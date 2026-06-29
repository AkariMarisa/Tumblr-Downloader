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
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.DownloadStatus
import com.example.tumblrdownloader.model.MediaType

class DownloadsAdapter(
    private val onClick: (DownloadItem) -> Unit,
    private val onStartOrResume: (DownloadItem) -> Unit,
    private val onPause: (DownloadItem) -> Unit,
    private val onRemove: (DownloadItem) -> Unit
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
            binding.tvStatus.text = statusText(item)

            val progressText = when {
                item.progress < 0 -> "--%"
                item.status == DownloadStatus.FAILED && !item.errorMessage.isNullOrBlank() -> item.errorMessage
                else -> "${item.progress}%"
            }
            binding.tvProgress.text = progressText

            val canPause = item.status == DownloadStatus.DOWNLOADING
            val canStart = item.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.FAILED)
            binding.btnStart.isVisible = canStart
            binding.btnPause.isVisible = canPause
            binding.btnRemove.isVisible = true

            if (canStart) {
                binding.btnStart.setOnClickListener {
                    onStartOrResume(item)
                }
            } else {
                binding.btnStart.setOnClickListener(null)
            }

            if (canPause) {
                binding.btnPause.setOnClickListener {
                    onPause(item)
                }
            } else {
                binding.btnPause.setOnClickListener(null)
            }

            binding.btnRemove.setOnClickListener {
                onRemove(item)
            }
            binding.ivThumb.clearColorFilter()
            binding.ivThumb.setBackgroundColor(0)

            when (item.type) {
                MediaType.IMAGE -> {
                    Glide.with(itemView.context)
                        .load(item.mediaUrl)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_report_image)
                        .into(binding.ivThumb)
                }

                MediaType.VIDEO -> {
                    binding.ivThumb.clearColorFilter()
                    Glide.with(itemView.context)
                        .asBitmap()
                        .load(item.mediaUrl)
                        .apply(RequestOptions.frameOf(500_000L))
                        .placeholder(android.R.drawable.ic_media_play)
                        .error(android.R.drawable.ic_media_play)
                        .into(binding.ivThumb)
                }

                MediaType.UNKNOWN -> {
                    binding.ivThumb.setColorFilter(null)
                    binding.ivThumb.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.gray_300))
                    binding.ivThumb.setImageResource(android.R.drawable.ic_menu_help)
                }
            }
            itemView.setOnClickListener { onClick(item) }
        }

        private fun statusText(item: DownloadItem): String {
            val ctx = itemView.context
            return when (item.status) {
                DownloadStatus.QUEUED -> ctx.getString(R.string.status_queued)
                DownloadStatus.DOWNLOADING -> ctx.getString(R.string.status_downloading)
                DownloadStatus.PAUSED -> ctx.getString(R.string.status_paused)
                DownloadStatus.COMPLETED -> ctx.getString(R.string.status_completed)
                DownloadStatus.FAILED -> ctx.getString(R.string.status_failed)
            }
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
