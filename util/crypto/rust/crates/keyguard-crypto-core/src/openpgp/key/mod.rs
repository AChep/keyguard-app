//! OpenPGP key generation and import operation facade.
//!
//! Generation and import are independent workflows. Both preserve the stable
//! native request/result contract while sharing only concrete crypto and
//! packet-preservation primitives.

mod generation;
mod import;
mod model;

pub(in crate::openpgp) use generation::generate_key;
pub(in crate::openpgp) use import::import_key;
pub(in crate::openpgp) use model::{
    KeyGenerationInput, KeyImportFailureReason, KeyImportInput, KeyImportResult, KeyKind,
};

#[cfg(test)]
pub(in crate::openpgp) use generation::{
    encode_key_material, generate_rsa_certificate_for_test, subkey_binding_signature,
};
#[cfg(test)]
pub(in crate::openpgp) use import::{armor_key_packets, import_secret_packet_public_len};
