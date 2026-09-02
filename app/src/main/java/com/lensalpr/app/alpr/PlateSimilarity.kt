package com.lensalpr.app.alpr

/**
 * Decides when two readings are the same car misread, and which spelling wins.
 *
 * The engine reads the same plate slightly differently from frame to frame: one character flips to
 * the one it looks like (EM7209 / EN7209), or the last character is cut off by the crop (EM720).
 * Without this, one car turns into three cards, three database rows and three "new contact" alerts.
 *
 * Two plates are only merged when the difference is a *shape* confusion. AB1234 and AB1235 differ by
 * one character too, but 4 and 5 look nothing alike, so those stay two different cars — a false
 * merge hides a follower, which is worse than a duplicate card.
 */
object PlateSimilarity {

    /** Characters that share a glyph shape at plate resolution. */
    private val CONFUSABLE = buildPairs(
        "0OQDC",
        "1IJLT7",
        "2Z",
        "3B8",
        "5S",
        "6GC",
        "8BR",
        "4A",
        "MN",
        "NH",
        // E read as L is what this phone actually does to EM-7209 at distance: the bars fade out.
        "EFL",
        "PR",
        "UV",
        "YV",
        "KX",
    )

    /** Shorter than this a single flipped character is too likely to be a genuinely different car. */
    private const val MIN_LENGTH = 5

    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        return when (a.length - b.length) {
            0 -> oneConfusion(a, b)
            1 -> truncated(long = a, short = b)
            -1 -> truncated(long = b, short = a)
            else -> false
        }
    }

    /** True when [a] is the spelling to keep. Longer beats shorter, then the better OCR score wins. */
    fun prefer(a: String, scoreA: Float, b: String, scoreB: Float): Boolean = when {
        a == b -> scoreA >= scoreB
        a.length != b.length -> a.length > b.length
        else -> scoreA >= scoreB
    }

    fun better(a: PlateReading, b: PlateReading): PlateReading =
        if (prefer(a.text, a.recognitionScore, b.text, b.recognitionScore)) a else b

    /** Same length, exactly one position differs, and those two characters look alike. */
    private fun oneConfusion(a: String, b: String): Boolean {
        if (a.length < MIN_LENGTH) return false
        var differing = -1
        for (index in a.indices) {
            if (a[index] == b[index]) continue
            if (differing >= 0) return false
            differing = index
        }
        if (differing < 0) return true
        return CONFUSABLE.contains(key(a[differing], b[differing]))
    }

    /** One character was cut off the front or the back of an otherwise identical read. */
    private fun truncated(long: String, short: String): Boolean {
        if (short.length < MIN_LENGTH) return false
        return long.startsWith(short) || long.endsWith(short)
    }

    private fun buildPairs(vararg groups: String): Set<Int> = buildSet {
        groups.forEach { group ->
            for (i in group.indices) {
                for (j in i + 1 until group.length) {
                    add(key(group[i], group[j]))
                }
            }
        }
    }

    /** Order-independent key for a character pair. */
    private fun key(first: Char, second: Char): Int {
        val low = minOf(first, second).code
        val high = maxOf(first, second).code
        return low * 256 + high
    }
}
