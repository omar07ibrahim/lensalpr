package com.lensalpr.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Road furniture that reads exactly like a short plate. */
class PlateSignageTest {

    @Test
    fun `latvian road numbers are not plates`() {
        assertNull(PlateFormats.parse("A7"))
        assertNull(PlateFormats.parse("P104"))
        assertNull(PlateFormats.parse("E67"))
        assertNull(PlateFormats.parse("A10"))
    }

    @Test
    fun `a real short plate still passes`() {
        // One letter and four digits is a format the operator explicitly asked to keep.
        assertNotNull(PlateFormats.parse("A1234"))
        assertEquals("A-1234", PlateFormats.parse("A1234")?.display)
        // Another leading letter is not a road-number prefix at all.
        assertNotNull(PlateFormats.parse("B104"))
    }

    @Test
    fun `city names on distance signs are not plates`() {
        assertNull(PlateFormats.parse("RIGA25"))
        assertNull(PlateFormats.parse("OGRE12"))
    }

    @Test
    fun `a four letter plate that is not a place name survives`() {
        assertNotNull(PlateFormats.parse("ABCD12"))
        assertTrue(PlateFormats.isSignage("RIGA25"))
        assertTrue(!PlateFormats.isSignage("ABCD12"))
    }
}
