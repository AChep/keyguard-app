//! SSH key generation, persistence, formatting, and agent signing.
//!
//! OpenSSH structures are handled by the reviewed vendored `ssh-key` crate.
//! Private RSA operations are delegated to the AWS-LC-only sensitive backend;
//! the RustCrypto RSA implementation is never enabled in this production graph.

use aws_lc_rs::{
    rand::{SecureRandom, SystemRandom},
    signature::{Ed25519KeyPair, KeyPair},
};
use base64ct::{Base64, Encoding};
use keyguard_crypto_sensitive::{
    RsaCrtComponents, RsaPrivateComponents, RsaSignatureHash, SensitiveRsaError,
    complete_rsa_pkcs1_der_from_components, generate_rsa_pkcs1_der, sign_rsa_pkcs1_v1_5,
};
use pkcs1::{RsaPrivateKey, RsaPublicKey as Pkcs1RsaPublicKey};
use pkcs8::{
    AlgorithmIdentifierRef, ObjectIdentifier, PrivateKeyInfo,
    der::{
        Decode as _, Encode as _,
        asn1::{AnyRef, OctetStringRef},
    },
};
use prost::Message;
use ssh_key::{
    Mpint, PrivateKey, PublicKey,
    private::{Ed25519Keypair, Ed25519PrivateKey, KeypairData, RsaKeypair},
    public::{Ed25519PublicKey, KeyData, RsaPublicKey},
};
use zeroize::{Zeroize, Zeroizing};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    primitives::PrimitiveError,
    protocol::{
        SshFormattedPrivateKey, SshKeyDescription, SshKeyExportCxfResult, SshKeyMaterial,
        SshKeyType, SshSignature,
    },
};

const MAX_KEY_BYTES: usize = 64 * 1024;
const MAX_SIGN_DATA_BYTES: usize = 1024 * 1024;
const RSA_ENCRYPTION_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.1.1");
const ED25519_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.3.101.112");

pub(crate) fn generate(key_type: SshKeyType, rsa_bits: u32) -> Result<Vec<u8>, PrimitiveError> {
    let material = match key_type {
        SshKeyType::Rsa => generate_rsa(rsa_bits)?,
        SshKeyType::Ed25519 if rsa_bits == 0 => generate_ed25519()?,
        SshKeyType::Ed25519 | SshKeyType::Unspecified => {
            return Err(PrimitiveError::InvalidArgument);
        }
    };
    Ok(material.encode_to_vec())
}

pub(crate) fn parse(
    private_key_pem: String,
    public_key_openssh: String,
) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher transfers ownership out of the protobuf request, so the
    // local copy must retain zeroizing ownership on every early-return path.
    let private_key_pem = Zeroizing::new(private_key_pem);
    bound_text(&private_key_pem)?;
    bound_text(&public_key_openssh)?;
    let private_key = decode_private_pem(&private_key_pem)?;

    let parsed_public = PublicKey::from_openssh(&public_key_openssh)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let key_type = validate_key_pair(&private_key, &parsed_public)?;
    let public_key = parsed_public
        .to_bytes()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    if public_key.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }

    Ok(SshKeyMaterial {
        r#type: key_type as i32,
        private_key: private_key.to_vec(),
        public_key,
    }
    .encode_to_vec())
}

pub(crate) fn describe(
    key_type: SshKeyType,
    private_key: Vec<u8>,
    public_key: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key = Zeroizing::new(private_key);
    bound_raw_key(&private_key)?;
    bound_raw_key(&public_key)?;
    let mut private_key_pem = format_private_key_text(key_type, &private_key)?;
    let public_key_openssh = match format_public_key_text(key_type, &public_key) {
        Ok(value) => value,
        Err(error) => {
            private_key_pem.zeroize();
            return Err(error);
        }
    };
    let description = SshKeyDescription {
        private_key_pem,
        public_key_openssh,
        private_fingerprint: fingerprint(&private_key),
        public_fingerprint: fingerprint(&public_key),
    };
    Ok(description.encode_to_vec())
}

pub(crate) fn private_key_rsa_bits(private_key: Vec<u8>) -> i32 {
    let private_key = Zeroizing::new(private_key);
    if private_key.len() > MAX_KEY_BYTES {
        return 0;
    }
    rsa_parts_from_openssh(&private_key)
        .or_else(|| rsa_parts_from_der(&private_key))
        .and_then(|parts| modulus_bits(&parts.modulus))
        .and_then(|bits| i32::try_from(bits).ok())
        .unwrap_or(0)
}

pub(crate) fn format_private_key(
    key_type: SshKeyType,
    private_key: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key = Zeroizing::new(private_key);
    bound_raw_key(&private_key)?;
    Ok(SshFormattedPrivateKey {
        value: format_private_key_text(key_type, &private_key)?,
    }
    .encode_to_vec())
}

pub(crate) fn sign(
    private_key_pem: String,
    public_key_openssh: Option<String>,
    data: Vec<u8>,
    flags: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    // See `parse`: `mem::take` empties the request before this function runs.
    // Keep the moved PEM text under zeroizing ownership as well.
    let private_key_pem = Zeroizing::new(private_key_pem);
    let data = Zeroizing::new(data);
    bound_text(&private_key_pem)?;
    if let Some(public_key) = public_key_openssh.as_deref() {
        bound_text(public_key)?;
    }
    if data.len() > MAX_SIGN_DATA_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    let parsed_public = public_key_openssh
        .as_deref()
        .map(PublicKey::from_openssh)
        .transpose()
        .map_err(|_| PrimitiveError::InvalidArgument)?;

    let private_key = decode_private_pem(&private_key_pem)?;
    let signature = match decode_private_key(&private_key)? {
        DecodedPrivateKey::Ed25519(key) => sign_ed25519(&key, parsed_public.as_ref(), &data)?,
        DecodedPrivateKey::Rsa(parts) => sign_rsa(parts, parsed_public.as_ref(), &data, flags)?,
    };
    Ok(signature.encode_to_vec())
}

