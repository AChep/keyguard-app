//! Structural OpenPGP signature-verification helpers.
//!
//! These functions validate signer identity, key/signature version pairing,
//! and the minimum cryptographic shape needed before a signature is checked.
//! They do not decide whether a verified statement is current or authorized.

use std::{borrow::Cow, cmp::Ordering};

use pgp::{
    crypto::{ecc_curve::ECCCurve, public_key::PublicKeyAlgorithm},
    packet::{
        Signature, SignatureConfig, SignatureType, SignatureVersion, SignatureVersionSpecific,
        SubpacketData,
    },
    types::{
        EcdsaPublicParams, Fingerprint, KeyDetails, KeyId, KeyVersion, PublicParams, SignatureBytes,
    },
};

const MIN_RSA_SIGNATURE_VERIFICATION_BITS: u16 = 2_048;

/// Maximum issuer-routing hints retained from one signature.
pub(crate) const MAX_SIGNATURE_ISSUER_HINTS: usize = 32;

/// Returns whether a key has a structurally acceptable verification shape.
pub(crate) fn key_signature_verification_acceptable(key: &impl KeyDetails) -> bool {
    if !signature_algorithm_acceptable(key.algorithm()) {
        return false;
    }
    let params = key.public_params();
    let PublicParams::RSA(_) = params else {
        return true;
    };
    super::public::leading_mpi_bits(params)
        .is_some_and(|bits| bits >= MIN_RSA_SIGNATURE_VERIFICATION_BITS)
}

/// Returns whether an algorithm may create or authenticate signatures.
///
/// This is deliberately operation-specific: legacy key packets remain
/// parseable, but RFC 9580 sections 12.5 and 12.8 prohibit using DSA or the
/// former combined ElGamal algorithm for signatures.
pub(crate) fn signature_algorithm_acceptable(algorithm: PublicKeyAlgorithm) -> bool {
    algorithm.can_sign()
        && !matches!(
            algorithm,
            PublicKeyAlgorithm::DSA | PublicKeyAlgorithm::Elgamal
        )
}

pub(crate) fn signature_matches_signer<V: KeyDetails>(signature: &Signature, signer: &V) -> bool {
    if !signature_verification_compatible(signature, signer) {
        return false;
    }
    let issuer = SignatureIssuerMetadata::from_signature(signature);
    issuer.signer_constraints_match(signer)
}

/// Removes mutable issuer-routing metadata before an exact-key verification.
///
/// rPGP's high-level verification helpers consult both signature subpacket
/// areas and can reject an otherwise valid signature when an intermediary
/// changes the unhashed issuer hints. The returned signature has identical
/// hashed data and cryptographic material, so the public-key operation remains
/// authoritative. Malformed issuer metadata is rejected before normalization.
/// Borrow the original when no normalization is necessary.
pub(crate) fn signature_ignoring_unhashed_issuer_hints(
    signature: &Signature,
) -> Option<Cow<'_, Signature>> {
    if SignatureIssuerMetadata::from_signature(signature).is_invalid() {
        return None;
    }
    let config = signature.config()?;
    if !config.unhashed_subpackets().any(|subpacket| {
        matches!(
            &subpacket.data,
            SubpacketData::IssuerFingerprint(_) | SubpacketData::IssuerKeyId(_)
        )
    }) {
        return Some(Cow::Borrowed(signature));
    }

    let mut config = config.clone();
    config.unhashed_subpackets.retain(|subpacket| {
        !matches!(
            &subpacket.data,
            SubpacketData::IssuerFingerprint(_) | SubpacketData::IssuerKeyId(_)
        )
    });
    Signature::from_config(
        config,
        signature.signed_hash_value()?,
        signature.signature()?.clone(),
    )
    .ok()
    .map(Cow::Owned)
}

