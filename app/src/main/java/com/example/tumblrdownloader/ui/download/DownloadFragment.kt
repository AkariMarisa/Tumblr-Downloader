package com.example.tumblrdownloader.ui.download

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.tumblrdownloader.ui.MainActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.FragmentDownloadBinding
import com.example.tumblrdownloader.ui.MainViewModel
import com.example.tumblrdownloader.ui.ParseEvent
import com.example.tumblrdownloader.ui.auth.TumblrLoginActivity
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
                binding.etUrl.text?.clear()
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

        binding.btnClearCookies.setOnClickListener {
            viewModel.clearSavedCookies()
            Toast.makeText(requireContext(), R.string.cookies_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            viewModel.downloadDirectoryLabel.collect { text ->
                binding.tvDownloadDirectory.text = getString(R.string.download_directory_display, text)
            }
        }

        lifecycleScope.launch {
            viewModel.autoPasteUrl.collect { url ->
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
                binding.tvPasteHint.visibility = View.VISIBLE
                Toast.makeText(requireContext(), R.string.clipboard_link_detected, Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            viewModel.parseEvent.collect { event ->
                when (event) {
                    is ParseEvent.Message -> {
                        if (event.text.isNotBlank()) {
                            Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
                        }
                    }

                    is ParseEvent.LoginRequired -> {
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        loginLauncher.launch(TumblrLoginActivity.newIntent(requireContext(), event.url))
                    }

                    is ParseEvent.Queued -> {
                        Toast.makeText(requireContext(), getString(R.string.queued_message, event.count), Toast.LENGTH_SHORT).show()
                        (requireActivity() as? MainActivity)?.showDownloadsTab()
                    }

                    is ParseEvent.CookieSecurityNotice -> {
                        Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
