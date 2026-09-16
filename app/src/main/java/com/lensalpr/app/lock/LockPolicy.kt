package com.lensalpr.app.lock

/**
 * How many misses the lock tolerates, and what a miss means.
 *
 * Three attempts, then everything the app collected is erased: the phone lives on a car's rear
 * window, and whoever picks it up must not be able to browse a season of plates and routes.
 */
object LockPolicy {

    const val MAX_ATTEMPTS = 3

    /** Attempts left after [failed] misses; never below zero. */
    fun attemptsLeft(failed: Int): Int = (MAX_ATTEMPTS - failed).coerceAtLeast(0)

    /** True when one more miss on top of [failed] must wipe the phone. */
    fun wipesOnNextMiss(failed: Int): Boolean = attemptsLeft(failed) <= 1
}
