package com.example.tumblrdownloader.utils

import android.content.Context
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_NAME_DOWNLOAD_HISTORY = "download_history_store"
private const val KEY_HISTORY = "download_items"

object DownloadHistoryStore {

    fun load(context: Context): List<DownloadItem> {
        val prefs = context.getSharedPreferences(PREF_NAME_DOWNLOAD_HISTORY, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { index ->
                decodeItem(arr.getJSONObject(index))
            }.toList()
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, items: List<DownloadItem>) {
        val prefs = context.getSharedPreferences(PREF_NAME_DOWNLOAD_HISTORY, Context.MODE_PRIVATE)
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(encodeItem(item))
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME_DOWNLOAD_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY)
            .apply()
    }

    private fun encodeItem(item: DownloadItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("sourceUrl", item.sourceUrl)
            put("mediaUrl", item.mediaUrl)
            put("title", item.title)
            put("type", item.type.name)
            put("status", item.status.name)
            put("progress", item.progress)
            put("errorMessage", item.errorMessage)
            put("retryCount", item.retryCount)
            put("maxRetries", item.maxRetries)
            put("createdAt", item.createdAt)
        }
    }

    private fun decodeItem(obj: JSONObject): DownloadItem {
        val status = runCatching {
            DownloadStatus.valueOf(obj.optString("status", DownloadStatus.QUEUED.name))
        }.getOrDefault(DownloadStatus.QUEUED)

        val type = runCatching {
            MediaType.valueOf(obj.optString("type", MediaType.UNKNOWN.name))
        }.getOrDefault(MediaType.UNKNOWN)

        return DownloadItem(
            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            sourceUrl = obj.optString("sourceUrl"),
            mediaUrl = obj.optString("mediaUrl"),
            title = obj.optString("title", "Tumblr Media"),
            type = type,
            status = status,
            progress = obj.optInt("progress"),
            errorMessage = obj.takeIf { !it.isNull("errorMessage") }
                ?.optString("errorMessage", "")
                ?.ifBlank { null },
            retryCount = obj.optInt("retryCount"),
            maxRetries = obj.optInt("maxRetries", 3),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
