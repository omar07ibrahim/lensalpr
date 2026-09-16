package com.lensalpr.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lensalpr.app.pipeline.FrameSnapshot
import kotlin.math.min

/**
 * Draws tracker boxes on top of the preview.
 *
 * Preview and ImageAnalysis are bound with a shared ViewPort and the preview is laid out with
 * FIT_CENTER, so analysis pixels map onto the view with a single uniform scale plus letterbox
 * offsets - no per-frame matrix from CameraX required.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f
        isFakeBoldText = true
    }
    private val scratch = RectF()
    private val labelRect = RectF()

    private var snapshot: FrameSnapshot? = null

    fun update(snapshot: FrameSnapshot?) {
        this.snapshot = snapshot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = snapshot ?: return
        if (current.imageWidth <= 0 || current.imageHeight <= 0) return

        val scale = min(
            width.toFloat() / current.imageWidth,
            height.toFloat() / current.imageHeight,
        )
        val offsetX = (width - current.imageWidth * scale) / 2f
        val offsetY = (height - current.imageHeight * scale) / 2f

        for (box in current.boxes) {
            scratch.set(
                box.rect.left * scale + offsetX,
                box.rect.top * scale + offsetY,
                box.rect.right * scale + offsetX,
                box.rect.bottom * scale + offsetY,
            )
            val color = when {
                box.confirmed -> COLOR_CONFIRMED
                box.pendingCount > 0 -> COLOR_PENDING
                else -> COLOR_TRACKING
            }
            boxPaint.color = color
            canvas.drawRoundRect(scratch, 10f, 10f, boxPaint)

            val text = box.label?.let { label ->
                if (box.confirmed || box.pendingCount == 0) label else "$label ${box.pendingCount}×"
            } ?: "#${box.id}"
            val textWidth = labelPaint.measureText(text)
            val labelWidth = textWidth + LABEL_PADDING * 2
            // Kept inside the view: a car at the right edge used to carry a label that started at
            // the edge and ran off it, so exactly the plate the operator wanted was unreadable.
            val left = scratch.left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            labelRect.set(
                left,
                scratch.top - LABEL_HEIGHT,
                left + labelWidth,
                scratch.top,
            )
            if (labelRect.top < 0f) labelRect.offset(0f, LABEL_HEIGHT)
            labelBackground.color = color
            canvas.drawRoundRect(labelRect, 6f, 6f, labelBackground)
            canvas.drawText(
                text,
                labelRect.left + LABEL_PADDING,
                labelRect.bottom - LABEL_BASELINE,
                labelPaint,
            )
        }
    }

    private companion object {
        const val COLOR_CONFIRMED = 0xFF35D07F.toInt()
        const val COLOR_PENDING = 0xFFF5A524.toInt()
        const val COLOR_TRACKING = 0xFF8FA0B4.toInt()
        const val LABEL_HEIGHT = 38f
        const val LABEL_PADDING = 10f
        const val LABEL_BASELINE = 9f
    }
}
