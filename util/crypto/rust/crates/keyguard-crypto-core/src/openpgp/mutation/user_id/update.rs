//! Add an identity or select the primary identity without retiring another identity.

use std::borrow::Cow;

use pgp::types::{KeyDetails, KeyVersion, SigningKey};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, KeyMaterial, UserIdCertificationBuilder,
        additional_user_id_certification_config, existing_user_id_recertification_config,
        preserve_key_policy, serialize_packet_body,
    },
    crypto::secret::{OpenPgpSecretSigner, SecretPacketRef},
    mutation::{MutationDocuments, MutationPreflight},
    packet::USER_ID_TAG,
    policy::{
        ValidatedCertificate, ValidatedUserId, certificate_index, next_signature_time,
        signature_creation_time,
    },
};

use super::{
    PrimaryKeyPolicySnapshot, UserIdMutationFailure as Failure, UserIdPostconditions,
    has_exclusive_primary_user_id, is_explicit_primary, is_valid_new_user_id,
    map_certification_error, recertify_user_id, reject_present_user_id, user_id_index,
    user_id_policy_template,
};

pub(crate) enum UserIdUpdate {
    Add(String),
    MakePrimary(String),
}

pub(crate) struct UserIdUpdateInput {
    pub(crate) documents: MutationDocuments,
    pub(crate) change: UserIdUpdate,
}

pub(crate) struct UserIdUpdateSuccess {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
    pub(crate) changed: bool,
}

pub(crate) fn update_user_id_request(
    mut request: UserIdUpdateInput,
) -> Result<UserIdUpdateSuccess, Failure> {
    request.documents.check()?;
    if let UserIdUpdate::Add(value) = &request.change
        && !is_valid_new_user_id(value)
    {
        return Err(Failure::InvalidNewUserId);
    }
    let preflight = request.documents.open()?;
    let policy = preflight.policy()?;
    let plan = plan_update(&request.change, &preflight, &policy)?;
    let before = PrimaryKeyPolicySnapshot::capture(&policy);
    let certificate = &preflight.canonical.semantic;
    let active_before: Vec<&[u8]> = policy
        .authenticated_user_ids()
        .map(|identity| identity.packet_body())
        .collect();
    let mut additions = Vec::new();
    if !plan.already_applied {
        if preflight.secret.primary_key.secret_params().is_encrypted() {
            return Err(Failure::ProtectedSecretKey);
        }
        let policy_template =
            user_id_policy_template(&policy).ok_or(Failure::MissingSelfSignature)?;
        // Follow the effective template and supersede certifications we reissue.
        // An unrelated future or revoked identity must not move this clock.
        let newest = plan
            .recertify
            .iter()
            .filter_map(|identity| policy.user_id_at(identity.index()))
            .filter_map(|identity| identity.newest_conservative_certification_time())
            .chain(signature_creation_time(policy_template).map(u64::from))
            .max();
        let time = next_signature_time(preflight.reference_time, newest)?;
        let secret_signer = OpenPgpSecretSigner::new(
            SecretPacketRef::Primary(&preflight.secret.primary_key),
            &preflight.secret.primary_key as &dyn SigningKey,
        )
        .map_err(|_| Failure::InternalFailure)?;
        let signer = secret_signer.as_ref();
        if let Some(value) = plan.new_identity {
            let config = additional_user_id_certification_config(
                policy_template,
                signer,
                time,
                plan.expected_primary == plan.target,
            )
            .map_err(map_certification_error)?;
            let certification =
                UserIdCertificationBuilder::new(signer, &certificate.primary_key, value, config)
                    .build()
                    .map_err(map_certification_error)?;
            additions.push(CertificateAddition::Identity {
                tag: USER_ID_TAG,
                body: plan.target.clone(),
                signature_bodies: vec![serialize_packet_body(&certification.signature)?],
            });
        }
        for &identity in &plan.recertify {
            let evaluated = policy
                .user_id_at(identity.index())
                .ok_or(Failure::TargetNotFound)?;
            if evaluated.policy_conflict {
                return Err(Failure::PolicyConflict);
            }
            let template = evaluated
                .effective_signature
                .ok_or(Failure::MissingSelfSignature)?;
            let selected = identity.packet_body() == plan.expected_primary;
            let mut config =
                existing_user_id_recertification_config(template, signer, time, selected)
                    .map_err(map_certification_error)?;
            if selected && plan.adopt_key_policy {
                preserve_key_policy(
                    &mut config,
                    policy_template
                        .config()
                        .ok_or(Failure::UnsupportedTemplate)?,
                );
            }
            additions.push(recertify_user_id(
                signer,
                certificate,
                identity.index(),
                config,
            )?);
        }
    }
    drop(policy);
    let mut active = active_before;
    active.push(&plan.target);
    let postconditions = UserIdPostconditions {
        inactive: None,
        active,
        expected_primary: Some(&plan.expected_primary),
        exclusive_primary: plan.exclusive_primary,
        primary_snapshot: Some(before),
        fallback_primary: plan.fallback_primary,
    };
    let mut mutated = Cow::Borrowed(&preflight.packet_set);
    if !additions.is_empty() {
        mutated.to_mut().apply_additions(&additions)?;
    }
    let (output, ()) = preflight.finalize(
        &mutated,
        // Updates use the local public projection. It withholds local
        // certifications without dropping unrelated unusable components.
        false,
        |canonical, after, secrets| {
            postconditions.check(after)?;
            Ok::<_, Failure>(((), certificate_index(after, canonical, secrets)))
        },
    )?;
    Ok(UserIdUpdateSuccess {
        key_material: output.key_material,
        certificate_index: output.certificate_index,
        changed: !plan.already_applied,
    })
}

