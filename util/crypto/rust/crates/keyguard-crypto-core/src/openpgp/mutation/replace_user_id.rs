//! Atomic textual OpenPGP User ID replacement.
//!
//! Replacement is expressed as three distributable signed statements: a new
//! self-certified User ID, a certification revocation for the old exact User ID
//! packet, and — when the primary User ID was only implied by timestamp order —
//! a re-certification that pins it. All of them are applied to one packet set
//! and finalized once.

use pgp::{
    armor::BlockType,
    packet::{KeyFlags, Signature},
    types::SigningKey,
};
use zeroize::{Zeroize, Zeroizing};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, CertificateSignatureOwner, KeyMaterial,
        PublicCertificatePacketSet, UserIdCertificationBuilder, UserIdCertificationError,
        UserIdRevocationBuilder, armor_key_packets, existing_user_id_recertification_config,
        identity_id, new_user_id_certification_config, serialize_packet_body,
    },
    crypto::{
        secret::{OpenPgpSecretSigner, SecretPacketRef},
        verification::signature_config_is_non_exportable,
    },
    mutation::{
        MutationPreflight, impl_mutation_failure_conversions, next_signature_time,
        validate_mutation_document_bounds,
    },
    packet::USER_ID_TAG,
    policy::{
        ValidatedCertificate, certificate_index, signature_creation_time, signature_is_primary,
        signature_is_revocable,
    },
};

const MAX_USER_ID_BYTES: usize = 1024;

pub(crate) struct UserIdReplacementInput {
    pub(crate) private_key: Vec<u8>,
    pub(crate) public_key: Vec<u8>,
    pub(crate) expected_primary_fingerprint: String,
    pub(crate) old_identity_id: String,
    pub(crate) new_user_id: String,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: u64,
}

impl Drop for UserIdReplacementInput {
    fn drop(&mut self) {
        self.private_key.zeroize();
    }
}

