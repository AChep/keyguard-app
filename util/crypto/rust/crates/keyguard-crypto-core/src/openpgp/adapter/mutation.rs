//! Protobuf mapping for certificate mutation workflows.

use crate::{
    openpgp::mutation::{
        self as workflow, CertificateMaterialReconcileInput, ExpirationUpdateFailure,
        ExpirationUpdateInput, MaterialInputError, MaterialPairError, ReconcileError,
        UserIdReplacementFailure, UserIdReplacementInput, UserIdRevocationFailure,
        UserIdRevocationInput,
    },
    primitives::PrimitiveError,
};

use super::{
    certificate::certificate_index,
    key::wire_key_material as key_material,
    wire::{
        Message as _, OpenPgpCertificateMaterialInputErrorReason,
        OpenPgpCertificateMaterialPairErrorReason, OpenPgpCertificateMaterialReconcileError,
        OpenPgpCertificateMaterialReconcileRequest, OpenPgpCertificateMaterialReconcileResult,
        OpenPgpCertificateMaterialReconcileSuccess, OpenPgpExpirationUpdateError,
        OpenPgpExpirationUpdateErrorReason, OpenPgpExpirationUpdateRequest,
        OpenPgpExpirationUpdateResult, OpenPgpExpirationUpdateSuccess,
        OpenPgpUserIdReplacementError, OpenPgpUserIdReplacementErrorReason,
        OpenPgpUserIdReplacementRequest, OpenPgpUserIdReplacementResult,
        OpenPgpUserIdReplacementSuccess, OpenPgpUserIdRevocationError,
        OpenPgpUserIdRevocationErrorReason, OpenPgpUserIdRevocationRequest,
        OpenPgpUserIdRevocationResult, OpenPgpUserIdRevocationSuccess,
        open_pgp_certificate_material_reconcile_result, open_pgp_expiration_update_result,
        open_pgp_user_id_replacement_result, open_pgp_user_id_revocation_result,
    },
};

pub(crate) fn update_expiration(
    mut request: OpenPgpExpirationUpdateRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = ExpirationUpdateInput {
        private_key: std::mem::take(&mut request.private_key),
        public_key: std::mem::take(&mut request.public_key),
        expected_primary_fingerprint: std::mem::take(&mut request.expected_primary_fingerprint),
        component_fingerprints: std::mem::take(&mut request.component_fingerprints),
        expires_at_epoch_seconds: request.expires_at_epoch_seconds,
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
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
                },
            )),
        }
        .encode_to_vec()),
    }
}

pub(crate) fn reconcile_certificate_material(
    mut request: OpenPgpCertificateMaterialReconcileRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = CertificateMaterialReconcileInput {
        expected_primary_fingerprint: std::mem::take(&mut request.expected_primary_fingerprint),
        existing_public_certificate: std::mem::take(&mut request.existing_public_certificate),
        incoming_public_certificate: std::mem::take(&mut request.incoming_public_certificate),
        existing_secret_certificate: std::mem::take(&mut request.existing_secret_certificate),
        incoming_secret_certificate: std::mem::take(&mut request.incoming_secret_certificate),
    };
    match workflow::reconcile_certificate_material_request(input) {
        Ok(mut success) => Ok(OpenPgpCertificateMaterialReconcileResult {
            result: Some(
                open_pgp_certificate_material_reconcile_result::Result::Success(
                    OpenPgpCertificateMaterialReconcileSuccess {
                        public_certificate: std::mem::take(&mut success.public_certificate),
                        private_certificate: std::mem::take(&mut success.private_certificate),
                        primary_fingerprint: std::mem::take(&mut success.primary_fingerprint),
                        existing_public_contributed: success.existing_public_contributed,
                        incoming_public_contributed: success.incoming_public_contributed,
                        existing_secret_contributed: success.existing_secret_contributed,
                        incoming_secret_contributed: success.incoming_secret_contributed,
                    },
                ),
            ),
        }
        .encode_to_vec()),
        Err(ReconcileError::Internal) => Err(PrimitiveError::Internal),
        Err(error) => Ok(OpenPgpCertificateMaterialReconcileResult {
            result: Some(
                open_pgp_certificate_material_reconcile_result::Result::Error(reconcile_error(
                    error,
                )),
            ),
        }
        .encode_to_vec()),
    }
}

