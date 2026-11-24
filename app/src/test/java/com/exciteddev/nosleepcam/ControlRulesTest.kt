package com.exciteddev.nosleepcam

import com.exciteddev.nosleepcam.ControlRules.FlashClick
import com.exciteddev.nosleepcam.ControlRules.PhotoRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlRulesTest {
    @Test
    fun flashTogglesWhenTheCameraHasOneAndNoHighSpeedSessionIsRunning() {
        assertEquals(FlashClick.Toggle, ControlRules.flashClick(supportsFlash = true, highSpeed = false))
    }

    @Test
    fun flashClickBeforeTheCameraReportedItsCapabilitiesStillToggles() {
        assertEquals(FlashClick.Toggle, ControlRules.flashClick(supportsFlash = null, highSpeed = false))
    }

    @Test
    fun aCameraWithoutAFlashExplainsWhyNothingHappens() {
        assertEquals(FlashClick.NoFlash, ControlRules.flashClick(supportsFlash = false, highSpeed = false))
    }

    @Test
    fun noFlashTakesPrecedenceOverHighSpeedInTheExplanation() {
        assertEquals(FlashClick.NoFlash, ControlRules.flashClick(supportsFlash = false, highSpeed = true))
    }

    @Test
    fun highSpeedModeHasNoFlash() {
        assertEquals(FlashClick.HighSpeed, ControlRules.flashClick(supportsFlash = true, highSpeed = true))
    }

    private fun photo(
        highSpeed: Boolean = false,
        cameraReady: Boolean = true,
        canCapture: Boolean = true,
        needsStoragePermission: Boolean = false,
    ) = ControlRules.photoRequest(highSpeed, cameraReady, canCapture, needsStoragePermission)

    @Test
    fun aPhotoIsTakenWhenEverythingIsReady() {
        assertEquals(PhotoRequest.Take, photo())
    }

    @Test
    fun highSpeedModeCannotTakePhotos() {
        assertEquals(PhotoRequest.HighSpeed, photo(highSpeed = true))
    }

    @Test
    fun aTapBeforeTheCameraIsReadyIsIgnored() {
        assertEquals(PhotoRequest.CameraNotReady, photo(cameraReady = false))
    }

    @Test
    fun aSessionWithoutAJpegStreamCannotTakePhotos() {
        assertEquals(PhotoRequest.Unavailable, photo(canCapture = false))
    }

    @Test
    fun oldAndroidAsksForTheStoragePermissionFirst() {
        assertEquals(PhotoRequest.NeedsStoragePermission, photo(needsStoragePermission = true))
    }

    @Test
    fun theChecksRunInOrderOfImportance() {
        assertEquals(PhotoRequest.HighSpeed, photo(highSpeed = true, cameraReady = false, canCapture = false))
        assertEquals(PhotoRequest.CameraNotReady, photo(cameraReady = false, canCapture = false, needsStoragePermission = true))
        assertEquals(PhotoRequest.Unavailable, photo(canCapture = false, needsStoragePermission = true))
    }
}
