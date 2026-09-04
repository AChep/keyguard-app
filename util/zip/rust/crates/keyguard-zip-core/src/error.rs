//! Stable, project-owned failure taxonomy, numbered like `util/io`'s so the
//! Kotlin decoders stay interchangeable. A `zip` crate error is either an
//! [`io::Error`], classified like any filesystem failure, or a structural
//! error mapped to a bridge code.
//!
//! Every failure is a packed `i64`:
//!
//! | bits    | content                       |
//! |---------|-------------------------------|
//! | 0..=7   | [`Operation`]                 |
//! | 8..=15  | [`FailureKind`]               |
//! | 16..=23 | [`ErrorDomain`]               |
//! | 24..=55 | raw code (`u32`)              |
//! | 56..=62 | reserved, zero                |
//! | 63      | failure marker, always set    |
//!
//! The reserved bits keep `-1` unrepresentable as a failure.

use std::io;

use zip::result::ZipError;

/// Raw code of [`BridgeError::InvalidArgument`].
pub const BRIDGE_ERROR_INVALID_ARGUMENT: u32 = 1;
/// Raw code of [`BridgeError::Panic`].
pub const BRIDGE_ERROR_PANIC: u32 = 2;
/// Raw code of [`BridgeError::Internal`].
pub const BRIDGE_ERROR_INTERNAL: u32 = 3;
/// Raw code of [`BridgeError::InvalidHandle`].
pub const BRIDGE_ERROR_INVALID_HANDLE: u32 = 4;
/// Raw code of [`BridgeError::InvalidState`].
pub const BRIDGE_ERROR_INVALID_STATE: u32 = 5;
/// Raw code of [`BridgeError::NameTooLong`].
pub const BRIDGE_ERROR_NAME_TOO_LONG: u32 = 6;
/// Raw code of [`BridgeError::Archive`].
pub const BRIDGE_ERROR_ARCHIVE: u32 = 7;
/// Raw code of [`BridgeError::WrongPassword`].
pub const BRIDGE_ERROR_WRONG_PASSWORD: u32 = 8;
/// Raw code of [`BridgeError::UnsupportedEntry`].
pub const BRIDGE_ERROR_UNSUPPORTED_ENTRY: u32 = 9;
/// Raw code of [`BridgeError::BufferTooSmall`].
pub const BRIDGE_ERROR_BUFFER_TOO_SMALL: u32 = 10;

const FAILURE_MARKER: u64 = 1 << 63;
const KIND_SHIFT: u32 = 8;
const DOMAIN_SHIFT: u32 = 16;
const RAW_CODE_SHIFT: u32 = 24;
const OPERATION_MASK: u64 = 0xff;

/// Stable failure classification independent of [`io::ErrorKind`].
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[allow(missing_docs)]
pub enum FailureKind {
    None = 0,
    PermissionDenied = 1,
    ReadOnlyFilesystem = 2,
    NotFound = 3,
    AlreadyExists = 4,
    StorageFull = 5,
    QuotaExceeded = 6,
    ResourceBusy = 7,
    InvalidInput = 8,
    Interrupted = 9,
    Unsupported = 10,
    /// No more specific stable classification applies.
    Other = 11,
    /// The native bridge failed internally.
    Internal = 12,
}

impl FailureKind {
    /// Classifies an [`io::ErrorKind`] into the stable taxonomy.
    #[must_use]
    pub fn from_io_error_kind(kind: io::ErrorKind) -> Self {
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
    /// The raw code is defined by the Keyguard bridge.
    Bridge = 3,
}

/// Protocol step that produced a failure, so Kotlin can name it in a message
/// without the native layer disclosing any path or content.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[allow(missing_docs)]
pub enum Operation {
    /// The ABI adapter failed before reaching the archive.
    Bridge = 0,
    Open = 1,
    BeginEntry = 2,
    Write = 3,
    EndEntry = 4,
    Finish = 5,
    Abort = 6,
    ReaderOpen = 7,
    NextEntry = 8,
    Read = 9,
    Close = 10,
}

impl TryFrom<u8> for Operation {
    type Error = ();

