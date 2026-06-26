package com.example.tumblrdownloader.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.FileUriExposedException
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.ActivitySettingsBinding
import com.example.tumblrdownloader.ui.MainViewModel
import com.example.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.menu_settings)

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

        binding.btnClearCookies.setOnClickListener {
            viewModel.clearSavedCookies()
            Toast.makeText(this, R.string.cookies_cleared_toast, Toast.LENGTH_SHORT).show()
        }

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

    private fun openDownloadDirectory() {
        val treeUri = DownloadUtils.getCurrentDownloadDirectory(this)
        val realDir = resolveDirectoryPath(treeUri)

        // 1) Try file:// URI — works with Material Files, Solid Explorer, FX, etc.
        //    Intent.createChooser() shows all apps that can handle resource/folder.
        if (realDir != null) {
            try {
                val fileIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(realDir), "resource/folder")
                }
                startActivity(Intent.createChooser(fileIntent, null))
                return
            } catch (_: FileUriExposedException) {
                // Android 7+ blocks file:// URIs — fall through
            } catch (_: ActivityNotFoundException) {
                // No app installed that handles resource/folder — fall through
            } catch (_: Exception) {
            }
        }

        // 2) Fallback: SAF tree URI + directory MIME type (works with system Files app)
        try {
            val safIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(safIntent, null))
            return
        } catch (_: Exception) {
        }

        // 3) Last resort: system directory picker at the right location
        try {
            val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }
            startActivity(pickerIntent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_download_directory_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Convert a tree URI (e.g. primary:Download/2) to the actual File path. */
    private fun resolveDirectoryPath(treeUri: Uri): File? {
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { null } ?: return null
        // SAF document ID for external storage: "primary:relative/path"
        if (!docId.startsWith("primary:")) return null
        val relativePath = docId.removePrefix("primary:")
        return File(Environment.getExternalStorageDirectory(), relativePath)
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