/// Validates a persisted SSH key pair and exports its private key for CXF.
///
/// This path accepts legacy `n/e/d` RSA records by completing their CRT
/// components in the sensitive backend. The stored public key is mandatory: it
/// binds the exported algorithm and public identity to the private material
/// before any secret leaves the native boundary. Output is canonical for CXF:
/// Ed25519 uses RFC 8410 PKCS#8 v1 and RSA uses explicit NULL parameters.
pub(crate) fn export_cxf(
    private_key_pem: String,
    public_key_openssh: String,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key_pem = Zeroizing::new(private_key_pem);
    bound_text(&private_key_pem)?;
    bound_text(&public_key_openssh)?;
    let decoded = decode_private_pem(&private_key_pem)?;
    let public_key = PublicKey::from_openssh(&public_key_openssh)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let key_type = validate_key_pair(&decoded, &public_key)?;

    let mut der = if let Ok(key) = PrivateKey::from_bytes(&decoded) {
        match key.key_data() {
            KeypairData::Ed25519(keypair) => ed25519_openssh_to_pkcs8(keypair)?,
            KeypairData::Rsa(keypair) => crate::ssh_import::rsa_openssh_to_pkcs8(keypair)?.to_vec(),
            _ => return Err(PrimitiveError::InvalidArgument),
        }
    } else if let Ok(info) = PrivateKeyInfo::from_der(&decoded) {
        match info.algorithm.oid {
            RSA_ENCRYPTION_OID => {
                if let Some(embedded_public_key) = info.public_key {
                    validate_rsa_pkcs8_public_key(embedded_public_key, &public_key)?;
                }
                let complete = complete_rsa_pkcs1_for_cxf(info.private_key, &public_key)?;
                // Always discard optional PKCS#8 extensions after validating
                // the v2 publicKey field. CXF only needs the private key, and
                // emitting v1 avoids preserving unrecognized metadata.
                encode_rsa_pkcs8(&complete)?
            }
            ED25519_OID => canonicalize_ed25519_pkcs8(&decoded)?,
            _ => return Err(PrimitiveError::InvalidArgument),
        }
    } else {
        let complete = complete_rsa_pkcs1_for_cxf(&decoded, &public_key)?;
        encode_rsa_pkcs8(&complete)?
    };

    if der.len() > MAX_KEY_BYTES {
        der.zeroize();
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(SshKeyExportCxfResult {
        r#type: key_type as i32,
        private_key_pkcs8: der,
    }
    .encode_to_vec())
}

fn ed25519_openssh_to_pkcs8(keypair: &Ed25519Keypair) -> Result<Vec<u8>, PrimitiveError> {
    // The vendored `ssh-key` crate only cross-checks a record's public half
    // against its seed under its `ed25519` feature, which this dependency graph
    // does not enable, so nothing upstream of here binds the two. Re-derive the
    // identity through AWS-LC before accepting the private record for export.
    Ed25519KeyPair::from_seed_and_public_key(keypair.private.as_ref(), keypair.public.as_ref())
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    encode_ed25519_pkcs8_v1(keypair.private.as_ref())
}

/// Accepts either RFC 8410 PKCS#8 shape, validates an optional attached public
/// half, and emits the interoperable v1 form. The separately supplied OpenSSH
/// public key has already been bound by [`validate_key_pair`].
fn canonicalize_ed25519_pkcs8(private_key: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
    let parsed = parse_ed25519_pkcs8(private_key).ok_or(PrimitiveError::InvalidArgument)?;
    let derived = Ed25519KeyPair::from_seed_unchecked(parsed.seed)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    if parsed
        .public_key
        .is_some_and(|embedded| embedded != derived.public_key().as_ref())
    {
        return Err(PrimitiveError::InvalidArgument);
    }
    encode_ed25519_pkcs8_v1(parsed.seed)
}

/// RFC 8410 wraps the 32-byte seed in a nested OCTET STRING. Omitting the
/// optional RFC 5958 publicKey field keeps the document at version v1, which is
/// the most broadly importable PKCS#8 representation.
fn encode_ed25519_pkcs8_v1(seed: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
    let inner = Zeroizing::new(
        OctetStringRef::new(seed)
            .and_then(|octet| octet.to_der())
            .map_err(|_| PrimitiveError::CryptoFailure)?,
    );
    PrivateKeyInfo::new(
        AlgorithmIdentifierRef {
            oid: ED25519_OID,
            parameters: None,
        },
        &inner,
    )
    .to_der()
    .map_err(|_| PrimitiveError::CryptoFailure)
}

/// Wraps a PKCS#1 `RSAPrivateKey` in a `PrivateKeyInfo`. rsaEncryption takes an
/// explicit NULL parameter (RFC 3279 2.3.1), so every export path emits that
/// deterministic canonical shape even when an accepted import used another
/// AlgorithmIdentifier encoding.
fn encode_rsa_pkcs8(pkcs1_der: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
    PrivateKeyInfo::new(
        AlgorithmIdentifierRef {
            oid: RSA_ENCRYPTION_OID,
            parameters: Some(AnyRef::NULL),
        },
        pkcs1_der,
    )
    .to_der()
    .map_err(|_| PrimitiveError::CryptoFailure)
}

fn generate_rsa(bits: u32) -> Result<SshKeyMaterial, PrimitiveError> {
    if !matches!(bits, 1024 | 2048 | 3072 | 4096) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let private_key = generate_rsa_pkcs1_der(bits).map_err(sensitive_rsa_error)?;
    if private_key.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    let parts = rsa_parts_from_pkcs1(&private_key).ok_or(PrimitiveError::CryptoFailure)?;
    let public_key = encode_rsa_public(&parts.modulus, &parts.public_exponent)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Rsa as i32,
        private_key: private_key.to_vec(),
        public_key,
    })
}

fn generate_ed25519() -> Result<SshKeyMaterial, PrimitiveError> {
    let mut seed = Zeroizing::new([0_u8; 32]);
    SystemRandom::new()
        .fill(seed.as_mut())
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    encode_ed25519_material(&seed, random_u32()?)
}

pub(super) fn encode_ed25519_material(
    seed: &[u8; 32],
    checkint: u32,
) -> Result<SshKeyMaterial, PrimitiveError> {
    let aws_keypair =
        Ed25519KeyPair::from_seed_unchecked(seed).map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_bytes: [u8; 32] = aws_keypair
        .public_key()
        .as_ref()
        .try_into()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let ssh_keypair = Ed25519Keypair {
        public: Ed25519PublicKey(public_bytes),
        private: Ed25519PrivateKey::from_bytes(seed),
    };
    let private = PrivateKey::new_with_checkint(ssh_keypair.into(), "", checkint)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_key = private
        .public_key()
        .to_bytes()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let private_key = private
        .to_bytes()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Ed25519 as i32,
        private_key: private_key.to_vec(),
        public_key,
    })
}

fn sign_ed25519(
    key: &Ed25519KeyPair,
    public_key: Option<&PublicKey>,
    data: &[u8],
) -> Result<SshSignature, PrimitiveError> {
    if let Some(public_key) = public_key {
        validate_ed25519_public(key, public_key)?;
    }
    let signature = key
        .try_sign(data)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(SshSignature {
        algorithm: "ssh-ed25519".to_owned(),
        signature: signature.as_ref().to_vec(),
    })
}

fn sign_rsa(
    mut parts: RsaParts,
    public_key: Option<&PublicKey>,
    data: &[u8],
    flags: u32,
) -> Result<SshSignature, PrimitiveError> {
    resolve_rsa_public(&mut parts, public_key)?;
    let (algorithm, hash) = if flags & 0x04 != 0 {
        ("rsa-sha2-512", RsaSignatureHash::Sha512)
    } else if flags & 0x02 != 0 {
        ("rsa-sha2-256", RsaSignatureHash::Sha256)
    } else {
        ("ssh-rsa", RsaSignatureHash::Sha1)
    };

    let components = rsa_private_components(parts);
    let signature = sign_rsa_pkcs1_v1_5(&components, hash, data).map_err(sensitive_rsa_error)?;
    Ok(SshSignature {
        algorithm: algorithm.to_owned(),
        signature,
    })
}

fn complete_rsa_pkcs1_for_cxf(
    private_key: &[u8],
    public_key: &PublicKey,
) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    let mut parts = rsa_parts_from_pkcs1(private_key).ok_or(PrimitiveError::InvalidArgument)?;
    resolve_rsa_public(&mut parts, Some(public_key))?;
    complete_rsa_pkcs1_der_from_components(&rsa_private_components(parts))
        .map_err(sensitive_rsa_error)
}

fn validate_rsa_pkcs8_public_key(
    embedded_public_key: &[u8],
    supplied_public_key: &PublicKey,
) -> Result<(), PrimitiveError> {
    let embedded = Pkcs1RsaPublicKey::from_der(embedded_public_key)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let supplied = supplied_public_key
        .key_data()
        .rsa()
        .ok_or(PrimitiveError::InvalidArgument)?;
    let supplied_modulus = positive_mpint(&supplied.n)?;
    let supplied_exponent = positive_mpint(&supplied.e)?;

    if strip_leading_zeroes(embedded.modulus.as_bytes()) != strip_leading_zeroes(&supplied_modulus)
        || strip_leading_zeroes(embedded.public_exponent.as_bytes())
            != strip_leading_zeroes(&supplied_exponent)
    {
        return Err(PrimitiveError::InvalidArgument);
    }
    Ok(())
}

fn rsa_private_components(mut parts: RsaParts) -> RsaPrivateComponents {
    let crt = parts.crt.take().map(|mut crt| {
        RsaCrtComponents::new(
            std::mem::take(&mut crt.prime_p),
            std::mem::take(&mut crt.prime_q),
            std::mem::take(&mut crt.exponent_p),
            std::mem::take(&mut crt.exponent_q),
            std::mem::take(&mut crt.coefficient),
        )
    });
    RsaPrivateComponents::new(
        std::mem::take(&mut parts.modulus),
        std::mem::take(&mut parts.public_exponent),
        std::mem::take(&mut parts.private_exponent),
        crt,
    )
}

fn validate_key_pair(
    private_key: &[u8],
    public_key: &PublicKey,
) -> Result<SshKeyType, PrimitiveError> {
    match decode_private_key(private_key)? {
        DecodedPrivateKey::Rsa(mut parts) => {
            resolve_rsa_public(&mut parts, Some(public_key))?;
            Ok(SshKeyType::Rsa)
        }
        DecodedPrivateKey::Ed25519(key) => {
            validate_ed25519_public(&key, public_key)?;
            Ok(SshKeyType::Ed25519)
        }
    }
}

enum DecodedPrivateKey {
    Rsa(RsaParts),
    Ed25519(Ed25519KeyPair),
}

