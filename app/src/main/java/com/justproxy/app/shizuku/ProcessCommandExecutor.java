package com.justproxy.app.shizuku;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes only the fixed airplane-mode and upgrade-recovery vectors accepted by JustProxy. */
final class ProcessCommandExecutor implements CommandExecutor {
    private static final int MAX_OUTPUT_CHARS = 4_096;
    private static final long TERMINATION_GRACE_MILLIS = 250L;

    @Override
    public CommandExecution execute(List<String> command, long timeoutMillis) {
        long startedNanos = System.nanoTime();
        if (!isAllowed(command)) {
            return CommandExecution.failedToStart(
                    "Command rejected by the JustProxy allowlist", elapsedSince(startedNanos));
        }
        if (timeoutMillis <= 0L) {
            return CommandExecution.failedToStart(
                    "Command timeout must be positive", elapsedSince(startedNanos));
        }

        final Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException | RuntimeException exception) {
            return CommandExecution.failedToStart(
                    safeExceptionMessage(exception), elapsedSince(startedNanos));
        }

        BoundedCollector collector = new BoundedCollector();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread drainer = new Thread(
                () -> drain(process.getInputStream(), collector, readFailure),
                "justproxy-shizuku-command-output");
        drainer.setDaemon(true);
        drainer.start();

        boolean timedOut = false;
        boolean interrupted = false;
        int exitCode = CommandExecution.NO_EXIT_CODE;
        try {
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                timedOut = true;
                terminate(process);
            }
            joinDrainer(drainer);
        } catch (InterruptedException exception) {
            interrupted = true;
            terminateImmediately(process);
            Thread.currentThread().interrupt();
        }

        IOException outputFailure = readFailure.get();
        if (outputFailure != null) {
            collector.appendNotice("Output read failed: " + safeExceptionMessage(outputFailure));
        }
        return new CommandExecution(
                true,
                timedOut,
                interrupted,
                exitCode,
                elapsedSince(startedNanos),
                collector.value(),
                "");
    }

    private static boolean isAllowed(List<String> command) {
        if (command == null) return false;
        if (command.equals(List.of(
                "/system/bin/cmd", "connectivity", "airplane-mode"))) return true;
        if (command.equals(List.of(
                "/system/bin/cmd", "connectivity", "airplane-mode", "enable"))) return true;
        if (command.equals(List.of(
                "/system/bin/cmd", "connectivity", "airplane-mode", "disable"))) return true;
        // Kept only so a beta.2 recovery marker can safely re-enable mobile data after upgrade.
        if (command.equals(List.of(
                "/system/bin/cmd", "phone", "data", "enable"))) return true;
        return command.equals(List.of("/system/bin/svc", "data", "enable"));
    }

    private static void drain(
            InputStream input, BoundedCollector collector, AtomicReference<IOException> failure) {
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[256];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                collector.append(buffer, count);
            }
        } catch (IOException exception) {
            failure.compareAndSet(null, exception);
        }
    }

    private static void joinDrainer(Thread drainer) throws InterruptedException {
        drainer.join(1_000L);
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static void terminateImmediately(Process process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // The process already exited or the runtime rejected another termination request.
        }
    }

    private static long elapsedSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String safeExceptionMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message.trim();
    }

    private static final class BoundedCollector {
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        synchronized void append(char[] chars, int count) {
            if (count <= 0) return;
            int remaining = MAX_OUTPUT_CHARS - value.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(remaining, count);
            value.append(chars, 0, accepted);
            if (accepted < count) truncated = true;
        }

        synchronized void appendNotice(String notice) {
            if (notice == null || notice.isEmpty()) return;
            char[] chars = ("\n" + notice).toCharArray();
            append(chars, chars.length);
        }

        synchronized String value() {
            String result = value.toString().replace('\u0000', ' ').trim();
            return truncated ? result + "\n...[output truncated]" : result;
        }
    }
}
