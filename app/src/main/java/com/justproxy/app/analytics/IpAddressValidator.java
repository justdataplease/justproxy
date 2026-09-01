package com.justproxy.app.analytics;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** IP-literal validation that never accepts a DNS hostname. */
final class IpAddressValidator {
    private IpAddressValidator() {
    }

    static String normalize(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("IP address is required");
        }

        String value = candidate.trim();
        if (value.isEmpty() || value.length() > 45) {
            throw new IllegalArgumentException("Invalid IP address");
        }

        if (value.indexOf(':') >= 0) {
            return normalizeIpv6(value);
        }
        return normalizeIpv4(value);
    }

    private static String normalizeIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address");
        }

        StringBuilder normalized = new StringBuilder(15);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            int octet = 0;
            for (int j = 0; j < part.length(); j++) {
                char character = part.charAt(j);
                if (character < '0' || character > '9') {
                    throw new IllegalArgumentException("Invalid IPv4 address");
                }
                octet = (octet * 10) + (character - '0');
            }
            if (octet > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            if (i > 0) {
                normalized.append('.');
            }
            normalized.append(octet);
        }
        return normalized.toString();
    }

    private static String normalizeIpv6(String value) {
        if (value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw new IllegalArgumentException("Scoped or bracketed IPv6 addresses are not accepted");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean allowed = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == ':'
                    || character == '.';
            if (!allowed) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
        }

        try {
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet6Address)) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            return parsed.getHostAddress().toLowerCase(Locale.US);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IPv6 address", exception);
        }
    }
}
