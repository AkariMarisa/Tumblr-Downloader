package com.example.tumblrdownloader.ui

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
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.ActivityMainBinding
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.ui.auth.TumblrLoginActivity
import com.example.tumblrdownloader.ui.about.AboutActivity
import com.example.tumblrdownloader.ui.settings.SettingsActivity
import com.example.tumblrdownloader.utils.LocaleHelper
import com.example.tumblrdownloader.utils.TumblrParser
import com.google.android.material.tabs.TabLayoutMediator
import com.example.tumblrdownloader.ui.download.DownloadFragment
import com.example.tumblrdownloader.ui.download.DownloadsFragment
import kotlinx.coroutines.launch
import androidx.viewpager2.widget.ViewPager2
import android.widget.Toast
import android.util.Log

private const val TAG = "MainActivity"
private const val PREFS_NAME = "tumblr_downloader"
private const val PREF_CLIPBOARD_AUTO_DETECT = "clipboard_auto_detect"
private const val PREF_LAST_AUTO_URL = "last_auto_detected_url"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    companion object {
        /**
         * Process-level flag: true until the first [onWindowFocusChanged]
         * call with focus.  Survives Activity recreation (config changes,
         * memory pressure after Settings) so that clipboard auto-detect
         * isn't skipped on return-from-settings.
         */
        @Volatile
        private var isColdStart = true
    }

    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    private lateinit var toggle: ActionBarDrawerToggle
    private var clearAllMenu: MenuItem? = null
    private var pauseAllMenu: MenuItem? = null
    private var resumeAllMenu: MenuItem? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            val data = it.data
            val username = data?.getStringExtra(com.example.tumblrdownloader.ui.auth.TumblrLoginActivity.EXTRA_USERNAME)
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
        handleIncomingIntent(intent)
    }

    // Note: Clipboard is detected via onWindowFocusChanged, not
    // OnPrimaryClipChangedListener. Android 10+ requires window focus
    // to read the clipboard, so OnPrimaryClipChangedListener is unreliable
    // (it fires before we can read). Focus-based detection covers all cases.

    override fun onResume() {
        super.onResume()
        viewModel.refreshTumblrAccount()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Cold start: skip clipboard check so the app doesn't re-process
            // stale links on every fresh launch. Only check when user switches
            // back from another app (already-running process).
            if (isColdStart) {
                isColdStart = false
                Log.d(TAG, "Cold start — skipping clipboard check")
                return
            }
            // onWindowFocusChanged(true) is the most reliable indicator that the
            // window has input focus, which is required by Android 10+ for
            // ClipboardManager.getPrimaryClip() to succeed (otherwise it throws
            // SecurityException).
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    handleClipboardAutoDownload()
                }
            }, 200)
        }
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
                Toast.makeText(this, R.string.cookies_cleared_toast, Toast.LENGTH_SHORT).show()
            }
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

        // Skip if this URL is already fully downloaded (same guard as clipboard auto-detect).
        if (viewModel.isUrlFullyCompleted(text)) {
            Log.d(TAG, "Incoming share URL already fully downloaded, skipping: ${text.take(60)}")
            return
        }

        if (viewModel.enqueueFromUrl(text)) {
            viewModel.notifyFromClipboard(text)
            binding.viewPager.currentItem = 0
        }
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

        if (!TumblrParser.isTumblrShareUrl(clipText)) {
            Log.d(TAG, "Clipboard not a Tumblr URL: ${clipText.take(60)}")
            return
        }

        // Persistent dedup — skip silently if this URL was already auto-detected.
        val lastUrl = prefs.getString(PREF_LAST_AUTO_URL, null)
        if (clipText == lastUrl) {
            Log.d(TAG, "Clipboard URL already auto-detected before: ${clipText.take(60)}")
            return
        }

        // Skip if all items for this source URL are already fully downloaded.
        // This prevents spurious loading when returning from the media viewer
        // with a previously-completed Tumblr URL still in the clipboard.
        if (viewModel.isUrlFullyCompleted(clipText)) {
            Log.d(TAG, "Clipboard URL already fully downloaded, skipping: ${clipText.take(60)}")
            return
        }

        Log.d(TAG, "Clipboard auto-detect: ${clipText.take(80)}")
        if (viewModel.enqueueFromUrl(clipText)) {
            prefs.edit().putString(PREF_LAST_AUTO_URL, clipText).apply()
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
