package com.example.nosleepcam

import android.content.Context
import com.example.nosleepcam.camera.CameraResolution

/**
 * Saved settings: the last camera and the resolution chosen for each camera.
 * Zoom and flash are deliberately NOT saved: on every launch zoom = 1x and the flash is off.
 */
internal class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var cameraId: String?
        get() = prefs.getString(KEY_CAMERA, null)
        set(value) = prefs.edit().putString(KEY_CAMERA, value).apply()

    /** URI of the last photo in the gallery (for the thumbnail circle on the next launch). */
    var lastPhoto: String?
        get() = prefs.getString(KEY_LAST_PHOTO, null)
        set(value) = prefs.edit().putString(KEY_LAST_PHOTO, value).apply()

    /** Language tag chosen by the user ("en", "uk"); null — follow the system language. */
    var language: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    fun resolution(cameraId: String): CameraResolution? =
        CameraResolution.tryParse(prefs.getString(resolutionKey(cameraId), null))

    fun setResolution(cameraId: String, resolution: CameraResolution) =
        prefs.edit().putString(resolutionKey(cameraId), resolution.key).apply()

    private fun resolutionKey(cameraId: String) = "resolution_$cameraId"

    companion object {
        private const val FILE = "nosleepcam"
        private const val KEY_CAMERA = "camera_id"
        private const val KEY_LAST_PHOTO = "last_photo"
        private const val KEY_LANGUAGE = "language"

        /**
         * Reads the language straight from a base context: it is needed in attachBaseContext, where
         * `applicationContext` does not exist yet.
         */
        fun readLanguage(base: Context): String? =
            base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
    }
}
