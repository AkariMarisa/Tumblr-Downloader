package com.example.tumblrdownloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.tumblrdownloader.databinding.ActivityMainBinding
import com.example.tumblrdownloader.utils.TumblrParser
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.tumblrdownloader.ui.download.DownloadFragment
import com.example.tumblrdownloader.ui.download.DownloadsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var clipboardManager: ClipboardManager

    private var lastAutoClipboardUrl: String? = null
    private val tabTitles = listOf("Download", "Downloads")
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardAutoDownload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onStart() {
        super.onStart()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        handleClipboardAutoDownload()
    }

    override fun onStop() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onStop()
    }

    private fun handleClipboardAutoDownload() {
        val clipText = getClipboardText() ?: return
        if (!TumblrParser.isTumblrShareUrl(clipText) || clipText == lastAutoClipboardUrl) {
            return
        }
        lastAutoClipboardUrl = clipText

        if (viewModel.enqueueFromUrl(clipText)) {
            viewModel.notifyFromClipboard(clipText)
        }
    }

    fun showDownloadsTab() {
        binding.viewPager.currentItem = 1
    }

    private fun getClipboardText(): String? {
        if (!clipboardManager.hasPrimaryClip()) return null
        val item = clipboardManager.primaryClip?.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()?.trim()
    }

    inner class MainPagerAdapter(activity: MainActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int) = when (position) {
            0 -> DownloadFragment()
            else -> DownloadsFragment()
        }
    }
}
