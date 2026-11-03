//! Unix domain sockets in a private run directory (Linux, Android and macOS).
//!
//! A listener serves `<parent>/<name>/i`. The run directory is new: `mkdirat`
//! creates it 0700, it is checked through its own descriptor and never chmodded.
//! The socket in it is 0600. Both are removed only while they are still the
//! entries the listener created.
//!
//! Every fd is close-on-exec, and SIGPIPE is suppressed per descriptor
//! (`MSG_NOSIGNAL`, `SO_NOSIGPIPE`, `F_SETNOSIGPIPE`), never process-wide. Both
//! objects are shared through `Arc` and are `Send + Sync`; an fd closes only when
//! the last `Arc` drops, so it is never freed while a call uses it.

mod peer;

use std::{
    ffi::{CStr, CString},
    io,
    mem::MaybeUninit,
    os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
    sync::atomic::{AtomicBool, Ordering},
};

use libc::{c_int, c_short};
use zeroize::Zeroizing;

use crate::{Error, Failure, Readiness, Result, name::UnixName};

const LISTEN: &str = "listen";
const ACCEPT: &str = "accept";
const READ: &str = "read";
const WRITE: &str = "write";
const AWAIT_INPUT: &str = "await_input";

/// The socket's file name in its run directory.
const SOCKET_FILE: &CStr = c"i";
/// Connections the kernel queues before they are accepted.
const BACKLOG: c_int = 32;
/// Random run directory names tried before giving up.
const DIRECTORY_ATTEMPTS: usize = 10;

/// The most unread input the last drop of a connection discards, more than a
/// default Linux send buffer holds. Past it the peer may see `ECONNRESET`.
#[cfg(any(target_os = "linux", target_os = "android"))]
const DISCARD_LIMIT: usize = 1 << 20;
/// What one `recv` of that discard takes.
#[cfg(any(target_os = "linux", target_os = "android"))]
const DISCARD_CHUNK: usize = 4096;

/// Writes never raise SIGPIPE.
#[cfg(any(target_os = "linux", target_os = "android"))]
const SEND_FLAGS: c_int = libc::MSG_NOSIGNAL;
/// The socket has `SO_NOSIGPIPE` instead.
#[cfg(target_os = "macos")]
const SEND_FLAGS: c_int = 0;

#[cfg(any(target_os = "linux", target_os = "android"))]
const ACCEPT_CALL: &str = "accept4";
#[cfg(target_os = "macos")]
const ACCEPT_CALL: &str = "accept";

/// `F_SETNOSIGPIPE` from XNU `<sys/fcntl.h>`; the libc crate does not define it.
#[cfg(target_os = "macos")]
const F_SETNOSIGPIPE: c_int = 73;

/// A system call's result, or its `errno`.
type Os<T> = std::result::Result<T, c_int>;

pub(crate) struct Listener {
    endpoint: Endpoint,
    socket: OwnedFd,
    wake_read: OwnedFd,
    wake_write: OwnedFd,
    accepting: AtomicBool,
    stopped: AtomicBool,
}

impl Listener {
    /// Creates `<parent>/<name>/i`. `name` is validated, path length included.
    ///
    /// 1. `parentfd = open(parent, O_DIRECTORY | O_CLOEXEC)`. It follows symbolic
    ///    links on purpose: macOS `/tmp` is one.
    /// 2. `mkdirat(parentfd, name, 0700)`. `EEXIST` retries a random name with a new
    ///    [`UnixName::directory`] up to 10 times; for an exact name it is
    ///    `Permission "name_in_use"`.
    /// 3. `dirfd = openat(parentfd, name, O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)`, then
    ///    `fstat`: a directory, `st_uid == geteuid()`, `mode & 0o077 == 0`, and the
    ///    entry `mkdirat` created. Never chmod.
    /// 4. Linux: `socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK)`, then
    ///    `fchmod(fd, 0600)`. macOS: `socket`, then `FD_CLOEXEC`, `O_NONBLOCK` and
    ///    `SO_NOSIGPIPE`.
    /// 5. `bind` to [`UnixName::socket_path`], with `sun_len` on macOS.
    /// 6. `fstatat(dirfd, "i", AT_SYMLINK_NOFOLLOW)` must be a socket owned by euid;
    ///    otherwise `Permission "bind_escaped"`. Then `fchmodat(dirfd, "i", 0600)`.
    /// 7. `listen(fd, 32)`.
    /// 8. Wake pipe. Linux: `pipe2(O_CLOEXEC | O_NONBLOCK)`. macOS: `pipe`, then
    ///    `FD_CLOEXEC` and `O_NONBLOCK`, and `F_SETNOSIGPIPE` on the write end.
    ///
    /// Connectable on return. A failure removes what this call created.
    pub(crate) fn bind(name: UnixName<'_>) -> Result<Self> {
        // Dropped on failure: removes the socket file and the run directory.
        let mut endpoint = Endpoint::create(&name)?;
        let socket = new_socket()?;
        endpoint.bind(&socket)?;
        // SAFETY: socket is a live, bound socket.
        if unsafe { libc::listen(socket.as_raw_fd(), BACKLOG) } != 0 {
            return Err(Failure::last_os_error(Error::Io, LISTEN, "listen"));
        }
        let (wake_read, wake_write) = wake_pipe()?;
        Ok(Self {
            endpoint,
            socket,
            wake_read,
            wake_write,
            accepting: AtomicBool::new(false),
            stopped: AtomicBool::new(false),
        })
    }

