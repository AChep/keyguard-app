//! Protocol-independent certificate index values and structural projections.
//!
//! These values describe retained key components only.  Authentication,
//! revocation, and time-qualified decisions are supplied by the policy layer;
//! protobuf numbering and encoding belong to the protocol adapter.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum KeyComponentRole {
    Primary,
    Subkey,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum AgentOperation {
    Sign,
    Decrypt,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct KeyComponentIndex {
    pub(crate) fingerprint: String,
    pub(crate) role: KeyComponentRole,
    pub(crate) public_key_algorithm_id: u32,
    pub(crate) algorithm: String,
    pub(crate) keygrips: Vec<String>,
    pub(crate) stored_secret_material: bool,
    pub(crate) agent_operations: Vec<AgentOperation>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct LegacyDesignatedRevoker {
    pub(crate) public_key_algorithm_id: u32,
    pub(crate) fingerprint: String,
    pub(crate) key_class: u32,
    pub(crate) sensitive: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct CertificateIndex {
    pub(crate) primary_fingerprint: String,
    pub(crate) components: Vec<KeyComponentIndex>,
    pub(crate) legacy_designated_revokers: Vec<LegacyDesignatedRevoker>,
}
