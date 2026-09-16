package com.lensalpr.app.settings

import android.content.Context
import android.util.Size
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lensalpr.app.alpr.PlateRegion
import com.lensalpr.app.camera.CameraCatalog
import com.lensalpr.app.camera.RearCameraSetup
import com.lensalpr.app.telegram.TelegramDefaults
import org.json.JSONArray
import org.json.JSONObject

/** One entry of the rotation plan: which zoom step, and how long it is held. */
data class PlannedStep(
    val stepId: String,
    val dwellSeconds: Int,
)

/**
 * Detector inputs are rectangular on purpose.
 *
 * The analysis frame is 16:9; a square input would spend 44% of every convolution on grey padding.
 * 800x448 matches the frame almost exactly, so the same compute budget buys 25% more horizontal
 * resolution - a vehicle stays detectable roughly 25% further away - and 640x384 buys back 40% of
 * the time at exactly today's reach.
 */
enum class DetectorModel(val asset: String, val inputWidth: Int, val inputHeight: Int) {
    FAST("yolo26n_640x384.onnx", 640, 384),
    REACH("yolo26n_800x448.onnx", 800, 448),
    ACCURATE("yolo26s_800x448.onnx", 800, 448),
}

enum class Accelerator {
    XNNPACK,
    NNAPI,
}

enum class CaptureResolution(val size: Size) {
    HD(Size(1280, 720)),
    FHD(Size(1920, 1080)),
    QHD(Size(2560, 1440)),
    UHD(Size(3840, 2160)),
    ;

    val label: String get() = "${size.width}×${size.height}"
}

/**
 * Everything the scanner needs, decided before the camera opens.
 *
 * Stored as plain preferences so the setup screen and the scanner never disagree about the plan
 * that is running.
 */
