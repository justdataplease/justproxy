package com.justproxy.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Discovers conservative, PC-reachable LAN endpoints without advertising cellular or VPN IPs. */
public final class NetworkAddresses {
    private NetworkAddresses() {}

    public static List<LocalAddress> localIpv4Addresses() {
        List<LocalAddress> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return candidates;
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                String name = networkInterface.getName();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || !isTrustedLanInterface(name)) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (isLocalIpv4Address(address)) {
                        candidates.add(new LocalAddress(
                                name,
                                interfaceLabel(name),
                                address.getHostAddress(),
                                interfacePriority(name, address.isLinkLocalAddress())));
                    }
                }
            }
        } catch (Exception ignored) {
            // The UI will continue to offer the deterministic ADB/USB endpoint.
        }

        candidates.sort(Comparator
                .comparingInt(LocalAddress::getPriority)
                .thenComparing(LocalAddress::getInterfaceName)
                .thenComparing(LocalAddress::getAddress));
        List<LocalAddress> unique = new ArrayList<>();
        Set<String> seenAddresses = new HashSet<>();
        for (LocalAddress candidate : candidates) {
            if (seenAddresses.add(candidate.address)) unique.add(candidate);
        }
        return Collections.unmodifiableList(unique);
    }

    /** Compatibility view used by older callers. Prefer {@link #localIpv4Addresses()}. */
    public static List<String> privateIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        for (LocalAddress address : localIpv4Addresses()) addresses.add(address.address);
        return addresses;
    }

    static boolean isLocalIpv4Address(InetAddress address) {
        if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 169 && second == 254);
    }

    static boolean isTrustedLanInterface(String interfaceName) {
        String name = normalize(interfaceName);
        if (name.isEmpty()
                || startsWithAny(name, "rmnet", "ccmni", "pdp", "wwan", "cell",
                        "tun", "tap", "wg", "ppp", "ipsec", "vti")) {
            return false;
        }
        return startsWithAny(name, "wlan", "wifi", "swlan", "ap", "softap", "p2p",
                "eth", "en", "rndis", "usb");
    }

    private static int interfacePriority(String interfaceName, boolean linkLocal) {
        String name = normalize(interfaceName);
        int priority;
        if (startsWithAny(name, "ap", "softap")) priority = 0;
        else if (startsWithAny(name, "wlan", "wifi", "swlan")) priority = 10;
        else if (name.startsWith("p2p")) priority = 20;
        else if (startsWithAny(name, "rndis", "usb")) priority = 30;
        else priority = 40;
        return linkLocal ? priority + 100 : priority;
    }

    private static String interfaceLabel(String interfaceName) {
        String name = normalize(interfaceName);
        if (startsWithAny(name, "ap", "softap")) return "Hotspot";
        if (startsWithAny(name, "wlan", "wifi", "swlan")) return "Wi-Fi / hotspot";
        if (name.startsWith("p2p")) return "Wi-Fi Direct";
        if (startsWithAny(name, "rndis", "usb")) return "USB tethering";
        return "Ethernet";
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class LocalAddress {
        private final String interfaceName;
        private final String label;
        private final String address;
        private final int priority;

        LocalAddress(String interfaceName, String label, String address, int priority) {
            this.interfaceName = interfaceName;
            this.label = label;
            this.address = address;
            this.priority = priority;
        }

        public String getInterfaceName() { return interfaceName; }

        public String getLabel() { return label; }

        public String getAddress() { return address; }

        int getPriority() { return priority; }

        public String getDisplayName() {
            return label + " (" + interfaceName + "): " + address;
        }
    }
}
