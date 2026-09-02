package com.justproxy.app.shizuku;

/** Reads the user-visible mobile-data setting without changing it. */
interface MobileDataStateReader {
    enum State { ENABLED, DISABLED, UNKNOWN }

    State read();
}
