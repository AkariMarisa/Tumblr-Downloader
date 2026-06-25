package com.example.tumblrdownloader.ui.download

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.FragmentDownloadBinding
import com.example.tumblrdownloader.ui.MainActivity
import com.example.tumblrdownloader.ui.MainViewModel
import com.example.tumblrdownloader.ui.ParseEvent
import com.example.tumblrdownloader.ui.auth.TumblrLoginActivity
import com.example.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.retryPendingLoginUrl()
        }
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult

        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // 若无法持久化权限，仍允许本次写入（未重启时通常可用）
            val msg = getString(R.string.download_dir_permission_warning)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }

        viewModel.setCustomDownloadDirectory(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text?.toString().orEmpty().trim()
            val ok = viewModel.enqueueFromUrl(url)
            if (!ok) {
                Toast.makeText(requireContext(), R.string.invalid_url, Toast.LENGTH_SHORT).show()
            } else {
                showLoading(true)
            }
        }

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(TumblrLoginActivity.newIntent(requireContext(), ""))
        }

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
        }

        binding.btnOpenDownloadDir.setOnClickListener {
            openDownloadDirectory()
        }

        binding.btnClearCookies.setOnClickListener {
            viewModel.clearSavedCookies()
            Toast.makeText(requireContext(), R.string.cookies_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        binding.loadingOverlay.setOnClickListener {
            // blocking overlay while parsing
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadDirectoryLabel.collect { text ->
                binding.tvDownloadDirectory.text = getString(R.string.download_directory_display, text)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.autoPasteUrl.collect { url ->
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
                binding.tvPasteHint.visibility = View.VISIBLE
                Toast.makeText(requireContext(), R.string.clipboard_link_detected, Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.parseEvent.collect { event ->
                when (event) {
                    is ParseEvent.Message -> {
                        showLoading(false)
                        if (event.text.isNotBlank()) {
                            Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
                        }
                    }

                    is ParseEvent.LoginRequired -> {
                        showLoading(false)
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        loginLauncher.launch(TumblrLoginActivity.newIntent(requireContext(), event.url))
                    }

                    is ParseEvent.Queued -> {
                        binding.etUrl.text?.clear()
                        Toast.makeText(requireContext(), getString(R.string.queued_message, event.count), Toast.LENGTH_SHORT).show()
                        (requireActivity() as? MainActivity)?.showDownloadsTab()
                        binding.loadingOverlay.post {
                            showLoading(false)
                        }
                    }

                    is ParseEvent.CookieSecurityNotice -> {
                        showLoading(false)
                        Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.isVisible = isLoading
        binding.btnDownload.isEnabled = !isLoading
        binding.btnLogin.isEnabled = !isLoading
        binding.btnChooseDownloadDir.isEnabled = !isLoading
        binding.btnResetDownloadDir.isEnabled = !isLoading
        binding.btnOpenDownloadDir.isEnabled = !isLoading
        binding.btnClearCookies.isEnabled = !isLoading
        binding.etUrl.isEnabled = !isLoading
    }

    private fun openDownloadDirectory() {
        val uri = DownloadUtils.getCurrentDownloadDirectory(requireContext())
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        if (!tryStartActivity(pickerIntent) && !tryStartActivity(viewIntent)) {
            Toast.makeText(requireContext(), R.string.open_download_directory_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
