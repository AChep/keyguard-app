//! Authentication, revocation, and component evaluation.
//!
//! This module turns retained certificate evidence into one policy-qualified
//! [`ValidatedCertificate`](super::model::ValidatedCertificate) view.

use pgp::{
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    packet::{
        PublicKey, PublicSubkey, Signature, SignatureType, SignatureVersionSpecific, SubpacketData,
    },
    ser::Serialize,
    types::{KeyDetails, KeyVersion, Tag, VerifyingKey},
};

use crate::openpgp::{
    certificate::PublicComponent,
    crypto::verification::{
        is_certification, key_signature_verification_acceptable, signature_creation_time,
        signature_ignoring_unhashed_issuer_hints, signature_matches_signer,
        signature_version_matches_signer,
    },
};

use super::{acceptance::*, budget::*, model::*, revocation::*, selection::*};

#[derive(Default)]
struct SubkeyAuthentication<'a> {
    verified_bindings: Vec<&'a Signature>,
    verified_any_bindings: Vec<&'a Signature>,
    verified_revocations: Vec<&'a Signature>,
    verified_templates: Vec<PolicyInactiveTemplate<'a>>,
}

#[derive(Default)]
struct CertificateAuthentication<'a> {
    subkeys: Vec<SubkeyAuthentication<'a>>,
    verified_direct: Vec<&'a Signature>,
    verified_direct_requirements: Vec<&'a Signature>,
    verified_direct_templates: Vec<PolicyInactiveTemplate<'a>>,
    direct_self_revocations: Vec<&'a Signature>,
    primary_self_revocations: Vec<&'a Signature>,
    user_ids: Vec<IdentityAuthentication<'a>>,
    user_attributes: Vec<IdentityAuthentication<'a>>,
}

#[derive(Default)]
struct IdentityAuthentication<'a> {
    verified_certifications: Vec<&'a Signature>,
    verified_any_certifications: Vec<&'a Signature>,
    verified_revocations: Vec<&'a Signature>,
    verified_templates: Vec<PolicyInactiveTemplate<'a>>,
}

struct AuthenticatedDirectThirdPartyRevocation<'a> {
    signature: &'a Signature,
    signer: DesignatedRevokerId,
    selector: CertificationRevocationSelector<'a>,
}

struct AuthenticatedDirectSelfRevocation<'a> {
    signature: &'a Signature,
    selector: CertificationRevocationSelector<'a>,
}

impl PublicComponent {
    pub(in crate::openpgp) fn verify_digest(
        &self,
        signature: &Signature,
        digest: &[u8],
        budget: &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError> {
        if !key_signature_verification_acceptable(self) {
            return Ok(false);
        }
        let Some(config) = signature.config() else {
            return Ok(false);
        };
        if !signature_version_matches_signer(signature.version(), self.version())
            || config.pub_alg != self.algorithm()
            || !signature_matches_signer(signature, self)
            || (config.pub_alg.is_pqc()
                && config
                    .hash_alg
                    .digest_size()
                    .is_none_or(|size| size * 8 < 256))
        {
            return Ok(false);
        }
        let Some(expected_prefix) = signature.signed_hash_value() else {
            return Ok(false);
        };
        if digest.get(..expected_prefix.len()) != Some(expected_prefix.as_slice()) {
            return Ok(false);
        }
        let Some(signature_bytes) = signature.signature() else {
            return Ok(false);
        };
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => key.verify(config.hash_alg, digest, signature_bytes).is_ok(),
            Self::Subkey(key) => key.verify(config.hash_alg, digest, signature_bytes).is_ok(),
        })
    }

    fn verifies_key_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        budget: &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError> {
        if !key_signature_verification_acceptable(self) {
            return Ok(false);
        }
        if !signature_matches_signer(signature, self) {
            return Ok(false);
        }
        budget.charge_public_key_verification()?;
        Ok(
            signature_ignoring_unhashed_issuer_hints(signature).is_some_and(
                |signature| match self {
                    Self::Primary(key) => signature.verify_key_third_party(primary, key).is_ok(),
                    Self::Subkey(key) => signature.verify_key_third_party(primary, key).is_ok(),
                },
            ),
        )
    }

    fn verifies_certification_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        tag: Tag,
        identity: &impl Serialize,
        budget: &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError> {
        if !key_signature_verification_acceptable(self) {
            return Ok(false);
        }
        if !signature_matches_signer(signature, self) {
            return Ok(false);
        }
        budget.charge_public_key_verification()?;
        Ok(
            signature_ignoring_unhashed_issuer_hints(signature).is_some_and(
                |signature| match self {
                    Self::Primary(key) => signature
                        .verify_third_party_certification(primary, key, tag, identity)
                        .is_ok(),
                    Self::Subkey(key) => signature
                        .verify_third_party_certification(primary, key, tag, identity)
                        .is_ok(),
                },
            ),
        )
    }

    fn verifies_subkey_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        subkey: &PublicSubkey,
        budget: &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError> {
        if !key_signature_verification_acceptable(self) {
            return Ok(false);
        }
        if !signature_matches_signer(signature, self) {
            return Ok(false);
        }
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => {
                verify_third_party_subkey_revocation(signature, primary, subkey, key)
            }
            Self::Subkey(key) => {
                verify_third_party_subkey_revocation(signature, primary, subkey, key)
            }
        })
    }
}

fn verify_third_party_subkey_revocation<V>(
    signature: &Signature,
    primary: &PublicKey,
    subkey: &PublicSubkey,
    signer: &V,
) -> bool
where
    V: VerifyingKey + Serialize,
{
    if signature.typ() != Some(SignatureType::SubkeyRevocation)
        || !signature_matches_signer(signature, signer)
        || !signature_version_matches_signer(signature.version(), signer.version())
    {
        return false;
    }
    let Some(config) = signature.config() else {
        return false;
    };
    let Ok(mut hasher) = config.hash_alg.new_hasher() else {
        return false;
    };
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    let Some(primary_hash_data) = key_hash_data(primary) else {
        return false;
    };
    let Some(subkey_hash_data) = key_hash_data(subkey) else {
        return false;
    };
    hasher.update(&primary_hash_data);
    hasher.update(&subkey_hash_data);
    let Ok(signature_data_len) = config.hash_signature_data(&mut hasher) else {
        return false;
    };
    let Ok(trailer) = config.trailer(signature_data_len) else {
        return false;
    };
    hasher.update(&trailer);
    let digest = hasher.finalize();
    let Some(expected_prefix) = signature.signed_hash_value() else {
        return false;
    };
    if digest.get(..expected_prefix.len()) != Some(expected_prefix.as_slice()) {
        return false;
    }
    signature
        .signature()
        .is_some_and(|value| signer.verify(config.hash_alg, &digest, value).is_ok())
}

fn key_hash_data<K>(key: &K) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let key_len = key.write_len();
    let mut result = Vec::with_capacity(key_len.saturating_add(5));
    match key.version() {
        KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V4 => {
            result.push(0x99);
            result.extend_from_slice(&u16::try_from(key_len).ok()?.to_be_bytes());
        }
        KeyVersion::V6 => {
            result.push(0x9b);
            result.extend_from_slice(&u32::try_from(key_len).ok()?.to_be_bytes());
        }
        _ => return None,
    }
    key.to_writer(&mut result).ok()?;
    Some(result)
}

/// Computes the digest signed by a primary-key-only signature.
///
/// `pgp` deliberately limits its direct-key helpers to signature types 0x1F
/// and 0x20. RFC 9580 also defines type 0x30 over this same data when it
/// revokes a Direct Key signature, so that narrow case is handled here.
pub(super) fn direct_key_signature_digest<K>(signature: &Signature, key: &K) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let config = signature.config()?;
    let mut hasher = config.hash_alg.new_hasher().ok()?;
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    hasher.update(&key_hash_data(key)?);
    let signature_data_len = config.hash_signature_data(&mut hasher).ok()?;
    hasher.update(&config.trailer(signature_data_len).ok()?);
    Some(hasher.finalize().to_vec())
}

