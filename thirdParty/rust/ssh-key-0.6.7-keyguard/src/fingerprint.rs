//! SSH public key fingerprints.

mod randomart;

use self::randomart::Randomart;
use crate::{public, Error, HashAlg, Result};
use core::{
    fmt::{self, Display},
    str::{self, FromStr},
};
use encoding::{
    base64::{Base64Unpadded, Encoding},
    Encode,
};
use sha2::{Digest, Sha256, Sha512};

/// Fingerprint encoding error message.
const FINGERPRINT_ERR_MSG: &str = "fingerprint encoding error";

#[cfg(feature = "alloc")]
use alloc::string::{String, ToString};

#[cfg(all(feature = "alloc", feature = "serde"))]
use serde::{de, ser, Deserialize, Serialize};

/// SSH public key fingerprints.
///
/// Fingerprints have an associated key fingerprint algorithm, i.e. a hash
/// function which is used to compute the fingerprint.
///
/// # Parsing/serializing fingerprint strings
///
/// The [`FromStr`] and [`Display`] impls on [`Fingerprint`] can be used to
/// parse and serialize fingerprints from the string format.
///
/// ### Example
///
/// ```text
/// SHA256:Nh0Me49Zh9fDw/VYUfq43IJmI1T+XrjiYONPND8GzaM
/// ```
///
/// # `serde` support
///
/// When the `serde` feature of this crate is enabled, this type receives impls
/// of [`Deserialize`][`serde::Deserialize`] and [`Serialize`][`serde::Serialize`].
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
#[non_exhaustive]
pub enum Fingerprint {
    /// Fingerprints computed using SHA-256.
    Sha256([u8; HashAlg::Sha256.digest_size()]),

    /// Fingerprints computed using SHA-512.
    Sha512([u8; HashAlg::Sha512.digest_size()]),
}

impl Fingerprint {
    /// Size of a SHA-512 hash encoded as Base64.
    const SHA512_BASE64_SIZE: usize = 86;

    /// Create a fingerprint of the given public key data using the provided
    /// hash algorithm.
    pub fn new(algorithm: HashAlg, public_key: &public::KeyData) -> Self {
        match algorithm {
            HashAlg::Sha256 => {
                let mut digest = Sha256::new();
                public_key.encode(&mut digest).expect(FINGERPRINT_ERR_MSG);
                Self::Sha256(digest.finalize().into())
            }
            HashAlg::Sha512 => {
                let mut digest = Sha512::new();
                public_key.encode(&mut digest).expect(FINGERPRINT_ERR_MSG);
                Self::Sha512(digest.finalize().into())
            }
        }
    }

    /// Get the hash algorithm used for this fingerprint.
    pub fn algorithm(self) -> HashAlg {
        match self {
            Self::Sha256(_) => HashAlg::Sha256,
            Self::Sha512(_) => HashAlg::Sha512,
        }
    }

    /// Get the name of the hash algorithm (upper case e.g. "SHA256").
    pub fn prefix(self) -> &'static str {
        match self.algorithm() {
            HashAlg::Sha256 => "SHA256",
            HashAlg::Sha512 => "SHA512",
        }
    }

    /// Get the bracketed hash algorithm footer for use in "randomart".
    fn footer(self) -> &'static str {
        match self.algorithm() {
            HashAlg::Sha256 => "[SHA256]",
            HashAlg::Sha512 => "[SHA512]",
        }
    }

    /// Get the raw digest output for the fingerprint as bytes.
    pub fn as_bytes(&self) -> &[u8] {
        match self {
            Self::Sha256(bytes) => bytes.as_slice(),
            Self::Sha512(bytes) => bytes.as_slice(),
        }
    }

    /// Get the SHA-256 fingerprint, if this is one.
    pub fn sha256(self) -> Option<[u8; HashAlg::Sha256.digest_size()]> {
        match self {
            Self::Sha256(fingerprint) => Some(fingerprint),
            _ => None,
        }
    }

    /// Get the SHA-512 fingerprint, if this is one.
    pub fn sha512(self) -> Option<[u8; HashAlg::Sha512.digest_size()]> {
        match self {
            Self::Sha512(fingerprint) => Some(fingerprint),
            _ => None,
        }
    }

    /// Is this fingerprint SHA-256?
    pub fn is_sha256(self) -> bool {
        matches!(self, Self::Sha256(_))
    }

    /// Is this fingerprint SHA-512?
    pub fn is_sha512(self) -> bool {
        matches!(self, Self::Sha512(_))
    }

    /// Format "randomart" for this fingerprint using the provided formatter.
    pub fn fmt_randomart(self, header: &str, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        Randomart::new(header, self).fmt(f)
    }

    /// Render "randomart" hash visualization for this fingerprint as a string.
    ///
    /// ```text
    /// +--[ED25519 256]--+
    /// |o+oO==+ o..      |
    /// |.o++Eo+o..       |
    /// |. +.oO.o . .     |
    /// | . o..B.. . .    |
    /// |  ...+ .S. o     |
    /// |  .o. . . . .    |
    /// |  o..    o       |
    /// |   B      .      |
    /// |  .o*            |
    /// +----[SHA256]-----+
    /// ```
    #[cfg(feature = "alloc")]
    pub fn to_randomart(self, header: &str) -> String {
        Randomart::new(header, self).to_string()
    }
}

impl AsRef<[u8]> for Fingerprint {
    fn as_ref(&self) -> &[u8] {
        self.as_bytes()
    }
}

impl Display for Fingerprint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let prefix = self.prefix();

        // Buffer size is the largest digest size of of any supported hash function
        let mut buf = [0u8; Self::SHA512_BASE64_SIZE];
        let base64 = Base64Unpadded::encode(self.as_bytes(), &mut buf).map_err(|_| fmt::Error)?;
        write!(f, "{prefix}:{base64}")
    }
}

impl FromStr for Fingerprint {
    type Err = Error;

    fn from_str(id: &str) -> Result<Self> {
        let (alg_str, base64) = id.split_once(':').ok_or(Error::AlgorithmUnknown)?;

        // Fingerprints use a special upper-case hash algorithm encoding.
        let algorithm = match alg_str {
            "SHA256" => HashAlg::Sha256,
            "SHA512" => HashAlg::Sha512,
            _ => return Err(Error::AlgorithmUnknown),
        };

        // Buffer size is the largest digest size of of any supported hash function
        let mut buf = [0u8; HashAlg::Sha512.digest_size()];
        let decoded_bytes = Base64Unpadded::decode(base64, &mut buf)?;

        match algorithm {
            HashAlg::Sha256 => Ok(Self::Sha256(decoded_bytes.try_into()?)),
            HashAlg::Sha512 => Ok(Self::Sha512(decoded_bytes.try_into()?)),
        }
    }
}

#[cfg(all(feature = "alloc", feature = "serde"))]
impl<'de> Deserialize<'de> for Fingerprint {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: de::Deserializer<'de>,
    {
        let string = String::deserialize(deserializer)?;
        string.parse().map_err(de::Error::custom)
    }
}

#[cfg(all(feature = "alloc", feature = "serde"))]
impl Serialize for Fingerprint {
    fn serialize<S>(&self, serializer: S) -> core::result::Result<S::Ok, S::Error>
    where
        S: ser::Serializer,
    {
        self.to_string().serialize(serializer)
    }
}
