package com.justproxy.app.wireguard;

import java.net.IDN;
import java.util.Locale;
import java.util.Objects;

/** A validated WireGuard peer endpoint consisting of a host or IP literal and UDP port. */
public final class WireGuardEndpoint {
    private final String host;
    private final int port;
    private final boolean ipv6;

    private WireGuardEndpoint(String host, int port, boolean ipv6) {
        this.host = host;
        this.port = port;
        this.ipv6 = ipv6;
    }

    public static WireGuardEndpoint of(String host, int port) {
        if (host == null || host.isEmpty() || !host.equals(host.trim())) {
            throw new IllegalArgumentException(
                    "endpoint host must not be null, empty, or surrounded by whitespace");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("endpoint port must be between 1 and 65535");
        }

        if (host.indexOf(':') >= 0) {
            IpAddressValidator.Family family =
                    IpAddressValidator.validateUsableLiteral(host, "endpoint host");
            if (family != IpAddressValidator.Family.IPV6) {
                throw new IllegalArgumentException("endpoint host must be a valid IPv6 literal");
            }
            return new WireGuardEndpoint(host.toLowerCase(Locale.ROOT), port, true);
        }

        if (IpAddressValidator.looksLikeIpv4(host)) {
            IpAddressValidator.validateIpv4(host, "endpoint host");
            return new WireGuardEndpoint(host, port, false);
        }

        String asciiHost = validateHostname(host);
        return new WireGuardEndpoint(asciiHost, port, false);
    }

    /** Parses {@code host:port}, {@code IPv4:port}, or {@code [IPv6]:port}. */
    public static WireGuardEndpoint parse(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "endpoint must not be null, empty, or surrounded by whitespace");
        }

        final String host;
        final String portText;
        if (value.charAt(0) == '[') {
            int closingBracket = value.indexOf(']');
            if (closingBracket <= 1 || closingBracket + 1 >= value.length()
                    || value.charAt(closingBracket + 1) != ':'
                    || value.indexOf('[', 1) >= 0
                    || value.indexOf(']', closingBracket + 1) >= 0) {
                throw new IllegalArgumentException(
                        "IPv6 endpoint must use the form [address]:port");
            }
            host = value.substring(1, closingBracket);
            portText = value.substring(closingBracket + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator <= 0 || separator != value.indexOf(':')) {
                throw new IllegalArgumentException(
                        "endpoint must use host:port or [IPv6]:port");
            }
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        return of(host, parsePort(portText));
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isIpv6() {
        return ipv6;
    }

    /** Returns the syntax expected by the Endpoint field in a WireGuard configuration. */
    public String toConfigValue() {
        return ipv6 ? "[" + host + "]:" + port : host + ":" + port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WireGuardEndpoint)) {
            return false;
        }
        WireGuardEndpoint that = (WireGuardEndpoint) other;
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return toConfigValue();
    }

    private static int parsePort(String value) {
        if (value.isEmpty() || value.length() > 5) {
            throw new IllegalArgumentException("endpoint port must be between 1 and 65535");
        }
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("endpoint port must contain only digits");
            }
            port = (port * 10) + (character - '0');
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("endpoint port must be between 1 and 65535");
        }
        return port;
    }

    private static String validateHostname(String host) {
        if (host.endsWith(".")) {
            throw new IllegalArgumentException("endpoint hostname must not end with a dot");
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("endpoint hostname is not valid", exception);
        }
        if (ascii.isEmpty() || ascii.length() > 253 || "localhost".equals(ascii)) {
            throw new IllegalArgumentException("endpoint hostname is not usable");
        }
        if (ascii.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("numeric endpoint hostname is ambiguous");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new IllegalArgumentException("endpoint hostname contains an invalid label");
            }
        }
        return ascii;
    }
}
