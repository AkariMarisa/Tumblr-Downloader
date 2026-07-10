package io.github.akarimarisa.tumblrdownloader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.model.MediaType

/**
 * Room entity for download history — one row per download item.
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val title: String,
    val type: String,           // MediaType.name()
    val status: String,         // DownloadStatus.name()
    val progress: Int,
    val errorMessage: String?,
    val retryCount: Int,
    val maxRetries: Int,
    val createdAt: Long,
    val downloadedBytes: Long,
    val downloadFileUri: String?
) {
    fun toDownloadItem(): DownloadItem = DownloadItem(
        id = id,
        sourceUrl = sourceUrl,
        mediaUrl = mediaUrl,
        title = title,
        type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.UNKNOWN),
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        progress = progress,
        errorMessage = errorMessage?.ifBlank { null },
        retryCount = retryCount,
        maxRetries = maxRetries,
        createdAt = createdAt,
        downloadedBytes = downloadedBytes,
        downloadFileUri = downloadFileUri?.ifBlank { null }
    )

    companion object {
        fun fromItem(item: DownloadItem): DownloadHistoryEntity = DownloadHistoryEntity(
            id = item.id,
            sourceUrl = item.sourceUrl,
            mediaUrl = item.mediaUrl,
            title = item.title,
            type = item.type.name,
            status = item.status.name,
            progress = item.progress,
            errorMessage = item.errorMessage,
            retryCount = item.retryCount,
            maxRetries = item.maxRetries,
            createdAt = item.createdAt,
            downloadedBytes = item.downloadedBytes,
            downloadFileUri = item.downloadFileUri
        )
    }
}
