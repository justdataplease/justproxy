package com.justproxy.app.proxy;

import com.justproxy.app.security.ClientAddressPolicy;

import java.io.EOFException;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated, TCP-only HTTP and SOCKS5 proxy listener.
 *
 * <p>The first client byte selects SOCKS5 ({@code 0x05}) or HTTP. HTTP supports CONNECT and
 * absolute-form HTTP requests. SOCKS5 supports username/password authenticated TCP CONNECT;
 * UDP ASSOCIATE and BIND are intentionally unsupported.
 */
public final class ProxyServer implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_MILLIS = 5_000L;

    private final ProxyServerConfig config;
    private final ProxyAnalyticsListener analyticsListener;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong nextSessionId = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong bytesUploaded = new AtomicLong();
    private final AtomicLong bytesDownloaded = new AtomicLong();
    private final Map<Long, ProxySession> activeSessions =
            new ConcurrentHashMap<Long, ProxySession>();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ExecutorService workerExecutor;
    private volatile Semaphore connectionSlots;
    private volatile long startedAtEpochMillis;

    public ProxyServer(ProxyServerConfig config) {
        this(config, ProxyAnalyticsListener.NONE);
    }

    public ProxyServer(ProxyServerConfig config, ProxyAnalyticsListener analyticsListener) {
        this.config = Objects.requireNonNull(config, "config");
        this.analyticsListener = analyticsListener == null
                ? ProxyAnalyticsListener.NONE
                : analyticsListener;
    }

    /** Starts the listener. A server instance may be stopped and started again. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("proxy server is already running");
        }
        if (!activeSessions.isEmpty()) {
            throw new IllegalStateException("previous proxy sessions are still stopping");
        }

        ServerSocket candidate = new ServerSocket();
        boolean bound = false;
        try {
            candidate.setReuseAddress(true);
            candidate.bind(
                    new InetSocketAddress(config.getBindAddress(), config.getPort()),
                    config.getServerBacklog());
            bound = true;
        } finally {
            if (!bound) {
                try {
                    candidate.close();
                } catch (IOException ignored) {
                    // Preserve the bind error.
                }
            }
        }

        serverSocket = candidate;
        connectionSlots = new Semaphore(config.getMaxConnections());
        workerExecutor = Executors.newCachedThreadPool(new ProxyThreadFactory("worker"));
        startedAtEpochMillis = System.currentTimeMillis();
        running.set(true);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "justproxy-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Returns the actual listener port, including an ephemeral port selected for port zero. */
    public int getBoundPort() {
        ServerSocket socket = serverSocket;
        return socket == null || !socket.isBound() ? -1 : socket.getLocalPort();
    }

    public ProxyStatsSnapshot getStatsSnapshot() {
        return new ProxyStatsSnapshot(
                running.get(),
                startedAtEpochMillis,
                System.currentTimeMillis(),
                totalConnections.get(),
                activeConnections.get(),
                rejectedConnections.get(),
                bytesUploaded.get(),
                bytesDownloaded.get());
    }

    public List<ProxySessionSnapshot> getActiveSessions() {
        List<ProxySessionSnapshot> result = new ArrayList<ProxySessionSnapshot>();
        for (ProxySession session : activeSessions.values()) {
            result.add(session.snapshot());
        }
        Collections.sort(result, new Comparator<ProxySessionSnapshot>() {
            @Override
            public int compare(ProxySessionSnapshot left, ProxySessionSnapshot right) {
                return Long.compare(left.getId(), right.getId());
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * Closes every current session while leaving the listener running.
     *
     * @return number of sessions targeted by this rotation
     */
    public int rotateSessions(RotationReason reason) {
        Objects.requireNonNull(reason, "reason");
        List<ProxySession> sessions = new ArrayList<ProxySession>(activeSessions.values());
        notifyRotation(new ProxyRotationEvent(System.currentTimeMillis(), reason, sessions.size()));
        for (ProxySession session : sessions) {
            session.requestClose(SessionCloseReason.ROTATED);
        }
        return sessions.size();
    }

    /** Stops accepting clients, closes active sessions, and waits briefly for workers to exit. */
    public void stop() {
        ServerSocket listener;
        ExecutorService executor;
        Thread accepter;
        synchronized (this) {
            boolean wasRunning = running.getAndSet(false);
            if (!wasRunning && serverSocket == null && workerExecutor == null) {
                return;
            }
            listener = serverSocket;
            executor = workerExecutor;
            accepter = acceptThread;
        }

        closeServerSocket(listener);
        for (ProxySession session : new ArrayList<ProxySession>(activeSessions.values())) {
            session.requestClose(SessionCloseReason.SERVER_STOPPED);
        }
        if (executor != null) {
            executor.shutdown();
        }
        joinThread(accepter, SHUTDOWN_WAIT_MILLIS);
        if (executor != null) {
            try {
                if (!executor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        synchronized (this) {
            if (serverSocket == listener) {
                serverSocket = null;
                workerExecutor = null;
                acceptThread = null;
                connectionSlots = null;
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    void addUploaded(long count) {
        bytesUploaded.addAndGet(count);
    }

    void addDownloaded(long count) {
        bytesDownloaded.addAndGet(count);
    }

    void publishSessionUpdated(ProxySession session, boolean force) {
        long now = System.currentTimeMillis();
        if (force || session.shouldPublish(now)) {
            try {
                analyticsListener.onSessionUpdated(session.snapshot());
            } catch (RuntimeException ignored) {
                // Analytics must never interrupt proxy traffic.
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                if (!running.get()) {
                    ProxyIo.closeQuietly(client);
                    break;
                }
                totalConnections.incrementAndGet();
                if (!ClientAddressPolicy.isTrustedLocal(client.getInetAddress())) {
                    rejectedConnections.incrementAndGet();
                    ProxyIo.closeQuietly(client);
                    client = null;
                    continue;
                }
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                // Unauthenticated clients get a short, separate deadline so a handful of
                // silent sockets cannot occupy every configured connection slot. The relay
                // switches to the normal idle timeout only after the protocol handshake.
                client.setSoTimeout(config.getHandshakeTimeoutMillis());
                Semaphore slots = connectionSlots;
                if (slots == null || !slots.tryAcquire()) {
                    rejectAtCapacity(client);
                    client = null;
                    continue;
                }

                final ProxySession session = new ProxySession(
                        this, nextSessionId.incrementAndGet(), client);
                activeSessions.put(session.snapshot().getId(), session);
                activeConnections.incrementAndGet();
                notifySessionOpened(session.snapshot());
                try {
                    workerExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(session);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    finishSession(session, SessionCloseReason.SERVER_STOPPED);
                }
                client = null;
            } catch (SocketException closed) {
                if (running.get()) {
                    running.set(false);
                }
                break;
            } catch (IOException acceptFailure) {
                if (running.get()) {
                    running.set(false);
                }
                break;
            } finally {
                ProxyIo.closeQuietly(client);
            }
        }
    }

    private void rejectAtCapacity(Socket client) {
        rejectedConnections.incrementAndGet();
        ProxySession rejected = new ProxySession(this, nextSessionId.incrementAndGet(), client);
        notifySessionOpened(rejected.snapshot());
        rejected.finish(SessionCloseReason.MAX_CONNECTIONS);
        ProxyIo.closeQuietly(client);
        notifySessionClosed(rejected.snapshot(), SessionCloseReason.MAX_CONNECTIONS);
    }

    private void handleClient(ProxySession session) {
        SessionCloseReason reason = SessionCloseReason.COMPLETED;
        try {
            Socket client = session.getClientSocket();
            PushbackInputStream input = new PushbackInputStream(client.getInputStream(), 1);
            int first = input.read();
            if (first < 0) {
                reason = SessionCloseReason.CLIENT_CLOSED;
            } else {
                input.unread(first);
                if (first == 0x05) {
                    session.setProtocol(ProxyProtocol.SOCKS5);
                    Socks5ProxyHandler.handle(
                            config, session, input, client.getOutputStream());
                } else {
                    session.setProtocol(ProxyProtocol.HTTP);
                    HttpProxyHandler.handle(
                            config, session, input, client.getOutputStream());
                }
            }
        } catch (ProxyFailure failure) {
            reason = failure.getCloseReason();
        } catch (SocketTimeoutException timeout) {
            reason = SessionCloseReason.IDLE_TIMEOUT;
        } catch (EOFException eof) {
            reason = SessionCloseReason.CLIENT_CLOSED;
        } catch (SocketException socketFailure) {
            reason = session.getRequestedCloseReason() == null
                    ? SessionCloseReason.NETWORK_ERROR
                    : session.getRequestedCloseReason();
        } catch (IOException networkFailure) {
            reason = SessionCloseReason.NETWORK_ERROR;
        } catch (RuntimeException unexpected) {
            reason = SessionCloseReason.INTERNAL_ERROR;
        } finally {
            SessionCloseReason requested = session.getRequestedCloseReason();
            finishSession(session, requested == null ? reason : requested);
        }
    }

    private void finishSession(ProxySession session, SessionCloseReason reason) {
        long id = session.snapshot().getId();
        if (!activeSessions.remove(id, session)) {
            return;
        }
        session.closeSockets();
        session.finish(reason);
        activeConnections.decrementAndGet();
        Semaphore slots = connectionSlots;
        if (slots != null) {
            slots.release();
        }
        notifySessionClosed(session.snapshot(), reason);
    }

    private void notifySessionOpened(ProxySessionSnapshot snapshot) {
        try {
            analyticsListener.onSessionOpened(snapshot);
        } catch (RuntimeException ignored) {
            // Analytics must never interrupt proxy traffic.
        }
    }

    private void notifySessionClosed(ProxySessionSnapshot snapshot, SessionCloseReason reason) {
        try {
            analyticsListener.onSessionClosed(snapshot, reason);
        } catch (RuntimeException ignored) {
            // Analytics must never interrupt proxy traffic.
        }
    }

    private void notifyRotation(ProxyRotationEvent event) {
        try {
            analyticsListener.onRotation(event);
        } catch (RuntimeException ignored) {
            // Analytics must never interrupt proxy traffic.
        }
    }

    private static void closeServerSocket(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort close.
        }
    }

    private static void joinThread(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ProxyThreadFactory implements ThreadFactory {
        private final String role;
        private final AtomicLong sequence = new AtomicLong();

        private ProxyThreadFactory(String role) {
            this.role = role;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable, "justproxy-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
