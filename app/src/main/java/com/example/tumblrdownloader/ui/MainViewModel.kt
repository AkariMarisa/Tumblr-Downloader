package com.example.tumblrdownloader.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStateManager
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.service.DownloadService
import com.example.tumblrdownloader.model.ParseEvent
import com.example.tumblrdownloader.utils.DownloadHistoryStore
import com.example.tumblrdownloader.utils.DownloadUtils
import com.example.tumblrdownloader.utils.TumblrAccount
import com.example.tumblrdownloader.utils.TumblrAccountStore
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.utils.TumblrCookieStore
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

    // ── download state manager (sequential, race-free) ────────────────
    val stateManager = DownloadStateManager(appContext).also { sm ->
        DownloadService.progressListener = sm.serviceProgressListener
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
        // Restore persisted items on boot — uses main thread (immediate);
        // the DiskHistoryStore load runs on IO, but the restore call itself is
        // on main after the suspend completes, so the restored list is set before
        // any user interaction queued on the main thread.
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                DownloadHistoryStore.load(appContext).map { item ->
                    when (item.status) {
                        DownloadStatus.DOWNLOADING -> item.copy(
                            status = DownloadStatus.FAILED,
                            progress = 0,
                            errorMessage = appContext.getString(R.string.restored_state_invalid)
                        )
                        else -> item
                    }
                }
            }
            stateManager.restore(restored)
        }

        refreshDownloadDirectoryLabel()
        refreshTumblrAccount()
        emitCookieStatusHint()
    }

    override fun onCleared() {
        if (DownloadService.progressListener === stateManager.serviceProgressListener) {
            DownloadService.progressListener = null
        }
        super.onCleared()
    }

    // ── delegated to stateManager (sequential, race-free) ─────────────
    fun enqueueFromUrl(rawUrl: String): Boolean = stateManager.enqueueFromUrl(rawUrl)

    fun startOrResumeDownload(itemId: String) = stateManager.startOrResume(itemId)

    fun pauseDownload(itemId: String) = stateManager.pause(itemId)

    fun removeDownload(itemId: String) = stateManager.remove(itemId)

    fun clearAllDownloads() = stateManager.clearAll()

    fun retryPendingLoginUrl() {
        val url = stateManager.peekPendingLoginUrl() ?: return

        viewModelScope.launch {
            val cookiesReady = withContext(Dispatchers.IO) {
                TumblrCookieStore.waitForCookiesReady()
            }

            if (cookiesReady) {
                stateManager.consumePendingLoginUrl()
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
        }
    }

    // ── non-delegated VM methods ──────────────────────────────────────
    fun notifyFromClipboard(url: String) {
        viewModelScope.launch { _autoPasteUrl.emit(url) }
    }

    fun setTumblrAccount(username: String) {
        val account = TumblrAccount(
            username = username,
            avatarUrl = "https://api.tumblr.com/v2/blog/${username}/avatar/512",
            status = "Online",
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
        TumblrCookieStore.clear(appContext)
        _tumblrAccount.value = TumblrAccount()
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

    private fun emitCookieStatusHint() {
        val showHint = TumblrCookieStore.hasSavedCookies(appContext) &&
            TumblrCookieStore.shouldShowSecurityNotice(appContext)
        if (!showHint) return
        TumblrCookieStore.markSecurityNoticeShown(appContext)
    }
}
