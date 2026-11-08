package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import io.github.akarimarisa.tumblrdownloader.R
import java.util.Collections
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
            // Keep Unicode letters (Chinese/Japanese/Korean, etc.), digits, . _ -; replace the rest with _
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .replace(Regex("_+"), "_")   // collapse consecutive underscores
            .ifBlank { fallback }
    }

    /**
     * Default download folder name: fixed to English so that Chinese
     * characters/spaces are not garbled after sanitization. The fixed name
     * also keeps the folder stable across app language changes.
     */
    fun getDefaultDownloadFolderName(context: Context): String {
        return "TumblrDownloader"
    }

    fun getCustomDownloadDirectory(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREF_NAME_DOWNLOAD_CONFIG, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DOWNLOAD_DIR_URI, null)
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun getCurrentDownloadDirectory(context: Context): Uri {
        return getCustomDownloadDirectory(context)
            ?: buildDefaultDownloadTreeUri(context)
    }

    /** Build a tree URI for the default subdir with single-segment encoding.
     *  DocumentsContract.buildTreeDocumentUri() uses appendPath() which would
     *  split on '/' in the document ID, creating multiple path segments and
     *  causing getTreeDocumentId() to return only the first part. */
    private fun buildDefaultDownloadTreeUri(context: Context): Uri {
        val docId = "primary:${Environment.DIRECTORY_DOWNLOADS}/${getDefaultDownloadFolderName(context)}"
        return Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")
    }

    fun setCustomDownloadDirectory(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREF_NAME_DOWNLOAD_CONFIG, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) {
                remove(KEY_CUSTOM_DOWNLOAD_DIR_URI)
            } else {
                putString(KEY_CUSTOM_DOWNLOAD_DIR_URI, uri.toString())
            }
        }.commit() // commit() over apply() so subsequent reads in the same frame see the change
    }

    fun clearCustomDownloadDirectory(context: Context) {
        setCustomDownloadDirectory(context, null)
    }

    fun getDownloadDirectoryLabel(context: Context): String {
        val custom = getCustomDownloadDirectory(context)
        val defaultPath = "${Environment.DIRECTORY_DOWNLOADS}/${getDefaultDownloadFolderName(context)}"
        return if (custom == null) {
            defaultPath
        } else {
            // Extract readable path from the SAF tree URI document ID
            // content://.../tree/primary:Download/Tumblr_Downloader → Download/Tumblr_Downloader
            try {
                val docId = DocumentsContract.getTreeDocumentId(custom)
                val path = docId.removePrefix("primary:")
                if (path.isNotBlank()) path else defaultPath
            } catch (_: Exception) {
                defaultPath
            }
        }
    }

    /**
     * URL cleanup patterns for [normalizeMediaIdentity].
     *
     * They are compiled **once** here on purpose: this function is called for
     * every queued item on every appended feed page (see the dedup key set in
     * `DownloadStateManager.processAppend`), so constructing five `Regex`s per
     * call used to cost ~130 µs/item on device — an 8 000-item queue burned
     * ~1 s of main thread per page and a large blog scan ended in an ANR.
     */
    private val SIZE_SEGMENT = Regex("/s\\d+x\\d+(?:_[^/]+)?/")
    private val CROP_BOX = Regex("_c\\d+,\\d+,\\d+,\\d+(?=\\.[a-z0-9]+$)")
    private val DIMENSIONS = Regex("_(\\d{2,4}x\\d{2,4})(?=\\.[a-z0-9]+$)")
    private val SQUARE_SUFFIX = Regex("_(\\d{2,4})sq(?=\\.[a-z0-9]+$)")
    private val PIXEL_SUFFIX = Regex("_(\\d{3,4})(?=\\.[a-z0-9]+$)")

    /**
     * Normalizes a media URL for parsing/dedup purposes:
     * - strips common resolution fragments
     * - keeps host+path, ignoring differences in query parameter order
     *
     * Results are memoized in [identityCache]: `processAppend` re-normalizes
     * *every* queued item on each appended feed page, so without the cache a
     * large queue cost O(queue) parses per page and a big blog scan starved the
     * main thread into an ANR. The function is a pure function of its argument,
     * so caching cannot change any result.
     */
    fun normalizeMediaIdentity(mediaUrl: String): String {
        identityCache[mediaUrl]?.let { return it }
        val identity = computeIdentity(mediaUrl)
        identityCache[mediaUrl] = identity
        return identity
    }

    /** Upper bound of [identityCache]; evicted entries are recomputed. */
    private const val IDENTITY_CACHE_LIMIT = 30_000

    /**
     * Access-ordered LRU of raw URL → normalized identity.
     *
     * Bounded so a long session with a huge queue cannot grow it without limit;
     * evicted entries are simply recomputed. Synchronized because callers span
     * the main thread (queue commands), the download service and tests.
     */
    private val identityCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > IDENTITY_CACHE_LIMIT
        }
    )

    /** Drop the memo. Tests only — production never needs to invalidate it. */
    @androidx.annotation.VisibleForTesting
    internal fun clearIdentityCache() {
        identityCache.clear()
    }

    private fun computeIdentity(mediaUrl: String): String {
        val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return mediaUrl.lowercase(Locale.getDefault())

        val host = uri.host?.lowercase(Locale.getDefault()) ?: ""
        val hostKey = if (host.endsWith(".media.tumblr.com")) {
            "media.tumblr.com"
        } else {
            host
        }

        var path = (uri.path.orEmpty().lowercase(Locale.getDefault()))
            .replace(SIZE_SEGMENT, "/")
            .replace(CROP_BOX, "")
            .replace(DIMENSIONS, "")
            // Square crop variants: "tumblr_x_250sq.jpg" (250x250 square
            // thumbnail) are the same media as "tumblr_x_250.jpg" — strip the
            // "sq" suffix so they don't become separate download items (issue
            // #24: one post, one image, but three files downloaded).
            .replace(SQUARE_SUFFIX, "")
            .replace(PIXEL_SUFFIX, "")

        if (hostKey.endsWith("media.tumblr.com")) {
            val segments = path.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                return "$hostKey/${segments[0]}/${segments[1]}"
            }
        }

        return "$hostKey$path"
    }
}
