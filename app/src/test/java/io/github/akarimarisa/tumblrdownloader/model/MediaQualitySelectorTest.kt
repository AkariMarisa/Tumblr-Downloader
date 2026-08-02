package io.github.akarimarisa.tumblrdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [MediaQualitySelector] — the resolution-range selection logic
 * behind the image/video quality tier settings.
 *
 * Pure JVM tests (no Android deps) so they run without Robolectric.
 */
class MediaQualitySelectorTest {

    // ── parseSize ─────────────────────────────────────────────────────

    @Test
    fun parseSize_image_pathSegment_usesLongEdge() {
        assertEquals(1920, MediaQualitySelector.parseSize("https://64.media.tumblr.com/h1/h2/s1280x1920/x.jpg", MediaType.IMAGE))
        assertEquals(1280, MediaQualitySelector.parseSize("https://64.media.tumblr.com/h1/h2/s1280x720/x.jpg", MediaType.IMAGE))
    }

    @Test
    fun parseSize_image_suffixWxH() {
        assertEquals(1920, MediaQualitySelector.parseSize("https://64.media.tumblr.com/h1/h2/tumblr_x_1280x1920.jpg", MediaType.IMAGE))
    }

    @Test
    fun parseSize_image_suffixNum() {
        assertEquals(500, MediaQualitySelector.parseSize("https://64.media.tumblr.com/h1/h2/tumblr_x_500.jpg", MediaType.IMAGE))
    }

    @Test
    fun parseSize_video_pathSegment_usesHeight() {
        assertEquals(1080, MediaQualitySelector.parseSize("https://va.media.tumblr.com/h1/h2/s1920x1080/xyz.mp4", MediaType.VIDEO))
        assertEquals(720, MediaQualitySelector.parseSize("https://va.media.tumblr.com/h1/h2/s1280x720/xyz.mp4", MediaType.VIDEO))
    }

    @Test
    fun parseSize_video_suffixNum() {
        assertEquals(480, MediaQualitySelector.parseSize("https://va.media.tumblr.com/h1/h2/tumblr_x_480.mp4", MediaType.VIDEO))
        assertEquals(720, MediaQualitySelector.parseSize("https://va.media.tumblr.com/h1/h2/tumblr_x_720.mp4", MediaType.VIDEO))
    }

    @Test
    fun parseSize_unknownUrl_returnsNull() {
        assertNull(MediaQualitySelector.parseSize("https://64.media.tumblr.com/h1/h2/", MediaType.IMAGE))
        assertNull(MediaQualitySelector.parseSize("https://example.com/foo/bar.jpg", MediaType.IMAGE))
    }

    // ── selectBestUrl: bounded tier ────────────────────────────────────

    @Test
    fun select_picksLargestWithinRange() {
        // Standard image tier: [500, 1280) → 500x500 wins over 250x250 & 1280x1920
        val urls = listOf(
            "https://64.media.tumblr.com/h1/h2/s1280x1920/big.jpg",
            "https://64.media.tumblr.com/h1/h2/s250x250/small.jpg",
            "https://64.media.tumblr.com/h1/h2/s500x500/mid.jpg"
        )
        assertEquals(urls[2], MediaQualitySelector.selectBestUrl(urls, MediaType.IMAGE, 500, 1280))
    }

    @Test
    fun select_picksLargestAtOrAboveLowerBound_whenNothingInRange() {
        // Low tier [null, 500): nothing below 500 → pick the smallest above (500x500)
        val urls = listOf(
            "https://64.media.tumblr.com/h1/h2/s1280x1920/big.jpg",
            "https://64.media.tumblr.com/h1/h2/s500x500/mid.jpg"
        )
        assertEquals(urls[1], MediaQualitySelector.selectBestUrl(urls, MediaType.IMAGE, null, 500))
    }

    @Test
    fun select_picksLargestBelowLowerBound_whenAboveRangeAlsoExists() {
        // High image tier [1280, null): candidates 500 and 1280. 1280 in range → picks it.
        val urls = listOf(
            "https://64.media.tumblr.com/h1/h2/s500x500/mid.jpg",
            "https://64.media.tumblr.com/h1/h2/s1280x1920/big.jpg"
        )
        assertEquals(urls[1], MediaQualitySelector.selectBestUrl(urls, MediaType.IMAGE, 1280, null))
    }

    @Test
    fun select_video_picksWithinRange() {
        // 720p tier [720, 1080): 720p rendition wins over 480p & 1080p
        val urls = listOf(
            "https://va.media.tumblr.com/h1/h2/tumblr_x_480.mp4",
            "https://va.media.tumblr.com/h1/h2/tumblr_x_1080.mp4",
            "https://va.media.tumblr.com/h1/h2/tumblr_x_720.mp4"
        )
        assertEquals(urls[2], MediaQualitySelector.selectBestUrl(urls, MediaType.VIDEO, 720, 1080))
    }

    @Test
    fun select_video_picksClosestBelow_whenNoInRange() {
        // 1080p tier [1080, null): only 480p & 720p available → pick largest below (720p)
        val urls = listOf(
            "https://va.media.tumblr.com/h1/h2/tumblr_x_480.mp4",
            "https://va.media.tumblr.com/h1/h2/tumblr_x_720.mp4"
        )
        assertEquals(urls[1], MediaQualitySelector.selectBestUrl(urls, MediaType.VIDEO, 1080, null))
    }

    @Test
    fun select_singleCandidate_returnsIt() {
        val url = "https://64.media.tumblr.com/h1/h2/s1280x1920/x.jpg"
        assertEquals(url, MediaQualitySelector.selectBestUrl(listOf(url), MediaType.IMAGE, 500, 1280))
    }

    @Test
    fun select_unparseableCandidates_returnsNull() {
        val urls = listOf(
            "https://64.media.tumblr.com/h1/h2/",
            "https://example.com/foo/bar.jpg"
        )
        assertNull(MediaQualitySelector.selectBestUrl(urls, MediaType.IMAGE, 500, 1280))
    }

    @Test
    fun select_empty_returnsNull() {
        assertNull(MediaQualitySelector.selectBestUrl(emptyList(), MediaType.IMAGE, 500, 1280))
    }
}
