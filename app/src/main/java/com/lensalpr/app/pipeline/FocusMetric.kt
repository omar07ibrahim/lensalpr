package com.lensalpr.app.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much detail a crop actually carries, independent of how bright it is.
 *
 * The plain "average edge strength" measure that this replaces confused darkness with blur: driving
 * into a tunnel, under trees or at dusk drops every gradient in the frame, so the blur gate would
 * start rejecting perfectly focused crops for the rest of the vehicle's life — exactly when reading
 * the plate is hardest and every frame counts.
 *
 * Dividing the gradient energy by the contrast of the same region cancels that out: halving the
 * contrast halves both terms and leaves the score where it was. What remains is the thing that
 * matters, how sharp the edges are relative to the tonal range they live in.
 *
 * The band is biased low in the crop because a rear plate sits below the middle of the car.
 */
object FocusMetric {

    const val DEFAULT_TOP = 0.35f
    const val DEFAULT_BOTTOM = 0.95f

    /** Keeps a flat, noiseless region from dividing by nothing and scoring as razor sharp. */
    private const val CONTRAST_FLOOR = 6f

    /**
     * @param pixels ARGB_8888 pixels, row-major, [width] per row.
     * @return roughly 0 for a flat or badly blurred crop, 0.3-1.5 for a sharp one. Only the ratio
     *   between crops of the same vehicle is meaningful, never the absolute value.
     */
    fun sharpness(
        pixels: IntArray,
        width: Int,
        height: Int,
        top: Float = DEFAULT_TOP,
        bottom: Float = DEFAULT_BOTTOM,
    ): Float {
        if (width < 8 || height < 8) return 0f
        val first = (height * top).toInt().coerceIn(0, height - 2)
        val last = (height * bottom).toInt().coerceIn(first + 1, height)

        var gradientSum = 0.0
        var gradientCount = 0
        var luminanceSum = 0.0
        var luminanceSquares = 0.0
        var luminanceCount = 0

        var y = first
        while (y < last) {
            val base = y * width
            var x = 1
            while (x < width - 1) {
                // Contrast is measured over every pixel of the row, not only the sampled ones: a
                // pattern that happens to align with the stride would otherwise look contrastless
                // and its gradients would divide by almost nothing.
                val here = luma(pixels[base + x])
                val next = luma(pixels[base + x + 1])
                luminanceSum += here + next
                luminanceSquares += here.toDouble() * here + next.toDouble() * next
                luminanceCount += 2
                gradientSum += abs(next - luma(pixels[base + x - 1]))
                gradientCount += 1
                x += 2
            }
            y += 2
        }

        if (gradientCount == 0 || luminanceCount == 0) return 0f
        val mean = luminanceSum / luminanceCount
        val variance = (luminanceSquares / luminanceCount) - mean * mean
        val contrast = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        return (gradientSum / gradientCount).toFloat() / (contrast + CONTRAST_FLOOR)
    }

    /** Rec. 601 luma in integer arithmetic; the constants sum to 256. */
    private fun luma(argb: Int): Int =
        ((argb ushr 16 and 0xFF) * 77 + (argb ushr 8 and 0xFF) * 151 + (argb and 0xFF) * 28) shr 8
}
