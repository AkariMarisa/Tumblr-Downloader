package io.github.akarimarisa.tumblrdownloader.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.File
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivitySettingsBinding
import io.github.akarimarisa.tumblrdownloader.model.ImageQualityTier
import io.github.akarimarisa.tumblrdownloader.model.MediaQualityPrefs
import io.github.akarimarisa.tumblrdownloader.model.VideoQualityTier
import io.github.akarimarisa.tumblrdownloader.ui.MainActivity
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.utils.DownloadUtils
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import kotlinx.coroutines.launch

private const val PREFS_NAME = "tumblr_downloader"
private const val PREF_CLIPBOARD_AUTO_DETECT = "clipboard_auto_detect"
private const val PREF_LAST_AUTO_URL = "last_auto_detected_url"
private const val PREF_MAX_CONCURRENT = "max_concurrent_downloads"
private const val PREF_RATE_LIMIT = "rate_limit_bytes_per_second"

private val RATE_LIMIT_OPTIONS = longArrayOf(0L, 262_144L, 524_288L, 1_048_576L, 2_097_152L, 4_194_304L)

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult

        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.download_dir_permission_warning, Toast.LENGTH_LONG).show()
        }

        viewModel.setCustomDownloadDirectory(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.menu_settings)

        setupLanguageSelector()

        binding.btnChooseDownloadDir.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            directoryPickerLauncher.launch(intent)
        }

        binding.btnResetDownloadDir.setOnClickListener {
            viewModel.resetDownloadDirectory()
            Toast.makeText(this, R.string.download_directory_reset_toast, Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenDownloadDir.setOnClickListener {
            openDownloadDirectory()
        }

        // ── Clipboard auto-detect toggle ──
        binding.switchClipboardAutoDetect.isChecked = prefs.getBoolean(PREF_CLIPBOARD_AUTO_DETECT, true)
        binding.switchClipboardAutoDetect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_CLIPBOARD_AUTO_DETECT, isChecked).apply()
        }

        // ── Clear auto-detect cache ──
        binding.btnClearCache.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pref_clear_cache)
                .setMessage(R.string.pref_clear_cache_dialog)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.edit().remove(PREF_LAST_AUTO_URL).apply()
                    Toast.makeText(this, R.string.cache_cleared_toast, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // ── Rate limit ──
        updateRateLimitDisplay()
        binding.btnRateLimit.setOnClickListener { showRateLimitDialog() }

        // ── Concurrent downloads ──
        updateConcurrencyDisplay()
        binding.btnConcurrentDownloads.setOnClickListener { showConcurrencyDialog() }

        // ── Media quality tiers ──
        updateImageQualityDisplay()
        updateVideoQualityDisplay()
        binding.btnImageQuality.setOnClickListener { showImageQualityDialog() }
        binding.btnVideoQuality.setOnClickListener { showVideoQualityDialog() }

        lifecycleScope.launch {
            viewModel.downloadDirectoryLabel.collect { text ->
                binding.tvDownloadDirectory.text = getString(R.string.download_directory_display, text)
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun getLanguageDisplayName(): String {
        val locale = LocaleHelper.getPersistedLocale(this)
        return when {
            locale == null -> getString(R.string.language_system)
            locale.toLanguageTag().startsWith("zh") -> getString(R.string.language_zh)
            else -> getString(R.string.language_en)
        }
    }

    private fun setupLanguageSelector() {
        val current = LocaleHelper.getPersistedLocale(this)

        binding.tvCurrentLanguage.text = getLanguageDisplayName()

        binding.btnLanguage.setOnClickListener {
            val items = arrayOf(
                getString(R.string.language_system),
                getString(R.string.language_zh),
                getString(R.string.language_en)
            )
            val checked = when {
                current == null -> 0
                current.toLanguageTag().startsWith("zh") -> 1
                else -> 2
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(items, checked) { dialog, which ->
                    val locale = when (which) {
                        1 -> Locale("zh")
                        2 -> Locale.ENGLISH
                        else -> null
                    }
                    LocaleHelper.persistLocale(this, locale)
                    dialog.dismiss()
                    // Restart the entire task so the new locale applies everywhere
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }.let { startActivity(it) }
                    finishAffinity()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ── Rate limit ───────────────────────────────────────────────

    private fun formatRateLimitLabel(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return getString(R.string.rate_limit_unlimited)
        return when {
            bytesPerSecond >= 1_048_576L && bytesPerSecond % 1_048_576L == 0L ->
                "${bytesPerSecond / 1_048_576} MB/s"
            bytesPerSecond >= 1_024L && bytesPerSecond % 1_024L == 0L ->
                "${bytesPerSecond / 1_024} KB/s"
            else -> "${bytesPerSecond} B/s"
        }
    }

    private fun updateRateLimitDisplay() {
        val current = prefs.getLong(PREF_RATE_LIMIT, 1_048_576L)
        binding.tvRateLimitValue.text = formatRateLimitLabel(current)
    }

    private fun showRateLimitDialog() {
        val current = prefs.getLong(PREF_RATE_LIMIT, 1_048_576L)
        val labels = RATE_LIMIT_OPTIONS.map { formatRateLimitLabel(it) }.toTypedArray()
        val checkedIndex = RATE_LIMIT_OPTIONS.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.pref_rate_limit)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                prefs.edit().putLong(PREF_RATE_LIMIT, RATE_LIMIT_OPTIONS[which]).apply()
                updateRateLimitDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Concurrent downloads ───────────────────────────────────────

    private fun updateConcurrencyDisplay() {
        val current = prefs.getInt(PREF_MAX_CONCURRENT, 1).coerceIn(1, 4)
        binding.tvConcurrentDownloads.text = getString(R.string.concurrent_downloads_value, current)
    }

    private fun showConcurrencyDialog() {
        val current = prefs.getInt(PREF_MAX_CONCURRENT, 1).coerceIn(1, 4)
        val options = arrayOf(1, 2, 3, 4)
        val labels = options.map { getString(R.string.concurrent_downloads_value, it) }.toTypedArray()
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.concurrent_downloads_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                prefs.edit().putInt(PREF_MAX_CONCURRENT, options[which]).apply()
                updateConcurrencyDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Media quality tiers ────────────────────────────────────────

    private fun updateImageQualityDisplay() {
        val tier = MediaQualityPrefs.read(this).imageTier
        binding.tvImageQuality.text = getString(tier.labelRes)
    }

    private fun updateVideoQualityDisplay() {
        val tier = MediaQualityPrefs.read(this).videoTier
        binding.tvVideoQuality.text = getString(tier.labelRes)
    }

    private fun showImageQualityDialog() {
        val current = MediaQualityPrefs.read(this).imageTier
        val options = ImageQualityTier.entries.toTypedArray()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.image_quality_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                MediaQualityPrefs.setImageTier(this, options[which])
                updateImageQualityDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVideoQualityDialog() {
        val current = MediaQualityPrefs.read(this).videoTier
        val options = VideoQualityTier.entries.toTypedArray()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.video_quality_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                MediaQualityPrefs.setVideoTier(this, options[which])
                updateVideoQualityDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDownloadDirectory() {
        val treeUri = DownloadUtils.getCurrentDownloadDirectory(this)
        val docId = DocumentsContract.getTreeDocumentId(treeUri)

        // 1) SAF document URI + vnd.android.document/directory
        //    → system Files app (DocumentsUI), navigates to the exact subdirectory.
        //    No createChooser — DocumentsUI is the default handler for this type
        //    when no third-party file managers are installed.
        //    No FLAG_GRANT_READ_URI_PERMISSION — SAF docs are world-readable.
        if (docId.startsWith("primary:")) {
            try {
                val docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                }
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: Exception) {
            }
        }

        // 2) file:// URI for file managers that accept resource/folder (Material Files etc.)
        try {
            if (docId.startsWith("primary:")) {
                val file = File(
                    Environment.getExternalStorageDirectory(),
                    docId.removePrefix("primary:")
                )
                val prevPolicy = if (Build.VERSION.SDK_INT >= 24)
                    StrictMode.getVmPolicy() else null
                if (prevPolicy != null) {
                    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(file), "resource/folder")
                    }
                    startActivity(Intent.createChooser(intent, null))
                    return
                } finally {
                    if (prevPolicy != null) {
                        StrictMode.setVmPolicy(prevPolicy)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3) SAF tree URI + vnd.android.document/directory → catch-all.
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: Exception) {
        }

        // 4) Last resort: system directory picker
        try {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_download_directory_failed, Toast.LENGTH_LONG).show()
        }
    }
}
