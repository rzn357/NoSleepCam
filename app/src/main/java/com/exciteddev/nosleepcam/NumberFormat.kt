package com.exciteddev.nosleepcam

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formatters of one thread for one language; a [DecimalFormat] is expensive to create and not thread-safe. */
private class FormatCache(
    val locale: Locale,
) {
    val formats = HashMap<String, DecimalFormat>()
}

private val formatCache = ThreadLocal<FormatCache>()

/**
 * Formats a number with a pattern (e.g. "0.#") using the device language's separator; the midpoint rounds up.
 * Formatters are cached per thread (the zoom label is drawn on every frame) and dropped when the device language changes.
 */
internal fun formatNumber(
    value: Double,
    pattern: String,
): String {
    val locale = Locale.getDefault()
    val cache = formatCache.get()?.takeIf { it.locale == locale } ?: FormatCache(locale).also { formatCache.set(it) }
    return cache.formats
        .getOrPut(pattern) {
            DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }
        }.format(value)
}
