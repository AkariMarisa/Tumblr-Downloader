package io.github.akarimarisa.tumblrdownloader.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-signal clipboard decision (#1 re-copy must be detected, #20 a
 * stale clip must not re-fire).
 *
 * Run with: `./gradlew testDebugUnitTest --tests "*ClipboardDedupeTest*"`
 */
class ClipboardDedupeTest {

    private val url = "https://www.tumblr.com/ginkobrief"
    private val description = "ClipDescription { text/plain hasLabel(3) <11-14 13:20:38.774> }"
    private val newDescription = "ClipDescription { text/plain hasLabel(3) <11-14 13:24:28.321> }"

    private fun shouldProcess(
        signal: Boolean = false,
        text: String = url,
        lastUrl: String? = url,
        lastDescription: String? = description,
        currentDescription: String? = description
    ) = ClipboardDedupe.shouldProcess(signal, text, lastUrl, lastDescription, currentDescription)

    @Test
    fun firstTimeIsProcessed() {
        assertTrue(shouldProcess(lastUrl = null, lastDescription = null))
    }

    @Test
    fun untouchedClipIsSkipped() {
        assertFalse("returning with an unchanged clip must stay quiet", shouldProcess())
    }

    @Test
    fun reCopiedSameLinkIsProcessed() {
        assertTrue(
            "the per-copy timestamp changed even though the text is identical (#1)",
            shouldProcess(currentDescription = newDescription)
        )
    }

    @Test
    fun listenerSignalWins() {
        assertTrue(shouldProcess(signal = true, currentDescription = description))
    }

    @Test
    fun differentLinkIsProcessed() {
        assertTrue(shouldProcess(text = "https://www.tumblr.com/kthingz/1"))
    }

    @Test
    fun descriptionlessClipFallsBackToTheUrl() {
        assertFalse(shouldProcess(currentDescription = null))
        assertTrue(shouldProcess(text = "https://www.tumblr.com/kthingz/1", currentDescription = null))
    }
}
