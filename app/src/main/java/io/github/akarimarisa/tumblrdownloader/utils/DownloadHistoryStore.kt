package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import io.github.akarimarisa.tumblrdownloader.db.AppDatabase
import io.github.akarimarisa.tumblrdownloader.db.DownloadHistoryEntity
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thread-safe persistence for download history backed by Room.
 *
 * Replaces the old JSON-in-SharedPreferences implementation, which was
 * vulnerable to data loss on concurrent writes or crash during save
 * (writing the entire list as one JSON string atomically is impossible
 * with SharedPreferences).
 *
 * All public functions are suspend — call from a background dispatcher.
 */
object DownloadHistoryStore {

    /**
     * Load all persisted download items.
     */
    suspend fun load(context: Context): List<DownloadItem> = withContext(Dispatchers.IO) {
        val entities = AppDatabase.getInstance(context)
            .downloadHistoryDao()
            .getAll()
        entities.map { it.toDownloadItem() }
    }

    /**
     * Atomically replace the entire history with [items].
     *
     * Uses a Room @Transaction so the delete + insert either both succeed
     * or both roll back — no corruption.
     */
    suspend fun save(context: Context, items: List<DownloadItem>) = withContext(Dispatchers.IO) {
        val entities = items.map { DownloadHistoryEntity.fromItem(it) }
        AppDatabase.getInstance(context)
            .downloadHistoryDao()
            .replaceAll(entities)
    }

    /**
     * Clear all persisted history.
     */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context)
            .downloadHistoryDao()
            .deleteAll()
    }

    // ── migration helpers ──────────────────────────────────────────────

    /**
     * Migrate data from the old SharedPreferences store into Room.
     *
     * Safe to call multiple times — only migrates if Room is empty.
     */
    suspend fun migrateFromSharedPrefs(context: Context) {
        val dao = AppDatabase.getInstance(context).downloadHistoryDao()
        if (dao.getAll().isNotEmpty()) return // already migrated

        val legacy = loadLegacy(context)
        if (legacy.isEmpty()) return

        dao.insertAll(legacy.map { DownloadHistoryEntity.fromItem(it) })

        // Wipe the legacy store so we don't re-migrate.
        legacyPrefs(context).edit().clear().apply()
    }

    private fun loadLegacy(context: Context): List<DownloadItem> {
        val prefs = legacyPrefs(context)
        val raw = prefs.getString(LEGACY_KEY_HISTORY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            MutableList(arr.length()) { index ->
                decodeLegacy(arr.getJSONObject(index))
            }.toList()
        }.getOrDefault(emptyList())
    }

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)

    private fun decodeLegacy(obj: org.json.JSONObject): DownloadItem {
        val status = runCatching {
            io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.valueOf(
                obj.optString("status", io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.QUEUED.name)
            )
        }.getOrDefault(io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.QUEUED)

        val type = runCatching {
            io.github.akarimarisa.tumblrdownloader.model.MediaType.valueOf(
                obj.optString("type", io.github.akarimarisa.tumblrdownloader.model.MediaType.UNKNOWN.name)
            )
        }.getOrDefault(io.github.akarimarisa.tumblrdownloader.model.MediaType.UNKNOWN)

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
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            downloadedBytes = obj.optLong("downloadedBytes", 0L),
            downloadFileUri = if (obj.isNull("downloadFileUri")) null
            else obj.optString("downloadFileUri", "").ifBlank { null }
        )
    }

    private const val LEGACY_PREF_NAME = "download_history_store"
    private const val LEGACY_KEY_HISTORY = "download_items"
}
