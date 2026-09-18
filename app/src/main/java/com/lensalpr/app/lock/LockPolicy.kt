package com.lensalpr.app.lock

/**
 * How many misses the entry code tolerates, and what running out means.
 *
 * Running out locks the phone, it does not erase it. Erasing on a mistyped code turns an ordinary
 * slip — cold hands, a bump in the road — into the permanent loss of a season of evidence, and the
 * owner is far more likely to be the one typing than a thief is. The way back is the bot, which
 * lives in a different process and answers from anywhere with a signal.
 */
object LockPolicy {

    const val MAX_ATTEMPTS = 3

    /** Exactly twelve digits; nothing shorter, nothing with letters in it. */
    const val CODE_LENGTH = 12

    fun isWellFormed(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    /** Attempts left after [failed] misses; never below zero. */
    fun attemptsLeft(failed: Int): Int = (MAX_ATTEMPTS - failed).coerceAtLeast(0)

    /** True once the misses have run out and only the bot can open the phone. */
    fun isLockedOut(failed: Int): Boolean = attemptsLeft(failed) <= 0
}
