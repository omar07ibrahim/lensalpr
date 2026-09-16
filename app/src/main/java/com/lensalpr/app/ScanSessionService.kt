package com.lensalpr.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the scanning session alive.
 *
 * Android refuses camera access to a backgrounded process, so without a foreground service the
 * whole thing stops the moment the screen turns off or the driver switches apps - which is exactly
 * when a phone stuck to the rear window is supposed to be working. The service owns no logic; it
 * exists so the session may keep the camera and the location feed.
 */
class ScanSessionService : Service() {

    /**
     * Holds the CPU awake for the whole session.
     *
     * The foreground service keeps the process alive, but it does not by itself stop the system
     * from winding the CPU down between camera callbacks — and the moment recognition slows is the
     * moment a car in frame stops being the same car to the tracker. The permission was already
     * declared and never used; this is what it was declared for.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            // "Stop" in the shade means stop *scanning*, not just drop the notification: the
            // session lives in the activity, and it is told first. It answers by restarting this
            // service in its idle state, or by finishing — either way the pipeline is really off.
            val handled = intent?.action == ACTION_STOP && stopListener?.let { listener ->
                Handler(Looper.getMainLooper()).post { listener() }
                true
            } == true
            if (!handled) stopSelf()
            return START_NOT_STICKY
        }
        // The activity owns the pipeline; restarting this service alone cannot restore a scan.
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.w(WAKE_TAG, "Cannot start a scanning service without camera permission")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat(intent.getStringExtra(EXTRA_STATUS) ?: getString(R.string.service_running))
            acquireWakeLock()
        } catch (error: RuntimeException) {
            // Promotion is a later framework callback: start() cannot catch this failure.
            Log.e(WAKE_TAG, "Cannot promote the scanning service to foreground", error)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                // No timeout: the session is the timeout. It is released in onDestroy, and the
                // service cannot outlive the process that owns it.
                acquire()
            }
        }.onSuccess { wakeLock = it }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ScanActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScanSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.service_stop), stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    ) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "scan_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.lensalpr.app.STOP_SESSION"
        private const val EXTRA_STATUS = "status"
        private const val WAKE_TAG = "LensALPR::session"

        /**
         * Whoever owns the session right now. Set by the scanner while it is alive and cleared
         * when it goes; invoked on the main thread when the operator taps "Stop" in the shade.
         */
        @Volatile
        var stopListener: (() -> Unit)? = null

        fun start(context: Context, status: String? = null) {
            val intent = Intent(context, ScanSessionService::class.java)
            if (status != null) intent.putExtra(EXTRA_STATUS, status)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScanSessionService::class.java)) }
        }
    }
}
