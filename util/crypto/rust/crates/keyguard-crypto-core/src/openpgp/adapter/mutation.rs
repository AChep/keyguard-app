//! Protobuf mapping for certificate mutation workflows.

use crate::{
    openpgp::mutation::{
        self as workflow, CertificateMaterialContributions, CertificateMaterialReconcileInput,
        ExpirationUpdateFailure, ExpirationUpdateInput, MaterialInputContribution,
        MaterialInputError, MaterialPairError, MaterialWithheldReason, ReconcileError,
        UserIdMutationFailure, UserIdReplacementInput, UserIdRevocationInput,
    },
    primitives::PrimitiveError,
};

use super::{
    certificate::certificate_index,
    key::wire_key_material as key_material,
    wire::{
        Message as _, OpenPgpCertificateMaterialContributions,
        OpenPgpCertificateMaterialInputContribution, OpenPgpCertificateMaterialInputErrorReason,
        OpenPgpCertificateMaterialPairErrorReason, OpenPgpCertificateMaterialReconcileError,
        OpenPgpCertificateMaterialReconcileV2Request, OpenPgpCertificateMaterialReconcileV2Result,
        OpenPgpCertificateMaterialReconcileV2Success, OpenPgpCertificateMaterialWithheldReason,
        OpenPgpExpirationUpdateError, OpenPgpExpirationUpdateErrorReason,
        OpenPgpExpirationUpdateRequest, OpenPgpExpirationUpdateResult,
        OpenPgpExpirationUpdateSuccess, OpenPgpUserIdMutationError,
        OpenPgpUserIdMutationErrorReason, OpenPgpUserIdReplacementRequest,
        OpenPgpUserIdReplacementResult, OpenPgpUserIdReplacementSuccess,
        OpenPgpUserIdRevocationRequest, OpenPgpUserIdRevocationResult,
        OpenPgpUserIdRevocationSuccess, open_pgp_certificate_material_reconcile_v2_result,
        open_pgp_expiration_update_result, open_pgp_user_id_replacement_result,
        open_pgp_user_id_revocation_result,
    },
};

/// Moves the documents every certificate mutation request carries out of a wire request.
macro_rules! take_documents {
    ($request:ident) => {
        workflow::MutationDocuments {
            private_key: std::mem::take(&mut $request.private_key),
            public_key: std::mem::take(&mut $request.public_key),
            expected_primary_fingerprint: std::mem::take(
                &mut $request.expected_primary_fingerprint,
            ),
            candidate_revocation_keys: std::mem::take(&mut $request.candidate_revocation_keys),
            reference_time_epoch_seconds: $request.reference_time_epoch_seconds,
        }
    };
}

pub(crate) fn update_expiration(
    mut request: OpenPgpExpirationUpdateRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = ExpirationUpdateInput {
        documents: take_documents!(request),
        component_fingerprints: std::mem::take(&mut request.component_fingerprints),
        expires_at_epoch_seconds: request.expires_at_epoch_seconds,
    };
    match workflow::update_expiration_request(input) {
        Ok(success) => Ok(OpenPgpExpirationUpdateResult {
            result: Some(open_pgp_expiration_update_result::Result::Success(
                OpenPgpExpirationUpdateSuccess {
                    key_material: Some(key_material(success.key_material)),
                    certificate_index: Some(certificate_index(success.certificate_index)),
                },
            )),
        }
        .encode_to_vec()),
        Err(ExpirationUpdateFailure::ResourceLimit) => Err(PrimitiveError::ResourceLimit),
        Err(error) => Ok(OpenPgpExpirationUpdateResult {
            result: Some(open_pgp_expiration_update_result::Result::Error(
                OpenPgpExpirationUpdateError {
                    reason: expiration_reason(error) as i32,
                    retry_at_epoch_seconds: error.retry_at(),
                },
            )),
        }
        .encode_to_vec()),
    }
}

