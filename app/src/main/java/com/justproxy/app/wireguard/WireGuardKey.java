package com.justproxy.app.wireguard;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** A validated, immutable 32-byte WireGuard key. */
public final class WireGuardKey {
    private static final int KEY_BYTES = 32;
    private static final int ENCODED_LENGTH = 44;

    private final byte[] value;

    private WireGuardKey(byte[] value) {
        this.value = value.clone();
    }

    /**
     * Parses the canonical padded Base64 representation used by WireGuard configuration files.
     * Whitespace, URL-safe Base64, non-canonical padding, and the all-zero key are rejected.
     */
    public static WireGuardKey parse(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("WireGuard key must not be null");
        }
        if (encoded.length() != ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    "WireGuard key must be 44 Base64 characters encoding 32 bytes");
        }

        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("WireGuard key is not valid Base64", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("WireGuard key must decode to exactly 32 bytes");
        }

        String canonical = Base64.getEncoder().encodeToString(decoded);
        if (!canonical.equals(encoded)) {
            throw new IllegalArgumentException(
                    "WireGuard key must use canonical padded standard Base64");
        }

        int combined = 0;
        for (byte item : decoded) {
            combined |= item;
        }
        if (combined == 0) {
            throw new IllegalArgumentException("WireGuard key must not be the all-zero key");
        }
        return new WireGuardKey(decoded);
    }

    /** Returns the canonical Base64 value for intentional configuration export. */
    public String getEncoded() {
        return Base64.getEncoder().encodeToString(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WireGuardKey)) {
            return false;
        }
        WireGuardKey that = (WireGuardKey) other;
        return MessageDigest.isEqual(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    /** Avoids accidentally writing key material to logs. */
    @Override
    public String toString() {
        return "WireGuardKey[redacted]";
    }
}
