package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RecoveryModePolicyTest {
    @Test
    public void markerWithoutModeUsesBeta2EnableOnlyRecovery() {
        assertEquals(
                RecoveryModePolicy.Mode.LEGACY_MOBILE_DATA,
                RecoveryModePolicy.fromStored(true, ""));
    }

    @Test
    public void explicitAirplaneMarkerUsesDisableOnlyRecovery() {
        assertEquals(
                RecoveryModePolicy.Mode.AIRPLANE_MODE,
                RecoveryModePolicy.fromStored(
                        true, RecoveryModePolicy.AIRPLANE_MODE_VALUE));
    }

    @Test
    public void absentMarkerDefaultsToCurrentAirplaneMode() {
        assertEquals(
                RecoveryModePolicy.Mode.AIRPLANE_MODE,
                RecoveryModePolicy.fromStored(false, ""));
    }

    @Test
    public void unknownPersistedModeFailsSafeAsLegacy() {
        assertEquals(
                RecoveryModePolicy.Mode.LEGACY_MOBILE_DATA,
                RecoveryModePolicy.fromStored(true, "future_mode"));
    }
}
