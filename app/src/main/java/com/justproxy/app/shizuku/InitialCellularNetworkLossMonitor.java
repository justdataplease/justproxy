package com.justproxy.app.shizuku;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Pure bounded watcher for the cellular handles captured before airplane mode changes. */
final class InitialCellularNetworkLossMonitor<T> implements CellularNetworkLossMonitor {
    interface ConnectivityProbe<T> {
        boolean isConnected(T network);
    }

    interface Clock {
        long nanoTime();
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final List<T> initialNetworks;
    private final ConnectivityProbe<T> probe;
    private final Clock clock;
    private final Sleeper sleeper;
    private final long pollMillis;

    InitialCellularNetworkLossMonitor(
            List<T> initialNetworks,
            ConnectivityProbe<T> probe,
            Clock clock,
            Sleeper sleeper,
            long pollMillis) {
        Objects.requireNonNull(initialNetworks, "initialNetworks");
        if (initialNetworks.isEmpty()) {
            throw new IllegalStateException(
                    "No connected cellular network is available to observe");
        }
        if (pollMillis <= 0L) throw new IllegalArgumentException("Poll time must be positive");
        this.initialNetworks = List.copyOf(initialNetworks);
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.pollMillis = pollMillis;
    }

    @Override
    public boolean awaitLoss(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("Loss timeout cannot be negative");
        }
        long startedNanos = clock.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (hasInitialCellularNetwork()) {
            long elapsedNanos = Math.max(0L, clock.nanoTime() - startedNanos);
            if (elapsedNanos >= timeoutNanos) return false;
            long remainingMillis = Math.max(
                    1L, TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsedNanos));
            sleeper.sleep(Math.min(pollMillis, remainingMillis));
        }
        return true;
    }

    private boolean hasInitialCellularNetwork() {
        for (T network : initialNetworks) {
            if (probe.isConnected(network)) return true;
        }
        return false;
    }

    @Override public void close() { }
}
