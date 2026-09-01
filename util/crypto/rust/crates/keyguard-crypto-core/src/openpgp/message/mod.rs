//! OpenPGP message parsing, signing, verification, encryption, and decryption.
//!
//! One-shot operations and streaming sessions share the same engines so that
//! chunking never changes packet bytes, policy decisions, or error classes.

mod read;
pub(super) mod write;

pub(crate) use read::{
    CertificateResolution, CertificationAuthorityInput, ClearVerificationResult,
    ClearVerificationSession, ClearVerifyInput, ComponentPolicySummary, ComponentRevocationStatus,
    DetachedVerificationSession, DetachedVerifyInput, MetadataResolution, MetadataResolveInput,
    OpenPgpReadError, PolicyUse, PublicKeyInfo, PublicKeyParseFailure, PublicKeyParseInput,
    PublicKeyParseOutcome, PublicKeyParseSuccess, PublicSubkeyInfo, RenewalCapability,
    UserIdCertificationEvaluateInput, UserIdInfo, Verification, VerificationStatus,
    VerificationWarning, VerifyInput, VerifyKind, evaluate_user_id_certifications,
    parse_public_key, resolve_metadata, verify,
};
pub(in crate::openpgp) use write::{
    ClearSignInput, DecryptInput, DecryptStreamInput, DecryptionResult, DecryptionWarning,
    DetachedSignInput, EncryptInput, EncryptStreamInput, EncryptionResult, LiteralMetadata,
    ProtectionMode, SignInput, SignKind, decrypt_request, encrypt_request, sign_request,
};
pub(crate) use write::{
    ClearSigningSession, DetachedSigningSession, OpenPgpDecryptionSession, OpenPgpEncryptionSession,
};

use read::{
    DataSignatureVerificationTime, OpenPgpReadBudget,
    evaluate_preverified_signatures_with_recipients, parse_public_key_documents,
};
