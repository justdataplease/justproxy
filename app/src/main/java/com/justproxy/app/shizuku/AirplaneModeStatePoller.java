package com.justproxy.app.shizuku;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Pure bounded poller used to verify that Android reports airplane mode disabled. */
final class AirplaneModeStatePoller {
    interface Clock {
        long nanoTime();
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AirplaneModeStateReader reader;
    private final Clock clock;
    private final Sleeper sleeper;

    AirplaneModeStatePoller(
            AirplaneModeStateReader reader, Clock clock, Sleeper sleeper) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    AirplaneModeStateReader.State awaitDisabled(long timeoutMillis, long pollMillis) {
        if (timeoutMillis < 0L || pollMillis <= 0L) {
            throw new IllegalArgumentException("Polling durations are invalid");
        }
        long startedNanos = clock.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        AirplaneModeStateReader.State state = AirplaneModeStateReader.State.UNKNOWN;
        while (true) {
            state = reader.read();
            if (state == AirplaneModeStateReader.State.DISABLED) return state;

            long elapsedNanos = Math.max(0L, clock.nanoTime() - startedNanos);
            if (elapsedNanos >= timeoutNanos) return state;
            long remainingMillis = Math.max(
                    1L, TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsedNanos));
            try {
                sleeper.sleep(Math.min(pollMillis, remainingMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return AirplaneModeStateReader.State.UNKNOWN;
            }
        }
    }
}
