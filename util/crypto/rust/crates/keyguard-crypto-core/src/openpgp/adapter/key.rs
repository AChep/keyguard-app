//! Protobuf conversion for OpenPGP key generation and import.
//!
//! Generated enum values, optional wrappers, import oneofs, and byte encoding
//! terminate here. Secret request and result fields move through zeroizing
//! ownership before entering or leaving the domain workflow.

use zeroize::Zeroizing;

use crate::openpgp::{
    certificate::KeyMaterial,
    error::OpenPgpWriteError,
    key::{
        self, KeyGenerationInput, KeyImportFailureReason, KeyImportInput, KeyImportResult, KeyKind,
    },
};

use super::wire::{
    Message as _, OpenPgpKeyGenerateRequest, OpenPgpKeyImportError, OpenPgpKeyImportErrorReason,
    OpenPgpKeyImportNeedsPassphrase, OpenPgpKeyImportRequest, OpenPgpKeyImportResult,
    OpenPgpKeyImportSuccess, OpenPgpKeyKind, OpenPgpKeyMaterial, open_pgp_key_import_result,
};

pub(in crate::openpgp) fn generate(
    request: OpenPgpKeyGenerateRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let kind = match OpenPgpKeyKind::try_from(request.kind) {
        Ok(OpenPgpKeyKind::Unspecified) | Err(_) => KeyKind::Unspecified,
        Ok(OpenPgpKeyKind::LegacyEd25519X25519) => KeyKind::LegacyEd25519X25519,
        Ok(OpenPgpKeyKind::Rsa) => KeyKind::Rsa,
    };
    let material = key::generate_key(KeyGenerationInput {
        kind,
        user_id: request.user_id,
        rsa_bits: request.rsa_bits,
        creation_time_epoch_seconds: request.creation_time_epoch_seconds,
        expiration_seconds: request.expiration_seconds,
    })?;
    Ok(wire_key_material(material).encode_to_vec())
}

pub(in crate::openpgp) fn import(
    mut request: OpenPgpKeyImportRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let result = key::import_key(KeyImportInput {
        key_data: Zeroizing::new(std::mem::take(&mut request.key_data)),
        passphrase_utf8: request.passphrase_utf8.take().map(Zeroizing::new),
    })?;
    Ok(wire_import_result(result).encode_to_vec())
}

pub(in crate::openpgp) fn wire_key_material(mut material: KeyMaterial) -> OpenPgpKeyMaterial {
    OpenPgpKeyMaterial {
        private_key_armored: std::mem::take(&mut material.private_key_armored),
        public_key_armored: std::mem::take(&mut material.public_key_armored),
        fingerprint: std::mem::take(&mut material.fingerprint),
    }
}

fn wire_import_result(result: KeyImportResult) -> OpenPgpKeyImportResult {
    let result = match result {
        KeyImportResult::Success(material) => {
            open_pgp_key_import_result::Result::Success(OpenPgpKeyImportSuccess {
                key_material: Some(wire_key_material(material)),
            })
        }
        KeyImportResult::NeedsPassphrase => {
            open_pgp_key_import_result::Result::NeedsPassphrase(OpenPgpKeyImportNeedsPassphrase {
                format_label: "OpenPGP".to_owned(),
            })
        }
        KeyImportResult::Error(reason) => {
            open_pgp_key_import_result::Result::Error(OpenPgpKeyImportError {
                reason: wire_import_failure(reason) as i32,
            })
        }
    };
    OpenPgpKeyImportResult {
        result: Some(result),
    }
}

const fn wire_import_failure(reason: KeyImportFailureReason) -> OpenPgpKeyImportErrorReason {
    match reason {
        KeyImportFailureReason::Empty => OpenPgpKeyImportErrorReason::Empty,
        KeyImportFailureReason::UnsupportedFormat => OpenPgpKeyImportErrorReason::UnsupportedFormat,
        KeyImportFailureReason::InvalidPassphrase => OpenPgpKeyImportErrorReason::InvalidPassphrase,
        KeyImportFailureReason::MalformedKey => OpenPgpKeyImportErrorReason::MalformedKey,
    }
}
