package com.lensalpr.app.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-track recognition bookkeeping shared between the analysis thread (which schedules OCR work)
 * and the main thread (which owns the consensus result).
 *
 * Each field has exactly one meaningful writer at a time, so plain volatile fields are enough and
 * the frame path never blocks on a lock.
 */
class TrackRuntime {
    /** Analysis thread: a crop of this track is currently in the OCR queue. */
    @Volatile
    var inFlight: Boolean = false

    /** Analysis thread: when the last crop was submitted. */
    @Volatile
    var lastSubmitMs: Long = 0L

    @Volatile
    var attempts: Int = 0

    /** Best focus score observed for this vehicle, used to reject visibly blurred repeats. */
    @Volatile
    var bestQuality: Float = 0f

    /** Main thread: plate that reached the consensus threshold for this track. */
    @Volatile
    var confirmedPlate: String? = null

    /** Main thread: best candidate so far and how many identical reads it has. */
    @Volatile
    var pendingPlate: String? = null

    @Volatile
    var pendingCount: Int = 0

    @Volatile
    var makeModel: String? = null

    /**
     * Main thread: where the plate sat inside the vehicle box on the last successful read, as
     * (left, top, right, bottom) fractions of that box. The analysis thread uses it to cut the next
     * crop around the plate instead of the whole car.
     */
    @Volatile
    var plateAnchor: FloatArray? = null

    /**
     * When the anchor was learned, on the elapsed-realtime clock — the same clock the analysis
     * thread ages it against. It used to be stamped with wall-clock time, and the difference of
     * the two clocks meant the anchor never expired at all.
     */
    @Volatile
    var anchorAtMs: Long = 0L
}

class RecognitionState {
    private val runtimes = ConcurrentHashMap<Int, TrackRuntime>()

    fun of(trackId: Int): TrackRuntime = runtimes.getOrPut(trackId) { TrackRuntime() }

    fun peek(trackId: Int): TrackRuntime? = runtimes[trackId]

    fun remove(trackId: Int) {
        runtimes.remove(trackId)
    }

    /** Visits every live track's bookkeeping; used when a plate is re-keyed under the tracks. */
    fun forEach(action: (Int, TrackRuntime) -> Unit) {
        runtimes.forEach { (id, runtime) -> action(id, runtime) }
    }

    fun clear() {
        runtimes.clear()
    }
}
