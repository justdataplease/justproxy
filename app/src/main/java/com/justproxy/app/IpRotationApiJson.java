package com.justproxy.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Tested JSON rendering for the public IP-rotation API contract. */
final class IpRotationApiJson {
    private IpRotationApiJson() { }

    static JSONObject statusObject(IpRotationStatus status) throws JSONException {
        return new JSONObject()
                .put("enabled", status.enabled)
                .put("provider", status.provider)
                .put("mode", status.mode)
                .put("state", status.state.name().toLowerCase(Locale.ROOT))
                .put("message", status.message)
                .put("interval_minutes", status.intervalMinutes)
                .put("airplane_mode_seconds", status.airplaneModeSeconds)
                // Compatibility alias retained for beta.2 clients.
                .put("data_off_seconds", status.dataOffSeconds)
                .put("next_at_ms", nullableTime(status.nextAtMillis))
                .put("last_attempt_at_ms", nullableTime(status.lastAttemptAtMillis))
                .put("last_outcome", status.lastOutcome.name().toLowerCase(Locale.ROOT))
                .put("recovery_required", status.recoveryRequired)
                .put("guarantees_ip_change", status.guaranteesIpChange);
    }

    static String actionJson(
            boolean accepted,
            String previousIp,
            boolean recoveryRequired,
            String reason,
            int airplaneModeSeconds,
            String rejectionMessage) {
        try {
            return new JSONObject()
                    .put("accepted", accepted)
                    .put("action", accepted
                            ? "airplane_mode_cycle_scheduled" : "none")
                    .put("previous_ip", nullableString(previousIp))
                    .put("ip_changed", JSONObject.NULL)
                    .put("manual_carrier_reset_required",
                            !accepted && (recoveryRequired
                                    || IpRotationAdmissionCoordinator
                                    .REASON_RECOVERY_REQUIRED.equals(reason)))
                    .put("reason", accepted ? JSONObject.NULL : reason)
                    .put("mode", "airplane_mode")
                    .put("airplane_mode_seconds", airplaneModeSeconds)
                    // Compatibility alias retained for beta.2 clients.
                    .put("data_off_seconds", airplaneModeSeconds)
                    .put("guarantees_ip_change", false)
                    .put("message", accepted
                            ? "Airplane mode will cycle and the public IP will be checked"
                            : rejectionMessage)
                    .toString();
        } catch (JSONException exception) {
            return new JSONObject().toString();
        }
    }

    private static Object nullableTime(long value) {
        return value == 0L ? JSONObject.NULL : value;
    }

    private static Object nullableString(String value) {
        return value == null || value.isEmpty() || "-".equals(value)
                ? JSONObject.NULL : value;
    }
}
