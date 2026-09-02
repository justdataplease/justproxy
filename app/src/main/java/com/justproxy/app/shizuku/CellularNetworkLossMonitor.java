package com.justproxy.app.shizuku;

/** Read-only observation created before airplane mode is enabled. */
interface CellularNetworkLossMonitor extends AutoCloseable {
    interface Factory {
        CellularNetworkLossMonitor open();
    }

    boolean awaitLoss(long timeoutMillis) throws InterruptedException;

    @Override
    void close();

    /** Fails closed when an older Shizuku server cannot supply a service Context. */
    static Factory unavailableFactory() {
        return () -> {
            throw new IllegalStateException(
                    "Shizuku API 13 or newer is required to observe cellular loss");
        };
    }
}
