package io.github.akarimarisa.tumblrdownloader.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import io.github.akarimarisa.tumblrdownloader.utils.BlogDownloadJobStore
import io.github.akarimarisa.tumblrdownloader.utils.BlogFeedParser
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    private suspend fun TestScope.advanceUntil(
        timeoutMs: Long = 5_000,
        predicate: suspend () -> Boolean
    ) {
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
    fun longBlogGateAsksBeforeAnythingIsQueued() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        // Small enough for the fake feed's totalPosts (4) to count as "long".
        BlogPrefs.prefs(app).edit().putInt(BlogPrefs.PREF_LONG_THRESHOLD, 2).commit()

        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)
        val events = mutableListOf<BlogEvent>()
        val collector = launch { manager.events.collect { events += it } }

        assertTrue(manager.enqueueBlog("https://www.tumblr.com/bonesbunns", BlogDownloadOptions()))
        advanceUntil { events.any { it is BlogEvent.ConfirmLongBlog } }

        val confirm = events.filterIsInstance<BlogEvent.ConfirmLongBlog>().single()
        assertEquals(
            "no media may be queued before the long-blog confirm is answered (#15)",
            0,
            stateManager.items.value.size
        )

        manager.respondLongBlog(confirm.jobId, true)
        advanceUntil { manager.jobs.value.any { it.state() == BlogJobState.DONE } }
        assertTrue(
            "answering yes must queue the blog's media",
            stateManager.items.value.isNotEmpty()
        )

        collector.cancel()
        BlogDownloadJobStore.clear(app)
        DownloadHistoryStore.clear(app)
    }

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

    @Test
    fun enqueueSameBlogReusesOneCardAtTheTopAndKeepsItsProgress() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)
        val gateway = FakeGateway()
        val manager = BlogDownloadManager.forTest(app, gateway, stateManager, testDispatcher)

        // First run of the blog under test.
        assertTrue(manager.enqueueBlog("https://www.tumblr.com/bonesbunns", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil {
            manager.jobs.value.size == 1 &&
                manager.jobs.value.first().state() == BlogJobState.DONE
        }
        val jobId = manager.jobs.value.first().jobId
        val itemsAfterFirstRun = stateManager.items.value.size
        assertTrue("first run must enqueue media", itemsAfterFirstRun > 0)

        // Keep one download completed so the second run has progress to preserve.
        val completed = stateManager.items.value.first()
        stateManager.serviceProgressListener.onDownloadUpdate(
            completed.copy(status = DownloadStatus.COMPLETED, progress = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // The rescan dedupes against persisted history, so wait for the first
        // run's source URLs to reach the store (real IO, not on the scheduler).
        advanceUntil { DownloadHistoryStore.loadSourceUrls(app).isNotEmpty() }

        // A second blog pushed below it, so "back to the top" is observable.
        assertTrue(manager.enqueueBlog("https://www.tumblr.com/otherblog", BlogDownloadOptions(forceLongBlog = true)))
        advanceUntil {
            manager.jobs.value.size == 2 &&
                manager.jobs.value.first().blogName == "otherblog" &&
                manager.jobs.value.first().state() == BlogJobState.DONE
        }

        // Re-download the same blog: one card only, reused and moved up.
        assertTrue(manager.enqueueBlog("https://www.tumblr.com/bonesbunns", BlogDownloadOptions(forceLongBlog = true)))
        assertEquals("a blog must never get a second card", 2, manager.jobs.value.size)
        val reused = manager.jobs.value.first()
        assertEquals("the existing card is reused, not recreated", jobId, reused.jobId)
        assertEquals("the card jumps to the top of the list", "bonesbunns", reused.blogName)
        assertEquals("the other blog's card is pushed down", "otherblog", manager.jobs.value[1].blogName)

        advanceUntil { manager.jobs.value.first().state() == BlogJobState.DONE }

        val kept = stateManager.items.value.first { it.id == completed.id }
        assertEquals("progress must not reset", DownloadStatus.COMPLETED, kept.status)
        assertEquals(100, kept.progress)
        assertEquals(
            "the rescan must not re-download or duplicate anything",
            itemsAfterFirstRun,
            stateManager.items.value.size
        )
        assertTrue(
            "posts downloaded before are skipped by the rescan",
            manager.jobs.value.first().postsSkipped > 0
        )
    }

    @Test
    fun interruptedRunningCardIsReusedInsteadOfBlockingTheSameBlog() = runTest(testDispatcher) {
        DownloadHistoryStore.clear(app)
        BlogDownloadJobStore.clear(app)

        // A process kill leaves the persisted card saying RUNNING even though
        // no worker exists. Pasting the same blog again must reuse/restart it
        // rather than report "already running" forever.
        val now = System.currentTimeMillis()
        val interrupted = BlogDownloadJobEntity(
            jobId = "interrupted-job",
            blogName = "bonesbunns",
            state = BlogJobState.RUNNING.name,
            resumeLink = page1Url,
            totalPosts = 4,
            pagesCompleted = 1,
            postsDiscovered = 2,
            postsSkipped = 0,
            postsNoMedia = 0,
            mediaEnqueued = 2,
            createdAt = now - 1_000,
            updatedAt = now - 1_000,
            errorCode = null
        )
        BlogDownloadJobStore.upsert(app, interrupted)

        val manager = BlogDownloadManager.forTest(app, FakeGateway(), stateManager, testDispatcher)
        advanceUntil { manager.jobs.value.any { it.jobId == interrupted.jobId } }

        val accepted = manager.enqueueBlog(
            "https://www.tumblr.com/bonesbunns",
            BlogDownloadOptions(forceLongBlog = true)
        )
        assertTrue("an interrupted RUNNING card must be reusable", accepted)

        advanceUntil { manager.jobs.value.first().state() == BlogJobState.DONE }

        assertEquals("the interrupted card must be reused, not duplicated", 1, manager.jobs.value.size)
        assertEquals(interrupted.jobId, manager.jobs.value.first().jobId)
        assertTrue("the restarted scan must enqueue media", manager.jobs.value.first().mediaEnqueued > 0)
    }
}
