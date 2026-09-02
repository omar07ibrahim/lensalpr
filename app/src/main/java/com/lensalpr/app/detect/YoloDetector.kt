package com.lensalpr.app.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.lensalpr.app.settings.Accelerator
import com.lensalpr.app.settings.DetectorModel
import com.lensalpr.app.settings.ScanConfig
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One vehicle box in source-frame pixel coordinates. */
class Detection {
    val box = RectF()
    var score = 0f
    var classId = 0
}

/**
 * YOLO26 vehicle detector on ONNX Runtime.
 *
 * The network input is **rectangular**, matching the 16:9 analysis frame. A square 640x640 input
 * spends 44% of every convolution on the grey letterbox bars above and below a 16:9 image; at
 * 800x448 the padding is under one percent, which buys either 25% more horizontal resolution for
 * the same cost or the same resolution for 60% of it. Same weights, same accuracy per pixel - the
 * only thing that changes is that we stop paying for emptiness.
 *
 * The exported graph is end-to-end (NMS free): the single output is `[1, 300, 6]` holding
 * `x1, y1, x2, y2, score, class` in letterboxed input pixels, already sorted by score.
 *
 * All per-frame buffers are allocated once and reused.
 */
class YoloDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val inputWidth: Int,
    private val inputHeight: Int,
    val backend: String,
) : Closeable {

    private val netBitmap: Bitmap =
        Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
    private val netCanvas = Canvas(netBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(inputWidth * inputHeight)
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(inputWidth * inputHeight * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
    private val pool = ArrayList<Detection>(MAX_OUTPUT_ROWS)
    private val results = ArrayList<Detection>(MAX_OUTPUT_ROWS)

    /** Wall time of the last inference, for the on-screen HUD and the frame scheduler. */
    @Volatile
    var lastInferenceMs: Long = 0
        private set

    /**
     * Runs detection on [frame] and returns boxes in frame pixels.
     *
     * The returned list is owned by the detector and is valid until the next call.
     */
    fun detect(frame: Bitmap, minScore: Float, minBoxPx: Int, classes: IntArray): List<Detection> {
        val started = SystemClock.elapsedRealtimeNanos()
        val frameWidth = frame.width
        val frameHeight = frame.height
        val scale = min(
            inputWidth.toFloat() / frameWidth,
            inputHeight.toFloat() / frameHeight,
        )
        val scaledWidth = (frameWidth * scale).roundToInt()
        val scaledHeight = (frameHeight * scale).roundToInt()
        val padX = (inputWidth - scaledWidth) / 2
        val padY = (inputHeight - scaledHeight) / 2

        if (padX > 0 || padY > 0) netCanvas.drawColor(PAD_COLOR)
        srcRect.set(0, 0, frameWidth, frameHeight)
        dstRect.set(padX, padY, padX + scaledWidth, padY + scaledHeight)
        netCanvas.drawBitmap(frame, srcRect, dstRect, paint)

        netBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        fillTensor()

        results.clear()
        OnnxTensor.createTensor(env, inputBuffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { output ->
                val value = output[0] as OnnxTensor
                decode(
                    buffer = value.floatBuffer,
                    minScore = minScore,
                    minBoxPx = minBoxPx,
                    classes = classes,
                    scale = scale,
                    padX = padX.toFloat(),
                    padY = padY.toFloat(),
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            }
        }
        lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        return results
    }

    private fun fillTensor() {
        val plane = inputWidth * inputHeight
        val buffer = inputBuffer
        buffer.clear()
        val data = pixels
        for (i in 0 until plane) {
            val pixel = data[i]
            buffer.put(i, ((pixel ushr 16) and 0xFF) * INV_255)
            buffer.put(plane + i, ((pixel ushr 8) and 0xFF) * INV_255)
            buffer.put(plane + plane + i, (pixel and 0xFF) * INV_255)
        }
        buffer.rewind()
    }

    private fun decode(
        buffer: FloatBuffer,
        minScore: Float,
        minBoxPx: Int,
        classes: IntArray,
        scale: Float,
        padX: Float,
        padY: Float,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        val rows = min(buffer.capacity() / ROW_STRIDE, MAX_OUTPUT_ROWS)
        for (row in 0 until rows) {
            val offset = row * ROW_STRIDE
            val score = buffer.get(offset + 4)
            if (score < minScore) continue
            val classId = buffer.get(offset + 5).roundToInt()
            if (!classes.contains(classId)) continue

            val left = (buffer.get(offset) - padX) / scale
            val top = (buffer.get(offset + 1) - padY) / scale
            val right = (buffer.get(offset + 2) - padX) / scale
            val bottom = (buffer.get(offset + 3) - padY) / scale

            val clampedLeft = max(0f, left)
            val clampedTop = max(0f, top)
            val clampedRight = min(frameWidth.toFloat(), right)
            val clampedBottom = min(frameHeight.toFloat(), bottom)
            if (clampedRight - clampedLeft < minBoxPx || clampedBottom - clampedTop < minBoxPx) {
                continue
            }

            val detection = obtain(results.size)
            detection.box.set(clampedLeft, clampedTop, clampedRight, clampedBottom)
            detection.score = score
            detection.classId = classId
            results.add(detection)
            if (results.size >= MAX_DETECTIONS) return
        }
    }

    private fun obtain(index: Int): Detection {
        while (pool.size <= index) pool.add(Detection())
        return pool[index]
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { netBitmap.recycle() }
    }

    companion object {
        private const val TAG = "LensALPR.Yolo"
        private const val PAD_COLOR = 0xFF727272.toInt() // 114,114,114 - Ultralytics letterbox grey
        private const val INV_255 = 1f / 255f
        private const val ROW_STRIDE = 6
        private const val MAX_OUTPUT_ROWS = 300
        private const val MAX_DETECTIONS = 48

        /** COCO ids kept by the vehicle filter: car, motorcycle, bus, truck. */
        val VEHICLE_CLASSES = intArrayOf(2, 3, 5, 7)

        fun create(context: Context, config: ScanConfig): YoloDetector {
            val env = OrtEnvironment.getEnvironment()
            val modelFile = extractModel(context, config.model)
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(true)

            var backend = "cpu"
            when (config.accelerator) {
                Accelerator.NNAPI -> {
                    val ok = runCatching { options.addNnapi() }.isSuccess
                    backend = if (ok) "nnapi" else "cpu"
                    options.setIntraOpNumThreads(config.detectorThreads)
                }

                Accelerator.XNNPACK -> {
                    val ok = runCatching {
                        options.addXnnpack(
                            mapOf("intra_op_num_threads" to config.detectorThreads.toString()),
                        )
                    }.isSuccess
                    if (ok) {
                        backend = "xnnpack"
                        // XNNPACK owns its own thread pool; ORT must not spawn a second one.
                        options.setIntraOpNumThreads(1)
                    } else {
                        options.setIntraOpNumThreads(config.detectorThreads)
                    }
                }
            }

            val session = runCatching {
                env.createSession(modelFile.absolutePath, options)
            }.getOrElse { error ->
                // A vendor accelerator can accept the options and then reject the graph; the CPU
                // provider always works, and a slower detector beats no detector.
                Log.w(TAG, "session creation failed on $backend, falling back to CPU", error)
                backend = "cpu"
                val plain = OrtSession.SessionOptions()
                plain.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                plain.setIntraOpNumThreads(config.detectorThreads)
                env.createSession(modelFile.absolutePath, plain)
            }
            val inputName = session.inputNames.first()
            Log.i(
                TAG,
                "Loaded ${config.model.asset} ${config.model.inputWidth}x${config.model.inputHeight} " +
                    "backend=$backend threads=${config.detectorThreads} outputs=${session.outputNames}",
            )
            return YoloDetector(
                env = env,
                session = session,
                inputName = inputName,
                inputWidth = config.model.inputWidth,
                inputHeight = config.model.inputHeight,
                backend = backend,
            )
        }

        /**
         * ONNX Runtime maps the model from disk, so the asset is copied once into app storage
         * instead of being held in the heap as a byte array.
         */
        private fun extractModel(context: Context, model: DetectorModel): File {
            val target = File(context.filesDir, model.asset)
            val expected = runCatching {
                context.assets.openFd(model.asset).use { it.length }
            }.getOrDefault(-1L)
            if (target.exists() && (expected < 0L || target.length() == expected)) return target
            context.assets.open(model.asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            return target
        }
    }
}
