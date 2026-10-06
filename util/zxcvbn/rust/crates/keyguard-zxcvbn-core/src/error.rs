//! Stable, project-owned failure taxonomy shared by every native bridge.
//!
//! [`FailureKind`] and [`ErrorDomain`] come from `keyguard-ffi`. Password
//! estimation is a pure computation, so the only failures it can report come
//! from the ABI bridge itself.

pub use keyguard_ffi::{ErrorDomain, FailureKind};

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
