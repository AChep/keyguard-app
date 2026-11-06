#![no_main]

use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{
        Ed25519VerifyRequest, NativeErrorCode, NativeRequest, NativeResponse, native_request,
    },
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

fuzz_target!(|data: &[u8]| {
    // The input as a wire-encoded request exercises protobuf parsing.
    if let Ok(request) = Ed25519VerifyRequest::decode(data) {
        verify(request);
    }

    // The input split into a 32-byte key, a 64-byte signature and a message
    // reaches point decompression and strict verification on arbitrary bytes.
    let public_key = data.get(..32).unwrap_or(data).to_vec();
    let signature = data.get(32..96).unwrap_or_default().to_vec();
    let message = data.get(96..).unwrap_or_default().to_vec();
    verify(Ed25519VerifyRequest {
        public_key,
        data: message,
        signature,
    });
});

fn verify(request: Ed25519VerifyRequest) {
    let well_formed_key = request.public_key.len() == 32;
    let encoded = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::Ed25519Verify(request)),
    }
    .encode_to_vec();
    if encoded.len() > keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES {
        return;
    }
    let response = keyguard_crypto_core::call(&encoded);
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
    let code = NativeResponse::decode(response.as_slice())
        .expect("response must decode")
        .status
        .expect("response must carry a status")
        .code;
    // Verification of any 32-byte key answers valid or invalid; only the key
    // length is an argument error.
    let expected = if well_formed_key {
        NativeErrorCode::Ok
    } else {
        NativeErrorCode::InvalidArgument
    };
    assert_eq!(code, expected as i32);
}
