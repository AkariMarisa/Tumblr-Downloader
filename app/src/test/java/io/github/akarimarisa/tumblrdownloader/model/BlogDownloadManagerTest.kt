package io.github.akarimarisa.tumblrdownloader.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.utils.BlogDownloadJobStore
import io.github.akarimarisa.tumblrdownloader.utils.BlogFeedParser
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Orchestration tests for [BlogDownloadManager] against a fake feed gateway.
 *
 * The manager's worker runs on the injected test dispatcher (virtual time),
 * but the Room-backed stores still hop to the real IO dispatcher, so a small
 * poll loop stitches the two together. The scan interval is forced to 0 and
 * the long-blog threshold to "unlimited" so tests are fast and gate-free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BlogDownloadManagerTest {

    private lateinit var app: Application
    private lateinit var stateManager: DownloadStateManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        stateManager = DownloadStateManager.forTest(app)
        // Fast scans, no confirm gate for the main tests.
        BlogPrefs.prefs(app).edit()
            .putLong(BlogPrefs.PREF_SCAN_INTERVAL_MS, 0L)
            .putInt(BlogPrefs.PREF_LONG_THRESHOLD, BlogPrefs.DEFAULT_LONG_THRESHOLD)
            .commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── fake gateway ─────────────────────────────────────────────────

    private val page0Url = "https://www.tumblr.com/api/v2/blog/bonesbunns/posts?after=a"
    private val page1Url = "https://www.tumblr.com/api/v2/blog/bonesbunns/posts?after=b"
    private val page2Url = "https://www.tumblr.com/api/v2/blog/bonesbunns/posts?after=c"

    private fun mediaPost(url: String, imgUrl: String) = JSONObject().apply {
        put("postUrl", url)
        put("type", "blocks")
        put(
            "content", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "image")
                        put(
                            "media", JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("url", imgUrl)
                                        put("mediaKey", "key-$url")
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    private fun textPost(url: String) = JSONObject().apply {
        put("postUrl", url)
        put("type", "blocks")
        put(
            "content", JSONArray().apply {
                put(JSONObject().apply { put("type", "text"); put("text", "hello") })
            }
        )
    }

    private fun feedBody(posts: JSONArray, totalPosts: Int, nextUrl: String?) = JSONObject().apply {
        put(
            "response", JSONObject().apply {
                put("posts", posts)
                put("totalPosts", totalPosts)
                if (nextUrl != null) {
                    put(
                        "links", JSONObject().apply {
                            put("next", JSONObject().apply { put("href", nextUrl) })
                        }
                    )
                }
            }
        )
    }.toString()

    private fun bootstrapHtml(initialPost: JSONObject) = buildString {
        append("<script id=\"___INITIAL_STATE___\">")
        append(
            JSONObject().apply {
                put("apiFetchStore", JSONObject().apply { put("API_TOKEN", "test-token") })
                put(
                    "PeeprRoute", JSONObject().apply {
                        put(
                            "initialTimeline", JSONObject().apply {
                                put("objects", JSONArray().apply { put(initialPost) })
                                put("nextLink", JSONObject().apply { put("href", "/v2/blog/bonesbunns/posts?after=a") })
                            }
                        )
                    }
                )
                put(
                    "queries", JSONObject().apply {
                        put(
                            "queries", JSONArray().apply {
                                put(JSONObject().apply { put("state", JSONObject().apply { put("data", JSONObject().apply { put("isLoggedIn", false) }) }) })
                                put(
                                    JSONObject().apply {
                                        put(
                                            "state", JSONObject().apply {
                                                put(
                                                    "data", JSONObject().apply {
                                                        put("name", "bonesbunns")
                                                        put("title", "Test Blog")
                                                        put("isPasswordProtected", false)
                                                        put("isAdult", false)
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
        append("</script>")
    }

    /**
     * Gateway serving the fixed page graph:
     * bootstrap(1 post) → page0(2 posts) → page1(1 post+text) → page2(1 post) → end.
     *
     * [page1Gate] (when provided) parks the worker on the first page-1 fetch so
     * tests can cancel deterministically at a persisted cursor boundary.
     */
    private inner class FakeGateway(
        private val page1Gate: CompletableDeferred<Unit>? = null
    ) : BlogFeedParser.FeedGateway {
        val requested = mutableListOf<String>()

        override suspend fun fetch(url: String, bearerToken: String?): BlogFeedParser.FetchResponse {
            requested += "$url|$bearerToken"
            if (bearerToken == null) {
                val initial = mediaPost("https://www.tumblr.com/bonesbunns/init", "https://64.media.tumblr.com/init_1280.jpg")
                return BlogFeedParser.FetchResponse(200, bootstrapHtml(initial))
            }
            if (url == page1Url && page1Gate != null) page1Gate.await()
            val body = when (url) {
                page0Url -> feedBody(
                    JSONArray().apply {
                        put(mediaPost("https://www.tumblr.com/bonesbunns/1", "https://64.media.tumblr.com/p1_1280.jpg"))
                        put(mediaPost("https://www.tumblr.com/bonesbunns/2", "https://64.media.tumblr.com/p2_1280.jpg"))
                    }, 4, page1Url
                )
                page1Url -> feedBody(
                    JSONArray().apply {
                        put(mediaPost("https://www.tumblr.com/bonesbunns/3", "https://va.media.tumblr.com/p3.mp4"))
                        put(textPost("https://www.tumblr.com/bonesbunns/4"))
                    }, 4, page2Url
                )
                page2Url -> feedBody(
                    JSONArray().apply { put(mediaPost("https://www.tumblr.com/bonesbunns/5", "https://64.media.tumblr.com/p5_1280.jpg")) },
                    4, null
                )
                else -> feedBody(JSONArray(), 4, null)
            }
            return BlogFeedParser.FetchResponse(200, body)
        }
    }

    /** Drive the virtual worker while leaving real wall-clock room for Room IO. */
    private suspend fun TestScope.advanceUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            testScheduler.advanceUntilIdle()
            // Yield to the real IO threads serving Room, then come back.
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
        testScheduler.advanceUntilIdle()
        assertTrue("condition not reached within $timeoutMs ms", predicate())
    }

    // ── tests ────────────────────────────────────────────────────────

    @Test
    fun enqueue_blog_runs_scan_and_enqueues_items_with_batch_tag() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        val ok = manager.enqueueBlog("https://www.tumblr.com/bonesbunns", BlogDownloadOptions())
        assertTrue("blog URL should be accepted", ok)

        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }

        val job = manager.jobs.value.first()
        assertEquals("bonesbunns", job.blogName)
        assertEquals(BlogJobState.DONE, job.state())
        assertNull("finished job must drop its cursor", job.resumeLink)
        // Scanned posts: init(1) + page0(2) + page1(p3 + text p4) + page2(p5) = 6
        assertEquals(6, job.postsDiscovered + job.postsSkipped + job.postsNoMedia)
        assertEquals(1, job.postsNoMedia)
        assertTrue("media should be enqueued", job.mediaEnqueued > 0)

        // The items hit the download state manager tagged with the job id.
        val items = stateManager.items.value
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.batchId == job.jobId })
        assertTrue(items.all { it.blogName == "bonesbunns" })
        // 5 media posts → 5 items (text post #4 has no media).
        assertEquals(5, items.size)
        // No duplicate enqueues despite bootstrap + page0 containing distinct posts.
        val identities = items.map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }.toSet()
        assertEquals(items.size, identities.size)

        val requestedUrls = gateway.requested.map { it.substringBefore('|') }
        assertTrue("bootstrap page must be fetched with a token", gateway.requested.any { it.endsWith("|null") })
        assertTrue("page0 must be fetched", requestedUrls.contains(page0Url))
        assertTrue("page1 must be fetched", requestedUrls.contains(page1Url))
        assertTrue("page2 must be fetched", requestedUrls.contains(page2Url))
    }

    @Test
    fun enqueue_rejects_invalid_url_and_duplicate_active_job() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(!manager.enqueueBlog("https://example.com/nope"))
        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        // The job card is already RUNNING in the flow before the worker starts,
        // so a second job for the same blog is refused without any advancement.
        assertTrue(!manager.enqueueBlog("https://www.tumblr.com/bonesbunns", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }
    }

    @Test
    fun cancel_marks_cancelled_and_resume_completes_from_cursor() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val page1Gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(page1Gate)
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        // Park the worker on the page-1 fetch, then cancel: the cursor persisted
        // just before that fetch (page1Url) must survive the cancellation.
        advanceUntil { gateway.requested.any { it.startsWith(page1Url) } }

        manager.cancel(manager.jobs.value.first().jobId)
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.CANCELLED } }

        val cancelled = manager.jobs.value.first()
        assertEquals(BlogJobState.CANCELLED, cancelled.state())
        assertNotNull("cancelled job must keep its cursor", cancelled.resumeLink)
        assertEquals(page1Url, cancelled.resumeLink)

        // Resume continues at the stored cursor and completes the scan.
        page1Gate.complete(Unit)
        manager.resume(cancelled.jobId)
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }
        val done = manager.jobs.value.first()
        assertEquals(BlogJobState.DONE, done.state())
        assertNull(done.resumeLink)
    }

    @Test
    fun password_protected_blog_is_blocked() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = object : BlogFeedParser.FeedGateway {
            override suspend fun fetch(url: String, bearerToken: String?): BlogFeedParser.FetchResponse {
                val html = buildString {
                    append("<script id=\"___INITIAL_STATE___\">")
                    append(
                        JSONObject().apply {
                            put("PeeprRoute", JSONObject())
                            put(
                                "queries", JSONObject().apply {
                                    put(
                                        "queries", JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put(
                                                        "state", JSONObject().apply {
                                                            put(
                                                                "data", JSONObject().apply {
                                                                    put("name", "bonesbunns")
                                                                    put("title", "Secret")
                                                                    put("isPasswordProtected", true)
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    append("</script>")
                }
                return BlogFeedParser.FetchResponse(200, html)
            }
        }
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.BLOCKED } }

        val blocked = manager.jobs.value.first()
        assertEquals(BlogJobState.BLOCKED, blocked.state())
        assertEquals("password_protected", blocked.errorCode)
    }

    @Test
    fun remove_deletes_the_card() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }

        val jobId = manager.jobs.value.first().jobId
        manager.remove(jobId)
        advanceUntil { manager.jobs.value.none { it.jobId == jobId } }
    }

    @Test
    fun cancel_pauses_the_whole_batch_queue() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val page1Gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(page1Gate)
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil {
            gateway.requested.any { it.startsWith(page1Url) } &&
                stateManager.items.value.isNotEmpty()
        }
        val jobId = manager.jobs.value.first().jobId
        assertTrue("scan should have enqueued items", stateManager.items.value.any { it.batchId == jobId })

        manager.cancel(jobId)
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.CANCELLED } }

        val batch = stateManager.items.value.filter { it.batchId == jobId }
        assertTrue("cancelled scan must keep its items", batch.isNotEmpty())
        assertTrue(
            "pausing the blog must pause its whole queue (scan + downloads)",
            batch.all { it.status == DownloadStatus.PAUSED }
        )
    }

    @Test
    fun resume_restarts_paused_batch_queue() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val page1Gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(page1Gate)
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil {
            gateway.requested.any { it.startsWith(page1Url) } &&
                stateManager.items.value.isNotEmpty()
        }
        val jobId = manager.jobs.value.first().jobId

        manager.cancel(jobId)
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.CANCELLED } }
        assertTrue(
            "items must be paused before resume",
            stateManager.items.value.filter { it.batchId == jobId }
                .all { it.status == DownloadStatus.PAUSED }
        )

        page1Gate.complete(Unit)
        manager.resume(jobId)
        advanceUntil {
            stateManager.items.value.any {
                it.batchId == jobId && it.status == DownloadStatus.DOWNLOADING
            }
        }

        val items = stateManager.items.value.filter { it.batchId == jobId }
        assertTrue("resume must restart the batch queue", items.any { it.status == DownloadStatus.DOWNLOADING })
        assertTrue("resumed items must no longer be paused", items.none { it.status == DownloadStatus.PAUSED })
    }

    @Test
    fun pause_finished_scan_pauses_downloads_without_rescan() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }
        val jobId = manager.jobs.value.first().jobId
        val fetchesAtDone = gateway.requested.size

        // Pause only the downloads of a finished scan: the job must stay DONE
        // (the feed is fully consumed, so a later Start just continues the
        // download queue instead of re-parsing the whole blog).
        manager.pause(jobId)
        advanceUntil {
            stateManager.items.value.any { it.batchId == jobId && it.status == DownloadStatus.PAUSED }
        }
        assertEquals(BlogJobState.DONE, manager.jobs.value.first().state())

        // Resume continues the queue without relaunching the scan worker:
        // the gateway must not see any new feed request.
        manager.resume(jobId)
        advanceUntil {
            stateManager.items.value.any { it.batchId == jobId && it.status == DownloadStatus.DOWNLOADING }
        }
        assertEquals("resume of a finished scan must not re-fetch the feed", fetchesAtDone, gateway.requested.size)
        assertEquals(BlogJobState.DONE, manager.jobs.value.first().state())
        assertTrue(
            "resumed items must no longer be paused",
            stateManager.items.value.filter { it.batchId == jobId }.none { it.status == DownloadStatus.PAUSED }
        )
    }

    @Test
    fun remove_purges_unfinished_batch_items_but_keeps_completed() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        assertTrue(manager.enqueueBlog("https://bonesbunns.tumblr.com", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }
        val jobId = manager.jobs.value.first().jobId

        // Simulate one successful download so the purge must keep it.
        val completed = stateManager.items.value.first()
        stateManager.serviceProgressListener.onDownloadUpdate(
            completed.copy(status = DownloadStatus.COMPLETED, progress = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        manager.remove(jobId)
        advanceUntil { manager.jobs.value.none { it.jobId == jobId } }

        val remaining = stateManager.items.value.filter { it.batchId == jobId }
        assertEquals(
            "remove must purge unfinished batch items and keep completed ones",
            1,
            remaining.size
        )
        assertEquals(DownloadStatus.COMPLETED, remaining.single().status)
    }
}
