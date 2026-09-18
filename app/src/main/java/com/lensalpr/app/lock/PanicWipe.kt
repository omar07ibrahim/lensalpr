package com.lensalpr.app.lock

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lensalpr.app.data.AppStore
import com.lensalpr.app.data.StorageCleaner
import com.lensalpr.app.data.WipeStats
import com.lensalpr.app.data.DATABASE_NAME
import java.io.File

/**
 * Erases everything the app knows, on the owner's explicit word.
 *
 * Deliberately *not* wired to the entry code any more. Wiping on three mistyped digits punishes
 * cold hands and a bump in the road far more often than it punishes a thief, and the thing it
 * destroys — a season of plates, routes and photographs — cannot be collected again. Running out
 * of attempts now locks the phone instead; this runs only when the owner asks for it from the bot,
 * having been told what it does and confirmed it.
 *
 * More thorough than `/wipe`, which keeps the settings and the admin list so a session can go on.
 * This leaves a phone that looks freshly installed: the database file gone rather than emptied,
 * every photo, clip, report and preference gone, the entry code gone. Only the extracted detector
 * models stay — they are assets, not evidence.
 */
object PanicWipe {

    private const val TAG = "LensALPR.Wipe"

    fun run(context: Context): String {
        val app = context.applicationContext
        val before = runCatching { StorageCleaner.usage(app) }.getOrNull()

        // Rows first, through the shared handle, so no other component serves them from its page
        // cache afterwards; then the handle itself; then the file.
        runCatching {
            val store = AppStore.get(app)
            StorageCleaner.wipe(app, store, keepAdmins = false)
        }.onFailure { Log.w(TAG, "database wipe failed", it) }
        AppStore.closeForWipe()
        runCatching { app.deleteDatabase(DATABASE_NAME) }

        // Everything under files/ except the detector models.
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

        // Preferences last, because the settings hold the bot token this message is travelling on.
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(app).edit(commit = true) { clear() }
        }
        runCatching {
            File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
                val name = file.name.removeSuffix(".xml")
                if (name.startsWith("android.")) return@forEach
                runCatching { app.deleteSharedPreferences(name) }
            }
        }
        LockStore.clear(app)

        Log.w(TAG, "all data erased on the operator's instruction")
        val freed = before?.let { (files, bytes) -> "Удалено: $files файлов, ${WipeStats.format(bytes)}." }
        return listOfNotNull(
            "🧨 Стёрто всё: база, фото, клипы, отчёты, настройки и код входа.",
            freed,
            "Телефон выглядит как после установки. Код входа задаётся заново при следующем запуске.",
        ).joinToString("\n")
    }
}
