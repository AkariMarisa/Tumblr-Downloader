package io.github.akarimarisa.tumblrdownloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.FragmentDownloadsBinding
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.ui.media.MediaViewerActivity
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.LocalizedToast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    // Latest data snapshots behind the empty state. The visibility must come
    // from the data, not from adapter.itemCount: submitList applies its diff
    // asynchronously, so itemCount is still stale on the very emission that
    // empties the list — clearing all downloads then hid "暂无下载记录".
    private var standaloneEmpty = true
    private var blogCardsEmpty = true

    private val adapter = DownloadsAdapter(
        onClick = ::openViewer,
        onStartOrResume = { item -> viewModel.startOrResumeDownload(item.id) },
        onPause = { item -> viewModel.pauseDownload(item.id) },
        onRemove = { item -> confirmRemoveDownload(item) }
    )

    private val blogJobAdapter = BlogJobAdapter(
        onDetail = { job ->
            // The long-blog confirmation dialog belongs to MainActivity, so
            // entering the detail screen first would park it behind that
            // activity — it would only pop up on the way back (#6). Hold the
            // user on the list until the gate decision is known.
            if (viewModel.blogConfirmPending.value.contains(job.jobId)) {
                LocalizedToast.show(
                    toastContext(),
                    R.string.blog_detail_wait_for_parse,
                    Toast.LENGTH_SHORT
                )
            } else {
                startActivity(
                    BlogDetailActivity.newIntent(requireContext(), job.jobId, job.blogName)
                )
            }
        },
        onResume = { job -> viewModel.resumeBlogJob(job.jobId) },
        onPause = { job -> viewModel.pauseBlogCard(job.jobId) },
        onRemove = { job -> confirmRemoveBlogJob(job) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.adapter = adapter

        binding.rvBlogJobs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBlogJobs.adapter = blogJobAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle re-collects the Flow every time the screen returns to
            // the foreground; otherwise the UI would not refresh after background
            // downloads finish when switching back to the app.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.downloads, viewModel.blogJobs) { list, jobs ->
                    val liveBlogs = jobs.map { it.blogName }.toSet()
                    // Blog batch items belong behind their blog card — except
                    // orphans: a row whose card is gone would be shown by no
                    // screen at all, which is why "already in the list" pointed
                    // at rows nobody could see (#18).
                    list.filter { it.batchId == null || it.blogName !in liveBlogs }
                }.collect { visible ->
                    standaloneEmpty = visible.isEmpty()
                    adapter.submitList(visible)
                    updateEmptyState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    combine(viewModel.blogJobs, viewModel.downloads) { jobs, items -> jobs to items },
                    viewModel.blogActiveJobIds
                ) { (jobs, items), activeIds ->
                    jobs.map { job ->
                        // Blog-wide (not job-scoped) so files kept from an
                        // earlier card still count — see [blogBatchOf] (#10).
                        val batch = blogBatchOf(items, job.jobId, job.blogName)
                        BlogCardUi(
                            job = job,
                            active = job.jobId in activeIds,
                            running = batch.any {
                                it.status == DownloadStatus.DOWNLOADING ||
                                    it.status == DownloadStatus.QUEUED
                            },
                            paused = batch.any { it.status == DownloadStatus.PAUSED },
                            completed = batch.count { it.status == DownloadStatus.COMPLETED },
                            total = batch.size
                        )
                    }
                }.collect { cardUi ->
                    blogJobAdapter.submitList(cardUi)
                    binding.tvBlogSectionTitle.isVisible = cardUi.isNotEmpty()
                    binding.rvBlogJobs.isVisible = cardUi.isNotEmpty()
                    blogCardsEmpty = cardUi.isEmpty()
                    updateEmptyState()
                }
            }
        }
    }

    /** Shows the empty state only when neither the queue nor the blog cards have rows. */
    private fun updateEmptyState() {
        val binding = _binding ?: return
        binding.tvEmpty.visibility =
            if (standaloneEmpty && blogCardsEmpty) View.VISIBLE else View.GONE
    }

    private fun toastContext() = LocaleHelper.contextForAppLocale(requireContext())

    override fun onResume() {
        super.onResume()
        binding.rvDownloads.post {
            if (adapter.itemCount > 0) {
                binding.rvDownloads.scrollToPosition(0)
            }
        }
    }

    private fun confirmRemoveDownload(item: DownloadItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_download)
            .setMessage(R.string.remove_confirm_message)
            .setPositiveButton(R.string.remove_confirm) { _, _ ->
                // NOTE: no clipboard wiping here. The dedupe key in
                // MainViewModel.removeDownload already stops the same link
                // from being re-detected, and emptying the user's clipboard
                // behind their back was both surprising and — combined with
                // that key being cleared — part of the #20 chain.
                viewModel.removeDownload(item.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveBlogJob(job: io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.blog_job_remove)
            .setMessage(R.string.blog_job_remove_confirm_message)
            .setPositiveButton(R.string.blog_job_remove) { _, _ ->
                viewModel.removeBlogJob(job.jobId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openViewer(item: DownloadItem) {
        startActivity(MediaViewerActivity.newIntent(requireContext(), item.mediaUrl, item.type.name))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
