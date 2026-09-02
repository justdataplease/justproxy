package com.justproxy.app.shizuku;

/** Internal, platform-neutral process result used by the command policy engine. */
final class CommandExecution {
    static final int NO_EXIT_CODE = -1;

    final boolean launched;
    final boolean timedOut;
    final boolean interrupted;
    final int exitCode;
    final long elapsedMillis;
    final String output;
    final String error;

    CommandExecution(
            boolean launched,
            boolean timedOut,
            boolean interrupted,
            int exitCode,
            long elapsedMillis,
            String output,
            String error) {
        this.launched = launched;
        this.timedOut = timedOut;
        this.interrupted = interrupted;
        this.exitCode = exitCode;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.output = output == null ? "" : output;
        this.error = error == null ? "" : error;
    }

    static CommandExecution completed(int exitCode, String output) {
        return new CommandExecution(true, false, false, exitCode, 0L, output, "");
    }

    static CommandExecution failedToStart(String error, long elapsedMillis) {
        return new CommandExecution(
                false, false, false, NO_EXIT_CODE, elapsedMillis, "", error);
    }

    boolean isSuccess() {
        return launched && !timedOut && !interrupted && exitCode == 0;
    }
}
