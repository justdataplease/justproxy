package com.justproxy.app.proxy;

/**
 * Receives in-memory proxy activity events. Implementations should return quickly and move
 * persistence or expensive processing onto their own executor.
 */
public interface ProxyAnalyticsListener {
    ProxyAnalyticsListener NONE = new ProxyAnalyticsListener() {};

    default void onSessionOpened(ProxySessionSnapshot session) {}

    default void onSessionUpdated(ProxySessionSnapshot session) {}

    default void onSessionClosed(ProxySessionSnapshot session, SessionCloseReason reason) {}

    default void onRotation(ProxyRotationEvent event) {}
}
