package com.justproxy.app.wireguard;

import java.util.List;

/** Builds the fixed-address, dual-stack client profile used by the one-peer beta. */
public final class WireGuardPeerProfileFactory {
    public static final String CLIENT_IPV4 = "10.66.0.2/32";
    public static final String CLIENT_IPV6 = "fd66::2/128";
    public static final List<String> PUBLIC_DNS = List.of(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001");

    private WireGuardPeerProfileFactory() {
    }

    public static WireGuardProfile createClientProfile(
            WireGuardPeerRecord record,
            WireGuardEndpoint phoneEndpoint) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        WireGuardPeer serverPeer = WireGuardPeer.builder(
                record.getServerPublicKey(), phoneEndpoint).build();
        return WireGuardProfile.builder(
                        record.getPeerName(), record.getClientPrivateKey(), serverPeer)
                .addresses(CLIENT_IPV4, CLIENT_IPV6)
                .dnsServers(PUBLIC_DNS)
                .mtu(1280)
                .build();
    }
}
