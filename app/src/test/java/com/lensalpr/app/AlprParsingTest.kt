package com.lensalpr.app

import com.lensalpr.app.alpr.AlprJson
import com.lensalpr.app.alpr.PlateText
import com.lensalpr.app.camera.formatZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateTextTest {

    @Test
    fun `separators and case never split one plate into two`() {
        val variants = listOf("a123-bc 77", "A123BC77", " a123 bc.77 ")
        val normalized = variants.map(PlateText::normalize).toSet()
        assertEquals(setOf("A123BC77"), normalized)
    }

    @Test
    fun `noise shorter than a plate is rejected`() {
        assertNull(PlateText.normalize("A1"))
        assertNull(PlateText.normalize("--"))
        assertNull(PlateText.normalize(null))
    }
}

class AlprJsonTest {

    @Test
    fun `plate text, scores and vehicle attributes are extracted`() {
        val json = """
            {
              "frame_id": 12,
              "plates": [
                {
                  "text": "AB 123 CD",
                  "confidences": [92.5, 88.0],
                  "warpedBox": [10,20,110,20,110,60,10,60],
                  "country": [{"code":"LV","name":"Latvia","confidence":71.0}],
                  "car": {
                    "confidence": 96.0,
                    "warpedBox": [0,0,300,0,300,200,0,200],
                    "color": [{"name":"black","confidence":80.0}],
                    "bodyStyle": [{"name":"sedan","confidence":77.0}],
                    "makeModelYear": [
                      {"make":"BMW","model":"5 Series","year":"2019","confidence":84.0}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val (plates, _) = AlprJson.parse(json)
        assertEquals(1, plates.size)
        val plate = plates.first()
        assertEquals("AB123CD", plate.text)
        assertEquals("AB 123 CD", plate.rawText)
        assertEquals(92.5f, plate.recognitionScore, 0.01f)
        assertEquals(88.0f, plate.detectionScore, 0.01f)
        assertEquals("Latvia", plate.countryName)
        assertEquals("BMW 5 Series", plate.car?.makeModel)
        assertEquals("2019", plate.car?.year)
        assertEquals("black", plate.car?.color)
        assertEquals("sedan", plate.car?.bodyStyle)
    }

    @Test
    fun `a plate-less car still reports make and model`() {
        val json = """
            {"cars":[{"confidence":90.0,"makeModelYear":[{"make":"Audi","model":"A6","year":"2021","confidence":66.0}]}]}
        """.trimIndent()
        val (plates, cars) = AlprJson.parse(json)
        assertTrue(plates.isEmpty())
        assertEquals("Audi A6", cars.single().makeModel)
    }

    @Test
    fun `malformed payloads never throw`() {
        assertTrue(AlprJson.parse(null).first.isEmpty())
        assertTrue(AlprJson.parse("not json").first.isEmpty())
        assertTrue(AlprJson.parse("""{"plates":[{"text":""}]}""").first.isEmpty())
    }
}

class ZoomLabelTest {

    @Test
    fun `marketing labels snap only within tolerance`() {
        assertEquals("1×", formatZoom(1.0f))
        assertEquals("3×", formatZoom(2.74f))
        assertEquals("5×", formatZoom(5.06f))
        assertEquals("10×", formatZoom(10f))
        assertEquals("0.6×", formatZoom(0.62f))
        // 4.2x is too far from both 3x and 5x to be relabelled.
        assertEquals("4.2×", formatZoom(4.2f))
    }
}
