# JustProxy distribution artifacts

This directory contains the v0.3.0-beta.1 test release:

- `android/JustProxy-android-0.3.0-beta.1-debug.apk`: debug-signed Android 8.0+ sideload build with the one-peer WireGuard gateway, optional legacy proxy, and disabled-by-default Shizuku IP-rotation beta.
- `python/justproxy_client-0.3.0b1-py3-none-any.whl`: Python 3.9+ control API client wheel with `rotate_ip()`.
- `python/justproxy_client-0.3.0b1.tar.gz`: Python client source distribution, not the complete JustProxy repository source.
- `SHA256SUMS.txt`: matching SHA-256 digests for all three release artifacts.

The custom Windows desktop executable is no longer distributed. Install an official WireGuard client separately from [wireguard.com/install](https://www.wireguard.com/install/), then import the standard `.conf` exported by the Android app. The Python wheel remains the supported scriptable status/control interface.

These are pre-1.0 test builds. The APK is not a Play Store production build, so Android may display a sideloading or debug-signing warning. A beta APK from a different build runner may have a different debug-signing identity. If Android rejects an update, uninstall the old test build first; this clears JustProxy settings, analytics, credentials, its stored peer, and any pending Shizuku recovery marker. Turn mobile data on and export anything you need before uninstalling. Download artifacts only from the `justdataplease/justproxy` repository or its GitHub Releases page and verify their matching checksums before installing them.

Shizuku is not included in the APK. Install it only from an [official Shizuku download](https://shizuku.rikka.app/download/) and follow the [official setup guide](https://shizuku.rikka.app/guide/setup/). The feature requires cellular-only egress and explicit permission, uses a 1-second default data-off time (allowed 1-10 seconds) and a 1-1440-minute interval, and must be started again after each non-root phone reboot. A carrier may return the same IP. If Android or Shizuku fails during the off window, turn mobile data on manually.

On PowerShell, calculate the APK digest with:

```powershell
Get-FileHash .\android\JustProxy-android-0.3.0-beta.1-debug.apk -Algorithm SHA256
```

Compare the result with the `SHA256SUMS.txt` published in the same v0.3.0-beta.1 release. The exported WireGuard `.conf` is not a public distribution artifact: it contains a client private key and must be transferred and stored securely.

See the repository [README](../README.md) for official-client import steps, same-LAN/hotspot requirements, cellular fail-closed behavior, endpoint/firewall limitations, exact Shizuku setup and recovery limitations, separate `/v1/rotate` and `/v1/ip-rotate` actions, Python usage, and the real-Pixel smoke-test checklist.
