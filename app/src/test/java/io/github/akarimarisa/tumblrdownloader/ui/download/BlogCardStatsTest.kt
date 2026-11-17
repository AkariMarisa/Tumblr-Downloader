package io.github.akarimarisa.tumblrdownloader.ui.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The card's stats line must show total posts, scanned posts and enqueued
 * media together (#17) — and must not print a bogus total before the feed has
 * reported one.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*BlogCardStatsTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class BlogCardStatsTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun job(totalPosts: Int?, mediaEnqueued: Int = 1671) = BlogDownloadJobEntity(
        jobId = "job-1",
        blogName = "kthingz",
        state = "RUNNING",
        resumeLink = null,
        totalPosts = totalPosts,
        pagesCompleted = 0,
        postsDiscovered = 0,
        postsSkipped = 0,
        postsNoMedia = 0,
        mediaEnqueued = mediaEnqueued,
        createdAt = 0L,
        updatedAt = 0L,
        errorCode = null
    )

    @Test
    fun showsTotalScannedAndMediaTogether() {
        assertEquals(
            "454/4743 posts scanned · 1671 media",
            blogCardStatsLabel(ctx, job(totalPosts = 4743), postsSeen = 454).toString()
        )
    }

    @Test
    fun omitsTheTotalWhileTheFeedHasNotReportedOne() {
        assertEquals(
            "454 posts scanned · 1671 media",
            blogCardStatsLabel(ctx, job(totalPosts = null), postsSeen = 454).toString()
        )
        assertEquals(
            "454 posts scanned · 1671 media",
            blogCardStatsLabel(ctx, job(totalPosts = 0), postsSeen = 454).toString()
        )
    }

    @Test
    fun usesTheSingularFormForOnePost() {
        assertEquals(
            "1/4743 post scanned · 1 media",
            blogCardStatsLabel(ctx, job(totalPosts = 4743, mediaEnqueued = 1), postsSeen = 1).toString()
        )
    }
}
