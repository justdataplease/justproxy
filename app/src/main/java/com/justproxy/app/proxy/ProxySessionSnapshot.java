package com.justproxy.app.proxy;

/** Immutable point-in-time view of an accepted client session. */
public final class ProxySessionSnapshot {
    private final long id;
    private final String clientAddress;
    private final ProxyProtocol protocol;
    private final String targetHost;
    private final int targetPort;
    private final String resolvedTargetAddress;
    private final boolean authenticated;
    private final long startedAtEpochMillis;
    private final long lastActivityAtEpochMillis;
    private final long endedAtEpochMillis;
    private final long bytesUploaded;
    private final long bytesDownloaded;
    private final SessionCloseReason closeReason;

    ProxySessionSnapshot(
            long id,
            String clientAddress,
            ProxyProtocol protocol,
            String targetHost,
            int targetPort,
            String resolvedTargetAddress,
            boolean authenticated,
            long startedAtEpochMillis,
            long lastActivityAtEpochMillis,
            long endedAtEpochMillis,
            long bytesUploaded,
            long bytesDownloaded,
            SessionCloseReason closeReason) {
        this.id = id;
        this.clientAddress = clientAddress;
        this.protocol = protocol;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.resolvedTargetAddress = resolvedTargetAddress;
        this.authenticated = authenticated;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.lastActivityAtEpochMillis = lastActivityAtEpochMillis;
        this.endedAtEpochMillis = endedAtEpochMillis;
        this.bytesUploaded = bytesUploaded;
        this.bytesDownloaded = bytesDownloaded;
        this.closeReason = closeReason;
    }

    public long getId() {
        return id;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public ProxyProtocol getProtocol() {
        return protocol;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public String getResolvedTargetAddress() {
        return resolvedTargetAddress;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long getLastActivityAtEpochMillis() {
        return lastActivityAtEpochMillis;
    }

    /** Zero while the session is active. */
    public long getEndedAtEpochMillis() {
        return endedAtEpochMillis;
    }

    public boolean isActive() {
        return endedAtEpochMillis == 0L;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public long getBytesDownloaded() {
        return bytesDownloaded;
    }

    /** Null while the session is active. */
    public SessionCloseReason getCloseReason() {
        return closeReason;
    }
}
