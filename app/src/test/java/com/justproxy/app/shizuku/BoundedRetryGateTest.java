package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BoundedRetryGateTest {
    @Test
    public void preventsDuplicatesAndStopsAtBound() {
        BoundedRetryGate gate = new BoundedRetryGate(3);

        assertEquals(1, gate.reserveNextAttempt());
        assertEquals(BoundedRetryGate.ALREADY_SCHEDULED, gate.reserveNextAttempt());
        gate.markScheduledRunStarted();
        assertEquals(2, gate.reserveNextAttempt());
        gate.markScheduledRunStarted();
        assertEquals(3, gate.reserveNextAttempt());
        gate.markScheduledRunStarted();
        assertEquals(BoundedRetryGate.EXHAUSTED, gate.reserveNextAttempt());
    }

    @Test
    public void resetAllowsFreshRetryBudget() {
        BoundedRetryGate gate = new BoundedRetryGate(1);
        assertEquals(1, gate.reserveNextAttempt());

        gate.reset();

        assertEquals(1, gate.reserveNextAttempt());
    }
}
