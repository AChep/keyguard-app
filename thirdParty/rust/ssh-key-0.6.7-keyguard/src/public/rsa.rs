//! Rivest–Shamir–Adleman (RSA) public keys.

use crate::{Error, Mpint, Result};
use core::hash::{Hash, Hasher};
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

#[cfg(feature = "rsa")]
use {
    crate::private::RsaKeypair,
    rsa::{pkcs1v15, traits::PublicKeyParts},
    sha2::{digest::const_oid::AssociatedOid, Digest},
};

/// RSA public key.
///
/// Described in [RFC4253 § 6.6](https://datatracker.ietf.org/doc/html/rfc4253#section-6.6).
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct RsaPublicKey {
    /// RSA public exponent.
    pub e: Mpint,

    /// RSA modulus.
    pub n: Mpint,
}

impl RsaPublicKey {
    /// Minimum allowed RSA key size.
    #[cfg(feature = "rsa")]
    pub(crate) const MIN_KEY_SIZE: usize = RsaKeypair::MIN_KEY_SIZE;
}

impl Decode for RsaPublicKey {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let e = Mpint::decode(reader)?;
        let n = Mpint::decode(reader)?;
        Ok(Self { e, n })
    }
}

impl Encode for RsaPublicKey {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [self.e.encoded_len()?, self.n.encoded_len()?].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.e.encode(writer)?;
        self.n.encode(writer)
    }
}

impl Hash for RsaPublicKey {
    #[inline]
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.e.as_bytes().hash(state);
        self.n.as_bytes().hash(state);
    }
}

#[cfg(feature = "rsa")]
impl TryFrom<RsaPublicKey> for rsa::RsaPublicKey {
    type Error = Error;

    fn try_from(key: RsaPublicKey) -> Result<rsa::RsaPublicKey> {
        rsa::RsaPublicKey::try_from(&key)
    }
}

#[cfg(feature = "rsa")]
impl TryFrom<&RsaPublicKey> for rsa::RsaPublicKey {
    type Error = Error;

    fn try_from(key: &RsaPublicKey) -> Result<rsa::RsaPublicKey> {
        let ret = rsa::RsaPublicKey::new(
            rsa::BigUint::try_from(&key.n)?,
            rsa::BigUint::try_from(&key.e)?,
        )
        .map_err(|_| Error::Crypto)?;

        if ret.size().saturating_mul(8) >= RsaPublicKey::MIN_KEY_SIZE {
            Ok(ret)
        } else {
            Err(Error::Crypto)
        }
    }
}

#[cfg(feature = "rsa")]
impl TryFrom<rsa::RsaPublicKey> for RsaPublicKey {
    type Error = Error;

    fn try_from(key: rsa::RsaPublicKey) -> Result<RsaPublicKey> {
        RsaPublicKey::try_from(&key)
    }
}

#[cfg(feature = "rsa")]
impl TryFrom<&rsa::RsaPublicKey> for RsaPublicKey {
    type Error = Error;

    fn try_from(key: &rsa::RsaPublicKey) -> Result<RsaPublicKey> {
        Ok(RsaPublicKey {
            e: key.e().try_into()?,
            n: key.n().try_into()?,
        })
    }
}

#[cfg(feature = "rsa")]
impl<D> TryFrom<&RsaPublicKey> for pkcs1v15::VerifyingKey<D>
where
    D: Digest + AssociatedOid,
{
    type Error = Error;

    fn try_from(key: &RsaPublicKey) -> Result<pkcs1v15::VerifyingKey<D>> {
        Ok(pkcs1v15::VerifyingKey::new(key.try_into()?))
    }
}
