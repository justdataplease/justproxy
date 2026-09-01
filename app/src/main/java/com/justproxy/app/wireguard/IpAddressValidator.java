package com.justproxy.app.wireguard;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Strict, DNS-free validation for IP literals used in exported profiles. */
final class IpAddressValidator {
    enum Family {
        IPV4,
        IPV6
    }

    private IpAddressValidator() {
    }

    static Family validateUsableLiteral(String value, String fieldName) {
        requireExactText(value, fieldName);
        if (value.indexOf(':') >= 0) {
            return validateIpv6(value, fieldName);
        }
        validateIpv4(value, fieldName);
        return Family.IPV4;
    }

    static boolean looksLikeIpv4(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '.' && (character < '0' || character > '9')) {
                return false;
            }
        }
        return true;
    }

    static void validateIpv4(String value, String fieldName) {
        requireExactText(value, fieldName);
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid(fieldName, "must be a four-octet IPv4 literal");
        }

        int first = -1;
        boolean allZero = true;
        boolean allOnes = true;
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3) {
                throw invalid(fieldName, "contains an invalid IPv4 octet");
            }
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                throw invalid(fieldName, "must not contain ambiguous leading-zero IPv4 octets");
            }
            int number = 0;
            for (int characterIndex = 0; characterIndex < octet.length(); characterIndex++) {
                char character = octet.charAt(characterIndex);
                if (character < '0' || character > '9') {
                    throw invalid(fieldName, "contains an invalid IPv4 octet");
                }
                number = (number * 10) + (character - '0');
            }
            if (number > 255) {
                throw invalid(fieldName, "contains an IPv4 octet greater than 255");
            }
            if (index == 0) {
                first = number;
            }
            allZero &= number == 0;
            allOnes &= number == 255;
        }

        if (allZero || allOnes || first == 127 || first >= 224) {
            throw invalid(fieldName, "must be a usable unicast IPv4 address");
        }
    }

    private static Family validateIpv6(String value, String fieldName) {
        if (value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw invalid(fieldName, "must be an IPv6 literal without brackets or a zone ID");
        }

        final InetAddress address;
        try {
            // A colon is required above, so this parses a numeric literal and cannot perform DNS.
            address = InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw invalid(fieldName, "is not a valid IPv6 literal");
        }
        if (!(address instanceof Inet6Address)) {
            throw invalid(fieldName, "must be an IPv6 literal, not an IPv4-mapped address");
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress()) {
            throw invalid(fieldName, "must be a usable unicast IPv6 address");
        }
        return Family.IPV6;
    }

    private static void requireExactText(String value, String fieldName) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw invalid(fieldName, "must not be null, empty, or surrounded by whitespace");
        }
    }

    private static IllegalArgumentException invalid(String fieldName, String message) {
        return new IllegalArgumentException(fieldName + " " + message);
    }
}
