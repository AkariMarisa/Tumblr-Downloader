package io.github.akarimarisa.tumblrdownloader.ui.download

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.FragmentDownloadBinding
import io.github.akarimarisa.tumblrdownloader.ui.MainActivity
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.model.ParseEvent
import io.github.akarimarisa.tumblrdownloader.ui.auth.TumblrLoginActivity
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    companion object {
        private const val TAG = "DownloadFragment"
    }

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var loginLaunchPending = false

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        loginLaunchPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.refreshTumblrAccount()
            viewModel.retryPendingLoginUrl()
        }
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

        binding.loadingOverlay.setOnClickListener {
            // blocking overlay while parsing
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.autoPasteUrl.collect { url ->
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
                showLoading(true)
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
                        if (loginLaunchPending) {
                            Log.d(TAG, "LoginRequired but launch already pending, skipping")
                            return@collect
                        }
                        loginLaunchPending = true
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
        binding.etUrl.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