pub(crate) fn reconcile_certificate_material_v2(
    mut request: OpenPgpCertificateMaterialReconcileV2Request,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = CertificateMaterialReconcileInput {
        expected_primary_fingerprint: std::mem::take(&mut request.expected_primary_fingerprint),
        existing_public_certificate: std::mem::take(&mut request.existing_public_certificate),
        incoming_public_certificate: std::mem::take(&mut request.incoming_public_certificate),
        existing_secret_certificate: std::mem::take(&mut request.existing_secret_certificate),
        incoming_secret_certificate: std::mem::take(&mut request.incoming_secret_certificate),
    };
    match workflow::reconcile_certificate_material_request(input) {
        Ok(mut success) => Ok(OpenPgpCertificateMaterialReconcileV2Result {
            result: Some(
                open_pgp_certificate_material_reconcile_v2_result::Result::Success(
                    OpenPgpCertificateMaterialReconcileV2Success {
                        local_public_material: std::mem::take(&mut success.local_public_material),
                        local_secret_material: std::mem::take(&mut success.private_certificate),
                        transferable_public_certificate: std::mem::take(
                            &mut success.transferable_public_certificate,
                        ),
                        transferable_secret_key: std::mem::take(
                            &mut success.transferable_private_certificate,
                        ),
                        primary_fingerprint: std::mem::take(&mut success.primary_fingerprint),
                        contributions: Some(wire_contributions(success.contributions)),
                        withheld_reasons: std::mem::take(&mut success.withheld_reasons)
                            .into_iter()
                            .map(wire_withheld_reason)
                            .map(|reason| reason as i32)
                            .collect(),
                    },
                ),
            ),
        }
        .encode_to_vec()),
        Err(ReconcileError::Internal) => Err(PrimitiveError::Internal),
        Err(error) => Ok(OpenPgpCertificateMaterialReconcileV2Result {
            result: Some(
                open_pgp_certificate_material_reconcile_v2_result::Result::Error(reconcile_error(
                    error,
                )),
            ),
        }
        .encode_to_vec()),
    }
}

fn wire_contributions(
    contributions: CertificateMaterialContributions,
) -> OpenPgpCertificateMaterialContributions {
    OpenPgpCertificateMaterialContributions {
        existing_public: Some(wire_contribution(contributions.existing_public)),
        incoming_public: Some(wire_contribution(contributions.incoming_public)),
        existing_secret: Some(wire_contribution(contributions.existing_secret)),
        incoming_secret: Some(wire_contribution(contributions.incoming_secret)),
    }
}

fn wire_contribution(
    contribution: MaterialInputContribution,
) -> OpenPgpCertificateMaterialInputContribution {
    OpenPgpCertificateMaterialInputContribution {
        present: contribution.present,
        unique_public_evidence: contribution.unique_public_evidence,
        unique_secret_capability: contribution.unique_secret_capability,
    }
}

fn wire_withheld_reason(
    reason: MaterialWithheldReason,
) -> OpenPgpCertificateMaterialWithheldReason {
    match reason {
        MaterialWithheldReason::NoTransferablePublicCertificate => {
            OpenPgpCertificateMaterialWithheldReason::NoTransferablePublicCertificate
        }
        MaterialWithheldReason::LocalPublicEvidence => {
            OpenPgpCertificateMaterialWithheldReason::LocalPublicEvidence
        }
        MaterialWithheldReason::SecretMaterialNotTransferable => {
            OpenPgpCertificateMaterialWithheldReason::SecretMaterialNotTransferable
        }
    }
}

pub(crate) fn revoke_user_id(
    mut request: OpenPgpUserIdRevocationRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = UserIdRevocationInput {
        documents: take_documents!(request),
        identity_id: std::mem::take(&mut request.identity_id),
    };
    match workflow::revoke_user_id_request(input) {
        Ok(success) => Ok(OpenPgpUserIdRevocationResult {
            result: Some(open_pgp_user_id_revocation_result::Result::Success(
                OpenPgpUserIdRevocationSuccess {
                    key_material: Some(key_material(success.key_material)),
                    revocation_certificate_armored: success.revocation_certificate_armored,
                    changed: success.changed,
                    effective_at_epoch_seconds: success.effective_at_epoch_seconds,
                    certificate_index: Some(certificate_index(success.certificate_index)),
                },
            )),
        }
        .encode_to_vec()),
        Err(UserIdMutationFailure::ResourceLimit) => Err(PrimitiveError::ResourceLimit),
        Err(error) => Ok(OpenPgpUserIdRevocationResult {
            result: Some(open_pgp_user_id_revocation_result::Result::Error(
                user_id_error(error),
            )),
        }
        .encode_to_vec()),
    }
}

