package com.justproxy.app.wireguard;

/** Immutable snapshot from the native userspace WireGuard gateway. */
public final class WireGuardGatewayStats {
    private final boolean running;
    private final long uploadedBytes;
    private final long downloadedBytes;
    private final int activeTcpFlows;
    private final int activeUdpFlows;
    private final long totalTcpFlows;
    private final long totalUdpFlows;
    private final long lastHandshakeMillis;
    private final String fatalError;

    public WireGuardGatewayStats(
            boolean running,
            long uploadedBytes,
            long downloadedBytes,
            int activeTcpFlows,
            int activeUdpFlows,
            long totalTcpFlows,
            long totalUdpFlows,
            long lastHandshakeMillis) {
        this(running, uploadedBytes, downloadedBytes, activeTcpFlows, activeUdpFlows,
                totalTcpFlows, totalUdpFlows, lastHandshakeMillis, null);
    }

    public WireGuardGatewayStats(
            boolean running,
            long uploadedBytes,
            long downloadedBytes,
            int activeTcpFlows,
            int activeUdpFlows,
            long totalTcpFlows,
            long totalUdpFlows,
            long lastHandshakeMillis,
            String fatalError) {
        this.running = running;
        this.uploadedBytes = nonNegative(uploadedBytes);
        this.downloadedBytes = nonNegative(downloadedBytes);
        this.activeTcpFlows = Math.max(0, activeTcpFlows);
        this.activeUdpFlows = Math.max(0, activeUdpFlows);
        this.totalTcpFlows = nonNegative(totalTcpFlows);
        this.totalUdpFlows = nonNegative(totalUdpFlows);
        this.lastHandshakeMillis = nonNegative(lastHandshakeMillis);
        this.fatalError = fatalError == null || fatalError.trim().isEmpty()
                ? null : fatalError.trim();
    }

    public static WireGuardGatewayStats stopped() {
        return new WireGuardGatewayStats(false, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean isRunning() { return running; }
    public long getUploadedBytes() { return uploadedBytes; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public int getActiveTcpFlows() { return activeTcpFlows; }
    public int getActiveUdpFlows() { return activeUdpFlows; }
    public int getActiveFlows() { return saturatedIntAdd(activeTcpFlows, activeUdpFlows); }
    public long getTotalTcpFlows() { return totalTcpFlows; }
    public long getTotalUdpFlows() { return totalUdpFlows; }
    public long getTotalFlows() { return saturatedAdd(totalTcpFlows, totalUdpFlows); }
    public long getLastHandshakeMillis() { return lastHandshakeMillis; }
    public String getFatalError() { return fatalError; }
    public boolean hasFatalError() { return fatalError != null; }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int saturatedIntAdd(int left, int right) {
        return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
    }
}
