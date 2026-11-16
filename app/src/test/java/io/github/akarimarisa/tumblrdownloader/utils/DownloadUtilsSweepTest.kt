package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orphaned `.pending-*` recognition (#R39). */
class DownloadUtilsSweepTest {

    private val videoId = "482a589dda87f8b6cc56a8c7197f17b27c12504f"
    private val imageId = "cfb5348d34a632fb67cf4671290bb6f730cb7fc9"

    @Test
    fun `pending of a known row is kept — Range resume stays intact`() {
        // current naming generation: <author>-<id>
        assertFalse(
            DownloadUtils.isOrphanPendingFileName(
                ".pending-1795356148-qqchurch-$videoId.mov",
                listOf(videoId)
            )
        )
        // legacy generation: post-<id>
        assertFalse(
            DownloadUtils.isOrphanPendingFileName(
                ".pending-1795015435-post-$imageId.jpg",
                listOf(imageId)
            )
        )
    }

    @Test
    fun `pending without any matching row is an orphan`() {
        assertTrue(
            DownloadUtils.isOrphanPendingFileName(
                ".pending-1795106323-kthingz-38e347c0b0a3efe461c06ac690a806097126cb21.jpg",
                listOf(videoId)
            )
        )
        assertTrue(
            DownloadUtils.isOrphanPendingFileName(".pending-1-post-deadbeefdeadbeef.jpg", emptyList())
        )
    }

    @Test
    fun `non-pending files are never swept`() {
        assertFalse(
            DownloadUtils.isOrphanPendingFileName("qqchurch-$videoId.mov", emptyList())
        )
        assertFalse(DownloadUtils.isOrphanPendingFileName(".pending-keep", emptyList()))
    }

    @Test
    fun `short fallback ids never protect a pending`() {
        // mediaIdFromUrl falls back to "media" for malformed URLs — such a row
        // must not pin every pending file forever (documented trade-off: its
        // own resume state may be swept and restart from scratch).
        assertTrue(
            DownloadUtils.isOrphanPendingFileName(".pending-1-author-media.jpg", listOf("media"))
        )
    }
}
