package com.justproxy.app.shizuku;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Main-process facade for Shizuku availability, permission, and safe mobile-data rotation. */
public final class ShizukuMobileDataController implements AutoCloseable {
    public static final int MIN_DOWN_TIME_MILLIS = MobileDataCommandEngine.MIN_DOWN_TIME_MILLIS;
    public static final int MAX_DOWN_TIME_MILLIS = MobileDataCommandEngine.MAX_DOWN_TIME_MILLIS;

    private static final int PERMISSION_REQUEST_CODE = 0x4a50;
    private static final int USER_SERVICE_PROTOCOL_VERSION = 1;
    private static final String USER_SERVICE_TAG = "justproxy-mobile-data";
    private static final String RECOVERY_PREFERENCES = "justproxy_mobile_data_recovery";
    private static final String RECOVERY_REQUIRED_KEY = "recovery_required";
    private static final long ENABLE_VERIFY_TIMEOUT_MILLIS = 5_000L;
    private static final long ENABLE_VERIFY_POLL_MILLIS = 250L;
    private static final int MAX_REBIND_ATTEMPTS = 3;
    private static final long REBIND_BASE_DELAY_MILLIS = 500L;

    public enum State {
        STOPPED,
        WAITING_FOR_SHIZUKU,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        BINDING,
        READY,
        UNSUPPORTED,
        ERROR
    }

    public interface AvailabilityCallback {
        void onAvailabilityChanged(Availability availability);
    }

    public interface OperationCallback {
        void onResult(MobileDataCommandResult result);

        void onUnavailable(Availability availability);

        void onError(Throwable error);
    }

    /** Immutable UI-facing snapshot. It never contains API tokens or traffic payloads. */
    public static final class Availability {
        private final State state;
        private final String message;
        private final int serverUid;
        private final boolean permissionGranted;
        private final boolean showPermissionRationale;

        Availability(
                State state,
                String message,
                int serverUid,
                boolean permissionGranted,
                boolean showPermissionRationale) {
            this.state = Objects.requireNonNull(state, "state");
            this.message = message == null ? "" : message;
            this.serverUid = serverUid;
            this.permissionGranted = permissionGranted;
            this.showPermissionRationale = showPermissionRationale;
        }

        public State getState() {
            return state;
        }

        public String getMessage() {
            return message;
        }

        public int getServerUid() {
            return serverUid;
        }

        public boolean isPermissionGranted() {
            return permissionGranted;
        }

        public boolean shouldShowPermissionRationale() {
            return showPermissionRationale;
        }

        public boolean isReady() {
            return state == State.READY;
        }
    }

    private interface RemoteOperation {
        MobileDataCommandResult call(IMobileDataService service) throws RemoteException;
    }

    private final Context applicationContext;
    private final AvailabilityCallback availabilityCallback;
    private final Handler mainHandler;
    private final Executor callbackExecutor;
    private final ExecutorService worker;
    private final SharedPreferences recoveryPreferences;
    private final MobileDataStateReader dataStateReader;
    private final MobileDataStatePoller dataStatePoller;
    private final RecoveryReconciler recoveryReconciler;
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final AtomicBoolean operationInFlight = new AtomicBoolean();
    private final BoundedRetryGate rebindRetryGate =
            new BoundedRetryGate(MAX_REBIND_ATTEMPTS);

