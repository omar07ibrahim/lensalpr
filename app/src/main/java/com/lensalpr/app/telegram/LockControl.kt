package com.lensalpr.app.telegram

/**
 * The bot's authority over the entry lock.
 *
 * Kept out of [BotHost] deliberately: that interface is "what the bot needs from the *running
 * scanner*", and every one of its answers is meaningless when the phone is locked. These four are
 * the opposite — they are exactly what still has to work when nothing else does.
 */
interface LockControl {

    /** The phone is showing the white sheet and only [unlock] will move it. */
    fun isLockedOut(): Boolean

    /** Opens the phone. False for a wrong code — and a wrong code here costs no attempt. */
    fun unlock(code: String): Boolean

    /** Locks the phone on purpose, from anywhere. */
    fun lock()

    /** Erases everything the app holds. Irreversible; the caller must have confirmed it. */
    fun wipeEverything(): String
}
