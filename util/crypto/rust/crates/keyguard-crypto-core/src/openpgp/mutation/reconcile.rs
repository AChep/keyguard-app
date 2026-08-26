//! Coherent OpenPGP public/secret certificate material reconciliation.
//!
//! Public and secret evidence are merged per logical side. Secret packets are
//! unioned by their public component fingerprint and then overlaid onto the
//! merged public result without decrypting or re-protecting them.
//!
//! The secret inputs to this vault/device reconciliation operation are trusted:
//! OpenPGP does not authenticate secret material as certificate evidence. The
//! separate public-certificate merger remains the boundary for keyserver and
//! other untrusted public-only material and never imports secret packets.

use pgp::armor::BlockType;
use zeroize::Zeroize;

use crate::openpgp::certificate::{
    CanonicalCertificate, CertificateMergeError, ExportClassificationBudget, MutationMaterialError,
    PublicCertificatePacketSet, SecretCertificateOverlay, SecretOverlayMergeError,
    SignatureRehomingBudget, armor_key_packets, merge_secret_certificate_overlays,
    normalize_expected_fingerprint, parse_public_certificate_packet_set_with_budget,
    project_secret_certificate, rebuild_secret_certificate,
    rebuild_transferable_secret_certificate,
};

pub(crate) struct CertificateMaterialReconcileInput {
    pub(crate) expected_primary_fingerprint: String,
    pub(crate) existing_public_certificate: Option<Vec<u8>>,
    pub(crate) incoming_public_certificate: Option<Vec<u8>>,
    pub(crate) existing_secret_certificate: Option<Vec<u8>>,
    pub(crate) incoming_secret_certificate: Option<Vec<u8>>,
}

impl Drop for CertificateMaterialReconcileInput {
    fn drop(&mut self) {
        if let Some(secret) = self.existing_secret_certificate.as_mut() {
            secret.zeroize();
        }
        if let Some(secret) = self.incoming_secret_certificate.as_mut() {
            secret.zeroize();
        }
    }
}

pub(crate) struct CertificateMaterialReconcileSuccess {
    /// V1 compatibility field containing local public material.
    pub(crate) public_certificate: Vec<u8>,
    /// Packet-preserving local public evidence for V2 persistence.
    pub(crate) local_public_material: Vec<u8>,
    /// V1 compatibility field containing local secret material.
    pub(crate) private_certificate: Option<Vec<u8>>,
    pub(crate) transferable_public_certificate: Option<Vec<u8>>,
    pub(crate) transferable_private_certificate: Option<Vec<u8>>,
    pub(crate) primary_fingerprint: String,
    pub(crate) existing_public_contributed: bool,
    pub(crate) incoming_public_contributed: bool,
    pub(crate) existing_secret_contributed: bool,
    pub(crate) incoming_secret_contributed: bool,
    pub(crate) contributions: CertificateMaterialContributions,
    pub(crate) withheld_reasons: Vec<MaterialWithheldReason>,
}

impl Drop for CertificateMaterialReconcileSuccess {
    fn drop(&mut self) {
        if let Some(private) = self.private_certificate.as_mut() {
            private.zeroize();
        }
        if let Some(private) = self.transferable_private_certificate.as_mut() {
            private.zeroize();
        }
    }
}

#[derive(Clone, Copy)]
pub(crate) struct CertificateMaterialContributions {
    pub(crate) existing_public: MaterialInputContribution,
    pub(crate) incoming_public: MaterialInputContribution,
    pub(crate) existing_secret: MaterialInputContribution,
    pub(crate) incoming_secret: MaterialInputContribution,
}

