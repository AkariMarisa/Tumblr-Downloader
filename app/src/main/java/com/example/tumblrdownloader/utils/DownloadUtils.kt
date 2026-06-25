package com.example.tumblrdownloader.utils

import android.content.Context
import android.net.Uri
import com.example.tumblrdownloader.R

object DownloadUtils {

    private const val PREF_NAME_DOWNLOAD_CONFIG = "download_config"
    private const val KEY_CUSTOM_DOWNLOAD_DIR_URI = "custom_download_dir_uri"

    fun sanitizeFileName(sourceUrl: String): String {
        val fallback = "tumblr_media"
        return sourceUrl
            .trim()
            .replace("https://", "")
            .replace("http://", "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { fallback }
    }

    fun getDefaultDownloadFolderName(context: Context): String {
        val raw = context.getString(R.string.app_name)
        return sanitizeFileName(raw).ifBlank { "TumblrDownloader" }
    }

    fun getCustomDownloadDirectory(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREF_NAME_DOWNLOAD_CONFIG, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DOWNLOAD_DIR_URI, null)
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setCustomDownloadDirectory(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREF_NAME_DOWNLOAD_CONFIG, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) {
                remove(KEY_CUSTOM_DOWNLOAD_DIR_URI)
            } else {
                putString(KEY_CUSTOM_DOWNLOAD_DIR_URI, uri.toString())
            }
        }.apply()
    }

    fun clearCustomDownloadDirectory(context: Context) {
        setCustomDownloadDirectory(context, null)
    }

    fun getDownloadDirectoryLabel(context: Context): String {
        val custom = getCustomDownloadDirectory(context)
        return if (custom == null) {
            "Download/${getDefaultDownloadFolderName(context)}"
        } else {
            custom.path?.trim('/')?.substringAfterLast('/')?.trim().orEmpty().ifBlank { custom.toString() }
        }
    }
}
