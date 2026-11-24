package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.round

/** Pure decisions made from a camera's reported characteristics (no Android objects, so they can be unit-tested). */
internal object CameraSpecRules {
    /** Maximum zoom we offer on the Huawei/Honor back camera; see [offeredMaxZoom]. */
    const val HUAWEI_MAX_ZOOM = 10f

    /** The largest preview size picked by default: about 2 MP keeps the preview smooth and the start fast. */
    const val DEFAULT_PREVIEW_PIXELS = 1920L * 1080L

    private const val MIN_USABLE_WIDTH = 320
    private const val MIN_USABLE_HEIGHT = 240
    private const val FULL_FOV_ASPECT_TOLERANCE = 0.02
    private const val MIN_HIGH_SPEED_FPS = 60

    /** The best autofocus mode among the [available] ones (`CONTROL_AF_AVAILABLE_MODES`). */
    fun pickAfMode(available: IntArray): Int =
        when {
            available.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            available.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            available.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }

    /** High-speed sessions prefer continuous *video* autofocus when the camera has it. */
    fun highSpeedAfMode(
        available: IntArray,
        defaultMode: Int,
    ): Int =
        if (available.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            defaultMode
        }

    /**
     * The sizes worth offering: distinct, largest first. Very tiny sizes (QCIF etc.) are of no use to anyone,
     * so they are dropped — unless nothing else is left.
     */
    fun usableResolutions(sizes: List<CameraResolution>): List<CameraResolution> {
        val sorted = sizes.distinct().sortedByDescending { it.pixels }
        val usable = sorted.filter { it.width >= MIN_USABLE_WIDTH && it.height >= MIN_USABLE_HEIGHT }
        return usable.ifEmpty { sorted }
    }

    /**
     * Maximum zoom offered to the user. Standard Camera2 declares only 6x on the Huawei/Honor back camera, while the
     * manufacturer's own camera app has 10x (`opticalMaxZoomValue = 10`). Verified on the Honor 10: the driver applies a crop
     * region up to 10x and further (up to ~14x), but at ~16x it HANGS (the camera thread blocks for tens of seconds), so we
     * never offer more than 10x. For other manufacturers the declared value is kept: how their drivers behave beyond it is
     * unknown, and a camera hang is worse than the lack of zoom.
     */
    fun offeredMaxZoom(
        declaredMaxZoom: Float,
        isHuaweiBackCamera: Boolean,
    ): Float = maxOf(declaredMaxZoom, if (isHuaweiBackCamera) HUAWEI_MAX_ZOOM else 0f)

    /**
     * Highest frame rate of a preview size: 1 / its minimum frame duration, capped by the upper bound of the auto-exposure
     * ranges (the preview will not go higher anyway). Null when the camera reports nothing useful.
     */
    fun declaredFrameRate(
        minFrameDurationNs: Long,
        maxAeFps: Int,
    ): Double? {
        if (minFrameDurationNs <= 0) return null

        var fps = 1e9 / minFrameDurationNs
        if (maxAeFps > 0) fps = minOf(fps, maxAeFps.toDouble())
        return if (fps >= 1) round(fps) else null
    }

    /**
     * For one high-speed size: the range with the highest upper bound and, among those, the lowest lower bound
     * ([30,120] — in the dark the camera can lower the rate instead of being left without exposure).
     * (0, 0) when there are no ranges.
     */
    fun bestHighSpeedRange(ranges: List<Pair<Int, Int>>): Pair<Int, Int> {
        var lower = 0
        var upper = 0
        for ((l, u) in ranges) {
            if (u > upper || (u == upper && l < lower)) {
                lower = l
                upper = u
            }
        }
        return lower to upper
    }

    /** Whether a frame rate range is a real high-speed mode (60 fps and above). */
    fun isHighSpeed(range: Pair<Int, Int>): Boolean = range.second >= MIN_HIGH_SPEED_FPS

    /**
     * Picks the preview size: the [wanted] one if the camera offers it, otherwise a balanced default —
     * the largest size up to [DEFAULT_PREVIEW_PIXELS] that shows the sensor's full field of view.
     *
     * @param candidates the sizes still allowed, largest first
     */
    fun pickResolution(
        candidates: List<CameraResolution>,
        wanted: CameraResolution?,
        sensorAspect: Double,
    ): CameraResolution? {
        if (candidates.isEmpty()) return null
        if (wanted != null && wanted in candidates) return wanted

        val balanced = candidates.filter { it.pixels <= DEFAULT_PREVIEW_PIXELS }
        if (balanced.isEmpty()) return candidates.last() // even the smallest is too big — take the smallest

        val fullFov = balanced.filter { abs(it.width.toDouble() / it.height - sensorAspect) < FULL_FOV_ASPECT_TOLERANCE }
        return fullFov.ifEmpty { balanced }.maxByOrNull { it.pixels }
    }

    /**
     * Target frame rate range for a size whose declared rate is [declaredRate]: the highest upper bound not greater
     * than it, and the lowest lower bound among those (in the dark the camera can still lengthen the exposure).
     * Null when the camera reports no ranges or none fits.
     */
    fun aeFpsRange(
        ranges: List<Pair<Int, Int>>,
        declaredRate: Double?,
    ): Pair<Int, Int>? {
        if (declaredRate == null || ranges.isEmpty()) return null

        val candidates = ranges.filter { it.second <= declaredRate.toInt() }
        if (candidates.isEmpty()) return null

        val upper = candidates.maxOf { it.second }
        return candidates.filter { it.second == upper }.minByOrNull { it.first }
    }
}
