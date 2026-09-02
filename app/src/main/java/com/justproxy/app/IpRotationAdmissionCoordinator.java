package com.justproxy.app;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * Linearizes an IP-rotation admission decision on the service worker.
 *
 * <p>The caller must supply the same single-thread executor that owns the mutable service
 * lifecycle. This avoids making an acceptance decision from a stale control-API snapshot.</p>
 */
final class IpRotationAdmissionCoordinator {
    static final String REASON_NOT_RUNNING = "not_running";
    static final String REASON_RECOVERY_REQUIRED = "recovery_required";
    static final String REASON_CELLULAR_ONLY_REQUIRED = "cellular_only_required";
    static final String REASON_SHIZUKU_NOT_READY = "shizuku_not_ready";
    static final String REASON_BUSY = "busy";
    static final String REASON_SERVICE_STOPPING = "service_stopping";
    static final String REASON_SERVICE_UNAVAILABLE = "service_unavailable";

    private final Executor worker;

    IpRotationAdmissionCoordinator(Executor worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * Runs {@code action} on the lifecycle worker and waits for its authoritative decision.
     *
     * <p>This method must be called from a control/client thread, never from the supplied
     * single-thread worker itself.</p>
     */
    Decision dispatch(Callable<Decision> action) {
        Objects.requireNonNull(action, "action");
        FutureTask<Decision> task = new FutureTask<>(action);
        try {
            worker.execute(task);
        } catch (RejectedExecutionException exception) {
            return Decision.rejected(REASON_SERVICE_STOPPING);
        } catch (RuntimeException exception) {
            return Decision.rejected(REASON_SERVICE_UNAVAILABLE);
        }

        boolean interrupted = false;
        try {
            while (true) {
                try {
                    Decision decision = task.get();
                    return decision == null
                            ? Decision.rejected(REASON_SERVICE_UNAVAILABLE)
                            : decision;
                } catch (InterruptedException exception) {
                    // The action may already be running. Wait for its truthful decision rather
                    // than returning a rejection while a mobile-data cycle starts in parallel.
                    interrupted = true;
                }
            }
        } catch (CancellationException | ExecutionException exception) {
            return Decision.rejected(REASON_SERVICE_UNAVAILABLE);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Pure admission policy. The previous display state is deliberately not a lock. */
    static Decision decide(Preconditions value) {
        Objects.requireNonNull(value, "value");
        if (value.operationInProgress) return Decision.rejected(REASON_BUSY);
        if (value.recoveryRequired) {
            return Decision.rejected(REASON_RECOVERY_REQUIRED);
        }
        if (!value.running) return Decision.rejected(REASON_NOT_RUNNING);
        if (!value.cellularOnly) {
            return Decision.rejected(REASON_CELLULAR_ONLY_REQUIRED);
        }
        if (!value.shizukuReady) {
            return Decision.rejected(REASON_SHIZUKU_NOT_READY);
        }
        return Decision.accepted();
    }

    static final class Preconditions {
        final boolean running;
        final boolean recoveryRequired;
        final boolean cellularOnly;
        final boolean shizukuReady;
        final boolean operationInProgress;
        final IpRotationStatus.State lastState;

        Preconditions(
                boolean running,
                boolean recoveryRequired,
                boolean cellularOnly,
                boolean shizukuReady,
                boolean operationInProgress,
                IpRotationStatus.State lastState) {
            this.running = running;
            this.recoveryRequired = recoveryRequired;
            this.cellularOnly = cellularOnly;
            this.shizukuReady = shizukuReady;
            this.operationInProgress = operationInProgress;
            this.lastState = Objects.requireNonNull(lastState, "lastState");
        }
    }

    static final class Decision {
        private final boolean accepted;
        private final String reason;

        private Decision(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        static Decision accepted() {
            return new Decision(true, null);
        }

        static Decision rejected(String reason) {
            if (reason == null || reason.trim().isEmpty()) {
                throw new IllegalArgumentException("A rejection reason is required");
            }
            return new Decision(false, reason);
        }

        boolean isAccepted() {
            return accepted;
        }

        String getReason() {
            return reason;
        }
    }
}
