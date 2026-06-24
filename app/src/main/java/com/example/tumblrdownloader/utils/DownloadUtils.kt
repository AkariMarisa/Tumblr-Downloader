package com.example.tumblrdownloader.utils

import android.content.Context
import java.io.File

object DownloadUtils {

    fun ensureDownloadDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "TumblrDownloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sanitizeFileName(sourceUrl: String): String {
        val fallback = "tumblr_media"
        return sourceUrl
            .trim()
            .replace("https://", "")
            .replace("http://", "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { fallback }
    }
}
