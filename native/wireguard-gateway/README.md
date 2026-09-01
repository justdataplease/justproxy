# JustProxy native WireGuard gateway

This crate is the Android data plane for JustProxy's unrooted WireGuard gateway. It accepts one authenticated WireGuard peer on a normal LAN/hotspot UDP socket, terminates the peer's IPv4 and IPv6 TCP/UDP flows in a userspace TCP/IP stack, and opens corresponding Android sockets on the selected egress `Network`.

It deliberately does **not** use `VpnService`: the phone is the WireGuard endpoint, not a VPN client. The WireGuard listener is never bound to the cellular `Network`; only upstream TCP/UDP sockets are. This keeps the listener reachable over Wi-Fi/hotspot while making cellular-only mode fail closed.

## Native API

The Android JNI class name is `com.justproxy.app.wireguard.WireGuardNativeGateway` with these static methods:

```java
static native long nativeStart(String configJson);
static native String nativeStop(long handle);
static native String nativeGetStats(long handle);
static native String nativeGenerateKeyPair();
static native String nativeGetLastError();
```

`nativeStart` launches a native runtime and returns a non-zero handle. It returns `0` on validation/bind failure; `nativeGetLastError()` supplies the reason. `nativeStop` joins the gateway thread and returns its final stats JSON. Both stats methods return `null` and set the last error for an invalid handle or native failure; they never substitute a zero-valued snapshot. The Java wrapper makes `close()` idempotent by clearing its handle before the one native stop call.

Stats JSON has stable fields `running`, `uploaded_bytes`, `downloaded_bytes`, `active_tcp_flows`, `active_udp_flows`, `total_tcp_flows`, `total_udp_flows`, and `last_handshake_ms`. `last_handshake_ms` comes from BoringTun's authenticated session-established timer. A terminal runtime failure adds `fatal_error`; that field is omitted while healthy. Transient per-flow failures never populate `fatal_error`.

Example configuration:

```json
{
  "private_key": "PHONE_PRIVATE_KEY_BASE64",
  "peer_public_key": "PC_PUBLIC_KEY_BASE64",
  "listen": "0.0.0.0:51820",
  "network_handle": 123456,
  "require_bound_network": true,
  "peer_ipv4": "10.66.0.2",
  "peer_ipv6": "fd66::2",
  "mtu": 1280,
  "tcp_buffer_size": 65536,
  "max_tcp_flows": 256,
  "max_udp_flows": 512
}
```

When `require_bound_network` is true, a zero Android network handle is rejected and each upstream socket must successfully bind through `android_setsocknetwork()` before it can connect or send. The service should stop/restart the native gateway whenever Android reports that the selected `Network` was lost or replaced.

## Build

The crate pins Rust 1.85.1 and its Android targets in `rust-toolchain.toml`. JustProxy pins Android NDK `28.2.13676358` (r28c) and cargo-ndk 4.1.2:

```sh
sdkmanager "ndk;28.2.13676358"
cargo install cargo-ndk --version 4.1.2 --locked
cd native/wireguard-gateway
cargo test --locked
cargo ndk --platform 26 \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../../app/build/generated/wireguard-jni \
  build --release --locked
```

NDK r28 is required so the shared objects are 16 KiB page-aligned by default. Android API 26 remains sufficient (`Network.getNetworkHandle()` and `android_setsocknetwork()` were added in API 23).

## Current beta boundaries

- One WireGuard peer per listener.
- TCP and UDP over inner IPv4/IPv6; no general ICMP forwarding.
- The peer may use only its configured inner IPv4/IPv6 source addresses.
- Private, loopback, link-local, multicast, documentation/test, and other non-public destinations are rejected; SMTP port 25 is rejected.
- DNS should initially use literal public resolvers in the client profile so it traverses the ordinary network-bound UDP path. This crate does not use the host resolver.
- Flow counts and successful TCP/UDP payload-byte counters are local analytics, not carrier billing records.

## Provenance and licenses

The userspace router structure and smoltcp adapter are adapted from `vi/wgslirpy` 0.2.0 (MIT OR Apache-2.0). WireGuard cryptography is provided by Cloudflare BoringTun (BSD-3-Clause), and the TCP/IP stack is smoltcp (0BSD). Tokio and the remaining Rust dependencies use permissive licenses. Preserve `NOTICE.md` and the upstream source headers when redistributing.
