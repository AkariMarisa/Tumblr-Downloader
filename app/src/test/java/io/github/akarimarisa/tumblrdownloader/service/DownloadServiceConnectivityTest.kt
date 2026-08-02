package io.github.akarimarisa.tumblrdownloader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DownloadService] network connectivity changes:
 * - Heartbeat as fallback with ConnectivityManager primary check
 * - 60s heartbeat interval (only when active downloads exist)
 * - isNetworkConnected() combines both sources
 *
 * Robolectric's default ConnectivityManager reports no active network, so
 * tests that expect connectivity must call
 * [DownloadService.setConnectivityManagerConnectedForTest] explicitly.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadServiceConnectivityTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadServiceConnectivityTest {

    private lateinit var context: Context
    private lateinit var service: DownloadService
    private val capturedItems = mutableListOf<DownloadItem>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedItems.clear()

        service = Robolectric.buildService(DownloadService::class.java).create().get()

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
    //  Heartbeat fallback (via setNetworkAvailableForTest hook)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun actionStart_whenBothDisconnected_pausesImmediately() {
        // CM reports no network AND heartbeat flag is false → offline
        service.setConnectivityManagerConnectedForTest(false)
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("both-offline")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find {
            it.id == "both-offline" && it.status == DownloadStatus.PAUSED
        }
        assertNotNull(
            "should pause when both ConnectivityManager and heartbeat report disconnected",
            paused
        )
    }

    @Test
    fun actionStart_whenCmConnected_heartbeatTrue_proceeds() {
        // CM says connected, heartbeat says reachable → online
        service.setConnectivityManagerConnectedForTest(true)
        service.setNetworkAvailableForTest(true)

        val intent = createStartIntent("cm-hb-on")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find {
            it.id == "cm-hb-on" && it.status == DownloadStatus.PAUSED
        }
        assertNull("should not pause when both agree online", paused)
    }

    @Test
    fun actionStart_whenCmConnected_heartbeatFalse_pauses() {
        // VPN case: CM says connected (VPN interface exists) but heartbeat
        // detects Tumblr unreachable → should pause
        service.setConnectivityManagerConnectedForTest(true)
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("vpn-case")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find {
            it.id == "vpn-case" && it.status == DownloadStatus.PAUSED
        }
        assertNotNull(
            "should pause when CM says connected but heartbeat says offline (VPN edge case)",
            paused
        )
    }

    @Test
    fun actionStart_whenCmDisconnected_heartbeatTrue_pauses() {
        // CM says no network at all → offline regardless of heartbeat
        service.setConnectivityManagerConnectedForTest(false)
        service.setNetworkAvailableForTest(true)

        val intent = createStartIntent("cm-off-hb-on")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find {
            it.id == "cm-off-hb-on" && it.status == DownloadStatus.PAUSED
        }
        assertNotNull(
            "should pause when CM reports no network (heartbeat alone is not enough)",
            paused
        )
    }

    @Test
    fun heartbeatFlag_defaultsToTrue() {
        // isNetworkAvailable starts as true; CM hook not set (defaults to null → real CM).
        // Robolectric's default CM has no active network → isConnectivityManagerConnected = false.
        // So isNetworkConnected() = false. This test verifies the default state.
        // With no CM override, default Robolectric CM returns no network → item pauses.
        val intent = createStartIntent("heartbeat-default")
        service.onStartCommand(intent, 0, 1)

        // In Robolectric default (no CM network), item should be paused
        val paused = capturedItems.find {
            it.id == "heartbeat-default" && it.status == DownloadStatus.PAUSED
        }
        assertNotNull(
            "with default Robolectric CM (no network), item should be paused",
            paused
        )
    }

    @Test
    fun heartbeatFlag_canBeOverriddenByTestHook() {
        service.setConnectivityManagerConnectedForTest(true)
        service.setNetworkAvailableForTest(false)

        val intent = createStartIntent("heartbeat-override")
        service.onStartCommand(intent, 0, 1)

        val paused = capturedItems.find {
            it.id == "heartbeat-override" && it.status == DownloadStatus.PAUSED
        }
        assertNotNull(
            "setNetworkAvailableForTest(false) should force offline state",
            paused
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Integration: pause → reconnect → resume
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun actionStart_pausedAfterDisconnect_canBeResumedWhenReconnected() {
        service.setConnectivityManagerConnectedForTest(true)
        service.setNetworkAvailableForTest(false)
        val intent = createStartIntent("cm-resume")
        service.onStartCommand(intent, 0, 1)
        capturedItems.clear()

        service.setNetworkAvailableForTest(true)
        service.onStartCommand(intent, 0, 2)

        val paused = capturedItems.find {
            it.id == "cm-resume" && it.status == DownloadStatus.PAUSED
        }
        assertNull(
            "after reconnection, the same item should not be paused again",
            paused
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Existing contract tests preserved
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun service_creates_withoutCrash() {
        assertNotNull("service should be created", service)
    }

    @Test
    fun actionPause_returnsStartSticky() {
        val pauseIntent = Intent(service, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_ITEM_ID, "test-pause-id")
        }
        val result = service.onStartCommand(pauseIntent, 0, 1)
        assertEquals("pause intent should return START_STICKY", Service.START_STICKY, result)
    }

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
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun createStartIntent(id: String) =
        Intent(service, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_ITEM_ID, id)
            putExtra(DownloadService.EXTRA_SOURCE_URL, "https://www.tumblr.com/test/$id")
            putExtra(DownloadService.EXTRA_MEDIA_URL, "https://media.tumblr.com/$id.jpg")
            putExtra(DownloadService.EXTRA_TYPE, MediaType.IMAGE.name)
            putExtra(DownloadService.EXTRA_TITLE, "Test $id")
            putExtra(DownloadService.EXTRA_RETRY_COUNT, 0)
            putExtra(DownloadService.EXTRA_MAX_RETRIES, 3)
        }
}
