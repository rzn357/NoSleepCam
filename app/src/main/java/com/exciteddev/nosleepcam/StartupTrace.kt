package com.exciteddev.nosleepcam

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Startup timing diagnostics: writes milliseconds since process start to logcat (tag "NSC-Startup").
 * In a normal build [mark] and [req] reduce to an empty condition, which R8 removes together with building the string.
 *
 * To enable: `./gradlew installRelease -PstartupTrace=true`, to watch: `adb logcat -s NSC-Startup`.
 */
internal object StartupTrace {
    inline fun mark(label: () -> String) {
        if (BuildConfig.STARTUP_TRACE) {
            val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            Log.i("NSC-Startup", "${ms.toString().padStart(5)} ms  ${label()}")
        }
    }

    /** Camera request diagnostics (tag "NSC-Req"). */
    inline fun req(message: () -> String) {
        if (BuildConfig.STARTUP_TRACE) message().let { if (it.isNotEmpty()) Log.i("NSC-Req", it) }
    }

    /** Frame rate diagnostics: measured vs. declared (tag "NSC-Fps"). */
    inline fun fps(message: () -> String) {
        if (BuildConfig.STARTUP_TRACE) Log.i("NSC-Fps", message())
    }
}
