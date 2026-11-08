package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for [DownloadUtils.normalizeMediaIdentity].
 *
 * Uses Robolectric because the function goes through `android.net.Uri.parse`.
 *
 * Issue #24: the rbrvp post https://www.tumblr.com/rbrvp/187621865550 carries a
 * single image served in 9 variant URLs (resolutions + square crops). Before
 * the `_Nsq` fix, the `_250sq` / `_75sq` crops produced two *extra* identities,
 * so "one post, one image" downloaded as three files (one per identity).
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*DownloadUtilsNormalizeTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadUtilsNormalizeTest {

    private val base = "https://64.media.tumblr.com/5c1a73de0ad924992daf87df503ea296"

    // All variants of the single rbrvp post image.
    private val postImageVariants = listOf(
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_100.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_1280.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_250.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_250sq.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_400.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_500.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_540.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_640.jpg",
        "$base/tumblr_pxmdpfJRos1y7xzt4o1_75sq.jpg"
    )

    // Blog avatar next to the post — must NOT merge with the post image.
    private val avatarUrl =
        "https://64.media.tumblr.com/805b098b10f87cfb323342acbb27a45a/dc25a1365a3ab418-96/s200x200u_c1/c71997161116a3e794db476b86f3c5207a0bbf8b.jpg"

    @Test
    fun allResolutionsAndSquareCropsCollapseToSingleIdentity() {
        val identities = postImageVariants.map { DownloadUtils.normalizeMediaIdentity(it) }
        assertEquals(
            "all 9 variants of the same image must dedupe to ONE identity",
            1,
            identities.distinct().size
        )
        assertEquals(
            "media.tumblr.com/5c1a73de0ad924992daf87df503ea296/tumblr_pxmdpfjros1y7xzt4o1.jpg",
            identities.first()
        )
    }

    @Test
    fun squareCropSharesIdentityWithItsResolution() {
        assertEquals(
            DownloadUtils.normalizeMediaIdentity("$base/tumblr_pxmdpfJRos1y7xzt4o1_250.jpg"),
            DownloadUtils.normalizeMediaIdentity("$base/tumblr_pxmdpfJRos1y7xzt4o1_250sq.jpg")
        )
        assertEquals(
            DownloadUtils.normalizeMediaIdentity("$base/tumblr_pxmdpfJRos1y7xzt4o1_1280.jpg"),
            DownloadUtils.normalizeMediaIdentity("$base/tumblr_pxmdpfJRos1y7xzt4o1_75sq.jpg")
        )
    }

    @Test
    fun avatarUrlKeepsDistinctIdentity() {
        val postIdentity = DownloadUtils.normalizeMediaIdentity(postImageVariants.first())
        val avatarIdentity = DownloadUtils.normalizeMediaIdentity(avatarUrl)
        assertTrue(
            "avatar must not share an identity with the post image",
            postIdentity != avatarIdentity
        )
    }

    /**
     * Performance guard for the batch scan: `DownloadStateManager.processAppend`
     * rebuilds its dedup key set from the *whole* queue on every appended feed
     * page, so the same media URLs are normalized over and over on the main
     * thread.
     *
     * Measured on this repo's host JVM for 10 000 distinct URLs: 78 ms with the
     * cleanup `Regex`es compiled inline, 52 ms once they were hoisted to
     * constants — still O(queue) per page. On device (ART) that path costs
     * ~130 µs/item, so an 8 000-item queue burned ~1 s of main thread per page:
     * the command loop never yielded, the UI and the history writer starved, and
     * a large blog scan ended in an ANR.
     *
     * The memo in `DownloadUtils.normalizeMediaIdentity` makes repeat passes
     * (what the queue actually does) hash lookups instead. The assertion is a
     * *ratio* against the cold pass so it stays valid on any machine speed; it
     * also compares both result sets so a cache that returns wrong identities
     * cannot pass.
     */
    @Test
    fun repeatedNormalizationOfTheSameQueueIsMuchCheaperThanTheFirstPass() {
        val queue = (1..10_000).map { i ->
            "https://64.media.tumblr.com/hash$i/tumblr_img${i}_540x810.jpg"
        }
        // Disjoint from `queue`: warms the JIT without warming the memo for the
        // URLs we are about to measure. Without this the second pass is ~3x
        // faster purely from code-cache warm-up (measured 94 ms → 33 ms with no
        // memo at all), which would hide a broken cache behind a green test.
        val warmup = (1..10_000).map { i ->
            "https://64.media.tumblr.com/warmup$i/img${i}.jpg"
        }
        DownloadUtils.clearIdentityCache()
        warmup.forEach { DownloadUtils.normalizeMediaIdentity(it) }

        val coldStart = System.nanoTime()
        val coldResults = queue.map { DownloadUtils.normalizeMediaIdentity(it) }
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0

        val warmStart = System.nanoTime()
        val warmResults = queue.map { DownloadUtils.normalizeMediaIdentity(it) }
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000.0

        assertEquals(
            "cache must never change an identity",
            coldResults,
            warmResults
        )
        println("MEASURED cold=%.1fms warm=%.1fms".format(coldMs, warmMs))
        assertTrue(
            ("cold pass %.0f ms vs warm pass %.0f ms: repeat normalization of the same " +
                "queue must be at least 2x cheaper, otherwise every appended feed page " +
                "re-parses the whole queue on the main thread").format(coldMs, warmMs),
            warmMs * 2 < coldMs
        )
    }
}
