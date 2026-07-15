//! Opaque private keys.
//!
//! [`OpaqueKeypair`] represents a keypair meant to be used with an algorithm unknown to this
//! crate, i.e. keypairs that use a custom algorithm as specified in [RFC4251 § 6].
//!
//! They are said to be opaque, because the meaning of their underlying byte representation is not
//! specified.
//!
//! [RFC4251 § 6]: https://www.rfc-editor.org/rfc/rfc4251.html#section-6

use crate::{
    public::{OpaquePublicKey, OpaquePublicKeyBytes},
    Algorithm, Error, Result,
};
use alloc::vec::Vec;
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use subtle::{Choice, ConstantTimeEq};
use zeroize::Zeroize;

/// An opaque private key.
///
/// The encoded representation of an `OpaquePrivateKeyBytes` consists of a 4-byte length prefix,
/// followed by its byte representation.
#[derive(Clone)]
pub struct OpaquePrivateKeyBytes(Vec<u8>);

/// An opaque keypair.
///
/// The encoded representation of an `OpaqueKeypair` consists of the encoded representation of its
/// [`OpaquePublicKey`] followed by the encoded representation of its [`OpaquePrivateKeyBytes`].
#[derive(Clone)]
pub struct OpaqueKeypair {
    /// The opaque private key
    pub private: OpaquePrivateKeyBytes,
    /// The opaque public key
    pub public: OpaquePublicKey,
}

/// The underlying representation of an [`OpaqueKeypair`].
///
/// The encoded representation of an `OpaqueKeypairBytes` consists of the encoded representation of
/// its [`OpaquePublicKeyBytes`] followed by the encoded representation of its
/// [`OpaquePrivateKeyBytes`].
pub struct OpaqueKeypairBytes {
    /// The opaque private key
    pub private: OpaquePrivateKeyBytes,
    /// The opaque public key
    pub public: OpaquePublicKeyBytes,
}

impl OpaqueKeypair {
    /// Create a new `OpaqueKeypair`.
    pub fn new(private_key: Vec<u8>, public: OpaquePublicKey) -> Self {
        Self {
            private: OpaquePrivateKeyBytes(private_key),
            public,
        }
    }

    /// Get the [`Algorithm`] for this key type.
    pub fn algorithm(&self) -> Algorithm {
        self.public.algorithm()
    }

    /// Decode [`OpaqueKeypair`] for the specified algorithm.
    pub(super) fn decode_as(reader: &mut impl Reader, algorithm: Algorithm) -> Result<Self> {
        let key = OpaqueKeypairBytes::decode(reader)?;
        let public = OpaquePublicKey {
            algorithm,
            key: key.public,
        };

        Ok(Self {
            public,
            private: key.private,
        })
    }
}

impl Decode for OpaquePrivateKeyBytes {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Ok(Self(Vec::decode(reader)?))
    }
}

impl Decode for OpaqueKeypairBytes {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let public = OpaquePublicKeyBytes::decode(reader)?;
        let private = OpaquePrivateKeyBytes::decode(reader)?;

        Ok(Self { public, private })
    }
}

impl Encode for OpaqueKeypair {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [self.public.encoded_len()?, self.private.encoded_len()?].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.public.encode(writer)?;
        self.private.encode(writer)?;

        Ok(())
    }
}

impl ConstantTimeEq for OpaqueKeypair {
    fn ct_eq(&self, other: &Self) -> Choice {
        Choice::from((self.public == other.public) as u8) & self.private.ct_eq(&other.private)
    }
}

impl Encode for OpaquePrivateKeyBytes {
    fn encoded_len(&self) -> encoding::Result<usize> {
        self.0.encoded_len()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.0.encode(writer)
    }
}

impl From<&OpaqueKeypair> for OpaquePublicKey {
    fn from(keypair: &OpaqueKeypair) -> OpaquePublicKey {
        keypair.public.clone()
    }
}

impl fmt::Debug for OpaqueKeypair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OpaqueKeypair")
            .field("public", &self.public)
            .finish_non_exhaustive()
    }
}

impl ConstantTimeEq for OpaquePrivateKeyBytes {
    fn ct_eq(&self, other: &Self) -> Choice {
        self.as_ref().ct_eq(other.as_ref())
    }
}

impl AsRef<[u8]> for OpaquePrivateKeyBytes {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

impl Drop for OpaquePrivateKeyBytes {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}