pub(crate) fn replace_user_id(
    mut request: OpenPgpUserIdReplacementRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = UserIdReplacementInput {
        documents: take_documents!(request),
        old_identity_id: std::mem::take(&mut request.old_identity_id),
        new_user_id: std::mem::take(&mut request.new_user_id),
    };
    match workflow::replace_user_id_request(input) {
        Ok(success) => Ok(OpenPgpUserIdReplacementResult {
            result: Some(open_pgp_user_id_replacement_result::Result::Success(
                OpenPgpUserIdReplacementSuccess {
                    key_material: Some(key_material(success.key_material)),
                    replacement_certificate_armored: success.replacement_certificate_armored,
                    changed: success.changed,
                    effective_at_epoch_seconds: success.effective_at_epoch_seconds,
                    old_identity_id: success.old_identity_id,
                    new_identity_id: success.new_identity_id,
                    primary_user_id: success.primary_user_id,
                    certificate_index: Some(certificate_index(success.certificate_index)),
                },
            )),
        }
        .encode_to_vec()),
        Err(UserIdMutationFailure::ResourceLimit) => Err(PrimitiveError::ResourceLimit),
        Err(error) => Ok(OpenPgpUserIdReplacementResult {
            result: Some(open_pgp_user_id_replacement_result::Result::Error(
                user_id_error(error),
            )),
        }
        .encode_to_vec()),
    }
}

fn expiration_reason(error: ExpirationUpdateFailure) -> OpenPgpExpirationUpdateErrorReason {
    match error {
        ExpirationUpdateFailure::EmptyPrivateKey => {
            OpenPgpExpirationUpdateErrorReason::EmptyPrivateKey
        }
        ExpirationUpdateFailure::MalformedKey => OpenPgpExpirationUpdateErrorReason::MalformedKey,
        ExpirationUpdateFailure::FingerprintMismatch => {
            OpenPgpExpirationUpdateErrorReason::FingerprintMismatch
        }
        ExpirationUpdateFailure::NoComponentsSelected => {
            OpenPgpExpirationUpdateErrorReason::NoComponentsSelected
        }
        ExpirationUpdateFailure::ComponentNotFound => {
            OpenPgpExpirationUpdateErrorReason::ComponentNotFound
        }
        ExpirationUpdateFailure::RevokedComponent => {
            OpenPgpExpirationUpdateErrorReason::RevokedComponent
        }
        ExpirationUpdateFailure::UnresolvedRevocationAuthority => {
            OpenPgpExpirationUpdateErrorReason::UnresolvedRevocationAuthority
        }
        ExpirationUpdateFailure::UnsupportedKeyVersion => {
            OpenPgpExpirationUpdateErrorReason::UnsupportedKeyVersion
        }
        ExpirationUpdateFailure::MissingSecretKey => {
            OpenPgpExpirationUpdateErrorReason::MissingSecretKey
        }
        ExpirationUpdateFailure::ProtectedSecretKey => {
            OpenPgpExpirationUpdateErrorReason::ProtectedSecretKey
        }
        ExpirationUpdateFailure::MissingSelfSignature => {
            OpenPgpExpirationUpdateErrorReason::MissingSelfSignature
        }
        ExpirationUpdateFailure::InvalidExpiration => {
            OpenPgpExpirationUpdateErrorReason::InvalidExpiration
        }
        ExpirationUpdateFailure::TimeConflict | ExpirationUpdateFailure::RetryAt(_) => {
            OpenPgpExpirationUpdateErrorReason::TimeConflict
        }
        ExpirationUpdateFailure::SignatureVerificationFailed => {
            OpenPgpExpirationUpdateErrorReason::SignatureVerificationFailed
        }
        ExpirationUpdateFailure::InternalFailure => {
            OpenPgpExpirationUpdateErrorReason::InternalFailure
        }
        ExpirationUpdateFailure::UnsupportedSigningHash => {
            OpenPgpExpirationUpdateErrorReason::UnsupportedSigningHash
        }
        ExpirationUpdateFailure::ResourceLimit => {
            OpenPgpExpirationUpdateErrorReason::InternalFailure
        }
    }
}

