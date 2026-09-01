package com.justproxy.app.wireguard;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** Lifecycle-safe Java contract for the native userspace WireGuard gateway. */
public final class WireGuardNativeGateway implements AutoCloseable {
    private static final Throwable LOAD_FAILURE;

    static {
        Throwable failure = null;
        try {
            System.loadLibrary("justproxy_wireguard_gateway");
        } catch (Throwable throwable) {
            failure = throwable;
        }
        LOAD_FAILURE = failure;
    }

    private long handle;

    private WireGuardNativeGateway(long handle) {
        this.handle = handle;
    }

    public static WireGuardNativeGateway start(Config config) {
        Objects.requireNonNull(config, "config");
        requireAvailable();
        final long handle;
        try {
            handle = nativeStart(config.toJson());
        } catch (LinkageError error) {
            throw linkageFailure(error);
        }
        if (handle == 0) {
            throw new IllegalStateException(nativeErrorOr(
                    "Native WireGuard gateway failed to start"));
        }
        return new WireGuardNativeGateway(handle);
    }

    /** Generates a Curve25519 private/public key pair in the native WireGuard implementation. */
    public static KeyPair generateKeyPair() {
        requireAvailable();
        final String json;
        try {
            json = nativeGenerateKeyPair();
        } catch (LinkageError error) {
            throw linkageFailure(error);
        }
        if (json == null || json.isEmpty()) {
            throw new IllegalStateException(nativeErrorOr(
                    "Native WireGuard key generation returned no data"));
        }
        try {
            JSONObject object = new JSONObject(json);
            WireGuardKey privateKey = WireGuardKey.parse(object.getString("private_key"));
            WireGuardKey publicKey = WireGuardKey.parse(object.getString("public_key"));
            return new KeyPair(privateKey, publicKey);
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Native WireGuard key generation returned invalid data", exception);
        }
    }

    public synchronized boolean isRunning() {
        return handle != 0 && getStatsLocked().isRunning();
    }

    public synchronized WireGuardGatewayStats getStats() {
        return handle == 0 ? WireGuardGatewayStats.stopped() : getStatsLocked();
    }

    private WireGuardGatewayStats getStatsLocked() {
        final String value;
        try {
            value = nativeGetStats(handle);
        } catch (LinkageError error) {
            throw linkageFailure(error);
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(nativeErrorOr(
                    "Native WireGuard gateway returned no statistics"));
        }
        return parseStats(value, "returned invalid statistics");
    }

    /** Stops the native thread and returns its final post-join counter snapshot. */
    public synchronized WireGuardGatewayStats stopAndGetStats() {
        long previous = handle;
        handle = 0;
        if (previous == 0) return WireGuardGatewayStats.stopped();
        final String value;
        try {
            value = nativeStop(previous);
        } catch (LinkageError error) {
            throw linkageFailure(error);
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(nativeErrorOr(
                    "Native WireGuard gateway failed to stop cleanly"));
        }
        return parseStats(value, "returned invalid final statistics");
    }

    private static WireGuardGatewayStats parseStats(String value, String failure) {
        try {
            JSONObject json = new JSONObject(value);
            return new WireGuardGatewayStats(
                    json.getBoolean("running"),
                    json.getLong("uploaded_bytes"),
                    json.getLong("downloaded_bytes"),
                    boundedInt(json.getLong("active_tcp_flows")),
                    boundedInt(json.getLong("active_udp_flows")),
                    json.getLong("total_tcp_flows"),
                    json.getLong("total_udp_flows"),
                    json.getLong("last_handshake_ms"),
                    json.isNull("fatal_error") ? null : json.optString("fatal_error", null));
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Native WireGuard gateway " + failure, exception);
        }
    }

    @Override
    public synchronized void close() {
        stopAndGetStats();
    }

    private static void requireAvailable() {
        if (LOAD_FAILURE != null) {
            throw new IllegalStateException(
                    "WireGuard native gateway is unavailable on this device", LOAD_FAILURE);
        }
    }

    private static int boundedInt(long value) {
        if (value <= 0) return 0;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String nativeErrorOr(String fallback) {
        try {
            String message = nativeGetLastError();
            return message == null || message.trim().isEmpty() ? fallback : message;
        } catch (LinkageError ignored) {
            return fallback;
        }
    }

    private static IllegalStateException linkageFailure(LinkageError error) {
        return new IllegalStateException(
                "WireGuard native gateway is incompatible with this build", error);
    }

    private static native long nativeStart(String configJson);
    private static native String nativeStop(long handle);
    private static native String nativeGetStats(long handle);
    private static native String nativeGenerateKeyPair();
    private static native String nativeGetLastError();

    public static final class Config {
        private final WireGuardKey privateKey;
        private final WireGuardKey peerPublicKey;
        private final int port;
        private final long networkHandle;
        private final boolean requireBoundNetwork;

        public Config(WireGuardKey privateKey, WireGuardKey peerPublicKey, int port,
                      long networkHandle, boolean requireBoundNetwork) {
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
            this.peerPublicKey = Objects.requireNonNull(peerPublicKey, "peerPublicKey");
            if (port < 1024 || port > 65_535) {
                throw new IllegalArgumentException(
                        "WireGuard port must be between 1024 and 65535");
            }
            if (networkHandle < 0 || requireBoundNetwork && networkHandle == 0) {
                throw new IllegalArgumentException(
                        "A selected Android network is required for fail-closed mode");
            }
            this.port = port;
            this.networkHandle = networkHandle;
            this.requireBoundNetwork = requireBoundNetwork;
        }

        String toJson() {
            try {
                return new JSONObject()
                        .put("private_key", privateKey.getEncoded())
                        .put("peer_public_key", peerPublicKey.getEncoded())
                        .put("listen", "0.0.0.0:" + port)
                        .put("network_handle", networkHandle)
                        .put("require_bound_network", requireBoundNetwork)
                        .put("peer_ipv4", "10.66.0.2")
                        .put("peer_ipv6", "fd66::2")
                        .put("mtu", 1280)
                        .put("tcp_buffer_size", 65_536)
                        .put("max_tcp_flows", 256)
                        .put("max_udp_flows", 512)
                        .toString();
            } catch (JSONException exception) {
                throw new IllegalStateException(
                        "Unable to serialize WireGuard gateway configuration", exception);
            }
        }
    }

    public static final class KeyPair {
        private final WireGuardKey privateKey;
        private final WireGuardKey publicKey;

        public KeyPair(WireGuardKey privateKey, WireGuardKey publicKey) {
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
            this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        }

        public WireGuardKey getPrivateKey() {
            return privateKey;
        }

        public WireGuardKey getPublicKey() {
            return publicKey;
        }

        @Override
        public String toString() {
            return "WireGuardNativeGateway.KeyPair[redacted]";
        }
    }
}
