package io.github.akarimarisa.tumblrdownloader.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [DownloadStateManager] pause / resume / remove state transitions.
 *
 * These cover the state management logic that underpins the network-aware
 * auto-pause/resume feature.  The actual network callback integration is
 * tested in [DownloadServiceNetworkTest].
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadStateManagerTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class DownloadStateManagerTest {

    private lateinit var app: Application
    private lateinit var stateManager: DownloadStateManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        stateManager = DownloadStateManager(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── pause ───────────────────────────────────────────────────────────

    @Test
    fun pause_changesDownloadingToPaused() = runTest(testDispatcher) {
        val item = item("a")
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("a")
        testDispatcher.scheduler.advanceUntilIdle()

        // Sanity: item is now DOWNLOADING
        assertEquals(
            DownloadStatus.DOWNLOADING,
            stateManager.items.value.find { it.id == "a" }?.status
        )

        // When: paused
        stateManager.pause("a")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: state is PAUSED
        assertEquals(
            DownloadStatus.PAUSED,
            stateManager.items.value.find { it.id == "a" }?.status
        )
    }

    @Test
    fun pause_completedItem_doesNothing() = runTest(testDispatcher) {
        val item = item("b", status = DownloadStatus.COMPLETED)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pause("b")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.COMPLETED,
            stateManager.items.value.find { it.id == "b" }?.status
        )
    }

    @Test
    fun pause_alreadyPaused_doesNothing() = runTest(testDispatcher) {
        val item = item("c", status = DownloadStatus.PAUSED)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pause("c")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.PAUSED,
            stateManager.items.value.find { it.id == "c" }?.status
        )
    }

    // ── start / resume ───────────────────────────────────────────────────

    @Test
    fun startOrResume_queuedItem_changesToDownloading() = runTest(testDispatcher) {
        val item = item("d")
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("d")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.DOWNLOADING,
            stateManager.items.value.find { it.id == "d" }?.status
        )
    }

    @Test
    fun startOrResume_pausedItem_changesToDownloading() = runTest(testDispatcher) {
        val item = item("e", status = DownloadStatus.PAUSED)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("e")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.DOWNLOADING,
            stateManager.items.value.find { it.id == "e" }?.status
        )
    }

    @Test
    fun startOrResume_completedItem_doesNothing() = runTest(testDispatcher) {
        val item = item("f", status = DownloadStatus.COMPLETED)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("f")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.COMPLETED,
            stateManager.items.value.find { it.id == "f" }?.status
        )
    }

    @Test
    fun startOrResume_downloadingItem_doesNothing() = runTest(testDispatcher) {
        val item = item("g", status = DownloadStatus.DOWNLOADING)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("g")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DownloadStatus.DOWNLOADING,
            stateManager.items.value.find { it.id == "g" }?.status
        )
    }

    @Test
    fun startOrResume_failedItem_resetsRetryCount() = runTest(testDispatcher) {
        val item = item("h", status = DownloadStatus.FAILED, retryCount = 3)
        stateManager.restore(listOf(item))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.startOrResume("h")
        testDispatcher.scheduler.advanceUntilIdle()

        val result = stateManager.items.value.find { it.id == "h" }
        assertEquals(DownloadStatus.DOWNLOADING, result?.status)
        assertEquals("retryCount should reset on failed→start", 0, result?.retryCount)
    }

    // ── remove ──────────────────────────────────────────────────────────

    @Test
    fun remove_deletesItem() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("i"), item("j")))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.remove("i")
        testDispatcher.scheduler.advanceUntilIdle()

        val ids = stateManager.items.value.map { it.id }
        assertFalse("removed item should not be present", "i" in ids)
        assertTrue("other items should remain", "j" in ids)
    }

    @Test
    fun remove_nonexistentItem_doesNothing() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("k")))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.remove("nonexistent")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, stateManager.items.value.size)
    }

    // ── clearAll ────────────────────────────────────────────────────────

    @Test
    fun clearAll_removesOnlyCompletedItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(
            item("m", status = DownloadStatus.COMPLETED),
            item("n", status = DownloadStatus.PAUSED),
            item("o", status = DownloadStatus.FAILED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.clearAll()
        testDispatcher.scheduler.advanceUntilIdle()

        val remaining = stateManager.items.value
        assertFalse("completed item should be removed", remaining.any { it.id == "m" })
        assertTrue("paused item should remain", remaining.any { it.id == "n" })
        assertTrue("failed item should remain", remaining.any { it.id == "o" })
    }

    // ── restore ─────────────────────────────────────────────────────────

    @Test
    fun restore_mergesWithExistingItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("p", status = DownloadStatus.COMPLETED)))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.restore(listOf(item("q", status = DownloadStatus.FAILED)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, stateManager.items.value.size)
    }

    @Test
    fun restore_doesNotDuplicateByMediaUrl() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("r", mediaUrl = "https://media.tumblr.com/dup.jpg")))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.restore(listOf(item("s", mediaUrl = "https://media.tumblr.com/dup.jpg")))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "duplicate mediaUrl should be skipped",
            1,
            stateManager.items.value.size
        )
    }

    // ── parallel start (max concurrent) ──────────────────────────────────

    @Test
    fun completion_fillsConcurrentSlots() = runTest(testDispatcher) {
        // max=3：一个任务完成后，应自动启动排队任务直到并发槽位满。
        // 这是修复 "批量添加后实际串行" 的关键行为。
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 3).commit()

        // 4 个排队任务 + 1 个正在下载的任务
        val queued = listOf(
            item("q1"), item("q2"), item("q3"), item("q4")
        )
        val running = item("r0", status = DownloadStatus.DOWNLOADING)
        stateManager.restore(listOf(running) + queued)
        testDispatcher.scheduler.advanceUntilIdle()

        // 模拟正在下载的任务完成
        stateManager.serviceProgressListener.onDownloadUpdate(
            running.copy(status = DownloadStatus.COMPLETED, progress = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        val downloading = statuses.count { it.second == DownloadStatus.DOWNLOADING }
        assertEquals(
            "completion should fill up to max=3 concurrent slots",
            3,
            downloading
        )
        // 剩余的应保持 QUEUED
        assertEquals(1, statuses.count { it.second == DownloadStatus.QUEUED })
    }

    @Test
    fun completion_respectsMaxConcurrentBound() = runTest(testDispatcher) {
        // max=2：已有 2 个下载中，完成其中一个后只能再启动 1 个。
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 2).commit()

        val queued = listOf(
            item("a1"), item("a2"), item("a3"), item("a4")
        )
        val running1 = item("b1", status = DownloadStatus.DOWNLOADING)
        val running2 = item("b2", status = DownloadStatus.DOWNLOADING)
        stateManager.restore(listOf(running1, running2) + queued)
        testDispatcher.scheduler.advanceUntilIdle()

        // b1 完成 → 槽位空出 1 个 → 应启动 1 个排队任务
        stateManager.serviceProgressListener.onDownloadUpdate(
            running1.copy(status = DownloadStatus.COMPLETED, progress = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        assertEquals(
            "running must never exceed max=2",
            2,
            statuses.count { it.second == DownloadStatus.DOWNLOADING }
        )
        assertEquals(3, statuses.count { it.second == DownloadStatus.QUEUED })
    }

    @Test
    fun resumeAll_startsMultipleUpToMaxConcurrent() = runTest(testDispatcher) {
        // max=3：resumeAll 应循环启动排队任务直到并发槽位满，
        // 而不是只启动一个（旧实现的实际串行行为）。
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 3).commit()

        val paused = listOf(
            item("p1", status = DownloadStatus.PAUSED),
            item("p2", status = DownloadStatus.PAUSED),
            item("p3", status = DownloadStatus.PAUSED),
            item("p4", status = DownloadStatus.PAUSED),
            item("p5", status = DownloadStatus.PAUSED)
        )
        stateManager.restore(paused)
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.resumeAll()
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        assertEquals(
            "resumeAll should fill up to max=3 concurrent slots",
            3,
            statuses.count { it.second == DownloadStatus.DOWNLOADING }
        )
        assertEquals(2, statuses.count { it.second == DownloadStatus.QUEUED })
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun item(
        id: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        mediaUrl: String = "https://media.tumblr.com/$id.jpg",
        retryCount: Int = 0
    ) = DownloadItem(
        id = id,
        sourceUrl = "https://www.tumblr.com/blog/$id",
        mediaUrl = mediaUrl,
        title = "Test $id",
        type = MediaType.IMAGE,
        status = status,
        retryCount = retryCount,
        maxRetries = 3
    )
}
