# Security policy

## Reporting a vulnerability

Please use GitHub private vulnerability reporting for this repository:

https://github.com/justdataplease/justproxy/security/advisories/new

Do not open a public issue containing an exploit, credential, traffic record, or identifying network data. Include the affected version, Android/Python environment, reproduction steps, impact, and a minimal proof of concept. You should receive an acknowledgement within seven days.

## Supported versions

JustProxy is currently a beta project. Security fixes are applied to the latest release and the main branch.

## Security boundaries

JustProxy is intended for a user-owned phone and computer connected by USB or a trusted private/hotspot network.

- Proxy and control listeners bind to Android loopback by default.
- LAN mode requires explicit opt-in and rejects clients whose source address is not local/private/link-local/ULA/CGNAT.
- The optional WireGuard gateway listens on its configured UDP port on local interfaces. It accepts only the single stored peer key and should be reachable only from a trusted LAN or the phone hotspot.
- Exported WireGuard `.conf` files contain the client private key. Store and transfer them as credentials, and revoke/regenerate the peer if a file is exposed.
- HTTP and SOCKS5 proxy authentication is mandatory.
- The control API uses a high-entropy Bearer token. In v0.3, that token also authorizes the disruptive `POST /v1/ip-rotate` action when the optional Shizuku feature is ready.
- Python proxy setup helpers require a proxy username and embed the username and password in generated proxy URLs; treat copied or printed URLs as credentials.
- Credentials are encrypted with Android Keystore and Android backup is disabled.
- Cellular-only mode binds proxy DNS/destination sockets and WireGuard gateway upstream sockets to Android's cellular Network and fails closed.
- Unsafe local destinations and TCP port 25 are blocked by default.
- HTTPS uses CONNECT tunneling; JustProxy does not intercept TLS.
- Traffic contents, headers, credentials, URL paths, and query strings are not logged.

Proxy authentication and the local control API are not encrypted on the local hop. Use USB forwarding or a trusted personal network. Do not expose either port through router or Internet port forwarding, and do not paste generated proxy URLs into logs or issue reports.

## Optional Shizuku boundary

Automatic IP rotation is disabled by default and is separate from the WireGuard
gateway, legacy proxy, and ordinary session reconnect. Shizuku is an external
privileged service; it is not bundled with JustProxy. Install it only from an
[official Shizuku source](https://shizuku.rikka.app/download/) and review its
[official setup guide](https://shizuku.rikka.app/guide/setup/) before granting
access.

- JustProxy requests Shizuku permission only after a user action. Revoking that
  permission or stopping Shizuku disables automatic mobile-data cycling.
- The privileged UserService exposes a narrow AIDL interface. It executes fixed
  `cmd phone data enable/disable` commands, with a fixed `svc data`
  compatibility fallback; it does not expose an arbitrary shell, raw command,
  destination, or user-controlled argument surface.
- The Android UI allows a 1–1440-minute interval and a 1–10-second data-off
  duration. The default duration is 1 second. Values are checked again at the
  service and privileged-command boundary.
- JustProxy refuses a cycle unless Android positively reports mobile data
  already enabled. It persists a device-protected recovery marker before
  disable, attempts enable in a finally path, and clears the marker only after
  Android reports data enabled.
- A pending recovery marker blocks another disable. If recovery fails, status
  and the foreground notification warn that mobile data may be off; the user
  must turn it on manually in Android settings.
- These safeguards reduce risk but are not a guarantee. A reboot, Shizuku
  server death, lost debugging authorization, process termination, default-SIM
  change, or OEM telephony failure during the disabled window can prevent
  automatic restoration. Non-root Shizuku must be started again after every
  reboot.
- `POST /v1/rotate` reconnects sessions only. `POST /v1/ip-rotate` can
  interrupt all JustProxy upstream flows and trigger the Shizuku mobile-data
  cycle. Do not give the Bearer token to a party that should not have that
  authority.
- Cycling mobile data never guarantees a new public IP; the carrier may return
  the same address, and API status reports `guarantees_ip_change: false`.

Security reports are especially useful for arbitrary-command injection,
permission-confusion, recovery-marker bypass, concurrent cycles, another
disable while recovery is pending, tokenless access to `/v1/ip-rotate`, or
leakage of privileged command output.

## Out of scope by design

The current release does not implement a public relay, remote dashboard, direct root/airplane-mode control, traffic interception, or SOCKS UDP. The only optional radio-related operation is the documented Shizuku mobile-data cycle. Reports that require unsupported deployment modes may be treated as feature requests unless they affect a documented security boundary.
