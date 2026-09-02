package com.lensalpr.app.alpr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry filter that catches what the character rules cannot: text that is not plate-shaped. */
class PlateShapeTest {

    @Test
    fun `a latvian plate is long and thin`() {
        assertTrue(AlprJson.plausiblePlateShape(470f, 100f))
    }

    @Test
    fun `a plate seen from an angle is still accepted`() {
        // A 4.7:1 plate rotated twenty degrees measures under 2:1 once boxed.
        assertTrue(AlprJson.plausiblePlateShape(475f, 254f))
    }

    @Test
    fun `a motorcycle plate is accepted`() {
        assertTrue(AlprJson.plausiblePlateShape(210f, 140f))
    }

    @Test
    fun `a square block of text is not a plate`() {
        assertFalse(AlprJson.plausiblePlateShape(120f, 120f))
        assertFalse(AlprJson.plausiblePlateShape(140f, 130f))
    }

    @Test
    fun `an absurdly stretched box is not a plate either`() {
        assertFalse(AlprJson.plausiblePlateShape(1000f, 60f))
    }

    @Test
    fun `without a usable box the shape rule stays out of the way`() {
        assertTrue(AlprJson.plausiblePlateShape(0f, 0f))
        assertTrue(AlprJson.plausiblePlateShape(3f, 2f))
    }
}
