package com.justproxy.app.wireguard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable model of an exportable dual-stack WireGuard client profile. */
public final class WireGuardProfile {
    private final WireGuardProfileName name;
    private final WireGuardKey privateKey;
    private final String ipv4Address;
    private final String ipv6Address;
    private final List<String> dnsServers;
    private final int mtu;
    private final WireGuardPeer serverPeer;

    private WireGuardProfile(Builder builder) {
        name = builder.name;
        privateKey = builder.privateKey;
        ipv4Address = builder.ipv4Address;
        ipv6Address = builder.ipv6Address;
        dnsServers = List.copyOf(builder.dnsServers);
        mtu = builder.mtu;
        serverPeer = builder.serverPeer;
    }

    public static Builder builder(
            WireGuardProfileName name,
            WireGuardKey privateKey,
            WireGuardPeer serverPeer) {
        return new Builder(name, privateKey, serverPeer);
    }

    public static Builder builder(
            String name,
            WireGuardKey privateKey,
            WireGuardPeer serverPeer) {
        return builder(WireGuardProfileName.of(name), privateKey, serverPeer);
    }

    public WireGuardProfileName getName() {
        return name;
    }

    public String getFileName() {
        return name.toFileName();
    }

    public WireGuardKey getPrivateKey() {
        return privateKey;
    }

    public String getIpv4Address() {
        return ipv4Address;
    }

    public String getIpv6Address() {
        return ipv6Address;
    }

    public List<String> getDnsServers() {
        return dnsServers;
    }

    public int getMtu() {
        return mtu;
    }

    public WireGuardPeer getServerPeer() {
        return serverPeer;
    }

    public String renderConfig() {
        return WireGuardConfigRenderer.render(this);
    }

    /** Builder requiring one client address from each IP family and at least one DNS server. */
    public static final class Builder {
        private final WireGuardProfileName name;
        private final WireGuardKey privateKey;
        private final WireGuardPeer serverPeer;
        private String ipv4Address;
        private String ipv6Address;
        private List<String> dnsServers = List.of();
        private int mtu = 1280;

        private Builder(
                WireGuardProfileName name,
                WireGuardKey privateKey,
                WireGuardPeer serverPeer) {
            this.name = Objects.requireNonNull(name, "name");
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
            this.serverPeer = Objects.requireNonNull(serverPeer, "serverPeer");
        }

        public Builder addresses(String ipv4Address, String ipv6Address) {
            this.ipv4Address = validateCidr(
                    ipv4Address, IpAddressValidator.Family.IPV4, "IPv4 interface address");
            this.ipv6Address = validateCidr(
                    ipv6Address, IpAddressValidator.Family.IPV6, "IPv6 interface address");
            return this;
        }

        public Builder dnsServers(String... dnsServers) {
            if (dnsServers == null) {
                throw new IllegalArgumentException("dnsServers must not be null");
            }
            return dnsServers(Arrays.asList(dnsServers));
        }

        public Builder dnsServers(List<String> dnsServers) {
            if (dnsServers == null || dnsServers.isEmpty() || dnsServers.size() > 4) {
                throw new IllegalArgumentException("dnsServers must contain between 1 and 4 entries");
            }
            List<String> validated = new ArrayList<>(dnsServers.size());
            for (String dnsServer : dnsServers) {
                IpAddressValidator.validateUsableLiteral(dnsServer, "DNS server");
                if (validated.contains(dnsServer)) {
                    throw new IllegalArgumentException("dnsServers must not contain duplicates");
                }
                validated.add(dnsServer);
            }
            this.dnsServers = List.copyOf(validated);
            return this;
        }

        public Builder mtu(int mtu) {
            this.mtu = mtu;
            return this;
        }

        public WireGuardProfile build() {
            if (ipv4Address == null || ipv6Address == null) {
                throw new IllegalStateException("both IPv4 and IPv6 interface addresses are required");
            }
            if (dnsServers.isEmpty()) {
                throw new IllegalStateException("at least one DNS server is required");
            }
            if (mtu < 1280 || mtu > 65_535) {
                throw new IllegalArgumentException("MTU must be between 1280 and 65535");
            }
            return new WireGuardProfile(this);
        }
    }

    private static String validateCidr(
            String value,
            IpAddressValidator.Family expectedFamily,
            String fieldName) {
        if (value == null || !value.equals(value.trim())) {
            throw new IllegalArgumentException(fieldName + " must not be null or padded");
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) {
            throw new IllegalArgumentException(fieldName + " must use address/prefix syntax");
        }
        String address = value.substring(0, slash);
        String prefixText = value.substring(slash + 1);
        if (prefixText.length() > 1 && prefixText.charAt(0) == '0') {
            throw new IllegalArgumentException(fieldName + " prefix must use canonical digits");
        }
        int prefix = 0;
        for (int index = 0; index < prefixText.length(); index++) {
            char character = prefixText.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(fieldName + " prefix must contain only digits");
            }
            prefix = (prefix * 10) + (character - '0');
        }
        IpAddressValidator.Family actualFamily =
                IpAddressValidator.validateUsableLiteral(address, fieldName);
        int maximumPrefix = actualFamily == IpAddressValidator.Family.IPV4 ? 32 : 128;
        if (actualFamily != expectedFamily || prefix > maximumPrefix) {
            throw new IllegalArgumentException(fieldName + " has the wrong family or prefix length");
        }
        return value;
    }
}
