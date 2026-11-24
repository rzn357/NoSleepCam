package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSpecRulesTest {
    private fun res(
        width: Int,
        height: Int,
    ) = CameraResolution(width, height)

    // ---- autofocus mode ----

    @Test
    fun continuousPictureIsPreferredOverEverythingElse() {
        val all =
            intArrayOf(
                CameraMetadata.CONTROL_AF_MODE_AUTO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CameraSpecRules.pickAfMode(all))
    }

    @Test
    fun autofocusFallsBackThroughVideoAndAutoToOff() {
        val videoAndAuto = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraSpecRules.pickAfMode(videoAndAuto))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_AUTO, CameraSpecRules.pickAfMode(intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO)))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_OFF, CameraSpecRules.pickAfMode(intArrayOf()))
    }

    @Test
    fun highSpeedPrefersVideoAutofocusWhenAvailable() {
        val withVideo = intArrayOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        assertEquals(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraSpecRules.highSpeedAfMode(withVideo, CameraMetadata.CONTROL_AF_MODE_AUTO),
        )
        assertEquals(
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraSpecRules.highSpeedAfMode(intArrayOf(), CameraMetadata.CONTROL_AF_MODE_AUTO),
        )
    }

    // ---- sizes ----

    @Test
    fun resolutionsAreDistinctLargestFirstAndTinySizesAreDropped() {
        val sizes = listOf(res(640, 480), res(176, 144), res(4000, 3000), res(640, 480), res(1920, 1080))
        assertEquals(listOf(res(4000, 3000), res(1920, 1080), res(640, 480)), CameraSpecRules.usableResolutions(sizes))
    }

    @Test
    fun tinySizesAreKeptWhenNothingElseIsLeft() {
        assertEquals(listOf(res(176, 144), res(128, 96)), CameraSpecRules.usableResolutions(listOf(res(128, 96), res(176, 144))))
    }

    // ---- zoom ----

    @Test
    fun huaweiBackCameraIsOfferedTenTimesZoomEvenWhenItDeclaresLess() {
        assertEquals(10f, CameraSpecRules.offeredMaxZoom(6f, isHuaweiBackCamera = true), 0f)
    }

    @Test
    fun otherCamerasKeepTheDeclaredZoom() {
        assertEquals(6f, CameraSpecRules.offeredMaxZoom(6f, isHuaweiBackCamera = false), 0f)
    }

    @Test
    fun aHigherDeclaredZoomIsNeverLowered() {
        assertEquals(16f, CameraSpecRules.offeredMaxZoom(16f, isHuaweiBackCamera = true), 0f)
    }

    // ---- frame rates ----

    @Test
    fun declaredFrameRateIsTheInverseOfTheMinimumFrameDuration() {
        assertEquals(30.0, CameraSpecRules.declaredFrameRate(33_333_333L, maxAeFps = 0)!!, 0.0)
    }

    @Test
    fun declaredFrameRateIsCappedByTheAutoExposureRange() {
        assertEquals(30.0, CameraSpecRules.declaredFrameRate(8_333_333L, maxAeFps = 30)!!, 0.0) // the sensor could do 120
    }

    @Test
    fun declaredFrameRateIsRoundedHalfToEven() {
        // 1e9 / 400_000_000 = 2.5 → 2.0 (banker's rounding, same as Math.rint)
        assertEquals(2.0, CameraSpecRules.declaredFrameRate(400_000_000L, maxAeFps = 0)!!, 0.0)
    }

    @Test
    fun missingOrNonsensicalDurationsGiveNoFrameRate() {
        assertNull(CameraSpecRules.declaredFrameRate(0, maxAeFps = 30))
        assertNull(CameraSpecRules.declaredFrameRate(-1, maxAeFps = 30))
        assertNull(CameraSpecRules.declaredFrameRate(2_000_000_000L, maxAeFps = 30)) // 0.5 fps
    }

    @Test
    fun highSpeedRangeHasTheHighestUpperAndThenTheLowestLowerBound() {
        val ranges = listOf(30 to 60, 60 to 120, 30 to 120, 120 to 120)
        assertEquals(30 to 120, CameraSpecRules.bestHighSpeedRange(ranges))
        assertEquals(0 to 0, CameraSpecRules.bestHighSpeedRange(emptyList()))
    }

    @Test
    fun onlyRatesFromSixtyFpsCountAsHighSpeed() {
        assertTrue(CameraSpecRules.isHighSpeed(30 to 60))
        assertFalse(CameraSpecRules.isHighSpeed(15 to 30))
    }

    @Test
    fun autoExposureRangeIsTheWidestOneWithinTheDeclaredRate() {
        val ranges = listOf(15 to 15, 14 to 25, 14 to 30, 30 to 30, 7 to 30)
        assertEquals(7 to 30, CameraSpecRules.aeFpsRange(ranges, declaredRate = 30.0))
        assertEquals(14 to 25, CameraSpecRules.aeFpsRange(ranges, declaredRate = 28.0))
    }

    @Test
    fun noAutoExposureRangeWhenNothingFitsOrNothingIsKnown() {
        assertNull(CameraSpecRules.aeFpsRange(listOf(30 to 30), declaredRate = 24.0))
        assertNull(CameraSpecRules.aeFpsRange(emptyList(), declaredRate = 30.0))
        assertNull(CameraSpecRules.aeFpsRange(listOf(30 to 30), declaredRate = null))
    }

    // ---- default preview size ----

    private val sensorAspect = 4.0 / 3

    @Test
    fun aRequestedSizeWinsWhenTheCameraOffersIt() {
        val candidates = listOf(res(4000, 3000), res(1440, 1080), res(640, 480))
        assertEquals(res(640, 480), CameraSpecRules.pickResolution(candidates, res(640, 480), sensorAspect))
    }

    @Test
    fun aRequestedSizeTheCameraDoesNotOfferIsIgnored() {
        val candidates = listOf(res(4000, 3000), res(1440, 1080))
        assertEquals(res(1440, 1080), CameraSpecRules.pickResolution(candidates, res(800, 600), sensorAspect))
    }

    @Test
    fun theDefaultIsTheLargestSizeUpToTwoMegapixelsWithTheFullFieldOfView() {
        // 1920x1080 is 16:9 (crops the sensor), so the 4:3 size is preferred even though it is smaller.
        val candidates = listOf(res(4000, 3000), res(1920, 1080), res(1440, 1080), res(640, 480))
        assertEquals(res(1440, 1080), CameraSpecRules.pickResolution(candidates, null, sensorAspect))
    }

    @Test
    fun withoutAFullFieldOfViewSizeTheLargestBalancedOneIsUsed() {
        val candidates = listOf(res(4000, 3000), res(1920, 1080), res(1280, 720))
        assertEquals(res(1920, 1080), CameraSpecRules.pickResolution(candidates, null, sensorAspect))
    }

    @Test
    fun whenEverythingIsHugeTheSmallestSizeIsUsed() {
        val candidates = listOf(res(4000, 3000), res(3264, 2448))
        assertEquals(res(3264, 2448), CameraSpecRules.pickResolution(candidates, null, sensorAspect))
    }

    @Test
    fun noCandidatesMeansNoResolution() {
        assertNull(CameraSpecRules.pickResolution(emptyList(), res(640, 480), sensorAspect))
    }
}
