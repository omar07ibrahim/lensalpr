package com.lensalpr.app.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One position sample of our own vehicle. */
data class GeoFix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Float,
    val speedMps: Float,
    val accuracyM: Float,
    val odometerM: Double,
)

enum class TurnDirection { LEFT, RIGHT, U_TURN }

/**
 * A route decision we made: the event another driver has to copy in order to follow us.
 *
 * [tMs] is when the heading started to swing and [endMs] when it settled again. Both matter to the
 * follow engine: a car seen *before* the start took the turn with us only if it is seen again
 * *after* the end, and a car first seen during the manoeuvre proves nothing either way.
 */
data class TurnEvent(
    val tMs: Long,
    val direction: TurnDirection,
    val degrees: Float,
    val odometerM: Double,
    val lat: Double,
    val lon: Double,
    val endMs: Long = tMs,
)

/**
 * Tracks our own movement: trip odometer, heading and the turns we take.
 *
 * Turns are the backbone of follow detection. A vehicle staying behind us on a straight road proves
 * nothing; a vehicle that reproduces three of our turns is no longer a coincidence. Heading is only
 * trusted above [MIN_TURN_SPEED_MPS] because GNSS bearing is noise at walking speed.
 */
class TripTracker(
    private val context: Context,
    private val onFix: (GeoFix) -> Unit,
    private val onTurn: (TurnEvent) -> Unit,
) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val headingWindow = ArrayDeque<Pair<Long, Float>>()
    private val turnTimes = ArrayList<Long>()
    private val odometerSamples = ArrayList<Pair<Long, Double>>()

    /** The last fix the odometer accepted; the base of the next measured step. */
    private var lastAccepted: Location? = null

    /** When a fix last passed the accuracy gate, i.e. when distance was last actually measured. */
    private var lastMeasuredMs = 0L
    private var lastBearing: Float? = null

    /** When [lastBearing] was measured; a heading from before a GPS outage is not a heading. */
    private var lastBearingAtMs = 0L
    private var running = false

    /** A heading swing is in progress; samples accumulate until it settles. */
    private var inManoeuvre = false
    private var manoeuvreTotal = 0f

    /** The furthest the heading swung during this manoeuvre, which is the turn that was driven. */
    private var manoeuvrePeak = 0f
    private var manoeuvreStartMs = 0L
    private var manoeuvreLat = 0.0
    private var manoeuvreLon = 0.0
    private var calmSamples = 0

    /** Every turn of the trip, including the ones whose timestamps have been pruned. */
    private var totalTurns = 0

    @Volatile
    var current: GeoFix? = null
        private set

    @Volatile
    var odometerM: Double = 0.0
        private set

    val turnCount: Int get() = totalTurns

    /** Location updates were requested and accepted; false means there is no speed source at all. */
    val isRunning: Boolean get() = running

    /**
     * Whether the odometer is a measurement rather than a placeholder.
     *
     * Everything the follow engine says about distance rests on this. Without a fix the odometer
     * reads a flat zero, which is indistinguishable from a car standing still — and treating one
     * as the other is what makes the whole tail detector go quiet without a word.
     */
    val hasOdometer: Boolean get() = running && current != null

    /**
     * Whether distance is being measured *right now*: a fix the odometer accepted, newer than
     * [maxAgeMs].
     *
     * One fix at the start of the drive and nothing since is not an odometer; the follow engine
     * has to fall back to time and sightings again when the feed goes away, or a phone that lost
     * GPS in a tunnel spends the rest of the drive demanding kilometres that will never come.
     * "Accepted" matters: in a tunnel or a garage the fused provider keeps delivering network
     * fixes hundreds of metres wide, which [advanceOdometer] rightly ignores — counting them as
     * measuring left the engine demanding distance from an odometer that stood still.
     */
    fun hasFreshFix(nowMs: Long, maxAgeMs: Long): Boolean =
        running && lastMeasuredMs != 0L && nowMs - lastMeasuredMs <= maxAgeMs

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::consume)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                // The request is accepted asynchronously; a refusal arrives here, not as a throw.
                .addOnFailureListener { error ->
                    Log.w(TAG, "location updates refused", error)
                    running = false
                }
        }.onFailure { error ->
            Log.w(TAG, "location updates unavailable", error)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { client.removeLocationUpdates(callback) }
        // The next start is a new stretch of road: the last heading and the last accepted point
        // belong to wherever the car went while nobody was measuring.
        forgetHeading()
        lastAccepted = null
        lastMeasuredMs = 0L
    }

    /**
     * Continues a trip that a previous process handed over.
     *
     * The restart keeps the trip id, so it has to keep the numbers that describe the trip too;
     * the restored turns carry no timestamps and therefore never count as "since" anything.
     */
    fun restore(odometerM: Double, turns: Int) {
        if (odometerM > this.odometerM) this.odometerM = odometerM
        totalTurns += turns.coerceAtLeast(0)
    }

    /**
     * Starts the trip counters over — after a wipe, when the operator asked for a clean slate and
     * the new trip row must not inherit the old trip's kilometres and turns.
     */
    fun resetTrip() {
        odometerM = 0.0
        totalTurns = 0
        turnTimes.clear()
        odometerSamples.clear()
        forgetHeading()
        lastAccepted = null
        current?.let { fix -> current = fix.copy(odometerM = 0.0) }
    }

    /** Turns taken since [tMs]; the core follow-detection input. */
    fun turnsSince(tMs: Long): Int = turnTimes.count { it >= tMs }

    /** Metres travelled since [tMs], interpolated from the odometer samples. */
    fun distanceSince(tMs: Long): Double {
        val start = odometerSamples.firstOrNull { it.first >= tMs }?.second
            ?: return 0.0
        return (odometerM - start).coerceAtLeast(0.0)
    }

    private fun consume(location: Location) {
        // One clock for the whole app: the follow engine compares these stamps against
        // System.currentTimeMillis(), and a GNSS fix carrying a different notion of "now" would
        // silently shift every turn window.
        val now = System.currentTimeMillis()
        advanceOdometer(location, now)

        odometerSamples += now to odometerM
        if (odometerSamples.size > MAX_SAMPLES) odometerSamples.subList(0, 600).clear()

        val fix = GeoFix(
            tMs = now,
            lat = location.latitude,
            lon = location.longitude,
            bearingDeg = if (location.hasBearing()) location.bearing else lastBearing ?: 0f,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            accuracyM = location.accuracy,
            odometerM = odometerM,
        )
        current = fix
        onFix(fix)

        detectTurn(location, now, fix)
    }

    /**
     * Adds the distance from the last *accepted* point, never from the last *received* one.
     *
     * The old rule replaced the base with every fix, accepted or not. A rejected 100 m jump while
     * parked then became the base of the next step, and the return to the true position was
     * booked as 100 m driven; and at a crawl every 1 m step fell under the floor, so a kilometre of
     * traffic jam measured nothing at all.
     */
    private fun advanceOdometer(location: Location, nowMs: Long) {
        if (location.accuracy >= MAX_ACCURACY_M) return
        lastMeasuredMs = nowMs
        val base = lastAccepted
        if (base == null) {
            lastAccepted = location
            return
        }
        val step = distanceMeters(base.latitude, base.longitude, location.latitude, location.longitude)
        val elapsedS = (location.elapsedRealtimeNanos - base.elapsedRealtimeNanos) / 1_000_000_000.0
        val moving = location.hasSpeed() && location.speed >= MIN_MOVING_SPEED_MPS
        when {
            // A jump nothing on the road can explain: a multipath hop. Take the new position as
            // the base without booking the distance.
            step > MAX_STEP_M && (elapsedS <= 0.0 || step > elapsedS * MAX_PLAUSIBLE_SPEED_MPS) ->
                lastAccepted = location

            step >= MIN_STEP_M -> {
                odometerM += step
                lastAccepted = location
            }

            // Below the floor. While actually moving the small increments are left to add up to
            // one step; while standing still the base follows the jitter so that noise around a
            // parked car does not slowly walk the odometer forward.
            !moving -> lastAccepted = location
        }
    }

    /**
     * Detects one turn per turn.
     *
     * The naive version — emit as soon as the heading has swung past a threshold, then start again
     * from zero — fires two or three times at a single junction, because a real 90 degree turn keeps
     * turning after the first 40 degrees. Every one of those events then credits every car behind us
     * with another "shared turn", which is the strongest evidence the follow engine has.
     *
     * So a turn is treated as a manoeuvre with a beginning and an end: once the heading starts
     * swinging the samples are accumulated until it settles again, and exactly one event is emitted,
     * carrying the total. That also restores the U-turn, which the old code could never see: it
     * emitted at 40 degrees and threw the remaining 140 away.
     */
    private fun detectTurn(location: Location, now: Long, fix: GeoFix) {
        val usable = location.hasBearing() &&
            fix.speedMps >= MIN_TURN_SPEED_MPS &&
            location.accuracy <= MAX_TURN_ACCURACY_M
        if (!usable) {
            // Stopping mid-junction ends the manoeuvre as surely as straightening out does.
            if (inManoeuvre) finishManoeuvre(now, settled = false)
            return
        }
        val bearing = location.bearing
        // A heading measured before a long silence — a tunnel, a garage, a stretch of bad fixes —
        // is not the heading we had a moment ago. Comparing against it turned the whole outage
        // into one instantaneous swing and a turn nobody took.
        if (lastBearing != null && now - lastBearingAtMs > BEARING_STALE_MS) {
            if (inManoeuvre) finishManoeuvre(now, settled = false)
            forgetHeading()
        }
        val previous = lastBearing
        lastBearing = bearing
        lastBearingAtMs = now
        if (previous == null) return
        val delta = signedDelta(previous, bearing)

        if (inManoeuvre) {
            manoeuvreTotal += delta
            // A roundabout can swing past 180 and come back; the extremum is the manoeuvre that was
            // actually driven, while the instantaneous sum at the moment of the deadline is not.
            if (abs(manoeuvreTotal) > abs(manoeuvrePeak)) manoeuvrePeak = manoeuvreTotal
            calmSamples = if (abs(delta) < CALM_DEGREES) calmSamples + 1 else 0
            if (calmSamples >= CALM_SAMPLES) {
                finishManoeuvre(now, settled = true)
            } else if (now - manoeuvreStartMs > MANOEUVRE_MAX_MS) {
                finishManoeuvre(now, settled = false)
            }
            return
        }

        // After a manoeuvre the heading has to be genuinely straight again before a new one may
        // arm. Without this the tail of a long junction - or the exit of a roundabout that was
        // force-closed on the deadline - immediately counts as a second turn, and every car behind
        // us collects a second shared turn for a junction we took once.
        if (calmSamples < CALM_SAMPLES) {
            calmSamples = if (abs(delta) < CALM_DEGREES) calmSamples + 1 else 0
            headingWindow.clear()
            return
        }

        headingWindow.addLast(now to delta)
        while (headingWindow.isNotEmpty() && now - headingWindow.first().first > TURN_WINDOW_MS) {
            headingWindow.removeFirst()
        }
        val total = headingWindow.sumOf { it.second.toDouble() }.toFloat()
        if (abs(total) < TURN_DEGREES) return

        inManoeuvre = true
        manoeuvreTotal = total
        manoeuvrePeak = total
        manoeuvreStartMs = headingWindow.first().first
        manoeuvreLat = fix.lat
        manoeuvreLon = fix.lon
        calmSamples = 0
        headingWindow.clear()
    }

    /**
     * Publishes the manoeuvre that has just ended, unless the heading came back where it started.
     *
     * A manoeuvre that [settled] on its own is judged by its *net* change of heading: a swerve
     * that swung sixty degrees and came straight back is not a turn, however far it peaked. One
     * closed by the deadline or by a loss of GPS is judged by its peak, because a roundabout that
     * is still being circled has no net heading yet. And a manoeuvre that settled has already
     * proven the road straight again — the calm samples that ended it are kept, so a second
     * junction a few seconds down the road is not swallowed by a second wait for calm.
     */
    private fun finishManoeuvre(now: Long, settled: Boolean) {
        val total = if (settled) manoeuvreTotal else {
            if (abs(manoeuvrePeak) > abs(manoeuvreTotal)) manoeuvrePeak else manoeuvreTotal
        }
        val startMs = manoeuvreStartMs
        inManoeuvre = false
        manoeuvreTotal = 0f
        manoeuvrePeak = 0f
        calmSamples = if (settled) CALM_SAMPLES else 0
        headingWindow.clear()
        // A swerve around a pothole crosses the threshold and comes straight back; it is not a turn.
        if (abs(total) < TURN_DEGREES) return

        totalTurns += 1
        turnTimes += startMs
        if (turnTimes.size > MAX_TURNS) turnTimes.subList(0, 100).clear()
        val direction = when {
            abs(total) >= U_TURN_DEGREES -> TurnDirection.U_TURN
            total > 0f -> TurnDirection.RIGHT
            else -> TurnDirection.LEFT
        }
        Log.i(TAG, "turn ${direction.name} ${total.toInt()}deg")
        onTurn(
            TurnEvent(
                tMs = startMs,
                direction = direction,
                degrees = total,
                odometerM = odometerM,
                lat = manoeuvreLat,
                lon = manoeuvreLon,
                endMs = now,
            ),
        )
    }

    private fun forgetHeading() {
        lastBearing = null
        lastBearingAtMs = 0L
        headingWindow.clear()
        calmSamples = 0
        inManoeuvre = false
        manoeuvreTotal = 0f
        manoeuvrePeak = 0f
    }

    companion object {
        private const val TAG = "LensALPR.Trip"
        private const val MIN_STEP_M = 1.5
        private const val MAX_STEP_M = 120.0
        private const val MAX_ACCURACY_M = 35f
        private const val MAX_SAMPLES = 7_200
        private const val MAX_TURNS = 1_000

        /** Faster than this between two fixes is not driving, it is a GPS hop. */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 70.0

        /** Reported speed below which the car is taken to be standing and jitter is not distance. */
        private const val MIN_MOVING_SPEED_MPS = 0.7f

        /** A heading older than this has nothing to say about the current one. */
        private const val BEARING_STALE_MS = 10_000L

        /** Below this speed GNSS bearing is noise, not a manoeuvre. */
        private const val MIN_TURN_SPEED_MPS = 3f

        /** Beyond this the fix is too vague to tell a manoeuvre from multipath. */
        private const val MAX_TURN_ACCURACY_M = 25f

        /**
         * A junction is taken in a handful of seconds. With a long window the gentle arc of a
         * motorway interchange accumulates past the threshold and every fellow traveller starts
         * collecting "shared turns" for driving down the same road.
         */
        private const val TURN_WINDOW_MS = 8_000L

        /** Per-sample heading change small enough to count as driving straight again. */
        private const val CALM_DEGREES = 4f

        /** Consecutive calm samples that end a manoeuvre; the fix rate is about one per second. */
        private const val CALM_SAMPLES = 3

        /** A manoeuvre that never settles — a roundabout entered and circled — is closed anyway. */
        private const val MANOEUVRE_MAX_MS = 15_000L
        private const val TURN_DEGREES = 40f
        private const val U_TURN_DEGREES = 150f

        /** Signed shortest angular difference, positive clockwise (to the right). */
        fun signedDelta(from: Float, to: Float): Float {
            var delta = (to - from) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return delta
        }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earth = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
