//! Policy- and time-qualified OpenPGP certificate views.
//!
//! Packet retention belongs to [`crate::openpgp::certificate`], while
//! authentication and selection are performed by sibling modules. The types
//! here keep those results tied to one certificate and one evaluation instant.

use std::time::{SystemTime, UNIX_EPOCH};

use pgp::{
    packet::{KeyFlags, PublicKey, PublicSubkey, Signature},
    types::{KeyDetails, KeyVersion},
};

use crate::openpgp::{
    crypto::verification::{signature_config_is_non_exportable, signature_creation_time},
    format::hex_upper,
};

use super::{
    acceptance::{
        SelfRevocationHashSecurity, key_created_at_or_before, self_revocation_signature_acceptable,
        signature_expired,
    },
    budget::DesignatedRevokerId,
    revocation::{RevocationTarget, revocation_is_effective, signature_is_revocable},
    selection::{component_is_expired, encryption_component_usable, signing_component_usable},
};

/// The result of evaluating revocation evidence for an authenticated key.
///
/// `Indeterminate` means that a revocation packet names an authorized
/// designated revoker, but the corresponding public key was not supplied, so
/// the packet cannot be authenticated. Issuer hints do not prove revocation,
/// but new-data use fails closed until the authority key is supplied and the
/// packet can be verified or rejected. A declaration by itself remains
/// `NotRevoked`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum RevocationStatus {
    NotRevoked,
    Revoked,
    Indeterminate,
}

/// Why an authenticated component cannot authorize a new outbound signature.
///
/// Expiration is intentionally not part of this decision. Recertification is
/// allowed to renew expired components, while unresolved third-party
/// revocation evidence must fail closed for every mutation that emits a new
/// signed statement.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum MutationAuthorizationError {
    Unauthenticated,
    Revoked,
    IndeterminateRevocation,
}

/// How a component qualified for a recertification that reissues its own
/// self-signatures.
///
/// `TemplateOnly` is the deliberate legacy rescue: a certificate whose
/// self-signatures are all past a hash cutoff authenticates nothing, so it can
/// neither read nor sign — but it must stay renewable, because the renewal is
/// exactly what replaces those signatures with modern ones. It is *not* an
/// authentication result and must never gate anything but issuing a fresh
/// self-signature over the same component.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum RenewalAuthorization {
    Authenticated,
    TemplateOnly,
}

impl RevocationStatus {
    pub(in crate::openpgp) fn is_revoked(self) -> bool {
        self == Self::Revoked
    }

    pub(in crate::openpgp) fn is_indeterminate(self) -> bool {
        self == Self::Indeterminate
    }

    pub(super) fn permits_new_data(self) -> bool {
        self == Self::NotRevoked
    }
}
/// A mathematically verified signature that the read and signing policy
/// deliberately does not accept, because its hash algorithm is past its policy
/// cutoff.
///
/// A template carries no authentication whatsoever.  It exists so that
/// certificate renewal can rebuild an equivalent statement with a modern hash
/// instead of stranding legacy keys; treating one as evidence would silently
/// reinstate SHA-1 self-certifications.  The inner signature is therefore only
/// reachable through [`PolicyInactiveTemplate::template_signature`], whose name
/// makes the tier explicit at every call site, and the wrapper deliberately
/// implements neither `Deref` nor a conversion to `&Signature`.
#[derive(Clone, Copy)]
pub(in crate::openpgp) struct PolicyInactiveTemplate<'a>(pub(super) &'a Signature);

impl<'a> PolicyInactiveTemplate<'a> {
    /// Returns the signature whose subpacket structure a mutation may copy.
    ///
    /// The caller must issue a fresh signature over the copied structure; the
    /// returned signature must never be treated as verified policy evidence.
    pub(in crate::openpgp) fn template_signature(self) -> &'a Signature {
        self.0
    }
}

impl std::fmt::Debug for PolicyInactiveTemplate<'_> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PolicyInactiveTemplate")
            .field("created", &signature_creation_time(self.0))
            .finish_non_exhaustive()
    }
}