/// Decodes an OpenSSH, PKCS#8, or PKCS#1 private key into its signing
/// material, verifying any public identity embedded in the document itself.
fn decode_private_key(private_key: &[u8]) -> Result<DecodedPrivateKey, PrimitiveError> {
    if let Ok(key) = PrivateKey::from_bytes(private_key) {
        return match key.key_data() {
            KeypairData::Rsa(keypair) => {
                Ok(DecodedPrivateKey::Rsa(rsa_parts_from_keypair(keypair)?))
            }
            KeypairData::Ed25519(keypair) => {
                let key = Ed25519KeyPair::from_seed_and_public_key(
                    keypair.private.as_ref(),
                    keypair.public.as_ref(),
                )
                .map_err(|_| PrimitiveError::InvalidArgument)?;
                Ok(DecodedPrivateKey::Ed25519(key))
            }
            _ => Err(PrimitiveError::InvalidArgument),
        };
    }

    if let Some(parts) = rsa_parts_from_der(private_key) {
        return Ok(DecodedPrivateKey::Rsa(parts));
    }

    let parsed = parse_ed25519_pkcs8(private_key).ok_or(PrimitiveError::InvalidArgument)?;
    let key = Ed25519KeyPair::from_seed_unchecked(parsed.seed)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    if parsed
        .public_key
        .is_some_and(|embedded| embedded != key.public_key().as_ref())
    {
        return Err(PrimitiveError::InvalidArgument);
    }
    Ok(DecodedPrivateKey::Ed25519(key))
}

fn validate_ed25519_public(
    key: &Ed25519KeyPair,
    public_key: &PublicKey,
) -> Result<(), PrimitiveError> {
    let supplied = public_key
        .key_data()
        .ed25519()
        .ok_or(PrimitiveError::InvalidArgument)?;
    if supplied.as_ref() != key.public_key().as_ref() {
        return Err(PrimitiveError::InvalidArgument);
    }
    Ok(())
}

fn resolve_rsa_public(
    parts: &mut RsaParts,
    public_key: Option<&PublicKey>,
) -> Result<(), PrimitiveError> {
    if let Some(public_key) = public_key {
        let supplied = public_key
            .key_data()
            .rsa()
            .ok_or(PrimitiveError::InvalidArgument)?;
        let supplied_modulus = positive_mpint(&supplied.n)?;
        if strip_leading_zeroes(&supplied_modulus) != strip_leading_zeroes(&parts.modulus) {
            return Err(PrimitiveError::InvalidArgument);
        }

        let supplied_exponent = positive_mpint(&supplied.e)?;
        if parts.public_exponent.iter().all(|byte| *byte == 0) {
            // Historical SSHJ/Keyguard RSA records persist n/d with a zero
            // exponent. Recover e only from a same-modulus public record; the
            // sensitive signing backend then validates the reconstructed
            // n/e/d key with RSA_check_key before producing a signature.
            parts.public_exponent = supplied_exponent;
        } else if strip_leading_zeroes(&supplied_exponent)
            != strip_leading_zeroes(&parts.public_exponent)
        {
            return Err(PrimitiveError::InvalidArgument);
        }
    }

    if parts.public_exponent.iter().all(|byte| *byte == 0) {
        return Err(PrimitiveError::InvalidArgument);
    }
    Ok(())
}

fn rsa_parts_from_keypair(keypair: &RsaKeypair) -> Result<RsaParts, PrimitiveError> {
    Ok(RsaParts {
        modulus: positive_mpint(&keypair.public.n)?,
        public_exponent: positive_mpint(&keypair.public.e)?,
        private_exponent: positive_mpint(&keypair.private.d)?,
        crt: None,
    })
}

fn rsa_parts_from_openssh(private_key: &[u8]) -> Option<RsaParts> {
    let key = PrivateKey::from_bytes(private_key).ok()?;
    let keypair = key.key_data().rsa()?;
    rsa_parts_from_keypair(keypair).ok()
}

fn rsa_parts_from_der(private_key: &[u8]) -> Option<RsaParts> {
    if let Ok(info) = PrivateKeyInfo::from_der(private_key) {
        if info.algorithm.oid != RSA_ENCRYPTION_OID {
            return None;
        }
        return rsa_parts_from_pkcs1(info.private_key);
    }
    rsa_parts_from_pkcs1(private_key)
}

fn rsa_parts_from_pkcs1(private_key: &[u8]) -> Option<RsaParts> {
    let key = RsaPrivateKey::from_der(private_key).ok()?;
    if key.other_prime_infos.is_some() {
        return None;
    }
    let crt_values = [
        key.prime1.as_bytes(),
        key.prime2.as_bytes(),
        key.exponent1.as_bytes(),
        key.exponent2.as_bytes(),
        key.coefficient.as_bytes(),
    ];
    let crt = crt_values
        .iter()
        .all(|value| value.iter().any(|byte| *byte != 0))
        .then(|| RsaCrtParts {
            prime_p: key.prime1.as_bytes().to_vec(),
            prime_q: key.prime2.as_bytes().to_vec(),
            exponent_p: key.exponent1.as_bytes().to_vec(),
            exponent_q: key.exponent2.as_bytes().to_vec(),
            coefficient: key.coefficient.as_bytes().to_vec(),
        });
    Some(RsaParts {
        modulus: key.modulus.as_bytes().to_vec(),
        public_exponent: key.public_exponent.as_bytes().to_vec(),
        private_exponent: key.private_exponent.as_bytes().to_vec(),
        crt,
    })
}

fn parse_ed25519_pkcs8(private_key: &[u8]) -> Option<ParsedEd25519Pkcs8<'_>> {
    let Ok(info) = PrivateKeyInfo::from_der(private_key) else {
        return None;
    };
    if info.algorithm.oid != ED25519_OID || info.algorithm.parameters.is_some() {
        return None;
    }

    // RFC 8410 wraps the 32-byte CurvePrivateKey in a second OCTET STRING.
    // `from_der` consumes the complete inner document, rejecting trailing or
    // malformed data instead of merely accepting a matching algorithm OID.
    let Ok(seed) = OctetStringRef::from_der(info.private_key) else {
        return None;
    };
    (seed.as_bytes().len() == 32
        && info
            .public_key
            .is_none_or(|public_key| public_key.len() == 32))
    .then_some(ParsedEd25519Pkcs8 {
        seed: seed.as_bytes(),
        public_key: info.public_key,
    })
}

fn is_ed25519_pkcs8(private_key: &[u8]) -> bool {
    parse_ed25519_pkcs8(private_key).is_some()
}

fn is_supported_pkcs8(private_key: &[u8]) -> bool {
    let Ok(info) = PrivateKeyInfo::from_der(private_key) else {
        return false;
    };
    match info.algorithm.oid {
        RSA_ENCRYPTION_OID => rsa_parts_from_pkcs1(info.private_key).is_some(),
        ED25519_OID => is_ed25519_pkcs8(private_key),
        _ => false,
    }
}

pub(super) fn encode_rsa_public(
    modulus: &[u8],
    exponent: &[u8],
) -> Result<Vec<u8>, PrimitiveError> {
    let key = PublicKey::new(
        KeyData::Rsa(RsaPublicKey {
            e: Mpint::from_positive_bytes(exponent).map_err(|_| PrimitiveError::CryptoFailure)?,
            n: Mpint::from_positive_bytes(modulus).map_err(|_| PrimitiveError::CryptoFailure)?,
        }),
        "",
    );
    key.to_bytes().map_err(|_| PrimitiveError::CryptoFailure)
}

