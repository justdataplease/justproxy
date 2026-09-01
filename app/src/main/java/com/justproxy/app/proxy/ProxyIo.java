package com.justproxy.app.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

final class ProxyIo {
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private ProxyIo() {}

    static int readUnsignedByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("unexpected end of stream");
        }
        return value;
    }

    static byte[] readFully(InputStream input, int count) throws IOException {
        byte[] result = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(result, offset, count - offset);
            if (read < 0) {
                throw new EOFException("unexpected end of stream");
            }
            offset += read;
        }
        return result;
    }

    static Socket connect(
            ProxyServerConfig config,
            ProxySession session,
            String host,
            InetAddress literalAddress,
            int port)
            throws IOException {
        InetAddress[] addresses = DestinationPolicy.resolveAllowed(
                config, host, literalAddress, port);
        IOException lastFailure = null;
        for (InetAddress address : addresses) {
            Socket socket = null;
            try {
                socket = config.getOutboundConnector().connect(
                        address, port, config.getConnectTimeoutMillis());
                if (socket == null || !socket.isConnected()) {
                    throw new IOException("outbound connector returned an unconnected socket");
                }
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(config.getIdleTimeoutMillis());
                session.attachUpstream(socket);
                session.setResolvedTarget(address.getHostAddress());
                return socket;
            } catch (IOException failure) {
                lastFailure = failure;
                closeQuietly(socket);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("could not connect to target");
    }

    static void relayBidirectional(
            final ProxySession session,
            final InputStream clientInput,
            final OutputStream clientOutput,
            final Socket upstream,
            final int idleTimeoutMillis)
            throws IOException {
        final InputStream upstreamInput = upstream.getInputStream();
        final OutputStream upstreamOutput = upstream.getOutputStream();
        final Socket client = session.getClientSocket();
        int pollTimeout = Math.min(idleTimeoutMillis, 1_000);
        client.setSoTimeout(pollTimeout);
        upstream.setSoTimeout(pollTimeout);

        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicReference<IOException> firstFailure = new AtomicReference<IOException>();
        final CountDownLatch directionsFinished = new CountDownLatch(2);

        Thread downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runRelayDirection(
                        upstreamInput,
                        clientOutput,
                        false,
                        session,
                        idleTimeoutMillis,
                        aborted,
                        firstFailure,
                        directionsFinished,
                        client,
                        upstream,
                        client);
            }
        }, "justproxy-download-" + session.snapshot().getId());
        downloadThread.setDaemon(true);
        downloadThread.start();

        runRelayDirection(
                clientInput,
                upstreamOutput,
                true,
                session,
                idleTimeoutMillis,
                aborted,
                firstFailure,
                directionsFinished,
                client,
                upstream,
                upstream);

        try {
            directionsFinished.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            aborted.set(true);
            closeQuietly(client);
            closeQuietly(upstream);
            throw new IOException("interrupted while stopping relay", interrupted);
        }

        IOException failure = firstFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    private static void runRelayDirection(
            InputStream input,
            OutputStream output,
            boolean upload,
            ProxySession session,
            int idleTimeoutMillis,
            AtomicBoolean aborted,
            AtomicReference<IOException> firstFailure,
            CountDownLatch directionsFinished,
            Socket client,
            Socket upstream,
            Socket outputSocket) {
        try {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (!aborted.get()) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (SocketTimeoutException timeout) {
                    if (session.isIdle(System.currentTimeMillis(), idleTimeoutMillis)) {
                        throw timeout;
                    }
                    continue;
                }
                if (read < 0) {
                    // A clean EOF is a TCP half-close, not a failure. Propagate FIN only in this
                    // direction and keep relaying the reverse stream until its own EOF.
                    outputSocket.shutdownOutput();
                    break;
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                if (upload) {
                    session.addUploaded(read);
                } else {
                    session.addDownloaded(read);
                }
            }
        } catch (IOException ioFailure) {
            if (aborted.compareAndSet(false, true)) {
                firstFailure.set(ioFailure);
                closeQuietly(client);
                closeQuietly(upstream);
            }
        } finally {
            directionsFinished.countDown();
        }
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort close.
        }
    }
}
