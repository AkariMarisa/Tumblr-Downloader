package com.example.tumblrdownloader.model

import java.util.UUID

enum class MediaType {
    IMAGE,
    VIDEO,
    UNKNOWN
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrl: String,
    val mediaUrl: String,
    val title: String,
    val type: MediaType,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var errorMessage: String? = null
)
