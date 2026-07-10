//! Ed25519 public keys.
//!
//! Edwards Digital Signature Algorithm (EdDSA) over Curve25519.

use crate::{Error, Result};
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

/// Ed25519 public key.
// TODO(tarcieri): use `ed25519::PublicKey`? (doesn't exist yet)
#[derive(Copy, Clone, Debug, Eq, Hash, PartialEq, PartialOrd, Ord)]
pub struct Ed25519PublicKey(pub [u8; Self::BYTE_SIZE]);

impl Ed25519PublicKey {
    /// Size of an Ed25519 public key in bytes.
    pub const BYTE_SIZE: usize = 32;
}

impl AsRef<[u8; Self::BYTE_SIZE]> for Ed25519PublicKey {
    fn as_ref(&self) -> &[u8; Self::BYTE_SIZE] {
        &self.0
    }
}

impl Decode for Ed25519PublicKey {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let mut bytes = [0u8; Self::BYTE_SIZE];
        reader.read_prefixed(|reader| reader.read(&mut bytes))?;
        Ok(Self(bytes))
    }
}

impl Encode for Ed25519PublicKey {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [4, Self::BYTE_SIZE].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.0.encode(writer)?;
        Ok(())
    }
}

impl TryFrom<&[u8]> for Ed25519PublicKey {
    type Error = Error;

    fn try_from(bytes: &[u8]) -> Result<Self> {
        Ok(Self(bytes.try_into()?))
    }
}

impl fmt::Display for Ed25519PublicKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:X}")
    }
}

impl fmt::LowerHex for Ed25519PublicKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

impl fmt::UpperHex for Ed25519PublicKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02X}")?;
        }
        Ok(())
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<Ed25519PublicKey> for ed25519_dalek::VerifyingKey {
    type Error = Error;

    fn try_from(key: Ed25519PublicKey) -> Result<ed25519_dalek::VerifyingKey> {
        ed25519_dalek::VerifyingKey::try_from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<&Ed25519PublicKey> for ed25519_dalek::VerifyingKey {
    type Error = Error;

    fn try_from(key: &Ed25519PublicKey) -> Result<ed25519_dalek::VerifyingKey> {
        ed25519_dalek::VerifyingKey::from_bytes(key.as_ref()).map_err(|_| Error::Crypto)
    }
}

#[cfg(feature = "ed25519")]
impl From<ed25519_dalek::VerifyingKey> for Ed25519PublicKey {
    fn from(key: ed25519_dalek::VerifyingKey) -> Ed25519PublicKey {
        Ed25519PublicKey::from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl From<&ed25519_dalek::VerifyingKey> for Ed25519PublicKey {
    fn from(key: &ed25519_dalek::VerifyingKey) -> Ed25519PublicKey {
        Ed25519PublicKey(key.to_bytes())
    }
}