fn verify_direct_certification_revocation<V, K>(
    signature: &Signature,
    signee: &K,
    signer: &V,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<bool, OpenPgpPolicyError>
where
    V: KeyDetails + VerifyingKey,
    K: KeyDetails + Serialize,
{
    if !key_signature_verification_acceptable(signer) {
        return Ok(false);
    }
    let Some(config) = signature.config() else {
        return Ok(false);
    };
    if signature.typ() != Some(SignatureType::CertRevocation)
        || !signature_version_matches_signer(signature.version(), signer.version())
        || config.pub_alg != signer.algorithm()
        || !signature_matches_signer(signature, signer)
        || (config.pub_alg.is_pqc()
            && config
                .hash_alg
                .digest_size()
                .is_none_or(|size| size * 8 < 256))
    {
        return Ok(false);
    }
    let Some(digest) = direct_key_signature_digest(signature, signee) else {
        return Ok(false);
    };
    let Some(expected_prefix) = signature.signed_hash_value() else {
        return Ok(false);
    };
    if digest.get(..expected_prefix.len()) != Some(expected_prefix.as_slice()) {
        return Ok(false);
    }
    let Some(signature_bytes) = signature.signature() else {
        return Ok(false);
    };
    budget.charge_public_key_verification()?;
    Ok(signer
        .verify(config.hash_alg, &digest, signature_bytes)
        .is_ok())
}

/// A Certification Revocation's authenticated Signature Target selector.
///
/// Parsing once makes malformed and unsupported selector states
/// unrepresentable in the targeted case. Unhashed selectors are mutable hints
/// and never enter this representation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CertificationRevocationSelector<'a> {
    Untargeted,
    Targeted {
        public_key_algorithm: PublicKeyAlgorithm,
        hash_algorithm: HashAlgorithm,
        digest: &'a [u8],
    },
    Inapplicable,
}

impl CertificationRevocationSelector<'_> {
    fn requests_digest_for(
        self,
        public_key_algorithm: PublicKeyAlgorithm,
        hash_algorithm: HashAlgorithm,
    ) -> bool {
        matches!(
            self,
            Self::Targeted {
                public_key_algorithm: candidate_public_key,
                hash_algorithm: candidate_hash,
                ..
            } if candidate_public_key == public_key_algorithm && candidate_hash == hash_algorithm
        )
    }

    fn targets(self, target: Option<&SignatureTargetDigest>) -> bool {
        match self {
            Self::Untargeted => true,
            Self::Targeted {
                public_key_algorithm,
                hash_algorithm,
                digest,
            } => target.is_some_and(|target| {
                target.public_key_algorithm == public_key_algorithm
                    && target.hash_algorithm == hash_algorithm
                    && target.digest.as_slice() == digest
            }),
            Self::Inapplicable => false,
        }
    }
}

struct SignatureTargetDigest {
    public_key_algorithm: PublicKeyAlgorithm,
    hash_algorithm: HashAlgorithm,
    digest: Vec<u8>,
}

/// Parses the exactly-one hashed Signature Target rule once per revocation.
///
/// Duplicate targets, unsupported algorithm identifiers, and digests whose
/// length does not match the selected hash algorithm fail closed.
fn certification_revocation_selector(
    revocation: &Signature,
) -> CertificationRevocationSelector<'_> {
    let Some(config) = revocation.config() else {
        return CertificationRevocationSelector::Inapplicable;
    };
    let mut targets = config.hashed_subpackets().filter_map(|subpacket| {
        if let SubpacketData::SignatureTarget(public_key, hash, digest) = &subpacket.data {
            Some((*public_key, *hash, digest.as_ref()))
        } else {
            None
        }
    });
    let Some((public_key_algorithm, hash_algorithm, digest)) = targets.next() else {
        return CertificationRevocationSelector::Untargeted;
    };
    if targets.next().is_some()
        || !public_key_algorithm.can_sign()
        || hash_algorithm.digest_size() != Some(digest.len())
    {
        return CertificationRevocationSelector::Inapplicable;
    }
    CertificationRevocationSelector::Targeted {
        public_key_algorithm,
        hash_algorithm,
        digest,
    }
}

/// Computes the digest signed by a User ID or User Attribute certification.
///
/// RFC 9580 §5.2.3.33 identifies a target by the algorithms and hash of the
/// target signature itself. This is the certification's original signed-data
/// digest, not the signature-over-signature input used by type 0x50.
pub(super) fn certification_signature_digest<K>(
    signature: &Signature,
    primary: &K,
    tag: Tag,
    identity: &impl Serialize,
) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let config = signature.config()?;
    let mut hasher = config.hash_alg.new_hasher().ok()?;
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    hasher.update(&key_hash_data(primary)?);

    let identity_len = identity.write_len();
    let mut identity_body = Vec::with_capacity(identity_len);
    identity.to_writer(&mut identity_body).ok()?;
    if identity_body.len() != identity_len {
        return None;
    }
    match config.version_specific {
        SignatureVersionSpecific::V2 { .. } | SignatureVersionSpecific::V3 { .. } => {}
        SignatureVersionSpecific::V4 | SignatureVersionSpecific::V6 { .. } => {
            let prefix = match tag {
                Tag::UserId => 0xb4,
                Tag::UserAttribute => 0xd1,
                _ => return None,
            };
            hasher.update(&[prefix]);
            hasher.update(&u32::try_from(identity_len).ok()?.to_be_bytes());
        }
    }
    hasher.update(&identity_body);

    let signature_data_len = config.hash_signature_data(&mut hasher).ok()?;
    hasher.update(&config.trailer(signature_data_len).ok()?);
    Some(hasher.finalize().to_vec())
}

/// Hashes a target candidate at most once and only when its algorithms match.
fn signature_target_digest_for_selectors<'a>(
    signature: &Signature,
    selectors: impl Iterator<Item = CertificationRevocationSelector<'a>>,
    budget: &mut OpenPgpPolicyBudget,
    compute_digest: impl FnOnce() -> Option<Vec<u8>>,
) -> Result<Option<SignatureTargetDigest>, OpenPgpPolicyError> {
    let Some(config) = signature.config() else {
        return Ok(None);
    };
    if !selectors
        .into_iter()
        .any(|selector| selector.requests_digest_for(config.pub_alg, config.hash_alg))
    {
        return Ok(None);
    }

    budget.charge_signature_target_digest()?;
    Ok(compute_digest().map(|digest| SignatureTargetDigest {
        public_key_algorithm: config.pub_alg,
        hash_algorithm: config.hash_alg,
        digest,
    }))
}

pub(in crate::openpgp) fn all_components(
    certificates: &[pgp::composed::SignedPublicKey],
) -> Vec<PublicComponent> {
    certificates
        .iter()
        .flat_map(certificate_components)
        .collect()
}

pub(in crate::openpgp) fn certificate_components(
    certificate: &pgp::composed::SignedPublicKey,
) -> impl Iterator<Item = PublicComponent> + '_ {
    std::iter::once(PublicComponent::Primary(certificate.primary_key.clone())).chain(
        certificate
            .public_subkeys
            .iter()
            .map(|subkey| PublicComponent::Subkey(subkey.key.clone())),
    )
}

