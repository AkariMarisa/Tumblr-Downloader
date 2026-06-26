package com.example.tumblrdownloader.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
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

        // Convert tree URI → document URI so the system Files app navigates
        // to the exact subdirectory.
        //   treeUri:   content://.../tree/primary%3ADownload%2Fsubdir
        //   docUri:    content://.../document/primary%3ADownload%2Fsubdir
        //
        // IMPORTANT: Uri.Builder.path() re-encodes %2F back to /, splitting the
        // document ID into multiple path segments.  We build the URI string
        // directly via Uri.parse() to preserve the single-segment encoding.
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val encodedDocId = Uri.encode(docId) // %3A + %2F preserved in a single segment
            val authority = treeUri.authority ?: "com.android.externalstorage.documents"
            val docUri = Uri.parse("${'$'}{treeUri.scheme}://${'$'}authority/document/${'$'}encodedDocId")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            }, null))
            return
        } catch (_: Exception) {
            // URI malformed, directory doesn't exist, or no app can handle it
        }

        // Fallback: system directory picker (always works)
        try {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_download_directory_failed, Toast.LENGTH_LONG).show()
        }
    }
}