/// Returns whether a signature's version, algorithm, and digest are compatible
/// with the candidate signer under RFC 9580 sections 5.2.3.2 through 5.2.3.5.
///
/// rPGP performs the final public-key operation, but this shared boundary is
/// still required: rPGP 0.20 accepts forbidden V3 elliptic-curve signatures,
/// and its ECDSA digest-floor comparison treats a byte count as a bit count.
pub(crate) fn signature_verification_compatible<V: KeyDetails>(
    signature: &Signature,
    signer: &V,
) -> bool {
    let Some(config) = signature.config() else {
        return false;
    };
    let algorithm = signer.algorithm();
    if !signature_version_matches_signer(signature.version(), signer.version())
        || config.pub_alg != algorithm
    {
        return false;
    }

    if signature.version() == SignatureVersion::V3
        && matches!(
            algorithm,
            PublicKeyAlgorithm::ECDSA
                | PublicKeyAlgorithm::EdDSALegacy
                | PublicKeyAlgorithm::Ed25519
                | PublicKeyAlgorithm::Ed448
        )
    {
        return false;
    }
    // RFC 9580 section 5.2.3.3.1 limits Ed25519Legacy to V4 signatures.
    if algorithm == PublicKeyAlgorithm::EdDSALegacy && signature.version() != SignatureVersion::V4 {
        return false;
    }

    let minimum_digest_bytes = match (algorithm, signer.public_params()) {
        (PublicKeyAlgorithm::ECDSA, PublicParams::ECDSA(params)) => match params {
            EcdsaPublicParams::P256 { .. } | EcdsaPublicParams::Secp256k1 { .. } => Some(32),
            EcdsaPublicParams::P384 { .. } => Some(48),
            // RFC 9580 makes P-521 an explicit 512-bit exception rather
            // than requiring its 66-octet field size.
            EcdsaPublicParams::P521 { .. } => Some(64),
            EcdsaPublicParams::Unsupported { curve, .. } => curve_fsize_bytes(curve),
        },
        (PublicKeyAlgorithm::EdDSALegacy, PublicParams::EdDSALegacy(params)) => {
            curve_fsize_bytes(&params.curve())
        }
        (PublicKeyAlgorithm::Ed25519, PublicParams::Ed25519(_)) => Some(32),
        (PublicKeyAlgorithm::Ed448, PublicParams::Ed448(_)) => Some(64),
        (
            PublicKeyAlgorithm::ECDSA
            | PublicKeyAlgorithm::EdDSALegacy
            | PublicKeyAlgorithm::Ed25519
            | PublicKeyAlgorithm::Ed448,
            _,
        ) => return false,
        _ => None,
    };
    minimum_digest_bytes.is_none_or(|minimum| {
        config
            .hash_alg
            .digest_size()
            .is_some_and(|actual| actual >= minimum)
    })
}

fn curve_fsize_bytes(curve: &ECCCurve) -> Option<usize> {
    match curve {
        ECCCurve::P256
        | ECCCurve::BrainpoolP256r1
        | ECCCurve::Ed25519Legacy
        | ECCCurve::Curve25519Legacy
        | ECCCurve::Secp256k1 => Some(32),
        ECCCurve::P384 | ECCCurve::BrainpoolP384r1 => Some(48),
        ECCCurve::P521 => Some(66),
        ECCCurve::BrainpoolP512r1 => Some(64),
        ECCCurve::Unknown(_) => None,
    }
}

