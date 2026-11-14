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
        // A description we have never seen means the clip was replaced — but
        // only when we also *have* a snapshot: right after an upgrade from a
        // version that predates the snapshot the field is empty, and that
        // must not re-fire the link already recorded in `lastUrl` (#1.12).
        if (clipDescription != null && lastClipDescription != null &&
            clipDescription != lastClipDescription
        ) {
            return true
        }
        // Never handled, or handled from a different source (share vs clipboard).
        return clipText != lastUrl
    }

    /**
     * True when the current clip was produced by copying inside our own URL
     * field (long-press → copy while we hold focus).
     *
     * Such a copy changes the clip description just like an external one, so
     * [shouldProcess] alone would re-trigger detection for a link that is
     * already in the field (#19). The listener records the description of our
     * own copy; when it still matches on the next focus-gained check — and no
     * external change signal arrived — the check adopts it as the new
     * snapshot and stays quiet. A copy made in another app afterwards carries
     * a different description, so it is still processed.
     */
    fun isOwnFieldCopy(
        clipChangedSignal: Boolean,
        selfCopyDescription: String?,
        clipDescription: String?
    ): Boolean = !clipChangedSignal &&
        selfCopyDescription != null &&
        clipDescription != null &&
        clipDescription == selfCopyDescription
}
