package com.justproxy.app.shizuku;

import android.content.Context;
import android.os.RemoteException;
import android.system.Os;

import androidx.annotation.Keep;

/**
 * Shizuku UserService running as the Shizuku server's shell or root identity.
 *
 * <p>Cellular-loss observation is delegated back to the app through
 * {@link ICellularLossObserver}: this process runs as shell (uid 2000) while its Context is
 * attributed to the app package, and Android 16+ ConnectivityService rejects that mismatch
 * ("Package com.justproxy.app does not belong to 2000").
 */
public final class MobileDataUserService extends IMobileDataService.Stub {
    private final MobileDataCommandEngine engine;

    public MobileDataUserService() {
        engine = new MobileDataCommandEngine(
                new ProcessCommandExecutor(), Thread::sleep, Os::getuid);
    }

    /** Preferred by Shizuku API 13; the Context itself is not needed. */
    @Keep
    public MobileDataUserService(Context context) {
        this();
    }

    @Override
    public MobileDataCommandResult probe() {
        return engine.probe();
    }

    @Override
    public MobileDataCommandResult cycle(int downTimeMillis, ICellularLossObserver lossObserver) {
        if (lossObserver == null) return engine.cycle(downTimeMillis);
        return engine.cycle(downTimeMillis, () -> new RemoteLossMonitor(lossObserver));
    }

    @Override
    public MobileDataCommandResult restore() {
        return engine.restore();
    }

    @Override
    public MobileDataCommandResult restoreMobileData() {
        return engine.restoreLegacyMobileData();
    }

    /** Reserved UserService transaction invoked by Shizuku when the service is removed. */
    @Override
    public void destroy() {
        System.exit(0);
    }

    /** Forwards the bounded wait to the app process, which owns a correctly attributed view. */
    private static final class RemoteLossMonitor implements CellularNetworkLossMonitor {
        private final ICellularLossObserver observer;

        RemoteLossMonitor(ICellularLossObserver observer) {
            this.observer = observer;
        }

        @Override
        public boolean awaitLoss(long timeoutMillis) {
            try {
                return observer.awaitLoss(timeoutMillis);
            } catch (RemoteException exception) {
                throw new IllegalStateException(
                        "App-side cellular observer is unreachable: " + exception.getMessage(),
                        exception);
            }
        }

        @Override public void close() { }
    }
}