    fn try_from(value: u8) -> Result<Self, <Self as TryFrom<u8>>::Error> {
        Ok(match value {
            0 => Self::Bridge,
            1 => Self::Open,
            2 => Self::BeginEntry,
            3 => Self::Write,
            4 => Self::EndEntry,
            5 => Self::Finish,
            6 => Self::Abort,
            7 => Self::ReaderOpen,
            8 => Self::NextEntry,
            9 => Self::Read,
            10 => Self::Close,
            _ => return Err(()),
        })
    }
}

/// A contained failure of the archive bridge; a stable code, never a message.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BridgeError {
    /// An argument violated the ABI contract.
    InvalidArgument,
    /// A panic was contained at an ABI boundary.
    Panic,
    /// An ABI adapter failed internally.
    Internal,
    /// The handle is unknown or was already consumed.
    InvalidHandle,
    /// The call is not allowed in the current entry state.
    InvalidState,
    /// An entry name exceeded [`crate::MAX_ENTRY_NAME_BYTES`].
    NameTooLong,
    /// The archive reported a structural error.
    Archive,
    /// An encrypted entry did not decrypt, or no password was given.
    WrongPassword,
    /// The entry uses a method the reader cannot decode.
    UnsupportedEntry,
    /// The caller's output buffer is too small.
    BufferTooSmall,
}

impl BridgeError {
    /// Returns the operation, kind, error domain, and raw code.
    #[must_use]
    pub const fn wire_parts(self) -> (Operation, FailureKind, ErrorDomain, u32) {
        let (kind, raw_code) = match self {
            Self::InvalidArgument => (FailureKind::InvalidInput, BRIDGE_ERROR_INVALID_ARGUMENT),
            Self::Panic => (FailureKind::Internal, BRIDGE_ERROR_PANIC),
            Self::Internal => (FailureKind::Internal, BRIDGE_ERROR_INTERNAL),
            Self::InvalidHandle => (FailureKind::InvalidInput, BRIDGE_ERROR_INVALID_HANDLE),
            Self::InvalidState => (FailureKind::InvalidInput, BRIDGE_ERROR_INVALID_STATE),
            Self::NameTooLong => (FailureKind::InvalidInput, BRIDGE_ERROR_NAME_TOO_LONG),
            Self::Archive => (FailureKind::Other, BRIDGE_ERROR_ARCHIVE),
            Self::WrongPassword => (FailureKind::InvalidInput, BRIDGE_ERROR_WRONG_PASSWORD),
            Self::UnsupportedEntry => (FailureKind::Unsupported, BRIDGE_ERROR_UNSUPPORTED_ENTRY),
            Self::BufferTooSmall => (FailureKind::InvalidInput, BRIDGE_ERROR_BUFFER_TOO_SMALL),
        };
        (Operation::Bridge, kind, ErrorDomain::Bridge, raw_code)
    }
}

/// Packs a failure into the negative scalar representation.
#[must_use]
pub const fn pack_failure(
    operation: Operation,
    kind: FailureKind,
    domain: ErrorDomain,
    raw_code: u32,
) -> i64 {
    (FAILURE_MARKER
        | (operation as u64 & OPERATION_MASK)
        | ((kind as u64) << KIND_SHIFT)
        | ((domain as u64) << DOMAIN_SHIFT)
        | ((raw_code as u64) << RAW_CODE_SHIFT)) as i64
}

/// Packs a bridge failure.
#[must_use]
pub const fn pack_bridge_error(error: BridgeError) -> i64 {
    let (operation, kind, domain, raw_code) = error.wire_parts();
    pack_failure(operation, kind, domain, raw_code)
}

/// Packs [`BridgeError::InvalidArgument`].
#[must_use]
pub const fn pack_bridge_invalid_argument() -> i64 {
    pack_bridge_error(BridgeError::InvalidArgument)
}

/// Packs [`BridgeError::Panic`].
#[must_use]
pub const fn pack_bridge_panic() -> i64 {
    pack_bridge_error(BridgeError::Panic)
}

/// Packs [`BridgeError::Internal`].
#[must_use]
pub const fn pack_bridge_internal() -> i64 {
    pack_bridge_error(BridgeError::Internal)
}

