package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;

public final class MobileDataStatePollerTest {
    @Test
    public void keepsPollingUnknownUntilEnabled() {
        FakeReader reader = new FakeReader(
                MobileDataStateReader.State.UNKNOWN,
                MobileDataStateReader.State.UNKNOWN,
                MobileDataStateReader.State.ENABLED);
        FakeTime time = new FakeTime();
        MobileDataStatePoller poller = new MobileDataStatePoller(reader, time, time);

        MobileDataStateReader.State result = poller.awaitEnabled(1_000L, 250L);

        assertEquals(MobileDataStateReader.State.ENABLED, result);
        assertEquals(3, reader.reads);
        assertEquals(500L, time.elapsedMillis);
    }

    @Test
    public void unknownStateStopsOnlyAtBoundedDeadline() {
        FakeReader reader = new FakeReader(MobileDataStateReader.State.UNKNOWN);
        FakeTime time = new FakeTime();
        MobileDataStatePoller poller = new MobileDataStatePoller(reader, time, time);

        MobileDataStateReader.State result = poller.awaitEnabled(500L, 250L);

        assertEquals(MobileDataStateReader.State.UNKNOWN, result);
        assertEquals(3, reader.reads);
        assertEquals(500L, time.elapsedMillis);
    }

    @Test
    public void interruptionReturnsUnknownAndPreservesInterrupt() {
        MobileDataStatePoller poller = new MobileDataStatePoller(
                () -> MobileDataStateReader.State.DISABLED,
                System::nanoTime,
                millis -> { throw new InterruptedException("test"); });

        try {
            assertEquals(
                    MobileDataStateReader.State.UNKNOWN,
                    poller.awaitEnabled(1_000L, 250L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class FakeReader implements MobileDataStateReader {
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
            implements MobileDataStatePoller.Clock, MobileDataStatePoller.Sleeper {
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
