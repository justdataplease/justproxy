package com.justproxy.app.security;

import java.net.InetAddress;

/** Limits wildcard listeners to loopback, link-local, private, ULA, and CGNAT clients. */
public final class ClientAddressPolicy {
    private ClientAddressPolicy() {}

    public static boolean isTrustedLocal(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 100 && (second & 0xc0) == 64;
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
