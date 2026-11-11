package io.github.akarimarisa.tumblrdownloader.ui.download

import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import io.github.akarimarisa.tumblrdownloader.model.BlogJobState
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure card logic behind three field reports:
 *
 *  - #8 the bar stayed at 0% while a large blog parsed and only moved after
 *        Pause;
 *  - #9 the remove button only existed while paused (now always visible —
 *        asserted here so it cannot regress silently);
 *  - #10 a re-added blog under-reported (46 completed while 86 files existed).
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*BlogCardLogicTest*"`
 */
class BlogCardLogicTest {

    private fun job(
        state: BlogJobState = BlogJobState.RUNNING,
        totalPosts: Int? = null,
        jobId: String = "job-1",
        blogName: String = "kthingz"
    ) = BlogDownloadJobEntity(
        jobId = jobId,
        blogName = blogName,
        state = state.name,
        resumeLink = null,
        totalPosts = totalPosts,
        pagesCompleted = 0,
        postsDiscovered = 0,
        postsSkipped = 0,
        postsNoMedia = 0,
        mediaEnqueued = 0,
        createdAt = 0L,
        updatedAt = 0L,
        errorCode = null
    )

    private fun ui(
        active: Boolean,
        completed: Int,
        total: Int,
        job: BlogDownloadJobEntity = job()
    ) = BlogCardUi(
        job = job,
        active = active,
        running = false,
        paused = false,
        completed = completed,
        total = total
    )

    private fun item(
        id: String,
        batchId: String?,
        blogName: String?,
        status: DownloadStatus = DownloadStatus.COMPLETED
    ) = DownloadItem(
        id = id,
        sourceUrl = "https://www.tumblr.com/post/$id",
        mediaUrl = "https://64.media.tumblr.com/$id.jpg",
        title = "$id.jpg",
        type = MediaType.IMAGE,
        status = status,
        batchId = batchId,
        blogName = blogName
    )

    // ── #8: progress while parsing + downloading at the same time ──────────

    @Test
    fun mediaProgressWinsWhileTheScanIsStillRunning() {
        // 46 of 86 downloaded, scan still active: the bar must show 53%, not
        // the posts-seen percent (which is 0 for a big blog).
        val progress = blogCardProgress(ui(active = true, completed = 46, total = 86), postsSeen = 3)
        assertEquals(53, progress.percent)
        assertFalse(progress.indeterminate)
    }

    @Test
    fun scanProgressIsIndeterminateUntilTotalPostsIsKnown() {
        val progress = blogCardProgress(ui(active = true, completed = 0, total = 0), postsSeen = 0)
        assertTrue("unknown totalPosts must show the spinner", progress.indeterminate)
    }

    @Test
    fun scanProgressCountsPostsWhenNothingIsTrackedYet() {
        val progress = blogCardProgress(
            ui(active = true, completed = 0, total = 0, job = job(totalPosts = 200)),
            postsSeen = 40
        )
        assertEquals(20, progress.percent)
        assertFalse(progress.indeterminate)
    }

    @Test
    fun activeCardNeverClaimsCompletionEvenWhenEveryKnownRowIsDone() {
        val progress = blogCardProgress(ui(active = true, completed = 10, total = 10), postsSeen = 5)
        assertEquals("the scan is still running", 99, progress.percent)
    }

    @Test
    fun finishedCardWithEverythingDownloadedShowsHundred() {
        val progress = blogCardProgress(
            ui(active = false, completed = 86, total = 86, job = job(state = BlogJobState.DONE)),
            postsSeen = 86
        )
        assertEquals(100, progress.percent)
        assertFalse(progress.indeterminate)
    }

    // ── #10: counting rows kept by an earlier card of the same blog ────────

    @Test
    fun batchCountsFilesKeptFromAnEarlierCardOfTheSameBlog() {
        val orphans = (1..40).map { item("old-$it", batchId = "old-job", blogName = "kthingz") }
        val current = (1..46).map { item("new-$it", batchId = "job-1", blogName = "kthingz") }

        val batch = blogBatchOf(orphans + current, jobId = "job-1", blogName = "kthingz")

        assertEquals("86 files exist on disk, so the card must count 86", 86, batch.size)
        assertEquals(86, batch.count { it.status == DownloadStatus.COMPLETED })
    }

    @Test
    fun batchIgnoresOtherBlogsAndStandaloneRows() {
        val items = listOf(
            item("a", batchId = "job-1", blogName = "kthingz"),
            item("b", batchId = "other-job", blogName = "other-blog"),
            item("c", batchId = null, blogName = null)
        )

        val batch = blogBatchOf(items, jobId = "job-1", blogName = "kthingz")

        assertEquals(listOf("a"), batch.map { it.id })
    }

    @Test
    fun batchFallsBackToTheJobIdWhenTheBlogNameIsMissing() {
        val items = listOf(
            item("a", batchId = "job-1", blogName = null),
            item("b", batchId = "other-job", blogName = null)
        )

        assertEquals(1, blogBatchOf(items, jobId = "job-1", blogName = null).size)
    }
}
