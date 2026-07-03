# Tumblr Downloader

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-30-brightgreen)](https://developer.android.com/studio)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin)](https://kotlinlang.org)

An Android app that downloads images and videos from Tumblr posts. Supports Android 11+.

<p align="center">
  <img src="logo.png" alt="Tumblr Downloader" width="128" height="128">
</p>

---

## Features

- **Download media** from Tumblr posts — images and videos
- **Multiple sources**: paste a link, share from other apps, or auto-detect from clipboard
- **Smart parsing**: extracts media from Tumblr's oEmbed API and page HTML; supports `.pnj` high-quality images
- **Login flow**: built-in WebView-based login to access private/restricted posts
- **Cookie persistence**: login cookies stored locally with security notice; survives app restart
- **Download queue**: sequential download with pause/resume, retry, and rate limiting
- **History**: download records survive app restarts
- **Media viewer**: view downloaded images and videos in-app
- **Custom download directory**: save to any folder via Storage Access Framework
- **Multi-language**: English and Chinese UI

---

## Screenshots

### English UI

| Download | Downloads | Navigation | Settings | About |
|---|---|---|---|---|
| <img src="screenshots/download_en.png" width="140" alt="Download"> | <img src="screenshots/downloads_en.png" width="140" alt="Downloads"> | <img src="screenshots/navdrawer_en.png" width="140" alt="Navigation"> | <img src="screenshots/settings_en.png" width="140" alt="Settings"> | <img src="screenshots/about_en.png" width="140" alt="About"> |

### Chinese UI (中文界面)

| Download | Downloads | Navigation | Settings | About |
|---|---|---|---|---|
| <img src="screenshots/download_zh.png" width="140" alt="Download"> | <img src="screenshots/downloads_zh.png" width="140" alt="Downloads"> | <img src="screenshots/navdrawer_zh.png" width="140" alt="Navigation"> | <img src="screenshots/settings_zh.png" width="140" alt="Settings"> | <img src="screenshots/about_zh.png" width="140" alt="About"> |

---

## Project Structure

```
app/src/main/java/com/example/tumblrdownloader/
├── TumblrDownloaderApplication.kt      # App entry point
├── MainActivity.kt                     # Single-activity host with TabLayout
├── MainViewModel.kt                    # Shared ViewModel bridging UI and state
├── model/
│   ├── DownloadItem.kt                 # Data class for a single download
│   ├── DownloadStateManager.kt         # Sequential command processor for queue
│   └── MediaType / DownloadStatus      # Enums
├── service/
│   └── DownloadService.kt              # Foreground service for actual downloads
├── ui/
│   ├── download/
│   │   ├── DownloadFragment.kt         # Input URL + trigger download
│   │   ├── DownloadsFragment.kt        # Download queue list
│   │   └── DownloadsAdapter.kt         # RecyclerView adapter
│   ├── auth/
│   │   └── TumblrLoginActivity.kt      # WebView-based Tumblr login
│   ├── media/
│   │   └── MediaViewerActivity.kt      # View downloaded images/videos
│   ├── settings/
│   │   └── SettingsActivity.kt         # Download dir, cookies, language
│   ├── about/
│   │   └── AboutActivity.kt
│   └── OnboardingActivity.kt           # First-launch walkthrough
└── utils/
    ├── TumblrParser.kt                 # Parse share URLs → media URLs
    ├── TumblrCookieStore.kt            # Persist/restore WebView cookies
    ├── TumblrAccountStore.kt           # Fetch & cache account info
    ├── DownloadUtils.kt                # File naming, directory helpers
    ├── DownloadHistoryStore.kt         # Persist download queue to disk
    ├── CompletedMediaStore.kt          # Track already-downloaded files
    └── LocaleHelper.kt                 # Per-app language override
```

---

## Architecture

The app follows a **single-activity + fragments** pattern with **MVVM**.

```
User Input → DownloadFragment
                  ↓
           MainViewModel
                  ↓
    DownloadStateManager (sequential command queue)
          ↙            ↘
  TumblrParser      DownloadService
  (URL → media)     (foreground, HTTP)
                        ↓
                  MediaStore / SAF
```

Key design decisions:

- **Sequential command processor** (`DownloadStateManager`): All state mutations go through a single coroutine channel, eliminating race conditions between user actions and download progress callbacks.
- **Two-phase parsing**: First tries Tumblr's oEmbed API (lightweight), then falls back to full page HTML parsing. JSON-structured data uses path-based filtering rather than fragile extension matching.
- **Cookie bridge**: WebView login cookies are persisted via `CookieManager` and shared with the OkHttp client used for parsing and downloading.

---

## Requirements

| Requirement | Version |
|---|---|
| Android | 11+ (API 30) |
| JDK | 17+ |
| Android Studio | Hedgehog (2023.1.1) or later |
| Gradle | 8.4+ |

---

## Build & Install

### Using Android Studio

1. Clone the repo:
   ```bash
   git clone https://github.com/AkariMarisa/Tumblr-Downloader.git
   ```
2. Open the project in Android Studio.
3. Let Gradle sync complete.
4. Select a physical device or emulator (API 30+) and run.

### Using command line

```bash
# Build debug APK
./scripts/build_local.sh

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** There is no Gradle wrapper (`gradlew`) in the repo — use the system `gradle` command or the bundled `scripts/build_local.sh` which sets the required `JAVA_HOME` and `ANDROID_HOME`.

---

## Usage

1. Open the app.
2. **Paste a Tumblr share URL** into the input field and tap **Download**.
3. Or **share a link** from another app (browser, Tumblr app) — the app will auto-detect it.
4. The app parses the URL and adds found media to the download queue.
5. Switch to the **Downloads** tab to monitor progress.
6. Tap a completed item to view the media.

### Login

For private or restricted posts:
1. Tap **Download** — the app will detect that login is required and open the login screen.
2. Sign in to your Tumblr account in the WebView.
3. Once logged in, the app saves the session cookies and retries the download automatically.

---

## Tech Stack

| Library | Purpose |
|---|---|
| [Kotlin](https://kotlinlang.org) | Language |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Core, AppCompat, Fragment, Lifecycle, ViewPager2, RecyclerView |
| [Material Components](https://material.io/develop/android) | UI components |
| [OkHttp](https://square.github.io/okhttp/) | HTTP client for Tumblr API & media download |
| [Retrofit](https://square.github.io/retrofit/) | (Available for future API integration) |
| [Glide](https://github.com/bumptech/glide) | Image loading & caching |
| [ExoPlayer](https://developer.android.com/media/media3/exoplayer) | Video playback |
| [Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) | Async operations |
| [ViewBinding](https://developer.android.com/topic/libraries/view-binding) | Type-safe view access |
| [Room](https://developer.android.com/training/data-storage/room) | (Available for future schema-based storage) |

---

## To-Do

### Reliability

- [ ] **Clipboard auto-detect**: The clipboard monitoring often fails to trigger or triggers duplicate downloads. Needs a more robust listener with debounce and dedup.
- [ ] **Retry on network change**: Pause downloads when WiFi disconnects, auto-resume on reconnection.
- [ ] **Cookie persistence race**: WebView login cookies don't always sync to the OkHttp client before the retry fires. Add a ready check before re-parsing.
- [ ] **SharedPreferences corruption**: Download history and cookie store use plain JSON in SharedPreferences — can break on concurrent writes or crash during save. Migrate to Room or a transactional store.
- [ ] **Adult content detection**: Posts behind Tumblr's "possible adult content" warning need an extra confirmation step; improve `looksLikeLoginPage` to handle this edge case.

### Features

- [ ] **Parallel downloads**: Currently sequential only (one at a time). Allow configurable parallel downloads (2-3).
- [ ] **Batch download**: Download all media from a blog, tag, or collection of posts.
- [ ] **Video quality selection**: Let users prefer 480p / 720p / 1080p when multiple renditions exist.
- [ ] **Download speed display**: Show real-time speed (KB/s) in notifications and download list.
- [ ] **Dark mode**: Follow system theme or allow manual toggle.
- [ ] **Search & filter**: Search by URL, filter by status (downloading / completed / failed) in the download list.
- [ ] **Notification actions**: Pause / cancel download directly from the notification.
- [ ] **Export / import history**: Backup and restore download history across devices.
- [ ] **Save to gallery**: Option to save completed downloads to the device gallery.

### Parse & Download Engine

- [ ] **MIME type fallback**: For URLs with unknown extensions, send a HEAD request to check `Content-Type` before falling back to `application/octet-stream`.
- [ ] **Parse diagnostics panel**: Show why parsing returned no results (404 / rate-limited / auth required / no media found).
- [ ] **Adaptive rate-limit settings**: Configurable speed limit (500 KB/s – 2 MB/s) and inter-task gap.
- [ ] **Cookie test entry**: Manual cookie paste in Settings for debugging private posts without re-login.
- [ ] **Refactor `inferExtension`**: The same logic appears in both `DownloadService.kt` and `DownloadStateManager.kt` — extract into a shared utility.

### Open Source & Project Health

- [ ] **LICENSE file**: Create the actual MIT `LICENSE` file in the repo root (README references it but the file doesn't exist).
- [ ] **Gradle wrapper**: Add `gradlew` so builds don't require a system Gradle installation.
- [ ] **CI/CD**: GitHub Actions for automated build, lint, and test on every PR.
- [ ] **Package name**: Rename from `com.example` to a proper namespace before publishing.
- [ ] **Tests**: Add unit tests for `TumblrParser`, `DownloadStateManager`, and instrumentation tests for the download flow.
- [ ] **Contributing guide**: Create `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`.
- [x] **Screenshots**: Add app screenshots to README.
- [ ] **Play Store / F-Droid**: Prepare release signing and store listings.

---

## Contributing

Contributions are welcome! Feel free to open an issue or submit a pull request.

Before contributing, please read our [Contributing Guidelines](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md).

### Development

```bash
# Fork and clone
git clone https://github.com/your-username/Tumblr-Downloader.git
cd Tumblr-Downloader

# Build
./scripts/build_local.sh

# Run tests (when available)
./gradlew test
```

---

## License

This project is licensed under the MIT License. See the [LICENSE](../LICENSE) file for details.
