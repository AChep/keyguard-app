//! Signed OpenPGP User ID revocation.
//!
//! The revocation is expressed as one signature packet added to the shared
//! certificate packet set. The same addition builds the minimal transferable
//! artifact the caller may publish, so the stored certificate and the
//! distributed one can never carry different statements.

use pgp::{armor::BlockType, types::SigningKey};
use zeroize::{Zeroize, Zeroizing};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, CertificateSignatureOwner, KeyMaterial,
        PublicCertificatePacketSet, UserIdRevocationBuilder, armor_key_packets, identity_id,
        serialize_packet_body,
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
    policy::{ValidatedCertificate, certificate_index},
};

pub(crate) struct UserIdRevocationInput {
    pub(crate) private_key: Vec<u8>,
    pub(crate) public_key: Vec<u8>,
    pub(crate) expected_primary_fingerprint: String,
    pub(crate) identity_id: String,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: u64,
}

impl Drop for UserIdRevocationInput {
    fn drop(&mut self) {
        self.private_key.zeroize();
    }
}

pub(crate) struct UserIdRevocationSuccess {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
    pub(crate) revocation_certificate_armored: Vec<u8>,
    pub(crate) changed: bool,
    pub(crate) effective_at_epoch_seconds: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum UserIdRevocationFailure {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    TargetNotFound,
    LastUserId,
    UnsupportedKeyVersion,
    ProtectedSecretKey,
    MissingSelfSignature,
    NonRevocable,
    TimeConflict,
    SignatureVerificationFailed,
    InternalFailure,
    CertificateRevoked,
    UnresolvedRevocationAuthority,
    UnsupportedSigningHash,
    ResourceLimit,
}

impl_mutation_failure_conversions!(UserIdRevocationFailure, revoked => CertificateRevoked);

pub(crate) fn revoke_user_id_request(
    mut request: UserIdRevocationInput,
) -> Result<UserIdRevocationSuccess, UserIdRevocationFailure> {
    if request.private_key.iter().all(u8::is_ascii_whitespace) {
        return Err(UserIdRevocationFailure::EmptyPrivateKey);
    }
    validate_mutation_document_bounds(
        &request.private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
    )?;
    if request.identity_id.is_empty() {
        return Err(UserIdRevocationFailure::TargetNotFound);
    }

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let preflight = MutationPreflight::open(
        &private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
        &request.expected_primary_fingerprint,
        request.reference_time_epoch_seconds,
    )?;
    let certificate = &preflight.canonical.semantic;
    let index = certificate
        .details
        .users
        .iter()
        .position(|user| identity_id(USER_ID_TAG, user.id.id()) == request.identity_id)
        .ok_or(UserIdRevocationFailure::TargetNotFound)?;
    let user = &certificate.details.users[index];
    // A User ID packet body is exactly its identifier.
    let target_body = user.id.id().to_vec();

    let policy = preflight.policy()?;
    policy.primary_component().authorize_mutation()?;
    let identity = policy
        .user_id_at(index)
        .ok_or(UserIdRevocationFailure::TargetNotFound)?;
    let certification_template = identity
        .revocation_exportability_template()
        .ok_or(UserIdRevocationFailure::MissingSelfSignature)?;
    let mutation_is_local = certification_template
        .config()
        .is_some_and(signature_config_is_non_exportable);
    // Idempotence is checked before the clock and before acquiring a signer.
    let already_effective_revocation_at = identity
        .revocation_status
        .is_revoked()
        .then_some(identity.effective_revocation_at)
        .flatten();
    if let Some(effective_at) = already_effective_revocation_at {
        drop(policy);
        return finish_success(
            &preflight,
            &preflight.packet_set,
            request.reference_time_epoch_seconds,
            None,
            Vec::new(),
            mutation_is_local,
            false,
            effective_at,
        );
    }

    // A certification policy rejects still counts here: a peer that accepts it
    // must see this revocation as superseding it, and an identity another
    // implementation shows as live must not become unrevocable.
    let newest_certification_time = identity
        .newest_conservative_certification_time()
        .ok_or(UserIdRevocationFailure::MissingSelfSignature)?;
    let revocation_time = next_signature_time(
        request.reference_time_epoch_seconds,
        Some(newest_certification_time),
    )
    .ok_or(UserIdRevocationFailure::TimeConflict)?;
    let effective_at = u64::from(revocation_time.as_secs());
    // The new revocation would be dated after every existing statement, so
    // idempotence is decided at that instant: a revocation already scheduled
    // for it is this operation's result, not something to duplicate.
    let scheduled_revocation_at = identity.self_revoked_at(effective_at);
    if !identity.live_certifications_are_revocable(effective_at) {
        return Err(UserIdRevocationFailure::NonRevocable);
    }
    if let Some(effective_at) = scheduled_revocation_at {
        drop(policy);
        return finish_success(
            &preflight,
            &preflight.packet_set,
            request.reference_time_epoch_seconds,
            None,
            Vec::new(),
            mutation_is_local,
            false,
            effective_at,
        );
    }
    // Keyguard keeps at least one authenticated textual identity on a V4
    // certificate. User Attributes are not interchangeable with User IDs for
    // identity lookup and therefore do not satisfy this invariant.
    if identity.authenticated() && !policy.has_authenticated_user_id_other_than(&target_body) {
        return Err(UserIdRevocationFailure::LastUserId);
    }
    if preflight.secret.primary_key.secret_params().is_encrypted() {
        return Err(UserIdRevocationFailure::ProtectedSecretKey);
    }

    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&preflight.secret.primary_key),
        &preflight.secret.primary_key as &dyn SigningKey,
    )
    .map_err(|_| UserIdRevocationFailure::InternalFailure)?;
    let signer = signer.as_ref();
    let revocation = UserIdRevocationBuilder::new(
        signer,
        &certificate.primary_key,
        &user.id,
        certification_template,
        revocation_time,
    )
    .build()
    .map_err(|error| match error {
        crate::openpgp::certificate::UserIdCertificationError::UnsupportedSigningHash => {
            UserIdRevocationFailure::UnsupportedSigningHash
        }
        _ => UserIdRevocationFailure::InternalFailure,
    })?;
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
        Some(target_body),
        artifact_armored,
        mutation_is_local,
        true,
        effective_at,
    )
}

#[allow(clippy::too_many_arguments)]
fn finish_success(
    preflight: &MutationPreflight,
    mutated: &PublicCertificatePacketSet,
    mutation_time: u64,
    revoked_target_body: Option<Vec<u8>>,
    revocation_certificate_armored: Vec<u8>,
    local_only_mutation: bool,
    changed: bool,
    effective_at_epoch_seconds: u64,
) -> Result<UserIdRevocationSuccess, UserIdRevocationFailure> {
    let (output, ()) = preflight.finalize(
        mutated,
        mutation_time,
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
                return Err(UserIdRevocationFailure::SignatureVerificationFailed);
            }
            Ok::<_, UserIdRevocationFailure>((
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
