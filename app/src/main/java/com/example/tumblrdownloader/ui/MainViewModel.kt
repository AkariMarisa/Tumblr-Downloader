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

    private val _parseMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val parseMessage = _parseMessage.asSharedFlow()

    fun enqueueFromUrl(rawUrl: String): Boolean {
        val url = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false

        viewModelScope.launch {
            when (val result = TumblrParser.parseShareUrl(url)) {
                is TumblrShareParseResult.Success -> {
                    appendItems(result.media)
                }
                is TumblrShareParseResult.LoginRequired -> {
                    _parseMessage.emit("${result.message} 你可在 Tumblr 网站确认该链接是否公开，或提供登录凭据后继续。")
                }
                is TumblrShareParseResult.Error -> {
                    _parseMessage.emit(result.message)
                }
                is TumblrShareParseResult.Empty -> {
                    _parseMessage.emit(result.message)
                }
            }
        }

        return true
    }

    private fun appendItems(candidates: List<ParsedTumblrMedia>) {
        if (candidates.isEmpty()) {
            viewModelScope.launch {
                _parseMessage.emit("该链接未识别到可下载媒体")
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
            val context = getApplication<Application>()
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(DownloadService.EXTRA_ITEM_ID, item.id)
                putExtra(DownloadService.EXTRA_SOURCE_URL, item.sourceUrl)
                putExtra(DownloadService.EXTRA_MEDIA_URL, item.mediaUrl)
                putExtra(DownloadService.EXTRA_TYPE, item.type.name)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
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
