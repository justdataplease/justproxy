package com.justproxy.app.shizuku;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Pure recovery-marker reconciliation that never depends on Shizuku. */
final class AirplaneModeRecoveryReconciler {
    interface MarkerClearer {
        boolean clear();
    }

    interface Clock {
        long nanoTime();
    }

    private final AirplaneModeStateReader stateReader;
    private final MarkerClearer markerClearer;
    private final Clock clock;

    AirplaneModeRecoveryReconciler(
            AirplaneModeStateReader stateReader, MarkerClearer markerClearer, Clock clock) {
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.markerClearer = Objects.requireNonNull(markerClearer, "markerClearer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    MobileDataCommandResult reconcile() {
        long startedNanos = clock.nanoTime();
        AirplaneModeStateReader.State state = stateReader.read();
        if (state == AirplaneModeStateReader.State.ENABLED) {
            throw new IllegalStateException(
                    "Airplane mode is still enabled. Turn it off in Android settings, then retry recovery.");
        }
        if (state != AirplaneModeStateReader.State.DISABLED) {
            throw new IllegalStateException(
                    "Android could not read airplane-mode state. Turn airplane mode off in Android settings, then retry recovery.");
        }
        if (!markerClearer.clear()) {
            throw new IllegalStateException(
                    "Airplane mode is disabled, but JustProxy could not clear its recovery state. Retry recovery.");
        }
        return new MobileDataCommandResult(
                MobileDataCommandResult.OPERATION_RECONCILE_RECOVERY,
                MobileDataCommandResult.STATUS_OK,
                -1,
                false,
                false,
                true,
                CommandExecution.NO_EXIT_CODE,
                CommandExecution.NO_EXIT_CODE,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, clock.nanoTime() - startedNanos)),
                "Airplane mode is disabled; recovery state was cleared",
                "");
    }
}
