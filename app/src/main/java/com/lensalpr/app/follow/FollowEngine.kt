package com.lensalpr.app.follow

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lensalpr.app.alpr.PlateSimilarity
import com.lensalpr.app.data.TrackingStore
import com.lensalpr.app.settings.RuntimeSettings
import com.lensalpr.app.track.TripTracker
import com.lensalpr.app.track.TurnEvent
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService

enum class ThreatLevel(val rank: Int) {
    /** Passed by, parked, or too little contact to mean anything. */
    IGNORE(0),

    /** Behind us long enough to be worth remembering. */
    WATCH(1),

    /** Copied our route or came back on another trip. */
    SUSPECT(2),

    /** Reproduced our route decisions; treat as an active tail. */
    TAIL(3),

    /** Manually flagged by the operator. */
    BLACKLIST(4),
    ;

    /** Short label for banners, alerts and the report. */
    fun title(): String = when (this) {
        IGNORE -> "контакт"
        WATCH -> "наблюдение"
        SUSPECT -> "подозрение"
        TAIL -> "хвост"
        BLACKLIST -> "чёрный список"
    }

    companion object {
        fun of(rank: Int): ThreatLevel = entries.firstOrNull { it.rank == rank } ?: IGNORE
    }
}

/** What the engine knows about one vehicle, including *why* it reached its level. */
data class FollowEvidence(
    val plate: String,
    /** The key this car's rows live under; use it for anything that reads or writes the database. */
    val storeKey: String,
    val displayPlate: String,
    val level: ThreatLevel,
    val sightings: Int,
    val encounters: Int,
    val tripsSeen: Int,
    val sharedTurns: Int,
    val reacquisitions: Int,
    val contactMs: Long,
    val contactM: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** How long this car was out of sight before the current stretch of company began. */
    val awayMs: Long,
    val blacklisted: Boolean,
    /** On the operator's police list: announced like the blacklist, named as police. */
    val police: Boolean,
    val ignored: Boolean,
    /** In how many distinct places this car has met us. */
    val places: Int,
    val makeModel: String?,
    val color: String?,
    val lastLens: String?,
    val reasons: List<String>,
)

data class FollowConfig(
    val alertAfterEncounters: Int = 2,
    val minSightingIntervalMs: Long = 2_000L,
)

/**
 * Decides which of the vehicles behind us is actually following us.
 *
 * The engine never scores on "time behind me" alone - on a highway that describes every commuter.
 * What it counts is *route agreement*: a turn only credits a vehicle when it was seen both shortly
 * before and shortly after we made it. Losing a vehicle and re-acquiring it after a turn counts
 * too, because that is what a competent tail looks like: it drops back and lets cars in between.
 *
 * Everything is explainable: each level carries the concrete evidence that produced it.
 */
