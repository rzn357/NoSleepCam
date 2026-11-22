package com.example.nosleepcam

import android.content.Context
import com.example.nosleepcam.camera.CameraResolution

/**
 * Збережені налаштування: остання камера та обрана для кожної камери роздільність.
 * Зум і спалах навмисно НЕ зберігаються: при кожному запуску зум = 1x, спалах вимкнений.
 */
internal class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nosleepcam", Context.MODE_PRIVATE)

    var cameraId: String?
        get() = prefs.getString(KEY_CAMERA, null)
        set(value) = prefs.edit().putString(KEY_CAMERA, value).apply()

    /** Адреса останнього знімка в галереї (для кола-мініатюри при наступному запуску). */
    var lastPhoto: String?
        get() = prefs.getString(KEY_LAST_PHOTO, null)
        set(value) = prefs.edit().putString(KEY_LAST_PHOTO, value).apply()

    fun resolution(cameraId: String): CameraResolution? =
        CameraResolution.tryParse(prefs.getString(resolutionKey(cameraId), null))

    fun setResolution(cameraId: String, resolution: CameraResolution) =
        prefs.edit().putString(resolutionKey(cameraId), resolution.key).apply()

    private fun resolutionKey(cameraId: String) = "resolution_$cameraId"

    private companion object {
        const val KEY_CAMERA = "camera_id"
        const val KEY_LAST_PHOTO = "last_photo"
    }
}