fn reconcile_error(error: ReconcileError) -> OpenPgpCertificateMaterialReconcileError {
    match error {
        ReconcileError::InvalidInputs {
            existing_public,
            incoming_public,
            existing_secret,
            incoming_secret,
        } => OpenPgpCertificateMaterialReconcileError {
            existing_public_input_error: input_reason(existing_public) as i32,
            incoming_public_input_error: input_reason(incoming_public) as i32,
            existing_secret_input_error: input_reason(existing_secret) as i32,
            incoming_secret_input_error: input_reason(incoming_secret) as i32,
            ..Default::default()
        },
        ReconcileError::Pair(error) => OpenPgpCertificateMaterialReconcileError {
            pair_error: pair_reason(error) as i32,
            ..Default::default()
        },
        ReconcileError::Internal => OpenPgpCertificateMaterialReconcileError::default(),
    }
}

fn input_reason(error: Option<MaterialInputError>) -> OpenPgpCertificateMaterialInputErrorReason {
    match error {
        None => OpenPgpCertificateMaterialInputErrorReason::Unspecified,
        Some(MaterialInputError::EmptyCertificate) => {
            OpenPgpCertificateMaterialInputErrorReason::EmptyCertificate
        }
        Some(MaterialInputError::MalformedCertificate) => {
            OpenPgpCertificateMaterialInputErrorReason::MalformedCertificate
        }
        Some(MaterialInputError::UnsupportedKeyVersion) => {
            OpenPgpCertificateMaterialInputErrorReason::UnsupportedKeyVersion
        }
        Some(MaterialInputError::FingerprintMismatch) => {
            OpenPgpCertificateMaterialInputErrorReason::FingerprintMismatch
        }
        Some(MaterialInputError::ComponentCollision) => {
            OpenPgpCertificateMaterialInputErrorReason::ComponentCollision
        }
        Some(MaterialInputError::ResourceLimit) => {
            OpenPgpCertificateMaterialInputErrorReason::ResourceLimit
        }
        Some(MaterialInputError::UnsupportedTskLayout) => {
            OpenPgpCertificateMaterialInputErrorReason::UnsupportedTskLayout
        }
    }
}

fn pair_reason(error: MaterialPairError) -> OpenPgpCertificateMaterialPairErrorReason {
    match error {
        MaterialPairError::MissingMaterial => {
            OpenPgpCertificateMaterialPairErrorReason::MissingMaterial
        }
        MaterialPairError::FingerprintMismatch => {
            OpenPgpCertificateMaterialPairErrorReason::FingerprintMismatch
        }
        MaterialPairError::ComponentCollision => {
            OpenPgpCertificateMaterialPairErrorReason::ComponentCollision
        }
        MaterialPairError::ResourceLimit => {
            OpenPgpCertificateMaterialPairErrorReason::ResourceLimit
        }
        MaterialPairError::InvalidRebuiltOutput => {
            OpenPgpCertificateMaterialPairErrorReason::InvalidRebuiltOutput
        }
        MaterialPairError::ConflictingSecretMaterial => {
            OpenPgpCertificateMaterialPairErrorReason::ConflictingSecretMaterial
        }
    }
}

fn user_id_error(error: UserIdMutationFailure) -> OpenPgpUserIdMutationError {
    OpenPgpUserIdMutationError {
        reason: user_id_reason(error) as i32,
        retry_at_epoch_seconds: error.retry_at(),
    }
}

