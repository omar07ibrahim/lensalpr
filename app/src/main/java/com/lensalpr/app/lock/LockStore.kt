package com.lensalpr.app.lock

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.edit

/**
 * The entry password and the count of misses against it, in a preferences file of their own.
 *
 * Separate from the settings on purpose: the settings screen has a "reset" button that clears the
 * default preferences, and a reset must not also remove the lock.
 */
object LockStore {

    private const val PREFS = "lensalpr_lock"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_FAILED = "failed"
    private const val KEY_AUTO_UNLOCK_UNTIL = "auto_unlock_until"

    /**
     * Whether this process has been through the lock screen. A fresh process starts locked; a
     * scanner restarted by its own alarm passes through [consumeAutoUnlock] instead.
     */
    @Volatile
    var unlocked: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    fun setPassword(context: Context, password: String) {
        val record = LockCrypto.create(password)
        prefs(context).edit {
            putString(KEY_SALT, Base64.encodeToString(record.salt, Base64.NO_WRAP))
            putString(KEY_HASH, Base64.encodeToString(record.hash, Base64.NO_WRAP))
            putInt(KEY_ITERATIONS, record.iterations)
            putInt(KEY_FAILED, 0)
        }
    }

    /** True for the right password; also resets the miss counter. */
    fun verify(context: Context, password: String): Boolean {
        val store = prefs(context)
        val salt = store.getString(KEY_SALT, null) ?: return false
        val hash = store.getString(KEY_HASH, null) ?: return false
        val record = runCatching {
            LockCrypto.Record(
                Base64.decode(salt, Base64.NO_WRAP),
                Base64.decode(hash, Base64.NO_WRAP),
                store.getInt(KEY_ITERATIONS, LockCrypto.ITERATIONS),
            )
        }.getOrNull() ?: return false
        val ok = LockCrypto.matches(record, password)
        if (ok) store.edit { putInt(KEY_FAILED, 0) }
        return ok
    }

    fun failedAttempts(context: Context): Int = prefs(context).getInt(KEY_FAILED, 0)

    fun attemptsLeft(context: Context): Int = LockPolicy.attemptsLeft(failedAttempts(context))

    /**
     * Records a miss and returns how many attempts remain. Written before the caller decides
     * anything, so killing the app between two misses does not give the attempts back.
     */
    fun noteFailure(context: Context): Int {
        val failed = failedAttempts(context) + 1
        prefs(context).edit(commit = true) { putInt(KEY_FAILED, failed) }
        return LockPolicy.attemptsLeft(failed)
    }

    /** Removes the password and the counter; the next launch asks for a new one. */
    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }

    /**
     * Lets the next launch within [windowMs] skip the lock screen.
     *
     * Only the scanner's own restart alarm arms this: nobody is at the phone when it fires, and a
     * lock screen there would end the session for good. The token is single-use.
     */
    fun armAutoUnlock(context: Context, windowMs: Long) {
        prefs(context).edit(commit = true) {
            putLong(KEY_AUTO_UNLOCK_UNTIL, SystemClock.elapsedRealtime() + windowMs)
        }
    }

    /** Cancels a token whose restart was called off; the next launch asks for the password again. */
    fun disarmAutoUnlock(context: Context) {
        prefs(context).edit(commit = true) { remove(KEY_AUTO_UNLOCK_UNTIL) }
    }

    fun consumeAutoUnlock(context: Context): Boolean {
        val store = prefs(context)
        val until = store.getLong(KEY_AUTO_UNLOCK_UNTIL, 0L)
        if (until == 0L) return false
        store.edit(commit = true) { remove(KEY_AUTO_UNLOCK_UNTIL) }
        // A reboot resets the uptime clock, so a stale token from before it reads as the future;
        // the window is short enough that only a genuine restart lands inside it.
        val now = SystemClock.elapsedRealtime()
        val ok = now <= until && until - now <= MAX_AUTO_UNLOCK_MS
        if (ok) unlocked = true
        return ok
    }

    private const val MAX_AUTO_UNLOCK_MS = 30L * 60_000L
}
