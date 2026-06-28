package com.example.tumblrdownloader.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.example.tumblrdownloader.ui.auth.TumblrLoginActivity
import com.example.tumblrdownloader.ui.about.AboutActivity
import com.example.tumblrdownloader.ui.settings.SettingsActivity
import com.example.tumblrdownloader.utils.TumblrParser
import com.google.android.material.tabs.TabLayoutMediator
import com.example.tumblrdownloader.ui.download.DownloadFragment
import com.example.tumblrdownloader.ui.download.DownloadsFragment
import kotlinx.coroutines.launch
import androidx.viewpager2.widget.ViewPager2
import android.util.Log

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var lastAutoClipboardUrl: String? = null
    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    private lateinit var toggle: ActionBarDrawerToggle
    private var clearAllMenu: MenuItem? = null

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

    private val clipboardCallback = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardAutoDownload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = listOf("Download", "Downloads")[position]
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                clearAllMenu?.isVisible = (position == 1)
            }
        })

        setupDrawer()
        collectTumblrAccount()
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        clipboardManager.addPrimaryClipChangedListener(clipboardCallback)
    }

    override fun onStop() {
        clipboardManager.removePrimaryClipChangedListener(clipboardCallback)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from Settings (separate ViewModel instance there)
        viewModel.refreshTumblrAccount()
        // Check clipboard when activity is fully in foreground (onResume).
        // onStart may be too early for getPrimaryClip() on some Android versions.
        handleClipboardAutoDownload()
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
            loginLauncher.launch(TumblrLoginActivity.newIntent(this, ""))
        }
    }

    private fun onNavItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
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

                    username.text = if (account.isLoggedIn) {
                        account.username ?: getString(R.string.drawer_user_logged_in)
                    } else {
                        getString(R.string.drawer_not_logged_in)
                    }
                    status.text = if (account.isLoggedIn) {
                        getString(R.string.drawer_user_status_logged_in,
                            account.status.orEmpty().ifBlank { getString(R.string.drawer_user_status_online) })
                    } else {
                        getString(R.string.drawer_user_status_logged_out)
                    }

                    val fallback = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.sym_def_app_icon)
                    Glide.with(this@MainActivity)
                        .load(account.avatarUrl)
                        .placeholder(fallback)
                        .error(fallback)
                        .circleCrop()
                        .into(avatar)

                    status.isVisible = true
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        clearAllMenu = menu.findItem(R.id.menu_clear_all)
        clearAllMenu?.isVisible = (binding.viewPager.currentItem == 1)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) return true
        return when (item.itemId) {
            R.id.menu_clear_all -> {
                viewModel.clearAllDownloads()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: return

        if (viewModel.enqueueFromUrl(text)) {
            lastAutoClipboardUrl = text
            viewModel.notifyFromClipboard(text)
            binding.viewPager.currentItem = 0
        }
    }

    private fun handleClipboardAutoDownload() {
        val clipText = try {
            getClipboardText()
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard read blocked: ${e.message}")
            null
        } ?: return

        if (!TumblrParser.isTumblrShareUrl(clipText) || clipText == lastAutoClipboardUrl) return

        Log.d(TAG, "Clipboard detected: ${clipText.take(80)}")
        if (viewModel.enqueueFromUrl(clipText)) {
            lastAutoClipboardUrl = clipText
            viewModel.notifyFromClipboard(clipText)
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
