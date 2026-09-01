# JustProxy

JustProxy turns an Android phone into an authenticated HTTP/HTTPS CONNECT and SOCKS5 proxy for a computer. Proxy-aware PC traffic enters over USB, a trusted Wi-Fi LAN, or the phone hotspot, then exits through the Android network selected in the app.

> Alpha distribution: the Android APK is debug-signed, the Windows executable is unsigned, and the Python package is pre-1.0. These artifacts are for testing, not a production or app-store release. Read the security and limitations sections before using LAN mode.

## What is included

| Component | Purpose |
| --- | --- |
| Android app | Runs the proxy, pins egress to cellular when requested, rotates sessions, and stores local traffic/IP analytics |
| Windows desktop app | Graphical status, traffic, public-IP, reconnect, and setup controls |
| Python package and CLI | Scriptable authenticated control API client |
| Distribution folder | Test APK, unsigned Windows executable, Python wheel/source archive, and checksums |

JustProxy is designed for a phone and PC you own or are authorized to use. It is not a residential-proxy marketplace, bandwidth-sharing client, interception tool, or public relay.

## How it works

~~~text
PC application
  |
  | HTTP proxy or SOCKS5 (authenticated)
  | USB/ADB, phone hotspot, or trusted LAN
  v
JustProxy Android foreground service
  |
  | DNS and TCP sockets bound to the chosen Android Network
  v
Cellular network (default) or Android system-default network
  |
  v
Internet destination
~~~

HTTPS is passed through with HTTP CONNECT. JustProxy does not install a certificate, decrypt TLS, or inspect payload contents.

## Features

- One authenticated TCP port auto-detects HTTP proxy and SOCKS5 clients.
- HTTPS tunneling with CONNECT and plain HTTP absolute-form forwarding.
- SOCKS5 username/password authentication with remote DNS.
- Cellular-only fail-closed routing: DNS and destination sockets use the same Android cellular Network.
- Loopback-only USB mode by default.
- Opt-in hotspot/LAN listener with a local/private client-address guard.
- Random credentials protected by Android Keystore; regenerate them at any time.
- Manual or scheduled session reconnect.
- Current public IP and verified IP-change history.
- Live run, today, and lifetime upload/download totals.
- Recent per-session metadata: time, client, protocol, host/port, bytes, and result.
- Maximum connections, idle timeout, data cap, and safe destination policy.
- Authenticated local JSON control API.
- Zero-runtime-dependency Python SDK/CLI.
- Windows desktop companion packaged as a single executable.
- Persistent Android notification with Reconnect and Stop actions.

## Downloads

The repository distribution directory contains:

- [Android APK](dist/android/JustProxy-android-debug.apk)
- [Windows desktop app](dist/windows/JustProxyDesktop.exe)
- [Python packages](dist/python/)
- [SHA-256 checksums](dist/SHA256SUMS.txt)
- [Artifact notes](dist/README.md)

GitHub Releases carries the same files. Windows SmartScreen and Android may warn because the executable is unsigned and the APK uses an Android debug signing key. Verify the SHA-256 checksums before installing downloaded artifacts.

## Quick start: phone hotspot

This is usually the easiest way to make PC proxy traffic leave through mobile data.

1. Install the JustProxy APK on an Android 8.0 or newer phone.
2. Enable the phone hotspot and connect the PC to it.
3. Open JustProxy and accept the safety notice.
4. Turn on **Allow hotspot / LAN clients**.
5. Keep **Cellular-only egress (fail closed)** enabled.
6. Tap **Start proxy**.
7. Copy the displayed phone address, port, username, and password.
8. Configure the PC application for either HTTP or SOCKS5, or use the Windows companion.

The default proxy port is 8282. The authenticated control API uses the next port, 8283. The proxy password is also the control API Bearer token.

## Quick start: USB

USB mode keeps both listeners on Android loopback and is the safest default. It requires Android developer options and USB debugging.

With the phone connected:

~~~powershell
adb forward tcp:8282 tcp:8282
adb forward tcp:8283 tcp:8283
~~~

Leave **Allow hotspot / LAN clients** disabled. Start JustProxy, then use:

- Proxy host: 127.0.0.1
- Proxy port: 8282
- Control API: http://127.0.0.1:8283
- Username/password: values shown by the Android app

Remove the forwarding rules when finished:

~~~powershell
adb forward --remove tcp:8282
adb forward --remove tcp:8283
~~~

## Test the proxy

Replace USERNAME and PASSWORD with the generated values.

HTTP/HTTPS CONNECT:

