//! Ed25519 private keys.
//!
//! Edwards Digital Signature Algorithm (EdDSA) over Curve25519.

use crate::{public::Ed25519PublicKey, Error, Result};
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use subtle::{Choice, ConstantTimeEq};
use zeroize::{Zeroize, Zeroizing};

#[cfg(feature = "rand_core")]
use rand_core::CryptoRngCore;

/// Ed25519 private key.
// TODO(tarcieri): use `ed25519::PrivateKey`? (doesn't exist yet)
#[derive(Clone)]
pub struct Ed25519PrivateKey([u8; Self::BYTE_SIZE]);

impl Ed25519PrivateKey {
    /// Size of an Ed25519 private key in bytes.
    pub const BYTE_SIZE: usize = 32;

    /// Generate a random Ed25519 private key.
    #[cfg(feature = "rand_core")]
    pub fn random(rng: &mut impl CryptoRngCore) -> Self {
        let mut key_bytes = [0u8; Self::BYTE_SIZE];
        rng.fill_bytes(&mut key_bytes);
        Self(key_bytes)
    }

    /// Parse Ed25519 private key from bytes.
    pub fn from_bytes(bytes: &[u8; Self::BYTE_SIZE]) -> Self {
        Self(*bytes)
    }

    /// Convert to the inner byte array.
    pub fn to_bytes(&self) -> [u8; Self::BYTE_SIZE] {
        self.0
    }
}

impl AsRef<[u8; Self::BYTE_SIZE]> for Ed25519PrivateKey {
    fn as_ref(&self) -> &[u8; Self::BYTE_SIZE] {
        &self.0
    }
}

impl ConstantTimeEq for Ed25519PrivateKey {
    fn ct_eq(&self, other: &Self) -> Choice {
        self.as_ref().ct_eq(other.as_ref())
    }
}

impl Eq for Ed25519PrivateKey {}

impl PartialEq for Ed25519PrivateKey {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl TryFrom<&[u8]> for Ed25519PrivateKey {
    type Error = Error;

    fn try_from(bytes: &[u8]) -> Result<Self> {
        Ok(Ed25519PrivateKey::from_bytes(bytes.try_into()?))
    }
}

impl fmt::Debug for Ed25519PrivateKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Ed25519PrivateKey").finish_non_exhaustive()
    }
}

impl fmt::LowerHex for Ed25519PrivateKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

impl fmt::UpperHex for Ed25519PrivateKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02X}")?;
        }
        Ok(())
    }
}

impl Drop for Ed25519PrivateKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

