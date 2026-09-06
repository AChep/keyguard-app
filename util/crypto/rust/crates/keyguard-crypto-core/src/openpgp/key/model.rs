//! Domain inputs and results for OpenPGP key workflows.
//!
//! Secret request buffers enter the workflow in zeroizing ownership. Protocol
//! enum numbers, optional message wrappers, and result oneofs stay in the
//! native adapter.

use zeroize::Zeroizing;

use crate::openpgp::certificate::KeyMaterial;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum KeyKind {
    Unspecified,
    LegacyEd25519X25519,
    Rsa,
}

pub(in crate::openpgp) struct KeyGenerationInput {
    pub(in crate::openpgp) kind: KeyKind,
    pub(in crate::openpgp) user_id: String,
    pub(in crate::openpgp) rsa_bits: u32,
    pub(in crate::openpgp) creation_time_epoch_seconds: u64,
    pub(in crate::openpgp) expiration_seconds: Option<u32>,
}

pub(in crate::openpgp) struct KeyImportInput {
    pub(in crate::openpgp) key_data: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) passphrase_utf8: Option<Zeroizing<Vec<u8>>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum KeyImportFailureReason {
    Empty,
    UnsupportedFormat,
    InvalidPassphrase,
    MalformedKey,
}

pub(in crate::openpgp) enum KeyImportResult {
    Success(KeyMaterial),
    NeedsPassphrase,
    Error(KeyImportFailureReason),
}
