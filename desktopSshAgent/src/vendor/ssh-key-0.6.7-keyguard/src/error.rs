//! Error types

use crate::Algorithm;
use core::fmt;

#[cfg(feature = "alloc")]
use crate::certificate;

/// Result type with `ssh-key`'s [`Error`] as the error type.
pub type Result<T> = core::result::Result<T, Error>;

/// Error type.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Error {
    /// Unknown algorithm.
    ///
    /// This is returned when an algorithm is completely unknown to this crate.
    AlgorithmUnknown,

    /// Unsupported algorithm.
    ///
    /// This is typically returned when an algorithm is recognized, but the
    /// relevant crate features to support it haven't been enabled.
    ///
    /// It may also be returned in the event an algorithm is inappropriate for
    /// a given usage pattern or context.
    AlgorithmUnsupported {
        /// Algorithm identifier.
        algorithm: Algorithm,
    },

    /// Certificate field is invalid or already set.
    #[cfg(feature = "alloc")]
    CertificateFieldInvalid(certificate::Field),

    /// Certificate validation failed.
    CertificateValidation,

    /// Cryptographic errors.
    Crypto,

    /// Cannot perform operation on decrypted private key.
    Decrypted,

    /// ECDSA key encoding errors.
    #[cfg(feature = "ecdsa")]
    Ecdsa(sec1::Error),

    /// Encoding errors.
    Encoding(encoding::Error),

    /// Cannot perform operation on encrypted private key.
    Encrypted,

    /// Other format encoding errors.
    FormatEncoding,

    /// Input/output errors.
    #[cfg(feature = "std")]
    Io(std::io::ErrorKind),

    /// Namespace invalid.
    Namespace,

    /// Public key is incorrect.
    PublicKey,

    /// Invalid timestamp (e.g. in a certificate)
    Time,

    /// Unexpected trailing data at end of message.
    TrailingData {
        /// Number of bytes of remaining data at end of message.
        remaining: usize,
    },

    /// Unsupported version.
    Version {
        /// Version number.
        number: u32,
    },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::AlgorithmUnknown => write!(f, "unknown algorithm"),
            Error::AlgorithmUnsupported { algorithm } => {
                write!(f, "unsupported algorithm: {algorithm}")
            }
            #[cfg(feature = "alloc")]
            Error::CertificateFieldInvalid(field) => {
                write!(f, "certificate field invalid: {field}")
            }
            Error::CertificateValidation => write!(f, "certificate validation failed"),
            Error::Crypto => write!(f, "cryptographic error"),
            Error::Decrypted => write!(f, "private key is already decrypted"),
            #[cfg(feature = "ecdsa")]
            Error::Ecdsa(err) => write!(f, "ECDSA encoding error: {err}"),
            Error::Encoding(err) => write!(f, "{err}"),
            Error::Encrypted => write!(f, "private key is encrypted"),
            Error::FormatEncoding => write!(f, "format encoding error"),
            #[cfg(feature = "std")]
            Error::Io(err) => write!(f, "I/O error: {}", std::io::Error::from(*err)),
            Error::Namespace => write!(f, "namespace invalid"),
            Error::PublicKey => write!(f, "public key is incorrect"),
            Error::Time => write!(f, "invalid time"),
            Error::TrailingData { remaining } => write!(
                f,
                "unexpected trailing data at end of message ({remaining} bytes)",
            ),
            Error::Version { number: version } => write!(f, "version unsupported: {version}"),
        }
    }
}

impl From<cipher::Error> for Error {
    fn from(_: cipher::Error) -> Error {
        Error::Crypto
    }
}

impl From<core::array::TryFromSliceError> for Error {
    fn from(_: core::array::TryFromSliceError) -> Error {
        Error::Encoding(encoding::Error::Length)
    }
}

impl From<core::str::Utf8Error> for Error {
    fn from(err: core::str::Utf8Error) -> Error {
        Error::Encoding(err.into())
    }
}

impl From<encoding::Error> for Error {
    fn from(err: encoding::Error) -> Error {
        Error::Encoding(err)
    }
}

impl From<encoding::LabelError> for Error {
    fn from(err: encoding::LabelError) -> Error {
        Error::Encoding(err.into())
    }
}

impl From<encoding::base64::Error> for Error {
    fn from(err: encoding::base64::Error) -> Error {
        Error::Encoding(err.into())
    }
}

impl From<encoding::pem::Error> for Error {
    fn from(err: encoding::pem::Error) -> Error {
        Error::Encoding(err.into())
    }
}

#[cfg(not(feature = "std"))]
impl From<signature::Error> for Error {
    fn from(_: signature::Error) -> Error {
        Error::Crypto
    }
}

#[cfg(feature = "std")]
impl From<signature::Error> for Error {
    fn from(err: signature::Error) -> Error {
        use std::error::Error as _;

        err.source()
            .and_then(|source| source.downcast_ref().cloned())
            .unwrap_or(Error::Crypto)
    }
}

#[cfg(not(feature = "std"))]
impl From<Error> for signature::Error {
    fn from(_: Error) -> signature::Error {
        signature::Error::new()
    }
}

#[cfg(feature = "std")]
impl From<Error> for signature::Error {
    fn from(err: Error) -> signature::Error {
        signature::Error::from_source(err)
    }
}

#[cfg(feature = "alloc")]
impl From<alloc::string::FromUtf8Error> for Error {
    fn from(err: alloc::string::FromUtf8Error) -> Error {
        Error::Encoding(err.into())
    }
}

#[cfg(feature = "ecdsa")]
impl From<sec1::Error> for Error {
    fn from(err: sec1::Error) -> Error {
        Error::Ecdsa(err)
    }
}

#[cfg(feature = "rsa")]
impl From<rsa::errors::Error> for Error {
    fn from(_: rsa::errors::Error) -> Error {
        Error::Crypto
    }
}

#[cfg(feature = "std")]
impl From<std::io::Error> for Error {
    fn from(err: std::io::Error) -> Error {
        Error::Io(err.kind())
    }
}

#[cfg(feature = "std")]
impl From<std::time::SystemTimeError> for Error {
    fn from(_: std::time::SystemTimeError) -> Error {
        Error::Time
    }
}

#[cfg(feature = "std")]
impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(err) => Some(err),
            Self::Encoding(err) => Some(err),
            _ => None,
        }
    }
}