#[derive(Clone, Copy)]
pub(crate) struct MaterialInputContribution {
    pub(crate) present: bool,
    pub(crate) unique_public_evidence: bool,
    pub(crate) unique_secret_capability: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialWithheldReason {
    NoTransferablePublicCertificate,
    LocalPublicEvidence,
    SecretMaterialNotTransferable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialInputError {
    EmptyCertificate,
    MalformedCertificate,
    UnsupportedKeyVersion,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
    UnsupportedTskLayout,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialPairError {
    MissingMaterial,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
    InvalidRebuiltOutput,
    ConflictingSecretMaterial,
}

pub(crate) enum ReconcileError {
    InvalidInputs {
        existing_public: Option<MaterialInputError>,
        incoming_public: Option<MaterialInputError>,
        existing_secret: Option<MaterialInputError>,
        incoming_secret: Option<MaterialInputError>,
    },
    Pair(MaterialPairError),
    Internal,
}

struct PublicMaterial {
    bytes: Vec<u8>,
    retained_bytes: Vec<u8>,
    transferable_bytes: Option<Vec<u8>>,
    fingerprint: String,
}

struct SecretMaterial {
    projection: PublicCertificatePacketSet,
    overlay: SecretCertificateOverlay,
}

/// Cryptographic verification accounting owned by one reconciliation request.
/// Every stage borrows these values; no nested operation may reset them.
#[derive(Default)]
struct ReconcileWorkBudget {
    signature_rehoming: SignatureRehomingBudget,
    export_classification: ExportClassificationBudget,
}

#[cfg(test)]
impl ReconcileWorkBudget {
    fn with_request_limits(signature_rehoming: usize, export_classification: usize) -> Self {
        Self {
            signature_rehoming: SignatureRehomingBudget::with_request_limit(signature_rehoming),
            export_classification: ExportClassificationBudget::with_request_limit(
                export_classification,
            ),
        }
    }
}

pub(crate) fn reconcile_certificate_material_request(
    request: CertificateMaterialReconcileInput,
) -> Result<CertificateMaterialReconcileSuccess, ReconcileError> {
    reconcile_certificate_material(&request, &mut ReconcileWorkBudget::default())
}

fn reconcile_certificate_material(
    request: &CertificateMaterialReconcileInput,
    budget: &mut ReconcileWorkBudget,
) -> Result<CertificateMaterialReconcileSuccess, ReconcileError> {
    let expected_fingerprint =
        normalize_expected_fingerprint(&request.expected_primary_fingerprint)
            .ok_or(ReconcileError::Pair(MaterialPairError::FingerprintMismatch))?;
    let existing = validate_public_input(
        request.existing_public_certificate.as_deref(),
        &expected_fingerprint,
        &mut budget.signature_rehoming,
    );
    let incoming = validate_public_input(
        request.incoming_public_certificate.as_deref(),
        &expected_fingerprint,
        &mut budget.signature_rehoming,
    );
    let existing_secret = validate_secret_input(
        request.existing_secret_certificate.as_deref(),
        &expected_fingerprint,
        &mut budget.signature_rehoming,
    );
    let incoming_secret = validate_secret_input(
        request.incoming_secret_certificate.as_deref(),
        &expected_fingerprint,
        &mut budget.signature_rehoming,
    );
    if matches!(existing, Err(InputValidationFailure::Internal))
        || matches!(incoming, Err(InputValidationFailure::Internal))
        || matches!(existing_secret, Err(InputValidationFailure::Internal))
        || matches!(incoming_secret, Err(InputValidationFailure::Internal))
    {
        return Err(ReconcileError::Internal);
    }
    let existing_error = invalid_input(&existing);
    let incoming_error = invalid_input(&incoming);
    let existing_secret_error = invalid_input(&existing_secret);
    let incoming_secret_error = invalid_input(&incoming_secret);
    if existing_error.is_some()
        || incoming_error.is_some()
        || existing_secret_error.is_some()
        || incoming_secret_error.is_some()
    {
        return Err(ReconcileError::InvalidInputs {
            existing_public: existing_error,
            incoming_public: incoming_error,
            existing_secret: existing_secret_error,
            incoming_secret: incoming_secret_error,
        });
    }
    let Ok(existing) = existing else {
        return Err(ReconcileError::Internal);
    };
    let Ok(incoming) = incoming else {
        return Err(ReconcileError::Internal);
    };
    let Ok(existing_secret) = existing_secret else {
        return Err(ReconcileError::Internal);
    };
    let Ok(incoming_secret) = incoming_secret else {
        return Err(ReconcileError::Internal);
    };

    let public_inputs = [
        existing.as_ref(),
        incoming.as_ref(),
        existing_secret.as_ref().map(|secret| &secret.projection),
        incoming_secret.as_ref().map(|secret| &secret.projection),
    ];
    let input_present = public_inputs.map(|input| input.is_some());

    let existing_side = build_side(
        existing.as_ref(),
        existing_secret.as_ref().map(|secret| &secret.projection),
    )?;
    let incoming_side = build_side(
        incoming.as_ref(),
        incoming_secret.as_ref().map(|secret| &secret.projection),
    )?;
    let merged = build_side(existing_side.as_ref(), incoming_side.as_ref())?
        .ok_or(ReconcileError::Pair(MaterialPairError::MissingMaterial))?;
    if merged.fingerprint_hex() != expected_fingerprint {
        return Err(ReconcileError::Pair(MaterialPairError::FingerprintMismatch));
    }
    let unique_public_evidence = unique_public_evidence(&merged, public_inputs)?;
    let existing_public_contributed = existing_side.as_ref().is_some_and(|_| {
        incoming_side
            .as_ref()
            .is_none_or(|incoming| merged != *incoming)
    });
    let incoming_public_contributed = incoming_side.as_ref().is_some_and(|_| {
        existing_side
            .as_ref()
            .is_none_or(|existing| merged != *existing)
    });

    let merged = merged
        .finalize_with_export_budget(&[], &mut budget.export_classification)
        .map(public_material)
        .map_err(map_pair_merge_error)?;

    if merged.bytes.is_empty() {
        return Err(ReconcileError::Pair(
            MaterialPairError::InvalidRebuiltOutput,
        ));
    }
    let public_certificate = armor_key_packets(&merged.bytes, BlockType::PublicKey)
        .map_err(map_output_material_error)?;
    let local_public_material = armor_key_packets(&merged.retained_bytes, BlockType::PublicKey)
        .map_err(map_output_material_error)?;
    let transferable_public_certificate = merged
        .transferable_bytes
        .as_deref()
        .map(|bytes| armor_key_packets(bytes, BlockType::PublicKey))
        .transpose()
        .map_err(map_output_material_error)?;
    let merged_secret = merge_secret_certificate_overlays(
        existing_secret.map(|secret| secret.overlay),
        incoming_secret.map(|secret| secret.overlay),
    )
    .map_err(map_secret_overlay_merge_error)?;
    let private_certificate = merged_secret
        .as_ref()
        .map(|secret| rebuild_and_validate_private(&merged, &secret.overlay))
        .transpose()?;
    let transferable_private_certificate = merged_secret
        .as_ref()
        .map(|secret| rebuild_and_validate_transferable_private(&merged, &secret.overlay))
        .transpose()?
        .flatten();
    let mut withheld_reasons = Vec::new();
    match merged.transferable_bytes.as_deref() {
        None => withheld_reasons.push(MaterialWithheldReason::NoTransferablePublicCertificate),
        Some(transferable) if transferable != merged.retained_bytes => {
            withheld_reasons.push(MaterialWithheldReason::LocalPublicEvidence);
        }
        Some(_) => {}
    }
    if private_certificate.is_some()
        && private_certificate.as_deref() != transferable_private_certificate.as_deref()
    {
        withheld_reasons.push(MaterialWithheldReason::SecretMaterialNotTransferable);
    }
    let existing_secret_contributed = merged_secret
        .as_ref()
        .is_some_and(|secret| secret.existing_contributed);
    let incoming_secret_contributed = merged_secret
        .as_ref()
        .is_some_and(|secret| secret.incoming_contributed);

    Ok(CertificateMaterialReconcileSuccess {
        public_certificate,
        local_public_material,
        private_certificate,
        transferable_public_certificate,
        transferable_private_certificate,
        primary_fingerprint: merged.fingerprint,
        existing_public_contributed,
        incoming_public_contributed,
        existing_secret_contributed,
        incoming_secret_contributed,
        contributions: CertificateMaterialContributions {
            existing_public: MaterialInputContribution {
                present: input_present[0],
                unique_public_evidence: unique_public_evidence[0],
                unique_secret_capability: false,
            },
            incoming_public: MaterialInputContribution {
                present: input_present[1],
                unique_public_evidence: unique_public_evidence[1],
                unique_secret_capability: false,
            },
            existing_secret: MaterialInputContribution {
                present: input_present[2],
                unique_public_evidence: unique_public_evidence[2],
                unique_secret_capability: existing_secret_contributed,
            },
            incoming_secret: MaterialInputContribution {
                present: input_present[3],
                unique_public_evidence: unique_public_evidence[3],
                unique_secret_capability: incoming_secret_contributed,
            },
        },
        withheld_reasons,
    })
}

fn unique_public_evidence(
    merged: &PublicCertificatePacketSet,
    inputs: [Option<&PublicCertificatePacketSet>; 4],
) -> Result<[bool; 4], ReconcileError> {
    // Packet-set equality covers all retained evidence after deterministic
    // ordering, so omission analysis does not need to parse or classify the
    // same certificates again.
    let mut unique = [false; 4];
    for (omitted_index, input) in inputs.iter().enumerate() {
        if input.is_none() {
            continue;
        }
        let documents = inputs
            .iter()
            .enumerate()
            .filter_map(|(index, material)| (index != omitted_index).then_some(*material).flatten())
            .collect::<Vec<_>>();
        unique[omitted_index] = if documents.is_empty() {
            true
        } else {
            merge_packet_sets(&documents)? != *merged
        };
    }
    Ok(unique)
}

enum InputValidationFailure {
    Invalid(MaterialInputError),
    Internal,
}

fn invalid_input<T>(
    result: &Result<Option<T>, InputValidationFailure>,
) -> Option<MaterialInputError> {
    match result {
        Err(InputValidationFailure::Invalid(error)) => Some(*error),
        Ok(_) | Err(InputValidationFailure::Internal) => None,
    }
}

fn validate_public_input(
    data: Option<&[u8]>,
    expected_fingerprint: &str,
    budget: &mut SignatureRehomingBudget,
) -> Result<Option<PublicCertificatePacketSet>, InputValidationFailure> {
    let Some(data) = data else {
        return Ok(None);
    };
    if data.is_empty() || data.iter().all(u8::is_ascii_whitespace) {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::EmptyCertificate,
        ));
    }
    let certificate = parse_public_certificate_packet_set_with_budget(data, budget)
        .map_err(map_public_input_error)?;
    if certificate.fingerprint_hex() != expected_fingerprint {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::FingerprintMismatch,
        ));
    }
    Ok(Some(certificate))
}

