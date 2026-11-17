package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import android.util.Log
import io.github.akarimarisa.tumblrdownloader.R
import okhttp3.Request
import org.json.JSONObject

data class TumblrAccount(
    val username: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null,
    val isLoggedIn: Boolean = false
)

object TumblrAccountStore {
    private const val TAG = "TumblrAccount"
    private const val PREF_NAME = "tumblr_account"
    private const val KEY_USERNAME = "username"
    private const val KEY_AVATAR_URL = "avatar_url"
    private const val KEY_STATUS = "status"

    // ponytail: hardcoded public client token, extracted from Tumblr web app JS bundle.
    // Replace if it stops working — find a fresh one via web devtools on any dashboard page.
    private const val BEARER_TOKEN = "aIcXSOoTtqrzR8L8YEIOmBeW94c3FmbSNSWAUbxsny9KKx5VFh"

    fun load(context: Context): TumblrAccount {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("account_json", null) ?: return TumblrAccount()
        return runCatching {
            val json = JSONObject(raw)
            TumblrAccount(
                username = json.optString(KEY_USERNAME, "").ifBlank { null },
                avatarUrl = json.optString(KEY_AVATAR_URL, "").ifBlank { null },
                status = json.optString(KEY_STATUS, "").ifBlank { null },
                isLoggedIn = true
            )
        }.getOrDefault(TumblrAccount())
    }

