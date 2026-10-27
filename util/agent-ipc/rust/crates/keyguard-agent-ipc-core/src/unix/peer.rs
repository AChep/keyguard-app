//! The kernel snapshot of an accepted peer (plan §4.2, §5.1 items 4 and 5, §5.2).
//! Taken right after accept, before any I/O:
//!
//! - Linux and Android: `SO_PEERCRED` with `len == 12`, `pid > 0` and
//!   `uid == geteuid()`. From Linux 6.5, `SO_PEERPIDFD` also rejects a peer whose
//!   process has exited, such as a client that queued a connection and is gone
//!   when it is accepted: another process may already have its pid. Older kernels
//!   (`ENOPROTOOPT`) rely on `SO_PEERCRED` alone.
//! - macOS: `getsockopt(0 /* SOL_LOCAL */, 3 /* LOCAL_PEEREPID */)` with
//!   `len == 4` and `pid > 0`, plus `getpeereid` with `uid == geteuid()`. The
//!   constants are defined here and pinned by a test.
//!
//! Any credential failure, `ENOTCONN` included, makes the connection's `peer_pid`
//! `Permission`, never an accept failure. The decision takes the expected uid as
//! an argument so tests can inject another one.

#[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
compile_error!("keyguard-agent-ipc-core verifies peers on Linux, Android and macOS only");

use std::os::fd::{AsRawFd, OwnedFd};

use libc::c_int;

use crate::{Error, Failure, Result};

const PEER_PID: &str = "peer_pid";

/// `sizeof(struct ucred)`: pid, uid and gid.
#[cfg(any(target_os = "linux", target_os = "android"))]
const UCRED_LEN: usize = 12;
/// `SO_PEERPIDFD` from Linux 6.5 `<asm-generic/socket.h>`. The libc crate has no
/// Android one.
#[cfg(any(target_os = "linux", target_os = "android"))]
const SO_PEERPIDFD: c_int = 77;

/// `SOL_LOCAL` from XNU `<sys/un.h>`.
#[cfg(target_os = "macos")]
const SOL_LOCAL: c_int = 0;
/// `LOCAL_PEEREPID` from XNU `<sys/un.h>`: the peer's effective pid, as the
/// helpers' own server check uses. Not `LOCAL_PEERPID` (2).
#[cfg(target_os = "macos")]
const LOCAL_PEEREPID: c_int = 3;
/// `sizeof(pid_t)`.
#[cfg(target_os = "macos")]
const PID_LEN: usize = 4;

/// What the kernel recorded about the process that connected.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Credentials {
    pid: libc::pid_t,
    uid: libc::uid_t,
}

/// The peer's pid, or `Permission` with the reason it cannot be verified.
pub(super) fn snapshot(socket: &OwnedFd) -> Result<u32> {
    let pid = verify(credentials(socket)?, super::effective_uid())?;
    #[cfg(any(target_os = "linux", target_os = "android"))]
    ensure_running(socket)?;
    Ok(pid)
}

/// Accepts a peer of `expected_uid` with a positive pid.
fn verify(credentials: Credentials, expected_uid: libc::uid_t) -> Result<u32> {
    if credentials.uid != expected_uid {
        return Err(rejected("foreign_uid"));
    }
    match u32::try_from(credentials.pid) {
        Ok(pid) if pid > 0 => Ok(pid),
        _ => Err(rejected("invalid_pid")),
    }
}

fn rejected(reason: &'static str) -> Failure {
    Failure::new(Error::Permission, PEER_PID, reason)
}

/// `Permission` with the OS code of the call that just failed.
fn unreadable(reason: &'static str) -> Failure {
    Failure::last_os_error(Error::Permission, PEER_PID, reason)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn credentials(socket: &OwnedFd) -> Result<Credentials> {
    // SAFETY: all-zero bytes are a valid ucred.
    let mut credentials: libc::ucred = unsafe { std::mem::zeroed() };
    let mut length = size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: socket is live, and credentials is writable storage of `length` bytes.
    let result = unsafe {
        libc::getsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&raw mut credentials).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(unreadable("so_peercred"));
    }
    if length as usize != UCRED_LEN {
        return Err(rejected("so_peercred"));
    }
    Ok(Credentials {
        pid: credentials.pid,
        uid: credentials.uid,
    })
}

