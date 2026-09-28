package io.github.akarimarisa.tumblrdownloader.utils

import android.net.Uri
import io.github.akarimarisa.tumblrdownloader.model.ImageQualityTier
import io.github.akarimarisa.tumblrdownloader.model.MediaQualitySelector
import io.github.akarimarisa.tumblrdownloader.model.MediaQualitySettings
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import io.github.akarimarisa.tumblrdownloader.model.VideoQualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for the tumblr.com anonymous blog-feed API — the batch download
 * counterpart of [TumblrParser] (single posts / oEmbed).
 *
 * All data is public and requires no login: the weekly v2 feed API is served
 * with an anonymous `Authorization: Bearer <token>` copied straight out of the
 * blog page's `___INITIAL_STATE___` payload, exactly like the web client does.
 *
 * Two documented Tumblr behaviors are baked into this parser:
 *  1. The feed is enumerable only via an opaque cursor (`links.next.href`).
 *     Page sizes vary (17–20 posts/pg) and `totalPosts` is an upper bound,
 *     so neither one may drive pagination — the cursor does.
 *  2. `*.tumblr.com` blog subdomains are behind a JS anti-bot challenge, so
 *     this parser never touches them. It only talks to `www.tumblr.com`.
 */
object BlogFeedParser {

    private val initialStateRegex = Regex(
        "<script[^>]+id=['\"]__+INITIAL_STATE__+['\"][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Absolute base for the relative API hrefs in `PeepprRoute.initialTimeline.nextLink` / `response.links.next.href`. */
    private const val API_BASE = "https://www.tumblr.com/api"

    private val blogUrlRegex = Regex(
        "(?i)^https?://" +
            "(?:(?:www\\.)?tumblr\\.com/([a-z0-9][a-z0-9_.-]{0,40})" +
            "|([a-z0-9][a-z0-9_.-]{0,40})\\.tumblr\\.com)/?$"
    )

    // ── data classes ─────────────────────────────────────────────────

    data class BlogInfo(
        val blogName: String,
        val title: String,
        val blogUrl: String,
        val numberOfPosts: Int?,
        val isAdult: Boolean,
        val isPasswordProtected: Boolean,
        val isHiddenFromBlogNetwork: Boolean
    )

    data class BootstrapData(
        val info: BlogInfo,
        val apiToken: String,
        /** Absolute URL of the next feed page (null when there is no more). */
        val nextLink: String?,
        /** Page-1 post objects embedded in the bootstrap page. */
        val posts: List<JSONObject>
    )

    data class FeedPage(
        val posts: List<JSONObject>,
        /** Absolute URL of the next feed page (null = feed end). */
        val nextLink: String?,
        val totalPosts: Int?
    )

    data class FetchResponse(val code: Int, val body: String)

    /** Pluggable transport so the manager and parser are unit-testable offline. */
    interface FeedGateway {
        suspend fun fetch(url: String, bearerToken: String?): FetchResponse
    }

    // ── blog URL helpers ─────────────────────────────────────────────

    /** True when [text] is exactly a Tumblr blog URL (www or subdomain form). */
    fun isBlogUrl(text: String): Boolean = parseBlogIdentifier(text) != null

    /**
     * Extract the bare blog name from a blog URL:
     *  - `https://www.tumblr.com/bonesbunns` → `bonesbunns`
     *  - `https://bonesbunns.tumblr.com/`    → `bonesbunns`
     * Returns null when the text is not a standalone blog URL.
     */
    fun parseBlogIdentifier(text: String): String? {
        val clean = text.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val m = blogUrlRegex.find(clean) ?: return null
        val group = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return group.lowercase()
    }

    fun absoluteApiUrl(href: String): String =
        if (href.startsWith("http", ignoreCase = true)) href else API_BASE + href

    // ── bootstrap (blog page SSR) ─────────────────────────────────────

    /**
     * Parse `https://www.tumblr.com/<blog>` SSR HTML: extracts the anonymous
     * API token, page-1 posts and the first feed cursor from
     * `___INITIAL_STATE___`.
     */
    fun parseBootstrap(html: String, fallbackBlogName: String): BootstrapData? {
        val match = initialStateRegex.find(html) ?: return null
        val root = runCatching { JSONObject(match.groupValues.getOrNull(1).orEmpty()) }.getOrNull() ?: return null
        val apiToken = root.optJSONObject("apiFetchStore")?.optString("API_TOKEN").orEmpty()

        val timeline = root.optJSONObject("PeeprRoute")
            ?.optJSONObject("initialTimeline")
        val objects = timeline?.optJSONArray("objects") ?: JSONArray()
        val posts = buildList {
            for (i in 0 until objects.length()) {
                objects.optJSONObject(i)?.let(::add)
            }
        }
        val nextHref = timeline?.optJSONObject("nextLink")
            ?.optString("href")
            ?.takeIf { it.isNotBlank() }

        val info = parseBlogInfoFromState(root, fallbackBlogName)
        return BootstrapData(
            info = info,
            apiToken = apiToken,
            nextLink = nextHref?.let(::absoluteApiUrl),
            posts = posts
        )
    }

    /** Extract blog metadata from the `queries` section of the bootstrap state. */
    fun parseBlogInfoFromState(root: JSONObject, fallbackBlogName: String): BlogInfo {
        val queries = root.optJSONObject("queries")?.optJSONArray("queries") ?: JSONArray()
        var data: JSONObject? = null
        for (i in 0 until queries.length()) {
            val q = queries.optJSONObject(i) ?: continue
            val d = q.optJSONObject("state")?.optJSONObject("data") ?: continue
            if (d.has("name") || d.has("url")) {
                data = d
                break
            }
        }
        val blog = data ?: JSONObject()
        return BlogInfo(
            blogName = blog.optString("name").ifBlank { fallbackBlogName },
            title = blog.optString("title").ifBlank { fallbackBlogName },
            blogUrl = blog.optString("url").ifBlank { "https://$fallbackBlogName.tumblr.com/" },
            numberOfPosts = null,
            isAdult = blog.optBoolean("isAdult", false),
            isPasswordProtected = blog.optBoolean("isPasswordProtected", false),
            isHiddenFromBlogNetwork = blog.optBoolean("isHiddenFromBlogNetwork", false)
        )
    }

    // ── feed page (v2 API JSON) ───────────────────────────────────────

    /**
     * Parse one anonymous v2 API feed response body:
     * `{ response: { posts, totalPosts, links: { next: { href } } } }`.
     */
    fun parseFeedPage(body: String): FeedPage? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val resp = root.optJSONObject("response") ?: return null
        val postsArray = resp.optJSONArray("posts") ?: JSONArray()
        val posts = buildList {
            for (i in 0 until postsArray.length()) {
                postsArray.optJSONObject(i)?.let(::add)
            }
        }
        val nextHref = resp.optJSONObject("links")
            ?.optJSONObject("next")
            ?.optString("href")
            ?.takeIf { it.isNotBlank() }
        val total = resp.opt("totalPosts")
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { it.toString().toIntOrNull() }
        return FeedPage(
            posts = posts,
            nextLink = nextHref?.let(::absoluteApiUrl),
            totalPosts = total
        )
    }

