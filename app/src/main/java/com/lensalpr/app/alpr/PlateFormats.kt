package com.lensalpr.app.alpr

/**
 * Turns whatever the OCR returned into a plate - or rejects it.
 *
 * Two jobs, and the second is what actually raises accuracy:
 *
 *  * **Rejection.** The engine happily reads any text it finds: on a measured run "0001", "GOOD",
 *    "XAKEP" and "INTERNET" all came back scoring 89, higher than the correct plate. No confidence
 *    threshold separates those from a real plate, but the *shape* does.
 *  * **Correction.** The Latvian layout is letters then digits, so the position of a character says
 *    what it must be. A `O` inside the number block can only be a zero, an `I` can only be a one.
 *    Applying that turns a discarded read into a correct one instead of a wrong one.
 */
object PlateFormats {

    /**
     * Latvia: 1-4 letters then 2-4 digits - A-1234, AA-12, ABC-123, ABCD-1234.
     *
     * At least two digits on purpose: a single trailing digit lets a word through. "GOOD" is one
     * ambiguity fix away from "GOO-0", and that is exactly the kind of invention this must not do.
     */
    private val LATVIA = Regex("^([A-Z]{1,4})([0-9]{2,4})$")

    private const val GENERIC_MIN = 4
    private const val GENERIC_MAX = 10
    private const val MAX_CORRECTIONS = 2

    /** Characters an OCR mixes up, resolved by the block they landed in. */
    private val TO_DIGIT = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'L' to '1', 'J' to '1',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6', 'T' to '7',
    )
    private val TO_LETTER = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G', '4' to 'A',
    )

    /** Cyrillic look-alikes, in case the engine ever answers outside the latin charset. */
    private val CYRILLIC = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
        'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X',
    )

    data class Plate(
        /** Comparison key: no separators, this is what consensus counts. */
        val key: String,
        /** What the operator reads: AA-1234. */
        val display: String,
        val latvian: Boolean,
        /** True when the format resolved an ambiguous character. */
        val corrected: Boolean,
    )

    /**
     * Text that has the shape of a plate but is painted on the road furniture instead.
     *
     * Latvian road numbers are a letter and two or three digits — A7, P104, E67 for the Via Baltica
     * — which is exactly the layout of a short plate, and they stand beside every road the phone
     * will ever look at. Distance signs add the same problem with city names in the letter block.
     * No confidence threshold separates these from plates; only knowing what they are does.
     */
    private val ROAD_NUMBER = Regex("^[AEP][0-9]{1,3}$")
    private val PLACE_NAMES = setOf("RIGA", "OGRE", "CESIS", "ADAZI", "SALDUS", "TALSI", "LIMBAZI")

    fun parse(raw: String?, strictLatvia: Boolean = false): Plate? {
        val cleaned = clean(raw) ?: return null
        if (isSignage(cleaned)) return null

        latvian(cleaned, corrected = false)?.let { return it }
        // Correction is exactly how a road sign sneaks back in: "P1O4" is one O-for-zero flip away
        // from the P104 that was just refused, and that flip is the commonest misread there is.
        correctToLatvian(cleaned)?.takeUnless { isSignage(it.key) }?.let { return it }
        if (strictLatvia) return null
        return generic(cleaned)
    }

    /** Same rules, for text typed by a human (a plate sent to the bot, for instance). */
    fun key(raw: String?): String? = parse(raw, strictLatvia = false)?.key

    /** True for the road numbers and place names that share a plate's shape. */
    fun isSignage(text: String): Boolean {
        if (ROAD_NUMBER.matches(text)) return true
        val letters = text.takeWhile { it in 'A'..'Z' }
        return letters.length >= 4 && letters in PLACE_NAMES
    }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val builder = StringBuilder(raw.length)
        raw.uppercase().forEach { char ->
            val mapped = CYRILLIC[char] ?: char
            if (mapped in 'A'..'Z' || mapped in '0'..'9') builder.append(mapped)
        }
        return builder.toString().takeIf { it.isNotEmpty() }
    }

    private fun latvian(text: String, corrected: Boolean): Plate? {
        LATVIA.matchEntire(text)?.let { match ->
            return Plate(
                key = text,
                display = "${match.groupValues[1]}-${match.groupValues[2]}",
                latvian = true,
                corrected = corrected,
            )
        }
        return null
    }

    /**
     * Tries to make the string valid by resolving characters that the layout leaves no choice
     * about. Every split point is considered, and the fix is accepted only when it costs at most
     * [MAX_CORRECTIONS] characters - beyond that it would be inventing a plate, not reading one.
     */
    private fun correctToLatvian(text: String): Plate? {
        if (text.length < 3 || text.length > 8) return null
        // Only repair something that already looks like a plate. A word with no digit in it at all
        // is not a misread plate, and "correcting" it would be inventing one.
        if (text.none { it.isDigit() } || text.none { it.isLetter() }) return null
        var best: Plate? = null
        var bestCost = MAX_CORRECTIONS + 1

        for (split in 1 until text.length) {
            val letterPart = text.substring(0, split)
            val digitPart = text.substring(split)
            if (letterPart.length > 4 || digitPart.length > 4) continue

            var cost = 0
            val letters = StringBuilder()
            for (char in letterPart) {
                when {
                    char in 'A'..'Z' -> letters.append(char)
                    TO_LETTER.containsKey(char) -> {
                        letters.append(TO_LETTER.getValue(char)); cost += 1
                    }
                    else -> cost = MAX_CORRECTIONS + 1
                }
                if (cost > MAX_CORRECTIONS) break
            }
            if (cost > MAX_CORRECTIONS) continue

            val digits = StringBuilder()
            for (char in digitPart) {
                when {
                    char in '0'..'9' -> digits.append(char)
                    TO_DIGIT.containsKey(char) -> {
                        digits.append(TO_DIGIT.getValue(char)); cost += 1
                    }
                    else -> cost = MAX_CORRECTIONS + 1
                }
                if (cost > MAX_CORRECTIONS) break
            }
            if (cost > MAX_CORRECTIONS || cost == 0 || cost >= bestCost) continue

            val fixed = letters.toString() + digits.toString()
            latvian(fixed, corrected = true)?.let {
                best = it
                bestCost = cost
            }
        }
        return best
    }

    /**
     * Anything outside the local format still has to look like a plate: a following car may well
     * be foreign, but a road sign is not a vehicle.
     */
    private fun generic(text: String): Plate? {
        if (text.length !in GENERIC_MIN..GENERIC_MAX) return null
        val digits = text.count { it.isDigit() }
        val letters = text.length - digits
        if (letters < 1 || digits < 2) return null
        return Plate(key = text, display = text, latvian = false, corrected = false)
    }
}
