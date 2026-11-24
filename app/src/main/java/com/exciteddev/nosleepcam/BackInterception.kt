package com.exciteddev.nosleepcam

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Android 13+ does not call `onBackPressed` for the "Back" gesture: a handler has to be registered with the dispatcher instead.
 * It is registered only while it is needed (the settings panel is open), so otherwise "Back" works as usual.
 *
 * Everything API 33 specific lives in this class, so the activity never references those types and loads fine on older systems.
 * It is created only after an SDK_INT check; the app has no AndroidX, hence @TargetApi instead of @RequiresApi.
 */
@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.TIRAMISU)
internal class BackInterception(
    private val activity: Activity,
    onBack: () -> Unit,
) {
    private val callback = OnBackInvokedCallback(onBack)
    private var registered = false

    fun setEnabled(enabled: Boolean) {
        if (enabled == registered) return

        registered = enabled
        if (enabled) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }
}
