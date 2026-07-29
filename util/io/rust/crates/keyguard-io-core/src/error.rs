//! Stable, project-owned failure taxonomy shared by every native bridge.

use std::io;

/// Stable failure classification independent of [`io::ErrorKind`]'s
/// implementation and discriminants.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FailureKind {
    /// No failure applies to the outcome.
    None = 0,
    /// The operation was denied by filesystem permissions or access policy.
    PermissionDenied = 1,
    /// The destination filesystem is read-only.
    ReadOnlyFilesystem = 2,
    /// A required filesystem object was not found.
    NotFound = 3,
    /// A filesystem object unexpectedly already exists.
    AlreadyExists = 4,
    /// The filesystem has no remaining storage capacity.
    StorageFull = 5,
    /// The caller's storage quota has been exhausted.
    QuotaExceeded = 6,
    /// The filesystem object is currently busy.
    ResourceBusy = 7,
    /// An input to the operation was invalid.
    InvalidInput = 8,
    /// The operation was interrupted.
    Interrupted = 9,
    /// The requested atomic operation is unsupported.
    Unsupported = 10,
    /// The failure has no more specific stable classification.
    Other = 11,
    /// The native bridge failed internally.
    Internal = 12,
    /// The platform could not establish the caller's requested durability.
    DurabilityUnavailable = 13,
}

impl FailureKind {
    pub(crate) fn from_io_error_kind(kind: io::ErrorKind) -> Self {
        match kind {
            io::ErrorKind::PermissionDenied => Self::PermissionDenied,
            io::ErrorKind::ReadOnlyFilesystem => Self::ReadOnlyFilesystem,
            io::ErrorKind::NotFound => Self::NotFound,
            io::ErrorKind::AlreadyExists => Self::AlreadyExists,
            io::ErrorKind::StorageFull => Self::StorageFull,
            io::ErrorKind::QuotaExceeded => Self::QuotaExceeded,
            io::ErrorKind::ResourceBusy => Self::ResourceBusy,
            io::ErrorKind::InvalidInput => Self::InvalidInput,
            io::ErrorKind::Interrupted => Self::Interrupted,
            io::ErrorKind::Unsupported => Self::Unsupported,
            _ => Self::Other,
        }
    }
}

/// Stable namespace of a raw native error code.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ErrorDomain {
    /// No raw native error applies.
    None = 0,
    /// The raw code is a POSIX `errno`.
    PosixErrno = 1,
    /// The raw code was returned by Win32 `GetLastError`.
    Win32LastError = 2,
    /// The raw code is defined by the Keyguard bridge.
    Bridge = 3,
}

/// Protocol step that produced a failure.
///
/// The step travels across the ABI so the Kotlin side can render an
/// actionable message ("fsync of the staged file failed") without the native
/// layer disclosing any path or file content.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Operation {
    /// A native ABI adapter failed before reaching the protocol.
    Bridge = 0,
    /// Opening the destination directory or resolving the destination.
    Begin = 1,
    /// Creating the staged temporary file.
    CreateStaged = 2,
    /// Writing bytes to a native file.
    Write = 3,
    /// Flushing file bytes to stable storage.
    FlushFile = 4,
    /// Reading destination metadata or applying it to the staged file.
    Metadata = 5,
    /// Publishing the staged file at the destination name.
    Rename = 6,
    /// Flushing the destination directory entry to stable storage.
    FlushDir = 7,
    /// Removing a no-longer-needed temporary artifact.
    Cleanup = 8,
    /// Reading bytes from a native file.
    Read = 9,
    /// Closing a native file.
    Close = 10,
    /// Scanning or removing orphaned temporary artifacts.
    Sweep = 11,
    /// Resolving or creating the destination's parent directory.
    PrepareParent = 12,
    /// Persisting parent-directory entries created by this operation.
    FlushParent = 13,
    /// Publishing through the exclusive hard-link fallback.
    HardLink = 14,
}

