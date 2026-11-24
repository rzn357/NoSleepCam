package com.example.nosleepcam

import com.example.nosleepcam.camera.CameraResolution
import com.example.nosleepcam.camera.PhotoMath
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoMathTest {

    @Test
    fun backCameraInPortraitUsesTheSensorOrientation() {
        assertEquals(90, PhotoMath.jpegOrientation(sensorOrientation = 90, displayRotation = 0, isFront = false))
    }

    @Test
    fun backCameraInLandscapeCompensatesTheDisplayRotation() {
        // The screen is rotated by 90° (the device is at 270° clockwise): the sensor frame is already "up".
        assertEquals(0, PhotoMath.jpegOrientation(90, 90, false))
        assertEquals(180, PhotoMath.jpegOrientation(90, 270, false))
        assertEquals(270, PhotoMath.jpegOrientation(90, 180, false))
    }

    @Test
    fun frontCameraRotatesTheOtherWay() {
        assertEquals(270, PhotoMath.jpegOrientation(270, 0, true))
        assertEquals(0, PhotoMath.jpegOrientation(270, 90, true))
        assertEquals(180, PhotoMath.jpegOrientation(270, 270, true))
    }

    @Test
    fun orientationIsAlwaysAMultipleOf90InRange() {
        for (sensor in intArrayOf(0, 90, 180, 270)) for (rotation in intArrayOf(0, 90, 180, 270)) for (front in booleanArrayOf(false, true)) {
            val value = PhotoMath.jpegOrientation(sensor, rotation, front)
            assertEquals(0, value % 90)
            assert(value in 0..270)
        }
    }

    private val jpegSizes = listOf(
        CameraResolution(4608, 3456), // 4:3
        CameraResolution(4608, 2592), // 16:9
        CameraResolution(3840, 2160), // 16:9
        CameraResolution(1920, 1080),
        CameraResolution(1440, 1080),
    )

    @Test
    fun photoKeepsThePreviewAspectRatio() {
        assertEquals(CameraResolution(4608, 3456), PhotoMath.pickJpegSize(jpegSizes, CameraResolution(1440, 1080)))
        assertEquals(CameraResolution(4608, 2592), PhotoMath.pickJpegSize(jpegSizes, CameraResolution(1920, 1080)))
    }

    @Test
    fun withoutAMatchingAspectTheLargestSizeIsUsed() {
        assertEquals(CameraResolution(4608, 3456), PhotoMath.pickJpegSize(jpegSizes, CameraResolution(1000, 1000)))
    }

    @Test
    fun noSizesMeansNoPhoto() {
        assertNull(PhotoMath.pickJpegSize(emptyList(), CameraResolution(1440, 1080)))
    }

    @Test
    fun fileNameFollowsTheHuaweiPattern() {
        val time = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 13, 15, 33) }.timeInMillis
        assertEquals("IMG_20261121_131533.jpg", PhotoMath.fileName(time))
        assertEquals("IMG_20261121_131533_2.jpg", PhotoMath.fileName(time, duplicate = 2))
    }
}
