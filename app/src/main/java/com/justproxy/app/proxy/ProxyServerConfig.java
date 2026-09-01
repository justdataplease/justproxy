package com.justproxy.app.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.net.SocketFactory;

/** Immutable configuration for {@link ProxyServer}. */
public final class ProxyServerConfig {
    private final InetAddress bindAddress;
    private final int port;
    private final byte[] username;
    private final byte[] password;
    private final byte[] basicCredentials;
    private final int handshakeTimeoutMillis;
    private final int idleTimeoutMillis;
    private final int connectTimeoutMillis;
    private final int maxConnections;
    private final int maxHttpHeaderBytes;
    private final int serverBacklog;
    private final boolean allowPrivateDestinations;
    private final OutboundConnector outboundConnector;

    private ProxyServerConfig(Builder builder) {
        bindAddress = builder.bindAddress;
        port = builder.port;
        username = builder.username.getBytes(StandardCharsets.UTF_8);
        password = builder.password.getBytes(StandardCharsets.UTF_8);
        basicCredentials = (builder.username + ":" + builder.password)
                .getBytes(StandardCharsets.UTF_8);
        handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
        idleTimeoutMillis = builder.idleTimeoutMillis;
        connectTimeoutMillis = builder.connectTimeoutMillis;
        maxConnections = builder.maxConnections;
        maxHttpHeaderBytes = builder.maxHttpHeaderBytes;
        serverBacklog = builder.serverBacklog;
        allowPrivateDestinations = builder.allowPrivateDestinations;
        outboundConnector = builder.outboundConnector;
    }

    public static Builder builder(String username, String password) {
        return new Builder(username, password);
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    /** Port zero requests an ephemeral port. */
    public int getPort() {
        return port;
    }

    public int getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /** Maximum time allowed to finish the proxy protocol and authentication handshake. */
    public int getHandshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getMaxHttpHeaderBytes() {
        return maxHttpHeaderBytes;
    }

    public int getServerBacklog() {
        return serverBacklog;
    }

    public boolean isPrivateDestinationsAllowed() {
        return allowPrivateDestinations;
    }

    public OutboundConnector getOutboundConnector() {
        return outboundConnector;
    }

    byte[] getBasicCredentials() {
        return basicCredentials;
    }

    byte[] getUsername() {
        return username;
    }

    byte[] getPassword() {
        return password;
    }

    /** Builder with conservative ingress and egress defaults. */
    public static final class Builder {
        private final String username;
        private final String password;
        private InetAddress bindAddress = InetAddress.getLoopbackAddress();
        private int port = 8080;
        private int handshakeTimeoutMillis = 8_000;
        private int idleTimeoutMillis = 60_000;
        private int connectTimeoutMillis = 10_000;
        private int maxConnections = 8;
        private int maxHttpHeaderBytes = 32 * 1024;
        private int serverBacklog = 50;
        private boolean allowPrivateDestinations;
        private OutboundConnector outboundConnector = new SystemOutboundConnector();

        private Builder(String username, String password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password");
        }

        public Builder bindAddress(InetAddress bindAddress) {
            this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder idleTimeoutMillis(int idleTimeoutMillis) {
            this.idleTimeoutMillis = idleTimeoutMillis;
            return this;
        }

        public Builder handshakeTimeoutMillis(int handshakeTimeoutMillis) {
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
            return this;
        }

        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder maxHttpHeaderBytes(int maxHttpHeaderBytes) {
            this.maxHttpHeaderBytes = maxHttpHeaderBytes;
            return this;
        }

        public Builder serverBacklog(int serverBacklog) {
            this.serverBacklog = serverBacklog;
            return this;
        }

        /**
         * Allows loopback, link-local, site-local/private, shared, and unique-local targets.
         * Any-local, multicast, and TCP port 25 remain blocked.
         */
        public Builder allowPrivateDestinations(boolean allowPrivateDestinations) {
            this.allowPrivateDestinations = allowPrivateDestinations;
            return this;
        }

        public Builder outboundConnector(OutboundConnector outboundConnector) {
            this.outboundConnector = Objects.requireNonNull(
                    outboundConnector, "outboundConnector");
            return this;
        }

        public ProxyServerConfig build() {
            byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            if (usernameBytes.length == 0 || usernameBytes.length > 255) {
                throw new IllegalArgumentException("username must contain 1 to 255 UTF-8 bytes");
            }
            if (username.indexOf(':') >= 0) {
                throw new IllegalArgumentException("username must not contain ':'");
            }
            if (passwordBytes.length == 0 || passwordBytes.length > 255) {
                throw new IllegalArgumentException("password must contain 1 to 255 UTF-8 bytes");
            }
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            if (idleTimeoutMillis < 100 || idleTimeoutMillis > 24 * 60 * 60 * 1000) {
                throw new IllegalArgumentException(
                        "idleTimeoutMillis must be between 100 and 86400000");
            }
            if (handshakeTimeoutMillis < 100 || handshakeTimeoutMillis > 60_000) {
                throw new IllegalArgumentException(
                        "handshakeTimeoutMillis must be between 100 and 60000");
            }
            if (connectTimeoutMillis < 100 || connectTimeoutMillis > 120_000) {
                throw new IllegalArgumentException(
                        "connectTimeoutMillis must be between 100 and 120000");
            }
            if (maxConnections < 1 || maxConnections > 1024) {
                throw new IllegalArgumentException("maxConnections must be between 1 and 1024");
            }
            if (maxHttpHeaderBytes < 1024 || maxHttpHeaderBytes > 1024 * 1024) {
                throw new IllegalArgumentException(
                        "maxHttpHeaderBytes must be between 1024 and 1048576");
            }
            if (serverBacklog < 1 || serverBacklog > 1024) {
                throw new IllegalArgumentException("serverBacklog must be between 1 and 1024");
            }
            return new ProxyServerConfig(this);
        }
    }

    private static final class SystemOutboundConnector implements OutboundConnector {
        @Override
        public InetAddress[] resolve(String host) throws IOException {
            return InetAddress.getAllByName(host);
        }

        @Override
        public Socket connect(InetAddress address, int port, int connectTimeoutMillis)
                throws IOException {
            Socket socket = SocketFactory.getDefault().createSocket();
            boolean connected = false;
            try {
                socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
                connected = true;
                return socket;
            } finally {
                if (!connected) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Preserve the connection failure.
                    }
                }
            }
        }
    }
}
