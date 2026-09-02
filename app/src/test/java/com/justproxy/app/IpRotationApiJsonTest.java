package com.justproxy.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class IpRotationApiJsonTest {
    @Test
    public void statusRendersAirplaneModeAndCompatibilityAlias() throws Exception {
        IpRotationStatus status = new IpRotationStatus(
                true,
                IpRotationStatus.State.TURNING_AIRPLANE_ON,
                "Turning airplane mode on",
                10,
                1,
                123L,
                100L,
                IpRotationStatus.Outcome.UNCHANGED,
                true,
                "airplane_mode");

        JSONObject json = IpRotationApiJson.statusObject(status);

        assertEquals("airplane_mode", json.getString("mode"));
        assertEquals("turning_airplane_on", json.getString("state"));
        assertEquals(1, json.getInt("airplane_mode_seconds"));
        assertEquals(1, json.getInt("data_off_seconds"));
        assertTrue(json.getBoolean("recovery_required"));
        assertFalse(json.getBoolean("guarantees_ip_change"));
    }

    @Test
    public void statusReportsLegacyRecoveryModeTruthfully() throws Exception {
        IpRotationStatus status = new IpRotationStatus(
                true,
                IpRotationStatus.State.RESTORING_LEGACY_MOBILE_DATA,
                "Restoring legacy mobile data",
                10,
                1,
                0L,
                100L,
                IpRotationStatus.Outcome.FAILED,
                true,
                "mobile_data_legacy");

        JSONObject json = IpRotationApiJson.statusObject(status);

        assertEquals("mobile_data_legacy", json.getString("mode"));
        assertEquals("restoring_legacy_mobile_data", json.getString("state"));
        assertTrue(json.isNull("next_at_ms"));
    }

    @Test
    public void acceptedActionUsesTypedAirplaneContract() throws Exception {
        JSONObject json = new JSONObject(IpRotationApiJson.actionJson(
                true, "198.51.100.10", false, null, 1, "unused"));

        assertTrue(json.getBoolean("accepted"));
        assertEquals("airplane_mode_cycle_scheduled", json.getString("action"));
        assertEquals("airplane_mode", json.getString("mode"));
        assertEquals(1, json.getInt("airplane_mode_seconds"));
        assertEquals(1, json.getInt("data_off_seconds"));
        assertEquals("Airplane mode will cycle and the public IP will be checked",
                json.getString("message"));
    }

    @Test
    public void recoveryRejectionRequiresManualCarrierReset() throws Exception {
        JSONObject json = new JSONObject(IpRotationApiJson.actionJson(
                false,
                null,
                true,
                IpRotationAdmissionCoordinator.REASON_RECOVERY_REQUIRED,
                1,
                "Airplane-mode recovery must finish first"));

        assertFalse(json.getBoolean("accepted"));
        assertEquals("none", json.getString("action"));
        assertTrue(json.getBoolean("manual_carrier_reset_required"));
        assertEquals(IpRotationAdmissionCoordinator.REASON_RECOVERY_REQUIRED,
                json.getString("reason"));
    }
}
