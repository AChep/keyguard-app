#![no_main]

use base64ct::{Base64, Encoding};
use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{NativeRequest, SshCertificateDescribeRequest, native_request},
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

fuzz_target!(|data: &[u8]| {
    describe(String::from_utf8_lossy(data).into_owned(), None);

    // Wrap the input as a certificate blob, so the wire parser is reachable
    // without first guessing a well-formed OpenSSH line.
    let selector = data.first().copied().unwrap_or_default();
    let body = data.get(1..).unwrap_or_default();
    let algorithm = if selector & 1 == 0 {
        "ssh-ed25519-cert-v01@openssh.com"
    } else {
        "ssh-rsa-cert-v01@openssh.com"
    };
    let mut blob = Vec::with_capacity(4 + algorithm.len() + body.len());
    blob.extend_from_slice(&(algorithm.len() as u32).to_be_bytes());
    blob.extend_from_slice(algorithm.as_bytes());
    blob.extend_from_slice(body);
    let certificate = format!("{algorithm} {}", Base64::encode_string(&blob));
    let public_key = (selector & 2 != 0).then(|| String::from_utf8_lossy(body).into_owned());
    describe(certificate, public_key);
});

fn describe(certificate_openssh: String, public_key_openssh: Option<String>) {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::SshCertificateDescribe(
            SshCertificateDescribeRequest {
                certificate_openssh,
                public_key_openssh,
            },
        )),
    };
    let response = keyguard_crypto_core::call(&request.encode_to_vec());
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
}
