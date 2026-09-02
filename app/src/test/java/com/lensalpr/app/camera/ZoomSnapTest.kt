package com.lensalpr.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom ratio the app requests decides which physical lens the camera hands over. Focal-length
 * maths lands just under the ratios Samsung switches on, which is enough to be served a crop of the
 * previous, wider module instead of the telephoto that was asked for.
 */
class ZoomSnapTest {

    @Test
    fun `the derived telephoto ratio reaches the switch point`() {
        assertEquals(3f, snapToSwitchPoint(2.79f), 0.001f)
    }

    @Test
    fun `the derived periscope ratio reaches the switch point`() {
        assertEquals(5f, snapToSwitchPoint(4.62f), 0.001f)
    }

    @Test
    fun `a ratio already at a switch point is left alone`() {
        assertEquals(1f, snapToSwitchPoint(1f), 0.001f)
        assertEquals(5f, snapToSwitchPoint(5f), 0.001f)
    }

    @Test
    fun `a ratio nowhere near a switch point is not invented upwards`() {
        // 7x is a genuine digital step between the periscope and 10x; snapping it would be a lie.
        assertEquals(7f, snapToSwitchPoint(7f), 0.001f)
        assertEquals(1.6f, snapToSwitchPoint(1.6f), 0.001f)
    }

    @Test
    fun `the ultra wide keeps its own ratio`() {
        assertEquals(0.5f, snapToSwitchPoint(0.47f), 0.001f)
    }
}
