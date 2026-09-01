// Adapted from libwgslirpy 0.2.0, Copyright Vitaly Shukela.
// Upstream license: MIT OR Apache-2.0. JustProxy modifications: MIT.

use std::{net::SocketAddr, sync::Arc, time::Duration};

use bytes::BytesMut;
use smoltcp::{
    phy::{Checksum, ChecksumCapabilities},
    wire::{
        IpAddress, IpEndpoint, IpProtocol, IpRepr, IpVersion, Ipv4Packet, Ipv6Packet, UdpPacket,
    },
};
use tokio::{
    net::UdpSocket,
    sync::mpsc::{Receiver, Sender},
};

use crate::{network::NetworkBinding, stats::GatewayStats};

const UDP_IDLE_TIMEOUT: Duration = Duration::from_secs(92);
const TEAR_OFF_ALLOCATION_SIZE: usize = 65_536;

pub(crate) async fn serve_udp(
    tx_to_peer: Sender<BytesMut>,
    mut rx_from_peer: Receiver<BytesMut>,
    client_addr: IpEndpoint,
    mtu: usize,
    network: NetworkBinding,
    stats: Arc<GatewayStats>,
) -> anyhow::Result<()> {
    let bind_addr = match client_addr.addr {
        IpAddress::Ipv4(_) => SocketAddr::new(std::net::Ipv4Addr::UNSPECIFIED.into(), 0),
        IpAddress::Ipv6(_) => SocketAddr::new(std::net::Ipv6Addr::UNSPECIFIED.into(), 0),
    };
    let upstream_socket = UdpSocket::bind(bind_addr).await?;
    network.bind_udp(&upstream_socket)?;

    let mut upstream_buffer = vec![0_u8; 65_535];
    let mut checksums = ChecksumCapabilities::ignored();
    checksums.udp = Checksum::Tx;
    checksums.ipv4 = Checksum::Tx;
    let mut packet_buffer = BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE);

    enum SelectOutcome {
        FromPeer(Option<BytesMut>),
        FromInternet(std::io::Result<(usize, SocketAddr)>),
        Timeout,
    }

    loop {
        let idle = tokio::time::sleep(UDP_IDLE_TIMEOUT);
        tokio::pin!(idle);
        let outcome = tokio::select! {
            packet = rx_from_peer.recv() => SelectOutcome::FromPeer(packet),
            packet = upstream_socket.recv_from(&mut upstream_buffer) => SelectOutcome::FromInternet(packet),
            _ = &mut idle => SelectOutcome::Timeout,
        };

        match outcome {
            SelectOutcome::FromPeer(None) | SelectOutcome::Timeout => break,
            SelectOutcome::FromPeer(Some(packet)) => {
                let (source, destination, payload) = match IpVersion::of_packet(&packet) {
                    Ok(IpVersion::Ipv4) => {
                        let Ok(ip) = Ipv4Packet::new_checked(&packet) else {
                            continue;
                        };
                        (ip.src_addr().into(), ip.dst_addr().into(), ip.payload())
                    }
                    Ok(IpVersion::Ipv6) => {
                        let Ok(ip) = Ipv6Packet::new_checked(&packet) else {
                            continue;
                        };
                        (ip.src_addr().into(), ip.dst_addr().into(), ip.payload())
                    }
                    Err(_) => continue,
                };

                let Ok(udp) = UdpPacket::new_checked(payload) else {
                    continue;
                };
                if !udp.verify_checksum(&source, &destination) {
                    continue;
                }
                let target = SocketAddr::new(destination.into(), udp.dst_port());
                let sent = upstream_socket.send_to(udp.payload(), target).await?;
                stats.add_uploaded(sent);
            }
            SelectOutcome::FromInternet(Err(error)) => {
                stats.set_transient_error(format!("UDP receive: {error}"));
            }
            SelectOutcome::FromInternet(Ok((bytes, source))) => {
                // These bytes have already crossed the Android network, even if
                // a datagram is too large for the configured tunnel MTU.
                stats.add_downloaded(bytes);
                let source = IpEndpoint::new(source.ip().into(), source.port());
                let data = &upstream_buffer[..bytes];
                let ip_header_len = match source.addr {
                    IpAddress::Ipv4(_) => 20,
                    IpAddress::Ipv6(_) => 40,
                };
                if ip_header_len + 8 + data.len() > mtu {
                    stats.set_transient_error(format!(
                        "dropping {}-byte UDP response larger than tunnel MTU {}",
                        data.len(),
                        mtu
                    ));
                    continue;
                }

                let ip_repr = IpRepr::new(
                    source.addr,
                    client_addr.addr,
                    IpProtocol::Udp,
                    data.len() + 8,
                    64,
                );
                let packet_len = ip_repr.buffer_len();
                packet_buffer.resize(packet_len, 0);
                let udp_repr = smoltcp::wire::UdpRepr {
                    src_port: source.port,
                    dst_port: client_addr.port,
                };

                match ip_repr {
                    IpRepr::Ipv4(repr) => {
                        let mut ip = Ipv4Packet::new_unchecked(&mut packet_buffer[..]);
                        repr.emit(&mut ip, &checksums);
                        let mut udp = UdpPacket::new_unchecked(ip.payload_mut());
                        udp_repr.emit(
                            &mut udp,
                            &source.addr,
                            &client_addr.addr,
                            data.len(),
                            |payload| payload.copy_from_slice(data),
                            &checksums,
                        );
                    }
                    IpRepr::Ipv6(repr) => {
                        let mut ip = Ipv6Packet::new_unchecked(&mut packet_buffer[..]);
                        repr.emit(&mut ip);
                        let mut udp = UdpPacket::new_unchecked(ip.payload_mut());
                        udp_repr.emit(
                            &mut udp,
                            &source.addr,
                            &client_addr.addr,
                            data.len(),
                            |payload| payload.copy_from_slice(data),
                            &checksums,
                        );
                    }
                }

                tx_to_peer.send(packet_buffer.split()).await?;
                if packet_buffer.capacity() < 2_048 {
                    packet_buffer = BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE);
                }
            }
        }
    }
    Ok(())
}
