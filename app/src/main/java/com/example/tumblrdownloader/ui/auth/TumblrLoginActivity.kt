package com.example.tumblrdownloader.ui.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.tumblrdownloader.databinding.ActivityTumblrLoginBinding
import java.util.Locale
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.utils.TumblrCookieStore

private const val TAG = "TumblrLoginActivity"

class TumblrLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTumblrLoginBinding

    private val loginTargetUrl: String by lazy {
        intent.getStringExtra(EXTRA_LOGIN_URL) ?: DEFAULT_LOGIN_URL
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
            CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (isLikelyLoggedIn(url)) {
                    Log.i(TAG, "Detected Tumblr logged-in state from URL: $url")
                    showLoginSavedNotice()
                }
            }
        }

        binding.btnDone.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        val hasPersisted = TumblrCookieStore.hasSavedCookies(this)
        if (hasPersisted && TumblrCookieStore.shouldShowSecurityNotice(this)) {
            showCookieSecurityReminder()
        }

        val initialUrl = if (loginTargetUrl.isBlank()) {
            DEFAULT_LOGIN_URL
        } else {
            buildLoginUrl(loginTargetUrl)
        }
        binding.webView.loadUrl(initialUrl)
    }

    private fun buildLoginUrl(targetUrl: String): String {
        return "https://www.tumblr.com/login?redirect_to=${android.net.Uri.encode(targetUrl)}"
    }

    private fun showLoginSavedNotice() {
        val saved = TumblrCookieStore.saveFromWebView(this)
        val title = getString(R.string.cookie_security_title)
        val message = if (saved) {
            getString(R.string.login_cookie_saved) + "\n" + getString(R.string.cookie_security_message)
        } else {
            getString(R.string.login_cookie_not_found)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (saved) {
                    TumblrCookieStore.markSecurityNoticeShown(this)
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

    private fun showCookieSecurityReminder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cookie_security_title)
            .setMessage(R.string.cookie_security_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TumblrCookieStore.markSecurityNoticeShown(this)
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
        private const val DEFAULT_LOGIN_URL = "https://www.tumblr.com/login"

        fun newIntent(context: Context, shareUrl: String): Intent {
            return Intent(context, TumblrLoginActivity::class.java).apply {
                putExtra(EXTRA_LOGIN_URL, shareUrl)
            }
        }
    }
}
