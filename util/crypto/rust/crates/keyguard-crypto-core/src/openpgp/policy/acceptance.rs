//! OpenPGP algorithm and signature acceptance rules.
//!
//! Cryptographic-policy time is trusted input and remains independent from a
//! signature's untrusted creation time.

use pgp::{
    crypto::hash::HashAlgorithm,
    packet::{Signature, SignatureConfig, SignatureType, SubpacketData},
    types::{Fingerprint, KeyDetails},
};

use crate::openpgp::crypto::verification::{
    SignatureIssuerMetadata, signature_creation_time, signature_expiration_seconds,
};

use super::{
    model::SignatureTier,
    revocation::{RevocationScope, RevocationTarget, revocation_scope},
    selection::signature_expired_at,
};

// Keyguard's legacy-hash policy uses explicit UTC deadlines. Data signatures
// require collision resistance; authenticated certificate statements require
// second-preimage resistance. Keeping both deadlines in one profile prevents
// the two policy paths from drifting apart.
pub(super) const SHA1_COLLISION_REJECT_AT: u64 = 1_359_676_800;
pub(super) const RIPEMD160_COLLISION_REJECT_AT: u64 = 1_359_676_800;
pub(super) const SHA1_SECOND_PREIMAGE_REJECT_AT: u64 = 1_675_209_600;
const RIPEMD160_SECOND_PREIMAGE_REJECT_AT: u64 = 1_359_676_800;

/// Bounded compatibility window for authenticating old revocation evidence.
///
/// The grace period applies to the trusted policy clock only. An untrusted
/// signature timestamp never selects an older cryptographic policy, and the
/// deadline itself is rejected.
pub(super) const LEGACY_REVOCATION_GRACE_SECONDS: u64 = (7 * 365 + 2) * 24 * 60 * 60;

#[derive(Clone, Copy)]
struct LegacyHashDeadlines {
    collision_reject_at: u64,
    second_preimage_reject_at: u64,
}

#[derive(Clone, Copy)]
enum HashRequirement {
    CollisionResistance,
    SecondPreimageResistance,
}

/// Hash property required by a self-revocation's signed component.
///
/// Key material is fixed by the signer, and bounded textual User IDs do not
/// leave practical room to conceal collision blocks, so those components only
/// require second-preimage resistance. Opaque or attacker-shaped identities
/// require collision resistance even for a self-issued Certification
/// Revocation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum SelfRevocationHashSecurity {
    CollisionResistance,
    SecondPreimageResistance,
}

pub(super) fn user_id_self_revocation_hash_security(user_id: &[u8]) -> SelfRevocationHashSecurity {
    const SAFE_USER_ID_MAX_BYTES: usize = 96;

    if user_id.len() > SAFE_USER_ID_MAX_BYTES {
        return SelfRevocationHashSecurity::CollisionResistance;
    }
    let Ok(user_id) = std::str::from_utf8(user_id) else {
        return SelfRevocationHashSecurity::CollisionResistance;
    };
    if user_id.chars().any(char::is_control) {
        SelfRevocationHashSecurity::CollisionResistance
    } else {
        SelfRevocationHashSecurity::SecondPreimageResistance
    }
}

#[derive(Clone, Copy)]
enum HashPolicy {
    Current,
    Legacy(LegacyHashDeadlines),
    Unsupported,
}

/// The independent clocks needed to validate a historical certificate.
///
/// `certificate_time` selects statements that were live when the data was
/// allegedly signed. `cryptographic_policy_time` selects the security profile
/// and therefore must come from trusted custody metadata or the current time,
/// never from the data signature itself.
#[derive(Clone, Copy)]
pub(super) struct CertificateValidationTimes {
    pub(super) certificate_time: u64,
    pub(super) cryptographic_policy_time: u64,
}

impl CertificateValidationTimes {
    pub(super) fn single(reference_time: u64) -> Self {
        Self {
            certificate_time: reference_time,
            cryptographic_policy_time: reference_time,
        }
    }
}

