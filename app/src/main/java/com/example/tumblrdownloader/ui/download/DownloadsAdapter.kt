package com.example.tumblrdownloader.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tumblrdownloader.R
import com.example.tumblrdownloader.databinding.ItemDownloadBinding
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.model.MediaType

class DownloadsAdapter(
    private val onClick: (DownloadItem) -> Unit,
    private val onRetry: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadsAdapter.DownloadVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadVH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DownloadVH(binding)
    }

    override fun onBindViewHolder(holder: DownloadVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DownloadVH(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.tvTitle.text = item.title
            binding.tvStatus.text = item.status.name
            binding.tvProgress.text = "${item.progress}%"

            if (item.status == DownloadStatus.FAILED && item.errorMessage.isNullOrBlank().not()) {
                binding.tvProgress.text = item.errorMessage
            }

            val thumbColor = when (item.type) {
                MediaType.IMAGE -> ContextCompat.getColor(itemView.context, R.color.green_500)
                MediaType.VIDEO -> ContextCompat.getColor(itemView.context, R.color.blue_500)
                MediaType.UNKNOWN -> ContextCompat.getColor(itemView.context, R.color.gray_300)
            }
            binding.vThumb.setBackgroundColor(thumbColor)

            val canRetry = item.status == DownloadStatus.FAILED && item.retryCount >= item.maxRetries
            binding.btnRetry.isVisible = canRetry
            if (canRetry) {
                binding.btnRetry.setOnClickListener {
                    onRetry(item)
                }
            } else {
                binding.btnRetry.setOnClickListener(null)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
