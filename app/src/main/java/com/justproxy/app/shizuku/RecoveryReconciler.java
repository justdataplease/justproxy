package com.justproxy.app.shizuku;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Pure recovery-marker reconciliation that never depends on Shizuku. */
final class RecoveryReconciler {
    interface MarkerClearer {
        boolean clear();
    }

    interface Clock {
        long nanoTime();
    }

    private final MobileDataStateReader stateReader;
    private final MarkerClearer markerClearer;
    private final Clock clock;

    RecoveryReconciler(
            MobileDataStateReader stateReader, MarkerClearer markerClearer, Clock clock) {
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.markerClearer = Objects.requireNonNull(markerClearer, "markerClearer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    MobileDataCommandResult reconcile() {
        long startedNanos = clock.nanoTime();
        MobileDataStateReader.State state = stateReader.read();
        if (state == MobileDataStateReader.State.DISABLED) {
            throw new IllegalStateException(
                    "Mobile data is still disabled. Enable it in Android settings, then retry recovery.");
        }
        if (state != MobileDataStateReader.State.ENABLED) {
            throw new IllegalStateException(
                    "Android could not read mobile-data state. Check the SIM and phone state, then retry recovery.");
        }
        if (!markerClearer.clear()) {
            throw new IllegalStateException(
                    "Mobile data is enabled, but JustProxy could not clear its recovery state. Retry recovery.");
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
                "Mobile data is enabled; recovery state was cleared",
                "");
    }
}
