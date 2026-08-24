//! Protocol-independent inputs and results for OpenPGP write workflows.
//!
//! Secret key buffers cross into the workflow under zeroizing ownership.
//! Protobuf defaults, enum numbers, optional message fields, and final payload
//! encoding belong exclusively to the native adapter.

use zeroize::Zeroizing;

pub(in crate::openpgp) use crate::openpgp::message::read::Verification;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::openpgp) enum SignKind {
    Unspecified,
    ClearText,
    Detached,
}

pub(in crate::openpgp) struct SignInput {
    pub(in crate::openpgp) kind: SignKind,
    pub(in crate::openpgp) content: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) private_key: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) preferred_fingerprint: String,
    pub(in crate::openpgp) armored: bool,
    pub(in crate::openpgp) signature_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) candidate_revocation_keys: Vec<Vec<u8>>,
}

pub(in crate::openpgp) struct DetachedSignInput {
    pub(in crate::openpgp) private_key: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) preferred_fingerprint: String,
    pub(in crate::openpgp) armored: bool,
    pub(in crate::openpgp) signature_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) candidate_revocation_keys: Vec<Vec<u8>>,
}

pub(in crate::openpgp) struct ClearSignInput {
    pub(in crate::openpgp) private_key: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) preferred_fingerprint: String,
    pub(in crate::openpgp) signature_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) candidate_revocation_keys: Vec<Vec<u8>>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::openpgp) enum ProtectionMode {
    SeipdV1Mdc,
    GnupgOcb,
    SeipdV2Aead,
}

pub(in crate::openpgp) struct EncryptInput {
    pub(in crate::openpgp) content: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) public_keys: Vec<Vec<u8>>,
    pub(in crate::openpgp) signing_private_key: Option<Zeroizing<Vec<u8>>>,
    pub(in crate::openpgp) preferred_signing_fingerprint: String,
    pub(in crate::openpgp) file_name: String,
    pub(in crate::openpgp) armored: bool,
    pub(in crate::openpgp) literal_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) enable_compression: bool,
    pub(in crate::openpgp) candidate_revocation_keys: Vec<Vec<u8>>,
}

pub(in crate::openpgp) struct EncryptStreamInput {
    pub(in crate::openpgp) public_keys: Vec<Vec<u8>>,
    pub(in crate::openpgp) signing_private_key: Option<Zeroizing<Vec<u8>>>,
    pub(in crate::openpgp) preferred_signing_fingerprint: String,
    pub(in crate::openpgp) file_name: String,
    pub(in crate::openpgp) armored: bool,
    pub(in crate::openpgp) literal_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) enable_compression: bool,
    pub(in crate::openpgp) candidate_revocation_keys: Vec<Vec<u8>>,
}

#[derive(Debug, Eq, PartialEq)]
pub(in crate::openpgp) struct EncryptionResult {
    pub(in crate::openpgp) data: Vec<u8>,
    pub(in crate::openpgp) protection_mode: ProtectionMode,
}

pub(in crate::openpgp) struct DecryptInput {
    pub(in crate::openpgp) content: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) private_keys: Vec<Zeroizing<Vec<u8>>>,
    pub(in crate::openpgp) verification_public_keys: Vec<Vec<u8>>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) allow_signed_only: bool,
}

pub(in crate::openpgp) struct DecryptStreamInput {
    pub(in crate::openpgp) private_keys: Vec<Zeroizing<Vec<u8>>>,
    pub(in crate::openpgp) verification_public_keys: Vec<Vec<u8>>,
    pub(in crate::openpgp) reference_time_epoch_seconds: Option<u64>,
    pub(in crate::openpgp) allow_signed_only: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::openpgp) enum DecryptionWarning {
    WeakRsaKey,
    ElgamalKey,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(in crate::openpgp) struct LiteralMetadata {
    pub(in crate::openpgp) file_name: Vec<u8>,
    pub(in crate::openpgp) format: u32,
    pub(in crate::openpgp) modification_time_epoch_seconds: u64,
    pub(in crate::openpgp) original_size: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(in crate::openpgp) struct DecryptionResult {
    pub(in crate::openpgp) data: Zeroizing<Vec<u8>>,
    pub(in crate::openpgp) verification: Option<Verification>,
    pub(in crate::openpgp) metadata: Option<LiteralMetadata>,
    pub(in crate::openpgp) encrypted: bool,
    pub(in crate::openpgp) declared_charset: Option<String>,
    pub(in crate::openpgp) decryption_key_fingerprint: Option<String>,
    pub(in crate::openpgp) warnings: Vec<DecryptionWarning>,
}
