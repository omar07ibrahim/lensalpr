package com.lensalpr.app.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts a clip too large to upload into pieces that fit.
 *
 * A bot may hand Telegram 50 MB. Anything past that was set aside on the phone under its own name
 * and never sent — which meant the one recording proving a car followed us stayed on the device
 * that might be taken, while the driver got a refusal instead of evidence. Clips recorded before
 * the encoder was pinned down run 60-190 MB, so this is not a corner case.
 *
 * The pieces are *remuxed*, never re-encoded: samples are copied across untouched and cuts land on
 * keyframes, so nothing is re-compressed, quality is identical to the original, and a ninety-second
 * clip splits in a second or two instead of occupying the CPU that recognition needs. Timestamps
 * are rebased per part, so each piece plays from its own beginning rather than starting with a
 * minute of black.
 */
object ClipSplitter {

    private const val TAG = "LensALPR.Split"

    /** Fallback sample buffer when the format does not declare one. */
    private const val DEFAULT_BUFFER_BYTES = 2 * 1024 * 1024

    /** Container tables and index overhead a part carries beyond its sample payload. */
    private const val CONTAINER_SLACK_BYTES = 512L * 1024

    /**
     * Assumed size of one group of pictures until a real one has been measured.
     *
     * A cut may only land on a keyframe, so a part always absorbs whatever follows the last one it
     * could have stopped at. Reserving nothing for that is what made the first version overshoot
     * the ceiling and throw the whole split away.
     */
    private const val INITIAL_GOP_ESTIMATE = 2L * 1024 * 1024

    /** Budget shrink applied when a pass still produced an oversized part. */
    private const val RETRY_SHRINK = 0.8

    /** Passes allowed before giving up; each one is a full remux of the file. */
    private const val MAX_PASSES = 3

    /**
     * Below this a part holds headers and nothing else.
     *
     * Deliberately small: a valid last piece is one group of pictures, which at the clip bitrate
     * can be a few tens of kilobytes. A 64 KiB floor threw away every such piece — and with it the
     * whole split, three passes in a row, so the recording stayed on the phone as "unsplittable".
     */
    private const val MIN_USABLE_BYTES = 4L * 1024

    /**
     * Splits [source] into parts of at most [maxBytes], written next to it.
     *
     * Returns the parts in order, or an empty list if the clip could not be split — in which case
     * the caller still has the original and should say so rather than pretend it was delivered.
     * The source file is left alone; deleting it is the caller's decision, and only after the
     * pieces are known to have arrived.
     */
    fun split(source: File, maxBytes: Long): List<File> {
        if (!source.exists() || source.length() <= 0L) return emptyList()
        var budget = maxBytes - CONTAINER_SLACK_BYTES
        repeat(MAX_PASSES) { pass ->
            if (budget <= MIN_USABLE_BYTES) return emptyList()
            val parts = attempt(source, budget, maxBytes)
            if (parts.isNotEmpty()) return parts
            // A pass fails when a part came out over the ceiling despite the reservation — the
            // stream's own structure is coarser than expected. Ask for less and try again rather
            // than discarding pieces that were perfectly good.
            budget = (budget * RETRY_SHRINK).toLong()
            Log.w(TAG, "${source.name}: pass ${pass + 1} failed; retrying with ${budget / (1024 * 1024)}MB")
        }
        return emptyList()
    }

    /** One full remux pass. Returns the finished parts, or an empty list if this pass is unusable. */
    private fun attempt(source: File, budget: Long, maxBytes: Long): List<File> {
        val extractor = MediaExtractor()
        // Written under temporary names and renamed only once the whole split has been validated.
        // The finished names match the resend queue's pattern, so a half-written piece left behind
        // by a crash would otherwise be picked up on the next start and uploaded as evidence.
        val pending = ArrayList<File>()
        var muxer: MediaMuxer? = null
        var partIndex = 0
        var ok = true
        try {
            extractor.setDataSource(source.absolutePath)

            val sourceTracks = ArrayList<Int>()
            val formats = ArrayList<MediaFormat>()
            var videoTrack = -1
            var rotation = 0
            var bufferBytes = DEFAULT_BUFFER_BYTES
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = sourceTracks.size
                    // Carried across explicitly. The rotation lives in the container, not in the
                    // samples, so a piece written without it plays on its side — and a clip filmed
                    // by a phone clamped to the windscreen is always rotated.
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }
                            .getOrDefault(0)
                    }
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferBytes = maxOf(bufferBytes, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                sourceTracks += index
                formats += format
                extractor.selectTrack(index)
            }
            if (sourceTracks.isEmpty() || videoTrack < 0) {
                Log.w(TAG, "${source.name}: no video track to split")
                return emptyList()
            }