/// Packs [`BridgeError::InvalidHandle`].
#[must_use]
pub const fn pack_bridge_invalid_handle() -> i64 {
    pack_bridge_error(BridgeError::InvalidHandle)
}

/// Packs a [`BridgeError::Archive`] that keeps `operation` and `kind`. The
/// reader uses it to tell a structural failure while listing from an
/// integrity failure while reading.
#[must_use]
pub const fn pack_archive_error(operation: Operation, kind: FailureKind) -> i64 {
    pack_failure(operation, kind, ErrorDomain::Bridge, BRIDGE_ERROR_ARCHIVE)
}

/// Packs an I/O failure. Without an `errno` the domain is
/// [`ErrorDomain::None`] and the code zero.
#[must_use]
pub fn pack_io_error(operation: Operation, error: &io::Error) -> i64 {
    let kind = FailureKind::from_io_error_kind(error.kind());
    match error.raw_os_error() {
        Some(code) => pack_failure(operation, kind, ErrorDomain::PosixErrno, code as u32),
        None => pack_failure(operation, kind, ErrorDomain::None, 0),
    }
}

/// Packs a `zip` crate failure: I/O errors through [`pack_io_error`], every
/// other variant as [`BridgeError::Archive`].
#[must_use]
pub fn pack_zip_error(operation: Operation, error: &ZipError) -> i64 {
    match error {
        ZipError::Io(error) => pack_io_error(operation, error),
        _ => pack_bridge_error(BridgeError::Archive),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Golden wire vectors mirrored by the Kotlin decoder tests; changing any
    /// value is an ABI break.
    mod golden {
        pub const BRIDGE_INVALID_ARGUMENT: i64 = 0x8000_0000_0103_0800_u64 as i64;
        pub const BRIDGE_PANIC: i64 = 0x8000_0000_0203_0C00_u64 as i64;
        pub const BRIDGE_INTERNAL: i64 = 0x8000_0000_0303_0C00_u64 as i64;
        pub const BRIDGE_INVALID_HANDLE: i64 = 0x8000_0000_0403_0800_u64 as i64;
        pub const BRIDGE_INVALID_STATE: i64 = 0x8000_0000_0503_0800_u64 as i64;
        pub const BRIDGE_NAME_TOO_LONG: i64 = 0x8000_0000_0603_0800_u64 as i64;
        pub const BRIDGE_ARCHIVE: i64 = 0x8000_0000_0703_0B00_u64 as i64;
        pub const BRIDGE_WRONG_PASSWORD: i64 = 0x8000_0000_0803_0800_u64 as i64;
        pub const BRIDGE_UNSUPPORTED_ENTRY: i64 = 0x8000_0000_0903_0A00_u64 as i64;
        pub const BRIDGE_BUFFER_TOO_SMALL: i64 = 0x8000_0000_0A03_0800_u64 as i64;
        pub const NEXT_ENTRY_ARCHIVE: i64 = 0x8000_0000_0703_0B08_u64 as i64;
        pub const READ_ARCHIVE: i64 = 0x8000_0000_0703_0809_u64 as i64;
    }

    #[test]
    fn packed_bridge_failures_match_the_golden_vectors() {
        assert_eq!(
            pack_bridge_error(BridgeError::InvalidArgument),
            golden::BRIDGE_INVALID_ARGUMENT
        );
        assert_eq!(pack_bridge_error(BridgeError::Panic), golden::BRIDGE_PANIC);
        assert_eq!(
            pack_bridge_error(BridgeError::Internal),
            golden::BRIDGE_INTERNAL
        );
        assert_eq!(
            pack_bridge_error(BridgeError::InvalidHandle),
            golden::BRIDGE_INVALID_HANDLE
        );
        assert_eq!(
            pack_bridge_error(BridgeError::InvalidState),
            golden::BRIDGE_INVALID_STATE
        );
        assert_eq!(
            pack_bridge_error(BridgeError::NameTooLong),
            golden::BRIDGE_NAME_TOO_LONG
        );
        assert_eq!(
            pack_bridge_error(BridgeError::Archive),
            golden::BRIDGE_ARCHIVE
        );
        assert_eq!(
            pack_bridge_error(BridgeError::WrongPassword),
            golden::BRIDGE_WRONG_PASSWORD
        );
        assert_eq!(
            pack_bridge_error(BridgeError::UnsupportedEntry),
            golden::BRIDGE_UNSUPPORTED_ENTRY
        );
        assert_eq!(
            pack_bridge_error(BridgeError::BufferTooSmall),
            golden::BRIDGE_BUFFER_TOO_SMALL
        );
    }

    #[test]
    fn packed_archive_failures_keep_their_operation_and_kind() {
        assert_eq!(
            pack_archive_error(Operation::NextEntry, FailureKind::Other),
            golden::NEXT_ENTRY_ARCHIVE
        );
        assert_eq!(
            pack_archive_error(Operation::Read, FailureKind::InvalidInput),
            golden::READ_ARCHIVE
        );
    }

    #[test]
    fn every_packed_failure_is_negative_and_never_the_reserved_marker() {
        for packed in [
            pack_bridge_invalid_argument(),
            pack_bridge_panic(),
            pack_bridge_internal(),
            pack_bridge_invalid_handle(),
            pack_bridge_error(BridgeError::InvalidState),
            pack_bridge_error(BridgeError::NameTooLong),
            pack_bridge_error(BridgeError::Archive),
            pack_bridge_error(BridgeError::WrongPassword),
            pack_bridge_error(BridgeError::UnsupportedEntry),
            pack_bridge_error(BridgeError::BufferTooSmall),
            pack_archive_error(Operation::NextEntry, FailureKind::Other),
            pack_archive_error(Operation::Read, FailureKind::InvalidInput),
            pack_io_error(
                Operation::Write,
                &io::Error::from_raw_os_error(libc_enospc()),
            ),
        ] {
            assert!(packed < 0, "{packed:#x} must be negative");
            assert_ne!(packed, -1, "{packed:#x} must not collide with -1");
            assert_eq!(
                packed as u64 >> 56 & 0x7f,
                0,
                "{packed:#x} must keep the reserved bits clear"
            );
        }
    }

    /// `ENOSPC` on every supported platform.
    const fn libc_enospc() -> i32 {
        28
    }

    #[test]
    fn an_io_error_keeps_its_errno_in_the_posix_domain() {
        let packed = pack_io_error(
            Operation::Write,
            &io::Error::from_raw_os_error(libc_enospc()),
        );
        assert_eq!(
            packed,
            pack_failure(
                Operation::Write,
                FailureKind::StorageFull,
                ErrorDomain::PosixErrno,
                libc_enospc() as u32,
            )
        );
    }

    #[test]
    fn an_io_error_without_an_errno_travels_without_a_raw_code() {
        let error = io::Error::new(io::ErrorKind::PermissionDenied, "denied");
        let packed = pack_io_error(Operation::Open, &error);
        assert_eq!(
            packed,
            pack_failure(
                Operation::Open,
                FailureKind::PermissionDenied,
                ErrorDomain::None,
                0,
            )
        );
    }

    #[test]
    fn a_zip_io_error_maps_through_the_io_path_and_others_to_archive() {
        let error = ZipError::Io(io::Error::from_raw_os_error(libc_enospc()));
        assert_eq!(
            pack_zip_error(Operation::Write, &error),
            pack_io_error(
                Operation::Write,
                &io::Error::from_raw_os_error(libc_enospc())
            )
        );
        assert_eq!(
            pack_zip_error(Operation::BeginEntry, &ZipError::InvalidArchive("".into())),
            pack_bridge_error(BridgeError::Archive)
        );
    }

    #[test]
    fn the_operation_round_trips_through_its_wire_byte() {
        for operation in [
            Operation::Bridge,
            Operation::Open,
            Operation::BeginEntry,
            Operation::Write,
            Operation::EndEntry,
            Operation::Finish,
            Operation::Abort,
            Operation::ReaderOpen,
            Operation::NextEntry,
            Operation::Read,
            Operation::Close,
        ] {
            assert_eq!(Operation::try_from(operation as u8), Ok(operation));
        }
        assert_eq!(Operation::try_from(11), Err(()));
    }
}
