package io.github.akarimarisa.tumblrdownloader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.service.DownloadService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The clipboard dedupe key (`last_auto_detected_url`) decides whether a link
 * still sitting in the clipboard is re-processed when the app regains focus.
 *
 * Deleting a row and clearing the history used to wipe it, so a stale blog
 * link was re-parsed on the next focus change — with the auto-download switch
 * on, that silently restarted a whole-blog scan the user had already removed
 * (#20). Only Settings → 清空自动检测 cache may reset it.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*MainViewModelClipboardDedupeTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelClipboardDedupeTest {

    private lateinit var app: Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: MainViewModel

    private val link = "https://www.tumblr.com/ginkobrief"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DownloadService.progressListener = null
        prefs().edit().putString(LAST_AUTO_URL, link).apply()
        store = ViewModelStore()
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))
            .get(MainViewModel::class.java)
    }

    @After
    fun tearDown() {
        store.clear()
        DownloadService.progressListener = null
        prefs().edit().remove(LAST_AUTO_URL).apply()
    }

    private fun prefs() =
        app.getSharedPreferences("tumblr_downloader", Context.MODE_PRIVATE)

    @Test
    fun removingARowKeepsTheClipboardDedupeKey() {
        vm.removeDownload("row-that-is-not-in-the-queue")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "deleting a row must not make the clipboard link detectable again (#20)",
            link,
            prefs().getString(LAST_AUTO_URL, null)
        )
    }

    @Test
    fun clearingHistoryKeepsTheClipboardDedupeKey() {
        vm.clearAllDownloads()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "clearing completed downloads must not reset clipboard dedupe (#20)",
            link,
            prefs().getString(LAST_AUTO_URL, null)
        )
    }

    companion object {
        private const val LAST_AUTO_URL = "last_auto_detected_url"
    }
}
