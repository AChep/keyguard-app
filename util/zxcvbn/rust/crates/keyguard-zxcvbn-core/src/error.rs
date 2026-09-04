//! Stable, project-owned failure taxonomy shared by every native bridge.
//!
//! The taxonomy is a subset of `util/io`'s: password estimation is a pure
//! computation, so the only failures it can report come from the ABI bridge
//! itself. The wire numbering is nevertheless identical so the Kotlin
//! decoders stay interchangeable.

/// Stable failure classification independent of any platform error code.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FailureKind {
    /// An input to the operation was invalid.
    InvalidInput = 8,
    /// The native bridge failed internally.
    Internal = 12,
}

/// Stable namespace of a raw native error code.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ErrorDomain {
    /// The raw code is defined by the Keyguard bridge.
    Bridge = 3,
}

/// Protocol step that produced a failure.
///
/// Estimation has a single step, the ABI adapter itself, but the field stays
/// on the wire so the packed scalar keeps `util/io`'s layout.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Operation {
    /// A native ABI adapter failed before reaching the estimator.
    Bridge = 0,
}

/// A contained failure of the estimation bridge.
///
/// The variants carry no message, password, or user input: only a stable code
/// the Kotlin side renders.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BridgeError {
    /// An argument violated the ABI contract.
    InvalidArgument,
    /// A panic was contained at an ABI boundary.
    Panic,
    /// An ABI adapter failed internally.
    Internal,
    /// An input exceeded the bridge's accepted size.
    InputTooLong,
}

impl BridgeError {
    /// Returns the stable operation, kind, error domain, and raw code.
    #[must_use]
    pub const fn wire_parts(self) -> (Operation, FailureKind, ErrorDomain, u32) {
        let (kind, raw_code) = match self {
            Self::InvalidArgument => (
                FailureKind::InvalidInput,
                crate::BRIDGE_ERROR_INVALID_ARGUMENT,
            ),
            Self::Panic => (FailureKind::Internal, crate::BRIDGE_ERROR_PANIC),
            Self::Internal => (FailureKind::Internal, crate::BRIDGE_ERROR_INTERNAL),
            Self::InputTooLong => (
                FailureKind::InvalidInput,
                crate::BRIDGE_ERROR_INPUT_TOO_LONG,
            ),
        };
        (Operation::Bridge, kind, ErrorDomain::Bridge, raw_code)
    }
}
