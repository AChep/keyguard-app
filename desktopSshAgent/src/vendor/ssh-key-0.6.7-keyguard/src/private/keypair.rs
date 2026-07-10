//! Private key pairs.

use super::ed25519::Ed25519Keypair;
use crate::{public, Algorithm, Error, Result};
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use subtle::{Choice, ConstantTimeEq};

#[cfg(feature = "alloc")]
use {
    super::{DsaKeypair, OpaqueKeypair, RsaKeypair, SkEd25519},
    alloc::vec::Vec,
};

#[cfg(feature = "ecdsa")]
use super::EcdsaKeypair;

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
use super::SkEcdsaSha2NistP256;

/// Private key data: digital signature key pairs.
///
/// SSH private keys contain pairs of public and private keys for various
/// supported digital signature algorithms.
// TODO(tarcieri): pseudo-private keys for FIDO/U2F security keys
#[derive(Clone, Debug)]
#[non_exhaustive]
pub enum KeypairData {
    /// Digital Signature Algorithm (DSA) keypair.
    #[cfg(feature = "alloc")]
    Dsa(DsaKeypair),

    /// ECDSA keypair.
    #[cfg(feature = "ecdsa")]
    Ecdsa(EcdsaKeypair),

    /// Ed25519 keypair.
    Ed25519(Ed25519Keypair),

    /// Encrypted private key (ciphertext).
    #[cfg(feature = "alloc")]
    Encrypted(Vec<u8>),

    /// RSA keypair.
    #[cfg(feature = "alloc")]
    Rsa(RsaKeypair),

    /// Security Key (FIDO/U2F) using ECDSA/NIST P-256 as specified in [PROTOCOL.u2f].
    ///
    /// [PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD
    #[cfg(all(feature = "alloc", feature = "ecdsa"))]
    SkEcdsaSha2NistP256(SkEcdsaSha2NistP256),

    /// Security Key (FIDO/U2F) using Ed25519 as specified in [PROTOCOL.u2f].
    ///
    /// [PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD
    #[cfg(feature = "alloc")]
    SkEd25519(SkEd25519),

    /// Opaque keypair.
    #[cfg(feature = "alloc")]
    Other(OpaqueKeypair),
}

