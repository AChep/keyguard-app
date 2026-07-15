//! Signatures (e.g. CA signatures over SSH certificates)

use crate::{private, public, Algorithm, EcdsaCurve, Error, Mpint, PrivateKey, PublicKey, Result};
use alloc::vec::Vec;
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use signature::{SignatureEncoding, Signer, Verifier};

#[cfg(feature = "ed25519")]
use crate::{private::Ed25519Keypair, public::Ed25519PublicKey};

#[cfg(feature = "dsa")]
use {
    crate::{private::DsaKeypair, public::DsaPublicKey},
    bigint::BigUint,
    sha1::Sha1,
    signature::{DigestSigner, DigestVerifier},
};

#[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
use crate::{
    private::{EcdsaKeypair, EcdsaPrivateKey},
    public::EcdsaPublicKey,
};

#[cfg(any(feature = "dsa", feature = "p256", feature = "p384", feature = "p521"))]
use core::iter;

#[cfg(feature = "rsa")]
use {
    crate::{private::RsaKeypair, public::RsaPublicKey, HashAlg},
    sha2::Sha512,
};

#[cfg(any(feature = "ed25519", feature = "rsa", feature = "p256"))]
use sha2::Sha256;

#[cfg(any(feature = "dsa", feature = "ed25519", feature = "p256"))]
use sha2::Digest;

const DSA_SIGNATURE_SIZE: usize = 40;
const ED25519_SIGNATURE_SIZE: usize = 64;
const SK_SIGNATURE_TRAILER_SIZE: usize = 5; // flags(u8), counter(u32)
const SK_ED25519_SIGNATURE_SIZE: usize = ED25519_SIGNATURE_SIZE + SK_SIGNATURE_TRAILER_SIZE;

/// Trait for signing keys which produce a [`Signature`].
///
/// This trait is automatically impl'd for any types which impl the
/// [`Signer`] trait for the SSH [`Signature`] type and also support a [`From`]
/// conversion for [`public::KeyData`].
pub trait SigningKey: Signer<Signature> {
    /// Get the [`public::KeyData`] for this signing key.
    fn public_key(&self) -> public::KeyData;
}

impl<T> SigningKey for T
where
    T: Signer<Signature>,
    public::KeyData: for<'a> From<&'a T>,
{
    fn public_key(&self) -> public::KeyData {
        self.into()
    }
}

/// Low-level digital signature (e.g. DSA, ECDSA, Ed25519).
///
/// These are low-level signatures used as part of the OpenSSH certificate
/// format to represent signatures by certificate authorities (CAs), as well
/// as the higher-level [`SshSig`][`crate::SshSig`] format, which provides
/// general-purpose signing functionality using SSH keys.
///
/// From OpenSSH's [PROTOCOL.certkeys] specification:
///
/// > Signatures are computed and encoded according to the rules defined for
/// > the CA's public key algorithm ([RFC4253 section 6.6] for ssh-rsa and
/// > ssh-dss, [RFC5656] for the ECDSA types, and [RFC8032] for Ed25519).
///
/// RSA signature support is implemented using the SHA2 family extensions as
/// described in [RFC8332].
///
/// [PROTOCOL.certkeys]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.certkeys?annotate=HEAD
/// [RFC4253 section 6.6]: https://datatracker.ietf.org/doc/html/rfc4253#section-6.6
/// [RFC5656]: https://datatracker.ietf.org/doc/html/rfc5656
/// [RFC8032]: https://datatracker.ietf.org/doc/html/rfc8032
/// [RFC8332]: https://datatracker.ietf.org/doc/html/rfc8332
#[derive(Clone, Eq, PartialEq, PartialOrd, Ord)]
pub struct Signature {
    /// Signature algorithm.
    algorithm: Algorithm,

    /// Raw signature serialized as algorithm-specific byte encoding.
    data: Vec<u8>,
}

impl Signature {
    /// Create a new signature with the given algorithm and raw signature data.
    ///
    /// See specifications in toplevel [`Signature`] documentation for how to
    /// format the raw signature data for a given algorithm.
    ///
    /// # Returns
    /// - [`Error::Encoding`] if the signature is not the correct length.
    pub fn new(algorithm: Algorithm, data: impl Into<Vec<u8>>) -> Result<Self> {
        let data = data.into();

        // Validate signature is well-formed per OpensSH encoding
        match algorithm {
            Algorithm::Dsa if data.len() == DSA_SIGNATURE_SIZE => (),
            Algorithm::Ecdsa { curve } => ecdsa_sig_size(&data, curve, false)?,
            Algorithm::Ed25519 if data.len() == ED25519_SIGNATURE_SIZE => (),
            Algorithm::SkEd25519 if data.len() == SK_ED25519_SIGNATURE_SIZE => (),
            Algorithm::SkEcdsaSha2NistP256 => ecdsa_sig_size(&data, EcdsaCurve::NistP256, true)?,
            Algorithm::Rsa { hash: Some(_) } => (),
            Algorithm::Other(_) if !data.is_empty() => (),
            _ => return Err(encoding::Error::Length.into()),
        }

        Ok(Self { algorithm, data })
    }

