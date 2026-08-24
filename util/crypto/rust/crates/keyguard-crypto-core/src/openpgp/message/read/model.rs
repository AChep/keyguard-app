//! Protocol-independent inputs and outcomes for OpenPGP read workflows.
//!
//! These values carry parsed certificate metadata and verification decisions
//! without assigning protobuf field or enum numbers.  The protocol adapter is
//! solely responsible for translating them to the native wire contract.

use crate::openpgp::certificate::CertificateIndex;
use zeroize::Zeroizing;

pub(crate) struct PublicKeyParseInput {
    pub(crate) key_data: Zeroizing<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: Option<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum VerifyKind {
    ClearText,
    Detached,
}

pub(crate) struct VerifyInput {
    pub(crate) kind: VerifyKind,
    pub(crate) content: Zeroizing<Vec<u8>>,
    pub(crate) signature: Vec<u8>,
    pub(crate) public_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: Option<u64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct DetachedVerifyInput {
    pub(crate) signature: Vec<u8>,
    pub(crate) public_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: Option<u64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ClearVerifyInput {
    pub(crate) public_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: Option<u64>,
}

pub(crate) struct MetadataResolveInput {
    pub(crate) private_key_data: Option<Zeroizing<Vec<u8>>>,
    pub(crate) public_key_data: Option<Vec<u8>>,
    pub(crate) normalized_fingerprint: String,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: Option<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum PublicKeyParseFailure {
    Empty,
    Malformed,
    UnsupportedKeyVersion,
    MultipleCertificates,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum PublicKeyParseOutcome {
    Success(PublicKeyParseSuccess),
    Failure(PublicKeyParseFailure),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct PublicKeyParseSuccess {
    pub(crate) keys: Vec<PublicKeyInfo>,
    pub(crate) skipped_certificates: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct PublicKeyInfo {
    pub(crate) fingerprint: String,
    pub(crate) keygrip: Option<String>,
    pub(crate) key_id: String,
    pub(crate) algorithm: String,
    pub(crate) bit_strength: Option<u32>,
    pub(crate) user_ids: Vec<String>,
    pub(crate) emails: Vec<String>,
    pub(crate) created_at_epoch_seconds: Option<u64>,
    pub(crate) expires_at_epoch_seconds: Option<u64>,
    pub(crate) revoked: bool,
    pub(crate) can_sign: bool,
    pub(crate) can_encrypt: bool,
    pub(crate) public_key_armored: String,
    pub(crate) subkeys: Vec<PublicSubkeyInfo>,
    pub(crate) user_id_details: Vec<UserIdInfo>,
    pub(crate) component_fingerprints: Vec<String>,
    pub(crate) revocation_authority_fingerprints: Vec<String>,
    pub(crate) authenticated: bool,
    pub(crate) renewal: RenewalCapability,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct PublicSubkeyInfo {
    pub(crate) fingerprint: String,
    pub(crate) keygrip: Option<String>,
    pub(crate) key_id: String,
    pub(crate) algorithm: String,
    pub(crate) bit_strength: Option<u32>,
    pub(crate) can_sign: bool,
    pub(crate) can_encrypt: bool,
    pub(crate) revoked: bool,
    pub(crate) created_at_epoch_seconds: Option<u64>,
    pub(crate) expires_at_epoch_seconds: Option<u64>,
    pub(crate) authenticated: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct UserIdInfo {
    pub(crate) identity_id: String,
    pub(crate) user_id: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum VerificationStatus {
    Valid,
    Invalid,
    MissingPublicKey,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum VerificationWarning {
    KeyRevoked,
    KeyExpired,
    SignatureExpired,
    PolicyConflict,
    WeakDigest,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct Verification {
    pub(crate) status: VerificationStatus,
    pub(crate) key_id: String,
    pub(crate) fingerprint: Option<String>,
    pub(crate) user_ids: Vec<String>,
    pub(crate) created_at_epoch_seconds: Option<u64>,
    pub(crate) warnings: Vec<VerificationWarning>,
    pub(crate) primary_fingerprint: Option<String>,
    pub(crate) primary_user_id: Option<String>,
    pub(crate) signatures: Vec<Verification>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ClearVerificationResult {
    pub(crate) verification: Verification,
    pub(crate) body_valid_utf8: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum PolicyUse {
    SignNewData,
    EncryptNewData,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RenewalCapability {
    Authenticated,
    TemplateOnly,
    None,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ComponentPolicySummary {
    pub(crate) fingerprint: String,
    pub(crate) allowed_new_data_uses: Vec<PolicyUse>,
    pub(crate) renewal: RenewalCapability,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct CertificateResolution {
    pub(crate) index: CertificateIndex,
    pub(crate) policy: Vec<ComponentPolicySummary>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct MetadataResolution {
    pub(crate) evaluated_at_epoch_seconds: u64,
    pub(crate) policy_revision: u32,
    pub(crate) certificates: Vec<CertificateResolution>,
}
