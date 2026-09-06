//! GnuPG agent private operations and canonical S-expression transport.
//!
//! The caller owns Assuan framing. This module performs strict component
//! selection, parses or renders canonical libgcrypt S-expressions, and keeps
//! all RSA private operations inside the audited AWS-LC adapter. rPGP is used
//! only for bounded OpenPGP packet parsing and public RFC 6637 metadata.

use ed25519_dalek::Signer as _;
use k256::ecdsa::signature::hazmat::PrehashSigner as _;
use p256::elliptic_curve::sec1::ToEncodedPoint as _;
use pgp::{
    composed::SignedPublicKey,
    crypto::{
        aes_kw, ecc_curve::ECCCurve, ecdh::build_ecdh_param, hash::HashAlgorithm,
        public_key::PublicKeyAlgorithm, sym::SymmetricKeyAlgorithm,
    },
    types::{KeyDetails, Password, PlainSecretParams, PublicParams},
};
use thiserror::Error;
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret as X25519Secret};
use zeroize::{Zeroize, Zeroizing};

use keyguard_crypto_sensitive::{
    DigestAlgorithm, DigestContext, RsaSignatureHash, decrypt_rsa_raw, sign_rsa_pkcs1_v1_5_digest,
};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        certificate::{
            MaterialErrorSeverity, MutationMaterialError, ParsedSecretCertificate,
            parse_mutation_candidates, parse_secret_certificate,
        },
        crypto::{
            secret::{SecretPacketRef, rsa_private_components},
            supports_decryption_key, supports_signing_key,
        },
        format::{hex_upper, normalize_fingerprint},
        packet::{
            PUBLIC_KEY_TAG, RawPacketError, RawPacketStream, SECRET_KEY_TAG, SECRET_SUBKEY_TAG,
        },
        policy::{
            OpenPgpPolicyBudget, OpenPgpPolicyError, all_components, reference_time,
            validate_certificate,
        },
    },
};

const MAX_AGENT_KEYS: usize = 64;
const MAX_AGENT_COMPONENTS: usize = 64;
// Shared with import, merge and mutation: a certificate small enough to
// import must stay usable for signing.
const MAX_AGENT_PACKETS: usize = crate::openpgp::packet::MAX_CERTIFICATE_PACKETS;
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
    #[error("OpenPGP agent internal failure")]
    Internal,
}

pub(crate) struct AgentSignInput {
    pub(crate) private_key: Vec<u8>,
    pub(crate) preferred_fingerprint: String,
    pub(crate) hash_algorithm: String,
    pub(crate) hash: Vec<u8>,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
}

impl Drop for AgentSignInput {
    fn drop(&mut self) {
        self.private_key.zeroize();
        self.hash.zeroize();
    }
}

pub(crate) struct AgentDecryptInput {
    pub(crate) private_key: Vec<u8>,
    pub(crate) preferred_fingerprint: String,
    pub(crate) ciphertext: Vec<u8>,
    pub(crate) unwrap_ecdh: bool,
}

