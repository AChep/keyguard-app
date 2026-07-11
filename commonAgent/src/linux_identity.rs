//! Linux caller identity derived directly from an accepted Unix socket.
//!
//! A numeric PID is not sufficient authorization evidence because it can be
//! reused after the original process exits.  Linux 6.5 added
//! `SO_PEERPIDFD`, which lets a server obtain a pidfd for the process that
//! connected the socket without a userspace PID lookup race.  This module
//! deliberately returns an error on older kernels; callers must retain their
//! per-connection principal rather than weakening the evidence. Successful
//! process-scoped identities retain both the pidfd and an `O_PATH` handle for
//! the accepted executable and require per-request revalidation.

#![cfg(target_os = "linux")]

use crate::{SubjectFingerprint, VerifiedSubject, VerifiedSubjectKind, PRINCIPAL_FINGERPRINT_LEN};
use sha2::{Digest, Sha256};
use std::ffi::CString;
use std::fs;
use std::io;
use std::mem::{size_of, MaybeUninit};
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};
use thiserror::Error;

const LINUX_PROCESS_FINGERPRINT_DOMAIN: &[u8] = b"keyguard-agent-linux-process-subject-v2\0";
const LINUX_APPLICATION_PROCESS_FINGERPRINT_DOMAIN: &[u8] =
    b"keyguard-agent-linux-application-process-subject-v1\0";
const LINUX_TERMINAL_SESSION_FINGERPRINT_DOMAIN: &[u8] =
    b"keyguard-agent-linux-terminal-session-subject-v1\0";
const MAX_BOOT_ID_LEN: usize = 64;
const MAX_ANCESTRY_DEPTH: usize = 16;
const MAX_CGROUP_PATH_LEN: usize = 4 * 1024;

/// Canonical namespace identity read through `/proc/PID/ns/*`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LinuxNamespaceMetadata {
    /// Device containing the namespace pseudo-inode.
    pub device: u64,
    /// Namespace pseudo-inode number.
    pub inode: u64,
}

/// Static address-space metadata that changes when a process replaces its
/// image with `execve(2)` under ordinary Linux address randomization.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LinuxMemoryLayoutMetadata {
    /// Beginning of executable code.
    pub start_code: u64,
    /// End of executable code.
    pub end_code: u64,
    /// Initial stack address.
    pub start_stack: u64,
    /// Beginning of initialized and uninitialized data.
    pub start_data: u64,
    /// End of initialized and uninitialized data.
    pub end_data: u64,
    /// Initial program-break address.
    pub start_brk: u64,
    /// Beginning of the argument block.
    pub arg_start: u64,
    /// End of the argument block.
    pub arg_end: u64,
    /// Beginning of the environment block.
    pub env_start: u64,
    /// End of the environment block.
    pub env_end: u64,
}

/// Canonical metadata for the executable image mapped by the peer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LinuxExecutableMetadata {
    /// Filesystem device containing the executable.
    pub device: u64,
    /// Executable inode number.
    pub inode: u64,
    /// File type and permission bits.
    pub mode: u32,
    /// Executable owner UID.
    pub uid: u32,
    /// Executable owner GID.
    pub gid: u32,
    /// Hard-link count.
    pub link_count: u64,
    /// Executable size in bytes.
    pub size: u64,
    /// Last modification timestamp seconds.
    pub mtime_seconds: i64,
    /// Last modification timestamp nanoseconds.
    pub mtime_nanoseconds: i64,
    /// Last inode-status change timestamp seconds.
    pub ctime_seconds: i64,
    /// Last inode-status change timestamp nanoseconds.
    pub ctime_nanoseconds: i64,
}

/// Immutable process/executable snapshot accepted for process-scoped reuse.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinuxProcessMetadata {
    /// Process start time in kernel clock ticks since boot.
    pub start_time_ticks: u64,
    /// Kernel boot identifier that gives start-time ticks a boot-local domain.
    pub boot_id: Box<str>,
    /// Real, effective, saved-set, and filesystem UIDs from `/proc/PID/status`.
    pub uids: [u32; 4],
    /// Real, effective, saved-set, and filesystem GIDs from `/proc/PID/status`.
    pub gids: [u32; 4],
    /// PID namespace identity.
    pub pid_namespace: LinuxNamespaceMetadata,
    /// User namespace identity.
    pub user_namespace: LinuxNamespaceMetadata,
    /// Mount namespace identity.
    pub mount_namespace: LinuxNamespaceMetadata,
    /// Static process address-space evidence.
    pub memory_layout: LinuxMemoryLayoutMetadata,
    /// Executable image identity.
    pub executable: LinuxExecutableMetadata,
}

/// Process-tree fields used only to prove application/session ownership.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct LinuxProcessRelationship {
    pid: u32,
    parent_pid: u32,
    session_id: u32,
    controlling_tty: i64,
    start_time_ticks: u64,
}

#[derive(Debug)]
struct RetainedProcessIdentity {
    pid: u32,
    pidfd: OwnedFd,
    executable: OwnedFd,
    accepted_metadata: LinuxProcessMetadata,
}

impl RetainedProcessIdentity {
    fn revalidate(&self) -> Result<(), LinuxIdentityError> {
        ensure_process_live(&self.pidfd)?;
        let current = read_stable_process_metadata(self.pid)?;
        let retained_executable = executable_metadata_from_fd(&self.executable)?;
        ensure_process_live(&self.pidfd)?;
        validate_accepted_metadata(&self.accepted_metadata, &current)?;
        validate_accepted_executable(&self.accepted_metadata.executable, &retained_executable)
    }
}

#[derive(Debug)]
struct LinuxApplicationGuard {
    path: Box<str>,
    device: u64,
    inode: u64,
    retained_cgroup: OwnedFd,
    process: RetainedProcessIdentity,
    accepted_chain: Box<[LinuxProcessRelationship]>,
}

/// A verified owning-application candidate for a Linux peer.
#[derive(Debug)]
pub struct LinuxApplicationIdentity {
    subject: VerifiedSubject,
    pid: Option<u32>,
    display_name_hint: Option<Box<str>>,
    executable_path_hint: Option<Box<str>>,
    guard: LinuxApplicationGuard,
}

impl LinuxApplicationIdentity {
    /// Application-instance authorization subject.
    #[must_use]
    pub const fn subject(&self) -> VerifiedSubject {
        self.subject
    }

    /// Best-effort application process for presentation only.
    #[must_use]
    pub const fn pid(&self) -> Option<u32> {
        self.pid
    }

    /// Best-effort application label for presentation only.
    #[must_use]
    pub fn display_name_hint(&self) -> Option<&str> {
        self.display_name_hint.as_deref()
    }

    /// Best-effort application executable for presentation/icon lookup only.
    #[must_use]
    pub fn executable_path_hint(&self) -> Option<&str> {
        self.executable_path_hint.as_deref()
    }
}