    /// The socket path, `<parent>/<name>/i`.
    pub(crate) fn endpoint(&self) -> &str {
        &self.endpoint.path
    }

    /// Accepts one connection.
    ///
    /// - A busy guard: a concurrent accept is `Busy`. `Closed` once stopped.
    /// - Linux `accept4(SOCK_CLOEXEC | SOCK_NONBLOCK)`; macOS `accept`, then
    ///   `FD_CLOEXEC`, `O_NONBLOCK` and `SO_NOSIGPIPE`.
    /// - `EAGAIN`: `poll({fd POLLIN, wake_r POLLIN})`; a readable wake fd is `Closed`.
    /// - `EINTR`, `ECONNABORTED`, `ECONNRESET`, `EPROTO`: retry.
    /// - `EMFILE`, `ENFILE`, `ENOBUFS`, `ENOMEM` or a broken listener: `Io` with the
    ///   OS code. These are the only failures accept returns.
    /// - Per-connection failures stay inside: a failed `fcntl` or `SO_NOSIGPIPE`
    ///   (XNU refuses options once both shutdown bits are set) closes that fd and
    ///   accept keeps looping, unreported.
    /// - The peer snapshot ([`peer`]) is taken here, before any I/O, and stored on
    ///   the connection.
    pub(crate) fn accept(&self) -> Result<Connection> {
        let _lane = Lane::enter(&self.accepting, ACCEPT)?;
        self.accept_with(|| accept_socket(&self.socket))
    }

    /// The accept loop over `accept`, one `accept`/`accept4` call.
    fn accept_with(&self, mut accept: impl FnMut() -> Os<OwnedFd>) -> Result<Connection> {
        loop {
            if self.stopped.load(Ordering::SeqCst) {
                return Err(closed(ACCEPT));
            }
            match accept() {
                Ok(socket) => {
                    if let Some(connection) = Connection::accepted(socket) {
                        return Ok(connection);
                    }
                }
                Err(code) if would_block(code) => self.await_client()?,
                Err(libc::EINTR | libc::ECONNABORTED | libc::ECONNRESET | libc::EPROTO) => {}
                Err(code) => return Err(io_failure(ACCEPT, ACCEPT_CALL, code)),
            }
        }
    }

    /// Waits until a client is queued or the listener is stopped.
    fn await_client(&self) -> Result<()> {
        let mut descriptors = [
            poll_fd(&self.socket, libc::POLLIN),
            poll_fd(&self.wake_read, libc::POLLIN),
        ];
        poll(&mut descriptors).map_err(|code| io_failure(ACCEPT, "poll", code))?;
        if descriptors[1].revents != 0 {
            return Err(closed(ACCEPT));
        }
        let listener = descriptors[0].revents;
        if listener & libc::POLLIN == 0
            && listener & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0
        {
            return Err(Failure::new(Error::Io, ACCEPT, "listener_broken"));
        }
        Ok(())
    }

    /// Stops the listener. Idempotent, non-blocking, never shuts the socket down.
    ///
    /// 1. `stopped = true`, then write 1 byte to `wake_w`. It is never drained.
    /// 2. If `fstatat(dirfd, "i")` still matches the socket's `(dev, ino)`,
    ///    `unlinkat(dirfd, "i", 0)`.
    /// 3. If `fstatat(parentfd, name, AT_SYMLINK_NOFOLLOW)` still matches the run
    ///    directory's `(dev, ino)`, `unlinkat(parentfd, name, AT_REMOVEDIR)`.
    ///
    /// The fds close at the last drop. A listener dropped without `close` removes
    /// its endpoint then.
    pub(crate) fn close(&self) {
        if self.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        let byte = [1_u8];
        retry(|| {
            // SAFETY: wake_write is live and byte is readable for its length. This is
            // the pipe's only write, so it never finds the pipe full.
            unsafe {
                libc::write(
                    self.wake_write.as_raw_fd(),
                    byte.as_ptr().cast(),
                    byte.len(),
                )
            }
        });
        self.endpoint.remove();
    }
}

pub(crate) struct Connection {
    socket: OwnedFd,
    peer: Result<u32>,
    closed: AtomicBool,
    inbound: AtomicBool,
    outbound: AtomicBool,
}

impl Connection {
    /// Prepares an accepted socket and takes the peer snapshot, before any I/O.
    /// `None` when the socket cannot be prepared: it is closed, and accept goes on.
    fn accepted(socket: OwnedFd) -> Option<Self> {
        #[cfg(target_os = "macos")]
        configure_socket(&socket, ACCEPT).ok()?;
        let peer = peer::snapshot(&socket);
        Some(Self {
            socket,
            peer,
            closed: AtomicBool::new(false),
            inbound: AtomicBool::new(false),
            outbound: AtomicBool::new(false),
        })
    }

