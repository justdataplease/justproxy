package com.justproxy.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;

public final class NetworkAddressesTest {
    @Test
    public void acceptsOnlyRfc1918AndIpv4LinkLocalAddresses() throws Exception {
        assertTrue(local("10.23.4.5"));
        assertTrue(local("172.16.0.1"));
        assertTrue(local("172.31.255.254"));
        assertTrue(local("192.168.43.1"));
        assertTrue(local("169.254.7.8"));

        assertFalse(local("127.0.0.1"));
        assertFalse(local("100.64.1.2"));
        assertFalse(local("8.8.8.8"));
    }

    @Test
    public void trustsLanInterfacesButRejectsCellularAndVpnInterfaces() {
        assertTrue(NetworkAddresses.isTrustedLanInterface("wlan0"));
        assertTrue(NetworkAddresses.isTrustedLanInterface("ap0"));
        assertTrue(NetworkAddresses.isTrustedLanInterface("softap0"));
        assertTrue(NetworkAddresses.isTrustedLanInterface("rndis0"));
        assertTrue(NetworkAddresses.isTrustedLanInterface("eth0"));

        assertFalse(NetworkAddresses.isTrustedLanInterface("rmnet_data0"));
        assertFalse(NetworkAddresses.isTrustedLanInterface("ccmni0"));
        assertFalse(NetworkAddresses.isTrustedLanInterface("tun0"));
        assertFalse(NetworkAddresses.isTrustedLanInterface("wg0"));
        assertFalse(NetworkAddresses.isTrustedLanInterface("ppp0"));
        assertFalse(NetworkAddresses.isTrustedLanInterface("unknown0"));
    }

    private static boolean local(String value) throws Exception {
        return NetworkAddresses.isLocalIpv4Address(InetAddress.getByName(value));
    }
}
