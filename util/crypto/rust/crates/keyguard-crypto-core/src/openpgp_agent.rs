//! GnuPG agent private operations and canonical S-expression transport.
//!
//! The caller owns Assuan framing. This module performs strict component
//! selection, parses or renders canonical libgcrypt S-expressions, and keeps
//! all RSA private operations inside the audited AWS-LC adapter. rPGP is used
//! only for bounded OpenPGP packet parsing and public RFC 6637 metadata.

use std::io::Cursor;

use ed25519_dalek::Signer as _;
use k256::ecdsa::signature::hazmat::PrehashSigner as _;
use p256::elliptic_curve::sec1::ToEncodedPoint as _;
use pgp::{
    composed::{PublicOrSecret, SignedSecretKey},
    crypto::{
        aes_kw, ecc_curve::ECCCurve, ecdh::build_ecdh_param, hash::HashAlgorithm,
        public_key::PublicKeyAlgorithm, sym::SymmetricKeyAlgorithm,
    },
    ser::Serialize,
    types::{Fingerprint, KeyDetails, KeyVersion, Password, PlainSecretParams, PublicParams},
};
use prost::Message as _;
use thiserror::Error;
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret as X25519Secret};
use zeroize::Zeroizing;

use keyguard_crypto_sensitive::{
    DigestAlgorithm, DigestContext, RsaPrivateComponents, RsaSignatureHash, decrypt_rsa_raw,
    sign_rsa_pkcs1_v1_5_digest,
};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp_packets::{RawPacketError, RawPacketStream},
    openpgp_read::{
        OpenPgpReadBudget, all_components, component_is_expired, encryption_component_usable,
        inspect_certificate, reference_time, signing_component_usable,
    },
    protocol::{
        OpenPgpAgentDecryptRequest, OpenPgpAgentDecryptResult, OpenPgpAgentDecryptSuccess,
        OpenPgpAgentError as ProtocolAgentError, OpenPgpAgentErrorReason, OpenPgpAgentSignRequest,
        OpenPgpAgentSignResult, OpenPgpAgentSignSuccess, open_pgp_agent_decrypt_result,
        open_pgp_agent_sign_result,
    },
};

const MAX_AGENT_KEYS: usize = 64;
const MAX_AGENT_COMPONENTS: usize = 64;
const MAX_AGENT_PACKETS: usize = 4 * 1024;
const MAX_SEXPR_DEPTH: usize = 16;
const MAX_SEXPR_ITEMS: usize = 64;
const MAX_AGENT_OUTPUT_BYTES: usize = 4 * 1024;
const X25519_BYTES: usize = 32;
const X25519_LEGACY_PREFIX: u8 = 0x40;

/// Stable internal failure classification for gpg-agent operations.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub(crate) enum OpenPgpAgentError {
    /// The key document, digest name, or canonical input is malformed.
    #[error("invalid OpenPGP agent request")]
    InvalidArgument,
    /// A control envelope or explicit parser bound was exceeded.
    #[error("OpenPGP agent resource limit exceeded")]
    ResourceLimit,
    /// A private operation or integrity check failed.
    #[error("OpenPGP agent cryptographic operation failed")]
    CryptoFailure,
    /// An invariant failed after otherwise valid parsing.
    #[allow(dead_code)]
    #[error("OpenPGP agent internal failure")]
    Internal,
}

/// Signs an already-computed digest and returns an encoded typed ABI result.
pub(crate) fn sign_request(
    mut request: OpenPgpAgentSignRequest,
) -> Result<Vec<u8>, OpenPgpAgentError> {
    if request.private_key.is_empty()
        || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.hash.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(
            if request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
                || request.hash.len() > MAX_CONTROL_ENVELOPE_BYTES
            {
                OpenPgpAgentError::ResourceLimit
            } else {
                OpenPgpAgentError::InvalidArgument
            },
        );
    }

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let digest = Zeroizing::new(std::mem::take(&mut request.hash));
    let keys = match parse_secret_keys(private_key.as_slice()) {
        Ok(keys) => keys,
        Err(KeyParseError::UnsupportedVersion) => {
            return Ok(encoded_sign_error(
                OpenPgpAgentErrorReason::UnsupportedAlgorithm,
            ));
        }
        Err(KeyParseError::ResourceLimit) => return Err(OpenPgpAgentError::ResourceLimit),
        Err(KeyParseError::Malformed) => return Err(OpenPgpAgentError::InvalidArgument),
    };
    let Some(packet) = select_packet(&keys, &request.preferred_fingerprint, AgentUse::Sign)? else {
        return Ok(encoded_sign_error(OpenPgpAgentErrorReason::KeyNotFound));
    };

    let canonical = match packet.algorithm() {
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSASign => {
            let hash = parse_rsa_hash(&request.hash_algorithm)?;
            let signature = rsa_sign(packet, hash, digest.as_slice())?;
            canonical_signature("rsa", &[("s", minimal_unsigned(&signature))])?
        }
        PublicKeyAlgorithm::ECDSA => {
            let signature = ecdsa_sign(packet, digest.as_slice())?;
            canonical_signature(
                "ecdsa",
                &[
                    ("r", minimal_unsigned(&signature.r)),
                    ("s", minimal_unsigned(&signature.s)),
                ],
            )?
        }
        PublicKeyAlgorithm::EdDSALegacy | PublicKeyAlgorithm::Ed25519 => {
            let signature = ed25519_sign(packet, digest.as_slice())?;
            let (r, s) = signature.split_at(32);
            canonical_signature("eddsa", &[("r", r), ("s", s)])?
        }
        _ => {
            return Ok(encoded_sign_error(
                OpenPgpAgentErrorReason::UnsupportedAlgorithm,
            ));
        }
    };

    Ok(OpenPgpAgentSignResult {
        result: Some(open_pgp_agent_sign_result::Result::Success(
            OpenPgpAgentSignSuccess {
                canonical_sexp: canonical.to_vec(),
            },
        )),
    }
    .encode_to_vec())
}

/// Performs raw RSA or ECDH agent decryption and returns an encoded ABI result.
pub(crate) fn decrypt_request(
    mut request: OpenPgpAgentDecryptRequest,
) -> Result<Vec<u8>, OpenPgpAgentError> {
    if request.private_key.is_empty()
        || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.ciphertext.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(
            if request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
                || request.ciphertext.len() > MAX_CONTROL_ENVELOPE_BYTES
            {
                OpenPgpAgentError::ResourceLimit
            } else {
                OpenPgpAgentError::InvalidArgument
            },
        );
    }

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let keys = match parse_secret_keys(private_key.as_slice()) {
        Ok(keys) => keys,
        Err(KeyParseError::UnsupportedVersion) => {
            return Ok(encoded_decrypt_error(
                OpenPgpAgentErrorReason::UnsupportedAlgorithm,
            ));
        }
        Err(KeyParseError::ResourceLimit) => return Err(OpenPgpAgentError::ResourceLimit),
        Err(KeyParseError::Malformed) => return Err(OpenPgpAgentError::InvalidArgument),
    };
    let Some(packet) = select_packet(&keys, &request.preferred_fingerprint, AgentUse::Decrypt)?
    else {
        // Preserve the JVM implementation's fail-closed ordering: an
        // authoritative selector fails before attacker-controlled ciphertext
        // is parsed.
        return Ok(encoded_decrypt_error(OpenPgpAgentErrorReason::KeyNotFound));
    };

    let ciphertext = Zeroizing::new(std::mem::take(&mut request.ciphertext));
    let encrypted = parse_enc_val(ciphertext.as_slice())?;
    let value = match encrypted.algorithm {
        EncValAlgorithm::Rsa => {
            if !matches!(
                packet.algorithm(),
                PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
            ) {
                return Ok(encoded_decrypt_error(
                    OpenPgpAgentErrorReason::UnsupportedAlgorithm,
                ));
            }
            let ciphertext = encrypted.a.ok_or(OpenPgpAgentError::InvalidArgument)?;
            rsa_decrypt(packet, minimal_unsigned(ciphertext))?
        }
        EncValAlgorithm::Ecdh => {
            if packet.algorithm() != PublicKeyAlgorithm::ECDH {
                return Ok(encoded_decrypt_error(
                    OpenPgpAgentErrorReason::UnsupportedAlgorithm,
                ));
            }
            let ephemeral = encrypted.e.ok_or(OpenPgpAgentError::InvalidArgument)?;
            let wrapped =
                parse_wrapped_key(encrypted.s.ok_or(OpenPgpAgentError::InvalidArgument)?)?;
            ecdh_decrypt(packet, ephemeral, wrapped, request.unwrap_ecdh)?
        }
    };
    let canonical = canonical_value(value.as_slice())?;

    Ok(OpenPgpAgentDecryptResult {
        result: Some(open_pgp_agent_decrypt_result::Result::Success(
            OpenPgpAgentDecryptSuccess {
                canonical_sexp: canonical.to_vec(),
            },
        )),
    }
    .encode_to_vec())
}

