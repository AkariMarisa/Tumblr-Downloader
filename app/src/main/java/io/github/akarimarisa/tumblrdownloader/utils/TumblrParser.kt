package io.github.akarimarisa.tumblrdownloader.utils

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import io.github.akarimarisa.tumblrdownloader.model.MediaType
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

    private val shareUrlRegex = Regex(
        "(?i)" +
        "https?://(?:www\\.)?tumblr\\.com/[A-Za-z0-9_.-]+/\\d+(?:/[A-Za-z0-9_-]+)?|" +
        "https?://[A-Za-z0-9_.-]+\\.tumblr\\.com/(?:post/)?(?:private/)?\\d+(?:/[A-Za-z0-9_-]+)?"
    )
    private val sourceParamRegex = Regex("([?&]source=[^&]+)")
    private val embedLinkRegex = Regex("data-href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val imageUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:jpg|jpeg|png|pnj|gif|webp|avif)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)
    private val videoUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:mp4|m3u8|mov)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)

    private val mediaUrlRegex =
        Regex("https?://[^\"'\\s>]+\\.(?:jpg|jpeg|png|pnj|gif|webp|avif|mp4|m3u8|mov)(?:\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)

    private val imageMetaRegex =
        Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val videoMetaRegex =
        Regex("<meta[^>]+property=\"og:video:url\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    // Tumblrs usually embed the whole SSR payload in this block, easier to harvest true media URLs there.
    private val initialStateScriptRegex = Regex(
        "<script[^>]+id=['\"]__+INITIAL_STATE__+['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val applicationJsonScriptRegex = Regex(
        "<script[^>]+type=['\"]application/json['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val NOISE_PATH_TOKENS = setOf(
        "avatar",
        "avatars",
        "userpic",
        "user_name",
        "userurl",
        "username",
        "profile",
        "cover",
        "cover_photo",
        "cover_image",
        "background",
        "background_image",
        "header",
        "theme",
        "theme_data",
        "colors",
        "blog",
        "tumblelog",
        "icon",
        "favicon",
        "banner",
        "user",
        "author",
        "canonical_url",
        "poster",
        "thumbnail",
        "thumbnails",
        "previews",
        "frame1"
    )

    internal val httpClient by lazy {
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
                lower.contains(".pnj") || lower.contains(".gif") || lower.contains(".webp") || lower.contains(".avif") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    /**
     * Parse a Tumblr share URL and try to extract concrete media links.
     */
    suspend fun parseShareUrl(rawUrl: String): TumblrShareParseResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeShareUrl(rawUrl)
        if (!isTumblrShareUrl(normalizedUrl)) {
            return@withContext TumblrShareParseResult.Error("Not a valid Tumblr share link")
        }

        // Private posts (/private/ URLs) require JavaScript rendering — the
        // current HTML parser cannot extract media from them.
        if (normalizedUrl.contains("/private/")) {
            return@withContext TumblrShareParseResult.Error(
                "Private Tumblr posts are not supported yet"
            )
        }

        when (val oembedParsed = parseWithOEmbed(normalizedUrl)) {
            is TumblrShareParseResult.Success -> {
                if (oembedParsed.media.isNotEmpty()) return@withContext oembedParsed
            }

            is TumblrShareParseResult.LoginRequired -> {
                // Don't return immediately — private posts don't have oEmbed
                // representations even when authenticated. If cookies are
                // present, fall through to page HTML which is more reliable.
                if (!hasTumblrCookies()) {
                    return@withContext oembedParsed
                }
            }
            else -> Unit
        }

        return@withContext when (val htmlParsed = parseWithPageHtml(normalizedUrl)) {
            is TumblrShareParseResult.Success -> {
                if (htmlParsed.media.isNotEmpty()) htmlParsed else {
                    TumblrShareParseResult.Empty("No downloadable media found")
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
                    message = "oEmbed returned 401/403 — post may be private or require login."
                )

                in 200..299 -> {
                    val body = response.body.trimStart()
                    if (response.finalUrl.contains("/login_required/") ||
                        body.isBlank() ||
                        (!body.startsWith("{") && !body.startsWith("["))
                    ) {
                        TumblrShareParseResult.LoginRequired(
                            url = postUrl,
                            message = "oEmbed returned HTML or empty response — login may be required."
                        )
                    } else {
                        parseOEmbedBody(response.body, postUrl)
                    }
                }
                in 300..399 -> TumblrShareParseResult.Error("oEmbed redirect error (${response.code})")
                else -> TumblrShareParseResult.Error("oEmbed request failed, HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithOEmbed failed", it)
            TumblrShareParseResult.Error("oEmbed unavailable: ${it.message}")
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
                TumblrShareParseResult.Error("oEmbed parsed but no downloadable media found")
            } else {
                TumblrShareParseResult.Success(
                    media = urls.mapIndexed { index, mediaUrl ->
                        ParsedTumblrMedia(
                            sourceUrl = postUrl,
                            mediaUrl = mediaUrl,
                            title = "$title (${index + 1})",
                            type = guessType(mediaUrl)
                        )
                    }
                )
            }
        }.getOrElse {
            Log.w(TAG, "parseOEmbedBody failed", it)
            TumblrShareParseResult.Error("oEmbed parse failed: ${it.message}")
        }
    }

    private suspend fun parseEmbedPost(html: String): List<String> {
        val embedUrl = extractEmbedUrl(html).takeIf { it.isNotBlank() } ?: return emptyList()
        val response = httpGet(embedUrl)
        if (response.code !in 200..299) return emptyList()

        return extractFromHtml(response.body, structuredOnly = true).ifEmpty {
            extractFromHtml(response.body)
        }
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
                        message = "Redirected to login — this content may be private or require authentication."
                    )
                }

                val html = response.body
                val source = normalizeShareUrl(postUrl)
                val candidates = run {
                    val structured = extractFromHtml(html, structuredOnly = true)
                    if (structured.isNotEmpty()) {
                        structured
                    } else {
                        extractFromHtml(html)
                    }
                }

                if (candidates.isEmpty() && looksLikeLoginPage(html)) {
                    TumblrShareParseResult.LoginRequired(
                        url = postUrl,
                        message = "Login page detected — this content may be private or require authentication."
                    )
                } else if (candidates.isEmpty() && looksLikeAdultContentPage(html)) {
                    // The adult content interstitial page may still contain the
                    // post's media URLs in __INITIAL_STATE__, just under JSON
                    // paths that [isLikelyPostMediaPath] rejects.  Try a relaxed
                    // extraction (no path filtering) before requiring a browser
                    // confirmation.
                    val relaxedCandidates = extractFromJsonRelaxed(html)
                        .filter { isLikelyPostMedia(it) }
                        .let { dedupeAndNormalize(it) }
                    if (relaxedCandidates.isNotEmpty()) {
                        val media = relaxedCandidates.mapIndexed { index, mediaUrl ->
                            ParsedTumblrMedia(
                                sourceUrl = source,
                                mediaUrl = mediaUrl,
                                title = "#${index + 1}",
                                type = guessType(mediaUrl)
                            )
                        }
                        TumblrShareParseResult.Success(media)
                    } else {
                        // Truly empty — the interstitial hides everything until
                        // the user clicks through in a browser.  Return LoginRequired
                        // so the app opens a browser for confirmation; the retry will
                        // have the cookie needed to bypass the interstitial.
                        TumblrShareParseResult.LoginRequired(
                            url = postUrl,
                            message = "Adult content warning — open in browser to confirm and retry."
                        )
                    }
                } else if (candidates.isEmpty()) {
                    TumblrShareParseResult.Empty("No downloadable media found")
                } else {
                    val media = candidates.mapIndexed { index, mediaUrl ->
                        ParsedTumblrMedia(
                            sourceUrl = source,
                            mediaUrl = mediaUrl,
                            title = "#${index + 1}",
                            type = guessType(mediaUrl)
                        )
                    }
                    TumblrShareParseResult.Success(media)
                }
            } else if (response.code == 401 || response.code == 403 || response.finalUrl.contains("/login_required/") || response.finalUrl.contains("/login/")) {
                TumblrShareParseResult.LoginRequired(
                    url = postUrl,
                    message = "HTTP 401/403 or login page — this content may be private."
                )
            } else {
                TumblrShareParseResult.Error("Page request failed, HTTP ${response.code}")
            }
        }.getOrElse {
            Log.w(TAG, "parseWithPageHtml failed", it)
            TumblrShareParseResult.Error("Page parse failed: ${it.message}")
        }
    }

    private fun extractFromHtml(html: String, preferVideo: Boolean = false, structuredOnly: Boolean = false): List<String> {
        val candidates = buildList {
            addAll(extractFromInitialState(html))
            addAll(extractFromJsonScripts(html))

            if (!structuredOnly) {
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

    /**
     * Extract media URLs from JSON scripts without JSON-path filtering.
     *
     * Tumblr's adult-content interstitial page still loads the post data in
     * [initialStateScriptRegex] but the JSON structure may put media URLs
     * under paths that [isLikelyPostMediaPath] rejects (e.g. keys unrelated
     * to "media", "photo", "video").  This function collects ALL
     * media.tumblr.com URLs from every JSON script block, relying on
     * [isLikelyPostMedia] (URL-level filtering) for noise removal.
     */
    private fun extractFromJsonRelaxed(html: String): List<String> {
        val urls = LinkedHashSet<String>()
        val jsonMediaUrlRegex = Regex(
            "https?://[^\"'\\s<>)\\]}]+media\\.tumblr\\.com[^\"'\\s<>)\\]}]+",
            RegexOption.IGNORE_CASE
        )

        fun extractFromText(raw: String) {
            jsonMediaUrlRegex.findAll(unescapeJsonText(raw)).forEach { m ->
                val url = m.value.trimEnd(')', ']', '}', ',', ';', '"', '\'', '>')
                if (url.isNotBlank()) {
                    urls.add(url)
                }
            }
        }

        initialStateScriptRegex.findAll(html).forEach { match ->
            extractFromText(match.groupValues.getOrNull(1).orEmpty())
        }
        applicationJsonScriptRegex.findAll(html).forEach { match ->
            extractFromText(match.groupValues.getOrNull(1).orEmpty())
        }

        return urls.toList()
    }

    private fun extractUrlsFromJsonText(jsonText: String): List<String> {
        val urls = LinkedHashSet<String>()

        runCatching {
            val root = JSONObject(jsonText)
            collectMediaUrlsFromJson(root, emptyList(), urls)
        }

        return urls.toList()
    }

    private fun collectMediaUrlsFromJson(
        value: Any?,
        jsonPath: List<String>,
        urls: MutableSet<String>
    ) {
        when (value) {
            null, JSONObject.NULL -> return
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isNoiseJsonKey(key)) {
                        continue
                    }
                    collectMediaUrlsFromJson(value.get(key), jsonPath + key, urls)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectMediaUrlsFromJson(value.get(i), jsonPath + i.toString(), urls)
                }
            }
            is String -> {
                val trimmed = unescapeJsonText(value.trim())
                if (!isLikelyPostMediaPath(jsonPath)) {
                    return
                }
                // For JSON-structured data, match any media.tumblr.com URL instead of
                // relying on file extension patterns.  The JSON path filtering + noise
                // tokens already provide sufficient precision; extension-based matching
                // is too fragile (Tumblr uses .pnj for high-quality images).
                val jsonMediaUrlRegex = Regex(
                    "https?://[^\"'\\s<>)\\]}]+media\\.tumblr\\.com[^\"'\\s<>)\\]}]+",
                    RegexOption.IGNORE_CASE
                )
                jsonMediaUrlRegex.findAll(trimmed).forEach { m ->
                    val url = m.value.trimEnd(')', ']', '}', ',', ';', '"', '\'')
                    if (url.isNotBlank()) {
                        urls.add(url)
                    }
                }
            }
        }
    }

    private fun isLikelyPostMediaPath(jsonPath: List<String>): Boolean {
        val path = jsonPath.map { it.lowercase(Locale.ROOT) }
        if (path.any { segment -> NOISE_PATH_TOKENS.any { token -> segment == token || segment.contains(token) } }) {
            return false
        }
        return path.any {
            it == "media" || it == "photos" || it == "photo" || it == "videos" ||
                it == "video" || it.endsWith("_media") || it.endsWith("media")
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
        val lower = key.lowercase(Locale.ROOT)
        if (NOISE_PATH_TOKENS.any { token -> lower == token || lower.contains(token) }) return true

        return when (lower) {
            "canonical_url", "blogavatar", "profile", "profile_url", "favicon", "og_image",
            "ogvideo", "actor", "ogvideo:url", "og:image", "blogname", "colors",
            "layout", "accent_colors", "theme_id", "theme_type", "theme_color", "user_theme",
            "tumblr_style" -> true
            else -> false
        }
    }

    private fun extractFromMeta(regex: Regex, html: String): List<String> =
        regex.findAll(html).map { it.groupValues[1] }.toList()

    private fun extractImageCandidates(html: String): List<String> =
        imageUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private fun extractVideoCandidates(html: String): List<String> =
        videoUrlRegex.findAll(html).map { it.value }.filter { isLikelyPostMedia(it) }.toList()

    private val loginPageTitleRegex = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)

    /**
     * Detect whether the page HTML is a Tumblr adult-content interstitial.
     *
     * Tumblr shows a full-page overlay for blogs/posts flagged as adult content
     * when the viewer has not explicitly confirmed they wish to view it.  The
     * actual post content is hidden behind this interstitial and requires either
     * a valid confirmation cookie or a click-through in a JavaScript-capable
     * context (WebView) to reach.
     */
    private fun looksLikeAdultContentPage(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)

        // Strong adult-content warning signals — look for the interstitial
        // heading text and the confirmation button/link text so that regular
        // posts mentioning these phrases are not falsely flagged.
        val adultHeadings = listOf(
            "this blog may contain adult content",
            "this blog may contain mature content",
            "this blog may contain sensitive content",
            "this post may contain adult content",
            "this post may contain mature content",
            "this post may contain sensitive content",
            "possible adult content",
            "this blog contains adult content",
            "adult content warning",
            "mature content warning",
        )
        val hasHeading = adultHeadings.any { lower.contains(it) }
        if (!hasHeading) return false

        // Require at least one confirmation-action signal to reduce false
        // positives (e.g. a post that merely *mentions* adult content).
        val actionSignals = listOf(
            "view this post anyway",
            "continue to this post",
            "show adult content",
            "i understand, continue",
            "i understand and wish to proceed",
            "continue to tumblr",
            "adult_interstitial",
            "adult-warning",
            "class=\"adult\"".lowercase(),
            "name=\"adult_confirm\"".lowercase(),
        )
        val hasAction = actionSignals.any { lower.contains(it) }

        return hasAction
    }

    private fun looksLikeLoginPage(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)

        // Strong signal: presence of a password input field indicates a real login form
        if (lower.contains("type=\"password\"") || lower.contains("type='password'")) {
            return true
        }

        // Check page title — a real login page has "Log in" or "Sign in" in the title,
        // whereas regular post pages have the blog name or post content as title.
        val titleMatch = loginPageTitleRegex.find(html)
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].lowercase(Locale.ROOT)
            if (title.contains("log in") || title.contains("sign in")) {
                return true
            }
        }

        // Explicit redirect to login_required (handled earlier via finalUrl check,
        // but also check body for edge cases where the redirect isn't caught)
        if (lower.contains("/login_required/")) {
            return true
        }

        return false
    }

    private fun isLikelyPostMedia(url: String): Boolean {
        if (url.contains("media.tumblr.com").not()) return false
        val lower = url.lowercase(Locale.ROOT)

        if (lower.contains("/avatar/") ||
            lower.contains("/avatars/") ||
            lower.contains("avatar_") ||
            lower.contains("/background") ||
            lower.contains("background_") ||
            lower.contains("backgrounds") ||
            lower.contains("/cover/") ||
            lower.contains("cover_") ||
            lower.contains("header") ||
            lower.contains("userpic") ||
            lower.contains("banner") ||
            lower.contains("/previews/")
        ) {
            return false
        }

        if (lower.contains("/poster/") || lower.contains("poster")) return false
        if (lower.contains("thumbnail") || lower.contains("thumb")) return false
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

        // Accept media.tumblr.com URLs both with and without file extensions.
        // Some Tumblr posts serve media at extensionless paths (e.g.
        // /HASH1/HASH2/) while others have traditional extensions (.jpg, .png,
        // .mp4, .pnj, etc.).  The domain check + noise path filters already
        // provide sufficient precision; the extension requirement was too
        // aggressive and silently dropped valid post media.
        val lastSegment = Uri.parse(url).lastPathSegment ?: return false
        val dotIndex = lastSegment.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex == lastSegment.length - 1) {
            // No extension — accept if it looks like a typical Tumblr media
            // hash (two path segments, alphanumeric with dashes/underscores).
            return true
        }
        val ext = lastSegment.substring(dotIndex + 1)
        return ext.length in 2..5 && ext.all { it.isLetterOrDigit() }
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
            val identity = DownloadUtils.normalizeMediaIdentity(url)
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

    /** Check whether CookieManager has non-blank cookies for Tumblr hosts. */
    /**
     * Check whether CookieManager has actual auth cookies (not just Cloudflare
     * tracking cookies) for Tumblr hosts.
     */
    private fun hasTumblrCookies(): Boolean {
        val cookieManager = CookieManager.getInstance()
        val hosts = listOf("https://www.tumblr.com", "https://tumblr.com")
        return hosts.any { host ->
            val rawCookie = cookieManager.getCookie(host) ?: return@any false
            // Filter out Cloudflare-only cookies — same logic as
            // TumblrCookieStore.normalizeCookieString().
            rawCookie.split(';').any { segment ->
                val trimmed = segment.trim()
                trimmed.isNotBlank() &&
                    !trimmed.lowercase(Locale.ROOT).startsWith("__cf_bm=") &&
                    !trimmed.lowercase(Locale.ROOT).startsWith("_cfuvid=")
            }
        }
    }

    private class WebViewCookieJar : CookieJar {
        private val cookieManager = CookieManager.getInstance()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            var rawCookie = cookieManager.getCookie(url.toString())
            // Cookies set for www.tumblr.com (without wildcard domain) won't
            // be returned for subdomain URLs like akarimarisa.tumblr.com.
            // Fall back to the www origin when the host-specific check fails.
            if (rawCookie.isNullOrBlank() && url.host.endsWith(".tumblr.com")) {
                rawCookie = cookieManager.getCookie("https://www.tumblr.com/")
            }
            if (rawCookie.isNullOrBlank()) return emptyList()
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