    // ── media extraction ──────────────────────────────────────────────

    /**
     * Extract every downloadable media URL of one NPF post, applying the
     * tier-selection and gifv→mp4 rules (see the design doc).
     *
     * Sources scanned: `post.content[]` and `post.trail[].content[]`, both of
     * which may hold `image`/`video` blocks. The `media` field of an NPF block
     * is either an array of media dicts or a single dict (native video blocks)
     * — both shapes are normalized. GIFV media carries an mp4 rendition list
     * (`media.video[]`); when present the mp4 is always preferred over the
     * static frame, per product decision.
     */
    fun extractMediaFromPost(
        post: JSONObject,
        postUrl: String,
        quality: MediaQualitySettings
    ): List<ParsedTumblrMedia> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        val types = LinkedHashMap<String, MediaType>()

        fun add(identity: String, url: String, type: MediaType) {
            if (!isDownloadableMedia(url)) return
            groups.getOrPut(identity) { mutableListOf() }.add(url)
            types.putIfAbsent(identity, type)
        }

        fun scan(content: JSONArray) {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "image", "video" -> {
                        val media = block.opt("media") ?: continue
                        val items = if (media is JSONArray) {
                            buildList {
                                for (j in 0 until media.length()) {
                                    media.optJSONObject(j)?.let(::add)
                                }
                            }
                        } else listOfNotNull(media as? JSONObject)

                        for (m in items) {
                            val gifv = m.optJSONArray("video")
                            if (gifv != null && gifv.length() > 0) {
                                val mp4 = selectBestGifv(gifv, quality.videoTier) ?: continue
                                val key = m.optString("mediaKey").ifBlank {
                                    DownloadUtils.normalizeMediaIdentity(mp4)
                                }
                                add(key, mp4, MediaType.VIDEO)
                            } else {
                                val u = m.optString("url")
                                if (u.isBlank()) continue
                                val key = m.optString("mediaKey").ifBlank {
                                    DownloadUtils.normalizeMediaIdentity(u)
                                }
                                add(key, u, TumblrParser.guessType(u))
                            }
                        }

                        // Native Tumblr video: the block itself carries the mp4.
                        if (block.optString("type") == "video") {
                            val u = block.optString("url").ifBlank { "" }
                            if (u.isNotBlank() && isDownloadableMedia(u)) {
                                val key = block.optJSONObject("media")?.optString("mediaKey").orEmpty()
                                    .ifBlank { DownloadUtils.normalizeMediaIdentity(u) }
                                add(key, u, MediaType.VIDEO)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        scan(post.optJSONArray("content") ?: JSONArray())
        val trail = post.optJSONArray("trail")
        if (trail != null) {
            for (i in 0 until trail.length()) {
                trail.optJSONObject(i)?.optJSONArray("content")?.let(::scan)
            }
        }

        var index = 1
        return buildList {
            for ((identity, urls) in groups) {
                val type = types[identity] ?: MediaType.UNKNOWN
                val chosen = when {
                    urls.size <= 1 -> urls.first()
                    type == MediaType.VIDEO -> selectBestVideo(urls, quality.videoTier) ?: urls.first()
                    else -> selectBestImage(urls, quality.imageTier) ?: urls.first()
                }
                add(
                    ParsedTumblrMedia(
                        sourceUrl = postUrl,
                        mediaUrl = chosen,
                        title = "$postUrl#${index}",
                        type = TumblrParser.guessType(chosen)
                    )
                )
                index++
            }
        }
    }

    /** Pick the mp4 rendition for a GIFV media entry by video tier. */
    private fun selectBestGifv(gifv: JSONArray, tier: VideoQualityTier): String? {
        val urls = buildList {
            for (i in 0 until gifv.length()) {
                gifv.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return selectBestVideo(urls, tier)
    }

    private fun selectBestVideo(urls: List<String>, tier: VideoQualityTier): String? {
        if (urls.size <= 1) return urls.firstOrNull()
        if (tier.isUnlimited) {
            return urls.maxWithOrNull(
                compareBy { MediaQualitySelector.parseSize(it, MediaType.VIDEO) ?: 0 }
            )
        }
        return MediaQualitySelector.selectBestUrl(urls, MediaType.VIDEO, tier.minHeight, tier.maxHeight)
    }

    private fun selectBestImage(urls: List<String>, tier: ImageQualityTier): String? {
        if (urls.size <= 1) return urls.firstOrNull()
        if (!tier.isUnlimited) {
            return MediaQualitySelector.selectBestUrl(urls, MediaType.IMAGE, tier.minEdge, tier.maxEdge)
        }
        // Unlimited: pick the largest rendition, tie-breaking for square crops
        // (squares are pre-crop previews, sized in one edge only).
        var best: String? = null
        var bestSize = -1
        var bestSquareBonus = Int.MAX_VALUE
        for (u in urls) {
            val size = MediaQualitySelector.parseSize(u, MediaType.IMAGE) ?: 0
            val squareBonus = if (isSquareRendition(u)) 0 else 1
            if (best == null || size > bestSize || (size == bestSize && squareBonus < bestSquareBonus)) {
                best = u
                bestSize = size
                bestSquareBonus = squareBonus
            }
        }
        return best
    }

    private fun isSquareRendition(url: String): Boolean =
        Regex("_\\d{2,4}sq(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** Only real post media is downloadable — reject avatars/posters/background noise. */
    fun isDownloadableMedia(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        if (!host.endsWith("media.tumblr.com")) return false
        val lower = url.lowercase()
        if (lower.contains("avatar") || lower.contains("/poster/") || lower.contains("thumbnail")) return false
        return true
    }
}

/** Live transport: reuses the shared OkHttp client (same proxy/cookie config). */
object LiveBlogFeedGateway : BlogFeedParser.FeedGateway {
    override suspend fun fetch(url: String, bearerToken: String?): BlogFeedParser.FetchResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Android) TumblrDownloader/1.0")
                .addHeader(
                    "Accept",
                    if (bearerToken != null) "application/json;format=camelcase"
                    else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            if (bearerToken != null) {
                builder.addHeader("Authorization", "Bearer $bearerToken")
            }
            TumblrParser.httpClient.newCall(builder.build()).execute().use { response ->
                BlogFeedParser.FetchResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }
}