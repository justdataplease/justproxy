package com.justproxy.app.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class IpAddressValidatorTest {
    @Test
    public void normalizesIpv4() {
        assertEquals("203.0.113.7", IpAddressValidator.normalize("203.000.113.007"));
    }

    @Test
    public void acceptsIpv6() {
        assertEquals(
                "2001:db8:0:0:0:0:0:1",
                IpAddressValidator.normalize("2001:db8::1"));
    }

    @Test
    public void rejectsHostnamesAndMalformedAddresses() {
        assertInvalid("api.ipify.org");
        assertInvalid("127.0.0.1 extra");
        assertInvalid("999.0.0.1");
        assertInvalid("[2001:db8::1]");
        assertInvalid("fe80::1%wlan0");
    }

    private static void assertInvalid(String value) {
        try {
            IpAddressValidator.normalize(value);
            fail("Expected invalid IP: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