impl TryFrom<u8> for Operation {
    type Error = ();

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        Ok(match value {
            0 => Self::Bridge,
            1 => Self::Begin,
            2 => Self::CreateStaged,
            3 => Self::Write,
            4 => Self::FlushFile,
            5 => Self::Metadata,
            6 => Self::Rename,
            7 => Self::FlushDir,
            8 => Self::Cleanup,
            9 => Self::Read,
            10 => Self::Close,
            11 => Self::Sweep,
            12 => Self::PrepareParent,
            13 => Self::FlushParent,
            14 => Self::HardLink,
            _ => return Err(()),
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum FailureOrigin {
    None,
    #[cfg(unix)]
    PosixErrno(u32),
    #[cfg(windows)]
    Win32LastError(u32),
    Bridge(u32),
}

impl FailureOrigin {
    const fn wire_parts(self) -> (ErrorDomain, u32) {
        match self {
            Self::None => (ErrorDomain::None, 0),
            #[cfg(unix)]
            Self::PosixErrno(code) => (ErrorDomain::PosixErrno, code),
            #[cfg(windows)]
            Self::Win32LastError(code) => (ErrorDomain::Win32LastError, code),
            Self::Bridge(code) => (ErrorDomain::Bridge, code),
        }
    }
}

/// Structured details for a filesystem failure.
///
/// Fields and constructors are private so a failure cannot contain
/// [`FailureKind::None`] or an invalid raw-code/domain combination.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FileSystemFailure {
    kind: FailureKind,
    origin: FailureOrigin,
}

impl FileSystemFailure {
    pub(crate) const fn semantic(kind: FailureKind) -> Self {
        assert!(!matches!(kind, FailureKind::None));
        Self {
            kind,
            origin: FailureOrigin::None,
        }
    }

    /// Classifies an I/O error and retains its raw code in the current
    /// target's native error domain when one is available.
    #[must_use]
    pub fn from_io_error(error: &io::Error) -> Self {
        Self::from_io_error_with_kind(error, None)
    }

    pub(crate) fn from_io_error_with_kind(
        error: &io::Error,
        override_kind: Option<FailureKind>,
    ) -> Self {
        let kind = match override_kind {
            Some(kind) => kind,
            None => FailureKind::from_io_error_kind(error.kind()),
        };
        assert!(!matches!(kind, FailureKind::None));
        let origin = current_target_error_origin(error.raw_os_error());
        Self { kind, origin }
    }

    /// Returns a contained invalid-argument failure for an ABI boundary.
    #[must_use]
    pub const fn bridge_invalid_argument() -> Self {
        Self {
            kind: FailureKind::InvalidInput,
            origin: FailureOrigin::Bridge(crate::BRIDGE_ERROR_INVALID_ARGUMENT),
        }
    }

    /// Returns a contained internal failure for a panic at an ABI boundary.
    #[must_use]
    pub const fn bridge_panic() -> Self {
        Self {
            kind: FailureKind::Internal,
            origin: FailureOrigin::Bridge(crate::BRIDGE_ERROR_PANIC),
        }
    }

    /// Returns a contained internal failure for an ABI adapter operation.
    #[must_use]
    pub const fn bridge_internal() -> Self {
        Self {
            kind: FailureKind::Internal,
            origin: FailureOrigin::Bridge(crate::BRIDGE_ERROR_INTERNAL),
        }
    }

    /// Returns a contained not-found failure for an unknown native handle.
    #[must_use]
    pub const fn bridge_unknown_handle() -> Self {
        Self {
            kind: FailureKind::NotFound,
            origin: FailureOrigin::Bridge(crate::BRIDGE_ERROR_UNKNOWN_HANDLE),
        }
    }

    /// Returns the stable failure kind.
    #[must_use]
    pub const fn kind(self) -> FailureKind {
        self.kind
    }

    /// Returns the stable kind, error domain, and raw code for an ABI bridge.
    #[must_use]
    pub const fn wire_parts(self) -> (FailureKind, ErrorDomain, u32) {
        let (domain, raw_code) = self.origin.wire_parts();
        (self.kind, domain, raw_code)
    }
}

fn current_target_error_origin(raw_code: Option<i32>) -> FailureOrigin {
    #[cfg(unix)]
    {
        raw_code.map_or(FailureOrigin::None, |code| {
            FailureOrigin::PosixErrno(code as u32)
        })
    }
    #[cfg(windows)]
    {
        raw_code.map_or(FailureOrigin::None, |code| {
            FailureOrigin::Win32LastError(code as u32)
        })
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = raw_code;
        FailureOrigin::None
    }
}

/// A protocol failure tagged with the step that produced it.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TxnError {
    operation: Operation,
    failure: FileSystemFailure,
    cleanup_incomplete: bool,
}

impl TxnError {
    pub(crate) const fn new(operation: Operation, failure: FileSystemFailure) -> Self {
        Self {
            operation,
            failure,
            cleanup_incomplete: false,
        }
    }

    pub(crate) fn from_io_error(operation: Operation, error: &io::Error) -> Self {
        Self::new(operation, FileSystemFailure::from_io_error(error))
    }

    /// Returns a contained invalid-argument failure for an ABI boundary.
    #[must_use]
    pub const fn bridge_invalid_argument() -> Self {
        Self::new(
            Operation::Bridge,
            FileSystemFailure::bridge_invalid_argument(),
        )
    }

    /// Returns a contained internal failure for a panic at an ABI boundary.
    #[must_use]
    pub const fn bridge_panic() -> Self {
        Self::new(Operation::Bridge, FileSystemFailure::bridge_panic())
    }

    /// Returns a contained internal failure for an ABI adapter operation.
    #[must_use]
    pub const fn bridge_internal() -> Self {
        Self::new(Operation::Bridge, FileSystemFailure::bridge_internal())
    }

    /// Returns a contained not-found failure for an unknown native handle.
    #[must_use]
    pub const fn bridge_unknown_handle() -> Self {
        Self::new(
            Operation::Bridge,
            FileSystemFailure::bridge_unknown_handle(),
        )
    }

    /// Returns the protocol step that produced the failure.
    #[must_use]
    pub const fn operation(self) -> Operation {
        self.operation
    }

    /// Returns the structured failure details.
    #[must_use]
    pub const fn failure(self) -> FileSystemFailure {
        self.failure
    }

    /// Returns whether cleanup attempted after the primary failure did not
    /// complete.
    ///
    /// The primary operation and failure remain authoritative. This flag only
    /// records that a recognizable temporary artifact may also remain.
    #[must_use]
    pub const fn cleanup_incomplete(self) -> bool {
        self.cleanup_incomplete
    }

    pub(crate) const fn with_cleanup_incomplete(mut self) -> Self {
        self.cleanup_incomplete = true;
        self
    }
}
