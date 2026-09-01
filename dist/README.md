# JustProxy distribution artifacts

This directory contains the alpha test artifacts produced from this repository:

- `android/JustProxy-android-debug.apk`: debug-signed Android 8.0+ sideload build.
- `windows/JustProxyDesktop.exe`: unsigned, one-file Windows companion.
- `python/justproxy_client-0.1.0-py3-none-any.whl`: Python 3.9+ wheel.
- `python/justproxy_client-0.1.0.tar.gz`: Python client source distribution, not the complete JustProxy repository source.
- `SHA256SUMS.txt`: SHA-256 digests for release artifacts.

These are pre-1.0 test builds. The APK is not a Play Store production build and the Windows executable has no publisher certificate, so Android, Windows, or SmartScreen may display warnings. Download artifacts only from the `justdataplease/justproxy` repository or its GitHub Releases page and verify their checksum before installing or running them.

On PowerShell, calculate a downloaded file's digest with:

```powershell
Get-FileHash .\JustProxyDesktop.exe -Algorithm SHA256
```

Compare the result with `SHA256SUMS.txt`. See the repository [README](../README.md) for phone setup, USB forwarding, authenticated proxy configuration, Python usage, limitations, and build/test instructions.
