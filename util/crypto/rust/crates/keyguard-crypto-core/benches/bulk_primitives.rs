//! Large-payload primitive benchmarks used for release regression tracking.
//!
//! Hosted CI compiles this benchmark; release comparisons require a fixed
//! baseline host.

use std::{hint::black_box, time::Duration};

use criterion::{Criterion, criterion_group, criterion_main};
use keyguard_crypto_core::{
    PROTOCOL_VERSION, call,
    protocol::{
        AesCbcPkcs7StreamOpenRequest, AesEcbNoPaddingEncryptRequest, CipherDirection,
        DigestRequest, DigestStreamOpenRequest, HashAlgorithm, HmacStreamOpenRequest,
        NativeRequest, NativeResponse, NativeStreamOpenRequest, OpenPgpDecryptFinal,
        OpenPgpDecryptStreamOpenRequest, OpenPgpDetachedVerifyStreamOpenRequest,
        OpenPgpEncryptFinal, OpenPgpEncryptStreamOpenRequest, OpenPgpKeyGenerateRequest,
        OpenPgpKeyKind, OpenPgpKeyMaterial, OpenPgpProtectionMode, OpenPgpVerification,
        OpenPgpVerificationStatus, SshAgentTcpChaCha20Poly1305Request, SshKeyGenerateRequest,
        SshKeyType, native_request, native_response, native_stream_open_request,
    },
    stream_finish, stream_open, stream_update,
};
use prost::Message;

const PAYLOAD_BYTES: usize = 8 * 1024 * 1024;
const SSH_AGENT_PAYLOAD_BYTES: usize = 1024 * 1024;
const STREAM_CHUNK_BYTES: usize = 64 * 1024;
const CHACHA20_POLY1305_TAG_BYTES: usize = 16;
const OPENPGP_REFERENCE_TIME: u64 = 1_783_970_000;
const OPENPGP_PUBLIC_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/cv25519-public.asc");
// GnuPG Ed25519/SHA-256 signature at epoch 1_783_944_000 over exactly
// `PAYLOAD_BYTES` repetitions of 0x5a.
const OPENPGP_DETACHED_SIGNATURE: &[u8] =
    include_bytes!("../tests/fixtures/openpgp/benchmark-detached-signature-8m-5a.asc");
const OPENPGP_MDC_PUBLIC_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/mdc-public.asc");
const OPENPGP_MDC_SECRET_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/mdc-secret.asc");

