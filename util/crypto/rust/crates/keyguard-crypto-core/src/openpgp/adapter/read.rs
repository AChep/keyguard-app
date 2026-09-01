//! Protobuf mapping for OpenPGP parsing, metadata, and verification workflows.

use zeroize::Zeroizing;

use crate::{
    openpgp::message::{
        self as workflow, CertificateResolution, CertificationAuthorityInput,
        ClearVerificationResult, ClearVerifyInput, ComponentPolicySummary,
        ComponentRevocationStatus, DetachedVerifyInput, MetadataResolution, MetadataResolveInput,
        PolicyUse, PublicKeyInfo, PublicKeyParseFailure, PublicKeyParseInput,
        PublicKeyParseOutcome, PublicKeyParseSuccess, PublicSubkeyInfo, RenewalCapability,
        UserIdCertificationEvaluateInput, UserIdInfo, Verification, VerificationStatus,
        VerificationWarning, VerifyInput, VerifyKind,
    },
    primitives::PrimitiveError,
};

use super::{
    certificate::certificate_index,
    wire::{
        Message as _, OpenPgpCertificateResolutionV2, OpenPgpClearVerifyResult,
        OpenPgpClearVerifyStreamOpenRequest, OpenPgpComponentPolicyV2,
        OpenPgpDetachedVerifyStreamOpenRequest, OpenPgpMetadataResolutionV2,
        OpenPgpMetadataResolveRequest, OpenPgpMetadataResolveResult, OpenPgpPolicyUse,
        OpenPgpPublicKeyInfo, OpenPgpPublicKeyParseError, OpenPgpPublicKeyParseErrorReason,
        OpenPgpPublicKeyParseRequest, OpenPgpPublicKeyParseResult, OpenPgpPublicKeyParseSuccess,
        OpenPgpPublicSubKeyInfo, OpenPgpRenewalAuthorization, OpenPgpRevocationStatus,
        OpenPgpUserIdCertificationEvaluateRequest, OpenPgpUserIdCertificationEvaluateResult,
        OpenPgpUserIdInfo, OpenPgpVerification, OpenPgpVerificationStatus,
        OpenPgpVerificationWarning, OpenPgpVerifyKind, OpenPgpVerifyRequest,
        open_pgp_public_key_parse_result,
    },
};

pub(crate) fn parse_public_key(
    mut request: OpenPgpPublicKeyParseRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let outcome = workflow::parse_public_key(PublicKeyParseInput {
        key_data: Zeroizing::new(std::mem::take(&mut request.key_data)),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    })
    .map_err(super::read_error)?;
    Ok(public_key_parse_outcome(outcome).encode_to_vec())
}

pub(crate) fn evaluate_user_id_certifications(
    mut request: OpenPgpUserIdCertificationEvaluateRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let result = workflow::evaluate_user_id_certifications(UserIdCertificationEvaluateInput {
        public_key: std::mem::take(&mut request.public_key),
        authorities: std::mem::take(&mut request.authorities)
            .into_iter()
            .map(|mut authority| CertificationAuthorityInput {
                public_key: std::mem::take(&mut authority.public_key),
                primary_fingerprint: std::mem::take(&mut authority.primary_fingerprint),
            })
            .collect(),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    })
    .map_err(super::read_error)?;
    Ok(OpenPgpUserIdCertificationEvaluateResult {
        confirmed_user_ids: result.confirmed_user_ids,
    }
    .encode_to_vec())
}