#[derive(Debug)]
struct LinuxTerminalSessionIdentity {
    subject: VerifiedSubject,
    session_id: u32,
    controlling_tty: i64,
    leader: RetainedProcessIdentity,
    accepted_chain: Box<[LinuxProcessRelationship]>,
}

/// Retained kernel-bound identity for the process that connected a Unix socket.
///
/// Keeping this value alive keeps the `SO_PEERPIDFD` descriptor alive. Call
/// [`LinuxProcessIdentity::revalidate`] immediately before each operation that
/// may reuse an approval; collecting a fingerprint and then dropping this guard
/// is not sufficient for process-scoped authorization.
#[derive(Debug)]
pub struct LinuxProcessIdentity {
    /// PID reported by the accepted socket's `SO_PEERCRED` snapshot.
    pub pid: u32,
    /// Effective UID reported by the accepted socket.
    pub uid: u32,
    /// Effective GID reported by the accepted socket.
    pub gid: u32,
    process_subject: VerifiedSubject,
    application: Option<LinuxApplicationIdentity>,
    terminal_session: Option<LinuxTerminalSessionIdentity>,
    pidfd: OwnedFd,
    executable: OwnedFd,
    accepted_metadata: LinuxProcessMetadata,
}

impl LinuxProcessIdentity {
    /// Maximum descriptors retained by one identity: direct peer pidfd/exe,
    /// application cgroup plus root pidfd/exe, and terminal-leader pidfd/exe.
    ///
    /// Agent connection limits must reserve this many additional descriptors
    /// per process-scoped Linux session.
    pub const RETAINED_FD_COUNT: usize = 7;

    /// Kernel-verified direct-process subject.
    #[must_use]
    pub const fn process_subject(&self) -> VerifiedSubject {
        self.process_subject
    }

    /// Verified owning-application instance, when Linux exposed a safe scope.
    #[must_use]
    pub const fn application(&self) -> Option<&LinuxApplicationIdentity> {
        self.application.as_ref()
    }

    /// Verified terminal-session subject, when a live session leader and TTY
    /// could be retained.
    #[must_use]
    pub fn terminal_session_subject(&self) -> Option<VerifiedSubject> {
        self.terminal_session
            .as_ref()
            .map(|session| session.subject)
    }

    /// Returns the retained pidfd without transferring ownership.
    #[must_use]
    pub fn peer_pidfd(&self) -> BorrowedFd<'_> {
        self.pidfd.as_fd()
    }

    /// Returns the retained `O_PATH` executable handle without transferring
    /// ownership.
    #[must_use]
    pub fn executable_fd(&self) -> BorrowedFd<'_> {
        self.executable.as_fd()
    }

    /// Returns the immutable process/executable snapshot used by the
    /// fingerprint and subsequent revalidation.
    #[must_use]
    pub const fn accepted_metadata(&self) -> &LinuxProcessMetadata {
        &self.accepted_metadata
    }

    /// Revalidates liveness and exact process/executable metadata.
    ///
    /// This performs pidfd liveness checks on both sides of the `/proc` reads,
    /// so numeric PID reuse cannot substitute a new process. A changed
    /// executable, credential set, namespace, or static address-space snapshot
    /// fails closed.
    ///
    /// # Errors
    ///
    /// Returns [`LinuxIdentityError::PeerExited`] when the retained peer has
    /// exited, [`LinuxIdentityError::PeerIdentityChanged`] when its accepted
    /// metadata changed, or another fail-closed collection error.
    pub fn revalidate(&self) -> Result<(), LinuxIdentityError> {
        ensure_process_live(&self.pidfd)?;
        let current_metadata = read_stable_process_metadata(self.pid)?;
        let retained_executable = executable_metadata_from_fd(&self.executable)?;
        ensure_process_live(&self.pidfd)?;
        validate_socket_credentials(&current_metadata, self.uid, self.gid)?;
        validate_accepted_metadata(&self.accepted_metadata, &current_metadata)?;
        validate_accepted_executable(&self.accepted_metadata.executable, &retained_executable)?;
        if let Some(application) = &self.application {
            revalidate_application(self.pid, application)?;
        }
        if let Some(session) = &self.terminal_session {
            revalidate_terminal_session(self.pid, session)?;
        }
        Ok(())
    }
}

