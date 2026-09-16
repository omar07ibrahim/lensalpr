package com.lensalpr.app.alpr

/** The plate layouts the scanner knows. The operator picks one; the engine's country hint may override it. */
enum class PlateRegion(val code: String) {
    LATVIA("LV"),
    LITHUANIA("LT"),
    ESTONIA("EE"),
    ;

    companion object {
        fun fromCode(code: String?): PlateRegion? {
            val trimmed = code?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?: return null
            return entries.firstOrNull { it.code.equals(trimmed, ignoreCase = true) }
        }
    }
}

/**
 * Turns whatever the OCR returned into a plate - or rejects it.
 *
 * Two jobs, and the second is what actually raises accuracy:
 *
 *  * **Rejection.** The engine happily reads any text it finds: on a measured run "0001", "GOOD",
 *    "XAKEP" and "INTERNET" all came back scoring 89, higher than the correct plate. No confidence
 *    threshold separates those from a real plate, but the *shape* does.
 *  * **Correction.** A layout says which positions are letters and which are digits, so the position
 *    of a character says what it must be. A `O` inside the number block can only be a zero, an `I`
 *    can only be a one. Applying that turns a discarded read into a correct one instead of a wrong one.
 *
 * Every region is a list of layout masks. Latvia's are strict on purpose — letters then digits, and
 * that is measured to work. Lithuania's and Estonia's are deliberately looser: both countries issue
 * several shapes (cars, trailers, motorcycles, older series), and a scanner that only knew the
 * standard car plate would throw the rest away.
 */
object PlateFormats {

    /**
     * One shape a plate can take: `L` a letter, `N` a digit, anything else a separator that is
     * shown but never part of the comparison key.
     */
    private class Layout(val mask: String) {
        val classes: String = mask.filter { it == 'L' || it == 'N' }
        val length: Int get() = classes.length

        fun matches(text: String): Boolean {
            if (text.length != length) return false
            for (index in text.indices) {
                val ok = if (classes[index] == 'L') text[index] in 'A'..'Z' else text[index] in '0'..'9'
                if (!ok) return false
            }
            return true
        }

        /** The text with the layout's separators put back: AA-1234, ABC 123, 123 ABC. */
        fun display(text: String): String {
            val out = StringBuilder(mask.length)
            var index = 0
            mask.forEach { char ->
                if (char == 'L' || char == 'N') out.append(text[index++]) else out.append(char)
            }
            return out.toString()
        }
    }

    /**
     * Latvia: 1-4 letters then 2-4 digits - A-1234, AA-12, ABC-123, ABCD-1234.
     *
     * At least two digits on purpose: a single trailing digit lets a word through. "GOOD" is one
     * ambiguity fix away from "GOO-0", and that is exactly the kind of invention this must not do.
     */
    private val LATVIA_LAYOUTS: List<Layout> = buildList {
        for (letters in 1..4) for (digits in 2..4) add(Layout("L".repeat(letters) + "-" + "N".repeat(digits)))
    }

    /**
     * Lithuania, loosely: the standard car plate is three letters and three digits (ABC 123), but
     * trailers carry two letters, motorcycles put the digits first, and older and special series
     * vary the split. Anything the country issues with letters *and* digits in a plausible order.
     */
    private val LITHUANIA_LAYOUTS: List<Layout> = listOf(
        "LLL NNN", "LL NNN", "NNN LL", "LLL NN", "LL NNNN", "NNNN LL", "LLLL NN",
    ).map(::Layout)

    /**
     * Estonia, loosely: the standard car plate is three digits and three letters (123 ABC);
     * motorcycles and trailers shorten the letter block, older series and transit plates put the
     * letters first.
     */
    private val ESTONIA_LAYOUTS: List<Layout> = listOf(
        "NNN LLL", "NNN LL", "NN LLL", "NNNN LL", "LLL NNN", "LL NNN", "LLL NN",
    ).map(::Layout)