struct RevocationEvaluationContext<'a> {
    declarations: &'a [DesignatedRevokerId],
    candidates: &'a [PublicComponent],
    effective_signature: Option<&'a Signature>,
    reference_time: u64,
    cryptographic_policy_time: u64,
    target: RevocationTarget,
    self_revocation_hash_security: SelfRevocationHashSecurity,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct RevocationEvaluation {
    status: RevocationStatus,
    effective_at: Option<u64>,
}

fn resolve_revocation_status<'a, F>(
    verified_self_revocations: &[&'a Signature],
    revocations: impl Iterator<Item = &'a Signature>,
    context: RevocationEvaluationContext<'_>,
    budget: &mut OpenPgpPolicyBudget,
    mut verifies: F,
) -> Result<RevocationEvaluation, OpenPgpPolicyError>
where
    F: FnMut(
        &PublicComponent,
        &Signature,
        &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError>,
{
    let RevocationEvaluationContext {
        declarations,
        candidates,
        effective_signature,
        reference_time,
        cryptographic_policy_time,
        target,
        self_revocation_hash_security,
    } = context;
    let effective_self_revocation_at = verified_self_revocations
        .iter()
        .copied()
        .filter(|signature| {
            self_revocation_signature_acceptable_at(
                signature,
                reference_time,
                cryptographic_policy_time,
                target,
                self_revocation_hash_security,
            )
        })
        .filter(|signature| {
            revocation_is_effective(
                std::iter::once(*signature),
                effective_signature,
                reference_time,
                target,
            )
        })
        .filter_map(|signature| signature_creation_time(signature).map(u64::from))
        .max();
    if let Some(effective_at) = effective_self_revocation_at {
        return Ok(RevocationEvaluation {
            status: RevocationStatus::Revoked,
            effective_at: Some(effective_at),
        });
    }

    let mut unresolved_authority = false;
    for signature in revocations {
        if verified_self_revocations
            .iter()
            .any(|verified| std::ptr::eq(*verified, signature))
        {
            continue;
        }
        if !third_party_revocation_signature_acceptable(
            signature,
            reference_time,
            cryptographic_policy_time,
            target,
        ) {
            continue;
        }
        // Missing authority keys must remain indeterminate even when an
        // apparent retirement predates a newer owner signature. Only apply
        // key restoration after authenticating the designated revoker.
        let preliminary_signature = match target {
            RevocationTarget::PrimaryKey | RevocationTarget::Subkey => None,
            _ => effective_signature,
        };
        if !revocation_is_effective(
            std::iter::once(signature),
            preliminary_signature,
            reference_time,
            target,
        ) {
            continue;
        }

        let signature_algorithm = signature.config().map(|config| u8::from(config.pub_alg));
        let mut signature_unresolved_authority = false;
        let mut verifies_with = |declarations: &[DesignatedRevokerId]| {
            for declaration in declarations
                .iter()
                .filter(|declaration| Some(declaration.algorithm) == signature_algorithm)
            {
                let candidate = candidates
                    .iter()
                    .find(|candidate| declaration.matches_component(candidate));
                let Some(candidate) = candidate else {
                    // Without the authority key, issuer evidence is required
                    // to distinguish a relevant revocation from an unrelated
                    // packet. With the exact key present, cryptographic
                    // verification below remains authoritative even for
                    // legacy issuer-less packets.
                    signature_unresolved_authority |= revocation_issuer_matches_declaration(
                        signature,
                        declaration.algorithm,
                        &declaration.fingerprint,
                    );
                    continue;
                };

                // Authority is established by the target certificate's
                // declaration and this exact key packet.  In particular, do
                // not recursively require the revoker's certificate to be
                // unexpired or unrevoked.
                if verifies(candidate, signature, budget)? {
                    return Ok(true);
                }
            }
            Ok(false)
        };
        if verifies_with(declarations)? {
            if revocation_is_effective(
                std::iter::once(signature),
                effective_signature,
                reference_time,
                target,
            ) {
                return Ok(RevocationEvaluation {
                    status: RevocationStatus::Revoked,
                    effective_at: signature_creation_time(signature).map(u64::from),
                });
            }
        } else {
            unresolved_authority |= signature_unresolved_authority;
        }
    }

    Ok(RevocationEvaluation {
        status: if unresolved_authority {
            RevocationStatus::Indeterminate
        } else {
            RevocationStatus::NotRevoked
        },
        effective_at: None,
    })
}

fn revocation_issuer_matches_declaration(
    signature: &Signature,
    algorithm: u8,
    fingerprint: &[u8],
) -> bool {
    let Some(config) = signature.config() else {
        return false;
    };
    if u8::from(config.pub_alg) != algorithm {
        return false;
    }

    // A stronger issuer hint that is ambiguous or mismatched must not fall
    // through to a weaker, attacker-controlled unhashed hint.
    if let Some(matches) = unambiguous_issuer_hint_matches(
        config.hashed_subpackets().filter_map(|subpacket| {
            if let SubpacketData::IssuerFingerprint(fingerprint) = &subpacket.data {
                Some(fingerprint.as_bytes())
            } else {
                None
            }
        }),
        Some(fingerprint),
    ) {
        return matches;
    }

    let declaration_key_id = revocation_key_id(fingerprint);
    if let Some(matches) = unambiguous_issuer_hint_matches(
        config.hashed_subpackets().filter_map(|subpacket| {
            if let SubpacketData::IssuerKeyId(key_id) = &subpacket.data {
                Some(key_id.as_ref())
            } else {
                None
            }
        }),
        declaration_key_id,
    ) {
        return matches;
    }
    if let Some(matches) = unambiguous_issuer_hint_matches(
        signature.issuer_key_id().into_iter().map(AsRef::as_ref),
        declaration_key_id,
    ) {
        return matches;
    }
    unambiguous_issuer_hint_matches(
        config.unhashed_subpackets().filter_map(|subpacket| {
            if let SubpacketData::IssuerFingerprint(fingerprint) = &subpacket.data {
                Some(fingerprint.as_bytes())
            } else {
                None
            }
        }),
        Some(fingerprint),
    )
    .unwrap_or(false)
}

fn unambiguous_issuer_hint_matches<'a>(
    mut values: impl Iterator<Item = &'a [u8]>,
    expected: Option<&[u8]>,
) -> Option<bool> {
    let first = values.next()?;
    Some(expected.is_some_and(|expected| first == expected) && values.all(|value| value == first))
}

fn authenticate_direct_third_party_revocations<'a>(
    certificate: &'a pgp::composed::SignedPublicKey,
    authentication: &CertificateAuthentication<'a>,
    declarations: &[DesignatedRevokerId],
    candidates: &[PublicComponent],
    budget: &mut OpenPgpPolicyBudget,
) -> Result<Vec<AuthenticatedDirectThirdPartyRevocation<'a>>, OpenPgpPolicyError> {
    let mut verified = Vec::new();
    for signature in certificate
        .details
        .direct_signatures
        .iter()
        .filter(|signature| signature.typ() == Some(SignatureType::CertRevocation))
    {
        if authentication
            .direct_self_revocations
            .iter()
            .any(|self_revocation| std::ptr::eq(*self_revocation, signature))
        {
            continue;
        }
        let signature_algorithm = signature.config().map(|config| u8::from(config.pub_alg));
        for declaration in declarations
            .iter()
            .filter(|declaration| Some(declaration.algorithm) == signature_algorithm)
        {
            let Some(candidate) = candidates
                .iter()
                .find(|candidate| declaration.matches_component(candidate))
            else {
                continue;
            };
            let authentic = match candidate {
                PublicComponent::Primary(key) => verify_direct_certification_revocation(
                    signature,
                    &certificate.primary_key,
                    key,
                    budget,
                )?,
                PublicComponent::Subkey(key) => verify_direct_certification_revocation(
                    signature,
                    &certificate.primary_key,
                    key,
                    budget,
                )?,
            };
            if authentic {
                verified.push(AuthenticatedDirectThirdPartyRevocation {
                    signature,
                    signer: declaration.clone(),
                    selector: certification_revocation_selector(signature),
                });
                break;
            }
        }
    }
    Ok(verified)
}

