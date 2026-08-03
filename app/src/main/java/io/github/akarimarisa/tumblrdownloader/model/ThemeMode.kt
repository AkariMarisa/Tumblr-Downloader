package io.github.akarimarisa.tumblrdownloader.model

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.github.akarimarisa.tumblrdownloader.R

/**
 * App theme mode: follow system, force light, or force dark.
 *
 * Stored in SharedPreferences so it survives restarts and is applied in
 * [TumblrDownloaderApplication.onCreate] via [AppCompatDelegate].
 */
enum class ThemeMode(val prefValue: String, val nightMode: Int, val labelRes: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

    companion object {
        fun fromPrefValue(value: String?): ThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}

object ThemeModePrefs {

    const val PREFS_NAME = "tumblr_downloader"
    const val PREF_THEME_MODE = "theme_mode"

    fun read(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromPrefValue(prefs.getString(PREF_THEME_MODE, null))
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_THEME_MODE, mode.prefValue).apply()
    }
}
