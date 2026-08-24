//! Protobuf conversion for OpenPGP signing, encryption, and decryption.
//!
//! Workflow inputs own secret buffers through `Zeroizing`; workflow outputs
//! remain typed until this module assigns protobuf enum numbers, optional
//! fields, and one-shot versus streaming-final envelopes.

use zeroize::Zeroizing;

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        error::OpenPgpWriteError,
        message::{
            self, ClearSignInput, DecryptInput, DecryptStreamInput, DecryptionResult,
            DetachedSignInput, EncryptInput, EncryptStreamInput, EncryptionResult, LiteralMetadata,
            ProtectionMode, SignInput, SignKind,
        },
    },
};

use super::{
    read,
    wire::{
        Message as _, OpenPgpClearSignStreamOpenRequest, OpenPgpDecryptFinal,
        OpenPgpDecryptRequest, OpenPgpDecryptResult, OpenPgpDecryptStreamOpenRequest,
        OpenPgpDecryptionWarning, OpenPgpDetachedSignStreamOpenRequest, OpenPgpEncryptFinal,
        OpenPgpEncryptRequest, OpenPgpEncryptResult, OpenPgpEncryptStreamOpenRequest,
        OpenPgpLiteralMetadata as WireLiteralMetadata, OpenPgpProtectionMode, OpenPgpSignKind,
        OpenPgpSignRequest,
    },
};

pub(in crate::openpgp) fn sign(
    mut request: OpenPgpSignRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let kind = match OpenPgpSignKind::try_from(request.kind) {
        Ok(OpenPgpSignKind::ClearText) => SignKind::ClearText,
        Ok(OpenPgpSignKind::Detached) => SignKind::Detached,
        Ok(OpenPgpSignKind::Unspecified) | Err(_) => SignKind::Unspecified,
    };
    message::sign_request(SignInput {
        kind,
        content: Zeroizing::new(std::mem::take(&mut request.content)),
        private_key: Zeroizing::new(std::mem::take(&mut request.private_key)),
        preferred_fingerprint: std::mem::take(&mut request.preferred_fingerprint),
        armored: request.armored,
        signature_time_epoch_seconds: request.signature_time_epoch_seconds,
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    })
}

pub(in crate::openpgp) fn detached_sign_input(
    mut request: OpenPgpDetachedSignStreamOpenRequest,
) -> DetachedSignInput {
    DetachedSignInput {
        private_key: Zeroizing::new(std::mem::take(&mut request.private_key)),
        preferred_fingerprint: std::mem::take(&mut request.preferred_fingerprint),
        armored: request.armored,
        signature_time_epoch_seconds: request.signature_time_epoch_seconds,
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    }
}

pub(in crate::openpgp) fn clear_sign_input(
    mut request: OpenPgpClearSignStreamOpenRequest,
) -> ClearSignInput {
    ClearSignInput {
        private_key: Zeroizing::new(std::mem::take(&mut request.private_key)),
        preferred_fingerprint: std::mem::take(&mut request.preferred_fingerprint),
        signature_time_epoch_seconds: request.signature_time_epoch_seconds,
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    }
}

pub(in crate::openpgp) fn encrypt(
    mut request: OpenPgpEncryptRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let result = message::encrypt_request(EncryptInput {
        content: Zeroizing::new(std::mem::take(&mut request.content)),
        public_keys: std::mem::take(&mut request.public_keys),
        signing_private_key: request.signing_private_key.take().map(Zeroizing::new),
        preferred_signing_fingerprint: std::mem::take(&mut request.preferred_signing_fingerprint),
        file_name: std::mem::take(&mut request.file_name),
        armored: request.armored,
        literal_time_epoch_seconds: request.literal_time_epoch_seconds,
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        // `true` permits recipient-negotiated compression; it no longer
        // selects ZIP unconditionally.
        enable_compression: request.enable_compression.unwrap_or(true),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    })?;
    Ok(encode_encrypt_result(result))
}

pub(in crate::openpgp) fn encrypt_stream_input(
    mut request: OpenPgpEncryptStreamOpenRequest,
) -> EncryptStreamInput {
    EncryptStreamInput {
        public_keys: std::mem::take(&mut request.public_keys),
        signing_private_key: request.signing_private_key.take().map(Zeroizing::new),
        preferred_signing_fingerprint: std::mem::take(&mut request.preferred_signing_fingerprint),
        file_name: std::mem::take(&mut request.file_name),
        armored: request.armored,
        literal_time_epoch_seconds: request.literal_time_epoch_seconds,
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        // Keep the streaming default identical to one-shot encryption.
        enable_compression: request.enable_compression.unwrap_or(true),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    }
}

