//! Atomic textual OpenPGP User ID replacement.
//!
//! Replacement is expressed as three distributable signed statements: a new
//! self-certified User ID, a certification revocation for the old exact User ID
//! packet, and — when the primary User ID was only implied by timestamp order —
//! a re-certification that pins it. All of them are applied to one packet set
//! and finalized once.

use pgp::{armor::BlockType, packet::Signature, types::SigningKey};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, CertificateSignatureOwner, KeyMaterial,
        PublicCertificatePacketSet, UserIdCertificationBuilder, UserIdRevocationBuilder,
        armor_key_packets, existing_user_id_recertification_config, identity_id,
        new_user_id_certification_config, serialize_packet_body,
    },
    crypto::{
        secret::{OpenPgpSecretSigner, SecretPacketRef},
        verification::signature_config_is_non_exportable,
    },
    mutation::{MutationDocuments, MutationPreflight},
    packet::USER_ID_TAG,
    policy::{
        ValidatedCertificate, certificate_index, next_signature_time, signature_creation_time,
        signature_is_primary,
    },
};

use super::{
    PrimaryKeyPolicySnapshot, UserIdMutationFailure, UserIdPostconditions, is_valid_new_user_id,
    map_certification_error, recertify_user_id, reject_present_user_id, user_id_index,
    user_id_policy_template,
};

