# Contributing to Tumblr Downloader

[🇨🇳 **中文贡献指南**](CONTRIBUTING_zh.md)

First of all, thank you for taking the time to contribute! 🎉

Whether you're fixing a bug, adding a feature, improving the docs, or answering questions, every contribution is appreciated. This guide explains how the project is organized and how to get your changes merged as smoothly as possible.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Building the App](#building-the-app)
  - [Running Tests](#running-tests)
- [Project Structure](#project-structure)
- [Coding Conventions](#coding-conventions)
- [Branching & Commits](#branching--commits)
- [Reporting Issues](#reporting-issues)
- [Submitting Pull Requests](#submitting-pull-requests)
- [Localization](#localization)
- [Questions?](#questions)

## Code of Conduct

This project is released with a [Contributor Code of Conduct](CODE_OF_CONDUCT.md) ([中文](CODE_OF_CONDUCT_zh.md)). By participating in this project, you agree to abide by its terms.

## Ways to Contribute

- **Report bugs** — open an issue with clear reproduction steps (see [Reporting Issues](#reporting-issues))
- **Suggest features** — describe the problem you're solving, not just the solution you have in mind
- **Fix bugs / implement features** — fork, branch, and open a pull request
- **Improve documentation** — this guide, the READMEs, or any other docs
- **Localize** — help keep the English and Chinese content in sync
- **Review pull requests** — meaningful, respectful reviews are always welcome

## Getting Started

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17+ |
| Android Studio | Hedgehog (2023.1.1) or later |
| Gradle | 8.4+ (wrapper included, no system Gradle needed) |
| Android SDK | platform 34 |
| Device / Emulator | Android 11 (API 30) or later |

### Building the App

```bash
# Clone your fork
git clone https://github.com/<your-username>/Tumblr-Downloader.git
cd Tumblr-Downloader

# Debug build (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Or use the helper script (sets up JAVA_HOME / ANDROID_HOME for local builds)
./scripts/build_local.sh

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running Tests

```bash
./gradlew test
```

Local unit tests run on the JVM (JUnit4 + Robolectric + Room in-memory) and cover Room DAOs and the SharedPreferences → Room migration, plus model and download-service logic. Please make sure your change doesn't break existing tests, and add tests for new behavior where practical.

## Project Structure

The app is a **single-activity + fragments** app following **MVVM**:

```
app/src/main/java/io/github/akarimarisa/tumblrdownloader/
├── MainActivity.kt / MainViewModel.kt   # Single-activity host + shared ViewModel
├── model/                               # DownloadItem, DownloadStateManager (sequential queue)
├── service/DownloadService.kt           # Foreground service performing downloads
├── ui/                                  # Fragments/Activities: download, auth, media, settings…
└── utils/                               # Parser, cookie/account stores, history/SAF helpers
```

See the [README](README.md) for the full file breakdown and architecture diagram.

## Coding Conventions

- **Language**: Kotlin, using the [official Kotlin code style](https://kotlinlang.org/docs/code-style-guide.html) (`kotlin.code.style=official` in `gradle.properties`)
- **Architecture**: MVVM, single-activity + fragments, shared `MainViewModel`
- **UI**: AndroidX + Material Components + ViewBinding
- **Async**: Kotlin coroutines — all queue state mutations go through `DownloadStateManager`'s single command channel
- **Storage**: Room for download history and cookies (already migrated from SharedPreferences)
- **Style**: match the surrounding code — 4-space indent, consistent naming, focused commits. (kotlin code style is the baseline; `ktlint`/`detekt` are not yet enforced by CI, so keep diffs tidy)

## Branching & Commits

Branch names follow `<type>/<description>`, for example:

- `feature/notification-actions`
- `fix/concurrent-downloads`
- `docs/fdroid-badge`

Commit messages use conventional prefixes: `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`. Keep each commit small and focused on one thing.

## Reporting Issues

Before opening an issue, please search the existing issues to avoid duplicates.

**Bug reports** should include:

- App version (Settings → About) and the Android version / device you're using
- The URL used, what you expected to happen, and what actually happened
- Steps to reproduce, as specific as possible
- Any relevant logs or screenshots

**Feature requests** should describe the problem you want to solve and how you imagine the feature working. Proposals that mention trade-offs and alternatives are especially welcome.

## Submitting Pull Requests

1. Fork the repo and create a branch off `master` (see [Branching & Commits](#branching--commits))
2. Make your changes — keep them small and focused
3. Build and test locally:

   ```bash
   ./gradlew assembleDebug test
   ```

4. Push the branch and open a pull request against `master`. CI builds a debug APK for every pull request.
5. In the PR description, explain **what** changed and **why**, and link any related issue.

**PR checklist**

- [ ] Branch based on the latest `master`
- [ ] Build passes (`./gradlew assembleDebug`)
- [ ] Tests pass, and new tests added when applicable (`./gradlew test`)
- [ ] Follows the existing code style and project structure
- [ ] Docs updated if user-facing behavior changed

## Localization

The project ships English and Simplified Chinese UI and docs, and they should stay in sync:

- UI strings: `app/src/main/res/values/strings.xml` ↔ `app/src/main/res/values-zh/strings.xml`
- Docs: `docs/README.md` ↔ `docs/README_zh.md`, `CONTRIBUTING.md` ↔ `CONTRIBUTING_zh.md`, `CODE_OF_CONDUCT.md` ↔ `CODE_OF_CONDUCT_zh.md`

## Questions?

- Open an [issue](https://github.com/AkariMarisa/Tumblr-Downloader/issues) — no question is too basic
- For the current status and roadmap, see the [To-Do](README.md#to-do) section of the README

Again, thank you for contributing! 💜