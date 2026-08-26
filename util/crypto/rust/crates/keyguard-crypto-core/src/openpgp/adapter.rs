//! Native protocol adapter for OpenPGP operations.
//!
//! The crate-level dispatcher enters the OpenPGP implementation through this
//! module.  It preserves the stable native error classification while keeping
//! packet, certificate, policy, and workflow modules independent of the
//! generic session registry.

use crate::primitives::PrimitiveError;

use self::wire::{
    OpenPgpClearSignStreamOpenRequest, OpenPgpClearVerifyStreamOpenRequest, OpenPgpDecryptRequest,
    OpenPgpDecryptStreamOpenRequest, OpenPgpDetachedSignStreamOpenRequest,
    OpenPgpDetachedVerifyStreamOpenRequest, OpenPgpEncryptRequest, OpenPgpEncryptStreamOpenRequest,
    OpenPgpKeyGenerateRequest, OpenPgpKeyImportRequest, OpenPgpSignRequest,
};

use super::{error::OpenPgpWriteError, message};

mod agent;
mod certificate;
pub(in crate::openpgp) mod key;
mod mutation;
mod read;
pub(crate) mod wire;
pub(in crate::openpgp) mod write;

pub(crate) use agent::{decrypt as agent_decrypt, sign as agent_sign};
pub(crate) use mutation::{
    reconcile_certificate_material, reconcile_certificate_material_v2, replace_user_id,
    revoke_user_id, update_expiration,
};
pub(crate) use read::{parse_public_key, resolve_metadata, verify};

pub(crate) fn generate_key(request: OpenPgpKeyGenerateRequest) -> Result<Vec<u8>, PrimitiveError> {
    key::generate(request).map_err(write_error)
}

pub(crate) fn import_key(request: OpenPgpKeyImportRequest) -> Result<Vec<u8>, PrimitiveError> {
    key::import(request).map_err(write_error)
}

pub(crate) fn sign(request: OpenPgpSignRequest) -> Result<Vec<u8>, PrimitiveError> {
    write::sign(request).map_err(write_error)
}

pub(crate) fn encrypt(request: OpenPgpEncryptRequest) -> Result<Vec<u8>, PrimitiveError> {
    write::encrypt(request).map_err(write_error)
}

pub(crate) fn decrypt(request: OpenPgpDecryptRequest) -> Result<Vec<u8>, PrimitiveError> {
    write::decrypt(request).map_err(write_error)
}

fn read_error(error: message::OpenPgpReadError) -> PrimitiveError {
    match error {
        message::OpenPgpReadError::InvalidArgument => PrimitiveError::InvalidArgument,
        message::OpenPgpReadError::ResourceLimit => PrimitiveError::ResourceLimit,
        message::OpenPgpReadError::Internal => PrimitiveError::CryptoFailure,
    }
}

fn write_error(error: OpenPgpWriteError) -> PrimitiveError {
    match error {
        OpenPgpWriteError::InvalidArgument => PrimitiveError::InvalidArgument,
        OpenPgpWriteError::MissingKey => PrimitiveError::NoUsableKey,
        OpenPgpWriteError::UnsupportedKeyVersion(_) => PrimitiveError::UnsupportedKeyVersion,
        OpenPgpWriteError::AuthenticationFailed => PrimitiveError::AuthenticationFailed,
        OpenPgpWriteError::ResourceLimit => PrimitiveError::ResourceLimit,
        OpenPgpWriteError::CryptoFailure => PrimitiveError::CryptoFailure,
        OpenPgpWriteError::Internal => PrimitiveError::Internal,
        OpenPgpWriteError::Panic => PrimitiveError::Panic,
    }
}

/// An active OpenPGP streaming workflow.
///
/// Finalization consumes this value, so a finalized workflow cannot be
/// updated or finalized for a second time.  The outer registry is responsible
/// for generation-tagged handle reuse and idempotent close behavior.
pub(crate) enum OpenPgpSession {
    DetachedVerify(Box<message::DetachedVerificationSession>),
    ClearVerify(Box<message::ClearVerificationSession>),
    DetachedSign(Box<message::DetachedSigningSession>),
    ClearSign(Box<message::ClearSigningSession>),
    Encrypt(Box<message::OpenPgpEncryptionSession>),
    Decrypt(Box<message::OpenPgpDecryptionSession>),
}

impl OpenPgpSession {
    pub(crate) fn detached_verify(
        request: OpenPgpDetachedVerifyStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::DetachedVerificationSession::open(read::detached_verify_input(request))
            .map(|session| Self::DetachedVerify(Box::new(session)))
            .map_err(stream_read_error)
    }

    pub(crate) fn clear_verify(
        request: OpenPgpClearVerifyStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::ClearVerificationSession::open(read::clear_verify_input(request))
            .map(|session| Self::ClearVerify(Box::new(session)))
            .map_err(stream_read_error)
    }

