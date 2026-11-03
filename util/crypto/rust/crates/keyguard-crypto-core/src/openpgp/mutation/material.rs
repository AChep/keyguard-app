//! Shared packet-preserving material pipeline for OpenPGP certificate mutations.
//!
//! Expiration renewal, User ID revocation and User ID replacement all read the
//! same evidence and write it back the same way, so they share one pipeline:
//!
//! ```text
//! private + public + candidate documents
//!       -> RawPacketStream            (bounded, lossless raw framing)
//!       -> PublicCertificatePacketSet (public projection of the secret key,
//!                                      unioned with the stored public copy)
//!       -> CanonicalCertificate       (`finalize()`: retained and transferable
//!                                      serializations, one composed view)
//!       -> validate_certificate       (`MutationPreflight::policy`)
//!       -> the operation's own packet additions or replacements
//!       -> finalize() again, re-validated once, secret material overlaid back
//! ```
//!
//! [`MutationPreflight`] owns everything up to and including policy
//! evaluation. Its error type describes only material-pipeline failures;
//! operations add their own authorization and domain errors at their boundary.

use pgp::{
    armor::BlockType,
    composed::{SignedPublicKey, SignedSecretKey},
    types::{KeyDetails, KeyVersion},
};
use zeroize::{Zeroize, Zeroizing};

use crate::openpgp::{
    certificate::{
        CanonicalCertificate, CertificateIndex, KeyMaterial, MAX_MUTATION_CANDIDATE_CERTIFICATES,
        MutationMaterialError, PublicCertificatePacketSet, SecretCertificateOverlay,
        armor_key_packets, armor_key_packets_zeroizing, parse_mutation_candidates,
        parse_single_certificate_packet_set, parse_single_secret, project_secret_certificate,
        rebuild_secret_certificate,
    },
    format::{fingerprint_hex, normalize_fingerprint},
    packet::USER_ATTRIBUTE_TAG,
    policy::{
        OpenPgpPolicyBudget, OpenPgpPolicyError, PublicComponent, ValidatedCertificate,
        all_components, validate_certificate,
    },
};

const MAX_KEY_DOCUMENT_BYTES: usize = 8 * 1024 * 1024;

pub(crate) struct MutationOutput {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
}

/// The documents and pins every certificate mutation request carries.
pub(crate) struct MutationDocuments {
    pub(crate) private_key: Vec<u8>,
    pub(crate) public_key: Vec<u8>,
    pub(crate) expected_primary_fingerprint: String,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: u64,
}

impl Drop for MutationDocuments {
    fn drop(&mut self) {
        self.private_key.zeroize();
    }
}

/// Request failures every mutation reports before its own argument errors.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum MutationRequestError {
    EmptyPrivateKey,
    ResourceLimit,
}

impl MutationDocuments {
    /// Checks the shared request invariants. Workflows call this before their
    /// own argument checks, so the same bad input reports the same error.
    pub(crate) fn check(&self) -> Result<(), MutationRequestError> {
        if self.private_key.iter().all(u8::is_ascii_whitespace) {
            return Err(MutationRequestError::EmptyPrivateKey);
        }
        if !self.within_bounds() {
            return Err(MutationRequestError::ResourceLimit);
        }
        Ok(())
    }

    /// Opens the preflight, erasing the private key as soon as it is parsed.
    pub(crate) fn open(&mut self) -> Result<MutationPreflight, MutationMaterialError> {
        // Parsing stays bounded even if a workflow skipped `check`.
        if !self.within_bounds() {
            return Err(MutationMaterialError::ResourceLimit);
        }
        let private_key = Zeroizing::new(std::mem::take(&mut self.private_key));
        MutationPreflight::open(
            &private_key,
            &self.public_key,
            &self.candidate_revocation_keys,
            &self.expected_primary_fingerprint,
            self.reference_time_epoch_seconds,
        )
    }