    /// The pid from the accept snapshot, or `Permission` with the snapshot's reason.
    pub(crate) fn peer_pid(&self) -> Result<u32> {
        self.peer
    }

    /// Inbound lane; `out` is not empty. `recv`: `>0` is the count; `0` is `Closed`
    /// if closed, else end of stream; `EAGAIN` polls `POLLIN` and re-checks
    /// `closed`; `ECONNRESET` is end of stream.
    pub(crate) fn read(&self, out: &mut [u8]) -> Result<usize> {
        let _lane = Lane::enter(&self.inbound, READ)?;
        loop {
            if self.is_closed() {
                return Err(closed(READ));
            }
            // SAFETY: socket is live and out is writable for its whole length.
            let count = unsafe {
                libc::recv(
                    self.socket.as_raw_fd(),
                    out.as_mut_ptr().cast(),
                    out.len(),
                    0,
                )
            };
            if let Ok(count) = usize::try_from(count) {
                return if count == 0 {
                    self.end_of_stream(READ, 0)
                } else {
                    Ok(count)
                };
            }
            match errno() {
                libc::EINTR => {}
                code if would_block(code) => self.wait(libc::POLLIN, READ)?,
                libc::ECONNRESET => return self.end_of_stream(READ, 0),
                code => return Err(self.failed(io_failure(READ, "recv", code))),
            }
        }
    }

    /// Outbound lane; `data` is not empty. Linux `send(MSG_NOSIGNAL)` only, never
    /// `write`; macOS `send` on the `SO_NOSIGPIPE` fd. `EAGAIN` polls `POLLOUT`;
    /// `EPIPE` or `ECONNRESET` is `Closed` if closed, else `PeerClosed`.
    pub(crate) fn write_all(&self, data: &[u8]) -> Result<()> {
        let _lane = Lane::enter(&self.outbound, WRITE)?;
        let mut rest = data;
        while !rest.is_empty() {
            if self.is_closed() {
                return Err(closed(WRITE));
            }
            // SAFETY: socket is live and rest is readable for its whole length.
            let count = unsafe {
                libc::send(
                    self.socket.as_raw_fd(),
                    rest.as_ptr().cast(),
                    rest.len(),
                    SEND_FLAGS,
                )
            };
            match usize::try_from(count) {
                Ok(0) => self.wait(libc::POLLOUT, WRITE)?,
                Ok(count) => rest = rest.get(count..).unwrap_or_default(),
                Err(_) => match errno() {
                    libc::EINTR => {}
                    code if would_block(code) => self.wait(libc::POLLOUT, WRITE)?,
                    code if is_peer_gone(code) => {
                        let failure = Failure::new(Error::PeerClosed, WRITE, "peer_closed")
                            .with_os_code(code);
                        return Err(self.failed(failure));
                    }
                    code => return Err(self.failed(io_failure(WRITE, "send", code))),
                },
            }
        }
        Ok(())
    }

    /// Inbound lane. `recv(&mut [0; 1], MSG_PEEK | MSG_DONTWAIT)`: `>0` is `Input`
    /// (the peeked byte is zeroized); `0` is `Closed` if closed, else `Eof`;
    /// `EAGAIN` polls, then loops.
    pub(crate) fn await_input(&self) -> Result<Readiness> {
        let _lane = Lane::enter(&self.inbound, AWAIT_INPUT)?;
        let mut byte = Zeroizing::new([0_u8; 1]);
        loop {
            if self.is_closed() {
                return Err(closed(AWAIT_INPUT));
            }
            // SAFETY: socket is live and byte is writable for its length.
            let count = unsafe {
                libc::recv(
                    self.socket.as_raw_fd(),
                    byte.as_mut_ptr().cast(),
                    byte.len(),
                    libc::MSG_PEEK | libc::MSG_DONTWAIT,
                )
            };
            if let Ok(count) = usize::try_from(count) {
                return if count == 0 {
                    self.end_of_stream(AWAIT_INPUT, Readiness::Eof)
                } else {
                    Ok(Readiness::Input)
                };
            }
            match errno() {
                libc::EINTR => {}
                code if would_block(code) => self.wait(libc::POLLIN, AWAIT_INPUT)?,
                libc::ECONNRESET => return self.end_of_stream(AWAIT_INPUT, Readiness::Eof),
                code => return Err(self.failed(io_failure(AWAIT_INPUT, "recv", code))),
            }
        }
    }

    /// Idempotent and non-blocking. `closed = true` (SeqCst), then
    /// `shutdown(fd, SHUT_WR)` and `shutdown(fd, SHUT_RD)` as two calls: XNU skips
    /// `SHUT_RDWR` when the read side is already shut, which would leave a writer
    /// asleep. Data already sent stays queued for the peer. `close(fd)` runs at the
    /// last drop, after Linux discards unread input there.
    pub(crate) fn close(&self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        for how in [libc::SHUT_WR, libc::SHUT_RD] {
            // SAFETY: socket is live. A failure, such as ENOTCONN for a peer that is
            // already gone, leaves nothing to wake, so it is ignored.
            unsafe { libc::shutdown(self.socket.as_raw_fd(), how) };
        }
    }

    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// `value` at the end of the stream, or `Closed` when this side closed it.
    fn end_of_stream<T>(&self, operation: &'static str, value: T) -> Result<T> {
        if self.is_closed() {
            Err(closed(operation))
        } else {
            Ok(value)
        }
    }

