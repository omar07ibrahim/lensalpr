package com.lensalpr.app.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lensalpr.app.settings.PlannedStep

data class PlanEntry(
    val index: Int,
    val planned: PlannedStep,
    val step: ZoomStep,
) {
    val dwellMs: Long get() = planned.dwellSeconds * 1_000L
}

data class RotationTick(
    val entry: PlanEntry,
    val holding: Boolean,
    val remainingMs: Long,
    val planSize: Int,
)

/**
 * Drives the configured rotation: hold a lens for its dwell time, then move to the next one.
 *
 * The dwell only starts once the camera reports the step as settled, so a slow HAL switch never
 * eats the time budget that was meant for recognition. If the step never settles the scheduler
 * still advances after [SETTLE_TIMEOUT_MS] instead of getting stuck on one lens.
 */
class LensRotationScheduler(
    private val onSwitch: (PlanEntry) -> Unit,
    private val onTick: (RotationTick) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var entries: List<PlanEntry> = emptyList()
    private var index = 0
    private var holding = false
    private var holdUntil = 0L
    private var switchedAt = 0L
    private var running = false
    private var paused = false
    private var remainingWhenPaused = 0L

    val current: PlanEntry?
        get() = entries.getOrNull(index)

    val planSize: Int
        get() = entries.size

    fun configure(entries: List<PlanEntry>) {
        this.entries = entries
        index = 0
    }

    fun start() {
        if (entries.isEmpty()) return
        running = true
        paused = false
        index = 0
        switchTo(index)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        val now = SystemClock.elapsedRealtime()
        if (value) {
            if (holding) remainingWhenPaused = (holdUntil - now).coerceAtLeast(0L)
        } else {
            // Resume with the slice that was left, not with an instant switch.
            if (holding) holdUntil = now + remainingWhenPaused
            remainingWhenPaused = 0L
        }
        paused = value
    }

    /** Called by the camera controller once the requested step is live. */
    fun onStepSettled(step: ZoomStep) {
        val entry = current ?: return
        if (entry.step.id != step.id || holding) return
        holding = true
        holdUntil = SystemClock.elapsedRealtime() + entry.dwellMs
    }

    /** Skips the rest of the current dwell. */
    fun advance() {
        if (!running || entries.isEmpty()) return
        index = (index + 1) % entries.size
        switchTo(index)
    }

    private fun switchTo(target: Int) {
        val entry = entries.getOrNull(target) ?: return
        holding = false
        switchedAt = SystemClock.elapsedRealtime()
        holdUntil = 0L
        onSwitch(entry)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val entry = current
            if (entry != null) {
                val now = SystemClock.elapsedRealtime()
                if (holding && !paused && entries.size > 1 && now >= holdUntil) {
                    advance()
                } else if (!holding && now - switchedAt > SETTLE_TIMEOUT_MS) {
                    // The lens never confirmed; keep the plan moving.
                    holding = true
                    holdUntil = now + entry.dwellMs
                }
                val remaining = when {
                    !holding -> entry.dwellMs
                    paused -> remainingWhenPaused
                    entries.size <= 1 -> 0L
                    else -> (holdUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
                onTick(
                    RotationTick(
                        entry = entry,
                        holding = holding,
                        remainingMs = remaining,
                        planSize = entries.size,
                    ),
                )
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 100L
        const val SETTLE_TIMEOUT_MS = 4_000L
    }
}
