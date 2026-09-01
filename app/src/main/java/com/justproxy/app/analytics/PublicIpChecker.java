package com.justproxy.app.analytics;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronously resolves the public IP through the selected Android network. */
public final class PublicIpChecker implements AutoCloseable {
    public interface Callback {
        void onSuccess(String publicIp);

        void onError(IOException error);
    }

    private static final String ENDPOINT = "https://api.ipify.org";
    private static final int CONNECT_TIMEOUT_MILLIS = 4_000;
    private static final int READ_TIMEOUT_MILLIS = 4_000;
    private static final int MAX_RESPONSE_BYTES = 128;

    private final ExecutorService worker;
    private final Executor callbackExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Callbacks run on the background worker. UI callers should pass a main-thread executor. */
    public PublicIpChecker() {
        this(createWorker(), Runnable::run);
    }

    /**
     * Creates a checker with explicit callback dispatch, for example
     * {@code command -> activity.runOnUiThread(command)}.
     */
    public PublicIpChecker(Executor callbackExecutor) {
        this(createWorker(), callbackExecutor);
    }

    PublicIpChecker(ExecutorService worker, Executor callbackExecutor) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    public Future<?> checkAsync(Callback callback) {
        return checkAsync(null, callback);
    }

    /** Uses {@link Network#openConnection(URL)} when a network is supplied. */
    public Future<?> checkAsync(Network network, Callback callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            throw new IllegalStateException("PublicIpChecker is closed");
        }
        try {
            return worker.submit(() -> {
                try {
                    String publicIp = query(network);
                    dispatch(() -> callback.onSuccess(publicIp));
                } catch (IOException exception) {
                    dispatch(() -> callback.onError(exception));
                } catch (RuntimeException exception) {
                    dispatch(() -> callback.onError(
                            new IOException("Public-IP check failed", exception)));
                }
            });
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("PublicIpChecker is closed", exception);
        }
    }

    private void dispatch(Runnable callback) {
        try {
            callbackExecutor.execute(callback);
        } catch (RuntimeException ignored) {
            // Callback executors can reject work while their Activity/Service is shutting down.
        }
    }

    private static String query(Network network) throws IOException {
        URL endpoint = URI.create(ENDPOINT).toURL();
        URLConnection rawConnection = network == null
                ? endpoint.openConnection()
                : network.openConnection(endpoint);
        if (!(rawConnection instanceof HttpURLConnection)) {
            throw new IOException("Unexpected connection type");
        }

        HttpURLConnection connection = (HttpURLConnection) rawConnection;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("User-Agent", "JustProxy/1");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Public-IP service returned HTTP " + status);
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("Public-IP response was too large");
            }

            String response;
            try (InputStream input = connection.getInputStream()) {
                response = readLimited(input);
            }
            try {
                return IpAddressValidator.normalize(response);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Public-IP service returned an invalid address", exception);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(48);
        byte[] buffer = new byte[64];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Public-IP response was too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private static ExecutorService createWorker() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "justproxy-public-ip");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    /** Cancels queued work and interrupts an in-flight request. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
        }
    }
}