pub(in crate::openpgp) fn signature_expired(signature: &Signature, reference_time: u64) -> bool {
    let Some(duration) = signature_expiration_seconds(signature) else {
        return false;
    };
    signature_expired_at(
        signature_creation_time(signature).map(u64::from),
        u64::from(duration),
        reference_time,
    )
}

pub(in crate::openpgp) fn authentication_signature_acceptable(
    signature: &Signature,
    reference_time: u64,
) -> bool {
    authentication_signature_acceptable_at(
        signature,
        CertificateValidationTimes::single(reference_time),
    )
}

pub(super) fn authentication_signature_acceptable_at(
    signature: &Signature,
    times: CertificateValidationTimes,
) -> bool {
    authentication_signature_policy_acceptable(signature, times)
        && !signature_expired(signature, times.certificate_time)
}

/// User Attributes may contain opaque binary data (most commonly images),
/// which can conceal chosen-prefix collision blocks.  Unlike a textual User ID
/// self-certification, authenticating one therefore requires
/// collision resistance as well as the ordinary binding-signature policy.
fn user_attribute_certification_signature_acceptable(
    signature: &Signature,
    times: CertificateValidationTimes,
) -> bool {
    authentication_signature_acceptable_at(signature, times)
        && signature.config().is_some_and(|config| {
            collision_resistant_hash_acceptable(config.hash_alg, times.cryptographic_policy_time, 0)
        })
}

/// Classifies a mathematically verified self-signature into the amalgamation's
/// tiers.
///
/// The caller must already have verified the signature against the correct
/// key; this only applies policy.
pub(super) fn signature_tier(
    signature: &Signature,
    times: CertificateValidationTimes,
) -> SignatureTier {
    if authentication_signature_acceptable_at(signature, times) {
        SignatureTier::Authenticated
    } else if policy_inactive_template_acceptable(signature, times.certificate_time) {
        SignatureTier::Template
    } else {
        SignatureTier::Rejected
    }
}

pub(super) fn user_attribute_certification_signature_tier(
    signature: &Signature,
    times: CertificateValidationTimes,
) -> SignatureTier {
    if user_attribute_certification_signature_acceptable(signature, times) {
        SignatureTier::Authenticated
    } else if policy_inactive_template_acceptable(signature, times.certificate_time) {
        SignatureTier::Template
    } else {
        SignatureTier::Rejected
    }
}

/// Returns whether a mathematically verified signature that the authentication
/// policy rejected may still be retained as a renewal template.
///
/// This requires everything `authentication_signature_policy_acceptable`
/// requires except the hash cutoff, so the only tolerated defect is an aged-out
/// hash algorithm.  In particular:
///
/// * a future-dated signature is never retained, in any tier — otherwise an
///   attacker could park a signature in the future and have renewal adopt it;
/// * a signature carrying a critical subpacket Keyguard does not understand is
///   never retained, because its semantics cannot be safely copied into a
///   freshly issued signature.
fn policy_inactive_template_acceptable(signature: &Signature, reference_time: u64) -> bool {
    signature_creation_time(signature).is_some_and(|created| u64::from(created) <= reference_time)
        && legacy_hash_algorithm(signature)
        && !has_unsupported_critical_subpacket(signature)
        && !signature_expired(signature, reference_time)
}

/// Returns whether the signature uses a hash algorithm covered by Keyguard's
/// deliberate weak-hash compatibility policy. MD5 is unconditionally
/// unsupported even when the crypto backend can verify it; unknown and
/// unimplemented hashes are excluded as well.
fn legacy_hash_algorithm(signature: &Signature) -> bool {
    matches!(
        signature.config().map(|config| config.hash_alg),
        Some(HashAlgorithm::Sha1 | HashAlgorithm::Ripemd160)
    )
}

