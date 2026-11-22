package com.example.nosleepcam.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Чиста логіка знімка: орієнтація JPEG, вибір розміру, ім'я файлу. */
internal object PhotoMath {

    /**
     * Значення для `JPEG_ORIENTATION`: на скільки градусів за годинниковою стрілкою треба повернути кадр сенсора, щоб
     * він був «вгору» для користувача. [displayRotation] — поворот екрана (0/90/180/270, як `Surface.ROTATION_*`);
     * екран повертається проти руху пристрою, тож поворот самого пристрою — це `360 − displayRotation`.
     */
    fun jpegOrientation(sensorOrientation: Int, displayRotation: Int, isFront: Boolean): Int {
        val device = (360 - displayRotation) % 360
        return if (isFront) (sensorOrientation - device + 360) % 360 else (sensorOrientation + device) % 360
    }

    /**
     * Розмір фото: найбільший серед підтримуваних JPEG-розмірів із тими самими пропорціями, що й попередній перегляд
     * (знімок має містити те, що видно на екрані). Якщо таких немає — найбільший із усіх.
     */
    fun pickJpegSize(sizes: List<CameraResolution>, preview: CameraResolution): CameraResolution? {
        if (sizes.isEmpty() || preview.height <= 0) return null

        val aspect = preview.width.toDouble() / preview.height
        val sameAspect = sizes.filter { it.height > 0 && abs(it.width.toDouble() / it.height - aspect) < ASPECT_TOLERANCE }
        return (sameAspect.ifEmpty { sizes }).maxByOrNull { it.pixels }
    }

    /** Ім'я файлу, як у камері Huawei: «IMG_20261121_131533.jpg»; [duplicate] > 0 додає «_1», «_2»… для одночасних знімків. */
    fun fileName(takenAtMs: Long, duplicate: Int = 0): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(takenAtMs))
        return if (duplicate > 0) "IMG_${stamp}_$duplicate.jpg" else "IMG_$stamp.jpg"
    }

    private const val ASPECT_TOLERANCE = 0.02
}