    private volatile Availability availability = new Availability(
            State.STOPPED, "Shizuku mobile-data control is stopped", -1, false, false);
    private volatile IMobileDataService remoteService;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean binding;
    private volatile boolean closeRequested;
    private Runnable pendingRebind;
    private long rebindGeneration;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            this::handleBinderReceived;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::handleBinderDead;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            this::handlePermissionResult;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started || closed) {
                binding = false;
                remoteService = null;
                cleanupBinding();
                return;
            }
            IMobileDataService candidate = IMobileDataService.Stub.asInterface(binder);
            binding = false;
            if (candidate == null) {
                remoteService = null;
                publish(State.ERROR, "Shizuku returned an invalid UserService binder", true, false);
                return;
            }
            remoteService = candidate;
            resetRebindState();
            publish(State.READY, "Mobile-data control is ready", true, false);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteService = null;
            binding = false;
            if (started && !closed) {
                if (isBinderAliveSafely()) {
                    publish(
                            State.BINDING,
                            "Mobile-data UserService disconnected; retrying",
                            hasPermissionSafely(),
                            false);
                    scheduleRebind();
                } else {
                    publish(
                            State.WAITING_FOR_SHIZUKU,
                            "Mobile-data UserService disconnected",
                            false,
                            false);
                }
            }
        }
    };

    public ShizukuMobileDataController(
            Context context, AvailabilityCallback availabilityCallback) {
        applicationContext = Objects.requireNonNull(context, "context").getApplicationContext();
        this.availabilityCallback = Objects.requireNonNull(
                availabilityCallback, "availabilityCallback");
        mainHandler = new Handler(Looper.getMainLooper());
        callbackExecutor = mainThreadExecutor(mainHandler);
        worker = Executors.newSingleThreadExecutor(workerThreadFactory());
        Context recoveryContext = applicationContext.createDeviceProtectedStorageContext();
        recoveryPreferences = recoveryContext.getSharedPreferences(
                RECOVERY_PREFERENCES, Context.MODE_PRIVATE);
        dataStateReader = new AndroidMobileDataStateReader(applicationContext);
        dataStatePoller = new MobileDataStatePoller(
                dataStateReader, System::nanoTime, Thread::sleep);
        recoveryReconciler = new RecoveryReconciler(
                dataStateReader, () -> setRecoveryRequired(false), System::nanoTime);
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(applicationContext, MobileDataUserService.class))
                .daemon(true)
                .tag(USER_SERVICE_TAG)
                .processNameSuffix("mobile_data")
                .version(USER_SERVICE_PROTOCOL_VERSION);
    }

    /** Registers Shizuku listeners. This controller is one-shot after {@link #close()}. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Controller is closed");
        if (started) return;
        started = true;
        publish(State.WAITING_FOR_SHIZUKU, "Waiting for Shizuku", false, false);
        try {
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        } catch (RuntimeException exception) {
            publish(State.ERROR, safeMessage("Could not initialize Shizuku", exception), false, false);
        }
    }

    /** Requests Shizuku access. Call only in direct response to a user action. */
    public void requestPermission() {
        if (!started || closed) {
            publish(State.ERROR, "Start the Shizuku controller before requesting access", false, false);
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                publish(State.WAITING_FOR_SHIZUKU, "Start Shizuku first", false, false);
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                evaluateBinder();
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (RuntimeException exception) {
            publish(State.ERROR, safeMessage("Could not request Shizuku access", exception), false, false);
        }
    }

    public Availability getAvailability() {
        return availability;
    }

    /** True after a cycle begins until Android positively reports mobile data enabled. */
    public boolean isRecoveryRequired() {
        return recoveryPreferences.getBoolean(RECOVERY_REQUIRED_KEY, false);
    }

    public void probeAsync(OperationCallback callback) {
        executeAsync(false, false, callback, IMobileDataService::probe);
    }

    public void cycleAsync(int downTimeMillis, OperationCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (downTimeMillis < MIN_DOWN_TIME_MILLIS
                || downTimeMillis > MAX_DOWN_TIME_MILLIS) {
            dispatch(() -> callback.onError(new IllegalArgumentException(
                    "Mobile-data off time must be between 1 and 10 seconds")));
            return;
        }
        executeAsync(true, true, callback, service -> service.cycle(downTimeMillis));
    }

    public void restoreAsync(OperationCallback callback) {
        executeAsync(true, false, callback, IMobileDataService::restore);
    }

    /**
     * Clears a stale recovery marker only when Android positively reports mobile data enabled.
     * This local check does not require Shizuku to be installed, started, permitted, or bound.
     */
    public void reconcileRecoveryAsync(OperationCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed) {
            dispatch(() -> callback.onError(
                    new IllegalStateException("Shizuku controller is closed")));
            return;
        }
        if (!operationInFlight.compareAndSet(false, true)) {
            dispatch(() -> callback.onError(
                    new IllegalStateException("Another mobile-data operation is already running")));
            return;
        }
        try {
            worker.submit(() -> {
                MobileDataCommandResult result = null;
                RuntimeException failure = null;
                try {
                    result = recoveryReconciler.reconcile();
                } catch (RuntimeException exception) {
                    failure = exception;
                } finally {
                    releaseOperationAndCleanupIfClosed();
                }
                if (result != null) {
                    MobileDataCommandResult terminalResult = result;
                    dispatch(() -> callback.onResult(terminalResult));
                } else {
                    RuntimeException terminalFailure = failure == null
                            ? new IllegalStateException("Recovery reconciliation failed")
                            : failure;
                    dispatch(() -> callback.onError(terminalFailure));
                }
            });
        } catch (RejectedExecutionException exception) {
            releaseOperationAndCleanupIfClosed();
            dispatch(() -> callback.onError(
                    new IllegalStateException("Shizuku controller is closed", exception)));
        }
    }

    private void executeAsync(
            boolean markRecovery,
            boolean requireInitiallyEnabled,
            OperationCallback callback,
            RemoteOperation operation) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(operation, "operation");
        IMobileDataService service = remoteService;
        Availability currentAvailability = availability;
        if (service == null || !currentAvailability.isReady()) {
            dispatch(() -> callback.onUnavailable(currentAvailability));
            return;
        }
        if (!operationInFlight.compareAndSet(false, true)) {
            dispatch(() -> callback.onError(
                    new IllegalStateException("Another mobile-data operation is already running")));
            return;
        }

        try {
            worker.submit(() -> {
                MobileDataCommandResult result = null;
                RemoteException remoteFailure = null;
                RuntimeException runtimeFailure = null;
                try {
                    if (requireInitiallyEnabled) requireMobileDataEnabled();
                    if (markRecovery && !setRecoveryRequired(true)) {
                        throw new IllegalStateException(
                                "Could not persist the mobile-data recovery marker");
                    }

                    result = operation.call(service);
                    if (result == null) {
                        throw new RemoteException("Shizuku UserService returned no result");
                    }
                    result = verifyRestoreIfNeeded(result);
                } catch (RemoteException exception) {
                    remoteFailure = exception;
                } catch (RuntimeException exception) {
                    runtimeFailure = exception;
                } finally {
                    // Release before every terminal callback so callbacks can start recovery.
                    releaseOperationAndCleanupIfClosed();
                }

                if (remoteFailure != null) {
                    handleRemoteFailure(remoteFailure, service);
                    RemoteException terminalFailure = remoteFailure;
                    dispatch(() -> callback.onError(terminalFailure));
                } else if (runtimeFailure != null) {
                    RuntimeException terminalFailure = runtimeFailure;
                    dispatch(() -> callback.onError(terminalFailure));
                } else if (result != null) {
                    if (result.getOperation() == MobileDataCommandResult.OPERATION_PROBE
                            && result.getStatus() == MobileDataCommandResult.STATUS_UNSUPPORTED) {
                        publish(State.UNSUPPORTED, result.getMessage(), true, false);
                    }
                    MobileDataCommandResult terminalResult = result;
                    dispatch(() -> callback.onResult(terminalResult));
                } else {
                    dispatch(() -> callback.onError(
                            new IllegalStateException("Mobile-data operation produced no result")));
                }
            });
        } catch (RejectedExecutionException exception) {
            releaseOperationAndCleanupIfClosed();
            dispatch(() -> callback.onError(
                    new IllegalStateException("Shizuku controller is closed", exception)));
        }
    }

    private void releaseOperationAndCleanupIfClosed() {
        operationInFlight.set(false);
        if (closeRequested) cleanupBinding();
    }

    private void requireMobileDataEnabled() {
        MobileDataStateReader.State state = dataStateReader.read();
        if (state == MobileDataStateReader.State.DISABLED) {
            throw new IllegalStateException(
                    "Mobile data is already disabled; cycle was not started");
        }
        if (state != MobileDataStateReader.State.ENABLED) {
            throw new IllegalStateException(
                    "Android could not verify that mobile data is enabled; cycle was not started");
        }
    }

    private MobileDataCommandResult verifyRestoreIfNeeded(MobileDataCommandResult result) {
        if (!result.isRestoreAttempted()) return result;
        MobileDataStateReader.State state = waitForEnabledState();
        if (state == MobileDataStateReader.State.ENABLED) {
            setRecoveryRequired(false);
            return result.withRestoreVerification(
                    true,
                    result.isSuccess()
                            ? result.getMessage()
                            : result.getMessage() + "; mobile data is enabled");
        }
        String verificationMessage = state == MobileDataStateReader.State.DISABLED
                ? "Enable command completed, but Android still reports mobile data disabled"
                : "Enable command completed, but Android could not verify mobile-data state";
        return result.withRestoreVerification(false, verificationMessage);
    }

    private MobileDataStateReader.State waitForEnabledState() {
        return dataStatePoller.awaitEnabled(
                ENABLE_VERIFY_TIMEOUT_MILLIS, ENABLE_VERIFY_POLL_MILLIS);
    }

    private boolean setRecoveryRequired(boolean required) {
        return recoveryPreferences.edit().putBoolean(RECOVERY_REQUIRED_KEY, required).commit();
    }

    private void handleBinderReceived() {
        if (started && !closed) {
            resetRebindState();
            evaluateBinder();
        }
    }

    private void handleBinderDead() {
        remoteService = null;
        binding = false;
        resetRebindState();
        if (started && !closed) {
            publish(State.WAITING_FOR_SHIZUKU, "Shizuku stopped", false, false);
        }
    }

    private void handlePermissionResult(int requestCode, int grantResult) {
        if (requestCode != PERMISSION_REQUEST_CODE || !started || closed) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            evaluateBinder();
            return;
        }
        boolean rationale = false;
        try {
            rationale = Shizuku.shouldShowRequestPermissionRationale();
        } catch (RuntimeException ignored) {
            // Binder may have died while the permission dialog was open.
        }
        publish(State.PERMISSION_DENIED, "Shizuku access was denied", false, rationale);
    }

    private void evaluateBinder() {
        try {
            if (!Shizuku.pingBinder()) {
                resetRebindState();
                publish(State.WAITING_FOR_SHIZUKU, "Start Shizuku first", false, false);
                return;
            }
            if (Shizuku.isPreV11()) {
                resetRebindState();
                publish(State.UNSUPPORTED, "Shizuku 11 or newer is required", false, false);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                resetRebindState();
                boolean rationale = Shizuku.shouldShowRequestPermissionRationale();
                publish(
                        State.PERMISSION_REQUIRED,
                        rationale
                                ? "Explain why mobile-data control needs Shizuku access"
                                : "Allow JustProxy in Shizuku",
                        false,
                        rationale);
                return;
            }

            int uid = Shizuku.getUid();
            if (uid != 0 && uid != 2000) {
                resetRebindState();
                publish(State.UNSUPPORTED, "Unexpected Shizuku server identity", true, false);
                return;
            }
            if (Shizuku.checkRemotePermission(Manifest.permission.MODIFY_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                resetRebindState();
                publish(
                        State.UNSUPPORTED,
                        "This device does not grant mobile-data control to Shizuku",
                        true,
                        false);
                return;
            }
            bindUserService(uid);
        } catch (RuntimeException exception) {
            publish(State.ERROR, safeMessage("Shizuku capability check failed", exception), false, false);
        }
    }

    private synchronized void bindUserService(int uid) {
        if (!started || closed) return;
        if (remoteService != null) {
            publish(State.READY, "Mobile-data control is ready", true, false, uid);
            return;
        }
        if (binding) return;
        binding = true;
        publish(State.BINDING, "Starting mobile-data UserService", true, false, uid);
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (RuntimeException exception) {
            binding = false;
            publish(
                    State.BINDING,
                    safeMessage("Could not bind mobile-data UserService; retrying", exception),
                    true,
                    false,
                    uid);
            scheduleRebind();
        }
    }

    private boolean hasPermissionSafely() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void scheduleRebind() {
        final int attempt;
        final long generation;
        final Runnable retry;
        synchronized (this) {
            if (!started || closed || remoteService != null || binding
                    || !isBinderAliveSafely()) {
                return;
            }
            attempt = rebindRetryGate.reserveNextAttempt();
            if (attempt == BoundedRetryGate.ALREADY_SCHEDULED) return;
            if (attempt == BoundedRetryGate.EXHAUSTED) {
                if (availability.getState() != State.ERROR) {
                    publish(
                            State.ERROR,
                            "Mobile-data UserService reconnect attempts were exhausted",
                            hasPermissionSafely(),
                            false);
                }
                return;
            }
            generation = rebindGeneration;
            retry = () -> runScheduledRebind(generation);
            pendingRebind = retry;
        }

        long delayMillis = REBIND_BASE_DELAY_MILLIS * attempt;
        if (!mainHandler.postDelayed(retry, delayMillis)) {
            synchronized (this) {
                if (generation == rebindGeneration && pendingRebind == retry) {
                    pendingRebind = null;
                    rebindRetryGate.markScheduledRunStarted();
                }
            }
            publish(
                    State.ERROR,
                    "Could not schedule a mobile-data UserService reconnect",
                    hasPermissionSafely(),
                    false);
        }
    }

    private void runScheduledRebind(long generation) {
        synchronized (this) {
            if (generation != rebindGeneration) return;
            pendingRebind = null;
            rebindRetryGate.markScheduledRunStarted();
            if (!started || closed || remoteService != null || binding) return;
        }
        if (!isBinderAliveSafely()) {
            resetRebindState();
            publish(State.WAITING_FOR_SHIZUKU, "Shizuku stopped", false, false);
            return;
        }

        // Drop only this controller's stale callback; never remove the shared daemon.
        detachBindingWithoutRemoval();
        evaluateBinder();
    }

    private synchronized void resetRebindState() {
        rebindGeneration++;
        Runnable pending = pendingRebind;
        pendingRebind = null;
        if (pending != null) mainHandler.removeCallbacks(pending);
        rebindRetryGate.reset();
    }

    private void handleRemoteFailure(
            RemoteException exception, IMobileDataService failedService) {
        if (remoteService != failedService) return;
        remoteService = null;
        binding = false;
        if (!started || closed) return;
        String message = exception instanceof DeadObjectException
                ? "Shizuku connection was lost"
                : "Mobile-data UserService call failed";
        if (isBinderAliveSafely()) {
            publish(State.BINDING, message + "; retrying", hasPermissionSafely(), false);
            scheduleRebind();
        } else {
            resetRebindState();
            publish(State.WAITING_FOR_SHIZUKU, message, false, false);
        }
    }

    private void publish(
            State state, String message, boolean permissionGranted, boolean rationale) {
        int uid = permissionGranted ? currentServerUidSafely() : -1;
        publish(state, message, permissionGranted, rationale, uid);
    }

    private void publish(
            State state,
            String message,
            boolean permissionGranted,
            boolean rationale,
            int uid) {
        Availability next = new Availability(state, message, uid, permissionGranted, rationale);
        availability = next;
        dispatch(() -> availabilityCallback.onAvailabilityChanged(next));
    }

    private int currentServerUidSafely() {
        try {
            return Shizuku.pingBinder() ? Shizuku.getUid() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private void dispatch(Runnable callback) {
        try {
            callbackExecutor.execute(() -> {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // UI callback owners may be shutting down.
                }
            });
        } catch (RuntimeException ignored) {
            // Main looper may be shutting down with the process.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;
        closeRequested = true;
        resetRebindState();
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (RuntimeException ignored) {
            // Listener removal is best effort after Shizuku or the process stops.
        }
        worker.shutdown();
        if (!operationInFlight.get()) detachBindingWithoutRemoval();
        publish(State.STOPPED, "Shizuku mobile-data control is stopped", false, false, -1);
    }

    private synchronized void cleanupBinding() {
        detachBindingWithoutRemoval();
    }

    private synchronized void detachBindingWithoutRemoval() {
        remoteService = null;
        binding = false;
        try {
            if (!Shizuku.pingBinder()) return;
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, false);
        } catch (RuntimeException ignored) {
            // Detaching is best effort; the shared daemon must never be removed here.
        }
    }

    private static boolean isBinderAliveSafely() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Executor mainThreadExecutor(Handler handler) {
        return command -> {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
            } else {
                handler.post(command);
            }
        };
    }

    private static ThreadFactory workerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "justproxy-shizuku-controller");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String safeMessage(String prefix, RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? prefix
                : prefix + ": " + message.trim();
    }
}