struct DirectSignatureRevocationContext<'a, 'cert> {
    primary: &'a PublicKey,
    self_revocations: &'a [AuthenticatedDirectSelfRevocation<'cert>],
    declarations: &'a [DesignatedRevokerId],
    candidates: &'a [PublicComponent],
    verified_third_party_revocations: &'a [AuthenticatedDirectThirdPartyRevocation<'cert>],
    reference_time: u64,
    cryptographic_policy_time: u64,
}

fn direct_signature_survives_certification_revocations(
    signature: &Signature,
    context: &DirectSignatureRevocationContext<'_, '_>,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<bool, OpenPgpPolicyError> {
    let target_digest = signature_target_digest_for_selectors(
        signature,
        context
            .self_revocations
            .iter()
            .map(|revocation| revocation.selector)
            .chain(
                context
                    .verified_third_party_revocations
                    .iter()
                    .map(|revocation| revocation.selector),
            ),
        budget,
        || direct_key_signature_digest(signature, context.primary),
    )?;
    let matching_self_revocations = context
        .self_revocations
        .iter()
        .filter(|revocation| revocation.selector.targets(target_digest.as_ref()))
        .map(|revocation| revocation.signature)
        .collect::<Vec<_>>();
    let revocation = resolve_revocation_status(
        &matching_self_revocations,
        context
            .verified_third_party_revocations
            .iter()
            .filter(|revocation| revocation.selector.targets(target_digest.as_ref()))
            .map(|revocation| revocation.signature),
        RevocationEvaluationContext {
            declarations: context.declarations,
            candidates: context.candidates,
            effective_signature: Some(signature),
            reference_time: context.reference_time,
            cryptographic_policy_time: context.cryptographic_policy_time,
            target: RevocationTarget::DirectKeySignature {
                revocable: signature_is_revocable(signature),
            },
            self_revocation_hash_security: SelfRevocationHashSecurity::SecondPreimageResistance,
        },
        budget,
        |candidate, revocation, _| {
            Ok(context
                .verified_third_party_revocations
                .iter()
                .any(|verified| {
                    std::ptr::eq(verified.signature, revocation)
                        && verified.signer.matches_component(candidate)
                }))
        },
    )?;
    Ok(revocation.status.permits_new_data())
}

fn resolve_identity_certification_revocation_status<'a, F>(
    verified_self_revocations: &[&'a Signature],
    revocations: &[(&'a Signature, CertificationRevocationSelector<'a>)],
    context: RevocationEvaluationContext<'_>,
    budget: &mut OpenPgpPolicyBudget,
    compute_target_digest: impl FnOnce(&Signature) -> Option<Vec<u8>>,
    verifies: &mut F,
) -> Result<RevocationEvaluation, OpenPgpPolicyError>
where
    F: FnMut(
        &PublicComponent,
        &Signature,
        &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError>,
{
    let target_digest = if let Some(signature) = context.effective_signature {
        signature_target_digest_for_selectors(
            signature,
            revocations.iter().map(|(_, selector)| *selector),
            budget,
            || compute_target_digest(signature),
        )?
    } else {
        None
    };
    let matching_self_revocations = revocations
        .iter()
        .filter(|(signature, selector)| {
            selector.targets(target_digest.as_ref())
                && verified_self_revocations
                    .iter()
                    .any(|verified| std::ptr::eq(*verified, *signature))
        })
        .map(|(signature, _)| *signature)
        .collect::<Vec<_>>();

    resolve_revocation_status(
        &matching_self_revocations,
        revocations
            .iter()
            .filter(|(_, selector)| selector.targets(target_digest.as_ref()))
            .map(|(signature, _)| *signature),
        context,
        budget,
        verifies,
    )
}

struct IdentityCertificationSelection<'a> {
    selection: PolicySelection<'a>,
    revocation: RevocationEvaluation,
}

struct IdentityCertificationRevocationContext<'a, 'cert> {
    verified_self_revocations: &'a [&'cert Signature],
    declarations: &'a [DesignatedRevokerId],
    candidates: &'a [PublicComponent],
    reference_time: u64,
    cryptographic_policy_time: u64,
    self_revocation_hash_security: SelfRevocationHashSecurity,
}

/// Resolves revocation per certification, then selects the canonical newest
/// surviving binding.
///
/// RFC 9580 scopes both Revocable and Signature Target to an individual
/// signature. An untargeted revocation remains applicable to every strictly
/// older revocable certification over this identity, while a targeted
/// revocation is applicable only to the matching digest. Resolving before
/// selection allows an older non-revoked or non-revocable certification to
/// become effective when a newer certification is revoked.
fn select_surviving_identity_certification<'a, F>(
    certifications: &[&'a Signature],
    revocations: impl Iterator<Item = &'a Signature>,
    context: IdentityCertificationRevocationContext<'_, 'a>,
    budget: &mut OpenPgpPolicyBudget,
    compute_target_digest: impl Fn(&Signature) -> Option<Vec<u8>>,
    mut verifies: F,
) -> Result<IdentityCertificationSelection<'a>, OpenPgpPolicyError>
where
    F: FnMut(
        &PublicComponent,
        &Signature,
        &mut OpenPgpPolicyBudget,
    ) -> Result<bool, OpenPgpPolicyError>,
{
    let IdentityCertificationRevocationContext {
        verified_self_revocations,
        declarations,
        candidates,
        reference_time,
        cryptographic_policy_time,
        self_revocation_hash_security,
    } = context;
    let revocations = revocations
        .map(|signature| (signature, certification_revocation_selector(signature)))
        .collect::<Vec<_>>();
    let mut survivors = Vec::with_capacity(certifications.len());
    let mut newest_effective_revocation_at = None;

    for signature in certifications
        .iter()
        .copied()
        .filter(|signature| !signature_expired(signature, reference_time))
    {
        let revocable = signature_is_revocable(signature);
        if !revocable {
            // RFC 9580 §5.2.3.20 makes every later revocation irrelevant to
            // this signature, so no target digest or designated-key
            // verification is needed for it.
            survivors.push((
                signature,
                RevocationEvaluation {
                    status: RevocationStatus::NotRevoked,
                    effective_at: None,
                },
            ));
            continue;
        }
        let revocation = resolve_identity_certification_revocation_status(
            verified_self_revocations,
            &revocations,
            RevocationEvaluationContext {
                declarations,
                candidates,
                effective_signature: Some(signature),
                reference_time,
                cryptographic_policy_time,
                target: RevocationTarget::Certification { revocable },
                self_revocation_hash_security,
            },
            budget,
            |signature| compute_target_digest(signature),
            &mut verifies,
        )?;
        if revocation.status.is_revoked() {
            newest_effective_revocation_at = newest_effective_revocation_at
                .into_iter()
                .chain(revocation.effective_at)
                .max();
        } else {
            survivors.push((signature, revocation));
        }
    }

    let selection = select_newest_policy_signature(
        survivors.iter().map(|(signature, _)| *signature),
        PolicyContext::Identity,
        |_| Ok(false),
    )?;
    let revocation = match &selection {
        PolicySelection::Selected { signature, .. } => survivors
            .iter()
            .find_map(|(survivor, revocation)| {
                std::ptr::eq(*survivor, *signature).then_some(*revocation)
            })
            .ok_or(OpenPgpPolicyError::Internal)?,
        PolicySelection::Missing | PolicySelection::Conflict
            if newest_effective_revocation_at.is_some() =>
        {
            RevocationEvaluation {
                status: RevocationStatus::Revoked,
                effective_at: newest_effective_revocation_at,
            }
        }
        PolicySelection::Missing | PolicySelection::Conflict => {
            // Preserve revocation evidence for an identity whose binding
            // certifications were all rejected by policy or are all expired.
            resolve_identity_certification_revocation_status(
                verified_self_revocations,
                &revocations,
                RevocationEvaluationContext {
                    declarations,
                    candidates,
                    effective_signature: None,
                    reference_time,
                    cryptographic_policy_time,
                    target: RevocationTarget::Certification { revocable: true },
                    self_revocation_hash_security,
                },
                budget,
                |_| None,
                &mut verifies,
            )?
        }
    };

    Ok(IdentityCertificationSelection {
        selection,
        revocation,
    })
}