fn benchmark_bulk_primitives(c: &mut Criterion) {
    let payload = vec![0x5a; PAYLOAD_BYTES];
    let openpgp_payload = deterministic_payload(PAYLOAD_BYTES);
    let aes = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::AesEcbNoPaddingEncrypt(
            AesEcbNoPaddingEncryptRequest {
                key: (0_u8..32).collect(),
                data: payload.clone(),
            },
        )),
    }
    .encode_to_vec();
    let sha256 = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::Digest(DigestRequest {
            algorithm: HashAlgorithm::Sha256 as i32,
            data: payload.clone(),
        })),
    }
    .encode_to_vec();
    let stream_digest = stream_open_request(native_stream_open_request::Operation::Digest(
        DigestStreamOpenRequest {
            algorithm: HashAlgorithm::Sha256 as i32,
        },
    ));
    let stream_hmac = stream_open_request(native_stream_open_request::Operation::Hmac(
        HmacStreamOpenRequest {
            algorithm: HashAlgorithm::Sha256 as i32,
            key: vec![0x5a; 32],
        },
    ));
    let openpgp_detached_verify = stream_open_request(
        native_stream_open_request::Operation::OpenPgpDetachedVerify(
            OpenPgpDetachedVerifyStreamOpenRequest {
                signature: OPENPGP_DETACHED_SIGNATURE.to_vec(),
                public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            },
        ),
    );
    let stream_aes_encrypt = stream_open_request(
        native_stream_open_request::Operation::AesCbcPkcs7(AesCbcPkcs7StreamOpenRequest {
            direction: CipherDirection::Encrypt as i32,
            key: (0_u8..32).collect(),
            iv: (32_u8..48).collect(),
        }),
    );
    let stream_aes_decrypt = stream_open_request(
        native_stream_open_request::Operation::AesCbcPkcs7(AesCbcPkcs7StreamOpenRequest {
            direction: CipherDirection::Decrypt as i32,
            key: (0_u8..32).collect(),
            iv: (32_u8..48).collect(),
        }),
    );
    let ciphertext = collect_stream_output(&stream_aes_encrypt, &payload);
    let ssh_agent_payload = vec![0x5a; SSH_AGENT_PAYLOAD_BYTES];
    let ssh_agent_header = ssh_agent_header(SSH_AGENT_PAYLOAD_BYTES + CHACHA20_POLY1305_TAG_BYTES);
    let ssh_agent_seal = ssh_agent_request(
        CipherDirection::Encrypt,
        ssh_agent_header.clone(),
        ssh_agent_payload,
    );
    let ssh_agent_ciphertext = response_bytes(call(&ssh_agent_seal));
    let ssh_agent_open = ssh_agent_request(
        CipherDirection::Decrypt,
        ssh_agent_header,
        ssh_agent_ciphertext,
    );
    let ed25519_keygen = ssh_keygen_request(SshKeyType::Ed25519, 0);
    let rsa_2048_keygen = ssh_keygen_request(SshKeyType::Rsa, 2048);
    let rsa_4096_keygen = ssh_keygen_request(SshKeyType::Rsa, 4096);
    let openpgp_modern_keygen = openpgp_keygen_request(OpenPgpKeyKind::LegacyEd25519X25519, 0);
    let openpgp_rsa_3072_keygen = openpgp_keygen_request(OpenPgpKeyKind::Rsa, 3_072);
    let openpgp_modern_material = generate_openpgp_key_material(&openpgp_modern_keygen);
    let openpgp_ocb_encrypt =
        openpgp_encrypt_stream_request(&openpgp_modern_material.public_key_armored);
    let openpgp_mdc_encrypt = openpgp_encrypt_stream_request(OPENPGP_MDC_PUBLIC_KEY);
    let openpgp_ocb_ciphertext = collect_openpgp_encryption(
        &openpgp_ocb_encrypt,
        &openpgp_payload,
        OpenPgpProtectionMode::GnupgOcb,
    );
    let openpgp_mdc_ciphertext = collect_openpgp_encryption(
        &openpgp_mdc_encrypt,
        &openpgp_payload,
        OpenPgpProtectionMode::SeipdV1Mdc,
    );
    let openpgp_ocb_decrypt =
        openpgp_decrypt_stream_request(&openpgp_modern_material.private_key_armored);
    let openpgp_mdc_decrypt = openpgp_decrypt_stream_request(OPENPGP_MDC_SECRET_KEY);

    c.bench_function("aes256_ecb_8m", |bencher| {
        bencher.iter(|| black_box(call(black_box(&aes))));
    });
    c.bench_function("sha256_8m", |bencher| {
        bencher.iter(|| black_box(call(black_box(&sha256))));
    });
    c.bench_function("sha256_stream_8m_64k", |bencher| {
        bencher.iter(|| run_stream(&stream_digest, &payload));
    });
    c.bench_function("hmac_sha256_stream_8m_64k", |bencher| {
        bencher.iter(|| run_stream(&stream_hmac, &payload));
    });
    c.bench_function("openpgp_detached_verify_stream_8m_64k", |bencher| {
        bencher.iter(|| run_openpgp_detached_verify_stream(&openpgp_detached_verify, &payload));
    });
    c.bench_function("openpgp_modern_keygen", |bencher| {
        bencher.iter(|| run_openpgp_keygen(&openpgp_modern_keygen));
    });
    c.bench_function("openpgp_rsa_3072_keygen", |bencher| {
        bencher.iter(|| run_openpgp_keygen(&openpgp_rsa_3072_keygen));
    });
    c.bench_function("openpgp_ocb_encrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| {
            run_openpgp_encryption(
                &openpgp_ocb_encrypt,
                &openpgp_payload,
                OpenPgpProtectionMode::GnupgOcb,
            )
        });
    });
    c.bench_function("openpgp_ocb_decrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| {
            run_openpgp_decryption(
                &openpgp_ocb_decrypt,
                &openpgp_ocb_ciphertext,
                &openpgp_payload,
            )
        });
    });
    c.bench_function("openpgp_mdc_encrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| {
            run_openpgp_encryption(
                &openpgp_mdc_encrypt,
                &openpgp_payload,
                OpenPgpProtectionMode::SeipdV1Mdc,
            )
        });
    });
    c.bench_function("openpgp_mdc_decrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| {
            run_openpgp_decryption(
                &openpgp_mdc_decrypt,
                &openpgp_mdc_ciphertext,
                &openpgp_payload,
            )
        });
    });
    c.bench_function("aes256_cbc_pkcs7_encrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| run_stream(&stream_aes_encrypt, &payload));
    });
    c.bench_function("aes256_cbc_pkcs7_decrypt_stream_8m_64k", |bencher| {
        bencher.iter(|| run_stream(&stream_aes_decrypt, &ciphertext));
    });
    c.bench_function("ssh_agent_chacha20_poly1305_seal_1m", |bencher| {
        bencher.iter(|| black_box(call(black_box(&ssh_agent_seal))));
    });
    c.bench_function("ssh_agent_chacha20_poly1305_open_1m", |bencher| {
        bencher.iter(|| black_box(call(black_box(&ssh_agent_open))));
    });
    c.bench_function("ssh_ed25519_keygen", |bencher| {
        bencher.iter(|| black_box(call(black_box(&ed25519_keygen))));
    });
    c.bench_function("ssh_rsa_2048_keygen", |bencher| {
        bencher.iter(|| black_box(call(black_box(&rsa_2048_keygen))));
    });
    c.bench_function("ssh_rsa_4096_keygen", |bencher| {
        bencher.iter(|| black_box(call(black_box(&rsa_4096_keygen))));
    });
}

