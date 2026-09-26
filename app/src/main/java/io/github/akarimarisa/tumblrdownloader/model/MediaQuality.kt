package io.github.akarimarisa.tumblrdownloader.model

import android.content.Context
import androidx.annotation.StringRes
import io.github.akarimarisa.tumblrdownloader.R
import java.util.Locale

/**
 * Download quality tier for images.
 *
 * Each tier defines a resolution range [minEdge, maxEdge) (based on the
 * longer edge in pixels):
 * - ORIGINAL original: unlimited, uses the best version the post provides (historical default)
 * - HIGH high: longer edge >=1280px
 * - STANDARD standard: longer edge 500~1280px
 * - LOW low: longer edge <500px
 *
 * Selection rules (see [MediaQualitySelector.selectBestUrl]):
 * prefer the largest version inside the tier range; if none matches, take
 * the largest version below the range's lower bound; if still none, take
 * the smallest version above the range's upper bound.
 */
enum class ImageQualityTier(
    val prefValue: String,
    @StringRes val labelRes: Int,
    /** Range lower bound (inclusive); null = no lower bound (0). */
    val minEdge: Int?,
    /** Range upper bound (exclusive); null = no upper bound. */
    val maxEdge: Int?
) {
    ORIGINAL("original", R.string.image_quality_original, null, null),
    HIGH("high", R.string.image_quality_high, 1280, null),
    STANDARD("standard", R.string.image_quality_standard, 500, 1280),
    LOW("low", R.string.image_quality_low, null, 500);

    val isUnlimited: Boolean get() = minEdge == null && maxEdge == null

    companion object {
        fun fromPrefValue(value: String?): ImageQualityTier =
            entries.firstOrNull { it.prefValue == value } ?: ORIGINAL
    }
}

/**
 * Download quality tier for videos.
 *
 * Each tier defines a resolution range [minHeight, maxHeight) (based on
 * height in pixels):
 * - BEST best: unlimited, uses the best version the post provides (historical default)
 * - P1080 1080p: height >=1080px
 * - P720 720p: height 720~1080px
 * - P480 480p: height <720px
 */
enum class VideoQualityTier(
    val prefValue: String,
    @StringRes val labelRes: Int,
    /** Range lower bound (inclusive); null = no lower bound (0). */
    val minHeight: Int?,
    /** Range upper bound (exclusive); null = no upper bound. */
    val maxHeight: Int?
) {
    BEST("best", R.string.video_quality_best, null, null),
    P1080("p1080", R.string.video_quality_1080p, 1080, null),
    P720("p720", R.string.video_quality_720p, 720, 1080),
    P480("p480", R.string.video_quality_480p, null, 720);

    val isUnlimited: Boolean get() = minHeight == null && maxHeight == null

    companion object {
        fun fromPrefValue(value: String?): VideoQualityTier =
            entries.firstOrNull { it.prefValue == value } ?: BEST
    }
}

/** The currently active quality tier combination. */
data class MediaQualitySettings(
    val imageTier: ImageQualityTier = ImageQualityTier.ORIGINAL,
    val videoTier: VideoQualityTier = VideoQualityTier.BEST
)

/** SharedPreferences access point (shares PREFS_NAME with DownloadService). */
object MediaQualityPrefs {

    const val PREFS_NAME = "tumblr_downloader"
    const val PREF_IMAGE_QUALITY = "image_quality_tier"
    const val PREF_VIDEO_QUALITY = "video_quality_tier"

    fun read(context: Context): MediaQualitySettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return MediaQualitySettings(
            imageTier = ImageQualityTier.fromPrefValue(
                prefs.getString(PREF_IMAGE_QUALITY, null)
            ),
            videoTier = VideoQualityTier.fromPrefValue(
                prefs.getString(PREF_VIDEO_QUALITY, null)
            )
        )
    }

    fun setImageTier(context: Context, tier: ImageQualityTier) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_IMAGE_QUALITY, tier.prefValue).apply()
    }

    fun setVideoTier(context: Context, tier: VideoQualityTier) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_VIDEO_QUALITY, tier.prefValue).apply()
    }
}

