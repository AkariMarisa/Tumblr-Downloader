package com.example.tumblrdownloader.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 持久化跟踪已 **真正写入磁盘** 的媒体文件（按归一化后的媒体 URL 存储）。
 *
 * 用于 Service 级最终防护：即使某个 item 因时序/重启被再次入队，
 * 这里能识别出该媒体已经下载过，直接跳过下载流程、返回 COMPLETED。
 *
 * 查询方法：[isCompleted]
 * 标记方法：[markCompleted]
 * 移除方法：[remove]（"重新下载"时调用）
 */
object CompletedMediaStore {

    private const val PREF_NAME = "completed_media_store"
    private const val KEY_PREFIX = "completed_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 媒体 URL 是否已经被标记为已完成。
     * 使用 [DownloadUtils.normalizeMediaIdentity] 归一化后再判等，
     * 与 [MainViewModel.appendItems] 的去重逻辑保持一致。
     */
    fun isCompleted(context: Context, mediaUrl: String): Boolean {
        val key = normalizedKey(mediaUrl) ?: return false
        return prefs(context).getBoolean(key, false)
    }

    /**
     * 标记一个媒体 URL 为已完成。在 [DownloadService] 成功下载后调用。
     */
    fun markCompleted(context: Context, mediaUrl: String) {
        val key = normalizedKey(mediaUrl) ?: return
        prefs(context).edit().putBoolean(key, true).apply()
    }

    /**
     * 移除某个媒体的完成标记（用于"重新下载"场景）。
     */
    fun remove(context: Context, mediaUrl: String) {
        val key = normalizedKey(mediaUrl) ?: return
        prefs(context).edit().remove(key).apply()
    }

    /** 清除所有已完成记录。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun normalizedKey(mediaUrl: String): String? {
        val normalized = DownloadUtils.normalizeMediaIdentity(mediaUrl)
        if (normalized.isBlank()) return null
        return KEY_PREFIX + normalized
    }
}
