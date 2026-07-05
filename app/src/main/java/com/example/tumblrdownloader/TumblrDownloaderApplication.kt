package com.example.tumblrdownloader

import android.app.Application
import android.content.Context
import com.example.tumblrdownloader.utils.DownloadHistoryStore
import com.example.tumblrdownloader.utils.LocaleHelper
import com.example.tumblrdownloader.utils.TumblrCookieStore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TumblrDownloaderApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Migrate old SharedPreferences data to Room (no-op if already migrated).
            DownloadHistoryStore.migrateFromSharedPrefs(this@TumblrDownloaderApplication)
            TumblrCookieStore.migrateFromSharedPrefs(this@TumblrDownloaderApplication)

            TumblrCookieStore.restoreIfNeeded(this@TumblrDownloaderApplication)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(base))
    }
}