fn encoded_sign_error(reason: OpenPgpAgentErrorReason) -> Vec<u8> {
    OpenPgpAgentSignResult {
        result: Some(open_pgp_agent_sign_result::Result::Error(
            ProtocolAgentError {
                reason: reason as i32,
            },
        )),
    }
    .encode_to_vec()
}

fn encoded_decrypt_error(reason: OpenPgpAgentErrorReason) -> Vec<u8> {
    OpenPgpAgentDecryptResult {
        result: Some(open_pgp_agent_decrypt_result::Result::Error(
            ProtocolAgentError {
                reason: reason as i32,
            },
        )),
    }
    .encode_to_vec()
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AgentUse {
    Sign,
    Decrypt,
}

#[derive(Clone, Copy)]
enum SecretPacketRef<'a> {
    Primary(&'a pgp::packet::SecretKey),
    Subkey(&'a pgp::packet::SecretSubkey),
}

impl<'a> SecretPacketRef<'a> {
    fn algorithm(self) -> PublicKeyAlgorithm {
        self.key_details().algorithm()
    }

    fn fingerprint(self) -> Fingerprint {
        self.key_details().fingerprint()
    }

    fn key_details(self) -> &'a dyn KeyDetails {
        match self {
            Self::Primary(key) => key.public_key(),
            Self::Subkey(key) => key.public_key(),
        }
    }

    fn unlock<T>(
        self,
        operation: impl FnOnce(&PublicParams, &PlainSecretParams) -> pgp::errors::Result<T>,
    ) -> Result<T, OpenPgpAgentError> {
        let nested = match self {
            Self::Primary(key) => key.unlock(&Password::empty(), operation),
            Self::Subkey(key) => key.unlock(&Password::empty(), operation),
        }
        .map_err(|_| OpenPgpAgentError::CryptoFailure)?;
        nested.map_err(|_| OpenPgpAgentError::CryptoFailure)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum KeyParseError {
    Malformed,
    UnsupportedVersion,
    ResourceLimit,
}

fn parse_secret_keys(input: &[u8]) -> Result<Vec<SignedSecretKey>, KeyParseError> {
    let packets =
        RawPacketStream::parse(input, MAX_AGENT_PACKETS).map_err(|error| match error {
            RawPacketError::Malformed => KeyParseError::Malformed,
            RawPacketError::ResourceLimit => KeyParseError::ResourceLimit,
        })?;
    let semantic = packets.semantic_bytes();
    let (items, _) = PublicOrSecret::from_reader_many(Cursor::new(semantic.as_slice()))
        .map_err(|_| KeyParseError::Malformed)?;
    let mut keys = Vec::new();
    for item in items.take(MAX_AGENT_KEYS + 1) {
        let item = item.map_err(|_| KeyParseError::Malformed)?;
        let PublicOrSecret::Secret(secret) = item else {
            continue;
        };
        if keys.len() == MAX_AGENT_KEYS {
            return Err(KeyParseError::ResourceLimit);
        }
        if secret.public_subkeys.len() + secret.secret_subkeys.len() > MAX_AGENT_COMPONENTS {
            return Err(KeyParseError::ResourceLimit);
        }
        if std::iter::once(secret.primary_key.version())
            .chain(
                secret
                    .public_subkeys
                    .iter()
                    .map(|subkey| subkey.key.version()),
            )
            .chain(
                secret
                    .secret_subkeys
                    .iter()
                    .map(|subkey| subkey.key.version()),
            )
            .any(|version| matches!(version, KeyVersion::V2 | KeyVersion::V3))
        {
            return Err(KeyParseError::UnsupportedVersion);
        }
        keys.push(secret);
    }
    Ok(keys)
}

fn select_packet<'a>(
    keys: &'a [SignedSecretKey],
    preferred_fingerprint: &str,
    usage: AgentUse,
) -> Result<Option<SecretPacketRef<'a>>, OpenPgpAgentError> {
    if usage == AgentUse::Decrypt && preferred_fingerprint.is_blank() {
        return Ok(None);
    }
    let preferred =
        (!preferred_fingerprint.is_blank()).then(|| normalize_fingerprint(preferred_fingerprint));
    let public_keys = keys
        .iter()
        .map(SignedSecretKey::to_public_key)
        .collect::<Vec<_>>();
    let candidates = all_components(&public_keys);
    let mut budget = OpenPgpReadBudget::default();
    let mut eligible = Vec::new();
    let now = reference_time(None);

    for (secret, public) in keys.iter().zip(&public_keys) {
        let public_offset = secret.public_subkeys.len();
        match usage {
            AgentUse::Sign => {
                let policy = inspect_certificate(public, &candidates, now, &mut budget)
                    .map_err(map_read_error)?;
                let primary_available = policy.primary.authenticated
                    && !policy.primary.revoked
                    && !component_is_expired(&policy.primary, now);
                if primary_available && signing_component_usable(&policy.primary, now, false) {
                    eligible.push(SecretPacketRef::Primary(&secret.primary_key));
                }
                if primary_available {
                    eligible.extend(secret.secret_subkeys.iter().enumerate().filter_map(
                        |(index, subkey)| {
                            let component = policy.subkeys.get(public_offset + index)?;
                            signing_component_usable(component, now, true)
                                .then_some(SecretPacketRef::Subkey(&subkey.key))
                        },
                    ));
                }
            }
            AgentUse::Decrypt => {
                let policy = inspect_certificate(public, &candidates, now, &mut budget)
                    .map_err(map_read_error)?;
                let primary_available = policy.primary.authenticated
                    && !policy.primary.revoked
                    && !component_is_expired(&policy.primary, now);
                if !primary_available {
                    continue;
                }
                if encryption_component_usable(&policy.primary, now) {
                    eligible.push(SecretPacketRef::Primary(&secret.primary_key));
                }
                eligible.extend(secret.secret_subkeys.iter().enumerate().filter_map(
                    |(index, subkey)| {
                        let component = policy.subkeys.get(public_offset + index)?;
                        encryption_component_usable(component, now)
                            .then_some(SecretPacketRef::Subkey(&subkey.key))
                    },
                ));
            }
        }
    }

    if let Some(preferred) = preferred {
        Ok(eligible
            .into_iter()
            .find(|packet| format!("{:X}", packet.fingerprint()) == preferred))
    } else if eligible.len() == 1 {
        Ok(eligible.pop())
    } else {
        Ok(None)
    }
}

fn map_read_error(error: crate::openpgp_read::OpenPgpReadError) -> OpenPgpAgentError {
    match error {
        crate::openpgp_read::OpenPgpReadError::InvalidArgument => {
            OpenPgpAgentError::InvalidArgument
        }
        crate::openpgp_read::OpenPgpReadError::ResourceLimit => OpenPgpAgentError::ResourceLimit,
        crate::openpgp_read::OpenPgpReadError::Internal => OpenPgpAgentError::Internal,
    }
}

trait IsBlank {
    fn is_blank(&self) -> bool;
}

impl IsBlank for str {
    fn is_blank(&self) -> bool {
        self.chars().all(char::is_whitespace)
    }
}

fn normalize_fingerprint(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_uppercase)
        .collect()
}

