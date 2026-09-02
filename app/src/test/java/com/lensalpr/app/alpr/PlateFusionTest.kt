package com.lensalpr.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateFusionTest {

    @Test
    fun `nothing to fuse`() {
        assertNull(PlateFusion.fuse(emptyList()))
    }

    @Test
    fun `one hallucinated character does not inherit the weight of the good reads`() {
        // Six reads say EM7209 and one says EM72099; the long one must not take the card over.
        val readings = List(6) { reading("EM7209", 80f) } + reading("EM72099", 92f)
        val fused = PlateFusion.fuse(readings)!!
        assertEquals("EM7209", fused.reading.text)
        assertEquals(6, fused.support)
    }

    @Test
    fun `support counts only the reads that back the spelling`() {
        val fused = PlateFusion.fuse(
            listOf(reading("EN7209", 88f), reading("EM7209", 80f), reading("EM7209", 79f)),
        )!!
        assertEquals("EM7209", fused.reading.text)
        assertEquals(2, fused.support)
    }

    @Test
    fun `a truncated read still supports the complete plate`() {
        val fused = PlateFusion.fuse(
            listOf(reading("EM7209", 88f), reading("EM720", 80f), reading("EM7209", 79f)),
        )!!
        assertEquals("EM7209", fused.reading.text)
        assertEquals(3, fused.support)
    }

    @Test
    fun `a single read is returned untouched`() {
        val only = reading("EM7209", 74f)
        assertEquals(only, fuseReading(listOf(only)))
    }

    @Test
    fun `the majority wins a disputed character`() {
        val fused = fuseReading(
            listOf(reading("EN7209", 88f), reading("EM7209", 80f), reading("EM7209", 79f)),
        )
        assertEquals("EM7209", fused?.text)
    }

    @Test
    fun `confidence beats a one-vote majority`() {
        // Two weak reads should not outvote a read the engine was far surer about.
        val fused = fuseReading(
            listOf(reading("EM7209", 95f), reading("EN7209", 41f), reading("EN7209", 42f)),
        )
        assertEquals("EM7209", fused?.text)
    }

    @Test
    fun `three reads each wrong in a different place still produce the right plate`() {
        val fused = fuseReading(
            listOf(reading("LM7209", 81f), reading("EM7208", 80f), reading("EM3209", 79f)),
        )
        assertEquals("EM7209", fused?.text)
    }

    @Test
    fun `the plate carries the confidence of the reads that said it`() {
        // The 88 belonged to the spelling that lost the vote, so it must not travel with the winner.
        val outvoted = fuseReading(
            listOf(reading("EN7209", 88f), reading("EM7209", 80f), reading("EM7209", 79f)),
        )!!
        assertEquals("EM7209", outvoted.text)
        assertEquals(80f, outvoted.recognitionScore, 0.01f)

        // Nobody produced this spelling alone, so it carries the average of the reads behind it.
        val synthesised = fuseReading(
            listOf(reading("LM7209", 81f), reading("EM7208", 80f), reading("EM3209", 79f)),
        )!!
        assertEquals("EM7209", synthesised.text)
        assertEquals(80f, synthesised.recognitionScore, 0.01f)
    }

    @Test
    fun `fusion never scores higher than the reads behind it`() {
        val fused = fuseReading(
            listOf(reading("EN7209", 88f), reading("EM7209", 80f), reading("EM7209", 79f)),
        )!!
        assertTrue(fused.recognitionScore <= 88f)
    }

    @Test
    fun `truncated reads do not drag the plate down to their length`() {
        val fused = fuseReading(
            listOf(reading("EM720", 93f), reading("EM7209", 78f), reading("EM720", 92f)),
        )
        assertEquals("EM7209", fused?.text)
    }

    @Test
    fun `whatever voting produces is still a valid plate`() {
        // Voters with different letter blocks can mix into a shape neither of them had.
        val fused = fuseReading(
            listOf(reading("AB1234", 90f), reading("ABCD12", 89f), reading("ABCD12", 88f)),
        )!!
        assertTrue("got ${fused.text}", PlateFormats.parse(fused.text) != null)
    }

    @Test
    fun `the display form follows the fused text`() {
        val fused = fuseReading(
            listOf(reading("EN7209", 88f), reading("EM7209", 80f), reading("EM7209", 79f)),
        )
        assertEquals("EM-7209", fused?.display)
    }

    /** The fused reading alone; support is asserted separately where it matters. */
    private fun fuseReading(readings: List<PlateReading>) = PlateFusion.fuse(readings)?.reading

    private fun reading(text: String, score: Float) = PlateReading(
        text = text,
        rawText = text,
        display = PlateFormats.parse(text)?.display ?: text,
        corrected = false,
        recognitionScore = score,
        detectionScore = 99f,
        countryCode = null,
        countryName = null,
        state = null,
        car = null,
        box = null,
    )
}
