package com.justproxy.app.proxy;

/** Immutable event emitted when active sessions are rotated. */
public final class ProxyRotationEvent {
    private final long timestampEpochMillis;
    private final RotationReason reason;
    private final int sessionsTargeted;

    ProxyRotationEvent(long timestampEpochMillis, RotationReason reason, int sessionsTargeted) {
        this.timestampEpochMillis = timestampEpochMillis;
        this.reason = reason;
        this.sessionsTargeted = sessionsTargeted;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }

    public RotationReason getReason() {
        return reason;
    }

    public int getSessionsTargeted() {
        return sessionsTargeted;
    }
}
