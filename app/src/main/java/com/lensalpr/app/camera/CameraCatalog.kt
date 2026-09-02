package com.lensalpr.app.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A real optical module that sits behind the rear logical camera. */
data class LensModule(
    val physicalId: String,
    /** Immutable optical property of the module; the strongest proof a lens is really active. */
    val focalLengthMm: Float,
    /** 35 mm equivalent, used to derive the relative optical zoom. */
    val equivalentFocalLengthMm: Float,
    /** Optical zoom relative to the main (wide) module of the same device. */
    val opticalZoom: Float,
    val activeArray: Rect?,
) {
    val shortLabel: String get() = formatZoom(opticalZoom)
}

enum class StepKind {
    /** Backed by a dedicated optical module. */
    OPTICAL,

    /** A bounded crop applied on top of the longest optical module. */
    DIGITAL,
}

/**
 * One entry of the rotation plan.
 *
 * [requestedZoom] is expressed in the logical rear-camera coordinate system: that is what the
 * vendor HAL uses to decide which physical module to activate, and what [LensVerifier] then
 * checks against the capture metadata.
 */
data class ZoomStep(
    val id: String,
    val label: String,
    val kind: StepKind,
    val requestedZoom: Float,
    val module: LensModule?,
    val baseLabel: String? = null,
    /** Crop factor applied inside the module when the camera is bound as a physical output. */
    val cropFactor: Float = 1f,
) {
    val expectedPhysicalId: String? get() = module?.physicalId
    val expectedFocalMm: Float? get() = module?.focalLengthMm
}

data class RearCameraSetup(
    val logicalId: String,
    val modules: List<LensModule>,
    val steps: List<ZoomStep>,
    val minZoom: Float,
    val maxZoom: Float,
) {
    fun step(id: String): ZoomStep? = steps.firstOrNull { it.id == id }
}

/**
 * Reads the rear logical camera and turns its physical children into an explicit list of zoom
 * steps.
 *
 * Roles are derived from the hardware (focal length + sensor size), never from hard-coded ids, so
 * the same code produces 1x/3x/5x on a Galaxy S25 Ultra and a sane subset elsewhere.
 */
object CameraCatalog {

    /** Digital steps offered on top of the longest optical module. */
    val DIGITAL_FACTORS = listOf(6f, 7f, 8f, 9f, 10f)

    private const val MAIN_EQUIVALENT_TARGET_MM = 24f
    private const val ULTRA_WIDE_MAX_ZOOM = 0.95f

    fun discover(context: Context): RearCameraSetup? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val ids = runCatching { manager.cameraIdList.toList() }.getOrNull() ?: return null
        val characteristics = ids.mapNotNull { id ->
            runCatching { id to manager.getCameraCharacteristics(id) }.getOrNull()
        }.toMap()
        if (characteristics.isEmpty()) return null

        val physicalChildren = characteristics.values.flatMap { it.physicalCameraIds }.toSet()

