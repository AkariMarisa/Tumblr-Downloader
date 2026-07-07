package com.example.tumblrdownloader.model

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.tumblrdownloader.R
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

    private var isParsing = false

    // ── parse events emitted to UI ─────────────────────────────────────
    private val _parseEvent = MutableSharedFlow<ParseEvent>(extraBufferCapacity = 4)
    val parseEvent = _parseEvent.asSharedFlow()

    private var pendingLoginUrl: String? = null
    private var retryAfterLogin = false
    /**
     * Set when [retryPendingLoginUrl] is in progress (has consumed the
     * pending URL but hasn't called [enqueueFromUrl] yet).  Prevents
     * clipboard auto-detect from racing ahead and stealing the parse slot.
     */
    @Volatile
    private var retryUrlPending: String? = null

    // ── command queue (single consumer processes one at a time) ────────
    private sealed class Cmd {
        data class Append(val candidates: List<ParsedTumblrMedia>) : Cmd()
        data class Restore(val items: List<DownloadItem>) : Cmd()
        data class StartOrResume(val itemId: String) : Cmd()
        data class Pause(val itemId: String) : Cmd()
        data class Remove(val itemId: String) : Cmd()
        data object ClearAll : Cmd()
        data class Progress(val item: DownloadItem) : Cmd()
        data object PauseAll : Cmd()
        data object ResumeAll : Cmd()
    }

    private val cmdCh = Channel<Cmd>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        scope.launch {
            try {
                for (cmd in cmdCh) {
                    try {
                        process(cmd)
                    } catch (e: Exception) {
                        android.util.Log.e("DownloadSM", "FATAL: command processor crashed on $cmd", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadSM", "FATAL: channel consumer loop exited", e)
            }
        }
    }

    // ── public API ─────────────────────────────────────────────────────

    /** Progress listener for [DownloadService]. */
    val serviceProgressListener = object : DownloadService.ProgressListener {
        override fun onDownloadUpdate(item: DownloadItem) {
            cmdCh.trySend(Cmd.Progress(item))
        }
    }

    /**
     * Parse and queue items from a Tumblr share URL.  Returns false if
     * invalid, already parsing, or the URL was already queued/succeeded.
     */
    fun enqueueFromUrl(rawUrl: String): Boolean {
        if (isParsing) {
            android.util.Log.w("DownloadSM", "enqueueFromUrl: already parsing, rejecting $rawUrl")
            return false
        }
        // If a login retry is pending (the URL was consumed but the actual
        // parse hasn't started yet), reject clipboard-auto-detect attempts.
        if (retryUrlPending != null) return false
        val url = TumblrParser.firstTumblrUrl(rawUrl.trim()) ?: return false

        isParsing = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = TumblrParser.parseShareUrl(url)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is TumblrShareParseResult.Success -> {
                            retryAfterLogin = false
                            android.util.Log.d("DownloadSM", "enqueueFromUrl: ${result.media.size} candidates")
                            cmdCh.trySend(Cmd.Append(result.media))
                        }
                        is TumblrShareParseResult.LoginRequired -> {
                            if (retryAfterLogin) {
                                retryAfterLogin = false
                                android.util.Log.w("DownloadSM", "LoginRequired after login retry — giving up")
                                _parseEvent.tryEmit(
                                    ParseEvent.CookieSecurityNotice(
                                        app.getString(R.string.login_retry_failed)
                                    )
                                )
                            } else {
                                pendingLoginUrl = result.url.ifBlank { url }
                                _parseEvent.tryEmit(
                                    ParseEvent.LoginRequired(
                                        url = result.url.ifBlank { url },
                                        message = app.getString(R.string.parse_login_required)
                                    )
                                )
                            }
                        }
                        is TumblrShareParseResult.Error -> {
                            retryAfterLogin = false
                            _parseEvent.tryEmit(ParseEvent.Message(localizeError(result.message)))
                        }
                        is TumblrShareParseResult.Empty -> {
                            retryAfterLogin = false
                            _parseEvent.tryEmit(ParseEvent.Message(localizeError(result.message)))
                        }
                    }
                }
            } catch (e: Exception) {
                _parseEvent.tryEmit(ParseEvent.Message(app.getString(R.string.parse_error_generic, e.message ?: app.getString(R.string.download_unknown_error))))
            } finally {
                isParsing = false
            }
        }
        return true
    }

    /** Directly restore a set of DownloadItems (e.g. from persistence). */
    fun restore(items: List<DownloadItem>) {
        cmdCh.trySend(Cmd.Restore(items))
    }

    fun startOrResume(itemId: String) { cmdCh.trySend(Cmd.StartOrResume(itemId)) }

    fun pause(itemId: String) { cmdCh.trySend(Cmd.Pause(itemId)) }

    fun remove(itemId: String) { cmdCh.trySend(Cmd.Remove(itemId)) }

    fun clearAll() { cmdCh.trySend(Cmd.ClearAll) }

    fun pauseAll() { cmdCh.trySend(Cmd.PauseAll) }

    fun resumeAll() { cmdCh.trySend(Cmd.ResumeAll) }

    fun peekPendingLoginUrl(): String? = pendingLoginUrl

    /** Mark the next parse attempt as a post-login retry. */
    fun markRetryAfterLogin() { retryAfterLogin = true }

    /**
     * Begin a retry sequence: consume the pending URL and set the retry
     * guard so that clipboard auto-detect won't steal the slot.
     * @return the pending URL, or null if none.
     */
    fun beginLoginRetry(): String? {
        val url = pendingLoginUrl
        pendingLoginUrl = null
        if (url != null) {
            retryUrlPending = url
        }
        return url
    }

    /**
     * Called after [beginLoginRetry] when the actual parse starts.
     * Clears the retry guard.
     */
    fun endLoginRetry() {
        retryUrlPending = null
    }

    /** Emit a one-shot parse event from outside (e.g. cookie sync notice). */
    fun emitParseEvent(event: ParseEvent) {
        _parseEvent.tryEmit(event)
    }

    fun consumePendingLoginUrl(): String? {
        val u = pendingLoginUrl
        pendingLoginUrl = null
        return u
    }

    // ── command processing (sequential on main thread) ────────────────
    private fun process(cmd: Cmd) {
        when (cmd) {
            is Cmd.Append -> processAppend(cmd.candidates)
            is Cmd.Restore -> processRestore(cmd.items)
            is Cmd.StartOrResume -> processStartOrResume(cmd.itemId)
            is Cmd.Pause -> processPause(cmd.itemId)
            is Cmd.Remove -> processRemove(cmd.itemId)
            is Cmd.ClearAll -> processClearAll()
            is Cmd.PauseAll -> processPauseAll()
            is Cmd.ResumeAll -> processResumeAll()
            is Cmd.Progress -> processProgress(cmd.item)
        }
        persistDirty()
    }

    // ── Append (from parse result) ─────────────────────────────────────
    private fun processAppend(candidates: List<ParsedTumblrMedia>) {
        android.util.Log.d("DownloadSM", "processAppend: ${candidates.size} candidates")
        if (candidates.isEmpty()) {
            _parseEvent.tryEmit(ParseEvent.Message(app.getString(R.string.parse_no_new_media)))
            return
        }

        val existing = _items.value
        android.util.Log.d("DownloadSM", "processAppend: ${existing.size} existing items")
        // Only deduplicate against non-FAILED items.  A failed download
        // should be retryable — the user shouldn't have to remove-and-re-add.
        val existingKeys = existing
            .filter { it.status != DownloadStatus.FAILED }
            .map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }
            .toMutableSet()

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
                    createdAt = baseTime + index
                )
            }
            .filter { existingKeys.add(DownloadUtils.normalizeMediaIdentity(it.mediaUrl)) }
            .toList()

        android.util.Log.d("DownloadSM", "processAppend: ${added.size} new items after dedup")

        if (added.isEmpty()) {
            _parseEvent.tryEmit(ParseEvent.Message(app.getString(R.string.parse_duplicate_skipped)))
            return
        }

        // Log the identities of added items
        added.forEach { item ->
            android.util.Log.d("DownloadSM", "processAppend: adding id=${item.id} normalized=${DownloadUtils.normalizeMediaIdentity(item.mediaUrl)}")
        }

        _items.value = (added + existing).sortedByDescending { it.createdAt }
        _parseEvent.tryEmit(ParseEvent.Queued(added.size))
        dirty = true
        startNextIfSlotAvailable()
    }

    // ── Restore (from persistence) ─────────────────────────────────────
    private fun processRestore(items: List<DownloadItem>) {
        val existing = _items.value
        val existingNormIds = existing.map { DownloadUtils.normalizeMediaIdentity(it.mediaUrl) }.toSet()
        val merged = (existing + items.filterNot {
            DownloadUtils.normalizeMediaIdentity(it.mediaUrl) in existingNormIds
        }).sortedByDescending { it.createdAt }
        android.util.Log.d("DownloadSM", "processRestore: ${existing.size} existing + ${items.size} history = ${merged.size} merged")
        _items.value = merged
        dirty = true
        // ponytail: do NOT auto-start restored items — files from the previous
        // session still exist on disk.  Auto-starting would create "(1)" copies
        // via MediaStore's name conflict resolution.  User clicks Start manually.
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

    // ── Clear Completed ────────────────────────────────────────────────
    /**
     * Remove only [DownloadStatus.COMPLETED] items from the list.
     * Non-completed items (paused, failed, queued, downloading) are kept
     * so the user can retry or resume them later.
     */
    private fun processClearAll() {
        val toRemove = _items.value.filter { it.status == DownloadStatus.COMPLETED }
        if (toRemove.isEmpty()) return

        toRemove.forEach { item ->
            CompletedMediaStore.remove(app, item.mediaUrl)
        }
        _items.value = _items.value.filterNot { it.status == DownloadStatus.COMPLETED }
        dirty = true
    }

    // ── Pause All / Resume All ─────────────────────────────────────────
    private fun processPauseAll() {
        // Send a single batch intent to the service first (cancels active +
        // drains queue), then update all local statuses.
        sendPauseAllIntent()

        var changed = false
        _items.value = _items.value.map { item ->
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                changed = true
                item.copy(status = DownloadStatus.PAUSED, progress = 0, errorMessage = null)
            } else item
        }
        if (changed) dirty = true
    }

    private fun processResumeAll() {
        val items = _items.value
        val hasActive = items.any { it.status == DownloadStatus.DOWNLOADING }
        var startedOne = false

        for (item in items) {
            if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.FAILED) continue

            val idx = items.indexOfFirst { it.id == item.id }
            if (idx < 0) continue

            if (!startedOne && !hasActive) {
                startedOne = true
                val toStart = item.copy(
                    status = DownloadStatus.DOWNLOADING,
                    progress = 0,
                    errorMessage = null,
                    retryCount = if (item.status == DownloadStatus.FAILED) 0 else item.retryCount
                )
                replaceItem(idx, toStart)
                sendStartIntent(toStart)
            } else {
                // Already have a running download or just started one;
                // put the rest in QUEUED so they auto-start when the
                // slot opens.
                replaceItem(idx, item.copy(
                    status = DownloadStatus.QUEUED,
                    progress = 0,
                    errorMessage = null,
                    retryCount = if (item.status == DownloadStatus.FAILED) 0 else item.retryCount
                ))
            }
        }
    }

    // ── Progress callback (from service) ────────────────────────────────
    private fun processProgress(update: DownloadItem) {
        android.util.Log.d("DownloadSM", "processProgress: id=${update.id.take(8)}... st=${update.status} prog=${update.progress} dl=${update.downloadedBytes}")
        _items.value = _items.value.map { existing ->
            if (existing.id != update.id) return@map existing

            // ponytail: ignore stale DOWNLOADING progress when user already paused
            // the item.  Also coalesce successive PAUSED (the service's
            // cancellation handler may emit PAUSED after processPauseAll already
            // set it) to avoid unnecessary StateFlow emissions that cause
            // RecyclerView flicker.
            val oldSt = existing.status
            val result = when {
                existing.status == DownloadStatus.PAUSED && update.status == DownloadStatus.DOWNLOADING -> existing
                existing.status == DownloadStatus.PAUSED && update.status == DownloadStatus.PAUSED -> existing
                existing.status == DownloadStatus.COMPLETED && update.status != DownloadStatus.COMPLETED -> existing
                else -> update
            }
            if (result !== existing) {
                android.util.Log.d("DownloadSM", "processProgress: ${existing.id.take(8)}... ${oldSt}/${existing.progress}% → ${update.status}/${update.progress}%")
            }
            result
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

        // ponytail: FIFO 顺序 — 按 createdAt 升序（最早添加的先下载），
        // 而不是按降序。降序会导致新任务插队，旧任务的剩余图片被延后，
        // 当新旧任务包含相同媒体时产生重复下载。
        val next = _items.value
            .filter { it.status == DownloadStatus.QUEUED }
            .minByOrNull { it.createdAt } ?: return

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

    private fun sendPauseAllIntent() {
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE_ALL
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
        // Map non-standard Tumblr extensions to standard ones so saved files are
        // recognizable by gallery apps and file managers.
        val mapped = when (rawExt) {
            "pnj" -> "png"
            else -> rawExt
        }
        return if (mapped in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) mapped else "bin"
    }

    /** Map known English parser error messages to localized strings. */
    private fun localizeError(msg: String): String {
        return when {
            msg.startsWith("Not a valid Tumblr share link") ->
                app.getString(R.string.parse_not_valid_url)
            msg.startsWith("Private Tumblr posts are not supported yet") ->
                app.getString(R.string.parse_private_not_supported)
            msg.startsWith("oEmbed redirect error") -> {
                val code = msg.filter { it.isDigit() }.toIntOrNull() ?: 0
                app.getString(R.string.parse_oembed_redirect_error, code)
            }
            msg.startsWith("oEmbed request failed, HTTP") -> {
                val code = msg.filter { it.isDigit() }.toIntOrNull() ?: 0
                app.getString(R.string.parse_oembed_http_error, code)
            }
            msg.startsWith("oEmbed unavailable:") ->
                app.getString(R.string.parse_oembed_unavailable, msg.substringAfter("oEmbed unavailable: "))
            msg.startsWith("oEmbed parsed but no downloadable media found") ->
                app.getString(R.string.parse_oembed_parsed_empty)
            msg.startsWith("oEmbed parse failed:") ->
                app.getString(R.string.parse_error_generic, msg.substringAfter("oEmbed parse failed: "))
            msg.startsWith("Page request failed, HTTP") -> {
                val code = msg.filter { it.isDigit() }.toIntOrNull() ?: 0
                app.getString(R.string.parse_page_request_failed, code)
            }
            msg.startsWith("Page parse failed:") ->
                app.getString(R.string.parse_error_generic, msg.substringAfter("Page parse failed: "))
            msg.startsWith("No downloadable media found") ->
                app.getString(R.string.parse_no_new_media)
            else -> msg
        }
    }
}

sealed class ParseEvent {
    data class Message(val text: String) : ParseEvent()
    data class LoginRequired(val url: String, val message: String) : ParseEvent()
    data class Queued(val count: Int) : ParseEvent()
    data class CookieSecurityNotice(val text: String) : ParseEvent()
}
