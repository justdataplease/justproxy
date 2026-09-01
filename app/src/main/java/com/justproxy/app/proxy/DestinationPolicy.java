package com.justproxy.app.proxy;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

final class DestinationPolicy {
    private DestinationPolicy() {}

    static InetAddress[] resolveAllowed(
            ProxyServerConfig config, String host, InetAddress literalAddress, int port)
            throws IOException {
        if (port < 1 || port > 65_535) {
            throw new ProxyFailure(SessionCloseReason.PROTOCOL_ERROR, "invalid target port");
        }
        if (port == 25) {
            throw new ProxyFailure(
                    SessionCloseReason.DESTINATION_DENIED, "TCP port 25 is blocked");
        }

        InetAddress[] resolved;
        if (literalAddress != null) {
            resolved = new InetAddress[] {literalAddress};
        } else {
            String normalizedHost = stripIpv6Brackets(host);
            if (normalizedHost.isEmpty()) {
                throw new ProxyFailure(SessionCloseReason.PROTOCOL_ERROR, "empty target host");
            }
            resolved = config.getOutboundConnector().resolve(normalizedHost);
        }

        if (resolved == null || resolved.length == 0) {
            throw new IOException("target did not resolve to an address");
        }

        List<InetAddress> allowed = new ArrayList<InetAddress>(resolved.length);
        for (InetAddress address : resolved) {
            if (address != null && isAllowed(address, config.isPrivateDestinationsAllowed())) {
                allowed.add(address);
            }
        }
        if (allowed.isEmpty()) {
            throw new ProxyFailure(
                    SessionCloseReason.DESTINATION_DENIED,
                    "target resolves only to blocked addresses");
        }
        return allowed.toArray(new InetAddress[allowed.size()]);
    }

    private static boolean isAllowed(InetAddress address, boolean allowPrivate) {
        byte[] bytes = address.getAddress();
        if (isIpv4Mapped(bytes)) {
            int first = bytes[12] & 0xff;
            // Java normally normalizes mapped literals to Inet4Address, but custom resolvers can
            // return Inet6Address. Classify the embedded IPv4 value explicitly either way.
            if (first == 0 || first >= 224) return false;
            return allowPrivate || !isRestrictedIpv4(bytes, 12);
        }

        // These address classes are never useful upstream proxy destinations.
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (allowPrivate) {
            return true;
        }
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return false;
        }

        if (address instanceof Inet4Address && bytes.length == 4) {
            return !isRestrictedIpv4(bytes, 0);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            // RFC 4193 unique-local addresses (fc00::/7) are not covered consistently by
            // InetAddress.isSiteLocalAddress().
            if ((bytes[0] & 0xfe) == 0xfc) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) return false;
        }
        return true;
    }

    private static boolean isRestrictedIpv4(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xff;
        int second = bytes[offset + 1] & 0xff;
        // RFC 1918, link-local, loopback, and RFC 6598 shared address space.
        return first == 0
                || first == 10
                || first == 127
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127)
                // Benchmark and reserved/broadcast ranges are local infrastructure too.
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }

    static String stripIpv6Brackets(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '['
                && trimmed.charAt(trimmed.length() - 1) == ']') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