    /// Get the [`Algorithm`] associated with this signature.
    pub fn algorithm(&self) -> Algorithm {
        self.algorithm.clone()
    }

    /// Get the raw signature as bytes.
    pub fn as_bytes(&self) -> &[u8] {
        &self.data
    }

    /// Placeholder signature used by the certificate builder.
    ///
    /// This is guaranteed generate an error if anything attempts to encode it.
    pub(crate) fn placeholder() -> Self {
        Self {
            algorithm: Algorithm::default(),
            data: Vec::new(),
        }
    }

    /// Check if this signature is the placeholder signature.
    pub(crate) fn is_placeholder(&self) -> bool {
        self.algorithm == Algorithm::default() && self.data.is_empty()
    }
}

/// Returns Ok() if data holds an ecdsa signature with components of appropriate size
/// according to curve.
fn ecdsa_sig_size(data: &Vec<u8>, curve: EcdsaCurve, sk_trailer: bool) -> Result<()> {
    let reader = &mut data.as_slice();

    for _ in 0..2 {
        let component = Mpint::decode(reader)?;

        if component.as_positive_bytes().ok_or(Error::Crypto)?.len() > curve.field_size() {
            return Err(encoding::Error::Length.into());
        }
    }
    if sk_trailer {
        reader.drain(SK_SIGNATURE_TRAILER_SIZE)?;
    }
    reader
        .finish(())
        .map_err(|_| encoding::Error::Length.into())
}

impl AsRef<[u8]> for Signature {
    fn as_ref(&self) -> &[u8] {
        self.as_bytes()
    }
}

impl Decode for Signature {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let algorithm = Algorithm::decode(reader)?;
        let mut data = Vec::decode(reader)?;

        if algorithm == Algorithm::SkEd25519 || algorithm == Algorithm::SkEcdsaSha2NistP256 {
            let flags = u8::decode(reader)?;
            let counter = u32::decode(reader)?;

            data.push(flags);
            data.extend(counter.to_be_bytes());
        }
        Self::new(algorithm, data)
    }
}

impl Encode for Signature {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [
            self.algorithm().encoded_len()?,
            self.as_bytes().encoded_len()?,
        ]
        .checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        if self.is_placeholder() {
            return Err(encoding::Error::Length);
        }

        self.algorithm().encode(writer)?;

        if self.algorithm == Algorithm::SkEd25519 {
            let signature_length = self
                .as_bytes()
                .len()
                .checked_sub(SK_SIGNATURE_TRAILER_SIZE)
                .ok_or(encoding::Error::Length)?;
            self.as_bytes()[..signature_length].encode(writer)?;
            writer.write(&self.as_bytes()[signature_length..])?;
        } else {
            self.as_bytes().encode(writer)?;
        }

        Ok(())
    }
}

impl SignatureEncoding for Signature {
    type Repr = Vec<u8>;
}

/// Decode [`Signature`] from an [`Algorithm`]-prefixed OpenSSH-encoded bytestring.
impl TryFrom<&[u8]> for Signature {
    type Error = Error;

    fn try_from(mut bytes: &[u8]) -> Result<Self> {
        Self::decode(&mut bytes)
    }
}

impl TryFrom<Signature> for Vec<u8> {
    type Error = Error;

    fn try_from(signature: Signature) -> Result<Vec<u8>> {
        let mut ret = Vec::<u8>::new();
        signature.encode(&mut ret)?;
        Ok(ret)
    }
}

impl fmt::Debug for Signature {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "Signature {{ algorithm: {:?}, data: {:X} }}",
            self.algorithm, self
        )
    }
}

impl fmt::LowerHex for Signature {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

impl fmt::UpperHex for Signature {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_ref() {
            write!(f, "{byte:02X}")?;
        }
        Ok(())
    }
}

impl Signer<Signature> for PrivateKey {
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        self.key_data().try_sign(message)
    }
}

impl Signer<Signature> for private::KeypairData {
    #[allow(unused_variables)]
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        match self {
            #[cfg(feature = "dsa")]
            Self::Dsa(keypair) => keypair.try_sign(message),
            #[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
            Self::Ecdsa(keypair) => keypair.try_sign(message),
            #[cfg(feature = "ed25519")]
            Self::Ed25519(keypair) => keypair.try_sign(message),
            #[cfg(feature = "rsa")]
            Self::Rsa(keypair) => keypair.try_sign(message),
            _ => Err(self.algorithm()?.unsupported_error().into()),
        }
    }
}

impl Verifier<Signature> for PublicKey {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        self.key_data().verify(message, signature)
    }
}

