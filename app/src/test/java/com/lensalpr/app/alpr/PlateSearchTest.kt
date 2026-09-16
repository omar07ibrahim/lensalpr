package com.lensalpr.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search box gets what the app shows: dashes, spaces, a Russian keyboard. */
class PlateSearchTest {

    @Test
    fun `separators are dropped from a search fragment`() {
        assertEquals("EM7209", PlateFormats.searchKey("EM-7209"))
        assertEquals("EM7209", PlateFormats.searchKey(" em 7209 "))
    }

    @Test
    fun `a fragment that is not a whole plate still searches`() {
        assertEquals("7209", PlateFormats.searchKey("7209"))
        assertEquals("EM", PlateFormats.searchKey("EM"))
    }

    @Test
    fun `cyrillic look-alikes map onto the latin keys`() {
        assertEquals("EM7209", PlateFormats.searchKey("ЕМ-7209"))
    }

    @Test
    fun `nothing to search for`() {
        assertNull(PlateFormats.searchKey("--"))
        assertNull(PlateFormats.searchKey(null))
    }
}