    /// `failure`, or `Closed` when this side closed the connection meanwhile.
    fn failed(&self, failure: Failure) -> Failure {
        if self.is_closed() {
            closed(failure.operation())
        } else {
            failure
        }
    }

    /// Blocks until the socket is ready for `events`. A local close wakes it: the
    /// shutdown state is level-triggered, and the caller re-checks `closed`.
    fn wait(&self, events: c_short, operation: &'static str) -> Result<()> {
        let mut descriptors = [poll_fd(&self.socket, events)];
        poll(&mut descriptors).map_err(|code| self.failed(io_failure(operation, "poll", code)))
    }
}

/// Linux resets a peer whose socket closes with unread input in its queue: the
/// peer reads what was sent, then gets `ECONNRESET` instead of end of stream. XNU
/// and Windows end the stream, as a rejected client that already wrote expects.
/// So the last drop, with no lane left, shuts the read side, which refuses any
/// further input, and discards what is queued before the fd closes.
#[cfg(any(target_os = "linux", target_os = "android"))]
impl Drop for Connection {
    fn drop(&mut self) {
        let fd = self.socket.as_raw_fd();
        // SAFETY: socket is live. A failure leaves the input to the cap below.
        unsafe { libc::shutdown(fd, libc::SHUT_RD) };
        let mut scratch = Zeroizing::new([0_u8; DISCARD_CHUNK]);
        let mut discarded = 0;
        while discarded < DISCARD_LIMIT {
            let count = retry(|| {
                // SAFETY: socket is live and scratch is writable for its length.
                unsafe {
                    libc::recv(
                        fd,
                        scratch.as_mut_ptr().cast(),
                        scratch.len(),
                        libc::MSG_DONTWAIT,
                    )
                }
            });
            // 0 is the end of the queue once the read side is shut; an error,
            // `EAGAIN` included, also ends it.
            match usize::try_from(count) {
                Ok(count) if count > 0 => discarded += count,
                _ => break,
            }
        }
    }
}

/// The socket file and its run directory, removed only while they are still the
/// entries this listener created. A failed bind drops it, which removes them.
struct Endpoint {
    path: String,
    parent: OwnedFd,
    directory: OwnedFd,
    name: CString,
    identity: Identity,
    socket: Option<Identity>,
    removed: AtomicBool,
}

impl Endpoint {
    /// Creates and opens the run directory, steps 1 to 3 of [`Listener::bind`].
    fn create(name: &UnixName<'_>) -> Result<Self> {
        let parent = open_parent(name.parent())?;
        let (directory_name, c_name, identity) = make_directory(&parent, name)?;
        let directory = open_directory(&parent, &c_name, identity).inspect_err(|_| {
            remove_entry(&parent, &c_name, identity, libc::AT_REMOVEDIR);
        })?;
        Ok(Self {
            path: name.socket_path(&directory_name),
            parent,
            directory,
            name: c_name,
            identity,
            socket: None,
            removed: AtomicBool::new(false),
        })
    }

    /// Binds `socket` to the path, then checks through the run directory's
    /// descriptor that the socket file landed in it, and protects it.
    fn bind(&mut self, socket: &OwnedFd) -> Result<()> {
        let (address, length) = socket_address(&self.path)?;
        // SAFETY: socket is live; address is initialized and length covers its
        // family and its path with the NUL.
        if unsafe { libc::bind(socket.as_raw_fd(), (&raw const address).cast(), length) } != 0 {
            let code = errno();
            return Err(if code == libc::EADDRINUSE {
                Failure::new(Error::Permission, LISTEN, "name_in_use").with_os_code(code)
            } else {
                io_failure(LISTEN, "bind", code)
            });
        }
        // `bind` resolved the path by name again; a replaced parent would send the
        // socket file elsewhere.
        match status_at(&self.directory, SOCKET_FILE) {
            Ok(status) if is_owned_socket(&status) => self.socket = Some(Identity::of(&status)),
            Ok(_) | Err(libc::ENOENT) => {
                return Err(Failure::new(Error::Permission, LISTEN, "bind_escaped"));
            }
            Err(code) => return Err(io_failure(LISTEN, "fstatat", code)),
        }
        // SAFETY: directory is live and SOCKET_FILE is NUL-terminated.
        if unsafe { libc::fchmodat(self.directory.as_raw_fd(), SOCKET_FILE.as_ptr(), 0o600, 0) }
            != 0
        {
            return Err(Failure::last_os_error(Error::Io, LISTEN, "fchmodat"));
        }
        Ok(())
    }