~~~bash
curl --proxy "http://USERNAME:PASSWORD@127.0.0.1:8282" https://api.ipify.org
~~~

SOCKS5 with DNS resolved through the phone:

~~~bash
curl --proxy "socks5h://USERNAME:PASSWORD@127.0.0.1:8282" https://api.ipify.org
~~~

The result should match the public IP shown in JustProxy. With cellular-only enabled, requests fail when Android cannot provide a cellular Network; JustProxy does not silently fall back to Wi-Fi.

## Windows desktop companion

Run [JustProxyDesktop.exe](dist/windows/JustProxyDesktop.exe), then enter:

- Phone host: 127.0.0.1 for USB, or the phone address shown in LAN mode
- Control port: proxy port plus one
- Token: the Android proxy password
- Proxy username and proxy port: values shown by Android

The desktop app can:

- show service state, phone messages, current public IP, active connections, and traffic totals;
- request a public-IP check, then poll for the asynchronous observation;
- reconnect active proxy sessions, then poll for the resulting IP observation;
- copy HTTP or SOCKS5 setup URLs.

The phone accepts Check IP and Reconnect requests before the public-IP check finishes. The desktop polls status, metrics, and IP history once per second for up to about 12 seconds. If no fresh observation arrives in that window, use **Refresh** to check again; this is not evidence that the carrier changed the address.

The executable does not contain phone credentials. Values are held only for the running desktop session.

Build it from source with:

~~~powershell
cd desktop
.\build.ps1 -InstallPyInstaller
~~~

## Python API and CLI

Install the bundled wheel:

~~~powershell
py -m pip install dist\python\justproxy_client-0.1.0-py3-none-any.whl
~~~

CLI examples:

~~~powershell
justproxy --token PASSWORD status
justproxy --token PASSWORD metrics
justproxy --token PASSWORD check-ip
justproxy --token PASSWORD rotate
justproxy --token PASSWORD ip-history
justproxy --token PASSWORD sessions
justproxy --token PASSWORD env --username USERNAME --password PASSWORD --proxy-port 8282
justproxy --base-url http://192.168.43.1:8283 --token PASSWORD status
~~~

The default control URL is http://127.0.0.1:8283. Global options such as `--base-url` and `--token` go before the subcommand. Every control command requires `--token` or `JUSTPROXY_TOKEN`. The `env` command also requires a proxy username; its proxy password defaults to the control token when `--password` and `JUSTPROXY_PROXY_PASSWORD` are omitted.

Python example:

~~~python
from justproxy_client import JustProxyClient

client = JustProxyClient(
    token="PHONE_PASSWORD",
    base_url="http://127.0.0.1:8283",
    proxy_username="PHONE_USERNAME",
    proxy_password="PHONE_PASSWORD",
)

print(client.status())
print(client.metrics())
result = client.rotate()
print(result)
print(client.requests_proxies(
    host="127.0.0.1",
    proxy_port=8282,
))
~~~

Proxy URL and environment helpers require authentication. Configure `proxy_username` on the client (as above) or pass a username to each helper; when a username is configured, `proxy_password` defaults to the control token if it is omitted. The package uses only the Python standard library at runtime. See [python/README.md](python/README.md) for the full SDK and CLI reference.

## Rotation semantics

The setting named **Reconnect sessions every N minutes** closes active proxy TCP sessions on schedule. New connections then use the currently selected Android network.

This does not guarantee a different carrier IP.

A normal third-party Android app cannot toggle airplane mode or mobile data programmatically. JustProxy therefore:

1. closes current sessions;
2. performs a fresh public-IP check;
3. records and reports **changed**, **unchanged**, or **check failed**.

If a different carrier IP is required, manually toggle mobile data/airplane mode, wait for cellular service to return, then tap **Check IP**. Root/device-owner automation may be added later as a clearly separate, opt-in build.

## Traffic analyzer and privacy

JustProxy stores analytics only in its private Android SQLite database:

- session start/end time;
- client address;
- HTTP or SOCKS5 protocol;
- destination host and port;
- uploaded/downloaded byte counts;
- short completion result;
- public-IP observations and whether the value changed.

It does not store traffic payloads, HTTP headers, proxy credentials, URL paths/query strings, DNS response contents, or TLS secrets. Android backup is disabled. Use **Clear history** on the history screen to erase stored analytics.

Traffic totals and the data cap count bytes relayed through the proxy's payload streams. They do not include TCP/IP or radio overhead and may not match carrier billing, so the phone or carrier data meter remains authoritative.

The public-IP check contacts https://api.ipify.org over the selected Android network. No JustProxy telemetry or crash-reporting SDK is included.

