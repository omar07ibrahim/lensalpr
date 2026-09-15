package checks

import ai.onnxruntime.Probe
import android.Manifest
import android.app.Service
import android.content.Assets
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import com.lensalpr.app.ScanSessionService
import com.lensalpr.app.alpr.*
import com.lensalpr.app.camera.*
import com.lensalpr.app.detect.YoloDetector
import com.lensalpr.app.settings.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val totals = linkedMapOf<String, IntArray>()
private fun test(group: String, name: String, body: () -> Unit) {
    val tally = totals.getOrPut(group) { intArrayOf(0, 0) }
    Handler.reset()
    try { body(); tally[0]++; println("PASS [$group] $name") }
    catch (error: Throwable) { tally[1]++; println("FAIL [$group] $name: ${error.cause?.message ?: error.message}") }
}
private fun service(vararg permissions: String) = ScanSessionService().apply {
    this.permissions.addAll(permissions)
}
private fun ScanSessionService.startForTest() = onStartCommand(Intent(this, ScanSessionService::class.java), 0, 1)
private fun plan() = listOf(
    PlanEntry(0, PlannedStep(3), ZoomStep("one")), PlanEntry(1, PlannedStep(5), ZoomStep("two")),
)

private fun sessionChecks() {
    fun scenario(name: String, body: () -> Unit) = test("session", name, body)
    scenario("camera permission alone starts a camera-only service") {
        val s = service(Manifest.permission.CAMERA); s.startForTest()
        check(s.promotedTypes == ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        check(s.power.latest?.isHeld == true)
    }
    for (p in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
        scenario("location type is enabled with $p permission") {
            val s = service(Manifest.permission.CAMERA, p); s.startForTest()
            check(s.promotedTypes == ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
    }
    scenario("missing camera stops without promotion") {
        val s = service(); s.startForTest(); check(s.stopped == 1 && s.promoted == 0)
    }
    scenario("null restart does not pretend to scan") {
        val s = service(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        check(s.onStartCommand(null, 0, 1) == Service.START_NOT_STICKY)
        check(s.stopped == 1 && s.promoted == 0 && s.power.latest == null)
    }
    scenario("normal start does not request fake sticky restoration") {
        val s = service(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        check(s.startForTest() == Service.START_NOT_STICKY)
    }
    scenario("foreground failure is caught in the service callback") {
        val s = service(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        s.failPromotion = true; s.startForTest(); check(s.stopped == 1 && s.power.latest?.isHeld != true)
    }
    scenario("destroy releases the session wake lock") {
        val s = service(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        s.startForTest(); s.onDestroy(); check(s.power.latest?.isHeld == false)
    }
    scenario("settlement while paused preserves the full dwell") {
        val switches = mutableListOf<PlanEntry>(); val ticks = mutableListOf<RotationTick>()
        val r = LensRotationScheduler({ switches.add(it) }, { ticks.add(it) })
        r.configure(plan()); r.start(); r.setPaused(true); r.onStepSettled(plan()[0].step)
        Handler.advanceBy(6000); check(switches.size == 1 && ticks.last().remainingMs == 3000L)
        r.setPaused(false); Handler.advanceBy(2900); check(switches.size == 1)
        Handler.advanceBy(100); check(switches.size == 2)
    }
    scenario("start preserves an already requested pause") {
        val switches = mutableListOf<PlanEntry>()
        val r = LensRotationScheduler({ switches.add(it) }, {})
        r.configure(plan()); r.setPaused(true); r.start(); r.onStepSettled(plan()[0].step)
        Handler.advanceBy(9000); check(switches.size == 1)
    }
    scenario("paused time does not consume the settle timeout") {
        val ticks = mutableListOf<RotationTick>()
        val r = LensRotationScheduler({}, { ticks.add(it) })
        r.configure(plan()); r.start(); Handler.advanceBy(1000); r.setPaused(true)
        Handler.advanceBy(9000); check(!ticks.last().holding)
        r.setPaused(false); Handler.advanceBy(2900); check(!ticks.last().holding)
        Handler.advanceBy(100); check(ticks.last().holding)
    }
    scenario("tick after advance describes the new lens") {
        val ticks = mutableListOf<RotationTick>(); val r = LensRotationScheduler({}, { ticks.add(it) })
        r.configure(plan()); r.start(); r.onStepSettled(plan()[0].step); Handler.advanceBy(3000)
        check(ticks.last().entry.index == 1 && ticks.last().remainingMs == 5000L)
    }
    scenario("stop inside a tick leaves no queued callback") {
        lateinit var r: LensRotationScheduler
        r = LensRotationScheduler({}, { r.stop() }); r.configure(plan()); r.start()
        Handler.advanceBy(0); check(Handler.pendingCount() == 0)
    }
    scenario("configure snapshots a mutable plan") {
        val entries = plan().toMutableList(); val r = LensRotationScheduler({}, {})
        r.configure(entries); entries.clear(); check(r.planSize == 2)
    }
}

private fun detectorChecks() {
    fun scenario(name: String, body: (Context) -> Unit) = test("detector", name) {
        Probe.reset(); Bitmap.created.clear(); Bitmap.failCreate = false; Canvas.failCreate = false
        val dir = Files.createTempDirectory("lens-detector-").toFile()
        try { body(Context(dir, Assets("original-model".toByteArray()))) }
        finally { dir.deleteRecursively(); Probe.onRun = {} }
    }
    fun noLeaks() { check(Probe.options.all { it.closed }); check(Probe.sessions.all { it.closed }) }
    val config = ScanConfig()
    scenario("options live until detector close, then close after the session") { c ->
        val d = YoloDetector.create(c, config); check(Probe.options.none { it.closed })
        d.close(); noLeaks(); check(Probe.events.takeLast(2) == listOf("session", "options"))
    }
    scenario("repeated close is idempotent") { c ->
        val d = YoloDetector.create(c, config); d.close(); val events = Probe.events.toList(); d.close()
        check(events == Probe.events); noLeaks()
    }
    scenario("failed accelerator creation cleans options before CPU fallback") { c ->
        Probe.failCreates = 1; val d = YoloDetector.create(c, config)
        check(d.backend == "cpu" && Probe.options.first().closed && !Probe.options.last().closed)
        d.close(); noLeaks()
    }
    scenario("failed provider registration discards partial options") { c ->
        Probe.failProvider = true; val d = YoloDetector.create(c, config)
        check(d.backend == "cpu" && Probe.options.size == 2 && Probe.options.first().closed)
        d.close(); noLeaks()
    }
    scenario("two failed session creations leak no options") { c ->
        Probe.failCreates = 2; check(runCatching { YoloDetector.create(c, config) }.isFailure); noLeaks()
    }
    scenario("failed option configuration leaks no handles") { c ->
        Probe.failConfiguration = true; check(runCatching { YoloDetector.create(c, config) }.isFailure); noLeaks()
    }
    scenario("missing input closes an already created session") { c ->
        Probe.noInput = true; check(runCatching { YoloDetector.create(c, config) }.isFailure); noLeaks()
    }
    scenario("bitmap allocation failure releases native ownership") { c ->
        Bitmap.failCreate = true; check(runCatching { YoloDetector.create(c, config) }.isFailure); noLeaks()
    }
    scenario("later constructor failure recycles the allocated bitmap") { c ->
        Canvas.failCreate = true; check(runCatching { YoloDetector.create(c, config) }.isFailure)
        noLeaks(); check(Bitmap.created.single().recycled)
    }
    scenario("failed session close retains options and can be retried") { c ->
        val d = YoloDetector.create(c, config); Probe.failNextClose = true; d.close()
        check(!Probe.options.single().closed && !Probe.sessions.single().closed); d.close(); noLeaks()
    }
    scenario("same-size changed model replaces stale cached bytes") { c ->
        c.filesDir.resolve(config.model.asset).writeText("obsolete-model")
        val d = YoloDetector.create(c, config)
        check(Probe.sessions.last().model.contentEquals(c.assets.bytes)); d.close()
    }
    scenario("compressed asset without descriptor is content validated") { c ->
        c.assets.descriptorAvailable = false; c.filesDir.resolve(config.model.asset).writeText("old")
        val d = YoloDetector.create(c, config)
        check(Probe.sessions.last().model.contentEquals(c.assets.bytes)); d.close()
    }
    scenario("unchanged model is not rewritten") { c ->
        val target = c.filesDir.resolve(config.model.asset); target.writeBytes(c.assets.bytes)
        check(target.setLastModified(1234567000L)); val stamp = target.lastModified()
        val d = YoloDetector.create(c, config); check(target.lastModified() == stamp); d.close()
    }
    scenario("interrupted copy preserves old bytes and removes staging files") { c ->
        val target = c.filesDir.resolve(config.model.asset); target.writeText("old"); c.assets.failAfter = 3
        check(runCatching { YoloDetector.create(c, config) }.isFailure)
        check(target.readText() == "old" && c.filesDir.listFiles()!!.map { it.name } == listOf(config.model.asset))
    }
    scenario("asset changing between reads cannot replace a usable cache") { c ->
        val target = c.filesDir.resolve(config.model.asset); target.writeText("old")
        c.assets.transform = { n, bytes -> if (n == 2) "changed".toByteArray() else bytes }
        check(runCatching { YoloDetector.create(c, config) }.isFailure)
        check(target.readText() == "old" && c.filesDir.listFiles()!!.size == 1)
    }
    scenario("first asset read failure leaves cache untouched") { c ->
        val target = c.filesDir.resolve(config.model.asset); target.writeText("old"); c.assets.failOpen = 1
        check(runCatching { YoloDetector.create(c, config) }.isFailure); check(target.readText() == "old")
    }
    scenario("non-finite model rows cannot publish invalid boxes or crash") { c ->
        Probe.rows = floatArrayOf(0f, 0f, 7f, 7f, Float.NaN, 2f, 0f, 0f, 7f, 7f, 0.9f, Float.NaN,
            Float.NaN, 0f, 7f, 7f, 0.9f, 2f, 0f, 0f, 7f, 7f, 0.9f, 2f)
        val d = YoloDetector.create(c, config); val result = d.detect(Bitmap(8, 8), 0.5f, 1, intArrayOf(2))
        check(result.size == 1 && result.single().box.left.isFinite()); d.close()
    }
    scenario("close waits for in-flight inference before releasing its resources") { c ->
        val d = YoloDetector.create(c, config)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val attempted = CountDownLatch(1); val closed = CountDownLatch(1)
        var inferenceFailure: Throwable? = null
        Probe.onRun = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val inference = Thread {
            try { d.detect(Bitmap(8, 8), 0.5f, 1, intArrayOf(2)) } catch (e: Throwable) { inferenceFailure = e }
        }
        val closer = Thread { attempted.countDown(); try { d.close() } finally { closed.countDown() } }
        try {
            inference.start(); check(entered.await(2, TimeUnit.SECONDS)); closer.start()
            check(attempted.await(2, TimeUnit.SECONDS))
            check(!closed.await(100, TimeUnit.MILLISECONDS)) { "close finished during inference" }
        } finally { release.countDown(); inference.join(3000); closer.join(3000) }
        check(!inference.isAlive && !closer.isAlive && inferenceFailure == null); noLeaks()
    }
}

private fun formatChecks() {
    fun scenario(name: String, body: () -> Unit) = test("formats", name, body)
    val method = AlprJson::class.java.getDeclaredMethod("parsePlate", JSONObject::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    fun parsed(raw: String, country: String?, strict: Boolean = false): PlateReading? {
        val item = JSONObject().put("text", raw).put("confidences", JSONArray().put(99.0).put(98.0))
            .put("warpedBox", JSONArray().put(0).put(0).put(120).put(0).put(120).put(30).put(0).put(30))
        if (country != null) item.put("country", JSONArray().put(JSONObject().put("code", country).put("name", country)))
        return method.invoke(AlprJson, item, strict) as PlateReading?
    }
    scenario("all existing local layouts preserve their display") {
        mapOf("A1234" to "A-1234", "a-1234" to "A-1234", "AA12" to "AA-12", "AA-12" to "AA-12",
            "ABC123" to "ABC-123", "ABCD-1234" to "ABCD-1234", "em 7209" to "EM-7209").forEach { (raw, display) ->
            val p = PlateFormats.parse(raw); check(p?.display == display && p.latvian)
        }
    }
    scenario("separators and case share the same key") {
        check(listOf("EM-7209", "em7209", " EM 7209 ").map { PlateFormats.key(it) }.toSet() == setOf("EM7209"))
    }
    scenario("word and digit-only noise remains rejected") {
        listOf("0001", "GOOD", "XAKEP", "NTERNET", "3AKOH").forEach { check(PlateFormats.parse(it) == null) }
    }
    scenario("explicit local mode repairs OCR substitution") {
        check(PlateFormats.parse("EM72O9", true)?.let { it.key == "EM7209" && it.corrected } == true)
    }
    scenario("already valid local strings are never substituted") {
        check(PlateFormats.parse("EMI209")?.let { it.display == "EMI-209" && !it.corrected } == true)
    }
    scenario("UK control is preserved and strict mode rejects it") {
        check(PlateFormats.parse("NY53NKD")?.key == "NY53NKD" && PlateFormats.parse("NY53NKD", true) == null)
    }
    scenario("unknown GBB-01-B keeps its final letter") {
        check(PlateFormats.parse("GBB-01-B")?.let { it.key == "GBB01B" && !it.corrected } == true)
    }
    scenario("unknown XX-85-TS is not rewritten into digits") { check(PlateFormats.parse("XX-85-TS")?.key == "XX85TS") }
    scenario("unknown ambiguous OCR preserves evidence instead of guessing country") {
        check(PlateFormats.parse("EM72O9")?.let { it.key == "EM72O9" && !it.corrected } == true)
    }
    scenario("manual normalization preserves a foreign identifier") {
        check(PlateText.normalize("GBB-01-B") == "GBB01B")
        check(PlateText.normalize("GBB-018") != PlateText.normalize("GBB-01-B"))
    }
    scenario("manual normalization does not change O into zero") { check(PlateFormats.key("EM72O9") == "EM72O9") }
    scenario("corrected signage cannot re-enter via generic mode") {
        for (strict in listOf(false, true)) for (raw in listOf("P104", "P1O4", "RIGA12", "R1GA12")) {
            check(PlateFormats.parse(raw, strict) == null) { "$raw strict=$strict" }
        }
    }
    scenario("ordinary short plates survive the sign filter") {
        check(PlateFormats.parse("B104")?.key == "B104" && PlateFormats.parse("A1234")?.key == "A1234")
    }
    scenario("AlprJson reads foreign country before normalizing") {
        val p = parsed("GBB-01-B", "NL"); check(p?.text == "GBB01B" && p.countryCode == "NL" && !p.corrected)
    }
    scenario("AlprJson repairs confirmed Latvian OCR") {
        val p = parsed("EM72O9", "LV"); check(p?.text == "EM7209" && p.corrected)
    }
    scenario("country hint tolerates case and whitespace") { check(parsed("EM72O9", " lv ")?.text == "EM7209") }
    scenario("strict local correction cannot override an explicit foreign hint") { check(parsed("GBB-01-B", "NL", true) == null) }
    scenario("AlprJson rejects misread signs before publishing") {
        check(parsed("P1O4", null) == null && parsed("R1GA12", "LV") == null)
    }
}

fun main() {
    sessionChecks(); detectorChecks(); formatChecks()
    totals.forEach { (name, count) -> println("RESULT $name: ${count[0]} passed; ${count[1]} failed") }
    val passed = totals.values.sumOf { it[0] }; val failed = totals.values.sumOf { it[1] }
    println("TOTAL: $passed passed; $failed failed")
    check(failed == 0) { "$failed regression checks failed" }
}
