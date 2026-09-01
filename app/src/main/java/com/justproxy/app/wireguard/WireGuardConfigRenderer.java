package com.justproxy.app.wireguard;

import java.util.Objects;

/** Deterministic renderer for standard WireGuard/wg-quick client configuration files. */
public final class WireGuardConfigRenderer {
    private WireGuardConfigRenderer() {
    }

    public static String render(WireGuardProfile profile) {
        Objects.requireNonNull(profile, "profile");
        WireGuardPeer peer = profile.getServerPeer();

        StringBuilder output = new StringBuilder(384);
        output.append("[Interface]\n")
                .append("PrivateKey = ").append(profile.getPrivateKey().getEncoded()).append('\n')
                .append("Address = ").append(profile.getIpv4Address()).append(", ")
                .append(profile.getIpv6Address()).append('\n')
                .append("DNS = ").append(String.join(", ", profile.getDnsServers())).append('\n')
                .append("MTU = ").append(profile.getMtu()).append("\n\n")
                .append("[Peer]\n")
                .append("PublicKey = ").append(peer.getPublicKey().getEncoded()).append('\n');
        peer.getPresharedKey().ifPresent(key -> output.append("PresharedKey = ")
                .append(key.getEncoded()).append('\n'));
        output.append("Endpoint = ").append(peer.getEndpoint().toConfigValue()).append('\n')
                .append("AllowedIPs = ").append(String.join(", ", peer.getAllowedIps())).append('\n')
                .append("PersistentKeepalive = ").append(peer.getPersistentKeepaliveSeconds())
                .append('\n');
        return output.toString();
    }
}
