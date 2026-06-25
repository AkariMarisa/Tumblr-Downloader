package com.example.tumblrdownloader.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.tumblrdownloader.R
import java.util.Locale

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

    fun getCurrentDownloadDirectory(context: Context): Uri {
        return getCustomDownloadDirectory(context)
            ?: DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOWNLOADS}/${getDefaultDownloadFolderName(context)}"
            )
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

    /**
     * 对媒体 URL 做解析去重归一化：
     * - 移除常见分辨率片段
     * - 保留 host+path，忽略查询参数顺序差异
     */
    fun normalizeMediaIdentity(mediaUrl: String): String {
        val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return mediaUrl.lowercase(Locale.getDefault())

        val host = uri.host?.lowercase(Locale.getDefault()) ?: ""
        val hostKey = if (host.endsWith(".media.tumblr.com")) {
            "media.tumblr.com"
        } else {
            host
        }

        var path = (uri.path.orEmpty().lowercase(Locale.getDefault()))
            .replace(Regex("/s\\d+x\\d+(?:_[^/]+)?/"), "/")
            .replace(Regex("_c\\d+,\\d+,\\d+,\\d+(?=\\.[a-z0-9]+$)"), "")
            .replace(Regex("_(\\d{2,4}x\\d{2,4})(?=\\.[a-z0-9]+$)"), "")
            .replace(Regex("_(\\d{3,4})(?=\\.[a-z0-9]+$)"), "")

        if (hostKey.endsWith("media.tumblr.com")) {
            val segments = path.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                return "$hostKey/${segments[0]}/${segments[1]}"
            }
        }

        return "$hostKey$path"
    }
}