    fun save(context: Context, account: TumblrAccount) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!account.isLoggedIn) {
            prefs.edit().remove("account_json").apply()
            return
        }
        val json = JSONObject().apply {
            put(KEY_USERNAME, account.username.orEmpty())
            put(KEY_AVATAR_URL, account.avatarUrl.orEmpty())
            put(KEY_STATUS, account.status.orEmpty())
        }
        prefs.edit().putString("account_json", json.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove("account_json").apply()
    }

    /**
     * Fetch account info via the same API the Tumblr web dashboard uses.
     * Falls back to dashboard HTML parsing if the API call fails.
     *
     * Returns `null` only when **every** attempt failed at the transport
     * level (offline / timeout / 5xx) — i.e. we learned nothing about the
     * session. A definitive "not logged in" answer is a non-logged-in
     * [TumblrAccount]. Callers keep their cached state on `null` instead of
     * flipping the UI to logged-out for a flaky network (#R34).
     */
    fun fetchAccountInfo(context: Context): TumblrAccount? {
        // Always try the API first — never return stale cached data without re-validation
        val apiAccount = fetchFromApi(context)
        if (apiAccount != null) {
            if (apiAccount.isLoggedIn) {
                Log.i(TAG, "fetch: api ok (${apiAccount.username})")
                return apiAccount
            }
            Log.i(TAG, "fetch: api answered logged-out, trying dashboard")
        } else {
            Log.w(TAG, "fetch: api transport failure, trying dashboard")
        }

        // Fallback: parse dashboard HTML
        val dashboardAccount = fetchFromDashboard(context)
        if (dashboardAccount != null) {
            Log.i(TAG, "fetch: dashboard " + if (dashboardAccount.isLoggedIn) "ok (${dashboardAccount.username})" else "answered logged-out")
            return dashboardAccount
        }

        Log.w(TAG, "fetch: both attempts failed at transport level, keeping cache")
        // API gave a definitive answer even though the dashboard fallback failed.
        return apiAccount
    }

    /** `null` = transport-level failure (told us nothing); [TumblrAccount] = a definitive answer. */
    private fun fetchFromApi(context: Context): TumblrAccount? {
        val request = Request.Builder()
            .url("https://www.tumblr.com/api/v2/user/info?fields%5Bblogs%5D=%3Favatar%2Cname%2C%3Ftitle%2Curl%2C%3Fblog_view_url%2C%3Fcan_message%2C%3Fdescription%2C%3Fis_adult%2C%3Fuuid%2C%3Fis_private_channel%2C%3Fposts%2C%3Fis_group_channel%2C%3Fprimary%2C%3Fadmin%2C%3Fdrafts%2C%3Ffollowers%2C%3Fqueue%2C%3Fhas_flagged_posts%2C%3Fmessages%2C%3Fask%2C%3Fcan_submit%2C%3Fmention_key%2C%3Ftimezone_offset%2C%3Fanalytics_url%2C%3Fis_premium_partner%2C%3Fis_blogless_advertiser%2C%3Fis_tumblrpay_onboarded%2C%3Ftheme%2C%3Ftumblrmart_orders")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .addHeader("Accept", "application/json;format=camelcase")
            .addHeader("Accept-Language", "zh-cn")
            .addHeader("Referer", "https://www.tumblr.com/")
            .addHeader("Authorization", "Bearer $BEARER_TOKEN")
            .addHeader("X-Version", "redpop/3/0//redpop/")
            .build()

        val response = runCatching { TumblrParser.httpClient.newCall(request).execute() }.getOrElse {
            Log.w(TAG, "api request failed: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            Log.w(TAG, "api HTTP $code")
            // Rate limits and server errors say nothing about the session.
            if (code == 429 || code >= 500) return null
            return TumblrAccount()
        }
        val body = response.body?.string().orEmpty()
        response.close()

        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null) {
            Log.w(TAG, "api: unparseable body (${body.take(80)})")
            return null
        }
        val meta = json.optJSONObject("meta")
        if (meta?.optInt("status", 0) != 200) {
            val s = meta?.optInt("status", 0) ?: 0
            Log.w(TAG, "api meta status=$s")
            if (s == 429 || s >= 500) return null
            return TumblrAccount()
        }

        val resp = json.optJSONObject("response") ?: return TumblrAccount()
        val user = resp.optJSONObject("user") ?: return TumblrAccount()
        val name = user.optString("name", "").ifBlank { return TumblrAccount() }

        // Get avatar from primary blog
        var avatarUrl: String? = null
        val blogs = user.optJSONArray("blogs")
        if (blogs != null && blogs.length() > 0) {
            val primaryBlog = blogs.optJSONObject(0)
            val avatars = primaryBlog?.optJSONArray("avatar")
            if (avatars != null && avatars.length() > 0) {
                avatarUrl = avatars.optJSONObject(0)?.optString("url", "")?.ifBlank { null }
            }
        }

        val account = TumblrAccount(
            username = name,
            avatarUrl = avatarUrl ?: "https://api.tumblr.com/v2/blog/${name}/avatar/512",
            status = context.getString(R.string.status_online),
            isLoggedIn = true
        )
        save(context, account)
        return account
    }

    /** `null` = transport-level failure; [TumblrAccount] = a definitive answer. */
    private fun fetchFromDashboard(context: Context): TumblrAccount? {
        val request = okhttp3.Request.Builder()
            .url("https://www.tumblr.com/dashboard")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = runCatching { TumblrParser.httpClient.newCall(request).execute() }.getOrElse {
            Log.w(TAG, "dashboard request failed: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            Log.w(TAG, "dashboard HTTP $code")
            if (code == 429 || code >= 500) return null
            return TumblrAccount()
        }
        val html = response.body?.string().orEmpty()
        response.close()
        val name = extractUsernameFromDashboard(html)
        if (name.isBlank()) {
            // A200 dashboard response without a username = login page/challenge.
            Log.w(TAG, "dashboard: no username in HTML (len=${html.length}) — session rejected")
            return TumblrAccount()
        }

        val account = TumblrAccount(
            username = name,
            avatarUrl = "https://api.tumblr.com/v2/blog/${name}/avatar/512",
            status = context.getString(R.string.status_online),
            isLoggedIn = true
        )
        save(context, account)
        return account
    }

    // --- Dashboard HTML parsers (same regexes as TumblrParser) ---

    private val stateScriptRegex = Regex(
        "<script[^>]+id=['\"]__+INITIAL_STATE__+['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val jsonScriptRegex = Regex(
        "<script[^>]+type=['\"]application/json['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private fun extractUsernameFromDashboard(html: String): String {
        val rawJson = stateScriptRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            .ifEmpty { jsonScriptRegex.find(html)?.groupValues?.getOrNull(1).orEmpty() }
        if (rawJson.isBlank()) return ""

        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return ""

        root.optString("name", "").takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONArray("blogs")?.optJSONObject(0)?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("account")?.optString("username", "")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("blog")?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("user")?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }

        return ""
    }
}
