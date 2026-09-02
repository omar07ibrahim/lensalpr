package com.lensalpr.app.follow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether the app speaks at all.
 *
 * The first case below is the failure that was actually observed from the car: a vehicle correctly
 * called a tail, announced once, and then never mentioned again as it kept reappearing. Everything
 * else here is the price of fixing it — the repeat must not become a chant.
 */
class AlertPolicyTest {

    private fun decide(
        level: ThreatLevel = ThreatLevel.TAIL,
        previousLevel: ThreatLevel = ThreatLevel.TAIL,
        blacklisted: Boolean = false,
        ignored: Boolean = false,
        segmentSightings: Int = 1,
        alertedInSegment: Boolean = false,
        lastAlertedMs: Long = 0L,
        nowMs: Long = 10_000_000L,
    ) = AlertPolicy.decide(
        level = level,
        previousLevel = previousLevel,
        blacklisted = blacklisted,
        ignored = ignored,
        segmentSightings = segmentSightings,
        alertedInSegment = alertedInSegment,
        lastAlertedMs = lastAlertedMs,
        nowMs = nowMs,
    )

    @Test
    fun `a confirmed tail seen again after a gap is announced again`() {
        // The stretch of company ended (alertedInSegment cleared with it) and the car is back.
        val reason = decide(
            segmentSightings = 1,
            alertedInSegment = false,
            lastAlertedMs = 10_000_000L - AlertPolicy.REPEAT_COOLDOWN_MS - 1,
        )
        assertEquals(AlertReason.RETURNED, reason)
    }

    @Test
    fun `climbing a level is always announced`() {
        assertEquals(
            AlertReason.RAISED,
            decide(level = ThreatLevel.TAIL, previousLevel = ThreatLevel.SUSPECT),
        )
    }

    @Test
    fun `a tail already named keeps being announced while it is in view`() {
        // The failure the driver reported: a blacklisted car was named once, went out of frame for
        // a minute, came back, and the phone said nothing — the recorder started a clip, so it
        // plainly knew the car was there. Below TAIL one word per stretch is right; at TAIL and
        // above, being told again is the entire point.
        assertEquals(
            AlertReason.PRESENT,
            decide(
                alertedInSegment = true,
                segmentSightings = 12,
                lastAlertedMs = 10_000_000L - AlertPolicy.PRESENT_INTERVAL_MS - 1,
            ),
        )
    }

    @Test
    fun `a suspect car is announced only once per stretch of company`() {
        assertEquals(
            AlertReason.NONE,
            decide(
                level = ThreatLevel.SUSPECT,
                previousLevel = ThreatLevel.SUSPECT,
                alertedInSegment = true,
                segmentSightings = 12,
            ),
        )
    }

    @Test
    fun `a suspect car that keeps flickering in and out cannot chant`() {
        assertEquals(
            AlertReason.NONE,
            decide(
                level = ThreatLevel.SUSPECT,
                previousLevel = ThreatLevel.SUSPECT,
                segmentSightings = AlertPolicy.REPEAT_SIGHTINGS,
                lastAlertedMs = 10_000_000L - 60_000L,
            ),
        )
    }

    @Test
    fun `even a tail cannot speak faster than the floor`() {
        // Insistent is not the same as continuous: a sentence has to finish before the next one.
        assertEquals(
            AlertReason.NONE,
            decide(
                alertedInSegment = true,
                lastAlertedMs = 10_000_000L - AlertPolicy.PRESENT_INTERVAL_MS + 1,
            ),
        )
    }

    @Test
    fun `a listed car in view keeps being announced too`() {
        assertEquals(
            AlertReason.PRESENT,
            decide(
                level = ThreatLevel.BLACKLIST,
                previousLevel = ThreatLevel.BLACKLIST,
                blacklisted = true,
                alertedInSegment = true,
                lastAlertedMs = 10_000_000L - AlertPolicy.PRESENT_INTERVAL_MS - 1,
            ),
        )
    }

    @Test
    fun `the first word in a new stretch names the reason, later ones do not`() {
        val first = decide(
            level = ThreatLevel.BLACKLIST,
            previousLevel = ThreatLevel.BLACKLIST,
            blacklisted = true,
            alertedInSegment = false,
        )
        assertEquals(AlertReason.BLACKLISTED, first)

        val later = decide(
            level = ThreatLevel.BLACKLIST,
            previousLevel = ThreatLevel.BLACKLIST,
            blacklisted = true,
            alertedInSegment = true,
            lastAlertedMs = 10_000_000L - AlertPolicy.PRESENT_INTERVAL_MS - 1,
        )
        assertEquals(AlertReason.PRESENT, later)
    }

    @Test
    fun `a dismissed tail stays silent however long it is in view`() {
        assertEquals(
            AlertReason.NONE,
            decide(ignored = true, alertedInSegment = true, segmentSightings = 40),
        )
    }

    @Test
    fun `mere suspicion needs a second reading before repeating`() {
        val once = decide(
            level = ThreatLevel.SUSPECT,
            previousLevel = ThreatLevel.SUSPECT,
            segmentSightings = 1,
        )
        assertEquals(AlertReason.NONE, once)

        val twice = decide(
            level = ThreatLevel.SUSPECT,
            previousLevel = ThreatLevel.SUSPECT,
            segmentSightings = AlertPolicy.REPEAT_SIGHTINGS,
        )
        assertEquals(AlertReason.RETURNED, twice)
    }

    @Test
    fun `a blacklisted car is announced whenever it turns up`() {
        // Listing it sets the level before it is ever seen, so nothing can raise it. It still has
        // to be announced, or the list would be the one thing that guarantees silence.
        assertEquals(
            AlertReason.BLACKLISTED,
            decide(
                level = ThreatLevel.BLACKLIST,
                previousLevel = ThreatLevel.BLACKLIST,
                blacklisted = true,
            ),
        )
    }

    @Test
    fun `a dismissed car never speaks`() {
        assertEquals(
            AlertReason.NONE,
            decide(ignored = true, level = ThreatLevel.IGNORE, previousLevel = ThreatLevel.TAIL),
        )
        // Not even when the operator's own list and the dismissal disagree.
        assertEquals(AlertReason.NONE, decide(ignored = true, blacklisted = true))
    }

    @Test
    fun `watching is not worth a repeat`() {
        assertEquals(
            AlertReason.NONE,
            decide(
                level = ThreatLevel.WATCH,
                previousLevel = ThreatLevel.WATCH,
                segmentSightings = 30,
            ),
        )
    }

    @Test
    fun `a first ever sighting of a known dangerous car is not held back by the cooldown`() {
        // lastAlertedMs == 0 means "never announced in this process" - typically a car restored
        // from the database after a restart. It must not be treated as recently reported.
        assertEquals(AlertReason.RETURNED, decide(lastAlertedMs = 0L, nowMs = 5_000L))
    }
}
