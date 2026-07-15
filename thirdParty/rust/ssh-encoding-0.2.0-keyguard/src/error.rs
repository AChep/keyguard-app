//! Error types.

use crate::LabelError;
use core::fmt;

/// Result type with `ssh-encoding` crate's [`Error`] as the error type.
pub type Result<T> = core::result::Result<T, Error>;

/// Error type.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Error {
    /// Base64-related errors.
    #[cfg(feature = "base64")]
    Base64(base64::Error),

    /// Character encoding-related errors.
    CharacterEncoding,

    /// Invalid label.
    Label(LabelError),

    /// Invalid length.
    Length,

    /// Overflow errors.
    Overflow,

    /// PEM encoding errors.
    #[cfg(feature = "pem")]
    Pem(pem::Error),

    /// Unexpected trailing data at end of message.
    TrailingData {
        /// Number of bytes of remaining data at end of message.
        remaining: usize,
    },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            #[cfg(feature = "base64")]
            Error::Base64(err) => write!(f, "Base64 encoding error: {err}"),
            Error::CharacterEncoding => write!(f, "character encoding invalid"),
            Error::Label(err) => write!(f, "{}", err),
            Error::Length => write!(f, "length invalid"),
            Error::Overflow => write!(f, "internal overflow error"),
            #[cfg(feature = "pem")]
            Error::Pem(err) => write!(f, "{err}"),
            Error::TrailingData { remaining } => write!(
                f,
                "unexpected trailing data at end of message ({remaining} bytes)",
            ),
        }
    }
}

impl From<LabelError> for Error {
    fn from(err: LabelError) -> Error {
        Error::Label(err)
    }
}

impl From<core::num::TryFromIntError> for Error {
    fn from(_: core::num::TryFromIntError) -> Error {
        Error::Overflow
    }
}

impl From<core::str::Utf8Error> for Error {
    fn from(_: core::str::Utf8Error) -> Error {
        Error::CharacterEncoding
    }
}

#[cfg(feature = "alloc")]
impl From<alloc::string::FromUtf8Error> for Error {
    fn from(_: alloc::string::FromUtf8Error) -> Error {
        Error::CharacterEncoding
    }
}

#[cfg(feature = "base64")]
impl From<base64::Error> for Error {
    fn from(err: base64::Error) -> Error {
        Error::Base64(err)
    }
}

#[cfg(feature = "base64")]
impl From<base64::InvalidLengthError> for Error {
    fn from(_: base64::InvalidLengthError) -> Error {
        Error::Length
    }
}

#[cfg(feature = "pem")]
impl From<pem::Error> for Error {
    fn from(err: pem::Error) -> Error {
        Error::Pem(err)
    }
}

#[cfg(feature = "std")]
impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            #[cfg(feature = "base64")]
            Self::Base64(err) => Some(err),
            #[cfg(feature = "pem")]
            Self::Pem(err) => Some(err),
            _ => None,
        }
    }
}
