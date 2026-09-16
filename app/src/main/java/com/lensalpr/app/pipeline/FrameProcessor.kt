package com.lensalpr.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lensalpr.app.camera.FrameGate
import com.lensalpr.app.alpr.PlateRoi
import com.lensalpr.app.detect.VehicleTrack
import com.lensalpr.app.detect.VehicleTracker
import com.lensalpr.app.detect.YoloDetector
import com.lensalpr.app.settings.ScanConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Overlay geometry for one tracked vehicle. */
class TrackBox(
    val id: Int,
    val rect: RectF,
    val label: String?,
    val confirmed: Boolean,
    val pendingCount: Int,
)

/** Everything the UI needs about one analysed frame. */
class FrameSnapshot(
    val imageWidth: Int,
    val imageHeight: Int,
    val boxes: List<TrackBox>,
    /** Frames sampled for crops per second. */
    val sampleFps: Float,
    /** Detector passes per second. */
    val detectFps: Float,
    val detectMs: Long,
    val queueDepth: Int,
    val droppedCrops: Int,
    val spilledCrops: Int,
    val trackCount: Int,
)

/**
 * The per-frame pipeline: RGBA frame -> YOLO26 -> tracker -> vehicle crops -> ALPR queue.
 *
 * Runs entirely on the CameraX analysis thread with `STRATEGY_KEEP_ONLY_LATEST`, so a slow frame
 * costs latency but never builds a backlog. The frame is held only for detection and cropping;
 * recognition itself happens on the worker thread against a copy, which is what keeps many
 * simultaneous vehicles flowing instead of serializing the camera behind the OCR engine.
 */