pub(in crate::openpgp) struct ComponentPolicy<'a, K> {
    pub(in crate::openpgp) key: &'a K,
    pub(in crate::openpgp) authenticated: bool,
    /// Defensive unresolved-policy state retained for mutation and reporting
    /// call sites. Ordinary evaluated certificates leave this false because
    /// equal-time statements retain packet order.
    pub(in crate::openpgp) policy_conflict: bool,
    pub(in crate::openpgp) effective_signature: Option<&'a Signature>,
    /// Every policy-acceptable, verified self-signature that binds this
    /// component: Direct Key signatures for a primary key, subkey binding
    /// signatures for a subkey.
    ///
    /// For a subkey, `effective_signature` is always selected out of this
    /// list.  For a primary key it may not be: a V4 certificate commonly
    /// carries no Direct Key signature at all and takes its effective policy
    /// from the primary User ID's certification, which lives on the
    /// corresponding [`IdentityPolicy`] instead.
    pub(in crate::openpgp) verified_bindings: Vec<&'a Signature>,
    /// Every binding over this component that verifies mathematically,
    /// including ones no policy tier accepts.
    ///
    /// This is not authentication evidence. Mutations only use its creation
    /// times so a replacement cannot later be superseded by a retained
    /// future-dated binding.
    pub(super) verified_any_bindings: Vec<&'a Signature>,
    /// Renewal-only tier: see [`PolicyInactiveTemplate`].  Never authentication.
    pub(in crate::openpgp) verified_templates: Vec<PolicyInactiveTemplate<'a>>,
    pub(in crate::openpgp) key_flags: Option<KeyFlags>,
    pub(in crate::openpgp) key_expiration_seconds: Option<u32>,
    pub(in crate::openpgp) revocation_status: RevocationStatus,
    /// Compatibility projection for metadata and mutation call sites.  This
    /// reports only a cryptographically authenticated revocation; outbound
    /// policy must inspect `revocation_status` so unresolved evidence fails
    /// closed without being mislabeled as a verified revocation.
    pub(in crate::openpgp) revoked: bool,
    pub(in crate::openpgp) signing_cross_certified: bool,
    /// The authenticated effective Features statement for this component.
    ///
    /// Absence is retained separately from a present subpacket because RFC
    /// 9580 permits SEIPDv2 support to be inferred for an available V6
    /// component only when the subpacket is absent.  A present statement
    /// without bit 0x08 is an explicit negative.
    pub(in crate::openpgp) features: AuthenticatedFeatures,
    pub(in crate::openpgp) allows_gnupg_ocb: bool,
    /// Authenticated effective preferences for v1 SEIPD encryption.  `None`
    /// means the effective self-signature did not carry the subpacket.
    pub(in crate::openpgp) preferred_symmetric: Option<Vec<u8>>,
    /// Authenticated effective compression preferences.  `None` means the
    /// effective self-signature did not carry the subpacket.
    pub(in crate::openpgp) preferred_compression: Option<Vec<u8>>,
    /// Authenticated effective RFC 9580 SEIPDv2 ciphersuite preferences.
    /// `None` means the effective self-signature did not carry the subpacket.
    pub(in crate::openpgp) preferred_aead: Option<Vec<(u8, u8)>>,
    /// Authenticated effective GnuPG/LibrePGP type-34 encryption-mode
    /// preferences.  This is deliberately separate from RFC 9580's type-39
    /// paired ciphersuites.
    pub(in crate::openpgp) preferred_encryption_modes: EncryptionModePreferences,
}

impl<K> ComponentPolicy<'_, K> {
    /// Returns whether this component carries a renewal-only template.
    pub(in crate::openpgp) fn has_template(&self) -> bool {
        !self.verified_templates.is_empty()
    }

    /// Newest creation time across every mathematically verified binding, for
    /// a mutation that must be dated after all retained statements.
    pub(in crate::openpgp) fn newest_conservative_binding_time(&self) -> Option<u64> {
        self.verified_bindings
            .iter()
            .chain(&self.verified_any_bindings)
            .filter_map(|signature| signature_creation_time(signature).map(u64::from))
            .max()
    }
}