pub(in crate::openpgp) fn revocation_key_id(fingerprint: &[u8]) -> Option<&[u8]> {
    match fingerprint.len() {
        // Version 4 key IDs are the low 64 bits of the fingerprint.
        20 => fingerprint.get(fingerprint.len().saturating_sub(8)..),
        // Version 6 key IDs are the high 64 bits of the fingerprint.
        32 => fingerprint.get(..8),
        _ => None,
    }
}

pub(in crate::openpgp) fn validate_certificate<'a>(
    certificate: &'a pgp::composed::SignedPublicKey,
    candidates: &[PublicComponent],
    reference_time: u64,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<ValidatedCertificate<'a>, OpenPgpPolicyError> {
    validate_certificate_with_policy_time(
        certificate,
        candidates,
        reference_time,
        reference_time,
        budget,
    )
}

/// Validates the certificate state at `certificate_time` under the security
/// profile selected by `cryptographic_policy_time`.
///
/// Existing certificate inspection uses one time for both. Data-signature
/// verification uses the signature's creation time only for the historical
/// certificate view and retains its trusted custody/reference time for
/// algorithm cutoffs.
pub(in crate::openpgp) fn validate_certificate_with_policy_time<'a>(
    certificate: &'a pgp::composed::SignedPublicKey,
    candidates: &[PublicComponent],
    certificate_time: u64,
    cryptographic_policy_time: u64,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<ValidatedCertificate<'a>, OpenPgpPolicyError> {
    validate_certificate_intern(
        certificate,
        candidates,
        CertificateValidationTimes {
            certificate_time,
            cryptographic_policy_time,
        },
        budget,
    )
}

