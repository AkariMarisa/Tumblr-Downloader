package com.example.tumblrdownloader.utils

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import com.example.tumblrdownloader.db.AppDatabase
import com.example.tumblrdownloader.db.CookieEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Persist / restore Tumblr login cookies via Room (replaces old JSON-in-SP).
 *
 * Cookie data is small (1–2 host entries) but must survive app restart.
 * Room's transactional writes protect against partial-write corruption.
 */
object TumblrCookieStore {
    private const val TAG = "TumblrCookieStore"

    private val trackedCookieHosts = listOf("https://www.tumblr.com", "https://tumblr.com")

    // ── synchronous helpers for callers that aren't in a coroutine ──────
    // These touch only Room (not CookieManager) so they're fast enough
    // to call from the main thread via runBlocking-free lazy reads.

    suspend fun hasSavedCookies(context: Context): Boolean = withContext(Dispatchers.IO) {
        val row = AppDatabase.getInstance(context).cookieDao().get()
        !row?.cookiesJson.isNullOrBlank()
    }

    suspend fun shouldShowSecurityNotice(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).cookieDao().get()?.tipShown != true
    }

    suspend fun markSecurityNoticeShown(context: Context) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).cookieDao()
        val row = dao.get() ?: CookieEntity()
        dao.upsert(row.copy(tipShown = true))
    }

    // ── save / restore (involve CookieManager, run on IO) ───────────────

    /**
     * Read cookies from WebView's [CookieManager] and persist to Room.
     *
     * @return `true` if any cookies were saved.
     */
    suspend fun saveFromWebView(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cookieManager = CookieManager.getInstance()
        val payload = JSONObject()

        for (host in trackedCookieHosts) {
            val rawCookie = cookieManager.getCookie(host) ?: continue
            val normalized = normalizeCookieString(rawCookie)
            if (normalized.isBlank()) continue
            payload.putOpt(host, normalized)
        }

        cookieManager.flush()

        if (payload.length() == 0) {
            // No cookies found — clear any stale data.
            AppDatabase.getInstance(context).cookieDao().deleteAll()
            return@withContext false
        }

        val dao = AppDatabase.getInstance(context).cookieDao()
        val existing = dao.get() ?: CookieEntity()
        dao.upsert(
            existing.copy(
                cookiesJson = payload.toString(),
                savedAt = System.currentTimeMillis()
            )
        )
        return@withContext true
    }

    /**
     * Restore persisted cookies into WebView's [CookieManager].
     *
     * @return `true` if cookies were restored.
     */
    suspend fun restoreIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).cookieDao()
        val row = dao.get() ?: return@withContext false
        val raw = row.cookiesJson ?: return@withContext false
        if (raw.isBlank()) return@withContext false

        val cookieManager = CookieManager.getInstance()
        val restored = runCatching {
            val payload = JSONObject(raw)
            val hostKeys = payload.keys()
            while (hostKeys.hasNext()) {
                val host = hostKeys.next()
                val rawCookie = payload.optString(host, "")
                restoreCookieString(cookieManager, host, rawCookie)
            }
            true
        }.getOrDefault(false)

        if (restored) {
            cookieManager.flush()
        }
        return@withContext restored
    }

    // ── clear ───────────────────────────────────────────────────────────

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).cookieDao().deleteAll()

        // Also clear cached account info.
        TumblrAccountStore.clear(context)

        val cookieManager = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.removeAllCookies(null)
        } else {
            @Suppress("DEPRECATION")
            cookieManager.removeAllCookie()
        }
        cookieManager.flush()
    }

    // ── polling helper ──────────────────────────────────────────────────

    /**
     * Poll [CookieManager] until cookies for a tracked Tumblr host are visible.
     * On some Android versions, cookies set by WebView during login are not
     * immediately available via [CookieManager.getCookie] — there is an async
     * sync delay before they settle.
     *
     * @param timeoutMs  Max total time to wait.
     * @param intervalMs Time between polling attempts.
     * @return `true` if valid cookies appeared within the timeout.
     */
    suspend fun waitForCookiesReady(
        timeoutMs: Long = 5_000,
        intervalMs: Long = 300
    ): Boolean {
        val cookieManager = CookieManager.getInstance()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            for (host in trackedCookieHosts) {
                val rawCookie = cookieManager.getCookie(host)
                if (!rawCookie.isNullOrBlank()) {
                    val normalized = normalizeCookieString(rawCookie)
                    if (normalized.isNotBlank()) {
                        Log.d(TAG, "waitForCookiesReady: cookies available for $host")
                        return true
                    }
                }
            }
            delay(intervalMs)
        }

        Log.w(TAG, "waitForCookiesReady: timeout after ${timeoutMs}ms — cookies not visible")
        return false
    }

    // ── migration helper ────────────────────────────────────────────────

    /**
     * Migrate data from the old SharedPreferences store into Room.
     *
     * Safe to call multiple times — only migrates if Room has no row yet.
     */
    suspend fun migrateFromSharedPrefs(context: Context) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).cookieDao()
        if (dao.get() != null) return@withContext // already migrated

        val legacy = legacyPrefs(context)
        val cookiesJson = legacy.getString(LEGACY_KEY_COOKIES, null)
        val savedAt = legacy.getLong(LEGACY_KEY_SAVED_AT, 0L)
        val tipShown = legacy.getBoolean(LEGACY_KEY_TIP_SHOWN, false)

        if (cookiesJson.isNullOrBlank() && savedAt == 0L && !tipShown) {
            return@withContext // nothing to migrate
        }

        dao.upsert(
            CookieEntity(
                cookiesJson = cookiesJson,
                savedAt = savedAt,
                tipShown = tipShown
            )
        )

        // Wipe legacy.
        legacy.edit().clear().apply()
    }

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)

    // ── internal helpers ────────────────────────────────────────────────

    private fun restoreCookieString(cookieManager: CookieManager, host: String, rawCookie: String) {
        rawCookie.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { cookieManager.setCookie(host, it) }
    }

    private fun normalizeCookieString(rawCookie: String): String {
        return rawCookie
            .split(';')
            .map { it.trim() }
            .filter { segment ->
                segment.isNotBlank() &&
                    !segment.lowercase(Locale.ROOT).startsWith("__cf_bm=") &&
                    !segment.lowercase(Locale.ROOT).startsWith("_cfuvid=")
            }
            .joinToString(";")
    }

    private const val LEGACY_PREF_NAME = "tumblr_cookie_store"
    private const val LEGACY_KEY_COOKIES = "saved_tumblr_cookies"
    private const val LEGACY_KEY_SAVED_AT = "saved_tumblr_cookies_at"
    private const val LEGACY_KEY_TIP_SHOWN = "cookie_tip_shown"
}