impl Drop for AgentDecryptInput {
    fn drop(&mut self) {
        self.private_key.zeroize();
        self.ciphertext.zeroize();
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum AgentOperationFailure {
    KeyNotFound,
    UnsupportedAlgorithm,
}

pub(crate) enum AgentOperationOutcome {
    Success(Vec<u8>),
    Failure(AgentOperationFailure),
}

impl Drop for AgentOperationOutcome {
    fn drop(&mut self) {
        if let Self::Success(value) = self {
            value.zeroize();
        }
    }
}

/// Maps a keyring parse failure onto the agent operation contract: an
/// unsupported key version is a reportable failure outcome, everything else
/// is a request error.
fn key_parse_outcome(error: KeyParseError) -> Result<AgentOperationOutcome, OpenPgpAgentError> {
    match error {
        KeyParseError::UnsupportedVersion => Ok(AgentOperationOutcome::Failure(
            AgentOperationFailure::UnsupportedAlgorithm,
        )),
        KeyParseError::ResourceLimit => Err(OpenPgpAgentError::ResourceLimit),
        KeyParseError::Malformed => Err(OpenPgpAgentError::InvalidArgument),
        KeyParseError::Internal => Err(OpenPgpAgentError::Internal),
    }
}

/// Signs an already-computed digest and returns a canonical S-expression.
pub(crate) fn sign(
    mut request: AgentSignInput,
) -> Result<AgentOperationOutcome, OpenPgpAgentError> {
    if request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.hash.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(OpenPgpAgentError::ResourceLimit);
    }
    if request.private_key.is_empty() {
        return Err(OpenPgpAgentError::InvalidArgument);
    }

    let digest = Zeroizing::new(std::mem::take(&mut request.hash));
    let hash = match AgentHashAlgorithm::parse(&request.hash_algorithm, digest.as_slice()) {
        Ok(hash) => hash,
        Err(AgentHashParseError::UnsupportedAlgorithm) => {
            return Ok(AgentOperationOutcome::Failure(
                AgentOperationFailure::UnsupportedAlgorithm,
            ));
        }
        Err(AgentHashParseError::InvalidArgument) => {
            return Err(OpenPgpAgentError::InvalidArgument);
        }
    };
    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let revocation_candidates = parse_agent_candidates(&request.candidate_revocation_keys)?;
    let keys = match parse_secret_keys(private_key.as_slice()) {
        Ok(keys) => keys,
        Err(error) => return key_parse_outcome(error),
    };
    if let Some(packet) = select_preferred_packet(&keys, &request.preferred_fingerprint)
        && packet.algorithm() == PublicKeyAlgorithm::ECDSA
        && !supports_signing_key(packet.algorithm(), packet.public_key().public_params())
    {
        return Ok(AgentOperationOutcome::Failure(
            AgentOperationFailure::UnsupportedAlgorithm,
        ));
    }
    let Some(packet) = select_sign_packet(
        &keys,
        &request.preferred_fingerprint,
        &revocation_candidates,
    )?
    else {
        return Ok(AgentOperationOutcome::Failure(
            AgentOperationFailure::KeyNotFound,
        ));
    };
    let canonical = match packet.algorithm() {
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSASign => {
            let signature = rsa_sign(packet, hash.rsa_signature_hash(), digest.as_slice())?;
            canonical_signature("rsa", &[("s", minimal_unsigned(&signature))])?
        }
        PublicKeyAlgorithm::ECDSA => {
            if !supports_signing_key(packet.algorithm(), packet.public_key().public_params()) {
                return Ok(AgentOperationOutcome::Failure(
                    AgentOperationFailure::UnsupportedAlgorithm,
                ));
            }
            validate_ecdsa_hash(packet, hash)?;
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
            // RFC 9580 sections 5.2.3.3 and 5.2.3.4 require at least a
            // 256-bit digest for both legacy and native Ed25519 signatures.
            hash.validate_minimum_size(32)?;
            let signature = ed25519_sign(packet, digest.as_slice())?;
            let (r, s) = signature.split_at(32);
            canonical_signature("eddsa", &[("r", r), ("s", s)])?
        }
        _ => {
            return Ok(AgentOperationOutcome::Failure(
                AgentOperationFailure::UnsupportedAlgorithm,
            ));
        }
    };

    Ok(AgentOperationOutcome::Success(canonical.to_vec()))
}

/// Performs raw RSA or ECDH agent decryption and returns a canonical value.
pub(crate) fn decrypt(
    mut request: AgentDecryptInput,
) -> Result<AgentOperationOutcome, OpenPgpAgentError> {
    if request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.ciphertext.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(OpenPgpAgentError::ResourceLimit);
    }
    if request.private_key.is_empty() {
        return Err(OpenPgpAgentError::InvalidArgument);
    }

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let keys = match parse_secret_keys(private_key.as_slice()) {
        Ok(keys) => keys,
        Err(error) => return key_parse_outcome(error),
    };
    let Some(packet) = select_preferred_packet(&keys, &request.preferred_fingerprint) else {
        // Preserve the JVM implementation's fail-closed ordering: an
        // authoritative selector fails before attacker-controlled ciphertext
        // is parsed.
        return Ok(AgentOperationOutcome::Failure(
            AgentOperationFailure::KeyNotFound,
        ));
    };
    if packet.algorithm() == PublicKeyAlgorithm::ECDH
        && !supports_decryption_key(packet.algorithm(), packet.public_key().public_params())
    {
        return Ok(AgentOperationOutcome::Failure(
            AgentOperationFailure::UnsupportedAlgorithm,
        ));
    }

