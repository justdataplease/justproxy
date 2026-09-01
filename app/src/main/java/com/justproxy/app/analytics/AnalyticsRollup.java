package com.justproxy.app.analytics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A validated aggregate mutation shared by the SQLite store and local JVM tests.
 *
 * <p>Keeping session and traffic increments separate is intentional: live byte checkpoints have
 * no session increment, while a completed-session metadata write has no byte increment.</p>
 */
final class AnalyticsRollup {
    private final String dayKey;
    private final long sessionCount;
    private final long uploadedBytes;
    private final long downloadedBytes;

    private AnalyticsRollup(
            String dayKey,
            long sessionCount,
            long uploadedBytes,
            long downloadedBytes) {
        this.dayKey = dayKey;
        this.sessionCount = sessionCount;
        this.uploadedBytes = uploadedBytes;
        this.downloadedBytes = downloadedBytes;
    }

    static AnalyticsRollup trafficDelta(
            long uploadedBytes,
            long downloadedBytes,
            long recordedAtMillis,
            ZoneId zoneId) {
        validateTimestamp(recordedAtMillis, "Traffic timestamp is invalid");
        validateBytes(uploadedBytes, downloadedBytes);
        return new AnalyticsRollup(
                dayKey(recordedAtMillis, zoneId), 0L, uploadedBytes, downloadedBytes);
    }

    static AnalyticsRollup completedSession(ProxySessionRecord session, boolean includeBytes) {
        if (session == null) {
            throw new IllegalArgumentException("Session is required");
        }
        return new AnalyticsRollup(
                dayKey(session.getEndedAtMillis(), ZoneId.systemDefault()),
                1L,
                includeBytes ? session.getUploadedBytes() : 0L,
                includeBytes ? session.getDownloadedBytes() : 0L);
    }

    static String dayKey(long timestampMillis, ZoneId zoneId) {
        validateTimestamp(timestampMillis, "Timestamp is invalid");
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone is required");
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.format(
                Instant.ofEpochMilli(timestampMillis).atZone(zoneId));
    }

    String getDayKey() {
        return dayKey;
    }

    long getSessionCount() {
        return sessionCount;
    }

    long getUploadedBytes() {
        return uploadedBytes;
    }

    long getDownloadedBytes() {
        return downloadedBytes;
    }

    boolean isEmpty() {
        return sessionCount == 0L && uploadedBytes == 0L && downloadedBytes == 0L;
    }

    private static void validateTimestamp(long timestampMillis, String message) {
        if (timestampMillis < 0L) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void validateBytes(long uploadedBytes, long downloadedBytes) {
        if (uploadedBytes < 0L || downloadedBytes < 0L) {
            throw new IllegalArgumentException("Traffic deltas cannot be negative");
        }
    }
}
