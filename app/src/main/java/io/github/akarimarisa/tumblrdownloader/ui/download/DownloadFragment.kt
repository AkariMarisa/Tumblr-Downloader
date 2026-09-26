package io.github.akarimarisa.tumblrdownloader.ui.download

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.LocalizedToast
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    companion object {
        private const val TAG = "DownloadFragment"
    }

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var loginLaunchPending = false

    private fun toastContext() = LocaleHelper.contextForAppLocale(requireContext())

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
                LocalizedToast.show(toastContext(), R.string.invalid_url, android.widget.Toast.LENGTH_SHORT)
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
                            LocalizedToast.show(toastContext(), event.text, android.widget.Toast.LENGTH_LONG)
                        }
                    }

                    is ParseEvent.LoginRequired -> {
                        showLoading(false)
                        if (loginLaunchPending) {
                            Log.d(TAG, "LoginRequired but launch already pending, skipping")
                            return@collect
                        }
                        loginLaunchPending = true
                        LocalizedToast.show(toastContext(), event.message, android.widget.Toast.LENGTH_LONG)
                        loginLauncher.launch(TumblrLoginActivity.newIntent(requireContext(), event.url))
                    }

                    is ParseEvent.Queued -> {
                        binding.etUrl.text?.clear()
                        LocalizedToast.show(toastContext(), toastContext().getString(R.string.queued_message, event.count), android.widget.Toast.LENGTH_SHORT)
                        (requireActivity() as? MainActivity)?.showDownloadsTab()
                        binding.loadingOverlay.post {
                            showLoading(false)
                        }
                    }

                    is ParseEvent.CookieSecurityNotice -> {
                        showLoading(false)
                        LocalizedToast.show(toastContext(), event.text, android.widget.Toast.LENGTH_LONG)
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
