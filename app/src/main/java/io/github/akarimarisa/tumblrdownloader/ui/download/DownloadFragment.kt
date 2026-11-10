package io.github.akarimarisa.tumblrdownloader.ui.download

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.FragmentDownloadBinding
import io.github.akarimarisa.tumblrdownloader.model.BlogDownloadOptions
import io.github.akarimarisa.tumblrdownloader.model.BlogEvent
import io.github.akarimarisa.tumblrdownloader.model.BlogMediaFilter
import io.github.akarimarisa.tumblrdownloader.ui.MainActivity
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.model.ParseEvent
import io.github.akarimarisa.tumblrdownloader.ui.auth.TumblrLoginActivity
import io.github.akarimarisa.tumblrdownloader.utils.AutoDownloadPrefs
import io.github.akarimarisa.tumblrdownloader.utils.BlogFeedParser
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.LocalizedToast
import io.github.akarimarisa.tumblrdownloader.utils.TumblrParser
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    companion object {
        private const val TAG = "DownloadFragment"

        private val mediaFilterChoices = arrayOf(
            BlogMediaFilter.ALL,
            BlogMediaFilter.IMAGES,
            BlogMediaFilter.VIDEOS
        )
    }

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var loginLaunchPending = false
    private var previewDialog: AlertDialog? = null
    private var previewDialogId: String? = null

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
            if (BlogFeedParser.isBlogUrl(url)) {
                promptBlogOptions(url)
            } else {
                val ok = viewModel.enqueueFromUrl(url)
                if (!ok) {
                    LocalizedToast.show(toastContext(), R.string.invalid_url, Toast.LENGTH_SHORT)
                } else {
                    showLoading(true)
                }
            }
        }

        binding.loadingOverlay.setOnClickListener {
            // blocking overlay while parsing
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.autoPasteUrl.collect { url ->
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
                if (!BlogFeedParser.isBlogUrl(url)) {
                    showLoading(true)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.parseEvent.collect { event ->
                when (event) {
                    is ParseEvent.Message -> {
                        showLoading(false)
                        if (event.text.isNotBlank()) {
                            LocalizedToast.show(toastContext(), event.text, Toast.LENGTH_LONG)
                        }
                    }

                    is ParseEvent.LoginRequired -> {
                        showLoading(false)
                        if (loginLaunchPending) {
                            Log.d(TAG, "LoginRequired but launch already pending, skipping")
                            return@collect
                        }
                        loginLaunchPending = true
                        LocalizedToast.show(toastContext(), event.message, Toast.LENGTH_LONG)
                        loginLauncher.launch(TumblrLoginActivity.newIntent(requireContext(), event.url))
                    }

                    is ParseEvent.Queued -> {
                        binding.etUrl.text?.clear()
                        LocalizedToast.show(toastContext(), toastContext().resources.getQuantityString(R.plurals.queued_message, event.count, event.count), Toast.LENGTH_SHORT)
                        (requireActivity() as? MainActivity)?.showDownloadsTab()
                        binding.loadingOverlay.post {
                            showLoading(false)
                        }
                    }

                    is ParseEvent.CookieSecurityNotice -> {
                        showLoading(false)
                        LocalizedToast.show(toastContext(), event.text, Toast.LENGTH_LONG)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mediaPreview.collect { request ->
                    // A null request means the picker was confirmed/cancelled
                    // (possibly from a previous view instance) — close ours.
                    if (request == null) {
                        previewDialog?.dismiss()
                        previewDialog = null
                        previewDialogId = null
                        return@collect
                    }
                    // Already showing this exact request (e.g. a StateFlow
                    // re-emission after rotation) — don't stack dialogs.
                    if (request.id == previewDialogId) return@collect

                    showLoading(false)
                    previewDialog?.dismiss()
                    previewDialogId = request.id
                    previewDialog = MediaPreviewDialog(
                        requireContext(),
                        request,
                        onConfirm = { indices ->
                            viewModel.confirmMediaPreview(request.id, indices)
                        },
                        onCancel = {
                            viewModel.cancelMediaPreview(request.id)
                        }
                    ).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.blogEvents.collect { event ->
                when (event) {
                    is BlogEvent.Message ->
                        LocalizedToast.show(toastContext(), event.text, Toast.LENGTH_LONG)

                    is BlogEvent.ConfirmLongBlog -> promptConfirmLong(event)

                    is BlogEvent.Finished -> onBlogFinished(event)
                }
            }
        }
    }

    private fun onBlogFinished(event: BlogEvent.Finished) {
        if (event.cancelled) {
            LocalizedToast.show(toastContext(), R.string.blog_job_cancelled_toast, Toast.LENGTH_SHORT)
            return
        }
        val job = viewModel.blogJobs.value.firstOrNull { it.jobId == event.jobId }
        if (job != null) {
            LocalizedToast.show(
                toastContext(),
                toastContext().resources.getQuantityString(
                    R.plurals.blog_job_scanned_toast,
                    job.postsDiscovered + job.postsSkipped + job.postsNoMedia,
                    job.postsDiscovered + job.postsSkipped + job.postsNoMedia,
                    job.mediaEnqueued
                ),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun promptBlogOptions(url: String) {
        // "自动下载全部媒体文件" enabled → no dialog at all, scan the whole
        // blog and take every image and video it finds.
        if (AutoDownloadPrefs.read(requireContext())) {
            startBlogDownload(url, BlogMediaFilter.ALL)
            return
        }
        val t = toastContext()
        AlertDialog.Builder(t)
            .setTitle(R.string.blog_media_filter_dialog_title)
            .setItems(
                arrayOf(
                    t.getString(R.string.blog_media_filter_all),
                    t.getString(R.string.blog_media_filter_images),
                    t.getString(R.string.blog_media_filter_videos)
                )
            ) { _, which ->
                startBlogDownload(url, mediaFilterChoices[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startBlogDownload(url: String, filter: BlogMediaFilter) {
        // enqueueBlogUrl suspends (card restore + first disk write),
        // so it must not run on the dialog's click thread directly.
        lifecycleScope.launch {
            val ok = viewModel.enqueueBlogUrl(
                url,
                BlogDownloadOptions(mediaFilter = filter)
            )
            if (ok && _binding != null) {
                binding.etUrl.text?.clear()
                showLoading(false)
                (requireActivity() as? MainActivity)?.showDownloadsTab()
            }
        }
    }

    private fun promptConfirmLong(event: BlogEvent.ConfirmLongBlog) {
        val t = toastContext()
        AlertDialog.Builder(t)
            .setTitle(R.string.blog_confirm_long_title)
            .setMessage(
                t.resources.getQuantityString(
                    R.plurals.blog_confirm_long_message,
                    event.totalPosts,
                    event.blogName,
                    event.totalPosts,
                    event.estimatedRequests
                )
            )
            .setPositiveButton(R.string.blog_confirm_start) { _, _ ->
                viewModel.respondBlogLong(event.jobId, true)
            }
            .setNegativeButton(R.string.blog_confirm_cancel) { _, _ ->
                viewModel.respondBlogLong(event.jobId, false)
            }
            .setCancelable(false)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.isVisible = isLoading
        binding.btnDownload.isEnabled = !isLoading
        binding.etUrl.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Do not call cancelMediaPreview here: a rotation recreates the view
        // and the StateFlow re-delivers the same request so the picker reappears.
        previewDialog?.dismiss()
        previewDialog = null
        previewDialogId = null
        _binding = null
    }
}
