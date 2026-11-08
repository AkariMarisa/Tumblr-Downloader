---
name: android-debug
description: Debug Android issues using .agents/tools/android device, screenshot, app, and debug commands.
allowed-tools: Bash(.agents/tools/android:*), Bash(adb:*)
argument-hint: issue to investigate
---

Debugging workflow:

1. Collect context with `.agents/tools/android device info --json`, `.agents/tools/android app current --json`, and `.agents/tools/android screenshot --json`.
2. If reproducing a bug, clear logs first with `.agents/tools/android debug clear-logs --json`. To reproduce from a clean app state, use `.agents/tools/android app clear --package <pkg> --json` before relaunching.
3. Collect logs with `.agents/tools/android debug logs --json`, adding `--package` and `--level` when relevant.
4. Correlate the logs with the current screen and the user's report.
5. Report:
   - what happened
   - the strongest evidence
   - likely root cause
   - the next debugging step

Use raw `adb` only for diagnostics not yet covered by the command layer.
