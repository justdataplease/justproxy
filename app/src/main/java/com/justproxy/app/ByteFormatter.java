package com.justproxy.app;

import java.util.Locale;

/** Shared presentation helper for non-negative traffic counters. */
final class ByteFormatter {
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

    private ByteFormatter() {}

    static String format(long bytes) {
        if (bytes < 0) return "\u2014";
        double amount = bytes;
        int unitIndex = 0;
        while (amount >= 1024d && unitIndex < UNITS.length - 1) {
            amount /= 1024d;
            unitIndex++;
        }
        if (unitIndex == 0) return bytes + " B";
        if (amount >= 100d) {
            return String.format(Locale.ROOT, "%.0f %s", amount, UNITS[unitIndex]);
        }
        if (amount >= 10d) {
            return String.format(Locale.ROOT, "%.1f %s", amount, UNITS[unitIndex]);
        }
        return String.format(Locale.ROOT, "%.2f %s", amount, UNITS[unitIndex]);
    }
}
