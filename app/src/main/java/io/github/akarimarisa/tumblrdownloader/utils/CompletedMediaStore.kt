package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistently tracks media files that have been **actually written to disk**.
 *
 * Keys are file-scoped ([DownloadUtils.postScopedMediaKey]: author prefix +
 * media identity, #R35) — the same media URL inside a reblog is a *different*
 * file (different author prefix), so it downloads once per post, while the
 * same post never downloads twice. The key therefore mirrors the exact
 * filename the service would write, which is also what this store guards: "is
 * that file already on disk?".
 *
 * Migration: entries written by older builds used the bare media identity and
 * no longer match — the first re-download of such a media overwrites in place
 * (#R32) and re-marks itself.
 *
 * Acts as the final service-level guard: even if an item is enqueued again
 * due to ordering/restart, this detects that the file was already downloaded
 * and skips the download flow, returning COMPLETED instead.
 */
object CompletedMediaStore {

    private const val PREF_NAME = "completed_media_store"
    private const val KEY_PREFIX = "completed_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Store key for a row/candidate, or null when the media URL is unusable. */
    fun keyFor(
        sourceUrl: String,
        mediaUrl: String,
        blogName: String?,
        inBatch: Boolean
    ): String? {
        val scoped = DownloadUtils.postScopedMediaKey(sourceUrl, mediaUrl, blogName, inBatch)
        if (scoped.isBlank()) return null
        return KEY_PREFIX + scoped
    }

    /** Whether the file behind [storeKey] has already been written to disk. */
    fun isCompleted(context: Context, storeKey: String?): Boolean {
        if (storeKey == null) return false
        return prefs(context).getBoolean(storeKey, false)
    }

    /** Marks the file behind [storeKey] as written. Called after a successful download in [DownloadService]. */
    fun markCompleted(context: Context, storeKey: String?) {
        if (storeKey == null) return
        prefs(context).edit().putBoolean(storeKey, true).apply()
    }

    /** Removes the mark (used for "re-download"). */
    fun remove(context: Context, storeKey: String?) {
        if (storeKey == null) return
        prefs(context).edit().remove(storeKey).apply()
    }

    /** Clears all completed records. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
