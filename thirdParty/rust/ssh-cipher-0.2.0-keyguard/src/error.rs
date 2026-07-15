//! Error types.

use crate::Cipher;
use core::fmt;

/// Result type with `ssh-cipher` crate's [`Error`] as the error type.
pub type Result<T> = core::result::Result<T, Error>;

/// Error type.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Error {
    /// Cryptographic errors.
    Crypto,

    /// Invalid key size.
    KeySize,

    /// Invalid initialization vector / nonce size.
    IvSize,

    /// Invalid AEAD tag size.
    TagSize,

    /// Unsupported cipher.
    UnsupportedCipher(Cipher),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Crypto => write!(f, "cryptographic error"),
            Error::KeySize => write!(f, "invalid key size"),
            Error::IvSize => write!(f, "invalid initialization vector size"),
            Error::TagSize => write!(f, "invalid AEAD tag size"),
            Error::UnsupportedCipher(cipher) => write!(f, "unsupported cipher: {}", cipher),
        }
    }
}

#[cfg(feature = "std")]
impl std::error::Error for Error {}
