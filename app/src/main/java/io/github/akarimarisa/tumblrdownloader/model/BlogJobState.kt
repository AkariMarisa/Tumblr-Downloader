package io.github.akarimarisa.tumblrdownloader.model

/** Lifecycle state of one blog full-profile download job (persisted as [state]). */
enum class BlogJobState {
    /** Scan is in progress (also covers "interrupted by app restart" — resume button re-launches it). */
    RUNNING,
    /** Feed fully consumed (last page had no `links.next`). */
    DONE,
    /** User cancelled, or the long-blog confirmation was declined. */
    CANCELLED,
    /** Permanent failure (password-protected blog, network outage after retries, …). */
    BLOCKED
}
