package com.justproxy.app.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;

public final class DestinationPolicyTest {
    @Test
    public void blocksPrivateIpv4MappedIpv6Literals() throws Exception {
        ProxyServerConfig config = ProxyServerConfig.builder("user", "password").build();

        assertMappedBlocked(config, "127.0.0.1");
        assertMappedBlocked(config, "10.0.0.1");
        assertMappedBlocked(config, "169.254.169.254");
    }

    @Test
    public void allowsPublicIpv4MappedIpv6Literal() throws Exception {
        ProxyServerConfig config = ProxyServerConfig.builder("user", "password").build();
        InetAddress mapped = mappedAddress("8.8.8.8");
        InetAddress[] allowed = DestinationPolicy.resolveAllowed(config, null, mapped, 443);
        assertEquals(1, allowed.length);
        assertTrue(allowed[0] instanceof Inet6Address);
    }

    private static void assertMappedBlocked(ProxyServerConfig config, String ipv4)
            throws Exception {
        try {
            DestinationPolicy.resolveAllowed(config, null, mappedAddress(ipv4), 443);
            fail("expected mapped private address to be denied: " + ipv4);
        } catch (ProxyFailure expected) {
            assertEquals(SessionCloseReason.DESTINATION_DENIED, expected.getCloseReason());
        }
    }

    private static Inet6Address mappedAddress(String ipv4) throws Exception {
        byte[] ipv4Bytes = InetAddress.getByName(ipv4).getAddress();
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        System.arraycopy(ipv4Bytes, 0, mapped, 12, 4);
        return Inet6Address.getByAddress(null, mapped, -1);
    }
}
