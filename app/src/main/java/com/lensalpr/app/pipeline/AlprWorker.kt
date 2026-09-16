package com.lensalpr.app.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.lensalpr.app.alpr.AlprEngine
import com.lensalpr.app.alpr.AlprOutcome
import android.graphics.Bitmap.CompressFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One vehicle crop waiting for the OCR engine.
 *
 * [buffer] is a pooled direct buffer holding tightly packed RGBA8888 pixels; [thumbnail] is a small
 * copy of the same crop whose ownership moves to the result consumer.
 */
class OcrJob(
    val trackId: Int,
    val generation: Long,
    val submittedAtMs: Long,
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    /** Row length in pixels; equals [width] unless the bitmap row was padded. */
    val strideInPixels: Int,
    val sourceRect: Rect,
    val thumbnail: Bitmap,
    val lensLabel: String,
    val quality: Float,
    /** Scheduler ranking: unread vehicles and big boxes outrank already-confirmed ones. */
    val priority: Float = 0f,
    /**
     * Where and how far along the trip the crop was taken. Carried with the job so a recognition
     * that happens later - after a spill to disk - is still attributed to the right place.
     */
    val lat: Double? = null,
    val lon: Double? = null,
    /** NaN when the crop was taken without a position fix; zero would read as "went nowhere". */
    val odometerM: Double = Double.NaN,
    /**
     * The vehicle box this crop belongs to, in frame pixels. Plate positions are remembered
     * relative to it, so they keep pointing at the plate while the car moves and grows.
     */
    val anchorRect: Rect = sourceRect,
    /** True when only the plate region was cut instead of the whole vehicle. */
    val narrow: Boolean = false,
    /**
     * True for a crop that waited on disk and was read later. Its result may still add a vote,
     * but it says nothing about whether the *current* crop of the same track is done: the live
     * job clears its own busy flag when it comes back, and this one must not clear it early.
     */
    val deferred: Boolean = false,
)

/** Reuses direct buffers so a 30 fps crop pipeline does not allocate native memory per frame. */
class CropBufferPool(private val maxBuffers: Int = 8) {

    private val pool = ArrayDeque<ByteBuffer>(maxBuffers)

    @Synchronized
    fun acquire(bytes: Int): ByteBuffer {
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.capacity() >= bytes) {
                iterator.remove()
                candidate.clear()
                candidate.limit(bytes)
                return candidate
            }
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    fun release(buffer: ByteBuffer) {
        if (pool.size >= maxBuffers) return
        buffer.clear()
        pool.addLast(buffer)
    }

    @Synchronized
    fun clear() {
        pool.clear()
    }
}

/**
 * Serial OCR worker.
 *
 * The native engine is a single process-wide instance, so throughput comes from keeping its queue
 * full rather than from calling it concurrently: the analysis thread never blocks, stale crops from
 * a previous lens are dropped, and the engine itself parallelizes internally across cores.
 */
