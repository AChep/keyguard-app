//! The private IPC endpoint between Keyguard and its own agent helpers.
//!
//! This crate is the app-side server. The helpers' client is the
//! `keyguard-agent-ipc` crate in `commonAgent/ipc`.
//!
//! It is a byte transport. It creates and secures the endpoint, accepts
//! connections with a kernel snapshot of the peer, moves bytes in both
//! directions at once, waits for input without consuming it, and closes with a
//! prompt wake-up of every blocked call. Framing, sessions, authentication,
//! limits, timeouts, peer policy and logging belong to the caller.
//!
//! Every call blocks the calling thread: there are no threads, no runtime, no
//! callbacks, no logging and no process-wide signal handlers. Closing the object
//! a call blocks on is the only way to cancel it.

pub mod bridge;
mod error;
mod name;

#[cfg(unix)]
mod unix;
#[cfg(unix)]
use unix as platform;
#[cfg(windows)]
mod windows;
#[cfg(windows)]
use windows as platform;

#[cfg(not(any(unix, windows)))]
compile_error!("keyguard-agent-ipc-core supports Unix and Windows only");

use std::sync::Arc;

pub use error::{Error, Failure};

/// Version of the C and JNI ABI.
pub const ABI_VERSION: u32 = 1;

/// Result with a stable failure category and a safe diagnostic.
pub type Result<T> = std::result::Result<T, Failure>;

/// Where [`Listener::bind`] creates its endpoint.
///
/// Prefixes have 0–32 bytes and exact names 1–64 bytes, both of `[A-Za-z0-9._-]`;
/// neither may be `.` or `..`. An exact name is for tests.
#[derive(Clone, Copy)]
pub enum Location<'a> {
    /// The socket `<parent>/<prefix><12 hex>/i` in a new 0700 run directory. `exact`
    /// replaces `<prefix><12 hex>`. The socket path fits `sun_path`: at most 103 bytes
    /// on macOS and 107 bytes on Linux.
    Unix {
        /// An existing absolute directory. It may be a symbolic link, such as `/tmp` on macOS.
        parent: &'a str,
        /// The start of the run directory's name.
        prefix: &'a str,
        /// The run directory's whole name, instead of a random one.
        exact: Option<&'a str>,
    },
    /// The named pipe `\\.\pipe\<prefix><32 hex>`, or `\\.\pipe\<exact>`.
    Windows {
        /// The start of the pipe's name.
        prefix: &'a str,
        /// The pipe's whole name, instead of a random one.
        exact: Option<&'a str>,
    },
}

/// Whether a connection has input, or its peer closed it.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Readiness {
    /// At least one byte is readable. It was not consumed.
    Input,
    /// The peer closed its side and every byte it sent has been read.
    Eof,
}

/// A listening endpoint, owned by this process.
pub struct Listener(Arc<platform::Listener>);

impl Listener {
    /// Creates and secures the endpoint. It is connectable on return.
    ///
    /// Arguments are validated before anything is created. A [`Location`] of the
    /// other platform is [`Error::InvalidArgument`]; a taken exact name is
    /// [`Error::Permission`].
    pub fn bind(location: Location<'_>) -> Result<Listener> {
        platform::Listener::bind(endpoint_name(location)?)
            .map(|listener| Listener(Arc::new(listener)))
    }

    /// The socket path or pipe name: the value of a helper's `--ipc-socket`.
    #[must_use]
    pub fn endpoint(&self) -> &str {
        self.0.endpoint()
    }

    /// Blocks until a client connects. One accept at a time: a concurrent call is
    /// [`Error::Busy`]. After [`Listener::close`] it is [`Error::Closed`].
    ///
    /// A failure of a single connection, such as a client that vanished or a peer
    /// that cannot be verified, never surfaces here. Only listener failures do.
    pub fn accept(&self) -> Result<Connection> {
        self.0
            .accept()
            .map(|connection| Connection(Arc::new(connection)))
    }

    /// Stops listening and wakes a blocked [`Listener::accept`]. Idempotent and
    /// non-blocking; connections already accepted stay open.
    pub fn close(&self) {
        self.0.close();
    }
}

/// An accepted connection. Its inbound lane ([`Connection::read`] and
/// [`Connection::await_input`]) and its outbound lane ([`Connection::write_all`])
/// run at the same time; a second call on a busy lane is [`Error::Busy`].
pub struct Connection(Arc<platform::Connection>);

impl Connection {
    /// The peer's process ID, from the kernel snapshot taken at accept, before any
    /// I/O. [`Error::Permission`] when the peer is not a verifiable process of this
    /// user.
    pub fn peer_pid(&self) -> Result<u32> {
        self.0.peer_pid()
    }

    /// Reads at least one byte into `out`, or returns 0 at end of stream. `out` must
    /// not be empty.
    pub fn read(&self, out: &mut [u8]) -> Result<usize> {
        if out.is_empty() {
            return Err(Failure::new(Error::InvalidArgument, "read", "empty_buffer"));
        }
        self.0.read(out)
    }

    /// Writes all of `data`. Empty data returns at once without a system call.
    /// [`Error::PeerClosed`] when the peer closed its side.
    pub fn write_all(&self, data: &[u8]) -> Result<()> {
        if data.is_empty() {
            return Ok(());
        }
        self.0.write_all(data)
    }

    /// Waits until input is readable or the peer closed, without consuming input.
    pub fn await_input(&self) -> Result<Readiness> {
        self.0.await_input()
    }

    /// Wakes every blocked call with [`Error::Closed`]. Idempotent and non-blocking.
    /// Data already written stays readable by the peer.
    pub fn close(&self) {
        self.0.close();
    }
}

#[cfg(unix)]
fn endpoint_name(location: Location<'_>) -> Result<name::UnixName<'_>> {
    match location {
        Location::Unix {
            parent,
            prefix,
            exact,
        } => name::UnixName::new(parent, prefix, exact),
        Location::Windows { .. } => Err(Failure::new(Error::InvalidArgument, "listen", "location")),
    }
}

#[cfg(windows)]
fn endpoint_name(location: Location<'_>) -> Result<name::WindowsName<'_>> {
    match location {
        Location::Windows { prefix, exact } => name::WindowsName::new(prefix, exact),
        Location::Unix { .. } => Err(Failure::new(Error::InvalidArgument, "listen", "location")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    const OTHER_PLATFORM: Location<'static> = Location::Windows {
        prefix: "kg-",
        exact: None,
    };
    #[cfg(windows)]
    const OTHER_PLATFORM: Location<'static> = Location::Unix {
        parent: "/tmp",
        prefix: "kg-",
        exact: None,
    };

    #[test]
    fn locations_of_the_other_platform_are_invalid() {
        let failure = Listener::bind(OTHER_PLATFORM).err().unwrap();
        assert_eq!(failure.kind(), Error::InvalidArgument);
        assert_eq!(failure.to_string(), "listen: location");
    }
}
