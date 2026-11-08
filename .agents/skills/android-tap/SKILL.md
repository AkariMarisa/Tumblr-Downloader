---
name: android-tap
description: Tap Android elements or coordinates through .agents/tools/android input commands.
allowed-tools: Bash(.agents/tools/android:*), Bash(adb:*)
argument-hint: element or coordinates to tap
---

Default to semantic tapping:

1. If the target is described by text, resource-id, or content description, run `.agents/tools/android input tap-element ... --json`. Use `--by any` unless you already know which field the app uses — icon-only controls often expose only `content-desc`, so `--by text` can miss them.
2. If the user gave coordinates, run `.agents/tools/android input tap --x ... --y ... --json`.
3. After tapping, verify the result with `.agents/tools/android ui dump --json` or `.agents/tools/android wait element ... --json`.

If the target is not visible, switch to `android-scroll` before falling back to raw coordinates.