pub(crate) struct UserIdReplacementSuccess {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
    pub(crate) replacement_certificate_armored: Vec<u8>,
    pub(crate) changed: bool,
    pub(crate) effective_at_epoch_seconds: u64,
    pub(crate) old_identity_id: String,
    pub(crate) new_identity_id: String,
    pub(crate) primary_user_id: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum UserIdReplacementFailure {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    TargetNotFound,
    TargetInactive,
    InvalidNewUserId,
    SameIdentity,
    DuplicateIdentity,
    PreviouslyRevokedIdentity,
    UnsupportedKeyVersion,
    ProtectedSecretKey,
    MissingSelfSignature,
    NonRevocable,
    UnsupportedTemplate,
    PolicyConflict,
    TimeConflict,
    SignatureVerificationFailed,
    InternalFailure,
    CertificateRevoked,
    UnresolvedRevocationAuthority,
    UnsupportedSigningHash,
    ResourceLimit,
}

impl_mutation_failure_conversions!(UserIdReplacementFailure, revoked => CertificateRevoked);

/// Primary-key policy fields a rename must leave exactly as it found them.
///
/// The single post-mutation validation reports what the certificate now says;
/// it cannot know what it said before. A V4 primary key takes its flags,
/// expiration and feature set from its primary User ID's certification, so a
/// new certification is precisely the thing that could move them — which is
/// why this comparison stays even though the rest of the old hand-diff is now
/// covered by that validation.
#[derive(Debug, PartialEq, Eq)]
struct PrimaryKeyPolicySnapshot {
    authenticated: bool,
    revocation_status: crate::openpgp::policy::RevocationStatus,
    key_flags: Option<KeyFlags>,
    key_expiration_seconds: Option<u32>,
    features: crate::openpgp::policy::AuthenticatedFeatures,
    allows_gnupg_ocb: bool,
    preferred_encryption_modes: crate::openpgp::policy::EncryptionModePreferences,
}

impl PrimaryKeyPolicySnapshot {
    fn capture(policy: &ValidatedCertificate<'_>) -> Self {
        Self {
            authenticated: policy.primary.authenticated,
            revocation_status: policy.primary.revocation_status,
            key_flags: policy.primary.key_flags.clone(),
            key_expiration_seconds: policy.primary.key_expiration_seconds,
            features: policy.primary.features.clone(),
            allows_gnupg_ocb: policy.primary.allows_gnupg_ocb,
            preferred_encryption_modes: policy.primary.preferred_encryption_modes.clone(),
        }
    }
}

pub(crate) fn replace_user_id_request(
    mut request: UserIdReplacementInput,
) -> Result<UserIdReplacementSuccess, UserIdReplacementFailure> {
    validate_request(&request)?;

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let preflight = MutationPreflight::open(
        &private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
        &request.expected_primary_fingerprint,
        request.reference_time_epoch_seconds,
    )?;
    let certificate = &preflight.canonical.semantic;
    let policy = preflight.policy()?;
    if policy.primary.policy_conflict {
        return Err(UserIdReplacementFailure::PolicyConflict);
    }
    policy.primary_component().authorize_mutation()?;
    let primary_snapshot = PrimaryKeyPolicySnapshot::capture(&policy);

    let target_index = certificate
        .details
        .users
        .iter()
        .position(|user| identity_id(USER_ID_TAG, user.id.id()) == request.old_identity_id)
        .ok_or(UserIdReplacementFailure::TargetNotFound)?;
    let target = &certificate.details.users[target_index];
    if target.id.id() == request.new_user_id.as_bytes() {
        return Err(UserIdReplacementFailure::SameIdentity);
    }
    // A User ID packet body is exactly its identifier.
    let target_body = target.id.id().to_vec();
    let target_identity = policy
        .user_id_at(target_index)
        .ok_or(UserIdReplacementFailure::TargetNotFound)?;
    let mutation_is_local = target_identity
        .revocation_exportability_template()
        .is_some_and(|signature| {
            signature
                .config()
                .is_some_and(signature_config_is_non_exportable)
        });
    if target_identity.policy_conflict {
        return Err(UserIdReplacementFailure::PolicyConflict);
    }
    if target_identity.revocation_status.is_indeterminate() {
        return Err(UserIdReplacementFailure::UnresolvedRevocationAuthority);
    }
    let new_identity_id = identity_id(USER_ID_TAG, request.new_user_id.as_bytes());
    let existing_new = policy.user_id(request.new_user_id.as_bytes());
    let new_active = policy
        .authenticated_user_ids()
        .any(|value| value.packet_body() == request.new_user_id.as_bytes());

    // Idempotence is checked before clock validation and signer acquisition.
    if target_identity.revocation_status.is_revoked() && new_active {
        if !target_identity.live_certifications_are_revocable(request.reference_time_epoch_seconds)
        {
            return Err(UserIdReplacementFailure::NonRevocable);
        }
        let effective_at = target_identity
            .effective_revocation_at
            .unwrap_or(request.reference_time_epoch_seconds);
        let primary_user_id = policy
            .primary_user_id()
            .map(|value| String::from_utf8_lossy(value.packet_body()).into_owned())
            .unwrap_or_default();
        drop(policy);
        return finish_success(
            &preflight,
            &preflight.packet_set,
            request.reference_time_epoch_seconds,
            FinishGuards::default(),
            Vec::new(),
            mutation_is_local,
            false,
            effective_at,
            request.old_identity_id.clone(),
            new_identity_id,
            primary_user_id,
        );
    }
    let target_active = policy
        .authenticated_user_ids()
        .any(|value| value.index() == target_index);
    if !target_active {
        return Err(UserIdReplacementFailure::TargetInactive);
    }
    // A User ID packet that is already present but carries no live
    // certification is still present: the replacement cannot add the packet
    // again. The packet set is asked directly, because the composed view omits
    // a signature-less identity entirely and reporting a missing
    // self-signature would describe the wrong object.
    if preflight
        .packet_set
        .has_identity(USER_ID_TAG, request.new_user_id.as_bytes())
    {
        return Err(
            if existing_new.is_some_and(|identity| identity.revocation_status.is_revoked()) {
                UserIdReplacementFailure::PreviouslyRevokedIdentity
            } else {
                UserIdReplacementFailure::DuplicateIdentity
            },
        );
    }

    let old_is_primary = policy
        .primary_user_id()
        .is_some_and(|primary| primary.index() == target_index);
    let primary_user_id = policy
        .primary_user_id()
        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?;
    let primary_index = primary_user_id.index();
    let primary_user = &certificate.details.users[primary_index];
    let primary_identity = policy
        .user_id_at(primary_index)
        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?;
    if primary_identity.policy_conflict {
        return Err(UserIdReplacementFailure::PolicyConflict);
    }
    let policy_template = primary_identity
        .effective_signature
        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?;
    let target_certification = target_identity
        .effective_signature
        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?;
    // A fresh non-primary replacement would otherwise become the newest
    // fallback candidate and silently take over primary User ID selection.
    let preserve_fallback_primary = !old_is_primary && !signature_is_primary(policy_template);
    let fallback_primary_was_revocable = primary_identity.effective_certification_revocable;

    let mut newest_relevant = target_identity
        .newest_conservative_certification_time()
        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?;
    newest_relevant = newest_relevant.max(
        primary_identity
            .newest_conservative_certification_time()
            .ok_or(UserIdReplacementFailure::MissingSelfSignature)?,
    );
    if let Some(time) = newest_signature_time(target_identity.verified_revocations.iter().copied())
    {
        newest_relevant = newest_relevant.max(time);
    }
    if old_is_primary {
        // Multiple active explicit-primary User IDs are valid; policy has
        // already selected one deterministically. Its replacement must be
        // newer than every competing explicit-primary certification so it
        // remains the unambiguous primary statement.
        for index in 0..certificate.details.users.len() {
            let Some(identity) = policy.user_id_at(index) else {
                continue;
            };
            if !identity.authenticated() {
                continue;
            }
            let Some(signature) = identity.effective_signature else {
                continue;
            };
            if signature_is_primary(signature) {
                newest_relevant = newest_relevant.max(
                    signature_creation_time(signature)
                        .map(u64::from)
                        .ok_or(UserIdReplacementFailure::MissingSelfSignature)?,
                );
            }
        }
    }

    let replacement_time =
        next_signature_time(request.reference_time_epoch_seconds, Some(newest_relevant))
            .ok_or(UserIdReplacementFailure::TimeConflict)?;
    let effective_at = u64::from(replacement_time.as_secs());
    if !target_identity.live_certifications_are_revocable(effective_at) {
        return Err(UserIdReplacementFailure::NonRevocable);
    }
    if preflight.secret.primary_key.secret_params().is_encrypted() {
        return Err(UserIdReplacementFailure::ProtectedSecretKey);
    }

    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&preflight.secret.primary_key),
        &preflight.secret.primary_key as &dyn SigningKey,
    )
    .map_err(|_| UserIdReplacementFailure::InternalFailure)?;
    let signer = signer.as_ref();
    let certification_config = new_user_id_certification_config(
        policy_template,
        target_certification,
        signer,
        replacement_time,
        old_is_primary,
    )
    .map_err(map_certification_error)?;
    let certification = UserIdCertificationBuilder::new(
        signer,
        &certificate.primary_key,
        &request.new_user_id,
        certification_config,
    )
    .build()
    .map_err(map_certification_error)?;
    if certification.user_id.id() != request.new_user_id.as_bytes() {
        return Err(UserIdReplacementFailure::InvalidNewUserId);
    }
    let fallback_primary_certification = if preserve_fallback_primary {
        let config = existing_user_id_recertification_config(
            policy_template,
            signer,
            replacement_time,
            true,
        )
        .map_err(map_certification_error)?;
        Some(
            UserIdCertificationBuilder::for_existing(
                signer,
                &certificate.primary_key,
                &primary_user.id,
                config,
            )
            .build()
            .map_err(map_certification_error)?
            .signature,
        )
    } else {
        None
    };
    let revocation = UserIdRevocationBuilder::new(
        signer,
        &certificate.primary_key,
        &target.id,
        target_certification,
        replacement_time,
    )
    .build()
    .map_err(map_certification_error)?;

    let primary_user_body = primary_user.id.id().to_vec();
    let expected_primary_user_id = if old_is_primary {
        request.new_user_id.as_bytes().to_vec()
    } else {
        primary_user_body.clone()
    };
    drop(policy);

    let mut additions = vec![CertificateAddition::Signature {
        owner: CertificateSignatureOwner::Identity {
            tag: USER_ID_TAG,
            body: target_body.clone(),
        },
        body: serialize_packet_body(&revocation)?,
    }];
    if let Some(signature) = fallback_primary_certification {
        additions.push(CertificateAddition::Signature {
            owner: CertificateSignatureOwner::Identity {
                tag: USER_ID_TAG,
                body: primary_user_body.clone(),
            },
            body: serialize_packet_body(&signature)?,
        });
    }
    let new_user_id_body = certification.user_id.id().to_vec();
    additions.push(CertificateAddition::Identity {
        tag: USER_ID_TAG,
        body: new_user_id_body.clone(),
        signature_bodies: vec![serialize_packet_body(&certification.signature)?],
    });

    // The replacement and its revocation inherit the old identity's local
    // policy. Certification Revocations do not acquire RFC export semantics
    // from a type-4 subpacket, so suppress the operation artifact explicitly
    // instead of broadening generic signature filtering again.
    let artifact = if mutation_is_local {
        Vec::new()
    } else {
        preflight.packet_set.fragment(&additions)?
    };
    let mut mutated = preflight.packet_set.clone();
    mutated.apply_additions(&additions)?;
    let artifact_armored = if artifact.is_empty() {
        Vec::new()
    } else {
        armor_key_packets(&artifact, BlockType::PublicKey)?
    };

    finish_success(
        &preflight,
        &mutated,
        effective_at,
        FinishGuards {
            revoked_target: Some(target_body),
            required_user_id: Some(new_user_id_body),
            expected_primary_user_id: Some(expected_primary_user_id),
            primary_snapshot: Some(primary_snapshot),
            fallback_primary: preserve_fallback_primary
                .then_some((primary_user_body, fallback_primary_was_revocable)),
        },
        artifact_armored,
        mutation_is_local,
        true,
        effective_at,
        request.old_identity_id.clone(),
        new_identity_id,
        String::new(),
    )
}