/// Returns whether a verified Direct Key self-signature may declare a
/// designated revoker.
///
/// Unlike ordinary effective key policy, revocation authority is cumulative:
/// allowing the signature-expiration subpacket on a declaration to remove it
/// would let a later key compromise discard a previously authorized revoker.
/// The declaration must still be non-future and satisfy the normal hash and
/// critical-subpacket policy for a Direct Key signature. A declaration is not
/// itself revocation evidence and therefore receives no legacy-hash grace.
pub(super) fn revocation_authority_declaration_acceptable(
    signature: &Signature,
    reference_time: u64,
    cryptographic_policy_time: u64,
) -> bool {
    signature_creation_time(signature).is_some_and(|created| u64::from(created) <= reference_time)
        && signature_hash_acceptable(signature, cryptographic_policy_time)
        && !has_unsupported_critical_subpacket(signature)
}

fn authentication_signature_policy_acceptable(
    signature: &Signature,
    times: CertificateValidationTimes,
) -> bool {
    signature_creation_time(signature)
        .is_some_and(|created| u64::from(created) <= times.certificate_time)
        && signature_hash_acceptable(signature, times.cryptographic_policy_time)
        && !has_unsupported_critical_subpacket(signature)
}

/// Returns whether a mathematically verified signature over caller-controlled
/// data is acceptable under the verification-time policy.
///
/// The reference time is the time the data entered non-tamperable custody, or
/// the current time when that is unknown.  A signature's untrusted creation
/// time must not select an older cryptographic policy. The caller supplies the
/// latest acceptable creation time so current-time checks can tolerate bounded
/// clock skew while explicit historical checks remain exact.
pub(in crate::openpgp) fn data_signature_acceptable(
    signature: &Signature,
    reference_time: u64,
    latest_acceptable_creation_time: u64,
    authenticated_recipient: Option<&Fingerprint>,
) -> bool {
    let Some(config) = signature.config() else {
        return false;
    };
    matches!(config.typ, SignatureType::Binary | SignatureType::Text)
        // RFC 9580 §5.2.3.11 requires v4/v6 creation time in the hashed
        // area.  The version-aware helper also preserves the fixed signed
        // creation field carried by legacy v2/v3 signatures.
        && signature_creation_time(signature).is_some_and(|created| {
            u64::from(created) <= latest_acceptable_creation_time
        })
        && data_signature_hash_acceptable(config.hash_alg, reference_time)
        // RFC 9580 §§5.2.3.36 and 13.12 bind any signature carrying a
        // hashed Intended Recipient Fingerprint to a matching encrypted
        // recipient context. Critical and noncritical instances impose the
        // same binding. Unhashed instances are deliberately ignored: they are
        // unauthenticated routing noise, not signer policy.
        && intended_recipient_acceptable(config, authenticated_recipient)
        && !has_unsupported_critical_subpacket(signature)
}

fn intended_recipient_acceptable(
    config: &SignatureConfig,
    authenticated_recipient: Option<&Fingerprint>,
) -> bool {
    let mut intended_recipients = config.hashed_subpackets.iter().filter_map(|subpacket| {
        let SubpacketData::IntendedRecipientFingerprint(fingerprint) = &subpacket.data else {
            return None;
        };
        Some(fingerprint)
    });
    let Some(first) = intended_recipients.next() else {
        return true;
    };
    let Some(authenticated_recipient) = authenticated_recipient else {
        return false;
    };
    first == authenticated_recipient
        || intended_recipients.any(|fingerprint| fingerprint == authenticated_recipient)
}

/// Policy gate for self-revocation evidence.  Keep this distinct from binding
/// policy so the deliberately bounded weak-hash compatibility window does not
/// weaken certifications or treat arbitrary old revocations as authoritative.
pub(super) fn self_revocation_signature_acceptable(
    signature: &Signature,
    reference_time: u64,
    target: RevocationTarget,
    hash_security: SelfRevocationHashSecurity,
) -> bool {
    self_revocation_signature_acceptable_at(
        signature,
        reference_time,
        reference_time,
        target,
        hash_security,
    )
}

