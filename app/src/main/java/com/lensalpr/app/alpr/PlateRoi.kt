package com.lensalpr.app.alpr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Remembers where the plate was inside a vehicle crop so the next frame can be cut around the plate
 * itself instead of the whole car.
 *
 * The first read has to look at the whole vehicle — that is how the plate is found at all, and it is
 * what the make/model classifiers need. Every read after that is different: the plate has already
 * been located, and sending the car again wastes the two things that decide whether a plate is
 * readable.
 *
 * *Resolution.* A vehicle crop is capped at 1280 px before it reaches the engine, so a car filling a
 * 4K frame arrives scaled to roughly 0.6× and its plate loses a third of the pixels it had. A crop
 * that only contains the plate stays under the cap at native resolution, so those pixels survive.
 *
 * *Attention.* A tightly framed plate is a much easier target for the engine's own detector than a
 * plate occupying two percent of a picture of a car.
 *
 * The anchor is stored relative to the vehicle box rather than to the frame, so it keeps pointing at
 * the plate while the car moves, approaches and shrinks. It is deliberately short-lived: when a read
 * from the narrow crop comes back empty the anchor is dropped and the next crop is the whole car
 * again, so a drifted anchor costs one frame and repairs itself.
 */
object PlateRoi {

    /** Padding around the plate, in multiples of its own size. Wide, because the engine still has to *find* it. */
    private const val PAD_X = 0.55f
    private const val PAD_Y = 0.9f

    /** Below this a narrow crop stops being worth a separate engine call. */
    private const val MIN_SIDE_PX = 48

    /** If the plate region is most of the car anyway, the plain vehicle crop is just as good. */
    private const val MAX_AREA_SHARE = 0.55f

    /** How far outside the vehicle box the crop may reach; enough for a tow bar, not the next car. */
    private const val BASIS_SLACK = 0.10f

    /**
     * Converts the engine's plate box into a position relative to the vehicle crop.
     *
     * @param plate box in the pixels of the image the engine was given (left, top, right, bottom).
     * @param bitmapWidth size of that image.
     * @param source region of the frame that image was cut from (left, top, right, bottom).
     * @param basis the vehicle crop the anchor is expressed in — for a whole-car read this is the
     *   same as [source], for a narrow re-read it is the vehicle box that produced the ROI.
     * @return normalised (left, top, right, bottom) inside [basis], or null when the box is unusable.
     */
    fun anchorOf(
        plate: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        source: IntArray,
        basis: IntArray,
    ): FloatArray? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val sourceWidth = (source[2] - source[0]).toFloat()
        val sourceHeight = (source[3] - source[1]).toFloat()
        val basisWidth = (basis[2] - basis[0]).toFloat()
        val basisHeight = (basis[3] - basis[1]).toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f || basisWidth <= 0f || basisHeight <= 0f) return null

        val scaleX = sourceWidth / bitmapWidth
        val scaleY = sourceHeight / bitmapHeight
        val left = source[0] + plate[0] * scaleX
        val top = source[1] + plate[1] * scaleY
        val right = source[0] + plate[2] * scaleX
        val bottom = source[1] + plate[3] * scaleY
        if (right - left < 4f || bottom - top < 2f) return null

        val anchor = floatArrayOf(
            (left - basis[0]) / basisWidth,
            (top - basis[1]) / basisHeight,
            (right - basis[0]) / basisWidth,
            (bottom - basis[1]) / basisHeight,
        )
        // A plate that maps far outside the vehicle box means the tracker and the engine were
        // looking at different cars; such an anchor is worse than none.
        if (anchor.any { it.isNaN() || it < -0.25f || it > 1.25f }) return null
        if (anchor[2] <= anchor[0] || anchor[3] <= anchor[1]) return null
        return anchor
    }

    /**
     * Projects a stored anchor onto the current vehicle crop and pads it into a workable region.
     *
     * @return (left, top, right, bottom) in frame pixels, or null when a narrow crop would not help.
     */
    fun project(
        anchor: FloatArray,
        basis: IntArray,
        frameWidth: Int,
        frameHeight: Int,
    ): IntArray? {
        val basisWidth = (basis[2] - basis[0]).toFloat()
        val basisHeight = (basis[3] - basis[1]).toFloat()
        if (basisWidth <= 0f || basisHeight <= 0f) return null

        val plateLeft = basis[0] + anchor[0] * basisWidth
        val plateTop = basis[1] + anchor[1] * basisHeight
        val plateRight = basis[0] + anchor[2] * basisWidth
        val plateBottom = basis[1] + anchor[3] * basisHeight
        val plateWidth = plateRight - plateLeft
        val plateHeight = plateBottom - plateTop
        if (plateWidth <= 1f || plateHeight <= 1f) return null

        val slackX = basisWidth * BASIS_SLACK
        val slackY = basisHeight * BASIS_SLACK
        val limitLeft = max(0f, basis[0] - slackX)
        val limitTop = max(0f, basis[1] - slackY)
        val limitRight = min(frameWidth.toFloat(), basis[2] + slackX)
        val limitBottom = min(frameHeight.toFloat(), basis[3] + slackY)

        val left = max(limitLeft, plateLeft - plateWidth * PAD_X).roundToInt()
        val top = max(limitTop, plateTop - plateHeight * PAD_Y).roundToInt()
        val right = min(limitRight, plateRight + plateWidth * PAD_X).roundToInt()
        val bottom = min(limitBottom, plateBottom + plateHeight * PAD_Y).roundToInt()

        val width = right - left
        val height = bottom - top
        if (width < MIN_SIDE_PX || height < MIN_SIDE_PX) return null
        val share = width.toFloat() * height / (basisWidth * basisHeight)
        if (share > MAX_AREA_SHARE) return null
        return intArrayOf(left, top, right, bottom)
    }
}