/// Post-conditions the finalized certificate has to satisfy.
#[derive(Default)]
struct FinishGuards {
    /// Old User ID packet body; must be inactive.
    revoked_target: Option<Vec<u8>>,
    /// New User ID packet body; must be active.
    required_user_id: Option<Vec<u8>>,
    expected_primary_user_id: Option<Vec<u8>>,
    primary_snapshot: Option<PrimaryKeyPolicySnapshot>,
    /// Pinned fallback primary body and the Revocable flag it must keep.
    fallback_primary: Option<(Vec<u8>, bool)>,
}

#[allow(clippy::too_many_arguments)]
fn finish_success(
    preflight: &MutationPreflight,
    mutated: &PublicCertificatePacketSet,
    mutation_time: u64,
    guards: FinishGuards,
    replacement_certificate_armored: Vec<u8>,
    local_only_mutation: bool,
    changed: bool,
    effective_at_epoch_seconds: u64,
    old_identity_id: String,
    new_identity_id: String,
    unchanged_primary_user_id: String,
) -> Result<UserIdReplacementSuccess, UserIdReplacementFailure> {
    let (output, primary_user_id) = preflight.finalize(
        mutated,
        mutation_time,
        local_only_mutation,
        |canonical, policy: &ValidatedCertificate<'_>, secret_fingerprints| {
            if policy.primary.policy_conflict {
                return Err(UserIdReplacementFailure::PolicyConflict);
            }
            policy.primary_component().authorize_mutation()?;
            if let Some(body) = guards.revoked_target.as_deref()
                && policy
                    .authenticated_user_ids()
                    .any(|active| active.packet_body() == body)
            {
                return Err(UserIdReplacementFailure::SignatureVerificationFailed);
            }
            if let Some(body) = guards.required_user_id.as_deref()
                && !policy
                    .authenticated_user_ids()
                    .any(|active| active.packet_body() == body)
            {
                return Err(UserIdReplacementFailure::SignatureVerificationFailed);
            }
            if let Some(expected) = guards.expected_primary_user_id.as_deref()
                && policy
                    .primary_user_id()
                    .map(|user_id| user_id.packet_body())
                    != Some(expected)
            {
                return Err(UserIdReplacementFailure::SignatureVerificationFailed);
            }
            if let Some(snapshot) = guards.primary_snapshot.as_ref()
                && &PrimaryKeyPolicySnapshot::capture(policy) != snapshot
            {
                return Err(UserIdReplacementFailure::SignatureVerificationFailed);
            }
            if let Some((body, revocable)) = guards.fallback_primary.as_ref()
                && !policy
                    .user_id(body)
                    .and_then(|identity| identity.effective_signature)
                    .is_some_and(|signature| signature_is_revocable(signature) == *revocable)
            {
                return Err(UserIdReplacementFailure::SignatureVerificationFailed);
            }
            let primary_user_id = policy
                .primary_user_id()
                .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
                .unwrap_or_default();
            Ok::<_, UserIdReplacementFailure>((
                primary_user_id,
                certificate_index(policy, canonical, secret_fingerprints),
            ))
        },
    )?;
    Ok(UserIdReplacementSuccess {
        key_material: output.key_material,
        certificate_index: output.certificate_index,
        replacement_certificate_armored,
        changed,
        effective_at_epoch_seconds,
        old_identity_id,
        new_identity_id,
        primary_user_id: if changed {
            primary_user_id
        } else {
            unchanged_primary_user_id
        },
    })
}

