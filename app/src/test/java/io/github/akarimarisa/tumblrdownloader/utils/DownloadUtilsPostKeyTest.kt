package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** File-scoped dedupe keys (#R35: one download per post). */
@RunWith(RobolectricTestRunner::class)
class DownloadUtilsPostKeyTest {

    private val media = "https://64.media.tumblr.com/x/5b0994da5e8a0fffee440484ccb851ffb8d13d17.jpg"

    @Test
    fun `same media in two posts yields two keys — download once per post`() {
        val original = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/ntn888/824351799806967808/slug", media, null, false
        )
        val reblog = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/fetishes-i-like3/833233941319270400/slug", media, null, false
        )
        assertNotEquals(original, reblog)
        assertTrue("original keyed by its author: $original", original.startsWith("ntn888|"))
        assertTrue("reblog keyed by its author: $reblog", reblog.startsWith("fetishes-i-like3|"))
    }

    @Test
    fun `size variants of one post collapse to one key`() {
        val a = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/ntn888/1/s",
            "https://64.media.tumblr.com/x/s1280x1920/5b0994da5e8a0fffee440484ccb851ffb8d13d17.jpg",
            null, false
        )
        val b = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/ntn888/1/s",
            "https://64.media.tumblr.com/x/s640x960/5b0994da5e8a0fffee440484ccb851ffb8d13d17.jpg",
            null, false
        )
        assertEquals(a, b)
    }

    @Test
    fun `batch rows are keyed by their blog — mirrors targetFileName`() {
        val batchKey = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/someone-else/1/s", media, "ginkobrief", inBatch = true
        )
        assertTrue(batchKey, batchKey.startsWith("ginkobrief|"))
        // blogName without a batch context must not win (single-post naming).
        val singleKey = DownloadUtils.postScopedMediaKey(
            "https://www.tumblr.com/someone-else/1/s", media, "ginkobrief", inBatch = false
        )
        assertTrue(singleKey, singleKey.startsWith("someone-else|"))
    }

    @Test
    fun `unusable media url yields the blank key`() {
        assertEquals("", DownloadUtils.postScopedMediaKey("https://www.tumblr.com/a/1", "", null, false))
    }
}
