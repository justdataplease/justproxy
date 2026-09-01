//! JustProxy's unrooted, one-peer WireGuard exit gateway.

mod channel_device;
mod jni_bridge;
mod network;
mod policy;
mod router;
mod stats;
mod tcp_flow;
mod udp_flow;
mod wireguard;

use std::{
    net::{Ipv4Addr, Ipv6Addr, SocketAddr},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::Arc,
    thread::JoinHandle,
};

use anyhow::{bail, Context};
use base64::Engine;
use boringtun::x25519::{PublicKey, StaticSecret};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, oneshot};

pub use stats::StatsSnapshot;

#[derive(Clone, Deserialize)]
pub struct GatewayConfig {
    pub private_key: String,
    pub peer_public_key: String,
    pub listen: SocketAddr,
    #[serde(default)]
    pub network_handle: u64,
    #[serde(default = "default_require_bound_network")]
    pub require_bound_network: bool,
    pub peer_ipv4: Option<Ipv4Addr>,
    pub peer_ipv6: Option<Ipv6Addr>,
    #[serde(default = "default_mtu")]
    pub mtu: usize,
    #[serde(default = "default_tcp_buffer_size")]
    pub tcp_buffer_size: usize,
    #[serde(default = "default_max_tcp_flows")]
    pub max_tcp_flows: usize,
    #[serde(default = "default_max_udp_flows")]
    pub max_udp_flows: usize,
    #[serde(default)]
    pub keepalive_interval_seconds: Option<u16>,
}

fn default_require_bound_network() -> bool {
    true
}
fn default_mtu() -> usize {
    1_280
}
fn default_tcp_buffer_size() -> usize {
    65_536
}
fn default_max_tcp_flows() -> usize {
    256
}
fn default_max_udp_flows() -> usize {
    256
}

struct ValidatedConfig {
    private_key: StaticSecret,
    peer_public_key: PublicKey,
    listen: SocketAddr,
    network: network::NetworkBinding,
    peer_ipv4: Option<Ipv4Addr>,
    peer_ipv6: Option<Ipv6Addr>,
    mtu: usize,
    tcp_buffer_size: usize,
    max_tcp_flows: usize,
    max_udp_flows: usize,
    keepalive_interval_seconds: Option<u16>,
}

impl GatewayConfig {
    pub fn from_json(json: &str) -> anyhow::Result<Self> {
        serde_json::from_str(json).context("invalid gateway configuration JSON")
    }

    fn validate(self) -> anyhow::Result<ValidatedConfig> {
        if self.listen.port() == 0 {
            bail!("WireGuard listen port must be non-zero");
        }
        if self.peer_ipv4.is_none() && self.peer_ipv6.is_none() {
            bail!("at least one assigned peer IP address is required");
        }
        if let Some(address) = self.peer_ipv4 {
            if !address.is_private() || address.is_unspecified() || address.is_broadcast() {
                bail!("peer_ipv4 must be an RFC1918 address");
            }
        }
        if let Some(address) = self.peer_ipv6 {
            if (address.segments()[0] & 0xfe00) != 0xfc00 {
                bail!("peer_ipv6 must be a unique-local address");
            }
        }
        if !(576..=1_500).contains(&self.mtu) {
            bail!("mtu must be between 576 and 1500");
        }
        if !(4_096..=4 * 1_024 * 1_024).contains(&self.tcp_buffer_size) {
            bail!("tcp_buffer_size must be between 4096 and 4194304");
        }
        if !(1..=4_096).contains(&self.max_tcp_flows) || !(1..=4_096).contains(&self.max_udp_flows)
        {
            bail!("flow limits must be between 1 and 4096");
        }
        if self.keepalive_interval_seconds == Some(0) {
            bail!("keepalive_interval_seconds must be non-zero when present");
        }
        let network = network::NetworkBinding::new(self.network_handle, self.require_bound_network);
        network.validate()?;
        Ok(ValidatedConfig {
            private_key: StaticSecret::from(parse_key(&self.private_key, "private_key")?),
            peer_public_key: PublicKey::from(parse_key(&self.peer_public_key, "peer_public_key")?),
            listen: self.listen,
            network,
            peer_ipv4: self.peer_ipv4,
            peer_ipv6: self.peer_ipv6,
            mtu: self.mtu,
            tcp_buffer_size: self.tcp_buffer_size,
            max_tcp_flows: self.max_tcp_flows,
            max_udp_flows: self.max_udp_flows,
            keepalive_interval_seconds: self.keepalive_interval_seconds,
        })
    }
}