fn user_id_reason(error: UserIdMutationFailure) -> OpenPgpUserIdMutationErrorReason {
    match error {
        UserIdMutationFailure::EmptyPrivateKey => OpenPgpUserIdMutationErrorReason::EmptyPrivateKey,
        UserIdMutationFailure::MalformedKey => OpenPgpUserIdMutationErrorReason::MalformedKey,
        UserIdMutationFailure::FingerprintMismatch => {
            OpenPgpUserIdMutationErrorReason::FingerprintMismatch
        }
        UserIdMutationFailure::TargetNotFound => OpenPgpUserIdMutationErrorReason::TargetNotFound,
        UserIdMutationFailure::TargetInactive => OpenPgpUserIdMutationErrorReason::TargetInactive,
        UserIdMutationFailure::InvalidNewUserId => {
            OpenPgpUserIdMutationErrorReason::InvalidNewUserId
        }
        UserIdMutationFailure::SameIdentity => OpenPgpUserIdMutationErrorReason::SameIdentity,
        UserIdMutationFailure::DuplicateIdentity => {
            OpenPgpUserIdMutationErrorReason::DuplicateIdentity
        }
        UserIdMutationFailure::PreviouslyRevokedIdentity => {
            OpenPgpUserIdMutationErrorReason::PreviouslyRevokedIdentity
        }
        UserIdMutationFailure::UnsupportedKeyVersion => {
            OpenPgpUserIdMutationErrorReason::UnsupportedKeyVersion
        }
        UserIdMutationFailure::ProtectedSecretKey => {
            OpenPgpUserIdMutationErrorReason::ProtectedSecretKey
        }
        UserIdMutationFailure::MissingSelfSignature => {
            OpenPgpUserIdMutationErrorReason::MissingSelfSignature
        }
        UserIdMutationFailure::NonRevocable => OpenPgpUserIdMutationErrorReason::NonRevocable,
        UserIdMutationFailure::UnsupportedTemplate => {
            OpenPgpUserIdMutationErrorReason::UnsupportedTemplate
        }
        UserIdMutationFailure::PolicyConflict => OpenPgpUserIdMutationErrorReason::PolicyConflict,
        UserIdMutationFailure::LastUserId => OpenPgpUserIdMutationErrorReason::LastUserId,
        UserIdMutationFailure::RetryAt(_) | UserIdMutationFailure::TimeConflict => {
            OpenPgpUserIdMutationErrorReason::TimeConflict
        }
        UserIdMutationFailure::SignatureVerificationFailed => {
            OpenPgpUserIdMutationErrorReason::SignatureVerificationFailed
        }
        UserIdMutationFailure::InternalFailure => OpenPgpUserIdMutationErrorReason::InternalFailure,
        UserIdMutationFailure::CertificateRevoked => {
            OpenPgpUserIdMutationErrorReason::CertificateRevoked
        }
        UserIdMutationFailure::UnresolvedRevocationAuthority => {
            OpenPgpUserIdMutationErrorReason::UnresolvedRevocationAuthority
        }
        UserIdMutationFailure::UnsupportedSigningHash => {
            OpenPgpUserIdMutationErrorReason::UnsupportedSigningHash
        }
        UserIdMutationFailure::ResourceLimit => OpenPgpUserIdMutationErrorReason::InternalFailure,
    }
}

pub(crate) fn update_user_id(
    mut request: super::wire::OpenPgpUserIdUpdateRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    use super::wire::{
        OpenPgpUserIdUpdateResult, OpenPgpUserIdUpdateSuccess,
        open_pgp_user_id_update_request::Change, open_pgp_user_id_update_result::Result as Outcome,
    };
    let change = match request
        .change
        .take()
        .ok_or(PrimitiveError::InvalidArgument)?
    {
        Change::NewUserId(value) => workflow::UserIdUpdate::Add(value),
        Change::PrimaryIdentityId(id) => workflow::UserIdUpdate::MakePrimary(id),
    };
    let input = workflow::UserIdUpdateInput {
        documents: take_documents!(request),
        change,
    };
    let result = match workflow::update_user_id_request(input) {
        Ok(success) => Outcome::Success(OpenPgpUserIdUpdateSuccess {
            key_material: Some(key_material(success.key_material)),
            certificate_index: Some(certificate_index(success.certificate_index)),
            changed: success.changed,
        }),
        Err(UserIdMutationFailure::ResourceLimit) => return Err(PrimitiveError::ResourceLimit),
        Err(error) => Outcome::Error(user_id_error(error)),
    };
    Ok(OpenPgpUserIdUpdateResult {
        result: Some(result),
    }
    .encode_to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn certificate_material_safety_errors_keep_distinct_wire_reasons() {
        assert_eq!(
            input_reason(Some(MaterialInputError::UnsupportedTskLayout)),
            OpenPgpCertificateMaterialInputErrorReason::UnsupportedTskLayout,
        );
        assert_eq!(
            pair_reason(MaterialPairError::ConflictingSecretMaterial),
            OpenPgpCertificateMaterialPairErrorReason::ConflictingSecretMaterial,
        );
    }

    #[test]
    fn replacement_conflicts_keep_distinct_wire_reasons() {
        assert_eq!(
            user_id_reason(UserIdMutationFailure::TimeConflict),
            OpenPgpUserIdMutationErrorReason::TimeConflict,
        );
        assert_eq!(
            user_id_reason(UserIdMutationFailure::PolicyConflict),
            OpenPgpUserIdMutationErrorReason::PolicyConflict,
        );
    }
}
