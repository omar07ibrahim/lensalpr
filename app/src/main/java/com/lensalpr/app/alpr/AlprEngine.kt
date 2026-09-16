package com.lensalpr.app.alpr

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.lensalpr.app.settings.ScanConfig
import org.buyun.alpr.sdk.AlprResult
import org.buyun.alpr.sdk.AlprSdk
import org.buyun.alpr.sdk.SDK_IMAGE_TYPE
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide owner of the native ALPR engine (plate OCR + make/model/colour classifiers).
 *
 * The native SDK keeps its state in process globals: exactly one initialization, and one call at a
 * time. Both invariants are enforced here so the camera pipeline can treat recognition as an
 * ordinary suspend-free function call from its worker thread.
 */
object AlprEngine {

    enum class State { NOT_STARTED, INITIALIZING, READY, ERROR }

    data class Status(
        val state: State,
        val code: Int = 0,
        val message: String? = null,
        val runtimeLimited: Boolean = false,
        /** How many times the runtime limit has been hit since the process started. */
        val limitHits: Int = 0,
    ) {
        val isReady: Boolean get() = state == State.READY && !runtimeLimited
    }

    private const val TAG = "LensALPR.Engine"

    /** Runtime entitlement exhausted; the engine refuses further frames until restarted. */
    const val CODE_RUNTIME_LIMIT = 20004

    /**
     * Activation material shipped with the vendor sample this project builds on. Replace it with a
     * licensed key for anything beyond evaluation.
     */
    private const val ACTIVATION_KEY =
        "o0KjpoZaeHqarAF6N1qL76CVoJ4oP5L6OQg6pDw2kPXMRxkzrL1hyb8IkiPkAFTaaAOVtvMxgQElsY" +
            "43tza5KZcT9CD+fxEHZvsfvsst/tAMOfq9U+Ypa8sHjQN6QCE5bCjw24CUdXWpRWWtLxRELoCoy38M" +
            "bHzlhlAf6NN7ncof5faY7J1QnHP+Rt2Xw9shRCzDPuEQZm7TeNdVT7QFfvIrIsroHJfa/HIXUdjx6l" +
            "wAHc+4Wq3XgU8+bHY5mtq6rMR5Pz/Fu5iqNyfzTBMMMfyyjZNUVa+N90eX5NdARbLofkkK1fN4enBb" +
            "d7tgnlEU7l2tx85BH4hEZGq8dnNmag=="

