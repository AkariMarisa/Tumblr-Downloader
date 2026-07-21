package io.github.akarimarisa.tumblrdownloader.ui.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivityTumblrLoginBinding
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.TumblrAccountStore
import io.github.akarimarisa.tumblrdownloader.utils.TumblrCookieStore
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

private const val TAG = "TumblrLoginActivity"

class TumblrLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTumblrLoginBinding

    private val loginTargetUrl: String by lazy {
        intent.getStringExtra(EXTRA_LOGIN_URL) ?: DEFAULT_LOGIN_URL
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTumblrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = settings.userAgentString + " TumblrDownloader"

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, false)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!request.isForMainFrame) {
                    val url = request.url.toString().lowercase(Locale.ROOT)
                    val host = request.url.host?.lowercase(Locale.ROOT) ?: return null
                    
                    // Block by domain
                    if (BLOCKED_TRACKER_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                        Log.d(TAG, "Blocked tracker domain: ${request.url}")
                        return WebResourceResponse(
                            "text/plain", "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    
                    // Block Tumblr first-party tracking paths
                    if (host.endsWith(".tumblr.com") || host == "www.tumblr.com") {
                        if (BLOCKED_Tumblr_PATHS.any { url.contains(it) }) {
                            Log.d(TAG, "Blocked tracker path: ${request.url}")
                            return WebResourceResponse(
                                "text/plain", "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Inject JS to remove any tracker elements that loaded before
                // shouldInterceptRequest could catch them (e.g. inline scripts).
                view?.evaluateJavascript(TRACKER_CLEANUP_JS, null)

                if (isLikelyLoggedIn(url)) {
                    Log.i(TAG, "Detected Tumblr logged-in state from URL: $url")
                    view?.evaluateJavascript(
                        "(function() { try { return window.__INITIAL_STATE__; } catch(e) { return null; } })()"
                    ) { json ->
                        if (json.isNullOrBlank() || json == "null") {
                            fallbackLoginDone()
                            return@evaluateJavascript
                        }
                        val name = parseUsername(json)
                        if (name != null) {
                            finishWithAccount(name)
                        } else {
                            fallbackLoginDone()
                        }
                    }
                }
            }
        }

        binding.btnDone.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        // Cookie check is now async (Room-backed).
        lifecycleScope.launch {
            val hasPersisted = TumblrCookieStore.hasSavedCookies(this@TumblrLoginActivity)
            if (hasPersisted && TumblrCookieStore.shouldShowSecurityNotice(this@TumblrLoginActivity)) {
                showCookieSecurityReminder()
            }
        }

        val initialUrl = if (loginTargetUrl.isBlank()) {
            DEFAULT_LOGIN_URL
        } else {
            buildLoginUrl(loginTargetUrl)
        }
        binding.webView.loadUrl(initialUrl)
    }

    private fun finishWithAccount(username: String) {
        lifecycleScope.launch {
            TumblrCookieStore.saveFromWebView(this@TumblrLoginActivity)
            TumblrCookieStore.markSecurityNoticeShown(this@TumblrLoginActivity)
        }

        val account = io.github.akarimarisa.tumblrdownloader.utils.TumblrAccount(
            username = username,
            avatarUrl = "https://api.tumblr.com/v2/blog/${username}/avatar/512",
            status = getString(R.string.status_online),
            isLoggedIn = true
        )
        TumblrAccountStore.save(this, account)

        val data = Intent().putExtra(EXTRA_USERNAME, username)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun parseUsername(json: String): String? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        root.optString("name", "").takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONArray("blogs")?.optJSONObject(0)?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("account")?.optString("username", "")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("user")?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun fallbackLoginDone() {
        // Couldn't extract username via JS, fall back to old flow
        showLoginSavedNotice()
    }

    private fun buildLoginUrl(targetUrl: String): String {
        return "https://www.tumblr.com/login?redirect_to=${android.net.Uri.encode(targetUrl)}"
    }

    private fun showLoginSavedNotice() {
        lifecycleScope.launch {
            val saved = TumblrCookieStore.saveFromWebView(this@TumblrLoginActivity)
            val title = getString(R.string.cookie_security_title)
            val message = if (saved) {
                getString(R.string.login_cookie_saved) + "\n" + getString(R.string.cookie_security_message)
            } else {
                getString(R.string.login_cookie_not_found)
            }

            AlertDialog.Builder(this@TumblrLoginActivity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (saved) {
                        lifecycleScope.launch {
                            TumblrCookieStore.markSecurityNoticeShown(this@TumblrLoginActivity)
                        }
                    }
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                .setOnCancelListener {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                .show()
        }
    }

    private fun showCookieSecurityReminder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cookie_security_title)
            .setMessage(R.string.cookie_security_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    TumblrCookieStore.markSecurityNoticeShown(this@TumblrLoginActivity)
                }
            }
            .show()
    }

    private fun isLikelyLoggedIn(url: String?): Boolean {
        val lower = (url ?: "").lowercase(Locale.getDefault())
        return lower.contains("/dashboard") ||
            lower.endsWith("https://www.tumblr.com/") ||
            lower.contains("/settings") ||
            lower.contains("/blog/")
    }

    companion object {
        private const val EXTRA_LOGIN_URL = "extra_login_url"
        const val EXTRA_USERNAME = "extra_username"
        private const val DEFAULT_LOGIN_URL = "https://www.tumblr.com/login"

        /**
         * Known third-party and first-party tracking domains to block in WebView.
         * Sourced from Ghostery report and EasyPrivacy filter list.
         */
        internal val BLOCKED_TRACKER_DOMAINS: Set<String> = setOf(
            // Third-party analytics / tracking
            "www.google-analytics.com",
            "www.googletagmanager.com",
            "www.googletagservices.com",
            "pagead2.googlesyndication.com",
            "adservice.google.com",
            "connect.facebook.net",
            "www.facebook.com",
            "bat.bing.com",
            "snap.licdn.com",
            "analytics.twitter.com",
            "static.ads-twitter.com",
            "scorecardresearch.com",
            "sb.scorecardresearch.com",
            "doubleclick.net",
            "www.doubleclick.net",
            "static.chartbeat.com",
            "cdn.chartbeat.com",
            "tags.tiqcdn.com",
            // Sentry error tracking (flagged by EasyPrivacy)
            "sentry-cdn.com",
            "browser.sentry-cdn.com",
            // Tumblr first-party tracking (flagged by EasyPrivacy)
            "px.srvcs.tumblr.com",
        )

        /**
         * Tumblr first-party tracking paths to block.
         * These are on tumblr.com domains but serve tracking purposes.
         */
        internal val BLOCKED_Tumblr_PATHS = setOf(
            "/pop/js/modern/sentry-",  // Sentry error tracking
            "/services/bblog",          // Blog tracking
            "/impixu",                  // Tracking pixel
        )

        /**
         * JavaScript injected after page load to remove tracker DOM elements
         * that may have been injected by inline scripts before
         * [shouldInterceptRequest] could intercept them.
         */
        internal val TRACKER_CLEANUP_JS = """
            (function() {
                try {
                    var selectors = [
                        'img[src*="srvcs.tumblr.com"]',
                        'img[src*="pixel"]',
                        'img[src*="sentry"]',
                        'iframe[src*="doubleclick"]',
                        'iframe[src*="facebook"]',
                        'iframe[src*="sentry"]',
                        'script[src*="google-analytics"]',
                        'script[src*="googletagmanager"]',
                        'script[src*="scorecardresearch"]',
                        'script[src*="bat.bing"]',
                        'script[src*="tiqcdn"]',
                        'script[src*="sentry-cdn"]',
                        'script[src*="/services/bblog"]'
                    ];
                    selectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            el.parentNode && el.parentNode.removeChild(el);
                        });
                    });
                } catch(e) {}
            })();
        """.trimIndent()

        fun newIntent(context: Context, shareUrl: String): Intent {
            return Intent(context, TumblrLoginActivity::class.java).apply {
                putExtra(EXTRA_LOGIN_URL, shareUrl)
            }
        }
    }
}
