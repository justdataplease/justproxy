package com.justproxy.app.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProxySessionRecordTest {
    @Test
    public void removesUrlPathAndCredentialsFromTarget() {
        ProxySessionRecord session = new ProxySessionRecord(
                100L,
                200L,
                "192.0.2.1:1234",
                "http",
                "https://user:secret@example.com:8443/private?token=secret",
                10L,
                20L,
                "success");

        assertEquals("example.com:8443", session.getTarget());
        assertEquals("HTTP", session.getProtocol());
        assertEquals("SUCCESS", session.getResult());
    }

    @Test
    public void storesOnlyShortOutcomeCode() {
        ProxySessionRecord session = new ProxySessionRecord(
                0L, 1L, "client", "SOCKS5", "example.com:443", 0L, 0L,
                "failed: https://example.com/private");

        assertEquals("UNKNOWN", session.getResult());
    }

    @Test
    public void rejectsInvalidCountersAndTimes() {
        try {
            new ProxySessionRecord(2L, 1L, "", "HTTP", "", 0L, 0L, "OK");
            fail("Expected invalid timestamps");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        try {
            new ProxySessionRecord(1L, 2L, "", "HTTP", "", -1L, 0L, "OK");
            fail("Expected invalid byte count");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
