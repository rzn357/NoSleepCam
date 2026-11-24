package com.exciteddev.nosleepcam.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundaries of the zoom slider's touch zones, measured on the Huawei camera (Honor 10): 3 px = 1 dp.
 * The test pins exactly the measured points: where Huawei opens the scale or jumps via the dot, and where it starts focus.
 */
class ZoomZonesTest {
    private fun px(value: Int) = value / 3f

    @Test
    fun ringOpensTheScaleOnTheRingAndAroundItHorizontally() {
        assertTrue(ZoomZones.hitsRing(0f, 0f))
        assertTrue(ZoomZones.hitsRing(px(50), 0f))
        assertTrue(ZoomZones.hitsRing(px(-100), 0f)) // in Huawei: still the scale
    }

    @Test
    fun beyondTheRingZoneTheTouchIsFocus() {
        assertFalse(ZoomZones.hitsRing(px(-110), 0f)) // in Huawei: already focus
        assertFalse(ZoomZones.hitsRing(px(-150), 0f))
        assertFalse(ZoomZones.hitsRing(px(-250), 0f))
    }

    @Test
    fun ringZoneIsOnlyAsTallAsTheRing() {
        assertTrue(ZoomZones.hitsRing(0f, px(-35)))
        assertTrue(ZoomZones.hitsRing(0f, px(40)))
        assertFalse(ZoomZones.hitsRing(0f, px(-60))) // in Huawei: nothing with the scale
        assertFalse(ZoomZones.hitsRing(0f, px(85)))
    }

    @Test
    fun presetDotAboveTheRingJumpsOnAndAroundIt() {
        val up = -1f // dotted tail up from the ring: at low zoom
        assertTrue(ZoomZones.hitsPreset(0f, 0f, up))
        assertTrue(ZoomZones.hitsPreset(0f, px(-60), up)) // 60 px farther from the ring — still a jump
        assertTrue(ZoomZones.hitsPreset(0f, px(30), up)) // 30 px toward the ring — still a jump
        assertTrue(ZoomZones.hitsPreset(px(100), 0f, up))
        assertTrue(ZoomZones.hitsPreset(px(-60), 0f, up))
    }

    @Test
    fun presetDotZoneEnds() {
        val up = -1f
        assertFalse(ZoomZones.hitsPreset(0f, px(60), up)) // 60 px toward the ring: nothing in Huawei
        assertFalse(ZoomZones.hitsPreset(px(-110), 0f, up)) // in Huawei: focus
        assertFalse(ZoomZones.hitsPreset(0f, px(-120), up))
    }

    @Test
    fun presetZoneIsMirroredWhenTheDotIsBelowTheRing() {
        val down = 1f // dotted tail down: at high zoom
        assertTrue(ZoomZones.hitsPreset(0f, px(60), down))
        assertTrue(ZoomZones.hitsPreset(0f, px(-30), down))
        assertFalse(ZoomZones.hitsPreset(0f, px(-60), down))
        assertFalse(ZoomZones.hitsPreset(0f, px(120), down))
    }
}
