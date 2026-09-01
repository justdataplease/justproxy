package com.justproxy.app.proxy;

import java.io.IOException;

final class ProxyFailure extends IOException {
    private final SessionCloseReason closeReason;

    ProxyFailure(SessionCloseReason closeReason, String message) {
        super(message);
        this.closeReason = closeReason;
    }

    ProxyFailure(SessionCloseReason closeReason, String message, Throwable cause) {
        super(message, cause);
        this.closeReason = closeReason;
    }

    SessionCloseReason getCloseReason() {
        return closeReason;
    }
}
