package com.justproxy.app.shizuku;

/** Pure state gate that bounds reconnect attempts and prevents duplicate scheduled retries. */
final class BoundedRetryGate {
    static final int ALREADY_SCHEDULED = -1;
    static final int EXHAUSTED = 0;

    private final int maxAttempts;
    private int attempts;
    private boolean scheduled;

    BoundedRetryGate(int maxAttempts) {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    synchronized int reserveNextAttempt() {
        if (scheduled) return ALREADY_SCHEDULED;
        if (attempts >= maxAttempts) return EXHAUSTED;
        scheduled = true;
        return ++attempts;
    }

    synchronized void markScheduledRunStarted() {
        scheduled = false;
    }

    synchronized void reset() {
        attempts = 0;
        scheduled = false;
    }
}
