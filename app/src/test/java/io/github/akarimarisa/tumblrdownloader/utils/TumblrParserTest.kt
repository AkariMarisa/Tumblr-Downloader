package io.github.akarimarisa.tumblrdownloader.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** URL-classification + HTML extraction tests for the share parser (#R28, #R30, #R31, #R33). */
@RunWith(RobolectricTestRunner::class)
class TumblrParserTest {

    @Test
    fun `tmblr short links are recognised`() {
        assertTrue(TumblrParser.isTumblrShareUrl("https://tmblr.co/ZQf2iA1xYkPz"))
        assertEquals(
            "https://tmblr.co/ZQf2iA1xYkPz",
            TumblrParser.firstTumblrUrl("看这个 https://tmblr.co/ZQf2iA1xYkPz 好棒")
        )
    }

    @Test
    fun `post url extracted from commented text`() {
        assertEquals(
            "https://www.tumblr.com/blogname/123456789012345678/slug",
            TumblrParser.firstTumblrUrl("分享一下 https://www.tumblr.com/blogname/123456789012345678/slug，谢谢")
        )
        assertNull(TumblrParser.firstTumblrUrl("hello world"))
    }

    @Test
    fun `private post url returns the localizable error key`() = runBlocking {
        val result = TumblrParser.parseShareUrl("https://someblog.tumblr.com/private/1234567890/slug")
        assertTrue("expected Error, got $result", result is TumblrShareParseResult.Error)
        // The literal is the mapping key consumed by DownloadStateManager
        // .localizeError — Chinese UIs show the localized string (#R33).
        assertEquals("Private Tumblr posts are not supported yet", (result as TumblrShareParseResult.Error).message)
    }

    @Test
    fun `non tumblr text is rejected`() = runBlocking {
        val result = TumblrParser.parseShareUrl("hello world")
        assertTrue("expected Error, got $result", result is TumblrShareParseResult.Error)
        assertEquals("Not a valid Tumblr share link", (result as TumblrShareParseResult.Error).message)
    }

    @Test
    fun `text-only post page yields no media even with og image present`() {
        // #R30 / case 2.28: the blog cover in og:image used to be scraped as
        // the post's media and opened a picker full of page-level junk.
        val html = """
            <html><head>
            <meta property="og:image" content="https://64.media.tumblr.com/blog/cover_1419x640.jpg"/>
            <script id="___INITIAL_STATE___">{}</script>
            </head><body><article class="post"><p>just words</p></article></body></html>
        """.trimIndent()
        val out = TumblrParser.extractFromHtml(html)
        assertTrue("must not scrape the blog cover: $out", out.isEmpty())
    }

    @Test
    fun `scoped scan keeps figures inside a post container`() {
        val html = """
            <html><head><script id="___INITIAL_STATE___">{}</script></head>
            <body><article class="post"><figure><img src="https://64.media.tumblr.com/x/s1280x1920/abc123def456.jpg"/></figure></article></body></html>
        """.trimIndent()
        val out = TumblrParser.extractFromHtml(html)
        assertTrue("figure inside article must be found: $out", out.any { it.contains("abc123def456") })
    }

    @Test
    fun `page without post container scopes to nothing`() {
        // #R30 / case 2.27: 404/server-error pages carry decorative art but no
        // post container — nothing may be scraped from them.
        val html = "<html><head><meta property=\"og:image\" content=\"https://64.media.tumblr.com/404art.jpg\"/></head><body><h1>Not found</h1><img src=\"https://64.media.tumblr.com/404art.jpg\"/></body></html>"
        assertEquals("", TumblrParser.scopeToPostContent(html))
        assertTrue(TumblrParser.extractFromHtml(html).isEmpty())
    }

    @Test
    fun `browser challenge page is recognised`() {
        // #R44: the real 403 face captured on device — a JS proof-of-work
        // challenge an HTTP client can never pass, logged in or out.
        val challenge = """
            <!DOCTYPE html><title>Checking your browser...</title>
            <noscript><p>Javascript required</p></noscript>
            <script src="/__challenge/probes.js?v=c3301fdb5ac5"></script>
            <script>window.HASHCASH_CONFIG={prefix:"0000",minTime:1500,headline:"Checking your browser"};</script>
        """.trimIndent()
        assertTrue(TumblrParser.isBrowserChallenge(challenge))
        // The markers must not fire on ordinary pages.
        assertFalse(
            "normal post page must not read as a challenge",
            TumblrParser.isBrowserChallenge(
                "<html><head><title>Just a post</title></head><body><article>hello</article></body></html>"
            )
        )
        assertFalse(
            "login page must not read as a challenge",
            TumblrParser.isBrowserChallenge("<html><title>Log in</title><input type=\"password\"/></html>")
        )
    }
}
