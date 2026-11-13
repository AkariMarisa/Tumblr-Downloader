package io.github.akarimarisa.tumblrdownloader.model

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import io.github.akarimarisa.tumblrdownloader.utils.BlogDownloadJobStore
import io.github.akarimarisa.tumblrdownloader.utils.BlogFeedParser
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import io.github.akarimarisa.tumblrdownloader.utils.LiveBlogFeedGateway
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.ParsedTumblrMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Which media a blog scan should enqueue. */
enum class BlogMediaFilter { ALL, IMAGES, VIDEOS }

/** Per-job user choices for a blog full-profile scan. */
data class BlogDownloadOptions(
    val mediaFilter: BlogMediaFilter = BlogMediaFilter.ALL,
    /** Skip the long-blog confirmation dialog even when the feed exceeds the threshold. */
    val forceLongBlog: Boolean = false
)

/** One-shot events surfaced to the UI (toasts, confirm dialogs). */
sealed class BlogEvent {
    data class ConfirmLongBlog(
        val jobId: String,
        val blogName: String,
        val totalPosts: Int,
        val estimatedRequests: Int,
        val mediaFilter: BlogMediaFilter
    ) : BlogEvent()
    data class Finished(val jobId: String, val cancelled: Boolean) : BlogEvent()
    data class Message(val text: String) : BlogEvent()
}

/**
 * Coordinator for blog full-profile batch downloads (issue #22).
 *
 * Owns one worker coroutine per job and the durable cursor stored in
 * [BlogDownloadJobEntity]: every page boundary persists the next feed href,
 * so an interrupted scan resumes mid-profile instead of starting over.
 *
 * The optimism guarantee: all enqueues funnel through
 * [DownloadStateManager.enqueueBlogAfterParse], which runs on the single
 * command processor, so batch appends are race-free with user actions.
 */
