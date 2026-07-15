//! Decrypt-only compatibility for the PKCS#8 PBES2 surface accepted by
//! SSHJ 0.40.0 on the repository's pinned JDK 21 runtime.
//!
//! The allowlist is intentionally narrower than the general PKCS#5 surface:
//! PBKDF2 with one of JDK 21's seven HMAC-SHA PRFs, followed by AES-128-CBC
//! or AES-256-CBC. The module does not expose encryption or key operations.

use aes::{Aes128, Aes256};
use cbc::Decryptor;
use cipher::{BlockDecryptMut, KeyIvInit, block_padding::Pkcs7};
use keyguard_crypto_sensitive::{DigestAlgorithm, pbkdf2_hmac};
use pkcs8::{
    AlgorithmIdentifierRef, ObjectIdentifier,
    der::{
        Decode as _,
        asn1::{AnyRef, OctetStringRef},
    },
};
use thiserror::Error;
use zeroize::Zeroizing;

const PBES2_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.5.13");
const PBKDF2_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.5.12");
const HMAC_SHA1_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.7");
const HMAC_SHA224_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.8");
const HMAC_SHA256_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.9");
const HMAC_SHA384_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.10");
const HMAC_SHA512_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.11");
const HMAC_SHA512_224_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.12");
const HMAC_SHA512_256_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.2.13");
const AES128_CBC_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("2.16.840.1.101.3.4.1.2");
const AES256_CBC_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("2.16.840.1.101.3.4.1.42");
const CLASSIC_PBE_OIDS: [ObjectIdentifier; 7] = [
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.5.3"),
    ObjectIdentifier::new_unwrap("1.3.6.1.4.1.42.2.19.1"),
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.12.1.3"),
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.12.1.6"),
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.12.1.5"),
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.12.1.2"),
    ObjectIdentifier::new_unwrap("1.2.840.113549.1.12.1.1"),
];

const AES_BLOCK_BYTES: usize = 16;
const AES128_KEY_BYTES: usize = 16;
const AES256_KEY_BYTES: usize = 32;
const MAX_SALT_BYTES: usize = 1024;
// This is over 244 times the frozen JDK 21 corpus count (4,096), while
// bounding a user-selected local import below an unreviewable CPU workload.
// Raising it requires fixed-host benchmark and resource-policy review.
const MAX_ITERATION_COUNT: u32 = 1_000_000;

