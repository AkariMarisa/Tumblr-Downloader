---
name: android-install
description: Install and launch an Android APK through .agents/tools/android app commands.
allowed-tools: Bash(.agents/tools/android:*), Bash(adb:*), Glob
argument-hint: path or name of the APK to install
---

Install flow:

1. Resolve the APK path with `Glob` if needed.
2. Run `.agents/tools/android app install --apk <path> --json`.
3. If the command returns a package name, launch it with `.agents/tools/android app launch --package <package> --json`.
4. If the package name is unknown, stop and ask for it instead of guessing.
5. Verify launch with `.agents/tools/android app current --json` and `.agents/tools/android screenshot --json`.

Do not infer the package from unrelated shell output such as a recent package list.
