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
    @Test
    public void probeRecognizesCmdPhoneHelpDespiteNonZeroExit() {
        FakeExecutor executor = new FakeExecutor(CommandExecution.completed(
                255, "data enable: enable mobile data\ndata disable: disable mobile data"));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.probe();

        assertTrue(result.isSuccess());
        assertFalse(result.isFallbackUsed());
        assertEquals(2000, result.getServerUid());
        assertEquals(
                List.of(List.of("/system/bin/cmd", "phone", "data", "help")),
                executor.commands);
    }

    @Test
    public void probeUsesSvcFallbackWhenCmdPhoneIsUnavailable() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(1, "unknown command"),
                CommandExecution.completed(1, "usage: svc data [enable|disable]"));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.probe();

        assertTrue(result.isSuccess());
        assertTrue(result.isFallbackUsed());
        assertEquals(
                List.of(
                        List.of("/system/bin/cmd", "phone", "data", "help"),
                        List.of("/system/bin/svc", "data")),
                executor.commands);
    }

    @Test
    public void probeRejectsMarkerlessOutputEvenWhenCommandsExitSuccessfully() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, "Telephony help is unavailable"),
                CommandExecution.completed(0, "Available commands include data statistics"));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.probe();

        assertFalse(result.isSuccess());
        assertEquals(MobileDataCommandResult.STATUS_UNSUPPORTED, result.getStatus());
    }

    @Test
    public void probeRejectsTimedOutCmdHelpBeforeUsingSvcFallback() {
        FakeExecutor executor = new FakeExecutor(
                new CommandExecution(
                        true,
                        true,
                        false,
                        CommandExecution.NO_EXIT_CODE,
                        8_000L,
                        "data enable data disable",
                        ""),
                CommandExecution.completed(1, "usage: svc data [enable|disable]"));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.probe();

        assertTrue(result.isSuccess());
        assertTrue(result.isFallbackUsed());
    }

    @Test
    public void cycleUsesOnlyFixedArgumentVectorsAndRestores() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        List<Long> sleeps = new ArrayList<>();
        MobileDataCommandEngine engine = engine(executor, sleeps::add);

        MobileDataCommandResult result = engine.cycle(1_000);

        assertTrue(result.isSuccess());
        assertTrue(result.isRestoreAttempted());
        assertTrue(result.isRestoreSucceeded());
        assertEquals(List.of(1_000L), sleeps);
        assertEquals(
                List.of(
                        List.of("/system/bin/cmd", "phone", "data", "disable"),
                        List.of("/system/bin/cmd", "phone", "data", "enable")),
                executor.commands);
    }

    @Test
    public void interruptedDelayStillAttemptsEnableBeforeRestoringInterrupt() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        MobileDataCommandEngine engine = engine(executor, millis -> {
            throw new InterruptedException("test");
        });

        try {
            MobileDataCommandResult result = engine.cycle(1_000);

            assertEquals(MobileDataCommandResult.STATUS_INTERRUPTED, result.getStatus());
            assertTrue(result.isRestoreAttempted());
            assertTrue(result.isRestoreSucceeded());
            assertEquals(
                    List.of(
                            List.of("/system/bin/cmd", "phone", "data", "disable"),
                            List.of("/system/bin/cmd", "phone", "data", "enable")),
                    executor.commands);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void failedCmdActionFallsBackToSvcAndStillRestores() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(1, "cmd unavailable"),
                CommandExecution.completed(0, ""),
                CommandExecution.completed(0, ""));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.cycle(1_000);

        assertTrue(result.isSuccess());
        assertTrue(result.isFallbackUsed());
        assertEquals(
                List.of(
                        List.of("/system/bin/cmd", "phone", "data", "disable"),
                        List.of("/system/bin/svc", "data", "disable"),
                        List.of("/system/bin/cmd", "phone", "data", "enable")),
                executor.commands);
    }

    @Test
    public void invalidDurationRunsNoCommands() {
        FakeExecutor executor = new FakeExecutor();
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.cycle(999);

        assertEquals(MobileDataCommandResult.STATUS_INVALID_ARGUMENT, result.getStatus());
        assertFalse(result.isRestoreAttempted());
        assertTrue(executor.commands.isEmpty());
    }

    @Test
    public void enableFailureIsReportedAsUnrestored() {
        FakeExecutor executor = new FakeExecutor(
                CommandExecution.completed(0, ""),
                CommandExecution.completed(1, "cmd enable failed"),
                CommandExecution.completed(1, "svc enable failed"));
        MobileDataCommandEngine engine = engine(executor, millis -> { });

        MobileDataCommandResult result = engine.cycle(1_000);

        assertEquals(MobileDataCommandResult.STATUS_ENABLE_FAILED, result.getStatus());
        assertTrue(result.isRestoreAttempted());
        assertFalse(result.isRestoreSucceeded());
        assertTrue(result.isFallbackUsed());
    }

    @Test
    public void processExecutorRejectsAnythingOutsideAllowlistWithoutLaunching() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor();

        CommandExecution result = executor.execute(
                List.of("/system/bin/sh", "-c", "cmd phone data disable"), 1_000L);

        assertFalse(result.launched);
        assertTrue(result.error.contains("allowlist"));
    }

    @Test
    public void processExecutorAllowsOnlyFixedNonMutatingProbeVectors() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor();

        CommandExecution cmdProbe = executor.execute(
                List.of("/system/bin/cmd", "phone", "data", "help"), 1_000L);
        CommandExecution svcProbe = executor.execute(
                List.of("/system/bin/svc", "data"), 1_000L);
        CommandExecution extraArgument = executor.execute(
                List.of("/system/bin/svc", "data", "help"), 1_000L);

        assertFalse(cmdProbe.error.contains("allowlist"));
        assertFalse(svcProbe.error.contains("allowlist"));
        assertTrue(extraArgument.error.contains("allowlist"));
    }

    private static MobileDataCommandEngine engine(
            CommandExecutor executor, MobileDataCommandEngine.Sleeper sleeper) {
        return new MobileDataCommandEngine(executor, sleeper, () -> 2000);
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
