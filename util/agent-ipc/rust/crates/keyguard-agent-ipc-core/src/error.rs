//! Stable failure codes and their safe diagnostics.

use std::{fmt, io};

/// Stable failure categories. The bindings return them as negative codes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i64)]
pub enum Error {
    /// An argument breaks the contract: a name, a length, a range, or a
    /// [`Location`](crate::Location) of another platform.
    InvalidArgument = -1,
    /// An operating-system call failed, or every bridge handle is taken.
    Io = -2,
    /// The object was closed locally, also while the call was blocked on it.
    Closed = -3,
    /// The peer closed the connection before a write completed.
    PeerClosed = -4,
    /// A bridge handle is unknown or was already consumed.
    InvalidHandle = -5,
    /// Another call already uses the same lane of this object.
    Busy = -6,
    /// The endpoint is taken or unsafe, or the peer cannot be verified.
    Permission = -7,
    /// A bug, a contained panic, or a listener that can no longer accept.
    Internal = -8,
}

impl Error {
    /// The negative code the bindings return.
    #[must_use]
    pub const fn code(self) -> i64 {
        self as i64
    }
}

/// A failure category with fixed labels and an optional OS error code.
///
/// It never holds a path, an endpoint name or a payload, so its [`Display`](fmt::Display)
/// form, `<operation>: <reason>[ (os N)]`, is safe to log.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Failure {
    kind: Error,
    operation: &'static str,
    reason: &'static str,
    os_code: Option<i32>,
}

impl Failure {
    /// `operation` names the transport call (`listen`, `accept`, `read`, …); `reason` is a
    /// fixed snake_case label: a condition or the system call that failed.
    pub(crate) const fn new(kind: Error, operation: &'static str, reason: &'static str) -> Self {
        Self {
            kind,
            operation,
            reason,
            os_code: None,
        }
    }

    pub(crate) const fn with_os_code(mut self, os_code: i32) -> Self {
        self.os_code = Some(os_code);
        self
    }

    /// Takes the OS code of the calling thread's last error (`errno` or `GetLastError`).
    /// Read it right after the failed call, before anything else can replace it.
    pub(crate) fn last_os_error(
        kind: Error,
        operation: &'static str,
        reason: &'static str,
    ) -> Self {
        let failure = Self::new(kind, operation, reason);
        match io::Error::last_os_error().raw_os_error() {
            Some(os_code) => failure.with_os_code(os_code),
            None => failure,
        }
    }

    /// The stable category.
    #[must_use]
    pub fn kind(self) -> Error {
        self.kind
    }

    /// The transport call that failed.
    #[must_use]
    pub fn operation(self) -> &'static str {
        self.operation
    }

    /// Why it failed: a fixed label.
    #[must_use]
    pub fn reason(self) -> &'static str {
        self.reason
    }

    /// The OS error code, when the failure came from a system call.
    #[must_use]
    pub fn os_code(self) -> Option<i32> {
        self.os_code
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.operation, self.reason)?;
        if let Some(os_code) = self.os_code {
            write!(formatter, " (os {os_code})")?;
        }
        Ok(())
    }
}

impl std::error::Error for Failure {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_the_abi_values() {
        let codes = [
            (Error::InvalidArgument, -1),
            (Error::Io, -2),
            (Error::Closed, -3),
            (Error::PeerClosed, -4),
            (Error::InvalidHandle, -5),
            (Error::Busy, -6),
            (Error::Permission, -7),
            (Error::Internal, -8),
        ];
        for (error, code) in codes {
            assert_eq!(error.code(), code);
        }
    }

    #[test]
    fn display_is_operation_reason_and_os_code() {
        let failure = Failure::new(Error::Io, "accept", "accept4");
        assert_eq!(failure.to_string(), "accept: accept4");
        assert_eq!(
            failure.with_os_code(24).to_string(),
            "accept: accept4 (os 24)"
        );
        assert_eq!(failure.with_os_code(24).kind(), Error::Io);
        assert_eq!(failure.with_os_code(24).os_code(), Some(24));
    }
}
