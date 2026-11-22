package com.example.nosleepcam.ui

import kotlin.math.abs

/**
 * Зони дотику повзунка зуму у згорнутому стані. Виміряно на камері Huawei (Honor 10, 3 px = 1 dp):
 * лише дотик НА кільці відкриває шкалу, лише дотик на кінцевій крапці пунктиру стрибком змінює зум (1x ↔ 2x),
 * а будь-який інший — це звичайний дотик по кадру, тобто фокус. Раніше повзунок ловив дотики по всій своїй смузі
 * (76 dp × уся висота шкали), і фокус біля нього неможливо було запустити.
 *
 * Усі значення в dp, відносно центра кільця (або кінцевої крапки).
 */
internal object ZoomZones {

    /** Півширина обох зон: 100 px (33 dp) ще вмикає, 110 px (37 dp) — уже фокус. */
    const val HALF_WIDTH = 35f

    /** Піввисота зони кільця ≈ радіус кільця: ±13 dp вмикає, ±20 dp — уже ні. */
    const val RING_HALF_HEIGHT = 16.5f

    /** Зона кінцевої крапки: 20 dp у бік «від кільця» вмикає, 10 dp «до кільця» вмикає, 20 dp — уже ні. */
    const val PRESET_AWAY = 22f
    const val PRESET_TOWARD = 14f

    /** Дотик [dx], [dy] від центру кільця потрапляє на кільце. */
    fun hitsRing(dx: Float, dy: Float): Boolean = abs(dx) <= HALF_WIDTH && abs(dy) <= RING_HALF_HEIGHT

    /**
     * Дотик [dx], [dy] від центру кінцевої крапки пунктиру. [direction] — де пунктир відносно кільця:
     * −1 — угору (зум менший за 2x), +1 — униз.
     */
    fun hitsPreset(dx: Float, dy: Float, direction: Float): Boolean {
        if (abs(dx) > HALF_WIDTH) return false

        val away = dy * direction // додатне — далі від кільця
        return away <= PRESET_AWAY && away >= -PRESET_TOWARD
    }
}