#[cfg(target_os = "macos")]
fn credentials(socket: &OwnedFd) -> Result<Credentials> {
    let mut pid: libc::pid_t = 0;
    let mut length = size_of::<libc::pid_t>() as libc::socklen_t;
    // SAFETY: socket is live, and pid is writable storage of `length` bytes.
    let result = unsafe {
        libc::getsockopt(
            socket.as_raw_fd(),
            SOL_LOCAL,
            LOCAL_PEEREPID,
            (&raw mut pid).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(unreadable("local_peerepid"));
    }
    if length as usize != PID_LEN {
        return Err(rejected("local_peerepid"));
    }
    let mut uid: libc::uid_t = 0;
    let mut gid: libc::gid_t = 0;
    // SAFETY: socket is live, and uid and gid are writable.
    if unsafe { libc::getpeereid(socket.as_raw_fd(), &mut uid, &mut gid) } != 0 {
        return Err(unreadable("getpeereid"));
    }
    Ok(Credentials { pid, uid })
}

/// Rejects a peer whose process has exited. The pidfd closes on return.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn ensure_running(socket: &OwnedFd) -> Result<()> {
    let Some(pidfd) = peer_pidfd(socket)? else {
        return Ok(());
    };
    let mut descriptor = libc::pollfd {
        fd: pidfd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    let ready = super::retry(|| {
        // SAFETY: descriptor is one live pollfd; a zero timeout never blocks.
        unsafe { libc::poll(&mut descriptor, 1, 0) }
    });
    if ready < 0 {
        return Err(unreadable("poll"));
    }
    // A pidfd becomes readable when its process exits.
    if descriptor.revents != 0 {
        return Err(rejected("peer_exited"));
    }
    Ok(())
}

/// A close-on-exec pidfd of the peer's process, or `None` on kernels before 6.5.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn peer_pidfd(socket: &OwnedFd) -> Result<Option<OwnedFd>> {
    let code = if forced_fallback() {
        libc::ENOPROTOOPT
    } else {
        let mut pidfd: c_int = -1;
        let mut length = size_of::<c_int>() as libc::socklen_t;
        // SAFETY: socket is live, and pidfd is writable storage of `length` bytes. On
        // success the kernel installs a new descriptor, which `adopt` then owns.
        let result = unsafe {
            libc::getsockopt(
                socket.as_raw_fd(),
                libc::SOL_SOCKET,
                SO_PEERPIDFD,
                (&raw mut pidfd).cast(),
                &mut length,
            )
        };
        if result == 0 {
            let pidfd = super::adopt(pidfd).ok_or_else(|| rejected("so_peerpidfd"))?;
            if length as usize != size_of::<c_int>() {
                return Err(rejected("so_peerpidfd"));
            }
            ensure_close_on_exec(&pidfd)?;
            return Ok(Some(pidfd));
        }
        super::errno()
    };
    match code {
        libc::ENOPROTOOPT => Ok(None),
        // The peer's process is gone already: it was reaped before accept.
        libc::EINVAL | libc::ESRCH => Err(rejected("peer_exited").with_os_code(code)),
        _ => Err(rejected("so_peerpidfd").with_os_code(code)),
    }
}

