package com.justproxy.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.justproxy.app.wireguard.WireGuardGatewayStats;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class ProxyServiceRunTotalsTest {
    @Test
    public void startAndRestartAreStickyWhileStopIsNot() {
        assertEquals(android.app.Service.START_NOT_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_STOP, true));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_START, false));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_RESTART, false));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(null, false));
    }

    @Test
    public void utilityActionsAreStickyOnlyDuringAnActiveRun() {
        assertEquals(android.app.Service.START_NOT_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_ROTATE, false));
        assertEquals(android.app.Service.START_NOT_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_REFRESH_IP, false));
        assertEquals(android.app.Service.START_NOT_STICKY,
                ProxyService.restartModeForAction(
                        ProxyService.ACTION_RELOAD_WIREGUARD_PEER, false));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_ROTATE, true));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(ProxyService.ACTION_REFRESH_IP, true));
        assertEquals(android.app.Service.START_STICKY,
                ProxyService.restartModeForAction(
                        ProxyService.ACTION_RELOAD_WIREGUARD_PEER, true));
    }

    @Test
    public void wireGuardRecoveryIsBoundedAndHonorsBackoff() {
        assertEquals(ProxyService.WireGuardRetryDecision.ATTEMPT,
                ProxyService.decideWireGuardRetry(
                        true, true, true, false, true, 0, 3, 1_000, 1_000));
        assertEquals(ProxyService.WireGuardRetryDecision.WAIT,
                ProxyService.decideWireGuardRetry(
                        true, true, true, false, true, 1, 3, 999, 1_000));
        assertEquals(ProxyService.WireGuardRetryDecision.EXHAUSTED,
                ProxyService.decideWireGuardRetry(
                        true, true, true, false, true, 3, 3, 1_000, 0));
        assertEquals(ProxyService.WireGuardRetryDecision.NONE,
                ProxyService.decideWireGuardRetry(
                        true, true, true, true, true, 0, 3, 1_000, 0));
        assertEquals(ProxyService.WireGuardRetryDecision.NONE,
                ProxyService.decideWireGuardRetry(
                        true, true, true, false, false, 0, 3, 1_000, 0));
        assertEquals(ProxyService.WireGuardRetryDecision.NONE,
                ProxyService.decideWireGuardRetry(
                        true, true, false, false, true, 0, 3, 1_000, 0));
    }

    @Test
    public void allWireGuardFailureVariantsAreErrors() {
        assertTrue(ProxyService.isWireGuardErrorMessage("WireGuard failed: native"));
        assertTrue(ProxyService.isWireGuardErrorMessage("WireGuard status failed: JNI"));
        assertTrue(ProxyService.isWireGuardErrorMessage("WireGuard reconnect failed: port"));
        assertTrue(ProxyService.isWireGuardErrorMessage("WireGuard restart failed: socket"));
        assertTrue(ProxyService.isWireGuardErrorMessage("WireGuard shutdown failed: thread"));
        assertFalse(ProxyService.isWireGuardErrorMessage("Listening for the computer"));
        assertFalse(ProxyService.isWireGuardErrorMessage(null));
    }

    @Test
    public void healthyWireGuardStatsClearOnlyStaleErrorMessages() {
        WireGuardGatewayStats running = new WireGuardGatewayStats(
                true, 0, 0, 0, 0, 0, 0, 0);
        assertEquals("Listening for the computer on UDP 51820",
                ProxyService.wireGuardMessageAfterStats(
                        "WireGuard status failed: JNI", running, 51820));
        assertEquals("WireGuard automatically restarted (1/3)",
                ProxyService.wireGuardMessageAfterStats(
                        "WireGuard automatically restarted (1/3)", running, 51820));
    }

    @Test
    public void stoppedWireGuardStatsExposeTerminalReason() {
        WireGuardGatewayStats stopped = WireGuardGatewayStats.stopped();
        assertEquals("WireGuard failed: gateway stopped unexpectedly",
                ProxyService.wireGuardMessageAfterStats("Listening", stopped, 51820));

        WireGuardGatewayStats fatal = new WireGuardGatewayStats(
                false, 0, 0, 0, 0, 0, 0, 0, "native loop failed");
        assertEquals("WireGuard failed: native loop failed",
                ProxyService.wireGuardMessageAfterStats("Listening", fatal, 51820));
    }

    @Test
    public void accumulatesReplacedServersUntilTrueRunReset() {
        ProxyService.RunTotals totals = new ProxyService.RunTotals();
        totals.add(100, 200, 2);
        totals.add(30, 40, 1);

        assertEquals(130L, totals.uploadedBytesWith(null));
        assertEquals(240L, totals.downloadedBytesWith(null));
        assertEquals(370L, totals.trafficBytesWith(null));
        assertEquals(3L, totals.totalConnectionsWith(null));

        totals.reset();
        assertEquals(0L, totals.trafficBytesWith(null));
        assertEquals(0L, totals.totalConnectionsWith(null));
    }

    @Test
    public void saturatesInsteadOfOverflowingDataCapAccounting() {
        ProxyService.RunTotals totals = new ProxyService.RunTotals();
        totals.add(Long.MAX_VALUE - 5, 4, Long.MAX_VALUE);
        totals.add(10, 2, 1);

        assertEquals(Long.MAX_VALUE, totals.uploadedBytesWith(null));
        assertEquals(Long.MAX_VALUE, totals.trafficBytesWith(null));
        assertEquals(Long.MAX_VALUE, totals.totalConnectionsWith(null));
    }

    @Test
    public void wireGuardCountersSurviveGatewayReplacementAndFeedTheDataCap() {
        ProxyService.RunTotals totals = new ProxyService.RunTotals();
        WireGuardGatewayStats first = new WireGuardGatewayStats(
                true, 100, 200, 1, 2, 4, 5, 1_000);

        assertEquals(100L, totals.uploadedBytesWith(null, first));
        assertEquals(200L, totals.downloadedBytesWith(null, first));
        assertEquals(300L, totals.trafficBytesWith(null, first));
        assertEquals(9L, totals.totalConnectionsWith(null, first));

        totals.add(first);
        WireGuardGatewayStats replacement = new WireGuardGatewayStats(
                true, 10, 20, 0, 1, 1, 2, 2_000);
        assertEquals(110L, totals.uploadedBytesWith(null, replacement));
        assertEquals(220L, totals.downloadedBytesWith(null, replacement));
        assertEquals(330L, totals.trafficBytesWith(null, replacement));
        assertEquals(12L, totals.totalConnectionsWith(null, replacement));
    }

    @Test
    public void coalescesManyIpRequestsIntoOneGuaranteedFollowUp() {
        ProxyService.IpCheckGate gate = new ProxyService.IpCheckGate();

        assertTrue(gate.startOrQueue());
        assertFalse(gate.startOrQueue());
        assertFalse(gate.startOrQueue());
        assertTrue(gate.finishAndShouldRetry(true));

        assertTrue(gate.startOrQueue());
        assertFalse(gate.finishAndShouldRetry(true));
    }

    @Test
    public void doesNotRetryPendingIpCheckAfterRunStops() {
        ProxyService.IpCheckGate gate = new ProxyService.IpCheckGate();
        assertTrue(gate.startOrQueue());
        assertFalse(gate.startOrQueue());
        gate.cancelPending();
        assertFalse(gate.finishAndShouldRetry(false));
    }

    @Test
    public void trafficCheckpointAdvancesOnlyAfterExplicitCommit() {
        ProxyService.TrafficCheckpoint checkpoint = new ProxyService.TrafficCheckpoint();
        ProxyService.TrafficCheckpoint.Delta first = checkpoint.pending(100, 200);
        assertEquals(100L, first.uploadedBytes);
        assertEquals(200L, first.downloadedBytes);

        // Simulate a failed database transaction by deliberately not committing.
        ProxyService.TrafficCheckpoint.Delta retry = checkpoint.pending(100, 200);
        assertEquals(100L, retry.uploadedBytes);
        assertEquals(200L, retry.downloadedBytes);

        checkpoint.commit(retry);
        ProxyService.TrafficCheckpoint.Delta next = checkpoint.pending(130, 240);
        assertEquals(30L, next.uploadedBytes);
        assertEquals(40L, next.downloadedBytes);
    }

    @Test
    public void trafficCheckpointNeverProducesNegativeDeltasAndCanReset() {
        ProxyService.TrafficCheckpoint checkpoint = new ProxyService.TrafficCheckpoint();
        ProxyService.TrafficCheckpoint.Delta initial = checkpoint.pending(50, 60);
        checkpoint.commit(initial);

        ProxyService.TrafficCheckpoint.Delta lower = checkpoint.pending(40, 20);
        assertEquals(0L, lower.uploadedBytes);
        assertEquals(0L, lower.downloadedBytes);
        checkpoint.commit(lower);

        ProxyService.TrafficCheckpoint.Delta recovered = checkpoint.pending(55, 67);
        assertEquals(5L, recovered.uploadedBytes);
        assertEquals(7L, recovered.downloadedBytes);

        checkpoint.reset();
        ProxyService.TrafficCheckpoint.Delta afterReset = checkpoint.pending(5, 7);
        assertEquals(5L, afterReset.uploadedBytes);
        assertEquals(7L, afterReset.downloadedBytes);
    }

    @Test
    public void analyticsSummaryCacheLoadsAtMostOncePerWindow() {
        ProxyService.TimedCache<String> cache = new ProxyService.TimedCache<>(5_000L);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("summary-1", cache.get(1_000L,
                () -> "summary-" + loads.incrementAndGet()));
        assertEquals("summary-1", cache.get(5_999L,
                () -> "summary-" + loads.incrementAndGet()));
        assertEquals(1, loads.get());

        assertEquals("summary-2", cache.get(6_000L,
                () -> "summary-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }

    @Test
    public void analyticsSummaryCacheRefreshesAfterWriteInvalidation() {
        ProxyService.TimedCache<String> cache = new ProxyService.TimedCache<>(5_000L);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("summary-1", cache.get(1_000L,
                () -> "summary-" + loads.incrementAndGet()));
        cache.invalidate();
        assertEquals("summary-2", cache.get(1_001L,
                () -> "summary-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }
}
