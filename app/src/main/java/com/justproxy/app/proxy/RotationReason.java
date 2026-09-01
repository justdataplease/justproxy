package com.justproxy.app.proxy;

/** The external reason for rotating (closing) all current proxy sessions. */
public enum RotationReason {
    MANUAL,
    SCHEDULED,
    NETWORK_CHANGED
}
