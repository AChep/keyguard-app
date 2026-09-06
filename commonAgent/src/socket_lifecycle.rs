//! Shared Unix domain socket lifecycle hardening for Keyguard agents.
//!
//! Owns the security-critical socket setup and teardown sequence used by the
//! desktop agents: path validation, the persistent `flock`-based lifecycle
//! lock that serializes socket ownership between agent processes,
//! identity-checked stale-socket removal, bound-socket permission
//! enforcement, managed parent directory preparation, and identity-checked
//! cleanup. Every operation is parameterized by an agent label (for example
//! `"GPG agent"`) so diagnostics stay agent-specific while the TOCTOU and
//! symlink defenses exist in exactly one place. Lifecycle lock files live
//! under the owner-only Keyguard directory `/tmp/keyguard-<uid>/agent-locks`,
//! outside socket directories managed by GnuPG.

use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use std::fs;
use std::future::Future;
use std::io::{self, ErrorKind};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{DirBuilderExt, FileTypeExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::net::UnixStream;
use tokio::sync::oneshot;
use tracing::{info, warn};

#[cfg(target_os = "macos")]
const UNIX_SOCKET_PATH_MAX_BYTES: usize = 103;
#[cfg(not(target_os = "macos"))]
const UNIX_SOCKET_PATH_MAX_BYTES: usize = 107;
const LIFECYCLE_LOCK_MODE: u32 = 0o600;
const LIFECYCLE_LOCK_DIRECTORY_NAME: &str = "agent-locks";
const LIFECYCLE_LOCK_HASH_DOMAIN: &[u8] = b"keyguard-agent-lifecycle-lock-v1\0";
const STALE_SOCKET_PROBE_TIMEOUT: Duration = Duration::from_millis(250);
const SOCKET_ATTESTATION_TIMEOUT: Duration = Duration::from_millis(250);

/// Filesystem identity of a directory entry, used to detect pathname swaps.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EntryIdentity {
    pub device: u64,
    pub inode: u64,
}

/// Returns the `{device, inode}` identity of `metadata`.
#[must_use]
pub fn entry_identity(metadata: &fs::Metadata) -> EntryIdentity {
    EntryIdentity {
        device: metadata.dev(),
        inode: metadata.ino(),
    }
}

/// Returns the real user ID of the current process.
#[must_use]
pub fn current_uid() -> libc::uid_t {
    // SAFETY: `getuid` has no preconditions and only reads the real user ID
    // maintained by the operating system for this process.
    unsafe { libc::getuid() }
}

fn current_euid() -> libc::uid_t {
    // SAFETY: `geteuid` has no preconditions and only reads the effective user
    // ID maintained by the operating system for this process.
    unsafe { libc::geteuid() }
}

/// Returns the path of the persistent lifecycle lock for `socket_path`.
///
/// The socket's existing parent directory is canonicalized so relative paths
/// and symlink aliases that reach the same absolute socket address share one
/// lock. The fixed-length filename is a domain-separated SHA-256 digest of
/// that address's raw Unix path bytes; this avoids both `sun_path`-length
/// coupling and filename collisions from lossy path encodings.
///
/// # Errors
///
/// Fails when the socket path has no filename or its parent directory cannot
/// be resolved to an absolute path.
pub fn lifecycle_lock_path(socket_path: &Path, uid: libc::uid_t) -> Result<PathBuf> {
    lifecycle_lock_path_in(socket_path, &lifecycle_lock_directory(uid))
}

fn lifecycle_lock_directory(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}")).join(LIFECYCLE_LOCK_DIRECTORY_NAME)
}

fn lifecycle_lock_path_in(socket_path: &Path, lock_directory: &Path) -> Result<PathBuf> {
    let file_name = socket_path.file_name().with_context(|| {
        format!(
            "agent socket path has no filename for lifecycle locking: {}",
            socket_path.display()
        )
    })?;
    let parent = socket_path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let canonical_parent = fs::canonicalize(parent).with_context(|| {
        format!(
            "failed to resolve agent socket parent for lifecycle locking: {}",
            parent.display()
        )
    })?;
    let absolute_socket_address = canonical_parent.join(file_name);

    let address_bytes = absolute_socket_address.as_os_str().as_bytes();
    let mut hasher = Sha256::new();
    hasher.update(LIFECYCLE_LOCK_HASH_DOMAIN);
    hasher.update((address_bytes.len() as u64).to_be_bytes());
    hasher.update(address_bytes);
    let lock_name = format!("sha256-{}.lock", hex::encode(hasher.finalize()));
    Ok(lock_directory.join(lock_name))
}

/// Holds `flock`-based exclusive ownership of an agent socket path. Dropping
/// the value releases the lock; the lock file's inode is deliberately kept.
pub struct SocketLifecycleLock {
    _file: fs::File,
}

/// Removes an agent's own socket file on drop, but only while the entry still
/// matches the identity this process bound.
pub struct SocketFileGuard {
    lifecycle: SocketLifecycle,
    path: PathBuf,
    identity: EntryIdentity,
    uid: libc::uid_t,
}

impl Drop for SocketFileGuard {
    fn drop(&mut self) {
        self.lifecycle
            .cleanup_socket_file(&self.path, self.identity, self.uid);
    }
}

/// Label-parameterized socket lifecycle operations for one agent kind.
#[derive(Clone, Copy)]
pub struct SocketLifecycle {
    /// Diagnostic label woven into every message, e.g. `"GPG agent"`.
    label: &'static str,
}

impl SocketLifecycle {
    #[must_use]
    pub const fn new(label: &'static str) -> Self {
        Self { label }
    }

    /// Rejects socket paths longer than the platform's `sun_path` capacity.
    ///
    /// # Errors
    ///
    /// Returns an error when the path exceeds the platform maximum.
    pub fn validate_socket_path(&self, socket_path: &Path) -> Result<()> {
        let path_length = socket_path.as_os_str().as_bytes().len();
        if path_length > UNIX_SOCKET_PATH_MAX_BYTES {
            anyhow::bail!(
                "{} socket path is too long for this platform ({} bytes, maximum {}): {}",
                self.label,
                path_length,
                UNIX_SOCKET_PATH_MAX_BYTES,
                socket_path.display()
            );
        }
        Ok(())
    }

