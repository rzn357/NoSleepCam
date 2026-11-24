package com.exciteddev.nosleepcam.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Pure photo logic: JPEG orientation, size selection, file name. */
internal object PhotoMath {
    /**
     * Value for `JPEG_ORIENTATION`: by how many degrees clockwise the sensor frame must be rotated so that
     * it is "up" for the user. [displayRotation] is the screen rotation (0/90/180/270, like `Surface.ROTATION_*`);
     * the screen rotates against the device's movement, so the device's own rotation is `360 − displayRotation`.
     */
    fun jpegOrientation(
        sensorOrientation: Int,
        displayRotation: Int,
        isFront: Boolean,
    ): Int {
        val device = (360 - displayRotation) % 360
        return if (isFront) (sensorOrientation - device + 360) % 360 else (sensorOrientation + device) % 360
    }

    /**
     * Photo size: the largest of the supported JPEG sizes with the same aspect ratio as the preview
     * (the photo must contain what is visible on screen). If there are none — the largest of all.
     */
    fun pickJpegSize(
        sizes: List<CameraResolution>,
        preview: CameraResolution,
    ): CameraResolution? {
        if (sizes.isEmpty() || preview.height <= 0) return null

        val aspect = preview.width.toDouble() / preview.height
        val sameAspect = sizes.filter { it.height > 0 && abs(it.width.toDouble() / it.height - aspect) < ASPECT_TOLERANCE }
        return (sameAspect.ifEmpty { sizes }).maxByOrNull { it.pixels }
    }

    /** File name, as in the Huawei camera: "IMG_20261121_131533.jpg"; [duplicate] > 0 adds "_1", "_2"… for simultaneous photos. */
    fun fileName(
        takenAtMs: Long,
        duplicate: Int = 0,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(takenAtMs))
        return if (duplicate > 0) "IMG_${stamp}_$duplicate.jpg" else "IMG_$stamp.jpg"
    }

    private const val ASPECT_TOLERANCE = 0.02
}