fn validate_certificate_intern<'a>(
    certificate: &'a pgp::composed::SignedPublicKey,
    candidates: &[PublicComponent],
    times: CertificateValidationTimes,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<ValidatedCertificate<'a>, OpenPgpPolicyError> {
    let CertificateValidationTimes {
        certificate_time: reference_time,
        cryptographic_policy_time,
    } = times;
    budget.begin_certificate_evaluation(certificate);
    let authentication = authenticate_certificate_bindings(certificate, times, budget)?;
    // A declaration may authorize the revocation that explicitly cancels the
    // declaration's own Direct Key signature. Resolve that one circular edge
    // from all verified Direct Key declarations, then rebuild the public
    // declaration set from surviving signatures only.
    let preliminary_authorized_revokers = designated_revokers(
        verified_revocation_authority_signatures(&authentication).filter(|signature| {
            revocation_authority_declaration_acceptable(
                signature,
                reference_time,
                cryptographic_policy_time,
            )
        }),
        budget,
    )?;
    let authenticated_direct_third_party_revocations = authenticate_direct_third_party_revocations(
        certificate,
        &authentication,
        &preliminary_authorized_revokers,
        candidates,
        budget,
    )?;
    let authenticated_direct_self_revocations = authentication
        .direct_self_revocations
        .iter()
        .copied()
        .map(|signature| AuthenticatedDirectSelfRevocation {
            signature,
            selector: certification_revocation_selector(signature),
        })
        .collect::<Vec<_>>();
    let direct_signature_revocation_context = DirectSignatureRevocationContext {
        primary: &certificate.primary_key,
        self_revocations: &authenticated_direct_self_revocations,
        declarations: &preliminary_authorized_revokers,
        candidates,
        verified_third_party_revocations: &authenticated_direct_third_party_revocations,
        reference_time,
        cryptographic_policy_time,
    };
    let mut unrevoked_direct_requirements = Vec::new();
    for signature in &authentication.verified_direct_requirements {
        if direct_signature_survives_certification_revocations(
            signature,
            &direct_signature_revocation_context,
            budget,
        )? {
            unrevoked_direct_requirements.push(*signature);
        }
    }
    let revocation_authority_requirements =
        designated_revokers(unrevoked_direct_requirements.iter().copied(), budget)?;
    let authorized_revoker_declarations = designated_revokers(
        unrevoked_direct_requirements
            .iter()
            .copied()
            .filter(|signature| {
                revocation_authority_declaration_acceptable(
                    signature,
                    reference_time,
                    cryptographic_policy_time,
                )
            }),
        budget,
    )?;

    let unrevoked_direct = authentication
        .verified_direct
        .iter()
        .copied()
        .filter(|signature| !signature_expired(signature, reference_time))
        .filter(|signature| {
            unrevoked_direct_requirements
                .iter()
                .any(|survivor| std::ptr::eq(*survivor, *signature))
        })
        .collect::<Vec<_>>();
    let unrevoked_direct_templates = authentication
        .verified_direct_templates
        .iter()
        .copied()
        .filter(|template| {
            unrevoked_direct_requirements
                .iter()
                .any(|survivor| std::ptr::eq(*survivor, template.template_signature()))
        })
        .collect::<Vec<_>>();
    let direct_selection = select_newest_policy_signature(
        unrevoked_direct.iter().copied(),
        PolicyContext::Direct,
        |_| Ok(false),
    )?;
    let mut attribute_fallback = Vec::new();
    let mut primary_fallback_policy_conflict = false;
    let mut user_ids = Vec::with_capacity(certificate.details.users.len());
    let mut authenticated_user_ids = Vec::new();
    for (index, (user, identity)) in certificate
        .details
        .users
        .iter()
        .zip(&authentication.user_ids)
        .enumerate()
    {
        let self_revocation_hash_security = user_id_self_revocation_hash_security(user.id.id());
        let IdentityCertificationSelection {
            selection,
            revocation,
        } = select_surviving_identity_certification(
            &identity.verified_certifications,
            user.signatures
                .iter()
                .filter(|signature| signature.typ() == Some(SignatureType::CertRevocation)),
            IdentityCertificationRevocationContext {
                verified_self_revocations: &identity.verified_revocations,
                declarations: &authorized_revoker_declarations,
                candidates,
                reference_time,
                cryptographic_policy_time,
                self_revocation_hash_security,
            },
            budget,
            |signature| {
                certification_signature_digest(
                    signature,
                    &certificate.primary_key,
                    Tag::UserId,
                    &user.id,
                )
            },
            |candidate, signature, budget| {
                candidate.verifies_certification_revocation(
                    signature,
                    &certificate.primary_key,
                    Tag::UserId,
                    &user.id,
                    budget,
                )
            },
        )?;
        let effective_signature = match &selection {
            PolicySelection::Selected { signature, .. } => Some(*signature),
            PolicySelection::Missing | PolicySelection::Conflict => None,
        };
        let policy_conflict = matches!(&selection, PolicySelection::Conflict);
        // RFC 4880 §5.2.3.12 scopes Revocable to the signature that carries
        // it, so the flag is read off the certification this revocation would
        // supersede rather than conjoined over every live certification.
        let effective_certification_revocable =
            effective_signature.is_none_or(signature_is_revocable);
        user_ids.push(IdentityPolicy {
            packet_body: Some(user.id.id()),
            policy_conflict,
            effective_signature,
            verified_certifications: identity.verified_certifications.clone(),
            verified_any_certifications: identity.verified_any_certifications.clone(),
            verified_revocations: identity.verified_revocations.clone(),
            verified_templates: identity.verified_templates.clone(),
            newest_certification_time: newest_creation_time(
                identity.verified_certifications.iter().copied(),
            ),
            effective_certification_revocable,
            self_revocation_hash_security,
            revocation_status: revocation.status,
            effective_revocation_at: revocation.effective_at,
        });
        if revocation.status.permits_new_data()
            && let PolicySelection::Selected {
                signature,
                projection,
            } = selection
        {
            authenticated_user_ids.push((
                ValidatedUserId {
                    index,
                    packet_body: user.id.id(),
                },
                projection.is_primary,
                signature_creation_time(signature).map(u64::from),
                signature,
            ));
        } else if revocation.status.permits_new_data() && policy_conflict {
            primary_fallback_policy_conflict = true;
        }
    }
    let mut user_attributes = Vec::with_capacity(certificate.details.user_attributes.len());
    for (attribute, identity) in certificate
        .details
        .user_attributes
        .iter()
        .zip(&authentication.user_attributes)
    {
        let self_revocation_hash_security = SelfRevocationHashSecurity::CollisionResistance;
        let IdentityCertificationSelection {
            selection,
            revocation,
        } = select_surviving_identity_certification(
            &identity.verified_certifications,
            attribute
                .signatures
                .iter()
                .filter(|signature| signature.typ() == Some(SignatureType::CertRevocation)),
            IdentityCertificationRevocationContext {
                verified_self_revocations: &identity.verified_revocations,
                declarations: &authorized_revoker_declarations,
                candidates,
                reference_time,
                cryptographic_policy_time,
                self_revocation_hash_security,
            },
            budget,
            |signature| {
                certification_signature_digest(
                    signature,
                    &certificate.primary_key,
                    Tag::UserAttribute,
                    &attribute.attr,
                )
            },
            |candidate, signature, budget| {
                candidate.verifies_certification_revocation(
                    signature,
                    &certificate.primary_key,
                    Tag::UserAttribute,
                    &attribute.attr,
                    budget,
                )
            },
        )?;
        let effective_signature = match &selection {
            PolicySelection::Selected { signature, .. } => Some(*signature),
            PolicySelection::Missing | PolicySelection::Conflict => None,
        };
        let policy_conflict = matches!(&selection, PolicySelection::Conflict);
        let effective_certification_revocable =
            effective_signature.is_none_or(signature_is_revocable);
        user_attributes.push(IdentityPolicy {
            packet_body: None,
            policy_conflict,
            effective_signature,
            verified_certifications: identity.verified_certifications.clone(),
            verified_any_certifications: identity.verified_any_certifications.clone(),
            verified_revocations: identity.verified_revocations.clone(),
            verified_templates: identity.verified_templates.clone(),
            newest_certification_time: newest_creation_time(
                identity.verified_certifications.iter().copied(),
            ),
            effective_certification_revocable,
            self_revocation_hash_security,
            revocation_status: revocation.status,
            effective_revocation_at: revocation.effective_at,
        });
        if revocation.status.permits_new_data()
            && certificate.primary_key.version() != KeyVersion::V6
            && let PolicySelection::Selected { signature, .. } = selection
        {
            attribute_fallback.push(signature);
        } else if revocation.status.permits_new_data()
            && certificate.primary_key.version() != KeyVersion::V6
            && policy_conflict
        {
            primary_fallback_policy_conflict = true;
        }
    }
    let selected_primary_signature = (certificate.primary_key.version() != KeyVersion::V6)
        .then(|| {
            select_primary_user_id_candidate(authenticated_user_ids.iter().map(
                |(user_id, primary, created, signature)| {
                    (*signature, user_id.packet_body(), *primary, *created)
                },
            ))
        })
        .flatten();
    // Primary policy combines direct-key and identity preferences, and its
    // effective signature need not be the newest of those statements. Keep
    // revocation ordering separate, and do not let a nonprimary identity or
    // User Attribute fallback restore the primary key.
    let direct_revocation_signature = match &direct_selection {
        PolicySelection::Selected { signature, .. } => Some(*signature),
        PolicySelection::Missing | PolicySelection::Conflict => None,
    };
    let primary_revocation_signature = direct_revocation_signature
        .into_iter()
        .chain(selected_primary_signature)
        .max_by_key(|signature| signature_creation_time(signature));
    let primary_fallback = match selected_primary_signature {
        Some(signature) => vec![signature],
        None => attribute_fallback,
    };
    let primary_fallback_selection = select_newest_policy_signature(
        primary_fallback.into_iter(),
        PolicyContext::Identity,
        |_| Ok(false),
    )?;
    let primary_fallback_selection = match primary_fallback_selection {
        PolicySelection::Missing if primary_fallback_policy_conflict => PolicySelection::Conflict,
        selection => selection,
    };
    let primary_selection = match primary_fallback_selection {
        PolicySelection::Selected {
            signature,
            projection: identity_projection,
        } if certificate.primary_key.version() != KeyVersion::V6 => match direct_selection {
            PolicySelection::Selected {
                projection: direct_projection,
                ..
            } => PolicySelection::Selected {
                signature,
                projection: Box::new(merge_v4_primary_policy(
                    *direct_projection,
                    *identity_projection,
                )),
            },
            PolicySelection::Missing => PolicySelection::Selected {
                signature,
                projection: identity_projection,
            },
            PolicySelection::Conflict => PolicySelection::Conflict,
        },
        PolicySelection::Missing => direct_selection,
        selection if certificate.primary_key.version() != KeyVersion::V6 => selection,
        _ => direct_selection,
    };
    let primary_policy_conflict = matches!(&primary_selection, PolicySelection::Conflict);
    let (primary_signature, primary_projection) = match primary_selection {
        PolicySelection::Selected {
            signature,
            projection,
        } => (Some(signature), Some(projection)),
        PolicySelection::Missing | PolicySelection::Conflict => (None, None),
    };
    let primary_revocation = resolve_revocation_status(
        &authentication.primary_self_revocations,
        certificate
            .details
            .revocation_signatures
            .iter()
            .filter(|signature| signature.typ() == Some(SignatureType::KeyRevocation)),
        RevocationEvaluationContext {
            declarations: &authorized_revoker_declarations,
            candidates,
            effective_signature: primary_signature.and(primary_revocation_signature),
            reference_time,
            cryptographic_policy_time,
            target: RevocationTarget::PrimaryKey,
            self_revocation_hash_security: SelfRevocationHashSecurity::SecondPreimageResistance,
        },
        budget,
        |candidate, signature, budget| {
            candidate.verifies_key_revocation(signature, &certificate.primary_key, budget)
        },
    )?;
    let primary_revocation_status = primary_revocation.status;
    let primary = ComponentPolicy {
        key: &certificate.primary_key,
        authenticated: primary_signature.is_some()
            && key_created_at_or_before(&certificate.primary_key, reference_time),
        policy_conflict: primary_policy_conflict,
        effective_signature: primary_signature,
        verified_bindings: unrevoked_direct,
        verified_any_bindings: authentication.verified_direct_requirements.clone(),
        verified_templates: unrevoked_direct_templates,
        key_flags: primary_projection
            .as_ref()
            .and_then(|projection| projection.key_flags.clone()),
        key_expiration_seconds: primary_projection
            .as_ref()
            .and_then(|projection| projection.key_expiration_seconds.flatten()),
        revocation_status: primary_revocation_status,
        revoked: primary_revocation_status.is_revoked(),
        signing_cross_certified: true,
        features: primary_projection
            .as_ref()
            .map_or(AuthenticatedFeatures::Missing, |projection| {
                projection.features.clone()
            }),
        allows_gnupg_ocb: primary_projection
            .as_ref()
            .is_some_and(|projection| projection.allows_gnupg_ocb()),
        preferred_symmetric: primary_projection
            .as_ref()
            .and_then(|projection| projection.preferred_symmetric.clone()),
        preferred_compression: primary_projection
            .as_ref()
            .and_then(|projection| projection.preferred_compression.clone()),
        preferred_aead: primary_projection
            .as_ref()
            .and_then(|projection| projection.preferred_aead.clone()),
        preferred_encryption_modes: primary_projection
            .as_ref()
            .map_or(EncryptionModePreferences::Missing, |projection| {
                projection.preferred_encryption_modes.clone()
            }),
    };

    let mut subkeys = Vec::with_capacity(certificate.public_subkeys.len());
    for (subkey, component) in certificate
        .public_subkeys
        .iter()
        .zip(&authentication.subkeys)
    {
        let binding_selection = select_newest_policy_signature(
            component
                .verified_bindings
                .iter()
                .copied()
                .filter(|signature| !signature_expired(signature, reference_time)),
            PolicyContext::Subkey,
            |signature| {
                embedded_cross_certified(
                    signature,
                    &subkey.key,
                    &certificate.primary_key,
                    times,
                    budget,
                )
            },
        )?;
        let policy_conflict = matches!(&binding_selection, PolicySelection::Conflict);
        let (binding, projection) = match binding_selection {
            PolicySelection::Selected {
                signature,
                projection,
            } => {
                let projection = match primary_projection.as_deref() {
                    Some(primary) => Box::new(merge_subkey_binding_policy(*projection, primary)),
                    None => projection,
                };
                (Some(signature), Some(projection))
            }
            PolicySelection::Missing | PolicySelection::Conflict => (None, None),
        };
        let revocation = resolve_revocation_status(
            &component.verified_revocations,
            subkey
                .signatures
                .iter()
                .filter(|signature| signature.typ() == Some(SignatureType::SubkeyRevocation)),
            RevocationEvaluationContext {
                declarations: &authorized_revoker_declarations,
                candidates,
                effective_signature: binding,
                reference_time,
                cryptographic_policy_time,
                target: RevocationTarget::Subkey,
                self_revocation_hash_security: SelfRevocationHashSecurity::SecondPreimageResistance,
            },
            budget,
            |candidate, signature, budget| {
                candidate.verifies_subkey_revocation(
                    signature,
                    &certificate.primary_key,
                    &subkey.key,
                    budget,
                )
            },
        )?;
        let revocation_status = revocation.status;
        subkeys.push(ComponentPolicy {
            key: &subkey.key,
            authenticated: binding.is_some()
                && key_created_at_or_before(&subkey.key, reference_time),
            policy_conflict,
            effective_signature: binding,
            verified_bindings: component.verified_bindings.clone(),
            verified_any_bindings: component.verified_any_bindings.clone(),
            verified_templates: component.verified_templates.clone(),
            key_flags: projection
                .as_ref()
                .and_then(|projection| projection.key_flags.clone()),
            key_expiration_seconds: projection
                .as_ref()
                .and_then(|projection| projection.key_expiration_seconds.flatten()),
            revocation_status,
            revoked: revocation_status.is_revoked(),
            signing_cross_certified: projection
                .as_ref()
                .is_some_and(|projection| projection.signing_cross_certified),
            features: projection
                .as_ref()
                .map_or(AuthenticatedFeatures::Missing, |projection| {
                    projection.features.clone()
                }),
            allows_gnupg_ocb: projection
                .as_ref()
                .is_some_and(|projection| projection.allows_gnupg_ocb()),
            preferred_symmetric: projection
                .as_ref()
                .and_then(|projection| projection.preferred_symmetric.clone()),
            preferred_compression: projection
                .as_ref()
                .and_then(|projection| projection.preferred_compression.clone()),
            preferred_aead: projection
                .as_ref()
                .and_then(|projection| projection.preferred_aead.clone()),
            preferred_encryption_modes: projection
                .as_ref()
                .map_or(EncryptionModePreferences::Missing, |projection| {
                    projection.preferred_encryption_modes.clone()
                }),
        });
    }

    let primary_user_id = select_primary_user_id_candidate(authenticated_user_ids.iter().map(
        |(user_id, primary, created, _)| (*user_id, user_id.packet_body(), *primary, *created),
    ));
    let authenticated_user_ids = authenticated_user_ids
        .into_iter()
        .map(|(user_id, _, _, _)| user_id)
        .collect();
    Ok(ValidatedCertificate {
        certificate,
        reference_time,
        revocation_authority_requirements,
        primary,
        subkeys,
        user_ids,
        user_attributes,
        authenticated_user_ids,
        primary_user_id,
    })
}

