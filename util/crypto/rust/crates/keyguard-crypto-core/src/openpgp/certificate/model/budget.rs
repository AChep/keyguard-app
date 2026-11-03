use std::collections::BTreeMap;

use super::{
    CertificateMergeError, FingerprintKey, MAX_EXPORT_CLASSIFICATION_VERIFICATIONS,
    MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST, MAX_SIGNATURE_REHOMING_VERIFICATIONS,
    MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST,
};

#[derive(Debug, Default)]
pub(crate) struct SignatureRehomingBudget(CertificateVerificationCounter);

/// Request-scoped accounting for cryptographic work performed solely to
/// classify ordinary transferable output.
///
/// This budget is deliberately independent from the policy budget:
/// structural parsing may derive an export view, but must not consume the
/// caller's later policy-verification allowance.
#[derive(Debug, Default)]
pub(crate) struct ExportClassificationBudget(CertificateVerificationCounter);

impl SignatureRehomingBudget {
    #[cfg(test)]
    pub(crate) fn with_request_limit(request_limit: usize) -> Self {
        Self(CertificateVerificationCounter::with_request_limit(
            request_limit,
        ))
    }

    pub(super) fn begin_certificate(&mut self, fingerprint: &FingerprintKey) {
        self.0.begin_certificate(fingerprint);
    }

    pub(super) fn verify(
        &mut self,
        verify: impl FnOnce() -> bool,
    ) -> Result<bool, CertificateMergeError> {
        self.0.charge(
            MAX_SIGNATURE_REHOMING_VERIFICATIONS,
            MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST,
            verify,
        )
    }
}

impl ExportClassificationBudget {
    #[cfg(test)]
    pub(crate) fn with_request_limit(request_limit: usize) -> Self {
        Self(CertificateVerificationCounter::with_request_limit(
            request_limit,
        ))
    }

    pub(super) fn begin_certificate(&mut self, fingerprint: &FingerprintKey) {
        self.0.begin_certificate(fingerprint);
    }

    pub(super) fn verify(
        &mut self,
        verify: impl FnOnce() -> bool,
    ) -> Result<bool, CertificateMergeError> {
        self.0.charge(
            MAX_EXPORT_CLASSIFICATION_VERIFICATIONS,
            MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST,
            verify,
        )
    }
}

/// Per-certificate and per-request verification counts shared by the
/// certificate budgets.
///
/// Each budget owns its own counter, so the allowances never merge.
#[derive(Debug, Default)]
struct CertificateVerificationCounter {
    verifications: usize,
    request_verifications: usize,
    active_certificate: Option<FingerprintKey>,
    inactive_certificate_verifications: BTreeMap<FingerprintKey, usize>,
    #[cfg(test)]
    request_limit: Option<usize>,
}

impl CertificateVerificationCounter {
    #[cfg(test)]
    fn with_request_limit(request_limit: usize) -> Self {
        Self {
            request_limit: Some(request_limit),
            ..Self::default()
        }
    }

    /// Selects the per-certificate counter for `fingerprint`, restoring the
    /// count consumed by an earlier visit instead of resetting it.
    fn begin_certificate(&mut self, fingerprint: &FingerprintKey) {
        if self.active_certificate.as_ref() == Some(fingerprint) {
            return;
        }
        if let Some(active) = self.active_certificate.take() {
            self.inactive_certificate_verifications
                .insert(active, self.verifications);
        }
        self.verifications = self
            .inactive_certificate_verifications
            .remove(fingerprint)
            .unwrap_or_default();
        self.active_certificate = Some(fingerprint.clone());
    }

    /// Charges one verification against both limits, then runs `verify`.
    ///
    /// Nothing is charged and `verify` is not invoked unless both limits
    /// admit the verification.
    fn charge(
        &mut self,
        certificate_limit: usize,
        request_limit: usize,
        verify: impl FnOnce() -> bool,
    ) -> Result<bool, CertificateMergeError> {
        #[cfg(test)]
        let request_limit = self.request_limit.unwrap_or(request_limit);
        let request_verifications = self
            .request_verifications
            .checked_add(1)
            .filter(|count| *count <= request_limit)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        let verifications = self
            .verifications
            .checked_add(1)
            .filter(|count| *count <= certificate_limit)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        self.request_verifications = request_verifications;
        self.verifications = verifications;
        Ok(verify())
    }
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;

    use super::*;

    fn fingerprint(marker: u8) -> FingerprintKey {
        FingerprintKey {
            version: 4,
            bytes: vec![marker; 20],
        }
    }

    #[test]
    fn signature_rehoming_has_a_fixed_verification_budget() {
        let mut budget = SignatureRehomingBudget::default();
        for _ in 0..MAX_SIGNATURE_REHOMING_VERIFICATIONS {
            assert!(budget.verify(|| true).expect("verification within cap"));
        }
        assert!(matches!(
            budget.verify(|| true),
            Err(CertificateMergeError::ResourceLimit)
        ));
    }

