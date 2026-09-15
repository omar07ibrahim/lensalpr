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
    private var pausedAt = 0L

    val current: PlanEntry?
        get() = entries.getOrNull(index)

    val planSize: Int
        get() = entries.size

    fun configure(entries: List<PlanEntry>) {
        stop()
        this.entries = entries.toList()
        index = 0
        holding = false
        remainingWhenPaused = 0L
    }

    fun start() {
        if (running || entries.isEmpty()) return
        running = true
        index = 0
        switchTo(index)
        handler.removeCallbacks(ticker)
        if (running) handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        val now = SystemClock.elapsedRealtime()
        if (value) {
            pausedAt = now
            remainingWhenPaused = if (holding) (holdUntil - now).coerceAtLeast(0L)
                else current?.dwellMs ?: 0L
        } else {
            // Paused time consumes neither the dwell nor the pending settle timeout.
            if (holding) holdUntil = now + remainingWhenPaused
            else switchedAt += (now - pausedAt).coerceAtLeast(0L)
            remainingWhenPaused = 0L
        }
        paused = value
    }

    /** Called by the camera controller once the requested step is live. */
    fun onStepSettled(step: ZoomStep) {
        val entry = current ?: return
        if (!running || entry.step.id != step.id || holding) return
        beginHold(entry, SystemClock.elapsedRealtime())
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
        if (paused) pausedAt = switchedAt
        holdUntil = 0L
        remainingWhenPaused = entry.dwellMs
        onSwitch(entry)
    }

    private fun beginHold(entry: PlanEntry, now: Long) {
        holding = true
        holdUntil = now + entry.dwellMs
        if (paused) remainingWhenPaused = entry.dwellMs
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val before = current
            if (before != null && !paused) {
                if (holding && entries.size > 1 && now >= holdUntil) {
                    advance()
                } else if (!holding && now - switchedAt >= SETTLE_TIMEOUT_MS) {
                    beginHold(before, now)
                }
            }
            // advance() can change the entry; callbacks may also stop the scheduler.
            val entry = current
            if (!running) return
            if (entry != null) {
                val remaining = when {
                    !holding -> entry.dwellMs
                    paused -> remainingWhenPaused
                    entries.size <= 1 -> 0L
                    else -> (holdUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
                onTick(RotationTick(entry, holding, remaining, entries.size))
            }
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 100L
        const val SETTLE_TIMEOUT_MS = 4_000L
    }
}
