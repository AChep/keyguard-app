//! Lossless OpenPGP certificate storage and target-specific certification.
//!
//! Certificate documents retain packet evidence independently of policy. The
//! policy layer may evaluate these values, but it does not own their framing or
//! serialization.

mod certification;
mod component;
mod identity;
mod index;
mod material;
mod model;

pub(crate) use certification::{
    UserIdCertificationBuilder, UserIdCertificationError, UserIdRevocationBuilder,
    existing_user_id_recertification_config, new_user_id_certification_config,
};
pub(crate) use identity::identity_id;
pub(crate) use index::{
    AgentOperation, CertificateIndex, KeyComponentIndex, KeyComponentRole, LegacyDesignatedRevoker,
};
pub(crate) use material::{
    KeyMaterial, MAX_MUTATION_CANDIDATE_CERTIFICATES, MaterialErrorSeverity, MutationMaterialError,
    ParsedSecretCertificate, SecretCertificateOverlay, SecretOverlayMergeError, armor_key_packets,
    armor_key_packets_zeroizing, is_gnu_dummy_secret_stub, merge_secret_certificate_overlays,
    parse_mutation_candidates, parse_secret_certificate, parse_single_secret,
    project_secret_certificate, rebuild_secret_certificate,
    rebuild_transferable_secret_certificate, serialize_packet_body,
};
pub(crate) use model::{
    CanonicalCertificate, CertificateAddition, CertificateMergeError, CertificateMutationError,
    CertificateSignatureOwner, ExportClassificationBudget, PublicCertificatePacketSet,
    SignatureRehomingBudget, local_public_certificate_preserving_framing,
    merge_public_certificate_packet_sets, normalize_expected_fingerprint,
    parse_public_certificate_packet_set_with_budget,
    parse_public_certificate_packet_sets_with_budget, parse_single_certificate_packet_set,
    raw_packet_is_exportable,
};

pub(crate) use component::PublicComponent;
#[cfg(test)]
pub(crate) use material::{filtered_tsk_fixture, parse_single_public};
#[cfg(test)]
pub(crate) use model::canonicalize_public_certificate;
