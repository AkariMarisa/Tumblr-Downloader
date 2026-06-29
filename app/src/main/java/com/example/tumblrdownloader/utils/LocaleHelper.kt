package com.example.tumblrdownloader.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANG = "language_code"

    /** Returns the persisted locale, or null if the user chose system default. */
    fun getPersistedLocale(context: Context): Locale? {
        val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null)
        return if (code != null) Locale.forLanguageTag(code) else null
    }

    /** Persists the user's choice. Pass null to clear (revert to system default). */
    fun persistLocale(context: Context, locale: Locale?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, locale?.toLanguageTag())
            .apply()
    }

    /** Wrap a context with the given locale as the configuration locale. */
    fun wrapContext(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createConfigurationContext(config)
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context
    }

    /** Apply the persisted locale on the given context (for use in attachBaseContext).
     *  Returns the original context when no override is saved (system default). */
    fun applyToContext(context: Context): Context {
        val locale = getPersistedLocale(context)
        return if (locale != null) wrapContext(context, locale) else context
    }
}