pub(in crate::openpgp) struct IdentityPolicy<'a> {
    /// Raw User ID packet body, or `None` for a User Attribute.
    ///
    /// A User Attribute has no body the composed view reproduces faithfully,
    /// so attributes are addressed positionally instead of by content.
    pub(super) packet_body: Option<&'a [u8]>,
    /// Defensive unresolved-policy state; canonical equal-time selection
    /// normally leaves this false.
    pub(in crate::openpgp) policy_conflict: bool,
    pub(in crate::openpgp) effective_signature: Option<&'a Signature>,
    /// Every policy-acceptable, verified self-certification over this identity.
    /// `effective_signature` is the one selected out of this list.
    pub(in crate::openpgp) verified_certifications: Vec<&'a Signature>,
    /// Certification revocations over this identity that verify under the
    /// certificate's own primary key.
    pub(in crate::openpgp) verified_revocations: Vec<&'a Signature>,
    /// Every self-certification over this identity that verifies
    /// mathematically, including ones no policy tier accepts.
    ///
    /// This is not evidence and must never authenticate anything. Mutation
    /// preflight reads only its creation times, so a new statement sorts after
    /// every mathematically valid self-certification without letting rejected
    /// policy fields influence the mutation.
    pub(super) verified_any_certifications: Vec<&'a Signature>,
    /// Renewal-only tier: see [`PolicyInactiveTemplate`].  Never authentication.
    pub(in crate::openpgp) verified_templates: Vec<PolicyInactiveTemplate<'a>>,
    /// Newest creation time across `verified_certifications`.  A mutation that
    /// must be chronologically newer than every accepted certification reads
    /// this instead of rescanning the identity's signatures with its own loop.
    pub(in crate::openpgp) newest_certification_time: Option<u64>,
    /// Whether `effective_signature` permits a later Certification Revocation.
    /// `true` when no certification is effective, matching the
    /// `revocation_is_effective` convention.
    pub(in crate::openpgp) effective_certification_revocable: bool,
    /// Hash property required for this component's self-revocations.
    pub(super) self_revocation_hash_security: SelfRevocationHashSecurity,
    pub(in crate::openpgp) revocation_status: RevocationStatus,
    /// Creation time of the authenticated revocation proving `Revoked`.
    /// Callers must not infer this evidence from the absence of an active UID.
    pub(in crate::openpgp) effective_revocation_at: Option<u64>,
}

/// An exact User ID component selected from a validated certificate view.
///
/// OpenPGP User IDs are arbitrary bytes.  The certificate index disambiguates
/// duplicate packet bodies, while `packet_body` keeps all policy and mutation
/// decisions independent of their lossy display representation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) struct ValidatedUserId<'a> {
    pub(super) index: usize,
    pub(super) packet_body: &'a [u8],
}

impl<'a> ValidatedUserId<'a> {
    pub(in crate::openpgp) fn index(self) -> usize {
        self.index
    }

    pub(in crate::openpgp) fn packet_body(self) -> &'a [u8] {
        self.packet_body
    }
}

impl<'a> IdentityPolicy<'a> {
    pub(in crate::openpgp) fn authenticated(&self) -> bool {
        self.effective_signature.is_some() && self.revocation_status.permits_new_data()
    }

    /// Returns whether this identity carries a renewal-only template.
    pub(in crate::openpgp) fn has_template(&self) -> bool {
        !self.verified_templates.is_empty()
    }

    /// Newest creation time across every mathematically verified
    /// certification, for a mutation that must be dated after all of them.
    ///
    /// Falls back to `newest_certification_time` for the accepted tier, which
    /// is the only figure a caller that trusts policy evidence should read.
    pub(in crate::openpgp) fn newest_conservative_certification_time(&self) -> Option<u64> {
        self.newest_certification_time
            .into_iter()
            .chain(
                self.verified_any_certifications
                    .iter()
                    .filter_map(|signature| signature_creation_time(signature).map(u64::from)),
            )
            .max()
    }

