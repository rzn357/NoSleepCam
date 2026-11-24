package com.exciteddev.nosleepcam

import com.exciteddev.nosleepcam.camera.CameraResolution
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CameraResolutionTest {

    private lateinit var savedLocale: Locale

    @Before
    fun useUkrainianLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("uk-UA"))
    }

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun keyRoundTrips() {
        val resolution = CameraResolution(4000, 3000)
        assertEquals("4000x3000", resolution.key)
        assertEquals(resolution, CameraResolution.tryParse(resolution.key))
    }

    @Test
    fun tryParseRejectsGarbage() {
        assertNull(CameraResolution.tryParse(null))
        assertNull(CameraResolution.tryParse(""))
        assertNull(CameraResolution.tryParse("  "))
        assertNull(CameraResolution.tryParse("4000"))
        assertNull(CameraResolution.tryParse("axb"))
        assertNull(CameraResolution.tryParse("0x100"))
        assertNull(CameraResolution.tryParse("-5x100"))
        assertNull(CameraResolution.tryParse("1x2x3"))
    }

    @Test
    fun aspectRatioUsesKnownLabelsRegardlessOfOrientation() {
        assertEquals("4:3", CameraResolution(4000, 3000).aspectRatio)
        assertEquals("4:3", CameraResolution(3000, 4000).aspectRatio)
        assertEquals("16:9", CameraResolution(1920, 1080).aspectRatio)
        assertEquals("1:1", CameraResolution(3456, 3456).aspectRatio)
    }

    @Test
    fun aspectRatioFallsBackToDecimalWithLocaleSeparator() {
        // 4608×2272 ≈ 2.03:1 — approximating to known aspect ratios does not fit
        assertEquals("2,03:1", CameraResolution(4608, 2272).aspectRatio)
    }

    @Test
    fun displayNameMatchesTheOriginalFormat() {
        assertEquals("4000×3000 · 12,0 MP · 4:3", CameraResolution(4000, 3000).displayName("MP"))
        assertEquals("1920×1080 · 2,1 MP · 16:9", CameraResolution(1920, 1080).displayName("MP"))
    }

    @Test
    fun pixelsDoNotOverflowInt() {
        assertEquals(12_000_000L, CameraResolution(4000, 3000).pixels)
        assertEquals(100_000L * 100_000L, CameraResolution(100_000, 100_000).pixels)
    }
}
