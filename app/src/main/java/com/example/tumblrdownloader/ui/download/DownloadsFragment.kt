package com.example.tumblrdownloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tumblrdownloader.databinding.FragmentDownloadsBinding
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.ui.MainViewModel
import com.example.tumblrdownloader.ui.media.MediaViewerActivity
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val adapter = DownloadsAdapter(
        onClick = ::openViewer,
        onRetry = { item -> viewModel.retryDownload(item.id) }
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
            viewModel.downloads.collect { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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

    private fun openViewer(item: DownloadItem) {
        startActivity(MediaViewerActivity.newIntent(requireContext(), item.mediaUrl, item.type.name))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