    /// Selects a verified self-certification solely as the exportability
    /// template for a new Certification Revocation.
    ///
    /// A policy-inactive or future-dated certification remains non-evidence,
    /// but it still determines whether a peer may transport this identity. If
    /// any verified certification is exportable, the identity is already
    /// transport-visible and its revocation must be exportable too. Only an
    /// identity whose every verified certification is local stays local.
    pub(in crate::openpgp) fn revocation_exportability_template(&self) -> Option<&'a Signature> {
        self.verified_any_certifications
            .iter()
            .copied()
            .find(|signature| {
                !signature
                    .config()
                    .is_some_and(signature_config_is_non_exportable)
            })
            .or_else(|| self.verified_any_certifications.first().copied())
    }

    /// Returns whether every mathematically verified certification that is
    /// live at `time` permits a later Certification Revocation.
    ///
    /// Read policy selects one effective certification, but mutation must be
    /// conservative across every certification a peer could accept. Otherwise
    /// revoking the selected certification can expose an older
    /// `Revocable(false)` certification and leave the identity authenticated.
    pub(in crate::openpgp) fn live_certifications_are_revocable(&self, time: u64) -> bool {
        self.verified_any_certifications
            .iter()
            .copied()
            .filter(|signature| {
                signature_creation_time(signature).is_some_and(|created| u64::from(created) <= time)
                    && !signature_expired(signature, time)
            })
            .all(signature_is_revocable)
    }

    /// Effective time of this identity's own revocation as seen at `time`.
    ///
    /// A mutation dates its new signature after every statement it supersedes,
    /// so it must ask whether the identity is already revoked at *that*
    /// instant rather than at the certificate's reference time. Only
    /// self-revocations are considered; third-party evidence needs the
    /// designated-revoker resolution that `revocation_status` already carries,
    /// and treating unresolvable evidence as "already revoked" would be the
    /// wrong direction to fail.
    pub(in crate::openpgp) fn self_revoked_at(&self, time: u64) -> Option<u64> {
        let target = RevocationTarget::Certification {
            revocable: self.effective_certification_revocable,
        };
        self.verified_revocations
            .iter()
            .copied()
            .filter(|signature| {
                self_revocation_signature_acceptable(
                    signature,
                    time,
                    target,
                    self.self_revocation_hash_security,
                )
            })
            .filter(|signature| {
                revocation_is_effective(
                    std::iter::once(*signature),
                    self.effective_signature,
                    time,
                    target,
                )
            })
            .filter_map(|signature| signature_creation_time(signature).map(u64::from))
            .max()
    }
}

/// A single, policy-checked view of a certificate at one reference time.
///
/// Keeping the certificate and reference time beside the component projections prevents callers
/// from accidentally combining policy results with another certificate or evaluation instant.
pub(in crate::openpgp) struct ValidatedCertificate<'a> {
    pub(super) certificate: &'a pgp::composed::SignedPublicKey,
    pub(super) reference_time: u64,
    pub(super) revocation_authority_requirements: Vec<DesignatedRevokerId>,
    pub(in crate::openpgp) primary: ComponentPolicy<'a, PublicKey>,
    pub(in crate::openpgp) subkeys: Vec<ComponentPolicy<'a, PublicSubkey>>,
    pub(super) user_ids: Vec<IdentityPolicy<'a>>,
    /// Attribute policy in certificate order, mirroring `user_ids`.
    pub(super) user_attributes: Vec<IdentityPolicy<'a>>,
    pub(super) authenticated_user_ids: Vec<ValidatedUserId<'a>>,
    pub(super) primary_user_id: Option<ValidatedUserId<'a>>,
}

#[derive(Clone, Copy)]
enum ComponentRole {
    Primary,
    Subkey,
}

/// A component whose policy is bound to its owning certificate evaluation.
///
/// The wrapper is the only public route to time- and role-sensitive decisions,
/// preventing a component evaluated for one certificate or instant from being
/// consumed through another certificate's policy context.
#[derive(Clone, Copy)]
pub(in crate::openpgp) struct EvaluatedComponent<'view, 'cert, K> {
    certificate: &'view ValidatedCertificate<'cert>,
    component: &'view ComponentPolicy<'cert, K>,
    role: ComponentRole,
}

