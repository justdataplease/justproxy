package com.justproxy.app.shizuku;

/** Reads the user-visible airplane-mode setting without changing it. */
interface AirplaneModeStateReader {
    enum State { ENABLED, DISABLED, UNKNOWN }

    State read();
}
