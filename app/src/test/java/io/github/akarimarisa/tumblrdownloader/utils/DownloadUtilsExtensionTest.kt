package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * URL → extension inference is what names the file on disk *and* the queue
 * title, so it has to agree with the picker's GIF detection and it must not
 * fall back to `.bin` just because the URL has no extension.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadUtilsExtensionTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class DownloadUtilsExtensionTest {

    @Test
    fun plainExtensionIsKept() {
        assertEquals("gif", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.gif"))
        assertEquals("jpg", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.jpg"))
        assertEquals("mp4", DownloadUtils.inferExtension("https://va.media.tumblr.com/tumblr_x.mp4"))
        assertEquals("m3u8", DownloadUtils.inferExtension("https://va.media.tumblr.com/x.m3u8"))
    }

    @Test
    fun uppercaseExtensionIsNotLocaleSensitive() {
        // lowercased with Locale.ROOT — Locale.getDefault() would turn this
        // into "gıf" on a Turkish device and miss the whitelist.
        assertEquals("gif", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.GIF"))
    }

    @Test
    fun tumblrQuirksAreNormalised() {
        assertEquals("png", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.pnj"))
        // The picker counts .gifv as a GIF, so the file must say .gif too.
        assertEquals("gif", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.gifv"))
    }

    @Test
    fun extensionlessUrlFallsBackToFormatParameter() {
        val base = "https://64.media.tumblr.com/HASH1/HASH2"
        assertEquals("gif", DownloadUtils.inferExtension("$base?format=gif"))
        assertEquals("jpg", DownloadUtils.inferExtension("$base/?format=jpg&s=tumblr_x"))
        assertEquals("bin", DownloadUtils.inferExtension(base))
        assertEquals("bin", DownloadUtils.inferExtension("$base?s=tumblr_x"))
    }

    @Test
    fun unknownExtensionStaysBinary() {
        assertEquals("bin", DownloadUtils.inferExtension("https://64.media.tumblr.com/a/tumblr_x.bmp"))
        assertEquals("bin", DownloadUtils.inferExtension("not a url at all"))
    }

    @Test
    fun mimeTypeHelpersAgreeWithTheExtension() {
        assertEquals("image/gif", DownloadUtils.inferMimeType("gif"))
        assertEquals("application/octet-stream", DownloadUtils.inferMimeType("bin"))
        assertEquals("gif", DownloadUtils.extensionForMimeType("image/gif; charset=binary"))
        assertEquals("jpg", DownloadUtils.extensionForMimeType("IMAGE/JPEG"))
        assertNull(DownloadUtils.extensionForMimeType("text/html"))
        assertNull(DownloadUtils.extensionForMimeType(null))
    }

    @Test
    fun pickerGifDetectionAndFileExtensionNeverDisagree() {
        val gifUrls = listOf(
            "https://64.media.tumblr.com/a/tumblr_x.gif",
            "https://64.media.tumblr.com/a/tumblr_x.gifv",
            "https://64.media.tumblr.com/HASH1/HASH2?format=gif"
        )
        for (url in gifUrls) {
            assertEquals(
                "picker and file naming must agree for $url",
                "gif",
                DownloadUtils.inferExtension(url)
            )
        }
    }
}
