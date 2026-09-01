// Routing model adapted from libwgslirpy 0.2.0, Copyright Vitaly Shukela.
// Upstream license: MIT OR Apache-2.0. JustProxy modifications: MIT.

use std::{
    collections::{hash_map::Entry, HashMap},
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    sync::Arc,
};

use bytes::BytesMut;
use smoltcp::wire::{
    IpAddress, IpEndpoint, IpProtocol, IpVersion, Ipv4Packet, Ipv6Packet, TcpPacket, UdpPacket,
};
use tokio::sync::mpsc::{channel, Receiver, Sender};

use crate::{
    network::NetworkBinding,
    policy::{destination_is_allowed, source_is_assigned},
    stats::{FlowKind, GatewayStats},
    tcp_flow, udp_flow,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum FlowKey {
    Tcp {
        client: IpEndpoint,
        internet: IpEndpoint,
    },
    Udp {
        client: IpEndpoint,
    },
}

#[derive(Clone)]
pub(crate) struct RouterOptions {
    pub mtu: usize,
    pub tcp_buffer_size: usize,
    pub max_tcp_flows: usize,
    pub max_udp_flows: usize,
    pub peer_ipv4: Option<Ipv4Addr>,
    pub peer_ipv6: Option<Ipv6Addr>,
    pub network: NetworkBinding,
    pub stats: Arc<GatewayStats>,
}

pub(crate) async fn run(
    mut rx_from_wireguard: Receiver<BytesMut>,
    tx_to_wireguard: Sender<BytesMut>,
    options: RouterOptions,
) -> anyhow::Result<()> {
    let mut flows = HashMap::<FlowKey, Sender<BytesMut>>::new();
    let (closed_tx, mut closed_rx) = tokio::sync::mpsc::unbounded_channel::<FlowKey>();

    loop {
        let packet = tokio::select! {
            packet = rx_from_wireguard.recv() => {
                let Some(packet) = packet else { break };
                packet
            }
            closed = closed_rx.recv() => {
                let Some(closed) = closed else { break };
                flows.remove(&closed);
                continue;
            }
        };

        let Some(parsed) = parse_transport(&packet) else {
            continue;
        };
        if !source_is_assigned(
            IpAddr::from(parsed.source),
            options.peer_ipv4,
            options.peer_ipv6,
        ) || !destination_is_allowed(IpAddr::from(parsed.destination), parsed.destination_port)
        {
            continue;
        }

        let (key, initial_tcp_syn) = match parsed.protocol {
            TransportProtocol::Tcp { initial_syn } => {
                let key = FlowKey::Tcp {
                    client: IpEndpoint::new(parsed.source, parsed.source_port),
                    internet: IpEndpoint::new(parsed.destination, parsed.destination_port),
                };
                (key, Some(initial_syn))
            }
            TransportProtocol::Udp => (
                FlowKey::Udp {
                    client: IpEndpoint::new(parsed.source, parsed.source_port),
                },
                None,
            ),
        };

        match flows.entry(key) {
            Entry::Occupied(entry) => {
                let _ = entry.get().try_send(packet);
            }
            Entry::Vacant(entry) => {
                if initial_tcp_syn == Some(false) {
                    continue;
                }
                let (kind, at_capacity) = match key {
                    FlowKey::Tcp { .. } => (
                        FlowKind::Tcp,
                        options.stats.active_flows(FlowKind::Tcp) >= options.max_tcp_flows as u64,
                    ),
                    FlowKey::Udp { .. } => (
                        FlowKind::Udp,
                        options.stats.active_flows(FlowKind::Udp) >= options.max_udp_flows as u64,
                    ),
                };
                if at_capacity {
                    continue;
                }

                let (flow_tx, flow_rx) = channel(16);
                let sender = entry.insert(flow_tx);
                let outbound = tx_to_wireguard.clone();
                let closed = closed_tx.clone();
                let flow_options = options.clone();
                let flow_guard = options.stats.open_flow(kind);
                tokio::spawn(async move {
                    let _flow_guard = flow_guard;
                    let result = match key {
                        FlowKey::Tcp { internet, .. } => {
                            tcp_flow::serve_tcp(
                                outbound,
                                flow_rx,
                                internet,
                                flow_options.mtu,
                                flow_options.tcp_buffer_size,
                                flow_options.network,
                                Arc::clone(&flow_options.stats),
                            )
                            .await
                        }
                        FlowKey::Udp { client } => {
                            udp_flow::serve_udp(
                                outbound,
                                flow_rx,
                                client,
                                flow_options.mtu,
                                flow_options.network,
                                Arc::clone(&flow_options.stats),
                            )
                            .await
                        }
                    };
                    if let Err(error) = result {
                        flow_options
                            .stats
                            .set_transient_error(format!("upstream flow failed: {error:#}"));
                    }
                    let _ = closed.send(key);
                });
                // A full flow queue is packet loss. TCP retransmits and UDP is
                // allowed to be lossy; blocking here would stall every flow.
                let _ = sender.try_send(packet);
            }
        }
    }
    Ok(())
}

#[derive(Clone, Copy)]
enum TransportProtocol {
    Tcp { initial_syn: bool },
    Udp,
}

#[derive(Clone, Copy)]
struct ParsedTransport {
    source: IpAddress,
    destination: IpAddress,
    source_port: u16,
    destination_port: u16,
    protocol: TransportProtocol,
}

fn parse_transport(packet: &[u8]) -> Option<ParsedTransport> {
    let (source, destination, protocol, payload) = match IpVersion::of_packet(packet).ok()? {
        IpVersion::Ipv4 => {
            let ip = Ipv4Packet::new_checked(packet).ok()?;
            (
                ip.src_addr().into(),
                ip.dst_addr().into(),
                ip.next_header(),
                ip.payload(),
            )
        }
        IpVersion::Ipv6 => {
            let ip = Ipv6Packet::new_checked(packet).ok()?;
            (
                ip.src_addr().into(),
                ip.dst_addr().into(),
                ip.next_header(),
                ip.payload(),
            )
        }
    };

    match protocol {
        IpProtocol::Tcp => {
            let tcp = TcpPacket::new_checked(payload).ok()?;
            Some(ParsedTransport {
                source,
                destination,
                source_port: tcp.src_port(),
                destination_port: tcp.dst_port(),
                protocol: TransportProtocol::Tcp {
                    initial_syn: tcp.syn() && !tcp.ack(),
                },
            })
        }
        IpProtocol::Udp => {
            let udp = UdpPacket::new_checked(payload).ok()?;
            Some(ParsedTransport {
                source,
                destination,
                source_port: udp.src_port(),
                destination_port: udp.dst_port(),
                protocol: TransportProtocol::Udp,
            })
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::parse_transport;
    use smoltcp::{
        phy::{Checksum, ChecksumCapabilities},
        wire::{IpAddress, IpProtocol, IpRepr, Ipv4Packet, UdpPacket, UdpRepr},
    };

    #[test]
    fn parses_an_ipv4_udp_flow() {
        let source: IpAddress = "10.66.0.2".parse::<std::net::IpAddr>().unwrap().into();
        let destination: IpAddress = "1.1.1.1".parse::<std::net::IpAddr>().unwrap().into();
        let repr = IpRepr::new(source, destination, IpProtocol::Udp, 12, 64);
        let mut checksums = ChecksumCapabilities::ignored();
        checksums.ipv4 = Checksum::Tx;
        checksums.udp = Checksum::Tx;
        let mut bytes = vec![0_u8; repr.buffer_len()];
        let IpRepr::Ipv4(ip_repr) = repr else {
            unreachable!();
        };
        let mut ip = Ipv4Packet::new_unchecked(&mut bytes);
        ip_repr.emit(&mut ip, &checksums);
        UdpRepr {
            src_port: 53000,
            dst_port: 53,
        }
        .emit(
            &mut UdpPacket::new_unchecked(ip.payload_mut()),
            &source,
            &destination,
            4,
            |payload| payload.copy_from_slice(b"test"),
            &checksums,
        );

        let parsed = parse_transport(&bytes).unwrap();
        assert_eq!(parsed.source_port, 53000);
        assert_eq!(parsed.destination_port, 53);
    }
}
