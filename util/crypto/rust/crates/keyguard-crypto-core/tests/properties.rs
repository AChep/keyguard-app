//! Property tests for boundary-visible primitive and streaming invariants.

use keyguard_crypto_core::{
    PROTOCOL_VERSION, call,
    protocol::{
        AesCbcPkcs7Request, AesCbcPkcs7StreamOpenRequest, CipherDirection, DigestRequest,
        DigestStreamOpenRequest, HashAlgorithm, HmacRequest, HmacStreamOpenRequest,
        NativeErrorCode, NativeRequest, NativeResponse, NativeStreamOpenRequest,
        OpenPgpClearVerifyResult, OpenPgpClearVerifyStreamOpenRequest,
        OpenPgpDetachedVerifyStreamOpenRequest, OpenPgpSignKind, OpenPgpSignRequest,
        OpenPgpVerification, OpenPgpVerificationStatus, OpenPgpVerifyKind, OpenPgpVerifyRequest,
        SshAgentTcpChaCha20Poly1305Request, StreamCipherAlgorithm, StreamCipherXorAtOffsetRequest,
        TwofishCbcPkcs7Request, TwofishCbcPkcs7StreamOpenRequest, native_request, native_response,
        native_stream_open_request,
    },
    stream_finish, stream_open, stream_update,
};
use pgp::{
    composed::{Deserializable, DetachedSignature, SignedSecretKey},
    crypto::hash::HashAlgorithm as OpenPgpHashAlgorithm,
    packet::{SignatureConfig, SignatureType, Subpacket, SubpacketData},
    ser::Serialize,
    types::{KeyDetails, Password, Timestamp},
};
use proptest::prelude::*;
use prost::Message;

const CASES: u32 = 128;
const OPENPGP_CASES: u32 = 32;
const OPENPGP_PUBLIC_KEY: &[u8] = include_bytes!("fixtures/openpgp/cv25519-public.asc");
const OPENPGP_SECRET_KEY: &[u8] = include_bytes!("fixtures/openpgp/cv25519-secret.asc");
const OPENPGP_SIGNATURE_TIME: u32 = 1_784_073_600;
const OPENPGP_REFERENCE_TIME: u64 = 1_784_073_601;

fn hash_algorithm() -> impl Strategy<Value = HashAlgorithm> {
    prop_oneof![
        Just(HashAlgorithm::Sha1),
        Just(HashAlgorithm::Sha256),
        Just(HashAlgorithm::Sha512),
        Just(HashAlgorithm::Md5),
    ]
}

fn aes_key() -> impl Strategy<Value = Vec<u8>> {
    prop_oneof![
        prop::collection::vec(any::<u8>(), 16),
        prop::collection::vec(any::<u8>(), 24),
        prop::collection::vec(any::<u8>(), 32),
    ]
}

fn twofish_key() -> impl Strategy<Value = Vec<u8>> {
    prop_oneof![
        prop::collection::vec(any::<u8>(), 16),
        prop::collection::vec(any::<u8>(), 24),
        prop::collection::vec(any::<u8>(), 32),
    ]
}

fn stream_cipher() -> impl Strategy<Value = (StreamCipherAlgorithm, Vec<u8>)> {
    prop_oneof![
        prop::collection::vec(any::<u8>(), 8)
            .prop_map(|nonce| (StreamCipherAlgorithm::Salsa20, nonce)),
        prop::collection::vec(any::<u8>(), 12)
            .prop_map(|nonce| (StreamCipherAlgorithm::Chacha20, nonce)),
    ]
}

fn response(request: NativeRequest) -> NativeResponse {
    NativeResponse::decode(call(request.encode_to_vec().as_slice()).as_slice())
        .expect("native response must decode")
}

fn response_bytes(response: NativeResponse) -> Vec<u8> {
    let status = response
        .status
        .expect("native response must contain status");
    assert_eq!(status.code, NativeErrorCode::Ok as i32);
    match response.result {
        Some(native_response::Result::BytesValue(bytes)) => bytes,
        _ => panic!("native response must contain bytes"),
    }
}

fn one_shot(operation: native_request::Operation) -> Vec<u8> {
    response_bytes(response(NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    }))
}

