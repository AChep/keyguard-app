//! Shared OpenPGP operation failure categories.
//!
//! Workflow layers use this stable classification internally; the protocol
//! adapter remains responsible for projecting it onto native result codes.

use thiserror::Error;

use super::{crypto::secret::SecretKeyAdapterError, packet::RawPacketError};

/// Stable internal write-operation failure classification.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum OpenPgpWriteError {
    /// A request enum, key, message, or control value is malformed.
    #[error("invalid OpenPGP write request")]
    InvalidArgument,
    /// All supplied private candidates, or any strict recipient, uses v2/v3.
    #[error("unsupported legacy OpenPGP key version")]
    UnsupportedKeyVersion(u8),
    /// No policy-valid signing, recipient, or decryption component exists.
    #[error("no usable OpenPGP key")]
    MissingKey,
    /// An encrypted message failed MDC or AEAD authentication.
    #[error("OpenPGP authentication failed")]
    AuthenticationFailed,
    /// An explicit parser, allocation, or work bound was exceeded.
    #[error("OpenPGP write resource limit exceeded")]
    ResourceLimit,
    /// A cryptographic backend rejected the operation.
    #[error("OpenPGP cryptographic operation failed")]
    CryptoFailure,
    /// A worker or internal composition invariant failed.
    #[error("OpenPGP write operation failed")]
    Internal,
    /// A streaming worker panic was contained before crossing the native boundary.
    #[error("OpenPGP streaming worker panicked")]
    Panic,
}

/// Collapses an rPGP composition failure to the internal write class.
pub(in crate::openpgp) fn pgp_internal(_: pgp::errors::Error) -> OpenPgpWriteError {
    OpenPgpWriteError::Internal
}

impl From<RawPacketError> for OpenPgpWriteError {
    fn from(error: RawPacketError) -> Self {
        match error {
            RawPacketError::Malformed => Self::InvalidArgument,
            RawPacketError::ResourceLimit => Self::ResourceLimit,
        }
    }
}

impl From<SecretKeyAdapterError> for OpenPgpWriteError {
    fn from(error: SecretKeyAdapterError) -> Self {
        match error {
            SecretKeyAdapterError::InvalidArgument => Self::InvalidArgument,
            SecretKeyAdapterError::Internal => Self::Internal,
        }
    }
}