    let ciphertext = Zeroizing::new(std::mem::take(&mut request.ciphertext));
    let encrypted = parse_enc_val(ciphertext.as_slice())?;
    let value = match encrypted.algorithm {
        EncValAlgorithm::Rsa => {
            if !agent_rsa_decrypt_algorithm(packet.algorithm()) {
                return Ok(AgentOperationOutcome::Failure(
                    AgentOperationFailure::UnsupportedAlgorithm,
                ));
            }
            let ciphertext = encrypted.a.ok_or(OpenPgpAgentError::InvalidArgument)?;
            rsa_decrypt(packet, minimal_unsigned(ciphertext))?
        }
        EncValAlgorithm::Ecdh => {
            if !agent_ecdh_decrypt_algorithm(packet.algorithm())
                || !supports_decryption_key(packet.algorithm(), packet.public_key().public_params())
            {
                return Ok(AgentOperationOutcome::Failure(
                    AgentOperationFailure::UnsupportedAlgorithm,
                ));
            }
            let ephemeral = encrypted.e.ok_or(OpenPgpAgentError::InvalidArgument)?;
            let wrapped =
                parse_wrapped_key(encrypted.s.ok_or(OpenPgpAgentError::InvalidArgument)?)?;
            ecdh_decrypt(packet, ephemeral, wrapped, request.unwrap_ecdh)?
        }
    };
    let canonical = canonical_value(value.as_slice())?;

    Ok(AgentOperationOutcome::Success(canonical.to_vec()))
}

/// Unlocks a stored secret packet with the empty password used by agent key
/// material and folds both rPGP failure layers into the agent contract.
fn unlock_agent_packet<T>(
    packet: SecretPacketRef<'_>,
    operation: impl FnOnce(&PublicParams, &PlainSecretParams) -> pgp::errors::Result<T>,
) -> Result<T, OpenPgpAgentError> {
    packet
        .unlock(&Password::empty(), operation)
        .map_err(|_| OpenPgpAgentError::CryptoFailure)?
        .map_err(|_| OpenPgpAgentError::CryptoFailure)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum KeyParseError {
    Malformed,
    UnsupportedVersion,
    ResourceLimit,
    Internal,
}

impl From<RawPacketError> for KeyParseError {
    fn from(error: RawPacketError) -> Self {
        match error {
            RawPacketError::Malformed => Self::Malformed,
            RawPacketError::ResourceLimit => Self::ResourceLimit,
        }
    }
}

fn parse_secret_keys(input: &[u8]) -> Result<Vec<ParsedSecretCertificate>, KeyParseError> {
    let packets = RawPacketStream::parse(input, MAX_AGENT_PACKETS)?;
    let starts = packets
        .packets()
        .iter()
        .enumerate()
        .filter_map(|(index, packet)| {
            matches!(packet.tag(), SECRET_KEY_TAG | PUBLIC_KEY_TAG).then_some(index)
        })
        .collect::<Vec<_>>();
    if starts.first().copied() != Some(0) {
        return Err(KeyParseError::Malformed);
    }
    let mut keys = Vec::new();
    for (range_index, start) in starts.iter().copied().enumerate() {
        let end = starts
            .get(range_index + 1)
            .copied()
            .unwrap_or(packets.packets().len());
        let spans = &packets.packets()[start..end];
        if !spans
            .iter()
            .any(|packet| matches!(packet.tag(), SECRET_KEY_TAG | SECRET_SUBKEY_TAG))
        {
            continue;
        }
        if keys.len() == MAX_AGENT_KEYS {
            return Err(KeyParseError::ResourceLimit);
        }
        let certificate_len = spans.iter().try_fold(0_usize, |length, packet| {
            length.checked_add(packets.raw(packet).len())
        });
        let mut certificate = Zeroizing::new(Vec::new());
        certificate
            .try_reserve_exact(certificate_len.ok_or(KeyParseError::ResourceLimit)?)
            .map_err(|_| KeyParseError::ResourceLimit)?;
        for packet in spans {
            certificate.extend_from_slice(packets.raw(packet));
        }
        let secret =
            parse_secret_certificate(certificate.as_slice()).map_err(|error| match error {
                MutationMaterialError::UnsupportedKeyVersion => KeyParseError::UnsupportedVersion,
                MutationMaterialError::ResourceLimit => KeyParseError::ResourceLimit,
                MutationMaterialError::MalformedKey
                | MutationMaterialError::FingerprintMismatch
                | MutationMaterialError::UnsupportedTskLayout => KeyParseError::Malformed,
                MutationMaterialError::InternalFailure
                | MutationMaterialError::SignatureVerificationFailed => KeyParseError::Internal,
            })?;
        if secret.public().public_subkeys.len() > MAX_AGENT_COMPONENTS {
            return Err(KeyParseError::ResourceLimit);
        }
        keys.push(secret);
    }
    Ok(keys)
}

fn agent_rsa_decrypt_algorithm(algorithm: PublicKeyAlgorithm) -> bool {
    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    )
}

