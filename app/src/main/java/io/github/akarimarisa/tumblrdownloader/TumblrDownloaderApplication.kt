package io.github.akarimarisa.tumblrdownloader

import android.app.Application
import android.content.Context
import io.github.akarimarisa.tumblrdownloader.utils.DownloadHistoryStore
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper
import io.github.akarimarisa.tumblrdownloader.utils.TumblrCookieStore

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