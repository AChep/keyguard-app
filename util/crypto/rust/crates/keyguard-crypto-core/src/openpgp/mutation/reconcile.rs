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
    CanonicalCertificate, CertificateMergeError, MutationMaterialError, SecretCertificateOverlay,
    SecretOverlayMergeError, armor_key_packets, canonicalize_public_certificate_material,
    merge_public_certificate_material_documents, merge_secret_certificate_overlays,
    normalize_expected_fingerprint, project_secret_certificate, rebuild_secret_certificate,
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
    pub(crate) public_certificate: Vec<u8>,
    pub(crate) private_certificate: Option<Vec<u8>>,
    pub(crate) primary_fingerprint: String,
    pub(crate) existing_public_contributed: bool,
    pub(crate) incoming_public_contributed: bool,
    pub(crate) existing_secret_contributed: bool,
    pub(crate) incoming_secret_contributed: bool,
}

impl Drop for CertificateMaterialReconcileSuccess {
    fn drop(&mut self) {
        if let Some(private) = self.private_certificate.as_mut() {
            private.zeroize();
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialInputError {
    EmptyCertificate,
    MalformedCertificate,
    UnsupportedKeyVersion,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialPairError {
    MissingMaterial,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
    InvalidRebuiltOutput,
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
    fingerprint: String,
}

struct SecretMaterial {
    projection: PublicMaterial,
    overlay: SecretCertificateOverlay,
}

pub(crate) fn reconcile_certificate_material_request(
    request: CertificateMaterialReconcileInput,
) -> Result<CertificateMaterialReconcileSuccess, ReconcileError> {
    reconcile_certificate_material(&request)
}

fn reconcile_certificate_material(
    request: &CertificateMaterialReconcileInput,
) -> Result<CertificateMaterialReconcileSuccess, ReconcileError> {
    let expected_fingerprint =
        normalize_expected_fingerprint(&request.expected_primary_fingerprint)
            .ok_or(ReconcileError::Pair(MaterialPairError::FingerprintMismatch))?;
    let existing = validate_public_input(
        request.existing_public_certificate.as_deref(),
        &expected_fingerprint,
    );
    let incoming = validate_public_input(
        request.incoming_public_certificate.as_deref(),
        &expected_fingerprint,
    );
    let existing_secret = validate_secret_input(
        request.existing_secret_certificate.as_deref(),
        &expected_fingerprint,
    );
    let incoming_secret = validate_secret_input(
        request.incoming_secret_certificate.as_deref(),
        &expected_fingerprint,
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
    if merged.fingerprint != expected_fingerprint {
        return Err(ReconcileError::Pair(MaterialPairError::FingerprintMismatch));
    }
    let existing_public_contributed = existing_side.as_ref().is_some_and(|_| {
        incoming_side
            .as_ref()
            .is_none_or(|incoming| merged.bytes != incoming.bytes)
    });
    let incoming_public_contributed = incoming_side.as_ref().is_some_and(|_| {
        existing_side
            .as_ref()
            .is_none_or(|existing| merged.bytes != existing.bytes)
    });

    if merged.bytes.is_empty() {
        return Err(ReconcileError::Pair(
            MaterialPairError::InvalidRebuiltOutput,
        ));
    }
    let public_certificate = armor_key_packets(&merged.bytes, BlockType::PublicKey)
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

    Ok(CertificateMaterialReconcileSuccess {
        public_certificate,
        private_certificate,
        primary_fingerprint: merged.fingerprint,
        existing_public_contributed,
        incoming_public_contributed,
        existing_secret_contributed: merged_secret
            .as_ref()
            .is_some_and(|secret| secret.existing_contributed),
        incoming_secret_contributed: merged_secret
            .as_ref()
            .is_some_and(|secret| secret.incoming_contributed),
    })
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
) -> Result<Option<PublicMaterial>, InputValidationFailure> {
    let Some(data) = data else {
        return Ok(None);
    };
    if data.is_empty() || data.iter().all(u8::is_ascii_whitespace) {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::EmptyCertificate,
        ));
    }
    let canonical =
        canonicalize_public_certificate_material(data).map_err(map_public_input_error)?;
    if canonical.fingerprint != expected_fingerprint {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::FingerprintMismatch,
        ));
    }
    Ok(Some(public_material(canonical)))
}

fn validate_secret_input(
    data: Option<&[u8]>,
    expected_fingerprint: &str,
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
    let canonical =
        canonicalize_public_certificate_material(&projection).map_err(map_public_input_error)?;
    if canonical.fingerprint != expected_fingerprint {
        return Err(InputValidationFailure::Invalid(
            MaterialInputError::FingerprintMismatch,
        ));
    }
    Ok(Some(SecretMaterial {
        projection: public_material(canonical),
        overlay,
    }))
}

fn public_material(canonical: CanonicalCertificate) -> PublicMaterial {
    PublicMaterial {
        // Reconciliation is vault/device local state, not an external
        // transferable export. Preserve bare components for later repair while
        // retaining the existing public-projection privacy filters.
        bytes: canonical.local_public_bytes,
        retained_bytes: canonical.retained_bytes,
        fingerprint: canonical.fingerprint,
    }
}

fn build_side(
    public: Option<&PublicMaterial>,
    secret_projection: Option<&PublicMaterial>,
) -> Result<Option<PublicMaterial>, ReconcileError> {
    match (public, secret_projection) {
        (None, None) => Ok(None),
        (Some(value), None) | (None, Some(value)) => Ok(Some(PublicMaterial {
            bytes: value.bytes.clone(),
            retained_bytes: value.retained_bytes.clone(),
            fingerprint: value.fingerprint.clone(),
        })),
        (Some(public), Some(secret)) => {
            merge_documents(&[&public.retained_bytes, &secret.retained_bytes]).map(Some)
        }
    }
}

fn merge_documents(documents: &[&[u8]]) -> Result<PublicMaterial, ReconcileError> {
    merge_public_certificate_material_documents(documents)
        .map(public_material)
        .map_err(map_pair_merge_error)
}

fn rebuild_and_validate_private(
    public: &PublicMaterial,
    overlay: &SecretCertificateOverlay,
) -> Result<Vec<u8>, ReconcileError> {
    let private_packets = rebuild_secret_certificate(&public.retained_bytes, overlay)
        .map_err(map_output_material_error)?;
    let (projection, _) =
        project_secret_certificate(&private_packets).map_err(map_output_material_error)?;
    let canonical_projection =
        canonicalize_public_certificate_material(&projection).map_err(|error| match error {
            CertificateMergeError::Internal => ReconcileError::Internal,
            CertificateMergeError::Malformed
            | CertificateMergeError::UnsupportedKeyVersion
            | CertificateMergeError::ComponentCollision
            | CertificateMergeError::ResourceLimit => {
                ReconcileError::Pair(MaterialPairError::InvalidRebuiltOutput)
            }
        })?;
    if canonical_projection.fingerprint != public.fingerprint
        || canonical_projection.local_public_bytes != public.bytes
        || canonical_projection.retained_bytes != public.retained_bytes
    {
        return Err(ReconcileError::Pair(
            MaterialPairError::InvalidRebuiltOutput,
        ));
    }
    armor_key_packets(&private_packets, BlockType::PrivateKey).map_err(map_output_material_error)
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
        | MutationMaterialError::SignatureVerificationFailed => {
            ReconcileError::Pair(MaterialPairError::InvalidRebuiltOutput)
        }
        MutationMaterialError::InternalFailure => ReconcileError::Internal,
    }
}

#[cfg(test)]
mod tests;
