package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class WireGuardEndpointTest {
    @Test
    public void parsesLanIpv4AndHostnameEndpoints() {
        WireGuardEndpoint ipv4 = WireGuardEndpoint.parse("192.168.43.1:51820");
        assertEquals("192.168.43.1", ipv4.getHost());
        assertEquals(51820, ipv4.getPort());
        assertFalse(ipv4.isIpv6());
        assertEquals("192.168.43.1:51820", ipv4.toConfigValue());

        WireGuardEndpoint hostname = WireGuardEndpoint.parse("JustProxy.Local:41194");
        assertEquals("justproxy.local", hostname.getHost());
        assertEquals("justproxy.local:41194", hostname.toConfigValue());
    }

    @Test
    public void parsesAndRendersBracketedIpv6Endpoint() {
        WireGuardEndpoint endpoint = WireGuardEndpoint.parse("[fd66:6a75:7374::1]:51820");

        assertTrue(endpoint.isIpv6());
        assertEquals("fd66:6a75:7374::1", endpoint.getHost());
        assertEquals("[fd66:6a75:7374::1]:51820", endpoint.toConfigValue());
        assertEquals(endpoint, WireGuardEndpoint.of("fd66:6a75:7374::1", 51820));
    }

    @Test
    public void acceptsIdnHostByEncodingItForPortableConfig() {
        WireGuardEndpoint endpoint = WireGuardEndpoint.of("κινητό.local", 51820);

        assertEquals("xn--sxadcn0byc.local:51820", endpoint.toConfigValue());
    }

    @Test
    public void rejectsMalformedAndAmbiguousEndpointSyntax() {
        assertInvalid("fd66::1:51820");
        assertInvalid("[fd66::1]51820");
        assertInvalid("[fd66::1]:0");
        assertInvalid("phone.local:+51820");
        assertInvalid("http://phone.local:51820");
        assertInvalid("phone_local:51820");
        assertInvalid("192.168.001.1:51820");
        assertInvalid(" phone.local:51820");
    }

    @Test
    public void rejectsAddressesThatCannotIdentifyAReachablePhonePeer() {
        assertInvalid("0.0.0.0:51820");
        assertInvalid("127.0.0.1:51820");
        assertInvalid("224.0.0.1:51820");
        assertInvalid("[::]:51820");
        assertInvalid("[::1]:51820");
        assertInvalid("localhost:51820");
    }

    private static void assertInvalid(String endpoint) {
        try {
            WireGuardEndpoint.parse(endpoint);
            fail("expected endpoint to be rejected: " + endpoint);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
