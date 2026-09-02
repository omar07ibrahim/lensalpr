package com.lensalpr.app.pipeline

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps score of how often each cropping strategy actually produces a plate.
 *
 * Every change to the recognition path so far has been an argument, not a measurement, and one of
 * them — cutting the next crop tightly around a plate the engine already found — could plausibly
 * make things worse rather than better. Rather than ask the operator to film and label a test drive,
 * the app measures itself during an ordinary one.
 *
 * The metric is the **share of crops that came back with a plate**, kept separately for each
 * strategy. Plates per minute would mostly measure the traffic; a hit rate over the crops actually
 * sent does not. What it cannot see is a plate read *wrongly* — a misread that still looks like a
 * plate counts as a hit here — so it detects a regression in finding plates, not in spelling them.
 */
class CropStats {

    private val sent = arrayOf(AtomicInteger(), AtomicInteger())
    private val hits = arrayOf(AtomicInteger(), AtomicInteger())
    private val latency = arrayOf(AtomicLong(), AtomicLong())

    fun record(narrow: Boolean, gotPlate: Boolean, latencyMs: Long) {
        val arm = if (narrow) NARROW else WIDE
        sent[arm].incrementAndGet()
        if (gotPlate) hits[arm].incrementAndGet()
        latency[arm].addAndGet(latencyMs)
    }

    fun reset() {
        for (arm in 0..1) {
            sent[arm].set(0)
            hits[arm].set(0)
            latency[arm].set(0L)
        }
    }

    val totalSent: Int get() = sent[WIDE].get() + sent[NARROW].get()

    /** Raw numbers for the report, which has to show the sample size and not just the percentage. */
    fun arm(narrow: Boolean): Triple<Int, Int, Long> {
        val index = if (narrow) NARROW else WIDE
        val total = sent[index].get()
        val avg = if (total == 0) 0L else latency[index].get() / total
        return Triple(total, hits[index].get(), avg)
    }

    /** One line per strategy, or a note that there is nothing to compare yet. */
    fun summary(): String {
        if (totalSent == 0) return "вырезов пока не было"
        return buildString {
            append(line("по машине", WIDE))
            append('\n')
            append(line("по номеру", NARROW))
        }
    }

    /** Short form for the status message. */
    fun compact(): String {
        if (totalSent == 0) return "—"
        return String.format(
            Locale.US,
            "машина %d%% · номер %d%%",
            percent(WIDE),
            percent(NARROW),
        )
    }

    private fun line(name: String, arm: Int): String {
        val total = sent[arm].get()
        if (total == 0) return "$name: не использовался"
        return String.format(
            Locale.US,
            "%s: номер в %d%% вырезов (%d из %d), %d мс",
            name,
            percent(arm),
            hits[arm].get(),
            total,
            latency[arm].get() / total,
        )
    }

    private fun percent(arm: Int): Int {
        val total = sent[arm].get()
        return if (total == 0) 0 else hits[arm].get() * 100 / total
    }

    private companion object {
        const val WIDE = 0
        const val NARROW = 1
    }
}
