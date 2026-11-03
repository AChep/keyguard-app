//! Private-key text exported for SSH file imports and persisted vault records.
//!
//! File import runs `ssh_private_key_import` and then `ssh_key_describe`. The vault
//! view runs `ssh_key_parse` on the stored text and then `ssh_key_describe`. Both
//! must export an ordinary private key in its own container's armor.
//!
//! Native reparsing accepts mislabeled armor, so it is not used as an oracle here.
//! Instead, the exact armor is compared, and `ssh-key`'s strict OpenSSH decoder
//! reads OpenSSH documents. AWS-LC generates RSA keys with `p > q`, which is also
//! the order the sensitive backend uses when it completes a key. Every complete
//! RSA export can therefore be compared with the generated PKCS#1 byte for byte.

use aws_lc_rs::{
    hmac,
    signature::{Ed25519KeyPair, KeyPair as _},
};
use base64ct::{Base64, Encoding};
use keyguard_crypto_core::{
    PROTOCOL_VERSION, call,
    protocol::{
        NativeErrorCode, NativeRequest, NativeResponse, SshKeyDescribeRequest, SshKeyDescription,
        SshKeyGenerateRequest, SshKeyMaterial, SshKeyParseRequest, SshKeyType,
        SshPrivateKeyImportError, SshPrivateKeyImportErrorReason, SshPrivateKeyImportRequest,
        SshPrivateKeyImportResult, native_request, native_response, ssh_private_key_import_result,
    },
};
use pkcs1::RsaPrivateKey;
use pkcs8::{
    AlgorithmIdentifierRef, ObjectIdentifier, PrivateKeyInfo,
    der::{
        Decode as _, Encode as _,
        asn1::{AnyRef, OctetStringRef, UintRef},
    },
};
use prost::Message;
use ssh_key::{
    LineEnding, Mpint, PrivateKey,
    private::{KeypairData, RsaKeypair, RsaPrivateKey as SshRsaPrivateKey},
    public::RsaPublicKey,
};

const RSA_ENCRYPTION_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.1.1");
const ED25519_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.3.101.112");
const PUTTY_COMMENT: &str = "keyguard-export-test";

#[test]
fn putty_rsa_import_exports_the_supplied_complete_key() {
    let generated = generated_rsa();
    let key = RsaPrivateKey::from_der(&generated.private_key).expect("generated PKCS#1");

    let imported = import(putty_v3_rsa(&key, true).as_bytes());
    let description = describe(&imported);

    assert_eq!(imported.public_key, generated.public_key);
    assert_eq!(
        description.private_key_pem,
        armored("PRIVATE KEY", &rsa_pkcs8(&generated.private_key)),
    );
    assert_eq!(description.public_key_openssh, public_text(&generated));
}

#[test]
fn putty_rsa_import_rejects_a_private_blob_without_factors() {
    // PuTTY requires p, q and iqmp after d. Only SSHJ accepted records
    // without them, and it exported those keys with zero CRT fields.
    let generated = generated_rsa();
    let key = RsaPrivateKey::from_der(&generated.private_key).expect("generated PKCS#1");

    let result = import_result(putty_v3_rsa(&key, false).as_bytes());

    assert_eq!(
        result,
        ssh_private_key_import_result::Result::Error(SshPrivateKeyImportError {
            reason: SshPrivateKeyImportErrorReason::MalformedKey as i32,
        }),
    );
}

#[test]
fn persisted_openssh_rsa_exports_openssh_armor() {
    let generated = generated_rsa();
    let key = RsaPrivateKey::from_der(&generated.private_key).expect("generated PKCS#1");
    let keypair = RsaKeypair {
        public: RsaPublicKey {
            e: mpint(key.public_exponent),
            n: mpint(key.modulus),
        },
        private: SshRsaPrivateKey {
            d: mpint(key.private_exponent),
            iqmp: mpint(key.coefficient),
            p: mpint(key.prime1),
            q: mpint(key.prime2),
        },
    };
    let stored = PrivateKey::new_with_checkint(KeypairData::Rsa(keypair), "", 0x0102_0304)
        .expect("OpenSSH RSA key")
        .to_openssh(LineEnding::LF)
        .expect("OpenSSH RSA document");

    let description = describe(&parse(&stored, &public_text(&generated)));

    assert_eq!(description.private_key_pem, *stored);
    let exported =
        PrivateKey::from_openssh(&description.private_key_pem).expect("strict OpenSSH armor");
    assert_eq!(
        exported.public_key().to_bytes().expect("public key"),
        generated.public_key,
    );
}

