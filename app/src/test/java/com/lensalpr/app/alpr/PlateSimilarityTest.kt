package com.lensalpr.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateSimilarityTest {

    @Test
    fun `same plate is similar`() {
        assertTrue(PlateSimilarity.similar("EM7209", "EM7209"))
    }

    @Test
    fun `one confusable character is the same car`() {
        // Both rows really came out of one drive with this phone.
        assertTrue(PlateSimilarity.similar("EM7209", "LM7209"))
        assertTrue(PlateSimilarity.similar("EM7209", "EN7209"))
        assertTrue(PlateSimilarity.similar("LV1234", "IV1234"))
        assertTrue(PlateSimilarity.similar("GO2055", "G02055"))
        assertTrue(PlateSimilarity.similar("KB5510", "KR5510"))
    }

    @Test
    fun `plates that differ by an unrelated character stay separate cars`() {
        assertFalse(PlateSimilarity.similar("AB1234", "AB1235"))
        assertFalse(PlateSimilarity.similar("AB1234", "AB1934"))
        assertFalse(PlateSimilarity.similar("EM7209", "EM7309"))
    }

    @Test
    fun `two differences are never merged`() {
        assertFalse(PlateSimilarity.similar("EM7209", "EN72O9"))
    }

    @Test
    fun `short plates are left alone`() {
        assertFalse(PlateSimilarity.similar("AB12", "AB1Z"))
        assertFalse(PlateSimilarity.similar("A123", "A12E"))
    }

    @Test
    fun `a cut off character is the same plate`() {
        assertTrue(PlateSimilarity.similar("EM7209", "EM720"))
        assertTrue(PlateSimilarity.similar("EM7209", "M7209"))
        assertFalse(PlateSimilarity.similar("EM7209", "M720"))
    }

    @Test
    fun `the better score wins between equal length spellings`() {
        assertTrue(PlateSimilarity.prefer("EM7209", 91f, "EN7209", 78f))
        assertFalse(PlateSimilarity.prefer("EN7209", 78f, "EM7209", 91f))
    }

    @Test
    fun `the complete read wins over a truncated one even with a better score`() {
        assertTrue(PlateSimilarity.prefer("EM7209", 60f, "EM720", 99f))
        assertFalse(PlateSimilarity.prefer("EM720", 99f, "EM7209", 60f))
    }

    @Test
    fun `better picks the reading the engine was surer about`() {
        val weak = reading("EN7209", 71f)
        val strong = reading("EM7209", 88f)
        assertEquals(strong, PlateSimilarity.better(weak, strong))
        assertEquals(strong, PlateSimilarity.better(strong, weak))
    }

    private fun reading(text: String, score: Float) = PlateReading(
        text = text,
        rawText = text,
        display = text,
        corrected = false,
        recognitionScore = score,
        detectionScore = score,
        countryCode = null,
        countryName = null,
        state = null,
        car = null,
        box = null,
    )
}