pub(super) fn self_revocation_signature_acceptable_at(
    signature: &Signature,
    reference_time: u64,
    cryptographic_policy_time: u64,
    target: RevocationTarget,
    hash_security: SelfRevocationHashSecurity,
) -> bool {
    let Some(created) = signature_creation_time(signature).map(u64::from) else {
        return false;
    };
    let retrospective_key_revocation = revocation_scope(signature)
        == RevocationScope::Retrospective
        && matches!(
            target,
            RevocationTarget::PrimaryKey | RevocationTarget::Subkey
        );
    // Retrospective key revocations are timeless evidence: compromise calls the
    // provenance of the key into question even outside the revocation
    // signature's creation window. Prospective key revocations are admitted
    // only after their creation time, then `revocation_is_effective` keeps them
    // final. Certification revocations retain liveness and ordering semantics.
    (created <= reference_time || retrospective_key_revocation)
        && signature.config().is_some_and(|config| {
            let requirement = match hash_security {
                SelfRevocationHashSecurity::CollisionResistance => {
                    HashRequirement::CollisionResistance
                }
                SelfRevocationHashSecurity::SecondPreimageResistance => {
                    HashRequirement::SecondPreimageResistance
                }
            };
            hash_meets_requirement(
                config.hash_alg,
                requirement,
                cryptographic_policy_time,
                LEGACY_REVOCATION_GRACE_SECONDS,
            )
        })
        && !has_unsupported_critical_revocation_subpacket(signature, target)
}

/// A designated revoker signs an attacker-influenced certificate, so its
/// revocation requires collision resistance in addition to the
/// second-preimage resistance sufficient for self revocations. The same
/// bounded grace applies to both requirements.
pub(super) fn third_party_revocation_signature_acceptable(
    signature: &Signature,
    reference_time: u64,
    cryptographic_policy_time: u64,
    target: RevocationTarget,
) -> bool {
    self_revocation_signature_acceptable_at(
        signature,
        reference_time,
        cryptographic_policy_time,
        target,
        SelfRevocationHashSecurity::SecondPreimageResistance,
    ) && signature.config().is_some_and(|config| {
        collision_resistant_hash_acceptable(
            config.hash_alg,
            cryptographic_policy_time,
            LEGACY_REVOCATION_GRACE_SECONDS,
        )
    })
}

pub(in crate::openpgp) fn signature_issuer_consistent<K: KeyDetails>(
    signature: &Signature,
    issuer: &K,
) -> bool {
    SignatureIssuerMetadata::from_signature(signature).signer_constraints_match(issuer)
}

fn signature_hash_acceptable(signature: &Signature, reference_time: u64) -> bool {
    signature_hash_acceptable_with_tolerance(signature, reference_time, 0)
}

fn signature_hash_acceptable_with_tolerance(
    signature: &Signature,
    reference_time: u64,
    tolerance_seconds: u64,
) -> bool {
    let Some(hash_algorithm) = signature.config().map(|config| config.hash_alg) else {
        return false;
    };
    hash_meets_requirement(
        hash_algorithm,
        HashRequirement::SecondPreimageResistance,
        reference_time,
        tolerance_seconds,
    )
}

pub(super) fn data_signature_hash_acceptable(
    hash_algorithm: HashAlgorithm,
    reference_time: u64,
) -> bool {
    collision_resistant_hash_acceptable(hash_algorithm, reference_time, 0)
}

fn collision_resistant_hash_acceptable(
    hash_algorithm: HashAlgorithm,
    reference_time: u64,
    tolerance_seconds: u64,
) -> bool {
    hash_meets_requirement(
        hash_algorithm,
        HashRequirement::CollisionResistance,
        reference_time,
        tolerance_seconds,
    )
}