fn parse_rsa_hash(value: &str) -> Result<RsaSignatureHash, OpenPgpAgentError> {
    match value.to_ascii_lowercase().as_str() {
        "sha1" => Ok(RsaSignatureHash::Sha1),
        "sha224" => Ok(RsaSignatureHash::Sha224),
        "sha256" => Ok(RsaSignatureHash::Sha256),
        "sha384" => Ok(RsaSignatureHash::Sha384),
        "sha512" => Ok(RsaSignatureHash::Sha512),
        _ => Err(OpenPgpAgentError::InvalidArgument),
    }
}

fn rsa_sign(
    packet: SecretPacketRef<'_>,
    hash: RsaSignatureHash,
    digest: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    packet.unlock(|public, private| {
        let components = rsa_components(public, private)?;
        sign_rsa_pkcs1_v1_5_digest(&components, hash, digest)
            .map(Zeroizing::new)
            .map_err(|_| pgp_error("AWS-LC RSA signing failed"))
    })
}

fn rsa_decrypt(
    packet: SecretPacketRef<'_>,
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    packet.unlock(|public, private| {
        let components = rsa_components(public, private)?;
        decrypt_rsa_raw(&components, ciphertext)
            .map(|value| Zeroizing::new(minimal_unsigned(value.as_slice()).to_vec()))
            .map_err(|_| pgp_error("AWS-LC raw RSA decryption failed"))
    })
}

fn rsa_components(
    public: &PublicParams,
    private: &PlainSecretParams,
) -> pgp::errors::Result<RsaPrivateComponents> {
    let PublicParams::RSA(public) = public else {
        return Err(pgp_error("inconsistent RSA public parameters"));
    };
    let PlainSecretParams::RSA(private) = private else {
        return Err(pgp_error("inconsistent RSA private parameters"));
    };

    let mut encoded_public = Vec::new();
    public.to_writer(&mut encoded_public)?;
    let (modulus, remainder) =
        take_mpi(&encoded_public).ok_or_else(|| pgp_error("invalid RSA public modulus"))?;
    let (public_exponent, trailing) =
        take_mpi(remainder).ok_or_else(|| pgp_error("invalid RSA public exponent"))?;
    if !trailing.is_empty() {
        return Err(pgp_error("trailing RSA public parameters"));
    }
    let (private_exponent, prime_p, prime_q, coefficient) = private.to_bytes();
    let private_exponent = Zeroizing::new(private_exponent);
    let _prime_p = Zeroizing::new(prime_p);
    let _prime_q = Zeroizing::new(prime_q);
    let _coefficient = Zeroizing::new(coefficient);

    Ok(RsaPrivateComponents::new(
        modulus.to_vec(),
        public_exponent.to_vec(),
        private_exponent.to_vec(),
        None,
    ))
}

fn take_mpi(input: &[u8]) -> Option<(&[u8], &[u8])> {
    let bits = usize::from(u16::from_be_bytes([*input.first()?, *input.get(1)?]));
    let bytes = bits.checked_add(7)?.checked_div(8)?;
    let end = 2_usize.checked_add(bytes)?;
    Some((input.get(2..end)?, input.get(end..)?))
}

struct EcdsaSignature {
    r: Zeroizing<Vec<u8>>,
    s: Zeroizing<Vec<u8>>,
}

fn ecdsa_sign(
    packet: SecretPacketRef<'_>,
    digest: &[u8],
) -> Result<EcdsaSignature, OpenPgpAgentError> {
    packet.unlock(|_, private| {
        let PlainSecretParams::ECDSA(private) = private else {
            return Err(pgp_error("inconsistent ECDSA private parameters"));
        };
        let secret = Zeroizing::new(private.to_bytes());
        match private.curve() {
            ECCCurve::P256 => {
                let digest = ecdsa_compatible_prehash(digest, 16)?;
                let key = p256::ecdsa::SigningKey::from_slice(secret.as_slice())?;
                let signature: p256::ecdsa::Signature = key.sign_prehash(&digest)?;
                let (r, s) = signature.split_bytes();
                Ok(EcdsaSignature {
                    r: Zeroizing::new(r.to_vec()),
                    s: Zeroizing::new(s.to_vec()),
                })
            }
            ECCCurve::P384 => {
                let digest = ecdsa_compatible_prehash(digest, 24)?;
                let key = p384::ecdsa::SigningKey::from_slice(secret.as_slice())?;
                let signature: p384::ecdsa::Signature = key.sign_prehash(&digest)?;
                let (r, s) = signature.split_bytes();
                Ok(EcdsaSignature {
                    r: Zeroizing::new(r.to_vec()),
                    s: Zeroizing::new(s.to_vec()),
                })
            }
            ECCCurve::P521 => {
                // RustCrypto requires at least half the 66-byte field width;
                // NONEwithECDSA accepts SHA-256 here and interprets it as the
                // same left-zero-padded integer.
                let digest = ecdsa_compatible_prehash(digest, 33)?;
                let key = p521::ecdsa::SigningKey::from_slice(secret.as_slice())?;
                let signature: p521::ecdsa::Signature = key.sign_prehash(&digest)?;
                let (r, s) = signature.split_bytes();
                Ok(EcdsaSignature {
                    r: Zeroizing::new(r.to_vec()),
                    s: Zeroizing::new(s.to_vec()),
                })
            }
            ECCCurve::Secp256k1 => {
                let digest = ecdsa_compatible_prehash(digest, 16)?;
                let key = k256::ecdsa::SigningKey::from_slice(secret.as_slice())?;
                let signature: k256::ecdsa::Signature = key.sign_prehash(&digest)?;
                let (r, s) = signature.split_bytes();
                Ok(EcdsaSignature {
                    r: Zeroizing::new(r.to_vec()),
                    s: Zeroizing::new(s.to_vec()),
                })
            }
            _ => Err(pgp_error("unsupported ECDSA curve")),
        }
    })
}

fn ecdsa_compatible_prehash(
    digest: &[u8],
    minimum: usize,
) -> pgp::errors::Result<Zeroizing<Vec<u8>>> {
    let length = digest.len().max(minimum);
    let mut padded = Zeroizing::new(Vec::new());
    padded
        .try_reserve_exact(length)
        .map_err(|_| pgp_error("ECDSA digest allocation failed"))?;
    padded.resize(length - digest.len(), 0);
    padded.extend_from_slice(digest);
    Ok(padded)
}

fn ed25519_sign(
    packet: SecretPacketRef<'_>,
    digest: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    packet.unlock(|_, private| {
        let bytes = match private {
            PlainSecretParams::Ed25519(key) => key.as_bytes(),
            PlainSecretParams::EdDSALegacy(pgp::crypto::eddsa_legacy::SecretKey::Ed25519(key)) => {
                key.as_bytes()
            }
            _ => return Err(pgp_error("unsupported EdDSA private parameters")),
        };
        let signing_key = ed25519_dalek::SigningKey::from_bytes(bytes);
        Ok(Zeroizing::new(signing_key.sign(digest).to_bytes().to_vec()))
    })
}

struct EcdhSharedSecret {
    kdf_input: Zeroizing<Vec<u8>>,
    legacy_value: Zeroizing<Vec<u8>>,
}