fn positive_mpint(value: &Mpint) -> Result<Vec<u8>, PrimitiveError> {
    value
        .as_positive_bytes()
        .filter(|bytes| !bytes.is_empty())
        .map(ToOwned::to_owned)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn format_private_key_text(
    key_type: SshKeyType,
    private_key: &[u8],
) -> Result<String, PrimitiveError> {
    let (label, width) = match key_type {
        SshKeyType::Ed25519 => ("OPENSSH PRIVATE KEY", 70),
        SshKeyType::Rsa => {
            // The historical formatter selected the generic PKCS#8 label from
            // the private document independently of the persisted public type.
            let pkcs8 = is_supported_pkcs8(private_key);
            if pkcs8 {
                ("PRIVATE KEY", 64)
            } else {
                ("RSA PRIVATE KEY", 64)
            }
        }
        SshKeyType::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    let encoded = Zeroizing::new(Base64::encode_string(private_key));
    let mut output = String::with_capacity(encoded.len() + 96);
    output.push_str("-----BEGIN ");
    output.push_str(label);
    output.push_str("-----\n");
    for line in encoded.as_bytes().chunks(width) {
        let line = std::str::from_utf8(line).map_err(|_| PrimitiveError::CryptoFailure)?;
        output.push_str(line);
        output.push('\n');
    }
    output.push_str("-----END ");
    output.push_str(label);
    output.push_str("-----\n");
    if output.len() > MAX_CONTROL_ENVELOPE_BYTES {
        output.zeroize();
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(output)
}

fn format_public_key_text(
    key_type: SshKeyType,
    public_key: &[u8],
) -> Result<String, PrimitiveError> {
    let prefix = match key_type {
        SshKeyType::Rsa => "ssh-rsa",
        SshKeyType::Ed25519 => "ssh-ed25519",
        SshKeyType::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    Ok(format!("{prefix} {}", Base64::encode_string(public_key)))
}

fn fingerprint(value: &[u8]) -> String {
    let digest = aws_lc_rs::digest::digest(&aws_lc_rs::digest::SHA256, value);
    format!("SHA256:{}", Base64::encode_string(digest.as_ref()))
}

fn decode_private_pem(value: &str) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    let mut encoded = Zeroizing::new(String::new());
    for line in value.lines() {
        let line = line.trim();
        if line.is_empty() || is_private_key_boundary(line) {
            continue;
        }
        encoded.push_str(line);
    }
    if encoded.is_empty() || encoded.len() > MAX_KEY_BYTES.saturating_mul(2) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let decoded =
        Zeroizing::new(Base64::decode_vec(&encoded).map_err(|_| PrimitiveError::InvalidArgument)?);
    if decoded.is_empty() || decoded.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(decoded)
}

fn is_private_key_boundary(line: &str) -> bool {
    let leading = line.bytes().take_while(|byte| *byte == b'-').count();
    let trailing = line.bytes().rev().take_while(|byte| *byte == b'-').count();
    if !(1..=5).contains(&leading)
        || !(1..=5).contains(&trailing)
        || leading + trailing >= line.len()
    {
        return false;
    }
    matches!(
        &line[leading..line.len() - trailing],
        "BEGIN PRIVATE KEY"
            | "END PRIVATE KEY"
            | "BEGIN RSA PRIVATE KEY"
            | "END RSA PRIVATE KEY"
            | "BEGIN OPENSSH PRIVATE KEY"
            | "END OPENSSH PRIVATE KEY"
    )
}

pub(super) fn random_u32() -> Result<u32, PrimitiveError> {
    let mut bytes = [0_u8; 4];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(u32::from_be_bytes(bytes))
}

fn modulus_bits(modulus: &[u8]) -> Option<u32> {
    let modulus = strip_leading_zeroes(modulus);
    let first = *modulus.first()?;
    let remaining = u32::try_from(modulus.len().checked_sub(1)?).ok()?;
    remaining
        .checked_mul(8)?
        .checked_add(8_u32.checked_sub(first.leading_zeros())?)
}

fn strip_leading_zeroes(mut value: &[u8]) -> &[u8] {
    while value.first() == Some(&0) {
        value = &value[1..];
    }
    value
}

fn bound_text(value: &str) -> Result<(), PrimitiveError> {
    if value.is_empty() {
        Err(PrimitiveError::InvalidArgument)
    } else if value.len() > MAX_KEY_BYTES {
        Err(PrimitiveError::ResourceLimit)
    } else {
        Ok(())
    }
}

fn bound_raw_key(value: &[u8]) -> Result<(), PrimitiveError> {
    if value.is_empty() {
        Err(PrimitiveError::InvalidArgument)
    } else if value.len() > MAX_KEY_BYTES {
        Err(PrimitiveError::ResourceLimit)
    } else {
        Ok(())
    }
}

fn sensitive_rsa_error(error: SensitiveRsaError) -> PrimitiveError {
    match error {
        SensitiveRsaError::InvalidKeySize | SensitiveRsaError::InvalidKey => {
            PrimitiveError::InvalidArgument
        }
        SensitiveRsaError::AllocationFailure | SensitiveRsaError::BackendFailure => {
            PrimitiveError::CryptoFailure
        }
    }
}

struct ParsedEd25519Pkcs8<'a> {
    seed: &'a [u8],
    public_key: Option<&'a [u8]>,
}

struct RsaParts {
    modulus: Vec<u8>,
    public_exponent: Vec<u8>,
    private_exponent: Vec<u8>,
    crt: Option<RsaCrtParts>,
}

impl Drop for RsaParts {
    fn drop(&mut self) {
        self.modulus.zeroize();
        self.public_exponent.zeroize();
        self.private_exponent.zeroize();
    }
}

struct RsaCrtParts {
    prime_p: Vec<u8>,
    prime_q: Vec<u8>,
    exponent_p: Vec<u8>,
    exponent_q: Vec<u8>,
    coefficient: Vec<u8>,
}

impl Drop for RsaCrtParts {
    fn drop(&mut self) {
        self.prime_p.zeroize();
        self.prime_q.zeroize();
        self.exponent_p.zeroize();
        self.exponent_q.zeroize();
        self.coefficient.zeroize();
    }
}

#[cfg(test)]
mod tests {
    use aws_lc_rs::signature::{
        ED25519, RSA_PKCS1_1024_8192_SHA1_FOR_LEGACY_USE_ONLY,
        RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
        RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY, RsaPublicKeyComponents, UnparsedPublicKey,
    };
    use pkcs8::der::asn1::UintRef;

    use super::*;

    #[test]
    fn generated_ed25519_round_trips_formats_and_signs() {
        let material = material(generate(SshKeyType::Ed25519, 0).expect("Ed25519 generation"));
        assert_eq!(material.r#type, SshKeyType::Ed25519 as i32);

        let private = PrivateKey::from_bytes(&material.private_key).expect("OpenSSH private key");
        let public = PublicKey::from_bytes(&material.public_key).expect("OpenSSH public key");
        assert_eq!(
            private.public_key().to_bytes().expect("public bytes"),
            material.public_key
        );
        let public_bytes = public
            .key_data()
            .ed25519()
            .expect("Ed25519 public key")
            .as_ref();

        let pem = format_private_key_text(SshKeyType::Ed25519, &material.private_key)
            .expect("private PEM");
        assert!(pem.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assert!(pem.ends_with("-----END OPENSSH PRIVATE KEY-----\n"));
        assert!(
            pem.lines()
                .skip(1)
                .take_while(|line| !line.starts_with("-----END"))
                .all(|line| line.len() <= 70)
        );

        let signed =
            signature(sign(pem, None, b"ssh-key-ed25519".to_vec(), 0).expect("Ed25519 signing"));
        assert_eq!(signed.algorithm, "ssh-ed25519");
        UnparsedPublicKey::new(&ED25519, public_bytes)
            .verify(b"ssh-key-ed25519", &signed.signature)
            .expect("AWS-LC must independently verify the signature");

        let described = SshKeyDescription::decode(
            describe(
                SshKeyType::Ed25519,
                material.private_key.clone(),
                material.public_key.clone(),
            )
            .expect("description")
            .as_slice(),
        )
        .expect("description payload");
        assert!(described.private_fingerprint.starts_with("SHA256:"));
        assert!(described.private_fingerprint.ends_with('='));
        assert!(described.public_fingerprint.ends_with('='));
    }

    #[test]
    fn deterministic_ed25519_encoding_matches_the_existing_openssh_fixture() {
        let seed: [u8; 32] =
            decode_hex("66c9549f97399d21e3d8ae3b725e03aedfba1384f54bfd643c732eac3f94e4fc")
                .try_into()
                .expect("32-byte Ed25519 seed");
        let material =
            encode_ed25519_material(&seed, 0x03b5_b4fe).expect("deterministic Ed25519 encoding");

        assert_eq!(
            Base64::encode_string(&material.private_key),
            concat!(
                "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQ",
                "AAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAIgDtbT+A7W0/gAAAAtzc2gtZWQ",
                "yNTUxOQAAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAEBmyVSflzmdIePYrjty",
                "XgOu37oThPVL/WQ8cy6sP5Tk/LjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/AAAAAAECAwQF",
            ),
        );
        assert_eq!(
            Base64::encode_string(&material.public_key),
            "AAAAC3NzaC1lZDI1NTE5AAAAILjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/",
        );
    }

    #[test]
    fn generated_rsa_1024_retains_exponent_size_and_flag_precedence() {
        let material = material(generate(SshKeyType::Rsa, 1024).expect("RSA-1024 generation"));
        assert_eq!(private_key_rsa_bits(material.private_key.clone()), 1024);
        let public = PublicKey::from_bytes(&material.public_key).expect("RSA public key");
        let rsa = public.key_data().rsa().expect("RSA public components");
        assert_eq!(rsa.e.as_positive_bytes(), Some(&[0x01, 0x00, 0x01][..]));

        let pem = format_private_key_text(SshKeyType::Rsa, &material.private_key)
            .expect("RSA private PEM");
        assert!(pem.starts_with("-----BEGIN RSA PRIVATE KEY-----\n"));
        assert!(pem.ends_with("-----END RSA PRIVATE KEY-----\n"));
        assert!(
            pem.lines()
                .skip(1)
                .take_while(|line| !line.starts_with("-----END"))
                .all(|line| line.len() <= 64)
        );
        let verifier = RsaPublicKeyComponents {
            n: rsa.n.as_positive_bytes().expect("positive modulus"),
            e: rsa.e.as_positive_bytes().expect("positive exponent"),
        };
        let cases = [
            (0, "ssh-rsa", &RSA_PKCS1_1024_8192_SHA1_FOR_LEGACY_USE_ONLY),
            (
                0x02,
                "rsa-sha2-256",
                &RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
            ),
            (
                0x04,
                "rsa-sha2-512",
                &RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY,
            ),
            (
                0x06,
                "rsa-sha2-512",
                &RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY,
            ),
        ];
        for (flags, algorithm, parameters) in cases {
            let signed = signature(
                sign(pem.clone(), None, b"ssh-key-rsa".to_vec(), flags).expect("RSA signing"),
            );
            assert_eq!(signed.algorithm, algorithm);
            assert_eq!(signed.signature.len(), 128);
            verifier
                .verify(parameters, b"ssh-key-rsa", &signed.signature)
                .expect("AWS-LC must independently verify the signature");
        }
    }

    #[test]
    fn incomplete_pkcs8_rsa_recovers_the_public_exponent_before_sensitive_operations() {
        let complete = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let incomplete = incomplete_pkcs8_rsa(&complete.private_key);
        let info = PrivateKeyInfo::from_der(&incomplete).expect("incomplete PKCS#8 parses");
        assert!(
            RsaPrivateKey::from_der(info.private_key).is_ok(),
            "incomplete PKCS#1 parses: {:?}",
            RsaPrivateKey::from_der(info.private_key).err(),
        );
        let pem =
            format_private_key_text(SshKeyType::Rsa, &incomplete).expect("incomplete PKCS#8 PEM");
        let public_text =
            format_public_key_text(SshKeyType::Rsa, &complete.public_key).expect("RSA public text");

        assert!(pem.starts_with("-----BEGIN PRIVATE KEY-----\n"));
        assert_eq!(private_key_rsa_bits(incomplete.clone()), 1024);
        assert!(parse(pem.clone(), public_text.clone()).is_ok());
        assert_eq!(
            sign(pem.clone(), None, b"incomplete-rsa".to_vec(), 0x02),
            Err(PrimitiveError::InvalidArgument),
        );

        let signed = signature(
            sign(
                pem.clone(),
                Some(public_text.clone()),
                b"incomplete-rsa".to_vec(),
                0x02,
            )
            .expect("n/e/d-only RSA signing"),
        );
        let public = PublicKey::from_bytes(&complete.public_key).expect("RSA public key");
        let rsa = public.key_data().rsa().expect("RSA public components");
        RsaPublicKeyComponents {
            n: rsa.n.as_positive_bytes().expect("positive modulus"),
            e: rsa.e.as_positive_bytes().expect("positive exponent"),
        }
        .verify(
            &RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
            b"incomplete-rsa",
            &signed.signature,
        )
        .expect("reconstructed key signature must verify");

        let other = material(generate(SshKeyType::Rsa, 1024).expect("other RSA generation"));
        let other_public = format_public_key_text(SshKeyType::Rsa, &other.public_key)
            .expect("other RSA public text");
        assert_eq!(
            sign(
                pem.clone(),
                Some(other_public),
                b"incomplete-rsa".to_vec(),
                0x02,
            ),
            Err(PrimitiveError::InvalidArgument),
        );

        let wrong_exponent_public =
            encode_rsa_public(rsa.n.as_positive_bytes().expect("positive modulus"), &[3])
                .expect("same-modulus RSA public key");
        let wrong_exponent_text = format_public_key_text(SshKeyType::Rsa, &wrong_exponent_public)
            .expect("same-modulus RSA public text");
        assert_eq!(
            sign(
                pem,
                Some(wrong_exponent_text),
                b"incomplete-rsa".to_vec(),
                0x02,
            ),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn ed25519_pkcs8_requires_the_exact_rfc8410_private_key_shape() {
        let seed = [0x42_u8; 32];
        let valid = ed25519_pkcs8(&seed, None, None);
        assert!(is_ed25519_pkcs8(&valid));
        assert!(parse_ed25519_pkcs8(&valid).is_some());

        // Persisted public/private types were historically classified
        // independently, so a valid PKCS#8 document keeps the generic label.
        assert!(
            format_private_key_text(SshKeyType::Rsa, &valid)
                .expect("compatibility formatting")
                .starts_with("-----BEGIN PRIVATE KEY-----\n"),
        );

        let raw_seed_without_nested_octet_string = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters: None,
            },
            &seed,
        )
        .to_der()
        .expect("malformed Ed25519 PKCS#8 encoding");
        assert!(!is_ed25519_pkcs8(&raw_seed_without_nested_octet_string));

        let short_seed = [0x42_u8; 31];
        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(&short_seed, None, None,)));

        let mut inner_with_trailing = OctetStringRef::new(&seed)
            .expect("nested OCTET STRING")
            .to_der()
            .expect("nested OCTET STRING DER");
        inner_with_trailing.push(0);
        let trailing = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters: None,
            },
            &inner_with_trailing,
        )
        .to_der()
        .expect("trailing-inner PKCS#8 encoding");
        assert!(!is_ed25519_pkcs8(&trailing));

        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(
            &seed,
            Some(AnyRef::NULL),
            None,
        )));
        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(
            &seed,
            None,
            Some(&[0x24; 31]),
        )));
    }

    #[test]
    fn persisted_parse_and_signing_reject_mismatched_ed25519_identity() {
        let first = material(generate(SshKeyType::Ed25519, 0).expect("first Ed25519 key"));
        let second = material(generate(SshKeyType::Ed25519, 0).expect("second Ed25519 key"));
        let private_pem =
            format_private_key_text(SshKeyType::Ed25519, &first.private_key).expect("private PEM");
        let matching_public =
            format_public_key_text(SshKeyType::Ed25519, &first.public_key).expect("public text");
        let mismatched_public =
            format_public_key_text(SshKeyType::Ed25519, &second.public_key).expect("public text");

        let parsed = material(
            parse(private_pem.clone(), matching_public).expect("matching identity parses"),
        );
        assert_eq!(parsed.private_key, first.private_key);
        assert_eq!(parsed.public_key, first.public_key);
        assert_eq!(
            parse(private_pem.clone(), mismatched_public.clone()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(
                private_pem,
                Some(mismatched_public),
                b"mismatched-ed25519".to_vec(),
                0,
            ),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn persisted_parse_and_signing_reject_mismatched_complete_rsa_identity() {
        let first = material(generate(SshKeyType::Rsa, 1024).expect("first RSA key"));
        let second = material(generate(SshKeyType::Rsa, 1024).expect("second RSA key"));
        let private_pem =
            format_private_key_text(SshKeyType::Rsa, &first.private_key).expect("private PEM");
        let matching_public =
            format_public_key_text(SshKeyType::Rsa, &first.public_key).expect("public text");
        let mismatched_public =
            format_public_key_text(SshKeyType::Rsa, &second.public_key).expect("public text");

        assert!(parse(private_pem.clone(), matching_public).is_ok());
        assert_eq!(
            parse(private_pem.clone(), mismatched_public.clone()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(
                private_pem.clone(),
                Some(mismatched_public),
                b"mismatched-rsa".to_vec(),
                0x02,
            ),
            Err(PrimitiveError::InvalidArgument),
        );

        let first_public = PublicKey::from_bytes(&first.public_key).expect("RSA public key");
        let first_rsa = first_public
            .key_data()
            .rsa()
            .expect("RSA public components");
        let wrong_exponent_public = encode_rsa_public(
            first_rsa.n.as_positive_bytes().expect("positive modulus"),
            &[3],
        )
        .expect("same-modulus RSA public key");
        let wrong_exponent_text = format_public_key_text(SshKeyType::Rsa, &wrong_exponent_public)
            .expect("same-modulus RSA public text");
        assert_eq!(
            parse(private_pem.clone(), wrong_exponent_text.clone()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(
                private_pem,
                Some(wrong_exponent_text),
                b"mismatched-rsa-exponent".to_vec(),
                0x02,
            ),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn persisted_parsing_rejects_trailing_documents_and_unrelated_pem_labels() {
        let ed = material(generate(SshKeyType::Ed25519, 0).expect("Ed25519 generation"));
        let ed_public = format_public_key_text(SshKeyType::Ed25519, &ed.public_key)
            .expect("Ed25519 public text");
        let mut trailing_private = ed.private_key.clone();
        trailing_private.push(0);
        let trailing_pem = format_private_key_text(SshKeyType::Ed25519, &trailing_private)
            .expect("trailing private PEM");
        assert_eq!(
            parse(trailing_pem, ed_public.clone()),
            Err(PrimitiveError::InvalidArgument),
        );

        let mut trailing_public = ed.public_key.clone();
        trailing_public.push(0);
        let trailing_public_text = format_public_key_text(SshKeyType::Ed25519, &trailing_public)
            .expect("trailing public text");
        let ed_pem = format_private_key_text(SshKeyType::Ed25519, &ed.private_key)
            .expect("Ed25519 private PEM");
        assert_eq!(
            parse(ed_pem.clone(), trailing_public_text),
            Err(PrimitiveError::InvalidArgument),
        );

        let mislabeled = ed_pem.replace("OPENSSH PRIVATE KEY", "CERTIFICATE");
        assert_eq!(
            parse(mislabeled, ed_public),
            Err(PrimitiveError::InvalidArgument),
        );

        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let mut trailing_der = rsa.private_key.clone();
        trailing_der.push(0);
        assert_eq!(private_key_rsa_bits(trailing_der.clone()), 0);
        assert!(rsa_parts_from_der(&trailing_der).is_none());
    }

    #[test]
    fn malformed_and_oversized_inputs_fail_before_crypto_work() {
        assert_eq!(
            parse("not a key".to_owned(), "not a key".to_owned()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(
                "not a key".to_owned(),
                None,
                vec![0_u8; MAX_SIGN_DATA_BYTES + 1],
                0,
            ),
            Err(PrimitiveError::ResourceLimit),
        );
        assert_eq!(private_key_rsa_bits(vec![0xa5; MAX_KEY_BYTES + 1]), 0);
    }

    // Ed25519 OpenSSH key generated with `ssh-keygen -t ed25519`. The expected
    // DER below is the RFC 8410 PKCS#8 v1 template assembled independently from
    // the key's 32-byte seed.
    const ED25519_GOLDEN_OPENSSH_PEM: &str = concat!(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n",
        "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n",
        "QyNTUxOQAAACCRW3vhbnH4ErsDEybqMu75IyghrTkyzDa30aKoSWgnkgAAAJBlR0JRZUdC\n",
        "UQAAAAtzc2gtZWQyNTUxOQAAACCRW3vhbnH4ErsDEybqMu75IyghrTkyzDa30aKoSWgnkg\n",
        "AAAEBJ9Y0pa8/Bvf2KAtsI7ulbNYoG6KAFTolkWkCCiMFaFJFbe+FucfgSuwMTJuoy7vkj\n",
        "KCGtOTLMNrfRoqhJaCeSAAAADWtleWd1YXJkLXRlc3Q=\n",
        "-----END OPENSSH PRIVATE KEY-----\n",
    );
    const ED25519_GOLDEN_PUBLIC_OPENSSH: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJFbe+FucfgSuwMTJuoy7vkjKCGtOTLMNrfRoqhJaCeS";

    #[test]
    fn ed25519_cxf_export_matches_the_independent_pkcs8_v1_golden_vector() {
        let payload = export_cxf(
            ED25519_GOLDEN_OPENSSH_PEM.to_owned(),
            ED25519_GOLDEN_PUBLIC_OPENSSH.to_owned(),
        )
        .expect("Ed25519 CXF export");
        let exported =
            SshKeyExportCxfResult::decode(payload.as_slice()).expect("CXF export payload");
        let expected = decode_hex(
            "302e020100300506032b65700422042049f58d296bcfc1bdfd8a02db08eee95b\
             358a06e8a0054e89645a408288c15a14",
        );
        assert_eq!(exported.private_key_pkcs8, expected);
        let info = PrivateKeyInfo::from_der(&exported.private_key_pkcs8).expect("PKCS#8 v1 parses");
        assert!(info.public_key.is_none());
        assert!(is_ed25519_pkcs8(&exported.private_key_pkcs8));
    }

    #[test]
    fn cxf_export_completes_legacy_rsa_using_the_matching_public_key() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let expected = rsa_parts_from_pkcs1(&rsa.private_key).expect("complete PKCS#1 parts");
        let incomplete = incomplete_pkcs8_rsa(&rsa.private_key);
        let private_pem =
            format_private_key_text(SshKeyType::Rsa, &incomplete).expect("incomplete PKCS#8 PEM");
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");

        let payload = export_cxf(private_pem, public_key).expect("CXF RSA export");
        let result = SshKeyExportCxfResult::decode(payload.as_slice()).expect("CXF export payload");
        assert_eq!(result.r#type, SshKeyType::Rsa as i32);
        let info =
            PrivateKeyInfo::from_der(&result.private_key_pkcs8).expect("exported PKCS#8 parses");
        assert_eq!(info.algorithm.oid, RSA_ENCRYPTION_OID);
        let completed = rsa_parts_from_pkcs1(info.private_key).expect("complete PKCS#1 parts");
        assert_eq!(completed.modulus, expected.modulus);
        assert_eq!(completed.public_exponent, expected.public_exponent);
        assert!(completed.crt.is_some());
    }

    #[test]
    fn cxf_export_rejects_a_public_key_from_a_different_pair() {
        let first = material(generate(SshKeyType::Ed25519, 0).expect("first Ed25519 generation"));
        let second = material(generate(SshKeyType::Ed25519, 0).expect("second Ed25519 generation"));
        let private_pem = format_private_key_text(SshKeyType::Ed25519, &first.private_key)
            .expect("OpenSSH private key");
        let public_key = format_public_key_text(SshKeyType::Ed25519, &second.public_key)
            .expect("different OpenSSH public key");

        assert_eq!(
            export_cxf(private_pem, public_key),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn cxf_export_rejects_unconvertible_keys() {
        assert_eq!(
            export_cxf(
                "not a key".to_owned(),
                ED25519_GOLDEN_PUBLIC_OPENSSH.to_owned(),
            ),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            export_cxf(String::new(), ED25519_GOLDEN_PUBLIC_OPENSSH.to_owned(),),
            Err(PrimitiveError::InvalidArgument),
        );

        // Encrypted OpenSSH decodes to `KeypairData::Encrypted`, never a keypair.
        assert_eq!(
            export_cxf(
                ED25519_OPENSSH_ENCRYPTED_PEM.to_owned(),
                ED25519_GOLDEN_PUBLIC_OPENSSH.to_owned(),
            ),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn cxf_rsa_openssh_pem_exports_a_complete_canonical_pkcs8_document() {
        // A synced RSA key is stored OpenSSH-armored, so this is its ordinary
        // shape. The record carries n, e, d, p, q and iqmp, so the CRT exponents
        // PKCS#1 requires are derivable and the key need not be skipped.
        let decoded = decode_private_pem(RSA_OPENSSH_UNENCRYPTED_PEM).expect("OpenSSH PEM");
        let private = PrivateKey::from_bytes(&decoded).expect("OpenSSH private key");
        let public_key = private
            .public_key()
            .to_openssh()
            .expect("OpenSSH public key");
        let payload = export_cxf(RSA_OPENSSH_UNENCRYPTED_PEM.to_owned(), public_key.clone())
            .expect("OpenSSH RSA CXF export");
        let exported =
            SshKeyExportCxfResult::decode(payload.as_slice()).expect("CXF export payload");

        let info =
            PrivateKeyInfo::from_der(&exported.private_key_pkcs8).expect("exported PKCS#8 parses");
        assert_eq!(info.algorithm.oid, RSA_ENCRYPTION_OID);
        assert_eq!(info.algorithm.parameters, Some(AnyRef::NULL));
        let parts = rsa_parts_from_pkcs1(info.private_key).expect("inner PKCS#1 parts");
        assert!(parts.crt.is_some());
        // `ssh-keygen -t rsa -b 2048` — the modulus must survive intact.
        assert_eq!(parts.modulus.len(), 256);
        assert_eq!(parts.public_exponent, &[0x01, 0x00, 0x01]);

        let pem = format_private_key_text(SshKeyType::Rsa, &exported.private_key_pkcs8)
            .expect("exported PKCS#8 PEM");
        let repeat = SshKeyExportCxfResult::decode(
            export_cxf(pem, public_key)
                .expect("CXF RSA re-export")
                .as_slice(),
        )
        .expect("CXF re-export payload");
        assert_eq!(repeat.private_key_pkcs8, exported.private_key_pkcs8);
    }

    #[test]
    fn ed25519_openssh_export_rejects_a_public_half_that_does_not_match_the_seed() {
        let seed = [0x11_u8; 32];
        let honest = encode_ed25519_material(&seed, 0x0102_0304).expect("Ed25519 material");
        let honest_pem =
            format_private_key_text(SshKeyType::Ed25519, &honest.private_key).expect("private PEM");
        let honest_public =
            format_public_key_text(SshKeyType::Ed25519, &honest.public_key).expect("public key");
        export_cxf(honest_pem, honest_public.clone())
            .expect("a self-consistent record still exports");

        let unrelated =
            Ed25519KeyPair::from_seed_unchecked(&[0x22_u8; 32]).expect("unrelated Ed25519 key");
        let unrelated_public: [u8; 32] = unrelated
            .public_key()
            .as_ref()
            .try_into()
            .expect("32-byte Ed25519 public key");
        let substituted = PrivateKey::new_with_checkint(
            Ed25519Keypair {
                public: Ed25519PublicKey(unrelated_public),
                private: Ed25519PrivateKey::from_bytes(&seed),
            }
            .into(),
            "",
            0x0102_0304,
        )
        .expect("substituted OpenSSH record");
        let substituted_bytes = substituted.to_bytes().expect("substituted OpenSSH bytes");
        // `ssh-key` keeps its seed/public consistency check behind the
        // `ed25519` feature, which this graph disables, so the substituted
        // record decodes cleanly and reaches the exporter intact.
        assert!(PrivateKey::from_bytes(&substituted_bytes).is_ok());
        let substituted_pem = format_private_key_text(SshKeyType::Ed25519, &substituted_bytes)
            .expect("substituted private PEM");
        assert_eq!(
            export_cxf(substituted_pem, honest_public),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn cxf_ed25519_pkcs8_v1_and_v2_normalize_to_the_same_v1_document() {
        let seed = [0x24_u8; 32];
        let derived = Ed25519KeyPair::from_seed_unchecked(&seed).expect("Ed25519 key pair");
        let derived_public: &[u8] = derived.public_key().as_ref();
        let public = PublicKey::new(
            KeyData::Ed25519(Ed25519PublicKey(
                derived_public.try_into().expect("32-byte public key"),
            )),
            "",
        )
        .to_openssh()
        .expect("OpenSSH public key");
        let v1 = ed25519_pkcs8(&seed, None, None);
        let v2 = ed25519_pkcs8(&seed, None, Some(derived_public));

        for input in [&v1, &v2] {
            let pem = format_private_key_text(SshKeyType::Rsa, input).expect("Ed25519 PKCS#8 PEM");
            let result = SshKeyExportCxfResult::decode(
                export_cxf(pem, public.clone())
                    .expect("Ed25519 CXF export")
                    .as_slice(),
            )
            .expect("CXF export payload");
            assert_eq!(result.private_key_pkcs8, v1);
            assert!(
                PrivateKeyInfo::from_der(&result.private_key_pkcs8)
                    .expect("canonical Ed25519 PKCS#8")
                    .public_key
                    .is_none(),
            );
        }
    }

    #[test]
    fn cxf_ed25519_pkcs8_rejects_an_attached_public_half_from_another_key() {
        let seed = [0x24_u8; 32];
        let derived = Ed25519KeyPair::from_seed_unchecked(&seed).expect("Ed25519 key pair");
        let derived_public: &[u8] = derived.public_key().as_ref();
        let public = PublicKey::new(
            KeyData::Ed25519(Ed25519PublicKey(
                derived_public.try_into().expect("32-byte public key"),
            )),
            "",
        )
        .to_openssh()
        .expect("OpenSSH public key");

        let substituted = ed25519_pkcs8(&seed, None, Some(&[0x77_u8; 32]));
        assert!(
            is_ed25519_pkcs8(&substituted),
            "the shape check alone accepts a substituted public half",
        );
        let substituted_pem = format_private_key_text(SshKeyType::Rsa, &substituted)
            .expect("substituted Ed25519 PKCS#8 PEM");
        assert_eq!(
            export_cxf(substituted_pem, public),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn cxf_export_canonicalizes_all_accepted_rsa_encryption_parameters() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let expected = rsa_parts_from_pkcs1(&rsa.private_key).expect("source PKCS#1 parts");
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");
        let parameter_der = [0x04, 0x01, 0x01];
        let non_null = AnyRef::from_der(&parameter_der).expect("OCTET STRING parameter");

        for parameters in [None, Some(AnyRef::NULL), Some(non_null)] {
            let input = PrivateKeyInfo::new(
                AlgorithmIdentifierRef {
                    oid: RSA_ENCRYPTION_OID,
                    parameters,
                },
                &rsa.private_key,
            )
            .to_der()
            .expect("RSA PKCS#8 document");
            let private_pem =
                format_private_key_text(SshKeyType::Rsa, &input).expect("RSA PKCS#8 PEM");
            let payload = export_cxf(private_pem, public_key.clone()).expect("CXF RSA export");
            let result =
                SshKeyExportCxfResult::decode(payload.as_slice()).expect("CXF export payload");
            let info = PrivateKeyInfo::from_der(&result.private_key_pkcs8)
                .expect("exported PKCS#8 parses");
            assert_eq!(info.algorithm.parameters, Some(AnyRef::NULL));
            let parts = rsa_parts_from_pkcs1(info.private_key).expect("inner PKCS#1 parts");
            assert_eq!(parts.modulus, expected.modulus);
            assert_eq!(parts.public_exponent, expected.public_exponent);
            assert!(parts.crt.is_some());

            let canonical_pem = format_private_key_text(SshKeyType::Rsa, &result.private_key_pkcs8)
                .expect("canonical PKCS#8 PEM");
            let repeat = SshKeyExportCxfResult::decode(
                export_cxf(canonical_pem, public_key.clone())
                    .expect("CXF RSA re-export")
                    .as_slice(),
            )
            .expect("CXF re-export payload");
            assert_eq!(repeat.private_key_pkcs8, result.private_key_pkcs8);
        }
    }

    #[test]
    fn cxf_rsa_pkcs8_v2_validates_its_public_key_and_normalizes_to_v1() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let private = RsaPrivateKey::from_der(&rsa.private_key).expect("complete PKCS#1 key");
        let embedded_public = Pkcs1RsaPublicKey {
            modulus: private.modulus,
            public_exponent: private.public_exponent,
        }
        .to_der()
        .expect("PKCS#1 public key");
        let v2 = rsa_pkcs8_with_public_key(&rsa.private_key, &embedded_public);
        let private_pem = format_private_key_text(SshKeyType::Rsa, &v2).expect("RSA PKCS#8 v2 PEM");
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");

        let payload = export_cxf(private_pem, public_key).expect("CXF RSA export");
        let result = SshKeyExportCxfResult::decode(payload.as_slice()).expect("CXF export payload");
        let info =
            PrivateKeyInfo::from_der(&result.private_key_pkcs8).expect("exported PKCS#8 parses");
        assert!(info.public_key.is_none());
        assert_eq!(info.algorithm.parameters, Some(AnyRef::NULL));
        assert_eq!(
            result.private_key_pkcs8,
            encode_rsa_pkcs8(&rsa.private_key).expect("canonical PKCS#8 v1"),
        );
    }

    #[test]
    fn cxf_rsa_pkcs8_v2_rejects_a_mismatched_public_key() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let other = material(generate(SshKeyType::Rsa, 1024).expect("other RSA generation"));
        let private = RsaPrivateKey::from_der(&rsa.private_key).expect("complete PKCS#1 key");
        let other_private =
            RsaPrivateKey::from_der(&other.private_key).expect("other complete PKCS#1 key");
        let wrong_exponent_bytes = [3_u8];
        let mismatches = [
            Pkcs1RsaPublicKey {
                modulus: other_private.modulus,
                public_exponent: private.public_exponent,
            }
            .to_der()
            .expect("wrong-modulus PKCS#1 public key"),
            Pkcs1RsaPublicKey {
                modulus: private.modulus,
                public_exponent: UintRef::new(&wrong_exponent_bytes).expect("wrong exponent"),
            }
            .to_der()
            .expect("wrong-exponent PKCS#1 public key"),
        ];
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");

        for embedded_public in mismatches {
            let v2 = rsa_pkcs8_with_public_key(&rsa.private_key, &embedded_public);
            let private_pem =
                format_private_key_text(SshKeyType::Rsa, &v2).expect("RSA PKCS#8 v2 PEM");
            assert_eq!(
                export_cxf(private_pem, public_key.clone()),
                Err(PrimitiveError::InvalidArgument),
            );
        }
    }

    #[test]
    fn cxf_rsa_pkcs8_v2_rejects_a_non_pkcs1_public_key() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");

        for embedded_public in [&[0x30, 0x00][..], rsa.public_key.as_slice()] {
            let v2 = rsa_pkcs8_with_public_key(&rsa.private_key, embedded_public);
            let private_pem =
                format_private_key_text(SshKeyType::Rsa, &v2).expect("RSA PKCS#8 v2 PEM");
            assert_eq!(
                export_cxf(private_pem, public_key.clone()),
                Err(PrimitiveError::InvalidArgument),
            );
        }
    }

    #[test]
    fn cxf_export_rejects_rsa_with_a_mutated_crt_coefficient() {
        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let private = RsaPrivateKey::from_der(&rsa.private_key).expect("complete PKCS#1 key");
        let mut coefficient = private.coefficient.as_bytes().to_vec();
        *coefficient.last_mut().expect("non-empty coefficient") ^= 1;
        let corrupted = RsaPrivateKey {
            coefficient: UintRef::new(&coefficient).expect("mutated coefficient"),
            ..private
        }
        .to_der()
        .expect("corrupted PKCS#1 document");
        let corrupted_pkcs8 = encode_rsa_pkcs8(&corrupted).expect("corrupted PKCS#8 document");
        let private_pem = format_private_key_text(SshKeyType::Rsa, &corrupted_pkcs8)
            .expect("corrupted PKCS#8 PEM");
        let public_key =
            format_public_key_text(SshKeyType::Rsa, &rsa.public_key).expect("OpenSSH public key");

        assert_eq!(
            export_cxf(private_pem, public_key),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    // OpenSSH RSA key generated with `ssh-keygen -t rsa -b 2048`.
    const RSA_OPENSSH_UNENCRYPTED_PEM: &str = concat!(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n",
        "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn\n",
        "NhAAAAAwEAAQAAAQEAmxS5I4AKFa0DNOFYUhFRFxHNge2oo6c2FYgJvHFbxpjxBTuXVWkm\n",
        "RavIg7BP1YSELJzLoiwPwXgDDsLN2Es0UF2EKFGxOtBd3awu5XxDRUx51pq9dxG50mEJtJ\n",
        "KyidmaK5NsqZkCHVPWTmMAD4LgBcnNCU9WseWD7XBex3iUWloTHKrouATHSUBQeiyu4QzG\n",
        "HTUALgmVrZnNfPoWcUhEwqccE56CgKiGKpBHnHoZFFLWwCbKydPwto9aXkuKJAvexCPO/J\n",
        "lAHeJIHEgewHk+lzbQcSSOsKf6WvncCO4knSk8Zq9NnO9Yb/yk32HVrjWV8I5nP73AW6K+\n",
        "SNax3/1blQAAA8gQCTiPEAk4jwAAAAdzc2gtcnNhAAABAQCbFLkjgAoVrQM04VhSEVEXEc\n",
        "2B7aijpzYViAm8cVvGmPEFO5dVaSZFq8iDsE/VhIQsnMuiLA/BeAMOws3YSzRQXYQoUbE6\n",
        "0F3drC7lfENFTHnWmr13EbnSYQm0krKJ2Zork2ypmQIdU9ZOYwAPguAFyc0JT1ax5YPtcF\n",
        "7HeJRaWhMcqui4BMdJQFB6LK7hDMYdNQAuCZWtmc18+hZxSETCpxwTnoKAqIYqkEecehkU\n",
        "UtbAJsrJ0/C2j1peS4okC97EI878mUAd4kgcSB7AeT6XNtBxJI6wp/pa+dwI7iSdKTxmr0\n",
        "2c71hv/KTfYdWuNZXwjmc/vcBbor5I1rHf/VuVAAAAAwEAAQAAAQEAmwnRuWL1Mgxgq0oq\n",
        "EQnM5uJecOmW8d1mHYp+KU2u8dHPC2sy9SmFIJwHf1gRyCWOOkea8QtZyRJhBC3OutEcgM\n",
        "etKt3Y8DKF1Oqhi716R1qYZ+sVRWeMPX3TxRnvsg7AqZXeSYN1cLpzArTIx7kQm9jOyeLu\n",
        "ijUpeoQfzQ2ISvY5i3ZkcYTyyqyPheazlJfwXt6/Wr/pmbjTsvjhNH/t4llOnK1L5X+t3R\n",
        "79YVD0y3XiS8VYZlfs5ZaS0662z0VKJl6MZjPwaFyBQvzkcuIoIMZEuEH0ZrStRoJ/a98s\n",
        "g+iWsN192iP6cY9LDDQsyvaJGV2lkDU/HwLQECsuy/lOQQAAAIEAvjFsYOtXpQytQr4wiz\n",
        "KMX6dJ/hZJ1mXpQ6u5BAwhg2VmjP8fU7rR/1lwVOGsVFTruSdMLIM2gCfyJ6SJSgm552CU\n",
        "oKGiIuMjfxwqyywarSvKjjNunQtsdKNc1miDU1nI/wcEct2FnSYTnXz+00A/iaA3eyyIL0\n",
        "ek2w06scnWQNgAAACBAM4VGmJ6+dt3W3aAlaM1BntrrUQCDA3NqTE4kNkwnPvO4nT/gXDB\n",
        "7ZBl1wb3vo8uOmwgbr9dvMUww0tTKqOcLJmaXBNDVzdKVJYWw/o4HGBWVNgx57X/l1KjP9\n",
        "CoS4GWxfgj4MX/qlzM/69CUQmDcSq08ksPnhiXUZkZbflpSbDxAAAAgQDApRYMMwbdPU2h\n",
        "vGTB2dPQYJk+HB81wtpAXIJCvKdiTyyIBBEYYGAeX56vYMewmD+Rd6S9+EInIv4G8lW2i7\n",
        "Ns1MFjiKZ34qDIqobySEABU0fLg724l0XdRRiourcF/eCY+/qk05PCSKQ5HQ0S7FPxs6NV\n",
        "vRc04G4UCEngczNU5QAAAA1rZXlndWFyZC10ZXN0AQIDBA==\n",
        "-----END OPENSSH PRIVATE KEY-----\n",
    );

    // Passphrase-encrypted Ed25519 key generated with
    // `ssh-keygen -t ed25519 -N <passphrase>`.
    const ED25519_OPENSSH_ENCRYPTED_PEM: &str = concat!(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n",
        "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABCnDxDxS5\n",
        "BFo8Jeur/T/HZrAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIBSzAtbmXa39qqmQ\n",
        "VUO/M4RXU9aZMla7+8LZQeXQnToyAAAAoIYiAjze9Iy89L8TYDrdHq4Dh7wz4haqKfKZg9\n",
        "dk6AzWCNdCEoxC6BpL9L/KJpUuYah/c3cdi0o9GICJSZ02do/tL/TydB6W3B1v3b4goEmh\n",
        "ANg0kr8QmeDUe43QBTxne7xpuLaOwKTvyeLsUR+bvsAO0nueJxIyDDwiTXlYgZY+NRFiNz\n",
        "xsDxulncpVuEaudogpRWO7riU/bOchAMIat9k=\n",
        "-----END OPENSSH PRIVATE KEY-----\n",
    );

    fn material(payload: Vec<u8>) -> SshKeyMaterial {
        SshKeyMaterial::decode(payload.as_slice()).expect("SSH key material payload")
    }

    fn signature(payload: Vec<u8>) -> SshSignature {
        SshSignature::decode(payload.as_slice()).expect("SSH signature payload")
    }

    fn incomplete_pkcs8_rsa(complete_pkcs1: &[u8]) -> Vec<u8> {
        let complete = RsaPrivateKey::from_der(complete_pkcs1).expect("complete PKCS#1 key");
        let zero_bytes = [0_u8];
        let zero = UintRef::new(&zero_bytes).expect("zero INTEGER");
        let incomplete = RsaPrivateKey {
            modulus: complete.modulus,
            public_exponent: zero,
            private_exponent: complete.private_exponent,
            prime1: zero,
            prime2: zero,
            exponent1: zero,
            exponent2: zero,
            coefficient: zero,
            other_prime_infos: None,
        }
        .to_der()
        .expect("incomplete PKCS#1 document");
        PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: RSA_ENCRYPTION_OID,
                parameters: Some(AnyRef::NULL),
            },
            &incomplete,
        )
        .to_der()
        .expect("incomplete PKCS#8 document")
    }

    fn rsa_pkcs8_with_public_key(private_key: &[u8], public_key: &[u8]) -> Vec<u8> {
        let mut info = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: RSA_ENCRYPTION_OID,
                parameters: Some(AnyRef::NULL),
            },
            private_key,
        );
        info.public_key = Some(public_key);
        info.to_der().expect("RSA PKCS#8 v2 document")
    }

    fn ed25519_pkcs8(
        seed: &[u8],
        parameters: Option<AnyRef<'_>>,
        public_key: Option<&[u8]>,
    ) -> Vec<u8> {
        let inner = OctetStringRef::new(seed)
            .expect("nested OCTET STRING")
            .to_der()
            .expect("nested OCTET STRING DER");
        let mut info = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters,
            },
            &inner,
        );
        info.public_key = public_key;
        info.to_der().expect("Ed25519 PKCS#8 document")
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("test hex is UTF-8");
                u8::from_str_radix(pair, 16).expect("test hex decodes")
            })
            .collect()
    }
}
