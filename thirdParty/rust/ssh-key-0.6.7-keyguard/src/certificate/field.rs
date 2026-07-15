//! Certificate fields.

use crate::Error;
use core::fmt;

/// Certificate fields.
///
/// This type is primarily used by the certificate builder for reporting
/// errors in certificates.
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Field {
    /// Subject public key
    PublicKey,

    /// Nonce
    Nonce,

    /// Serial number
    Serial,

    /// Certificate type: user or host
    Type,

    /// Key ID
    KeyId,

    /// Valid principals
    ValidPrincipals,

    /// Valid after (Unix time)
    ValidAfter,

    /// Valid before (Unix time)
    ValidBefore,

    /// Critical options
    CriticalOptions,

    /// Extensions
    Extensions,

    /// Signature key (i.e. CA key)
    SignatureKey,

    /// Signature
    Signature,

    /// Comment
    Comment,
}

impl Field {
    /// Get the field name as a string
    pub fn as_str(self) -> &'static str {
        match self {
            Self::PublicKey => "public key",
            Self::Nonce => "nonce",
            Self::Serial => "serial",
            Self::Type => "type",
            Self::KeyId => "key id",
            Self::ValidPrincipals => "valid principals",
            Self::ValidAfter => "valid after",
            Self::ValidBefore => "valid before",
            Self::CriticalOptions => "critical options",
            Self::Extensions => "extensions",
            Self::SignatureKey => "signature key",
            Self::Signature => "signature",
            Self::Comment => "comment",
        }
    }

    /// Get an [`Error`] that this field is invalid.
    pub fn invalid_error(self) -> Error {
        Error::CertificateFieldInvalid(self)
    }
}

impl AsRef<str> for Field {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

impl fmt::Display for Field {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}
