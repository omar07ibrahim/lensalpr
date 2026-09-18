package com.lensalpr.app.data

import android.content.Context

/**
 * One database for the whole process.
 *
 * The scanner and the bot now live in different components with different lifetimes — the bot
 * outlives the activity on purpose — but they read and write the same evidence. Two
 * SQLiteOpenHelpers over one file means two connection pools racing for the same write lock, and
 * the loser gets "database is locked" at exactly the moment a tail is being recorded.
 */
object AppStore {

    @Volatile
    private var instance: TrackingStore? = null

    fun get(context: Context): TrackingStore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: TrackingStore(context.applicationContext).also { instance = it }
        }
    }

    /**
     * Closes the shared handle before the database file itself is deleted.
     *
     * A helper still holding a deleted file will happily serve rows from its page cache and then
     * recreate the file on the next write, which is how an "erase everything" leaves data behind.
     *
     * The instance itself is deliberately kept. Other components — the bot's service above all —
     * hold this object for their whole lifetime, and handing the next caller a *different*
     * TrackingStore would put two SQLiteOpenHelpers back on one file: exactly the race this object
     * exists to prevent, reintroduced by the cleanup. `SQLiteOpenHelper` reopens on the next query,
     * so the shared instance simply comes back pointing at a fresh, empty database.
     */
    fun closeForWipe() {
        synchronized(this) {
            runCatching { instance?.close() }
        }
    }
}
