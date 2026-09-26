package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistently tracks media files that have been **actually written to disk**
 * (keyed by the normalized media URL).
 *
 * Acts as the final service-level guard: even if an item is enqueued again
 * due to ordering/restart, this detects that the media was already downloaded
 * and skips the download flow, returning COMPLETED instead.
 *
 * Query: [isCompleted]
 * Mark: [markCompleted]
 * Remove: [remove] (called on "re-download")
 */
object CompletedMediaStore {

    private const val PREF_NAME = "completed_media_store"
    private const val KEY_PREFIX = "completed_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the media URL has already been marked as completed.
     * Normalizes via [DownloadUtils.normalizeMediaIdentity] before comparing,
     * consistent with the dedup logic in [MainViewModel.appendItems].
     */
    fun isCompleted(context: Context, mediaUrl: String): Boolean {
        val key = normalizedKey(mediaUrl) ?: return false
        return prefs(context).getBoolean(key, false)
    }

    /**
     * Marks a media URL as completed. Called after a successful download in
     * [DownloadService].
     */
    fun markCompleted(context: Context, mediaUrl: String) {
        val key = normalizedKey(mediaUrl) ?: return
        prefs(context).edit().putBoolean(key, true).apply()
    }

    /**
     * Removes the completed mark for a media URL (used for "re-download").
     */
    fun remove(context: Context, mediaUrl: String) {
        val key = normalizedKey(mediaUrl) ?: return
        prefs(context).edit().remove(key).apply()
    }

    /** Clears all completed records. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun normalizedKey(mediaUrl: String): String? {
        val normalized = DownloadUtils.normalizeMediaIdentity(mediaUrl)
        if (normalized.isBlank()) return null
        return KEY_PREFIX + normalized
    }
}
