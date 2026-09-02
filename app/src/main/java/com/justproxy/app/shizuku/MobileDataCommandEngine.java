package com.justproxy.app.shizuku;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** Serial command policy for probing, cycling, and restoring airplane mode. */
final class MobileDataCommandEngine {
    static final int MIN_DOWN_TIME_MILLIS = 1_000;
    static final int MAX_DOWN_TIME_MILLIS = 10_000;
    static final long COMMAND_TIMEOUT_MILLIS = 8_000L;
    static final long CELLULAR_LOSS_TIMEOUT_MILLIS = 15_000L;

    private static final List<String> AIRPLANE_QUERY =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode");
    private static final List<String> AIRPLANE_ENABLE =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode", "enable");
    private static final List<String> AIRPLANE_DISABLE =
            List.of("/system/bin/cmd", "connectivity", "airplane-mode", "disable");
    private static final List<String> LEGACY_CMD_DATA_ENABLE =
            List.of("/system/bin/cmd", "phone", "data", "enable");
    private static final List<String> LEGACY_SVC_DATA_ENABLE =
            List.of("/system/bin/svc", "data", "enable");

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final CommandExecutor executor;
    private final Sleeper sleeper;
    private final IntSupplier uidSupplier;
    private final CellularNetworkLossMonitor.Factory lossMonitorFactory;

    MobileDataCommandEngine(CommandExecutor executor, Sleeper sleeper, IntSupplier uidSupplier) {
        this(executor, sleeper, uidSupplier, CellularNetworkLossMonitor.unavailableFactory());
    }

