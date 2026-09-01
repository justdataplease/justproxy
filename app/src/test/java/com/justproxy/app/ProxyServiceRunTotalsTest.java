package com.justproxy.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProxyServiceRunTotalsTest {
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

        checkpoint.reset();
        ProxyService.TrafficCheckpoint.Delta afterReset = checkpoint.pending(5, 7);
        assertEquals(5L, afterReset.uploadedBytes);
        assertEquals(7L, afterReset.downloadedBytes);
    }
}
