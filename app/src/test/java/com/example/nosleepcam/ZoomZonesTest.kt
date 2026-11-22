package com.example.nosleepcam

import com.example.nosleepcam.ui.ZoomZones
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межі зон дотику повзунка зуму, виміряні на камері Huawei (Honor 10): 3 px = 1 dp.
 * Тест фіксує саме виміряні точки: де Huawei відкриває шкалу чи стрибає за крапкою, а де запускає фокус.
 */
class ZoomZonesTest {

    private fun px(value: Int) = value / 3f

    @Test
    fun ringOpensTheScaleOnTheRingAndAroundItHorizontally() {
        assertTrue(ZoomZones.hitsRing(0f, 0f))
        assertTrue(ZoomZones.hitsRing(px(50), 0f))
        assertTrue(ZoomZones.hitsRing(px(-100), 0f)) // у Huawei: ще шкала
    }

    @Test
    fun beyondTheRingZoneTheTouchIsFocus() {
        assertFalse(ZoomZones.hitsRing(px(-110), 0f)) // у Huawei: уже фокус
        assertFalse(ZoomZones.hitsRing(px(-150), 0f))
        assertFalse(ZoomZones.hitsRing(px(-250), 0f))
    }

    @Test
    fun ringZoneIsOnlyAsTallAsTheRing() {
        assertTrue(ZoomZones.hitsRing(0f, px(-35)))
        assertTrue(ZoomZones.hitsRing(0f, px(40)))
        assertFalse(ZoomZones.hitsRing(0f, px(-60))) // у Huawei: нічого з шкалою
        assertFalse(ZoomZones.hitsRing(0f, px(85)))
    }

    @Test
    fun presetDotAboveTheRingJumpsOnAndAroundIt() {
        val up = -1f // пунктир угору від кільця: на малому зумі
        assertTrue(ZoomZones.hitsPreset(0f, 0f, up))
        assertTrue(ZoomZones.hitsPreset(0f, px(-60), up))   // 60 px далі від кільця — ще стрибок
        assertTrue(ZoomZones.hitsPreset(0f, px(30), up))    // 30 px до кільця — ще стрибок
        assertTrue(ZoomZones.hitsPreset(px(100), 0f, up))
        assertTrue(ZoomZones.hitsPreset(px(-60), 0f, up))
    }

    @Test
    fun presetDotZoneEnds() {
        val up = -1f
        assertFalse(ZoomZones.hitsPreset(0f, px(60), up))   // 60 px до кільця: у Huawei нічого
        assertFalse(ZoomZones.hitsPreset(px(-110), 0f, up)) // у Huawei: фокус
        assertFalse(ZoomZones.hitsPreset(0f, px(-120), up))
    }

    @Test
    fun presetZoneIsMirroredWhenTheDotIsBelowTheRing() {
        val down = 1f // пунктир униз: на великому зумі
        assertTrue(ZoomZones.hitsPreset(0f, px(60), down))
        assertTrue(ZoomZones.hitsPreset(0f, px(-30), down))
        assertFalse(ZoomZones.hitsPreset(0f, px(-60), down))
        assertFalse(ZoomZones.hitsPreset(0f, px(120), down))
    }
}
