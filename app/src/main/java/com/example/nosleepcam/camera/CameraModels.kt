package com.example.nosleepcam.camera

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formats a number with a pattern (e.g. "0.#") using the device language's separator; the midpoint rounds up, as in .NET. */
internal fun formatNumber(value: Double, pattern: String): String =
    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.getDefault()))
        .apply { roundingMode = RoundingMode.HALF_UP }
        .format(value)

/** A preview frame size supported by the camera. */
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

    /** Text for the picker list: "4000×3000 · 12.0 MP · 4:3"; [megapixelUnit] is the localized "MP". */
    fun displayName(megapixelUnit: String): String =
        "$width×$height · ${formatNumber(megapixels, "0.0")} $megapixelUnit · $aspectRatio"

    /** Stable key for saving in settings: "4000x3000". */
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

/** One physical/logical camera of the device (back, front, etc.). */
data class CameraDescriptor(val id: String, val name: String)

/** Capabilities of the opened camera: what can be shown to the user in the UI. */
class CameraCapabilities(
    val camera: CameraDescriptor,
    val availableCameras: List<CameraDescriptor>,

    /** Supported resolutions, from the largest to the smallest. */
    val resolutions: List<CameraResolution>,

    /**
     * Highest frame rate (fps) for each resolution according to the camera itself; the key is [CameraResolution.key].
     * If the camera did not report it, there is no entry. This is a ceiling, not a guarantee: in low light auto-exposure lowers the rate.
     */
    val frameRates: Map<String, Double>,

    /** Highest frame rate in high-speed mode (Camera2 constrained high-speed), e.g. 120; 0 — not supported. */
    val highSpeedFps: Int,

    /** Frame size in high-speed mode (the camera allows only a few sizes there). */
    val highSpeedResolution: CameraResolution?,

    /** Maximum magnification offered to the user. */
    val maxZoom: Double,

    val supportsAutoFocus: Boolean,

    /** Whether this camera has a flash (torch). Usually only the back camera does. */
    val supportsFlash: Boolean,

    /** Whether the camera can focus at a given point (AF regions). */
    val supportsTapToFocus: Boolean,
) {
    val minZoom: Double get() = 1.0
}
