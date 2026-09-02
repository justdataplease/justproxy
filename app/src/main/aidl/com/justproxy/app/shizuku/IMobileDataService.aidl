package com.justproxy.app.shizuku;

import com.justproxy.app.shizuku.MobileDataCommandResult;

/** Narrow privileged API. It deliberately does not accept arbitrary commands. */
interface IMobileDataService {
    MobileDataCommandResult probe() = 1;
    MobileDataCommandResult cycle(int downTimeMillis) = 2;
    MobileDataCommandResult restore() = 3;

    // Reserved Shizuku UserService destroy transaction (Binder transaction 16777115).
    void destroy() = 16777114;
}
