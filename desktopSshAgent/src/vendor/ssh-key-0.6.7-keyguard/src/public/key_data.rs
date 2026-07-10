//! Public key data.

use super::{Ed25519PublicKey, SkEd25519};
use crate::{Algorithm, Error, Fingerprint, HashAlg, Result};
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

#[cfg(feature = "alloc")]
use super::{DsaPublicKey, OpaquePublicKey, RsaPublicKey};

#[cfg(feature = "ecdsa")]
use super::{EcdsaPublicKey, SkEcdsaSha2NistP256};

/// Public key data.
#[derive(Clone, Debug, Eq, Hash, PartialEq, PartialOrd, Ord)]
#[non_exhaustive]
pub enum KeyData {
    /// Digital Signature Algorithm (DSA) public key data.
    #[cfg(feature = "alloc")]
    Dsa(DsaPublicKey),

    /// Elliptic Curve Digital Signature Algorithm (ECDSA) public key data.
    #[cfg(feature = "ecdsa")]
    Ecdsa(EcdsaPublicKey),

    /// Ed25519 public key data.
    Ed25519(Ed25519PublicKey),

    /// RSA public key data.
    #[cfg(feature = "alloc")]
    Rsa(RsaPublicKey),

    /// Security Key (FIDO/U2F) using ECDSA/NIST P-256 as specified in [PROTOCOL.u2f].
    ///
    /// [PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD
    #[cfg(feature = "ecdsa")]
    SkEcdsaSha2NistP256(SkEcdsaSha2NistP256),

    /// Security Key (FIDO/U2F) using Ed25519 as specified in [PROTOCOL.u2f].
    ///
    /// [PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD
    SkEd25519(SkEd25519),

    /// Opaque public key data.
    #[cfg(feature = "alloc")]
    Other(OpaquePublicKey),
}

