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

    /**
     * The change plus the affected card, so callers do not have to search the snapshot.
     *
     * [score] is the confidence of the read that produced this result — what the encounter photo
     * taken from this very frame is worth — as opposed to the card's best score ever.
     */
    data class SubmitResult(val change: Change, val card: VehicleCard?, val score: Float = 0f)

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
        /** Confidence of the classifier answer the make, model and year were taken from. */
        var attributeConfidence: Float = 0f,
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
        // A crop read late from disk says nothing about the crop the scheduler is waiting for
        // now; only the live job may release its own track.
        if (!job.deferred) runtime.inFlight = false

        val state = consensus.getOrPut(job.trackId) { TrackConsensus() }
        val reading = selectReading(outcome, minScore, job)
        mergeCar(state, outcome, reading)
        state.car?.makeModel?.let { runtime.makeModel = it }

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
                // The box label follows the card: a spelling refined by later votes used to stay
                // on the frame as the old reading while the list showed the corrected one.
                runtime.confirmedPlate = entry.plate
                recycleUnused(state, job, entry)
                return SubmitResult(Change.UPDATED, entry.toCard(), reading.recognitionScore)
            }
        }

        if (count < requiredMatches) {
            if (betterFrame) {
                recycle(state.candidateThumb)
                state.candidateThumb = job.thumbnail
            } else {
                recycle(job.thumbnail)
            }
            return SubmitResult(Change.PENDING, null, reading.recognitionScore)
        }

        // Confirmed.
        val thumbnail = if (betterFrame) job.thumbnail else state.candidateThumb ?: job.thumbnail
        if (thumbnail !== job.thumbnail) recycle(job.thumbnail)
        if (thumbnail !== state.candidateThumb) recycle(state.candidateThumb)
        state.candidateThumb = null

        // The same car read a minute ago under another lens must land on its own card, even if that
        // read spelled one character differently — unless that card belongs to a car that is in
        // the picture *right now* on another track. Two vehicles side by side with plates one
        // confusable character apart are two vehicles, and folding one into the other hid the
        // very car the operator would have wanted to see.
        val existing = vehicles[winner.text] ?: vehicles.values.firstOrNull {
            PlateSimilarity.similar(it.plate, winner.text) && !confirmedOnAnotherLiveTrack(it.plate, job.trackId)
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
            return SubmitResult(Change.UPDATED, existing.toCard(), reading.recognitionScore)
        }

        state.confirmed = winner.text
        runtime.confirmedPlate = winner.text
        val entry = createEntry(winner, state.car ?: winner.car, thumbnail, job.lensLabel, nowMs, votes)
        trim()
        return SubmitResult(Change.CONFIRMED, entry.toCard(), reading.recognitionScore)
    }

    /** True when a *different* live track has this plate as its confirmed reading. */
    private fun confirmedOnAnotherLiveTrack(plate: String, trackId: Int): Boolean =
        consensus.any { (id, state) -> id != trackId && state.confirmed == plate }

    /**
     * A reading whose track no longer exists — a crop that waited too long in the queue, or one
     * recovered from disk, possibly from a previous run.
     *
     * It cannot join any track's consensus, but the pixels were real and the plate is worth having.
     * So it either corroborates a card we already hold, joins the votes its own track parked when
     * the lens rotated, or it accumulates against its own plate until it has met the same bar a
     * live vehicle has to meet.
     */
    private fun submitOrphan(job: OcrJob, outcome: AlprOutcome, nowMs: Long): SubmitResult {
        val reading = selectReading(outcome, minScore, job)
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
            }
            // The newest look at the car, published, so the encounter photo is a picture of this
            // meeting; the frame it replaces goes to the GC like every other published bitmap.
            existing.latestThumbnail = job.thumbnail
            applyAttributes(existing, reading.car)
            return SubmitResult(Change.UPDATED, existing.toCard(), reading.recognitionScore)
        }

        // The track this crop came from may have ended a moment ago, taking its votes to the pool.
        // A read that was in flight when the lens rotated is the last of those votes, not the first
        // of a new tally — and it is very often the one that tips the car over the threshold.
        val parkedIndex = pool.indexOfFirst { PlateSimilarity.similar(it.plate, reading.text) }
        if (parkedIndex >= 0) {
            val parked = pool[parkedIndex]
            val label = parked.plate
            val count = (parked.state.counts[label] ?: 0) + 1
            val history = parked.state.variants.getOrPut(label) { ArrayList(PlateFusion.MAX_READINGS) }
            history += reading
            if (history.size > PlateFusion.MAX_READINGS) history.removeAt(0)
            val fused = PlateFusion.fuse(history)
            val winner = fused?.reading ?: reading
            val betterFrame = reading.recognitionScore >= parked.state.bestScore
            if (count < requiredMatches) {
                parked.state.counts[label] = count
                if (betterFrame) {
                    recycle(parked.state.candidateThumb)
                    parked.state.candidateThumb = job.thumbnail
                    parked.state.bestScore = reading.recognitionScore
                } else {
                    recycle(job.thumbnail)
                }
                return SubmitResult(Change.PENDING, null, reading.recognitionScore)
            }
            pool.removeAt(parkedIndex)
            val best = if (betterFrame) job.thumbnail else parked.state.candidateThumb ?: job.thumbnail
            if (best !== parked.state.candidateThumb) recycle(parked.state.candidateThumb)
            val entry = createEntry(
                winner,
                parked.state.car ?: reading.car,
                best,
                job.lensLabel,
                nowMs,
                fused?.support ?: count,
            )
            entry.latestThumbnail = job.thumbnail
            trim()
            return SubmitResult(Change.CONFIRMED, entry.toCard(), reading.recognitionScore)
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
            return SubmitResult(Change.PENDING, null, reading.recognitionScore)
        }
        orphanCounts.remove(group)
        val entry = createEntry(reading, reading.car, job.thumbnail, job.lensLabel, nowMs, count)
        trim()
        return SubmitResult(Change.CONFIRMED, entry.toCard(), reading.recognitionScore)
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
            attributeConfidence = car?.makeModelConfidence ?: 0f,
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
        val parked = pool[index].state
        // Two different cars can carry near-identical plates; if the classifiers disagree about
        // what they are looking at, the votes belong to neither — but they stay parked for the
        // car they do belong to, which may be the next track along.
        val here = state.car?.make
        val there = parked.car?.make
        if (here != null && there != null && here != there) return
        pool.removeAt(index)
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

    /**
     * Operator correction: move a published card onto another plate.
     *
     * Every live track that had confirmed the old spelling follows the card, or the next read of
     * that track would find its old consensus above the threshold with no card under it — and
     * create the card the operator just corrected away, right next to the corrected one.
     */
    fun rename(from: String, to: String, display: String): Boolean {
        val entry = vehicles.remove(from) ?: return false
        val existing = vehicles[to]
        if (existing != null) {
            existing.sightings += entry.sightings
            existing.lenses += entry.lenses
            if (entry.ocrScore > existing.ocrScore) {
                existing.ocrScore = entry.ocrScore
                existing.thumbnail = entry.thumbnail
            }
            if (entry.lastSeenMs > existing.lastSeenMs) {
                existing.lastSeenMs = entry.lastSeenMs
                existing.latestThumbnail = entry.latestThumbnail ?: existing.latestThumbnail
            }
            existing.votes = Int.MAX_VALUE
        } else {
            entry.plate = to
            entry.displayPlate = display
            // The operator outranks any amount of voting.
            entry.votes = Int.MAX_VALUE
            vehicles[to] = entry
        }
        rekeyLiveTracks(from, to)
        return true
    }

    /** Points every consensus and runtime that had confirmed [from] at [to]. */
    private fun rekeyLiveTracks(from: String, to: String) {
        consensus.values.forEach { state ->
            if (state.confirmed == from) {
                state.confirmed = to
                // The old spelling's votes would re-confirm it on the next read; they now stand
                // behind the corrected one.
                val moved = state.counts.remove(from)
                if (moved != null) state.counts[to] = maxOf(moved, state.counts[to] ?: 0)
                state.variants.remove(from)?.let { history -> state.variants.getOrPut(to) { ArrayList() }.addAll(history) }
            }
        }
        recognition.forEach { _, runtime ->
            if (runtime.confirmedPlate == from) runtime.confirmedPlate = to
            if (runtime.pendingPlate == from) runtime.pendingPlate = to
        }
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
        // Evidence wants the latest look at the car, not the luckiest one — every time, not only
        // when the frame also happened to be the best read.
        entry.latestThumbnail = job.thumbnail
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
        val previous = entry.plate
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
            if (collision.attributeConfidence > entry.attributeConfidence) {
                entry.make = collision.make
                entry.model = collision.model
                entry.year = collision.year
                entry.attributeConfidence = collision.attributeConfidence
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
        rekeyLiveTracks(previous, entry.plate)
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

    /**
     * Make, model and year travel as one answer from one classifier call; filling them in one
     * field at a time assembled cars that do not exist ("Toyota X5"). A surer answer replaces the
     * whole group; colour and body are filled once, because the classifier gives no confidence
     * for them to compare.
     */
    private fun applyAttributes(entry: VehicleEntry, car: CarInfo?) {
        if (car == null) return
        val hasMakeModel = car.make != null || car.model != null
        val empty = entry.make == null && entry.model == null
        if (hasMakeModel && (empty || car.makeModelConfidence > entry.attributeConfidence + ATTRIBUTE_MARGIN)) {
            entry.make = car.make
            entry.model = car.model
            entry.year = car.year ?: entry.year
            entry.attributeConfidence = car.makeModelConfidence
        }
        if (entry.color == null) entry.color = car.color
        if (entry.bodyStyle == null) entry.bodyStyle = car.bodyStyle
    }

    /**
     * The classifier answer that belongs to the plate this result is about.
     *
     * A crop can hold two cars, and the engine attaches a `car` to each plate it read. Taking the
     * most confident make/model anywhere in the crop labelled the tracked car with its
     * neighbour's badge; the reading's own car comes first, the anonymous cars of the crop only
     * when the plate carried none.
     */
    private fun mergeCar(state: TrackConsensus, outcome: AlprOutcome, reading: PlateReading?) {
        val best = reading?.car ?: outcome.cars.maxByOrNull { it.makeModelConfidence } ?: return
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

        /** A classifier has to be this much surer before it may overwrite an earlier make/model. */
        private const val ATTRIBUTE_MARGIN = 8f

        /**
         * Share of a whole-vehicle crop, on each side, that is padding around the detector's box.
         * A plate centred inside that strip sits on the bumper of the car alongside, not on ours.
         */
        private const val EDGE_SHARE = FrameProcessor.CROP_MARGIN / (1f + 2f * FrameProcessor.CROP_MARGIN)

        /**
         * The one reading a result is judged by.
         *
         * Everything downstream — the consensus vote, the card, and the remembered plate position —
         * has to agree on which of several detected plates the result is about, or the anchor ends
         * up pointing at the neighbouring car's plate while the card records this one's.
         *
         * Among the plates that clear the score floor, the ones inside the tracked vehicle's own
         * box come first: a whole-car crop carries a margin around the detector box, and the most
         * confident read in the crop used to win even when it sat in that margin, on the next
         * car's bumper. Only when nothing is inside does the best read anywhere count.
         */
        fun selectReading(outcome: AlprOutcome, minScore: Float, job: OcrJob? = null): PlateReading? {
            val eligible = outcome.plates.filter { it.recognitionScore >= minScore }
            if (eligible.isEmpty()) return null
            if (job == null || job.narrow || job.width <= 0 || job.height <= 0 || eligible.size == 1) {
                return eligible.maxByOrNull { it.recognitionScore }
            }
            val insetX = job.width * EDGE_SHARE
            val insetY = job.height * EDGE_SHARE
            val inside = eligible.filter { reading ->
                val box = reading.box ?: return@filter true
                val centreX = box.centerX()
                val centreY = box.centerY()
                centreX >= insetX && centreX <= job.width - insetX &&
                    centreY >= insetY && centreY <= job.height - insetY
            }
            return inside.ifEmpty { eligible }.maxByOrNull { it.recognitionScore }
        }
    }

    /** Only unpublished bitmaps are recycled; anything the list may still draw is left to the GC. */
    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }
}
