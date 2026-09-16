package com.lensalpr.app.report

import android.content.Context
import android.util.Base64
import com.lensalpr.app.data.EncounterRow
import com.lensalpr.app.data.TrackingStore
import com.lensalpr.app.follow.ThreatLevel
import com.lensalpr.app.track.TripTracker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the self-contained evidence report that gets sent to Telegram.
 *
 * One HTML file, no external assets except the map tiles: Leaflet is inlined from the APK and every
 * photo is embedded as a data URI.
 *
 * Three views of the same evidence, because a tail is a claim about *time and route*, not a list of
 * plates:
 *  * the **map** carries our track, every turn we took and where each vehicle appeared, with the
 *    encounters of one plate joined by a line - "seen here, then again there" in one glance;
 *  * the **timeline** puts every vehicle on the same clock, which is what makes a car that keeps
 *    coming back across the trip impossible to miss;
 *  * the **cards** hold the photos and the concrete reasons behind each level.
 *
 * Severity colours were re-stepped against the CVD validator: the previous amber/orange pair for
 * "watch" and "suspect" measured ΔE 7 for normal vision and 3.5 under deuteranopia - the two levels
 * that matter most were the two nobody could tell apart. Every level also always carries its name
 * as text, so colour is never the only encoding.
 */
class HtmlReportBuilder(
    private val context: Context,
    private val store: TrackingStore,
) {

    data class Options(
        val sinceMs: Long,
        /** When set, the report covers this plate alone: its encounters, its route, its photos. */
        val plate: String? = null,
        val untilMs: Long = System.currentTimeMillis(),
        val tripIds: List<Long> = emptyList(),
        val minLevel: ThreatLevel = ThreatLevel.IGNORE,
        val maxPhotosPerVehicle: Int = 8,
        val maxPhotoBytes: Long = 12L * 1024 * 1024,
        /**
         * How each cropping strategy performed during the session, when it was measured.
         *
         * The comparison decides a real setting and only exists in memory, so it died with the
         * session and never reached the one artefact the operator can actually keep and send on.
         * A measurement nobody can read afterwards is not a measurement.
         */
        val cropStats: CropReport? = null,
    )

    /** One arm of the crop-strategy comparison, as the report shows it. */
    data class CropArm(val sent: Int, val hits: Int, val avgLatencyMs: Long) {
        val percent: Int get() = if (sent == 0) 0 else hits * 100 / sent
    }

    data class CropReport(val wide: CropArm, val narrow: CropArm, val mode: String)

    fun build(options: Options): File {
        // Three threads can be building reports at once and the stamp has minute resolution: an
        // alert's own report and a tap on the report button would otherwise write the same file.
        val stamp = FILE_STAMP.format(zoned(options.untilMs))
        val unique = (System.nanoTime() % 100_000L).toString().padStart(5, '0')
        val target = File(context.cacheDir, "lensalpr_${stamp}_$unique.html")

        val vehicles = if (options.plate != null) {
            listOfNotNull(store.vehicle(options.plate))
        } else {
            store.vehiclesSeenSince(options.sinceMs)
                .filter { it.level >= options.minLevel.rank || it.blacklisted || it.police }
        }
        // Our own route belongs to the trips that overlap the window, whether or not a single
        // plate was read on them: a drive with nobody behind us still has a map and a distance.
        val tripIds = options.tripIds.ifEmpty {
            if (options.plate != null) {
                store.encounters(options.plate, options.sinceMs, options.untilMs).map { it.tripId }.distinct()
            } else {
                store.tripsBetween(options.sinceMs, options.untilMs)
            }
        }

        var photoBudget = options.maxPhotoBytes
        val counts = IntArray(5)
        var earliest = options.untilMs
        val vehiclesJson = JSONArray()

        vehicles.forEach { vehicle ->
            val encounters = store.encounters(vehicle.plate, options.sinceMs, options.untilMs)
            if (encounters.isEmpty()) return@forEach
            val level = when {
                // Dismissed by the operator: whatever the list or the level says, it is not a
                // tail in this report.
                vehicle.ignored -> ThreatLevel.IGNORE
                vehicle.blacklisted -> ThreatLevel.BLACKLIST
                else -> ThreatLevel.of(minOf(vehicle.level, ThreatLevel.TAIL.rank))
            }
            counts[level.rank] += 1
            earliest = minOf(earliest, encounters.first().startedAt)

            // The photo budget has to be spent on the most recent meetings. Both the per-vehicle
            // cap and the global byte budget are therefore consumed newest first; the cards are
            // still laid out in the order the meetings happened.
            val embedded = HashMap<Long, String>()
            val skippedByBudget = HashSet<Long>()
            encounters.asReversed()
                .filter { !it.photo.isNullOrBlank() }
                .take(options.maxPhotosPerVehicle)
                .forEach { encounter ->
                    val path = encounter.photo ?: return@forEach
                    if (photoBudget <= 0) {
                        skippedByBudget += encounter.id
                        return@forEach
                    }
                    val data = embedPhoto(File(path))
                    if (data == null) return@forEach
                    photoBudget -= data.length
                    embedded[encounter.id] = data
                }
            val encountersJson = JSONArray()
            encounters.forEach { encounter ->
                val photo = embedded[encounter.id]
                val skipped = encounter.id in skippedByBudget ||
                    (photo == null && !encounter.photo.isNullOrBlank() && File(encounter.photo).exists())
                encountersJson.put(encounterJson(encounter, photo, skipped))
            }
            val totalInWindow = store.countEncounters(vehicle.plate, options.sinceMs, options.untilMs)

            vehiclesJson.put(
                JSONObject()
                    .put("plate", vehicle.displayPlate)
                    .put("key", vehicle.plate)
                    .put("level", level.rank)
                    .put("levelName", levelName(level))
                    .put("police", vehicle.police)
                    .put("makeModel", vehicle.makeModel ?: "")
                    .put("color", vehicle.color ?: "")
                    .put("country", vehicle.country ?: "")
                    .put("sightings", vehicle.sightings)
                    .put("encounters", vehicle.encounters)
                    // The counter above covers the vehicle's whole history; the tiles below only
                    // cover the report window, and a photo only exists where one could be taken.
                    // Carrying all three numbers is what lets the card explain itself instead of
                    // looking like it lost pictures.
                    .put("encountersShown", encounters.size)
                    .put("encountersInWindow", totalInWindow)
                    // Photos actually embedded, not photos that exist somewhere. A file the size
                    // budget skipped or that failed to load is a picture the reader cannot see,
                    // and counting it would reproduce the very mismatch this number exists to
                    // explain.
                    .put("photosShown", embedded.size)
                    .put("turns", vehicle.sharedTurns)
                    .put("score", vehicle.bestScore.toInt())
                    // Zero means the row was created by a hand-typed mark and the camera has not
                    // read the plate yet. The timestamp format carries no year, so 1970 would
                    // print as a perfectly plausible "01.01 03:00" — a report handed to somebody
                    // as evidence must not invent a first contact.
                    .put(
                        "first",
                        if (vehicle.firstSeen > 0L) time(vehicle.firstSeen) else "—",
                    )
                    .put("last", time(vehicle.lastSeen))
                    .put("lastMs", vehicle.lastSeen)
                    .put("ignored", vehicle.ignored)
                    .put(
                        "reasons",
                        JSONArray(
                            // A dismissed car still belongs in the report; it just says why it is quiet.
                            if (vehicle.ignored) {
                                listOf("помечен как «свой»") + reasons(vehicle.plate, level)
                            } else {
                                reasons(vehicle.plate, level)
                            },
                        ),
                    )
                    .put("encountersData", encountersJson),
            )
        }

        val trackJson = JSONArray()
        var distanceM = 0.0
        tripIds.forEach { tripId ->
            val points = store.trackPoints(tripId).filter { it.tMs in options.sinceMs..options.untilMs }
            if (points.size < 2) return@forEach
            val leg = JSONArray()
            points.forEach { point -> leg.put(JSONArray().put(round6(point.lat)).put(round6(point.lon))) }
            distanceM += legDistance(points)
            trackJson.put(leg)
        }

        // Only our own trips' turns: a dossier about one car must not carry every junction of
        // every other drive, and the map must not be framed around them.
        val turns = store.turnsBetween(options.sinceMs, options.untilMs, tripIds.takeIf { it.isNotEmpty() })
        val turnsJson = JSONArray()
        turns.forEach { turn ->
            turnsJson.put(
                JSONObject()
                    .put("lat", round6(turn.lat))
                    .put("lon", round6(turn.lon))
                    .put("t", time(turn.tMs))
                    .put("dir", directionName(turn.direction)),
            )
        }

        // A per-vehicle report asks for everything ever (sinceMs = 0); taking the minimum would
        // then date the header to 1970 and squash the timeline against its right edge.
        val windowFrom = if (options.sinceMs <= 0L) {
            earliest.coerceAtMost(options.untilMs - 1)
        } else {
            minOf(earliest, options.sinceMs).coerceAtMost(options.untilMs - 1)
        }
        val stats = JSONObject()
            .put("vehicles", vehiclesJson.length())
            .put("tail", counts[ThreatLevel.TAIL.rank] + counts[ThreatLevel.BLACKLIST.rank])
            .put("suspect", counts[ThreatLevel.SUSPECT.rank])
            .put("watch", counts[ThreatLevel.WATCH.rank])
            .put("turns", turns.size)
            .put("km", round1(distanceM / 1000.0))
            .put("minutes", ((options.untilMs - windowFrom) / 60_000).coerceAtLeast(0))

        val crops = options.cropStats?.let { report ->
            JSONObject()
                .put("mode", report.mode)
                .put("wideSent", report.wide.sent)
                .put("wideHits", report.wide.hits)
                .put("widePercent", report.wide.percent)
                .put("wideMs", report.wide.avgLatencyMs)
                .put("narrowSent", report.narrow.sent)
                .put("narrowHits", report.narrow.hits)
                .put("narrowPercent", report.narrow.percent)
                .put("narrowMs", report.narrow.avgLatencyMs)
        }

        val payload = JSONObject()
            .put("generated", time(options.untilMs))
            .put("from", time(windowFrom))
            .put("windowFrom", windowFrom)
            .put("windowTo", options.untilMs)
            .put("stats", stats)
            .put("crops", crops ?: JSONObject.NULL)
            .put("vehicles", vehiclesJson)
            .put("track", trackJson)
            .put("turns", turnsJson)
            .toString()
            // A literal "</script>" inside the data would end the tag early.
            .replace("<", "\\u003c")

        target.writeText(document(payload), Charsets.UTF_8)
        return target
    }

    /**
     * Kilometres of one leg of our route.
     *
     * From the validated odometer stored with the points when the rows carry it: the live
     * odometer already rejected the GPS hops, and summing raw point-to-point distances put them
     * straight back — a parked phone produced a ten-kilometre report. Older rows without an
     * odometer fall back to the sum, minus any step no car could have driven.
     */
    private fun legDistance(points: List<com.lensalpr.app.data.TrackPoint>): Double {
        val odometer = points.map { it.odometerM }.filter { it.isFinite() }
        if (odometer.size >= 2) return (odometer.max() - odometer.min()).coerceAtLeast(0.0)
        var sum = 0.0
        for (index in 1 until points.size) {
            val step = TripTracker.distanceMeters(
                points[index - 1].lat, points[index - 1].lon, points[index].lat, points[index].lon,
            )
            val seconds = (points[index].tMs - points[index - 1].tMs) / 1000.0
            if (seconds > 0 && step / seconds > MAX_PLAUSIBLE_SPEED_MPS) continue
            sum += step
        }
        return sum
    }

    private fun encounterJson(encounter: EncounterRow, photo: String?, skipped: Boolean): JSONObject = JSONObject()
        .put("id", encounter.id)
        .put("start", time(encounter.startedAt))
        .put("end", time(encounter.endedAt))
        .put("startMs", encounter.startedAt)
        .put("endMs", encounter.endedAt)
        .put("minutes", ((encounter.endedAt - encounter.startedAt) / 60_000).toInt())
        .put("hasPos", encounter.hasStart)
        .put("lat", round6(encounter.startLat))
        .put("lon", round6(encounter.startLon))
        .put("elat", round6(encounter.endLat))
        .put("elon", round6(encounter.endLon))
        // The lens the photo was taken with when there is a photo; the encounter's first lens
        // otherwise. A replaced photo used to keep the caption of the frame it replaced.
        .put("lens", (if (photo != null) encounter.photoLens else null) ?: encounter.lens ?: "")
        .put("sightings", encounter.sightings)
        .put("score", encounter.bestScore.toInt())
        .put("photo", photo ?: "")
        .put("photoSkipped", skipped)

    private fun reasons(plate: String, level: ThreatLevel): List<String> {
        val vehicle = store.vehicle(plate) ?: return emptyList()
        return buildList {
            if (vehicle.blacklisted) add("в чёрном списке")
            if (vehicle.police) add("полиция")
            if (vehicle.sharedTurns > 0) add("${vehicle.sharedTurns} общих поворотов")
            if (vehicle.tripsSeen > 1) add("${vehicle.tripsSeen} разные поездки")
            if (vehicle.reacquisitions > 0) add("${vehicle.reacquisitions}× терялся и возвращался")
            if (vehicle.encounters > 1) add("${vehicle.encounters} встреч")
            if (vehicle.contactMs >= 60_000) add("${vehicle.contactMs / 60_000} мин контакта")
            if (vehicle.contactM >= 500) add("${round1(vehicle.contactM / 1000.0)} км рядом")
            if (isEmpty() && level == ThreatLevel.IGNORE) add("однократный контакт")
        }
    }

    private fun embedPhoto(file: File): String? {
        if (!file.exists() || file.length() > MAX_SINGLE_PHOTO) return null
        return runCatching {
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun asset(name: String): String =
        context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun levelName(level: ThreatLevel): String = when (level) {
        ThreatLevel.BLACKLIST -> "ЧЁРНЫЙ СПИСОК"
        ThreatLevel.TAIL -> "ХВОСТ"
        ThreatLevel.SUSPECT -> "ПОДОЗРЕНИЕ"
        ThreatLevel.WATCH -> "НАБЛЮДЕНИЕ"
        ThreatLevel.IGNORE -> "контакт"
    }

    private fun directionName(direction: String): String = when (direction) {
        "LEFT" -> "налево"
        "RIGHT" -> "направо"
        "U_TURN" -> "разворот"
        else -> "поворот"
    }

    private fun round1(value: Double) = Math.round(value * 10.0) / 10.0
    private fun round6(value: Double) = Math.round(value * 1_000_000.0) / 1_000_000.0

    private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    private fun time(ms: Long): String = TIME.format(zoned(ms))

    private fun document(payload: String): String = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LensALPR — отчёт</title>
<style>${asset("web/leaflet.css")}</style>
<style>
:root {
  color-scheme: dark;
  --bg:#0b0d10; --panel:#151a20; --card:#1b2027; --line:#2a313a;
  --ink:#eef2f6; --ink2:#9aa6b2; --ink3:#6b7885; --route:#35D07F;
  --l0:#8FA0B4; --l1:#4DA3FF; --l2:#F2C230; --l3:#FF4D4D; --l4:#C77DFF;
}
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink);
  font:14px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,sans-serif; }
header { padding:16px 18px 12px; border-bottom:1px solid var(--line); }
h1 { font-size:17px; margin:0 0 2px; letter-spacing:.2px; }
.period { color:var(--ink2); font-size:12px; }
.tiles { display:flex; gap:10px; flex-wrap:wrap; margin-top:12px; }
.tile { background:var(--panel); border:1px solid var(--line); border-radius:12px;
  padding:9px 14px; min-width:94px; }
.tile b { display:block; font-size:22px; line-height:1.15; font-variant-numeric:tabular-nums; }
.tile span { color:var(--ink2); font-size:11px; }
.bar { padding:10px 18px; display:flex; gap:8px; align-items:center; flex-wrap:wrap;
  border-bottom:1px solid var(--line); position:sticky; top:0; background:var(--bg); z-index:600; }
.chip { border:1px solid var(--line); background:var(--panel); color:var(--ink2);
  border-radius:999px; padding:5px 12px; font-size:12px; cursor:pointer; user-select:none;
  font:inherit; font-size:12px; }
.chip[aria-pressed="true"] { color:var(--ink); }
.chip:focus-visible { outline:2px solid var(--l1); outline-offset:2px; }
.chip i { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px;
  vertical-align:1px; }
#q { margin-left:auto; background:var(--panel); border:1px solid var(--line); color:var(--ink);
  border-radius:999px; padding:6px 14px; font-size:12px; min-width:140px; outline:none; }
