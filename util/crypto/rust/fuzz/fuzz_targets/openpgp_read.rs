#![no_main]

use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{
        NativeRequest, NativeResponse, NativeStreamOpenRequest,
        OpenPgpClearVerifyStreamOpenRequest, OpenPgpDetachedVerifyStreamOpenRequest,
        OpenPgpMetadataResolveRequest, OpenPgpPublicKeyParseRequest, OpenPgpVerifyKind,
        OpenPgpVerifyRequest, native_request, native_response, native_stream_open_request,
    },
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

const MAX_FUZZ_INPUT_BYTES: usize = 256 * 1024;
const REFERENCE_TIME: u64 = 1_783_944_100;
const PUBLIC_KEY: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/cv25519-secret.asc");
const DETACHED_BODY: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/detached-body.txt");
const DETACHED_SIGNATURE: &[u8] = include_bytes!(
    "../../crates/keyguard-crypto-core/tests/fixtures/openpgp/detached-signature.asc"
);
const CLEAR_SIGNED: &[u8] =
    include_bytes!("../../crates/keyguard-crypto-core/tests/fixtures/openpgp/clear-signed.asc");

fuzz_target!(|input: &[u8]| {
    let input = &input[..input.len().min(MAX_FUZZ_INPUT_BYTES)];
    let selector = input.first().copied().unwrap_or_default();
    let body = input.get(1..).unwrap_or_default();

    let parse_data = choose_bytes(selector, body, PUBLIC_KEY);
    call(native_request::Operation::OpenPgpPublicKeyParse(
        OpenPgpPublicKeyParseRequest {
            key_data: parse_data,
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    ));

    match selector % 5 {
        0 => call(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: choose_bytes(selector.rotate_left(1), body, DETACHED_BODY),
                signature: DETACHED_SIGNATURE.to_vec(),
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )),
        1 => call(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: DETACHED_BODY.to_vec(),
                signature: choose_bytes(selector.rotate_left(1), body, DETACHED_SIGNATURE),
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )),
        2 => call(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: DETACHED_BODY.to_vec(),
                signature: DETACHED_SIGNATURE.to_vec(),
                public_keys: vec![choose_bytes(selector.rotate_left(1), body, PUBLIC_KEY)],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )),
        3 => call(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: choose_bytes(selector.rotate_left(1), body, CLEAR_SIGNED),
                signature: Vec::new(),
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )),
        _ => call(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: DETACHED_BODY.to_vec(),
                signature: DETACHED_SIGNATURE.to_vec(),
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )),
    }

    let candidate_count = usize::from(selector % 66);
    call(native_request::Operation::OpenPgpMetadataResolve(
        OpenPgpMetadataResolveRequest {
            private_key_data: (selector & 1 == 0)
                .then(|| choose_bytes(selector.rotate_left(2), body, SECRET_KEY)),
            public_key_data: Some(choose_bytes(selector.rotate_left(3), body, PUBLIC_KEY)),
            normalized_fingerprint: String::from_utf8_lossy(body).into_owned(),
            candidate_revocation_keys: vec![PUBLIC_KEY.to_vec(); candidate_count],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    ));

    if selector & 0x04 != 0 {
        fuzz_stream(
            native_stream_open_request::Operation::OpenPgpDetachedVerify(
                OpenPgpDetachedVerifyStreamOpenRequest {
                    signature: DETACHED_SIGNATURE.to_vec(),
                    public_keys: vec![PUBLIC_KEY.to_vec()],
                    reference_time_epoch_seconds: Some(REFERENCE_TIME),
                },
            ),
            choose_bytes(selector, body, DETACHED_BODY),
        );
    }
    if selector & 0x08 != 0 {
        fuzz_stream(
            native_stream_open_request::Operation::OpenPgpClearVerify(
                OpenPgpClearVerifyStreamOpenRequest {
                    public_keys: vec![PUBLIC_KEY.to_vec()],
                    reference_time_epoch_seconds: Some(REFERENCE_TIME),
                },
            ),
            choose_bytes(selector.rotate_left(4), body, CLEAR_SIGNED),
        );
    }
});

fn choose_bytes(selector: u8, input: &[u8], fixture: &[u8]) -> Vec<u8> {
    match selector % 3 {
        0 => input.to_vec(),
        1 => {
            let split_seed = input
                .iter()
                .take(std::mem::size_of::<usize>())
                .fold(usize::from(selector), |value, byte| {
                    value.rotate_left(5) ^ usize::from(*byte)
                });
            let split = split_seed % (fixture.len() + 1);
            let mut value = Vec::with_capacity(split.saturating_add(input.len()));
            value.extend_from_slice(&fixture[..split]);
            value.extend_from_slice(input);
            value
        }
        _ => fixture.to_vec(),
    }
}

fn call(operation: native_request::Operation) {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    let response = keyguard_crypto_core::call(&request.encode_to_vec());
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
}

fn fuzz_stream(operation: native_stream_open_request::Operation, content: Vec<u8>) {
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

    for chunk in content.chunks(keyguard_crypto_core::MAX_STREAM_CHUNK_BYTES) {
        let response = keyguard_crypto_core::stream_update(handle, chunk);
        assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
    }
    let response = keyguard_crypto_core::stream_finish(handle);
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
    let _ = keyguard_crypto_core::stream_close(handle);
}
