//! Signed OpenPGP User ID revocation.
//!
//! The revocation is expressed as one signature packet added to the shared
//! certificate packet set. The same addition builds the minimal transferable
//! artifact the caller may publish, so the stored certificate and the
//! distributed one can never carry different statements.

use pgp::{armor::BlockType, types::SigningKey};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, CertificateSignatureOwner, KeyMaterial,
        PublicCertificatePacketSet, UserIdRevocationBuilder, armor_key_packets,
        serialize_packet_body,
    },
    crypto::{
        secret::{OpenPgpSecretSigner, SecretPacketRef},
        verification::signature_config_is_non_exportable,
    },
    mutation::{MutationDocuments, MutationPreflight},
    packet::USER_ID_TAG,
    policy::{UserIdRevocationPlan, ValidatedCertificate, certificate_index},
};

pub(crate) struct UserIdRevocationInput {
    pub(crate) documents: MutationDocuments,
    pub(crate) identity_id: String,
}

pub(crate) struct UserIdRevocationSuccess {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
    pub(crate) revocation_certificate_armored: Vec<u8>,
    pub(crate) changed: bool,
    pub(crate) effective_at_epoch_seconds: u64,
}

use super::{UserIdMutationFailure, map_certification_error, user_id_index};

pub(crate) fn revoke_user_id_request(
    mut request: UserIdRevocationInput,
) -> Result<UserIdRevocationSuccess, UserIdMutationFailure> {
    request.documents.check()?;
    if request.identity_id.is_empty() {
        return Err(UserIdMutationFailure::TargetNotFound);
    }

    let preflight = request.documents.open()?;
    let certificate = &preflight.canonical.semantic;
    let index = user_id_index(certificate, &request.identity_id)?;
    let user = &certificate.details.users[index];
    // A User ID packet body is exactly its identifier.
    let target_body = user.id.id().to_vec();

    let policy = preflight.policy()?;
    let plan = policy.plan_user_id_revocation(index)?;
    let certification_template = plan.template();
    let mutation_is_local = certification_template
        .config()
        .is_some_and(signature_config_is_non_exportable);
    let revocation_time = match plan {
        UserIdRevocationPlan::AlreadyRevoked { effective_at, .. } => {
            drop(policy);
            return finish_success(
                &preflight,
                &preflight.packet_set,
                None,
                Vec::new(),
                mutation_is_local,
                false,
                effective_at,
            );
        }
        UserIdRevocationPlan::Sign { at, .. } => at,
    };
    let effective_at = u64::from(revocation_time.as_secs());
    if preflight.secret.primary_key.secret_params().is_encrypted() {
        return Err(UserIdMutationFailure::ProtectedSecretKey);
    }

    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&preflight.secret.primary_key),
        &preflight.secret.primary_key as &dyn SigningKey,
    )
    .map_err(|_| UserIdMutationFailure::InternalFailure)?;
    let signer = signer.as_ref();
    let revocation = UserIdRevocationBuilder::new(
        signer,
        &certificate.primary_key,
        &user.id,
        certification_template,
        revocation_time,
    )
    .build()
    .map_err(map_certification_error)?;
    drop(policy);

    let additions = vec![CertificateAddition::Signature {
        owner: CertificateSignatureOwner::Identity {
            tag: USER_ID_TAG,
            body: target_body.clone(),
        },
        body: serialize_packet_body(&revocation)?,
    }];
    // Exportable Certification does not apply to Certification Revocations,
    // so generic certificate export correctly ignores the copied type-4
    // subpacket. The operation still inherits the source certification's
    // privacy contract and must not publish an artifact for a local identity.
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
        Some(target_body),
        artifact_armored,
        mutation_is_local,
        true,
        effective_at,
    )
}

fn finish_success(
    preflight: &MutationPreflight,
    mutated: &PublicCertificatePacketSet,
    revoked_target_body: Option<Vec<u8>>,
    revocation_certificate_armored: Vec<u8>,
    local_only_mutation: bool,
    changed: bool,
    effective_at_epoch_seconds: u64,
) -> Result<UserIdRevocationSuccess, UserIdMutationFailure> {
    let (output, ()) = preflight.finalize(
        mutated,
        local_only_mutation,
        |canonical, policy: &ValidatedCertificate<'_>, secret_fingerprints| {
            // Fail closed: the revocation must be effective in the certificate
            // handed back, evaluated by the same policy that will read it.
            policy.primary_component().authorize_mutation()?;
            if let Some(body) = revoked_target_body.as_deref()
                && !policy
                    .user_id(body)
                    .is_some_and(|identity| identity.revocation_status.is_revoked())
            {
                return Err(UserIdMutationFailure::SignatureVerificationFailed);
            }
            Ok::<_, UserIdMutationFailure>((
                (),
                certificate_index(policy, canonical, secret_fingerprints),
            ))
        },
    )?;
    Ok(UserIdRevocationSuccess {
        key_material: output.key_material,
        certificate_index: output.certificate_index,
        revocation_certificate_armored,
        changed,
        effective_at_epoch_seconds,
    })
}

#[cfg(test)]
mod tests;
