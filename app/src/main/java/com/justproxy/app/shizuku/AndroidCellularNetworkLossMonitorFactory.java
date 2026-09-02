package com.justproxy.app.shizuku;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.ArrayList;
import java.util.List;

/** Watches the cellular network that existed before airplane mode was enabled. */
final class AndroidCellularNetworkLossMonitorFactory
        implements CellularNetworkLossMonitor.Factory {
    private static final long POLL_MILLIS = 100L;

    private final ConnectivityManager connectivityManager;

    AndroidCellularNetworkLossMonitorFactory(Context context) {
        connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public CellularNetworkLossMonitor open() {
        if (connectivityManager == null) {
            throw new IllegalStateException("ConnectivityManager is unavailable");
        }
        List<Network> initialCellularNetworks = new ArrayList<>();
        for (Network network : connectivityManager.getAllNetworks()) {
            if (isCellularInternetNetwork(network)) initialCellularNetworks.add(network);
        }
        return new InitialCellularNetworkLossMonitor<>(
                initialCellularNetworks,
                this::isCellularInternetNetwork,
                System::nanoTime,
                Thread::sleep,
                POLL_MILLIS);
    }

    private boolean isCellularInternetNetwork(Network network) {
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