impl Verifier<Signature> for public::KeyData {
    #[allow(unused_variables)]
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        match self {
            #[cfg(feature = "dsa")]
            Self::Dsa(pk) => pk.verify(message, signature),
            #[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
            Self::Ecdsa(pk) => pk.verify(message, signature),
            #[cfg(feature = "ed25519")]
            Self::Ed25519(pk) => pk.verify(message, signature),
            #[cfg(feature = "ed25519")]
            Self::SkEd25519(pk) => pk.verify(message, signature),
            #[cfg(feature = "p256")]
            Self::SkEcdsaSha2NistP256(pk) => pk.verify(message, signature),
            #[cfg(feature = "rsa")]
            Self::Rsa(pk) => pk.verify(message, signature),
            #[allow(unreachable_patterns)]
            _ => Err(self.algorithm().unsupported_error().into()),
        }
    }
}

#[cfg(feature = "dsa")]
impl Signer<Signature> for DsaKeypair {
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        let signature = dsa::SigningKey::try_from(self)?
            .try_sign_digest(Sha1::new_with_prefix(message))
            .map_err(|_| signature::Error::new())?;

        // Encode the format specified in RFC4253 section 6.6: two raw 80-bit integers concatenated
        let mut data = Vec::new();

        for component in [signature.r(), signature.s()] {
            let mut bytes = component.to_bytes_be();
            let pad_len = (DSA_SIGNATURE_SIZE / 2).saturating_sub(bytes.len());
            data.extend(iter::repeat(0).take(pad_len));
            data.append(&mut bytes);
        }

        debug_assert_eq!(data.len(), DSA_SIGNATURE_SIZE);

        Ok(Signature {
            algorithm: Algorithm::Dsa,
            data,
        })
    }
}

#[cfg(feature = "dsa")]
impl Verifier<Signature> for DsaPublicKey {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        match signature.algorithm {
            Algorithm::Dsa => {
                let data = signature.data.as_slice();
                if data.len() != DSA_SIGNATURE_SIZE {
                    return Err(signature::Error::new());
                }
                let (r, s) = data.split_at(DSA_SIGNATURE_SIZE / 2);
                let signature = dsa::Signature::from_components(
                    BigUint::from_bytes_be(r),
                    BigUint::from_bytes_be(s),
                )?;
                dsa::VerifyingKey::try_from(self)?
                    .verify_digest(Sha1::new_with_prefix(message), &signature)
                    .map_err(|_| signature::Error::new())
            }
            _ => Err(signature.algorithm().unsupported_error().into()),
        }
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<Signature> for ed25519_dalek::Signature {
    type Error = Error;

    fn try_from(signature: Signature) -> Result<ed25519_dalek::Signature> {
        ed25519_dalek::Signature::try_from(&signature)
    }
}

#[cfg(feature = "ed25519")]
impl TryFrom<&Signature> for ed25519_dalek::Signature {
    type Error = Error;

    fn try_from(signature: &Signature) -> Result<ed25519_dalek::Signature> {
        match signature.algorithm {
            Algorithm::Ed25519 | Algorithm::SkEd25519 => {
                Ok(ed25519_dalek::Signature::try_from(signature.as_bytes())?)
            }
            _ => Err(Error::AlgorithmUnknown),
        }
    }
}

#[cfg(feature = "ed25519")]
impl Signer<Signature> for Ed25519Keypair {
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        let signature = ed25519_dalek::SigningKey::try_from(self)?.sign(message);

        Ok(Signature {
            algorithm: Algorithm::Ed25519,
            data: signature.to_vec(),
        })
    }
}

#[cfg(feature = "ed25519")]
impl Verifier<Signature> for Ed25519PublicKey {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        let signature = ed25519_dalek::Signature::try_from(signature)?;
        ed25519_dalek::VerifyingKey::try_from(self)?.verify(message, &signature)
    }
}

#[cfg(feature = "ed25519")]
impl Verifier<Signature> for public::SkEd25519 {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        let (signature, flags_and_counter) = split_sk_signature(signature)?;
        let signature = ed25519_dalek::Signature::try_from(signature)?;
        ed25519_dalek::VerifyingKey::try_from(self.public_key())?.verify(
            &make_sk_signed_data(self.application(), flags_and_counter, message),
            &signature,
        )
    }
}

#[cfg(feature = "p256")]
impl Verifier<Signature> for public::SkEcdsaSha2NistP256 {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        let (signature_bytes, flags_and_counter) = split_sk_signature(signature)?;
        let signature = p256_signature_from_openssh_bytes(signature_bytes)?;
        p256::ecdsa::VerifyingKey::from_encoded_point(self.ec_point())?.verify(
            &make_sk_signed_data(self.application(), flags_and_counter, message),
            &signature,
        )
    }
}

