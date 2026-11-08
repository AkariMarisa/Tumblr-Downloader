# Android Command Contract

This repo now centers on one executable interface:

```bash
.agents/tools/android ...
```

Skills and agent adapters should prefer this command surface over raw `adb` calls. Fall back to raw `adb` only when a needed operation is not implemented yet.

On Windows, invoke it as `tools\android.cmd ...` (or `python tools/android ...`); the examples below use the POSIX form.

## Design Rules

- Prefer `--json` for machine-readable output.
- Prefer `--device <id>` when the user named a device.
- If multiple devices are attached and no `--device` is provided, the command exits non-zero and lists candidates.
- Commands should fail with non-zero exit codes and a clear error message instead of returning ambiguous prose.

## Core Commands

### Device

```bash
.agents/tools/android device list --json
.agents/tools/android device info --device emulator-5554 --json
.agents/tools/android device avds --json
.agents/tools/android device start-emulator --avd-name Pixel_9 --json
```

### Connect (Wi-Fi / Tailscale)

```bash
.agents/tools/android connect wifi --json
.agents/tools/android connect ip --host 192.168.1.42 --json
.agents/tools/android connect tailscale --host my-phone --json
.agents/tools/android connect pair --host 192.168.1.42 --port 37123 --code 123456 --json
.agents/tools/android connect disconnect --host 192.168.1.42 --json
.agents/tools/android connect disconnect --json
```

- `connect wifi` needs the device on USB once: it reads the phone's Wi-Fi IP, switches
  adbd to TCP mode (`adb tcpip`), and connects. Afterwards the cable can be unplugged.
- `connect pair` covers Android 11+ Wireless debugging (Settings → Developer options →
  Wireless debugging → Pair device with pairing code); follow it with `connect ip` using
  the connect port shown on the device.
- `connect tailscale` accepts a Tailscale machine name (resolved through the `tailscale`
  CLI) or a `100.x` IP. The phone must be running the Tailscale app on the same tailnet
  and adbd must already listen on TCP (run `connect wifi` once while on USB).
- While USB stays plugged in, the same phone appears twice in `device list`; pass
  `--device <ip:port>` (or unplug the cable) for later commands.
- `device list --json` reports a `connection` field: `usb`, `tcp`, or `emulator`.
- Security: TCP adb stays enabled until reboot or `adb usb`. Only enable it on trusted
  networks; a Tailscale tailnet is fine, open Wi-Fi is not.

### Screenshots

```bash
.agents/tools/android screenshot --out /tmp/screen.png --json
```

### UI

```bash
.agents/tools/android ui dump --json
.agents/tools/android ui find --by text --value "Login" --json
.agents/tools/android ui find --by resource-id --value btn_login --json
.agents/tools/android ui find --by any --value "Alarm" --json
```

Selectors: `resource-id`, `text`, `content-desc`, or `any` (matches all three).
Prefer `any` when you have not inspected the UI yet — icon-only controls
frequently expose only `content-desc`, so a `text` selector silently misses them.

### Input

```bash
.agents/tools/android input tap --x 540 --y 1600 --json
.agents/tools/android input tap-element --by text --value "Login" --json
.agents/tools/android input text --text "user@example.com" --json
.agents/tools/android input key --key back --json
.agents/tools/android input swipe --x1 540 --y1 1800 --x2 540 --y2 600 --duration 300 --json
.agents/tools/android input long-press --x 540 --y 1600 --duration 600 --json
.agents/tools/android input double-tap --x 540 --y 1600 --gap 100 --json
```

`input text` only supports ASCII. The command fails fast for non-ASCII text and for a
literal `%s` (which Android's `input text` always converts to a space) instead of
sending corrupted input to the device.

### Wait / Scroll

```bash
.agents/tools/android wait element --by text --value "Home" --timeout 10000 --json
.agents/tools/android scroll find --by text --value "Privacy" --max-scrolls 10 --json
```

### App

```bash
.agents/tools/android app install --apk ./app/build/outputs/apk/debug/app-debug.apk --json
.agents/tools/android app launch --package com.example.app --json
.agents/tools/android app current --json
.agents/tools/android app stop --package com.example.app --json
.agents/tools/android app clear --package com.example.app --json
```

`app stop` force-stops the app. `app clear` wipes app data and force-stops it —
useful for reproducing bugs from a clean state before `app launch`.

### Debug

```bash
.agents/tools/android debug clear-logs --json
.agents/tools/android debug logs --package com.example.app --level E --lines 300 --json
```

## Skill Guidance

- `android`: route to the right command family and keep verifying after each step.
- `android-ui`: use `ui dump` / `ui find` and summarize the result.
- `android-tap`: use `input tap-element` first; only fall back to coordinate tap when needed.
- `android-navigate`: chain `ui find`, `input tap-element`, `wait element`, and screenshots.
- `android-test`: use command outputs as evidence, not inferred prose.
- `android-install`: install first, then launch explicitly; do not guess the package from unrelated shell output.

## Output Conventions

- Screenshots should return the saved path plus dimensions when available.
- UI commands should return `bestMatch` and `matches` in JSON mode.
- Wait/scroll commands should report elapsed time or scroll count.
- App/debug commands should preserve enough raw output to troubleshoot failures.