fn validate_secret_input(
    data: Option<&[u8]>,
    expected_fingerprint: &str,
    budget: &mut SignatureRehomingBudget,
) -> Result<Option<SecretMaterial>, InputValidationFailure> {
    let Some(data) = data else {
        return Ok(None);
    };
    if data.is_empty() || data.iter().all(u8::is_ascii_whitespace) {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::EmptyCertificate,
        ));
    }
    let (projection, overlay) = project_secret_certificate(data).map_err(map_secret_input_error)?;
    let projection = parse_public_certificate_packet_set_with_budget(&projection, budget)
        .map_err(map_public_input_error)?;
    if projection.fingerprint_hex() != expected_fingerprint {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::FingerprintMismatch,
        ));
    }
    Ok(Some(SecretMaterial {
        projection,
        overlay,
    }))
}

fn public_material(canonical: CanonicalCertificate) -> PublicMaterial {
    let transferable_bytes = canonical.transferable.then_some(canonical.bytes);
    PublicMaterial {
        // Reconciliation is vault/device local state, not an external
        // transferable export. Preserve bare components for later repair while
        // retaining the existing public-projection privacy filters.
        bytes: canonical.local_public_bytes,
        retained_bytes: canonical.retained_bytes,
        transferable_bytes,
        fingerprint: canonical.fingerprint,
    }
}

