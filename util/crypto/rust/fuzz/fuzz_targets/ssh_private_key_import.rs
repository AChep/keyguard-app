#![no_main]

use base64ct::{Base64, Encoding};
use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{NativeRequest, SshPrivateKeyImportRequest, native_request},
};
use keyguard_crypto_sensitive::{DigestAlgorithm, HmacContext};
use libfuzzer_sys::fuzz_target;
use prost::Message;

fuzz_target!(|data: &[u8]| {
    // Preserve complete files and arbitrary comment bytes; the old u8 split
    // limited documents to 255 bytes, shorter than even an Ed25519 PPK.
    import(data.to_vec(), None);
    let selector = data.first().copied().unwrap_or_default();
    let length = data
        .get(1..5)
        .and_then(|bytes| bytes.try_into().ok())
        .map(u32::from_le_bytes)
        .unwrap_or_default() as usize;
    let body = data.get(5..).unwrap_or_default();
    let split = length % (body.len() + 1);
    import(body[..split].to_vec(), Some(body[split..].to_vec()));

    // Unencrypted MAC keys are public. Recompute a genuine v3 MAC around
    // mutated blobs so normalization stays reachable without a verifier bypass.
    if body.len() <= 512 * 1024 {
        let algorithm = if selector & 1 == 0 {
            "ssh-rsa"
        } else {
            "ssh-ed25519"
        };
        let mut public = Vec::new();
        public.extend_from_slice(&(algorithm.len() as u32).to_be_bytes());
        public.extend_from_slice(algorithm.as_bytes());
        public.extend_from_slice(&body[..split]);
        let private = &body[split..];
        let mut mac = HmacContext::new(DigestAlgorithm::Sha256, b"").unwrap();
        for field in [algorithm.as_bytes(), b"none", b"fuzz", &public, private] {
            mac.update(&(field.len() as u32).to_be_bytes()).unwrap();
            mac.update(field).unwrap();
        }
        let mut tag = [0; 32];
        mac.finalize_into(&mut tag).unwrap();
        let tag: String = tag.iter().map(|byte| format!("{byte:02x}")).collect();
        let document = format!(
            "PuTTY-User-Key-File-3: {algorithm}\nEncryption: none\nComment: fuzz\nPublic-Lines: 1\n{}\nPrivate-Lines: 1\n{}\nPrivate-MAC: {tag}\n",
            Base64::encode_string(&public),
            Base64::encode_string(private),
        );
        import(document.into_bytes(), None);
    }
});

fn import(content: Vec<u8>, passphrase_utf8: Option<Vec<u8>>) {
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
}