    fn within_bounds(&self) -> bool {
        self.private_key.len() <= MAX_KEY_DOCUMENT_BYTES
            && self.public_key.len() <= MAX_KEY_DOCUMENT_BYTES
            && self.candidate_revocation_keys.len() <= MAX_MUTATION_CANDIDATE_CERTIFICATES
            && self
                .candidate_revocation_keys
                .iter()
                .all(|candidate| candidate.len() <= MAX_KEY_DOCUMENT_BYTES)
    }
}

/// Everything the three certificate mutations need before they may sign.
///
/// The shared preflight exists so expiration renewal, User ID revocation and
/// User ID replacement cannot drift apart on the steps that decide whether a
/// mutation is safe at all: the secret copy is projected and unioned with the
/// stored public certificate exactly once, that union is serialized exactly
/// once, and the resulting certificate is the only thing policy ever sees.
pub(crate) struct MutationPreflight {
    /// Composed secret view, for signer acquisition and secret subkey lookup.
    pub(crate) secret: SignedSecretKey,
    /// Byte-exact secret packet bodies, keyed by the components they protect.
    pub(crate) secret_overlay: SecretCertificateOverlay,
    /// Mutable packet evidence, and the only thing a mutation edits.
    pub(crate) packet_set: PublicCertificatePacketSet,
    /// Canonical view of `packet_set` as it stood before any mutation.
    pub(crate) canonical: CanonicalCertificate,
    /// Advisory certificates that may authenticate designated-revoker evidence.
    pub(crate) external: Vec<SignedPublicKey>,
    /// Every component policy may use to resolve a revocation.
    pub(crate) candidates: Vec<PublicComponent>,
    pub(crate) reference_time: u64,
}

impl MutationPreflight {
    /// Parses, projects, unions, pins and bounds one mutation's inputs.
    pub(crate) fn open(
        private_key: &[u8],
        public_key: &[u8],
        candidate_documents: &[Vec<u8>],
        expected_primary_fingerprint: &str,
        reference_time: u64,
    ) -> Result<Self, MutationMaterialError> {
        let secret = parse_single_secret(private_key)?;
        let (projection, secret_overlay) = project_secret_certificate(private_key)?;
        let mut packet_set = parse_single_certificate_packet_set(public_key)?;
        let projected = parse_single_certificate_packet_set(&projection)?;
        // A changed flag is available here, but no caller needs it: the merge
        // is computed once and its canonical output is the mutation input
        // whether or not either side contributed.
        packet_set.merge(projected)?;
        let canonical = packet_set.finalize()?;
        if canonical
            .components
            .iter()
            .any(|component| !matches!(component.version(), KeyVersion::V4 | KeyVersion::V6))
        {
            return Err(MutationMaterialError::UnsupportedKeyVersion);
        }
        let expected = normalize_fingerprint(expected_primary_fingerprint);
        if (!expected.is_empty() && expected != canonical.fingerprint)
            || fingerprint_hex(secret.primary_key.public_key()) != canonical.fingerprint
        {
            return Err(MutationMaterialError::FingerprintMismatch);
        }
        let external = parse_mutation_candidates(candidate_documents)?;
        let mut candidates = canonical.components.clone();
        candidates.extend(all_components(&external));
        Ok(Self {
            secret,
            secret_overlay,
            packet_set,
            canonical,
            external,
            candidates,
            reference_time,
        })
    }