impl<'view, 'cert, K> EvaluatedComponent<'view, 'cert, K> {
    pub(in crate::openpgp) fn policy(&self) -> &'view ComponentPolicy<'cert, K> {
        self.component
    }

    pub(in crate::openpgp) fn authorize_mutation(&self) -> Result<(), MutationAuthorizationError> {
        if !self.component.authenticated {
            return Err(MutationAuthorizationError::Unauthenticated);
        }
        match self.component.revocation_status {
            RevocationStatus::NotRevoked => Ok(()),
            RevocationStatus::Revoked => Err(MutationAuthorizationError::Revoked),
            RevocationStatus::Indeterminate => {
                Err(MutationAuthorizationError::IndeterminateRevocation)
            }
        }
    }
}

impl<K: KeyDetails> EvaluatedComponent<'_, '_, K> {
    pub(in crate::openpgp) fn is_expired(&self) -> bool {
        component_is_expired(self.component, self.certificate.reference_time)
    }

    pub(in crate::openpgp) fn signing_usable(&self) -> bool {
        signing_component_usable(
            self.component,
            self.certificate.reference_time,
            matches!(self.role, ComponentRole::Subkey),
        )
    }

    pub(in crate::openpgp) fn encryption_usable(&self) -> bool {
        encryption_component_usable(self.component, self.certificate.reference_time)
    }
}

impl EvaluatedComponent<'_, '_, PublicSubkey> {
    pub(in crate::openpgp) fn authorize_renewal(
        &self,
    ) -> Result<RenewalAuthorization, MutationAuthorizationError> {
        renewal_authorization(
            self.component.authenticated,
            self.component.has_template()
                && key_created_at_or_before(self.component.key, self.certificate.reference_time),
            self.component.revocation_status,
        )
    }
}

