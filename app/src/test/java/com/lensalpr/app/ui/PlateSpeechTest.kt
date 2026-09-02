package com.lensalpr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plate has to survive being heard once, at speed, over road noise.
 */
class PlateSpeechTest {

    @Test
    fun `latin letters are spoken as russian letter names`() {
        assertEquals("е, эн, 7, 2, 0, 9", PlateSpeech.spell("EN-7209"))
    }

    @Test
    fun `the separator is dropped rather than announced`() {
        // "минус" in the middle of a plate is worse than no separator at all.
        assertEquals("ка, а, 2, 2, 7, 2", PlateSpeech.spell("KA-2272"))
        assertEquals(PlateSpeech.spell("KA2272"), PlateSpeech.spell("KA-2272"))
    }

    @Test
    fun `lowercase reads the same as uppercase`() {
        assertEquals(PlateSpeech.spell("NK2703"), PlateSpeech.spell("nk2703"))
    }

    @Test
    fun `digits are spoken one at a time, never as a number`() {
        // "восемьсот пятьдесят пять" is unusable for writing a plate down.
        assertEquals("8, 5, 5, 5", PlateSpeech.spell("8555"))
    }

    @Test
    fun `every latin letter has a name`() {
        ('A'..'Z').forEach { letter ->
            val spoken = PlateSpeech.spell(letter.toString())
            assertEquals("$letter has no spoken name", true, spoken.isNotBlank())
        }
    }

    @Test
    fun `junk characters are skipped without leaving stray commas`() {
        assertEquals("а, б не поддерживается", "а, бэ", PlateSpeech.spell("A/B"))
        assertEquals("", PlateSpeech.spell("---"))
    }
}
