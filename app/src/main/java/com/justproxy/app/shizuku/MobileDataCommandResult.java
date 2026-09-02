package com.justproxy.app.shizuku;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable, bounded result returned by the privileged mobile-data UserService. */
public final class MobileDataCommandResult implements Parcelable {
    public static final int OPERATION_PROBE = 1;
    public static final int OPERATION_CYCLE = 2;
    public static final int OPERATION_RESTORE = 3;
    public static final int OPERATION_RECONCILE_RECOVERY = 4;

    public static final int STATUS_OK = 0;
    public static final int STATUS_UNSUPPORTED = 1;
    public static final int STATUS_INVALID_ARGUMENT = 2;
    public static final int STATUS_DISABLE_FAILED = 3;
    public static final int STATUS_ENABLE_FAILED = 4;
    public static final int STATUS_TIMED_OUT = 5;
    public static final int STATUS_INTERRUPTED = 6;
    public static final int STATUS_INTERNAL_ERROR = 7;

    private static final int MAX_MESSAGE_CHARS = 256;
    private static final int MAX_OUTPUT_CHARS = 4_096;

    public static final Creator<MobileDataCommandResult> CREATOR =
            new Creator<MobileDataCommandResult>() {
                @Override
                public MobileDataCommandResult createFromParcel(Parcel source) {
                    return new MobileDataCommandResult(source);
                }

                @Override
                public MobileDataCommandResult[] newArray(int size) {
                    return new MobileDataCommandResult[size];
                }
            };

    private final int operation;
    private final int status;
    private final int serverUid;
    private final boolean fallbackUsed;
    private final boolean restoreAttempted;
    private final boolean restoreSucceeded;
    private final int commandExitCode;
    private final int restoreExitCode;
    private final long elapsedMillis;
    private final String message;
    private final String output;

    public MobileDataCommandResult(
            int operation,
            int status,
            int serverUid,
            boolean fallbackUsed,
            boolean restoreAttempted,
            boolean restoreSucceeded,
            int commandExitCode,
            int restoreExitCode,
            long elapsedMillis,
            String message,
            String output) {
        this.operation = operation;
        this.status = status;
        this.serverUid = serverUid;
        this.fallbackUsed = fallbackUsed;
        this.restoreAttempted = restoreAttempted;
        this.restoreSucceeded = restoreSucceeded;
        this.commandExitCode = commandExitCode;
        this.restoreExitCode = restoreExitCode;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.message = limit(message == null ? "" : message, MAX_MESSAGE_CHARS);
        this.output = limit(output == null ? "" : output, MAX_OUTPUT_CHARS);
    }

    private MobileDataCommandResult(Parcel source) {
        operation = source.readInt();
        status = source.readInt();
        serverUid = source.readInt();
        fallbackUsed = source.readInt() != 0;
        restoreAttempted = source.readInt() != 0;
        restoreSucceeded = source.readInt() != 0;
        commandExitCode = source.readInt();
        restoreExitCode = source.readInt();
        elapsedMillis = Math.max(0L, source.readLong());
        String parcelMessage = source.readString();
        String parcelOutput = source.readString();
        message = limit(parcelMessage == null ? "" : parcelMessage, MAX_MESSAGE_CHARS);
        output = limit(parcelOutput == null ? "" : parcelOutput, MAX_OUTPUT_CHARS);
    }

    public int getOperation() {
        return operation;
    }

    public int getStatus() {
        return status;
    }

    public int getServerUid() {
        return serverUid;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public boolean isRestoreAttempted() {
        return restoreAttempted;
    }

    public boolean isRestoreSucceeded() {
        return restoreSucceeded;
    }

    public int getCommandExitCode() {
        return commandExitCode;
    }

    public int getRestoreExitCode() {
        return restoreExitCode;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getMessage() {
        return message;
    }

    public String getOutput() {
        return output;
    }

    public boolean isSuccess() {
        return status == STATUS_OK;
    }

    MobileDataCommandResult withRestoreVerification(boolean enabled, String verificationMessage) {
        int verifiedStatus = !enabled && status == STATUS_OK ? STATUS_ENABLE_FAILED : status;
        String verifiedMessage = verificationMessage == null || verificationMessage.trim().isEmpty()
                ? message
                : verificationMessage.trim();
        return new MobileDataCommandResult(
                operation,
                verifiedStatus,
                serverUid,
                fallbackUsed,
                restoreAttempted,
                enabled,
                commandExitCode,
                restoreExitCode,
                elapsedMillis,
                verifiedMessage,
                output);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(operation);
        destination.writeInt(status);
        destination.writeInt(serverUid);
        destination.writeInt(fallbackUsed ? 1 : 0);
        destination.writeInt(restoreAttempted ? 1 : 0);
        destination.writeInt(restoreSucceeded ? 1 : 0);
        destination.writeInt(commandExitCode);
        destination.writeInt(restoreExitCode);
        destination.writeLong(elapsedMillis);
        destination.writeString(message);
        destination.writeString(output);
    }

    private static String limit(String value, int maxChars) {
        String normalized = value.replace('\u0000', ' ').trim();
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars - 14) + "...[truncated]";
    }
}