    /// Removes the socket file, then the run directory, each only while it is
    /// still the entry this endpoint created. Once.
    fn remove(&self) {
        if self.removed.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Some(socket) = self.socket {
            remove_entry(&self.directory, SOCKET_FILE, socket, 0);
        }
        remove_entry(&self.parent, &self.name, self.identity, libc::AT_REMOVEDIR);
    }
}

impl Drop for Endpoint {
    fn drop(&mut self) {
        self.remove();
    }
}

/// `open(parent, O_DIRECTORY | O_CLOEXEC)`. It follows symbolic links on purpose.
fn open_parent(parent: &str) -> Result<OwnedFd> {
    let path = CString::new(parent)
        .map_err(|_| Failure::new(Error::InvalidArgument, LISTEN, "invalid_parent"))?;
    let fd = retry(|| {
        // SAFETY: path is NUL-terminated.
        unsafe {
            libc::open(
                path.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        }
    });
    adopt(fd).ok_or_else(|| Failure::last_os_error(Error::Io, LISTEN, "open"))
}

/// `mkdirat(parent, name, 0700)`, then the identity of what it created. A random
/// name that is taken is retried with a new one; an exact one is `Permission`.
fn make_directory(parent: &OwnedFd, name: &UnixName<'_>) -> Result<(String, CString, Identity)> {
    let attempts = if name.is_exact() {
        1
    } else {
        DIRECTORY_ATTEMPTS
    };
    for _ in 0..attempts {
        let directory = name.directory()?;
        let c_name = CString::new(directory.as_str())
            .map_err(|_| Failure::new(Error::InvalidArgument, LISTEN, "invalid_name"))?;
        let made = retry(|| {
            // SAFETY: parent is live and c_name is NUL-terminated.
            unsafe { libc::mkdirat(parent.as_raw_fd(), c_name.as_ptr(), 0o700) }
        });
        if made == 0 {
            let status =
                status_at(parent, &c_name).map_err(|code| io_failure(LISTEN, "fstatat", code))?;
            let identity = Identity::of(&status);
            if !is_private_directory(&status) {
                // A file system that ignores modes or owners, for one.
                remove_entry(parent, &c_name, identity, libc::AT_REMOVEDIR);
                return Err(directory_unsafe());
            }
            return Ok((directory, c_name, identity));
        }
        let code = errno();
        if code != libc::EEXIST {
            return Err(io_failure(LISTEN, "mkdirat", code));
        }
    }
    Err(Failure::new(Error::Permission, LISTEN, "name_in_use").with_os_code(libc::EEXIST))
}

/// `openat(parent, name, O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)`, then `fstat`: the
/// private directory `mkdirat` created.
///
/// An entry swapped since, for a symbolic link (`ELOOP`), a file (`ENOTDIR`) or
/// another directory, is `Permission "directory_unsafe"`. The directory `mkdirat`
/// made was moved away by then; it is left there, since its name no longer finds
/// it safely.
fn open_directory(parent: &OwnedFd, name: &CStr, identity: Identity) -> Result<OwnedFd> {
    let fd = retry(|| {
        // SAFETY: parent is live and name is NUL-terminated.
        unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        }
    });
    let Some(directory) = adopt(fd) else {
        let code = errno();
        return Err(match code {
            libc::ELOOP | libc::ENOTDIR => directory_unsafe().with_os_code(code),
            _ => io_failure(LISTEN, "openat", code),
        });
    };
    let status = status(&directory).map_err(|code| io_failure(LISTEN, "fstat", code))?;
    if !is_private_directory(&status) || Identity::of(&status) != identity {
        return Err(directory_unsafe());
    }
    Ok(directory)
}

fn directory_unsafe() -> Failure {
    Failure::new(Error::Permission, LISTEN, "directory_unsafe")
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn new_socket() -> Result<OwnedFd> {
    // SAFETY: socket takes integer arguments only.
    let fd = unsafe {
        libc::socket(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
            0,
        )
    };
    let socket = adopt(fd).ok_or_else(|| Failure::last_os_error(Error::Io, LISTEN, "socket"))?;
    // `bind` creates the file with this mode less the umask, never wider.
    // SAFETY: socket is live.
    if unsafe { libc::fchmod(socket.as_raw_fd(), 0o600) } != 0 {
        return Err(Failure::last_os_error(Error::Io, LISTEN, "fchmod"));
    }
    Ok(socket)
}

#[cfg(target_os = "macos")]
fn new_socket() -> Result<OwnedFd> {
    // SAFETY: socket takes integer arguments only.
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    let socket = adopt(fd).ok_or_else(|| Failure::last_os_error(Error::Io, LISTEN, "socket"))?;
    configure_socket(&socket, LISTEN)?;
    Ok(socket)
}

/// Close-on-exec, non-blocking and without SIGPIPE: macOS has no flags for them
/// at creation.
#[cfg(target_os = "macos")]
fn configure_socket(socket: &OwnedFd, operation: &'static str) -> Result<()> {
    configure_descriptor(socket, operation)?;
    let enabled: c_int = 1;
    // SAFETY: socket is live and the option value is one c_int of the given length.
    let result = unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_NOSIGPIPE,
            (&raw const enabled).cast(),
            size_of::<c_int>() as libc::socklen_t,
        )
    };
    if result != 0 {
        return Err(Failure::last_os_error(Error::Io, operation, "setsockopt"));
    }
    Ok(())
}

