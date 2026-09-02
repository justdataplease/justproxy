package com.justproxy.app.shizuku;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** Serial command policy for probing, cycling, and restoring mobile data. */
final class MobileDataCommandEngine {
    static final int MIN_DOWN_TIME_MILLIS = 1_000;
    static final int MAX_DOWN_TIME_MILLIS = 10_000;
    static final long COMMAND_TIMEOUT_MILLIS = 8_000L;

    private static final List<String> CMD_DATA_HELP =
            List.of("/system/bin/cmd", "phone", "data", "help");
    private static final List<String> SVC_DATA_HELP = List.of("/system/bin/svc", "data");
    private static final List<String> CMD_DISABLE =
            List.of("/system/bin/cmd", "phone", "data", "disable");
    private static final List<String> CMD_ENABLE =
            List.of("/system/bin/cmd", "phone", "data", "enable");
    private static final List<String> SVC_DISABLE =
            List.of("/system/bin/svc", "data", "disable");
    private static final List<String> SVC_ENABLE =
            List.of("/system/bin/svc", "data", "enable");

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final CommandExecutor executor;
    private final Sleeper sleeper;
    private final IntSupplier uidSupplier;

    MobileDataCommandEngine(CommandExecutor executor, Sleeper sleeper, IntSupplier uidSupplier) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.uidSupplier = Objects.requireNonNull(uidSupplier, "uidSupplier");
    }

    synchronized MobileDataCommandResult probe() {
        long startedNanos = System.nanoTime();
        CommandExecution primary = safeExecute(CMD_DATA_HELP);
        String primaryOutput = describe("cmd phone data help", primary);
        if (isCmdPhoneSupported(primary)) {
            return result(
                    MobileDataCommandResult.OPERATION_PROBE,
                    MobileDataCommandResult.STATUS_OK,
                    false,
                    false,
                    false,
                    primary.exitCode,
                    CommandExecution.NO_EXIT_CODE,
                    elapsedSince(startedNanos),
                    "Mobile-data control is available through cmd phone",
                    primaryOutput);
        }

        CommandExecution fallback = safeExecute(SVC_DATA_HELP);
        boolean supported = isSvcDataSupported(fallback);
        return result(
                MobileDataCommandResult.OPERATION_PROBE,
                supported
                        ? MobileDataCommandResult.STATUS_OK
                        : statusForFailure(fallback, MobileDataCommandResult.STATUS_UNSUPPORTED),
                true,
                false,
                false,
                fallback.exitCode,
                CommandExecution.NO_EXIT_CODE,
                elapsedSince(startedNanos),
                supported
                        ? "Mobile-data control is available through svc fallback"
                        : "Neither cmd phone data nor svc data was detected",
                joinOutput(primaryOutput, describe("svc data", fallback)));
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
                    "Mobile-data off time must be between 1 and 10 seconds",
                    "");
        }

        ActionExecution disable = null;
        ActionExecution enable;
        int status = MobileDataCommandResult.STATUS_OK;
        String message = "Mobile data was cycled and restored";
        boolean restoreThreadInterrupt = false;
        try {
            disable = executeAction(false);
            if (!disable.isSuccess()) {
                status = statusForFailure(
                        disable.effective, MobileDataCommandResult.STATUS_DISABLE_FAILED);
                message = "Could not disable mobile data";
            } else {
                try {
                    sleeper.sleep(downTimeMillis);
                } catch (InterruptedException exception) {
                    restoreThreadInterrupt = true;
                    status = MobileDataCommandResult.STATUS_INTERRUPTED;
                    message = "Mobile-data cycle was interrupted";
                } catch (RuntimeException exception) {
                    status = MobileDataCommandResult.STATUS_INTERNAL_ERROR;
                    message = "Mobile-data delay failed: " + safeMessage(exception);
                }
            }
        } finally {
            // Clear an interrupt just long enough to make the safety-critical restore attempt.
            restoreThreadInterrupt |= Thread.interrupted();
            enable = executeAction(true);
        }

        if (!enable.isSuccess()) {
            status = statusForFailure(
                    enable.effective, MobileDataCommandResult.STATUS_ENABLE_FAILED);
            message = "Mobile data could not be restored automatically";
        }
        if (restoreThreadInterrupt) Thread.currentThread().interrupt();

        return result(
                MobileDataCommandResult.OPERATION_CYCLE,
                status,
                (disable != null && disable.fallbackUsed) || enable.fallbackUsed,
                true,
                enable.isSuccess(),
                disable == null
                        ? CommandExecution.NO_EXIT_CODE
                        : disable.effective.exitCode,
                enable.effective.exitCode,
                elapsedSince(startedNanos),
                message,
                joinOutput(
                        disable == null ? "Disable command did not run" : disable.output,
                        enable.output));
    }

    synchronized MobileDataCommandResult restore() {
        long startedNanos = System.nanoTime();
        boolean restoreThreadInterrupt = Thread.interrupted();
        ActionExecution enable = executeAction(true);
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
                        ? "Mobile data was enabled"
                        : "Mobile data could not be restored automatically",
                enable.output);
    }

    private ActionExecution executeAction(boolean enable) {
        String action = enable ? "enable" : "disable";
        CommandExecution primary = safeExecute(enable ? CMD_ENABLE : CMD_DISABLE);
        String output = describe("cmd phone data " + action, primary);
        if (primary.isSuccess() || primary.interrupted) {
            return new ActionExecution(primary, false, output);
        }

        CommandExecution fallback = safeExecute(enable ? SVC_ENABLE : SVC_DISABLE);
        return new ActionExecution(
                fallback,
                true,
                joinOutput(output, describe("svc data " + action + " (fallback)", fallback)));
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

    private static boolean isCmdPhoneSupported(CommandExecution execution) {
        if (!isCompletedProbe(execution)) return false;
        String output = execution.output.toLowerCase(Locale.ROOT);
        return output.contains("data enable") && output.contains("data disable");
    }

    private static boolean isSvcDataSupported(CommandExecution execution) {
        if (!isCompletedProbe(execution)) return false;
        String output = execution.output.toLowerCase(Locale.ROOT);
        return output.contains("svc data")
                && output.contains("enable")
                && output.contains("disable");
    }

    private static boolean isCompletedProbe(CommandExecution execution) {
        return execution.launched && !execution.timedOut && !execution.interrupted;
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
