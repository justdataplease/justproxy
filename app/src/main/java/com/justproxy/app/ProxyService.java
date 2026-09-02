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
import com.justproxy.app.shizuku.MobileDataCommandResult;
import com.justproxy.app.shizuku.ShizukuMobileDataController;
import com.justproxy.app.proxy.OutboundConnector;
import com.justproxy.app.proxy.ProxyAnalyticsListener;
import com.justproxy.app.proxy.ProxyServer;
import com.justproxy.app.proxy.ProxyServerConfig;
import com.justproxy.app.proxy.ProxySessionSnapshot;
import com.justproxy.app.proxy.ProxyStatsSnapshot;
import com.justproxy.app.proxy.RotationReason;
import com.justproxy.app.proxy.SessionCloseReason;
import com.justproxy.app.wireguard.WireGuardGatewayStats;
import com.justproxy.app.wireguard.WireGuardGatewayStatus;
import com.justproxy.app.wireguard.WireGuardNativeGateway;
import com.justproxy.app.wireguard.WireGuardPeerRecord;
import com.justproxy.app.wireguard.WireGuardPeerStore;

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
    public static final String ACTION_ROTATE_IP = "com.justproxy.app.ROTATE_IP";
    public static final String ACTION_RECOVER_MOBILE_DATA =
            "com.justproxy.app.RECOVER_MOBILE_DATA";
    public static final String ACTION_REFRESH_IP = "com.justproxy.app.REFRESH_IP";
    public static final String ACTION_RELOAD_WIREGUARD_PEER =
            "com.justproxy.app.RELOAD_WIREGUARD_PEER";

    private static final String CHANNEL_ID = "proxy_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final long ANALYTICS_REFRESH_INTERVAL_MILLIS = 5_000L;
    private static final int MAX_AUTOMATIC_WIREGUARD_RESTARTS = 3;
    private static final AtomicReference<ProxyStatus> STATUS =
            new AtomicReference<>(ProxyStatus.stopped());

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IpCheckGate ipCheckGate = new IpCheckGate();
    private final RunTotals runTotals = new RunTotals();
    private final RunTotals wireGuardRunTotals = new RunTotals();
    private final TrafficCheckpoint trafficCheckpoint = new TrafficCheckpoint();
    private final TimedCache<AnalyticsSummary> analyticsSummaryCache =
            new TimedCache<>(ANALYTICS_REFRESH_INTERVAL_MILLIS);
    private final Object workerLifecycleLock = new Object();
    private ScheduledExecutorService worker;
    private IpRotationAdmissionCoordinator ipRotationAdmissionCoordinator;
    private boolean workerStopping;
    private AppSettings settings;
    private AnalyticsStore analyticsStore;
    private PublicIpChecker publicIpChecker;
    private CellularNetworkManager cellularNetworkManager;
    private ShizukuMobileDataController mobileDataController;
    private volatile ShizukuMobileDataController.Availability mobileDataAvailability;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private ProxyServer proxyServer;
    private WireGuardNativeGateway wireGuardGateway;
    private WireGuardPeerStore wireGuardPeerStore;
    private ControlApiServer controlApiServer;
    private volatile Network selectedNetwork;
    private volatile boolean desiredRunning;
    private volatile String serviceMessage = "Proxy is off";
    private volatile String wireGuardMessage = "WireGuard gateway is disabled";
    private volatile boolean wireGuardProfileConfigured;
    private long lastWireGuardHandshakeMillis;
    private WireGuardGatewayStats lastWireGuardSnapshot = WireGuardGatewayStats.stopped();
    private int automaticWireGuardRestarts;
    private long nextWireGuardRestartAtMillis;
    private boolean wireGuardRecoveryPending;
    private long startedAtMillis;
    private long nextRotationAtMillis;
    private long nextIpRotationAtMillis;
    private long nextIpRotationElapsedRealtime;
    private long lastIpRotationAttemptAtMillis;
    private long ipRotationGeneration;
    private String ipRotationPreviousIp;
    private boolean ipRotationInProgress;
    private boolean ipRotationAwaitingIpCheck;
    private boolean stopPendingAfterIpRotation;
    private boolean restartPendingAfterIpRotation;
    private IpRotationStatus.State ipRotationState = IpRotationStatus.State.DISABLED;
    private IpRotationStatus.Outcome lastIpRotationOutcome =
            IpRotationStatus.Outcome.NEVER;
    private String ipRotationMessage = "Automatic IP rotation is disabled";
    private long ipCheckGeneration;
    private long cellularRequestGeneration;
    private long lastTrafficCheckpointAtMillis;
    private int notificationTick;

    enum WireGuardRetryDecision {
        NONE,
        WAIT,
        ATTEMPT,
        EXHAUSTED
    }

    public static ProxyStatus getStatus() {
        return STATUS.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        wireGuardPeerStore = new WireGuardPeerStore(this);
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
        ipRotationAdmissionCoordinator = new IpRotationAdmissionCoordinator(task -> {
            synchronized (workerLifecycleLock) {
                if (workerStopping) {
                    throw new RejectedExecutionException("JustProxy is stopping");
                }
                worker.execute(task);
            }
        });
        mobileDataController = new ShizukuMobileDataController(this,
                availability -> execute(() -> onMobileDataAvailabilityChanged(availability)));
        mobileDataAvailability = mobileDataController.getAvailability();
        mobileDataController.start();
        createNotificationChannel();
        worker.scheduleAtFixedRate(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            worker.execute(this::requestStopRuntime);
        } else if (ACTION_ROTATE.equals(action)) {
            worker.execute(() -> rotateSessions(RotationReason.MANUAL));
        } else if (ACTION_ROTATE_IP.equals(action)) {
            execute(() -> requestIpRotation(false));
        } else if (ACTION_RECOVER_MOBILE_DATA.equals(action)) {
            worker.execute(() -> {
                if (mobileDataController.isRecoveryRequired()
                        && !ipRotationInProgress) {
                    beginMobileDataRecovery("Checking mobile-data recovery");
                } else {
                    updateIdleIpRotationStatus();
                    updateStatus();
                }
            });
        } else if (ACTION_REFRESH_IP.equals(action)) {
            worker.execute(this::checkPublicIp);
        } else if (ACTION_RELOAD_WIREGUARD_PEER.equals(action)) {
            worker.execute(this::reloadWireGuardPeer);
        } else if (ACTION_RESTART.equals(action)) {
            startInForeground("Applying new credentials");
            worker.execute(this::requestRestartRuntime);
        } else {
            startInForeground("Starting proxy");
            worker.execute(this::startRuntime);
        }
        return restartModeForAction(action, desiredRunning);
    }

    static int restartModeForAction(String action, boolean running) {
        if (ACTION_STOP.equals(action)) return START_NOT_STICKY;
        if (ACTION_ROTATE.equals(action) || ACTION_ROTATE_IP.equals(action)
                || ACTION_RECOVER_MOBILE_DATA.equals(action)
                || ACTION_REFRESH_IP.equals(action)
                || ACTION_RELOAD_WIREGUARD_PEER.equals(action)) {
            return running ? START_STICKY : START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startRuntime() {
        startRuntime(!desiredRunning);
    }

    private void startRuntime(boolean resetRunTotals) {
        if (ipRotationInProgress) return;
        if (desiredRunning && isRuntimeHealthy()) return;

        // A failed listener can leave the control API alive. Treat ACTION_START as a retry:
        // close every partial component first so ports, callbacks, and network requests cannot
        // leak into the replacement runtime.
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        stopDataPlanes();
        closeControlApi();
        nextRotationAtMillis = 0;
        nextIpRotationAtMillis = 0;
        nextIpRotationElapsedRealtime = 0;

        if (resetRunTotals) {
            runTotals.reset();
            wireGuardRunTotals.reset();
            trafficCheckpoint.reset();
            lastTrafficCheckpointAtMillis = System.currentTimeMillis();
            startedAtMillis = System.currentTimeMillis();
            automaticWireGuardRestarts = 0;
            nextWireGuardRestartAtMillis = 0;
            lastIpRotationAttemptAtMillis = 0;
            lastIpRotationOutcome = IpRotationStatus.Outcome.NEVER;
        } else if (startedAtMillis == 0) {
            startedAtMillis = System.currentTimeMillis();
        }
        desiredRunning = true;
        stopPendingAfterIpRotation = false;
        restartPendingAfterIpRotation = false;
        serviceMessage = "Starting";
        updateIdleIpRotationStatus();
        refreshWireGuardProfilePresence("Waiting to start WireGuard");
        acquireWakeLock();
        if (mobileDataController.isRecoveryRequired()) {
            beginMobileDataRecovery("Checking mobile data before startup");
        } else {
            continueStartupAfterMobileDataRecovery();
        }
        updateStatus();
    }

    private void continueStartupAfterMobileDataRecovery() {
        if (!desiredRunning) return;
        boolean controlHealthy = controlApiServer != null
                && controlApiServer.isRunning()
                && controlApiServer.getBoundPort() == settings.getPort() + 1;
        if (!controlHealthy) {
            try {
                startControlApi();
            } catch (IOException exception) {
                stopRuntime(true,
                        "Control API port unavailable: " + safeMessage(exception));
                return;
            } catch (RuntimeException exception) {
                stopRuntime(true, "Control API failed: " + safeMessage(exception));
                return;
            }
        }
        startConfiguredNetworkPath();
    }

    private void startConfiguredNetworkPath() {
        if (!desiredRunning) return;
        if (settings.isCellularOnly()) {
            serviceMessage = "Waiting for a cellular network";
            requestCellularNetwork();
        } else {
            startDataPlanes(null);
        }
    }

    private boolean isRuntimeHealthy() {
        boolean controlHealthy = controlApiServer != null
                && controlApiServer.isRunning()
                && controlApiServer.getBoundPort() == settings.getPort() + 1;
        if (!controlHealthy) return false;
        if (configuredDataPlanesHealthy()) return true;
        // No proxy listener is expected while cellular-only mode is genuinely waiting for a
        // network. A failed cellular listener retains selectedNetwork and is therefore retried.
        return settings.isCellularOnly() && selectedNetwork == null
                && !serviceMessage.startsWith("Data plane failed")
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
                        if (network.equals(selectedNetwork)
                                && configuredDataPlanesHealthy()) return;
                        startDataPlanes(network);
                    });
                }

                @Override public void onCellularLost() {
                    execute(() -> {
                        if (!isCurrentCellularRequest(generation)) return;
                        stopDataPlanes();
                        selectedNetwork = null;
                        serviceMessage = "Cellular network lost - JustProxy paused";
                        setWireGuardWaitingMessage();
                        updateStatus();
                    });
                }

                @Override public void onCellularUnavailable() {
                    execute(() -> {
                        if (!isCurrentCellularRequest(generation)) return;
                        stopDataPlanes();
                        selectedNetwork = null;
                        serviceMessage = "No cellular network - retrying";
                        setWireGuardWaitingMessage();
                        updateStatus();
                        scheduleCellularRetry(generation);
                    });
                }
            });
        } catch (RuntimeException exception) {
            if (!isCurrentCellularRequest(generation)) return;
            stopDataPlanes();
            selectedNetwork = null;
            serviceMessage = "Cellular request failed: " + safeMessage(exception)
                    + " - retrying";
            setWireGuardWaitingMessage();
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

    private void startDataPlanes(Network network) {
        stopDataPlanes();
        selectedNetwork = network;
        automaticWireGuardRestarts = 0;
        nextWireGuardRestartAtMillis = 0;
        wireGuardRecoveryPending = false;
        boolean started = false;
        String failure = null;

        if (settings.isLegacyProxyEnabled()) {
            try {
                startLegacyProxyServer(network);
                started = true;
            } catch (Exception exception) {
                failure = "Legacy proxy: " + safeMessage(exception);
                stopProxyServer();
            }
        }

        if (settings.isWireGuardEnabled()) {
            try {
                started |= startWireGuardGateway(network);
            } catch (Exception exception) {
                failure = appendFailure(failure,
                        "WireGuard: " + safeMessage(exception));
                stopWireGuardGateway();
                wireGuardMessage = "WireGuard failed: " + safeMessage(exception);
                wireGuardRecoveryPending = wireGuardProfileConfigured;
            }
        } else {
            wireGuardProfileConfigured = false;
            wireGuardMessage = "WireGuard gateway is disabled";
        }

        if (started) {
            serviceMessage = dataPlaneRunningMessage(network);
            if (failure != null) serviceMessage += "  |  " + failure;
            resetRotationDeadline();
            ensureIpRotationDeadline();
            if (ipRotationAwaitingIpCheck) {
                ipRotationState = IpRotationStatus.State.CHECKING_IP;
                ipRotationMessage = "Cellular returned; checking the public IP";
            }
            checkPublicIp();
        } else if (settings.isWireGuardEnabled() && !wireGuardProfileConfigured
                && failure == null) {
            serviceMessage = "Create and export a WireGuard computer profile";
        } else {
            serviceMessage = "Data plane failed"
                    + (failure == null ? "" : ": " + failure);
        }
        updateStatus();
    }

    private void startLegacyProxyServer(Network network) throws IOException {
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
        ProxyServer server = new ProxyServer(builder.build(), new AnalyticsListener());
        server.start();
        proxyServer = server;
    }

    private boolean startWireGuardGateway(Network network) {
        java.util.Optional<WireGuardPeerRecord> stored = wireGuardPeerStore.load();
        wireGuardProfileConfigured = stored.isPresent();
        if (!stored.isPresent()) {
            wireGuardMessage = "Create a computer profile to start WireGuard";
            return false;
        }
        WireGuardPeerRecord peer = stored.get();
        long networkHandle = network == null ? 0 : network.getNetworkHandle();
        WireGuardNativeGateway.Config config = new WireGuardNativeGateway.Config(
                peer.getServerPrivateKey(),
                peer.getClientPublicKey(),
                settings.getWireGuardPort(),
                networkHandle,
                settings.isCellularOnly());
        wireGuardGateway = WireGuardNativeGateway.start(config);
        lastWireGuardSnapshot = WireGuardGatewayStats.stopped();
        wireGuardRecoveryPending = false;
        wireGuardMessage = wireGuardListeningMessage();
        return true;
    }

    private String wireGuardListeningMessage() {
        return "Listening for the computer on UDP " + settings.getWireGuardPort();
    }

    private String dataPlaneRunningMessage(Network network) {
        return network == null
                ? "Running on the system default network"
                : "Running with cellular-only egress";
    }

    private void resetRotationDeadline() {
        int rotationMinutes = settings.getRotationMinutes();
        nextRotationAtMillis = rotationMinutes == 0 ? 0
                : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(rotationMinutes);
    }

    private void ensureIpRotationDeadline() {
        if (!settings.isShizukuIpRotationEnabled()) {
            nextIpRotationAtMillis = 0;
            nextIpRotationElapsedRealtime = 0;
            if (!ipRotationInProgress && !mobileDataController.isRecoveryRequired()) {
                updateIdleIpRotationStatus();
            }
            return;
        }
        if (nextIpRotationElapsedRealtime != 0) return;
        resetIpRotationDeadline();
    }

    private void resetIpRotationDeadline() {
        if (!settings.isShizukuIpRotationEnabled() || !desiredRunning) {
            nextIpRotationAtMillis = 0;
            nextIpRotationElapsedRealtime = 0;
            return;
        }
        long delay = TimeUnit.MINUTES.toMillis(
                settings.getShizukuIpRotationIntervalMinutes());
        nextIpRotationAtMillis = System.currentTimeMillis() + delay;
        nextIpRotationElapsedRealtime = android.os.SystemClock.elapsedRealtime() + delay;
    }

    private void refreshWireGuardProfilePresence(String configuredMessage) {
        if (!settings.isWireGuardEnabled()) {
            wireGuardProfileConfigured = false;
            wireGuardMessage = "WireGuard gateway is disabled";
            return;
        }
        try {
            wireGuardProfileConfigured = wireGuardPeerStore.load().isPresent();
            wireGuardMessage = wireGuardProfileConfigured
                    ? configuredMessage : "Create a computer profile to start WireGuard";
        } catch (RuntimeException exception) {
            wireGuardProfileConfigured = false;
            wireGuardMessage = "WireGuard failed: peer storage: "
                    + safeMessage(exception);
        }
    }

    private void setWireGuardWaitingMessage() {
        if (!settings.isWireGuardEnabled()) {
            wireGuardMessage = "WireGuard gateway is disabled";
        } else if (wireGuardMessage.startsWith("WireGuard failed")) {
            // Preserve the actionable storage/native error.
        } else if (wireGuardProfileConfigured) {
            wireGuardMessage = "Waiting for a cellular network";
        } else {
            wireGuardMessage = "Create a computer profile to start WireGuard";
        }
    }

    private boolean configuredDataPlanesHealthy() {
        if (settings.isLegacyProxyEnabled()
                && (proxyServer == null || !proxyServer.isRunning())) {
            return false;
        }
        if (settings.isWireGuardEnabled() && wireGuardProfileConfigured
                && !currentWireGuardStats().isRunning()) {
            return false;
        }
        return settings.isLegacyProxyEnabled()
                || settings.isWireGuardEnabled() && !wireGuardProfileConfigured
                || wireGuardGateway != null;
    }

    private boolean anyDataPlaneRunning() {
        return proxyServer != null && proxyServer.isRunning()
                || currentWireGuardStats().isRunning();
    }

    private static String appendFailure(String existing, String next) {
        return existing == null ? next : existing + "; " + next;
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
        if (!anyDataPlaneRunning()) {
            serviceMessage = "Cannot reconnect while JustProxy is paused";
            updateStatus();
            return;
        }
        int closed = proxyServer == null ? 0 : proxyServer.rotateSessions(reason);
        WireGuardGatewayStats wireGuardStats = currentWireGuardStats();
        int wireGuardFlows = wireGuardStats.getActiveFlows();
        boolean wireGuardReconnectFailed = false;
        if (wireGuardGateway != null) {
            if (reason == RotationReason.MANUAL) {
                automaticWireGuardRestarts = 0;
                nextWireGuardRestartAtMillis = 0;
            }
            stopWireGuardGateway();
            try {
                wireGuardReconnectFailed = !startWireGuardGateway(selectedNetwork);
            } catch (RuntimeException exception) {
                wireGuardMessage = "WireGuard reconnect failed: " + safeMessage(exception);
                wireGuardRecoveryPending = wireGuardProfileConfigured;
                wireGuardReconnectFailed = true;
            }
        }
        if (wireGuardReconnectFailed && !isLegacyProxyRunning()) {
            serviceMessage = wireGuardProfileConfigured
                    ? "Data plane failed: " + wireGuardMessage
                    : "Create and export a WireGuard computer profile";
            nextRotationAtMillis = 0;
        } else {
            serviceMessage = "Reconnected " + (closed + wireGuardFlows)
                    + " connection(s); checking whether the public IP changed";
            resetRotationDeadline();
            worker.schedule(this::checkPublicIp, 2, TimeUnit.SECONDS);
        }
        updateStatus();
    }

    private void requestStopRuntime() {
        if (ipRotationInProgress || mobileDataController.isRecoveryRequired()) {
            stopPendingAfterIpRotation = true;
            restartPendingAfterIpRotation = false;
            serviceMessage = "Stopping after mobile data is restored";
            if (!ipRotationInProgress && mobileDataController.isRecoveryRequired()) {
                beginMobileDataRecovery("Checking mobile data before stopping");
            }
            updateStatus();
            return;
        }
        stopRuntime(true, "Stopped by user");
    }

    private void requestRestartRuntime() {
        if (ipRotationInProgress || mobileDataController.isRecoveryRequired()) {
            restartPendingAfterIpRotation = true;
            stopPendingAfterIpRotation = false;
            serviceMessage = "Restarting after mobile data is restored";
            if (!ipRotationInProgress && mobileDataController.isRecoveryRequired()) {
                beginMobileDataRecovery("Checking mobile data before restarting");
            }
            updateStatus();
            return;
        }
        boolean continuingRun = desiredRunning;
        stopRuntime(false, "Restarting");
        startRuntime(!continuingRun);
    }

    private void requestIpRotation(boolean scheduled) {
        if (scheduled && !settings.isShizukuIpRotationEnabled()) return;
        if (ipRotationInProgress) {
            ipRotationMessage = "An IP rotation is already in progress";
            updateStatus();
            return;
        }
        if (!desiredRunning || !anyDataPlaneRunning()) {
            ipRotationMessage = "Start JustProxy and wait for cellular before rotating the IP";
            updateStatus();
            return;
        }
        if (!settings.isCellularOnly()) {
            ipRotationState = IpRotationStatus.State.UNSUPPORTED;
            ipRotationMessage = "Automatic IP rotation requires cellular-only egress";
            if (scheduled) resetIpRotationDeadline();
            updateStatus();
            return;
        }
        if (mobileDataController.isRecoveryRequired()) {
            beginMobileDataRecovery("Checking a previous mobile-data cycle");
            return;
        }
        ShizukuMobileDataController.Availability availability = mobileDataAvailability;
        if (availability == null || !availability.isReady()) {
            updateIdleIpRotationStatus();
            serviceMessage = "IP rotation unavailable: " + ipRotationMessage;
            if (scheduled) resetIpRotationDeadline();
            updateStatus();
            return;
        }

        ipRotationInProgress = true;
        ipRotationAwaitingIpCheck = false;
        long generation = ++ipRotationGeneration;
        lastIpRotationAttemptAtMillis = System.currentTimeMillis();
        ipRotationPreviousIp = getAnalyticsSummary().getCurrentPublicIp();
        ipRotationState = IpRotationStatus.State.TURNING_DATA_OFF;
        int downSeconds = settings.getShizukuDataOffSeconds();
        ipRotationMessage = "Cycling mobile data off for " + downSeconds
                + (downSeconds == 1 ? " second" : " seconds");
        serviceMessage = ipRotationMessage;
        nextIpRotationAtMillis = 0;
        nextIpRotationElapsedRealtime = 0;
        checkpointTraffic(true);
        stopDataPlanes();
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        updateStatus();

        mobileDataController.cycleAsync(downSeconds * 1_000,
                new ShizukuMobileDataController.OperationCallback() {
                    @Override public void onResult(MobileDataCommandResult result) {
                        execute(() -> finishMobileDataCycle(generation, result));
                    }

                    @Override public void onUnavailable(
                            ShizukuMobileDataController.Availability availability) {
                        execute(() -> failMobileDataOperation(generation,
                                availability == null
                                        ? "Shizuku became unavailable"
                                        : availability.getMessage()));
                    }

                    @Override public void onError(Throwable error) {
                        execute(() -> failMobileDataOperation(
                                generation, safeMessage(error)));
                    }
                });
    }

    private IpRotationAdmissionCoordinator.Decision admitIpRotationOnWorker() {
        ShizukuMobileDataController.Availability availability =
                mobileDataAvailability;
        IpRotationAdmissionCoordinator.Decision decision =
                IpRotationAdmissionCoordinator.decide(
                        new IpRotationAdmissionCoordinator.Preconditions(
                                desiredRunning && anyDataPlaneRunning(),
                                mobileDataController.isRecoveryRequired(),
                                settings.isCellularOnly(),
                                availability != null && availability.isReady(),
                                ipRotationInProgress,
                                ipRotationState));
        if (!decision.isAccepted()) return decision;

        requestIpRotation(false);
        return ipRotationInProgress
                ? decision
                : IpRotationAdmissionCoordinator.Decision.rejected(
                        IpRotationAdmissionCoordinator.REASON_SERVICE_UNAVAILABLE);
    }

    private void finishMobileDataCycle(long generation,
                                       MobileDataCommandResult result) {
        if (generation != ipRotationGeneration) return;
        if (result != null && result.isSuccess() && result.isRestoreSucceeded()
                && !mobileDataController.isRecoveryRequired()) {
            if (finishPendingLifecycleAction()) return;
            ipRotationAwaitingIpCheck = true;
            ipRotationState = IpRotationStatus.State.WAITING_FOR_CELLULAR;
            ipRotationMessage = "Mobile data restored; waiting for cellular";
            serviceMessage = ipRotationMessage;
            requestCellularNetwork();
            worker.schedule(() -> timeoutIpRotation(generation),
                    60, TimeUnit.SECONDS);
            updateStatus();
            return;
        }
        String message = result == null
                ? "Mobile-data cycle returned no result" : result.getMessage();
        failMobileDataOperation(generation, message);
    }

    private void failMobileDataOperation(long generation, String message) {
        if (generation != ipRotationGeneration) return;
        if (mobileDataController.isRecoveryRequired()) {
            ipRotationMessage = "Re-enable required: " + message;
            beginMobileDataRecovery(ipRotationMessage);
            return;
        }
        completeFailedIpRotation(message, true);
    }

    private void beginMobileDataRestore(String message) {
        if (ipRotationInProgress && !mobileDataController.isRecoveryRequired()) return;
        ipRotationInProgress = true;
        ipRotationAwaitingIpCheck = false;
        long generation = ++ipRotationGeneration;
        ipRotationState = IpRotationStatus.State.TURNING_DATA_ON;
        ipRotationMessage = message;
        serviceMessage = message;
        updateStatus();
        mobileDataController.restoreAsync(
                new ShizukuMobileDataController.OperationCallback() {
                    @Override public void onResult(MobileDataCommandResult result) {
                        execute(() -> finishMobileDataRestore(generation, result));
                    }

                    @Override public void onUnavailable(
                            ShizukuMobileDataController.Availability availability) {
                        execute(() -> failMobileDataRestore(generation,
                                availability == null
                                        ? "Shizuku is unavailable"
                                        : availability.getMessage()));
                    }

                    @Override public void onError(Throwable error) {
                        execute(() -> failMobileDataRestore(
                                generation, safeMessage(error)));
                    }
                });
    }

    private void beginMobileDataRecovery(String message) {
        ipRotationInProgress = true;
        ipRotationAwaitingIpCheck = false;
        long generation = ++ipRotationGeneration;
        ipRotationState = IpRotationStatus.State.TURNING_DATA_ON;
        ipRotationMessage = message;
        serviceMessage = message;
        updateStatus();
        mobileDataController.reconcileRecoveryAsync(
                new ShizukuMobileDataController.OperationCallback() {
                    @Override public void onResult(MobileDataCommandResult result) {
                        execute(() -> finishMobileDataRestore(generation, result));
                    }

                    @Override public void onUnavailable(
                            ShizukuMobileDataController.Availability availability) {
                        execute(() -> continueRecoveryAfterReconcile(generation,
                                availability == null
                                        ? "Shizuku is unavailable"
                                        : availability.getMessage()));
                    }

                    @Override public void onError(Throwable error) {
                        execute(() -> continueRecoveryAfterReconcile(
                                generation, safeMessage(error)));
                    }
                });
    }

    private void continueRecoveryAfterReconcile(long generation,
                                                String message) {
        if (generation != ipRotationGeneration) return;
        ShizukuMobileDataController.Availability availability =
                mobileDataController.getAvailability();
        mobileDataAvailability = availability;
        if (availability != null && availability.isReady()) {
            beginMobileDataRestore(message + "; attempting Shizuku restore");
        } else {
            failMobileDataRestore(generation, message
                    + ". Turn mobile data on manually, then tap Retry recovery.");
        }
    }

    private void finishMobileDataRestore(long generation,
                                         MobileDataCommandResult result) {
        if (generation != ipRotationGeneration) return;
        if (result != null && result.isSuccess() && result.isRestoreSucceeded()
                && !mobileDataController.isRecoveryRequired()) {
            ipRotationInProgress = false;
            lastIpRotationOutcome = IpRotationStatus.Outcome.FAILED;
            ipRotationState = IpRotationStatus.State.READY;
            ipRotationMessage = "Mobile data restored";
            if (finishPendingLifecycleAction()) return;
            if (desiredRunning) {
                serviceMessage = "Mobile data restored; waiting for cellular";
                continueStartupAfterMobileDataRecovery();
                resetIpRotationDeadline();
            }
            updateStatus();
            return;
        }
        failMobileDataRestore(generation, result == null
                ? "Mobile-data restore returned no result" : result.getMessage());
    }

    private void failMobileDataRestore(long generation, String message) {
        if (generation != ipRotationGeneration) return;
        ipRotationInProgress = false;
        ipRotationAwaitingIpCheck = false;
        nextIpRotationAtMillis = 0;
        nextIpRotationElapsedRealtime = 0;
        lastIpRotationOutcome = IpRotationStatus.Outcome.FAILED;
        ipRotationState = IpRotationStatus.State.ERROR;
        ipRotationMessage = "Mobile data may be off. Turn it on manually. " + message;
        serviceMessage = ipRotationMessage;
        updateStatus();
        updateNotification();
    }

    private boolean finishPendingLifecycleAction() {
        ipRotationInProgress = false;
        ipRotationAwaitingIpCheck = false;
        if (stopPendingAfterIpRotation) {
            stopPendingAfterIpRotation = false;
            restartPendingAfterIpRotation = false;
            stopRuntime(true, "Stopped by user");
            return true;
        }
        if (restartPendingAfterIpRotation) {
            restartPendingAfterIpRotation = false;
            stopPendingAfterIpRotation = false;
            boolean continuingRun = desiredRunning;
            stopRuntime(false, "Restarting");
            startRuntime(!continuingRun);
            return true;
        }
        return false;
    }

    private void timeoutIpRotation(long generation) {
        if (generation != ipRotationGeneration
                || !ipRotationInProgress || !ipRotationAwaitingIpCheck) return;
        completeFailedIpRotation(
                "Cellular did not return in time; JustProxy will keep retrying", true);
    }

    private void completeFailedIpRotation(String message, boolean resumeNetwork) {
        ipRotationInProgress = false;
        ipRotationAwaitingIpCheck = false;
        lastIpRotationOutcome = IpRotationStatus.Outcome.FAILED;
        ipRotationState = IpRotationStatus.State.ERROR;
        ipRotationMessage = message;
        serviceMessage = "IP rotation failed: " + message;
        if (mobileDataController.isRecoveryRequired()) {
            nextIpRotationAtMillis = 0;
            nextIpRotationElapsedRealtime = 0;
        } else {
            if (finishPendingLifecycleAction()) return;
            resetIpRotationDeadline();
            if (resumeNetwork && desiredRunning && selectedNetwork == null) {
                startConfiguredNetworkPath();
            }
        }
        updateStatus();
    }

    private void onMobileDataAvailabilityChanged(
            ShizukuMobileDataController.Availability availability) {
        mobileDataAvailability = availability;
        if (mobileDataController.isRecoveryRequired()
                && availability != null && availability.isReady()
                && !ipRotationInProgress) {
            beginMobileDataRecovery("Checking mobile-data recovery");
            return;
        }
        if (!ipRotationInProgress) updateIdleIpRotationStatus();
        updateStatus();
    }

    private void updateIdleIpRotationStatus() {
        if (mobileDataController != null && mobileDataController.isRecoveryRequired()) {
            ipRotationState = IpRotationStatus.State.ERROR;
            ipRotationMessage = "Mobile data may be off; recovery is required";
            return;
        }
        boolean automaticEnabled = settings.isShizukuIpRotationEnabled();
        if (!settings.isCellularOnly()) {
            ipRotationState = IpRotationStatus.State.UNSUPPORTED;
            ipRotationMessage = "Automatic IP rotation requires cellular-only egress";
            return;
        }
        ShizukuMobileDataController.Availability availability = mobileDataAvailability;
        if (availability == null) {
            ipRotationState = IpRotationStatus.State.NOT_RUNNING;
            ipRotationMessage = "Start Shizuku to enable automatic IP rotation";
            return;
        }
        ipRotationMessage = (automaticEnabled ? "" : "Automatic schedule disabled. ")
                + availability.getMessage();
        switch (availability.getState()) {
            case READY:
                ipRotationState = IpRotationStatus.State.READY;
                break;
            case PERMISSION_REQUIRED:
                ipRotationState = IpRotationStatus.State.PERMISSION_REQUIRED;
                break;
            case PERMISSION_DENIED:
                ipRotationState = IpRotationStatus.State.PERMISSION_DENIED;
                break;
            case BINDING:
                ipRotationState = IpRotationStatus.State.BINDING;
                break;
            case UNSUPPORTED:
                ipRotationState = IpRotationStatus.State.UNSUPPORTED;
                break;
            case ERROR:
                ipRotationState = IpRotationStatus.State.ERROR;
                break;
            case STOPPED:
            case WAITING_FOR_SHIZUKU:
            default:
                ipRotationState = IpRotationStatus.State.NOT_RUNNING;
                break;
        }
    }

    private void reloadWireGuardPeer() {
        automaticWireGuardRestarts = 0;
        nextWireGuardRestartAtMillis = 0;
        wireGuardRecoveryPending = false;
        stopWireGuardGateway();
        try {
            wireGuardProfileConfigured = wireGuardPeerStore.load().isPresent();
        } catch (RuntimeException exception) {
            wireGuardProfileConfigured = false;
            wireGuardMessage = "WireGuard failed: peer storage: "
                    + safeMessage(exception);
            if (desiredRunning && !isLegacyProxyRunning()) {
                serviceMessage = "Data plane failed: WireGuard peer storage";
                nextRotationAtMillis = 0;
            }
            updateStatus();
            stopServiceIfIdle();
            return;
        }
        if (!desiredRunning || !settings.isWireGuardEnabled()) {
            wireGuardMessage = !settings.isWireGuardEnabled()
                    ? "WireGuard gateway is disabled"
                    : wireGuardProfileConfigured
                    ? "WireGuard gateway is stopped" : "Create a computer profile";
            updateStatus();
            stopServiceIfIdle();
            return;
        }
        if (settings.isCellularOnly() && selectedNetwork == null) {
            wireGuardMessage = wireGuardProfileConfigured
                    ? "Waiting for a cellular network" : "Create a computer profile";
            if (!wireGuardProfileConfigured && !isLegacyProxyRunning()) {
                serviceMessage = "Create and export a WireGuard computer profile";
                nextRotationAtMillis = 0;
            }
            updateStatus();
            return;
        }
        try {
            boolean started = startWireGuardGateway(selectedNetwork);
            if (started) {
                serviceMessage = dataPlaneRunningMessage(selectedNetwork);
                resetRotationDeadline();
                checkPublicIp();
            } else if (isLegacyProxyRunning()) {
                serviceMessage = "Legacy proxy running; create a WireGuard computer profile";
            } else {
                serviceMessage = "Create and export a WireGuard computer profile";
                nextRotationAtMillis = 0;
            }
        } catch (RuntimeException exception) {
            wireGuardMessage = "WireGuard failed: " + safeMessage(exception);
            wireGuardRecoveryPending = wireGuardProfileConfigured;
            if (!isLegacyProxyRunning()) {
                serviceMessage = "Data plane failed: WireGuard: " + safeMessage(exception);
                nextRotationAtMillis = 0;
            }
        }
        updateStatus();
    }

    private boolean isLegacyProxyRunning() {
        return proxyServer != null && proxyServer.isRunning();
    }

    private void stopServiceIfIdle() {
        if (desiredRunning) return;
        mainHandler.post(() -> {
            if (!desiredRunning) stopSelf();
        });
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
        boolean completesIpRotation = current && ipRotationAwaitingIpCheck;
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
        if (completesIpRotation) completeIpRotationCheck(publicIp, error);
        if (!finishAndRetryPendingPublicIpCheck() && current) updateStatus();
    }

    private void completeIpRotationCheck(String publicIp, IOException error) {
        ipRotationInProgress = false;
        ipRotationAwaitingIpCheck = false;
        if (error != null) {
            lastIpRotationOutcome = IpRotationStatus.Outcome.FAILED;
            ipRotationMessage = "Mobile data was restored, but the public IP check failed";
        } else if (ipRotationPreviousIp != null
                && !ipRotationPreviousIp.equals(publicIp)) {
            lastIpRotationOutcome = IpRotationStatus.Outcome.CHANGED;
            ipRotationMessage = "Public IP changed: " + ipRotationPreviousIp
                    + " -> " + publicIp;
        } else if (ipRotationPreviousIp == null) {
            lastIpRotationOutcome = IpRotationStatus.Outcome.UNCHANGED;
            ipRotationMessage = "Public IP detected; no earlier value was available to compare";
        } else {
            lastIpRotationOutcome = IpRotationStatus.Outcome.UNCHANGED;
            ipRotationMessage = "Carrier returned the same public IP";
        }
        ipRotationState = IpRotationStatus.State.READY;
        resetIpRotationDeadline();
        if (!finishPendingLifecycleAction()) updateStatus();
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
        if (desiredRunning) maybeRecoverWireGuard(now);
        if (desiredRunning && anyDataPlaneRunning()) {
            ProxyStatsSnapshot stats = currentProxyStats();
            WireGuardGatewayStats wireGuardStats = currentWireGuardStats();
            long capMiB = settings.getDataCapMiB();
            if (capMiB > 0 && runTotals.trafficBytesWith(stats, wireGuardStats)
                    >= capMiB * 1024L * 1024L) {
                stopRuntime(true, "Data cap reached");
                return;
            }
            if (nextIpRotationElapsedRealtime > 0
                    && android.os.SystemClock.elapsedRealtime()
                    >= nextIpRotationElapsedRealtime) {
                requestIpRotation(true);
                return;
            }
            if (nextRotationAtMillis > 0 && now >= nextRotationAtMillis) {
                rotateSessions(RotationReason.SCHEDULED);
            }
        }
        updateStatus();
        if (wakeLock != null && desiredRunning && !wakeLock.isHeld()) acquireWakeLock();
        if (++notificationTick % 5 == 0 && desiredRunning) updateNotification();
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException | LinkageError error) {
            serviceMessage = "Service monitor failed: " + safeMessage(error);
            try {
                updateStatus();
            } catch (RuntimeException | LinkageError ignored) {
                // Keep the fixed-rate task alive so a transient native or storage error
                // cannot silently disable data-cap enforcement and network recovery.
            }
        }
    }

    private void updateStatus() {
        AnalyticsSummary summary = getAnalyticsSummary();
        ProxyStatsSnapshot stats = currentProxyStats();
        WireGuardGatewayStats wireGuardStats = currentWireGuardStats();
        if (wireGuardStats.getLastHandshakeMillis() > 0) {
            lastWireGuardHandshakeMillis = wireGuardStats.getLastHandshakeMillis();
        }
        boolean proxyRunning = stats != null && stats.isRunning();
        boolean wireGuardRunning = wireGuardStats.isRunning();
        ProxyStatus.State state = !desiredRunning ? ProxyStatus.State.STOPPED
                : proxyRunning || wireGuardRunning ? ProxyStatus.State.RUNNING
                : serviceMessage.startsWith("Data plane failed")
                || serviceMessage.startsWith("Control API")
                || serviceMessage.startsWith("Cellular request failed")
                || serviceMessage.startsWith("Service monitor failed")
                || mobileDataController.isRecoveryRequired()
                || isWireGuardErrorMessage(wireGuardMessage)
                ? ProxyStatus.State.ERROR : ProxyStatus.State.PAUSED;
        String address = settings.isLanAccessEnabled() ? "0.0.0.0" : "127.0.0.1";
        String egress = settings.isCellularOnly() ? "Cellular only" : "System default";
        WireGuardGatewayStatus wireGuardStatus = buildWireGuardStatus(wireGuardStats);
        STATUS.set(new ProxyStatus(state, serviceMessage, address, settings.getPort(), egress,
                summary.getCurrentPublicIp(),
                runTotals.uploadedBytesWith(stats, wireGuardStats),
                runTotals.downloadedBytesWith(stats, wireGuardStats),
                summary.getTodayUploadedBytes(), summary.getTodayDownloadedBytes(),
                summary.getLifetimeUploadedBytes(), summary.getLifetimeDownloadedBytes(),
                saturatedIntAdd(stats == null ? 0 : stats.getActiveConnections(),
                        wireGuardStats.getActiveFlows()),
                summary.getLifetimeSessionCount(), summary.getPublicIpChangeCount(),
                desiredRunning ? startedAtMillis : 0, nextRotationAtMillis,
                wireGuardStatus, buildIpRotationStatus()));
    }

    private IpRotationStatus buildIpRotationStatus() {
        boolean enabled = settings.isShizukuIpRotationEnabled();
        return new IpRotationStatus(
                enabled,
                ipRotationState,
                ipRotationMessage,
                settings.getShizukuIpRotationIntervalMinutes(),
                settings.getShizukuDataOffSeconds(),
                enabled ? nextIpRotationAtMillis : 0,
                lastIpRotationAttemptAtMillis,
                lastIpRotationOutcome,
                mobileDataController != null
                        && mobileDataController.isRecoveryRequired());
    }

    private ProxyStatsSnapshot currentProxyStats() {
        return proxyServer == null ? null : proxyServer.getStatsSnapshot();
    }

    private WireGuardGatewayStats currentWireGuardStats() {
        WireGuardNativeGateway gateway = wireGuardGateway;
        if (gateway == null) return WireGuardGatewayStats.stopped();
        try {
            WireGuardGatewayStats current = gateway.getStats();
            lastWireGuardSnapshot = current;
            wireGuardRecoveryPending = current.hasFatalError() || !current.isRunning();
            wireGuardMessage = wireGuardMessageAfterStats(
                    wireGuardMessage, current, settings.getWireGuardPort());
            if (wireGuardRecoveryPending && !isLegacyProxyRunning()) {
                serviceMessage = "Data plane failed: " + wireGuardMessage;
            }
            return current;
        } catch (RuntimeException exception) {
            wireGuardMessage = "WireGuard status failed: " + safeMessage(exception);
            wireGuardRecoveryPending = true;
            if (!isLegacyProxyRunning()) {
                serviceMessage = "Data plane failed: " + wireGuardMessage;
            }
            return stoppedWireGuardSnapshot(wireGuardMessage);
        }
    }

    private WireGuardGatewayStats stoppedWireGuardSnapshot(String error) {
        WireGuardGatewayStats previous = lastWireGuardSnapshot;
        return new WireGuardGatewayStats(false,
                previous.getUploadedBytes(), previous.getDownloadedBytes(), 0, 0,
                previous.getTotalTcpFlows(), previous.getTotalUdpFlows(),
                previous.getLastHandshakeMillis(), error);
    }

    private void maybeRecoverWireGuard(long now) {
        WireGuardNativeGateway gateway = wireGuardGateway;
        WireGuardGatewayStats current = gateway == null
                ? WireGuardGatewayStats.stopped() : currentWireGuardStats();
        boolean cellularReady = !settings.isCellularOnly() || selectedNetwork != null;
        WireGuardRetryDecision decision = decideWireGuardRetry(
                settings.isWireGuardEnabled(),
                wireGuardProfileConfigured, cellularReady,
                current.isRunning(), wireGuardRecoveryPending,
                automaticWireGuardRestarts, MAX_AUTOMATIC_WIREGUARD_RESTARTS,
                now, nextWireGuardRestartAtMillis);
        if (decision == WireGuardRetryDecision.NONE
                || decision == WireGuardRetryDecision.WAIT) return;
        if (decision == WireGuardRetryDecision.EXHAUSTED) {
            if (gateway != null) stopWireGuardGateway();
            wireGuardRecoveryPending = false;
            wireGuardMessage = "WireGuard failed repeatedly; stop and start JustProxy to retry";
            if (!isLegacyProxyRunning()) {
                serviceMessage = "Data plane failed: " + wireGuardMessage;
            }
            return;
        }

        if (gateway != null) stopWireGuardGateway();
        automaticWireGuardRestarts++;
        long delaySeconds = automaticWireGuardRestarts == 1 ? 5
                : automaticWireGuardRestarts == 2 ? 15 : 60;
        nextWireGuardRestartAtMillis = now + TimeUnit.SECONDS.toMillis(delaySeconds);
        try {
            startWireGuardGateway(selectedNetwork);
            wireGuardMessage = "WireGuard automatically restarted ("
                    + automaticWireGuardRestarts + "/"
                    + MAX_AUTOMATIC_WIREGUARD_RESTARTS + ")";
            serviceMessage = dataPlaneRunningMessage(selectedNetwork);
        } catch (RuntimeException exception) {
            wireGuardMessage = "WireGuard restart failed: " + safeMessage(exception);
            wireGuardRecoveryPending = true;
        }
    }

    static WireGuardRetryDecision decideWireGuardRetry(
            boolean enabled, boolean profileConfigured, boolean cellularReady,
            boolean running, boolean recoveryPending, int attempts, int maximum,
            long nowMillis, long notBeforeMillis) {
        if (!enabled || !profileConfigured || !cellularReady
                || running || !recoveryPending) {
            return WireGuardRetryDecision.NONE;
        }
        if (attempts >= maximum) return WireGuardRetryDecision.EXHAUSTED;
        return nowMillis < notBeforeMillis
                ? WireGuardRetryDecision.WAIT : WireGuardRetryDecision.ATTEMPT;
    }

    static String wireGuardMessageAfterStats(String previous,
                                             WireGuardGatewayStats stats,
                                             int port) {
        if (stats.hasFatalError()) {
            return "WireGuard failed: " + stats.getFatalError();
        }
        if (!stats.isRunning()) {
            return "WireGuard failed: gateway stopped unexpectedly";
        }
        return previous == null || isWireGuardErrorMessage(previous)
                ? "Listening for the computer on UDP " + port : previous;
    }

    private WireGuardGatewayStatus buildWireGuardStatus(
            WireGuardGatewayStats current) {
        if (!settings.isWireGuardEnabled()) {
            return WireGuardGatewayStatus.disabled();
        }
        WireGuardGatewayStatus.State state;
        if (current.isRunning()) {
            state = WireGuardGatewayStatus.State.RUNNING;
        } else if (isWireGuardErrorMessage(wireGuardMessage)) {
            state = WireGuardGatewayStatus.State.ERROR;
        } else if (!wireGuardProfileConfigured) {
            state = WireGuardGatewayStatus.State.NO_PROFILE;
        } else {
            state = WireGuardGatewayStatus.State.WAITING;
        }
        return new WireGuardGatewayStatus(
                state,
                wireGuardMessage,
                settings.getWireGuardPort(),
                wireGuardProfileConfigured ? 1 : 0,
                current.getActiveFlows(),
                wireGuardRunTotals.totalConnectionsWith(null, current),
                wireGuardRunTotals.uploadedBytesWith(null, current),
                wireGuardRunTotals.downloadedBytesWith(null, current),
                Math.max(lastWireGuardHandshakeMillis, current.getLastHandshakeMillis()));
    }

    private AnalyticsSummary getAnalyticsSummary() {
        return analyticsSummaryCache.get(
                System.currentTimeMillis(), analyticsStore::getSummary);
    }

    private void stopRuntime(boolean stopService, String message) {
        desiredRunning = false;
        ipCheckGate.cancelPending();
        nextRotationAtMillis = 0;
        nextIpRotationAtMillis = 0;
        nextIpRotationElapsedRealtime = 0;
        ipRotationInProgress = false;
        ipRotationAwaitingIpCheck = false;
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        stopDataPlanes();
        refreshWireGuardProfilePresence("WireGuard gateway is stopped");
        closeControlApi();
        releaseWakeLock();
        serviceMessage = message;
        updateIdleIpRotationStatus();
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

    private void stopDataPlanes() {
        stopProxyServer();
        stopWireGuardGateway();
    }

    private void stopWireGuardGateway() {
        ipCheckGeneration++;
        WireGuardNativeGateway gateway = wireGuardGateway;
        wireGuardRecoveryPending = false;
        if (gateway == null) return;
        checkpointTraffic(true);
        wireGuardGateway = null;
        WireGuardGatewayStats finalStats = lastWireGuardSnapshot;
        try {
            finalStats = gateway.stopAndGetStats();
        } catch (RuntimeException exception) {
            wireGuardMessage = "WireGuard shutdown failed: "
                    + safeMessage(exception);
        }
        runTotals.add(finalStats);
        wireGuardRunTotals.add(finalStats);
        if (finalStats.getLastHandshakeMillis() > 0) {
            lastWireGuardHandshakeMillis = finalStats.getLastHandshakeMillis();
        }
        lastWireGuardSnapshot = WireGuardGatewayStats.stopped();
        checkpointTraffic(true);
    }

    private void checkpointTraffic(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastTrafficCheckpointAtMillis < 5_000L) return;
        ProxyStatsSnapshot current = currentProxyStats();
        WireGuardGatewayStats wireGuardStats = currentWireGuardStats();
        TrafficCheckpoint.Delta delta = trafficCheckpoint.pending(
                runTotals.uploadedBytesWith(current, wireGuardStats),
                runTotals.downloadedBytesWith(current, wireGuardStats));
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    static boolean isWireGuardErrorMessage(String message) {
        return message != null && (message.startsWith("WireGuard failed")
                || message.startsWith("WireGuard status failed")
                || message.startsWith("WireGuard reconnect failed")
                || message.startsWith("WireGuard restart failed")
                || message.startsWith("WireGuard shutdown failed"));
    }

    private static int saturatedIntAdd(int left, int right) {
        return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
    }

    @Override
    public void onDestroy() {
        desiredRunning = false;
        synchronized (workerLifecycleLock) {
            workerStopping = true;
            try {
                worker.execute(() -> {
                    try {
                        destroyRuntimeOnWorker();
                    } finally {
                        mobileDataController.close();
                        publicIpChecker.close();
                        analyticsStore.close();
                        worker.shutdownNow();
                    }
                });
                worker.shutdown();
            } catch (RejectedExecutionException exception) {
                destroyRuntimeOnWorker();
                mobileDataController.close();
                publicIpChecker.close();
                analyticsStore.close();
                worker.shutdownNow();
            }
        }
        super.onDestroy();
    }

    private void destroyRuntimeOnWorker() {
        ipCheckGate.cancelPending();
        nextRotationAtMillis = 0;
        nextIpRotationAtMillis = 0;
        nextIpRotationElapsedRealtime = 0;
        releaseCellularNetworkRequest();
        selectedNetwork = null;
        stopDataPlanes();
        refreshWireGuardProfilePresence("WireGuard gateway is stopped");
        closeControlApi();
        releaseWakeLock();
        if ("Starting".equals(serviceMessage)) serviceMessage = "Service stopped";
        updateStatus();
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
                .setSmallIcon(R.drawable.ic_justproxy_notification)
                .setColor(getColor(R.color.teal_dark))
                .setContentTitle("JustProxy - " + status.state.name().toLowerCase(Locale.ROOT))
                .setContentText(serviceMessage)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(desiredRunning)
                .setShowWhen(false);
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_justproxy_notification, "Reconnect",
                servicePendingIntent(ACTION_ROTATE, 11)).build());
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_justproxy_notification, "Stop",
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
                json.put("wireguard", new JSONObject()
                        .put("state", status.wireGuard.state.name()
                                .toLowerCase(Locale.ROOT))
                        .put("message", status.wireGuard.message)
                        .put("port", status.wireGuard.port)
                        .put("configured_peers", status.wireGuard.configuredPeers)
                        .put("active_flows", status.wireGuard.activeFlows)
                        .put("total_flows", status.wireGuard.totalFlows)
                        .put("uploaded_bytes", status.wireGuard.uploadedBytes)
                        .put("downloaded_bytes", status.wireGuard.downloadedBytes)
                        .put("last_handshake_ms", status.wireGuard.lastHandshakeMillis == 0
                                ? JSONObject.NULL : status.wireGuard.lastHandshakeMillis));
                json.put("ip_rotation", new JSONObject()
                        .put("enabled", status.ipRotation.enabled)
                        .put("provider", status.ipRotation.provider)
                        .put("state", status.ipRotation.state.name()
                                .toLowerCase(Locale.ROOT))
                        .put("message", status.ipRotation.message)
                        .put("interval_minutes", status.ipRotation.intervalMinutes)
                        .put("data_off_seconds", status.ipRotation.dataOffSeconds)
                        .put("next_at_ms", status.ipRotation.nextAtMillis == 0
                                ? JSONObject.NULL : status.ipRotation.nextAtMillis)
                        .put("last_attempt_at_ms",
                                status.ipRotation.lastAttemptAtMillis == 0
                                        ? JSONObject.NULL
                                        : status.ipRotation.lastAttemptAtMillis)
                        .put("last_outcome", status.ipRotation.lastOutcome.name()
                                .toLowerCase(Locale.ROOT))
                        .put("recovery_required",
                                status.ipRotation.recoveryRequired)
                        .put("guarantees_ip_change",
                                status.ipRotation.guaranteesIpChange));
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
                        .put("wireguard_uploaded_bytes",
                                status.wireGuard.uploadedBytes)
                        .put("wireguard_downloaded_bytes",
                                status.wireGuard.downloadedBytes)
                        .put("wireguard_active_flows",
                                status.wireGuard.activeFlows)
                        .put("wireguard_total_flows",
                                status.wireGuard.totalFlows)
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

        @Override public String rotateIpJson() {
            IpRotationAdmissionCoordinator.Decision decision =
                    ipRotationAdmissionCoordinator.dispatch(
                            ProxyService.this::admitIpRotationOnWorker);
            ProxyStatus status = STATUS.get();
            boolean accepted = decision.isAccepted();
            String reason = decision.getReason();
            boolean recoveryRequired = mobileDataController.isRecoveryRequired();
            String rejectionMessage =
                    IpRotationAdmissionCoordinator.REASON_NOT_RUNNING.equals(reason)
                    ? "JustProxy is not running"
                    : IpRotationAdmissionCoordinator.REASON_RECOVERY_REQUIRED.equals(reason)
                    ? "Mobile-data recovery must finish first"
                    : IpRotationAdmissionCoordinator.REASON_CELLULAR_ONLY_REQUIRED.equals(reason)
                    ? "Cellular-only egress is required"
                    : IpRotationAdmissionCoordinator.REASON_SHIZUKU_NOT_READY.equals(reason)
                    ? "Shizuku mobile-data control is not ready"
                    : IpRotationAdmissionCoordinator.REASON_BUSY.equals(reason)
                    ? "An IP rotation is already pending or running"
                    : IpRotationAdmissionCoordinator.REASON_SERVICE_STOPPING.equals(reason)
                    ? "JustProxy is stopping"
                    : "IP rotation is temporarily unavailable";
            try {
                return new JSONObject()
                        .put("accepted", accepted)
                        .put("action", accepted
                                ? "mobile_data_cycle_scheduled" : "none")
                        .put("previous_ip", valueOrNull(accepted
                                ? ipRotationPreviousIp : status.publicIp))
                        .put("ip_changed", JSONObject.NULL)
                        .put("manual_carrier_reset_required",
                                !accepted && (recoveryRequired
                                        || IpRotationAdmissionCoordinator
                                        .REASON_RECOVERY_REQUIRED.equals(reason)))
                        .put("reason", accepted ? JSONObject.NULL : reason)
                        .put("data_off_seconds",
                                settings.getShizukuDataOffSeconds())
                        .put("guarantees_ip_change", false)
                        .put("message", accepted
                                ? "Mobile data will cycle and the public IP will be checked"
                                : rejectionMessage)
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

        synchronized void add(WireGuardGatewayStats stats) {
            if (stats == null) return;
            add(stats.getUploadedBytes(), stats.getDownloadedBytes(), stats.getTotalFlows());
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

        synchronized long uploadedBytesWith(
                ProxyStatsSnapshot current, WireGuardGatewayStats wireGuard) {
            return saturatedAdd(uploadedBytesWith(current),
                    wireGuard == null ? 0 : wireGuard.getUploadedBytes());
        }

        synchronized long downloadedBytesWith(ProxyStatsSnapshot current) {
            return saturatedAdd(downloadedBytes,
                    current == null ? 0 : current.getBytesDownloaded());
        }

        synchronized long downloadedBytesWith(
                ProxyStatsSnapshot current, WireGuardGatewayStats wireGuard) {
            return saturatedAdd(downloadedBytesWith(current),
                    wireGuard == null ? 0 : wireGuard.getDownloadedBytes());
        }

        synchronized long totalConnectionsWith(ProxyStatsSnapshot current) {
            return saturatedAdd(totalConnections,
                    current == null ? 0 : current.getTotalConnections());
        }

        synchronized long totalConnectionsWith(
                ProxyStatsSnapshot current, WireGuardGatewayStats wireGuard) {
            return saturatedAdd(totalConnectionsWith(current),
                    wireGuard == null ? 0 : wireGuard.getTotalFlows());
        }

        synchronized long trafficBytesWith(ProxyStatsSnapshot current) {
            long currentUploaded = current == null ? 0 : current.getBytesUploaded();
            long currentDownloaded = current == null ? 0 : current.getBytesDownloaded();
            return saturatedAdd(
                    saturatedAdd(uploadedBytes, currentUploaded),
                    saturatedAdd(downloadedBytes, currentDownloaded));
        }

        synchronized long trafficBytesWith(
                ProxyStatsSnapshot current, WireGuardGatewayStats wireGuard) {
            long currentWireGuard = wireGuard == null ? 0 : saturatedAdd(
                    wireGuard.getUploadedBytes(), wireGuard.getDownloadedBytes());
            return saturatedAdd(trafficBytesWith(current), currentWireGuard);
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
            uploadedBaseline = Math.max(uploadedBaseline, delta.totalUploaded);
            downloadedBaseline = Math.max(downloadedBaseline, delta.totalDownloaded);
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
