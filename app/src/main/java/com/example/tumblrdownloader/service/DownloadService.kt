package com.example.tumblrdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class DownloadService : Service() {

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

        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_SOURCE_URL = "extra_source_url"
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_RETRY_COUNT = "extra_retry_count"
        const val EXTRA_MAX_RETRIES = "extra_max_retries"

        @Volatile
        var progressListener: ProgressListener? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager
    private val queueChannel = Channel<DownloadItem>(Channel.UNLIMITED)
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
        val item = parseIntent(intent) ?: return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification(item.title, "排队中", 0, null))
        queueChannel.trySend(item)
        startWorkerIfNeeded()

        return START_STICKY
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

                val gap = calcGapDelay()
                if (gap > 0) {
                    emitProgress(item.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = "等待 ${gap}ms 后开始下载"))
                    delay(gap)
                }

                processWithRetry(item)
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

    private suspend fun processWithRetry(item: DownloadItem) {
        var current = item
        while (true) {
            emitProgress(current.copy(status = DownloadStatus.DOWNLOADING, progress = 0, errorMessage = retryHint(current)))
            val outputFile = buildOutputFile(current)
            try {
                downloadWithRateLimit(current.mediaUrl, outputFile) { downloaded, totalBytes ->
                    emitProgress(
                        current.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = calcProgress(downloaded, totalBytes),
                            errorMessage = retryHint(current)
                        )
                    )
                }
                emitProgress(current.copy(status = DownloadStatus.COMPLETED, progress = 100, errorMessage = null))
                return
            } catch (e: Exception) {
                outputFile.delete()
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

    private suspend fun downloadWithRateLimit(
        mediaUrl: String,
        outputFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val request = Request.Builder()
            .url(mediaUrl)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("DNT", "1")
            .build()

        val response = client.newCall(request).execute()
        response.use { safeResponse ->
            if (!safeResponse.isSuccessful) {
                throw IOException("HTTP ${safeResponse.code} ${safeResponse.message}")
            }

            val responseBody = safeResponse.body ?: throw IOException("响应体为空")
            val totalBytes = responseBody.contentLength()
            var downloaded = 0L
            val startedAt = SystemClock.elapsedRealtime()

            responseBody.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val len = input.read(buffer)
                        if (len < 0) break

                        output.write(buffer, 0, len)
                        downloaded += len.toLong()
                        onProgress(downloaded, totalBytes)
                        enforceRateLimit(downloaded, startedAt)
                    }
                }
            }
        }
    }

    private suspend fun enforceRateLimit(downloadedBytes: Long, startTimeMs: Long) {
        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        if (elapsedMs <= 0) return

        val maxAllowedBytes = MAX_BYTES_PER_SECOND * elapsedMs / 1000L
        if (downloadedBytes > maxAllowedBytes) {
            val extraBytes = downloadedBytes - maxAllowedBytes
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

    private fun buildOutputFile(item: DownloadItem): File {
        val dir = DownloadUtils.ensureDownloadDir(this)
        val ext = inferExtension(item.mediaUrl)
        val prefix = DownloadUtils.sanitizeFileName("${item.title}-${item.id}")
        var candidate = File(dir, "$prefix.$ext")
        while (candidate.exists()) {
            val suffix = UUID.randomUUID().toString().take(4)
            candidate = File(dir, "${prefix}_${suffix}.$ext")
        }
        return candidate
    }

    private fun inferExtension(mediaUrl: String): String {
        val path = runCatching { Uri.parse(mediaUrl).lastPathSegment.orEmpty() }.getOrDefault("")
        val rawExt = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return if (rawExt in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) rawExt else "bin"
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
            maxRetries = maxRetries
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