/// Expected failure classes for the JDK 21 PBES2 compatibility surface.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub(crate) enum Pbes2Error {
    /// The container selects a syntactically valid algorithm outside the
    /// frozen SSHJ/JDK 21 allowlist.
    #[error("unsupported PKCS#8 encryption algorithm")]
    UnsupportedAlgorithm,
    /// The DER or algorithm parameters are structurally invalid.
    #[error("malformed PKCS#8 encrypted key")]
    Malformed,
    /// AES-CBC padding rejected the passphrase or ciphertext.
    #[error("PKCS#8 decryption failed")]
    InvalidPassphrase,
    /// An explicit KDF or input bound was exceeded.
    #[error("PKCS#8 decryption resource limit exceeded")]
    ResourceLimit,
    /// The sensitive KDF backend failed unexpectedly.
    #[error("PKCS#8 sensitive backend failure")]
    Backend,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Pbkdf2Prf {
    Sha1,
    Sha224,
    Sha256,
    Sha384,
    Sha512,
    Sha512_224,
    Sha512_256,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AesCipher {
    Aes128,
    Aes256,
}

impl AesCipher {
    const fn key_bytes(self) -> usize {
        match self {
            Self::Aes128 => AES128_KEY_BYTES,
            Self::Aes256 => AES256_KEY_BYTES,
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct Pbes2Params<'a> {
    salt: &'a [u8],
    iteration_count: u32,
    prf: Pbkdf2Prf,
    cipher: AesCipher,
    iv: &'a [u8],
    encrypted_data: &'a [u8],
}

#[derive(Clone, Copy, Debug)]
struct Pbkdf2Params<'a> {
    salt: &'a [u8],
    iteration_count: u32,
    encoded_key_length: Option<u32>,
    prf: Pbkdf2Prf,
}

/// Decrypts the exact PBES2/PBKDF2/AES-CBC matrix accepted by SSHJ through
/// JDK 21's JSSE/JCE providers.
pub(crate) fn decrypt_jdk21_encrypted_pkcs8(
    encoded: &[u8],
    passphrase_utf8: &[u8],
) -> Result<Zeroizing<Vec<u8>>, Pbes2Error> {
    let params = parse_encrypted_private_key_info(encoded)?;
    if params.iteration_count > MAX_ITERATION_COUNT {
        return Err(Pbes2Error::ResourceLimit);
    }

    let digest = match params.prf {
        Pbkdf2Prf::Sha1 => DigestAlgorithm::Sha1,
        Pbkdf2Prf::Sha224 => DigestAlgorithm::Sha224,
        Pbkdf2Prf::Sha256 => DigestAlgorithm::Sha256,
        Pbkdf2Prf::Sha384 => DigestAlgorithm::Sha384,
        Pbkdf2Prf::Sha512 => DigestAlgorithm::Sha512,
        Pbkdf2Prf::Sha512_224 => DigestAlgorithm::Sha512_224,
        Pbkdf2Prf::Sha512_256 => DigestAlgorithm::Sha512_256,
    };
    let mut key = Zeroizing::new(vec![0_u8; params.cipher.key_bytes()]);
    pbkdf2_hmac(
        digest,
        passphrase_utf8,
        params.salt,
        params.iteration_count,
        &mut key,
    )
    .map_err(|_| Pbes2Error::Backend)?;

    let mut plaintext = Zeroizing::new(params.encrypted_data.to_vec());
    let unpadded_length = match params.cipher {
        AesCipher::Aes128 => decrypt_aes_cbc::<Aes128>(&key, params.iv, &mut plaintext)?,
        AesCipher::Aes256 => decrypt_aes_cbc::<Aes256>(&key, params.iv, &mut plaintext)?,
    };
    plaintext.truncate(unpadded_length);
    Ok(plaintext)
}

fn decrypt_aes_cbc<C>(key: &[u8], iv: &[u8], ciphertext: &mut [u8]) -> Result<usize, Pbes2Error>
where
    C: cipher::BlockCipher + cipher::BlockDecrypt + cipher::KeyInit,
{
    if ciphertext.is_empty() || !ciphertext.len().is_multiple_of(AES_BLOCK_BYTES) {
        return Err(Pbes2Error::InvalidPassphrase);
    }
    let decryptor = Decryptor::<C>::new_from_slices(key, iv).map_err(|_| Pbes2Error::Malformed)?;
    decryptor
        .decrypt_padded_mut::<Pkcs7>(ciphertext)
        .map(|plaintext| plaintext.len())
        .map_err(|_| Pbes2Error::InvalidPassphrase)
}

fn parse_encrypted_private_key_info(encoded: &[u8]) -> Result<Pbes2Params<'_>, Pbes2Error> {
    let outer = AnyRef::from_der(encoded).map_err(|_| Pbes2Error::Malformed)?;
    let (algorithm, encrypted_data) = outer
        .sequence(|reader| {
            let algorithm = AlgorithmIdentifierRef::decode(reader)?;
            let encrypted_data = OctetStringRef::decode(reader)?.as_bytes();
            Ok((algorithm, encrypted_data))
        })
        .map_err(|_| Pbes2Error::Malformed)?;
    if algorithm.oid != PBES2_OID {
        return Err(if CLASSIC_PBE_OIDS.contains(&algorithm.oid) {
            // SSHJ 0.40.0 reached JDK 21's classic-PBE name/parameter path
            // for these algorithms and surfaced the failure as malformed
            // key material rather than as an unsupported algorithm.
            Pbes2Error::Malformed
        } else {
            Pbes2Error::UnsupportedAlgorithm
        });
    }
    let pbes2 = algorithm.parameters.ok_or(Pbes2Error::Malformed)?;
    let (kdf, encryption) = pbes2
        .sequence(|reader| {
            Ok((
                AlgorithmIdentifierRef::decode(reader)?,
                AlgorithmIdentifierRef::decode(reader)?,
            ))
        })
        .map_err(|_| Pbes2Error::Malformed)?;

    let kdf = parse_pbkdf2(kdf)?;
    let (cipher, iv) = parse_encryption(encryption)?;
    if kdf
        .encoded_key_length
        .is_some_and(|length| length != cipher.key_bytes() as u32)
    {
        return Err(Pbes2Error::Malformed);
    }
    Ok(Pbes2Params {
        salt: kdf.salt,
        iteration_count: kdf.iteration_count,
        prf: kdf.prf,
        cipher,
        iv,
        encrypted_data,
    })
}

fn parse_pbkdf2(algorithm: AlgorithmIdentifierRef<'_>) -> Result<Pbkdf2Params<'_>, Pbes2Error> {
    if algorithm.oid != PBKDF2_OID {
        return Err(Pbes2Error::UnsupportedAlgorithm);
    }
    let parameters = algorithm.parameters.ok_or(Pbes2Error::Malformed)?;
    let (salt, iteration_count, encoded_key_length, prf) = parameters
        .sequence(|reader| {
            Ok((
                OctetStringRef::decode(reader)?.as_bytes(),
                u32::decode(reader)?,
                Option::<u32>::decode(reader)?,
                Option::<AlgorithmIdentifierRef<'_>>::decode(reader)?,
            ))
        })
        .map_err(|_| Pbes2Error::Malformed)?;
    validate_kdf_bounds(salt, iteration_count)?;
    let prf = match prf {
        Some(prf) => parse_prf(prf)?,
        None => Pbkdf2Prf::Sha1,
    };
    Ok(Pbkdf2Params {
        salt,
        iteration_count,
        encoded_key_length,
        prf,
    })
}

fn validate_kdf_bounds(salt: &[u8], iteration_count: u32) -> Result<(), Pbes2Error> {
    if salt.is_empty() || iteration_count == 0 {
        return Err(Pbes2Error::Malformed);
    }
    if salt.len() > MAX_SALT_BYTES || iteration_count > MAX_ITERATION_COUNT {
        return Err(Pbes2Error::ResourceLimit);
    }
    Ok(())
}

fn parse_prf(algorithm: AlgorithmIdentifierRef<'_>) -> Result<Pbkdf2Prf, Pbes2Error> {
    if algorithm
        .parameters
        .is_some_and(|parameters| !parameters.is_null())
    {
        return Err(Pbes2Error::Malformed);
    }
    match algorithm.oid {
        HMAC_SHA1_OID => Ok(Pbkdf2Prf::Sha1),
        HMAC_SHA224_OID => Ok(Pbkdf2Prf::Sha224),
        HMAC_SHA256_OID => Ok(Pbkdf2Prf::Sha256),
        HMAC_SHA384_OID => Ok(Pbkdf2Prf::Sha384),
        HMAC_SHA512_OID => Ok(Pbkdf2Prf::Sha512),
        HMAC_SHA512_224_OID => Ok(Pbkdf2Prf::Sha512_224),
        HMAC_SHA512_256_OID => Ok(Pbkdf2Prf::Sha512_256),
        _ => Err(Pbes2Error::UnsupportedAlgorithm),
    }
}

fn parse_encryption(
    algorithm: AlgorithmIdentifierRef<'_>,
) -> Result<(AesCipher, &[u8]), Pbes2Error> {
    let cipher = match algorithm.oid {
        AES128_CBC_OID => AesCipher::Aes128,
        AES256_CBC_OID => AesCipher::Aes256,
        _ => return Err(Pbes2Error::UnsupportedAlgorithm),
    };
    let iv = algorithm
        .parameters
        .ok_or(Pbes2Error::Malformed)?
        .decode_as::<OctetStringRef<'_>>()
        .map_err(|_| Pbes2Error::Malformed)?;
    if iv.as_bytes().len() != AES_BLOCK_BYTES {
        return Err(Pbes2Error::Malformed);
    }
    Ok((cipher, iv.as_bytes()))
}

#[cfg(test)]
mod tests {
    use pkcs8::der::Encode as _;

    use super::*;

    #[test]
    fn kdf_policy_rejects_oversized_salt_and_cpu_work() {
        assert_eq!(
            validate_kdf_bounds(&[0_u8; 16], MAX_ITERATION_COUNT),
            Ok(()),
        );
        assert_eq!(
            validate_kdf_bounds(&[0_u8; 16], MAX_ITERATION_COUNT + 1),
            Err(Pbes2Error::ResourceLimit),
        );
        assert_eq!(
            validate_kdf_bounds(&vec![0_u8; MAX_SALT_BYTES + 1], 1),
            Err(Pbes2Error::ResourceLimit),
        );
    }

    #[test]
    fn classic_pbe_outer_algorithms_keep_the_frozen_malformed_classification() {
        for oid in CLASSIC_PBE_OIDS {
            let encoded = encrypted_private_key_info(oid);
            assert!(
                matches!(
                    parse_encrypted_private_key_info(&encoded),
                    Err(Pbes2Error::Malformed)
                ),
                "{oid}",
            );
        }

        let unknown = encrypted_private_key_info(ObjectIdentifier::new_unwrap("1.2.3.4"));
        assert!(matches!(
            parse_encrypted_private_key_info(&unknown),
            Err(Pbes2Error::UnsupportedAlgorithm)
        ));
    }

    fn encrypted_private_key_info(oid: ObjectIdentifier) -> Vec<u8> {
        let mut algorithm = oid.to_der().expect("test OID DER");
        algorithm.extend_from_slice(&[0x05, 0x00]);
        let algorithm = short_sequence(&algorithm);

        let mut body = algorithm;
        body.extend_from_slice(&[0x04, 0x01, 0x00]);
        short_sequence(&body)
    }

    fn short_sequence(body: &[u8]) -> Vec<u8> {
        let mut encoded = vec![
            0x30,
            u8::try_from(body.len()).expect("test sequence uses short DER length"),
        ];
        encoded.extend_from_slice(body);
        encoded
    }
}