pub(in crate::openpgp) fn encode_encrypt_final(result: EncryptionResult) -> Vec<u8> {
    let result = encrypt_result_message(result);
    OpenPgpEncryptFinal {
        data: result.data,
        protection_mode: result.protection_mode,
    }
    .encode_to_vec()
}

fn encode_encrypt_result(result: EncryptionResult) -> Vec<u8> {
    encrypt_result_message(result).encode_to_vec()
}

/// Converts the workflow result once; the one-shot and streaming-final
/// envelopes carry the same field set.
fn encrypt_result_message(result: EncryptionResult) -> OpenPgpEncryptResult {
    OpenPgpEncryptResult {
        data: result.data,
        protection_mode: wire_protection_mode(result.protection_mode) as i32,
    }
}

pub(in crate::openpgp) fn decrypt(
    mut request: OpenPgpDecryptRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let result = message::decrypt_request(DecryptInput {
        content: Zeroizing::new(std::mem::take(&mut request.content)),
        private_keys: zeroizing_documents(std::mem::take(&mut request.private_keys)),
        verification_public_keys: std::mem::take(&mut request.verification_public_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        allow_signed_only: request.allow_signed_only.unwrap_or(false),
    })?;
    let encoded = encode_decrypt_result(result);
    if encoded.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    Ok(encoded)
}

pub(in crate::openpgp) fn decrypt_stream_input(
    mut request: OpenPgpDecryptStreamOpenRequest,
) -> DecryptStreamInput {
    DecryptStreamInput {
        private_keys: zeroizing_documents(std::mem::take(&mut request.private_keys)),
        verification_public_keys: std::mem::take(&mut request.verification_public_keys),
        reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        allow_signed_only: request.allow_signed_only.unwrap_or(false),
    }
}

pub(in crate::openpgp) fn encode_decrypt_final(result: DecryptionResult) -> Vec<u8> {
    // The generated one-shot message zeroizes on drop, so its fields are
    // taken rather than moved out.
    let mut result = decrypt_result_message(result);
    OpenPgpDecryptFinal {
        data: std::mem::take(&mut result.data),
        verification: result.verification.take(),
        metadata: result.metadata.take(),
        encrypted: result.encrypted,
        declared_charset: result.declared_charset.take(),
        decryption_key_fingerprint: result.decryption_key_fingerprint.take(),
        warnings: std::mem::take(&mut result.warnings),
    }
    .encode_to_vec()
}

fn encode_decrypt_result(result: DecryptionResult) -> Vec<u8> {
    decrypt_result_message(result).encode_to_vec()
}

/// Converts the workflow result once; the one-shot and streaming-final
/// envelopes carry the same field set.
fn decrypt_result_message(mut result: DecryptionResult) -> OpenPgpDecryptResult {
    OpenPgpDecryptResult {
        data: std::mem::take(&mut *result.data),
        verification: result.verification.map(read::verification),
        metadata: result.metadata.map(wire_literal_metadata),
        encrypted: result.encrypted,
        declared_charset: result.declared_charset,
        decryption_key_fingerprint: result.decryption_key_fingerprint,
        warnings: result
            .warnings
            .into_iter()
            .map(wire_decryption_warning)
            .map(i32::from)
            .collect(),
    }
}

const fn wire_decryption_warning(warning: message::DecryptionWarning) -> OpenPgpDecryptionWarning {
    match warning {
        message::DecryptionWarning::WeakRsaKey => OpenPgpDecryptionWarning::WeakRsaKey,
        message::DecryptionWarning::ElgamalKey => OpenPgpDecryptionWarning::ElgamalKey,
    }
}

fn zeroizing_documents(documents: Vec<Vec<u8>>) -> Vec<Zeroizing<Vec<u8>>> {
    documents.into_iter().map(Zeroizing::new).collect()
}

const fn wire_protection_mode(mode: ProtectionMode) -> OpenPgpProtectionMode {
    match mode {
        ProtectionMode::SeipdV1Mdc => OpenPgpProtectionMode::SeipdV1Mdc,
        ProtectionMode::GnupgOcb => OpenPgpProtectionMode::GnupgOcb,
        ProtectionMode::SeipdV2Aead => OpenPgpProtectionMode::SeipdV2Aead,
    }
}

fn wire_literal_metadata(metadata: LiteralMetadata) -> WireLiteralMetadata {
    WireLiteralMetadata {
        file_name: metadata.file_name,
        format: metadata.format,
        modification_time_epoch_seconds: metadata.modification_time_epoch_seconds,
        original_size: metadata.original_size,
    }
}
