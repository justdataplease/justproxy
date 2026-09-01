use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex,
};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
pub struct StatsSnapshot {
    pub running: bool,
    pub uploaded_bytes: u64,
    pub downloaded_bytes: u64,
    pub active_tcp_flows: u64,
    pub active_udp_flows: u64,
    pub total_tcp_flows: u64,
    pub total_udp_flows: u64,
    pub last_handshake_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fatal_error: Option<String>,
}

#[derive(Debug, Default)]
pub(crate) struct GatewayStats {
    running: AtomicBool,
    uploaded_bytes: AtomicU64,
    downloaded_bytes: AtomicU64,
    active_tcp_flows: AtomicU64,
    active_udp_flows: AtomicU64,
    total_tcp_flows: AtomicU64,
    total_udp_flows: AtomicU64,
    last_handshake_ms: AtomicU64,
    last_transient_error: Mutex<String>,
    fatal_error: Mutex<Option<String>>,
}

#[derive(Clone, Copy)]
pub(crate) enum FlowKind {
    Tcp,
    Udp,
}

pub(crate) struct ActiveFlowGuard {
    stats: Arc<GatewayStats>,
    kind: FlowKind,
}

impl Drop for ActiveFlowGuard {
    fn drop(&mut self) {
        let counter = match self.kind {
            FlowKind::Tcp => &self.stats.active_tcp_flows,
            FlowKind::Udp => &self.stats.active_udp_flows,
        };
        counter.fetch_sub(1, Ordering::Relaxed);
    }
}

impl GatewayStats {
    pub(crate) fn set_running(&self, running: bool) {
        self.running.store(running, Ordering::Release);
    }

    pub(crate) fn add_uploaded(&self, bytes: usize) {
        self.uploaded_bytes
            .fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub(crate) fn add_downloaded(&self, bytes: usize) {
        self.downloaded_bytes
            .fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub(crate) fn mark_handshake_age(&self, age: std::time::Duration) {
        let established_at = std::time::SystemTime::now()
            .checked_sub(age)
            .unwrap_or(std::time::UNIX_EPOCH);
        let millis = established_at
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
            .min(u64::MAX as u128) as u64;
        self.last_handshake_ms.store(millis, Ordering::Release);
    }

    pub(crate) fn open_flow(self: &Arc<Self>, kind: FlowKind) -> ActiveFlowGuard {
        match kind {
            FlowKind::Tcp => {
                self.active_tcp_flows.fetch_add(1, Ordering::Relaxed);
                self.total_tcp_flows.fetch_add(1, Ordering::Relaxed);
            }
            FlowKind::Udp => {
                self.active_udp_flows.fetch_add(1, Ordering::Relaxed);
                self.total_udp_flows.fetch_add(1, Ordering::Relaxed);
            }
        }
        ActiveFlowGuard {
            stats: Arc::clone(self),
            kind,
        }
    }

    pub(crate) fn active_flows(&self, kind: FlowKind) -> u64 {
        match kind {
            FlowKind::Tcp => self.active_tcp_flows.load(Ordering::Relaxed),
            FlowKind::Udp => self.active_udp_flows.load(Ordering::Relaxed),
        }
    }

    pub(crate) fn set_transient_error(&self, error: impl Into<String>) {
        if let Ok(mut value) = self.last_transient_error.lock() {
            *value = error.into();
        }
    }

    pub(crate) fn set_fatal_error(&self, error: impl Into<String>) {
        if let Ok(mut value) = self.fatal_error.lock() {
            *value = Some(error.into());
        }
    }

    pub(crate) fn snapshot(&self) -> StatsSnapshot {
        StatsSnapshot {
            running: self.running.load(Ordering::Acquire),
            uploaded_bytes: self.uploaded_bytes.load(Ordering::Relaxed),
            downloaded_bytes: self.downloaded_bytes.load(Ordering::Relaxed),
            active_tcp_flows: self.active_tcp_flows.load(Ordering::Relaxed),
            active_udp_flows: self.active_udp_flows.load(Ordering::Relaxed),
            total_tcp_flows: self.total_tcp_flows.load(Ordering::Relaxed),
            total_udp_flows: self.total_udp_flows.load(Ordering::Relaxed),
            last_handshake_ms: self.last_handshake_ms.load(Ordering::Acquire),
            fatal_error: self.fatal_error.lock().ok().and_then(|value| value.clone()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::GatewayStats;

    #[test]
    fn transient_errors_do_not_become_terminal_failures() {
        let stats = GatewayStats::default();
        stats.set_transient_error("one UDP datagram failed");
        assert_eq!(stats.snapshot().fatal_error, None);
        stats.set_fatal_error("router terminated");
        assert_eq!(
            stats.snapshot().fatal_error.as_deref(),
            Some("router terminated")
        );
    }
}
