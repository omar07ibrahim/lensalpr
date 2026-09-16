package com.lensalpr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Makes a crash something you find out about, instead of something you find out from.
 *
 * The phone spends the drive stuck to the rear window with nobody looking at it. Without this, an
 * uncaught exception ends the session in silence: the notification disappears, recognition stops,
 * and the first sign of trouble is an empty report hours later — by which time the drive that was
 * supposed to be watched is over.
 *
 * So a crash leaves a note on disk and puts a tappable notification on the screen. The note is
 * delivered to Telegram by the next session that starts, with the stack trace attached, because a
 * dying process is in no position to make a network call.
 */
object CrashReporter {

    private const val TAG = "LensALPR.Crash"
    private const val FILE = "last_crash.txt"
    private const val CHANNEL = "lensalpr_crash"
    private const val NOTIFICATION_ID = 4711
    private val STAMP = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            runCatching { notify(app) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val text = buildString {
            append("время: ").append(STAMP.format(Date())).append('\n')
            append("поток: ").append(thread.name).append('\n')
            append(writer.toString())
        }
        File(context.filesDir, FILE).writeText(text.take(MAX_REPORT_CHARS))
        Log.e(TAG, "session crashed on ${thread.name}", error)
    }

    /** A crash the operator can see and act on without unlocking anything first. */
    private fun notify(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.crash_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val restart = PendingIntent.getActivity(
            context,
            0,
            // SINGLE_TOP as well: without it a tap on a stale note, hours into a new session,
            // finished the running scanner and started it over — trip, clip and bot included.
            Intent(context, ScanActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.crash_title))
            .setContentText(context.getString(R.string.crash_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(restart)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Takes the note off the shade: a session is running, so the tap it offered has no job left. */
    fun dismiss(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /** The report left by a previous run, if any. Kept on disk until [clear] confirms delivery. */
    fun peek(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Drops the report once it has reached the operator. Deleting it at read time would lose it
     * whenever the bot is not connected yet — which is precisely the case after a crash at startup.
     */
    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }

    private const val MAX_REPORT_CHARS = 8_000
}
