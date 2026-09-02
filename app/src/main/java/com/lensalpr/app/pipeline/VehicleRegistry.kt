package com.lensalpr.app.pipeline

import android.graphics.Bitmap
import com.lensalpr.app.alpr.AlprOutcome
import com.lensalpr.app.alpr.CarInfo
import com.lensalpr.app.alpr.PlateFusion
import com.lensalpr.app.alpr.PlateReading
import com.lensalpr.app.alpr.PlateSimilarity

/** A confirmed vehicle as shown in the right-hand list. */
data class VehicleCard(
    val plate: String,
    val displayPlate: String,
    val make: String?,
    val model: String?,
    val year: String?,
    val color: String?,
    val bodyStyle: String?,
    val country: String?,
    val ocrScore: Float,
    val thumbnail: Bitmap?,
    /** The most recent frame of this car; what an encounter photo should be filed with. */
    val latestThumbnail: Bitmap?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confirmedAtMs: Long,
    val sightings: Int,
    val lenses: List<String>,
    /** Threat level rank from the follow engine, attached by the scanner. */
    val level: Int = 0,
    val levelName: String? = null,
    val reasons: String? = null,
) {
    val makeModel: String?
        get() = when {
            make != null && model != null -> "$make $model"
            make != null -> make
            model != null -> model
            else -> null
        }
}

/**
 * Turns a stream of noisy per-crop readings into confirmed vehicles.
 *
 * A single OCR hit is never trusted: a plate is published only after the same normalized string has
 * been read [requiredMatches] times for the same tracked vehicle. Until then the track carries a
 * visible "pending" candidate so the operator can see the engine converging.
 *
 * Confirmed plates are deduplicated globally, which matters with a rotating lens plan: the same car
 * seen again at 5x updates the existing card instead of creating a second one.
 *
 * Main thread only.
 */