#[cfg(feature = "ed25519")]
impl From<Ed25519PrivateKey> for ed25519_dalek::SigningKey {
    fn from(key: Ed25519PrivateKey) -> ed25519_dalek::SigningKey {
        ed25519_dalek::SigningKey::from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl From<&Ed25519PrivateKey> for ed25519_dalek::SigningKey {
    fn from(key: &Ed25519PrivateKey) -> ed25519_dalek::SigningKey {
        ed25519_dalek::SigningKey::from_bytes(key.as_ref())
    }
}

#[cfg(feature = "ed25519")]
impl From<ed25519_dalek::SigningKey> for Ed25519PrivateKey {
    fn from(key: ed25519_dalek::SigningKey) -> Ed25519PrivateKey {
        Ed25519PrivateKey::from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl From<&ed25519_dalek::SigningKey> for Ed25519PrivateKey {
    fn from(key: &ed25519_dalek::SigningKey) -> Ed25519PrivateKey {
        Ed25519PrivateKey(key.to_bytes())
    }
}

#[cfg(feature = "ed25519")]
impl From<Ed25519PrivateKey> for Ed25519PublicKey {
    fn from(private: Ed25519PrivateKey) -> Ed25519PublicKey {
        Ed25519PublicKey::from(&private)
    }
}

#[cfg(feature = "ed25519")]
impl From<&Ed25519PrivateKey> for Ed25519PublicKey {
    fn from(private: &Ed25519PrivateKey) -> Ed25519PublicKey {
        ed25519_dalek::SigningKey::from(private)
            .verifying_key()
            .into()
    }
}

/// Ed25519 private/public keypair.
#[derive(Clone)]
pub struct Ed25519Keypair {
    /// Public key.
    pub public: Ed25519PublicKey,

    /// Private key.
    pub private: Ed25519PrivateKey,
}

impl Ed25519Keypair {
    /// Size of an Ed25519 keypair in bytes.
    pub const BYTE_SIZE: usize = 64;

    /// Generate a random Ed25519 private keypair.
    #[cfg(feature = "ed25519")]
    pub fn random(rng: &mut impl CryptoRngCore) -> Self {
        Ed25519PrivateKey::random(rng).into()
    }

    /// Expand a keypair from a 32-byte seed value.
    #[cfg(feature = "ed25519")]
    pub fn from_seed(seed: &[u8; Ed25519PrivateKey::BYTE_SIZE]) -> Self {
        Ed25519PrivateKey::from_bytes(seed).into()
    }

    /// Parse Ed25519 keypair from 64-bytes which comprise the serialized
    /// private and public keys.
    pub fn from_bytes(bytes: &[u8; Self::BYTE_SIZE]) -> Result<Self> {
        let (priv_bytes, pub_bytes) = bytes.split_at(Ed25519PrivateKey::BYTE_SIZE);
        let private = Ed25519PrivateKey::try_from(priv_bytes)?;
        let public = Ed25519PublicKey::try_from(pub_bytes)?;

        // Validate the public key if possible
        #[cfg(feature = "ed25519")]
        if Ed25519PublicKey::from(&private) != public {
            return Err(Error::Crypto);
        }

        Ok(Ed25519Keypair { private, public })
    }

    /// Serialize an Ed25519 keypair as bytes.
    pub fn to_bytes(&self) -> [u8; Self::BYTE_SIZE] {
        let mut result = [0u8; Self::BYTE_SIZE];
        result[..(Self::BYTE_SIZE / 2)].copy_from_slice(self.private.as_ref());
        result[(Self::BYTE_SIZE / 2)..].copy_from_slice(self.public.as_ref());
        result
    }
}

impl ConstantTimeEq for Ed25519Keypair {
    fn ct_eq(&self, other: &Self) -> Choice {
        Choice::from((self.public == other.public) as u8) & self.private.ct_eq(&other.private)
    }
}

impl Eq for Ed25519Keypair {}

impl PartialEq for Ed25519Keypair {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Decode for Ed25519Keypair {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        // Decode private key
        let public = Ed25519PublicKey::decode(reader)?;

        // The OpenSSH serialization of Ed25519 keys is repetitive and includes
        // a serialization of `private_key[32] || public_key[32]` immediately
        // following the public key.
        let mut bytes = Zeroizing::new([0u8; Self::BYTE_SIZE]);
        reader.read_prefixed(|reader| reader.read(&mut *bytes))?;

        let keypair = Self::from_bytes(&bytes)?;

        // Ensure public key matches the one one the keypair
        if keypair.public == public {
            Ok(keypair)
        } else {
            Err(Error::Crypto)
        }
    }
}

impl Encode for Ed25519Keypair {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [4, self.public.encoded_len()?, Self::BYTE_SIZE].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.public.encode(writer)?;
        Zeroizing::new(self.to_bytes()).as_ref().encode(writer)?;
        Ok(())
    }
}

impl From<Ed25519Keypair> for Ed25519PublicKey {
    fn from(keypair: Ed25519Keypair) -> Ed25519PublicKey {
        keypair.public
    }
}

impl From<&Ed25519Keypair> for Ed25519PublicKey {
    fn from(keypair: &Ed25519Keypair) -> Ed25519PublicKey {
        keypair.public
    }
}

impl TryFrom<&[u8]> for Ed25519Keypair {
    type Error = Error;

    fn try_from(bytes: &[u8]) -> Result<Self> {
        Ed25519Keypair::from_bytes(bytes.try_into()?)
    }
}

impl fmt::Debug for Ed25519Keypair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Ed25519Keypair")
            .field("public", &self.public)
            .finish_non_exhaustive()
    }
}

#[cfg(feature = "ed25519")]
impl From<Ed25519PrivateKey> for Ed25519Keypair {
    fn from(private: Ed25519PrivateKey) -> Ed25519Keypair {
        let secret = ed25519_dalek::SigningKey::from(&private);
        let public = secret.verifying_key().into();
        Ed25519Keypair { private, public }
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<Ed25519Keypair> for ed25519_dalek::SigningKey {
    type Error = Error;

    fn try_from(key: Ed25519Keypair) -> Result<ed25519_dalek::SigningKey> {
        ed25519_dalek::SigningKey::try_from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<&Ed25519Keypair> for ed25519_dalek::SigningKey {
    type Error = Error;

    fn try_from(key: &Ed25519Keypair) -> Result<ed25519_dalek::SigningKey> {
        let signing_key = ed25519_dalek::SigningKey::from(&key.private);
        let verifying_key = ed25519_dalek::VerifyingKey::try_from(&key.public)?;

        if signing_key.verifying_key() == verifying_key {
            Ok(signing_key)
        } else {
            Err(Error::PublicKey)
        }
    }
}

#[cfg(feature = "ed25519")]
impl From<ed25519_dalek::SigningKey> for Ed25519Keypair {
    fn from(key: ed25519_dalek::SigningKey) -> Ed25519Keypair {
        Ed25519Keypair::from(&key)
    }
}

#[cfg(feature = "ed25519")]
impl From<&ed25519_dalek::SigningKey> for Ed25519Keypair {
    fn from(key: &ed25519_dalek::SigningKey) -> Ed25519Keypair {
        Ed25519Keypair {
            private: key.into(),
            public: key.verifying_key().into(),
        }
    }
}