    #[test]
    fn signature_rehoming_request_limit_cannot_multiply_across_certificates() {
        let mut budget = SignatureRehomingBudget::default();
        for marker in [1_u8, 2] {
            budget.begin_certificate(&fingerprint(marker));
            for _ in 0..MAX_SIGNATURE_REHOMING_VERIFICATIONS {
                assert!(!budget.verify(|| false).expect("verification within cap"));
            }
        }
        assert_eq!(
            budget.0.request_verifications,
            MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST,
        );

        budget.begin_certificate(&fingerprint(3));
        assert!(matches!(
            budget.verify(|| false),
            Err(CertificateMergeError::ResourceLimit)
        ));
        assert_eq!(budget.0.verifications, 0);
    }

    #[test]
    fn export_classification_has_a_fixed_verification_budget() {
        let mut budget = ExportClassificationBudget::default();
        budget.begin_certificate(&fingerprint(1));
        for _ in 0..MAX_EXPORT_CLASSIFICATION_VERIFICATIONS {
            assert!(budget.verify(|| true).expect("verification within cap"));
        }
        assert!(matches!(
            budget.verify(|| true),
            Err(CertificateMergeError::ResourceLimit)
        ));
    }

    #[test]
    fn export_classification_request_limit_cannot_multiply_across_certificates() {
        let mut budget = ExportClassificationBudget::default();
        for marker in [1_u8, 2] {
            budget.begin_certificate(&fingerprint(marker));
            for _ in 0..MAX_EXPORT_CLASSIFICATION_VERIFICATIONS {
                assert!(!budget.verify(|| false).expect("verification within cap"));
            }
        }
        assert_eq!(
            budget.0.request_verifications,
            MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST,
        );

        budget.begin_certificate(&fingerprint(3));
        assert!(matches!(
            budget.verify(|| false),
            Err(CertificateMergeError::ResourceLimit)
        ));
        assert_eq!(budget.0.verifications, 0);
    }

    #[test]
    fn request_limit_override_replaces_only_the_request_limit() {
        let mut budget = SignatureRehomingBudget::with_request_limit(2);
        budget.begin_certificate(&fingerprint(1));
        budget.verify(|| true).expect("first request verification");
        budget.begin_certificate(&fingerprint(2));
        budget.verify(|| true).expect("second request verification");
        assert!(matches!(
            budget.verify(|| true),
            Err(CertificateMergeError::ResourceLimit)
        ));

        let mut budget = ExportClassificationBudget::with_request_limit(usize::MAX);
        for _ in 0..MAX_EXPORT_CLASSIFICATION_VERIFICATIONS {
            budget.verify(|| true).expect("verification within cap");
        }
        assert!(matches!(
            budget.verify(|| true),
            Err(CertificateMergeError::ResourceLimit)
        ));
    }

    #[test]
    fn revisiting_a_certificate_restores_its_consumption() {
        let mut counter = CertificateVerificationCounter::default();
        counter.begin_certificate(&fingerprint(1));
        for _ in 0..3 {
            counter
                .charge(4, 8, || true)
                .expect("verification within cap");
        }
        counter.begin_certificate(&fingerprint(2));
        counter
            .charge(4, 8, || true)
            .expect("verification within cap");
        assert_eq!(counter.verifications, 1);

        counter.begin_certificate(&fingerprint(1));
        assert_eq!(counter.verifications, 3);
        assert_eq!(counter.request_verifications, 4);
        counter
            .charge(4, 8, || true)
            .expect("last certificate verification");
        assert!(matches!(
            counter.charge(4, 8, || true),
            Err(CertificateMergeError::ResourceLimit)
        ));
    }

    #[test]
    fn denied_verification_charges_nothing_and_does_not_run() {
        let called = Cell::new(false);
        let verify = || {
            called.set(true);
            true
        };

        let mut counter = CertificateVerificationCounter::default();
        counter
            .charge(1, 8, || true)
            .expect("verification within cap");
        assert!(matches!(
            counter.charge(1, 8, verify),
            Err(CertificateMergeError::ResourceLimit)
        ));
        assert!(!called.get());
        assert_eq!(counter.verifications, 1);
        assert_eq!(counter.request_verifications, 1);

        let mut counter = CertificateVerificationCounter::default();
        counter
            .charge(8, 1, || true)
            .expect("verification within cap");
        counter.begin_certificate(&fingerprint(1));
        assert!(matches!(
            counter.charge(8, 1, verify),
            Err(CertificateMergeError::ResourceLimit)
        ));
        assert!(!called.get());
        assert_eq!(counter.verifications, 0);
        assert_eq!(counter.request_verifications, 1);
    }
}