#[test]
fn persisted_ed25519_pkcs8_exports_pkcs8_armor() {
    let seed = [0x5c_u8; 32];
    let inner = OctetStringRef::new(&seed)
        .and_then(|seed| seed.to_der())
        .expect("RFC 8410 private key");
    let pkcs8 = PrivateKeyInfo::new(
        AlgorithmIdentifierRef {
            oid: ED25519_OID,
            parameters: None,
        },
        &inner,
    )
    .to_der()
    .expect("Ed25519 PKCS#8");
    let public = Ed25519KeyPair::from_seed_unchecked(&seed).expect("Ed25519 seed");
    let public_key = ssh_wire(&[b"ssh-ed25519", public.public_key().as_ref()]);
    let stored = armored("PRIVATE KEY", &pkcs8);

    let material = parse(
        &stored,
        &format!("ssh-ed25519 {}", Base64::encode_string(&public_key)),
    );
    let description = describe(&material);

    assert_eq!(material.r#type, SshKeyType::Ed25519 as i32);
    assert_eq!(description.private_key_pem, stored);
}

#[test]
fn persisted_incomplete_rsa_exports_a_completed_key() {
    let generated = generated_rsa();
    let complete = RsaPrivateKey::from_der(&generated.private_key).expect("generated PKCS#1");
    let zero = UintRef::new(&[0]).expect("zero INTEGER");
    // Earlier PuTTY imports stored n/e/d with zero CRT fields. Older SSHJ
    // records also stored a zero public exponent.
    for public_exponent in [complete.public_exponent, zero] {
        let incomplete = RsaPrivateKey {
            public_exponent,
            prime1: zero,
            prime2: zero,
            exponent1: zero,
            exponent2: zero,
            coefficient: zero,
            ..complete.clone()
        }
        .to_der()
        .expect("incomplete PKCS#1");
        let stored_pkcs8 = rsa_pkcs8(&incomplete);

        let material = parse(
            &armored("PRIVATE KEY", &stored_pkcs8),
            &public_text(&generated),
        );
        let description = describe(&material);

        assert_eq!(
            material.private_key, stored_pkcs8,
            "stored record stays as-is"
        );
        assert_eq!(
            description.private_key_pem,
            armored("PRIVATE KEY", &rsa_pkcs8(&generated.private_key)),
        );
    }
}

#[test]
fn persisted_complete_keys_export_unchanged() {
    let rsa = generate(SshKeyType::Rsa, 2048);
    let ed25519 = generate(SshKeyType::Ed25519, 0);
    for (label, material) in [("RSA PRIVATE KEY", rsa), ("OPENSSH PRIVATE KEY", ed25519)] {
        let stored = armored(label, &material.private_key);

        let description = describe(&parse(&stored, &public_text(&material)));

        assert_eq!(description.private_key_pem, stored);
    }
}

fn response(operation: native_request::Operation) -> NativeResponse {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    NativeResponse::decode(call(&request.encode_to_vec()).as_slice()).expect("native response")
}

fn success<T: Message + Default>(operation: native_request::Operation) -> T {
    let response = response(operation);
    assert_eq!(
        response.status.expect("native status").code,
        NativeErrorCode::Ok as i32,
    );
    match response.result {
        Some(native_response::Result::BytesValue(bytes)) => {
            T::decode(bytes.as_slice()).expect("domain response")
        }
        _ => panic!("expected domain bytes"),
    }
}

fn generate(key_type: SshKeyType, rsa_bits: u32) -> SshKeyMaterial {
    success(native_request::Operation::SshKeyGenerate(
        SshKeyGenerateRequest {
            r#type: key_type as i32,
            rsa_bits,
        },
    ))
}

fn generated_rsa() -> SshKeyMaterial {
    let material = generate(SshKeyType::Rsa, 2048);
    let key = RsaPrivateKey::from_der(&material.private_key).expect("generated PKCS#1");
    let (p, q) = (key.prime1.as_bytes(), key.prime2.as_bytes());
    assert!((p.len(), p) > (q.len(), q), "AWS-LC generates p > q");
    material
}

fn import_result(content: &[u8]) -> ssh_private_key_import_result::Result {
    let result: SshPrivateKeyImportResult = success(
        native_request::Operation::SshPrivateKeyImport(SshPrivateKeyImportRequest {
            content: content.to_vec(),
            passphrase_utf8: None,
        }),
    );
    result.result.expect("import result")
}

fn import(content: &[u8]) -> SshKeyMaterial {
    match import_result(content) {
        ssh_private_key_import_result::Result::Success(success) => {
            success.key_material.expect("imported key material")
        }
        other => panic!("expected import success, got {other:?}"),
    }
}

fn parse(private_key: &str, public_key: &str) -> SshKeyMaterial {
    success(native_request::Operation::SshKeyParse(SshKeyParseRequest {
        private_key_pem: private_key.to_owned(),
        public_key_openssh: public_key.to_owned(),
    }))
}

fn describe(material: &SshKeyMaterial) -> SshKeyDescription {
    success(native_request::Operation::SshKeyDescribe(
        SshKeyDescribeRequest {
            r#type: material.r#type,
            private_key: material.private_key.clone(),
            public_key: material.public_key.clone(),
        },
    ))
}

fn public_text(material: &SshKeyMaterial) -> String {
    let prefix = if material.r#type == SshKeyType::Rsa as i32 {
        "ssh-rsa"
    } else {
        "ssh-ed25519"
    };
    format!("{prefix} {}", Base64::encode_string(&material.public_key))
}

