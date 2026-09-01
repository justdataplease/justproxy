package com.justproxy.app.proxy;

/** Immutable aggregate statistics for one {@link ProxyServer} instance. */
public final class ProxyStatsSnapshot {
    private final boolean running;
    private final long startedAtEpochMillis;
    private final long snapshotAtEpochMillis;
    private final long totalConnections;
    private final int activeConnections;
    private final long rejectedConnections;
    private final long bytesUploaded;
    private final long bytesDownloaded;

    ProxyStatsSnapshot(
            boolean running,
            long startedAtEpochMillis,
            long snapshotAtEpochMillis,
            long totalConnections,
            int activeConnections,
            long rejectedConnections,
            long bytesUploaded,
            long bytesDownloaded) {
        this.running = running;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.snapshotAtEpochMillis = snapshotAtEpochMillis;
        this.totalConnections = totalConnections;
        this.activeConnections = activeConnections;
        this.rejectedConnections = rejectedConnections;
        this.bytesUploaded = bytesUploaded;
        this.bytesDownloaded = bytesDownloaded;
    }

    public boolean isRunning() {
        return running;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long getSnapshotAtEpochMillis() {
        return snapshotAtEpochMillis;
    }

    public long getTotalConnections() {
        return totalConnections;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    public long getRejectedConnections() {
        return rejectedConnections;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public long getBytesDownloaded() {
        return bytesDownloaded;
    }
}
