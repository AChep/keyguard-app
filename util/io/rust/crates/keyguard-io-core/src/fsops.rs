//! Leaf filesystem primitives behind the fault-injection seam.
//!
//! [`FsOps`] is the complete set of syscalls the atomic-write protocol makes,
//! so the protocol in `txn.rs` can be exercised against an in-memory
//! simulated filesystem with power cuts injected between any two steps. The
//! release build monomorphizes [`RealFs`], which compiles to direct syscalls.

use std::{io, path::Path, time::Duration};

#[cfg(unix)]
use crate::naming::{TemporaryArtifactProtocol, new_temporary_artifact_names};
use crate::{
    durability::{SyncLevel, platform_max_sync_level},
    naming::{MAX_TEMPORARY_ARTIFACT_ATTEMPTS, TemporaryFileRole},
    txn::DirectoryPermissions,
};

/// SyncLevel strength of a single flush call.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FlushKind {
    /// Order finalized file data and metadata ahead of publication.
    Ordered,
    /// Force finalized file data and metadata onto stable storage.
    Durable,
}

/// Reported strength of a completed flush.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FlushOutcome {
    /// The requested flush strength was achieved.
    Full,
    /// A weaker ordering-only flush was substituted (for example plain
    /// `fsync` where `F_FULLFSYNC` is unsupported).
    Degraded,
    /// The filesystem supports no flush at all; the write is only as durable
    /// as an unsynced write.
    Unsupported,
}

/// A newly-created staged artifact together with its retained producer lease.
#[derive(Debug)]
pub struct CreatedStaged<F> {
    /// Canonical data-entry name relative to the retained parent directory.
    pub name: String,
    /// Open staged data file. Platform implementations may attach a producer
    /// lease whose lifetime is coupled to this value.
    pub file: F,
}

/// Semantic classification attached to a staged-creation failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StagedCreationFailureKind {
    /// Infer the failure kind from the native error.
    Inferred,
    /// A cooperating writer or sweeper currently owns an incompatible lease.
    ResourceBusy,
    /// The filesystem provides neither supported lease protocol.
    Unsupported,
}

/// Failure returned while creating a fully leased staged artifact.
#[derive(Debug)]
pub struct StagedCreationError {
    error: io::Error,
    kind: StagedCreationFailureKind,
    cleanup_incomplete: bool,
}

impl StagedCreationError {
    pub(crate) fn inferred(error: io::Error) -> Self {
        Self {
            error,
            kind: StagedCreationFailureKind::Inferred,
            cleanup_incomplete: false,
        }
    }

    #[cfg(any(unix, test))]
    pub(crate) fn classified(error: io::Error, kind: StagedCreationFailureKind) -> Self {
        Self {
            error,
            kind,
            cleanup_incomplete: false,
        }
    }

    #[cfg(any(unix, test))]
    pub(crate) fn with_cleanup_incomplete(mut self) -> Self {
        self.cleanup_incomplete = true;
        self
    }

    /// Returns the native primary failure.
    #[must_use]
    pub const fn error(&self) -> &io::Error {
        &self.error
    }

    /// Returns the semantic failure classification.
    #[must_use]
    pub const fn kind(&self) -> StagedCreationFailureKind {
        self.kind
    }

    /// Returns whether cleanup after the primary failure left a recognizable
    /// temporary artifact behind.
    #[must_use]
    pub const fn cleanup_incomplete(&self) -> bool {
        self.cleanup_incomplete
    }

    #[cfg(unix)]
    pub(crate) fn into_error(self) -> io::Error {
        self.error
    }
}

impl From<io::Error> for StagedCreationError {
    fn from(error: io::Error) -> Self {
        Self::inferred(error)
    }
}

/// Namespace state left by the publication primitive.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StagedNameResidual {
    /// Rename consumed the staged data name.
    AbsentAfterRename,
    /// Hard-link publication retained the staged data name.
    PresentAfterHardLink,
}

/// Opaque identity of a filesystem object retained across publication.
///
/// Equality is meaningful only for objects observed through the same retained
/// destination-directory capability.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FileIdentity {
    scheme: IdentityScheme,
    volume: u64,
    object: [u8; 16],
}

/// How a platform expressed an object's identity.
///
/// Part of the identity rather than an implementation detail, so two identities
/// obtained through different schemes are never equal. Microsoft documents no
/// relationship between the 128-bit `FILE_ID_INFO` file ID and the 64-bit
/// `BY_HANDLE_FILE_INFORMATION` file index, so treating them as interchangeable
/// would rest on an unverified coincidence in either direction. Tagging makes a
/// scheme change resolve to "not the same object", and every caller reads that
/// as a reason to withhold action: `reconcile_ambiguous_publication` reports
/// `PublicationUnknown` and the sweep classifies the candidate `Changed`
/// instead of removing it.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum IdentityScheme {
    /// POSIX `st_dev` with `st_ino`.
    #[cfg(unix)]
    PosixInode,
    /// Windows volume serial with the 128-bit `FILE_ID_INFO` file ID.
    #[cfg(windows)]
    WindowsFileId128,
    /// Windows volume serial with the 64-bit `BY_HANDLE_FILE_INFORMATION` file
    /// index, used by volumes that do not implement `FileIdInfo`.
    #[cfg(windows)]
    WindowsFileIndex64,
    /// Simulator identity.
    #[cfg(test)]
    Simulated,
}

impl FileIdentity {
    const fn from_parts(scheme: IdentityScheme, volume: u64, object: [u8; 16]) -> Self {
        Self {
            scheme,
            volume,
            object,
        }
    }

    fn from_scalar(scheme: IdentityScheme, volume: u64, object: u64) -> Self {
        let mut bytes = [0_u8; 16];
        bytes[..8].copy_from_slice(&object.to_ne_bytes());
        Self::from_parts(scheme, volume, bytes)
    }

    #[cfg(unix)]
    fn posix(device: u64, inode: u64) -> Self {
        Self::from_scalar(IdentityScheme::PosixInode, device, inode)
    }

    #[cfg(windows)]
    fn windows_file_id_128(volume: u64, object: [u8; 16]) -> Self {
        Self::from_parts(IdentityScheme::WindowsFileId128, volume, object)
    }

    #[cfg(windows)]
    fn windows_file_index_64(volume: u64, index: u64) -> Self {
        Self::from_scalar(IdentityScheme::WindowsFileIndex64, volume, index)
    }

    #[cfg(test)]
    pub(crate) fn simulated(inode: u64) -> Self {
        Self::from_scalar(IdentityScheme::Simulated, 0, inode)
    }
}

/// Whether a failed publication request is proven not to have changed the
/// namespace.
#[derive(Debug)]
pub enum PublicationAttemptError {
    /// Validation or capability rejection happened before mutation dispatch.
    DefinitelyUnchanged(io::Error),
    /// A namespace request was dispatched, so the returned error is not proof
    /// that the mutation did not take effect.
    MayHaveMutated(io::Error),
}

impl PublicationAttemptError {
    /// Returns the native failure reported by the publication primitive.
    #[must_use]
    pub const fn error(&self) -> &io::Error {
        match self {
            Self::DefinitelyUnchanged(error) | Self::MayHaveMutated(error) => error,
        }
    }
}

/// Result of cleanup after publication could not be reconciled.
#[derive(Debug)]
pub enum PublicationUnknownCleanup {
    /// Exact-identity cleanup and close completed.
    Complete,
    /// Cleanup is incomplete, with an optional native cleanup failure.
    ///
    /// `None` means cleanup was deliberately not attempted because it could
    /// have deleted the destination.
    Incomplete(Option<io::Error>),
}

/// Cleanup policy after an ambiguous publication was positively reconciled.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AmbiguousPublicationCleanup {
    /// Observe and remove only a temporary name that still identifies the
    /// retained staged object.
    ExactStagedName,
    /// Retain the staged handle through the publication barrier, then close it
    /// without name removal or disposition.
    CloseOnly,
}

/// The complete syscall surface of the atomic-write protocol.
///
/// All names are UTF-8 and relative to the transaction's destination
/// directory, which implementations address through an anchored directory
/// handle where the platform supports one.
pub trait FsOps {
    /// Anchored destination-directory handle.
    type Dir;
    /// Open native file.
    type File;
    /// Snapshot of replaceable destination basic permissions.
    type Metadata;

    /// Strongest synchronization level this backend advertises before any
    /// filesystem access.
    ///
    /// Native backends inherit the current platform contract. Simulated or
    /// specialized backends may override the ceiling when they model a
    /// different synchronization environment. Wrappers that preserve the
    /// wrapped backend's guarantees should forward its advertised ceiling.
    fn advertised_sync_level_ceiling(&self) -> SyncLevel {
        platform_max_sync_level()
    }

    /// Opens the absolute filesystem root used to resolve a destination.
    fn open_root(&self, path: &Path) -> io::Result<Self::Dir>;

    /// Opens one child directory relative to `parent`.
    ///
    /// When `follow_links` is false, the final component must be a real
    /// directory rather than a symlink or reparse point.
    fn open_dir_at(
        &self,
        parent: &Self::Dir,
        name: &str,
        follow_links: bool,
    ) -> io::Result<Self::Dir>;

    /// Creates one child directory relative to `parent`.
    fn create_dir_at(
        &self,
        parent: &Self::Dir,
        name: &str,
        permissions: DirectoryPermissions,
    ) -> io::Result<()>;

    /// Exclusively creates and returns one child directory.
    ///
    /// Platforms whose creation primitive returns an open handle should
    /// override this method to avoid re-resolving the newly-created name.
    fn create_and_open_dir_at(
        &self,
        parent: &Self::Dir,
        name: &str,
        permissions: DirectoryPermissions,
    ) -> io::Result<Self::Dir> {
        self.create_dir_at(parent, name, permissions)?;
        self.open_dir_at(parent, name, false)
    }

    /// Exclusively creates `name` inside `dir`.
    ///
    /// The file is created readable and writable with mode `0600` when
    /// `owner_only` is requested, and never follows an existing name.
    fn create_file_at(
        &self,
        dir: &Self::Dir,
        name: &str,
        owner_only: bool,
    ) -> io::Result<Self::File>;

    /// Creates a staged artifact only after establishing a producer lease.
    ///
    /// POSIX implementations select the directory-lease v2 protocol when
    /// possible and fall back to the sidecar-lease v2 protocol. Other
    /// platforms may use an equivalent native lifetime guarantee.
    fn create_staged_at(
        &self,
        dir: &Self::Dir,
        role: TemporaryFileRole,
        owner_only: bool,
    ) -> Result<CreatedStaged<Self::File>, StagedCreationError>;

    /// Appends the complete buffer to `file`.
    fn write_all(&self, file: &mut Self::File, buffer: &[u8]) -> io::Result<()>;

    /// Flushes file data and metadata with the requested strength.
    fn flush_file(&self, file: &mut Self::File, kind: FlushKind) -> io::Result<FlushOutcome>;

    /// Reads the basic permissions of an existing regular destination without
    /// following a symbolic link or reparse point, or returns `None` when the
    /// destination does not exist.
    fn read_replace_metadata(
        &self,
        dir: &Self::Dir,
        name: &str,
    ) -> io::Result<Option<Self::Metadata>>;

    /// Applies previously captured basic permissions to the staged file.
    fn apply_replace_metadata(
        &self,
        file: &mut Self::File,
        metadata: &Self::Metadata,
    ) -> io::Result<()>;

    /// Verifies that the staged file has the captured basic permissions.
    fn verify_replace_metadata(
        &self,
        file: &mut Self::File,
        metadata: &Self::Metadata,
    ) -> io::Result<()>;

    /// Captures the exact identity of the retained staged file.
    fn staged_file_identity(&self, file: &Self::File) -> io::Result<FileIdentity>;

    /// Observes `name` relative to `dir` without following its final link.
    fn observe_file_identity_at(
        &self,
        dir: &Self::Dir,
        name: &str,
    ) -> io::Result<Option<FileIdentity>>;

    /// Renames `from` to `to` inside `dir`.
    ///
    /// With `no_replace` the rename fails with an already-exists error when
    /// the destination exists. After every result, `file` must continue to
    /// identify the original retained staged object so an ambiguous result can
    /// be reconciled against its captured identity.
    fn rename(
        &self,
        dir: &Self::Dir,
        from: &str,
        file: &mut Self::File,
        to: &str,
        no_replace: bool,
    ) -> Result<(), PublicationAttemptError>;

    /// Creates a hard link `to` referring to `from`, both inside `dir`.
    ///
    /// This is the exclusive-publication fallback for filesystems without an
    /// exclusive rename primitive.
    fn hard_link(
        &self,
        dir: &Self::Dir,
        from: &str,
        file: &Self::File,
        to: &str,
    ) -> Result<(), PublicationAttemptError>;

    /// Removes `name` from `dir`.
    fn unlink(&self, dir: &Self::Dir, name: &str) -> io::Result<()>;

    /// Persists metadata changes made inside an arbitrary directory.
    ///
    /// The transaction uses this barrier while preparing newly-created parent
    /// components, before it creates the staged file. Support for this barrier
    /// is independent of file and publication synchronization support.
    fn flush_directory(&self, dir: &Self::Dir) -> io::Result<FlushOutcome>;

    /// Persists the directory entry produced by a completed rename.
    ///
    /// POSIX targets flush the anchored directory handle; Windows flushes the
    /// still-open renamed file handle, its closest equivalent. Support for
    /// this post-publication barrier is independent of file and created-parent
    /// synchronization support.
    fn flush_publication(&self, dir: &Self::Dir, file: &mut Self::File)
    -> io::Result<FlushOutcome>;

    /// Closes the staged file handle.
    fn close(&self, file: Self::File) -> io::Result<()>;

    /// Removes any staged namespace residual after successful publication.
    ///
    /// Implementations keep the producer lease held until the data name is
    /// proven absent and any lease sidecar has been removed.
    fn finalize_staged_after_publication(
        &self,
        dir: &Self::Dir,
        name: &str,
        file: &mut Self::File,
        residual: StagedNameResidual,
    ) -> io::Result<()> {
        let _ = file;
        match residual {
            StagedNameResidual::AbsentAfterRename => Ok(()),
            StagedNameResidual::PresentAfterHardLink => self.unlink(dir, name),
        }
    }

    /// Removes the exact staged file and then closes it.
    ///
    /// The default keeps the retained file—and any lease attached to it—open
    /// until the residual name has been removed. Handle-capable platforms
    /// should override it when deletion can be attached directly to identity.
    fn discard_staged(&self, dir: &Self::Dir, name: &str, file: Self::File) -> io::Result<()> {
        let removal = self.unlink(dir, name);
        let close = self.close(file);
        removal.and(close)
    }

    /// Cleans up only the exact retained staged object after publication could
    /// not be reconciled.
    fn cleanup_after_publication_unknown(
        &self,
        dir: &Self::Dir,
        name: &str,
        file: Self::File,
    ) -> PublicationUnknownCleanup {
        match self.discard_staged(dir, name, file) {
            Ok(()) => PublicationUnknownCleanup::Complete,
            Err(error) => PublicationUnknownCleanup::Incomplete(Some(error)),
        }
    }

    /// Selects cleanup behavior after identity establishes that an ambiguous
    /// publication did take effect.
    ///
    /// Handle-relative platforms where the retained handle may already name
    /// the destination must return [`AmbiguousPublicationCleanup::CloseOnly`].
    fn ambiguous_publication_cleanup(&self) -> AmbiguousPublicationCleanup {
        AmbiguousPublicationCleanup::ExactStagedName
    }

    /// Returns whether a definitely-unchanged rename error means the primitive
    /// is unsupported and a fallback strategy should be attempted.
    ///
    /// This predicate is never authoritative for an error returned after
    /// dispatch; such errors must enter the ambiguous-publication path instead.
    fn is_rename_unsupported(&self, error: &io::Error) -> bool;

    /// Returns whether a hard-link error means this filesystem provides no
    /// hard links, rather than that one link was refused.
    ///
    /// Separate from [`FsOps::is_rename_unsupported`] because the two
    /// primitives report the condition with different codes: the rename
    /// predicate must not admit `EPERM`, which is how Linux `link(2)` spells
    /// "the filesystem does not support the creation of hard links". The
    /// default keeps platforms without a distinct answer on their existing
    /// behavior.
    fn is_hard_link_unsupported(&self, error: &io::Error) -> bool {
        self.is_rename_unsupported(error)
    }

    /// Returns whether a definitely-unchanged rename preparation error is
    /// transient interference worth retrying.
    ///
    /// An implementation must not use this predicate for an error returned
    /// after dispatch.
    fn is_rename_retryable(&self, error: &io::Error) -> bool;

    /// Bounded backoff schedule for retryable rename errors.
    fn rename_retry_delays(&self) -> &[Duration];

    /// Sleeps between rename retries.
    fn sleep(&self, duration: Duration) {
        std::thread::sleep(duration);
    }
}

/// Production [`FsOps`] implementation for the current target.
#[derive(Clone, Copy, Debug, Default)]
pub struct RealFs;

#[cfg(unix)]
mod posix {
    use std::{
        ffi::CString,
        fs::File,
        io::{self, Write},
        os::{
            fd::{AsRawFd, FromRawFd, IntoRawFd, OwnedFd, RawFd},
            unix::ffi::OsStrExt,
        },
        path::Path,
        time::Duration,
    };

    use super::{
        CreatedStaged, DirectoryPermissions, FileIdentity, FlushKind, FlushOutcome, FsOps,
        PublicationAttemptError, RealFs, StagedCreationError, StagedCreationFailureKind,
        StagedNameResidual, TemporaryArtifactProtocol, TemporaryFileRole,
        new_temporary_artifact_names,
    };

    /// Anchored destination-directory file descriptor.
    pub struct PosixDir {
        fd: OwnedFd,
    }

    /// POSIX staged data together with the lease protecting its temporary
    /// namespace entry.
    pub struct PosixFile {
        data: File,
        lease: Option<PosixProducerLease>,
    }

    impl AsRawFd for PosixFile {
        fn as_raw_fd(&self) -> RawFd {
            self.data.as_raw_fd()
        }
    }

    enum PosixProducerLease {
        Directory(DirectoryWriterLease),
        Sidecar {
            name: String,
            file: File,
            identity: FileIdentity,
        },
    }

    /// Shared producer lease for a destination directory.
    ///
    /// Releasing explicitly unlocks the shared open-file description before
    /// closing this process's descriptor. That also clears the lock from any
    /// descriptor inherited across `fork`, avoiding a stale Busy result until
    /// the child reaches `exec`.
    struct DirectoryWriterLease {
        fd: Option<OwnedFd>,
    }

    impl DirectoryWriterLease {
        fn new(fd: OwnedFd) -> Self {
            Self { fd: Some(fd) }
        }

        fn release(mut self) -> io::Result<()> {
            let fd = self
                .fd
                .take()
                .expect("directory writer lease descriptor must be present");
            let unlock = unlock_directory(fd.as_raw_fd());
            let close = close_owned_fd(fd);
            unlock.and(close)
        }
    }

    impl AsRawFd for DirectoryWriterLease {
        fn as_raw_fd(&self) -> RawFd {
            self.fd
                .as_ref()
                .expect("directory writer lease descriptor must be present")
                .as_raw_fd()
        }
    }

    impl Drop for DirectoryWriterLease {
        fn drop(&mut self) {
            if let Some(fd) = self.fd.as_ref() {
                let _ = unlock_directory(fd.as_raw_fd());
            }
        }
    }

    /// Captured ordinary POSIX access bits for Replace publications.
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub struct PosixReplaceMetadata {
        mode: libc::mode_t,
    }