    private fun layoutsOf(region: PlateRegion): List<Layout> = when (region) {
        PlateRegion.LATVIA -> LATVIA_LAYOUTS
        PlateRegion.LITHUANIA -> LITHUANIA_LAYOUTS
        PlateRegion.ESTONIA -> ESTONIA_LAYOUTS
    }

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
        /** What the operator reads: AA-1234, ABC 123, 123 ABC. */
        val display: String,
        /** Which country's layout the text fits, or null for a plate accepted by shape alone. */
        val region: PlateRegion?,
        /** True when the format resolved an ambiguous character. */
        val corrected: Boolean,
    ) {
        val latvian: Boolean get() = region == PlateRegion.LATVIA
    }

    /**
     * Text that has the shape of a plate but is painted on the road furniture instead.
     *
     * Latvian road numbers are a letter and two or three digits — A7, P104, E67 for the Via Baltica
     * — which is exactly the layout of a short plate, and they stand beside every road the phone
     * will ever look at. Distance signs add the same problem with city names in the letter block.
     * No confidence threshold separates these from plates; only knowing what they are does.
     */
    private val ROAD_NUMBER = Regex("^[AEP][0-9]{1,3}$")
    private val PLACE_NAMES = setOf(
        "RIGA", "OGRE", "CESIS", "ADAZI", "SALDUS", "TALSI", "LIMBAZI",
        "VILNIUS", "KAUNAS", "SIAULIAI", "PANEVEZYS", "TALLINN", "TARTU", "NARVA", "PARNU", "VALGA",
    )

    /**
     * Parses an OCR read.
     *
     * The text is first tried against the layouts of the country the engine named, then of the
     * configured [region], then — unless [strict] — of every region the scanner knows: a Latvian
     * car is a Latvian car whether the phone is set to Estonia or not. OCR substitutions (O for 0
     * and the like) are applied only when a country is known — the engine's hint, or the
     * configured region in strict mode — because "correcting" a foreign plate into a local shape
     * invents a car that does not exist. In [strict] mode anything outside the configured region
     * is refused; otherwise a plausible shape passes as a generic plate.
     */
    fun parse(
        raw: String?,
        strict: Boolean = false,
        countryCode: String? = null,
        region: PlateRegion = PlateRegion.LATVIA,
    ): Plate? {
        val cleaned = clean(raw) ?: return null
        if (isSignage(cleaned)) return null

        val hintCode = countryCode?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
        val hint = PlateRegion.fromCode(hintCode)
        // The engine named a country this scanner has no layouts for: never repaired into a local
        // shape. A text that fits the configured layouts *exactly* still passes, strict or not — the
        // country classifier mislabels local plates often enough that it must not veto a shape
        // that is plainly local.
        val foreign = hintCode != null && hint == null

        if (strict) {
            exact(cleaned, region)?.let { return it }
        } else {
            hint?.let { exact(cleaned, it) }?.let { return it }
            exact(cleaned, region)?.let { return it }
            PlateRegion.entries.firstNotNullOfOrNull { exact(cleaned, it) }?.let { return it }
        }

        // A rejected sign must not re-enter through the generic fallback as P1O4 or R1GA12 —
        // whatever region is configured, and whether or not a repair is about to be applied.
        if (PlateRegion.entries.any { candidate -> correct(cleaned, candidate)?.key?.let(::isSignage) == true }) return null

        val correctionRegion = when {
            hint != null && (!strict || hint == region) -> hint
            foreign -> null
            strict -> region
            else -> null
        }
        correctionRegion?.let { correct(cleaned, it) }?.let { return it }
        if (strict) return null
        return generic(cleaned)
    }

    /** Human input normalizes separators/case/look-alikes, never speculative OCR mistakes. */
    fun key(raw: String?): String? {
        val cleaned = clean(raw) ?: return null
        if (isSignage(cleaned)) return null
        return PlateRegion.entries.firstNotNullOfOrNull { exact(cleaned, it) }?.key ?: generic(cleaned)?.key
    }

    /**
     * A fragment typed into a search box, reduced to the key alphabet: case folded, separators
     * dropped, Cyrillic look-alikes mapped to Latin. No format rule applies — "7209" is a valid
     * thing to search for and not a valid plate.
     */
    fun searchKey(raw: String?): String? = clean(raw)

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

    private fun exact(text: String, region: PlateRegion): Plate? {
        val layout = layoutsOf(region).firstOrNull { it.matches(text) } ?: return null
        return Plate(key = text, display = layout.display(text), region = region, corrected = false)
    }

    /**
     * Tries to make the string valid by resolving characters that a layout leaves no choice
     * about. Every layout of the region is considered, and the fix is accepted only when it costs
     * at most [MAX_CORRECTIONS] characters - beyond that it would be inventing a plate, not
     * reading one. Among equally cheap fixes the region's first layout wins.
     */
    private fun correct(text: String, region: PlateRegion): Plate? {
        if (text.length < 3 || text.length > 8) return null
        // Only repair something that already looks like a plate. A word with no digit in it at all
        // is not a misread plate, and "correcting" it would be inventing one.
        if (text.none { it.isDigit() } || text.none { it.isLetter() }) return null
        var best: Plate? = null
        var bestCost = MAX_CORRECTIONS + 1

        for (layout in layoutsOf(region)) {
            if (layout.length != text.length) continue
            var cost = 0
            val fixed = StringBuilder(text.length)
            for (index in text.indices) {
                val char = text[index]
                val wantsLetter = layout.classes[index] == 'L'
                when {
                    wantsLetter && char in 'A'..'Z' -> fixed.append(char)
                    !wantsLetter && char in '0'..'9' -> fixed.append(char)
                    wantsLetter && TO_LETTER.containsKey(char) -> {
                        fixed.append(TO_LETTER.getValue(char)); cost += 1
                    }
                    !wantsLetter && TO_DIGIT.containsKey(char) -> {
                        fixed.append(TO_DIGIT.getValue(char)); cost += 1
                    }
                    else -> cost = MAX_CORRECTIONS + 1
                }
                if (cost > MAX_CORRECTIONS) break
            }
            if (cost > MAX_CORRECTIONS || cost == 0 || cost >= bestCost) continue
            val key = fixed.toString()
            best = Plate(key = key, display = layout.display(key), region = region, corrected = true)
            bestCost = cost
        }
        return best
    }

    /**
     * Anything outside the known layouts still has to look like a plate: a following car may well
     * be foreign, but a road sign is not a vehicle.
     */
    private fun generic(text: String): Plate? {
        if (text.length !in GENERIC_MIN..GENERIC_MAX) return null
        val digits = text.count { it.isDigit() }
        val letters = text.length - digits
        if (letters < 1 || digits < 2) return null
        return Plate(key = text, display = text, region = null, corrected = false)
    }
}
