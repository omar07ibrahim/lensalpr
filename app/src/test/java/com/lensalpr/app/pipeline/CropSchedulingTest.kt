package com.lensalpr.app.pipeline

import com.lensalpr.app.detect.VehicleTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropSchedulingTest {

    @Test
    fun `a distant car cannot carry a readable plate`() {
        // 100 px of car is roughly 25 px of plate: nothing can read that.
        assertTrue(FrameProcessor.estimatedPlatePixels(100f) < FrameProcessor.MIN_PLATE_PX)
        assertTrue(FrameProcessor.estimatedPlatePixels(200f) > FrameProcessor.MIN_PLATE_PX)
    }

    @Test
    fun `the crop size cap is taken into account`() {
        // A car filling a 4K frame is scaled down before the engine sees it, so its plate does not
        // grow without limit — but it stays far above the readability floor.
        val huge = FrameProcessor.estimatedPlatePixels(3000f)
        val large = FrameProcessor.estimatedPlatePixels(1500f)
        assertTrue("huge=$huge large=$large", huge > FrameProcessor.MIN_PLATE_PX * 5)
        assertTrue("scaling must flatten the curve", huge < large * 1.5f)
    }

    @Test
    fun `an empty box is not readable`() {
        assertTrue(FrameProcessor.estimatedPlatePixels(0f) == 0f)
    }

    @Test
    fun `a car holding station behind us is never called smeared`() {
        // The vehicle that matters barely moves in the frame; it must always be worth a crop.
        assertFalse(FrameProcessor.smearedByMotion(2f, 1f, 400f))
        assertFalse(FrameProcessor.smearedByMotion(0f, 0f, 120f))
    }

    @Test
    fun `traffic crossing the frame is expected to be smeared`() {
        assertTrue(FrameProcessor.smearedByMotion(90f, 0f, 400f))
        assertTrue(FrameProcessor.smearedByMotion(0f, 70f, 300f))
    }

    @Test
    fun `the limit scales with the size of the vehicle`() {
        // The same absolute movement is harmless for a close car and fatal for a distant one.
        assertFalse(FrameProcessor.smearedByMotion(30f, 0f, 600f))
        assertTrue(FrameProcessor.smearedByMotion(30f, 0f, 150f))
    }

    @Test
    fun `a degenerate box cannot be judged`() {
        assertFalse(FrameProcessor.smearedByMotion(50f, 50f, 0f))
    }

    @Test
    fun `a car keeps its identity while it approaches`() {
        // Typical frame-to-frame growth of a car closing in: well within tolerance.
        assertTrue(VehicleTracker.plausibleMatch(200f, 150f, 215f, 161f))
        assertTrue(VehicleTracker.plausibleMatch(200f, 150f, 190f, 142f))
    }

    @Test
    fun `a box that suddenly doubles belongs to something else`() {
        assertFalse(VehicleTracker.plausibleMatch(200f, 150f, 500f, 380f))
        assertFalse(VehicleTracker.plausibleMatch(500f, 380f, 200f, 150f))
    }

    @Test
    fun `a box that changes shape is a different object`() {
        // Same area, but a car does not turn into a lorry between two frames.
        assertFalse(VehicleTracker.plausibleMatch(200f, 150f, 300f, 100f))
    }

    @Test
    fun `degenerate boxes never match`() {
        assertFalse(VehicleTracker.plausibleMatch(0f, 150f, 200f, 150f))
        assertFalse(VehicleTracker.plausibleMatch(200f, 150f, 200f, 0f))
    }
}
