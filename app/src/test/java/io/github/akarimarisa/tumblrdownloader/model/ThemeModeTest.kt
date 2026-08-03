package io.github.akarimarisa.tumblrdownloader.model

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [ThemeMode] / [ThemeModePrefs] — the follow-system / light / dark
 * theme preference backing the dark mode setting.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*ThemeModeTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class ThemeModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // ── fromPrefValue ────────────────────────────────────────────────

    @Test
    fun fromPrefValue_null_returnsSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue(null))
    }

    @Test
    fun fromPrefValue_validValues_mapCorrectly() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPrefValue("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromPrefValue("dark"))
    }

    @Test
    fun fromPrefValue_unknownValue_fallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue("bogus"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue(""))
    }

    // ── nightMode mapping ────────────────────────────────────────────

    @Test
    fun nightMode_mapsToAppCompatModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.nightMode)
    }

    // ── prefs round-trip ─────────────────────────────────────────────

    @Test
    fun prefs_defaultIsSystem() {
        val mode = ThemeModePrefs.read(app)
        assertEquals(ThemeMode.SYSTEM, mode)
    }

    @Test
    fun prefs_setAndRead_roundTrip() {
        ThemeModePrefs.set(app, ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemeModePrefs.read(app))
        ThemeModePrefs.set(app, ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemeModePrefs.read(app))
        ThemeModePrefs.set(app, ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, ThemeModePrefs.read(app))
    }
}
