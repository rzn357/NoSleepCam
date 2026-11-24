package com.exciteddev.nosleepcam.ui

import kotlin.math.abs

/**
 * Touch zones of the zoom slider in the collapsed state. Measured on the Huawei camera (Honor 10, 3 px = 1 dp):
 * only a touch ON the ring opens the scale, only a touch on the end dot of the dotted tail switches the zoom in a jump (1x ↔ 2x),
 * and any other touch is a regular touch on the frame, i.e. focus. Previously the slider caught touches along its whole strip
 * (76 dp × the full scale height), and focus near it could not be triggered.
 *
 * All values are in dp, relative to the centre of the ring (or of the end dot).
 */
internal object ZoomZones {

    /** Half-width of both zones: 100 px (33 dp) still triggers, 110 px (37 dp) is already focus. */
    const val HALF_WIDTH = 35f

    /** Half-height of the ring zone ≈ ring radius: ±13 dp triggers, ±20 dp no longer does. */
    const val RING_HALF_HEIGHT = 16.5f

    /** End dot zone: 20 dp away from the ring still triggers, 10 dp toward the ring still triggers, 20 dp toward the ring no longer does. */
    const val PRESET_AWAY = 22f
    const val PRESET_TOWARD = 14f

    /** A touch at [dx], [dy] from the ring centre lands on the ring. */
    fun hitsRing(dx: Float, dy: Float): Boolean = abs(dx) <= HALF_WIDTH && abs(dy) <= RING_HALF_HEIGHT

    /**
     * A touch at [dx], [dy] from the centre of the end dot of the tail. [direction] — where the tail is relative to the ring:
     * −1 — up (zoom below 2x), +1 — down.
     */
    fun hitsPreset(dx: Float, dy: Float, direction: Float): Boolean {
        if (abs(dx) > HALF_WIDTH) return false

        val away = dy * direction // positive — farther from the ring
        return away <= PRESET_AWAY && away >= -PRESET_TOWARD
    }
}