    /// Acquires the exclusive lifecycle lock guarding `socket_path`.
    ///
    /// # Errors
    ///
    /// Fails when the socket's absolute address cannot be resolved, the
    /// Keyguard lock directory cannot be created and secured, another agent
    /// process holds the lock, or the lock file is unsafe (symlink, non-file,
    /// or foreign owner).
    pub fn acquire_lifecycle_lock(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
    ) -> Result<SocketLifecycleLock> {
        let lock_directory = lifecycle_lock_directory(uid);
        self.acquire_lifecycle_lock_in(socket_path, uid, &lock_directory)
    }

    fn acquire_lifecycle_lock_in(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
        lock_directory: &Path,
    ) -> Result<SocketLifecycleLock> {
        self.ensure_lifecycle_lock_directory(lock_directory, uid)?;
        let lock_path = lifecycle_lock_path_in(socket_path, lock_directory)?;
        let mut create_options = fs::OpenOptions::new();
        create_options
            .read(true)
            .write(true)
            .create_new(true)
            .mode(LIFECYCLE_LOCK_MODE)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);

        let (file, created) = match create_options.open(&lock_path) {
            Ok(file) => (file, true),
            Err(error) if error.kind() == ErrorKind::AlreadyExists => {
                let mut open_options = fs::OpenOptions::new();
                open_options
                    .read(true)
                    .write(true)
                    .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
                match open_options.open(&lock_path) {
                    Ok(file) => (file, false),
                    Err(error) if error.raw_os_error() == Some(libc::ELOOP) => {
                        anyhow::bail!(
                            "unsafe {} lifecycle lock at {}: entry is a symlink",
                            self.label,
                            lock_path.display()
                        );
                    }
                    Err(error) => {
                        return Err(error).with_context(|| {
                            format!(
                                "failed to safely open {} lifecycle lock: {}",
                                self.label,
                                lock_path.display()
                            )
                        });
                    }
                }
            }
            Err(error) => {
                return Err(error).with_context(|| {
                    format!(
                        "failed to safely create {} lifecycle lock: {}",
                        self.label,
                        lock_path.display()
                    )
                });
            }
        };

        let opened_metadata = file.metadata().with_context(|| {
            format!(
                "failed to inspect opened {} lifecycle lock: {}",
                self.label,
                lock_path.display()
            )
        })?;
        self.validate_lifecycle_lock_metadata(&lock_path, &opened_metadata, uid)?;
        if created {
            file.set_permissions(fs::Permissions::from_mode(LIFECYCLE_LOCK_MODE))
                .with_context(|| {
                    format!(
                        "failed to set {} lifecycle lock permissions to 0600: {}",
                        self.label,
                        lock_path.display()
                    )
                })?;
        }

        loop {
            // SAFETY: `file.as_raw_fd()` is a live descriptor for the regular
            // lock file, and the operation contains only valid `flock` flags.
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 {
                break;
            }
            let error = io::Error::last_os_error();
            if error.kind() == ErrorKind::Interrupted {
                continue;
            }
            if error.kind() == ErrorKind::WouldBlock {
                anyhow::bail!(
                    "another Keyguard {} process is already using socket {}",
                    self.label,
                    socket_path.display()
                );
            }
            return Err(error).with_context(|| {
                format!(
                    "failed to acquire {} lifecycle lock: {}",
                    self.label,
                    lock_path.display()
                )
            });
        }