fn stream_open_response(open_operation: native_stream_open_request::Operation) -> NativeResponse {
    NativeResponse::decode(
        stream_open(
            NativeStreamOpenRequest {
                protocol_version: PROTOCOL_VERSION,
                operation: Some(open_operation),
            }
            .encode_to_vec()
            .as_slice(),
        )
        .as_slice(),
    )
    .expect("stream-open response must decode")
}

fn response_status_code(response: &NativeResponse) -> i32 {
    response
        .status
        .as_ref()
        .expect("native response must contain status")
        .code
}

fn stream(
    open_operation: native_stream_open_request::Operation,
    data: &[u8],
    chunk: usize,
) -> Vec<u8> {
    stream_partitioned(open_operation, data, &[chunk])
}

fn stream_partitioned(
    open_operation: native_stream_open_request::Operation,
    data: &[u8],
    partitions: &[usize],
) -> Vec<u8> {
    assert!(!partitions.is_empty());
    assert!(partitions.iter().all(|size| *size > 0));
    let open_response = stream_open_response(open_operation);
    let open_status = open_response
        .status
        .expect("stream-open response must contain status");
    assert_eq!(open_status.code, NativeErrorCode::Ok as i32);
    let handle = match open_response.result {
        Some(native_response::Result::Uint64Value(handle)) => handle,
        _ => panic!("stream-open response must contain a handle"),
    };

    let mut output = Vec::new();
    let mut offset = 0;
    for size in partitions.iter().copied().cycle() {
        if offset == data.len() {
            break;
        }
        let end = offset.saturating_add(size).min(data.len());
        let part = &data[offset..end];
        let update = NativeResponse::decode(stream_update(handle, part).as_slice())
            .expect("stream-update response must decode");
        output.extend(response_bytes(update));
        offset = end;
    }
    let finish = NativeResponse::decode(stream_finish(handle).as_slice())
        .expect("stream-finish response must decode");
    output.extend(response_bytes(finish));
    output
}

fn deterministic_detached_signature(data: &[u8]) -> Vec<u8> {
    let (secret_key, _) = SignedSecretKey::from_armor_single(OPENPGP_SECRET_KEY)
        .expect("fixed OpenPGP secret key must parse");
    let signer = &secret_key.primary_key;
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        signer.algorithm(),
        OpenPgpHashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .expect("issuer fingerprint subpacket must encode"),
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            OPENPGP_SIGNATURE_TIME,
        )))
        .expect("signature creation subpacket must encode"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .expect("issuer key ID subpacket must encode"),
    ];
    let signature = config
        .sign(signer, &Password::empty(), data)
        .expect("fixed OpenPGP test key must sign arbitrary data");
    DetachedSignature::new(signature)
        .to_bytes()
        .expect("detached signature must serialize")
}

fn one_shot_detached_response(data: &[u8], signature: &[u8]) -> NativeResponse {
    response(NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::OpenPgpVerify(
            OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: data.to_vec(),
                signature: signature.to_vec(),
                public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            },
        )),
    })
}

fn one_shot_detached_verification(data: &[u8], signature: &[u8]) -> Vec<u8> {
    response_bytes(one_shot_detached_response(data, signature))
}

fn detached_verification_stream_open_response(signature: &[u8]) -> NativeResponse {
    stream_open_response(
        native_stream_open_request::Operation::OpenPgpDetachedVerify(
            OpenPgpDetachedVerifyStreamOpenRequest {
                signature: signature.to_vec(),
                public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            },
        ),
    )
}

fn streamed_detached_verification(data: &[u8], signature: &[u8], partitions: &[usize]) -> Vec<u8> {
    stream_partitioned(
        native_stream_open_request::Operation::OpenPgpDetachedVerify(
            OpenPgpDetachedVerifyStreamOpenRequest {
                signature: signature.to_vec(),
                public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            },
        ),
        data,
        partitions,
    )
}

fn open_pgp_verification(data: &[u8]) -> OpenPgpVerification {
    OpenPgpVerification::decode(data).expect("OpenPGP verification result must decode")
}

fn deterministic_clear_signed_document(body: &str) -> Vec<u8> {
    one_shot(native_request::Operation::OpenPgpSign(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: body.as_bytes().to_vec(),
        private_key: OPENPGP_SECRET_KEY.to_vec(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: Some(u64::from(OPENPGP_SIGNATURE_TIME)),
        reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
        candidate_revocation_keys: Vec::new(),
    }))
}