fn ecdh_decrypt(
    packet: SecretPacketRef<'_>,
    ephemeral: &[u8],
    wrapped: &[u8],
    unwrap: bool,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    packet.unlock(|public, private| {
        let PublicParams::ECDH(public) = public else {
            return Err(pgp_error("inconsistent ECDH public parameters"));
        };
        let PlainSecretParams::ECDH(private) = private else {
            return Err(pgp_error("inconsistent ECDH private parameters"));
        };
        let secret = Zeroizing::new(private.to_bytes());
        let shared = match private.curve() {
            ECCCurve::Curve25519Legacy => x25519_shared(secret.as_slice(), ephemeral)?,
            ECCCurve::P256 => p256_shared(secret.as_slice(), ephemeral)?,
            ECCCurve::P384 => p384_shared(secret.as_slice(), ephemeral)?,
            ECCCurve::P521 => p521_shared(secret.as_slice(), ephemeral)?,
            _ => return Err(pgp_error("unsupported ECDH curve")),
        };
        if !unwrap {
            return Ok(shared.legacy_value);
        }

        let (hash, symmetric) = ecdh_algorithms(public)?;
        let key_size = match symmetric {
            SymmetricKeyAlgorithm::AES128 => 16,
            SymmetricKeyAlgorithm::AES192 => 24,
            SymmetricKeyAlgorithm::AES256 => 32,
            _ => return Err(pgp_error("unsupported RFC 6637 AES-KW algorithm")),
        };
        if wrapped.len() < 16 || !wrapped.len().is_multiple_of(8) {
            return Err(pgp_error("invalid RFC 3394 wrapped key length"));
        }
        let fingerprint = packet.fingerprint();
        let param = build_ecdh_param(
            &private.curve().oid(),
            symmetric,
            hash,
            fingerprint.as_bytes(),
        );
        let kek = rfc6637_kdf(hash, shared.kdf_input.as_slice(), key_size, &param)?;
        aes_kw::unwrap(kek.as_slice(), wrapped)
            .map_err(|_| pgp_error("RFC 3394 integrity check failed"))
    })
}

fn ecdh_algorithms(
    public: &pgp::types::EcdhPublicParams,
) -> pgp::errors::Result<(HashAlgorithm, SymmetricKeyAlgorithm)> {
    match public {
        pgp::types::EcdhPublicParams::Curve25519Legacy { hash, alg_sym, .. }
        | pgp::types::EcdhPublicParams::P256 { hash, alg_sym, .. }
        | pgp::types::EcdhPublicParams::P384 { hash, alg_sym, .. }
        | pgp::types::EcdhPublicParams::P521 { hash, alg_sym, .. } => Ok((*hash, *alg_sym)),
        _ => Err(pgp_error("unsupported ECDH public parameters")),
    }
}

fn x25519_shared(secret: &[u8], ephemeral: &[u8]) -> pgp::errors::Result<EcdhSharedSecret> {
    let ephemeral = match ephemeral {
        [X25519_LEGACY_PREFIX, body @ ..] if body.len() == X25519_BYTES => body,
        body if body.len() == X25519_BYTES => body,
        _ => return Err(pgp_error("invalid Curve25519 ephemeral point")),
    };
    let secret: [u8; X25519_BYTES] = secret
        .try_into()
        .map_err(|_| pgp_error("invalid Curve25519 secret"))?;
    let ephemeral: [u8; X25519_BYTES] = ephemeral
        .try_into()
        .map_err(|_| pgp_error("invalid Curve25519 point"))?;
    let shared = X25519Secret::from(secret).diffie_hellman(&X25519PublicKey::from(ephemeral));
    if !shared.was_contributory() {
        return Err(pgp_error("all-zero Curve25519 shared secret"));
    }
    let kdf_input = Zeroizing::new(shared.as_bytes().to_vec());
    let mut legacy_value = Zeroizing::new(Vec::new());
    legacy_value
        .try_reserve_exact(X25519_BYTES + 1)
        .map_err(|_| pgp_error("Curve25519 output allocation failed"))?;
    legacy_value.push(X25519_LEGACY_PREFIX);
    legacy_value.extend_from_slice(shared.as_bytes());
    Ok(EcdhSharedSecret {
        kdf_input,
        legacy_value,
    })
}

fn p256_shared(secret: &[u8], ephemeral: &[u8]) -> pgp::errors::Result<EcdhSharedSecret> {
    if ephemeral.len() != 65 || ephemeral.first() != Some(&0x04) {
        return Err(pgp_error("invalid P-256 ephemeral point"));
    }
    let secret = p256::SecretKey::from_slice(secret)?;
    let public = p256::PublicKey::from_sec1_bytes(ephemeral)?;
    let point = (p256::ProjectivePoint::from(*public.as_affine())
        * secret.to_nonzero_scalar().as_ref())
    .to_affine()
    .to_encoded_point(false);
    nist_shared_from_point(point.as_bytes(), 32)
}

fn p384_shared(secret: &[u8], ephemeral: &[u8]) -> pgp::errors::Result<EcdhSharedSecret> {
    if ephemeral.len() != 97 || ephemeral.first() != Some(&0x04) {
        return Err(pgp_error("invalid P-384 ephemeral point"));
    }
    let secret = p384::SecretKey::from_slice(secret)?;
    let public = p384::PublicKey::from_sec1_bytes(ephemeral)?;
    let point = (p384::ProjectivePoint::from(*public.as_affine())
        * secret.to_nonzero_scalar().as_ref())
    .to_affine()
    .to_encoded_point(false);
    nist_shared_from_point(point.as_bytes(), 48)
}

fn p521_shared(secret: &[u8], ephemeral: &[u8]) -> pgp::errors::Result<EcdhSharedSecret> {
    if ephemeral.len() != 133 || ephemeral.first() != Some(&0x04) {
        return Err(pgp_error("invalid P-521 ephemeral point"));
    }
    let secret = p521::SecretKey::from_slice(secret)?;
    let public = p521::PublicKey::from_sec1_bytes(ephemeral)?;
    let point = (p521::ProjectivePoint::from(*public.as_affine())
        * secret.to_nonzero_scalar().as_ref())
    .to_affine()
    .to_encoded_point(false);
    nist_shared_from_point(point.as_bytes(), 66)
}

fn nist_shared_from_point(
    point: &[u8],
    coordinate_size: usize,
) -> pgp::errors::Result<EcdhSharedSecret> {
    let expected = coordinate_size
        .checked_mul(2)
        .and_then(|length| length.checked_add(1))
        .ok_or_else(|| pgp_error("ECDH point length overflow"))?;
    if point.len() != expected || point.first() != Some(&0x04) {
        return Err(pgp_error("invalid ECDH shared point"));
    }
    Ok(EcdhSharedSecret {
        kdf_input: Zeroizing::new(point[1..1 + coordinate_size].to_vec()),
        legacy_value: Zeroizing::new(point.to_vec()),
    })
}

fn rfc6637_kdf(
    hash: HashAlgorithm,
    shared: &[u8],
    output_size: usize,
    param: &[u8],
) -> pgp::errors::Result<Zeroizing<Vec<u8>>> {
    let algorithm = match hash {
        HashAlgorithm::Sha256 => DigestAlgorithm::Sha256,
        HashAlgorithm::Sha384 => DigestAlgorithm::Sha384,
        HashAlgorithm::Sha512 => DigestAlgorithm::Sha512,
        _ => return Err(pgp_error("unsupported RFC 6637 hash algorithm")),
    };
    if output_size > algorithm.output_size() {
        return Err(pgp_error("RFC 6637 digest shorter than KEK"));
    }
    let mut context = DigestContext::new(algorithm)
        .map_err(|_| pgp_error("RFC 6637 digest initialization failed"))?;
    context
        .update(&[0, 0, 0, 1])
        .and_then(|()| context.update(shared))
        .and_then(|()| context.update(param))
        .map_err(|_| pgp_error("RFC 6637 digest update failed"))?;
    let mut digest = Zeroizing::new([0_u8; 64]);
    context
        .finalize_into(&mut digest[..algorithm.output_size()])
        .map_err(|_| pgp_error("RFC 6637 digest finalization failed"))?;
    Ok(Zeroizing::new(digest[..output_size].to_vec()))
}

fn parse_wrapped_key(input: &[u8]) -> Result<&[u8], OpenPgpAgentError> {
    let (&declared, body) = input
        .split_first()
        .ok_or(OpenPgpAgentError::InvalidArgument)?;
    if usize::from(declared) != body.len() {
        return Err(OpenPgpAgentError::InvalidArgument);
    }
    Ok(body)
}

