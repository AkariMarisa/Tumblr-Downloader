package com.example.tumblrdownloader.utils

import android.net.Uri
import android.util.Log
import com.example.tumblrdownloader.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.Locale

private const val TAG = "TumblrParser"

object TumblrParser {

    private val shareUrlRegex = Regex("(?i)^https?://(?:www\\.)?tumblr\\.com/[A-Za-z0-9_.-]+/\\d+")
    private val sourceParamRegex = Regex("([?&]source=[^&]+)")

    private val imageUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:jpg|jpeg|png|gif|webp|avif)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)
    private val videoUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:mp4|m3u8|mov)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)

    private val imageMetaRegex =
        Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val videoMetaRegex =
        Regex("<meta[^>]+property=\"og:video:url\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val httpClient by lazy {
        val proxy = resolveProxyAddress()
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)

        if (proxy != null) {
            builder.proxy(proxy)
        }

        builder.build()
    }

    fun isTumblrShareUrl(text: String): Boolean {
        return shareUrlRegex.containsMatchIn(normalizeText(text))
    }

    fun firstTumblrUrl(text: String): String? {
        return shareUrlRegex.find(normalizeText(text))?.value
    }

    fun guessType(mediaUrl: String): MediaType {
        val lower = mediaUrl.lowercase(Locale.getDefault())
        return when {
            lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mov") ||
                videoUrlRegex.containsMatchIn(lower) -> MediaType.VIDEO
            lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                lower.contains(".gif") || lower.contains(".webp") || lower.contains(".avif") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    /**
     * Parse a Tumblr share URL and try to extract concrete media links.
     */
    suspend fun parseShareUrl(rawUrl: String): TumblrShareParseResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeShareUrl(rawUrl)
        if (!isTumblrShareUrl(normalizedUrl)) {
            return@withContext TumblrShareParseResult.Error("不是有效的 Tumblr 分享链接")
        }

        // Priority: oEmbed first.
        when (val oembedParsed = parseWithOEmbed(normalizedUrl)) {
            is TumblrShareParseResult.Success -> {
                if (oembedParsed.media.isNotEmpty()) return@withContext oembedParsed
            }
            is TumblrShareParseResult.LoginRequired -> return@withContext oembedParsed
            else -> Unit
        }

        // Fallback: parse raw page html.
        return@withContext when (val htmlParsed = parseWithPageHtml(normalizedUrl)) {
            is TumblrShareParseResult.Success -> {
                if (htmlParsed.media.isNotEmpty()) htmlParsed else {
                    TumblrShareParseResult.Empty("页面未返回可下载媒体")
                }
            }

            is TumblrShareParseResult.LoginRequired -> htmlParsed
            is TumblrShareParseResult.Error -> htmlParsed
            is TumblrShareParseResult.Empty -> htmlParsed
        }
    }

    private suspend fun parseWithOEmbed(postUrl: String): TumblrShareParseResult {
        val endpoint = "https://www.tumblr.com/oembed/1.0?url=${Uri.encode(postUrl)}&omit_script=1"
        return runCatching {
            val response = httpGet(endpoint)
            when (response.code) {
                401, 403 -> TumblrShareParseResult.LoginRequired(
                    url = postUrl,
                    message = "oEmbed 返回 401/403，通常是私密/需要登陆的帖子。"
                )
                in 200..299 -> parseOEmbedBody(response.body, postUrl)
                in 300..399 -> TumblrShareParseResult.Error("oEmbed 重定向异常（${response.code}）")
                else -> TumblrShareParseResult.Error("oEmbed 请求失败，HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithOEmbed failed", it)
            TumblrShareParseResult.Error("oEmbed 不可用：${it.message}")
        }
    }

    private fun parseOEmbedBody(body: String, postUrl: String): TumblrShareParseResult {
        return runCatching {
            val json = JSONObject(body)
            val type = json.optString("type").lowercase(Locale.getDefault())
            val title = json.optString("title").ifBlank { postUrl }
            val html = json.optString("html")

            val urls = when (type) {
                "photo", "image" -> extractImageCandidates(html) + extractFromJsonField(json, "thumbnail_url")
                "video" -> extractVideoCandidates(html).ifEmpty { extractFromJsonField(json, "url") }
                else -> extractImageCandidates(html) + extractVideoCandidates(html)
            }.distinct()

            if (urls.isEmpty()) {
                TumblrShareParseResult.Error("oEmbed 解析成功但未识别到可下载地址")
            } else {
                TumblrShareParseResult.Success(
                    media = urls.mapIndexed { index, mediaUrl ->
                        ParsedTumblrMedia(
                            sourceUrl = postUrl,
                            mediaUrl = mediaUrl,
                            title = "$title（${index + 1}）",
                            type = guessType(mediaUrl)
                        )
                    }
                )
            }
        }.getOrElse {
            Log.w(TAG, "parseOEmbedBody failed", it)
            TumblrShareParseResult.Error("oEmbed 内容解析失败：${it.message}")
        }
    }

    private suspend fun parseWithPageHtml(postUrl: String): TumblrShareParseResult {
        return runCatching {
            val response = httpGet(postUrl)
            if (response.code in 200..299) {
                val html = response.body
                val source = normalizeShareUrl(postUrl)
                val candidates = buildList {
                    addAll(extractImageCandidates(html))
                    addAll(extractVideoCandidates(html))
                    addAll(extractFromMeta(imageMetaRegex, html))
                    addAll(extractFromMeta(videoMetaRegex, html))
                }.map { it.trim() }
                    .filter { it.isNotBlank() }
                    .filter { isLikelyPostMedia(it) }
                    .distinct()

                if (candidates.isEmpty()) {
                    TumblrShareParseResult.Empty("页面未返回可下载媒体")
                } else {
                    TumblrShareParseResult.Success(
                        media = candidates.mapIndexed { index, mediaUrl ->
                            ParsedTumblrMedia(
                                sourceUrl = source,
                                mediaUrl = mediaUrl,
                                title = "Tumblr 媒体（${index + 1}）",
                                type = guessType(mediaUrl)
                            )
                        }
                    )
                }
            } else if (response.code == 401 || response.code == 403) {
                TumblrShareParseResult.LoginRequired(
                    url = postUrl,
                    message = "页面返回 401/403，通常是私密/未公开内容。"
                )
            } else {
                TumblrShareParseResult.Error("页面请求失败，HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithPageHtml failed", it)
            TumblrShareParseResult.Error("页面解析失败：${it.message}")
        }
    }

    private fun extractFromJsonField(json: JSONObject, key: String): List<String> {
        return json.optString(key)
            .takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    private fun extractImageCandidates(html: String): List<String> =
        imageUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private fun extractVideoCandidates(html: String): List<String> =
        videoUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private fun extractFromMeta(regex: Regex, html: String): List<String> =
        regex.findAll(html).map { it.groupValues[1] }.toList()

    private fun isLikelyPostMedia(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        if (lower.contains("avatar_")) return false
        if (lower.contains("/avatar/")) return false
        if (lower.contains("static.tumblr.com") && lower.contains("/avatars/")) return false
        return true
    }

    private fun normalizeShareUrl(rawUrl: String): String {
        return sourceParamRegex.replace(normalizeText(rawUrl), "").trimEnd('?', '&', ' ')
    }

    private fun normalizeText(value: String): String {
        return value.trim()
    }

    private fun resolveProxyAddress(): java.net.Proxy? {
        val proxyValue = System.getProperty("tumblr.proxy")
            ?: System.getenv("TUMBLR_DOWNLOADER_PROXY")
        if (proxyValue.isNullOrBlank()) return null

        return runCatching {
            val uri = android.net.Uri.parse(proxyValue)
            val host = uri.host ?: return@runCatching null
            val port = uri.port
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
        }.getOrNull()
    }

    private suspend fun httpGet(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Android) TumblrDownloader/1.0")
            .addHeader("Accept", "text/html,application/json,application/xhtml+xml")
            .build()

        httpClient.newCall(request).execute().use { response: Response ->
            HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }
}

data class ParsedTumblrMedia(
    val sourceUrl: String,
    val mediaUrl: String,
    val title: String,
    val type: MediaType
)

sealed class TumblrShareParseResult {
    data class Success(val media: List<ParsedTumblrMedia>) : TumblrShareParseResult()
    data class LoginRequired(val url: String, val message: String) : TumblrShareParseResult()
    data class Error(val message: String) : TumblrShareParseResult()
    data class Empty(val message: String) : TumblrShareParseResult()
}

private data class HttpResponse(val code: Int, val body: String)