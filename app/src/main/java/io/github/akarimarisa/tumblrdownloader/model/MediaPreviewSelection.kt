package io.github.akarimarisa.tumblrdownloader.model

import io.github.akarimarisa.tumblrdownloader.utils.ParsedTumblrMedia
import java.util.Locale

/** One outgoing parse result waiting for the user to pick what to download. */
data class MediaPreviewRequest(
    val id: String,
    val items: List<ParsedTumblrMedia>
)

/** The media kinds the picker can select in one tap. */
enum class MediaPreviewKind {
    IMAGES,
    VIDEOS,
    GIFS
}

/**
 * Pure selection state for the pre-download media picker.
 *
 * Kept free of Android/View types so the select-all / deselect-all / per-kind
 * rules can be unit-tested without Robolectric. Every media item belongs to
 * exactly one [MediaPreviewKind]: GIFs are detected by file extension (they
 * are parsed as [MediaType.IMAGE]), everything else falls back to IMAGES.
 */
data class MediaPreviewSelection(
    val items: List<ParsedTumblrMedia>,
    val selected: Set<Int> = items.indices.toSet()
) {

    val selectedCount: Int get() = selected.size
    val allSelected: Boolean get() = selected.size == items.size
    val noneSelected: Boolean get() = selected.isEmpty()

    fun isSelected(index: Int): Boolean = index in selected

    fun setSelected(index: Int, isSelected: Boolean): MediaPreviewSelection {
        if (index !in items.indices) return this
        val next = selected.toMutableSet()
        if (isSelected) next.add(index) else next.remove(index)
        return copy(selected = next)
    }

    fun toggle(index: Int): MediaPreviewSelection = setSelected(index, index !in selected)

    fun selectAll(): MediaPreviewSelection = copy(selected = items.indices.toSet())

    fun selectNone(): MediaPreviewSelection = copy(selected = emptySet())

    /**
     * Select every item of [kind]; when all of them are already selected,
     * deselect that kind instead. This makes one chip work as both
     * "select all videos" and "clear videos".
     */
    fun toggleKind(kind: MediaPreviewKind): MediaPreviewSelection {
        val matching = items.indices.filter { kindOf(items[it]) == kind }
        if (matching.isEmpty()) return this
        val next = selected.toMutableSet()
        if (matching.all { it in next }) {
            next.removeAll(matching.toSet())
        } else {
            next.addAll(matching)
        }
        return copy(selected = next)
    }

    fun countOf(kind: MediaPreviewKind): Int =
        items.indices.count { kindOf(items[it]) == kind }

    companion object {
        fun kindOf(item: ParsedTumblrMedia): MediaPreviewKind = when {
            item.type == MediaType.VIDEO -> MediaPreviewKind.VIDEOS
            isGif(item.mediaUrl) -> MediaPreviewKind.GIFS
            else -> MediaPreviewKind.IMAGES
        }

        private fun isGif(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            return path.endsWith(".gif") || path.endsWith(".gifv")
        }
    }
}
