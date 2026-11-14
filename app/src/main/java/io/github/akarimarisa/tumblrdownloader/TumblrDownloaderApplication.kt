package io.github.akarimarisa.tumblrdownloader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.github.akarimarisa.tumblrdownloader.model.ThemeModePrefs
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
        // SystemUI resolves the app label used by Android 13+ toasts from the
        // platform per-app locale, while older Android versions use the
        // wrapped context supplied from attachBaseContext.
        LocaleHelper.syncPlatformLocale(this)
        // Apply persisted theme mode (follow-system / light / dark) before any
        // Activity is created so DayNight picks the right resources.
        AppCompatDelegate.setDefaultNightMode(ThemeModePrefs.read(this).nightMode)
        registerClipboardListener(this)
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

    companion object {
        /**
         * Set by the process-wide clipboard listener, consumed by the next
         * focus-gained check.
         *
         * The listener lives here instead of in the Activity on purpose: the
         * Activity is routinely destroyed while the app sits in the
         * background, and its listener died with it — a link copied in
         * another app during that window produced no signal at all, so
         * re-copying the same link never re-triggered detection (#1).
         */
        @Volatile
        private var primaryClipChanged = false

        /** Published by the focused Activity so we can tell our own copies apart (#2). */
        @Volatile
        private var windowFocused = false

        /**
         * Description of the last copy made **by us** (long-press → copy in
         * the URL field while our window holds focus).
         *
         * Our own copy changes the clip description too, so without this the
         * description signal would treat it as an external copy on the next
         * focus-gained check and re-trigger detection for a link that is
         * already in the field (#19). Consumed by that check.
         */
        @Volatile
        private var selfCopyDescription: String? = null

        private var clipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

        /** Registers the listener once per process. */
        fun registerClipboardListener(context: Context) {
            if (clipListener != null) return
            val manager = context.applicationContext
                .getSystemService(android.content.ClipboardManager::class.java) ?: return
            val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                val focused = windowFocused
                android.util.Log.d("TumblrApp", "clipboard changed (ourWindowFocused=$focused)")
                // A copy made while our own window is focused comes from the
                // URL field (long-press → copy): re-parsing it would be the
                // false trigger reported as #2. Everything else — another app,
                // or our Activity having been destroyed — is worth reacting
                // to (#1).
                if (!focused) {
                    primaryClipChanged = true
                } else {
                    // Remember which description our own copy produced so the
                    // next check can tell it apart from a copy made in another
                    // app (#19). Reading is safe here: we hold focus.
                    selfCopyDescription = try {
                        manager.primaryClipDescription?.toString()
                    } catch (e: SecurityException) {
                        android.util.Log.w("TumblrApp", "self-copy desc read blocked: ${e.message}")
                        null
                    }
                    android.util.Log.d("TumblrApp", "selfCopyDesc=$selfCopyDescription")
                }
            }
            clipListener = listener
            manager.addPrimaryClipChangedListener(listener)
            android.util.Log.d("TumblrApp", "clipboard listener registered")
        }

        fun setWindowFocused(focused: Boolean) {
            windowFocused = focused
        }

        /** Reads and clears the change signal. */
        fun consumeClipChange(): Boolean {
            val changed = primaryClipChanged
            primaryClipChanged = false
            return changed
        }

        /** Reads and clears the description of our own last copy (#19). */
        fun consumeSelfCopyDescription(): String? {
            val desc = selfCopyDescription
            selfCopyDescription = null
            return desc
        }
    }
}
