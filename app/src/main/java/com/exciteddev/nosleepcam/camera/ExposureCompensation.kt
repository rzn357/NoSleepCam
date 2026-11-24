package com.exciteddev.nosleepcam.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Brightness (exposure compensation): converts the "sun" position to a Camera2 index and a value label.
 * The camera defines an index range (e.g. −4…+4) and an EV step (e.g. 1/2), so the Honor 10 gives ±2 EV in 0.5 steps.
 */
internal object ExposureCompensation {
    /** The minus sign as in the Huawei camera (U+2212), not a hyphen. */
    private const val MINUS = '\u2212'

    /** [fraction] ∈ [−1, 1] (positive — brighter) → index in [evMin, evMax]. The range may be asymmetric. */
    fun indexFor(
        fraction: Float,
        evMin: Int,
        evMax: Int,
    ): Int {
        val f = fraction.coerceIn(-1f, 1f)
        val scaled = if (f >= 0f) f * evMax else f * -evMin
        return abs(scaled).roundToInt() * (if (scaled < 0f) -1 else 1)
    }

    /** Inverse of [indexFor]: index → position in [−1, 1]. */
    fun fractionFor(
        index: Int,
        evMin: Int,
        evMax: Int,
    ): Float =
        when {
            index > 0 && evMax > 0 -> index.coerceAtMost(evMax) / evMax.toFloat()
            index < 0 && evMin < 0 -> -(index.coerceAtLeast(evMin) / evMin.toFloat())
            else -> 0f
        }

    /** "+1.5", "−0.5" or "0" (comma or period — depending on the device language). */
    fun format(
        index: Int,
        step: Float,
    ): String {
        if (index == 0) return "0"
        return (if (index > 0) "+" else MINUS.toString()) + formatNumber(abs(index * step).toDouble(), "0.0")
    }
}
