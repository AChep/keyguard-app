//! Helpers shared by the integration tests: endpoints, plain clients and time
//! bounds. Clients here are the standard library's: a Unix stream, or on Windows
//! a synchronous pipe handle, which must not read and write at the same time.

#![allow(dead_code, reason = "each test binary uses some of the helpers")]

use std::{
    io,
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

use keyguard_agent_ipc_core::{Connection, Listener, Location};

pub const MIB: usize = 1 << 20;

/// Scales the timing bounds of the wake tests: `KEYGUARD_AGENT_IPC_TIMING_SCALE`,
/// 1 by default.
pub fn timing_scale() -> u32 {
    std::env::var("KEYGUARD_AGENT_IPC_TIMING_SCALE")
        .ok()
        .and_then(|scale| scale.parse().ok())
        .filter(|&scale| scale > 0)
        .unwrap_or(1)
}

/// Where test endpoints live: on Unix a private directory under `/tmp`, short
/// enough for `sun_path`; on Windows the pipe namespace.
pub struct Parent {
    #[cfg(unix)]
    directory: tempfile::TempDir,
}

impl Parent {
    pub fn new() -> Self {
        Self {
            #[cfg(unix)]
            directory: tempfile::Builder::new()
                .prefix("kgipc-")
                .tempdir_in("/tmp")
                .unwrap(),
        }
    }

    /// The bridge's `parent` argument: required on Unix, empty on Windows.
    pub fn bridge_parent(&self) -> &str {
        #[cfg(unix)]
        {
            self.directory.path().to_str().unwrap()
        }
        #[cfg(windows)]
        {
            ""
        }
    }

    pub fn location<'a>(&'a self, exact: Option<&'a str>) -> Location<'a> {
        #[cfg(unix)]
        {
            Location::Unix {
                parent: self.bridge_parent(),
                prefix: "kg-test-",
                exact,
            }
        }
        #[cfg(windows)]
        {
            Location::Windows {
                prefix: "kg-agent-ipc-test-",
                exact,
            }
        }
    }

    /// A listener with a random name.
    pub fn bind(&self) -> Listener {
        Listener::bind(self.location(None)).unwrap()
    }
}

/// A fresh exact name.
pub fn exact_name() -> String {
    let mut random = [0_u8; 8];
    getrandom::fill(&mut random).unwrap();
    format!("kg-test-{:016x}", u64::from_ne_bytes(random))
}

#[cfg(unix)]
pub type Client = std::os::unix::net::UnixStream;
#[cfg(windows)]
pub type Client = std::fs::File;

/// One connection attempt.
pub fn try_connect(endpoint: &str) -> io::Result<Client> {
    #[cfg(unix)]
    {
        Client::connect(endpoint)
    }
    #[cfg(windows)]
    {
        std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(endpoint)
    }
}

/// Every instance of the pipe is taken, or the socket's backlog is full.
pub fn is_busy(error: &io::Error) -> bool {
    #[cfg(unix)]
    {
        matches!(
            error.kind(),
            io::ErrorKind::ConnectionRefused | io::ErrorKind::WouldBlock
        )
    }
    #[cfg(windows)]
    {
        error.raw_os_error() == Some(windows_sys::Win32::Foundation::ERROR_PIPE_BUSY as i32)
    }
}

/// Connects, retrying while busy for up to 10 seconds.
pub fn connect(endpoint: &str) -> Client {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        match try_connect(endpoint) {
            Ok(client) => return client,
            Err(error) if is_busy(&error) && Instant::now() < deadline => {
                thread::sleep(Duration::from_millis(2));
            }
            Err(error) => panic!("connect failed: {error}"),
        }
    }
}

/// A client and its accepted connection.
pub fn pair(listener: &Listener) -> (Connection, Client) {
    let client = connect(listener.endpoint());
    (listener.accept().unwrap(), client)
}

/// Reads exactly `out.len()` bytes from the connection.
pub fn read_exact(connection: &Connection, mut out: &mut [u8]) {
    while !out.is_empty() {
        let count = connection.read(out).unwrap();
        assert_ne!(count, 0, "unexpected end of stream");
        out = &mut out[count..];
    }
}

/// `length` bytes that are not all alike.
pub fn pattern(length: usize) -> Vec<u8> {
    (0..length).map(|index| (index % 251) as u8).collect()
}

/// Runs `body` on its own thread and fails the test if it takes longer than
/// `limit`, so a call that waits when it must not fails instead of hanging.
pub fn within<T: Send + 'static>(limit: Duration, body: impl FnOnce() -> T + Send + 'static) -> T {
    let (sender, receiver) = mpsc::channel();
    let worker = thread::spawn(move || {
        let _ = sender.send(body());
    });
    match receiver.recv_timeout(limit) {
        Ok(value) => {
            let _ = worker.join();
            value
        }
        Err(mpsc::RecvTimeoutError::Disconnected) => match worker.join() {
            Err(panic) => std::panic::resume_unwind(panic),
            Ok(()) => unreachable!("the worker ended without a result"),
        },
        Err(mpsc::RecvTimeoutError::Timeout) => panic!("timed out after {limit:?}"),
    }
}

/// Joins a server thread without blocking the async runtime. The helper's pipe
/// client keeps a read pending, so on Windows its handle closes only once the
/// runtime has taken the cancelled read off the completion port: a server that
/// waits for the client to leave would otherwise wait forever.
pub async fn join<T: Send + 'static>(thread: thread::JoinHandle<T>) -> T {
    match tokio::task::spawn_blocking(move || thread.join())
        .await
        .unwrap()
    {
        Ok(value) => value,
        Err(panic) => std::panic::resume_unwind(panic),
    }
}

/// Busy-waits for `duration`: shorter and more even than a sleep.
pub fn spin(duration: Duration) {
    let until = Instant::now() + duration;
    while Instant::now() < until {
        std::hint::spin_loop();
    }
}
