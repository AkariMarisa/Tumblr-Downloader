package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * File naming for batch vs single downloads: a blog's files must land in one
 * predictable folder and carry the blog name, not a stray `post` prefix, and
 * the queue title must be built from the same pieces as the file on disk.
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadUtilsNamingTest*"`
 */
@RunWith(RobolectricTestRunner::class)
class DownloadUtilsNamingTest {

    // ── blog folder (#14) ────────────────────────────────────────────────

    @Test
    fun blogFolderIsStableAndUniquePerBlog() {
        val folder = DownloadUtils.blogFolderName("kthingz")

        assertEquals(folder, DownloadUtils.blogFolderName("kthingz"))
        assertEquals(
            "blog names are case-insensitive on tumblr",
            folder,
            DownloadUtils.blogFolderName("KThingz")
        )
        assertNotEquals(folder, DownloadUtils.blogFolderName("latex-alexandra-susan"))
        assertTrue("folder starts with the blog name", folder.startsWith("kthingz-"))
        assertTrue(
            "short hash of 8 hex chars",
            Regex("^[0-9a-f]{8}$").matches(folder.substringAfterLast('-'))
        )
    }

    @Test
    fun blogFolderIsFilesystemSafe() {
        for (name in listOf("weird/name?with*chars", "  spaced  ", "..", "")) {
            val folder = DownloadUtils.blogFolderName(name)
            assertFalse("no path separators in $name", folder.contains('/'))
            assertFalse("no wildcards in $name", folder.contains('*'))
            assertFalse("no query marks in $name", folder.contains('?'))
            assertTrue("never empty", folder.isNotBlank())
        }
    }

    // ── author prefix (file name == queue title) ─────────────────────────

    @Test
    fun authorComesFromThePostUrl() {
        assertEquals(
            "kthingz",
            DownloadUtils.authorFromSourceUrl("https://www.tumblr.com/kthingz/832353009935794176/slug")
        )
        assertEquals(
            "bonesbunns",
            DownloadUtils.authorFromSourceUrl("https://bonesbunns.tumblr.com/post/123")
        )
    }

    @Test
    fun postPermalinksNeverBecomeAPostPrefix() {
        // www.tumblr.com/post/<id> carries no blog name: the literal "post"
        // used to end up as the file-name prefix for every blog download.
        assertEquals(
            "tumblr",
            DownloadUtils.authorFromSourceUrl("https://www.tumblr.com/post/832353009935794176")
        )
        assertEquals(
            "tumblr",
            DownloadUtils.authorFromSourceUrl("https://www.tumblr.com/post/123/some-slug")
        )
        assertEquals("tumblr", DownloadUtils.authorFromSourceUrl("https://www.tumblr.com/post"))
        assertEquals("tumblr", DownloadUtils.authorFromSourceUrl("not a url at all"))
    }

    // ── media id ─────────────────────────────────────────────────────────

    @Test
    fun mediaIdIsTheLastPathSegmentWithoutItsExtension() {
        assertEquals(
            "1c1cdf1c49c2e014ac2d547c1abcf22470bae2a3",
            DownloadUtils.mediaIdFromUrl(
                "https://64.media.tumblr.com/x/1c1cdf1c49c2e014ac2d547c1abcf22470bae2a3.jpg?s=1280x1920"
            )
        )
        assertEquals(
            "HASH2",
            DownloadUtils.mediaIdFromUrl("https://64.media.tumblr.com/HASH1/HASH2/")
        )
        assertEquals("media", DownloadUtils.mediaIdFromUrl("not a url"))
    }
}