/// Errors while deriving Linux process authorization evidence.
#[derive(Debug, Error)]
pub enum LinuxIdentityError {
    /// The running kernel cannot return a pidfd for a socket peer.
    #[error("SO_PEERPIDFD is unavailable; connection-scoped authorization is required")]
    PeerPidfdUnsupported(#[source] io::Error),
    /// A kernel socket or pidfd operation failed.
    #[error("failed to retrieve kernel-bound Linux peer identity")]
    Kernel(#[source] io::Error),
    /// The peer exited while its identity was being collected.
    #[error("socket peer exited or its retained pidfd became signaled")]
    PeerExited,
    /// The retained peer no longer has the accepted process/executable image.
    #[error("socket peer process or executable identity changed")]
    PeerIdentityChanged,
    /// Trusted process metadata was missing or malformed.
    #[error("invalid Linux peer process metadata: {0}")]
    InvalidMetadata(&'static str),
}

/// Derives process-scoped authorization evidence from an accepted Unix socket.
///
/// This function never falls back to `pidfd_open(pid)`: doing so after reading
/// `SO_PEERCRED` would leave a PID-reuse window.  Callers should retain their
/// fresh connection principal when this function returns an error. On success,
/// retain the returned guard for the connection lifetime and call
/// [`LinuxProcessIdentity::revalidate`] immediately before authorization-
/// sensitive IPC.
///
/// # Errors
///
/// Returns [`LinuxIdentityError::PeerPidfdUnsupported`] on kernels without
/// `SO_PEERPIDFD`, and a fail-closed error for incomplete or racing process
/// metadata.
pub fn process_identity_from_socket(
    socket_fd: RawFd,
) -> Result<LinuxProcessIdentity, LinuxIdentityError> {
    let credentials = peer_credentials(socket_fd)?;
    let pid = u32::try_from(credentials.pid)
        .ok()
        .filter(|pid| *pid != 0)
        .ok_or(LinuxIdentityError::InvalidMetadata("peer PID"))?;
    let pidfd = peer_pidfd(socket_fd)?;

    ensure_process_live(&pidfd)?;
    let process = read_stable_process_metadata(pid)?;
    let executable = open_peer_executable(pid)?;
    let retained_executable = executable_metadata_from_fd(&executable)?;
    let confirmed_process = read_stable_process_metadata(pid)?;
    ensure_process_live(&pidfd)?;
    validate_socket_credentials(&process, credentials.uid, credentials.gid)?;
    validate_accepted_metadata(&process, &confirmed_process)?;
    validate_accepted_executable(&process.executable, &retained_executable)?;

    let process_fingerprint =
        fingerprint_process_instance(pid, credentials.uid, credentials.gid, &process);
    let process_subject = VerifiedSubject::new(
        SubjectFingerprint::from_bytes(process_fingerprint),
        VerifiedSubjectKind::Process,
    );

    // Application and terminal candidates are optional refinements. Failure to
    // establish either must not discard the already kernel-bound process
    // subject. Consumers conservatively fall back to process/connection scope.
    let application = collect_application_identity(pid, credentials.uid)
        .ok()
        .flatten();
    let terminal_session = collect_terminal_session_identity(pid, credentials.uid)
        .ok()
        .flatten();

    Ok(LinuxProcessIdentity {
        pid,
        uid: credentials.uid,
        gid: credentials.gid,
        process_subject,
        application,
        terminal_session,
        pidfd,
        executable,
        accepted_metadata: process,
    })
}

fn peer_credentials(socket_fd: RawFd) -> Result<libc::ucred, LinuxIdentityError> {
    let mut credentials = MaybeUninit::<libc::ucred>::uninit();
    let mut length = size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: `credentials` is writable storage of the exact length supplied
    // to `getsockopt`; it is only assumed initialized after a successful call
    // that reports the expected size.
    let result = unsafe {
        libc::getsockopt(
            socket_fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            credentials.as_mut_ptr().cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    if length as usize != size_of::<libc::ucred>() {
        return Err(LinuxIdentityError::InvalidMetadata(
            "SO_PEERCRED response length",
        ));
    }

    // SAFETY: the successful `getsockopt` above initialized exactly one
    // `libc::ucred` value.
    Ok(unsafe { credentials.assume_init() })
}

fn peer_pidfd(socket_fd: RawFd) -> Result<OwnedFd, LinuxIdentityError> {
    let mut pidfd: libc::c_int = -1;
    let mut length = size_of::<libc::c_int>() as libc::socklen_t;
    // SAFETY: `pidfd` is valid writable integer storage and `length` describes
    // its exact size. On success the kernel installs a new descriptor in the
    // calling process.
    let result = unsafe {
        libc::getsockopt(
            socket_fd,
            libc::SOL_SOCKET,
            libc::SO_PEERPIDFD,
            (&mut pidfd as *mut libc::c_int).cast(),
            &mut length,
        )
    };
    if result != 0 {
        let error = io::Error::last_os_error();
        return match error.raw_os_error() {
            Some(libc::ENOPROTOOPT) | Some(libc::EINVAL) | Some(libc::ENOSYS) => {
                Err(LinuxIdentityError::PeerPidfdUnsupported(error))
            }
            _ => Err(LinuxIdentityError::Kernel(error)),
        };
    }
    if length as usize != size_of::<libc::c_int>() || pidfd < 0 {
        return Err(LinuxIdentityError::InvalidMetadata("SO_PEERPIDFD response"));
    }

    // SAFETY: a successful `SO_PEERPIDFD` call returned a newly owned file
    // descriptor. `OwnedFd` closes it exactly once.
    let pidfd = unsafe { OwnedFd::from_raw_fd(pidfd) };
    ensure_close_on_exec(&pidfd)?;
    Ok(pidfd)
}

fn open_peer_executable(pid: u32) -> Result<OwnedFd, LinuxIdentityError> {
    let path = CString::new(format!("/proc/{pid}/exe"))
        .map_err(|_| LinuxIdentityError::InvalidMetadata("peer executable path"))?;
    // SAFETY: `path` is a valid NUL-terminated string. O_PATH obtains a stable
    // reference to the mapped executable without requiring read permission,
    // and O_CLOEXEC prevents leaking the authorization guard into child images.
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_PATH | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    // SAFETY: successful `open` returned a newly owned descriptor.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    ensure_close_on_exec(&fd)?;
    Ok(fd)
}

fn executable_metadata_from_fd(
    executable: &OwnedFd,
) -> Result<LinuxExecutableMetadata, LinuxIdentityError> {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: `stat` is writable storage for one `libc::stat` and is assumed
    // initialized only after successful fstat on the retained descriptor.
    let result = unsafe { libc::fstat(executable.as_raw_fd(), stat.as_mut_ptr()) };
    if result != 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    // SAFETY: successful fstat initialized the complete structure.
    let stat = unsafe { stat.assume_init() };
    let size = u64::try_from(stat.st_size)
        .map_err(|_| LinuxIdentityError::InvalidMetadata("peer executable size"))?;
    let metadata = LinuxExecutableMetadata {
        device: stat.st_dev,
        inode: stat.st_ino,
        mode: stat.st_mode,
        uid: stat.st_uid,
        gid: stat.st_gid,
        link_count: u64::from(stat.st_nlink),
        size,
        mtime_seconds: stat.st_mtime,
        mtime_nanoseconds: stat.st_mtime_nsec,
        ctime_seconds: stat.st_ctime,
        ctime_nanoseconds: stat.st_ctime_nsec,
    };
    validate_executable_metadata(metadata)
}

fn executable_metadata_from_fs(
    executable: &fs::Metadata,
) -> Result<LinuxExecutableMetadata, LinuxIdentityError> {
    let metadata = LinuxExecutableMetadata {
        device: executable.dev(),
        inode: executable.ino(),
        mode: executable.mode(),
        uid: executable.uid(),
        gid: executable.gid(),
        link_count: executable.nlink(),
        size: executable.size(),
        mtime_seconds: executable.mtime(),
        mtime_nanoseconds: executable.mtime_nsec(),
        ctime_seconds: executable.ctime(),
        ctime_nanoseconds: executable.ctime_nsec(),
    };
    validate_executable_metadata(metadata)
}

fn validate_executable_metadata(
    metadata: LinuxExecutableMetadata,
) -> Result<LinuxExecutableMetadata, LinuxIdentityError> {
    if metadata.mode & libc::S_IFMT != libc::S_IFREG {
        return Err(LinuxIdentityError::InvalidMetadata(
            "peer executable file type",
        ));
    }
    Ok(metadata)
}

fn ensure_close_on_exec(fd: &OwnedFd) -> Result<(), LinuxIdentityError> {
    // SAFETY: `fd` is a live descriptor and F_GETFD does not dereference an
    // additional argument.
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) };
    if flags < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    if flags & libc::FD_CLOEXEC != 0 {
        return Ok(());
    }

    // SAFETY: `fd` remains owned for this call and the argument preserves all
    // existing descriptor flags while adding FD_CLOEXEC.
    let result = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFD, flags | libc::FD_CLOEXEC) };
    if result < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    Ok(())
}

fn ensure_process_live(pidfd: &OwnedFd) -> Result<(), LinuxIdentityError> {
    let mut descriptor = libc::pollfd {
        fd: pidfd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    // SAFETY: `descriptor` points to one initialized `pollfd`; timeout zero
    // makes this a non-blocking liveness check.
    let result = unsafe { libc::poll(&mut descriptor, 1, 0) };
    if result < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    if descriptor.revents & libc::POLLNVAL != 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::from_raw_os_error(
            libc::EBADF,
        )));
    }
    if descriptor.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
        return Err(LinuxIdentityError::PeerExited);
    }
    if result > 0 || descriptor.revents != 0 {
        return Err(LinuxIdentityError::InvalidMetadata(
            "unexpected pidfd poll event",
        ));
    }
    Ok(())
}

