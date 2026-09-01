package com.justproxy.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/** Requests and tracks a cellular network without binding the app process globally. */
public final class CellularNetworkManager {
    public interface Listener {
        void onCellularAvailable(Network network);
        void onCellularLost();
        void onCellularUnavailable();
    }

    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network cellularNetwork;
    private long requestGeneration;

    public CellularNetworkManager(Context context) {
        connectivityManager = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public synchronized void request(Listener listener) {
        release();
        final long generation = ++requestGeneration;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        ConnectivityManager.NetworkCallback candidate =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        if (setAvailableIfCurrent(generation, network)) {
                            listener.onCellularAvailable(network);
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        if (clearLostIfCurrent(generation, network)) {
                            listener.onCellularLost();
                        }
                    }

                    @Override
                    public void onUnavailable() {
                        if (clearUnavailableIfCurrent(generation)) {
                            listener.onCellularUnavailable();
                        }
                    }
                };
        callback = candidate;
        try {
            connectivityManager.requestNetwork(request, candidate, 15_000);
        } catch (RuntimeException registrationFailure) {
            if (generation == requestGeneration && callback == candidate) {
                callback = null;
                cellularNetwork = null;
                requestGeneration++;
            }
            try {
                connectivityManager.unregisterNetworkCallback(candidate);
            } catch (RuntimeException ignored) {
                // Registration may have failed before Android retained the callback.
            }
            throw registrationFailure;
        }
    }

    private synchronized boolean setAvailableIfCurrent(long generation, Network network) {
        if (generation != requestGeneration || callback == null) return false;
        cellularNetwork = network;
        return true;
    }

    private synchronized boolean clearLostIfCurrent(long generation, Network network) {
        if (generation != requestGeneration || callback == null
                || !network.equals(cellularNetwork)) {
            return false;
        }
        cellularNetwork = null;
        return true;
    }

    private synchronized boolean clearUnavailableIfCurrent(long generation) {
        if (generation != requestGeneration || callback == null) return false;
        cellularNetwork = null;
        callback = null;
        requestGeneration++;
        return true;
    }

    public Network getCellularNetwork() {
        return cellularNetwork;
    }

    public Network getDefaultNetwork() {
        return connectivityManager.getActiveNetwork();
    }

    public synchronized void release() {
        requestGeneration++;
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        cellularNetwork = null;
        if (previous != null) {
            try {
                connectivityManager.unregisterNetworkCallback(previous);
            } catch (RuntimeException ignored) {
                // Callback was already released or connectivity state changed during shutdown.
            }
        }
    }
}