fn agent_ecdh_decrypt_algorithm(algorithm: PublicKeyAlgorithm) -> bool {
    algorithm == PublicKeyAlgorithm::ECDH
}

fn select_preferred_packet<'a>(
    keys: &'a [ParsedSecretCertificate],
    preferred_fingerprint: &str,
) -> Option<SecretPacketRef<'a>> {
    if preferred_fingerprint.is_blank() {
        return None;
    }
    let preferred = normalize_fingerprint(preferred_fingerprint);
    for secret in keys {
        if let Some(primary) = secret.primary().map(SecretPacketRef::Primary)
            && hex_upper(primary.fingerprint().as_bytes()) == preferred
        {
            return Some(primary);
        }
        for subkey in secret.subkeys() {
            let subkey = SecretPacketRef::Subkey(subkey);
            if hex_upper(subkey.fingerprint().as_bytes()) == preferred {
                return Some(subkey);
            }
        }
    }
    None
}

fn select_sign_packet<'a>(
    keys: &'a [ParsedSecretCertificate],
    preferred_fingerprint: &str,
    revocation_candidates: &[SignedPublicKey],
) -> Result<Option<SecretPacketRef<'a>>, OpenPgpAgentError> {
    let preferred =
        (!preferred_fingerprint.is_blank()).then(|| normalize_fingerprint(preferred_fingerprint));
    let public_keys = keys
        .iter()
        .map(|secret| secret.public().clone())
        .collect::<Vec<_>>();
    let mut candidates = all_components(&public_keys);
    candidates.extend(all_components(revocation_candidates));
    let mut budget = OpenPgpPolicyBudget::default();
    let mut eligible = Vec::new();
    let now = reference_time(None);

    for (secret, public) in keys.iter().zip(&public_keys) {
        let policy = validate_certificate(public, &candidates, now, &mut budget)
            .map_err(map_policy_error)?;
        // Assuan PKSIGN supplies only a key and digest, so it cannot prove that
        // a request is a renewal rather than an arbitrary data signature. Raw
        // agent signing therefore follows the same strict, time-qualified
        // SIGN_NEW_DATA policy exported by metadata. Renewal-only authority is
        // consumed exclusively by the dedicated certificate-mutation path.
        let primary_available = policy.primary_available();
        if primary_available && policy.primary_component().signing_usable() {
            eligible.extend(secret.primary().map(SecretPacketRef::Primary));
        }
        if primary_available {
            eligible.extend(secret.subkeys().iter().filter_map(|subkey| {
                policy
                    .subkeys_matching(subkey)
                    .any(|component| component.signing_usable())
                    .then_some(SecretPacketRef::Subkey(subkey))
            }));
        }
    }

    if let Some(preferred) = preferred {
        Ok(eligible
            .into_iter()
            .find(|packet| hex_upper(packet.fingerprint().as_bytes()) == preferred))
    } else if eligible.len() == 1 {
        Ok(eligible.pop())
    } else {
        Ok(None)
    }
}

