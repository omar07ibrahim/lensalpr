package com.lensalpr.app.camera

import android.os.Build

data class CameraHardwareIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
) {
    companion object {
        fun fromBuild(): CameraHardwareIdentity = CameraHardwareIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
        )

        val displayName: String
            get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}

enum class LensRoute {
    /**
     * Bind the rear logical camera and drive the module with `setZoomRatio`. The HAL performs the
     * physical switch and [LensVerifier] proves which module answered.
     */
    LOGICAL_MULTI_CAMERA,

    /**
     * Bind the physical camera id directly through Camera2Interop. Removes any doubt about routing
     * but several Samsung firmwares terminate the CameraDevice when a physical output is requested
     * next to a second surface, so it stays opt-in.
     */
    DIRECT_PHYSICAL_OUTPUT,
}

/**
 * Chooses how a [ZoomStep] is applied to the camera session.
 *
 * The default is the logical route for every device: it works on Samsung, keeps Preview and
 * ImageAnalysis on the same stream configuration, and is still fully verifiable through capture
 * metadata. Direct physical binding is available as an explicit experiment.
 */
object PhysicalLensRoutingPolicy {

    private const val SAMSUNG = "samsung"

    fun route(
        hardware: CameraHardwareIdentity,
        step: ZoomStep,
        allowDirectPhysical: Boolean,
        outputSurfaceCount: Int,
    ): LensRoute {
        if (step.module == null) return LensRoute.LOGICAL_MULTI_CAMERA
        if (!allowDirectPhysical) return LensRoute.LOGICAL_MULTI_CAMERA
        // A physical output next to a second surface is the configuration that breaks Samsung's
        // camera stack; only a single-surface session may take the direct route there.
        if (hardware.manufacturer.equals(SAMSUNG, ignoreCase = true) && outputSurfaceCount > 1) {
            return LensRoute.LOGICAL_MULTI_CAMERA
        }
        return LensRoute.DIRECT_PHYSICAL_OUTPUT
    }

    /**
     * Zoom to hand to `CameraControl.setZoomRatio` for the selected route.
     *
     * On the logical route the ratio is expressed in the logical camera space (1.0 / 2.74 / 5.06 /
     * 10.0). Once a physical camera is bound directly its own coordinate system starts at 1.0, so
     * only the extra crop of a digital step is requested.
     */
    fun controlZoomRatio(route: LensRoute, step: ZoomStep): Float = when (route) {
        LensRoute.LOGICAL_MULTI_CAMERA -> step.requestedZoom
        LensRoute.DIRECT_PHYSICAL_OUTPUT -> step.cropFactor
    }.coerceAtLeast(0.1f)
}
