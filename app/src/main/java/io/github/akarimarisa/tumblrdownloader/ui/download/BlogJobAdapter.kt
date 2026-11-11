package io.github.akarimarisa.tumblrdownloader.ui.download

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ItemBlogJobBinding
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import io.github.akarimarisa.tumblrdownloader.model.BlogJobState
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem

/**
 * UI state for one blog-job card: the durable [BlogDownloadJobEntity] plus
 * live aggregates of its batch download items.
 *
 * A card represents a *full* (subscription-style) blog download, so its state
 * follows the media-download lifecycle — parsing → downloading → completed —
 * and only reports "completed" once every media file has actually downloaded.
 */
data class BlogCardUi(
    val job: BlogDownloadJobEntity,
    /** The scan worker is currently parsing this blog's feed. */
    val active: Boolean,
    /** Any batch item is downloading or queued to download right now. */
    val running: Boolean,
    /** Any batch item is paused (this blog's downloads were paused). */
    val paused: Boolean,
    /** Batch items already downloaded. */
    val completed: Int,
    /** Batch items currently tracked for this job. */
    val total: Int
) {
    val done: Boolean get() = job.state() == BlogJobState.DONE
    /** No batch items (everything already downloaded → nothing left) or every item completed. */
    val completedAll: Boolean get() = done && (total == 0 || completed >= total)
}

/** Percent (−1 = indeterminate) for the card's progress bar. */
data class BlogCardProgress(val percent: Int, val indeterminate: Boolean)

/**
 * Progress for a card: media (completed/total) wins as soon as there is
 * anything to count.
 *
 * While the scan runs, the posts-seen percent sits at 0 for a large blog —
 * `totalPosts` is an API upper bound and integer division quantizes to 0 — so
 * preferring it made the bar look frozen until Pause flipped the card out of
 * the "active" branch (#8). Media progress is capped at 99 until the job is
 * done, so the bar never claims completion while the scan is still running.
 */
fun blogCardProgress(ui: BlogCardUi, postsSeen: Int): BlogCardProgress {
    val mediaPct = if (ui.total > 0) ui.completed * 100 / ui.total else -1
    val percent = when {
        mediaPct >= 0 && ui.active -> mediaPct.coerceIn(0, 99)
        mediaPct >= 0 -> if (ui.completedAll) 100 else mediaPct.coerceIn(0, 99)
        ui.active -> scanProgressPercent(ui.job, postsSeen)
        ui.job.state() == BlogJobState.DONE -> 100
        else -> scanProgressPercent(ui.job, postsSeen)
    }
    return BlogCardProgress(percent, percent < 0)
}

/** Posts-seen progress, or −1 while `totalPosts` is still unknown. */
fun scanProgressPercent(job: BlogDownloadJobEntity, postsSeen: Int): Int =
    if (job.totalPosts != null && job.totalPosts > 0) {
        (postsSeen * 100 / job.totalPosts).coerceIn(0, 99)
    } else {
        -1
    }

/**
 * Rows that back this blog card: its own batch plus leftovers from an earlier
 * card of the same blog.
 *
 * Removing a card keeps its completed rows (they are what the files on disk
 * are counted against) under the old `batchId`, and re-adding the blog mints a
 * fresh `jobId` — a job-scoped filter hid all of them, so the card reported
 * 46 completed while 86 files existed (#10).
 */
fun blogBatchOf(
    items: List<DownloadItem>,
    jobId: String,
    blogName: String?
): List<DownloadItem> = items.filter {
    it.batchId == jobId ||
        (blogName != null && it.batchId != null && it.blogName == blogName)
}