/// What one update changes, decided once before anything is signed.
struct UpdatePlan<'a> {
    /// Identity that must be active afterwards.
    target: Vec<u8>,
    /// Identity policy must select as primary afterwards.
    expected_primary: Vec<u8>,
    /// New identity to certify; only Add sets it.
    new_identity: Option<&'a str>,
    /// Existing identities to recertify. They also bound the signature time.
    recertify: Vec<ValidatedUserId<'a>>,
    /// Whether the selected primary takes the V4 key policy from the policy template.
    adopt_key_policy: bool,
    /// Whether the target must end up as the only explicit primary.
    exclusive_primary: bool,
    /// Pinned implicit primary and the Revocable flag it must keep.
    fallback_primary: Option<(&'a [u8], bool)>,
    already_applied: bool,
}

fn plan_update<'a>(
    change: &'a UserIdUpdate,
    preflight: &MutationPreflight,
    policy: &ValidatedCertificate<'a>,
) -> Result<UpdatePlan<'a>, Failure> {
    let certificate = policy.certificate();
    match change {
        UserIdUpdate::Add(value) => {
            policy.authorize_user_id_addition()?;
            let target = value.as_bytes();
            let already_applied = policy
                .authenticated_user_ids()
                .any(|identity| identity.packet_body() == target);
            if !already_applied {
                reject_present_user_id(preflight, policy, target)?;
            }
            let primary = policy.primary_user_id();
            // Pin an implicit primary, so the newer identity cannot take over
            // primary selection by timestamp.
            let recertify: Vec<_> = policy
                .authenticated_user_ids()
                .filter(|identity| {
                    primary.is_some_and(|primary| primary.index() == identity.index())
                        && !is_explicit_primary(policy, identity.index())
                })
                .collect();
            let fallback_primary = recertify.first().and_then(|pinned| {
                policy.user_id_at(pinned.index()).map(|identity| {
                    (
                        pinned.packet_body(),
                        identity.effective_certification_revocable,
                    )
                })
            });
            Ok(UpdatePlan {
                target: target.to_vec(),
                expected_primary: primary.map_or(target, |id| id.packet_body()).to_vec(),
                new_identity: Some(value),
                recertify,
                adopt_key_policy: false,
                exclusive_primary: false,
                fallback_primary,
                already_applied,
            })
        }
        UserIdUpdate::MakePrimary(id) => {
            policy.authorize_user_id_mutation()?;
            let index = user_id_index(certificate, id)?;
            policy.authorize_user_id_primary_selection(index)?;
            let target = certificate.details.users[index].id.id();
            Ok(UpdatePlan {
                target: target.to_vec(),
                expected_primary: target.to_vec(),
                new_identity: None,
                // Establish one explicit primary and clear its competitors.
                recertify: policy
                    .authenticated_user_ids()
                    .filter(|identity| {
                        identity.packet_body() == target
                            || is_explicit_primary(policy, identity.index())
                    })
                    .collect(),
                adopt_key_policy: certificate.primary_key.version() == KeyVersion::V4,
                exclusive_primary: true,
                fallback_primary: None,
                already_applied: has_exclusive_primary_user_id(policy, target),
            })
        }
    }
}

#[cfg(test)]
mod tests;
