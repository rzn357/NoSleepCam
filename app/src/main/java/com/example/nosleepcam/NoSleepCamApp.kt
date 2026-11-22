package com.example.nosleepcam

import android.app.Application
import com.example.nosleepcam.camera.CameraController

class NoSleepCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark { "NoSleepCamApp.onCreate" }

        // Камера відкривається найдовше (сотні мс), тому починаємо її відкривати ще ДО створення інтерфейсу:
        // вони йдуть паралельно на різних потоках.
        CameraController.warmUp(this)
        StartupTrace.mark { "Прогрів камери запущено" }
    }
}
