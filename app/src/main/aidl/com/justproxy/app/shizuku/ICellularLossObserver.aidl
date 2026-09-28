package com.justproxy.app.shizuku;

/**
 * App-side watcher for the cellular network that existed before airplane mode was enabled.
 * The privileged UserService cannot query ConnectivityService itself: Android 16+ rejects
 * calls attributed to the app package from the shell identity.
 */
interface ICellularLossObserver {
    boolean awaitLoss(long timeoutMillis) = 1;
}