/**
 * Pure logic: picks the target version from the multiple resolution variants
 * of the same media, according to the tier range [lo, hi).
 *
 * Selection rules:
 * 1. a variant inside the range [lo, hi) -> take the largest (best quality in range);
 * 2. no in-range variant -> take the largest one below lo (closest to the lower bound);
 * 3. still none -> take the smallest one above hi (closest to the upper bound);
 * 4. none of the variant sizes can be parsed -> return null, caller falls back
 *    to the original selection logic.
 */
object MediaQualitySelector {

    private val pathSizeRegex = Regex("/s(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private val suffixWxHRegex = Regex("_(\\d{2,4})x(\\d{2,4})(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE)
    private val suffixNumRegex = Regex("_(\\d{3,4})(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE)
    // Square crop variants: "tumblr_x_250sq.jpg" is a 250x250 square crop of
    // the same image as "tumblr_x_250.jpg".  Parse it as its edge length so it
    // ranks together with the matching resolution instead of being treated as
    // an unparseable odd-one-out (issue #24).
    private val suffixNumSqRegex = Regex("_(\\d{2,4})sq(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE)

    /**
     * Parses the resolution size from a media URL.
     * For images returns the longer edge max(w, h); for videos returns height h.
     * Supports common Tumblr formats:
     * - `/s1280x1920/` path segment
     * - `_1280x1920.jpg` size suffix
     * - `_720.mp4` / `_480.mp4` video height suffixes
     * - `_250sq.jpg` / `_75sq.jpg` square-crop suffixes (parsed as the edge length)
     * Returns null when parsing fails.
     */
    fun parseSize(url: String, type: MediaType): Int? {
        val lower = url.lowercase(Locale.ROOT)

        pathSizeRegex.find(lower)?.let { m ->
            val w = m.groupValues[1].toIntOrNull()
            val h = m.groupValues[2].toIntOrNull()
            if (w != null && h != null) {
                return if (type == MediaType.VIDEO) h else maxOf(w, h)
            }
        }

        suffixWxHRegex.find(lower)?.let { m ->
            val w = m.groupValues[1].toIntOrNull()
            val h = m.groupValues[2].toIntOrNull()
            if (w != null && h != null) {
                return if (type == MediaType.VIDEO) h else maxOf(w, h)
            }
        }

        suffixNumRegex.find(lower)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }

        suffixNumSqRegex.find(lower)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }

        return null
    }

    /**
     * Picks the target version from the candidates of the same media using the
     * tier range [lo, hi).
     * Returns the selected URL; returns null when no size can be parsed.
     */
    fun selectBestUrl(
        candidates: List<String>,
        type: MediaType,
        lo: Int?,
        hi: Int?
    ): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()

        val known = candidates.mapNotNull { url ->
            parseSize(url, type)?.let { size -> url to size }
        }
        if (known.isEmpty()) return null

        val lower = lo ?: Int.MIN_VALUE
        val upper = hi ?: Int.MAX_VALUE

        // 1) best quality inside the range
        val inRange = known.filter { (_, size) -> size in lower until upper }
        if (inRange.isNotEmpty()) {
            return inRange.maxWithOrNull(
                compareBy<Pair<String, Int>> { it.second }
                    .thenBy { if (isSquareRendition(it.first)) 0 else 1 }
            )!!.first
        }

        // 2) largest version below the lower bound
        val below = known.filter { (_, size) -> size < lower }
        if (below.isNotEmpty()) {
            return below.maxWithOrNull(
                compareBy<Pair<String, Int>> { it.second }
                    .thenBy { if (isSquareRendition(it.first)) 0 else 1 }
            )!!.first
        }

        // 3) smallest version above the upper bound (closest to the tier)
        return known.minWithOrNull(
            compareBy<Pair<String, Int>> { it.second }
                .thenBy { if (isSquareRendition(it.first)) 0 else 1 }
        )!!.first
    }

    private fun isSquareRendition(url: String): Boolean =
        Regex("_\\d{2,4}sq(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
}
