package com.lensalpr.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lensalpr.app.settings.ScanConfig
import java.util.concurrent.Executor

data class LensState(
    val step: ZoomStep,
    val transitioning: Boolean,
    val verification: LensVerifier.Snapshot,
    val appliedZoom: Float,
)

/**
 * Owns the CameraX session and the physical-lens proof.
 *
 * Design decisions that matter for correctness:
 *  * the rear **logical** camera stays bound for the whole session and lens changes are requested
 *    with `setZoomRatio`, which is the route Samsung's HAL honours; no rebind means the 1x -> 3x ->
 *    5x -> 10x rotation never tears down the stream;
 *  * a single [LensVerifier] instance is installed as the Camera2 session capture callback and is
 *    re-targeted on every step, so the proof survives the rotation;
 *  * recognition is gated by [FrameGate], never by optimism;
 *  * every asynchronous answer — the provider future, the zoom future, the verifier — is checked
 *    against the controller's own [closed] flag and the generation it was asked for, so a callback
 *    that lands after `/stop` or after the next lens change cannot touch the shared gate.
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val setup: RearCameraSetup,
    private val config: ScanConfig,
    private val gate: FrameGate,
    private val analysisExecutor: Executor,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onLensState: (LensState) -> Unit,
    private val onStepSettled: (ZoomStep, Long) -> Unit,
    private val onError: (String) -> Unit,
    /** Optional clip recorder bound into the same session. */
    private val videoRecorder: VideoRecorder? = null,
    /**
     * First generation number this controller hands to the gate. A controller built for `/go`
     * after `/stop` must continue the numbering of the one it replaces, or the frame processor
     * sees the same generation twice and keeps the previous session's tracks alive.
     */
    initialGeneration: Long = 0L,
) {

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hardware = CameraHardwareIdentity.fromBuild()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    private var boundRoute: LensRoute? = null
    private var boundPhysicalId: String? = null

    /** Set by [shutdown]; every late callback checks it before touching shared state. */
    @Volatile
    private var closed = false

    /** Resolution the camera actually negotiated for analysis. */
    val analysisResolution: android.util.Size?
        get() = analysis?.resolutionInfo?.resolution

    private var generation = initialGeneration
    private var settledGeneration = -1L
    private var appliedZoom = 1f

    /** The last generation this controller handed to the gate; the next one continues from it. */
    val currentGeneration: Long get() = generation

    /** What the operator asked of the torch; re-applied after every (re)bind. */
    private var torchWanted = false

    var currentStep: ZoomStep? = null
        private set

    val verifier = LensVerifier(
        listenerExecutor = mainExecutor,
        listener = ::onVerification,
    )

    /**
     * Resolves the camera provider and reports back on the main thread.
     *
     * The first [apply] performs the bind, so the rotation scheduler stays the single owner of
     * which lens is active - including the very first one.
     */
    fun prepare(onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                // A session stopped from Telegram while the provider was still resolving must not
                // bind a camera nobody asked for any more.
                if (closed) return@addListener
                val provider = runCatching { future.get() }.getOrElse { error ->
                    Log.e(TAG, "Camera provider unavailable", error)
                    onError(error.message ?: error.javaClass.simpleName)
                    return@addListener
                }
                cameraProvider = provider
                onReady()
            },
            mainExecutor,
        )
    }

    /** Moves the session to [step]; rebinds only when the routing really changes. */
    fun apply(step: ZoomStep, forceRebind: Boolean = false) {
        if (closed) return
        val provider = cameraProvider ?: return
        val route = PhysicalLensRoutingPolicy.route(
            hardware = hardware,
            step = step,
            allowDirectPhysical = config.allowDirectPhysical,
            outputSurfaceCount = OUTPUT_SURFACES,
        )
        val needsRebind = forceRebind ||
            camera == null ||
            route != boundRoute ||
            (route == LensRoute.DIRECT_PHYSICAL_OUTPUT && step.expectedPhysicalId != boundPhysicalId)

        currentStep = step
        generation += 1
        val generation = this.generation
        gate.beginTransition(generation, step.label)
        // The same number applyZoom is about to request, so the verifier checks the camera's answer
        // against what was actually asked for rather than against the logical step.
        verifier.retarget(step, generation, PhysicalLensRoutingPolicy.controlZoomRatio(route, step))
        publishState(step)

        // Posted before the bind: a session that fails to bind must still leave the verifier in
        // a terminal state, not "verifying" forever on a camera that is not there.
        mainHandler.postDelayed({ if (!closed) verifier.checkTimeout() }, verifier.timeoutMs + 150L)
        if (needsRebind && !bind(provider, step, route)) {
            // Nothing is bound: there is no camera to zoom and no metadata to wait for. Settle the
            // step so the rotation keeps moving; the stall watchdog will ask for another rebind.
            settle(generation, force = true)
            return
        }
        applyZoom(step, route, generation)

        // A HAL that answers with steady metadata still needs a moment to settle the crop; a hard
        // ceiling keeps the rotation moving even when no terminal verification state arrives.
        mainHandler.postDelayed({ settle(generation, force = true) }, SETTLE_CEILING_MS)
    }

    /**
     * Rebuilds the session on the current step. Used when frames stop arriving: the HAL can drop a
     * stream after a thermal event or an app switch and never report it, and a rebind is the only
     * thing that brings it back.
     */
    fun rebind(): Boolean {
        val step = currentStep ?: return false
        Log.w(TAG, "rebinding session on ${step.label}")
        apply(step, forceRebind = true)
        return true
    }

    /**
     * Binds the use cases for [step]. Returns false when nothing could be bound.
     *
     * The old session is forgotten *before* it is torn down: a failed bind used to leave the
     * fields pointing at a camera CameraX had already unbound, so the next step saw
     * `needsRebind == false` and sent its zoom to a dead session for the rest of the drive.
     */
    private fun bind(provider: ProcessCameraProvider, step: ZoomStep, route: LensRoute): Boolean {
        runCatching { analysis?.clearAnalyzer() }
        camera = null
        analysis = null
        preview = null
        boundRoute = null
        boundPhysicalId = null
        runCatching { provider.unbindAll() }

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_90
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    config.resolution.size,
                    // Treat the setting as a ceiling: silently jumping from a requested 1440p to
                    // 4K triples the per-frame cost without the operator ever asking for it.
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        // Preview and analysis must agree on the aspect ratio, otherwise they see different fields
        // of view and the overlay boxes drift away from the vehicles.
        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(rotation)

        Camera2Interop.Extender(analysisBuilder).apply {
            setSessionCaptureCallback(verifier)
            // Video-mode focus rather than stills: it hunts far less, and a hunting lens on a rear
            // window - where the glass itself is a plausible focus target - costs whole seconds of
            // unreadable frames every time the traffic behind changes depth.
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
            // Digital stabilisation would crop and shift the frame per-frame, which breaks both the
            // overlay geometry and the remembered plate position; the optical one is a genuine
            // sharpness win on the long lens and moves nothing in software.
            setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            // Both of the following are asked for only when the device says it supports them: an
            // unsupported capture request can make the whole session fail to configure, and a
            // camera that will not open is worse than any amount of motion blur.
            if (capabilities.opticalStabilization) {
                setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }
            // Pinning the frame rate pins the exposure time to at most a thirtieth of a second.
            // Motion blur on a plate is a function of exposure, not of focus, and at speed it is
            // the difference between characters and a smear; the cost is a noisier frame.
            capabilities.steadyFpsRange?.let { range ->
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            )
            setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_FAST,
            )
            if (route == LensRoute.DIRECT_PHYSICAL_OUTPUT) {
                step.expectedPhysicalId?.let(::setPhysicalCameraId)
            }
        }
        if (route == LensRoute.DIRECT_PHYSICAL_OUTPUT) {
            step.expectedPhysicalId?.let {
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it)
            }
        }

        val newPreview = previewBuilder.build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val newAnalysis = analysisBuilder.build().apply {
            setAnalyzer(analysisExecutor, analyzer)
        }

        fun group(withVideo: Boolean): UseCaseGroup {
            val builder = UseCaseGroup.Builder()
                .addUseCase(newPreview)
                .addUseCase(newAnalysis)
            if (withVideo) videoRecorder?.buildUseCase(rotation)?.let(builder::addUseCase)
            previewView.viewPort?.let(builder::setViewPort)
            return builder.build()
        }

        // Not every device allows preview + analysis + video at these resolutions. If the camera
        // refuses the combination, recognition matters more than the clip, so retry without it.
        var bound = runCatching {
            provider.bindToLifecycle(lifecycleOwner, selector(), group(videoRecorder != null))
        }.getOrElse { error ->
            Log.w(TAG, "bindToLifecycle failed with video", error)
            null
        }
        if (bound == null && videoRecorder != null) {
            Log.w(TAG, "camera refused the video use case; continuing without clips")
            videoRecorder.detach()
            bound = runCatching {
                provider.bindToLifecycle(lifecycleOwner, selector(), group(false))
            }.getOrElse { error ->
                Log.e(TAG, "bindToLifecycle failed", error)
                null
            }
        }

        if (bound == null) {
            newAnalysis.clearAnalyzer()
            onError("bindToLifecycle failed")
            return false
        }

        preview = newPreview
        analysis = newAnalysis
        camera = bound
        boundRoute = route
        boundPhysicalId = if (route == LensRoute.DIRECT_PHYSICAL_OUTPUT) step.expectedPhysicalId else null
        // CameraX resets every control on unbind, the torch included; the operator's choice
        // survives the rebind only if it is asked for again.
        if (torchWanted) runCatching { bound.cameraControl.enableTorch(true) }

        Log.i(
            TAG,
            "Bound logical=${setup.logicalId} route=$route physical=${step.expectedPhysicalId} " +
                "analysis=${newAnalysis.resolutionInfo?.resolution}",
        )
        return true
    }

    private fun applyZoom(step: ZoomStep, route: LensRoute, generation: Long) {
        val bound = camera ?: return
        val requested = PhysicalLensRoutingPolicy.controlZoomRatio(route, step)
        val zoomState = bound.cameraInfo.zoomState.value
        val ratio = if (zoomState != null) {
            requested.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            requested
        }
        appliedZoom = ratio
        val future = bound.cameraControl.setZoomRatio(ratio)
        future.addListener(
            {
                // CameraX cancels a zoom request the moment the next one is issued, and the
                // cancellation arrives after the next step has already reopened the gate. Only the
                // answer to the request that is still current may touch the gate.
                if (closed || generation != this.generation || bound !== camera) return@addListener
                val failure = runCatching { future.get() }.exceptionOrNull()
                if (failure != null) {
                    // The lens never moved, so whatever arrives next is not the step that was asked
                    // for. In strict mode the gate then keeps the frames out rather than crediting
                    // them to optics they were not taken with.
                    Log.w(TAG, "setZoomRatio($ratio) failed", failure)
                    gate.updateLens(usable = false, settled = true)
                } else {
                    appliedZoom = bound.cameraInfo.zoomState.value?.zoomRatio ?: ratio
                }
                currentStep?.let(::publishState)
            },
            mainExecutor,
        )
    }

    /** What this particular camera will actually accept, asked once and cached. */
    private class CaptureCapabilities(
        val opticalStabilization: Boolean,
        val steadyFpsRange: android.util.Range<Int>?,
    )

    private val capabilities: CaptureCapabilities by lazy { readCapabilities() }

    private fun readCapabilities(): CaptureCapabilities {
        val chars = runCatching {
            val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            manager?.getCameraCharacteristics(setup.logicalId)
        }.getOrNull() ?: return CaptureCapabilities(false, null)

        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON }
            ?: false
        // A fixed range is what caps the exposure; a variable one lets the camera slow down again.
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            // Exactly thirty: a fixed sixty would halve the exposure again at the cost of doubling
            // the thermal load on a phone that is already the warmest thing in the car.
            ?.firstOrNull { it.lower == STEADY_FPS && it.upper == STEADY_FPS }
        Log.i(TAG, "capture capabilities: ois=$ois fps=$fps")
        return CaptureCapabilities(ois, fps)
    }

    private fun onVerification(snapshot: LensVerifier.Snapshot) {
        if (closed || snapshot.generation != generation) return
        val settled = when (snapshot.state) {
            LensVerifier.State.VERIFIED,
            LensVerifier.State.UNSUPPORTED,
            LensVerifier.State.TIMED_OUT,
            LensVerifier.State.FAILED,
            -> true

            else -> false
        }
        gate.updateLens(usable = snapshot.isUsable, settled = settled)
        currentStep?.let(::publishState)
        if (settled) settle(snapshot.generation, force = false)
    }

    private fun settle(generation: Long, force: Boolean) {
        if (closed || generation != this.generation || settledGeneration == generation) return
        if (force) gate.updateLens(usable = gate.lensUsable, settled = true)
        settledGeneration = generation
        currentStep?.let {
            // Without this the HUD can stay on "switching" forever: a lens that never confirms
            // produces no further verifier updates to render.
            publishState(it)
            onStepSettled(it, generation)
        }
    }

    private fun publishState(step: ZoomStep) {
        if (closed) return
        onLensState(
            LensState(
                step = step,
                transitioning = gate.transitioning,
                verification = verifier.snapshot,
                appliedZoom = appliedZoom,
            ),
        )
    }

    private fun selector(): CameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos ->
            val match = infos.filter { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId == setup.logicalId }
                    .getOrDefault(false)
            }
            match.ifEmpty { infos }
        }
        .build()

    /**
     * The activity is locked to landscape, but the user can still flip the phone end for end.
     * Following the display keeps the analysis buffer the right way up without recreating anything.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val display = previewView.display ?: return
            if (display.displayId != displayId) return
            val rotation = display.rotation
            preview?.targetRotation = rotation
            analysis?.targetRotation = rotation
            // The clip must follow too, or a phone flipped end for end films upside down.
            videoRecorder?.videoCapture?.targetRotation = rotation
        }
    }

    init {
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, mainHandler)
    }

    fun setTorch(enabled: Boolean) {
        torchWanted = enabled
        runCatching { camera?.cameraControl?.enableTorch(enabled) }
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun shutdown() {
        closed = true
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { analysis?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        analysis = null
        preview = null
        boundRoute = null
        boundPhysicalId = null
    }

    private companion object {
        /** Lowest fixed frame rate worth pinning; anything slower means a longer exposure. */
        const val STEADY_FPS = 30

        const val TAG = "LensALPR.Camera"

        /** Preview + ImageAnalysis. */
        const val OUTPUT_SURFACES = 2
        const val SETTLE_CEILING_MS = 2_000L
    }
}