class BlogJobAdapter(
    private val onDetail: (BlogDownloadJobEntity) -> Unit,
    private val onResume: (BlogDownloadJobEntity) -> Unit,
    private val onPause: (BlogDownloadJobEntity) -> Unit,
    private val onRemove: (BlogDownloadJobEntity) -> Unit
) : ListAdapter<BlogCardUi, BlogJobAdapter.JobVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobVH {
        val binding = ItemBlogJobBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return JobVH(binding)
    }

    override fun onBindViewHolder(holder: JobVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class JobVH(private val binding: ItemBlogJobBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(ui: BlogCardUi) {
            val ctx = binding.root.context
            val job = ui.job
            binding.tvBlogName.text = job.blogName

            // State chip — the card follows the full-download lifecycle:
            // parsing → downloading (with progress) → completed only when
            // every media file is down; paused/error interrupt the chain.
            val state = job.state()
            val (labelRes, colorRes) = when {
                ui.active -> Pair(R.string.blog_job_state_parsing, R.color.green_500)
                state == BlogJobState.BLOCKED -> Pair(R.string.blog_job_state_blocked, R.color.red_500)
                state == BlogJobState.CANCELLED -> Pair(R.string.blog_job_state_paused, R.color.gray_500)
                ui.completedAll -> Pair(R.string.blog_job_state_done, R.color.blue_500)
                ui.done && ui.paused -> Pair(R.string.blog_job_state_paused, R.color.gray_500)
                ui.done && ui.running -> Pair(R.string.blog_job_state_downloading, R.color.green_500)
                ui.done && ui.completed == 0 -> Pair(R.string.blog_job_state_parsed, R.color.teal_700)
                ui.done -> Pair(R.string.blog_job_state_downloading, R.color.green_500)
                else -> Pair(R.string.blog_job_interrupted, R.color.yellow_600)
            }
            binding.tvState.text = ctx.getString(labelRes)
            ViewCompat.setBackgroundTintList(
                binding.tvState,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))
            )

            val posts = job.postsDiscovered + job.postsSkipped + job.postsNoMedia
            binding.tvStats.text = ctx.resources.getQuantityString(
                R.plurals.blog_job_stats, posts, posts, job.mediaEnqueued
            )

            // Progress bar — see [blogCardProgress] for the precedence rules.
            val progress = blogCardProgress(ui, posts)
            binding.pbProgress.isIndeterminate = progress.indeterminate
            if (progress.percent >= 0) binding.pbProgress.progress = progress.percent

            // Download summary line under the bar (media done / media total).
            binding.tvSummary.isVisible = false
            when {
                ui.completedAll && ui.total > 0 -> binding.tvSummary.apply {
                    text = ctx.getString(R.string.blog_job_download_complete, ui.total)
                    isVisible = true
                }
                // Shown for every state — including while the scan is still
                // running — so the numbers are readable instead of appearing
                // only after a pause (#8).
                ui.total > 0 -> binding.tvSummary.apply {
                    text = ctx.getString(R.string.blog_job_download_progress, ui.completed, ui.total)
                    isVisible = true
                }
            }

            binding.tvError.isVisible = job.errorCode != null && state == BlogJobState.BLOCKED
            binding.tvError.text = when (job.errorCode) {
                "password_protected" -> ctx.getString(R.string.blog_error_password_protected)
                "not_found" -> ctx.getString(R.string.blog_error_not_found)
                "rate_limited" -> ctx.getString(R.string.blog_error_rate_limited)
                "network" -> ctx.getString(R.string.blog_error_network)
                else -> ctx.getString(R.string.blog_error_internal)
            }

            binding.btnDetail.setOnClickListener { onDetail(job) }

            // Direct Pause on the card while the scan or its downloads are
            // running; Start whenever there is still work and none of it is
            // in flight (paused / interrupted / partial / retry).
            binding.btnCancel.isVisible = ui.active || ui.running
            binding.btnCancel.setOnClickListener { onPause(job) }
            val canResume = !ui.active && !ui.running && !ui.completedAll && run {
                state != BlogJobState.DONE || (ui.total > 0 && ui.completed < ui.total)
            }
            binding.btnResume.isVisible = canResume
            binding.btnResume.setOnClickListener { onResume(job) }
            // Remove stays available in every state: the manager cancels the
            // scan, finishes a pending confirmation and keeps completed rows
            // (a confirm dialog guards the action), so hiding it while the job
            // was running only made it look like the button was broken (#9).
            binding.btnRemove.isVisible = true
            binding.btnRemove.setOnClickListener { onRemove(job) }
        }

    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlogCardUi>() {
            override fun areItemsTheSame(oldItem: BlogCardUi, newItem: BlogCardUi) =
                oldItem.job.jobId == newItem.job.jobId

            override fun areContentsTheSame(oldItem: BlogCardUi, newItem: BlogCardUi) =
                oldItem == newItem
        }
    }
}