/// Validated issuer-routing hints carried by one signature packet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum SignatureIssuerMetadata<'a> {
    Missing,
    Invalid,
    Valid {
        signature_version: SignatureVersion,
        signer_constraints: Vec<SignatureIssuerHint<'a>>,
        routing_hints: Vec<SignatureIssuerHint<'a>>,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum SignatureIssuerHint<'a> {
    Fingerprint(&'a Fingerprint),
    KeyId(&'a KeyId),
}

impl<'a> SignatureIssuerMetadata<'a> {
    pub(crate) fn from_signature(signature: &'a Signature) -> Self {
        if !matches!(
            signature.version(),
            SignatureVersion::V3 | SignatureVersion::V4 | SignatureVersion::V6
        ) {
            return Self::Invalid;
        }
        let Some(config) = signature.config() else {
            return Self::Invalid;
        };
        let signature_version = signature.version();

        if let SignatureVersionSpecific::V3 { issuer_key_id, .. } = &config.version_specific {
            return Self::Valid {
                signature_version,
                signer_constraints: vec![SignatureIssuerHint::KeyId(issuer_key_id)],
                routing_hints: Vec::new(),
            };
        }

        let Some((signer_constraints, routing_hints)) = issuer_hints(config, signature_version)
        else {
            return Self::Invalid;
        };
        if signer_constraints.is_empty() && routing_hints.is_empty() {
            return Self::Missing;
        }
        Self::Valid {
            signature_version,
            signer_constraints,
            routing_hints,
        }
    }

    pub(crate) fn is_missing(&self) -> bool {
        matches!(self, Self::Missing)
    }

    pub(crate) fn is_invalid(&self) -> bool {
        matches!(self, Self::Invalid)
    }

    pub(crate) fn matches(&self, signer: &impl KeyDetails) -> bool {
        let Self::Valid {
            signature_version,
            signer_constraints,
            routing_hints,
        } = self
        else {
            return false;
        };
        if !signature_version_matches_signer(*signature_version, signer.version()) {
            return false;
        }
        issuer_hints_match(signer_constraints.iter().chain(routing_hints), signer)
    }

    /// Returns whether structurally valid issuer metadata permits `signer`.
    ///
    /// Authenticated constraints are authoritative when present. V4 and V6
    /// unhashed issuer subpackets may route to a candidate, but cannot override
    /// a foreign authenticated issuer or veto an exact candidate when no
    /// authenticated issuer constraint exists.
    pub(crate) fn signer_constraints_match(&self, signer: &impl KeyDetails) -> bool {
        let Self::Valid {
            signature_version,
            signer_constraints,
            ..
        } = self
        else {
            return self.is_missing();
        };
        if !signature_version_matches_signer(*signature_version, signer.version()) {
            return false;
        }
        signer_constraints.is_empty() || issuer_hints_match(signer_constraints.iter(), signer)
    }

    pub(crate) fn fingerprint(&self) -> Option<&Fingerprint> {
        let Self::Valid {
            signer_constraints,
            routing_hints,
            ..
        } = self
        else {
            return None;
        };
        signer_constraints
            .iter()
            .chain(routing_hints)
            .find_map(|hint| match hint {
                SignatureIssuerHint::Fingerprint(fingerprint) => Some(*fingerprint),
                SignatureIssuerHint::KeyId(_) => None,
            })
    }

    pub(crate) fn key_id(&self) -> Option<&KeyId> {
        let Self::Valid {
            signer_constraints,
            routing_hints,
            ..
        } = self
        else {
            return None;
        };
        signer_constraints
            .iter()
            .chain(routing_hints)
            .find_map(|hint| match hint {
                SignatureIssuerHint::Fingerprint(_) => None,
                SignatureIssuerHint::KeyId(key_id) => Some(*key_id),
            })
    }
}

fn issuer_hints_match<'hint, 'signature: 'hint>(
    mut hints: impl Iterator<Item = &'hint SignatureIssuerHint<'signature>>,
    signer: &impl KeyDetails,
) -> bool {
    let fingerprint = signer.fingerprint();
    let key_id = signer.legacy_key_id();
    hints.any(|hint| match hint {
        SignatureIssuerHint::Fingerprint(candidate) => **candidate == fingerprint,
        SignatureIssuerHint::KeyId(candidate) => **candidate == key_id,
    })
}

fn issuer_hints<'a>(
    config: &'a SignatureConfig,
    signature_version: SignatureVersion,
) -> Option<(Vec<SignatureIssuerHint<'a>>, Vec<SignatureIssuerHint<'a>>)> {
    let mut signer_constraints = Vec::with_capacity(1);
    let mut expected_v4_key_id = None;
    let mut has_hashed_key_id = false;
    let mut hashed_key_id_matches = false;
    for subpacket in config.hashed_subpackets() {
        let hint = match &subpacket.data {
            SubpacketData::IssuerFingerprint(fingerprint) => {
                if !matches!(
                    (signature_version, fingerprint.version()),
                    (SignatureVersion::V4, Some(KeyVersion::V4))
                        | (SignatureVersion::V6, Some(KeyVersion::V6))
                ) {
                    return None;
                }
                if let Fingerprint::V4(bytes) = fingerprint {
                    expected_v4_key_id = Some(&bytes[12..]);
                }
                SignatureIssuerHint::Fingerprint(fingerprint)
            }
            SubpacketData::IssuerKeyId(key_id) => {
                if signature_version == SignatureVersion::V6 {
                    return None;
                }
                has_hashed_key_id = true;
                SignatureIssuerHint::KeyId(key_id)
            }
            _ => continue,
        };
        if signer_constraints.len() == MAX_SIGNATURE_ISSUER_HINTS {
            return None;
        }
        signer_constraints.push(hint);
    }

    if let Some(expected) = expected_v4_key_id {
        for hint in &signer_constraints {
            if let SignatureIssuerHint::KeyId(key_id) = hint
                && key_id.as_ref() == expected
            {
                hashed_key_id_matches = true;
                break;
            }
        }
    }
    if expected_v4_key_id.is_some() && has_hashed_key_id && !hashed_key_id_matches {
        return None;
    }

    let mut routing_hints = Vec::with_capacity(1);
    for subpacket in config.unhashed_subpackets() {
        let hint = match &subpacket.data {
            SubpacketData::IssuerFingerprint(fingerprint) => {
                if !matches!(
                    (signature_version, fingerprint.version()),
                    (SignatureVersion::V4, Some(KeyVersion::V4))
                        | (SignatureVersion::V6, Some(KeyVersion::V6))
                ) {
                    return None;
                }
                SignatureIssuerHint::Fingerprint(fingerprint)
            }
            SubpacketData::IssuerKeyId(key_id) => {
                if signature_version == SignatureVersion::V6 {
                    return None;
                }
                SignatureIssuerHint::KeyId(key_id)
            }
            _ => continue,
        };
        if signer_constraints.len() + routing_hints.len() < MAX_SIGNATURE_ISSUER_HINTS {
            routing_hints.push(hint);
        }
    }
    Some((signer_constraints, routing_hints))
}

pub(crate) fn signature_version_matches_signer(
    signature_version: SignatureVersion,
    signer_version: KeyVersion,
) -> bool {
    matches!(
        (signature_version, signer_version),
        (SignatureVersion::V3, KeyVersion::V3 | KeyVersion::V4)
            | (SignatureVersion::V4, KeyVersion::V4)
            | (SignatureVersion::V6, KeyVersion::V6)
    )
}

pub(crate) fn signature_creation_time(signature: &Signature) -> Option<u32> {
    let config = signature.config()?;
    match config.version() {
        SignatureVersion::V4 | SignatureVersion::V6 => config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match subpacket.data {
                SubpacketData::SignatureCreationTime(time) => Some(time.as_secs()),
                _ => None,
            }),
        SignatureVersion::V2 | SignatureVersion::V3 => config.created().map(|time| time.as_secs()),
        _ => None,
    }
}

