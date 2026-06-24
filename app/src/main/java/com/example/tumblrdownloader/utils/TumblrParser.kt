package com.example.tumblrdownloader.utils

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.example.tumblrdownloader.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "TumblrParser"

object TumblrParser {

    private val shareUrlRegex = Regex("(?i)^https?://(?:www\\.)?tumblr\\.com/[A-Za-z0-9_.-]+/\\d+")
    private val sourceParamRegex = Regex("([?&]source=[^&]+)")
    private val embedLinkRegex = Regex("data-href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val imageUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:jpg|jpeg|png|gif|webp|avif)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)
    private val videoUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:mp4|m3u8|mov)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)

    private val mediaUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:jpg|jpeg|png|gif|webp|avif|mp4|m3u8|mov)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)

    private val imageMetaRegex =
        Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val videoMetaRegex =
        Regex("<meta[^>]+property=\"og:video:url\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    // Tumblrs usually embed the whole SSR payload in this block, easier to harvest true media URLs there.
    private val initialStateScriptRegex = Regex(
        "<script[^>]+id=['\"]___+INITIAL_STATE___+['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val applicationJsonScriptRegex = Regex(
        "<script[^>]+type=['\"]application/json['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val httpClient by lazy {
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(WebViewCookieJar())

        resolveProxyAddress()?.let { builder.proxy(it) }

        builder.build()
    }

    fun isTumblrShareUrl(text: String): Boolean {
        return shareUrlRegex.containsMatchIn(normalizeText(text))
    }

    fun firstTumblrUrl(text: String): String? {
        return shareUrlRegex.find(normalizeText(text))?.value
    }

    fun guessType(mediaUrl: String): MediaType {
        val lower = mediaUrl.lowercase(Locale.ROOT)
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

        when (val oembedParsed = parseWithOEmbed(normalizedUrl)) {
            is TumblrShareParseResult.Success -> {
                if (oembedParsed.media.isNotEmpty()) return@withContext oembedParsed
            }

            is TumblrShareParseResult.LoginRequired -> return@withContext oembedParsed
            else -> Unit
        }

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

                in 200..299 -> {
                    val body = response.body.trimStart()
                    if (response.finalUrl.contains("/login_required/") ||
                        body.isBlank() ||
                        (!body.startsWith("{") && !body.startsWith("["))
                    ) {
                        TumblrShareParseResult.LoginRequired(
                            url = postUrl,
                            message = "oEmbed 返回 HTML 或空响应，通常是登录态受限。"
                        )
                    } else {
                        parseOEmbedBody(response.body, postUrl)
                    }
                }
                in 300..399 -> TumblrShareParseResult.Error("oEmbed 重定向异常（${response.code}）")
                else -> TumblrShareParseResult.Error("oEmbed 请求失败，HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithOEmbed failed", it)
            TumblrShareParseResult.Error("oEmbed 不可用：${it.message}")
        }
    }

    private suspend fun parseOEmbedBody(body: String, postUrl: String): TumblrShareParseResult {
        return runCatching {
            val json = JSONObject(body)
            val type = json.optString("type").lowercase(Locale.ROOT)
            val title = json.optString("title").ifBlank { postUrl }
            val html = json.optString("html")

            val urls = when (type) {
                "photo", "image" -> extractFromHtml(html)
                "video" -> extractFromHtml(html, preferVideo = true)
                "rich" -> {
                    val embedded = parseEmbedPost(html)
                    if (embedded.isNotEmpty()) embedded else extractFromHtml(html)
                }
                else -> extractFromHtml(html)
            }

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

    private suspend fun parseEmbedPost(html: String): List<String> {
        val embedUrl = extractEmbedUrl(html).takeIf { it.isNotBlank() } ?: return emptyList()
        val response = httpGet(embedUrl)
        if (response.code !in 200..299) return emptyList()

        return extractFromHtml(response.body)
    }

    private fun extractEmbedUrl(html: String): String {
        return embedLinkRegex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private suspend fun parseWithPageHtml(postUrl: String): TumblrShareParseResult {
        return runCatching {
            val response = httpGet(postUrl)
            if (response.code in 200..299) {
                if (response.finalUrl.contains("/login_required/") || response.finalUrl.contains("/login/")) {
                    return@runCatching TumblrShareParseResult.LoginRequired(
                        url = postUrl,
                        message = "页面返回登录重定向，通常是未公开/需登录内容。"
                    )
                }

                val html = response.body
                val source = normalizeShareUrl(postUrl)
                val candidates = extractFromHtml(html)

                if (candidates.isEmpty() && looksLikeLoginPage(html)) {
                    TumblrShareParseResult.LoginRequired(
                        url = postUrl,
                        message = "页面返回了登录页，通常是未公开/需登录内容。"
                    )
                } else if (candidates.isEmpty()) {
                    TumblrShareParseResult.Empty("页面未返回可下载媒体")
                } else {
                    val media = candidates.mapIndexed { index, mediaUrl ->
                        ParsedTumblrMedia(
                            sourceUrl = source,
                            mediaUrl = mediaUrl,
                            title = "Tumblr 媒体（${index + 1}）",
                            type = guessType(mediaUrl)
                        )
                    }
                    TumblrShareParseResult.Success(media)
                }
            } else if (response.code == 401 || response.code == 403 || response.finalUrl.contains("/login_required/") || response.finalUrl.contains("/login/")) {
                TumblrShareParseResult.LoginRequired(
                    url = postUrl,
                    message = "页面返回 401/403 或登录页，通常是私密/未公开内容。"
                )
            } else {
                TumblrShareParseResult.Error("页面请求失败，HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithPageHtml failed", it)
            TumblrShareParseResult.Error("页面解析失败：${it.message}")
        }
    }

    private fun extractFromHtml(html: String, preferVideo: Boolean = false): List<String> {
        val candidates = buildList {
            addAll(extractFromInitialState(html))
            addAll(extractFromJsonScripts(html))
            addAll(extractImageCandidates(html))
            addAll(extractVideoCandidates(html))
            if (!preferVideo) {
                addAll(extractFromMeta(imageMetaRegex, html))
                addAll(extractFromMeta(videoMetaRegex, html))
            } else {
                addAll(extractFromMeta(videoMetaRegex, html))
                addAll(extractFromMeta(imageMetaRegex, html))
            }
        }

        return dedupeAndNormalize(
            candidates
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { isLikelyPostMedia(it) }
                .distinct()
        )
    }

    private fun extractFromInitialState(html: String): List<String> {
        val match = initialStateScriptRegex.find(html) ?: return emptyList()
        val rawJson = match.groupValues.getOrNull(1).orEmpty()
        return extractUrlsFromJsonText(rawJson)
    }

    private fun extractFromJsonScripts(html: String): List<String> {
        return applicationJsonScriptRegex.findAll(html).flatMap { match ->
            extractUrlsFromJsonText(match.groupValues.getOrNull(1).orEmpty()).asSequence()
        }.toList()
    }

    private fun extractUrlsFromJsonText(jsonText: String): List<String> {
        val urls = LinkedHashSet<String>()

        extractFromRawText(jsonText).forEach(urls::add)

        runCatching {
            val root = JSONObject(jsonText)
            collectMediaUrlsFromJson(root, urls)
        }

        return urls.toList().filter(::isLikelyPostMedia)
    }

    private fun collectMediaUrlsFromJson(value: Any?, urls: MutableSet<String>) {
        when (value) {
            null, JSONObject.NULL -> return
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isNoiseJsonKey(key)) {
                        continue
                    }
                    collectMediaUrlsFromJson(value.get(key), urls)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectMediaUrlsFromJson(value.get(i), urls)
                }
            }
            is String -> {
                val trimmed = unescapeJsonText(value.trim())
                if (isLikelyPostMedia(trimmed)) {
                    mediaUrlRegex.findAll(trimmed).forEach { m ->
                        m.value.let(urls::add)
                    }
                }
            }
        }
    }

    private fun extractFromRawText(text: String): List<String> =
        mediaUrlRegex.findAll(unescapeJsonText(text)).map { it.value }.toList()

    private fun unescapeJsonText(raw: String): String =
        raw
            .replace("\\\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#x2F;", "/")

    private fun isNoiseJsonKey(key: String): Boolean {
        return when (key.lowercase(Locale.ROOT)) {
            "avatar", "avatars", "avatar_url", "userpic", "user_name", "userurl", "username",
            "canonical_url", "blogavatar", "profile", "profile_url", "favicon", "icon", "og_image", "ogvideo", "actor", "author" -> true
            else -> false
        }
    }

    private fun extractFromMeta(regex: Regex, html: String): List<String> =
        regex.findAll(html).map { it.groupValues[1] }.toList()

    private fun extractImageCandidates(html: String): List<String> =
        imageUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private fun extractVideoCandidates(html: String): List<String> =
        videoUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private fun looksLikeLoginPage(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)
        return lower.contains("log in") ||
            lower.contains("sign in") ||
            lower.contains("/login") ||
            lower.contains("password") ||
            lower.contains("please login")
    }

    private fun isLikelyPostMedia(url: String): Boolean {
        if (url.contains("media.tumblr.com").not()) return false
        val lower = url.lowercase(Locale.ROOT)

        if (lower.contains("avatar_")) return false
        if (lower.contains("/avatar/")) return false
        if (lower.contains("/avatars/")) return false
        if (lower.contains("/previews/")) return false
        if (lower.contains("frame1")) return false
        if (lower.contains("_c1")) return false

        parseResolution(url)?.let { (w, h) ->
            if ((w < 250 || h < 250) && !(lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".mov"))) {
                return false
            }
        }

        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        if (host.contains("assets.tumblr.com") || host.contains("static.tumblr.com")) return false

        if (!(host.endsWith("media.tumblr.com") || lower.contains("media.tumblr.com"))) return false

        // keep real image/video files only
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
            lower.contains(".gif") || lower.contains(".webp") || lower.contains(".avif") ||
            lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mov")
    }

    private val resolutionSegment = Regex("/s(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)

    private fun isLowResCandidate(url: String): Boolean {
        val m = resolutionSegment.find(url) ?: return false
        val w = m.groupValues[1].toIntOrNull() ?: return false
        val h = m.groupValues[2].toIntOrNull() ?: return false
        return w < 250 || h < 250
    }

    private fun parseResolution(url: String): Pair<Int, Int>? {
        val m = resolutionSegment.find(url) ?: return null
        return m.groupValues[1].toIntOrNull()?.let { w ->
            m.groupValues[2].toIntOrNull()?.let { h -> w to h }
        }
    }

    private fun dedupeAndNormalize(urls: List<String>): List<String> {
        val keepLatestByIdentity = LinkedHashMap<String, String>()
        val keepScores = HashMap<String, Int>()

        urls.forEach { url ->
            val identity = mediaIdentity(url)
            val score = mediaPreferenceScore(url)
            val existingScore = keepScores[identity]

            if (existingScore == null || score > existingScore) {
                keepLatestByIdentity[identity] = url
                keepScores[identity] = score
            }
        }

        val list = keepLatestByIdentity.values.toList()

        return list
    }

    private fun mediaPreferenceScore(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        var score = 0

        // Host preference: prefer post media hosts.
        score += when {
            lower.contains("va.media.tumblr.com") -> 8_000_000
            lower.contains("64.media.tumblr.com") -> 7_000_000
            else -> 1_000_000
        }

        if (lower.contains("_c1")) score -= 150_000
        if (lower.contains("previews/")) score -= 200_000
        if (isLowResCandidate(url)) score -= 200_000

        val resolution = parseResolution(url)
        if (resolution != null) {
            score += resolution.first * resolution.second
        }

        if (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mov")) {
            score += 2_000_000
        }

        return score + url.length
    }

    private fun mediaIdentity(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty()
            .replace(Regex("/s\\d+x\\d+(?:_c)?/"), "/")
            .replace(Regex("/s\\d+x\\d+_[0-9a-z]+/"), "/")
        return "$host$path"
    }

    private fun normalizeShareUrl(rawUrl: String): String {
        return sourceParamRegex.replace(normalizeText(rawUrl), "").trimEnd('?', '&', ' ')
    }

    private fun normalizeText(value: String): String {
        return value.trim()
    }

    private suspend fun httpGet(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Android) TumblrDownloader/1.0")
            .addHeader("Accept", "text/html,application/json,application/xhtml+xml")
            .build()

        httpClient.newCall(request).execute().use { response: Response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                finalUrl = response.request.url.toString()
            )
        }
    }

    private fun resolveProxyAddress(): java.net.Proxy? {
        val proxyValue = System.getProperty("tumblr.proxy")
            ?: System.getenv("TUMBLR_DOWNLOADER_PROXY")

        if (proxyValue.isNullOrBlank()) return null

        return runCatching {
            val uri = Uri.parse(proxyValue)
            val host = uri.host ?: return@runCatching null
            val port = uri.port
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
        }.getOrNull()
    }

    private class WebViewCookieJar : CookieJar {
        private val cookieManager = CookieManager.getInstance()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val rawCookie = cookieManager.getCookie(url.toString()) ?: return emptyList()
            return rawCookie
                .split(';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { Cookie.parse(url, it) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookieManager.setCookie(url.toString(), it.toString()) }
            cookieManager.flush()
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

private data class HttpResponse(val code: Int, val body: String, val finalUrl: String)
