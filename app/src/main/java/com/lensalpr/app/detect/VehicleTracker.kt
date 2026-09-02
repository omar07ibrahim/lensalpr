package com.lensalpr.app.detect

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * A vehicle followed across frames.
 *
 * The track is the unit the recognition scheduler reasons about: it carries the OCR bookkeeping so
 * a car that is already confirmed stops consuming engine time, and an unread car keeps its turn in
 * the queue.
 */
class VehicleTrack internal constructor(val id: Int) {
    val box = RectF()
    var velocityX = 0f
        internal set
    var velocityY = 0f
        internal set
    var score = 0f
        internal set
    var classId = 0
        internal set
    var hits = 0
        internal set
    var missed = 0
        internal set
    var firstSeenMs = 0L
        internal set
    var lastSeenMs = 0L
        internal set
    internal var lastFrame = -1L

    val area: Float get() = box.width() * box.height()
}

/**
 * Lightweight IoU tracker with constant-velocity prediction.
 *
 * No Kalman filter: at 15-30 analysed frames per second a predicted box plus greedy IoU matching
 * keeps identities stable for traffic, and it costs microseconds instead of milliseconds.
 */
class VehicleTracker(
    private val iouThreshold: Float = 0.25f,
    private val maxMissed: Int = 12,
    private val maxTracks: Int = 32,
    private val smoothing: Float = 0.6f,
    /** Invoked when a track disappears so its recognition bookkeeping can be released. */
    private val onTrackLost: ((Int) -> Unit)? = null,
) {

    private val tracks = ArrayList<VehicleTrack>()
    private val predicted = RectF()
    private var nextId = 1
    private var frameIndex = 0L

    val active: List<VehicleTrack> get() = tracks

    fun update(detections: List<Detection>, nowMs: Long): List<VehicleTrack> {
        val frame = ++frameIndex
        val claimed = BooleanArray(tracks.size)
        val matched = BooleanArray(detections.size)

        // Detections arrive sorted by score; the most confident box claims its track first.
        for (d in detections.indices) {
            val detection = detections[d]
            var bestIndex = -1
            var bestIou = iouThreshold
            for (t in tracks.indices) {
                if (claimed[t]) continue
                val track = tracks[t]
                predictInto(track, predicted)
                val iou = iou(predicted, detection.box)
                if (iou <= bestIou) continue
                // A car cannot double in size between two frames. Without this a small car passing
                // in front of a truck can steal the truck's identity, and with it the plate that
                // was being read - the consensus then mixes two vehicles into one card.
                // Only while the track is fresh: a car that was hidden behind a van for half a
                // second reappears at a genuinely different size and shape, and refusing it there
                // would split one vehicle into two and reset everything known about its plate.
                if (track.missed <= SHAPE_GATE_MAX_MISSED &&
                    !plausibleMatch(
                        track.box.width(),
                        track.box.height(),
                        detection.box.width(),
                        detection.box.height(),
                    )
                ) {
                    continue
                }
                bestIou = iou
                bestIndex = t
            }
            if (bestIndex >= 0) {
                claimed[bestIndex] = true
                matched[d] = true
                apply(tracks[bestIndex], detection, nowMs, frame)
            }
        }

        for (d in detections.indices) {
            if (matched[d] || tracks.size >= maxTracks) continue
            val track = VehicleTrack(nextId++)
            track.box.set(detections[d].box)
            track.score = detections[d].score
            track.classId = detections[d].classId
            track.hits = 1
            track.firstSeenMs = nowMs
            track.lastSeenMs = nowMs
            track.lastFrame = frame
            tracks.add(track)
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track.lastFrame == frame) continue
            track.missed += 1
            // Keep coasting the box so a short occlusion does not split the identity.
            track.box.offset(track.velocityX, track.velocityY)
            if (track.missed > maxMissed) {
                iterator.remove()
                onTrackLost?.invoke(track.id)
            }
        }
        return tracks
    }

    /** Geometry from another lens is meaningless; identities restart on every zoom change. */
    fun reset() {
        val lost = onTrackLost
        if (lost != null) tracks.forEach { lost(it.id) }
        tracks.clear()
    }

    private fun apply(track: VehicleTrack, detection: Detection, nowMs: Long, frame: Long) {
        val previousCenterX = track.box.centerX()
        val previousCenterY = track.box.centerY()
        track.box.set(
            lerp(track.box.left, detection.box.left),
            lerp(track.box.top, detection.box.top),
            lerp(track.box.right, detection.box.right),
            lerp(track.box.bottom, detection.box.bottom),
        )
        track.velocityX = track.box.centerX() - previousCenterX
        track.velocityY = track.box.centerY() - previousCenterY
        track.score = detection.score
        track.classId = detection.classId
        track.hits += 1
        track.missed = 0
        track.lastSeenMs = nowMs
        track.lastFrame = frame
    }

    private fun lerp(previous: Float, current: Float): Float =
        previous + (current - previous) * smoothing

    private fun predictInto(track: VehicleTrack, out: RectF) {
        out.set(track.box)
        out.offset(track.velocityX, track.velocityY)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        /** Frames of absence after which the shape check stops applying. */
        private const val SHAPE_GATE_MAX_MISSED = 3

        /** Widest per-frame size change still attributable to the same vehicle approaching. */
        private const val MIN_SCALE_STEP = 0.45f
        private const val MAX_SCALE_STEP = 2.2f

        /** A box that changes shape this much is a different object, however well it overlaps. */
        private const val MIN_ASPECT_STEP = 0.55f
        private const val MAX_ASPECT_STEP = 1.8f

        fun plausibleMatch(
            previousWidth: Float,
            previousHeight: Float,
            width: Float,
            height: Float,
        ): Boolean {
            if (previousWidth <= 0f || previousHeight <= 0f || width <= 0f || height <= 0f) return false
            val scaleX = width / previousWidth
            val scaleY = height / previousHeight
            if (scaleX !in MIN_SCALE_STEP..MAX_SCALE_STEP) return false
            if (scaleY !in MIN_SCALE_STEP..MAX_SCALE_STEP) return false
            val aspectChange = (width / height) / (previousWidth / previousHeight)
            return aspectChange in MIN_ASPECT_STEP..MAX_ASPECT_STEP
        }
    }
}