/// Returns whether the last hashed Exportable Certification subpacket marks
/// the signature as local-only.
///
/// RFC 9580 section 5.2.3.9 recommends the last hashed occurrence when
/// duplicate subpackets conflict. An absent subpacket defaults to exportable,
/// and unhashed occurrences are ignored because they are not authenticated.
pub(crate) fn signature_config_is_non_exportable(config: &SignatureConfig) -> bool {
    config
        .hashed_subpackets
        .iter()
        .rev()
        .find_map(|subpacket| match subpacket.data {
            SubpacketData::ExportableCertification(exportable) => Some(!exportable),
            _ => None,
        })
        .unwrap_or(false)
}

/// Compares only the cryptographic output carried by two signatures.
///
/// This matches Sequoia's deterministic equal-time self-signature tie-break:
/// MPI values are compared lexicographically as byte strings. RFC 9580-native
/// signatures use their native bytes in the same way. Unlike a full packet
/// comparison, this cannot be influenced by changing unhashed subpackets.
pub(crate) fn cryptographic_signature_material_cmp(
    first: &Signature,
    second: &Signature,
) -> Ordering {
    match (first.signature(), second.signature()) {
        (Some(SignatureBytes::Mpis(first)), Some(SignatureBytes::Mpis(second))) => first
            .iter()
            .map(|mpi| mpi.as_ref())
            .cmp(second.iter().map(|mpi| mpi.as_ref())),
        (Some(SignatureBytes::Native(first)), Some(SignatureBytes::Native(second))) => {
            let first: &[u8] = first.as_ref();
            let second: &[u8] = second.as_ref();
            first.cmp(second)
        }
        (Some(SignatureBytes::Mpis(_)), Some(SignatureBytes::Native(_))) => Ordering::Less,
        (Some(SignatureBytes::Native(_)), Some(SignatureBytes::Mpis(_))) => Ordering::Greater,
        (Some(_), None) => Ordering::Greater,
        (None, Some(_)) => Ordering::Less,
        (None, None) => Ordering::Equal,
    }
}

pub(crate) fn signature_expiration_seconds(signature: &Signature) -> Option<u32> {
    signature
        .config()
        .and_then(|config| {
            config
                .hashed_subpackets
                .iter()
                .rev()
                .find_map(|subpacket| match subpacket.data {
                    SubpacketData::SignatureExpirationTime(duration) => Some(duration.as_secs()),
                    _ => None,
                })
        })
        .and_then(|duration| (duration != 0).then_some(duration))
}

pub(crate) fn is_certification(signature_type: Option<SignatureType>) -> bool {
    matches!(
        signature_type,
        Some(
            SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
        )
    )
}
