//! Packet-preserving OpenPGP certificate mutation workflows.
//!
//! Every operation shares one bounded preflight/finalization pipeline so only
//! explicitly selected certificate statements are rewritten or appended.

mod expiration;
mod material;
mod reconcile;
mod user_id;

pub(crate) use user_id::{
    UserIdMutationFailure, UserIdReplacementInput, UserIdRevocationInput, UserIdUpdate,
    UserIdUpdateInput, replace_user_id_request, revoke_user_id_request, update_user_id_request,
};

pub(crate) use expiration::{
    ExpirationUpdateFailure, ExpirationUpdateInput, update_expiration_request,
};
pub(crate) use material::{MutationDocuments, MutationPreflight};
pub(crate) use reconcile::{
    CertificateMaterialContributions, CertificateMaterialReconcileInput, MaterialInputContribution,
    MaterialInputError, MaterialPairError, MaterialWithheldReason, ReconcileError,
    reconcile_certificate_material_request,
};
/// Implements the failure conversions every mutation workflow shares.
///
/// Expiration renewal, User ID revocation and User ID replacement report the
/// shared material, policy and authorization errors through their own failure
/// enums with identical reason mappings; this macro writes those mappings
/// once. `revoked` names the one divergent case: the failure variant an
/// authorization [`MutationAuthorizationError::Revoked`][revoked] maps to.
/// The failure enum must also carry `TimeConflict` and `RetryAt(u64)`.
///
/// [revoked]: crate::openpgp::policy::MutationAuthorizationError::Revoked
macro_rules! impl_mutation_failure_conversions {
    ($failure:ident, revoked => $revoked:ident) => {
        impl From<crate::openpgp::certificate::MutationMaterialError> for $failure {
            fn from(error: crate::openpgp::certificate::MutationMaterialError) -> Self {
                use crate::openpgp::certificate::MutationMaterialError;
                match error {
                    MutationMaterialError::MalformedKey => Self::MalformedKey,
                    MutationMaterialError::FingerprintMismatch => Self::FingerprintMismatch,
                    MutationMaterialError::UnsupportedKeyVersion => Self::UnsupportedKeyVersion,
                    MutationMaterialError::UnsupportedTskLayout => Self::MalformedKey,
                    MutationMaterialError::ResourceLimit => Self::ResourceLimit,
                    MutationMaterialError::InternalFailure => Self::InternalFailure,
                    MutationMaterialError::SignatureVerificationFailed => {
                        Self::SignatureVerificationFailed
                    }
                }
            }
        }

        impl From<crate::openpgp::certificate::CertificateMutationError> for $failure {
            fn from(error: crate::openpgp::certificate::CertificateMutationError) -> Self {
                Self::from(crate::openpgp::certificate::MutationMaterialError::from(
                    error,
                ))
            }
        }

        impl From<crate::openpgp::mutation::material::MutationRequestError> for $failure {
            fn from(error: crate::openpgp::mutation::material::MutationRequestError) -> Self {
                use crate::openpgp::mutation::material::MutationRequestError;
                match error {
                    MutationRequestError::EmptyPrivateKey => Self::EmptyPrivateKey,
                    MutationRequestError::ResourceLimit => Self::ResourceLimit,
                }
            }
        }

        impl From<crate::openpgp::policy::OpenPgpPolicyError> for $failure {
            fn from(error: crate::openpgp::policy::OpenPgpPolicyError) -> Self {
                use crate::openpgp::policy::OpenPgpPolicyError;
                match error {
                    OpenPgpPolicyError::ResourceLimit
                    | OpenPgpPolicyError::RequestResourceLimit => Self::ResourceLimit,
                    OpenPgpPolicyError::Internal => Self::InternalFailure,
                }
            }
        }

        impl From<crate::openpgp::policy::MutationAuthorizationError> for $failure {
            fn from(error: crate::openpgp::policy::MutationAuthorizationError) -> Self {
                use crate::openpgp::policy::MutationAuthorizationError;
                match error {
                    MutationAuthorizationError::Unauthenticated => {
                        Self::SignatureVerificationFailed
                    }
                    MutationAuthorizationError::Revoked => Self::$revoked,
                    MutationAuthorizationError::IndeterminateRevocation => {
                        Self::UnresolvedRevocationAuthority
                    }
                }
            }
        }

        impl From<crate::openpgp::policy::SignatureTimeError> for $failure {
            fn from(error: crate::openpgp::policy::SignatureTimeError) -> Self {
                use crate::openpgp::policy::SignatureTimeError;
                match error {
                    SignatureTimeError::RetryAt(time) => Self::RetryAt(time),
                    SignatureTimeError::Unrepresentable | SignatureTimeError::TooFarAhead => {
                        Self::TimeConflict
                    }
                }
            }
        }

        impl $failure {
            /// Exact time at which a mutation blocked by signature ordering may be retried.
            pub(crate) const fn retry_at(self) -> Option<u64> {
                match self {
                    Self::RetryAt(time) => Some(time),
                    _ => None,
                }
            }
        }
    };
}
pub(crate) use impl_mutation_failure_conversions;

#[cfg(test)]
mod version_tests;
