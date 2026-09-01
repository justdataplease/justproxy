package com.justproxy.app.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ProxyServerTest {
    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-pass";

    @Test
    public void httpConnectRequiresAuthenticationAndUsesInjectedConnector() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (EchoServer target = new EchoServer()) {
            MappingConnector connector = new MappingConnector("unit.test");
            ProxyServerConfig config = baseConfig()
                    .outboundConnector(connector)
                    .allowPrivateDestinations(true)
                    .build();
            try (ProxyServer proxy = new ProxyServer(config, listener)) {
                proxy.start();

                try (Socket unauthenticated = connectTo(proxy)) {
                    writeAscii(
                            unauthenticated,
                            "CONNECT unit.test:" + target.getPort() + " HTTP/1.1\r\n"
                                    + "Host: unit.test\r\n\r\n");
                    assertTrue(readHttpHead(unauthenticated.getInputStream()).contains(" 407 "));
                }

                byte[] payload = "through-http-connect".getBytes(StandardCharsets.UTF_8);
                try (Socket client = connectTo(proxy)) {
                    writeAscii(
                            client,
                            "CONNECT unit.test:" + target.getPort() + " HTTP/1.1\r\n"
                                    + "Host: unit.test\r\n"
                                    + authorizationHeader()
                                    + "\r\n");
                    assertTrue(readHttpHead(client.getInputStream()).contains(" 200 "));
                    client.getOutputStream().write(payload);
                    client.getOutputStream().flush();
                    assertArrayEquals(payload, readFully(client.getInputStream(), payload.length));
                }

                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return proxy.getStatsSnapshot().getActiveConnections() == 0;
                    }
                });
                ProxyStatsSnapshot stats = proxy.getStatsSnapshot();
                assertEquals(2L, stats.getTotalConnections());
                assertEquals(payload.length, stats.getBytesUploaded());
                assertEquals(payload.length, stats.getBytesDownloaded());
                assertEquals(1, connector.resolveCalls.get());
                assertEquals(1, connector.connectCalls.get());

                ProxySessionSnapshot tunnel = listener.findClosed(ProxyProtocol.HTTP, true);
                assertNotNull(tunnel);
                assertEquals("unit.test", tunnel.getTargetHost());
                assertEquals(target.getPort(), tunnel.getTargetPort());
                assertEquals(InetAddress.getLoopbackAddress().getHostAddress(),
                        tunnel.getResolvedTargetAddress());
                assertTrue(listener.hasCloseReason(SessionCloseReason.AUTHENTICATION_FAILED));
            }
        }
    }

    @Test
    public void httpConnectPreservesClientHalfCloseForDelayedResponse() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (HalfCloseResponseServer target = new HalfCloseResponseServer()) {
            ProxyServerConfig config = baseConfig()
                    .allowPrivateDestinations(true)
                    .build();
            try (ProxyServer proxy = new ProxyServer(config, listener)) {
                proxy.start();
                String payload = "request-needs-eof";
                try (Socket client = connectTo(proxy)) {
                    writeAscii(
                            client,
                            "CONNECT 127.0.0.1:" + target.getPort() + " HTTP/1.1\r\n"
                                    + authorizationHeader() + "\r\n");
                    assertTrue(readHttpHead(client.getInputStream()).contains(" 200 "));
                    writeAscii(client, payload);
                    client.shutdownOutput();

                    assertEquals(
                            "reply-after-eof:" + payload,
                            new String(readUntilEof(client.getInputStream()),
                                    StandardCharsets.UTF_8));
                }
                await(() -> proxy.getStatsSnapshot().getActiveConnections() == 0);
                assertEquals(payload, target.getRequest());
            }
        }
    }

    @Test
    public void absoluteFormHttpIsRewrittenAndProxyCredentialsAreStripped() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (OneShotHttpServer target = new OneShotHttpServer()) {
            ProxyServerConfig config = baseConfig()
                    .allowPrivateDestinations(true)
                    .build();
            try (ProxyServer proxy = new ProxyServer(config, listener)) {
                proxy.start();
                String url = "http://127.0.0.1:" + target.getPort() + "/hello?q=one";
                try (Socket client = connectTo(proxy)) {
                    writeAscii(
                            client,
                            "GET " + url + " HTTP/1.1\r\n"
                                    + "Host: 127.0.0.1:" + target.getPort() + "\r\n"
                                    + authorizationHeader()
                                    + "Proxy-Connection: keep-alive\r\n"
                                    + "Connection: keep-alive\r\n"
                                    + "X-Test: forwarded\r\n\r\n");
                    String response = new String(
                            readUntilEof(client.getInputStream()), StandardCharsets.ISO_8859_1);
                    assertTrue(response.contains("200 OK"));
                    assertTrue(response.endsWith("hello"));
                }

                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return target.getRequestHead() != null;
                    }
                });
                String forwarded = target.getRequestHead();
                assertTrue(forwarded.startsWith("GET /hello?q=one HTTP/1.1\r\n"));
                assertTrue(forwarded.contains("X-Test: forwarded\r\n"));
                assertTrue(forwarded.contains("Connection: close\r\n"));
                assertFalse(forwarded.toLowerCase().contains("proxy-authorization"));
                assertFalse(forwarded.toLowerCase().contains("proxy-connection"));
                assertFalse(forwarded.contains("Connection: keep-alive"));

                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return proxy.getStatsSnapshot().getActiveConnections() == 0;
                    }
                });
                assertTrue(proxy.getStatsSnapshot().getBytesUploaded() > 0L);
                assertTrue(proxy.getStatsSnapshot().getBytesDownloaded() > 0L);
                assertNotNull(listener.findClosed(ProxyProtocol.HTTP, true));
            }
        }
    }

    @Test
    public void socks5UsernamePasswordConnectRelaysAndCountsBytes() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (EchoServer target = new EchoServer()) {
            ProxyServerConfig config = baseConfig()
                    .allowPrivateDestinations(true)
                    .build();
            try (ProxyServer proxy = new ProxyServer(config, listener)) {
                proxy.start();

                try (Socket badClient = connectTo(proxy)) {
                    negotiateSocksMethod(badClient);
                    sendSocksCredentials(badClient, USERNAME, "wrong");
                    assertArrayEquals(
                            new byte[] {0x01, 0x01},
                            readFully(badClient.getInputStream(), 2));
                }

                byte[] payload = "through-socks-five".getBytes(StandardCharsets.UTF_8);
                try (Socket client = connectTo(proxy)) {
                    negotiateSocksMethod(client);
                    sendSocksCredentials(client, USERNAME, PASSWORD);
                    assertArrayEquals(
                            new byte[] {0x01, 0x00},
                            readFully(client.getInputStream(), 2));
                    sendSocksIpv4Connect(client, target.getPort());
                    readSuccessfulSocksReply(client.getInputStream());
                    client.getOutputStream().write(payload);
                    client.getOutputStream().flush();
                    assertArrayEquals(payload, readFully(client.getInputStream(), payload.length));
                }

                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return proxy.getStatsSnapshot().getActiveConnections() == 0;
                    }
                });
                ProxyStatsSnapshot stats = proxy.getStatsSnapshot();
                assertEquals(payload.length, stats.getBytesUploaded());
                assertEquals(payload.length, stats.getBytesDownloaded());
                ProxySessionSnapshot tunnel = listener.findClosed(ProxyProtocol.SOCKS5, true);
                assertNotNull(tunnel);
                assertEquals(target.getPort(), tunnel.getTargetPort());
                assertTrue(listener.hasCloseReason(SessionCloseReason.AUTHENTICATION_FAILED));
            }
        }
    }

    @Test
    public void socks5RawMappedIpv6CannotReachPrivateIpv4Targets() throws Exception {
        NeverConnector connector = new NeverConnector();
        RecordingListener listener = new RecordingListener();
        ProxyServerConfig config = baseConfig().outboundConnector(connector).build();
        try (ProxyServer proxy = new ProxyServer(config, listener)) {
            proxy.start();
            String[] blocked = {"127.0.0.1", "10.0.0.1", "169.254.169.254"};
            for (String target : blocked) {
                try (Socket client = connectTo(proxy)) {
                    negotiateSocksMethod(client);
                    sendSocksCredentials(client, USERNAME, PASSWORD);
                    assertArrayEquals(
                            new byte[] {0x01, 0x00},
                            readFully(client.getInputStream(), 2));
                    sendSocksMappedIpv6Connect(client, target, 80);
                    assertArrayEquals(
                            new byte[] {0x05, 0x02},
                            readFully(client.getInputStream(), 2));
                }
            }
            await(() -> proxy.getStatsSnapshot().getActiveConnections() == 0);
            assertEquals(0, connector.resolveCalls.get());
            assertEquals(0, connector.connectCalls.get());
            await(() -> listener.hasCloseReason(SessionCloseReason.DESTINATION_DENIED));
        }
    }

    @Test
    public void silentClientUsesHandshakeTimeoutThenAuthenticatedClientCanConnect()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        ProxyServerConfig config = baseConfig()
                .handshakeTimeoutMillis(200)
                .idleTimeoutMillis(10_000)
                .maxConnections(1)
                .allowPrivateDestinations(true)
                .build();
        ProxyServer proxy = new ProxyServer(config, listener);
        proxy.start();
        try {
            Socket idleClient = connectTo(proxy);
            await(new Condition() {
                @Override
                public boolean evaluate() {
                    return proxy.getStatsSnapshot().getActiveConnections() == 1;
                }
            });
            try (Socket rejectedClient = connectTo(proxy)) {
                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return proxy.getStatsSnapshot().getRejectedConnections() == 1L;
                    }
                });
            }

            await(new Condition() {
                @Override
                public boolean evaluate() {
                    return proxy.getStatsSnapshot().getActiveConnections() == 0;
                }
            });
            idleClient.close();
            assertTrue(listener.hasCloseReason(SessionCloseReason.MAX_CONNECTIONS));
            assertTrue(listener.hasCloseReason(SessionCloseReason.IDLE_TIMEOUT));

            try (EchoServer target = new EchoServer(); Socket tunnel = connectTo(proxy)) {
                writeAscii(
                        tunnel,
                        "CONNECT 127.0.0.1:" + target.getPort() + " HTTP/1.1\r\n"
                                + authorizationHeader() + "\r\n");
                assertTrue(readHttpHead(tunnel.getInputStream()).contains(" 200 "));
                assertEquals(1, proxy.rotateSessions(RotationReason.SCHEDULED));
                await(new Condition() {
                    @Override
                    public boolean evaluate() {
                        return proxy.getStatsSnapshot().getActiveConnections() == 0;
                    }
                });
            }
            assertEquals(1, listener.rotations.size());
            assertEquals(RotationReason.SCHEDULED, listener.rotations.get(0).getReason());
            assertEquals(1, listener.rotations.get(0).getSessionsTargeted());
            assertTrue(listener.hasCloseReason(SessionCloseReason.ROTATED));
        } finally {
            proxy.stop();
        }
        assertFalse(proxy.isRunning());
        assertEquals(0, proxy.getStatsSnapshot().getActiveConnections());
    }

    @Test
    public void blocksPrivateTargetsSmtpAndOversizedHttpHeaders() throws Exception {
        NeverConnector connector = new NeverConnector();
        RecordingListener listener = new RecordingListener();
        ProxyServerConfig config = baseConfig()
                .outboundConnector(connector)
                .maxHttpHeaderBytes(1024)
                .build();
        try (ProxyServer proxy = new ProxyServer(config, listener)) {
            proxy.start();
            try (Socket smtp = connectTo(proxy)) {
                writeAscii(
                        smtp,
                        "CONNECT example.com:25 HTTP/1.1\r\n"
                                + authorizationHeader() + "\r\n");
                assertTrue(readHttpHead(smtp.getInputStream()).contains(" 403 "));
            }

            try (Socket oversized = connectTo(proxy)) {
                writeAscii(
                        oversized,
                        "GET http://example.com/ HTTP/1.1\r\n"
                                + authorizationHeader()
                                + "X-Large: " + repeat('a', 1200) + "\r\n\r\n");
                assertTrue(readHttpHead(oversized.getInputStream()).contains(" 431 "));
            }

            await(new Condition() {
                @Override
                public boolean evaluate() {
                    return proxy.getStatsSnapshot().getActiveConnections() == 0;
                }
            });
            assertEquals(0, connector.resolveCalls.get());
            assertEquals(0, connector.connectCalls.get());
            assertTrue(listener.hasCloseReason(SessionCloseReason.DESTINATION_DENIED));
            assertTrue(listener.hasCloseReason(SessionCloseReason.PROTOCOL_ERROR));
        }
    }

    private static ProxyServerConfig.Builder baseConfig() throws Exception {
        return ProxyServerConfig.builder(USERNAME, PASSWORD)
                .bindAddress(InetAddress.getLoopbackAddress())
                .port(0)
                .connectTimeoutMillis(1_000)
                .idleTimeoutMillis(2_000)
                .maxConnections(4);
    }

    private static Socket connectTo(ProxyServer server) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getBoundPort()),
                1_000);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static String authorizationHeader() {
        String token = Base64.getEncoder().encodeToString(
                (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        return "Proxy-Authorization: Basic " + token + "\r\n";
    }

    private static void negotiateSocksMethod(Socket client) throws IOException {
        client.getOutputStream().write(new byte[] {0x05, 0x01, 0x02});
        client.getOutputStream().flush();
        assertArrayEquals(new byte[] {0x05, 0x02}, readFully(client.getInputStream(), 2));
    }

    private static void sendSocksCredentials(Socket client, String username, String password)
            throws IOException {
        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        output.write(0x01);
        output.write(usernameBytes.length);
        output.write(usernameBytes);
        output.write(passwordBytes.length);
        output.write(passwordBytes);
        output.flush();
    }

    private static void sendSocksIpv4Connect(Socket client, int port) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1});
        output.write((port >>> 8) & 0xff);
        output.write(port & 0xff);
        output.flush();
    }

    private static void sendSocksMappedIpv6Connect(Socket client, String ipv4, int port)
            throws IOException {
        byte[] address = new byte[16];
        address[10] = (byte) 0xff;
        address[11] = (byte) 0xff;
        byte[] ipv4Bytes = InetAddress.getByName(ipv4).getAddress();
        System.arraycopy(ipv4Bytes, 0, address, 12, 4);
        OutputStream output = client.getOutputStream();
        output.write(new byte[] {0x05, 0x01, 0x00, 0x04});
        output.write(address);
        output.write((port >>> 8) & 0xff);
        output.write(port & 0xff);
        output.flush();
    }

    private static void readSuccessfulSocksReply(InputStream input) throws IOException {
        byte[] prefix = readFully(input, 4);
        assertEquals(0x05, prefix[0] & 0xff);
        assertEquals(0x00, prefix[1] & 0xff);
        int addressLength;
        if ((prefix[3] & 0xff) == 0x01) {
            addressLength = 4;
        } else if ((prefix[3] & 0xff) == 0x04) {
            addressLength = 16;
        } else if ((prefix[3] & 0xff) == 0x03) {
            addressLength = input.read();
        } else {
            throw new IOException("invalid SOCKS reply address type");
        }
        readFully(input, addressLength + 2);
    }

    private static void writeAscii(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static String readHttpHead(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int matched = 0;
        String marker = "\r\n\r\n";
        while (result.size() < 64 * 1024) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("response ended before HTTP headers completed");
            }
            result.write(next);
            if (next == marker.charAt(matched)) {
                matched++;
                if (matched == marker.length()) {
                    return new String(result.toByteArray(), StandardCharsets.ISO_8859_1);
                }
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        throw new IOException("response headers too large");
    }

    private static byte[] readFully(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(bytes, offset, count - offset);
            if (read < 0) {
                throw new EOFException("unexpected end of stream");
            }
            offset += read;
        }
        return bytes;
    }

    private static byte[] readUntilEof(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        for (int i = 0; i < characters.length; i++) {
            characters[i] = value;
        }
        return new String(characters);
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!condition.evaluate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("condition was not met before timeout", condition.evaluate());
    }

    private interface Condition {
        boolean evaluate();
    }

    private static final class RecordingListener implements ProxyAnalyticsListener {
        private final List<ProxySessionSnapshot> closed =
                new CopyOnWriteArrayList<ProxySessionSnapshot>();
        private final List<ProxyRotationEvent> rotations =
                new CopyOnWriteArrayList<ProxyRotationEvent>();

        @Override
        public void onSessionClosed(ProxySessionSnapshot session, SessionCloseReason reason) {
            closed.add(session);
        }

        @Override
        public void onRotation(ProxyRotationEvent event) {
            rotations.add(event);
        }

        private ProxySessionSnapshot findClosed(ProxyProtocol protocol, boolean authenticated) {
            for (ProxySessionSnapshot session : closed) {
                if (session.getProtocol() == protocol
                        && session.isAuthenticated() == authenticated) {
                    return session;
                }
            }
            return null;
        }

        private boolean hasCloseReason(SessionCloseReason reason) {
            for (ProxySessionSnapshot session : closed) {
                if (session.getCloseReason() == reason) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class MappingConnector implements OutboundConnector {
        private final String expectedHost;
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger connectCalls = new AtomicInteger();

        private MappingConnector(String expectedHost) {
            this.expectedHost = expectedHost;
        }

        @Override
        public InetAddress[] resolve(String host) throws IOException {
            assertEquals(expectedHost, host);
            resolveCalls.incrementAndGet();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }

        @Override
        public Socket connect(InetAddress address, int port, int timeoutMillis)
                throws IOException {
            connectCalls.incrementAndGet();
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
            return socket;
        }
    }

    private static final class NeverConnector implements OutboundConnector {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger connectCalls = new AtomicInteger();

        @Override
        public InetAddress[] resolve(String host) throws IOException {
            resolveCalls.incrementAndGet();
            throw new IOException("must not resolve");
        }

        @Override
        public Socket connect(InetAddress address, int port, int timeoutMillis)
                throws IOException {
            connectCalls.incrementAndGet();
            throw new IOException("must not connect");
        }
    }

    private static final class HalfCloseResponseServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile String request;
        private volatile boolean closed;

        private HalfCloseResponseServer() throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(this::runServer, "test-half-close-server");
            thread.setDaemon(true);
            thread.start();
        }

        private int getPort() {
            return server.getLocalPort();
        }

        private String getRequest() {
            return request;
        }

        private void runServer() {
            try (Socket client = server.accept()) {
                request = new String(readUntilEof(client.getInputStream()),
                        StandardCharsets.UTF_8);
                writeAscii(client, "reply-after-eof:" + request);
                client.shutdownOutput();
            } catch (IOException ignored) {
                if (!closed) throw new AssertionError(ignored);
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            thread.join(1_000L);
        }
    }

    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile boolean closed;

        private EchoServer() throws IOException {
            server = new ServerSocket(
                    0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "test-echo-server");
            thread.setDaemon(true);
            thread.start();
        }

        private int getPort() {
            return server.getLocalPort();
        }

        private void runServer() {
            try (Socket client = server.accept()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = client.getInputStream().read(buffer)) >= 0) {
                    client.getOutputStream().write(buffer, 0, read);
                    client.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                if (!closed) {
                    throw new AssertionError(ignored);
                }
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            thread.join(1_000L);
        }
    }

    private static final class OneShotHttpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile String requestHead;
        private volatile boolean closed;

        private OneShotHttpServer() throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "test-http-origin");
            thread.setDaemon(true);
            thread.start();
        }

        private int getPort() {
            return server.getLocalPort();
        }

        private String getRequestHead() {
            return requestHead;
        }

        private void runServer() {
            try (Socket client = server.accept()) {
                requestHead = readHttpHead(client.getInputStream());
                writeAscii(
                        client,
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello");
            } catch (IOException ignored) {
                if (!closed) {
                    throw new AssertionError(ignored);
                }
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            thread.join(1_000L);
        }
    }
}
