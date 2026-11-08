---
name: android-connect
description: Connect to Android devices over Wi-Fi or Tailscale through .agents/tools/android connect commands.
allowed-tools: Bash(.agents/tools/android:*), Bash(adb:*), Bash(tailscale:*)
argument-hint: how to reach the device (wifi, tailscale machine name, ip)
---

Pick the flow by what is available:

1. Device plugged in over USB: `.agents/tools/android connect wifi --json`. The cable can be unplugged afterwards.
2. Device shows a Wireless debugging pairing code: `.agents/tools/android connect pair --host <ip> --port <pairing-port> --code <code> --json`, then `.agents/tools/android connect ip --host <ip> --port <connect-port> --json`.
3. Device on the user's tailnet: `.agents/tools/android connect tailscale --host <machine-name-or-100.x-ip> --json`. Requires adbd already in TCP mode (run `connect wifi` once while on USB).
4. Known IP: `.agents/tools/android connect ip --host <ip> --json`.

After connecting:

- Verify with `.agents/tools/android device list --json`: the endpoint must have state `device` and connection `tcp`.
- While more than one device is listed (for example USB plus Wi-Fi for the same phone), pass `--device <ip:port>` to every later command.
- Disconnect with `.agents/tools/android connect disconnect --json`.

Failure handling:

- A Tailscale peer that is offline cannot be reached; ask the user to open the Tailscale app on the phone.
- If `connect ip` times out, adbd is probably not in TCP mode; fall back to flow 1 or 2.
- Remind the user that TCP adb stays enabled until reboot or `adb usb`, so it should only be enabled on trusted networks.