fn verifies_primary_key_signature(signature: &Signature, primary: &PublicKey) -> bool {
    signature_ignoring_unhashed_issuer_hints(signature)
        .is_some_and(|signature| signature.verify_key(primary).is_ok())
}

fn verifies_primary_certification(
    signature: &Signature,
    primary: &PublicKey,
    tag: Tag,
    identity: &impl Serialize,
) -> bool {
    signature_ignoring_unhashed_issuer_hints(signature).is_some_and(|signature| {
        signature
            .verify_certification(primary, tag, identity)
            .is_ok()
    })
}

fn verifies_primary_subkey_binding(
    signature: &Signature,
    primary: &PublicKey,
    subkey: &PublicSubkey,
) -> bool {
    signature_ignoring_unhashed_issuer_hints(signature)
        .is_some_and(|signature| signature.verify_subkey_binding(primary, subkey).is_ok())
}

fn authenticate_certificate_bindings<'a>(
    certificate: &'a pgp::composed::SignedPublicKey,
    times: CertificateValidationTimes,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<CertificateAuthentication<'a>, OpenPgpPolicyError> {
    if !key_signature_verification_acceptable(&certificate.primary_key) {
        return Ok(CertificateAuthentication {
            subkeys: std::iter::repeat_with(SubkeyAuthentication::default)
                .take(certificate.public_subkeys.len())
                .collect(),
            user_ids: std::iter::repeat_with(IdentityAuthentication::default)
                .take(certificate.details.users.len())
                .collect(),
            user_attributes: std::iter::repeat_with(IdentityAuthentication::default)
                .take(certificate.details.user_attributes.len())
                .collect(),
            ..CertificateAuthentication::default()
        });
    }
    let mut verified_direct = Vec::new();
    let mut verified_direct_requirements = Vec::new();
    let mut verified_direct_templates = Vec::new();
    let mut direct_self_revocations = Vec::new();
    for signature in &certificate.details.direct_signatures {
        match signature.typ() {
            Some(SignatureType::Key) => {
                if signature_matches_signer(signature, &certificate.primary_key) {
                    budget.charge_public_key_verification()?;
                    if verifies_primary_key_signature(signature, &certificate.primary_key) {
                        verified_direct_requirements.push(signature);
                        match signature_tier(signature, times) {
                            SignatureTier::Authenticated => verified_direct.push(signature),
                            SignatureTier::Template => {
                                verified_direct_templates.push(PolicyInactiveTemplate(signature));
                            }
                            SignatureTier::Rejected => {}
                        }
                    }
                }
            }
            Some(SignatureType::CertRevocation)
                if verify_direct_certification_revocation(
                    signature,
                    &certificate.primary_key,
                    &certificate.primary_key,
                    budget,
                )? =>
            {
                direct_self_revocations.push(signature);
            }
            _ => {}
        }
    }

    let mut primary_self_revocations = Vec::new();
    for signature in &certificate.details.revocation_signatures {
        if signature.typ() != Some(SignatureType::KeyRevocation) {
            continue;
        }
        if signature_matches_signer(signature, &certificate.primary_key) {
            budget.charge_public_key_verification()?;
            if verifies_primary_key_signature(signature, &certificate.primary_key) {
                primary_self_revocations.push(signature);
            }
        }
    }

    let mut user_ids = Vec::with_capacity(certificate.details.users.len());
    for user in &certificate.details.users {
        let mut certifications = Vec::new();
        let mut verified_any = Vec::new();
        let mut revocations = Vec::new();
        let mut templates = Vec::new();
        for signature in &user.signatures {
            if is_certification(signature.typ()) {
                // Every self-issued certification is verified, including ones
                // no policy tier accepts: mutation chronology still has to
                // sort a new statement after one a peer might retain.
                if signature_matches_signer(signature, &certificate.primary_key) {
                    budget.charge_public_key_verification()?;
                    if verifies_primary_certification(
                        signature,
                        &certificate.primary_key,
                        Tag::UserId,
                        &user.id,
                    ) {
                        verified_any.push(signature);
                        match signature_tier(signature, times) {
                            SignatureTier::Authenticated => certifications.push(signature),
                            SignatureTier::Template => {
                                templates.push(PolicyInactiveTemplate(signature));
                            }
                            SignatureTier::Rejected => {}
                        }
                    }
                }
            } else if signature.typ() == Some(SignatureType::CertRevocation)
                && signature_matches_signer(signature, &certificate.primary_key)
            {
                budget.charge_public_key_verification()?;
                if verifies_primary_certification(
                    signature,
                    &certificate.primary_key,
                    Tag::UserId,
                    &user.id,
                ) {
                    revocations.push(signature);
                }
            }
        }
        user_ids.push(IdentityAuthentication {
            verified_certifications: certifications,
            verified_any_certifications: verified_any,
            verified_revocations: revocations,
            verified_templates: templates,
        });
    }

    let mut user_attributes = Vec::with_capacity(certificate.details.user_attributes.len());
    for attribute in &certificate.details.user_attributes {
        let mut certifications = Vec::new();
        let mut verified_any = Vec::new();
        let mut revocations = Vec::new();
        let mut templates = Vec::new();
        for signature in &attribute.signatures {
            if is_certification(signature.typ()) {
                if signature_matches_signer(signature, &certificate.primary_key) {
                    budget.charge_public_key_verification()?;
                    if verifies_primary_certification(
                        signature,
                        &certificate.primary_key,
                        Tag::UserAttribute,
                        &attribute.attr,
                    ) {
                        verified_any.push(signature);
                        match user_attribute_certification_signature_tier(signature, times) {
                            SignatureTier::Authenticated => certifications.push(signature),
                            SignatureTier::Template => {
                                templates.push(PolicyInactiveTemplate(signature));
                            }
                            SignatureTier::Rejected => {}
                        }
                    }
                }
            } else if signature.typ() == Some(SignatureType::CertRevocation)
                && signature_matches_signer(signature, &certificate.primary_key)
            {
                budget.charge_public_key_verification()?;
                if verifies_primary_certification(
                    signature,
                    &certificate.primary_key,
                    Tag::UserAttribute,
                    &attribute.attr,
                ) {
                    revocations.push(signature);
                }
            }
        }
        user_attributes.push(IdentityAuthentication {
            verified_certifications: certifications,
            verified_any_certifications: verified_any,
            verified_revocations: revocations,
            verified_templates: templates,
        });
    }

    let mut subkeys = Vec::with_capacity(certificate.public_subkeys.len());
    for subkey in &certificate.public_subkeys {
        let mut bindings = Vec::new();
        let mut verified_any = Vec::new();
        let mut revocations = Vec::new();
        let mut templates = Vec::new();
        for signature in &subkey.signatures {
            if signature.typ() == Some(SignatureType::SubkeyBinding) {
                let tier = signature_tier(signature, times);
                if signature_matches_signer(signature, &certificate.primary_key) {
                    budget.charge_public_key_verification()?;
                    if verifies_primary_subkey_binding(
                        signature,
                        &certificate.primary_key,
                        &subkey.key,
                    ) {
                        verified_any.push(signature);
                        match tier {
                            SignatureTier::Authenticated => bindings.push(signature),
                            SignatureTier::Template => {
                                templates.push(PolicyInactiveTemplate(signature));
                            }
                            SignatureTier::Rejected => {}
                        }
                    }
                }
            } else if signature.typ() == Some(SignatureType::SubkeyRevocation)
                && signature_matches_signer(signature, &certificate.primary_key)
            {
                budget.charge_public_key_verification()?;
                if verifies_primary_subkey_binding(signature, &certificate.primary_key, &subkey.key)
                {
                    revocations.push(signature);
                }
            }
        }
        subkeys.push(SubkeyAuthentication {
            verified_bindings: bindings,
            verified_any_bindings: verified_any,
            verified_revocations: revocations,
            verified_templates: templates,
        });
    }

    Ok(CertificateAuthentication {
        subkeys,
        verified_direct,
        verified_direct_requirements,
        verified_direct_templates,
        direct_self_revocations,
        primary_self_revocations,
        user_ids,
        user_attributes,
    })
}