fn hash_meets_requirement(
    hash_algorithm: HashAlgorithm,
    requirement: HashRequirement,
    reference_time: u64,
    grace_seconds: u64,
) -> bool {
    match hash_policy(hash_algorithm) {
        HashPolicy::Current => true,
        HashPolicy::Unsupported => false,
        HashPolicy::Legacy(deadlines) => {
            let reject_at = match requirement {
                HashRequirement::CollisionResistance => deadlines.collision_reject_at,
                HashRequirement::SecondPreimageResistance => deadlines.second_preimage_reject_at,
            };
            reject_at
                .checked_add(grace_seconds)
                .is_some_and(|deadline| reference_time < deadline)
        }
    }
}

const fn hash_policy(hash_algorithm: HashAlgorithm) -> HashPolicy {
    match hash_algorithm {
        HashAlgorithm::Md5 => HashPolicy::Unsupported,
        HashAlgorithm::Sha1 => HashPolicy::Legacy(LegacyHashDeadlines {
            collision_reject_at: SHA1_COLLISION_REJECT_AT,
            second_preimage_reject_at: SHA1_SECOND_PREIMAGE_REJECT_AT,
        }),
        HashAlgorithm::Ripemd160 => HashPolicy::Legacy(LegacyHashDeadlines {
            collision_reject_at: RIPEMD160_COLLISION_REJECT_AT,
            second_preimage_reject_at: RIPEMD160_SECOND_PREIMAGE_REJECT_AT,
        }),
        HashAlgorithm::Sha224
        | HashAlgorithm::Sha256
        | HashAlgorithm::Sha384
        | HashAlgorithm::Sha512
        | HashAlgorithm::Sha3_256
        | HashAlgorithm::Sha3_512 => HashPolicy::Current,
        HashAlgorithm::None | HashAlgorithm::Private10 | HashAlgorithm::Other(_) => {
            HashPolicy::Unsupported
        }
        _ => HashPolicy::Unsupported,
    }
}

/// Returns whether the digest belongs to the known-weak legacy set.
///
/// This intentionally covers only the known-weak algorithms, not unknown or
/// unsupported ones, so callers can warn "this signature uses a weak digest"
/// as a distinct statement. Maintain it alongside [`hash_policy`].
pub(in crate::openpgp) const fn is_legacy_weak_hash(hash_algorithm: HashAlgorithm) -> bool {
    matches!(
        hash_algorithm,
        HashAlgorithm::Md5 | HashAlgorithm::Sha1 | HashAlgorithm::Ripemd160
    )
}

fn has_unsupported_critical_subpacket(signature: &Signature) -> bool {
    has_unsupported_critical_subpacket_with_signature_target(signature, false)
}

fn has_unsupported_critical_revocation_subpacket(
    signature: &Signature,
    target: RevocationTarget,
) -> bool {
    has_unsupported_critical_subpacket_with_signature_target(
        signature,
        matches!(
            target,
            RevocationTarget::Certification { .. } | RevocationTarget::DirectKeySignature { .. }
        ),
    )
}

fn has_unsupported_critical_subpacket_with_signature_target(
    signature: &Signature,
    allow_signature_target: bool,
) -> bool {
    signature.config().is_none_or(|config| {
        config.hashed_subpackets.iter().any(|subpacket| {
            subpacket.is_critical
                && matches!(
                    subpacket.data,
                    SubpacketData::Notation(_)
                        | SubpacketData::PreferredEncryptionModes(_)
                        | SubpacketData::Experimental(_, _)
                        | SubpacketData::Other(_, _)
                )
                || subpacket.is_critical
                    && !allow_signature_target
                    && matches!(subpacket.data, SubpacketData::SignatureTarget(_, _, _))
        })
    })
}

pub(super) fn key_created_at_or_before(key: &impl KeyDetails, reference_time: u64) -> bool {
    u64::from(key.created_at().as_secs()) <= reference_time
}
