package com.example.tumblrdownloader

import android.app.Application
import android.content.Context
import com.example.tumblrdownloader.utils.LocaleHelper
import com.example.tumblrdownloader.utils.TumblrCookieStore

class TumblrDownloaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TumblrCookieStore.restoreIfNeeded(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(base))
    }
}