fn build_side(
    public: Option<&PublicCertificatePacketSet>,
    secret_projection: Option<&PublicCertificatePacketSet>,
) -> Result<Option<PublicCertificatePacketSet>, ReconcileError> {
    match (public, secret_projection) {
        (None, None) => Ok(None),
        (Some(value), None) | (None, Some(value)) => merge_packet_sets(&[value]).map(Some),
        (Some(public), Some(secret)) => merge_packet_sets(&[public, secret]).map(Some),
    }
}

fn merge_packet_sets(
    certificates: &[&PublicCertificatePacketSet],
) -> Result<PublicCertificatePacketSet, ReconcileError> {
    let mut certificates = certificates.iter().copied();
    let mut merged = certificates
        .next()
        .ok_or(ReconcileError::Pair(MaterialPairError::MissingMaterial))?
        .clone();
    for certificate in certificates {
        merged
            .merge(certificate.clone())
            .map_err(map_pair_merge_error)?;
    }
    merged.sort_component_order();
    Ok(merged)
}

fn rebuild_and_validate_private(
    public: &PublicMaterial,
    overlay: &SecretCertificateOverlay,
) -> Result<Vec<u8>, ReconcileError> {
    let private_packets = rebuild_secret_certificate(&public.retained_bytes, overlay)
        .map_err(map_output_material_error)?;
    let (projection, _) =
        project_secret_certificate(&private_packets).map_err(map_output_material_error)?;
    // The rebuild starts from these canonical public packets and changes only
    // their key-packet secret suffixes. Projecting it must recover them exactly.
    if projection != public.retained_bytes {
        return Err(ReconcileError::Pair(
            MaterialPairError::InvalidRebuiltOutput,
        ));
    }
    armor_key_packets(&private_packets, BlockType::PrivateKey).map_err(map_output_material_error)
}