impl KeyData {
    /// Get the [`Algorithm`] for this public key.
    pub fn algorithm(&self) -> Algorithm {
        match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(_) => Algorithm::Dsa,
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.algorithm(),
            Self::Ed25519(_) => Algorithm::Ed25519,
            #[cfg(feature = "alloc")]
            Self::Rsa(_) => Algorithm::Rsa { hash: None },
            #[cfg(feature = "ecdsa")]
            Self::SkEcdsaSha2NistP256(_) => Algorithm::SkEcdsaSha2NistP256,
            Self::SkEd25519(_) => Algorithm::SkEd25519,
            #[cfg(feature = "alloc")]
            Self::Other(key) => key.algorithm(),
        }
    }

    /// Get DSA public key if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn dsa(&self) -> Option<&DsaPublicKey> {
        match self {
            Self::Dsa(key) => Some(key),
            _ => None,
        }
    }

    /// Get ECDSA public key if this key is the correct type.
    #[cfg(feature = "ecdsa")]
    pub fn ecdsa(&self) -> Option<&EcdsaPublicKey> {
        match self {
            Self::Ecdsa(key) => Some(key),
            _ => None,
        }
    }

    /// Get Ed25519 public key if this key is the correct type.
    pub fn ed25519(&self) -> Option<&Ed25519PublicKey> {
        match self {
            Self::Ed25519(key) => Some(key),
            #[allow(unreachable_patterns)]
            _ => None,
        }
    }

    /// Compute key fingerprint.
    ///
    /// Use [`Default::default()`] to use the default hash function (SHA-256).
    pub fn fingerprint(&self, hash_alg: HashAlg) -> Fingerprint {
        Fingerprint::new(hash_alg, self)
    }

    /// Get RSA public key if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn rsa(&self) -> Option<&RsaPublicKey> {
        match self {
            Self::Rsa(key) => Some(key),
            _ => None,
        }
    }

    /// Get FIDO/U2F ECDSA/NIST P-256 public key if this key is the correct type.
    #[cfg(feature = "ecdsa")]
    pub fn sk_ecdsa_p256(&self) -> Option<&SkEcdsaSha2NistP256> {
        match self {
            Self::SkEcdsaSha2NistP256(sk) => Some(sk),
            _ => None,
        }
    }

    /// Get FIDO/U2F Ed25519 public key if this key is the correct type.
    pub fn sk_ed25519(&self) -> Option<&SkEd25519> {
        match self {
            Self::SkEd25519(sk) => Some(sk),
            _ => None,
        }
    }

    /// Get the custom, opaque public key if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn other(&self) -> Option<&OpaquePublicKey> {
        match self {
            Self::Other(key) => Some(key),
            _ => None,
        }
    }

    /// Is this key a DSA key?
    #[cfg(feature = "alloc")]
    pub fn is_dsa(&self) -> bool {
        matches!(self, Self::Dsa(_))
    }

    /// Is this key an ECDSA key?
    #[cfg(feature = "ecdsa")]
    pub fn is_ecdsa(&self) -> bool {
        matches!(self, Self::Ecdsa(_))
    }

    /// Is this key an Ed25519 key?
    pub fn is_ed25519(&self) -> bool {
        matches!(self, Self::Ed25519(_))
    }

    /// Is this key an RSA key?
    #[cfg(feature = "alloc")]
    pub fn is_rsa(&self) -> bool {
        matches!(self, Self::Rsa(_))
    }

    /// Is this key a FIDO/U2F ECDSA/NIST P-256 key?
    #[cfg(feature = "ecdsa")]
    pub fn is_sk_ecdsa_p256(&self) -> bool {
        matches!(self, Self::SkEcdsaSha2NistP256(_))
    }

    /// Is this key a FIDO/U2F Ed25519 key?
    pub fn is_sk_ed25519(&self) -> bool {
        matches!(self, Self::SkEd25519(_))
    }

    /// Is this a key with a custom algorithm?
    #[cfg(feature = "alloc")]
    pub fn is_other(&self) -> bool {
        matches!(self, Self::Other(_))
    }

    /// Decode [`KeyData`] for the specified algorithm.
    pub(crate) fn decode_as(reader: &mut impl Reader, algorithm: Algorithm) -> Result<Self> {
        match algorithm {
            #[cfg(feature = "alloc")]
            Algorithm::Dsa => DsaPublicKey::decode(reader).map(Self::Dsa),
            #[cfg(feature = "ecdsa")]
            Algorithm::Ecdsa { curve } => match EcdsaPublicKey::decode(reader)? {
                key if key.curve() == curve => Ok(Self::Ecdsa(key)),
                _ => Err(Error::AlgorithmUnknown),
            },
            Algorithm::Ed25519 => Ed25519PublicKey::decode(reader).map(Self::Ed25519),
            #[cfg(feature = "alloc")]
            Algorithm::Rsa { .. } => RsaPublicKey::decode(reader).map(Self::Rsa),
            #[cfg(feature = "ecdsa")]
            Algorithm::SkEcdsaSha2NistP256 => {
                SkEcdsaSha2NistP256::decode(reader).map(Self::SkEcdsaSha2NistP256)
            }
            Algorithm::SkEd25519 => SkEd25519::decode(reader).map(Self::SkEd25519),
            #[cfg(feature = "alloc")]
            Algorithm::Other(_) => OpaquePublicKey::decode_as(reader, algorithm).map(Self::Other),
            #[allow(unreachable_patterns)]
            _ => Err(Error::AlgorithmUnknown),
        }
    }

    /// Get the encoded length of this key data without a leading algorithm
    /// identifier.
    pub(crate) fn encoded_key_data_len(&self) -> encoding::Result<usize> {
        match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(key) => key.encoded_len(),
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.encoded_len(),
            Self::Ed25519(key) => key.encoded_len(),
            #[cfg(feature = "alloc")]
            Self::Rsa(key) => key.encoded_len(),
            #[cfg(feature = "ecdsa")]
            Self::SkEcdsaSha2NistP256(sk) => sk.encoded_len(),
            Self::SkEd25519(sk) => sk.encoded_len(),
            #[cfg(feature = "alloc")]
            Self::Other(other) => other.key.encoded_len(),
        }
    }

    /// Encode the key data without a leading algorithm identifier.
    pub(crate) fn encode_key_data(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(key) => key.encode(writer),
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.encode(writer),
            Self::Ed25519(key) => key.encode(writer),
            #[cfg(feature = "alloc")]
            Self::Rsa(key) => key.encode(writer),
            #[cfg(feature = "ecdsa")]
            Self::SkEcdsaSha2NistP256(sk) => sk.encode(writer),
            Self::SkEd25519(sk) => sk.encode(writer),
            #[cfg(feature = "alloc")]
            Self::Other(other) => other.key.encode(writer),
        }
    }
}

impl Decode for KeyData {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let algorithm = Algorithm::decode(reader)?;
        Self::decode_as(reader, algorithm)
    }
}

impl Encode for KeyData {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [
            self.algorithm().encoded_len()?,
            self.encoded_key_data_len()?,
        ]
        .checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.algorithm().encode(writer)?;
        self.encode_key_data(writer)
    }
}

#[cfg(feature = "alloc")]
impl From<DsaPublicKey> for KeyData {
    fn from(public_key: DsaPublicKey) -> KeyData {
        Self::Dsa(public_key)
    }
}

#[cfg(feature = "ecdsa")]
impl From<EcdsaPublicKey> for KeyData {
    fn from(public_key: EcdsaPublicKey) -> KeyData {
        Self::Ecdsa(public_key)
    }
}

impl From<Ed25519PublicKey> for KeyData {
    fn from(public_key: Ed25519PublicKey) -> KeyData {
        Self::Ed25519(public_key)
    }
}

#[cfg(feature = "alloc")]
impl From<RsaPublicKey> for KeyData {
    fn from(public_key: RsaPublicKey) -> KeyData {
        Self::Rsa(public_key)
    }
}

#[cfg(feature = "ecdsa")]
impl From<SkEcdsaSha2NistP256> for KeyData {
    fn from(public_key: SkEcdsaSha2NistP256) -> KeyData {
        Self::SkEcdsaSha2NistP256(public_key)
    }
}

impl From<SkEd25519> for KeyData {
    fn from(public_key: SkEd25519) -> KeyData {
        Self::SkEd25519(public_key)
    }
}
