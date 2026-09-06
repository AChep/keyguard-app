//! Bounded work accounting for OpenPGP policy evaluation.
//!
//! Every cryptographic verification is charged both to the certificate
//! currently selected in the request budget and to the request as a whole.
//! Designated-revoker declarations likewise share one request allowance.

use std::collections::BTreeMap;

use pgp::types::KeyDetails;
use thiserror::Error;

use crate::openpgp::certificate::PublicComponent;

pub(super) const MAX_PUBLIC_KEY_VERIFICATIONS_PER_CERTIFICATE: usize = 4 * 1024;
// One maximally expensive certificate still leaves one complete independent
// per-certificate allowance. This is deliberately far below the 64-certificate
// request cardinality multiplied by the per-certificate cap, and is consistent
// with the request-wide 4,096-signature shape limit.
pub(super) const MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST: usize = 8 * 1024;
pub(super) const MAX_DESIGNATED_REVOKERS_PER_REQUEST: usize = 32;
// Each canonical certificate retains at most 4,096 signatures in total. A
// target digest is computed only for an algorithm requested by an otherwise
// well-formed Certification Revocation, so no valid certificate can require
// more digest computations. Keep an independent policy-side cap so
// non-canonical internal callers cannot bypass the work bound.
pub(super) const MAX_SIGNATURE_TARGET_DIGESTS_PER_CERTIFICATE: usize = 4 * 1024;
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(in crate::openpgp) enum OpenPgpPolicyError {
    /// A certificate-local allowance was exhausted. Multi-certificate callers
    /// may quarantine that certificate and continue with an independent one.
    #[error("OpenPGP policy resource limit exceeded")]
    ResourceLimit,
    /// The aggregate allowance shared by the whole request was exhausted.
    /// This must fail the request instead of being mistaken for a local
    /// certificate rejection.
    #[error("OpenPGP policy request resource limit exceeded")]
    RequestResourceLimit,
    #[error("OpenPGP policy evaluation failed")]
    Internal,
}

#[derive(Debug, Default)]
pub(in crate::openpgp) struct OpenPgpPolicyBudget {
    pub(super) public_key_verifications: usize,
    pub(super) request_public_key_verifications: usize,
    pub(super) signature_target_digests: usize,
    active_certificate: Option<Vec<u8>>,
    inactive_certificate_work: BTreeMap<Vec<u8>, CertificateWork>,
    pub(super) designated_revokers: Vec<DesignatedRevokerId>,
}

#[derive(Clone, Copy, Debug, Default)]
struct CertificateWork {
    public_key_verifications: usize,
    signature_target_digests: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::openpgp) struct DesignatedRevokerId {
    pub(in crate::openpgp) algorithm: u8,
    pub(in crate::openpgp) fingerprint: Vec<u8>,
    pub(in crate::openpgp) key_class: u8,
}

impl DesignatedRevokerId {
    /// Returns whether `candidate` is exactly the declared revoker key.
    ///
    /// This is the only comparison mutation and read policy may use, so that a
    /// declaration is never matched by algorithm or key ID alone.
    pub(super) fn matches_component(&self, candidate: &PublicComponent) -> bool {
        u8::from(candidate.algorithm()) == self.algorithm
            && candidate.fingerprint().as_bytes() == self.fingerprint.as_slice()
    }
}

impl OpenPgpPolicyBudget {
    /// Selects the per-request work counter for `certificate`.
    ///
    /// Historical views of the same certificate share one allowance. Work is
    /// saved by primary fingerprint when evaluation moves to another
    /// certificate, so a certification-flooded certificate cannot drain an
    /// unrelated certificate's allowance.
    pub(super) fn begin_certificate_evaluation(
        &mut self,
        certificate: &pgp::composed::SignedPublicKey,
    ) {
        self.select_certificate(certificate.primary_key.fingerprint().as_bytes());
    }

    /// Restores the counter for a certificate whose policy was already
    /// evaluated and whose data-signature candidate is about to be checked.
    pub(in crate::openpgp) fn select_certificate(&mut self, fingerprint: &[u8]) {
        if self.active_certificate.as_deref() == Some(fingerprint) {
            return;
        }
        if let Some(active) = self.active_certificate.take() {
            self.inactive_certificate_work.insert(
                active,
                CertificateWork {
                    public_key_verifications: self.public_key_verifications,
                    signature_target_digests: self.signature_target_digests,
                },
            );
        }
        let (fingerprint, work) = self
            .inactive_certificate_work
            .remove_entry(fingerprint)
            .unwrap_or_else(|| (fingerprint.to_vec(), CertificateWork::default()));
        self.public_key_verifications = work.public_key_verifications;
        self.signature_target_digests = work.signature_target_digests;
        self.active_certificate = Some(fingerprint);
    }

    pub(in crate::openpgp) fn charge_public_key_verification(
        &mut self,
    ) -> Result<(), OpenPgpPolicyError> {
        let request_public_key_verifications = self
            .request_public_key_verifications
            .checked_add(1)
            .filter(|value| *value <= MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST)
            .ok_or(OpenPgpPolicyError::RequestResourceLimit)?;
        let public_key_verifications = self
            .public_key_verifications
            .checked_add(1)
            .filter(|value| *value <= MAX_PUBLIC_KEY_VERIFICATIONS_PER_CERTIFICATE)
            .ok_or(OpenPgpPolicyError::ResourceLimit)?;
        self.request_public_key_verifications = request_public_key_verifications;
        self.public_key_verifications = public_key_verifications;
        Ok(())
    }

    pub(super) fn charge_signature_target_digest(&mut self) -> Result<(), OpenPgpPolicyError> {
        self.signature_target_digests = self
            .signature_target_digests
            .checked_add(1)
            .filter(|value| *value <= MAX_SIGNATURE_TARGET_DIGESTS_PER_CERTIFICATE)
            .ok_or(OpenPgpPolicyError::ResourceLimit)?;
        Ok(())
    }

    pub(super) fn charge_designated_revoker(
        &mut self,
        revoker: DesignatedRevokerId,
    ) -> Result<(), OpenPgpPolicyError> {
        insert_designated_revoker(&mut self.designated_revokers, revoker).map(|_| ())
    }
}

pub(super) fn insert_designated_revoker(
    revokers: &mut Vec<DesignatedRevokerId>,
    candidate: DesignatedRevokerId,
) -> Result<bool, OpenPgpPolicyError> {
    if revokers.contains(&candidate) {
        return Ok(false);
    }
    // The cap is request-wide, so exhausting it must not be recoverable as a
    // per-certificate skip.
    if revokers.len() >= MAX_DESIGNATED_REVOKERS_PER_REQUEST {
        return Err(OpenPgpPolicyError::RequestResourceLimit);
    }
    revokers.push(candidate);
    Ok(true)
}