/// `FD_CLOEXEC` and `O_NONBLOCK`.
#[cfg(target_os = "macos")]
fn configure_descriptor(fd: &OwnedFd, operation: &'static str) -> Result<()> {
    // SAFETY: fd is live; F_SETFD takes an integer argument.
    if unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC) } != 0 {
        return Err(Failure::last_os_error(Error::Io, operation, "fcntl"));
    }
    // SAFETY: fd is live; F_GETFL takes no argument.
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    if flags < 0 {
        return Err(Failure::last_os_error(Error::Io, operation, "fcntl"));
    }
    // SAFETY: fd is live; F_SETFL takes an integer argument.
    if unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } != 0 {
        return Err(Failure::last_os_error(Error::Io, operation, "fcntl"));
    }
    Ok(())
}

/// The wake pipe's read and write ends.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn wake_pipe() -> Result<(OwnedFd, OwnedFd)> {
    let mut fds: [c_int; 2] = [-1; 2];
    // SAFETY: fds is writable storage for two descriptors.
    if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC | libc::O_NONBLOCK) } != 0 {
        return Err(Failure::last_os_error(Error::Io, LISTEN, "pipe2"));
    }
    pipe_ends(fds)
}

/// The wake pipe's read and write ends.
#[cfg(target_os = "macos")]
fn wake_pipe() -> Result<(OwnedFd, OwnedFd)> {
    let mut fds: [c_int; 2] = [-1; 2];
    // SAFETY: fds is writable storage for two descriptors.
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        return Err(Failure::last_os_error(Error::Io, LISTEN, "pipe"));
    }
    let (read, write) = pipe_ends(fds)?;
    configure_descriptor(&read, LISTEN)?;
    configure_descriptor(&write, LISTEN)?;
    // SAFETY: write is live; F_SETNOSIGPIPE takes an integer argument.
    if unsafe { libc::fcntl(write.as_raw_fd(), F_SETNOSIGPIPE, 1) } != 0 {
        return Err(Failure::last_os_error(Error::Io, LISTEN, "fcntl"));
    }
    Ok((read, write))
}