fn rebuild_and_validate_transferable_private(
    public: &PublicMaterial,
    overlay: &SecretCertificateOverlay,
) -> Result<Option<Vec<u8>>, ReconcileError> {
    let Some(transferable_public) = public.transferable_bytes.as_deref() else {
        return Ok(None);
    };
    let Some(private_packets) =
        rebuild_transferable_secret_certificate(transferable_public, overlay)
            .map_err(map_output_material_error)?
    else {
        return Ok(None);
    };
    let (projection, _) =
        project_secret_certificate(&private_packets).map_err(map_output_material_error)?;
    // Exact recovery is stronger than reparsing and re-canonicalizing the same
    // public evidence with another cryptographic work allowance.
    if projection != transferable_public {
        return Err(ReconcileError::Pair(
            MaterialPairError::InvalidRebuiltOutput,
        ));
    }
    armor_key_packets(&private_packets, BlockType::PrivateKey)
        .map(Some)
        .map_err(map_output_material_error)
}

fn map_public_input_error(error: CertificateMergeError) -> InputValidationFailure {
    match error {
        CertificateMergeError::Malformed => {
            InputValidationFailure::Invalid(MaterialInputError::MalformedCertificate)
        }
        CertificateMergeError::UnsupportedKeyVersion => {
            InputValidationFailure::Invalid(MaterialInputError::UnsupportedKeyVersion)
        }
        CertificateMergeError::ComponentCollision => {
            InputValidationFailure::Invalid(MaterialInputError::ComponentCollision)
        }
        CertificateMergeError::ResourceLimit => {
            InputValidationFailure::Invalid(MaterialInputError::ResourceLimit)
        }
        CertificateMergeError::Internal => InputValidationFailure::Internal,
    }
}

fn map_secret_input_error(error: MutationMaterialError) -> InputValidationFailure {
    match error {
        MutationMaterialError::MalformedKey => {
            InputValidationFailure::Invalid(MaterialInputError::MalformedCertificate)
        }
        MutationMaterialError::FingerprintMismatch => {
            InputValidationFailure::Invalid(MaterialInputError::ComponentCollision)
        }
        MutationMaterialError::UnsupportedKeyVersion => {
            InputValidationFailure::Invalid(MaterialInputError::UnsupportedKeyVersion)
        }
        MutationMaterialError::UnsupportedTskLayout => {
            InputValidationFailure::Invalid(MaterialInputError::UnsupportedTskLayout)
        }
        MutationMaterialError::ResourceLimit => {
            InputValidationFailure::Invalid(MaterialInputError::ResourceLimit)
        }
        MutationMaterialError::InternalFailure
        | MutationMaterialError::SignatureVerificationFailed => InputValidationFailure::Internal,
    }
}

fn map_pair_merge_error(error: CertificateMergeError) -> ReconcileError {
    match error {
        CertificateMergeError::ComponentCollision => {
            ReconcileError::Pair(MaterialPairError::ComponentCollision)
        }
        CertificateMergeError::ResourceLimit => {
            ReconcileError::Pair(MaterialPairError::ResourceLimit)
        }
        CertificateMergeError::Malformed | CertificateMergeError::UnsupportedKeyVersion => {
            ReconcileError::Pair(MaterialPairError::InvalidRebuiltOutput)
        }
        CertificateMergeError::Internal => ReconcileError::Internal,
    }
}

fn map_secret_overlay_merge_error(error: SecretOverlayMergeError) -> ReconcileError {
    match error {
        SecretOverlayMergeError::ComponentMismatch => {
            ReconcileError::Pair(MaterialPairError::ComponentCollision)
        }
        SecretOverlayMergeError::ConflictingSecretMaterial => {
            ReconcileError::Pair(MaterialPairError::ConflictingSecretMaterial)
        }
    }
}

fn map_output_material_error(error: MutationMaterialError) -> ReconcileError {
    match error {
        MutationMaterialError::ResourceLimit => {
            ReconcileError::Pair(MaterialPairError::ResourceLimit)
        }
        MutationMaterialError::MalformedKey
        | MutationMaterialError::FingerprintMismatch
        | MutationMaterialError::UnsupportedKeyVersion
        | MutationMaterialError::UnsupportedTskLayout
        | MutationMaterialError::SignatureVerificationFailed => {
            ReconcileError::Pair(MaterialPairError::InvalidRebuiltOutput)
        }
        MutationMaterialError::InternalFailure => ReconcileError::Internal,
    }
}

#[cfg(test)]
mod tests;