pub(crate) struct UserIdReplacementInput {
    pub(crate) documents: MutationDocuments,
    pub(crate) old_identity_id: String,
    pub(crate) new_user_id: String,
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

pub(crate) fn replace_user_id_request(
    mut request: UserIdReplacementInput,
) -> Result<UserIdReplacementSuccess, UserIdMutationFailure> {
    validate_request(&request)?;

    let preflight = request.documents.open()?;
    let certificate = &preflight.canonical.semantic;
    let policy = preflight.policy()?;
    policy.authorize_user_id_mutation()?;
    let primary_snapshot = PrimaryKeyPolicySnapshot::capture(&policy);

    let target_index = user_id_index(certificate, &request.old_identity_id)?;
    let target = &certificate.details.users[target_index];
    if target.id.id() == request.new_user_id.as_bytes() {
        return Err(UserIdMutationFailure::SameIdentity);
    }
    // A User ID packet body is exactly its identifier.
    let target_body = target.id.id().to_vec();
    let target_identity = policy
        .user_id_at(target_index)
        .ok_or(UserIdMutationFailure::TargetNotFound)?;
    let mutation_is_local = target_identity
        .revocation_exportability_template()
        .is_some_and(|signature| {
            signature
                .config()
                .is_some_and(signature_config_is_non_exportable)
        });
    let new_identity_id = identity_id(USER_ID_TAG, request.new_user_id.as_bytes());
    let new_active = policy
        .authenticated_user_ids()
        .any(|value| value.packet_body() == request.new_user_id.as_bytes());

    // Idempotence is checked before clock validation and signer acquisition.
    if target_identity.revocation_status.is_revoked() && new_active {
        if !target_identity.live_certifications_are_revocable(preflight.reference_time) {
            return Err(UserIdMutationFailure::NonRevocable);
        }
        let effective_at = target_identity
            .effective_revocation_at
            .unwrap_or(preflight.reference_time);
        let primary_user_id = policy
            .primary_user_id()
            .map(|value| String::from_utf8_lossy(value.packet_body()).into_owned())
            .unwrap_or_default();
        drop(policy);
        return finish_success(
            &preflight,
            &preflight.packet_set,
            UserIdPostconditions::default(),
            Vec::new(),
            mutation_is_local,
            false,
            effective_at,
            request.old_identity_id.clone(),
            new_identity_id,
            primary_user_id,
        );
    }
    policy.authorize_user_id_replacement(target_index)?;
    reject_present_user_id(&preflight, &policy, request.new_user_id.as_bytes())?;

    let old_is_primary = policy
        .primary_user_id()
        .is_some_and(|primary| primary.index() == target_index);
    let primary_user_id = policy
        .primary_user_id()
        .ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    let primary_index = primary_user_id.index();
    let primary_user = &certificate.details.users[primary_index];
    let primary_identity = policy
        .user_id_at(primary_index)
        .ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    if primary_identity.policy_conflict {
        return Err(UserIdMutationFailure::PolicyConflict);
    }
    let primary_certification = primary_identity
        .effective_signature
        .ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    let policy_template =
        user_id_policy_template(&policy).ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    let target_certification = target_identity
        .effective_signature
        .ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    // A fresh non-primary replacement would otherwise become the newest
    // fallback candidate and silently take over primary User ID selection.
    let preserve_fallback_primary = !old_is_primary && !signature_is_primary(primary_certification);
    let fallback_primary_was_revocable = primary_identity.effective_certification_revocable;

    let mut newest_relevant = target_identity
        .newest_conservative_certification_time()
        .ok_or(UserIdMutationFailure::MissingSelfSignature)?;
    newest_relevant = newest_relevant.max(
        primary_identity
            .newest_conservative_certification_time()
            .ok_or(UserIdMutationFailure::MissingSelfSignature)?,
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
                        .ok_or(UserIdMutationFailure::MissingSelfSignature)?,
                );
            }
        }
    }

    let replacement_time = next_signature_time(preflight.reference_time, Some(newest_relevant))?;
    let effective_at = u64::from(replacement_time.as_secs());
    if preflight.secret.primary_key.secret_params().is_encrypted() {
        return Err(UserIdMutationFailure::ProtectedSecretKey);
    }

    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&preflight.secret.primary_key),
        &preflight.secret.primary_key as &dyn SigningKey,
    )
    .map_err(|_| UserIdMutationFailure::InternalFailure)?;
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
        return Err(UserIdMutationFailure::InvalidNewUserId);
    }
    let fallback_primary_recertification = if preserve_fallback_primary {
        let config = existing_user_id_recertification_config(
            primary_certification,
            signer,
            replacement_time,
            true,
        )
        .map_err(map_certification_error)?;
        Some(recertify_user_id(
            signer,
            certificate,
            primary_index,
            config,
        )?)
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
    additions.extend(fallback_primary_recertification);
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
        preflight
            .packet_set
            .fragment(&additions, &preflight.canonical.bytes)?
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
        UserIdPostconditions {
            inactive: Some(&target_body),
            active: vec![&new_user_id_body],
            expected_primary: Some(&expected_primary_user_id),
            exclusive_primary: false,
            primary_snapshot: Some(primary_snapshot),
            fallback_primary: preserve_fallback_primary
                .then_some((primary_user_body.as_slice(), fallback_primary_was_revocable)),
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

#[allow(clippy::too_many_arguments)]
fn finish_success(
    preflight: &MutationPreflight,
    mutated: &PublicCertificatePacketSet,
    postconditions: UserIdPostconditions<'_>,
    replacement_certificate_armored: Vec<u8>,
    local_only_mutation: bool,
    changed: bool,
    effective_at_epoch_seconds: u64,
    old_identity_id: String,
    new_identity_id: String,
    unchanged_primary_user_id: String,
) -> Result<UserIdReplacementSuccess, UserIdMutationFailure> {
    let (output, primary_user_id) = preflight.finalize(
        mutated,
        local_only_mutation,
        |canonical, policy: &ValidatedCertificate<'_>, secret_fingerprints| {
            postconditions.check(policy)?;
            let primary_user_id = policy
                .primary_user_id()
                .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
                .unwrap_or_default();
            Ok::<_, UserIdMutationFailure>((
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

fn validate_request(request: &UserIdReplacementInput) -> Result<(), UserIdMutationFailure> {
    request.documents.check()?;
    if request.old_identity_id.is_empty() {
        return Err(UserIdMutationFailure::TargetNotFound);
    }
    if !is_valid_new_user_id(&request.new_user_id) {
        return Err(UserIdMutationFailure::InvalidNewUserId);
    }
    Ok(())
}

#[cfg(test)]
mod tests;