fn pgp_error(message: &'static str) -> pgp::errors::Error {
    message.to_owned().into()
}

fn minimal_unsigned(value: &[u8]) -> &[u8] {
    let first = value
        .iter()
        .position(|byte| *byte != 0)
        .unwrap_or(value.len().saturating_sub(1));
    &value[first..]
}

#[derive(Debug)]
enum SExpr<'a> {
    Atom(&'a [u8]),
    List(Vec<SExpr<'a>>),
}

struct CanonicalCursor<'a> {
    input: &'a [u8],
    offset: usize,
    items: usize,
}

impl<'a> CanonicalCursor<'a> {
    fn parse(input: &'a [u8]) -> Result<SExpr<'a>, OpenPgpAgentError> {
        if input.is_empty() || input.len() > MAX_CONTROL_ENVELOPE_BYTES {
            return Err(if input.len() > MAX_CONTROL_ENVELOPE_BYTES {
                OpenPgpAgentError::ResourceLimit
            } else {
                OpenPgpAgentError::InvalidArgument
            });
        }
        let mut cursor = Self {
            input,
            offset: 0,
            items: 0,
        };
        let value = cursor.element(0)?;
        if cursor.offset != input.len() {
            return Err(OpenPgpAgentError::InvalidArgument);
        }
        Ok(value)
    }

    fn element(&mut self, depth: usize) -> Result<SExpr<'a>, OpenPgpAgentError> {
        self.items = self
            .items
            .checked_add(1)
            .filter(|count| *count <= MAX_SEXPR_ITEMS)
            .ok_or(OpenPgpAgentError::ResourceLimit)?;
        match self.input.get(self.offset) {
            Some(b'(') => self.list(depth),
            Some(_) => self.atom(),
            None => Err(OpenPgpAgentError::InvalidArgument),
        }
    }

    fn list(&mut self, depth: usize) -> Result<SExpr<'a>, OpenPgpAgentError> {
        if depth >= MAX_SEXPR_DEPTH {
            return Err(OpenPgpAgentError::ResourceLimit);
        }
        self.offset += 1;
        let mut items = Vec::new();
        loop {
            match self.input.get(self.offset) {
                Some(b')') => {
                    self.offset += 1;
                    return Ok(SExpr::List(items));
                }
                Some(_) => {
                    items
                        .try_reserve(1)
                        .map_err(|_| OpenPgpAgentError::ResourceLimit)?;
                    items.push(self.element(depth + 1)?);
                }
                None => return Err(OpenPgpAgentError::InvalidArgument),
            }
        }
    }

    fn atom(&mut self) -> Result<SExpr<'a>, OpenPgpAgentError> {
        let mut length = 0_usize;
        let mut digits = 0_usize;
        while let Some(byte @ b'0'..=b'9') = self.input.get(self.offset).copied() {
            length = length
                .checked_mul(10)
                .and_then(|value| value.checked_add(usize::from(byte - b'0')))
                .filter(|value| *value <= self.input.len())
                .ok_or(OpenPgpAgentError::InvalidArgument)?;
            self.offset += 1;
            digits += 1;
        }
        if digits == 0 || self.input.get(self.offset) != Some(&b':') {
            return Err(OpenPgpAgentError::InvalidArgument);
        }
        self.offset += 1;
        let end = self
            .offset
            .checked_add(length)
            .filter(|end| *end <= self.input.len())
            .ok_or(OpenPgpAgentError::InvalidArgument)?;
        let atom = &self.input[self.offset..end];
        self.offset = end;
        Ok(SExpr::Atom(atom))
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum EncValAlgorithm {
    Rsa,
    Ecdh,
}

struct EncVal<'a> {
    algorithm: EncValAlgorithm,
    a: Option<&'a [u8]>,
    e: Option<&'a [u8]>,
    s: Option<&'a [u8]>,
}

fn parse_enc_val(input: &[u8]) -> Result<EncVal<'_>, OpenPgpAgentError> {
    let SExpr::List(outer) = CanonicalCursor::parse(input)? else {
        return Err(OpenPgpAgentError::InvalidArgument);
    };
    if !matches!(outer.first(), Some(SExpr::Atom(b"enc-val"))) {
        return Err(OpenPgpAgentError::InvalidArgument);
    }
    let mut algorithm_list = None;
    for item in &outer[1..] {
        let SExpr::List(items) = item else {
            return Err(OpenPgpAgentError::InvalidArgument);
        };
        let Some(SExpr::Atom(name)) = items.first() else {
            return Err(OpenPgpAgentError::InvalidArgument);
        };
        if *name == b"flags" {
            continue;
        }
        if *name == b"rsa" || *name == b"ecdh" || *name == b"ecc" {
            if algorithm_list.replace(items.as_slice()).is_some() {
                return Err(OpenPgpAgentError::InvalidArgument);
            }
        } else {
            return Err(OpenPgpAgentError::InvalidArgument);
        }
    }
    let list = algorithm_list.ok_or(OpenPgpAgentError::InvalidArgument)?;
    let algorithm_name = match list.first() {
        Some(SExpr::Atom(name)) => *name,
        _ => return Err(OpenPgpAgentError::InvalidArgument),
    };
    let algorithm = if algorithm_name == b"rsa" {
        EncValAlgorithm::Rsa
    } else if algorithm_name == b"ecdh" || algorithm_name == b"ecc" {
        EncValAlgorithm::Ecdh
    } else {
        return Err(OpenPgpAgentError::InvalidArgument);
    };
    let mut result = EncVal {
        algorithm,
        a: None,
        e: None,
        s: None,
    };
    for parameter in &list[1..] {
        let SExpr::List(values) = parameter else {
            return Err(OpenPgpAgentError::InvalidArgument);
        };
        let [SExpr::Atom(name), SExpr::Atom(value)] = values.as_slice() else {
            return Err(OpenPgpAgentError::InvalidArgument);
        };
        let slot = match *name {
            b"a" => &mut result.a,
            b"e" => &mut result.e,
            b"s" => &mut result.s,
            _ => continue,
        };
        if slot.replace(*value).is_some() {
            return Err(OpenPgpAgentError::InvalidArgument);
        }
    }
    Ok(result)
}

struct CanonicalWriter {
    output: Zeroizing<Vec<u8>>,
}

impl CanonicalWriter {
    fn new() -> Self {
        Self {
            output: Zeroizing::new(Vec::new()),
        }
    }

    fn byte(&mut self, value: u8) -> Result<(), OpenPgpAgentError> {
        self.reserve(1)?;
        self.output.push(value);
        Ok(())
    }

    fn atom(&mut self, value: &[u8]) -> Result<(), OpenPgpAgentError> {
        let length = value.len().to_string();
        let additional = length
            .len()
            .checked_add(1)
            .and_then(|size| size.checked_add(value.len()))
            .ok_or(OpenPgpAgentError::ResourceLimit)?;
        self.reserve(additional)?;
        self.output.extend_from_slice(length.as_bytes());
        self.output.push(b':');
        self.output.extend_from_slice(value);
        Ok(())
    }

    fn reserve(&mut self, additional: usize) -> Result<(), OpenPgpAgentError> {
        self.output
            .len()
            .checked_add(additional)
            .filter(|length| *length <= MAX_AGENT_OUTPUT_BYTES)
            .ok_or(OpenPgpAgentError::ResourceLimit)?;
        self.output
            .try_reserve_exact(additional)
            .map_err(|_| OpenPgpAgentError::ResourceLimit)
    }
}

fn canonical_signature(
    algorithm: &str,
    components: &[(&str, &[u8])],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    let mut writer = CanonicalWriter::new();
    writer.byte(b'(')?;
    writer.atom(b"sig-val")?;
    writer.byte(b'(')?;
    writer.atom(algorithm.as_bytes())?;
    for (name, value) in components {
        writer.byte(b'(')?;
        writer.atom(name.as_bytes())?;
        writer.atom(value)?;
        writer.byte(b')')?;
    }
    writer.byte(b')')?;
    writer.byte(b')')?;
    Ok(writer.output)
}

