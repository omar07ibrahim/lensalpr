package com.lensalpr.app.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long lists must reach the chat whole and valid; the old fixed cut broke tags mid-way. */
class TelegramTextTest {

    @Test
    fun `short text is one message`() {
        assertEquals(listOf("hello"), TelegramClient.splitForTelegram("hello", 100))
    }

    @Test
    fun `long lists are cut between lines and every line stays intact`() {
        val lines = (1..40).map { "<code>AB-%04d</code> — встреч $it".format(it) }
        val text = lines.joinToString("\n")
        val chunks = TelegramClient.splitForTelegram(text, 300)
        assertTrue("got ${chunks.size} chunks", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 300)
            chunk.split('\n').forEach { line -> assertTrue(line, line.startsWith("<code>") && line.contains("</code>")) }
        }
        assertEquals(lines, chunks.flatMap { it.split('\n') })
    }

    @Test
    fun `a single overlong line is cut on a space`() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val chunks = TelegramClient.splitForTelegram(text, 60)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 60) }
        assertEquals(text.split(' '), chunks.flatMap { it.split(' ') })
    }

    @Test
    fun `dynamic text is escaped for html`() {
        assertEquals("Class.&lt;init&gt; &amp; co", TelegramClient.escape("Class.<init> & co"))
    }
}
