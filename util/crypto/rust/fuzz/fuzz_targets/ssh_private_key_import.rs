#![no_main]

use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{NativeRequest, SshPrivateKeyImportRequest, native_request},
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

fuzz_target!(|data: &[u8]| {
    let selector = data.first().copied().unwrap_or_default();
    let body = data.get(1..).unwrap_or_default();
    let split = if body.is_empty() {
        0
    } else {
        usize::from(selector) % (body.len() + 1)
    };
    let content = String::from_utf8_lossy(&body[..split]).into_owned();
    let passphrase_utf8 = (selector & 1 != 0).then(|| body[split..].to_vec());
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::SshPrivateKeyImport(
            SshPrivateKeyImportRequest {
                content,
                passphrase_utf8,
            },
        )),
    };

    let response = keyguard_crypto_core::call(&request.encode_to_vec());
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
});
