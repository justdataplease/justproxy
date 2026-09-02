# Security policy

## Reporting a vulnerability

Please use GitHub private vulnerability reporting for this repository:

https://github.com/justdataplease/justproxy/security/advisories/new

Do not open a public issue containing an exploit, credential, traffic record, or identifying network data. Include the affected version, Android/Python environment, reproduction steps, impact, and a minimal proof of concept. You should receive an acknowledgement within seven days.

## Supported versions

JustProxy is currently a beta project. Security fixes are applied to the latest release and the main branch.

## Security boundaries

JustProxy is intended for a user-owned phone and computer connected by USB or a trusted private network.

- Proxy and control listeners bind to Android loopback by default.
- LAN mode requires explicit opt-in and rejects clients whose source address is not local/private/link-local/ULA/CGNAT.
- The optional WireGuard gateway listens on its configured UDP port on local interfaces. It accepts only the single stored peer key and should be reachable only from a trusted LAN.
- Exported WireGuard `.conf` files contain the client private key. Store and transfer them as credentials, and revoke/regenerate the peer if a file is exposed.
- HTTP and SOCKS5 proxy authentication is mandatory.
- The control API uses a high-entropy Bearer token. That token also authorizes disruptive `POST /v1/ip-rotate` actions when Shizuku rotation is ready.
- Python proxy helpers embed credentials in generated URLs; treat copied or printed URLs as credentials.
- Credentials are encrypted with Android Keystore and Android backup is disabled.
- Cellular-only mode binds proxy DNS/destination sockets and WireGuard upstream sockets to Android's cellular `Network` and fails closed.
- Unsafe local destinations and TCP port 25 are blocked by default.
- HTTPS uses CONNECT tunneling; JustProxy does not intercept TLS.
- Traffic contents, headers, credentials, URL paths, and query strings are not logged.

Proxy authentication and the local control API are not encrypted on the local hop. Use USB forwarding or a trusted personal network. Do not expose either port through router or Internet port forwarding, and do not paste generated proxy URLs into logs or issue reports.

## Optional Shizuku boundary

Automatic IP rotation is disabled by default and separate from WireGuard, the legacy proxy, and ordinary session reconnect. Shizuku is an external privileged service and is not bundled with JustProxy. Install it only from an [official Shizuku source](https://shizuku.rikka.app/download/) and review its [official setup guide](https://shizuku.rikka.app/guide/setup/) before granting access. JustProxy requires a context-capable Shizuku API 13 server for this feature.

- JustProxy requests Shizuku permission only after a user action. Revoking permission or stopping Shizuku disables automatic airplane-mode cycling.
- JustProxy verifies that Shizuku's remote identity has Android's `NETWORK_SETTINGS` permission before binding the privileged service.
- The UserService exposes a narrow AIDL interface. Normal rotation executes only fixed `cmd connectivity airplane-mode` query, enable, and disable argument vectors. It exposes no arbitrary shell, raw command, destination, or user-controlled argument surface.
- Fixed `cmd phone data enable` and `svc data enable` vectors remain only to recover an interrupted beta.2 marker. No mobile-data disable vector remains.
- The UI allows a 1-1440-minute interval and a 1-10-second airplane-mode hold after observed cellular loss. The default hold is 1 second. Values are checked again at the service and command boundary.
- JustProxy refuses a new cycle unless Android reports mobile data enabled and airplane mode disabled. It persists a device-protected, mode-aware recovery marker before the Binder command, watches the existing cellular network disappear, attempts airplane-mode disable in a `finally` path, and clears the marker only after Android reports airplane mode disabled.
- The marker-before-Binder policy prioritizes restoring connectivity after a crash; it cannot prove ownership if the user manually changes airplane mode while rotation is pending. Do not manually toggle airplane mode until the operation or recovery finishes.
- A pending marker blocks another cycle. If recovery fails, status and the notification show whether airplane mode may still be on or, for a migrated beta.2 marker, mobile data may still be off. Follow the displayed manual recovery action.
- These safeguards reduce risk but are not a guarantee. A reboot, Shizuku server death, lost debugging authorization, process termination, or OEM connectivity failure during the enabled window can prevent automatic restoration. Non-root Shizuku must be started again after every reboot.
- Airplane mode can disrupt Wi-Fi, calls, SMS, IMS, and all radio connectivity. Scheduled rotation should remain disabled when uninterrupted phone service is needed.
- Phone-hotspot clients are unsupported for automatic airplane rotation: the hotspot can stop and may not restart automatically. Use a shared trusted router and configure a Pixel to keep Wi-Fi on in airplane mode.
- `POST /v1/rotate` reconnects sessions only. `POST /v1/ip-rotate` can interrupt every upstream flow and start the airplane-mode cycle. Do not share the Bearer token with anyone who should not have that authority.
- Cycling airplane mode never guarantees a new public IP. The carrier may return the same address, and API status reports `guarantees_ip_change: false`.

Security reports are especially useful for arbitrary-command injection, permission confusion, recovery-marker bypass, concurrent cycles, another cycle while recovery is pending, tokenless `/v1/ip-rotate` access, or leakage of privileged command output.

## Out of scope by design

The current release does not implement a public relay, remote dashboard, direct root control, traffic interception, or SOCKS UDP. The only optional radio-related operation is the documented fixed-command Shizuku airplane-mode cycle. Reports that require unsupported deployment modes may be treated as feature requests unless they affect a documented security boundary.