fn newest_signature_time<'a>(signatures: impl Iterator<Item = &'a Signature>) -> Option<u64> {
    signatures
        .filter_map(signature_creation_time)
        .map(u64::from)
        .max()
}

fn validate_request(request: &UserIdReplacementInput) -> Result<(), UserIdReplacementFailure> {
    if request.private_key.iter().all(u8::is_ascii_whitespace) {
        return Err(UserIdReplacementFailure::EmptyPrivateKey);
    }
    validate_mutation_document_bounds(
        &request.private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
    )?;
    if request.old_identity_id.is_empty() {
        return Err(UserIdReplacementFailure::TargetNotFound);
    }
    if request.new_user_id.is_empty()
        || request.new_user_id.chars().all(char::is_whitespace)
        || request.new_user_id.len() > MAX_USER_ID_BYTES
        || request.new_user_id.chars().any(char::is_control)
    {
        return Err(UserIdReplacementFailure::InvalidNewUserId);
    }
    Ok(())
}

const fn map_certification_error(error: UserIdCertificationError) -> UserIdReplacementFailure {
    match error {
        UserIdCertificationError::InvalidUserId => UserIdReplacementFailure::InvalidNewUserId,
        UserIdCertificationError::UnsupportedTemplate => {
            UserIdReplacementFailure::UnsupportedTemplate
        }
        UserIdCertificationError::UnsupportedSigningHash => {
            UserIdReplacementFailure::UnsupportedSigningHash
        }
        UserIdCertificationError::InvalidSignatureType
        | UserIdCertificationError::SigningFailed => UserIdReplacementFailure::InternalFailure,
    }
}

#[cfg(test)]
mod tests;
