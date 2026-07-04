package com.example.tumblrdownloader.utils

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

object TumblrCookieStore {
    private const val TAG = "TumblrCookieStore"
    private const val PREF_NAME = "tumblr_cookie_store"
    private const val PREF_KEY_COOKIES = "saved_tumblr_cookies"
    private const val PREF_KEY_SAVED_AT = "saved_tumblr_cookies_at"
    private const val PREF_KEY_TIP_SHOWN = "cookie_tip_shown"

    private val trackedCookieHosts = listOf("https://www.tumblr.com", "https://tumblr.com")

    fun hasSavedCookies(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_COOKIES, null).isNullOrBlank().not()
    }

    fun shouldShowSecurityNotice(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(PREF_KEY_TIP_SHOWN, false)
    }

    fun markSecurityNoticeShown(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_TIP_SHOWN, true)
            .apply()
    }

    fun saveFromWebView(context: Context): Boolean {
        val cookieManager = CookieManager.getInstance()
        val payload = JSONObject()

        for (host in trackedCookieHosts) {
            val rawCookie = cookieManager.getCookie(host) ?: continue
            val normalized = normalizeCookieString(rawCookie)
            if (normalized.isBlank()) continue
            payload.putOpt(host, normalized)
        }

        cookieManager.flush()

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return if (payload.length() == 0) {
            prefs.edit().remove(PREF_KEY_COOKIES).remove(PREF_KEY_SAVED_AT).apply()
            false
        } else {
            prefs.edit()
                .putString(PREF_KEY_COOKIES, payload.toString())
                .putLong(PREF_KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
            true
        }
    }

    fun restoreIfNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY_COOKIES, null) ?: return false
        if (raw.isBlank()) return false

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
        return restored
    }

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

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Also clear cached account info
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
}
