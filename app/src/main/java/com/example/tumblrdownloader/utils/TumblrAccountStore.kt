package com.example.tumblrdownloader.utils

import android.content.Context
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

    fun fetchAccountInfo(context: Context): TumblrAccount {
        val cached = load(context)
        if (cached.isLoggedIn) return cached

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
            status = "在线",
            isLoggedIn = true
        )
        save(context, account)
        return account
    }

    // Same regex patterns as TumblrParser
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

        // Try top-level "name"
        root.optString("name", "").takeIf { it.isNotBlank() }?.let { return it }

        // Try "blogs" array first entry
        root.optJSONArray("blogs")?.optJSONObject(0)?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }

        // Try "account" object
        root.optJSONObject("account")?.optString("username", "")?.takeIf { it.isNotBlank() }?.let { return it }

        // Try "blog" object
        root.optJSONObject("blog")?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }

        // Try "user" object
        root.optJSONObject("user")?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }

        return ""
    }

}
