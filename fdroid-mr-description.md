# MR Title (修改标题)

**New app: Tumblr Downloader**

---

# MR Description

## App Information

| Field | Value |
|-------|-------|
| **Name** | Tumblr Downloader |
| **Package** | `io.github.akarimarisa.tumblrdownloader` |
| **License** | MIT |
| **Source Code** | https://github.com/AkariMarisa/Tumblr-Downloader |
| **Issue Tracker** | https://github.com/AkariMarisa/Tumblr-Downloader/issues |
| **Donate** | https://ko-fi.com/E0C822IGF6 |

## Description

Download images and videos from Tumblr posts. Features include automatic clipboard detection for Tumblr URLs, share from other apps, built-in WebView login for private posts, download queue with pause/resume/retry, download history, built-in media viewer, custom save directory via SAF, Material Design 3 UI, multi-language support (EN/ZH), and network-aware auto-pause.

## Anti-Features

- **NonFreeNet**: Connects to Tumblr (non-free network service)

## Dependencies (all FOSS)

| Library | License |
|---------|---------|
| Retrofit | Apache 2.0 |
| OkHttp | Apache 2.0 |
| Glide | BSD/MIT |
| ExoPlayer | Apache 2.0 |
| Room | Apache 2.0 |
| Kotlin Coroutines | Apache 2.0 |
| Material Components | Apache 2.0 |

## Build Instructions

```bash
git clone https://github.com/AkariMarisa/Tumblr-Downloader.git
cd Tumblr-Downloader
./gradlew assembleFullRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

---

<!--Remove the above lines before submitting!-->

<!--Add the corresponding issue number or remove this if this merge request does not close an issue at rfp.-->

<!--Add the corresponding issue number or remove this if this merge request does not close an issue at fdroiddata.-->

/label ~"New App"
