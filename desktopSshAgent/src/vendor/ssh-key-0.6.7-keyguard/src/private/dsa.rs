//! Digital Signature Algorithm (DSA) private keys.

use crate::{public::DsaPublicKey, Error, Mpint, Result};
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use subtle::{Choice, ConstantTimeEq};
use zeroize::Zeroize;

#[cfg(all(feature = "dsa", feature = "rand_core"))]
use rand_core::CryptoRngCore;

/// Digital Signature Algorithm (DSA) private key.
///
/// Uniformly random integer `x`, such that `0 < x < q`, i.e. `x` is in the
/// range `[1, q–1]`.
///
/// Described in [FIPS 186-4 § 4.1](https://csrc.nist.gov/publications/detail/fips/186/4/final).
#[derive(Clone)]
pub struct DsaPrivateKey {
    /// Integer representing a DSA private key.
    inner: Mpint,
}

impl DsaPrivateKey {
    /// Get the serialized private key as bytes.
    pub fn as_bytes(&self) -> &[u8] {
        self.inner.as_bytes()
    }

    /// Get the inner [`Mpint`].
    pub fn as_mpint(&self) -> &Mpint {
        &self.inner
    }
}

impl AsRef<[u8]> for DsaPrivateKey {
    fn as_ref(&self) -> &[u8] {
        self.as_bytes()
    }
}

impl ConstantTimeEq for DsaPrivateKey {
    fn ct_eq(&self, other: &Self) -> Choice {
        self.inner.ct_eq(&other.inner)
    }
}

impl Eq for DsaPrivateKey {}

impl PartialEq for DsaPrivateKey {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Decode for DsaPrivateKey {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Ok(Self {
            inner: Mpint::decode(reader)?,
        })
    }
}

impl Encode for DsaPrivateKey {
    fn encoded_len(&self) -> encoding::Result<usize> {
        self.inner.encoded_len()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.inner.encode(writer)
    }
}

impl fmt::Debug for DsaPrivateKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DsaPrivateKey").finish_non_exhaustive()
    }
}

impl Drop for DsaPrivateKey {
    fn drop(&mut self) {
        self.inner.zeroize();
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<DsaPrivateKey> for dsa::BigUint {
    type Error = Error;

    fn try_from(key: DsaPrivateKey) -> Result<dsa::BigUint> {
        dsa::BigUint::try_from(&key.inner)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<&DsaPrivateKey> for dsa::BigUint {
    type Error = Error;

    fn try_from(key: &DsaPrivateKey) -> Result<dsa::BigUint> {
        dsa::BigUint::try_from(&key.inner)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<dsa::SigningKey> for DsaPrivateKey {
    type Error = Error;

    fn try_from(key: dsa::SigningKey) -> Result<DsaPrivateKey> {
        DsaPrivateKey::try_from(&key)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<&dsa::SigningKey> for DsaPrivateKey {
    type Error = Error;

    fn try_from(key: &dsa::SigningKey) -> Result<DsaPrivateKey> {
        Ok(DsaPrivateKey {
            inner: key.x().try_into()?,
        })
    }
}

/// Digital Signature Algorithm (DSA) private/public keypair.
#[derive(Clone)]
pub struct DsaKeypair {
    /// Public key.
    pub public: DsaPublicKey,

    /// Private key.
    pub private: DsaPrivateKey,
}

impl DsaKeypair {
    /// Key size.
    #[cfg(all(feature = "dsa", feature = "rand_core"))]
    #[allow(deprecated)]
    pub(crate) const KEY_SIZE: dsa::KeySize = dsa::KeySize::DSA_1024_160;

    /// Generate a random DSA private key.
    #[cfg(all(feature = "dsa", feature = "rand_core"))]
    pub fn random(rng: &mut impl CryptoRngCore) -> Result<Self> {
        let components = dsa::Components::generate(rng, Self::KEY_SIZE);
        dsa::SigningKey::generate(rng, components).try_into()
    }
}

impl ConstantTimeEq for DsaKeypair {
    fn ct_eq(&self, other: &Self) -> Choice {
        Choice::from((self.public == other.public) as u8) & self.private.ct_eq(&other.private)
    }
}

impl PartialEq for DsaKeypair {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Eq for DsaKeypair {}

impl Decode for DsaKeypair {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let public = DsaPublicKey::decode(reader)?;
        let private = DsaPrivateKey::decode(reader)?;
        Ok(DsaKeypair { public, private })
    }
}

impl Encode for DsaKeypair {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [self.public.encoded_len()?, self.private.encoded_len()?].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.public.encode(writer)?;
        self.private.encode(writer)
    }
}

impl From<DsaKeypair> for DsaPublicKey {
    fn from(keypair: DsaKeypair) -> DsaPublicKey {
        keypair.public
    }
}

impl From<&DsaKeypair> for DsaPublicKey {
    fn from(keypair: &DsaKeypair) -> DsaPublicKey {
        keypair.public.clone()
    }
}

impl fmt::Debug for DsaKeypair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DsaKeypair")
            .field("public", &self.public)
            .finish_non_exhaustive()
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<DsaKeypair> for dsa::SigningKey {
    type Error = Error;

    fn try_from(key: DsaKeypair) -> Result<dsa::SigningKey> {
        dsa::SigningKey::try_from(&key)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<&DsaKeypair> for dsa::SigningKey {
    type Error = Error;

    fn try_from(key: &DsaKeypair) -> Result<dsa::SigningKey> {
        Ok(dsa::SigningKey::from_components(
            dsa::VerifyingKey::try_from(&key.public)?,
            dsa::BigUint::try_from(&key.private)?,
        )?)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<dsa::SigningKey> for DsaKeypair {
    type Error = Error;

    fn try_from(key: dsa::SigningKey) -> Result<DsaKeypair> {
        DsaKeypair::try_from(&key)
    }
}

#[cfg(feature = "dsa")]
impl TryFrom<&dsa::SigningKey> for DsaKeypair {
    type Error = Error;

    fn try_from(key: &dsa::SigningKey) -> Result<DsaKeypair> {
        Ok(DsaKeypair {
            private: key.try_into()?,
            public: key.verifying_key().try_into()?,
        })
    }
}
