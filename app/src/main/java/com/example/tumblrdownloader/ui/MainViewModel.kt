package com.example.tumblrdownloader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import java.util.Locale
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
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 1
    }

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
                var shouldStartNext = false
                updateDownloads { list ->
                    list.map { existing ->
                        if (existing.id != item.id) {
                            return@map existing
                        }

                        shouldStartNext = existing.status == DownloadStatus.DOWNLOADING && item.status != DownloadStatus.DOWNLOADING
                        item
                    }
                }

                if (shouldStartNext) {
                    scheduleQueuedDownloads()
                }
            }
        }
    }

    init {
        DownloadService.progressListener = serviceProgressListener

        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val loaded = DownloadHistoryStore.load(appContext)
                loaded.map {
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
            }
            updateDownloads { restored }
            scheduleQueuedDownloads()
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

        val existingKeys = _downloads.value
            .map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }
            .toMutableSet()

        val baseTime = System.currentTimeMillis()
        val added = candidates
            .mapIndexed { index, media ->
                DownloadItem(
                    sourceUrl = media.sourceUrl,
                    mediaUrl = media.mediaUrl,
                    title = displayFileName(media.sourceUrl, media.mediaUrl),
                    type = media.type,
                    createdAt = baseTime + (candidates.size - index)
                )
            }
            .filter { item ->
                existingKeys.add(DownloadUtils.normalizeMediaIdentity(item.mediaUrl))
            }

        if (added.isEmpty()) {
            viewModelScope.launch {
                _parseEvent.emit(ParseEvent.Message("该链接中的媒体已在下载列表中，已跳过重复项。"))
            }
            return
        }

        updateDownloads { list -> added + list }
        viewModelScope.launch {
            _parseEvent.emit(ParseEvent.Queued(added.size))
        }
        scheduleQueuedDownloads()
    }

    private fun displayFileName(sourceUrl: String, mediaUrl: String): String {
        val author = authorFromSource(sourceUrl)
        val mediaId = mediaIdFromUrl(mediaUrl)
        val ext = inferExtension(mediaUrl)
        return "${DownloadUtils.sanitizeFileName("${author}-${mediaId}")}.${ext}"
    }

    private fun authorFromSource(sourceUrl: String): String {
        val path = runCatching { Uri.parse(sourceUrl).path?.trim('/')?.split('/') ?: emptyList<String>() }
            .getOrDefault(emptyList<String>())
        return path.firstOrNull()?.takeIf { it.isNotBlank() } ?: "tumblr"
    }

    private fun mediaIdFromUrl(mediaUrl: String): String {
        val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return "media"
        val lastSegment = uri.lastPathSegment
            ?.substringBefore('?')
            ?.let(Uri::decode)
            ?: ""

        if (lastSegment.isNotBlank()) {
            return lastSegment.substringBeforeLast('.').ifBlank { lastSegment }
        }

        val fallback = uri.toString().substringAfterLast('/', "media").substringBefore('?')
        return fallback.substringBeforeLast('.').ifBlank { "media" }
    }

    private fun inferExtension(mediaUrl: String): String {
        val path = runCatching { Uri.parse(mediaUrl).lastPathSegment.orEmpty() }.getOrDefault("")
        val rawExt = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return if (rawExt in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) rawExt else "bin"
    }

    private fun scheduleQueuedDownloads() {
        val queued = _downloads.value
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedByDescending { it.createdAt }

        val runningCount = _downloads.value.count { it.status == DownloadStatus.DOWNLOADING }
        val availableSlots = MAX_CONCURRENT_DOWNLOADS - runningCount
        if (availableSlots <= 0 || queued.isEmpty()) return

        queued.take(availableSlots).forEach { item ->
            val toStart = item.copy(
                status = DownloadStatus.DOWNLOADING,
                progress = 0,
                errorMessage = null,
                retryCount = if (item.status == DownloadStatus.FAILED) 0 else item.retryCount
            )
            updateDownloads { list -> list.map { if (it.id == item.id) toStart else it } }
            startDownload(toStart)
        }
    }

    private fun startDownload(item: DownloadItem) {
        val intent = Intent(appContext, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
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

    fun startOrResumeDownload(itemId: String) {
        val target = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (target.status == DownloadStatus.DOWNLOADING || target.status == DownloadStatus.COMPLETED) return

        val runningCount = _downloads.value.count { it.status == DownloadStatus.DOWNLOADING }
        val canStartNow = runningCount < MAX_CONCURRENT_DOWNLOADS

        val toStart = target.copy(
            status = if (canStartNow) DownloadStatus.DOWNLOADING else DownloadStatus.QUEUED,
            progress = 0,
            errorMessage = null,
            retryCount = if (target.status == DownloadStatus.FAILED) 0 else target.retryCount
        )
        updateDownloads { list -> list.map { if (it.id == itemId) toStart else it } }

        if (canStartNow) {
            startDownload(toStart)
        }
        scheduleQueuedDownloads()
    }

    fun pauseDownload(itemId: String) {
        val target = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (target.status == DownloadStatus.COMPLETED || target.status == DownloadStatus.PAUSED) return

        updateDownloads { list ->
            list.map {
                if (it.id != itemId) it else it.copy(
                    status = DownloadStatus.PAUSED,
                    errorMessage = null
                )
            }
        }

        val intent = Intent(appContext, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_ITEM_ID, itemId)
        }
        appContext.startService(intent)

        scheduleQueuedDownloads()
    }

    fun removeDownload(itemId: String) {
        updateDownloads { list -> list.filterNot { it.id == itemId } }

        val intent = Intent(appContext, DownloadService::class.java).apply {
            action = DownloadService.ACTION_REMOVE
            putExtra(DownloadService.EXTRA_ITEM_ID, itemId)
        }
        appContext.startService(intent)

        scheduleQueuedDownloads()
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
