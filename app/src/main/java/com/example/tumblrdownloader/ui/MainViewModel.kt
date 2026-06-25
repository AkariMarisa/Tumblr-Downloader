package com.example.tumblrdownloader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.service.DownloadService
import com.example.tumblrdownloader.utils.DownloadHistoryStore
import com.example.tumblrdownloader.utils.DownloadUtils
import com.example.tumblrdownloader.utils.ParsedTumblrMedia
import com.example.tumblrdownloader.utils.TumblrCookieStore
import com.example.tumblrdownloader.utils.TumblrParser
import com.example.tumblrdownloader.utils.TumblrShareParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _autoPasteUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoPasteUrl = _autoPasteUrl.asSharedFlow()

    private val _parseEvent = MutableSharedFlow<ParseEvent>(extraBufferCapacity = 1)
    val parseEvent = _parseEvent.asSharedFlow()

    private val _downloadDirectoryLabel = MutableStateFlow(DownloadUtils.getDownloadDirectoryLabel(appContext))
    val downloadDirectoryLabel: StateFlow<String> = _downloadDirectoryLabel.asStateFlow()

    private var pendingLoginUrl: String? = null

    private val serviceProgressListener = object : DownloadService.ProgressListener {
        override fun onDownloadUpdate(item: DownloadItem) {
            viewModelScope.launch(Dispatchers.Main) {
                updateDownloads { list ->
                    list.map { existing -> if (existing.id == item.id) item else existing }
                }
            }
        }
    }

    init {
        DownloadService.progressListener = serviceProgressListener

        viewModelScope.launch(Dispatchers.IO) {
            val restored = DownloadHistoryStore.load(appContext)
            val normalized = restored.map {
                if (it.status == DownloadStatus.DOWNLOADING) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        progress = 0,
                        errorMessage = "应用重启后状态已失效，可手动重试"
                    )
                } else {
                    it
                }
            }
            updateDownloads { normalized }
        }

        refreshDownloadDirectoryLabel()
        emitCookieStatusHint()
    }

    override fun onCleared() {
        if (DownloadService.progressListener === serviceProgressListener) {
            DownloadService.progressListener = null
        }
        super.onCleared()
    }

    fun enqueueFromUrl(rawUrl: String): Boolean {
        val url = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false

        viewModelScope.launch {
            when (val result = TumblrParser.parseShareUrl(url)) {
                is TumblrShareParseResult.Success -> {
                    appendItems(result.media)
                }

                is TumblrShareParseResult.LoginRequired -> {
                    pendingLoginUrl = result.url.ifBlank { url }
                    _parseEvent.emit(
                        ParseEvent.LoginRequired(
                            url = result.url.ifBlank { url },
                            message = "${result.message} 登录后可重试。"
                        )
                    )
                }

                is TumblrShareParseResult.Error -> {
                    _parseEvent.emit(ParseEvent.Message(result.message))
                }

                is TumblrShareParseResult.Empty -> {
                    _parseEvent.emit(ParseEvent.Message(result.message))
                }
            }
        }

        return true
    }

    fun retryPendingLoginUrl(): Boolean {
        val url = pendingLoginUrl ?: return false
        pendingLoginUrl = null
        return enqueueFromUrl(url)
    }

    fun clearSavedCookies() {
        TumblrCookieStore.clear(appContext)
        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.Message("已清除本地登录 Cookie（解析不再自动复用）"))
        }
    }

    fun setCustomDownloadDirectory(uri: Uri) {
        DownloadUtils.setCustomDownloadDirectory(appContext, uri)
        refreshDownloadDirectoryLabel()
        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.Message("已设置下载目录：${uri.path.orEmpty().ifBlank { uri.toString() }}"))
        }
    }

    fun resetDownloadDirectory() {
        DownloadUtils.clearCustomDownloadDirectory(appContext)
        refreshDownloadDirectoryLabel()
        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.Message("已恢复默认下载目录"))
        }
    }

    private fun appendItems(candidates: List<ParsedTumblrMedia>) {
        if (candidates.isEmpty()) {
            viewModelScope.launch {
                _parseEvent.emit(ParseEvent.Message("该链接未识别到可下载媒体"))
            }
            return
        }

        val added = candidates.mapIndexed { index, media ->
            DownloadItem(
                sourceUrl = media.sourceUrl,
                mediaUrl = media.mediaUrl,
                title = media.title.ifBlank { "Item ${index + 1}" },
                type = media.type
            )
        }

        updateDownloads { list -> added + list }
        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.Queued(added.size))
        }
        added.forEach { item ->
            startDownload(item)
        }
    }

    private fun startDownload(item: DownloadItem) {
        val intent = Intent(appContext, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_ITEM_ID, item.id)
            putExtra(DownloadService.EXTRA_SOURCE_URL, item.sourceUrl)
            putExtra(DownloadService.EXTRA_MEDIA_URL, item.mediaUrl)
            putExtra(DownloadService.EXTRA_TYPE, item.type.name)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_RETRY_COUNT, item.retryCount)
            putExtra(DownloadService.EXTRA_MAX_RETRIES, item.maxRetries)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun retryDownload(itemId: String) {
        val target = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (target.status != DownloadStatus.FAILED || target.retryCount < target.maxRetries) {
            return
        }

        val retried = target.copy(
            status = DownloadStatus.QUEUED,
            progress = 0,
            errorMessage = null,
            retryCount = 0
        )

        updateDownloads { list -> list.map { if (it.id == itemId) retried else it } }
        startDownload(retried)
    }

    fun notifyFromClipboard(url: String) {
        viewModelScope.launch {
            _autoPasteUrl.emit(url)
        }
    }

    fun updateProgress(itemId: String, progress: Int, status: DownloadStatus) {
        updateDownloads { list ->
            list.map { item ->
                if (item.id != itemId) item else item.copy(status = status, progress = progress)
            }
        }
    }

    private fun updateDownloads(transform: (List<DownloadItem>) -> List<DownloadItem>) {
        val updated = transform(_downloads.value).sortedByDescending { it.createdAt }
        _downloads.value = updated
        persistDownloads(updated)
    }

    private fun persistDownloads(items: List<DownloadItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            DownloadHistoryStore.save(appContext, items)
        }
    }

    private fun refreshDownloadDirectoryLabel() {
        _downloadDirectoryLabel.value = DownloadUtils.getDownloadDirectoryLabel(appContext)
    }

    private fun emitCookieStatusHint() {
        val showHint = TumblrCookieStore.hasSavedCookies(appContext) && TumblrCookieStore.shouldShowSecurityNotice(appContext)
        if (!showHint) return

        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.CookieSecurityNotice("检测到本地保存的登录 Cookie。用于解析私密内容，仅本地存储；如非本人设备请点击清除。"))
        }
        TumblrCookieStore.markSecurityNoticeShown(appContext)
    }
}

sealed class ParseEvent {
    data class Message(val text: String) : ParseEvent()
    data class LoginRequired(val url: String, val message: String) : ParseEvent()
    data class Queued(val count: Int) : ParseEvent()
    data class CookieSecurityNotice(val text: String) : ParseEvent()
}