impl KeypairData {
    /// Get the [`Algorithm`] for this private key.
    pub fn algorithm(&self) -> Result<Algorithm> {
        Ok(match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(_) => Algorithm::Dsa,
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.algorithm(),
            Self::Ed25519(_) => Algorithm::Ed25519,
            #[cfg(feature = "alloc")]
            Self::Encrypted(_) => return Err(Error::Encrypted),
            #[cfg(feature = "alloc")]
            Self::Rsa(_) => Algorithm::Rsa { hash: None },
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            Self::SkEcdsaSha2NistP256(_) => Algorithm::SkEcdsaSha2NistP256,
            #[cfg(feature = "alloc")]
            Self::SkEd25519(_) => Algorithm::SkEd25519,
            #[cfg(feature = "alloc")]
            Self::Other(key) => key.algorithm(),
        })
    }

    /// Get DSA keypair if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn dsa(&self) -> Option<&DsaKeypair> {
        match self {
            Self::Dsa(key) => Some(key),
            _ => None,
        }
    }

    /// Get ECDSA private key if this key is the correct type.
    #[cfg(feature = "ecdsa")]
    pub fn ecdsa(&self) -> Option<&EcdsaKeypair> {
        match self {
            Self::Ecdsa(keypair) => Some(keypair),
            _ => None,
        }
    }

    /// Get Ed25519 private key if this key is the correct type.
    pub fn ed25519(&self) -> Option<&Ed25519Keypair> {
        match self {
            Self::Ed25519(key) => Some(key),
            #[allow(unreachable_patterns)]
            _ => None,
        }
    }

    /// Get the encrypted ciphertext if this key is encrypted.
    #[cfg(feature = "alloc")]
    pub fn encrypted(&self) -> Option<&[u8]> {
        match self {
            Self::Encrypted(ciphertext) => Some(ciphertext),
            _ => None,
        }
    }

    /// Get RSA keypair if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn rsa(&self) -> Option<&RsaKeypair> {
        match self {
            Self::Rsa(key) => Some(key),
            _ => None,
        }
    }

    /// Get FIDO/U2F ECDSA/NIST P-256 private key if this key is the correct type.
    #[cfg(all(feature = "alloc", feature = "ecdsa"))]
    pub fn sk_ecdsa_p256(&self) -> Option<&SkEcdsaSha2NistP256> {
        match self {
            Self::SkEcdsaSha2NistP256(sk) => Some(sk),
            _ => None,
        }
    }

    /// Get FIDO/U2F Ed25519 private key if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn sk_ed25519(&self) -> Option<&SkEd25519> {
        match self {
            Self::SkEd25519(sk) => Some(sk),
            _ => None,
        }
    }

    /// Get the custom, opaque private key if this key is the correct type.
    #[cfg(feature = "alloc")]
    pub fn other(&self) -> Option<&OpaqueKeypair> {
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

    /// Is this key encrypted?
    #[cfg(not(feature = "alloc"))]
    pub fn is_encrypted(&self) -> bool {
        false
    }

    /// Is this key encrypted?
    #[cfg(feature = "alloc")]
    pub fn is_encrypted(&self) -> bool {
        matches!(self, Self::Encrypted(_))
    }

    /// Is this key an RSA key?
    #[cfg(feature = "alloc")]
    pub fn is_rsa(&self) -> bool {
        matches!(self, Self::Rsa(_))
    }

    /// Is this key a FIDO/U2F ECDSA/NIST P-256 key?
    #[cfg(all(feature = "alloc", feature = "ecdsa"))]
    pub fn is_sk_ecdsa_p256(&self) -> bool {
        matches!(self, Self::SkEcdsaSha2NistP256(_))
    }

    /// Is this key a FIDO/U2F Ed25519 key?
    #[cfg(feature = "alloc")]
    pub fn is_sk_ed25519(&self) -> bool {
        matches!(self, Self::SkEd25519(_))
    }

    /// Is this a key with a custom algorithm?
    #[cfg(feature = "alloc")]
    pub fn is_other(&self) -> bool {
        matches!(self, Self::Other(_))
    }

    /// Compute a deterministic "checkint" for this private key.
    ///
    /// This is a sort of primitive pseudo-MAC used by the OpenSSH key format.
    // TODO(tarcieri): true randomness or a better algorithm?
    pub(super) fn checkint(&self) -> u32 {
        let bytes = match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(dsa) => dsa.private.as_bytes(),
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(ecdsa) => ecdsa.private_key_bytes(),
            Self::Ed25519(ed25519) => ed25519.private.as_ref(),
            #[cfg(feature = "alloc")]
            Self::Encrypted(ciphertext) => ciphertext.as_ref(),
            #[cfg(feature = "alloc")]
            Self::Rsa(rsa) => rsa.private.d.as_bytes(),
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            Self::SkEcdsaSha2NistP256(sk) => sk.key_handle(),
            #[cfg(feature = "alloc")]
            Self::SkEd25519(sk) => sk.key_handle(),
            #[cfg(feature = "alloc")]
            Self::Other(key) => key.private.as_ref(),
        };

        let mut n = 0u32;

        for chunk in bytes.chunks_exact(4) {
            n ^= u32::from_be_bytes(chunk.try_into().expect("not 4 bytes"));
        }

        n
    }

    /// Decode [`KeypairData`] for the specified algorithm.
    pub fn decode_as(reader: &mut impl Reader, algorithm: Algorithm) -> Result<Self> {
        match algorithm {
            #[cfg(feature = "alloc")]
            Algorithm::Dsa => DsaKeypair::decode(reader).map(Self::Dsa),
            #[cfg(feature = "ecdsa")]
            Algorithm::Ecdsa { curve } => match EcdsaKeypair::decode(reader)? {
                keypair if keypair.curve() == curve => Ok(Self::Ecdsa(keypair)),
                _ => Err(Error::AlgorithmUnknown),
            },
            Algorithm::Ed25519 => Ed25519Keypair::decode(reader).map(Self::Ed25519),
            #[cfg(feature = "alloc")]
            Algorithm::Rsa { .. } => RsaKeypair::decode(reader).map(Self::Rsa),
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            Algorithm::SkEcdsaSha2NistP256 => {
                SkEcdsaSha2NistP256::decode(reader).map(Self::SkEcdsaSha2NistP256)
            }
            #[cfg(feature = "alloc")]
            Algorithm::SkEd25519 => SkEd25519::decode(reader).map(Self::SkEd25519),
            #[cfg(feature = "alloc")]
            algorithm @ Algorithm::Other(_) => {
                OpaqueKeypair::decode_as(reader, algorithm).map(Self::Other)
            }
            #[allow(unreachable_patterns)]
            _ => Err(Error::AlgorithmUnknown),
        }
    }
}

impl ConstantTimeEq for KeypairData {
    fn ct_eq(&self, other: &Self) -> Choice {
        // Note: constant-time with respect to key *data* comparisons, not algorithms
        match (self, other) {
            #[cfg(feature = "alloc")]
            (Self::Dsa(a), Self::Dsa(b)) => a.ct_eq(b),
            #[cfg(feature = "ecdsa")]
            (Self::Ecdsa(a), Self::Ecdsa(b)) => a.ct_eq(b),
            (Self::Ed25519(a), Self::Ed25519(b)) => a.ct_eq(b),
            #[cfg(feature = "alloc")]
            (Self::Encrypted(a), Self::Encrypted(b)) => a.ct_eq(b),
            #[cfg(feature = "alloc")]
            (Self::Rsa(a), Self::Rsa(b)) => a.ct_eq(b),
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            (Self::SkEcdsaSha2NistP256(a), Self::SkEcdsaSha2NistP256(b)) => {
                // Security Keys store the actual private key in hardware.
                // The key structs contain all public data.
                Choice::from((a == b) as u8)
            }
            #[cfg(feature = "alloc")]
            (Self::SkEd25519(a), Self::SkEd25519(b)) => {
                // Security Keys store the actual private key in hardware.
                // The key structs contain all public data.
                Choice::from((a == b) as u8)
            }
            #[cfg(feature = "alloc")]
            (Self::Other(a), Self::Other(b)) => a.ct_eq(b),
            #[allow(unreachable_patterns)]
            _ => Choice::from(0),
        }
    }
}

