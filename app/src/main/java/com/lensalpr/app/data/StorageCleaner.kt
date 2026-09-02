package com.lensalpr.app.data

import android.content.Context
import java.io.File
import java.util.Locale

/** Everything one wipe removed: database rows plus the files behind them. */
data class WipeStats(
    val db: DbWipe,
    val files: Int,
    val bytes: Long,
) {
    fun describe(): String = buildString {
        append("машин ${db.vehicles}, встреч ${db.encounters}, наблюдений ${db.sightings}, ")
        append("поездок ${db.trips}, файлов $files (${format(bytes)})")
    }

    companion object {
        fun format(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
            else -> "$bytes Б"
        }
    }
}

/**
 * Erases every trace of past sessions: plate database, evidence photos, follower clips, crops that
 * were spilled to disk, generated reports and debug captures.
 *
 * The detector models live in [Context.getFilesDir] as well but are left alone — they are extracted
 * assets, not user data, and re-extracting them costs a slow first frame for nothing.
 */
object StorageCleaner {

    private val KEEP_SUFFIXES = listOf(".onnx")

    /** How much disk the session data occupies right now. */
    fun usage(context: Context): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        walk(context) { file ->
            files++
            bytes += file.length()
        }
        return files to bytes
    }

    fun wipe(context: Context, store: TrackingStore, keepAdmins: Boolean = true): WipeStats {
        var files = 0
        var bytes = 0L
        walk(context) { file ->
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                files++
                bytes += size
            }
        }
        // Directories are recreated so a running session keeps writing without a restart.
        directories(context).forEach { runCatching { it.mkdirs() } }
        val db = store.wipeDatabase(keepAdmins)
        return WipeStats(db, files, bytes)
    }

    private fun directories(context: Context): List<File> = listOf(
        File(context.filesDir, "evidence"),
        File(context.filesDir, "clips"),
        File(context.cacheDir, "clips"),
        File(context.cacheDir, "spill"),
        File(context.cacheDir, "bench"),
    )

    /** Visits every file a session produced, deepest first. */
    private fun walk(context: Context, action: (File) -> Unit) {
        directories(context).forEach { dir -> dir.walkBottomUp().filter { it.isFile }.forEach(action) }
        // Reports, snapshots and any stray log land straight in the cache root.
        context.cacheDir.listFiles()?.filter { it.isFile }?.forEach(action)
        context.filesDir.listFiles()
            ?.filter { file -> file.isFile && KEEP_SUFFIXES.none { file.name.endsWith(it) } }
            ?.forEach(action)
    }
}
