package com.example.tumblrdownloader.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.ActivityAboutBinding
import com.example.tumblrdownloader.utils.LocaleHelper

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvAppName.text = getString(R.string.app_name)
        binding.tvAppDescription.text = getString(R.string.about_description)

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
        binding.tvAppVersion.text = getString(R.string.about_version, version)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
