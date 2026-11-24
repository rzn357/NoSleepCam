package com.exciteddev.nosleepcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorGeometryTest {
    private val sensor = SensorRect(0, 0, 4000, 3000)

    @Test
    fun noZoomShowsTheWholeSensor() {
        assertEquals(sensor, SensorGeometry.cropForZoom(sensor, 1f))
    }

    @Test
    fun cropIsCentredAndShrinksWithZoom() {
        assertEquals(SensorRect(1000, 750, 3000, 2250), SensorGeometry.cropForZoom(sensor, 2f))
    }

    @Test
    fun cropSizeIsAlwaysEven() {
        // 4000 / 3 = 1333 would be odd; cameras require even crop sizes.
        assertEquals(SensorRect(1334, 1000, 2666, 2000), SensorGeometry.cropForZoom(sensor, 3f))
    }

    @Test
    fun cropRespectsANonZeroOrigin() {
        val active = SensorRect(8, 16, 4008, 3016)
        assertEquals(SensorRect(1008, 766, 3008, 2266), SensorGeometry.cropForZoom(active, 2f))
    }

    @Test
    fun visibleRectIsTheCropWhenAspectRatiosMatch() {
        assertEquals(sensor, SensorGeometry.visibleRect(sensor, 1440, 1080))
    }

    @Test
    fun visibleRectIsCutTopAndBottomForAWiderStream() {
        // A 16:9 stream from a 4:3 sensor: the height shrinks to 4000 / (16/9) = 2250.
        assertEquals(SensorRect(0, 375, 4000, 2625), SensorGeometry.visibleRect(sensor, 1920, 1080))
    }

    @Test
    fun visibleRectIsCutLeftAndRightForANarrowerStream() {
        val wide = SensorRect(0, 0, 4000, 2250)
        assertEquals(SensorRect(500, 0, 3500, 2250), SensorGeometry.visibleRect(wide, 1440, 1080))
    }

    @Test
    fun naturalPointIsRotatedBackIntoTheSensor() {
        assertEquals(0.2 to 0.7, SensorGeometry.naturalToSensor(0.2, 0.7, 0))
        assertPoint(0.7, 0.8, SensorGeometry.naturalToSensor(0.2, 0.7, 90))
        assertPoint(0.8, 0.3, SensorGeometry.naturalToSensor(0.2, 0.7, 180))
        assertPoint(0.3, 0.2, SensorGeometry.naturalToSensor(0.2, 0.7, 270))
    }

    @Test
    fun focusRegionIsCentredOnTheTapAndSizedByTheVisibleArea() {
        // 18% of the shorter side (3000) = 540 px.
        assertEquals(SensorRect(1730, 1230, 2270, 1770), SensorGeometry.focusRegion(0.5f, 0.5f, 90, sensor, sensor))
    }

    @Test
    fun focusRegionIsKeptInsideTheSensor() {
        assertEquals(SensorRect(0, 0, 540, 540), SensorGeometry.focusRegion(0f, 0f, 0, sensor, sensor))
        // Sensor orientation 90: natural (0, 0) is the bottom-left corner of the sensor buffer.
        assertEquals(SensorRect(0, 2460, 540, 3000), SensorGeometry.focusRegion(0f, 0f, 90, sensor, sensor))
    }

    @Test
    fun focusRegionHasAMinimumSizeButNeverExceedsTheSensor() {
        val tiny = SensorRect(0, 0, 100, 100)
        assertEquals(32, SensorGeometry.focusRegion(0.5f, 0.5f, 0, tiny, tiny).width)

        val smaller = SensorRect(0, 0, 20, 20)
        assertEquals(20, SensorGeometry.focusRegion(0.5f, 0.5f, 0, smaller, smaller).width)
    }

    @Test
    fun cropClampingIsDetectedBeyondATolerance() {
        assertFalse(SensorGeometry.isCropClamped(askedWidth = 1000, appliedWidth = 1000))
        assertFalse(SensorGeometry.isCropClamped(askedWidth = 1000, appliedWidth = 1030))
        assertTrue(SensorGeometry.isCropClamped(askedWidth = 1000, appliedWidth = 1031))
        assertFalse(SensorGeometry.isCropClamped(askedWidth = 1000, appliedWidth = 700)) // narrower: just aspect-ratio fitting
        assertFalse(SensorGeometry.isCropClamped(askedWidth = 0, appliedWidth = 1000))
    }

    @Test
    fun achievedZoomIsRoundedDownToATenthAndNeverBelowTheDeclaredMaximum() {
        assertEquals(4.0f, SensorGeometry.achievedZoom(activeWidth = 4000, appliedWidth = 1000, declaredMax = 1f), 1e-4f)
        assertEquals(3.2f, SensorGeometry.achievedZoom(activeWidth = 4000, appliedWidth = 1250, declaredMax = 1f), 1e-4f)
        assertEquals(6.0f, SensorGeometry.achievedZoom(activeWidth = 4000, appliedWidth = 1000, declaredMax = 6f), 1e-4f)
    }

    @Test
    fun regionVisibilityIsDecidedByItsCentre() {
        val crop = SensorRect(1000, 750, 3000, 2250)
        assertTrue(crop.containsCenterOf(SensorRect(1900, 1400, 2100, 1600)))
        assertTrue(crop.containsCenterOf(SensorRect(1000, 750, 1002, 752))) // the left/top edge is inclusive
        assertFalse(crop.containsCenterOf(SensorRect(2999, 1400, 3001, 1402))) // the right edge is exclusive
        assertFalse(crop.containsCenterOf(SensorRect(0, 0, 200, 200)))
    }

    private fun assertPoint(
        x: Double,
        y: Double,
        actual: Pair<Double, Double>,
    ) {
        assertEquals(x, actual.first, 1e-9)
        assertEquals(y, actual.second, 1e-9)
    }
}
