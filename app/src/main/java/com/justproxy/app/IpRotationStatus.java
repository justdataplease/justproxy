package com.justproxy.app;

/** Immutable status for the optional Shizuku-backed airplane-mode IP rotation. */
public final class IpRotationStatus {
    public enum State {
        DISABLED,
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        BINDING,
        READY,
        TURNING_AIRPLANE_ON,
        AIRPLANE_ON,
        TURNING_AIRPLANE_OFF,
        RESTORING_LEGACY_MOBILE_DATA,
        WAITING_FOR_CELLULAR,
        CHECKING_IP,
        UNSUPPORTED,
        ERROR
    }

    public enum Outcome { NEVER, CHANGED, UNCHANGED, FAILED }

    public final boolean enabled;
    public final String provider;
    public final String mode;
    public final State state;
    public final String message;
    public final int intervalMinutes;
    public final int dataOffSeconds;
    public final int airplaneModeSeconds;
    public final long nextAtMillis;
    public final long lastAttemptAtMillis;
    public final Outcome lastOutcome;
    public final boolean recoveryRequired;
    public final boolean guaranteesIpChange;

    public IpRotationStatus(boolean enabled, State state, String message,
                            int intervalMinutes, int dataOffSeconds,
                            long nextAtMillis, long lastAttemptAtMillis,
                            Outcome lastOutcome, boolean recoveryRequired) {
        this(enabled, state, message, intervalMinutes, dataOffSeconds,
                nextAtMillis, lastAttemptAtMillis, lastOutcome, recoveryRequired,
                "airplane_mode");
    }

    public IpRotationStatus(boolean enabled, State state, String message,
                            int intervalMinutes, int dataOffSeconds,
                            long nextAtMillis, long lastAttemptAtMillis,
                            Outcome lastOutcome, boolean recoveryRequired,
                            String mode) {
        this.enabled = enabled;
        this.provider = "shizuku";
        this.mode = "mobile_data_legacy".equals(mode)
                ? "mobile_data_legacy" : "airplane_mode";
        this.state = state == null ? State.ERROR : state;
        this.message = valueOrFallback(message, "Automatic IP rotation unavailable");
        this.intervalMinutes = intervalMinutes;
        this.dataOffSeconds = dataOffSeconds;
        this.airplaneModeSeconds = dataOffSeconds;
        this.nextAtMillis = nextAtMillis;
        this.lastAttemptAtMillis = lastAttemptAtMillis;
        this.lastOutcome = lastOutcome == null ? Outcome.NEVER : lastOutcome;
        this.recoveryRequired = recoveryRequired;
        this.guaranteesIpChange = false;
    }

    public static IpRotationStatus disabled(int intervalMinutes, int dataOffSeconds) {
        return new IpRotationStatus(false, State.DISABLED,
                "Automatic IP rotation is disabled", intervalMinutes, dataOffSeconds,
                0, 0, Outcome.NEVER, false);
    }

    private static String valueOrFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
