package com.example.tumblrdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.tumblrdownloader.model.DownloadItem
import com.example.tumblrdownloader.model.MediaType
import com.example.tumblrdownloader.utils.DownloadUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        val sourceUrl = intent?.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        val mediaUrl = intent?.getStringExtra(EXTRA_MEDIA_URL).orEmpty()
        val type = runCatching {
            MediaType.valueOf(intent?.getStringExtra(EXTRA_TYPE).orEmpty())
        }.getOrDefault(MediaType.UNKNOWN)

        val item = DownloadItem(
            id = itemId,
            sourceUrl = sourceUrl,
            mediaUrl = mediaUrl,
            title = sourceUrl.substringAfterLast('/').ifBlank { "Tumblr Media" },
            type = type
        )

        startForeground(NOTIFICATION_ID, buildNotification(item.title, "Queued"))

        serviceScope.launch {
            simulateDownload(item)
        }

        return START_STICKY
    }

    private suspend fun simulateDownload(item: DownloadItem) {
        repeat(5) { step ->
            delay(400)
            val progress = (step + 1) * 20
            val title = "Downloading: ${item.title}"
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, "${progress}%"))
        }
        val file = File(DownloadUtils.ensureDownloadDir(this), "${item.id}.bin")
        runCatching { URL(item.mediaUrl).openConnection().openInputStream().use { it.close() } }
            .onFailure {
                // placeholder: keep file placeholder anyway
                file.writeText("")
            }
        stopSelf()
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tumblr Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download status updates"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "tumblr_download_channel"
        private const val NOTIFICATION_ID = 10001

        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_SOURCE_URL = "extra_source_url"
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_TYPE = "extra_type"
    }
}
