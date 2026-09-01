// Adapted from libwgslirpy 0.2.0, Copyright Vitaly Shukela.
// Upstream license: MIT OR Apache-2.0. JustProxy modifications: MIT.

use std::{net::SocketAddr, sync::Arc, time::Duration};

use anyhow::Context;
use bytes::BytesMut;
use smoltcp::{
    iface::{Config, Interface, SocketSet, SocketStorage},
    socket::tcp::{self, State},
    time::Instant,
    wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpSocket,
    sync::mpsc::{Receiver, Sender},
};

use crate::{channel_device::ChannelDevice, network::NetworkBinding, stats::GatewayStats};

const DANGLE_TIME: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) async fn serve_tcp(
    tx_to_peer: Sender<BytesMut>,
    mut rx_from_peer: Receiver<BytesMut>,
    external_addr: IpEndpoint,
    mtu: usize,
    tcp_buffer_size: usize,
    network: NetworkBinding,
    stats: Arc<GatewayStats>,
) -> anyhow::Result<()> {
    let target_addr = match external_addr.addr {
        IpAddress::Ipv4(address) => {
            SocketAddr::new(std::net::IpAddr::V4(address.into()), external_addr.port)
        }
        IpAddress::Ipv6(address) => {
            SocketAddr::new(std::net::IpAddr::V6(address.into()), external_addr.port)
        }
    };

    let mut device = ChannelDevice::new(tx_to_peer, mtu);
    let interface_config = Config::new(HardwareAddress::Ip);
    let mut interface = Interface::new(interface_config, &mut device, Instant::now());
    interface.update_ip_addrs(|addresses| {
        let _ = addresses.push(IpCidr::new(external_addr.addr, 0));
    });

    #[derive(Debug)]
    enum SelectOutcome {
        TimePassed,
        PacketFromPeer(Option<BytesMut>),
        WrittenToInternet(Result<usize, std::io::Error>),
        InternetWriteShutdown(Result<(), std::io::Error>),
        ReadFromInternet(Result<usize, std::io::Error>),
        Noop,
        Deadline,
    }

    macro_rules! poll_until_deadline {
        ($loop_label:lifetime, $deadline:ident, $sockets:ident, $code:block) => {{
            let mut sleeper: Option<tokio::time::Sleep> = None;
            $loop_label: loop {
                $code
                let outcome = if let Some(delay) = sleeper.take() {
                    tokio::select! {
                        biased;
                        packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                        _ = delay => SelectOutcome::TimePassed,
                        _ = &mut $deadline => SelectOutcome::Deadline,
                    }
                } else {
                    tokio::select! {
                        biased;
                        packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                        _ = std::future::ready(()) => SelectOutcome::Noop,
                        _ = &mut $deadline => SelectOutcome::Deadline,
                    }
                };
                match outcome {
                    SelectOutcome::TimePassed => {
                        interface.poll(Instant::now(), &mut device, &mut $sockets);
                    }
                    SelectOutcome::PacketFromPeer(Some(packet)) => {
                        device.rx = Some(packet);
                        interface.poll(Instant::now(), &mut device, &mut $sockets);
                    }
                    SelectOutcome::Noop => {
                        let delay = interface
                            .poll_delay(Instant::now(), &mut $sockets)
                            .map(|delay| Duration::from_micros(delay.total_micros()))
                            .unwrap_or(Duration::from_secs(60));
                        sleeper = Some(tokio::time::sleep(delay));
                        continue;
                    }
                    SelectOutcome::PacketFromPeer(None) => return Ok(()),
                    SelectOutcome::Deadline => break $loop_label,
                    SelectOutcome::WrittenToInternet(_)
                    | SelectOutcome::InternetWriteShutdown(_)
                    | SelectOutcome::ReadFromInternet(_) => {
                        unreachable!()
                    }
                }
                sleeper = None;
            }
        }};
    }

    let tcp_rx_buffer = tcp::SocketBuffer::new(vec![0; tcp_buffer_size]);
    let tcp_tx_buffer = tcp::SocketBuffer::new(vec![0; tcp_buffer_size]);
    let tcp_socket = tcp::Socket::new(tcp_rx_buffer, tcp_tx_buffer);
    let mut internet_buffer = vec![0; tcp_buffer_size];
    let mut sockets = SocketSet::new([SocketStorage::EMPTY]);
    let handle = sockets.add(tcp_socket);
    interface.poll(Instant::now(), &mut device, &mut sockets);

    let connect_result = async {
        let socket = if target_addr.is_ipv4() {
            TcpSocket::new_v4()?
        } else {
            TcpSocket::new_v6()?
        };
        network.bind_tcp(&socket)?;
        let stream = tokio::time::timeout(CONNECT_TIMEOUT, socket.connect(target_addr))
            .await
            .context("TCP connect timed out")??;
        Ok::<_, anyhow::Error>(stream)
    }
    .await;

    let mut tcp = match connect_result {
        Ok(socket) => socket,
        Err(error) => {
            stats.set_transient_error(format!("TCP upstream {target_addr}: {error:#}"));

            // Keep polling briefly with no listening TCP socket. smoltcp emits
            // an RST for the peer's SYN instead of leaving it hanging.
            let graveyard_deadline = tokio::time::sleep(DANGLE_TIME);
            tokio::pin!(graveyard_deadline);
            let mut empty_sockets = SocketSet::new([]);
            poll_until_deadline!(
                'graveyard,
                graveyard_deadline,
                empty_sockets,
                {}
            );
            return Ok(());
        }
    };

    sockets
        .get_mut::<tcp::Socket>(handle)
        .listen(external_addr)?;
    let (mut tcp_reader, mut tcp_writer) = tcp.split();

    let accept_deadline = tokio::time::sleep(DANGLE_TIME);
    tokio::pin!(accept_deadline);
    poll_until_deadline!('accept, accept_deadline, sockets, {
        let socket = sockets.get_mut::<tcp::Socket>(handle);
        if socket.is_active() && socket.state() != State::SynSent {
            break 'accept;
        }
        if socket.state() == State::Closed {
            break 'accept;
        }
    });
    if !sockets.get_mut::<tcp::Socket>(handle).is_active() {
        return Ok(());
    }

    let mut sleeper: Option<tokio::time::Sleep> = None;
    let mut upstream_write_shutdown = false;

    'transfer: loop {
        let socket = sockets.get_mut::<tcp::Socket>(handle);
        match socket.state() {
            State::Closed | State::Listen | State::Closing | State::LastAck | State::TimeWait => {
                break 'transfer;
            }
            State::FinWait1
            | State::SynSent
            | State::CloseWait
            | State::FinWait2
            | State::SynReceived
            | State::Established => {}
        }

        let can_send_to_peer = if socket.can_send() {
            socket.send_capacity().saturating_sub(socket.send_queue())
        } else {
            0
        };

        let (data_to_internet, should_shutdown) = if socket.may_recv() {
            match socket.peek(65_536) {
                Ok(data) if !data.is_empty() => (Some(data), false),
                _ => (None, false),
            }
        } else if !upstream_write_shutdown && socket.state() == State::CloseWait {
            (None, true)
        } else {
            (None, false)
        };

        let outcome = if let Some(delay) = sleeper.take() {
            if should_shutdown {
                tokio::select! {
                    biased;
                    packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                    result = tcp_writer.shutdown() => SelectOutcome::InternetWriteShutdown(result),
                    result = tcp_reader.read(&mut internet_buffer[..can_send_to_peer]), if can_send_to_peer > 0 => SelectOutcome::ReadFromInternet(result),
                    _ = delay => SelectOutcome::TimePassed,
                }
            } else {
                tokio::select! {
                    biased;
                    packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                    result = tcp_writer.write(data_to_internet.unwrap_or(b"")), if data_to_internet.is_some() => SelectOutcome::WrittenToInternet(result),
                    result = tcp_reader.read(&mut internet_buffer[..can_send_to_peer]), if can_send_to_peer > 0 => SelectOutcome::ReadFromInternet(result),
                    _ = delay => SelectOutcome::TimePassed,
                }
            }
        } else if should_shutdown {
            tokio::select! {
                biased;
                packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                result = tcp_writer.shutdown() => SelectOutcome::InternetWriteShutdown(result),
                result = tcp_reader.read(&mut internet_buffer[..can_send_to_peer]), if can_send_to_peer > 0 => SelectOutcome::ReadFromInternet(result),
                _ = std::future::ready(()) => SelectOutcome::Noop,
            }
        } else {
            tokio::select! {
                biased;
                packet = rx_from_peer.recv() => SelectOutcome::PacketFromPeer(packet),
                result = tcp_writer.write(data_to_internet.unwrap_or(b"")), if data_to_internet.is_some() => SelectOutcome::WrittenToInternet(result),
                result = tcp_reader.read(&mut internet_buffer[..can_send_to_peer]), if can_send_to_peer > 0 => SelectOutcome::ReadFromInternet(result),
                _ = std::future::ready(()) => SelectOutcome::Noop,
            }
        };

        match outcome {
            SelectOutcome::TimePassed => {
                interface.poll(Instant::now(), &mut device, &mut sockets);
            }
            SelectOutcome::PacketFromPeer(Some(packet)) => {
                device.rx = Some(packet);
                interface.poll(Instant::now(), &mut device, &mut sockets);
            }
            SelectOutcome::ReadFromInternet(Ok(0)) => socket.close(),
            SelectOutcome::InternetWriteShutdown(Ok(())) => upstream_write_shutdown = true,
            SelectOutcome::WrittenToInternet(Ok(0)) => {
                stats.set_transient_error(format!(
                    "TCP flow {target_addr}: zero-byte upstream write"
                ));
                socket.abort();
                break 'transfer;
            }
            SelectOutcome::WrittenToInternet(Ok(bytes)) => {
                socket.recv(|_| (bytes, ()))?;
                stats.add_uploaded(bytes);
            }
            SelectOutcome::ReadFromInternet(Ok(bytes)) => {
                let sent = socket.send_slice(&internet_buffer[..bytes])?;
                stats.add_downloaded(sent);
            }
            SelectOutcome::Noop => {
                let delay = interface
                    .poll_delay(Instant::now(), &sockets)
                    .map(|delay| Duration::from_micros(delay.total_micros()))
                    .unwrap_or(Duration::from_secs(60));
                sleeper = Some(tokio::time::sleep(delay));
                continue;
            }
            SelectOutcome::PacketFromPeer(None) => return Ok(()),
            SelectOutcome::WrittenToInternet(Err(error))
            | SelectOutcome::InternetWriteShutdown(Err(error))
            | SelectOutcome::ReadFromInternet(Err(error)) => {
                stats.set_transient_error(format!("TCP flow {target_addr}: {error}"));
                socket.abort();
                break 'transfer;
            }
            SelectOutcome::Deadline => unreachable!(),
        }
        sleeper = None;
    }

    let finish_deadline = tokio::time::sleep(DANGLE_TIME);
    tokio::pin!(finish_deadline);
    let _ = tcp.shutdown().await;
    drop(tcp);
    poll_until_deadline!('finish, finish_deadline, sockets, {});
    Ok(())
}
