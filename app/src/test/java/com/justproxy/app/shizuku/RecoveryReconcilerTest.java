package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryReconcilerTest {
    @Test
    public void enabledStateClearsMarkerAndReturnsStructuredSuccess() {
        AtomicBoolean marker = new AtomicBoolean(true);
        RecoveryReconciler reconciler = new RecoveryReconciler(
                () -> MobileDataStateReader.State.ENABLED,
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
    public void disabledStateKeepsMarkerAndReturnsActionableError() {
        assertStateFailure(
                MobileDataStateReader.State.DISABLED,
                "Enable it in Android settings");
    }

    @Test
    public void unknownStateKeepsMarkerAndReturnsActionableError() {
        assertStateFailure(MobileDataStateReader.State.UNKNOWN, "Check the SIM");
    }

    @Test
    public void failedCommitReturnsActionableError() {
        RecoveryReconciler reconciler = new RecoveryReconciler(
                () -> MobileDataStateReader.State.ENABLED,
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
            MobileDataStateReader.State state, String expectedMessage) {
        AtomicBoolean clearCalled = new AtomicBoolean();
        RecoveryReconciler reconciler = new RecoveryReconciler(
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