fn parse_key(value: &str, field: &str) -> anyhow::Result<[u8; 32]> {
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(value.trim())
        .with_context(|| format!("{field} is not valid base64"))?;
    decoded
        .as_slice()
        .try_into()
        .with_context(|| format!("{field} must decode to exactly 32 bytes"))
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GeneratedKeyPair {
    pub private_key: String,
    pub public_key: String,
}

pub fn generate_key_pair() -> anyhow::Result<GeneratedKeyPair> {
    let mut secret_bytes = [0_u8; 32];
    getrandom::getrandom(&mut secret_bytes)
        .map_err(|error| anyhow::anyhow!("secure random generator failed: {error}"))?;
    let secret = StaticSecret::from(secret_bytes);
    let public = PublicKey::from(&secret);
    let encoder = base64::engine::general_purpose::STANDARD;
    Ok(GeneratedKeyPair {
        private_key: encoder.encode(secret.to_bytes()),
        public_key: encoder.encode(public.as_bytes()),
    })
}

pub struct Gateway {
    stop_tx: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
    stats: Arc<stats::GatewayStats>,
}

impl Gateway {
    pub fn start(config: GatewayConfig) -> anyhow::Result<Self> {
        let config = config.validate()?;
        let listener = std::net::UdpSocket::bind(config.listen)
            .with_context(|| format!("cannot bind WireGuard listener {}", config.listen))?;
        listener.set_nonblocking(true)?;
        let stats = Arc::new(stats::GatewayStats::default());
        stats.set_running(true);
        let thread_stats = Arc::clone(&stats);
        let (stop_tx, stop_rx) = oneshot::channel();
        let spawn = std::thread::Builder::new()
            .name("justproxy-wireguard".to_owned())
            .spawn(move || {
                let outcome = catch_unwind(AssertUnwindSafe(|| {
                    run_gateway_thread(config, listener, stop_rx, Arc::clone(&thread_stats))
                }));
                match outcome {
                    Ok(Ok(())) => {}
                    Ok(Err(error)) => thread_stats.set_fatal_error(format!("{error:#}")),
                    Err(payload) => thread_stats.set_fatal_error(format!(
                        "gateway thread panicked: {}",
                        panic_payload_message(payload.as_ref())
                    )),
                }
                thread_stats.set_running(false);
            });
        let thread = match spawn {
            Ok(thread) => thread,
            Err(error) => {
                stats.set_running(false);
                return Err(error).context("cannot create gateway thread");
            }
        };
        Ok(Self {
            stop_tx: Some(stop_tx),
            thread: Some(thread),
            stats,
        })
    }

    pub fn snapshot(&self) -> StatsSnapshot {
        self.stats.snapshot()
    }

    pub fn stop(&mut self) -> anyhow::Result<()> {
        if let Some(stop) = self.stop_tx.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            thread
                .join()
                .map_err(|_| anyhow::anyhow!("gateway thread panicked"))?;
        }
        Ok(())
    }
}

fn panic_payload_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic payload".to_owned()
    }
}

impl Drop for Gateway {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn run_gateway_thread(
    config: ValidatedConfig,
    listener: std::net::UdpSocket,
    stop_rx: oneshot::Receiver<()>,
    stats: Arc<stats::GatewayStats>,
) -> anyhow::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .context("cannot create Tokio runtime")?;
    runtime.block_on(run_gateway(config, listener, stop_rx, stats))
}

async fn run_gateway(
    config: ValidatedConfig,
    listener: std::net::UdpSocket,
    mut stop_rx: oneshot::Receiver<()>,
    stats: Arc<stats::GatewayStats>,
) -> anyhow::Result<()> {
    let (wireguard_to_router_tx, wireguard_to_router_rx) = mpsc::channel(512);
    let (router_to_wireguard_tx, router_to_wireguard_rx) = mpsc::channel(512);
    let wireguard = wireguard::run(
        wireguard::WireGuardOptions {
            private_key: config.private_key,
            peer_public_key: config.peer_public_key,
            keepalive_interval: config.keepalive_interval_seconds,
            listener,
            stats: Arc::clone(&stats),
        },
        wireguard_to_router_tx,
        router_to_wireguard_rx,
    );
    let router = router::run(
        wireguard_to_router_rx,
        router_to_wireguard_tx,
        router::RouterOptions {
            mtu: config.mtu,
            tcp_buffer_size: config.tcp_buffer_size,
            max_tcp_flows: config.max_tcp_flows,
            max_udp_flows: config.max_udp_flows,
            peer_ipv4: config.peer_ipv4,
            peer_ipv6: config.peer_ipv6,
            network: config.network,
            stats,
        },
    );
    tokio::pin!(wireguard);
    tokio::pin!(router);
    tokio::select! {
        _ = &mut stop_rx => Ok(()),
        result = &mut wireguard => match result {
            Ok(()) => Err(anyhow::anyhow!("WireGuard listener ended unexpectedly")),
            Err(error) => Err(error.context("WireGuard listener failed")),
        },
        result = &mut router => match result {
            Ok(()) => Err(anyhow::anyhow!("userspace router ended unexpectedly")),
            Err(error) => Err(error.context("userspace router failed")),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::{generate_key_pair, GatewayConfig};
    use base64::Engine;

    fn valid_json(network_handle: u64) -> String {
        let gateway = generate_key_pair().unwrap();
        let peer = generate_key_pair().unwrap();
        format!(
            r#"{{"private_key":"{}","peer_public_key":"{}","listen":"127.0.0.1:51820","network_handle":{},"require_bound_network":true,"peer_ipv4":"10.66.0.2","peer_ipv6":"fd66:6a75:7374::2"}}"#,
            gateway.private_key, peer.public_key, network_handle
        )
    }

    #[test]
    fn key_generation_returns_wireguard_sized_keys() {
        let pair = generate_key_pair().unwrap();
        let decoder = base64::engine::general_purpose::STANDARD;
        assert_eq!(decoder.decode(pair.private_key).unwrap().len(), 32);
        assert_eq!(decoder.decode(pair.public_key).unwrap().len(), 32);
    }

    #[test]
    fn configuration_fails_closed_without_network_handle() {
        let error = GatewayConfig::from_json(&valid_json(0))
            .unwrap()
            .validate()
            .err()
            .unwrap();
        assert!(error
            .to_string()
            .contains("non-zero Android Network handle"));
    }

    #[test]
    fn valid_configuration_uses_safe_defaults() {
        let config = GatewayConfig::from_json(&valid_json(42)).unwrap();
        assert_eq!(config.mtu, 1_280);
        assert_eq!(config.max_tcp_flows, 256);
        config.validate().unwrap();
    }

    #[test]
    fn public_inner_addresses_are_rejected() {
        let json = valid_json(42).replace("10.66.0.2", "8.8.8.8");
        assert!(GatewayConfig::from_json(&json).unwrap().validate().is_err());
    }
}