data class ScanConfig(
    val plan: List<PlannedStep>,
    val model: DetectorModel,
    val accelerator: Accelerator,
    val detectorThreads: Int,
    val confidence: Float,
    val minBoxPx: Int,
    val resolution: CaptureResolution,
    val consensusMatches: Int,
    val ocrIntervalMs: Int,
    val maxCropsPerFrame: Int,
    val minOcrScore: Int,
    val deepSearch: Boolean,
    /** Straightens plates that are not square to the camera before reading them. */
    val rectifyPlates: Boolean,
    val strictLens: Boolean,
    val vmmr: Boolean,
    val strictPlateFormat: Boolean,
    /** Whose plate layouts are expected on the road: tried first, and the only ones repaired. */
    val plateRegion: PlateRegion,
    val allowDirectPhysical: Boolean,
    val followEnabled: Boolean,
    val voiceAlerts: Boolean,
    val telegramEnabled: Boolean,
    val telegramToken: String,
    val telegramOwnerId: Long,
    val alertMinLevel: Int,
    val alertAfterEncounters: Int,
) {

    fun save(context: Context) {
        val json = JSONArray()
        plan.forEach { step ->
            json.put(
                JSONObject()
                    .put(KEY_STEP_ID, step.stepId)
                    .put(KEY_STEP_DWELL, step.dwellSeconds),
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_PLAN, json.toString())
            putString(KEY_MODEL, model.name)
            putString(KEY_ACCEL, accelerator.name)
            putInt(KEY_THREADS, detectorThreads)
            putFloat(KEY_CONFIDENCE, confidence)
            putInt(KEY_MIN_BOX, minBoxPx)
            putString(KEY_RESOLUTION, resolution.name)
            putInt(KEY_CONSENSUS, consensusMatches)
            putInt(KEY_OCR_INTERVAL, ocrIntervalMs)
            putInt(KEY_MAX_CROPS, maxCropsPerFrame)
            putInt(KEY_MIN_SCORE, minOcrScore)
            putBoolean(KEY_DEEP, deepSearch)
            putBoolean(KEY_RECTIFY, rectifyPlates)
            putBoolean(KEY_STRICT, strictLens)
            putBoolean(KEY_VMMR, vmmr)
            putBoolean(KEY_STRICT_FORMAT, strictPlateFormat)
            putString(KEY_REGION, plateRegion.name)
            putBoolean(KEY_DIRECT_PHYSICAL, allowDirectPhysical)
            putBoolean(KEY_FOLLOW, followEnabled)
            putBoolean(KEY_VOICE, voiceAlerts)
            putBoolean(KEY_TG_ENABLED, telegramEnabled)
            putString(KEY_TG_TOKEN, telegramToken)
            putLong(KEY_TG_OWNER, telegramOwnerId)
            putInt(KEY_ALERT_LEVEL, alertMinLevel)
            putInt(KEY_ALERT_ENCOUNTERS, alertAfterEncounters)
        }
    }

    companion object {
        private const val KEY_PLAN = "lens_plan"
        private const val KEY_STEP_ID = "id"
        private const val KEY_STEP_DWELL = "dwell"
        private const val KEY_MODEL = "detector_model"
        private const val KEY_ACCEL = "detector_accel"
        private const val KEY_THREADS = "detector_threads"
        private const val KEY_CONFIDENCE = "detector_confidence"
        private const val KEY_MIN_BOX = "detector_min_box"
        private const val KEY_RESOLUTION = "capture_resolution"
        private const val KEY_CONSENSUS = "recognition_consensus"
        private const val KEY_OCR_INTERVAL = "recognition_interval"
        private const val KEY_MAX_CROPS = "recognition_max_crops"
        private const val KEY_MIN_SCORE = "recognition_min_score"
        private const val KEY_DEEP = "recognition_deep"
        private const val KEY_RECTIFY = "recognition_rectify"
        private const val KEY_STRICT = "recognition_strict_lens"
        private const val KEY_VMMR = "recognition_vmmr"
        private const val KEY_STRICT_FORMAT = "recognition_strict_format"
        private const val KEY_REGION = "recognition_region"
        private const val KEY_DIRECT_PHYSICAL = "camera_direct_physical"
        private const val KEY_FOLLOW = "follow_enabled"
        private const val KEY_VOICE = "follow_voice"
        private const val KEY_TG_ENABLED = "telegram_enabled"
        private const val KEY_TG_TOKEN = "telegram_token"
        private const val KEY_TG_OWNER = "telegram_owner"
        private const val KEY_ALERT_LEVEL = "alert_min_level"
        private const val KEY_ALERT_ENCOUNTERS = "alert_after_encounters"

        const val DEFAULT_DWELL_SECONDS = 5
        const val MIN_DWELL_SECONDS = 1
        const val MAX_DWELL_SECONDS = 120

        fun load(context: Context, setup: RearCameraSetup?): ScanConfig {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val storedPlan = parsePlan(prefs.getString(KEY_PLAN, null))
            val validIds = setup?.steps?.map { it.id }?.toSet()
            val plan = storedPlan
                .filter { validIds == null || it.stepId in validIds }
                .ifEmpty { defaultPlan(setup) }

            return ScanConfig(
                plan = plan,
                model = enumOrDefault(prefs.getString(KEY_MODEL, null), DetectorModel.REACH),
                accelerator = enumOrDefault(prefs.getString(KEY_ACCEL, null), Accelerator.XNNPACK),
                detectorThreads = prefs.getInt(KEY_THREADS, defaultThreads()),
                confidence = prefs.getFloat(KEY_CONFIDENCE, 0.35f),
                minBoxPx = prefs.getInt(KEY_MIN_BOX, 96),
                // Plate pixels are the one thing nothing downstream can recover: the crop that
                // reaches the OCR engine is cut from this frame at native resolution, so a plate
                // that is 60 px wide at 1440p is 90 px at 2160p. Speed is bought elsewhere.
                resolution = enumOrDefault(
                    prefs.getString(KEY_RESOLUTION, null),
                    CaptureResolution.UHD,
                ),
                consensusMatches = prefs.getInt(KEY_CONSENSUS, 2),
                ocrIntervalMs = prefs.getInt(KEY_OCR_INTERVAL, 220),
                maxCropsPerFrame = prefs.getInt(KEY_MAX_CROPS, 3),
                minOcrScore = prefs.getInt(KEY_MIN_SCORE, 45),
                deepSearch = prefs.getBoolean(KEY_DEEP, true),
                // Off by default: it costs latency on every read, and whether it pays for itself
                // depends on how the phone ends up aimed through the rear glass.
                rectifyPlates = prefs.getBoolean(KEY_RECTIFY, false),
                strictLens = prefs.getBoolean(KEY_STRICT, true),
                vmmr = prefs.getBoolean(KEY_VMMR, true),
                // Off by default: a car that follows you may well be foreign, and the shape check
                // already removes the signs and stickers the engine reads as plates.
                strictPlateFormat = prefs.getBoolean(KEY_STRICT_FORMAT, false),
                plateRegion = enumOrDefault(prefs.getString(KEY_REGION, null), PlateRegion.LATVIA),
                allowDirectPhysical = prefs.getBoolean(KEY_DIRECT_PHYSICAL, false),
                followEnabled = prefs.getBoolean(KEY_FOLLOW, true),
                voiceAlerts = prefs.getBoolean(KEY_VOICE, true),
                telegramEnabled = prefs.getBoolean(KEY_TG_ENABLED, TelegramDefaults.ENABLED),
                telegramToken = prefs.getString(KEY_TG_TOKEN, "").orEmpty().trim()
                    .ifBlank { TelegramDefaults.BOT_TOKEN },
                telegramOwnerId = prefs.getLong(KEY_TG_OWNER, 0L)
                    .takeIf { it != 0L } ?: TelegramDefaults.OWNER_ID,
                // Default: shout from "suspect" upwards, or as soon as a car meets us twice.
                alertMinLevel = prefs.getInt(KEY_ALERT_LEVEL, 2),
                alertAfterEncounters = prefs.getInt(KEY_ALERT_ENCOUNTERS, 2),
            )
        }

        fun defaultPlan(setup: RearCameraSetup?): List<PlannedStep> {
            val ids = setup?.let(CameraCatalog::defaultEnabledStepIds).orEmpty()
            if (ids.isEmpty()) return listOf(PlannedStep("logical", DEFAULT_DWELL_SECONDS))
            return ids.map { PlannedStep(it, DEFAULT_DWELL_SECONDS) }
        }

        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

        private fun parsePlan(raw: String?): List<PlannedStep> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString(KEY_STEP_ID).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    PlannedStep(
                        stepId = id,
                        dwellSeconds = item.optInt(KEY_STEP_DWELL, DEFAULT_DWELL_SECONDS)
                            .coerceIn(MIN_DWELL_SECONDS, MAX_DWELL_SECONDS),
                    )
                }
            }.getOrDefault(emptyList())
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
            name?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: fallback
    }
}
