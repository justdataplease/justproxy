package com.justproxy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.justproxy.app.analytics.AnalyticsStore;
import com.justproxy.app.analytics.AnalyticsSummary;
import com.justproxy.app.analytics.ProxySessionRecord;
import com.justproxy.app.analytics.PublicIpChecker;
import com.justproxy.app.analytics.PublicIpObservation;
import com.justproxy.app.control.ControlApiServer;
import com.justproxy.app.proxy.OutboundConnector;
import com.justproxy.app.proxy.ProxyAnalyticsListener;
import com.justproxy.app.proxy.ProxyServer;
import com.justproxy.app.proxy.ProxyServerConfig;
import com.justproxy.app.proxy.ProxySessionSnapshot;
import com.justproxy.app.proxy.ProxyStatsSnapshot;
import com.justproxy.app.proxy.RotationReason;
import com.justproxy.app.proxy.SessionCloseReason;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ProxyService extends Service {
    public static final String ACTION_START = "com.justproxy.app.START";
    public static final String ACTION_STOP = "com.justproxy.app.STOP";
    public static final String ACTION_RESTART = "com.justproxy.app.RESTART";
    public static final String ACTION_ROTATE = "com.justproxy.app.ROTATE";
    public static final String ACTION_REFRESH_IP = "com.justproxy.app.REFRESH_IP";

    private static final String CHANNEL_ID = "proxy_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final long ANALYTICS_REFRESH_INTERVAL_MILLIS = 5_000L;
    private static final AtomicReference<ProxyStatus> STATUS =
            new AtomicReference<>(ProxyStatus.stopped());

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IpCheckGate ipCheckGate = new IpCheckGate();
    private final RunTotals runTotals = new RunTotals();
    private final TrafficCheckpoint trafficCheckpoint = new TrafficCheckpoint();
    private final TimedCache<AnalyticsSummary> analyticsSummaryCache =
            new TimedCache<>(ANALYTICS_REFRESH_INTERVAL_MILLIS);
    private ScheduledExecutorService worker;
    private AppSettings settings;
    private AnalyticsStore analyticsStore;
    private PublicIpChecker publicIpChecker;
    private CellularNetworkManager cellularNetworkManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private ProxyServer proxyServer;
    private ControlApiServer controlApiServer;
    private volatile Network selectedNetwork;
    private volatile boolean desiredRunning;
    private volatile String serviceMessage = "Proxy is off";
    private long startedAtMillis;
    private long nextRotationAtMillis;
    private long ipCheckGeneration;
    private long cellularRequestGeneration;
    private long lastTrafficCheckpointAtMillis;
    private int notificationTick;

    public static ProxyStatus getStatus() {
        return STATUS.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        analyticsStore = new AnalyticsStore(this);
        publicIpChecker = new PublicIpChecker();
        cellularNetworkManager = new CellularNetworkManager(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "JustProxy::ProxyService");
        wakeLock.setReferenceCounted(false);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "justproxy-service");
            thread.setDaemon(true);
            return thread;
        });
        createNotificationChannel();
        worker.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            worker.execute(() -> stopRuntime(true, "Stopped by user"));
        } else if (ACTION_ROTATE.equals(action)) {
            worker.execute(() -> rotateSessions(RotationReason.MANUAL));
        } else if (ACTION_REFRESH_IP.equals(action)) {
            worker.execute(this::checkPublicIp);
        } else if (ACTION_RESTART.equals(action)) {
            startInForeground("Applying new credentials");
            worker.execute(() -> {
                boolean continuingRun = desiredRunning;
                stopRuntime(false, "Restarting");
                startRuntime(!continuingRun);
            });
        } else {
            startInForeground("Starting proxy");
            worker.execute(this::startRuntime);
        }
        return restartModeForAction(action, desiredRunning);
    }

    static int restartModeForAction(String action, boolean running) {
        if (ACTION_STOP.equals(action)) return START_NOT_STICKY;
        if (ACTION_ROTATE.equals(action) || ACTION_REFRESH_IP.equals(action)) {
            return running ? START_STICKY : START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startRuntime() {
        startRuntime(!desiredRunning);
    }

    private void startRuntime(boolean resetRunTotals) {
        if (desiredRunning && isRuntimeHealthy()) return;

        // A failed listener can leave the control API alive. Treat ACTION_START as a retry:
        // close every partial component first so ports, callbacks, and network requests cannot
        // leak into the replacement runtime.
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        stopProxyServer();
        closeControlApi();
        nextRotationAtMillis = 0;

        if (resetRunTotals) {
            runTotals.reset();
            trafficCheckpoint.reset();
            lastTrafficCheckpointAtMillis = System.currentTimeMillis();
            startedAtMillis = System.currentTimeMillis();
        } else if (startedAtMillis == 0) {
            startedAtMillis = System.currentTimeMillis();
        }
        desiredRunning = true;
        serviceMessage = "Starting";
        acquireWakeLock();
        try {
            startControlApi();
        } catch (IOException exception) {
            stopRuntime(true, "Control API port unavailable: " + safeMessage(exception));
            return;
        } catch (RuntimeException exception) {
            stopRuntime(true, "Control API failed: " + safeMessage(exception));
            return;
        }
        if (settings.isCellularOnly()) {
            serviceMessage = "Waiting for a cellular network";
            requestCellularNetwork();
        } else {
            startProxyServer(null);
        }
        updateStatus();
    }

    private boolean isRuntimeHealthy() {
        boolean controlHealthy = controlApiServer != null
                && controlApiServer.isRunning()
                && controlApiServer.getBoundPort() == settings.getPort() + 1;
        if (!controlHealthy) return false;
        if (proxyServer != null && proxyServer.isRunning()) return true;
        // No proxy listener is expected while cellular-only mode is genuinely waiting for a
        // network. A failed cellular listener retains selectedNetwork and is therefore retried.
        return settings.isCellularOnly() && selectedNetwork == null
                && !serviceMessage.startsWith("Proxy failed")
                && !serviceMessage.startsWith("Cellular request failed");
    }

    private void requestCellularNetwork() {
        if (!desiredRunning) return;
        long generation = ++cellularRequestGeneration;
        try {
            cellularNetworkManager.request(new CellularNetworkManager.Listener() {
                @Override public void onCellularAvailable(Network network) {
                    execute(() -> {
                        if (!isCurrentCellularRequest(generation)) return;
                        if (!network.equals(cellularNetworkManager.getCellularNetwork())) return;
                        if (network.equals(selectedNetwork) && proxyServer != null
                                && proxyServer.isRunning()) return;
                        startProxyServer(network);
                    });
                }

                @Override public void onCellularLost() {
                    execute(() -> {
                        if (!isCurrentCellularRequest(generation)) return;
                        stopProxyServer();
                        selectedNetwork = null;
                        serviceMessage = "Cellular network lost - proxy paused";
                        updateStatus();
                    });
                }

                @Override public void onCellularUnavailable() {
                    execute(() -> {
                        if (!isCurrentCellularRequest(generation)) return;
                        stopProxyServer();
                        selectedNetwork = null;
                        serviceMessage = "No cellular network - retrying";
                        updateStatus();
                        scheduleCellularRetry(generation);
                    });
                }
            });
        } catch (RuntimeException exception) {
            if (!isCurrentCellularRequest(generation)) return;
            stopProxyServer();
            selectedNetwork = null;
            serviceMessage = "Cellular request failed: " + safeMessage(exception)
                    + " - retrying";
            updateStatus();
            scheduleCellularRetry(generation);
        }
    }

    private boolean isCurrentCellularRequest(long generation) {
        return desiredRunning && generation == cellularRequestGeneration;
    }

    private void scheduleCellularRetry(long generation) {
        worker.schedule(() -> {
            if (isCurrentCellularRequest(generation)) requestCellularNetwork();
        }, 15, TimeUnit.SECONDS);
    }

    private void releaseCellularNetworkRequest() {
        cellularRequestGeneration++;
        cellularNetworkManager.release();
    }

    private void startProxyServer(Network network) {
        stopProxyServer();
        selectedNetwork = network;
        try {
            AppSettings.Credentials credentials = settings.getCredentials();
            InetAddress bind = InetAddress.getByName(
                    settings.isLanAccessEnabled() ? "0.0.0.0" : "127.0.0.1");
            ProxyServerConfig.Builder builder = ProxyServerConfig
                    .builder(credentials.username, credentials.password)
                    .bindAddress(bind)
                    .port(settings.getPort())
                    .handshakeTimeoutMillis(8_000)
                    .idleTimeoutMillis(settings.getIdleTimeoutSeconds() * 1_000)
                    .connectTimeoutMillis(12_000)
                    .maxConnections(settings.getMaxConnections())
                    .maxHttpHeaderBytes(32 * 1024)
                    .allowPrivateDestinations(settings.isPrivateDestinationAccessEnabled());
            if (network != null) {
                builder.outboundConnector(new AndroidNetworkConnector(network));
            }
            proxyServer = new ProxyServer(builder.build(), new AnalyticsListener());
            proxyServer.start();
            serviceMessage = network == null
                    ? "Running on the system default network"
                    : "Running with cellular-only egress";
            int rotationMinutes = settings.getRotationMinutes();
            nextRotationAtMillis = rotationMinutes == 0 ? 0
                    : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(rotationMinutes);
            checkPublicIp();
        } catch (Exception exception) {
            stopProxyServer();
            serviceMessage = "Proxy failed: " + safeMessage(exception);
        }
        updateStatus();
    }

    private void startControlApi() throws IOException {
        closeControlApi();
        AppSettings.Credentials credentials = settings.getCredentials();
        InetAddress bind = InetAddress.getByName(
                settings.isLanAccessEnabled() ? "0.0.0.0" : "127.0.0.1");
        controlApiServer = new ControlApiServer(bind, settings.getPort() + 1,
                credentials.password, new ApiHandler(),
                message -> serviceMessage = "Control API: " + message);
        controlApiServer.start();
    }

    private void rotateSessions(RotationReason reason) {
        if (proxyServer == null || !proxyServer.isRunning()) {
            serviceMessage = "Cannot reconnect while the proxy is paused";
            updateStatus();
            return;
        }
        int closed = proxyServer.rotateSessions(reason);
        serviceMessage = "Reconnected " + closed
                + " session(s); checking whether the public IP changed";
        int rotationMinutes = settings.getRotationMinutes();
        nextRotationAtMillis = rotationMinutes == 0 ? 0
                : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(rotationMinutes);
        worker.schedule(this::checkPublicIp, 2, TimeUnit.SECONDS);
        updateStatus();
    }

    private void checkPublicIp() {
        if (!desiredRunning) {
            ipCheckGate.cancelPending();
            return;
        }
        Network network = settings.isCellularOnly() ? selectedNetwork : null;
        if (settings.isCellularOnly() && network == null) {
            serviceMessage = "Public IP not checked: cellular network unavailable";
            updateStatus();
            return;
        }
        if (!ipCheckGate.startOrQueue()) return;
        long generation = ipCheckGeneration;
        String previous = getAnalyticsSummary().getCurrentPublicIp();
        try {
            publicIpChecker.checkAsync(network, new PublicIpChecker.Callback() {
                @Override public void onSuccess(String publicIp) {
                    execute(() -> finishPublicIpCheck(
                            generation, previous, publicIp, null));
                }

                @Override public void onError(IOException error) {
                    execute(() -> finishPublicIpCheck(
                            generation, previous, null, error));
                }
            });
        } catch (RuntimeException exception) {
            serviceMessage = "Public IP check failed";
            if (!finishAndRetryPendingPublicIpCheck()) updateStatus();
        }
    }

    private void finishPublicIpCheck(long generation, String previous, String publicIp,
                                     IOException error) {
        boolean current = desiredRunning && generation == ipCheckGeneration;
        if (current && error == null) {
            analyticsStore.recordPublicIp(publicIp);
            analyticsSummaryCache.invalidate();
            if (previous == null) {
                serviceMessage = "Public IP detected";
            } else if (previous.equals(publicIp)) {
                serviceMessage = "Public IP unchanged";
            } else {
                serviceMessage = "Public IP changed: " + previous + " -> " + publicIp;
            }
        } else if (current) {
            serviceMessage = "Public IP check failed: " + safeMessage(error);
        }
        if (!finishAndRetryPendingPublicIpCheck() && current) updateStatus();
    }

    private boolean finishAndRetryPendingPublicIpCheck() {
        boolean retry = ipCheckGate.finishAndShouldRetry(desiredRunning);
        if (retry) checkPublicIp();
        return retry;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (desiredRunning && now - lastTrafficCheckpointAtMillis >= 5_000L) {
            checkpointTraffic(false);
        }
        if (desiredRunning && proxyServer != null && proxyServer.isRunning()) {
            if (nextRotationAtMillis > 0 && now >= nextRotationAtMillis) {
                rotateSessions(RotationReason.SCHEDULED);
            }
            ProxyStatsSnapshot stats = proxyServer.getStatsSnapshot();
            long capMiB = settings.getDataCapMiB();
            if (capMiB > 0 && runTotals.trafficBytesWith(stats)
                    >= capMiB * 1024L * 1024L) {
                stopRuntime(true, "Data cap reached");
                return;
            }
        }
        updateStatus();
        if (wakeLock != null && desiredRunning && !wakeLock.isHeld()) acquireWakeLock();
        if (++notificationTick % 5 == 0 && desiredRunning) updateNotification();
    }

    private void updateStatus() {
        AnalyticsSummary summary = getAnalyticsSummary();
        ProxyStatsSnapshot stats = proxyServer == null ? null : proxyServer.getStatsSnapshot();
        ProxyStatus.State state = !desiredRunning ? ProxyStatus.State.STOPPED
                : stats != null && stats.isRunning() ? ProxyStatus.State.RUNNING
                : serviceMessage.startsWith("Proxy failed")
                || serviceMessage.startsWith("Control API")
                || serviceMessage.startsWith("Cellular request failed")
                ? ProxyStatus.State.ERROR : ProxyStatus.State.PAUSED;
        String address = settings.isLanAccessEnabled() ? "0.0.0.0" : "127.0.0.1";
        String egress = settings.isCellularOnly() ? "Cellular only" : "System default";
        STATUS.set(new ProxyStatus(state, serviceMessage, address, settings.getPort(), egress,
                summary.getCurrentPublicIp(),
                runTotals.uploadedBytesWith(stats),
                runTotals.downloadedBytesWith(stats),
                summary.getTodayUploadedBytes(), summary.getTodayDownloadedBytes(),
                summary.getLifetimeUploadedBytes(), summary.getLifetimeDownloadedBytes(),
                stats == null ? 0 : stats.getActiveConnections(),
                summary.getLifetimeSessionCount(), summary.getPublicIpChangeCount(),
                desiredRunning ? startedAtMillis : 0, nextRotationAtMillis));
    }

    private AnalyticsSummary getAnalyticsSummary() {
        return analyticsSummaryCache.get(
                System.currentTimeMillis(), analyticsStore::getSummary);
    }

    private void stopRuntime(boolean stopService, String message) {
        desiredRunning = false;
        ipCheckGate.cancelPending();
        nextRotationAtMillis = 0;
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        stopProxyServer();
        closeControlApi();
        releaseWakeLock();
        serviceMessage = message;
        updateStatus();
        updateNotification();
        if (stopService) {
            mainHandler.post(() -> {
                if (desiredRunning) return;
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                stopSelf();
            });
        }
    }

    private void stopProxyServer() {
        ipCheckGeneration++;
        ProxyServer server = proxyServer;
        if (server != null) checkpointTraffic(true);
        proxyServer = null;
        if (server != null) {
            server.close();
            runTotals.add(server.getStatsSnapshot());
            checkpointTraffic(true);
        }
    }

    private void checkpointTraffic(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastTrafficCheckpointAtMillis < 5_000L) return;
        ProxyStatsSnapshot current = proxyServer == null
                ? null : proxyServer.getStatsSnapshot();
        TrafficCheckpoint.Delta delta = trafficCheckpoint.pending(
                runTotals.uploadedBytesWith(current),
                runTotals.downloadedBytesWith(current));
        try {
            analyticsStore.recordTrafficDelta(
                    delta.uploadedBytes, delta.downloadedBytes, now);
            trafficCheckpoint.commit(delta);
            analyticsSummaryCache.invalidate();
            lastTrafficCheckpointAtMillis = now;
        } catch (RuntimeException ignored) {
            // Keep the old baseline so the complete delta is retried at the next checkpoint.
        }
    }

    private void closeControlApi() {
        ControlApiServer server = controlApiServer;
        controlApiServer = null;
        if (server != null) server.close();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60_000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void execute(Runnable runnable) {
        try { worker.execute(runnable); } catch (RejectedExecutionException ignored) {}
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        desiredRunning = false;
        releaseCellularNetworkRequest();
        stopProxyServer();
        closeControlApi();
        releaseWakeLock();
        if ("Starting".equals(serviceMessage)) serviceMessage = "Service stopped";
        updateStatus();
        publicIpChecker.close();
        analyticsStore.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "JustProxy service", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when your phone is accepting proxy connections");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void startInForeground(String message) {
        serviceMessage = message;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (desiredRunning) notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        ProxyStatus status = STATUS.get();
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_justproxy)
                .setColor(getColor(R.color.teal_dark))
                .setContentTitle("JustProxy - " + status.state.name().toLowerCase(Locale.ROOT))
                .setContentText(serviceMessage)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(desiredRunning)
                .setShowWhen(false);
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_justproxy, "Reconnect",
                servicePendingIntent(ACTION_ROTATE, 11)).build());
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_justproxy, "Stop",
                servicePendingIntent(ACTION_STOP, 12)).build());
        return builder.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, ProxyService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private final class AnalyticsListener implements ProxyAnalyticsListener {
        @Override
        public void onSessionClosed(ProxySessionSnapshot session, SessionCloseReason reason) {
            long ended = session.getEndedAtEpochMillis() > 0
                    ? session.getEndedAtEpochMillis() : System.currentTimeMillis();
            String host = session.getTargetHost();
            String target = host == null ? "" : formatTarget(host, session.getTargetPort());
            analyticsStore.recordCompletedSessionMetadata(
                    session.getStartedAtEpochMillis(), ended,
                    session.getClientAddress(),
                    session.getProtocol() == null ? "UNKNOWN" : session.getProtocol().name(),
                    target, session.getBytesUploaded(), session.getBytesDownloaded(),
                    reason == null ? "UNKNOWN" : reason.name());
            analyticsSummaryCache.invalidate();
            if (desiredRunning) execute(() -> checkpointTraffic(true));
        }
    }

    private final class ApiHandler implements ControlApiServer.Handler {
        @Override public String statusJson() {
            ProxyStatus status = STATUS.get();
            try {
                JSONObject json = new JSONObject();
                json.put("version", "v1");
                json.put("state", status.state.name().toLowerCase(Locale.ROOT));
                json.put("message", status.message);
                json.put("listen_host", status.listenAddress);
                json.put("proxy_port", status.port);
                json.put("control_port", status.port + 1);
                json.put("egress", status.egress);
                json.put("public_ip", valueOrNull(status.publicIp));
                json.put("active_connections", status.activeConnections);
                json.put("started_at_ms", status.startedAtMillis == 0
                        ? JSONObject.NULL : status.startedAtMillis);
                json.put("next_rotation_at_ms", status.nextRotationAtMillis == 0
                        ? JSONObject.NULL : status.nextRotationAtMillis);
                json.put("rotation_guarantees_ip_change", false);
                return json.toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }

        @Override public String metricsJson() {
            ProxyStatus status = STATUS.get();
            try {
                return new JSONObject()
                        .put("run_uploaded_bytes", status.runUploadedBytes)
                        .put("run_downloaded_bytes", status.runDownloadedBytes)
                        .put("today_uploaded_bytes", status.todayUploadedBytes)
                        .put("today_downloaded_bytes", status.todayDownloadedBytes)
                        .put("lifetime_uploaded_bytes", status.lifetimeUploadedBytes)
                        .put("lifetime_downloaded_bytes", status.lifetimeDownloadedBytes)
                        .put("lifetime_sessions", status.lifetimeSessions)
                        .put("ip_change_count", status.ipChangeCount)
                        .toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }

        @Override public String ipHistoryJson() {
            try {
                JSONArray items = new JSONArray();
                for (PublicIpObservation observation : analyticsStore.getPublicIpHistory(100)) {
                    items.put(new JSONObject()
                            .put("ip", observation.getIpAddress())
                            .put("observed_at_ms", observation.getObservedAtMillis())
                            .put("changed", observation.isChangedFromPrevious()));
                }
                return new JSONObject().put("items", items).toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }

        @Override public String sessionsJson() {
            try {
                JSONArray items = new JSONArray();
                for (ProxySessionRecord session : analyticsStore.getRecentSessions(100)) {
                    items.put(new JSONObject()
                            .put("started_at_ms", session.getStartedAtMillis())
                            .put("ended_at_ms", session.getEndedAtMillis())
                            .put("client", session.getClientAddress())
                            .put("protocol", session.getProtocol())
                            .put("target", session.getTarget())
                            .put("uploaded_bytes", session.getUploadedBytes())
                            .put("downloaded_bytes", session.getDownloadedBytes())
                            .put("result", session.getResult()));
                }
                return new JSONObject().put("items", items).toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }

        @Override public String rotateJson() {
            ProxyStatus status = STATUS.get();
            boolean accepted = status.state == ProxyStatus.State.RUNNING;
            if (accepted) execute(() -> rotateSessions(RotationReason.MANUAL));
            try {
                return new JSONObject()
                        .put("accepted", accepted)
                        .put("action", accepted ? "sessions_reconnect_scheduled" : "none")
                        .put("previous_ip", valueOrNull(status.publicIp))
                        .put("ip_changed", JSONObject.NULL)
                        .put("manual_carrier_reset_required", true)
                        .put("message", accepted
                                ? "Sessions will reconnect and the public IP will be checked"
                                : "Proxy is not running")
                        .toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }

        @Override public String checkIpJson() {
            boolean accepted = desiredRunning;
            if (accepted) execute(ProxyService.this::checkPublicIp);
            try {
                return new JSONObject()
                        .put("accepted", accepted)
                        .put("message", accepted
                                ? "Public IP check scheduled" : "Proxy is not running")
                        .toString();
            } catch (JSONException exception) {
                return jsonFailure();
            }
        }
    }

    private static Object valueOrNull(String value) {
        return value == null || value.isEmpty() || "-".equals(value) ? JSONObject.NULL : value;
    }

    private static String jsonFailure() {
        return new JSONObject().toString();
    }

    private static String formatTarget(String host, int port) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    /** Totals for one user-requested run, including every replaced ProxyServer instance. */
    static final class RunTotals {
        private long uploadedBytes;
        private long downloadedBytes;
        private long totalConnections;

        synchronized void reset() {
            uploadedBytes = 0;
            downloadedBytes = 0;
            totalConnections = 0;
        }

        synchronized void add(ProxyStatsSnapshot stats) {
            if (stats == null) return;
            add(stats.getBytesUploaded(), stats.getBytesDownloaded(), stats.getTotalConnections());
        }

        synchronized void add(long uploaded, long downloaded, long connections) {
            uploadedBytes = saturatedAdd(uploadedBytes, uploaded);
            downloadedBytes = saturatedAdd(downloadedBytes, downloaded);
            totalConnections = saturatedAdd(totalConnections, connections);
        }

        synchronized long uploadedBytesWith(ProxyStatsSnapshot current) {
            return saturatedAdd(uploadedBytes,
                    current == null ? 0 : current.getBytesUploaded());
        }

        synchronized long downloadedBytesWith(ProxyStatsSnapshot current) {
            return saturatedAdd(downloadedBytes,
                    current == null ? 0 : current.getBytesDownloaded());
        }

        synchronized long totalConnectionsWith(ProxyStatsSnapshot current) {
            return saturatedAdd(totalConnections,
                    current == null ? 0 : current.getTotalConnections());
        }

        synchronized long trafficBytesWith(ProxyStatsSnapshot current) {
            long currentUploaded = current == null ? 0 : current.getBytesUploaded();
            long currentDownloaded = current == null ? 0 : current.getBytesDownloaded();
            return saturatedAdd(
                    saturatedAdd(uploadedBytes, currentUploaded),
                    saturatedAdd(downloadedBytes, currentDownloaded));
        }

        private static long saturatedAdd(long left, long right) {
            if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
                return Long.MAX_VALUE;
            }
            return left + right;
        }
    }

    /** Thread-safe read-through cache that can be refreshed immediately after analytics writes. */
    static final class TimedCache<T> {
        private final long maxAgeMillis;
        private T value;
        private long loadedAtMillis;
        private boolean invalidated = true;

        TimedCache(long maxAgeMillis) {
            if (maxAgeMillis <= 0) {
                throw new IllegalArgumentException("Cache age must be positive");
            }
            this.maxAgeMillis = maxAgeMillis;
        }

        synchronized T get(long nowMillis, Supplier<T> loader) {
            if (loader == null) throw new IllegalArgumentException("Loader is required");
            boolean clockMovedBackwards = value != null && nowMillis < loadedAtMillis;
            boolean expired = value != null && nowMillis - loadedAtMillis >= maxAgeMillis;
            if (value == null || invalidated || clockMovedBackwards || expired) {
                T loaded = loader.get();
                if (loaded == null) throw new IllegalStateException("Loader returned null");
                value = loaded;
                loadedAtMillis = nowMillis;
                invalidated = false;
            }
            return value;
        }

        synchronized void invalidate() {
            invalidated = true;
        }
    }

    /** Database checkpoint baseline; callers commit only after the transaction succeeds. */
    static final class TrafficCheckpoint {
        private long uploadedBaseline;
        private long downloadedBaseline;

        synchronized Delta pending(long totalUploaded, long totalDownloaded) {
            if (totalUploaded < 0 || totalDownloaded < 0) {
                throw new IllegalArgumentException("Traffic totals cannot be negative");
            }
            return new Delta(
                    totalUploaded,
                    totalDownloaded,
                    nonNegativeDifference(totalUploaded, uploadedBaseline),
                    nonNegativeDifference(totalDownloaded, downloadedBaseline));
        }

        synchronized void commit(Delta delta) {
            uploadedBaseline = delta.totalUploaded;
            downloadedBaseline = delta.totalDownloaded;
        }

        synchronized void reset() {
            uploadedBaseline = 0;
            downloadedBaseline = 0;
        }

        private static long nonNegativeDifference(long value, long baseline) {
            return value >= baseline ? value - baseline : 0;
        }

        static final class Delta {
            final long totalUploaded;
            final long totalDownloaded;
            final long uploadedBytes;
            final long downloadedBytes;

            Delta(long totalUploaded, long totalDownloaded,
                  long uploadedBytes, long downloadedBytes) {
                this.totalUploaded = totalUploaded;
                this.totalDownloaded = totalDownloaded;
                this.uploadedBytes = uploadedBytes;
                this.downloadedBytes = downloadedBytes;
            }
        }
    }

    /** One in-flight IP lookup plus one coalesced follow-up request. */
    static final class IpCheckGate {
        private boolean inFlight;
        private boolean pending;

        synchronized boolean startOrQueue() {
            if (inFlight) {
                pending = true;
                return false;
            }
            inFlight = true;
            pending = false;
            return true;
        }

        synchronized boolean finishAndShouldRetry(boolean running) {
            inFlight = false;
            boolean retry = pending && running;
            pending = false;
            return retry;
        }

        synchronized void cancelPending() {
            pending = false;
        }
    }

    private static final class AndroidNetworkConnector implements OutboundConnector {
        private final Network network;

        AndroidNetworkConnector(Network network) {
            this.network = network;
        }

        @Override public InetAddress[] resolve(String host) throws IOException {
            return network.getAllByName(host);
        }

        @Override public Socket connect(InetAddress address, int port, int timeout)
                throws IOException {
            Socket socket = network.getSocketFactory().createSocket();
            boolean connected = false;
            try {
                socket.connect(new InetSocketAddress(address, port), timeout);
                connected = true;
                return socket;
            } finally {
                if (!connected) try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
