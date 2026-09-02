package com.lensalpr.app.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Proves that the vendor camera stack really activated the requested optical module.
 *
 * Asking CameraX for a zoom ratio is a request, not a fact: a HAL is free to answer a 5x request
 * with a digital crop of the main sensor. This callback reads the per-frame Camera2 metadata and
 * only reports [State.VERIFIED] after [requiredConsecutiveFrames] frames that never contradict the
 * expected module: `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` must not name a different camera, and
 * the immutable `LENS_FOCAL_LENGTH` must match. A digital crop cannot fake the focal length of a
 * periscope lens, so the focal check alone still proves the optics on a stack that reports no
 * active physical id.
 *
 * A single instance is reused for the whole session: [retarget] switches the expectation when the
 * rotation plan moves to another step, so the Camera2 session callback never has to be re-bound.
 */
class LensVerifier(
    private val requiredConsecutiveFrames: Int = 10,
    private val focalToleranceMm: Float = 0.8f,
    /** How far the reported zoom may sit from the requested one before the step is not that step. */
    private val zoomTolerance: Float = 0.05f,
    val timeoutMs: Long = 5_000L,
    private val mismatchLimit: Int = 150,
    private val listenerExecutor: Executor,
    private val listener: (Snapshot) -> Unit,
) : CameraCaptureSession.CaptureCallback() {

    enum class State {
        /** No expectation set yet. */
        IDLE,

        /** Collecting matching frames. */
        VERIFYING,

        /** The requested module has been proven active. */
        VERIFIED,

        /** Metadata contradicts the request (usually a digital crop of another module). */
        MISMATCH,

        /** Too many contradicting frames in a row. */
        FAILED,

        /** No proof arrived within [timeoutMs]. */
        TIMED_OUT,

        /** The device cannot be checked (no physical children on the logical camera). */
        UNSUPPORTED,
    }

    enum class Reason {
        NONE,
        ACTIVE_ID_MISMATCH,
        FOCAL_MISSING,
        FOCAL_MISMATCH,
        CAPTURE_FAILED,

        /** The camera settled on a different zoom ratio than the step asked for. */
        ZOOM_MISMATCH,
        TIMEOUT,
        NO_PHYSICAL_MODULE,
    }

    data class Snapshot(
        val generation: Long,
        val state: State,
        val reason: Reason,
        val expectedPhysicalId: String?,
        val expectedFocalMm: Float?,
        val observedPhysicalId: String?,
        val observedFocalMm: Float?,
        val matchingFrames: Int,
        val requiredFrames: Int,
        val elapsedMs: Long,
    ) {
        val isVerified: Boolean get() = state == State.VERIFIED

        /** Frames may be recognized: either proven, or unverifiable hardware. */
        val isUsable: Boolean get() = state == State.VERIFIED || state == State.UNSUPPORTED
    }

    private val lock = Any()

    private var generation: Long = 0L
    private var expectedPhysicalId: String? = null
    private var expectedFocalMm: Float? = null
    private var expectedZoom: Float? = null
    private var startedAtMs: Long = SystemClock.elapsedRealtime()
    private var matches: Int = 0
    private var mismatches: Int = 0
    private var verifiedOnce: Boolean = false
    private var published: Snapshot? = null

    @Volatile
    var snapshot: Snapshot = Snapshot(
        generation = 0L,
        state = State.IDLE,
        reason = Reason.NONE,
        expectedPhysicalId = null,
        expectedFocalMm = null,
        observedPhysicalId = null,
        observedFocalMm = null,
        matchingFrames = 0,
        requiredFrames = requiredConsecutiveFrames,
        elapsedMs = 0L,
    )
        private set

    /**
     * Points the verifier at another step and restarts the proof from scratch.
     *
     * [appliedZoomRatio] is the ratio that was actually handed to `CameraControl`, which is not the
     * step's requested zoom on every route: once a physical camera is bound directly its own
     * coordinate system starts at 1.0 and only the digital crop is requested. Comparing the
     * camera's answer against the logical figure on that route can never match, and the mismatch
     * holds the frame gate shut for the whole session — the camera runs, the preview is live, and
     * nothing is ever recognised.
     */
    fun retarget(step: ZoomStep, generation: Long, appliedZoomRatio: Float) {
        val next = synchronized(lock) {
            this.generation = generation
            expectedPhysicalId = step.expectedPhysicalId
            expectedFocalMm = step.expectedFocalMm
            expectedZoom = appliedZoomRatio
            startedAtMs = SystemClock.elapsedRealtime()
            matches = 0
            mismatches = 0
            verifiedOnce = false
            val unsupported = step.module == null
            update(
                state = if (unsupported) State.UNSUPPORTED else State.VERIFYING,
                reason = if (unsupported) Reason.NO_PHYSICAL_MODULE else Reason.NONE,
                observedId = null,
                observedFocal = null,
            )
        }
        publish(next)
    }

    /** Advances the deadline even when the session delivers no metadata at all. */
    fun checkTimeout() {
        val next = synchronized(lock) {
            val current = snapshot
            if (current.state == State.IDLE ||
                current.state == State.UNSUPPORTED ||
                verifiedOnce ||
                SystemClock.elapsedRealtime() - startedAtMs < timeoutMs
            ) {
                null
            } else {
                update(State.TIMED_OUT, Reason.TIMEOUT, current.observedPhysicalId, current.observedFocalMm)
            }
        }
        next?.let(::publish)
    }

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val next = synchronized(lock) { evaluate(result) } ?: return
        publish(next)
    }

    override fun onCaptureFailed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        failure: CaptureFailure,
    ) {
        val next = synchronized(lock) {
            if (expectedPhysicalId == null) null else mismatch(Reason.CAPTURE_FAILED, null, null)
        }
        next?.let(::publish)
    }

    private fun evaluate(result: TotalCaptureResult): Snapshot? {
        val expectedId = expectedPhysicalId ?: return null
        val expectedFocal = expectedFocalMm ?: return null

        // The optics can be right while the framing is not: on a digital step the module never
        // changes, so focal length alone would happily confirm 10x while the sensor is still being
        // asked for 5x. The zoom the camera reports back is the only proof of the framing.
        val expectedRatio = expectedZoom
        val appliedRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
        if (expectedRatio != null && appliedRatio != null && expectedRatio > 0f) {
            val drift = abs(appliedRatio - expectedRatio) / expectedRatio
            if (drift > zoomTolerance) {
                return mismatch(
                    Reason.ZOOM_MISMATCH,
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                    result.get(CaptureResult.LENS_FOCAL_LENGTH),
                )
            }
        }

        val activeId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val physicalResults = runCatching { result.physicalCameraTotalResults }
            .getOrDefault(emptyMap())
        // Present only when the session really has a physical output stream (direct route).
        val physicalResult = physicalResults[expectedId]

        // The vendor named the live module and it is not the one that was requested: a digital
        // crop of another sensor, exactly the case this class exists to catch.
        if (activeId != null && activeId != expectedId && physicalResult == null) {
            return mismatch(
                Reason.ACTIVE_ID_MISMATCH,
                activeId,
                result.get(CaptureResult.LENS_FOCAL_LENGTH),
            )
        }

        // Focal length is the immutable optical property of the module. It is the proof that still
        // holds when a HAL (or a directly bound physical camera) reports no active physical id at
        // all: no amount of cropping turns a 5.4 mm wide lens into an 18.6 mm periscope.
        val source = physicalResult ?: result
        val focal = source.get(CaptureResult.LENS_FOCAL_LENGTH)
        val observedId = activeId ?: physicalResult?.let { expectedId }

        if (focal == null || !focal.isFinite()) {
            return mismatch(Reason.FOCAL_MISSING, observedId, focal)
        }
        if (abs(focal - expectedFocal) > focalToleranceMm) {
            return mismatch(Reason.FOCAL_MISMATCH, observedId, focal)
        }

        // A late but correct answer still counts: the deadline in [checkTimeout] only unblocks the
        // rotation, it must never stop a slow HAL from proving the lens afterwards.
        mismatches = 0
        matches += 1
        if (matches >= requiredConsecutiveFrames) {
            verifiedOnce = true
            return update(State.VERIFIED, Reason.NONE, observedId, focal)
        }
        return update(State.VERIFYING, Reason.NONE, observedId, focal)
    }

    private fun mismatch(reason: Reason, observedId: String?, observedFocal: Float?): Snapshot {
        matches = 0
        mismatches += 1
        // A lens that was proven and then changed underneath us must invalidate recognition
        // immediately, not after the mismatch budget is exhausted.
        val state = if (mismatches >= mismatchLimit) State.FAILED else State.MISMATCH
        return update(state, reason, observedId, observedFocal)
    }

    private fun update(
        state: State,
        reason: Reason,
        observedId: String?,
        observedFocal: Float?,
    ): Snapshot {
        val next = Snapshot(
            generation = generation,
            state = state,
            reason = reason,
            expectedPhysicalId = expectedPhysicalId,
            expectedFocalMm = expectedFocalMm,
            observedPhysicalId = observedId,
            observedFocalMm = observedFocal,
            matchingFrames = matches,
            requiredFrames = requiredConsecutiveFrames,
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
        )
        snapshot = next
        return next
    }

    /**
     * Camera2 keeps delivering identical metadata at the sensor frame rate. Only evidence that can
     * change a UI or gating decision is forwarded.
     */
    private fun publish(next: Snapshot) {
        val shouldPublish = synchronized(lock) {
            val last = published
            val changed = last == null ||
                last.state != next.state ||
                last.reason != next.reason ||
                last.generation != next.generation ||
                last.observedPhysicalId != next.observedPhysicalId ||
                last.observedFocalMm != next.observedFocalMm
            if (changed) published = next
            changed
        }
        if (!shouldPublish) return
        runCatching { listenerExecutor.execute { listener(next) } }
    }
}
