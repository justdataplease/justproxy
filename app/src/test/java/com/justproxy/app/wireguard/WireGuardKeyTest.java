package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;

public final class WireGuardKeyTest {
    @Test
    public void acceptsCanonical32ByteKeyAndRoundTripsIt() {
        String encoded = keyText(7);

        WireGuardKey key = WireGuardKey.parse(encoded);

        assertEquals(encoded, key.getEncoded());
        assertEquals(key, WireGuardKey.parse(encoded));
        assertEquals(key.hashCode(), WireGuardKey.parse(encoded).hashCode());
        assertNotEquals(key, WireGuardKey.parse(keyText(8)));
        assertFalse(key.toString().contains(encoded));
    }

    @Test
    public void rejectsWrongLengthAndNonStandardOrWhitespaceBase64() {
        assertInvalid(keyText(1).substring(0, 43));

        byte[] highBytes = new byte[32];
        Arrays.fill(highBytes, (byte) 0xff);
        String standard = Base64.getEncoder().encodeToString(highBytes);
        assertInvalid(standard.replace('/', '_'));
        assertInvalid(standard.substring(0, 10) + "\n" + standard.substring(11));
    }

    @Test
    public void rejectsNonCanonicalPaddingBitsAndAllZeroKey() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 1);
        String canonical = Base64.getEncoder().encodeToString(bytes);
        // The last two bits of this sextet are padding and must be zero. Java's decoder accepts
        // the alternate spelling, so the explicit canonical round-trip check is important.
        String nonCanonical = canonical.substring(0, 42) + "F=";
        assertInvalid(nonCanonical);

        assertInvalid(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    public void rejectsNullWithoutEchoingSecretMaterial() {
        try {
            WireGuardKey.parse(null);
            fail("expected null key to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("null="));
        }
    }

    private static void assertInvalid(String value) {
        try {
            WireGuardKey.parse(value);
            fail("expected invalid key to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains(value));
        }
    }

    static String keyText(int seed) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
