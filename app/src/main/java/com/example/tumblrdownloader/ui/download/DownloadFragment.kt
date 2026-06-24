package com.example.tumblrdownloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.FragmentDownloadBinding
import com.example.tumblrdownloader.ui.MainViewModel
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

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

        lifecycleScope.launch {
            viewModel.autoPasteUrl.collect { url ->
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
