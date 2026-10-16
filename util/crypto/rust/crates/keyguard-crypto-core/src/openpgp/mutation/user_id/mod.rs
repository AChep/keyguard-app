//! Shared contracts and invariants for signed User ID mutations.

mod replace;
mod revoke;
mod update;

pub(crate) use replace::{UserIdReplacementInput, replace_user_id_request};
pub(crate) use revoke::{UserIdRevocationInput, revoke_user_id_request};
pub(crate) use update::{UserIdUpdate, UserIdUpdateInput, update_user_id_request};

use super::{MutationPreflight, impl_mutation_failure_conversions};
use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateSignatureOwner, UserIdCertificationBuilder,
        UserIdCertificationError, identity_id, serialize_packet_body,
    },
    crypto::signer::SigningKeyRef,
    packet::USER_ID_TAG,
    policy::{ValidatedCertificate, signature_is_primary, signature_is_revocable},
};
use pgp::{
    composed::SignedPublicKey,
    packet::{KeyFlags, Signature, SignatureConfig},
    types::{KeyDetails, KeyVersion},
};

const MAX_USER_ID_BYTES: usize = 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum UserIdMutationFailure {
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
    RetryAt(u64),
    LastUserId,
    SignatureVerificationFailed,
    InternalFailure,
    CertificateRevoked,
    UnresolvedRevocationAuthority,
    UnsupportedSigningHash,
    ResourceLimit,
}

impl_mutation_failure_conversions!(UserIdMutationFailure, revoked => CertificateRevoked);

/// Primary-key policy fields identity additions, replacements and primary selection preserve.
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

/// Post-conditions a finalized User ID addition, replacement or primary
/// selection must satisfy.
#[derive(Default)]
struct UserIdPostconditions<'a> {
    /// Identity that must no longer be active.
    inactive: Option<&'a [u8]>,
    /// Identities that must be active.
    active: Vec<&'a [u8]>,
    /// Identity policy must select as primary.
    expected_primary: Option<&'a [u8]>,
    /// Whether `expected_primary` must be the only explicit primary.
    exclusive_primary: bool,
    primary_snapshot: Option<PrimaryKeyPolicySnapshot>,
    /// Pinned fallback primary body and the Revocable flag it must keep.
    fallback_primary: Option<(&'a [u8], bool)>,
}

impl UserIdPostconditions<'_> {
    fn check(&self, after: &ValidatedCertificate<'_>) -> Result<(), UserIdMutationFailure> {
        after.authorize_user_id_mutation()?;
        let is_active = |body: &[u8]| {
            after
                .authenticated_user_ids()
                .any(|identity| identity.packet_body() == body)
        };
        let satisfied = !self.inactive.is_some_and(is_active)
            && self.active.iter().all(|body| is_active(body))
            && self.expected_primary.is_none_or(|expected| {
                after.primary_user_id().map(|id| id.packet_body()) == Some(expected)
                    && (!self.exclusive_primary || has_exclusive_primary_user_id(after, expected))
            })
            && self
                .primary_snapshot
                .as_ref()
                .is_none_or(|snapshot| PrimaryKeyPolicySnapshot::capture(after) == *snapshot)
            && self.fallback_primary.is_none_or(|(body, revocable)| {
                after
                    .user_id(body)
                    .and_then(|identity| identity.effective_signature)
                    .is_some_and(|signature| signature_is_revocable(signature) == revocable)
            });
        if satisfied {
            Ok(())
        } else {
            Err(UserIdMutationFailure::SignatureVerificationFailed)
        }
    }
}

/// A repeated selection is a no-op only after competing primary markers are cleared.
fn has_exclusive_primary_user_id(policy: &ValidatedCertificate<'_>, target: &[u8]) -> bool {
    policy.authenticated_user_ids().all(|identity| {
        is_explicit_primary(policy, identity.index()) == (identity.packet_body() == target)
    })
}

/// Whether the identity's effective certification carries an explicit primary marker.
fn is_explicit_primary(policy: &ValidatedCertificate<'_>, index: usize) -> bool {
    policy
        .user_id_at(index)
        .and_then(|identity| identity.effective_signature)
        .is_some_and(signature_is_primary)
}

