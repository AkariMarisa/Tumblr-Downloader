package io.github.akarimarisa.tumblrdownloader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivityMainBinding
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.ui.auth.TumblrLoginActivity
import io.github.akarimarisa.tumblrdownloader.ui.about.AboutActivity
import io.github.akarimarisa.tumblrdownloader.ui.settings.SettingsActivity
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.LocalizedToast
import io.github.akarimarisa.tumblrdownloader.utils.BlogFeedParser
import io.github.akarimarisa.tumblrdownloader.utils.TumblrParser
import com.google.android.material.tabs.TabLayoutMediator
import io.github.akarimarisa.tumblrdownloader.ui.download.DownloadFragment
import io.github.akarimarisa.tumblrdownloader.ui.download.DownloadsFragment
import kotlinx.coroutines.launch
import androidx.viewpager2.widget.ViewPager2
import android.util.Log

private const val TAG = "MainActivity"
private const val PREFS_NAME = "tumblr_downloader"
private const val PREF_CLIPBOARD_AUTO_DETECT = "clipboard_auto_detect"
private const val PREF_LAST_AUTO_URL = "last_auto_detected_url"

/** Delay before reading the clipboard: Android only allows the read once the window actually holds focus. */
private const val CLIPBOARD_CHECK_DELAY_MS = 200L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }

    /**
     * Set by [clipboardChangedListener] and consumed by the next
     * [handleClipboardAutoDownload].
     *
     * Content alone cannot tell "the user copied the same link again" from
     * "nothing changed", so the last-URL dedupe used to swallow re-copies
     * forever — a skipped picker could never be reopened (#3) and a partially
     * downloaded post stopped being detected (#2). The listener is only a
     * *signal* (reading the clip still needs window focus on Android 10+);
     * the last-URL check stays as the fallback if the callback is missed.
     */
    @Volatile
    private var primaryClipChanged = false

    private val clipboardChangedListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener {
            // A copy made while our window is focused comes from our own URL
            // field (long-press → copy): re-parsing it would be the false
            // trigger reported as #2. Copies made in other apps arrive while
            // we are backgrounded — those are the ones worth reacting to (#4).
            if (!hasWindowFocus()) {
                primaryClipChanged = true
            }
        }
    private lateinit var toggle: ActionBarDrawerToggle
    private var clearAllMenu: MenuItem? = null
    private var pauseAllMenu: MenuItem? = null
    private var resumeAllMenu: MenuItem? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            val data = it.data
            val username = data?.getStringExtra(io.github.akarimarisa.tumblrdownloader.ui.auth.TumblrLoginActivity.EXTRA_USERNAME)
            if (!username.isNullOrBlank()) {
                viewModel.setTumblrAccount(username)
            } else {
                viewModel.refreshTumblrAccount()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // First-run onboarding
        if (!OnboardingActivity.isDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = listOf(getString(R.string.tab_download), getString(R.string.tab_downloads))[position]
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val isDownloadsTab = position == 1
                clearAllMenu?.isVisible = isDownloadsTab
                pauseAllMenu?.isVisible = isDownloadsTab
                resumeAllMenu?.isVisible = isDownloadsTab
            }
        })

        setupDrawer()
        collectTumblrAccount()
        collectDownloadsState()
        clipboardManager.addPrimaryClipChangedListener(clipboardChangedListener)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener)
        super.onDestroy()
    }

    // Clipboard detection needs window focus to *read* the clip (Android 10+),
    // so the check runs from onWindowFocusChanged — and from the clip-change
    // listener when the copy happens while we are already focused.

    override fun onResume() {
        super.onResume()
        // Claim the singleton DownloadService progress listener so the
        // visible screen receives download updates regardless of which
        // ViewModel was constructed first. See
        // MainViewModel.claimServiceProgressListener() — fixes issue #24
        // (downloads stuck at "0%" while files complete).
        viewModel.claimServiceProgressListener()
        viewModel.refreshTumblrAccount()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // onWindowFocusChanged(true) is the most reliable indicator that the
            // window has input focus, which is required by Android 10+ for
            // ClipboardManager.getPrimaryClip() to succeed (otherwise it throws
            // SecurityException).
            //
            // A cold start checks too: the last-URL dedupe survives process
            // death, so it still blocks stale links — skipping the first check
            // only made "copy a link, open the app" silently do nothing,
            // including after the process was killed mid-flow (#4).
            scheduleClipboardCheck()
        }
    }

    private fun scheduleClipboardCheck() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                handleClipboardAutoDownload()
            }
        }, CLIPBOARD_CHECK_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setupDrawer() {
        toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        toggle.isDrawerIndicatorEnabled = true
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this::onNavItemSelected)

        binding.navView.getHeaderView(0).setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            // If already logged in, don't re-open the login page
            // (Tumblr shows the dashboard, which is confusing).
            // TODO: navigate to user settings / profile instead.
            if (!viewModel.tumblrAccount.value.isLoggedIn) {
                loginLauncher.launch(TumblrLoginActivity.newIntent(this, ""))
            }
        }
    }

    private fun onNavItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_logout -> {
                viewModel.clearSavedCookies()
                LocalizedToast.show(LocaleHelper.contextForAppLocale(this), R.string.cookies_cleared_toast, android.widget.Toast.LENGTH_SHORT)
            }
            R.id.menu_tutorial -> startActivity(
                OnboardingActivity.newTutorialIntent(this)
            )
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun collectTumblrAccount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tumblrAccount.collect { account ->
                    val header = binding.navView.getHeaderView(0)
                    val avatar = header.findViewById<android.widget.ImageView>(R.id.ivDrawerAvatar)
                    val username = header.findViewById<android.widget.TextView>(R.id.tvDrawerUserName)
                    val status = header.findViewById<android.widget.TextView>(R.id.tvDrawerUserStatus)

                    val loggedIn = account.isLoggedIn

                    username.text = if (loggedIn) {
                        account.username ?: getString(R.string.drawer_user_logged_in)
                    } else {
                        getString(R.string.drawer_not_logged_in)
                    }
                    status.text = if (loggedIn) {
                        getString(R.string.drawer_user_status_logged_in,
                            account.status.orEmpty().ifBlank { getString(R.string.drawer_user_status_online) })
                    } else {
                        getString(R.string.drawer_user_status_logged_out)
                    }

                    val fallback = ContextCompat.getDrawable(this@MainActivity, R.mipmap.ic_launcher)
                    Glide.with(this@MainActivity)
                        .load(account.avatarUrl)
                        .placeholder(fallback)
                        .error(fallback)
                        .circleCrop()
                        .into(avatar)

                    status.isVisible = true

                    // Show logout button only when logged in
                    binding.navView.menu.findItem(R.id.menu_logout)?.isVisible = loggedIn
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        clearAllMenu = menu.findItem(R.id.menu_clear_all)
        pauseAllMenu = menu.findItem(R.id.menu_pause_all)
        resumeAllMenu = menu.findItem(R.id.menu_resume_all)
        val isDownloadsTab = binding.viewPager.currentItem == 1
        clearAllMenu?.isVisible = isDownloadsTab
        pauseAllMenu?.isVisible = isDownloadsTab
        resumeAllMenu?.isVisible = isDownloadsTab
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) return true
        return when (item.itemId) {
            R.id.menu_pause_all -> {
                viewModel.pauseAllDownloads()
                true
            }
            R.id.menu_resume_all -> {
                viewModel.resumeAllDownloads()
                true
            }
            R.id.menu_clear_all -> {
                showClearAllConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearAllConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_downloads)
            .setMessage(R.string.clear_all_confirm_message)
            .setPositiveButton(R.string.clear_all_confirm) { _, _ ->
                // Clear clipboard to prevent re-detection of the same URL
                // when the user switches away and back.
                runCatching {
                    clipboardManager.setPrimaryClip(
                        android.content.ClipData.newPlainText(null, "")
                    )
                }
                viewModel.clearAllDownloads()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun collectDownloadsState() {
        lifecycleScope.launch {
            viewModel.downloads.collect { list ->
                // Update overflow menu item states based on download list.
                val hasPausable = list.any {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                }
                val hasResumable = list.any {
                    it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
                }
                pauseAllMenu?.isEnabled = hasPausable
                resumeAllMenu?.isEnabled = hasResumable
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: return

        // A standalone blog URL kicks off the batch-download flow instead of
        // single-post parsing — fill the Download tab field so the user picks
        // media options there.
        if (BlogFeedParser.isBlogUrl(text)) {
            Log.d(TAG, "Incoming share is a blog URL: ${text.take(60)}")
            binding.viewPager.currentItem = 0
            viewModel.notifyFromClipboard(text)
            viewModel.promptBlogOptions(text)
            // Remember it: a cold start checks the clipboard too, and the
            // clipboard still holds what the user shared (#4).
            rememberHandledClip(text)
            return
        }

        // Skip if this URL is already fully downloaded (same guard as clipboard auto-detect).
        if (viewModel.isUrlFullyCompleted(text)) {
            Log.d(TAG, "Incoming share URL already fully downloaded, skipping: ${text.take(60)}")
            rememberHandledClip(text)
            return
        }

        if (viewModel.enqueueFromUrl(text)) {
            rememberHandledClip(text)
            viewModel.notifyFromClipboard(text)
            binding.viewPager.currentItem = 0
        }
    }

    /** Records a URL the app already acted on so the clipboard check skips it. */
    private fun rememberHandledClip(url: String) {
        prefs.edit().putString(PREF_LAST_AUTO_URL, url).apply()
    }

    private fun handleClipboardAutoDownload() {
        if (!prefs.getBoolean(PREF_CLIPBOARD_AUTO_DETECT, true)) {
            Log.d(TAG, "Clipboard auto-detect disabled via settings")
            return
        }

        val clipText = try {
            getClipboardText()
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard read blocked (no window focus): ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard read failed", e)
            null
        } ?: return

        if (clipText.isBlank()) {
            Log.d(TAG, "Clipboard is empty, skipping")
            return
        }

        val isPostUrl = TumblrParser.isTumblrShareUrl(clipText)
        val isBlogUrl = !isPostUrl && BlogFeedParser.isBlogUrl(clipText)
        if (!isPostUrl && !isBlogUrl) {
            Log.d(TAG, "Clipboard not a Tumblr URL: ${clipText.take(60)}")
            return
        }

        // Dedup: skip when the clipboard is unchanged since the last handled
        // URL. The clip-change signal takes priority — re-copying the same
        // link must be able to open the picker again (#2, #3) — while the
        // last-URL fallback keeps the old behaviour if the callback was missed
        // (e.g. returning from the media viewer with a stale link).
        val clipChanged = primaryClipChanged
        primaryClipChanged = false
        val lastUrl = prefs.getString(PREF_LAST_AUTO_URL, null)
        if (!clipChanged && clipText == lastUrl) {
            Log.d(TAG, "Clipboard unchanged since last handling: ${clipText.take(60)}")
            return
        }

        // Skip if all items for this source URL are already fully downloaded.
        // This prevents spurious loading when returning from the media viewer
        // with a previously-completed Tumblr URL still in the clipboard.
        // Blog URLs are never "fully completed" (firstTumblrUrl has no match).
        if (viewModel.isUrlFullyCompleted(clipText)) {
            Log.d(TAG, "Clipboard URL already fully downloaded, skipping: ${clipText.take(60)}")
            return
        }

        Log.d(TAG, "Clipboard auto-detect: ${clipText.take(80)}")
        if (isBlogUrl) {
            // Blog URLs enter the batch flow: fill the field, switch to the
            // Download tab and let the type dialog decide (it starts the scan
            // directly when "auto download all media" is on).
            rememberHandledClip(clipText)
            binding.viewPager.currentItem = 0
            viewModel.notifyFromClipboard(clipText)
            viewModel.promptBlogOptions(clipText)
            Log.d(TAG, "Clipboard auto-detect: blog URL handed to the batch flow")
        } else if (viewModel.enqueueFromUrl(clipText)) {
            rememberHandledClip(clipText)
            // Switch to Download tab first so the user sees the loading overlay
            binding.viewPager.currentItem = 0
            viewModel.notifyFromClipboard(clipText)
            Log.d(TAG, "Clipboard auto-detect queued successfully")
        } else {
            Log.w(TAG, "Clipboard auto-detect enqueueFromUrl returned false")
        }
    }

    private fun getClipboardText(): String? {
        if (!clipboardManager.hasPrimaryClip()) return null
        return clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    }

    fun showDownloadsTab() {
        binding.viewPager.currentItem = 1
    }

    inner class MainPagerAdapter(activity: MainActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int) = when (position) {
            0 -> DownloadFragment()
            else -> DownloadsFragment()
        }
    }
}
