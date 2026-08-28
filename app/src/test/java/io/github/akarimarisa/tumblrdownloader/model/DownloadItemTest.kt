package io.github.akarimarisa.tumblrdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadItemTest {

    private val item = DownloadItem(
        id = "download-id",
        sourceUrl = "https://www.tumblr.com/example/post/1",
        mediaUrl = "https://va.media.tumblr.com/example.mp4",
        title = "Example",
        type = MediaType.VIDEO
    )

    @Test
    fun withPartialDownload_preservesResumeTargetAndOffset() {
        val resumed = item.withPartialDownload("content://downloads/42", 6_291_456L)

        assertEquals("content://downloads/42", resumed.downloadFileUri)
        assertEquals(6_291_456L, resumed.downloadedBytes)
        assertEquals(item.id, resumed.id)
        assertEquals(item.mediaUrl, resumed.mediaUrl)
    }

    @Test
    fun withPartialDownload_clampsNegativeOffsets() {
        val resumed = item.withPartialDownload("content://downloads/42", -1L)

        assertEquals(0L, resumed.downloadedBytes)
        assertEquals("content://downloads/42", resumed.downloadFileUri)
        assertNull(item.downloadFileUri)
    }
}
