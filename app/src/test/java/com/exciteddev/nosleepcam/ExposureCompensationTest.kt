package com.exciteddev.nosleepcam

import com.exciteddev.nosleepcam.camera.ExposureCompensation
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExposureCompensationTest {

    private lateinit var savedLocale: Locale

    @Before
    fun useUkrainianLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("uk-UA"))
    }

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun endsOfTheLineGiveTheRangeLimits() {
        assertEquals(4, ExposureCompensation.indexFor(1f, -4, 4))
        assertEquals(-4, ExposureCompensation.indexFor(-1f, -4, 4))
        assertEquals(0, ExposureCompensation.indexFor(0f, -4, 4))
    }

    @Test
    fun positionIsRoundedToTheNearestStep() {
        assertEquals(2, ExposureCompensation.indexFor(0.5f, -4, 4))
        assertEquals(2, ExposureCompensation.indexFor(0.4f, -4, 4))
        assertEquals(0, ExposureCompensation.indexFor(0.1f, -4, 4))
        assertEquals(-2, ExposureCompensation.indexFor(-0.4f, -4, 4))
    }

    @Test
    fun positionIsClampedToTheLine() {
        assertEquals(4, ExposureCompensation.indexFor(7f, -4, 4))
        assertEquals(-4, ExposureCompensation.indexFor(-7f, -4, 4))
    }

    @Test
    fun asymmetricRangeUsesEachSideSeparately() {
        assertEquals(6, ExposureCompensation.indexFor(1f, -2, 6))
        assertEquals(-2, ExposureCompensation.indexFor(-1f, -2, 6))
        assertEquals(3, ExposureCompensation.indexFor(0.5f, -2, 6))
        assertEquals(-1, ExposureCompensation.indexFor(-0.5f, -2, 6))
    }

    @Test
    fun fractionIsTheInverseOfIndex() {
        for (index in -4..4) {
            val fraction = ExposureCompensation.fractionFor(index, -4, 4)
            assertEquals(index, ExposureCompensation.indexFor(fraction, -4, 4))
        }
        assertEquals(0.5f, ExposureCompensation.fractionFor(2, -4, 4), 1e-6f)
        assertEquals(-1f, ExposureCompensation.fractionFor(-4, -4, 4), 1e-6f)
    }

    @Test
    fun fractionIsZeroWhenTheRangeHasNoSuchSide() {
        assertEquals(0f, ExposureCompensation.fractionFor(3, 0, 0), 0f)
        assertEquals(0f, ExposureCompensation.fractionFor(-3, 0, 4), 0f)
    }

    @Test
    fun labelUsesSignMinusAndLocaleSeparator() {
        assertEquals("0", ExposureCompensation.format(0, 0.5f))
        assertEquals("+1,5", ExposureCompensation.format(3, 0.5f))
        assertEquals("−0,5", ExposureCompensation.format(-1, 0.5f))
        assertEquals("+2,0", ExposureCompensation.format(4, 0.5f))
    }
}