/// RFC 7468 armor; OpenSSH wraps its own documents at 70 columns.
fn armored(label: &str, document: &[u8]) -> String {
    let width = if label == "OPENSSH PRIVATE KEY" {
        70
    } else {
        64
    };
    let encoded = Base64::encode_string(document);
    let mut text = format!("-----BEGIN {label}-----\n");
    for line in encoded.as_bytes().chunks(width) {
        text.push_str(std::str::from_utf8(line).expect("Base64 ASCII"));
        text.push('\n');
    }
    text.push_str(&format!("-----END {label}-----\n"));
    text
}

fn rsa_pkcs8(pkcs1: &[u8]) -> Vec<u8> {
    PrivateKeyInfo::new(
        AlgorithmIdentifierRef {
            oid: RSA_ENCRYPTION_OID,
            parameters: Some(AnyRef::NULL),
        },
        pkcs1,
    )
    .to_der()
    .expect("RSA PKCS#8")
}

/// Unencrypted PPK v3 (PuTTY Appendix C). Its MAC is HMAC-SHA-256 with an
/// empty key. PuTTY's `iqmp` is `q^-1 mod p`, the same as PKCS#1's coefficient.
fn putty_v3_rsa(key: &RsaPrivateKey<'_>, include_factors: bool) -> String {
    let public = ssh_wire(&[
        b"ssh-rsa",
        &mpint_bytes(key.public_exponent),
        &mpint_bytes(key.modulus),
    ]);
    let mut private_fields = vec![mpint_bytes(key.private_exponent)];
    if include_factors {
        private_fields.extend([
            mpint_bytes(key.prime1),
            mpint_bytes(key.prime2),
            mpint_bytes(key.coefficient),
        ]);
    }
    let private = ssh_wire(&private_fields.iter().map(Vec::as_slice).collect::<Vec<_>>());
    let mac = hmac::sign(
        &hmac::Key::new(hmac::HMAC_SHA256, &[]),
        &ssh_wire(&[
            b"ssh-rsa",
            b"none",
            PUTTY_COMMENT.as_bytes(),
            &public,
            &private,
        ]),
    );

    let mut document =
        format!("PuTTY-User-Key-File-3: ssh-rsa\nEncryption: none\nComment: {PUTTY_COMMENT}\n");
    for (name, blob) in [("Public", &public), ("Private", &private)] {
        let encoded = Base64::encode_string(blob);
        let lines: Vec<_> = encoded.as_bytes().chunks(64).collect();
        document.push_str(&format!("{name}-Lines: {}\n", lines.len()));
        for line in lines {
            document.push_str(std::str::from_utf8(line).expect("Base64 ASCII"));
            document.push('\n');
        }
    }
    let mac: String = mac
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    document.push_str(&format!("Private-MAC: {mac}\n"));
    document
}

fn ssh_wire(values: &[&[u8]]) -> Vec<u8> {
    let mut output = Vec::new();
    for value in values {
        output.extend_from_slice(
            &u32::try_from(value.len())
                .expect("SSH string length")
                .to_be_bytes(),
        );
        output.extend_from_slice(value);
    }
    output
}

fn mpint(value: UintRef<'_>) -> Mpint {
    Mpint::from_positive_bytes(value.as_bytes()).expect("positive mpint")
}

/// SSH mpint body: a leading zero keeps a set high bit positive.
fn mpint_bytes(value: UintRef<'_>) -> Vec<u8> {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len() + 1);
    if bytes.first().is_some_and(|byte| byte & 0x80 != 0) {
        output.push(0);
    }
    output.extend_from_slice(bytes);
    output
}