## Controls

| Control | Behavior |
| --- | --- |
| Allow hotspot / LAN clients | Binds listeners on IPv4 wildcard but rejects non-local/public client addresses |
| Cellular-only egress | Requests Android cellular transport and fails closed if unavailable |
| Allow private/LAN destinations | Advanced override for destination blocking; off by default |
| Proxy port | Proxy listener; control API uses the next port |
| Reconnect interval | 0 disables; 1 to 1440 minutes schedules session reconnect |
| Idle timeout | Closes sessions with no activity |
| Maximum connections | Bounds concurrent proxy sessions |
| Data cap | Stops the current run after the configured proxy-stream MiB total |

Private/loopback/link-local/multicast destinations and TCP port 25 are blocked by default to reduce SSRF, LAN scanning, and spam abuse. The advanced private-destination switch deliberately relaxes part of that policy.

## Control API

The API is plain HTTP because it is intended only for USB forwarding or a trusted local network. Every request requires:

~~~http
Authorization: Bearer PHONE_PASSWORD
~~~

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /v1/status | Service, listener, egress, public IP, rotation status |
| GET | /v1/metrics | Run/today/lifetime byte and connection totals |
| GET | /v1/ip-history | Recent public-IP observations |
| GET | /v1/sessions | Recent sanitized session metadata |
| POST | /v1/rotate | Close/reconnect sessions and schedule an IP check |
| POST | /v1/check-ip | Schedule a public-IP check |

Do not expose the control port through Internet port forwarding. Authentication is not a substitute for TLS on an untrusted network.

## Build from source

Requirements:

- Android Studio or Android SDK 35
- JDK 17 or newer
- Python 3.9 or newer
- Windows with PyInstaller 6 for the desktop executable

Android:

~~~powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug
~~~

Linux/macOS:

~~~bash
./gradlew testDebugUnitTest assembleDebug
~~~

Python SDK:

~~~powershell
py -m unittest discover -s python\tests -v
py -m build --outdir dist\python python
~~~

Desktop helpers:

~~~powershell
py -m unittest discover -s desktop\tests -v
~~~

## Tests

The project includes integration tests for:

- HTTP CONNECT authentication, tunneling, and TCP half-close behavior;
- plain HTTP rewriting and proxy-credential stripping;
- SOCKS5 authentication and tunneling;
- destination/header/SMTP policy;
- bounded listener saturation, authentication deadlines, connection caps, idle timeouts, rotation, and shutdown;
- local-only ingress address policy;
- authenticated control API routes;
- analytics sanitization, IP validation, live byte checkpoints, and local-day rollups;
- Python SDK, CLI, error handling, and proxy URL helpers;
- Windows desktop validation, traffic formatting, asynchronous IP-result polling, and honest rotation messaging.

CI runs the Android/JVM, Python, and Windows suites, builds every platform artifact, clean-installs the wheel, and runs the frozen Windows executable's self-test. A real-device smoke test is still recommended across Pixel/AOSP and Samsung phones because hotspot and background-service behavior varies by manufacturer.

## Important limitations

- Only proxy-aware PC applications use JustProxy. A future desktop TUN/VPN companion is required for all PC traffic and UDP.
- SOCKS5 supports TCP CONNECT only; BIND and UDP ASSOCIATE are rejected.
- Direct access is local/USB only. Carrier CGNAT normally prevents Internet clients from reaching the phone. Remote use requires a future phone-initiated encrypted relay.
- Proxy credentials are not encrypted on the local hop. Prefer USB or a trusted personal hotspot.
- Scheduled reconnect is not automatic carrier-IP rotation.
- The current APK is an alpha/debug build, not a Play Store production release.
- The bundled Windows executable is unsigned and may trigger SmartScreen; it is an alpha test build, not a production installer.
- Public-IP checking depends on the external ipify endpoint.
- Battery optimization and aggressive manufacturer task killers may stop long-running background networking despite the foreground service.

## Project origins

The Proxyrack mobile-proxy repository linked during planning was used only for feature research. Its proxy engine is shipped as an opaque AAR, its service depends on Proxyrack infrastructure, and the repository does not provide a reusable license. JustProxy is a clean-room implementation and does not copy that code, binary, backend, branding, or assets.

## Responsible use

Use JustProxy only on devices and networks you own or administer and only with destinations that permit the traffic. You remain responsible for mobile-data charges, carrier terms, website terms, and applicable law. Do not operate it as an open proxy or provide access to unknown third parties.

## License

JustProxy is released under the [MIT License](LICENSE).