fn openpgp_keygen_request(kind: OpenPgpKeyKind, rsa_bits: u32) -> Vec<u8> {
    NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::OpenPgpKeyGenerate(
            OpenPgpKeyGenerateRequest {
                kind: kind as i32,
                user_id: "Keyguard fixed-host benchmark <benchmark@test.invalid>".to_owned(),
                rsa_bits,
                creation_time_epoch_seconds: OPENPGP_REFERENCE_TIME - 100,
                expiration_seconds: None,
            },
        )),
    }
    .encode_to_vec()
}

fn openpgp_encrypt_stream_request(public_key: &[u8]) -> Vec<u8> {
    stream_open_request(native_stream_open_request::Operation::OpenPgpEncrypt(
        OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![public_key.to_vec()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "benchmark.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME - 1),
            reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            enable_compression: None,
        },
    ))
}

fn openpgp_decrypt_stream_request(private_key: &[u8]) -> Vec<u8> {
    stream_open_request(native_stream_open_request::Operation::OpenPgpDecrypt(
        OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![private_key.to_vec()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            allow_signed_only: None,
        },
    ))
}

fn ssh_keygen_request(key_type: SshKeyType, rsa_bits: u32) -> Vec<u8> {
    NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::SshKeyGenerate(
            SshKeyGenerateRequest {
                r#type: key_type as i32,
                rsa_bits,
            },
        )),
    }
    .encode_to_vec()
}

fn ssh_agent_request(direction: CipherDirection, header: Vec<u8>, payload: Vec<u8>) -> Vec<u8> {
    NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::SshAgentTcpChacha20Poly1305(
            SshAgentTcpChaCha20Poly1305Request {
                direction: direction as i32,
                key: (0_u8..32).collect(),
                nonce: vec![0xa0, 0xa1, 0xa2, 0xa3, 0, 0, 0, 0, 0, 0, 0, 1],
                header,
                payload,
            },
        )),
    }
    .encode_to_vec()
}

fn ssh_agent_header(payload_bytes: usize) -> Vec<u8> {
    let payload_bytes = u32::try_from(payload_bytes).expect("benchmark payload must fit a u32");
    let mut header = Vec::with_capacity(18);
    header.extend_from_slice(b"KSAG");
    header.extend_from_slice(&[2, 3]);
    header.extend_from_slice(&1_u64.to_be_bytes());
    header.extend_from_slice(&payload_bytes.to_be_bytes());
    header
}

fn stream_open_request(operation: native_stream_open_request::Operation) -> Vec<u8> {
    NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    }
    .encode_to_vec()
}

fn run_stream(open_request: &[u8], payload: &[u8]) {
    let response = NativeResponse::decode(stream_open(open_request).as_slice())
        .expect("stream open response must decode");
    let handle = match response.result {
        Some(native_response::Result::Uint64Value(handle)) => handle,
        _ => panic!("stream open must return a session handle"),
    };
    for chunk in payload.chunks(STREAM_CHUNK_BYTES) {
        black_box(stream_update(handle, black_box(chunk)));
    }
    black_box(stream_finish(handle));
}

fn run_openpgp_detached_verify_stream(open_request: &[u8], payload: &[u8]) {
    let handle = open_handle(open_request);
    for chunk in payload.chunks(STREAM_CHUNK_BYTES) {
        black_box(stream_update(handle, black_box(chunk)));
    }
    let verification =
        OpenPgpVerification::decode(response_bytes(stream_finish(handle)).as_slice())
            .expect("OpenPGP verification response must decode");
    assert_eq!(
        verification.status,
        OpenPgpVerificationStatus::Valid as i32,
        "benchmark fixture must verify over the full 8 MiB payload",
    );
    black_box(verification);
}

