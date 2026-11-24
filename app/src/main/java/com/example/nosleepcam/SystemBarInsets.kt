package com.example.nosleepcam

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * System bars.
 *
 * - Portrait: the bars are visible and black, the content is inset from them (on Android 15+ the window is drawn under them by default,
 *   on older systems the inset is zero).
 * - Landscape: the bars are hidden and the camera fills the whole screen — otherwise the status strip "cuts off" the top of the frame. A swipe from the edge
 *   temporarily shows the bars over the image.
 *
 * API 30+ calls are moved into separate classes: that way the activity class does not reference them directly, and on older
 * systems the runtime does not complain about unknown methods during verification.
 */
internal object SystemBarInsets {

    /** Insets from the system bars. In immersive mode ([immersive]) there are none — the bars are hidden. */
    fun install(root: View, immersive: () -> Boolean) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            when {
                immersive() -> view.setPadding(0, 0, 0, 0)
                Build.VERSION.SDK_INT >= 30 -> Api30.applyPadding(view, insets)
                else -> applyLegacyPadding(view, insets)
            }
            insets
        }
    }

    /** Hides (true) or shows (false) the system bars. */
    fun setImmersive(window: Window, immersive: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.setImmersive(window, immersive)
        } else {
            applyLegacyImmersive(window, immersive)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyPadding(view: View, insets: WindowInsets) {
        view.setPadding(
            insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
            insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
        )
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyImmersive(window: Window, immersive: Boolean) {
        window.decorView.systemUiVisibility = if (immersive) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // We don't pull in @RequiresApi from AndroidX (the app has no AndroidX), so we use the framework @TargetApi.
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun applyPadding(view: View, insets: WindowInsets) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }

        fun setImmersive(window: Window, immersive: Boolean) {
            val controller = window.insetsController ?: return
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        }
    }
}
