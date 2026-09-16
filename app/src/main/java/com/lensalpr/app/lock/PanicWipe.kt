package com.lensalpr.app.lock

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lensalpr.app.data.StorageCleaner
import com.lensalpr.app.data.TrackingStore
import java.io.File

/**
 * Erases everything the app knows after the third wrong password.
 *
 * More thorough than the operator's `/wipe`: that one keeps the settings and the admin list so
 * the session can go on. This one leaves a phone that looks freshly installed - database file
 * gone rather than emptied, every photo, clip, report and preference gone, the password itself
 * gone. Only the extracted detector models stay: they are assets, not data.
 */
object PanicWipe {

    private const val TAG = "LensALPR.Wipe"
    private const val LOCK_PREFS = "lensalpr_lock"

    fun run(context: Context) {
        val app = context.applicationContext
        // Rows first, through the store, so an open connection elsewhere does not resurrect them
        // from its page cache; then the file itself.
        runCatching {
            val store = TrackingStore(app)
            runCatching { StorageCleaner.wipe(app, store, keepAdmins = false) }
            store.close()
        }.onFailure { Log.w(TAG, "database wipe failed", it) }
        runCatching { app.deleteDatabase(TrackingStore.DATABASE_NAME) }
        runCatching { app.getDatabasePath(TrackingStore.DATABASE_NAME).parentFile?.deleteRecursively() }

        // Everything under files/ except the models, including directories older builds left behind.
        runCatching {
            app.filesDir.walkBottomUp().forEach { file ->
                if (file == app.filesDir) return@forEach
                if (file.isFile && file.name.endsWith(".onnx")) return@forEach
                if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) return@forEach
                file.delete()
            }
        }
        runCatching { app.cacheDir.deleteRecursively() }
        runCatching { app.cacheDir.mkdirs() }
        runCatching { app.codeCacheDir.deleteRecursively() }
        runCatching { app.getExternalFilesDir(null)?.deleteRecursively() }
        runCatching { app.externalCacheDir?.deleteRecursively() }

        // Preferences last: the settings with the bot token, and the lock with its counter.
        runCatching { PreferenceManager.getDefaultSharedPreferences(app).edit(commit = true) { clear() } }
        runCatching {
            val dir = File(app.applicationInfo.dataDir, "shared_prefs")
            dir.listFiles()?.forEach { file ->
                val name = file.name.removeSuffix(".xml")
                if (name.startsWith("android.")) return@forEach
                runCatching { app.deleteSharedPreferences(name) }
            }
        }
        runCatching { app.getSharedPreferences(LOCK_PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        Log.w(TAG, "all data erased after repeated wrong passwords")
    }
}