fn run_openpgp_keygen(request: &[u8]) {
    black_box(generate_openpgp_key_material(request));
}

fn generate_openpgp_key_material(request: &[u8]) -> OpenPgpKeyMaterial {
    let material = OpenPgpKeyMaterial::decode(response_bytes(call(request)).as_slice())
        .expect("OpenPGP key-generation response must decode");
    assert!(!material.private_key_armored.is_empty());
    assert!(!material.public_key_armored.is_empty());
    assert_eq!(material.fingerprint.len(), 40);
    material
}

fn run_openpgp_encryption(
    open_request: &[u8],
    payload: &[u8],
    expected_mode: OpenPgpProtectionMode,
) {
    let encrypted = collect_openpgp_encryption(open_request, payload, expected_mode);
    assert!(!encrypted.is_empty());
    black_box(encrypted);
}

fn run_openpgp_decryption(open_request: &[u8], ciphertext: &[u8], expected_plaintext: &[u8]) {
    let plaintext = collect_openpgp_decryption(open_request, ciphertext);
    assert_eq!(plaintext, expected_plaintext);
    black_box(plaintext);
}

fn collect_openpgp_encryption(
    open_request: &[u8],
    payload: &[u8],
    expected_mode: OpenPgpProtectionMode,
) -> Vec<u8> {
    let handle = open_handle(open_request);
    let mut output = Vec::with_capacity(payload.len().saturating_add(4 * 1024));
    for chunk in payload.chunks(STREAM_CHUNK_BYTES) {
        output.extend(response_bytes(stream_update(handle, chunk)));
    }
    let final_payload = response_bytes(stream_finish(handle));
    let final_result = OpenPgpEncryptFinal::decode(final_payload.as_slice())
        .expect("OpenPGP encryption final response must decode");
    assert_eq!(final_result.protection_mode, expected_mode as i32);
    output.extend_from_slice(&final_result.data);
    output
}

fn collect_openpgp_decryption(open_request: &[u8], ciphertext: &[u8]) -> Vec<u8> {
    let handle = open_handle(open_request);
    let mut output = Vec::with_capacity(ciphertext.len());
    for chunk in ciphertext.chunks(STREAM_CHUNK_BYTES) {
        output.extend(response_bytes(stream_update(handle, chunk)));
    }
    let final_payload = response_bytes(stream_finish(handle));
    let final_result = OpenPgpDecryptFinal::decode(final_payload.as_slice())
        .expect("OpenPGP decryption final response must decode");
    assert!(final_result.verification.is_none());
    output.extend_from_slice(&final_result.data);
    output
}

fn collect_stream_output(open_request: &[u8], payload: &[u8]) -> Vec<u8> {
    let handle = open_handle(open_request);
    let mut output = Vec::with_capacity(payload.len() + 16);
    for chunk in payload.chunks(STREAM_CHUNK_BYTES) {
        output.extend(response_bytes(stream_update(handle, chunk)));
    }
    output.extend(response_bytes(stream_finish(handle)));
    output
}

fn deterministic_payload(length: usize) -> Vec<u8> {
    let mut state = 0x6a09_e667_f3bc_c909_u64;
    let mut output = vec![0_u8; length];
    for byte in &mut output {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        *byte = (state >> 24) as u8;
    }
    output
}

fn open_handle(open_request: &[u8]) -> u64 {
    let response = NativeResponse::decode(stream_open(open_request).as_slice())
        .expect("stream open response must decode");
    match response.result {
        Some(native_response::Result::Uint64Value(handle)) => handle,
        _ => panic!("stream open must return a session handle"),
    }
}

fn response_bytes(response: Vec<u8>) -> Vec<u8> {
    let response = NativeResponse::decode(response.as_slice()).expect("response must decode");
    match response.result {
        Some(native_response::Result::BytesValue(bytes)) => bytes,
        _ => panic!("stream operation must return bytes"),
    }
}

fn criterion_config() -> Criterion {
    Criterion::default()
        .sample_size(10)
        .warm_up_time(Duration::from_millis(500))
        .measurement_time(Duration::from_secs(2))
        .without_plots()
}

criterion_group! {
    name = benches;
    config = criterion_config();
    targets = benchmark_bulk_primitives
}
criterion_main!(benches);
