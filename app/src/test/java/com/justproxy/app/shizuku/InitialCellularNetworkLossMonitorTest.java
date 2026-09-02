package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class InitialCellularNetworkLossMonitorTest {
    @Test
    public void emptyInitialSnapshotFailsClosed() {
        try {
            monitor(List.of(), network -> false, new AtomicLong());
            fail("Expected empty snapshot rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No connected cellular"));
        }
    }

    @Test
    public void unreadableNetworkStateDoesNotCountAsLoss() throws Exception {
        InitialCellularNetworkLossMonitor<String> monitor = monitor(
                List.of("cellular"),
                network -> { throw new SecurityException("unreadable"); },
                new AtomicLong());

        try {
            monitor.awaitLoss(1_000L);
            fail("Expected state read failure");
        } catch (SecurityException expected) {
            assertEquals("unreadable", expected.getMessage());
        }
    }

    @Test
    public void waitsUntilEveryInitialHandleIsGone() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean firstConnected = new AtomicBoolean(true);
        AtomicBoolean secondConnected = new AtomicBoolean(true);
        InitialCellularNetworkLossMonitor<String> monitor = new InitialCellularNetworkLossMonitor<>(
                List.of("first", "second"),
                network -> "first".equals(network)
                        ? firstConnected.get() : secondConnected.get(),
                clock::get,
                millis -> {
                    firstConnected.set(false);
                    secondConnected.set(false);
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
                },
                100L);

        assertTrue(monitor.awaitLoss(1_000L));
    }

    @Test
    public void replacementHandleDoesNotHideLossOfCapturedHandle() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean initialConnected = new AtomicBoolean(false);
        AtomicBoolean replacementConnected = new AtomicBoolean(true);
        InitialCellularNetworkLossMonitor<String> monitor = monitor(
                List.of("initial"),
                network -> initialConnected.get(),
                clock);

        assertTrue(replacementConnected.get());
        assertTrue(monitor.awaitLoss(1_000L));
    }

    @Test
    public void connectedInitialHandleTimesOut() throws Exception {
        AtomicLong clock = new AtomicLong();
        InitialCellularNetworkLossMonitor<String> monitor = monitor(
                List.of("initial"), network -> true, clock);

        assertFalse(monitor.awaitLoss(250L));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250L), clock.get());
    }

    private static <T> InitialCellularNetworkLossMonitor<T> monitor(
            List<T> networks,
            InitialCellularNetworkLossMonitor.ConnectivityProbe<T> probe,
            AtomicLong clock) {
        return new InitialCellularNetworkLossMonitor<>(
                networks,
                probe,
                clock::get,
                millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)),
                100L);
    }
}
