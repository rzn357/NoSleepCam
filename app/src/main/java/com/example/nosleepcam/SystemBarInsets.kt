package com.example.nosleepcam

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Системні панелі.
 *
 * - Портрет: панелі видимі й чорні, вміст відступає від них (на Android 15+ вікно за замовчуванням малюється під ними,
 *   на старіших системах відступ нульовий).
 * - Ландшафт: панелі ховаються, і камера займає весь екран — інакше смуга статусу «відрізає» верх кадра. Свайп від краю
 *   тимчасово показує панелі поверх зображення.
 *
 * Виклики API 30+ винесено в окремі класи: так клас активності не посилається на них напряму, і на старіших
 * системах рантайм не скаржиться на невідомі методи при верифікації.
 */
internal object SystemBarInsets {

    /** Відступи від системних панелей. У повноекранному режимі ([immersive]) їх немає — панелі приховані. */
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

    /** Ховає (true) або показує (false) системні панелі. */
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

    // @RequiresApi з AndroidX не підключаємо (додаток без AndroidX), тому фреймворкова @TargetApi.
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
