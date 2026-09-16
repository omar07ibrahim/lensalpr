package com.lensalpr.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.lensalpr.app.alpr.PlateSimilarity
import com.lensalpr.app.track.TripTracker
import java.io.File

data class VehicleRow(
    val plate: String,
    val displayPlate: String,
    val make: String?,
    val model: String?,
    val year: String?,
    val color: String?,
    val body: String?,
    val country: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightings: Int,
    val encounters: Int,
    val tripsSeen: Int,
    val sharedTurns: Int,
    val reacquisitions: Int,
    val contactMs: Long,
    val contactM: Double,
    val bestScore: Float,
    val level: Int,
    val blacklisted: Boolean,
    /** Dismissed by the operator: never alerts, never records, never climbs a level again. */
    val ignored: Boolean,
    /**
     * Marked by the operator as a police vehicle.
     *
     * Alerts exactly like the blacklist — the driver wants to be told loudly and every time — but
     * kept as its own flag rather than folded into it, because "this is a patrol car" and "this
     * car is following me" are different facts and the answer to them is different.
     */
    val police: Boolean,
    val note: String?,
) {
    val makeModel: String?
        get() = listOfNotNull(make, model).takeIf { it.isNotEmpty() }?.joinToString(" ")
}

data class EncounterRow(
    val id: Long,
    val plate: String,
    val tripId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val sightings: Int,
    val bestScore: Float,
    val photo: String?,
    val lens: String?,
    val distanceM: Double,
    /** Score and lens of the frame the photo was actually taken from, when there is a photo. */
    val photoScore: Float = 0f,
    val photoLens: String? = null,
    /** False when the encounter never had a usable position. */
    val hasStart: Boolean = true,
)

data class TrackPoint(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    /** The validated trip odometer at this point; NaN for rows written before it was stored. */
    val odometerM: Double = Double.NaN,
)

/** A route decision we made: the event a follower has to copy. */
data class TurnRow(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val direction: String,
    val degrees: Float,
)

data class AdminRow(val chatId: Long, val title: String, val role: String, val addedAt: Long)

/** Rows removed by a wipe, so the operator sees it really happened. */
data class DbWipe(val vehicles: Int, val encounters: Int, val sightings: Int, val trips: Int)

/** Outcome of persisting one confirmed sighting. */
data class SightingRecord(
    /** The row this sighting actually landed on: a near-identical read is merged into one car. */
    val plate: String,
    val encounterId: Long,
    val newEncounter: Boolean,
    val encounters: Int,
    val tripsSeen: Int,
    val sightings: Int,
    /** Distinct places this car has met us in; one street corner is not two meetings. */
    val places: Int,
)

/**
 * Local evidence database: our trips, every confirmed plate sighting, and the encounters they group
 * into.
 *
 * An *encounter* is a continuous contact with one vehicle: the same car seen again twenty minutes
 * later is a second encounter, which is exactly the unit the report shows (one photo and one map
 * marker per encounter, with a line drawn from the first to the last).
 *
 * Plain SQLite on purpose: no annotation processor in the build, and the schema is small enough to
 * read in one screen. All calls are synchronous and must run off the main thread.
 */
class TrackingStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trips(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    distance_m REAL NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicles(
                    plate TEXT PRIMARY KEY,
                    display_plate TEXT,
                    make TEXT, model TEXT, year TEXT, color TEXT, body TEXT, country TEXT,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    sightings INTEGER NOT NULL DEFAULT 0,
                    encounters INTEGER NOT NULL DEFAULT 0,
                    trips_seen INTEGER NOT NULL DEFAULT 0,
                    shared_turns INTEGER NOT NULL DEFAULT 0,
                    reacquisitions INTEGER NOT NULL DEFAULT 0,
                    contact_ms INTEGER NOT NULL DEFAULT 0,
                    contact_m REAL NOT NULL DEFAULT 0,
                    best_score REAL NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 0,
                    blacklisted INTEGER NOT NULL DEFAULT 0,
                    ignored INTEGER NOT NULL DEFAULT 0,
                    police INTEGER NOT NULL DEFAULT 0,
                    note TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS encounters(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    plate TEXT NOT NULL,
                    trip_id INTEGER NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER NOT NULL,
                    start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,
                    sightings INTEGER NOT NULL DEFAULT 0,
                    best_score REAL NOT NULL DEFAULT 0,
                    photo TEXT,
                    lens TEXT,
                    distance_m REAL NOT NULL DEFAULT 0,
                    photo_score REAL NOT NULL DEFAULT 0,
                    photo_lens TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sightings(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    plate TEXT NOT NULL,
                    trip_id INTEGER NOT NULL,
                    encounter_id INTEGER NOT NULL,
                    t_ms INTEGER NOT NULL,
                    lat REAL, lon REAL, bearing REAL, speed REAL, odometer_m REAL,
                    lens TEXT, ocr_score REAL, distance_m REAL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS track(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trip_id INTEGER NOT NULL,
                    t_ms INTEGER NOT NULL,
                    lat REAL NOT NULL, lon REAL NOT NULL, speed REAL,
                    odometer_m REAL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS admins(
                    chat_id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL,
                    role TEXT NOT NULL,
                    added_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS turns(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trip_id INTEGER NOT NULL,
                    t_ms INTEGER NOT NULL,
                    lat REAL, lon REAL,
                    direction TEXT NOT NULL,
                    degrees REAL NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_trip ON turns(trip_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_t ON turns(t_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sight_plate ON sightings(plate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sight_trip ON sightings(trip_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_enc_plate ON encounters(plate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_enc_trip ON encounters(trip_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_track_trip ON track(trip_id)")
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
            runCatching {
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == column) return true
                    }
                }
                false
            }.getOrDefault(false)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Every statement is CREATE ... IF NOT EXISTS, so an upgrade adds the missing pieces
            // and leaves the collected evidence alone.
            onCreate(db)
            // Columns added after a table already existed need their own statement. Asking the
            // schema first rather than swallowing the error keeps a genuine failure visible.
            if (!hasColumn(db, "vehicles", "ignored")) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "vehicles", "police")) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN police INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "encounters", "photo_score")) {
                db.execSQL("ALTER TABLE encounters ADD COLUMN photo_score REAL NOT NULL DEFAULT 0")
                // Rows from before the column existed: the photo they hold was taken at the best
                // score of the encounter at the time, which is the closest thing on record.
                db.execSQL("UPDATE encounters SET photo_score = best_score WHERE photo IS NOT NULL")
            }
            if (!hasColumn(db, "encounters", "photo_lens")) {
                db.execSQL("ALTER TABLE encounters ADD COLUMN photo_lens TEXT")
                db.execSQL("UPDATE encounters SET photo_lens = lens WHERE photo IS NOT NULL")
            }
            if (!hasColumn(db, "track", "odometer_m")) {
                db.execSQL("ALTER TABLE track ADD COLUMN odometer_m REAL")
            }
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    val photoDir: File = File(context.filesDir, "evidence").apply { mkdirs() }

    // ------------------------------------------------------------------ trips

    fun startTrip(nowMs: Long): Long = db.insert(
        "trips",
        null,
        ContentValues().apply {
            put("started_at", nowMs)
            put("distance_m", 0.0)
        },
    )

    fun finishTrip(tripId: Long, nowMs: Long, distanceM: Double) {
        db.update(
            "trips",
            ContentValues().apply {
                put("ended_at", nowMs)
                put("distance_m", distanceM)
            },
            "id = ?",
            arrayOf(tripId.toString()),
        )
    }

    /**
     * Closes every trip a dead process left open, except the one that is running now.
     *
     * A crash or a kill never reaches [finishTrip], and a row with no end used to mean "still
     * active" for ever: it was never pruned, and the report treated it as a trip in progress.
     * The end is set to the last thing recorded on it — the last track point, else the start.
     */
    fun closeAbandonedTrips(exceptTripId: Long) {
        db.execSQL(
            """
            UPDATE trips SET ended_at = COALESCE(
                (SELECT MAX(t_ms) FROM track WHERE track.trip_id = trips.id),
                (SELECT MAX(ended_at) FROM encounters WHERE encounters.trip_id = trips.id),
                started_at
            ), distance_m = COALESCE(
                (SELECT MAX(odometer_m) FROM track WHERE track.trip_id = trips.id AND odometer_m IS NOT NULL),
                distance_m
            )
            WHERE ended_at IS NULL AND id != ?
            """.trimIndent(),
            arrayOf<Any?>(exceptTripId),
        )
    }

    fun appendTrackPoint(tripId: Long, point: TrackPoint) {
        db.insert(
            "track",
            null,
            ContentValues().apply {
                put("trip_id", tripId)
                put("t_ms", point.tMs)
                put("lat", point.lat)
                put("lon", point.lon)
                put("speed", point.speedMps)
                if (point.odometerM.isFinite()) put("odometer_m", point.odometerM)
            },
        )
    }

    fun recordTurn(tripId: Long, turn: TurnRow) {
        db.insert(
            "turns",
            null,
            ContentValues().apply {
                put("trip_id", tripId)
                put("t_ms", turn.tMs)
                put("lat", turn.lat)
                put("lon", turn.lon)
                put("direction", turn.direction)
                put("degrees", turn.degrees)
            },
        )
    }

    fun turnsSince(sinceMs: Long, limit: Int = 5_000): List<TurnRow> =
        turnsBetween(sinceMs, Long.MAX_VALUE, null, limit)

    /**
     * Our turns inside a window, optionally only those of the given trips.
     *
     * A per-vehicle report is about one car's trips; the turns of every other drive in the
     * database have no business on its map, and they used to drag the map's frame across the
     * whole country.
     */
    fun turnsBetween(sinceMs: Long, untilMs: Long, tripIds: Collection<Long>?, limit: Int = 5_000): List<TurnRow> {
        val trips = tripIds?.takeIf { it.isNotEmpty() }
        val tripClause = trips?.let { " AND trip_id IN (${it.joinToString(",") { id -> id.toString() }})" }.orEmpty()
        return db.rawQuery(
            "SELECT t_ms, lat, lon, direction, degrees FROM turns WHERE t_ms >= ? AND t_ms <= ?$tripClause " +
                "ORDER BY t_ms LIMIT ?",
            arrayOf(sinceMs.toString(), untilMs.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TurnRow(
                            cursor.getLong(0),
                            cursor.getDouble(1),
                            cursor.getDouble(2),
                            cursor.getString(3),
                            cursor.getFloat(4),
                        ),
                    )
                }
            }
        }
    }

    /** Ids of the trips that overlap the window, newest last; a trip still open counts as ongoing. */
    fun tripsBetween(sinceMs: Long, untilMs: Long): List<Long> = db.rawQuery(
        "SELECT id FROM trips WHERE started_at <= ? AND (ended_at IS NULL OR ended_at >= ?) ORDER BY started_at",
        arrayOf(untilMs.toString(), sinceMs.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    fun trackPoints(tripId: Long): List<TrackPoint> = db.rawQuery(
        "SELECT t_ms, lat, lon, speed, odometer_m FROM track WHERE trip_id = ? ORDER BY t_ms",
        arrayOf(tripId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TrackPoint(
                        cursor.getLong(0),
                        cursor.getDouble(1),
                        cursor.getDouble(2),
                        cursor.getFloat(3),
                        if (cursor.isNull(4)) Double.NaN else cursor.getDouble(4),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------- sightings

    /**
     * Persists a sighting, attaching it to the running encounter or opening a new one.
     *
     * [photoWriter] is invoked only when this frame deserves to become the encounter's picture -
     * the first contact, or a materially better read - so the report keeps one good photo per
     * encounter instead of hundreds of near-duplicates.
     */
    @Synchronized
    fun recordSighting(
        plate: String,
        displayPlate: String,
        tripId: Long,
        tMs: Long,
        lat: Double?,
        lon: Double?,
        bearing: Float,
        speedMps: Float,
        odometerM: Double,
        lens: String,
        ocrScore: Float,
        distanceM: Double?,
        make: String?,
        model: String?,
        year: String?,
        color: String?,
        body: String?,
        country: String?,
        photoWriter: ((File) -> Boolean)?,
    ): SightingRecord {
        val database = db
        var pendingPhoto: Long? = null
        val record: SightingRecord
        database.beginTransaction()
        try {
            // Yesterday's row for the same car may spell one character differently; land on it
            // instead of creating a twin, or keep the better read and rename the old row.
            val plate = canonicalPlate(plate, displayPlate, ocrScore)
            // The running encounter is this trip's: a new drive that meets the same car within
            // three minutes of the old one ending is a second meeting, not a continuation.
            val existing = database.rawQuery(
                "SELECT id, ended_at, photo_score, photo FROM encounters " +
                    "WHERE plate = ? AND trip_id = ? ORDER BY ended_at DESC LIMIT 1",
                arrayOf(plate, tripId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    OpenEncounter(
                        id = cursor.getLong(0),
                        endedAt = cursor.getLong(1),
                        photoScore = cursor.getFloat(2),
                        photo = cursor.getString(3),
                    )
                } else {
                    null
                }
            }

            val running = existing?.takeIf { kotlin.math.abs(tMs - it.endedAt) <= ENCOUNTER_GAP_MS }
            val encounterId: Long
            var newEncounter = false
            val wantsPhoto: Boolean

            if (running != null) {
                encounterId = running.id
                // Against the score of the photo that is actually on disk, not the encounter's
                // best read: reads that improved in small steps never replaced a weak first shot.
                wantsPhoto = ocrScore > running.photoScore + PHOTO_IMPROVEMENT ||
                    running.photo.isNullOrBlank()
                // Reads can arrive out of capture order. The start only moves earlier, the end
                // only later, and the end position follows the end time rather than the delivery.
                database.execSQL(
                    "UPDATE encounters SET " +
                        "started_at = MIN(started_at, ?), " +
                        "start_lat = COALESCE(start_lat, ?), start_lon = COALESCE(start_lon, ?), " +
                        "end_lat = CASE WHEN ? >= ended_at THEN COALESCE(?, end_lat) ELSE end_lat END, " +
                        "end_lon = CASE WHEN ? >= ended_at THEN COALESCE(?, end_lon) ELSE end_lon END, " +
                        "ended_at = MAX(ended_at, ?), " +
                        "sightings = sightings + 1, best_score = MAX(best_score, ?) WHERE id = ?",
                    arrayOf<Any?>(tMs, lat, lon, tMs, lat, tMs, lon, tMs, ocrScore, encounterId),
                )
            } else {
                newEncounter = true
                wantsPhoto = true
                encounterId = database.insert(
                    "encounters",
                    null,
                    ContentValues().apply {
                        put("plate", plate)
                        put("trip_id", tripId)
                        put("started_at", tMs)
                        put("ended_at", tMs)
                        put("start_lat", lat)
                        put("start_lon", lon)
                        put("end_lat", lat)
                        put("end_lon", lon)
                        put("sightings", 1)
                        put("best_score", ocrScore)
                        put("lens", lens)
                    },
                )
            }

            database.insert(
                "sightings",
                null,
                ContentValues().apply {
                    put("plate", plate)
                    put("trip_id", tripId)
                    put("encounter_id", encounterId)
                    put("t_ms", tMs)
                    put("lat", lat)
                    put("lon", lon)
                    put("bearing", bearing)
                    put("speed", speedMps)
                    put("odometer_m", odometerM)
                    put("lens", lens)
                    put("ocr_score", ocrScore)
                    put("distance_m", distanceM)
                },
            )

            // The JPEG is encoded after the transaction commits: compressing a photo takes tens of
            // milliseconds, and holding the database lock for them stalls every other writer —
            // including the follow engine deciding whether the car behind is a tail.
            pendingPhoto = if (wantsPhoto) encounterId else null

            val counts = database.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT trip_id) FROM encounters WHERE plate = ?",
                arrayOf(plate),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getInt(1) else 0 to 0
            }
            val totalSightings = database.rawQuery(
                "SELECT COUNT(*) FROM sightings WHERE plate = ?",
                arrayOf(plate),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

            database.execSQL(
                """
                INSERT INTO vehicles(plate, display_plate, make, model, year, color, body, country,
                    first_seen, last_seen, sightings, encounters, trips_seen, best_score)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(plate) DO UPDATE SET
                    display_plate = CASE
                        WHEN LENGTH(excluded.display_plate) > LENGTH(vehicles.display_plate)
                            THEN excluded.display_plate
                        WHEN LENGTH(excluded.display_plate) < LENGTH(vehicles.display_plate)
                            THEN vehicles.display_plate
                        WHEN excluded.best_score >= vehicles.best_score THEN excluded.display_plate
                        ELSE vehicles.display_plate
                    END,
                    make = COALESCE(vehicles.make, excluded.make),
                    model = COALESCE(vehicles.model, excluded.model),
                    year = COALESCE(vehicles.year, excluded.year),
                    color = COALESCE(vehicles.color, excluded.color),
                    body = COALESCE(vehicles.body, excluded.body),
                    country = COALESCE(vehicles.country, excluded.country),
                    last_seen = MAX(vehicles.last_seen, excluded.last_seen),
                    -- A row created by hand for a plate the camera had not seen carries zero here,
                    -- and nothing used to overwrite it: the report would then claim the car had
                    -- been followed since January, in a format with no year to give it away.
                    -- The first real sighting fixes it; after that the earliest reading wins.
                    first_seen = CASE
                        WHEN vehicles.first_seen = 0 THEN excluded.first_seen
                        ELSE MIN(vehicles.first_seen, excluded.first_seen)
                    END,
                    sightings = excluded.sightings,
                    encounters = excluded.encounters,
                    trips_seen = excluded.trips_seen,
                    best_score = MAX(vehicles.best_score, excluded.best_score)
                """.trimIndent(),
                arrayOf<Any?>(
                    plate, displayPlate, make, model, year, color, body, country,
                    tMs, tMs, totalSightings, counts.first, counts.second, ocrScore,
                ),
            )

            val places = distinctPlaces(plate)
            record = SightingRecord(
                plate = plate,
                encounterId = encounterId,
                newEncounter = newEncounter,
                encounters = counts.first,
                tripsSeen = counts.second,
                sightings = totalSightings,
                places = places,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        val photoFor = pendingPhoto
        if (photoFor != null && photoWriter != null) {
            val file = File(photoDir, "enc_$photoFor.jpg")
            // Both failure paths were silent, which is why "five encounters, three photos" was
            // impossible to explain from the phone. The encounter row is written either way — it
            // is the observation that matters — but a missing picture now leaves a trace.
            if (photoWriter(file)) {
                runCatching {
                    // The score and the lens of the frame the picture came from travel with it,
                    // so the next candidate is judged against the photo that is really there and
                    // the report captions the photo with the lens that took it.
                    database.execSQL(
                        "UPDATE encounters SET photo = ?, photo_score = ?, photo_lens = ? WHERE id = ?",
                        arrayOf<Any?>(file.absolutePath, ocrScore, lens, photoFor),
                    )
                }.onFailure { error ->
                    Log.w(TAG, "encounter $photoFor: photo written but not linked", error)
                }
            } else {
                Log.w(TAG, "encounter $photoFor: could not write ${file.name}")
            }
        } else if (photoFor != null) {
            Log.w(TAG, "encounter $photoFor: no thumbnail available, stays without a photo")
        }
        return record
    }

    /**
     * Resolves a reading to the plate already stored for that car when the two differ only by a
     * character the engine confuses. The spelling with the better score wins, and the losing rows
     * are moved onto it so history, photos and threat level stay on one vehicle.
     */
    fun canonicalPlate(plate: String, displayPlate: String, score: Float): String {
        val exact = db.rawQuery(
            "SELECT 1 FROM vehicles WHERE plate = ? LIMIT 1",
            arrayOf(plate),
        ).use { it.moveToFirst() }
        if (exact) return plate

        val candidates = db.rawQuery(
            // Only cars the camera has actually read. A row created by /bl for a plate nobody has
            // seen yet has no score, so the confusion matcher would happily fold it into a
            // neighbouring spelling — and the operator's mark would end up on somebody else's car.
            "SELECT plate, best_score FROM vehicles WHERE sightings > 0 " +
                "ORDER BY last_seen DESC LIMIT ?",
            arrayOf(MERGE_SCAN_LIMIT.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getFloat(1)) }
        }
        val match = candidates.firstOrNull { PlateSimilarity.similar(it.first, plate) } ?: return plate
        if (PlateSimilarity.prefer(match.first, match.second, plate, score)) return match.first

        renamePlate(match.first, plate, displayPlate)
        return plate
    }

    /**
     * Folds vehicles that are one OCR confusion apart into a single row, keeping the spelling the
     * engine scored highest. Runs once per session: history collected before the merge existed —
     * or written while two cards were still separate — ends up on one car.
     */
    fun mergeDuplicates(): Int {
        val rows = db.rawQuery(
            // Same exclusion as canonicalPlate, and here it matters even more: this runs at every
            // session start, and mergeInto carries blacklisted across with MAX(). A scoreless row
            // typed by the operator would sort last, be folded into a similar plate the camera had
            // really seen, and put that innocent car on the blacklist for good.
            "SELECT plate, best_score FROM vehicles WHERE sightings > 0 " +
                "ORDER BY LENGTH(plate) DESC, best_score DESC",
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getFloat(1)) }
        }
        val kept = ArrayList<String>(rows.size)
        var merged = 0
        db.beginTransaction()
        try {
            rows.forEach { (plate, _) ->
                val target = kept.firstOrNull { PlateSimilarity.similar(it, plate) }
                if (target == null) {
                    kept += plate
                } else {
                    mergeInto(from = plate, into = target)
                    merged++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return merged
    }

    /** Rolls one vehicle's history into another and drops the losing row. */
    private fun mergeInto(from: String, into: String) {
        val loser = vehicle(from) ?: return
        val winner = vehicle(into) ?: return
        db.execSQL("UPDATE encounters SET plate = ? WHERE plate = ?", arrayOf<Any?>(into, from))
        db.execSQL("UPDATE sightings SET plate = ? WHERE plate = ?", arrayOf<Any?>(into, from))
        val encounters = db.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT trip_id) FROM encounters WHERE plate = ?",
            arrayOf(into),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getInt(1) else 0 to 0 }
        val sightings = db.rawQuery(
            "SELECT COUNT(*) FROM sightings WHERE plate = ?",
            arrayOf(into),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        db.execSQL(
            """
            UPDATE vehicles SET
                make = COALESCE(make, ?), model = COALESCE(model, ?), year = COALESCE(year, ?),
                color = COALESCE(color, ?), body = COALESCE(body, ?), country = COALESCE(country, ?),
                -- MIN would let a hand-created row's zero win and hand the surviving car a first
                -- contact in 1970, permanently. Zero means "unknown", not "earliest".
                first_seen = CASE
                    WHEN first_seen = 0 THEN ?
                    WHEN ? = 0 THEN first_seen
                    ELSE MIN(first_seen, ?)
                END,
                last_seen = MAX(last_seen, ?),
                sightings = ?, encounters = ?, trips_seen = ?,
                shared_turns = MAX(shared_turns, ?), reacquisitions = MAX(reacquisitions, ?),
                contact_ms = MAX(contact_ms, ?), contact_m = MAX(contact_m, ?),
                best_score = MAX(best_score, ?), level = MAX(level, ?),
                blacklisted = MAX(blacklisted, ?), ignored = MAX(ignored, ?),
                police = MAX(police, ?), note = COALESCE(note, ?)
            WHERE plate = ?
            """.trimIndent(),
            arrayOf<Any?>(
                loser.make, loser.model, loser.year, loser.color, loser.body, loser.country,
                // Three bindings for the one CASE above.
                loser.firstSeen, loser.firstSeen, loser.firstSeen,
                loser.lastSeen,
                sightings, encounters.first, encounters.second,
                loser.sharedTurns, loser.reacquisitions,
                loser.contactMs, loser.contactM,
                loser.bestScore, loser.level,
                if (loser.blacklisted) 1 else 0, if (loser.ignored) 1 else 0,
                if (loser.police) 1 else 0, loser.note,
                winner.plate,
            ),
        )
        db.execSQL("DELETE FROM vehicles WHERE plate = ?", arrayOf<Any?>(from))
    }

    /**
     * Operator override for a plate the engine got wrong, or a merge that should not have happened.
     * When the target already exists the two cars are merged; otherwise the row is simply renamed.
     *
     * Checked and changed inside one transaction, and judged by the rows that actually moved: the
     * automatic merge can rename the same row a moment before, and a rename that changed nothing
     * used to answer "done" anyway.
     */
    fun renamePlateManually(from: String, to: String, displayPlate: String): Boolean {
        if (from == to) return false
        val database = db
        database.beginTransaction()
        try {
            if (vehicle(from) == null) return false
            val changed = if (vehicle(to) != null) {
                mergeInto(from = from, into = to)
                vehicle(from) == null
            } else {
                renamePlate(from, to, displayPlate) > 0
            }
            database.setTransactionSuccessful()
            return changed
        } finally {
            database.endTransaction()
        }
    }

    /** Moves every row of one plate onto another. Called only when the target has no row yet. */
    private fun renamePlate(from: String, to: String, displayPlate: String): Int {
        val moved = db.compileStatement(
            "UPDATE vehicles SET plate = ?, display_plate = ? WHERE plate = ?",
        ).use { statement ->
            statement.bindString(1, to)
            statement.bindString(2, displayPlate)
            statement.bindString(3, from)
            statement.executeUpdateDelete()
        }
        db.execSQL("UPDATE encounters SET plate = ? WHERE plate = ?", arrayOf<Any?>(to, from))
        db.execSQL("UPDATE sightings SET plate = ? WHERE plate = ?", arrayOf<Any?>(to, from))
        return moved
    }

    /**
     * Writes what the follow engine currently knows about a vehicle.
     *
     * Every column here accumulates and none of them can legitimately shrink: turns are counted,
     * contact is banked, and the classifier's inputs only grow. A plain assignment therefore had a
     * nasty failure mode — a freshly started engine, which by definition knows nothing yet, wrote
     * zeros over an hour of collected evidence the moment it saw the car once. `MAX` makes the
     * write additive, so a restart mid-drive can only ever learn, never forget.
     *
     * `ignored` is the one thing that must be able to pull a level down, and it has its own
     * statement in [setIgnored].
     */
    fun updateEvidence(
        plate: String,
        sharedTurns: Int,
        reacquisitions: Int,
        contactMs: Long,
        contactM: Double,
        level: Int,
    ) {
        db.execSQL(
            "UPDATE vehicles SET shared_turns = MAX(shared_turns, ?), " +
                "reacquisitions = MAX(reacquisitions, ?), contact_ms = MAX(contact_ms, ?), " +
                "contact_m = MAX(contact_m, ?), " +
                "level = CASE WHEN ignored = 1 THEN 0 ELSE MAX(level, ?) END WHERE plate = ?",
            arrayOf<Any?>(sharedTurns, reacquisitions, contactMs, contactM, level, plate),
        )
    }

    /**
     * Marks a vehicle, creating its row if the camera has never read that plate.
     *
     * The operator can type a plate they read in the mirror, and a bare `UPDATE` silently changed
     * nothing for a car with no row yet — while the bot answered "⛔️ в чёрном списке". The mark
     * survived in memory until the next start and then vanished, which is the worst possible
     * outcome for the one control that exists to make a car impossible to miss.
     *
     * Taking a car off the list also takes the level the list gave it: the blacklist writes 4
     * into a column that otherwise only ever grows, and left there it came back as BLACKLIST on
     * the next start.
     */
    fun setBlacklisted(
        plate: String,
        blacklisted: Boolean,
        displayPlate: String? = null,
        note: String? = null,
    ) {
        ensureVehicleRow(plate, displayPlate)
        db.execSQL(
            "UPDATE vehicles SET blacklisted = ?, note = COALESCE(?, note), " +
                "level = CASE WHEN ? = 0 AND level >= 4 THEN 0 ELSE level END WHERE plate = ?",
            arrayOf<Any?>(if (blacklisted) 1 else 0, note, if (blacklisted) 1 else 0, plate),
        )
    }

    /** True when this plate already had a row, i.e. the camera has actually seen the car. */
    fun isKnown(plate: String): Boolean = db.rawQuery(
        "SELECT 1 FROM vehicles WHERE plate = ? LIMIT 1",
        arrayOf(plate),
    ).use { it.moveToFirst() }

    /**
     * Creates the minimum row a mark can hang on.
     *
     * The timestamps are zero, not now: this row records an operator's decision, not a sighting.
     * `vehiclesSeenSince` selects purely on `last_seen`, so a fresh timestamp made a car the camera
     * has never seen show up in the trip debrief as met — and, being blacklisted, counted among the
     * tails. The driver would be told a car had followed them on a drive where it never appeared.
     * The mark itself does not depend on the timestamps: retention only spares rows by their flags,
     * and the engine loads them by plate.
     */
    private fun ensureVehicleRow(plate: String, displayPlate: String?) {
        db.execSQL(
            "INSERT OR IGNORE INTO vehicles(plate, display_plate, first_seen, last_seen) " +
                "VALUES(?,?,0,0)",
            arrayOf<Any?>(plate, displayPlate ?: plate),
        )
    }

    fun blacklistedVehicles(): List<VehicleRow> = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE blacklisted = 1 ORDER BY last_seen DESC",
        null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
    }

    fun blacklistedPlates(): Set<String> = db.rawQuery(
        "SELECT plate FROM vehicles WHERE blacklisted = 1",
        null,
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /**
     * Marks a vehicle as police, or clears the mark.
     *
     * Deliberately a twin of [setBlacklisted] rather than a reuse of it: both shout, but the
     * driver needs to know *which* one is behind them, and a single flag could not answer that.
     */
    fun setPolice(plate: String, police: Boolean, displayPlate: String? = null) {
        ensureVehicleRow(plate, displayPlate)
        db.execSQL(
            "UPDATE vehicles SET police = ? WHERE plate = ?",
            arrayOf<Any?>(if (police) 1 else 0, plate),
        )
    }

    fun policeVehicles(): List<VehicleRow> = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE police = 1 ORDER BY last_seen DESC",
        null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
    }

    fun policePlates(): Set<String> = db.rawQuery(
        "SELECT plate FROM vehicles WHERE police = 1",
        null,
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /**
     * Dismisses a vehicle for good: the neighbour's car parked outside every morning is not a
     * follower, and one tap should stop it from ever raising an alarm again.
     */
    fun setIgnored(plate: String, ignored: Boolean, displayPlate: String? = null) {
        // Same reason as [setBlacklisted]: dismissing a car the camera has not read yet must
        // survive a restart, or the neighbour's car starts raising alarms again tomorrow.
        ensureVehicleRow(plate, displayPlate)
        db.execSQL(
            "UPDATE vehicles SET ignored = ?, level = CASE WHEN ? THEN 0 ELSE level END WHERE plate = ?",
            arrayOf<Any?>(if (ignored) 1 else 0, if (ignored) 1 else 0, plate),
        )
    }

    fun ignoredPlates(): Set<String> = db.rawQuery(
        "SELECT plate FROM vehicles WHERE ignored = 1",
        null,
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun ignoredVehicles(): List<VehicleRow> = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE ignored = 1 ORDER BY last_seen DESC",
        null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
    }

    /**
     * How many distinct places this vehicle has met us in.
     *
     * Two encounters on the same street are a neighbour; two encounters kilometres apart are the
     * thing worth waking the driver up for. Encounters are clustered greedily — good enough for a
     * handful of points per car, and no spatial index to maintain.
     */
    fun distinctPlaces(plate: String, radiusM: Double = PLACE_RADIUS_M): Int {
        val points = db.rawQuery(
            "SELECT start_lat, start_lon FROM encounters WHERE plate = ? AND start_lat IS NOT NULL " +
                "ORDER BY started_at DESC LIMIT ?",
            arrayOf(plate, PLACE_SCAN_LIMIT.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getDouble(0) to cursor.getDouble(1))
            }
        }
        val clusters = ArrayList<Pair<Double, Double>>()
        points.forEach { point ->
            val known = clusters.any { TripTracker.distanceMeters(it.first, it.second, point.first, point.second) <= radiusM }
            if (!known) clusters += point
        }
        return clusters.size
    }

    fun vehicle(plate: String): VehicleRow? = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE plate = ?",
        arrayOf(plate),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toVehicle() else null }

    fun vehicles(minLevel: Int = 0, limit: Int = 200): List<VehicleRow> = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE level >= ? OR blacklisted = 1 OR police = 1 " +
            // Level outranks the police flag: a patrol car is worth listing, but never at the
            // cost of pushing a confirmed tail past the point where the message is truncated.
            "ORDER BY blacklisted DESC, level DESC, police DESC, last_seen DESC LIMIT ?",
        arrayOf(minLevel.toString(), limit.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
    }

    fun vehiclesSeenSince(sinceMs: Long, limit: Int = 2_000): List<VehicleRow> = db.rawQuery(
        "$VEHICLE_COLUMNS WHERE last_seen >= ? ORDER BY level DESC, last_seen DESC LIMIT ?",
        arrayOf(sinceMs.toString(), limit.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
    }

    /** How many vehicles were seen in the window — the true count, not the size of a page. */
    fun countVehiclesSeenSince(sinceMs: Long): Int = db.rawQuery(
        "SELECT COUNT(*) FROM vehicles WHERE last_seen >= ?",
        arrayOf(sinceMs.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /**
     * Substring search over everything ever seen, for the bot.
     *
     * The query is reduced to the key alphabet first — no dashes, no spaces, look-alike Cyrillic
     * mapped to Latin — because the operator pastes the plate the way the app shows it, `EM-7209`,
     * and the keys are stored without the dash.
     */
    fun searchPlates(query: String, limit: Int = 20): List<VehicleRow> {
        val needle = com.lensalpr.app.alpr.PlateFormats.searchKey(query) ?: return emptyList()
        return db.rawQuery(
            "$VEHICLE_COLUMNS WHERE plate LIKE ? OR REPLACE(display_plate, '-', '') LIKE ? " +
                "ORDER BY last_seen DESC LIMIT ?",
            arrayOf("%$needle%", "%$needle%", limit.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toVehicle()) }
        }
    }

    /**
     * Encounters with one vehicle, oldest first.
     *
     * [sinceMs] is applied in SQL rather than by the caller: a car met fifty times over a month
     * would otherwise fill the limit with ancient rows and the report would show nothing at all for
     * the very vehicle it exists to describe.
     */
    fun encounters(
        plate: String,
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 500,
    ): List<EncounterRow> = db.rawQuery(
        "$ENCOUNTER_COLUMNS WHERE plate = ? AND ended_at >= ? AND started_at <= ? " +
            "ORDER BY started_at DESC LIMIT ?",
        arrayOf(plate, sinceMs.toString(), untilMs.toString(), limit.toString()),
    ).use { cursor ->
        // Newest first in SQL so the limit keeps the recent history, reversed here because every
        // caller wants it in the order it happened. Ascending with a limit would hand a long-lived
        // vehicle its fiftieth-oldest encounter and call it the latest.
        buildList { while (cursor.moveToNext()) add(cursor.toEncounter()) }.reversed()
    }

    fun countEncounters(plate: String, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): Int = db.rawQuery(
        "SELECT COUNT(*) FROM encounters WHERE plate = ? AND ended_at >= ? AND started_at <= ?",
        arrayOf(plate, sinceMs.toString(), untilMs.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun encountersSince(sinceMs: Long, limit: Int = 5_000): List<EncounterRow> = db.rawQuery(
        "$ENCOUNTER_COLUMNS WHERE ended_at >= ? ORDER BY started_at LIMIT ?",
        arrayOf(sinceMs.toString(), limit.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toEncounter()) }
    }

    // ----------------------------------------------------------------- admins

    fun admins(): List<AdminRow> = db.rawQuery(
        "SELECT chat_id, title, role, added_at FROM admins ORDER BY added_at",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(AdminRow(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
    }

    fun addAdmin(chatId: Long, title: String, role: String, nowMs: Long) {
        db.execSQL(
            "INSERT INTO admins(chat_id, title, role, added_at) VALUES(?,?,?,?) " +
                "ON CONFLICT(chat_id) DO UPDATE SET title = excluded.title, role = excluded.role",
            arrayOf<Any?>(chatId, title, role, nowMs),
        )
    }

    fun removeAdmin(chatId: Long): Boolean =
        db.delete("admins", "chat_id = ?", arrayOf(chatId.toString())) > 0

    // ------------------------------------------------------------------ misc

    /**
     * Keeps the evidence set bounded; plate data is personal data, so it does not live forever.
     *
     * [currentTripId] is the one trip that may stay open: any other trip without an end was left
     * behind by a dead process and expires like a finished one.
     */
    fun prune(olderThanMs: Long, currentTripId: Long = 0L) {
        val stale = db.rawQuery(
            "SELECT photo FROM encounters WHERE ended_at < ? AND photo IS NOT NULL",
            arrayOf(olderThanMs.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        stale.forEach { path -> runCatching { File(path).delete() } }
        db.execSQL("DELETE FROM sightings WHERE t_ms < ?", arrayOf<Any?>(olderThanMs))
        db.execSQL("DELETE FROM encounters WHERE ended_at < ?", arrayOf<Any?>(olderThanMs))
        db.execSQL("DELETE FROM track WHERE t_ms < ?", arrayOf<Any?>(olderThanMs))
        // Our own turns are a complete record of where the driver goes; they expire like the rest.
        db.execSQL("DELETE FROM turns WHERE t_ms < ?", arrayOf<Any?>(olderThanMs))
        db.execSQL(
            "DELETE FROM trips WHERE id != ? AND COALESCE(ended_at, started_at) < ?",
            arrayOf<Any?>(currentTripId, olderThanMs),
        )
        db.execSQL(
            // A police mark is a deliberate operator decision like the other two, so retention
            // must not quietly erase the row that carries it.
            "DELETE FROM vehicles WHERE blacklisted = 0 AND ignored = 0 AND police = 0 " +
                "AND plate NOT IN (SELECT DISTINCT plate FROM encounters)",
        )
        // Retention removed encounters and their photos; the per-vehicle counters were left behind
        // and kept claiming meetings whose rows and pictures no longer exist. That is one of the
        // ways "5 encounters, 3 photos" happened without anything actually going wrong.
        // The counts are exact: a row the operator typed by hand has met us on zero trips, and
        // pretending it was one made the report claim a meeting that never happened.
        db.execSQL(
            """
            UPDATE vehicles SET
                encounters = (SELECT COUNT(*) FROM encounters e WHERE e.plate = vehicles.plate),
                trips_seen = (SELECT COUNT(DISTINCT trip_id) FROM encounters e WHERE e.plate = vehicles.plate),
                sightings = (SELECT COUNT(*) FROM sightings s WHERE s.plate = vehicles.plate)
            """.trimIndent(),
        )
        // Photos whose encounter is gone — written after a crash, or linked to a row that was
        // deleted — were never reclaimed by anything. Only files the table does not mention.
        runCatching {
            val referenced = db.rawQuery("SELECT photo FROM encounters WHERE photo IS NOT NULL", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(File(cursor.getString(0)).name) }
            }
            photoDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in referenced && file.name.endsWith(".jpg") &&
                    System.currentTimeMillis() - file.lastModified() > ORPHAN_PHOTO_GRACE_MS
                ) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Erases every observation. Admin ids survive by default, otherwise a wipe from the bot would
     * lock everyone but the owner out of it.
     */
    fun wipeDatabase(keepAdmins: Boolean = true): DbWipe {
        val stats = DbWipe(
            vehicles = countRows("vehicles"),
            encounters = countRows("encounters"),
            sightings = countRows("sightings"),
            trips = countRows("trips"),
        )
        db.beginTransaction()
        try {
            listOf("sightings", "encounters", "vehicles", "track", "turns", "trips").forEach {
                db.execSQL("DELETE FROM $it")
            }
            if (!keepAdmins) db.execSQL("DELETE FROM admins")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Outside the transaction: SQLite refuses to vacuum inside one.
        runCatching { db.execSQL("VACUUM") }
        return stats
    }

    fun countRows(table: String): Int = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }.getOrDefault(0)

    fun databaseFile(): File = File(db.path)

    fun close() = runCatching { helper.close() }.let { }

    private fun Cursor.toVehicle() = VehicleRow(
        plate = getString(0),
        displayPlate = getString(1) ?: getString(0),
        make = getString(2),
        model = getString(3),
        year = getString(4),
        color = getString(5),
        body = getString(6),
        country = getString(7),
        firstSeen = getLong(8),
        lastSeen = getLong(9),
        sightings = getInt(10),
        encounters = getInt(11),
        tripsSeen = getInt(12),
        sharedTurns = getInt(13),
        reacquisitions = getInt(14),
        contactMs = getLong(15),
        contactM = getDouble(16),
        bestScore = getFloat(17),
        level = getInt(18),
        blacklisted = getInt(19) == 1,
        ignored = getInt(20) == 1,
        police = getInt(21) == 1,
        note = getString(22),
    )

    private fun Cursor.toEncounter() = EncounterRow(
        id = getLong(0),
        plate = getString(1),
        tripId = getLong(2),
        startedAt = getLong(3),
        endedAt = getLong(4),
        startLat = getDouble(5),
        startLon = getDouble(6),
        endLat = getDouble(7),
        endLon = getDouble(8),
        sightings = getInt(9),
        bestScore = getFloat(10),
        photo = getString(11),
        lens = getString(12),
        distanceM = getDouble(13),
        photoScore = getFloat(14),
        photoLens = getString(15),
        hasStart = !isNull(5) && !isNull(6),
    )

    private class OpenEncounter(
        val id: Long,
        val endedAt: Long,
        val photoScore: Float,
        val photo: String?,
    )

    private companion object {
        const val TAG = "LensALPR.Store"
        const val NAME = "lensalpr.db"
        const val VERSION = 5

        /** A gap longer than this starts a new encounter with the same vehicle. */
        const val ENCOUNTER_GAP_MS = 180_000L
        const val PHOTO_IMPROVEMENT = 6f

        /** How many recent cars a new reading is compared against before it becomes its own row. */
        const val MERGE_SCAN_LIMIT = 300

        /** Encounters closer than this are the same place, not a second meeting. */
        const val PLACE_RADIUS_M = 600.0
        const val PLACE_SCAN_LIMIT = 60

        /** A photo not yet linked to its row may still be mid-write; leave the fresh ones alone. */
        const val ORPHAN_PHOTO_GRACE_MS = 3_600_000L

        const val VEHICLE_COLUMNS =
            "SELECT plate, display_plate, make, model, year, color, body, country, first_seen, " +
                "last_seen, sightings, encounters, trips_seen, shared_turns, reacquisitions, " +
                "contact_ms, contact_m, best_score, level, blacklisted, ignored, police, note FROM vehicles"

        const val ENCOUNTER_COLUMNS =
            "SELECT id, plate, trip_id, started_at, ended_at, start_lat, start_lon, end_lat, end_lon, " +
                "sightings, best_score, photo, lens, distance_m, photo_score, photo_lens FROM encounters"
    }
}