pub(crate) fn revoke_user_id(
    mut request: OpenPgpUserIdRevocationRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = UserIdRevocationInput {
        private_key: std::mem::take(&mut request.private_key),
        public_key: std::mem::take(&mut request.public_key),
        expected_primary_fingerprint: std::mem::take(&mut request.expected_primary_fingerprint),
        identity_id: std::mem::take(&mut request.identity_id),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
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
        Err(UserIdRevocationFailure::ResourceLimit) => Err(PrimitiveError::ResourceLimit),
        Err(error) => Ok(OpenPgpUserIdRevocationResult {
            result: Some(open_pgp_user_id_revocation_result::Result::Error(
                OpenPgpUserIdRevocationError {
                    reason: revocation_reason(error) as i32,
                },
            )),
        }
        .encode_to_vec()),
    }
}

pub(crate) fn replace_user_id(
    mut request: OpenPgpUserIdReplacementRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let input = UserIdReplacementInput {
        private_key: std::mem::take(&mut request.private_key),
        public_key: std::mem::take(&mut request.public_key),
        expected_primary_fingerprint: std::mem::take(&mut request.expected_primary_fingerprint),
        old_identity_id: std::mem::take(&mut request.old_identity_id),
        new_user_id: std::mem::take(&mut request.new_user_id),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
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
        Err(UserIdReplacementFailure::ResourceLimit) => Err(PrimitiveError::ResourceLimit),
        Err(error) => Ok(OpenPgpUserIdReplacementResult {
            result: Some(open_pgp_user_id_replacement_result::Result::Error(
                OpenPgpUserIdReplacementError {
                    reason: replacement_reason(error) as i32,
                },
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
        ExpirationUpdateFailure::TimeConflict => OpenPgpExpirationUpdateErrorReason::TimeConflict,
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
    }
}

fn revocation_reason(error: UserIdRevocationFailure) -> OpenPgpUserIdRevocationErrorReason {
    match error {
        UserIdRevocationFailure::EmptyPrivateKey => {
            OpenPgpUserIdRevocationErrorReason::EmptyPrivateKey
        }
        UserIdRevocationFailure::MalformedKey => OpenPgpUserIdRevocationErrorReason::MalformedKey,
        UserIdRevocationFailure::FingerprintMismatch => {
            OpenPgpUserIdRevocationErrorReason::FingerprintMismatch
        }
        UserIdRevocationFailure::TargetNotFound => {
            OpenPgpUserIdRevocationErrorReason::TargetNotFound
        }
        UserIdRevocationFailure::LastUserId => OpenPgpUserIdRevocationErrorReason::LastUserId,
        UserIdRevocationFailure::UnsupportedKeyVersion => {
            OpenPgpUserIdRevocationErrorReason::UnsupportedKeyVersion
        }
        UserIdRevocationFailure::ProtectedSecretKey => {
            OpenPgpUserIdRevocationErrorReason::ProtectedSecretKey
        }
        UserIdRevocationFailure::MissingSelfSignature => {
            OpenPgpUserIdRevocationErrorReason::MissingSelfSignature
        }
        UserIdRevocationFailure::NonRevocable => OpenPgpUserIdRevocationErrorReason::NonRevocable,
        UserIdRevocationFailure::TimeConflict => OpenPgpUserIdRevocationErrorReason::TimeConflict,
        UserIdRevocationFailure::SignatureVerificationFailed => {
            OpenPgpUserIdRevocationErrorReason::SignatureVerificationFailed
        }
        UserIdRevocationFailure::InternalFailure => {
            OpenPgpUserIdRevocationErrorReason::InternalFailure
        }
        UserIdRevocationFailure::CertificateRevoked => {
            OpenPgpUserIdRevocationErrorReason::CertificateRevoked
        }
        UserIdRevocationFailure::UnresolvedRevocationAuthority => {
            OpenPgpUserIdRevocationErrorReason::UnresolvedRevocationAuthority
        }
        UserIdRevocationFailure::UnsupportedSigningHash => {
            OpenPgpUserIdRevocationErrorReason::UnsupportedSigningHash
        }
        UserIdRevocationFailure::ResourceLimit => {
            OpenPgpUserIdRevocationErrorReason::InternalFailure
        }
    }
}

fn replacement_reason(error: UserIdReplacementFailure) -> OpenPgpUserIdReplacementErrorReason {
    match error {
        UserIdReplacementFailure::EmptyPrivateKey => {
            OpenPgpUserIdReplacementErrorReason::EmptyPrivateKey
        }
        UserIdReplacementFailure::MalformedKey => OpenPgpUserIdReplacementErrorReason::MalformedKey,
        UserIdReplacementFailure::FingerprintMismatch => {
            OpenPgpUserIdReplacementErrorReason::FingerprintMismatch
        }
        UserIdReplacementFailure::TargetNotFound => {
            OpenPgpUserIdReplacementErrorReason::TargetNotFound
        }
        UserIdReplacementFailure::TargetInactive => {
            OpenPgpUserIdReplacementErrorReason::TargetInactive
        }
        UserIdReplacementFailure::InvalidNewUserId => {
            OpenPgpUserIdReplacementErrorReason::InvalidNewUserId
        }
        UserIdReplacementFailure::SameIdentity => OpenPgpUserIdReplacementErrorReason::SameIdentity,
        UserIdReplacementFailure::DuplicateIdentity => {
            OpenPgpUserIdReplacementErrorReason::DuplicateIdentity
        }
        UserIdReplacementFailure::PreviouslyRevokedIdentity => {
            OpenPgpUserIdReplacementErrorReason::PreviouslyRevokedIdentity
        }
        UserIdReplacementFailure::UnsupportedKeyVersion => {
            OpenPgpUserIdReplacementErrorReason::UnsupportedKeyVersion
        }
        UserIdReplacementFailure::ProtectedSecretKey => {
            OpenPgpUserIdReplacementErrorReason::ProtectedSecretKey
        }
        UserIdReplacementFailure::MissingSelfSignature => {
            OpenPgpUserIdReplacementErrorReason::MissingSelfSignature
        }
        UserIdReplacementFailure::NonRevocable => OpenPgpUserIdReplacementErrorReason::NonRevocable,
        UserIdReplacementFailure::UnsupportedTemplate => {
            OpenPgpUserIdReplacementErrorReason::UnsupportedTemplate
        }
        UserIdReplacementFailure::TimeConflict => OpenPgpUserIdReplacementErrorReason::TimeConflict,
        UserIdReplacementFailure::SignatureVerificationFailed => {
            OpenPgpUserIdReplacementErrorReason::SignatureVerificationFailed
        }
        UserIdReplacementFailure::InternalFailure => {
            OpenPgpUserIdReplacementErrorReason::InternalFailure
        }
        UserIdReplacementFailure::CertificateRevoked => {
            OpenPgpUserIdReplacementErrorReason::CertificateRevoked
        }
        UserIdReplacementFailure::UnresolvedRevocationAuthority => {
            OpenPgpUserIdReplacementErrorReason::UnresolvedRevocationAuthority
        }
        UserIdReplacementFailure::UnsupportedSigningHash => {
            OpenPgpUserIdReplacementErrorReason::UnsupportedSigningHash
        }
        UserIdReplacementFailure::ResourceLimit => {
            OpenPgpUserIdReplacementErrorReason::InternalFailure
        }
    }
}
