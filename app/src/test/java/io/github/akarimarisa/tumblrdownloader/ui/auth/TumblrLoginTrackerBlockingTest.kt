package io.github.akarimarisa.tumblrdownloader.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TumblrLoginActivity] tracker-blocking logic.
 *
 * The companion object's [TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS] and
 * [TumblrLoginActivity.TRACKER_CLEANUP_JS] are exercised here without
 * requiring Android instrumentation.
 */
class TumblrLoginTrackerBlockingTest {

    // ═══════════════════════════════════════════════════════════════════
    //  BLOCKED_TRACKER_DOMAINS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun blockedDomains_containsExpectedThirdPartyTrackers() {
        val domains = TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS

        // Google analytics / tag manager
        assertTrue("google-analytics", domains.contains("www.google-analytics.com"))
        assertTrue("googletagmanager", domains.contains("www.googletagmanager.com"))

        // Facebook
        assertTrue("facebook connect", domains.contains("connect.facebook.net"))
        assertTrue("facebook", domains.contains("www.facebook.com"))

        // Bing
        assertTrue("bat.bing.com", domains.contains("bat.bing.com"))

        // LinkedIn
        assertTrue("snap.licdn.com", domains.contains("snap.licdn.com"))

        // Twitter
        assertTrue("analytics.twitter.com", domains.contains("analytics.twitter.com"))

        // Comscore
        assertTrue("scorecardresearch", domains.contains("scorecardresearch.com"))

        // DoubleClick
        assertTrue("doubleclick", domains.contains("doubleclick.net"))
    }

    @Test
    fun blockedDomains_containsTumblrFirstPartyTracker() {
        assertTrue(
            "px.srvcs.tumblr.com should be blocked (EasyPrivacy)",
            TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS.contains("px.srvcs.tumblr.com")
        )
    }

    @Test
    fun blockedDomains_noDuplicates() {
        val domains = TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS
        assertEquals(
            "BLOCKED_TRACKER_DOMAINS should have no duplicates",
            domains.size,
            domains.toSet().size
        )
    }

    @Test
    fun blockedDomains_doesNotBlockEssentialTumblrDomains() {
        val blocked = TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS

        // These must NOT be blocked — they are essential for login and media
        assertFalse("www.tumblr.com must not be blocked", blocked.contains("www.tumblr.com"))
        assertFalse("media.tumblr.com must not be blocked", blocked.contains("media.tumblr.com"))
        assertFalse("assets.tumblr.com must not be blocked", blocked.contains("assets.tumblr.com"))
        assertFalse("api.tumblr.com must not be blocked", blocked.contains("api.tumblr.com"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Domain matching logic (mirrors shouldInterceptRequest)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Simulate the domain-matching logic from shouldInterceptRequest.
     */
    private fun isTrackerHost(host: String): Boolean {
        val lower = host.lowercase()
        return TumblrLoginActivity.BLOCKED_TRACKER_DOMAINS.any { lower == it || lower.endsWith(".$it") }
    }

    @Test
    fun domainMatching_exactDomainIsBlocked() {
        assertTrue(isTrackerHost("www.google-analytics.com"))
        assertTrue(isTrackerHost("px.srvcs.tumblr.com"))
        assertTrue(isTrackerHost("connect.facebook.net"))
    }

    @Test
    fun domainMatching_subdomainIsBlocked() {
        assertTrue(isTrackerHost("sb.scorecardresearch.com"))
        assertTrue(isTrackerHost("www.googletagmanager.com"))
        assertTrue(isTrackerHost("pagead2.googlesyndication.com"))
    }

    @Test
    fun domainMatching_tumblrMainDomainPasses() {
        assertFalse(isTrackerHost("www.tumblr.com"))
        assertFalse(isTrackerHost("tumblr.com"))
        assertFalse(isTrackerHost("media.tumblr.com"))
        assertFalse(isTrackerHost("assets.tumblr.com"))
    }

    @Test
    fun domainMatching_randomDomainPasses() {
        assertFalse(isTrackerHost("example.com"))
        assertFalse(isTrackerHost("cdn.example.com"))
        assertFalse(isTrackerHost("fonts.googleapis.com"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TRACKER_CLEANUP_JS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun cleanupJs_isNotEmpty() {
        assertTrue(
            "TRACKER_CLEANUP_JS should not be blank",
            TumblrLoginActivity.TRACKER_CLEANUP_JS.isNotBlank()
        )
    }

    @Test
    fun cleanupJs_isValidJavascript() {
        val js = TumblrLoginActivity.TRACKER_CLEANUP_JS
        // Basic structural check: should be a self-executing function
        assertTrue("should start with (function", js.trimStart().startsWith("(function"))
        assertTrue("should end with })();", js.trimEnd().endsWith("})();"))
    }

    @Test
    fun cleanupJs_targetsTrackerSelectors() {
        val js = TumblrLoginActivity.TRACKER_CLEANUP_JS
        // Should reference known tracker patterns
        assertTrue("should target srvcs.tumblr.com", js.contains("srvcs.tumblr.com"))
        assertTrue("should target doubleclick", js.contains("doubleclick"))
        assertTrue("should target facebook", js.contains("facebook"))
        assertTrue("should target google-analytics", js.contains("google-analytics"))
        assertTrue("should target googletagmanager", js.contains("googletagmanager"))
        assertTrue("should target scorecardresearch", js.contains("scorecardresearch"))
    }
}
