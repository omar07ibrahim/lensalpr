package com.lensalpr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lensalpr.app.data.AppStore
import com.lensalpr.app.lock.LockActivity
import com.lensalpr.app.lock.LockStore
import com.lensalpr.app.lock.PanicWipe
import com.lensalpr.app.settings.RuntimeSettings
import com.lensalpr.app.settings.ScanConfig
import com.lensalpr.app.telegram.BotHostRouter
import com.lensalpr.app.telegram.BotSettings
import com.lensalpr.app.telegram.LockControl
import com.lensalpr.app.telegram.TelegramBot

/**
 * The bot has a home of its own now, independent of whether anything is being scanned.
 *
 * It used to be a field of the scanning activity, which meant the remote control died with the
 * screen: close the app and there was no way to ask the phone anything. Worse, a phone that has
 * locked itself out has no activity left at all, so the one command able to open it was the one
 * command that could never arrive. Here the bot survives all of that, and it is the only component
 * still running while the entry lock is engaged.
 *
 * It owns no scanning logic. The scanner attaches itself to [BotHostRouter] while it lives.
 */
class BotService : Service() {

    private var link: TelegramBot? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        startBot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCKED -> {
                startForegroundCompat()
                link?.broadcast(
                    "🔒 Телефон заблокирован: три неверных кода подряд. Экран белый, " +
                        "сканирование остановлено.\nОткрыть — <code>/unlock КОД</code>.",
                    urgent = true,
                )
            }

            ACTION_UNLOCKED, ACTION_REFRESH -> startForegroundCompat()
        }
        // Another chance to connect: the token may have been filled in since the last attempt.
        startBot()
        // The bot should be the last thing to die when the system is short of memory, and the
        // first to come back.
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { link?.stop() }
        link = null
        super.onDestroy()
    }

    /**
     * Brings the link up, if it can be brought up.
     *
     * Idempotent, and retried on every [onStartCommand]: a first launch with no token configured
     * used to leave the service running and permanently mute, because nothing ever asked again.
     * Now every screen that starts the service also gives the bot another chance to connect.
     */
    private fun startBot() {
        if (link != null) return
        if (ScanConfig.telegram(this).token.isBlank()) {
            Log.i(TAG, "no bot token yet; the service stays up and will retry")
            return
        }
        val runtime = RuntimeSettings(this)
        val active = TelegramBot(
            store = AppStore.get(this),
            host = BotHostRouter,
            lock = lockControl,
            settings = {
                // Read every time, not captured: this service outlives the setup screen, so a
                // token or owner id corrected there has to reach a bot that is already running.
                val telegram = ScanConfig.telegram(this)
                BotSettings(
                    token = telegram.token,
                    ownerId = telegram.ownerId,
                    enabled = telegram.enabled,
                    alertMinLevel = runtime.alertMinLevel,
                    alertAfterEncounters = runtime.alertAfterEncounters,
                    narrowCrops = runtime.narrowCrops,
                )
            },
        )
        link = active
        active.start()
        Log.i(TAG, "bot service up")
    }

    /**
     * What the bot may do about the lock.
     *
     * Lives here rather than in the scanner because every one of these has to work when there is
     * no scanner — which is the entire reason the lock has a remote at all.
     */
    private val lockControl = object : LockControl {
        override fun isLockedOut(): Boolean = LockStore.isLockedOut(this@BotService)

        override fun unlock(code: String): Boolean {
            // Checked without spending an attempt: the three attempts belong to whoever is holding
            // the phone, and a typo in a chat message must not push the owner closer to lockout.
            if (!LockStore.checkCode(this@BotService, code)) return false
            LockStore.unlockRemotely(this@BotService)
            start(this@BotService, ACTION_UNLOCKED)
            return true
        }

        override fun lock() {
            LockStore.lockRemotely(this@BotService)
            ScanSessionService.stop(this@BotService)
            // Put the white sheet in front straight away. Starting an activity from a service is
            // exactly what the "display over other apps" permission is for; without it Android
            // refuses, and the scanner still stops itself within a watchdog tick — it just takes
            // a few seconds longer for the screen to catch up.
            runCatching {
                startActivity(
                    Intent(this@BotService, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.onFailure { Log.w(TAG, "could not raise the lock screen", it) }
            start(this@BotService, ACTION_REFRESH)
        }

        override fun wipeEverything(): String = PanicWipe.run(this@BotService)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bot_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { setShowBadge(false) },
            )
        }
        val locked = LockStore.isLockedOut(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bot_service_title))
            .setContentText(
                getString(if (locked) R.string.bot_service_locked else R.string.bot_service_text),
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()

        // specialUse rather than dataSync: this is a long-lived control channel with no work to
        // finish, and dataSync is capped at a few hours a day on recent Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "LensALPR.BotSvc"
        private const val CHANNEL_ID = "bot_link"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_LOCKED = "com.lensalpr.app.LOCKED"
        private const val ACTION_UNLOCKED = "com.lensalpr.app.UNLOCKED"

        /** Redraw the notification without announcing anything; the caller already has. */
        private const val ACTION_REFRESH = "com.lensalpr.app.REFRESH"

        @Volatile
        private var instance: BotService? = null

        /** The live bot, or null while the service is coming up or has no token configured. */
        val bot: TelegramBot? get() = instance?.link

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, BotService::class.java)
            if (action != null) intent.action = action
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start the bot service", it) }
        }

        /** Tells the operator the phone just locked itself, over the one channel still alive. */
        fun announceLockout(context: Context) = start(context, ACTION_LOCKED)
    }
}
