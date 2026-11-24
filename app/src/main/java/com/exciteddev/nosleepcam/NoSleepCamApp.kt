package com.exciteddev.nosleepcam

import android.app.Application
import android.content.Context
import com.exciteddev.nosleepcam.camera.CameraController

/**
 * Owns the process-wide [CameraController]: it is created here and handed to the activity (plain manual dependency injection),
 * so the camera can be warmed up before any UI exists.
 */
class NoSleepCamApp : Application() {
    internal lateinit var cameraController: CameraController
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark { "NoSleepCamApp.onCreate" }

        // The camera takes the longest to open (hundreds of ms), so we start opening it BEFORE the UI is created:
        // the two proceed in parallel on different threads.
        cameraController = CameraController(this)
        cameraController.warmUp {
            val prefs = Prefs(this)
            val cameraId = prefs.cameraId
            CameraController.WarmUpSettings(cameraId, cameraId?.let(prefs::resolution))
        }
        StartupTrace.mark { "Camera warm-up started" }
    }
}
