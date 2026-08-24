//! Policy-qualified projection of a certificate's stable component index.
//!
//! The resulting values are protocol independent. Policy decides which
//! designated-revoker declarations are authenticated; the certificate layer
//! supplies structural component metadata; the adapter assigns wire values.

use pgp::types::KeyDetails;

use crate::openpgp::{
    certificate::{
        AgentOperation, CanonicalCertificate, CertificateIndex, KeyComponentIndex,
        KeyComponentRole, LegacyDesignatedRevoker, PublicComponent,
    },
    crypto::{algorithm_name, keygrip, supports_decryption_key, supports_signing_key},
    format::hex_upper,
};

use super::ValidatedCertificate;

pub(in crate::openpgp) fn certificate_index(
    policy: &ValidatedCertificate<'_>,
    certificate: &CanonicalCertificate,
    secret_fingerprints: &[String],
) -> CertificateIndex {
    let components = certificate
        .components
        .iter()
        .map(|component| {
            let role = match component {
                PublicComponent::Primary(_) => KeyComponentRole::Primary,
                PublicComponent::Subkey(_) => KeyComponentRole::Subkey,
            };
            component_index(component, role, secret_fingerprints)
        })
        .collect();
    let legacy_designated_revokers = policy
        .legacy_designated_revokers()
        .map(|revoker| LegacyDesignatedRevoker {
            public_key_algorithm_id: u32::from(revoker.algorithm),
            fingerprint: hex_upper(&revoker.fingerprint),
            key_class: u32::from(revoker.key_class),
            sensitive: revoker.key_class & 0x40 != 0,
        })
        .collect();
    CertificateIndex {
        primary_fingerprint: certificate.fingerprint.clone(),
        components,
        legacy_designated_revokers,
    }
}

fn component_index(
    component: &PublicComponent,
    role: KeyComponentRole,
    secret_fingerprints: &[String],
) -> KeyComponentIndex {
    let fingerprint = component.fingerprint_hex();
    let keygrips = keygrip(component.public_params()).into_iter().collect();
    let mut agent_operations = Vec::with_capacity(2);
    if supports_signing_key(component.algorithm(), component.public_params()) {
        agent_operations.push(AgentOperation::Sign);
    }
    if supports_decryption_key(component.algorithm(), component.public_params()) {
        agent_operations.push(AgentOperation::Decrypt);
    }
    KeyComponentIndex {
        fingerprint: fingerprint.clone(),
        role,
        public_key_algorithm_id: u32::from(u8::from(component.algorithm())),
        algorithm: algorithm_name(component.algorithm()),
        keygrips,
        stored_secret_material: secret_fingerprints.contains(&fingerprint),
        agent_operations,
    }
}