/// The kernel creates the pidfd close-on-exec; this makes sure of it.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn ensure_close_on_exec(fd: &OwnedFd) -> Result<()> {
    // SAFETY: fd is live; F_GETFD takes no argument.
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) };
    if flags < 0 {
        return Err(unreadable("fcntl"));
    }
    if flags & libc::FD_CLOEXEC != 0 {
        return Ok(());
    }
    // SAFETY: fd is live; F_SETFD takes an integer argument.
    if unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFD, flags | libc::FD_CLOEXEC) } != 0 {
        return Err(unreadable("fcntl"));
    }
    Ok(())
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
thread_local! {
    /// Makes this thread take the path of kernels without `SO_PEERPIDFD`.
    static FORCE_FALLBACK: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// A test hook: whether `SO_PEERPIDFD` reports `ENOPROTOOPT` without asking.
#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
fn forced_fallback() -> bool {
    FORCE_FALLBACK.get()
}

#[cfg(all(not(test), any(target_os = "linux", target_os = "android")))]
fn forced_fallback() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use std::os::unix::net::{UnixListener, UnixStream};

    use super::*;

    #[test]
    fn credentials_decide_by_uid_and_pid() {
        let uid = 501;
        let peer = |pid, uid| Credentials { pid, uid };
        assert_eq!(verify(peer(42, uid), uid), Ok(42));
        for (peer, reason) in [
            (peer(42, uid + 1), "foreign_uid"),
            (peer(42, 0), "foreign_uid"),
            (peer(0, uid), "invalid_pid"),
            (peer(-1, uid), "invalid_pid"),
        ] {
            let failure = verify(peer, uid).unwrap_err();
            assert_eq!(failure.kind(), Error::Permission);
            assert_eq!(failure.operation(), "peer_pid");
            assert_eq!(failure.reason(), reason);
        }
    }

    #[test]
    fn own_process_is_verified() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("s");
        let listener = UnixListener::bind(&path).unwrap();
        let _client = UnixStream::connect(&path).unwrap();
        let (server, _) = listener.accept().unwrap();
        assert_eq!(snapshot(&OwnedFd::from(server)), Ok(std::process::id()));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn constants_match_xnu() {
        assert_eq!((SOL_LOCAL, LOCAL_PEEREPID), (0, 3));
        assert_eq!(SOL_LOCAL, libc::SOL_LOCAL);
        assert_eq!(LOCAL_PEEREPID, libc::LOCAL_PEEREPID);
        assert_eq!(size_of::<libc::pid_t>(), PID_LEN);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn constants_match_linux() {
        assert_eq!(SO_PEERPIDFD, 77);
        assert_eq!(SO_PEERPIDFD, libc::SO_PEERPIDFD);
        assert_eq!(libc::SO_PEERCRED, 17);
        assert_eq!(size_of::<libc::ucred>(), UCRED_LEN);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn peer_pidfd_is_close_on_exec_and_quiet_while_the_peer_runs() {
        let (server, _client) = UnixStream::pair().unwrap();
        let server = OwnedFd::from(server);
        let Some(pidfd) = peer_pidfd(&server).unwrap() else {
            // A kernel before 6.5.
            return;
        };
        // SAFETY: F_GETFD takes no argument.
        let flags = unsafe { libc::fcntl(pidfd.as_raw_fd(), libc::F_GETFD) };
        assert!(flags >= 0);
        assert_ne!(flags & libc::FD_CLOEXEC, 0);
        assert_eq!(ensure_running(&server), Ok(()));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn exited_peer_is_rejected_and_the_fallback_reads_credentials_only() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("s");
        let listener = UnixListener::bind(&path).unwrap();
        let child = connect_from_exited_child(path.to_str().unwrap());
        let (server, _) = listener.accept().unwrap();
        let server = OwnedFd::from(server);

        FORCE_FALLBACK.set(true);
        let fallback = snapshot(&server);
        FORCE_FALLBACK.set(false);
        assert_eq!(fallback, Ok(child));

        let (probe, _peer) = UnixStream::pair().unwrap();
        if peer_pidfd(&OwnedFd::from(probe)).unwrap().is_some() {
            let failure = snapshot(&server).unwrap_err();
            assert_eq!(failure.kind(), Error::Permission);
            assert_eq!(failure.reason(), "peer_exited");
        } else {
            assert_eq!(snapshot(&server), Ok(child));
        }
    }

    /// Forks a child that connects to `path` and exits, reaps it, and returns its
    /// pid. Its connection waits in the listener's queue.
    #[cfg(target_os = "linux")]
    fn connect_from_exited_child(path: &str) -> u32 {
        let (address, length) = super::super::socket_address(path).unwrap();
        // SAFETY: the child calls only async-signal-safe functions (socket, connect
        // and _exit), so no lock another thread held at the fork can block it.
        let pid = unsafe { libc::fork() };
        assert!(pid >= 0, "fork failed");
        if pid == 0 {
            // SAFETY: as above; address is initialized and length is its size.
            unsafe {
                let socket = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
                let connected = libc::connect(socket, (&raw const address).cast(), length);
                libc::_exit(i32::from(socket < 0 || connected != 0));
            }
        }
        let mut status = 0;
        // SAFETY: pid is this process's child and status is writable.
        assert_eq!(unsafe { libc::waitpid(pid, &mut status, 0) }, pid);
        assert!(
            libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0,
            "the child could not connect"
        );
        u32::try_from(pid).unwrap()
    }
}