    private val engineLock = ReentrantLock(true)
    private val stateLock = Any()
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val initExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alpr-init")
    }

    @Volatile
    var status: Status = Status(State.NOT_STARTED)
        private set

    /** Engine settings the current native instance was initialized with. */
    @Volatile
    private var activeConfig: String? = null

    /**
     * Whether the SDK holds a native instance at all — regardless of whether that instance is
     * still usable.
     *
     * Kept apart from [activeConfig] on purpose. The runtime limit clears the config so that a
     * plain [initialize] counts as a repair, but the native side is still initialized and its
     * `init` short-circuits on an existing instance: without an explicit `deInit` first the "new"
     * engine was the same exhausted one, reported READY, and refused the next frame.
     */
    @Volatile
    private var nativeInitialized = false

    /**
     * A configuration requested while another initialization was still running.
     *
     * The setup screen can change native parameters and reopen the scanner before the previous
     * `init` returns; returning early from [initialize] then left the old parameters in force and
     * the new screen's worker running against an engine it never asked for.
     */
    private var pendingRequested: String? = null

    /** Whether only the local plate layout is accepted; set from the scan configuration. */
    @Volatile
    private var strictLatvia: Boolean = false

    /**
     * Bumped by every successful initialization.
     *
     * A frame that entered the native call before a restart may return its verdict after it, and a
     * stale [CODE_RUNTIME_LIMIT] from the previous instance would immediately poison the fresh one.
     * The recognition path carries the generation it started under and the limit is only honoured
     * when the two still agree.
     */
    @Volatile
    private var generation: Int = 0

    /** When the current native instance became usable; the basis for preventive recycling. */
    @Volatile
    private var readySinceMs: Long = 0L

    /** How many times this process has hit the runtime limit. Shown to the operator, never reset. */
    @Volatile
    private var limitHits: Int = 0

    /**
     * Activation is done once per process and never repeated.
     *
     * `AlprSdk.setActivation` stores its return code in a static gate, and `init`, `process` and
     * `warmUp` all hand back an `AlprResult` wrapping the native pointer -1 whenever that gate is
     * non-zero — calling `code()` on it dereferences -1. So a second activation that fails for any
     * reason would not merely fail to restart the engine, it would brick the SDK for the rest of
     * the process and crash on the first frame afterwards. The native side stays activated across
     * deInit/init, so the restart path simply skips it.
     */
    @Volatile
    private var activated = false

    /**
     * How long the running instance has been recognizing, or zero when it is not up.
     *
     * The vendor entitlement is spent by wall-clock time inside one native session, so this is the
     * number that decides whether the engine should be recycled while nothing is behind us — long
     * before it dies in the middle of a tail.
     */
    fun uptimeMs(): Long {
        val since = readySinceMs
        return if (since == 0L || !status.isReady) 0L else SystemClock.elapsedRealtime() - since
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners += listener
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners -= listener
    }

    /**
     * Starts the engine, or restarts it when the requested settings differ from the running
     * instance. Recognition options such as deep search or the VMMR classifiers are native
     * initialization parameters, so a changed setup screen must reach the engine.
     *
     * [force] tears the native instance down and builds it again even when the settings are
     * unchanged. That is the only way back from [CODE_RUNTIME_LIMIT]: the entitlement is spent per
     * native session, so a session that has run out is worthless and a new one is the repair.
     */
    fun initialize(context: Context, config: ScanConfig, force: Boolean = false) {
        val assets = context.applicationContext.assets
        val requested = engineConfig(config).toString()
        strictLatvia = config.strictPlateFormat
        synchronized(stateLock) {
            if (status.state == State.INITIALIZING) {
                // Remembered, not dropped: the running init finishes with the parameters it was
                // given and then immediately applies these.
                if (force || requested != activeConfig) pendingRequested = requested
                return
            }
            if (!force && status.isReady && activeConfig == requested) return
            if (force) activeConfig = null
            // A fresh Status, never a copy: the copy would carry runtimeLimited into the new
            // instance and lock it out again before it has read a single frame.
            publish(Status(State.INITIALIZING, limitHits = limitHits))
        }
        initExecutor.execute { runInit(assets, requested, force) }
    }

    /** One initialization on the init thread, followed by whatever was requested meanwhile. */
    private fun runInit(assets: AssetManager, requested: String, force: Boolean) {
        var next = performInit(assets, requested, force)
        while (true) {
            val pending = synchronized(stateLock) {
                val queued = pendingRequested
                pendingRequested = null
                if (queued != null && (queued != activeConfig || next.state != State.READY)) queued else null
            }
            if (pending == null) break
            Log.i(TAG, "settings changed during initialization; applying the newer ones")
            next = performInit(assets, pending, force = true)
        }
        publish(next)
    }

    private fun performInit(assets: AssetManager, requested: String, force: Boolean): Status =
        runCatching {
            engineLock.withLock {
                if (nativeInitialized) {
                    Log.i(TAG, if (force) "forced engine restart" else "engine settings changed; restarting")
                    runCatching { AlprSdk.deInit()?.delete() }
                    nativeInitialized = false
                    activeConfig = null
                }
                if (!activated) {
                    val activation = AlprSdk.setActivation(ACTIVATION_KEY)
                    if (activation != 0) {
                        return@withLock Status(
                            State.ERROR,
                            code = activation,
                            message = "activation failed",
                            limitHits = limitHits,
                        )
                    }
                    activated = true
                }
                val result = AlprSdk.init(assets, requested, null)
                try {
                    if (result.code() == 0) {
                        Log.i(TAG, "engine ready: ${result.phrase()}")
                        nativeInitialized = true
                        activeConfig = requested
                        // Anything still in flight from the previous instance can no longer
                        // report a limit against this one.
                        generation += 1
                        readySinceMs = SystemClock.elapsedRealtime()
                        warmUp()
                        Status(State.READY, limitHits = limitHits)
                    } else {
                        Log.e(TAG, "engine init failed: ${result.code()} ${result.phrase()}")
                        Status(State.ERROR, result.code(), result.phrase(), limitHits = limitHits)
                    }
                } finally {
                    result.delete()
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "engine init crashed", error)
            Status(
                State.ERROR,
                message = error.message ?: error.javaClass.simpleName,
                limitHits = limitHits,
            )
        }

    /**
     * Pays the lazy model loading up front. Best effort: on some builds of the vendor AAR the
     * Java wrapper and the native symbol disagree and the call cannot even be linked. That is
     * worth a line in the log, not a failed start — the first real frame simply pays instead.
     */
    private fun warmUp() {
        val outcome = runCatching {
            val result = AlprSdk.warmUp(SDK_IMAGE_TYPE.ULTALPR_SDK_IMAGE_TYPE_RGBA32)
            val code = result?.code() ?: -1
            result?.delete()
            code
        }
        outcome.onFailure { error -> Log.w(TAG, "warm-up unavailable: ${error.javaClass.simpleName}: ${error.message}") }
        outcome.onSuccess { code -> if (code != 0) Log.w(TAG, "warm-up returned $code") }
    }

    /**
     * Rebuilds the native instance after the runtime entitlement ran out.
     *
     * Returns false when there is nothing to repair, so the caller does not count an attempt it
     * never made.
     */
    fun recoverFromRuntimeLimit(context: Context, config: ScanConfig): Boolean {
        synchronized(stateLock) {
            if (!status.runtimeLimited) return false
        }
        Log.w(TAG, "recovering from runtime limit (hit #$limitHits)")
        initialize(context, config, force = true)
        return true
    }

    /**
     * Recycles a healthy engine before its entitlement runs out.
     *
     * The limit arrives without warning in the middle of a drive; taking the engine down on purpose
     * while nothing is behind us costs a couple of seconds of blindness at a moment when there is
     * nothing to see, instead of the rest of the trip at a moment when there is.
     */
    fun recycle(context: Context, config: ScanConfig) {
        if (!status.isReady) return
        Log.i(TAG, "preventive recycle after ${uptimeMs() / 60_000} min")
        initialize(context, config, force = true)
    }

    /**
     * Restarts the engine with [config] on the calling thread and returns once it is usable.
     *
     * Only the benchmark needs this: it has to step through several configurations in order, and
     * the asynchronous [initialize] path would race with its own measurements.
     */
    fun reinitializeBlocking(context: Context, config: ScanConfig): Boolean {
        val assets = context.applicationContext.assets
        val requested = engineConfig(config).toString()
        strictLatvia = config.strictPlateFormat
        // isReady rather than the state: a runtime-limited engine would otherwise report success
        // here and the benchmark would silently measure an engine that refuses every frame.
        if (status.isReady && activeConfig == requested) return true
        val next = performInit(assets, requested, force = true)
        publish(next)
        return next.state == State.READY
    }

    /**
     * Recognizes one RGBA8888 image.
     *
     * [buffer] must be a direct buffer holding [height] rows of [strideInPixels] pixels; the crop
     * pipeline always hands over tightly packed data.
     */
    fun process(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        strideInPixels: Int = width,
    ): AlprOutcome {
        if (!status.isReady) {
            return AlprOutcome.failure(-1, "engine not ready", 0L)
        }
        // Read before the native call: the instance this frame belongs to is the one that may be
        // declared out of entitlement, not whichever instance happens to be current on return.
        val gen = generation
        return engineLock.withLock {
            // Re-checked under the lock: an initialization that ran between the check above and
            // taking the lock has replaced the instance this frame was meant for.
            if (!status.isReady) return AlprOutcome.failure(-1, "engine not ready", 0L)
            val started = SystemClock.elapsedRealtimeNanos()
            var result: AlprResult? = null
            try {
                result = AlprSdk.process(
                    SDK_IMAGE_TYPE.ULTALPR_SDK_IMAGE_TYPE_RGBA32,
                    buffer,
                    width.toLong(),
                    height.toLong(),
                    strideInPixels.toLong(),
                    EXIF_TOP_LEFT,
                )
                val latency = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                val code = result.code()
                if (code != 0) {
                    if (code == CODE_RUNTIME_LIMIT) markRuntimeLimited(gen)
                    AlprOutcome.failure(code, result.phrase(), latency)
                } else {
                    val (plates, cars) = AlprJson.parse(result.json(), strictLatvia)
                    AlprOutcome(true, code, result.phrase(), plates, cars, latency)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "process failed", error)
                AlprOutcome.failure(
                    -2,
                    error.message ?: error.javaClass.simpleName,
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L,
                )
            } finally {
                runCatching { result?.delete() }
            }
        }
    }

    /**
     * The native instance [gen] has spent its runtime entitlement.
     *
     * Under [stateLock] and guarded by the generation, because this is called from the recognition
     * thread while another thread may already be building a replacement: without both, a verdict
     * from the instance that just died would land on the one that just came up and lock the engine
     * out permanently. [activeConfig] is cleared so a plain `initialize` is enough to repair it;
     * [nativeInitialized] is deliberately left set so that repair tears the dead instance down.
     */
    private fun markRuntimeLimited(gen: Int) {
        synchronized(stateLock) {
            if (gen != generation) return
            if (status.state != State.READY || status.runtimeLimited) return
            limitHits += 1
            activeConfig = null
            readySinceMs = 0L
            Log.w(TAG, "engine hit runtime limit $CODE_RUNTIME_LIMIT (hit #$limitHits)")
            publish(status.copy(runtimeLimited = true, code = CODE_RUNTIME_LIMIT, limitHits = limitHits))
        }
    }

    private fun publish(next: Status) {
        status = next
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    /**
     * Engine configuration.
     *
     * The pipeline feeds tight vehicle crops rather than whole frames, so the pyramidal search is
     * allowed to engage on much smaller images than the vendor default and the car detector stays
     * on to keep make/model/colour available even when a plate cannot be read.
     */
    private fun engineConfig(config: ScanConfig): JSONObject = JSONObject().apply {
        put("debug_level", "warn")
        put("debug_write_input_image_enabled", false)
        put("num_threads", -1)
        put("gpgpu_enabled", true)
        put("charset", "latin")
        put("max_latency", -1)
        put("ienv_enabled", false)
        put("openvino_enabled", false)
        put("detect_minscore", 0.1)
        put("detect_quantization_enabled", true)
        put("detect_roi", JSONArray(listOf(0f, 0f, 0f, 0f)))
        put("car_noplate_detect_enabled", true)
        put("car_noplate_detect_min_score", 0.6)
        put("pyramidal_search_enabled", config.deepSearch)
        put("pyramidal_search_sensitivity", 0.28)
        put("pyramidal_search_minscore", 0.5)
        put("pyramidal_search_min_image_size_inpixels", 320)
        put("pyramidal_search_quantization_enabled", true)
        put("klass_lpci_enabled", true)
        put("klass_vcr_enabled", config.vmmr)
        put("klass_vmmr_enabled", config.vmmr)
        put("klass_vbsr_enabled", config.vmmr)
        put("klass_vcr_gamma", 1.5)
        put("recogn_minscore", (config.minOcrScore / 100.0).coerceIn(0.05, 0.95))
        put("recogn_score_type", "min")
        put("recogn_rectify_enabled", config.rectifyPlates)
        put("recogn_quantization_enabled", true)
    }

    /** The analysis buffer is already display-oriented, so no EXIF rotation is applied. */
    private const val EXIF_TOP_LEFT = 1
}
