//! OpenPGP certificate authentication and effective-policy evaluation.
//!
//! Certificate bytes remain lossless in the certificate layer. This layer
//! derives policy- and time-qualified views without rewriting that evidence.

mod acceptance;
mod budget;
mod evaluation;
mod index;
mod model;
mod revocation;
mod selection;

#[cfg(test)]
mod tests;

pub(in crate::openpgp) use crate::openpgp::{
    certificate::PublicComponent,
    crypto::verification::{
        SignatureIssuerMetadata, key_signature_verification_acceptable, signature_creation_time,
        signature_expiration_seconds,
    },
};
pub(in crate::openpgp) use acceptance::{
    authentication_signature_acceptable, data_signature_acceptable, is_legacy_weak_hash,
    signature_expired, signature_issuer_consistent,
};
pub(in crate::openpgp) use budget::{DesignatedRevokerId, OpenPgpPolicyBudget, OpenPgpPolicyError};
pub(in crate::openpgp) use evaluation::{
    all_components, certificate_components, revocation_key_id, validate_certificate,
    validate_certificate_with_policy_time,
};
pub(in crate::openpgp) use index::certificate_index;
pub(in crate::openpgp) use model::{
    AuthenticatedFeatures, ComponentPolicy, EncryptionModePreferences, EvaluatedComponent,
    IdentityPolicy, MutationAuthorizationError, PolicyContext, PolicyInactiveTemplate,
    PolicySelection, RenewalAuthorization, RevocationStatus, ValidatedCertificate, reference_time,
};
pub(in crate::openpgp) use revocation::signature_is_revocable;
pub(in crate::openpgp) use selection::{
    authenticated_key_flags, can_encrypt, can_sign, component_expiration,
    select_newest_policy_signature_in, signature_is_primary,
};

#[cfg(test)]
pub(in crate::openpgp) use selection::{
    select_newest_policy_signature, select_primary_user_id, signature_expired_at,
};
