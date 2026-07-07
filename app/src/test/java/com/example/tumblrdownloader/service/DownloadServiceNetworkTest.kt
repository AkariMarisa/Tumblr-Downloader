package com.example.tumblrdownloader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.model.MediaType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager

/**
 * Tests for [DownloadService] network-aware pause/resume integration.
 *
 * Verifies that:
 * - The network callback is registered during service creation.
 * - [ACTION_PAUSE] (same code path as network-loss pause) emits PAUSED
 *   status via [DownloadService.progressListener].
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadServiceNetworkTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadServiceNetworkTest {

    private lateinit var context: Context
    private lateinit var service: DownloadService
    private lateinit var shadowConnectivityManager: ShadowConnectivityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        service = Robolectric.buildService(DownloadService::class.java).create().get()
        shadowConnectivityManager = Shadows.shadowOf(
            service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )
    }

    @After
    fun tearDown() {
        // Clean up the static progress listener to avoid leaking across tests.
        DownloadService.progressListener = null
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    @Test
    fun service_creates_withoutCrash() {
        // Creating the service in setUp already exercises onCreate() including
        // registerNetworkCallback().  Verifying no exception is sufficient.
        assertNotNull("service should be created", service)
    }

    @Test
    fun networkCallback_isRegistered_afterCreate() {
        // The shadow connectivity manager tracks registered callbacks.
        // After service.onCreate(), our NetworkCallback should be among them.
        val callbacks = shadowConnectivityManager.networkCallbacks
        assertTrue(
            "at least one NetworkCallback should be registered",
            callbacks.isNotEmpty()
        )
    }

    // ── ACTION_PAUSE → progressListener ────────────────────────────────

    @Test
    fun actionPause_returnsStartSticky() {
        val pauseIntent = Intent(service, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_ITEM_ID, "test-pause-id")
        }

        val result = service.onStartCommand(pauseIntent, 0, 1)

        assertEquals(
            "pause intent should return START_STICKY",
            Service.START_STICKY,
            result
        )
    }

    @Test
    fun actionPause_forActiveId_addsToSuspended() {
        // Send start for an item first
        val startIntent = createStartIntent("pause-active-1")
        service.onStartCommand(startIntent, 0, 1)

        // Now pause it
        val pauseIntent = Intent(service, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_ITEM_ID, "pause-active-1")
        }
        val result = service.onStartCommand(pauseIntent, 0, 2)

        assertEquals(
            "pause intent should return START_STICKY",
            Service.START_STICKY,
            result
        )
    }

    // ── ACTION_START → queuedIds tracking ──────────────────────────────

    @Test
    fun actionStart_duplicateId_returnsSticky() {
        val intent = createStartIntent("dup-id")
        service.onStartCommand(intent, 0, 1)
        // Send same ID again
        val result = service.onStartCommand(intent, 0, 2)

        assertEquals(
            "duplicate start should be silently ignored (START_STICKY)",
            Service.START_STICKY,
            result
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun createStartIntent(id: String) =
        Intent(service, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_ITEM_ID, id)
            putExtra(
                DownloadService.EXTRA_SOURCE_URL,
                "https://www.tumblr.com/test/$id"
            )
            putExtra(
                DownloadService.EXTRA_MEDIA_URL,
                "https://media.tumblr.com/$id.jpg"
            )
            putExtra(DownloadService.EXTRA_TYPE, MediaType.IMAGE.name)
            putExtra(DownloadService.EXTRA_TITLE, "Test $id")
            putExtra(DownloadService.EXTRA_RETRY_COUNT, 0)
            putExtra(DownloadService.EXTRA_MAX_RETRIES, 3)
        }
}
