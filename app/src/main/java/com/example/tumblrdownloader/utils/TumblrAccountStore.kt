package com.example.tumblrdownloader.utils

import android.content.Context
import okhttp3.Request
import org.json.JSONObject

data class TumblrAccount(
    val username: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null,
    val isLoggedIn: Boolean = false
)

object TumblrAccountStore {
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
     */
    fun fetchAccountInfo(context: Context): TumblrAccount {
        // Always try the API first — never return stale cached data without re-validation
        val apiAccount = fetchFromApi(context)
        if (apiAccount.isLoggedIn) return apiAccount

        // Fallback: parse dashboard HTML
        val dashboardAccount = fetchFromDashboard(context)
        if (dashboardAccount.isLoggedIn) return dashboardAccount

        return TumblrAccount()
    }

    private fun fetchFromApi(context: Context): TumblrAccount {
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

        val response = runCatching { TumblrParser.httpClient.newCall(request).execute() }.getOrNull() ?: return TumblrAccount()
        if (!response.isSuccessful) {
            response.close()
            return TumblrAccount()
        }
        val body = response.body?.string().orEmpty()
        response.close()

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return TumblrAccount()
        val meta = json.optJSONObject("meta")
        if (meta?.optInt("status", 0) != 200) return TumblrAccount()

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
            status = "Online",
            isLoggedIn = true
        )
        save(context, account)
        return account
    }

    private fun fetchFromDashboard(context: Context): TumblrAccount {
        val request = okhttp3.Request.Builder()
            .url("https://www.tumblr.com/dashboard")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = runCatching { TumblrParser.httpClient.newCall(request).execute() }.getOrNull() ?: return TumblrAccount()
        if (!response.isSuccessful) {
            response.close()
            return TumblrAccount()
        }
        val html = response.body?.string().orEmpty()
        response.close()
        val name = extractUsernameFromDashboard(html)
        if (name.isBlank()) return TumblrAccount()

        val account = TumblrAccount(
            username = name,
            avatarUrl = "https://api.tumblr.com/v2/blog/${name}/avatar/512",
            status = "Online",
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
