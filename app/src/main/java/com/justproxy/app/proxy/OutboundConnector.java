package com.justproxy.app.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Resolves and connects upstream destinations.
 *
 * <p>Android callers can implement this with {@code Network.getAllByName()} and
 * {@code Network.getSocketFactory()} to keep both DNS and TCP on one selected network. The
 * proxy never silently falls back to the system network when a custom connector is supplied.
 */
public interface OutboundConnector {
    InetAddress[] resolve(String host) throws IOException;

    Socket connect(InetAddress address, int port, int connectTimeoutMillis) throws IOException;
}
