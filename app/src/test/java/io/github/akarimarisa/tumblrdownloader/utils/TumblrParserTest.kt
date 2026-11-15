package io.github.akarimarisa.tumblrdownloader.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL-classification tests for the share parser (#R28, #R31, #R33). */
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
}
