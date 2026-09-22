package io.github.akarimarisa.tumblrdownloader.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.service.DownloadService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for issue #24 — downloads stuck at "0%" while the file
 * completes in the background.
 *
 * Root cause: DownloadService delivered progress through a single static
 * [DownloadService.progressListener] slot that was claimed at ViewModel
 * construction time.  When the task was restored with SettingsActivity on
 * top of the back stack, Settings' ViewModel claimed the slot first, the
 * recreated MainActivity skipped registration (slot already taken), and
 * Settings' ViewModel cleared the slot on teardown — leaving the download
 * list permanently without progress events.
 *
 * The fix: MainActivity claims the slot in onResume() via
 * [MainViewModel.claimServiceProgressListener], so registration no longer
 * depends on construction order.  SettingsActivity's ViewModel never
 * claims, so the download list stays live even while Settings is on top.
 *
 * Additionally, all MainViewModel instances now share one process-wide
 * [io.github.akarimarisa.tumblrdownloader.model.DownloadStateManager]
 * (see [DownloadStateManager.getInstance]) so the single state owner is
 * structural, not accidental.
 *
 * These tests exercise the claim/release semantics directly (an activity
 * launch is not used because Robolectric cannot inflate this project's
 * view-binding layouts).
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*MainViewModelProgressListenerTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainViewModelProgressListenerTest {

    @Before
    fun setUp() {
        DownloadService.progressListener = null
    }

    @After
    fun tearDown() {
        DownloadService.progressListener = null
    }

    private fun dummyListener(label: String) = object : DownloadService.ProgressListener {
        override fun onDownloadUpdate(item: DownloadItem) = Unit
        override fun toString(): String = "dummy($label)"
    }

    private fun createMainViewModel(): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
    }

    @Test
    fun `claiming the slot wins even when another view model holds it`() {
        // Simulates the SettingsActivity ViewModel holding the slot after
        // the task was restored with Settings on top of the back stack.
        val settingsVm = dummyListener("settings")
        DownloadService.progressListener = settingsVm

        val mainVm = createMainViewModel()
        mainVm.claimServiceProgressListener()

        assertSame(mainVm.stateManager.serviceProgressListener, DownloadService.progressListener)
        assertNotSame(settingsVm, DownloadService.progressListener)
    }

    @Test
    fun `clearing the owner view model releases the slot`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val mainVm = ViewModelProvider(
            store, ViewModelProvider.AndroidViewModelFactory(app)
        )[MainViewModel::class.java]
        mainVm.claimServiceProgressListener()
        assertNotNull(DownloadService.progressListener)

        store.clear() // triggers MainViewModel.onCleared()

        assertNull(DownloadService.progressListener)
    }

    @Test
    fun `all main view model instances share one state manager`() {
        // Both MainActivity and SettingsActivity construct their own
        // MainViewModel — they must back onto the SAME process-wide
        // DownloadStateManager, otherwise the second instance would
        // restore + re-persist its own snapshot alongside the live one.
        val mainVm = createMainViewModel()
        val settingsVm = createMainViewModel()

        assertSame(mainVm.stateManager, settingsVm.stateManager)
    }
}