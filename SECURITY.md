# Security policy

## Reporting a vulnerability

Please use GitHub private vulnerability reporting for this repository:

https://github.com/justdataplease/justproxy/security/advisories/new

Do not open a public issue containing an exploit, credential, traffic record, or identifying network data. Include the affected version, Android/Windows/Python environment, reproduction steps, impact, and a minimal proof of concept. You should receive an acknowledgement within seven days.

## Supported versions

JustProxy is currently an alpha project. Security fixes are applied to the latest release and the main branch.

## Security boundaries

JustProxy is intended for a user-owned phone and computer connected by USB or a trusted private/hotspot network.

- Proxy and control listeners bind to Android loopback by default.
- LAN mode requires explicit opt-in and rejects clients whose source address is not local/private/link-local/ULA/CGNAT.
- HTTP and SOCKS5 proxy authentication is mandatory.
- The control API uses a high-entropy Bearer token.
- Python/desktop proxy setup helpers require a proxy username and embed the username and password in generated proxy URLs; treat copied or printed URLs as credentials.
- Credentials are encrypted with Android Keystore and Android backup is disabled.
- Cellular-only mode binds both DNS and destination sockets to Android's cellular Network and fails closed.
- Unsafe local destinations and TCP port 25 are blocked by default.
- HTTPS uses CONNECT tunneling; JustProxy does not intercept TLS.
- Traffic contents, headers, credentials, URL paths, and query strings are not logged.

Proxy authentication and the local control API are not encrypted on the local hop. Use USB forwarding or a trusted personal network. Do not expose either port through router or Internet port forwarding, and do not paste generated proxy URLs into logs or issue reports.

## Out of scope by design

The current release does not implement a public relay, remote dashboard, rooted radio toggle, traffic interception, SOCKS UDP, or a desktop TUN driver. Reports that require unsupported deployment modes may be treated as feature requests unless they affect a documented security boundary.
