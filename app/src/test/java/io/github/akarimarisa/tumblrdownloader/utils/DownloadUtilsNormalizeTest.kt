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
@Config(sdk = [28])
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
}