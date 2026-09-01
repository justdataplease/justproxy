// Adapted from libwgslirpy 0.2.0, Copyright Vitaly Shukela.
// Upstream license: MIT OR Apache-2.0. JustProxy modifications: MIT.

use bytes::BytesMut;
use smoltcp::phy::{Checksum, Device, DeviceCapabilities, Medium, RxToken, TxToken};
use tokio::sync::mpsc::Sender;

const TEAR_OFF_ALLOCATION_SIZE: usize = 65_536;

/// A raw-IP smoltcp device backed by one receive slot and an async transmit queue.
pub struct ChannelDevice {
    pub tx: Sender<BytesMut>,
    pub rx: Option<BytesMut>,
    tear_off_buffer: BytesMut,
    mtu: usize,
}

impl ChannelDevice {
    pub fn new(tx: Sender<BytesMut>, mtu: usize) -> Self {
        Self {
            tx,
            rx: None,
            tear_off_buffer: BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE),
            mtu,
        }
    }
}

pub struct ChannelRxToken(BytesMut);

impl Device for ChannelDevice {
    type RxToken<'a>
        = ChannelRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = &'a mut ChannelDevice
    where
        Self: 'a;

    fn receive(
        &mut self,
        _timestamp: smoltcp::time::Instant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        self.rx.take().map(|packet| (ChannelRxToken(packet), self))
    }

    fn transmit(&mut self, _timestamp: smoltcp::time::Instant) -> Option<Self::TxToken<'_>> {
        if self.tx.capacity() == 0 {
            return None;
        }
        Some(self)
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut capabilities = DeviceCapabilities::default();
        capabilities.medium = Medium::Ip;
        capabilities.max_transmission_unit = self.mtu;
        capabilities.checksum = smoltcp::phy::ChecksumCapabilities::ignored();
        capabilities.checksum.tcp = Checksum::Tx;
        capabilities.checksum.udp = Checksum::Tx;
        capabilities.checksum.ipv4 = Checksum::Tx;
        capabilities.checksum.icmpv4 = Checksum::Tx;
        capabilities.checksum.icmpv6 = Checksum::Tx;
        capabilities
    }
}

impl RxToken for ChannelRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.0)
    }
}

impl TxToken for &mut ChannelDevice {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        self.tear_off_buffer.resize(len, 0);
        let result = f(&mut self.tear_off_buffer);
        let packet = self.tear_off_buffer.split();
        let _ = self.tx.try_send(packet);
        if self.tear_off_buffer.capacity() < 2_048 {
            self.tear_off_buffer = BytesMut::with_capacity(TEAR_OFF_ALLOCATION_SIZE);
        }
        result
    }
}
