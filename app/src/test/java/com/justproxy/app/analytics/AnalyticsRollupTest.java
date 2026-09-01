package com.justproxy.app.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class AnalyticsRollupTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    public void trafficDeltaContainsBytesButNoSessionCount() {
        AnalyticsRollup delta = AnalyticsRollup.trafficDelta(120L, 340L, 1_000L, UTC);

        assertEquals(0L, delta.getSessionCount());
        assertEquals(120L, delta.getUploadedBytes());
        assertEquals(340L, delta.getDownloadedBytes());
        assertFalse(delta.isEmpty());
    }

    @Test
    public void trafficDeltasAreAttributedToTheirOwnLocalDays() {
        ZoneId athens = ZoneId.of("Europe/Athens");
        long beforeMidnight = LocalDateTime.of(2026, 8, 31, 23, 59)
                .atZone(athens).toInstant().toEpochMilli();
        long afterMidnight = LocalDateTime.of(2026, 9, 1, 0, 1)
                .atZone(athens).toInstant().toEpochMilli();

        AnalyticsRollup first = AnalyticsRollup.trafficDelta(10L, 20L, beforeMidnight, athens);
        AnalyticsRollup second = AnalyticsRollup.trafficDelta(30L, 40L, afterMidnight, athens);

        assertEquals("2026-08-31", first.getDayKey());
        assertEquals("2026-09-01", second.getDayKey());

        Totals totals = new Totals();
        totals.apply(first);
        totals.apply(second);
        assertEquals(40L, totals.uploaded);
        assertEquals(60L, totals.downloaded);
        assertEquals(10L, totals.uploadedByDay.get("2026-08-31").longValue());
        assertEquals(30L, totals.uploadedByDay.get("2026-09-01").longValue());
    }

    @Test
    public void metadataOnlyCompletionCountsSessionWithoutCountingBytesAgain() {
        ProxySessionRecord session = session(500L, 700L);
        AnalyticsRollup checkpoint = AnalyticsRollup.trafficDelta(500L, 700L, 2_000L, UTC);
        AnalyticsRollup completion = AnalyticsRollup.completedSession(session, false);

        Totals totals = new Totals();
        totals.apply(checkpoint);
        totals.apply(completion);

        assertEquals(1L, totals.sessions);
        assertEquals(500L, totals.uploaded);
        assertEquals(700L, totals.downloaded);
        assertEquals(0L, completion.getUploadedBytes());
        assertEquals(0L, completion.getDownloadedBytes());
        assertEquals(500L, session.getUploadedBytes());
        assertEquals(700L, session.getDownloadedBytes());
    }

    @Test
    public void legacyCompletionStillCountsSessionAndBytes() {
        AnalyticsRollup legacy = AnalyticsRollup.completedSession(session(50L, 70L), true);

        assertEquals(1L, legacy.getSessionCount());
        assertEquals(50L, legacy.getUploadedBytes());
        assertEquals(70L, legacy.getDownloadedBytes());
    }

    @Test
    public void zeroTrafficCheckpointIsAnEmptyNoOp() {
        assertTrue(AnalyticsRollup.trafficDelta(0L, 0L, 0L, UTC).isEmpty());
    }

    @Test
    public void rejectsNegativeTrafficAndInvalidInputs() {
        expectIllegalArgument(() -> AnalyticsRollup.trafficDelta(-1L, 0L, 0L, UTC));
        expectIllegalArgument(() -> AnalyticsRollup.trafficDelta(0L, -1L, 0L, UTC));
        expectIllegalArgument(() -> AnalyticsRollup.trafficDelta(0L, 0L, -1L, UTC));
        expectIllegalArgument(() -> AnalyticsRollup.trafficDelta(0L, 0L, 0L, null));
        expectIllegalArgument(() -> AnalyticsRollup.completedSession(null, false));
    }

    private static ProxySessionRecord session(long uploaded, long downloaded) {
        return new ProxySessionRecord(
                1_000L, 2_000L, "127.0.0.1", "HTTP", "example.com:443",
                uploaded, downloaded, "COMPLETED");
    }

    private static void expectIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static final class Totals {
        private long sessions;
        private long uploaded;
        private long downloaded;
        private final Map<String, Long> uploadedByDay = new HashMap<>();

        private void apply(AnalyticsRollup rollup) {
            sessions += rollup.getSessionCount();
            uploaded += rollup.getUploadedBytes();
            downloaded += rollup.getDownloadedBytes();
            uploadedByDay.put(
                    rollup.getDayKey(),
                    uploadedByDay.getOrDefault(rollup.getDayKey(), 0L)
                            + rollup.getUploadedBytes());
        }
    }
}
