package io.github.akarimarisa.tumblrdownloader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStateManager
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.service.DownloadService
import io.github.akarimarisa.tumblrdownloader.model.ParseEvent
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
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
    // (MainActivity AND SettingsActivity each have their own instance), so
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
     * return to the screen); SettingsActivity's ViewModel never claims the
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

    // ── non-download VM state ─────────────────────────────────────────
    private val _autoPasteUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoPasteUrl = _autoPasteUrl.asSharedFlow()

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
                        errorMessage = appContext.getString(R.string.restored_state_invalid)
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
    fun enqueueFromUrl(rawUrl: String): Boolean = stateManager.enqueueFromUrl(rawUrl)

    fun startOrResumeDownload(itemId: String) = stateManager.startOrResume(itemId)

    fun pauseDownload(itemId: String) = stateManager.pause(itemId)

    fun removeDownload(itemId: String) {
        // Clear auto-detect cache so users can re-download the same link
        // after deleting a task (the cache only blocks the exact last URL).
        prefs.edit().remove("last_auto_detected_url").apply()
        stateManager.remove(itemId)
    }

    fun clearAllDownloads() {
        prefs.edit().remove("last_auto_detected_url").apply()
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
                enqueueFromUrl(url)
            } else {
                Log.w(TAG, "retryPendingLoginUrl: cookie sync timeout, URL preserved for manual retry")
                stateManager.emitParseEvent(
                    ParseEvent.CookieSecurityNotice(
                        getApplication<Application>().getString(R.string.cookie_sync_timeout)
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
