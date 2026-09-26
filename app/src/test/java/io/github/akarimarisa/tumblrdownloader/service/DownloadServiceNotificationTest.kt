package io.github.akarimarisa.tumblrdownloader.service

import android.app.Notification
import android.content.Intent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for the notification action buttons.
 *
 * `buildNotification` is exercised directly (it's `internal` for testability)
 * because Robolectric's ShadowNotificationManager does not capture notifications
 * posted through the service's own `notificationManager` field.
 *
 * Issue #24 follow-up: the notification deliberately has EXACTLY ONE action —
 * "Stop all" (ACTION_PAUSE_ALL) — for every unfinished state. It pauses all
 * queued/in-flight downloads at once. Per-item pause/resume/retry buttons and
 * any remove/cancel button are gone; state transitions are driven from the app
 * UI (DownloadStateManager).
 *
 * Verifies:
 * - DOWNLOADING / QUEUED / PAUSED / FAILED notifications show exactly one
 *   action, and it always points to ACTION_PAUSE_ALL
 * - the Stop-all action carries no per-item extras
 * - there is never an ACTION_REMOVE / ACTION_PAUSE / ACTION_START button
 * - COMPLETED / null-item notifications show no actions
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadServiceNotificationTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadServiceNotificationTest {

    private lateinit var service: DownloadService

    @Before
    fun setUp() {
        service = Robolectric.buildService(DownloadService::class.java).create().get()
        service.setConnectivityManagerConnectedForTest(true)
        service.setNetworkAvailableForTest(true)
    }

    @After
    fun tearDown() {
        DownloadService.progressListener = null
    }

    private fun buildFor(item: DownloadItem): Notification =
        service.buildNotification(item, item.title, "status", item.progress, null)

    private fun actionIntents(notification: Notification): List<Intent> {
        return notification.actions
            ?.mapNotNull { action ->
                runCatching {
                    shadowOf(action.actionIntent).savedIntent
                }.getOrNull()
            }
            ?: emptyList()
    }

    private fun item(
        id: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        progress: Int = 0,
        downloadedBytes: Long = 0L,
        downloadFileUri: String? = null
    ) = DownloadItem(
        id = id,
        sourceUrl = "https://www.tumblr.com/test/$id",
        mediaUrl = "https://media.tumblr.com/$id.jpg",
        title = "Test $id",
        type = MediaType.IMAGE,
        status = status,
        progress = progress,
        downloadedBytes = downloadedBytes,
        downloadFileUri = downloadFileUri,
        retryCount = 0,
        maxRetries = 3
    )

    private val unfinishedStatuses = listOf(
        DownloadStatus.DOWNLOADING,
        DownloadStatus.QUEUED,
        DownloadStatus.PAUSED,
        DownloadStatus.FAILED
    )

    @Test
    fun everyUnfinishedState_showsExactlyOneStopAllAction() {
        for (status in unfinishedStatuses) {
            val notification = buildFor(item("notif-$status", status = status, progress = 40))
            val actions = notification.actions ?: emptyArray()
            assertEquals("$status should show exactly 1 action", 1, actions.size)
            assertEquals(
                "$status should show the stop-all (ACTION_PAUSE_ALL) action",
                DownloadService.ACTION_PAUSE_ALL,
                actionIntents(notification)[0].action
            )
        }
    }

    @Test
    fun stopAllAction_carriesNoPerItemExtras() {
        val notification = buildFor(item("notif-stopall", progress = 60))
        val intent = actionIntents(notification)[0]

        assertEquals(DownloadService.ACTION_PAUSE_ALL, intent.action)
        assertNull(
            "stop-all must be global — no item id",
            intent.getStringExtra(DownloadService.EXTRA_ITEM_ID)
        )
        assertNull(
            "stop-all must be global — no media url",
            intent.getStringExtra(DownloadService.EXTRA_MEDIA_URL)
        )
    }

    @Test
    fun notification_hasNoRemoveOrPerItemActionsForAnyUnfinishedState() {
        // Issue #24: a notification "cancel" used to send ACTION_REMOVE (which
        // zeroed the item's progress) and per-item pause/resume/retry buttons
        // no longer exist either — only the global stop-all may be offered.
        for (status in unfinishedStatuses) {
            val notification = buildFor(item("notif-$status", status = status, progress = 40))
            val intents = actionIntents(notification)
            assertTrue(
                "$status notification must never offer ACTION_REMOVE (bug #24)",
                intents.none { it.action == DownloadService.ACTION_REMOVE }
            )
            assertTrue(
                "$status notification must never offer per-item ACTION_PAUSE",
                intents.none { it.action == DownloadService.ACTION_PAUSE }
            )
            assertTrue(
                "$status notification must never offer per-item ACTION_START (resume/retry)",
                intents.none { it.action == DownloadService.ACTION_START }
            )
        }
    }

    @Test
    fun completed_notification_showsNoActions() {
        val notification = buildFor(item("notif-completed", status = DownloadStatus.COMPLETED, progress = 100))

        val actions = notification.actions
        assertTrue(
            "completed notification should have no actions",
            actions == null || actions.isEmpty()
        )
    }

    @Test
    fun nullItem_notification_showsNoActions() {
        val notification = service.buildNotification(null, "title", "content", 0, null)

        val actions = notification.actions
        assertTrue(
            "null item notification should have no actions",
            actions == null || actions.isEmpty()
        )
    }
}