package io.github.akarimarisa.tumblrdownloader.utils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `自动下载全部媒体文件` switch defaults to off (the picker stays on) and
 * must survive being toggled from the settings screen.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*AutoDownloadPrefsTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class AutoDownloadPrefsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AutoDownloadPrefs.write(app, false)
    }

    @Test
    fun missingKeyFallsBackToThePicker() {
        app.getSharedPreferences(AutoDownloadPrefs.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .remove(AutoDownloadPrefs.KEY_AUTO_DOWNLOAD_ALL)
            .apply()

        assertFalse(
            "an unset preference must keep the pre-download picker enabled",
            AutoDownloadPrefs.read(app)
        )
    }

    @Test
    fun writeThenRead_roundTrips() {
        AutoDownloadPrefs.write(app, true)
        assertTrue("enabling must be picked up by the parse path", AutoDownloadPrefs.read(app))

        AutoDownloadPrefs.write(app, false)
        assertFalse(AutoDownloadPrefs.read(app))
    }
}
