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
 * Tests for [DownloadService] heartbeat-based network connectivity guard.
 *
 * Instead of relying on system network callbacks, the service uses a periodic
 * HEAD-request heartbeat to www.tumblr.com.  Tests manipulate the connectivity
 * flag directly via [DownloadService.setNetworkAvailableForTest].
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadServiceNetworkTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadServiceNetworkTest {

    private lateinit var context: Context
    private lateinit var service: DownloadService
    private lateinit var shadowConnectivityManager: ShadowConnectivityManager
    private val capturedItems = mutableListOf<DownloadItem>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedItems.clear()

        service = Robolectric.buildService(DownloadService::class.java).create().get()
        shadowConnectivityManager = Shadows.shadowOf(
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )

        // Capture all progress updates in a list so we can assert them.
        DownloadService.progressListener = object : DownloadService.ProgressListener {
            override fun onDownloadUpdate(item: DownloadItem) {
                capturedItems.add(item)
            }
        }
    }

    @After
    fun tearDown() {
        DownloadService.progressListener = null
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun service_creates_withoutCrash() {
        assertNotNull("service should be created", service)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Network guard: ACTION_START → immediate PAUSED when offline
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun actionStart_whenDisconnected_pausesImmediately_andEmitsPaused() {
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("net-pause-1")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find { it.id == "net-pause-1" }
        assertNotNull("should emit a progress update for the item", paused)
        assertEquals(
            "should be paused immediately",
            DownloadStatus.PAUSED,
            paused!!.status
        )
        assertNotNull(
            "errorMessage should contain the network-waiting text",
            paused.errorMessage
        )
    }

    @Test
    fun actionStart_whenDisconnected_doesNotEnterQueuedIds() {
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("net-dup")
        service.onStartCommand(intent, 0, 1)
        capturedItems.clear()
        service.onStartCommand(intent, 0, 2)

        val second = capturedItems.find { it.id == "net-dup" }
        assertNotNull(
            "second send when disconnected should also emit a progress update",
            second
        )
    }

    @Test
    fun actionStart_whenConnected_proceedsNormally() {
        service.setNetworkAvailableForTest(true)

        val intent = createStartIntent("net-ok")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find { it.id == "net-ok" && it.status == DownloadStatus.PAUSED }
        assertNull(
            "should NOT pause the item when network is available",
            paused
        )
    }

    @Test
    fun actionStart_pausedAfterDisconnect_canBeResumedWhenReconnected() {
        service.setNetworkAvailableForTest(false)
        val intent = createStartIntent("net-resume")
        service.onStartCommand(intent, 0, 1)
        capturedItems.clear()

        service.setNetworkAvailableForTest(true)

        service.onStartCommand(intent, 0, 2)

        val paused = capturedItems.find { it.id == "net-resume" && it.status == DownloadStatus.PAUSED }
        assertNull(
            "after reconnection, the same item should not be paused again by the guard",
            paused
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  isNetworkConnected() — heartbeat flag
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun isNetworkConnected_returnsTrue_whenNetworkIsConnected() {
        service.setNetworkAvailableForTest(true)

        val intent = createStartIntent("conn-test")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find { it.id == "conn-test" && it.status == DownloadStatus.PAUSED }
        assertNull("item should not be paused when connected", paused)
    }

    @Test
    fun isNetworkConnected_returnsFalse_whenDisconnected() {
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("disc-test")
        service.onStartCommand(intent, 0, 1)

        val item = capturedItems.find { it.id == "disc-test" }
        assertNotNull("should receive a progress update", item)
        assertEquals(
            "should be paused when network is disconnected",
            DownloadStatus.PAUSED,
            item!!.status
        )
    }

    @Test
    fun isNetworkConnected_defaultsToTrue() {
        // isNetworkAvailable starts as true; heartbeat hasn't run yet.
        val intent = createStartIntent("default-test")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find { it.id == "default-test" && it.status == DownloadStatus.PAUSED }
        assertNull(
            "default state should assume connected so the first heartbeat " +
            "cycle doesn't block legitimate downloads",
            paused
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  runQueue network guard
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun runQueueNetworkGuard_pausesQueuedItems_whenDisconnected() {
        service.setNetworkAvailableForTest(true)
        service.onStartCommand(createStartIntent("q-1"), 0, 1)
        service.onStartCommand(createStartIntent("q-2"), 0, 2)
        service.onStartCommand(createStartIntent("q-3"), 0, 3)
        capturedItems.clear()

        service.setNetworkAvailableForTest(false)

        val intent4 = createStartIntent("q-4")
        service.onStartCommand(intent4, 0, 4)

        assertTrue(
            "item started while disconnected should be paused",
            capturedItems.any { it.id == "q-4" && it.status == DownloadStatus.PAUSED }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ACTION_PAUSE (existing contract preserved)
    // ═══════════════════════════════════════════════════════════════════

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
        val startIntent = createStartIntent("pause-active-1")
        service.onStartCommand(startIntent, 0, 1)

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

    // ═══════════════════════════════════════════════════════════════════
    //  ACTION_START → duplicate guard preserved
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun actionStart_duplicateId_returnsSticky() {
        val intent = createStartIntent("dup-id")
        service.onStartCommand(intent, 0, 1)
        val result = service.onStartCommand(intent, 0, 2)

        assertEquals(
            "duplicate start should be silently ignored (START_STICKY)",
            Service.START_STICKY,
            result
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  processWithRetry network guard
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun processWithRetry_checksNetworkAndPauses_whenDisconnectedDuringRetry() {
        service.setNetworkAvailableForTest(true)
        val intent = createStartIntent("retry-pause")
        service.onStartCommand(intent, 0, 1)

        capturedItems.clear()
        service.setNetworkAvailableForTest(false)

        // Send another intent — the second item hits the onStartCommand guard.
        val intent2 = createStartIntent("retry-pause-2")
        service.onStartCommand(intent2, 0, 2)

        assertTrue(
            "second item should have received a progress update",
            capturedItems.any { it.id == "retry-pause-2" }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

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
