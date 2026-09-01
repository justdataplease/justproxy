package com.justproxy.app.analytics;

/** Immutable traffic and public-IP totals suitable for rendering in the main screen. */
public final class AnalyticsSummary {
    private final long lifetimeSessionCount;
    private final long lifetimeUploadedBytes;
    private final long lifetimeDownloadedBytes;
    private final long todaySessionCount;
    private final long todayUploadedBytes;
    private final long todayDownloadedBytes;
    private final long publicIpObservationCount;
    private final long publicIpChangeCount;
    private final String currentPublicIp;
    private final long currentPublicIpObservedAtMillis;

    AnalyticsSummary(
            long lifetimeSessionCount,
            long lifetimeUploadedBytes,
            long lifetimeDownloadedBytes,
            long todaySessionCount,
            long todayUploadedBytes,
            long todayDownloadedBytes,
            long publicIpObservationCount,
            long publicIpChangeCount,
            String currentPublicIp,
            long currentPublicIpObservedAtMillis) {
        this.lifetimeSessionCount = lifetimeSessionCount;
        this.lifetimeUploadedBytes = lifetimeUploadedBytes;
        this.lifetimeDownloadedBytes = lifetimeDownloadedBytes;
        this.todaySessionCount = todaySessionCount;
        this.todayUploadedBytes = todayUploadedBytes;
        this.todayDownloadedBytes = todayDownloadedBytes;
        this.publicIpObservationCount = publicIpObservationCount;
        this.publicIpChangeCount = publicIpChangeCount;
        this.currentPublicIp = currentPublicIp;
        this.currentPublicIpObservedAtMillis = currentPublicIpObservedAtMillis;
    }

    public long getLifetimeSessionCount() {
        return lifetimeSessionCount;
    }

    public long getLifetimeUploadedBytes() {
        return lifetimeUploadedBytes;
    }

    public long getLifetimeDownloadedBytes() {
        return lifetimeDownloadedBytes;
    }

    public long getLifetimeTotalBytes() {
        return lifetimeUploadedBytes + lifetimeDownloadedBytes;
    }

    public long getTodaySessionCount() {
        return todaySessionCount;
    }

    public long getTodayUploadedBytes() {
        return todayUploadedBytes;
    }

    public long getTodayDownloadedBytes() {
        return todayDownloadedBytes;
    }

    public long getTodayTotalBytes() {
        return todayUploadedBytes + todayDownloadedBytes;
    }

    public long getPublicIpObservationCount() {
        return publicIpObservationCount;
    }

    public long getPublicIpChangeCount() {
        return publicIpChangeCount;
    }

    /** Returns null until the first successful public-IP check is recorded. */
    public String getCurrentPublicIp() {
        return currentPublicIp;
    }

    public long getCurrentPublicIpObservedAtMillis() {
        return currentPublicIpObservedAtMillis;
    }
}
