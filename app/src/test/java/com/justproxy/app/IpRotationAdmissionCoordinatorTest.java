                                                                                                            package com.justproxy.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class IpRotationAdmissionCoordinatorTest {
    @Test
    public void concurrentRequestsStartExactlyOneOperation() throws Exception {
        final int callers = 16;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService clients = Executors.newFixedThreadPool(callers);
        try {
            IpRotationAdmissionCoordinator coordinator =
                    new IpRotationAdmissionCoordinator(worker);
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean operationInProgress = new AtomicBoolean();
            AtomicInteger starts = new AtomicInteger();
            Callable<IpRotationAdmissionCoordinator.Decision> admission = () -> {
                IpRotationAdmissionCoordinator.Decision decision =
                        IpRotationAdmissionCoordinator.decide(preconditions(
                                true, false, true, true,
                                operationInProgress.get(), IpRotationStatus.State.ERROR));
                if (decision.isAccepted()) {
                    operationInProgress.set(true);
                    starts.incrementAndGet();
                }
                return decision;
            };

            List<Future<IpRotationAdmissionCoordinator.Decision>> results =
                    new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(clients.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return coordinator.dispatch(admission);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int accepted = 0;
            int busy = 0;
            for (Future<IpRotationAdmissionCoordinator.Decision> result : results) {
                IpRotationAdmissionCoordinator.Decision decision =
                        result.get(5, TimeUnit.SECONDS);
                if (decision.isAccepted()) {
                    accepted++;
                    assertNull(decision.getReason());
                } else if (IpRotationAdmissionCoordinator.REASON_BUSY.equals(
                        decision.getReason())) {
                    busy++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(callers - 1, busy);
            assertEquals(1, starts.get());
        } finally {
            clients.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    public void rejectedDispatchReturnsServiceStoppingWithoutRunningAction() {
        AtomicInteger invocations = new AtomicInteger();
        IpRotationAdmissionCoordinator coordinator =
                new IpRotationAdmissionCoordinator(command -> {
                    throw new RejectedExecutionException("stopping");
                });

        IpRotationAdmissionCoordinator.Decision decision = coordinator.dispatch(() -> {
            invocations.incrementAndGet();
            return IpRotationAdmissionCoordinator.Decision.accepted();
        });

        assertFalse(decision.isAccepted());
        assertEquals(IpRotationAdmissionCoordinator.REASON_SERVICE_STOPPING,
                decision.getReason());
        assertEquals(0, invocations.get());
    }

    @Test
    public void interruptedDispatchWaitsForDecisionAndPreservesInterrupt() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<IpRotationAdmissionCoordinator.Decision> result =
                new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        IpRotationAdmissionCoordinator coordinator =
                new IpRotationAdmissionCoordinator(command -> {
                    queued.set(command);
                    submitted.countDown();
                });

        Thread caller = new Thread(() -> {
            result.set(coordinator.dispatch(() -> {
                invocations.incrementAndGet();
                return IpRotationAdmissionCoordinator.Decision.accepted();
            }));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        assertTrue(caller.isAlive());
        queued.get().run();
        caller.join(5_000L);

        assertFalse(caller.isAlive());
        assertTrue(result.get().isAccepted());
        assertNull(result.get().getReason());
        assertTrue(interruptPreserved.get());
        assertEquals(1, invocations.get());
    }

    @Test
    public void actionFailureReturnsServiceUnavailable() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            IpRotationAdmissionCoordinator coordinator =
                    new IpRotationAdmissionCoordinator(worker);
            IpRotationAdmissionCoordinator.Decision decision = coordinator.dispatch(() -> {
                throw new IllegalStateException("boom");
            });

            assertFalse(decision.isAccepted());
            assertEquals(IpRotationAdmissionCoordinator.REASON_SERVICE_UNAVAILABLE,
                    decision.getReason());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void errorDisplayStateIsRetryableWhenOperationIsIdle() {
        IpRotationAdmissionCoordinator.Decision decision =
                IpRotationAdmissionCoordinator.decide(preconditions(
                        true, false, true, true, false,
                        IpRotationStatus.State.ERROR));

        assertTrue(decision.isAccepted());
        assertNull(decision.getReason());
    }

    @Test
    public void activeOperationAndRecoveryRemainFailClosed() {
        IpRotationAdmissionCoordinator.Decision active =
                IpRotationAdmissionCoordinator.decide(preconditions(
                        true, false, true, true, true,
                        IpRotationStatus.State.ERROR));
        IpRotationAdmissionCoordinator.Decision recovery =
                IpRotationAdmissionCoordinator.decide(preconditions(
                        true, true, true, true, false,
                        IpRotationStatus.State.READY));

        assertEquals(IpRotationAdmissionCoordinator.REASON_BUSY, active.getReason());
        assertEquals(IpRotationAdmissionCoordinator.REASON_RECOVERY_REQUIRED,
                recovery.getReason());
    }

    @Test
    public void preconditionRejectionsHaveStablePrecedence() {
        assertReason(IpRotationAdmissionCoordinator.REASON_BUSY,
                preconditions(false, true, false, false, true,
                        IpRotationStatus.State.ERROR));
        assertReason(IpRotationAdmissionCoordinator.REASON_RECOVERY_REQUIRED,
                preconditions(false, true, false, false, false,
                        IpRotationStatus.State.ERROR));
        assertReason(IpRotationAdmissionCoordinator.REASON_NOT_RUNNING,
                preconditions(false, false, false, false, false,
                        IpRotationStatus.State.ERROR));
        assertReason(IpRotationAdmissionCoordinator.REASON_CELLULAR_ONLY_REQUIRED,
                preconditions(true, false, false, false, false,
                        IpRotationStatus.State.ERROR));
        assertReason(IpRotationAdmissionCoordinator.REASON_SHIZUKU_NOT_READY,
                preconditions(true, false, true, false, false,
                        IpRotationStatus.State.ERROR));
    }

    private static IpRotationAdmissionCoordinator.Preconditions preconditions(
            boolean running,
            boolean recoveryRequired,
            boolean cellularOnly,
            boolean shizukuReady,
            boolean operationInProgress,
            IpRotationStatus.State lastState) {
        return new IpRotationAdmissionCoordinator.Preconditions(
                running,
                recoveryRequired,
                cellularOnly,
                shizukuReady,
                operationInProgress,
                lastState);
    }

    private static void assertReason(
            String expected,
            IpRotationAdmissionCoordinator.Preconditions preconditions) {
        IpRotationAdmissionCoordinator.Decision decision =
                IpRotationAdmissionCoordinator.decide(preconditions);
        assertFalse(decision.isAccepted());
        assertEquals(expected, decision.getReason());
    }
}
