package com.lensalpr.app.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The turn maths decides who counts as a follower, so its sign convention has to be nailed down. */
class TripMathTest {

    @Test
    fun `turning right across north is a small positive delta`() {
        assertEquals(20f, TripTracker.signedDelta(350f, 10f), 0.01f)
    }

    @Test
    fun `turning left across north is a small negative delta`() {
        assertEquals(-20f, TripTracker.signedDelta(10f, 350f), 0.01f)
    }

    @Test
    fun `a quarter turn is ninety degrees whichever way it is driven`() {
        assertEquals(90f, TripTracker.signedDelta(0f, 90f), 0.01f)
        assertEquals(-90f, TripTracker.signedDelta(90f, 0f), 0.01f)
    }

    @Test
    fun `a junction accumulates past the threshold across the north wrap`() {
        // Five one-second headings of a normal right turn that crosses north: the deltas the
        // tracker sums must come out positive and add up to the turn, not to a 350-degree swing.
        val headings = listOf(340f, 355f, 10f, 25f, 40f, 50f)
        val total = headings.zipWithNext { from, to -> TripTracker.signedDelta(from, to) }.sum()
        assertEquals(70f, total, 0.01f)
        assertTrue(total >= 40f)
    }

    @Test
    fun `distance between two points on the same street is metres, not degrees`() {
        val metres = TripTracker.distanceMeters(56.9496, 24.1052, 56.9496, 24.1152)
        assertTrue("got $metres", metres in 550.0..620.0)
    }

    @Test
    fun `the same point is zero metres away`() {
        assertEquals(0.0, TripTracker.distanceMeters(56.9496, 24.1052, 56.9496, 24.1052), 0.001)
    }
}
