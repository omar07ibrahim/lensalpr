package com.lensalpr.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateRoiTest {

    /** A vehicle crop of 400×400 frame pixels, handed to the engine at native size. */
    private val carCrop = intArrayOf(100, 200, 500, 600)

    @Test
    fun `the anchor is the plate position inside the vehicle box`() {
        val anchor = PlateRoi.anchorOf(
            plate = floatArrayOf(150f, 300f, 250f, 340f),
            bitmapWidth = 400,
            bitmapHeight = 400,
            source = carCrop,
            basis = carCrop,
        )
        assertNotNull(anchor)
        assertEquals(0.375f, anchor!![0], 0.001f)
        assertEquals(0.75f, anchor[1], 0.001f)
        assertEquals(0.625f, anchor[2], 0.001f)
        assertEquals(0.85f, anchor[3], 0.001f)
    }

    @Test
    fun `a downscaled crop maps back to full frame pixels`() {
        // 800 frame pixels handed over as a 400 pixel bitmap: every engine coordinate is worth two.
        val wideCrop = intArrayOf(100, 200, 900, 1000)
        val anchor = PlateRoi.anchorOf(
            plate = floatArrayOf(100f, 200f, 200f, 240f),
            bitmapWidth = 400,
            bitmapHeight = 400,
            source = wideCrop,
            basis = wideCrop,
        )!!
        assertEquals(0.25f, anchor[0], 0.001f)
        assertEquals(0.5f, anchor[2], 0.001f)
    }

    @Test
    fun `a plate found outside the vehicle is not remembered`() {
        val anchor = PlateRoi.anchorOf(
            plate = floatArrayOf(-260f, 300f, -160f, 340f),
            bitmapWidth = 400,
            bitmapHeight = 400,
            source = carCrop,
            basis = carCrop,
        )
        assertNull(anchor)
    }

    @Test
    fun `a narrow read keeps pointing at the same place on the car`() {
        // Second stage: the region cropped was only the plate, but the anchor must come back out in
        // vehicle coordinates so the next frame can project it again.
        val roi = intArrayOf(195, 464, 405, 576)
        val anchor = PlateRoi.anchorOf(
            plate = floatArrayOf(55f, 36f, 155f, 76f),
            bitmapWidth = roi[2] - roi[0],
            bitmapHeight = roi[3] - roi[1],
            source = roi,
            basis = carCrop,
        )!!
        assertEquals(0.375f, anchor[0], 0.01f)
        assertEquals(0.75f, anchor[1], 0.01f)
        assertEquals(0.625f, anchor[2], 0.01f)
        assertEquals(0.85f, anchor[3], 0.01f)
    }

    @Test
    fun `projection pads the plate and stays inside the car`() {
        val anchor = floatArrayOf(0.375f, 0.75f, 0.625f, 0.85f)
        val roi = PlateRoi.project(anchor, carCrop, frameWidth = 3840, frameHeight = 2160)!!
        // Plate is 100x40 at (250,500); padding is 55% of the width and 90% of the height.
        assertEquals(195, roi[0])
        assertEquals(464, roi[1])
        assertEquals(405, roi[2])
        assertEquals(576, roi[3])
        assertTrue(roi[0] >= carCrop[0] - 40 && roi[2] <= carCrop[2] + 40)
    }

    @Test
    fun `the projection follows the car as it grows`() {
        val anchor = floatArrayOf(0.375f, 0.75f, 0.625f, 0.85f)
        val closer = intArrayOf(0, 0, 800, 800)
        val roi = PlateRoi.project(anchor, closer, frameWidth = 3840, frameHeight = 2160)!!
        // Plate is 200x80 at (300,600) now; padding scales with it.
        assertEquals(190, roi[0])
        assertEquals(528, roi[1])
        assertEquals(610, roi[2])
        assertEquals(752, roi[3])
    }

    @Test
    fun `a plate filling the car is not worth a separate crop`() {
        val anchor = floatArrayOf(0.05f, 0.1f, 0.95f, 0.9f)
        assertNull(PlateRoi.project(anchor, carCrop, frameWidth = 3840, frameHeight = 2160))
    }

    @Test
    fun `a region too small to read is refused`() {
        val tiny = intArrayOf(0, 0, 60, 60)
        val anchor = floatArrayOf(0.4f, 0.4f, 0.5f, 0.45f)
        assertNull(PlateRoi.project(anchor, tiny, frameWidth = 3840, frameHeight = 2160))
    }

    @Test
    fun `the projection is clipped to the frame`() {
        val edge = intArrayOf(3600, 1900, 3840, 2140)
        val anchor = floatArrayOf(0.6f, 0.7f, 0.95f, 0.8f)
        val roi = PlateRoi.project(anchor, edge, frameWidth = 3840, frameHeight = 2160)
        if (roi != null) {
            assertTrue(roi[2] <= 3840)
            assertTrue(roi[3] <= 2160)
        }
    }
}
