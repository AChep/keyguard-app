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

/// Enforces the shared document and candidate-count limits for certificate mutations.
pub(crate) fn validate_mutation_document_bounds(
    private_key: &[u8],
    public_key: &[u8],
    candidate_revocation_keys: &[Vec<u8>],
) -> Result<(), MutationMaterialError> {
    if private_key.len() > MAX_KEY_DOCUMENT_BYTES
        || public_key.len() > MAX_KEY_DOCUMENT_BYTES
        || candidate_revocation_keys.len() > MAX_MUTATION_CANDIDATE_CERTIFICATES
        || candidate_revocation_keys
            .iter()
            .any(|candidate| candidate.len() > MAX_KEY_DOCUMENT_BYTES)
    {
        return Err(MutationMaterialError::ResourceLimit);
    }
    Ok(())
}

/// Maximum distance a replacement signature may advance past the caller's
/// reference time to remain newer than the statement it supersedes.
const MAX_SIGNATURE_TIME_ADVANCE_SECONDS: u64 = 300;

/// Resolves the instant a mutation's new signatures are created at.
///
/// OpenPGP self-signature selection is timestamp based, so a replacement must
/// be strictly newer than its predecessor. Returns `None` when the required
/// advance exceeds [`MAX_SIGNATURE_TIME_ADVANCE_SECONDS`] or does not fit a
/// V4 timestamp.
pub(crate) fn next_signature_time(
    reference_time: u64,
    newest_superseded: Option<u64>,
) -> Option<pgp::types::Timestamp> {
    let earliest = match newest_superseded {
        Some(time) => time.checked_add(1)?,
        None => reference_time,
    };
    let resolved = reference_time.max(earliest);
    if resolved.saturating_sub(reference_time) > MAX_SIGNATURE_TIME_ADVANCE_SECONDS {
        return None;
    }
    u32::try_from(resolved)
        .ok()
        .map(pgp::types::Timestamp::from_secs)
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
            .any(|component| component.version() != KeyVersion::V4)
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
    pub(crate) fn finalize<T, E>(
        &self,
        mutated: &PublicCertificatePacketSet,
        mutation_time: u64,
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
        let policy =
            validate_certificate(&canonical.semantic, &candidates, mutation_time, &mut budget)?;
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
        let private_key_armored =
            armor_key_packets_zeroizing(&private_packets, BlockType::PrivateKey)?;
        // Mutation output feeds local key state. Keep unusable components that
        // the operation did not target; explicit external export applies the
        // stricter transferable-component classification separately.
        let public_key_armored = armor_key_packets(public_bytes, BlockType::PublicKey)?;
        Ok((
            MutationOutput {
                key_material: KeyMaterial {
                    private_key_armored: private_key_armored.to_vec(),
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
