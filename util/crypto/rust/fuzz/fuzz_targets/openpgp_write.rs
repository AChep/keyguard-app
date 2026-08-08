#![no_main]

use keyguard_crypto_core::{
    MAX_CONTROL_ENVELOPE_BYTES, MAX_STREAM_CHUNK_BYTES, PROTOCOL_VERSION,
    protocol::{
        NativeRequest, NativeResponse, NativeStreamOpenRequest, OpenPgpClearSignStreamOpenRequest,
        OpenPgpDecryptRequest, OpenPgpDecryptStreamOpenRequest,
        OpenPgpDetachedSignStreamOpenRequest, OpenPgpEncryptRequest,
        OpenPgpEncryptStreamOpenRequest, OpenPgpKeyImportRequest, OpenPgpSignKind,
        OpenPgpSignRequest, native_request, native_response, native_stream_open_request,
    },
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

const MAX_FUZZ_INPUT_BYTES: usize = 128 * 1024;
const REFERENCE_TIME: u64 = 1_783_970_000;
const PUBLIC_KEY: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/cv25519-secret.asc");

fuzz_target!(|input: &[u8]| {
    let input = &input[..input.len().min(MAX_FUZZ_INPUT_BYTES)];
    let selector = input.first().copied().unwrap_or_default();
    let body = input.get(1..).unwrap_or_default();
    let private_key = choose_bytes(selector.rotate_left(1), body, SECRET_KEY);
    let public_key = choose_bytes(selector.rotate_left(2), body, PUBLIC_KEY);

    call(native_request::Operation::OpenPgpKeyImport(
        OpenPgpKeyImportRequest {
            key_data: choose_bytes(selector, body, SECRET_KEY),
            passphrase_utf8: (selector & 1 != 0).then(|| body[..body.len().min(64)].to_vec()),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    ));
    call(native_request::Operation::OpenPgpSign(OpenPgpSignRequest {
        kind: match selector % 3 {
            0 => OpenPgpSignKind::ClearText,
            1 => OpenPgpSignKind::Detached,
            _ => OpenPgpSignKind::Unspecified,
        } as i32,
        content: body.to_vec(),
        private_key: private_key.clone(),
        preferred_fingerprint: lossy_text(body, 96),
        armored: selector & 2 == 0,
        signature_time_epoch_seconds: Some(REFERENCE_TIME - 1),
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    }));
    call(native_request::Operation::OpenPgpEncrypt(
        OpenPgpEncryptRequest {
            content: body.to_vec(),
            public_keys: vec![public_key.clone()],
            signing_private_key: (selector & 4 != 0).then(|| private_key.clone()),
            preferred_signing_fingerprint: String::new(),
            file_name: lossy_text(body, 128),
            armored: selector & 8 == 0,
            literal_time_epoch_seconds: Some(REFERENCE_TIME - 1),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
            enable_compression: None,
        },
    ));
    call(native_request::Operation::OpenPgpDecrypt(
        OpenPgpDecryptRequest {
            content: body.to_vec(),
            private_keys: vec![private_key.clone()],
            verification_public_keys: vec![public_key.clone()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
            allow_signed_only: None,
        },
    ));

    let operations = [
        native_stream_open_request::Operation::OpenPgpDetachedSign(
            OpenPgpDetachedSignStreamOpenRequest {
                private_key: private_key.clone(),
                preferred_fingerprint: String::new(),
                armored: selector & 0x10 == 0,
                signature_time_epoch_seconds: Some(REFERENCE_TIME - 1),
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        ),
        native_stream_open_request::Operation::OpenPgpClearSign(
            OpenPgpClearSignStreamOpenRequest {
                private_key: private_key.clone(),
                preferred_fingerprint: String::new(),
                signature_time_epoch_seconds: Some(REFERENCE_TIME - 1),
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        ),
        native_stream_open_request::Operation::OpenPgpEncrypt(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![public_key.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "fuzz.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(REFERENCE_TIME - 1),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
            enable_compression: None,
        }),
        native_stream_open_request::Operation::OpenPgpDecrypt(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![private_key],
            verification_public_keys: vec![public_key],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
            allow_signed_only: None,
        }),
    ];
    for (index, operation) in operations.into_iter().enumerate() {
        fuzz_stream(selector.wrapping_add(index as u8), body, operation);
    }
});

fn choose_bytes(selector: u8, input: &[u8], fixture: &[u8]) -> Vec<u8> {
    match selector % 3 {
        0 => input.to_vec(),
        1 => {
            let split = input
                .iter()
                .take(8)
                .fold(usize::from(selector), |value, byte| {
                    value.rotate_left(5) ^ usize::from(*byte)
                })
                % (fixture.len() + 1);
            let mut value = fixture[..split].to_vec();
            value.extend_from_slice(input);
            value
        }
        _ => fixture.to_vec(),
    }
}

fn lossy_text(input: &[u8], maximum: usize) -> String {
    String::from_utf8_lossy(&input[..input.len().min(maximum)]).into_owned()
}

fn call(operation: native_request::Operation) {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    let response = keyguard_crypto_core::call(&request.encode_to_vec());
    assert!(response.len() <= MAX_CONTROL_ENVELOPE_BYTES);
}

fn fuzz_stream(selector: u8, body: &[u8], operation: native_stream_open_request::Operation) {
    let request = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    let response = keyguard_crypto_core::stream_open(&request.encode_to_vec());
    let Ok(response) = NativeResponse::decode(response.as_slice()) else {
        return;
    };
    let Some(native_response::Result::Uint64Value(handle)) = response.result else {
        return;
    };

    let chunk_size = usize::from(selector)
        .saturating_add(1)
        .min(MAX_STREAM_CHUNK_BYTES);
    for chunk in body.chunks(chunk_size) {
        let response = keyguard_crypto_core::stream_update(handle, chunk);
        assert!(response.len() <= MAX_CONTROL_ENVELOPE_BYTES);
    }
    if selector & 1 == 0 {
        let response = keyguard_crypto_core::stream_finish(handle);
        assert!(response.len() <= MAX_CONTROL_ENVELOPE_BYTES);
        let _ = keyguard_crypto_core::stream_update(handle, b"stale");
    }
    let _ = keyguard_crypto_core::stream_close(handle);
    let _ = keyguard_crypto_core::stream_close(handle);
}
