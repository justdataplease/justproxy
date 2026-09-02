package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class MobileDataCommandEngineTest {
    private static final List<String> QUERY =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode");
    private static final List<String> ENABLE =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode", "enable");
    private static final List<String> DISABLE =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode", "disable");

    @Test
    public void probeRecognizesExactAirplaneModeState() {
        FakeExecutor executor = new FakeExecutor(CommandExecution.completed(0, "disabled\n"));
        MobileDataCommandEngine engine = engine(executor, millis -> { }, monitor(true));

        MobileDataCommandResult result = engine.probe();

        assertTrue(result.isSuccess());
        assertFalse(result.isFallbackUsed());
        assertEquals(2000, result.getServerUid());
        assertEquals(List.of(QUERY), executor.commands);
    }

    @Test
    public void probeRejectsFailureAndUnexpectedOutput() {
        FakeExecutor failedExecutor = new FakeExecutor(
                CommandExecution.completed(1, "disabled"));
        FakeExecutor unexpectedExecutor = new FakeExecutor(
                CommandExecution.completed(0, "airplane mode: disabled"));

        assertEquals(
                MobileDataCommandResult.STATUS_UNSUPPORTED,
                engine(failedExecutor, millis -> { }, monitor(true)).probe().getStatus());
        assertEquals(
                MobileDataCommandResult.STATUS_UNSUPPORTED,
                engine(unexpectedExecutor, millis -> { }, monitor(true)).probe().getStatus());
    }

    @Test
    public void cycleUsesOnlyFixedAirplaneVectorsWaitsForLossAndRestores() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"),
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        List<Long> sleeps = new ArrayList<>();
        FakeLossMonitor monitor = monitor(true);
        MobileDataCommandEngine engine = engine(executor, sleeps::add, monitor);

        MobileDataCommandResult result = engine.cycle(1_000);

        assertTrue(result.isSuccess());
        assertTrue(result.isRestoreAttempted());
        assertTrue(result.isRestoreSucceeded());
        assertEquals(List.of(1_000L), sleeps);
        assertEquals(List.of(MobileDataCommandEngine.CELLULAR_LOSS_TIMEOUT_MILLIS),
                monitor.timeouts);
        assertTrue(monitor.closed);
        assertEquals(List.of(QUERY, ENABLE, DISABLE), executor.commands);
    }

    @Test
    public void existingAirplaneModeIsNeverChanged() {
        FakeExecutor executor = new FakeExecutor(CommandExecution.completed(0, "enabled"));
        FakeLossMonitor monitor = monitor(true);

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor).cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_INVALID_ARGUMENT, result.getStatus());
        assertFalse(result.isRestoreAttempted());
        assertEquals(List.of(QUERY), executor.commands);
        assertFalse(monitor.opened);
    }

    @Test
    public void unverifiedInitialStateRunsNoMutation() {
        FakeExecutor executor = new FakeExecutor(CommandExecution.completed(0, "unknown"));

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor(true)).cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_UNSUPPORTED, result.getStatus());
        assertFalse(result.isRestoreAttempted());
        assertEquals(List.of(QUERY), executor.commands);
    }

    @Test
    public void unavailableCellularObserverFailsBeforeAirplaneMutation() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"));
        MobileDataCommandEngine engine = new MobileDataCommandEngine(
                executor,
                millis -> { },
                () -> 2000,
                CellularNetworkLossMonitor.unavailableFactory());

        MobileDataCommandResult result = engine.cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_INTERNAL_ERROR, result.getStatus());
        assertFalse(result.isRestoreAttempted());
        assertEquals(List.of(QUERY), executor.commands);
    }

    @Test
    public void failedEnableStillAttemptsAirplaneDisable() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"),
                CommandExecution.completed(1, "enable failed"),
                CommandExecution.completed(0, ""));

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor(true)).cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_DISABLE_FAILED, result.getStatus());
        assertTrue(result.isRestoreAttempted());
        assertTrue(result.isRestoreSucceeded());
        assertEquals(List.of(QUERY, ENABLE, DISABLE), executor.commands);
    }

    @Test
    public void cellularLossTimeoutStillTurnsAirplaneModeOff() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"),
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        List<Long> sleeps = new ArrayList<>();

        MobileDataCommandResult result =
                engine(executor, sleeps::add, monitor(false)).cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_TIMED_OUT, result.getStatus());
        assertTrue(result.isRestoreSucceeded());
        assertTrue(sleeps.isEmpty());
        assertEquals(List.of(QUERY, ENABLE, DISABLE), executor.commands);
    }

    @Test
    public void interruptedHoldRestoresBeforeInterruptIsReasserted() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"),
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        MobileDataCommandEngine engine = engine(executor, millis -> {
            throw new InterruptedException("test");
        }, monitor(true));

        try {
            MobileDataCommandResult result = engine.cycle(1_000);

            assertEquals(MobileDataCommandResult.STATUS_INTERRUPTED, result.getStatus());
            assertTrue(result.isRestoreAttempted());
            assertTrue(result.isRestoreSucceeded());
            assertEquals(List.of(QUERY, ENABLE, DISABLE), executor.commands);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void disableFailureIsReportedAsUnrestored() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "disabled"),
                CommandExecution.completed(0, ""),
                CommandExecution.completed(1, "disable failed"));

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor(true)).cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_ENABLE_FAILED, result.getStatus());
        assertTrue(result.isRestoreAttempted());
        assertFalse(result.isRestoreSucceeded());
    }

    @Test
    public void invalidDurationRunsNoCommands() {
        FakeExecutor executor = new FakeExecutor();

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor(true)).cycle(999);

        assertEquals(MobileDataCommandResult.STATUS_INVALID_ARGUMENT, result.getStatus());
        assertFalse(result.isRestoreAttempted());
        assertTrue(executor.commands.isEmpty());
    }

    @Test
    public void legacyRecoveryCanOnlyEnableDataAndUsesFallback() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(1, "cmd unavailable"),
                CommandExecution.completed(0, ""));

        MobileDataCommandResult result =
                engine(executor, millis -> { }, monitor(true)).restoreLegacyMobileData();

        assertTrue(result.isSuccess());
        assertTrue(result.isFallbackUsed());
        assertEquals(
                List.of(
                        List.of("/system/bin/cmd", "phone", "data", "enable"),
                        List.of("/system/bin/svc", "data", "enable")),
                executor.commands);
    }

    @Test
    public void processExecutorAllowsOnlyAirplaneAndLegacyEnableVectors() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor();

        CommandExecution query = executor.execute(QUERY, 1_000L);
        CommandExecution enable = executor.execute(ENABLE, 1_000L);
        CommandExecution disable = executor.execute(DISABLE, 1_000L);
        CommandExecution legacyEnable = executor.execute(
                List.of("/system/bin/cmd", "phone", "data", "enable"), 1_000L);
        CommandExecution legacyDisable = executor.execute(
                List.of("/system/bin/cmd", "phone", "data", "disable"), 1_000L);
        CommandExecution shell = executor.execute(
                List.of("/system/bin/sh", "-c", "cmd connectivity airplane-mode enable"),
                1_000L);

        assertFalse(query.error.contains("allowlist"));
        assertFalse(enable.error.contains("allowlist"));
        assertFalse(disable.error.contains("allowlist"));
        assertFalse(legacyEnable.error.contains("allowlist"));
        assertTrue(legacyDisable.error.contains("allowlist"));
        assertTrue(shell.error.contains("allowlist"));
    }

    private static MobileDataCommandEngine engine(
            CommandExecutor executor,
            MobileDataCommandEngine.Sleeper sleeper,
            FakeLossMonitor monitor) {
        return new MobileDataCommandEngine(executor, sleeper, () -> 2000, () -> {
            monitor.opened = true;
            return monitor;
        });
    }

    private static FakeLossMonitor monitor(boolean lossObserved) {
        return new FakeLossMonitor(lossObserved);
    }

    private static final class FakeLossMonitor implements CellularNetworkLossMonitor {
        final boolean lossObserved;
        final List<Long> timeouts = new ArrayList<>();
        boolean opened;
        boolean closed;

        FakeLossMonitor(boolean lossObserved) {
            this.lossObserved = lossObserved;
        }

        @Override public boolean awaitLoss(long timeoutMillis) {
            timeouts.add(timeoutMillis);
            return lossObserved;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static final class FakeExecutor implements CommandExecutor {
        final List<List<String>> commands = new ArrayList<>();
        private final Deque<CommandExecution> results = new ArrayDeque<>();

        FakeExecutor(CommandExecution... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public CommandExecution execute(List<String> command, long timeoutMillis) {
            commands.add(List.copyOf(command));
            assertEquals(MobileDataCommandEngine.COMMAND_TIMEOUT_MILLIS, timeoutMillis);
            if (results.isEmpty()) throw new AssertionError("No scripted command result");
            return results.removeFirst();
        }
    }
}