#[cfg(any(feature = "p256", feature = "ed25519"))]
fn make_sk_signed_data(application: &str, flags_and_counter: &[u8], message: &[u8]) -> Vec<u8> {
    const SHA256_OUTPUT_LENGTH: usize = 32;
    const SIGNED_SK_DATA_LENGTH: usize = 2 * SHA256_OUTPUT_LENGTH + SK_SIGNATURE_TRAILER_SIZE;

    let mut signed_data = Vec::with_capacity(SIGNED_SK_DATA_LENGTH);
    signed_data.extend(Sha256::digest(application));
    signed_data.extend(flags_and_counter);
    signed_data.extend(Sha256::digest(message));
    signed_data
}

#[cfg(any(feature = "p256", feature = "ed25519"))]
fn split_sk_signature(signature: &Signature) -> Result<(&[u8], &[u8])> {
    let signature_bytes = signature.as_bytes();
    let signature_len = signature_bytes
        .len()
        .checked_sub(SK_SIGNATURE_TRAILER_SIZE)
        .ok_or(Error::Encoding(encoding::Error::Length))?;
    Ok((
        &signature_bytes[..signature_len],
        &signature_bytes[signature_len..],
    ))
}

macro_rules! impl_signature_for_curve {
    ($krate:ident, $feature:expr, $curve:ident, $size:expr) => {
        #[cfg(feature = $feature)]
        impl TryFrom<$krate::ecdsa::Signature> for Signature {
            type Error = Error;

            fn try_from(signature: $krate::ecdsa::Signature) -> Result<Signature> {
                Signature::try_from(&signature)
            }
        }

        #[cfg(feature = $feature)]
        impl TryFrom<&$krate::ecdsa::Signature> for Signature {
            type Error = Error;

            fn try_from(signature: &$krate::ecdsa::Signature) -> Result<Signature> {
                let (r, s) = signature.split_bytes();

                #[allow(clippy::arithmetic_side_effects)]
                let mut data = Vec::with_capacity($size * 2 + 4 * 2 + 2);

                Mpint::from_positive_bytes(&r)?.encode(&mut data)?;
                Mpint::from_positive_bytes(&s)?.encode(&mut data)?;

                Ok(Signature {
                    algorithm: Algorithm::Ecdsa {
                        curve: EcdsaCurve::$curve,
                    },
                    data,
                })
            }
        }

        #[cfg(feature = $feature)]
        impl TryFrom<Signature> for $krate::ecdsa::Signature {
            type Error = Error;

            fn try_from(signature: Signature) -> Result<$krate::ecdsa::Signature> {
                $krate::ecdsa::Signature::try_from(&signature)
            }
        }

        #[cfg(feature = $feature)]
        impl Signer<Signature> for EcdsaPrivateKey<$size> {
            fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
                let signing_key = $krate::ecdsa::SigningKey::from_slice(self.as_ref())?;
                let signature: $krate::ecdsa::Signature = signing_key.try_sign(message)?;
                Ok(signature.try_into()?)
            }
        }
    };
}

impl_signature_for_curve!(p256, "p256", NistP256, 32);
impl_signature_for_curve!(p384, "p384", NistP384, 48);
impl_signature_for_curve!(p521, "p521", NistP521, 66);

/// Build a generic sized object from a `u8` iterator, with leading zero padding
#[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
fn zero_pad_field_bytes<B: FromIterator<u8> + Copy>(m: Mpint) -> Option<B> {
    use core::mem::size_of;

    let bytes = m.as_positive_bytes()?;
    size_of::<B>()
        .checked_sub(bytes.len())
        .map(|i| B::from_iter(iter::repeat(0u8).take(i).chain(bytes.iter().cloned())))
}

#[cfg(feature = "p256")]
impl TryFrom<&Signature> for p256::ecdsa::Signature {
    type Error = Error;

    fn try_from(signature: &Signature) -> Result<p256::ecdsa::Signature> {
        match signature.algorithm {
            Algorithm::Ecdsa {
                curve: EcdsaCurve::NistP256,
            } => p256_signature_from_openssh_bytes(signature.as_bytes()),
            _ => Err(signature.algorithm.clone().unsupported_error()),
        }
    }
}
#[cfg(feature = "p256")]
fn p256_signature_from_openssh_bytes(mut signature_bytes: &[u8]) -> Result<p256::ecdsa::Signature> {
    let reader = &mut signature_bytes;
    let r = Mpint::decode(reader)?;
    let s = Mpint::decode(reader)?;

    match (
        zero_pad_field_bytes::<p256::FieldBytes>(r),
        zero_pad_field_bytes::<p256::FieldBytes>(s),
    ) {
        (Some(r), Some(s)) => Ok(p256::ecdsa::Signature::from_scalars(r, s)?),
        _ => Err(Error::Crypto),
    }
}

#[cfg(feature = "p384")]
impl TryFrom<&Signature> for p384::ecdsa::Signature {
    type Error = Error;

