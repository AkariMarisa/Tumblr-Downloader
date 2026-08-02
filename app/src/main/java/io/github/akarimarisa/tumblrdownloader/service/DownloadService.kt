package io.github.akarimarisa.tumblrdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.utils.CompletedMediaStore
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
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
        private const val RETRY_DELAY_MS = 1_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val PREFS_NAME = "tumblr_downloader"
        private const val PREF_MAX_CONCURRENT = "max_concurrent_downloads"
        private const val PREF_RATE_LIMIT = "rate_limit_bytes_per_second"
        private const val DEFAULT_RATE_LIMIT = 1_048_576L
        /** Number of milliseconds over which the speed is averaged. */
        private const val SPEED_WINDOW_MS = 1_000L

        /**
         * Format bytes-per-second into a human-readable speed string.
         * e.g. 256000 -> "256 KB/s", 1572864 -> "1.5 MB/s"
         */
        fun formatSpeed(bytesPerSecond: Long): String {
            if (bytesPerSecond <= 0) return ""
            return if (bytesPerSecond < 1024 * 1024) {
                val kb = bytesPerSecond / 1024.0
                String.format(Locale.US, "%.1f KB/s", kb)
            } else {
                val mb = bytesPerSecond / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB/s", mb)
            }
        }

        const val ACTION_START = "io.github.akarimarisa.tumblrdownloader.action.START_DOWNLOAD"
        const val ACTION_PAUSE = "io.github.akarimarisa.tumblrdownloader.action.PAUSE_DOWNLOAD"
        const val ACTION_REMOVE = "io.github.akarimarisa.tumblrdownloader.action.REMOVE_DOWNLOAD"
        const val ACTION_CLEAR_ALL = "io.github.akarimarisa.tumblrdownloader.action.CLEAR_ALL"
        const val ACTION_PAUSE_ALL = "io.github.akarimarisa.tumblrdownloader.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "io.github.akarimarisa.tumblrdownloader.action.RESUME_ALL"

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

    // ── parallel download support ───────────────────────────────────
    private val concurrencySemaphore = kotlinx.coroutines.sync.Semaphore(getMaxConcurrentDownloads())
    private val activeJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())
    private val activeDownloadedBytesMap = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                // 为每个请求设置 Referer（Tumblr CDN 有时会检查）
                val request = chain.request().newBuilder()
                    .addHeader("Referer", "https://www.tumblr.com/")
                    .addHeader("Origin", "https://www.tumblr.com")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private var workerJob: Job? = null
    private var lastTaskCompletedAtMs = 0L

    // ── network connectivity check ───────────────────────────────────
    // Primary: ConnectivityManager (standard Android API, no extra traffic).
    // Fallback: periodic HEAD-request heartbeat to www.tumblr.com (only when
    // there are active downloads).  This catches edge cases where
    // ConnectivityManager reports connected but the actual path to Tumblr
    // is broken (e.g. through VPN).
    private val heartbeatClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @Volatile
    private var isNetworkAvailable = true
    @Volatile
    private var testConnectivityManagerConnected: Boolean? = null
    private var heartbeatJob: Job? = null

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                // Always check ConnectivityManager (cheap, no network traffic).
                // Only ping Tumblr when there are active downloads — no need
                // to maintain a heartbeat when the service is idle.
                if (activeJobs.isNotEmpty()) {
                    isNetworkAvailable = isConnectivityManagerConnected() ||
                        try {
                            val req = okhttp3.Request.Builder()
                                .url("https://www.tumblr.com/")
                                .head()
                                .build()
                            heartbeatClient.newCall(req).execute().use { response ->
                                response.isSuccessful
                            }
                        } catch (_: Exception) {
                            false
                        }
                }
                // When idle, don't overwrite isNetworkAvailable — let it
                // retain its last value.  The CM check in isNetworkConnected()
                // handles the airplane-mode case immediately.
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Check connectivity via [ConnectivityManager].
     * Returns `true` if the device has a network with internet capability.
     * In tests, returns the value set by [setConnectivityManagerConnectedForTest].
     */
    private fun isConnectivityManagerConnected(): Boolean {
        testConnectivityManagerConnected?.let { return it }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Immediate connectivity probe — checks CM and, if connected, pings
     * Tumblr once to update [isNetworkAvailable].  Called from the download
     * retry path so that a network failure is detected within seconds
     * instead of waiting for the next heartbeat cycle.
     */
    private fun probeNetworkNow() {
        if (!isConnectivityManagerConnected()) {
            isNetworkAvailable = false
            return
        }
        isNetworkAvailable = try {
            val req = okhttp3.Request.Builder()
                .url("https://www.tumblr.com/")
                .head()
                .build()
            heartbeatClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startHeartbeat()
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

                // Network guard: if no connectivity, pause the item immediately
                // without entering the download queue.  It will be auto-paused
                // and can be manually resumed later.
                if (!isNetworkConnected()) {
                    emitProgress(resumedItem.copy(
                        status = DownloadStatus.PAUSED,
                        progress = 0,
                        errorMessage = getNetworkWaitingString()
                    ))
                    return START_STICKY
                }

                if (!queuedItemIds.add(resumedItem.id)) {
                    return START_STICKY
                }

                try {
                    startForeground(NOTIFICATION_ID, buildNotification(resumedItem.title, getString(R.string.status_queued), 0, null))
                } catch (e: Exception) {
                    android.util.Log.w("DownloadSvc", "startForeground failed: ${e.message}")
                    // On some ROMs (MIUI, ColorOS, etc.) startForeground may be
                    // blocked.  Try to process the download anyway as a best-effort
                    // background service.
                }
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
                // Cancel all active downloads
                activeJobs.values.forEach { it.cancel(CancellationException("Cleared by user")) }
                activeJobs.clear()
                workerJob?.cancel()

                // Drain the queue channel
                while (queueChannel.tryReceive().isSuccess) { }

                // Clear all internal state
                queuedItemIds.clear()
                suspendedItemIds.clear()
                removedItemIds.clear()
                pauseStateMap.clear()
                downloadTargetMap.clear()
                activeDownloadedBytesMap.clear()

                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PAUSE_ALL -> {
                // Cancel all active download jobs
                activeJobs.values.forEach { it.cancel(CancellationException("Paused by user")) }
                activeJobs.clear()
                // Drain the queue channel so queued items don't start
                while (queueChannel.tryReceive().isSuccess) { }
                return START_STICKY
            }

            ACTION_RESUME_ALL -> {
                // All the work is done by DownloadStateManager (updates
                // statuses and sends individual ACTION_START intents).
                // The service has nothing extra to do here.
                return START_STICKY
            }

            else -> return START_STICKY
        }

        return START_STICKY
    }

    private fun pauseDownload(itemId: String?) {
        val id = itemId?.trim().orEmpty()
        if (id.isBlank()) return

        suspendedItemIds.add(id)
        queuedItemIds.remove(id)
        // Cancel the active job if this item is currently downloading
        val job = activeJobs.remove(id)
        if (job != null) {
            job.cancel(CancellationException("Paused by user"))
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
        // Cancel the active job if this item is currently downloading
        val job = activeJobs.remove(id)
        if (job != null) {
            job.cancel(CancellationException("Removed by user"))
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
                    emitProgress(item.copy(status = DownloadStatus.PAUSED, progress = 0, errorMessage = resolveString(R.string.status_paused)))
                    continue
                }

                val gap = calcGapDelay()
                if (gap > 0) {
                    emitProgress(item.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = getStringSafely(R.string.download_waiting, gap)))
                    delay(gap)

                    if (suspendedItemIds.contains(item.id) || removedItemIds.remove(item.id)) {
                        emitProgress(item.copy(status = DownloadStatus.PAUSED, progress = 0, errorMessage = resolveString(R.string.status_paused)))
                        continue
                    }

                    if (!isNetworkConnected()) {
                        emitProgress(item.copy(
                            status = DownloadStatus.PAUSED,
                            progress = 0,
                            errorMessage = getNetworkWaitingString()
                        ))
                        continue
                    }
                }

                // Acquire semaphore permit — blocks if max concurrency reached
                concurrencySemaphore.acquire()
                activeDownloadedBytesMap[item.id] = item.downloadedBytes
                val job = serviceScope.launch {
                    try {
                        withTimeout(10 * 60_000L) { // 10 minutes max per item
                            processWithRetry(item)
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        android.util.Log.w("DownloadSvc", "runQueue: download job timed out for ${item.id}")
                        emitProgress(item.copy(
                            status = DownloadStatus.FAILED,
                            progress = 0,
                            errorMessage = getString(R.string.download_timed_out)
                        ))
                    } finally {
                        activeJobs.remove(item.id)
                        activeDownloadedBytesMap.remove(item.id)
                        concurrencySemaphore.release()
                        lastTaskCompletedAtMs = SystemClock.elapsedRealtime()
                    }
                }
                activeJobs[item.id] = job
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
        android.util.Log.d("DownloadSvc", "processWithRetry: id=${item.id.take(8)}... url=${item.mediaUrl.take(60)}")
        var current = item

        // ponytail: pre-download check — 如果这个 media URL 已经被标记为已完成，
        // 直接跳过后面的下载流程。这是 `processAppend` 去重之外的第二道防线，
        // 防止同一媒体 URL 因时序/重启等原因被再次下载。
        if (CompletedMediaStore.isCompleted(this@DownloadService, current.mediaUrl)) {
            emitProgress(current.copy(status = DownloadStatus.COMPLETED, progress = 100, errorMessage = null))
            return
        }

        var isResume = current.downloadedBytes > 0L && !current.downloadFileUri.isNullOrBlank()
        var bytesThisSession = 0L
        var lastProgress = 0

        while (true) {
            // Network guard: if connectivity is gone during a retry loop,
            // pause immediately instead of wasting retries (each retry
            // would wait for the read timeout before throwing IOException).
            // This also prevents queued items behind this one from being
            // blocked longer than necessary.
            if (!isNetworkConnected()) {
                // Save partial state so user can resume later.
                val uri = downloadTargetMap[current.id]?.toString() ?: current.downloadFileUri
                val savedBytes = bytesThisSession.coerceAtLeast(current.downloadedBytes)
                if (uri != null && savedBytes > 0L) {
                    pauseStateMap[current.id] = PauseState(
                        fileUri = uri,
                        downloadedBytes = savedBytes
                    )
                }
                emitProgress(current.copy(
                    status = DownloadStatus.PAUSED,
                    progress = lastProgress,
                    downloadedBytes = savedBytes,
                    downloadFileUri = uri,
                    errorMessage = getNetworkWaitingString()
                ))
                return
            }

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
                        ?: throw IOException("Cannot open file in append mode: $uri")
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
                                ?: throw IOException("Cannot open cached file in append mode: $cachedUri")
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
                                ?: throw IOException("Cannot overwrite cached file: $cachedUri")
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
                        errorMessage = getString(R.string.download_target_creation_failed, e.message ?: getString(R.string.download_unknown_error))
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
                ) { totalDownloaded, totalFileBytes, speed ->
                    bytesThisSession = totalDownloaded
                    lastProgress = calcProgress(totalDownloaded, totalFileBytes)
                    val percent = lastProgress
                    emitProgress(
                        current.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = percent,
                            downloadedBytes = totalDownloaded,
                            downloadFileUri = output.uri.toString(),
                            errorMessage = retryHint(current),
                            speedBytesPerSecond = speed
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

                // ponytail: server doesn't support Range — fall back to a full download.
                // Delete the partial file regardless of downloadTargetMap status;
                // otherwise the old partial + new full download = two copies on disk.
                if (isResume && (e.message?.contains("Range") == true || e.message?.contains("416") == true)) {
                    runCatching { contentResolver.delete(output.uri, null, null) }
                    isResume = false
                    current = current.copy(downloadedBytes = 0L, downloadFileUri = null)
                    emitProgress(current.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0,
                        errorMessage = getString(R.string.download_server_no_resume)
                    ))
                    try {
                        delay(RETRY_DELAY_MS)
                    } catch (ce: CancellationException) {
                        // File already deleted above; just emit PAUSED
                        emitProgress(current.copy(
                            status = DownloadStatus.PAUSED,
                            errorMessage = null
                        ))
                        return
                    }
                    continue
                }

                // Immediate network check: probe CM + Tumblr now instead
                // of waiting for the next heartbeat cycle.
                probeNetworkNow()
                if (!isNetworkConnected()) {
                    val uri = downloadTargetMap[current.id]?.toString() ?: current.downloadFileUri
                    val savedBytes = bytesThisSession.coerceAtLeast(current.downloadedBytes)
                    if (uri != null && savedBytes > 0L) {
                        pauseStateMap[current.id] = PauseState(
                            fileUri = uri,
                            downloadedBytes = savedBytes
                        )
                    }
                    emitProgress(current.copy(
                        status = DownloadStatus.PAUSED,
                        progress = lastProgress,
                        downloadedBytes = savedBytes,
                        downloadFileUri = uri,
                        errorMessage = getNetworkWaitingString()
                    ))
                    return
                }

                if (current.retryCount >= current.maxRetries) {
                    emitProgress(
                        current.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = getString(R.string.download_failed_with_retries, e.message ?: getString(R.string.download_unknown_error), current.retryCount, current.maxRetries)
                        )
                    )
                    return
                }

                current = current.copy(retryCount = current.retryCount + 1)
                emitProgress(
                    current.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0,
                        errorMessage = getString(R.string.download_retrying, current.retryCount, current.maxRetries)
                    )
                )
                // CRITICAL: delay() is a suspension point.  If the job was
                // cancelled during the IOException handling above (e.g. user
                // pressed pause while a timeout was in-flight), delay() will
                // throw CancellationException.  This CancellationException
                // would bypass the dedicated catch block above (it's inside
                // catch (e: Exception), not inside the try), causing the
                // pause state to be silently lost.  Catch it here explicitly.
                try {
                    delay(RETRY_DELAY_MS)
                } catch (ce: CancellationException) {
                    // The partial file was already cleaned up above, so
                    // there's no file to resume from.  Emit PAUSED so the
                    // UI is consistent, and return.  The next resume will
                    // start from scratch — which is safe.
                    emitProgress(current.copy(
                        status = DownloadStatus.PAUSED,
                        errorMessage = null
                    ))
                    return
                }
            }
        }
    }

    private fun retryHint(item: DownloadItem): String? {
        return if (item.retryCount == 0) {
            null
        } else {
            getString(R.string.download_retry_hint, item.retryCount, item.maxRetries)
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
        onProgress: (downloaded: Long, total: Long, speedBytesPerSecond: Long) -> Unit
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

        android.util.Log.d("DownloadSvc", "downloadWithRateLimit: requesting $mediaUrl (offset=$offsetBytes)")
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        android.util.Log.d("DownloadSvc", "downloadWithRateLimit: response code=${response.code}, len=${response.body?.contentLength()}")
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

            val responseBody = safeResponse.body ?: throw IOException("Empty response body")

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
            // Speed calculation: sliding window of SPEED_WINDOW_MS
            var windowStartMs = startedAt
            var windowBytes = 0L

            responseBody.byteStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        // CANCELLATION GATE: check between reads so that pause/remove
                        // can break out even when the current chunk is in-flight.
                        // Without this, a blocked read on Dispatchers.IO would prevent
                        // CancellationException from being delivered indefinitely.
                        yield()
                        val len = input.read(buffer)
                        if (len < 0) break

                        output.write(buffer, 0, len)
                        downloadedThisRequest += len.toLong()
                        windowBytes += len.toLong()

                        // Calculate speed over the sliding window
                        val nowMs = SystemClock.elapsedRealtime()
                        val windowElapsed = nowMs - windowStartMs
                        val speed = if (windowElapsed >= SPEED_WINDOW_MS) {
                            val bps = windowBytes * 1000L / windowElapsed
                            // Reset window
                            windowStartMs = nowMs
                            windowBytes = 0L
                            bps
                        } else {
                            // Not enough time elapsed yet; use total time for initial estimate
                            val totalElapsed = nowMs - startedAt
                            if (totalElapsed > 0) downloadedThisRequest * 1000L / totalElapsed else 0L
                        }

                        onProgress(offsetBytes + downloadedThisRequest, totalBytes, speed)
                        enforceRateLimit(downloadedThisRequest, startedAt)
                    }
                }
            }
        }
    }

    private suspend fun enforceRateLimit(downloadedBytesThisSession: Long, startTimeMs: Long) {
        val maxBytesPerSecond = getMaxBytesPerSecond()
        if (maxBytesPerSecond <= 0L) return // unlimited

        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        if (elapsedMs <= 0) return

        val maxAllowedBytes = maxBytesPerSecond * elapsedMs / 1000L
        if (downloadedBytesThisSession > maxAllowedBytes) {
            val extraBytes = downloadedBytesThisSession - maxAllowedBytes
            val sleepMs = (extraBytes * 1000L) / maxBytesPerSecond
            if (sleepMs > 0) {
                delay(sleepMs)
            }
        }
    }

    private fun getMaxBytesPerSecond(): Long {
        return try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_RATE_LIMIT, DEFAULT_RATE_LIMIT)
        } catch (_: Exception) {
            DEFAULT_RATE_LIMIT
        }
    }

    private fun calcProgress(downloaded: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) {
            return -1
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

        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$folderName"

        // Let MediaStore handle naming conflicts naturally — it appends "(1)",
        // "(2)", etc. when the DISPLAY_NAME already exists in its database.
        // We no longer delete stale files/rows so re-downloads won't overwrite
        // previously saved files.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: throw IOException("Cannot create target file in download directory")
        val output = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open download output stream")

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
        // Map non-standard Tumblr extensions to standard ones so saved files are
        // recognizable by gallery apps and file managers.
        val mapped = when (rawExt) {
            "pnj" -> "png"
            else -> rawExt
        }
        return if (mapped in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "m3u8", "mov", "webm")) mapped else "bin"
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
        val speedText = if (item.status == DownloadStatus.DOWNLOADING && item.speedBytesPerSecond > 0) {
            formatSpeed(item.speedBytesPerSecond)
        } else null
        val contentText = if (speedText != null && item.progress >= 0) {
            getString(R.string.download_speed_notification, item.progress, speedText)
        } else {
            statusText(item)
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(item.title, contentText, item.progress, item.errorMessage)
        )
    }

    private fun statusText(item: DownloadItem): String {
        return when (item.status) {
            DownloadStatus.QUEUED -> item.errorMessage ?: resolveString(R.string.status_queued)
            DownloadStatus.DOWNLOADING -> item.errorMessage ?: resolveString(R.string.status_downloading)
            DownloadStatus.PAUSED -> resolveString(R.string.status_paused)
            DownloadStatus.COMPLETED -> resolveString(R.string.download_completed_notification)
            DownloadStatus.FAILED -> item.errorMessage ?: resolveString(R.string.status_failed)
        }
    }

    /**
     * Resolve a string resource with a fallback chain.
     * This prevents [Resources.NotFoundException] crashes in environments
     * where the resource table is not fully available (e.g. Robolectric).
     */
    private fun resolveString(@androidx.annotation.StringRes resId: Int): String {
        return try {
            getString(resId)
        } catch (_: Exception) {
            try {
                applicationContext.getString(resId)
            } catch (_: Exception) {
                // If all else fails, return the resource name as a placeholder.
                try {
                    resources.getResourceEntryName(resId)
                } catch (_: Exception) {
                    "…"
                }
            }
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
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
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
            io.github.akarimarisa.tumblrdownloader.model.MediaType.valueOf(safeIntent.getStringExtra(EXTRA_TYPE).orEmpty())
        }.getOrDefault(io.github.akarimarisa.tumblrdownloader.model.MediaType.UNKNOWN)
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
        stopHeartbeat()
        queueChannel.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Direct connectivity check, independent of the network-callback flag.
     *
     * This is used in [processWithRetry] to proactively detect network loss
     * during retry loops or blocking IO, where [onLost] may not have fired
     * yet or its [CancellationException] couldn't be delivered through a
     * blocking read.
     */
    /** Resolve the network-waiting string with fallback. */
    private fun getNetworkWaitingString(): String = resolveString(R.string.download_waiting_network)

    /** Resolve a format-string resource with fallback. */
    private fun getStringSafely(@androidx.annotation.StringRes resId: Int, vararg formatArgs: Any): String {
        return try {
            getString(resId, *formatArgs)
        } catch (_: Exception) {
            try {
                applicationContext.getString(resId, *formatArgs)
            } catch (_: Exception) {
                resolveString(resId)
            }
        }
    }

    private fun getMaxConcurrentDownloads(): Int {
        return try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MAX_CONCURRENT, 1).coerceIn(1, 4)
        } catch (_: Exception) { 1 }
    }

    /**
     * Check connectivity: if [ConnectivityManager] says no network at all,
     * return false immediately.  Otherwise trust the heartbeat flag which
     * detects actual reachability to Tumblr (critical for VPN edge cases
     * where CM reports connected but the real path is broken).
     */
    private fun isNetworkConnected(): Boolean {
        if (!isConnectivityManagerConnected()) return false
        return isNetworkAvailable
    }

    /** Test-only hook to simulate connectivity loss/gain via heartbeat. */
    @VisibleForTesting
    internal fun setNetworkAvailableForTest(available: Boolean) {
        isNetworkAvailable = available
    }

    /** Test-only hook to simulate ConnectivityManager state. */
    @VisibleForTesting
    internal fun setConnectivityManagerConnectedForTest(connected: Boolean) {
        testConnectivityManagerConnected = connected
    }
}