class FollowEngine(
    private val store: TrackingStore,
    private val tracker: TripTracker,
    private val io: ExecutorService,
    private val runtime: RuntimeSettings,
    private val config: FollowConfig = FollowConfig(),
    private val onUpdate: (FollowEvidence, AlertReason) -> Unit,
    /** Raised the moment a vehicle has held station long enough to count as following us. */
    private val onFollowerConfirmed: (FollowEvidence) -> Unit = {},
    /** The follower has been out of frame long enough; the clip about it can end. */
    private val onFollowerLost: (String) -> Unit = {},
) {

    private class PendingTurn(val event: TurnEvent) {
        val before = HashSet<String>()
        val credited = HashSet<String>()
    }

    private class State(var plate: String) {
        var displayPlate: String = plate

        /** Best OCR score this car was ever read with; decides which spelling survives a merge. */
        var bestScore = 0f
        var firstSeenMs = 0L
        var lastSeenMs = 0L
        var firstOdometerM = 0.0
        var lastOdometerM = 0.0
        var sightings = 0

        /** Contact banked from earlier stretches of company, excluding the gaps between them. */
        var contactMsBanked = 0L
        var contactMBanked = 0.0

        /** Sightings within the current stretch; two glimpses are not two minutes of following. */
        var segmentSightings = 0

        /**
         * Ends the current stretch of company and banks what it was worth.
         *
         * Idempotent: the open stretch is collapsed onto its own end, so calling this twice — once
         * because the car went out of frame, once because the next sighting arrived after a long
         * gap — cannot bank the same minutes again. Double-banked contact would climb straight
         * through the TAIL threshold on its own.
         */
        fun closeSegment() {
            if (firstSeenMs != 0L && lastSeenMs > firstSeenMs) {
                contactMsBanked += lastSeenMs - firstSeenMs
                contactMBanked += (lastOdometerM - firstOdometerM).coerceAtLeast(0.0)
            }
            firstSeenMs = lastSeenMs
            firstOdometerM = lastOdometerM
            segmentSightings = 0
            // The next stretch of company is a new event and deserves its own word, even though
            // the car's level cannot climb any higher than it already is.
            alertedInSegment = false
        }

        fun contactMs(): Long = contactMsBanked + (lastSeenMs - firstSeenMs).coerceAtLeast(0L)

        fun contactM(): Double = contactMBanked + (lastOdometerM - firstOdometerM).coerceAtLeast(0.0)

        var encounters = 0
        var tripsSeen = 1
        var sharedTurns = 0
        var reacquisitions = 0
        var turnsDuringContact = 0

        /**
         * Turns this car was behind us for and did not take. Counted but not yet acted on: a plate
         * the engine simply failed to read after the junction looks exactly the same from here, and
         * that has to be measured on the road before it is allowed to clear anybody.
         */
        var missedTurns = 0
        var movedWithUs = false
        var level = ThreatLevel.IGNORE
        var blacklisted = false

        /** Marked by the operator as a patrol car; shouts like the blacklist, under its own name. */
        var police = false

        /** Dismissed by the operator; stays at IGNORE whatever it does. */
        var ignored = false

        /** Distinct places this car met us in, straight from the database. */
        var places = 1

        /** A clip is being written about this car right now. */
        var videoActive = false
        var makeModel: String? = null
        var color: String? = null
        var lastLens: String? = null
        var lastRecordedMs = 0L

        /** Wall-clock moment this car was last reported, whenever its crop was taken. */
        var lastDeliveredMs = 0L

        /** Held station behind us for the configured time. */
        var follower = false

        /** Was a follower, disappeared, and came back - the alarm case. */
        var returnedAfterFollowing = false

        /** When the operator was last told about this car, whatever the reason. */
        var lastAlertedMs = 0L

        /** Already reported during the current stretch of company; one message per stretch. */
        var alertedInSegment = false

        /** The gap that ended the previous stretch; how long this car was gone before coming back. */
        var lastGapMs = 0L

        /**
         * The stretch was closed because the car left the picture, and no sighting has opened a
         * new one yet.
         *
         * Without this the time spent out of sight is quietly billed as company: the stretch keeps
         * its old start, the car returns, and the minutes it was absent land in the contact total
         * that decides whether it is a tail.
         */
        var awaitingReturn = false

        /**
         * The row this car actually lives under in the database.
         *
         * The engine and the store resolve near-identical spellings independently — the engine
         * among this session's cars by this session's best score, the store among every car it has
         * ever seen — and they can disagree. The engine settles on `EM7209` while every encounter,
         * photo and blacklist row goes to yesterday's better-read `EN7209`; then the alarm looks up
         * its photo under a key with no rows and arrives blind, and the "⛔️ В чёрный список" button
         * writes to a car that does not exist. This is the key for anything touching the database;
         * [plate] stays the key the engine reasons with.
         */
        var storeKey: String = plate

        /**
         * At least one observation of this car was stamped with a real odometer reading.
         *
         * When it is false, every distance below is a placeholder and the classifier has to reason
         * from time and sightings instead — otherwise a phone with no location fix quietly decides
         * that nobody is ever following it.
         */
        var hasOdometer = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val states = HashMap<String, State>()

    /**
     * Every key this engine has ever used, including ones a rename has since retired.
     *
     * The recorder and the absence timer hold a key that may no longer exist in [states]. This is
     * what tells "a car we knew and have lost track of" apart from "a label that was never a
     * plate" — the manual clip is titled by the operator, and mistaking it for a vanished vehicle
     * would stop it a few seconds after it started.
     */
    private val owned = HashSet<String>()
    private val pendingTurns = ArrayDeque<PendingTurn>()
    private val recentSightings = ArrayDeque<Pair<String, Long>>()

    @Volatile
    private var tripId: Long = 0L

    fun bindTrip(tripId: Long) {
        this.tripId = tripId
    }

    /** Snapshot for the UI, most dangerous first. */
    fun evidence(): List<FollowEvidence> = states.values
        .map(::toEvidence)
        .filter { it.level != ThreatLevel.IGNORE }
        .sortedWith(compareByDescending<FollowEvidence> { it.level.rank }.thenByDescending { it.lastSeenMs })

    fun evidenceFor(plate: String): FollowEvidence? = states[plate]?.let(::toEvidence)

    /**
     * Vehicles that were behind us moments ago, longest company first.
     *
     * This is the question the driver actually has — not "which plates were recognised today" but
     * "who is with me now, and for how long". Everything else in the engine is history; this is the
     * present tense.
     */
    fun companions(nowMs: Long, withinMs: Long = COMPANION_WINDOW_MS): List<FollowEvidence> =
        states.values
            .filter { !it.ignored && nowMs - it.lastDeliveredMs <= withinMs }
            .sortedByDescending { it.contactMs() }
            .map(::toEvidence)

    /**
     * Checks whether the vehicle a clip is about has left the picture for long enough to stop
     * recording. Called on a timer, because "nothing happened" is exactly what has to be noticed.
     */
    fun checkFollowerAbsence(plate: String?, nowMs: Long) {
        if (plate == null) return
        val state = resolve(plate) ?: run {
            // The key vanished, and only a key this engine once owned can vanish. A better reading
            // renamed the car, or the database was wiped; either way the clip is now about a
            // vehicle nothing can report on, so nothing would ever stop it and the recorder would
            // cut fresh ninety-second files of an empty road until the drive ended.
            //
            // A key that was never ours is a different matter entirely — a manually started clip
            // is labelled by the operator, not by a plate, and it must run until they stop it.
            if (plate in owned) onFollowerLost(plate)
            return
        }
        // Liveness is a wall-clock question: "have I seen this car lately", not "how old is the
        // newest crop of it". Capture time would keep a clip running on a car long gone.
        if (nowMs - state.lastDeliveredMs >= runtime.videoAbsentSeconds * 1_000L) {
            state.videoActive = false
            onFollowerLost(plate)
        }
    }

    /**
     * Notices that a vehicle has left the picture — any vehicle, not just the filmed one.
     *
     * Until now "gone from the frame" existed only inside the recorder: [checkFollowerAbsence] is
     * called for the single car a clip is being written about, and nothing else in the app ever
     * asked the question. The alert path had to infer a departure from a gap of two full minutes
     * between sightings, while the recorder gave up after one, so a car that vanished for ninety
     * seconds and came back found its clip restarted and its stretch of company still open — which
     * is precisely how a blacklisted vehicle could be filmed on its return without a word being
     * said about it.
     *
     * The threshold is its own constant rather than the operator's video setting: that one may be
     * turned down to five seconds, and stretches torn up that often would never accumulate the
     * sightings a follower has to show.
     */
    fun sweepPresence(nowMs: Long) {
        states.values.forEach { state ->
            if (state.lastDeliveredMs == 0L) return@forEach
            if (state.segmentSightings == 0 && !state.alertedInSegment) return@forEach
            if (nowMs - state.lastDeliveredMs < PRESENCE_GAP_MS) return@forEach
            state.lastGapMs = (nowMs - state.lastSeenMs).coerceAtLeast(0L)
            state.closeSegment()
            state.awaitingReturn = true
        }
    }

    /** The clip about this car ended; a later return may start a new one. */
    fun noteVideoFinished(plate: String) {
        // Through [resolve], because a car renamed mid-clip would otherwise keep videoActive set
        // for ever on its new key and could never be filmed again for the rest of the drive.
        resolve(plate)?.videoActive = false
    }

    /**
     * Finds the vehicle a key refers to, following a rename.
     *
     * Plates are the identity here, and that identity moves: a better-scored reading renames the
     * whole state onto the corrected spelling. Anything holding the old key — the recorder, the
     * absence timer — must still be able to find the car it is about.
     */
    private fun resolve(plate: String): State? = states[plate]
        ?: states.values.firstOrNull { PlateSimilarity.similar(it.plate, plate) }

    /**
     * Forgets every vehicle. Used when the operator erases the database mid-session: leaving the
     * threat state behind would keep alerting about cars whose evidence no longer exists.
     */
    fun reset() {
        states.clear()
        // Deliberately kept: a clip may still be running about one of these, and the recorder has
        // to be able to learn that its subject no longer exists.
        pendingTurns.clear()
        recentSightings.clear()
        loadBlacklist()
        loadIgnored()
    }

    fun setBlacklisted(plate: String, blacklisted: Boolean) {
        // Through resolve, or a car the engine is tracking under a corrected spelling would get a
        // second, empty state under the operator's key: the list would look applied while the
        // vehicle actually on the road kept its old level and kept alerting.
        val state = resolve(plate) ?: State(plate).also { states[plate] = it; owned += plate }
        state.blacklisted = blacklisted
        state.level = if (blacklisted) ThreatLevel.BLACKLIST else classify(state)
        io.execute { runCatching { store.setBlacklisted(state.plate, blacklisted, state.displayPlate) } }
        // Listing a car is the operator's own action, and they are looking at the bot when they do
        // it — the button's own reply is the confirmation. An alarm here would announce "снова
        // рядом" about a car that may be nowhere near us, so the marks are only cleared, letting
        // the next real sighting speak immediately instead of waiting out the repeat cooldown.
        state.alertedInSegment = false
        state.lastAlertedMs = 0L
        onUpdate(toEvidence(state), AlertReason.NONE)
    }

    /**
     * Marks a vehicle as police, or clears the mark.
     *
     * Deliberately does not touch [State.level]: a patrol car is not an accusation, and folding it
     * into the threat scale would put it in the tail report and start filming it. The alerting
     * comes from the flag alone — see [AlertPolicy.isPersistent].
     */
    fun setPolice(plate: String, police: Boolean) {
        val state = resolve(plate) ?: State(plate).also { states[plate] = it; owned += plate }
        state.police = police
        io.execute { runCatching { store.setPolice(state.plate, police, state.displayPlate) } }
        // Same reasoning as [setBlacklisted]: the operator is looking at the reply, so the marking
        // itself is not an alarm — the next time the car is actually behind us is.
        state.alertedInSegment = false
        state.lastAlertedMs = 0L
        onUpdate(toEvidence(state), AlertReason.NONE)
    }

    fun loadPolice() {
        io.execute {
            val plates = runCatching { store.policePlates() }.getOrDefault(emptySet())
            if (plates.isEmpty()) return@execute
            main.post {
                plates.forEach { plate ->
                    states.getOrPut(plate) { State(plate) }.police = true
                }
            }
        }
    }

    /**
     * Closes turn windows that have run out, booking every vehicle that was behind us before the
     * junction and never reappeared after it.
     *
     * Called on a timer as well as on the next turn: on a long straight road the previous window
     * would otherwise stay open for kilometres and the evidence would arrive far too late to mean
     * anything.
     */
    fun sweepTurns(nowMs: Long) {
        while (pendingTurns.isNotEmpty() &&
            nowMs - pendingTurns.first().event.tMs > POST_TURN_WINDOW_MS
        ) {
            val turn = pendingTurns.removeFirst()
            turn.before.forEach { plate ->
                if (plate !in turn.credited) states[plate]?.let { it.missedTurns += 1 }
            }
        }
    }

    /** Operator correction: the history collected under one spelling belongs to another car. */
    fun rename(from: String, to: String, display: String): Boolean {
        val state = states.remove(from) ?: return false
        val target = states[to]
        val previousTargetLevel = target?.level ?: ThreatLevel.IGNORE
        if (target != null) {
            target.sightings += state.sightings
            target.segmentSightings += state.segmentSightings
            target.sharedTurns = maxOf(target.sharedTurns, state.sharedTurns)
            target.reacquisitions = maxOf(target.reacquisitions, state.reacquisitions)
            target.turnsDuringContact = maxOf(target.turnsDuringContact, state.turnsDuringContact)
            target.missedTurns = maxOf(target.missedTurns, state.missedTurns)
            // Both cars' company is real and neither includes the gap between them: bank the two
            // completed histories and start one fresh segment, instead of taking the wider span and
            // calling an hour apart an hour together.
            target.contactMsBanked = target.contactMs() + state.contactMs()
            target.contactMBanked = target.contactM() + state.contactM()
            target.bestScore = maxOf(target.bestScore, state.bestScore)
            // Evidence is never lost by a spelling correction: whichever half knew the car was
            // blacklisted, dismissed, following us or returning, the merged state knows it too.
            target.blacklisted = target.blacklisted || state.blacklisted
            target.police = target.police || state.police
            target.ignored = target.ignored || state.ignored
            target.follower = target.follower || state.follower
            // Losing this meant the clip already running about the merged car would never be
            // recognised as running, and a second one would be started on top of it.
            target.videoActive = target.videoActive || state.videoActive
            target.hasOdometer = target.hasOdometer || state.hasOdometer
            target.movedWithUs = target.movedWithUs || state.movedWithUs
            target.returnedAfterFollowing =
                target.returnedAfterFollowing || state.returnedAfterFollowing
            if (state.firstSeenMs != 0L && (target.firstSeenMs == 0L || state.firstSeenMs < target.firstSeenMs)) {
                target.firstSeenMs = state.firstSeenMs
                target.firstOdometerM = state.firstOdometerM
            }
            if (state.lastSeenMs > target.lastSeenMs) {
                target.lastSeenMs = state.lastSeenMs
                target.lastOdometerM = state.lastOdometerM
            }
            target.lastDeliveredMs = maxOf(target.lastDeliveredMs, state.lastDeliveredMs)
            // The banked totals already contain everything up to here; the open segment restarts
            // empty so nothing is counted twice.
            target.firstSeenMs = target.lastSeenMs
            target.firstOdometerM = target.lastOdometerM
            target.makeModel = target.makeModel ?: state.makeModel
            target.color = target.color ?: state.color
            target.level = when {
                target.ignored -> ThreatLevel.IGNORE
                target.blacklisted -> ThreatLevel.BLACKLIST
                else -> classify(target)
            }
            if (target.blacklisted) io.execute { runCatching { store.setBlacklisted(to, true) } }
            // Merging two halves of one car's history can push it over a threshold; that is a
            // genuine promotion and has to be announced, not swallowed as bookkeeping.
            val merged = if (target.level.rank > previousTargetLevel.rank) {
                target.lastAlertedMs = System.currentTimeMillis()
                target.alertedInSegment = true
                AlertReason.RAISED
            } else {
                AlertReason.NONE
            }
            onUpdate(toEvidence(target), merged)
        } else {
            state.plate = to
            state.displayPlate = display
            states[to] = state
        }
        pendingTurns.forEach { turn ->
            if (turn.before.remove(from)) turn.before += to
            if (turn.credited.remove(from)) turn.credited += to
        }
        // The sighting log feeds the "seen before the turn" set of every turn still to come.
        val relabelled = recentSightings.map { if (it.first == from) to to it.second else it }
        recentSightings.clear()
        recentSightings.addAll(relabelled)
        return true
    }

    fun setIgnored(plate: String, ignored: Boolean) {
        // Same reason as [setBlacklisted]: dismissing a car has to reach the state the engine is
        // actually reasoning about, not a namesake created by the dismissal itself. "🙈 Игнорировать"
        // that leaves the real vehicle alerting is worse than no button at all.
        val state = resolve(plate) ?: State(plate).also { states[plate] = it; owned += plate }
        state.ignored = ignored
        state.level = when {
            ignored -> ThreatLevel.IGNORE
            state.blacklisted -> ThreatLevel.BLACKLIST
            else -> classify(state)
        }
        io.execute { runCatching { store.setIgnored(state.plate, ignored, state.displayPlate) } }
        onUpdate(toEvidence(state), AlertReason.NONE)
    }

    fun loadIgnored() {
        io.execute {
            val plates = runCatching { store.ignoredPlates() }.getOrDefault(emptySet())
            if (plates.isEmpty()) return@execute
            main.post {
                plates.forEach { plate ->
                    val state = states.getOrPut(plate) { State(plate) }
                    state.ignored = true
                    state.level = ThreatLevel.IGNORE
                }
            }
        }
    }

    /**
     * Restores what was known about recently seen vehicles from the database.
     *
     * Without this the engine starts every process with an empty memory, so a car that was a
     * confirmed tail ninety seconds ago — before a crash, before the engine had to be restarted,
     * before the driver reopened the app — comes back as a stranger with no turns, no contact and
     * no level, and has to earn its way up from scratch while it is still behind us.
     *
     * Only the banked evidence is restored, never an open stretch of company: the car is not in
     * frame at this moment, and pretending otherwise would credit it with the gap. Returns how many
     * vehicles were queued for restoration; the restore itself lands on the main thread, where the
     * engine's state lives.
     */
    fun hydrate(nowMs: Long, windowMs: Long = HYDRATE_WINDOW_MS): Int {
        val rows = store.vehiclesSeenSince(nowMs - windowMs)
        if (rows.isEmpty()) return 0
        val restored = rows.filter {
            it.level > ThreatLevel.IGNORE.rank || it.blacklisted || it.police || it.ignored
        }
        if (restored.isEmpty()) return 0
        main.post {
            restored.forEach { row ->
                val state = states.getOrPut(row.plate) { State(row.plate) }
                // A live state always wins: this runs off the io thread and a sighting may already
                // have landed on the same car while the query was in flight.
                if (state.sightings > 0) return@forEach
                state.storeKey = row.plate
                state.displayPlate = row.displayPlate
                state.bestScore = row.bestScore
                state.sharedTurns = row.sharedTurns
                state.reacquisitions = row.reacquisitions
                state.contactMsBanked = row.contactMs
                state.contactMBanked = row.contactM
                state.encounters = row.encounters
                state.tripsSeen = row.tripsSeen
                state.makeModel = row.makeModel
                state.color = row.color
                state.blacklisted = row.blacklisted
                state.police = row.police
                state.ignored = row.ignored
                // It was moving with us when the evidence was collected, or it would not have a
                // level; without this the first gate in classify() would demote it to IGNORE.
                state.movedWithUs = row.contactM >= MOVING_CONTACT_M
                // Banked distance can only have been measured, so the restored evidence is not
                // "blind". Without this the classifier would fall back to its no-GPS rules on the
                // first sighting after a restart — before the new fix has arrived — and throw away
                // the very history this method exists to preserve.
                state.hasOdometer = row.contactM > 0.0
                state.level = when {
                    row.ignored -> ThreatLevel.IGNORE
                    row.blacklisted -> ThreatLevel.BLACKLIST
                    else -> ThreatLevel.of(row.level)
                }
                // Known to be dangerous and not reported in this process yet: the next time it is
                // actually behind us is an event, not a repeat.
                state.lastAlertedMs = 0L
                state.alertedInSegment = false
            }
        }
        return restored.size
    }

    fun loadBlacklist() {
        io.execute {
            val plates = runCatching { store.blacklistedPlates() }.getOrDefault(emptySet())
            if (plates.isEmpty()) return@execute
            main.post {
                plates.forEach { plate ->
                    val state = states.getOrPut(plate) { State(plate) }
                    state.blacklisted = true
                    state.level = ThreatLevel.BLACKLIST
                }
            }
        }
    }

    /**
     * A route decision we just made. Everything seen shortly before it becomes a candidate: if the
     * same plate shows up again shortly after, it took the same turn we did.
     */
    fun onTurn(event: TurnEvent) {
        val pending = PendingTurn(event)
        val since = event.tMs - PRE_TURN_WINDOW_MS
        recentSightings.forEach { (plate, tMs) -> if (tMs >= since) pending.before += plate }
        pendingTurns.addLast(pending)
        sweepTurns(event.tMs)
        states.values.forEach { state ->
            if (state.lastSeenMs >= since) state.turnsDuringContact += 1
        }
    }

    /**
     * One confirmed plate read. Repeat reads of the same vehicle are throttled: the evidence that
     * matters is time and route, not how many frames the OCR managed to grab.
     */
    fun onSighting(
        plate: String,
        displayPlate: String,
        makeModel: String?,
        make: String?,
        model: String?,
        year: String?,
        color: String?,
        body: String?,
        country: String?,
        ocrScore: Float,
        lens: String,
        nowMs: Long,
        thumbnail: Bitmap?,
        /**
         * When the crop was actually taken. A recognition that waited on disk must be recorded
         * where the vehicle was, not where we are now.
         */
        capturedAtMs: Long = nowMs,
        lat: Double? = null,
        lon: Double? = null,
        odometerM: Double = Double.NaN,
    ) {
        // A single flipped character is the same car misread, not a second one. Everything below —
        // contact time, turns, the tail decision — has to accumulate on one key or a careful
        // follower stays invisible by simply being read two ways.
        val key = canonicalKey(plate, ocrScore)
        val state = states.getOrPut(key) { State(key) }
        owned += key
        state.bestScore = maxOf(state.bestScore, ocrScore)
        if (nowMs - state.lastRecordedMs < config.minSightingIntervalMs) return
        state.lastRecordedMs = nowMs
        state.lastDeliveredMs = nowMs

        // When the crop was taken, not when the engine got round to it. A crop recovered from disk
        // arrives minutes late; recording it as "now" would stretch its contact across the gap and
        // credit it with every turn taken in between.
        val eventMs = minOf(capturedAtMs, nowMs)

        val fix = tracker.current
        val stampLat = lat ?: fix?.lat
        val stampLon = lon ?: fix?.lon
        val odometer = if (odometerM.isNaN()) tracker.odometerM else odometerM
        if (tracker.hasOdometer) state.hasOdometer = true
        val previousLevel = state.level

        if (state.firstSeenMs == 0L) {
            state.firstSeenMs = eventMs
            state.firstOdometerM = odometer
        } else {
            val gapMs = eventMs - state.lastSeenMs
            val gapM = odometer - state.lastOdometerM
            val turnsInGap = tracker.turnsSince(state.lastSeenMs)
            if (gapMs > REACQUIRE_GAP_MS) {
                if (turnsInGap >= 1 || gapM > REACQUIRE_DISTANCE_M) {
                    state.reacquisitions += 1
                    // Held station, vanished, and is behind us again: this is the pattern a tail
                    // produces on purpose.
                    if (state.follower) state.returnedAfterFollowing = true
                }
                // The car was not with us during the gap, so the gap is not contact. Bank the
                // segment that just ended and start a new one — otherwise two glimpses an hour
                // apart read as an hour of company, and every regular on the route becomes a tail.
                state.lastGapMs = gapMs
                state.closeSegment()
                state.firstSeenMs = eventMs
                state.firstOdometerM = odometer
            } else if (state.awaitingReturn) {
                // The stretch was already banked when the car left the picture. Anchor the new one
                // to this sighting, or the time it spent out of sight would be counted as company
                // and push it towards TAIL on nothing but its own absence.
                state.firstSeenMs = eventMs
                state.firstOdometerM = odometer
            }
            state.awaitingReturn = false
        }
        // A late arrival must never rewind the clock; the newest observation defines "last seen".
        // Time and odometer move together or not at all — pairing a fresh odometer with an old
        // timestamp would invent distance travelled in company that never happened.
        if (eventMs >= state.lastSeenMs) {
            state.lastSeenMs = eventMs
            state.lastOdometerM = odometer
        }
        state.sightings += 1
        state.segmentSightings += 1
        if (key == plate) state.displayPlate = displayPlate
        state.lastLens = lens
        makeModel?.let { state.makeModel = it }
        color?.let { state.color = it }
        if (odometer - state.firstOdometerM > MOVING_CONTACT_M) state.movedWithUs = true

        creditTurns(key, eventMs)

        // Time actually spent in our company, not the span between the first glimpse and the last.
        // Following also has to be witnessed, not inferred from two sightings at either end of it.
        val contactMs = state.contactMs()
        val becameFollower = !state.follower &&
            contactMs >= runtime.tailSeconds * 1_000L &&
            state.segmentSightings >= MIN_FOLLOWER_SIGHTINGS
        if (becameFollower) state.follower = true

        recentSightings.addLast(key to eventMs)
        while (recentSightings.isNotEmpty() &&
            nowMs - recentSightings.first().second > SIGHTING_LOG_MS
        ) {
            recentSightings.removeFirst()
        }

        state.level = when {
            state.ignored -> ThreatLevel.IGNORE
            state.blacklisted -> ThreatLevel.BLACKLIST
            // Never downwards. What the engine concluded about a car was concluded from evidence
            // that still stands, and a verdict that could drop and climb again would announce the
            // same car as a fresh promotion every time it did — which is how a repeat alarm turns
            // into a chant. Only the operator's own decisions, handled above, may lower a level.
            else -> maxOf(classify(state), state.level)
        }
        val reason = AlertPolicy.decide(
            level = state.level,
            previousLevel = previousLevel,
            blacklisted = state.blacklisted,
            police = state.police,
            ignored = state.ignored,
            segmentSightings = state.segmentSightings,
            alertedInSegment = state.alertedInSegment,
            lastAlertedMs = state.lastAlertedMs,
            nowMs = nowMs,
        )
        if (reason != AlertReason.NONE) {
            state.lastAlertedMs = nowMs
            state.alertedInSegment = true
        }
        val evidence = toEvidence(state)
        onUpdate(evidence, reason)
        // Film the tail, not the suspicion. A car that merely kept station behind us for a while is
        // a candidate; only a confirmed tail is worth twenty megabytes of video and the battery.
        // Film the tail, not the suspicion. Deliberately *not* the police list: a patrol car is
        // marked to be announced, not to be evidence, and filming it would cost more than storage.
        // A running clip blocks the parked auto-sleep, so a marked car standing beside us keeps the
        // scanner awake indefinitely — and the recorder takes one subject at a time, so a patrol
        // car in view would silently deny the clip to a vehicle actually following us.
        val worthFilming = !state.ignored &&
            (state.level.rank >= ThreatLevel.TAIL.rank || state.blacklisted)
        if (worthFilming && !state.videoActive) {
            state.videoActive = true
            onFollowerConfirmed(evidence)
        }

        val trip = tripId
        io.execute {
            runCatching {
                val record = store.recordSighting(
                    plate = key,
                    displayPlate = state.displayPlate,
                    tripId = trip,
                    tMs = capturedAtMs,
                    lat = stampLat,
                    lon = stampLon,
                    bearing = fix?.bearingDeg ?: 0f,
                    speedMps = fix?.speedMps ?: 0f,
                    odometerM = odometer,
                    lens = lens,
                    ocrScore = ocrScore,
                    distanceM = null,
                    make = make,
                    model = model,
                    year = year,
                    color = color,
                    body = body,
                    country = country,
                    photoWriter = thumbnail?.let { bitmap -> { file -> writeJpeg(bitmap, file) } },
                )
                main.post {
                    state.encounters = record.encounters
                    state.tripsSeen = record.tripsSeen
                    state.places = record.places
                    // Where the store decided this sighting belongs. Only ever read back out; the
                    // engine's own key is left alone, because merging the two would mean rewriting
                    // in-memory evidence for a purely clerical disagreement.
                    state.storeKey = record.plate
                }
                store.updateEvidence(
                    plate = record.plate,
                    sharedTurns = state.sharedTurns,
                    reacquisitions = state.reacquisitions,
                    contactMs = state.contactMs(),
                    contactM = state.contactM(),
                    level = state.level.rank,
                )
            }.onFailure { error -> Log.w(TAG, "persist failed for $key", error) }
        }
    }

    /**
     * Maps a reading onto the vehicle it belongs to. When the fresh spelling is the better-scored
     * one the whole history moves onto it, so the card, the database and the alerts all agree.
     */
    private fun canonicalKey(plate: String, score: Float): String {
        if (states.containsKey(plate)) return plate
        val similar = states.keys.firstOrNull { PlateSimilarity.similar(it, plate) } ?: return plate
        val existing = states[similar] ?: return plate
        if (PlateSimilarity.prefer(similar, existing.bestScore, plate, score)) {
            Log.i(TAG, "merged $plate into $similar")
            return similar
        }
        states.remove(similar)
        existing.plate = plate
        existing.displayPlate = plate
        states[plate] = existing
        // Turn credit is keyed by plate as well; without this the renamed car loses its evidence.
        pendingTurns.forEach { turn ->
            if (turn.before.remove(similar)) turn.before += plate
            if (turn.credited.remove(similar)) turn.credited += plate
        }
        Log.i(TAG, "renamed $similar to better read $plate")
        return plate
    }

    private fun creditTurns(plate: String, nowMs: Long) {
        val state = states[plate] ?: return
        pendingTurns.forEach { turn ->
            val age = nowMs - turn.event.tMs
            if (age in 0..POST_TURN_WINDOW_MS &&
                plate in turn.before &&
                turn.credited.add(plate)
            ) {
                state.sharedTurns += 1
            }
        }
    }

    /**
     * Threshold model rather than a weighted score: the operator has to be able to read the reason
     * off the card and agree with it.
     */
    private fun classify(state: State): ThreatLevel {
        if (state.ignored) return ThreatLevel.IGNORE
        val contactMs = state.contactMs()
        val contactM = state.contactM()

        // Every distance below is measured by GPS. Without a fix the odometer reads a flat zero,
        // which is not "this car went nowhere" but "we do not know" — and the two used to be the
        // same thing here, so a phone that never got a fix classified every single vehicle as
        // harmless and the scanner ran all day in perfect silence. When distance is unavailable
        // the engine falls back to what it can still measure: time in company and how many times
        // it actually saw the car.
        val blind = !state.hasOdometer
        if (blind) {
            // Cumulative counters only. The sighted branch gates on movedWithUs, which is sticky,
            // so gating on per-stretch numbers here would make the blind branch the one place in
            // the classifier that can go *down* — a known tail would drop to IGNORE after every
            // gap and then climb back, and every climb reads as a promotion worth announcing.
            if (contactMs < BLIND_MOVING_CONTACT_MS || state.sightings < BLIND_MIN_SIGHTINGS) {
                return ThreatLevel.IGNORE
            }
        } else if (!state.movedWithUs && contactM < MOVING_CONTACT_M) {
            return ThreatLevel.IGNORE
        }

        // Kept station, disappeared, came back. Alarming — but only together with route evidence:
        // on a commuter corridor the same cars drop out of frame and return all day long.
        if (state.returnedAfterFollowing && state.sharedTurns >= 1) return ThreatLevel.TAIL

        // Meeting us twice only means something when the two meetings were in different places:
        // the car parked outside the house is in the picture every single morning.
        val metElsewhere = state.tripsSeen >= 2 && state.places >= 2
        val tail = state.sharedTurns >= 3 ||
            (state.sharedTurns >= 2 && contactMs >= TAIL_CONTACT_MS) ||
            (metElsewhere && state.sharedTurns >= 2) ||
            (state.reacquisitions >= 2 && state.sharedTurns >= 1)
        if (tail) return ThreatLevel.TAIL

        // Without a single turn during the whole contact we are on one road: demand much more
        // distance before calling a fellow traveller suspicious.
        val straightRoad = state.turnsDuringContact == 0
        val distanceGate = if (straightRoad) SUSPECT_HIGHWAY_M else SUSPECT_CONTACT_M
        val longContact = if (blind) {
            // No distance to demand, so demand more time instead of waving the rule through.
            contactMs >= BLIND_SUSPECT_CONTACT_MS
        } else {
            contactMs >= SUSPECT_CONTACT_MS && contactM >= distanceGate
        }
        val suspect = state.sharedTurns >= 2 ||
            metElsewhere ||
            state.reacquisitions >= 2 ||
            longContact
        if (suspect) return ThreatLevel.SUSPECT

        val watch = state.sightings >= 3 &&
            (contactMs >= WATCH_CONTACT_MS || (!blind && contactM >= WATCH_CONTACT_M))
        return if (watch) ThreatLevel.WATCH else ThreatLevel.IGNORE
    }

    private fun toEvidence(state: State): FollowEvidence {
        val contactMs = state.contactMs()
        val contactM = state.contactM()
        val reasons = buildList {
            if (state.blacklisted) add("в чёрном списке")
            if (state.returnedAfterFollowing) add("следовал и вернулся после пропажи")
            if (state.follower) add("держался ${runtime.tailSeconds} с и дольше")
            if (state.sharedTurns > 0) add("${state.sharedTurns} общих поворот${plural(state.sharedTurns)}")
            if (state.tripsSeen > 1) add("${state.tripsSeen} разные поездки")
            if (state.places > 1) add("${state.places} разных мест")
            if (state.ignored) add("помечен как «свой»")
            if (state.reacquisitions > 0) add("${state.reacquisitions}× терялся и возвращался")
            if (state.missedTurns > 0) add("не поехал за нами ${state.missedTurns}×")
            if (state.encounters > 1) add("${state.encounters} встреч")
            if (contactMs >= 60_000) add("${contactMs / 60_000} мин контакта")
            if (contactM >= 500) add(String.format(java.util.Locale.US, "%.1f км рядом", contactM / 1000.0))
        }
        return FollowEvidence(
            plate = state.plate,
            storeKey = state.storeKey,
            displayPlate = state.displayPlate,
            level = state.level,
            sightings = state.sightings,
            encounters = state.encounters,
            tripsSeen = state.tripsSeen,
            sharedTurns = state.sharedTurns,
            reacquisitions = state.reacquisitions,
            contactMs = contactMs,
            contactM = contactM,
            firstSeenMs = state.firstSeenMs,
            lastSeenMs = state.lastSeenMs,
            awayMs = state.lastGapMs,
            blacklisted = state.blacklisted,
            police = state.police,
            ignored = state.ignored,
            places = state.places,
            makeModel = state.makeModel,
            color = state.color,
            lastLens = state.lastLens,
            reasons = reasons,
        )
    }

    private fun plural(count: Int): String = when {
        count % 10 == 1 && count % 100 != 11 -> "а"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "а"
        else -> "ов"
    }

    private fun writeJpeg(bitmap: Bitmap, file: File): Boolean = runCatching {
        if (bitmap.isRecycled) return false
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "LensALPR.Follow"

        /** Seen this long before a turn makes a vehicle a candidate for having taken it. */
        /**
         * Sightings needed inside one stretch before it counts as following rather than as two
         * coincidental glimpses at either end of it.
         */
        const val MIN_FOLLOWER_SIGHTINGS = 4

        /** How far back the engine looks when restoring its memory from the database. */
        const val HYDRATE_WINDOW_MS = 6L * 3_600_000L

        /** Seen this recently counts as "still with us"; a lens rotation alone takes a few seconds. */
        const val COMPANION_WINDOW_MS = 20_000L

        const val PRE_TURN_WINDOW_MS = 60_000L

        /** Seen again within this window after the turn credits it. */
        const val POST_TURN_WINDOW_MS = 120_000L
        const val SIGHTING_LOG_MS = 180_000L

        /**
         * Out of sight this long ends the current stretch of company.
         *
         * Has to clear a full lens rotation with room to spare — a car visible only through the
         * telephoto step is legitimately unseen for the twenty seconds the other steps are on
         * duty, and tearing its stretch up every rotation would stop it ever accumulating the
         * sightings that make a follower.
         */
        const val PRESENCE_GAP_MS = 45_000L

        const val REACQUIRE_GAP_MS = 120_000L
        const val REACQUIRE_DISTANCE_M = 800.0

        /** Contact shorter than this in distance is a pass-by or a parked car. */
        const val MOVING_CONTACT_M = 150.0

        /**
         * With no odometer, this much company stands in for [MOVING_CONTACT_M].
         *
         * Must stay clear of [WATCH_CONTACT_MS], or the gate and the first verdict above it become
         * the same test and nothing can ever be dismissed: without GPS every car sharing a traffic
         * jam for a minute would be announced. Three minutes plus a handful of confirmed readings
         * is the least that distinguishes company from coincidence when distance is unknown.
         */
        const val BLIND_MOVING_CONTACT_MS = 180_000L

        /** Confirmed readings required before a car counts at all without an odometer. */
        const val BLIND_MIN_SIGHTINGS = 6

        /** With no odometer, contact alone has to carry the whole "suspicious" verdict. */
        const val BLIND_SUSPECT_CONTACT_MS = 600_000L

        const val WATCH_CONTACT_MS = 60_000L
        const val WATCH_CONTACT_M = 700.0
        const val SUSPECT_CONTACT_MS = 180_000L
        const val SUSPECT_CONTACT_M = 2_500.0
        const val SUSPECT_HIGHWAY_M = 8_000.0
        const val TAIL_CONTACT_MS = 300_000L
    }
}
