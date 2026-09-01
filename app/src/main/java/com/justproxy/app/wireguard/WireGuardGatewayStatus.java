package com.justproxy.app.wireguard;

/** Immutable, secret-free gateway status exposed to the UI and control API. */
public final class WireGuardGatewayStatus {
    public enum State { DISABLED, NO_PROFILE, WAITING, RUNNING, ERROR }

    public final State state;
    public final String message;
    public final int port;
    public final int configuredPeers;
    public final int activeFlows;
    public final long totalFlows;
    public final long uploadedBytes;
    public final long downloadedBytes;
    public final long lastHandshakeMillis;

    public WireGuardGatewayStatus(
            State state,
            String message,
            int port,
            int configuredPeers,
            int activeFlows,
            long totalFlows,
            long uploadedBytes,
            long downloadedBytes,
            long lastHandshakeMillis) {
        this.state = state == null ? State.DISABLED : state;
        this.message = message == null || message.trim().isEmpty() ? "-" : message;
        this.port = Math.max(0, port);
        this.configuredPeers = Math.max(0, configuredPeers);
        this.activeFlows = Math.max(0, activeFlows);
        this.totalFlows = Math.max(0, totalFlows);
        this.uploadedBytes = Math.max(0, uploadedBytes);
        this.downloadedBytes = Math.max(0, downloadedBytes);
        this.lastHandshakeMillis = Math.max(0, lastHandshakeMillis);
    }

    public static WireGuardGatewayStatus disabled() {
        return new WireGuardGatewayStatus(
                State.DISABLED, "WireGuard gateway is disabled", 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }
}
