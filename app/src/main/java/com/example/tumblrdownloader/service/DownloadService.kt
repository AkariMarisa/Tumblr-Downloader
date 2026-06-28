package com.example.tumblrdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.utils.CompletedMediaStore
import com.example.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class DownloadService : Service() {

    private data class PauseState(
        val fileUri: String,
        val downloadedBytes: Long
    )

    private data class DownloadTarget(
        val uri: Uri,
        val outputStream: OutputStream,
        val onSuccess: () -> Unit,
        val onFailure: () -> Unit
    )

    interface ProgressListener {
        fun onDownloadUpdate(item: DownloadItem)
    }

    companion object {
        private const val CHANNEL_ID = "tumblr_download_channel"
        private const val NOTIFICATION_ID = 10001
        private const val IDLE_TIMEOUT_MS = 5_000L
        private const val MIN_TASK_GAP_MS = 1_000L
        private const val MAX_TASK_GAP_MS = 1_500L
        private const val MAX_BYTES_PER_SECOND = 1_024L * 1_024L
        private const val RETRY_DELAY_MS = 1_000L

        const val ACTION_START = "com.example.tumblrdownloader.action.START_DOWNLOAD"
        const val ACTION_PAUSE = "com.example.tumblrdownloader.action.PAUSE_DOWNLOAD"
        const val ACTION_REMOVE = "com.example.tumblrdownloader.action.REMOVE_DOWNLOAD"
        const val ACTION_CLEAR_ALL = "com.example.tumblrdownloader.action.CLEAR_ALL"

        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_SOURCE_URL = "extra_source_url"
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_RETRY_COUNT = "extra_retry_count"
        const val EXTRA_MAX_RETRIES = "extra_max_retries"
        const val EXTRA_DOWNLOADED_BYTES = "extra_downloaded_bytes"
        const val EXTRA_FILE_URI = "extra_file_uri"

        @Volatile
        var progressListener: ProgressListener? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager
    private val queueChannel = Channel<DownloadItem>(Channel.UNLIMITED)
    private val suspendedItemIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val removedItemIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val queuedItemIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val pauseStateMap = Collections.synchronizedMap(mutableMapOf<String, PauseState>())

    /**
     * Cached download URIs — once a file is created for an item, reuse the
     * same URI on retry (avoids MediaStore's "(N)" filename mutations).
     */
    private val downloadTargetMap = Collections.synchronizedMap(mutableMapOf<String, Uri>())

    private var activeTaskJob: Job? = null
    @Volatile
    private var activeItemId: String? = null
    private var activeDownloadedBytes = 0L
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var workerJob: Job? = null
    private var lastTaskCompletedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val item = parseIntent(intent) ?: return START_STICKY
                val saved = pauseStateMap.remove(item.id)
                val resumedItem = if (saved != null) {
                    item.copy(
                        downloadedBytes = saved.downloadedBytes,
                        downloadFileUri = saved.fileUri
                    )
                } else item

                suspendedItemIds.remove(resumedItem.id)
                removedItemIds.remove(resumedItem.id)

                if (!queuedItemIds.add(resumedItem.id)) {
                    return START_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification(resumedItem.title, "排队中", 0, null))
                queueChannel.trySend(resumedItem)
                startWorkerIfNeeded()
            }

            ACTION_PAUSE -> {
                pauseDownload(intent?.getStringExtra(EXTRA_ITEM_ID))
                return START_STICKY
            }

            ACTION_REMOVE -> {
                removeDownload(intent?.getStringExtra(EXTRA_ITEM_ID))
                return START_STICKY
            }

            ACTION_CLEAR_ALL -> {
                // Cancel active download
                activeTaskJob?.cancel(CancellationException("Cleared by user"))
                workerJob?.cancel()

                // Drain the queue channel
                while (queueChannel.tryReceive().isSuccess) { }

                // Clear all internal state
                queuedItemIds.clear()
                suspendedItemIds.clear()
                removedItemIds.clear()
                pauseStateMap.clear()
                downloadTargetMap.clear()

                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> return START_STICKY
        }

        return START_STICKY
    }

    private fun pauseDownload(itemId: String?) {
        val id = itemId?.trim().orEmpty()
        if (id.isBlank()) return

        // Don't write pauseStateMap here — the in-flight `processWithRetry` has
        // the real fileUri. Its CancellationException handler is the sole writer.
        if (activeItemId != id) {
            suspendedItemIds.add(id)
            queuedItemIds.remove(id)
            return
        }

        suspendedItemIds.add(id)
        queuedItemIds.remove(id)
        if (activeItemId == id) {
            activeTaskJob?.cancel(CancellationException("Paused by user"))
        }
    }

    private fun removeDownload(itemId: String?) {
        val id = itemId?.trim().orEmpty()
        if (id.isBlank()) return

        removedItemIds.add(id)
        suspendedItemIds.remove(id)
        queuedItemIds.remove(id)
        pauseStateMap.remove(id)
        downloadTargetMap.remove(id)
        if (activeItemId == id) {
            activeTaskJob?.cancel(CancellationException("Removed by user"))
        }
    }

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return
        workerJob = serviceScope.launch {
            runQueue()
        }
    }

    private suspend fun runQueue() {
        try {
            while (true) {
                val item = withTimeoutOrNull(IDLE_TIMEOUT_MS) {
                    queueChannel.receive()
                } ?: break

                queuedItemIds.remove(item.id)

                if (suspendedItemIds.contains(item.id) || removedItemIds.remove(item.id)) {
                    emitProgress(item.copy(status = DownloadStatus.PAUSED, progress = 0, errorMessage = "已暂停"))
                    continue
                }

                val gap = calcGapDelay()
                if (gap > 0) {
                    emitProgress(item.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = "等待 ${gap}ms 后开始下载"))
                    delay(gap)

                    if (suspendedItemIds.contains(item.id) || removedItemIds.remove(item.id)) {
                        emitProgress(item.copy(status = DownloadStatus.PAUSED, progress = 0, errorMessage = "已暂停"))
                        continue
                    }
                }

                activeItemId = item.id
                activeDownloadedBytes = item.downloadedBytes
                activeTaskJob = serviceScope.launch {
                    processWithRetry(item)
                }
                activeTaskJob?.join()

                activeItemId = null
                activeTaskJob = null
                lastTaskCompletedAtMs = SystemClock.elapsedRealtime()
            }
        } finally {
            workerJob = null
            stopSelf()
        }
    }

    private fun calcGapDelay(): Long {
        if (lastTaskCompletedAtMs == 0L) return 0L
        val targetGapMs = Random.nextLong(MIN_TASK_GAP_MS, MAX_TASK_GAP_MS + 1)
        val elapsed = SystemClock.elapsedRealtime() - lastTaskCompletedAtMs
        return if (elapsed >= targetGapMs) 0L else targetGapMs - elapsed
    }

    /** @return COMPLETED / FAILED on terminal states, null on pause / remove. */
    private suspend fun processWithRetry(item: DownloadItem) {
        var current = item

        var isResume = current.downloadedBytes > 0L && !current.downloadFileUri.isNullOrBlank()
        var bytesThisSession = 0L
        var lastProgress = 0

        while (true) {
            emitProgress(current.copy(status = DownloadStatus.DOWNLOADING, progress = 0, errorMessage = retryHint(current)))

            // ── create or reuse the output target ──────────────────────────
            val output = runCatching {
                if (isResume && current.downloadedBytes > 0L) {
                    val uri = Uri.parse(current.downloadFileUri)
                    // Use actual file size on disk, not the stored value — the
                    // stored offset may be stale (e.g. COMPLETED → PAUSED race).
                    val actualBytes = try {
                        contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                            fd.statSize
                        } ?: current.downloadedBytes
                    } catch (_: Exception) {
                        current.downloadedBytes
                    }
                    // Update the item so downloadWithRateLimit uses the correct Range header
                    current = current.copy(downloadedBytes = actualBytes)
                    // "wa" = write + append
                    val os = contentResolver.openOutputStream(uri, "wa")
                        ?: throw IOException("无法以追加模式打开文件：$uri")
                    DownloadTarget(
                        uri = uri,
                        outputStream = os,
                        onSuccess = {
                            // ponytail: file fully written.  IS_PENDING update
                            // is cosmetic (hides from gallery); ignore failures.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                runCatching {
                                    val done = ContentValues().apply {
                                        put(MediaStore.Downloads.IS_PENDING, 0)
                                    }
                                    contentResolver.update(uri, done, null, null)
                                }
                            }
                        },
                        onFailure = {
                            // Don't delete on pause; only on fatal errors or explicit remove
                            runCatching {
                                // Close quietly
                            }
                        }
                    )
                } else {
                    // ponytail: reuse cached URI if one already exists for this
                    // item (e.g. from a previous retry attempt).  MediaStore
                    // would otherwise create "(N)" copies on every retry.
                    val cachedUri = downloadTargetMap[current.id]
                    if (cachedUri != null) {
                        android.util.Log.d("DownloadSvc", "reusing cached URI for ${current.id}: $cachedUri")
                        val actualBytes = try {
                            contentResolver.openFileDescriptor(cachedUri, "r")?.use { fd ->
                                fd.statSize
                            } ?: 0L
                        } catch (_: Exception) { 0L }
                        current = current.copy(downloadedBytes = actualBytes, downloadFileUri = cachedUri.toString())
                        if (actualBytes > 0L) {
                            // File exists with data — resume
                            if (current.downloadedBytes > 0L) isResume = true
                            val os = contentResolver.openOutputStream(cachedUri, "wa")
                                ?: throw IOException("无法以追加模式打开缓存文件：$cachedUri")
                            DownloadTarget(
                                uri = cachedUri,
                                outputStream = os,
                                onSuccess = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        runCatching {
                                            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                                            contentResolver.update(cachedUri, done, null, null)
                                        }
                                    }
                                },
                                onFailure = { /* keep cache on failure */ }
                            )
                        } else {
                            // File was deleted or is empty — create fresh but
                            // use the same URI (overwrite).
                            val os = contentResolver.openOutputStream(cachedUri, "wt")
                                ?: throw IOException("无法覆盖缓存文件：$cachedUri")
                            DownloadTarget(
                                uri = cachedUri,
                                outputStream = os,
                                onSuccess = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        runCatching {
                                            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                                            contentResolver.update(cachedUri, done, null, null)
                                        }
                                    }
                                },
                                onFailure = { /* keep cache */ }
                            )
                        }
                    } else {
                        val target = createDownloadTarget(current)
                        downloadTargetMap[current.id] = target.uri
                        target
                    }
                }
            }.getOrElse { e ->
                emitProgress(
                    current.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "下载目标创建失败：${e.message ?: "未知错误"}"
                    )
                )
                return
            }

            // ── download ───────────────────────────────────────────────────
            try {
                downloadWithRateLimit(
                    mediaUrl = current.mediaUrl,
                    outputStream = output.outputStream,
                    offsetBytes = if (isResume) current.downloadedBytes else 0L
                ) { totalDownloaded, totalFileBytes ->
                    bytesThisSession = totalDownloaded
                    activeDownloadedBytes = totalDownloaded
                    lastProgress = calcProgress(totalDownloaded, totalFileBytes)
                    val percent = lastProgress
                    emitProgress(
                        current.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = percent,
                            errorMessage = retryHint(current)
                        )
                    )
                }
                output.onSuccess()
                CompletedMediaStore.markCompleted(this@DownloadService, current.mediaUrl)
                emitProgress(current.copy(status = DownloadStatus.COMPLETED, progress = 100, errorMessage = null))
                return
            } catch (e: CancellationException) {
                // ── pause ── save state so we can resume from this position
                val state = pauseStateMap.remove(current.id)
                val savedBytes = state?.downloadedBytes ?: bytesThisSession
                val savedUri = (state?.fileUri?.takeIf { it.isNotBlank() }
                    ?: output.uri.toString())

                if (e.message?.contains("Paused") == true) {
                    // Keep the file, save position for later resume
                    pauseStateMap[current.id] = PauseState(
                        fileUri = savedUri,
                        downloadedBytes = savedBytes
                    )
                    emitProgress(current.copy(
                        status = DownloadStatus.PAUSED,
                        progress = lastProgress,
                        downloadedBytes = savedBytes,
                        downloadFileUri = savedUri,
                        errorMessage = null
                    ))
                } else {
                    // Removed — delete the partial file
                    output.onFailure()
                    runCatching { contentResolver.delete(output.uri, null, null) }
                }
                return
            } catch (e: Exception) {
                // ponytail: for cached URIs (retry), don't delete the file —
                // truncate (create fresh) on the same URI instead.  Only
                // delete uncached (first-attempt) files.
                if (downloadTargetMap.containsKey(current.id)) {
                    // cached: just close, keep file on disk for reuse
                    output.onFailure()
                } else {
                    output.onFailure()
                    runCatching { contentResolver.delete(output.uri, null, null) }
                }
                // Remove from cache so next retry creates a fresh target
                downloadTargetMap.remove(current.id)

                // ponytail: server doesn't support Range — fall back to a full download
                if (isResume && (e.message?.contains("Range") == true || e.message?.contains("416") == true)) {
                    isResume = false
                    current = current.copy(downloadedBytes = 0L, downloadFileUri = null)
                    emitProgress(current.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0,
                        errorMessage = "服务器不支持断点续传，从头开始"
                    ))
                    delay(RETRY_DELAY_MS)
                    continue
                }

                if (current.retryCount >= current.maxRetries) {
                    emitProgress(
                        current.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = "下载失败：${e.message ?: "未知错误"}。已重试 ${current.retryCount}/${current.maxRetries} 次。可手动重试。"
                        )
                    )
                    return
                }

                current = current.copy(retryCount = current.retryCount + 1)
                emitProgress(
                    current.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0,
                        errorMessage = "第 ${current.retryCount}/${current.maxRetries} 次重试中"
                    )
                )
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun retryHint(item: DownloadItem): String? {
        return if (item.retryCount == 0) {
            null
        } else {
            "重试 ${item.retryCount}/${item.maxRetries}"
        }
    }

    /**
     * Download with optional HTTP Range resume.
     *
     * @param offsetBytes if > 0, send `Range: bytes={offsetBytes}-` and open the
     *                    output stream in append mode.  The progress callback
     *                    reports **absolute** bytes (offset + bytes in this request).
     */
    private suspend fun downloadWithRateLimit(
        mediaUrl: String,
        outputStream: OutputStream,
        offsetBytes: Long = 0L,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val requestBuilder = Request.Builder()
            .url(mediaUrl)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("DNT", "1")

        if (offsetBytes > 0L) {
            requestBuilder.addHeader("Range", "bytes=$offsetBytes-")
        }

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        response.use { safeResponse ->
            // ponytail: when we sent a Range header but the server returned 200
            // (full content) instead of 206 (partial), the server doesn't support
            // resumable downloads.  Also handle 416 (Range Not Satisfiable).
            if (offsetBytes > 0L && safeResponse.code != 206) {
                throw IOException(
                    if (safeResponse.code == 416)
                        "HTTP 416 — Range not satisfiable, retrying from zero"
                    else
                        "HTTP ${safeResponse.code} — server does not support Range, retrying from zero"
                )
            }
            if (!safeResponse.isSuccessful) {
                throw IOException("HTTP ${safeResponse.code} ${safeResponse.message}")
            }

            val responseBody = safeResponse.body ?: throw IOException("响应体为空")

            // ── resolve total file size ────────────────────────────────────
            var totalBytes = responseBody.contentLength()
            if (offsetBytes > 0L) {
                val contentRange = safeResponse.header("Content-Range")
                if (contentRange != null) {
                    val parsed = contentRange.substringAfter('/').toLongOrNull()
                    if (parsed != null) totalBytes = parsed
                } else if (totalBytes > 0L) {
                    totalBytes = offsetBytes + totalBytes
                }
            }
            if (totalBytes <= 0L) totalBytes = -1L

            var downloadedThisRequest = 0L
            val startedAt = SystemClock.elapsedRealtime()

            responseBody.byteStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val len = input.read(buffer)
                        if (len < 0) break

                        output.write(buffer, 0, len)
                        downloadedThisRequest += len.toLong()
                        onProgress(offsetBytes + downloadedThisRequest, totalBytes)
                        enforceRateLimit(downloadedThisRequest, startedAt)
                    }
                }
            }
        }
    }

    private suspend fun enforceRateLimit(downloadedBytesThisSession: Long, startTimeMs: Long) {
        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        if (elapsedMs <= 0) return

        val maxAllowedBytes = MAX_BYTES_PER_SECOND * elapsedMs / 1000L
        if (downloadedBytesThisSession > maxAllowedBytes) {
            val extraBytes = downloadedBytesThisSession - maxAllowedBytes
            val sleepMs = (extraBytes * 1000L) / MAX_BYTES_PER_SECOND
            if (sleepMs > 0) {
                delay(sleepMs)
            }
        }
    }

    private fun calcProgress(downloaded: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) {
            return 0
        }
        return ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    // ── file / output helpers ─────────────────────────────────────────────

    private fun createDownloadTarget(item: DownloadItem): DownloadTarget {
        val ext = inferExtension(item.mediaUrl)
        val mimeType = inferMimeType(ext)
        val fileName = "${DownloadUtils.sanitizeFileName("${authorFromSource(item.sourceUrl)}-${mediaIdFromUrl(item.mediaUrl)}")}.${ext}"

        val custom = createCustomDirTarget(fileName, mimeType)
        if (custom != null) return custom

        return createMediaStoreTarget(fileName, mimeType)
    }

    private fun authorFromSource(sourceUrl: String): String {
        val segments = runCatching { Uri.parse(sourceUrl).path?.trim('/')?.split('/') ?: emptyList<String>() }
            .getOrDefault(emptyList<String>())
        return segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: "tumblr"
    }

    private fun mediaIdFromUrl(mediaUrl: String): String {
        val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return "media"
        val lastSegment = runCatching {
            uri.lastPathSegment
                ?.substringBefore('?')
                ?.let(Uri::decode)
                ?: ""
        }.getOrDefault("")

        if (lastSegment.isNotBlank()) {
            return lastSegment.substringBeforeLast('.').ifBlank { lastSegment }
        }

        val fallback = uri.toString().substringAfterLast('/', "media").substringBefore('?')
        return fallback.substringBeforeLast('.').ifBlank { "media" }
    }

    private fun createCustomDirTarget(fileName: String, mimeType: String): DownloadTarget? {
        val rootUri = DownloadUtils.getCustomDownloadDirectory(this) ?: return null
        val rootDir = runCatching { DocumentFile.fromTreeUri(this, rootUri) }.getOrNull() ?: return null
        if (!rootDir.isDirectory || !rootDir.canWrite()) return null

        val availableName = resolveUniqueName(rootDir, fileName)
        val file = rootDir.createFile(mimeType, availableName) ?: return null
        val output = contentResolver.openOutputStream(file.uri) ?: return null
        return DownloadTarget(
            uri = file.uri,
            outputStream = output,
            onSuccess = {
                // File is already visible in a custom dir; nothing extra to do
            },
            onFailure = {
                runCatching { contentResolver.delete(file.uri, null, null) }
            }
        )
    }

    private fun createMediaStoreTarget(fileName: String, mimeType: String): DownloadTarget {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val folderName = DownloadUtils.getDefaultDownloadFolderName(this@DownloadService)

        // ponytail: delete stale file AND stale MediaStore rows before
        // inserting.  MediaStore creates "(1)" copies when the filename OR a
        // database row with the same display_name already exists.
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$folderName"
        val fsPath = java.io.File(
            Environment.getExternalStorageDirectory(), relativePath + "/$fileName"
        )
        if (fsPath.exists()) {
            android.util.Log.d("DownloadSvc", "createMediaStoreTarget: deleting stale file: $fileName")
            fsPath.delete()
        }
        // Delete any stale MediaStore rows — they cause "(1)" naming even if
        // the file was already deleted.
        runCatching {
            val delWhere = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val delArgs = arrayOf(fileName, relativePath)
            val deleted = contentResolver.delete(collection, delWhere, delArgs)
            if (deleted > 0) android.util.Log.d("DownloadSvc", "deleted $deleted stale MediaStore rows")
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: throw IOException("无法在下载目录创建目标文件")
        val output = contentResolver.openOutputStream(uri) ?: throw IOException("无法打开下载输出流")

        return DownloadTarget(
            uri = uri,
            outputStream = output,
            onSuccess = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                        contentResolver.update(uri, done, null, null)
                    }
                }
            },
            onFailure = {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        )
    }

    private fun resolveUniqueName(directory: DocumentFile, fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        val baseName = if (lastDot > 0) fileName.substring(0, lastDot) else fileName
        val extension = if (lastDot > 0) fileName.substring(lastDot) else ""

        if (directory.findFile(fileName) == null) {
            return fileName
        }

        var index = 1
        while (index <= 99) {
            val candidate = "${baseName}_${index}$extension"
            if (directory.findFile(candidate) == null) {
                return candidate
            }
            index++
        }

        val uuidSuffix = UUID.randomUUID().toString().take(4)
        return "${baseName}_${uuidSuffix}$extension"
    }

    private fun inferExtension(mediaUrl: String): String {
        val path = runCatching { Uri.parse(mediaUrl).lastPathSegment.orEmpty() }.getOrDefault("")
        val rawExt = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return if (rawExt in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) rawExt else "bin"
    }

    private fun inferMimeType(ext: String): String {
        return when (ext.lowercase(Locale.getDefault())) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "mp4" -> "video/mp4"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun emitProgress(item: DownloadItem) {
        progressListener?.onDownloadUpdate(item)
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(item.title, statusText(item), item.progress, item.errorMessage)
        )
    }

    private fun statusText(item: DownloadItem): String {
        return when (item.status) {
            DownloadStatus.QUEUED -> item.errorMessage ?: "排队中"
            DownloadStatus.DOWNLOADING -> item.errorMessage ?: "下载中"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.COMPLETED -> "下载完成"
            DownloadStatus.FAILED -> item.errorMessage ?: "下载失败"
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        errorMessage: String?
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (errorMessage == null) content else "$content - $errorMessage")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    private fun parseIntent(intent: Intent?): DownloadItem? {
        val safeIntent = intent ?: return null
        val id = safeIntent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        val sourceUrl = safeIntent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        val mediaUrl = safeIntent.getStringExtra(EXTRA_MEDIA_URL).orEmpty()
        val title = safeIntent.getStringExtra(EXTRA_TITLE)
        val type = runCatching {
            com.example.tumblrdownloader.model.MediaType.valueOf(safeIntent.getStringExtra(EXTRA_TYPE).orEmpty())
        }.getOrDefault(com.example.tumblrdownloader.model.MediaType.UNKNOWN)
        if (id.isBlank() || mediaUrl.isBlank()) return null

        val retryCount = safeIntent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        val maxRetries = safeIntent.getIntExtra(EXTRA_MAX_RETRIES, 3)

        val downloadedBytes = safeIntent.getLongExtra(EXTRA_DOWNLOADED_BYTES, 0L)
        val downloadFileUri = safeIntent.getStringExtra(EXTRA_FILE_URI)

        return DownloadItem(
            id = id,
            sourceUrl = sourceUrl,
            mediaUrl = mediaUrl,
            title = title.orEmpty().ifBlank { "Tumblr Media" },
            type = type,
            status = DownloadStatus.QUEUED,
            progress = 0,
            errorMessage = null,
            retryCount = retryCount,
            maxRetries = maxRetries,
            downloadedBytes = downloadedBytes,
            downloadFileUri = downloadFileUri?.takeIf { it.isNotBlank() }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tumblr Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download status updates"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        queueChannel.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