fn newest_creation_time<'a>(signatures: impl Iterator<Item = &'a Signature>) -> Option<u64> {
    signatures
        .filter_map(signature_creation_time)
        .map(u64::from)
        .max()
}

/// Returns every cryptographically verified Direct Key self-signature that can
/// carry a certificate-wide designated-revoker declaration.
///
/// RFC 9580 only specifies the deprecated Revocation Key subpacket on Direct
/// Key self-signatures. Revocation authority is cumulative across those
/// signatures, so every otherwise valid Direct Key self-signature participates,
/// not only the currently effective one. The caller separately applies
/// declaration policy and removes signatures canceled by Certification
/// Revocations before exposing or authorizing their declarations.
fn verified_revocation_authority_signatures<'view, 'cert: 'view>(
    authentication: &'view CertificateAuthentication<'cert>,
) -> impl Iterator<Item = &'cert Signature> + 'view {
    authentication.verified_direct_requirements.iter().copied()
}

/// Returns whether the signature type is an RFC 4880 certification.
/// Extracts the designated revoker declarations from verified Direct Key
/// self-signatures.
///
/// This is the only place Keyguard interprets a Revocation Key subpacket.  A
/// declaration without the required 0x80 class bit is not a declaration at all
/// (RFC 4880 §5.2.3.15), so ignoring the class bit — as hand-rolled mutation
/// copies used to — would let an unrelated subpacket authorize a revoker.
pub(super) fn designated_revokers<'a>(
    verified_signatures: impl IntoIterator<Item = &'a Signature>,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<Vec<DesignatedRevokerId>, OpenPgpPolicyError> {
    let mut declarations = Vec::new();
    for signature in verified_signatures {
        let Some(config) = signature.config() else {
            continue;
        };
        for subpacket in config.hashed_subpackets() {
            if let SubpacketData::RevocationKey(key) = &subpacket.data {
                let key_class = key.class as u8;
                if key_class & 0x80 == 0 {
                    continue;
                }
                let revoker = DesignatedRevokerId {
                    algorithm: u8::from(key.algorithm),
                    fingerprint: key.fingerprint.to_vec(),
                    key_class,
                };
                budget.charge_designated_revoker(revoker.clone())?;
                insert_designated_revoker(&mut declarations, revoker)?;
            }
        }
    }
    Ok(declarations)
}
