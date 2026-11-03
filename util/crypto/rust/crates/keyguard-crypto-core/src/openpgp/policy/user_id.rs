//! Operation-specific authorization shared by metadata and mutation workflows.
//!
//! These guards describe public certificate policy. Revocation planning includes
//! signature timing so metadata and execution evaluate the same proposed statement.
//! Status is read at the reference time only: a future-dated revocation is not
//! in effect yet and does not make a new one redundant.
//! Secret-key availability and template signing support remain signing-time checks.

use pgp::{
    packet::Signature,
    types::{KeyDetails, KeyVersion, Timestamp},
};

use super::{
    IdentityPolicy, MutationAuthorizationError, SignatureTimeError, ValidatedCertificate,
    next_signature_time,
};

pub(in crate::openpgp) enum UserIdRevocationPlan<'a> {
    AlreadyRevoked {
        effective_at: u64,
        template: &'a Signature,
    },
    Sign {
        at: Timestamp,
        template: &'a Signature,
    },
}

impl<'a> UserIdRevocationPlan<'a> {
    /// Self-certification the revocation targets and takes its exportability from.
    pub(in crate::openpgp) const fn template(&self) -> &'a Signature {
        match self {
            Self::AlreadyRevoked { template, .. } | Self::Sign { template, .. } => template,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::openpgp) enum UserIdAuthorizationError {
    Primary(MutationAuthorizationError),
    PolicyConflict,
    TargetNotFound,
    TargetInactive,
    MissingSelfSignature,
    NonRevocable,
    LastUserId,
    UnresolvedRevocationAuthority,
    Time(SignatureTimeError),
}

impl<'a> ValidatedCertificate<'a> {
    pub(in crate::openpgp) fn authorize_user_id_addition(
        &self,
    ) -> Result<(), UserIdAuthorizationError> {
        self.authorize_user_id_mutation()?;
        // A V4 certification takes its key policy from the primary User ID.
        // A direct-key signature alone is not a V4 certification template.
        if self.certificate().primary_key.version() == KeyVersion::V4
            && self.primary_user_id().is_none()
        {
            return Err(UserIdAuthorizationError::MissingSelfSignature);
        }
        Ok(())
    }

    pub(in crate::openpgp) fn authorize_user_id_mutation(
        &self,
    ) -> Result<(), UserIdAuthorizationError> {
        if self.primary.policy_conflict {
            return Err(UserIdAuthorizationError::PolicyConflict);
        }
        self.primary_component()
            .authorize_mutation()
            .map_err(UserIdAuthorizationError::Primary)
    }

    pub(in crate::openpgp) fn authorize_user_id_primary_selection(
        &self,
        index: usize,
    ) -> Result<&IdentityPolicy<'a>, UserIdAuthorizationError> {
        self.authorize_user_id_mutation()?;
        let identity = self
            .user_id_at(index)
            .ok_or(UserIdAuthorizationError::TargetNotFound)?;
        if identity.policy_conflict {
            return Err(UserIdAuthorizationError::PolicyConflict);
        }
        if identity.revocation_status.is_indeterminate() {
            return Err(UserIdAuthorizationError::UnresolvedRevocationAuthority);
        }
        if !identity.authenticated() {
            return Err(UserIdAuthorizationError::TargetInactive);
        }
        Ok(identity)
    }

    pub(in crate::openpgp) fn authorize_user_id_replacement(
        &self,
        index: usize,
    ) -> Result<(), UserIdAuthorizationError> {
        let identity = self.authorize_user_id_primary_selection(index)?;
        if !identity.live_certifications_are_revocable(self.reference_time()) {
            return Err(UserIdAuthorizationError::NonRevocable);
        }
        Ok(())
    }

    /// Returns whether revocation is possible now or after a short clock wait.
    /// A short wait does not hide the action.
    pub(in crate::openpgp) fn can_revoke_user_id(&self, index: usize) -> bool {
        matches!(
            self.plan_user_id_revocation(index),
            Ok(UserIdRevocationPlan::Sign { .. })
                | Err(UserIdAuthorizationError::Time(SignatureTimeError::RetryAt(
                    _
                )))
        )
    }

    /// Inactive identities can still need revocation. In particular, a future or
    /// expired certification accepted by another implementation must be covered.
    pub(in crate::openpgp) fn plan_user_id_revocation(
        &self,
        index: usize,
    ) -> Result<UserIdRevocationPlan<'a>, UserIdAuthorizationError> {
        // Self-revocation must also work for identities whose current policy or
        // external revocation status is unresolved. It does not certify them,
        // so an unresolved primary policy does not block it either.
        self.primary_component()
            .authorize_mutation()
            .map_err(UserIdAuthorizationError::Primary)?;
        let identity = self
            .user_id_at(index)
            .ok_or(UserIdAuthorizationError::TargetNotFound)?;
        let template = identity
            .revocation_exportability_template()
            .ok_or(UserIdAuthorizationError::MissingSelfSignature)?;
        let newest = identity
            .newest_conservative_certification_time()
            .ok_or(UserIdAuthorizationError::MissingSelfSignature)?;
        if identity.revocation_status.is_revoked()
            && let Some(effective_at) = identity.effective_revocation_at
            && effective_at >= newest
        {
            return Ok(UserIdRevocationPlan::AlreadyRevoked {
                effective_at,
                template,
            });
        }
        if !identity.live_certifications_are_revocable(self.reference_time()) {
            return Err(UserIdAuthorizationError::NonRevocable);
        }
        if self.certificate().primary_key.version() == KeyVersion::V4
            && identity.authenticated()
            && !self.has_authenticated_user_id_other_than(identity.packet_body.unwrap_or_default())
        {
            return Err(UserIdAuthorizationError::LastUserId);
        }
        // Checked last, so `can_revoke_user_id` can treat a short wait as revocable.
        let at = next_signature_time(self.reference_time(), Some(newest))
            .map_err(UserIdAuthorizationError::Time)?;
        Ok(UserIdRevocationPlan::Sign { at, template })
    }
}