        Ok(SocketLifecycleLock { _file: file })
    }

    fn ensure_lifecycle_lock_directory(
        &self,
        lock_directory: &Path,
        uid: libc::uid_t,
    ) -> Result<()> {
        let keyguard_root = lock_directory.parent().with_context(|| {
            format!(
                "Keyguard lifecycle lock directory has no parent: {}",
                lock_directory.display()
            )
        })?;
        self.ensure_safe_managed_parent_dir(keyguard_root, uid)?;
        self.ensure_safe_managed_parent_dir(lock_directory, uid)
    }

    fn validate_lifecycle_lock_metadata(
        &self,
        lock_path: &Path,
        metadata: &fs::Metadata,
        uid: libc::uid_t,
    ) -> Result<()> {
        if !metadata.file_type().is_file() {
            anyhow::bail!(
                "unsafe {} lifecycle lock at {}: entry is not a regular file",
                self.label,
                lock_path.display()
            );
        }
        if metadata.uid() != uid {
            anyhow::bail!(
                "unsafe {} lifecycle lock at {}: owned by uid {}, expected {}",
                self.label,
                lock_path.display(),
                metadata.uid(),
                uid
            );
        }
        Ok(())
    }

    fn validate_owned_socket_metadata(
        &self,
        socket_path: &Path,
        metadata: &fs::Metadata,
        uid: libc::uid_t,
    ) -> Result<EntryIdentity> {
        let file_type = metadata.file_type();
        if file_type.is_symlink() {
            anyhow::bail!(
                "unsafe {} socket entry at {}: entry is a symlink",
                self.label,
                socket_path.display()
            );
        }
        if !file_type.is_socket() {
            anyhow::bail!(
                "unsafe {} socket entry at {}: entry is not a Unix socket",
                self.label,
                socket_path.display()
            );
        }
        if metadata.uid() != uid {
            anyhow::bail!(
                "unsafe {} socket entry at {}: owned by uid {}, expected {}",
                self.label,
                socket_path.display(),
                metadata.uid(),
                uid
            );
        }
        Ok(entry_identity(metadata))
    }

    /// Returns the identity of the socket entry at `socket_path`, requiring
    /// it to be a Unix socket owned by `uid`.
    ///
    /// # Errors
    ///
    /// Fails when the entry is missing, a symlink, not a socket, or owned by
    /// another user.
    pub fn owned_socket_identity(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
    ) -> Result<EntryIdentity> {
        let metadata = fs::symlink_metadata(socket_path).with_context(|| {
            format!(
                "failed to inspect {} socket entry: {}",
                self.label,
                socket_path.display()
            )
        })?;
        self.validate_owned_socket_metadata(socket_path, &metadata, uid)
    }

    /// Returns whether `stream` is connected to this exact process instance.
    ///
    /// The kernel-provided peer PID, rather than the filesystem owner alone,
    /// distinguishes another process running under the same user ID.
    ///
    /// # Errors
    ///
    /// Fails when peer credentials cannot be read.
    pub fn is_current_process_peer(&self, stream: &UnixStream) -> Result<bool> {
        let credentials = stream
            .peer_cred()
            .with_context(|| format!("failed to read {} socket peer credentials", self.label))?;
        let peer_pid = credentials.pid().and_then(|pid| u32::try_from(pid).ok());
        Ok(credentials.uid() == current_euid() && peer_pid == Some(std::process::id()))
    }

    /// Proves that `socket_path` currently reaches a listener owned by this
    /// exact process and still names `expected_identity`.
    ///
    /// # Errors
    ///
    /// Fails closed on a pathname change, credential mismatch, connection
    /// failure, unsafe permissions, or timeout.
    pub async fn attest_bound_socket_path(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
        expected_identity: EntryIdentity,
    ) -> Result<()> {
        let attestation = async {
            let initial_identity = self.owned_socket_identity(socket_path, uid)?;
            if initial_identity != expected_identity {
                anyhow::bail!(
                    "{} socket changed before endpoint attestation: {}",
                    self.label,
                    socket_path.display()
                );
            }

            let probe = UnixStream::connect(socket_path).await.with_context(|| {
                format!(
                    "failed to connect to {} socket for endpoint attestation: {}",
                    self.label,
                    socket_path.display()
                )
            })?;
            if !self.is_current_process_peer(&probe)? {
                anyhow::bail!(
                    "{} socket endpoint belongs to another process: {}",
                    self.label,
                    socket_path.display()
                );
            }

            let final_metadata = fs::symlink_metadata(socket_path).with_context(|| {
                format!(
                    "failed to re-check {} socket after endpoint attestation: {}",
                    self.label,
                    socket_path.display()
                )
            })?;
            let final_identity =
                self.validate_owned_socket_metadata(socket_path, &final_metadata, uid)?;
            if final_identity != expected_identity {
                anyhow::bail!(
                    "{} socket changed during endpoint attestation: {}",
                    self.label,
                    socket_path.display()
                );
            }
            let socket_mode = final_metadata.mode() & 0o777;
            if socket_mode != 0o600 {
                anyhow::bail!(
                    "unsafe {} socket permissions after endpoint attestation at {}: expected 0600, got {:03o}",
                    self.label,
                    socket_path.display(),
                    socket_mode
                );
            }
            Ok(())
        };

        tokio::time::timeout(SOCKET_ATTESTATION_TIMEOUT, attestation)
            .await
            .with_context(|| {
                format!(
                    "timed out attesting the {} socket endpoint: {}",
                    self.label,
                    socket_path.display()
                )
            })?
    }

    /// Removes a stale, owned socket entry at `socket_path` so a fresh bind
    /// can succeed. A live listener, a foreign entry, or an entry that
    /// changes while probing fails the preparation instead.
    ///
    /// # Errors
    ///
    /// Fails when the entry is unsafe, still accepting connections, or the
    /// staleness probe cannot complete.
    pub async fn prepare_socket_path_for_bind(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
    ) -> Result<()> {
        self.prepare_socket_path_for_bind_with_timeout(socket_path, uid, STALE_SOCKET_PROBE_TIMEOUT)
            .await
    }

    async fn prepare_socket_path_for_bind_with_timeout(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
        probe_timeout: Duration,
    ) -> Result<()> {
        // Unlike std's UnixStream::connect, Tokio initiates this connect on a
        // nonblocking descriptor. Dropping the timed-out future closes that
        // descriptor, so a full listener backlog cannot strand helper startup.
        self.prepare_socket_path_for_bind_with_connect(
            socket_path,
            uid,
            probe_timeout,
            UnixStream::connect(socket_path),
        )
        .await
    }

    async fn prepare_socket_path_for_bind_with_connect<F, T>(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
        probe_timeout: Duration,
        connect: F,
    ) -> Result<()>
    where
        F: Future<Output = io::Result<T>>,
    {
        let original_metadata = match fs::symlink_metadata(socket_path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
            Err(error) => {
                return Err(error).with_context(|| {
                    format!(
                        "failed to inspect existing {} socket entry: {}",
                        self.label,
                        socket_path.display()
                    )
                });
            }
        };
        let original_identity =
            self.validate_owned_socket_metadata(socket_path, &original_metadata, uid)?;

        match tokio::time::timeout(probe_timeout, connect).await {
            Ok(Ok(_)) => {
                anyhow::bail!(
                    "{} is already listening at {}",
                    self.label,
                    socket_path.display()
                );
            }
            Ok(Err(error)) if error.kind() == ErrorKind::ConnectionRefused => {}
            Ok(Err(error)) if error.kind() == ErrorKind::NotFound => {
                if matches!(
                    fs::symlink_metadata(socket_path),
                    Err(ref inspect_error) if inspect_error.kind() == ErrorKind::NotFound
                ) {
                    return Ok(());
                }
                anyhow::bail!(
                    "{} socket entry changed while checking whether it is stale: {}",
                    self.label,
                    socket_path.display()
                );
            }
            Ok(Err(error)) => {
                return Err(error).with_context(|| {
                    format!(
                        "could not safely determine whether the {} socket is stale: {}",
                        self.label,
                        socket_path.display()
                    )
                });
            }
            Err(_) => {
                anyhow::bail!(
                    "timed out while checking whether the {} socket is stale: {}",
                    self.label,
                    socket_path.display()
                );
            }
        }

        self.remove_stale_socket_if_unchanged(socket_path, original_identity, uid)
    }

    fn remove_stale_socket_if_unchanged(
        &self,
        socket_path: &Path,
        original_identity: EntryIdentity,
        uid: libc::uid_t,
    ) -> Result<()> {
        warn!(
            label = self.label,
            path = %socket_path.display(),
            "removing stale agent socket"
        );
        self.remove_socket_entry_if_unchanged(socket_path, original_identity, uid)
            .with_context(|| {
                format!(
                    "failed to remove stale {} socket: {}",
                    self.label,
                    socket_path.display()
                )
            })
    }

    /// Restricts the freshly bound socket at `socket_path` to 0600 and
    /// verifies that its identity did not change while securing it.
    ///
    /// # Errors
    ///
    /// Fails when the permissions cannot be applied or the entry was swapped
    /// while being secured.
    pub fn secure_bound_socket(
        &self,
        socket_path: &Path,
        uid: libc::uid_t,
        bound_identity: EntryIdentity,
    ) -> Result<()> {
        fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600)).with_context(|| {
            format!(
                "failed to set permissions on {} socket: {}",
                self.label,
                socket_path.display()
            )
        })?;
        let protected_identity = self.owned_socket_identity(socket_path, uid)?;
        if protected_identity != bound_identity {
            anyhow::bail!(
                "{} socket changed while securing it: {}",
                self.label,
                socket_path.display()
            );
        }
        let socket_mode = fs::symlink_metadata(socket_path)
            .with_context(|| {
                format!(
                    "failed to re-check {} socket permissions: {}",
                    self.label,
                    socket_path.display()
                )
            })?
            .mode()
            & 0o777;
        if socket_mode != 0o600 {
            anyhow::bail!(
                "unsafe {} socket permissions at {}: expected 0600, got {:03o}",
                self.label,
                socket_path.display(),
                socket_mode
            );
        }
        Ok(())
    }

    /// Creates a guard that removes the socket at `socket_path` on drop,
    /// but only while it still has `identity` and is owned by `uid`.
    #[must_use]
    pub fn guard(
        &self,
        socket_path: &Path,
        identity: EntryIdentity,
        uid: libc::uid_t,
    ) -> SocketFileGuard {
        SocketFileGuard {
            lifecycle: *self,
            path: socket_path.to_path_buf(),
            identity,
            uid,
        }
    }

    /// Removes the entry at `socket_path` when it still matches
    /// `expected_identity`, and leaves a replacement untouched otherwise.
    pub fn cleanup_socket_file(
        &self,
        socket_path: &Path,
        expected_identity: EntryIdentity,
        uid: libc::uid_t,
    ) {
        match self.remove_socket_entry_if_unchanged(socket_path, expected_identity, uid) {
            Ok(()) => info!(
                label = self.label,
                path = %socket_path.display(),
                "removed agent socket file"
            ),
            Err(error)
                if error
                    .downcast_ref::<io::Error>()
                    .is_some_and(|error| error.kind() == ErrorKind::NotFound) => {}
            Err(error) => {
                warn!(
                    label = self.label,
                    path = %socket_path.display(),
                    error = %error,
                    "refusing unsafe agent socket cleanup"
                );
            }
        }
    }

    fn remove_socket_entry_if_unchanged(
        &self,
        socket_path: &Path,
        expected_identity: EntryIdentity,
        uid: libc::uid_t,
    ) -> Result<()> {
        let identity = self.owned_socket_identity(socket_path, uid)?;
        if identity != expected_identity {
            anyhow::bail!(
                "{} socket changed before removal: {}",
                self.label,
                socket_path.display()
            );
        }

        fs::remove_file(socket_path).with_context(|| {
            format!(
                "failed to remove {} socket: {}",
                self.label,
                socket_path.display()
            )
        })
    }

    /// Validates that `metadata` describes a non-symlink directory owned by
    /// `uid`, returning its identity. `description` names the directory in
    /// diagnostics, e.g. `"managed GPG agent directory"`.
    ///
    /// # Errors
    ///
    /// Fails when the entry is a symlink, not a directory, or owned by
    /// another user.
    pub fn validate_owned_directory_metadata(
        &self,
        directory: &Path,
        metadata: &fs::Metadata,
        uid: libc::uid_t,
        description: &str,
    ) -> Result<EntryIdentity> {
        if metadata.file_type().is_symlink() {
            anyhow::bail!(
                "unsafe {description} {}: directory is a symlink",
                directory.display()
            );
        }
        if !metadata.file_type().is_dir() {
            anyhow::bail!(
                "unsafe {description} {}: entry is not a directory",
                directory.display()
            );
        }
        if metadata.uid() != uid {
            anyhow::bail!(
                "unsafe {description} {}: owned by uid {}, expected {}",
                directory.display(),
                metadata.uid(),
                uid
            );
        }
        Ok(entry_identity(metadata))
    }

    /// Creates or tightens an agent-managed socket parent directory to mode
    /// 0700, verifying ownership and identity across the permission change.
    ///
    /// # Errors
    ///
    /// Fails when the directory cannot be created or secured, or when it is
    /// unsafe (symlink, non-directory, foreign owner, swapped entry).
    pub fn ensure_safe_managed_parent_dir(&self, parent: &Path, uid: libc::uid_t) -> Result<()> {
        let description = format!("managed {} directory", self.label);
        let mut builder = fs::DirBuilder::new();
        builder.mode(0o700);
        match builder.create(parent) {
            Ok(()) => {}
            Err(e) if e.kind() == ErrorKind::AlreadyExists => {}
            Err(e) => {
                return Err(e).with_context(|| {
                    format!("failed to create {description} {}", parent.display())
                });
            }
        }

        let metadata = fs::symlink_metadata(parent)
            .with_context(|| format!("failed to inspect {description} {}", parent.display()))?;
        let identity =
            self.validate_owned_directory_metadata(parent, &metadata, uid, &description)?;
        if (metadata.mode() & 0o777) != 0o700 {
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700)).with_context(|| {
                format!(
                    "failed to set {description} permissions to 0700: {}",
                    parent.display()
                )
            })?;
        }

        let final_metadata = fs::symlink_metadata(parent)
            .with_context(|| format!("failed to re-check {description} {}", parent.display()))?;
        let final_identity =
            self.validate_owned_directory_metadata(parent, &final_metadata, uid, &description)?;
        if final_identity != identity {
            anyhow::bail!(
                "{description} changed while securing it: {}",
                parent.display()
            );
        }
        let final_mode = final_metadata.mode() & 0o777;
        if final_mode != 0o700 {
            anyhow::bail!(
                "unsafe {description} {}: expected mode 0700, got {:03o}",
                parent.display(),
                final_mode
            );
        }

        Ok(())
    }
}

