# Tumblr Downloader

## Overview

This Android application allows users to download images and videos from Tumblr posts. It supports Android 11 and later devices and is built using Kotlin.

## Features

- Download images and videos from Tumblr posts
- Save media to a local directory
- View downloaded media within the app
- Automatically detect and download Tumblr links from the clipboard

## Project Structure

```plaintext
TumblrDownloader/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/
│   │   │   │       └── example/
│   │   │   │           └── tumblrdownloader/
│   │   │   │               ├── MainActivity.kt
│   │   │   │               ├── DownloadService.kt
│   │   │   │               ├── MediaViewerActivity.kt
│   │   │   │               └── utils/
│   │   │   │                   ├── TumblrParser.kt
│   │   │   │                   └── DownloadUtils.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   └── activity_media_viewer.xml
│   │   │   │   ├── values/
│   │   │   │   │   └── strings.xml
│   │   │   │   └── drawable/
│   │   │   │       └── ic_launcher_background.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/
│   │           └── com/
│   │               └── example/
│   │                   └── tumblrdownloader/
│   │                       └── ExampleUnitTest.kt
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## To-Do

- [ ] **Private content cookie test path**: Add a settings/input entry to paste logged-in Tumblr cookies (`cookie_name=value; ...`) so parse attempts can test posts that currently return login-required/404.
- [ ] **Cookie persistence with privacy guardrails**: store cookies locally only, clear them on logout, and never upload to backend.
- [ ] **Download record persistence**: save queue and retry status to Room so tasks survive process kill/reinstall.
- [ ] **Adaptive rate-limit settings**: allow 500KB/s~2MB/s configurable and 1~1.5s inter-task gap.
- [ ] **Parse diagnostics panel**: show parsed media count, candidate source type, and reason when empty.

## Technologies

- **Kotlin**: Primary programming language
- **Android SDK**: For building Android applications
- **Retrofit**: For network requests
- **Glide**: For image loading and caching
- **ExoPlayer**: For video playback
- **Room**: For local database storage

## UI Prototypes

### Main Activity

![Main Activity](docs/prototypes/main_activity.html)

Main Activity contains two tabs:

**Download Tab** (default):
- **Input Field**: For manually entering Tumblr share links
- **Download Button**: To manually start downloading

**Downloads Tab**:
- **Media List**: Displays currently downloading and completed files
- Supports tapping to view images or videos

### Media Viewer Activity

![Media Viewer Activity](docs/prototypes/media_viewer_activity.html)

- **Media Display**: Shows the selected image or video
- **Back Button**: To return to the main activity

## Setup Instructions

1. Clone the repository
2. Open the project in Android Studio
3. Build and run the application on an Android device or emulator

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## License

This project is licensed under the MIT License.