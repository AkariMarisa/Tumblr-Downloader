package io.github.akarimarisa.tumblrdownloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.FragmentDownloadsBinding
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.ui.media.MediaViewerActivity
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

        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle 确保每次回到前台时重新收集 Flow，
            // 否则后台下载完成后切回前台时 UI 不会刷新。
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloads.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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

    private fun openViewer(item: DownloadItem) {
        startActivity(MediaViewerActivity.newIntent(requireContext(), item.mediaUrl, item.type.name))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
