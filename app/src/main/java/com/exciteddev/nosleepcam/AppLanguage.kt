package com.exciteddev.nosleepcam

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The app language: until the user picks one in the settings, the phone's system language is used when the app is
 * translated into it, and English otherwise. The choice is stored in [Prefs] as a language tag ("en", "uk");
 * null means "not chosen yet".
 *
 * Android 10 and older have no per-app language, so the chosen locale is applied by wrapping the base context
 * ([wrap], called from attachBaseContext) and by setting the default [Locale] (used for number formatting).
 * With the system choice nothing is wrapped, so startup costs nothing extra.
 */
internal object AppLanguage {

    /** Languages the app is translated into: language tag → name in its own language. */
    val SUPPORTED: List<Pair<String, String>> = listOf("en" to "English", "uk" to "Українська")

    /** The language in use: the user's choice, else the system language if supported, else English. */
    fun effectiveTag(base: Context): String {
        val chosen = Prefs.readLanguage(base)
        if (chosen != null && SUPPORTED.any { it.first == chosen }) return chosen
        return systemLocale().language.takeIf { language -> SUPPORTED.any { it.first == language } } ?: DEFAULT
    }

    /** Returns [base] with the language in use applied (or [base] itself when that is the system language). */
    fun wrap(base: Context): Context {
        val tag = effectiveTag(base)
        val system = systemLocale()
        if (Prefs.readLanguage(base) == null && tag == system.language) {
            // Nothing chosen and the system language is supported: drop any locale set earlier and keep the base.
            if (Locale.getDefault() != system) Locale.setDefault(system)
            return base
        }

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Used when the system language is not one of [SUPPORTED]. */
    private const val DEFAULT = "en"

    /** The language the system is set to now (ignores our own override). */
    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]
}
