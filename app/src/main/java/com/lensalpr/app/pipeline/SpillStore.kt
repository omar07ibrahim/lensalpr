package com.lensalpr.app.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A crop that waited on disk, with the moment and place it was actually taken. */
class SpilledCrop(
    val bitmap: Bitmap,
    /**
     * The picture of the whole vehicle that was cut alongside the crop, when one was saved. A
     * narrow crop shows only the plate, and without this the card and the encounter photo of a
     * car recovered from disk would be a picture of a number rather than of a car.
     */
    val thumbnail: Bitmap?,
    val trackId: Int,
    val capturedAtMs: Long,
    val lensLabel: String,
    val quality: Float,
    val priority: Float,
    val lat: Double?,
    val lon: Double?,
    val odometerM: Double,
    val narrow: Boolean,
    /**
     * True when this crop was written by an earlier run of the app. Its track id belongs to that
     * run's numbering and must never be matched against a live track, or a recovered read would be
     * merged into whichever unrelated vehicle happens to hold the same id today.
     */
    val carriedOver: Boolean,
)

/**
 * Disk overflow for the recognition queue.
 *
 * When traffic arrives faster than the engine reads plates, the choice used to be between dropping
 * a crop and stalling the camera. A third option is better: park the crop on disk and read it when
 * the engine has a gap. The pixels do not expire, and because every crop carries the timestamp,
 * lens and position of the moment it was taken, a plate recognized a minute later still lands on
 * the map exactly where the vehicle really was.
 *
 * Crops from a previous run are kept and read, but flagged: track ids restart with every scan, so
 * such a crop can only ever contribute a plate, never join the consensus of a live vehicle.
 */
class SpillStore(private val dir: File, private val maxFiles: Int = 600, private val maxBytes: Long = 400L * 1024 * 1024) {

    private val sequence = AtomicInteger(0)

    /** Highest index written by a previous run; everything up to it is carried over. */
    private val carriedCeiling = AtomicInteger(-1)
    private val queued = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    val count: Int get() = queued.get()

    /**
     * Picks up whatever the previous run could not finish.
     *
     * Each crop carries its own time and position, so a plate photographed before the app was
     * closed is still worth reading - that was the whole point of parking it on disk.
     */
    @Synchronized
    fun adopt(): Int {
        val images = runCatching { dir.apply { mkdirs() }.listFiles { file -> file.name.endsWith(".jpg") } }
            .getOrNull()
            .orEmpty()
        val highest = images.mapNotNull(::indexOf).maxOrNull() ?: -1
        sequence.set(highest + 1)
        carriedCeiling.set(highest)
        queued.set(images.size)
        bytes.set(images.sumOf { it.length() })
        return images.size
    }

    /**
     * Throws the parked crops away; only a wipe wants this.
     *
     * Under the same monitor as [offer] and [poll]: a wipe arrives from the bot while the writer
     * may be mid-file and the drain loop mid-read, and clearing the counters underneath either of
     * them left a phantom queue depth and a reused index.
     */
    @Synchronized
    fun reset() {
        runCatching {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }
        sequence.set(0)
        carriedCeiling.set(-1)
        queued.set(0)
        bytes.set(0)
    }