    fn try_from(signature: &Signature) -> Result<p384::ecdsa::Signature> {
        match signature.algorithm {
            Algorithm::Ecdsa {
                curve: EcdsaCurve::NistP384,
            } => {
                let reader = &mut signature.as_bytes();
                let r = Mpint::decode(reader)?;
                let s = Mpint::decode(reader)?;

                match (
                    zero_pad_field_bytes::<p384::FieldBytes>(r),
                    zero_pad_field_bytes::<p384::FieldBytes>(s),
                ) {
                    (Some(r), Some(s)) => Ok(p384::ecdsa::Signature::from_scalars(r, s)?),
                    _ => Err(Error::Crypto),
                }
            }
            _ => Err(signature.algorithm.clone().unsupported_error()),
        }
    }
}

#[cfg(feature = "p521")]
impl TryFrom<&Signature> for p521::ecdsa::Signature {
    type Error = Error;

    fn try_from(signature: &Signature) -> Result<p521::ecdsa::Signature> {
        match signature.algorithm {
            Algorithm::Ecdsa {
                curve: EcdsaCurve::NistP521,
            } => {
                let reader = &mut signature.as_bytes();
                let r = Mpint::decode(reader)?;
                let s = Mpint::decode(reader)?;

                match (
                    zero_pad_field_bytes::<p521::FieldBytes>(r),
                    zero_pad_field_bytes::<p521::FieldBytes>(s),
                ) {
                    (Some(r), Some(s)) => Ok(p521::ecdsa::Signature::from_scalars(r, s)?),
                    _ => Err(Error::Crypto),
                }
            }
            _ => Err(signature.algorithm.clone().unsupported_error()),
        }
    }
}

#[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
impl Signer<Signature> for EcdsaKeypair {
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        match self {
            #[cfg(feature = "p256")]
            Self::NistP256 { private, .. } => private.try_sign(message),
            #[cfg(feature = "p384")]
            Self::NistP384 { private, .. } => private.try_sign(message),
            #[cfg(feature = "p521")]
            Self::NistP521 { private, .. } => private.try_sign(message),
            #[cfg(not(all(feature = "p256", feature = "p384", feature = "p521")))]
            _ => Err(self.algorithm().unsupported_error().into()),
        }
    }
}

#[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
impl Verifier<Signature> for EcdsaPublicKey {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        match signature.algorithm {
            Algorithm::Ecdsa { curve } => match curve {
                #[cfg(feature = "p256")]
                EcdsaCurve::NistP256 => {
                    let verifying_key = p256::ecdsa::VerifyingKey::try_from(self)?;
                    let signature = p256::ecdsa::Signature::try_from(signature)?;
                    verifying_key.verify(message, &signature)
                }

                #[cfg(feature = "p384")]
                EcdsaCurve::NistP384 => {
                    let verifying_key = p384::ecdsa::VerifyingKey::try_from(self)?;
                    let signature = p384::ecdsa::Signature::try_from(signature)?;
                    verifying_key.verify(message, &signature)
                }

                #[cfg(feature = "p521")]
                EcdsaCurve::NistP521 => {
                    let verifying_key = p521::ecdsa::VerifyingKey::try_from(self)?;
                    let signature = p521::ecdsa::Signature::try_from(signature)?;
                    verifying_key.verify(message, &signature)
                }

                #[cfg(not(all(feature = "p256", feature = "p384", feature = "p521")))]
                _ => Err(signature.algorithm().unsupported_error().into()),
            },
            _ => Err(signature.algorithm().unsupported_error().into()),
        }
    }
}

#[cfg(feature = "rsa")]
impl Signer<Signature> for RsaKeypair {
    fn try_sign(&self, message: &[u8]) -> signature::Result<Signature> {
        let data = rsa::pkcs1v15::SigningKey::<Sha512>::try_from(self)?
            .try_sign(message)
            .map_err(|_| signature::Error::new())?;

        Ok(Signature {
            algorithm: Algorithm::Rsa {
                hash: Some(HashAlg::Sha512),
            },
            data: data.to_vec(),
        })
    }
}

