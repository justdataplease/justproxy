# JustProxy distribution artifacts

This directory contains the v0.2.0-beta.1 test artifacts produced from this repository:

- `android/JustProxy-android-debug.apk`: debug-signed Android 8.0+ sideload build with the one-peer WireGuard gateway beta and optional legacy proxy.
- `python/justproxy_client-0.2.0b1-py3-none-any.whl`: Python 3.9+ control API client wheel.
- `python/justproxy_client-0.2.0b1.tar.gz`: Python client source distribution, not the complete JustProxy repository source.
- `SHA256SUMS.txt`: SHA-256 digests for release artifacts.

The custom Windows desktop executable is no longer distributed. Install an official WireGuard client separately from [wireguard.com/install](https://www.wireguard.com/install/), then import the standard `.conf` exported by the Android app.

These are pre-1.0 test builds. The APK is not a Play Store production build, so Android may display a sideloading or debug-signing warning. A beta APK from a different build runner may have a different debug-signing identity. If Android rejects an update, uninstall the old test build first; this clears JustProxy settings, analytics, credentials, and its stored peer. Download artifacts only from the `justdataplease/justproxy` repository or its GitHub Releases page and verify their checksums before installing them.

On PowerShell, calculate the APK digest with:

```powershell
Get-FileHash .\android\JustProxy-android-debug.apk -Algorithm SHA256
```

Compare the result with `SHA256SUMS.txt`. The exported WireGuard `.conf` is not a public distribution artifact: it contains a client private key and must be transferred and stored securely.

See the repository [README](../README.md) for official-client import steps, same-LAN/hotspot requirements, cellular fail-closed behavior, endpoint/firewall limitations, legacy proxy and USB setup, Python usage, and build/test instructions.