    @Synchronized
    fun offer(job: OcrJob): Boolean {
        if (queued.get() >= maxFiles || bytes.get() >= maxBytes) return false
        val index = sequence.getAndIncrement()
        return runCatching {
            val bitmap = Bitmap.createBitmap(job.strideInPixels, job.height, Bitmap.Config.ARGB_8888)
            job.buffer.rewind()
            bitmap.copyPixelsFromBuffer(job.buffer)
            job.buffer.rewind()
            // Written under a temporary name and renamed last: a crash mid-compress would otherwise
            // leave a truncated JPEG that the drain path decodes into garbage — or into nothing,
            // while still counting as one of the crops that were promised not to be lost.
            val pending = File(dir, "spill_%05d.tmp".format(index))
            val image = File(dir, "spill_%05d.jpg".format(index))
            FileOutputStream(pending).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            bitmap.recycle()

            // The whole-vehicle picture travels with the crop, so a plate read from disk still
            // gets a photograph of the car. Written before the index file: without it the crop is
            // still readable, so its absence is a degraded photo, not a lost read.
            val thumb = File(dir, "spill_%05d.thumb".format(index))
            val thumbnail = job.thumbnail
            if (!thumbnail.isRecycled) {
                runCatching {
                    FileOutputStream(thumb).use { output ->
                        thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, output)
                    }
                }.onFailure { runCatching { thumb.delete() } }
            }

            val meta = JSONObject()
                .put("track", job.trackId)
                .put("t", job.submittedAtMs)
                .put("lens", job.lensLabel)
                .put("q", job.quality.toDouble())
                .put("p", job.priority.toDouble())
                .put("narrow", job.narrow)
            // JSON has no NaN, and `put` throws on one — which would take the whole spill down and
            // silently turn "park this crop for later" into "throw this crop away". An unknown
            // odometer is written by being absent, and read back as unknown.
            if (job.odometerM.isFinite()) meta.put("odo", job.odometerM)
            job.lat?.let { meta.put("lat", it) }
            job.lon?.let { meta.put("lon", it) }
            File(dir, "spill_%05d.json".format(index)).writeText(meta.toString())
            if (!pending.renameTo(image)) {
                pending.delete()
                thumb.delete()
                return false
            }

            queued.incrementAndGet()
            bytes.addAndGet(image.length())
            true
        }.getOrElse { error ->
            Log.w(TAG, "spill failed", error)
            false
        }
    }

    /**
     * Newest first: a car that just passed is still worth alerting on, an old one is history.
     *
     * Synchronised against [offer]: the writer and the drain loop share this directory. Ordered by
     * the numeric index, not by the file name — the name is only zero-padded to five digits, and a
     * long backlog past 99999 would otherwise sort "spill_100000" before "spill_99999".
     */
    @Synchronized
    fun poll(): SpilledCrop? {
        val images = dir.listFiles { file -> file.name.endsWith(".jpg") } ?: return null
        val newest = images.maxByOrNull { indexOf(it) ?: -1 } ?: return null
        val index = indexOf(newest) ?: -1
        val base = newest.name.removeSuffix(".jpg")
        val metaFile = File(dir, "$base.json")
        val thumbFile = File(dir, "$base.thumb")
        val result = runCatching {
            val bitmap = BitmapFactory.decodeFile(newest.absolutePath) ?: return@runCatching null
            // Without its metadata the crop has no time and no place, so a recognition from it would
            // be pinned to wherever the car happens to be now — worse than not reading it at all.
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            if (meta == null) {
                bitmap.recycle()
                return@runCatching null
            }
            val thumbnail = if (thumbFile.exists()) {
                runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath) }.getOrNull()
            } else {
                null
            }
            SpilledCrop(
                bitmap = bitmap,
                thumbnail = thumbnail,
                trackId = meta.optInt("track", -1),
                capturedAtMs = meta.optLong("t", System.currentTimeMillis()),
                lensLabel = meta.optString("lens", "?"),
                quality = meta.optDouble("q", 0.0).toFloat(),
                priority = meta.optDouble("p", 0.0).toFloat(),
                lat = if (meta.has("lat")) meta.optDouble("lat") else null,
                lon = if (meta.has("lon")) meta.optDouble("lon") else null,
                // Absent means the crop was taken with no position fix. Zero would be a lie the
                // follow engine cannot detect: it reads as "this car travelled nowhere with us".
                odometerM = if (meta.has("odo")) meta.optDouble("odo", Double.NaN) else Double.NaN,
                narrow = meta.optBoolean("narrow", false),
                carriedOver = index <= carriedCeiling.get(),
            )
        }.getOrNull()

        bytes.addAndGet(-newest.length())
        newest.delete()
        metaFile.delete()
        thumbFile.delete()
        queued.decrementAndGet()
        return result
    }

    private fun indexOf(file: File): Int? =
        file.name.removePrefix("spill_").removeSuffix(".jpg").toIntOrNull()

    private companion object {
        const val TAG = "LensALPR.Spill"
        const val JPEG_QUALITY = 95
        const val THUMB_QUALITY = 85
    }
}
