package io.github.akarimarisa.tumblrdownloader.model

import android.content.Context
import androidx.annotation.StringRes
import io.github.akarimarisa.tumblrdownloader.R
import java.util.Locale

/**
 * 下载清晰度档位（图片）。
 *
 * 每个档位定义一个分辨率区间 [minEdge, maxEdge)（图片按长边像素，单位 px）：
 * - ORIGINAL 原图：不限制，取帖子提供的最佳版本（历史默认行为）
 * - HIGH 高清：长边 ≥1280px
 * - STANDARD 标清：长边 500~1280px
 * - LOW 低清：长边 <500px
 *
 * 选择规则（见 [MediaQualitySelector.selectBestUrl]）：
 * 优先在档位区间内选择尺寸最大的版本；没有匹配的版本时，
 * 取低于区间下界的最大版本；仍没有则取高于区间上界的最小版本。
 */
enum class ImageQualityTier(
    val prefValue: String,
    @StringRes val labelRes: Int,
    /** 区间下界（含），null = 无下界（0）。 */
    val minEdge: Int?,
    /** 区间上界（不含），null = 无上界。 */
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
 * 下载清晰度档位（视频）。
 *
 * 每个档位定义一个分辨率区间 [minHeight, maxHeight)（视频按高度像素）：
 * - BEST 最高：不限制，取帖子提供的最佳版本（历史默认行为）
 * - P1080 1080p：高度 ≥1080px
 * - P720 720p：高度 720~1080px
 * - P480 480p：高度 <720px
 */
enum class VideoQualityTier(
    val prefValue: String,
    @StringRes val labelRes: Int,
    /** 区间下界（含），null = 无下界（0）。 */
    val minHeight: Int?,
    /** 区间上界（不含），null = 无上界。 */
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

/** 当前生效的清晰度档位组合。 */
data class MediaQualitySettings(
    val imageTier: ImageQualityTier = ImageQualityTier.ORIGINAL,
    val videoTier: VideoQualityTier = VideoQualityTier.BEST
)

/** SharedPreferences 读写入口（与 DownloadService 共用 PREFS_NAME）。 */
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
 * 纯逻辑：从同一媒体的多个分辨率版本中，按档位区间选出目标版本。
 *
 * 选择规则：
 * 1. 区间 [lo, hi) 内的版本 → 取尺寸最大的（同区间内质量最好）；
 * 2. 没有区间内版本 → 取低于 lo 的最大版本（最接近档位下界）；
 * 3. 仍没有 → 取高于 hi 的最小版本（最接近档位上界）；
 * 4. 所有版本尺寸都无法解析 → 返回 null，由调用方回退原有选择逻辑。
 */
object MediaQualitySelector {

    private val pathSizeRegex = Regex("/s(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private val suffixWxHRegex = Regex("_(\\d{2,4})x(\\d{2,4})(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE)
    private val suffixNumRegex = Regex("_(\\d{3,4})(?=\\.[a-z0-9]+$)", RegexOption.IGNORE_CASE)

    /**
     * 解析媒体 URL 的清晰度尺寸。
     * 图片返回长边 max(w, h)；视频返回高度 h。
     * 支持 Tumblr 常见格式：
     * - `/s1280x1920/` 路径段
     * - `_1280x1920.jpg` 尺寸后缀
     * - `_720.mp4` / `_480.mp4` 视频高度后缀
     * 解析失败返回 null。
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

        return null
    }

    /**
     * 从同一媒体的候选 URL 中按档位区间 [lo, hi) 选出目标版本。
     * 返回选中的 URL；全部无法解析尺寸时返回 null。
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

        // 1) 区间内质量最好
        val inRange = known.filter { (_, size) -> size in lower until upper }
        if (inRange.isNotEmpty()) {
            return inRange.maxByOrNull { it.second }!!.first
        }

        // 2) 低于下界的最大版本
        val below = known.filter { (_, size) -> size < lower }
        if (below.isNotEmpty()) {
            return below.maxByOrNull { it.second }!!.first
        }

        // 3) 高于上界的最小版本（最接近档位）
        return known.minByOrNull { it.second }!!.first
    }
}
