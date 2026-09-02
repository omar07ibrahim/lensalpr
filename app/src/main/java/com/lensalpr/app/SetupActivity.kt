package com.lensalpr.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lensalpr.app.camera.CameraCatalog
import com.lensalpr.app.camera.CameraHardwareIdentity
import com.lensalpr.app.camera.RearCameraSetup
import com.lensalpr.app.camera.StepKind
import com.lensalpr.app.camera.ZoomStep
import com.lensalpr.app.data.StorageCleaner
import com.lensalpr.app.data.TrackingStore
import com.lensalpr.app.data.WipeStats
import com.lensalpr.app.databinding.ActivitySetupBinding
import com.lensalpr.app.databinding.ItemLensStepBinding
import com.lensalpr.app.settings.Accelerator
import com.lensalpr.app.settings.CaptureResolution
import com.lensalpr.app.settings.DetectorModel
import com.lensalpr.app.settings.PlannedStep
import com.lensalpr.app.settings.ScanConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Pre-flight screen.
 *
 * Everything that changes how the camera session is built is decided here, before the first frame:
 * which zoom steps take part in the rotation, how long each one is held, and how much work the
 * detector and the OCR engine are allowed to do per frame.
 */
class SetupActivity : AppCompatActivity() {

    private class RowState(var enabled: Boolean, var dwellSeconds: Int)

