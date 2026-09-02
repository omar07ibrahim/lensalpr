package com.lensalpr.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatSeekBar
import kotlin.math.abs

/**
 * A slider that does not hijack vertical scrolling.
 *
 * The stock [android.widget.SeekBar] starts dragging on ACTION_DOWN and immediately tells its
 * parent to stop intercepting, so on a settings page every attempt to scroll past a slider changes
 * its value instead. Here the gesture is left undecided until it commits: horizontal movement (or a
 * plain tap) goes to the slider, vertical movement is released to the scroll container.
 */
class ScrollFriendlySeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle,
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var claimed = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = false
                // Consume without starting a drag: the direction of the gesture is not known yet.
                return true
            }

            MotionEvent.ACTION_MOVE -> if (!claimed) {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dy > touchSlop && dy > dx) return false
                if (dx <= touchSlop) return true
                claimed = true
            }
        }
        return super.onTouchEvent(event)
    }
}
