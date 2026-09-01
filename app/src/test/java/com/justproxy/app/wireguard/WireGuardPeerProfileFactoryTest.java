package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public final class WireGuardPeerProfileFactoryTest {
    @Test
    public void buildsFixedAddressPublicDnsFullTunnelProfile() {
        WireGuardPeerRecord record = record();
        WireGuardEndpoint endpoint = WireGuardEndpoint.parse("192.168.43.1:51820");

        WireGuardProfile profile =
                WireGuardPeerProfileFactory.createClientProfile(record, endpoint);
        String config = profile.renderConfig();

        assertEquals("JustProxy-PC.conf", profile.getFileName());
        assertEquals("10.66.0.2/32", profile.getIpv4Address());
        assertEquals("fd66::2/128", profile.getIpv6Address());
        assertEquals(List.of(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001"), profile.getDnsServers());
        assertEquals(1280, profile.getMtu());
        assertTrue(config.contains("PrivateKey = "
                + record.getClientPrivateKey().getEncoded()));
        assertTrue(config.contains("PublicKey = "
                + record.getServerPublicKey().getEncoded()));
        assertTrue(config.contains("Endpoint = 192.168.43.1:51820\n"));
        assertTrue(config.contains("AllowedIPs = 0.0.0.0/0, ::/0\n"));
        assertFalse(config.contains(record.getServerPrivateKey().getEncoded()));
        assertFalse(config.contains(record.getClientPublicKey().getEncoded()));
    }

    @Test
    public void publicDnsDefaultsCannotBeMutated() {
        try {
            WireGuardPeerProfileFactory.PUBLIC_DNS.add("8.8.8.8");
            fail("expected immutable DNS defaults");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static WireGuardPeerRecord record() {
        return new WireGuardPeerRecord(
                WireGuardProfileName.of("JustProxy-PC"),
                1_725_000_000_123L,
                key(1), key(33), key(65), key(97));
    }

    private static WireGuardKey key(int seed) {
        return WireGuardKey.parse(WireGuardKeyTest.keyText(seed));
    }
}
