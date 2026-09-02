package com.lensalpr.app.pipeline

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test signal is deliberately two-layered: a broad tonal ramp plus fine texture on top of it.
 *
 * That is what a real crop looks like — a car body spanning most of the tonal range, with plate
 * characters as fine detail — and it is the only shape of signal where focus and contrast can be
 * told apart at all. Blur destroys the fine layer and leaves the broad one, which is exactly what
 * the measure has to notice; dimming scales both layers together, which it has to ignore.
 */
class FocusMetricTest {

    private val width = 64
    private val height = 64

    @Test
    fun `a sharp pattern scores far above a blurred one`() {
        val sharp = FocusMetric.sharpness(scene(0, 255, texture = 40), width, height)
        val blurred = FocusMetric.sharpness(scene(0, 255, texture = 4), width, height)
        assertTrue("sharp=$sharp blurred=$blurred", sharp > blurred * 3f)
    }

    @Test
    fun `a flat crop has no detail to speak of`() {
        val flat = FocusMetric.sharpness(scene(128, 128, texture = 0), width, height)
        assertTrue("got $flat", flat < 0.05f)
    }

    /**
     * The point of the whole metric: driving into a tunnel must not look like losing focus. The old
     * plain-gradient measure halved when the contrast halved, which is what made the blur gate throw
     * away perfectly sharp frames in poor light.
     */
    @Test
    fun `halving the contrast barely moves the score`() {
        val bright = FocusMetric.sharpness(scene(0, 255, texture = 40), width, height)
        val dim = FocusMetric.sharpness(scene(64, 192, texture = 20), width, height)
        val drift = abs(bright - dim) / bright
        assertTrue("bright=$bright dim=$dim drift=$drift", drift < 0.2f)

        val plainDrift = abs(plainGradient(scene(0, 255, texture = 40)) - plainGradient(scene(64, 192, texture = 20))) /
            plainGradient(scene(0, 255, texture = 40))
        assertTrue("normalised $drift should beat plain $plainDrift", drift < plainDrift / 2f)
    }

    @Test
    fun `a dim sharp crop outranks a bright blurred one`() {
        // The case that used to be decided the wrong way round: a shaded but focused plate against a
        // sunlit smear.
        val dimSharp = FocusMetric.sharpness(scene(64, 192, texture = 20), width, height)
        val brightBlurred = FocusMetric.sharpness(scene(0, 255, texture = 4), width, height)
        assertTrue("dimSharp=$dimSharp brightBlurred=$brightBlurred", dimSharp > brightBlurred)
    }

    @Test
    fun `a crop too small to judge scores zero instead of guessing`() {
        assertTrue(FocusMetric.sharpness(IntArray(16), 4, 4) == 0f)
    }

    /** The measure this replaced: mean absolute gradient over the same band, scaled by 255. */
    private fun plainGradient(pixels: IntArray): Float {
        var sum = 0L
        var count = 0
        var y = (height * FocusMetric.DEFAULT_TOP).toInt()
        while (y < (height * FocusMetric.DEFAULT_BOTTOM).toInt()) {
            var x = 1
            while (x < width - 1) {
                sum += abs(luma(pixels[y * width + x + 1]) - luma(pixels[y * width + x - 1]))
                count++
                x += 2
            }
            y += 2
        }
        return if (count == 0) 0f else (sum.toFloat() / count) / 255f
    }

    private fun luma(argb: Int) =
        ((argb ushr 16 and 0xFF) * 77 + (argb ushr 8 and 0xFF) * 151 + (argb and 0xFF) * 28) shr 8

    /**
     * A left-to-right ramp from [low] to [high] carrying a two-pixel checker of ±[texture].
     * Lowering [texture] is what blur does; narrowing [low]..[high] is what shade does.
     */
    private fun scene(low: Int, high: Int, texture: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val ramp = low + (high - low) * x / (width - 1)
        val fine = if ((x / 2 + y / 2) % 2 == 0) texture else -texture
        gray(ramp + fine)
    }

    private fun gray(level: Int): Int {
        val value = level.coerceIn(0, 255)
        return (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
}
