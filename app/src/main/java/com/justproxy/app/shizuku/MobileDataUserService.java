package com.justproxy.app.shizuku;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

/** Shizuku UserService running as the Shizuku server's shell or root identity. */
public final class MobileDataUserService extends IMobileDataService.Stub {
    private final MobileDataCommandEngine engine;

    /** Required for Shizuku versions before API 13. */
    public MobileDataUserService() {
        engine = new MobileDataCommandEngine(
                new ProcessCommandExecutor(), Thread::sleep, Os::getuid);
    }

    /** Preferred by Shizuku API 13; its Context enables cellular-loss observation. */
    @Keep
    public MobileDataUserService(Context context) {
        engine = new MobileDataCommandEngine(
                new ProcessCommandExecutor(),
                Thread::sleep,
                Os::getuid,
                new AndroidCellularNetworkLossMonitorFactory(context));
    }

    @Override
    public MobileDataCommandResult probe() {
        return engine.probe();
    }

    @Override
    public MobileDataCommandResult cycle(int downTimeMillis) {
        return engine.cycle(downTimeMillis);
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
}
