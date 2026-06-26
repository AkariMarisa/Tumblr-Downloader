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
        val httpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val cookieManager = android.webkit.CookieManager.getInstance()

        val request = okhttp3.Request.Builder()
            .url("https://www.tumblr.com/svc/account/info")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Android) TumblrDownloader/1.0")
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .addHeader("Referer", "https://www.tumblr.com/dashboard")
            .addHeader("Cookie", cookieManager.getCookie("https://www.tumblr.com").orEmpty())
            .build()

        val response = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: return TumblrAccount()
        if (!response.isSuccessful) return TumblrAccount()

        val body = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return TumblrAccount()

        val responseObj = json.optJSONObject("response") ?: return TumblrAccount()
        val user = responseObj.optJSONObject("user") ?: return TumblrAccount()

        val name = user.optString("name", "")
        val avatarUrl = "https://api.tumblr.com/v2/blog/${name}/avatar/512"

        return TumblrAccount(
            username = name.ifBlank { null },
            avatarUrl = avatarUrl,
            status = user.optString("status", "").ifBlank { null },
            isLoggedIn = name.isNotBlank()
        ).also { save(context, it) }
    }
}
