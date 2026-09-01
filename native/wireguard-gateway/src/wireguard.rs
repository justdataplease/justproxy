// Adapted from libwgslirpy 0.2.0, Copyright Vitaly Shukela.
// Upstream license: MIT OR Apache-2.0. JustProxy modifications: MIT.

use std::{net::SocketAddr, sync::Arc, time::Duration};

use boringtun::{
    noise::{Tunn, TunnResult},
    x25519::{PublicKey, StaticSecret},
};
use bytes::BytesMut;
use tokio::{
    net::UdpSocket,
    sync::mpsc::{Receiver, Sender},
};

use crate::stats::GatewayStats;

const TEAR_OFF_ALLOCATION_SIZE: usize = 65_536;

pub(crate) struct WireGuardOptions {
    pub private_key: StaticSecret,
    pub peer_public_key: PublicKey,
    pub keepalive_interval: Option<u16>,
    pub listener: std::net::UdpSocket,
    pub stats: Arc<GatewayStats>,
}

pub(crate) async fn run(
    options: WireGuardOptions,
    tx_from_wireguard: Sender<BytesMut>,
    mut rx_to_wireguard: Receiver<BytesMut>,
) -> anyhow::Result<()> {
    let mut tunnel = Tunn::new(
        options.private_key,
        options.peer_public_key,
        None,
        options.keepalive_interval,
        0,
        None,
    )
    .map_err(|error| anyhow::anyhow!(error))?;

    // Deliberately do not call Network.bindSocket/android_setsocknetwork here.
    // This listener must remain reachable from the phone's Wi-Fi/LAN address.
    let listener = UdpSocket::from_std(options.listener)?;
    let mut current_peer: Option<SocketAddr> = None;
    let mut timer = tokio::time::interval(Duration::from_secs(1));
    let mut network_buffer = vec![0_u8; 65_535];
    let mut tunnel_scratch = vec![0_u8; 65_535];
    let mut outgoing_packet = BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE);
    let mut previous_handshake_age: Option<Duration> = None;

    loop {
        let mut received_from = None;
        let mut result = tokio::select! {
            _ = timer.tick() => tunnel.update_timers(&mut tunnel_scratch),
            received = listener.recv_from(&mut network_buffer) => {
                let (length, source) = received?;
                received_from = Some(source);
                tunnel.decapsulate(None, &network_buffer[..length], &mut tunnel_scratch)
            }
            packet = rx_to_wireguard.recv() => {
                let Some(packet) = packet else { break };
                tunnel.encapsulate(&packet, &mut tunnel_scratch)
            }
        };

        if let Some(source) = received_from {
            if !matches!(result, TunnResult::Err(_)) {
                // Endpoint roaming is accepted only after BoringTun has
                // authenticated the datagram.
                current_peer = Some(source);
            }
        }

        loop {
            match result {
                TunnResult::Done => break,
                TunnResult::Err(error) => {
                    options
                        .stats
                        .set_transient_error(format!("WireGuard packet rejected: {error:?}"));
                    break;
                }
                TunnResult::WriteToNetwork(packet) => {
                    if let Some(peer) = current_peer {
                        if let Err(error) = listener.send_to(packet, peer).await {
                            options
                                .stats
                                .set_transient_error(format!("WireGuard LAN send failed: {error}"));
                        }
                    }
                    result = tunnel.decapsulate(None, b"", &mut tunnel_scratch);
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    outgoing_packet.extend_from_slice(packet);
                    match tx_from_wireguard.try_send(outgoing_packet.split()) {
                        Ok(()) => {}
                        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                        Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => return Ok(()),
                    }
                    if outgoing_packet.capacity() < 2_048 {
                        outgoing_packet = BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE);
                    }
                    break;
                }
            }
        }

        let handshake_age = tunnel.time_since_last_handshake();
        if let Some(age) = handshake_age {
            // BoringTun's session-established timer increases monotonically
            // and resets when a new authenticated session is established.
            if previous_handshake_age.is_none_or(|previous| age < previous) {
                options.stats.mark_handshake_age(age);
            }
        }
        previous_handshake_age = handshake_age;
    }
    Ok(())
}
