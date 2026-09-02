package com.lensalpr.app.camera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Lifecycle of the *session*, not of the screen.
 *
 * CameraX unbinds when the lifecycle it was given stops, so binding to the Activity means the
 * camera dies the moment the screen turns off. The session owns its own lifecycle instead: it stays
 * resumed for as long as the operator is scanning, and the foreground service is what makes that
 * legal in the background.
 */
class SessionLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    /** Main thread only. */
    fun start() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun stop() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
