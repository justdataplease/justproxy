package com.justproxy.app.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ControlApiServerTest {
    private final AtomicInteger rotations = new AtomicInteger();
    private ControlApiServer server;
    private String baseUrl;

    @Before
    public void startServer() throws Exception {
        server = new ControlApiServer(InetAddress.getLoopbackAddress(), 0, "test-token",
                new ControlApiServer.Handler() {
                    @Override public String statusJson() { return "{status:ok}"; }
                    @Override public String metricsJson() { return "{metrics:ok}"; }
                    @Override public String ipHistoryJson() { return "{ips:ok}"; }
                    @Override public String sessionsJson() { return "{sessions:ok}"; }
                    @Override public String rotateJson() {
                        rotations.incrementAndGet();
                        return "{rotate:accepted}";
                    }
                    @Override public String checkIpJson() { return "{check:accepted}"; }
                }, message -> {});
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getBoundPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.close();
    }

    @Test
    public void rejectsMissingAndWrongBearerToken() throws Exception {
        assertEquals(401, request("GET", "/v1/status", null).status);
        assertEquals(401, request("GET", "/v1/status", "wrong").status);
    }

    @Test
    public void routesAuthenticatedReadsAndActions() throws Exception {
        Response status = request("GET", "/v1/status", "test-token");
        assertEquals(200, status.status);
        assertEquals("{status:ok}", status.body);

        Response rotate = request("POST", "/v1/rotate", "test-token");
        assertEquals(200, rotate.status);
        assertEquals("{rotate:accepted}", rotate.body);
        assertEquals(1, rotations.get());
    }

    @Test
    public void rejectsUnknownRouteWithoutLeakingDetails() throws Exception {
        Response response = request("GET", "/v1/unknown", "test-token");
        assertEquals(404, response.status);
        assertTrue(response.body.contains("not_found"));
    }

    @Test
    public void boundsSilentClientsAndClosesEveryTrackedSocketOnShutdown() throws Exception {
        int capacity = ControlApiServer.CLIENT_THREADS
                + ControlApiServer.CLIENT_QUEUE_CAPACITY;
        List<Socket> held = new ArrayList<>();
        try {
            for (int index = 0; index < capacity; index++) {
                held.add(connectSilently());
            }
            await(() -> server.getTrackedClientCount() == capacity);

            try (Socket overflow = connectSilently()) {
                assertRemoteClosed(overflow);
            }

            server.close();
            assertTrue(!server.isRunning());
            await(() -> server.getTrackedClientCount() == 0);
            for (Socket socket : held) assertRemoteClosed(socket);
        } finally {
            for (Socket socket : held) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private Response request(String method, String path, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if ("POST".equals(method)) connection.setDoOutput(true);
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = read(input);
        connection.disconnect();
        return new Response(status, body);
    }

    private Socket connectSilently() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), server.getBoundPort()), 5_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static void assertRemoteClosed(Socket socket) throws Exception {
        try {
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketException expected) {
            // Closing a live socket can surface as EOF or a platform-specific reset.
        }
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

    private static String read(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