#[cfg(feature = "rsa")]
impl Verifier<Signature> for RsaPublicKey {
    fn verify(&self, message: &[u8], signature: &Signature) -> signature::Result<()> {
        match signature.algorithm {
            Algorithm::Rsa { hash: Some(hash) } => {
                let signature = rsa::pkcs1v15::Signature::try_from(signature.data.as_ref())?;

                match hash {
                    HashAlg::Sha256 => rsa::pkcs1v15::VerifyingKey::<Sha256>::try_from(self)?
                        .verify(message, &signature)
                        .map_err(|_| signature::Error::new()),
                    HashAlg::Sha512 => rsa::pkcs1v15::VerifyingKey::<Sha512>::try_from(self)?
                        .verify(message, &signature)
                        .map_err(|_| signature::Error::new()),
                }
            }
            _ => Err(signature.algorithm().unsupported_error().into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::Signature;
    use crate::{Algorithm, EcdsaCurve, HashAlg};
    use alloc::vec::Vec;
    use encoding::Encode;
    use hex_literal::hex;

    #[cfg(feature = "ed25519")]
    use {
        super::Ed25519Keypair,
        signature::{Signer, Verifier},
    };

    #[cfg(feature = "p256")]
    use super::{zero_pad_field_bytes, Mpint};

    const DSA_SIGNATURE: &[u8] = &hex!("000000077373682d6473730000002866725bf3c56100e975e21fff28a60f73717534d285ea3e1beefc2891f7189d00bd4d94627e84c55c");
    const ECDSA_SHA2_P256_SIGNATURE: &[u8] = &hex!("0000001365636473612d736861322d6e6973747032353600000048000000201298ab320720a32139cda8a40c97a13dc54ce032ea3c6f09ea9e87501e48fa1d0000002046e4ac697a6424a9870b9ef04ca1182cd741965f989bd1f1f4a26fd83cf70348");
    const ED25519_SIGNATURE: &[u8] = &hex!("0000000b7373682d65643235353139000000403d6b9906b76875aef1e7b2f1e02078a94f439aebb9a4734da1a851a81e22ce0199bbf820387a8de9c834c9c3cc778d9972dcbe70f68d53cc6bc9e26b02b46d04");
    const SK_ED25519_SIGNATURE: &[u8] = &hex!("0000001a736b2d7373682d65643235353139406f70656e7373682e636f6d000000402f5670b6f93465d17423878a74084bf331767031ed240c627c8eb79ab8fa1b935a1fd993f52f5a13fec1797f8a434f943a6096246aea8dd5c8aa922cba3d95060100000009");
    const RSA_SHA512_SIGNATURE: &[u8] = &hex!("0000000c7273612d736861322d3531320000018085a4ad1a91a62c00c85de7bb511f38088ff2bce763d76f4786febbe55d47624f9e2cffce58a680183b9ad162c7f0191ea26cab001ac5f5055743eced58e9981789305c208fc98d2657954e38eb28c7e7f3fbe92393a14324ed77aebb772a41aa7a107b38cb9bd1d9ad79b275135d1d7e019bb1d56d74f2450be6db0771f48f6707d3fcf9789592ca2e55595acc16b6e8d0139b56c5d1360b3a1e060f4151a3d7841df2c2a8c94d6f8a1bf633165ee0bcadac5642763df0dd79d3235ae5506595145f199d8abe8f9980411bf70a16e30f273736324d047043317044c36374d6a5ed34cac251e01c6795e4578393f9090bf4ae3e74a0009275a197315fc9c62f1c9aec1ba3b2d37c3b207e5500df19e090e7097ebc038fb9c9e35aea9161479ba6b5190f48e89e1abe51e8ec0e120ef89776e129687ca52d1892c8e88e6ef062a7d96b8a87682ca6a42ff1df0cdf5815c3645aeed7267ca7093043db0565e0f109b796bf117b9d2bb6d6debc0c67a4c9fb3aae3e29b00c7bd70f6c11cf53c295ff");

    /// Example test vector for signing.
    #[cfg(feature = "ed25519")]
    const EXAMPLE_MSG: &[u8] = b"Hello, world!";

    #[cfg(feature = "p256")]
    #[test]
    fn convert_ecdsa_sha2_p256() {
        let p256_signature = p256::ecdsa::Signature::try_from(hex!("00000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001").as_ref()).unwrap();
        let _ssh_signature = Signature::try_from(p256_signature).unwrap();
    }

    #[cfg(feature = "p256")]
    #[test]
    fn zero_pad_field_bytes_p256() {
        let i = Mpint::from_bytes(&hex!(
            "1122334455667788112233445566778811223344556677881122334455667788"
        ))
        .unwrap();
        let fb = zero_pad_field_bytes::<p256::FieldBytes>(i);
        assert!(fb.is_some());

        // too long
        let i = Mpint::from_bytes(&hex!(
            "991122334455667788112233445566778811223344556677881122334455667788"
        ))
        .unwrap();
        let fb = zero_pad_field_bytes::<p256::FieldBytes>(i);
        assert!(fb.is_none());

        // short is okay
        let i = Mpint::from_bytes(&hex!(
            "22334455667788112233445566778811223344556677881122334455667788"
        ))
        .unwrap();
        let fb = zero_pad_field_bytes::<p256::FieldBytes>(i)
            .expect("failed to build FieldBytes from short hex string");
        assert_eq!(fb[0], 0x00);
        assert_eq!(fb[1], 0x22);
    }

    #[test]
    fn decode_dsa() {
        let signature = Signature::try_from(DSA_SIGNATURE).unwrap();
        assert_eq!(Algorithm::Dsa, signature.algorithm());
    }

    #[test]
    fn decode_ecdsa_sha2_p256() {
        let signature = Signature::try_from(ECDSA_SHA2_P256_SIGNATURE).unwrap();
        assert_eq!(
            Algorithm::Ecdsa {
                curve: EcdsaCurve::NistP256
            },
            signature.algorithm()
        );
    }

    #[test]
    fn decode_ed25519() {
        let signature = Signature::try_from(ED25519_SIGNATURE).unwrap();
        assert_eq!(Algorithm::Ed25519, signature.algorithm());
    }

    #[test]
    fn decode_sk_ed25519() {
        let signature = Signature::try_from(SK_ED25519_SIGNATURE).unwrap();
        assert_eq!(Algorithm::SkEd25519, signature.algorithm());
    }

    #[test]
    fn decode_rsa() {
        let signature = Signature::try_from(RSA_SHA512_SIGNATURE).unwrap();
        assert_eq!(
            Algorithm::Rsa {
                hash: Some(HashAlg::Sha512)
            },
            signature.algorithm()
        );
    }

    #[test]
    fn encode_dsa() {
        let signature = Signature::try_from(DSA_SIGNATURE).unwrap();

        let mut result = Vec::new();
        signature.encode(&mut result).unwrap();
        assert_eq!(DSA_SIGNATURE, &result);
    }

    #[test]
    fn encode_ecdsa_sha2_p256() {
        let signature = Signature::try_from(ECDSA_SHA2_P256_SIGNATURE).unwrap();

        let mut result = Vec::new();
        signature.encode(&mut result).unwrap();
        assert_eq!(ECDSA_SHA2_P256_SIGNATURE, &result);
    }

    #[test]
    fn encode_ed25519() {
        let signature = Signature::try_from(ED25519_SIGNATURE).unwrap();

        let mut result = Vec::new();
        signature.encode(&mut result).unwrap();
        assert_eq!(ED25519_SIGNATURE, &result);
    }

    #[test]
    fn encode_sk_ed25519() {
        let signature = Signature::try_from(SK_ED25519_SIGNATURE).unwrap();

        let mut result = Vec::new();
        signature.encode(&mut result).unwrap();
        assert_eq!(SK_ED25519_SIGNATURE, &result);
    }

    #[cfg(feature = "dsa")]
    #[test]
    fn try_sign_and_verify_dsa() {
        use super::{DsaKeypair, DSA_SIGNATURE_SIZE};
        use encoding::Decode as _;
        use signature::{Signer as _, Verifier as _};

        fn check_signature_component_lens(
            keypair: &DsaKeypair,
            data: &[u8],
            r_len: usize,
            s_len: usize,
        ) {
            use sha1::{Digest as _, Sha1};
            use signature::DigestSigner as _;

            let signature = dsa::SigningKey::try_from(keypair)
                .expect("valid DSA signing key")
                .try_sign_digest(Sha1::new_with_prefix(data))
                .expect("valid DSA signature");

            let r = signature.r().to_bytes_be();
            assert_eq!(
                r.len(),
                r_len,
                "dsa signature component `r` has len {} != {}",
                r.len(),
                r_len
            );
            let s = signature.s().to_bytes_be();
            assert_eq!(
                s.len(),
                s_len,
                "dsa signature component `s` has len {} != {}",
                s.len(),
                s_len
            );
        }

        let keypair = hex!("0000008100c161fb30c9e4e3602c8510f93bbd48d813da845dfcc75f3696e440cd019d609809608cd592b8430db901d7b43740740045b547c60fb035d69f9c64d3dfbfb13bb3edd8ccfdd44705739a639eb70f4aed16b0b8355de1b21cd9d442eff250895573a8af7ce2fb71fb062e887482dab5c68139845fb8afafc5f3819dc782920d510000001500f3fb6762430332bd5950edc5cd1ae6f17b88514f0000008061ef1394d864905e8efec3b610b7288a6522893af2a475f910796e0de47c8b065d365e942e80e471d1e6d4abdee1d3d3ede7103c6996432f1a9f9a671a31388672d63555077911fc69e641a997087260d22cdbf4965aa64bb382204f88987890ec225a5a7723a977dc1ecc5e04cf678f994692b20470adbf697489f800817b920000008100a9a6f1b65fc724d65df7441908b34af66489a4a3872cbbba25ea1bcfc83f25c4af1a62e339eefc814907cfaf0cb6d2d16996212a32a27a63013f01c57d0630f0be16c8c69d16fc25438e613b904b98aeb3e7c356fa8e75ee1d474c9f82f1280c5a6c18e9e607fcf7586eefb75ea9399da893b807375ac1396fd586bf277161980000001500ced95f1c7bbb39be4987837ad1f71be31bb7b0d9");
        let keypair = DsaKeypair::decode(&mut &keypair[..]).expect("properly encoded DSA keypair");

        let data = hex!("F0000040713d5f6fffe0000e6421ab0b3a69774d3da02fd72b107d6b32b6dad7c1660bbf507bf3eac3304cc5058f7e6f81b04239b8471459b1f3b387e2626f7eb8f6bcdd3200000006626c616465320000000e7373682d636f6e6e656374696f6e00000009686f73746261736564000000077373682d647373000001b2000000077373682d6473730000008100c161fb30c9e4e3602c8510f93bbd48d813da845dfcc75f3696e440cd019d609809608cd592b8430db901d7b43740740045b547c60fb035d69f9c64d3dfbfb13bb3edd8ccfdd44705739a639eb70f4aed16b0b8355de1b21cd9d442eff250895573a8af7ce2fb71fb062e887482dab5c68139845fb8afafc5f3819dc782920d510000001500f3fb6762430332bd5950edc5cd1ae6f17b88514f0000008061ef1394d864905e8efec3b610b7288a6522893af2a475f910796e0de47c8b065d365e942e80e471d1e6d4abdee1d3d3ede7103c6996432f1a9f9a671a31388672d63555077911fc69e641a997087260d22cdbf4965aa64bb382204f88987890ec225a5a7723a977dc1ecc5e04cf678f994692b20470adbf697489f800817b920000008100a9a6f1b65fc724d65df7441908b34af66489a4a3872cbbba25ea1bcfc83f25c4af1a62e339eefc814907cfaf0cb6d2d16996212a32a27a63013f01c57d0630f0be16c8c69d16fc25438e613b904b98aeb3e7c356fa8e75ee1d474c9f82f1280c5a6c18e9e607fcf7586eefb75ea9399da893b807375ac1396fd586bf2771619800000015746f6d61746f7373682e6c6f63616c646f6d61696e00000009746f6d61746f737368");
        check_signature_component_lens(
            &keypair,
            &data,
            DSA_SIGNATURE_SIZE / 2,
            DSA_SIGNATURE_SIZE / 2,
        );
        let signature = keypair.try_sign(&data[..]).expect("dsa try_sign is ok");
        keypair
            .public
            .verify(&data[..], &signature)
            .expect("dsa verify is ok");

        let data = hex!("00000040713d5f6fffe0000e6421ab0b3a69774d3da02fd72b107d6b32b6dad7c1660bbf507bf3eac3304cc5058f7e6f81b04239b8471459b1f3b387e2626f7eb8f6bcdd3200000006626c616465320000000e7373682d636f6e6e656374696f6e00000009686f73746261736564000000077373682d647373000001b2000000077373682d6473730000008100c161fb30c9e4e3602c8510f93bbd48d813da845dfcc75f3696e440cd019d609809608cd592b8430db901d7b43740740045b547c60fb035d69f9c64d3dfbfb13bb3edd8ccfdd44705739a639eb70f4aed16b0b8355de1b21cd9d442eff250895573a8af7ce2fb71fb062e887482dab5c68139845fb8afafc5f3819dc782920d510000001500f3fb6762430332bd5950edc5cd1ae6f17b88514f0000008061ef1394d864905e8efec3b610b7288a6522893af2a475f910796e0de47c8b065d365e942e80e471d1e6d4abdee1d3d3ede7103c6996432f1a9f9a671a31388672d63555077911fc69e641a997087260d22cdbf4965aa64bb382204f88987890ec225a5a7723a977dc1ecc5e04cf678f994692b20470adbf697489f800817b920000008100a9a6f1b65fc724d65df7441908b34af66489a4a3872cbbba25ea1bcfc83f25c4af1a62e339eefc814907cfaf0cb6d2d16996212a32a27a63013f01c57d0630f0be16c8c69d16fc25438e613b904b98aeb3e7c356fa8e75ee1d474c9f82f1280c5a6c18e9e607fcf7586eefb75ea9399da893b807375ac1396fd586bf2771619800000015746f6d61746f7373682e6c6f63616c646f6d61696e00000009746f6d61746f737368");
        // verify that this data produces signature with `r` integer component that is less than 160 bits/20 bytes.
        check_signature_component_lens(
            &keypair,
            &data,
            DSA_SIGNATURE_SIZE / 2 - 1,
            DSA_SIGNATURE_SIZE / 2,
        );
        let signature = keypair
            .try_sign(&data[..])
            .expect("dsa try_sign for r.len() == 19 is ok");
        keypair
            .public
            .verify(&data[..], &signature)
            .expect("dsa verify is ok");
    }

    #[cfg(feature = "ed25519")]
    #[test]
    fn sign_and_verify_ed25519() {
        let keypair = Ed25519Keypair::from_seed(&[42; 32]);
        let signature = keypair.sign(EXAMPLE_MSG);
        assert!(keypair.public.verify(EXAMPLE_MSG, &signature).is_ok());
    }

    #[test]
    fn placeholder() {
        assert!(!Signature::try_from(ED25519_SIGNATURE)
            .unwrap()
            .is_placeholder());

        let placeholder = Signature::placeholder();
        assert!(placeholder.is_placeholder());

        let mut writer = Vec::new();
        assert_eq!(
            placeholder.encode(&mut writer),
            Err(encoding::Error::Length)
        );
    }
}
