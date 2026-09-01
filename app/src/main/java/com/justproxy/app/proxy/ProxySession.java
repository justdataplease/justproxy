package com.justproxy.app.proxy;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ProxySession {
    private final ProxyServer owner;
    private final long id;
    private final Socket clientSocket;
    private final String clientAddress;
    private final long startedAtEpochMillis;
    private final AtomicLong lastActivityAtEpochMillis;
    private final AtomicLong bytesUploaded = new AtomicLong();
    private final AtomicLong bytesDownloaded = new AtomicLong();
    private final AtomicLong lastPublishedAtEpochMillis = new AtomicLong();
    private final AtomicReference<SessionCloseReason> requestedCloseReason =
            new AtomicReference<SessionCloseReason>();
    private volatile ProxyProtocol protocol = ProxyProtocol.UNKNOWN;
    private volatile String targetHost;
    private volatile int targetPort;
    private volatile String resolvedTargetAddress;
    private volatile boolean authenticated;
    private volatile long endedAtEpochMillis;
    private volatile SessionCloseReason closeReason;
    private volatile Socket upstreamSocket;

    ProxySession(ProxyServer owner, long id, Socket clientSocket) {
        this.owner = owner;
        this.id = id;
        this.clientSocket = clientSocket;
        this.clientAddress = String.valueOf(clientSocket.getRemoteSocketAddress());
        this.startedAtEpochMillis = System.currentTimeMillis();
        this.lastActivityAtEpochMillis = new AtomicLong(startedAtEpochMillis);
    }

    Socket getClientSocket() {
        return clientSocket;
    }

    void setProtocol(ProxyProtocol protocol) {
        this.protocol = protocol;
        touch();
        owner.publishSessionUpdated(this, true);
    }

    void setTarget(String host, int port) {
        targetHost = host;
        targetPort = port;
        touch();
        owner.publishSessionUpdated(this, true);
    }

    void setResolvedTarget(String hostAddress) {
        resolvedTargetAddress = hostAddress;
        touch();
        owner.publishSessionUpdated(this, true);
    }

    void setAuthenticated() {
        authenticated = true;
        touch();
        owner.publishSessionUpdated(this, true);
    }

    void attachUpstream(Socket socket) {
        upstreamSocket = socket;
    }

    void addUploaded(long count) {
        if (count <= 0) {
            return;
        }
        bytesUploaded.addAndGet(count);
        owner.addUploaded(count);
        touch();
        owner.publishSessionUpdated(this, false);
    }

    void addDownloaded(long count) {
        if (count <= 0) {
            return;
        }
        bytesDownloaded.addAndGet(count);
        owner.addDownloaded(count);
        touch();
        owner.publishSessionUpdated(this, false);
    }

    void touch() {
        lastActivityAtEpochMillis.set(System.currentTimeMillis());
    }

    boolean isIdle(long nowEpochMillis, int idleTimeoutMillis) {
        return nowEpochMillis - lastActivityAtEpochMillis.get() >= idleTimeoutMillis;
    }

    boolean shouldPublish(long nowEpochMillis) {
        long previous = lastPublishedAtEpochMillis.get();
        return nowEpochMillis - previous >= 250L
                && lastPublishedAtEpochMillis.compareAndSet(previous, nowEpochMillis);
    }

    void requestClose(SessionCloseReason reason) {
        requestedCloseReason.compareAndSet(null, reason);
        closeSockets();
    }

    void closeSockets() {
        ProxyIo.closeQuietly(clientSocket);
        ProxyIo.closeQuietly(upstreamSocket);
    }

    SessionCloseReason getRequestedCloseReason() {
        return requestedCloseReason.get();
    }

    void finish(SessionCloseReason reason) {
        closeReason = reason;
        endedAtEpochMillis = System.currentTimeMillis();
        lastActivityAtEpochMillis.set(endedAtEpochMillis);
    }

    ProxySessionSnapshot snapshot() {
        return new ProxySessionSnapshot(
                id,
                clientAddress,
                protocol,
                targetHost,
                targetPort,
                resolvedTargetAddress,
                authenticated,
                startedAtEpochMillis,
                lastActivityAtEpochMillis.get(),
                endedAtEpochMillis,
                bytesUploaded.get(),
                bytesDownloaded.get(),
                closeReason);
    }

}
