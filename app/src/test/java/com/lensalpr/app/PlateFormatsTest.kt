package com.lensalpr.app

import com.lensalpr.app.alpr.PlateFormats
import com.lensalpr.app.alpr.PlateRegion
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
            assertEquals("input $raw", PlateRegion.LATVIA, plate?.region)
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
            .forEach { junk -> assertNull(junk, PlateFormats.parse(junk, strict = true)) }
        listOf("0001", "GOOD", "XAKEP", "NTERNET", "3AKOH")
            .forEach { junk -> assertNull(junk, PlateFormats.parse(junk)) }
    }

    @Test
    fun `characters the layout leaves no choice about are repaired`() {
        // Once the local format is explicit, repair OCR confusion inside its digit block.
        assertEquals("EM-7209", PlateFormats.parse("EM72O9", strict = true)?.display)
        assertTrue(PlateFormats.parse("EM72O9", strict = true)?.corrected == true)
        // A string that is already a valid layout is never touched: EMI-209 is a plate on its own.
        assertEquals("EMI-209", PlateFormats.parse("EMI209")?.display)
        assertTrue(PlateFormats.parse("EMI209")?.corrected == false)
        // ...but a repair is never allowed to invent a plate out of a word.
        assertNull(PlateFormats.parse("GOOD", strict = true))
    }

    @Test
    fun `foreign plates still pass unless the local format is enforced`() {
        val uk = PlateFormats.parse("NY53NKD")
        assertEquals("NY53NKD", uk?.key)
        assertTrue(uk?.latvian == false)
        assertNull(uk?.region)
        assertNull(PlateFormats.parse("NY53NKD", strict = true))
    }

    @Test
    fun `unknown and foreign identifiers are preserved rather than guessed`() {
        assertEquals("GBB01B", PlateFormats.parse("GBB-01-B")?.key)
        assertEquals("XX85TS", PlateFormats.parse("XX-85-TS")?.key)
        assertEquals("EM72O9", PlateFormats.parse("EM72O9")?.key)
        assertEquals("GBB01B", PlateFormats.parse("GBB-01-B", countryCode = "NL")?.key)
        assertNull(PlateFormats.parse("GBB-01-B", strict = true, countryCode = "NL"))
    }

    @Test
    fun `country evidence permits OCR correction without modifying human input`() {
        assertEquals("EM7209", PlateFormats.parse("EM72O9", countryCode = "LV")?.key)
        assertEquals("EM72O9", PlateFormats.key("EM72O9"))
        assertEquals("GBB01B", PlateFormats.key("GBB-01-B"))
        assertTrue(PlateFormats.key("GBB-018") != PlateFormats.key("GBB-01-B"))
    }

    @Test
    fun `corrected signs never reappear through the generic fallback`() {
        for (strict in listOf(false, true)) {
            for (raw in listOf("P104", "P1O4", "RIGA12", "R1GA12")) {
                assertNull("$raw strict=$strict", PlateFormats.parse(raw, strict))
            }
        }
    }

    // ------------------------------------------------------------ Lithuania and Estonia

    @Test
    fun `lithuanian layouts are accepted whatever region is configured`() {
        val cases = mapOf(
            "ABC123" to "ABC 123",
            "abc 123" to "ABC 123",
            "AB123" to "AB 123",
            "123AB" to "123 AB",
            "AB1234" to "AB 1234",
            "ABC12" to "ABC 12",
        )
        cases.forEach { (raw, display) ->
            val plate = PlateFormats.parse(raw, region = PlateRegion.LITHUANIA)
            assertEquals("input $raw", display, plate?.display)
            assertEquals("input $raw", PlateRegion.LITHUANIA, plate?.region)
            assertEquals("input $raw", raw.uppercase().filter { it.isLetterOrDigit() }, plate?.key)
        }
        // With Latvia configured the same text is a Latvian plate when Latvia's layouts fit it...
        assertEquals(PlateRegion.LATVIA, PlateFormats.parse("ABC123")?.region)
        assertEquals("ABC-123", PlateFormats.parse("ABC123")?.display)
        // ...and still accepted, as Lithuanian, when only Lithuania's do.
        val digitsFirst = PlateFormats.parse("123AB")
        assertEquals(PlateRegion.LITHUANIA, digitsFirst?.region)
        assertEquals("123 AB", digitsFirst?.display)
    }

    @Test
    fun `estonian layouts are accepted whatever region is configured`() {
        val cases = mapOf(
            "123ABC" to "123 ABC",
            "123 abc" to "123 ABC",
            "123AB" to "123 AB",
            "12ABC" to "12 ABC",
            "1234AB" to "1234 AB",
        )
        cases.forEach { (raw, display) ->
            val plate = PlateFormats.parse(raw, region = PlateRegion.ESTONIA)
            assertEquals("input $raw", display, plate?.display)
            assertEquals("input $raw", PlateRegion.ESTONIA, plate?.region)
        }
        // Digits-first with three letters fits only Estonia, so it is Estonian on a Latvian phone.
        assertEquals(PlateRegion.ESTONIA, PlateFormats.parse("123ABC")?.region)
        // And a Latvian car on an Estonian phone is still a Latvian car.
        assertEquals(PlateRegion.LATVIA, PlateFormats.parse("EM7209", region = PlateRegion.ESTONIA)?.region)
        assertEquals("EM-7209", PlateFormats.parse("EM7209", region = PlateRegion.ESTONIA)?.display)
    }

    @Test
    fun `the engine's country hint decides which layouts win`() {
        // Three letters and three digits fit Latvia, Lithuania and Estonia; the hint breaks the tie.
        assertEquals("ABC 123", PlateFormats.parse("ABC123", countryCode = "LT")?.display)
        assertEquals("ABC 123", PlateFormats.parse("ABC123", countryCode = "EE")?.display)
        assertEquals("ABC-123", PlateFormats.parse("ABC123", countryCode = "LV")?.display)
        assertEquals("ABC-123", PlateFormats.parse("ABC123", countryCode = "XX")?.display)
    }

    @Test
    fun `repairs follow the configured region`() {
        // 1 read as I inside the digit block of an Estonian plate: only the digit block can hold it.
        assertEquals("123 ABC", PlateFormats.parse("I23ABC", strict = true, region = PlateRegion.ESTONIA)?.display)
        assertEquals("123 ABC", PlateFormats.parse("I23ABC", countryCode = "EE")?.display)
        // A Lithuanian read with O in the digit block.
        assertEquals("ABC 120", PlateFormats.parse("ABC12O", countryCode = "LT")?.display)
        // Without a hint and without strict the read is kept as is - it may be a foreign plate.
        assertEquals("ABC12O", PlateFormats.parse("ABC12O")?.key)
        // A country the scanner has no layouts for is never "repaired" into a local plate.
        assertEquals("ABC12O", PlateFormats.parse("ABC12O", countryCode = "PL")?.key)
        assertNull(PlateFormats.parse("ABC12O", strict = true, countryCode = "PL"))
        // Strict on a Latvian phone refuses a shape only Estonia issues.
        assertNull(PlateFormats.parse("123ABC", strict = true))
        assertNull(PlateFormats.parse("123ABC", strict = true, countryCode = "EE"))
        // Strict on an Estonian phone refuses a Latvian-only shape and the generic fallback.
        assertNull(PlateFormats.parse("ABCD1234", strict = true, region = PlateRegion.ESTONIA))
        assertNull(PlateFormats.parse("NY53NKD", strict = true, region = PlateRegion.ESTONIA))
    }

    @Test
    fun `baltic place names on distance signs are not plates`() {
        listOf("VILNIUS 12", "KAUNAS45", "TALLINN 120", "TARTU 88").forEach { sign ->
            assertNull(sign, PlateFormats.parse(sign, region = PlateRegion.LITHUANIA))
            assertNull(sign, PlateFormats.key(sign))
        }
    }

    @Test
    fun `human input is keyed by the same layouts in every region`() {
        assertEquals("123ABC", PlateFormats.key("123 abc"))
        assertEquals("ABC123", PlateFormats.key("abc-123"))
        assertEquals("123ABC", PlateFormats.parse("123 abc")?.key)
        assertEquals("123 ABC", PlateFormats.parse("123 abc")?.display)
    }
}
