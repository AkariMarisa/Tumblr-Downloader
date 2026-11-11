package io.github.akarimarisa.tumblrdownloader.ui.download

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivityBlogDetailBinding
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.ui.MainViewModel
import io.github.akarimarisa.tumblrdownloader.ui.media.MediaViewerActivity
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import kotlinx.coroutines.launch

/**
 * Drill-down page for one blog batch job: shows only the download items
 * tagged with this job's [batchId], live-filtered from the shared queue.
 */
class BlogDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_BLOG_NAME = "blog_name"

        fun newIntent(context: Context, jobId: String, blogName: String): Intent =
            Intent(context, BlogDetailActivity::class.java)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_BLOG_NAME, blogName)
    }

    private lateinit var binding: ActivityBlogDetailBinding
    private val viewModel: MainViewModel by viewModels()

    private val adapter = DownloadsAdapter(
        onClick = ::openViewer,
        onStartOrResume = { item -> viewModel.startOrResumeDownload(item.id) },
        onPause = { item -> viewModel.pauseDownload(item.id) },
        onRemove = { item -> confirmRemoveDownload(item) }
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlogDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: run {
            finish()
            return
        }
        supportActionBar?.title =
            intent.getStringExtra(EXTRA_BLOG_NAME)?.ifBlank { null } ?: getString(R.string.blog_detail_title)

        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter

        // Batch controls: pause/start every unfinished item of this blog job
        // at once, instead of tapping each row.
        binding.btnPauseAll.setOnClickListener { viewModel.pauseBlogBatch(jobId) }
        binding.btnResumeAll.setOnClickListener { viewModel.resumeBlogBatch(jobId) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloads.collect { list ->
                    // Blog-wide predicate, same as the card (#10).
                    val blogName = intent.getStringExtra(EXTRA_BLOG_NAME)?.ifBlank { null }
                    val filtered = blogBatchOf(list, jobId, blogName)
                    adapter.submitList(filtered)
                    binding.tvEmpty.isVisible = filtered.isEmpty()
                    binding.tvDetailSummary.text = resources.getQuantityString(
                        R.plurals.blog_detail_item_count, filtered.size, filtered.size
                    )
                    // Only offer an action when there is anything it can act on.
                    val anyActive = filtered.any {
                        it.status == io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.DOWNLOADING ||
                            it.status == io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.QUEUED
                    }
                    val anyPaused = filtered.any {
                        it.status == io.github.akarimarisa.tumblrdownloader.model.DownloadStatus.PAUSED
                    }
                    binding.btnPauseAll.isEnabled = anyActive
                    binding.btnResumeAll.isEnabled = anyPaused
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmRemoveDownload(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_download)
            .setMessage(R.string.remove_confirm_message)
            .setPositiveButton(R.string.remove_confirm) { _, _ -> viewModel.removeDownload(item.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openViewer(item: DownloadItem) {
        startActivity(MediaViewerActivity.newIntent(this, item.mediaUrl, item.type.name))
    }
}