    /// Evaluates the pre-mutation policy view exactly once per call.
    pub(crate) fn policy(&self) -> Result<ValidatedCertificate<'_>, OpenPgpPolicyError> {
        let mut budget = OpenPgpPolicyBudget::default();
        validate_certificate(
            &self.canonical.semantic,
            &self.candidates,
            self.reference_time,
            &mut budget,
        )
    }

    /// Requires every retained public subkey packet to carry a valid binding.
    ///
    /// Only expiration renewal needs this: it addresses subkeys the composed
    /// view may have dropped, so an unbound subkey packet would otherwise be
    /// silently skipped instead of failing the request.
    pub(crate) fn require_bound_subkeys(&self) -> Result<(), MutationMaterialError> {
        if self.packet_set.subkeys_are_bound()? {
            Ok(())
        } else {
            Err(MutationMaterialError::FingerprintMismatch)
        }
    }

    /// Raw User Attribute packet bodies, in certificate order.
    ///
    /// A User ID packet body is its own identifier, so only attributes need
    /// this: rPGP does not reproduce their bodies. The composed view's
    /// attribute list is built from the very same canonical bytes, so position
    /// `n` here is position `n` there; the count is checked so a composed
    /// parser that dropped one can never shift a mutation onto the wrong
    /// packet.
    pub(crate) fn user_attribute_bodies(
        &self,
        expected: usize,
    ) -> Result<Vec<&[u8]>, MutationMaterialError> {
        let bodies = self
            .canonical
            .identities
            .iter()
            .filter(|identity| identity.tag == USER_ATTRIBUTE_TAG)
            .map(|identity| identity.body.as_slice())
            .collect::<Vec<_>>();
        if bodies.len() != expected {
            return Err(MutationMaterialError::MalformedKey);
        }
        Ok(bodies)
    }

    /// Serializes the mutated packet set once, re-validates it once, and
    /// derives every output from that single pair of views.
    ///
    /// `inspect` carries the operation's own post-conditions. It runs before
    /// any secret material is touched, so a mutation that failed its own
    /// invariant never produces a key document at all.
    /// `transferable_public_output` is reserved for an explicitly local-only
    /// mutation: its private result retains the new evidence, while its public
    /// result must not disclose the affected identity.
    /// The result is validated at the reference time: new signatures are never
    /// dated after it (see `policy::next_signature_time`).
    pub(crate) fn finalize<T, E>(
        &self,
        mutated: &PublicCertificatePacketSet,
        transferable_public_output: bool,
        inspect: impl FnOnce(
            &CanonicalCertificate,
            &ValidatedCertificate<'_>,
            &[String],
        ) -> Result<(T, CertificateIndex), E>,
    ) -> Result<(MutationOutput, T), E>
    where
        E: From<MutationMaterialError> + From<OpenPgpPolicyError>,
    {
        let canonical = mutated
            .finalize_with_revocation_candidates(&self.candidates)
            .map_err(MutationMaterialError::from)?;
        let mut candidates = canonical.components.clone();
        candidates.extend(all_components(&self.external));
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(
            &canonical.semantic,
            &candidates,
            self.reference_time,
            &mut budget,
        )?;
        let stored_secret_fingerprints = self.stored_secret_fingerprints();
        let (inspected, certificate_index) =
            inspect(&canonical, &policy, &stored_secret_fingerprints)?;

        let public_bytes = if transferable_public_output {
            &canonical.bytes
        } else {
            &canonical.local_public_bytes
        };
        if public_bytes.is_empty() {
            return Err(MutationMaterialError::MalformedKey.into());
        }

        let private_packets =
            rebuild_secret_certificate(&canonical.retained_bytes, &self.secret_overlay)?;
        let mut private_key_armored =
            armor_key_packets_zeroizing(&private_packets, BlockType::PrivateKey)?;
        // Mutation output feeds local key state. Keep unusable components that
        // the operation did not target; explicit external export applies the
        // stricter transferable-component classification separately.
        let public_key_armored = armor_key_packets(public_bytes, BlockType::PublicKey)?;
        Ok((
            MutationOutput {
                key_material: KeyMaterial {
                    private_key_armored: std::mem::take(&mut *private_key_armored),
                    public_key_armored,
                    fingerprint: canonical.fingerprint.clone(),
                },
                certificate_index,
            },
            inspected,
        ))
    }

    /// Fingerprints whose secret packets are actually stored, matching what
    /// the read pipeline reports for the same document.
    fn stored_secret_fingerprints(&self) -> Vec<String> {
        let mut fingerprints = Vec::new();
        if self.secret_overlay.has_secret_primary() {
            fingerprints.push(self.canonical.fingerprint.clone());
        }
        fingerprints.extend(
            self.secret_overlay
                .secret_subkey_fingerprints()
                .map(str::to_owned),
        );
        fingerprints
    }
}

#[cfg(test)]
mod tests;
