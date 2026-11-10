package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context

/**
 * The `自动下载全部媒体文件` switch (default **off**).
 *
 * It is the single source of truth for whether parsed media is offered to the
 * user or queued straight away:
 *
 *  - **off (default)** — a multi-media post opens the pre-download picker, so
 *    the user picks the files (or skips them) before anything is queued. This
 *    holds for *every* entry point: manual input, share intents, the clipboard
 *    auto-detect and the post-login retry. A whole-blog download keeps its
 *    type filter dialog (all / images / videos) instead of a per-file picker.
 *  - **on** — everything is downloaded immediately: no picker for single
 *    posts and not even the type dialog for a blog.
 *
 * Keeping the decision here (instead of a `review` flag passed by each caller)
 * is what stops the clipboard auto-detect from silently bypassing the picker.
 */
object AutoDownloadPrefs {

    const val PREFS_NAME = "tumblr_downloader"
    const val KEY_AUTO_DOWNLOAD_ALL = "auto_download_all_media"

    /** True when the user asked for everything to download without asking. */
    fun read(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_DOWNLOAD_ALL, false)
    } catch (_: Exception) {
        // A missing/corrupt prefs file must not break parsing: fail safe to
        // the picker (the default, least surprising behaviour).
        false
    }

    /** Persist the switch from the settings screen. */
    fun write(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_DOWNLOAD_ALL, enabled)
            .apply()
    }
}