fn normalized_cleartext_body(body: &[u8]) -> Vec<u8> {
    let body = body.strip_suffix(b"\n").unwrap_or(body);
    let mut recovered = Vec::with_capacity(body.len());
    for (index, line) in body.split(|byte| *byte == b'\n').enumerate() {
        if index > 0 {
            recovered.push(b'\n');
        }
        let content_length = line
            .iter()
            .rposition(|byte| !matches!(byte, b' ' | b'\t'))
            .map_or(0, |position| position + 1);
        recovered.extend_from_slice(&line[..content_length]);
    }
    recovered
}

fn one_shot_clear_verification(document: &[u8]) -> Vec<u8> {
    one_shot(native_request::Operation::OpenPgpVerify(
        OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: document.to_vec(),
            signature: Vec::new(),
            public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
        },
    ))
}

fn streamed_clear_verification(
    document: &[u8],
    partitions: &[usize],
) -> (Vec<u8>, OpenPgpClearVerifyResult) {
    assert!(!partitions.is_empty());
    assert!(partitions.iter().all(|size| *size > 0));
    let open_response =
        stream_open_response(native_stream_open_request::Operation::OpenPgpClearVerify(
            OpenPgpClearVerifyStreamOpenRequest {
                public_keys: vec![OPENPGP_PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(OPENPGP_REFERENCE_TIME),
            },
        ));
    let open_status = open_response
        .status
        .expect("stream-open response must contain status");
    assert_eq!(open_status.code, NativeErrorCode::Ok as i32);
    let handle = match open_response.result {
        Some(native_response::Result::Uint64Value(handle)) => handle,
        _ => panic!("stream-open response must contain a handle"),
    };

    let mut body = Vec::new();
    let mut offset = 0;
    for size in partitions.iter().copied().cycle() {
        if offset == document.len() {
            break;
        }
        let end = offset.saturating_add(size).min(document.len());
        let update =
            NativeResponse::decode(stream_update(handle, &document[offset..end]).as_slice())
                .expect("stream-update response must decode");
        body.extend(response_bytes(update));
        offset = end;
    }
    let finish = NativeResponse::decode(stream_finish(handle).as_slice())
        .expect("stream-finish response must decode");
    let result = OpenPgpClearVerifyResult::decode(response_bytes(finish).as_slice())
        .expect("clear-verify result must decode");
    (body, result)
}

#[test]
fn clear_signed_recovery_excludes_the_signature_separator_line_ending() {
    let cases: [(&str, &[u8]); 5] = [
        ("", b""),
        ("\n", b""),
        ("a", b"a"),
        ("a\n", b"a"),
        ("a\n\n", b"a\n"),
    ];
    let mut documents = Vec::with_capacity(cases.len());

    for (body, expected) in cases {
        let document = deterministic_clear_signed_document(body);
        let (recovered, result) = streamed_clear_verification(&document, &[1, 7, 31]);

        assert_eq!(recovered, expected);
        assert_eq!(
            result
                .verification
                .expect("clear-sign verification result must be present")
                .status,
            OpenPgpVerificationStatus::Valid as i32,
        );
        documents.push(document);
    }

    assert_eq!(documents[0], documents[1]);
    assert_eq!(documents[2], documents[3]);
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(CASES))]

    #[test]
    fn digest_is_independent_of_stream_chunk_boundaries(
        algorithm in hash_algorithm(),
        data in prop::collection::vec(any::<u8>(), 0..8192),
        chunk in 1_usize..1024,
    ) {
        let expected = one_shot(native_request::Operation::Digest(DigestRequest {
            algorithm: algorithm as i32,
            data: data.clone(),
        }));
        let actual = stream(
            native_stream_open_request::Operation::Digest(DigestStreamOpenRequest {
                algorithm: algorithm as i32,
            }),
            &data,
            chunk,
        );

        prop_assert_eq!(actual, expected);
    }

    #[test]
    fn hmac_is_independent_of_stream_chunk_boundaries(
        algorithm in hash_algorithm(),
        key in prop::collection::vec(any::<u8>(), 0..256),
        data in prop::collection::vec(any::<u8>(), 0..8192),
        chunk in 1_usize..1024,
    ) {
        let expected = one_shot(native_request::Operation::Hmac(HmacRequest {
            algorithm: algorithm as i32,
            key: key.clone(),
            data: data.clone(),
        }));
        let actual = stream(
            native_stream_open_request::Operation::Hmac(HmacStreamOpenRequest {
                algorithm: algorithm as i32,
                key,
            }),
            &data,
            chunk,
        );

        prop_assert_eq!(actual, expected);
    }

    #[test]
    fn aes_cbc_streaming_matches_one_shot_and_round_trips(
        key in aes_key(),
        iv in prop::collection::vec(any::<u8>(), 16),
        plaintext in prop::collection::vec(any::<u8>(), 0..8192),
        encrypt_chunk in 1_usize..1024,
        decrypt_chunk in 1_usize..1024,
    ) {
        let one_shot_ciphertext = one_shot(native_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: plaintext.clone(),
            },
        ));
        let streamed_ciphertext = stream(
            native_stream_open_request::Operation::AesCbcPkcs7(
                AesCbcPkcs7StreamOpenRequest {
                    direction: CipherDirection::Encrypt as i32,
                    key: key.clone(),
                    iv: iv.clone(),
                },
            ),
            &plaintext,
            encrypt_chunk,
        );
        prop_assert_eq!(&streamed_ciphertext, &one_shot_ciphertext);

        let one_shot_plaintext = one_shot(native_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7Request {
                direction: CipherDirection::Decrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: one_shot_ciphertext.clone(),
            },
        ));
        let streamed_plaintext = stream(
            native_stream_open_request::Operation::AesCbcPkcs7(
                AesCbcPkcs7StreamOpenRequest {
                    direction: CipherDirection::Decrypt as i32,
                    key,
                    iv,
                },
            ),
            &one_shot_ciphertext,
            decrypt_chunk,
        );

        prop_assert_eq!(&one_shot_plaintext, &plaintext);
        prop_assert_eq!(&streamed_plaintext, &plaintext);
    }

    #[test]
    fn kdbx_stream_ciphers_are_offset_composable_and_involutive(
        (algorithm, nonce) in stream_cipher(),
        key in prop::collection::vec(any::<u8>(), 32),
        input in prop::collection::vec(any::<u8>(), 0..8192),
        offset in 0_u64..1_000_000,
        chunk in 1_usize..1024,
    ) {
        let transform = |data: Vec<u8>, absolute_offset: u64| {
            one_shot(native_request::Operation::StreamCipherXorAtOffset(
                StreamCipherXorAtOffsetRequest {
                    algorithm: algorithm as i32,
                    key: key.clone(),
                    nonce: nonce.clone(),
                    offset: absolute_offset,
                    data,
                },
            ))
        };

        let expected = transform(input.clone(), offset);
        let mut chunked = Vec::with_capacity(input.len());
        for (index, part) in input.chunks(chunk).enumerate() {
            let part_offset = offset + (index * chunk) as u64;
            chunked.extend(transform(part.to_vec(), part_offset));
        }
        let round_trip = transform(expected.clone(), offset);

        prop_assert_eq!(chunked, expected);
        prop_assert_eq!(round_trip, input);
    }

    #[test]
    fn twofish_cbc_streaming_matches_one_shot_and_round_trips(
        key in twofish_key(),
        iv in prop::collection::vec(any::<u8>(), 16),
        plaintext in prop::collection::vec(any::<u8>(), 0..8192),
        encrypt_chunk in 1_usize..1024,
        decrypt_chunk in 1_usize..1024,
    ) {
        let one_shot_ciphertext = one_shot(native_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: plaintext.clone(),
            },
        ));
        let streamed_ciphertext = stream(
            native_stream_open_request::Operation::TwofishCbcPkcs7(
                TwofishCbcPkcs7StreamOpenRequest {
                    direction: CipherDirection::Encrypt as i32,
                    key: key.clone(),
                    iv: iv.clone(),
                },
            ),
            &plaintext,
            encrypt_chunk,
        );
        prop_assert_eq!(&streamed_ciphertext, &one_shot_ciphertext);

        let one_shot_plaintext = one_shot(native_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7Request {
                direction: CipherDirection::Decrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: one_shot_ciphertext.clone(),
            },
        ));
        let streamed_plaintext = stream(
            native_stream_open_request::Operation::TwofishCbcPkcs7(
                TwofishCbcPkcs7StreamOpenRequest {
                    direction: CipherDirection::Decrypt as i32,
                    key,
                    iv,
                },
            ),
            &one_shot_ciphertext,
            decrypt_chunk,
        );

        prop_assert_eq!(&one_shot_plaintext, &plaintext);
        prop_assert_eq!(&streamed_plaintext, &plaintext);
    }

    #[test]
    fn ssh_agent_tcp_chacha20_poly1305_round_trips_and_authenticates(
        key in prop::collection::vec(any::<u8>(), 32),
        nonce in prop::collection::vec(any::<u8>(), 12),
        header in prop::collection::vec(any::<u8>(), 18),
        plaintext in prop::collection::vec(any::<u8>(), 0..8192),
    ) {
        let encrypted = one_shot(
            native_request::Operation::SshAgentTcpChacha20Poly1305(
                SshAgentTcpChaCha20Poly1305Request {
                    direction: CipherDirection::Encrypt as i32,
                    key: key.clone(),
                    nonce: nonce.clone(),
                    header: header.clone(),
                    payload: plaintext.clone(),
                },
            ),
        );
        prop_assert_eq!(encrypted.len(), plaintext.len() + 16);
        let decrypted = one_shot(
            native_request::Operation::SshAgentTcpChacha20Poly1305(
                SshAgentTcpChaCha20Poly1305Request {
                    direction: CipherDirection::Decrypt as i32,
                    key,
                    nonce,
                    header,
                    payload: encrypted,
                },
            ),
        );
        prop_assert_eq!(decrypted, plaintext);
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(OPENPGP_CASES))]

    #[test]
    fn detached_openpgp_one_shot_and_streaming_match_for_valid_and_mutated_bodies(
        body in prop::collection::vec(any::<u8>(), 0..4096),
        partitions in prop::collection::vec(1_usize..512, 1..16),
        mutation_index in any::<usize>(),
        mutation_mask in 1_u8..=u8::MAX,
    ) {
        let signature = deterministic_detached_signature(&body);
        let one_shot_valid = one_shot_detached_verification(&body, &signature);
        let streamed_valid = streamed_detached_verification(&body, &signature, &partitions);

        prop_assert_eq!(&streamed_valid, &one_shot_valid);
        prop_assert_eq!(
            open_pgp_verification(&one_shot_valid).status,
            OpenPgpVerificationStatus::Valid as i32,
        );

        let mut mutated = body.clone();
        if mutated.is_empty() {
            mutated.push(mutation_mask);
        } else {
            let index = mutation_index % mutated.len();
            mutated[index] ^= mutation_mask;
        }
        prop_assert_ne!(&mutated, &body);

        let one_shot_invalid = one_shot_detached_verification(&mutated, &signature);
        let streamed_invalid =
            streamed_detached_verification(&mutated, &signature, &partitions);

        prop_assert_eq!(&streamed_invalid, &one_shot_invalid);
        prop_assert_eq!(
            open_pgp_verification(&one_shot_invalid).status,
            OpenPgpVerificationStatus::Invalid as i32,
        );

        let malformed_signature = b"not an OpenPGP detached signature";
        let malformed_one_shot = one_shot_detached_response(&body, malformed_signature);
        let malformed_stream_open =
            detached_verification_stream_open_response(malformed_signature);
        prop_assert_eq!(
            response_status_code(&malformed_stream_open),
            response_status_code(&malformed_one_shot),
        );
        prop_assert_eq!(
            response_status_code(&malformed_one_shot),
            NativeErrorCode::InvalidArgument as i32,
        );
    }

    #[test]
    fn clear_signed_openpgp_one_shot_and_streaming_match_and_recover_the_body(
        lines in prop::collection::vec("[ -~]{0,40}", 0..12),
        partitions in prop::collection::vec(1_usize..512, 1..16),
    ) {
        let body = lines.join("\n");
        let document = deterministic_clear_signed_document(&body);

        let one_shot_verification = open_pgp_verification(&one_shot_clear_verification(&document));
        prop_assert_eq!(
            one_shot_verification.status,
            OpenPgpVerificationStatus::Valid as i32,
        );

        let (streamed_body, streamed_result) =
            streamed_clear_verification(&document, &partitions);
        prop_assert_eq!(streamed_result.verification, Some(one_shot_verification));
        prop_assert!(streamed_result.body_valid_utf8);
        prop_assert_eq!(
            streamed_body,
            normalized_cleartext_body(body.as_bytes()),
        );
    }
}