impl<'a> ValidatedCertificate<'a> {
    pub(in crate::openpgp) fn certificate(&self) -> &'a pgp::composed::SignedPublicKey {
        self.certificate
    }

    #[cfg(test)]
    pub(in crate::openpgp) fn reference_time(&self) -> u64 {
        self.reference_time
    }

    pub(in crate::openpgp) fn authenticated_user_ids(
        &self,
    ) -> impl Iterator<Item = ValidatedUserId<'a>> + '_ {
        self.authenticated_user_ids.iter().copied()
    }

    pub(in crate::openpgp) fn primary_user_id(&self) -> Option<ValidatedUserId<'a>> {
        self.primary_user_id
    }

    #[cfg(test)]
    pub(in crate::openpgp) fn verified_user_ids_for_test(&self) -> Vec<String> {
        self.authenticated_user_ids()
            .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
            .collect()
    }

    #[cfg(test)]
    pub(in crate::openpgp) fn primary_user_id_for_test(&self) -> Option<String> {
        self.primary_user_id()
            .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
    }

    pub(in crate::openpgp) fn revocation_authority_fingerprints(
        &self,
    ) -> impl Iterator<Item = String> + '_ {
        self.revocation_authority_requirements
            .iter()
            .map(|authority| hex_upper(&authority.fingerprint))
    }

    pub(in crate::openpgp) fn legacy_designated_revokers(
        &self,
    ) -> impl Iterator<Item = &DesignatedRevokerId> {
        self.revocation_authority_requirements.iter()
    }

    pub(in crate::openpgp) fn primary_component(&self) -> EvaluatedComponent<'_, 'a, PublicKey> {
        EvaluatedComponent {
            certificate: self,
            component: &self.primary,
            role: ComponentRole::Primary,
        }
    }

    pub(in crate::openpgp) fn subkey(
        &self,
        key: &impl KeyDetails,
    ) -> Option<EvaluatedComponent<'_, 'a, PublicSubkey>> {
        self.subkeys_matching(key).next()
    }

    pub(in crate::openpgp) fn subkey_components(
        &self,
    ) -> impl Iterator<Item = EvaluatedComponent<'_, 'a, PublicSubkey>> {
        self.subkeys.iter().map(|component| EvaluatedComponent {
            certificate: self,
            component,
            role: ComponentRole::Subkey,
        })
    }

    /// Returns every subkey policy entry carrying `key`'s fingerprint.
    ///
    /// A keyring can present the same subkey twice with its signatures split
    /// across the entries (rPGP keeps public and secret subkeys in separate
    /// lists).  Bindings for identical key material are cumulative, so a
    /// caller judging usability must consider every entry — the first match
    /// alone can carry the wrong entry's bindings.
    pub(in crate::openpgp) fn subkeys_matching<'b>(
        &'b self,
        key: &impl KeyDetails,
    ) -> impl Iterator<Item = EvaluatedComponent<'b, 'a, PublicSubkey>> {
        let fingerprint = key.fingerprint();
        self.subkeys
            .iter()
            .filter(move |component| component.key.fingerprint() == fingerprint)
            .map(|component| EvaluatedComponent {
                certificate: self,
                component,
                role: ComponentRole::Subkey,
            })
    }

    pub(in crate::openpgp) fn user_id(&self, packet_body: &[u8]) -> Option<&IdentityPolicy<'a>> {
        self.user_ids
            .iter()
            .find(|identity| identity.packet_body == Some(packet_body))
    }

    /// Returns the User ID policy at `index` of the certificate's User ID list.
    pub(in crate::openpgp) fn user_id_at(&self, index: usize) -> Option<&IdentityPolicy<'a>> {
        self.user_ids.get(index)
    }

    /// Returns the User Attribute policy at `index` of the certificate's
    /// attribute list.
    ///
    /// Attributes are positional because their packet body is not recoverable
    /// from the composed view; see [`IdentityPolicy::packet_body`].
    pub(in crate::openpgp) fn user_attribute_at(
        &self,
        index: usize,
    ) -> Option<&IdentityPolicy<'a>> {
        self.user_attributes.get(index)
    }

    pub(in crate::openpgp) fn has_authenticated_user_id_other_than(
        &self,
        packet_body: &[u8],
    ) -> bool {
        self.user_ids
            .iter()
            .any(|identity| identity.packet_body != Some(packet_body) && identity.authenticated())
    }

    pub(in crate::openpgp) fn primary_available(&self) -> bool {
        self.primary.authenticated
            && self.primary.revocation_status.permits_new_data()
            && !self.primary_component().is_expired()
    }

    /// Authorizes reissuing the primary key's own self-signatures.
    ///
    /// A primary key whose only usable self-signatures are weak-hash templates
    /// is unauthenticated for every other purpose but still renewable; the
    /// templates may sit on the primary key itself or on any of its
    /// non-revoked identities, because a V4 certificate commonly carries no
    /// Direct Key signature at all.
    pub(in crate::openpgp) fn authorize_primary_renewal(
        &self,
    ) -> Result<RenewalAuthorization, MutationAuthorizationError> {
        let has_template = self.primary.has_template()
            || self
                .user_ids
                .iter()
                .chain(&self.user_attributes)
                .any(|identity| {
                    identity.has_template() && identity.revocation_status.permits_new_data()
                });
        renewal_authorization(
            self.primary.authenticated,
            has_template && key_created_at_or_before(self.primary.key, self.reference_time),
            self.primary.revocation_status,
        )
    }
}

/// Shared decision table behind the renewal authorizations.
///
/// Revocation is evaluated first and identically to `authorize_mutation`, so
/// the template tier can never resurrect a revoked or indeterminate component.
fn renewal_authorization(
    authenticated: bool,
    has_usable_template: bool,
    status: RevocationStatus,
) -> Result<RenewalAuthorization, MutationAuthorizationError> {
    match status {
        RevocationStatus::Revoked => return Err(MutationAuthorizationError::Revoked),
        RevocationStatus::Indeterminate => {
            return Err(MutationAuthorizationError::IndeterminateRevocation);
        }
        RevocationStatus::NotRevoked => {}
    }
    if authenticated {
        Ok(RenewalAuthorization::Authenticated)
    } else if has_usable_template {
        Ok(RenewalAuthorization::TemplateOnly)
    } else {
        Err(MutationAuthorizationError::Unauthenticated)
    }
}

