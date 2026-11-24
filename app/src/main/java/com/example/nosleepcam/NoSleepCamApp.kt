package com.example.nosleepcam

import android.app.Application
import android.content.Context
import com.example.nosleepcam.camera.CameraController

class NoSleepCamApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark { "NoSleepCamApp.onCreate" }

        // The camera takes the longest to open (hundreds of ms), so we start opening it BEFORE the UI is created:
        // the two proceed in parallel on different threads.
        CameraController.warmUp(this)
        StartupTrace.mark { "Camera warm-up started" }
    }
}
