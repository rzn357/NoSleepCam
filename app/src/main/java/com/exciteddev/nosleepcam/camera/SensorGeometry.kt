package com.exciteddev.nosleepcam.camera

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * An integer rectangle in sensor coordinates. A plain value type (unlike [Rect], which is a stub in JVM unit tests),
 * so that all the geometry below can be tested without a device.
 */
internal data class SensorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Whether the centre of [other] lies inside this rectangle (the right and bottom edges are exclusive). */
    fun containsCenterOf(other: SensorRect): Boolean {
        val cx = (other.left + other.right) / 2
        val cy = (other.top + other.bottom) / 2
        return cx >= left && cx < right && cy >= top && cy < bottom
    }

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

internal fun Rect.toSensorRect() = SensorRect(left, top, right, bottom)

/** Pure sensor-space geometry: zoom crop, visible area, tap-to-focus regions and zoom verification. */
internal object SensorGeometry {
    /** The sensor area that must be shown at the given [zoom] (centred, even-sized as cameras require). */
    fun cropForZoom(
        active: SensorRect,
        zoom: Float,
    ): SensorRect {
        val cropW = (active.width / zoom).toInt() and 1.inv()
        val cropH = (active.height / zoom).toInt() and 1.inv()
        val left = active.left + (active.width - cropW) / 2
        val top = active.top + (active.height - cropH) / 2
        return SensorRect(left, top, left + cropW, top + cropH)
    }

    /**
     * The actually visible part of the sensor: the camera additionally crops the zoom area to the aspect ratio
     * of the stream (e.g. 16:9 from a 4:3 sensor). Needed to map a touch to sensor coordinates accurately.
     */
    fun visibleRect(
        crop: SensorRect,
        streamWidth: Int,
        streamHeight: Int,
    ): SensorRect {
        val streamAspect = streamWidth.toDouble() / streamHeight
        val cropAspect = crop.width.toDouble() / crop.height

        if (abs(streamAspect - cropAspect) < ASPECT_EPSILON) return crop

        if (cropAspect > streamAspect) {
            val w = (crop.height * streamAspect).roundToInt()
            val dx = (crop.width - w) / 2
            return SensorRect(crop.left + dx, crop.top, crop.left + dx + w, crop.bottom)
        }

        val h = (crop.width / streamAspect).roundToInt()
        val dy = (crop.height - h) / 2
        return SensorRect(crop.left, crop.top + dy, crop.right, crop.top + dy + h)
    }

    /**
     * Rotates a point of the "natural" image (as on a phone held in portrait, both coordinates in 0..1)
     * back into the sensor buffer, whose orientation is [sensorOrientation] degrees.
     */
    fun naturalToSensor(
        s: Double,
        t: Double,
        sensorOrientation: Int,
    ): Pair<Double, Double> =
        when (sensorOrientation) {
            90 -> t to 1 - s
            180 -> 1 - s to 1 - t
            270 -> 1 - t to s
            else -> s to t
        }

    /**
     * The square focus/metering region for a tap at ([s], [t]) of the natural image: about 18% of the visible area,
     * at least [MIN_REGION_SIDE] px, kept inside the [active] array.
     */
    fun focusRegion(
        s: Float,
        t: Float,
        sensorOrientation: Int,
        visible: SensorRect,
        active: SensorRect,
    ): SensorRect {
        val (sx, sy) = naturalToSensor(s.toDouble(), t.toDouble(), sensorOrientation)

        val px = visible.left + (sx * visible.width).roundToInt()
        val py = visible.top + (sy * visible.height).roundToInt()
        val wanted = maxOf(MIN_REGION_SIDE, (minOf(visible.width, visible.height) * REGION_FRACTION).toInt())
        val side = minOf(wanted, minOf(active.width, active.height))

        val left = (px - side / 2).coerceIn(active.left, active.right - side)
        val top = (py - side / 2).coerceIn(active.top, active.bottom - side)
        return SensorRect(left, top, left + side, top + side)
    }

    /**
     * Whether the camera silently applied a wider crop than the one requested (it cannot really zoom that far).
     * The tolerance is 3%: cameras round the area slightly; a narrower area is just fitting to the stream's aspect ratio.
     */
    fun isCropClamped(
        askedWidth: Int,
        appliedWidth: Int,
    ): Boolean = askedWidth > 0 && appliedWidth > 0 && appliedWidth > askedWidth * CLAMP_TOLERANCE

    /** The zoom the camera really achieved, given the [appliedWidth] of the crop it used; never below [declaredMax]. */
    fun achievedZoom(
        activeWidth: Int,
        appliedWidth: Int,
        declaredMax: Float,
    ): Float {
        val achieved = activeWidth / appliedWidth.toFloat()
        return maxOf(declaredMax, floor(achieved * 10f + 0.01f) / 10f)
    }

    private const val ASPECT_EPSILON = 1e-3
    private const val MIN_REGION_SIDE = 32
    private const val REGION_FRACTION = 0.18
    private const val CLAMP_TOLERANCE = 1.03f
}
