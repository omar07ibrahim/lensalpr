package com.lensalpr.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the bot back after the phone restarts, and after the app itself is replaced.
 *
 * Only the bot. Scanning needs the camera, and a foreground service claiming the camera cannot be
 * started from boot at all on modern Android — it also needs a windscreen mount and a driver. What
 * matters here is that a phone which rebooted on its own in the car is still reachable, and can
 * still be unlocked, without anybody touching it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i(TAG, "starting the bot after ${intent.action}")
                BotService.start(context)
            }
        }
    }

    private companion object {
        const val TAG = "LensALPR.Boot"
    }
}
