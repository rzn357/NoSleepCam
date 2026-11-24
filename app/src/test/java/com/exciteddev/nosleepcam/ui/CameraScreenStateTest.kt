package com.exciteddev.nosleepcam.ui

import com.exciteddev.nosleepcam.camera.CameraCapabilities
import com.exciteddev.nosleepcam.camera.CameraDescriptor
import com.exciteddev.nosleepcam.camera.CameraResolution
import com.exciteddev.nosleepcam.camera.LensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraScreenStateTest {
    private val back = CameraDescriptor("0", LensFacing.Back)
    private val front = CameraDescriptor("1", LensFacing.Front)
    private val hd = CameraResolution(1920, 1080)

    private fun caps(
        camera: CameraDescriptor = back,
        maxZoom: Double = 10.0,
        highSpeedFps: Int = 120,
    ) = CameraCapabilities(
        camera = camera,
        availableCameras = listOf(back, front),
        resolutions = listOf(hd),
        frameRates = mapOf(hd to 30.0),
        highSpeedFps = highSpeedFps,
        highSpeedResolution = if (highSpeedFps > 0) hd else null,
        maxZoom = maxZoom,
        supportsAutoFocus = true,
        supportsFlash = true,
        supportsTapToFocus = true,
    )

    @Test
    fun theScreenStartsAtOneXWithEverythingOff() {
        val state = CameraScreenState()
        assertEquals(1.0, state.zoom, 0.0)
        assertFalse(state.flashOn)
        assertFalse(state.highSpeed)
        assertFalse(state.settingsOpen)
        assertNull(state.capabilities)
    }

    @Test
    fun capabilitiesSelectTheirCamera() {
        val state = CameraScreenState(selectedCameraId = "0").withCapabilities(caps(camera = front))
        assertEquals("1", state.selectedCameraId)
    }

    @Test
    fun theZoomIsLoweredToTheNewMaximum() {
        val state = CameraScreenState(zoom = 8.0).withCapabilities(caps(maxZoom = 3.0))
        assertEquals(3.0, state.zoom, 0.0)
    }

    @Test
    fun theZoomWithinTheRangeIsKept() {
        val state = CameraScreenState(zoom = 2.5).withCapabilities(caps(maxZoom = 10.0))
        assertEquals(2.5, state.zoom, 0.0)
    }

    @Test
    fun highSpeedModeIsDroppedWhenTheCameraNoLongerOffersIt() {
        val state = CameraScreenState(highSpeed = true).withCapabilities(caps(highSpeedFps = 0))
        assertFalse(state.highSpeed)
    }

    @Test
    fun highSpeedModeStaysWhileTheCameraOffersIt() {
        val state = CameraScreenState(highSpeed = true).withCapabilities(caps(highSpeedFps = 120))
        assertTrue(state.highSpeed)
    }

    @Test
    fun turningHighSpeedOnTurnsTheFlashOff() {
        val state = CameraScreenState(flashOn = true).withHighSpeed(true)
        assertTrue(state.highSpeed)
        assertFalse(state.flashOn)
    }

    @Test
    fun turningHighSpeedOffLeavesTheFlashAlone() {
        val state = CameraScreenState(flashOn = true, highSpeed = false).withHighSpeed(false)
        assertTrue(state.flashOn)
    }

    @Test
    fun settingsOfAScreenWithoutCapabilitiesAreEmpty() {
        val ui = CameraScreenState().settingsUiState(pageHeightDp = 700.0)
        assertTrue(ui.resolutions.isEmpty())
        assertTrue(ui.cameras.isEmpty())
        assertNull(ui.highSpeed)
        assertEquals(700.0, ui.pageHeightDp, 0.0)
    }

    @Test
    fun settingsShowTheHighSpeedModeOnlyForACameraThatOffersIt() {
        val on = CameraScreenState(highSpeed = true).withCapabilities(caps(highSpeedFps = 120)).settingsUiState(700.0)
        assertEquals(HighSpeedState(120, hd, enabled = true), on.highSpeed)

        val none = CameraScreenState().withCapabilities(caps(highSpeedFps = 0)).settingsUiState(700.0)
        assertNull(none.highSpeed)
    }

    @Test
    fun settingsShowTheResolutionTheCameraRunsWith() {
        val ui = CameraScreenState(appliedResolution = hd).withCapabilities(caps()).settingsUiState(700.0)
        assertEquals(hd, ui.selectedResolution)
        assertEquals(listOf(hd), ui.resolutions)
    }
}