fn pipe_ends([read, write]: [c_int; 2]) -> Result<(OwnedFd, OwnedFd)> {
    match (adopt(read), adopt(write)) {
        (Some(read), Some(write)) => Ok((read, write)),
        _ => Err(Failure::new(Error::Internal, LISTEN, "pipe")),
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn accept_socket(listener: &OwnedFd) -> Os<OwnedFd> {
    // SAFETY: listener is live; null address pointers ask for no peer address.
    let fd = unsafe {
        libc::accept4(
            listener.as_raw_fd(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
        )
    };
    adopt(fd).ok_or_else(errno)
}

#[cfg(target_os = "macos")]
fn accept_socket(listener: &OwnedFd) -> Os<OwnedFd> {
    // SAFETY: listener is live; null address pointers ask for no peer address.
    let fd = unsafe {
        libc::accept(
            listener.as_raw_fd(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    };
    adopt(fd).ok_or_else(errno)
}

/// `sockaddr_un` for `path` and its length, with `sun_len` on macOS.
fn socket_address(path: &str) -> Result<(libc::sockaddr_un, libc::socklen_t)> {
    // SAFETY: all-zero bytes are a valid sockaddr_un.
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    let bytes = path.as_bytes();
    // The name rules keep the path shorter already; `sun_path` also holds the NUL.
    if bytes.len() >= address.sun_path.len() || bytes.contains(&0) {
        return Err(Failure::new(
            Error::InvalidArgument,
            LISTEN,
            "path_too_long",
        ));
    }
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (slot, byte) in address.sun_path.iter_mut().zip(bytes) {
        *slot = libc::c_char::from_ne_bytes([*byte]);
    }
    let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + bytes.len() + 1;
    #[cfg(target_os = "macos")]
    {
        address.sun_len = length as u8;
    }
    Ok((address, length as libc::socklen_t))
}

/// A file's `(st_dev, st_ino)`: tells a created entry from its replacement.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Identity {
    device: libc::dev_t,
    inode: libc::ino_t,
}

impl Identity {
    fn of(status: &libc::stat) -> Self {
        Self {
            device: status.st_dev,
            inode: status.st_ino,
        }
    }
}

/// `fstat(fd)`.
fn status(fd: &OwnedFd) -> Os<libc::stat> {
    let mut status = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: fd is live and status is writable storage for one stat.
    if unsafe { libc::fstat(fd.as_raw_fd(), status.as_mut_ptr()) } != 0 {
        return Err(errno());
    }
    // SAFETY: a successful fstat initialized the whole structure.
    Ok(unsafe { status.assume_init() })
}

/// `fstatat(at, name, AT_SYMLINK_NOFOLLOW)`: the entry itself, never a link's target.
fn status_at(at: &OwnedFd, name: &CStr) -> Os<libc::stat> {
    let mut status = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: at is live, name is NUL-terminated and status is writable storage for
    // one stat.
    let result = unsafe {
        libc::fstatat(
            at.as_raw_fd(),
            name.as_ptr(),
            status.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    if result != 0 {
        return Err(errno());
    }
    // SAFETY: a successful fstatat initialized the whole structure.
    Ok(unsafe { status.assume_init() })
}

fn is_private_directory(status: &libc::stat) -> bool {
    (status.st_mode & libc::S_IFMT) == libc::S_IFDIR
        && status.st_uid == effective_uid()
        && (status.st_mode & 0o077) == 0
}

fn is_owned_socket(status: &libc::stat) -> bool {
    (status.st_mode & libc::S_IFMT) == libc::S_IFSOCK && status.st_uid == effective_uid()
}

/// Unlinks `name` in `at` only while it is still the entry `expected` identifies.
fn remove_entry(at: &OwnedFd, name: &CStr, expected: Identity, flags: c_int) {
    if status_at(at, name).is_ok_and(|status| Identity::of(&status) == expected) {
        // SAFETY: at is live and name is NUL-terminated. A failure, such as a run
        // directory that is not empty, leaves the entry.
        unsafe { libc::unlinkat(at.as_raw_fd(), name.as_ptr(), flags) };
    }
}

/// One call at a time on a lane; a concurrent one is `Busy`.
struct Lane<'a>(&'a AtomicBool);

impl<'a> Lane<'a> {
    fn enter(busy: &'a AtomicBool, operation: &'static str) -> Result<Self> {
        busy.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map(|_| Self(busy))
            .map_err(|_| Failure::new(Error::Busy, operation, "busy"))
    }
}

impl Drop for Lane<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

fn poll_fd(fd: &OwnedFd, events: c_short) -> libc::pollfd {
    libc::pollfd {
        fd: fd.as_raw_fd(),
        events,
        revents: 0,
    }
}

/// Blocks until one of `descriptors` is ready.
fn poll(descriptors: &mut [libc::pollfd]) -> Os<()> {
    let result = retry(|| {
        // SAFETY: descriptors is a live, writable array of exactly its length.
        unsafe {
            libc::poll(
                descriptors.as_mut_ptr(),
                descriptors.len() as libc::nfds_t,
                -1,
            )
        }
    });
    if result < 0 { Err(errno()) } else { Ok(()) }
}

/// Repeats a system call that a signal interrupted. `errno` stays the last call's.
fn retry<T: PartialEq + From<i8>>(mut call: impl FnMut() -> T) -> T {
    loop {
        let result = call();
        if result != T::from(-1) || errno() != libc::EINTR {
            return result;
        }
    }
}

/// Owns `fd`, the result of a call that creates a descriptor; `None` if it failed.
fn adopt(fd: RawFd) -> Option<OwnedFd> {
    if fd < 0 {
        return None;
    }
    // SAFETY: a non-negative result of a call that creates a descriptor is a new
    // descriptor that nothing else owns.
    Some(unsafe { OwnedFd::from_raw_fd(fd) })
}

fn errno() -> c_int {
    io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

fn would_block(code: c_int) -> bool {
    code == libc::EAGAIN || code == libc::EWOULDBLOCK
}

/// The peer closed its side, or is closing it. XNU can report a socket that is
/// being torn down as `EPROTOTYPE`.
fn is_peer_gone(code: c_int) -> bool {
    code == libc::EPIPE
        || code == libc::ECONNRESET
        || (cfg!(target_os = "macos") && code == libc::EPROTOTYPE)
}

fn effective_uid() -> libc::uid_t {
    // SAFETY: geteuid has no arguments and cannot fail.
    unsafe { libc::geteuid() }
}

fn closed(operation: &'static str) -> Failure {
    Failure::new(Error::Closed, operation, "closed")
}

fn io_failure(operation: &'static str, reason: &'static str, code: c_int) -> Failure {
    Failure::new(Error::Io, operation, reason).with_os_code(code)
}

#[cfg(test)]
mod tests {
    use std::{
        fs,
        os::unix::fs::{DirBuilderExt, MetadataExt, symlink},
        os::unix::net::UnixStream,
        path::Path,
    };

    use super::*;

    fn listener(parent: &tempfile::TempDir) -> Listener {
        let parent = parent.path().to_str().unwrap();
        Listener::bind(UnixName::new(parent, "kg-", None).unwrap()).unwrap()
    }

    fn descriptor_flags(fd: &OwnedFd) -> c_int {
        // SAFETY: F_GETFD takes no argument.
        unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) }
    }

    fn status_flags(fd: &OwnedFd) -> c_int {
        // SAFETY: F_GETFL takes no argument.
        unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) }
    }

    #[test]
    fn socket_file_is_the_name_rules_one() {
        assert_eq!(SOCKET_FILE.to_str(), Ok(crate::name::UNIX_SOCKET_FILE));
    }

    #[test]
    fn accept_retries_transient_errors_and_returns_the_others() {
        let parent = tempfile::tempdir().unwrap();
        let listener = listener(&parent);
        let (server, _client) = UnixStream::pair().unwrap();
        let mut incoming = vec![
            Err(libc::ECONNABORTED),
            Err(libc::EINTR),
            Err(libc::ECONNRESET),
            Err(libc::EPROTO),
            Ok(OwnedFd::from(server)),
        ]
        .into_iter();
        let connection = listener.accept_with(|| incoming.next().unwrap());
        assert!(connection.is_ok());
        assert!(incoming.next().is_none());

        for code in [
            libc::EMFILE,
            libc::ENFILE,
            libc::ENOBUFS,
            libc::ENOMEM,
            libc::EBADF,
            libc::EINVAL,
        ] {
            let mut calls = 0;
            let failure = listener
                .accept_with(|| {
                    calls += 1;
                    Err(code)
                })
                .err()
                .unwrap();
            assert_eq!(calls, 1);
            assert_eq!(failure.kind(), Error::Io);
            assert_eq!(failure.operation(), "accept");
            assert_eq!(failure.os_code(), Some(code));
        }

        listener.close();
        let failure = listener.accept_with(|| unreachable!()).err().unwrap();
        assert_eq!(failure.kind(), Error::Closed);
    }

    #[test]
    fn wake_pipe_is_close_on_exec_nonblocking_and_quiet() {
        let parent = tempfile::tempdir().unwrap();
        let listener = listener(&parent);
        for end in [&listener.wake_read, &listener.wake_write] {
            assert_ne!(descriptor_flags(end) & libc::FD_CLOEXEC, 0);
            assert_ne!(status_flags(end) & libc::O_NONBLOCK, 0);
        }
        #[cfg(target_os = "macos")]
        {
            // `F_GETNOSIGPIPE` from XNU `<sys/fcntl.h>`.
            const F_GETNOSIGPIPE: c_int = 74;
            // SAFETY: F_GETNOSIGPIPE takes no argument.
            let quiet = unsafe { libc::fcntl(listener.wake_write.as_raw_fd(), F_GETNOSIGPIPE) };
            assert_eq!(quiet, 1);
        }
        listener.close();
    }

    #[test]
    fn listener_holds_its_parent_and_run_directory_open() {
        let parent = tempfile::tempdir().unwrap();
        let listener = listener(&parent);
        let run = Path::new(listener.endpoint()).parent().unwrap();

        let held = status(&listener.endpoint.directory).unwrap();
        assert_eq!(Identity::of(&held), listener.endpoint.identity);
        assert_eq!(held.st_ino, fs::symlink_metadata(run).unwrap().ino());
        let held = status(&listener.endpoint.parent).unwrap();
        assert_eq!(held.st_ino, fs::metadata(parent.path()).unwrap().ino());

        for fd in [
            &listener.endpoint.parent,
            &listener.endpoint.directory,
            &listener.socket,
        ] {
            assert_ne!(descriptor_flags(fd) & libc::FD_CLOEXEC, 0);
        }
        assert_ne!(status_flags(&listener.socket) & libc::O_NONBLOCK, 0);
        listener.close();
        assert!(fs::symlink_metadata(run).is_err());
    }

    /// The run directory's entry swapped between `mkdirat` and `openat` is unsafe,
    /// whatever replaced it; only an entry that is gone stays an I/O failure.
    #[test]
    fn a_swapped_run_directory_is_unsafe() {
        let parent = tempfile::tempdir().unwrap();
        let target = parent.path().join("target");
        fs::create_dir(&target).unwrap();
        symlink(&target, parent.path().join("link")).unwrap();
        symlink(
            parent.path().join("missing"),
            parent.path().join("dangling"),
        )
        .unwrap();
        fs::write(parent.path().join("file"), b"").unwrap();
        fs::DirBuilder::new()
            .mode(0o700)
            .create(parent.path().join("other"))
            .unwrap();
        let parent = open_parent(parent.path().to_str().unwrap()).unwrap();
        // What `mkdirat` created: the identity none of the entries has.
        let created = Identity::of(&status(&parent).unwrap());

        for name in [c"link", c"dangling", c"file", c"other"] {
            let failure = open_directory(&parent, name, created).unwrap_err();
            assert_eq!(failure.kind(), Error::Permission, "{name:?}: {failure}");
            assert_eq!(failure.operation(), "listen");
            assert_eq!(failure.reason(), "directory_unsafe");
        }
        let failure = open_directory(&parent, c"missing", created).unwrap_err();
        assert_eq!(failure.kind(), Error::Io, "{failure}");
        assert_eq!(failure.reason(), "openat");
        assert_eq!(failure.os_code(), Some(libc::ENOENT));

        let other = Identity::of(&status_at(&parent, c"other").unwrap());
        assert!(open_directory(&parent, c"other", other).is_ok());
    }

    #[test]
    fn lanes_are_busy_while_taken() {
        let busy = AtomicBool::new(false);
        let lane = Lane::enter(&busy, "read").ok();
        let failure = Lane::enter(&busy, "await_input").err().unwrap();
        assert_eq!(failure.kind(), Error::Busy);
        assert_eq!(failure.to_string(), "await_input: busy");
        drop(lane);
        assert!(Lane::enter(&busy, "read").is_ok());
    }
}
