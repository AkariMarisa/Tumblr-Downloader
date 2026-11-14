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

    @Test
    fun ownFieldCopyIsRecognised() {
        assertTrue(
            ClipboardDedupe.isOwnFieldCopy(
                clipChangedSignal = false,
                selfCopyDescription = description,
                clipDescription = description
            )
        )
    }

    @Test
    fun externalCopyAfterOwnCopyIsNotMistakenForIt() {
        // Another app copied afterwards: its description differs from the one
        // our own copy produced, so the check must fall through to the
        // normal signals (which then process it).
        assertFalse(
            ClipboardDedupe.isOwnFieldCopy(
                clipChangedSignal = false,
                selfCopyDescription = description,
                clipDescription = newDescription
            )
        )
        // …and the description signal picks it up as a real change.
        assertTrue(
            shouldProcess(
                lastDescription = description,
                currentDescription = newDescription
            )
        )
    }

    @Test
    fun listenerSignalBeatsOwnFieldCopy() {
        // An explicit change signal always wins over the own-copy shortcut.
        assertFalse(
            ClipboardDedupe.isOwnFieldCopy(
                clipChangedSignal = true,
                selfCopyDescription = description,
                clipDescription = description
            )
        )
    }

    @Test
    fun upgradeWithoutDescriptionSnapshotFallsBackToTheUrl() {
        // v1.0.7 predates the snapshot: `lastClipDescription` is null while the
        // clip still holds the link it already handled — must stay quiet.
        assertFalse(shouldProcess(lastDescription = null))
        // …but a genuinely different link is still picked up after the upgrade.
        assertTrue(shouldProcess(text = "https://www.tumblr.com/kthingz/1", lastDescription = null))
    }
}
