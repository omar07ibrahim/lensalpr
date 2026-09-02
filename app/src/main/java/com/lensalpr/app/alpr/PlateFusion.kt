package com.lensalpr.app.alpr

/**
 * Builds one plate out of several imperfect reads of the same car.
 *
 * The engine rarely fails the whole plate at once — it fails one character at a time, and usually a
 * different one on every frame. Picking the single best-scoring read throws that away: if frame A
 * says `EM7209` at 88 and frame B says `EN7209` at 71, the best-read rule is right here and wrong
 * whenever the confident frame is the one that slipped.
 *
 * So the reads vote per position, weighted by how sure the engine was. Three reads that each got one
 * character wrong — in three different places — still produce the correct plate, which no single
 * read contained.
 *
 * Two guardrails keep this from inventing plates:
 *
 *  * only reads of the **longest** length present vote, because a truncated read is missing
 *    information rather than disagreeing about it;
 *  * the fused string must still pass [PlateFormats], so voting can never assemble a shape that is
 *    not a plate.
 */
object PlateFusion {

    /** Beyond this the oldest reads add nothing and only cost memory. */
    const val MAX_READINGS = 8

    /** The reading a group of reads agrees on, and how many of them actually support it. */
    data class Fused(val reading: PlateReading, val support: Int)

    /**
     * Returns the reading the group agrees on, or the best single read when there is nothing to
     * fuse. [readings] must already be near-identical (see [PlateSimilarity.similar]).
     */
    fun fuse(readings: List<PlateReading>): Fused? {
        if (readings.isEmpty()) return null
        val best = readings.maxByOrNull { it.recognitionScore } ?: return null
        if (readings.size == 1) return Fused(best, 1)

        // A truncated read is missing information, not disagreeing about it, so the most complete
        // reads normally decide. But a single read one character longer than everything else is far
        // more likely to have hallucinated that character than to be the only complete one, so it
        // does not get to overrule a well-supported shorter spelling.
        val cohorts = readings.groupBy { it.text.length }
        val longest = cohorts.keys.max()
        val longestCohort = cohorts.getValue(longest)
        val bestShorter = cohorts.filterKeys { it < longest }.values.maxByOrNull { it.size }
        val voters = if (longestCohort.size < MIN_LENGTH_SUPPORT &&
            (bestShorter?.size ?: 0) >= STRONG_SUPPORT
        ) {
            bestShorter!!
        } else {
            longestCohort
        }
        val leader = voters.maxByOrNull { it.recognitionScore } ?: best
        // One voter is one voter: prefix credit is for a group that agreed, not for a lone read
        // that happens to be longer than the truncations around it.
        if (voters.size < 2) return Fused(leader, readings.count { it.text == leader.text })

        val length = leader.text.length
        // A read missing its last character still knows the other five. Aligning it against the
        // leader lets it vote where it has something to say and abstain where it does not, instead
        // of being thrown out of the ballot entirely.
        val ballots = readings.mapNotNull { reading ->
            val offset = when {
                reading.text.length == length -> 0
                reading.text.length < length -> alignmentOffset(reading.text, leader.text)
                else -> null
            }
            offset?.let { reading to it }
        }

        val fused = StringBuilder(length)
        for (position in 0 until length) {
            val weights = HashMap<Char, Double>(4)
            ballots.forEach { (reading, offset) ->
                val index = position - offset
                if (index < 0 || index >= reading.text.length) return@forEach
                val weight = reading.recognitionScore.toDouble().coerceAtLeast(1.0)
                weights[reading.text[index]] = (weights[reading.text[index]] ?: 0.0) + weight
            }
            if (weights.isEmpty()) {
                fused.append(leader.text[position])
                continue
            }
            // Ties go to the best-scoring read: without it the outcome would depend on hash order.
            val winner = weights.entries
                .sortedWith(
                    compareByDescending<Map.Entry<Char, Double>> { it.value }
                        .thenBy { if (it.key == leader.text[position]) 0 else 1 },
                )
                .first()
            fused.append(winner.key)
        }

        val text = fused.toString()
        if (text == leader.text) return Fused(leader, supportFor(text, readings))

        val plate = PlateFormats.parse(text) ?: return Fused(leader, supportFor(leader.text, readings))
        // The plate carries the confidence of the reads that actually said it. When the fusion is a
        // synthesis nobody produced on its own it carries the average of the reads behind it —
        // never the score of the spelling that was outvoted.
        val agreeing = voters.filter { it.text == text }
        val source = agreeing.maxByOrNull { it.recognitionScore }
        val score = source?.recognitionScore
            ?: voters.map { it.recognitionScore }.average().toFloat()
        return Fused(
            (source ?: leader).copy(
                text = plate.key,
                display = plate.display,
                corrected = plate.corrected || leader.corrected,
                recognitionScore = score,
            ),
            supportFor(text, readings),
        )
    }

    /**
     * How many reads actually back this spelling.
     *
     * A read that is a prefix or a suffix of it counts: it saw part of the same plate and disagrees
     * with nothing. This is what the card's evidence count is built from, so one read must never be
     * able to claim the weight of the whole group.
     */
    private fun supportFor(text: String, readings: List<PlateReading>): Int = readings.count {
        it.text == text || (it.text.length < text.length && (text.startsWith(it.text) || text.endsWith(it.text)))
    }.coerceAtLeast(1)

    /**
     * Where a shorter read sits inside the full plate: at the front, at the back, or nowhere it can
     * be trusted. One mismatched character is tolerated — that is the misread the vote exists to
     * outnumber — but two means the alignment itself is guesswork.
     */
    private fun alignmentOffset(short: String, full: String): Int? {
        if (short.isEmpty() || short.length >= full.length) return null
        val head = mismatches(short, full, 0)
        val tailOffset = full.length - short.length
        val tail = mismatches(short, full, tailOffset)
        return when {
            head <= MAX_ALIGN_MISMATCH && head <= tail -> 0
            tail <= MAX_ALIGN_MISMATCH -> tailOffset
            else -> null
        }
    }

    private fun mismatches(short: String, full: String, offset: Int): Int {
        var count = 0
        for (index in short.indices) {
            if (short[index] != full[index + offset]) count += 1
        }
        return count
    }

    private const val MAX_ALIGN_MISMATCH = 1

    /** Reads needed at the longest length before it may outrank a shorter, better-supported one. */
    private const val MIN_LENGTH_SUPPORT = 2

    /** A shorter spelling with this many reads behind it is not overruled by a single longer one. */
    private const val STRONG_SUPPORT = 3
}