            val buffer = ByteBuffer.allocate(bufferBytes)
            val info = MediaCodec.BufferInfo()
            var muxerTracks = IntArray(0)
            var written = 0L
            var timeOffsetUs = -1L
            // The largest run of samples seen between two keyframes. A cut can only happen at the
            // start of such a run, so this much has to stay free or the part overshoots.
            var gopTail = INITIAL_GOP_ESTIMATE
            var writtenAtSync = 0L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val slot = sourceTracks.indexOf(extractor.sampleTrackIndex)
                if (slot < 0) {
                    extractor.advance()
                    continue
                }
                val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                val atCut = slot == videoTrack && isSync

                if (atCut) {
                    gopTail = maxOf(gopTail, written - writtenAtSync)
                    writtenAtSync = written
                }

                // A part may only end on a keyframe of the video track: cutting anywhere else
                // produces a piece whose first frames reference data that stayed behind, and a
                // player shows either nothing or a smear until the next keyframe arrives. The
                // reservation is what makes the decision safe — everything up to the *next*
                // keyframe still has to fit after this one is written.
                if (muxer != null && atCut && written + sampleSize + gopTail > budget) {
                    if (!finish(muxer)) ok = false
                    muxer = null
                }

                if (muxer == null) {
                    partIndex += 1
                    val part = File(source.parentFile, partName(source, partIndex) + ".tmp")
                    runCatching { part.delete() }
                    val next = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    // Owned by the cleanup paths from this moment on: addTrack and start can both
                    // throw, and a muxer that is not yet in `muxer` would otherwise keep its file
                    // handle and its temporary file for the rest of the process.
                    muxer = next
                    pending += part
                    if (rotation != 0) runCatching { next.setOrientationHint(rotation) }
                    muxerTracks = IntArray(formats.size) { next.addTrack(formats[it]) }
                    next.start()
                    written = 0L
                    writtenAtSync = 0L
                    timeOffsetUs = -1L
                }

                if (timeOffsetUs < 0L) timeOffsetUs = extractor.sampleTime
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = (extractor.sampleTime - timeOffsetUs).coerceAtLeast(0L)
                // MediaExtractor and MediaCodec disagree about the name of the sync bit; only the
                // keyframe flag survives the trip, and it is the only one a muxer acts on.
                info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                // The muxer reads the buffer through its own position and limit, not through the
                // offset and size in the info — a documented trap that produces files whose frames
                // are silently truncated. readSampleData leaves the position at zero, so the limit
                // is the part that has to be said out loud.
                buffer.position(0)
                buffer.limit(sampleSize)
                muxer.writeSampleData(muxerTracks[slot], buffer, info)
                buffer.clear()
                written += sampleSize
                extractor.advance()
            }
            if (!finish(muxer)) ok = false
            muxer = null

            // One part means the clip was never really oversized, or the whole thing sat between
            // two keyframes. Either way the caller gains nothing from a copy of the original.
            if (!ok || pending.size < 2) {
                Log.w(TAG, "${source.name}: ${pending.size} part(s), muxing ok=$ok")
                return discard(pending)
            }
            val bad = pending.filter { it.length() > maxBytes || it.length() < MIN_USABLE_BYTES }
            if (bad.isNotEmpty()) {
                Log.w(TAG, "${source.name}: ${bad.size} unusable part(s)")
                return discard(pending)
            }
            // Only now do the pieces take the names the uploader and the resend queue look for.
            // Every piece already published is remembered so a failure on the next one rolls the
            // whole set back — a half-published set would be picked up by the resend queue as if
            // it were the complete recording.
            val finished = ArrayList<File>(pending.size)
            for (tmp in pending) {
                val target = File(tmp.parentFile, tmp.name.removeSuffix(".tmp"))
                runCatching { target.delete() }
                if (!tmp.renameTo(target)) {
                    Log.w(TAG, "cannot rename ${tmp.name}")
                    discard(finished)
                    return discard(pending)
                }
                finished += target
            }
            Log.i(TAG, "${source.name}: split into ${finished.size} parts")
            return finished
        } catch (error: Throwable) {
            Log.w(TAG, "cannot split ${source.name}", error)
            runCatching { finish(muxer) }
            return discard(pending)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun discard(parts: List<File>): List<File> {
        parts.forEach { runCatching { it.delete() } }
        return emptyList()
    }

    /**
     * Closes a part and reports whether it is actually finished.
     *
     * `stop` is where the muxer writes the index without which the file is a headerless blob no
     * player will open. Swallowing a failure here shipped exactly that to Telegram and called it
     * evidence, so the outcome has to travel back to the caller.
     */
    private fun finish(muxer: MediaMuxer?): Boolean {
        if (muxer == null) return true
        val stopped = runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        if (!stopped) Log.w(TAG, "muxer.stop() failed; part is incomplete")
        return stopped
    }

    private fun partName(source: File, index: Int): String =
        source.name.removeSuffix(".mp4") + "_part%02d".format(index) + ".mp4"
}
