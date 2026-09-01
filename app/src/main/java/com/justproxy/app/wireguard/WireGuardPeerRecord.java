package com.justproxy.app.wireguard;

import java.util.Objects;

/** Immutable key material and identity for JustProxy's single-peer beta. */
public final class WireGuardPeerRecord {
    private final WireGuardProfileName peerName;
    private final long createdAtMillis;
    private final WireGuardKey serverPrivateKey;
    private final WireGuardKey serverPublicKey;
    private final WireGuardKey clientPrivateKey;
    private final WireGuardKey clientPublicKey;

    public WireGuardPeerRecord(
            WireGuardProfileName peerName,
            long createdAtMillis,
            WireGuardKey serverPrivateKey,
            WireGuardKey serverPublicKey,
            WireGuardKey clientPrivateKey,
            WireGuardKey clientPublicKey) {
        this.peerName = Objects.requireNonNull(peerName, "peerName");
        if (createdAtMillis <= 0) {
            throw new IllegalArgumentException("createdAtMillis must be positive");
        }
        this.createdAtMillis = createdAtMillis;
        this.serverPrivateKey = Objects.requireNonNull(serverPrivateKey, "serverPrivateKey");
        this.serverPublicKey = Objects.requireNonNull(serverPublicKey, "serverPublicKey");
        this.clientPrivateKey = Objects.requireNonNull(clientPrivateKey, "clientPrivateKey");
        this.clientPublicKey = Objects.requireNonNull(clientPublicKey, "clientPublicKey");
    }

    public WireGuardProfileName getPeerName() {
        return peerName;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public WireGuardKey getServerPrivateKey() {
        return serverPrivateKey;
    }

    public WireGuardKey getServerPublicKey() {
        return serverPublicKey;
    }

    public WireGuardKey getClientPrivateKey() {
        return clientPrivateKey;
    }

    public WireGuardKey getClientPublicKey() {
        return clientPublicKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WireGuardPeerRecord)) {
            return false;
        }
        WireGuardPeerRecord that = (WireGuardPeerRecord) other;
        return createdAtMillis == that.createdAtMillis
                && peerName.equals(that.peerName)
                && serverPrivateKey.equals(that.serverPrivateKey)
                && serverPublicKey.equals(that.serverPublicKey)
                && clientPrivateKey.equals(that.clientPrivateKey)
                && clientPublicKey.equals(that.clientPublicKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerName, createdAtMillis, serverPrivateKey, serverPublicKey,
                clientPrivateKey, clientPublicKey);
    }

    /** Does not expose private or public key material in logs. */
    @Override
    public String toString() {
        return "WireGuardPeerRecord{name=" + peerName + ", createdAtMillis="
                + createdAtMillis + ", keys=[redacted]}";
    }
}