/// Reissues the certification of the User ID at `index` with `config`.
fn recertify_user_id(
    signer: SigningKeyRef<'_>,
    certificate: &SignedPublicKey,
    index: usize,
    config: SignatureConfig,
) -> Result<CertificateAddition, UserIdMutationFailure> {
    let user_id = &certificate.details.users[index].id;
    let signature =
        UserIdCertificationBuilder::for_existing(signer, &certificate.primary_key, user_id, config)
            .build()
            .map_err(map_certification_error)?
            .signature;
    Ok(CertificateAddition::Signature {
        owner: CertificateSignatureOwner::Identity {
            tag: USER_ID_TAG,
            // A User ID packet body is exactly its identifier.
            body: user_id.id().to_vec(),
        },
        body: serialize_packet_body(&signature)?,
    })
}

/// Accepts non-blank, single-line text that fits one User ID packet.
fn is_valid_new_user_id(value: &str) -> bool {
    !value.chars().all(char::is_whitespace)
        && value.len() <= MAX_USER_ID_BYTES
        && !value
            .chars()
            .any(|character| character.is_control() || matches!(character, '\u{2028}' | '\u{2029}'))
}

/// Returns the signature whose key policy a new User ID certification follows:
/// the Direct Key signature on V6, the primary User ID certification on V4.
fn user_id_policy_template<'a>(policy: &ValidatedCertificate<'a>) -> Option<&'a Signature> {
    if policy.certificate().primary_key.version() == KeyVersion::V6 {
        policy.primary.effective_signature
    } else {
        policy
            .primary_user_id()
            .and_then(|id| policy.user_id_at(id.index()))
            .and_then(|identity| identity.effective_signature)
    }
}

/// Returns the index of the User ID packet that `id` addresses.
fn user_id_index(certificate: &SignedPublicKey, id: &str) -> Result<usize, UserIdMutationFailure> {
    certificate
        .details
        .users
        .iter()
        .position(|user| identity_id(USER_ID_TAG, user.id.id()) == id)
        .ok_or(UserIdMutationFailure::TargetNotFound)
}

/// Rejects a new User ID whose packet is already present, even without a live
/// certification. The packet set is asked directly, because the composed view
/// omits a signature-less identity entirely.
fn reject_present_user_id(
    preflight: &MutationPreflight,
    policy: &ValidatedCertificate<'_>,
    body: &[u8],
) -> Result<(), UserIdMutationFailure> {
    if !preflight.packet_set.has_identity(USER_ID_TAG, body) {
        return Ok(());
    }
    Err(
        if policy
            .user_id(body)
            .is_some_and(|identity| identity.revocation_status.is_revoked())
        {
            UserIdMutationFailure::PreviouslyRevokedIdentity
        } else {
            UserIdMutationFailure::DuplicateIdentity
        },
    )
}

const fn map_certification_error(error: UserIdCertificationError) -> UserIdMutationFailure {
    match error {
        UserIdCertificationError::InvalidUserId => UserIdMutationFailure::InvalidNewUserId,
        UserIdCertificationError::UnsupportedTemplate => UserIdMutationFailure::UnsupportedTemplate,
        UserIdCertificationError::UnsupportedSigningHash => {
            UserIdMutationFailure::UnsupportedSigningHash
        }
        UserIdCertificationError::InvalidSignatureType
        | UserIdCertificationError::SigningFailed => UserIdMutationFailure::InternalFailure,
    }
}

impl From<crate::openpgp::policy::UserIdAuthorizationError> for UserIdMutationFailure {
    fn from(error: crate::openpgp::policy::UserIdAuthorizationError) -> Self {
        use crate::openpgp::policy::UserIdAuthorizationError as Error;
        match error {
            Error::Primary(error) => Self::from(error),
            Error::PolicyConflict => Self::PolicyConflict,
            Error::TargetNotFound => Self::TargetNotFound,
            Error::TargetInactive => Self::TargetInactive,
            Error::MissingSelfSignature => Self::MissingSelfSignature,
            Error::NonRevocable => Self::NonRevocable,
            Error::LastUserId => Self::LastUserId,
            Error::UnresolvedRevocationAuthority => Self::UnresolvedRevocationAuthority,
            Error::Time(error) => Self::from(error),
        }
    }
}