pub(crate) fn verify(mut request: OpenPgpVerifyRequest) -> Result<Vec<u8>, PrimitiveError> {
    let kind = match OpenPgpVerifyKind::try_from(request.kind) {
        Ok(OpenPgpVerifyKind::ClearText) => VerifyKind::ClearText,
        Ok(OpenPgpVerifyKind::Detached) => VerifyKind::Detached,
        Ok(OpenPgpVerifyKind::Unspecified) | Err(_) => {
            return Err(PrimitiveError::InvalidArgument);
        }
    };
    let result = workflow::verify(VerifyInput {
        kind,
        content: Zeroizing::new(std::mem::take(&mut request.content)),
        signature: std::mem::take(&mut request.signature),
        public_keys: std::mem::take(&mut request.public_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    })
    .map_err(super::read_error)?;
    Ok(verification(result).encode_to_vec())
}

pub(crate) fn resolve_metadata(
    mut request: OpenPgpMetadataResolveRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    let resolution = workflow::resolve_metadata(MetadataResolveInput {
        private_key_data: request.private_key_data.take().map(Zeroizing::new),
        public_key_data: request.public_key_data.take(),
        normalized_fingerprint: std::mem::take(&mut request.normalized_fingerprint),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    })
    .map_err(super::read_error)?;
    Ok(OpenPgpMetadataResolveResult {
        resolution: resolution.map(metadata_resolution),
    }
    .encode_to_vec())
}

pub(super) fn detached_verify_input(
    mut request: OpenPgpDetachedVerifyStreamOpenRequest,
) -> DetachedVerifyInput {
    DetachedVerifyInput {
        signature: std::mem::take(&mut request.signature),
        public_keys: std::mem::take(&mut request.public_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    }
}

pub(super) fn clear_verify_input(
    mut request: OpenPgpClearVerifyStreamOpenRequest,
) -> ClearVerifyInput {
    ClearVerifyInput {
        public_keys: std::mem::take(&mut request.public_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
    }
}

pub(super) fn encode_verification(result: Verification) -> Vec<u8> {
    verification(result).encode_to_vec()
}

pub(super) fn encode_clear_verification(result: ClearVerificationResult) -> Vec<u8> {
    OpenPgpClearVerifyResult {
        verification: Some(verification(result.verification)),
        body_valid_utf8: result.body_valid_utf8,
    }
    .encode_to_vec()
}

pub(super) fn verification(result: Verification) -> OpenPgpVerification {
    OpenPgpVerification {
        status: verification_status(result.status) as i32,
        key_id: result.key_id,
        fingerprint: result.fingerprint,
        user_ids: result.user_ids,
        created_at_epoch_seconds: result.created_at_epoch_seconds,
        warnings: result
            .warnings
            .into_iter()
            .map(|warning| verification_warning(warning) as i32)
            .collect(),
        primary_fingerprint: result.primary_fingerprint,
        primary_user_id: result.primary_user_id,
        signatures: result.signatures.into_iter().map(verification).collect(),
    }
}

fn public_key_parse_outcome(outcome: PublicKeyParseOutcome) -> OpenPgpPublicKeyParseResult {
    let result = match outcome {
        PublicKeyParseOutcome::Success(success) => {
            open_pgp_public_key_parse_result::Result::Success(public_key_parse_success(success))
        }
        PublicKeyParseOutcome::Failure(reason) => {
            open_pgp_public_key_parse_result::Result::Error(OpenPgpPublicKeyParseError {
                reason: public_key_parse_failure(reason) as i32,
            })
        }
    };
    OpenPgpPublicKeyParseResult {
        result: Some(result),
    }
}

fn public_key_parse_success(success: PublicKeyParseSuccess) -> OpenPgpPublicKeyParseSuccess {
    OpenPgpPublicKeyParseSuccess {
        keys: success.keys.into_iter().map(public_key_info).collect(),
        skipped_certificates: success.skipped_certificates,
    }
}

fn public_key_info(info: PublicKeyInfo) -> OpenPgpPublicKeyInfo {
    OpenPgpPublicKeyInfo {
        fingerprint: info.fingerprint,
        keygrip: info.keygrip,
        key_id: info.key_id,
        algorithm: info.algorithm,
        bit_strength: info.bit_strength,
        user_ids: info.user_ids,
        emails: info.emails,
        created_at_epoch_seconds: info.created_at_epoch_seconds,
        expires_at_epoch_seconds: info.expires_at_epoch_seconds,
        revoked: info.revoked,
        can_sign: info.can_sign,
        can_encrypt: info.can_encrypt,
        public_key_armored: info.public_key_armored,
        subkeys: info.subkeys.into_iter().map(public_subkey_info).collect(),
        user_id_details: info.user_id_details.into_iter().map(user_id_info).collect(),
        component_fingerprints: info.component_fingerprints,
        revocation_authority_fingerprints: info.revocation_authority_fingerprints,
        authenticated: info.authenticated,
        renewal: renewal_capability(info.renewal) as i32,
    }
}

fn public_subkey_info(info: PublicSubkeyInfo) -> OpenPgpPublicSubKeyInfo {
    OpenPgpPublicSubKeyInfo {
        fingerprint: info.fingerprint,
        keygrip: info.keygrip,
        key_id: info.key_id,
        algorithm: info.algorithm,
        bit_strength: info.bit_strength,
        can_sign: info.can_sign,
        can_encrypt: info.can_encrypt,
        revoked: info.revoked,
        created_at_epoch_seconds: info.created_at_epoch_seconds,
        expires_at_epoch_seconds: info.expires_at_epoch_seconds,
        authenticated: info.authenticated,
    }
}

fn user_id_info(info: UserIdInfo) -> OpenPgpUserIdInfo {
    OpenPgpUserIdInfo {
        identity_id: info.identity_id,
        user_id: info.user_id,
    }
}

fn metadata_resolution(resolution: MetadataResolution) -> OpenPgpMetadataResolutionV2 {
    OpenPgpMetadataResolutionV2 {
        evaluated_at_epoch_seconds: resolution.evaluated_at_epoch_seconds,
        policy_revision: resolution.policy_revision,
        certificates: resolution
            .certificates
            .into_iter()
            .map(certificate_resolution)
            .collect(),
    }
}

fn certificate_resolution(resolution: CertificateResolution) -> OpenPgpCertificateResolutionV2 {
    OpenPgpCertificateResolutionV2 {
        index: Some(certificate_index(resolution.index)),
        policy: resolution
            .policy
            .into_iter()
            .map(component_policy)
            .collect(),
    }
}

fn component_policy(policy: ComponentPolicySummary) -> OpenPgpComponentPolicyV2 {
    OpenPgpComponentPolicyV2 {
        fingerprint: policy.fingerprint,
        allowed_new_data_uses: policy
            .allowed_new_data_uses
            .into_iter()
            .map(|value| policy_use(value) as i32)
            .collect(),
        renewal: renewal_capability(policy.renewal) as i32,
        revocation_status: match policy.revocation_status {
            ComponentRevocationStatus::NotRevoked => OpenPgpRevocationStatus::NotRevoked,
            ComponentRevocationStatus::Revoked => OpenPgpRevocationStatus::Revoked,
            ComponentRevocationStatus::Indeterminate => OpenPgpRevocationStatus::Indeterminate,
        } as i32,
    }
}

fn public_key_parse_failure(reason: PublicKeyParseFailure) -> OpenPgpPublicKeyParseErrorReason {
    match reason {
        PublicKeyParseFailure::Empty => OpenPgpPublicKeyParseErrorReason::Empty,
        PublicKeyParseFailure::Malformed => OpenPgpPublicKeyParseErrorReason::Malformed,
        PublicKeyParseFailure::UnsupportedKeyVersion => {
            OpenPgpPublicKeyParseErrorReason::UnsupportedKeyVersion
        }
        PublicKeyParseFailure::MultipleCertificates => {
            OpenPgpPublicKeyParseErrorReason::MultipleCertificates
        }
    }
}

fn verification_status(status: VerificationStatus) -> OpenPgpVerificationStatus {
    match status {
        VerificationStatus::Valid => OpenPgpVerificationStatus::Valid,
        VerificationStatus::Invalid => OpenPgpVerificationStatus::Invalid,
        VerificationStatus::MissingPublicKey => OpenPgpVerificationStatus::MissingPublicKey,
    }
}

fn verification_warning(warning: VerificationWarning) -> OpenPgpVerificationWarning {
    match warning {
        VerificationWarning::KeyRevoked => OpenPgpVerificationWarning::KeyRevoked,
        VerificationWarning::KeyExpired => OpenPgpVerificationWarning::KeyExpired,
        VerificationWarning::SignatureExpired => OpenPgpVerificationWarning::SignatureExpired,
        VerificationWarning::PolicyConflict => OpenPgpVerificationWarning::PolicyConflict,
        VerificationWarning::WeakDigest => OpenPgpVerificationWarning::WeakDigest,
    }
}

fn policy_use(value: PolicyUse) -> OpenPgpPolicyUse {
    match value {
        PolicyUse::SignNewData => OpenPgpPolicyUse::SignNewData,
        PolicyUse::EncryptNewData => OpenPgpPolicyUse::EncryptNewData,
    }
}

fn renewal_capability(value: RenewalCapability) -> OpenPgpRenewalAuthorization {
    match value {
        RenewalCapability::Authenticated => OpenPgpRenewalAuthorization::Authenticated,
        RenewalCapability::TemplateOnly => OpenPgpRenewalAuthorization::TemplateOnly,
        RenewalCapability::None => OpenPgpRenewalAuthorization::None,
    }
}
