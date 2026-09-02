package com.lensalpr.app.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.lensalpr.app.alpr.AlprEngine
import com.lensalpr.app.settings.ScanConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs every engine configuration over one identical crop.
 *
 * Comparing settings by pointing a hand-held phone at a plate twice measures the operator's hands,
 * not the engine: across four live runs the same scene produced 10, 2, 0 and 5 correct reads. Here
 * the pixels are frozen once and replayed through each configuration, so the only variable left is
 * the configuration.
 */
object OcrBenchmark {

    data class Variant(val name: String, val deepSearch: Boolean, val vmmr: Boolean)

    private val VARIANTS = listOf(
        Variant("A deep+vmmr", deepSearch = true, vmmr = true),
        Variant("B vmmr", deepSearch = false, vmmr = true),
        Variant("C deep", deepSearch = true, vmmr = false),
        Variant("D neither", deepSearch = false, vmmr = false),
    )

    /**
     * @param crop the exact pixels the pipeline would have sent to the engine
     * @param repeats measured calls per configuration, after one warm-up call
     */
    fun run(
        context: Context,
        base: ScanConfig,
        crop: Bitmap,
        repeats: Int = 10,
    ): String {
        val width = crop.width
        val height = crop.height
        val stride = crop.rowBytes / 4
        val buffer = ByteBuffer
            .allocateDirect(crop.rowBytes * height)
            .order(ByteOrder.nativeOrder())
        crop.copyPixelsToBuffer(buffer)
        buffer.rewind()

        val report = StringBuilder()
        report.append("bench ${width}x${height}, $repeats прогонов на конфигурацию\n")

        VARIANTS.forEach { variant ->
            val config = base.copy(deepSearch = variant.deepSearch, vmmr = variant.vmmr)
            if (!AlprEngine.reinitializeBlocking(context, config)) {
                report.append("${variant.name}: движок не поднялся\n")
                return@forEach
            }
            // Warm-up: the first call after init pays for lazy model loading.
            AlprEngine.process(buffer, width, height, stride)

            var total = 0L
            var best = 0f
            val texts = LinkedHashMap<String, Int>()
            var withPlate = 0
            repeat(repeats) {
                val started = SystemClock.elapsedRealtime()
                val outcome = AlprEngine.process(buffer, width, height, stride)
                total += SystemClock.elapsedRealtime() - started
                val reading = outcome.plates.maxByOrNull { it.recognitionScore }
                if (reading != null) {
                    withPlate += 1
                    best = maxOf(best, reading.recognitionScore)
                    texts[reading.text] = (texts[reading.text] ?: 0) + 1
                }
            }
            val list = texts.entries.joinToString(", ") { "${it.key}×${it.value}" }
            report.append(
                "${variant.name}: ${total / repeats} мс · номер в $withPlate/$repeats · " +
                    "балл ${best.toInt()} · $list\n",
            )
            Log.i(TAG, report.lines().last { it.isNotBlank() })
        }

        // Leave the engine exactly as the operator configured it.
        AlprEngine.reinitializeBlocking(context, base)
        return report.toString()
    }

    /**
     * Replays a recorded set of crops through every configuration.
     *
     * Every configuration sees byte-identical input, so the differences that come out are the
     * settings and nothing else.
     */
    fun runDataset(context: Context, base: ScanConfig, dir: File): String {
        val files = dir.listFiles { file -> file.name.endsWith(".jpg") }?.sortedBy { it.name }
            ?: return "нет записи"
        if (files.isEmpty()) return "запись пуста"

        val report = StringBuilder("bench по записи: ${files.size} вырезов\n")
        VARIANTS.forEach { variant ->
            val config = base.copy(deepSearch = variant.deepSearch, vmmr = variant.vmmr)
            if (!AlprEngine.reinitializeBlocking(context, config)) {
                report.append("${variant.name}: движок не поднялся\n")
                return@forEach
            }
            var total = 0L
            var withPlate = 0
            val texts = HashMap<String, Int>()
            files.forEach { file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEach
                val stride = bitmap.rowBytes / 4
                val buffer = ByteBuffer
                    .allocateDirect(bitmap.rowBytes * bitmap.height)
                    .order(ByteOrder.nativeOrder())
                bitmap.copyPixelsToBuffer(buffer)
                buffer.rewind()
                val started = SystemClock.elapsedRealtime()
                val outcome = AlprEngine.process(buffer, bitmap.width, bitmap.height, stride)
                total += SystemClock.elapsedRealtime() - started
                outcome.plates.maxByOrNull { it.recognitionScore }?.let { reading ->
                    withPlate += 1
                    texts[reading.text] = (texts[reading.text] ?: 0) + 1
                }
                bitmap.recycle()
            }
            val top = texts.entries.sortedByDescending { it.value }.take(14)
                .joinToString(", ") { "${it.key}×${it.value}" }
            val line = "${variant.name}: ${total / files.size} мс · номер в $withPlate/${files.size} · $top"
            report.append(line).append('\n')
            Log.i(TAG, line)
        }
        AlprEngine.reinitializeBlocking(context, base)
        return report.toString()
    }

    private const val TAG = "LensALPR.Bench"
}