fn read_stable_process_metadata(pid: u32) -> Result<LinuxProcessMetadata, LinuxIdentityError> {
    let first = read_process_metadata_once(pid)?;
    let second = read_process_metadata_once(pid)?;
    if first != second {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(first)
}

fn read_process_metadata_once(pid: u32) -> Result<LinuxProcessMetadata, LinuxIdentityError> {
    let proc_dir = PathBuf::from("/proc").join(pid.to_string());
    let stat = fs::read_to_string(proc_dir.join("stat")).map_err(LinuxIdentityError::Kernel)?;
    let stat = parse_process_stat(&stat)?;
    let status = fs::read_to_string(proc_dir.join("status")).map_err(LinuxIdentityError::Kernel)?;
    let uids = parse_status_ids(&status, "Uid:", "process UIDs")?;
    let gids = parse_status_ids(&status, "Gid:", "process GIDs")?;

    let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(LinuxIdentityError::Kernel)?;
    let boot_id = boot_id.trim();
    if boot_id.is_empty() || boot_id.len() > MAX_BOOT_ID_LEN || !boot_id.is_ascii() {
        return Err(LinuxIdentityError::InvalidMetadata("kernel boot ID"));
    }

    let pid_namespace = read_namespace_metadata(&proc_dir, "pid")?;
    let user_namespace = read_namespace_metadata(&proc_dir, "user")?;
    let mount_namespace = read_namespace_metadata(&proc_dir, "mnt")?;
    // Following `/proc/PID/exe` yields metadata for the image actually mapped
    // by this process, including an unlinked executable. Binding it prevents a
    // process-scoped approval from surviving an `execve` into a different
    // image on an already-open agent connection.
    let executable = fs::metadata(proc_dir.join("exe")).map_err(LinuxIdentityError::Kernel)?;
    let executable = executable_metadata_from_fs(&executable)?;

    Ok(LinuxProcessMetadata {
        start_time_ticks: stat.start_time_ticks,
        boot_id: boot_id.into(),
        uids,
        gids,
        pid_namespace,
        user_namespace,
        mount_namespace,
        memory_layout: stat.memory_layout,
        executable,
    })
}

fn read_stable_process_relationship(
    pid: u32,
) -> Result<LinuxProcessRelationship, LinuxIdentityError> {
    let first = read_process_relationship_once(pid)?;
    let second = read_process_relationship_once(pid)?;
    if first != second {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(first)
}

fn read_process_relationship_once(
    pid: u32,
) -> Result<LinuxProcessRelationship, LinuxIdentityError> {
    let stat = fs::read_to_string(PathBuf::from("/proc").join(pid.to_string()).join("stat"))
        .map_err(LinuxIdentityError::Kernel)?;
    let stat = parse_process_stat(&stat)?;
    Ok(LinuxProcessRelationship {
        pid,
        parent_pid: stat.parent_pid,
        session_id: stat.session_id,
        controlling_tty: stat.controlling_tty,
        start_time_ticks: stat.start_time_ticks,
    })
}

fn collect_ancestry(
    leaf_pid: u32,
    stop_pid: Option<u32>,
) -> Result<Box<[LinuxProcessRelationship]>, LinuxIdentityError> {
    let mut chain = Vec::with_capacity(MAX_ANCESTRY_DEPTH);
    let mut current_pid = leaf_pid;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        let relationship = read_stable_process_relationship(current_pid)?;
        let parent_pid = relationship.parent_pid;
        chain.push(relationship);
        if stop_pid == Some(current_pid) {
            return Ok(chain.into_boxed_slice());
        }
        if parent_pid <= 1 || parent_pid == current_pid {
            break;
        }
        current_pid = parent_pid;
    }
    if stop_pid.is_some() {
        return Err(LinuxIdentityError::InvalidMetadata(
            "application/session process is not a bounded peer ancestor",
        ));
    }
    Ok(chain.into_boxed_slice())
}

fn retain_process_by_pid(pid: u32) -> Result<RetainedProcessIdentity, LinuxIdentityError> {
    let pidfd = pidfd_open(pid)?;
    ensure_process_live(&pidfd)?;
    let accepted_metadata = read_stable_process_metadata(pid)?;
    let executable = open_peer_executable(pid)?;
    let retained_executable = executable_metadata_from_fd(&executable)?;
    let confirmed = read_stable_process_metadata(pid)?;
    ensure_process_live(&pidfd)?;
    validate_accepted_metadata(&accepted_metadata, &confirmed)?;
    validate_accepted_executable(&accepted_metadata.executable, &retained_executable)?;
    Ok(RetainedProcessIdentity {
        pid,
        pidfd,
        executable,
        accepted_metadata,
    })
}

fn pidfd_open(pid: u32) -> Result<OwnedFd, LinuxIdentityError> {
    // SAFETY: pidfd_open takes a numeric PID and flags=0, and returns a newly
    // owned descriptor without dereferencing userspace pointers.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
    if fd < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    let fd = i32::try_from(fd)
        .map_err(|_| LinuxIdentityError::InvalidMetadata("pidfd_open descriptor"))?;
    // SAFETY: successful pidfd_open returned one newly owned descriptor.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    ensure_close_on_exec(&fd)?;
    Ok(fd)
}

fn collect_application_identity(
    peer_pid: u32,
    peer_uid: u32,
) -> Result<Option<LinuxApplicationIdentity>, LinuxIdentityError> {
    // POSIX sessions are caller-created job-control groups, not application
    // boundaries. Without a recognized cgroup unit, omit the wider subject and
    // let consumers fall back to terminal-session or direct-process identity.
    collect_cgroup_application_identity(peer_pid, peer_uid)
}

fn collect_cgroup_application_identity(
    peer_pid: u32,
    peer_uid: u32,
) -> Result<Option<LinuxApplicationIdentity>, LinuxIdentityError> {
    let first_path = read_unified_cgroup_path(peer_pid)?;
    let Some(unit_name) = application_unit_component(&first_path) else {
        return Ok(None);
    };
    let cgroup_path = cgroup_filesystem_path(&first_path)?;
    let retained_cgroup = open_path_directory(&cgroup_path)?;
    let (device, inode) = file_identity_from_fd(&retained_cgroup)?;
    let second_path = read_unified_cgroup_path(peer_pid)?;
    if first_path != second_path {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let current_metadata = fs::metadata(&cgroup_path).map_err(LinuxIdentityError::Kernel)?;
    if current_metadata.dev() != device || current_metadata.ino() != inode {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }

    // The cgroup name/inode is only a boundary hint: same-user processes may
    // be able to request cgroup moves. Authorization is therefore bound to a
    // retained, executable-pinned ancestor process and a revalidated ancestry
    // chain, never to cgroup membership alone.
    let Some(candidate_pid) = application_root_candidate(peer_pid, &first_path) else {
        return Ok(None);
    };
    let accepted_chain = collect_ancestry(peer_pid, Some(candidate_pid))?;
    let process = retain_process_by_pid(candidate_pid)?;
    if process.accepted_metadata.uids[1] != peer_uid {
        return Ok(None);
    }
    let confirmed_chain = collect_ancestry(peer_pid, Some(candidate_pid))?;
    if accepted_chain != confirmed_chain || read_unified_cgroup_path(peer_pid)? != first_path {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let fingerprint = fingerprint_retained_process(
        LINUX_APPLICATION_PROCESS_FINGERPRINT_DOMAIN,
        candidate_pid,
        peer_uid,
        &process.accepted_metadata,
    )?;
    let executable_path_hint = fs::read_link(
        PathBuf::from("/proc")
            .join(candidate_pid.to_string())
            .join("exe"),
    )
    .ok()
    .map(|path| path.to_string_lossy().into_owned());
    let display_name_hint = executable_path_hint
        .as_deref()
        .and_then(|path| Path::new(path).file_name())
        .and_then(|name| name.to_str())
        .map(str::to_owned)
        .unwrap_or(unit_name);

    Ok(Some(LinuxApplicationIdentity {
        subject: VerifiedSubject::new(
            SubjectFingerprint::from_bytes(fingerprint),
            VerifiedSubjectKind::ApplicationInstance,
        ),
        pid: Some(candidate_pid),
        display_name_hint: Some(display_name_hint.into()),
        executable_path_hint: executable_path_hint.map(Into::into),
        guard: LinuxApplicationGuard {
            path: first_path.into(),
            device,
            inode,
            retained_cgroup,
            process,
            accepted_chain,
        },
    }))
}

fn collect_terminal_session_identity(
    peer_pid: u32,
    peer_uid: u32,
) -> Result<Option<LinuxTerminalSessionIdentity>, LinuxIdentityError> {
    let peer = read_stable_process_relationship(peer_pid)?;
    if peer.session_id <= 1 || peer.controlling_tty == 0 {
        return Ok(None);
    }
    let accepted_chain = collect_ancestry(peer_pid, Some(peer.session_id))?;
    let Some(leader_relationship) = accepted_chain.last() else {
        return Ok(None);
    };
    if leader_relationship.pid != peer.session_id
        || leader_relationship.session_id != peer.session_id
        || leader_relationship.controlling_tty != peer.controlling_tty
    {
        return Ok(None);
    }
    let leader = retain_process_by_pid(peer.session_id)?;
    if leader.accepted_metadata.uids[1] != peer_uid {
        return Ok(None);
    }
    let confirmed_chain = collect_ancestry(peer_pid, Some(peer.session_id))?;
    if accepted_chain != confirmed_chain {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let subject = terminal_session_subject_from_leader(
        peer.session_id,
        peer_uid,
        leader.accepted_metadata.gids[1],
        &leader.accepted_metadata,
        peer.controlling_tty,
    )?;

    Ok(Some(LinuxTerminalSessionIdentity {
        subject,
        session_id: peer.session_id,
        controlling_tty: peer.controlling_tty,
        leader,
        accepted_chain,
    }))
}

fn terminal_session_subject_from_leader(
    leader_pid: u32,
    uid: u32,
    gid: u32,
    metadata: &LinuxProcessMetadata,
    controlling_tty: i64,
) -> Result<VerifiedSubject, LinuxIdentityError> {
    let mut canonical = fingerprint_process_canonical(leader_pid, uid, gid, metadata);
    canonical.extend_from_slice(&controlling_tty.to_be_bytes());
    let fingerprint =
        SubjectFingerprint::derive(LINUX_TERMINAL_SESSION_FINGERPRINT_DOMAIN, &canonical)
            .map_err(|_| LinuxIdentityError::InvalidMetadata("terminal session fingerprint"))?;
    Ok(VerifiedSubject::new(
        fingerprint,
        VerifiedSubjectKind::TerminalSession,
    ))
}

fn revalidate_application(
    peer_pid: u32,
    application: &LinuxApplicationIdentity,
) -> Result<(), LinuxIdentityError> {
    let guard = &application.guard;
    guard.process.revalidate()?;
    let current_chain = collect_ancestry(peer_pid, Some(guard.process.pid))?;
    if current_chain.as_ref() != guard.accepted_chain.as_ref() {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let current_path = read_unified_cgroup_path(peer_pid)?;
    if current_path != guard.path.as_ref() {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let current = cgroup_filesystem_path(&current_path)?;
    let metadata = fs::metadata(current).map_err(LinuxIdentityError::Kernel)?;
    let retained = file_identity_from_fd(&guard.retained_cgroup)?;
    if metadata.dev() != guard.device
        || metadata.ino() != guard.inode
        || retained != (guard.device, guard.inode)
    {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(())
}

fn revalidate_terminal_session(
    peer_pid: u32,
    session: &LinuxTerminalSessionIdentity,
) -> Result<(), LinuxIdentityError> {
    session.leader.revalidate()?;
    let peer = read_stable_process_relationship(peer_pid)?;
    if peer.session_id != session.session_id || peer.controlling_tty != session.controlling_tty {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    let current_chain = collect_ancestry(peer_pid, Some(session.session_id))?;
    let Some(leader_relationship) = current_chain.last() else {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    };
    if leader_relationship.pid != session.session_id
        || leader_relationship.session_id != session.session_id
        || leader_relationship.controlling_tty != session.controlling_tty
    {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    if current_chain.as_ref() != session.accepted_chain.as_ref() {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(())
}

fn read_unified_cgroup_path(pid: u32) -> Result<String, LinuxIdentityError> {
    let text = fs::read_to_string(PathBuf::from("/proc").join(pid.to_string()).join("cgroup"))
        .map_err(LinuxIdentityError::Kernel)?;
    let mut paths = text.lines().filter_map(|line| line.strip_prefix("0::"));
    let path = paths
        .next()
        .ok_or(LinuxIdentityError::InvalidMetadata("unified cgroup path"))?;
    if paths.next().is_some()
        || path.is_empty()
        || path.len() > MAX_CGROUP_PATH_LEN
        || path.contains('\0')
    {
        return Err(LinuxIdentityError::InvalidMetadata("unified cgroup path"));
    }
    let path = Path::new(path);
    if !path.is_absolute()
        || path
            .components()
            .any(|component| !matches!(component, Component::RootDir | Component::Normal(_)))
    {
        return Err(LinuxIdentityError::InvalidMetadata("unified cgroup path"));
    }
    Ok(path.to_string_lossy().into_owned())
}

fn application_unit_component(path: &str) -> Option<String> {
    let mut inside_app_slice = false;
    for component in Path::new(path)
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => value.to_str(),
            _ => None,
        })
    {
        if !inside_app_slice {
            inside_app_slice = component == "app.slice";
            continue;
        }

        let is_scope = component.ends_with(".scope");
        let is_service = component.ends_with(".service");
        let is_application_unit = (component.starts_with("app-") && (is_scope || is_service))
            || (is_scope && (component.starts_with("flatpak-") || component.starts_with("snap.")));
        if is_application_unit {
            return Some(component.to_owned());
        }
    }
    None
}

fn cgroup_filesystem_path(path: &str) -> Result<PathBuf, LinuxIdentityError> {
    let relative = Path::new(path)
        .strip_prefix("/")
        .map_err(|_| LinuxIdentityError::InvalidMetadata("unified cgroup path"))?;
    Ok(Path::new("/sys/fs/cgroup").join(relative))
}

fn open_path_directory(path: &Path) -> Result<OwnedFd, LinuxIdentityError> {
    use std::os::unix::ffi::OsStrExt;

    let path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| LinuxIdentityError::InvalidMetadata("cgroup filesystem path"))?;
    // SAFETY: `path` is NUL-terminated and O_PATH|O_DIRECTORY obtains a stable,
    // non-readable reference to exactly the cgroup directory inode.
    let fd = unsafe {
        libc::open(
            path.as_ptr(),
            libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    // SAFETY: successful open returned one newly owned descriptor.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    ensure_close_on_exec(&fd)?;
    Ok(fd)
}

fn file_identity_from_fd(fd: &OwnedFd) -> Result<(u64, u64), LinuxIdentityError> {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: `stat` is valid writable storage and fd is retained for the call.
    let result = unsafe { libc::fstat(fd.as_raw_fd(), stat.as_mut_ptr()) };
    if result != 0 {
        return Err(LinuxIdentityError::Kernel(io::Error::last_os_error()));
    }
    // SAFETY: successful fstat initialized the complete structure.
    let stat = unsafe { stat.assume_init() };
    Ok((stat.st_dev, stat.st_ino))
}

fn application_root_candidate(peer_pid: u32, cgroup_path: &str) -> Option<u32> {
    let Ok(chain) = collect_ancestry(peer_pid, None) else {
        return None;
    };
    let mut candidate = None;
    for relationship in chain.iter() {
        let Ok(path) = read_unified_cgroup_path(relationship.pid) else {
            break;
        };
        if path != cgroup_path {
            break;
        }
        candidate = Some(relationship.pid);
    }
    candidate
}

fn fingerprint_retained_process(
    domain: &[u8],
    pid: u32,
    uid: u32,
    metadata: &LinuxProcessMetadata,
) -> Result<[u8; PRINCIPAL_FINGERPRINT_LEN], LinuxIdentityError> {
    let canonical = fingerprint_process_canonical(pid, uid, metadata.gids[1], metadata);
    SubjectFingerprint::derive(domain, &canonical)
        .map(SubjectFingerprint::into_bytes)
        .map_err(|_| LinuxIdentityError::InvalidMetadata("application process fingerprint"))
}

fn read_namespace_metadata(
    proc_dir: &std::path::Path,
    namespace: &str,
) -> Result<LinuxNamespaceMetadata, LinuxIdentityError> {
    let metadata =
        fs::metadata(proc_dir.join("ns").join(namespace)).map_err(LinuxIdentityError::Kernel)?;
    Ok(LinuxNamespaceMetadata {
        device: metadata.dev(),
        inode: metadata.ino(),
    })
}

fn parse_status_ids(
    status: &str,
    prefix: &str,
    description: &'static str,
) -> Result<[u32; 4], LinuxIdentityError> {
    let values = status
        .lines()
        .find_map(|line| line.strip_prefix(prefix))
        .ok_or(LinuxIdentityError::InvalidMetadata(description))?;
    let mut values = values.split_whitespace();
    let mut parsed = [0u32; 4];
    for id in &mut parsed {
        *id = values
            .next()
            .ok_or(LinuxIdentityError::InvalidMetadata(description))?
            .parse()
            .map_err(|_| LinuxIdentityError::InvalidMetadata(description))?;
    }
    if values.next().is_some() {
        return Err(LinuxIdentityError::InvalidMetadata(description));
    }
    Ok(parsed)
}

fn validate_socket_credentials(
    metadata: &LinuxProcessMetadata,
    socket_uid: u32,
    socket_gid: u32,
) -> Result<(), LinuxIdentityError> {
    if metadata.uids[1] != socket_uid || metadata.gids[1] != socket_gid {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(())
}

fn validate_accepted_metadata(
    accepted: &LinuxProcessMetadata,
    current: &LinuxProcessMetadata,
) -> Result<(), LinuxIdentityError> {
    if accepted != current {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(())
}

fn validate_accepted_executable(
    accepted: &LinuxExecutableMetadata,
    retained: &LinuxExecutableMetadata,
) -> Result<(), LinuxIdentityError> {
    if accepted != retained {
        return Err(LinuxIdentityError::PeerIdentityChanged);
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ParsedProcessStat {
    parent_pid: u32,
    session_id: u32,
    controlling_tty: i64,
    start_time_ticks: u64,
    memory_layout: LinuxMemoryLayoutMetadata,
}

fn parse_process_stat(stat: &str) -> Result<ParsedProcessStat, LinuxIdentityError> {
    // `comm` (field 2) is parenthesized and may itself contain spaces or `)`.
    // The last `)` therefore marks the only safe start of fields 3 onward.
    let command_end = stat.rfind(')').ok_or(LinuxIdentityError::InvalidMetadata(
        "/proc PID stat command",
    ))?;
    let fields = stat
        .get(command_end + 1..)
        .ok_or(LinuxIdentityError::InvalidMetadata("/proc PID stat fields"))?;
    // Field 3 is index 0 in this suffix. Capture process start time and static
    // address-space coordinates that are replaced by execve(2).
    let mut selected = [None; 13];
    let mut controlling_tty = None;
    for (index, value) in fields.split_whitespace().enumerate() {
        let slot = match index {
            1 => Some(0), // field 4: ppid
            3 => Some(1), // field 6: session
            4 => {
                controlling_tty = Some(value.parse::<i64>().map_err(|_| {
                    LinuxIdentityError::InvalidMetadata("/proc PID stat controlling TTY")
                })?);
                None
            }
            19 => Some(2),  // field 22: starttime
            23 => Some(3),  // field 26: startcode
            24 => Some(4),  // field 27: endcode
            25 => Some(5),  // field 28: startstack
            42 => Some(6),  // field 45: start_data
            43 => Some(7),  // field 46: end_data
            44 => Some(8),  // field 47: start_brk
            45 => Some(9),  // field 48: arg_start
            46 => Some(10), // field 49: arg_end
            47 => Some(11), // field 50: env_start
            48 => Some(12), // field 51: env_end
            _ => None,
        };
        if let Some(slot) = slot {
            selected[slot] = Some(value.parse().map_err(|_| {
                LinuxIdentityError::InvalidMetadata("/proc PID stat identity fields")
            })?);
        }
    }
    let [parent_pid, session_id, start_time_ticks, start_code, end_code, start_stack, start_data, end_data, start_brk, arg_start, arg_end, env_start, env_end] =
        selected.map(|value| {
            value.ok_or(LinuxIdentityError::InvalidMetadata(
                "/proc PID stat identity fields",
            ))
        });

    Ok(ParsedProcessStat {
        parent_pid: u32::try_from(parent_pid?)
            .map_err(|_| LinuxIdentityError::InvalidMetadata("/proc PID stat parent PID"))?,
        session_id: u32::try_from(session_id?)
            .map_err(|_| LinuxIdentityError::InvalidMetadata("/proc PID stat session ID"))?,
        controlling_tty: controlling_tty.ok_or(LinuxIdentityError::InvalidMetadata(
            "/proc PID stat controlling TTY",
        ))?,
        start_time_ticks: start_time_ticks?,
        memory_layout: LinuxMemoryLayoutMetadata {
            start_code: start_code?,
            end_code: end_code?,
            start_stack: start_stack?,
            start_data: start_data?,
            end_data: end_data?,
            start_brk: start_brk?,
            arg_start: arg_start?,
            arg_end: arg_end?,
            env_start: env_start?,
            env_end: env_end?,
        },
    })
}

fn fingerprint_process_instance(
    pid: u32,
    uid: u32,
    gid: u32,
    metadata: &LinuxProcessMetadata,
) -> [u8; PRINCIPAL_FINGERPRINT_LEN] {
    let canonical = fingerprint_process_canonical(pid, uid, gid, metadata);
    let mut digest = Sha256::new();
    digest.update(LINUX_PROCESS_FINGERPRINT_DOMAIN);
    digest.update(canonical);
    digest.finalize().into()
}

fn fingerprint_process_canonical(
    pid: u32,
    uid: u32,
    gid: u32,
    metadata: &LinuxProcessMetadata,
) -> Vec<u8> {
    let mut output = Vec::with_capacity(256 + metadata.boot_id.len());
    output.extend_from_slice(&pid.to_be_bytes());
    output.extend_from_slice(&uid.to_be_bytes());
    output.extend_from_slice(&gid.to_be_bytes());
    output.extend_from_slice(&metadata.start_time_ticks.to_be_bytes());
    output.extend_from_slice(&(metadata.boot_id.len() as u32).to_be_bytes());
    output.extend_from_slice(metadata.boot_id.as_bytes());
    for id in metadata.uids.into_iter().chain(metadata.gids) {
        output.extend_from_slice(&id.to_be_bytes());
    }
    for namespace in [
        metadata.pid_namespace,
        metadata.user_namespace,
        metadata.mount_namespace,
    ] {
        output.extend_from_slice(&namespace.device.to_be_bytes());
        output.extend_from_slice(&namespace.inode.to_be_bytes());
    }
    let memory = metadata.memory_layout;
    for address in [
        memory.start_code,
        memory.end_code,
        memory.start_stack,
        memory.start_data,
        memory.end_data,
        memory.start_brk,
        memory.arg_start,
        memory.arg_end,
        memory.env_start,
        memory.env_end,
    ] {
        output.extend_from_slice(&address.to_be_bytes());
    }
    let executable = metadata.executable;
    output.extend_from_slice(&executable.device.to_be_bytes());
    output.extend_from_slice(&executable.inode.to_be_bytes());
    output.extend_from_slice(&executable.mode.to_be_bytes());
    output.extend_from_slice(&executable.uid.to_be_bytes());
    output.extend_from_slice(&executable.gid.to_be_bytes());
    output.extend_from_slice(&executable.link_count.to_be_bytes());
    output.extend_from_slice(&executable.size.to_be_bytes());
    output.extend_from_slice(&executable.mtime_seconds.to_be_bytes());
    output.extend_from_slice(&executable.mtime_nanoseconds.to_be_bytes());
    output.extend_from_slice(&executable.ctime_seconds.to_be_bytes());
    output.extend_from_slice(&executable.ctime_nanoseconds.to_be_bytes());
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixStream;

    #[test]
    fn parses_identity_fields_after_complex_process_name() {
        let mut fields = (3u64..=51)
            .map(|value| value.to_string())
            .collect::<Vec<_>>();
        fields[19] = "987654".to_string();
        fields[23] = "2600".to_string();
        fields[48] = "5100".to_string();
        let stat = format!("123 (worker name)with)paren) {}", fields.join(" "));
        let parsed = parse_process_stat(&stat).expect("process stat");

        assert_eq!(parsed.parent_pid, 4);
        assert_eq!(parsed.session_id, 6);
        assert_eq!(parsed.controlling_tty, 7);
        assert_eq!(parsed.start_time_ticks, 987654);
        assert_eq!(parsed.memory_layout.start_code, 2600);
        assert_eq!(parsed.memory_layout.env_end, 5100);
    }

    #[test]
    fn recognizes_only_bounded_application_unit_components() {
        let accepted = [
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/app-org.gnome.Evince-12345.scope",
                "app-org.gnome.Evince-12345.scope",
            ),
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/app-gnome-org.gnome.Evince@12345.service",
                "app-gnome-org.gnome.Evince@12345.service",
            ),
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/app-org.kde.amarok.service",
                "app-org.kde.amarok.service",
            ),
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/app-org.example.App.slice/app-org.example.App-42.scope",
                "app-org.example.App-42.scope",
            ),
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/flatpak-org.example.App-42.scope",
                "flatpak-org.example.App-42.scope",
            ),
            (
                "/user.slice/user-1000.slice/user@1000.service/app.slice/snap.firefox.firefox.scope",
                "snap.firefox.firefox.scope",
            ),
        ];
        for (path, expected) in accepted {
            assert_eq!(
                application_unit_component(path).as_deref(),
                Some(expected),
                "{path}",
            );
        }

        let rejected = [
            "/user.slice/user-1000.slice/user@1000.service/session.slice/app-org.example.App-42.scope",
            "/user.slice/user-1000.slice/user@1000.service/background.slice/app-org.example.App.service",
            "/user.slice/user-1000.slice/user@1000.service/application.slice/app-org.example.App.service",
            "/user.slice/user-1000.slice/user@1000.service/app.slice/org.example.App.service",
            "/user.slice/user-1000.slice/user@1000.service/app.slice/user@1000.service",
            "/user.slice/user-1000.slice/user@1000.service/app.slice/app-org.example.App.slice",
            "/user.slice/user-1000.slice/user@1000.service/app.slice/flatpak-org.example.App.service",
            "/user.slice/user-1000.slice/user@1000.service/app.slice/snap.firefox.firefox.service",
            "/user.slice/user-1000.slice/user@1000.service/session.slice/vte-spawn.scope",
        ];
        for path in rejected {
            assert!(application_unit_component(path).is_none(), "{path}");
        }
    }

    #[test]
    fn application_fingerprint_is_independent_from_connecting_child() {
        let metadata = fixture_metadata();
        let first = fingerprint_retained_process(
            LINUX_APPLICATION_PROCESS_FINGERPRINT_DOMAIN,
            20,
            20,
            &metadata,
        )
        .expect("application fingerprint");
        let repeated = fingerprint_retained_process(
            LINUX_APPLICATION_PROCESS_FINGERPRINT_DOMAIN,
            20,
            20,
            &metadata,
        )
        .expect("application fingerprint");

        assert_eq!(first, repeated);
    }

    #[test]
    fn terminal_subject_depends_only_on_retained_leader_and_tty() {
        let metadata = fixture_metadata();
        let first = terminal_session_subject_from_leader(20, 20, 30, &metadata, 100)
            .expect("terminal subject");
        let another_child = terminal_session_subject_from_leader(20, 20, 30, &metadata, 100)
            .expect("terminal subject");
        let another_tty = terminal_session_subject_from_leader(20, 20, 30, &metadata, 101)
            .expect("terminal subject");
        let another_leader = terminal_session_subject_from_leader(21, 20, 30, &metadata, 100)
            .expect("terminal subject");

        assert_eq!(first, another_child);
        assert_ne!(first, another_tty);
        assert_ne!(first, another_leader);
    }

    #[test]
    fn parses_exact_status_credential_sets() {
        let status = "Name:\ttest\nUid:\t1\t2\t3\t4\nGid:\t5\t6\t7\t8\n";

        assert_eq!(
            parse_status_ids(status, "Uid:", "UIDs").expect("UIDs"),
            [1, 2, 3, 4]
        );
        assert_eq!(
            parse_status_ids(status, "Gid:", "GIDs").expect("GIDs"),
            [5, 6, 7, 8]
        );
    }

    #[test]
    fn process_fingerprint_is_domain_bound_and_deterministic() {
        let metadata = fixture_metadata();
        let first = fingerprint_process_instance(10, 20, 30, &metadata);
        let repeated = fingerprint_process_instance(10, 20, 30, &metadata);
        let mut another_start = metadata.clone();
        another_start.start_time_ticks += 1;
        let another_start = fingerprint_process_instance(10, 20, 30, &another_start);
        let mut another_boot = metadata.clone();
        another_boot.boot_id = "boot-b".into();
        let another_boot = fingerprint_process_instance(10, 20, 30, &another_boot);
        let mut another_executable = metadata;
        another_executable.executable.inode += 1;
        let another_executable = fingerprint_process_instance(10, 20, 30, &another_executable);

        assert_eq!(first, repeated);
        assert_ne!(first, another_start);
        assert_ne!(first, another_boot);
        assert_ne!(first, another_executable);
    }

    #[test]
    fn revalidation_snapshot_rejects_exec_and_credential_changes() {
        let accepted = fixture_metadata();
        assert!(validate_accepted_metadata(&accepted, &accepted).is_ok());

        let mut another_executable = accepted.clone();
        another_executable.executable.inode += 1;
        assert!(matches!(
            validate_accepted_metadata(&accepted, &another_executable),
            Err(LinuxIdentityError::PeerIdentityChanged)
        ));
        assert!(matches!(
            validate_accepted_executable(&accepted.executable, &another_executable.executable,),
            Err(LinuxIdentityError::PeerIdentityChanged)
        ));

        let mut another_layout = accepted.clone();
        another_layout.memory_layout.start_stack += 1;
        assert!(matches!(
            validate_accepted_metadata(&accepted, &another_layout),
            Err(LinuxIdentityError::PeerIdentityChanged)
        ));

        let mut another_credentials = accepted.clone();
        another_credentials.uids[1] += 1;
        assert!(matches!(
            validate_socket_credentials(&another_credentials, 20, 30),
            Err(LinuxIdentityError::PeerIdentityChanged)
        ));
    }

    #[test]
    fn retained_socket_peer_pidfd_revalidates_current_process_when_supported() {
        let (server, _client) = UnixStream::pair().expect("Unix socket pair");
        let identity = match process_identity_from_socket(server.as_raw_fd()) {
            Ok(identity) => identity,
            Err(LinuxIdentityError::PeerPidfdUnsupported(_)) => return,
            Err(error) => panic!("failed to derive peer identity: {error}"),
        };

        assert_eq!(identity.pid, std::process::id());
        assert_eq!(
            identity.process_subject().fingerprint().as_bytes().len(),
            PRINCIPAL_FINGERPRINT_LEN
        );
        identity.revalidate().expect("revalidate live peer");
        assert_eq!(identity.accepted_metadata().uids[1], identity.uid);
        assert_eq!(identity.accepted_metadata().gids[1], identity.gid);

        // SAFETY: F_GETFD only reads flags from the retained live descriptor.
        let flags = unsafe { libc::fcntl(identity.peer_pidfd().as_raw_fd(), libc::F_GETFD) };
        assert!(flags >= 0);
        assert_ne!(flags & libc::FD_CLOEXEC, 0);
        // SAFETY: F_GETFD only reads flags from the retained live descriptor.
        let flags = unsafe { libc::fcntl(identity.executable_fd().as_raw_fd(), libc::F_GETFD) };
        assert!(flags >= 0);
        assert_ne!(flags & libc::FD_CLOEXEC, 0);
        assert_eq!(
            identity.process_subject().kind(),
            VerifiedSubjectKind::Process
        );
        assert_eq!(LinuxProcessIdentity::RETAINED_FD_COUNT, 7);
    }

    fn fixture_metadata() -> LinuxProcessMetadata {
        LinuxProcessMetadata {
            start_time_ticks: 40,
            boot_id: "boot-a".into(),
            uids: [20, 20, 20, 20],
            gids: [30, 30, 30, 30],
            pid_namespace: LinuxNamespaceMetadata {
                device: 50,
                inode: 51,
            },
            user_namespace: LinuxNamespaceMetadata {
                device: 52,
                inode: 53,
            },
            mount_namespace: LinuxNamespaceMetadata {
                device: 54,
                inode: 55,
            },
            memory_layout: LinuxMemoryLayoutMetadata {
                start_code: 60,
                end_code: 61,
                start_stack: 62,
                start_data: 63,
                end_data: 64,
                start_brk: 65,
                arg_start: 66,
                arg_end: 67,
                env_start: 68,
                env_end: 69,
            },
            executable: LinuxExecutableMetadata {
                device: 70,
                inode: 71,
                mode: libc::S_IFREG | 0o755,
                uid: 20,
                gid: 30,
                link_count: 1,
                size: 72,
                mtime_seconds: 73,
                mtime_nanoseconds: 74,
                ctime_seconds: 75,
                ctime_nanoseconds: 76,
            },
        }
    }
}