#map { height:56vh; min-height:320px; }
section { padding:16px 18px; }
h2 { font-size:12px; text-transform:uppercase; letter-spacing:.8px; color:var(--ink2);
  margin:0 0 10px; font-weight:600; }
.tl { background:var(--panel); border:1px solid var(--line); border-radius:14px; padding:12px; }
.tlrow { display:grid; grid-template-columns:104px 1fr; gap:10px; align-items:center;
  margin-bottom:6px; }
.tlname { font:600 12px/1 ui-monospace,Menlo,Consolas,monospace;
  white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.tltrack { position:relative; height:14px; background:#0f1216; border-radius:7px; overflow:hidden; }
.seg { position:absolute; top:0; height:14px; border-radius:4px; min-width:3px;
  box-shadow:0 0 0 2px var(--panel); }
.axis { display:flex; justify-content:space-between; color:var(--ink3); font-size:10px;
  margin:6px 0 0 114px; font-variant-numeric:tabular-nums; }
.card { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:12px;
  margin-bottom:10px; border-left:3px solid var(--l0); }
.head { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.plate { font:700 20px/1 ui-monospace,Menlo,Consolas,monospace; letter-spacing:1px; }
.badge { font-size:10px; font-weight:700; padding:3px 9px; border-radius:999px; color:#0b0d10;
  letter-spacing:.4px; }
.badge.police { background:#ffffff; }
.muted { color:var(--ink2); font-size:12px; }
.reasons { margin-top:7px; display:flex; gap:6px; flex-wrap:wrap; }
.reason { background:#0f1216; border:1px solid var(--line); border-radius:8px; padding:3px 9px;
  font-size:11px; color:#c8d2dd; }
.shots { display:flex; gap:8px; overflow-x:auto; margin-top:10px; padding-bottom:4px; }
.shot { flex:0 0 auto; width:190px; }
.shot img { width:100%; border-radius:8px; display:block; background:#000; cursor:zoom-in;
  border:1px solid var(--line); }
.cap { font-size:10px; color:var(--ink2); margin-top:4px; font-variant-numeric:tabular-nums; }
.empty { color:var(--ink2); text-align:center; padding:18px; font-size:13px; }
.arms { display:flex; flex-wrap:wrap; gap:10px; }
.arm { flex:1 1 220px; background:var(--panel); border:1px solid var(--line);
  border-radius:12px; padding:12px 14px; }
.armname { color:var(--ink2); font-size:12px; text-transform:uppercase; letter-spacing:.05em; }
.armpct { font-size:30px; font-weight:700; line-height:1.15; }
.armsub { color:var(--ink2); font-size:12px; margin-top:2px; }
.verdict { margin-top:10px; padding:10px 14px; border-radius:12px;
  background:var(--panel); border:1px solid var(--line); font-size:13px; }
#box { position:fixed; inset:0; background:#000d; display:none; align-items:center;
  justify-content:center; z-index:9999; cursor:zoom-out; padding:20px; }
#box img { max-width:100%; max-height:100%; border-radius:10px; }
.leaflet-popup-content-wrapper, .leaflet-popup-tip { background:var(--card); color:var(--ink); }
.leaflet-popup-content { margin:10px 12px; font-size:12px; }
.leaflet-popup-content img { width:210px; border-radius:6px; display:block; margin-top:6px; }
.leaflet-container { background:#0f1216; }
</style>
</head>
<body>
<header>
  <h1>LensALPR — отчёт о слежке</h1>
  <div class="period" id="period"></div>
  <div class="tiles" id="tiles"></div>
</header>

<div class="bar">
  <button type="button" class="chip" data-level="all" aria-pressed="true">все</button>
  <button type="button" class="chip" data-level="4" aria-pressed="true"><i style="background:var(--l4)"></i>чёрный список</button>
  <button type="button" class="chip" data-level="3" aria-pressed="true"><i style="background:var(--l3)"></i>хвост</button>
  <button type="button" class="chip" data-level="2" aria-pressed="true"><i style="background:var(--l2)"></i>подозрение</button>
  <button type="button" class="chip" data-level="1" aria-pressed="true"><i style="background:var(--l1)"></i>наблюдение</button>
  <button type="button" class="chip" data-level="0" aria-pressed="true"><i style="background:var(--l0)"></i>контакт</button>
  <span class="chip" style="cursor:default"><i style="background:var(--route)"></i>наш маршрут и повороты</span>
  <input id="q" placeholder="номер…" aria-label="поиск по номеру">
</div>

<div id="map"></div>

<section>
  <h2>Замер: чем резать вырезы</h2>
  <div id="crops"></div>
</section>

<section>
  <h2>Кто и когда был рядом</h2>
  <div class="tl" id="timeline"></div>
</section>

<section>
  <h2>Машины</h2>
  <div id="list"></div>
</section>

<div id="box"><img alt=""></div>

<script>${asset("web/leaflet.js")}</script>
<script>
const DATA = $payload;
const COLORS = ['#8FA0B4', '#4DA3FF', '#F2C230', '#FF4D4D', '#C77DFF'];
const S = DATA.stats;

document.getElementById('period').textContent = DATA.from + ' — ' + DATA.generated;
document.getElementById('tiles').innerHTML = [
  ['машин', S.vehicles, 'var(--ink)'],
  ['хвост', S.tail, 'var(--l3)'],
  ['подозрение', S.suspect, 'var(--l2)'],
  ['наблюдение', S.watch, 'var(--l1)'],
  ['наших поворотов', S.turns, 'var(--route)'],
  ['километров', S.km, 'var(--ink)'],
  ['минут', S.minutes, 'var(--ink)']
].map(function (t) {
  return '<div class="tile"><b style="color:' + t[2] + '">' + t[1] + '</b><span>' + t[0] + '</span></div>';
}).join('');

// The crop-strategy measurement. It decides a real setting, and until now it existed only as a
// line in a chat message that scrolls away — so it is stated here in the file that gets kept,
// with the sample sizes visible, because a hit rate over six crops is not a result.
(function () {
  const C = DATA.crops;
  const box = document.getElementById('crops');
  if (!C || (C.wideSent + C.narrowSent) === 0) {
    box.innerHTML = '<div class="empty">замер вырезов не проводился</div>';
    return;
  }
  const MIN = 40;
  function arm(name, sent, hits, pct, ms, note) {
    const thin = sent < MIN;
    return '<div class="arm">' +
      '<div class="armname">' + name + '</div>' +
      '<div class="armpct"' + (thin ? ' style="opacity:.55"' : '') + '>' + pct + '%</div>' +
      '<div class="armsub">номер в ' + hits + ' из ' + sent + ' вырезов · ' + ms + ' мс' +
      (thin ? ' · <b>мало данных</b>' : '') + '</div>' +
      (note ? '<div class="armsub">' + note + '</div>' : '') +
      '</div>';
  }
  // Two separate facts: which strategy finds more plates, and which costs less. The old text
  // welded "and faster" onto the accuracy winner whether or not it was.
  function speed(winnerMs, loserMs) {
    if (winnerMs < loserMs) return ' и быстрее (' + winnerMs + ' мс против ' + loserMs + ' мс)';
    if (winnerMs > loserMs) return ', но медленнее (' + winnerMs + ' мс против ' + loserMs + ' мс)';
    return '';
  }
  let verdict;
  if (C.wideSent < MIN || C.narrowSent < MIN) {
    verdict = 'Данных пока мало — нужно не меньше ' + MIN + ' вырезов на каждую стратегию. ' +
      'Покатайся ещё в режиме «эксперимент».';
  } else if (C.widePercent > C.narrowPercent) {
    verdict = 'Лучше <b>по машине</b>: ' + C.widePercent + '% против ' + C.narrowPercent + '%' +
      speed(C.wideMs, C.narrowMs) + '.';
  } else if (C.narrowPercent > C.widePercent) {
    verdict = 'Лучше <b>по номеру</b>: ' + C.narrowPercent + '% против ' + C.widePercent + '%' +
      speed(C.narrowMs, C.wideMs) + '.';
  } else {
    verdict = 'Ничья по проценту — тогда выигрывает более дешёвая: ' +
      (C.narrowMs < C.wideMs ? '<b>по номеру</b>' : '<b>по машине</b>') + '.';
  }
  box.innerHTML =
    '<div class="arms">' +
      arm('по машине', C.wideSent, C.wideHits, C.widePercent, C.wideMs, 'весь автомобиль') +
      arm('по номеру', C.narrowSent, C.narrowHits, C.narrowPercent, C.narrowMs, 'кроп вокруг найденного номера') +
    '</div>' +
    '<div class="verdict">' + verdict + '</div>' +
    '<div class="armsub">режим за поездку: ' + C.mode + '</div>';
})();

const map = L.map('map', { preferCanvas: true }).setView([56.95, 24.1], 12);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap'
}).addTo(map);

const bounds = [];
DATA.track.forEach(function (leg) {
  if (!leg.length) return;
  L.polyline(leg, { color: '#35D07F', weight: 3, opacity: .9 }).addTo(map);
  leg.forEach(function (p) { bounds.push(p); });
  L.circleMarker(leg[0], {
    radius: 5, color: '#35D07F', fillColor: '#0b0d10', fillOpacity: 1, weight: 2
  }).addTo(map).bindPopup('старт');
  L.circleMarker(leg[leg.length - 1], {
    radius: 5, color: '#35D07F', fillColor: '#35D07F', fillOpacity: 1, weight: 2
  }).addTo(map).bindPopup('финиш');
});

// Our own turns belong on the map: they are the events a follower has to copy.
DATA.turns.forEach(function (t) {
  if (!t.lat && !t.lon) return;
  L.circleMarker([t.lat, t.lon], {
    radius: 4, color: '#35D07F', fillColor: '#0b0d10', fillOpacity: 1, weight: 2, opacity: .85
  }).addTo(map).bindPopup('наш ' + t.dir + ' · ' + t.t);
  bounds.push([t.lat, t.lon]);
});

const layers = {};
DATA.vehicles.forEach(function (v) {
  const color = COLORS[v.level];
  const group = L.layerGroup().addTo(map);
  const path = [];
  v.encountersData.forEach(function (e, i) {
    if (!e.hasPos || (!e.lat && !e.lon)) return;
    path.push([e.lat, e.lon]);
    bounds.push([e.lat, e.lon]);
    L.circleMarker([e.lat, e.lon], {
      radius: v.level >= 3 ? 9 : 7, color: color, fillColor: color, fillOpacity: .9, weight: 2
    }).addTo(group).bindPopup(
      '<b>' + v.plate + '</b> · ' + v.levelName + (v.police ? ' · ПОЛИЦИЯ' : '') +
      '<br>встреча ' + (i + 1) + ' · ' + e.start +
      (e.minutes ? ' (' + e.minutes + ' мин)' : '') +
      '<br><span style="color:#9aa6b2">' + (v.makeModel || '') + ' · ' + e.lens +
      ' · OCR ' + e.score + '%</span>' + (e.photo ? '<img src="' + e.photo + '">' : '')
    );
    // Where the meeting ended, when it ended somewhere else: the stretch of road the car was
    // with us for, not only the point where it was first seen.
    if ((e.elat || e.elon) && (e.elat !== e.lat || e.elon !== e.lon)) {
      L.polyline([[e.lat, e.lon], [e.elat, e.elon]], { color: color, weight: 4, opacity: .55 }).addTo(group);
      bounds.push([e.elat, e.elon]);
    }
  });
  if (path.length > 1) {
    L.polyline(path, { color: color, weight: 2, opacity: .75, dashArray: '6 6' }).addTo(group);
  }
  layers[v.key] = group;
});
if (bounds.length) map.fitBounds(bounds, { padding: [30, 30] });

const T0 = DATA.windowFrom, SPAN = Math.max(1, DATA.windowTo - DATA.windowFrom);
function renderTimeline(list) {
  const box = document.getElementById('timeline');
  if (!list.length) { box.innerHTML = '<div class="empty">нет машин под фильтром</div>'; return; }
  box.innerHTML = list.map(function (v) {
    const segs = v.encountersData.map(function (e) {
      const left = Math.min(100, Math.max(0, (e.startMs - T0) / SPAN * 100));
      const width = Math.min(100 - left, Math.max(0.7, (e.endMs - e.startMs) / SPAN * 100));
      return '<div class="seg" style="left:' + left + '%;width:' + width + '%;background:' +
        COLORS[v.level] + '" title="' + v.plate + ' · ' + v.levelName + ' · ' + e.start +
        ' – ' + e.end + '"></div>';
    }).join('');
    return '<div class="tlrow"><div class="tlname">' + v.plate + '</div>' +
      '<div class="tltrack">' + segs + '</div></div>';
  }).join('') +
    '<div class="axis"><span>' + DATA.from + '</span><span>' + DATA.generated + '</span></div>';
}

function renderCards(list) {
  const box = document.getElementById('list');
  if (!list.length) { box.innerHTML = '<div class="empty">нет машин под фильтром</div>'; return; }
  box.innerHTML = list.map(function (v) {
    const color = COLORS[v.level];
    const hidden = Math.max(0, v.encountersInWindow - v.encountersShown);
    const older = Math.max(0, v.encounters - v.encountersInWindow);
    const shots = v.encountersData.map(function (e, i) {
      const missing = e.photoSkipped ? 'фото не вошло в отчёт' : 'фото нет';
      return '<div class="shot">' +
        (e.photo ? '<img src="' + e.photo + '" alt="">' : '<div class="cap">' + missing + '</div>') +
        '<div class="cap">' + e.start +
        (e.minutes ? ' · ' + e.minutes + ' мин' : '') + ' · ' + e.lens + '</div></div>';
    }).join('');
    // Numbering the tiles 1..N implied they were encounters 1..N of this car. They are not: the
    // window cuts off the older ones and some have no picture, so the labels contradicted the
    // counter in the line above. The date is the honest label; the gap is stated outright — and
    // "outside the window" is said only about meetings that really are.
    const notes = [];
    if (hidden > 0) notes.push('показаны ' + v.encountersShown + ' из ' + v.encountersInWindow + ' встреч за период');
    if (older > 0) notes.push('ещё ' + older + ' встреч вне окна отчёта');
    if (v.photosShown < v.encountersShown) notes.push('фото у ' + v.photosShown + ' из ' + v.encountersShown + ' встреч');
    const shotNote = notes.length ? '<div class="muted" style="margin-top:4px">' + notes.join(' · ') + '</div>' : '';
    return '<div class="card" style="border-left-color:' + color + '">' +
      '<div class="head"><span class="plate" style="color:' + color + '">' + v.plate + '</span>' +
      '<span class="badge" style="background:' + color + '">' + v.levelName + '</span>' +
      (v.police ? '<span class="badge police">🚔 ПОЛИЦИЯ</span>' : '') +
      '<span class="muted">' + [v.makeModel, v.color, v.country].filter(Boolean).join(' · ') +
      '</span></div>' +
      '<div class="reasons">' + v.reasons.map(function (r) {
        return '<span class="reason">' + r + '</span>';
      }).join('') + '</div>' +
      '<div class="muted" style="margin-top:6px">первый ' + v.first + ' · последний ' + v.last +
      ' · встреч ' + v.encounters + ' · кадров ' + v.sightings + ' · OCR ' + v.score + '%</div>' +
      shotNote +
      '<div class="shots">' + shots + '</div></div>';
  }).join('');
}

// The same look-alike mapping the app uses when it reads a plate, so a number typed on a Russian
// keyboard finds the car instead of silently searching for nothing.
const CYRILLIC = { 'А':'A','В':'B','Е':'E','К':'K','М':'M','Н':'H','О':'O','Р':'P','С':'C','Т':'T','У':'Y','Х':'X' };
function searchKey(text) {
  return text.toUpperCase().split('').map(function (c) { return CYRILLIC[c] || c; }).join('')
    .replace(/[^A-Z0-9]/g, '');
}

const active = new Set([0, 1, 2, 3, 4]);
function syncAllChip() {
  const all = document.querySelector('.chip[data-level="all"]');
  if (all) all.setAttribute('aria-pressed', String(active.size === 5));
}
function apply() {
  const q = searchKey(document.getElementById('q').value.trim());
  const list = DATA.vehicles.filter(function (v) {
    return active.has(v.level) && (!q || v.key.indexOf(q) >= 0);
  });
  const keep = {};
  list.forEach(function (v) { keep[v.key] = true; });
  Object.keys(layers).forEach(function (key) {
    if (keep[key]) { map.addLayer(layers[key]); } else { map.removeLayer(layers[key]); }
  });
  renderTimeline(list);
  renderCards(list);
}

document.querySelectorAll('.chip[data-level]').forEach(function (chip) {
  chip.addEventListener('click', function () {
    const level = chip.dataset.level;
    if (level === 'all') {
      const on = active.size < 5;
      active.clear();
      if (on) { [0, 1, 2, 3, 4].forEach(function (l) { active.add(l); }); }
      document.querySelectorAll('.chip[data-level]').forEach(function (c) {
        c.setAttribute('aria-pressed', String(on));
      });
    } else {
      const n = Number(level);
      if (active.has(n)) { active.delete(n); } else { active.add(n); }
      chip.setAttribute('aria-pressed', String(active.has(n)));
      syncAllChip();
    }
    apply();
  });
});
document.getElementById('q').addEventListener('input', apply);

const box = document.getElementById('box');
document.addEventListener('click', function (event) {
  if (event.target.tagName === 'IMG' && event.target.closest('.shot')) {
    box.querySelector('img').src = event.target.src;
    box.style.display = 'flex';
  } else if (event.target === box || event.target.parentNode === box) {
    box.style.display = 'none';
  }
});

DATA.vehicles.sort(function (a, b) { return b.level - a.level || b.lastMs - a.lastMs; });
apply();
</script>
</body>
</html>
""".trimIndent()

    private companion object {
        /** Immutable and thread-safe, unlike SimpleDateFormat: reports are built on three lanes. */
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss", Locale.US)
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.US)
        const val MAX_SINGLE_PHOTO = 900_000L

        /** Faster than this between two stored points is a GPS hop, not driving. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 70.0
    }
}
