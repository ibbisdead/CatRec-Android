package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Handles locale persistence and context wrapping so that:
 * - The chosen language survives process restarts (via SharedPreferences, which is
 *   synchronously readable inside attachBaseContext).
 * - All stringResource / getString calls in both Compose and XML layouts pick up
 *   the correct values-xx strings after an Activity recreate.
 *
 * Usage:
 *   MainActivity.attachBaseContext  → LocaleHelper.wrap(base)
 *   Language changed in Settings   → LocaleHelper.persist(ctx, code) then Activity.recreate()
 */
object LocaleHelper {

    private const val PREFS = "catrec_locale"
    private const val KEY   = "language_code"

    /** Save the language code to SharedPreferences for synchronous access later. */
    fun persist(context: Context, languageCode: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, languageCode)
            .apply()
    }

    /** Read the saved language code (never null; defaults to "system"). */
    fun getSaved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "system") ?: "system"

    /**
     * Wrap [base] with a context that has the saved locale applied to its Configuration.
     * Call this from Activity.attachBaseContext so every resource lookup uses the right locale.
     */
    fun wrap(base: Context): Context {
        val code = getSaved(base)
        if (code.isBlank() || code.equals("system", ignoreCase = true)) return base

        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Apply a new language immediately and persist it.
     * Call this when the user picks a language in Settings, then call Activity.recreate().
     */
    fun apply(context: Context, languageCode: String) {
        persist(context, languageCode)

        // AppCompatDelegate handles the per-app locale on API 33+ natively;
        // on older versions it stores the preference and we rely on attachBaseContext.
        val localeList = if (languageCode.equals("system", ignoreCase = true)) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