fn parse_agent_candidates(
    documents: &[Vec<u8>],
) -> Result<Vec<SignedPublicKey>, OpenPgpAgentError> {
    parse_mutation_candidates(documents).map_err(OpenPgpAgentError::from)
}

impl From<MutationMaterialError> for OpenPgpAgentError {
    fn from(error: MutationMaterialError) -> Self {
        match error.severity() {
            MaterialErrorSeverity::ResourceLimit => Self::ResourceLimit,
            MaterialErrorSeverity::InvalidArgument => Self::InvalidArgument,
            MaterialErrorSeverity::Internal => Self::Internal,
        }
    }
}

fn map_policy_error(error: OpenPgpPolicyError) -> OpenPgpAgentError {
    match error {
        OpenPgpPolicyError::ResourceLimit | OpenPgpPolicyError::RequestResourceLimit => {
            OpenPgpAgentError::ResourceLimit
        }
        OpenPgpPolicyError::Internal => OpenPgpAgentError::Internal,
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AgentHashAlgorithm {
    Sha224,
    Sha256,
    Sha384,
    Sha512,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AgentHashParseError {
    UnsupportedAlgorithm,
    InvalidArgument,
}

impl AgentHashAlgorithm {
    fn parse(value: &str, digest: &[u8]) -> Result<Self, AgentHashParseError> {
        // Accept the RFC 9580 SHA-2 generation set in both forms emitted by
        // GnuPG's SETHASH: names and decimal libgcrypt IDs.  GnuPG also
        // accepts MD5 (1), SHA-1 (2), and RIPEMD-160 (3), but RFC 9580
        // section 9.5 prohibits generating signatures with those algorithms.
        let algorithm = match value.to_ascii_lowercase().as_str() {
            "11" | "sha224" => Self::Sha224,
            "8" | "sha256" => Self::Sha256,
            "9" | "sha384" => Self::Sha384,
            "10" | "sha512" => Self::Sha512,
            "1" | "md5" | "2" | "sha1" | "3" | "rmd160" | "ripemd160" => {
                return Err(AgentHashParseError::UnsupportedAlgorithm);
            }
            _ => return Err(AgentHashParseError::InvalidArgument),
        };
        if digest.len() != algorithm.digest_size() {
            return Err(AgentHashParseError::InvalidArgument);
        }
        Ok(algorithm)
    }

    const fn digest_size(self) -> usize {
        match self {
            Self::Sha224 => 28,
            Self::Sha256 => 32,
            Self::Sha384 => 48,
            Self::Sha512 => 64,
        }
    }

    fn validate_minimum_size(self, minimum: usize) -> Result<(), OpenPgpAgentError> {
        if self.digest_size() < minimum {
            Err(OpenPgpAgentError::InvalidArgument)
        } else {
            Ok(())
        }
    }

    const fn rsa_signature_hash(self) -> RsaSignatureHash {
        match self {
            Self::Sha224 => RsaSignatureHash::Sha224,
            Self::Sha256 => RsaSignatureHash::Sha256,
            Self::Sha384 => RsaSignatureHash::Sha384,
            Self::Sha512 => RsaSignatureHash::Sha512,
        }
    }
}

fn validate_ecdsa_hash(
    packet: SecretPacketRef<'_>,
    hash: AgentHashAlgorithm,
) -> Result<(), OpenPgpAgentError> {
    let PublicParams::ECDSA(params) = packet.public_key().public_params() else {
        return Err(OpenPgpAgentError::Internal);
    };
    let minimum = match params.curve() {
        ECCCurve::P256 | ECCCurve::Secp256k1 => 32,
        ECCCurve::P384 => 48,
        // RFC 9580 section 5.2.3.2 explicitly lowers P-521's requirement
        // from its 66-byte field size to a still-mandatory 512-bit digest.
        ECCCurve::P521 => 64,
        // The signing backend rejects every other ECDSA curve.  Preserve that
        // unsupported-key behavior instead of assigning an invented floor.
        _ => return Ok(()),
    };
    hash.validate_minimum_size(minimum)
}

fn rsa_sign(
    packet: SecretPacketRef<'_>,
    hash: RsaSignatureHash,
    digest: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    unlock_agent_packet(packet, |public, private| {
        let components = rsa_private_components(public, private)?;
        sign_rsa_pkcs1_v1_5_digest(&components, hash, digest)
            .map(Zeroizing::new)
            .map_err(|_| pgp_error("AWS-LC RSA signing failed"))
    })
}

fn rsa_decrypt(
    packet: SecretPacketRef<'_>,
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    unlock_agent_packet(packet, |public, private| {
        let components = rsa_private_components(public, private)?;
        decrypt_rsa_raw(&components, ciphertext)
            .map(|value| Zeroizing::new(minimal_unsigned(value.as_slice()).to_vec()))
            .map_err(|_| pgp_error("AWS-LC raw RSA decryption failed"))
    })
}

struct EcdsaSignature {
    r: Zeroizing<Vec<u8>>,
    s: Zeroizing<Vec<u8>>,
}

fn ecdsa_sign(
    packet: SecretPacketRef<'_>,
    digest: &[u8],
) -> Result<EcdsaSignature, OpenPgpAgentError> {
    unlock_agent_packet(packet, |_, private| {
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
    unlock_agent_packet(packet, |_, private| {
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
    unlock_agent_packet(packet, |public, private| {
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
    fn new(capacity: usize) -> Result<Self, OpenPgpAgentError> {
        if capacity > MAX_AGENT_OUTPUT_BYTES {
            return Err(OpenPgpAgentError::ResourceLimit);
        }
        let mut output = Zeroizing::new(Vec::new());
        output
            .try_reserve_exact(capacity)
            .map_err(|_| OpenPgpAgentError::ResourceLimit)?;
        Ok(Self { output })
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
        let length = self
            .output
            .len()
            .checked_add(additional)
            .filter(|length| *length <= MAX_AGENT_OUTPUT_BYTES)
            .ok_or(OpenPgpAgentError::ResourceLimit)?;
        if length > self.output.capacity() {
            return Err(OpenPgpAgentError::Internal);
        }
        Ok(())
    }
}

fn canonical_atom_len(value: &[u8]) -> Result<usize, OpenPgpAgentError> {
    value
        .len()
        .to_string()
        .len()
        .checked_add(1)
        .and_then(|length| length.checked_add(value.len()))
        .ok_or(OpenPgpAgentError::ResourceLimit)
}

fn canonical_signature(
    algorithm: &str,
    components: &[(&str, &[u8])],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    let mut output_len = 4_usize
        .checked_add(canonical_atom_len(b"sig-val")?)
        .ok_or(OpenPgpAgentError::ResourceLimit)?;
    output_len = output_len
        .checked_add(canonical_atom_len(algorithm.as_bytes())?)
        .ok_or(OpenPgpAgentError::ResourceLimit)?;
    for (name, value) in components {
        let name_len = canonical_atom_len(name.as_bytes())?;
        let value_len = canonical_atom_len(value)?;
        output_len = output_len
            .checked_add(2)
            .and_then(|length| length.checked_add(name_len))
            .and_then(|length| length.checked_add(value_len))
            .ok_or(OpenPgpAgentError::ResourceLimit)?;
    }
    let mut writer = CanonicalWriter::new(output_len)?;
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
    if writer.output.len() != output_len {
        return Err(OpenPgpAgentError::Internal);
    }
    Ok(writer.output)
}

fn canonical_value(value: &[u8]) -> Result<Zeroizing<Vec<u8>>, OpenPgpAgentError> {
    let value_len = canonical_atom_len(value)?;
    let output_len = 2_usize
        .checked_add(canonical_atom_len(b"value")?)
        .and_then(|length| length.checked_add(value_len))
        .ok_or(OpenPgpAgentError::ResourceLimit)?;
    let mut writer = CanonicalWriter::new(output_len)?;
    writer.byte(b'(')?;
    writer.atom(b"value")?;
    writer.atom(value)?;
    writer.byte(b')')?;
    if writer.output.len() != output_len {
        return Err(OpenPgpAgentError::Internal);
    }
    Ok(writer.output)
}

#[cfg(test)]
mod tests;
