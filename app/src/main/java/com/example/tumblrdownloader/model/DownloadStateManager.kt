package com.example.tumblrdownloader.model

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.tumblrdownloader.service.DownloadService
import com.example.tumblrdownloader.utils.CompletedMediaStore
import com.example.tumblrdownloader.utils.DownloadHistoryStore
import com.example.tumblrdownloader.utils.DownloadUtils
import com.example.tumblrdownloader.utils.ParsedTumblrMedia
import com.example.tumblrdownloader.utils.TumblrParser
import com.example.tumblrdownloader.utils.TumblrShareParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-owner sequential command processor for all download state.
 *
 * Every state transition passes through a single coroutine that validates,
 * applies, and persists changes.  The ViewModel and Service delegate all
 * mutations here — no more races between user actions and progress callbacks.
 */
class DownloadStateManager(private val app: Application) {

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 1
    }

    // ── state ──────────────────────────────────────────────────────────
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    // ── parse events emitted to UI ─────────────────────────────────────
    private val _parseEvent = MutableSharedFlow<ParseEvent>(extraBufferCapacity = 4)
    val parseEvent = _parseEvent.asSharedFlow()

    private var pendingLoginUrl: String? = null

    // ── command queue (single consumer processes one at a time) ────────
    private sealed class Cmd {
        data class Append(val candidates: List<ParsedTumblrMedia>) : Cmd()
        data class StartOrResume(val itemId: String) : Cmd()
        data class Pause(val itemId: String) : Cmd()
        data class Remove(val itemId: String) : Cmd()
        data object ClearAll : Cmd()
        data class Progress(val item: DownloadItem) : Cmd()
    }

    private val cmdCh = Channel<Cmd>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        scope.launch { for (cmd in cmdCh) process(cmd) }
    }

    // ── public API ─────────────────────────────────────────────────────

    /** Progress listener for [DownloadService]. */
    val serviceProgressListener = object : DownloadService.ProgressListener {
        override fun onDownloadUpdate(item: DownloadItem) {
            cmdCh.trySend(Cmd.Progress(item))
        }
    }

    /** Parse and queue items from a Tumblr share URL.  Returns false if invalid. */
    fun enqueueFromUrl(rawUrl: String): Boolean {
        val url = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false
        scope.launch(Dispatchers.IO) {
            val result = try {
                TumblrParser.parseShareUrl(url)
            } catch (e: Exception) {
                // network / parse errors are reported inside parseShareUrl as Error
                _parseEvent.tryEmit(ParseEvent.Message("解析失败：${e.message ?: "未知错误"}"))
                return@launch
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is TumblrShareParseResult.Success -> cmdCh.trySend(Cmd.Append(result.media))
                    is TumblrShareParseResult.LoginRequired -> {
                        pendingLoginUrl = result.url.ifBlank { url }
                        _parseEvent.tryEmit(
                            ParseEvent.LoginRequired(
                                url = result.url.ifBlank { url },
                                message = "登录后可重试。"
                            )
                        )
                    }
                    is TumblrShareParseResult.Error -> _parseEvent.tryEmit(ParseEvent.Message(result.message))
                    is TumblrShareParseResult.Empty -> _parseEvent.tryEmit(ParseEvent.Message(result.message))
                }
            }
        }
        return true
    }

    /** Directly restore a set of DownloadItems (e.g. from persistence). */
    fun restore(items: List<DownloadItem>) {
        // Items from persistence are fully formed — just set the list directly.
        // No dedup needed (they came from us).
        _items.value = items.sortedByDescending { it.createdAt }
        startNextIfSlotAvailable()
    }

    fun startOrResume(itemId: String) { cmdCh.trySend(Cmd.StartOrResume(itemId)) }

    fun pause(itemId: String) { cmdCh.trySend(Cmd.Pause(itemId)) }

    fun remove(itemId: String) { cmdCh.trySend(Cmd.Remove(itemId)) }

    fun clearAll() { cmdCh.trySend(Cmd.ClearAll) }

    fun consumePendingLoginUrl(): String? {
        val u = pendingLoginUrl
        pendingLoginUrl = null
        return u
    }

    // ── command processing (sequential on main thread) ────────────────
    private fun process(cmd: Cmd) {
        when (cmd) {
            is Cmd.Append -> processAppend(cmd.candidates)
            is Cmd.StartOrResume -> processStartOrResume(cmd.itemId)
            is Cmd.Pause -> processPause(cmd.itemId)
            is Cmd.Remove -> processRemove(cmd.itemId)
            is Cmd.ClearAll -> processClearAll()
            is Cmd.Progress -> processProgress(cmd.item)
        }
        persistDirty()
    }

    // ── Append (from parse result) ─────────────────────────────────────
    private fun processAppend(candidates: List<ParsedTumblrMedia>) {
        if (candidates.isEmpty()) {
            _parseEvent.tryEmit(ParseEvent.Message("该链接未识别到可下载媒体"))
            return
        }

        val existing = _items.value
        val existingKeys = existing.map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }.toMutableSet()

        val baseTime = System.currentTimeMillis()
        val added = candidates
            .asSequence()
            .distinctBy { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }
            .mapIndexed { index, media ->
                DownloadItem(
                    sourceUrl = media.sourceUrl,
                    mediaUrl = media.mediaUrl,
                    title = displayFileName(media.sourceUrl, media.mediaUrl),
                    type = media.type,
                    createdAt = baseTime + (candidates.size - index)
                )
            }
            .filter { existingKeys.add(DownloadUtils.normalizeMediaIdentity(it.mediaUrl)) }
            .toList()

        if (added.isEmpty()) {
            _parseEvent.tryEmit(ParseEvent.Message("该链接中的媒体已在下载列表中，已跳过重复项。"))
            return
        }

        _items.value = (added + existing).sortedByDescending { it.createdAt }
        _parseEvent.tryEmit(ParseEvent.Queued(added.size))
        dirty = true
        startNextIfSlotAvailable()
    }

    // ── Start / Resume ──────────────────────────────────────────────────
    private fun processStartOrResume(itemId: String) {
        val idx = _items.value.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _items.value[idx]

        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.COMPLETED) return

        val runningCount = _items.value.count { it.status == DownloadStatus.DOWNLOADING }
        val canStartNow = runningCount < MAX_CONCURRENT_DOWNLOADS

        val toStart = item.copy(
            status = if (canStartNow) DownloadStatus.DOWNLOADING else DownloadStatus.QUEUED,
            progress = 0,
            errorMessage = null,
            retryCount = if (item.status == DownloadStatus.FAILED) 0 else item.retryCount
        )
        replaceItem(idx, toStart)

        if (canStartNow) sendStartIntent(toStart)
    }

    // ── Pause ───────────────────────────────────────────────────────────
    private fun processPause(itemId: String) {
        val idx = _items.value.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _items.value[idx]

        if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.PAUSED) return

        replaceItem(idx, item.copy(status = DownloadStatus.PAUSED, errorMessage = null))
        sendPauseIntent(itemId)
    }

    // ── Remove ──────────────────────────────────────────────────────────
    private fun processRemove(itemId: String) {
        val item = _items.value.find { it.id == itemId }
        item?.let { CompletedMediaStore.remove(app, it.mediaUrl) }
        _items.value = _items.value.filterNot { it.id == itemId }
        dirty = true
        sendRemoveIntent(itemId)
    }

    // ── Clear All ───────────────────────────────────────────────────────
    private fun processClearAll() {
        _items.value = emptyList()
        CompletedMediaStore.clear(app)
        DownloadHistoryStore.clear(app)
        sendClearAllIntent()
        dirty = false
    }

    // ── Progress callback (from service) ────────────────────────────────
    private fun processProgress(update: DownloadItem) {
        _items.value = _items.value.map { existing ->
            if (existing.id != update.id) return@map existing

            // ponytail: ignore stale DOWNLOADING progress when user already paused
            // the item.  Allow COMPLETED/FAILED to go through even if stale.
            when {
                existing.status == DownloadStatus.PAUSED && update.status == DownloadStatus.DOWNLOADING -> existing
                existing.status == DownloadStatus.COMPLETED && update.status != DownloadStatus.COMPLETED -> existing
                else -> update
            }
        }
        dirty = true

        // When a terminal status (COMPLETED/FAILED/PAUSED) arrives for the
        // currently-DOWNLOADING item, try to start the next queued one.
        val updateIdx = _items.value.indexOfFirst { it.id == update.id }
        if (updateIdx >= 0) {
            val item = _items.value[updateIdx]
            if (item.status != DownloadStatus.DOWNLOADING) {
                startNextIfSlotAvailable()
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun replaceItem(idx: Int, newItem: DownloadItem) {
        val list = _items.value.toMutableList()
        list[idx] = newItem
        _items.value = list
        dirty = true
    }

    private fun startNextIfSlotAvailable() {
        val running = _items.value.count { it.status == DownloadStatus.DOWNLOADING }
        if (running >= MAX_CONCURRENT_DOWNLOADS) return

        val next = _items.value
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedByDescending { it.createdAt }
            .firstOrNull() ?: return

        val idx = _items.value.indexOfFirst { it.id == next.id }
        val started = next.copy(status = DownloadStatus.DOWNLOADING, progress = 0)
        replaceItem(idx, started)
        sendStartIntent(started)
    }

    // ── Intent helpers ──────────────────────────────────────────────────

    private fun sendStartIntent(item: DownloadItem) {
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_ITEM_ID, item.id)
            putExtra(DownloadService.EXTRA_SOURCE_URL, item.sourceUrl)
            putExtra(DownloadService.EXTRA_MEDIA_URL, item.mediaUrl)
            putExtra(DownloadService.EXTRA_TYPE, item.type.name)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_RETRY_COUNT, item.retryCount)
            putExtra(DownloadService.EXTRA_MAX_RETRIES, item.maxRetries)
            if (item.downloadedBytes > 0L) {
                putExtra(DownloadService.EXTRA_DOWNLOADED_BYTES, item.downloadedBytes)
            }
            if (!item.downloadFileUri.isNullOrBlank()) {
                putExtra(DownloadService.EXTRA_FILE_URI, item.downloadFileUri)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    private fun sendPauseIntent(itemId: String) {
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_ITEM_ID, itemId)
        }
        app.startService(intent)
    }

    private fun sendRemoveIntent(itemId: String) {
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_REMOVE
            putExtra(DownloadService.EXTRA_ITEM_ID, itemId)
        }
        app.startService(intent)
    }

    private fun sendClearAllIntent() {
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CLEAR_ALL
        }
        app.startService(intent)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private var dirty = false
    private var persistJob: Job? = null

    /** Debounced persist — coalesces rapid mutations into one disk write. */
    private fun persistDirty() {
        if (!dirty) return
        dirty = false
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(100)
            withContext(Dispatchers.IO) {
                DownloadHistoryStore.save(app, _items.value)
            }
        }
    }

    // ── Display helpers ─────────────────────────────────────────────────

    private fun displayFileName(sourceUrl: String, mediaUrl: String): String {
        val author = sourceAuthor(sourceUrl)
        val mediaId = mediaIdFromUrl(mediaUrl)
        val ext = inferExtension(mediaUrl)
        return "${DownloadUtils.sanitizeFileName("${author}-${mediaId}")}.${ext}"
    }

    private fun sourceAuthor(sourceUrl: String): String {
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
        val rawExt = path.substringAfterLast('.', "").lowercase(java.util.Locale.getDefault())
        return if (rawExt in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) rawExt else "bin"
    }
}

sealed class ParseEvent {
    data class Message(val text: String) : ParseEvent()
    data class LoginRequired(val url: String, val message: String) : ParseEvent()
    data class Queued(val count: Int) : ParseEvent()
    data class CookieSecurityNotice(val text: String) : ParseEvent()
}
