package io.github.akarimarisa.tumblrdownloader.utils

/**
 * Decides whether the clipboard should be processed when the window regains
 * focus.
 *
 * Content alone cannot tell "the user copied the same link again" from
 * "nothing changed", and Android stops delivering clip-change callbacks to a
 * *backgrounded* app — so three signals are combined:
 *
 *  1. the process-wide listener (fires while we are focused),
 *  2. the clip **description**, which carries a per-copy timestamp on current
 *     Android builds and stays stable while the clip is untouched, and
 *  3. the last URL we handled, as the fallback when neither signal arrives.
 *
 * Re-copying a link in another app must therefore open the picker again (#1),
 * while returning from the media viewer with an untouched clip must stay
 * quiet (#20).
 */
object ClipboardDedupe {

    fun shouldProcess(
        clipChangedSignal: Boolean,
        clipText: String,
        lastUrl: String?,
        lastClipDescription: String?,
        clipDescription: String?
    ): Boolean {
        if (clipChangedSignal) return true
        // A description we have never seen means the clip was replaced.
        if (clipDescription != null && clipDescription != lastClipDescription) return true
        // Never handled, or handled from a different source (share vs clipboard).
        return clipText != lastUrl
    }
}
