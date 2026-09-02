package com.lensalpr.app

import com.lensalpr.app.alpr.PlateFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateFormatsTest {

    @Test
    fun `every latvian layout is accepted and shown with the dash`() {
        val cases = mapOf(
            "A1234" to "A-1234",
            "a-1234" to "A-1234",
            "AA12" to "AA-12",
            "AA-12" to "AA-12",
            "ABC123" to "ABC-123",
            "ABCD-1234" to "ABCD-1234",
            "em 7209" to "EM-7209",
        )
        cases.forEach { (raw, display) ->
            val plate = PlateFormats.parse(raw)
            assertEquals("input $raw", display, plate?.display)
            assertTrue("input $raw", plate?.latvian == true)
        }
    }

    @Test
    fun `separators never split one plate into two vehicles`() {
        val keys = listOf("EM-7209", "em7209", " EM 7209 ").map { PlateFormats.parse(it)?.key }
        assertEquals(setOf("EM7209"), keys.toSet())
    }

    @Test
    fun `text the engine reads off signs and screens is rejected`() {
        // Novelty plates and stray text, measured on a real run at confidence 82-89 - higher than
        // the correct plate, so only the shape can tell them apart.
        listOf("0001", "GOOD", "XAKEP", "NTERNET", "3AKOH", "SVIN", "ALMAZ")
            .forEach { junk -> assertNull(junk, PlateFormats.parse(junk, strictLatvia = true)) }
        listOf("0001", "GOOD", "XAKEP", "NTERNET", "3AKOH")
            .forEach { junk -> assertNull(junk, PlateFormats.parse(junk)) }
    }

    @Test
    fun `characters the layout leaves no choice about are repaired`() {
        // O cannot stand inside the number block, I cannot either.
        assertEquals("EM-7209", PlateFormats.parse("EM72O9")?.display)
        assertTrue(PlateFormats.parse("EM72O9")?.corrected == true)
        // A string that is already a valid layout is never touched: EMI-209 is a plate on its own.
        assertEquals("EMI-209", PlateFormats.parse("EMI209")?.display)
        assertTrue(PlateFormats.parse("EMI209")?.corrected == false)
        // ...but a repair is never allowed to invent a plate out of a word.
        assertNull(PlateFormats.parse("GOOD", strictLatvia = true))
    }

    @Test
    fun `foreign plates still pass unless the local format is enforced`() {
        val uk = PlateFormats.parse("NY53NKD")
        assertEquals("NY53NKD", uk?.key)
        assertTrue(uk?.latvian == false)
        assertNull(PlateFormats.parse("NY53NKD", strictLatvia = true))
    }
}
