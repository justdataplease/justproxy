# JustProxy

JustProxy v0.2 turns an unrooted Android phone into a local WireGuard Internet gateway. Export a standard `.conf` file from the phone, import it into the official WireGuard client on a computer or another mobile device, and route that device's IPv4 and IPv6 TCP/UDP traffic through the phone's selected Android network. The existing authenticated HTTP/HTTPS CONNECT and SOCKS5 proxy remains available as an optional compatibility mode.

> Beta distribution: the Android APK is debug-signed and the Python package is pre-1.0. The WireGuard gateway is a one-peer beta. These artifacts are for testing, not a production or app-store release.

## What is included

| Component | Purpose |
| --- | --- |
| Android app | Runs the userspace WireGuard gateway, optional legacy proxy, cellular routing, profile export, and local traffic/IP analytics |
| WireGuard client profile | Standard dual-stack full-tunnel `.conf` imported into an official WireGuard client |
| Python package and CLI | Scriptable authenticated control API client and legacy proxy helpers |
| Distribution folder | Test APK, Python wheel/source archive, and checksums |

The former custom Windows desktop executable has been removed. JustProxy now uses the official WireGuard applications for system-wide tunnelling; the Android UI and optional Python SDK provide status and control.

JustProxy is designed for a phone and client device you own or are authorized to use. It is not a residential-proxy marketplace, bandwidth-sharing client, interception tool, or public relay.

## How it works

~~~text
Official WireGuard client on the computer/device
  |
  | encrypted WireGuard UDP over the same LAN or phone hotspot
  v
JustProxy listener on the phone's Wi-Fi/hotspot address
  |
  | userspace IPv4/IPv6 TCP and UDP forwarding
  | upstream sockets bound to the selected Android Network
  v
Cellular network (default) or Android system-default network
  |
  v
Internet destination
~~~

The phone's Wi-Fi can and normally should remain enabled: it carries the local encrypted connection between the client and phone. With **Cellular-only egress (fail closed)** enabled, only the gateway's upstream Internet sockets are bound to Android's cellular `Network`. Losing cellular service stops forwarding instead of silently falling back to Wi-Fi.

Legacy proxy traffic follows a separate local HTTP/SOCKS5 listener. HTTPS uses CONNECT passthrough; JustProxy does not install a certificate, decrypt TLS, or inspect payload contents.

## Features

- Standard WireGuard `.conf` export for official Windows, macOS, Linux, Android, and iOS clients.
- Full-tunnel `0.0.0.0/0` and `::/0` routes with IPv4/IPv6 TCP and UDP forwarding.
- One authenticated WireGuard peer in the v0.2 beta.
- Userspace gateway on an unrooted phone; it does not use Android `VpnService`.
- Cellular-only fail-closed routing for WireGuard, DNS, and legacy proxy destinations.
- WireGuard active/total flow counts, byte estimates, and latest handshake status.
- Current public IP and verified IP-change history.
- Live run, today, and lifetime upload/download totals plus a combined run data cap.
- Android-Keystore-encrypted WireGuard and proxy credentials.
- Manual reconnect of proxy sessions and WireGuard flows.
- Optional authenticated HTTP/HTTPS CONNECT and SOCKS5 proxy.
- Loopback-only USB proxy mode and opt-in hotspot/LAN proxy listener.
- Authenticated local JSON control API.
- Zero-runtime-dependency Python SDK/CLI.
- Persistent Android notification with Reconnect and Stop actions.

## Downloads

The repository distribution directory contains:

- [Android APK](dist/android/JustProxy-android-debug.apk)
- [Python packages](dist/python/)
- [SHA-256 checksums](dist/SHA256SUMS.txt)
- [Artifact notes](dist/README.md)

