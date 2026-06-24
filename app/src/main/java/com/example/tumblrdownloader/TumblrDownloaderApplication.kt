package com.example.tumblrdownloader

import android.app.Application
import com.example.tumblrdownloader.utils.TumblrCookieStore

class TumblrDownloaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TumblrCookieStore.restoreIfNeeded(this)
    }
}