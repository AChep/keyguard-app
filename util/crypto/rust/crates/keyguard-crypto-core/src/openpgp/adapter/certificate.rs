//! Shared protobuf projections for protocol-independent certificate values.

use crate::openpgp::certificate::{AgentOperation, CertificateIndex, KeyComponentRole};

use super::wire::{
    OpenPgpAgentOperation, OpenPgpCertificateIndexV2, OpenPgpKeyComponentIndexV2,
    OpenPgpKeyComponentRole, OpenPgpLegacyDesignatedRevokerV2,
};

pub(super) fn certificate_index(index: CertificateIndex) -> OpenPgpCertificateIndexV2 {
    OpenPgpCertificateIndexV2 {
        primary_fingerprint: index.primary_fingerprint,
        components: index
            .components
            .into_iter()
            .map(|component| OpenPgpKeyComponentIndexV2 {
                fingerprint: component.fingerprint,
                role: match component.role {
                    KeyComponentRole::Primary => OpenPgpKeyComponentRole::Primary,
                    KeyComponentRole::Subkey => OpenPgpKeyComponentRole::Subkey,
                } as i32,
                public_key_algorithm_id: component.public_key_algorithm_id,
                algorithm: component.algorithm,
                keygrips: component.keygrips,
                stored_secret_material: component.stored_secret_material,
                agent_operations: component
                    .agent_operations
                    .into_iter()
                    .map(|operation| match operation {
                        AgentOperation::Sign => OpenPgpAgentOperation::Sign,
                        AgentOperation::Decrypt => OpenPgpAgentOperation::Decrypt,
                    } as i32)
                    .collect(),
            })
            .collect(),
        legacy_designated_revokers: index
            .legacy_designated_revokers
            .into_iter()
            .map(|revoker| OpenPgpLegacyDesignatedRevokerV2 {
                public_key_algorithm_id: revoker.public_key_algorithm_id,
                fingerprint: revoker.fingerprint,
                key_class: revoker.key_class,
                sensitive: revoker.sensitive,
            })
            .collect(),
    }
}