        val logical = characteristics.entries
            .filter { (id, chars) ->
                id !in physicalChildren &&
                    chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            // The camera exposing the most physical children is the multi-lens rear camera.
            .maxByOrNull { it.value.physicalCameraIds.size }
            ?: return null

        val logicalId = logical.key
        val logicalChars = logical.value

        val childIds = logicalChars.physicalCameraIds.toList()
        val specs = childIds.mapNotNull { physicalId ->
            val chars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                ?: return@mapNotNull null
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull() ?: return@mapNotNull null
            val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
            val equivalent = if (sensorWidth != null && sensorWidth > 0f) {
                focal * 36f / sensorWidth
            } else {
                // Without the sensor size the raw focal length still orders the modules.
                focal
            }
            Triple(
                physicalId,
                focal,
                equivalent,
            ) to chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }

        if (specs.isEmpty()) {
            return RearCameraSetup(
                logicalId = logicalId,
                modules = emptyList(),
                steps = listOf(logicalOnlyStep()),
                minZoom = 1f,
                maxZoom = maxZoomOf(logicalChars),
            )
        }

        val mainEquivalent = specs
            .map { it.first.third }
            .minByOrNull { abs(it - MAIN_EQUIVALENT_TARGET_MM) }
            ?: specs.first().first.third

        val modules = specs
            .map { (spec, activeArray) ->
                val (physicalId, focal, equivalent) = spec
                LensModule(
                    physicalId = physicalId,
                    focalLengthMm = focal,
                    equivalentFocalLengthMm = equivalent,
                    opticalZoom = if (mainEquivalent > 0f) equivalent / mainEquivalent else 1f,
                    activeArray = activeArray,
                )
            }
            .sortedBy { it.opticalZoom }

        val maxZoom = maxZoomOf(logicalChars)
        val minZoom = minZoomOf(logicalChars)

        val opticalSteps = modules.map { module ->
            ZoomStep(
                id = "opt_${module.physicalId}",
                label = module.shortLabel,
                kind = StepKind.OPTICAL,
                requestedZoom = snapToSwitchPoint(module.opticalZoom).coerceIn(minZoom, maxZoom),
                module = module,
            )
        }

        val telephoto = modules.maxByOrNull { it.opticalZoom }
        val digitalSteps = if (telephoto != null && telephoto.opticalZoom > ULTRA_WIDE_MAX_ZOOM) {
            DIGITAL_FACTORS
                .filter { it > telephoto.opticalZoom + 0.25f && it <= maxZoom }
                .map { factor ->
                    ZoomStep(
                        id = "dig_${factor.roundToInt()}",
                        label = formatZoom(factor),
                        kind = StepKind.DIGITAL,
                        requestedZoom = factor,
                        module = telephoto,
                        baseLabel = telephoto.shortLabel,
                        cropFactor = factor / telephoto.opticalZoom,
                    )
                }
        } else {
            emptyList()
        }

        return RearCameraSetup(
            logicalId = logicalId,
            modules = modules,
            steps = opticalSteps + digitalSteps,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }

    /** Steps that make sense for plate reading by default: 1x and every tele module. */
    fun defaultEnabledStepIds(setup: RearCameraSetup): List<String> {
        val optical = setup.steps.filter {
            it.kind == StepKind.OPTICAL && (it.module?.opticalZoom ?: 1f) >= ULTRA_WIDE_MAX_ZOOM
        }
        return optical.map { it.id }
    }

    private fun logicalOnlyStep(): ZoomStep = ZoomStep(
        id = "logical",
        label = "1×",
        kind = StepKind.OPTICAL,
        requestedZoom = 1f,
        module = null,
    )

    private fun maxZoomOf(chars: CameraCharacteristics): Float {
        val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val fromRange = range?.upper
        val fromDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        return (fromRange ?: fromDigital ?: 10f).coerceAtLeast(1f)
    }

    private fun minZoomOf(chars: CameraCharacteristics): Float {
        val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        return (range?.lower ?: 1f).coerceAtMost(1f)
    }
}

/** Snaps marketing-style zoom values so 2.74x reads as "3x" without hiding the real ratio. */
/**
 * Rounds a derived zoom ratio up to the ratio the camera actually switches lenses at.
 *
 * The optical ratio computed from focal lengths lands a little under the marketing figure — the
 * S25 Ultra's telephoto works out at about 2.8x and the periscope at 4.6x. The HAL, however,
 * switches physical modules at 3.0 and 5.0, so requesting the derived value can leave the frame
 * cropped from the previous, wider lens: exactly the case the lens verifier exists to catch.
 * Snapping up costs a few percent of field of view and buys the module that was asked for.
 */
fun snapToSwitchPoint(value: Float): Float {
    val canonical = SWITCH_POINTS.firstOrNull { it >= value && (it - value) / it <= SNAP_TOLERANCE }
    return canonical ?: value
}

private val SWITCH_POINTS = floatArrayOf(0.5f, 1f, 2f, 3f, 5f, 10f)
private const val SNAP_TOLERANCE = 0.15f

fun formatZoom(value: Float): String {
    val canonical = floatArrayOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f).minByOrNull { abs(it - value) }
    val display = canonical?.takeIf { abs(value - it) / it <= 0.15f } ?: value
    val rounded = display.roundToInt()
    return if (abs(display - rounded) < 0.08f) {
        "$rounded×"
    } else {
        String.format(Locale.US, "%.1f×", display)
    }
}