class BlogDownloadManager private constructor(
    private val app: Application,
    private val gateway: BlogFeedParser.FeedGateway,
    private val stateManager: DownloadStateManager,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        @Volatile
        private var INSTANCE: BlogDownloadManager? = null

        fun getInstance(app: Application): BlogDownloadManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlogDownloadManager(app, LiveBlogFeedGateway, DownloadStateManager.getInstance(app))
                    .also { INSTANCE = it }
            }

        /** Fresh, isolated instance for unit tests only. */
        @VisibleForTesting
        internal fun forTest(
            app: Application,
            gateway: BlogFeedParser.FeedGateway,
            stateManager: DownloadStateManager,
            ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
        ): BlogDownloadManager =
            BlogDownloadManager(app, gateway, stateManager, ioDispatcher)

        /** Queue rows a scan may leave behind before it waits for downloads to catch up (#12). */
        private const val MAX_QUEUE_ROWS = 3_000

        /** Poll interval while waiting for the queue to drain. */
        private const val QUEUE_DRAIN_POLL_MS = 1_000L

        /** Cap on that wait (30 min) so a permanently stuck queue can't pin a worker forever. */
        private const val MAX_QUEUE_DRAIN_STEPS = 1_800
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _jobs = MutableStateFlow<List<BlogDownloadJobEntity>>(emptyList())
    private val _events = MutableSharedFlow<BlogEvent>(extraBufferCapacity = 16)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val confirmGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Jobs whose long-blog confirmation is not decided yet — from worker start
     * until the gate is answered (or passed).
     *
     * The Download list uses it to hold the user on the list while the
     * "确定要下载该博客" dialog is pending: that dialog is attached to
     * MainActivity, so opening the detail screen first would leave it hidden
     * behind the detail activity and it would only pop up on the way back.
     */
    private val _confirmPending = MutableStateFlow<Set<String>>(emptySet())
    val confirmPending: StateFlow<Set<String>> = _confirmPending.asStateFlow()

    private fun setConfirmPending(jobId: String, pending: Boolean) {
        _confirmPending.update { current ->
            if (pending) current + jobId else current - jobId
        }
    }
    private val jobOptions = ConcurrentHashMap<String, BlogDownloadOptions>()
    private val stateMutex = Mutex()
    private val _activeJobIds = MutableStateFlow<Set<String>>(emptySet())

    /** All known job cards, newest first. */
    val jobs: StateFlow<List<BlogDownloadJobEntity>> = _jobs.asStateFlow()
    val events: SharedFlow<BlogEvent> = _events.asSharedFlow()

    /**
     * Job ids whose scan worker is currently running, as a [StateFlow] so the
     * UI can react the moment a scan starts or stops (the card must show
     * "parsing" immediately on enqueue, not a beat later).
     */
    val activeJobIds: StateFlow<Set<String>> = _activeJobIds.asStateFlow()

    /** True when a worker is actively scanning this job right now. */
    fun isActive(jobId: String): Boolean = activeJobs.containsKey(jobId)

    private fun trackActive(jobId: String) {
        _activeJobIds.value = _activeJobIds.value + jobId
    }

    private fun untrackActive(jobId: String) {
        _activeJobIds.value = _activeJobIds.value - jobId
    }

    /**
     * Completed once the persisted cards have been merged into [jobs].
     * [enqueueBlog] awaits this before its duplicate-job check, so an enqueue
     * racing the very first restore (process start → immediate paste) cannot
     * create a second job for a blog that already has one on disk.
     */
    private val restored = CompletableDeferred<Unit>()

    init {
        // Restore persisted job cards. Merge (never replace): a job enqueued
        // before this async restore finishes must not be thrown away.
        scope.launch {
            try {
                stateMutex.withLock {
                    val loaded = BlogDownloadJobStore.loadAll(app)
                    _jobs.value = (loaded + _jobs.value)
                        .distinctBy { it.jobId }
                        .sortedByDescending { it.createdAt }
                }
            } catch (e: Exception) {
                android.util.Log.e("BlogDM", "job card restore failed", e)
            } finally {
                // Always release the enqueue path, even on a failed restore.
                restored.complete(Unit)
            }
        }
    }

    // ── public API ────────────────────────────────────────────────────

    /**
     * Start a full-profile scan for a standalone blog URL.
     *
     * **One card per blog.** A job that is still running refuses a second
     * enqueue (there is nothing to add); any other previous job for the same
     * blog — finished, cancelled or blocked — has its card *reused*: it moves
     * back to the top of the list ([BlogDownloadJobEntity.createdAt] is the
     * sort key), starts a fresh scan for posts published since the last run,
     * and keeps every item it already tracks. Completed files therefore stay
     * completed, queued items keep their place and the card's progress bar
     * continues where it left off — only the new media is downloaded.
     *
     * Returns false when the URL is not a blog URL or the same blog is
     * already being downloaded right now.
     *
     * Suspend on purpose: it waits for the initial card restore so the
     * duplicate check sees persisted jobs, and it writes the card inside
     * the same [stateMutex] critical section as the in-memory insert — so a
     * cancel/pause landing right after enqueue can never observe an in-memory
     * card with no row on disk (which used to make [markState] a no-op).
     */
    suspend fun enqueueBlog(rawUrl: String, options: BlogDownloadOptions = BlogDownloadOptions()): Boolean {
        val blogName = BlogFeedParser.parseBlogIdentifier(rawUrl)
            ?: run {
                _events.tryEmit(BlogEvent.Message(localizedString(R.string.blog_url_invalid)))
                return false
            }
        restored.await()
        stateMutex.withLock {
            val now = System.currentTimeMillis()

            // Still scanning: keep the single card and tell the user where to
            // find it instead of queueing a duplicate. Check the live worker map
            // rather than the persisted state: a process kill leaves a card in
            // RUNNING with no worker behind it, and such an "interrupted" card
            // must be re-usable (its Resume button already restarts it).
            if (_jobs.value.any {
                    it.blogName == blogName &&
                        activeJobs.containsKey(it.jobId)
                }
            ) {
                _events.tryEmit(BlogEvent.Message(localizedString(R.string.blog_job_already_running, blogName)))
                return false
            }

            // Reuse the previous card for this blog rather than adding a
            // second one (createdAt doubles as the list's sort key, so
            // refreshing it brings the card straight back to the top).
            // Scan counters restart with the new pass, but mediaEnqueued is
            // kept: it counts the card's items, so it keeps matching the
            // "downloaded x/y" line instead of dropping back to zero.
            val previous = _jobs.value
                .filter { it.blogName == blogName }
                .maxByOrNull { it.createdAt }
            if (previous != null) {
                _events.tryEmit(BlogEvent.Message(localizedString(R.string.blog_job_resumed, blogName)))
            }
            val job = previous?.copy(
                state = BlogJobState.RUNNING.name,
                resumeLink = null,
                totalPosts = null,
                pagesCompleted = 0,
                postsDiscovered = 0,
                postsSkipped = 0,
                postsNoMedia = 0,
                createdAt = now,
                updatedAt = now,
                errorCode = null
            ) ?: BlogDownloadJobEntity(
                jobId = UUID.randomUUID().toString(),
                blogName = blogName,
                state = BlogJobState.RUNNING.name,
                resumeLink = null,
                totalPosts = null,
                pagesCompleted = 0,
                postsDiscovered = 0,
                postsSkipped = 0,
                postsNoMedia = 0,
                mediaEnqueued = 0,
                createdAt = now,
                updatedAt = now,
                errorCode = null
            )

            // A cancelled card can still carry its cancel flag and a pending
            // long-blog confirmation from the previous run; both must be
            // cleared or the relaunched worker would stop (or hang) at once.
            cancelFlags.remove(job.jobId)
            confirmGates.remove(job.jobId)
            jobOptions[job.jobId] = options
            // Inherit rows an earlier card of this blog left behind — a crash
            // mid-remove orphans them with no card left to show or purge them,
            // so the next card has to adopt them (#13 aftermath).
            stateManager.adoptBatchItems(blogName, job.jobId)
            // Show the card immediately and persist it before returning.
            _jobs.value = (listOf(job) + _jobs.value.filterNot { it.jobId == job.jobId })
                .sortedByDescending { it.createdAt }
            BlogDownloadJobStore.upsert(app, job)
            launchWorker(job)
        }
        return true
    }

    /**
     * Resume a previously interrupted/cancelled/blocked job from its persisted
     * cursor. The scan re-bootstraps for a fresh token, then continues at the
     * stored `resumeLink`.
     */
    fun resume(jobId: String) {
        val job = _jobs.value.firstOrNull { it.jobId == jobId } ?: return
        if (activeJobs.containsKey(jobId)) return
        // A finished scan (DONE) has no feed left to re-fetch: only restart
        // the batch's paused/queued downloads. Everything else relaunches the
        // scan from its persisted cursor and resumes the batch alongside it.
        if (job.state() == BlogJobState.DONE) {
            stateManager.resumeBatch(jobId)
            return
        }
        // `cancel()` leaves the flag set for the worker to observe; clear it here
        // so the relaunched worker does not immediately break out again.
        cancelFlags.remove(jobId)
        jobOptions.putIfAbsent(jobId, BlogDownloadOptions())
        markState(jobId, BlogJobState.RUNNING, errorCode = null)
        // Re-start the batch's paused download items alongside the scan.
        stateManager.resumeBatch(jobId)
        launchWorker(job)
    }

    /**
     * Card-level pause. A still-running scan is cancelled (marking the job
     * CANCELLED so [resume] relaunches it from its cursor); a finished scan
     * (DONE) only pauses the batch's unfinished download items, keeping the
     * job DONE so a later [resume] continues downloading without re-parsing
     * the whole feed.
     */
    fun pause(jobId: String) {
        val job = _jobs.value.firstOrNull { it.jobId == jobId } ?: return
        if (job.state() == BlogJobState.RUNNING) cancel(jobId) else stateManager.pauseBatch(jobId)
    }

    /** Stop the worker (if any), mark the job cancelled, and pause its queue. Resume remains available. */
    fun cancel(jobId: String) {
        cancelFlags[jobId] = true
        activeJobs.remove(jobId)?.cancel()
        untrackActive(jobId)
        confirmGates.remove(jobId)?.complete(false)
        markState(jobId, BlogJobState.CANCELLED)
        // "Pause the blog's queue": pause every unfinished item it enqueued,
        // so the whole blog task stops together with the scan.
        stateManager.pauseBatch(jobId)
    }

    /** Remove the job card entirely (any state) and purge its unfinished queue. */
    fun remove(jobId: String) {
        cancelFlags[jobId] = true
        activeJobs.remove(jobId)?.cancel()
        untrackActive(jobId)
        confirmGates.remove(jobId)?.complete(false)
        // Drop the batch's unfinished download items (completed files stay on
        // disk and their list rows are kept, so a re-scan dedupes instead of
        // re-downloading). Runs after the card is gone so the items disappear
        // from the (hidden) queue at the same time as their entry point.
        stateManager.removeBatch(jobId)
        scope.launch {
            stateMutex.withLock {
                BlogDownloadJobStore.delete(app, jobId)
                _jobs.value = _jobs.value.filterNot { it.jobId == jobId }
            }
        }
    }

    /** Answer the long-blog confirmation dialog. */
    fun respondLongBlog(jobId: String, proceed: Boolean) {
        confirmGates.remove(jobId)?.complete(proceed)
    }

    // ── internal ──────────────────────────────────────────────────────

    private fun markState(jobId: String, state: BlogJobState, errorCode: String? = null) {
        scope.launch {
            stateMutex.withLock {
                val now = System.currentTimeMillis()
                // A card is normally on disk already (enqueueBlog persists it
                // inside this same lock). Fall back to the in-memory row so a
                // state change is never dropped when the first write has not
                // landed; if the card is gone altogether there is nothing to
                // mark — [remove] deletes the row and the entry together.
                val cur = BlogDownloadJobStore.load(app, jobId)
                    ?.copy(state = state.name, errorCode = errorCode, updatedAt = now)
                    ?: _jobs.value.firstOrNull { it.jobId == jobId }
                        ?.copy(state = state.name, errorCode = errorCode, updatedAt = now)
                    ?: return@withLock
                BlogDownloadJobStore.upsert(app, cur)
                _jobs.value = _jobs.value.map { if (it.jobId == jobId) cur else it }
            }
        }
    }

    /**
     * Persist a worker's card snapshot and mirror it into [jobs] atomically.
     *
     * The [cancelFlags] check runs *under* the lock: cancel/remove always set
     * the flag before launching their own state change, so a worker snapshot
     * that arrives too late is dropped instead of resurrecting a card that
     * was just cancelled or removed (and the reverse ordering still ends with
     * the cancel winning, because it writes last).
     */
    private suspend fun persistAndPublish(job: BlogDownloadJobEntity) {
        stateMutex.withLock {
            if (cancelFlags.containsKey(job.jobId)) return@withLock
            BlogDownloadJobStore.upsert(app, job)
            _jobs.value = _jobs.value.map { if (it.jobId == job.jobId) job else it }
        }
    }

    private fun launchWorker(job: BlogDownloadJobEntity) {
        val jobId = job.jobId
        if (activeJobs.containsKey(jobId)) return
        val worker = scope.launch(ioDispatcher) {
            var blocked: BlogDownloadManager.BlockedException? = null
            var crashed: Exception? = null
            try {
                runWorker(job)
            } catch (ce: CancellationException) {
                if (cancelFlags[jobId] != true) throw ce
            } catch (be: BlockedException) {
                blocked = be
            } catch (e: Exception) {
                crashed = e
                android.util.Log.e("BlogDM", "worker crashed for $jobId", e)
            } finally {
                activeJobs.remove(jobId)
                untrackActive(jobId)
                // Safety net: a worker that died before reaching the gate
                // must not leave the list locked out of the detail screen.
                setConfirmPending(jobId, false)
            }
            val reason = blocked
            if (reason != null) {
                markState(jobId, BlogJobState.BLOCKED, errorCode = reason.code)
                _events.tryEmit(BlogEvent.Message(localizedString(blogErrorString(reason.code))))
            } else if (crashed != null) {
                markState(jobId, BlogJobState.BLOCKED, errorCode = "internal")
                _events.tryEmit(BlogEvent.Message(localizedString(R.string.blog_error_internal)))
            }
        }
        activeJobs[jobId] = worker
        trackActive(jobId)
    }

    /**
     * Waits until the queue drops below [MAX_QUEUE_ROWS], the job is paused or
     * removed (its cancel flag), or [MAX_QUEUE_DRAIN_STEPS] polls elapse.
     */
    private suspend fun awaitQueueCapacity(jobId: String) {
        var steps = 0
        while (
            stateManager.items.value.size > MAX_QUEUE_ROWS &&
            !cancelFlags.containsKey(jobId) &&
            steps < MAX_QUEUE_DRAIN_STEPS
        ) {
            delay(QUEUE_DRAIN_POLL_MS)
            steps++
        }
    }

    private suspend fun runWorker(initialJob: BlogDownloadJobEntity) {
        val jobId = initialJob.jobId
        // Until the long-blog gate is answered (or passed) the list must not
        // navigate away: the confirm dialog belongs to MainActivity.
        setConfirmPending(jobId, true)
        var job = initialJob
        val blogName = job.blogName
        val opts = jobOptions[jobId] ?: BlogDownloadOptions()
        val quality = MediaQualityPrefs.read(app)
        val interval = BlogPrefs.scanIntervalMs(app)
        val longThreshold = BlogPrefs.longThreshold(app)

        val fresh = job.resumeLink == null && job.pagesCompleted == 0

        val boot = bootstrapBlog(blogName)
        val token = boot.apiToken
        var posts = if (fresh) boot.posts else emptyList()
        var nextLink = if (fresh) boot.nextLink else job.resumeLink
        // The bootstrap already reports the blog's post count, so a fresh job
        // can decide about the long-blog gate before it queues anything.
        var total = job.totalPosts ?: boot.info.numberOfPosts
        // …but when it doesn't, the count only arrives with the first feed
        // page: hold the bootstrap posts until then so the gate can still ask
        // before anything is queued (#15).
        var heldPosts: List<JSONObject> = emptyList()
        var heldForCount = false

        var pages = job.pagesCompleted
        var discovered = job.postsDiscovered
        var skipped = job.postsSkipped
        var noMedia = job.postsNoMedia
        var enqueued = job.mediaEnqueued
        var gated = false

        var knownSources: Set<String>? = null
        var seenPosts = HashSet<String>()
        var seenMedia = HashSet<String>()

        while (true) {
            if (cancelFlags.containsKey(jobId)) {
                break
            }

            // Long-blog gate — ask BEFORE this pass queues anything. It used
            // to run after the enqueue block, so downloads were already
            // running by the time the dialog appeared (#15). The bootstrap's
            // post count lets a fresh job decide before its first page is
            // processed; a job that learns `total` later gates at the top of
            // the following pass instead.
            if (!gated && !opts.forceLongBlog && total != null && total >= longThreshold) {
                gated = true
                _events.tryEmit(
                    BlogEvent.ConfirmLongBlog(
                        jobId = jobId,
                        blogName = blogName,
                        totalPosts = total,
                        estimatedRequests = (total / 20).coerceAtLeast(pages),
                        mediaFilter = opts.mediaFilter
                    )
                )
                val proceed = confirmGates.getOrPut(jobId) { CompletableDeferred() }.await()
                if (!proceed) {
                    break
                }
            }

            if (posts.isNotEmpty()) {
                // Hold the very first batch of a fresh job when the post count
                // is still unknown *and* a next page exists to learn it from:
                // the long-blog gate must be able to ask first (#15). With no
                // next page there is nothing to learn, so queue right away.
                if (!heldForCount && total == null && nextLink != null) {
                    heldForCount = true
                    heldPosts = posts
                    posts = emptyList()
                }
            }
            if (posts.isNotEmpty()) {
                val snapshot = knownSources ?: DownloadHistoryStore.loadSourceUrls(app)
                    .also { knownSources = it }
                for (post in posts) {
                    val postUrl = post.optString("postUrl")
                    if (postUrl.isBlank()) continue
                    if (!seenPosts.add(postUrl)) continue
                    if (postUrl in snapshot) {
                        skipped++
                        continue
                    }
                    val all = BlogFeedParser.extractMediaFromPost(post, postUrl, quality)
                    val filtered = when (opts.mediaFilter) {
                        BlogMediaFilter.ALL -> all
                        BlogMediaFilter.IMAGES -> all.filter { it.type == MediaType.IMAGE }
                        BlogMediaFilter.VIDEOS -> all.filter { it.type == MediaType.VIDEO }
                    }
                    val batch = mutableListOf<ParsedTumblrMedia>()
                    for (candidate in filtered) {
                        if (seenMedia.add(DownloadUtils.normalizeMediaIdentity(candidate.mediaUrl))) {
                            batch += candidate
                        }
                    }
                    if (batch.isEmpty()) {
                        noMedia++
                    } else {
                        discovered++
                        enqueued += batch.size
                        // Back-pressure: a blog with tens of thousands of media
                        // must not pile rows up faster than the queue drains.
                        // Every append re-normalises the whole queue on the main
                        // thread, and each row is kept in RAM and serialised on
                        // every persistence tick — a 4.7k-post blog saturated the
                        // CPU and ANR'd the app (#12).
                        awaitQueueCapacity(jobId)
                        stateManager.enqueueBlogAfterParse(batch, jobId, blogName)
                    }
                }
            }

            val nextToFetch = nextLink
            if (nextToFetch == null) {
                // Feed fully consumed. If the job was removed while the last
                // page was being processed, its row is already deleted — don't
                // resurrect the card.
                if (cancelFlags.containsKey(jobId)) return
                val done = job.copy(
                    state = BlogJobState.DONE.name,
                    resumeLink = null,
                    pagesCompleted = pages,
                    postsDiscovered = discovered,
                    postsSkipped = skipped,
                    postsNoMedia = noMedia,
                    mediaEnqueued = enqueued,
                    totalPosts = total,
                    updatedAt = System.currentTimeMillis(),
                    errorCode = null
                )
                persistAndPublish(done)
                _events.tryEmit(BlogEvent.Finished(jobId, cancelled = false))
                return
            }

            // Persist the cursor before attempting the next fetch.
            job = job.copy(
                state = BlogJobState.RUNNING.name,
                resumeLink = nextToFetch,
                pagesCompleted = pages,
                postsDiscovered = discovered,
                postsSkipped = skipped,
                postsNoMedia = noMedia,
                mediaEnqueued = enqueued,
                totalPosts = total,
                updatedAt = System.currentTimeMillis()
            )
            persistAndPublish(job)

            // The gate decision is known once `total` is — or once we stopped
            // waiting for it (first page fetched and the feed reported no
            // count, so no dialog can come). A worker suspended in the gate
            // never reaches this line, so the detail screen stays locked until
            // the user decides.
            if (total != null || heldForCount) setConfirmPending(jobId, false)

            if (cancelFlags.containsKey(jobId)) {
                break
            }

            delay(interval)

            val feed = fetchFeedPage(nextToFetch, token)
            if (total == null) total = feed.totalPosts
            posts = heldPosts + feed.posts
            heldPosts = emptyList()
            nextLink = feed.nextLink
            pages++
        }

        // Only reachable via `break`: the job was cancelled or the user said no.
        cancelFlags.remove(jobId)
        markState(jobId, BlogJobState.CANCELLED)
        _events.tryEmit(BlogEvent.Finished(jobId, cancelled = true))
    }

    private suspend fun bootstrapBlog(blogName: String): BlogFeedParser.BootstrapData {
        var attempts = 0
        while (true) {
            try {
                val resp = gateway.fetch("https://www.tumblr.com/$blogName", null)
                if (resp.code == 404) throw BlockedException("not_found")
                if (resp.code == 429 || resp.code == 503) {
                    if (attempts >= 3) throw BlockedException("rate_limited")
                    delay(backoffMs(attempts++))
                    continue
                }
                if (resp.code !in 200..299) throw BlockedException("http_${resp.code}")
                val boot = BlogFeedParser.parseBootstrap(resp.body, blogName)
                    ?: throw BlockedException("page_parse_failed")
                if (boot.info.isPasswordProtected) throw BlockedException("password_protected")
                if (boot.apiToken.isBlank()) throw BlockedException("token_missing")
                return boot
            } catch (io: IOException) {
                if (attempts >= 3) throw BlockedException("network")
                delay(backoffMs(attempts++))
            }
        }
    }

    /** Fetch one feed page with bounded retries; a 401 re-bootstraps a fresh token. */
    private suspend fun fetchFeedPage(url: String, token: String): BlogFeedParser.FeedPage {
        var currentToken = token
        var refreshed = false
        var attempts = 0
        while (true) {
            try {
                val resp = gateway.fetch(url, currentToken)
                if (resp.code in 200..299) {
                    return BlogFeedParser.parseFeedPage(resp.body)
                        ?: throw BlockedException("page_parse_failed")
                }
                if (resp.code == 401 && !refreshed) {
                    val blog = url.substringAfter("www.tumblr.com/api/v2/blog/")
                        .substringBefore("/")
                        .substringBefore("?")
                        .ifBlank { "tumblr" }
                    currentToken = bootstrapBlog(blog).apiToken
                    refreshed = true
                    continue
                }
                if (resp.code == 429 || resp.code == 503 || resp.code == 500) {
                    if (attempts >= 3) throw BlockedException("rate_limited")
                    delay(backoffMs(attempts++))
                    continue
                }
                if (resp.code == 404) throw BlockedException("not_found")
                throw BlockedException("http_${resp.code}")
            } catch (io: IOException) {
                if (attempts >= 3) throw BlockedException("network")
                delay(backoffMs(attempts++))
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        0 -> 500L
        1 -> 1_000L
        else -> 2_000L
    }

    private fun blogErrorString(code: String): Int = when (code) {
        "not_found" -> R.string.blog_error_not_found
        "password_protected" -> R.string.blog_error_password_protected
        "rate_limited" -> R.string.blog_error_rate_limited
        "network" -> R.string.blog_error_network
        "page_parse_failed", "token_missing" -> R.string.blog_error_parse_failed
        else -> R.string.blog_error_http
    }

    private fun localizedString(@StringRes res: Int, vararg args: Any): String =
        LocaleHelper.contextForAppLocale(app).getString(res, *args)

    private class BlockedException(val code: String) : Exception(code)
}

/** SharedPreferences for blog scan tuning. */
object BlogPrefs {
    const val PREF_SCAN_INTERVAL_MS = "blog_scan_interval_ms"
    const val PREF_LONG_THRESHOLD = "blog_long_threshold"
    const val DEFAULT_SCAN_INTERVAL_MS = 500L
    const val DEFAULT_LONG_THRESHOLD = 10_000

    fun prefs(context: Context) =
        context.getSharedPreferences(MediaQualityPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    fun scanIntervalMs(context: Context): Long =
        prefs(context).getLong(PREF_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS)

    fun longThreshold(context: Context): Int =
        prefs(context).getInt(PREF_LONG_THRESHOLD, DEFAULT_LONG_THRESHOLD)
}
