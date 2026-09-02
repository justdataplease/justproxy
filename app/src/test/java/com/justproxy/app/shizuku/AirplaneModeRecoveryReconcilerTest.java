package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AirplaneModeRecoveryReconcilerTest {
    @Test
    public void disabledStateClearsMarkerAndReturnsStructuredSuccess() {
        AtomicBoolean marker = new AtomicBoolean(true);
        AirplaneModeRecoveryReconciler reconciler = new AirplaneModeRecoveryReconciler(
                () -> AirplaneModeStateReader.State.DISABLED,
                () -> {
                    marker.set(false);
                    return true;
                },
                () -> 0L);

        MobileDataCommandResult result = reconciler.reconcile();

        assertFalse(marker.get());
        assertTrue(result.isSuccess());
        assertTrue(result.isRestoreSucceeded());
        assertEquals(
                MobileDataCommandResult.OPERATION_RECONCILE_RECOVERY,
                result.getOperation());
    }

    @Test
    public void enabledStateKeepsMarkerAndReturnsActionableError() {
        assertStateFailure(
                AirplaneModeStateReader.State.ENABLED,
                "Turn it off in Android settings");
    }

    @Test
    public void unknownStateKeepsMarkerAndReturnsActionableError() {
        assertStateFailure(
                AirplaneModeStateReader.State.UNKNOWN,
                "could not read airplane-mode state");
    }

    @Test
    public void failedCommitReturnsActionableError() {
        AirplaneModeRecoveryReconciler reconciler = new AirplaneModeRecoveryReconciler(
                () -> AirplaneModeStateReader.State.DISABLED,
                () -> false,
                () -> 0L);

        try {
            reconciler.reconcile();
            fail("expected marker commit failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("could not clear"));
        }
    }

    private static void assertStateFailure(
            AirplaneModeStateReader.State state, String expectedMessage) {
        AtomicBoolean clearCalled = new AtomicBoolean();
        AirplaneModeRecoveryReconciler reconciler = new AirplaneModeRecoveryReconciler(
                () -> state,
                () -> {
                    clearCalled.set(true);
                    return true;
                },
                () -> 0L);

        try {
            reconciler.reconcile();
            fail("expected reconciliation failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(expectedMessage));
        }
        assertFalse(clearCalled.get());
    }
}