    MobileDataCommandEngine(
            CommandExecutor executor,
            Sleeper sleeper,
            IntSupplier uidSupplier,
            CellularNetworkLossMonitor.Factory lossMonitorFactory) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.uidSupplier = Objects.requireNonNull(uidSupplier, "uidSupplier");
        this.lossMonitorFactory = Objects.requireNonNull(lossMonitorFactory, "lossMonitorFactory");
    }

    synchronized MobileDataCommandResult probe() {
        long startedNanos = System.nanoTime();
        CommandExecution query = safeExecute(AIRPLANE_QUERY);
        AirplaneState state = airplaneState(query);
        boolean supported = state != AirplaneState.UNKNOWN;
        return result(
                MobileDataCommandResult.OPERATION_PROBE,
                supported
                        ? MobileDataCommandResult.STATUS_OK
                        : statusForFailure(query, MobileDataCommandResult.STATUS_UNSUPPORTED),
                false,
                false,
                false,
                query.exitCode,
                CommandExecution.NO_EXIT_CODE,
                elapsedSince(startedNanos),
                supported
                        ? "Airplane-mode control is available"
                        : "Android airplane-mode control is unavailable",
                describe("cmd connectivity airplane-mode", query));
    }

    synchronized MobileDataCommandResult cycle(int downTimeMillis) {
        long startedNanos = System.nanoTime();
        if (downTimeMillis < MIN_DOWN_TIME_MILLIS
                || downTimeMillis > MAX_DOWN_TIME_MILLIS) {
            return result(
                    MobileDataCommandResult.OPERATION_CYCLE,
                    MobileDataCommandResult.STATUS_INVALID_ARGUMENT,
                    false,
                    false,
                    false,
                    CommandExecution.NO_EXIT_CODE,
                    CommandExecution.NO_EXIT_CODE,
                    elapsedSince(startedNanos),
                    "Airplane-mode time must be between 1 and 10 seconds",
                    "");
        }

        CommandExecution query = safeExecute(AIRPLANE_QUERY);
        AirplaneState initialState = airplaneState(query);
        String queryOutput = describe("cmd connectivity airplane-mode", query);
        if (initialState == AirplaneState.UNKNOWN) {
            return result(
                    MobileDataCommandResult.OPERATION_CYCLE,
                    statusForFailure(query, MobileDataCommandResult.STATUS_UNSUPPORTED),
                    false,
                    false,
                    false,
                    query.exitCode,
                    CommandExecution.NO_EXIT_CODE,
                    elapsedSince(startedNanos),
                    "Could not verify that airplane mode is off; cycle was not started",
                    queryOutput);
        }
        if (initialState == AirplaneState.ENABLED) {
            return result(
                    MobileDataCommandResult.OPERATION_CYCLE,
                    MobileDataCommandResult.STATUS_INVALID_ARGUMENT,
                    false,
                    false,
                    false,
                    query.exitCode,
                    CommandExecution.NO_EXIT_CODE,
                    elapsedSince(startedNanos),
                    "Airplane mode is already on; cycle was not started",
                    queryOutput);
        }

        final CellularNetworkLossMonitor lossMonitor;
        try {
            lossMonitor = Objects.requireNonNull(
                    lossMonitorFactory.open(), "loss monitor factory returned null");
        } catch (RuntimeException exception) {
            return result(
                    MobileDataCommandResult.OPERATION_CYCLE,
                    MobileDataCommandResult.STATUS_INTERNAL_ERROR,
                    false,
                    false,
                    false,
                    query.exitCode,
                    CommandExecution.NO_EXIT_CODE,
                    elapsedSince(startedNanos),
                    "Could not watch the cellular network; cycle was not started",
                    joinOutput(queryOutput, safeMessage(exception)));
        }

        CommandExecution enable = null;
        CommandExecution disable;
        int status = MobileDataCommandResult.STATUS_OK;
        String message = "Airplane mode was cycled and turned off";
        boolean restoreThreadInterrupt = false;
        try {
            enable = safeExecute(AIRPLANE_ENABLE);
            if (!enable.isSuccess()) {
                status = statusForFailure(
                        enable, MobileDataCommandResult.STATUS_DISABLE_FAILED);
                message = "Could not turn airplane mode on";
            } else {
                try {
                    if (!lossMonitor.awaitLoss(CELLULAR_LOSS_TIMEOUT_MILLIS)) {
                        status = MobileDataCommandResult.STATUS_TIMED_OUT;
                        message = "Cellular did not disconnect after airplane mode was turned on";
                    } else {
                        sleeper.sleep(downTimeMillis);
                    }
                } catch (InterruptedException exception) {
                    restoreThreadInterrupt = true;
                    status = MobileDataCommandResult.STATUS_INTERRUPTED;
                    message = "Airplane-mode cycle was interrupted";
                } catch (RuntimeException exception) {
                    status = MobileDataCommandResult.STATUS_INTERNAL_ERROR;
                    message = "Cellular-loss wait failed: " + safeMessage(exception);
                }
            }
        } finally {
            // Once enable was attempted, always turn airplane mode off, even on failure/interrupt.
            restoreThreadInterrupt |= Thread.interrupted();
            disable = safeExecute(AIRPLANE_DISABLE);
            try {
                lossMonitor.close();
            } catch (RuntimeException ignored) {
                // Closing a read-only monitor must never prevent the radio restore command.
            }
        }

        if (!disable.isSuccess()) {
            status = statusForFailure(
                    disable, MobileDataCommandResult.STATUS_ENABLE_FAILED);
            message = "Airplane mode could not be turned off automatically";
        }
        if (restoreThreadInterrupt) Thread.currentThread().interrupt();

        return result(
                MobileDataCommandResult.OPERATION_CYCLE,
                status,
                false,
                true,
                disable.isSuccess(),
                enable == null ? CommandExecution.NO_EXIT_CODE : enable.exitCode,
                disable.exitCode,
                elapsedSince(startedNanos),
                message,
                joinOutput(
                        queryOutput,
                        joinOutput(
                                enable == null
                                        ? "Airplane-mode enable command did not run"
                                        : describe("cmd connectivity airplane-mode enable", enable),
                                describe("cmd connectivity airplane-mode disable", disable))));
    }

    synchronized MobileDataCommandResult restore() {
        long startedNanos = System.nanoTime();
        boolean restoreThreadInterrupt = Thread.interrupted();
        CommandExecution disable = safeExecute(AIRPLANE_DISABLE);
        if (restoreThreadInterrupt) Thread.currentThread().interrupt();
        return result(
                MobileDataCommandResult.OPERATION_RESTORE,
                disable.isSuccess()
                        ? MobileDataCommandResult.STATUS_OK
                        : statusForFailure(
                                disable, MobileDataCommandResult.STATUS_ENABLE_FAILED),
                false,
                true,
                disable.isSuccess(),
                disable.exitCode,
                disable.exitCode,
                elapsedSince(startedNanos),
                disable.isSuccess()
                        ? "Airplane mode was turned off"
                        : "Airplane mode could not be turned off automatically",
                describe("cmd connectivity airplane-mode disable", disable));
    }

    /** Upgrade-only recovery for an interrupted beta.2 mobile-data cycle. */
    synchronized MobileDataCommandResult restoreLegacyMobileData() {
        long startedNanos = System.nanoTime();
        boolean restoreThreadInterrupt = Thread.interrupted();
        ActionExecution enable = executeLegacyMobileDataEnable();
        if (restoreThreadInterrupt) Thread.currentThread().interrupt();
        return result(
                MobileDataCommandResult.OPERATION_RESTORE,
                enable.isSuccess()
                        ? MobileDataCommandResult.STATUS_OK
                        : statusForFailure(
                                enable.effective, MobileDataCommandResult.STATUS_ENABLE_FAILED),
                enable.fallbackUsed,
                true,
                enable.isSuccess(),
                enable.effective.exitCode,
                enable.effective.exitCode,
                elapsedSince(startedNanos),
                enable.isSuccess()
                        ? "Legacy mobile-data recovery completed"
                        : "Mobile data could not be restored automatically",
                enable.output);
    }

    private ActionExecution executeLegacyMobileDataEnable() {
        CommandExecution primary = safeExecute(LEGACY_CMD_DATA_ENABLE);
        String output = describe("cmd phone data enable (legacy recovery)", primary);
        if (primary.isSuccess() || primary.interrupted) {
            return new ActionExecution(primary, false, output);
        }
        CommandExecution fallback = safeExecute(LEGACY_SVC_DATA_ENABLE);
        return new ActionExecution(
                fallback,
                true,
                joinOutput(output, describe("svc data enable (legacy recovery)", fallback)));
    }

    private CommandExecution safeExecute(List<String> command) {
        try {
            return executor.execute(command, COMMAND_TIMEOUT_MILLIS);
        } catch (RuntimeException exception) {
            return CommandExecution.failedToStart(
                    "Executor failed: " + safeMessage(exception), 0L);
        }
    }

    private MobileDataCommandResult result(
            int operation,
            int status,
            boolean fallbackUsed,
            boolean restoreAttempted,
            boolean restoreSucceeded,
            int commandExitCode,
            int restoreExitCode,
            long elapsedMillis,
            String message,
            String output) {
        int uid;
        try {
            uid = uidSupplier.getAsInt();
        } catch (RuntimeException exception) {
            uid = -1;
        }
        return new MobileDataCommandResult(
                operation,
                status,
                uid,
                fallbackUsed,
                restoreAttempted,
                restoreSucceeded,
                commandExitCode,
                restoreExitCode,
                elapsedMillis,
                message,
                output);
    }

    private static AirplaneState airplaneState(CommandExecution execution) {
        if (!execution.isSuccess()) return AirplaneState.UNKNOWN;
        String output = execution.output.trim().toLowerCase(Locale.ROOT);
        if ("enabled".equals(output)) return AirplaneState.ENABLED;
        if ("disabled".equals(output)) return AirplaneState.DISABLED;
        return AirplaneState.UNKNOWN;
    }

    private static int statusForFailure(CommandExecution execution, int defaultStatus) {
        if (execution.interrupted) return MobileDataCommandResult.STATUS_INTERRUPTED;
        if (execution.timedOut) return MobileDataCommandResult.STATUS_TIMED_OUT;
        return defaultStatus;
    }

    private static String describe(String label, CommandExecution execution) {
        StringBuilder description = new StringBuilder(label).append(": ");
        if (!execution.launched) {
            description.append("not started");
        } else if (execution.timedOut) {
            description.append("timed out");
        } else if (execution.interrupted) {
            description.append("interrupted");
        } else {
            description.append("exit ").append(execution.exitCode);
        }
        if (!execution.error.isEmpty()) description.append(" (").append(execution.error).append(')');
        if (!execution.output.isEmpty()) description.append('\n').append(execution.output);
        return description.toString();
    }

    private static String joinOutput(String first, String second) {
        if (first == null || first.isEmpty()) return second == null ? "" : second;
        if (second == null || second.isEmpty()) return first;
        return first + "\n" + second;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message.trim();
    }

    private static long elapsedSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private enum AirplaneState { ENABLED, DISABLED, UNKNOWN }

    private static final class ActionExecution {
        final CommandExecution effective;
        final boolean fallbackUsed;
        final String output;

        ActionExecution(CommandExecution effective, boolean fallbackUsed, String output) {
            this.effective = effective;
            this.fallbackUsed = fallbackUsed;
            this.output = output;
        }

        boolean isSuccess() {
            return effective.isSuccess();
        }
    }
}
