package com.justproxy.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ByteFormatterTest {
    @Test
    public void formatsTrafficWithIecUnitsAndAdaptivePrecision() {
        assertEquals("0 B", ByteFormatter.format(0));
        assertEquals("1023 B", ByteFormatter.format(1023));
        assertEquals("1.00 KiB", ByteFormatter.format(1024));
        assertEquals("10.0 KiB", ByteFormatter.format(10L * 1024L));
        assertEquals("100 KiB", ByteFormatter.format(100L * 1024L));
        assertEquals("1.00 MiB", ByteFormatter.format(1024L * 1024L));
        assertEquals("1.00 GiB", ByteFormatter.format(1024L * 1024L * 1024L));
    }

    @Test
    public void rejectsInvalidNegativeCounter() {
        assertEquals("\u2014", ByteFormatter.format(-1));
    }
}
