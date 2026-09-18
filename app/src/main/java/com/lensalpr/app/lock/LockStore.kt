package com.lensalpr.app.lock

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.edit

/**
 * The entry code, the misses against it, and whether the phone is currently locked out.
 *
 * In a preferences file of its own, away from the settings: the setup screen can reset the
 * settings, and a reset must never be a way around the lock.
 *
 * Read from three places that do not share a thread — the lock screen, the scanner, and the bot's
 * service — so every write is committed rather than applied. Losing a miss to a killed process is
 * exactly the hole someone with the phone in their hands would use.
 */
object LockStore {

    private const val PREFS = "lensalpr_lock"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_FAILED = "failed"
    private const val KEY_LOCKED_AT = "locked_at"
    private const val KEY_AUTO_UNLOCK_UNTIL = "auto_unlock_until"

    /**
     * Whether this process has been past the lock screen.
     *
     * Deliberately not persisted: a fresh process starts locked. The one exception is the
     * scanner restarting itself, which passes through [consumeAutoUnlock] because nobody is
     * holding the phone when that happens.
     */
    @Volatile
    var unlocked: Boolean = false
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCode(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    fun setCode(context: Context, code: String) {
        val record = LockCrypto.create(code)
        prefs(context).edit(commit = true) {
            putString(KEY_SALT, Base64.encodeToString(record.salt, Base64.NO_WRAP))
            putString(KEY_HASH, Base64.encodeToString(record.hash, Base64.NO_WRAP))
            putInt(KEY_ITERATIONS, record.iterations)
            putInt(KEY_FAILED, 0)
            remove(KEY_LOCKED_AT)
        }
        unlocked = true
    }

    /**
     * Checks a code without touching the counters.
     *
     * The bot needs this: an unlock attempt over Telegram must be able to fail without spending
     * one of the three attempts that belong to whoever is holding the phone.
     */
    fun checkCode(context: Context, code: String): Boolean {
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
        return LockCrypto.matches(record, code)
    }

    /** The code typed on the phone: on success clears the misses and opens the process. */
    fun verifyOnDevice(context: Context, code: String): Boolean {
        if (!checkCode(context, code)) return false
        prefs(context).edit(commit = true) {
            putInt(KEY_FAILED, 0)
            remove(KEY_LOCKED_AT)
        }
        unlocked = true
        return true
    }

    fun failedAttempts(context: Context): Int = prefs(context).getInt(KEY_FAILED, 0)

    fun attemptsLeft(context: Context): Int = LockPolicy.attemptsLeft(failedAttempts(context))

    /** True once the attempts are spent: the white screen, until the bot says otherwise. */
    fun isLockedOut(context: Context): Boolean = LockPolicy.isLockedOut(failedAttempts(context))

    fun lockedAtMs(context: Context): Long = prefs(context).getLong(KEY_LOCKED_AT, 0L)

    /**
     * Records a miss and returns how many attempts remain.
     *
     * Committed before the caller decides anything, so killing the app between two misses does not
     * hand the attempts back.
     */
    fun noteFailure(context: Context): Int {
        val failed = failedAttempts(context) + 1
        val left = LockPolicy.attemptsLeft(failed)
        prefs(context).edit(commit = true) {
            putInt(KEY_FAILED, failed)
            if (left <= 0) putLong(KEY_LOCKED_AT, System.currentTimeMillis())
        }
        return left
    }

    /**
     * Opens the phone from the bot: clears the lockout and lets the lock screen through.
     *
     * The only way back from the white screen. Nothing on the phone itself can do this, which is
     * the point — whoever is holding it cannot wait out or retry their way in.
     */
    fun unlockRemotely(context: Context) {
        prefs(context).edit(commit = true) {
            putInt(KEY_FAILED, 0)
            remove(KEY_LOCKED_AT)
        }
        unlocked = true
    }

    /** Locks the phone from the bot, on purpose: the scanner stops and the screen goes white. */
    fun lockRemotely(context: Context) {
        prefs(context).edit(commit = true) {
            putInt(KEY_FAILED, LockPolicy.MAX_ATTEMPTS)
            putLong(KEY_LOCKED_AT, System.currentTimeMillis())
        }
        unlocked = false
    }

    /** Shuts this process out again without touching the counters — used when the app is left. */
    fun relock() {
        unlocked = false
    }

    /** Removes the code and every counter; the next launch asks for a new one. */
    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        unlocked = false
    }

    /**
     * Lets the next launch inside [windowMs] skip the lock screen.
     *
     * Armed only by the scanner's own restart, where there is nobody to type anything and a lock
     * screen would end the drive. Single use, and short: a token found later is refused.
     */
    fun armAutoUnlock(context: Context, windowMs: Long) {
        prefs(context).edit(commit = true) {
            putLong(KEY_AUTO_UNLOCK_UNTIL, SystemClock.elapsedRealtime() + windowMs)
        }
    }

    fun disarmAutoUnlock(context: Context) {
        prefs(context).edit(commit = true) { remove(KEY_AUTO_UNLOCK_UNTIL) }
    }

    fun consumeAutoUnlock(context: Context): Boolean {
        val store = prefs(context)
        val until = store.getLong(KEY_AUTO_UNLOCK_UNTIL, 0L)
        if (until == 0L) return false
        store.edit(commit = true) { remove(KEY_AUTO_UNLOCK_UNTIL) }
        // A locked-out phone is never opened by a restart token; only the bot lifts that.
        if (isLockedOut(context)) return false
        // A reboot resets the uptime clock, so a token from before it reads as the far future.
        val now = SystemClock.elapsedRealtime()
        val ok = now <= until && until - now <= MAX_AUTO_UNLOCK_MS
        if (ok) unlocked = true
        return ok
    }

    private const val MAX_AUTO_UNLOCK_MS = 30L * 60_000L
}