    private lateinit var binding: ActivitySetupBinding
    private var setup: RearCameraSetup? = null
    private var config: ScanConfig? = null
    private val rows = LinkedHashMap<String, RowState>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        // Both, always. Asking for FINE on its own is rejected by the platform without showing a
        // dialog at all, so the app would silently start with no location — and with no location
        // the follow engine has no odometer, every vehicle classifies as IGNORE, and the scanner
        // runs all day without ever raising an alarm.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Without it the session notification is invisible - and that notification is the only way
        // to see the scanner is alive while the screen is off, and to stop it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.permissionButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
        binding.btnTelegramTest.setOnClickListener { testTelegram() }
        binding.btnReset.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit { clear() }
            rows.clear()
            refresh()
        }
        binding.btnWipe.setOnClickListener { confirmWipe() }
        binding.btnStart.setOnClickListener { start() }

        if (!hasCameraPermission() || !hasLocationPermission()) {
            permissionLauncher.launch(requiredPermissions)
        }
        refresh()
        announceUpdateOnce()
    }

    /**
     * Says, exactly once ever, that a new build has been installed and is ready to test.
     *
     * There is somebody standing next to the phone who has no other way of knowing an update
     * landed — the screen looks the same either way. This runs on the launcher screen so it does
     * not wait for anyone to press start, and it writes its flag before speaking, so the next
     * launch and every launch after it are silent again with nothing to remember to remove. The
     * engine is shut down as soon as the sentence ends; the scanner builds its own.
     */
    private fun announceUpdateOnce() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(KEY_UPDATE_ANNOUNCED, false)) return
        prefs.edit { putBoolean(KEY_UPDATE_ANNOUNCED, true) }
        // Through a holder the callback re-reads, and posted to the main thread before it is read.
        // TextToSpeech is documented to call back asynchronously but is not required to, and a
        // synchronous onInit would run before the constructor had returned — reading a reference
        // that is still null and losing the one sentence this whole thing exists to say.
        val holder = AtomicReference<TextToSpeech?>(null)
        val created = TextToSpeech(applicationContext) { status ->
            binding.root.post {
                val speaker = holder.get()
                if (status != TextToSpeech.SUCCESS || speaker == null) {
                    Toast.makeText(this, UPDATE_TEXT, Toast.LENGTH_LONG).show()
                    runCatching { speaker?.shutdown() }
                    return@post
                }
                val language = runCatching { speaker.setLanguage(Locale.forLanguageTag("ru")) }
                    .getOrNull()
                // No Russian voice data means the sentence would be silent or unintelligible, and
                // the person waiting beside the phone would learn nothing. Show it instead.
                if (language == TextToSpeech.LANG_MISSING_DATA ||
                    language == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Toast.makeText(this, UPDATE_TEXT, Toast.LENGTH_LONG).show()
                    runCatching { speaker.shutdown() }
                    return@post
                }
                runCatching {
                    speaker.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            runCatching { speaker.shutdown() }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            runCatching { speaker.shutdown() }
                        }
                    })
                    speaker.speak(UPDATE_TEXT, TextToSpeech.QUEUE_FLUSH, null, UPDATE_UTTERANCE)
                }.onFailure {
                    Toast.makeText(this, UPDATE_TEXT, Toast.LENGTH_LONG).show()
                    runCatching { speaker.shutdown() }
                }
            }
        }
        holder.set(created)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val granted = hasCameraPermission()
        binding.permissionCard.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnStart.isEnabled = granted

        val discovered = setup ?: CameraCatalog.discover(this)?.also { setup = it }
        val loaded = ScanConfig.load(this, discovered)
        config = loaded

        binding.hardwareSummary.text = getString(
            R.string.hardware_summary,
            CameraHardwareIdentity.displayName,
            discovered?.modules?.size ?: 0,
        )

        buildLensRows(discovered, loaded)
        bindCaptureControls(loaded)
        bindDetectorControls(loaded)
        bindRecognitionControls(loaded)
        bindFollowControls(loaded)
    }

    private fun orderedSteps(setup: RearCameraSetup?): List<ZoomStep> =
        setup?.steps.orEmpty().sortedBy { it.requestedZoom }

    private fun buildLensRows(setup: RearCameraSetup?, config: ScanConfig) {
        val steps = orderedSteps(setup)
        val planned = config.plan.associateBy { it.stepId }
        binding.lensContainer.removeAllViews()

        steps.forEach { step ->
            val state = rows.getOrPut(step.id) {
                val entry = planned[step.id]
                RowState(
                    enabled = entry != null,
                    dwellSeconds = entry?.dwellSeconds ?: ScanConfig.DEFAULT_DWELL_SECONDS,
                )
            }
            val row = ItemLensStepBinding.inflate(layoutInflater, binding.lensContainer, false)
            row.label.text = step.label
            row.kind.text = when (step.kind) {
                StepKind.OPTICAL -> getString(R.string.lens_kind_optical)
                StepKind.DIGITAL -> getString(R.string.lens_kind_digital, step.baseLabel ?: "")
            }
            row.kind.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (step.kind == StepKind.OPTICAL) R.color.optical else R.color.digital,
                ),
            )
            val module = step.module
            row.detail.text = if (module != null) {
                getString(R.string.lens_detail, module.focalLengthMm, module.physicalId)
            } else {
                getString(R.string.lens_detail_logical)
            }
            row.enabled.isChecked = state.enabled
            row.dwell.text = getString(R.string.dwell_seconds, state.dwellSeconds)

            row.enabled.setOnCheckedChangeListener { _, checked ->
                state.enabled = checked
                binding.planWarning.visibility = android.view.View.GONE
            }
            row.minus.setOnClickListener {
                state.dwellSeconds = (state.dwellSeconds - dwellStep(state.dwellSeconds - 1))
                    .coerceIn(ScanConfig.MIN_DWELL_SECONDS, ScanConfig.MAX_DWELL_SECONDS)
                row.dwell.text = getString(R.string.dwell_seconds, state.dwellSeconds)
            }
            row.plus.setOnClickListener {
                state.dwellSeconds = (state.dwellSeconds + dwellStep(state.dwellSeconds))
                    .coerceIn(ScanConfig.MIN_DWELL_SECONDS, ScanConfig.MAX_DWELL_SECONDS)
                row.dwell.text = getString(R.string.dwell_seconds, state.dwellSeconds)
            }
            binding.lensContainer.addView(row.root)
        }
    }

    /** Fine steps for short holds, coarser once the dwell is long enough to matter. */
    private fun dwellStep(current: Int): Int = when {
        current >= 30 -> 10
        current >= 10 -> 5
        else -> 1
    }

    private fun bindCaptureControls(config: ScanConfig) {
        val id = when (config.resolution) {
            CaptureResolution.HD -> R.id.rbHd
            CaptureResolution.FHD -> R.id.rbFhd
            CaptureResolution.QHD -> R.id.rbQhd
            CaptureResolution.UHD -> R.id.rbUhd
        }
        binding.resolutionGroup.check(id)
    }

    private fun bindDetectorControls(config: ScanConfig) {
        binding.modelGroup.check(
            when (config.model) {
                DetectorModel.ACCURATE -> R.id.rbSmall
                DetectorModel.REACH -> R.id.rbNano
                DetectorModel.FAST -> R.id.rbFast
            },
        )
        binding.accelGroup.check(
            if (config.accelerator == Accelerator.NNAPI) R.id.rbNnapi else R.id.rbXnn,
        )
        bindSeek(
            seekBar = binding.seekConfidence,
            value = (config.confidence * 100f).roundToInt(),
        ) { progress ->
            binding.labelConfidence.text = getString(R.string.detector_confidence, progress)
        }
        bindSeek(binding.seekMinBox, config.minBoxPx) { progress ->
            binding.labelMinBox.text = getString(R.string.detector_min_box, progress)
        }
    }

    private fun bindRecognitionControls(config: ScanConfig) {
        bindSeek(binding.seekConsensus, config.consensusMatches) { progress ->
            binding.labelConsensus.text = getString(R.string.recognition_consensus, progress)
        }
        bindSeek(binding.seekInterval, config.ocrIntervalMs) { progress ->
            binding.labelInterval.text = getString(R.string.recognition_interval, progress)
        }
        bindSeek(binding.seekCrops, config.maxCropsPerFrame) { progress ->
            binding.labelCrops.text = getString(R.string.recognition_max_crops, progress)
        }
        bindSeek(binding.seekMinScore, config.minOcrScore) { progress ->
            binding.labelMinScore.text = getString(R.string.recognition_min_score, progress)
        }
        binding.switchVmmr.isChecked = config.vmmr
        binding.switchStrictFormat.isChecked = config.strictPlateFormat
        binding.switchDeep.isChecked = config.deepSearch
        binding.switchRectify.isChecked = config.rectifyPlates
        binding.switchStrict.isChecked = config.strictLens
        binding.switchDirect.isChecked = config.allowDirectPhysical
    }

    private fun bindFollowControls(config: ScanConfig) {
        binding.switchFollow.isChecked = config.followEnabled
        binding.switchVoice.isChecked = config.voiceAlerts
        binding.switchTelegram.isChecked = config.telegramEnabled
        if (binding.editToken.text.isNullOrBlank()) {
            binding.editToken.setText(config.telegramToken)
        }
        if (binding.editOwner.text.isNullOrBlank() && config.telegramOwnerId != 0L) {
            binding.editOwner.setText(config.telegramOwnerId.toString())
        }
        bindSeek(binding.seekAlertLevel, config.alertMinLevel) { progress ->
            binding.labelAlertLevel.text = getString(R.string.alert_level, levelName(progress))
        }
        bindSeek(binding.seekAlertEncounters, config.alertAfterEncounters) { progress ->
            binding.labelAlertEncounters.text = getString(R.string.alert_encounters, progress)
        }
        if (!hasLocationPermission() && config.followEnabled) {
            binding.telegramStatus.text = getString(R.string.location_required)
        }
    }

    private fun levelName(rank: Int): String = when (rank) {
        1 -> getString(R.string.level_watch)
        2 -> getString(R.string.level_suspect)
        3 -> getString(R.string.level_tail)
        else -> getString(R.string.level_blacklist)
    }

    /** Verifies the token end to end: identifies the bot and sends a real message to the owner. */
    /** Wipes every observation before a fresh drive: database, photos, clips, reports. */
    private fun confirmWipe() {
        Thread { val usage = StorageCleaner.usage(applicationContext); runOnUiThread { showWipeDialog(usage) } }.start()
    }

    private fun showWipeDialog(usage: Pair<Int, Long>) {
        if (isFinishing || isDestroyed) return
        val (files, bytes) = usage
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_data)
            .setMessage(getString(R.string.wipe_confirm, files, WipeStats.format(bytes)))
            .setNegativeButton(R.string.dialog_close, null)
            .setPositiveButton(R.string.wipe_do) { _, _ ->
                Thread {
                    val store = TrackingStore(applicationContext)
                    val stats = runCatching { StorageCleaner.wipe(applicationContext, store) }
                    store.close()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            stats.getOrNull()?.describe() ?: getString(R.string.wipe_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.start()
            }
            .show()
    }

    private fun testTelegram() {
        val token = binding.editToken.text?.toString()?.trim().orEmpty()
        val owner = binding.editOwner.text?.toString()?.trim()?.toLongOrNull() ?: 0L
        if (token.isBlank() || owner == 0L) {
            binding.telegramStatus.text = getString(R.string.telegram_need_fields)
            return
        }
        binding.telegramStatus.text = "…"
        Thread {
            val client = com.lensalpr.app.telegram.TelegramClient(token)
            val name = client.getMe()
            val delivered = name != null &&
                client.sendMessage(owner, "\u2705 LensALPR: связь есть.")
            runOnUiThread {
                binding.telegramStatus.text = if (delivered) {
                    getString(R.string.telegram_ok, name)
                } else {
                    getString(R.string.telegram_fail)
                }
            }
        }.start()
    }

    private fun bindSeek(seekBar: SeekBar, value: Int, onValue: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                onValue(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        seekBar.progress = value.coerceIn(seekBar.min, seekBar.max)
        onValue(seekBar.progress)
    }

    private fun start() {
        val current = config ?: return
        val steps = orderedSteps(setup)
        val plan = steps.mapNotNull { step ->
            val state = rows[step.id] ?: return@mapNotNull null
            if (!state.enabled) null else PlannedStep(step.id, state.dwellSeconds)
        }
        if (plan.isEmpty()) {
            binding.planWarning.visibility = android.view.View.VISIBLE
            return
        }

        current.copy(
            plan = plan,
            model = when (binding.modelGroup.checkedRadioButtonId) {
                R.id.rbSmall -> DetectorModel.ACCURATE
                R.id.rbFast -> DetectorModel.FAST
                else -> DetectorModel.REACH
            },
            accelerator = if (binding.accelGroup.checkedRadioButtonId == R.id.rbNnapi) {
                Accelerator.NNAPI
            } else {
                Accelerator.XNNPACK
            },
            confidence = binding.seekConfidence.progress / 100f,
            minBoxPx = binding.seekMinBox.progress,
            resolution = when (binding.resolutionGroup.checkedRadioButtonId) {
                R.id.rbHd -> CaptureResolution.HD
                R.id.rbFhd -> CaptureResolution.FHD
                R.id.rbUhd -> CaptureResolution.UHD
                else -> CaptureResolution.QHD
            },
            consensusMatches = binding.seekConsensus.progress,
            ocrIntervalMs = binding.seekInterval.progress,
            maxCropsPerFrame = binding.seekCrops.progress,
            minOcrScore = binding.seekMinScore.progress,
            deepSearch = binding.switchDeep.isChecked,
            rectifyPlates = binding.switchRectify.isChecked,
            strictLens = binding.switchStrict.isChecked,
            vmmr = binding.switchVmmr.isChecked,
            strictPlateFormat = binding.switchStrictFormat.isChecked,
            allowDirectPhysical = binding.switchDirect.isChecked,
            followEnabled = binding.switchFollow.isChecked,
            voiceAlerts = binding.switchVoice.isChecked,
            telegramEnabled = binding.switchTelegram.isChecked,
            telegramToken = binding.editToken.text?.toString()?.trim().orEmpty(),
            telegramOwnerId = binding.editOwner.text?.toString()?.trim()?.toLongOrNull() ?: 0L,
            alertMinLevel = binding.seekAlertLevel.progress,
            alertAfterEncounters = binding.seekAlertEncounters.progress,
        ).save(this)

        startActivity(Intent(this, ScanActivity::class.java))
    }

    private companion object {
        /**
         * Set the first time the "update installed" line is spoken; never spoken again after.
         *
         * Bumped whenever the line itself changes: the flag is what makes the announcement
         * one-shot, so a new key is the only way to let a new wording be heard once.
         */
        const val KEY_UPDATE_ANNOUNCED = "update_announced_v6"
        const val UPDATE_UTTERANCE = "update-ready"
        const val UPDATE_TEXT =
            "Внимание. Внимание. Внимание. Всё готово, можете проверять."
    }
}