    fn c_name(name: &str) -> io::Result<CString> {
        CString::new(name.as_bytes()).map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))
    }

    fn c_path(path: &Path) -> io::Result<CString> {
        CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))
    }

    fn is_unsupported_errno(error: &io::Error) -> bool {
        // Comparisons instead of a pattern: ENOTSUP and EOPNOTSUPP alias on
        // Linux, which a `|` pattern would report as unreachable.
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::EINVAL
                || code == libc::ENOSYS
                || code == libc::ENOTSUP
                || code == libc::EOPNOTSUPP
                || code == libc::EXDEV
        )
    }

    /// Android's API level, or `0` when it cannot be determined.
    ///
    /// Android installs a seccomp-bpf filter on every app process whose
    /// allowlist is generated from the *device's* bionic, so a syscall's
    /// availability is a property of the platform version rather than of the
    /// kernel. A number outside the generated ranges is answered with
    /// `SECCOMP_RET_TRAP` — SIGSYS, then debuggerd — and the filter runs before
    /// syscall dispatch, so no errno is ever returned for the caller to
    /// classify. Raw syscalls must therefore be gated on the level that
    /// allowlisted them instead of probed.
    ///
    /// A failed property read caches `0`, which closes every capability gate.
    #[cfg(target_os = "android")]
    fn android_api_level() -> u32 {
        use std::sync::atomic::{AtomicU32, Ordering};

        // Stores `level + 1` so that zero remains the unprobed sentinel.
        static CACHED: AtomicU32 = AtomicU32::new(0);

        let cached = CACHED.load(Ordering::Relaxed);
        if cached != 0 {
            return cached - 1;
        }
        let level = read_android_api_level().unwrap_or(0);
        CACHED.store(level.saturating_add(1), Ordering::Relaxed);
        level
    }

    #[cfg(target_os = "android")]
    fn read_android_api_level() -> Option<u32> {
        // bionic's PROP_VALUE_MAX; the writable buffer must admit the trailing
        // NUL that `__system_property_get` always appends.
        const PROP_VALUE_MAX: usize = 92;

        let mut value = [0_u8; PROP_VALUE_MAX];
        // SAFETY: The property name is a NUL-terminated static string and
        // `value` is writable storage of exactly the documented maximum size.
        let length = unsafe {
            libc::__system_property_get(
                c"ro.build.version.sdk".as_ptr(),
                value.as_mut_ptr().cast::<libc::c_char>(),
            )
        };
        let length = usize::try_from(length).ok()?;
        std::str::from_utf8(value.get(..length)?)
            .ok()?
            .trim()
            .parse()
            .ok()
    }

    /// Whether an Android platform version's seccomp allowlist admits
    /// `renameat2`.
    ///
    /// It entered `SECCOMP_WHITELIST_COMMON.TXT` in API 28 (Android 9); on API
    /// 26 and 27 the number is in no allowlist and is therefore trapped.
    #[cfg(any(target_os = "android", test))]
    const fn android_exclusive_rename_permitted(api_level: u32) -> bool {
        api_level >= 28
    }

    /// Whether an Android platform version's seccomp allowlist admits `statx`.
    ///
    /// It entered `SYSCALLS.TXT` in API 30 (Android 11). Below that the mount
    /// identifier is simply unavailable, which [`same_mount`] already degrades
    /// to a device comparison.
    #[cfg(any(target_os = "android", test))]
    const fn android_mount_id_query_permitted(api_level: u32) -> bool {
        api_level >= 30
    }

    /// Whether the platform's syscall filter admits `renameat2`.
    #[cfg(target_os = "linux")]
    const fn exclusive_rename_permitted() -> bool {
        true
    }

    #[cfg(target_os = "android")]
    fn exclusive_rename_permitted() -> bool {
        android_exclusive_rename_permitted(android_api_level())
    }

    /// Whether the platform's syscall filter admits `statx`.
    #[cfg(target_os = "linux")]
    const fn mount_id_query_permitted() -> bool {
        true
    }

    #[cfg(target_os = "android")]
    fn mount_id_query_permitted() -> bool {
        android_mount_id_query_permitted(android_api_level())
    }

    // `openat2` has no counterpart predicate: it is absent from every bionic
    // allowlist to date, so it is compiled out on Android entirely rather than
    // gated on a level that would eventually admit it.
    #[cfg(target_os = "linux")]
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum OpenAt2ErrorAction {
        FallBackToMountIdValidation,
        Return,
    }

    #[cfg(target_os = "linux")]
    fn classify_openat2_error(error: &io::Error) -> OpenAt2ErrorAction {
        match error.raw_os_error() {
            // ENOSYS covers old kernels, EINVAL covers kernels that do not
            // recognize the resolve flags, and EPERM covers common seccomp
            // policies that block the newer syscall. The fallback remains
            // fail-closed because it requires a retained statx mount ID.
            Some(code)
                if code == libc::ENOSYS
                    || code == libc::EINVAL
                    || code == libc::E2BIG
                    || code == libc::EPERM =>
            {
                OpenAt2ErrorAction::FallBackToMountIdValidation
            }
            _ => OpenAt2ErrorAction::Return,
        }
    }

    #[cfg(target_os = "linux")]
    #[repr(C)]
    struct OpenHow {
        flags: u64,
        mode: u64,
        resolve: u64,
    }

    #[cfg(target_vendor = "apple")]
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    struct MountIdentity {
        fsid: [u8; size_of::<libc::fsid_t>()],
        mount_point: [libc::c_char; libc::MAXPATHLEN as usize],
    }

    /// Linux mount identity, strongest-available first.
    ///
    /// `mount_id` distinguishes bind mounts that share a device; `device` is
    /// the pre-5.8 fallback that does not. See [`statx_mount_id`].
    /// Widened rather than `libc::dev_t`: 32-bit Android targets declare
    /// `dev_t` as `u32` while `stat::st_dev` is still `u64`.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[derive(Clone, Copy, Debug)]
    struct MountIdentity {
        device: u64,
        mount_id: Option<u64>,
    }

    #[cfg(not(any(target_os = "linux", target_os = "android", target_vendor = "apple")))]
    type MountIdentity = libc::dev_t;

    /// Compares two mount identities using the strongest signal both carry.
    ///
    /// Mixing the two Linux signals would be a category error, so a missing
    /// mount identifier on either side demotes the whole comparison to the
    /// device.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn same_mount(parent: &MountIdentity, child: &MountIdentity) -> bool {
        match (parent.mount_id, child.mount_id) {
            (Some(parent_id), Some(child_id)) => parent_id == child_id,
            _ => parent.device == child.device,
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn same_mount(parent: &MountIdentity, child: &MountIdentity) -> bool {
        parent == child
    }

    fn ensure_same_mount(parent: &MountIdentity, child: &MountIdentity) -> io::Result<()> {
        if same_mount(parent, child) {
            Ok(())
        } else {
            Err(io::Error::from_raw_os_error(libc::EXDEV))
        }
    }

    fn openat_directory(
        parent_fd: RawFd,
        name: &std::ffi::CStr,
        no_follow: bool,
    ) -> io::Result<OwnedFd> {
        let mut flags = libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC;
        if no_follow {
            flags |= libc::O_NOFOLLOW;
        }
        // SAFETY: The name is NUL-terminated, the parent descriptor is valid,
        // and the call has no out-pointer arguments.
        let fd = unsafe { libc::openat(parent_fd, name.as_ptr(), flags) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat` returned a new, uniquely-owned descriptor.
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }

    #[cfg(target_os = "linux")]
    fn openat2_directory_without_links_or_mounts(
        parent_fd: RawFd,
        name: &std::ffi::CStr,
    ) -> io::Result<OwnedFd> {
        // These stable UAPI constants are not exposed by every libc.
        const RESOLVE_NO_XDEV: u64 = 0x01;
        const RESOLVE_NO_SYMLINKS: u64 = 0x04;
        const RESOLVE_BENEATH: u64 = 0x08;

        let how = OpenHow {
            flags: (libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW) as u64,
            mode: 0,
            resolve: RESOLVE_NO_XDEV | RESOLVE_NO_SYMLINKS | RESOLVE_BENEATH,
        };
        // SAFETY: `name` and `how` remain valid for the duration of the
        // syscall, `how` uses the kernel's fixed open_how prefix layout, and
        // the parent descriptor is retained by the caller.
        let fd = unsafe {
            libc::syscall(
                libc::SYS_openat2,
                parent_fd,
                name.as_ptr(),
                std::ptr::addr_of!(how),
                size_of::<OpenHow>(),
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat2` returned a new, uniquely-owned descriptor.
        Ok(unsafe { OwnedFd::from_raw_fd(fd as RawFd) })
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn mount_identity(fd: RawFd) -> io::Result<MountIdentity> {
        Ok(MountIdentity {
            device: file_identity_component(stat_fd_raw(fd)?.st_dev, "device")?,
            mount_id: statx_mount_id(fd),
        })
    }

    /// Returns the kernel's mount identifier, or `None` when this kernel has
    /// none to give.
    ///
    /// `STATX_MNT_ID` arrived in Linux 5.8 and `statx` itself in 4.11. Below
    /// 5.8 the syscall either fails outright or — per statx(2) — "the mask bit
    /// corresponding to that field will be cleared in stx_mask even if the
    /// user asked for it" while still reporting success.
    ///
    /// Neither is a reason to refuse the write. This path is already the
    /// fallback for a kernel without `openat2` (5.6), and Android's supported
    /// range reaches back to 4.19 and 5.4 kernels, so on those devices a
    /// missing mount identifier is the common case rather than an edge case.
    /// Treating it as fatal would make every atomic write impossible there.
    ///
    /// The caller degrades to the filesystem device, which is exactly what
    /// every non-Linux Unix target uses. That still rejects ordinary mount
    /// crossings but cannot distinguish a bind mount deliberately retaining
    /// the same device; `O_NOFOLLOW` continues to block symlink escapes
    /// either way.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn statx_mount_id(fd: RawFd) -> Option<u64> {
        if !mount_id_query_permitted() {
            return None;
        }
        let mut stat = std::mem::MaybeUninit::<libc::statx>::uninit();
        // SAFETY: The empty pathname is NUL-terminated, AT_EMPTY_PATH makes
        // `fd` the object being queried, and `stat` is writable storage of
        // exactly the kernel statx layout.
        let result = unsafe {
            libc::syscall(
                libc::SYS_statx,
                fd,
                c"".as_ptr(),
                libc::AT_EMPTY_PATH | libc::AT_NO_AUTOMOUNT | libc::AT_STATX_SYNC_AS_STAT,
                libc::STATX_MNT_ID,
                stat.as_mut_ptr(),
            )
        };
        if result != 0 {
            return None;
        }
        // SAFETY: A successful statx call initialized the output structure.
        let stat = unsafe { stat.assume_init() };
        if stat.stx_mask & libc::STATX_MNT_ID == 0 {
            return None;
        }
        Some(stat.stx_mnt_id)
    }

    #[cfg(target_vendor = "apple")]
    fn mount_identity(fd: RawFd) -> io::Result<MountIdentity> {
        let mut stat = std::mem::MaybeUninit::<libc::statfs>::uninit();
        // SAFETY: `fd` is retained and `stat` is writable storage of the exact
        // type expected by fstatfs.
        if unsafe { libc::fstatfs(fd, stat.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: A successful fstatfs call initialized the output structure.
        let stat = unsafe { stat.assume_init() };
        let mut fsid = [0_u8; size_of::<libc::fsid_t>()];
        // SAFETY: `stat.f_fsid` is initialized, and the destination has its
        // exact byte size. Apple fsid_t is an opaque pair of kernel integers,
        // so copying its representation is the only stable public comparison.
        unsafe {
            std::ptr::copy_nonoverlapping(
                std::ptr::addr_of!(stat.f_fsid).cast::<u8>(),
                fsid.as_mut_ptr(),
                fsid.len(),
            );
        }
        Ok(MountIdentity {
            fsid,
            mount_point: stat.f_mntonname,
        })
    }

    #[cfg(not(any(target_os = "linux", target_os = "android", target_vendor = "apple")))]
    fn mount_identity(fd: RawFd) -> io::Result<MountIdentity> {
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: `fd` is retained and `stat` is writable storage of the exact
        // type expected by fstat.
        if unsafe { libc::fstat(fd, stat.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: A successful fstat call initialized the output structure.
        let stat = unsafe { stat.assume_init() };
        // Portable POSIX exposes only the filesystem device through fstat.
        // This rejects ordinary mount crossings but cannot distinguish a bind
        // mount that deliberately retains the same st_dev.
        Ok(stat.st_dev)
    }

    fn openat_directory_without_links_or_mounts(
        parent_fd: RawFd,
        name: &std::ffi::CStr,
    ) -> io::Result<OwnedFd> {
        // Android is deliberately excluded: `openat2` is absent from every
        // bionic seccomp allowlist, so issuing it would raise SIGSYS rather
        // than return a classifiable errno. See [`android_api_level`].
        #[cfg(target_os = "linux")]
        {
            match openat2_directory_without_links_or_mounts(parent_fd, name) {
                Ok(fd) => return Ok(fd),
                Err(error)
                    if classify_openat2_error(&error)
                        == OpenAt2ErrorAction::FallBackToMountIdValidation => {}
                Err(error) => return Err(error),
            }
        }

        let child = openat_directory(parent_fd, name, true)?;
        let parent_identity = mount_identity(parent_fd)?;
        let child_identity = mount_identity(child.as_raw_fd())?;
        ensure_same_mount(&parent_identity, &child_identity)?;
        Ok(child)
    }

    impl FsOps for RealFs {
        type Dir = PosixDir;
        type File = PosixFile;
        type Metadata = PosixReplaceMetadata;

        fn open_root(&self, path: &Path) -> io::Result<PosixDir> {
            let path = c_path(path)?;
            // SAFETY: The path is NUL-terminated for the duration of the call
            // and the flag set contains no out-pointer arguments.
            let fd = unsafe {
                libc::open(
                    path.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
                )
            };
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }
            // SAFETY: `open` succeeded and returned a uniquely-owned descriptor.
            let fd = unsafe { OwnedFd::from_raw_fd(fd) };
            Ok(PosixDir { fd })
        }

        fn open_dir_at(
            &self,
            parent: &PosixDir,
            name: &str,
            follow_links: bool,
        ) -> io::Result<PosixDir> {
            let name = c_name(name)?;
            let fd = if follow_links {
                openat_directory(parent.fd.as_raw_fd(), &name, false)?
            } else {
                openat_directory_without_links_or_mounts(parent.fd.as_raw_fd(), &name)?
            };
            Ok(PosixDir { fd })
        }

        fn create_dir_at(
            &self,
            parent: &PosixDir,
            name: &str,
            permissions: DirectoryPermissions,
        ) -> io::Result<()> {
            let name = c_name(name)?;
            let mode: libc::mode_t = match permissions {
                DirectoryPermissions::OwnerOnly => 0o700,
                DirectoryPermissions::ProcessDefault => 0o777,
            };
            // SAFETY: The name is NUL-terminated and the parent descriptor is
            // valid for the duration of mkdirat.
            if unsafe { libc::mkdirat(parent.fd.as_raw_fd(), name.as_ptr(), mode) } == 0 {
                Ok(())
            } else {
                Err(io::Error::last_os_error())
            }
        }

        fn create_file_at(
            &self,
            dir: &PosixDir,
            name: &str,
            owner_only: bool,
        ) -> io::Result<PosixFile> {
            create_data_file_at(dir.fd.as_raw_fd(), name, owner_only)
                .map(|data| PosixFile { data, lease: None })
        }

        fn create_staged_at(
            &self,
            dir: &PosixDir,
            role: TemporaryFileRole,
            owner_only: bool,
        ) -> Result<CreatedStaged<PosixFile>, StagedCreationError> {
            create_posix_staged_at(dir.fd.as_raw_fd(), role, owner_only)
        }

        fn write_all(&self, file: &mut PosixFile, buffer: &[u8]) -> io::Result<()> {
            file.data.write_all(buffer)
        }

        fn flush_file(&self, file: &mut PosixFile, kind: FlushKind) -> io::Result<FlushOutcome> {
            flush_fd(file.data.as_raw_fd(), kind)
        }

        fn read_replace_metadata(
            &self,
            dir: &PosixDir,
            name: &str,
        ) -> io::Result<Option<PosixReplaceMetadata>> {
            let name = c_name(name)?;
            let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
            // SAFETY: The name is NUL-terminated, the directory descriptor is
            // valid, and `stat` points to writable storage of the exact type.
            let result = unsafe {
                libc::fstatat(
                    dir.fd.as_raw_fd(),
                    name.as_ptr(),
                    stat.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            };
            if result != 0 {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::ENOENT) {
                    return Ok(None);
                }
                return Err(error);
            }
            // SAFETY: `fstatat` succeeded, so the buffer is fully initialized.
            let stat = unsafe { stat.assume_init() };
            if u64::from(stat.st_mode) & u64::from(libc::S_IFMT) != u64::from(libc::S_IFREG) {
                return Err(io::Error::from_raw_os_error(libc::EINVAL));
            }
            Ok(Some(PosixReplaceMetadata {
                mode: (u64::from(stat.st_mode) & 0o777) as libc::mode_t,
            }))
        }

        fn apply_replace_metadata(
            &self,
            file: &mut PosixFile,
            metadata: &PosixReplaceMetadata,
        ) -> io::Result<()> {
            // SAFETY: The descriptor is valid and the mode was masked to
            // permission bits when captured.
            let result = unsafe { libc::fchmod(file.data.as_raw_fd(), metadata.mode) };
            if result != 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        }

        fn verify_replace_metadata(
            &self,
            file: &mut PosixFile,
            metadata: &PosixReplaceMetadata,
        ) -> io::Result<()> {
            let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
            // SAFETY: The descriptor is valid and `stat` points to writable
            // storage of the exact type.
            if unsafe { libc::fstat(file.data.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
                return Err(io::Error::last_os_error());
            }
            // SAFETY: `fstat` succeeded, so the buffer is fully initialized.
            let stat = unsafe { stat.assume_init() };
            if u64::from(stat.st_mode) & 0o777 != u64::from(metadata.mode) {
                return Err(io::Error::other(
                    "staged file permissions differ from the captured destination permissions",
                ));
            }
            Ok(())
        }

        fn staged_file_identity(&self, file: &PosixFile) -> io::Result<FileIdentity> {
            file_identity(&stat_fd_raw(file.data.as_raw_fd())?)
        }

        fn observe_file_identity_at(
            &self,
            dir: &PosixDir,
            name: &str,
        ) -> io::Result<Option<FileIdentity>> {
            match stat_name_at(dir.fd.as_raw_fd(), name) {
                Ok(stat) => file_identity(&stat).map(Some),
                Err(error) if error.raw_os_error() == Some(libc::ENOENT) => Ok(None),
                Err(error) => Err(error),
            }
        }

        fn rename(
            &self,
            dir: &PosixDir,
            from: &str,
            file: &mut PosixFile,
            to: &str,
            no_replace: bool,
        ) -> Result<(), PublicationAttemptError> {
            let from = c_name(from).map_err(PublicationAttemptError::DefinitelyUnchanged)?;
            let to = c_name(to).map_err(PublicationAttemptError::DefinitelyUnchanged)?;
            let dir_fd = dir.fd.as_raw_fd();
            match verify_named_file_at_c_name(dir_fd, &from, &file.data)
                .map_err(PublicationAttemptError::DefinitelyUnchanged)?
            {
                NamedFileBinding::Same => {}
                NamedFileBinding::Absent => {
                    return Err(PublicationAttemptError::DefinitelyUnchanged(
                        io::Error::from(io::ErrorKind::NotFound),
                    ));
                }
                NamedFileBinding::Changed => {
                    return Err(PublicationAttemptError::DefinitelyUnchanged(
                        io::Error::other("rename source no longer refers to retained file"),
                    ));
                }
            }
            if no_replace {
                #[cfg(not(any(
                    target_os = "linux",
                    target_os = "android",
                    target_vendor = "apple"
                )))]
                {
                    let _ = (dir_fd, from, to);
                    return Err(PublicationAttemptError::DefinitelyUnchanged(
                        io::Error::from_raw_os_error(libc::ENOSYS),
                    ));
                }
                #[cfg(any(target_os = "linux", target_os = "android"))]
                {
                    if !exclusive_rename_permitted() {
                        // The syscall filter would trap `renameat2` with SIGSYS
                        // before the kernel saw it, so it must not be issued at
                        // all. ENOSYS is the same answer a kernel without the
                        // syscall gives, which routes publication to the
                        // exclusive hard-link fallback.
                        return Err(PublicationAttemptError::DefinitelyUnchanged(
                            io::Error::from_raw_os_error(libc::ENOSYS),
                        ));
                    }
                    return rename_exclusive(dir_fd, &from, &to)
                        .map_err(classify_exclusive_rename_error);
                }
                #[cfg(target_vendor = "apple")]
                {
                    return rename_exclusive(dir_fd, &from, &to)
                        .map_err(classify_exclusive_rename_error);
                }
            }
            // SAFETY: Both names are NUL-terminated for the duration of the
            // call and the directory descriptor is valid.
            let result = unsafe { libc::renameat(dir_fd, from.as_ptr(), dir_fd, to.as_ptr()) };
            if result == 0 {
                Ok(())
            } else {
                Err(PublicationAttemptError::MayHaveMutated(
                    io::Error::last_os_error(),
                ))
            }
        }

        fn hard_link(
            &self,
            dir: &PosixDir,
            from: &str,
            file: &PosixFile,
            to: &str,
        ) -> Result<(), PublicationAttemptError> {
            let from = c_name(from).map_err(PublicationAttemptError::DefinitelyUnchanged)?;
            let to = c_name(to).map_err(PublicationAttemptError::DefinitelyUnchanged)?;
            let dir_fd = dir.fd.as_raw_fd();
            match verify_named_file_at_c_name(dir_fd, &from, &file.data)
                .map_err(PublicationAttemptError::DefinitelyUnchanged)?
            {
                NamedFileBinding::Same => {}
                NamedFileBinding::Absent => {
                    return Err(PublicationAttemptError::DefinitelyUnchanged(
                        io::Error::from(io::ErrorKind::NotFound),
                    ));
                }
                NamedFileBinding::Changed => {
                    return Err(PublicationAttemptError::DefinitelyUnchanged(
                        io::Error::other("hard-link source no longer refers to retained file"),
                    ));
                }
            }
            // SAFETY: Both names are NUL-terminated for the duration of the
            // call and the directory descriptor is valid.
            let result = unsafe { libc::linkat(dir_fd, from.as_ptr(), dir_fd, to.as_ptr(), 0) };
            if result == 0 {
                return Ok(());
            }
            let error = io::Error::last_os_error();
            if hard_link_definitely_unchanged(&error) {
                return Err(PublicationAttemptError::DefinitelyUnchanged(error));
            }
            Err(PublicationAttemptError::MayHaveMutated(error))
        }

        fn unlink(&self, dir: &PosixDir, name: &str) -> io::Result<()> {
            unlink_name_at(dir.fd.as_raw_fd(), name)
        }

        fn flush_directory(&self, dir: &PosixDir) -> io::Result<FlushOutcome> {
            flush_directory_fd(dir.fd.as_raw_fd())
        }

        fn flush_publication(
            &self,
            dir: &PosixDir,
            _file: &mut PosixFile,
        ) -> io::Result<FlushOutcome> {
            flush_directory_fd(dir.fd.as_raw_fd())
        }

        fn close(&self, file: PosixFile) -> io::Result<()> {
            close_posix_file(file)
        }

        fn finalize_staged_after_publication(
            &self,
            dir: &PosixDir,
            name: &str,
            file: &mut PosixFile,
            residual: StagedNameResidual,
        ) -> io::Result<()> {
            finalize_posix_staged(dir.fd.as_raw_fd(), name, file, residual)
        }

        fn discard_staged(
            &self,
            dir: &PosixDir,
            name: &str,
            mut file: PosixFile,
        ) -> io::Result<()> {
            let removal = remove_posix_staged_data(
                dir.fd.as_raw_fd(),
                name,
                &file.data,
                StagedNameResidual::PresentAfterHardLink,
            )
            .and_then(|()| remove_posix_lease_after_data(dir.fd.as_raw_fd(), &mut file));
            let close = close_posix_file(file);
            removal.and(close)
        }

        fn is_rename_unsupported(&self, error: &io::Error) -> bool {
            exclusive_rename_capability_absent(error)
        }

        fn is_hard_link_unsupported(&self, error: &io::Error) -> bool {
            hard_link_capability_absent(error)
        }

        fn is_rename_retryable(&self, _error: &io::Error) -> bool {
            false
        }

        fn rename_retry_delays(&self) -> &[Duration] {
            &[]
        }
    }

    fn create_data_file_at(directory_fd: RawFd, name: &str, owner_only: bool) -> io::Result<File> {
        let name = c_name(name)?;
        let mode: libc::c_uint = if owner_only { 0o600 } else { 0o666 };
        // SAFETY: The name is NUL-terminated for the duration of the call,
        // the directory descriptor is valid, and O_CREAT passes the mode as
        // the documented fourth argument.
        let fd = unsafe {
            libc::openat(
                directory_fd,
                name.as_ptr(),
                libc::O_CREAT | libc::O_EXCL | libc::O_RDWR | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                mode,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat` succeeded and returned a uniquely-owned descriptor.
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum LeaseAttempt {
        Acquired,
        Busy,
        Unsupported,
    }

    enum DirectoryLeaseAttempt {
        Acquired(DirectoryWriterLease),
        Unsupported,
    }

    /// Bounded wait for a contended directory writer lease, roughly 190 ms.
    ///
    /// The writer and the sweeper both probe with `LOCK_NB`, so each backs off
    /// rather than blocking — but only the sweeper had a retry policy
    /// (`TemporaryArtifactMaintenance`'s one- and five-minute ladder). A sweep
    /// that won the race therefore turned a user-initiated save into a hard
    /// `ResourceBusy`, while the housekeeping pass that displaced it simply tried
    /// again later: the priority was inverted, and only the lower-priority side
    /// was resilient.
    ///
    /// The writer is the user-facing operation, so it is the side that waits.
    /// Short enough to stay imperceptible in a save path, and long enough to
    /// outlast a sweep of a small directory — a sweep large enough to exceed it
    /// still surfaces `ResourceBusy`, which is why this reduces the window
    /// rather than closing it.
    const DIRECTORY_LEASE_RETRY_DELAYS: [Duration; 8] = [
        Duration::from_millis(1),
        Duration::from_millis(2),
        Duration::from_millis(4),
        Duration::from_millis(8),
        Duration::from_millis(16),
        Duration::from_millis(32),
        Duration::from_millis(64),
        Duration::from_millis(64),
    ];

    /// Retries `acquire` while it reports the lease busy.
    ///
    /// Only [`StagedCreationFailureKind::ResourceBusy`] is retried: an inferred
    /// or unsupported failure will not resolve by waiting, and a cleanup-
    /// incomplete result must surface immediately so the caller learns a staged
    /// artifact may remain.
    ///
    /// `sleep` is injected so the ladder is testable without real delays.
    fn retry_while_lease_busy<T>(
        delays: &[Duration],
        mut sleep: impl FnMut(Duration),
        mut acquire: impl FnMut() -> Result<T, StagedCreationError>,
    ) -> Result<T, StagedCreationError> {
        let mut attempt = 0;
        loop {
            let error = match acquire() {
                Ok(value) => return Ok(value),
                Err(error) => error,
            };
            let retryable = error.kind() == StagedCreationFailureKind::ResourceBusy
                && !error.cleanup_incomplete()
                && attempt < delays.len();
            if !retryable {
                return Err(error);
            }
            sleep(delays[attempt]);
            attempt += 1;
        }
    }

    fn create_posix_staged_at(
        directory_fd: RawFd,
        role: TemporaryFileRole,
        owner_only: bool,
    ) -> Result<CreatedStaged<PosixFile>, StagedCreationError> {
        let lease =
            retry_while_lease_busy(&DIRECTORY_LEASE_RETRY_DELAYS, std::thread::sleep, || {
                acquire_directory_writer_lease(directory_fd)
            })?;
        match lease {
            DirectoryLeaseAttempt::Acquired(lease) => {
                create_directory_lease_staged(directory_fd, role, owner_only, lease)
            }
            DirectoryLeaseAttempt::Unsupported => {
                create_sidecar_lease_staged(directory_fd, role, owner_only)
            }
        }
    }

    pub(crate) fn create_pathless_named_scratch_at(directory_fd: RawFd) -> io::Result<File> {
        let staged = create_posix_staged_at(directory_fd, TemporaryFileRole::Scratch, true)
            .map_err(StagedCreationError::into_error)?;
        make_staged_scratch_pathless(directory_fd, staged)
    }

    fn make_staged_scratch_pathless(
        directory_fd: RawFd,
        staged: CreatedStaged<PosixFile>,
    ) -> io::Result<File> {
        let CreatedStaged {
            name,
            file: mut staged,
        } = staged;
        if let Err(error) = finalize_posix_staged(
            directory_fd,
            &name,
            &mut staged,
            StagedNameResidual::PresentAfterHardLink,
        ) {
            let _ = close_posix_file(staged);
            return Err(error);
        }
        let PosixFile { data, lease } = staged;
        debug_assert!(lease.is_none());
        Ok(data)
    }

    #[cfg(test)]
    fn create_pathless_sidecar_scratch_at(directory_fd: RawFd) -> io::Result<File> {
        let staged = create_sidecar_lease_staged(directory_fd, TemporaryFileRole::Scratch, true)
            .map_err(StagedCreationError::into_error)?;
        make_staged_scratch_pathless(directory_fd, staged)
    }

    fn create_directory_lease_staged(
        directory_fd: RawFd,
        role: TemporaryFileRole,
        owner_only: bool,
        lease: DirectoryWriterLease,
    ) -> Result<CreatedStaged<PosixFile>, StagedCreationError> {
        for _ in 0..super::MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
            let names =
                new_temporary_artifact_names(role, TemporaryArtifactProtocol::DirectoryLeaseV1)
                    .map_err(StagedCreationError::inferred)?;
            match create_data_file_at(directory_fd, &names.data, owner_only) {
                Ok(data) => {
                    return Ok(CreatedStaged {
                        name: names.data,
                        file: PosixFile {
                            data,
                            lease: Some(PosixProducerLease::Directory(lease)),
                        },
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(StagedCreationError::inferred(error)),
            }
        }
        Err(StagedCreationError::inferred(io::Error::from(
            io::ErrorKind::AlreadyExists,
        )))
    }

    fn create_sidecar_lease_staged(
        directory_fd: RawFd,
        role: TemporaryFileRole,
        owner_only: bool,
    ) -> Result<CreatedStaged<PosixFile>, StagedCreationError> {
        for _ in 0..super::MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
            let names =
                new_temporary_artifact_names(role, TemporaryArtifactProtocol::SidecarLeaseV1)
                    .map_err(StagedCreationError::inferred)?;
            let sidecar_name = names
                .lease
                .as_deref()
                .expect("sidecar protocol must generate a lease name");
            let sidecar = match create_sidecar_at(directory_fd, sidecar_name) {
                Ok(file) => file,
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(StagedCreationError::inferred(error)),
            };
            let identity = prepare_sidecar(&sidecar).map_err(|error| {
                cleanup_pending_sidecar(
                    directory_fd,
                    sidecar_name,
                    &sidecar,
                    StagedCreationError::inferred(error),
                )
            })?;
            match try_lock_exclusive(sidecar.as_raw_fd()) {
                Ok(LeaseAttempt::Acquired) => {}
                Ok(LeaseAttempt::Busy) => {
                    // A sweeper may win the narrow create-to-lock race. Without
                    // the lease this process must not unlink the sidecar.
                    drop(sidecar);
                    return Err(StagedCreationError::classified(
                        io::Error::from(io::ErrorKind::ResourceBusy),
                        StagedCreationFailureKind::ResourceBusy,
                    )
                    .with_cleanup_incomplete());
                }
                Ok(LeaseAttempt::Unsupported) => {
                    return Err(cleanup_pending_sidecar(
                        directory_fd,
                        sidecar_name,
                        &sidecar,
                        StagedCreationError::classified(
                            io::Error::from(io::ErrorKind::Unsupported),
                            StagedCreationFailureKind::Unsupported,
                        ),
                    ));
                }
                Err(error) => {
                    return Err(cleanup_pending_sidecar(
                        directory_fd,
                        sidecar_name,
                        &sidecar,
                        StagedCreationError::inferred(error),
                    ));
                }
            }
            if let Err(error) =
                revalidate_sidecar_at(directory_fd, sidecar_name, &sidecar, identity)
            {
                return Err(cleanup_pending_sidecar(
                    directory_fd,
                    sidecar_name,
                    &sidecar,
                    StagedCreationError::inferred(error),
                ));
            }
            match ensure_name_absent(directory_fd, &names.data) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                    if unlink_exact_file(directory_fd, sidecar_name, &sidecar).is_err() {
                        return Err(StagedCreationError::inferred(error).with_cleanup_incomplete());
                    }
                    continue;
                }
                Err(error) => {
                    return Err(cleanup_pending_sidecar(
                        directory_fd,
                        sidecar_name,
                        &sidecar,
                        StagedCreationError::inferred(error),
                    ));
                }
            }
            match create_data_file_at(directory_fd, &names.data, owner_only) {
                Ok(data) => {
                    return Ok(CreatedStaged {
                        name: names.data,
                        file: PosixFile {
                            data,
                            lease: Some(PosixProducerLease::Sidecar {
                                name: sidecar_name.to_owned(),
                                file: sidecar,
                                identity,
                            }),
                        },
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                    if unlink_exact_file(directory_fd, sidecar_name, &sidecar).is_err() {
                        return Err(StagedCreationError::inferred(error).with_cleanup_incomplete());
                    }
                }
                Err(error) => {
                    return Err(cleanup_pending_sidecar(
                        directory_fd,
                        sidecar_name,
                        &sidecar,
                        StagedCreationError::inferred(error),
                    ));
                }
            }
        }
        Err(StagedCreationError::inferred(io::Error::from(
            io::ErrorKind::AlreadyExists,
        )))
    }

    fn acquire_directory_writer_lease(
        directory_fd: RawFd,
    ) -> Result<DirectoryLeaseAttempt, StagedCreationError> {
        let before = stat_fd_raw(directory_fd).map_err(StagedCreationError::inferred)?;
        if !stat_is_directory(&before) {
            return Err(StagedCreationError::inferred(io::Error::other(
                "staged parent descriptor is not a directory",
            )));
        }
        let fresh = DirectoryWriterLease::new(
            openat_directory(directory_fd, c".", true).map_err(StagedCreationError::inferred)?,
        );
        let opened = stat_fd_raw(fresh.as_raw_fd()).map_err(StagedCreationError::inferred)?;
        if !same_stat_identity(&before, &opened) || !stat_is_directory(&opened) {
            return Err(StagedCreationError::inferred(io::Error::other(
                "fresh directory lease descriptor changed identity",
            )));
        }

        match try_lock_directory(fresh.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)? {
            LeaseAttempt::Acquired => {
                match try_lock_directory(fresh.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB)? {
                    LeaseAttempt::Acquired => {}
                    LeaseAttempt::Busy => {
                        return Err(StagedCreationError::classified(
                            io::Error::from(io::ErrorKind::ResourceBusy),
                            StagedCreationFailureKind::ResourceBusy,
                        ));
                    }
                    LeaseAttempt::Unsupported => {
                        return Err(StagedCreationError::inferred(io::Error::other(
                            "directory lease became unsupported during lock conversion",
                        )));
                    }
                }
            }
            LeaseAttempt::Busy => {
                match try_lock_directory(fresh.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB)? {
                    LeaseAttempt::Acquired => {}
                    LeaseAttempt::Busy => {
                        return Err(StagedCreationError::classified(
                            io::Error::from(io::ErrorKind::ResourceBusy),
                            StagedCreationFailureKind::ResourceBusy,
                        ));
                    }
                    LeaseAttempt::Unsupported => {
                        return Err(StagedCreationError::inferred(io::Error::other(
                            "directory lease probe returned inconsistent results",
                        )));
                    }
                }
            }
            LeaseAttempt::Unsupported => return Ok(DirectoryLeaseAttempt::Unsupported),
        }

        let locked = stat_fd_raw(fresh.as_raw_fd()).map_err(StagedCreationError::inferred)?;
        if !same_stat_identity(&before, &locked) || !stat_is_directory(&locked) {
            return Err(StagedCreationError::inferred(io::Error::other(
                "directory identity changed while acquiring writer lease",
            )));
        }
        Ok(DirectoryLeaseAttempt::Acquired(fresh))
    }

    fn try_lock_directory(fd: RawFd, operation: libc::c_int) -> io::Result<LeaseAttempt> {
        loop {
            // SAFETY: `fd` is a retained directory descriptor and flock has
            // no pointer arguments.
            if unsafe { libc::flock(fd, operation) } == 0 {
                return Ok(LeaseAttempt::Acquired);
            }
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if error.kind() == io::ErrorKind::WouldBlock {
                return Ok(LeaseAttempt::Busy);
            }
            if lock_capability_absent(&error) {
                return Ok(LeaseAttempt::Unsupported);
            }
            return Err(error);
        }
    }

    fn unlock_directory(fd: RawFd) -> io::Result<()> {
        loop {
            // SAFETY: `fd` owns an acquired directory lease and flock has no
            // pointer arguments.
            if unsafe { libc::flock(fd, libc::LOCK_UN) } == 0 {
                return Ok(());
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }

    fn try_lock_exclusive(fd: RawFd) -> io::Result<LeaseAttempt> {
        loop {
            // SAFETY: `fd` is retained and flock has no pointer arguments.
            if unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) } == 0 {
                return Ok(LeaseAttempt::Acquired);
            }
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if error.kind() == io::ErrorKind::WouldBlock {
                return Ok(LeaseAttempt::Busy);
            }
            if lock_capability_absent(&error) {
                return Ok(LeaseAttempt::Unsupported);
            }
            return Err(error);
        }
    }

    fn create_sidecar_at(directory_fd: RawFd, name: &str) -> io::Result<File> {
        let name = c_name(name)?;
        // SAFETY: The name is NUL-terminated, the parent descriptor is valid,
        // and O_CREAT supplies the documented mode argument.
        let fd = unsafe {
            libc::openat(
                directory_fd,
                name.as_ptr(),
                libc::O_CREAT | libc::O_EXCL | libc::O_RDWR | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                0o600,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat` returned a new uniquely-owned descriptor.
        let file = unsafe { File::from_raw_fd(fd) };
        Ok(file)
    }

    fn prepare_sidecar(file: &File) -> io::Result<FileIdentity> {
        // SAFETY: `file` retains the descriptor and the fixed mode has the
        // platform's mode_t representation.
        if unsafe { libc::fchmod(file.as_raw_fd(), 0o600) } != 0 {
            return Err(io::Error::last_os_error());
        }
        stat_file_identity(file)
    }

    fn validate_sidecar_stat(stat: &libc::stat) -> io::Result<()> {
        // SAFETY: geteuid has no arguments and no failure mode.
        let effective_user = unsafe { libc::geteuid() };
        if !stat_is_regular(stat)
            || stat.st_uid != effective_user
            || u64::from(stat.st_mode) & 0o777 != 0o600
            || stat.st_nlink != 1
        {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "lease sidecar is not an owner-only single-link regular file",
            ));
        }
        Ok(())
    }

    fn stat_file_identity(file: &File) -> io::Result<FileIdentity> {
        let stat = stat_fd_raw(file.as_raw_fd())?;
        validate_sidecar_stat(&stat)?;
        file_identity(&stat)
    }

    fn revalidate_sidecar_at(
        directory_fd: RawFd,
        name: &str,
        file: &File,
        identity: FileIdentity,
    ) -> io::Result<()> {
        let retained = stat_fd_raw(file.as_raw_fd())?;
        validate_sidecar_stat(&retained)?;
        if file_identity(&retained)? != identity {
            return Err(io::Error::other("retained sidecar identity changed"));
        }
        let named = stat_name_at(directory_fd, name)?;
        validate_sidecar_stat(&named)?;
        if file_identity(&named)? != identity {
            return Err(io::Error::other(
                "lease sidecar name no longer refers to retained file",
            ));
        }
        Ok(())
    }

    fn cleanup_pending_sidecar(
        directory_fd: RawFd,
        name: &str,
        file: &File,
        primary: StagedCreationError,
    ) -> StagedCreationError {
        if unlink_exact_file(directory_fd, name, file).is_err() {
            primary.with_cleanup_incomplete()
        } else {
            primary
        }
    }

    fn finalize_posix_staged(
        directory_fd: RawFd,
        name: &str,
        file: &mut PosixFile,
        residual: StagedNameResidual,
    ) -> io::Result<()> {
        remove_posix_staged_data(directory_fd, name, &file.data, residual)?;
        remove_posix_lease_after_data(directory_fd, file)
    }

    fn remove_posix_staged_data(
        directory_fd: RawFd,
        name: &str,
        data: &File,
        residual: StagedNameResidual,
    ) -> io::Result<()> {
        match verify_named_file_at(directory_fd, name, data)? {
            NamedFileBinding::Absent => {}
            NamedFileBinding::Same if residual == StagedNameResidual::PresentAfterHardLink => {
                unlink_name_at(directory_fd, name)?;
            }
            NamedFileBinding::Same => {
                return Err(io::Error::other(
                    "rename left the staged data name unexpectedly present",
                ));
            }
            NamedFileBinding::Changed => {
                return Err(io::Error::other(
                    "staged data name no longer refers to retained file",
                ));
            }
        }
        ensure_name_absent(directory_fd, name)
    }

    fn remove_posix_lease_after_data(directory_fd: RawFd, file: &mut PosixFile) -> io::Result<()> {
        let Some(lease) = file.lease.as_mut() else {
            return Ok(());
        };
        match lease {
            PosixProducerLease::Directory(_) => {}
            PosixProducerLease::Sidecar {
                name,
                file: sidecar,
                identity,
            } => {
                revalidate_sidecar_at(directory_fd, name, sidecar, *identity)?;
                unlink_name_at(directory_fd, name)?;
                ensure_name_absent(directory_fd, name)?;
            }
        }
        // Closing the lease is the final state transition, after every
        // recognizable temporary name has been proven absent. Consume it
        // explicitly so a close failure remains observable.
        match file.lease.take() {
            Some(PosixProducerLease::Directory(directory)) => directory.release(),
            Some(PosixProducerLease::Sidecar { file, .. }) => close_file(file),
            None => Ok(()),
        }
    }

    fn unlink_exact_file(directory_fd: RawFd, name: &str, file: &File) -> io::Result<()> {
        match verify_named_file_at(directory_fd, name, file)? {
            NamedFileBinding::Same => {
                unlink_name_at(directory_fd, name)?;
                ensure_name_absent(directory_fd, name)
            }
            NamedFileBinding::Absent => Ok(()),
            NamedFileBinding::Changed => Err(io::Error::other(
                "temporary name no longer refers to retained file",
            )),
        }
    }

    fn ensure_name_absent(directory_fd: RawFd, name: &str) -> io::Result<()> {
        match stat_name_at(directory_fd, name) {
            Err(error) if error.raw_os_error() == Some(libc::ENOENT) => Ok(()),
            Ok(_) => Err(io::Error::from(io::ErrorKind::AlreadyExists)),
            Err(error) => Err(error),
        }
    }

    fn unlink_name_at(directory_fd: RawFd, name: &str) -> io::Result<()> {
        let name = c_name(name)?;
        // SAFETY: The name is NUL-terminated and the retained directory
        // descriptor remains valid for the call.
        if unsafe { libc::unlinkat(directory_fd, name.as_ptr(), 0) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    fn stat_fd_raw(fd: RawFd) -> io::Result<libc::stat> {
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: `fd` is retained and `stat` is writable storage of the exact
        // kernel structure.
        if unsafe { libc::fstat(fd, stat.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: successful fstat initialized the output.
        Ok(unsafe { stat.assume_init() })
    }

    fn stat_name_at(directory_fd: RawFd, name: &str) -> io::Result<libc::stat> {
        let name = c_name(name)?;
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: `name` is NUL-terminated, the directory descriptor is
        // retained, and `stat` is writable storage.
        if unsafe {
            libc::fstatat(
                directory_fd,
                name.as_ptr(),
                stat.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: successful fstatat initialized the output.
        Ok(unsafe { stat.assume_init() })
    }

    fn stat_is_regular(stat: &libc::stat) -> bool {
        u64::from(stat.st_mode) & u64::from(libc::S_IFMT) == u64::from(libc::S_IFREG)
    }

    fn stat_is_directory(stat: &libc::stat) -> bool {
        u64::from(stat.st_mode) & u64::from(libc::S_IFMT) == u64::from(libc::S_IFDIR)
    }

    fn same_stat_identity(left: &libc::stat, right: &libc::stat) -> bool {
        left.st_dev == right.st_dev && left.st_ino == right.st_ino
    }

    fn file_identity(stat: &libc::stat) -> io::Result<FileIdentity> {
        Ok(FileIdentity::posix(
            file_identity_component(stat.st_dev, "device")?,
            file_identity_component(stat.st_ino, "inode")?,
        ))
    }

    fn file_identity_component<T>(value: T, label: &str) -> io::Result<u64>
    where
        T: TryInto<u64>,
    {
        value
            .try_into()
            .map_err(|_| io::Error::other(format!("invalid filesystem {label} identity")))
    }

    fn close_posix_file(file: PosixFile) -> io::Result<()> {
        let PosixFile { data, lease } = file;
        let data_close = close_file(data);
        let lease_close = match lease {
            Some(PosixProducerLease::Directory(directory)) => directory.release(),
            Some(PosixProducerLease::Sidecar { file, .. }) => close_file(file),
            None => Ok(()),
        };
        data_close.and(lease_close)
    }

    fn close_file(file: File) -> io::Result<()> {
        close_raw_fd(file.into_raw_fd())
    }

    fn close_owned_fd(fd: OwnedFd) -> io::Result<()> {
        close_raw_fd(fd.into_raw_fd())
    }

    fn close_raw_fd(fd: RawFd) -> io::Result<()> {
        // SAFETY: ownership of `fd` was transferred into this function and it
        // is consumed exactly once.
        if unsafe { libc::close(fd) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum NamedFileBinding {
        Same,
        Absent,
        Changed,
    }

    fn verify_named_file_at(
        directory_fd: RawFd,
        name: &str,
        file: &File,
    ) -> io::Result<NamedFileBinding> {
        let name = c_name(name)?;
        verify_named_file_at_c_name(directory_fd, &name, file)
    }

    fn verify_named_file_at_c_name(
        directory_fd: RawFd,
        name: &std::ffi::CStr,
        file: &File,
    ) -> io::Result<NamedFileBinding> {
        let mut retained = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: the file descriptor is valid and `retained` points to
        // writable storage of the exact type.
        if unsafe { libc::fstat(file.as_raw_fd(), retained.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `fstat` succeeded, so the structure is initialized.
        let retained = unsafe { retained.assume_init() };

        let mut named = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: the relative name is NUL-terminated, the retained directory
        // descriptor is valid, and `named` is writable storage.
        if unsafe {
            libc::fstatat(
                directory_fd,
                name.as_ptr(),
                named.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            let error = io::Error::last_os_error();
            return if error.raw_os_error() == Some(libc::ENOENT) {
                Ok(NamedFileBinding::Absent)
            } else {
                Err(error)
            };
        }
        // SAFETY: `fstatat` succeeded, so the structure is initialized.
        let named = unsafe { named.assume_init() };
        if retained.st_dev == named.st_dev
            && retained.st_ino == named.st_ino
            && u64::from(named.st_mode) & u64::from(libc::S_IFMT) == u64::from(libc::S_IFREG)
        {
            Ok(NamedFileBinding::Same)
        } else {
            Ok(NamedFileBinding::Changed)
        }
    }

    /// Returns whether a `flock` error means the volume provides no lease
    /// protocol, rather than that this particular attempt was refused.
    ///
    /// Shared with the orphan sweeper, which must reach the same conclusion:
    /// the two sides previously disagreed about `EINVAL`, so a volume that
    /// answers with it downgraded the sweeper to the sidecar protocol while
    /// making every write fail outright — the sweeper kept running and the
    /// user-facing operation did not.
    ///
    /// `EINVAL` is fail-soft on purpose. `flock(2)` documents it only as "op is
    /// invalid", so on this crate's fixed operation words it should be
    /// unreachable; but if some filesystem does answer with it, treating it as
    /// a hard error costs every atomic write on that volume, while treating it
    /// as an absent capability costs a downgrade to a sidecar lease that works.
    ///
    /// `ENOTSUP` is load-bearing on Apple targets: `flock(2)` documents it as
    /// the wrong-descriptor-type error, and Darwin defines it as 45 while
    /// `EOPNOTSUPP` is 102. Linux aliases the two to 95, so omitting either is
    /// invisible there and silently disables directory leases on macOS and iOS.
    /// Comparisons rather than a `|` pattern for that reason.
    ///
    /// `EBADF` is deliberately excluded on both sides. `flock(2)` defines it as
    /// "fd is not an open file descriptor", which is a defect in this crate and
    /// not a property of the filesystem; a fresh `fstat` of the descriptor
    /// immediately precedes every lock attempt.
    pub(crate) fn lock_capability_absent(error: &io::Error) -> bool {
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENOSYS
                || code == libc::ENOTSUP
                || code == libc::EOPNOTSUPP
                || code == libc::EINVAL
        )
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn rename_exclusive(
        dir_fd: RawFd,
        from: &std::ffi::CStr,
        to: &std::ffi::CStr,
    ) -> io::Result<()> {
        // SAFETY: Both names are NUL-terminated for the duration of the call,
        // the directory descriptor is valid, and the fixed flag has the
        // renameat2 ABI's expected integer type.
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                dir_fd,
                from.as_ptr(),
                dir_fd,
                to.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        };
        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    /// Returns whether a failed `linkat` provably left the namespace alone.
    ///
    /// The exclusive hard-link fallback exists for filesystems without an
    /// exclusive rename primitive, so its own refusals must stay recoverable —
    /// otherwise the ladder ends in [`CommitOutcome::PublicationUnknown`] and a
    /// leaked staged artifact on exactly the volumes it was built to serve.
    ///
    /// `EEXIST` is "newpath already exists", the refusal that makes this
    /// publication exclusive in the first place. The rest are validation
    /// failures the kernel raises before creating the new directory entry:
    ///
    /// * `ENOTSUP`/`EOPNOTSUPP` — Darwin's `link(2)` lists "[ENOTSUP] The
    ///   underlying file system does not support this call", which is the FAT
    ///   and exFAT answer, and the one that matters here because those volumes
    ///   also refuse `renameatx_np`.
    /// * `EPERM` — Linux's `link(2)` lists "The filesystem containing oldpath
    ///   and newpath does not support the creation of hard links". Its other
    ///   documented cause, a directory source, cannot arise: the source has
    ///   already been verified to be the retained regular file.
    /// * `EXDEV`, `EMLINK`, `EROFS`, `EACCES`, `ENAMETOOLONG`, `ENOTDIR`,
    ///   `ELOOP`, `ENOSYS` — cross-device, link-count, read-only, permission,
    ///   and name-resolution checks, all performed before the link is made.
    ///
    /// Everything else stays ambiguous, which is what the distinction is for:
    /// `EIO`, `ENOSPC`, and `EDQUOT` can surface after the link exists.
    fn hard_link_definitely_unchanged(error: &io::Error) -> bool {
        if hard_link_capability_absent(error) {
            return true;
        }
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::EEXIST
                || code == libc::EXDEV
                || code == libc::EMLINK
                || code == libc::EROFS
                || code == libc::EACCES
                || code == libc::ENAMETOOLONG
                || code == libc::ENOTDIR
                || code == libc::ELOOP
        )
    }

    /// Returns whether `linkat` reported that this filesystem has no hard
    /// links at all, as opposed to refusing one particular link.
    ///
    /// Kept separate from [`hard_link_definitely_unchanged`] so the reported
    /// [`FailureKind`] distinguishes "this volume cannot do it" from "this
    /// link was refused"; `EMLINK` and `EEXIST` are refusals, not capability
    /// gaps. Comparisons rather than a `|` pattern: `ENOTSUP` and `EOPNOTSUPP`
    /// alias on Linux.
    fn hard_link_capability_absent(error: &io::Error) -> bool {
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENOSYS
                || code == libc::ENOTSUP
                || code == libc::EOPNOTSUPP
                || code == libc::EPERM
        )
    }

    /// Returns whether an exclusive rename was refused for want of the
    /// capability, rather than attempted against the namespace.
    ///
    /// `ENOSYS` is the kernel's authoritative report that the `renameat2`
    /// syscall number is not implemented, so no request reached the filesystem.
    ///
    /// `EINVAL` is the errno Linux documents for "The filesystem does not
    /// support one of the flags in flags", and it is how `nfs_rename`, FUSE
    /// below protocol 7.23, overlayfs, and ext2/vfat on kernels before 4.9
    /// refuse `RENAME_NOREPLACE`. Its three other documented causes are flag
    /// validation ("An invalid flag was specified in flags", and the two
    /// mutually exclusive `RENAME_EXCHANGE` combinations). The one remaining
    /// cause — "The new pathname contained a path prefix of the old, or, more
    /// generally, an attempt was made to make a directory a subdirectory of
    /// itself" — cannot arise at this call site: both names are single
    /// components resolved against one directory descriptor, and the source has
    /// already been verified to be the retained regular file.
    ///
    /// `ENOTSUP` is what Apple documents for `renameatx_np` when "flags has a
    /// value that is not supported by the file system". It is compared rather
    /// than pattern-matched alongside `EOPNOTSUPP` because the two alias on
    /// Linux.
    ///
    /// Every cause is validation performed before any namespace mutation, so
    /// reporting these as `DefinitelyUnchanged` is sound, and it is what lets
    /// [`AtomicWriteTxn::commit`] reach the exclusive hard-link fallback that
    /// exists for exactly these filesystems.
    fn exclusive_rename_capability_absent(error: &io::Error) -> bool {
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENOSYS
                || code == libc::EINVAL
                || code == libc::ENOTSUP
                || code == libc::EOPNOTSUPP
        )
    }

    /// Classifies a failed exclusive rename.
    ///
    /// Shared by every platform with an exclusive-rename primitive. Keeping one
    /// classifier is deliberate: an Apple-specific variant that mapped every
    /// error to `MayHaveMutated` made the hard-link fallback unreachable there,
    /// because `renameatx_np` always resolves in libSystem and so never reports
    /// `ENOSYS`.
    #[cfg(any(
        target_os = "linux",
        target_os = "android",
        target_vendor = "apple",
        test
    ))]
    fn classify_exclusive_rename_error(error: io::Error) -> PublicationAttemptError {
        if exclusive_rename_capability_absent(&error) {
            PublicationAttemptError::DefinitelyUnchanged(error)
        } else {
            PublicationAttemptError::MayHaveMutated(error)
        }
    }

    #[cfg(target_vendor = "apple")]
    fn rename_exclusive(
        dir_fd: RawFd,
        from: &std::ffi::CStr,
        to: &std::ffi::CStr,
    ) -> io::Result<()> {
        unsafe extern "C" {
            fn renameatx_np(
                fromfd: libc::c_int,
                from: *const libc::c_char,
                tofd: libc::c_int,
                to: *const libc::c_char,
                flags: libc::c_uint,
            ) -> libc::c_int;
        }
        // SAFETY: Both names are NUL-terminated for the duration of the call,
        // the directory descriptor is valid, and RENAME_EXCL is a documented
        // renameatx_np flag.
        let result = unsafe {
            renameatx_np(
                dir_fd,
                from.as_ptr(),
                dir_fd,
                to.as_ptr(),
                libc::RENAME_EXCL as libc::c_uint,
            )
        };
        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    /// Persists a directory's entries, the namespace half of the durable tier.
    ///
    /// A namespace barrier is only ever requested at
    /// [`SyncLevel::FileAndNamespaceSynchronized`], so the directory entry
    /// needs exactly the device-level barrier the staged bytes already get:
    /// this delegates to [`flush_fd`] with [`FlushKind::Durable`] rather than
    /// selecting its own primitive.
    ///
    /// That matters on Apple targets, where `fsync(2)` states it "will flush
    /// all data from the host to the drive" but "the drive itself may not
    /// physically write the data to the platters for quite some time". The
    /// guarantee is a property of the call, not of file data, so a directory
    /// entry published with plain `fsync` can still be lost to power failure.
    /// `F_FULLFSYNC` is the documented remedy and applies equally to a
    /// directory descriptor.
    ///
    /// A volume that rejects the strong command degrades to
    /// [`FlushOutcome::Degraded`], which callers turn into a weaker reported
    /// tier instead of an unbacked durability claim.
    /// Issues a flush command, reissuing it if a signal interrupts it.
    ///
    /// Darwin's `fsync(2)` documents `[EINTR] Its execution is interrupted by a
    /// signal`, POSIX.1-2024 lists the same, and `F_FULLFSYNC` /
    /// `F_BARRIERFSYNC` "do the same thing as fsync(2)" before their device
    /// work — which "may take quite a while to complete", widening the window.
    /// This crate is loaded into ART or the JVM, where thread-suspension and
    /// profiling signals are routine, so an un-retried `EINTR` would turn a
    /// healthy commit into a hard failure at the flush step.
    ///
    /// Reissuing is safe here and only here: `EINTR` means the request never
    /// completed, whereas after `EIO` the kernel may already have dropped the
    /// dirty pages, which is why the fsyncgate rule forbids retrying *that*.
    /// The caller therefore keeps propagating every other errno untouched.
    ///
    /// Takes the command as a closure because the two call shapes differ:
    /// `fsync` reports success as `0` and `fcntl` as any value other than `-1`.
    fn flush_uninterrupted(mut command: impl FnMut() -> libc::c_int) -> io::Result<()> {
        loop {
            if command() != -1 {
                return Ok(());
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }

    fn flush_directory_fd(fd: RawFd) -> io::Result<FlushOutcome> {
        flush_fd(fd, FlushKind::Durable)
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn flush_fd(fd: RawFd, _kind: FlushKind) -> io::Result<FlushOutcome> {
        // SAFETY: The descriptor is valid for the duration of the call.
        let Err(error) = flush_uninterrupted(|| unsafe { libc::fsync(fd) }) else {
            return Ok(FlushOutcome::Full);
        };
        if is_unsupported_errno(&error) {
            return Ok(FlushOutcome::Unsupported);
        }
        Err(error)
    }

    /// Returns whether an `fcntl` flush command was refused because the
    /// object cannot serve it, rather than because the flush itself failed.
    ///
    /// Apple's `fcntl(2)` scopes `F_FULLFSYNC` to HFS, FAT, UDF, and APFS and
    /// `F_BARRIERFSYNC` to HFS and APFS, but documents no errno for the
    /// refusal, and the observed value is object- and volume-dependent:
    /// `ENODEV` from a device that cannot flush, `ENOTTY` and `ENOTSUP` from
    /// third-party and network volumes. Treating an unlisted refusal as a
    /// hard failure would abort commits that plain `fsync` can still serve,
    /// so this list is deliberately wider than [`is_unsupported_errno`].
    ///
    /// It stays a list rather than "any error" (which is what libuv does)
    /// because a genuine `EIO` must propagate: after a reported I/O failure
    /// the kernel may already have dropped the dirty pages, and retrying with
    /// `fsync` could report a false success.
    #[cfg(target_vendor = "apple")]
    fn is_flush_command_unsupported(error: &io::Error) -> bool {
        if is_unsupported_errno(error) {
            return true;
        }
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENODEV || code == libc::ENOTTY
        )
    }

    #[cfg(target_vendor = "apple")]
    fn flush_fd(fd: RawFd, kind: FlushKind) -> io::Result<FlushOutcome> {
        // Not exposed by the libc crate for every Apple target.
        const F_BARRIERFSYNC: libc::c_int = 85;
        let command = match kind {
            FlushKind::Ordered => F_BARRIERFSYNC,
            FlushKind::Durable => libc::F_FULLFSYNC,
        };
        // SAFETY: The descriptor is valid and both commands are argumentless
        // fcntl flush requests.
        let Err(fcntl_error) = flush_uninterrupted(|| unsafe { libc::fcntl(fd, command) }) else {
            return Ok(FlushOutcome::Full);
        };
        if !is_flush_command_unsupported(&fcntl_error) {
            return Err(fcntl_error);
        }
        // Some volumes (SMB, third-party filesystems) reject the fcntl flush
        // commands; plain fsync still orders writes to the device.
        // SAFETY: The descriptor is valid for the duration of the call.
        let Err(error) = flush_uninterrupted(|| unsafe { libc::fsync(fd) }) else {
            return Ok(match kind {
                FlushKind::Ordered => FlushOutcome::Full,
                FlushKind::Durable => FlushOutcome::Degraded,
            });
        };
        if is_unsupported_errno(&error) {
            return Ok(FlushOutcome::Unsupported);
        }
        Err(error)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android", target_vendor = "apple")))]
    fn flush_fd(fd: RawFd, _kind: FlushKind) -> io::Result<FlushOutcome> {
        // SAFETY: The descriptor is valid for the duration of the call.
        let Err(error) = flush_uninterrupted(|| unsafe { libc::fsync(fd) }) else {
            return Ok(FlushOutcome::Full);
        };
        if is_unsupported_errno(&error) {
            return Ok(FlushOutcome::Unsupported);
        }
        Err(error)
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::{
            process::{Child, Command, Stdio},
            time::{Duration, Instant},
        };

        const LOCK_HELPER_ROLE: &str = "KEYGUARD_IO_POSIX_LOCK_HELPER_ROLE";
        const LOCK_HELPER_DIRECTORY: &str = "KEYGUARD_IO_POSIX_LOCK_HELPER_DIRECTORY";
        const LOCK_HELPER_READY: &str = "KEYGUARD_IO_POSIX_LOCK_HELPER_READY";
        const LOCK_HELPER_RELEASE: &str = "KEYGUARD_IO_POSIX_LOCK_HELPER_RELEASE";

        /// Every documented capability refusal must both be reported as
        /// pre-mutation and be recognized as unsupported, because
        /// `AtomicWriteTxn::commit` requires *both* to reach
        /// `publish_via_hard_link`. Classifying one without the other silently
        /// disables the fallback for the filesystems it exists to serve — NFS,
        /// FUSE below protocol 7.23, overlayfs, SMB, exFAT, macFUSE.
        #[test]
        fn capability_refusals_authorize_the_hard_link_fallback() {
            for errno in [libc::ENOSYS, libc::EINVAL, libc::ENOTSUP, libc::EOPNOTSUPP] {
                let classified =
                    classify_exclusive_rename_error(io::Error::from_raw_os_error(errno));
                assert!(
                    matches!(&classified, PublicationAttemptError::DefinitelyUnchanged(_)),
                    "errno {errno} is flag validation and cannot have mutated the namespace"
                );
                assert!(
                    RealFs.is_rename_unsupported(classified.error()),
                    "errno {errno} must route publication to the hard-link fallback"
                );
            }
        }

        /// The fallback's own refusals must stay recoverable, or the ladder ends
        /// in `PublicationUnknown` plus a leaked staged artifact on exactly the
        /// volumes it exists to serve — a FAT or exFAT volume refuses
        /// `renameatx_np` *and* `linkat`, so both rungs are reached there.
        #[test]
        fn hard_link_capability_refusals_are_recoverable_and_reported_unsupported() {
            for errno in [
                libc::ENOSYS,
                libc::ENOTSUP,
                libc::EOPNOTSUPP,
                // Linux link(2): "The filesystem containing oldpath and newpath
                // does not support the creation of hard links."
                libc::EPERM,
            ] {
                let error = io::Error::from_raw_os_error(errno);
                assert!(
                    hard_link_definitely_unchanged(&error),
                    "errno {errno} is refused before the link is created"
                );
                assert!(
                    RealFs.is_hard_link_unsupported(&error),
                    "errno {errno} must be reported as an unsupported primitive"
                );
            }

            // Refusals of one link, not of the capability: recoverable, but a
            // capability report would be wrong.
            for errno in [libc::EEXIST, libc::EMLINK, libc::EXDEV, libc::EROFS] {
                let error = io::Error::from_raw_os_error(errno);
                assert!(
                    hard_link_definitely_unchanged(&error),
                    "errno {errno} is validated before the link is created"
                );
                assert!(
                    !RealFs.is_hard_link_unsupported(&error),
                    "errno {errno} refuses one link and must not claim the volume cannot link"
                );
            }

            // These can surface after the link exists, so they must stay
            // ambiguous and reach identity reconciliation.
            for errno in [libc::EIO, libc::ENOSPC, libc::EDQUOT] {
                assert!(
                    !hard_link_definitely_unchanged(&io::Error::from_raw_os_error(errno)),
                    "errno {errno} can be reported after the link was established"
                );
            }

            // The rename predicate must not absorb EPERM: for renameat2 it is
            // not a capability report, and treating it as one would send a
            // genuine permission failure down the fallback ladder.
            assert!(
                !RealFs.is_rename_unsupported(&io::Error::from_raw_os_error(libc::EPERM)),
                "EPERM must not select the hard-link fallback from a rename failure"
            );
        }

        /// An error that may have reached the namespace must stay ambiguous, and
        /// must never be mistaken for a capability gap — re-publishing through
        /// the hard-link path could otherwise double-apply a rename that already
        /// took effect.
        #[test]
        fn genuine_failures_stay_ambiguous_and_do_not_claim_unsupported() {
            for errno in [
                libc::EIO,
                libc::EACCES,
                libc::ENOSPC,
                libc::EEXIST,
                libc::EPERM,
                libc::EROFS,
            ] {
                let classified =
                    classify_exclusive_rename_error(io::Error::from_raw_os_error(errno));
                assert!(
                    matches!(&classified, PublicationAttemptError::MayHaveMutated(_)),
                    "errno {errno} must not authorize retry or fallback"
                );
                assert!(!RealFs.is_rename_unsupported(classified.error()));
            }
        }

        fn test_directory() -> std::path::PathBuf {
            let mut nonce = [0_u8; 8];
            getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
            let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
            let directory = std::env::temp_dir().join(format!("keyguard-posix-fsops-{nonce}"));
            std::fs::create_dir(&directory).expect("test directory must be created");
            directory
        }

        /// The namespace barrier must reach the same strength as the file
        /// barrier on a real directory.
        ///
        /// On Apple this is the regression guard for using plain `fsync`,
        /// which returns success while leaving the directory entry in the
        /// drive's volatile cache. `flush_directory_fd` must therefore behave
        /// exactly like a durable file flush on the same descriptor.
        #[test]
        fn directory_flush_matches_the_durable_file_barrier() {
            let directory = test_directory();
            let dir = RealFs
                .open_root(&directory)
                .expect("test directory must open");

            let namespace = RealFs
                .flush_directory(&dir)
                .expect("directory flush must succeed on a real directory");
            let durable = flush_fd(dir.fd.as_raw_fd(), FlushKind::Durable)
                .expect("durable flush must succeed on a real directory");

            assert_eq!(
                namespace, durable,
                "the namespace barrier must not be weaker than a durable file flush"
            );
            assert_eq!(
                namespace,
                FlushOutcome::Full,
                "an ordinary temporary directory must support the durable barrier"
            );

            std::fs::remove_dir_all(&directory).expect("test directory must be removed");
        }

        /// Proves the namespace barrier actually issues `F_FULLFSYNC` rather
        /// than plain `fsync`.
        ///
        /// On APFS both commands succeed, so no ordinary directory can tell
        /// them apart. `/dev/null` can: `fsync` succeeds on it while
        /// `F_FULLFSYNC` is refused with `ENODEV`. A `Degraded` result is
        /// therefore only reachable if the strong command was attempted and
        /// fell back — plain `fsync` would report `Full`.
        ///
        /// This doubles as the guard for [`is_flush_command_unsupported`]:
        /// were `ENODEV` missing from it, the fallback would surface as a
        /// hard error instead.
        #[cfg(target_vendor = "apple")]
        #[test]
        fn namespace_barrier_attempts_the_full_fsync_command() {
            let sink = std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open("/dev/null")
                .expect("/dev/null must open");

            assert_eq!(
                flush_directory_fd(sink.as_raw_fd())
                    .expect("an F_FULLFSYNC refusal must degrade, not fail"),
                FlushOutcome::Degraded,
                "the namespace barrier must attempt F_FULLFSYNC before falling back to fsync"
            );

            // The ordering command is separately satisfiable here, so a
            // Degraded result is specific to the durable request.
            assert_eq!(
                flush_fd(sink.as_raw_fd(), FlushKind::Ordered)
                    .expect("an F_BARRIERFSYNC refusal must degrade, not fail"),
                FlushOutcome::Full,
            );
        }

        /// End-to-end: a real durable commit must actually reach the tier it
        /// reports. Guards the whole `achieved()` claim against a namespace
        /// flush that silently degrades.
        #[test]
        fn real_durable_commit_achieves_the_namespace_tier() {
            use crate::{
                directory::{AtomicDirectory, RelativeDestination},
                durability::{AchievedSyncLevel, SyncLevel, SyncPolicy},
                txn::{
                    AtomicWriteOptions, AtomicWriteTxn, CommitOutcome, ExistingParentLinkPolicy,
                    ParentDirectoryPolicy, Permissions, PublishPolicy,
                },
            };

            let directory = test_directory();
            // Anchored on a retained root, exactly as every production caller
            // does. Resolving the root first is required on macOS, where the
            // temporary directory sits behind the `/var` symlink and, beyond
            // it, a firmlink onto the Data volume that `Reject` counts as a
            // mount crossing.
            let root =
                AtomicDirectory::open(&RealFs, &directory).expect("the test root must be pinned");
            let mut txn = AtomicWriteTxn::begin_at_directory(
                RealFs,
                &root,
                RelativeDestination::parse("vault.bin").expect("relative path must parse"),
                AtomicWriteOptions {
                    publication: PublishPolicy::Create {
                        permissions: Permissions::OwnerOnly,
                    },
                    parent_directory: ParentDirectoryPolicy::RequireExisting,
                    existing_parent_links: ExistingParentLinkPolicy::Reject,
                    synchronization: SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized),
                },
            )
            .expect("a durable transaction must open beneath a pinned root");
            txn.write(b"durable").expect("write must succeed");
            let success = txn.commit().expect("durable commit must succeed");

            assert_eq!(success.outcome(), CommitOutcome::Published);
            assert_eq!(
                success.achieved(),
                Some(AchievedSyncLevel::FileAndNamespaceSynchronized),
                "a required durable commit must not report a weaker achieved tier"
            );
            assert!(!success.cleanup().is_incomplete());
            assert_eq!(
                std::fs::read(directory.join("vault.bin"))
                    .expect("published file must be readable"),
                b"durable"
            );

            std::fs::remove_dir_all(&directory).expect("test directory must be removed");
        }

        struct LockHelper {
            child: Child,
            release: std::path::PathBuf,
        }

        impl LockHelper {
            fn release(mut self) {
                std::fs::write(&self.release, b"release")
                    .expect("parent must signal subprocess release");
                let status = self.child.wait().expect("lock helper must be waitable");
                assert!(status.success(), "lock helper failed with {status}");
            }
        }

        fn spawn_lock_helper(
            role: &str,
            directory: &Path,
            ready: &Path,
            release: &Path,
        ) -> Option<LockHelper> {
            let mut child =
                Command::new(std::env::current_exe().expect("test binary must resolve"))
                    .arg("--exact")
                    .arg("fsops::posix::tests::cross_process_lock_helper")
                    .arg("--nocapture")
                    .env(LOCK_HELPER_ROLE, role)
                    .env(LOCK_HELPER_DIRECTORY, directory)
                    .env(LOCK_HELPER_READY, ready)
                    .env(LOCK_HELPER_RELEASE, release)
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::inherit())
                    .spawn()
                    .expect("lock helper must spawn");
            let deadline = Instant::now() + Duration::from_secs(5);
            let status = loop {
                if let Ok(status) = std::fs::read(ready)
                    && !status.is_empty()
                {
                    break status;
                }
                if let Some(status) = child.try_wait().expect("lock helper must be waitable") {
                    panic!("lock helper exited before readiness with {status}");
                }
                assert!(
                    Instant::now() < deadline,
                    "lock helper did not become ready"
                );
                std::thread::sleep(Duration::from_millis(5));
            };
            if status == b"unsupported" {
                let child_status = child.wait().expect("unsupported helper must be waitable");
                assert!(
                    child_status.success(),
                    "unsupported helper failed with {child_status}"
                );
                return None;
            }
            assert_eq!(status, b"ready");
            Some(LockHelper {
                child,
                release: release.to_owned(),
            })
        }

        #[test]
        fn cross_process_lock_helper() {
            let Some(role) = std::env::var_os(LOCK_HELPER_ROLE) else {
                return;
            };
            let directory = std::path::PathBuf::from(
                std::env::var_os(LOCK_HELPER_DIRECTORY).expect("helper directory must be provided"),
            );
            let ready = std::path::PathBuf::from(
                std::env::var_os(LOCK_HELPER_READY).expect("helper ready path must be provided"),
            );
            let release = std::path::PathBuf::from(
                std::env::var_os(LOCK_HELPER_RELEASE)
                    .expect("helper release path must be provided"),
            );
            let fs = RealFs;
            let dir = fs
                .open_root(&directory)
                .expect("helper directory must open");
            let held = match role.to_str().expect("helper role must be UTF-8") {
                "writer" => match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                    .expect("writer lock probe must complete")
                {
                    DirectoryLeaseAttempt::Acquired(lease) => lease,
                    DirectoryLeaseAttempt::Unsupported => {
                        std::fs::write(ready, b"unsupported")
                            .expect("unsupported status must be signaled");
                        return;
                    }
                },
                "sweeper" => {
                    let lease = openat_directory(dir.fd.as_raw_fd(), c".", true)
                        .expect("fresh sweeper description must open");
                    match try_lock_directory(lease.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                        .expect("sweeper lock probe must complete")
                    {
                        LeaseAttempt::Acquired => DirectoryWriterLease::new(lease),
                        LeaseAttempt::Unsupported => {
                            std::fs::write(ready, b"unsupported")
                                .expect("unsupported status must be signaled");
                            return;
                        }
                        LeaseAttempt::Busy => {
                            panic!("isolated helper directory unexpectedly has a writer")
                        }
                    }
                }
                other => panic!("unknown lock helper role {other}"),
            };
            std::fs::write(&ready, b"ready").expect("readiness must be signaled");
            let deadline = Instant::now() + Duration::from_secs(10);
            while !release.exists() {
                assert!(
                    Instant::now() < deadline,
                    "parent did not release the lock helper"
                );
                std::thread::sleep(Duration::from_millis(5));
            }
            held.release().expect("helper lease must release");
        }

        #[test]
        fn staged_discard_unlinks_before_releasing_the_retained_file() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let staged = fs
                .create_file_at(&dir, "stage.tmp", true)
                .expect("staged file must be created");

            fs.discard_staged(&dir, "stage.tmp", staged)
                .expect("staged discard must succeed");

            assert!(!directory.join("stage.tmp").exists());
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn independently_opened_directory_writer_leases_share_while_sweeper_is_busy() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let first = match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("first writer lease probe must complete")
            {
                DirectoryLeaseAttempt::Acquired(lease) => lease,
                DirectoryLeaseAttempt::Unsupported => {
                    let _ = std::fs::remove_dir_all(directory);
                    return;
                }
            };
            let second = match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("second writer lease probe must complete")
            {
                DirectoryLeaseAttempt::Acquired(lease) => lease,
                DirectoryLeaseAttempt::Unsupported => {
                    panic!("lease support must not change between fresh descriptions")
                }
            };
            let sweeper = openat_directory(dir.fd.as_raw_fd(), c".", true)
                .expect("fresh sweeper description must open");

            assert_eq!(
                try_lock_directory(sweeper.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                    .expect("sweeper probe must complete"),
                LeaseAttempt::Busy,
            );
            second.release().expect("second writer lease must release");
            first.release().expect("first writer lease must release");
            assert_eq!(
                try_lock_directory(sweeper.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                    .expect("sweeper probe after release must complete"),
                LeaseAttempt::Acquired,
            );

            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn cross_process_writers_hold_independent_shared_flocks() {
            let directory = test_directory();
            let target = directory.join("target");
            let ready = directory.join("ready");
            let release = directory.join("release");
            std::fs::create_dir(&target).expect("target directory must be created");
            let Some(helper) = spawn_lock_helper("writer", &target, &ready, &release) else {
                let _ = std::fs::remove_dir_all(directory);
                return;
            };

            let fs = RealFs;
            let dir = fs.open_root(&target).expect("target directory must open");
            let second = acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("second writer probe must complete");
            helper.release();

            let DirectoryLeaseAttempt::Acquired(second) = second else {
                panic!("flock support changed after the subprocess acquired it");
            };
            second.release().expect("second writer lease must release");
            let _ = std::fs::remove_dir_all(directory);
        }

        /// The hard-link fallback is the whole Create-mode publication path on
        /// filesystems without an exclusive rename, and — since `renameat2` is
        /// withheld below API 28 — on Android 8.0 and 8.1 as well. Until now it
        /// was exercised only by the simulator, so this pins the real-kernel
        /// behavior the fallback depends on: `linkat` publishes the retained
        /// bytes, refuses an occupied destination without disturbing it, and
        /// leaves the staged name in place for `PresentAfterHardLink` to remove.
        #[test]
        fn hard_link_publication_is_exclusive_and_leaves_a_removable_staged_name() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");

            let mut staged = fs
                .create_staged_at(&dir, TemporaryFileRole::New, true)
                .expect("staging must succeed");
            fs.write_all(&mut staged.file, b"linked")
                .expect("write must succeed");

            // Publication into a free name binds the staged bytes.
            fs.hard_link(&dir, &staged.name, &staged.file, "vault.bin")
                .expect("hard-link publication must succeed");
            assert_eq!(
                std::fs::read(directory.join("vault.bin"))
                    .expect("published file must be readable"),
                b"linked",
            );
            assert!(
                directory.join(&staged.name).exists(),
                "the staged name must survive a hard-link publication"
            );

            // A second publication must be refused, and must not disturb the
            // file already at the destination.
            let occupied = fs
                .hard_link(&dir, &staged.name, &staged.file, "vault.bin")
                .expect_err("an occupied destination must refuse publication");
            assert_eq!(
                occupied.error().kind(),
                io::ErrorKind::AlreadyExists,
                "linkat must report the destination as occupied"
            );
            assert!(
                matches!(&occupied, PublicationAttemptError::DefinitelyUnchanged(_)),
                "an exclusivity refusal proves the destination was untouched"
            );
            assert_eq!(
                std::fs::read(directory.join("vault.bin")).expect("published file must survive"),
                b"linked",
            );

            // Finalizing the residual removes only the staged name.
            fs.finalize_staged_after_publication(
                &dir,
                &staged.name,
                &mut staged.file,
                StagedNameResidual::PresentAfterHardLink,
            )
            .expect("staged-name removal must succeed");
            assert!(!directory.join(&staged.name).exists());
            assert!(directory.join("vault.bin").exists());

            fs.close(staged.file).expect("staged file must close");
            let _ = std::fs::remove_dir_all(directory);
        }

        /// A writer must outlast a sweep that releases within the ladder, and it
        /// must sleep the documented backoff rather than spinning.
        #[test]
        fn a_busy_directory_lease_is_waited_out_before_it_is_reported() {
            let delays = [
                Duration::from_millis(1),
                Duration::from_millis(2),
                Duration::from_millis(4),
            ];

            let mut slept = Vec::new();
            let mut remaining_busy = 2;
            let acquired = retry_while_lease_busy(
                &delays,
                |delay| slept.push(delay),
                || {
                    if remaining_busy > 0 {
                        remaining_busy -= 1;
                        return Err(StagedCreationError::classified(
                            io::Error::from(io::ErrorKind::ResourceBusy),
                            StagedCreationFailureKind::ResourceBusy,
                        ));
                    }
                    Ok(7_u8)
                },
            )
            .expect("a lease that frees within the ladder must be acquired");

            assert_eq!(acquired, 7);
            assert_eq!(
                slept,
                [Duration::from_millis(1), Duration::from_millis(2)],
                "each wait must use the next backoff step"
            );
        }

        /// The ladder is bounded, and only a plain busy lease is retried.
        #[test]
        fn only_a_recoverable_busy_lease_is_retried() {
            let delays = [Duration::from_millis(1), Duration::from_millis(2)];

            // Exhausting the ladder reports the busy lease.
            let mut waits = 0;
            let error = retry_while_lease_busy::<u8>(
                &delays,
                |_| waits += 1,
                || {
                    Err(StagedCreationError::classified(
                        io::Error::from(io::ErrorKind::ResourceBusy),
                        StagedCreationFailureKind::ResourceBusy,
                    ))
                },
            )
            .expect_err("a permanently busy lease must be reported");
            assert_eq!(error.kind(), StagedCreationFailureKind::ResourceBusy);
            assert_eq!(waits, delays.len(), "the ladder must be bounded");

            // A cleanup-incomplete busy result must surface at once: the caller
            // has to learn that a staged artifact may remain.
            let mut waits = 0;
            let error = retry_while_lease_busy::<u8>(
                &delays,
                |_| waits += 1,
                || {
                    Err(StagedCreationError::classified(
                        io::Error::from(io::ErrorKind::ResourceBusy),
                        StagedCreationFailureKind::ResourceBusy,
                    )
                    .with_cleanup_incomplete())
                },
            )
            .expect_err("a cleanup-incomplete result must not be retried");
            assert!(error.cleanup_incomplete());
            assert_eq!(waits, 0);

            // Neither will waiting resolve an unsupported or inferred failure.
            for kind in [
                StagedCreationFailureKind::Unsupported,
                StagedCreationFailureKind::Inferred,
            ] {
                let mut waits = 0;
                let _ = retry_while_lease_busy::<u8>(
                    &delays,
                    |_| waits += 1,
                    || {
                        Err(StagedCreationError::classified(
                            io::Error::from(io::ErrorKind::Unsupported),
                            kind,
                        ))
                    },
                )
                .expect_err("a non-busy failure must not be retried");
                assert_eq!(waits, 0, "{kind:?} must not be waited on");
            }
        }

        /// The documented ladder must actually be a bounded backoff, since a
        /// save path blocks on it.
        #[test]
        fn the_directory_lease_ladder_is_a_bounded_backoff() {
            assert!(!DIRECTORY_LEASE_RETRY_DELAYS.is_empty());
            let total: Duration = DIRECTORY_LEASE_RETRY_DELAYS.iter().sum();
            assert!(
                total <= Duration::from_millis(500),
                "a save must not block for {total:?}"
            );
            assert!(
                DIRECTORY_LEASE_RETRY_DELAYS
                    .windows(2)
                    .all(|pair| pair[0] <= pair[1]),
                "the backoff must not decrease"
            );
        }

        #[test]
        fn cross_process_sweeper_blocks_writer_before_artifact_creation() {
            let directory = test_directory();
            let target = directory.join("target");
            let ready = directory.join("ready");
            let release = directory.join("release");
            std::fs::create_dir(&target).expect("target directory must be created");
            let Some(helper) = spawn_lock_helper("sweeper", &target, &ready, &release) else {
                let _ = std::fs::remove_dir_all(directory);
                return;
            };

            let fs = RealFs;
            let dir = fs.open_root(&target).expect("target directory must open");
            let started = Instant::now();
            let result = create_posix_staged_at(dir.fd.as_raw_fd(), TemporaryFileRole::New, true);
            let waited = started.elapsed();
            let target_is_empty = std::fs::read_dir(&target)
                .expect("target directory must remain readable")
                .next()
                .is_none();
            helper.release();

            // Guards the wiring, not just the helper: a writer that reports the
            // contended lease without first waiting out the backoff would return
            // immediately. The ladder's first six steps already total 63 ms, so a
            // conservative floor still distinguishes waiting from not waiting.
            assert!(
                waited >= Duration::from_millis(50),
                "the writer must wait out a contended lease before reporting it busy, waited {waited:?}"
            );

            let error = match result {
                Ok(_) => panic!("exclusive subprocess sweeper must block the writer"),
                Err(error) => error,
            };
            assert_eq!(
                error.kind(),
                StagedCreationFailureKind::ResourceBusy,
                "writer/sweeper contention must remain distinct from unsupported flock"
            );
            assert!(
                target_is_empty,
                "writer must reject contention before creating an artifact"
            );
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn explicit_writer_release_unlocks_an_inherited_descriptor() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let writer = match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("writer lease probe must complete")
            {
                DirectoryLeaseAttempt::Acquired(lease) => lease,
                DirectoryLeaseAttempt::Unsupported => {
                    let _ = std::fs::remove_dir_all(directory);
                    return;
                }
            };
            // SAFETY: writer is retained and dup returns a new owned descriptor.
            let duplicate = unsafe { libc::dup(writer.as_raw_fd()) };
            assert!(duplicate >= 0, "directory descriptor duplication must work");
            // SAFETY: dup returned a uniquely-owned descriptor.
            let duplicate = unsafe { OwnedFd::from_raw_fd(duplicate) };
            writer
                .release()
                .expect("writer release must explicitly unlock");

            let independent = openat_directory(dir.fd.as_raw_fd(), c".", true)
                .expect("independent directory description must open");
            assert_eq!(
                try_lock_directory(independent.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                    .expect("exclusive probe after explicit release must complete"),
                LeaseAttempt::Acquired,
                "unlock must release the open-description lock even while a duplicate remains open",
            );

            drop(duplicate);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn duplicated_descriptor_can_mutate_a_writer_lease() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let writer = match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("writer lease probe must complete")
            {
                DirectoryLeaseAttempt::Acquired(lease) => lease,
                DirectoryLeaseAttempt::Unsupported => {
                    let _ = std::fs::remove_dir_all(directory);
                    return;
                }
            };
            // SAFETY: writer is retained and dup returns a new owned descriptor.
            let duplicate = unsafe { libc::dup(writer.as_raw_fd()) };
            assert!(duplicate >= 0, "directory descriptor duplication must work");
            // SAFETY: dup returned a uniquely-owned descriptor.
            let duplicate = unsafe { OwnedFd::from_raw_fd(duplicate) };

            assert_eq!(
                try_lock_directory(duplicate.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                    .expect("lock conversion through duplicate must complete"),
                LeaseAttempt::Acquired,
                "a dup shares and therefore mutates the writer's open-description lock",
            );
            let independent = openat_directory(dir.fd.as_raw_fd(), c".", true)
                .expect("independent directory description must open");
            assert_eq!(
                try_lock_directory(independent.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB)
                    .expect("independent shared probe must complete"),
                LeaseAttempt::Busy,
            );

            writer
                .release()
                .expect("writer release must unlock the duplicated description");
            assert_eq!(
                try_lock_directory(independent.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB)
                    .expect("shared probe after release must complete"),
                LeaseAttempt::Acquired,
            );
            drop(duplicate);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn dropping_writer_lease_unlocks_an_inherited_descriptor() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let writer = match acquire_directory_writer_lease(dir.fd.as_raw_fd())
                .expect("writer lease probe must complete")
            {
                DirectoryLeaseAttempt::Acquired(lease) => lease,
                DirectoryLeaseAttempt::Unsupported => {
                    let _ = std::fs::remove_dir_all(directory);
                    return;
                }
            };
            // SAFETY: writer is retained and dup returns a new owned descriptor.
            let duplicate = unsafe { libc::dup(writer.as_raw_fd()) };
            assert!(duplicate >= 0, "directory descriptor duplication must work");
            // SAFETY: dup returned a uniquely-owned descriptor.
            let duplicate = unsafe { OwnedFd::from_raw_fd(duplicate) };
            drop(writer);

            let independent = openat_directory(dir.fd.as_raw_fd(), c".", true)
                .expect("independent directory description must open");
            assert_eq!(
                try_lock_directory(independent.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB)
                    .expect("exclusive probe after dropped lease must complete"),
                LeaseAttempt::Acquired,
                "Drop must unlock the open-description lock while a duplicate remains open",
            );

            drop(duplicate);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn sidecar_writer_creates_exact_lease_before_data_and_removes_data_first() {
            use std::os::unix::fs::MetadataExt as _;

            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let CreatedStaged {
                name,
                file: mut staged,
            } = create_sidecar_lease_staged(dir.fd.as_raw_fd(), TemporaryFileRole::New, true)
                .expect("sidecar staged artifact must be created");
            let parsed =
                crate::naming::parse_temporary_artifact_name(&name).expect("data name must parse");
            assert_eq!(parsed.protocol, TemporaryArtifactProtocol::SidecarLeaseV1);
            let PosixProducerLease::Sidecar {
                name: lease_name, ..
            } = staged.lease.as_ref().expect("lease must be retained")
            else {
                panic!("sidecar creation must retain its sidecar lease");
            };
            let lease_name = lease_name.clone();
            let metadata =
                std::fs::symlink_metadata(directory.join(&lease_name)).expect("sidecar must exist");
            assert_eq!(metadata.mode() & 0o777, 0o600);
            assert_eq!(metadata.nlink(), 1);

            finalize_posix_staged(
                dir.fd.as_raw_fd(),
                &name,
                &mut staged,
                StagedNameResidual::PresentAfterHardLink,
            )
            .expect("pathless finalization must remove the pair");
            assert!(!directory.join(&name).exists());
            assert!(!directory.join(&lease_name).exists());
            close_posix_file(staged).expect("data file must close");
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn named_scratch_fallback_returns_data_only_after_all_names_are_absent() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");

            let scratch = create_pathless_named_scratch_at(dir.fd.as_raw_fd())
                .expect("named scratch fallback must become pathless");

            assert_eq!(
                std::fs::read_dir(&directory)
                    .expect("scratch directory must remain readable")
                    .count(),
                0,
                "data and optional sidecar names must be absent before return",
            );
            close_file(scratch).expect("scratch file must close");
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn forced_sidecar_scratch_returns_only_after_data_and_lease_are_absent() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");

            let scratch = create_pathless_sidecar_scratch_at(dir.fd.as_raw_fd())
                .expect("forced v1s scratch must become pathless");

            assert_eq!(
                std::fs::read_dir(&directory)
                    .expect("scratch directory must remain readable")
                    .count(),
                0,
                "v1s data and sidecar names must be absent before return",
            );
            close_file(scratch).expect("scratch file must close");
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn staged_discard_never_unlinks_a_substituted_name() {
            let directory = test_directory();
            let fs = RealFs;
            let dir = fs.open_root(&directory).expect("directory must open");
            let staged = fs
                .create_file_at(&dir, "stage.tmp", true)
                .expect("staged file must be created");
            std::fs::rename(directory.join("stage.tmp"), directory.join("original.tmp"))
                .expect("retained staged file must be moved");
            std::fs::write(directory.join("stage.tmp"), b"replacement")
                .expect("substitute must be created");

            fs.discard_staged(&dir, "stage.tmp", staged)
                .expect_err("changed staged binding must be rejected");

            assert_eq!(
                std::fs::read(directory.join("stage.tmp")).expect("substitute must remain"),
                b"replacement",
            );
            assert!(directory.join("original.tmp").exists());
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn reject_refuses_a_symlink_while_follow_and_pin_retains_its_first_target() {
            let directory = test_directory();
            let target_a = directory.join("target-a");
            let target_b = directory.join("target-b");
            let link = directory.join("link");
            std::fs::create_dir(&target_a).expect("first target must be created");
            std::fs::create_dir(&target_b).expect("second target must be created");
            std::os::unix::fs::symlink("target-a", &link)
                .expect("directory symlink must be created");

            let fs = RealFs;
            let root = fs.open_root(&directory).expect("root directory must open");
            let rejected = match fs.open_dir_at(&root, "link", false) {
                Ok(_) => panic!("reject mode must not open a symlink"),
                Err(error) => error,
            };
            // EMLINK is FreeBSD's spelling of "O_NOFOLLOW was specified and the
            // target is a symbolic link"; it must classify like the others.
            assert!(
                rejected.raw_os_error() == Some(libc::ELOOP)
                    || rejected.raw_os_error() == Some(libc::EXDEV)
                    || rejected.raw_os_error() == Some(libc::ENOTDIR)
                    || rejected.raw_os_error() == Some(libc::EMLINK),
                "unexpected reject error: {rejected}",
            );

            let pinned = fs
                .open_dir_at(&root, "link", true)
                .expect("follow-and-pin must resolve the existing symlink");
            std::fs::remove_file(&link).expect("first symlink must be removed");
            std::os::unix::fs::symlink("target-b", &link)
                .expect("replacement symlink must be created");
            let staged = fs
                .create_file_at(&pinned, "pinned.tmp", true)
                .expect("pinned target must remain usable");
            fs.close(staged).expect("pinned file must close");

            assert!(target_a.join("pinned.tmp").exists());
            assert!(!target_b.join("pinned.tmp").exists());
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn retained_child_directory_is_not_redirected_by_ancestor_rename() {
            let directory = test_directory();
            let moved = directory.with_extension("moved");
            std::fs::create_dir(directory.join("child")).expect("child directory must be created");

            let fs = RealFs;
            let root = fs.open_root(&directory).expect("root directory must open");
            let child = fs
                .open_dir_at(&root, "child", false)
                .expect("child directory must open");
            std::fs::rename(&directory, &moved).expect("ancestor rename must succeed");
            let staged = fs
                .create_file_at(&child, "anchored.tmp", true)
                .expect("retained child must remain usable");
            fs.close(staged).expect("anchored file must close");

            assert!(moved.join("child/anchored.tmp").exists());
            assert!(!directory.exists());
            let _ = std::fs::remove_dir_all(moved);
        }

        /// The gates key off the bionic *allowlist* version, not the kernel
        /// version that introduced each syscall. A number outside the generated
        /// ranges is answered with `SECCOMP_RET_TRAP`, so a threshold that is
        /// too low is an app crash rather than a graceful fallback — and one
        /// that is too high silently forfeits an available capability.
        ///
        /// Level `0` is what a failed `ro.build.version.sdk` read caches, so it
        /// must close both gates.
        #[test]
        fn android_raw_syscall_gates_match_the_bionic_allowlist_versions() {
            for level in [0, 26, 27] {
                assert!(
                    !android_exclusive_rename_permitted(level),
                    "renameat2 is trapped at API {level}"
                );
            }
            for level in [28, 29, 30, 36] {
                assert!(
                    android_exclusive_rename_permitted(level),
                    "renameat2 is allowlisted at API {level}"
                );
            }

            for level in [0, 26, 27, 28, 29] {
                assert!(
                    !android_mount_id_query_permitted(level),
                    "statx is trapped at API {level}"
                );
            }
            for level in [30, 31, 36] {
                assert!(
                    android_mount_id_query_permitted(level),
                    "statx is allowlisted at API {level}"
                );
            }
        }

        #[cfg(target_os = "linux")]
        #[test]
        fn openat2_errors_only_fall_back_for_capability_or_sandbox_absence() {
            for errno in [libc::ENOSYS, libc::EINVAL, libc::E2BIG, libc::EPERM] {
                assert_eq!(
                    classify_openat2_error(&io::Error::from_raw_os_error(errno)),
                    OpenAt2ErrorAction::FallBackToMountIdValidation,
                );
            }
            for errno in [libc::EXDEV, libc::ELOOP, libc::ENOENT, libc::EACCES] {
                assert_eq!(
                    classify_openat2_error(&io::Error::from_raw_os_error(errno)),
                    OpenAt2ErrorAction::Return,
                );
            }
        }

        #[cfg(any(target_os = "linux", target_os = "android"))]
        fn linux_identity(device: u64, mount_id: Option<u64>) -> MountIdentity {
            MountIdentity { device, mount_id }
        }

        #[cfg(any(target_os = "linux", target_os = "android"))]
        #[test]
        fn fallback_mount_identity_comparison_rejects_crossings() {
            ensure_same_mount(&linux_identity(7, Some(41)), &linux_identity(7, Some(41)))
                .expect("equal mount IDs must be accepted");
            let error =
                ensure_same_mount(&linux_identity(7, Some(41)), &linux_identity(7, Some(42)))
                    .expect_err("different mount IDs must be rejected");
            assert_eq!(error.raw_os_error(), Some(libc::EXDEV));

            // A mount ID outranks the device when both sides carry one, so a
            // bind mount that shares a device is still a crossing.
            ensure_same_mount(&linux_identity(7, Some(41)), &linux_identity(7, Some(42)))
                .expect_err("a matching device must not rescue distinct mount IDs");
        }

        /// Kernels below 5.8 report no mount identifier. The comparison must
        /// degrade to the device rather than fail, or no atomic write can
        /// succeed on Android's 4.19 and 5.4 kernels.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        #[test]
        fn absent_mount_id_degrades_to_the_device_instead_of_failing() {
            ensure_same_mount(&linux_identity(7, None), &linux_identity(7, None))
                .expect("a shared device must be accepted without mount IDs");
            let error = ensure_same_mount(&linux_identity(7, None), &linux_identity(8, None))
                .expect_err("distinct devices must still be rejected");
            assert_eq!(error.raw_os_error(), Some(libc::EXDEV));

            // The two signals are not comparable, so one-sided availability
            // demotes to the device instead of reporting a false crossing.
            ensure_same_mount(&linux_identity(7, Some(41)), &linux_identity(7, None))
                .expect("a one-sided mount ID must fall back to the device");
        }

        /// The regression itself: resolving a mount identity must never fail
        /// merely because the kernel predates `STATX_MNT_ID`.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        #[test]
        fn mount_identity_succeeds_without_statx_mount_id_support() {
            let fs = RealFs;
            let root = fs
                .open_root(Path::new("/"))
                .expect("filesystem root must open");
            let identity = mount_identity(root.fd.as_raw_fd())
                .expect("mount identity must not depend on statx mount-ID support");
            assert_ne!(identity.device, 0, "a real device must be reported");
        }

        #[cfg(any(target_os = "linux", target_vendor = "apple"))]
        #[test]
        fn reject_refuses_an_existing_distinct_mount() {
            let fs = RealFs;
            let root = fs
                .open_root(Path::new("/"))
                .expect("filesystem root must open");
            let root_identity = mount_identity(root.fd.as_raw_fd())
                .expect("the root mount identity must resolve on every supported kernel");

            for candidate in ["proc", "sys", "dev"] {
                let Ok(followed) = fs.open_dir_at(&root, candidate, true) else {
                    continue;
                };
                let Ok(candidate_identity) = mount_identity(followed.fd.as_raw_fd()) else {
                    continue;
                };
                // Compared through `same_mount` rather than `==` so the
                // pre-5.8 device fallback is exercised on the same terms
                // production uses.
                if same_mount(&candidate_identity, &root_identity) {
                    continue;
                }

                let error = match fs.open_dir_at(&root, candidate, false) {
                    Ok(_) => panic!("reject mode must not cross the {candidate} mount"),
                    Err(error) => error,
                };
                assert_eq!(error.raw_os_error(), Some(libc::EXDEV));
                return;
            }
        }
    }
}

#[cfg(unix)]
pub use posix::{PosixDir, PosixReplaceMetadata};
#[cfg(unix)]
pub(crate) use posix::{create_pathless_named_scratch_at, lock_capability_absent};

#[cfg(windows)]
mod win {
    use std::{
        ffi::OsStr,
        fs::File,
        io::{self, Write},
        mem::{size_of, size_of_val},
        os::windows::io::{AsRawHandle, FromRawHandle},
        path::Path,
        ptr,
        time::Duration,
    };

    use windows_sys::{
        Wdk::Storage::FileSystem::{
            FILE_CREATE, FILE_DIRECTORY_FILE, FILE_NON_DIRECTORY_FILE, FILE_OPEN_REPARSE_POINT,
            FILE_RENAME_INFORMATION, FILE_SYNCHRONOUS_IO_NONALERT, FileRenameInformation,
        },
        Win32::{
            Foundation::{
                ERROR_FILE_NOT_FOUND, ERROR_INVALID_FUNCTION, ERROR_INVALID_PARAMETER,
                ERROR_NOT_SUPPORTED, ERROR_REPARSE_POINT_ENCOUNTERED, GENERIC_READ, GENERIC_WRITE,
                GetLastError, HANDLE, NTSTATUS, OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE,
                STATUS_ACCESS_DENIED,
            },
            Storage::FileSystem::{
                BY_HANDLE_FILE_INFORMATION, DELETE, FILE_ATTRIBUTE_DIRECTORY,
                FILE_ATTRIBUTE_NORMAL, FILE_ATTRIBUTE_REPARSE_POINT, FILE_ATTRIBUTE_TAG_INFO,
                FILE_ID_INFO, FILE_READ_ATTRIBUTES, FILE_SHARE_DELETE, FILE_SHARE_READ,
                FILE_SHARE_WRITE, FILE_TRAVERSE, FileAttributeTagInfo, FileIdInfo,
                FileStandardInfo, FlushFileBuffers, GetFileInformationByHandle, READ_CONTROL,
                SYNCHRONIZE, WRITE_DAC,
            },
        },
    };

    use super::{
        AmbiguousPublicationCleanup, CreatedStaged, DirectoryPermissions, FileIdentity, FlushKind,
        FlushOutcome, FsOps, MAX_TEMPORARY_ARTIFACT_ATTEMPTS, PublicationAttemptError,
        PublicationUnknownCleanup, RealFs, StagedCreationError, TemporaryFileRole,
    };
    use crate::naming::new_file_lease_artifact_name;
    use crate::windows_nt::{
        FileStandardInfoBytes, NtAbsolutePath, NtCreateOptions, NtRelativeName, OwnedHandle,
        mark_delete_on_close, nt_create_file, nt_open_file, nt_open_file_status,
        nt_set_file_information_bytes, nt_status_to_io_error, query_file_information,
    };
    use crate::winfs::{
        CapturedDacl, apply_file_dacl, capture_file_dacl, owner_only_directory_security,
        owner_only_file_security, verify_file_dacl, verify_owner_only_directory,
        verify_owner_only_file,
    };

    const PREFERRED_DIRECTORY_CAPABILITY_ACCESS: u32 = FILE_TRAVERSE | FILE_READ_ATTRIBUTES;
    const MINIMUM_DIRECTORY_CAPABILITY_ACCESS: u32 = FILE_READ_ATTRIBUTES;
    const CREATED_DIRECTORY_ACCESS: u32 = FILE_TRAVERSE | FILE_READ_ATTRIBUTES;
    const PERMISSIVE_SHARING: u32 = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;

    /// Retained destination-directory capability.
    pub struct WinDir {
        handle: OwnedHandle,
    }

    impl WinDir {
        pub(crate) fn traversal_handle(&self) -> HANDLE {
            self.handle.as_raw()
        }
    }

    impl RealFs {
        pub(crate) fn open_windows_root(&self, path: &Path) -> io::Result<WinDir> {
            let path = NtAbsolutePath::parse(path)?;
            let handle =
                open_directory_capability(ptr::null_mut(), path.as_slice(), OBJ_CASE_INSENSITIVE)?;
            validate_directory(handle.as_raw(), false)?;
            Ok(WinDir { handle })
        }

        pub(crate) fn open_windows_dir_at(
            &self,
            parent: &WinDir,
            name: &OsStr,
            follow_links: bool,
        ) -> io::Result<WinDir> {
            let name = NtRelativeName::parse_os(name)?;
            let object_attributes = if follow_links {
                OBJ_CASE_INSENSITIVE
            } else {
                OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE
            };
            let handle = open_directory_capability(
                parent.traversal_handle(),
                name.as_slice(),
                object_attributes,
            )
            .map_err(map_reparse_error)?;
            validate_directory(handle.as_raw(), !follow_links)?;
            Ok(WinDir { handle })
        }

        pub(crate) fn create_and_open_windows_dir_at(
            &self,
            parent: &WinDir,
            name: &OsStr,
            permissions: DirectoryPermissions,
        ) -> io::Result<WinDir> {
            let handle = create_directory_at(parent, name, permissions)?;
            Ok(WinDir { handle })
        }
    }

    /// Captured destination DACL for a Replace publication.
    pub struct WinReplaceMetadata {
        dacl: CapturedDacl,
    }

    impl FsOps for RealFs {
        type Dir = WinDir;
        type File = File;
        type Metadata = WinReplaceMetadata;

        fn open_root(&self, path: &Path) -> io::Result<WinDir> {
            self.open_windows_root(path)
        }

        fn open_dir_at(
            &self,
            parent: &WinDir,
            name: &str,
            follow_links: bool,
        ) -> io::Result<WinDir> {
            self.open_windows_dir_at(parent, OsStr::new(name), follow_links)
        }

        fn create_dir_at(
            &self,
            parent: &WinDir,
            name: &str,
            permissions: DirectoryPermissions,
        ) -> io::Result<()> {
            drop(create_directory_at(parent, OsStr::new(name), permissions)?);
            Ok(())
        }

        fn create_and_open_dir_at(
            &self,
            parent: &WinDir,
            name: &str,
            permissions: DirectoryPermissions,
        ) -> io::Result<WinDir> {
            self.create_and_open_windows_dir_at(parent, OsStr::new(name), permissions)
        }

        fn create_file_at(&self, dir: &WinDir, name: &str, owner_only: bool) -> io::Result<File> {
            let name = NtRelativeName::parse(name)?;
            let security = if owner_only {
                Some(owner_only_file_security()?)
            } else {
                None
            };
            let security_descriptor = security
                .as_ref()
                .map_or(ptr::null(), |security| security.descriptor());
            let handle = nt_create_file(
                dir.traversal_handle(),
                &name,
                &NtCreateOptions {
                    desired_access: GENERIC_READ
                        | GENERIC_WRITE
                        | DELETE
                        | READ_CONTROL
                        | WRITE_DAC
                        | SYNCHRONIZE,
                    // Deny delete sharing so the sweeper still observes a
                    // live staged artifact as busy, while read/write sharing
                    // permits independent FILE_ID_INFO reconciliation after
                    // an ambiguous rename reply.
                    share_access: FILE_SHARE_READ | FILE_SHARE_WRITE,
                    disposition: FILE_CREATE,
                    create_options: FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT,
                    file_attributes: FILE_ATTRIBUTE_NORMAL,
                    object_attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
                    security_descriptor,
                },
            )?;
            if let Some(expected) = security.as_ref()
                && let Err(error) = verify_owner_only_file(handle.as_raw(), expected)
            {
                // Delete the exact newly-created object if its security
                // descriptor was not applied as requested.
                let _ = mark_delete_on_close(handle.as_raw());
                drop(handle);
                return Err(error);
            }
            // SAFETY: `handle` uniquely owns a successful file-create result
            // and transfers that ownership into `File`.
            Ok(unsafe { File::from_raw_handle(handle.into_raw()) })
        }

        fn create_staged_at(
            &self,
            dir: &WinDir,
            role: TemporaryFileRole,
            owner_only: bool,
        ) -> Result<CreatedStaged<File>, StagedCreationError> {
            for _ in 0..MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
                let name =
                    new_file_lease_artifact_name(role).map_err(StagedCreationError::inferred)?;
                match self.create_file_at(dir, &name, owner_only) {
                    Ok(file) => return Ok(CreatedStaged { name, file }),
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                    Err(error) => return Err(StagedCreationError::inferred(error)),
                }
            }
            Err(StagedCreationError::inferred(io::Error::from(
                io::ErrorKind::AlreadyExists,
            )))
        }

        fn write_all(&self, file: &mut File, buffer: &[u8]) -> io::Result<()> {
            file.write_all(buffer)
        }

        fn flush_file(&self, file: &mut File, _kind: FlushKind) -> io::Result<FlushOutcome> {
            flush_handle(file)
        }

        fn read_replace_metadata(
            &self,
            dir: &WinDir,
            name: &str,
        ) -> io::Result<Option<WinReplaceMetadata>> {
            let name = NtRelativeName::parse(name)?;
            let destination = match nt_open_file(
                dir.traversal_handle(),
                name.as_slice(),
                READ_CONTROL | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                PERMISSIVE_SHARING,
                FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT,
                OBJ_CASE_INSENSITIVE,
            ) {
                Ok(destination) => destination,
                Err(error) => {
                    if error.raw_os_error() == Some(ERROR_FILE_NOT_FOUND as i32) {
                        return Ok(None);
                    }
                    return Err(error);
                }
            };
            let attributes: FILE_ATTRIBUTE_TAG_INFO =
                query_file_information(destination.as_raw(), FileAttributeTagInfo)?;
            let standard: FileStandardInfoBytes =
                query_file_information(destination.as_raw(), FileStandardInfo)?;
            if attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
                || attributes.ReparseTag != 0
                || standard.is_directory()
            {
                return Err(io::Error::from(io::ErrorKind::InvalidInput));
            }
            let dacl = capture_file_dacl(destination.as_raw())?;
            Ok(Some(WinReplaceMetadata { dacl }))
        }

        fn apply_replace_metadata(
            &self,
            file: &mut File,
            metadata: &WinReplaceMetadata,
        ) -> io::Result<()> {
            apply_file_dacl(file.as_raw_handle(), &metadata.dacl)
        }

        fn verify_replace_metadata(
            &self,
            file: &mut File,
            metadata: &WinReplaceMetadata,
        ) -> io::Result<()> {
            verify_file_dacl(file.as_raw_handle(), &metadata.dacl)
        }

        fn staged_file_identity(&self, file: &File) -> io::Result<FileIdentity> {
            windows_file_identity(file.as_raw_handle())
        }

        fn observe_file_identity_at(
            &self,
            dir: &WinDir,
            name: &str,
        ) -> io::Result<Option<FileIdentity>> {
            let name = NtRelativeName::parse(name)?;
            let destination = match nt_open_file(
                dir.traversal_handle(),
                name.as_slice(),
                FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                PERMISSIVE_SHARING,
                FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT,
                OBJ_CASE_INSENSITIVE,
            ) {
                Ok(destination) => destination,
                Err(error) if error.raw_os_error() == Some(ERROR_FILE_NOT_FOUND as i32) => {
                    return Ok(None);
                }
                Err(error) => return Err(map_reparse_error(error)),
            };
            Ok(Some(windows_file_identity(destination.as_raw())?))
        }

        fn rename(
            &self,
            dir: &WinDir,
            _from: &str,
            file: &mut File,
            to: &str,
            no_replace: bool,
        ) -> Result<(), PublicationAttemptError> {
            rename_by_handle(file, dir.traversal_handle(), to, no_replace)
        }

        fn hard_link(
            &self,
            _dir: &WinDir,
            _from: &str,
            _file: &File,
            _to: &str,
        ) -> Result<(), PublicationAttemptError> {
            // The handle-based rename already failed; there is no path-based
            // fallback worth trusting after the staged handle has established
            // the file identity that the transaction intends to publish.
            Err(PublicationAttemptError::DefinitelyUnchanged(
                io::Error::from_raw_os_error(ERROR_NOT_SUPPORTED as i32),
            ))
        }

        fn unlink(&self, dir: &WinDir, name: &str) -> io::Result<()> {
            let name = NtRelativeName::parse(name)?;
            let handle = nt_open_file(
                dir.traversal_handle(),
                name.as_slice(),
                DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                PERMISSIVE_SHARING,
                FILE_NON_DIRECTORY_FILE | FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT,
                OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
            )
            .map_err(map_reparse_error)?;
            mark_delete_on_close(handle.as_raw())?;
            drop(handle);
            Ok(())
        }

        fn flush_directory(&self, _dir: &WinDir) -> io::Result<FlushOutcome> {
            // Win32 exposes no documented equivalent of POSIX directory
            // fsync. Missing parent paths therefore cannot satisfy a strict
            // Durable request.
            Ok(FlushOutcome::Unsupported)
        }

        fn flush_publication(&self, _dir: &WinDir, file: &mut File) -> io::Result<FlushOutcome> {
            // The staged handle stays valid under the destination name, so
            // this is the strongest barrier Win32 offers after publication.
            // It is deliberately not claimed to persist the directory entry:
            // FlushFileBuffers is documented only to write "the buffered
            // information for a specified file", with no statement about the
            // containing directory. Nothing here may be used to argue that
            // `platform_max_sync_level` could be raised on Windows — that
            // ceiling stays at `FileSynchronized`, and `flush_directory`
            // above reports `Unsupported` for the same reason.
            flush_handle(file)
        }

        fn close(&self, file: File) -> io::Result<()> {
            // Dropping File closes the handle; Windows reports close errors
            // through FlushFileBuffers, which the protocol already issued.
            drop(file);
            Ok(())
        }

        fn discard_staged(&self, dir: &WinDir, name: &str, file: File) -> io::Result<()> {
            let result = discard_staged_under_its_own_name(dir, name, &file);
            drop(file);
            result
        }

        fn cleanup_after_publication_unknown(
            &self,
            _dir: &WinDir,
            _name: &str,
            file: File,
        ) -> PublicationUnknownCleanup {
            // The retained handle may now be the destination. Disposition or
            // delete-on-close would therefore risk deleting a successfully
            // published file after a lost rename reply.
            drop(file);
            PublicationUnknownCleanup::Incomplete(None)
        }

        fn ambiguous_publication_cleanup(&self) -> AmbiguousPublicationCleanup {
            AmbiguousPublicationCleanup::CloseOnly
        }

        fn is_rename_unsupported(&self, _error: &io::Error) -> bool {
            // Windows capability refusals arrive only after the rename request
            // has been dispatched, so they are ambiguous rather than evidence
            // authorizing a different publication primitive.
            false
        }

        fn is_rename_retryable(&self, _error: &io::Error) -> bool {
            false
        }

        fn rename_retry_delays(&self) -> &[Duration] {
            &[]
        }
    }

    fn flush_handle(file: &File) -> io::Result<FlushOutcome> {
        // SAFETY: The handle is valid for the duration of the call.
        if unsafe { FlushFileBuffers(file.as_raw_handle()) } != 0 {
            return Ok(FlushOutcome::Full);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() == Some(ERROR_INVALID_FUNCTION as i32) {
            return Ok(FlushOutcome::Unsupported);
        }
        Err(error)
    }

    fn open_directory_capability(
        root: HANDLE,
        name: &[u16],
        object_attributes: u32,
    ) -> io::Result<OwnedHandle> {
        retry_directory_capability_open(|desired_access| {
            nt_open_file_status(
                root,
                name,
                desired_access,
                PERMISSIVE_SHARING,
                FILE_DIRECTORY_FILE,
                object_attributes,
            )
        })
        .map_err(nt_status_to_io_error)
    }

    fn retry_directory_capability_open<T>(
        mut open: impl FnMut(u32) -> Result<T, NTSTATUS>,
    ) -> Result<T, NTSTATUS> {
        match open(PREFERRED_DIRECTORY_CAPABILITY_ACCESS) {
            // A retained directory is a metadata target and a RootDirectory
            // anchor, not an I/O stream. Prefer explicit traversal access for
            // tokens without bypass-traverse privilege, but tolerate
            // constrained tokens that permit metadata access and
            // handle-relative lookup while denying FILE_TRAVERSE.
            Err(STATUS_ACCESS_DENIED) => open(MINIMUM_DIRECTORY_CAPABILITY_ACCESS),
            result => result,
        }
    }

    fn create_directory_at(
        parent: &WinDir,
        name: &OsStr,
        permissions: DirectoryPermissions,
    ) -> io::Result<OwnedHandle> {
        let name = NtRelativeName::parse_os(name)?;
        let security = match permissions {
            DirectoryPermissions::OwnerOnly => Some(owner_only_directory_security()?),
            DirectoryPermissions::ProcessDefault => None,
        };
        let security_descriptor = security
            .as_ref()
            .map_or(ptr::null(), |security| security.descriptor());
        let desired_access =
            CREATED_DIRECTORY_ACCESS | READ_CONTROL | if security.is_some() { DELETE } else { 0 };
        let handle = nt_create_file(
            parent.traversal_handle(),
            &name,
            &NtCreateOptions {
                desired_access,
                share_access: PERMISSIVE_SHARING,
                disposition: FILE_CREATE,
                create_options: FILE_DIRECTORY_FILE,
                file_attributes: FILE_ATTRIBUTE_NORMAL,
                object_attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
                security_descriptor,
            },
        )
        .map_err(map_reparse_error)?;
        if let Some(expected) = security.as_ref()
            && let Err(error) = verify_owner_only_directory(handle.as_raw(), expected)
        {
            // Verification is tied to the exact object returned by create.
            // Best-effort delete-on-close prevents a misconfigured empty
            // directory from surviving, without resolving its name again.
            let _ = mark_delete_on_close(handle.as_raw());
            drop(handle);
            return Err(error);
        }
        Ok(handle)
    }

    fn validate_directory(handle: HANDLE, reject_reparse: bool) -> io::Result<()> {
        let attributes: FILE_ATTRIBUTE_TAG_INFO =
            query_file_information(handle, FileAttributeTagInfo)?;
        if attributes.FileAttributes & FILE_ATTRIBUTE_DIRECTORY == 0 {
            return Err(io::Error::from(io::ErrorKind::NotADirectory));
        }
        if reject_reparse
            && (attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
                || attributes.ReparseTag != 0)
        {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        Ok(())
    }

    fn map_reparse_error(error: io::Error) -> io::Error {
        if error.raw_os_error() == Some(ERROR_REPARSE_POINT_ENCOUNTERED as i32) {
            io::Error::from(io::ErrorKind::InvalidInput)
        } else {
            error
        }
    }

    fn rename_by_handle(
        file: &File,
        destination_directory: HANDLE,
        destination_name: &str,
        no_replace: bool,
    ) -> Result<(), PublicationAttemptError> {
        let mode = if no_replace {
            WindowsRenameMode::CreateExclusive
        } else {
            WindowsRenameMode::Replace
        };
        WindowsRenameRequest::new(destination_directory, destination_name, mode)
            .map_err(PublicationAttemptError::DefinitelyUnchanged)?
            .dispatch_with(file, nt_set_file_information_bytes)
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum WindowsRenameMode {
        CreateExclusive,
        Replace,
    }

    /// A completely prepared, one-shot Windows rename request.
    ///
    /// Construction performs every fallible operation that can still prove the
    /// namespace unchanged. Once [`Self::dispatch_with`] invokes its closure,
    /// every returned failure is ambiguous and no request may be re-issued.
    struct WindowsRenameRequest {
        storage: Vec<usize>,
        request_len: usize,
        information_class: i32,
    }

    impl WindowsRenameRequest {
        fn new(
            destination_directory: HANDLE,
            destination_name: &str,
            mode: WindowsRenameMode,
        ) -> io::Result<Self> {
            let name = NtRelativeName::parse(destination_name)?;
            let name = name.as_slice();
            let name_bytes = size_of_val(name);
            // Microsoft specifies a buffer of at least the struct plus the
            // FileName bytes. `FileNameLength` below remains the exact
            // unterminated byte length.
            //
            // The allocation is `usize`-backed rather than a `Vec<u8>` because
            // the buffer is written through as the struct: `RootDirectory` is
            // pointer-aligned, whereas `Vec<u8>` guarantees only byte
            // alignment.
            const {
                assert!(
                    align_of::<FILE_RENAME_INFORMATION>() <= align_of::<usize>(),
                    "usize-backed storage must satisfy FILE_RENAME_INFORMATION alignment",
                );
            }
            let request_len = size_of::<FILE_RENAME_INFORMATION>() + name_bytes;
            let mut storage = vec![0_usize; request_len.div_ceil(size_of::<usize>())];
            let info = storage.as_mut_ptr().cast::<FILE_RENAME_INFORMATION>();
            // SAFETY: `info` points at a live, zeroed allocation of at least
            // `request_len` bytes whose alignment the const assertion above
            // proves sufficient for the struct; every write stays inside it.
            unsafe {
                (*info).Anonymous.ReplaceIfExists = mode == WindowsRenameMode::Replace;
                (*info).RootDirectory = destination_directory;
                (*info).FileNameLength = name_bytes as u32;
                ptr::copy_nonoverlapping(
                    name.as_ptr(),
                    (&raw mut (*info).FileName).cast::<u16>(),
                    name.len(),
                );
            }
            Ok(Self {
                storage,
                request_len,
                information_class: FileRenameInformation,
            })
        }

        fn dispatch_with(
            self,
            file: &File,
            dispatch: impl FnOnce(HANDLE, i32, &[u8]) -> io::Result<()>,
        ) -> Result<(), PublicationAttemptError> {
            // SAFETY: `self.storage` remains live across the one dispatch call
            // and contains at least `self.request_len` initialized bytes.
            let request = unsafe {
                std::slice::from_raw_parts(self.storage.as_ptr().cast::<u8>(), self.request_len)
            };
            dispatch(file.as_raw_handle(), self.information_class, request)
                .map_err(PublicationAttemptError::MayHaveMutated)
        }
    }

    /// Discards the staged file only while the staged name still names it.
    ///
    /// A handle-relative rename leaves the retained handle valid *under the
    /// destination name*, so a delete disposition applied to that handle after
    /// a rename that took effect destroys the published file. That is not a
    /// hypothetical: Microsoft documents that a minifilter which fails an
    /// operation in a post-operation callback does not undo the operation, so a
    /// rename can succeed and still report `ERROR_ACCESS_DENIED`. The
    /// transaction now keeps that result on its close-only ambiguous-cleanup
    /// path, while this identity check remains defense in depth for every
    /// ordinary staged-name discard.
    ///
    /// Deletion must therefore stay attached to the *name*, not to the handle.
    /// It cannot be done by reopening the name for `DELETE`, because the staged
    /// file is deliberately opened without `FILE_SHARE_DELETE` so a concurrent
    /// sweeper observes it as busy — this process's own retained handle would
    /// refuse the second open with a sharing violation. What remains is to
    /// prove the binding first: while the staged name still resolves to the
    /// retained object, the rename did not take effect, so the handle cannot be
    /// the destination and the disposition is safe.
    ///
    /// Observing the name absent means the rename consumed it, or a sweeper
    /// already reclaimed it, so there is nothing to remove and nothing to
    /// report. Any other answer withholds deletion: a leaked temporary is
    /// recoverable by a later sweep, and a destroyed vault is not.
    fn discard_staged_under_its_own_name(dir: &WinDir, name: &str, file: &File) -> io::Result<()> {
        let retained = windows_file_identity(file.as_raw_handle())?;
        match RealFs.observe_file_identity_at(dir, name)? {
            None => Ok(()),
            Some(identity) if identity == retained => mark_delete_on_close(file.as_raw_handle()),
            Some(_) => Err(io::Error::other(
                "staged name no longer refers to the retained file",
            )),
        }
    }

    /// Reads an object's identity, degrading on volumes without file IDs.
    ///
    /// `FileIdInfo` is unimplemented across the FAT family: the reference
    /// `fastfat` driver's `FatCommonQueryInformation` has no
    /// `FileIdInformation` case and falls through to
    /// `STATUS_INVALID_PARAMETER`, and Microsoft documents the same family
    /// limitation for the related open flag — "The FAT, ExFAT, UDFS, and CDFS
    /// file systems do not support the FILE_OPEN_BY_FILE_ID flag."
    ///
    /// `GetFileInformationByHandle` is the documented substitute for precisely
    /// this comparison: "You can compare the VolumeSerialNumber and FileIndex
    /// members returned in the BY_HANDLE_FILE_INFORMATION structure to
    /// determine if two paths map to the same target." It states no required
    /// access rights, so it also serves the attribute-only handle that
    /// [`FsOps::observe_file_identity_at`] opens.
    ///
    /// The weaker answer carries its own [`IdentityScheme`], so it is only ever
    /// compared against another weak answer.
    fn windows_file_identity(handle: HANDLE) -> io::Result<FileIdentity> {
        let strong = match query_file_information::<FILE_ID_INFO>(handle, FileIdInfo) {
            Ok(identity) => {
                return Ok(FileIdentity::windows_file_id_128(
                    identity.VolumeSerialNumber,
                    identity.FileId.Identifier,
                ));
            }
            Err(error) => error,
        };
        if !information_class_unrecognized(&strong) {
            return Err(strong);
        }

        let mut information = std::mem::MaybeUninit::<BY_HANDLE_FILE_INFORMATION>::uninit();
        // SAFETY: `handle` is a live non-pipe file handle and `information` is
        // writable storage of exactly the out-parameter's type.
        if unsafe { GetFileInformationByHandle(handle, information.as_mut_ptr()) } == 0 {
            // SAFETY: `GetLastError` is read immediately after the failed call.
            return Err(io::Error::from_raw_os_error(
                unsafe { GetLastError() } as i32
            ));
        }
        // SAFETY: A successful call initialized the output structure.
        let information = unsafe { information.assume_init() };
        let index =
            (u64::from(information.nFileIndexHigh) << 32) | u64::from(information.nFileIndexLow);
        Ok(FileIdentity::windows_file_index_64(
            u64::from(information.dwVolumeSerialNumber),
            index,
        ))
    }

    /// Returns whether a file-information request was rejected because the
    /// volume does not implement the class, rather than attempted and refused.
    ///
    /// An unimplemented information class surfaces as
    /// `STATUS_INVALID_PARAMETER` or `STATUS_INVALID_INFO_CLASS`, which map
    /// onto these Win32 codes. This predicate is only for the read-only file-ID
    /// query fallback; publication never interprets a returned rename error.
    fn information_class_unrecognized(error: &io::Error) -> bool {
        matches!(
            error.raw_os_error(),
            Some(code) if code == ERROR_INVALID_PARAMETER as i32
                || code == ERROR_NOT_SUPPORTED as i32
                || code == ERROR_INVALID_FUNCTION as i32
        )
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use crate::{fsops::IdentityScheme, winfs::verify_inherited_owner_only_child};
        use windows_sys::Win32::Foundation::{
            ERROR_ACCESS_DENIED, ERROR_INVALID_LEVEL, ERROR_SHARING_VIOLATION,
        };

        fn test_directory(label: &str) -> (std::path::PathBuf, std::path::PathBuf) {
            let mut nonce = [0_u8; 8];
            getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
            let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
            let directory =
                std::env::temp_dir().join(format!("keyguard-win-fsops-{label}-{nonce}"));
            let moved =
                std::env::temp_dir().join(format!("keyguard-win-fsops-{label}-{nonce}-moved"));
            std::fs::create_dir(&directory).expect("test directory must be created");
            (directory, moved)
        }

        /// The FAT-family fallback must never be mistaken for the strong
        /// answer. Microsoft documents no relationship between the 128-bit
        /// `FILE_ID_INFO` file ID and the 64-bit file index, so an identity
        /// carrying the same bits under a different scheme has to compare
        /// unequal — publication would otherwise be able to conclude "the
        /// destination is my staged file" from two incomparable observations.
        #[test]
        fn file_index_identities_never_equal_file_id_identities() {
            const VOLUME: u64 = 0x0123_4567_89ab_cdef;
            const INDEX: u64 = 0x1122_3344_5566_7788;

            let mut zero_extended = [0_u8; 16];
            zero_extended[..8].copy_from_slice(&INDEX.to_ne_bytes());

            let weak = FileIdentity::windows_file_index_64(VOLUME, INDEX);
            let strong = FileIdentity::windows_file_id_128(VOLUME, zero_extended);
            assert_ne!(
                weak, strong,
                "a zero-extended file index must not alias a 128-bit file ID"
            );

            assert_eq!(weak, FileIdentity::windows_file_index_64(VOLUME, INDEX));
            assert_eq!(
                strong,
                FileIdentity::windows_file_id_128(VOLUME, zero_extended)
            );
            assert_ne!(
                weak,
                FileIdentity::windows_file_index_64(VOLUME, INDEX ^ 1),
                "a differing index must still be distinguished"
            );
            assert_ne!(
                weak,
                FileIdentity::windows_file_index_64(VOLUME ^ 1, INDEX),
                "a differing volume must still be distinguished"
            );
        }

        /// The fallback is entered only for an unimplemented information class.
        /// A genuine failure — a revoked handle, an I/O error — must propagate
        /// rather than silently downgrade the identity scheme.
        #[test]
        fn only_unrecognized_information_classes_select_the_fallback() {
            for code in [
                ERROR_INVALID_PARAMETER,
                ERROR_NOT_SUPPORTED,
                ERROR_INVALID_FUNCTION,
            ] {
                assert!(
                    information_class_unrecognized(&io::Error::from_raw_os_error(code as i32)),
                    "{code} must select the file-index fallback"
                );
            }
            for code in [
                ERROR_ACCESS_DENIED,
                ERROR_SHARING_VIOLATION,
                ERROR_FILE_NOT_FOUND,
                ERROR_INVALID_LEVEL,
            ] {
                assert!(
                    !information_class_unrecognized(&io::Error::from_raw_os_error(code as i32)),
                    "{code} must propagate instead of downgrading the scheme"
                );
            }
        }

        #[test]
        fn retained_directory_open_retries_only_raw_access_denied() {
            let mut preferred_attempts = Vec::new();
            let preferred = retry_directory_capability_open(|access| {
                preferred_attempts.push(access);
                Ok::<_, NTSTATUS>("preferred")
            });
            assert_eq!(preferred, Ok("preferred"));
            assert_eq!(preferred_attempts, [PREFERRED_DIRECTORY_CAPABILITY_ACCESS]);

            let mut fallback_attempts = Vec::new();
            let fallback = retry_directory_capability_open(|access| {
                fallback_attempts.push(access);
                if fallback_attempts.len() == 1 {
                    Err(STATUS_ACCESS_DENIED)
                } else {
                    Ok("minimum")
                }
            });
            assert_eq!(fallback, Ok("minimum"));
            assert_eq!(
                fallback_attempts,
                [
                    PREFERRED_DIRECTORY_CAPABILITY_ACCESS,
                    MINIMUM_DIRECTORY_CAPABILITY_ACCESS,
                ]
            );

            let mut delete_pending_attempts = Vec::new();
            let delete_pending = retry_directory_capability_open(|access| {
                delete_pending_attempts.push(access);
                Err::<(), _>(crate::windows_nt::STATUS_DELETE_PENDING)
            });
            assert_eq!(
                delete_pending,
                Err(crate::windows_nt::STATUS_DELETE_PENDING)
            );
            assert_eq!(
                delete_pending_attempts,
                [PREFERRED_DIRECTORY_CAPABILITY_ACCESS]
            );
        }

        /// The strong query must be what a real NTFS volume answers, so the
        /// fallback stays dormant where file IDs exist.
        #[test]
        fn ntfs_temporary_volume_yields_a_file_id_identity() {
            let (directory, _moved) = test_directory("identity");
            let path = directory.join("identity.bin");
            std::fs::write(&path, b"identity").expect("test file must be written");
            let file = File::open(&path).expect("test file must open");

            let identity = windows_file_identity(file.as_raw_handle())
                .expect("a temporary volume must report an identity");
            assert_eq!(identity.scheme, IdentityScheme::WindowsFileId128);

            let again = windows_file_identity(file.as_raw_handle())
                .expect("a second observation must succeed");
            assert_eq!(identity, again, "identity must be stable for one handle");

            drop(file);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn prepared_rename_request_encodes_native_modes_and_retained_root() {
            let (directory, _moved) = test_directory("request-encoding");
            let file = File::create(directory.join("stage.tmp"))
                .expect("staged file handle must be created");
            let destination_directory = file.as_raw_handle();
            let destination_name = "vault.bin";
            let expected_name: Vec<u16> = destination_name.encode_utf16().collect();
            let expected_name_bytes: Vec<u8> = expected_name
                .iter()
                .flat_map(|unit| unit.to_ne_bytes())
                .collect();

            for (mode, expected_replace) in [
                (WindowsRenameMode::CreateExclusive, false),
                (WindowsRenameMode::Replace, true),
            ] {
                let request =
                    WindowsRenameRequest::new(destination_directory, destination_name, mode)
                        .expect("rename request must be prepared");
                request
                    .dispatch_with(&file, |handle, information_class, request| {
                        assert_eq!(handle, file.as_raw_handle());
                        assert_eq!(information_class, FileRenameInformation);
                        assert_eq!(
                            request.len(),
                            size_of::<FILE_RENAME_INFORMATION>() + expected_name_bytes.len(),
                        );
                        assert_eq!(
                            &request[..size_of::<u32>()],
                            if expected_replace {
                                &[1, 0, 0, 0]
                            } else {
                                &[0, 0, 0, 0]
                            },
                            "the native boolean and its union padding must be canonical",
                        );

                        let root_offset =
                            std::mem::offset_of!(FILE_RENAME_INFORMATION, RootDirectory);
                        let encoded_root = usize::from_ne_bytes(
                            request[root_offset..root_offset + size_of::<usize>()]
                                .try_into()
                                .expect("request must contain the retained root handle"),
                        );
                        assert_eq!(encoded_root, destination_directory as usize);

                        let length_offset =
                            std::mem::offset_of!(FILE_RENAME_INFORMATION, FileNameLength);
                        let encoded_length = u32::from_ne_bytes(
                            request[length_offset..length_offset + size_of::<u32>()]
                                .try_into()
                                .expect("request must contain the file-name length"),
                        );
                        assert_eq!(
                            encoded_length as usize,
                            size_of_val(expected_name.as_slice())
                        );

                        let name_offset = std::mem::offset_of!(FILE_RENAME_INFORMATION, FileName);
                        assert_eq!(
                            &request[name_offset..name_offset + encoded_length as usize],
                            expected_name_bytes.as_slice(),
                        );
                        Ok(())
                    })
                    .expect("the injected dispatch must succeed");
            }

            drop(file);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn every_dispatched_rename_error_is_ambiguous_and_issued_once() {
            let (directory, _moved) = test_directory("dispatch-errors");
            let file = File::create(directory.join("stage.tmp"))
                .expect("staged file handle must be created");

            for mode in [
                WindowsRenameMode::CreateExclusive,
                WindowsRenameMode::Replace,
            ] {
                for code in [
                    ERROR_ACCESS_DENIED,
                    ERROR_SHARING_VIOLATION,
                    ERROR_INVALID_PARAMETER,
                    ERROR_NOT_SUPPORTED,
                    ERROR_INVALID_FUNCTION,
                ] {
                    let request =
                        WindowsRenameRequest::new(file.as_raw_handle(), "vault.bin", mode)
                            .expect("rename request must be prepared");
                    let mut calls = 0;
                    let error = request
                        .dispatch_with(&file, |_handle, information_class, request| {
                            calls += 1;
                            assert_eq!(information_class, FileRenameInformation);
                            assert!(!request.is_empty());
                            Err(io::Error::from_raw_os_error(code as i32))
                        })
                        .expect_err("the injected dispatch must fail");

                    assert_eq!(calls, 1, "a prepared request may be dispatched only once");
                    assert_eq!(error.error().raw_os_error(), Some(code as i32));
                    assert!(
                        matches!(error, PublicationAttemptError::MayHaveMutated(_)),
                        "every error returned after dispatch must remain ambiguous",
                    );
                }
            }

            let error = rename_by_handle(&file, ptr::null_mut(), "bad/name", false)
                .expect_err("invalid input must fail while preparing the request");
            assert!(
                matches!(error, PublicationAttemptError::DefinitelyUnchanged(_)),
                "pre-dispatch validation may still prove the namespace unchanged",
            );

            drop(file);
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn windows_never_retries_a_returned_rename_error() {
            for code in [
                ERROR_ACCESS_DENIED,
                ERROR_SHARING_VIOLATION,
                ERROR_INVALID_PARAMETER,
                ERROR_NOT_SUPPORTED,
                ERROR_INVALID_FUNCTION,
            ] {
                assert!(
                    !RealFs.is_rename_retryable(&io::Error::from_raw_os_error(code as i32)),
                    "Win32 error {code} was returned after dispatch",
                );
                assert!(
                    !RealFs.is_rename_unsupported(&io::Error::from_raw_os_error(code as i32)),
                    "Win32 error {code} must not authorize a publication fallback",
                );
            }
            assert!(RealFs.rename_retry_delays().is_empty());
        }

        #[test]
        fn native_replace_overwrites_a_closed_regular_destination() {
            let (directory, _moved) = test_directory("native-replace");
            std::fs::write(directory.join("vault.bin"), b"old bytes")
                .expect("existing destination must be created");

            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"complete replacement")
                    .expect("staged write must succeed");

                fs.rename(&dir, "stage.tmp", &mut staged, "vault.bin", false)
                    .expect("native rename must replace a closed regular destination");
                fs.close(staged).expect("staged handle must close");
            }

            assert_eq!(
                std::fs::read(directory.join("vault.bin"))
                    .expect("replacement destination must be readable"),
                b"complete replacement",
            );
            assert!(!directory.join("stage.tmp").exists());
            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn native_exclusive_create_collision_is_ambiguous_and_preserves_both_files() {
            let (directory, _moved) = test_directory("native-create-collision");
            std::fs::write(directory.join("vault.bin"), b"existing")
                .expect("existing destination must be created");

            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"candidate")
                    .expect("staged write must succeed");

                let error = fs
                    .rename(&dir, "stage.tmp", &mut staged, "vault.bin", true)
                    .expect_err("native exclusive create must refuse an occupied destination");
                assert!(
                    matches!(error, PublicationAttemptError::MayHaveMutated(_)),
                    "even a collision returned after dispatch must remain ambiguous",
                );
                fs.close(staged)
                    .expect("ambiguous cleanup must only close the retained handle");
            }

            assert_eq!(
                std::fs::read(directory.join("vault.bin"))
                    .expect("existing destination must remain readable"),
                b"existing",
            );
            assert_eq!(
                std::fs::read(directory.join("stage.tmp"))
                    .expect("refused staged candidate must remain readable"),
                b"candidate",
            );
            let _ = std::fs::remove_dir_all(directory);
        }

        /// A real refusal still comes back after the kernel has received the
        /// operation. The destination may appear unchanged, but that
        /// observation cannot prove that no mutation happened and was later
        /// hidden or superseded. Cleanup must therefore close the handle
        /// without setting delete disposition.
        #[test]
        fn a_dispatched_refusal_remains_ambiguous() {
            let (directory, _moved) = test_directory("refusal");
            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                std::fs::create_dir(directory.join("vault.bin"))
                    .expect("occupying directory must be created");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"unpublished")
                    .expect("staged write must succeed");

                let error = fs
                    .rename(&dir, "stage.tmp", &mut staged, "vault.bin", false)
                    .expect_err("a directory destination must refuse the rename");
                assert!(
                    matches!(error, PublicationAttemptError::MayHaveMutated(_)),
                    "a returned dispatch error must remain ambiguous",
                );

                fs.close(staged)
                    .expect("ambiguous cleanup must only close the retained handle");
                assert!(
                    directory.join("vault.bin").is_dir(),
                    "the destination must be untouched",
                );
                assert!(
                    directory.join("stage.tmp").exists(),
                    "ambiguous cleanup must not delete the staged name",
                );
            }

            let _ = std::fs::remove_dir_all(directory);
        }

        #[test]
        fn relative_publish_stays_anchored_after_directory_rename() {
            let (directory, moved) = test_directory("rename");
            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                std::fs::rename(&directory, &moved)
                    .expect("open directory must permit rename sharing");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"anchored")
                    .expect("staged write must succeed");

                fs.rename(&dir, "stage.tmp", &mut staged, "published.bin", false)
                    .expect("handle-relative publication must succeed");
                fs.close(staged).expect("staged handle must close");
            }

            assert!(!directory.exists());
            assert_eq!(
                std::fs::read(moved.join("published.bin")).expect("published bytes must be read"),
                b"anchored"
            );
            let _ = std::fs::remove_dir_all(&moved);
        }

        #[test]
        fn staged_discard_uses_identity_after_directory_rename() {
            let (directory, moved) = test_directory("discard");
            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                std::fs::rename(&directory, &moved)
                    .expect("open directory must permit rename sharing");
                let staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");

                fs.discard_staged(&dir, "stage.tmp", staged)
                    .expect("handle-relative discard must succeed");
                assert!(!moved.join("stage.tmp").exists());
            }

            let _ = std::fs::remove_dir_all(&moved);
        }

        /// Simulates a minifilter rejecting completed Create and Replace
        /// operations from its post-operation callback: perform each real
        /// rename, then replace its successful reply with
        /// `ERROR_ACCESS_DENIED`.
        #[test]
        fn applied_native_renames_with_lost_replies_are_ambiguous_and_dispatched_once() {
            for (label, mode, existing_destination) in [
                (
                    "lost-create-reply",
                    WindowsRenameMode::CreateExclusive,
                    false,
                ),
                ("lost-replace-reply", WindowsRenameMode::Replace, true),
            ] {
                let (directory, _moved) = test_directory(label);
                if existing_destination {
                    std::fs::write(directory.join("vault.bin"), b"old bytes")
                        .expect("existing destination must be created");
                }
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"the complete new vault")
                    .expect("staged write must succeed");

                let request = WindowsRenameRequest::new(dir.traversal_handle(), "vault.bin", mode)
                    .expect("rename request must be prepared");
                let mut calls = 0;
                let error = request
                    .dispatch_with(&staged, |handle, information_class, request| {
                        calls += 1;
                        nt_set_file_information_bytes(handle, information_class, request)?;
                        Err(io::Error::from_raw_os_error(ERROR_ACCESS_DENIED as i32))
                    })
                    .expect_err("the successful rename reply must be hidden");

                assert_eq!(calls, 1, "publication must be dispatched exactly once");
                assert!(
                    matches!(error, PublicationAttemptError::MayHaveMutated(_)),
                    "a failure returned after dispatch must remain ambiguous",
                );
                assert!(
                    matches!(
                        fs.cleanup_after_publication_unknown(&dir, "stage.tmp", staged),
                        PublicationUnknownCleanup::Incomplete(None),
                    ),
                    "ambiguous cleanup must close without setting delete disposition",
                );

                assert_eq!(
                    std::fs::read(directory.join("vault.bin"))
                        .expect("the published destination must survive ambiguous cleanup"),
                    b"the complete new vault",
                );
                assert!(!directory.join("stage.tmp").exists());

                let _ = std::fs::remove_dir_all(directory);
            }
        }

        /// The same hazard with the staged name reoccupied by an unrelated file.
        ///
        /// Guards against the tempting-but-wrong repair of deleting through the
        /// handle whenever the staged name merely exists: after a rename that
        /// took effect the name is free, so anything may reappear under it while
        /// the retained handle still denotes the destination. Only an identity
        /// match may authorize the disposition, and a mismatch must be reported
        /// rather than acted on.
        #[test]
        fn staged_discard_refuses_a_reoccupied_staged_name_and_keeps_the_destination() {
            let (directory, _moved) = test_directory("discard-substituted");
            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"the complete new vault")
                    .expect("staged write must succeed");
                fs.rename(&dir, "stage.tmp", &mut staged, "vault.bin", true)
                    .expect("handle-relative publication must succeed");
                std::fs::write(directory.join("stage.tmp"), b"unrelated")
                    .expect("substitute must be created");

                fs.discard_staged(&dir, "stage.tmp", staged)
                    .expect_err("a substituted staged name must be reported, not deleted");

                assert_eq!(
                    std::fs::read(directory.join("vault.bin"))
                        .expect("the published destination must survive staged cleanup"),
                    b"the complete new vault",
                );
                assert_eq!(
                    std::fs::read(directory.join("stage.tmp")).expect("substitute must remain"),
                    b"unrelated",
                );
            }

            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn owner_only_directory_is_verified_and_inherited_by_children() {
            let (directory, _) = test_directory("owner-directory");
            {
                let fs = RealFs;
                let root = fs.open_root(&directory).expect("root directory must open");
                let expected_file =
                    owner_only_file_security().expect("expected file descriptor must build");
                let owner_file = fs
                    .create_file_at(&root, "owner-file.bin", true)
                    .expect("owner-only file must be created and verified");
                verify_owner_only_file(owner_file.as_raw_handle(), &expected_file)
                    .expect("created file owner and DACL must match");
                fs.close(owner_file).expect("owner-only file must close");

                let secure = fs
                    .create_and_open_dir_at(&root, "secure", DirectoryPermissions::OwnerOnly)
                    .expect("owner-only directory must be created and verified");
                let expected = owner_only_directory_security()
                    .expect("expected directory descriptor must be built");
                verify_owner_only_directory(secure.traversal_handle(), &expected)
                    .expect("created directory owner and DACL must match");

                let mut child_file = fs
                    .create_file_at(&secure, "child.bin", false)
                    .expect("child file must inherit usable access");
                fs.write_all(&mut child_file, b"inherited")
                    .expect("inherited file access must permit writing");
                verify_inherited_owner_only_child(child_file.as_raw_handle(), &expected)
                    .expect("child file must have only the inherited owner ACE");
                assert!(
                    verify_owner_only_file(child_file.as_raw_handle(), &expected_file).is_err(),
                    "the file verifier must reject an inherited child DACL"
                );
                fs.close(child_file).expect("child file must close");

                let child_directory = fs
                    .create_and_open_dir_at(
                        &secure,
                        "child-directory",
                        DirectoryPermissions::ProcessDefault,
                    )
                    .expect("child directory must inherit usable access");
                verify_inherited_owner_only_child(child_directory.traversal_handle(), &expected)
                    .expect("child directory must have only the inherited owner ACE");

                let mut grandchild_file = fs
                    .create_file_at(&child_directory, "grandchild.bin", false)
                    .expect("inherited directory ACE must propagate to a grandchild");
                fs.write_all(&mut grandchild_file, b"propagated")
                    .expect("propagated file access must permit writing");
                verify_inherited_owner_only_child(grandchild_file.as_raw_handle(), &expected)
                    .expect("grandchild file must have only the inherited owner ACE");
                fs.close(grandchild_file)
                    .expect("grandchild file must close");
            }

            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn owner_only_file_dacl_round_trips_through_preservation() {
            let (directory, _) = test_directory("owner-file-preservation");
            {
                let fs = RealFs;
                let root = fs.open_root(&directory).expect("root directory must open");
                let destination = fs
                    .create_file_at(&root, "destination.bin", true)
                    .expect("owner-only destination must be created");
                fs.close(destination)
                    .expect("owner-only destination must close");
                let metadata = fs
                    .read_replace_metadata(&root, "destination.bin")
                    .expect("destination DACL must be readable")
                    .expect("destination must exist");
                let mut staged = fs
                    .create_file_at(&root, "staged.bin", true)
                    .expect("owner-only staged file must be created");

                fs.apply_replace_metadata(&mut staged, &metadata)
                    .expect("captured destination DACL must apply");
                fs.verify_replace_metadata(&mut staged, &metadata)
                    .expect("applied destination DACL must verify exactly");
                fs.close(staged).expect("staged file must close");
            }

            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn followed_directory_reparse_is_pinned_and_reject_mode_is_safe() {
            let (directory, moved) = test_directory("reparse");
            let target_a = directory.join("target-a");
            let target_b = directory.join("target-b");
            let link = directory.join("link");
            std::fs::create_dir(&target_a).expect("first target must be created");
            std::fs::create_dir(&target_b).expect("second target must be created");
            if let Err(error) = std::os::windows::fs::symlink_dir(&target_a, &link) {
                if crate::windows_symlink_unavailable(&error) {
                    let _ = std::fs::remove_dir_all(&directory);
                    return;
                }
                panic!("directory symlink creation failed unexpectedly: {error}");
            }

            {
                let fs = RealFs;
                let root = fs.open_root(&directory).expect("root directory must open");
                let rejected = match fs.open_dir_at(&root, "link", false) {
                    Ok(_) => panic!("no-follow open must reject the reparse point"),
                    Err(error) => error,
                };
                assert_eq!(rejected.kind(), io::ErrorKind::InvalidInput);

                let followed = fs
                    .open_dir_at(&root, "link", true)
                    .expect("follow open must preserve current behavior");
                std::fs::remove_dir(&link).expect("old directory symlink must be removed");
                std::os::windows::fs::symlink_dir(&target_b, &link)
                    .expect("replacement directory symlink must be created");

                let staged = fs
                    .create_file_at(&followed, "pinned.tmp", true)
                    .expect("file must be created through the pinned target");
                fs.close(staged).expect("file handle must close");
            }

            assert!(target_a.join("pinned.tmp").exists());
            assert!(!target_b.join("pinned.tmp").exists());
            std::fs::rename(&directory, &moved).expect("test tree must remain removable");
            let _ = std::fs::remove_dir_all(&moved);
        }

        #[test]
        fn identity_observation_treats_a_leaf_directory_as_occupied() {
            let (directory, _) = test_directory("observe-directory");
            std::fs::create_dir(directory.join("occupied"))
                .expect("occupied leaf directory must be created");
            {
                let fs = RealFs;
                let root = fs.open_root(&directory).expect("root directory must open");
                assert!(
                    fs.observe_file_identity_at(&root, "occupied")
                        .expect("directory identity observation must succeed")
                        .is_some(),
                    "a create preflight must classify the directory as occupied"
                );
            }
            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn identity_observation_opens_a_leaf_reparse_without_following() {
            let (directory, _) = test_directory("observe-reparse");
            let target = directory.join("target.bin");
            let link = directory.join("occupied");
            std::fs::write(&target, b"target").expect("target must be created");
            if let Err(error) = std::os::windows::fs::symlink_file(&target, &link) {
                if crate::windows_symlink_unavailable(&error) {
                    let _ = std::fs::remove_dir_all(&directory);
                    return;
                }
                panic!("file symlink creation failed unexpectedly: {error}");
            }
            {
                let fs = RealFs;
                let root = fs.open_root(&directory).expect("root directory must open");
                let target_identity = fs
                    .observe_file_identity_at(&root, "target.bin")
                    .expect("target identity observation must succeed")
                    .expect("target must exist");
                let link_identity = fs
                    .observe_file_identity_at(&root, "occupied")
                    .expect("reparse identity observation must succeed")
                    .expect("reparse entry must be classified as occupied");
                assert_ne!(
                    link_identity, target_identity,
                    "no-follow observation must identify the reparse entry itself"
                );
            }
            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn destination_reparse_is_inspected_and_replaced_without_following() {
            let (directory, _) = test_directory("leaf-reparse");
            let target = directory.join("target.bin");
            let destination = directory.join("destination.bin");
            std::fs::write(&target, b"precious").expect("target bytes must be written");
            if let Err(error) = std::os::windows::fs::symlink_file(&target, &destination) {
                if crate::windows_symlink_unavailable(&error) {
                    let _ = std::fs::remove_dir_all(&directory);
                    return;
                }
                panic!("file symlink creation failed unexpectedly: {error}");
            }

            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let metadata_error = match fs.read_replace_metadata(&dir, "destination.bin") {
                    Ok(_) => panic!("metadata preservation must reject a destination reparse"),
                    Err(error) => error,
                };
                assert_eq!(metadata_error.kind(), io::ErrorKind::InvalidInput);

                let mut staged = fs
                    .create_file_at(&dir, "stage.tmp", true)
                    .expect("staged file must be created");
                fs.write_all(&mut staged, b"replacement")
                    .expect("staged write must succeed");
                fs.rename(&dir, "stage.tmp", &mut staged, "destination.bin", false)
                    .expect("rename must replace the reparse directory entry");
                fs.close(staged).expect("staged handle must close");
            }

            assert_eq!(
                std::fs::read(&target).expect("target must remain readable"),
                b"precious"
            );
            assert_eq!(
                std::fs::read(&destination).expect("replacement must be readable"),
                b"replacement"
            );
            assert!(
                !std::fs::symlink_metadata(&destination)
                    .expect("replacement metadata must be readable")
                    .file_type()
                    .is_symlink()
            );
            let _ = std::fs::remove_dir_all(&directory);
        }

        #[test]
        fn destination_directory_metadata_is_rejected_as_invalid_input() {
            let (directory, _) = test_directory("leaf-directory");
            std::fs::create_dir(directory.join("destination.bin"))
                .expect("destination directory must be created");

            {
                let fs = RealFs;
                let dir = fs
                    .open_root(&directory)
                    .expect("directory handle must open");
                let error = match fs.read_replace_metadata(&dir, "destination.bin") {
                    Ok(_) => panic!("metadata preservation must reject a destination directory"),
                    Err(error) => error,
                };
                assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
            }

            let _ = std::fs::remove_dir_all(&directory);
        }
    }
}

#[cfg(windows)]
pub use win::{WinDir, WinReplaceMetadata};
