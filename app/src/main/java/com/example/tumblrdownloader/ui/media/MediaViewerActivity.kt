package com.example.tumblrdownloader.ui.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.tumblrdownloader.databinding.ActivityMediaViewerBinding
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer

class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private var player: SimpleExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(KEY_MEDIA_URL).orEmpty()
        val type = intent.getStringExtra(KEY_TYPE)

        binding.btnBack.setOnClickListener { finish() }

        if (url.isBlank()) {
            finish()
            return
        }

        when (type) {
            TYPE_VIDEO -> showVideo(url)
            else -> showImage(url)
        }
    }

    private fun showImage(url: String) {
        binding.playerView.visibility = View.GONE
        binding.ivMedia.visibility = View.VISIBLE
        Glide.with(this).load(url)
            .error(android.R.drawable.ic_menu_report_image)
            .into(binding.ivMedia)
    }

    private fun showVideo(url: String) {
        binding.ivMedia.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        player = SimpleExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val KEY_MEDIA_URL = "media_url"
        private const val KEY_TYPE = "media_type"
        private const val TYPE_VIDEO = "VIDEO"

        fun newIntent(context: Context, mediaUrl: String, mediaType: String): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(KEY_MEDIA_URL, mediaUrl)
                putExtra(KEY_TYPE, mediaType)
            }
        }
    }
}
