package com.lensalpr.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lensalpr.app.alpr.AlprEngine
import com.lensalpr.app.alpr.AlprOutcome
import com.lensalpr.app.alpr.PlateRoi
import com.lensalpr.app.camera.CameraCatalog
import com.lensalpr.app.camera.CameraController
import com.lensalpr.app.camera.ClipSplitter
import com.lensalpr.app.camera.FrameGate
import com.lensalpr.app.camera.LensRotationScheduler
import com.lensalpr.app.camera.LensState
import com.lensalpr.app.camera.LensVerifier
import com.lensalpr.app.camera.PlanEntry
import com.lensalpr.app.camera.RearCameraSetup
import com.lensalpr.app.camera.RotationTick
import com.lensalpr.app.camera.SessionLifecycleOwner
import com.lensalpr.app.camera.VideoRecorder
import com.lensalpr.app.data.StorageCleaner
import com.lensalpr.app.data.TrackPoint
import com.lensalpr.app.data.TrackingStore
import com.lensalpr.app.data.WipeStats
import com.lensalpr.app.databinding.ActivityScanBinding
import com.lensalpr.app.databinding.DialogVehicleBinding
import com.lensalpr.app.detect.YoloDetector
import com.lensalpr.app.follow.AlertPolicy
import com.lensalpr.app.follow.AlertReason
import com.lensalpr.app.follow.FollowConfig
import com.lensalpr.app.follow.FollowEngine
import com.lensalpr.app.follow.FollowEvidence
import com.lensalpr.app.lock.LockActivity
import com.lensalpr.app.lock.LockCrypto
import com.lensalpr.app.lock.LockPolicy
import com.lensalpr.app.lock.LockStore
import com.lensalpr.app.follow.ThreatLevel
import com.lensalpr.app.pipeline.AlprWorker
import com.lensalpr.app.pipeline.CropBufferPool
import com.lensalpr.app.pipeline.CropStats
import com.lensalpr.app.pipeline.FrameProcessor
import com.lensalpr.app.pipeline.FrameSnapshot
import com.lensalpr.app.pipeline.OcrJob
import com.lensalpr.app.pipeline.RecognitionState
import com.lensalpr.app.pipeline.SpillStore
import com.lensalpr.app.pipeline.VehicleCard
import com.lensalpr.app.pipeline.VehicleRegistry
import com.lensalpr.app.report.HtmlReportBuilder
import com.lensalpr.app.settings.PlannedStep
import com.lensalpr.app.settings.RuntimeSettings
import com.lensalpr.app.settings.ScanConfig
import com.lensalpr.app.telegram.BotHost
import com.lensalpr.app.telegram.BotSettings
import com.lensalpr.app.telegram.TelegramBot
import com.lensalpr.app.telegram.TelegramClient
import com.lensalpr.app.track.GeoFix
import com.lensalpr.app.track.TripTracker
import com.lensalpr.app.track.TurnEvent
import com.lensalpr.app.ui.PlateSpeech
import com.lensalpr.app.ui.VehicleAdapter
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The scanner.
 *
 * Thread map:
 *  * main - camera session, lens rotation, consensus and UI;
 *  * `frame-analysis` - RGBA copy, YOLO26 inference, tracking, crop extraction;
 *  * `alpr-worker` - the native plate/make-model engine, fed from a bounded crop queue.
 *
 * Nothing on the frame path waits for recognition, so a burst of vehicles increases queue depth
 * rather than dropping the detector's frame rate.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var config: ScanConfig
    private lateinit var gate: FrameGate
    private lateinit var recognition: RecognitionState
    private lateinit var registry: VehicleRegistry
    private lateinit var pool: CropBufferPool
    private lateinit var worker: AlprWorker
    private lateinit var spillStore: SpillStore
    private lateinit var adapter: VehicleAdapter

    private lateinit var store: TrackingStore
    private var reportBuilder: HtmlReportBuilder? = null
    private lateinit var runtime: RuntimeSettings
    private val sessionOwner = SessionLifecycleOwner()
    private var videoRecorder: VideoRecorder? = null
    private var tracker: TripTracker? = null
    private var follow: FollowEngine? = null
    private var bot: TelegramBot? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Rank of the line being spoken right now; see [VOICE_PRIORITY_IDLE] and friends. */
    private var speakingPriority = VOICE_PRIORITY_IDLE

    /** Held only while something is being said, so the music ducks and then comes back. */
    private var audioFocus: AudioFocusRequest? = null

    /** Why the voice will stay silent, or null when it is going to work. */
    @Volatile
    private var voiceState: String? = null

    /** Written on main, read by the bot's retry lane when it asks whether a car was dismissed. */
    private val evidenceByPlate = ConcurrentHashMap<String, FollowEvidence>()

    /** Measures the two cropping strategies against each other during ordinary driving. */
    private val cropStats = CropStats()

    /** Set at the top of onDestroy: every callback that lands afterwards has nothing to act on. */
    @Volatile
    private var destroyed = false

    /** Set once the lock let this activity build itself; before that onDestroy has nothing to tear down. */
    private var created = false

    /** Airplane mode as last observed; see [applyAirplaneMode]. */
    private var airplaneMode = false
    private var airplaneReceiver: BroadcastReceiver? = null

    /** Puts the base banner back once a blacklist or police banner has had its time on screen. */
    private var markBannerRestore: Runnable? = null

    /**
     * When airplane mode was last switched off (uptime clock), or zero. The bot counts "unreachable
     * since" from its last success, and the flight itself must not be billed as an outage.
     */
    private var airplaneOffAtMs = 0L

    /**
     * Until when a refused restart stays refused (uptime clock). The watchdogs that ask for one
     * would otherwise announce the same stuck engine, and ask again, every couple of minutes.
     */
    private var restartRefusedUntilMs = 0L

    /** Milliseconds the engine has actually spent answering crops since it last came up. */
    private var engineBusyMs = 0L
    private var engineBusyTickMs = 0L

    /** Makes every spoken line its own utterance; a plate is spoken more than once per minute. */
    private var utteranceSeq = 0L

    /**
     * When the current blind spell was first noticed. Recovery is only ever declared when a frame
     * has actually passed the gate *since then* — a rebind used to rebase the clock and count
     * as a recovery on its own, so the ladder reset itself and looped: skip, rebind, "восстановлено",
     * skip, rebind, for the rest of the drive, without a single plate read.
     */
    private var blindDetectedAtMs = 0L

    /** When the camera was last asked to come up; the deadline for its first frame. */
    private var cameraStartedAtMs = 0L

    /** The final "kill the process" step of a restart, held so closing the app can cancel it. */
    private var exitPending: Runnable? = null

    /** The alarm armed to bring the app back, held so a cancelled restart can disarm it. */
    private var restartAlarm: PendingIntent? = null

    /**
     * The operator, the absence timer or the session asked the recorder to stop. Until the
     * recorder confirms, the watchdog must not re-arm a segment continuation on the clip that is
     * being stopped, and the finalize callback must not start the next one.
     */
    private var recordingStopRequested = false

    /** The running clip was started by hand; it continues across file limits until stopped. */
    private var manualRecording = false

    /** When the engine was first seen stuck inside one crop, or zero. */
    private var ocrStuckSinceMs = 0L

    /** When the clip backlog was last offered to the bot again. */
    private var lastClipResendMs = SystemClock.elapsedRealtime()

    /** Where the previous camera controller left the generation counter; the next continues it. */
    private var lastControllerGeneration = 0L

    /** Priority of every utterance still queued in the speech engine, by its id. */
    private val utterancePriorities = ConcurrentHashMap<String, Int>()

    /**
     * Who is behind us right now, refreshed on the main thread so the bot may read it from its own.
     */
    @Volatile
    private var companions: List<FollowEvidence> = emptyList()

    /** Consecutive watchdog ticks with no frames from the camera; each one escalates the response. */
    private var stallStrikes = 0

    /** When the last recovery attempt was made; a rebind needs longer than one watchdog tick. */
    private var lastRebindAtMs = 0L

    /** Watchdog ticks with frames arriving but none reaching recognition. */
    private var blindStrikes = 0

    /** Recovery rungs already tried for the current blind spell; see [recoverFromBlindness]. */
    private var blindActions = 0

    /** When the last recovery attempt was made, so each one is given time to work. */
    private var lastBlindActionMs = 0L

    /** Set once the ladder has run out and the process restart has been ordered. */
    private var blindGaveUp = false

    /** The operator has been told Telegram is unreachable; said once, not every five seconds. */
    private var botUnreachableTold = false

    /**
     * When the gate was last *allowed* to open again, on the elapsed-realtime clock.
     *
     * The blindness clock cannot run from "the last frame that got through" alone. A paused
     * session freezes that stamp, so waking after a twenty-minute stop read as twenty minutes of
     * blindness inside the very same watchdog tick that lifted the pause — the frame that would
     * have refreshed it was still tens of milliseconds away. Every legitimate reopening rebases
     * this instead: leaving the pause, resuming the session, rebinding the camera.
     */
    private var gateResumedAtMs = 0L

    /** When the ALPR engine last went blind on its runtime limit, or zero while it is healthy. */
    private var engineDownSinceMs = 0L

    /** Engine restarts attempted for the current outage; reset once it recognizes again. */
    private var engineRestarts = 0

    /** Set once the operator has been told the engine could not be brought back. */
    private var engineGaveUp = false

    /** Scheduled engine restart, held so a burst of status updates cannot queue several. */
    private var engineRestartPending: Runnable? = null

    /** Scheduled process restart, held so closing the app cancels it instead of resurrecting it. */
    private var processRestartPending: Runnable? = null

    /** When the engine was last recycled on purpose, to keep it well short of its limit. */
    private var lastRecycleMs = 0L

    /**
     * When the clip directory was last swept; housekeeping has to keep up with a long drive.
     *
     * Seeded at construction rather than left at zero: `elapsedRealtime` on a phone that has been
     * up for days is far past the interval, so a zero start made the very first watchdog tick
     * sweep — five seconds after the resend queue had been built, and before a single upload had
     * finished.
     */
    private var lastClipPruneMs = SystemClock.elapsedRealtime()

    /**
     * Clips currently being uploaded. Never deleted by housekeeping, whatever their age.
     *
     * Synchronised: filled on the io thread that queues the upload, drained on the bot's media
     * thread when delivery resolves, read by the sweep on the io thread.
     */
    private val clipsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When a recording failure was last reported, so a stuck encoder cannot spam the chat. */
    private var lastClipFailureMs = 0L

    /** When a failed engine was last retried, so a hopeless init cannot spin. */
    private var lastEngineRetryMs = 0L

    /** When the engine last became usable; how long a restart bought is the whole question. */
    private var engineReadySinceMs = 0L

    /** Consecutive failed initializations, cleared once the engine reports READY. */
    private var engineErrorRetries = 0

    /** When the engine entered INITIALIZING, to notice an init that never comes back. */
    private var engineInitSinceMs = 0L

    /** Last time each plate was spoken aloud; a repeat alert must not become a chant. */
    private val lastSpokenMs = HashMap<String, Long>()

    /** What the session notification currently says, so it is not rewritten on every tick. */
    private var lastNotifiedBanner: String? = null

    @Volatile
    private var tripId = 0L

    @Volatile
    private var tripStartMs = 0L
    private var lastTrackPointMs = 0L

    @Volatile
    private var statusFps = 0f

    @Volatile
    private var statusDetMs = 0L

    @Volatile
    private var statusQueue = 0

    @Volatile
    private var statusTracked = 0

    @Volatile
    private var statusVerified = false

    private var setup: RearCameraSetup? = null
    private var planEntries: List<PlanEntry> = emptyList()
    private var detector: YoloDetector? = null
    private var processor: FrameProcessor? = null
    private var controller: CameraController? = null
    private var scheduler: LensRotationScheduler? = null

    private val ioService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lensalpr-io")
    }

    /**
     * The io lane as everybody else sees it.
     *
     * A task handed in after `onDestroy` shut the lane down used to throw
     * `RejectedExecutionException` on whichever thread offered it — the main thread, for a
     * Telegram command that arrived a moment late — and take the process down with it. Late
     * work has nothing left to do; it is dropped with a line in the log.
     */
    private val ioExecutor = Executor { task ->
        runCatching { ioService.execute(task) }
            .onFailure { Log.w(TAG, "io task after shutdown dropped") }
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "frame-analysis").apply { priority = Thread.NORM_PRIORITY + 2 }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val engineListener: (AlprEngine.Status) -> Unit = { status -> onEngineStatus(status) }

    @Volatile
    private var lensLabel: String = ""

    /** Set when a clip is cut only because it grew too long; recording resumes in a new file. */
    private var segmentPlate: String? = null

    private var thermalLevel = 0
    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null

    private var userPaused = false
    private var lifecyclePaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh process is locked unless its own restart alarm brought it back: nobody is at
        // the phone then, and a lock screen would end the session for good.
        if (!LockStore.unlocked && !LockStore.consumeAutoUnlock(this)) {
            startActivity(LockActivity.intent(this, LockActivity.TARGET_SCAN))
            finish()
            return
        }
        created = true
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()

        setup = CameraCatalog.discover(this)
        config = ScanConfig.load(this, setup)

        runtime = RuntimeSettings(this)
        // The session keeps the camera; the service keeps the session legal in the background.
        sessionOwner.start()
        ScanSessionService.stopListener = { onNotificationStop() }
        ScanSessionService.start(this)
        gate = FrameGate(strict = config.strictLens)
        recognition = RecognitionState()
        registry = VehicleRegistry(
            requiredMatches = config.consensusMatches,
            minScore = config.minOcrScore.toFloat(),
            recognition = recognition,
        )
        // A spelling the voters synthesise is validated the way every other read is — under the
        // configured country's layouts, not the Latvian default.
        registry.parsePlate = { text ->
            com.lensalpr.app.alpr.PlateFormats.parse(text, config.strictPlateFormat, null, config.plateRegion)
        }
        pool = CropBufferPool()
        spillStore = SpillStore(File(cacheDir, "spill")).apply {
            val carried = adopt()
            if (carried > 0) Log.i(TAG, "carried over $carried spilled crops")
        }
        worker = AlprWorker(
            pool = pool,
            currentGeneration = { gate.generation },
            onResult = ::onRecognition,
            // A crop that never reaches the engine must still free its vehicle, or the scheduler
            // keeps believing that car is mid-recognition and never looks at it again.
            onDropped = { job -> recognition.peek(job.trackId)?.inFlight = false },
        ).apply { spill = spillStore }

        adapter = VehicleAdapter(::showVehicle)
        binding.vehicleList.layoutManager = LinearLayoutManager(this)
        binding.vehicleList.adapter = adapter
        binding.vehicleList.itemAnimator = null

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnSkip.setOnClickListener { scheduler?.advance() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnRecord.setOnClickListener {
            Toast.makeText(this, botHost.toggleRecording(), Toast.LENGTH_SHORT).show()
            refreshRecordButton()
        }
        binding.btnBlackout.setOnClickListener { setBlackout(true) }
        binding.blackout.setOnClickListener { setBlackout(false) }
        binding.btnReport.setOnClickListener { shareReport() }
        binding.btnReport.setOnLongClickListener {
            toggleCapture()
            true
        }
        // A report is useful even without Telegram configured.
            binding.panelTitle.setOnLongClickListener {
                shareReport()
                true
            }
        binding.btnAddPlate.setOnClickListener { showAddPlateDialog() }
        binding.btnClear.setOnClickListener {
            registry.clear()
            evidenceByPlate.clear()
            adapter.submitList(emptyList())
            updatePanelTitle()
        }

        requestBatteryExemption()
        store = TrackingStore(applicationContext)
        reportBuilder = HtmlReportBuilder(applicationContext, store)
        startFollowDetection()
        startBot()
        watchAirplaneMode()
        reportPreviousCrash()
        warnIfBatteryRestricted()
        resendPendingClips()
        startThermalWatch()
        if (config.voiceAlerts) initVoice()

        updatePanelTitle()
        AlprEngine.addListener(engineListener)
        AlprEngine.initialize(this, config)
        // Armed here, not inside the pipeline. Every early exit in startPipeline — no usable lens,
        // a detector model that will not load — used to skip the watchdog entirely, and with it
        // the only thing that revives a dead Telegram poller and the only thing that notices a
        // dead camera. A session that failed to start is exactly when those matter most.
        mainHandler.postDelayed(recordingWatchdog, WATCHDOG_INTERVAL_MS)
        startPipeline()
    }

    private fun startPipeline() {
        val rearSetup = setup
        val steps = rearSetup?.steps.orEmpty()
        val entries = config.plan.mapIndexedNotNull { index, planned ->
            val step = steps.firstOrNull { it.id == planned.stepId } ?: return@mapIndexedNotNull null
            PlanEntry(index, planned, step)
        }.ifEmpty {
            val fallback = steps.firstOrNull() ?: return@ifEmpty emptyList()
            listOf(
                PlanEntry(
                    0,
                    PlannedStep(fallback.id, ScanConfig.DEFAULT_DWELL_SECONDS),
                    fallback,
                ),
            )
        }

        if (rearSetup == null || entries.isEmpty()) {
            showBanner(getString(R.string.camera_error, "no usable lens"))
            return
        }
        lensLabel = entries.first().step.label
        planEntries = entries

        // A plain thread, not a lifecycle-scoped coroutine: cancelling the coroutine on a quick
        // Back threw away a detector that had just finished loading — the native session it owned
        // was never closed, and the field it was bound for stayed null so onDestroy could not
        // close it either. Here the loaded object always reaches the main thread, which either
        // keeps it or closes it.
        Thread({
            val loaded = runCatching { YoloDetector.create(applicationContext, config) }
            mainHandler.post { onDetectorLoaded(loaded, entries, rearSetup) }
        }, "yolo-load").start()
    }

    private fun onDetectorLoaded(
        loaded: Result<YoloDetector>,
        entries: List<PlanEntry>,
        rearSetup: RearCameraSetup,
    ) {
        val yolo = loaded.getOrElse { error ->
            Log.e(TAG, "detector unavailable", error)
            showBanner(
                getString(R.string.detector_error, error.message ?: error.javaClass.simpleName),
            )
            return
        }
        if (destroyed || isFinishing || isDestroyed) {
            yolo.close()
            return
        }
        detector = yolo

        run {
            val frameProcessor = FrameProcessor(
                config = config,
                gate = gate,
                detector = yolo,
                worker = worker,
                pool = pool,
                recognition = recognition,
                geoStamp = {
                    val fix = tracker?.current
                    if (fix == null) null else doubleArrayOf(fix.lat, fix.lon, fix.odometerM)
                },
                onTrackLost = { trackId -> mainHandler.post { registry.onTrackLost(trackId) } },
                onFrame = { snapshot -> mainHandler.post { renderFrame(snapshot) } },
            )
            processor = frameProcessor
            // Immediately, not on the first watchdog tick. The processor starts with narrow crops
            // enabled, so up to five seconds of every session ignored the operator's setting — long
            // enough to put crops of the strategy they turned off into the measurement that is
            // supposed to decide between them.
            applyCropStrategy()

            val rotation = LensRotationScheduler(
                onSwitch = { entry ->
                    lensLabel = entry.step.label
                    controller?.apply(entry.step)
                },
                onTick = ::renderRotation,
            )
            rotation.configure(entries)
            rotation.setPaused(gate.paused)
            scheduler = rotation

            val recorder = VideoRecorder(applicationContext, ::onClipFinished, ::onClipFailed)
            videoRecorder = recorder

            // A `/stop` that arrived while the detector was still loading leaves the pipeline
            // built and the camera unbound; `/go` binds it through resumeSession like any other.
            if (!sessionRunning) return

            val camera = CameraController(
                context = this@ScanActivity,
                lifecycleOwner = sessionOwner,
                previewView = binding.previewView,
                setup = rearSetup,
                config = config,
                gate = gate,
                analysisExecutor = analysisExecutor,
                analyzer = frameProcessor,
                onLensState = ::renderLens,
                onStepSettled = { step, _ -> rotation.onStepSettled(step) },
                onError = { message -> showBanner(getString(R.string.camera_error, message)) },
                videoRecorder = recorder,
                initialGeneration = lastControllerGeneration,
            )
            controller = camera
            cameraStartedAtMs = SystemClock.elapsedRealtime()
            // The shared ViewPort is only available once the preview has been measured.
            camera.prepare {
                binding.previewView.doOnLayout {
                    if (!destroyed && controller === camera && sessionRunning) rotation.start()
                }
            }
            mainHandler.postDelayed({
                val report = preflightReport()
                Log.i(TAG, "preflight: " + report.replace('\n', '|'))
                bot?.broadcast(report)
            }, PREFLIGHT_DELAY_MS)
        }
    }

    // ----------------------------------------------------------------- follow

    /**
     * The follow engine always runs; the setting decides whether it *classifies*.
     *
     * Turning "detect tailing" off used to turn off the engine, and with it the only path that
     * writes a confirmed plate to the database — every car of the drive existed until the screen
     * closed and then never. The history, the encounter photos and the operator's own lists are
     * not optional; only the GPS-based verdicts are.
     */
    private fun startFollowDetection() {
        val trip = TripTracker(this, onFix = ::onGeoFix, onTurn = ::onTurn)
        tracker = trip
        val engine = FollowEngine(
            store = store,
            tracker = trip,
            io = ioExecutor,
            runtime = runtime,
            config = FollowConfig(alertAfterEncounters = runtime.alertAfterEncounters),
            onUpdate = ::onThreat,
            onFollowerConfirmed = ::onFollowerConfirmed,
            onFollowerLost = ::onFollowerLost,
            detectFollowing = config.followEnabled,
        )
        follow = engine
        // A restart that happened seconds ago is the same drive. Continuing its trip keeps the
        // route in one piece and, more importantly, stops the restart from inventing evidence:
        // a second trip id would make every car still behind us claim "seen on two trips".
        val handover = runtime.consumeTripHandover(System.currentTimeMillis())
        val resumed = handover != null
        tripStartMs = handover?.startedAtMs ?: System.currentTimeMillis()
        // The kilometres and turns of the trip travel with its id, or the debrief of a drive that
        // restarted once would describe only the part after the restart.
        if (handover != null) trip.restore(handover.odometerM, handover.turns)
        ioExecutor.execute {
            val id = handover?.tripId
                ?: runCatching { store.startTrip(tripStartMs) }.getOrDefault(0L)
            tripId = id
            engine.bindTrip(id)
            // Trips a dead process left open are closed now, so they age out like any other and
            // the report does not treat them as still in progress.
            runCatching { store.closeAbandonedTrips(id) }
            engine.loadBlacklist()
            engine.loadPolice()
            engine.loadIgnored()
            // Housekeeping first, restoration second: a state restored under a spelling the merge
            // deletes a moment later would point at a row that no longer exists.
            runCatching { store.prune(System.currentTimeMillis() - RETENTION_MS, id) }
            runCatching { store.mergeDuplicates() }
                .onSuccess { count -> if (count > 0) Log.i(TAG, "merged $count duplicate plates") }
            // What the engine knew before the restart lives in the database; without this the tail
            // that was being tracked a moment ago comes back as an unknown car with no history.
            runCatching { engine.hydrate(System.currentTimeMillis()) }
                .onSuccess { count -> if (count > 0) Log.i(TAG, "restored $count vehicles from db") }
                .onFailure { error -> Log.w(TAG, "hydrate failed", error) }
            if (resumed) Log.i(TAG, "resumed trip $id after process restart")
        }
        if (!config.followEnabled) return
        if (hasLocationPermission()) {
            trip.start()
        } else {
            showBanner(getString(R.string.location_required))
        }
    }

    /**
     * "Stop" tapped in the notification shade. Stopping the service alone left the camera, the
     * pipeline and the bot running with no notification to show for it; the session is what
     * has to stop, and a second tap on an idle session closes the screen.
     */
    private fun onNotificationStop() {
        if (destroyed) return
        if (sessionRunning) {
            haltSession()
        } else {
            finish()
        }
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The engine has decided this car is an actual tail. Stills stop being enough: the clip is what
     * shows the distance keeping and the manoeuvres. Mere suspicion never gets here — filming every
     * car that lingers behind us would burn the battery and bury the real one in noise.
     */
    private fun onFollowerConfirmed(evidence: FollowEvidence) {
        // Heat is deliberately not a reason to refuse. A confirmed tail is the one recording worth
        // making, and declining to film it because the phone is warm throws away the only evidence
        // the drive was ever going to produce.
        if (!runtime.videoEnabled) {
            follow?.noteVideoFinished(evidence.plate)
            return
        }
        val recorder = videoRecorder
        if (recorder == null || recorder.isRecording) {
            follow?.noteVideoFinished(evidence.plate)
            return
        }
        if (recorder.start(evidence.plate)) {
            manualRecording = false
            recordingStopRequested = false
            showBanner("🎥 Запись: ${evidence.displayPlate}")
            bot?.broadcast(
                "🎥 <code>${evidence.displayPlate}</code> — ${evidence.level.title()}, начал запись",
            )
        } else {
            follow?.noteVideoFinished(evidence.plate)
            // A refusal to record is usually "no room left", and left unsaid it turns into a whole
            // drive with no footage of anything. Say it — throttled, because the next sighting is
            // two seconds away — and try to make room, which is the one thing that can fix it.
            val now = SystemClock.elapsedRealtime()
            if (now - lastClipFailureMs >= CLIP_FAILURE_REPORT_MS) {
                lastClipFailureMs = now
                // Clean up first, then say what actually happened. Announcing "чищу старые клипы"
                // in advance was a promise the sweep could not always keep, and the driver had no
                // way to tell "it sorted itself out" from "this drive will have no footage".
                val plate = evidence.displayPlate
                if (recorder.videoCapture == null) {
                    // The camera refused the video use case when the session was built — this
                    // phone simply cannot film alongside preview and analysis. Blaming the disk
                    // would send the driver deleting photos for the rest of the trip.
                    bot?.broadcast(
                        "⛔️ Клипы на этом телефоне недоступны: камера не отдала видеопоток. " +
                            "<code>$plate</code> веду только по фото. Память чистить не нужно.",
                        urgent = true,
                    )
                } else {
                    ioExecutor.execute {
                        // mkdirs first: usableSpace on a path that does not exist answers zero,
                        // which reads as "the phone is completely full" on a phone with 40 GB free.
                        val dir = File(filesDir, "clips").apply { runCatching { mkdirs() } }
                        val before = runCatching { dir.usableSpace }.getOrDefault(0L)
                        runCatching { pruneClips(dir, clipsInFlight.toSet()) }
                        val after = runCatching { dir.usableSpace }.getOrDefault(0L)
                        val freedMb = ((after - before) / (1024 * 1024)).coerceAtLeast(0L)
                        val message = when {
                            // There was room all along, so space was never the reason.
                            before >= VideoRecorder.MIN_FREE_BYTES ->
                                "⚠️ Не начал запись <code>$plate</code>: камера отказала в старте " +
                                    "клипа. Свободно ${after / (1024 * 1024)} МБ — дело не в месте."

                            after >= VideoRecorder.MIN_FREE_BYTES ->
                                "⚠️ Не начал запись <code>$plate</code> — не хватало места. " +
                                    "Освободил $freedMb МБ, следующая попытка должна пройти."

                            else ->
                                "⛔️ Не могу писать видео <code>$plate</code>: на телефоне " +
                                    "${after / (1024 * 1024)} МБ свободно, удалять больше нечего. " +
                                    "Освободите память — записи этой поездки не будет."
                        }
                        mainHandler.post { bot?.broadcast(message, urgent = true) }
                    }
                }
                lastClipPruneMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun onFollowerLost(plate: String) {
        val recorder = videoRecorder ?: return
        if (recorder.currentTarget == plate) {
            stopRecording()
        }
    }

    /**
     * The one way a clip is stopped on purpose. The flag survives until the recorder's own
     * finalize arrives, so a watchdog tick in between cannot re-arm a continuation and the
     * finalize cannot start the next segment of a clip somebody just ended.
     */
    private fun stopRecording() {
        val recorder = videoRecorder ?: return
        segmentPlate = null
        if (recorder.isRecording) {
            recordingStopRequested = true
            recorder.stop()
        }
    }

    private fun onClipFailed(plate: String, error: Int) {
        mainHandler.post {
            recordingStopRequested = false
            manualRecording = false
            onClipFailedOnMain(plate, error)
        }
    }

    /**
     * The recorder produced nothing usable — no disk space, a camera taken away mid-clip.
     *
     * Undoing the announcement matters more than the clip: without releasing the follow engine's
     * mark this vehicle would be considered "already being filmed" for the rest of the drive and
     * never filmed again, while the banner kept promising a recording that does not exist.
     */
    private fun onClipFailedOnMain(plate: String, error: Int) {
        hideBanner()
        follow?.noteVideoFinished(plate)
        segmentPlate = null
        // Releasing the mark lets the engine try again immediately, which is right — but if
        // the encoder is broken or the disk is full it will fail again on the next sighting,
        // two seconds later, for the rest of the drive. The repair must not become the flood.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipFailureMs < CLIP_FAILURE_REPORT_MS) return
        lastClipFailureMs = now
        bot?.broadcast("⚠️ Не удалось записать видео <code>${TelegramClient.escape(plate)}</code> (код $error)", urgent = true)
    }

    private fun onClipFinished(file: File, plate: String, durationMs: Long, hitLimit: Boolean, sourceLost: Boolean) {
        Log.i(TAG, "clip ${file.name} ${durationMs / 1000}s ${file.length() / 1024}KB limit=$hitLimit sourceLost=$sourceLost")
        mainHandler.post {
            // A clip the recorder ended itself, because the file filled up, is the same situation
            // as one the session cut on time: the subject is still there and filming must go on.
            // Without this the recorder would fall silent for the rest of the pursuit the moment a
            // clip hit its ceiling. A clip somebody *stopped* — the operator, the absence timer,
            // the session ending — is over, whatever the file did; and a manual clip continues on
            // the operator's word alone, not on the follow engine's opinion of a label.
            val manual = manualRecording
            val stopped = recordingStopRequested
            recordingStopRequested = false
            val subjectStillHere = manual || follow?.evidenceFor(plate) != null
            val allowed = sessionRunning && !destroyed && (manual || runtime.videoEnabled)
            // A camera rebind (the stall watchdog, the blindness ladder) takes the use case away
            // underneath the clip: the file is fine and the subject is still there, so filming
            // goes on — after a moment, on the use case the rebind is about to create.
            val continues = !stopped && allowed &&
                (segmentPlate == plate || ((hitLimit || sourceLost) && subjectStillHere))
            segmentPlate = null
            if (continues) {
                // Long tail: keep filming in a new file instead of one clip Telegram will refuse.
                val restart = Runnable {
                    if (destroyed) return@Runnable
                    if (!sessionRunning || videoRecorder?.start(plate) != true) {
                        hideBanner()
                        manualRecording = false
                        // The follow-up failed, so this car is no longer being filmed; say so, or
                        // it would never be filmed again for the rest of the drive.
                        follow?.noteVideoFinished(plate)
                    }
                    refreshRecordButton()
                }
                if (sourceLost) mainHandler.postDelayed(restart, SOURCE_LOST_RETRY_MS) else restart.run()
            } else {
                hideBanner()
                manualRecording = false
                follow?.noteVideoFinished(plate)
            }
            refreshRecordButton()
        }
        deliverClip(file, evidenceByPlate[plate]?.displayPlate ?: plate, durationMs)
    }

    /**
     * Hands a clip to Telegram and remembers the outcome on disk: a delivered clip is renamed, so a
     * clip left behind by a crash or a failed upload is still recognisable as unsent next time.
     */
    private fun deliverClip(file: File, plate: String, durationMs: Long) {
        val active = bot
        if (active == null) {
            Log.w(TAG, "clip ${file.name} kept: no bot")
            return
        }
        // Too large for the Bot API. Cutting it into pieces is the difference between the driver
        // receiving the footage of the car that followed them and receiving a refusal naming a
        // file only the phone has — and the phone is the thing at risk in that scenario.
        if (file.length() > MAX_SENDABLE_BYTES) {
            splitAndDeliver(file, plate)
            return
        }
        // Uploading is asynchronous and can take minutes on a mobile uplink, while housekeeping
        // runs on its own timer and deletes anything undelivered past its retention. Without this
        // the sweep erased the very files that were mid-upload — and the driver got refusals
        // naming clips the app had just destroyed.
        clipsInFlight += file.name
        active.sendClip(file, plate, durationMs) { delivered ->
            if (delivered) {
                runCatching { file.renameTo(File(file.parentFile, "${SENT_PREFIX}${file.name}")) }
            } else {
                Log.w(TAG, "clip ${file.name} not delivered (${file.length() / (1024 * 1024)} MB)")
            }
            clipsInFlight -= file.name
        }
    }

    /**
     * Recovers the plate a clip is about from its file name.
     *
     * Clips are named `tail_<time>_<plate>.mp4`, and a piece of a split one gains a `_partNN`
     * suffix — so taking the text after the last underscore hands back "part01" and the driver
     * receives a video captioned with a word instead of a number plate.
     */
    private fun plateFromClipName(name: String): String = name
        .removeSuffix(".mp4")
        .substringBefore("_part")
        .substringAfterLast('_')

    /**
     * Cuts an oversized clip into uploadable pieces and sends those instead.
     *
     * Remuxing, not re-encoding, so it costs a second or two and loses nothing. The original keeps
     * its own name on the phone — it is still the unedited recording, and the pieces are what
     * travels. It is renamed out of the resend queue so the next start does not split it a second
     * time; the pieces stay in the queue, so any that failed to upload are retried like any other
     * clip.
     */
    private fun splitAndDeliver(file: File, plate: String) {
        val megabytes = file.length() / (1024 * 1024)
        clipsInFlight += file.name
        ioExecutor.execute {
            val parts = runCatching { ClipSplitter.split(file, MAX_SENDABLE_BYTES) }
                .getOrDefault(emptyList())
            mainHandler.post {
                clipsInFlight -= file.name
                if (isFinishing || isDestroyed) return@post
                if (parts.isEmpty()) {
                    // Nothing was lost — the footage is still on the phone — but the operator has
                    // to know the upload is not coming, or they will wait for evidence that never
                    // arrives.
                    runCatching { file.renameTo(File(file.parentFile, "$OVERSIZED_PREFIX${file.name}")) }
                    Log.w(TAG, "clip ${file.name} (${megabytes}MB) could not be split")
                    bot?.broadcast(
                        "⚠️ Клип <code>$plate</code> — $megabytes МБ, не влезает в Telegram и не " +
                            "делится. Файл на телефоне: <code>${file.name}</code>",
                    )
                    return@post
                }
                Log.i(TAG, "clip ${file.name} (${megabytes}MB) split into ${parts.size} parts")
                bot?.broadcast(
                    "✂️ Клип <code>$plate</code> — $megabytes МБ, режу на ${parts.size} части и отправляю",
                )
                runCatching { file.renameTo(File(file.parentFile, "$SPLIT_PREFIX${file.name}")) }
                parts.forEachIndexed { index, part ->
                    deliverClip(part, "$plate ${index + 1}/${parts.size}", 0L)
                }
            }
        }
    }

    /** Clips a previous run could not deliver: sent now rather than left on the phone forever. */
    /**
     * Delivers the note left by a session that died. A crashing process is in no position to make a
     * network call, so the next start does it — otherwise a crash mid-drive is discovered only by
     * the absence of results, hours later.
     */
    private fun reportPreviousCrash() {
        // A session is running: the note's "tap to restart" has no job left, and tapping it
        // hours later used to finish this very session.
        CrashReporter.dismiss(this)
        ioExecutor.execute {
            val report = CrashReporter.peek(this) ?: return@execute
            Log.w(TAG, "previous session crashed: $report")
            // No bot yet means the note stays on disk for the next start, rather than vanishing
            // unread — a crash during startup is exactly when the bot is not connected.
            val active = bot ?: return@execute
            // Escaped: a stack trace is full of `<init>` and generics, and unescaped it was
            // invalid HTML that Telegram refused — so the one message about the crash was the
            // one that never arrived. And the note is deleted only once somebody has read it.
            active.broadcast(
                "💥 <b>Прошлая сессия упала</b>\n<pre>${TelegramClient.escape(report.take(1200))}</pre>",
                urgent = true,
            ) { delivered -> if (delivered) CrashReporter.clear(this) }
        }
    }

    /**
     * Sums up the drive the moment it ends, and sends the map with it.
     *
     * Stopping the session used to just release the camera and say nothing, which left the operator
     * to remember to ask for a report. The answer to "was anyone following me" should arrive when
     * the question is still live — at the kerb, not two hours later.
     */
    private fun sendTripDebrief() {
        val started = tripStartMs
        val distanceKm = (tracker?.odometerM ?: 0.0) / 1000.0
        val turns = tracker?.turnCount ?: 0
        val since = if (started > 0L) started else System.currentTimeMillis() - DEFAULT_REPORT_WINDOW_MS
        ioExecutor.execute {
            val vehicles = runCatching { store.vehiclesSeenSince(since) }.getOrDefault(emptyList())
            val tails = vehicles.count { it.level >= ThreatLevel.TAIL.rank || it.blacklisted }
            val suspects = vehicles.count { it.level == ThreatLevel.SUSPECT.rank }
            val watched = vehicles.count { it.level == ThreatLevel.WATCH.rank }
            val minutes = ((System.currentTimeMillis() - since) / 60_000).coerceAtLeast(0)
            val summary = buildString {
                append("🏁 <b>Поездка закончена</b>\n")
                append(String.format(Locale.US, "%d мин · %.1f км · поворотов %d\n", minutes, distanceKm, turns))
                append("машин распознано: ${vehicles.size}\n")
                if (tails > 0) append("🔴 хвостов: $tails\n")
                if (suspects > 0) append("🟠 подозрительных: $suspects\n")
                if (watched > 0) append("🟡 под наблюдением: $watched\n")
                if (tails == 0 && suspects == 0) append("✅ ничего похожего на слежку\n")
                append(cropStats.summary())
            }
            bot?.broadcast(summary)
            val report = runCatching {
                reportBuilder?.build(HtmlReportBuilder.Options(sinceMs = since, cropStats = cropReport()))
            }.getOrNull()
            if (report != null) bot?.sendReport(report, "🗺 Отчёт по поездке")
        }
    }

    /**
     * Sends the clips that never made it, and stops the ones that never will from blocking them.
     *
     * Two things went wrong here at once. The queue took the five *oldest* files, so a handful of
     * clips too large for Telegram — everything recorded before the bitrate was capped — were
     * picked again on every single start, refused again, and kept the newer, perfectly sendable
     * clips from ever being chosen. And retention only ever deleted files already delivered, so
     * everything that failed accumulated for good: an hour of tailing is around thirty segments,
     * and a week of that fills the phone.
     */
    /**
     * Offers the backlog again while driving, not only at start-up.
     *
     * A drive that began out of coverage used to keep its clips until the next launch; once the
     * signal was back nothing tried them again for the rest of the day.
     */
    private fun resendClipsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipResendMs < CLIP_RESEND_INTERVAL_MS) return
        if (clipsInFlight.isNotEmpty()) return
        val active = bot ?: return
        if (active.unreachableForMs(now) > 0L) return
        lastClipResendMs = now
        resendPendingClips()
    }

    private fun resendPendingClips() {
        ioExecutor.execute {
            val dir = File(filesDir, "clips")
            val all = dir.listFiles { file ->
                file.isFile && file.name.startsWith(CLIP_PREFIX) && file.name.endsWith(".mp4")
            }.orEmpty()

            // Clips from before the encoder was pinned down run 60-190 MB. These used to be set
            // aside and forgotten; now they are cut into uploadable pieces, which is the only way
            // the recording ever leaves the phone. Oldest first so a backlog arrives in order.
            val big = all.filter { it.length() > MAX_SENDABLE_BYTES }
                .sortedBy { it.lastModified() }
                .take(MAX_RESEND_CLIPS)

            // Newest first: the last clip of the previous drive is the one worth having.
            val pending = all
                .filter { it.length() in 1..MAX_SENDABLE_BYTES }
                .sortedByDescending { it.lastModified() }
                .take(MAX_RESEND_CLIPS)
            // By name, and after the queue is built. Retention deletes anything undelivered past
            // three days, and a weekend without coverage puts every clip in that bracket — so
            // pruning first destroyed the very files this function had just decided to send, and
            // the driver got five refusals naming files the app had erased a moment earlier.
            // Uploading is asynchronous, so the exclusion has to survive past this call.
            //
            // The oversized ones belong in here too, and by a wider margin: splitting is queued
            // onto this same single io thread, so it necessarily runs *after* the prune below.
            // Leaving them out meant the sweep deleted a 190 MB recording seconds before the
            // splitter opened it, and the chat received "0 МБ, не делится" naming a file the app
            // had just destroyed. A clip old enough to need splitting is old enough to be swept.
            val keep = (pending + big).mapTo(HashSet()) { it.name }
            big.forEach { file ->
                val plate = plateFromClipName(file.name)
                Log.i(TAG, "clip ${file.name} is ${file.length() / (1024 * 1024)}MB; splitting")
                mainHandler.post { if (!isFinishing && !isDestroyed) splitAndDeliver(file, plate) }
            }
            pending.forEach { file ->
                val plate = plateFromClipName(file.name)
                Log.i(TAG, "resending clip ${file.name}")
                deliverClip(file, plate, 0L)
            }
            pruneClips(dir, keep + clipsInFlight.toSet())
        }
    }

    /**
     * Periodic housekeeping while driving.
     *
     * The start-up pass cannot bound a long trip: an hour of continuous tailing writes some thirty
     * segments, and the budget was only ever checked before any of them existed. Running from the
     * watchdog also makes the "do not touch the file being written" guard mean something — at
     * start-up the recorder does not exist yet, so it never protected anything.
     */
    private fun pruneClipsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipPruneMs < CLIP_PRUNE_INTERVAL_MS) return
        lastClipPruneMs = now
        ioExecutor.execute {
            runCatching { pruneClips(File(filesDir, "clips"), clipsInFlight.toSet()) }
        }
    }

    /**
     * Keeps the clip directory inside a budget, cheapest evidence first.
     *
     * Order matters more than age: a `sent_` clip is already in Telegram, so deleting it costs
     * nothing, while an undelivered one is the only copy in existence. Files being written right
     * now are left alone — this runs on the io thread while the recorder may be mid-clip.
     */
    private fun pruneClips(dir: File, keep: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        val recording = videoRecorder?.isRecording == true
        fun inUse(file: File) =
            file.name in keep || (recording && now - file.lastModified() < ACTIVE_CLIP_GRACE_MS)

        dir.listFiles()?.forEach { file ->
            if (!file.isFile || inUse(file)) return@forEach
            val limit = if (file.name.startsWith(SENT_PREFIX)) CLIP_RETENTION_MS else UNSENT_RETENTION_MS
            if (now - file.lastModified() > limit) runCatching { file.delete() }
        }

        val files = dir.listFiles()?.filter { it.isFile && !inUse(it) }.orEmpty()
        var total = files.sumOf { it.length() }
        // Two independent reasons to delete, and the second one is what makes this recoverable.
        // The budget alone could not free anything on a phone whose disk was full of somebody
        // else's photos: the clips directory would sit at half a gigabyte, well under budget,
        // while recording refused to start for want of space — every five minutes, for the whole
        // drive, with the app promising a cleanup that deleted nothing.
        val free = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val overBudget = total > CLIP_DIR_BUDGET_BYTES
        // Only chase the free-space target when the clips could actually reach it. On a phone
        // filled by somebody else's photos, deleting every undelivered clip would still leave too
        // little room to record — so it would destroy the only copy of an hour of evidence and buy
        // nothing at all. Better to keep the footage and say plainly that the disk is full.
        val starved = free < MIN_FREE_BYTES && free + total >= MIN_FREE_BYTES
        if (!overBudget && !starved) {
            if (free < MIN_FREE_BYTES) {
                Log.w(TAG, "only ${free / (1024 * 1024)}MB free and clips hold just " +
                    "${total / (1024 * 1024)}MB; keeping them")
            }
            return
        }
        Log.w(TAG, "pruning clips: ${total / (1024 * 1024)}MB used, ${free / (1024 * 1024)}MB free")
        // Cheapest evidence first: a `sent_` clip is already in Telegram, an undelivered one is
        // the only copy there is.
        val victims = files.sortedWith(
            compareByDescending<File> { it.name.startsWith(SENT_PREFIX) }.thenBy { it.lastModified() },
        )
        var freed = 0L
        for (file in victims) {
            val stillOver = total > CLIP_DIR_BUDGET_BYTES
            val stillStarved = starved && (free + freed) < MIN_FREE_BYTES
            if (!stillOver && !stillStarved) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= size
                freed += size
            }
        }
        if (freed > 0) Log.i(TAG, "freed ${freed / (1024 * 1024)}MB of clips")
    }

    /**
     * Decides whether the second recognition stage may run right now.
     *
     * In experiment mode the two strategies take turns for a minute each, so both are measured
     * against the same road, the same light and the same traffic — the only way to compare them
     * without asking the operator to drive the route twice.
     */
    /**
     * Notices when the camera has stopped delivering frames.
     *
     * A stalled stream — the HAL dropping a session after a thermal event, another app taking the
     * camera, a driver switching away and back — looks exactly like an empty road: the overlay keeps
     * its last picture, the bot keeps answering, and nothing at all is being watched. For an
     * application whose whole job is to notice what is behind you, silence has to be loud.
     */
    private fun checkCameraAlive() {
        val active = processor
        if (!sessionRunning || active == null) {
            stallStrikes = 0
            return
        }
        // A camera that never delivered its first frame is judged from the moment it was asked
        // to start. The zero sentinel used to mean "nothing to check", so a session whose camera
        // never came up at all was the one session no watchdog ever looked at.
        val lastFrame = if (active.lastAnalyzedAtMs != 0L) active.lastAnalyzedAtMs else cameraStartedAtMs
        val since = SystemClock.elapsedRealtime() - lastFrame
        if (lastFrame == 0L || since < STALL_LIMIT_MS) {
            if (stallStrikes > 0) {
                stallStrikes = 0
                lastRebindAtMs = 0L
                hideBanner()
                bot?.broadcast("📷 Камера снова отдаёт кадры", urgent = true)
            }
            return
        }

        // Count failed recoveries, not watchdog ticks: a rebind needs longer than one tick to bring
        // frames back, and counting ticks would tear down the attempt that was about to succeed and
        // then give up altogether while the camera was still coming up.
        val sinceRebind = SystemClock.elapsedRealtime() - lastRebindAtMs
        if (lastRebindAtMs != 0L && sinceRebind < REBIND_GRACE_MS) return

        stallStrikes += 1
        lastRebindAtMs = SystemClock.elapsedRealtime()
        cameraStartedAtMs = lastRebindAtMs
        Log.w(TAG, "no frames for ${since / 1000}s, recovery attempt $stallStrikes")
        showBanner("📷 Камера молчит ${since / 1000} с — перезапускаю ($stallStrikes)")
        controller?.rebind()
        if (stallStrikes == STALL_ALERT_AFTER) {
            bot?.broadcast(
                "⚠️ Камера не отдаёт кадры ${since / 1000} с и не поднимается перезапуском. " +
                    "Сканирование сейчас ничего не видит — перезапусти приложение.",
                urgent = true,
            )
        }
    }

    /**
     * Notices when frames arrive but nothing is being recognised.
     *
     * [checkCameraAlive] catches a dead stream. This catches the other half, which has no symptom
     * at all: the camera is delivering, the preview is live, the bot answers cheerfully — and every
     * frame is being dropped at the gate because a lens will not verify or the scanner put itself
     * to sleep. From the driver's seat that is indistinguishable from an empty road, which is
     * precisely why it has to be said out loud.
     */
    private fun checkRecognitionAlive() {
        val active = processor
        val now = SystemClock.elapsedRealtime()
        if (!sessionRunning || active == null || gate.paused) {
            // A pause is not a fault, and the clock has to start again from the moment it ends —
            // otherwise the whole stop counts as blindness on the tick that lifts it.
            rebaseBlindClock()
            return
        }
        // No frames at all is the camera watchdog's problem, not this one. Rebase as well: when the
        // stream comes back, the gap belongs to the camera, and judging it here would spend a rung
        // of this ladder on a fault the other watchdog has already handled.
        if (active.lastAnalyzedAtMs == 0L || now - active.lastAnalyzedAtMs > STALL_LIMIT_MS) {
            gateResumedAtMs = now
            return
        }
        // From the last frame that got through, or from the moment this session first saw a frame
        // if none ever has — never from zero, which would read as the phone's whole uptime and
        // declare an outage before the first lens had finished verifying.
        val passed = active.lastPassedGateAtMs
        val since = maxOf(passed, active.firstAnalyzedAtMs, gateResumedAtMs)
        val blindFor = now - since
        if (blindStrikes > 0) {
            // Inside a blind spell only one thing counts as recovery: a frame that actually got
            // through since the spell began. The grace after a rebind delays the next rung; it
            // proves nothing, and it used to be mistaken for the cure.
            if (passed > blindDetectedAtMs) {
                clearBlindState()
                hideBanner()
                bot?.broadcast("🔓 Кадры снова доходят до распознавания", urgent = true)
                speak("Распознавание восстановлено.")
                return
            }
            if (blindFor < BLIND_LIMIT_MS) return
        } else {
            if (since != 0L && blindFor < BLIND_LIMIT_MS) return
            blindDetectedAtMs = now
        }
        blindStrikes += 1
        // The verifier's own verdict, not a guess from the gate flags. Two drives died this way
        // and left no diagnosis at all; ZOOM_MISMATCH and ACTIVE_ID_MISMATCH mean very different
        // things and only one of them is worth changing the lens plan over.
        val snapshot = controller?.verifier?.snapshot
        val detail = snapshot
            ?.takeIf { it.reason != LensVerifier.Reason.NONE }
            ?.let { ", ${it.state}/${it.reason}" }
            .orEmpty()
        val why = when {
            !gate.lensUsable -> "линза $lensLabel не подтверждена$detail"
            gate.transitioning -> "переключение линзы не завершилось$detail"
            else -> "шлюз закрыт"
        }
        showBanner("🔒 Кадры идут, но не распознаются ($why)")
        if (blindStrikes == BLIND_ALERT_AFTER) {
            bot?.broadcast(
                "⚠️ Камера отдаёт кадры, но распознавание их не получает ($why) уже " +
                    "${blindFor / 1000} с. Хвост сейчас не определяется. Пробую поднять.",
                urgent = true,
            )
            speak("Внимание. Распознавание не работает. Восстанавливаю.")
        }
        recoverFromBlindness(now, why, blindFor)
    }

    /**
     * Tries to reopen a frame gate that has stayed shut.
     *
     * Two drives ended this way: the camera delivering, GPS logging, the bot answering, and not one
     * plate read for the next ninety minutes. Telling the driver was not enough — nobody reads a
     * banner on a phone pointed backwards, and a warning about a scanner that has stopped scanning
     * is worth much less than a scanner that starts again.
     *
     * The ladder goes from cheapest to most destructive, one rung per grace period so each attempt
     * is given time to work before the next is judged. Skipping the lens comes first because a
     * single zoom step that cannot be verified is the likeliest cause and the cheapest to leave
     * behind; rebinding rebuilds the whole session; and if even that fails twice the process is
     * restarted, which is the only thing left and which now carries the trip across.
     */
    private fun recoverFromBlindness(nowMs: Long, why: String, blindFor: Long) {
        if (nowMs - lastBlindActionMs < BLIND_RECOVERY_GRACE_MS) return
        lastBlindActionMs = nowMs
        blindActions += 1
        when (blindActions) {
            1 -> {
                Log.w(TAG, "gate shut for ${blindFor / 1000}s ($why); skipping the lens step")
                scheduler?.advance()
            }

            2, 3 -> {
                Log.w(TAG, "gate still shut ($why); rebinding the camera (attempt $blindActions)")
                showBanner("🔒 $why — пересобираю камеру ($blindActions)")
                controller?.rebind()
                // A rebind reopens the verification window; the clock must not count it as more
                // blindness, or the next tick would already be asking for the rung after this one.
                gateResumedAtMs = SystemClock.elapsedRealtime()
            }

            else -> {
                if (blindGaveUp) return
                blindGaveUp = true
                Log.e(TAG, "gate shut after every recovery ($why); restarting the process")
                bot?.broadcast(
                    "⛔️ Распознавание не поднялось ($why) — перезапускаю приложение, " +
                        "поездка продолжится в том же треке.",
                    urgent = true,
                )
                mainHandler.postDelayed(
                    { restartProcess("шлюз кадров закрыт", "распознавание не поднимается ($why)") },
                    PROCESS_RESTART_DELAY_MS,
                )
            }
        }
    }

    /**
     * Notices that Telegram cannot be reached and says so once, out loud.
     *
     * The bot is the channel every warning travels down, and when it is down it cannot report its
     * own silence — so an operator watching a quiet chat concludes the road was quiet. It is not
     * always the network's fault either: this phone currently holds an IPv6-only LTE link with no
     * DNS servers, on which the API host simply does not resolve. The voice still works offline,
     * which is exactly why it is the one that has to carry this.
     */
    private fun checkBotReachable() {
        val active = bot ?: return
        // No network is the expected state of airplane mode, not a fault worth a sentence — and
        // the minutes spent in it are not counted against the link once it is back.
        if (airplaneMode) return
        val now = SystemClock.elapsedRealtime()
        var down = active.unreachableForMs(now)
        if (airplaneOffAtMs != 0L) down = minOf(down, now - airplaneOffAtMs)
        if (down < BOT_UNREACHABLE_LIMIT_MS) {
            if (botUnreachableTold) {
                botUnreachableTold = false
                hideBanner()
                speak("Связь с Telegram восстановлена.")
            }
            return
        }
        if (botUnreachableTold) return
        botUnreachableTold = true
        Log.w(TAG, "telegram unreachable for ${down / 1000}s")
        showBanner("📵 Нет связи с Telegram — тревоги в чат не уходят")
        speak("Внимание. Нет связи с Telegram. Тревоги буду говорить только голосом.")
    }

    /**
     * Forgets everything about a blind spell.
     *
     * All four fields together, always. Clearing only [blindStrikes] left the ladder's position
     * behind, so the next spell resumed from whatever rung the previous one reached — and a car
     * that had merely been parked could arrive at "restart the process" on its first tick.
     */
    private fun clearBlindState() {
        blindStrikes = 0
        blindActions = 0
        blindGaveUp = false
        lastBlindActionMs = 0L
        blindDetectedAtMs = 0L
    }

    /** The gate is allowed to open again; give it a clean run before judging it. */
    private fun rebaseBlindClock() {
        gateResumedAtMs = SystemClock.elapsedRealtime()
        clearBlindState()
    }

    private fun applyCropStrategy() {
        val processor = processor ?: return
        processor.narrowAllowed = when (runtime.narrowCrops) {
            RuntimeSettings.NARROW_OFF -> false
            RuntimeSettings.NARROW_EXPERIMENT -> {
                val block = (System.currentTimeMillis() - tripStartMs) / RuntimeSettings.EXPERIMENT_BLOCK_MS
                block % 2L == 1L
            }
            else -> true
        }
    }

    /**
     * Starts or stops the manual clip. Main thread only: the recorder has one owner.
     *
     * Returns what to tell whoever asked — the button or the bot — and says so truthfully: the
     * old answer was composed before the recorder had been asked, so "🎥 Пишу" went out for a
     * clip that had been refused for want of space or of a video stream.
     */
    private fun toggleRecordingOnMain(): String {
        val recorder = videoRecorder ?: return "Видео недоступно"
        val answer = if (recorder.isRecording) {
            val target = recorder.currentTarget ?: ""
            stopRecording()
            "⏹ Останавливаю запись $target".trim()
        } else if (recorder.videoCapture == null) {
            "⛔️ Видеопоток недоступен: камера не отдала его при запуске"
        } else if (recorder.start("manual")) {
            manualRecording = true
            recordingStopRequested = false
            "🎥 Пишу вручную — /rec ещё раз чтобы закончить"
        } else {
            "⚠️ Запись не началась: мало места или камера отказала"
        }
        refreshRecordButton()
        return answer
    }

    /**
     * Runs [block] on the main thread and waits for its answer, unless we already are there.
     *
     * The bot's answers used to be composed on the bot's thread from state the main thread was
     * about to change: "already stopped" for a /stop that arrived right after /go, "resumed" for
     * a resume that left the parked sleep in place. The truth lives on one thread; the caller
     * waits a moment for it rather than guessing.
     */
    private fun onMainSync(fallback: String, block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val holder = arrayOf(fallback)
        val posted = mainHandler.post {
            runCatching { holder[0] = block() }
            latch.countDown()
        }
        if (!posted) return fallback
        return if (latch.await(MAIN_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) holder[0] else fallback
    }

    /** The record button doubles as the indicator: a stop sign while a clip is being written. */
    private fun refreshRecordButton() {
        binding.btnRecord.text = if (videoRecorder?.isRecording == true) "⏹" else "🎥"
    }

    /** The target leaving the picture is exactly the event nothing else would notice. */
    private val recordingWatchdog = object : Runnable {
        override fun run() {
            updateParkedState()
            applyCropStrategy()
            checkCameraAlive()
            checkRecognitionAlive()
            checkBotReachable()
            maybeRecycleEngine()
            checkOcrStuck()
            pruneClipsIfDue()
            resendClipsIfDue()
            val now = System.currentTimeMillis()
            follow?.sweepTurns(now)
            // Unconditional, and not inside the recorder block below: whether a car has left the
            // picture is a question about the car, not about whether a clip happens to be running.
            follow?.sweepPresence(now)
            companions = follow?.companions(now).orEmpty()
            updatePanelTitle(statusTracked)
            bot?.ensureAlive()
            refreshRecordButton()
            val recorder = videoRecorder
            val target = recorder?.currentTarget
            if (recorder != null && target != null && !recordingStopRequested) {
                if (recorder.elapsedMs > MAX_CLIP_MS) {
                    // Cut a segment instead of growing one file past what Telegram accepts. The
                    // target is still with us, so recording continues in the next file. Never on
                    // a clip that is already being stopped: that used to turn the operator's Stop
                    // back into a continuation.
                    segmentPlate = target
                    recorder.stop()
                } else if (!manualRecording) {
                    follow?.checkFollowerAbsence(target, System.currentTimeMillis())
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Standing still costs battery and heat for nothing. Recognition sleeps until the car moves -
     * unless a clip is running, because a follower waiting next to a parked car is the whole point.
     */
    private fun updateParkedState() {
        // A stopped session is not "parked"; announcing it would only confuse the operator.
        if (!sessionRunning) return
        // Neither is a session that never came up. The watchdog now starts before the pipeline, so
        // without this a failed start would report "😴 стоим на месте" — which is true and
        // completely beside the point when the real news is that there is no scanner at all.
        if (processor == null) return
        if (!runtime.autoPauseParked) {
            if (parkedPause) {
                parkedPause = false
                applyPause()
            }
            return
        }
        val now = System.currentTimeMillis()
        val recording = videoRecorder?.isRecording == true
        val moving = now - lastMovingMs < PARKED_AFTER_MS
        // "Standing still" is only ever inferred from GPS speed, and the only thing that can wake
        // the scanner again is a fix showing movement. So without a working location feed — no
        // permission, a refused subscription, a tunnel, a phone with no fix yet — the five-minute
        // timer would fire once and close the frame gate for the rest of the drive, with a preview
        // still running and the bot still answering. Sleep is only allowed while something is
        // actually able to wake it up.
        val trip = tracker
        val speedSourceAlive = trip != null &&
            trip.isRunning &&
            trip.current != null &&
            now - (trip.current?.tMs ?: 0L) < FIX_STALE_MS
        val shouldPause = speedSourceAlive && !moving && !recording
        if (shouldPause == parkedPause) return
        parkedPause = shouldPause
        applyPause()
        if (shouldPause) {
            showBanner("😴 Стоим на месте — распознавание спит")
            bot?.broadcast("😴 Стоим на месте — распознавание уснуло, проснётся при движении")
        } else {
            hideBanner()
            bot?.broadcast("🚗 Поехали — распознавание проснулось")
        }
    }

    /** Releases the camera and the location feed; the process stays alive for the bot. */
    private fun haltSession() {
        if (!sessionRunning) return
        sessionRunning = false
        scheduler?.stop()
        // Not a segment break — the session is ending, so the clip must not try to continue.
        stopRecording()
        controller?.let { lastControllerGeneration = it.currentGeneration }
        controller?.shutdown()
        // The use case went away with the camera; a recorder still pointing at it would accept
        // `/rec`, announce a clip and fail it a second later.
        videoRecorder?.detach()
        tracker?.stop()
        // Through applyPause for the same reason as the resume side: one place decides what the
        // gate's paused flag means, so the two can never disagree about who paused what.
        applyPause()
        showBanner("🛑 Сессия остановлена из Telegram")
        ScanSessionService.start(this, getString(R.string.service_idle))
        sendTripDebrief()
    }

    /** Rebinds the camera and restarts the rotation with the same pipeline objects. */
    private fun resumeSession(): Boolean {
        if (sessionRunning) return true
        val rearSetup = setup ?: return false
        val frameProcessor = processor ?: return false
        val recorder = videoRecorder ?: VideoRecorder(applicationContext, ::onClipFinished, ::onClipFailed)
            .also { videoRecorder = it }

        sessionRunning = true
        // Through applyPause, never directly: writing the flag here discarded userPaused and
        // parkedPause, so /go from Telegram silently un-paused a session the driver had paused.
        applyPause()
        rebaseBlindClock()
        if (config.followEnabled && hasLocationPermission()) tracker?.start()
        hideBanner()
        ScanSessionService.start(this, getString(R.string.service_running))

        val rotation = LensRotationScheduler(
            onSwitch = { entry ->
                lensLabel = entry.step.label
                controller?.apply(entry.step)
            },
            onTick = ::renderRotation,
        )
        rotation.configure(planEntries)
        // The pause the gate is under right now is the pause the new scheduler starts under.
        rotation.setPaused(gate.paused)
        scheduler = rotation

        val camera = CameraController(
            context = this,
            lifecycleOwner = sessionOwner,
            previewView = binding.previewView,
            setup = rearSetup,
            config = config,
            gate = gate,
            analysisExecutor = analysisExecutor,
            analyzer = frameProcessor,
            onLensState = ::renderLens,
            onStepSettled = { step, _ -> rotation.onStepSettled(step) },
            onError = { message -> showBanner(getString(R.string.camera_error, message)) },
            videoRecorder = recorder,
            // Continues the numbering of the controller `/stop` shut down, so the frame processor
            // sees a new generation and starts its tracks over instead of inheriting the cars
            // that were in the picture before the stop.
            initialGeneration = lastControllerGeneration,
        )
        controller = camera
        cameraStartedAtMs = SystemClock.elapsedRealtime()
        camera.prepare {
            binding.previewView.doOnLayout {
                if (!destroyed && controller === camera && sessionRunning) rotation.start()
            }
        }
        return true
    }

    private fun onGeoFix(fix: GeoFix) {
        if (fix.speedMps > MOVING_SPEED_MPS) lastMovingMs = fix.tMs
        renderTrip(fix)
        if (fix.tMs - lastTrackPointMs < TRACK_POINT_INTERVAL_MS) return
        lastTrackPointMs = fix.tMs
        val trip = tripId
        if (trip == 0L) return
        val point = TrackPoint(fix.tMs, fix.lat, fix.lon, fix.speedMps, fix.odometerM)
        ioExecutor.execute { runCatching { store.appendTrackPoint(trip, point) } }
    }

    private fun onTurn(event: TurnEvent) {
        follow?.onTurn(event)
        tracker?.current?.let(::renderTrip)
        val trip = tripId
        if (trip == 0L) return
        val row = com.lensalpr.app.data.TurnRow(
            tMs = event.tMs,
            lat = event.lat,
            lon = event.lon,
            direction = event.direction.name,
            degrees = event.degrees,
        )
        ioExecutor.execute { runCatching { store.recordTurn(trip, row) } }
    }

    /**
     * Speed, turns and fix accuracy on screen.
     *
     * The turn counter is the one number that proves the follow engine has any input at all: if it
     * does not move while the car turns, every "shared turn" in the evidence would be a zero.
     */
    private fun renderTrip(fix: GeoFix) {
        val trip = tracker ?: return
        binding.trip.text = getString(
            R.string.hud_trip,
            fix.speedMps * 3.6f,
            trip.turnCount,
            (trip.odometerM / 1000.0).toFloat(),
            fix.accuracyM,
        )
    }

    /**
     * Something happened with a vehicle: paint it, say it, send it.
     *
     * The gate is the *reason*, not a level change. Gating on a level change meant every car was
     * announced exactly once and then never again — the classifier's inputs only grow, so nothing
     * could raise a confirmed tail a second time, and a car that had already been called a tail
     * could drive past twice more in complete silence. See [AlertReason].
     */
    private fun onThreat(evidence: FollowEvidence, reason: AlertReason) {
        evidenceByPlate[evidence.plate] = evidence
        publishVehicles()
        // A police mark deliberately leaves the threat level alone — a patrol car is not an
        // accusation — so it has to be let past a gate written in terms of that level, or the one
        // list the operator built by hand would be the one thing the app never mentions.
        // "After N separate meetings" is a rule of its own: a car the classifier has no verdict
        // on is still worth a word on the meeting the operator asked to be told about.
        val worthSaying = evidence.police ||
            evidence.level.rank >= ThreatLevel.WATCH.rank ||
            evidence.encounters >= runtime.alertAfterEncounters
        if (reason == AlertReason.NONE || !worthSaying) return
        // With tailing detection off, only the operator's own lists may speak.
        if (!config.followEnabled && !evidence.police && !evidence.blacklisted) return
        announce(evidence, reason)
        // The two lists the operator wrote by hand also get the screen, not only the voice.
        if (evidence.police || evidence.blacklisted) showMarkBanner(evidence)
        val active = bot ?: return
        // A "still behind us" line carries no photo, and it arrives every few seconds. Looking one
        // up would put a database read on the io thread at that rate for a picture nobody sends.
        if (reason == AlertReason.PRESENT) {
            ioExecutor.execute { active.alert(evidence, null, reason) }
            return
        }
        ioExecutor.execute {
            // storeKey, not plate: the engine and the store can settle on different spellings of
            // the same car, and looking the photo up under the engine's key would find nothing and
            // send the alarm blind.
            val photo = runCatching { store.encounters(evidence.storeKey).lastOrNull()?.photo }
                .getOrNull()
                ?.let(::File)
            active.alert(evidence, photo, reason)
        }
    }

    private fun publishVehicles() {
        val cards = registry.snapshot().map { card ->
            val evidence = evidenceByPlate[card.plate] ?: return@map card
            card.copy(
                level = evidence.level.rank,
                levelName = levelLabel(evidence.level, evidence.police),
                reasons = evidence.reasons.joinToString(" · ").takeIf { it.isNotBlank() },
            )
        }.sortedWith(
            // Police first among equals: its level is deliberately left at zero, so without this
            // the one car the driver marked by hand would sit at the bottom of the list.
            compareByDescending<VehicleCard> { it.level }
                .thenByDescending { it.levelName == "ПОЛИЦИЯ" }
                .thenByDescending { it.confirmedAtMs },
        )
        adapter.submitList(cards)
        updatePanelTitle(statusTracked)
    }

    private fun levelLabel(level: ThreatLevel, police: Boolean = false): String? = when {
        // The badge answers "what is this car", and for a patrol car that is the whole answer —
        // it outranks whatever the follow engine happened to conclude about its driving.
        police -> "ПОЛИЦИЯ"
        level == ThreatLevel.BLACKLIST -> "ЧС"
        level == ThreatLevel.TAIL -> "ХВОСТ"
        level == ThreatLevel.SUSPECT -> "ПОДОЗРЕНИЕ"
        level == ThreatLevel.WATCH -> "НАБЛЮДЕНИЕ"
        else -> null
    }

    /**
     * What the recognition engine is really doing, for `/status` and the preflight report.
     *
     * The raw state lies after the runtime limit — it stays READY while the engine refuses every
     * frame — and that is exactly the moment somebody asks the bot whether scanning still works.
     */
    /**
     * The crop-strategy measurement, packaged for the report.
     *
     * This is the number that decides a setting the operator cannot judge by eye, and it only ever
     * lived in a chat line that scrolls away. Putting it in the file makes it something that can be
     * kept, compared between drives, and argued about.
     */
    private fun cropReport(): HtmlReportBuilder.CropReport {
        val (wideSent, wideHits, wideMs) = cropStats.arm(narrow = false)
        val (narrowSent, narrowHits, narrowMs) = cropStats.arm(narrow = true)
        return HtmlReportBuilder.CropReport(
            wide = HtmlReportBuilder.CropArm(wideSent, wideHits, wideMs),
            narrow = HtmlReportBuilder.CropArm(narrowSent, narrowHits, narrowMs),
            mode = runtime.narrowCropsName(),
        )
    }

    private fun engineHealth(): String {
        val status = AlprEngine.status
        val uptime = AlprEngine.uptimeMs()
        return buildString {
            when {
                status.runtimeLimited ->
                    append("ЛИМИТ ${AlprEngine.CODE_RUNTIME_LIMIT} — номера не читаются, перезапуск $engineRestarts/$ENGINE_RESTART_LIMIT")

                status.state == AlprEngine.State.READY -> {
                    append("READY")
                    if (uptime > 0) append(", ${uptime / 60_000} мин наработки")
                }

                else -> append(status.state.toString())
            }
            if (status.limitHits > 0 && !status.runtimeLimited) {
                append(" (лимит ловили ${status.limitHits}×)")
            }
        }
    }

    /**
     * Two lines about the same car can sit in the queue together — a promotion and, seconds
     * later, the meeting count. Keyed by plate alone the second overwrote the first, and when the
     * first ended the queue looked empty: focus was given back and the music came up over the
     * line still being spoken.
     */
    private fun utteranceIdFor(plate: String): String = "$plate#${utteranceSeq++}"

    /** One short spoken line. Used for events the driver must hear, not for per-car chatter. */
    private fun speak(text: String) {
        if (!config.voiceAlerts || !ttsReady) return
        say(text, text.hashCode().toString(), VOICE_PRIORITY_STATUS)
    }

    /**
     * The queue has drained: nothing is speaking, so the next line may not be treated as an
     * interruption of anything.
     */
    private fun onSpeechEnded(utteranceId: String?) {
        utteranceId?.let(utterancePriorities::remove)
        if (tts?.isSpeaking == true && utterancePriorities.isNotEmpty()) return
        utterancePriorities.clear()
        speakingPriority = VOICE_PRIORITY_IDLE
        abandonAudioFocus()
    }

    /** The engine has started a queued line; the rank being spoken is now that line's. */
    private fun onSpeechStarted(utteranceId: String?) {
        val priority = utteranceId?.let(utterancePriorities::get) ?: return
        speakingPriority = priority
    }

    /**
     * Asks the system to quieten whatever else is playing for the duration of a spoken warning.
     *
     * The phone lives on the windscreen of a car that is usually playing music over Bluetooth. The
     * audio attributes decide how the sound is routed; this is what makes the music get out of its
     * way. Without it the warning is mixed underneath at whatever the media volume happens to be —
     * which, on a phone used as a dashcam, is often nothing at all.
     */
    private fun holdAudioFocus() {
        if (audioFocus != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .build()
        audioFocus = request
        runCatching { manager.requestAudioFocus(request) }
            .onFailure { audioFocus = null }
    }

    private fun abandonAudioFocus() {
        val request = audioFocus ?: return
        audioFocus = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    /**
     * Speaks one line, deciding whether it may interrupt whatever is already being said.
     *
     * Flushing unconditionally looked right — the alarm about a tail should not wait — but it made
     * the phone cut itself off: two cars behind us at once produced two insistent lines two seconds
     * apart, each wiping the other, and neither was ever heard to the end. Worse, an alarm erased
     * the sentence saying that recognition itself had died. So a line only interrupts something
     * *less* important than itself; against an equal it waits its turn, which costs a second or two
     * and is always finished.
     */
    private fun say(text: String, utteranceId: String, priority: Int): Boolean {
        val engine = tts ?: return false
        val mode = if (priority > speakingPriority) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        if (mode == TextToSpeech.QUEUE_FLUSH) utterancePriorities.clear()
        if (mode == TextToSpeech.QUEUE_FLUSH || speakingPriority == VOICE_PRIORITY_IDLE) {
            speakingPriority = priority
        }
        utterancePriorities[utteranceId] = priority
        holdAudioFocus()
        // The return code is the only word the engine gives about a line it refused; an ERROR
        // treated as success marked the alarm as spoken and never spoke it.
        val accepted = runCatching { engine.speak(text, mode, null, utteranceId) }
            .getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        if (!accepted) {
            utterancePriorities.remove(utteranceId)
            if (utterancePriorities.isEmpty()) {
                speakingPriority = VOICE_PRIORITY_IDLE
                abandonAudioFocus()
            }
        }
        return accepted
    }

    private fun initVoice() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                voiceState = "движок синтеза не запустился"
                Log.w(TAG, "tts init failed: $status")
                return@TextToSpeech
            }
            // The result of setLanguage was being thrown away. On a phone whose TTS has no Russian
            // voice data that call fails and the alarm is simply never spoken — while every other
            // signal says the scanner is fine. For a driver watching the road, the voice is the
            // channel that matters, so whether it works is part of the readiness check.
            val applied = runCatching { tts?.setLanguage(Locale.forLanguageTag("ru")) }.getOrNull()
            voiceState = when (applied) {
                TextToSpeech.LANG_MISSING_DATA ->
                    "русский голос не установлен — тревоги вслух не прозвучат"

                TextToSpeech.LANG_NOT_SUPPORTED ->
                    "русский не поддерживается движком — тревоги вслух не прозвучат"

                null -> "не удалось выбрать язык"
                else -> null
            }
            voiceState?.let { Log.w(TAG, "tts language: $it") }
            // Navigation guidance, not media. On the default usage the warning comes out of the
            // media stream: it competes with whatever is playing in the car instead of ducking it,
            // and on a phone whose media volume is turned down it is not heard at all. This is the
            // usage built for exactly this — a short spoken instruction that has to reach a driver
            // over music, and that the car stereo routes to the speakers rather than swallowing.
            runCatching {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            // Both halves of the interruption rule. The listener is what makes "something more
            // important is already speaking" a fact rather than a guess, and the audio focus is
            // what actually ducks the music: the usage above decides how the system routes the
            // sound, but only a focus request makes another app get quieter for it.
            runCatching {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { onSpeechStarted(utteranceId) }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onSpeechEnded(utteranceId) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        mainHandler.post { onSpeechEnded(utteranceId) }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onSpeechEnded(utteranceId) }
                    }
                })
            }
            ttsReady = true
            // Proves the channel out loud, once, at the start of every session. A driver has no
            // other way to learn that the voice is broken until the moment it fails to warn them,
            // and by then the car is already behind them. Deliberately a full sentence with a
            // digit in it: that is what exercises the Russian voice data the alarms need.
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                speak("Сканирование запущено. Голос работает, тревоги прозвучат вслух.")
            }
        }
    }

    /**
     * Spelled out character by character: a plate read as a word is useless at speed.
     *
     * A repeat is deliberately shorter. The first time a car is called a tail the driver needs the
     * plate; the fourth time the same car comes back, dictating seven characters again is noise
     * that competes with the road for attention — what matters is that it is *back*.
     */
    private fun announce(evidence: FollowEvidence, reason: AlertReason) {
        if (!config.voiceAlerts || !ttsReady) return
        // The same threshold that governs Telegram, so the operator tunes one number instead of
        // discovering that the phone talks about cars the chat does not consider worth mentioning.
        // A confirmed tail and a car on the operator's own list are never subject to it: those two
        // are the reason the app exists, and a threshold that can silence them is a setting that
        // can turn off the alarm without saying so.
        val insistent = AlertPolicy.isPersistent(evidence.level, evidence.blacklisted || evidence.police)
        // The same two thresholds the chat applies — the level, or the number of separate
        // meetings — so the voice and the chat agree about which cars are worth a word.
        val meetsThreshold = evidence.level.rank >= runtime.alertMinLevel ||
            evidence.encounters >= runtime.alertAfterEncounters
        if (!insistent && !meetsThreshold) return
        val now = SystemClock.elapsedRealtime()
        val spokenAt = lastSpokenMs[evidence.plate] ?: 0L
        val persistent = insistent
        // A promotion is never a repeat. "Наблюдение" a minute ago and "ХВОСТ" now are different
        // facts, and swallowing the second one to avoid saying the plate twice would silence the
        // single most important thing the app has to say. A tail or a listed car is not held back
        // by the long cooldown at all: while it is behind us, being told is the point.
        val floor = when {
            reason == AlertReason.RAISED -> 0L
            persistent -> VOICE_PERSISTENT_MS
            else -> VOICE_COOLDOWN_MS
        }
        if (floor > 0L && spokenAt != 0L && now - spokenAt < floor) return
        // The one thing worse than saying nothing is saying it late. TextToSpeech queues, so
        // repeats stacked behind an unfinished sentence would drift further and further from the
        // road until the phone was calmly describing a car that left minutes ago.
        if (reason == AlertReason.PRESENT && tts?.isSpeaking == true) return
        val header = when {
            // Police first: if a car is on both lists, "police" is the fact that changes what the
            // driver does next.
            evidence.police -> "Внимание, полиция"
            evidence.blacklisted -> "Внимание, чёрный список"
            evidence.level == ThreatLevel.TAIL -> "Внимание, хвост"
            evidence.level == ThreatLevel.SUSPECT -> "Подозрительная машина"
            else -> "Машина рядом"
        }
        // The insistent form: level and plate, nothing else. Said every few seconds while the car
        // is there, so it has to be short enough to finish before the next one is due — and the
        // driver already heard the make, the colour and the reasons the first time.
        if (reason == AlertReason.PRESENT) {
            val short = "$header. ${PlateSpeech.spell(evidence.displayPlate)}."
            // A reminder, not news: it never interrupts, it only fills a silence.
            if (say(short, utteranceIdFor(evidence.plate), VOICE_PRIORITY_REMINDER)) lastSpokenMs[evidence.plate] = now
            return
        }
        val line = buildString {
            append(header)
            if (reason != AlertReason.RAISED) append(", снова рядом")
            append(". Номер ").append(PlateSpeech.spell(evidence.displayPlate)).append('.')
            // Everything known about the car, in the order a person would ask: what is it, what
            // colour, and why am I being told. A plate alone identifies nothing in a mirror.
            // Colour through the vocabulary: the classifier answers in English, and a Russian
            // voice reading "white" produces a sound that identifies no car at all. The make is
            // left as it comes — brand names survive being read aloud, colours do not.
            listOfNotNull(evidence.makeModel, PlateSpeech.word(evidence.color))
                .takeIf { it.isNotEmpty() }
                ?.let { append(' ').append(it.joinToString(", ")).append('.') }
            val minutes = evidence.contactMs / 60_000
            if (reason == AlertReason.RAISED) {
                if (evidence.sharedTurns > 0) {
                    append(" Общих поворотов: ").append(evidence.sharedTurns).append('.')
                }
                if (minutes >= 1) append(" Рядом ").append(minutes).append(" минут.")
                if (evidence.places > 1) append(" Встречались в разных местах.")
            } else {
                val away = evidence.awayMs / 60_000
                if (away >= 1) append(" Пропадала на ").append(away).append(" минут.")
            }
        }
        // A tail or a listed car outranks a merely suspicious one and the startup chatter, so it
        // cuts in rather than waiting behind them. It does not outrank another tail: see [say].
        // Marked as spoken only when the engine accepted the line.
        if (say(line, utteranceIdFor(evidence.plate), if (insistent) VOICE_PRIORITY_ALARM else VOICE_PRIORITY_NOTICE)) {
            lastSpokenMs[evidence.plate] = now
        }
    }

    /** Builds the HTML report and hands it to any app that can open or forward it. */
    private fun shareReport() {
        Toast.makeText(this, "Собираю отчёт…", Toast.LENGTH_SHORT).show()
        val since = botHost.currentTripStartMs()
        Log.i(TAG, "report requested since=$since builder=${reportBuilder != null}")
        ioExecutor.execute {
            val file = try {
                reportBuilder?.build(HtmlReportBuilder.Options(sinceMs = since, cropStats = cropReport()))
            } catch (error: Throwable) {
                Log.e(TAG, "report build failed", error)
                null
            }
            Log.i(TAG, "report file=${file?.absolutePath} size=${file?.length()}")
            mainHandler.post {
                if (file == null || isFinishing || isDestroyed) {
                    Toast.makeText(this, "Нет данных для отчёта", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.files",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(android.content.Intent.createChooser(intent, "Отчёт")) }
            }
        }
    }

    /**
     * Long press records every crop the engine receives; the next long press stops the recording
     * and replays the whole set through each configuration.
     */
    private fun toggleCapture() {
        val dir = File(cacheDir, "bench")
        if (worker.captureDir == null) {
            worker.startCapture(dir)
            Toast.makeText(this, "Запись вырезов началась — снимай машины", Toast.LENGTH_LONG).show()
            return
        }
        val count = worker.stopCapture()
        Toast.makeText(this, "Записано $count вырезов, считаю…", Toast.LENGTH_LONG).show()
        ioExecutor.execute {
            val report = runCatching {
                com.lensalpr.app.bench.OcrBenchmark.runDataset(applicationContext, config, dir)
            }.getOrElse { error ->
                Log.e(TAG, "dataset benchmark failed", error)
                "ошибка: ${error.message}"
            }
            Log.i(TAG, "dataset\n$report")
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, report, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Freezes one vehicle crop and replays it through every engine configuration. */
    private fun runBenchmark() {
        val processor = processor ?: return
        Toast.makeText(this, "Замер: держи кадр 2 секунды", Toast.LENGTH_SHORT).show()
        processor.requestCrop { crop ->
            ioExecutor.execute {
                val report = runCatching {
                    com.lensalpr.app.bench.OcrBenchmark.run(applicationContext, config, crop)
                }.getOrElse { error ->
                    Log.e(TAG, "benchmark failed", error)
                    "ошибка: ${error.message}"
                }
                runCatching { crop.recycle() }
                Log.i(TAG, "benchmark\n$report")
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, report, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * One message that answers "is this thing actually ready to drive with?".
     *
     * Every line here is something that silently ruins a trip: a lens that never confirmed, no GPS
     * fix, a camera that refused the video use case, a battery optimisation that will kill the
     * service in half an hour.
     */
    private fun preflightReport(): String {
        val resolution = controller?.analysisResolution
        val verification = controller?.verifier?.snapshot
        val fix = tracker?.current
        val free = runCatching { cacheDir.usableSpace / (1024L * 1024L) }.getOrDefault(0L)
        val battery = runCatching {
            val manager = getSystemService(android.os.PowerManager::class.java)
            manager?.isIgnoringBatteryOptimizations(packageName) == true
        }.getOrDefault(false)

        fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"

        return buildString {
            append("🧪 <b>Проверка готовности</b>\n")
            append("${mark(resolution != null)} камера: ${resolution ?: "не привязана"}\n")
            append(
                "${mark(verification?.isUsable == true)} линза $lensLabel: " +
                    "${verification?.state ?: "?"} phys ${verification?.observedPhysicalId ?: "?"}\n",
            )
            append("${mark(AlprEngine.status.isReady)} движок: ${engineHealth()}\n")
            append("${mark(detector != null)} детектор: ${config.model.asset}\n")
            append(
                when {
                    !config.voiceAlerts -> "⚠️ голос: выключен в настройках\n"
                    !ttsReady -> "⚠️ голос: движок ещё не готов\n"
                    voiceState != null -> "⚠️ голос: $voiceState\n"
                    else -> "✅ голос: русский, тревоги прозвучат вслух\n"
                },
            )
            append(
                if (fix == null) {
                    "⚠️ GPS: нет фикса — повороты считаться не будут\n"
                } else {
                    "✅ GPS: точность ±${fix.accuracyM.toInt()} м, поворотов ${tracker?.turnCount ?: 0}\n"
                },
            )
            if (airplaneMode) append("✈️ режим самолёта: слежка на паузе, только чёрный список и полиция\n")
            append(
                "${mark(videoRecorder?.videoCapture != null)} видео: " +
                    if (videoRecorder?.videoCapture != null) "готово" else "камера отказала\n",
            )
            if (videoRecorder?.videoCapture != null) append("\n")
            append("${mark(free > 500)} место: $free МБ\n")
            append(
                when (thermalLevel) {
                    0 -> "✅ нагрев: норма\n"
                    1 -> "⚠️ нагрев: греется (работаю на полную)\n"
                    else -> "🔥 нагрев: горячий (работаю на полную)\n"
                },
            )
            if (cropStats.totalSent > 0) {
                append("📐 вырез по номеру: ${runtime.narrowCropsName()}\n")
                cropStats.summary().lines().forEach { line -> append("   $line\n") }
            }
            val carried = spillStore.count
            if (carried > 0) append("ℹ️ отложено вырезов: $carried — дочитаю на холостом ходу\n")
            val undelivered = runCatching {
                File(filesDir, "clips").listFiles { file -> file.name.startsWith("tail_") }?.size ?: 0
            }.getOrDefault(0)
            if (undelivered > 0) append("⚠️ не отправлено клипов: $undelivered\n")
            append("${mark(battery)} батарея: ${if (battery) "без ограничений" else "оптимизация включена"}\n")
            val overlay = runCatching { Settings.canDrawOverlays(this@ScanActivity) }.getOrDefault(false)
            append(
                "${mark(overlay)} самоперезапуск: " +
                    if (overlay) "разрешён (поверх других приложений)\n" else "нет разрешения «поверх других приложений» — перезапуститься сам не смогу\n",
            )
            bot?.problemText()?.let { problem -> append("⚠️ Telegram: $problem\n") }
            // Every reason the gate may be shut, not only the button: a stopped session and a
            // parked sleep used to be reported as "идёт" because the button had not been pressed.
            val scanning = when {
                !sessionRunning -> "остановлено (/go чтобы запустить)"
                userPaused -> "на паузе"
                parkedPause -> "спит на стоянке"
                else -> "идёт"
            }
            append("${mark(scanning == "идёт")} сканирование: $scanning")
        }
    }

    /**
     * Reports heat. Does not act on it.
     *
     * On the rear window in the sun this phone gets hot, and the app used to answer by sampling
     * less often, cutting fewer crops and stopping the recording. That is a trade the operator has
     * explicitly rejected: a scanner that quietly reads half as many cars is a scanner that misses
     * the one car it exists to find, and the moment it degrades — heavy traffic, sun, a long drive
     * — is exactly the moment the evidence matters. Full frame rate, full crop budget, keep
     * filming.
     *
     * Android's own governor still throttles the CPU underneath, and at
     * [android.os.PowerManager.THERMAL_STATUS_CRITICAL] and above it may take the camera away
     * outright. Nothing here can prevent that; what it can do is say so, so an empty stretch of
     * report has an explanation. The frame watchdog handles the camera actually going.
     */
    private fun startThermalWatch() {
        val manager = getSystemService(android.os.PowerManager::class.java) ?: return
        applyThermal(manager.currentThermalStatus)
        thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
            mainHandler.post { applyThermal(status) }
        }
        runCatching {
            manager.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalListener!!)
        }
    }

    private fun applyThermal(status: Int) {
        val level = when {
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 2
            status >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        if (level == thermalLevel) return
        val previous = thermalLevel
        thermalLevel = level
        Log.i(TAG, "thermal status $status (performance unchanged)")
        when {
            level >= 2 -> bot?.broadcast(
                "🔥 Телефон сильно греется. Частоту и запись НЕ снижаю — работаю на полную. " +
                    "Если система заберёт камеру, придёт отдельное сообщение.",
            )

            level == 1 && previous == 0 -> bot?.broadcast("🌡 Телефон греется, работаю на полную")

            level == 0 && previous > 0 -> bot?.broadcast("❄️ Остыл")
        }
    }

    /**
     * Says out loud that the session is living on borrowed time.
     *
     * The exemption dialog is easy to dismiss and the consequence — Samsung killing the service
     * halfway through a drive — arrives an hour later with no explanation. Better to be told at the
     * start, on the phone in your pocket, than to find an empty report at the end.
     */
    private fun warnIfBatteryRestricted() {
        val manager = getSystemService(android.os.PowerManager::class.java) ?: return
        if (manager.isIgnoringBatteryOptimizations(packageName)) return
        mainHandler.postDelayed(
            {
                bot?.broadcast(
                    "⚠️ Оптимизация батареи включена — система может выключить сканирование " +
                        "посреди поездки. Настройки → Батарея → без ограничений для LensALPR.",
                )
            },
            BATTERY_WARNING_DELAY_MS,
        )
    }

    /** Samsung stops background work aggressively; without this the service may not last an hour. */
    private fun requestBatteryExemption() {
        val manager = getSystemService(android.os.PowerManager::class.java) ?: return
        if (manager.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    // -------------------------------------------------------------- telegram

    /**
     * The bot answers whenever a token exists, even with the Telegram switch off — that switch mutes
     * alerts, it is not a reason to ignore the owner's commands from the road.
     */
    private fun startBot() {
        if (config.telegramToken.isBlank()) return
        val active = TelegramBot(
            store = store,
            host = botHost,
            outboxFile = File(filesDir, "telegram_outbox.json"),
            settings = {
                BotSettings(
                    token = config.telegramToken,
                    ownerId = config.telegramOwnerId,
                    enabled = config.telegramEnabled,
                    alertMinLevel = runtime.alertMinLevel,
                    alertAfterEncounters = runtime.alertAfterEncounters,
                    narrowCrops = runtime.narrowCrops,
                )
            },
        )
        bot = active
        active.start()
    }

    private val botHost = object : BotHost {
        override fun statusText(): String {
            val trip = tracker
            val verified = if (statusVerified) "подтверждена" else "не подтверждена"
            return buildString {
                append("🛰 <b>LensALPR</b>\n")
                append("линза ${lensLabel} · $verified\n")
                append(
                    String.format(
                        Locale.US,
                        "%.1f fps · det %d мс · очередь %d\n",
                        statusFps,
                        statusDetMs,
                        statusQueue,
                    ),
                )
                append("в кадре: $statusTracked · подтверждено: ${adapter.itemCount}\n")
                if (userPaused) append("⏸ на паузе\n")
                if (airplaneMode) append("✈️ режим самолёта: слежка на паузе, только чёрный список и полиция\n")
                val spilled = spillStore.count
                if (spilled > 0) append("отложено на диск: $spilled\n")
                if (trip != null) {
                    append(
                        String.format(
                            Locale.US,
                            "поездка: %.1f км · поворотов %d\n",
                            trip.odometerM / 1000.0,
                            trip.turnCount,
                        ),
                    )
                }
                append("движок: ${engineHealth()}\n")
                append("вырез→номер: ${cropStats.compact()}")
            }
        }

        override fun buildVehicleReport(plate: String): File? = try {
            val key = com.lensalpr.app.alpr.PlateText.normalize(plate) ?: plate
            reportBuilder?.build(HtmlReportBuilder.Options(sinceMs = 0L, plate = key, cropStats = cropReport()))
        } catch (error: Throwable) {
            Log.e(TAG, "vehicle report failed", error)
            null
        }

        override fun buildReport(sinceMs: Long): File? = try {
            reportBuilder?.build(HtmlReportBuilder.Options(sinceMs = sinceMs, cropStats = cropReport()))
        } catch (error: Throwable) {
            Log.e(TAG, "report build failed", error)
            null
        }

        override fun listVehicles(minLevel: Int): String {
            val rows = runCatching { store.vehicles(minLevel) }.getOrDefault(emptyList())
            if (rows.isEmpty()) return "Пока пусто."
            return buildString {
                append("🚗 <b>Машины</b>\n")
                rows.take(40).forEach { row ->
                    val mark = when {
                        row.blacklisted -> "⛔️"
                        row.police -> "🚔"
                        row.level >= ThreatLevel.TAIL.rank -> "🔴"
                        row.level >= ThreatLevel.SUSPECT.rank -> "🟠"
                        row.level >= ThreatLevel.WATCH.rank -> "🟡"
                        else -> "⚪️"
                    }
                    val facts = listOfNotNull(
                        row.makeModel,
                        "${row.encounters} встреч",
                        row.sharedTurns.takeIf { it > 0 }?.let { "$it поворотов" },
                    ).joinToString(" · ")
                    append("$mark <code>${row.displayPlate}</code> — $facts\n")
                }
            }
        }

        /**
         * Everything known about one plate, plus the pictures that actually exist.
         *
         * The card used to claim "5 encounters" and then hand over three photos without a word of
         * explanation. Both numbers were true and neither could be checked: the counter is every
         * encounter ever recorded, while a photo exists only where a thumbnail was available at the
         * time and the file survived. Now the card lists the encounters it is showing, marks the
         * ones without a picture, and says how many are older than the listed window — so nothing
         * looks like a lost photo when it is really an old meeting or a missing thumbnail.
         */
        override fun vehicleCard(plate: String): Pair<String, List<File>>? {
            val key = com.lensalpr.app.alpr.PlateText.normalize(plate) ?: return null
            val row = runCatching { store.vehicle(key) }.getOrNull() ?: return null
            val encounters = runCatching { store.encounters(key) }.getOrDefault(emptyList())
            // Newest last, and the caller only sends the tail of this list, so the freshest
            // pictures are the ones that survive its own cap.
            val photos = encounters.mapNotNull { it.photo?.let(::File) }.filter { it.exists() }
            val hidden = (row.encounters - encounters.size).coerceAtLeast(0)
            val text = buildString {
                append("<code>${row.displayPlate}</code>\n")
                listOfNotNull(row.makeModel, row.color, row.country)
                    .takeIf { it.isNotEmpty() }
                    ?.let { append(it.joinToString(" · ")).append("\n") }
                append("встреч: ${row.encounters} · с фото: ${photos.size} · кадров: ${row.sightings}\n")
                append("поворотов: ${row.sharedTurns} · возвратов: ${row.reacquisitions}\n")
                append("поездок: ${row.tripsSeen} · уровень: ${row.level}\n")
                if (row.blacklisted) append("⛔️ в чёрном списке\n")
                if (row.police) append("🚔 полиция\n")
                if (row.ignored) append("🙈 помечена как своя\n")
                if (hidden > 0) append("(ещё $hidden встреч(и) старше показанных)\n")
                encounters.forEach { encounter ->
                    val hasPhoto = encounter.photo?.let { File(it).exists() } == true
                    append(if (hasPhoto) "📷 " else "▫️ ")
                    append(DATE_TIME.format(Date(encounter.startedAt)))
                    append(" · ${encounter.lens ?: "?"}")
                    append(" · кадров ${encounter.sightings}")
                    if (!hasPhoto) append(" · без фото")
                    append('\n')
                }
            }
            return text to photos
        }

        /**
         * Marks a plate as police, whether or not the camera has ever seen it.
         *
         * Typed from the road far more often than tapped: the driver spots a patrol car, reads the
         * plate off it and wants the phone to shout the next time it turns up. So an unknown plate
         * is accepted and says so, exactly like the blacklist.
         */
        override fun setPolice(plate: String, police: Boolean): String {
            val parsed = com.lensalpr.app.alpr.PlateFormats.parse(plate)
                ?: return "Не похоже на номер: ${TelegramClient.escape(plate)}"
            val known = runCatching { store.isKnown(parsed.key) }.getOrDefault(true)
            // The engine writes the mark under the row the car actually lives in; a second write
            // under the typed spelling created a twin row whenever the two differed.
            val engine = follow
            if (engine != null) {
                mainHandler.post { engine.setPolice(parsed.key, police) }
            } else {
                ioExecutor.execute { runCatching { store.setPolice(parsed.key, police, parsed.display) } }
            }
            val note = if (known) "" else "\n(машина ещё ни разу не встречалась — сработает при первой встрече)"
            return if (police) {
                "🚔 <code>${parsed.display}</code> помечена как полиция$note"
            } else {
                "✅ <code>${parsed.display}</code> больше не полиция"
            }
        }

        override fun policeText(): String {
            val rows = runCatching { store.policeVehicles() }.getOrDefault(emptyList())
            if (rows.isEmpty()) return "🚔 Список полиции пуст.\nДобавить: <code>/police AB1234</code>"
            return buildString {
                append("🚔 <b>Полиция</b>\n")
                rows.forEach { row ->
                    append("<code>${row.displayPlate}</code>")
                    row.makeModel?.let { append(" — $it") }
                    row.color?.let { append(", $it") }
                    if (row.encounters > 0) append(" · встреч ${row.encounters}")
                    append('\n')
                }
                append("\nУбрать: <code>/unpolice AB1234</code>")
            }
        }

        override fun setBlacklist(plate: String, blacklisted: Boolean): String {
            val parsed = com.lensalpr.app.alpr.PlateFormats.parse(plate)
                ?: return "Не похоже на номер: ${TelegramClient.escape(plate)}"
            // Read before the write is queued: the answer has to say whether this is a car the
            // camera actually knows, and the write itself happens on the io thread — by the
            // engine, under the row the car actually lives in.
            val known = runCatching { store.isKnown(parsed.key) }.getOrDefault(true)
            val engine = follow
            if (engine != null) {
                mainHandler.post { engine.setBlacklisted(parsed.key, blacklisted) }
            } else {
                ioExecutor.execute { runCatching { store.setBlacklisted(parsed.key, blacklisted, parsed.display) } }
            }
            val note = if (known) "" else "\n(машина ещё ни разу не встречалась — сработает при первой встрече)"
            return if (blacklisted) {
                "⛔️ <code>${parsed.display}</code> в чёрном списке$note"
            } else {
                "✅ <code>${parsed.display}</code> убран из чёрного списка"
            }
        }

        override fun blacklistText(): String {
            val rows = runCatching { store.blacklistedVehicles() }.getOrDefault(emptyList())
            if (rows.isEmpty()) return "Чёрный список пуст"
            return buildString {
                append("⛔️ <b>Чёрный список</b>\n")
                rows.forEach { row ->
                    append("<code>${row.displayPlate}</code>")
                    row.makeModel?.let { append(" · $it") }
                    append(" · встреч ${row.encounters}")
                    // A car listed by hand has no sighting behind it, and printing 01.01.1970 as
                    // its last meeting would be worse than saying so.
                    if (row.lastSeen > 0L) {
                        append(" · ${DATE_TIME.format(Date(row.lastSeen))}")
                    } else {
                        append(" · ещё не встречалась")
                    }
                    append('\n')
                }
            }
        }

        /**
         * Default report window: the whole trip, and never less than the last hours - a report
         * pulled two minutes after starting the engine must still show what happened on the way.
         */
        /** Blocks briefly while the analysis thread hands over the next frame. */
        override fun snapshot(): File? {
            val active = processor ?: return null
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOfNulls<File>(1)
            active.requestFrame { bitmap ->
                runCatching {
                    val target = File(cacheDir, "frame.jpg")
                    val width = minOf(bitmap.width, SNAPSHOT_WIDTH)
                    val scaled = if (width < bitmap.width) {
                        android.graphics.Bitmap.createScaledBitmap(
                            bitmap,
                            width,
                            bitmap.height * width / bitmap.width,
                            true,
                        )
                    } else {
                        bitmap
                    }
                    java.io.FileOutputStream(target).use { output ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
                    }
                    if (scaled !== bitmap) scaled.recycle()
                    holder[0] = target
                }
                bitmap.recycle()
                latch.countDown()
            }
            latch.await(4, TimeUnit.SECONDS)
            return holder[0]
        }

        override fun setPaused(paused: Boolean): String = onMainSync(
            if (paused) "⏸ Распознавание на паузе" else "▶️ Распознавание продолжено",
        ) {
            // The same operation the on-screen button performs, parked sleep included: a resume
            // from the chat used to clear the operator's pause and leave the parking pause on, so
            // "продолжено" was answered while nothing was being recognised.
            setUserPaused(paused)
            if (paused) "⏸ Распознавание на паузе" else "▶️ Распознавание продолжено"
        }

        override fun isPaused(): Boolean = userPaused

        override fun isSessionRunning(): Boolean = sessionRunning

        override fun setSessionRunning(running: Boolean): String = onMainSync(
            if (running) "🟢 Запускаю сессию" else "🛑 Останавливаю сессию, камера освобождена",
        ) {
            // Decided on the thread that owns the flag, or two commands in quick succession
            // judged each other's outcome and the second one was dropped as "already done".
            if (destroyed) return@onMainSync "Сканер закрыт"
            if (running == sessionRunning) {
                if (running) "Уже работает" else "Уже остановлено"
            } else if (running) {
                if (resumeSession()) "🟢 Запускаю сессию" else "⚠️ Не могу запустить: детектор ещё не готов, попробуй через несколько секунд"
            } else {
                haltSession()
                "🛑 Останавливаю сессию, камера освобождена"
            }
        }

        override fun nextLens(): String {
            mainHandler.post { scheduler?.advance() }
            return "🔀 Переключаю линзу"
        }

        override fun toggleRecording(): String = onMainSync("🎥 Команда записи отправлена") {
            // Everything else that starts or stops the recorder runs on main; a /rec arriving from
            // Telegram at the same moment a tail is confirmed must not race it — and the answer is
            // the recorder's own, not a guess made before it was asked.
            if (destroyed) "Сканер закрыт" else toggleRecordingOnMain()
        }

        override fun findPlates(query: String): String {
            val trimmed = query.trim()
            if (trimmed.length < 2) return "Что искать? Например: /find 7209"
            val rows = runCatching { store.searchPlates(trimmed) }.getOrDefault(emptyList())
            val shown = TelegramClient.escape(trimmed)
            if (rows.isEmpty()) return "Ничего не нашёл по «$shown»"
            return buildString {
                append("🔎 <b>$shown</b>\n")
                rows.forEach { row ->
                    append("<code>${row.displayPlate}</code> — встреч ${row.encounters}")
                    row.makeModel?.let { append(" · $it") }
                    append(" · ${DATE_TIME.format(Date(row.lastSeen))}\n")
                }
            }
        }

        override fun preflightText(): String = preflightReport()

        override fun settingsText(): String = runtime.summary()

        /**
         * [key] prefixed with "=" sets an absolute value, otherwise the number is a step - the
         * buttons send steps, the /set command sends values.
         */
        override fun applySetting(key: String, delta: Int): String {
            val absolute = key.startsWith("=")
            val name = key.removePrefix("=").lowercase()
            return when (name) {
                "tail" -> {
                    val value = if (absolute) delta else runtime.tailSeconds + delta
                    "⏱ Время хвоста: ${runtime.setTailSeconds(value)} с"
                }

                "absent" -> {
                    val value = if (absolute) delta else runtime.videoAbsentSeconds + delta
                    "🎥 Стоп записи через ${runtime.setVideoAbsentSeconds(value)} с"
                }

                "park" -> {
                    val enabled = if (absolute) delta > 0 else !runtime.autoPauseParked
                    "😴 Сон на стоянке: " +
                        if (runtime.setAutoPauseParked(enabled)) "включён" else "выключен"
                }

                "video" -> {
                    val enabled = if (absolute) delta > 0 else !runtime.videoEnabled
                    "🎥 Видео: " + if (runtime.setVideoEnabled(enabled)) "включено" else "выключено"
                }

                "narrow" -> {
                    val value = if (absolute) delta else runtime.narrowCrops + delta
                    val mode = runtime.setNarrowCrops(value)
                    cropStats.reset()
                    mainHandler.post { applyCropStrategy() }
                    "📐 Вырез по номеру: ${runtime.narrowCropsName()} (счётчики сброшены)" +
                        if (mode == RuntimeSettings.NARROW_EXPERIMENT) {
                            "\nСравниваю обе стратегии, меняю каждую минуту — смотри /check"
                        } else {
                            ""
                        }
                }

                "level" -> {
                    val value = if (absolute) delta else runtime.alertMinLevel + delta
                    "🔔 Уровень тревоги: ${runtime.setAlertMinLevel(value)}"
                }

                "meetings" -> {
                    val value = if (absolute) delta else runtime.alertAfterEncounters + delta
                    "🤝 Тревога после ${runtime.setAlertAfterEncounters(value)} встреч"
                }

                else -> "Не знаю такой настройки: $name"
            }
        }

        override fun currentTripStartMs(): Long {
            val recent = System.currentTimeMillis() - DEFAULT_REPORT_WINDOW_MS
            val trip = tripStartMs.takeIf { it > 0L } ?: recent
            return minOf(trip, recent)
        }

        override fun companionsText(): String {
            val escorts = companions
            if (escorts.isEmpty()) {
                // "Clean" is only an answer while somebody is actually looking. A shut gate, a
                // dead camera or a blind engine is not a quiet road.
                val blind = blindStrikes > 0 || stallStrikes > 0 || !AlprEngine.status.isReady
                return when {
                    userPaused || parkedPause || !sessionRunning -> "Сканирование не идёт — сзади не смотрю"
                    blind -> "⚠️ Распознавание сейчас не работает — сзади не смотрю (см. /check)"
                    else -> "✅ Рядом чисто — никто не держится"
                }
            }
            return buildString {
                append("👁 <b>Сейчас за нами</b>\n")
                escorts.take(8).forEach { evidence ->
                    val minutes = evidence.contactMs / 60_000
                    val duration = if (minutes >= 1) "$minutes мин" else "${evidence.contactMs / 1000} с"
                    append("<code>${evidence.displayPlate}</code> · $duration")
                    if (evidence.level != ThreatLevel.IGNORE) append(" · ${evidence.level.title()}")
                    evidence.makeModel?.let { append(" · $it") }
                    append('\n')
                    if (evidence.reasons.isNotEmpty()) {
                        append("   ").append(evidence.reasons.take(3).joinToString(" · ")).append('\n')
                    }
                }
            }
        }

        override fun setPassword(password: String): String {
            val pin = password.trim()
            if (!LockCrypto.isValid(pin)) {
                return "Пароль — ровно ${LockCrypto.PASSWORD_LENGTH} цифр: /setpassword 123456789012"
            }
            LockStore.setPassword(applicationContext, pin)
            return "🔐 Пароль входа заменён. Попыток до удаления данных: ${LockPolicy.MAX_ATTEMPTS}"
        }

        override fun setIgnored(plate: String, ignored: Boolean): String {
            val parsed = com.lensalpr.app.alpr.PlateFormats.parse(plate)
                ?: return "Не похоже на номер: ${TelegramClient.escape(plate)}"
            mainHandler.post {
                follow?.setIgnored(parsed.key, ignored)
                // A dismissed car must also stop filming right now, not after the next timeout —
                // even when the clip was started under the spelling the engine has since
                // corrected, which is why the recorder's key is resolved through the engine.
                val target = videoRecorder?.currentTarget
                if (ignored && target != null && !manualRecording &&
                    (target == parsed.key || follow?.sameVehicle(target, parsed.key) == true)
                ) {
                    stopRecording()
                }
            }
            // The engine persists the flag under the car's real row; only without an engine does
            // the typed spelling go straight to the database.
            if (follow == null) {
                ioExecutor.execute { runCatching { store.setIgnored(parsed.key, ignored, parsed.display) } }
            }
            return if (ignored) {
                "🙈 <code>${parsed.display}</code> — свой, больше не тревожу"
            } else {
                "🔔 <code>${parsed.display}</code> снова под наблюдением"
            }
        }

        override fun renamePlate(from: String, to: String): String {
            val source = com.lensalpr.app.alpr.PlateFormats.parse(from)
                ?: return "Не похоже на номер: ${TelegramClient.escape(from)}"
            val target = com.lensalpr.app.alpr.PlateFormats.parse(to)
                ?: return "Не похоже на номер: ${TelegramClient.escape(to)}"
            if (source.key == target.key) return "Это один и тот же номер"
            val renamed = runCatching {
                store.renamePlateManually(source.key, target.key, target.display)
            }.getOrDefault(false)
            mainHandler.post {
                follow?.rename(source.key, target.key, target.display)
                registry.rename(source.key, target.key, target.display)
                evidenceByPlate.remove(source.key)
                // The card must not lose its level and its marks for the seconds until the next
                // sighting; the engine already knows the car under its new name.
                follow?.evidenceFor(target.key)?.let { evidenceByPlate[target.key] = it }
                // A clip in progress about the old spelling follows the car, or the absence timer
                // would find no such car and stop it a few seconds later — and the segment
                // continuation would restart it under a name nothing recognises.
                if (videoRecorder?.currentTarget == source.key) videoRecorder?.retarget(target.key)
                if (segmentPlate == source.key) segmentPlate = target.key
                publishVehicles()
            }
            return if (renamed) {
                "✏️ <code>${source.display}</code> → <code>${target.display}</code>"
            } else {
                "В базе нет <code>${source.display}</code>, но карточку поправил"
            }
        }

        override fun ignoredText(): String {
            val rows = runCatching { store.ignoredVehicles() }.getOrDefault(emptyList())
            if (rows.isEmpty()) return "Список «своих» пуст"
            return buildString {
                append("🙈 <b>Свои машины</b>\n")
                rows.take(30).forEach { row ->
                    append("<code>${row.displayPlate}</code>")
                    row.makeModel?.let { append("  $it") }
                    append("  ×${row.sightings}\n")
                }
                append("\nВернуть: <code>/unignore НОМЕР</code>")
            }
        }

        override fun storageText(): String {
            val (files, bytes) = StorageCleaner.usage(applicationContext)
            val free = runCatching { cacheDir.usableSpace / (1024L * 1024L) }.getOrDefault(0L)
            return buildString {
                append("💾 <b>Память</b>\n")
                append("машин: ${store.countRows("vehicles")}\n")
                append("встреч: ${store.countRows("encounters")}\n")
                append("наблюдений: ${store.countRows("sightings")}\n")
                append("поездок: ${store.countRows("trips")} · точек трека: ${store.countRows("track")}\n")
                append("файлов: $files · ${WipeStats.format(bytes)}\n")
                append("база: ${WipeStats.format(store.databaseFile().length())}\n")
                append("свободно на телефоне: $free МБ")
            }
        }

        /**
         * A wipe is a sequence, not a call: the producers are stopped first, on the thread that
         * owns them, then the files and rows go on the io lane — behind every write already
         * queued there, so nothing lands in the database after it was emptied — and only then is
         * the new trip bound and the engine reset. Running it from the bot's thread in the middle
         * of everything used to leave a recording writing into a deleted file, sightings filed
         * against a deleted trip, and the trip odometer still counting the old drive.
         */
        override fun wipeAll(): String {
            if (destroyed) return "Сканер закрыт"
            val stopped = CountDownLatch(1)
            mainHandler.post {
                // Nothing may be mid-write into a file that is about to go.
                stopRecording()
                registry.clear()
                evidenceByPlate.clear()
                adapter.submitList(emptyList())
                stopped.countDown()
            }
            stopped.await(MAIN_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val result = arrayOfNulls<String>(1)
            val done = CountDownLatch(1)
            ioExecutor.execute {
                try {
                    val stats = runCatching { StorageCleaner.wipe(applicationContext, store) }
                        .getOrElse { error ->
                            Log.e(TAG, "wipe failed", error)
                            result[0] = "Не смог очистить: ${error.message ?: error.javaClass.simpleName}"
                            return@execute
                        }
                    spillStore.reset()
                    // The old trip row is gone; keep writing into a fresh one instead of a dangling id.
                    val now = System.currentTimeMillis()
                    val trip = runCatching { store.startTrip(now) }.getOrDefault(0L)
                    cropStats.reset()
                    mainHandler.post {
                        tripStartMs = now
                        tripId = trip
                        // Without this the engine keeps judging cars by evidence that no longer
                        // exists, and every new sighting is filed against a trip row that was just
                        // deleted — and the new trip would start with the old one's kilometres.
                        follow?.bindTrip(trip)
                        follow?.reset()
                        tracker?.resetTrip()
                        updatePanelTitle()
                    }
                    Log.i(TAG, "wiped: ${stats.describe()}")
                    result[0] = "🧹 <b>Очищено</b>\n${stats.describe()}\nНачата новая поездка."
                } finally {
                    done.countDown()
                }
            }
            done.await(WIPE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return result[0] ?: "🧹 Очистка запущена, займёт немного времени"
        }

        override fun isIgnored(plate: String): Boolean =
            evidenceByPlate[plate]?.ignored == true ||
                evidenceByPlate.values.any { it.storeKey == plate && it.ignored }
    }

    // ---------------------------------------------------------------- recognition

    /** Called on the worker thread; consensus and UI state stay on main. */
    /**
     * Feeds the plate position back to the frame path, so the next crop of this car can be cut
     * around the plate instead of the whole vehicle.
     *
     * A narrow crop that came back with nothing means the remembered position has drifted off the
     * plate; forgetting it costs one frame and puts the whole car back in the picture.
     */
    private fun rememberPlatePosition(job: OcrJob, outcome: AlprOutcome, nowMs: Long) {
        if (job.anchorRect.width() <= 0 || job.anchorRect.height() <= 0) return
        val runtime = recognition.peek(job.trackId) ?: return
        // The same reading the card is built from, so the remembered position can never belong to a
        // different plate than the one that was recorded.
        val box = VehicleRegistry.selectReading(outcome, config.minOcrScore.toFloat(), job)?.box
        if (box == null) {
            if (job.narrow) runtime.plateAnchor = null
            return
        }
        val anchor = PlateRoi.anchorOf(
            plate = floatArrayOf(box.left, box.top, box.right, box.bottom),
            bitmapWidth = job.width,
            bitmapHeight = job.height,
            source = intArrayOf(
                job.sourceRect.left,
                job.sourceRect.top,
                job.sourceRect.right,
                job.sourceRect.bottom,
            ),
            basis = intArrayOf(
                job.anchorRect.left,
                job.anchorRect.top,
                job.anchorRect.right,
                job.anchorRect.bottom,
            ),
        )
        if (anchor != null) {
            runtime.plateAnchor = anchor
            // The clock the analysis thread ages the anchor against; the wall-clock `nowMs` of
            // the history is a different clock, and the difference made the anchor immortal.
            runtime.anchorAtMs = SystemClock.elapsedRealtime()
        } else if (job.narrow) {
            runtime.plateAnchor = null
        }
    }

    private fun onRecognition(job: OcrJob, outcome: AlprOutcome) {
        mainHandler.post {
            if (isFinishing || isDestroyed) {
                if (!job.thumbnail.isRecycled) job.thumbnail.recycle()
                return@post
            }
            val now = System.currentTimeMillis()
            // Crops recovered from disk are old and were queued under overload; counting them would
            // bias whichever strategy happened to be running when the queue overflowed.
            if (job.anchorRect.width() > 0) {
                cropStats.record(job.narrow, outcome.plates.isNotEmpty(), outcome.latencyMs)
            }
            rememberPlatePosition(job, outcome, now)
            val result = registry.submit(job, outcome, now)
            val card = result.card
            if (result.change == VehicleRegistry.Change.CONFIRMED ||
                result.change == VehicleRegistry.Change.UPDATED
            ) {
                // A crop recovered from disk is a real plate but a false observation: it arrives
                // minutes late and, because the spill store drains newest first, out of order. Let
                // it count towards the plate and keep it out of the follow evidence, where a stale
                // timestamp and a stale odometer would stretch contact across the gap and hand the
                // car every turn taken in between.
                val fresh = now - job.submittedAtMs <= SIGHTING_FRESHNESS_MS
                // The score of *this* read, for the photo taken from this frame; the card's best
                // score belongs to some earlier, luckier frame.
                val readScore = if (result.score > 0f) result.score else card?.ocrScore ?: 0f
                if (card != null && fresh) {
                    follow?.onSighting(
                        capturedAtMs = job.submittedAtMs,
                        lat = job.lat,
                        lon = job.lon,
                        odometerM = job.odometerM,
                        plate = card.plate,
                        displayPlate = card.displayPlate,
                        makeModel = card.makeModel,
                        make = card.make,
                        model = card.model,
                        year = card.year,
                        color = card.color,
                        body = card.bodyStyle,
                        country = card.country,
                        ocrScore = readScore,
                        lens = job.lensLabel,
                        nowMs = now,
                        // The latest look at the car, not the best-scoring one. The card on screen
                        // wants the clearest plate; an encounter photo has to be a picture of that
                        // encounter, or it is not evidence of anything.
                        thumbnail = card.latestThumbnail ?: card.thumbnail,
                    )
                } else if (card != null) {
                    // Too old to say anything about who is behind us now, but a real observation
                    // of a real plate at a real time and place: it goes into the history, where a
                    // crop that waited on disk used to vanish without a trace.
                    follow?.recordLateSighting(
                        plate = card.plate,
                        displayPlate = card.displayPlate,
                        make = card.make,
                        model = card.model,
                        year = card.year,
                        color = card.color,
                        body = card.bodyStyle,
                        country = card.country,
                        ocrScore = readScore,
                        lens = job.lensLabel,
                        capturedAtMs = job.submittedAtMs,
                        lat = job.lat,
                        lon = job.lon,
                        odometerM = job.odometerM,
                        thumbnail = card.latestThumbnail ?: card.thumbnail,
                    )
                }
                publishVehicles()
                if (result.change == VehicleRegistry.Change.CONFIRMED) {
                    binding.vehicleList.scrollToPosition(0)
                }
            }
            if (!outcome.ok && outcome.code > 0) {
                Log.w(TAG, "engine error ${outcome.code}: ${outcome.phrase}")
            }
        }
    }

    private fun onEngineStatus(status: AlprEngine.Status) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            when {
                status.runtimeLimited -> onEngineRuntimeLimit()

                status.state == AlprEngine.State.READY -> {
                    worker.start()
                    hideBanner()
                    onEngineRecovered()
                }

                status.state == AlprEngine.State.ERROR -> showBanner(
                    getString(R.string.engine_error, status.message ?: status.code.toString()),
                )

                else -> showBanner(getString(R.string.engine_initializing))
            }
        }
    }

    /**
     * The recognition engine has spent its runtime entitlement and refuses every frame.
     *
     * This used to be a dead end: the worker was stopped, a banner appeared on a screen that is by
     * design blacked out and pointing backwards, and nothing ever started recognition again. The
     * engine is now rebuilt in place — the entitlement is spent per native session, so a fresh
     * session is the repair — and the driver is told over Telegram, which is the only channel that
     * reaches somebody holding a steering wheel.
     */
    private fun onEngineRuntimeLimit() {
        worker.stop()
        if (engineDownSinceMs == 0L) {
            engineDownSinceMs = SystemClock.elapsedRealtime()
            // Once the driver has been told the engine cannot be revived, repeating it every time
            // a periodic knock fails is noise they can do nothing about. The fatal banner stays on
            // screen and `/status` still tells the truth.
            val announce = !engineGaveUp
            // Only a restart that actually bought recognition time counts as having worked. If the
            // limit comes back within seconds, the allowance is being counted per process and no
            // number of rebuilds will change that — so the attempt counter must survive the brief
            // READY in between, or the app would rebuild the engine forever and cook the phone.
            val healthyFor = if (engineReadySinceMs != 0L) engineBusyMs else 0L
            if (healthyFor >= ENGINE_HEALTHY_MS) {
                engineRestarts = 0
                engineGaveUp = false
            } else if (engineRestarts > 0) {
                Log.w(TAG, "engine survived only ${healthyFor / 1000}s; limit looks process-wide")
            }
            engineReadySinceMs = 0L
            if (announce) {
                bot?.broadcast(
                    "🛑 Движок ALPR упёрся в лимит рантайма (${AlprEngine.CODE_RUNTIME_LIMIT}). " +
                        "Номера сейчас НЕ читаются и хвост не определяется. Поднимаю движок…",
                    urgent = true,
                )
                speak("Внимание. Распознавание остановлено. Перезапускаю.")
            } else {
                Log.w(TAG, "runtime limit again after a last-ditch retry; staying quiet")
            }
        }
        if (engineRestarts >= ENGINE_RESTART_LIMIT) {
            if (!engineGaveUp) {
                engineGaveUp = true
                showBanner(getString(R.string.engine_runtime_limited_fatal))
                bot?.broadcast(
                    "⛔️ Движок ALPR не поднялся после $ENGINE_RESTART_LIMIT попыток — " +
                        "лимит держится на весь процесс. Перезапускаю приложение целиком, " +
                        "поездка продолжится в том же треке.",
                    urgent = true,
                )
                // The engine reload did not help, so the entitlement is counted per process and the
                // only thing left that can clear it is a new process. Everything that would be lost
                // is written down first; see restartProcess. Held in a field so closing the app
                // during the grace period cancels it — otherwise the app would kill the process the
                // driver just dismissed and bring itself back from the dead.
                val kill = Runnable {
                    processRestartPending = null
                    restartProcess("лимит ALPR")
                }
                processRestartPending = kill
                mainHandler.postDelayed(kill, PROCESS_RESTART_DELAY_MS)
            }
            return
        }
        // Back off: if the very first frame after a restart hits the limit again the counter is
        // process-wide, and hammering init in a loop only heats the phone.
        val attempt = engineRestarts
        engineRestarts += 1
        val delay = ENGINE_RESTART_BACKOFF_MS.getOrElse(attempt) { ENGINE_RESTART_BACKOFF_MS.last() }
        showBanner(getString(R.string.engine_runtime_limited, engineRestarts, ENGINE_RESTART_LIMIT))
        engineRestartPending?.let(mainHandler::removeCallbacks)
        val restart = Runnable {
            engineRestartPending = null
            if (isFinishing || isDestroyed) return@Runnable
            AlprEngine.recoverFromRuntimeLimit(this, config)
        }
        engineRestartPending = restart
        mainHandler.postDelayed(restart, delay)
    }

    /**
     * Kills and relaunches the process, because some failures only a new process can clear.
     *
     * Nothing here is a formality. The clip in progress is finalized or it stays on disk without an
     * MP4 index and the next start uploads a file nobody can play; the trip is handed over or the
     * classifier starts inventing "two different trips" against every car still behind us; the
     * alarm is armed before the process dies because a dead process cannot start itself.
     */
    private fun restartProcess(reason: String, spoken: String = "лимит движка ALPR"): Boolean {
        if (isFinishing || isDestroyed) {
            Log.i(TAG, "restart cancelled: the activity is already going away")
            return false
        }
        // One restart at a time: the stuck-engine and the blind ladders can both ask inside the
        // same drain window, and each ask used to cost one of the three hourly attempts.
        if (exitPending != null) return true
        // Since Android 10 an app with no visible window may not start an activity, and the
        // relaunch alarm fires after this process has killed itself. "Display over other apps"
        // is the one exemption a sideloaded app can hold; without it the alarm is refused and
        // the scanner stays dead until somebody looks — worse than staying up blind and saying so.
        if (!runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            Log.e(TAG, "no overlay permission: the relaunch would be blocked; staying up")
            restartRefusedUntilMs = SystemClock.elapsedRealtime() + RESTART_WINDOW_MS
            showBanner(getString(R.string.engine_runtime_limited_fatal))
            bot?.broadcast(
                "⚠️ Не могу перезапуститься сам: нет разрешения «поверх других приложений» " +
                    "(экран настроек → кнопка). Остаюсь работать, распознавание сейчас не работает. " +
                    "Причина: $spoken.",
                urgent = true,
            )
            return false
        }
        val now = System.currentTimeMillis()
        val attempt = runtime.noteProcessRestart(now, RESTART_WINDOW_MS)
        if (attempt > MAX_PROCESS_RESTARTS) {
            Log.e(TAG, "refusing to restart: $attempt attempts within the hour")
            restartRefusedUntilMs = SystemClock.elapsedRealtime() + RESTART_WINDOW_MS
            showBanner(getString(R.string.engine_runtime_limited_fatal))
            // Names the fault that actually ordered the restart. Saying "the engine licence" when
            // the real problem was a lens that would not verify sent the operator hunting in the
            // wrong place — and this message is the last thing they get before the app gives up.
            bot?.broadcast(
                "⛔️ Перезапуск не помогает ($attempt раза за час) — больше не пробую. " +
                    "Причина: $spoken. Распознавание сейчас не работает.",
                urgent = true,
            )
            return false
        }
        Log.w(TAG, "restarting process ($attempt/$MAX_PROCESS_RESTARTS): $reason")

        val intent = Intent(this, ScanActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // The alarm is sent by the system with no launch privilege of its own; the creator's
        // (this app's, through the overlay permission) must be attached explicitly, or the
        // relaunch is judged as a plain background start and refused.
        val options = if (android.os.Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            android.app.ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        val pending = PendingIntent.getActivity(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        val alarm = getSystemService(AlarmManager::class.java)
        val fireAt = SystemClock.elapsedRealtime() + RESTART_ALARM_DELAY_MS
        // Exact alarms need a permission the platform may withhold, and losing the restart is far
        // worse than a late one: an inexact alarm still fires within minutes on a phone that is
        // awake and plugged into a car, which is exactly the situation here.
        var armed = runCatching {
            if (alarm != null && alarm.canScheduleExactAlarms()) {
                alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, fireAt, pending)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!armed) {
            armed = runCatching {
                alarm?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, fireAt, pending)
                alarm != null
            }.getOrDefault(false)
        }
        if (!armed) {
            // No alarm means no way back. A process that kills itself now stays dead until
            // somebody notices; staying up and blind is the lesser evil, and it is said so.
            Log.e(TAG, "no restart alarm could be armed; staying up instead of restarting")
            restartRefusedUntilMs = SystemClock.elapsedRealtime() + RESTART_WINDOW_MS
            bot?.broadcast(
                "⚠️ Не смог поставить будильник на перезапуск — приложение остаётся открытым, " +
                    "перезапусти его вручную. Причина: $spoken.",
                urgent = true,
            )
            return false
        }
        restartAlarm = pending
        // Only now, with the way back secured: a handover, a lock-bypass token and a stopped clip
        // are commitments to a restart, and were being made before it was known to be possible.
        // Ownership of the trip moves to the process that is about to start — with its
        // kilometres and turns, or the debrief describes only the part after the restart.
        val trip = tripId
        if (trip > 0L) runtime.handOverTrip(trip, tripStartMs, tracker?.odometerM ?: 0.0, tracker?.turnCount ?: 0)
        // Finish the clip properly; a truncated MP4 is not evidence, it is a corrupt file.
        stopRecording()
        // The relaunch happens with nobody at the phone; it must get past the lock screen. An
        // inexact alarm can fire minutes late, so the token outlives the nominal delay by a margin.
        LockStore.armAutoUnlock(this, RESTART_ALARM_DELAY_MS + AUTO_UNLOCK_GRACE_MS)

        // Give the recorder time to write the MP4 index and the bot time to flush its queue, then
        // go. The alarm above is what brings the app back. Held in a field so that closing the
        // app during the drain cancels both the exit and the alarm; without that the app killed
        // the process the driver had just dismissed and brought itself back from the dead.
        //
        // The bot is deliberately not stopped: `stop()` announces "LensALPR остановлен", which is
        // the opposite of what is happening, and its shutdown would race the exit anyway. The
        // service is stopped so the notification does not outlive the process that owns it.
        val startedWaiting = SystemClock.elapsedRealtime()
        val exit = object : Runnable {
            override fun run() {
                if (exitPending !== this) return
                // The clip's finalize arrives on this looper; wait for it up to a ceiling rather
                // than hoping a fixed delay was long enough for the encoder.
                val waited = SystemClock.elapsedRealtime() - startedWaiting
                if (videoRecorder?.isRecording == true && waited < RESTART_DRAIN_CEILING_MS) {
                    mainHandler.postDelayed(this, 200L)
                    return
                }
                exitPending = null
                restartAlarm = null
                runCatching { ScanSessionService.stop(this@ScanActivity) }
                finish()
                exitProcess(0)
            }
        }
        exitPending = exit
        mainHandler.postDelayed(exit, RESTART_DRAIN_MS)
        return true
    }

    /** The engine is recognizing again; close the outage and say so where it will be read. */
    private fun onEngineRecovered() {
        val now = SystemClock.elapsedRealtime()
        engineReadySinceMs = now
        engineBusyMs = 0L
        engineBusyTickMs = now
        lastRecycleMs = now
        engineErrorRetries = 0
        if (engineDownSinceMs == 0L) return
        val downSeconds = (now - engineDownSinceMs) / 1000
        engineDownSinceMs = 0L
        engineRestartPending?.let(mainHandler::removeCallbacks)
        engineRestartPending = null
        // Deliberately not clearing engineRestarts here: whether this restart really worked is
        // only known once the engine has run for a while, and that is decided in
        // [onEngineRuntimeLimit] if the limit comes back.
        //
        // And once every repair has been exhausted, the periodic knock keeps producing a READY
        // that dies on the next frame. Announcing each of those would tell the driver "читаю
        // номера снова" every five minutes about an engine that reads nothing — the exact lie this
        // app exists to prevent. Success is announced from [maybeRecycleEngine] instead, once the
        // engine has proved it by running.
        if (engineGaveUp) {
            Log.i(TAG, "engine came back after a last-ditch retry; waiting to see if it holds")
            return
        }
        bot?.broadcast("✅ Движок ALPR поднялся за $downSeconds с — читаю номера снова", urgent = true)
        speak("Распознавание восстановлено.")
    }

    /**
     * Takes the engine down and builds it again while there is nothing behind us to miss.
     *
     * The runtime limit arrives unannounced in the middle of a drive. Spending two seconds of
     * blindness on an empty road is a far better trade than losing the engine at the moment a car
     * is actually following us, so a healthy engine is recycled once it has been up long enough to
     * be near its allowance — but never while a vehicle is being tracked or filmed.
     */
    /**
     * Notices an engine that went into a crop and never came out.
     *
     * A native call cannot be interrupted, so the only cure is a new process; but the first
     * duty is to say so, because from every other signal — camera fine, gate open, bot answering
     * — a hung engine is an empty road.
     */
    private fun checkOcrStuck() {
        if (!sessionRunning) return
        val now = SystemClock.elapsedRealtime()
        val since = worker.currentJobSinceMs
        if (since == 0L || now - since < OCR_STUCK_MS) {
            ocrStuckSinceMs = 0L
            return
        }
        if (ocrStuckSinceMs == 0L) {
            // Already asked for a restart and been refused: the banner is up, the chat knows,
            // and repeating both every ninety seconds helps nobody.
            if (now < restartRefusedUntilMs) return
            ocrStuckSinceMs = now
            Log.e(TAG, "engine has been inside one crop for ${(now - since) / 1000}s")
            showBanner("🛑 Движок ALPR завис на одном кадре")
            bot?.broadcast(
                "🛑 Движок ALPR завис на одном кадре уже ${(now - since) / 1000} с. " +
                    "Номера не читаются; если не отпустит, перезапущу приложение.",
                urgent = true,
            )
            speak("Внимание. Движок распознавания завис.")
            return
        }
        if (now - ocrStuckSinceMs >= OCR_STUCK_RESTART_MS) {
            ocrStuckSinceMs = 0L
            restartProcess("движок завис на кадре", "движок распознавания завис")
        }
    }

    private fun maybeRecycleEngine() {
        if (!sessionRunning) return
        val now = SystemClock.elapsedRealtime()
        val status = AlprEngine.status
        // The initialization clock belongs to one attempt. It used to survive INITIALIZING → ERROR
        // → INITIALIZING and add the retries up into a "hung" verdict about an init that had been
        // running for twenty seconds.
        if (status.state != AlprEngine.State.INITIALIZING) engineInitSinceMs = 0L

        // Health is measured in work, not in minutes. The runtime limit is only ever discovered
        // inside a recognition call, and no crop reaches the engine while the gate is shut — so on
        // a parking stop, or a pause, or an empty road, an idle engine would sit at READY and
        // "prove" itself without reading a single plate. The clock only runs while the engine has
        // actually answered a crop recently; frames passing the gate prove nothing about it.
        val engineWorking = now - worker.lastProcessedAtMs < TRACK_COUNT_FRESH_MS
        // Accumulated, not restarted: resetting the clock on every idle gap measured the last
        // uninterrupted busy stretch, so an engine that had read plates for an hour with a red
        // light in between "survived only seconds" and the restart budget ratcheted towards a
        // process restart it had never earned.
        if (engineReadySinceMs != 0L && engineWorking && engineBusyTickMs != 0L) {
            engineBusyMs += (now - engineBusyTickMs).coerceIn(0L, WATCHDOG_INTERVAL_MS * 2)
        }
        engineBusyTickMs = now

        // A periodic knock that finally stuck. Announced here rather than the moment the engine
        // reports READY, because in this state it reports READY every five minutes and dies on the
        // next frame — only running for a while is proof.
        if (engineGaveUp && status.isReady && engineReadySinceMs != 0L &&
            engineBusyMs >= ENGINE_HEALTHY_MS
        ) {
            engineGaveUp = false
            engineRestarts = 0
            bot?.broadcast(
                "✅ Движок ALPR держится ${engineBusyMs / 60_000} мин работы — " +
                    "читаю номера снова",
                urgent = true,
            )
            speak("Распознавание восстановлено.")
            hideBanner()
        }

        // Giving up on restarting the *process* must not mean giving up on the engine. Once that
        // budget was spent, nothing in the app ever touched recognition again: the worker was
        // stopped, no timer was pending, and every other branch below returns early while the
        // outage is open — so the phone spent the rest of the day looking healthy and reading
        // nothing. Keep knocking, slowly. The vendor's allowance may simply be waiting out a
        // clock, and a try every few minutes costs nothing next to a blind drive.
        if (status.runtimeLimited) {
            if (now - lastEngineRetryMs < ENGINE_LAST_DITCH_RETRY_MS) return
            lastEngineRetryMs = now
            Log.w(TAG, "engine still limited; last-ditch reinitialization")
            AlprEngine.recoverFromRuntimeLimit(this, config)
            return
        }

        // An engine that failed to come up is as blind as one that hit its limit, and the failure
        // branch had no way back either: a crashed init left the worker stopped for good. Retrying
        // is safe because initialize only short-circuits on a *usable* engine.
        if (status.state == AlprEngine.State.ERROR && !status.runtimeLimited) {
            if (now - lastEngineRetryMs < ENGINE_ERROR_RETRY_MS) return
            lastEngineRetryMs = now
            engineErrorRetries += 1
            Log.w(TAG, "engine in ERROR (${status.code} ${status.message}); retry $engineErrorRetries")
            // Told once, on the same channel as every other blindness. Silent retrying is how the
            // driver ends up believing the scanner is watching the road when it is not.
            if (engineErrorRetries == 1) {
                bot?.broadcast(
                    "🛑 Движок ALPR не запустился (${status.code} ${status.message ?: ""}). " +
                        "Номера не читаются, пробую поднять.",
                    urgent = true,
                )
                speak("Внимание. Движок распознавания не запустился.")
            }
            if (engineErrorRetries > ENGINE_ERROR_RETRY_LIMIT) {
                if (!engineGaveUp) {
                    engineGaveUp = true
                    bot?.broadcast(
                        "⛔️ Движок ALPR не поднимается ($engineErrorRetries попыток). " +
                            "Перезапускаю приложение целиком.",
                        urgent = true,
                    )
                    restartProcess("движок не запускается")
                }
                return
            }
            AlprEngine.initialize(this, config, force = true)
            return
        }

        // An initialization that never finishes publishes nothing, so no branch of onEngineStatus
        // ever runs again: the worker stays stopped, the banner still says "starting", and every
        // other health signal looks fine because frames keep flowing through an open gate. Nothing
        // else in the app can notice this.
        if (status.state == AlprEngine.State.INITIALIZING) {
            if (engineInitSinceMs == 0L) engineInitSinceMs = now
            if (now - engineInitSinceMs > ENGINE_INIT_TIMEOUT_MS) {
                engineInitSinceMs = now
                if (now < restartRefusedUntilMs) return
                Log.e(TAG, "engine stuck in INITIALIZING; restarting the process")
                bot?.broadcast(
                    "⛔️ Движок ALPR завис на запуске. Перезапускаю приложение.",
                    urgent = true,
                )
                restartProcess("движок завис на инициализации", "движок завис на запуске")
            }
            return
        }

        if (!runtime.enginePreventiveReload) return
        if (engineDownSinceMs != 0L) return
        if (AlprEngine.uptimeMs() < ENGINE_RECYCLE_AFTER_MS) return
        if (now - lastRecycleMs < ENGINE_RECYCLE_AFTER_MS) return
        // Nothing may be lost by the pause: no car in frame, no clip running, no queue to drain.
        if (videoRecorder?.isRecording == true || worker.queueDepth > 0) return
        if (companions.isNotEmpty()) return
        // statusTracked is only refreshed while frames are being published, so once the pipeline
        // is asleep or gated it freezes at whatever was last in view — the parked car that would
        // then block the recycle for the whole stop, which is precisely the safest moment for it.
        val framesFresh = processor?.let { now - it.lastPassedGateAtMs < TRACK_COUNT_FRESH_MS } == true
        if (framesFresh && statusTracked > 0) return
        lastRecycleMs = now
        Log.i(TAG, "recycling engine preventively after ${AlprEngine.uptimeMs() / 60_000} min")
        worker.stop()
        AlprEngine.recycle(this, config)
    }

    // ---------------------------------------------------------------- rendering

    private fun renderFrame(snapshot: FrameSnapshot) {
        binding.overlayView.update(snapshot)
        statusFps = snapshot.detectFps
        statusDetMs = snapshot.detectMs
        statusQueue = snapshot.queueDepth
        statusTracked = snapshot.trackCount
        binding.stats.text = getString(
            R.string.hud_stats,
            snapshot.sampleFps,
            snapshot.detectFps,
            snapshot.detectMs.toInt(),
            worker.lastLatencyMs.toInt(),
            snapshot.queueDepth,
            snapshot.spilledCrops,
            snapshot.droppedCrops,
        )
        updatePanelTitle(snapshot.trackCount)
    }

    private fun renderLens(state: LensState) {
        lensLabel = state.step.label
        binding.lensChip.text = state.step.label
        val verification = state.verification
        val lensStateText = when {
            state.transitioning -> getString(R.string.lens_state_switching)
            verification.state == LensVerifier.State.VERIFIED -> getString(R.string.lens_state_verified)
            verification.state == LensVerifier.State.UNSUPPORTED -> ""
            verification.state == LensVerifier.State.VERIFYING -> getString(R.string.lens_state_verifying)
            else -> getString(R.string.lens_state_failed)
        }
        val physical = verification.observedPhysicalId ?: verification.expectedPhysicalId
        val resolution = controller?.analysisResolution
        binding.lensState.text = buildString {
            if (physical != null) append("phys $physical ")
            append(lensStateText)
            if (resolution != null) append(" · ${resolution.width}×${resolution.height}")
        }
        statusVerified = verification.isVerified
        binding.lensChip.setTextColor(
            when {
                verification.isVerified -> getColor(R.color.accent)
                verification.state == LensVerifier.State.UNSUPPORTED -> getColor(R.color.text_primary)
                else -> getColor(R.color.warn)
            },
        )
    }

    private fun renderRotation(tick: RotationTick) {
        binding.countdown.text = if (tick.planSize <= 1 || !tick.holding) {
            getString(R.string.hud_hold)
        } else {
            getString(R.string.hud_next_in, tick.remainingMs / 1000f)
        }
    }

    private fun updatePanelTitle(trackCount: Int = -1) {
        val tracked = if (trackCount >= 0) trackCount else 0
        val counts = getString(R.string.hud_counts, adapter.itemCount, tracked)
        // The headline answers the only question that matters while driving: is anyone with me.
        // "Clean" is a claim, and it is only made while somebody is looking.
        val escort = companions.firstOrNull()
        val watching = sessionRunning && !userPaused && !parkedPause
        binding.panelTitle.text = if (escort == null) {
            when {
                !watching -> "$counts\n⏸ ${getString(R.string.scan_not_running)}"
                airplaneMode -> "$counts\n✈️ только чёрный список"
                else -> "$counts\n✅ рядом чисто"
            }
        } else {
            val minutes = escort.contactMs / 60_000
            val duration = if (minutes >= 1) "$minutes мин" else "${escort.contactMs / 1000} с"
            "$counts\n👁 ${escort.displayPlate} держится $duration"
        }
        binding.emptyLabel.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /**
     * Puts a message on screen — and on the notification, which is the copy anyone will see.
     *
     * The banner lives under the blackout view by design: the whole point of blackout is a phone
     * that does not glow on the rear window at night. That left the banner announcing faults to
     * nobody. The session notification is already in the shade, on the side of the phone the
     * driver actually looks at, and it costs nothing to keep it honest.
     */
    /**
     * Both guarded against arriving after the activity is gone.
     *
     * These update the session notification as well as the on-screen banner, and they are reached
     * from asynchronous callbacks — the camera's, and the recorder's `Finalize`, which is
     * delivered on this same Looper and therefore *after* `onDestroy` has already stopped the
     * service. Closing the app while a clip was being written left the notification in the shade
     * and a wake lock held by a service whose activity no longer existed.
     */
    private fun showBanner(message: String) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            binding.statusBanner.text = message
            binding.statusBanner.visibility = View.VISIBLE
            if (message != lastNotifiedBanner) {
                lastNotifiedBanner = message
                ScanSessionService.start(this, message)
            }
        }
    }

    private fun hideBanner() {
        if (isFinishing || isDestroyed) return
        binding.statusBanner.visibility = View.GONE
        if (lastNotifiedBanner != null) {
            lastNotifiedBanner = null
            ScanSessionService.start(this, getString(R.string.service_running))
        }
    }

    // ---------------------------------------------------------------- manual lists

    /**
     * A plate typed in by hand, for a car the camera has not seen yet.
     *
     * The bot does this with `/bl`, and in airplane mode the bot is out of reach — which is
     * exactly when the operator watches the blacklist most. Goes through the same host methods as
     * the bot and the card dialog, so the mark lands in the database and the follow engine alike.
     */
    private fun showAddPlateDialog() {
        val dialogBinding = com.lensalpr.app.databinding.DialogAddPlateBinding.inflate(layoutInflater)
        fun typed(): String = dialogBinding.plateInput.text?.toString().orEmpty().trim()
        fun apply(reply: String) {
            Toast.makeText(this, reply.replace(Regex("<[^>]+>"), ""), Toast.LENGTH_LONG).show()
            publishVehicles()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_plate_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_add_blacklist) { _, _ -> apply(botHost.setBlacklist(typed(), true)) }
            .setNeutralButton(R.string.action_add_police) { _, _ -> apply(botHost.setPolice(typed(), true)) }
            .setNegativeButton(R.string.dialog_cancel_action, null)
            .create()
        dialogBinding.plateInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                apply(botHost.setBlacklist(typed(), true))
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
        dialogBinding.plateInput.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------------------------------------------------------------- airplane mode

    /**
     * Airplane mode is the operator's way of saying "no network, probably no GPS, keep going".
     *
     * The scanner keeps recognizing and recording every car, but the route classifier is paused
     * — every verdict it could reach would rest on evidence that is not being collected — and the
     * attention goes to the two lists the operator wrote by hand. The phone also stops
     * complaining about a Telegram it cannot reach.
     *
     * A car on the blacklist or the police list is confirmed on its first exact read instead of
     * waiting for consensus in *every* mode, not only here: the operator asked for the alarm the
     * moment such a plate is read, and an exact match against a hand-written list is not the kind
     * of read that consensus exists to protect against.
     */
    private fun watchAirplaneMode() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyAirplaneMode(intent.getBooleanExtra("state", isAirplaneModeOn()), announce = true)
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        airplaneReceiver = receiver
        registry.priority = { plate -> follow?.isMarked(plate) == true }
        applyAirplaneMode(isAirplaneModeOn(), announce = false)
    }

    private fun isAirplaneModeOn(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)

    private fun applyAirplaneMode(on: Boolean, announce: Boolean) {
        if (destroyed || airplaneMode == on) return
        airplaneMode = on
        follow?.watchlistOnly = on
        bot?.offline = on
        Log.i(TAG, "airplane mode ${if (on) "on" else "off"}")
        if (on) {
            showBanner(getString(R.string.airplane_banner))
            if (announce) speak("Режим самолёта. Слежу только за чёрным списком и полицией.")
        } else {
            airplaneOffAtMs = SystemClock.elapsedRealtime()
            if (lastNotifiedBanner == getString(R.string.airplane_banner)) hideBanner()
            if (announce) speak("Режим самолёта выключен. Обычный режим.")
        }
        updatePanelTitle(statusTracked)
    }

    /** A listed car on the screen for a while, then whatever banner was there before. */
    private fun showMarkBanner(evidence: FollowEvidence) {
        val text = if (evidence.police) {
            "🚔 ПОЛИЦИЯ: ${evidence.displayPlate}"
        } else {
            "⛔️ ЧЁРНЫЙ СПИСОК: ${evidence.displayPlate}"
        }
        showBanner(text)
        markBannerRestore?.let(mainHandler::removeCallbacks)
        val restore = Runnable {
            markBannerRestore = null
            if (destroyed || binding.statusBanner.text != text) return@Runnable
            if (airplaneMode) showBanner(getString(R.string.airplane_banner)) else hideBanner()
        }
        markBannerRestore = restore
        mainHandler.postDelayed(restore, MARK_BANNER_MS)
    }

    // ---------------------------------------------------------------- controls

    private fun togglePause() {
        setUserPaused(!userPaused)
    }

    /** The one manual pause/resume, shared by the button and the bot. Main thread only. */
    private fun setUserPaused(paused: Boolean) {
        userPaused = paused
        // "Resume" has to actually resume. The parked sleep is a separate term of the same
        // disjunction, so without clearing it the button did nothing at all whenever the scanner
        // had put itself to sleep — which is exactly when somebody reaches for it.
        if (!userPaused && parkedPause) {
            parkedPause = false
            lastMovingMs = System.currentTimeMillis()
            hideBanner()
        }
        applyPause()
        binding.btnPause.setText(if (userPaused) R.string.action_resume else R.string.action_pause)
        updatePanelTitle(statusTracked)
    }

    private fun applyPause() {
        val paused = userPaused || lifecyclePaused || parkedPause || !sessionRunning
        gate.paused = paused
        scheduler?.setPaused(paused)
        if (paused && spillStore.count > 0) {
            Toast.makeText(
                this,
                "Догоняю отложенные вырезы: ${spillStore.count}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private var torchOn = false
    private var parkedPause = false

    @Volatile
    private var sessionRunning = true
    private var lastMovingMs = System.currentTimeMillis()

    /**
     * Screen off, scanner on.
     *
     * A phone on the rear window does not need a display: it heats the glass, reflects into the
     * mirror at night and announces that something is filming. Recognition keeps running.
     */
    private fun setBlackout(enabled: Boolean) {
        binding.blackout.visibility = if (enabled) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = if (enabled) 0.01f else -1f
        }
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        controller?.setTorch(torchOn)
    }

    private fun showVehicle(card: VehicleCard) {
        val dialogBinding = DialogVehicleBinding.inflate(layoutInflater)
        dialogBinding.plate.text = card.displayPlate
        dialogBinding.makeModel.text = listOfNotNull(card.makeModel, card.year)
            .joinToString(" · ")
            .ifBlank { getString(R.string.card_unknown_model) }
        dialogBinding.details.text = listOfNotNull(
            card.color?.let { getString(R.string.vehicle_color, it) },
            card.bodyStyle?.let { getString(R.string.vehicle_body, it) },
            card.country?.let { getString(R.string.vehicle_country, it) },
            getString(R.string.vehicle_ocr, card.ocrScore.roundToInt()),
            getString(R.string.vehicle_lens, card.lenses.joinToString("/")),
            getString(
                R.string.vehicle_seen,
                clock.format(Date(card.firstSeenMs)),
                clock.format(Date(card.lastSeenMs)),
            ),
        ).joinToString("\n")
        card.thumbnail?.takeIf { !it.isRecycled }?.let(dialogBinding.image::setImageBitmap)

        // The dialog has room for three buttons, and deciding about the car matters more than
        // copying it, so the plate itself carries the clipboard.
        dialogBinding.plate.setOnLongClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("plate", card.displayPlate))
            Toast.makeText(this, R.string.plate_copied, Toast.LENGTH_SHORT).show()
            true
        }

        val evidence = evidenceByPlate[card.plate]
        val blacklisted = evidence?.blacklisted == true
        val ignored = evidence?.ignored == true
        val police = evidence?.police == true
        dialogBinding.btnPolice.setText(
            if (police) R.string.action_unpolice else R.string.action_police,
        )
        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(
                if (blacklisted) R.string.action_unblacklist else R.string.action_blacklist,
            ) { _, _ ->
                // Through the same path the bot uses: it writes the database itself, so the mark
                // is kept even when the follow engine is not classifying.
                botHost.setBlacklist(card.plate, !blacklisted)
                if (!blacklisted) {
                    Toast.makeText(
                        this,
                        getString(R.string.blacklisted_toast, card.displayPlate),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                publishVehicles()
            }
            .setPositiveButton(R.string.dialog_close, null)
            .setNeutralButton(if (ignored) R.string.action_unignore else R.string.action_ignore) { _, _ ->
                Toast.makeText(
                    this,
                    botHost.setIgnored(card.plate, !ignored).replace(Regex("<[^>]+>"), ""),
                    Toast.LENGTH_SHORT,
                ).show()
                publishVehicles()
            }
            .show()
            // The dialog owns the view, so the listener is attached after it is shown and closes
            // the dialog itself — otherwise marking a car would leave the driver looking at a
            // button whose label no longer matches what it would do.
            .also { dialog ->
                dialogBinding.btnPolice.setOnClickListener {
                    botHost.setPolice(card.plate, !police)
                    Toast.makeText(
                        this,
                        getString(
                            if (police) R.string.unpolice_toast else R.string.police_toast,
                            card.displayPlate,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    publishVehicles()
                    dialog.dismiss()
                }
            }
    }

    private fun enterImmersiveMode() {
        // The screen stays on for the whole drive. Blackout already takes the brightness to almost
        // nothing, so this costs very little — and letting the display time out invites the system
        // to start winding the process down at the exact point nobody is watching for it.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        lifecyclePaused = false
        applyPause()
    }

    override fun onStop() {
        // Deliberately not pausing: a phone on the rear window keeps working with the screen off,
        // which is what the foreground service is for.
        super.onStop()
    }

    override fun onDestroy() {
        if (!created) {
            // Sent to the lock screen from onCreate: nothing below exists yet.
            super.onDestroy()
            return
        }
        // First, before anything is torn down: every callback that lands from here on checks it.
        destroyed = true
        airplaneReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        airplaneReceiver = null
        markBannerRestore?.let(mainHandler::removeCallbacks)
        markBannerRestore = null
        AlprEngine.removeListener(engineListener)
        ScanSessionService.stopListener = null
        thermalListener?.let { listener ->
            runCatching {
                getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(listener)
            }
        }
        sessionOwner.stop()
        ScanSessionService.stop(this)
        mainHandler.removeCallbacks(recordingWatchdog)
        // The driver closing the app outranks any repair the app had planned for itself. Without
        // this, dismissing the scanner during the grace period would kill the process and then
        // bring the app straight back from an alarm — including the final exit step and the alarm
        // it had already armed.
        engineRestartPending?.let(mainHandler::removeCallbacks)
        engineRestartPending = null
        processRestartPending?.let(mainHandler::removeCallbacks)
        processRestartPending = null
        exitPending?.let { exit ->
            mainHandler.removeCallbacks(exit)
            exitPending = null
            restartAlarm?.let { alarm ->
                runCatching { getSystemService(AlarmManager::class.java)?.cancel(alarm) }
                runCatching { alarm.cancel() }
            }
            restartAlarm = null
            // The trip was handed to a process that will now never start; take it back so the
            // next manual launch does not resume a drive that ended here — and that launch has a
            // human at the phone, so it goes through the lock screen like any other.
            runtime.consumeTripHandover(System.currentTimeMillis())
            LockStore.disarmAutoUnlock(this)
        }
        // Finalizing an MP4 is asynchronous. Give it a moment before the bot is torn down, or the
        // clip recorded seconds before closing the app would never be delivered.
        // The Finalize callback is delivered on this very Looper, so sleeping here could only ever
        // prevent it. An undelivered clip is picked up by resendPendingClips on the next start.
        segmentPlate = null
        videoRecorder?.detach()
        tracker?.stop()
        bot?.stop()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        // The focus request outlives the speech engine unless it is given back explicitly; the
        // car's music stayed ducked after the scanner was closed mid-sentence.
        abandonAudioFocus()
        val trip = tripId
        val distance = tracker?.odometerM ?: 0.0
        if (trip != 0L) {
            ioExecutor.execute {
                runCatching { store.finishTrip(trip, System.currentTimeMillis(), distance) }
            }
        }
        ioService.shutdown()
        scheduler?.stop()
        controller?.shutdown()
        // Closing the ONNX session or recycling its bitmaps while a frame is still being detected
        // is a native use-after-free. The cleanup is therefore queued on the analysis thread itself,
        // behind whatever frame it is busy with: no frame can follow — the analyzer was cleared
        // above — and the detector is closed exactly once, by the thread that used it, whether
        // that happens now or a second later. Waiting on it here with a timeout used to leave the
        // detector open for good whenever the frame outlasted the timeout and the process lived on.
        val cleanup = processor
        val yolo = detector
        processor = null
        detector = null
        runCatching {
            analysisExecutor.execute {
                runCatching { cleanup?.release() }
                runCatching { yolo?.close() }
            }
        }.onFailure {
            runCatching { cleanup?.release() }
            runCatching { yolo?.close() }
        }
        analysisExecutor.shutdown()
        worker.close()
        registry.clear()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LensALPR.Scan"
        const val TRACK_POINT_INTERVAL_MS = 3_000L
        const val DEFAULT_REPORT_WINDOW_MS = 12L * 3_600_000L
        const val SNAPSHOT_WIDTH = 1920
        const val WATCHDOG_INTERVAL_MS = 5_000L

        /**
         * Silence from the camera beyond this is a fault, not a slow frame: even at 4K with the
         * detector throttled the stream delivers several frames a second.
         */
        const val STALL_LIMIT_MS = 12_000L

        /** Time a rebind is given to produce a frame before another attempt is counted. */
        const val REBIND_GRACE_MS = 15_000L

        /**
         * How late a crop may be and still count as an observation of where a car was. Beyond this
         * it only contributes its plate: the follow engine reasons about time and route, and a
         * recovered crop knows neither any more.
         */
        const val SIGHTING_FRESHNESS_MS = 10_000L

        /** Failed recoveries before the operator is told the session is blind. */
        const val STALL_ALERT_AFTER = 2

        /**
         * Frames arriving but none recognised for this long is a fault, not a lens change.
         *
         * A rotation step settles inside two seconds; anything past this is a gate that is not
         * going to reopen on its own.
         */
        const val BLIND_LIMIT_MS = 15_000L

        /** Watchdog ticks of blindness before Telegram is told. */
        const val BLIND_ALERT_AFTER = 2

        /**
         * Time each recovery attempt is given before the next rung is tried.
         *
         * A lens change settles in about two seconds and a rebind needs several, so this is
         * generous on purpose: judging an attempt too early would spend the whole ladder in half a
         * minute and land on a process restart that was never needed.
         */
        const val BLIND_RECOVERY_GRACE_MS = 20_000L

        /**
         * Telegram silence past this is an outage worth mentioning.
         *
         * Long enough to ride out a tunnel, a lift or a cell handover without a word.
         */
        const val BOT_UNREACHABLE_LIMIT_MS = 3L * 60_000L

        /**
         * Floor on how often one plate may be spoken aloud.
         *
         * The engine already rations alerts per car; this is the last line of defence for the one
         * channel the driver cannot ignore or postpone.
         */
        const val VOICE_COOLDOWN_MS = 2L * 60_000L

        /**
         * Floor between spoken warnings about a tail or a listed car.
         *
         * The operator asked to be told on every recognition while such a car is behind them, so
         * this is only wide enough for the sentence to finish. Raise it if the voice becomes more
         * distracting than the car.
         */
        const val VOICE_PERSISTENT_MS = 12_000L

        /**
         * What a spoken line is allowed to interrupt.
         *
         * Only a strictly higher rank cuts in; equals queue. That is what stops two tails from
         * erasing each other two seconds apart and what keeps an alarm from wiping the sentence
         * saying that recognition itself has stopped — while still letting a tail jump ahead of
         * a merely suspicious car.
         */
        const val VOICE_PRIORITY_IDLE = -1
        const val VOICE_PRIORITY_REMINDER = 0
        const val VOICE_PRIORITY_NOTICE = 1
        const val VOICE_PRIORITY_STATUS = 2
        const val VOICE_PRIORITY_ALARM = 3

        /**
         * Engine rebuilds attempted before the whole process is restarted.
         *
         * If rebuilding the native session cleared the runtime allowance, the first attempt already
         * worked. More than a couple of failures means the allowance is counted per process, and no
         * number of further rebuilds will change that.
         */
        const val ENGINE_RESTART_LIMIT = 3

        /** Delay before each engine rebuild: immediate, then backing off. */
        val ENGINE_RESTART_BACKOFF_MS = longArrayOf(500L, 20_000L, 60_000L)

        /**
         * Uptime after which a healthy engine is rebuilt on purpose.
         *
         * Comfortably below the hour-plus at which the limit was observed on the road, so the
         * rebuild happens at a moment of our choosing rather than the engine's.
         */
        const val ENGINE_RECYCLE_AFTER_MS = 40L * 60_000L

        /** How often a failed engine initialization is retried. */
        const val ENGINE_ERROR_RETRY_MS = 30_000L

        /** Failed initializations before the process is restarted instead. */
        const val ENGINE_ERROR_RETRY_LIMIT = 4

        /**
         * How often a runtime-limited engine is poked once every other repair has been exhausted.
         *
         * Slow on purpose — this is the state where nothing has worked yet — but never zero: the
         * alternative is an app that looks alive and reads nothing until somebody notices.
         */
        const val ENGINE_LAST_DITCH_RETRY_MS = 5L * 60_000L

        /** Past this, the last published track count describes a frame nobody is looking at. */
        const val TRACK_COUNT_FRESH_MS = 5_000L

        /**
         * An initialization taking longer than this is hung, not slow.
         *
         * A cold start with the warm-up pass takes a few seconds on this hardware; two minutes is
         * far past any honest reading of "still working on it".
         */
        const val ENGINE_INIT_TIMEOUT_MS = 120_000L

        /**
         * Recognition time a restart must buy before it counts as having worked.
         *
         * Below this, the runtime allowance is clearly counted per process rather than per native
         * session, and rebuilding the engine is not a repair — it is a loop.
         */
        const val ENGINE_HEALTHY_MS = 5L * 60_000L

        /** Grace before the process restart, so the last Telegram message actually leaves. */
        const val PROCESS_RESTART_DELAY_MS = 2_500L

        /** Time the recorder is given to write the MP4 index before the process dies. */
        const val RESTART_DRAIN_MS = 1_500L

        /** The most the exit waits for a clip that is still finalizing. */
        const val RESTART_DRAIN_CEILING_MS = 6_000L

        /** How long a bot command waits for the main thread's answer before answering blind. */
        const val MAIN_SYNC_TIMEOUT_MS = 2_500L

        /** How long a wipe may take before the bot is answered without the result. */
        const val WIPE_TIMEOUT_MS = 30_000L

        /** How often the undelivered clips are offered to Telegram again while driving. */
        const val CLIP_RESEND_INTERVAL_MS = 10L * 60_000L

        /** Inside one crop this long, the engine is not slow; it is gone. */
        const val OCR_STUCK_MS = 45_000L

        /** Stuck this much longer after being reported, the process is restarted. */
        const val OCR_STUCK_RESTART_MS = 90_000L

        /**
         * When the alarm fires, measured from the moment it is armed.
         *
         * Must be comfortably *after* the longest the exit can wait for the recorder, or the alarm
         * launches the activity in the process that is about to kill itself — the alarm is then
         * spent, the process dies, and nothing ever brings the app back.
         */
        const val RESTART_ALARM_DELAY_MS = RESTART_DRAIN_CEILING_MS + 3_000L
        const val RESTART_REQUEST_CODE = 0x1E5A

        /** How long past the nominal alarm delay a restarted process may still skip the lock. */
        const val AUTO_UNLOCK_GRACE_MS = 10L * 60_000L

        /** How long a blacklist or police banner keeps the screen before the base banner returns. */
        const val MARK_BANNER_MS = 30_000L

        /** How long after a rebind took the camera away before the clip is started again on the new one. */
        const val SOURCE_LOST_RETRY_MS = 1_500L

        /**
         * Process restarts allowed inside [RESTART_WINDOW_MS].
         *
         * The attempt counters live in the activity and die with the process, so without a
         * persistent budget a phone where nothing can clear the limit would restart itself for
         * ever. Past this the app stays up and blind, and says so, which is recoverable — a boot
         * loop is not.
         */
        const val MAX_PROCESS_RESTARTS = 3
        const val RESTART_WINDOW_MS = 60L * 60_000L

        /** Long enough for the bot to have connected before the warning is sent. */
        const val BATTERY_WARNING_DELAY_MS = 20_000L
        const val PREFLIGHT_DELAY_MS = 20_000L
        const val MOVING_SPEED_MPS = 2f
        const val PARKED_AFTER_MS = 5L * 60_000L

        /**
         * A fix older than this is not a speed source any more.
         *
         * Location updates are requested once a second; a minute and a half of nothing means the
         * feed is gone, and a scanner must never put itself to sleep on evidence that stale.
         */
        const val FIX_STALE_MS = 90_000L

        /** Telegram refuses uploads past 50 MB; three minutes of 720p stays well inside that. */
        /**
         * 720p costs about 20 MB per minute on this phone, and a bot may upload 50 MB, so a tail
         * longer than this is filmed as several clips instead of one Telegram refuses.
         */
        const val MAX_CLIP_MS = 90_000L
        const val MAX_RESEND_CLIPS = 5

        /** Delivered clips stay on the phone for a week, then make room for new ones. */
        const val CLIP_RETENTION_MS = 7L * 24 * 3_600_000L
        const val SENT_PREFIX = "sent_"
        const val CLIP_PREFIX = "tail_"

        /** Clips too large for Telegram, kept out of the queue but not thrown away. */
        const val OVERSIZED_PREFIX = "big_"

        /**
         * The original of a clip that was cut up and sent in pieces.
         *
         * Kept on the phone as the unedited recording, and renamed out of the resend queue so the
         * next start does not split the same file again on top of the parts already sent.
         */
        const val SPLIT_PREFIX = "split_"

        /**
         * Undelivered clips live shorter than delivered ones.
         *
         * Backwards at first glance, and deliberate: a `sent_` file is a local copy of something
         * Telegram already holds, while an undelivered one is unique — but the undelivered ones
         * are also the ones that pile up without limit when the phone spends a drive out of
         * coverage, and a full disk stops the next clip from being recorded at all.
         */
        const val UNSENT_RETENTION_MS = 3L * 24 * 3_600_000L

        /** Ceiling on the whole clip directory. */
        const val CLIP_DIR_BUDGET_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * Free space the recorder needs, mirrored from [VideoRecorder].
         *
         * Housekeeping has to aim at the same number the recorder refuses on, or the two disagree
         * and the app spends a drive cleaning up to a threshold that still leaves it unable to
         * record.
         */
        const val MIN_FREE_BYTES = VideoRecorder.MIN_FREE_BYTES

        /** A file touched this recently may be the one the recorder is writing. */
        const val ACTIVE_CLIP_GRACE_MS = 5L * 60_000L

        /** How often the clip directory is swept while driving. */
        const val CLIP_PRUNE_INTERVAL_MS = 10L * 60_000L

        /** Floor between reports that a clip could not be recorded. */
        const val CLIP_FAILURE_REPORT_MS = 5L * 60_000L

        /** Matches the bot's own refusal threshold exactly, so the two cannot drift apart. */
        val MAX_SENDABLE_BYTES = (TelegramBot.MAX_UPLOAD_MB * 1024 * 1024).toLong()


        /** Plate sightings are personal data; they do not need to live longer than a month. */
        const val RETENTION_MS = 30L * 24 * 3_600_000L

        val DATE_TIME = SimpleDateFormat("dd.MM HH:mm", Locale.US)
    }
}