class VehicleRegistry(
    private val requiredMatches: Int,
    private val minScore: Float,
    private val recognition: RecognitionState,
    private val maxVehicles: Int = 200,
) {

    enum class Change { NONE, PENDING, CONFIRMED, UPDATED }

    /** The change plus the affected card, so callers do not have to search the snapshot. */
    data class SubmitResult(val change: Change, val card: VehicleCard?)

    private class TrackConsensus {
        val counts = HashMap<String, Int>()

        /** Every reading of each merged group, so they can vote character by character. */
        val variants = HashMap<String, MutableList<PlateReading>>()
        var bestReading: PlateReading? = null
        var bestScore = 0f

        /** Not published yet: safe to recycle when a better frame arrives. */
        var candidateThumb: Bitmap? = null
        var car: CarInfo? = null
        var confirmed: String? = null
    }

    private class VehicleEntry(
        var plate: String,
        var displayPlate: String,
        var make: String?,
        var model: String?,
        var year: String?,
        var color: String?,
        var bodyStyle: String?,
        var country: String?,
        var ocrScore: Float,
        /** The best-read frame; what the on-screen card shows, because it is the clearest plate. */
        var thumbnail: Bitmap?,
        /**
         * The most recent frame, whatever it scored.
         *
         * [thumbnail] freezes on the best OCR result and never moves again, which is right for a
         * list you glance at and wrong for evidence: every encounter with a car read well once was
         * being filed with the same photograph, so a meeting at 15:40 carried a picture taken at
         * 12:10, on a different lens, in a different place. As a record of that meeting it was
         * simply false.
         */
        var latestThumbnail: Bitmap?,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        val confirmedAtMs: Long,
        var sightings: Int,
        val lenses: LinkedHashSet<String>,
        /** How many reads voted for the spelling this card currently carries. */
        var votes: Int,
    )

    private val consensus = HashMap<Int, TrackConsensus>()
    private val vehicles = LinkedHashMap<String, VehicleEntry>()

    /** Corroboration counter for readings whose track is gone; keyed by plate, with its age. */
    private val orphanCounts = HashMap<String, Pair<Int, Long>>()

    /** Votes rescued from tracks the lens rotation ended, waiting to be claimed by the same car. */
    private class Orphaned(val plate: String, val state: TrackConsensus, val atMs: Long)

    private val pool = ArrayList<Orphaned>()

    fun submit(job: OcrJob, outcome: AlprOutcome, nowMs: Long): SubmitResult {
        // A result can outlive its track: the queue holds crops for seconds, and a crop recovered
        // from disk belongs to a vehicle that left the picture minutes ago — or to a previous run of
        // the app entirely. Recreating the bookkeeping for a track that will never be lost again
        // leaks it forever, and matching it against a live track of the same number would merge two
        // unrelated cars. Such a reading can still tell us about a plate, and nothing more.
        val runtime = recognition.peek(job.trackId)
            ?: return submitOrphan(job, outcome, nowMs)
        runtime.inFlight = false

        val state = consensus.getOrPut(job.trackId) { TrackConsensus() }
        mergeCar(state, outcome)
        state.car?.makeModel?.let { runtime.makeModel = it }

        val reading = selectReading(outcome, minScore)

        if (reading == null) {
            // Even a plate-less frame can carry make/model, and its crop may be the only image the
            // vehicle ever gets, so keep it when nothing better exists.
            if (state.candidateThumb == null && outcome.cars.isNotEmpty()) {
                state.candidateThumb = job.thumbnail
            } else {
                recycle(job.thumbnail)
            }
            return SubmitResult(Change.NONE, null)
        }

        adoptPooled(state, reading, nowMs)

        // One character flipping between frames is the engine, not another car: those readings share
        // a counter and the best-scoring spelling of the group represents it.
        val group = state.counts.keys.firstOrNull { PlateSimilarity.similar(it, reading.text) }
            ?: reading.text
        val count = (state.counts[group] ?: 0) + 1
        state.counts[group] = count
        val history = state.variants.getOrPut(group) { ArrayList(PlateFusion.MAX_READINGS) }
        history += reading
        if (history.size > PlateFusion.MAX_READINGS) history.removeAt(0)
        // Reads of one car disagree one character at a time; letting them vote turns three
        // imperfect reads into the plate none of them got right on its own.
        val fused = PlateFusion.fuse(history)
        val winner = fused?.reading ?: reading
        // Evidence means reads that actually back this spelling, not reads of this car in general:
        // otherwise one hallucinated character inherits the weight of every good read before it.
        val votes = fused?.support ?: 1
        runtime.pendingPlate = winner.text
        runtime.pendingCount = count

        val betterFrame = reading.recognitionScore >= state.bestScore
        if (betterFrame) {
            state.bestScore = reading.recognitionScore
            state.bestReading = reading
        }

        // A confirmed track keeps refining its own plate. A different string is treated as a fresh
        // candidate instead of being allowed to rewrite the vehicle that was already published.
        val alreadyConfirmed = state.confirmed
        if (alreadyConfirmed != null && PlateSimilarity.similar(alreadyConfirmed, reading.text)) {
            val entry = vehicles[alreadyConfirmed]
            if (entry != null) {
                touch(entry, job, winner, state, nowMs, betterFrame, votes)
                state.confirmed = entry.plate
                recycleUnused(state, job, entry)
                return SubmitResult(Change.UPDATED, entry.toCard())
            }
        }

        if (count < requiredMatches) {
            if (betterFrame) {
                recycle(state.candidateThumb)
                state.candidateThumb = job.thumbnail
            } else {
                recycle(job.thumbnail)
            }
            return SubmitResult(Change.PENDING, null)
        }

        // Confirmed.
        val thumbnail = if (betterFrame) job.thumbnail else state.candidateThumb ?: job.thumbnail
        if (thumbnail !== job.thumbnail) recycle(job.thumbnail)
        if (thumbnail !== state.candidateThumb) recycle(state.candidateThumb)
        state.candidateThumb = null

        // The same car read a minute ago under another lens must land on its own card, even if that
        // read spelled one character differently.
        val existing = vehicles[winner.text] ?: vehicles.values.firstOrNull {
            PlateSimilarity.similar(it.plate, winner.text)
        }
        if (existing != null) {
            existing.lastSeenMs = nowMs
            existing.sightings += 1
            existing.lenses += job.lensLabel
            val renamed = upgradePlate(existing, winner, votes)
            if (renamed || winner.recognitionScore > existing.ocrScore) {
                existing.ocrScore = winner.recognitionScore
                existing.thumbnail = thumbnail
            }
            // Always the newest frame, and therefore published: it may be handed to the evidence
            // writer on another thread, so it must not be recycled here. The frame it replaces
            // goes to the GC, following the same rule as every other published bitmap.
            existing.latestThumbnail = thumbnail
            applyAttributes(existing, state.car ?: winner.car)
            state.confirmed = existing.plate
            runtime.confirmedPlate = existing.plate
            return SubmitResult(Change.UPDATED, existing.toCard())
        }

        state.confirmed = winner.text
        runtime.confirmedPlate = winner.text
        val entry = createEntry(winner, state.car ?: winner.car, thumbnail, job.lensLabel, nowMs, votes)
        trim()
        return SubmitResult(Change.CONFIRMED, entry.toCard())
    }

    /**
     * A reading whose track no longer exists — a crop that waited too long in the queue, or one
     * recovered from disk, possibly from a previous run.
     *
     * It cannot join any track's consensus, but the pixels were real and the plate is worth having.
     * So it either corroborates a card we already hold, or it accumulates against its own plate
     * until it has met the same bar a live vehicle has to meet.
     */
    private fun submitOrphan(job: OcrJob, outcome: AlprOutcome, nowMs: Long): SubmitResult {
        val reading = selectReading(outcome, minScore)
        if (reading == null) {
            recycle(job.thumbnail)
            return SubmitResult(Change.NONE, null)
        }

        val existing = vehicles[reading.text] ?: vehicles.values.firstOrNull {
            PlateSimilarity.similar(it.plate, reading.text)
        }
        if (existing != null) {
            existing.lastSeenMs = maxOf(existing.lastSeenMs, nowMs)
            existing.sightings += 1
            existing.lenses += job.lensLabel
            val renamed = upgradePlate(existing, reading, 1)
            if (renamed || reading.recognitionScore > existing.ocrScore) {
                existing.ocrScore = reading.recognitionScore
                existing.thumbnail = job.thumbnail
            } else {
                recycle(job.thumbnail)
            }
            applyAttributes(existing, reading.car)
            return SubmitResult(Change.UPDATED, existing.toCard())
        }

        // Aged out rather than merely capped: a tally kept from an hour ago is not corroboration,
        // and two different cars one confusable character apart would otherwise share it forever.
        pruneOrphanCounts(nowMs)
        val group = orphanCounts.keys.firstOrNull { PlateSimilarity.similar(it, reading.text) }
            ?: reading.text
        val count = (orphanCounts[group]?.first ?: 0) + 1
        orphanCounts[group] = count to nowMs
        if (orphanCounts.size > MAX_ORPHAN_GROUPS) orphanCounts.clear()
        if (count < requiredMatches) {
            recycle(job.thumbnail)
            return SubmitResult(Change.PENDING, null)
        }
        orphanCounts.remove(group)
        val entry = createEntry(reading, reading.car, job.thumbnail, job.lensLabel, nowMs, count)
        trim()
        return SubmitResult(Change.CONFIRMED, entry.toCard())
    }

    private fun createEntry(
        reading: PlateReading,
        car: CarInfo?,
        thumbnail: Bitmap,
        lens: String,
        nowMs: Long,
        votes: Int,
    ): VehicleEntry {
        val entry = VehicleEntry(
            plate = reading.text,
            displayPlate = reading.display,
            make = car?.make,
            model = car?.model,
            year = car?.year,
            color = car?.color,
            bodyStyle = car?.bodyStyle,
            country = reading.countryName ?: reading.countryCode,
            ocrScore = reading.recognitionScore,
            thumbnail = thumbnail,
            latestThumbnail = thumbnail,
            firstSeenMs = nowMs,
            lastSeenMs = nowMs,
            confirmedAtMs = nowMs,
            sightings = 1,
            lenses = linkedSetOf(lens),
            votes = votes,
        )
        vehicles[reading.text] = entry
        return entry
    }

    /**
     * A track has ended — usually because the lens rotated and the tracker had to start over, since
     * geometry from one field of view cannot be matched against another.
     *
     * The votes it had collected are still about a real car that is very probably still in the
     * picture, so they are parked for a few seconds instead of being thrown away. Without this, a
     * plan that changes lens every five seconds can keep resetting a vehicle to zero reads and never
     * confirm it at all.
     */
    fun onTrackLost(trackId: Int) {
        val state = consensus.remove(trackId)
        recognition.remove(trackId)
        if (state == null) return
        val pending = state.counts.keys.firstOrNull()
        if (pending == null || state.confirmed != null) {
            recycle(state.candidateThumb)
            return
        }
        // Park the group that actually has the votes, labelled by its own plate. Handing over the
        // whole consensus would carry every other group with it, and those were never compared
        // against the car that adopts them.
        val label = state.counts.maxByOrNull { it.value }?.key ?: pending
        val parked = TrackConsensus().apply {
            counts[label] = state.counts.getValue(label)
            state.variants[label]?.let { variants[label] = it }
            car = state.car
            bestScore = state.bestScore
            candidateThumb = state.candidateThumb
        }
        state.candidateThumb = null
        pool += Orphaned(label, parked, System.currentTimeMillis())
        while (pool.size > MAX_POOLED) pool.removeAt(0).let { recycle(it.state.candidateThumb) }
    }

    private fun pruneOrphanCounts(nowMs: Long) {
        orphanCounts.entries.removeAll { nowMs - it.value.second > ORPHAN_TALLY_TTL_MS }
    }

    /** Drops parked consensus that is too old to belong to anything still on the road. */
    private fun prunePool(nowMs: Long) {
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.atMs > POOL_TTL_MS) {
                recycle(entry.state.candidateThumb)
                iterator.remove()
            }
        }
    }

    /** Reclaims the votes a car had collected under the previous lens, if this is that car. */
    private fun adoptPooled(state: TrackConsensus, reading: PlateReading, nowMs: Long) {
        prunePool(nowMs)
        if (state.counts.isNotEmpty()) return
        val index = pool.indexOfFirst { PlateSimilarity.similar(it.plate, reading.text) }
        if (index < 0) return
        val parked = pool.removeAt(index).state
        // Two different cars can carry near-identical plates; if the classifiers disagree about
        // what they are looking at, the votes belong to neither.
        val here = state.car?.make
        val there = parked.car?.make
        if (here != null && there != null && here != there) {
            recycle(parked.candidateThumb)
            return
        }
        state.counts.putAll(parked.counts)
        state.variants.putAll(parked.variants)
        if (state.car == null) state.car = parked.car
        if (state.candidateThumb == null) {
            state.candidateThumb = parked.candidateThumb
        } else {
            recycle(parked.candidateThumb)
        }
        state.bestScore = maxOf(state.bestScore, parked.bestScore)
    }

    fun snapshot(): List<VehicleCard> = vehicles.values
        .sortedByDescending { it.confirmedAtMs }
        .map { entry -> entry.toCard() }

    private fun VehicleEntry.toCard(): VehicleCard = VehicleCard(
        plate = plate,
        displayPlate = displayPlate,
        make = make,
        model = model,
        year = year,
        color = color,
        bodyStyle = bodyStyle,
        country = country,
        ocrScore = ocrScore,
        thumbnail = thumbnail,
        latestThumbnail = latestThumbnail ?: thumbnail,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        confirmedAtMs = confirmedAtMs,
        sightings = sightings,
        lenses = lenses.toList(),
    )

    /** Operator correction: move a published card onto another plate. */
    fun rename(from: String, to: String, display: String): Boolean {
        val entry = vehicles.remove(from) ?: return false
        val existing = vehicles[to]
        if (existing != null) {
            existing.sightings += entry.sightings
            existing.lenses += entry.lenses
            existing.lastSeenMs = maxOf(existing.lastSeenMs, entry.lastSeenMs)
            if (entry.ocrScore > existing.ocrScore) {
                existing.ocrScore = entry.ocrScore
                existing.thumbnail = entry.thumbnail
            }
            existing.votes = Int.MAX_VALUE
            return true
        }
        entry.plate = to
        entry.displayPlate = display
        // The operator outranks any amount of voting.
        entry.votes = Int.MAX_VALUE
        vehicles[to] = entry
        return true
    }

    fun clear() {
        consensus.values.forEach { recycle(it.candidateThumb) }
        consensus.clear()
        pool.forEach { recycle(it.state.candidateThumb) }
        pool.clear()
        orphanCounts.clear()
        vehicles.clear()
        recognition.clear()
    }

    private fun touch(
        entry: VehicleEntry,
        job: OcrJob,
        reading: PlateReading,
        state: TrackConsensus,
        nowMs: Long,
        betterFrame: Boolean,
        votes: Int,
    ) {
        entry.lastSeenMs = nowMs
        entry.sightings += 1
        entry.lenses += job.lensLabel
        val renamed = upgradePlate(entry, reading, votes)
        if (renamed || (betterFrame && reading.recognitionScore > entry.ocrScore)) {
            entry.ocrScore = reading.recognitionScore
            if (betterFrame) entry.thumbnail = job.thumbnail
        }
        // Evidence wants the latest look at the car, not the luckiest one.
        if (betterFrame) entry.latestThumbnail = job.thumbnail
        applyAttributes(entry, state.car ?: reading.car)
    }

    /**
     * Adopts the spelling with the most evidence behind it and re-keys the card so later readings
     * find it under the winning plate.
     *
     * Votes outrank confidence on purpose. One lucky frame reading `EN-7209` at 88% used to freeze
     * that spelling forever, because every later read of the true `EM-7209` scored a little lower —
     * yet three reads agreeing is stronger evidence than one read being sure. Only when the evidence
     * is equally thin does the score decide.
     *
     * @return true when the card changed its plate.
     */
    private fun upgradePlate(entry: VehicleEntry, reading: PlateReading, votes: Int): Boolean {
        if (entry.plate == reading.text) {
            entry.votes = maxOf(entry.votes, votes)
            if (reading.recognitionScore > entry.ocrScore) entry.displayPlate = reading.display
            return false
        }
        val keepExisting = when {
            votes > entry.votes -> false
            votes < entry.votes -> true
            else -> PlateSimilarity.prefer(
                entry.plate,
                entry.ocrScore,
                reading.text,
                reading.recognitionScore,
            )
        }
        if (keepExisting) return false
        vehicles.remove(entry.plate)
        // The winning spelling may already have a card of its own — two tracks of the same car
        // converging. The other card is folded *into this one* rather than the other way round: the
        // caller goes on writing to `entry`, and a card the registry no longer lists would collect
        // the score, the photo and — through the returned card — the follow evidence of a plate
        // nobody can look up.
        val collision = vehicles.remove(reading.text)
        if (collision != null && collision !== entry) {
            entry.sightings += collision.sightings
            entry.lenses += collision.lenses
            entry.lastSeenMs = maxOf(entry.lastSeenMs, collision.lastSeenMs)
            entry.votes = maxOf(entry.votes, collision.votes)
            if (collision.ocrScore > entry.ocrScore) {
                entry.ocrScore = collision.ocrScore
                entry.thumbnail = collision.thumbnail
            }
            // Whichever half saw the car more recently owns the evidence frame.
            if (collision.lastSeenMs >= entry.lastSeenMs && collision.latestThumbnail != null) {
                entry.latestThumbnail = collision.latestThumbnail
            }
            entry.make = entry.make ?: collision.make
            entry.model = entry.model ?: collision.model
            entry.color = entry.color ?: collision.color
            entry.bodyStyle = entry.bodyStyle ?: collision.bodyStyle
            entry.country = entry.country ?: collision.country
        }
        entry.plate = reading.text
        entry.displayPlate = reading.display
        entry.votes = votes
        vehicles[entry.plate] = entry
        return true
    }

    private fun recycleUnused(
        state: TrackConsensus,
        job: OcrJob,
        entry: VehicleEntry,
    ) {
        // Both slots, not just the visible one. The card keeps the best-read frame and the
        // evidence keeps the newest, and a frame that became the newest without beating the best
        // is held only by the second — recycling it here would hand the evidence writer a bitmap
        // that has already been freed.
        if (entry.thumbnail !== job.thumbnail && entry.latestThumbnail !== job.thumbnail) {
            recycle(job.thumbnail)
        }
        recycle(state.candidateThumb)
        state.candidateThumb = null
    }

    private fun applyAttributes(entry: VehicleEntry, car: CarInfo?) {
        if (car == null) return
        if (entry.make == null) entry.make = car.make
        if (entry.model == null) entry.model = car.model
        if (entry.year == null) entry.year = car.year
        if (entry.color == null) entry.color = car.color
        if (entry.bodyStyle == null) entry.bodyStyle = car.bodyStyle
    }

    private fun mergeCar(state: TrackConsensus, outcome: AlprOutcome) {
        val candidates = ArrayList<CarInfo>(outcome.cars)
        outcome.plates.mapNotNullTo(candidates) { it.car }
        val best = candidates.maxByOrNull { it.makeModelConfidence } ?: return
        val current = state.car
        if (current == null || best.makeModelConfidence > current.makeModelConfidence) {
            state.car = best
        }
    }

    private fun trim() {
        if (vehicles.size <= maxVehicles) return
        val oldest = vehicles.values.minByOrNull { it.confirmedAtMs } ?: return
        vehicles.remove(oldest.plate)
    }

    companion object {
        /** Beyond this the orphan tally is stale bookkeeping rather than evidence. */
        private const val MAX_ORPHAN_GROUPS = 200

        /** Recovered crops of one car arrive within minutes of each other, not hours. */
        private const val ORPHAN_TALLY_TTL_MS = 5L * 60_000L

        /** How long votes survive without a track. Long enough for a lens change, no longer. */
        private const val POOL_TTL_MS = 8_000L
        private const val MAX_POOLED = 24

        /**
         * The one reading a result is judged by.
         *
         * Everything downstream — the consensus vote, the card, and the remembered plate position —
         * has to agree on which of several detected plates the result is about, or the anchor ends
         * up pointing at the neighbouring car's plate while the card records this one's.
         */
        fun selectReading(outcome: AlprOutcome, minScore: Float): PlateReading? = outcome.plates
            .filter { it.recognitionScore >= minScore }
            .maxByOrNull { it.recognitionScore }
    }

    /** Only unpublished bitmaps are recycled; anything the list may still draw is left to the GC. */
    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }
}
