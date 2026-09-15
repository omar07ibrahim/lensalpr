package com.lensalpr.app.alpr

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** Vehicle attributes produced by the classifiers bundled with the ALPR engine. */
data class CarInfo(
    val confidence: Float,
    val make: String?,
    val model: String?,
    val year: String?,
    val makeModelConfidence: Float,
    val color: String?,
    val bodyStyle: String?,
    val box: RectF?,
) {
    val makeModel: String?
        get() = when {
            make != null && model != null -> "$make $model"
            make != null -> make
            model != null -> model
            else -> null
        }

    companion object {
        val EMPTY = CarInfo(0f, null, null, null, 0f, null, null, null)
    }
}

/** One plate read out of one crop. */
data class PlateReading(
    val text: String,
    val rawText: String,
    /** Formatted for humans: AA-1234. */
    val display: String,
    /** The format resolved an ambiguous character (O inside the number block, and so on). */
    val corrected: Boolean,
    val recognitionScore: Float,
    val detectionScore: Float,
    val countryCode: String?,
    val countryName: String?,
    val state: String?,
    val car: CarInfo?,
    val box: RectF?,
)

/** Result of a single engine call. */
data class AlprOutcome(
    val ok: Boolean,
    val code: Int,
    val phrase: String?,
    val plates: List<PlateReading>,
    val cars: List<CarInfo>,
    val latencyMs: Long,
) {
    companion object {
        fun failure(code: Int, phrase: String?, latencyMs: Long) =
            AlprOutcome(false, code, phrase, emptyList(), emptyList(), latencyMs)
    }
}

/**
 * Plate strings only ever compare equal when they are the same physical plate.
 *
 * Separators and case vary between reads of the same plate, so the consensus counter works on the
 * normalized form while the UI keeps the raw text the engine produced.
 */
object PlateText {

    /** Comparison key for a plate typed by a human; delegates to the format rules. */
    fun normalize(raw: String?): String? = PlateFormats.key(raw)
}

/** Parses the engine's JSON payload. Kept free of Android and SDK types so it is unit-testable. */
object AlprJson {

    fun parse(
        json: String?,
        strictLatvia: Boolean = false,
    ): Pair<List<PlateReading>, List<CarInfo>> {
        if (json.isNullOrBlank()) return emptyList<PlateReading>() to emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return emptyList<PlateReading>() to emptyList()

        val plates = ArrayList<PlateReading>()
        root.optJSONArray("plates")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parsePlate(item, strictLatvia)?.let(plates::add)
            }
        }

        val cars = ArrayList<CarInfo>()
        root.optJSONArray("cars")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseCar(item)?.let(cars::add)
            }
        }
        return plates to cars
    }

    private fun parsePlate(item: JSONObject, strictLatvia: Boolean): PlateReading? {
        val raw = item.optString("text").trim()
        val country = item.optJSONArray("country")?.optJSONObject(0)
        val countryCode = country?.optString("code")?.nullIfBlank()
        val plate = PlateFormats.parse(raw, strictLatvia, countryCode) ?: return null
        val box = boxOf(item.optJSONArray("warpedBox"))
        if (!plausiblePlateShape(box?.width() ?: 0f, box?.height() ?: 0f)) return null
        val confidences = item.optJSONArray("confidences")
        return PlateReading(
            text = plate.key,
            rawText = raw,
            display = plate.display,
            corrected = plate.corrected,
            recognitionScore = score(confidences?.optDouble(0, 0.0)),
            detectionScore = score(confidences?.optDouble(1, 0.0)),
            countryCode = countryCode,
            countryName = country?.optString("name")?.nullIfBlank(),
            state = country?.optString("state")?.nullIfBlank(),
            car = item.optJSONObject("car")?.let(::parseCar),
            box = box,
        )
    }

    /**
     * A plate is a long, thin rectangle. Text that is nearly square is a sticker, a badge or a road
     * sign, and the shape rules in [PlateFormats] cannot always tell: "0001" passes them happily.
     *
     * The bounds are wide on purpose. A plate seen from an angle collapses towards square — a 4.7:1
     * plate rotated twenty degrees already measures under 2:1 — and a motorcycle plate is barely
     * wider than it is tall to begin with. This is meant to throw away the obviously wrong shapes,
     * not to police the geometry of a genuine read.
     */
    fun plausiblePlateShape(width: Float, height: Float): Boolean {
        // No box at all, or one too small to measure: the shape rules have nothing to say.
        if (width < 4f || height < 4f) return true
        val aspect = width / height
        return aspect in MIN_PLATE_ASPECT..MAX_PLATE_ASPECT
    }

    private fun parseCar(item: JSONObject): CarInfo? {
        val makeModelYear = item.optJSONArray("makeModelYear")?.optJSONObject(0)
        val color = item.optJSONArray("color")?.optJSONObject(0)
        val bodyStyle = item.optJSONArray("bodyStyle")?.optJSONObject(0)
        val info = CarInfo(
            confidence = score(item.optDouble("confidence", 0.0)),
            make = makeModelYear?.optString("make")?.nullIfBlank(),
            model = makeModelYear?.optString("model")?.nullIfBlank(),
            year = makeModelYear?.opt("year")?.toString()?.nullIfBlank(),
            makeModelConfidence = score(makeModelYear?.optDouble("confidence", 0.0)),
            color = color?.optString("name")?.nullIfBlank(),
            bodyStyle = bodyStyle?.optString("name")?.nullIfBlank(),
            box = boxOf(item.optJSONArray("warpedBox")),
        )
        return if (info == CarInfo.EMPTY) null else info
    }

    /** The engine returns a 4-corner polygon; the app only needs its bounding box. */
    private fun boxOf(array: JSONArray?): RectF? {
        if (array == null || array.length() < 8) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in 0 until 8 step 2) {
            val x = array.optDouble(index, Double.NaN)
            val y = array.optDouble(index + 1, Double.NaN)
            if (x.isNaN() || y.isNaN()) return null
            left = minOf(left, x.toFloat())
            right = maxOf(right, x.toFloat())
            top = minOf(top, y.toFloat())
            bottom = maxOf(bottom, y.toFloat())
        }
        return RectF(left, top, right, bottom)
    }

    private const val MIN_PLATE_ASPECT = 1.15f
    private const val MAX_PLATE_ASPECT = 9f

    private fun score(value: Double?): Float {
        if (value == null || value.isNaN() || value.isInfinite()) return 0f
        return value.coerceIn(0.0, 100.0).toFloat()
    }

    private fun String.nullIfBlank(): String? {
        val trimmed = trim()
        return if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) null else trimmed
    }
}
