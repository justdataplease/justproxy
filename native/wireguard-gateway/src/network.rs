use std::io;

use tokio::net::{TcpSocket, UdpSocket};

/// Per-socket Android network selection.
///
/// The WireGuard listener never passes through this object. Only sockets that
/// connect or send traffic to the public Internet are bound to the selected
/// Android `Network`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct NetworkBinding {
    network_handle: u64,
    required: bool,
}

impl NetworkBinding {
    pub fn new(network_handle: u64, required: bool) -> Self {
        Self {
            network_handle,
            required,
        }
    }

    pub fn validate(self) -> io::Result<()> {
        if self.required && self.network_handle == 0 {
            return Err(io::Error::new(
                io::ErrorKind::NotConnected,
                "cellular-only mode requires a non-zero Android Network handle",
            ));
        }
        Ok(())
    }

    pub fn bind_tcp(self, socket: &TcpSocket) -> io::Result<()> {
        self.validate()?;
        if self.network_handle == 0 {
            return Ok(());
        }
        #[cfg(target_os = "android")]
        {
            use std::os::fd::AsRawFd;
            return bind_android_fd(self.network_handle, socket.as_raw_fd());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = socket;
            if self.required {
                Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "Android Network binding is unavailable on this target",
                ))
            } else {
                Ok(())
            }
        }
    }

    pub fn bind_udp(self, socket: &UdpSocket) -> io::Result<()> {
        self.validate()?;
        if self.network_handle == 0 {
            return Ok(());
        }
        #[cfg(target_os = "android")]
        {
            use std::os::fd::AsRawFd;
            return bind_android_fd(self.network_handle, socket.as_raw_fd());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = socket;
            if self.required {
                Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "Android Network binding is unavailable on this target",
                ))
            } else {
                Ok(())
            }
        }
    }
}

#[cfg(target_os = "android")]
fn bind_android_fd(network_handle: u64, fd: std::ffi::c_int) -> io::Result<()> {
    #[link(name = "android")]
    extern "C" {
        fn android_setsocknetwork(network: u64, fd: std::ffi::c_int) -> std::ffi::c_int;
    }

    // SAFETY: `fd` is borrowed from a live Tokio socket and the NDK function
    // neither takes ownership nor retains the descriptor.
    let result = unsafe { android_setsocknetwork(network_handle, fd) };
    if result == 0 {
        Ok(())
    } else {
        // android_setsocknetwork returns -1 and stores the actual reason in
        // errno (it does not return a negative errno value directly).
        Err(io::Error::last_os_error())
    }
}

#[cfg(test)]
mod tests {
    use super::NetworkBinding;

    #[test]
    fn cellular_only_mode_fails_closed_without_a_handle() {
        let error = NetworkBinding::new(0, true).validate().unwrap_err();
        assert_eq!(error.kind(), std::io::ErrorKind::NotConnected);
    }

    #[test]
    fn automatic_mode_allows_no_handle() {
        NetworkBinding::new(0, false).validate().unwrap();
    }
}