#[derive(Clone, Copy)]
pub(in crate::openpgp) enum PolicyContext {
    Direct,
    Identity,
    Subkey,
}

/// Which tier of the amalgamation a mathematically verified signature belongs
/// to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum SignatureTier {
    /// Accepted by the read and signing policy.
    Authenticated,
    /// Renewal template only: see [`PolicyInactiveTemplate`].
    Template,
    /// Not usable in any tier.
    Rejected,
}

pub(in crate::openpgp) enum PolicySelection<'a> {
    Missing,
    /// Reserved fail-closed state for callers that cannot select a policy.
    /// Equal-time packet-order selection does not produce this state.
    Conflict,
    Selected {
        signature: &'a Signature,
        projection: Box<SignaturePolicyProjection>,
    },
}

/// The authenticated type-34 Preferred Encryption Modes statement.
///
/// Duplicate type-34 subpackets are not collapsed using packet order.  The
/// legacy subpacket has had incompatible names across OpenPGP drafts, so an
/// ambiguous statement must not opt a recipient into GnuPG's reserved tag-20
/// wire format.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum EncryptionModePreferences {
    Missing,
    Present(Box<[u8]>),
    Ambiguous,
}

impl EncryptionModePreferences {
    pub(super) fn or(self, fallback: Self) -> Self {
        match self {
            Self::Missing => fallback,
            Self::Present(_) | Self::Ambiguous => self,
        }
    }

    pub(super) fn contains(&self, algorithm: u8) -> bool {
        matches!(self, Self::Present(algorithms) if algorithms.contains(&algorithm))
    }
}

/// An authenticated effective Features self-signature subpacket.
///
/// This is intentionally not an `Option<Vec<u8>>`: callers negotiating a
/// feature whose support may be inferred from absence must make that state
/// distinction explicit.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum AuthenticatedFeatures {
    Missing,
    Present(Box<[u8]>),
}

impl AuthenticatedFeatures {
    pub(super) fn or(self, fallback: Self) -> Self {
        match self {
            Self::Missing => fallback,
            Self::Present(_) => self,
        }
    }

    pub(in crate::openpgp) fn contains(&self, feature: u8) -> bool {
        matches!(self, Self::Present(features) if features.first().is_some_and(|byte| byte & feature != 0))
    }

    pub(in crate::openpgp) fn is_missing(&self) -> bool {
        matches!(self, Self::Missing)
    }

    /// Returns whether this effective component policy permits RFC 9580
    /// SEIPDv2, including the V6-only inference from an absent subpacket.
    ///
    /// The caller remains responsible for requiring an available,
    /// policy-valid primary key before using this result.
    pub(in crate::openpgp) fn allows_seipd_v2(&self, key_version: KeyVersion) -> bool {
        self.contains(0x08) || (key_version == KeyVersion::V6 && self.is_missing())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::openpgp) struct SignaturePolicyProjection {
    pub(super) signature_expiration_seconds: Option<u32>,
    pub(super) key_expiration_seconds: Option<Option<u32>>,
    pub(super) key_flags: Option<KeyFlags>,
    pub(super) is_primary: bool,
    pub(super) preferred_symmetric: Option<Vec<u8>>,
    pub(super) preferred_compression: Option<Vec<u8>>,
    pub(super) preferred_aead: Option<Vec<(u8, u8)>>,
    pub(super) preferred_encryption_modes: EncryptionModePreferences,
    pub(super) features: AuthenticatedFeatures,
    pub(super) signing_cross_certified: bool,
}

pub(in crate::openpgp) fn reference_time(explicit: Option<u64>) -> u64 {
    explicit.unwrap_or_else(|| {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_secs())
    })
}