class FrameProcessor(
    private val config: ScanConfig,
    private val gate: FrameGate,
    private val detector: YoloDetector,
    private val worker: AlprWorker,
    private val pool: CropBufferPool,
    private val recognition: RecognitionState,
    /** Latitude, longitude and trip odometer at this instant; stamped onto every crop. */
    private val geoStamp: () -> DoubleArray?,
    private val onTrackLost: (Int) -> Unit,
    private val onFrame: (FrameSnapshot) -> Unit,
) : ImageAnalysis.Analyzer {

    private val tracker = VehicleTracker(onTrackLost = { id -> onTrackLost(id) })

    private var rawBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var rotatedCanvas: Canvas? = null
    private var scratch: ByteBuffer? = null
    private var thumbPixels = IntArray(0)
    private var lastGeneration = -1L
    private var lastFrameAtMs = 0L
    private var fps = 0f
    private var detectFps = 0f
    private var lastDetectAtMs = 0L
    private var previousDetectAtMs = 0L

    /** Set once by the benchmark; the next analysed frame hands over a copy of one vehicle crop. */
    @Volatile
    private var cropRequest: ((Bitmap) -> Unit)? = null

    /** Set by the bot; the next analysed frame hands over a copy of the whole picture. */
    @Volatile
    private var frameRequest: ((Bitmap) -> Unit)? = null


    /**
     * Whether the second stage may be used at all. Driven by the operator's setting, and flipped
     * every minute while the comparison experiment is running.
     */
    @Volatile
    var narrowAllowed: Boolean = true

    /**
     * When the camera last delivered a frame, whether or not it was recognised.
     *
     * Deliberately stamped before the gate: a paused session and a camera that has stopped
     * delivering look identical from the outside, and for this app the difference is everything.
     */
    @Volatile
    var lastAnalyzedAtMs: Long = 0L
        private set

    /**
     * When a frame last made it past the gate and was actually examined.
     *
     * The pair with [lastAnalyzedAtMs] is what distinguishes "the camera died" from "the camera is
     * fine and nothing is being recognised anyway" — the second failure has no symptom at all
     * otherwise, which is the worst possible property for a scanner.
     */
    @Volatile
    var lastPassedGateAtMs: Long = 0L
        private set

    /**
     * When this session first received a frame at all.
     *
     * The baseline for "how long have we been blind". Measuring from [lastPassedGateAtMs] alone was
     * wrong at startup: it is zero until a frame first passes the gate, so the elapsed time read as
     * the whole uptime of the phone and a session whose lens was still verifying looked like a
     * ninety-minute outage on its very first tick.
     */
    @Volatile
    var firstAnalyzedAtMs: Long = 0L
        private set

    /** Crops cut around a remembered plate rather than around the whole car. */
    @Volatile
    var narrowCrops: Int = 0
        private set

    /** Crops that had to show the whole vehicle because no plate position was known. */
    @Volatile
    var wideCrops: Int = 0
        private set

    private val cropRect = Rect()
    private val cropMatrix = Matrix()
    private val thumbMatrix = Matrix()
    private val rotationMatrix = Matrix()

    override fun analyze(image: ImageProxy) {
        try {
            lastAnalyzedAtMs = SystemClock.elapsedRealtime()
            if (firstAnalyzedAtMs == 0L) firstAnalyzedAtMs = lastAnalyzedAtMs
            // One read of one object: the generation and the lens label it belongs to travel
            // together, so a frame cannot be stamped with the label of a lens it was not taken
            // through when the switch lands halfway through this method.
            val epoch = gate.epoch
            val generation = epoch.generation
            if (generation != lastGeneration) {
                // Geometry from the previous lens cannot be matched against the new field of view.
                tracker.reset()
                lastGeneration = generation
                publish(0, 0, emptyList())
            }
            // Served before the gate: the operator asking for a picture from Telegram expects one
            // even while recognition is paused.
            frameRequest?.let { consumer ->
                frameRequest = null
                copyFrame(image)
                    ?.let { frame -> runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
                    ?.let(consumer)
            }
            if (!gate.isOpen) return
            // Frames arriving and frames being *looked at* are different questions, and only the
            // second one means the scanner is working. A gate stuck shut — an unverifiable lens,
            // a sleep nothing can wake — looks identical to an empty road from every other signal
            // the app has, so it gets its own clock.
            lastPassedGateAtMs = SystemClock.elapsedRealtime()

            val now = SystemClock.elapsedRealtime()
            // One fixed floor, never widened for heat. Detection rate is what decides whether a
            // track survives; halving it because the phone is warm is how a followed car turns
            // into a stranger halfway through a drive.
            if (now - lastFrameAtMs < MIN_FRAME_INTERVAL_MS) return
            updateFps(now)
            lastFrameAtMs = now

            val frame = copyFrame(image) ?: return

            val detections = detector.detect(
                frame = frame,
                minScore = config.confidence,
                minBoxPx = config.minBoxPx,
                classes = YoloDetector.VEHICLE_CLASSES,
            )
            previousDetectAtMs = lastDetectAtMs
            lastDetectAtMs = now
            updateDetectFps(now)
            val tracks = tracker.update(detections, now)
            cropRequest?.let { consumer ->
                cropRequest = null
                serveCropRequest(frame, tracks, consumer)
            }
            scheduleRecognition(frame, tracks, now, epoch)
            publish(frame.width, frame.height, tracks)
        } catch (error: Throwable) {
            Log.e(TAG, "frame failed", error)
        } finally {
            image.close()
        }
    }

    /**
     * Captures the crop of the largest tracked vehicle from the next frame, exactly as the
     * recognition path would have cut it.
     */
    fun requestCrop(consumer: (Bitmap) -> Unit) {
        cropRequest = consumer
    }

    /** Hands the next analysed frame to [consumer]; used by the bot to answer with a live photo. */
    fun requestFrame(consumer: (Bitmap) -> Unit) {
        frameRequest = consumer
    }

    private fun serveCropRequest(frame: Bitmap, tracks: List<VehicleTrack>, consumer: (Bitmap) -> Unit) {
        val track = tracks.filter { it.missed == 0 }.maxByOrNull { it.area }
        val copy = if (track != null && expandCrop(track.box, frame.width, frame.height)) {
            runCatching {
                Bitmap.createBitmap(frame, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            }.getOrNull()
        } else {
            null
        } ?: runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy != null) consumer(copy)
    }

    fun release() {
        tracker.reset()
        rawBitmap?.recycle()
        rotatedBitmap?.recycle()
        rawBitmap = null
        rotatedBitmap = null
        rotatedCanvas = null
        scratch = null
    }

    private fun updateDetectFps(now: Long) {
        val delta = now - previousDetectAtMs
        if (previousDetectAtMs != 0L && delta in 1..3_000) {
            val instant = 1_000f / delta
            detectFps = if (detectFps == 0f) instant else detectFps * 0.8f + instant * 0.2f
        }
    }

    private fun updateFps(now: Long) {
        val delta = now - lastFrameAtMs
        if (lastFrameAtMs != 0L && delta in 1..2_000) {
            val instant = 1_000f / delta
            fps = if (fps == 0f) instant else fps * 0.8f + instant * 0.2f
        }
    }

    /**
     * Copies the analysis plane into a reusable ARGB_8888 bitmap, display-oriented.
     *
     * CameraX already produced RGBA in display orientation, and Android's ARGB_8888 has the same
     * byte layout, so the common case is a straight memcpy - and the same bitmap then serves both
     * the detector (scaled) and the crops (native resolution). Should the camera stack ever hand
     * back a buffer that still needs rotating (reverse landscape on a stack that ignores
     * `setOutputImageRotationEnabled`), it is rotated here rather than fed to the detector sideways.
     */
    private fun copyFrame(image: ImageProxy): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val raw = ensureBitmap(rawBitmap, width, height).also { rawBitmap = it }
        val buffer = plane.buffer
        val rowBytes = width * PIXEL_STRIDE
        buffer.clear()
        if (plane.rowStride == rowBytes) {
            raw.copyPixelsFromBuffer(buffer)
        } else {
            val target = ensureScratch(rowBytes * height)
            target.clear()
            for (row in 0 until height) {
                val start = row * plane.rowStride
                buffer.limit(start + rowBytes)
                buffer.position(start)
                target.put(buffer)
            }
            target.rewind()
            raw.copyPixelsFromBuffer(target)
        }
        buffer.clear()

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw

        val swap = rotation == 90 || rotation == 270
        val outWidth = if (swap) height else width
        val outHeight = if (swap) width else height
        var rotated = rotatedBitmap
        if (rotated == null || rotated.width != outWidth || rotated.height != outHeight) {
            rotated?.recycle()
            rotated = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            rotatedBitmap = rotated
            rotatedCanvas = Canvas(rotated)
        }
        val canvas = rotatedCanvas ?: Canvas(rotated).also { rotatedCanvas = it }
        rotationMatrix.reset()
        rotationMatrix.postTranslate(-width / 2f, -height / 2f)
        rotationMatrix.postRotate(rotation.toFloat())
        rotationMatrix.postTranslate(outWidth / 2f, outHeight / 2f)
        canvas.drawBitmap(raw, rotationMatrix, null)
        return rotated
    }

    private fun ensureBitmap(current: Bitmap?, width: Int, height: Int): Bitmap {
        if (current != null && current.width == width && current.height == height) return current
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun ensureScratch(bytes: Int): ByteBuffer {
        val current = scratch
        if (current != null && current.capacity() >= bytes) return current
        val created = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        scratch = created
        return created
    }

    /**
     * Picks which vehicles get engine time this frame.
     *
     * Unread vehicles always outrank confirmed ones, larger boxes outrank distant ones, and every
     * track has a cooldown so one car in the middle of the frame cannot starve the others.
     */
    private fun scheduleRecognition(
        frame: Bitmap,
        tracks: List<VehicleTrack>,
        now: Long,
        epoch: FrameGate.Epoch,
    ) {
        // The operator's setting, and nothing else. Heat used to cut this to two crops and then to
        // one; the only thing that reduces it now is a genuinely full queue, below, where cutting
        // more crops would buy nothing anyway.
        val cropBudget = config.maxCropsPerFrame
        if (tracks.isEmpty() || cropBudget <= 0 || !worker.isRunning) return
        val queueBusy = worker.queueDepth >= 2
        val frameArea = (frame.width.toFloat() * frame.height.toFloat()).coerceAtLeast(1f)

        val idleQueue = worker.queueDepth == 0
        val candidates = ArrayList<Pair<VehicleTrack, Float>>(tracks.size)
        for (track in tracks) {
            // A car that crosses the frame in half a second never survives a two-frame
            // confirmation. When it is close enough to be unmistakable - or when the engine is
            // idle anyway - read it from the very first frame it appears on.
            val obvious = track.box.width() >= config.minBoxPx * 2f
            val requiredHits = if (obvious || idleQueue) 1 else MIN_HITS
            if (track.hits < requiredHits || track.missed > 0) continue
            if (track.box.width() < config.minBoxPx || track.box.height() < config.minBoxPx) continue
            val runtime = recognition.of(track.id)
            if (runtime.inFlight) continue
            // A plate eleven pixels wide cannot be read by anything, and every such crop costs a
            // slot another car could have used. When the engine has nothing else to do the crop is
            // free, so hopeless ones are only skipped while there is a queue.
            if (!idleQueue && runtime.plateAnchor == null &&
                estimatedPlatePixels(track.box.width()) < MIN_PLATE_PX
            ) {
                continue
            }
            // Focus is measured after the fact; speed predicts it. A vehicle sliding across a
            // sizeable fraction of its own width between two frames arrives smeared, and the crop
            // is spent before anyone looks at it. A car that is actually following us barely moves
            // in the frame, so this only ever sacrifices traffic going the other way.
            if (!idleQueue && runtime.attempts > 0 &&
                smearedByMotion(track.velocityX, track.velocityY, track.box.width())
            ) {
                continue
            }
            val confirmed = runtime.confirmedPlate != null
            // A re-read that only has to look at the plate costs a fraction of a whole-vehicle
            // call, so an anchored car can afford to be checked more often — and every extra read
            // is another vote in the consensus.
            val anchored = narrowAllowed && runtime.plateAnchor != null
            val cooldown = when {
                confirmed && anchored -> CONFIRMED_RECHECK_NARROW_MS
                confirmed -> CONFIRMED_RECHECK_MS
                else -> config.ocrIntervalMs.toLong()
            }
            if (now - runtime.lastSubmitMs < cooldown) continue

            val areaScore = min(1f, track.area / frameArea * 12f)
            // While the engine has spare time, spread it over cars nothing is known about. Once it
            // is saturated, finishing a vehicle that needs one more read is worth more than starting
            // another: a plate confirmed is evidence, a plate half-read is nothing.
            val convergence = when {
                !queueBusy -> if (runtime.pendingCount == 0) 40f else 0f
                runtime.pendingCount >= config.consensusMatches - 1 && runtime.pendingCount > 0 -> 60f
                runtime.pendingCount > 0 -> 30f
                else -> 10f
            }
            val priority = (if (confirmed) 0f else 100f) +
                convergence +
                areaScore * 30f +
                track.score * 10f
            candidates += track to priority
        }
        if (candidates.isEmpty()) return
        candidates.sortByDescending { it.second }

        // Under saturation, cut one crop instead of several. Now that submit no longer waits for
        // room, a full queue means every extra crop this frame either evicts a queued one or goes
        // to disk — churn that buys nothing, while the crops themselves cost the analysis thread.
        // One crop for the car that most needs reading is the whole of what is useful here.
        val budget = if (worker.isSaturated) 1 else cropBudget
        var attempted = 0
        for ((track, priority) in candidates) {
            // Attempts, not successes: counting only what got through would walk the entire
            // candidate list on a saturated queue, cutting a crop for every car in frame.
            if (attempted >= budget) break
            attempted += 1
            submitJob(frame, track, now, epoch, queueBusy, priority)
        }
    }

    private fun submitJob(
        frame: Bitmap,
        track: VehicleTrack,
        now: Long,
        epoch: FrameGate.Epoch,
        queueBusy: Boolean,
        priority: Float,
    ): Boolean {
        if (!expandCrop(track.box, frame.width, frame.height)) return false

        val vehicleRect = Rect(cropRect)
        val runtime = recognition.of(track.id)

        // Second stage: the plate has already been located on this car, so cut around it instead of
        // sending the whole vehicle again. The plate keeps its native pixels — a vehicle crop is
        // capped at MAX_CROP_WIDTH and a close car loses a third of them to that cap.
        // Every few attempts the whole vehicle is looked at again, whatever else is known about it.
        // The classifiers need it for make and model, but the real reason is that a narrow crop can
        // only ever confirm what the anchor already believes: if it has drifted onto the plate of
        // the car alongside, nothing inside that crop will ever say so.
        val owesVehicleLook = runtime.attempts % WIDE_LOOK_EVERY == WIDE_LOOK_EVERY - 1
        val anchor = runtime.plateAnchor
            ?.takeIf { narrowAllowed && !owesVehicleLook && now - runtime.anchorAtMs <= ANCHOR_TTL_MS }
            ?.takeIf { plausibleAnchor(it, vehicleRect, track.box) || dropAnchor(runtime) }
        val narrowRoi = anchor?.let {
            PlateRoi.project(
                anchor = it,
                basis = intArrayOf(vehicleRect.left, vehicleRect.top, vehicleRect.right, vehicleRect.bottom),
                frameWidth = frame.width,
                frameHeight = frame.height,
            )
        }
        if (narrowRoi != null) cropRect.set(narrowRoi[0], narrowRoi[1], narrowRoi[2], narrowRoi[3])

        // The card and the evidence photo always show the car, never the bare plate, and the focus
        // score stays comparable between the two stages because it is measured on the same picture.
        // Built before the crop on purpose: it is the only input the blur gate needs, and cutting a
        // multi-megapixel crop just to throw it away is the most expensive thing this method does.
        val thumbnail = thumbnailOf(frame, vehicleRect) ?: return false
        val quality = sharpness(thumbnail)
        // Decay the reference so a single very sharp frame cannot permanently reject a vehicle
        // once the light or the distance changes.
        runtime.bestQuality = max(quality, runtime.bestQuality * QUALITY_DECAY)
        // Under queue pressure a visibly blurred frame is not worth the engine time.
        if (queueBusy && runtime.attempts > 0 && quality < runtime.bestQuality * BLUR_REJECT_RATIO) {
            thumbnail.recycle()
            return false
        }

        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        val scale = min(1f, MAX_CROP_WIDTH.toFloat() / cropWidth)
        val matrix = if (scale < 1f) {
            cropMatrix.reset()
            cropMatrix.setScale(scale, scale)
            cropMatrix
        } else {
            null
        }

        val crop = runCatching {
            Bitmap.createBitmap(
                frame,
                cropRect.left,
                cropRect.top,
                cropWidth,
                cropHeight,
                matrix,
                true,
            )
        }.getOrNull() ?: run {
            thumbnail.recycle()
            return false
        }
        if (crop === frame) {
            thumbnail.recycle()
            return false
        }

        val geo = geoStamp()
        val stridePixels = crop.rowBytes / PIXEL_STRIDE
        val pixelWidth = crop.width
        val pixelHeight = crop.height
        val bytes = crop.rowBytes * pixelHeight
        val buffer = pool.acquire(bytes)
        buffer.clear()
        buffer.limit(bytes)
        crop.copyPixelsToBuffer(buffer)
        buffer.rewind()
        crop.recycle()

        val job = OcrJob(
            trackId = track.id,
            generation = epoch.generation,
            submittedAtMs = System.currentTimeMillis(),
            buffer = buffer,
            width = pixelWidth,
            height = pixelHeight,
            strideInPixels = stridePixels,
            sourceRect = Rect(cropRect),
            thumbnail = thumbnail,
            lensLabel = epoch.label,
            quality = quality,
            priority = priority,
            lat = geo?.get(0),
            lon = geo?.get(1),
            // NaN, not zero: zero is a real odometer reading and it lied in both directions. With
            // no location at all every crop carried 0 m, so contact distance stayed at zero and
            // every vehicle classified as harmless; and when a fix arrived late, a car's first
            // observations sat at 0 m and its later ones at the real reading, inventing kilometres
            // of company that never happened. NaN reaches the follow engine's own fallback.
            odometerM = geo?.get(2) ?: Double.NaN,
            anchorRect = vehicleRect,
            narrow = narrowRoi != null,
        )
        if (narrowRoi != null) narrowCrops += 1 else wideCrops += 1
        runtime.inFlight = true
        runtime.lastSubmitMs = now
        runtime.attempts += 1
        if (!worker.submit(job)) {
            runtime.inFlight = false
            return false
        }
        return true
    }

    /**
     * Whether a remembered plate position still describes *this* vehicle.
     *
     * The anchor is stored against the padded crop, and the crop is padded precisely so the plate is
     * never cut off — which means a plate belonging to the car in the next lane can sit inside it
     * quite comfortably. Checking against the detector's own box instead, and demanding a plate of
     * plausible size well inside it, is what stops a drifted anchor from re-confirming itself
     * forever on somebody else's number.
     */
    private fun plausibleAnchor(anchor: FloatArray, padded: Rect, box: RectF): Boolean {
        if (padded.width() <= 0 || padded.height() <= 0 || box.width() <= 0f) return false
        val centreX = padded.left + (anchor[0] + anchor[2]) / 2f * padded.width()
        val centreY = padded.top + (anchor[1] + anchor[3]) / 2f * padded.height()
        val insetX = box.width() * ANCHOR_INSET
        val insetY = box.height() * ANCHOR_INSET
        if (centreX < box.left + insetX || centreX > box.right - insetX) return false
        if (centreY < box.top + insetY || centreY > box.bottom - insetY) return false
        val share = (anchor[2] - anchor[0]) * padded.width() / box.width()
        return share in MIN_PLATE_SHARE..MAX_PLATE_SHARE
    }

    /** Forgets an anchor that no longer points at this car; always returns false. */
    private fun dropAnchor(runtime: TrackRuntime): Boolean {
        runtime.plateAnchor = null
        return false
    }

    /** Expands the detector box slightly so the plate is never cut off at the bumper. */
    private fun expandCrop(box: RectF, frameWidth: Int, frameHeight: Int): Boolean {
        val marginX = box.width() * CROP_MARGIN
        val marginY = box.height() * CROP_MARGIN
        val left = max(0f, box.left - marginX).roundToInt()
        val top = max(0f, box.top - marginY).roundToInt()
        val right = min(frameWidth.toFloat(), box.right + marginX).roundToInt()
        val bottom = min(frameHeight.toFloat(), box.bottom + marginY).roundToInt()
        if (right - left < MIN_CROP_PX || bottom - top < MIN_CROP_PX) return false
        cropRect.set(left, top, right, bottom)
        return true
    }

    /** A 256 px wide picture of the whole vehicle, cut straight from the frame. */
    private fun thumbnailOf(frame: Bitmap, rect: Rect): Bitmap? {
        if (rect.width() < 8 || rect.height() < 8) return null
        val scale = min(1f, THUMBNAIL_WIDTH.toFloat() / rect.width())
        val matrix = if (scale < 1f) {
            thumbMatrix.reset()
            thumbMatrix.setScale(scale, scale)
            thumbMatrix
        } else {
            null
        }
        val thumbnail = runCatching {
            Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height(), matrix, true)
        }.getOrNull() ?: return null
        return if (thumbnail === frame) null else thumbnail
    }

    /**
     * How much readable detail this crop carries. See [FocusMetric] for why the raw gradient it
     * replaced had to go: it could not tell a shaded plate from a smeared one.
     */
    private fun sharpness(thumbnail: Bitmap): Float {
        val width = thumbnail.width
        val height = thumbnail.height
        if (width < 8 || height < 8) return 0f
        val needed = width * height
        if (thumbPixels.size < needed) thumbPixels = IntArray(needed)
        thumbnail.getPixels(thumbPixels, 0, width, 0, 0, width, height)
        return FocusMetric.sharpness(thumbPixels, width, height)
    }

    private fun publish(imageWidth: Int, imageHeight: Int, tracks: List<VehicleTrack>) {
        val boxes = ArrayList<TrackBox>(tracks.size)
        for (track in tracks) {
            if (track.missed > 0) continue
            val runtime = recognition.peek(track.id)
            boxes += TrackBox(
                id = track.id,
                rect = RectF(track.box),
                label = runtime?.confirmedPlate ?: runtime?.pendingPlate ?: runtime?.makeModel,
                confirmed = runtime?.confirmedPlate != null,
                pendingCount = runtime?.pendingCount ?: 0,
            )
        }
        onFrame(
            FrameSnapshot(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                boxes = boxes,
                sampleFps = fps,
                detectFps = detectFps,
                detectMs = detector.lastInferenceMs,
                queueDepth = worker.queueDepth,
                droppedCrops = worker.droppedJobs,
                spilledCrops = worker.spilledCount,
                trackCount = boxes.size,
            ),
        )
    }

    companion object {
        /** Share of a car's width taken up by its plate; a 520 mm plate on a 1.8 m wide car. */
        const val PLATE_WIDTH_SHARE = 0.25f

        /** Below this the recognizer has nothing to work with, whatever the settings say. */
        const val MIN_PLATE_PX = 26f

        /**
         * Frame-to-frame movement, as a share of the vehicle's own width, past which the crop is
         * expected to be smeared. Generous on purpose: this is a saving, not a quality gate, and a
         * wrongly skipped frame is followed by another one milliseconds later.
         */
        const val MOTION_LIMIT = 0.14f

        /** True when the vehicle is crossing the frame fast enough to blur its own plate. */
        fun smearedByMotion(velocityX: Float, velocityY: Float, boxWidth: Float): Boolean {
            if (boxWidth <= 0f) return false
            val travelled = kotlin.math.hypot(velocityX, velocityY)
            return travelled > boxWidth * MOTION_LIMIT
        }

        /**
         * How wide the plate will be in the image the engine finally sees, taking into account that
         * a large vehicle crop is scaled down to [MAX_CROP_WIDTH] before it is sent.
         */
        fun estimatedPlatePixels(carWidthPx: Float): Float {
            if (carWidthPx <= 0f) return 0f
            val cropWidth = carWidthPx * (1f + 2f * CROP_MARGIN)
            val scale = min(1f, MAX_CROP_WIDTH / cropWidth)
            return carWidthPx * PLATE_WIDTH_SHARE * scale
        }

        const val TAG = "LensALPR.Frame"
        const val PIXEL_STRIDE = 4
        const val MIN_FRAME_INTERVAL_MS = 25L
        const val MIN_HITS = 2
        const val CROP_MARGIN = 0.06f
        const val MIN_CROP_PX = 64
        const val MAX_CROP_WIDTH = 1280
        const val THUMBNAIL_WIDTH = 256
        const val CONFIRMED_RECHECK_MS = 2_500L
        const val CONFIRMED_RECHECK_NARROW_MS = 1_200L

        /**
         * How long a remembered plate position stays usable.
         *
         * The anchor is stored relative to the vehicle box, which the tracker keeps updating, so it
         * does not go stale as the car moves — only if the tracker itself drifts onto something
         * else. It has to outlive the re-check interval of a confirmed vehicle, or an anchored car
         * would never actually use the narrow crop.
         */
        const val ANCHOR_TTL_MS = 4_000L

        /** How far inside the detector's box a plate centre must sit to belong to that vehicle. */
        const val ANCHOR_INSET = 0.10f

        /** A plate is about a quarter of a car's width; outside this range it is another car's. */
        const val MIN_PLATE_SHARE = 0.08f
        const val MAX_PLATE_SHARE = 0.55f

        /** Attempts between forced whole-vehicle looks, which re-validate the anchor. */
        const val WIDE_LOOK_EVERY = 4
        const val BLUR_REJECT_RATIO = 0.55f
        const val QUALITY_DECAY = 0.97f

    }
}
