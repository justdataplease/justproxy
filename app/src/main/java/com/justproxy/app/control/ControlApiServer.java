package com.justproxy.app.control;

import com.justproxy.app.security.ClientAddressPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal authenticated HTTP/JSON control plane. It never serves proxy traffic. */
public final class ControlApiServer implements AutoCloseable {
    public interface Handler {
        String statusJson();
        String metricsJson();
        String ipHistoryJson();
        String sessionsJson();
        String rotateJson();
        String checkIpJson();
    }

    public interface ErrorListener {
        void onControlApiError(String message);
    }

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int CLIENT_THREADS = 4;
    static final int CLIENT_QUEUE_CAPACITY = 8;
    private final InetAddress bindAddress;
    private final int port;
    private final byte[] expectedToken;
    private final Handler handler;
    private final ErrorListener errorListener;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> liveClients = ConcurrentHashMap.newKeySet();
    private volatile ThreadPoolExecutor clients;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    public ControlApiServer(InetAddress bindAddress, int port, String token,
                            Handler handler, ErrorListener errorListener) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
        this.errorListener = errorListener;
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port), 8);
        clients = new ThreadPoolExecutor(
                CLIENT_THREADS,
                CLIENT_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY),
                runnable -> {
            Thread thread = new Thread(runnable, "justproxy-control-client");
            thread.setDaemon(true);
            return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "justproxy-control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized int getBoundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    int getTrackedClientCount() {
        return liveClients.size();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                ServerSocket listener = serverSocket;
                if (listener == null) break;
                Socket client = listener.accept();
                if (!ClientAddressPolicy.isTrustedLocal(client.getInetAddress())) {
                    try { client.close(); } catch (IOException ignored) {}
                    continue;
                }
                client.setSoTimeout(5_000);
                submitClient(client);
            } catch (IOException exception) {
                boolean unexpected = running.getAndSet(false);
                if (unexpected && errorListener != null) {
                    errorListener.onControlApiError(safeMessage(exception));
                }
                break;
            }
        }
    }

    private void submitClient(Socket client) {
        ThreadPoolExecutor executor = clients;
        if (!running.get() || executor == null) {
            closeQuietly(client);
            return;
        }
        liveClients.add(client);
        if (!running.get()) {
            liveClients.remove(client);
            closeQuietly(client);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    handle(client);
                } finally {
                    liveClients.remove(client);
                    closeQuietly(client);
                }
            });
        } catch (RejectedExecutionException rejected) {
            liveClients.remove(client);
            closeQuietly(client);
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            Request request = readRequest(socket.getInputStream());
            if (request == null) {
                write(socket, 400, jsonError("bad_request"));
                return;
            }
            if (!authenticated(request.headers.get("authorization"))) {
                write(socket, 401, jsonError("unauthorized"));
                return;
            }
            String response;
            if ("GET".equals(request.method) && "/v1/status".equals(request.path)) {
                response = handler.statusJson();
            } else if ("GET".equals(request.method) && "/v1/metrics".equals(request.path)) {
                response = handler.metricsJson();
            } else if ("GET".equals(request.method) && "/v1/ip-history".equals(request.path)) {
                response = handler.ipHistoryJson();
            } else if ("GET".equals(request.method) && "/v1/sessions".equals(request.path)) {
                response = handler.sessionsJson();
            } else if ("POST".equals(request.method) && "/v1/rotate".equals(request.path)) {
                response = handler.rotateJson();
            } else if ("POST".equals(request.method) && "/v1/check-ip".equals(request.path)) {
                response = handler.checkIpJson();
            } else {
                write(socket, 404, jsonError("not_found"));
                return;
            }
            write(socket, 200, response == null ? "{}" : response);
        } catch (Exception ignored) {
            // Malformed and timed-out callers are isolated from the proxy service.
        }
    }

    private boolean authenticated(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] actual = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, actual);
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) return null;
            bytes.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') matched++;
            else matched = value == '\r' ? 1 : 0;
            if (matched == 4) break;
        }
        if (matched != 4) return null;
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1.name()).split("\\r\\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length != 3) return null;
        String path = first[1];
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        return new Request(first[0].toUpperCase(Locale.ROOT), path, headers);
    }

    private static void write(Socket socket, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 401 ? "Unauthorized" : "Not Found";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private static String jsonError(String code) {
        char quote = 34;
        return "{" + quote + "error" + quote + ":" + quote + code + quote + "}";
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        for (Socket client : liveClients) {
            closeQuietly(client);
        }
        liveClients.clear();
        ThreadPoolExecutor executor = clients;
        clients = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }
}
