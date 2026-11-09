package io.github.akarimarisa.tumblrdownloader.ui.download

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.DialogMediaPreviewBinding
import io.github.akarimarisa.tumblrdownloader.model.MediaPreviewKind
import io.github.akarimarisa.tumblrdownloader.model.MediaPreviewRequest
import io.github.akarimarisa.tumblrdownloader.model.MediaPreviewSelection

/**
 * Pre-download confirmation screen for one parsed post.
 *
 * The parse result already lives in [MediaPreviewRequest]; this dialog only
 * edits the selection and reports the kept indices back. It never enqueues by
 * itself, so dismissing it (back / Skip) queues nothing.
 */
class MediaPreviewDialog(
    private val context: Context,
    private val request: MediaPreviewRequest,
    private val onConfirm: (Set<Int>) -> Unit,
    private val onCancel: () -> Unit
) {

    private var selection = MediaPreviewSelection(request.items)
    private var positiveButton: android.widget.TextView? = null

    fun show(): AlertDialog {
        val binding = DialogMediaPreviewBinding.inflate(LayoutInflater.from(context))
        lateinit var adapter: MediaPreviewAdapter
        adapter = MediaPreviewAdapter { index ->
            selection = selection.toggle(index)
            render(binding, adapter)
        }
        binding.rvPreview.layoutManager = LinearLayoutManager(context)
        binding.rvPreview.adapter = adapter
        adapter.submit(request.items, selection.selected)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.media_preview_title)
            .setView(binding.root)
            .setPositiveButton(R.string.media_preview_download, null)
            .setNegativeButton(R.string.media_preview_skip, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            positiveButton = positive

            positive.setOnClickListener {
                onConfirm(selection.selected)
                dialog.dismiss()
            }
            negative.setOnClickListener {
                onCancel()
                dialog.dismiss()
            }

            binding.btnSelectAllMedia.setOnClickListener {
                selection = selection.selectAll()
                render(binding, adapter)
            }
            binding.btnSelectNoneMedia.setOnClickListener {
                selection = selection.selectNone()
                render(binding, adapter)
            }
            binding.btnImages.setOnClickListener {
                selection = selection.toggleKind(MediaPreviewKind.IMAGES)
                render(binding, adapter)
            }
            binding.btnVideos.setOnClickListener {
                selection = selection.toggleKind(MediaPreviewKind.VIDEOS)
                render(binding, adapter)
            }
            binding.btnGifs.setOnClickListener {
                selection = selection.toggleKind(MediaPreviewKind.GIFS)
                render(binding, adapter)
            }

            render(binding, adapter)
        }

        dialog.setOnCancelListener { onCancel() }

        dialog.show()
        return dialog
    }

    private fun render(
        binding: DialogMediaPreviewBinding,
        adapter: MediaPreviewAdapter
    ) {
        binding.tvPreviewSummary.text = context.getString(
            R.string.media_preview_summary,
            selection.selectedCount,
            request.items.size
        )
        binding.btnImages.text = countLabel(binding, R.string.media_preview_images, MediaPreviewKind.IMAGES)
        binding.btnVideos.text = countLabel(binding, R.string.media_preview_videos, MediaPreviewKind.VIDEOS)
        binding.btnGifs.text = countLabel(binding, R.string.media_preview_gifs, MediaPreviewKind.GIFS)
        binding.btnImages.isEnabled = selection.countOf(MediaPreviewKind.IMAGES) > 0
        binding.btnVideos.isEnabled = selection.countOf(MediaPreviewKind.VIDEOS) > 0
        binding.btnGifs.isEnabled = selection.countOf(MediaPreviewKind.GIFS) > 0
        binding.btnSelectAllMedia.isEnabled = !selection.allSelected
        binding.btnSelectNoneMedia.isEnabled = !selection.noneSelected

        adapter.updateSelection(selection.selected)

        positiveButton?.let { positive ->
            positive.text = context.getString(
                R.string.media_preview_download_selected,
                selection.selectedCount
            )
            positive.isEnabled = !selection.noneSelected
        }
    }

    private fun countLabel(
        binding: DialogMediaPreviewBinding,
        labelRes: Int,
        kind: MediaPreviewKind
    ): String = binding.root.context.getString(
        R.string.media_preview_count,
        binding.root.context.getString(labelRes),
        selection.countOf(kind)
    )
}
