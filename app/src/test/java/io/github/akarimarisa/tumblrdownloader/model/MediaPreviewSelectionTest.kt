package io.github.akarimarisa.tumblrdownloader.model

import io.github.akarimarisa.tumblrdownloader.utils.ParsedTumblrMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure selection rules behind the pre-download media picker. */
class MediaPreviewSelectionTest {

    private val items = listOf(
        media("https://64.media.tumblr.com/a.jpg", MediaType.IMAGE),
        media("https://64.media.tumblr.com/b.png", MediaType.IMAGE),
        media("https://64.media.tumblr.com/c.gif", MediaType.IMAGE),
        media("https://64.media.tumblr.com/d.gifv", MediaType.IMAGE),
        media("https://va.media.tumblr.com/e.mp4", MediaType.VIDEO)
    )

    @Test
    fun startsWithEverythingSelected() {
        val selection = MediaPreviewSelection(items)

        assertEquals(5, selection.selectedCount)
        assertTrue(selection.allSelected)
        assertFalse(selection.noneSelected)
    }

    @Test
    fun toggleAndSetSelectedOnlyTouchOneIndex() {
        val selection = MediaPreviewSelection(items)
            .toggle(1)
            .setSelected(3, false)

        assertFalse(selection.isSelected(1))
        assertFalse(selection.isSelected(3))
        assertTrue(selection.isSelected(0))
        assertEquals(3, selection.selectedCount)
    }

    @Test
    fun selectAllAndSelectNone() {
        val none = MediaPreviewSelection(items).selectNone()
        assertTrue(none.noneSelected)
        assertEquals(0, none.selectedCount)

        val all = none.selectAll()
        assertTrue(all.allSelected)
        assertEquals(items.size, all.selectedCount)
    }

    @Test
    fun gifsAreSplitFromPlainImages() {
        val selection = MediaPreviewSelection(items)

        assertEquals(2, selection.countOf(MediaPreviewKind.IMAGES))
        assertEquals(2, selection.countOf(MediaPreviewKind.GIFS))
        assertEquals(1, selection.countOf(MediaPreviewKind.VIDEOS))
    }

    @Test
    fun toggleKindSelectsThenClearsThatKindOnly() {
        val cleared = MediaPreviewSelection(items).toggleKind(MediaPreviewKind.VIDEOS)

        assertFalse(cleared.isSelected(4))
        assertTrue("other kinds must stay selected", cleared.isSelected(0))
        assertEquals(4, cleared.selectedCount)

        val restored = cleared.toggleKind(MediaPreviewKind.VIDEOS)
        assertTrue(restored.isSelected(4))
        assertTrue(restored.allSelected)
    }

    @Test
    fun toggleKindSelectsKindAfterSelectNone() {
        val selection = MediaPreviewSelection(items)
            .selectNone()
            .toggleKind(MediaPreviewKind.GIFS)

        assertEquals(setOf(2, 3), selection.selected)
    }

    @Test
    fun outOfRangeIndicesAreIgnored() {
        val selection = MediaPreviewSelection(items).setSelected(99, false)
        assertEquals(items.size, selection.selectedCount)
    }

    private fun media(url: String, type: MediaType) = ParsedTumblrMedia(
        sourceUrl = "https://www.tumblr.com/blog/test/${url.hashCode()}",
        mediaUrl = url,
        title = "Media",
        type = type
    )
}
