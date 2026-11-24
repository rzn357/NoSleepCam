package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import com.exciteddev.nosleepcam.StartupTrace
import kotlin.math.roundToInt

/**
 * The actual frame rate (shown next to the flash icon): counts frames by sensor timestamps (more accurate than by callback arrival
 * time) in a ≈1 s window and notifies the UI when the integer value changes. Camera thread only.
 */
internal class FrameRateReporter(
    private val events: CameraEvents,
) {
    private val meter = FrameRateMeter()

    /** The last value passed to the UI; 0 — the camera is not running. */
    var reported = 0
        private set

    fun onFrame(
        result: TotalCaptureResult,
        spec: CameraSpec?,
        resolution: CameraResolution?,
    ) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val fps = meter.onFrame(timestamp) ?: return
        trace(fps, result, spec, resolution)
        publish(fps.roundToInt())
    }

    /** The camera was closed or reconfigured — the old value is no longer reliable. */
    fun reset() {
        meter.reset()
        publish(0)
    }

    private fun publish(fps: Int) {
        if (fps == reported) return

        reported = fps
        events.emit { it.onFrameRate(fps) }
    }

    private fun trace(
        fps: Double,
        result: TotalCaptureResult,
        spec: CameraSpec?,
        resolution: CameraResolution?,
    ) = StartupTrace.fps {
        val key = resolution?.key ?: "?"
        val declared = spec?.frameRates?.get(resolution) ?: 0.0
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val frameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        val aeRange = spec?.let { RequestFactory.aeFpsRange(it, resolution) }
        "$key: measured ${"%.1f".format(fps)} fps, declared ${"%.0f".format(declared)}, " +
            "AE range ${aeRange?.let { "[${it.first},${it.second}]" } ?: "not set"}; " +
            "exposure ${"%.1f".format(exposureNs / 1e6)} ms, frame duration ${"%.1f".format(frameNs / 1e6)} ms"
    }
}
