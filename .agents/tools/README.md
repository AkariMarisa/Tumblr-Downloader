<p align="center">
  <img src="assets/logo.png" alt="Android ADB Control" width="240">
</p>

<h1 align="center">Android ADB Skill</h1>

<p align="center">
  <b>Control real Android phones and emulators from AI coding agents.</b><br>
  Agent skills on top of a tested adb command layer. No MCP server required.
</p>

<p align="center">
  <a href="https://github.com/Freespirits/android-control-skill/actions/workflows/test.yml">
    <img src="https://github.com/Freespirits/android-control-skill/actions/workflows/test.yml/badge.svg" alt="Test">
  </a>
</p>

<p align="center">
  🎬 <a href="assets/demo.mp4"><b>Watch the demo video</b></a>
</p>

---

## What is this?

Two layers that let a coding agent drive an Android device:

- `./tools/android`, a single Python file that wraps `adb` with a stable, JSON-first
  command surface: screenshots, UI inspection, element targeting, gestures, waits,
  app lifecycle, logs, and network connectivity.
- `skills/*/SKILL.md`, short task workflows that tell the agent how to compose those
  commands for navigation, testing, debugging, and installs.

The agent works at the task level. The commands stay deterministic and testable.

## Features

- Every command takes `--json`, so agents parse structured output instead of
  scraping prose.
- Find, tap, wait, and scroll by `resource-id`, `text`, `content-desc`, or `any`.
  Ranking prefers the smallest matching element, so taps land on the visible
  control instead of a clipped parent container.
- `connect wifi` switches a USB phone to network adb in one command.
  `connect tailscale` reaches it from anywhere on your tailnet.
- Verified on a Galaxy Z Fold6 running Android 16. The command layer handles
  multi-display screenshot output, slow UI dumps, and icon-only navigation rails.
- Runs on Linux, macOS, and Windows. A `tools\android.cmd` shim covers PowerShell,
  and SDK discovery checks `%LOCALAPPDATA%\Android\Sdk` and `.exe`/`.bat` tool
  names.
- CI runs the test suite against fake `adb` and `emulator` binaries, so no device
  is needed on the runner.

## Why a CLI instead of an MCP server?

The hard part of device automation is the command design, and a CLI carries it
better here: it is testable, versioned with the repo, and composable with ordinary
shell logic. Agents with shell access (Claude Code, Codex, Cursor, Copilot) call it
directly. If you later need MCP for a shell-less client, each tool can wrap one CLI
command.

## Prerequisites

- Android SDK with `adb` on your PATH, or `ANDROID_HOME` set
- Python 3 (`python3` on POSIX, `python` on Windows)
- A connected Android device or running emulator

```bash
# quick check (POSIX)
./tools/android device list --json
```

```powershell
# quick check (Windows)
tools\android.cmd device list --json
```

SDK discovery checks `ANDROID_HOME` and `ANDROID_SDK_ROOT`, then the default
install locations on macOS (`~/Library/Android/sdk`), Linux (`~/Android/Sdk`), and
Windows (`%LOCALAPPDATA%\Android\Sdk`).

## Command tour

```bash
# Devices & connectivity
./tools/android device list --json
./tools/android connect wifi --json                    # USB to network adb in one step
./tools/android connect tailscale --host my-phone --json
./tools/android connect pair --host 192.168.1.42 --port 37123 --code 123456

# See the screen
./tools/android screenshot --out screen.png --json
./tools/android ui dump --json
./tools/android ui find --by any --value "Login" --json

# Interact
./tools/android input tap-element --by any --value "Login" --json
./tools/android input text --text "user@example.com" --json
./tools/android input swipe --x1 540 --y1 1800 --x2 540 --y2 600
./tools/android input long-press --x 540 --y 1600 --json
./tools/android wait element --by any --value "Home" --timeout 10000 --json
./tools/android scroll find --by text --value "Privacy" --json

# Apps & debugging
./tools/android app install --apk ./app-debug.apk --json
./tools/android app launch --package com.example.app --json
./tools/android app clear --package com.example.app --json   # clean-state repro
./tools/android debug logs --package com.example.app --level E --json
```

Full contract: [`docs/command-contract.md`](docs/command-contract.md)

## Installation

Copy the core folders into your project:

```bash
cp -r docs skills tools /path/to/project/
```

Then add the adapter file for your agent:

| Agent | Adapter |
|---|---|
| Claude Code | `CLAUDE.md` |
| Codex | `AGENTS.md` |
| Cursor | `.cursor/rules/android-adb.mdc` |
| GitHub Copilot | `.github/copilot-instructions.md` |

## Skills

| Skill | Purpose |
|---|---|
| `android` | General orchestration |
| `android-connect` | Connect over Wi-Fi debugging or Tailscale |
| `android-screenshot` | Capture and inspect screenshots |
| `android-ui` | Dump and search the UI tree |
| `android-tap` | Tap elements or coordinates |
| `android-navigate` | Multi-step navigation with verification |
| `android-scroll` | Scroll to find off-screen elements |
| `android-gesture` | Swipe, long press, double tap |
| `android-test` | Run a test flow with evidence |
| `android-debug` | Collect logs and diagnose failures |
| `android-install` | Install and launch an APK |
| `android-device` | Device and emulator management |

Example usage:

```text
/android open Settings and navigate to Privacy
/android-connect connect to my phone over tailscale
/android-test login with user@example.com and verify the home screen
/android-debug crashes in com.example.app after tapping Settings
```

## Development workflow

[`docs/ai-development-workflow.md`](docs/ai-development-workflow.md) covers using
this repo inside an Android app repo: edit, build, install, navigate, verify,
iterate.

## Testing

```bash
python3 -m unittest discover -s tests -v
```

CI runs the suite on every push via
[`.github/workflows/test.yml`](.github/workflows/test.yml). Tests use fake
`adb` and `emulator` binaries, so no SDK or device is required. The fakes are
POSIX shebang scripts, so on Windows the integration tests skip and the unit
tests run; CI covers the full suite.

## Repo structure

```text
android-control-skill/
├── tools/
│   ├── android              # the command layer (single-file Python CLI)
│   └── android.cmd          # Windows shim
├── docs/
│   ├── command-contract.md
│   └── ai-development-workflow.md
├── tests/
│   └── test_tools_android.py
├── skills/                  # android-* agent skills
├── assets/                  # logo & demo video
├── AGENTS.md · CLAUDE.md · .cursor/ · .github/
└── README.md
```

## Credits

Original project by [Amit Nayar](https://github.com/amit-nayar):
[amit-nayar/android-adb-skill](https://github.com/amit-nayar/android-adb-skill).
This fork adds Windows support, real-device fixes, gesture and app lifecycle
commands, and Wi-Fi/Tailscale connectivity.

## License

MIT
