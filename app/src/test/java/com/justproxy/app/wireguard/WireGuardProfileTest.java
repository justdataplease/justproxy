package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class WireGuardProfileTest {
    @Test
    public void rendersStandardDeterministicDualStackFullTunnelConfiguration() {
        WireGuardKey privateKey = WireGuardKey.parse(WireGuardKeyTest.keyText(1));
        WireGuardKey publicKey = WireGuardKey.parse(WireGuardKeyTest.keyText(33));
        WireGuardKey presharedKey = WireGuardKey.parse(WireGuardKeyTest.keyText(65));
        WireGuardPeer peer = WireGuardPeer.builder(
                        publicKey, WireGuardEndpoint.parse("192.168.43.1:51820"))
                .presharedKey(presharedKey)
                .persistentKeepaliveSeconds(25)
                .build();
        WireGuardProfile profile = WireGuardProfile.builder("Office PC", privateKey, peer)
                .addresses("10.66.0.2/32", "fd66:6a75:7374::2/128")
                .dnsServers("10.66.0.1", "fd66:6a75:7374::1")
                .mtu(1280)
                .build();

        String expected = "[Interface]\n"
                + "PrivateKey = " + privateKey.getEncoded() + "\n"
                + "Address = 10.66.0.2/32, fd66:6a75:7374::2/128\n"
                + "DNS = 10.66.0.1, fd66:6a75:7374::1\n"
                + "MTU = 1280\n\n"
                + "[Peer]\n"
                + "PublicKey = " + publicKey.getEncoded() + "\n"
                + "PresharedKey = " + presharedKey.getEncoded() + "\n"
                + "Endpoint = 192.168.43.1:51820\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "PersistentKeepalive = 25\n";

        assertEquals(expected, profile.renderConfig());
        assertEquals(expected, WireGuardConfigRenderer.render(profile));
        assertEquals("Office PC.conf", profile.getFileName());
        assertEquals(List.of("0.0.0.0/0", "::/0"), peer.getAllowedIps());
    }

    @Test
    public void modelDefensivelyCopiesListsAndExposesImmutableViews() {
        List<String> dns = new ArrayList<>(List.of("10.66.0.1"));
        WireGuardProfile profile = validBuilder().dnsServers(dns).build();
        dns.add("1.1.1.1");

        assertEquals(List.of("10.66.0.1"), profile.getDnsServers());
        try {
            profile.getDnsServers().add("8.8.8.8");
            fail("expected immutable DNS list");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            profile.getServerPeer().getAllowedIps().add("10.0.0.0/8");
            fail("expected immutable allowed-IP list");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void omitsOptionalPresharedKeyButKeepsFullTunnelRoutes() {
        WireGuardProfile profile = validBuilder().dnsServers("10.66.0.1").build();

        String config = profile.renderConfig();
        assertFalse(config.contains("PresharedKey"));
        assertFalse(config.contains("AllowedIPs = 0.0.0.0/0\n"));
        assertEquals(true, config.contains("AllowedIPs = 0.0.0.0/0, ::/0\n"));
    }

    @Test
    public void rejectsMissingOrWrongFamilyAddressesInvalidDnsAndUnsafeMtu() {
        assertBuildFails(WireGuardProfile.builder(
                "PC", key(1), validPeer()).dnsServers("10.66.0.1"));

        try {
            validBaseBuilder().addresses("fd66::2/128", "fd66::3/128");
            fail("expected IPv4 family mismatch");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            validBuilder().dnsServers("dns.example");
            fail("expected non-literal DNS server to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            validBuilder().dnsServers("10.66.0.1", "10.66.0.1");
            fail("expected duplicate DNS server to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            validBuilder().dnsServers("10.66.0.1").mtu(1279).build();
            fail("expected MTU below IPv6 minimum to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void validatesPersistentKeepaliveRange() {
        try {
            WireGuardPeer.builder(key(2), WireGuardEndpoint.parse("phone.local:51820"))
                    .persistentKeepaliveSeconds(65_536)
                    .build();
            fail("expected keepalive overflow to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static WireGuardProfile.Builder validBuilder() {
        return validBaseBuilder().addresses("10.66.0.2/32", "fd66::2/128");
    }

    private static WireGuardProfile.Builder validBaseBuilder() {
        return WireGuardProfile.builder("PC", key(1), validPeer());
    }

    private static WireGuardPeer validPeer() {
        return WireGuardPeer.builder(
                key(2), WireGuardEndpoint.parse("phone.local:51820")).build();
    }

    private static WireGuardKey key(int seed) {
        return WireGuardKey.parse(WireGuardKeyTest.keyText(seed));
    }

    private static void assertBuildFails(WireGuardProfile.Builder builder) {
        try {
            builder.build();
            fail("expected profile build to fail");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }
}
