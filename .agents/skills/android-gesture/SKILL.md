---
name: android-gesture
description: Perform Android gestures through .agents/tools/android input commands.
allowed-tools: Bash(.agents/tools/android:*), Bash(adb:*)
argument-hint: gesture to perform
---

Map requests to command-layer actions:

- tap: `input tap`
- long press: `input long-press` (default 600ms, tune with `--duration`)
- swipe / drag: `input swipe`
- double tap: `input double-tap` (tune tap spacing with `--gap`)

If the user names an element instead of coordinates, resolve it first with `ui find` or `input tap-element`.

After the gesture, verify with `ui dump` or `screenshot`.
