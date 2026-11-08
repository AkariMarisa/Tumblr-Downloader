package io.github.akarimarisa.tumblrdownloader.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivitySettingsBinding
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper

/**
 * First level of the settings hierarchy.
 *
 * It deliberately lists *only* the settings categories — the concrete options
 * live one level down in [SettingsDetailActivity]. A single screen holding
 * every option (directory, concurrency, rate limit, qualities, blog scan
 * pacing, language, theme, cache) was flagged as too dense in the F-Droid
 * review thread, so the screen became a short menu of focused sub-screens.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.rowDownload.setOnClickListener { open(SettingsCategory.DOWNLOAD) }
        binding.rowQuality.setOnClickListener { open(SettingsCategory.QUALITY) }
        binding.rowBlog.setOnClickListener { open(SettingsCategory.BLOG_SCAN) }
        binding.rowInterface.setOnClickListener { open(SettingsCategory.INTERFACE) }
    }

    private fun open(category: SettingsCategory) {
        startActivity(SettingsDetailActivity.newIntent(this, category))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
