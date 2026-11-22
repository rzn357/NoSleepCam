package com.example.nosleepcam.camera

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Форматує число за шаблоном (напр. «0.#») з роздільником мови пристрою; середина округлюється вгору, як у .NET. */
internal fun formatNumber(value: Double, pattern: String): String =
    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.getDefault()))
        .apply { roundingMode = RoundingMode.HALF_UP }
        .format(value)

/** Розмір кадру попереднього перегляду, який підтримує камера. */
data class CameraResolution(val width: Int, val height: Int) {

    val pixels: Long get() = width.toLong() * height

    val megapixels: Double get() = pixels / 1_000_000.0

    val aspectRatio: String
        get() {
            if (width <= 0 || height <= 0) return "?"

            val ratio = maxOf(width, height).toDouble() / minOf(width, height)
            for ((known, label) in KNOWN_ASPECTS) {
                if (Math.abs(ratio - known) < 0.02) return label
            }
            return formatNumber(ratio, "0.##") + ":1"
        }

    /** Текст для списку вибору: «4000×3000 · 12,0 МП · 4:3». */
    val displayName: String
        get() = "$width×$height · ${formatNumber(megapixels, "0.0")} МП · $aspectRatio"

    /** Стабільний ключ для збереження в налаштуваннях: «4000x3000». */
    val key: String get() = "${width}x$height"

    companion object {
        private val KNOWN_ASPECTS = listOf(
            4.0 / 3 to "4:3", 16.0 / 9 to "16:9", 3.0 / 2 to "3:2", 1.0 to "1:1",
            5.0 / 4 to "5:4", 11.0 / 9 to "11:9", 2.0 to "2:1", 18.0 / 9 to "18:9",
            19.5 / 9 to "19.5:9", 20.0 / 9 to "20:9", 21.0 / 9 to "21:9",
        )

        fun tryParse(key: String?): CameraResolution? {
            if (key.isNullOrBlank()) return null

            val parts = key.split('x')
            if (parts.size != 2) return null

            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return if (w > 0 && h > 0) CameraResolution(w, h) else null
        }
    }
}

/** Одна фізична/логічна камера пристрою (задня, передня тощо). */
data class CameraDescriptor(val id: String, val name: String)

/** Можливості відкритої камери: що саме можна показати користувачу в інтерфейсі. */
class CameraCapabilities(
    val camera: CameraDescriptor,
    val availableCameras: List<CameraDescriptor>,

    /** Підтримувані роздільності, від найбільшої до найменшої. */
    val resolutions: List<CameraResolution>,

    /**
     * Найбільша частота кадрів (к/с) для кожної роздільності за даними самої камери; ключ — [CameraResolution.key].
     * Якщо камера не повідомила — запису немає. Це межа, а не гарантія: у слабкому освітленні автоекспозиція знижує частоту.
     */
    val frameRates: Map<String, Double>,

    /** Найбільша частота кадрів у високошвидкісному режимі (Camera2 constrained high-speed), напр. 120; 0 — не підтримується. */
    val highSpeedFps: Int,

    /** Розмір кадру у високошвидкісному режимі (камера дозволяє там лише кілька розмірів). */
    val highSpeedResolution: CameraResolution?,

    /** Максимальне збільшення, яке пропонується користувачу. */
    val maxZoom: Double,

    val supportsAutoFocus: Boolean,

    /** Чи має ця камера спалах (ліхтарик). Зазвичай є лише в задньої камери. */
    val supportsFlash: Boolean,

    /** Чи вміє камера фокусуватись у вказаній точці (AF-регіони). */
    val supportsTapToFocus: Boolean,
) {
    val minZoom: Double get() = 1.0
}
