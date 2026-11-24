package com.exciteddev.nosleepcam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Size
import com.exciteddev.nosleepcam.LOG_TAG
import com.exciteddev.nosleepcam.StartupTrace

/** Reads a [CameraSpec] from the system's `CameraCharacteristics`. Every read is a system call, so callers cache the result. */
internal class CameraSpecReader(
    private val manager: CameraManager,
) {
    fun read(id: String): CameraSpec {
        val ch = manager.getCameraCharacteristics(id)

        val lens = lensFacing(ch.getOr(CameraCharacteristics.LENS_FACING, -1))
        val declaredMaxZoom = maxOf(1f, ch.getOr(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1f))
        val active = ch.getOr(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT))
        val capabilities = ch.getOr(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, IntArray(0))
        val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val afModes = ch.getOr(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, IntArray(0))
        val afMode = CameraSpecRules.pickAfMode(afModes)

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)
        val aeRanges = readAeFpsRanges(ch)

        val spec =
            CameraSpec(
                id = id,
                lens = lens,
                sensorOrientation = ch.getOr(CameraCharacteristics.SENSOR_ORIENTATION, DEFAULT_SENSOR_ORIENTATION),
                activeArray = active.toSensorRect(),
                declaredMaxZoom = declaredMaxZoom,
                maxZoom = CameraSpecRules.offeredMaxZoom(declaredMaxZoom, isHuaweiFamily && lens == LensFacing.Back),
                afMode = afMode,
                hasAfAuto = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO),
                maxAfRegions = ch.getOr(CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 0),
                maxAeRegions = ch.getOr(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, 0),
                hasFlash = ch.getOr(CameraCharacteristics.FLASH_INFO_AVAILABLE, false),
                evMin = evRange?.lower ?: 0,
                evMax = evRange?.upper ?: 0,
                evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
                backwardCompatible =
                    capabilities.isEmpty() ||
                        capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
                resolutions = CameraSpecRules.usableResolutions(previewSizes.toResolutions()),
                jpegSizes =
                    map
                        ?.getOutputSizes(ImageFormat.JPEG)
                        .toResolutions()
                        .distinct()
                        .sortedByDescending { it.pixels },
                frameRates = readFrameRates(map, previewSizes, aeRanges.maxOfOrNull { it.second } ?: 0),
                aeFpsRanges = aeRanges,
                highSpeedConfigs = readHighSpeedConfigs(capabilities, map),
                highSpeedAfMode = CameraSpecRules.highSpeedAfMode(afModes, afMode),
            )

        StartupTrace.req {
            val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList()
            "spec $id: maxAfRegions=${spec.maxAfRegions} maxAeRegions=${spec.maxAeRegions} afModes=${afModes.toList()} " +
                "aeModes=$aeModes aeComp=$evRange hasFlash=${spec.hasFlash} activeArray=$active"
        }
        return spec
    }

    private fun <T> CameraCharacteristics.getOr(
        key: CameraCharacteristics.Key<T>,
        default: T,
    ): T = get(key) ?: default

    private fun lensFacing(facing: Int) =
        when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> LensFacing.Front
            CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.External
            else -> LensFacing.Back
        }

    private fun Array<Size>?.toResolutions(): List<CameraResolution> = orEmpty().map { CameraResolution(it.width, it.height) }

    private fun readFrameRates(
        map: StreamConfigurationMap?,
        sizes: Array<Size>?,
        maxAeFps: Int,
    ): Map<CameraResolution, Double> {
        val rates = HashMap<CameraResolution, Double>()
        if (map == null || sizes == null) return rates

        for (size in sizes) {
            try {
                val frameNs = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                CameraSpecRules.declaredFrameRate(frameNs, maxAeFps)?.let { rates[CameraResolution(size.width, size.height)] = it }
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "Frame duration ${size.width}x${size.height}: ${ex.message}")
            }
        }
        return rates
    }

    /**
     * High-speed modes of the camera: sizes with a rate above 30 fps (e.g. 1920×1080 and 1280×720 up to 120), largest first.
     * Empty if the camera does not declare this mode.
     */
    private fun readHighSpeedConfigs(
        capabilities: IntArray,
        map: StreamConfigurationMap?,
    ): List<HighSpeedConfig> {
        if (map == null || !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
            return emptyList()
        }

        return try {
            map.highSpeedVideoSizes
                .orEmpty()
                .mapNotNull { size ->
                    val ranges = map.getHighSpeedVideoFpsRangesFor(size).orEmpty().map { it.lower to it.upper }
                    val best = CameraSpecRules.bestHighSpeedRange(ranges)
                    if (CameraSpecRules.isHighSpeed(best)) {
                        HighSpeedConfig(CameraResolution(size.width, size.height), best.first, best.second)
                    } else {
                        null
                    }
                }.sortedByDescending { it.size.pixels }
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "High-speed configs: ${ex.message}")
            emptyList()
        }
    }

    /** Target frame rate ranges allowed by the camera for auto-exposure (empty — unknown). */
    private fun readAeFpsRanges(ch: CameraCharacteristics): List<Pair<Int, Int>> =
        try {
            ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper } ?: emptyList()
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "AE fps ranges: ${ex.message}")
            emptyList()
        }

    private companion object {
        const val DEFAULT_SENSOR_WIDTH = 4000
        const val DEFAULT_SENSOR_HEIGHT = 3000
        const val DEFAULT_SENSOR_ORIENTATION = 90

        val isHuaweiFamily: Boolean
            get() =
                Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
                    Build.BRAND.equals("HONOR", ignoreCase = true) ||
                    Build.MANUFACTURER.equals("HONOR", ignoreCase = true)
    }
}
