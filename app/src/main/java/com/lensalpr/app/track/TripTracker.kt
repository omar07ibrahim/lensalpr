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

/** A route decision we made: the event another driver has to copy in order to follow us. */
data class TurnEvent(
    val tMs: Long,
    val direction: TurnDirection,
    val degrees: Float,
    val odometerM: Double,
    val lat: Double,
    val lon: Double,
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

    private var lastLocation: Location? = null
    private var lastBearing: Float? = null
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

    @Volatile
    var current: GeoFix? = null
        private set

    @Volatile
    var odometerM: Double = 0.0
        private set

    val turnCount: Int get() = turnTimes.size

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
        }.onFailure { error ->
            Log.w(TAG, "location updates unavailable", error)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { client.removeLocationUpdates(callback) }
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
        val previous = lastLocation
        if (previous != null && location.accuracy < MAX_ACCURACY_M) {
            val step = distanceMeters(
                previous.latitude,
                previous.longitude,
                location.latitude,
                location.longitude,
            )
            if (step in MIN_STEP_M..MAX_STEP_M) odometerM += step
        }
        lastLocation = location

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
            if (inManoeuvre) finishManoeuvre()
            return
        }
        val bearing = location.bearing
        val previous = lastBearing
        lastBearing = bearing
        if (previous == null) return
        val delta = signedDelta(previous, bearing)

        if (inManoeuvre) {
            manoeuvreTotal += delta
            // A roundabout can swing past 180 and come back; the extremum is the manoeuvre that was
            // actually driven, while the instantaneous sum at the moment of the deadline is not.
            if (abs(manoeuvreTotal) > abs(manoeuvrePeak)) manoeuvrePeak = manoeuvreTotal
            calmSamples = if (abs(delta) < CALM_DEGREES) calmSamples + 1 else 0
            if (calmSamples >= CALM_SAMPLES || now - manoeuvreStartMs > MANOEUVRE_MAX_MS) {
                finishManoeuvre()
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

    /** Publishes the manoeuvre that has just ended, unless the heading came back where it started. */
    private fun finishManoeuvre() {
        val total = if (abs(manoeuvrePeak) > abs(manoeuvreTotal)) manoeuvrePeak else manoeuvreTotal
        inManoeuvre = false
        manoeuvreTotal = 0f
        manoeuvrePeak = 0f
        calmSamples = 0
        headingWindow.clear()
        // A swerve around a pothole crosses the threshold and comes straight back; it is not a turn.
        if (abs(total) < TURN_DEGREES) return

        turnTimes += manoeuvreStartMs
        if (turnTimes.size > MAX_TURNS) turnTimes.subList(0, 100).clear()
        val direction = when {
            abs(total) >= U_TURN_DEGREES -> TurnDirection.U_TURN
            total > 0f -> TurnDirection.RIGHT
            else -> TurnDirection.LEFT
        }
        Log.i(TAG, "turn ${direction.name} ${total.toInt()}deg")
        onTurn(
            TurnEvent(
                tMs = manoeuvreStartMs,
                direction = direction,
                degrees = total,
                odometerM = odometerM,
                lat = manoeuvreLat,
                lon = manoeuvreLon,
            ),
        )
    }

    companion object {
        private const val TAG = "LensALPR.Trip"
        private const val MIN_STEP_M = 1.5
        private const val MAX_STEP_M = 120.0
        private const val MAX_ACCURACY_M = 35f
        private const val MAX_SAMPLES = 7_200
        private const val MAX_TURNS = 1_000

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
