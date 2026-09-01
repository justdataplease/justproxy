package com.justproxy.app.wireguard;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable server peer settings for a dual-stack, full-tunnel client profile. */
public final class WireGuardPeer {
    private static final List<String> FULL_TUNNEL_ALLOWED_IPS =
            List.of("0.0.0.0/0", "::/0");

    private final WireGuardKey publicKey;
    private final WireGuardKey presharedKey;
    private final WireGuardEndpoint endpoint;
    private final int persistentKeepaliveSeconds;

    private WireGuardPeer(Builder builder) {
        publicKey = builder.publicKey;
        presharedKey = builder.presharedKey;
        endpoint = builder.endpoint;
        persistentKeepaliveSeconds = builder.persistentKeepaliveSeconds;
    }

    public static Builder builder(WireGuardKey publicKey, WireGuardEndpoint endpoint) {
        return new Builder(publicKey, endpoint);
    }

    public WireGuardKey getPublicKey() {
        return publicKey;
    }

    public Optional<WireGuardKey> getPresharedKey() {
        return Optional.ofNullable(presharedKey);
    }

    public WireGuardEndpoint getEndpoint() {
        return endpoint;
    }

    /** Always contains both IPv4 and IPv6 default routes for a full tunnel. */
    public List<String> getAllowedIps() {
        return FULL_TUNNEL_ALLOWED_IPS;
    }

    public int getPersistentKeepaliveSeconds() {
        return persistentKeepaliveSeconds;
    }

    public static final class Builder {
        private final WireGuardKey publicKey;
        private final WireGuardEndpoint endpoint;
        private WireGuardKey presharedKey;
        private int persistentKeepaliveSeconds = 25;

        private Builder(WireGuardKey publicKey, WireGuardEndpoint endpoint) {
            this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        public Builder presharedKey(WireGuardKey presharedKey) {
            this.presharedKey = Objects.requireNonNull(presharedKey, "presharedKey");
            return this;
        }

        /** Zero disables keepalive; otherwise the value is expressed in seconds. */
        public Builder persistentKeepaliveSeconds(int persistentKeepaliveSeconds) {
            this.persistentKeepaliveSeconds = persistentKeepaliveSeconds;
            return this;
        }

        public WireGuardPeer build() {
            if (persistentKeepaliveSeconds < 0 || persistentKeepaliveSeconds > 65_535) {
                throw new IllegalArgumentException(
                        "persistentKeepaliveSeconds must be between 0 and 65535");
            }
            return new WireGuardPeer(this);
        }
    }
}
