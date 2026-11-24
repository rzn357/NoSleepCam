package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import android.view.Surface

/** The settings a preview or still request is built from; a snapshot of what the user and the tap focus asked for. */
internal class RequestParams(
    /** Exposure compensation index (brightness); clamped to the camera's range. */
    val evIndex: Int,
    val torch: Boolean,
    /** The sensor area to show (zoom). */
    val crop: SensorRect,
    /** Tap focus holds the lens: the requests go in AF_MODE_AUTO (see [FocusRules]). */
    val afAutoMode: Boolean,
    val afRegion: SensorRect?,
    val aeRegion: SensorRect?,
)

/** Builds Camera2 requests. Knows nothing about the controller's state: everything comes in as arguments. */
internal object RequestFactory {
    private const val JPEG_QUALITY: Byte = 95

    /** A preview request with the given AF trigger (IDLE for the repeating one). */
    fun preview(
        camera: ActiveCamera,
        afTrigger: Int,
        params: RequestParams,
    ): CaptureRequest.Builder {
        val builder = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(camera.surface)
        applyCommonSettings(builder, camera.spec, params)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)

        // Without an explicit range the Honor 10 driver by default keeps the preview at ~25 fps (range [14,25]), although for
        // most sizes it declares 30; with an explicit [14,30] it gives 30 in good light. The upper bound is the one declared for this size.
        aeFpsRange(camera.spec, camera.resolution)?.let { (lower, upper) ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lower, upper))
        }

        return builder
    }

    /**
     * A full-size JPEG request with the same zoom, brightness, flash and focus as the preview.
     * After tap focus the lens is locked in AUTO mode — the photo keeps it too, and torch stays on for the photo as well.
     */
    fun still(
        camera: ActiveCamera,
        target: Surface,
        jpegOrientation: Int,
        params: RequestParams,
    ): CaptureRequest.Builder {
        val builder = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(target)
        applyCommonSettings(builder, camera.spec, params)
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        return builder
    }

    /**
     * A request for a high-speed session. Deliberately minimal: only auto modes, the frame rate range from the camera's list, and zoom.
     * We don't add flash, focus regions and triggers here — such a session does not accept everything, and the Honor driver
     * has already hung on invalid parameters.
     */
    fun highSpeed(
        camera: ActiveCamera,
        config: HighSpeedConfig,
        crop: SensorRect,
    ): CaptureRequest {
        val builder = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(camera.surface)

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AF_MODE, camera.spec.highSpeedAfMode)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.lower, config.upper))
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop.toRect())
        return builder.build()
    }

    /** Target frame rate range for the chosen resolution (see [CameraSpecRules.aeFpsRange]). */
    fun aeFpsRange(
        spec: CameraSpec,
        resolution: CameraResolution?,
    ) = resolution?.let { CameraSpecRules.aeFpsRange(spec.aeFpsRanges, spec.frameRates[it]) }

    /** Settings shared by the preview and the still-photo requests: exposure, focus mode, flash, zoom and tap regions. */
    private fun applyCommonSettings(
        builder: CaptureRequest.Builder,
        spec: CameraSpec,
        params: RequestParams,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (spec.evMax > spec.evMin) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, params.evIndex.coerceIn(spec.evMin, spec.evMax))
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, FocusRules.afModeFor(params.afAutoMode, spec.afMode))

        // Torch keeps the flash on in every frame (including during focusing).
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (params.torch && spec.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )

        builder.set(CaptureRequest.SCALER_CROP_REGION, params.crop.toRect())
        applyTapRegions(builder, spec, params)
    }

    /**
     * Tap focus/metering regions go into a request only when they are inside the visible area:
     * a region outside it is ignored by the camera.
     */
    private fun applyTapRegions(
        builder: CaptureRequest.Builder,
        spec: CameraSpec,
        params: RequestParams,
    ) {
        params.afRegion
            ?.takeIf { spec.maxAfRegions > 0 && params.crop.containsCenterOf(it) }
            ?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it.toMetering())) }
        params.aeRegion
            ?.takeIf { spec.maxAeRegions > 0 && params.crop.containsCenterOf(it) }
            ?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it.toMetering())) }
    }

    private fun SensorRect.toMetering() = MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)
}
