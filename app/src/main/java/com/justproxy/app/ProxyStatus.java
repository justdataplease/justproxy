package com.justproxy.app;

import com.justproxy.app.wireguard.WireGuardGatewayStatus;

/** Immutable service state safe for the activity to read from the main thread. */
public final class ProxyStatus {
    public enum State { STOPPED, STARTING, RUNNING, PAUSED, ERROR }
    public final State state;
    public final String message;
    public final String listenAddress;
    public final int port;
    public final String egress;
    public final String publicIp;
    public final long runUploadedBytes;
    public final long runDownloadedBytes;
    public final long todayUploadedBytes;
    public final long todayDownloadedBytes;
    public final long lifetimeUploadedBytes;
    public final long lifetimeDownloadedBytes;
    public final int activeConnections;
    public final long lifetimeSessions;
    public final long ipChangeCount;
    public final long startedAtMillis;
    public final long nextRotationAtMillis;
    public final WireGuardGatewayStatus wireGuard;

    public ProxyStatus(State state, String message, String listenAddress, int port,
                       String egress, String publicIp, long runUploadedBytes,
                       long runDownloadedBytes, long todayUploadedBytes,
                       long todayDownloadedBytes, long lifetimeUploadedBytes,
                       long lifetimeDownloadedBytes, int activeConnections,
                       long lifetimeSessions, long ipChangeCount, long startedAtMillis,
                       long nextRotationAtMillis, WireGuardGatewayStatus wireGuard) {
        this.state = state;
        this.message = valueOrDash(message);
        this.listenAddress = valueOrDash(listenAddress);
        this.port = port;
        this.egress = valueOrDash(egress);
        this.publicIp = valueOrDash(publicIp);
        this.runUploadedBytes = runUploadedBytes;
        this.runDownloadedBytes = runDownloadedBytes;
        this.todayUploadedBytes = todayUploadedBytes;
        this.todayDownloadedBytes = todayDownloadedBytes;
        this.lifetimeUploadedBytes = lifetimeUploadedBytes;
        this.lifetimeDownloadedBytes = lifetimeDownloadedBytes;
        this.activeConnections = activeConnections;
        this.lifetimeSessions = lifetimeSessions;
        this.ipChangeCount = ipChangeCount;
        this.startedAtMillis = startedAtMillis;
        this.nextRotationAtMillis = nextRotationAtMillis;
        this.wireGuard = wireGuard == null
                ? WireGuardGatewayStatus.disabled() : wireGuard;
    }

    public static ProxyStatus stopped() {
        return new ProxyStatus(State.STOPPED, "Proxy is off", "-", 0, "-", "-",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                WireGuardGatewayStatus.disabled());
    }

    public boolean isActive() {
        return state == State.STARTING || state == State.RUNNING
                || state == State.PAUSED || state == State.ERROR;
    }

    private static String valueOrDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }
}
