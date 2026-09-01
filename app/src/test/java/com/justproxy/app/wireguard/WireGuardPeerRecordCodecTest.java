package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

public final class WireGuardPeerRecordCodecTest {
    @Test
    public void roundTripsAllOnePeerFieldsWithoutLoggingKeys() {
        WireGuardPeerRecord record = record();

        byte[] encoded = WireGuardPeerRecordCodec.encode(record);
        WireGuardPeerRecord decoded = WireGuardPeerRecordCodec.decode(encoded);

        assertEquals(record, decoded);
        assertEquals(record.hashCode(), decoded.hashCode());
        assertEquals("Office-PC", decoded.getPeerName().getValue());
        assertEquals(1_725_000_000_123L, decoded.getCreatedAtMillis());
        assertFalse(decoded.toString().contains(decoded.getServerPrivateKey().getEncoded()));
        assertFalse(decoded.toString().contains(decoded.getClientPublicKey().getEncoded()));
    }

    @Test
    public void rejectsUnknownTruncatedAndTrailingFormats() {
        byte[] encoded = WireGuardPeerRecordCodec.encode(record());

        byte[] wrongMagic = encoded.clone();
        wrongMagic[0] ^= 0x01;
        assertInvalid(wrongMagic);

        byte[] wrongVersion = encoded.clone();
        wrongVersion[4] = 2;
        assertInvalid(wrongVersion);

        assertInvalid(Arrays.copyOf(encoded, encoded.length - 1));

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        trailing[trailing.length - 1] = 7;
        assertInvalid(trailing);
        assertInvalid(new byte[0]);
        assertInvalid(new byte[4097]);
    }

    @Test
    public void requiresPositiveCreationTime() {
        try {
            new WireGuardPeerRecord(
                    WireGuardProfileName.of("PC"),
                    0,
                    key(1), key(2), key(3), key(4));
            fail("expected invalid timestamp");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static WireGuardPeerRecord record() {
        return new WireGuardPeerRecord(
                WireGuardProfileName.of("Office-PC"),
                1_725_000_000_123L,
                key(1), key(33), key(65), key(97));
    }

    private static WireGuardKey key(int seed) {
        return WireGuardKey.parse(WireGuardKeyTest.keyText(seed));
    }

    private static void assertInvalid(byte[] value) {
        try {
            WireGuardPeerRecordCodec.decode(value);
            fail("expected invalid peer record");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
