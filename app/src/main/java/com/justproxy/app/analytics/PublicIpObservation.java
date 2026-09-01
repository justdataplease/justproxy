package com.justproxy.app.analytics;

/** A timestamped public-IP check. */
public final class PublicIpObservation {
    private final long id;
    private final long observedAtMillis;
    private final String ipAddress;
    private final boolean changedFromPrevious;

    PublicIpObservation(
            long id, long observedAtMillis, String ipAddress, boolean changedFromPrevious) {
        this.id = id;
        this.observedAtMillis = observedAtMillis;
        this.ipAddress = ipAddress;
        this.changedFromPrevious = changedFromPrevious;
    }

    public long getId() {
        return id;
    }

    public long getObservedAtMillis() {
        return observedAtMillis;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isChangedFromPrevious() {
        return changedFromPrevious;
    }
}
