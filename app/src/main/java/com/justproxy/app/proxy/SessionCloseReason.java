package com.justproxy.app.proxy;

/** Why a proxy session ended. */
public enum SessionCloseReason {
    COMPLETED,
    CLIENT_CLOSED,
    IDLE_TIMEOUT,
    AUTHENTICATION_FAILED,
    DESTINATION_DENIED,
    CONNECT_FAILED,
    PROTOCOL_ERROR,
    MAX_CONNECTIONS,
    SERVER_STOPPED,
    ROTATED,
    NETWORK_ERROR,
    INTERNAL_ERROR
}