fn canonical_value(value: &[u8]) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    let mut writer = CanonicalWriter::new();
    writer.byte(b'(')?;
    writer.atom(b"value")?;
    writer.atom(value)?;
    writer.byte(b')')?;
    Ok(writer.output)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic, clippy::unwrap_used)]

    use super::*;
    use crate::{
        openpgp_write::generate_key_request,
        protocol::{
            OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial,
            OpenPgpMetadataResolveRequest, OpenPgpMetadataResolveResult,
            OpenPgpPublicKeyParseRequest, OpenPgpPublicKeyParseResult,
            open_pgp_agent_decrypt_result, open_pgp_agent_sign_result,
            open_pgp_public_key_parse_result,
        },
    };
    use pgp::{
        composed::{ArmorOptions, Deserializable},
        packet::{KeyFlags, SignatureConfig, SignatureType, Subpacket, SubpacketData},
        types::{Duration, Mpi, SignatureBytes, Timestamp, VerifyingKey},
    };
    use proptest::prelude::*;

    const TEST_TIME: u64 = 1_700_000_000;

    #[test]
    fn canonical_enc_val_preserves_binary_atoms_and_flags() {
        let input = b"(7:enc-val(5:flags3:raw)(4:ecdh(1:e4:\0@\x7f\xff)(1:s3:\x02ab)))";
        let parsed = parse_enc_val(input).expect("canonical expression parses");
        assert_eq!(parsed.algorithm, EncValAlgorithm::Ecdh);
        assert_eq!(parsed.e, Some(&[0, 0x40, 0x7f, 0xff][..]));
        assert_eq!(parsed.s, Some(&[2, b'a', b'b'][..]));
    }

    #[test]
    fn canonical_parser_rejects_trailing_overflow_duplicate_and_deep_inputs() {
        for malformed in [
            b"(7:enc-val(3:rsa(1:a1:x)))junk".as_slice(),
            b"(7:enc-val(3:rsa(1:a1:x)(1:a1:y)))".as_slice(),
            b"(7:enc-val(3:rsa(1:a999999999999999999999:x)))".as_slice(),
        ] {
            assert!(parse_enc_val(malformed).is_err());
        }
        let mut deep = vec![b'('; MAX_SEXPR_DEPTH + 1];
        deep.extend_from_slice(b"1:x");
        deep.extend(std::iter::repeat_n(b')', MAX_SEXPR_DEPTH + 1));
        assert_eq!(
            CanonicalCursor::parse(&deep).expect_err("depth bound must fail"),
            OpenPgpAgentError::ResourceLimit,
        );
    }

    proptest! {
        #[test]
        fn canonical_parser_contains_arbitrary_malformed_input(
            input in proptest::collection::vec(any::<u8>(), 0..2_048),
        ) {
            let _ = CanonicalCursor::parse(&input);
        }
    }

    #[test]
    fn canonical_renderers_emit_transport_not_advanced_hex() {
        assert_eq!(
            canonical_signature("ecdsa", &[("r", &[0, 1]), ("s", &[2, 3])])
                .expect("signature renders")
                .as_slice(),
            b"(7:sig-val(5:ecdsa(1:r2:\0\x01)(1:s2:\x02\x03)))",
        );
        assert_eq!(
            canonical_value(&[0, 0xff])
                .expect("value renders")
                .as_slice(),
            b"(5:value2:\0\xff)",
        );
    }

    #[test]
    fn minimal_unsigned_keeps_one_zero_octet() {
        assert_eq!(minimal_unsigned(&[0, 0, 1]), &[1]);
        assert_eq!(minimal_unsigned(&[0, 0]), &[0]);
        assert!(minimal_unsigned(&[]).is_empty());
    }

    #[test]
    fn rsa_agent_signing_routes_prehashed_private_work_through_aws_lc() {
        let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
        let keys = parse_secret_keys(&material.private_key_armored).expect("parse generated RSA");
        let packet = keys
            .iter()
            .flat_map(|key| {
                key.secret_subkeys
                    .iter()
                    .map(|subkey| SecretPacketRef::Subkey(&subkey.key))
            })
            .find(|packet| {
                matches!(
                    packet.algorithm(),
                    PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSASign
                )
            })
            .expect("generated RSA signing component");
        let fingerprint = format!("{:X}", packet.fingerprint());
        let digest = [0x5a_u8; 32];

        let response = OpenPgpAgentSignResult::decode(
            sign_request(OpenPgpAgentSignRequest {
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: fingerprint,
                hash_algorithm: "sha256".to_owned(),
                hash: digest.to_vec(),
            })
            .expect("agent RSA signing")
            .as_slice(),
        )
        .expect("decode agent RSA result");
        let canonical = match response.result.as_ref() {
            Some(open_pgp_agent_sign_result::Result::Success(success)) => {
                success.canonical_sexp.as_slice()
            }
            result => panic!("expected RSA signing success, got {result:?}"),
        };
        let signature = signature_components(canonical, b"rsa");
        assert_eq!(signature.len(), 1);
        assert_eq!(signature[0].0, b"s");
        let signature = SignatureBytes::Mpis(vec![Mpi::from_slice(&signature[0].1)]);
        verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
    }

    #[test]
    fn ed25519_agent_signing_keeps_fixed_width_components() {
        let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
        let keys =
            parse_secret_keys(&material.private_key_armored).expect("parse generated Ed25519");
        let packet = keys
            .iter()
            .flat_map(|key| {
                key.secret_subkeys
                    .iter()
                    .map(|subkey| SecretPacketRef::Subkey(&subkey.key))
            })
            .find(|packet| {
                matches!(
                    packet.algorithm(),
                    PublicKeyAlgorithm::EdDSALegacy | PublicKeyAlgorithm::Ed25519
                )
            })
            .expect("generated Ed25519 signing component");
        let fingerprint = format!("{:X}", packet.fingerprint());
        let digest = [0xa5_u8; 32];

        let response = OpenPgpAgentSignResult::decode(
            sign_request(OpenPgpAgentSignRequest {
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: fingerprint,
                hash_algorithm: "sha256".to_owned(),
                hash: digest.to_vec(),
            })
            .expect("agent Ed25519 signing")
            .as_slice(),
        )
        .expect("decode agent Ed25519 result");
        let canonical = match response.result.as_ref() {
            Some(open_pgp_agent_sign_result::Result::Success(success)) => {
                success.canonical_sexp.as_slice()
            }
            result => panic!("expected Ed25519 signing success, got {result:?}"),
        };
        let signature = signature_components(canonical, b"eddsa");
        assert_eq!(signature.len(), 2);
        assert_eq!(signature[0].0, b"r");
        assert_eq!(signature[1].0, b"s");
        assert_eq!(signature[0].1.len(), 32);
        assert_eq!(signature[1].1.len(), 32);
        let signature = SignatureBytes::Mpis(vec![
            Mpi::from_slice(&signature[0].1),
            Mpi::from_slice(&signature[1].1),
        ]);
        verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
    }

    #[test]
    fn x25519_agent_decryption_returns_legacy_and_rfc6637_values() {
        let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
        let keys = parse_secret_keys(&material.private_key_armored).expect("parse generated ECDH");
        let packet = keys
            .iter()
            .flat_map(secret_packets)
            .find(|packet| packet.algorithm() == PublicKeyAlgorithm::ECDH)
            .expect("generated Curve25519 component");
        let fingerprint = format!("{:X}", packet.fingerprint());
        let recipient_secret = packet
            .unlock(|_, private| {
                let PlainSecretParams::ECDH(private) = private else {
                    return Err(pgp_error("expected ECDH private parameters"));
                };
                if private.curve() != ECCCurve::Curve25519Legacy {
                    return Err(pgp_error("expected legacy Curve25519"));
                }
                Ok(private.to_bytes())
            })
            .expect("unlock generated Curve25519");
        let recipient_secret: [u8; 32] = recipient_secret
            .as_slice()
            .try_into()
            .expect("Curve25519 scalar width");
        let recipient_public = X25519PublicKey::from(&X25519Secret::from(recipient_secret));
        let ephemeral_secret = X25519Secret::from([0x33_u8; 32]);
        let ephemeral_public = X25519PublicKey::from(&ephemeral_secret);
        let shared = ephemeral_secret.diffie_hellman(&recipient_public);
        assert!(shared.was_contributory());

        let PublicParams::ECDH(public) = packet.key_details().public_params() else {
            panic!("generated ECDH public parameters");
        };
        let (hash, symmetric) = ecdh_algorithms(public).expect("supported RFC 6637 algorithms");
        let param = build_ecdh_param(
            &ECCCurve::Curve25519Legacy.oid(),
            symmetric,
            hash,
            packet.fingerprint().as_bytes(),
        );
        let kek = rfc6637_kdf(hash, shared.as_bytes(), symmetric.key_size(), &param)
            .expect("derive RFC 6637 KEK");
        let padded_session_key = [0x42_u8; 40];
        let wrapped = aes_kw::wrap(kek.as_slice(), &padded_session_key).expect("AES-wrap session");
        let mut encoded_wrapped = vec![u8::try_from(wrapped.len()).expect("wrapped key length")];
        encoded_wrapped.extend_from_slice(&wrapped);
        let mut encoded_ephemeral = vec![X25519_LEGACY_PREFIX];
        encoded_ephemeral.extend_from_slice(ephemeral_public.as_bytes());
        let enc_val = ecdh_enc_val(&encoded_ephemeral, &encoded_wrapped);

        let decrypt = |unwrap_ecdh| {
            OpenPgpAgentDecryptResult::decode(
                decrypt_request(OpenPgpAgentDecryptRequest {
                    private_key: material.private_key_armored.clone(),
                    preferred_fingerprint: fingerprint.clone(),
                    ciphertext: enc_val.clone(),
                    unwrap_ecdh,
                })
                .expect("agent Curve25519 decryption")
                .as_slice(),
            )
            .expect("decode agent Curve25519 result")
        };
        let unwrapped = decrypt(true);
        assert_eq!(
            decrypt_value(&unwrapped),
            padded_session_key,
            "agent must leave RFC 6637 PKCS#5 padding for gpg",
        );
        let legacy = decrypt(false);
        let mut expected_legacy = vec![X25519_LEGACY_PREFIX];
        expected_legacy.extend_from_slice(shared.as_bytes());
        assert_eq!(decrypt_value(&legacy), expected_legacy);

        let missing = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: "0".repeat(40),
                ciphertext: Vec::new(),
                unwrap_ecdh: false,
            })
            .expect("missing selector is a typed result")
            .as_slice(),
        )
        .expect("decode missing selector result");
        assert!(matches!(
            missing.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));

        let blank = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: String::new(),
                ciphertext: Vec::new(),
                unwrap_ecdh: false,
            })
            .expect("blank selector is a typed result")
            .as_slice(),
        )
        .expect("decode blank-selector result");
        assert!(matches!(
            blank.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));
    }

    #[test]
    fn expired_or_revoked_components_are_not_routable() {
        let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
        let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated RSA key");
        let primary = &secret.primary_key;
        let target = secret.secret_subkeys[1].key.public_key().clone();
        let fingerprint = format!("{:X}", target.fingerprint());
        let mut flags = KeyFlags::default();
        flags.set_sign(true);
        let mut config = SignatureConfig::v4(
            SignatureType::SubkeyBinding,
            primary.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                TEST_TIME as u32,
            )))
            .expect("signature creation subpacket"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
                .expect("issuer fingerprint subpacket"),
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                1,
            )))
            .expect("signature expiration subpacket"),
            Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags subpacket"),
        ];
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
                .expect("issuer key ID subpacket"),
        ];
        let binding = config
            .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &target)
            .expect("create sign-only binding");
        secret.secret_subkeys[1].signatures = vec![binding];
        let mut revocation = SignatureConfig::v4(
            SignatureType::KeyRevocation,
            primary.algorithm(),
            HashAlgorithm::Sha256,
        );
        revocation.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                (TEST_TIME + 2) as u32,
            )))
            .expect("revocation creation subpacket"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
                .expect("revocation issuer fingerprint"),
        ];
        revocation.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
                .expect("revocation issuer key ID"),
        ];
        let revocation = revocation
            .sign_key(primary, &Password::empty(), primary.public_key())
            .expect("create primary revocation");
        secret.details.revocation_signatures.push(revocation);

        let public = secret.to_public_key();
        let candidates = all_components(std::slice::from_ref(&public));
        let mut budget = OpenPgpReadBudget::default();
        let policy = inspect_certificate(&public, &candidates, TEST_TIME + 1, &mut budget)
            .expect("inspect sign-only certificate");
        let component = &policy.subkeys[secret.public_subkeys.len() + 1];
        assert!(!component.authenticated);
        assert!(!encryption_component_usable(component, TEST_TIME + 1));
        let public_info = OpenPgpPublicKeyParseResult::decode(
            crate::openpgp_read::parse_public_key_request(OpenPgpPublicKeyParseRequest {
                key_data: public
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("armor sign-only public key"),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
            })
            .expect("parse current public policy")
            .as_slice(),
        )
        .expect("decode current public policy");
        let Some(open_pgp_public_key_parse_result::Result::Success(success)) = public_info.result
        else {
            panic!("expected current public-key information");
        };
        assert!(success.keys[0].revoked);
        assert!(!success.keys[0].can_encrypt);

        let private_key = secret
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor sign-only key");
        let decrypt_result = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: private_key.clone(),
                preferred_fingerprint: fingerprint.clone(),
                ciphertext: Vec::new(),
                unwrap_ecdh: false,
            })
            .expect("expired component must return a typed result")
            .as_slice(),
        )
        .expect("decode expired-component result");
        assert!(matches!(
            decrypt_result.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));

        let metadata = OpenPgpMetadataResolveResult::decode(
            crate::openpgp_read::resolve_metadata(OpenPgpMetadataResolveRequest {
                private_key_data: Some(private_key),
                public_key_data: None,
                normalized_fingerprint: material.fingerprint.clone(),
                candidate_revocation_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
            })
            .expect("resolve current metadata")
            .as_slice(),
        )
        .expect("decode current metadata");
        assert_eq!(metadata.metadata, None);
    }

    #[test]
    fn current_rsa_metadata_respects_key_flags() {
        let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
        let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated RSA key");
        let primary_fingerprint = format!("{:X}", secret.primary_key.fingerprint());
        let signing_fingerprint = format!("{:X}", secret.secret_subkeys[0].key.fingerprint());
        let encryption_fingerprint = format!("{:X}", secret.secret_subkeys[1].key.fingerprint());
        let metadata = OpenPgpMetadataResolveResult::decode(
            crate::openpgp_read::resolve_metadata(OpenPgpMetadataResolveRequest {
                private_key_data: Some(material.private_key_armored.clone()),
                public_key_data: None,
                normalized_fingerprint: primary_fingerprint.clone(),
                candidate_revocation_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("resolve current RSA metadata")
            .as_slice(),
        )
        .expect("decode current RSA metadata")
        .metadata
        .expect("current RSA metadata");

        let key = |fingerprint: &str| {
            metadata
                .keys
                .iter()
                .find(|key| key.fingerprint == fingerprint)
                .unwrap_or_else(|| panic!("missing metadata for {fingerprint}"))
        };
        assert!(key(&primary_fingerprint).capabilities.is_empty());
        assert_eq!(key(&signing_fingerprint).capabilities, ["sign"]);
        assert_eq!(key(&encryption_fingerprint).capabilities, ["decrypt"]);

        let decrypt_result = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: secret
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("armor generated RSA key"),
                preferred_fingerprint: signing_fingerprint,
                ciphertext: Vec::new(),
                unwrap_ecdh: false,
            })
            .expect("sign-only RSA selection must return a typed result")
            .as_slice(),
        )
        .expect("decode sign-only RSA result");
        assert!(matches!(
            decrypt_result.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));
    }

    #[test]
    fn agent_rejects_unbound_signing_and_decryption_subkeys() {
        let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
        let (mut signing_unbound, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated signing key");
        let signing_fingerprint =
            format!("{:X}", signing_unbound.secret_subkeys[0].key.fingerprint());
        signing_unbound.secret_subkeys[0].signatures =
            signing_unbound.secret_subkeys[1].signatures.clone();
        let signing_unbound = signing_unbound
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor unbound signing key");
        assert_sign_key_not_found(signing_unbound, signing_fingerprint);

        let (mut decryption_unbound, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated decryption key");
        let decryption_fingerprint = format!(
            "{:X}",
            decryption_unbound.secret_subkeys[1].key.fingerprint()
        );
        decryption_unbound.secret_subkeys[1].signatures =
            decryption_unbound.secret_subkeys[0].signatures.clone();
        let decryption_unbound = decryption_unbound
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor unbound decryption key");
        let response = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: decryption_unbound,
                preferred_fingerprint: decryption_fingerprint,
                ciphertext: Vec::new(),
                unwrap_ecdh: false,
            })
            .expect("unbound selector is a typed result")
            .as_slice(),
        )
        .expect("decode unbound decryption result");
        assert!(matches!(
            response.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));
    }

    #[test]
    fn agent_signing_requires_authenticated_sign_flag_and_cross_certification() {
        let rsa = generated_material(OpenPgpKeyKind::Rsa, 3_072);
        let (rsa, _) =
            SignedSecretKey::from_reader_single(Cursor::new(rsa.private_key_armored.as_slice()))
                .expect("parse generated RSA key");
        let encryption_fingerprint = format!("{:X}", rsa.secret_subkeys[1].key.fingerprint());
        assert_sign_key_not_found(
            rsa.to_armored_bytes(ArmorOptions::default())
                .expect("armor RSA key"),
            encryption_fingerprint,
        );

        let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
        let (mut missing_cross_certification, _) = SignedSecretKey::from_reader_single(
            Cursor::new(material.private_key_armored.as_slice()),
        )
        .expect("parse generated signing key");
        let primary = &missing_cross_certification.primary_key;
        let signing_subkey = missing_cross_certification.secret_subkeys[0]
            .key
            .public_key()
            .clone();
        let signing_fingerprint = format!("{:X}", signing_subkey.fingerprint());
        let mut flags = KeyFlags::default();
        flags.set_sign(true);
        let mut config = SignatureConfig::v4(
            SignatureType::SubkeyBinding,
            primary.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                TEST_TIME as u32,
            )))
            .expect("signature creation subpacket"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
                .expect("issuer fingerprint subpacket"),
            Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags subpacket"),
        ];
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
                .expect("issuer key ID subpacket"),
        ];
        let binding = config
            .sign_subkey_binding(
                primary,
                primary.public_key(),
                &Password::empty(),
                &signing_subkey,
            )
            .expect("create binding without back-signature");
        missing_cross_certification.secret_subkeys[0].signatures = vec![binding];
        assert_sign_key_not_found(
            missing_cross_certification
                .to_armored_bytes(ArmorOptions::default())
                .expect("armor key missing cross-certification"),
            signing_fingerprint,
        );
    }

    fn generated_material(kind: OpenPgpKeyKind, rsa_bits: u32) -> OpenPgpKeyMaterial {
        OpenPgpKeyMaterial::decode(
            generate_key_request(OpenPgpKeyGenerateRequest {
                kind: kind as i32,
                user_id: "Agent Test <agent@example.test>".to_owned(),
                rsa_bits,
                creation_time_epoch_seconds: TEST_TIME,
                expiration_seconds: None,
            })
            .expect("generate agent test certificate")
            .as_slice(),
        )
        .expect("decode generated agent key material")
    }

    fn assert_sign_key_not_found(private_key: Vec<u8>, preferred_fingerprint: String) {
        let response = OpenPgpAgentSignResult::decode(
            sign_request(OpenPgpAgentSignRequest {
                private_key,
                preferred_fingerprint,
                hash_algorithm: "sha256".to_owned(),
                hash: vec![0x5a; 32],
            })
            .expect("unusable signing selector is a typed result")
            .as_slice(),
        )
        .expect("decode unusable signing result");
        assert!(matches!(
            response.result,
            Some(open_pgp_agent_sign_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
        ));
    }

    fn secret_packets(key: &SignedSecretKey) -> impl Iterator<Item = SecretPacketRef<'_>> {
        std::iter::once(SecretPacketRef::Primary(&key.primary_key)).chain(
            key.secret_subkeys
                .iter()
                .map(|subkey| SecretPacketRef::Subkey(&subkey.key)),
        )
    }

    fn verify_with_packet(
        packet: SecretPacketRef<'_>,
        hash: HashAlgorithm,
        digest: &[u8],
        signature: &SignatureBytes,
    ) {
        match packet {
            SecretPacketRef::Primary(key) => key
                .public_key()
                .verify(hash, digest, signature)
                .expect("agent signature verifies"),
            SecretPacketRef::Subkey(key) => key
                .public_key()
                .verify(hash, digest, signature)
                .expect("agent signature verifies"),
        }
    }

    fn signature_components(input: &[u8], algorithm: &[u8]) -> Vec<(Vec<u8>, Vec<u8>)> {
        let SExpr::List(outer) = CanonicalCursor::parse(input).expect("parse signature expression")
        else {
            panic!("signature expression must be a list");
        };
        assert!(matches!(outer.first(), Some(SExpr::Atom(b"sig-val"))));
        let Some(SExpr::List(signature)) = outer.get(1) else {
            panic!("signature body must be a list");
        };
        assert!(matches!(signature.first(), Some(SExpr::Atom(name)) if *name == algorithm));
        signature[1..]
            .iter()
            .map(|component| {
                let SExpr::List(component) = component else {
                    panic!("signature component must be a list");
                };
                let [SExpr::Atom(name), SExpr::Atom(value)] = component.as_slice() else {
                    panic!("signature component must contain a name and value");
                };
                (name.to_vec(), value.to_vec())
            })
            .collect()
    }

    fn ecdh_enc_val(ephemeral: &[u8], wrapped: &[u8]) -> Vec<u8> {
        let mut writer = CanonicalWriter::new();
        writer.byte(b'(').expect("outer list");
        writer.atom(b"enc-val").expect("enc-val atom");
        writer.byte(b'(').expect("ECDH list");
        writer.atom(b"ecdh").expect("ECDH atom");
        for (name, value) in [(b"e".as_slice(), ephemeral), (b"s".as_slice(), wrapped)] {
            writer.byte(b'(').expect("parameter list");
            writer.atom(name).expect("parameter name");
            writer.atom(value).expect("parameter value");
            writer.byte(b')').expect("parameter close");
        }
        writer.byte(b')').expect("ECDH close");
        writer.byte(b')').expect("outer close");
        writer.output.to_vec()
    }

    fn decrypt_value(result: &OpenPgpAgentDecryptResult) -> Vec<u8> {
        let canonical = match result.result.as_ref() {
            Some(open_pgp_agent_decrypt_result::Result::Success(success)) => {
                success.canonical_sexp.as_slice()
            }
            result => panic!("expected decrypt success, got {result:?}"),
        };
        let SExpr::List(value) = CanonicalCursor::parse(canonical).expect("parse value expression")
        else {
            panic!("value expression must be a list");
        };
        let [SExpr::Atom(b"value"), SExpr::Atom(value)] = value.as_slice() else {
            panic!("expected canonical value expression");
        };
        value.to_vec()
    }
}
