//! The packed `i64` failure word returned by the scalar bridges.
//!
//! | bits    | content                                    |
//! |---------|--------------------------------------------|
//! | 0..=7   | bridge-specific operation                  |
//! | 8..=15  | [`FailureKind`]                            |
//! | 16..=23 | [`ErrorDomain`]                            |
//! | 24..=55 | raw code (`u32`)                           |
//! | 56..=62 | reserved, zero; `util/io` assigns bit 56   |
//! | 63      | failure marker, always set                 |
//!
//! The reserved bits keep `-1` unrepresentable as a failure. The Kotlin
//! decoders mirror this layout, so changing it is an ABI break.

use std::io;

/// Bit that marks a packed failure.
pub const FAILURE_MARKER: u64 = 1 << 63;
/// Width of the operation field at bits 0..=7.
pub const OPERATION_MASK: u64 = 0xff;
/// Offset of the [`FailureKind`] field.
pub const KIND_SHIFT: u32 = 8;
/// Offset of the [`ErrorDomain`] field.
pub const DOMAIN_SHIFT: u32 = 16;
/// Offset of the raw code field.
pub const RAW_CODE_SHIFT: u32 = 24;

/// Operation of a failure raised by the ABI adapter itself.
pub const OPERATION_BRIDGE: u8 = 0;

/// Bridge code of an argument that violated the ABI contract.
pub const BRIDGE_ERROR_INVALID_ARGUMENT: u32 = 1;
/// Bridge code of a panic contained at an ABI boundary.
pub const BRIDGE_ERROR_PANIC: u32 = 2;
/// Bridge code of an internal ABI adapter failure.
pub const BRIDGE_ERROR_INTERNAL: u32 = 3;

/// [`BRIDGE_ERROR_INVALID_ARGUMENT`], packed.
pub const BRIDGE_INVALID_ARGUMENT: i64 = pack_failure(
    OPERATION_BRIDGE,
    FailureKind::InvalidInput,
    ErrorDomain::Bridge,
    BRIDGE_ERROR_INVALID_ARGUMENT,
);
/// [`BRIDGE_ERROR_PANIC`], packed.
pub const BRIDGE_PANIC: i64 = pack_failure(
    OPERATION_BRIDGE,
    FailureKind::Internal,
    ErrorDomain::Bridge,
    BRIDGE_ERROR_PANIC,
);
/// [`BRIDGE_ERROR_INTERNAL`], packed.
pub const BRIDGE_INTERNAL: i64 = pack_failure(
    OPERATION_BRIDGE,
    FailureKind::Internal,
    ErrorDomain::Bridge,
    BRIDGE_ERROR_INTERNAL,
);

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
    /// The raw code was returned by Win32 `GetLastError`.
    Win32LastError = 2,
    /// The raw code is defined by the Keyguard bridge.
    Bridge = 3,
}

/// Packs a failure into the negative scalar representation. Bits 56..=62
/// stay clear.
#[must_use]
pub const fn pack_failure(
    operation: u8,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bridge_failures_match_the_golden_vectors() {
        assert_eq!(BRIDGE_INVALID_ARGUMENT, 0x8000_0000_0103_0800_u64 as i64);
        assert_eq!(BRIDGE_PANIC, 0x8000_0000_0203_0C00_u64 as i64);
        assert_eq!(BRIDGE_INTERNAL, 0x8000_0000_0303_0C00_u64 as i64);
    }

    #[test]
    fn packed_failures_match_the_golden_vectors() {
        // Write, PermissionDenied, PosixErrno, EACCES=13.
        assert_eq!(
            pack_failure(
                3,
                FailureKind::PermissionDenied,
                ErrorDomain::PosixErrno,
                13
            ),
            0x8000_0000_0D01_0103_u64 as i64,
        );
        assert_eq!(
            pack_failure(14, FailureKind::Unsupported, ErrorDomain::None, 0),
            0x8000_0000_0000_0A0E_u64 as i64,
        );
    }

    #[test]
    fn packed_failures_are_negative_and_keep_the_reserved_bits_clear() {
        let packed = pack_failure(
            u8::MAX,
            FailureKind::DurabilityUnavailable,
            ErrorDomain::Bridge,
            u32::MAX,
        );
        assert!(packed < 0);
        assert_ne!(packed, -1);
        assert_eq!((packed as u64 >> 56) & 0x7f, 0);
    }

    #[test]
    fn io_error_kinds_map_to_the_stable_taxonomy() {
        assert_eq!(
            FailureKind::from_io_error_kind(io::ErrorKind::PermissionDenied),
            FailureKind::PermissionDenied,
        );
        assert_eq!(
            FailureKind::from_io_error_kind(io::ErrorKind::Unsupported),
            FailureKind::Unsupported,
        );
        assert_eq!(
            FailureKind::from_io_error_kind(io::ErrorKind::TimedOut),
            FailureKind::Other,
        );
    }
}
