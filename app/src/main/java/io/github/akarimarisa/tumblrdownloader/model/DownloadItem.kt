package io.github.akarimarisa.tumblrdownloader.model

import java.util.UUID

enum class MediaType {
    IMAGE,
    VIDEO,
    UNKNOWN
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrl: String,
    val mediaUrl: String,
    val title: String,
    val type: MediaType,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    /** Bytes already written to disk for HTTP Range resume. */
    val downloadedBytes: Long = 0L,
    /** Content URI of the partial file for append-on-resume. */
    val downloadFileUri: String? = null,
    /** Current download speed in bytes per second (transient, not persisted). */
    val speedBytesPerSecond: Long = 0L,
    /** Batch-job id when this item was enqueued by a blog full-profile scan (null = single post). */
    val batchId: String? = null,
    /** Blog name of the originating batch scan (only set when [batchId] is set). */
    val blogName: String? = null
) {
    /** Preserve the byte offset and target needed for an HTTP Range retry. */
    internal fun withPartialDownload(fileUri: String, downloadedBytes: Long): DownloadItem =
        copy(
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            downloadFileUri = fileUri
        )
}