async fn wait_for_shutdown_signal() {
    use tokio::signal::unix::{signal, SignalKind};

    let mut terminate = signal(SignalKind::terminate()).expect("failed to install SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = terminate.recv() => {}
    }
}

/// Resolves once either a termination signal arrives or the parent process
/// closes the agent's stdin, naming the shutdown reason.
pub async fn wait_for_shutdown_request(parent_stdin_closed: oneshot::Receiver<()>) -> &'static str {
    tokio::select! {
        _ = wait_for_shutdown_signal() => "signal",
        _ = parent_stdin_closed => "parent_stdin_closed",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::os::unix::fs::symlink;
    use std::os::unix::net::{UnixListener as StdUnixListener, UnixStream as StdUnixStream};
    use std::pin::Pin;
    use std::process::{Command, Stdio};
    use std::task::{Context as TaskContext, Poll};
    use std::time::Instant;
    use tempfile::tempdir;
    #[cfg(target_os = "linux")]
    use tokio::net::UnixSocket;

    const LIFECYCLE: SocketLifecycle = SocketLifecycle::new("test agent");
    const LIFECYCLE_LOCK_SUBPROCESS_ENV: &str = "KEYGUARD_AGENT_LOCK_TEST_SOCKET";
    const LIFECYCLE_LOCK_DIRECTORY_SUBPROCESS_ENV: &str = "KEYGUARD_AGENT_LOCK_TEST_DIRECTORY";
    const PEER_LISTENER_SUBPROCESS_ENV: &str = "KEYGUARD_AGENT_PEER_TEST_SOCKET";
    const PEER_LISTENER_READY_SUBPROCESS_ENV: &str = "KEYGUARD_AGENT_PEER_TEST_READY";

    #[test]
    fn socket_lifecycle_lock_subprocess() {
        let Some(socket_path) = std::env::var_os(LIFECYCLE_LOCK_SUBPROCESS_ENV) else {
            return;
        };
        let lock_directory = std::env::var_os(LIFECYCLE_LOCK_DIRECTORY_SUBPROCESS_ENV)
            .map(PathBuf::from)
            .expect("lock test directory");

        let result = LIFECYCLE.acquire_lifecycle_lock_in(
            Path::new(&socket_path),
            current_uid(),
            &lock_directory,
        );
        let Err(error) = result else {
            panic!("concurrent subprocess unexpectedly acquired lifecycle lock");
        };
        assert!(
            error.to_string().contains("already using socket"),
            "unexpected contention error: {error:#}"
        );
    }

    #[test]
    fn socket_attestation_peer_subprocess() {
        let Some(socket_path) = std::env::var_os(PEER_LISTENER_SUBPROCESS_ENV) else {
            return;
        };
        let ready_path =
            std::env::var_os(PEER_LISTENER_READY_SUBPROCESS_ENV).expect("peer test readiness path");
        let socket_path = PathBuf::from(socket_path);
        let _listener = StdUnixListener::bind(&socket_path).expect("bind peer test listener");
        fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600))
            .expect("secure peer test listener");
        fs::write(ready_path, b"ready").expect("publish peer readiness");
        let mut input = Vec::new();
        io::stdin()
            .read_to_end(&mut input)
            .expect("wait for peer test parent");
    }

    #[tokio::test]
    async fn endpoint_attestation_accepts_current_process_listener() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600))
            .expect("secure socket");
        let identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("socket identity");

        LIFECYCLE
            .attest_bound_socket_path(&socket_path, current_uid(), identity)
            .await
            .expect("attest current process listener");
    }

    #[tokio::test]
    async fn endpoint_attestation_rejects_adopted_same_uid_listener() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let ready_path = tmp.path().join("ready");
        let mut child = Command::new(std::env::current_exe().expect("test executable"))
            .args([
                "--exact",
                "socket_lifecycle::tests::socket_attestation_peer_subprocess",
            ])
            .env(PEER_LISTENER_SUBPROCESS_ENV, &socket_path)
            .env(PEER_LISTENER_READY_SUBPROCESS_ENV, &ready_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn peer listener");
        let deadline = Instant::now() + Duration::from_secs(3);
        while !ready_path.exists() {
            assert!(
                child.try_wait().expect("poll peer listener").is_none(),
                "peer listener exited before readiness"
            );
            assert!(
                Instant::now() < deadline,
                "peer listener readiness timed out"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        let adopted_identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("adopted socket identity");

        let error = LIFECYCLE
            .attest_bound_socket_path(&socket_path, current_uid(), adopted_identity)
            .await
            .expect_err("another process listener must fail attestation");

        assert!(error.to_string().contains("belongs to another process"));
        assert!(socket_path.exists());
        drop(child.stdin.take());
        assert!(child.wait().expect("reap peer listener").success());
    }

    #[test]
    fn lifecycle_lock_blocks_second_process_and_persists_inode() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let lock_directory = tmp.path().join("locks");
        let lock_path = lifecycle_lock_path_in(&socket_path, &lock_directory).expect("lock path");
        let stale_listener = StdUnixListener::bind(&socket_path).expect("bind stale socket");
        drop(stale_listener);
        wait_until_socket_is_not_connectable(&socket_path);
        let lifecycle_lock = LIFECYCLE
            .acquire_lifecycle_lock_in(&socket_path, current_uid(), &lock_directory)
            .expect("acquire lock");
        let original_metadata = fs::symlink_metadata(&lock_path).expect("lock metadata");
        let original_identity = entry_identity(&original_metadata);

        let mut child = Command::new(std::env::current_exe().expect("test executable"))
            .args([
                "--exact",
                "socket_lifecycle::tests::socket_lifecycle_lock_subprocess",
            ])
            .env(LIFECYCLE_LOCK_SUBPROCESS_ENV, &socket_path)
            .env(LIFECYCLE_LOCK_DIRECTORY_SUBPROCESS_ENV, &lock_directory)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn lock contender");
        let deadline = Instant::now() + Duration::from_secs(3);
        let status = loop {
            if let Some(status) = child.try_wait().expect("poll lock contender") {
                break status;
            }
            if Instant::now() >= deadline {
                child.kill().expect("stop blocked lock contender");
                child.wait().expect("reap blocked lock contender");
                panic!("concurrent lifecycle-lock acquisition did not fail quickly");
            }
            std::thread::sleep(Duration::from_millis(10));
        };
        assert!(
            status.success(),
            "lock contender did not observe contention"
        );
        assert!(
            socket_path.exists(),
            "contender removed the stale socket without owning the lifecycle lock"
        );

        drop(lifecycle_lock);
        let released_metadata = fs::symlink_metadata(&lock_path).expect("persistent lock metadata");
        assert_eq!(entry_identity(&released_metadata), original_identity);
        assert_eq!(released_metadata.mode() & 0o7777, LIFECYCLE_LOCK_MODE);

        let reacquired = LIFECYCLE
            .acquire_lifecycle_lock_in(&socket_path, current_uid(), &lock_directory)
            .expect("reacquire released lock");
        drop(reacquired);
        assert_eq!(
            entry_identity(&fs::symlink_metadata(&lock_path).expect("final lock metadata")),
            original_identity
        );
    }

    #[test]
    fn lifecycle_lock_rejects_symlink_without_changing_target() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let lock_directory = tmp.path().join("locks");
        LIFECYCLE
            .ensure_lifecycle_lock_directory(&lock_directory, current_uid())
            .expect("prepare lock directory");
        let lock_path = lifecycle_lock_path_in(&socket_path, &lock_directory).expect("lock path");
        let target_path = tmp.path().join("target");
        fs::write(&target_path, b"preserve me").expect("write target");
        fs::set_permissions(&target_path, fs::Permissions::from_mode(0o644))
            .expect("set target mode");
        symlink(&target_path, &lock_path).expect("create lock symlink");

        let result =
            LIFECYCLE.acquire_lifecycle_lock_in(&socket_path, current_uid(), &lock_directory);
        let Err(error) = result else {
            panic!("symlink lifecycle lock unexpectedly succeeded");
        };

        assert!(error.to_string().contains("symlink"));
        assert_eq!(fs::read(&target_path).expect("read target"), b"preserve me");
        assert_eq!(
            fs::symlink_metadata(&target_path)
                .expect("target metadata")
                .mode()
                & 0o7777,
            0o644
        );
        assert!(fs::symlink_metadata(&lock_path)
            .expect("lock symlink metadata")
            .file_type()
            .is_symlink());
    }

    #[test]
    fn lifecycle_lock_rejects_non_file_entry() {
        let tmp = tempdir().expect("tempdir");
        let lock_directory = tmp.path().join("locks");
        LIFECYCLE
            .ensure_lifecycle_lock_directory(&lock_directory, current_uid())
            .expect("prepare lock directory");
        let directory_socket_path = tmp.path().join("directory.sock");
        let directory_lock_path =
            lifecycle_lock_path_in(&directory_socket_path, &lock_directory).expect("lock path");
        fs::create_dir(&directory_lock_path).expect("create lock directory");
        assert!(LIFECYCLE
            .acquire_lifecycle_lock_in(&directory_socket_path, current_uid(), &lock_directory,)
            .is_err());
        assert!(directory_lock_path.is_dir());
    }

    #[test]
    fn lifecycle_lock_rejects_unexpected_owner_uid() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("wrong-owner.sock");
        let lock_directory = tmp.path().join("locks");
        let wrong_uid = current_uid()
            .checked_add(1)
            .expect("test uid must have a successor");

        let result = LIFECYCLE.acquire_lifecycle_lock_in(&socket_path, wrong_uid, &lock_directory);
        let Err(error) = result else {
            panic!("unexpected lifecycle lock owner uid unexpectedly succeeded");
        };

        assert!(error.to_string().contains("owned by uid"));
    }

    #[test]
    fn lifecycle_lock_path_is_fixed_length_and_outside_socket_directory() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("S.gpg-agent");

        let lock_path =
            lifecycle_lock_path(&socket_path, current_uid()).expect("derive lifecycle lock path");

        assert_eq!(
            lock_path.parent(),
            Some(lifecycle_lock_directory(current_uid()).as_path())
        );
        assert!(!lock_path.starts_with(tmp.path()));
        assert_eq!(
            lock_path
                .file_name()
                .expect("lock filename")
                .as_bytes()
                .len(),
            "sha256-".len() + 64 + ".lock".len()
        );
    }

    #[test]
    fn socket_path_aliases_share_one_absolute_address_lock() {
        let tmp = tempdir().expect("tempdir");
        let real_parent = tmp.path().join("real-parent");
        let parent_alias = tmp.path().join("parent-alias");
        let lock_directory = tmp.path().join("locks");
        fs::create_dir(&real_parent).expect("create real socket parent");
        symlink(&real_parent, &parent_alias).expect("create socket parent alias");

        let real_lock = lifecycle_lock_path_in(&real_parent.join("agent.sock"), &lock_directory)
            .expect("derive real lock path");
        let alias_lock = lifecycle_lock_path_in(&parent_alias.join("agent.sock"), &lock_directory)
            .expect("derive alias lock path");

        assert_eq!(real_lock, alias_lock);
    }

    #[test]
    fn different_socket_paths_do_not_share_lifecycle_locks() {
        let tmp = tempdir().expect("tempdir");
        let first_parent = tmp.path().join("first");
        let second_parent = tmp.path().join("second");
        let lock_directory = tmp.path().join("locks");
        fs::create_dir(&first_parent).expect("create first socket parent");
        fs::create_dir(&second_parent).expect("create second socket parent");
        let first_socket = first_parent.join("agent.sock");
        let second_socket = second_parent.join("agent.sock");
        let first_lock_path =
            lifecycle_lock_path_in(&first_socket, &lock_directory).expect("derive first lock path");
        let second_lock_path = lifecycle_lock_path_in(&second_socket, &lock_directory)
            .expect("derive second lock path");

        let first_lock = LIFECYCLE
            .acquire_lifecycle_lock_in(&first_socket, current_uid(), &lock_directory)
            .expect("acquire first lifecycle lock");
        let second_lock = LIFECYCLE
            .acquire_lifecycle_lock_in(&second_socket, current_uid(), &lock_directory)
            .expect("acquire distinct second lifecycle lock");

        assert_ne!(first_lock_path, second_lock_path);
        assert!(first_lock_path.is_file());
        assert!(second_lock_path.is_file());
        drop((first_lock, second_lock));
    }

    #[test]
    fn gpg_socket_directory_is_removable_while_lifecycle_lock_persists_elsewhere() {
        let tmp = tempdir().expect("tempdir");
        let gpg_socket_directory = tmp.path().join("gnupg-sockets");
        let lock_directory = tmp.path().join("keyguard-locks");
        fs::create_dir(&gpg_socket_directory).expect("create GPG socket directory");
        let socket_path = gpg_socket_directory.join("S.gpg-agent");
        let listener = StdUnixListener::bind(&socket_path).expect("bind GPG socket");
        let socket_identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("GPG socket identity");
        let lifecycle_lock = LIFECYCLE
            .acquire_lifecycle_lock_in(&socket_path, current_uid(), &lock_directory)
            .expect("acquire GPG lifecycle lock");
        let lock_path =
            lifecycle_lock_path_in(&socket_path, &lock_directory).expect("derive lock path");

        LIFECYCLE.cleanup_socket_file(&socket_path, socket_identity, current_uid());
        fs::remove_dir(&gpg_socket_directory).expect("remove empty GPG socket directory");

        assert!(!gpg_socket_directory.exists());
        assert!(lock_path.is_file());
        assert!(lock_path.starts_with(&lock_directory));
        assert_eq!(
            fs::symlink_metadata(&lock_directory)
                .expect("lock directory metadata")
                .mode()
                & 0o777,
            0o700
        );
        drop(listener);
        drop(lifecycle_lock);
        assert!(lock_path.is_file());
    }

    #[test]
    fn unix_socket_path_length_is_checked_before_bind() {
        let too_long = PathBuf::from("a".repeat(UNIX_SOCKET_PATH_MAX_BYTES + 1));
        let err = LIFECYCLE
            .validate_socket_path(&too_long)
            .expect_err("must fail");
        assert!(err.to_string().contains("too long"));

        let maximum = PathBuf::from("a".repeat(UNIX_SOCKET_PATH_MAX_BYTES));
        LIFECYCLE
            .validate_socket_path(&maximum)
            .expect("maximum length should pass preflight");
    }

    #[test]
    fn safe_managed_parent_is_created_with_0700() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("managed-parent");

        LIFECYCLE
            .ensure_safe_managed_parent_dir(&parent, current_uid())
            .expect("prepare parent");

        let metadata = fs::symlink_metadata(&parent).expect("metadata");
        assert!(metadata.file_type().is_dir());
        assert_eq!(metadata.mode() & 0o777, 0o700);
    }

    #[test]
    fn safe_managed_parent_permissions_are_tightened_to_0700() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("managed-parent");
        fs::create_dir(&parent).expect("create parent");
        fs::set_permissions(&parent, fs::Permissions::from_mode(0o755)).expect("chmod 755");

        LIFECYCLE
            .ensure_safe_managed_parent_dir(&parent, current_uid())
            .expect("prepare parent");

        let metadata = fs::symlink_metadata(&parent).expect("metadata");
        assert_eq!(metadata.mode() & 0o777, 0o700);
    }

    #[test]
    fn safe_managed_parent_rejects_symlink_non_directory_and_wrong_owner() {
        let tmp = tempdir().expect("tempdir");
        let target = tmp.path().join("real-parent");
        let link = tmp.path().join("link-parent");
        let file = tmp.path().join("file-parent");
        fs::create_dir(&target).expect("create target");
        symlink(&target, &link).expect("create symlink");
        fs::write(&file, b"not a directory").expect("write file");

        let link_error = LIFECYCLE
            .ensure_safe_managed_parent_dir(&link, current_uid())
            .expect_err("must fail");
        assert!(link_error.to_string().contains("symlink"));

        let file_error = LIFECYCLE
            .ensure_safe_managed_parent_dir(&file, current_uid())
            .expect_err("must fail");
        assert!(file_error.to_string().contains("not a directory"));

        let wrong_uid = current_uid()
            .checked_add(1)
            .expect("test uid must have a successor");
        let owner_error = LIFECYCLE
            .ensure_safe_managed_parent_dir(&target, wrong_uid)
            .expect_err("must fail");
        assert!(owner_error.to_string().contains("owned by uid"));
    }

    #[tokio::test]
    async fn stale_owned_socket_is_removed_before_bind() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        drop(listener);
        wait_until_socket_is_not_connectable(&socket_path);

        LIFECYCLE
            .prepare_socket_path_for_bind(&socket_path, current_uid())
            .await
            .expect("remove stale socket");

        assert!(!socket_path.exists());
    }

    fn wait_until_socket_is_not_connectable(socket_path: &Path) {
        let deadline = Instant::now() + Duration::from_millis(250);
        loop {
            match StdUnixStream::connect(socket_path) {
                Err(error)
                    if matches!(
                        error.kind(),
                        ErrorKind::ConnectionRefused | ErrorKind::NotFound
                    ) =>
                {
                    return;
                }
                Err(error) => panic!(
                    "unexpected error while waiting for dropped test listener at {}: {error}",
                    socket_path.display()
                ),
                Ok(stream) => drop(stream),
            }

            assert!(
                Instant::now() < deadline,
                "dropped test listener remained connectable at {}",
                socket_path.display()
            );
            std::thread::sleep(Duration::from_millis(1));
        }
    }

    #[tokio::test]
    async fn live_socket_is_not_removed_before_bind() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");

        let error = LIFECYCLE
            .prepare_socket_path_for_bind(&socket_path, current_uid())
            .await
            .expect_err("must fail");

        assert!(error.to_string().contains("already listening"));
        assert!(socket_path.exists());
    }

    struct PendingConnect {
        _stream: StdUnixStream,
    }

    impl Future for PendingConnect {
        type Output = io::Result<()>;

        fn poll(self: Pin<&mut Self>, _context: &mut TaskContext<'_>) -> Poll<Self::Output> {
            Poll::Pending
        }
    }

    #[tokio::test(start_paused = true)]
    async fn timed_out_socket_probe_preserves_path_and_closes_connect_fd() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        let (pending_stream, mut peer) = StdUnixStream::pair().expect("create probe socket pair");

        let error = LIFECYCLE
            .prepare_socket_path_for_bind_with_connect(
                &socket_path,
                current_uid(),
                Duration::from_millis(25),
                PendingConnect {
                    _stream: pending_stream,
                },
            )
            .await
            .expect_err("pending probe must time out");

        assert!(error.to_string().contains("timed out"));
        assert!(socket_path.exists());
        let mut byte = [0u8; 1];
        assert_eq!(
            peer.read(&mut byte).expect("read closed probe peer"),
            0,
            "timed-out connect descriptor must be closed"
        );
    }

    #[tokio::test]
    async fn unknown_socket_probe_error_preserves_path() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        let connect = std::future::ready(Err::<(), _>(io::Error::new(
            ErrorKind::PermissionDenied,
            "injected probe failure",
        )));

        let error = LIFECYCLE
            .prepare_socket_path_for_bind_with_connect(
                &socket_path,
                current_uid(),
                Duration::from_millis(25),
                connect,
            )
            .await
            .expect_err("unknown probe failure must be preserved");

        assert!(error
            .to_string()
            .contains("could not safely determine whether"));
        assert!(socket_path.exists());
    }

    #[cfg(target_os = "linux")]
    async fn saturate_listener(socket_path: &Path) -> (tokio::net::UnixListener, Vec<UnixStream>) {
        let socket = UnixSocket::new_stream().expect("create listener socket");
        socket.bind(socket_path).expect("bind listener socket");
        let listener = socket.listen(1).expect("listen with minimal backlog");
        let mut clients = Vec::new();

        for _ in 0..256 {
            match tokio::time::timeout(Duration::from_millis(25), UnixStream::connect(socket_path))
                .await
            {
                Ok(Ok(client)) => clients.push(client),
                Ok(Err(error)) if error.kind() == ErrorKind::WouldBlock => {
                    return (listener, clients);
                }
                Err(_) => return (listener, clients),
                Ok(Err(error)) => panic!("unexpected saturation error: {error}"),
            }
        }

        panic!("listener backlog did not saturate after 256 connections");
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn saturated_live_socket_probe_is_bounded_and_preserves_socket() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let (_listener, _clients) = saturate_listener(&socket_path).await;
        let probe_timeout = Duration::from_millis(25);
        let started_at = Instant::now();

        let error = LIFECYCLE
            .prepare_socket_path_for_bind_with_timeout(&socket_path, current_uid(), probe_timeout)
            .await
            .expect_err("a saturated live listener must not be considered stale");

        assert!(
            started_at.elapsed() < Duration::from_secs(1),
            "saturated listener probe exceeded its bound"
        );
        assert!(
            error.to_string().contains("timed out")
                || error
                    .to_string()
                    .contains("could not safely determine whether")
        );
        assert!(socket_path.exists());
    }

    #[test]
    fn replaced_socket_is_not_removed_after_stale_probe() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let replacement_path = tmp.path().join("replacement.sock");
        let stale_listener = StdUnixListener::bind(&socket_path).expect("bind stale socket");
        let stale_identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("stale identity");
        drop(stale_listener);
        let replacement_listener =
            StdUnixListener::bind(&replacement_path).expect("bind replacement socket");
        fs::remove_file(&socket_path).expect("unlink stale socket");
        fs::rename(&replacement_path, &socket_path).expect("install replacement socket");

        let error = LIFECYCLE
            .remove_stale_socket_if_unchanged(&socket_path, stale_identity, current_uid())
            .expect_err("replacement must not be removed");

        assert!(format!("{error:#}").contains("changed"));
        assert!(socket_path.exists());
        drop(replacement_listener);
    }

    #[tokio::test]
    async fn unsafe_existing_entries_are_not_removed_before_bind() {
        let tmp = tempdir().expect("tempdir");
        let file_path = tmp.path().join("file.sock");
        let link_path = tmp.path().join("link.sock");
        fs::write(&file_path, b"not a socket").expect("write file");
        symlink(&file_path, &link_path).expect("create symlink");

        let file_error = LIFECYCLE
            .prepare_socket_path_for_bind(&file_path, current_uid())
            .await
            .expect_err("file must fail");
        assert!(file_error.to_string().contains("not a Unix socket"));
        assert!(file_path.exists());

        let link_error = LIFECYCLE
            .prepare_socket_path_for_bind(&link_path, current_uid())
            .await
            .expect_err("link must fail");
        assert!(link_error.to_string().contains("symlink"));
        assert!(link_path.exists());
    }

    #[tokio::test]
    async fn socket_owned_by_another_uid_is_not_removed_before_bind() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        let wrong_uid = current_uid()
            .checked_add(1)
            .expect("test uid must have a successor");

        let error = LIFECYCLE
            .prepare_socket_path_for_bind(&socket_path, wrong_uid)
            .await
            .expect_err("must fail");

        assert!(error.to_string().contains("owned by uid"));
        assert!(socket_path.exists());
    }

    #[test]
    fn secure_bound_socket_enforces_owner_only_mode() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        let identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("identity");

        LIFECYCLE
            .secure_bound_socket(&socket_path, current_uid(), identity)
            .expect("secure socket");

        assert_eq!(
            fs::symlink_metadata(&socket_path).expect("metadata").mode() & 0o777,
            0o600
        );
    }

    #[test]
    fn secure_bound_socket_rejects_replaced_entry() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let replacement_path = tmp.path().join("replacement.sock");
        let _original_listener = StdUnixListener::bind(&socket_path).expect("bind original");
        let original_identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("original identity");
        let _replacement_listener =
            StdUnixListener::bind(&replacement_path).expect("bind replacement");
        fs::remove_file(&socket_path).expect("unlink original");
        fs::rename(&replacement_path, &socket_path).expect("replace socket path");

        let error = LIFECYCLE
            .secure_bound_socket(&socket_path, current_uid(), original_identity)
            .expect_err("replaced socket must fail");

        assert!(error.to_string().contains("changed while securing it"));
        assert!(socket_path.exists());
    }

    #[test]
    fn cleanup_removes_only_the_socket_with_the_expected_identity() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let replacement_path = tmp.path().join("replacement.sock");
        let original_listener = StdUnixListener::bind(&socket_path).expect("bind original");
        let original_identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("original identity");
        let replacement_listener =
            StdUnixListener::bind(&replacement_path).expect("bind replacement");

        fs::remove_file(&socket_path).expect("unlink original");
        fs::rename(&replacement_path, &socket_path).expect("replace socket path");
        LIFECYCLE.cleanup_socket_file(&socket_path, original_identity, current_uid());

        assert!(socket_path.exists());
        drop(original_listener);
        drop(replacement_listener);
    }

    #[test]
    fn cleanup_removes_matching_socket() {
        let tmp = tempdir().expect("tempdir");
        let socket_path = tmp.path().join("agent.sock");
        let listener = StdUnixListener::bind(&socket_path).expect("bind socket");
        let identity = LIFECYCLE
            .owned_socket_identity(&socket_path, current_uid())
            .expect("identity");

        LIFECYCLE.cleanup_socket_file(&socket_path, identity, current_uid());

        assert!(!socket_path.exists());
        drop(listener);
    }

    #[tokio::test]
    async fn shutdown_request_reports_parent_stdin_closed() {
        let (sender, receiver) = tokio::sync::oneshot::channel::<()>();
        drop(sender);

        let reason = wait_for_shutdown_request(receiver).await;

        assert_eq!(reason, "parent_stdin_closed");
    }
}
