package com.example.tumblrdownloader.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.service.DownloadService
import com.example.tumblrdownloader.utils.TumblrParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _autoPasteUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoPasteUrl = _autoPasteUrl

    fun enqueueFromUrl(rawUrl: String): Boolean {
        val url = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false
        val items = TumblrParser.parseMediaCandidates(url)

        if (items.isEmpty()) {
            return false
        }

        val added = items.mapIndexed { index, mediaUrl ->
            DownloadItem(
                sourceUrl = url,
                mediaUrl = mediaUrl,
                title = "Item ${index + 1}",
                type = TumblrParser.guessType(mediaUrl)
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

        return true
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
