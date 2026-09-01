package com.justproxy.app.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;

public final class ClientAddressPolicyTest {
    @Test public void acceptsLocalPrivateAndSharedRanges() throws Exception {
        assertTrue(trusted("127.0.0.1"));
        assertTrue(trusted("10.1.2.3"));
        assertTrue(trusted("172.16.4.2"));
        assertTrue(trusted("192.168.50.7"));
        assertTrue(trusted("169.254.1.2"));
        assertTrue(trusted("100.64.1.2"));
        assertTrue(trusted("100.127.255.254"));
        assertTrue(trusted("fc00::1"));
        assertTrue(trusted("fd12:3456::1"));
        assertTrue(trusted("fe80::1"));
    }

    @Test public void rejectsPublicMulticastAndWildcardRanges() throws Exception {
        assertFalse(trusted("0.0.0.0"));
        assertFalse(trusted("8.8.8.8"));
        assertFalse(trusted("1.1.1.1"));
        assertFalse(trusted("100.128.0.1"));
        assertFalse(trusted("224.0.0.1"));
        assertFalse(trusted("2001:4860:4860::8888"));
        assertFalse(trusted("ff02::1"));
    }

    private static boolean trusted(String value) throws Exception {
        return ClientAddressPolicy.isTrustedLocal(InetAddress.getByName(value));
    }
}
