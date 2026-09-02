package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;

public final class AirplaneModeStatePollerTest {
    @Test
    public void keepsPollingUnknownAndEnabledUntilDisabled() {
        FakeReader reader = new FakeReader(
                AirplaneModeStateReader.State.UNKNOWN,
                AirplaneModeStateReader.State.ENABLED,
                AirplaneModeStateReader.State.DISABLED);
        FakeTime time = new FakeTime();
        AirplaneModeStatePoller poller = new AirplaneModeStatePoller(reader, time, time);

        AirplaneModeStateReader.State result = poller.awaitDisabled(1_000L, 250L);

        assertEquals(AirplaneModeStateReader.State.DISABLED, result);
        assertEquals(3, reader.reads);
        assertEquals(500L, time.elapsedMillis);
    }

    @Test
    public void enabledStateStopsOnlyAtBoundedDeadline() {
        FakeReader reader = new FakeReader(AirplaneModeStateReader.State.ENABLED);
        FakeTime time = new FakeTime();
        AirplaneModeStatePoller poller = new AirplaneModeStatePoller(reader, time, time);

        AirplaneModeStateReader.State result = poller.awaitDisabled(500L, 250L);

        assertEquals(AirplaneModeStateReader.State.ENABLED, result);
        assertEquals(3, reader.reads);
        assertEquals(500L, time.elapsedMillis);
    }

    @Test
    public void interruptionReturnsUnknownAndPreservesInterrupt() {
        AirplaneModeStatePoller poller = new AirplaneModeStatePoller(
                () -> AirplaneModeStateReader.State.ENABLED,
                System::nanoTime,
                millis -> { throw new InterruptedException("test"); });

        try {
            assertEquals(
                    AirplaneModeStateReader.State.UNKNOWN,
                    poller.awaitDisabled(1_000L, 250L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositivePollInterval() {
        AirplaneModeStatePoller poller = new AirplaneModeStatePoller(
                () -> AirplaneModeStateReader.State.DISABLED,
                System::nanoTime,
                millis -> { });

        poller.awaitDisabled(1_000L, 0L);
    }

    private static final class FakeReader implements AirplaneModeStateReader {
        private final Deque<State> states = new ArrayDeque<>();
        private State last;
        int reads;

        FakeReader(State... states) {
            for (State state : states) this.states.addLast(state);
            last = states[states.length - 1];
        }

        @Override
        public State read() {
            reads++;
            if (!states.isEmpty()) last = states.removeFirst();
            return last;
        }
    }

    private static final class FakeTime
            implements AirplaneModeStatePoller.Clock, AirplaneModeStatePoller.Sleeper {
        long elapsedMillis;

        @Override
        public long nanoTime() {
            return elapsedMillis * 1_000_000L;
        }

        @Override
        public void sleep(long millis) {
            elapsedMillis += millis;
        }
    }
}
