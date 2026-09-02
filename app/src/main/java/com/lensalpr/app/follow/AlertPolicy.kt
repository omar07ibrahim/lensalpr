package com.lensalpr.app.follow

/**
 * Why the operator is being told about a vehicle right now.
 *
 * Threat levels only ever climb — every input to the classifier is a counter or a sticky flag — so
 * "the level went up" fires exactly once per car and then never again. That was enough for the
 * first alarm and nothing else: a car confirmed as a tail could drive past three more times in
 * complete silence, which is exactly what happened on the road. The reason separates the two
 * questions the driver actually has: *what is this car* and *is it with me right now*.
 */
enum class AlertReason {
    /** Nothing to say: the car is known, unchanged, and was reported recently enough. */
    NONE,

    /** The classifier moved this car up a level. */
    RAISED,

    /** A car already known to be dangerous is behind us again after being away. */
    RETURNED,

    /** On the operator's own list; every fresh meeting is worth a message. */
    BLACKLISTED,

    /**
     * A confirmed tail or a listed car is in view *right now*, and was already named.
     *
     * The driver asked for this in as many words: once a car is a tail or on the list, the phone
     * should keep saying so for as long as it can see it, rather than mentioning it once and
     * leaving the mirror to do the rest. Everything below TAIL stays restrained — this is the
     * level at which being nagged is the point.
     */
    PRESENT,
}

/**
 * Decides whether a sighting is worth interrupting the driver for.
 *
 * Kept apart from the engine and free of any state of its own, because this is the rule that
 * decides whether the app speaks at all — the one piece of logic whose failure mode is total
 * silence, and therefore the one that has to be checkable without a phone.
 *
 * A rise in level is the obvious case and used to be the only one. The other is *this car is here
 * again*, which is deliberately harder to trigger than a bare sighting: the previous stretch of
 * company must have ended, the return must be witnessed, and no car may speak more often than
 * [REPEAT_COOLDOWN_MS]. That is what separates "it came back" from a car in the same traffic jam
 * flickering in and out of frame every ninety seconds.
 */
object AlertPolicy {

    /**
     * Sightings inside a new stretch before a merely suspicious car is announced again.
     *
     * A confirmed tail or a listed car is announced on the first reading instead: it has already
     * earned the benefit of the doubt, and every sighting reaching the follow engine has survived
     * the registry's multi-frame consensus, so none of these are lone OCR guesses.
     */
    const val REPEAT_SIGHTINGS = 2

    /**
     * Floor on how often one vehicle may interrupt the driver.
     *
     * Long enough that a car stuck in the same jam cannot turn a warning into a chant; short
     * enough that a tail which drops back and reappears twenty minutes later still gets its own
     * alarm.
     */
    const val REPEAT_COOLDOWN_MS = 8L * 60_000L

    /**
     * Floor between two words about a car that is a confirmed tail or on the operator's list.
     *
     * Short on purpose. For these two levels the driver asked to be told on every recognition for
     * as long as the car is visible, so this is only here to stop one vehicle from occupying the
     * voice completely — the speech channel applies its own guard on top, and Telegram a much
     * longer one, because a chat message every twelve seconds would hit the Bot API's rate limit
     * and take the retry queue down with it.
     */
    const val PRESENT_INTERVAL_MS = 12_000L

    /**
     * Levels at which a car is announced for as long as it stays in view.
     *
     * [marked] covers both hand-made lists — the blacklist and the police list. Neither is a guess
     * the engine made, so neither is subject to the restraint that keeps the app from nagging
     * about cars it merely suspects.
     */
    fun isPersistent(level: ThreatLevel, marked: Boolean): Boolean =
        marked || level.rank >= ThreatLevel.TAIL.rank

    fun decide(
        level: ThreatLevel,
        previousLevel: ThreatLevel,
        blacklisted: Boolean,
        /** On the operator's police list: shouts exactly like the blacklist, under its own name. */
        police: Boolean = false,
        ignored: Boolean,
        /** Readings inside the current stretch of company; reset when a stretch ends. */
        segmentSightings: Int,
        /** Already announced during this stretch. */
        alertedInSegment: Boolean,
        /** When this car was last announced, or zero if never. */
        lastAlertedMs: Long,
        nowMs: Long,
    ): AlertReason {
        if (ignored) return AlertReason.NONE
        if (level.rank > previousLevel.rank) return AlertReason.RAISED

        // A confirmed tail or a listed car: keep saying it, on a short floor, for as long as it is
        // being recognised. The two gates below — one word per stretch of company, then eight
        // minutes of quiet — are what made the phone announce a blacklisted car once and then say
        // nothing when it came back: a car that drops out of frame for ninety seconds never ends
        // its stretch, so `alertedInSegment` stayed set and swallowed everything after the first
        // word. For these levels that silence is exactly the wrong answer.
        if (isPersistent(level, blacklisted || police)) {
            if (lastAlertedMs != 0L && nowMs - lastAlertedMs < PRESENT_INTERVAL_MS) {
                return AlertReason.NONE
            }
            return when {
                alertedInSegment -> AlertReason.PRESENT
                blacklisted || police -> AlertReason.BLACKLISTED
                else -> AlertReason.RETURNED
            }
        }

        // Marked cars have already returned above, so only the engine's own verdicts reach here.
        if (level.rank < ThreatLevel.SUSPECT.rank) return AlertReason.NONE
        if (alertedInSegment) return AlertReason.NONE
        if (segmentSightings < REPEAT_SIGHTINGS) return AlertReason.NONE
        if (lastAlertedMs != 0L && nowMs - lastAlertedMs < REPEAT_COOLDOWN_MS) return AlertReason.NONE
        return AlertReason.RETURNED
    }
}
