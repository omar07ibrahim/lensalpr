package com.lensalpr.app.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Settings that can change while the scanner is running, including from Telegram.
 *
 * [ScanConfig] is a snapshot taken when the camera opens - it decides how the session is built and
 * must not move under the pipeline's feet. These values are different: they are thresholds the
 * operator tunes from the car, so they live in one mutable place that both the app and the bot
 * read and write.
 */
class RuntimeSettings(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Continuous contact after which a vehicle counts as following us. */
    @Volatile
    var tailSeconds: Int = prefs.getInt(KEY_TAIL, DEFAULT_TAIL)
        private set

    /** How long the target must be out of frame before the recording stops. */
    @Volatile
    var videoAbsentSeconds: Int = prefs.getInt(KEY_ABSENT, DEFAULT_ABSENT)
        private set

    /** Record video once a vehicle qualifies as a follower. */
    @Volatile
    var videoEnabled: Boolean = prefs.getBoolean(KEY_VIDEO, true)
        private set

    /** Sleep recognition while the car stands still; a stakeout otherwise eats the battery. */
    @Volatile
    var autoPauseParked: Boolean = prefs.getBoolean(KEY_AUTO_PAUSE, true)
        private set

    /**
     * Whether the second recognition stage — cutting the next crop around a plate already found —
     * is used: [NARROW_OFF], [NARROW_ON], or [NARROW_EXPERIMENT], which alternates every minute so
     * the two strategies can be compared on the same drive.
     */
    @Volatile
    var narrowCrops: Int = prefs.getInt(KEY_NARROW, NARROW_ON)
        private set

    @Volatile
    var alertMinLevel: Int = prefs.getInt(KEY_ALERT_LEVEL, 2)
        private set

    @Volatile
    var alertAfterEncounters: Int = prefs.getInt(KEY_ALERT_ENCOUNTERS, 2)
        private set

    /**
     * Rebuild the recognition engine on purpose before its runtime entitlement runs out.
     *
     * The vendor engine stops recognizing after a couple of hours in one native session and says so
     * only through an error code. Recycling it while the road is empty costs a second of blindness
     * at a moment when there is nothing to see; not recycling it costs the rest of the drive.
     */
    @Volatile
    var enginePreventiveReload: Boolean = prefs.getBoolean(KEY_ENGINE_RELOAD, true)
        private set

    fun setEnginePreventiveReload(value: Boolean): Boolean {
        enginePreventiveReload = value
        prefs.edit { putBoolean(KEY_ENGINE_RELOAD, value) }
        return enginePreventiveReload
    }

    /**
     * Remembers the running trip across a deliberate process restart.
     *
     * Restarting the process to revive the engine must not fabricate evidence: a new trip id would
     * make every car still behind us look like it had met us "on two different trips", which is one
     * of the things that promotes a vehicle to SUSPECT.
     */
    fun handOverTrip(tripId: Long, startedAtMs: Long) {
        prefs.edit {
            putLong(KEY_HANDOVER_TRIP, tripId)
            putLong(KEY_HANDOVER_START, startedAtMs)
            putLong(KEY_HANDOVER_AT, System.currentTimeMillis())
        }
    }

    /**
     * Records a deliberate process restart and reports how many happened inside [windowMs].
     *
     * The attempt counters that decide whether to restart live in the activity and die with the
     * process, so they cannot see a loop. This can: it is the only thing standing between a phone
     * where the engine's allowance genuinely cannot be cleared and an app that reboots itself for
     * the rest of the day.
     */
    fun noteProcessRestart(nowMs: Long, windowMs: Long): Int {
        val since = prefs.getLong(KEY_RESTART_WINDOW_AT, 0L)
        val fresh = nowMs - since > windowMs
        val count = if (fresh) 1 else prefs.getInt(KEY_RESTART_COUNT, 0) + 1
        prefs.edit {
            putInt(KEY_RESTART_COUNT, count)
            if (fresh) putLong(KEY_RESTART_WINDOW_AT, nowMs)
        }
        return count
    }

    /** How many deliberate restarts happened inside [windowMs], without recording another. */
    fun processRestarts(nowMs: Long, windowMs: Long): Int {
        val since = prefs.getLong(KEY_RESTART_WINDOW_AT, 0L)
        if (nowMs - since > windowMs) return 0
        return prefs.getInt(KEY_RESTART_COUNT, 0)
    }

    /**
     * The trip a restarting process left behind, or null when there is none worth resuming.
     *
     * Consumed once: a handover older than [HANDOVER_VALID_MS] belongs to a drive that has ended,
     * and picking it up would staple today's cars onto yesterday's route.
     */
    fun consumeTripHandover(nowMs: Long): Pair<Long, Long>? {
        val tripId = prefs.getLong(KEY_HANDOVER_TRIP, 0L)
        val startedAt = prefs.getLong(KEY_HANDOVER_START, 0L)
        val savedAt = prefs.getLong(KEY_HANDOVER_AT, 0L)
        prefs.edit {
            remove(KEY_HANDOVER_TRIP)
            remove(KEY_HANDOVER_START)
            remove(KEY_HANDOVER_AT)
        }
        if (tripId <= 0L || startedAt <= 0L) return null
        if (nowMs - savedAt > HANDOVER_VALID_MS) return null
        return tripId to startedAt
    }

    fun setTailSeconds(value: Int): Int {
        tailSeconds = value.coerceIn(15, 1_800)
        prefs.edit { putInt(KEY_TAIL, tailSeconds) }
        return tailSeconds
    }

    fun setVideoAbsentSeconds(value: Int): Int {
        videoAbsentSeconds = value.coerceIn(5, 600)
        prefs.edit { putInt(KEY_ABSENT, videoAbsentSeconds) }
        return videoAbsentSeconds
    }

    fun setVideoEnabled(value: Boolean): Boolean {
        videoEnabled = value
        prefs.edit { putBoolean(KEY_VIDEO, value) }
        return videoEnabled
    }

    fun setAutoPauseParked(value: Boolean): Boolean {
        autoPauseParked = value
        prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }
        return autoPauseParked
    }

    fun setNarrowCrops(value: Int): Int {
        narrowCrops = value.coerceIn(NARROW_OFF, NARROW_EXPERIMENT)
        prefs.edit { putInt(KEY_NARROW, narrowCrops) }
        return narrowCrops
    }

    /**
     * The strategy by the thing it does, not by whether a flag is set.
     *
     * "вкл"/"выкл" answered a question nobody asks. What the operator needs to know is what the
     * scanner is cutting out of each frame, because that is the choice being made.
     */
    fun narrowCropsName(): String = when (narrowCrops) {
        NARROW_OFF -> "по машине"
        NARROW_EXPERIMENT -> "сравнение"
        else -> "по номеру"
    }

    fun setAlertMinLevel(value: Int): Int {
        alertMinLevel = value.coerceIn(1, 4)
        prefs.edit { putInt(KEY_ALERT_LEVEL, alertMinLevel) }
        return alertMinLevel
    }

    fun setAlertAfterEncounters(value: Int): Int {
        alertAfterEncounters = value.coerceIn(1, 10)
        prefs.edit { putInt(KEY_ALERT_ENCOUNTERS, alertAfterEncounters) }
        return alertAfterEncounters
    }

    fun summary(): String = buildString {
        append("⚙️ <b>Настройки</b>\n")
        append("время хвоста: <b>${tailSeconds} с</b> — столько машина должна держаться сзади\n")
        append("видео: <b>${if (videoEnabled) "вкл" else "выкл"}</b>, ")
        append("стоп через <b>${videoAbsentSeconds} с</b> после пропажи из кадра\n")
        append("сон на стоянке: <b>${if (autoPauseParked) "вкл" else "выкл"}</b>\n")
        append("тревога с уровня: <b>${levelName(alertMinLevel)}</b>\n")
        append("или после <b>${alertAfterEncounters}</b> встреч\n")
        append("профилактика движка: <b>${if (enginePreventiveReload) "вкл" else "выкл"}</b>\n")
        // Stated outright, because it was not stated anywhere at all: the operator had no way to
        // see which cropping strategy was running and reasonably assumed the safe one while the
        // A/B comparison had been on for days.
        append("вырез: <b>${narrowCropsName()}</b>")
        if (narrowCrops == NARROW_EXPERIMENT) {
            append(" — сравниваю обе, меняю каждую минуту")
        }
    }

    private fun levelName(rank: Int): String = when (rank) {
        1 -> "наблюдение"
        2 -> "подозрение"
        3 -> "хвост"
        else -> "чёрный список"
    }

    companion object {
        const val NARROW_OFF = 0
        const val NARROW_ON = 1
        const val NARROW_EXPERIMENT = 2

        /** How long each arm of the experiment runs before the other takes over. */
        const val EXPERIMENT_BLOCK_MS = 60_000L

        const val KEY_NARROW = "recognition_narrow_crops"
        const val KEY_TAIL = "follow_tail_seconds"
        const val KEY_ABSENT = "follow_video_absent_seconds"
        const val KEY_VIDEO = "follow_video_enabled"
        const val KEY_AUTO_PAUSE = "follow_auto_pause"
        const val KEY_ALERT_LEVEL = "alert_min_level"
        const val KEY_ALERT_ENCOUNTERS = "alert_after_encounters"
        const val KEY_ENGINE_RELOAD = "engine_preventive_reload"

        private const val KEY_HANDOVER_TRIP = "handover_trip_id"
        private const val KEY_HANDOVER_START = "handover_trip_start"
        private const val KEY_HANDOVER_AT = "handover_saved_at"
        private const val KEY_RESTART_COUNT = "process_restart_count"
        private const val KEY_RESTART_WINDOW_AT = "process_restart_window_at"

        /** A restart takes seconds; anything older than this is not the same drive. */
        private const val HANDOVER_VALID_MS = 5L * 60_000L

        const val DEFAULT_TAIL = 120
        const val DEFAULT_ABSENT = 60
    }
}
