package com.exciteddev.nosleepcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomControlTest {
    private fun spec(
        declared: Float = 6f,
        offered: Float = 10f,
        id: String = "0",
        highSpeed: List<HighSpeedConfig> = emptyList(),
    ) = CameraSpec(
        id = id,
        lens = LensFacing.Back,
        sensorOrientation = 90,
        activeArray = SensorRect(0, 0, 4000, 3000),
        declaredMaxZoom = declared,
        offeredMaxZoom = offered,
        afMode = 0,
        hasAfAuto = true,
        maxAfRegions = 1,
        maxAeRegions = 1,
        hasFlash = true,
        evMin = -4,
        evMax = 4,
        evStep = 0.5f,
        backwardCompatible = true,
        resolutions = emptyList(),
        jpegSizes = emptyList(),
        frameRates = emptyMap(),
        aeFpsRanges = emptyList(),
        highSpeedConfigs = highSpeed,
        highSpeedAfMode = 0,
    )

    @Test
    fun theOfferedMaximumIsUsedUntilTheCameraProvesItWrong() {
        val zoom = ZoomControl()
        assertEquals(10f, zoom.maxZoom(spec(), highSpeed = false), 0f)
    }

    @Test
    fun highSpeedModeIsLimitedToTheDeclaredMaximum() {
        val zoom = ZoomControl()
        assertEquals(6f, zoom.maxZoom(spec(), highSpeed = true), 0f)
    }

    @Test
    fun currentZoomIsClampedToTheMaximum() {
        val zoom = ZoomControl()
        zoom.requested = 25f
        assertEquals(10f, zoom.current(spec(), highSpeed = false), 0f)
        zoom.requested = 0.3f
        assertEquals(1f, zoom.current(spec(), highSpeed = false), 0f)
    }

    @Test
    fun aCameraThatHonoursTheZoomKeepsItsLimit() {
        val zoom = ZoomControl()
        // Asked 400 px wide (10x of 4000), applied the same.
        assertNull(zoom.verify(spec(), askedWidth = 400, appliedWidth = 400))
        assertEquals(10f, zoom.offeredMax(spec()), 0f)
    }

    @Test
    fun aCameraThatClampsTheZoomLowersTheLimit() {
        val zoom = ZoomControl()
        // Asked 10x (400 px) but the camera applied 8x (500 px).
        val limit = zoom.verify(spec(), askedWidth = 400, appliedWidth = 500)

        assertEquals(8f, limit!!, 0.01f)
        assertEquals(limit, zoom.offeredMax(spec()), 0f)
    }

    @Test
    fun theRequestedZoomIsPulledDownToTheNewLimit() {
        val zoom = ZoomControl()
        zoom.requested = 10f
        zoom.verify(spec(), askedWidth = 400, appliedWidth = 500)

        assertEquals(8f, zoom.requested, 0.01f)
    }

    @Test
    fun aLimitIsRememberedPerCamera() {
        val zoom = ZoomControl()
        zoom.verify(spec(id = "0"), askedWidth = 400, appliedWidth = 500)

        assertEquals(10f, zoom.offeredMax(spec(id = "1")), 0f)
    }

    @Test
    fun nothingIsCheckedWhenTheOfferedMaximumIsTheDeclaredOne() {
        val zoom = ZoomControl()
        assertNull(zoom.verify(spec(declared = 4f, offered = 4f), askedWidth = 1000, appliedWidth = 2000))
    }

    @Test
    fun theHighSpeedPolicyRejectsSizesOneByOne() {
        val big = CameraResolution(1920, 1080)
        val small = CameraResolution(1280, 720)
        val spec = spec(highSpeed = listOf(HighSpeedConfig(big, 30, 120), HighSpeedConfig(small, 30, 120)))
        val policy = HighSpeedPolicy()

        assertEquals(big, policy.config(spec)?.size)
        assertFalse(policy.reject(spec, big)) // one size is still left
        assertEquals(small, policy.config(spec)?.size)
        assertTrue(policy.isSupported(spec))
    }

    @Test
    fun aCameraThatRejectedEverySizeIsNoLongerOfferedTheMode() {
        val size = CameraResolution(1920, 1080)
        val spec = spec(highSpeed = listOf(HighSpeedConfig(size, 30, 120)))
        val policy = HighSpeedPolicy()
        policy.wanted = true

        assertTrue(policy.isActive(spec))
        assertTrue(policy.reject(spec, size))
        assertFalse(policy.isSupported(spec))
        assertFalse(policy.isActive(spec))
    }
}
