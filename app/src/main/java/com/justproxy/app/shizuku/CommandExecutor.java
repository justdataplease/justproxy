package com.justproxy.app.shizuku;

import java.util.List;

/** Injectable boundary around the very small privileged command allowlist. */
interface CommandExecutor {
    CommandExecution execute(List<String> command, long timeoutMillis);
}
