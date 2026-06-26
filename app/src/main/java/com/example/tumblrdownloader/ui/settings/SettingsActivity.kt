package com.example.tumblrdownloader.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
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

        // Tree URI + directory MIME type → file managers that support SAF open directly.
        // Intent.createChooser() ensures the user sees the app picker to choose their
        // preferred file manager (system Files, Material Files, FX, Solid Explorer, etc.)
        try {
            val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { null }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Material Files reads EXTRA_INITIAL_URI in its extraPath handler
                if (docId != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(treeUri.authority ?: "com.android.externalstorage.documents", docId))
                }
            }
            startActivity(Intent.createChooser(intent, null))
            return
        } catch (_: Exception) {
            // Path doesn't exist (default dir), or no file manager installed
        }

        // 2) Fallback: parent path (default subdir may not exist yet)
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { null }
        if (docId != null && docId.contains('/')) {
            val parentDocId = docId.substringBeforeLast('/')
            try {
                val parentUri = DocumentsContract.buildTreeDocumentUri(
                    treeUri.authority ?: "com.android.externalstorage.documents",
                    parentDocId
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(parentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, null))
                return
            } catch (_: Exception) { }
        }

        // 3) Last resort: system directory picker
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_download_directory_failed, Toast.LENGTH_LONG).show()
        }
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
