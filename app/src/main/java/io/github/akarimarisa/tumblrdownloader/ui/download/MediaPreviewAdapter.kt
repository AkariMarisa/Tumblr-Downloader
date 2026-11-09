package io.github.akarimarisa.tumblrdownloader.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ItemMediaPreviewBinding
import io.github.akarimarisa.tumblrdownloader.model.MediaPreviewKind
import io.github.akarimarisa.tumblrdownloader.model.MediaPreviewSelection
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import io.github.akarimarisa.tumblrdownloader.utils.ParsedTumblrMedia

/**
 * Rows of the pre-download picker. Tapping anywhere on a row toggles its
 * checkbox; the checkbox itself is not clickable so the row stays one target.
 */
class MediaPreviewAdapter(
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<MediaPreviewAdapter.VH>() {

    private var items: List<ParsedTumblrMedia> = emptyList()
    private var selected: Set<Int> = emptySet()

    fun submit(newItems: List<ParsedTumblrMedia>, newSelected: Set<Int>) {
        items = newItems
        selected = newSelected
        notifyDataSetChanged()
    }

    fun updateSelection(newSelected: Set<Int>) {
        if (selected == newSelected) return
        val previously = selected
        selected = newSelected
        items.indices.forEach { index ->
            if ((index in previously) != (index in newSelected)) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMediaPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position, position in selected)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(position in selected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(
        private val binding: ItemMediaPreviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ParsedTumblrMedia, position: Int, isSelected: Boolean) {
            val context = binding.root.context
            val typeLabel = context.getString(typeLabelRes(item))

            binding.tvTitle.text = item.title.ifBlank { typeLabel }
            binding.tvMediaType.text = typeLabel
            bindSelection(isSelected)

            binding.root.setOnClickListener { onToggle(position) }

            if (item.type == MediaType.VIDEO) {
                Glide.with(context)
                    .asBitmap()
                    .load(item.mediaUrl)
                    .apply(RequestOptions.frameOf(500_000L))
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .into(binding.ivPreview)
            } else {
                Glide.with(context)
                    .load(item.mediaUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(binding.ivPreview)
            }
        }

        fun bindSelection(isSelected: Boolean) {
            binding.cbSelect.isChecked = isSelected
        }

        private fun typeLabelRes(item: ParsedTumblrMedia): Int = when (MediaPreviewSelection.kindOf(item)) {
            MediaPreviewKind.IMAGES -> R.string.media_preview_type_image
            MediaPreviewKind.VIDEOS -> R.string.media_preview_type_video
            MediaPreviewKind.GIFS -> R.string.media_preview_type_gif
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
    }
}
