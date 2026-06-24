package com.example.tumblrdownloader.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.service.DownloadService
import com.example.tumblrdownloader.utils.ParsedTumblrMedia
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

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _autoPasteUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoPasteUrl = _autoPasteUrl.asSharedFlow()

    private val _parseEvent = MutableSharedFlow<ParseEvent>(extraBufferCapacity = 1)
    val parseEvent = _parseEvent.asSharedFlow()

    private var pendingLoginUrl: String? = null

    private val serviceProgressListener = object : DownloadService.ProgressListener {
        override fun onDownloadUpdate(item: DownloadItem) {
            viewModelScope.launch(Dispatchers.Main) {
                _downloads.value = _downloads.value.map { existing ->
                    if (existing.id == item.id) item else existing
                }
            }
        }
    }

    init {
        DownloadService.progressListener = serviceProgressListener
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

        _downloads.value = _downloads.value + added
        added.forEach { item ->
            startDownload(item)
        }
    }

    private fun startDownload(item: DownloadItem) {
        val context = getApplication<Application>()
        val intent = Intent(context, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_ITEM_ID, item.id)
            putExtra(DownloadService.EXTRA_SOURCE_URL, item.sourceUrl)
            putExtra(DownloadService.EXTRA_MEDIA_URL, item.mediaUrl)
            putExtra(DownloadService.EXTRA_TYPE, item.type.name)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_RETRY_COUNT, item.retryCount)
            putExtra(DownloadService.EXTRA_MAX_RETRIES, item.maxRetries)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
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

        _downloads.value = _downloads.value.map {
            if (it.id == itemId) retried else it
        }

        startDownload(retried)
    }

    fun notifyFromClipboard(url: String) {
        viewModelScope.launch {
            _autoPasteUrl.emit(url)
        }
    }

    fun updateProgress(itemId: String, progress: Int, status: DownloadStatus) {
        _downloads.value = _downloads.value.map { item ->
            if (item.id != itemId) item else item.copy(status = status, progress = progress)
        }
    }
}

sealed class ParseEvent {
    data class Message(val text: String) : ParseEvent()
    data class LoginRequired(val url: String, val message: String) : ParseEvent()
}
