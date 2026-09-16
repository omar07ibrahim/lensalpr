package com.lensalpr.app.camera

/**
 * Single source of truth for "may this frame be recognized?".
 *
 * Written from the main thread, read from the camera analysis thread on every frame. While a zoom
 * transition is in flight, or while the active lens has not been proven by capture metadata, the
 * pipeline throws frames away instead of attributing a plate to the wrong optics.
 */
class FrameGate(strict: Boolean) {

    /**
     * One lens change: the generation number and the label of the lens it was made for.
     *
     * Published as one immutable object so the analysis thread reads both halves of the same
     * change. Reading them from two separate fields let a frame taken through the old lens be
     * stamped with the new lens's label whenever the switch landed between the two reads.
     */
    class Epoch(val generation: Long, val label: String)

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
    var epoch: Epoch = Epoch(0L, "")
        private set

    val generation: Long get() = epoch.generation

    val isOpen: Boolean
        get() = !transitioning && !paused && (lensUsable || !strict)

    fun beginTransition(generation: Long, label: String = epoch.label) {
        // Close first, then publish the new generation: a reader that sees the new generation must
        // never see an open gate that still belongs to the previous lens.
        transitioning = true
        lensUsable = false
        epoch = Epoch(generation, label)
    }

    fun updateLens(usable: Boolean, settled: Boolean) {
        lensUsable = usable
        if (settled) transitioning = false
    }
}
