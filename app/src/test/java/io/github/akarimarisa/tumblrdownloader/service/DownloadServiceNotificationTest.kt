package io.github.akarimarisa.tumblrdownloader.service

import android.app.Notification
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for the notification action buttons (pause / resume / cancel).
 *
 * `buildNotification` is exercised directly (it's `internal` for testability)
 * because Robolectric's ShadowNotificationManager does not capture notifications
 * posted through the service's own `notificationManager` field.
 *
 * Verifies:
 * - DOWNLOADING / QUEUED notifications show Pause + Cancel actions
 * - PAUSED notifications show Resume + Cancel actions
 * - COMPLETED notifications show no actions
 * - Each action's PendingIntent targets the right service action and extras
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

    @Test
    fun downloading_notification_showsPauseAndCancel() {
        val notification = buildFor(item("notif-dl"))

        val actions = notification.actions ?: emptyArray()
        assertEquals("downloading should show 2 actions", 2, actions.size)
        assertEquals(
            "pause action should point to ACTION_PAUSE",
            DownloadService.ACTION_PAUSE,
            actionIntents(notification)[0].action
        )
        assertEquals(
            "cancel action should point to ACTION_REMOVE",
            DownloadService.ACTION_REMOVE,
            actionIntents(notification)[1].action
        )
    }

    @Test
    fun queued_notification_showsPauseAndCancel() {
        val notification = buildFor(item("notif-queued", status = DownloadStatus.QUEUED))

        val actions = notification.actions ?: emptyArray()
        assertEquals("queued should show 2 actions", 2, actions.size)
        assertEquals(
            DownloadService.ACTION_PAUSE,
            actionIntents(notification)[0].action
        )
        assertEquals(
            DownloadService.ACTION_REMOVE,
            actionIntents(notification)[1].action
        )
    }

    @Test
    fun downloading_notification_pauseAction_targetsActionPause() {
        val notification = buildFor(item("notif-pause-target"))
        val savedIntents = actionIntents(notification)

        assertEquals(
            "pause action should point to ACTION_PAUSE",
            DownloadService.ACTION_PAUSE,
            savedIntents[0].action
        )
        assertEquals(
            "pause action should carry the item id",
            "notif-pause-target",
            savedIntents[0].getStringExtra(DownloadService.EXTRA_ITEM_ID)
        )
    }

    @Test
    fun downloading_notification_cancelAction_targetsActionRemove() {
        val notification = buildFor(item("notif-remove-target"))
        val savedIntents = actionIntents(notification)

        assertEquals(
            "cancel action should point to ACTION_REMOVE",
            DownloadService.ACTION_REMOVE,
            savedIntents[1].action
        )
        assertEquals(
            "cancel action should carry the item id",
            "notif-remove-target",
            savedIntents[1].getStringExtra(DownloadService.EXTRA_ITEM_ID)
        )
    }

    @Test
    fun paused_notification_showsResumeAndCancel() {
        val notification = buildFor(item("notif-paused", status = DownloadStatus.PAUSED, progress = 40))

        val actions = notification.actions ?: emptyArray()
        assertEquals("paused should show 2 actions", 2, actions.size)
        assertEquals(
            "resume action should point to ACTION_START",
            DownloadService.ACTION_START,
            actionIntents(notification)[0].action
        )
        assertEquals(
            "cancel action should point to ACTION_REMOVE",
            DownloadService.ACTION_REMOVE,
            actionIntents(notification)[1].action
        )
    }

    @Test
    fun paused_notification_resumeAction_targetsActionStart() {
        val notification = buildFor(item(
            "notif-resume-target",
            status = DownloadStatus.PAUSED,
            progress = 40,
            downloadedBytes = 2048L,
            downloadFileUri = "content://media/test/notif-resume-target.jpg"
        ))
        val savedIntents = actionIntents(notification)

        assertEquals(
            "resume action should point to ACTION_START",
            DownloadService.ACTION_START,
            savedIntents[0].action
        )
        assertEquals(
            "resume action should carry the item id",
            "notif-resume-target",
            savedIntents[0].getStringExtra(DownloadService.EXTRA_ITEM_ID)
        )
        assertEquals(
            "resume action should carry the media url",
            "https://media.tumblr.com/notif-resume-target.jpg",
            savedIntents[0].getStringExtra(DownloadService.EXTRA_MEDIA_URL)
        )
        assertEquals(
            "resume action should carry the resume offset",
            2048L,
            savedIntents[0].getLongExtra(DownloadService.EXTRA_DOWNLOADED_BYTES, 0L)
        )
    }

    @Test
    fun failed_notification_showsPauseAndCancel() {
        val notification = buildFor(item("notif-failed", status = DownloadStatus.FAILED))

        val actions = notification.actions ?: emptyArray()
        assertEquals("failed should show 2 actions", 2, actions.size)
        assertEquals(
            DownloadService.ACTION_PAUSE,
            actionIntents(notification)[0].action
        )
        assertEquals(
            DownloadService.ACTION_REMOVE,
            actionIntents(notification)[1].action
        )
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
