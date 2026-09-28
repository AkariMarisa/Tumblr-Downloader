package io.github.akarimarisa.tumblrdownloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val adapter = DownloadsAdapter(
        onClick = ::openViewer,
        onStartOrResume = { item -> viewModel.startOrResumeDownload(item.id) },
        onPause = { item -> viewModel.pauseDownload(item.id) },
        onRemove = { item -> confirmRemoveDownload(item) }
    )

    private val blogJobAdapter = BlogJobAdapter(
        onDetail = { job ->
            startActivity(
                BlogDetailActivity.newIntent(requireContext(), job.jobId, job.blogName)
            )
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
                viewModel.downloads.collect { list ->
                    // Blog batch items belong behind their blog card
                    // (BlogDetailActivity) — keep the main queue to standalone
                    // downloads only.
                    val visible = list.filter { it.batchId == null }
                    adapter.submitList(visible)
                    binding.tvEmpty.visibility =
                        if (visible.isEmpty() && blogJobAdapter.itemCount == 0) View.VISIBLE else View.GONE
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
                        val batch = items.filter { it.batchId == job.jobId }
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
                    binding.tvEmpty.visibility =
                        if (cardUi.isEmpty() && adapter.itemCount == 0) View.VISIBLE else View.GONE
                }
            }
        }
    }

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
                // Clear clipboard to prevent re-detection of the same URL
                runCatching {
                    val cm = requireContext().getSystemService(
                        android.content.ClipboardManager::class.java
                    )
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(null, ""))
                }
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