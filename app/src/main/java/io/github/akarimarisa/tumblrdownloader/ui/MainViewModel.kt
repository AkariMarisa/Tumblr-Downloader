package io.github.akarimarisa.tumblrdownloader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import io.github.akarimarisa.tumblrdownloader.model.BlogDownloadManager
import io.github.akarimarisa.tumblrdownloader.model.BlogDownloadOptions
import io.github.akarimarisa.tumblrdownloader.model.BlogEvent
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStateManager
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.service.DownloadService
import io.github.akarimarisa.tumblrdownloader.model.ParseEvent
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.TumblrAccount
import io.github.akarimarisa.tumblrdownloader.utils.TumblrAccountStore
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.utils.TumblrCookieStore
import io.github.akarimarisa.tumblrdownloader.utils.TumblrParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val appContext = getApplication<Application>()
    private val prefs = appContext.getSharedPreferences("tumblr_downloader", Context.MODE_PRIVATE)

    // ── download state manager (sequential, race-free) ────────────────
    // Process-wide singleton — there must be exactly ONE state owner per
    // process.  MainViewModel is constructed once per hosting Activity
    // (MainActivity AND SettingsDetailActivity each have their own instance), so
    // a per-instance DownloadStateManager would restore and re-persist its
    // own snapshot on every Settings visit (racing the live one) and
    // reintroduce the registration-order bugs behind issue #24.
    val stateManager = DownloadStateManager.getInstance(appContext)

    /**
     * Claims the singleton [DownloadService.progressListener] slot for this
     * state manager.
     *
     * Called from MainActivity.onResume() so the visible screen always
     * receives download updates regardless of which ViewModel was
     * constructed first.  Safe to call repeatedly (resume happens on every
     * return to the screen); SettingsDetailActivity's ViewModel never claims the
     * slot, so the download list stays live even while Settings is on top.
     *
     * The slot is intentionally NEVER released by a ViewModel: the state
     * manager is process-wide and persists even when every hosting Activity
     * is destroyed or evicted while the app is backgrounded.  Releasing it
     * on onCleared() would leave the manager deaf (and stop it persisting)
     * while the foreground service keeps downloading — the exact issue #24
     * "stuck progress" symptom class.
     */
    fun claimServiceProgressListener() {
        DownloadService.progressListener = stateManager.serviceProgressListener
    }

    val downloads: StateFlow<List<DownloadItem>> = stateManager.items
    val parseEvent = stateManager.parseEvent

    /** Pending pre-download picker request (null when nothing to review). */
    val mediaPreview = stateManager.mediaPreview

    // ── blog full-profile batch download manager (process-wide singleton) ──
    val blogManager = BlogDownloadManager.getInstance(appContext)
    val blogJobs: StateFlow<List<BlogDownloadJobEntity>> = blogManager.jobs
    val blogEvents = blogManager.events

    /** Blog jobs whose long-blog confirmation has not been answered yet. */
    val blogConfirmPending = blogManager.confirmPending

    fun blogIsActive(jobId: String): Boolean = blogManager.isActive(jobId)
    val blogActiveJobIds = blogManager.activeJobIds
    /** Suspends until the card restore has finished and the job card is on disk. */
    suspend fun enqueueBlogUrl(rawUrl: String, options: BlogDownloadOptions): Boolean =
        blogManager.enqueueBlog(rawUrl, options)
    fun resumeBlogJob(jobId: String) = blogManager.resume(jobId)
    fun cancelBlogJob(jobId: String) = blogManager.cancel(jobId)
    fun removeBlogJob(jobId: String) = blogManager.remove(jobId)

    fun pauseBlogCard(jobId: String) = blogManager.pause(jobId)
    fun respondBlogLong(jobId: String, proceed: Boolean) = blogManager.respondLongBlog(jobId, proceed)

    // ── non-download VM state ─────────────────────────────────────────
    private val _autoPasteUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoPasteUrl = _autoPasteUrl.asSharedFlow()

    /**
     * A blog URL waiting for the type dialog (or for a direct start when
     * "auto download all media" is on).
     *
     * A StateFlow rather than a SharedFlow: the DownloadFragment may be
     * stopped when the clipboard/share hands the URL over (the user is on the
     * other tab), and the prompt must still arrive once it is visible again —
     * then it is consumed so it never pops twice.
     */
    private val _blogPrompt = MutableStateFlow<String?>(null)
    val blogPrompt: StateFlow<String?> = _blogPrompt

    private val _downloadDirectoryLabel = MutableStateFlow(DownloadUtils.getDownloadDirectoryLabel(appContext))
    val downloadDirectoryLabel: StateFlow<String> = _downloadDirectoryLabel.asStateFlow()

    private val _tumblrAccount = MutableStateFlow(TumblrAccountStore.load(appContext))
    val tumblrAccount: StateFlow<TumblrAccount> = _tumblrAccount.asStateFlow()

    init {
        viewModelScope.launch {
            val restored = DownloadHistoryStore.load(appContext).map { item ->
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> item.copy(
                        status = DownloadStatus.FAILED,
                        progress = 0,
                        errorMessage = LocaleHelper.contextForAppLocale(appContext).getString(R.string.restored_state_invalid)
                    )
                    else -> item
                }
            }
            stateManager.restore(restored)

            // Cookie status hint — now backed by Room.
            val showHint = withContext(Dispatchers.IO) {
                TumblrCookieStore.hasSavedCookies(appContext) &&
                        TumblrCookieStore.shouldShowSecurityNotice(appContext)
            }
            if (showHint) {
                TumblrCookieStore.markSecurityNoticeShown(appContext)
            }
        }

        refreshDownloadDirectoryLabel()
        refreshTumblrAccount()
    }

    // ── delegated to stateManager (sequential, race-free) ─────────────
    /** The pre-download picker is decided by [AutoDownloadPrefs], not per call. */
    fun enqueueFromUrl(rawUrl: String): Boolean =
        stateManager.enqueueFromUrl(rawUrl)

    fun confirmMediaPreview(previewId: String, selectedIndices: Set<Int>) =
        stateManager.confirmMediaPreview(previewId, selectedIndices)

    fun cancelMediaPreview(previewId: String) = stateManager.cancelMediaPreview(previewId)

    fun startOrResumeDownload(itemId: String) = stateManager.startOrResume(itemId)

    fun pauseDownload(itemId: String) = stateManager.pause(itemId)

    fun removeDownload(itemId: String) {
        // NOTE: this must NOT clear `last_auto_detected_url`. The clipboard
        // dedupe key is what stops a stale link (still sitting in the
        // clipboard) from being re-processed on every focus change — clearing
        // it here made removing one row silently restart whatever blog link
        // was last copied (#20). Re-copying a link still re-triggers detection
        // via the clip-change signal, and Settings → 清空自动检测缓存 remains
        // the explicit way to reset it.
        stateManager.remove(itemId)
    }

    fun clearAllDownloads() {
        // Same as removeDownload: clearing history is unrelated to the
        // clipboard dedupe key (see #20).
        stateManager.clearAll()
    }

    fun pauseAllDownloads() = stateManager.pauseAll()

    fun resumeAllDownloads() = stateManager.resumeAll()

    fun retryPendingLoginUrl() {
        // beginLoginRetry consumes the pending URL AND sets a guard so
        // clipboard auto-detect won't race ahead and steal the parse slot.
        val url = stateManager.beginLoginRetry() ?: return

        viewModelScope.launch {
            val cookiesReady = withContext(Dispatchers.IO) {
                TumblrCookieStore.waitForCookiesReady()
            }

            if (cookiesReady) {
                stateManager.markRetryAfterLogin()
                stateManager.enqueueFromUrl(url, loginRetry = true)
            } else {
                Log.w(TAG, "retryPendingLoginUrl: cookie sync timeout, URL preserved for manual retry")
                stateManager.emitParseEvent(
                    ParseEvent.CookieSecurityNotice(
                        LocaleHelper.contextForAppLocale(appContext).getString(R.string.cookie_sync_timeout)
                    )
                )
            }
            stateManager.endLoginRetry()
        }
    }

    // ── non-delegated VM methods ──────────────────────────────────────

    /**
     * Returns true if all items associated with the given Tumblr URL
     * have been fully downloaded (status == COMPLETED).
     * Used by clipboard auto-detect to avoid re-parsing already-downloaded posts.
     */
    fun isUrlFullyCompleted(rawUrl: String): Boolean {
        val normalized = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false
        val urlBase = normalized.substringBefore('?').trimEnd('/')
        val itemsForUrl = stateManager.items.value.filter { item ->
            item.sourceUrl.substringBefore('?').trimEnd('/') == urlBase
        }
        return itemsForUrl.isNotEmpty() && itemsForUrl.all { it.status == DownloadStatus.COMPLETED }
    }

    fun notifyFromClipboard(url: String) {
        viewModelScope.launch { _autoPasteUrl.emit(url) }
    }

    /** Ask the Download tab to run a blog URL through [promptBlogOptions]. */
    fun promptBlogOptions(url: String) {
        _blogPrompt.value = url
    }

    /** The Download tab took the prompt — don't show it again. */
    fun consumeBlogPrompt() {
        _blogPrompt.value = null
    }

    fun setTumblrAccount(username: String) {
        val account = TumblrAccount(
            username = username,
            avatarUrl = "https://api.tumblr.com/v2/blog/${username}/avatar/512",
            status = appContext.getString(R.string.status_online),
            isLoggedIn = true
        )
        TumblrAccountStore.save(appContext, account)
        _tumblrAccount.value = account
    }

    fun refreshTumblrAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            val account = TumblrAccountStore.fetchAccountInfo(appContext)
            _tumblrAccount.value = account
        }
    }

    fun clearSavedCookies() {
        viewModelScope.launch {
            TumblrCookieStore.clear(appContext)
            _tumblrAccount.value = TumblrAccount()
        }
    }

    fun setCustomDownloadDirectory(uri: Uri) {
        DownloadUtils.setCustomDownloadDirectory(appContext, uri)
        refreshDownloadDirectoryLabel()
    }

    fun resetDownloadDirectory() {
        DownloadUtils.clearCustomDownloadDirectory(appContext)
        refreshDownloadDirectoryLabel()
    }

    private fun refreshDownloadDirectoryLabel() {
        _downloadDirectoryLabel.value = DownloadUtils.getDownloadDirectoryLabel(appContext)
    }

    /*
     * emitCookieStatusHint was moved into the init {} coroutine above
     * when the stores migrated to Room (suspend functions).
     */
}
