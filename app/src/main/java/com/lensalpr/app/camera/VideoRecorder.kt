package com.lensalpr.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the pursuit, not just snapshots of it.
 *
 * A vehicle that has held station behind us long enough to count as a follower is worth a
 * continuous clip: it shows the distance keeping, the lane changes and the turns, which a burst of
 * stills cannot. Recording runs on the same camera session, so the lens rotation keeps working and
 * the clip shows exactly what the operator would have seen.
 */
class VideoRecorder(
    private val context: Context,
    /**
     * A clip is complete. The last argument says the recorder stopped itself on a limit rather
     * than being asked to — the subject is still behind us and filming has to carry on in a new
     * file, exactly as when the session cuts a segment on time.
     */
    private val onFinished: (File, String, Long, Boolean) -> Unit,
    /** Nothing was written and there is no file. The caller must undo whatever it announced. */
    private val onFailed: (String, Int) -> Unit = { _, _ -> },
) {

    private val executor = ContextCompat.getMainExecutor(context)

    @Volatile
    private var recording: Recording? = null

    @Volatile
    private var target: String? = null

    /** Monotonic: a clock correction mid-clip must not turn into a negative or hour-long clip. */
    @Volatile
    private var startedAtMs = 0L

    @Volatile
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    val isRecording: Boolean get() = recording != null

    /** Plate the current clip is about. */
    val currentTarget: String? get() = target

    val elapsedMs: Long
        get() = if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /** Builds the use case to bind next to Preview and ImageAnalysis. */
    @SuppressLint("RestrictedApi")
    fun buildUseCase(targetRotation: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            // Ordered rather than a bare `from(HD)`: a single unsupported quality leaves the
            // selector with nothing and the device picks whatever it likes — which on this phone
            // means following the 4K analysis stream and producing clips no bot can upload.
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                ),
            )
            // The decisive setting. Left to itself the encoder uses the device default — 6 to 17
            // Mbps on this hardware, which is 60-190 MB for a ninety-second clip against a 50 MB
            // ceiling, and every clip of an actual tail was refused. The clip exists to show
            // distance keeping, lane changes and turns; the plate is read from the full-resolution
            // analysis stream, never from this file, so resolution and bitrate only have to carry
            // behaviour. At this rate ninety seconds is about 28 MB.
            .setTargetVideoEncodingBitRate(TARGET_BITRATE)
            // A little over one segment. CameraX already refuses below 50 MB by default; raising
            // it far higher would turn "the disk is nearly full" into "no clip at all" on a phone
            // where a useful forty-megabyte clip would still have fitted.
            .setRequiredFreeStorageBytes(MAX_CLIP_BYTES + 16L * 1024 * 1024)
            .build()
        val capture = VideoCapture.withOutput(recorder)
        capture.targetRotation = targetRotation
        videoCapture = capture
        return capture
    }

    fun detach() {
        stop()
        videoCapture = null
    }

    /**
     * The clip's subject was renamed while it was being written. The file keeps its name; what
     * changes is the key the absence timer and the follow engine use to find the car.
     */
    fun retarget(plate: String) {
        if (recording != null) target = plate
    }

    @SuppressLint("MissingPermission")
    fun start(plate: String): Boolean {
        val capture = videoCapture ?: return false
        if (recording != null) return false
        // Refuse up front rather than announcing "🎥 Запись", filming for two seconds and handing
        // back a stub. A clip that cannot be written is not evidence, and the announcement of one
        // that does not exist is worse than saying nothing.
        val free = runCatching { directory().usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < MIN_FREE_BYTES) {
            Log.w(TAG, "only ${free / (1024 * 1024)} MB free; not recording")
            return false
        }
        val file = uniqueFile(plate)
        return runCatching {
            // A hard ceiling the encoder cannot talk its way past. The bitrate above is a request;
            // this is the guarantee, and CameraX finalizes the file properly when it is hit, so
            // what lands on disk is a playable clip rather than a refused one.
            val options = FileOutputOptions.Builder(file)
                .setFileSizeLimit(MAX_CLIP_BYTES)
                .build()
            target = plate
            startedAtMs = SystemClock.elapsedRealtime()
            recording = capture.output
                .prepareRecording(context, options)
                // No audio: the app never asks for the microphone.
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val plateForClip = target ?: plate
                        // The encoder's own count of what it wrote, not a wall-clock difference.
                        val duration = runCatching {
                            event.recordingStats.recordedDurationNanos / 1_000_000L
                        }.getOrDefault(elapsedMs)
                        recording = null
                        target = null
                        startedAtMs = 0L
                        val usable = !event.hasError() || RECOVERABLE_ERRORS.contains(event.error)
                        val hitLimit = event.hasError() && LIMIT_ERRORS.contains(event.error)
                        // "Something was encoded" is the test, not an arbitrary byte count: a
                        // finalized three-second clip is a few tens of kilobytes and still evidence.
                        val bytes = runCatching { event.recordingStats.numBytesRecorded }.getOrDefault(0L)
                        val hasContent = (bytes > 0L || duration > 0L) && file.length() > MIN_USABLE_BYTES
                        if (usable && hasContent) {
                            if (event.hasError()) Log.w(TAG, "partial clip kept: ${event.error}")
                            onFinished(file, plateForClip, duration, hitLimit)
                        } else {
                            // Only when there is genuinely nothing to keep. Evidence of a tail
                            // cannot be re-recorded, so a truncated clip beats no clip.
                            Log.w(TAG, "recording failed: ${event.error} bytes=$bytes")
                            runCatching { file.delete() }
                            // Somebody has to be told, or the failure is invisible in the worst
                            // possible way: the banner says "🎥 Запись", the follow engine still
                            // believes a clip is running, and that vehicle is never filmed again
                            // for the rest of the drive.
                            runCatching { onFailed(plateForClip, event.error) }
                        }
                    }
                }
            Log.i(TAG, "recording started for $plate -> ${file.name}")
            true
        }.getOrElse { error ->
            Log.w(TAG, "cannot start recording", error)
            target = null
            startedAtMs = 0L
            false
        }
    }

    fun stop() {
        val active = recording ?: return
        runCatching { active.stop() }
    }

    /**
     * A file name that cannot collide with a clip already on disk.
     *
     * The old `HHmmss` stamp repeated every day and every time two clips of the same car started
     * within a second; CameraX opens the path with truncation, so the earlier recording — the only
     * copy of it — was silently overwritten. The date and the milliseconds make the stamp unique in
     * practice, and the existence check makes it unique in fact.
     */
    private fun uniqueFile(plate: String): File {
        val dir = directory()
        val now = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            val stamp = STAMP.format(Date(now + attempt))
            val file = File(dir, "tail_${stamp}_${plate}.mp4")
            if (!file.exists()) return file
            attempt += 1
        }
    }

    /**
     * Evidence lives in files, not in the cache: Android empties the cache directory whenever it
     * needs space, and the one clip proving a car followed us must not be what it deletes.
     */
    private fun directory(): File = File(context.filesDir, "clips").apply { mkdirs() }

    companion object {
        /** Errors after which the file still holds the footage recorded up to that point. */
        val RECOVERABLE_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        )

        /** The subset that means "this file is full", not "recording went wrong". */
        val LIMIT_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
        )

        /**
         * Encoding rate asked of the device.
         *
         * 2.5 Mbps at 720p is generous for showing how a car behaves and far below what this
         * hardware picks unprompted. The plate never comes from here — it is read from the
         * full-resolution analysis stream — so the only thing the clip has to survive is being
         * uploaded.
         */
        const val TARGET_BITRATE = 2_500_000

        /**
         * Hard ceiling on one clip, safely under the 50 MB a Telegram bot may upload.
         *
         * The bitrate above is a request the encoder may ignore; this is enforced by CameraX, and
         * it finalizes the file properly on the way out, so hitting it costs a segment boundary
         * rather than the evidence.
         */
        const val MAX_CLIP_BYTES = 45L * 1024 * 1024

        /** Free space below which starting a clip only produces a stub. */
        const val MIN_FREE_BYTES = MAX_CLIP_BYTES + 64L * 1024 * 1024

        /** Below this the container holds only headers: no `moov`, no sample, nothing to play. */
        const val MIN_USABLE_BYTES = 4L * 1024

        const val TAG = "LensALPR.Video"

        /** Date and milliseconds: unique across days and across two clips started in one second. */
        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
    }
}