GitHub Releases carries the same files. Android may warn because the APK uses a debug signing key. A beta APK produced by a different build runner may have a different debug-signing identity; if Android rejects an update, uninstall the old test build before installing the new one. Uninstalling clears JustProxy settings, analytics, credentials, and its stored peer, so export anything you need first. Verify the SHA-256 checksums before installing downloaded artifacts. Install an official WireGuard client separately from the [WireGuard installation page](https://www.wireguard.com/install/).

## Quick start: WireGuard

The phone and client must be locally reachable over the same Wi-Fi LAN or the phone's hotspot. The phone may keep Wi-Fi and mobile data enabled at the same time.

1. Install the JustProxy APK on an Android 8.0 or newer phone.
2. Install an [official WireGuard client](https://www.wireguard.com/install/) on the computer or mobile device that will use the phone.
3. Enable mobile data on the phone and confirm it has Internet access.
4. Either enable the phone hotspot and connect the client to it, or connect both devices to the same trusted Wi-Fi LAN.
5. Open JustProxy and accept the safety notice.
6. Leave the default WireGuard UDP port `51820` selected for the first setup.
7. Tap **Create / export computer profile**, create the one peer, select the phone's current LAN/hotspot address, then export the `.conf` to a trusted location.
8. Return to the main screen, enable **WireGuard gateway (full computer traffic)**, keep **Cellular-only egress (fail closed)** enabled, and tap **Start JustProxy**.
9. Transfer the exported file securely to the client, import it into WireGuard, and activate the tunnel.

The v0.2 beta accepts exactly one peer identity. Regenerating or revoking it immediately invalidates the old configuration after the gateway reloads. Do not run the same exported profile on multiple devices at the same time: they share one private key and inner address.

### Import the profile

- **Windows or macOS:** open the official WireGuard app, choose **Import tunnel(s) from file**, select the exported `.conf`, then activate it.
- **Android or iOS:** in the official WireGuard app choose **Add** and import from a file/archive. JustProxy does not currently export a QR code.
- **Linux:** install WireGuard tools and bring the profile up with `sudo wg-quick up /path/to/JustProxy-PC.conf`.

The profile routes `0.0.0.0/0` and `::/0`, assigns `10.66.0.2` and `fd66::2`, uses an MTU of 1280, and sends DNS to literal public resolvers through the tunnel.

> Security warning: the exported `.conf` contains the client private key. Anyone who obtains it can impersonate the accepted peer. Do not paste it into issues, logs, chat, or screenshots. Store and transfer it securely, and use **Revoke peer** if it may have leaked.

### LAN, hotspot, endpoint, and firewall requirements

The exported profile records the selected phone address and UDP port as a static WireGuard `Endpoint`. The client must be able to send UDP directly to that address.

- A phone hotspot normally provides the simplest direct local path.
- On Wi-Fi, both devices must be on the same reachable LAN; a guest network, client/AP isolation, VLAN rules, or a local firewall may block device-to-device UDP.
- If the phone's LAN address or WireGuard port changes, re-export the profile or edit its `Endpoint` in the WireGuard client.
- JustProxy v0.2 does not provide NAT traversal, dynamic endpoint discovery, or a public relay.
- Carrier CGNAT and mobile firewalls normally prevent an Internet client from initiating a connection to the phone. Direct remote use is not supported.
- Do not expose the gateway or control port through public router port forwarding.

The WireGuard listener remains on the phone's LAN/hotspot interface. Upstream TCP/UDP sockets are independently bound to cellular in fail-closed mode. Turning off phone Wi-Fi may therefore break the client-to-phone path even when mobile data remains available.

### Verify the tunnel

With the WireGuard tunnel active:

~~~bash
curl -4 https://api.ipify.org
curl -6 https://api64.ipify.org
~~~

The IPv4 result should match the public IP shown in JustProxy. IPv6 requires usable IPv6 service from the selected Android network; it fails closed rather than escaping outside the WireGuard route when unavailable. Normal DNS and UDP applications should work through the tunnel. General ICMP forwarding is not implemented, so `ping` is not a valid health check for this beta.

## Legacy HTTP/SOCKS5 proxy

The authenticated proxy remains supported for applications that already understand HTTP or SOCKS5. It is optional when WireGuard is enabled.

### Hotspot or trusted LAN

1. Enable the phone hotspot and connect the computer, or put both devices on the same trusted LAN.
2. Turn on **Enable legacy HTTP / SOCKS5 proxy** and **Legacy proxy/control: allow LAN clients**.
3. Keep **Cellular-only egress (fail closed)** enabled.
4. Tap **Start JustProxy**.
5. Use the displayed phone address, proxy port, username, and password in the proxy-aware application.

The default proxy port is 8282. The authenticated control API uses the next port, 8283. The proxy password is also the control API Bearer token.

### USB/ADB for the legacy proxy and control API

ADB forwarding is TCP-only in this setup and does not carry the WireGuard UDP tunnel. With USB debugging enabled:

~~~powershell
adb forward tcp:8282 tcp:8282
adb forward tcp:8283 tcp:8283
~~~

Leave **Legacy proxy/control: allow LAN clients** disabled, then use `127.0.0.1:8282` for the proxy and `http://127.0.0.1:8283` for the control API.

Remove the forwarding rules when finished:

~~~powershell
adb forward --remove tcp:8282
adb forward --remove tcp:8283
~~~

Test HTTP CONNECT or SOCKS5 with:

~~~bash
curl --proxy "http://USERNAME:PASSWORD@127.0.0.1:8282" https://api.ipify.org
curl --proxy "socks5h://USERNAME:PASSWORD@127.0.0.1:8282" https://api.ipify.org
~~~

The result should match the public IP shown in JustProxy. With cellular-only enabled, requests fail when Android cannot provide a cellular `Network`; JustProxy does not silently fall back to Wi-Fi.

## Python API and CLI

Install the bundled wheel:

~~~powershell
py -m pip install dist\python\justproxy_client-0.2.0b1-py3-none-any.whl
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

Proxy URL and environment helpers require authentication. Configure `proxy_username` on the client (as above) or pass a username to each helper; when a username is configured, `proxy_password` defaults to the control token if it is omitted. These helpers remain supported for the legacy proxy.

Newer API responses expose a typed `status.wireguard` object and WireGuard upload, download, active-flow, and total-flow metrics. Older phones that omit those fields remain compatible; the SDK preserves unknown fields in each model's `raw` mapping. The package uses only the Python standard library at runtime. See [python/README.md](python/README.md) for the full SDK and CLI reference.

## Reconnect and carrier IP changes

**Reconnect** closes active legacy proxy sessions, restarts WireGuard forwarding flows, and performs a fresh public-IP check. Scheduled reconnect uses the same principle. It does not toggle the cellular radio and does not guarantee a different carrier IP.

A normal unrooted third-party Android app cannot toggle airplane mode or mobile data programmatically. To request a new carrier lease:

1. Leave the phone's Wi-Fi/hotspot path available to the client when possible.
2. Manually turn **mobile data** off.
3. Wait briefly, then turn mobile data on and wait for cellular service to return.
4. Allow the WireGuard client to re-handshake, then tap **Check IP** in JustProxy.
5. Trust the reported observation: **changed**, **unchanged**, or **check failed**.

The carrier may return the same public IP after any number of toggles. Airplane mode can also disable the Wi-Fi/hotspot link that carries the local WireGuard tunnel, so a mobile-data-only toggle is usually less disruptive.

## Traffic analyzer and privacy

JustProxy stores analytics only in its private Android SQLite database:

- combined WireGuard and legacy-proxy uploaded/downloaded totals;
- completed legacy proxy session time, client, protocol, destination host/port, bytes, and result;
- public-IP observations and whether the value changed.

Live WireGuard status also reports active/total userspace flows, byte estimates, and the latest handshake time. It does not persist packet payloads or WireGuard private keys in analytics.

JustProxy does not store traffic payloads, HTTP headers, proxy credentials, URL paths/query strings, DNS response contents, or TLS secrets. Android backup is disabled. Use **Clear history** on the history screen to erase stored analytics.

WireGuard counts are packet-byte estimates and proxy counts are relayed payload bytes. Neither includes every IP, WireGuard, TCP, UDP, or radio overhead byte, so totals and the data cap may differ from carrier billing. The phone or carrier data meter remains authoritative.

The public-IP check contacts https://api.ipify.org over the selected Android network. No JustProxy telemetry or crash-reporting SDK is included.

## Controls

| Control | Behavior |
| --- | --- |
| Enable WireGuard gateway | Starts the encrypted full-device gateway when the one peer exists |
| Enable legacy HTTP / SOCKS5 proxy | Keeps proxy-aware application support available independently of WireGuard |
| Legacy proxy/control: allow LAN clients | Exposes the legacy proxy/control listeners to trusted local clients; WireGuard always needs a selected local endpoint |
| Cellular-only egress | Requests Android cellular transport and fails closed if unavailable |
| Allow private/LAN destinations | Advanced legacy-proxy destination override; off by default |
| WireGuard UDP port | Local encrypted listener; default 51820 and must differ from proxy/control ports |
| Proxy port | Proxy listener; control API uses the next port |
| Reconnect interval | 0 disables; 1 to 1440 minutes schedules proxy/WireGuard flow reconnect |
| Idle timeout | Closes legacy proxy sessions with no activity |
| Maximum connections | Bounds concurrent legacy proxy sessions |
| Data cap | Stops the run after the combined gateway/proxy MiB estimate; the run survives network flaps and resets on stop/start or process recreation |

The WireGuard beta rejects private, loopback, link-local, multicast, documentation/test, and other non-public destinations, plus TCP/UDP port 25. The legacy proxy blocks unsafe local destinations and TCP port 25 by default; its advanced private-destination switch deliberately relaxes part of that policy.

## Control API

The API is plain HTTP because it is intended only for USB forwarding or a trusted local network. Every request requires:

~~~http
Authorization: Bearer PHONE_PASSWORD
~~~

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /v1/status | Service, proxy, public IP, rotation, and nested WireGuard gateway status |
| GET | /v1/metrics | Run/today/lifetime totals plus WireGuard bytes and flow counts |
| GET | /v1/ip-history | Recent public-IP observations |
| GET | /v1/sessions | Recent sanitized session metadata |
| POST | /v1/rotate | Close/reconnect sessions and schedule an IP check |
| POST | /v1/check-ip | Schedule a public-IP check |

Do not expose the control port through Internet port forwarding. Authentication is not a substitute for TLS on an untrusted network.

The API never returns a WireGuard private key or an exportable client profile. Create, export, regenerate, and revoke the one peer only through the local Android UI.

## Build from source

Requirements:

- Android Studio or Android SDK 35
- JDK 17 or newer
- Python 3.9 or newer
- Rust 1.85.1, Android NDK 28.2.13676358, and cargo-ndk 4.1.2 for the native gateway

Build and test the native gateway first. See [native/wireguard-gateway/README.md](native/wireguard-gateway/README.md) for ABI build commands and third-party notices.

~~~bash
cargo test --manifest-path native/wireguard-gateway/Cargo.toml
~~~

Android on Windows:

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

## Tests

The project includes integration tests for:

- WireGuard key/profile validation, deterministic full-tunnel configuration rendering, encrypted peer-record codecs, and endpoint safety;
- gateway status, combined run/data-cap accounting, and additive control API fields;
- native WireGuard packet, policy, TCP/UDP, flow-limit, and fail-closed network-binding behavior;
- HTTP CONNECT authentication, tunneling, and TCP half-close behavior;
- plain HTTP rewriting and proxy-credential stripping;
- SOCKS5 authentication and tunneling;
- destination/header/SMTP policy;
- bounded listener saturation, authentication deadlines, connection caps, idle timeouts, rotation, and shutdown;
- local-only ingress address policy;
- authenticated control API routes;
- analytics sanitization, IP validation, live byte checkpoints, and local-day rollups;
- Python SDK, CLI, WireGuard model compatibility, error handling, and proxy URL helpers.

CI runs the Rust tests/lints, Android/JVM tests and lint, Python suite, native Android cross-build, APK packaging checks, and a clean wheel installation. A real-phone/client smoke test remains required for WireGuard handshakes, TCP/UDP, IPv4/IPv6, hotspot reachability, cellular loss/recovery, and manufacturer-specific background behavior.

## Important limitations

- WireGuard v0.2 supports one peer identity and one fixed pair of inner addresses. It is not a multi-user VPN server.
- The WireGuard data plane supports inner IPv4/IPv6 TCP and UDP but not general ICMP; `ping` may fail even when web and UDP traffic work.
- The profile endpoint is a local LAN/hotspot address. There is no NAT traversal, relay, roaming endpoint service, or direct remote-access mode.
- Guest-Wi-Fi isolation, host firewalls, router rules, or a changed phone LAN address can prevent the WireGuard handshake.
- Usable tunneled IPv6 depends on IPv6 service from the selected Android network.
- The exported `.conf` contains a private key. Revoke/regenerate the peer if the file is exposed.
- No custom desktop application is shipped; an official WireGuard client is required for the full-device tunnel.
- The optional SOCKS5 proxy still supports TCP CONNECT only; BIND and UDP ASSOCIATE are rejected.
- Legacy proxy credentials are not encrypted on the local hop. Prefer USB or a trusted personal LAN/hotspot.
- Scheduled reconnect and manually toggling mobile data do not guarantee carrier-IP rotation.
- The current APK is a beta/debug build, not a Play Store production release.
- Public-IP checking depends on the external ipify endpoint.
- Battery optimization and aggressive manufacturer task killers may stop long-running background networking despite the foreground service.
- JustProxy requests a sticky service restart after an ordinary Android process kill, but Android and
  manufacturer task managers do not guarantee recovery. A recreated process begins a new in-memory
  data-cap run; the phone or carrier data meter remains authoritative.

## Project origins

The Proxyrack mobile-proxy repository linked during planning was used only for feature research. Its proxy engine is shipped as an opaque AAR, its service depends on Proxyrack infrastructure, and the repository does not provide a reusable license. JustProxy is a clean-room implementation and does not copy that code, binary, backend, branding, or assets.

## Responsible use

Use JustProxy only on devices and networks you own or administer and only with destinations that permit the traffic. You remain responsible for mobile-data charges, carrier terms, website terms, and applicable law. Do not operate it as an open proxy or provide access to unknown third parties.

## License

JustProxy is released under the [MIT License](LICENSE).