class AlprWorker(
    private val pool: CropBufferPool,
    private val queueCapacity: Int = 12,
    /**
     * A crop only ages out when the vehicle it shows is long gone. The pixels themselves never
     * expire: a plate photographed four seconds ago carries exactly the same characters.
     */
    private val staleAfterMs: Long = 5_000L,
    private val maxQueuedBytes: Long = 48L * 1024 * 1024,
    private val currentGeneration: () -> Long,
    private val onResult: (OcrJob, AlprOutcome) -> Unit,
    /**
     * A crop left the pipeline without ever reaching the engine.
     *
     * The scheduler marks a track as busy the moment it hands over a crop and only clears that mark
     * when a result comes back. Every path that drops a crop instead must say so, or the track is
     * silently excluded from recognition for as long as it stays in view — which, for a car that is
     * following us, is the entire time it matters.
     */
    private val onDropped: (OcrJob) -> Unit = {},
) {

    /** Most valuable crop first; among equals, the oldest, so nothing starves. */
    private val queue = PriorityBlockingQueue<OcrJob>(
        queueCapacity,
        compareByDescending<OcrJob> { it.priority }.thenBy { it.submittedAtMs },
    )
    private val queuedBytes = AtomicLong(0)
    private val dropped = AtomicInteger(0)
    /**
     * Bounded on purpose. Every queued task holds a pooled crop buffer of a few megabytes, so an
     * unbounded queue turns a slow disk into hundreds of megabytes of native memory and an OOM
     * exactly when traffic is heaviest.
     */
    private val spillExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SPILL_QUEUE_DEPTH),
        { runnable -> Thread(runnable, "alpr-spill") },
    )

    /** Set by the scanner; when present, an overflowing queue goes to disk instead of the bin. */
    @Volatile
    var spill: SpillStore? = null

    val spilledCount: Int get() = spill?.count ?: 0

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * Bumped on every [start]. A loop compares it with the value it was started with, so a loop
     * whose `stop()` timed out — the engine was mid-frame and cannot be interrupted — exits on its
     * own the moment a newer loop exists, instead of two threads draining one queue.
     */
    private val epoch = AtomicInteger(0)

    @Volatile
    var lastLatencyMs: Long = 0L
        private set

    /**
     * When the engine last *finished* a crop, on the elapsed-realtime clock. Zero until it has.
     *
     * This — not the gate, not the queue — is what proves recognition is working: the runtime
     * limit is only ever discovered inside a call, and frames flowing past an open gate on an
     * empty road prove nothing about the engine at all.
     */
    @Volatile
    var lastProcessedAtMs: Long = 0L
        private set

    /** When the current crop was handed to the engine, or zero while none is. */
    @Volatile
    var currentJobSinceMs: Long = 0L
        private set

    val queueDepth: Int get() = queue.size

    val isSaturated: Boolean
        get() = queue.size >= queueCapacity || queuedBytes.get() >= maxQueuedBytes

    /** False until the engine is ready; the frame path then skips cropping entirely. */
    val isRunning: Boolean get() = running

    val droppedJobs: Int get() = dropped.get()

    /**
     * While set, every crop handed to the engine is also written to disk.
     *
     * This is what makes the settings comparable: the same real crops can then be replayed through
     * each configuration offline, instead of pointing a hand-held phone at a plate four times.
     */
    @Volatile
    var captureDir: File? = null

    @Volatile
    var capturedCount: Int = 0
        private set

    fun startCapture(dir: File) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        capturedCount = 0
        captureDir = dir
    }

    fun stopCapture(): Int {
        captureDir = null
        return capturedCount
    }

    fun start() {
        if (running) return
        running = true
        val myEpoch = epoch.incrementAndGet()
        thread = Thread({ loop(myEpoch) }, "alpr-worker").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        running = false
        val worker = thread
        thread = null
        worker?.interrupt()
        // Wait for it to leave the engine. Closing the native session under a running process()
        // call is a use-after-free, and the only thing that guarantees it is not running is this.
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(WORKER_JOIN_MS) }
        }
        while (true) {
            val job = queue.poll() ?: break
            queuedBytes.addAndGet(-job.buffer.capacity().toLong())
            discard(job)
        }
        queuedBytes.set(0)
        pool.clear()
    }

    /**
     * Final teardown. Kept apart from [stop] because the session can be stopped and started again
     * from Telegram, and a shut-down executor would leave the restarted session unable to park
     * anything on disk.
     *
     * Orderly, not `shutdownNow`: a park task already accepted owns the only copy of its crop,
     * and the promise made to the scheduler was that it would be on disk for the next start.
     * The writer finishes the handful it holds on its own thread; nothing waits for it.
     */
    fun close() {
        stop()
        spillExecutor.shutdown()
    }

    /**
     * Queues a crop. Never blocks the caller.
     *
     * This runs on the camera's analysis thread, several times per frame. It used to sleep up to
     * 200 ms per crop while the engine caught up — which, with three crops a frame,
     * stalled analysis for the better part of a second exactly when traffic was heaviest. The
     * tracker predicts a box from one frame of velocity, so a gap that long moves every car
     * further than the IoU threshold tolerates: tracks break, identities are lost, and the car
     * that was being followed becomes a new unknown vehicle. Waiting to avoid dropping one crop
     * cost the thing the crops exist for.
     *
     * A crop with nowhere to go is parked on disk instead, which costs a delay rather than a loss.
     */
    fun submit(job: OcrJob): Boolean {
        if (!running) {
            discard(job)
            return false
        }
        val bytes = job.buffer.capacity().toLong()

        if (hasRoom(bytes)) {
            enqueue(job, bytes)
            return true
        }

        // Make room by displacing the least valuable crops — plural: freeing one slot does not
        // free enough *bytes* when the newcomer is a larger crop, and enqueueing on a count alone
        // let the queue grow past its memory budget exactly under the heaviest traffic.
        val displaced = ArrayList<OcrJob>(2)
        while (!hasRoom(bytes)) {
            val weakest = queue.minWithOrNull(
                compareBy<OcrJob> { it.priority }.thenByDescending { it.submittedAtMs },
            ) ?: break
            if (weakest.priority >= job.priority || !queue.remove(weakest)) break
            queuedBytes.addAndGet(-weakest.buffer.capacity().toLong())
            displaced += weakest
        }
        val store = spill
        if (hasRoom(bytes)) {
            enqueue(job, bytes)
            // The displaced crops go to disk when possible, so a close, unread car arriving while
            // the queue is full of distant re-reads costs those re-reads a delay, not their lives.
            displaced.forEach { evicted ->
                if (store == null || !park(store, evicted)) {
                    dropped.incrementAndGet()
                    discard(evicted)
                }
            }
            return true
        }
        // Nothing weaker to displace — put back whatever was taken out and park the newcomer.
        displaced.forEach { evicted -> enqueue(evicted, evicted.buffer.capacity().toLong()) }
        if (store != null && park(store, job)) return true
        dropped.incrementAndGet()
        discard(job)
        return false
    }

    /**
     * Reads one parked crop. Called when the live queue runs dry, which is exactly when the engine
     * has spare time, and on demand while the scanner is paused.
     *
     * Only while the engine is actually able to read: the store deletes a crop the moment it is
     * handed out, and feeding it to an engine that is restarting or out of entitlement destroyed
     * the whole backlog without a single plate coming back.
     */
    fun drainOneSpilled(): Boolean {
        val store = spill ?: return false
        if (!AlprEngine.status.isReady) return false
        val crop = store.poll() ?: return false
        val bitmap = crop.bitmap
        val stride = bitmap.rowBytes / 4
        // Pooled like every other crop: draining six hundred parked images must not allocate six
        // hundred direct buffers and hope the collector keeps up.
        val buffer = pool.acquire(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        currentJobSinceMs = SystemClock.elapsedRealtime()
        val outcome = try {
            AlprEngine.process(buffer, bitmap.width, bitmap.height, stride)
        } catch (error: Throwable) {
            Log.e(TAG, "deferred recognition failed", error)
            AlprOutcome.failure(-3, error.message, 0L)
        }
        currentJobSinceMs = 0L
        lastLatencyMs = outcome.latencyMs
        if (outcome.code != AlprEngine.CODE_RUNTIME_LIMIT) lastProcessedAtMs = SystemClock.elapsedRealtime()
        // The saved picture of the whole car when there is one; a narrow crop is a plate, not a
        // vehicle, and must not become the card's photograph.
        val thumbnail = crop.thumbnail ?: Bitmap.createScaledBitmap(
            bitmap,
            minOf(bitmap.width, THUMBNAIL_WIDTH),
            maxOf(1, bitmap.height * minOf(bitmap.width, THUMBNAIL_WIDTH) / bitmap.width),
            true,
        )
        val cropWidth = bitmap.width
        val cropHeight = bitmap.height
        // A crop already narrower than the thumbnail width comes back as the very same object, and
        // recycling it here would hand the card a dead bitmap that crashes the list minutes later.
        if (thumbnail !== bitmap) bitmap.recycle()

        val job = OcrJob(
            // A crop from a previous run carries that run's track numbering; a negative id keeps it
            // out of every live track's bookkeeping while still letting its plate be recorded.
            trackId = if (crop.carriedOver) -1 - (crop.trackId and 0xFFFF) else crop.trackId,
            generation = currentGeneration(),
            submittedAtMs = crop.capturedAtMs,
            buffer = buffer,
            width = cropWidth,
            height = cropHeight,
            strideInPixels = stride,
            sourceRect = Rect(),
            thumbnail = thumbnail,
            lensLabel = crop.lensLabel,
            quality = crop.quality,
            priority = crop.priority,
            lat = crop.lat,
            lon = crop.lon,
            odometerM = crop.odometerM,
            narrow = crop.narrow,
            deferred = true,
        )
        pool.release(buffer)
        runCatching { onResult(job, outcome) }
        return true
    }

    /** Hands a crop to the disk writer. Returns false when the writer itself is backed up. */
    private fun park(store: SpillStore, job: OcrJob): Boolean {
        val accepted = runCatching {
            spillExecutor.execute {
                val stored = store.offer(job)
                if (!stored) dropped.incrementAndGet()
                // release, not discard: park() already told the scheduler this crop is gone, and a
                // second notification lands hundreds of milliseconds later — long enough to clear
                // the flag of a *newer* crop and have the same car cropped twice over.
                release(job)
            }
        }.isSuccess
        // On its way to disk, the crop no longer represents the live state of that vehicle: the
        // scheduler may cut a fresh one on the very next frame.
        if (accepted) runCatching { onDropped(job) }
        return accepted
    }

    private fun hasRoom(bytes: Long): Boolean =
        queue.size < queueCapacity && queuedBytes.get() + bytes <= maxQueuedBytes

    private fun enqueue(job: OcrJob, bytes: Long) {
        queuedBytes.addAndGet(bytes)
        queue.put(job)
    }

    private fun loop(myEpoch: Int) {
        while (running && epoch.get() == myEpoch) {
            val job = try {
                queue.poll(120L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: run {
                // Idle engine: catch up on whatever had to wait on disk, and keep going until
                // either the backlog is empty or a live crop arrives — one per poll interval would
                // take half an hour to clear a few hundred parked crops.
                // One bad crop must cost one crop, not the worker thread and with it every
                // recognition for the rest of the drive.
                runCatching {
                    while (running && epoch.get() == myEpoch && queue.isEmpty() && drainOneSpilled()) {
                        // nothing further; each pass delivers one recovered plate
                    }
                }.onFailure { error -> Log.w(TAG, "spill drain failed", error) }
                null
            } ?: continue
            queuedBytes.addAndGet(-job.buffer.capacity().toLong())

            // Deliberately not dropped on a lens change: the crop was taken with the lens recorded
            // on the job, and its pixels remain a valid picture of that plate.
            val stale = System.currentTimeMillis() - job.submittedAtMs > staleAfterMs
            if (stale) {
                // Too old for the live consensus, but not for the record: the pixels are still a
                // plate, and disk is where such crops belong when there is a disk to put them on.
                val store = spill
                if (store != null && park(store, job)) continue
                dropped.incrementAndGet()
                discard(job)
                continue
            }

            currentJobSinceMs = SystemClock.elapsedRealtime()
            val outcome = try {
                AlprEngine.process(job.buffer, job.width, job.height, job.strideInPixels)
            } catch (error: Throwable) {
                Log.e(TAG, "recognition failed", error)
                AlprOutcome.failure(-3, error.message, 0L)
            }
            currentJobSinceMs = 0L
            lastLatencyMs = outcome.latencyMs
            if (outcome.code != AlprEngine.CODE_RUNTIME_LIMIT) lastProcessedAtMs = SystemClock.elapsedRealtime()
            captureDir?.let { dir -> saveCrop(dir, job) }
            // One line per crop: enough to compare engine settings from logcat without guessing.
            Log.i(
                TAG,
                "ocr ${outcome.latencyMs}ms ${job.width}x${job.height} plates=${outcome.plates.size} " +
                    "cars=${outcome.cars.size} lens=${job.lensLabel} q=${"%.2f".format(job.quality)} " +
                    "text=" + outcome.plates.joinToString(",") { "${it.text}:${it.recognitionScore.toInt()}" },
            )
            pool.release(job.buffer)
            // Ownership of the thumbnail moves to the consumer.
            runCatching { onResult(job, outcome) }
        }
    }

    /** Stores the crop exactly as the engine received it, plus its metadata line. */
    private fun saveCrop(dir: File, job: OcrJob) {
        if (capturedCount >= MAX_CAPTURE) return
        runCatching {
            val bitmap = Bitmap.createBitmap(job.strideInPixels, job.height, Bitmap.Config.ARGB_8888)
            job.buffer.rewind()
            bitmap.copyPixelsFromBuffer(job.buffer)
            job.buffer.rewind()
            val index = capturedCount
            // Through a temporary name: the benchmark lists the directory the moment capture
            // stops, and a JPEG still being written would otherwise be counted and fail to decode.
            val pending = File(dir, "crop_%04d.tmp".format(index))
            FileOutputStream(pending).use { output ->
                bitmap.compress(CompressFormat.JPEG, 95, output)
            }
            bitmap.recycle()
            pending.renameTo(File(dir, "crop_%04d.jpg".format(index)))
            File(dir, "index.txt").appendText(
                "crop_%04d.jpg %dx%d lens=%s q=%.2f track=%d\n".format(
                    index, job.width, job.height, job.lensLabel, job.quality, job.trackId,
                ),
            )
            capturedCount = index + 1
        }
    }

    private fun discard(job: OcrJob) {
        release(job)
        runCatching { onDropped(job) }
    }

    /** Returns a crop's resources without claiming its vehicle is free to be cropped again. */
    private fun release(job: OcrJob) {
        pool.release(job.buffer)
        if (!job.thumbnail.isRecycled) job.thumbnail.recycle()
    }

    private companion object {
        const val TAG = "LensALPR.Worker"
        const val MAX_CAPTURE = 400

        /** Crops allowed to wait for the spill writer; each one holds a pooled buffer. */
        const val SPILL_QUEUE_DEPTH = 6

        /** Long enough for one in-flight recognition to finish; the engine is not interruptible. */
        const val WORKER_JOIN_MS = 1_500L
        const val THUMBNAIL_WIDTH = 256
    }
}
