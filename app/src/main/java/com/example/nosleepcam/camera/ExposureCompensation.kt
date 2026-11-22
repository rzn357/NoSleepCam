package com.example.nosleepcam.camera

import kotlin.math.abs

/**
 * Яскравість (експокомпенсація): перетворення положення «сонечка» на індекс Camera2 і підпис значення.
 * Камера задає діапазон індексів (напр. −4…+4) і крок у EV (напр. 1/2), тож Honor 10 дає ±2 EV із кроком 0,5.
 */
internal object ExposureCompensation {

    /** Знак «мінус» як у камері Huawei (U+2212), а не дефіс. */
    private const val MINUS = '\u2212'

    /** [fraction] ∈ [−1, 1] (додатне — яскравіше) → індекс у [evMin, evMax]. Діапазон може бути несиметричним. */
    fun indexFor(fraction: Float, evMin: Int, evMax: Int): Int {
        val f = fraction.coerceIn(-1f, 1f)
        val scaled = if (f >= 0f) f * evMax else f * -evMin
        return Math.round(abs(scaled)) * (if (scaled < 0f) -1 else 1)
    }

    /** Обернене до [indexFor]: індекс → положення в [−1, 1]. */
    fun fractionFor(index: Int, evMin: Int, evMax: Int): Float = when {
        index > 0 && evMax > 0 -> index.coerceAtMost(evMax) / evMax.toFloat()
        index < 0 && evMin < 0 -> -(index.coerceAtLeast(evMin) / evMin.toFloat())
        else -> 0f
    }

    /** «+1,5», «−0,5» або «0» (кома чи крапка — за мовою пристрою). */
    fun format(index: Int, step: Float): String {
        if (index == 0) return "0"
        return (if (index > 0) "+" else MINUS.toString()) + formatNumber(abs(index * step).toDouble(), "0.0")
    }
}
