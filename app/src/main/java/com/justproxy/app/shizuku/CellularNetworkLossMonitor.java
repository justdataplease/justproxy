package com.justproxy.app.shizuku;

/** Read-only observation created before airplane mode is enabled. */
interface CellularNetworkLossMonitor extends AutoCloseable {
    interface Factory {
        CellularNetworkLossMonitor open();
    }

    boolean awaitLoss(long timeoutMillis) throws InterruptedException;

    @Override
    void close();

    /** Fails closed when the app did not supply a cellular-loss observer. */
    static Factory unavailableFactory() {
        return () -> {
            throw new IllegalStateException(
                    "No cellular-loss observer was supplied for this cycle");
        };
    }
}