impl Eq for KeypairData {}

impl PartialEq for KeypairData {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Decode for KeypairData {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let algorithm = Algorithm::decode(reader)?;
        Self::decode_as(reader, algorithm)
    }
}

impl Encode for KeypairData {
    fn encoded_len(&self) -> encoding::Result<usize> {
        let alg_len = self
            .algorithm()
            .ok()
            .map(|alg| alg.encoded_len())
            .transpose()?
            .unwrap_or(0);

        let key_len = match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(key) => key.encoded_len()?,
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.encoded_len()?,
            Self::Ed25519(key) => key.encoded_len()?,
            #[cfg(feature = "alloc")]
            Self::Encrypted(ciphertext) => return Ok(ciphertext.len()),
            #[cfg(feature = "alloc")]
            Self::Rsa(key) => key.encoded_len()?,
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            Self::SkEcdsaSha2NistP256(sk) => sk.encoded_len()?,
            #[cfg(feature = "alloc")]
            Self::SkEd25519(sk) => sk.encoded_len()?,
            #[cfg(feature = "alloc")]
            Self::Other(key) => key.encoded_len()?,
        };

        [alg_len, key_len].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        if let Ok(alg) = self.algorithm() {
            alg.encode(writer)?;
        }

        match self {
            #[cfg(feature = "alloc")]
            Self::Dsa(key) => key.encode(writer)?,
            #[cfg(feature = "ecdsa")]
            Self::Ecdsa(key) => key.encode(writer)?,
            Self::Ed25519(key) => key.encode(writer)?,
            #[cfg(feature = "alloc")]
            Self::Encrypted(ciphertext) => writer.write(ciphertext)?,
            #[cfg(feature = "alloc")]
            Self::Rsa(key) => key.encode(writer)?,
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            Self::SkEcdsaSha2NistP256(sk) => sk.encode(writer)?,
            #[cfg(feature = "alloc")]
            Self::SkEd25519(sk) => sk.encode(writer)?,
            #[cfg(feature = "alloc")]
            Self::Other(key) => key.encode(writer)?,
        }

        Ok(())
    }
}

impl TryFrom<&KeypairData> for public::KeyData {
    type Error = Error;

    fn try_from(keypair_data: &KeypairData) -> Result<public::KeyData> {
        Ok(match keypair_data {
            #[cfg(feature = "alloc")]
            KeypairData::Dsa(dsa) => public::KeyData::Dsa(dsa.into()),
            #[cfg(feature = "ecdsa")]
            KeypairData::Ecdsa(ecdsa) => public::KeyData::Ecdsa(ecdsa.into()),
            KeypairData::Ed25519(ed25519) => public::KeyData::Ed25519(ed25519.into()),
            #[cfg(feature = "alloc")]
            KeypairData::Encrypted(_) => return Err(Error::Encrypted),
            #[cfg(feature = "alloc")]
            KeypairData::Rsa(rsa) => public::KeyData::Rsa(rsa.into()),
            #[cfg(all(feature = "alloc", feature = "ecdsa"))]
            KeypairData::SkEcdsaSha2NistP256(sk) => {
                public::KeyData::SkEcdsaSha2NistP256(sk.public().clone())
            }
            #[cfg(feature = "alloc")]
            KeypairData::SkEd25519(sk) => public::KeyData::SkEd25519(sk.public().clone()),
            #[cfg(feature = "alloc")]
            KeypairData::Other(key) => public::KeyData::Other(key.into()),
        })
    }
}

#[cfg(feature = "alloc")]
impl From<DsaKeypair> for KeypairData {
    fn from(keypair: DsaKeypair) -> KeypairData {
        Self::Dsa(keypair)
    }
}

#[cfg(feature = "ecdsa")]
impl From<EcdsaKeypair> for KeypairData {
    fn from(keypair: EcdsaKeypair) -> KeypairData {
        Self::Ecdsa(keypair)
    }
}

impl From<Ed25519Keypair> for KeypairData {
    fn from(keypair: Ed25519Keypair) -> KeypairData {
        Self::Ed25519(keypair)
    }
}

#[cfg(feature = "alloc")]
impl From<RsaKeypair> for KeypairData {
    fn from(keypair: RsaKeypair) -> KeypairData {
        Self::Rsa(keypair)
    }
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
impl From<SkEcdsaSha2NistP256> for KeypairData {
    fn from(keypair: SkEcdsaSha2NistP256) -> KeypairData {
        Self::SkEcdsaSha2NistP256(keypair)
    }
}

#[cfg(feature = "alloc")]
impl From<SkEd25519> for KeypairData {
    fn from(keypair: SkEd25519) -> KeypairData {
        Self::SkEd25519(keypair)
    }
}
