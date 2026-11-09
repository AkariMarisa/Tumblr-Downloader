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
import io.github.akarimarisa.tumblrdownloader.utils.CompletedMediaStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import io.github.akarimarisa.tumblrdownloader.utils.ParsedTumblrMedia

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
        stateManager = DownloadStateManager.forTest(app)
        // Keep persistence on the test scheduler so advanceUntilIdle observes
        // the write (issue #24).
        stateManager.persistIo = testDispatcher
    }

    @After
    fun tearDown() {
        stateManager.persistIo = Dispatchers.IO
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

    // ── pause all ──────────────────────────────────────────────────────

    @Test
    fun pauseAll_preservesInFlightProgress() = runTest(testDispatcher) {
        // An in-flight download at 60% must keep showing 60% after
        // pause-all — zeroing it would lie to the user (issue #24 audit).
        val running = item("p", status = DownloadStatus.DOWNLOADING).copy(progress = 60)
        val queued = item("q", status = DownloadStatus.QUEUED)
        stateManager.restore(listOf(running, queued))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pauseAll()
        testDispatcher.scheduler.advanceUntilIdle()

        val paused = stateManager.items.value.find { it.id == "p" }
        assertEquals(DownloadStatus.PAUSED, paused?.status)
        assertEquals(
            "in-flight progress must be preserved across pause-all",
            60,
            paused?.progress
        )
    }

    // ── singleton (issue #24 audit) ────────────────────────────────────

    @Test
    fun getInstance_returnsTheSameInstance() = runTest(testDispatcher) {
        // Exactly one state owner per process — a second instance would
        // restore + re-persist its own snapshot and split the state.
        val first = DownloadStateManager.getInstance(app)
        val second = DownloadStateManager.getInstance(app)
        assertSame("process must have a single DownloadStateManager", first, second)
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

    @Test
    fun clearAll_keepsCompletedBlogBatchItems() = runTest(testDispatcher) {
        // Blog batch items back their card's full-download record. "Clear
        // completed" must only act on standalone downloads — otherwise it
        // hollows out the blog detail while leaving the card dangling.
        stateManager.restore(listOf(
            item("solo", status = DownloadStatus.COMPLETED),
            item("batchDone", status = DownloadStatus.COMPLETED, batchId = "b1", blogName = "b1"),
            item("batchPaused", status = DownloadStatus.PAUSED, batchId = "b1", blogName = "b1")
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.clearAll()
        testDispatcher.scheduler.advanceUntilIdle()

        val remaining = stateManager.items.value
        assertFalse("standalone completed item should be removed", remaining.any { it.id == "solo" })
        assertTrue("completed batch item must be kept", remaining.any { it.id == "batchDone" })
        assertTrue("paused batch item must be kept", remaining.any { it.id == "batchPaused" })
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
        // max=3: when a task completes, queued tasks should be started automatically
        // until the concurrent slots are full. This is the key behavior of the
        // "queue stayed effectively serial after batch add" fix.
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 3).commit()

        // 4 queued tasks + 1 currently downloading task
        val queued = listOf(
            item("q1"), item("q2"), item("q3"), item("q4")
        )
        val running = item("r0", status = DownloadStatus.DOWNLOADING)
        stateManager.restore(listOf(running) + queued)
        testDispatcher.scheduler.advanceUntilIdle()

        // simulate the running task completing
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
        // the rest should stay QUEUED
        assertEquals(1, statuses.count { it.second == DownloadStatus.QUEUED })
    }

    @Test
    fun completion_respectsMaxConcurrentBound() = runTest(testDispatcher) {
        // max=2: with 2 already downloading, completing one can only start 1 more.
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 2).commit()

        val queued = listOf(
            item("a1"), item("a2"), item("a3"), item("a4")
        )
        val running1 = item("b1", status = DownloadStatus.DOWNLOADING)
        val running2 = item("b2", status = DownloadStatus.DOWNLOADING)
        stateManager.restore(listOf(running1, running2) + queued)
        testDispatcher.scheduler.advanceUntilIdle()

        // b1 completes -> 1 slot frees -> exactly 1 queued task should start
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
    fun batchItem_progressUpdate_withoutBatchId_keepsBatchMetadata() = runTest(testDispatcher) {
        // Regression: the service rebuilds its DownloadItem from ACTION_START
        // intent extras, which carry no batchId/blogName. A progress update for
        // a downloading blog item must NOT wipe the row's batchId, otherwise a
        // blog download silently leaks into the standalone main list (the
        // "blog tasks show mixed with single downloads" complaint).
        val batchItem = item("b1", status = DownloadStatus.DOWNLOADING, batchId = "job-z", blogName = "z")
        stateManager.restore(listOf(batchItem))
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate the service's progress callback — no batchId, no blogName.
        stateManager.serviceProgressListener.onDownloadUpdate(
            DownloadItem(
                id = "b1",
                sourceUrl = batchItem.sourceUrl,
                mediaUrl = batchItem.mediaUrl,
                title = batchItem.title,
                type = MediaType.IMAGE,
                status = DownloadStatus.DOWNLOADING,
                downloadedBytes = 4096,
                progress = 10,
                retryCount = 0,
                maxRetries = 3
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = stateManager.items.value.find { it.id == "b1" }
        assertEquals(
            "blog item must keep its batchId after service progress",
            "job-z",
            updated?.batchId
        )
        assertEquals("blog item must keep its blogName after service progress", "z", updated?.blogName)
    }

    @Test
    fun appendWhileGloballyPaused_chainContinuesAfterEachCompletion() = runTest(testDispatcher) {
        // max=1: two fresh appends made while the queue is globally paused.
        // The first starts immediately; when it completes, the SECOND must
        // start too — new downloads must keep flowing after a pause, not stall
        // behind the (paused) older rows.
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 1).commit()

        val pausedOld = item("old1", status = DownloadStatus.PAUSED, batchId = "job-o", blogName = "o")
        stateManager.restore(listOf(pausedOld))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pauseAll()
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.enqueueBlogAfterParse(
            listOf(
                ParsedTumblrMedia(
                    sourceUrl = "https://www.tumblr.com/post/9",
                    mediaUrl = "https://media.tumblr.com/new-1.jpg",
                    title = "new-1",
                    type = MediaType.IMAGE
                ),
                ParsedTumblrMedia(
                    sourceUrl = "https://www.tumblr.com/post/10",
                    mediaUrl = "https://media.tumblr.com/new-2.jpg",
                    title = "new-2",
                    type = MediaType.IMAGE
                )
            ),
            batchId = "job-n",
            blogName = "n"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        fun statusOfUrl(url: String) = stateManager.items.value.firstOrNull { it.mediaUrl == url }?.status
        assertEquals("first fresh append starts despite global pause", DownloadStatus.DOWNLOADING, statusOfUrl("https://media.tumblr.com/new-1.jpg"))
        assertEquals("second fresh append waits for the single slot", DownloadStatus.QUEUED, statusOfUrl("https://media.tumblr.com/new-2.jpg"))

        // Simulate the first completing (service update carries no batchId).
        val firstId = stateManager.items.value.first { it.mediaUrl == "https://media.tumblr.com/new-1.jpg" }.id
        stateManager.serviceProgressListener.onDownloadUpdate(
            DownloadItem(
                id = firstId,
                sourceUrl = "https://www.tumblr.com/post/9",
                mediaUrl = "https://media.tumblr.com/new-1.jpg",
                title = "new-1",
                type = MediaType.IMAGE,
                status = DownloadStatus.COMPLETED,
                progress = 100,
                retryCount = 0,
                maxRetries = 3
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("second fresh append starts after the first completes", DownloadStatus.DOWNLOADING, statusOfUrl("https://media.tumblr.com/new-2.jpg"))
        assertEquals(
            "old paused row must stay paused while new downloads flow",
            DownloadStatus.PAUSED,
            statusOfUrl("https://media.tumblr.com/old1.jpg")
        )
        assertEquals(
            "newer appends keep their batchId",
            "job-n",
            stateManager.items.value.firstOrNull { it.mediaUrl == "https://media.tumblr.com/new-2.jpg" }?.batchId
        )
    }

    @Test
    fun resumeAll_startsMultipleUpToMaxConcurrent() = runTest(testDispatcher) {
        // max=3: resumeAll should start queued tasks in a loop until the concurrent
        // slots are full, instead of starting only one (the old effectively
        // serial behavior).
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

    // ── blog batch queue ───────────────────────────────────────────────

    @Test
    fun pauseBatch_pausesOnlyBatchItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(
            item("b1", status = DownloadStatus.DOWNLOADING, batchId = "job-x", blogName = "x"),
            item("b2", status = DownloadStatus.QUEUED, batchId = "job-x", blogName = "x"),
            item("b3", status = DownloadStatus.COMPLETED, batchId = "job-x", blogName = "x"),
            item("s1", status = DownloadStatus.DOWNLOADING)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pauseBatch("job-x")
        testDispatcher.scheduler.advanceUntilIdle()

        val items = stateManager.items.value.associateBy { it.id }
        assertEquals("downloading batch item paused", DownloadStatus.PAUSED, items["b1"]?.status)
        assertEquals("queued batch item paused", DownloadStatus.PAUSED, items["b2"]?.status)
        assertEquals("completed batch item untouched", DownloadStatus.COMPLETED, items["b3"]?.status)
        assertEquals("standalone item untouched", DownloadStatus.DOWNLOADING, items["s1"]?.status)
    }

    @Test
    fun pauseBatch_unknownBatch_doesNothing() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("z1", status = DownloadStatus.DOWNLOADING)))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pauseBatch("nope")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.DOWNLOADING, stateManager.items.value.single().status)
    }

    @Test
    fun resumeBatch_restartsPausedBatchItemsUpToConcurrencyCap() = runTest(testDispatcher) {
        val prefs = app.getSharedPreferences("tumblr_downloader", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrent_downloads", 2).commit()

        stateManager.restore(listOf(
            item("r1", status = DownloadStatus.PAUSED, batchId = "job-y", blogName = "y"),
            item("r2", status = DownloadStatus.PAUSED, batchId = "job-y", blogName = "y"),
            item("r3", status = DownloadStatus.PAUSED, batchId = "job-y", blogName = "y")
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.resumeBatch("job-y")
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        assertEquals(2, statuses.count { it.second == DownloadStatus.DOWNLOADING })
        assertEquals(1, statuses.count { it.second == DownloadStatus.QUEUED })
    }

    @Test
    fun resumeBatch_ignoresOtherBatchItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(
            item("x1", status = DownloadStatus.PAUSED, batchId = "job-a", blogName = "a"),
            item("x2", status = DownloadStatus.PAUSED, batchId = "job-b", blogName = "b")
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.resumeBatch("job-a")
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        assertEquals(
            "job-a resumes",
            DownloadStatus.DOWNLOADING,
            statuses.find { it.first == "x1" }?.second
        )
        assertEquals("job-b stays paused", DownloadStatus.PAUSED, statuses.find { it.first == "x2" }?.second)
    }

    @Test
    fun resumeBatch_retriesFailedBatchItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(
            item("f1", status = DownloadStatus.FAILED, batchId = "job-r", blogName = "r"),
            item("p2", status = DownloadStatus.PAUSED, batchId = "job-r", blogName = "r"),
            item("d3", status = DownloadStatus.COMPLETED, batchId = "job-r", blogName = "r"),
            item("f4", status = DownloadStatus.FAILED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.resumeBatch("job-r")
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = stateManager.items.value.map { it.id to it.status }
        assertTrue(
            "failed batch item must be retried on resume",
            statuses.any { it.first == "f1" && it.second != DownloadStatus.FAILED }
        )
        assertTrue("paused batch item must resume", statuses.any { it.first == "p2" && it.second != DownloadStatus.PAUSED })
        assertTrue("completed batch item is untouched", statuses.any { it.first == "d3" && it.second == DownloadStatus.COMPLETED })
        assertEquals(
            "standalone failed item is not touched by the batch resume",
            DownloadStatus.FAILED,
            statuses.find { it.first == "f4" }?.second
        )
    }

    @Test
    fun removeBatch_removesOnlyUnfinishedBatchItems() = runTest(testDispatcher) {
        stateManager.restore(listOf(
            item("m1", status = DownloadStatus.PAUSED, batchId = "job-z", blogName = "z"),
            item("m2", status = DownloadStatus.QUEUED, batchId = "job-z", blogName = "z"),
            item("m3", status = DownloadStatus.COMPLETED, batchId = "job-z", blogName = "z"),
            item("s2", status = DownloadStatus.QUEUED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.removeBatch("job-z")
        testDispatcher.scheduler.advanceUntilIdle()

        val ids = stateManager.items.value.map { it.id }
        assertFalse("paused batch item removed", "m1" in ids)
        assertFalse("queued batch item removed", "m2" in ids)
        assertTrue("completed batch item kept", "m3" in ids)
        assertTrue("standalone item kept", "s2" in ids)
    }

    @Test
    fun removeBatch_unknownBatch_doesNothing() = runTest(testDispatcher) {
        stateManager.restore(listOf(item("k2")))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.removeBatch("nope")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, stateManager.items.value.size)
    }

    @Test
    fun appendWhileGloballyPaused_startsImmediately() = runTest(testDispatcher) {
        // Regression for "pause downloads, then add a new download": a fresh
        // append must start right away instead of sitting at QUEUED forever.
        // The old guard treated every append as paused too, which wedged the
        // queue behind the paused rows (nothing ever finished → nothing ever
        // started).  DownloadService accepts every explicit ACTION_START, so
        // starting the append is safe (no foreground-service crash).
        stateManager.restore(listOf(
            item("d1", status = DownloadStatus.DOWNLOADING),
            item("d2", status = DownloadStatus.QUEUED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.pauseAll()
        testDispatcher.scheduler.advanceUntilIdle()

        stateManager.enqueueBlogAfterParse(
            listOf(
                ParsedTumblrMedia(
                    sourceUrl = "https://www.tumblr.com/post/1",
                    mediaUrl = "https://media.tumblr.com/append-paused.jpg",
                    title = "x",
                    type = MediaType.IMAGE
                )
            ),
            batchId = "job-a",
            blogName = "a"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val appended = stateManager.items.value.find { it.batchId == "job-a" }
        assertNotNull("new append should still be added", appended)
        assertEquals(
            "a fresh append must start even while the queue is globally paused",
            DownloadStatus.DOWNLOADING,
            appended?.status
        )
        // The pre-existing paused rows must have stayed paused.
        assertEquals(DownloadStatus.PAUSED, stateManager.items.value.find { it.id == "d1" }?.status)
        assertEquals(DownloadStatus.PAUSED, stateManager.items.value.find { it.id == "d2" }?.status)
    }

    // ── issue #24: finished downloads must not read as 0% ─────────────

    @Test
    fun completedProgress_isPersistedImmediately() = runTest(testDispatcher) {
        // A download that finishes inside the 5s progress-persist window used
        // to have its terminal COMPLETED/100 snapshot swallowed by the
        // throttle: the process then restarted from the last (0%) snapshot.
        stateManager.restore(listOf(item("c1", status = DownloadStatus.DOWNLOADING)))
        testDispatcher.scheduler.advanceUntilIdle()
        val writesAfterRestore = stateManager.persistWriteCount

        // A mid-download progress event inside the throttle window is a
        // low-frequency snapshot only — it must not force a write.
        stateManager.serviceProgressListener.onDownloadUpdate(
            item("c1").copy(status = DownloadStatus.DOWNLOADING, progress = 40)
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "progress events inside the throttle window stay a snapshot",
            writesAfterRestore,
            stateManager.persistWriteCount
        )

        // The terminal snapshot, however, must be written right away.
        stateManager.serviceProgressListener.onDownloadUpdate(
            item("c1").copy(status = DownloadStatus.COMPLETED, progress = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, stateManager.items.value.single().status)
        assertEquals(100, stateManager.items.value.single().progress)

        // DownloadHistoryStore.save hops to Dispatchers.IO internally, so the
        // write lands on a real thread: drive the test scheduler in bounded
        // polls until it completes. Without the completion-bypass branch this
        // count never rises and the assertion fails after the poll budget.
        var polls = 0
        while (stateManager.persistWriteCount <= writesAfterRestore && polls < 100) {
            Thread.sleep(20)
            testDispatcher.scheduler.advanceUntilIdle()
            polls++
        }
        assertTrue(
            "COMPLETED must bypass the progress-persist throttle",
            stateManager.persistWriteCount > writesAfterRestore
        )
    }

    @Test
    fun restore_promotesItemsWhoseFileIsAlreadyOnDisk() = runTest(testDispatcher) {
        // Issue #24: the terminal COMPLETED callback is dropped when the
        // service's listener slot is empty during a process/UI rebuild, so the
        // persisted row keeps an old snapshot (FAILED/0%) while the file is
        // written.  Restore must trust the disk (CompletedMediaStore).
        val lost = item("lost", status = DownloadStatus.FAILED)
        val untouched = item("untouched", status = DownloadStatus.FAILED)
        CompletedMediaStore.markCompleted(app, lost.mediaUrl)

        stateManager.restore(listOf(lost, untouched))
        testDispatcher.scheduler.advanceUntilIdle()

        val restored = stateManager.items.value.associateBy { it.id }
        assertEquals(
            "a file already on disk is COMPLETED, whatever the history said",
            DownloadStatus.COMPLETED,
            restored["lost"]?.status
        )
        assertEquals(100, restored["lost"]?.progress)
        assertNull(restored["lost"]?.errorMessage)
        assertEquals(
            "items without a completed marker are left alone",
            DownloadStatus.FAILED,
            restored["untouched"]?.status
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    // ── pre-download picker ────────────────────────────────────────────

    @Test
    fun mediaPreview_dedupesExistingAndDuplicateCandidates() = runTest(testDispatcher) {
        val existingUrl = "https://64.media.tumblr.com/preview-existing.jpg"
        stateManager.restore(listOf(item("existing", mediaUrl = existingUrl)))
        testDispatcher.scheduler.advanceUntilIdle()

        val first = "https://64.media.tumblr.com/preview-first.jpg"
        val second = "https://64.media.tumblr.com/preview-second.jpg"
        stateManager.offerMediaPreviewForTest(
            listOf(
                media(existingUrl),
                media(first),
                media(second),
                media(first)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val request = stateManager.mediaPreview.value
        assertNotNull("multiple fresh items must open the picker", request)
        assertEquals(2, request!!.items.size)
        assertEquals(
            listOf(first, second).map { DownloadUtils.normalizeMediaIdentity(it) },
            request.items.map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }
        )
    }

    @Test
    fun mediaPreview_singleFreshItemEnqueuesWithoutPicker() = runTest(testDispatcher) {
        val url = "https://64.media.tumblr.com/preview-single-${java.util.UUID.randomUUID()}.jpg"
        stateManager.offerMediaPreviewForTest(listOf(media(url)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("a single item must not need a confirmation screen", stateManager.mediaPreview.value)
        assertTrue(
            "the single item must be queued directly",
            stateManager.items.value.any {
                DownloadUtils.normalizeMediaIdentity(it.mediaUrl) ==
                    DownloadUtils.normalizeMediaIdentity(url)
            }
        )
    }

    @Test
    fun confirmMediaPreview_enqueuesOnlySelectedIndices() = runTest(testDispatcher) {
        val urls = (1..3).map {
            "https://64.media.tumblr.com/preview-confirm-$it-${java.util.UUID.randomUUID()}.jpg"
        }
        stateManager.offerMediaPreviewForTest(urls.map(::media))
        testDispatcher.scheduler.advanceUntilIdle()

        val request = stateManager.mediaPreview.value!!
        stateManager.confirmMediaPreview(request.id, setOf(0, 2))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("confirming must close the picker", stateManager.mediaPreview.value)
        val queued = stateManager.items.value.map {
            DownloadUtils.normalizeMediaIdentity(it.mediaUrl)
        }.toSet()
        assertTrue(queued.contains(DownloadUtils.normalizeMediaIdentity(urls[0])))
        assertTrue(queued.contains(DownloadUtils.normalizeMediaIdentity(urls[2])))
        assertFalse(
            "unselected media must not be queued",
            queued.contains(DownloadUtils.normalizeMediaIdentity(urls[1]))
        )
    }

    @Test
    fun cancelMediaPreview_queuesNothingAndClosesPicker() = runTest(testDispatcher) {
        val urls = (1..2).map {
            "https://64.media.tumblr.com/preview-cancel-$it-${java.util.UUID.randomUUID()}.jpg"
        }
        stateManager.offerMediaPreviewForTest(urls.map(::media))
        testDispatcher.scheduler.advanceUntilIdle()

        val request = stateManager.mediaPreview.value!!
        stateManager.cancelMediaPreview(request.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(stateManager.mediaPreview.value)
        assertTrue("skipping must queue nothing", stateManager.items.value.isEmpty())
    }

    @Test
    fun cancelMediaPreview_ignoresStaleId() = runTest(testDispatcher) {
        val urls = (1..2).map {
            "https://64.media.tumblr.com/preview-stale-$it-${java.util.UUID.randomUUID()}.jpg"
        }
        stateManager.offerMediaPreviewForTest(urls.map(::media))
        testDispatcher.scheduler.advanceUntilIdle()

        val request = stateManager.mediaPreview.value!!
        stateManager.cancelMediaPreview("not-the-current-id")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "a stale dialog must not dismiss the live picker",
            request.id,
            stateManager.mediaPreview.value?.id
        )
    }

    private fun media(mediaUrl: String) = ParsedTumblrMedia(
        sourceUrl = "https://www.tumblr.com/blog/test/${mediaUrl.hashCode()}",
        mediaUrl = mediaUrl,
        title = "Preview",
        type = MediaType.IMAGE
    )

    private fun item(
        id: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        mediaUrl: String = "https://media.tumblr.com/$id.jpg",
        retryCount: Int = 0,
        batchId: String? = null,
        blogName: String? = null
    ) = DownloadItem(
        id = id,
        sourceUrl = "https://www.tumblr.com/blog/$id",
        mediaUrl = mediaUrl,
        title = "Test $id",
        type = MediaType.IMAGE,
        status = status,
        retryCount = retryCount,
        maxRetries = 3,
        batchId = batchId,
        blogName = blogName
    )
}