    pub(crate) fn detached_sign(
        request: OpenPgpDetachedSignStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::DetachedSigningSession::open(write::detached_sign_input(request))
            .map(|session| Self::DetachedSign(Box::new(session)))
            .map_err(write_error)
    }

    pub(crate) fn clear_sign(
        request: OpenPgpClearSignStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::ClearSigningSession::open(write::clear_sign_input(request))
            .map(|session| Self::ClearSign(Box::new(session)))
            .map_err(write_error)
    }

    pub(crate) fn encrypt(
        request: OpenPgpEncryptStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::OpenPgpEncryptionSession::open(write::encrypt_stream_input(request))
            .map(|session| Self::Encrypt(Box::new(session)))
            .map_err(write_error)
    }

    pub(crate) fn decrypt(
        request: OpenPgpDecryptStreamOpenRequest,
    ) -> Result<Self, PrimitiveError> {
        message::OpenPgpDecryptionSession::open(write::decrypt_stream_input(request))
            .map(|session| Self::Decrypt(Box::new(session)))
            .map_err(write_error)
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
        match self {
            Self::DetachedVerify(session) => {
                session.update(data).map_err(stream_read_error)?;
                Ok(Vec::new())
            }
            Self::ClearVerify(session) => session.update(data).map_err(stream_read_error),
            Self::DetachedSign(session) => {
                session.update(data).map_err(write_error)?;
                Ok(Vec::new())
            }
            Self::ClearSign(session) => session.update(data).map_err(write_error),
            Self::Encrypt(session) => session.update(data).map_err(write_error),
            Self::Decrypt(session) => session.update(data).map_err(write_error),
        }
    }

    pub(crate) fn finish(self) -> Result<Vec<u8>, PrimitiveError> {
        match self {
            Self::DetachedVerify(session) => (*session)
                .finish()
                .map(read::encode_verification)
                .map_err(stream_read_error),
            Self::ClearVerify(session) => (*session)
                .finish()
                .map(read::encode_clear_verification)
                .map_err(stream_read_error),
            Self::DetachedSign(session) => (*session).finish().map_err(write_error),
            Self::ClearSign(session) => (*session).finish().map_err(write_error),
            Self::Encrypt(session) => (*session)
                .finish()
                .map(write::encode_encrypt_final)
                .map_err(write_error),
            Self::Decrypt(session) => (*session)
                .finish()
                .map(write::encode_decrypt_final)
                .map_err(write_error),
        }
    }
}

/// Unlike the one-shot [`read_error`] projection, streaming keeps `Internal`
/// failures classified as internal instead of folding them into
/// `CryptoFailure`.
fn stream_read_error(error: message::OpenPgpReadError) -> PrimitiveError {
    match error {
        message::OpenPgpReadError::InvalidArgument => PrimitiveError::InvalidArgument,
        message::OpenPgpReadError::ResourceLimit => PrimitiveError::ResourceLimit,
        message::OpenPgpReadError::Internal => PrimitiveError::Internal,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_error_projection_preserves_native_classes() {
        assert_eq!(
            read_error(message::OpenPgpReadError::InvalidArgument),
            PrimitiveError::InvalidArgument
        );
        assert_eq!(
            read_error(message::OpenPgpReadError::ResourceLimit),
            PrimitiveError::ResourceLimit
        );
        assert_eq!(
            read_error(message::OpenPgpReadError::Internal),
            PrimitiveError::CryptoFailure
        );
    }

    #[test]
    fn write_error_projection_preserves_native_classes() {
        let cases = [
            (
                OpenPgpWriteError::InvalidArgument,
                PrimitiveError::InvalidArgument,
            ),
            (OpenPgpWriteError::MissingKey, PrimitiveError::NoUsableKey),
            (
                OpenPgpWriteError::UnsupportedKeyVersion(3),
                PrimitiveError::UnsupportedKeyVersion,
            ),
            (
                OpenPgpWriteError::AuthenticationFailed,
                PrimitiveError::AuthenticationFailed,
            ),
            (
                OpenPgpWriteError::ResourceLimit,
                PrimitiveError::ResourceLimit,
            ),
            (
                OpenPgpWriteError::CryptoFailure,
                PrimitiveError::CryptoFailure,
            ),
            (OpenPgpWriteError::Internal, PrimitiveError::Internal),
            (OpenPgpWriteError::Panic, PrimitiveError::Panic),
        ];

        for (input, expected) in cases {
            assert_eq!(write_error(input), expected);
        }
    }

    #[test]
    fn streaming_read_projection_keeps_internal_failures_internal() {
        assert_eq!(
            stream_read_error(message::OpenPgpReadError::Internal),
            PrimitiveError::Internal
        );
    }
}
