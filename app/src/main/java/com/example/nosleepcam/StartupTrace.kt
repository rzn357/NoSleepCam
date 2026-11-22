package com.example.nosleepcam

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Діагностика часу запуску: пише в logcat (тег «NSC-Startup») мілісекунди від старту процесу.
 * У звичайній збірці [mark] і [req] зводяться до порожньої умови, яку R8 вилучає разом із побудовою рядка.
 *
 * Увімкнути: `./gradlew installRelease -PstartupTrace=true`, дивитись: `adb logcat -s NSC-Startup`.
 */
internal object StartupTrace {

    inline fun mark(label: () -> String) {
        if (BuildConfig.STARTUP_TRACE) {
            val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            Log.i("NSC-Startup", "${ms.toString().padStart(5)} ms  ${label()}")
        }
    }

    /** Діагностика запитів до камери (тег «NSC-Req»). */
    inline fun req(message: () -> String) {
        if (BuildConfig.STARTUP_TRACE) message().let { if (it.isNotEmpty()) Log.i("NSC-Req", it) }
    }

    /** Діагностика частоти кадрів: виміряна проти заявленої (тег «NSC-Fps»). */
    inline fun fps(message: () -> String) {
        if (BuildConfig.STARTUP_TRACE) Log.i("NSC-Fps", message())
    }
}
