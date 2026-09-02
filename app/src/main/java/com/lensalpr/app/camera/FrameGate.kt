package com.lensalpr.app.camera

/**
 * Single source of truth for "may this frame be recognized?".
 *
 * Written from the main thread, read from the camera analysis thread on every frame. While a zoom
 * transition is in flight, or while the active lens has not been proven by capture metadata, the
 * pipeline throws frames away instead of attributing a plate to the wrong optics.
 */
class FrameGate(strict: Boolean) {

    @Volatile
    var strict: Boolean = strict

    @Volatile
    var transitioning: Boolean = true
        private set

    @Volatile
    var lensUsable: Boolean = false
        private set

    @Volatile
    var paused: Boolean = false

    /** Bumped on every lens change so in-flight work from the previous optics can be discarded. */
    @Volatile
    var generation: Long = 0L
        private set

    val isOpen: Boolean
        get() = !transitioning && !paused && (lensUsable || !strict)

    fun beginTransition(generation: Long) {
        this.generation = generation
        transitioning = true
        lensUsable = false
    }

    fun updateLens(usable: Boolean, settled: Boolean) {
        lensUsable = usable
        if (settled) transitioning = false
    }
}
