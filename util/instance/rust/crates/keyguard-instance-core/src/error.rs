use std::{fmt, io};

/// Stable failure categories returned by every binding.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i64)]
pub enum Error {
    /// Invalid identity, path, duration, or bridge argument.
    InvalidArgument = -1,
    /// An operating-system I/O operation failed.
    Io = -2,
    /// Ownership or activation could not complete within the deadline.
    Timeout = -3,
    /// The endpoint or peer uses an incompatible or malformed protocol.
    Protocol = -4,
    /// A handle is unknown or already closed.
    InvalidHandle = -5,
    /// The primary cannot currently accept activation.
    Unavailable = -6,
    /// Coordination permissions or peer credentials are invalid.
    Permission = -7,
    /// A worker or binding failed internally.
    Internal = -8,
}

/// A native failure retaining diagnostics without paths, credentials, or OS error messages.
///
/// The C/JNI ABI still returns only the stable numeric [Error] category. Native callers can
/// inspect this value; the bridge exposes its safe diagnostic fields per calling thread
/// and also writes them to standard error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Failure {
    kind: Error,
    operation: Option<&'static str>,
    reason: Option<&'static str>,
    io_kind: Option<io::ErrorKind>,
    os_code: Option<i32>,
}

impl Failure {
    /// The stable category used by C and JNI callers.
    pub fn kind(self) -> Error {
        self.kind
    }

    /// The innermost operation, identified by a fixed implementation label.
    pub fn operation(self) -> Option<&'static str> {
        self.operation
    }

    /// The operating system's numeric error code, when available.
    pub fn os_code(self) -> Option<i32> {
        self.os_code
    }

    /// The original I/O category, when the failure originated in an OS operation.
    pub fn io_kind(self) -> Option<io::ErrorKind> {
        self.io_kind
    }

    pub(crate) fn context(mut self, operation: &'static str) -> Self {
        // Outer layers must not obscure the operation that actually failed.
        self.operation.get_or_insert(operation);
        self
    }

    #[cfg(windows)]
    pub(crate) fn reason(mut self, reason: &'static str) -> Self {
        self.reason.get_or_insert(reason);
        self
    }

    pub(crate) fn into_timeout(mut self) -> Self {
        // Keep the last failed contact's diagnostics after exhausting the overall deadline.
        self.kind = Error::Timeout;
        self
    }
}

impl From<Error> for Failure {
    fn from(kind: Error) -> Self {
        Self {
            kind,
            operation: None,
            reason: None,
            io_kind: None,
            os_code: None,
        }
    }
}

impl From<io::Error> for Failure {
    fn from(error: io::Error) -> Self {
        let io_kind = error.kind();
        let kind = match io_kind {
            io::ErrorKind::PermissionDenied => Error::Permission,
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => Error::Timeout,
            io::ErrorKind::InvalidInput => Error::InvalidArgument,
            _ => Error::Io,
        };
        Self {
            kind,
            operation: None,
            reason: None,
            io_kind: Some(io_kind),
            os_code: error.raw_os_error(),
        }
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "kind={:?}", self.kind)?;
        if let Some(operation) = self.operation {
            write!(formatter, " operation={operation}")?;
        }
        if let Some(reason) = self.reason {
            write!(formatter, " reason={reason}")?;
        }
        if let Some(kind) = self.io_kind {
            write!(formatter, " io_kind={kind:?}")?;
        }
        if let Some(code) = self.os_code {
            write!(formatter, " os_code={code}")?;
        }
        Ok(())
    }
}

impl std::error::Error for Failure {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostics_retain_os_code_and_innermost_operation() {
        let original = io::Error::from_raw_os_error(2);
        let failure = Failure::from(original)
            .context("read_endpoint")
            .context("acquire");
        assert_eq!(failure.os_code(), Some(2));
        assert_eq!(failure.io_kind(), Some(io::ErrorKind::NotFound));
        assert_eq!(failure.operation(), Some("read_endpoint"));
        let timeout = failure.into_timeout();
        assert_eq!(timeout.kind(), Error::Timeout);
        assert_eq!(timeout.os_code(), failure.os_code());
        assert_eq!(timeout.operation(), failure.operation());
    }

    #[test]
    fn diagnostics_never_retain_custom_io_messages() {
        let failure = Failure::from(io::Error::other("/private/user/path token=secret"))
            .context("read_endpoint");
        for diagnostic in [failure.to_string(), format!("{failure:?}")] {
            assert!(!diagnostic.contains("/private/user/path"));
            assert!(!diagnostic.contains("secret"));
            assert!(diagnostic.contains("read_endpoint"));
        }
    }
}
