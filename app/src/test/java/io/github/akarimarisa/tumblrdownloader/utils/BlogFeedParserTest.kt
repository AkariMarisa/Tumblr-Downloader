package io.github.akarimarisa.tumblrdownloader.utils

import io.github.akarimarisa.tumblrdownloader.model.MediaQualitySettings
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [BlogFeedParser] against real page/feed fixtures captured
 * from tumblr.com (see [fixture]). Covers both the modern NPF blocks format
 * (bonesbunns/tempestrune) and the old-format posts without `mediaKey`
 * (rubberporn2 / rubbergenjutsu).
 */
@RunWith(RobolectricTestRunner::class)
class BlogFeedParserTest {

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("missing fixture $name")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Wrap a raw `___INITIAL_STATE___` JSON body in the SSR `<script>` tag the page carries. */
    private fun bootHtml(stateJson: String) =
        "<script id=\"___INITIAL_STATE___\">$stateJson</script>"

    // ── blog URL identification ──────────────────────────────────────

    @Test
    fun `blog url - www form`() {
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("https://www.tumblr.com/bonesbunns"))
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("http://www.tumblr.com/bonesbunns/"))
    }

    @Test
    fun `blog url - subdomain form`() {
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("https://bonesbunns.tumblr.com"))
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("https://bonesbunns.tumblr.com/"))
    }

    @Test
    fun `blog url - normalization`() {
        // case-insensitive host + query/fragment stripped
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("https://www.tumblr.com/BonesBunns/?ref=search"))
        assertEquals("bonesbunns", BlogFeedParser.parseBlogIdentifier("https://BonesBunns.tumblr.com#top"))
    }

    @Test
    fun `blog url - post urls rejected`() {
        assertNull(BlogFeedParser.parseBlogIdentifier("https://www.tumblr.com/bonesbunns/828871589032558592/hi"))
        assertNull(BlogFeedParser.parseBlogIdentifier("https://bonesbunns.tumblr.com/post/828871589032558592/hi"))
        assertNull(BlogFeedParser.parseBlogIdentifier("https://www.tumblr.com/tagged/xxx"))
        assertFalse(BlogFeedParser.isBlogUrl("https://www.tumblr.com/bonesbunns/828871589032558592"))
    }

    @Test
    fun `blog url - garbage rejected`() {
        assertNull(BlogFeedParser.parseBlogIdentifier("not a url"))
        assertNull(BlogFeedParser.parseBlogIdentifier("https://example.com/blog"))
        assertNull(BlogFeedParser.parseBlogIdentifier(""))
    }

    // ── bootstrap parsing ────────────────────────────────────────────

    @Test
    fun `bootstrap - full state page`() {
        val boot = BlogFeedParser.parseBootstrap(bootHtml(fixture("bootstrap_state.json")), "bonesbunns")
        assertNotNull(boot)
        assertEquals("aIcXSOoTtqrzR8L8YEIOmBeW94c3FmbSNSWAUbxsny9KKx5VFh", boot!!.apiToken)
        assertEquals("bonesbunns", boot.info.blogName)
        assertFalse(boot.info.isPasswordProtected)
        assertEquals(3, boot.posts.size)
        // nextLink is made absolute against the www.tumblr.com/api base
        val next = boot.nextLink
        assertNotNull(next)
        assertTrue("nextLink should be absolute: $next", next!!.startsWith("https://www.tumblr.com/api/v2/blog/"))
    }

    @Test
    fun `bootstrap - page without state returns null`() {
        assertNull(BlogFeedParser.parseBootstrap("<html><body>no state here</body></html>", "bonesbunns"))
        // hashcash challenge page (what *.*.tumblr.com serves)
        assertNull(BlogFeedParser.parseBootstrap("<!DOCTYPE html><title>Checking your browser...</title>", "bonesbunns"))
    }

    @Test
    fun `blog info - queries section`() {
        val boot = BlogFeedParser.parseBootstrap(bootHtml(fixture("bootstrap_state.json")), "fallback")
        assertNotNull(boot)
        assertEquals("bonesbunns", boot!!.info.blogName)
        assertEquals("Art Stuff Goes Here", boot.info.title)
    }

    @Test
    fun `blog info - fallback name when queries missing`() {
        val state = JSONObject("""{"PeeprRoute":{"initialTimeline":{"objects":[],"nextLink":null}}}""")
        val info = BlogFeedParser.parseBlogInfoFromState(state, "fallback_blog")
        assertEquals("fallback_blog", info.blogName)
        assertFalse(info.isPasswordProtected)
        assertFalse(info.isAdult)
        assertNull(info.numberOfPosts)
    }

    // ── feed page parsing ────────────────────────────────────────────

    @Test
    fun `feed page - posts total and next`() {
        val page = BlogFeedParser.parseFeedPage(fixture("feed_page.json"))
        assertNotNull(page)
        assertEquals(3, page!!.posts.size)
        assertEquals(287, page.totalPosts)
        val next = page.nextLink
        assertNotNull(next)
        assertTrue("nextLink should be absolute: $next", next!!.startsWith("https://www.tumblr.com/api/v2/blog/bonesbunns/posts?"))
    }

    @Test
    fun `feed page - end of feed (no links next)`() {
        val body = """{"response":{"posts":[{"type":"blocks"}],"totalPosts":2}}"""
        val page = BlogFeedParser.parseFeedPage(body)
        assertNotNull(page)
        assertEquals(1, page!!.posts.size)
        assertNull(page.nextLink)
        assertEquals(2, page.totalPosts)
    }

    @Test
    fun `feed page - totalPosts null when absent`() {
        val page = BlogFeedParser.parseFeedPage("""{"response":{"posts":[]}}""")
        assertNotNull(page)
        assertNull(page!!.totalPosts)
    }

    @Test
    fun `feed page - garbage body returns null`() {
        assertNull(BlogFeedParser.parseFeedPage("not json"))
        assertNull(BlogFeedParser.parseFeedPage("""{"something":"else"}"""))
    }

    // ── media extraction: gifv → mp4 ─────────────────────────────────

    @Test
    fun `extract - gifv produces mp4 video`() {
        val post = JSONObject(fixture("gifv_post.json"))
        val postUrl = post.getString("postUrl")
        val result = BlogFeedParser.extractMediaFromPost(post, postUrl, MediaQualitySettings())

        assertTrue("expected at least one media: ${result.map { it.mediaUrl }}", result.isNotEmpty())

        // The gifv block: media.video[] present → MUST pick the mp4 rendition,
        // never the static .gifv frame.
        val gifvHit = result.firstOrNull {
            it.mediaUrl.startsWith("https://") && it.mediaUrl.endsWith(".mp4")
        }
        assertNotNull("expected an mp4 for the gifv media", gifvHit)
        assertEquals(MediaType.VIDEO, gifvHit!!.type)
        assertFalse("gifv static frame must not be chosen", gifvHit.mediaUrl.endsWith(".gifv"))
        assertEquals(postUrl, gifvHit.sourceUrl)
    }

    // ── media extraction: old-format (no mediaKey) ───────────────────

    @Test
    fun `extract - old format image without mediaKey`() {
        val post = JSONObject(fixture("old_image_post.json"))
        val postUrl = post.getString("postUrl")
        val result = BlogFeedParser.extractMediaFromPost(post, postUrl, MediaQualitySettings())

        assertTrue("old-format image should be extracted: ${result.map { it.mediaUrl }}", result.isNotEmpty())
        val first = result.first()
        assertEquals(MediaType.IMAGE, first.type)
        assertTrue(first.mediaUrl.startsWith("https://64.media.tumblr.com/"))
        // ORIGINAL tier → pick the largest rendition of the block
        assertTrue(
            "largest rendition expected: ${first.mediaUrl}",
            first.mediaUrl.contains("_1280.") || first.mediaUrl.contains("/1280/")
        )
    }

    // ── media extraction: native video in trail ──────────────────────

    @Test
    fun `extract - native video block in trail content`() {
        val post = JSONObject(fixture("native_video_post.json"))
        val postUrl = post.getString("postUrl")
        val result = BlogFeedParser.extractMediaFromPost(post, postUrl, MediaQualitySettings())

        assertTrue("native video should be extracted: ${result.map { it.mediaUrl }}", result.isNotEmpty())
        val video = result.firstOrNull { it.type == MediaType.VIDEO }
        assertNotNull("expected a video item", video)
        assertTrue(
            "native video url should be va.media: ${video!!.mediaUrl}",
            video.mediaUrl.startsWith("https://va.media.tumblr.com/")
        )
        assertTrue(video.mediaUrl.endsWith(".mp4"))
    }

    // ── media extraction: content + trail dedup & filtering ──────────

    @Test
    fun `extract - duplicated media in content and trail yields one item`() {
        val media = """{"url":"https://64.media.tumblr.com/aaa_tumblr_x_1280.jpg"}"""
        val post = JSONObject(
            """{
              "postUrl":"https://www.tumblr.com/b/1",
              "content":[{"type":"image","media":[$media]}],
              "trail":[{"content":[{"type":"image","media":[$media]}]}]
            }"""
        )
        val result = BlogFeedParser.extractMediaFromPost(post, "https://www.tumblr.com/b/1", MediaQualitySettings())
        assertEquals(1, result.size)
    }

    @Test
    fun `extract - text-only post yields nothing`() {
        val post = JSONObject(
            """{
              "postUrl":"https://www.tumblr.com/b/2",
              "content":[{"type":"text","text":"hello"}]
            }"""
        )
        val result = BlogFeedParser.extractMediaFromPost(post, "https://www.tumblr.com/b/2", MediaQualitySettings())
        assertTrue(result.isEmpty())
    }

    // ── media URL filtering ──────────────────────────────────────────

    @Test
    fun `isDownloadableMedia - host and noise filtering`() {
        assertTrue(BlogFeedParser.isDownloadableMedia("https://64.media.tumblr.com/x/abc_1280.jpg"))
        assertTrue(BlogFeedParser.isDownloadableMedia("https://va.media.tumblr.com/tumblr_x.mp4"))
        assertFalse("avatar must be rejected", BlogFeedParser.isDownloadableMedia("https://64.media.tumblr.com/avatar_gr_512.png"))
        assertFalse("poster must be rejected", BlogFeedParser.isDownloadableMedia("https://64.media.tumblr.com/poster/frame1.jpg"))
        assertFalse("thumbnail must be rejected", BlogFeedParser.isDownloadableMedia("https://64.media.tumblr.com/tumblr_x_thumbnail_512.jpg"))
        assertFalse("non-tumblr host must be rejected", BlogFeedParser.isDownloadableMedia("https://cdn.example.com/x.jpg"))
        assertFalse("empty url", BlogFeedParser.isDownloadableMedia(""))
    }

    // ── absoluteApiUrl ───────────────────────────────────────────────

    @Test
    fun `absolute api url resolution`() {
        assertEquals(
            "https://www.tumblr.com/api/v2/blog/bonesbunns/posts",
            BlogFeedParser.absoluteApiUrl("/v2/blog/bonesbunns/posts")
        )
        assertEquals(
            "https://other.example.com/x",
            BlogFeedParser.absoluteApiUrl("https://other.example.com/x")
        )
    }
}
