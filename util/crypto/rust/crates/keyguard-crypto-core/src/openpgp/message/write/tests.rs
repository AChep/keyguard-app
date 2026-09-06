#![allow(clippy::expect_used, clippy::panic, clippy::unwrap_used)]

use super::*;
use crate::openpgp::adapter::OpenPgpSession;
use crate::openpgp::adapter::key::wire_key_material;
use crate::openpgp::adapter::wire::{
    OpenPgpClearSignStreamOpenRequest, OpenPgpDecryptFinal, OpenPgpDecryptRequest,
    OpenPgpDecryptResult, OpenPgpDecryptStreamOpenRequest, OpenPgpDecryptionWarning,
    OpenPgpDetachedSignStreamOpenRequest, OpenPgpEncryptFinal, OpenPgpEncryptRequest,
    OpenPgpEncryptResult, OpenPgpEncryptStreamOpenRequest, OpenPgpKeyGenerateRequest,
    OpenPgpKeyImportErrorReason, OpenPgpKeyImportRequest, OpenPgpKeyImportResult, OpenPgpKeyKind,
    OpenPgpKeyMaterial, OpenPgpMetadataResolveRequest, OpenPgpMetadataResolveResult,
    OpenPgpProtectionMode, OpenPgpSignKind, OpenPgpSignRequest, OpenPgpVerification,
    OpenPgpVerificationStatus, OpenPgpVerifyKind, OpenPgpVerifyRequest, open_pgp_key_import_result,
};
use crate::openpgp::adapter::write::{
    clear_sign_input, decrypt as decrypt_request, decrypt_stream_input, detached_sign_input,
    encode_decrypt_final, encode_encrypt_final, encrypt as encrypt_request, encrypt_stream_input,
    sign as sign_request,
};
use crate::openpgp::{
    adapter::key::{generate as generate_key_request, import as import_key_request},
    certificate::filtered_tsk_fixture,
    crypto::signer::SigningKeyRef,
    key::{
        armor_key_packets, encode_key_material, import_secret_packet_public_len,
        subkey_binding_signature,
    },
    packet::{
        PUBLIC_KEY_TAG, SECRET_KEY_TAG, SECRET_SUBKEY_TAG, SIGNATURE_TAG, armor::RawPackets,
        write_fixed_packet,
    },
};
use crate::primitives::PrimitiveError;
use pgp::composed::{
    EncryptionCaps, KeyType, MessageBuilder, SecretKeyParamsBuilder, SubkeyParamsBuilder,
};
use pgp::crypto::ecc_curve::ECCCurve;
use pgp::packet::{
    Features, KeyFlags, PubKeyInner, PublicSubkey, SecretKey, SecretSubkey, SignatureVersion,
};
use pgp::types::{
    Duration, ElgamalPublicParams, EncryptedSecretParams, EncryptionKey, Mpi, PlainSecretParams,
    PublicParams, RevocationKey, RevocationKeyClass, RsaPublicParams, S2kParams, SecretParams,
    StringToKey,
};
use prost::Message as _;

const TEST_TIME: u64 = 1_700_000_000;
static STREAM_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
type EncryptedMessageArmorCase = (&'static str, Vec<u8>, Vec<u8>, OpenPgpProtectionMode, bool);

fn open_detached_sign_session(
    request: OpenPgpDetachedSignStreamOpenRequest,
) -> Result<DetachedSigningSession, OpenPgpWriteError> {
    DetachedSigningSession::open(detached_sign_input(request))
}

fn open_clear_sign_session(
    request: OpenPgpClearSignStreamOpenRequest,
) -> Result<ClearSigningSession, OpenPgpWriteError> {
    ClearSigningSession::open(clear_sign_input(request))
}

fn open_encryption_session(
    request: OpenPgpEncryptStreamOpenRequest,
) -> Result<OpenPgpEncryptionSession, OpenPgpWriteError> {
    OpenPgpEncryptionSession::open(encrypt_stream_input(request))
}

fn open_decryption_session(
    request: OpenPgpDecryptStreamOpenRequest,
) -> Result<OpenPgpDecryptionSession, OpenPgpWriteError> {
    OpenPgpDecryptionSession::open(decrypt_stream_input(request))
}

fn encrypt_literal_metadata_case(
    public_key: &[u8],
    content: &[u8],
    file_name: &str,
    literal_time_epoch_seconds: Option<u64>,
    reference_time_epoch_seconds: u64,
    enable_compression: bool,
    streaming: bool,
) -> (Vec<u8>, OpenPgpProtectionMode) {
    if !streaming {
        let result = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: content.to_vec(),
                public_keys: vec![public_key.to_vec()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: file_name.to_owned(),
                armored: false,
                literal_time_epoch_seconds,
                reference_time_epoch_seconds: Some(reference_time_epoch_seconds),
                enable_compression: Some(enable_compression),
                candidate_revocation_keys: Vec::new(),
            })
            .expect("encrypt literal-metadata case")
            .as_slice(),
        )
        .expect("decode literal-metadata encryption result");
        let mode =
            OpenPgpProtectionMode::try_from(result.protection_mode).expect("known protection mode");
        return (result.data, mode);
    }

    let mut session = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![public_key.to_vec()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: file_name.to_owned(),
        armored: false,
        literal_time_epoch_seconds,
        reference_time_epoch_seconds: Some(reference_time_epoch_seconds),
        enable_compression: Some(enable_compression),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open literal-metadata encryption stream");
    let mut encrypted = Vec::new();
    for chunk in content.chunks(7) {
        encrypted.extend_from_slice(&session.update(chunk).expect("encrypt metadata chunk"));
    }
    let final_result = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(session.finish().expect("finish metadata encryption")).as_slice(),
    )
    .expect("decode literal-metadata encryption final");
    encrypted.extend_from_slice(&final_result.data);
    let mode = OpenPgpProtectionMode::try_from(final_result.protection_mode)
        .expect("known protection mode");
    (encrypted, mode)
}

fn decrypt_literal_metadata_case(
    encrypted: Vec<u8>,
    private_key: &[u8],
    verification_public_key: Option<&[u8]>,
    reference_time_epoch_seconds: u64,
) -> OpenPgpDecryptResult {
    OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted,
            private_keys: vec![private_key.to_vec()],
            verification_public_keys: verification_public_key
                .into_iter()
                .map(<[u8]>::to_vec)
                .collect(),
            reference_time_epoch_seconds: Some(reference_time_epoch_seconds),
            allow_signed_only: None,
        })
        .expect("decrypt literal-metadata case")
        .as_slice(),
    )
    .expect("decode literal-metadata decryption result")
}

fn for_odd_chunks(data: &[u8], sizes: &[usize], mut operation: impl FnMut(&[u8])) {
    let mut offset = 0_usize;
    for size in sizes {
        if offset == data.len() {
            return;
        }
        let end = offset.saturating_add(*size).min(data.len());
        operation(&data[offset..end]);
        offset = end;
    }
    for chunk in data[offset..].chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
        operation(chunk);
    }
}

fn generated_modern_material() -> OpenPgpKeyMaterial {
    OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Alice Example <alice@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: None,
        })
        .expect("generate certificate")
        .as_slice(),
    )
    .expect("decode key material")
}

fn encrypt_recipient_documents(
    public_keys: Vec<Vec<u8>>,
    candidate_revocation_keys: Vec<Vec<u8>>,
    reference_time: u64,
    streaming: bool,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let content = b"strict recipient selection";
    if !streaming {
        let encoded = encrypt_request(OpenPgpEncryptRequest {
            content: content.to_vec(),
            public_keys,
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "strict-recipients.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(reference_time),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: Some(false),
            candidate_revocation_keys,
        })?;
        return Ok(OpenPgpEncryptResult::decode(encoded.as_slice())
            .expect("decode buffered strict-recipient result")
            .data);
    }

    let mut session = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys,
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "strict-recipients.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(reference_time),
        reference_time_epoch_seconds: Some(reference_time),
        enable_compression: Some(false),
        candidate_revocation_keys,
    })?;
    let mut encrypted = session.update(content)?;
    let final_result =
        OpenPgpEncryptFinal::decode(encode_encrypt_final(session.finish()?).as_slice())
            .expect("decode streaming strict-recipient result");
    encrypted.extend_from_slice(&final_result.data);
    Ok(encrypted)
}

fn self_revoked_public_key(material: &OpenPgpKeyMaterial) -> Vec<u8> {
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse self-revoked recipient");
    let signer = SigningKeyRef(&secret.primary_key);
    let revocation = signature_config(SignatureType::KeyRevocation, &signer, TEST_TIME + 1)
        .sign_key(&signer, &Password::empty(), secret.primary_key.public_key())
        .expect("create recipient self-revocation");
    secret.details.revocation_signatures.push(revocation);
    secret
        .to_public_key()
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor self-revoked recipient")
}

fn generated_rsa_material() -> OpenPgpKeyMaterial {
    static MATERIAL: std::sync::OnceLock<OpenPgpKeyMaterial> = std::sync::OnceLock::new();
    MATERIAL
        .get_or_init(|| {
            OpenPgpKeyMaterial::decode(
                generate_key_request(OpenPgpKeyGenerateRequest {
                    kind: OpenPgpKeyKind::Rsa as i32,
                    user_id: "RSA Example <rsa@example.test>".to_owned(),
                    rsa_bits: 3072,
                    creation_time_epoch_seconds: TEST_TIME,
                    expiration_seconds: None,
                })
                .expect("generate RSA certificate")
                .as_slice(),
            )
            .expect("decode RSA key material")
        })
        .clone()
}

fn generated_v4_materials() -> [(&'static str, OpenPgpKeyMaterial); 2] {
    [
        ("LegacyEd25519X25519", generated_modern_material()),
        ("RSA", generated_rsa_material()),
    ]
}

fn assert_gnupg_compatible_encryption_wire(encrypted: &[u8], context: &str) {
    let packets = RawPacketStream::parse(encrypted, MAX_OPENPGP_PACKETS)
        .unwrap_or_else(|error| panic!("scan {context} encrypted message: {error:?}"));
    let pkesks = packets
        .packets()
        .iter()
        .filter(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .collect::<Vec<_>>();
    assert_eq!(pkesks.len(), 1, "{context}");
    assert_eq!(
        packets.body(pkesks[0]).first().copied(),
        Some(3),
        "{context} must use a v3 PKESK",
    );
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::GnupgAeadData))
        .unwrap_or_else(|| panic!("find {context} GnuPG tag-20 packet"));
    assert_eq!(
        packets.body(protected).get(..4),
        Some(
            &[
                1,
                u8::from(SymmetricKeyAlgorithm::AES256),
                u8::from(AeadAlgorithm::Ocb),
                GNUPG_AEAD_CHUNK_OCTET,
            ][..],
        ),
        "{context}",
    );
    assert!(
        packets
            .packets()
            .iter()
            .all(|packet| packet.tag() != u8::from(Tag::SymEncryptedProtectedData)),
        "{context} must not emit RFC 9580 SEIPDv2",
    );
}

#[test]
fn generated_v4_certificates_advertise_the_gnupg_compatible_aead_profile() {
    for (kind, material) in generated_v4_materials() {
        let certificates = parse_public_key_documents(
            std::slice::from_ref(&material.public_key_armored),
            &mut OpenPgpReadBudget::default(),
        )
        .unwrap_or_else(|error| panic!("parse generated {kind} certificate: {error:?}"));
        let candidates = all_components(&certificates);
        let policy = validate_certificate(
            &certificates[0],
            &candidates,
            TEST_TIME + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .unwrap_or_else(|error| panic!("validate generated {kind} certificate: {error:?}"));

        assert_eq!(policy.primary.key.version(), KeyVersion::V4, "{kind}");
        assert!(policy.primary.features.contains(0x01), "{kind}");
        assert!(policy.primary.features.contains(0x02), "{kind}");
        assert!(!policy.primary.features.contains(0x08), "{kind}");
        assert!(policy.primary.preferred_aead.is_none(), "{kind}");
        assert!(!recipient_allows_seipd_v2(&policy), "{kind}");
        assert!(policy.primary.allows_gnupg_ocb, "{kind}");
    }
}

#[test]
fn generated_v4_recipients_use_gnupg_wire_buffered_and_streaming() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"generated V4 GnuPG-compatible encryption";
    let reference_time = TEST_TIME + 1;

    for (kind, material) in generated_v4_materials() {
        for streaming in [false, true] {
            let encrypted = encrypt_preference_test_message(
                std::slice::from_ref(&material),
                plaintext,
                reference_time,
                streaming,
                false,
                OpenPgpProtectionMode::GnupgOcb,
            );
            let context = format!("generated {kind}, streaming={streaming}");
            assert_gnupg_compatible_encryption_wire(&encrypted, &context);
            let result = OpenPgpDecryptResult::decode(
                decrypt_request(OpenPgpDecryptRequest {
                    content: encrypted,
                    private_keys: vec![material.private_key_armored.clone()],
                    verification_public_keys: Vec::new(),
                    reference_time_epoch_seconds: Some(reference_time),
                    allow_signed_only: None,
                })
                .unwrap_or_else(|error| panic!("decrypt {context}: {error:?}"))
                .as_slice(),
            )
            .unwrap_or_else(|error| panic!("decode {context} plaintext: {error}"));
            assert_eq!(result.data, plaintext, "{context}");
        }
    }
}

#[test]
fn generated_v4_private_keys_still_decrypt_rfc9580_seipd_v2() {
    let plaintext = b"RFC 9580 SEIPDv2 remains readable by generated V4 keys";

    for (kind, material) in generated_v4_materials() {
        let (certificate, _) = SignedPublicKey::from_reader_single(Cursor::new(
            material.public_key_armored.as_slice(),
        ))
        .unwrap_or_else(|error| panic!("parse generated {kind} public key: {error:?}"));
        let recipient = certificate
            .public_subkeys
            .iter()
            .find(|subkey| subkey.key.algorithm().can_encrypt())
            .map(|subkey| PublicComponent::Subkey(subkey.key.clone()))
            .unwrap_or_else(|| panic!("find generated {kind} encryption subkey"));
        let composed = build_composed_message(
            plaintext,
            b"rfc9580.bin",
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            None,
            &[],
            None,
        )
        .unwrap_or_else(|error| panic!("compose generated {kind} RFC 9580 message: {error:?}"));
        let encrypted = encrypt_composed_message(
            composed.as_slice(),
            std::slice::from_ref(&recipient),
            ProtectionMode::SeipdV2Aead,
            SymmetricKeyAlgorithm::AES256,
        )
        .unwrap_or_else(|error| panic!("encrypt generated {kind} RFC 9580 message: {error:?}"));

        let packets = RawPacketStream::parse(&encrypted, MAX_OPENPGP_PACKETS)
            .unwrap_or_else(|error| panic!("scan generated {kind} RFC 9580 message: {error:?}"));
        let pkesk = packets
            .packets()
            .iter()
            .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
            .unwrap_or_else(|| panic!("find generated {kind} RFC 9580 PKESK"));
        assert_eq!(packets.body(pkesk).first().copied(), Some(6), "{kind}");
        let protected = packets
            .packets()
            .iter()
            .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
            .unwrap_or_else(|| panic!("find generated {kind} RFC 9580 SEIPD"));
        assert_eq!(packets.body(protected).first().copied(), Some(2), "{kind}",);

        let secret = parse_secret_key(&material.private_key_armored)
            .unwrap_or_else(|error| panic!("parse generated {kind} secret key: {error:?}"));
        Message::from_bytes(encrypted.as_slice())
            .unwrap_or_else(|error| {
                panic!("rPGP parses generated {kind} RFC 9580 message: {error}")
            })
            .decrypt(&Password::empty(), &secret)
            .unwrap_or_else(|error| {
                panic!("rPGP decrypts generated {kind} RFC 9580 message: {error}")
            });
        let result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.to_vec(),
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
                allow_signed_only: None,
            })
            .unwrap_or_else(|error| panic!("decrypt generated {kind} RFC 9580 message: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode generated {kind} RFC 9580 plaintext: {error}"));
        assert_eq!(result.data, plaintext, "{kind}");
    }
}

#[test]
fn third_party_v4_standard_and_dual_advertisements_still_select_rfc9580() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"third-party RFC 9580 negotiation remains standards-first";
    let reference_time = 1_800_000_000;

    for (profile, material) in [
        ("standard-only", generated_v4_standard_aead_material()),
        ("dual-advertising", generated_dual_aead_material()),
    ] {
        for streaming in [false, true] {
            let encrypted = encrypt_seipd_v2_test_message(
                std::slice::from_ref(&material),
                plaintext,
                reference_time,
                streaming,
            );
            assert_seipd_v2_test_message(
                &encrypted,
                std::slice::from_ref(&material),
                plaintext,
                reference_time,
                SymmetricKeyAlgorithm::AES256,
            );
            let packets = RawPacketStream::parse(&encrypted, MAX_OPENPGP_PACKETS)
                .unwrap_or_else(|error| panic!("scan {profile} message: {error:?}"));
            let pkesk = packets
                .packets()
                .iter()
                .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
                .unwrap_or_else(|| panic!("find {profile} PKESK"));
            assert_eq!(
                packets.body(pkesk).first().copied(),
                Some(6),
                "{profile}, streaming={streaming}",
            );
        }
    }
}

fn encrypt_to_rsa_material(material: &OpenPgpKeyMaterial, plaintext: &[u8]) -> Vec<u8> {
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse RSA budget fixture");
    let mut rng = AwsLcRng;
    let mut builder = MessageBuilder::from_bytes("private-attempt-budget.bin", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_to_key(&mut rng, &secret.secret_subkeys[1].public_key())
        .expect("encrypt RSA budget fixture");
    builder.to_vec(rng).expect("serialize RSA budget fixture")
}

fn rewrite_first_packet_body(
    input: &[u8],
    tag: Tag,
    rewrite: impl FnOnce(&mut Vec<u8>),
) -> Vec<u8> {
    let packets =
        RawPacketStream::parse(input, MAX_OPENPGP_PACKETS).expect("scan packet stream for rewrite");
    let mut rewrite = Some(rewrite);
    let mut output = Vec::with_capacity(input.len());
    for packet in packets.packets() {
        if packet.tag() == u8::from(tag) && rewrite.is_some() {
            let mut body = packets.body_to_vec(packet);
            rewrite.take().expect("rewrite is present")(&mut body);
            write_fixed_packet(packet.tag(), &body, &mut output)
                .expect("serialize rewritten packet");
        } else {
            output.extend_from_slice(packets.raw(packet));
        }
    }
    assert!(rewrite.is_none(), "target packet must be present");
    output
}

fn rsa_pkesk_with_plaintext(encrypted: &[u8], private_key: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let packets =
        RawPacketStream::parse(encrypted, MAX_OPENPGP_PACKETS).expect("scan RSA encrypted message");
    let body = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .map(|packet| packets.body_to_vec(packet))
        .expect("RSA PKESK packet");
    assert_eq!(body.first().copied(), Some(3));
    assert!(body.len() > 10);
    assert!(matches!(
        PublicKeyAlgorithm::from(body[9]),
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ));

    let secret = parse_secret_key(private_key).expect("parse RSA recipient");
    let recipient = secret
        .secret_subkeys
        .iter()
        .find(|subkey| subkey.key.legacy_key_id().as_ref() == &body[1..9])
        .expect("find RSA recipient subkey");
    let values = recipient
        .key
        .public_key()
        .encrypt(AwsLcRng, plaintext, EskType::V3_4)
        .expect("encrypt crafted RSA PKESK plaintext");

    rewrite_first_packet_body(encrypted, Tag::PublicKeyEncryptedSessionKey, move |body| {
        body.truncate(10);
        values
            .to_writer(body)
            .expect("serialize crafted RSA PKESK values");
    })
}

fn rsa_message_with_anonymous_pkesks(
    encrypted: &[u8],
    copies: usize,
    retain_exact_pkesk: bool,
) -> Vec<u8> {
    let packets =
        RawPacketStream::parse(encrypted, MAX_OPENPGP_PACKETS).expect("scan RSA encrypted message");
    let pkesk = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .expect("RSA PKESK packet");
    let mut anonymous_body = packets.body_to_vec(pkesk);
    assert_eq!(anonymous_body.first().copied(), Some(3));
    anonymous_body[1..9].fill(0);

    let mut output = Vec::with_capacity(encrypted.len() + copies * anonymous_body.len());
    for _ in 0..copies {
        write_fixed_packet(pkesk.tag(), &anonymous_body, &mut output)
            .expect("serialize anonymous RSA PKESK");
    }
    for packet in packets.packets() {
        if retain_exact_pkesk || !std::ptr::eq(packet, pkesk) {
            output.extend_from_slice(packets.raw(packet));
        }
    }
    output
}

fn rsa_certificate_with_target_at_candidate_65(
    material: &OpenPgpKeyMaterial,
) -> (Vec<u8>, SecretSubkey) {
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse RSA late-candidate fixture");
    assert_eq!(secret.secret_subkeys.len(), 2);
    let target = secret.secret_subkeys[1].key.clone();

    let packets = RawPacketStream::parse(&material.private_key_armored, MAX_OPENPGP_PACKETS)
        .expect("scan RSA late-candidate fixture");
    let subkey_positions = packets
        .packets()
        .iter()
        .enumerate()
        .filter_map(|(index, packet)| (packet.tag() == SECRET_SUBKEY_TAG).then_some(index))
        .collect::<Vec<_>>();
    assert_eq!(subkey_positions.len(), 2);
    let template_start = subkey_positions[0];
    let template_end = subkey_positions[1];
    let template = &packets.packets()[template_start];
    let template_body = packets.body_to_vec(template);
    let public_len = import_secret_packet_public_len(&packets, template)
        .expect("parse RSA template public fields");
    assert!(public_len < template_body.len());
    assert_eq!(template_body.first().copied(), Some(4));
    assert!(matches!(
        PublicKeyAlgorithm::from(template_body[5]),
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ));

    let mut certificate = Vec::new();
    for packet in packets.packets().iter().take(template_start) {
        certificate.extend_from_slice(packets.raw(packet));
    }
    // Varying the creation-time octet gives each otherwise-valid clone a
    // distinct fingerprint. All clones hold the first subkey's RSA material,
    // so they cannot decrypt a PKESK for the second real subkey. This makes the
    // real target the 65th compatible component without 64 key generations.
    for variant in 1_u8..=63 {
        let mut body = template_body.clone();
        body[4] ^= variant;
        write_fixed_packet(template.tag(), &body, &mut certificate)
            .expect("serialize mismatched RSA subkey");
        for packet in &packets.packets()[template_start + 1..template_end] {
            certificate.extend_from_slice(packets.raw(packet));
        }
    }
    for packet in packets.packets().iter().skip(template_end) {
        certificate.extend_from_slice(packets.raw(packet));
    }
    (certificate, target)
}

fn buffered_public_decryption_error(encrypted: Vec<u8>, private_key: Vec<u8>) -> PrimitiveError {
    crate::openpgp::adapter::decrypt(OpenPgpDecryptRequest {
        content: encrypted,
        private_keys: vec![private_key],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME),
        allow_signed_only: None,
    })
    .expect_err("crafted encrypted message must fail")
}

fn streaming_public_decryption_error(encrypted: &[u8], private_key: Vec<u8>) -> PrimitiveError {
    let mut session = OpenPgpSession::decrypt(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME),
        allow_signed_only: None,
    })
    .expect("open crafted decryption stream");
    for chunk in encrypted.chunks(17) {
        if let Err(error) = session.update(chunk) {
            return error;
        }
    }
    session
        .finish()
        .expect_err("crafted encrypted stream must fail")
}

#[test]
fn rsa_pkesk_processing_and_payload_authentication_share_public_error() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_rsa_material();
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"uniform RSA PKESK failure".to_vec(),
            public_keys: vec![
                material.public_key_armored.clone(),
                include_bytes!("../../../../tests/fixtures/openpgp/mdc-public.asc").to_vec(),
            ],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "pkesk-oracle.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(1_800_000_000),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt RSA oracle regression message")
        .as_slice(),
    )
    .expect("decode RSA oracle regression message")
    .data;

    // Arrange independently reachable failures covered by RFC 9580 section
    // 13.5: invalid PKCS#1 padding, invalid session-key size, checksum failure,
    // unsupported algorithm, a valid but wrong session key, and bad payload
    // authentication after the original PKESK decoded successfully.
    let bad_padding =
        rewrite_first_packet_body(&encrypted, Tag::PublicKeyEncryptedSessionKey, |body| {
            *body.last_mut().expect("RSA ciphertext") ^= 1
        });
    let mut bad_size_plaintext = vec![u8::from(SymmetricKeyAlgorithm::AES256)];
    bad_size_plaintext.extend_from_slice(&[0_u8; 31]);
    bad_size_plaintext.extend_from_slice(&[0, 0]);
    let bad_size = rsa_pkesk_with_plaintext(
        &encrypted,
        &material.private_key_armored,
        &bad_size_plaintext,
    );
    let mut bad_checksum_plaintext = vec![u8::from(SymmetricKeyAlgorithm::AES256)];
    bad_checksum_plaintext.extend_from_slice(&[0_u8; 32]);
    bad_checksum_plaintext.extend_from_slice(&[0, 1]);
    let bad_checksum = rsa_pkesk_with_plaintext(
        &encrypted,
        &material.private_key_armored,
        &bad_checksum_plaintext,
    );
    let unsupported_algorithm =
        rsa_pkesk_with_plaintext(&encrypted, &material.private_key_armored, &[100, 0, 0]);
    let wrong_key = [0xa5_u8; 32];
    let wrong_key_checksum = wrong_key
        .iter()
        .fold(0_u16, |sum, byte| sum.wrapping_add(u16::from(*byte)));
    let mut wrong_key_plaintext = vec![u8::from(SymmetricKeyAlgorithm::AES256)];
    wrong_key_plaintext.extend_from_slice(&wrong_key);
    wrong_key_plaintext.extend_from_slice(&wrong_key_checksum.to_be_bytes());
    let wrong_key = rsa_pkesk_with_plaintext(
        &encrypted,
        &material.private_key_armored,
        &wrong_key_plaintext,
    );
    let mut bad_payload_authentication = encrypted.clone();
    *bad_payload_authentication
        .last_mut()
        .expect("encrypted payload") ^= 1;

    // Act and assert the buffered public adapter exposes one class for every
    // failure after the matching RSA key is attempted.
    for (label, crafted) in [
        ("padding", bad_padding.clone()),
        ("session-key size", bad_size),
        ("checksum", bad_checksum.clone()),
        ("algorithm", unsupported_algorithm),
        ("wrong session key", wrong_key.clone()),
        ("payload authentication", bad_payload_authentication.clone()),
    ] {
        assert_eq!(
            buffered_public_decryption_error(crafted, material.private_key_armored.clone()),
            PrimitiveError::AuthenticationFailed,
            "{label}",
        );
    }

    // The worker may surface its terminal result during update or finish; both
    // are projected through the same stable streaming error mapping.
    for (label, crafted) in [
        ("padding", bad_padding),
        ("checksum", bad_checksum),
        ("wrong session key", wrong_key),
        ("payload authentication", bad_payload_authentication),
    ] {
        assert_eq!(
            streaming_public_decryption_error(&crafted, material.private_key_armored.clone()),
            PrimitiveError::AuthenticationFailed,
            "streaming {label}",
        );
    }

    // A supplied key that cannot match or attempt the recipient retains the
    // honest public NoUsableKey classification.
    let unrelated = generated_modern_material();
    assert_eq!(
        buffered_public_decryption_error(encrypted, unrelated.private_key_armored.clone()),
        PrimitiveError::NoUsableKey,
    );
}

#[test]
fn private_key_attempt_budget_boundary_is_inclusive() {
    let mut budget = PrivateKeyAttemptBudget::new(2);
    assert!(budget.consume());
    assert!(budget.consume());
    assert_eq!(budget.attempts, 2);
    assert!(!budget.exhausted);

    assert!(!budget.consume());
    assert_eq!(budget.attempts, 2);
    assert!(budget.exhausted);
}

#[test]
fn private_key_candidates_preserve_same_fingerprint_secret_packets() {
    let material = generated_rsa_material();
    let first = parse_private_certificate(&material.private_key_armored)
        .expect("parse first RSA certificate");
    let second = parse_private_certificate(&material.private_key_armored)
        .expect("parse repeated RSA certificate");
    let single_count = private_key_candidates(std::slice::from_ref(&first))
        .expect("enumerate one certificate")
        .len();
    let repeated_certificates = [first, second];
    let repeated =
        private_key_candidates(&repeated_certificates).expect("enumerate repeated certificate");

    assert_eq!(repeated.len(), 2 * single_count);
}

#[test]
fn exact_pkesk_precedes_a_wire_earlier_anonymous_pkesk() {
    let material = generated_rsa_material();
    let encrypted = encrypt_to_rsa_material(&material, b"exact recipient wins");
    let anonymous_first = rsa_message_with_anonymous_pkesks(&encrypted, 1, true);
    let message = Message::from_bytes(Cursor::new(anonymous_first.as_slice()))
        .expect("parse mixed-recipient message");
    let secret = parse_private_certificate(&material.private_key_armored)
        .expect("parse exact recipient certificate");
    let mut budget = PrivateKeyAttemptBudget::new(1);

    let recovered =
        find_message_session_key_with_budget(&message, std::slice::from_ref(&secret), &mut budget)
            .expect("exact recipient must be routed before wildcard work");

    assert_eq!(budget.attempts, 1);
    assert!(!budget.exhausted);
    assert!(
        secret
            .subkeys()
            .iter()
            .any(|subkey| &subkey.fingerprint() == recovered.key_fingerprint())
    );
}

#[test]
fn anonymous_pkesk_search_has_one_global_private_operation_budget() {
    let material = generated_rsa_material();
    let encrypted = encrypt_to_rsa_material(&material, b"bounded wildcard failure");
    let invalid = rsa_pkesk_with_plaintext(&encrypted, &material.private_key_armored, &[100, 0, 0]);
    let secret = parse_private_certificate(&material.private_key_armored)
        .expect("parse wildcard candidate certificate");
    let candidate_count = private_key_candidates(std::slice::from_ref(&secret))
        .expect("enumerate wildcard candidates")
        .len();

    let one_wildcard = rsa_message_with_anonymous_pkesks(&invalid, 1, false);
    let message = Message::from_bytes(Cursor::new(one_wildcard.as_slice()))
        .expect("parse one-wildcard message");
    let mut inclusive = PrivateKeyAttemptBudget::new(candidate_count);
    let error = match find_message_session_key_with_budget(
        &message,
        std::slice::from_ref(&secret),
        &mut inclusive,
    ) {
        Err(error) => error,
        Ok(_) => panic!("invalid wildcard PKESK must not recover a session key"),
    };
    assert_eq!(error, OpenPgpWriteError::AuthenticationFailed);
    assert_eq!(inclusive.attempts, candidate_count);
    assert!(!inclusive.exhausted);

    let many_wildcards = rsa_message_with_anonymous_pkesks(&invalid, 3, false);
    let message = Message::from_bytes(Cursor::new(many_wildcards.as_slice()))
        .expect("parse many-wildcard message");
    let mut global = PrivateKeyAttemptBudget::new(candidate_count);
    let error = match find_message_session_key_with_budget(
        &message,
        std::slice::from_ref(&secret),
        &mut global,
    ) {
        Err(error) => error,
        Ok(_) => panic!("invalid wildcard PKESKs must not recover a session key"),
    };
    assert_eq!(error, OpenPgpWriteError::ResourceLimit);
    assert_eq!(global.attempts, candidate_count);
    assert!(global.exhausted);
}

#[test]
fn incompatible_private_algorithms_do_not_consume_attempt_budget() {
    let material = generated_rsa_material();
    let encrypted = encrypt_to_rsa_material(&material, b"algorithm prefilter");
    let anonymous = rsa_message_with_anonymous_pkesks(&encrypted, 1, false);
    let message = Message::from_bytes(Cursor::new(anonymous.as_slice()))
        .expect("parse anonymous RSA message");
    let unrelated = generated_modern_material();
    let secret = parse_private_certificate(&unrelated.private_key_armored)
        .expect("parse non-RSA certificate");
    let mut budget = PrivateKeyAttemptBudget::new(0);

    let error = match find_message_session_key_with_budget(
        &message,
        std::slice::from_ref(&secret),
        &mut budget,
    ) {
        Err(error) => error,
        Ok(_) => panic!("incompatible key must not recover an RSA session key"),
    };
    assert_eq!(error, OpenPgpWriteError::MissingKey);
    assert_eq!(budget.attempts, 0);
    assert!(!budget.exhausted);
}

#[test]
fn hidden_recipient_reaches_candidate_65_buffered_and_streaming() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_rsa_material();
    let (private_key, target) = rsa_certificate_with_target_at_candidate_65(&material);
    let parsed = parse_private_certificate(&private_key)
        .expect("primary plus 64 private subkeys must remain accepted");
    assert!(parsed.primary().is_some());
    assert_eq!(parsed.subkeys().len(), MAX_OPENPGP_COMPONENTS);
    assert_eq!(
        private_key_candidates(std::slice::from_ref(&parsed))
            .expect("enumerate late-candidate certificate")
            .len(),
        MAX_OPENPGP_PRIVATE_COMPONENTS_PER_CERTIFICATE,
    );
    assert_eq!(
        parsed.subkeys().last().map(KeyDetails::fingerprint),
        Some(target.fingerprint()),
    );

    let plaintext = b"the hidden recipient at candidate 65 is reachable";
    let mut rng = AwsLcRng;
    let mut builder = MessageBuilder::from_bytes("candidate-65.bin", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_to_key_anonymous(&mut rng, &target.public_key())
        .expect("encrypt for the late hidden recipient");
    let encrypted = builder
        .to_vec(rng)
        .expect("serialize late hidden-recipient message");
    let expected_fingerprint = fingerprint_hex(&target);

    let one_shot = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.clone(),
            private_keys: vec![private_key.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("decrypt candidate-65 hidden recipient")
        .as_slice(),
    )
    .expect("decode candidate-65 one-shot result");
    assert_eq!(one_shot.data, plaintext);
    assert_eq!(
        one_shot.decryption_key_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );

    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME),
        allow_signed_only: None,
    })
    .expect("open candidate-65 streaming decrypt");
    let mut streamed = Vec::new();
    for chunk in encrypted.chunks(17) {
        streamed.extend_from_slice(
            &session
                .update(chunk)
                .expect("update candidate-65 streaming decrypt"),
        );
    }
    let final_result = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(
            session
                .finish()
                .expect("finish candidate-65 streaming decrypt"),
        )
        .as_slice(),
    )
    .expect("decode candidate-65 streaming result");
    streamed.extend_from_slice(&final_result.data);
    assert_eq!(streamed, plaintext);
    assert_eq!(
        final_result.decryption_key_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );
}

fn elgamal_encryption_certificate(algorithm: PublicKeyAlgorithm) -> SignedPublicKey {
    assert!(matches!(
        algorithm,
        PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt
    ));
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("ElGamal Recipient <elgamal@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build ElGamal host certificate")
        .generate(AwsLcRng)
        .expect("generate ElGamal host certificate");

    // The selection test never performs ElGamal encryption.  These small,
    // syntactically valid public values are sufficient to construct and bind
    // a legacy packet whose policy treatment is under test.
    let mut encoded_params = Vec::new();
    for value in [23_u8, 5, 8] {
        Mpi::from_slice(&[value])
            .to_writer(&mut encoded_params)
            .expect("serialize ElGamal parameter");
    }
    let params = ElgamalPublicParams::try_from_reader(
        Cursor::new(encoded_params),
        algorithm == PublicKeyAlgorithm::ElgamalEncrypt,
    )
    .expect("parse ElGamal public parameters");
    let subkey = PublicSubkey::from_inner(
        PubKeyInner::new(
            KeyVersion::V4,
            algorithm,
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            PublicParams::Elgamal(params),
        )
        .expect("construct ElGamal public subkey"),
    )
    .expect("wrap ElGamal public subkey");

    let mut flags = KeyFlags::default();
    flags.set_encrypt_comms(true);
    flags.set_encrypt_storage(true);
    let mut binding = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    binding.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            TEST_TIME as u32,
        )))
        .expect("ElGamal binding creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("ElGamal binding issuer"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("ElGamal binding key flags"),
    ];
    let binding = binding
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("bind ElGamal encryption subkey");
    binding
        .verify_subkey_binding(secret.primary_key.public_key(), &subkey)
        .expect("ElGamal binding remains mathematically valid");

    let mut certificate = secret.to_public_key();
    certificate.public_subkeys = vec![pgp::composed::SignedPublicSubKey::new(
        subkey,
        vec![binding],
    )];
    certificate
}

fn rsa_encryption_certificate(algorithm: PublicKeyAlgorithm, bits: u16) -> SignedPublicKey {
    assert!(matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ));
    assert!(bits >= 8);
    let mut modulus = vec![0_u8; usize::from(bits).div_ceil(8)];
    modulus[0] = 1 << ((bits - 1) % 8);
    *modulus.last_mut().expect("non-empty RSA modulus") |= 1;

    let mut encoded_params = Vec::new();
    for value in [modulus.as_slice(), &[1, 0, 1]] {
        Mpi::from_slice(value)
            .to_writer(&mut encoded_params)
            .expect("serialize RSA parameter");
    }
    let params = RsaPublicParams::try_from_reader(Cursor::new(encoded_params))
        .expect("parse RSA public parameters");
    let subkey = PublicSubkey::from_inner(
        PubKeyInner::new(
            KeyVersion::V4,
            algorithm,
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            PublicParams::RSA(params),
        )
        .expect("construct RSA public subkey"),
    )
    .expect("wrap RSA public subkey");

    let material = generated_modern_material();
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse RSA host certificate");

    let mut flags = KeyFlags::default();
    flags.set_encrypt_comms(true);
    flags.set_encrypt_storage(true);
    let mut binding = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    binding.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            TEST_TIME as u32,
        )))
        .expect("RSA binding creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("RSA binding issuer"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("RSA binding key flags"),
    ];
    let binding = binding
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("bind RSA encryption subkey");
    binding
        .verify_subkey_binding(secret.primary_key.public_key(), &subkey)
        .expect("RSA binding remains mathematically valid");

    let mut certificate = secret.to_public_key();
    certificate.public_subkeys = vec![pgp::composed::SignedPublicSubKey::new(
        subkey,
        vec![binding],
    )];
    certificate
}

#[test]
fn authenticated_elgamal_subkeys_are_not_selected_for_new_encryption() {
    let request = |public_key| OpenPgpEncryptRequest {
        content: b"algorithm policy".to_vec(),
        public_keys: vec![public_key],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "algorithm-policy.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    };

    let modern = generated_modern_material();
    encrypt_request(request(modern.public_key_armored.clone()))
        .expect("modern X25519 recipient remains selectable");

    for algorithm in [
        PublicKeyAlgorithm::ElgamalEncrypt,
        PublicKeyAlgorithm::Elgamal,
    ] {
        let certificate = elgamal_encryption_certificate(algorithm);
        let candidates = all_components(std::slice::from_ref(&certificate));
        let policy = validate_certificate(
            &certificate,
            &candidates,
            TEST_TIME + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("authenticate ElGamal host certificate");
        let subkey = policy
            .subkey_components()
            .next()
            .expect("one ElGamal subkey");
        assert!(subkey.policy().authenticated, "{algorithm:?}");
        assert!(!subkey.encryption_usable(), "{algorithm:?}");

        let public_key = certificate
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor ElGamal certificate");
        assert_eq!(
            encrypt_request(request(public_key)),
            Err(OpenPgpWriteError::MissingKey),
            "{algorithm:?}",
        );
    }
}

#[test]
fn rsa_encryption_components_require_at_least_2048_bits() {
    let request = |public_key| OpenPgpEncryptRequest {
        content: b"RSA encryption strength policy".to_vec(),
        public_keys: vec![public_key],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "rsa-strength-policy.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    };

    for algorithm in [PublicKeyAlgorithm::RSA, PublicKeyAlgorithm::RSAEncrypt] {
        for (bits, expected_usable) in [(1_024, false), (2_048, true)] {
            let certificate = rsa_encryption_certificate(algorithm, bits);
            let candidates = all_components(std::slice::from_ref(&certificate));
            let policy = validate_certificate(
                &certificate,
                &candidates,
                TEST_TIME + 1,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("authenticate RSA host certificate");
            assert!(policy.primary_available(), "{algorithm:?} RSA-{bits}");
            let subkey = policy
                .subkey_components()
                .next()
                .expect("one RSA encryption subkey");
            assert!(subkey.policy().authenticated, "{algorithm:?} RSA-{bits}");
            assert_eq!(
                subkey.encryption_usable(),
                expected_usable,
                "{algorithm:?} RSA-{bits}",
            );

            let selection = select_recipients(
                std::slice::from_ref(&certificate),
                &[],
                TEST_TIME + 1,
                &mut OpenPgpReadBudget::default(),
            );
            if expected_usable {
                let (recipients, _, _) = selection.expect("select RSA recipient");
                assert_eq!(recipients.len(), 1, "{algorithm:?} RSA-{bits}");
            } else {
                assert!(
                    matches!(selection, Err(OpenPgpWriteError::MissingKey)),
                    "{algorithm:?} RSA-{bits}",
                );
            }

            let public_key = certificate
                .to_armored_bytes(ArmorOptions::default())
                .expect("armor RSA certificate");
            let result = encrypt_request(request(public_key));
            if expected_usable {
                result.expect("RSA-2048 remains usable for encryption");
            } else {
                assert_eq!(
                    result,
                    Err(OpenPgpWriteError::MissingKey),
                    "{algorithm:?} RSA-{bits}",
                );
            }
        }
    }
}

fn decryption_warning_material(
    algorithm: PublicKeyAlgorithm,
    public_params: PublicParams,
    secret_params: SecretParams,
) -> (OpenPgpKeyMaterial, SecretSubkey) {
    let mut host = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Historical Decryption <historical@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build historical decryption host")
        .generate(AwsLcRng)
        .expect("generate historical decryption host");
    let public = PublicSubkey::from_inner(
        PubKeyInner::new(
            KeyVersion::V4,
            algorithm,
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            public_params,
        )
        .expect("construct historical encryption subkey"),
    )
    .expect("wrap historical encryption subkey");
    let subkey = SecretSubkey::new(public, secret_params)
        .expect("attach historical private encryption material");
    let binding = subkey_binding_signature(
        SigningKeyRef(&host.primary_key),
        host.primary_key.public_key(),
        subkey.public_key(),
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        false,
        None,
    )
    .expect("bind historical encryption subkey");
    host.secret_subkeys
        .push(pgp::composed::SignedSecretSubKey::new(
            subkey.clone(),
            vec![binding],
        ));
    let material =
        wire_key_material(encode_key_material(&host).expect("encode historical key material"));
    (material, subkey)
}

fn rsa_decryption_warning_material(bits: u32) -> (OpenPgpKeyMaterial, SecretSubkey) {
    let (public, secret) = KeyType::Rsa(bits)
        .generate(AwsLcRng)
        .unwrap_or_else(|error| panic!("generate RSA-{bits} historical key: {error}"));
    decryption_warning_material(PublicKeyAlgorithm::RSA, public, secret)
}

fn elgamal_decryption_warning_material() -> (OpenPgpKeyMaterial, SecretSubkey) {
    // 2^521 - 1 is a Mersenne prime and leaves enough room for OpenPGP's
    // PKCS#1-v1_5 encoded AES-256 session key. x=2 therefore gives y=4.
    let mut prime = vec![0xff; 66];
    prime[0] = 0x01;
    let mut encoded_public = Vec::new();
    for value in [prime.as_slice(), &[2], &[4]] {
        Mpi::from_slice(value)
            .to_writer(&mut encoded_public)
            .expect("serialize ElGamal warning parameter");
    }
    let public = PublicParams::Elgamal(
        ElgamalPublicParams::try_from_reader(Cursor::new(encoded_public), true)
            .expect("parse ElGamal warning parameters"),
    );
    let mut encoded_secret = Vec::new();
    Mpi::from_slice(&[2])
        .to_writer(&mut encoded_secret)
        .expect("serialize ElGamal warning secret");
    let secret = PlainSecretParams::try_from_reader_no_checksum(
        Cursor::new(encoded_secret),
        KeyVersion::V4,
        PublicKeyAlgorithm::ElgamalEncrypt,
        &public,
    )
    .expect("parse ElGamal warning secret");
    decryption_warning_material(
        PublicKeyAlgorithm::ElgamalEncrypt,
        public,
        SecretParams::Plain(secret),
    )
}

fn warning_test_message(target: &SecretSubkey, plaintext: &[u8]) -> Vec<u8> {
    let mut rng = AwsLcRng;
    let mut builder = MessageBuilder::from_bytes("warning.bin", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_to_key(&mut rng, &target.public_key())
        .expect("encrypt historical warning fixture");
    builder
        .to_vec(rng)
        .expect("serialize historical warning fixture")
}

fn assert_buffered_and_streaming_decryption_warnings(
    encrypted: &[u8],
    private_keys: &[&[u8]],
    plaintext: &[u8],
    expected: &[OpenPgpDecryptionWarning],
) {
    let private_keys = private_keys
        .iter()
        .map(|key| (*key).to_vec())
        .collect::<Vec<_>>();
    let expected = expected
        .iter()
        .copied()
        .map(|warning| warning as i32)
        .collect::<Vec<_>>();
    let buffered = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.to_vec(),
            private_keys: private_keys.clone(),
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            allow_signed_only: None,
        })
        .expect("buffered historical decryption")
        .as_slice(),
    )
    .expect("decode buffered historical decryption");
    assert_eq!(buffered.data, plaintext);
    assert!(buffered.verification.is_none());
    assert_eq!(buffered.warnings, expected);

    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys,
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        allow_signed_only: None,
    })
    .expect("open streaming historical decryption");
    let mut streamed = Vec::new();
    for chunk in encrypted.chunks(23) {
        streamed.extend_from_slice(
            &session
                .update(chunk)
                .expect("update streaming historical decryption"),
        );
    }
    let final_result = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(
            session
                .finish()
                .expect("finish streaming historical decryption"),
        )
        .as_slice(),
    )
    .expect("decode streaming historical decryption");
    streamed.extend_from_slice(&final_result.data);
    assert_eq!(streamed, plaintext);
    assert!(final_result.verification.is_none());
    assert_eq!(final_result.warnings, expected);
}

#[test]
fn successful_rsa_decryption_warns_below_3072_bits_at_exact_boundaries() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"RSA historical decryption warning";

    for (bits, warn) in [(2_047, true), (2_048, true), (3_071, true), (3_072, false)] {
        let (material, target) = rsa_decryption_warning_material(bits);
        let mut encoded = Vec::with_capacity(target.public_params().write_len());
        target
            .public_params()
            .to_writer(&mut encoded)
            .expect("serialize generated RSA public parameters");
        assert_eq!(u16::from_be_bytes([encoded[0], encoded[1]]), bits as u16);
        let encrypted = warning_test_message(&target, plaintext);
        let expected = if warn {
            &[OpenPgpDecryptionWarning::WeakRsaKey][..]
        } else {
            &[]
        };
        assert_buffered_and_streaming_decryption_warnings(
            &encrypted,
            &[&material.private_key_armored],
            plaintext,
            expected,
        );
    }
}

#[test]
fn failed_weak_rsa_attempt_does_not_warn_when_strong_rsa_recovers_the_message() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"only the successful recipient controls the warning";
    let (weak_material, weak_target) = rsa_decryption_warning_material(2_048);
    let (strong_material, strong_target) = rsa_decryption_warning_material(3_072);
    let mut rng = AwsLcRng;
    let mut builder = MessageBuilder::from_bytes("warning-attempt.bin", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_to_key(&mut rng, &weak_target.public_key())
        .expect("encrypt session key to weak RSA recipient");
    builder
        .encrypt_to_key(&mut rng, &strong_target.public_key())
        .expect("encrypt session key to strong RSA recipient");
    let encrypted = builder
        .to_vec(rng)
        .expect("serialize multiple-recipient warning fixture");
    let encrypted =
        rewrite_first_packet_body(&encrypted, Tag::PublicKeyEncryptedSessionKey, |body| {
            assert_eq!(body.get(1..9), Some(weak_target.legacy_key_id().as_ref()));
            *body.last_mut().expect("weak RSA ciphertext") ^= 1;
        });

    assert_buffered_and_streaming_decryption_warnings(
        &encrypted,
        &[
            &weak_material.private_key_armored,
            &strong_material.private_key_armored,
        ],
        plaintext,
        &[],
    );
}

#[test]
fn elgamal_components_are_classified_for_successful_recovery_warning() {
    let (material, target) = elgamal_decryption_warning_material();
    let parsed = parse_private_certificate(&material.private_key_armored)
        .expect("parse ElGamal warning certificate");
    let packet = parsed
        .subkeys()
        .iter()
        .find(|subkey| subkey.fingerprint() == target.fingerprint())
        .map(SecretPacketRef::Subkey)
        .expect("find ElGamal warning component");

    assert_eq!(
        decryption_warning(packet),
        Some(DecryptionWarning::ElgamalKey),
    );
}

#[test]
fn ecdh_and_x25519_decryption_do_not_report_weak_key_warnings() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"modern decryption has no asymmetric warning";

    for material in [
        generated_modern_material(),
        generated_v6_encryption_material(),
    ] {
        let parsed = parse_private_certificate(&material.private_key_armored)
            .expect("parse modern warning-control key");
        let target = parsed
            .subkeys()
            .iter()
            .find(|subkey| subkey.algorithm().can_encrypt())
            .expect("modern encryption subkey");
        let encrypted = warning_test_message(target, plaintext);
        assert_buffered_and_streaming_decryption_warnings(
            &encrypted,
            &[&material.private_key_armored],
            plaintext,
            &[],
        );
    }
}

#[test]
fn mixed_usable_and_unusable_recipients_fail_buffered_and_streaming_selection() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let usable = generated_modern_material();
    let expired = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Recipient <expired-recipient@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: Some(1),
        })
        .expect("generate expired recipient")
        .as_slice(),
    )
    .expect("decode expired recipient");
    let revoked = self_revoked_public_key(&generated_modern_material());
    let signing_only = generated_v6_signing_key()
        .to_public_key()
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor signing-only recipient");
    let reference_time = TEST_TIME + 2;

    for (label, unusable) in [
        ("expired", expired.public_key_armored.clone()),
        ("revoked", revoked),
        ("without an encryption component", signing_only),
    ] {
        for streaming in [false, true] {
            assert_eq!(
                encrypt_recipient_documents(
                    vec![usable.public_key_armored.clone(), unusable.clone()],
                    Vec::new(),
                    reference_time,
                    streaming,
                ),
                Err(OpenPgpWriteError::MissingKey),
                "mixed usable + {label}, streaming={streaming}",
            );
        }
    }
}

#[test]
fn all_unusable_recipients_fail_buffered_and_streaming_selection() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let expired = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Recipient <expired-recipient@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: Some(1),
        })
        .expect("generate expired recipient")
        .as_slice(),
    )
    .expect("decode expired recipient")
    .public_key_armored
    .clone();
    let revoked = self_revoked_public_key(&generated_modern_material());
    let signing_only = generated_v6_signing_key()
        .to_public_key()
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor signing-only recipient");
    let reference_time = TEST_TIME + 2;

    for (label, public_keys) in [
        ("expired only", vec![expired.clone()]),
        ("revoked only", vec![revoked.clone()]),
        ("signing-only", vec![signing_only.clone()]),
        ("all unusable classes", vec![expired, revoked, signing_only]),
    ] {
        for streaming in [false, true] {
            assert_eq!(
                encrypt_recipient_documents(
                    public_keys.clone(),
                    Vec::new(),
                    reference_time,
                    streaming,
                ),
                Err(OpenPgpWriteError::MissingKey),
                "{label}, streaming={streaming}",
            );
        }
    }
}

fn generated_v6_signing_key() -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("V6 Armor <v6-armor@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 signing key parameters")
        .generate(AwsLcRng)
        .expect("generate v6 signing key")
}

fn generated_v6_encryption_material() -> OpenPgpKeyMaterial {
    generated_v6_encryption_material_with_v1_cipher(SymmetricKeyAlgorithm::AES256)
}

fn generated_v6_encryption_material_with_v1_cipher(
    v1_cipher: SymmetricKeyAlgorithm,
) -> OpenPgpKeyMaterial {
    generated_v6_encryption_material_with_preferences(
        "v6-encryption",
        v1_cipher,
        &[(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)],
    )
}

fn generated_v6_encryption_material_with_preferences(
    label: &str,
    v1_cipher: SymmetricKeyAlgorithm,
    preferred_aead: &[(SymmetricKeyAlgorithm, AeadAlgorithm)],
) -> OpenPgpKeyMaterial {
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .feature_seipd_v2(true)
        .preferred_symmetric_algorithms(vec![v1_cipher].into())
        .preferred_aead_algorithms(preferred_aead.to_vec().into())
        .primary_user_id(format!("{label} <{label}@example.test>"))
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .passphrase(None)
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V6)
                .key_type(KeyType::X25519)
                .can_encrypt(EncryptionCaps::All)
                .created_at(Timestamp::from_secs(TEST_TIME as u32))
                .build()
                .expect("build V6 encryption subkey"),
        )
        .build()
        .expect("build V6 encryption certificate")
        .generate(AwsLcRng)
        .expect("generate V6 encryption certificate");
    wire_key_material(encode_key_material(&secret).expect("encode V6 encryption material"))
}

fn rewrite_effective_primary_features(
    material: OpenPgpKeyMaterial,
    features: Option<u8>,
    remove_aead_preferences: bool,
) -> OpenPgpKeyMaterial {
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse recipient material for primary-policy rewrite");
    let version = secret.primary_key.version();
    let template = match version {
        KeyVersion::V4 => secret.details.users[0].signatures[0]
            .config()
            .expect("V4 effective certification")
            .clone(),
        KeyVersion::V6 => secret.details.direct_signatures[0]
            .config()
            .expect("V6 effective Direct Key signature")
            .clone(),
        _ => panic!("unsupported primary version in test fixture: {version:?}"),
    };
    let mut config = match version {
        KeyVersion::V4 => SignatureConfig::v4(
            template.typ,
            secret.primary_key.algorithm(),
            template.hash_alg,
        ),
        KeyVersion::V6 => SignatureConfig::v6(
            AwsLcRng,
            template.typ,
            secret.primary_key.algorithm(),
            template.hash_alg,
        )
        .expect("create fresh V6 signature salt"),
        _ => unreachable!("version checked above"),
    };
    config.hashed_subpackets = template.hashed_subpackets;
    config.unhashed_subpackets = template.unhashed_subpackets;
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(subpacket.data, SubpacketData::Features(_))
            && !(remove_aead_preferences
                && matches!(subpacket.data, SubpacketData::PreferredAeadAlgorithms(_)))
    });
    if let Some(features) = features {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::Features(Features::from(&[features][..])))
                .expect("replacement Features subpacket"),
        );
    }

    let signature = match version {
        KeyVersion::V4 => config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &secret.details.users[0].id,
            )
            .expect("re-sign V4 primary certification"),
        KeyVersion::V6 => config
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("re-sign V6 Direct Key policy"),
        _ => unreachable!("version checked above"),
    };
    match version {
        KeyVersion::V4 => secret.details.users[0].signatures = vec![signature],
        KeyVersion::V6 => secret.details.direct_signatures = vec![signature],
        _ => unreachable!("version checked above"),
    }
    wire_key_material(encode_key_material(&secret).expect("encode rewritten recipient material"))
}

#[derive(Clone, Copy)]
struct EncryptionSubkeyPolicy<'a> {
    features: Option<u8>,
    preferred_symmetric: Option<&'a [SymmetricKeyAlgorithm]>,
    preferred_compression: Option<&'a [CompressionAlgorithm]>,
    preferred_aead: Option<&'a [(SymmetricKeyAlgorithm, AeadAlgorithm)]>,
    preferred_encryption_modes: Option<&'a [AeadAlgorithm]>,
}

fn replace_encryption_subkey_binding(
    secret: &mut SignedSecretKey,
    policy: EncryptionSubkeyPolicy<'_>,
) {
    let subkey_index = secret
        .secret_subkeys
        .iter()
        .position(|subkey| subkey.key.algorithm().can_encrypt())
        .expect("find encryption subkey for policy rewrite");
    let subkey = secret.secret_subkeys[subkey_index].key.public_key().clone();
    let mut flags = KeyFlags::default();
    flags.set_encrypt_comms(true);
    flags.set_encrypt_storage(true);
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            TEST_TIME as u32,
        )))
        .expect("subkey-policy binding creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("subkey-policy binding issuer"),
        Subpacket::regular(SubpacketData::KeyFlags(flags))
            .expect("subkey-policy binding key flags"),
    ];
    if let Some(features) = policy.features {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::Features(Features::from(&[features][..])))
                .expect("subkey-policy Features"),
        );
    }
    if let Some(preferences) = policy.preferred_symmetric {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                preferences.to_vec().into(),
            ))
            .expect("subkey-policy symmetric preferences"),
        );
    }
    if let Some(preferences) = policy.preferred_compression {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
                preferences.to_vec().into(),
            ))
            .expect("subkey-policy compression preferences"),
        );
    }
    if let Some(preferences) = policy.preferred_aead {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
                preferences.to_vec().into(),
            ))
            .expect("subkey-policy AEAD preferences"),
        );
    }
    if let Some(preferences) = policy.preferred_encryption_modes {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredEncryptionModes(
                preferences.to_vec().into(),
            ))
            .expect("subkey-policy encryption-mode preferences"),
        );
    }
    let binding = config
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("sign rewritten encryption subkey binding");
    secret.secret_subkeys[subkey_index].signatures = vec![binding];
}

fn rewrite_encryption_subkey_policy(
    material: OpenPgpKeyMaterial,
    policy: EncryptionSubkeyPolicy<'_>,
) -> OpenPgpKeyMaterial {
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse recipient material for subkey-policy rewrite");
    replace_encryption_subkey_binding(&mut secret, policy);
    wire_key_material(encode_key_material(&secret).expect("encode subkey-policy material"))
}

fn generated_gnupg_only_material() -> OpenPgpKeyMaterial {
    generated_aead_material("GnuPG-only", false, true)
}

fn generated_dual_aead_material() -> OpenPgpKeyMaterial {
    generated_aead_material("GnuPG-and-RFC9580", true, true)
}

fn generated_v4_standard_aead_material() -> OpenPgpKeyMaterial {
    generated_aead_material("RFC9580-only", true, false)
}

fn generated_aead_material(
    label: &str,
    supports_seipd_v2: bool,
    supports_gnupg_ocb: bool,
) -> OpenPgpKeyMaterial {
    let created_at = Timestamp::from_secs(TEST_TIME as u32);
    let mut secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .feature_seipd_v1(true)
        .feature_seipd_v2(false)
        .primary_user_id(format!("{label} <{label}@example.test>"))
        .created_at(created_at)
        .passphrase(None)
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V4)
                .key_type(KeyType::ECDH(ECCCurve::Curve25519Legacy))
                .can_encrypt(EncryptionCaps::All)
                .created_at(created_at)
                .build()
                .expect("build GnuPG-only encryption subkey"),
        )
        .build()
        .expect("build GnuPG-compatible certificate")
        .generate(AwsLcRng)
        .expect("generate GnuPG-compatible certificate");

    let mut flags = KeyFlags::default();
    flags.set_certify(true);
    flags.set_sign(true);
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    let mut advertised_features = 0x01_u8;
    if supports_gnupg_ocb {
        advertised_features |= 0x02;
    }
    if supports_seipd_v2 {
        advertised_features |= 0x08;
    }
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .expect("GnuPG-only signature time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("GnuPG-only issuer fingerprint"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("GnuPG-only primary flags"),
        Subpacket::regular(SubpacketData::IsPrimary(true)).expect("GnuPG-only primary User ID"),
        Subpacket::regular(SubpacketData::Features(Features::from(
            &[advertised_features][..],
        )))
        .expect("GnuPG-compatible features"),
        Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
            vec![SymmetricKeyAlgorithm::AES256].into(),
        ))
        .expect("GnuPG-compatible symmetric preferences"),
        Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
            vec![CompressionAlgorithm::ZIP].into(),
        ))
        .expect("GnuPG-compatible compression preferences"),
    ];
    if supports_gnupg_ocb {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredEncryptionModes(
                vec![AeadAlgorithm::Ocb].into(),
            ))
            .expect("GnuPG-compatible encryption-mode preferences"),
        );
    }
    if supports_seipd_v2 {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
                vec![(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)].into(),
            ))
            .expect("RFC 9580 AEAD preferences"),
        );
    }
    let user_id = secret.details.users[0].id.clone();
    let certification = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign GnuPG-compatible self-certification");
    secret.details.users[0].signatures = vec![certification];
    let standard_aead = [(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)];
    let gnupg_modes = [AeadAlgorithm::Ocb];
    replace_encryption_subkey_binding(
        &mut secret,
        EncryptionSubkeyPolicy {
            features: Some(advertised_features),
            preferred_symmetric: Some(&[SymmetricKeyAlgorithm::AES256]),
            preferred_compression: Some(&[CompressionAlgorithm::ZIP]),
            preferred_aead: supports_seipd_v2.then_some(standard_aead.as_slice()),
            preferred_encryption_modes: supports_gnupg_ocb.then_some(gnupg_modes.as_slice()),
        },
    );

    wire_key_material(encode_key_material(&secret).expect("encode GnuPG-compatible material"))
}

fn generated_v4_preference_material(
    label: &str,
    preferred_symmetric: Option<&[SymmetricKeyAlgorithm]>,
    preferred_compression: Option<&[CompressionAlgorithm]>,
) -> OpenPgpKeyMaterial {
    let created_at = Timestamp::now();
    let mut secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .feature_seipd_v1(true)
        .feature_seipd_v2(false)
        .primary_user_id(format!("{label} <{label}@example.test>"))
        .created_at(created_at)
        .passphrase(None)
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V4)
                .key_type(KeyType::ECDH(ECCCurve::Curve25519Legacy))
                .can_encrypt(EncryptionCaps::All)
                .created_at(created_at)
                .build()
                .expect("build preference-test encryption subkey"),
        )
        .build()
        .expect("build preference-test certificate")
        .generate(AwsLcRng)
        .expect("generate preference-test certificate");

    let mut flags = KeyFlags::default();
    flags.set_certify(true);
    flags.set_sign(true);
    let signature_time = Timestamp::now();
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(signature_time))
            .expect("preference-test signature time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("preference-test issuer fingerprint"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("preference-test primary flags"),
        Subpacket::regular(SubpacketData::IsPrimary(true))
            .expect("preference-test primary User ID"),
        Subpacket::regular(SubpacketData::Features(Features::from(&[0x01][..])))
            .expect("preference-test SEIPDv1 feature"),
    ];
    if let Some(preferences) = preferred_symmetric {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                preferences.to_vec().into(),
            ))
            .expect("preference-test symmetric preferences"),
        );
    }
    if let Some(preferences) = preferred_compression {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
                preferences.to_vec().into(),
            ))
            .expect("preference-test compression preferences"),
        );
    }
    let user_id = secret.details.users[0].id.clone();
    let certification = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign preference-test self-certification");
    secret.details.users[0].signatures = vec![certification];
    replace_encryption_subkey_binding(
        &mut secret,
        EncryptionSubkeyPolicy {
            features: Some(0x01),
            preferred_symmetric,
            preferred_compression,
            preferred_aead: None,
            preferred_encryption_modes: None,
        },
    );

    wire_key_material(encode_key_material(&secret).expect("encode preference-test material"))
}

fn encrypt_preference_test_message(
    materials: &[OpenPgpKeyMaterial],
    plaintext: &[u8],
    reference_time: u64,
    streaming: bool,
    enable_compression: bool,
    expected_protection_mode: OpenPgpProtectionMode,
) -> Vec<u8> {
    let public_keys = materials
        .iter()
        .map(|material| material.public_key_armored.clone())
        .collect::<Vec<_>>();
    if !streaming {
        let result = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys,
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "preferences.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(reference_time),
                reference_time_epoch_seconds: Some(reference_time),
                enable_compression: Some(enable_compression),
                candidate_revocation_keys: Vec::new(),
            })
            .expect("buffered preference-test encryption")
            .as_slice(),
        )
        .expect("decode buffered preference-test result");
        assert_eq!(result.protection_mode, expected_protection_mode as i32,);
        return result.data;
    }

    let mut session = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys,
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "preferences.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(reference_time),
        reference_time_epoch_seconds: Some(reference_time),
        enable_compression: Some(enable_compression),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open streaming preference-test encryption");
    let mut output = Vec::new();
    for chunk in plaintext.chunks(11) {
        output.extend_from_slice(
            &session
                .update(chunk)
                .expect("streaming preference-test update"),
        );
    }
    let result = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(
            session
                .finish()
                .expect("finish streaming preference-test encryption"),
        )
        .as_slice(),
    )
    .expect("decode streaming preference-test result");
    assert_eq!(result.protection_mode, expected_protection_mode as i32,);
    output.extend_from_slice(&result.data);
    output
}

fn encrypt_seipd_v2_test_message(
    materials: &[OpenPgpKeyMaterial],
    plaintext: &[u8],
    reference_time: u64,
    streaming: bool,
) -> Vec<u8> {
    let public_keys = materials
        .iter()
        .map(|material| material.public_key_armored.clone())
        .collect::<Vec<_>>();
    if !streaming {
        let result = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys,
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "seipdv2-negotiation.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(reference_time),
                reference_time_epoch_seconds: Some(reference_time),
                enable_compression: Some(false),
                candidate_revocation_keys: Vec::new(),
            })
            .expect("buffered SEIPDv2 negotiation encryption")
            .as_slice(),
        )
        .expect("decode buffered SEIPDv2 negotiation result");
        assert_eq!(
            result.protection_mode,
            OpenPgpProtectionMode::SeipdV2Aead as i32,
        );
        return result.data;
    }

    let mut session = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys,
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "seipdv2-negotiation.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(reference_time),
        reference_time_epoch_seconds: Some(reference_time),
        enable_compression: Some(false),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open streaming SEIPDv2 negotiation encryption");
    let mut output = Vec::new();
    for chunk in plaintext.chunks(11) {
        output.extend_from_slice(
            &session
                .update(chunk)
                .expect("streaming SEIPDv2 negotiation update"),
        );
    }
    let result = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(
            session
                .finish()
                .expect("finish streaming SEIPDv2 negotiation encryption"),
        )
        .as_slice(),
    )
    .expect("decode streaming SEIPDv2 negotiation result");
    assert_eq!(
        result.protection_mode,
        OpenPgpProtectionMode::SeipdV2Aead as i32,
    );
    output.extend_from_slice(&result.data);
    output
}

fn assert_seipd_v2_test_message(
    encrypted: &[u8],
    materials: &[OpenPgpKeyMaterial],
    plaintext: &[u8],
    reference_time: u64,
    expected_symmetric: SymmetricKeyAlgorithm,
) {
    let packets = RawPacketStream::parse(encrypted, MAX_OPENPGP_PACKETS)
        .expect("scan SEIPDv2 negotiation message");
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
        .expect("SEIPDv2 negotiation packet");
    assert_eq!(
        &packets.body(protected)[..3],
        &[
            2,
            u8::from(expected_symmetric),
            u8::from(AeadAlgorithm::Ocb),
        ],
    );

    for material in materials {
        let result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.to_vec(),
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(reference_time),
                allow_signed_only: None,
            })
            .expect("decrypt SEIPDv2 negotiation message")
            .as_slice(),
        )
        .expect("decode SEIPDv2 negotiation plaintext");
        assert_eq!(result.data, plaintext);
    }
}

fn assert_preference_test_message(
    encrypted: &[u8],
    materials: &[OpenPgpKeyMaterial],
    plaintext: &[u8],
    reference_time: u64,
    expected_symmetric: Option<SymmetricKeyAlgorithm>,
    expected_compression: Option<CompressionAlgorithm>,
) {
    let secret = parse_secret_key(&materials[0].private_key_armored)
        .expect("parse preference-test recipient");
    let usable = parse_private_certificate(&materials[0].private_key_armored)
        .expect("parse usable preference-test recipient");
    let message = Message::from_bytes(encrypted).expect("parse preference-test message");
    let recovered = find_message_session_key(&message, std::slice::from_ref(&usable))
        .expect("recover preference-test session key");
    assert_eq!(recovered.symmetric_algorithm(), expected_symmetric);
    let decrypted_message = message
        .decrypt(&Password::empty(), &secret)
        .expect("decrypt preference-test packet structure");
    let actual_compression = match decrypted_message {
        Message::Compressed { mut reader, .. } => {
            let mut algorithm = [0_u8];
            reader
                .read_exact(&mut algorithm)
                .expect("read compressed packet algorithm");
            Some(CompressionAlgorithm::from(algorithm[0]))
        }
        _ => None,
    };
    assert_eq!(actual_compression, expected_compression);

    for material in materials {
        let result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.to_vec(),
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(reference_time),
                allow_signed_only: None,
            })
            .expect("decrypt preference-test message")
            .as_slice(),
        )
        .expect("decode preference-test plaintext");
        assert_eq!(result.data, plaintext);
    }
}

#[test]
fn recipient_algorithm_negotiation_intersects_preferences_and_applies_rfc_defaults() {
    type RecipientPreferences<'a> = (Option<&'a [u8]>, Option<&'a [u8]>);

    let negotiate = |preferences: &[RecipientPreferences<'_>], enable_compression| {
        let mut support = RecipientProtectionSupport::new();
        support.all_allow_seipd_v2 = false;
        support.all_allow_gnupg_ocb = false;
        for (symmetric, compression) in preferences {
            support.intersect_preferences(*symmetric, *compression);
        }
        select_message_algorithms(&support, enable_compression).expect("select algorithms")
    };

    let aes192 = [u8::from(SymmetricKeyAlgorithm::AES192)];
    let zlib = [u8::from(CompressionAlgorithm::ZLIB)];
    let single = negotiate(&[(Some(&aes192), Some(&zlib))], true);
    assert_eq!(single.symmetric, SymmetricKeyAlgorithm::AES192);
    assert_eq!(single.compression, Some(CompressionAlgorithm::ZLIB));

    let aes256_aes192 = [
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(SymmetricKeyAlgorithm::AES192),
    ];
    let zip_zlib = [
        u8::from(CompressionAlgorithm::ZIP),
        u8::from(CompressionAlgorithm::ZLIB),
    ];
    let intersection = negotiate(
        &[
            (Some(&aes256_aes192), Some(&zip_zlib)),
            (Some(&aes192), Some(&zlib)),
        ],
        true,
    );
    assert_eq!(intersection.symmetric, SymmetricKeyAlgorithm::AES192);
    assert_eq!(intersection.compression, Some(CompressionAlgorithm::ZLIB));

    let absent = negotiate(&[(None, None)], true);
    assert_eq!(absent.symmetric, SymmetricKeyAlgorithm::AES128);
    assert_eq!(absent.compression, None);

    let aes256 = [u8::from(SymmetricKeyAlgorithm::AES256)];
    let zip = [u8::from(CompressionAlgorithm::ZIP)];
    let explicit_zip = negotiate(&[(Some(&aes256), Some(&zip))], true);
    assert_eq!(explicit_zip.compression, Some(CompressionAlgorithm::ZIP));

    let uncompressed = [u8::from(CompressionAlgorithm::Uncompressed)];
    let explicit_uncompressed = negotiate(&[(Some(&aes256), Some(&uncompressed))], true);
    assert_eq!(explicit_uncompressed.compression, None);

    let absent_and_zip = negotiate(&[(Some(&aes256), None), (Some(&aes256), Some(&zip))], true);
    assert_eq!(absent_and_zip.compression, None);

    let disjoint_advertised = negotiate(
        &[(Some(&aes256), Some(&zip)), (Some(&aes192), Some(&zlib))],
        true,
    );
    assert_eq!(
        disjoint_advertised.symmetric,
        SymmetricKeyAlgorithm::AES128,
        "AES-128 is tacitly appended to both advertised lists",
    );
    assert_eq!(
        disjoint_advertised.compression, None,
        "Uncompressed is tacitly appended to both advertised lists",
    );

    let disabled = negotiate(&[(Some(&aes192), Some(&zlib))], false);
    assert_eq!(disabled.compression, None);
}

#[test]
fn protection_mode_negotiation_prefers_standard_aead_and_uses_compatible_fallbacks() {
    let select = |allows_seipd_v2, allows_gnupg_ocb| {
        let mut support = RecipientProtectionSupport::new();
        support.all_allow_seipd_v2 = allows_seipd_v2;
        support.all_allow_gnupg_ocb = allows_gnupg_ocb;
        select_protection_mode(&support)
    };

    assert_eq!(select(true, true), ProtectionMode::SeipdV2Aead);
    assert_eq!(select(true, false), ProtectionMode::SeipdV2Aead);
    assert_eq!(select(false, true), ProtectionMode::GnupgOcb);
    assert_eq!(select(false, false), ProtectionMode::SeipdV1Mdc);

    let mut no_common_standard_suite = RecipientProtectionSupport::new();
    no_common_standard_suite.common_seipd_v2.clear();
    assert_eq!(
        select_protection_mode(&no_common_standard_suite),
        ProtectionMode::GnupgOcb,
        "LibrePGP/GnuPG OCB remains available when no standard suite is common",
    );
}

#[test]
fn seipd_v2_ciphersuite_negotiation_intersects_preferences_and_applies_implicit_default() {
    type AeadPreferences<'a> = Option<&'a [(u8, u8)]>;

    let negotiate = |preferences: &[AeadPreferences<'_>]| {
        let mut support = RecipientProtectionSupport::new();
        support.all_allow_gnupg_ocb = false;
        for preference in preferences {
            support.intersect_aead_preferences(*preference);
        }
        select_message_algorithms(&support, false).expect("select SEIPDv2 ciphersuite")
    };
    let aes128_ocb = [(
        u8::from(SymmetricKeyAlgorithm::AES128),
        u8::from(AeadAlgorithm::Ocb),
    )];
    let aes256_ocb = [(
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(AeadAlgorithm::Ocb),
    )];
    let aes128_then_aes256 = [aes128_ocb[0], aes256_ocb[0]];

    let aes128_only = negotiate(&[Some(&aes128_ocb)]);
    assert_eq!(aes128_only.protection_mode, ProtectionMode::SeipdV2Aead);
    assert_eq!(aes128_only.symmetric, SymmetricKeyAlgorithm::AES128);

    let sender_preference = negotiate(&[Some(&aes128_then_aes256)]);
    assert_eq!(sender_preference.symmetric, SymmetricKeyAlgorithm::AES256);

    let intersection = negotiate(&[Some(&aes256_ocb), Some(&aes128_ocb)]);
    assert_eq!(intersection.symmetric, SymmetricKeyAlgorithm::AES128);

    for implicit in [None, Some(&[][..]), Some(&aes256_ocb[..])] {
        let selected = negotiate(&[implicit]);
        assert_eq!(selected.protection_mode, ProtectionMode::SeipdV2Aead);
        assert_eq!(
            selected.symmetric,
            if implicit == Some(&aes256_ocb[..]) {
                SymmetricKeyAlgorithm::AES256
            } else {
                SymmetricKeyAlgorithm::AES128
            },
        );
    }

    let mut no_common_suite = RecipientProtectionSupport::new();
    no_common_suite.all_allow_gnupg_ocb = false;
    no_common_suite.common_seipd_v2.clear();
    let fallback = select_message_algorithms(&no_common_suite, false)
        .expect("fall back to v1 when no SEIPDv2 suite is common");
    assert_eq!(fallback.protection_mode, ProtectionMode::SeipdV1Mdc);

    no_common_suite.common_v1_symmetric.clear();
    assert_eq!(
        select_message_algorithms(&no_common_suite, false),
        Err(OpenPgpWriteError::InvalidArgument),
    );
}

#[test]
fn buffered_and_streaming_seipd_v2_writers_use_the_negotiated_ciphersuite() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    struct Scenario {
        label: &'static str,
        materials: Vec<OpenPgpKeyMaterial>,
        symmetric: SymmetricKeyAlgorithm,
    }

    let scenarios = vec![
        Scenario {
            label: "AES-128-only recipient",
            materials: vec![generated_v6_encryption_material_with_preferences(
                "aes128-only",
                SymmetricKeyAlgorithm::AES256,
                &[(SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb)],
            )],
            symmetric: SymmetricKeyAlgorithm::AES128,
        },
        Scenario {
            label: "sender AES-256 preference",
            materials: vec![generated_v6_encryption_material_with_preferences(
                "sender-aes256-preference",
                SymmetricKeyAlgorithm::AES128,
                &[
                    (SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb),
                    (SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb),
                ],
            )],
            symmetric: SymmetricKeyAlgorithm::AES256,
        },
        Scenario {
            label: "multi-recipient intersection",
            materials: vec![
                generated_v6_encryption_material_with_preferences(
                    "intersection-aes256",
                    SymmetricKeyAlgorithm::AES256,
                    &[(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)],
                ),
                generated_v6_encryption_material_with_preferences(
                    "intersection-aes128",
                    SymmetricKeyAlgorithm::AES256,
                    &[(SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb)],
                ),
            ],
            symmetric: SymmetricKeyAlgorithm::AES128,
        },
        Scenario {
            label: "implicit AES-128/OCB default",
            materials: vec![generated_v6_encryption_material_with_preferences(
                "implicit-aes128-ocb",
                SymmetricKeyAlgorithm::AES256,
                &[],
            )],
            symmetric: SymmetricKeyAlgorithm::AES128,
        },
    ];
    let reference_time = u64::from(Timestamp::now().as_secs()) + 2;
    let plaintext = b"RFC 9580 ordered AEAD ciphersuite negotiation";

    for scenario in scenarios {
        for streaming in [false, true] {
            let encrypted = encrypt_seipd_v2_test_message(
                &scenario.materials,
                plaintext,
                reference_time,
                streaming,
            );
            assert!(!encrypted.is_empty(), "{}", scenario.label);
            assert_seipd_v2_test_message(
                &encrypted,
                &scenario.materials,
                plaintext,
                reference_time,
                scenario.symmetric,
            );
        }
    }
}

#[test]
fn buffered_and_streaming_writers_use_the_same_negotiated_preferences() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    struct Scenario {
        label: &'static str,
        materials: Vec<OpenPgpKeyMaterial>,
        protection_mode: OpenPgpProtectionMode,
        symmetric: Option<SymmetricKeyAlgorithm>,
        enable_compression: bool,
        compression: Option<CompressionAlgorithm>,
    }

    let scenarios = vec![
        Scenario {
            label: "dual-advertising third-party recipient explicitly advertising ZIP",
            materials: vec![generated_dual_aead_material()],
            protection_mode: OpenPgpProtectionMode::SeipdV2Aead,
            symmetric: None,
            enable_compression: true,
            compression: Some(CompressionAlgorithm::ZIP),
        },
        Scenario {
            label: "explicit ZIP",
            materials: vec![generated_v4_preference_material(
                "explicit-zip",
                Some(&[SymmetricKeyAlgorithm::AES192]),
                Some(&[CompressionAlgorithm::ZIP]),
            )],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: true,
            compression: Some(CompressionAlgorithm::ZIP),
        },
        Scenario {
            label: "explicit ZLIB",
            materials: vec![generated_v4_preference_material(
                "explicit-zlib",
                Some(&[SymmetricKeyAlgorithm::AES192]),
                Some(&[CompressionAlgorithm::ZLIB]),
            )],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: true,
            compression: Some(CompressionAlgorithm::ZLIB),
        },
        Scenario {
            label: "multi-recipient intersection",
            materials: vec![
                generated_v4_preference_material(
                    "intersection-first",
                    Some(&[SymmetricKeyAlgorithm::AES256, SymmetricKeyAlgorithm::AES192]),
                    Some(&[CompressionAlgorithm::ZIP, CompressionAlgorithm::ZLIB]),
                ),
                generated_v4_preference_material(
                    "intersection-second",
                    Some(&[SymmetricKeyAlgorithm::AES192]),
                    Some(&[CompressionAlgorithm::ZLIB]),
                ),
            ],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: true,
            compression: Some(CompressionAlgorithm::ZLIB),
        },
        Scenario {
            label: "absent preferences",
            materials: vec![generated_v4_preference_material(
                "absent-preferences",
                None,
                None,
            )],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES128),
            enable_compression: true,
            compression: None,
        },
        Scenario {
            label: "explicit Uncompressed",
            materials: vec![generated_v4_preference_material(
                "explicit-uncompressed",
                Some(&[SymmetricKeyAlgorithm::AES192]),
                Some(&[CompressionAlgorithm::Uncompressed]),
            )],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: true,
            compression: None,
        },
        Scenario {
            label: "absent and explicit ZIP recipients",
            materials: vec![
                generated_v4_preference_material(
                    "mixed-absent",
                    Some(&[SymmetricKeyAlgorithm::AES192]),
                    None,
                ),
                generated_v4_preference_material(
                    "mixed-zip",
                    Some(&[SymmetricKeyAlgorithm::AES192]),
                    Some(&[CompressionAlgorithm::ZIP]),
                ),
            ],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: true,
            compression: None,
        },
        Scenario {
            label: "compression disabled with explicit ZIP",
            materials: vec![generated_v4_preference_material(
                "disabled-zip",
                Some(&[SymmetricKeyAlgorithm::AES192]),
                Some(&[CompressionAlgorithm::ZIP]),
            )],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES192),
            enable_compression: false,
            compression: None,
        },
        Scenario {
            label: "disjoint advertised preferences",
            materials: vec![
                generated_v4_preference_material(
                    "disjoint-first",
                    Some(&[SymmetricKeyAlgorithm::AES256]),
                    Some(&[CompressionAlgorithm::ZIP]),
                ),
                generated_v4_preference_material(
                    "disjoint-second",
                    Some(&[SymmetricKeyAlgorithm::AES192]),
                    Some(&[CompressionAlgorithm::ZLIB]),
                ),
            ],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            symmetric: Some(SymmetricKeyAlgorithm::AES128),
            enable_compression: true,
            compression: None,
        },
    ];
    let reference_time = u64::from(Timestamp::now().as_secs()) + 2;
    let plaintext = b"authenticated recipient preference negotiation";

    for scenario in scenarios {
        for streaming in [false, true] {
            let encrypted = encrypt_preference_test_message(
                &scenario.materials,
                plaintext,
                reference_time,
                streaming,
                scenario.enable_compression,
                scenario.protection_mode,
            );
            assert!(
                !encrypted.is_empty(),
                "{} produced no output (streaming={streaming})",
                scenario.label,
            );
            assert_preference_test_message(
                &encrypted,
                &scenario.materials,
                plaintext,
                reference_time,
                scenario.symmetric,
                scenario.compression,
            );
        }
    }
}

#[test]
fn selected_encryption_subkey_policy_overrides_primary_and_omissions_fall_back() {
    struct Scenario {
        label: &'static str,
        material: OpenPgpKeyMaterial,
        protection_mode: OpenPgpProtectionMode,
        protection: ProtectionMode,
        symmetric: SymmetricKeyAlgorithm,
        pkesk_symmetric: Option<SymmetricKeyAlgorithm>,
        compression: Option<CompressionAlgorithm>,
        allows_gnupg_ocb: bool,
    }

    let fallback = rewrite_encryption_subkey_policy(
        generated_dual_aead_material(),
        EncryptionSubkeyPolicy {
            features: None,
            preferred_symmetric: None,
            preferred_compression: None,
            preferred_aead: None,
            preferred_encryption_modes: None,
        },
    );
    let narrowed_v1 = rewrite_encryption_subkey_policy(
        generated_dual_aead_material(),
        EncryptionSubkeyPolicy {
            features: Some(0x01),
            preferred_symmetric: Some(&[SymmetricKeyAlgorithm::AES128]),
            preferred_compression: Some(&[CompressionAlgorithm::Uncompressed]),
            preferred_aead: None,
            preferred_encryption_modes: None,
        },
    );
    let narrowed_v2 = rewrite_encryption_subkey_policy(
        generated_dual_aead_material(),
        EncryptionSubkeyPolicy {
            features: Some(0x09),
            preferred_symmetric: Some(&[SymmetricKeyAlgorithm::AES128]),
            preferred_compression: Some(&[CompressionAlgorithm::Uncompressed]),
            preferred_aead: Some(&[(SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb)]),
            preferred_encryption_modes: None,
        },
    );
    let scenarios = [
        Scenario {
            label: "omitted binding fields inherit primary policy",
            material: fallback,
            protection_mode: OpenPgpProtectionMode::SeipdV2Aead,
            protection: ProtectionMode::SeipdV2Aead,
            symmetric: SymmetricKeyAlgorithm::AES256,
            pkesk_symmetric: None,
            compression: Some(CompressionAlgorithm::ZIP),
            allows_gnupg_ocb: true,
        },
        Scenario {
            label: "explicit binding disables AEAD and narrows v1 algorithms",
            material: narrowed_v1,
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            protection: ProtectionMode::SeipdV1Mdc,
            symmetric: SymmetricKeyAlgorithm::AES128,
            pkesk_symmetric: Some(SymmetricKeyAlgorithm::AES128),
            compression: None,
            allows_gnupg_ocb: false,
        },
        Scenario {
            label: "explicit binding narrows RFC 9580 ciphersuite",
            material: narrowed_v2,
            protection_mode: OpenPgpProtectionMode::SeipdV2Aead,
            protection: ProtectionMode::SeipdV2Aead,
            symmetric: SymmetricKeyAlgorithm::AES128,
            pkesk_symmetric: None,
            compression: None,
            allows_gnupg_ocb: false,
        },
    ];
    let reference_time = 1_800_000_000;
    let plaintext = b"selected encryption subkey policy controls the message";

    for scenario in scenarios {
        let certificates = parse_public_key_documents(
            std::slice::from_ref(&scenario.material.public_key_armored),
            &mut OpenPgpReadBudget::default(),
        )
        .unwrap_or_else(|error| panic!("parse {}: {error:?}", scenario.label));
        let (_, _, support) = select_recipients(
            &certificates,
            &[],
            reference_time,
            &mut OpenPgpReadBudget::default(),
        )
        .unwrap_or_else(|error| panic!("select {}: {error:?}", scenario.label));
        assert_eq!(
            support.all_allow_gnupg_ocb, scenario.allows_gnupg_ocb,
            "{}",
            scenario.label,
        );
        let algorithms = select_message_algorithms(&support, true)
            .unwrap_or_else(|error| panic!("negotiate {}: {error:?}", scenario.label));
        assert_eq!(
            algorithms.protection_mode, scenario.protection,
            "{}",
            scenario.label,
        );
        assert_eq!(
            algorithms.symmetric, scenario.symmetric,
            "{}",
            scenario.label
        );
        assert_eq!(
            algorithms.compression, scenario.compression,
            "{}",
            scenario.label,
        );

        let encrypted = encrypt_preference_test_message(
            std::slice::from_ref(&scenario.material),
            plaintext,
            reference_time,
            false,
            true,
            scenario.protection_mode,
        );
        assert_preference_test_message(
            &encrypted,
            std::slice::from_ref(&scenario.material),
            plaintext,
            reference_time,
            scenario.pkesk_symmetric,
            scenario.compression,
        );
        let packets = RawPacketStream::parse(&encrypted, MAX_OPENPGP_PACKETS)
            .unwrap_or_else(|error| panic!("scan {}: {error:?}", scenario.label));
        assert!(
            packets
                .packets()
                .iter()
                .all(|packet| packet.tag() != u8::from(Tag::GnupgAeadData)),
            "{}",
            scenario.label,
        );
        if scenario.protection_mode == OpenPgpProtectionMode::SeipdV2Aead {
            let protected = packets
                .packets()
                .iter()
                .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
                .unwrap_or_else(|| panic!("find {} SEIPDv2 packet", scenario.label));
            assert_eq!(
                &packets.body(protected)[..3],
                &[
                    2,
                    u8::from(scenario.symmetric),
                    u8::from(AeadAlgorithm::Ocb),
                ],
                "{}",
                scenario.label,
            );
        }
    }
}

fn armor_has_checksum(data: &[u8]) -> bool {
    data.split(|byte| *byte == b'\n')
        .any(|line| line.first() == Some(&b'='))
}

fn encrypted_message_armor_cases() -> Vec<EncryptedMessageArmorCase> {
    let dual_mode = generated_dual_aead_material();
    let gnupg_only = generated_gnupg_only_material();
    vec![
        (
            "dual-advertising SEIPDv2",
            dual_mode.public_key_armored.clone(),
            dual_mode.private_key_armored.clone(),
            OpenPgpProtectionMode::SeipdV2Aead,
            false,
        ),
        (
            "GnuPG-only OCB",
            gnupg_only.public_key_armored.clone(),
            gnupg_only.private_key_armored.clone(),
            OpenPgpProtectionMode::GnupgOcb,
            true,
        ),
        (
            "SEIPDv1 MDC",
            include_bytes!("../../../../tests/fixtures/openpgp/mdc-public.asc").to_vec(),
            include_bytes!("../../../../tests/fixtures/openpgp/mdc-secret.asc").to_vec(),
            OpenPgpProtectionMode::SeipdV1Mdc,
            true,
        ),
    ]
}

fn signature_armor(document: &[u8]) -> &[u8] {
    let marker = b"-----BEGIN PGP SIGNATURE-----";
    let start = document
        .windows(marker.len())
        .position(|window| window == marker)
        .expect("signature armor marker");
    &document[start..]
}

fn cleartext_preamble(document: &[u8]) -> &[u8] {
    let end = document
        .windows(2)
        .position(|window| window == b"\n\n")
        .expect("cleartext signature preamble");
    &document[..end + 2]
}

fn assert_cleartext_signature_wire(
    document: &[u8],
    expected_preamble: &[u8],
    expected_version: SignatureVersion,
    expected_hash: HashAlgorithm,
) {
    assert_eq!(cleartext_preamble(document), expected_preamble);
    let (signature, _) =
        DetachedSignature::from_reader_single(Cursor::new(signature_armor(document)))
            .expect("armored cleartext signature must reparse");
    assert_eq!(signature.signature.version(), expected_version);
    assert_eq!(signature.signature.hash_alg(), Some(expected_hash));
}

fn assert_signature_armor_reparses(document: &[u8], expected_checksum: bool) {
    let armor = signature_armor(document);
    assert_eq!(armor_has_checksum(armor), expected_checksum);
    DetachedSignature::from_reader_single(Cursor::new(armor))
        .expect("armored signature must reparse");
}

fn armor_test_packets_unchecked(packets: &[u8], block_type: BlockType) -> Vec<u8> {
    let options = ArmorOptions::default();
    let mut output = Vec::new();
    armor::write(
        &RawPackets(packets),
        block_type,
        &mut output,
        options.headers,
        options.include_checksum,
    )
    .expect("armor deliberately malformed test packets");
    output
}

fn armor_crc_test_message(
    packets: &[u8],
    block_type: BlockType,
    include_checksum: bool,
) -> Vec<u8> {
    let mut headers = Headers::new();
    headers.insert("Charset".to_owned(), vec!["ISO-8859-1".to_owned()]);
    let mut output = Vec::new();
    armor::write(
        &RawPackets(packets),
        block_type,
        &mut output,
        Some(&headers),
        include_checksum,
    )
    .expect("armor CRC tolerance test message");
    output
}

fn replace_armor_checksum_lines(input: &[u8], replacements: &[&[u8]]) -> Vec<u8> {
    let footer = b"-----END PGP MESSAGE-----";
    let footer_start = input
        .windows(footer.len())
        .position(|window| window == footer)
        .expect("message armor footer");
    let before_footer = input[..footer_start]
        .strip_suffix(b"\n")
        .expect("line break before armor footer");
    let previous_line_start = before_footer
        .iter()
        .rposition(|byte| *byte == b'\n')
        .map_or(0, |index| index + 1);
    let previous_line = &before_footer[previous_line_start..];
    let checksum_start = if previous_line
        .iter()
        .copied()
        .find(|byte| !matches!(byte, b' ' | b'\t' | b'\r'))
        == Some(b'=')
    {
        previous_line_start
    } else {
        footer_start
    };

    let replacement_bytes = replacements
        .iter()
        .map(|line| line.len() + 1)
        .sum::<usize>();
    let mut output = Vec::with_capacity(input.len() + replacement_bytes);
    output.extend_from_slice(&input[..checksum_start]);
    for replacement in replacements {
        output.extend_from_slice(replacement);
        output.push(b'\n');
    }
    output.extend_from_slice(&input[footer_start..]);
    output
}

fn decrypt_crc_test_message_one_shot(armored: Vec<u8>, private_key: &[u8]) -> OpenPgpDecryptResult {
    OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: armored,
            private_keys: vec![private_key.to_vec()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(1_800_000_000),
            allow_signed_only: None,
        })
        .expect("one-shot armored decryption")
        .as_slice(),
    )
    .expect("decode one-shot armored decryption result")
}

fn decrypt_crc_test_message_streaming(
    armored: &[u8],
    private_key: &[u8],
) -> (Vec<u8>, OpenPgpDecryptFinal) {
    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key.to_vec()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(1_800_000_000),
        allow_signed_only: None,
    })
    .expect("open CRC tolerance decryption stream");
    let footer_start = armored
        .windows(b"-----END PGP MESSAGE-----".len())
        .position(|window| window == b"-----END PGP MESSAGE-----")
        .expect("streamed message armor footer");
    let byte_chunks_start = footer_start.saturating_sub(48);
    let mut plaintext = session
        .update(&armored[..byte_chunks_start])
        .expect("stream armor prefix");
    for byte in &armored[byte_chunks_start..] {
        plaintext.extend_from_slice(
            &session
                .update(std::slice::from_ref(byte))
                .expect("stream armor footer byte"),
        );
    }
    let final_result = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(session.finish().expect("finish armored decryption stream"))
            .as_slice(),
    )
    .expect("decode armored decryption final result");
    plaintext.extend_from_slice(&final_result.data);
    (plaintext, final_result)
}

fn crc_test_streaming_is_rejected(armored: &[u8], private_key: &[u8]) -> bool {
    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key.to_vec()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(1_800_000_000),
        allow_signed_only: None,
    })
    .expect("open strict armor decryption stream");
    for chunk in armored.chunks(17) {
        if session.update(chunk).is_err() {
            return true;
        }
    }
    session.finish().is_err()
}

fn insert_packet_before_encrypted_data(message: &[u8], packet: &[u8]) -> Vec<u8> {
    let packets = RawPacketStream::parse(message, MAX_OPENPGP_PACKETS)
        .expect("scan encrypted message for packet insertion");
    let mut inserted = false;
    let mut saw_esk = false;
    let mut output = Vec::with_capacity(message.len() + packet.len());
    for span in packets.packets() {
        let tag = span.tag();
        saw_esk |= matches!(tag, 1 | 3);
        if !inserted && saw_esk && matches!(tag, 9 | 18 | 20) {
            output.extend_from_slice(packet);
            inserted = true;
        }
        output.extend_from_slice(packets.raw(span));
    }
    assert!(
        inserted,
        "encrypted message must contain an ESK and payload"
    );
    output
}

fn fixed_test_packet(tag: u8, body: &[u8]) -> Vec<u8> {
    let mut packet = Vec::with_capacity(body.len() + 6);
    write_fixed_packet(tag, body, &mut packet).expect("write test packet");
    packet
}

fn decryptable_encrypted_message(
    plaintext: &[u8],
    public_key: &[u8],
) -> Result<Vec<u8>, OpenPgpWriteError> {
    encrypt_request(OpenPgpEncryptRequest {
        content: plaintext.to_vec(),
        public_keys: vec![public_key.to_vec()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "noncritical.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME),
        reference_time_epoch_seconds: Some(1_800_000_000),
        enable_compression: Some(false),
        candidate_revocation_keys: Vec::new(),
    })
}

fn decrypt_envelope_one_shot(
    content: Vec<u8>,
    private_key: &[u8],
) -> Result<OpenPgpDecryptResult, OpenPgpWriteError> {
    let encoded = decrypt_request(OpenPgpDecryptRequest {
        content,
        private_keys: vec![private_key.to_vec()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(1_800_000_000),
        allow_signed_only: None,
    })?;
    Ok(OpenPgpDecryptResult::decode(encoded.as_slice()).expect("decode decryption result"))
}

fn decrypt_envelope_streaming(
    content: &[u8],
    private_key: &[u8],
    chunk_size: usize,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key.to_vec()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(1_800_000_000),
        allow_signed_only: None,
    })?;
    let mut plaintext = Vec::new();
    for chunk in content.chunks(chunk_size) {
        plaintext.extend_from_slice(&session.update(chunk)?);
    }
    plaintext.extend_from_slice(&session.finish()?.data);
    Ok(plaintext)
}

fn inline_direct_key_signature_message(secret: &SignedSecretKey) -> (Vec<u8>, Vec<u8>) {
    let signer = &secret.secret_subkeys[0].key;
    let key = secret.primary_key.public_key();
    let key_len = u16::try_from(key.write_len()).expect("v4 key packet body length");
    let mut content = Vec::with_capacity(key.write_len() + 3);
    content.push(0x99);
    content.extend_from_slice(&key_len.to_be_bytes());
    key.to_writer(&mut content)
        .expect("serialize key hash input");

    let signature_time = Timestamp::from_secs((TEST_TIME + 1) as u32);
    let mut config = data_signature_config(signer, SignatureType::Key)
        .expect("create direct key signature config");
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(signer, signature_time).expect("create signing subpackets")
    else {
        panic!("expected user-defined signing subpackets");
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    let SignatureVersionSpecific::V4 = &config.version_specific else {
        panic!("generated modern fixture must use v4 signatures");
    };
    let one_pass = OnePassSignature::v3(
        SignatureType::Key,
        config.hash_alg,
        signer.algorithm(),
        signer.legacy_key_id(),
    );
    let signature = config
        .sign_key(signer, &Password::empty(), key)
        .expect("sign direct key fixture");
    let mut message = Vec::new();
    one_pass
        .to_writer_with_header(&mut message)
        .expect("serialize one-pass signature");
    write_literal_packet(&mut message, &content, b"typed.bin", signature_time)
        .expect("serialize literal data");
    signature
        .to_writer_with_header(&mut message)
        .expect("serialize typed signature");
    (message, content)
}

fn recipient_bound_inline_message(
    secret: &SignedSecretKey,
    intended_recipient: Fingerprint,
    critical: bool,
) -> (Vec<u8>, Vec<u8>) {
    let signer = &secret.secret_subkeys[0].key;
    let content = b"recipient-bound inline signature".to_vec();
    let signature_time = Timestamp::from_secs((TEST_TIME + 1) as u32);
    let mut config = data_signature_config(signer, SignatureType::Binary)
        .expect("create binary signature config");
    let SubpacketConfig::UserDefined {
        mut hashed,
        unhashed,
    } = signing_subpackets(signer, signature_time).expect("create signing subpackets")
    else {
        panic!("expected user-defined signing subpackets");
    };
    let intended_recipient = SubpacketData::IntendedRecipientFingerprint(intended_recipient);
    hashed.push(
        if critical {
            Subpacket::critical(intended_recipient)
        } else {
            Subpacket::regular(intended_recipient)
        }
        .expect("create intended-recipient subpacket"),
    );
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    let SignatureVersionSpecific::V4 = &config.version_specific else {
        panic!("generated modern fixture must use v4 signatures");
    };
    let one_pass = OnePassSignature::v3(
        SignatureType::Binary,
        config.hash_alg,
        signer.algorithm(),
        signer.legacy_key_id(),
    );
    let signature = config
        .sign(signer, &Password::empty(), Cursor::new(content.as_slice()))
        .expect("sign recipient-bound fixture");
    let mut message = Vec::new();
    one_pass
        .to_writer_with_header(&mut message)
        .expect("serialize one-pass signature");
    write_literal_packet(
        &mut message,
        &content,
        b"recipient-bound.bin",
        signature_time,
    )
    .expect("serialize literal data");
    signature
        .to_writer_with_header(&mut message)
        .expect("serialize recipient-bound signature");
    (message, content)
}

fn decrypt_recipient_bound_case(
    encrypted: &[u8],
    private_key: &[u8],
    verification_public_key: &[u8],
    reference_time: u64,
    streaming: bool,
) -> (Vec<u8>, OpenPgpVerification) {
    if !streaming {
        let mut result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.to_vec(),
                private_keys: vec![private_key.to_vec()],
                verification_public_keys: vec![verification_public_key.to_vec()],
                reference_time_epoch_seconds: Some(reference_time),
                allow_signed_only: None,
            })
            .expect("decrypt recipient-bound message")
            .as_slice(),
        )
        .expect("decode recipient-bound decryption result");
        return (
            std::mem::take(&mut result.data),
            result.verification.take().expect("inline verification"),
        );
    }

    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![private_key.to_vec()],
        verification_public_keys: vec![verification_public_key.to_vec()],
        reference_time_epoch_seconds: Some(reference_time),
        allow_signed_only: None,
    })
    .expect("open recipient-bound decryption stream");
    let mut plaintext = Vec::new();
    for chunk in encrypted.chunks(7) {
        plaintext.extend_from_slice(
            &session
                .update(chunk)
                .expect("stream recipient-bound message"),
        );
    }
    let mut final_result = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(
            session
                .finish()
                .expect("finish recipient-bound decryption stream"),
        )
        .as_slice(),
    )
    .expect("decode recipient-bound decryption final");
    plaintext.extend_from_slice(&final_result.data);
    (
        plaintext,
        final_result
            .verification
            .take()
            .expect("inline verification"),
    )
}

fn signed_encryption_output(
    signing_private_key: &[u8],
    public_keys: &[Vec<u8>],
    candidate_revocation_keys: &[Vec<u8>],
    content: &[u8],
    operation_time: u64,
    streaming: bool,
) -> Vec<u8> {
    if !streaming {
        return OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: content.to_vec(),
                public_keys: public_keys.to_vec(),
                signing_private_key: Some(signing_private_key.to_vec()),
                preferred_signing_fingerprint: String::new(),
                file_name: "recipient-bound.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(operation_time),
                reference_time_epoch_seconds: Some(operation_time + 1),
                enable_compression: Some(false),
                candidate_revocation_keys: candidate_revocation_keys.to_vec(),
            })
            .expect("buffered signed encryption")
            .as_slice(),
        )
        .expect("decode buffered signed encryption")
        .data;
    }

    let mut session = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: public_keys.to_vec(),
        signing_private_key: Some(signing_private_key.to_vec()),
        preferred_signing_fingerprint: String::new(),
        file_name: "recipient-bound.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(operation_time),
        reference_time_epoch_seconds: Some(operation_time + 1),
        enable_compression: Some(false),
        candidate_revocation_keys: candidate_revocation_keys.to_vec(),
    })
    .expect("open streaming signed encryption");
    let mut output = Vec::new();
    for chunk in content.chunks(7) {
        output.extend_from_slice(&session.update(chunk).expect("stream signed encryption"));
    }
    let final_output = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(
            session
                .finish()
                .expect("finish streaming signed encryption"),
        )
        .as_slice(),
    )
    .expect("decode streaming signed encryption final");
    output.extend_from_slice(&final_output.data);
    output
}

fn inline_signature_from_message(mut message: Message<'_>) -> pgp::packet::Signature {
    message = message.decompress().expect("decompress signed message");
    message.as_data_vec().expect("consume signed literal data");
    let Message::Signed { reader, .. } = message else {
        panic!("expected inline signed message");
    };
    reader
        .signature(0)
        .expect("inline signature packet")
        .clone()
}

fn decrypt_inline_signature(encrypted: &[u8], private_key: &[u8]) -> pgp::packet::Signature {
    let secret = parse_secret_key(private_key).expect("parse recipient secret key");
    let password = Password::empty();
    let (message, _) = Message::from_bytes(encrypted)
        .expect("parse encrypted message")
        .decrypt_the_ring(
            TheRing {
                secret_keys: vec![&secret],
                key_passwords: vec![&password],
                decrypt_options: DecryptionOptions::new().enable_gnupg_aead(),
                ..Default::default()
            },
            true,
        )
        .expect("decrypt signed message");
    inline_signature_from_message(message)
}

fn intended_recipient_subpackets(signature: &pgp::packet::Signature) -> Vec<(Fingerprint, bool)> {
    signature
        .config()
        .expect("known signature config")
        .hashed_subpackets
        .iter()
        .filter_map(|subpacket| {
            let SubpacketData::IntendedRecipientFingerprint(fingerprint) = &subpacket.data else {
                return None;
            };
            Some((fingerprint.clone(), subpacket.is_critical))
        })
        .collect()
}

#[test]
fn signed_encryption_emits_deduplicated_versioned_intended_recipients_with_streaming_parity() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let v4_signer = generated_modern_material();
    let v6_signer = generated_v6_signing_key();
    let v6_signer_material =
        wire_key_material(encode_key_material(&v6_signer).expect("encode V6 signer"));
    let v4_recipient = generated_modern_material();
    let v6_recipient = generated_v6_encryption_material();
    let v4_recipient_secret =
        parse_secret_key(&v4_recipient.private_key_armored).expect("parse V4 recipient");
    let v6_recipient_secret =
        parse_secret_key(&v6_recipient.private_key_armored).expect("parse V6 recipient");
    let v4_fingerprint = v4_recipient_secret.primary_key.fingerprint();
    let v6_fingerprint = v6_recipient_secret.primary_key.fingerprint();
    let content = b"signed encryption intended-recipient coverage";
    let operation_time = u64::from(Timestamp::now().as_secs());

    for (recipient_label, public_keys, mut expected) in [
        (
            "one V4 recipient",
            vec![v4_recipient.public_key_armored.clone()],
            vec![v4_fingerprint.clone()],
        ),
        (
            "mixed recipients with duplicate V4 certificate",
            vec![
                v6_recipient.public_key_armored.clone(),
                v4_recipient.public_key_armored.clone(),
                v4_recipient.public_key_armored.clone(),
            ],
            vec![v4_fingerprint.clone(), v6_fingerprint.clone()],
        ),
    ] {
        expected.sort_by(|left, right| {
            left.version()
                .map(u8::from)
                .cmp(&right.version().map(u8::from))
                .then_with(|| left.as_bytes().cmp(right.as_bytes()))
        });
        for (signer_label, signer_private, signer_public, expected_version, critical) in [
            (
                "V4 signer",
                v4_signer.private_key_armored.as_slice(),
                v4_signer.public_key_armored.as_slice(),
                SignatureVersion::V4,
                false,
            ),
            (
                "V6 signer",
                v6_signer_material.private_key_armored.as_slice(),
                v6_signer_material.public_key_armored.as_slice(),
                SignatureVersion::V6,
                true,
            ),
        ] {
            for streaming in [false, true] {
                let encrypted = signed_encryption_output(
                    signer_private,
                    &public_keys,
                    &[],
                    content,
                    operation_time,
                    streaming,
                );
                let packets = RawPacketStream::parse(&encrypted, MAX_OPENPGP_PACKETS)
                    .expect("scan signed encrypted message");
                assert_eq!(
                    packets
                        .packets()
                        .iter()
                        .filter(|packet| {
                            packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey)
                        })
                        .count(),
                    expected.len(),
                    "deduplicated recipient PKESKs: {recipient_label}, {signer_label}, streaming={streaming}",
                );
                let signature =
                    decrypt_inline_signature(&encrypted, &v4_recipient.private_key_armored);
                assert_eq!(
                    signature.version(),
                    expected_version,
                    "{recipient_label}, {signer_label}, streaming={streaming}",
                );
                let subpackets = intended_recipient_subpackets(&signature);
                assert_eq!(
                    subpackets
                        .iter()
                        .map(|(fingerprint, _)| fingerprint)
                        .collect::<Vec<_>>(),
                    expected.iter().collect::<Vec<_>>(),
                    "{recipient_label}, {signer_label}, streaming={streaming}",
                );
                assert!(
                    subpackets
                        .iter()
                        .all(|(_, is_critical)| *is_critical == critical),
                    "{recipient_label}, {signer_label}, streaming={streaming}",
                );

                let decrypted = OpenPgpDecryptResult::decode(
                    decrypt_request(OpenPgpDecryptRequest {
                        content: encrypted.clone(),
                        private_keys: vec![v4_recipient.private_key_armored.clone()],
                        verification_public_keys: vec![signer_public.to_vec()],
                        reference_time_epoch_seconds: Some(operation_time + 1),
                        allow_signed_only: None,
                    })
                    .expect("decrypt and verify intended-recipient message")
                    .as_slice(),
                )
                .expect("decode intended-recipient decryption result");
                assert_eq!(decrypted.data, content);
                assert_eq!(
                    decrypted
                        .verification
                        .as_ref()
                        .expect("inline verification")
                        .status,
                    OpenPgpVerificationStatus::Valid as i32,
                    "{recipient_label}, {signer_label}, streaming={streaming}",
                );

                if expected.len() == 2 && !streaming {
                    let decrypted = OpenPgpDecryptResult::decode(
                        decrypt_request(OpenPgpDecryptRequest {
                            content: encrypted,
                            private_keys: vec![v6_recipient.private_key_armored.clone()],
                            verification_public_keys: vec![signer_public.to_vec()],
                            reference_time_epoch_seconds: Some(operation_time + 1),
                            allow_signed_only: None,
                        })
                        .expect("decrypt with V6 intended recipient")
                        .as_slice(),
                    )
                    .expect("decode V6 intended-recipient result");
                    assert_eq!(decrypted.data, content);
                    assert_eq!(
                        decrypted
                            .verification
                            .as_ref()
                            .expect("V6 recipient inline verification")
                            .status,
                        OpenPgpVerificationStatus::Valid as i32,
                    );
                }
            }
        }
    }

    assert!(matches!(v4_fingerprint, Fingerprint::V4(_)));
    assert!(matches!(v6_fingerprint, Fingerprint::V6(_)));
}

#[test]
fn revocation_candidates_do_not_become_encryption_or_intended_recipients() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let signer = generated_modern_material();
    let recipient = generated_modern_material();
    let candidate = generated_modern_material();
    let recipient_secret =
        parse_secret_key(&recipient.private_key_armored).expect("parse requested recipient");
    let expected_fingerprint = recipient_secret.primary_key.fingerprint();
    let content = b"revocation candidates are policy context only";
    let operation_time = TEST_TIME + 2;

    for streaming in [false, true] {
        let encrypted = signed_encryption_output(
            &signer.private_key_armored,
            std::slice::from_ref(&recipient.public_key_armored),
            std::slice::from_ref(&candidate.public_key_armored),
            content,
            operation_time,
            streaming,
        );
        let packets = RawPacketStream::parse(&encrypted, MAX_OPENPGP_PACKETS)
            .expect("scan candidate-context encryption");
        assert_eq!(
            packets
                .packets()
                .iter()
                .filter(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
                .count(),
            1,
            "streaming={streaming}",
        );
        let signature = decrypt_inline_signature(&encrypted, &recipient.private_key_armored);
        assert_eq!(
            intended_recipient_subpackets(&signature)
                .into_iter()
                .map(|(fingerprint, _)| fingerprint)
                .collect::<Vec<_>>(),
            vec![expected_fingerprint.clone()],
            "streaming={streaming}",
        );
        assert_eq!(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted,
                private_keys: vec![candidate.private_key_armored.clone()],
                verification_public_keys: vec![signer.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(operation_time + 1),
                allow_signed_only: None,
            }),
            Err(OpenPgpWriteError::MissingKey),
            "streaming={streaming}",
        );
    }
}

#[test]
fn generated_intended_recipient_rejects_reencryption_and_sign_only_omits_binding() {
    let signer_material = generated_modern_material();
    let original_recipient = generated_modern_material();
    let forwarded_recipient = generated_modern_material();
    let signer =
        parse_private_certificate(&signer_material.private_key_armored).expect("parse signer");
    let signing_packet =
        select_signing_packet(&signer, "", TEST_TIME + 2, &[]).expect("select signer");
    let original_certificates = parse_public_key_documents(
        std::slice::from_ref(&original_recipient.public_key_armored),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse original recipient");
    let (original_components, intended_recipients, _) = select_recipients(
        &original_certificates,
        &[],
        TEST_TIME + 2,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select original recipient");
    let forwarded_certificates = parse_public_key_documents(
        std::slice::from_ref(&forwarded_recipient.public_key_armored),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse forwarded recipient");
    let (forwarded_components, _, _) = select_recipients(
        &forwarded_certificates,
        &[],
        TEST_TIME + 2,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select forwarded recipient");
    let content = b"do not forward this signed statement";
    let composed = build_composed_message(
        content,
        b"recipient-bound.bin",
        Timestamp::from_secs((TEST_TIME + 1) as u32),
        Some(Timestamp::from_secs((TEST_TIME + 1) as u32)),
        Some(signing_packet),
        &intended_recipients,
        None,
    )
    .expect("compose recipient-bound message");

    for (label, recipients, private_key, expected_status) in [
        (
            "original encryption context",
            original_components.as_slice(),
            original_recipient.private_key_armored.as_slice(),
            OpenPgpVerificationStatus::Valid,
        ),
        (
            "forwarded encryption context",
            forwarded_components.as_slice(),
            forwarded_recipient.private_key_armored.as_slice(),
            OpenPgpVerificationStatus::Invalid,
        ),
    ] {
        let encrypted = encrypt_composed_message(
            &composed,
            recipients,
            ProtectionMode::SeipdV1Mdc,
            SymmetricKeyAlgorithm::AES256,
        )
        .expect("encrypt composed recipient-bound message");
        let result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted,
                private_keys: vec![private_key.to_vec()],
                verification_public_keys: vec![signer_material.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
                allow_signed_only: None,
            })
            .unwrap_or_else(|error| panic!("decrypt {label}: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label}: {error}"));
        assert_eq!(result.data, content, "{label}");
        assert_eq!(
            result
                .verification
                .as_ref()
                .expect("inline verification")
                .status,
            expected_status as i32,
            "{label}",
        );
    }

    let sign_only = build_composed_message(
        content,
        b"sign-only.bin",
        Timestamp::from_secs((TEST_TIME + 1) as u32),
        Some(Timestamp::from_secs((TEST_TIME + 1) as u32)),
        Some(signing_packet),
        &[],
        None,
    )
    .expect("compose sign-only message");
    let signature = inline_signature_from_message(
        Message::from_bytes(sign_only.as_slice()).expect("parse sign-only message"),
    );
    assert!(intended_recipient_subpackets(&signature).is_empty());
}

#[test]
fn cleartext_hash_header_is_limited_to_v4_sha2_legacy_compatibility() {
    let bare_preamble = b"-----BEGIN PGP SIGNED MESSAGE-----\n\n";
    for hash_algorithm in [
        HashAlgorithm::Sha224,
        HashAlgorithm::Sha256,
        HashAlgorithm::Sha384,
        HashAlgorithm::Sha512,
    ] {
        assert_eq!(
            cleartext_signature_header(SignatureVersion::V4, hash_algorithm),
            format!("-----BEGIN PGP SIGNED MESSAGE-----\nHash: {hash_algorithm}\n\n").into_bytes(),
        );
        assert_eq!(
            cleartext_signature_header(SignatureVersion::V6, hash_algorithm),
            bare_preamble,
        );
    }

    for hash_algorithm in [
        HashAlgorithm::None,
        HashAlgorithm::Md5,
        HashAlgorithm::Sha1,
        HashAlgorithm::Ripemd160,
        HashAlgorithm::Sha3_256,
        HashAlgorithm::Sha3_512,
        HashAlgorithm::Private10,
        HashAlgorithm::Other(111),
    ] {
        for signature_version in [SignatureVersion::V4, SignatureVersion::V6] {
            assert_eq!(
                cleartext_signature_header(signature_version, hash_algorithm),
                bare_preamble,
                "version={signature_version:?}, hash={hash_algorithm:?}",
            );
        }
    }
}

#[test]
fn imported_ed448_data_signatures_use_a_512_bit_hash() {
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed448)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Ed448 <ed448@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build Ed448 certificate parameters")
        .generate(AwsLcRng)
        .expect("generate Ed448 certificate");
    let signer = &secret.primary_key;
    let mut config = data_signature_config(signer, SignatureType::Binary)
        .expect("select Ed448 data-signature hash");
    assert_eq!(config.hash_alg, HashAlgorithm::Sha3_512);
    assert_eq!(
        cleartext_signature_header(config.version(), config.hash_alg),
        b"-----BEGIN PGP SIGNED MESSAGE-----\n\n",
    );
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(signer, Timestamp::from_secs(TEST_TIME as u32 + 1))
            .expect("prepare signing subpackets")
    else {
        panic!("expected user-defined signing subpackets");
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;

    let signature = config
        .sign(signer, &Password::empty(), Cursor::new(b"Ed448 payload"))
        .expect("sign with selected Ed448 hash");
    assert_eq!(signature.hash_alg(), Some(HashAlgorithm::Sha3_512));
    signature
        .verify(signer.public_key(), &b"Ed448 payload"[..])
        .expect("verify Ed448 data signature");
}

#[test]
fn v6_sha3_cleartext_signatures_omit_hash_header_one_shot_and_streaming() {
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed448)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Ed448 Cleartext <ed448-cleartext@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build Ed448 cleartext certificate parameters")
        .generate(AwsLcRng)
        .expect("generate Ed448 cleartext certificate");
    let material = wire_key_material(
        encode_key_material(&secret).expect("encode Ed448 cleartext signing material"),
    );
    let content = b"V6 SHA3 cleartext signature";
    let expected_preamble = b"-----BEGIN PGP SIGNED MESSAGE-----\n\n";

    let one_shot = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: None,
        reference_time_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
    })
    .expect("create one-shot Ed448 cleartext signature");
    assert_cleartext_signature_wire(
        &one_shot,
        expected_preamble,
        SignatureVersion::V6,
        HashAlgorithm::Sha3_512,
    );

    let mut session = open_clear_sign_session(OpenPgpClearSignStreamOpenRequest {
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        signature_time_epoch_seconds: None,
        reference_time_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open streaming Ed448 cleartext signer");
    let mut streaming = session
        .update(&content[..7])
        .expect("update first Ed448 cleartext chunk");
    streaming.extend_from_slice(
        &session
            .update(&content[7..])
            .expect("update second Ed448 cleartext chunk"),
    );
    streaming.extend_from_slice(
        &session
            .finish()
            .expect("finish streaming Ed448 cleartext signature"),
    );
    assert_cleartext_signature_wire(
        &streaming,
        expected_preamble,
        SignatureVersion::V6,
        HashAlgorithm::Sha3_512,
    );

    for (label, document) in [("one-shot", one_shot), ("streaming", streaming)] {
        let verification = OpenPgpVerification::decode(
            crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: document,
                signature: Vec::new(),
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: None,
            })
            .unwrap_or_else(|error| panic!("verify {label} Ed448 clear signature: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label} Ed448 clear verification: {error}"));
        assert_eq!(
            verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} Ed448 clear signature",
        );
    }
}

fn verify_detached_with_keys(
    content: &[u8],
    signature: &[u8],
    public_keys: Vec<Vec<u8>>,
) -> OpenPgpVerification {
    OpenPgpVerification::decode(
        crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: content.to_vec(),
            signature: signature.to_vec(),
            public_keys,
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
        })
        .expect("verify detached signature")
        .as_slice(),
    )
    .expect("decode detached verification")
}

fn assert_rejected_signer(
    verification: &OpenPgpVerification,
    expected_fingerprint: &str,
    context: &str,
) {
    assert_eq!(
        verification.status,
        OpenPgpVerificationStatus::Invalid as i32,
        "{context}",
    );
    assert_eq!(
        verification.fingerprint.as_deref(),
        Some(expected_fingerprint),
        "{context}",
    );
    assert!(verification.user_ids.is_empty());
    assert!(verification.warnings.is_empty());
    assert!(verification.primary_fingerprint.is_none());
    assert!(verification.primary_user_id.is_none());
}

#[test]
fn certificate_policy_uses_the_authenticated_primary_user_id_not_packet_order() {
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Primary <primary@example.test>".to_owned())
        .user_id("Secondary <secondary@example.test>")
        .passphrase(None)
        .build()
        .expect("build certificate parameters")
        .generate(AwsLcRng)
        .expect("generate certificate");
    let mut public = secret.to_public_key();
    let reference_time = public
        .details
        .users
        .iter()
        .flat_map(|user| user.signatures.iter())
        .filter_map(crate::openpgp::policy::signature_creation_time)
        .map(u64::from)
        .max()
        .expect("generated user IDs have self-signatures");
    public.details.users.swap(0, 1);
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        reference_time,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect reordered certificate");

    assert_eq!(
        policy.verified_user_ids_for_test(),
        [
            "Secondary <secondary@example.test>",
            "Primary <primary@example.test>",
        ],
    );
    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some("Primary <primary@example.test>"),
    );
}

fn import_secret(secret: &SignedSecretKey) -> OpenPgpKeyImportResult {
    let key_data = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor secret key");
    OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data,
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("import request")
        .as_slice(),
    )
    .expect("decode import result")
}

fn imported_material(result: OpenPgpKeyImportResult) -> OpenPgpKeyMaterial {
    match result.result {
        Some(open_pgp_key_import_result::Result::Success(success)) => {
            success.key_material.expect("imported key material")
        }
        result => panic!("expected successful import, got {result:?}"),
    }
}

fn import_key_data(key_data: Vec<u8>) -> OpenPgpKeyImportResult {
    OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data,
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("import request")
        .as_slice(),
    )
    .expect("decode import result")
}

fn secret_certificate_with_gnu_dummy_primary(
    source: &[u8],
    s2k_usage: u8,
    trailing: &[u8],
) -> Vec<u8> {
    assert!(matches!(s2k_usage, 254 | 255));
    let stream =
        RawPacketStream::parse(source, MAX_OPENPGP_PACKETS).expect("parse secret certificate");
    let primary = stream
        .packets()
        .first()
        .filter(|packet| packet.tag() == SECRET_KEY_TAG)
        .expect("secret certificate starts with a secret primary");
    let public_len =
        import_secret_packet_public_len(&stream, primary).expect("parse primary public fields");
    let primary_body = stream.body(primary);
    let mut dummy_body = Vec::with_capacity(public_len + 8 + trailing.len());
    dummy_body.extend_from_slice(&primary_body[..public_len]);
    // GnuPG private S2K 101, mode 1: cipher 0, hash 0, "GNU", and no
    // secret material after the extension number.
    dummy_body.extend_from_slice(&[s2k_usage, 0, 101, 0, b'G', b'N', b'U', 1]);
    dummy_body.extend_from_slice(trailing);

    let mut certificate = Vec::new();
    write_fixed_packet(SECRET_KEY_TAG, &dummy_body, &mut certificate)
        .expect("serialize GNU dummy primary");
    for packet in stream.packets().iter().skip(1) {
        certificate.extend_from_slice(stream.raw(packet));
    }
    certificate
}

fn secret_certificate_with_public_primary(source: &[u8], public: &[u8]) -> Vec<u8> {
    let secret =
        RawPacketStream::parse(source, MAX_OPENPGP_PACKETS).expect("parse secret certificate");
    let public =
        RawPacketStream::parse(public, MAX_OPENPGP_PACKETS).expect("parse public certificate");
    let public_primary = public
        .packets()
        .first()
        .filter(|packet| packet.tag() == PUBLIC_KEY_TAG)
        .expect("public certificate starts with a public primary");
    assert_eq!(
        secret.packets().first().map(|packet| packet.tag()),
        Some(SECRET_KEY_TAG),
    );
    let mut certificate = Vec::new();
    certificate.extend_from_slice(public.raw(public_primary));
    for packet in secret.packets().iter().skip(1) {
        certificate.extend_from_slice(secret.raw(packet));
    }
    certificate
}

fn secret_certificate_with_subkeys_from(primary: &[u8], donor: &[u8]) -> Vec<u8> {
    let primary_stream = RawPacketStream::parse(primary, MAX_OPENPGP_PACKETS)
        .expect("parse primary secret certificate");
    let donor_stream =
        RawPacketStream::parse(donor, MAX_OPENPGP_PACKETS).expect("parse donor secret certificate");
    let primary_subkey = primary_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == SECRET_SUBKEY_TAG)
        .expect("primary certificate has a secret subkey");
    let donor_subkey = donor_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == SECRET_SUBKEY_TAG)
        .expect("donor certificate has a secret subkey");
    let mut certificate = Vec::new();
    for packet in primary_stream.packets().iter().take(primary_subkey) {
        certificate.extend_from_slice(primary_stream.raw(packet));
    }
    for packet in donor_stream.packets().iter().skip(donor_subkey) {
        certificate.extend_from_slice(donor_stream.raw(packet));
    }
    certificate
}

fn resolved_metadata(
    material: &OpenPgpKeyMaterial,
) -> Option<crate::openpgp::adapter::wire::OpenPgpMetadataResolutionV2> {
    OpenPgpMetadataResolveResult::decode(
        crate::openpgp::adapter::resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(material.private_key_armored.clone()),
            public_key_data: Some(material.public_key_armored.clone()),
            normalized_fingerprint: material.fingerprint.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("resolve imported metadata")
        .as_slice(),
    )
    .expect("decode imported metadata")
    .resolution
}

fn signature_config(
    signature_type: SignatureType,
    signer: &dyn SigningKey,
    created_at: u64,
) -> SignatureConfig {
    let created_at = Timestamp::from_secs(created_at as u32);
    let mut config = SignatureConfig::v4(signature_type, signer.algorithm(), HashAlgorithm::Sha256);
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .expect("signature creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .expect("issuer fingerprint subpacket"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .expect("issuer key ID subpacket"),
    ];
    config
}

fn subkey_binding_with_signature_expiration(
    secret: &SignedSecretKey,
    subkey_index: usize,
    created_at: u64,
    expiration_seconds: u32,
    keep_embedded_signature: bool,
) -> pgp::packet::Signature {
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[subkey_index].key.public_key().clone();
    let template = secret.secret_subkeys[subkey_index]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .expect("subkey binding template");
    let mut config = template.config().cloned().expect("binding config");
    config
        .hashed_subpackets
        .retain(|subpacket| match subpacket.data {
            SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_) => {
                false
            }
            SubpacketData::EmbeddedSignature(_) => keep_embedded_signature,
            _ => true,
        });
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            created_at as u32,
        )))
        .expect("signature creation subpacket"),
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
            expiration_seconds,
        )))
        .expect("signature expiration subpacket"),
    );
    config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign replacement subkey binding")
}

#[test]
fn gnupg_ocb_associated_data_uses_new_packet_tag_octet() {
    let associated_data = gnupg_ocb_associated_data(0x0102_0304_0506_0708);
    assert_eq!(associated_data[0], 0xd4);
    assert_eq!(
        &associated_data[5..],
        &0x0102_0304_0506_0708_u64.to_be_bytes()
    );
}

#[test]
fn generated_certificate_imports_and_signs() {
    let material = generated_modern_material();
    let imported = OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data: material.private_key_armored.clone(),
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("import request")
        .as_slice(),
    )
    .expect("decode import result");
    assert!(matches!(
        imported.result,
        Some(open_pgp_key_import_result::Result::Success(_))
    ));

    let content = b"OpenPGP detached signature";
    let signature = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("sign request");
    let (signature, _) =
        DetachedSignature::from_reader_single(Cursor::new(signature)).expect("parse signature");
    let (certificate, _) =
        SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
            .expect("parse public certificate");
    let signing_key = certificate
        .public_subkeys
        .iter()
        .find(|subkey| subkey.key.algorithm().can_sign())
        .expect("signing subkey");
    signature
        .verify(&signing_key.key, content)
        .expect("verify signature");
}

#[test]
fn imported_key_armor_omits_v6_checksums_and_retains_v4_checksums() {
    let v6 = generated_v6_signing_key();
    let v6_input = v6
        .to_armored_bytes(ArmorOptions {
            include_checksum: true,
            ..ArmorOptions::default()
        })
        .expect("armor v6 import input with a legacy checksum");
    let v6_material = OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data: v6_input,
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("import v6 key")
        .as_slice(),
    )
    .map(imported_material)
    .expect("decode v6 import result");

    assert!(!armor_has_checksum(&v6_material.private_key_armored));
    assert!(!armor_has_checksum(&v6_material.public_key_armored));
    SignedSecretKey::from_reader_single(Cursor::new(&v6_material.private_key_armored))
        .expect("reparse imported v6 secret key");
    SignedPublicKey::from_reader_single(Cursor::new(&v6_material.public_key_armored))
        .expect("reparse imported v6 public key");

    let v4 = generated_modern_material();
    let v4_material = OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data: v4.private_key_armored.clone(),
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("import v4 key")
        .as_slice(),
    )
    .map(imported_material)
    .expect("decode v4 import result");

    assert!(armor_has_checksum(&v4_material.private_key_armored));
    assert!(armor_has_checksum(&v4_material.public_key_armored));
    SignedSecretKey::from_reader_single(Cursor::new(&v4_material.private_key_armored))
        .expect("reparse imported v4 secret key");
    SignedPublicKey::from_reader_single(Cursor::new(&v4_material.public_key_armored))
        .expect("reparse imported v4 public key");
}

#[test]
fn import_rejects_mixed_primary_and_subkey_key_packet_versions() {
    let v4 = generated_modern_material();
    let v6 = generated_v6_encryption_material();

    for (material, case) in [(&v4, "uniform V4"), (&v6, "uniform V6")] {
        assert!(
            matches!(
                import_key_data(material.private_key_armored.clone()).result,
                Some(open_pgp_key_import_result::Result::Success(_))
            ),
            "accept {case} secret certificate",
        );
    }

    for (primary, donor, case) in [
        (&v4, &v6, "V4 primary with V6 secret subkey"),
        (&v6, &v4, "V6 primary with V4 secret subkey"),
    ] {
        let mixed = secret_certificate_with_subkeys_from(
            &primary.private_key_armored,
            &donor.private_key_armored,
        );
        assert!(
            matches!(
                import_key_data(mixed).result,
                Some(open_pgp_key_import_result::Result::Error(error))
                    if error.reason == OpenPgpKeyImportErrorReason::MalformedKey as i32
            ),
            "reject {case}",
        );
    }
}

#[test]
fn one_shot_and_streaming_signature_armor_follow_signature_version() {
    let v6 = generated_v6_signing_key();
    let v6_material =
        wire_key_material(encode_key_material(&v6).expect("encode v6 signing material"));
    let cases = [
        (
            generated_modern_material(),
            true,
            SignatureVersion::V4,
            b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n".as_slice(),
            "v4",
        ),
        (
            v6_material,
            false,
            SignatureVersion::V6,
            b"-----BEGIN PGP SIGNED MESSAGE-----\n\n".as_slice(),
            "v6",
        ),
    ];
    let content = b"version-driven detached and cleartext armor";

    for (material, expected_checksum, expected_version, expected_preamble, label) in cases {
        let operation_time = expected_checksum.then_some(TEST_TIME + 1);
        let detached = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: true,
            signature_time_epoch_seconds: operation_time,
            reference_time_epoch_seconds: operation_time,
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("create one-shot {label} detached signature: {error}"));
        assert_signature_armor_reparses(&detached, expected_checksum);

        let mut detached_stream =
            open_detached_sign_session(OpenPgpDetachedSignStreamOpenRequest {
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: String::new(),
                armored: true,
                signature_time_epoch_seconds: operation_time,
                reference_time_epoch_seconds: operation_time,
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("open streaming {label} detached signer: {error}"));
        detached_stream
            .update(&content[..11])
            .unwrap_or_else(|error| panic!("update streaming {label} detached signer: {error}"));
        detached_stream
            .update(&content[11..])
            .unwrap_or_else(|error| panic!("update streaming {label} detached signer: {error}"));
        let detached_stream = detached_stream
            .finish()
            .unwrap_or_else(|error| panic!("finish streaming {label} detached signer: {error}"));
        assert_signature_armor_reparses(&detached_stream, expected_checksum);

        let clear = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::ClearText as i32,
            content: content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: true,
            signature_time_epoch_seconds: operation_time,
            reference_time_epoch_seconds: operation_time,
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("create one-shot {label} clear signature: {error}"));
        assert_signature_armor_reparses(&clear, expected_checksum);
        assert_cleartext_signature_wire(
            &clear,
            expected_preamble,
            expected_version,
            HashAlgorithm::Sha256,
        );
        let clear_verification = OpenPgpVerification::decode(
            crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: clear,
                signature: Vec::new(),
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: operation_time.map(|_| TEST_TIME + 2),
            })
            .unwrap_or_else(|error| panic!("verify one-shot {label} clear signature: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode one-shot {label} clear verification: {error}"));
        assert_eq!(
            clear_verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "one-shot {label} clear signature",
        );

        let mut clear_stream = open_clear_sign_session(OpenPgpClearSignStreamOpenRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            signature_time_epoch_seconds: operation_time,
            reference_time_epoch_seconds: operation_time,
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("open streaming {label} clear signer: {error}"));
        let mut clear_stream_output = clear_stream
            .update(&content[..11])
            .unwrap_or_else(|error| panic!("update streaming {label} clear signer: {error}"));
        clear_stream_output.extend_from_slice(
            &clear_stream
                .update(&content[11..])
                .unwrap_or_else(|error| panic!("update streaming {label} clear signer: {error}")),
        );
        clear_stream_output.extend_from_slice(
            &clear_stream
                .finish()
                .unwrap_or_else(|error| panic!("finish streaming {label} clear signer: {error}")),
        );
        assert_signature_armor_reparses(&clear_stream_output, expected_checksum);
        assert_cleartext_signature_wire(
            &clear_stream_output,
            expected_preamble,
            expected_version,
            HashAlgorithm::Sha256,
        );
        let clear_stream_verification = OpenPgpVerification::decode(
            crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: clear_stream_output,
                signature: Vec::new(),
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: operation_time.map(|_| TEST_TIME + 2),
            })
            .unwrap_or_else(|error| panic!("verify streaming {label} clear signature: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode streaming {label} clear verification: {error}"));
        assert_eq!(
            clear_stream_verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "streaming {label} clear signature",
        );
    }
}

#[cfg(unix)]
#[test]
fn gnupg_verifies_v4_cleartext_signature_with_legacy_hash_header() {
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        process::Command,
        time::{SystemTime, UNIX_EPOCH},
    };

    if !Command::new("gpg")
        .arg("--version")
        .output()
        .is_ok_and(|output| output.status.success())
    {
        return;
    }

    struct GnuPgHome(std::path::PathBuf);

    impl Drop for GnuPgHome {
        fn drop(&mut self) {
            let _ = Command::new("gpgconf")
                .arg("--homedir")
                .arg(&self.0)
                .args(["--kill", "all"])
                .status();
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    let material = generated_modern_material();
    let signed = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: b"GnuPG cleartext Hash header interoperability".to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("create GnuPG-compatible V4 cleartext signature");
    let legacy_preamble = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n";
    assert_cleartext_signature_wire(
        &signed,
        legacy_preamble,
        SignatureVersion::V4,
        HashAlgorithm::Sha256,
    );

    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock after Unix epoch")
        .as_nanos();
    let home = GnuPgHome(
        std::path::Path::new("/tmp")
            .join(format!("kg-gpg-clearsign-{}-{nonce:x}", std::process::id(),)),
    );
    fs::create_dir(&home.0).expect("create isolated GnuPG home");
    fs::set_permissions(&home.0, fs::Permissions::from_mode(0o700))
        .expect("restrict isolated GnuPG home");
    let public_key_path = home.0.join("public.asc");
    fs::write(&public_key_path, &material.public_key_armored)
        .expect("write GnuPG verification certificate");
    let imported = Command::new("gpg")
        .args(["--batch", "--yes", "--no-tty", "--homedir"])
        .arg(&home.0)
        .arg("--import")
        .arg(&public_key_path)
        .output()
        .expect("run GnuPG verification-key import");
    assert!(
        imported.status.success(),
        "GnuPG failed to import V4 verification certificate: {}",
        String::from_utf8_lossy(&imported.stderr),
    );

    let document_path = home.0.join("signed.asc");
    fs::write(&document_path, signed).expect("write GnuPG cleartext signature");
    let verified = Command::new("gpg")
        .args(["--batch", "--no-tty", "--homedir"])
        .arg(&home.0)
        .arg("--verify")
        .arg(&document_path)
        .output()
        .expect("run GnuPG cleartext verification");
    assert!(
        verified.status.success(),
        "GnuPG rejected V4 cleartext signature with legacy Hash header: {}",
        String::from_utf8_lossy(&verified.stderr),
    );
}

#[test]
fn import_preserves_third_party_certification_and_direct_signature() {
    let material = generated_modern_material();
    let certifier_material = generated_modern_material();
    let (mut target, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
        certifier_material.private_key_armored.as_slice(),
    ))
    .expect("parse certifier secret key");
    let signer = SigningKeyRef(&certifier.primary_key);
    let password = Password::empty();

    let certification = signature_config(SignatureType::CertPositive, &signer, TEST_TIME + 1)
        .sign_certification_third_party(
            &signer,
            &password,
            target.primary_key.public_key(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("create third-party certification");
    certification
        .verify_third_party_certification(
            target.primary_key.public_key(),
            certifier.primary_key.public_key(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("verify third-party certification");
    target.details.users[0]
        .signatures
        .push(certification.clone());

    let direct = signature_config(SignatureType::Key, &signer, TEST_TIME + 2)
        .sign_key(&signer, &password, target.primary_key.public_key())
        .expect("create third-party Direct Key signature");
    direct
        .verify_key_third_party(
            target.primary_key.public_key(),
            certifier.primary_key.public_key(),
        )
        .expect("verify third-party Direct Key signature");
    target.details.direct_signatures.push(direct.clone());

    assert!(target.verify_bindings().is_err());
    let imported = imported_material(import_secret(&target));
    let (imported_secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.as_slice()))
            .expect("parse imported secret key");
    let (imported_public, _) =
        SignedPublicKey::from_reader_single(Cursor::new(imported.public_key_armored.as_slice()))
            .expect("parse imported public key");

    assert!(
        imported_secret.details.users[0]
            .signatures
            .contains(&certification)
    );
    assert!(
        imported_public.details.users[0]
            .signatures
            .contains(&certification)
    );
    assert!(imported_secret.details.direct_signatures.contains(&direct));
    assert!(imported_public.details.direct_signatures.contains(&direct));
}

#[test]
fn secret_import_filters_local_public_export_evidence() {
    let material = generated_modern_material();
    let certifier_material = generated_modern_material();
    let revoker_material = generated_modern_material();
    let (mut target, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
        certifier_material.private_key_armored.as_slice(),
    ))
    .expect("parse certifier secret key");
    let (revoker, _) = SignedSecretKey::from_reader_single(Cursor::new(
        revoker_material.private_key_armored.as_slice(),
    ))
    .expect("parse revoker secret key");
    let password = Password::empty();

    let (local_certification, ordinary_certification) = {
        let certifier_signer = SigningKeyRef(&certifier.primary_key);
        let mut local_config = signature_config(
            SignatureType::CertPositive,
            &certifier_signer,
            TEST_TIME + 1,
        );
        local_config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::ExportableCertification(false))
                .expect("exportable certification subpacket"),
        );
        let local = local_config
            .sign_certification_third_party(
                &certifier_signer,
                &password,
                target.primary_key.public_key(),
                Tag::UserId,
                &target.details.users[0].id,
            )
            .expect("create local certification");
        let ordinary = signature_config(
            SignatureType::CertPositive,
            &certifier_signer,
            TEST_TIME + 2,
        )
        .sign_certification_third_party(
            &certifier_signer,
            &password,
            target.primary_key.public_key(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("create ordinary certification");
        (local, ordinary)
    };
    let (sensitive_declaration, ordinary_declaration) = {
        let target_signer = SigningKeyRef(&target.primary_key);
        let declaration = |class, created_at| {
            let mut config = signature_config(SignatureType::Key, &target_signer, created_at);
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
                    class,
                    revoker.primary_key.algorithm(),
                    revoker.primary_key.fingerprint().as_bytes(),
                )))
                .expect("revocation key subpacket"),
            );
            config
                .sign_key(&target_signer, &password, target.primary_key.public_key())
                .expect("create designated-revoker declaration")
        };
        (
            declaration(RevocationKeyClass::Sensitive, TEST_TIME + 3),
            declaration(RevocationKeyClass::Default, TEST_TIME + 4),
        )
    };
    target.details.users[0]
        .signatures
        .extend([local_certification.clone(), ordinary_certification.clone()]);
    target
        .details
        .direct_signatures
        .extend([sensitive_declaration.clone(), ordinary_declaration.clone()]);

    let serialized = target.to_bytes().expect("serialize augmented secret key");
    let stream = RawPacketStream::parse(&serialized, MAX_OPENPGP_PACKETS)
        .expect("scan augmented secret key");
    let range = stream
        .first_secret_certificate()
        .expect("find augmented secret key");
    let local_packets = [
        [0xca, 0x03, b'P', b'G', b'P'].as_slice(),
        [0xcc, 0x01, 0x01].as_slice(),
        [0xe8, 0x03, 0x01, 0x02, 0x03].as_slice(),
    ];
    let mut input = Vec::new();
    for (position, packet) in stream.packets()[range].iter().enumerate() {
        input.extend_from_slice(stream.raw(packet));
        if position == 0 {
            for packet in local_packets {
                input.extend_from_slice(packet);
            }
        }
    }
    let imported = imported_material(import_key_data(
        armor_key_packets(&input, BlockType::PrivateKey)
            .expect("armor secret key with local packets"),
    ));
    let imported_private =
        RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan imported private key");
    let imported_public = RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
        .expect("scan imported public key");
    let contains_signature = |stream: &RawPacketStream, signature: &pgp::packet::Signature| {
        let body = signature.to_bytes().expect("serialize signature body");
        stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == SIGNATURE_TAG && stream.body(packet).as_slice() == body)
    };

    assert_eq!(imported_private.bytes(), input);
    assert!(contains_signature(&imported_private, &local_certification));
    assert!(contains_signature(
        &imported_private,
        &sensitive_declaration
    ));
    assert!(
        imported_private
            .packets()
            .iter()
            .any(|packet| packet.tag() == 10)
    );
    assert!(
        imported_private
            .packets()
            .iter()
            .any(|packet| packet.tag() == 12)
    );

    assert!(!contains_signature(&imported_public, &local_certification));
    assert!(!contains_signature(
        &imported_public,
        &sensitive_declaration
    ));
    assert!(contains_signature(
        &imported_public,
        &ordinary_certification
    ));
    assert!(contains_signature(&imported_public, &ordinary_declaration));
    assert!(
        imported_public
            .packets()
            .iter()
            .all(|packet| !matches!(packet.tag(), 10 | 12))
    );
    assert!(
        imported_public
            .packets()
            .iter()
            .any(|packet| packet.tag() == 40
                && imported_public.raw(packet) == [0xe8, 0x03, 0x01, 0x02, 0x03])
    );
    SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.as_slice()))
        .expect("imported private key reparses");
    SignedPublicKey::from_reader_single(Cursor::new(imported.public_key_armored.as_slice()))
        .expect("imported public key reparses");
}

#[test]
fn import_uses_valid_binding_when_newer_foreign_binding_is_present() {
    let material = generated_modern_material();
    let certifier_material = generated_modern_material();
    let (mut target, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
        certifier_material.private_key_armored.as_slice(),
    ))
    .expect("parse certifier secret key");
    let signer = SigningKeyRef(&certifier.primary_key);
    let subkey = target.secret_subkeys[0].key.public_key().clone();
    let foreign_binding = signature_config(SignatureType::SubkeyBinding, &signer, TEST_TIME + 10)
        .sign_subkey_binding(
            &signer,
            target.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("create foreign subkey binding");
    assert!(
        foreign_binding
            .verify_subkey_binding(target.primary_key.public_key(), &subkey)
            .is_err()
    );
    target.secret_subkeys[0]
        .signatures
        .push(foreign_binding.clone());

    assert!(target.verify_bindings().is_err());
    let imported = imported_material(import_secret(&target));
    let (imported_secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.as_slice()))
            .expect("parse imported secret key");
    assert!(
        imported_secret.secret_subkeys[0]
            .signatures
            .contains(&foreign_binding)
    );
}

#[test]
fn import_preserves_unresolved_designated_revoker_signature() {
    let material = generated_modern_material();
    let revoker_material = generated_modern_material();
    let (mut target, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (revoker, _) = SignedSecretKey::from_reader_single(Cursor::new(
        revoker_material.private_key_armored.as_slice(),
    ))
    .expect("parse revoker secret key");
    let target_signer = SigningKeyRef(&target.primary_key);
    let revoker_signer = SigningKeyRef(&revoker.primary_key);
    let password = Password::empty();

    let mut declaration = signature_config(SignatureType::Key, &target_signer, TEST_TIME + 1);
    declaration.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
            RevocationKeyClass::Default,
            revoker.primary_key.algorithm(),
            revoker.primary_key.fingerprint().as_bytes(),
        )))
        .expect("revocation key subpacket"),
    );
    let declaration = declaration
        .sign_key(&target_signer, &password, target.primary_key.public_key())
        .expect("create revoker declaration");
    declaration
        .verify_key(target.primary_key.public_key())
        .expect("verify revoker declaration");
    target.details.direct_signatures.push(declaration.clone());

    let revocation = signature_config(SignatureType::KeyRevocation, &revoker_signer, TEST_TIME + 2)
        .sign_key(&revoker_signer, &password, target.primary_key.public_key())
        .expect("create designated revocation");
    revocation
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("verify designated revocation");
    target
        .details
        .revocation_signatures
        .push(revocation.clone());

    assert!(target.verify_bindings().is_err());
    let imported = imported_material(import_secret(&target));
    let (imported_secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.as_slice()))
            .expect("parse imported secret key");
    assert!(
        imported_secret
            .details
            .direct_signatures
            .contains(&declaration)
    );
    assert!(
        imported_secret
            .details
            .revocation_signatures
            .contains(&revocation)
    );
}

#[test]
fn designated_revocation_blocks_signing_before_and_after_verification() {
    let material = generated_modern_material();
    let revoker_material = generated_modern_material();
    let (mut target, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (revoker, _) = SignedSecretKey::from_reader_single(Cursor::new(
        revoker_material.private_key_armored.as_slice(),
    ))
    .expect("parse revoker secret key");
    let target_signer = SigningKeyRef(&target.primary_key);
    let revoker_signer = SigningKeyRef(&revoker.primary_key);
    let password = Password::empty();

    let mut declaration = signature_config(SignatureType::Key, &target_signer, TEST_TIME + 1);
    declaration.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
            RevocationKeyClass::Default,
            revoker.primary_key.algorithm(),
            revoker.primary_key.fingerprint().as_bytes(),
        )))
        .expect("revocation key subpacket"),
    );
    target.details.direct_signatures.push(
        declaration
            .sign_key(&target_signer, &password, target.primary_key.public_key())
            .expect("create revoker declaration"),
    );
    target.details.revocation_signatures.push(
        signature_config(SignatureType::KeyRevocation, &revoker_signer, TEST_TIME + 2)
            .sign_key(&revoker_signer, &password, target.primary_key.public_key())
            .expect("create designated revocation"),
    );

    let private_key = target
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor target secret key");
    let revoker_public = revoker
        .to_public_key()
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor revoker public key");
    let request = |candidate_revocation_keys| OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: b"designated revoker policy".to_vec(),
        private_key: private_key.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: true,
        signature_time_epoch_seconds: Some(TEST_TIME + 3),
        reference_time_epoch_seconds: Some(TEST_TIME + 3),
        candidate_revocation_keys,
    };

    assert_eq!(
        sign_request(request(Vec::new())),
        Err(OpenPgpWriteError::MissingKey),
    );
    assert_eq!(
        sign_request(request(vec![revoker_public.clone()])),
        Err(OpenPgpWriteError::MissingKey),
    );

    let mut over_limit = vec![b"unrelated malformed vault item".to_vec(); MAX_OPENPGP_KEYS];
    over_limit.push(revoker_public);
    assert_eq!(
        sign_request(request(over_limit)),
        Err(OpenPgpWriteError::ResourceLimit),
    );
}

#[test]
fn import_preserves_but_quarantines_foreign_only_identity_and_unbound_subkey() {
    let material = generated_modern_material();
    let certifier_material = generated_modern_material();
    let (mut foreign_only, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
        certifier_material.private_key_armored.as_slice(),
    ))
    .expect("parse certifier secret key");
    let signer = SigningKeyRef(&certifier.primary_key);
    let certification = signature_config(SignatureType::CertPositive, &signer, TEST_TIME + 1)
        .sign_certification_third_party(
            &signer,
            &Password::empty(),
            foreign_only.primary_key.public_key(),
            Tag::UserId,
            &foreign_only.details.users[0].id,
        )
        .expect("create foreign-only certification");
    foreign_only.details.users[0].signatures = vec![certification];
    let foreign_input = foreign_only
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor foreign-only key");
    let foreign_imported = imported_material(import_secret(&foreign_only));
    let foreign_input_packets = RawPacketStream::parse(&foreign_input, MAX_OPENPGP_PACKETS)
        .expect("scan foreign-only input");
    let foreign_output_packets =
        RawPacketStream::parse(&foreign_imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan foreign-only output");
    assert_eq!(
        foreign_input_packets.bytes(),
        foreign_output_packets.bytes()
    );
    let (foreign_output, _) =
        SignedSecretKey::from_reader_single(Cursor::new(&foreign_imported.private_key_armored))
            .expect("parse foreign-only output");
    let mut budget = OpenPgpPolicyBudget::default();
    let foreign_public = foreign_output.to_public_key();
    let foreign_candidates = all_components(std::slice::from_ref(&foreign_public));
    let foreign_policy =
        validate_certificate(&foreign_public, &foreign_candidates, TEST_TIME, &mut budget)
            .expect("inspect foreign-only output");
    assert!(!foreign_policy.primary.authenticated);

    let (mut unbound, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key again");
    unbound.secret_subkeys[0].signatures = unbound.secret_subkeys[1].signatures.clone();
    let unbound_input = unbound
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor unbound key");
    let unbound_imported = imported_material(import_secret(&unbound));
    let unbound_input_packets =
        RawPacketStream::parse(&unbound_input, MAX_OPENPGP_PACKETS).expect("scan unbound input");
    let unbound_output_packets =
        RawPacketStream::parse(&unbound_imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan unbound output");
    assert_eq!(
        unbound_input_packets.bytes(),
        unbound_output_packets.bytes()
    );
    let (unbound_output, _) =
        SignedSecretKey::from_reader_single(Cursor::new(&unbound_imported.private_key_armored))
            .expect("parse unbound output");
    let mut budget = OpenPgpPolicyBudget::default();
    let unbound_public = unbound_output.to_public_key();
    let unbound_candidates = all_components(std::slice::from_ref(&unbound_public));
    let unbound_policy =
        validate_certificate(&unbound_public, &unbound_candidates, TEST_TIME, &mut budget)
            .expect("inspect unbound output");
    assert!(!unbound_policy.subkeys[0].authenticated);
}

#[test]
fn import_preserves_signatureless_v4_and_v6_primaries_without_authorizing_use() {
    let v4 = generated_rsa_material();
    let v6 = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .passphrase(None)
        .build()
        .expect("build v6 key parameters")
        .generate(AwsLcRng)
        .expect("generate v6 key")
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor v6 key");

    for input in [v4.private_key_armored.clone(), v6] {
        let packets =
            RawPacketStream::parse(&input, MAX_OPENPGP_PACKETS).expect("scan generated key");
        let range = packets
            .first_secret_certificate()
            .expect("find generated secret key");
        let primary = packets
            .packets()
            .get(range.start)
            .expect("primary secret packet");
        let primary_only = packets.raw(primary).to_vec();
        let primary_only_armored = armor_key_packets(&primary_only, BlockType::PrivateKey)
            .expect("armor signatureless key");
        let imported = imported_material(import_key_data(primary_only_armored));
        let imported_private =
            RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan imported signatureless key");
        assert_eq!(imported_private.bytes(), primary_only);
        let imported_public =
            RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan imported signatureless public key");
        assert_eq!(imported_public.packets().len(), 1);
        assert_eq!(imported_public.packets()[0].tag(), 6);
        let metadata = resolved_metadata(&imported).expect("signatureless certificate is selected");
        assert!(
            metadata
                .certificates
                .iter()
                .flat_map(|certificate| &certificate.policy)
                .all(|component| component.allowed_new_data_uses.is_empty())
        );
    }
}

#[test]
fn import_preserves_unsigned_secret_subkeys_without_advertising_them() {
    let material = generated_modern_material();
    let packets = RawPacketStream::parse(&material.private_key_armored, MAX_OPENPGP_PACKETS)
        .expect("scan generated key");
    let range = packets
        .first_secret_certificate()
        .expect("find generated secret key");
    let mut unsigned = Vec::new();
    let mut after_subkey = false;
    for packet in &packets.packets()[range] {
        match packet.tag() {
            7 => {
                after_subkey = true;
                unsigned.extend_from_slice(packets.raw(packet));
            }
            2 if after_subkey => {}
            _ => {
                after_subkey = false;
                unsigned.extend_from_slice(packets.raw(packet));
            }
        }
    }
    let unsigned_count = RawPacketStream::parse(&unsigned, MAX_OPENPGP_PACKETS)
        .expect("scan unsigned key")
        .packets()
        .iter()
        .filter(|packet| packet.tag() == 7)
        .count();
    assert!(unsigned_count > 0);

    let armored =
        armor_key_packets(&unsigned, BlockType::PrivateKey).expect("armor unsigned-subkey key");
    let imported = imported_material(import_key_data(armored));
    let imported_private =
        RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan imported private key");
    assert_eq!(imported_private.bytes(), unsigned);
    let imported_public = RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
        .expect("scan imported public key");
    assert_eq!(
        imported_public
            .packets()
            .iter()
            .filter(|packet| packet.tag() == 14)
            .count(),
        unsigned_count,
    );
    let metadata = resolved_metadata(&imported).expect("authenticated primary metadata");
    assert!(
        metadata
            .certificates
            .iter()
            .flat_map(|certificate| &certificate.policy)
            .all(|component| component.allowed_new_data_uses.is_empty())
    );
    let index = metadata.certificates[0]
        .index
        .as_ref()
        .expect("certificate index");
    assert_eq!(index.primary_fingerprint, imported.fingerprint);
    assert_eq!(index.components.len(), unsigned_count + 1);
    assert!(
        index.components.iter().all(|component| {
            component.stored_secret_material && !component.keygrips.is_empty()
        })
    );
}

#[test]
fn import_preserves_unknown_noncritical_packets_but_rejects_unknown_critical_packets() {
    let material = generated_modern_material();
    let packets = RawPacketStream::parse(&material.private_key_armored, MAX_OPENPGP_PACKETS)
        .expect("scan generated key");
    let range = packets
        .first_secret_certificate()
        .expect("find generated key");
    let mut with_noncritical = Vec::new();
    for (position, packet) in packets.packets()[range.clone()].iter().enumerate() {
        with_noncritical.extend_from_slice(packets.raw(packet));
        if position == 0 {
            with_noncritical.extend_from_slice(&[0xe8, 0x03, 0x01, 0x02, 0x03]);
        }
    }
    let imported = imported_material(import_key_data(
        armor_key_packets(&with_noncritical, BlockType::PrivateKey)
            .expect("armor key with noncritical packet"),
    ));
    let imported_private =
        RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan imported key with noncritical packet");
    assert_eq!(imported_private.bytes(), with_noncritical);
    assert!(
        imported_private
            .packets()
            .iter()
            .any(|packet| packet.tag() == 40)
    );
    assert!(resolved_metadata(&imported).is_some());

    let mut with_critical = Vec::new();
    for (position, packet) in packets.packets()[range].iter().enumerate() {
        with_critical.extend_from_slice(packets.raw(packet));
        if position == 0 {
            with_critical.extend_from_slice(&[0xd6, 0x00]);
        }
    }
    let rejected = import_key_data(armor_test_packets_unchecked(
        &with_critical,
        BlockType::PrivateKey,
    ));
    assert!(matches!(
        rejected.result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::MalformedKey as i32
    ));
}

#[test]
fn import_accepts_expired_transferable_secret_key() {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Example <expired@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: Some(1),
        })
        .expect("generate expired certificate")
        .as_slice(),
    )
    .expect("decode expired material");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse expired secret key");
    assert!(matches!(
        import_secret(&secret).result,
        Some(open_pgp_key_import_result::Result::Success(_))
    ));
}

#[test]
fn import_accepts_missing_backsig_but_signing_rejects_subkey() {
    let material = generated_modern_material();
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated secret key");
    let primary = &secret.primary_key;
    let signing_subkey = secret.secret_subkeys[0].key.public_key().clone();
    let binding = subkey_binding_signature(
        SigningKeyRef(primary),
        primary.public_key(),
        &signing_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        true,
        None,
    )
    .expect("create signing binding without back-signature");
    secret.secret_subkeys[0].signatures = vec![binding];

    assert!(matches!(
        import_secret(&secret).result,
        Some(open_pgp_key_import_result::Result::Success(_))
    ));
    let private_key = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor key without back-signature");
    assert_eq!(
        sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: b"must not sign".to_vec(),
            private_key,
            preferred_fingerprint: material.fingerprint.clone(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            candidate_revocation_keys: Vec::new(),
        })
        .expect_err("missing back-signature must prevent signing"),
        OpenPgpWriteError::MissingKey,
    );
}

#[test]
fn verification_rejects_unowned_signing_subkeys_without_attributing_identity() {
    let material = generated_modern_material();
    let foreign_material = generated_modern_material();
    let content = b"valid data signature from an unowned component";
    let signature = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("sign before mutating the public certificate");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse target secret key");
    let (foreign, _) = SignedSecretKey::from_reader_single(Cursor::new(
        foreign_material.private_key_armored.as_slice(),
    ))
    .expect("parse foreign secret key");
    let signing_subkey = secret.secret_subkeys[0].key.public_key().clone();
    let signing_fingerprint = fingerprint_hex(&signing_subkey);
    let (detached, _) = DetachedSignature::from_reader_single(Cursor::new(&signature))
        .expect("parse detached signature");
    detached
        .verify(&signing_subkey, content)
        .expect("the data signature itself is valid");

    let missing_backsig = subkey_binding_signature(
        SigningKeyRef(&secret.primary_key),
        secret.primary_key.public_key(),
        &signing_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        true,
        None,
    )
    .expect("create binding without back-signature");

    let foreign_signer = SigningKeyRef(&foreign.primary_key);
    let invalid_backsig = signature_config(SignatureType::KeyBinding, &foreign_signer, TEST_TIME)
        .sign_primary_key_binding(
            &foreign_signer,
            &signing_subkey,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("create invalid back-signature");
    let invalid_backsig_binding = subkey_binding_signature(
        SigningKeyRef(&secret.primary_key),
        secret.primary_key.public_key(),
        &signing_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        true,
        Some(invalid_backsig),
    )
    .expect("create binding with invalid back-signature");

    let foreign_binding =
        signature_config(SignatureType::SubkeyBinding, &foreign_signer, TEST_TIME)
            .sign_subkey_binding(
                &foreign_signer,
                secret.primary_key.public_key(),
                &Password::empty(),
                &signing_subkey,
            )
            .expect("create foreign binding");

    let mut unsigned = secret.to_public_key();
    unsigned.public_subkeys[0].signatures.clear();
    let unsigned_key = unsigned
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor certificate with unsigned subkey");
    let unsigned_result = verify_detached_with_keys(content, &signature, vec![unsigned_key]);
    assert_eq!(
        unsigned_result.status,
        OpenPgpVerificationStatus::MissingPublicKey as i32,
    );
    assert!(unsigned_result.fingerprint.is_none());
    assert!(unsigned_result.primary_fingerprint.is_none());
    assert!(unsigned_result.primary_user_id.is_none());

    for (name, signatures) in [
        ("missing back-signature", vec![missing_backsig]),
        ("invalid back-signature", vec![invalid_backsig_binding]),
    ] {
        let mut public = secret.to_public_key();
        public.public_subkeys[0].signatures = signatures;
        let public_key = public
            .to_armored_bytes(ArmorOptions::default())
            .unwrap_or_else(|error| panic!("armor {name} certificate: {error}"));
        let verification = verify_detached_with_keys(content, &signature, vec![public_key]);
        assert_rejected_signer(&verification, &signing_fingerprint, name);
    }

    // Placement is syntactic, so a binding signature issued by a foreign key
    // stays with the subkey it was filed under.  Policy is the only verifier
    // and refuses to authenticate the component, which makes this behave
    // exactly like the missing and invalid back-signature cases above rather
    // than making the subkey disappear from the certificate.
    let mut public = secret.to_public_key();
    public.public_subkeys[0].signatures = vec![foreign_binding];
    let public_key = public
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor certificate with foreign binding");
    let verification = verify_detached_with_keys(content, &signature, vec![public_key]);
    assert_rejected_signer(&verification, &signing_fingerprint, "foreign binding");
    assert!(verification.primary_fingerprint.is_none());
    assert!(verification.primary_user_id.is_none());
}

#[test]
fn a_signing_key_bound_into_two_certificates_still_verifies() {
    // RFC 9580 §5.2.3.12: issuer subpackets are hints, so more than one
    // component matching a hint must not veto verification. One colliding
    // (or, as here, genuinely shared) component used to reject the
    // signature outright, which is a verification denial-of-service.
    let material = generated_modern_material();
    let host_material = generated_modern_material();
    let content = b"a shared signing subkey must not become a verification denial of service";
    let signature = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("sign with the shared subkey");

    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse owning secret key");
    let (host, _) = SignedSecretKey::from_reader_single(Cursor::new(
        host_material.private_key_armored.as_slice(),
    ))
    .expect("parse second secret key");
    let shared_secret_subkey = &secret.secret_subkeys[0];
    let shared_subkey = shared_secret_subkey.key.public_key().clone();
    let shared_fingerprint = fingerprint_hex(&shared_subkey);

    // The owner cross-certifies the shared subkey into the second
    // certificate, so both components authenticate and both match the
    // signature's issuer hint.
    let back_signature = signature_config(
        SignatureType::KeyBinding,
        &SigningKeyRef(&shared_secret_subkey.key),
        TEST_TIME,
    )
    .sign_primary_key_binding(
        &SigningKeyRef(&shared_secret_subkey.key),
        &shared_subkey,
        &Password::empty(),
        host.primary_key.public_key(),
    )
    .expect("create back-signature for the second certificate");
    let binding = subkey_binding_signature(
        SigningKeyRef(&host.primary_key),
        host.primary_key.public_key(),
        &shared_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        true,
        Some(back_signature),
    )
    .expect("bind the shared subkey into the second certificate");

    let mut host_public = host.to_public_key();
    host_public
        .public_subkeys
        .push(pgp::composed::SignedPublicSubKey::new(
            shared_subkey,
            vec![binding],
        ));
    let host_public_key = host_public
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor the second certificate");

    for (context, public_keys) in [
        (
            "owner first",
            vec![material.public_key_armored.clone(), host_public_key.clone()],
        ),
        (
            "host first",
            vec![host_public_key, material.public_key_armored.clone()],
        ),
    ] {
        let verification = verify_detached_with_keys(content, &signature, public_keys);
        assert_eq!(
            verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{context}",
        );
        assert_eq!(
            verification.fingerprint.as_deref(),
            Some(shared_fingerprint.as_str()),
            "{context}",
        );
    }
}

#[test]
fn verification_skips_reparented_subkey_and_uses_authenticated_certificate() {
    let material = generated_modern_material();
    let attacker_material = generated_modern_material();
    let content = b"authenticated owner must win over attacker-controlled packet order";
    let signature = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("create detached signature");
    let (owner, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse owner key");
    let (attacker, _) = SignedSecretKey::from_reader_single(Cursor::new(
        attacker_material.private_key_armored.as_slice(),
    ))
    .expect("parse attacker key");
    let signing_subkey = owner.secret_subkeys[0].key.public_key().clone();
    let signing_fingerprint = fingerprint_hex(&signing_subkey);
    let forged_binding = subkey_binding_signature(
        SigningKeyRef(&attacker.primary_key),
        attacker.primary_key.public_key(),
        &signing_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        true,
        None,
    )
    .expect("bind the foreign component without its back-signature");
    let mut forged = attacker.to_public_key();
    forged.public_subkeys[0].key = signing_subkey;
    forged.public_subkeys[0].signatures = vec![forged_binding];
    let forged_key = forged
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor forged certificate");
    let owner_key = material.public_key_armored.clone();
    let owner_primary = fingerprint_hex(owner.primary_key.public_key());

    for public_keys in [
        vec![forged_key.clone(), owner_key.clone()],
        vec![owner_key.clone(), forged_key.clone()],
    ] {
        let verification = verify_detached_with_keys(content, &signature, public_keys);
        assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32,);
        assert_eq!(
            verification.fingerprint.as_deref(),
            Some(signing_fingerprint.as_str()),
        );
        assert_eq!(
            verification.primary_fingerprint.as_deref(),
            Some(owner_primary.as_str()),
        );
        assert_eq!(
            verification.primary_user_id.as_deref(),
            Some("Alice Example <alice@example.test>"),
        );
    }

    let rejected = verify_detached_with_keys(content, &signature, vec![forged_key]);
    assert_rejected_signer(&rejected, &signing_fingerprint, "forged certificate only");

    let owner_usable =
        parse_private_certificate(&material.private_key_armored).expect("parse usable owner key");
    let signing_packet =
        select_signing_packet(&owner_usable, &material.fingerprint, TEST_TIME + 1, &[])
            .expect("select authenticated signing packet");
    let signed_message = build_composed_message(
        content,
        b"owner.txt",
        Timestamp::from_secs((TEST_TIME + 1) as u32),
        Some(Timestamp::from_secs((TEST_TIME + 1) as u32)),
        Some(signing_packet),
        &[],
        None,
    )
    .expect("compose inline-signed message")
    .to_vec();
    let inline_result = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: signed_message.clone(),
            private_keys: Vec::new(),
            verification_public_keys: vec![
                forged
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("re-armor forged certificate"),
                owner_key,
            ],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: Some(true),
        })
        .expect("verify inline signature with forged certificate first")
        .as_slice(),
    )
    .expect("decode inline verification");
    let inline_verification = inline_result
        .verification
        .as_ref()
        .expect("inline verification");
    assert_eq!(
        inline_verification.status,
        OpenPgpVerificationStatus::Valid as i32,
    );
    assert_eq!(
        inline_verification.primary_fingerprint.as_deref(),
        Some(owner_primary.as_str()),
    );

    assert_eq!(
        decrypt_request(OpenPgpDecryptRequest {
            content: signed_message,
            private_keys: Vec::new(),
            verification_public_keys: vec![
                forged
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("re-armor forged certificate"),
            ],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: Some(true),
        }),
        Err(OpenPgpWriteError::InvalidArgument),
    );
}

#[test]
fn inline_verification_rejects_mathematically_valid_non_document_signature() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated secret key");
    let (signed_message, content) = inline_direct_key_signature_message(&secret);
    let mut parsed =
        Message::from_bytes(signed_message.as_slice()).expect("parse direct-key inline fixture");
    let mut parsed_content = Vec::new();
    parsed
        .read_to_end(&mut parsed_content)
        .expect("read direct-key inline fixture");
    assert_eq!(parsed_content, content);
    parsed
        .verify_nested_explicit(0, secret.secret_subkeys[0].key.public_key())
        .expect("rPGP nested verification accepts the direct key signature over these bytes");
    drop(parsed);

    assert_eq!(
        decrypt_request(OpenPgpDecryptRequest {
            content: signed_message.clone(),
            private_keys: Vec::new(),
            verification_public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: Some(true),
        }),
        Err(OpenPgpWriteError::InvalidArgument),
    );

    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: Vec::new(),
        verification_public_keys: vec![material.public_key_armored.clone()],
        reference_time_epoch_seconds: Some(TEST_TIME + 2),
        allow_signed_only: Some(true),
    })
    .expect("open invalid signed-only decryption stream");
    let mut provisional = Vec::new();
    for chunk in signed_message.chunks(7) {
        provisional.extend_from_slice(
            &session
                .update(chunk)
                .expect("stream invalid signed-only message"),
        );
    }
    assert!(matches!(
        session.finish(),
        Err(OpenPgpWriteError::InvalidArgument)
    ));
    assert!(content.starts_with(&provisional));
}

#[test]
fn inline_verification_matches_the_authenticated_recipient_primary_fingerprint() {
    let signer_material = generated_modern_material();
    let recipient_material = generated_modern_material();
    let other_material = generated_modern_material();
    let (signer, _) = SignedSecretKey::from_reader_single(Cursor::new(
        signer_material.private_key_armored.as_slice(),
    ))
    .expect("parse signer secret key");
    let (recipient, _) = SignedSecretKey::from_reader_single(Cursor::new(
        recipient_material.private_key_armored.as_slice(),
    ))
    .expect("parse recipient secret key");
    let (other, _) = SignedSecretKey::from_reader_single(Cursor::new(
        other_material.private_key_armored.as_slice(),
    ))
    .expect("parse other secret key");
    let recipient_component = recipient
        .to_public_key()
        .public_subkeys
        .into_iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key))
        .expect("recipient encryption subkey");
    let recipient_primary = recipient.primary_key.fingerprint();
    let other_primary = other.primary_key.fingerprint();

    for critical in [false, true] {
        for (label, intended_recipient, expected_status) in [
            (
                "matching primary",
                recipient_primary.clone(),
                OpenPgpVerificationStatus::Valid,
            ),
            (
                "different primary",
                other_primary.clone(),
                OpenPgpVerificationStatus::Invalid,
            ),
        ] {
            let (signed, content) =
                recipient_bound_inline_message(&signer, intended_recipient, critical);
            let encrypted = encrypt_composed_message(
                &signed,
                std::slice::from_ref(&recipient_component),
                ProtectionMode::SeipdV1Mdc,
                SymmetricKeyAlgorithm::AES256,
            )
            .expect("encrypt recipient-bound inline message");
            let result = OpenPgpDecryptResult::decode(
                decrypt_request(OpenPgpDecryptRequest {
                    content: encrypted,
                    private_keys: vec![recipient_material.private_key_armored.clone()],
                    verification_public_keys: vec![signer_material.public_key_armored.clone()],
                    reference_time_epoch_seconds: Some(TEST_TIME + 2),
                    allow_signed_only: None,
                })
                .expect("decrypt recipient-bound inline message")
                .as_slice(),
            )
            .expect("decode recipient-bound decryption result");
            assert_eq!(result.data, content, "{label}, critical={critical}");
            assert!(result.encrypted, "{label}, critical={critical}");
            assert_eq!(
                result
                    .verification
                    .as_ref()
                    .expect("inline verification")
                    .status,
                expected_status as i32,
                "{label}, critical={critical}",
            );
        }
    }

    let (signed, content) = recipient_bound_inline_message(&signer, recipient_primary, false);
    let encrypted = encrypt_composed_message(
        &signed,
        std::slice::from_ref(&recipient_component),
        ProtectionMode::SeipdV1Mdc,
        SymmetricKeyAlgorithm::AES256,
    )
    .expect("encrypt recipient-bound inline message");
    let (mut unbound_recipient, _) = SignedSecretKey::from_reader_single(Cursor::new(
        recipient_material.private_key_armored.as_slice(),
    ))
    .expect("parse recipient for unbound-subkey fixture");
    unbound_recipient.secret_subkeys[1].signatures =
        unbound_recipient.secret_subkeys[0].signatures.clone();
    let result = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted,
            private_keys: vec![
                unbound_recipient
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("armor unbound recipient"),
            ],
            verification_public_keys: vec![signer_material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: None,
        })
        .expect("decrypt through the unbound recipient component")
        .as_slice(),
    )
    .expect("decode unbound-recipient decryption result");
    assert_eq!(result.data, content);
    assert!(result.decryption_key_fingerprint.is_some());
    assert_eq!(
        result
            .verification
            .as_ref()
            .expect("inline verification")
            .status,
        OpenPgpVerificationStatus::Invalid as i32,
    );
}

#[test]
fn expired_historical_recipient_binding_verifies_intended_recipient_buffered_and_streaming() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let signer_material = generated_modern_material();
    let recipient_material = generated_modern_material();
    let (signer, _) = SignedSecretKey::from_reader_single(Cursor::new(
        signer_material.private_key_armored.as_slice(),
    ))
    .expect("parse signer secret key");
    let (mut recipient, _) = SignedSecretKey::from_reader_single(Cursor::new(
        recipient_material.private_key_armored.as_slice(),
    ))
    .expect("parse recipient secret key");
    let recipient_component = recipient
        .to_public_key()
        .public_subkeys
        .into_iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key))
        .expect("recipient encryption subkey");
    let (signed, content) =
        recipient_bound_inline_message(&signer, recipient.primary_key.fingerprint(), false);
    let encrypted = encrypt_composed_message(
        &signed,
        std::slice::from_ref(&recipient_component),
        ProtectionMode::SeipdV1Mdc,
        SymmetricKeyAlgorithm::AES256,
    )
    .expect("encrypt historically recipient-bound inline message");

    let expired_binding =
        subkey_binding_with_signature_expiration(&recipient, 1, TEST_TIME, 10, false);
    recipient.secret_subkeys[1].signatures = vec![expired_binding];
    let private_key = recipient
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor recipient with expired binding");

    for streaming in [false, true] {
        let (plaintext, verification) = decrypt_recipient_bound_case(
            &encrypted,
            &private_key,
            &signer_material.public_key_armored,
            TEST_TIME + 20,
            streaming,
        );
        assert_eq!(plaintext, content, "streaming={streaming}");
        assert_eq!(
            verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "streaming={streaming}",
        );
    }
}

#[test]
fn never_bound_recipient_subkey_is_not_misattributed_buffered_or_streaming() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let signer_material = generated_modern_material();
    let recipient_material = generated_modern_material();
    let (signer, _) = SignedSecretKey::from_reader_single(Cursor::new(
        signer_material.private_key_armored.as_slice(),
    ))
    .expect("parse signer secret key");
    let (mut recipient, _) = SignedSecretKey::from_reader_single(Cursor::new(
        recipient_material.private_key_armored.as_slice(),
    ))
    .expect("parse recipient secret key");
    let recipient_component = recipient
        .to_public_key()
        .public_subkeys
        .into_iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key))
        .expect("recipient encryption subkey");
    let (signed, content) =
        recipient_bound_inline_message(&signer, recipient.primary_key.fingerprint(), false);
    let encrypted = encrypt_composed_message(
        &signed,
        std::slice::from_ref(&recipient_component),
        ProtectionMode::SeipdV1Mdc,
        SymmetricKeyAlgorithm::AES256,
    )
    .expect("encrypt recipient-bound inline message");

    // The encryption subkey's exact secret material remains available, but its
    // signatures were copied from a different subkey and never authenticate it
    // as belonging to this primary key at any time.
    recipient.secret_subkeys[1].signatures = recipient.secret_subkeys[0].signatures.clone();
    let private_key = recipient
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor recipient with spliced binding");

    for streaming in [false, true] {
        let (plaintext, verification) = decrypt_recipient_bound_case(
            &encrypted,
            &private_key,
            &signer_material.public_key_armored,
            TEST_TIME + 2,
            streaming,
        );
        assert_eq!(plaintext, content, "streaming={streaming}");
        assert_eq!(
            verification.status,
            OpenPgpVerificationStatus::Invalid as i32,
            "streaming={streaming}",
        );
    }
}

#[test]
fn verification_resolves_multiple_unhashed_key_ids_cryptographically() {
    let owner_material = generated_modern_material();
    let other_material = generated_modern_material();
    let content = b"multiple issuer key IDs must not select by packet order";
    let (owner, _) = SignedSecretKey::from_reader_single(Cursor::new(
        owner_material.private_key_armored.as_slice(),
    ))
    .expect("parse owner key");
    let (other, _) = SignedSecretKey::from_reader_single(Cursor::new(
        other_material.private_key_armored.as_slice(),
    ))
    .expect("parse other key");
    let signer = &owner.secret_subkeys[0].key;
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("signature creation subpacket"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .expect("owner issuer key ID"),
        Subpacket::regular(SubpacketData::IssuerKeyId(
            other.secret_subkeys[0].key.legacy_key_id(),
        ))
        .expect("other issuer key ID"),
    ];
    let signature = config
        .sign(signer, &Password::empty(), Cursor::new(content))
        .expect("sign ambiguous issuer signature");
    signature
        .verify(signer.public_key(), content.as_slice())
        .expect("the data signature itself is valid");
    let signature = DetachedSignature::new(signature)
        .to_bytes()
        .expect("serialize ambiguous signature");

    let verification = verify_detached_with_keys(
        content,
        &signature,
        vec![
            owner_material.public_key_armored.clone(),
            other_material.public_key_armored.clone(),
        ],
    );
    assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32,);
    assert_eq!(
        verification.fingerprint.as_deref(),
        Some(fingerprint_hex(signer).as_str()),
    );
    assert_eq!(
        verification.user_ids,
        ["Alice Example <alice@example.test>"],
    );
    assert!(verification.warnings.is_empty());
    assert_eq!(
        verification.primary_fingerprint.as_deref(),
        Some(fingerprint_hex(&owner.primary_key).as_str()),
    );
    assert_eq!(
        verification.primary_user_id.as_deref(),
        Some("Alice Example <alice@example.test>"),
    );
}

#[test]
fn message_decryption_routes_exact_unbound_subkey_for_historical_access() {
    let material = generated_modern_material();
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"bound recipient only".to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "quarantine.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt to bound subkey")
        .as_slice(),
    )
    .expect("decode encrypted message");
    let (mut unbound, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated key");
    unbound.secret_subkeys[1].signatures = unbound.secret_subkeys[0].signatures.clone();
    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![
                unbound
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("armor unbound key"),
            ],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("historical decryption does not require a current binding")
        .as_slice(),
    )
    .expect("decode historical decryption result");
    assert_eq!(decrypted.data, b"bound recipient only");
}

#[test]
fn message_decryption_ignores_current_flags_for_historical_access() {
    let material = generated_modern_material();
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"sign-only recipient must be rejected".to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "sign-only.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt to bound encryption subkey")
        .as_slice(),
    )
    .expect("decode encrypted message");

    let (mut sign_only, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated key");
    let primary = &sign_only.primary_key;
    let target = sign_only.secret_subkeys[1].key.public_key().clone();
    let binding = subkey_binding_signature(
        SigningKeyRef(primary),
        primary.public_key(),
        &target,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        false,
        None,
    )
    .expect("create current sign-only binding");
    sign_only.secret_subkeys[1].signatures = vec![binding];

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![
                sign_only
                    .to_armored_bytes(ArmorOptions::default())
                    .expect("armor sign-only key"),
            ],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("historical decryption does not require current encrypt flags")
        .as_slice(),
    )
    .expect("decode historical decryption result");
    assert_eq!(decrypted.data, b"sign-only recipient must be rejected");
}

#[test]
fn expired_newer_binding_does_not_shadow_live_cross_certified_binding() {
    let material = generated_modern_material();
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated secret key");
    let newer = subkey_binding_with_signature_expiration(&secret, 0, TEST_TIME + 10, 1, false);
    secret.secret_subkeys[0].signatures.push(newer);

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let component_index = secret.public_subkeys.len();
    let inspect_at = |reference_time| {
        let mut budget = OpenPgpPolicyBudget::default();
        validate_certificate(&public, &candidates, reference_time, &mut budget)
            .expect("inspect certificate")
    };

    let current = inspect_at(TEST_TIME + 10);
    let current = &current.subkeys[component_index];
    assert_eq!(
        current
            .effective_signature
            .and_then(pgp::packet::Signature::created)
            .map(Timestamp::as_secs),
        Some((TEST_TIME + 10) as u32),
    );
    assert!(!current.signing_cross_certified);

    let after_expiration_policy = inspect_at(TEST_TIME + 12);
    let after_expiration = &after_expiration_policy.subkeys[component_index];
    assert_eq!(
        after_expiration
            .effective_signature
            .and_then(pgp::packet::Signature::created)
            .map(Timestamp::as_secs),
        Some(TEST_TIME as u32),
    );
    assert!(after_expiration.signing_cross_certified);
    assert!(
        after_expiration_policy
            .subkey_components()
            .nth(component_index)
            .expect("evaluated subkey")
            .signing_usable(),
    );
}

#[test]
fn import_accepts_signature_expired_subkey_binding() {
    let material = generated_modern_material();
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated secret key");
    let expired = subkey_binding_with_signature_expiration(&secret, 1, TEST_TIME - 10, 1, true);
    secret.secret_subkeys[1].signatures = vec![expired];

    assert!(matches!(
        import_secret(&secret).result,
        Some(open_pgp_key_import_result::Result::Success(_))
    ));
}

#[test]
fn generated_certificate_stream_signature_verifies_through_read_policy() {
    let material = generated_modern_material();
    let content = b"streamed OpenPGP detached signature";
    let mut session = open_detached_sign_session(OpenPgpDetachedSignStreamOpenRequest {
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: material.fingerprint.clone(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open detached signing session");
    session.update(&content[..11]).expect("first signing chunk");
    session
        .update(&content[11..])
        .expect("second signing chunk");
    let signature = session.finish().expect("finish detached signature");
    let verification = OpenPgpVerification::decode(
        crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: content.to_vec(),
            signature,
            public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
        })
        .expect("verify streamed detached signature")
        .as_slice(),
    )
    .expect("decode verification");
    assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
}

#[test]
fn rsa_generation_signing_and_decryption_use_the_sensitive_adapter() {
    let material = generated_rsa_material();
    let content = b"AWS-LC RSA OpenPGP adapter";
    let signature_bytes = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::Detached as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: false,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("RSA detached signature");
    let (signature, _) =
        DetachedSignature::from_reader_single(Cursor::new(signature_bytes.clone()))
            .expect("parse RSA signature");
    let (certificate, _) =
        SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
            .expect("parse RSA certificate");
    let signing_key = certificate
        .public_subkeys
        .iter()
        .find(|subkey| subkey.key.algorithm().can_sign())
        .expect("RSA signing subkey");
    signature
        .verify(&signing_key.key, content)
        .expect("verify RSA signature");
    let verification = OpenPgpVerification::decode(
        crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: content.to_vec(),
            signature: signature_bytes,
            public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
        })
        .expect("verify RSA signature through read policy")
        .as_slice(),
    )
    .expect("decode RSA verification");
    assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(
        verification.fingerprint.as_deref(),
        Some(fingerprint_hex(&signing_key.key).as_str()),
    );
    assert_eq!(
        verification.primary_fingerprint.as_deref(),
        Some(fingerprint_hex(&certificate.primary_key).as_str()),
    );
    assert_eq!(
        verification.primary_user_id.as_deref(),
        Some("RSA Example <rsa@example.test>"),
    );

    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: content.to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "rsa.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("RSA recipient encryption")
        .as_slice(),
    )
    .expect("decode RSA encryption");
    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            allow_signed_only: None,
        })
        .expect("AWS-LC RSA PKESK decryption")
        .as_slice(),
    )
    .expect("decode RSA decryption");
    assert_eq!(decrypted.data, content);
}

#[test]
fn import_reports_passphrase_and_public_only_outcomes() {
    let material = generated_modern_material();
    let (mut protected, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.clone()))
            .expect("parse generated secret key");
    let password = Password::from("correct horse battery staple");
    protected
        .primary_key
        .set_password(AwsLcRng, &password)
        .expect("protect primary key");
    for subkey in &mut protected.secret_subkeys {
        subkey
            .key
            .set_password(AwsLcRng, &password)
            .expect("protect secret subkey");
    }
    let protected = protected
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor protected key");

    let import = |key_data: Vec<u8>, passphrase_utf8: Option<Vec<u8>>| {
        OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data,
                passphrase_utf8,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("import request")
            .as_slice(),
        )
        .expect("decode import result")
    };
    assert!(matches!(
        import(protected.clone(), None).result,
        Some(open_pgp_key_import_result::Result::NeedsPassphrase(_))
    ));
    assert!(matches!(
        import(protected.clone(), Some(b"wrong".to_vec())).result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::InvalidPassphrase as i32
    ));
    assert!(matches!(
        import(protected, Some(b"correct horse battery staple".to_vec())).result,
        Some(open_pgp_key_import_result::Result::Success(_))
    ));
    assert!(matches!(
        import(material.public_key_armored.clone(), None).result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
    ));
}

#[test]
fn import_accepts_sequoia_filtered_tsk_with_public_primary_and_secret_subkeys() {
    let filtered = filtered_tsk_fixture();
    let filtered_stream = RawPacketStream::parse(&filtered, MAX_OPENPGP_PACKETS)
        .expect("parse Sequoia-style filtered TSK");
    assert_eq!(filtered_stream.packets()[0].tag(), PUBLIC_KEY_TAG);
    assert!(
        filtered_stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == SECRET_SUBKEY_TAG)
    );

    let imported = imported_material(import_key_data(filtered));
    let private = RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
        .expect("parse imported filtered TSK");
    assert_eq!(private.packets()[0].tag(), PUBLIC_KEY_TAG);
    assert!(
        private
            .packets()
            .iter()
            .any(|packet| packet.tag() == SECRET_SUBKEY_TAG)
    );
    SignedPublicKey::from_reader_single(Cursor::new(&imported.public_key_armored))
        .expect("parse public projection of filtered TSK");
}

#[test]
fn import_treats_exact_v4_gnu_dummy_primary_as_absent_secret_material() {
    let source = generated_modern_material();

    for usage in [254, 255] {
        let dummy =
            secret_certificate_with_gnu_dummy_primary(&source.private_key_armored, usage, &[]);
        let dummy_stream = RawPacketStream::parse(&dummy, MAX_OPENPGP_PACKETS)
            .expect("parse source GNU dummy TSK");
        let imported = imported_material(import_key_data(dummy));
        let private = RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("parse preserved GNU dummy TSK");
        let public = RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
            .expect("parse public projection of GNU dummy TSK");

        assert_eq!(private.packets()[0].tag(), SECRET_KEY_TAG);
        assert_eq!(
            private.raw(&private.packets()[0]),
            dummy_stream.raw(&dummy_stream.packets()[0]),
        );
        assert_eq!(public.packets()[0].tag(), PUBLIC_KEY_TAG);
        assert!(!public.packets().iter().any(|packet| packet.tag() == 5));
        assert!(
            private
                .packets()
                .iter()
                .any(|packet| packet.tag() == SECRET_SUBKEY_TAG)
        );
        assert_eq!(imported.fingerprint, source.fingerprint);
    }

    for usage in [254, 255] {
        let trailing_data =
            secret_certificate_with_gnu_dummy_primary(&source.private_key_armored, usage, &[0]);
        assert!(matches!(
            import_key_data(trailing_data).result,
            Some(open_pgp_key_import_result::Result::NeedsPassphrase(_))
        ));
    }
}

#[cfg(unix)]
#[test]
fn gnupg_reimports_preserved_dummy_primary_and_secret_subkeys() {
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        process::Command,
        time::{SystemTime, UNIX_EPOCH},
    };

    if Command::new("gpg").arg("--version").output().is_err() {
        return;
    }

    struct GnuPgHome(std::path::PathBuf);

    impl Drop for GnuPgHome {
        fn drop(&mut self) {
            let _ = Command::new("gpgconf")
                .arg("--homedir")
                .arg(&self.0)
                .args(["--kill", "all"])
                .status();
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    let source = generated_modern_material();
    for usage in [254, 255] {
        let dummy =
            secret_certificate_with_gnu_dummy_primary(&source.private_key_armored, usage, &[]);
        let imported = imported_material(import_key_data(dummy));
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock after Unix epoch")
            .as_nanos();
        // GnuPG agent sockets have a short platform path limit.  `/tmp` keeps
        // this isolated home below it even when the host's TMPDIR is long.
        let home = GnuPgHome(
            std::path::Path::new("/tmp")
                .join(format!("kg-gpg-{}-{usage}-{nonce:x}", std::process::id(),)),
        );
        fs::create_dir(&home.0).expect("create isolated GnuPG home");
        fs::set_permissions(&home.0, fs::Permissions::from_mode(0o700))
            .expect("restrict isolated GnuPG home");
        let agent = Command::new("gpg-connect-agent")
            .arg("--homedir")
            .arg(&home.0)
            .arg("/bye")
            .output();
        if !agent.is_ok_and(|output| output.status.success()) {
            // Sandboxed test runners may provide gpg but prohibit its Unix
            // domain socket. Deterministic packet tests still cover the wire
            // contract in that environment.
            return;
        }
        let key_path = home.0.join("filtered-private.asc");
        fs::write(&key_path, &imported.private_key_armored)
            .expect("write filtered private key fixture");

        let imported_by_gnupg = Command::new("gpg")
            .args(["--batch", "--yes", "--no-tty", "--homedir"])
            .arg(&home.0)
            .arg("--import")
            .arg(&key_path)
            .output()
            .expect("run GnuPG import");
        assert!(
            imported_by_gnupg.status.success(),
            "GnuPG failed to import usage {usage}: {}",
            String::from_utf8_lossy(&imported_by_gnupg.stderr),
        );

        let listed = Command::new("gpg")
            .args(["--batch", "--with-colons", "--homedir"])
            .arg(&home.0)
            .arg("--list-secret-keys")
            .arg(&imported.fingerprint)
            .output()
            .expect("list imported GnuPG secret keys");
        assert!(
            listed.status.success(),
            "GnuPG did not retain usage {usage} secret keys: {}",
            String::from_utf8_lossy(&listed.stderr),
        );
        let listing = String::from_utf8_lossy(&listed.stdout);
        assert!(
            listing.lines().any(|line| line.starts_with("ssb:")),
            "GnuPG usage {usage} listing has no secret subkey: {listing}",
        );
    }
}

#[cfg(unix)]
#[test]
fn gnupg_decrypts_messages_for_generated_v4_certificates() {
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        process::Command,
        time::{SystemTime, UNIX_EPOCH},
    };

    if !Command::new("gpg")
        .arg("--version")
        .output()
        .is_ok_and(|output| output.status.success())
    {
        return;
    }

    struct GnuPgHome(std::path::PathBuf);

    impl Drop for GnuPgHome {
        fn drop(&mut self) {
            let _ = Command::new("gpgconf")
                .arg("--homedir")
                .arg(&self.0)
                .args(["--kill", "all"])
                .status();
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    let plaintext = b"GnuPG decrypts Keyguard-generated V4 recipients";
    for (index, (kind, material)) in generated_v4_materials().into_iter().enumerate() {
        let encrypted = encrypt_preference_test_message(
            std::slice::from_ref(&material),
            plaintext,
            TEST_TIME + 1,
            false,
            false,
            OpenPgpProtectionMode::GnupgOcb,
        );
        assert_gnupg_compatible_encryption_wire(&encrypted, kind);

        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock after Unix epoch")
            .as_nanos();
        let home = GnuPgHome(std::path::Path::new("/tmp").join(format!(
            "kg-gpg-decrypt-{}-{index}-{nonce:x}",
            std::process::id(),
        )));
        fs::create_dir(&home.0).expect("create isolated GnuPG home");
        fs::set_permissions(&home.0, fs::Permissions::from_mode(0o700))
            .expect("restrict isolated GnuPG home");
        let agent = Command::new("gpg-connect-agent")
            .arg("--homedir")
            .arg(&home.0)
            .arg("/bye")
            .output();
        if !agent.is_ok_and(|output| output.status.success()) {
            // Sandboxed runners may expose gpg but prohibit its Unix-domain
            // socket. The deterministic wire test above remains authoritative.
            return;
        }

        let key_path = home.0.join("generated-private.asc");
        let message_path = home.0.join("message.pgp");
        fs::write(&key_path, &material.private_key_armored)
            .expect("write generated GnuPG private key");
        fs::write(&message_path, &encrypted).expect("write generated GnuPG message");

        let imported = Command::new("gpg")
            .args(["--batch", "--yes", "--no-tty", "--homedir"])
            .arg(&home.0)
            .arg("--import")
            .arg(&key_path)
            .output()
            .expect("run GnuPG generated-key import");
        assert!(
            imported.status.success(),
            "GnuPG failed to import generated {kind} key: {}",
            String::from_utf8_lossy(&imported.stderr),
        );

        let decrypted = Command::new("gpg")
            .args([
                "--batch",
                "--yes",
                "--no-tty",
                "--pinentry-mode",
                "loopback",
                "--homedir",
            ])
            .arg(&home.0)
            .arg("--decrypt")
            .arg(&message_path)
            .output()
            .expect("run GnuPG generated-key decryption");
        assert!(
            decrypted.status.success(),
            "GnuPG failed to decrypt generated {kind} message: {}",
            String::from_utf8_lossy(&decrypted.stderr),
        );
        assert_eq!(decrypted.stdout, plaintext, "generated {kind}");
    }
}

#[test]
fn filtered_tsk_consumers_use_secret_subkeys_end_to_end() {
    let sequoia_source = generated_modern_material();
    let sequoia = secret_certificate_with_public_primary(
        &sequoia_source.private_key_armored,
        &sequoia_source.public_key_armored,
    );
    let gnu_source = generated_modern_material();
    let gnu_dummy =
        secret_certificate_with_gnu_dummy_primary(&gnu_source.private_key_armored, 254, &[]);
    let gnu_normalized = imported_material(import_key_data(gnu_dummy));
    let cases = [
        ("Sequoia filtered TSK", sequoia),
        (
            "preserved GnuPG dummy-primary TSK",
            gnu_normalized.private_key_armored.clone(),
        ),
    ];
    let content = b"mixed transferable secret-key consumer coverage";

    for (label, private_key) in cases {
        let parsed = parse_private_certificate(&private_key)
            .unwrap_or_else(|error| panic!("parse {label}: {error}"));
        assert!(parsed.primary().is_none(), "{label} has no primary secret");
        let primary_fingerprint = fingerprint_hex(&parsed.public().primary_key);
        let signing_packet =
            select_signing_packet(&parsed, &primary_fingerprint, TEST_TIME + 1, &[])
                .unwrap_or_else(|error| panic!("select {label} signing subkey: {error}"));
        let SecretPacketRef::Subkey(signing_subkey) = signing_packet else {
            panic!("{label} must sign with a real secret subkey");
        };
        let signing_fingerprint = fingerprint_hex(signing_subkey);
        let public_key = parsed
            .public()
            .to_armored_bytes(ArmorOptions::default())
            .unwrap_or_else(|error| panic!("armor {label} public certificate: {error}"));
        drop(parsed);

        let detached = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: content.to_vec(),
            private_key: private_key.clone(),
            preferred_fingerprint: primary_fingerprint.clone(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("detached sign with {label}: {error}"));
        let verification = verify_detached_with_keys(content, &detached, vec![public_key.clone()]);
        assert_eq!(
            verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} detached signature",
        );
        assert_eq!(
            verification.fingerprint.as_deref(),
            Some(signing_fingerprint.as_str()),
            "{label} detached signer",
        );

        let clear = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::ClearText as i32,
            content: content.to_vec(),
            private_key: private_key.clone(),
            preferred_fingerprint: primary_fingerprint.clone(),
            armored: true,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("clear sign with {label}: {error}"));
        let clear_verification = OpenPgpVerification::decode(
            crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: clear,
                signature: Vec::new(),
                public_keys: vec![public_key.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
            })
            .unwrap_or_else(|error| panic!("verify {label} clear signature: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label} clear verification: {error}"));
        assert_eq!(
            clear_verification.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} clear signature",
        );

        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: content.to_vec(),
                public_keys: vec![public_key.clone()],
                signing_private_key: Some(private_key.clone()),
                preferred_signing_fingerprint: primary_fingerprint,
                file_name: "mixed-tsk.txt".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 1),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
                enable_compression: Some(false),
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("encrypt and sign with {label}: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label} encryption result: {error}"));
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![private_key],
                verification_public_keys: vec![public_key],
                // Encrypt-and-sign uses the independent signing clock, not the
                // literal timestamp or the historical key-selection time.
                reference_time_epoch_seconds: None,
                allow_signed_only: None,
            })
            .unwrap_or_else(|error| panic!("decrypt with {label} encryption subkey: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label} decryption result: {error}"));
        assert_eq!(decrypted.data, content, "{label} decrypted content");
        assert_eq!(
            decrypted
                .verification
                .as_ref()
                .expect("inline signature verification")
                .status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} inline signature",
        );
        assert!(
            decrypted.decryption_key_fingerprint.is_some(),
            "{label} decryption used a secret encryption subkey",
        );
    }
}

fn malleable_cfb_params<K>(
    plain: &PlainSecretParams,
    public: &K,
    passphrase: &[u8],
    seed: u8,
) -> EncryptedSecretParams
where
    K: KeyDetails + Serialize,
{
    let sym_alg = SymmetricKeyAlgorithm::AES256;
    let s2k = StringToKey::IteratedAndSalted {
        hash_alg: HashAlgorithm::Sha256,
        salt: [seed; 8],
        count: 0x60,
    };
    let iv = vec![seed.wrapping_add(1); sym_alg.block_size()];
    let derived = s2k
        .derive_key(passphrase, sym_alg.key_size())
        .expect("derive test S2K");
    let mut data = Zeroizing::new(Vec::new());
    plain
        .to_writer(&mut *data, public.version())
        .expect("serialize plain secret with simple checksum");
    sym_alg
        .encrypt_with_iv_regular(derived.as_ref(), &iv, &mut data)
        .expect("encrypt test secret");
    EncryptedSecretParams::new(
        data.to_vec().into(),
        S2kParams::MalleableCfb {
            sym_alg,
            s2k,
            iv: iv.into(),
        },
    )
}

#[test]
fn import_accepts_bc_legacy_malleable_cfb_and_removes_password() {
    let material = generated_modern_material();
    let (mut protected, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.clone()))
            .expect("parse generated secret key");
    let passphrase = b"correct horse battery staple";

    let primary_public = protected.primary_key.public_key().clone();
    let primary_plain = match protected.primary_key.secret_params() {
        SecretParams::Plain(plain) => plain.clone(),
        SecretParams::Encrypted(_) => panic!("generated primary is unprotected"),
    };
    let primary_encrypted = malleable_cfb_params(&primary_plain, &primary_public, passphrase, 1);
    protected.primary_key =
        SecretKey::new(primary_public, SecretParams::Encrypted(primary_encrypted))
            .expect("protect primary");

    for (index, subkey) in protected.secret_subkeys.iter_mut().enumerate() {
        let public = subkey.key.public_key().clone();
        let plain = match subkey.key.secret_params() {
            SecretParams::Plain(plain) => plain.clone(),
            SecretParams::Encrypted(_) => panic!("generated subkey is unprotected"),
        };
        let encrypted = malleable_cfb_params(&plain, &public, passphrase, index as u8 + 2);
        subkey.key =
            SecretSubkey::new(public, SecretParams::Encrypted(encrypted)).expect("protect subkey");
    }
    let protected = protected
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor usage-255 key");
    let raw = RawPacketStream::parse(&protected, MAX_OPENPGP_PACKETS).expect("scan usage-255 key");
    let range = raw.first_secret_certificate().expect("find usage-255 key");
    for span in raw.packets()[range]
        .iter()
        .filter(|span| matches!(span.tag(), 5 | 7))
    {
        let public_len = import_secret_packet_public_len(&raw, span).expect("parse secret packet");
        let body = raw.body(span);
        assert_eq!(body.get(public_len), Some(&255));
    }

    let import = |password: &[u8]| {
        OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data: protected.clone(),
                passphrase_utf8: Some(password.to_vec()),
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("typed import result")
            .as_slice(),
        )
        .expect("decode import result")
    };
    assert!(matches!(
        import(b"wrong").result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::InvalidPassphrase as i32
    ));
    let success = match import(passphrase).result {
        Some(open_pgp_key_import_result::Result::Success(success)) => success,
        result => panic!("expected successful compatibility import, got {result:?}"),
    };
    let imported = success.key_material.expect("imported key material");
    let (passwordless, _) =
        SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.clone()))
            .expect("parse passwordless import");
    assert!(!passwordless.primary_key.secret_params().is_encrypted());
    assert!(
        passwordless
            .secret_subkeys
            .iter()
            .all(|subkey| !subkey.key.secret_params().is_encrypted())
    );
}

#[test]
fn clear_sign_canonicalizes_marker_lines_and_trailing_whitespace() {
    let material = generated_modern_material();
    let content = b"- leading dash\n - space-indented dash\n\t- tab-indented dash\n-----BEGIN PGP SIGNATURE-----\n-----BEGIN PGP SIGNED MESSAGE-----\ntrailing whitespace here   ";
    let signed = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: Some(TEST_TIME + 1),
        reference_time_epoch_seconds: Some(TEST_TIME + 1),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("clear sign marker lines");
    assert!(
        signed
            .windows(b"- -----BEGIN PGP SIGNATURE-----".len())
            .any(|window| window == b"- -----BEGIN PGP SIGNATURE-----")
    );
    assert!(
        signed
            .windows(b"\n - space-indented dash\n\t- tab-indented dash\n".len())
            .any(|window| window == b"\n - space-indented dash\n\t- tab-indented dash\n")
    );
    let verification = OpenPgpVerification::decode(
        crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: signed.to_vec(),
            signature: Vec::new(),
            public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("verify clear signature")
        .as_slice(),
    )
    .expect("decode verification");
    assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
}

#[test]
fn restored_authorities_allow_signing_and_encryption_only_while_restoration_is_live() {
    let _guard = STREAM_TEST_LOCK.lock().expect("stream test lock");
    let material = generated_modern_material();
    let (base, _) = SignedSecretKey::from_reader_single(Cursor::new(&material.private_key_armored))
        .expect("generated secret");
    for revoke_primary in [false, true] {
        let mut secret = base.clone();
        let signer = SigningKeyRef(&base.primary_key);
        let targets = if revoke_primary {
            vec![None]
        } else {
            vec![Some(0), Some(1)]
        };
        for target in targets {
            let mut config = signature_config(
                if target.is_none() {
                    SignatureType::KeyRevocation
                } else {
                    SignatureType::SubkeyRevocation
                },
                &signer,
                TEST_TIME + 10,
            );
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::RevocationReason(
                    pgp::packet::RevocationCode::KeyRetired,
                    Vec::new().into(),
                ))
                .expect("retirement reason"),
            );
            let revocation = if let Some(index) = target {
                config.sign_subkey_binding(
                    &signer,
                    base.primary_key.public_key(),
                    &Password::empty(),
                    base.secret_subkeys[index].key.public_key(),
                )
            } else {
                config.sign_key(&signer, &Password::empty(), base.primary_key.public_key())
            }
            .expect("retire authority");
            let mut config = if let Some(index) = target {
                base.secret_subkeys[index]
                    .signatures
                    .iter()
                    .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
                    .and_then(pgp::packet::Signature::config)
                    .cloned()
                    .expect("binding config")
            } else {
                signature_config(SignatureType::Key, &signer, TEST_TIME + 20)
            };
            config.hashed_subpackets.retain(|packet| {
                !matches!(
                    packet.data,
                    SubpacketData::SignatureCreationTime(_)
                        | SubpacketData::SignatureExpirationTime(_)
                )
            });
            config.hashed_subpackets.extend([
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    (TEST_TIME + 20) as u32,
                )))
                .expect("restoration time"),
                Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                    10,
                )))
                .expect("restoration lifetime"),
            ]);
            let restoring = if let Some(index) = target {
                config.sign_subkey_binding(
                    &signer,
                    base.primary_key.public_key(),
                    &Password::empty(),
                    base.secret_subkeys[index].key.public_key(),
                )
            } else {
                config.sign_key(&signer, &Password::empty(), base.primary_key.public_key())
            }
            .expect("restore authority");
            if let Some(index) = target {
                secret.secret_subkeys[index]
                    .signatures
                    .extend([revocation, restoring]);
            } else {
                secret.details.revocation_signatures.push(revocation);
                secret.details.direct_signatures.push(restoring);
            }
        }
        let private_key = secret
            .to_armored_bytes(ArmorOptions::default())
            .expect("secret armor");
        let public_key = secret
            .to_public_key()
            .to_armored_bytes(ArmorOptions::default())
            .expect("public armor");
        let fingerprint = fingerprint_hex(base.secret_subkeys[0].key.public_key());
        for (offset, available) in [
            (9, true),
            (10, false),
            (19, false),
            (20, true),
            (29, true),
            (30, false),
        ] {
            let time = TEST_TIME + offset;
            let signed = sign_request(OpenPgpSignRequest {
                kind: OpenPgpSignKind::Detached as i32,
                content: b"restored signer".to_vec(),
                private_key: private_key.clone(),
                preferred_fingerprint: fingerprint.clone(),
                armored: false,
                signature_time_epoch_seconds: Some(time),
                reference_time_epoch_seconds: Some(time),
                candidate_revocation_keys: Vec::new(),
            });
            assert_eq!(
                signed.is_ok(),
                available,
                "primary {revoke_primary}, signed at {time}"
            );
            let streamed = open_detached_sign_session(OpenPgpDetachedSignStreamOpenRequest {
                private_key: private_key.clone(),
                preferred_fingerprint: fingerprint.clone(),
                armored: false,
                signature_time_epoch_seconds: Some(time),
                reference_time_epoch_seconds: Some(time),
                candidate_revocation_keys: Vec::new(),
            })
            .and_then(|mut stream| {
                stream.update(b"restored signer")?;
                stream.finish()
            });
            assert_eq!(
                streamed.is_ok(),
                available,
                "primary {revoke_primary}, streamed at {time}"
            );
            if !available {
                assert_eq!(signed.err(), Some(OpenPgpWriteError::MissingKey));
                assert_eq!(streamed.err(), Some(OpenPgpWriteError::MissingKey));
            }
            for streaming in [false, true] {
                let encrypted = encrypt_recipient_documents(
                    vec![public_key.clone()],
                    Vec::new(),
                    time,
                    streaming,
                );
                assert_eq!(
                    encrypted.is_ok(),
                    available,
                    "primary {revoke_primary}, encrypted at {time}, streaming {streaming}"
                );
                if !available {
                    assert_eq!(encrypted.err(), Some(OpenPgpWriteError::MissingKey));
                }
            }
        }
    }
}

#[test]
fn expired_primary_blocks_signing_and_recipient_selection() {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Example <expired@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: Some(1),
        })
        .expect("generate expiring certificate")
        .as_slice(),
    )
    .expect("decode expiring certificate");
    let ciphertext = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"historical decrypt".to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "historical.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt while the certificate is live")
        .as_slice(),
    )
    .expect("decode historical encryption result");
    assert_eq!(
        sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: b"expired".to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            candidate_revocation_keys: Vec::new(),
        })
        .expect_err("expired primary cannot sign"),
        OpenPgpWriteError::MissingKey
    );
    assert_eq!(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"expired".to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "expired.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect_err("expired primary cannot receive"),
        OpenPgpWriteError::MissingKey
    );

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: ciphertext.data,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: Some(false),
        })
        .expect("expired key remains available for historical decryption")
        .as_slice(),
    )
    .expect("decode historical decryption result");
    assert_eq!(decrypted.data, b"historical decrypt");
}

#[test]
fn legacy_recipients_are_strict_but_legacy_decrypt_candidates_are_skipped() {
    let material = generated_modern_material();
    let legacy_public = include_bytes!("../../../../tests/fixtures/openpgp/v3-public.asc").to_vec();
    let legacy_secret_bytes =
        include_bytes!("../../../../tests/fixtures/openpgp/v3-secret.asc").to_vec();
    let legacy_import = OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data: legacy_secret_bytes.clone(),
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("legacy import returns typed result")
        .as_slice(),
    )
    .expect("decode legacy import result");
    assert!(matches!(
        legacy_import.result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
    ));
    let request = |public_keys| OpenPgpEncryptRequest {
        content: b"legacy policy".to_vec(),
        public_keys,
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "legacy.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME),
        reference_time_epoch_seconds: Some(TEST_TIME),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    };
    assert_eq!(
        encrypt_request(request(vec![legacy_public.clone()])),
        Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
    );
    assert_eq!(
        encrypt_request(request(vec![
            material.public_key_armored.clone(),
            legacy_public,
        ])),
        Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
    );

    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(request(vec![material.public_key_armored.clone()]))
            .expect("modern recipient encryption")
            .as_slice(),
    )
    .expect("decode modern encryption");
    let modern_secret = Zeroizing::new(material.private_key_armored.clone());
    let candidates = vec![Zeroizing::new(legacy_secret_bytes.clone()), modern_secret];
    assert_eq!(
        parse_secret_key_candidates(&candidates)
            .expect("mixed candidates")
            .len(),
        1
    );
    let legacy_only = vec![Zeroizing::new(
        include_bytes!("../../../../tests/fixtures/openpgp/v3-secret.asc").to_vec(),
    )];
    assert!(matches!(
        parse_secret_key_candidates(&legacy_only),
        Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
    ));
    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![legacy_secret_bytes, material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("mixed-candidate decryption")
        .as_slice(),
    )
    .expect("decode mixed-candidate decryption");
    assert_eq!(decrypted.data, b"legacy policy");
}

#[test]
fn legacy_v2_secret_packet_is_unsupported_but_truncation_is_malformed() {
    let mut legacy_v2 = RawPacketStream::parse(
        include_bytes!("../../../../tests/fixtures/openpgp/v3-secret.asc"),
        MAX_OPENPGP_PACKETS,
    )
    .expect("decode checked-in v3 secret fixture")
    .bytes()
    .to_vec();
    assert_eq!(legacy_v2.first().copied(), Some(0x95));
    assert_eq!(legacy_v2.get(3).copied(), Some(3));
    legacy_v2[3] = 2;

    let import = |key_data: Vec<u8>| {
        OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data,
                passphrase_utf8: None,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("import returns a typed result")
            .as_slice(),
        )
        .expect("decode import result")
    };
    let legacy_result = import(legacy_v2.to_vec());
    assert!(matches!(
        legacy_result.result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
    ));

    let truncated_result = import(legacy_v2[..3].to_vec());
    assert!(matches!(
        truncated_result.result,
        Some(open_pgp_key_import_result::Result::Error(error))
            if error.reason == OpenPgpKeyImportErrorReason::MalformedKey as i32
    ));
}

#[test]
fn recipient_features_presence_controls_v6_seipd_v2_inference_end_to_end() {
    let v6_without_features =
        rewrite_effective_primary_features(generated_v6_encryption_material(), None, true);
    let v6_explicit_negative =
        rewrite_effective_primary_features(generated_v6_encryption_material(), Some(0x01), true);
    let v4_without_features = rewrite_effective_primary_features(
        generated_v4_preference_material("v4-no-features", None, None),
        None,
        false,
    );
    let v6_explicit = generated_v6_encryption_material();
    let plaintext = b"authenticated Features presence controls SEIPDv2 inference";
    let reference_time = 1_800_000_000;

    struct Scenario<'a> {
        label: &'static str,
        recipients: Vec<&'a OpenPgpKeyMaterial>,
        protection_mode: OpenPgpProtectionMode,
        pkesk_version: u8,
        seipd_version: u8,
        symmetric: Option<SymmetricKeyAlgorithm>,
    }

    let scenarios = [
        Scenario {
            label: "V6 primary without Features",
            recipients: vec![&v6_without_features],
            protection_mode: OpenPgpProtectionMode::SeipdV2Aead,
            pkesk_version: 6,
            seipd_version: 2,
            symmetric: Some(SymmetricKeyAlgorithm::AES128),
        },
        Scenario {
            label: "V6 primary with explicit negative Features",
            recipients: vec![&v6_explicit_negative],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            pkesk_version: 3,
            seipd_version: 1,
            symmetric: None,
        },
        Scenario {
            label: "V4 primary without Features",
            recipients: vec![&v4_without_features],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            pkesk_version: 3,
            seipd_version: 1,
            symmetric: None,
        },
        Scenario {
            label: "mixed inferred-V6 and negative-V4 recipients",
            recipients: vec![&v6_without_features, &v4_without_features],
            protection_mode: OpenPgpProtectionMode::SeipdV1Mdc,
            pkesk_version: 3,
            seipd_version: 1,
            symmetric: None,
        },
        Scenario {
            label: "V6 primary with explicit SEIPDv2 feature",
            recipients: vec![&v6_explicit],
            protection_mode: OpenPgpProtectionMode::SeipdV2Aead,
            pkesk_version: 6,
            seipd_version: 2,
            symmetric: Some(SymmetricKeyAlgorithm::AES256),
        },
    ];

    for scenario in scenarios {
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys: scenario
                    .recipients
                    .iter()
                    .map(|material| material.public_key_armored.clone())
                    .collect(),
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "features-inference.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(reference_time),
                enable_compression: Some(false),
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("encrypt {}: {error:?}", scenario.label))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {} result: {error}", scenario.label));
        assert_eq!(
            encrypted.protection_mode, scenario.protection_mode as i32,
            "{}",
            scenario.label,
        );

        let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
            .unwrap_or_else(|error| panic!("scan {} message: {error:?}", scenario.label));
        let pkesks = packets
            .packets()
            .iter()
            .filter(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
            .collect::<Vec<_>>();
        assert_eq!(
            pkesks.len(),
            scenario.recipients.len(),
            "{}",
            scenario.label
        );
        assert!(
            pkesks.iter().all(|packet| {
                packets.body(packet).first().copied() == Some(scenario.pkesk_version)
            }),
            "{}",
            scenario.label,
        );
        let protected = packets
            .packets()
            .iter()
            .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
            .unwrap_or_else(|| panic!("find {} SEIPD packet", scenario.label));
        let protected_body = packets.body(protected);
        assert_eq!(
            protected_body.first().copied(),
            Some(scenario.seipd_version),
            "{}",
            scenario.label,
        );
        if let Some(symmetric) = scenario.symmetric {
            assert_eq!(
                protected_body.get(1..3),
                Some(&[u8::from(symmetric), u8::from(AeadAlgorithm::Ocb)][..]),
                "{}",
                scenario.label,
            );
        }
        assert!(
            packets
                .packets()
                .iter()
                .all(|packet| packet.tag() != u8::from(Tag::GnupgAeadData)),
            "{}",
            scenario.label,
        );

        for recipient in scenario.recipients {
            let decrypted = OpenPgpDecryptResult::decode(
                decrypt_request(OpenPgpDecryptRequest {
                    content: encrypted.data.clone(),
                    private_keys: vec![recipient.private_key_armored.clone()],
                    verification_public_keys: Vec::new(),
                    reference_time_epoch_seconds: Some(reference_time),
                    allow_signed_only: None,
                })
                .unwrap_or_else(|error| panic!("decrypt {}: {error:?}", scenario.label))
                .as_slice(),
            )
            .unwrap_or_else(|error| panic!("decode {} plaintext: {error}", scenario.label));
            assert_eq!(decrypted.data, plaintext, "{}", scenario.label);
        }
    }
}

#[test]
fn dual_mode_recipient_prefers_standard_seipd_v2_with_v6_pkesk() {
    let material = generated_dual_aead_material();
    let reference_time = 1_800_000_000;
    let plaintext = b"dual-advertising RFC 9580 SEIPDv2 payload";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "_CONSOLE".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt request")
        .as_slice(),
    )
    .expect("decode encryption result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::SeipdV2Aead as i32
    );

    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan encrypted message");
    let pkesk = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .expect("PKESK packet");
    assert_eq!(packets.body(pkesk).first().copied(), Some(6));
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
        .expect("standard SEIPDv2 packet");
    assert_eq!(
        &packets.body(protected)[..3],
        &[
            2,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
        ]
    );
    assert!(
        packets
            .packets()
            .iter()
            .all(|packet| packet.tag() != u8::from(Tag::GnupgAeadData)),
        "dual-advertising recipients must not select reserved critical tag 20",
    );

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data.clone(),
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(reference_time),
            allow_signed_only: None,
        })
        .expect("decrypt request")
        .as_slice(),
    )
    .expect("decode decryption result");
    assert_eq!(decrypted.data, plaintext);

    let mut tampered = encrypted.data;
    *tampered.last_mut().expect("encrypted body") ^= 1;
    let error = decrypt_request(OpenPgpDecryptRequest {
        content: tampered,
        private_keys: vec![material.private_key_armored.clone()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(reference_time),
        allow_signed_only: None,
    })
    .expect_err("tampered SEIPDv2 must fail");
    assert_eq!(error, OpenPgpWriteError::AuthenticationFailed);
}

#[test]
fn gnupg_only_recipient_uses_tag_20_with_v3_pkesk() {
    let material = generated_gnupg_only_material();
    let reference_time = 1_800_000_000;
    let certificates = parse_public_key_documents(
        std::slice::from_ref(&material.public_key_armored),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse GnuPG-only recipient");
    let (_, _, support) = select_recipients(
        &certificates,
        &[],
        reference_time,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select GnuPG-only recipient");
    assert!(!support.all_allow_seipd_v2);
    assert!(support.all_allow_gnupg_ocb);

    let plaintext = b"GnuPG-only LibrePGP OCB payload";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "_CONSOLE".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt for GnuPG-only fixture")
        .as_slice(),
    )
    .expect("decode GnuPG-only encryption result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::GnupgOcb as i32,
    );

    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan GnuPG-only encrypted message");
    let pkesk = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .expect("GnuPG-only PKESK packet");
    assert_eq!(packets.body(pkesk).first().copied(), Some(3));
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::GnupgAeadData))
        .expect("GnuPG-only tag-20 packet");
    assert_eq!(
        &packets.body(protected)[..4],
        &[
            1,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
            GNUPG_AEAD_CHUNK_OCTET,
        ],
    );

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(reference_time),
            allow_signed_only: None,
        })
        .expect("decrypt GnuPG-only message")
        .as_slice(),
    )
    .expect("decode GnuPG-only plaintext");
    assert_eq!(decrypted.data, plaintext);
}

#[test]
fn current_gnupg_fixture_uses_authenticated_type34_for_tag20_ocb() {
    let public_key =
        include_bytes!("../../../../tests/fixtures/openpgp/cv25519-public.asc").to_vec();
    let private_key =
        include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc").to_vec();
    let reference_time = 1_800_000_000;
    let certificates = parse_public_key_documents(
        std::slice::from_ref(&public_key),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse current GnuPG fixture recipient");
    let (_, _, support) = select_recipients(
        &certificates,
        &[],
        reference_time,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select current GnuPG fixture recipient");
    assert!(!support.all_allow_seipd_v2);
    assert!(
        support.all_allow_gnupg_ocb,
        "the fixture authenticates feature 0x02, AES-256, and type-34 OCB",
    );

    let plaintext = b"current GnuPG fixture tag-20 OCB";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![public_key],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "gnupg-fixture.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt for current GnuPG fixture")
        .as_slice(),
    )
    .expect("decode current GnuPG fixture result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::GnupgOcb as i32,
    );

    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan current GnuPG fixture message");
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::GnupgAeadData))
        .expect("current GnuPG fixture tag-20 packet");
    assert_eq!(
        &packets.body(protected)[..4],
        &[
            1,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
            GNUPG_AEAD_CHUNK_OCTET,
        ],
    );

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data,
            private_keys: vec![private_key],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(reference_time),
            allow_signed_only: None,
        })
        .expect("decrypt current GnuPG fixture tag-20 message")
        .as_slice(),
    )
    .expect("decode current GnuPG fixture tag-20 plaintext");
    assert_eq!(decrypted.data, plaintext);
}

#[test]
fn mixed_standard_only_and_gnupg_only_recipients_fall_back_to_seipd_v1() {
    let standard = generated_v6_encryption_material();
    let gnupg_only = generated_gnupg_only_material();
    let reference_time = 1_800_000_000;
    let plaintext = b"mixed incompatible AEAD recipient capabilities";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![
                standard.public_key_armored.clone(),
                gnupg_only.public_key_armored.clone(),
            ],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "mixed-aead.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt for mixed AEAD recipients")
        .as_slice(),
    )
    .expect("decode mixed AEAD encryption result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::SeipdV1Mdc as i32,
    );

    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan mixed AEAD encrypted message");
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
        .expect("mixed-recipient SEIPD packet");
    assert_eq!(packets.body(protected).first().copied(), Some(1));
    assert!(
        packets
            .packets()
            .iter()
            .all(|packet| packet.tag() != u8::from(Tag::GnupgAeadData)),
    );

    for private_key in [
        standard.private_key_armored.clone(),
        gnupg_only.private_key_armored.clone(),
    ] {
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data.clone(),
                private_keys: vec![private_key],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(reference_time),
                allow_signed_only: None,
            })
            .expect("decrypt mixed AEAD fallback")
            .as_slice(),
        )
        .expect("decode mixed AEAD fallback plaintext");
        assert_eq!(decrypted.data, plaintext);
    }
}

#[test]
fn buffered_encryption_armor_checksum_follows_selected_mode() {
    let plaintext = b"buffered encrypted-message armor checksum policy";
    for (label, public_key, private_key, expected_mode, expected_checksum) in
        encrypted_message_armor_cases()
    {
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys: vec![public_key],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "armor.bin".to_owned(),
                armored: true,
                literal_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(1_800_000_000),
                enable_compression: Some(false),
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("encrypt buffered {label} message: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode buffered {label} result: {error}"));

        assert_eq!(encrypted.protection_mode, expected_mode as i32, "{label}");
        assert!(encrypted.data.starts_with(b"-----BEGIN PGP MESSAGE-----"));
        assert_eq!(
            armor_has_checksum(&encrypted.data),
            expected_checksum,
            "{label}"
        );

        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![private_key],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(1_800_000_000),
                allow_signed_only: None,
            })
            .unwrap_or_else(|error| panic!("decrypt buffered {label} message: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode buffered {label} plaintext: {error}"));
        assert_eq!(decrypted.data, plaintext, "{label}");
    }
}

#[test]
fn seipd_v2_explicit_suite_ignores_v1_preferences_and_authenticates() {
    let material = generated_v6_encryption_material_with_v1_cipher(SymmetricKeyAlgorithm::AES128);
    let reference_time = u64::from(Timestamp::now().as_secs());
    let certificates = parse_public_key_documents(
        std::slice::from_ref(&material.public_key_armored),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse SEIPDv2-only recipient");
    let (recipients, _, support) = select_recipients(
        &certificates,
        &[],
        reference_time,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select SEIPDv2-only recipient");
    assert_eq!(recipients.len(), 1);
    assert!(support.all_allow_seipd_v2);
    assert!(!support.all_allow_gnupg_ocb);

    let plaintext = b"independently negotiated RFC 9580 SEIPDv2 payload";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "_CONSOLE".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt request")
        .as_slice(),
    )
    .expect("decode encryption result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::SeipdV2Aead as i32
    );
    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan encrypted message");
    let pkesk = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .expect("PKESK packet");
    assert_eq!(packets.body(pkesk).first().copied(), Some(6));
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
        .expect("standard SEIPD packet");
    assert_eq!(
        &packets.body(protected)[..3],
        &[
            2,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
        ],
    );
    let secret = parse_secret_key(&material.private_key_armored).expect("parse recipient");
    Message::from_bytes(encrypted.data.as_slice())
        .expect("rPGP parses SEIPDv2 message")
        .decrypt(&Password::empty(), &secret)
        .expect("rPGP decrypts emitted SEIPDv2 message");

    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data.clone(),
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(reference_time),
            allow_signed_only: None,
        })
        .expect("decrypt request")
        .as_slice(),
    )
    .expect("decode decryption result");
    assert_eq!(decrypted.data, plaintext);
    assert!(decrypted.verification.is_none());

    let mut tampered = encrypted.data;
    *tampered.last_mut().expect("encrypted body") ^= 1;
    let error = decrypt_request(OpenPgpDecryptRequest {
        content: tampered,
        private_keys: vec![material.private_key_armored.clone()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(reference_time),
        allow_signed_only: None,
    })
    .expect_err("tampered SEIPDv2 must fail");
    assert_eq!(error, OpenPgpWriteError::AuthenticationFailed);
}

#[test]
fn shared_encryption_component_still_intersects_each_certificate_features() {
    let owner_material = generated_v4_standard_aead_material();
    let (owner, _) = SignedSecretKey::from_reader_single(Cursor::new(
        owner_material.private_key_armored.as_slice(),
    ))
    .expect("parse owner secret key");
    let shared_subkey = owner
        .secret_subkeys
        .iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| subkey.key.public_key().clone())
        .expect("find owner encryption subkey");
    let shared_fingerprint = fingerprint_hex(&shared_subkey);

    let host = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .feature_seipd_v1(true)
        .feature_seipd_v2(false)
        .primary_user_id("MDC Only <mdc-only@example.test>".to_owned())
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .passphrase(None)
        .build()
        .expect("build MDC-only host certificate")
        .generate(AwsLcRng)
        .expect("generate MDC-only host certificate");
    let binding = subkey_binding_signature(
        SigningKeyRef(&host.primary_key),
        host.primary_key.public_key(),
        &shared_subkey,
        &Password::empty(),
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        false,
        None,
    )
    .expect("bind shared encryption subkey into host certificate");
    let mut host_public = host.to_public_key();
    host_public
        .public_subkeys
        .push(pgp::composed::SignedPublicSubKey::new(
            shared_subkey,
            vec![binding],
        ));
    let host_public_key = host_public
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor MDC-only host certificate");
    let reference_time = u64::from(Timestamp::now().as_secs());

    let parsed = parse_public_key_documents(
        &[
            owner_material.public_key_armored.clone(),
            host_public_key.clone(),
        ],
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse both certificates sharing an encryption subkey");
    let candidates = all_components(&parsed);
    let policies = parsed
        .iter()
        .map(|certificate| {
            validate_certificate(
                certificate,
                &candidates,
                reference_time,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("validate certificate sharing an encryption subkey")
        })
        .collect::<Vec<_>>();
    assert!(recipient_allows_seipd_v2(&policies[0]));
    assert!(
        !policies[0].primary.allows_gnupg_ocb,
        "RFC 9580 type-39 preferences do not advertise GnuPG tag-20 support",
    );
    assert!(!recipient_allows_seipd_v2(&policies[1]));
    assert!(!policies[1].primary.allows_gnupg_ocb);
    for policy in &policies {
        assert!(policy.primary_available());
        assert!(
            policy.subkey_components().any(|component| {
                component.encryption_usable()
                    && fingerprint_hex(component.policy().key) == shared_fingerprint
            }),
            "each certificate must accept the shared encryption component",
        );
    }
    let (recipients, _, protection_support) = select_recipients(
        &parsed,
        &[],
        reference_time,
        &mut OpenPgpReadBudget::default(),
    )
    .expect("select the shared recipient component");
    assert_eq!(recipients.len(), 1);
    assert!(!protection_support.all_allow_seipd_v2);
    assert!(!protection_support.all_allow_gnupg_ocb);

    let plaintext = b"every target certificate constrains the protection mode";
    for (context, public_keys) in [
        (
            "capable certificate first",
            vec![
                owner_material.public_key_armored.clone(),
                host_public_key.clone(),
            ],
        ),
        (
            "MDC-only certificate first",
            vec![
                host_public_key.clone(),
                owner_material.public_key_armored.clone(),
            ],
        ),
    ] {
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys,
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "shared-subkey.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 1),
                reference_time_epoch_seconds: Some(reference_time),
                enable_compression: None,
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("encrypt with {context}: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode encryption result for {context}: {error}"));
        assert_eq!(
            encrypted.protection_mode,
            OpenPgpProtectionMode::SeipdV1Mdc as i32,
            "{context}",
        );

        let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
            .unwrap_or_else(|error| panic!("scan encrypted message for {context}: {error:?}"));
        assert_eq!(
            packets
                .packets()
                .iter()
                .filter(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
                .count(),
            1,
            "the shared recipient component needs only one PKESK ({context})",
        );
        let protected = packets
            .packets()
            .iter()
            .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
            .unwrap_or_else(|| panic!("find SEIPD packet for {context}"));
        assert_eq!(
            packets.body(protected).first().copied(),
            Some(1),
            "{context}",
        );
    }
}

#[test]
fn seipd_v2_uses_v6_pkesk_for_mixed_v4_and_v6_recipients() {
    let v4 = generated_dual_aead_material();
    let v6 = generated_v6_encryption_material();
    let plaintext = b"mixed V4 and V6 recipient payload";
    let reference_time = u64::from(Timestamp::now().as_secs());
    let certificates = parse_public_key_documents(
        &[v4.public_key_armored.clone(), v6.public_key_armored.clone()],
        &mut OpenPgpReadBudget::default(),
    )
    .expect("parse both recipient certificates");
    assert_eq!(certificates.len(), 2);
    let candidates = all_components(&certificates);
    for (index, certificate) in certificates.iter().enumerate() {
        let policy = validate_certificate(
            certificate,
            &candidates,
            reference_time,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("validate recipient certificate");
        assert!(policy.primary_available(), "recipient {index} primary");
        assert!(
            policy
                .subkey_components()
                .any(|component| component.encryption_usable()),
            "recipient {index} encryption subkey"
        );
    }
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![v4.public_key_armored.clone(), v6.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "_CONSOLE".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(reference_time),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt for mixed-version recipients")
        .as_slice(),
    )
    .expect("decode mixed-version encryption result");
    assert_eq!(
        encrypted.protection_mode,
        OpenPgpProtectionMode::SeipdV2Aead as i32
    );

    let packets = RawPacketStream::parse(&encrypted.data, MAX_OPENPGP_PACKETS)
        .expect("scan mixed-version encrypted message");
    let pkesks = packets
        .packets()
        .iter()
        .filter(|packet| packet.tag() == u8::from(Tag::PublicKeyEncryptedSessionKey))
        .collect::<Vec<_>>();
    assert_eq!(pkesks.len(), 2);
    assert!(
        pkesks
            .iter()
            .all(|packet| packets.body(packet).first().copied() == Some(6))
    );
    let protected = packets
        .packets()
        .iter()
        .find(|packet| packet.tag() == u8::from(Tag::SymEncryptedProtectedData))
        .expect("standard SEIPD packet");
    assert_eq!(packets.body(protected).first().copied(), Some(2));

    for private_key in [&v4.private_key_armored, &v6.private_key_armored] {
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data.clone(),
                private_keys: vec![private_key.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(reference_time),
                allow_signed_only: None,
            })
            .expect("decrypt mixed-version message")
            .as_slice(),
        )
        .expect("decode mixed-version decryption result");
        assert_eq!(decrypted.data, plaintext);
    }
}

#[test]
fn seipd_v1_mdc_roundtrip() {
    let material = generated_modern_material();
    let (certificate, _) =
        SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
            .expect("parse public certificate");
    let recipient = certificate
        .public_subkeys
        .iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key.clone()))
        .expect("encryption subkey");
    let plaintext = b"AES-256 SEIPDv1 MDC payload";
    let composed = build_composed_message(
        plaintext,
        b"_CONSOLE",
        Timestamp::from_secs(TEST_TIME as u32),
        None,
        None,
        &[],
        Some(CompressionAlgorithm::ZIP),
    )
    .expect("compose message");
    let encrypted = encrypt_composed_message(
        composed.as_slice(),
        &[recipient],
        ProtectionMode::SeipdV1Mdc,
        SymmetricKeyAlgorithm::AES256,
    )
    .expect("encrypt MDC message");
    let decrypted = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("decrypt MDC message")
        .as_slice(),
    )
    .expect("decode MDC decryption result");
    assert_eq!(decrypted.data, plaintext);
}

#[test]
fn streaming_armor_matches_independent_crc24_and_base64_kat() {
    let (sender, receiver) = mpsc::sync_channel(STREAM_CHANNEL_DEPTH);
    let mut armor = OpenPgpArmorWriter::new(OpenPgpChannelWriter::new(sender), true)
        .expect("open armor writer");
    armor
        .write_all(b"123456789")
        .expect("write RFC CRC-24 check value");
    armor.finish().expect("finish armor");
    let mut encoded = Vec::new();
    while let Ok(message) = receiver.try_recv() {
        match message {
            OpenPgpWorkerOutput::Data(bytes) => encoded.extend_from_slice(&bytes),
            OpenPgpWorkerOutput::Consumed | OpenPgpWorkerOutput::Finished(_) => {
                panic!("armor writer emitted a worker control message")
            }
        }
    }
    assert_eq!(
        encoded,
        b"-----BEGIN PGP MESSAGE-----\n\nMTIzNDU2Nzg5\n=Ic8C\n-----END PGP MESSAGE-----\n"
    );
}

#[test]
fn streaming_encryption_armor_checksum_follows_selected_mode() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"streaming encrypted-message armor checksum policy";
    for (label, public_key, private_key, expected_mode, expected_checksum) in
        encrypted_message_armor_cases()
    {
        let mut encryption = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![public_key],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "armor-stream.bin".to_owned(),
            armored: true,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(1_800_000_000),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .unwrap_or_else(|error| panic!("open streaming {label} encryption: {error:?}"));
        let mut encrypted = encryption
            .update(&plaintext[..13])
            .unwrap_or_else(|error| panic!("update streaming {label} encryption: {error:?}"));
        encrypted.extend_from_slice(
            &encryption
                .update(&plaintext[13..])
                .unwrap_or_else(|error| panic!("update streaming {label} encryption: {error:?}")),
        );
        let final_output =
            OpenPgpEncryptFinal::decode(
                encode_encrypt_final(encryption.finish().unwrap_or_else(|error| {
                    panic!("finish streaming {label} encryption: {error:?}")
                }))
                .as_slice(),
            )
            .unwrap_or_else(|error| panic!("decode streaming {label} result: {error}"));
        assert_eq!(
            final_output.protection_mode, expected_mode as i32,
            "{label}"
        );
        encrypted.extend_from_slice(&final_output.data);
        assert!(encrypted.starts_with(b"-----BEGIN PGP MESSAGE-----"));
        assert_eq!(armor_has_checksum(&encrypted), expected_checksum, "{label}");

        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted,
                private_keys: vec![private_key],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(1_800_000_000),
                allow_signed_only: None,
            })
            .unwrap_or_else(|error| panic!("decrypt streaming {label} message: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode streaming {label} plaintext: {error}"));
        assert_eq!(decrypted.data, plaintext, "{label}");
    }
}

#[test]
fn one_shot_and_byte_chunked_decryption_ignore_crc24_footer_failures_for_all_message_modes() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"RFC 9580 malformed CRC24 tolerance";

    for (mode_label, public_key, private_key, expected_mode, _) in encrypted_message_armor_cases() {
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys: vec![public_key],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "crc24.txt".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(1_800_000_000),
                enable_compression: Some(false),
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("encrypt {mode_label} CRC fixture: {error:?}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {mode_label} CRC fixture: {error}"));
        assert_eq!(
            encrypted.protection_mode, expected_mode as i32,
            "{mode_label}"
        );

        // Even SEIPDv2, whose encoder is required to omit CRC24, must not be
        // rejected by a reader solely because a checksum footer is present.
        let correct = armor_crc_test_message(&encrypted.data, BlockType::Message, true);
        let missing = replace_armor_checksum_lines(&correct, &[]);
        assert!(!armor_has_checksum(&missing), "{mode_label}");
        let variants = [
            ("correct", correct.clone()),
            ("missing", missing),
            (
                "wrong decoded value",
                replace_armor_checksum_lines(&correct, &[b"=AAAA"]),
            ),
            (
                "invalid base64 syntax",
                replace_armor_checksum_lines(&correct, &[b"=%%%?"]),
            ),
            (
                "short checksum",
                replace_armor_checksum_lines(&correct, &[b"=A"]),
            ),
            (
                "duplicated malformed checksum",
                replace_armor_checksum_lines(&correct, &[b"=AAAA", b"=not-base64"]),
            ),
            (
                "whitespace-prefixed malformed checksum",
                replace_armor_checksum_lines(&correct, &[b" \t=not-base64"]),
            ),
        ];

        for (crc_label, armored) in variants {
            let one_shot = decrypt_crc_test_message_one_shot(armored.clone(), &private_key);
            assert_eq!(one_shot.data, plaintext, "{mode_label}: {crc_label}");
            assert_eq!(
                one_shot.declared_charset.as_deref(),
                Some("ISO-8859-1"),
                "{mode_label}: {crc_label}",
            );

            // The footer suffix is delivered one byte at a time, placing a
            // chunk boundary at every possible position in all CRC variants.
            let (streamed, final_result) =
                decrypt_crc_test_message_streaming(&armored, &private_key);
            assert_eq!(streamed, plaintext, "{mode_label}: {crc_label}");
            assert_eq!(
                final_result.declared_charset.as_deref(),
                Some("ISO-8859-1"),
                "{mode_label}: {crc_label}",
            );
        }
    }
}

#[test]
fn crc24_tolerance_keeps_message_payload_headers_boundaries_and_types_strict() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: b"strict armor structure".to_vec(),
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "strict.txt".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 2),
            reference_time_epoch_seconds: Some(1_800_000_000),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt strict armor fixture")
        .as_slice(),
    )
    .expect("decode strict armor fixture");
    let valid = armor_crc_test_message(&encrypted.data, BlockType::Message, true);

    let mut malformed_payload = valid.clone();
    let payload_start = malformed_payload
        .windows(2)
        .position(|window| window == b"\n\n")
        .expect("armor header separator")
        + 2;
    malformed_payload[payload_start] = b'!';

    let misplaced_checksum =
        replace_armor_checksum_lines(&valid, &[b"=A", b"payload-after-checksum"]);
    let separated_checksum = replace_armor_checksum_lines(&valid, &[b"=A", b""]);

    let mut malformed_header = valid.clone();
    let header_end = malformed_header
        .windows(2)
        .position(|window| window == b"\n\n")
        .expect("armor header separator");
    malformed_header.splice(
        header_end + 1..header_end + 1,
        b"Malformed header\n".iter().copied(),
    );

    let mut mismatched_boundary = valid.clone();
    let footer_start = mismatched_boundary
        .windows(b"-----END PGP MESSAGE-----".len())
        .position(|window| window == b"-----END PGP MESSAGE-----")
        .expect("armor footer");
    mismatched_boundary[footer_start + b"-----END PGP MESSAG".len()] = b'X';

    let wrong_block_type = armor_crc_test_message(&encrypted.data, BlockType::Signature, true);
    let mut trailing_packet_framing = encrypted.data.clone();
    trailing_packet_framing.extend_from_slice(&[0xcb, 0]);
    let trailing_packet_framing =
        armor_crc_test_message(&trailing_packet_framing, BlockType::Message, true);
    for (label, malformed) in [
        ("message base64", malformed_payload),
        ("misplaced checksum", misplaced_checksum),
        ("separated checksum", separated_checksum),
        ("armor header", malformed_header),
        ("mismatched boundary", mismatched_boundary),
        ("wrong block type", wrong_block_type),
        ("trailing packet framing", trailing_packet_framing),
    ] {
        assert!(
            decrypt_request(OpenPgpDecryptRequest {
                content: malformed.clone(),
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(1_800_000_000),
                allow_signed_only: None,
            })
            .is_err(),
            "must reject malformed {label}",
        );
        assert!(
            crc_test_streaming_is_rejected(&malformed, &material.private_key_armored),
            "streaming must reject malformed {label}",
        );
    }
}

#[test]
fn decryption_ignores_unknown_noncritical_packets_between_esk_and_encrypted_data() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let plaintext = b"RFC 9580 unknown noncritical packet";
    let encrypted = OpenPgpEncryptResult::decode(
        decryptable_encrypted_message(plaintext, &material.public_key_armored)
            .expect("encrypt noncritical-packet fixture")
            .as_slice(),
    )
    .expect("decode noncritical-packet encryption result")
    .data;

    for tag in [40_u8, 60_u8] {
        let unknown = fixed_test_packet(tag, b"opaque future packet body");
        let binary = insert_packet_before_encrypted_data(&encrypted, &unknown);
        let armored = armor_crc_test_message(&binary, BlockType::Message, true);
        for (format, content) in [("binary", binary), ("armored", armored)] {
            let one_shot =
                decrypt_envelope_one_shot(content.clone(), material.private_key_armored.as_slice())
                    .unwrap_or_else(|error| panic!("decrypt {format} tag {tag}: {error:?}"));
            assert_eq!(one_shot.data, plaintext, "one-shot {format} tag {tag}");

            for (streaming, chunk_size) in [("byte", 1_usize), ("chunked", 17_usize)] {
                let streamed = decrypt_envelope_streaming(
                    &content,
                    material.private_key_armored.as_slice(),
                    chunk_size,
                )
                .unwrap_or_else(|error| {
                    panic!("decrypt {streaming}-streamed {format} tag {tag}: {error:?}")
                });
                assert_eq!(
                    streamed, plaintext,
                    "{streaming}-streamed {format} tag {tag}",
                );
            }
        }
    }
}

#[test]
fn decryption_rejects_unknown_critical_and_known_misplaced_packets() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let encrypted = OpenPgpEncryptResult::decode(
        decryptable_encrypted_message(
            b"strict encrypted-message packet grammar",
            &material.public_key_armored,
        )
        .expect("encrypt strict packet-grammar fixture")
        .as_slice(),
    )
    .expect("decode strict packet-grammar encryption result")
    .data;

    for (label, packet) in [
        (
            "unknown critical tag 22",
            fixed_test_packet(22, b"critical"),
        ),
        ("misplaced literal data", fixed_test_packet(11, b"")),
    ] {
        let binary = insert_packet_before_encrypted_data(&encrypted, &packet);
        let armored = armor_crc_test_message(&binary, BlockType::Message, true);
        for (format, content) in [("binary", binary), ("armored", armored)] {
            assert!(
                decrypt_envelope_one_shot(
                    content.clone(),
                    material.private_key_armored.as_slice(),
                )
                .is_err(),
                "one-shot must reject {format} {label}",
            );
            assert!(
                decrypt_envelope_streaming(&content, material.private_key_armored.as_slice(), 1,)
                    .is_err(),
                "streaming must reject {format} {label}",
            );
        }
    }

    let mut oversized_packet = vec![Tag::from(40).encode(), 0xff];
    oversized_packet.extend_from_slice(
        &u32::try_from(MAX_CONTROL_ENVELOPE_BYTES + 1)
            .expect("control envelope limit fits u32")
            .to_be_bytes(),
    );
    let binary = insert_packet_before_encrypted_data(&encrypted, &oversized_packet);
    let armored = armor_crc_test_message(&binary, BlockType::Message, true);
    for (format, content) in [("binary", binary), ("armored", armored)] {
        assert_eq!(
            decrypt_envelope_one_shot(content.clone(), material.private_key_armored.as_slice(),),
            Err(OpenPgpWriteError::ResourceLimit),
            "one-shot {format} oversized unknown packet",
        );
        assert_eq!(
            decrypt_envelope_streaming(&content, material.private_key_armored.as_slice(), 17),
            Err(OpenPgpWriteError::ResourceLimit),
            "streaming {format} oversized unknown packet",
        );
    }
}

#[test]
fn unknown_noncritical_envelope_framing_and_resource_limits_remain_strict() {
    for esk_tag in [
        Tag::PublicKeyEncryptedSessionKey,
        Tag::SymKeyEncryptedSessionKey,
    ] {
        for unknown_tag in [40_u8, 60_u8] {
            let input = [
                esk_tag.encode(),
                0,
                Tag::from(unknown_tag).encode(),
                1,
                0xa5,
                Tag::SymEncryptedProtectedData.encode(),
                0,
            ];
            let exceeded = Arc::new(AtomicBool::new(false));
            let mut output = Vec::new();
            MessageEnvelopeReader::new(Cursor::new(input), exceeded.clone())
                .read_to_end(&mut output)
                .expect("ignore fixed-length unknown noncritical packet");
            assert_eq!(
                output,
                [
                    esk_tag.encode(),
                    0,
                    Tag::SymEncryptedProtectedData.encode(),
                    0,
                ],
            );
            assert!(!exceeded.load(Ordering::Acquire));
        }
    }

    let mut partial = vec![
        Tag::SymKeyEncryptedSessionKey.encode(),
        0,
        Tag::from(40).encode(),
        0xe9,
    ];
    partial.extend(std::iter::repeat_n(0xa5, 512));
    partial.extend_from_slice(&[3, 1, 2, 3, Tag::SymEncryptedProtectedData.encode(), 0]);
    let exceeded = Arc::new(AtomicBool::new(false));
    let mut output = Vec::new();
    MessageEnvelopeReader::new(Cursor::new(partial), exceeded.clone())
        .read_to_end(&mut output)
        .expect("ignore partial-length unknown noncritical packet");
    assert_eq!(
        output,
        [
            Tag::SymKeyEncryptedSessionKey.encode(),
            0,
            Tag::SymEncryptedProtectedData.encode(),
            0,
        ],
    );
    assert!(!exceeded.load(Ordering::Acquire));

    let synthetic_esk = [Tag::PublicKeyEncryptedSessionKey.encode(), 0];

    let mut truncated = synthetic_esk.to_vec();
    truncated.extend_from_slice(&[0xe8, 5, 0]);
    let exceeded = Arc::new(AtomicBool::new(false));
    let error = MessageEnvelopeReader::new(Cursor::new(truncated), exceeded.clone())
        .read_to_end(&mut Vec::new())
        .expect_err("truncated unknown packet must fail");
    assert_eq!(error.kind(), std::io::ErrorKind::UnexpectedEof);
    assert!(!exceeded.load(Ordering::Acquire));

    let mut short_partial = synthetic_esk.to_vec();
    short_partial.extend_from_slice(&[0xe8, 0xe0, 0]);
    let exceeded = Arc::new(AtomicBool::new(false));
    let error = MessageEnvelopeReader::new(Cursor::new(short_partial), exceeded.clone())
        .read_to_end(&mut Vec::new())
        .expect_err("short initial partial body must fail");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(!exceeded.load(Ordering::Acquire));

    let mut oversized = synthetic_esk.to_vec();
    oversized.push(0xe8);
    oversized.push(0xff);
    oversized.extend_from_slice(
        &u32::try_from(MAX_CONTROL_ENVELOPE_BYTES + 1)
            .expect("control envelope limit fits u32")
            .to_be_bytes(),
    );
    let exceeded = Arc::new(AtomicBool::new(false));
    let error = MessageEnvelopeReader::new(Cursor::new(oversized), exceeded.clone())
        .read_to_end(&mut Vec::new())
        .expect_err("oversized unknown packet must fail before reading its body");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(exceeded.load(Ordering::Acquire));

    let mut too_many_packets = synthetic_esk.to_vec();
    for _ in 0..MAX_OPENPGP_PACKETS {
        too_many_packets.extend_from_slice(&[0xe8, 0]);
    }
    let exceeded = Arc::new(AtomicBool::new(false));
    let error = MessageEnvelopeReader::new(Cursor::new(too_many_packets), exceeded.clone())
        .read_to_end(&mut Vec::new())
        .expect_err("unknown packet count must be bounded");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(exceeded.load(Ordering::Acquire));

    let mut too_many_chunks = synthetic_esk.to_vec();
    too_many_chunks.extend_from_slice(&[0xe8, 0xe9]);
    too_many_chunks.extend(std::iter::repeat_n(0_u8, 512));
    for _ in 0..MAX_PARTIAL_BODY_CHUNKS {
        too_many_chunks.extend_from_slice(&[0xe0, 0]);
    }
    let exceeded = Arc::new(AtomicBool::new(false));
    let error = MessageEnvelopeReader::new(Cursor::new(too_many_chunks), exceeded.clone())
        .read_to_end(&mut Vec::new())
        .expect_err("unknown partial chunk count must be bounded");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(exceeded.load(Ordering::Acquire));
}

#[test]
fn streaming_clear_sign_matches_one_shot_across_utf8_and_line_boundaries() {
    let material = generated_modern_material();
    let mut content = Vec::new();
    for index in 0..8_192 {
        content.extend_from_slice(
            format!("- unicode λ line {index}\t \r\nplain line {index}\n").as_bytes(),
        );
    }
    content.extend_from_slice(b"final trailing whitespace\t ");
    let expected = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: content.clone(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: Some(TEST_TIME + 5),
        reference_time_epoch_seconds: Some(TEST_TIME + 5),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("one-shot clear signature");
    let mut session = open_clear_sign_session(OpenPgpClearSignStreamOpenRequest {
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        signature_time_epoch_seconds: Some(TEST_TIME + 5),
        reference_time_epoch_seconds: Some(TEST_TIME + 5),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open clear-sign stream");
    let mut actual = Vec::new();
    for_odd_chunks(&content, &[1, 2, 7, 31], |chunk| {
        actual.extend_from_slice(&session.update(chunk).expect("clear-sign update"));
    });
    actual.extend_from_slice(&session.finish().expect("finish clear-sign stream"));

    assert_eq!(actual, expected);
    assert!(actual.windows(4).any(|window| window == b"- - "));
}

#[test]
fn clear_sign_pending_whitespace_limit_is_inclusive_and_atomic() {
    let material = generated_modern_material();
    let request = || OpenPgpClearSignStreamOpenRequest {
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        signature_time_epoch_seconds: Some(TEST_TIME + 5),
        reference_time_epoch_seconds: Some(TEST_TIME + 5),
        candidate_revocation_keys: Vec::new(),
    };
    let whitespace = (0..MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES)
        .map(|index| if index % 2 == 0 { b' ' } else { b'\t' })
        .collect::<Vec<_>>();

    let mut session = open_clear_sign_session(request()).expect("open clear-sign stream");
    let mut signed = session
        .update(&whitespace[..whitespace.len() - 1])
        .expect("accept whitespace below the limit");
    let pending_before = session.pending_whitespace.to_vec();
    let utf8_tail_before = session.utf8_tail.to_vec();
    let started_before = session.started;
    let line_start_before = session.line_start;
    let canonical_needs_break_before = session.canonical_needs_break;
    let previous_input_was_cr_before = session.previous_input_was_cr;
    let output_ended_with_line_break_before = session.output_ended_with_line_break;

    assert_eq!(
        session.update(&[whitespace[whitespace.len() - 1], b' ']),
        Err(OpenPgpWriteError::ResourceLimit),
    );
    assert_eq!(session.pending_whitespace.as_slice(), pending_before);
    assert_eq!(session.utf8_tail.as_slice(), utf8_tail_before);
    assert_eq!(session.started, started_before);
    assert_eq!(session.line_start, line_start_before);
    assert_eq!(session.canonical_needs_break, canonical_needs_break_before);
    assert_eq!(session.previous_input_was_cr, previous_input_was_cr_before);
    assert_eq!(
        session.output_ended_with_line_break,
        output_ended_with_line_break_before
    );

    signed.extend_from_slice(
        &session
            .update(&[whitespace[whitespace.len() - 1], b'\n'])
            .expect("recover with an exactly-at-limit run"),
    );
    signed.extend_from_slice(
        &session
            .finish()
            .expect("finish recovered clear-sign stream"),
    );
    let verification = OpenPgpVerification::decode(
        crate::openpgp::adapter::verify(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: signed,
            signature: Vec::new(),
            public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 5),
        })
        .expect("verify recovered clear signature")
        .as_slice(),
    )
    .expect("decode recovered verification");
    assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);

    let mixed_content = b"mixed \t \t  whitespace";
    let expected = sign_request(OpenPgpSignRequest {
        kind: OpenPgpSignKind::ClearText as i32,
        content: mixed_content.to_vec(),
        private_key: material.private_key_armored.clone(),
        preferred_fingerprint: String::new(),
        armored: true,
        signature_time_epoch_seconds: Some(TEST_TIME + 5),
        reference_time_epoch_seconds: Some(TEST_TIME + 5),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("one-shot mixed-whitespace clear signature");
    let mut mixed =
        open_clear_sign_session(request()).expect("open mixed-whitespace clear-sign stream");
    let mut actual = mixed.update(b"mixed \t ").expect("buffer mixed whitespace");
    actual.extend_from_slice(
        &mixed
            .update(b"\t  whitespace")
            .expect("flush mixed whitespace"),
    );
    actual.extend_from_slice(&mixed.finish().expect("finish mixed-whitespace stream"));
    assert_eq!(actual, expected);

    let mut fresh = open_clear_sign_session(request()).expect("open fresh clear-sign stream");
    let too_long = vec![b' '; MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES + 1];
    assert_eq!(
        fresh.update(&too_long),
        Err(OpenPgpWriteError::ResourceLimit),
    );
    assert!(!fresh.started);
    assert!(fresh.pending_whitespace.is_empty());
    assert!(fresh.utf8_tail.is_empty());

    let mut reset = open_clear_sign_session(request()).expect("open reset clear-sign stream");
    let _ = reset
        .update(&whitespace)
        .expect("accept an exactly-at-limit run");
    assert_eq!(
        reset.pending_whitespace.len(),
        MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES
    );
    let _ = reset.update(b"\n").expect("line break resets the run");
    assert!(reset.pending_whitespace.is_empty());
    let _ = reset
        .update(&whitespace)
        .expect("accept another run after a line break");
    assert_eq!(
        reset.pending_whitespace.len(),
        MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES
    );

    let mut content_reset =
        open_clear_sign_session(request()).expect("open content-reset clear-sign stream");
    let _ = content_reset
        .update(&whitespace)
        .expect("accept a run before content");
    let _ = content_reset
        .update(b"x")
        .expect("non-whitespace resets the run");
    assert!(content_reset.pending_whitespace.is_empty());
}

#[test]
fn allow_signed_only_rejects_unsigned_literal_messages_without_streaming_plaintext() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let plaintext = b"unsigned OpenPGP literal payload";

    for enable_compression in [false, true] {
        let unsigned = build_composed_message(
            plaintext,
            b"unsigned.txt",
            Timestamp::from_secs((TEST_TIME + 6) as u32),
            None,
            None,
            &[],
            enable_compression.then_some(CompressionAlgorithm::ZIP),
        )
        .expect("compose unsigned literal message");

        assert_eq!(
            decrypt_request(OpenPgpDecryptRequest {
                content: unsigned.to_vec(),
                private_keys: Vec::new(),
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 6),
                allow_signed_only: Some(true),
            }),
            Err(OpenPgpWriteError::InvalidArgument),
        );

        let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
            private_keys: Vec::new(),
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 6),
            allow_signed_only: Some(true),
        })
        .expect("open signed-only decryption stream");
        let mut provisional = Vec::new();
        let mut update_failure = None;
        for chunk in unsigned.chunks(7) {
            match session.update(chunk) {
                Ok(data) => provisional.extend_from_slice(&data),
                Err(error) => {
                    update_failure = Some(error);
                    break;
                }
            }
        }
        let error = if let Some(error) = update_failure {
            error
        } else {
            session
                .finish()
                .expect_err("unsigned literal stream must fail")
        };

        assert_eq!(error, OpenPgpWriteError::InvalidArgument);
        assert!(provisional.is_empty());
    }
}

#[test]
fn signed_only_messages_require_valid_verification_and_preserve_literal_metadata() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let plaintext = b"signed-only OpenPGP payload";
    let secret = parse_private_certificate(&material.private_key_armored)
        .expect("parse generated secret key");
    let signing_packet = select_signing_packet(&secret, &material.fingerprint, TEST_TIME + 6, &[])
        .expect("select signing packet");

    for enable_compression in [false, true] {
        let signed = build_composed_message(
            plaintext,
            b"signed-only.txt",
            Timestamp::from_secs((TEST_TIME + 6) as u32),
            Some(Timestamp::from_secs((TEST_TIME + 6) as u32)),
            Some(signing_packet),
            &[],
            enable_compression.then_some(CompressionAlgorithm::ZIP),
        )
        .expect("compose signed-only message");
        let result = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: signed.to_vec(),
                private_keys: Vec::new(),
                verification_public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 6),
                allow_signed_only: Some(true),
            })
            .expect("decode signed-only message")
            .as_slice(),
        )
        .expect("decode signed-only result");

        assert_eq!(result.data, plaintext);
        assert!(!result.encrypted);
        assert!(result.decryption_key_fingerprint.is_none());
        assert!(result.warnings.is_empty());
        assert_eq!(
            result.verification.as_ref().expect("verification").status,
            OpenPgpVerificationStatus::Valid as i32,
        );
        let metadata = result.metadata.as_ref().expect("literal metadata");
        assert_eq!(metadata.file_name, b"signed-only.txt");
        assert_eq!(metadata.modification_time_epoch_seconds, TEST_TIME + 6);
        assert_eq!(metadata.original_size, plaintext.len() as u64);

        assert_eq!(
            decrypt_request(OpenPgpDecryptRequest {
                content: signed.to_vec(),
                private_keys: Vec::new(),
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 6),
                allow_signed_only: Some(true),
            }),
            Err(OpenPgpWriteError::InvalidArgument),
        );

        let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
            private_keys: Vec::new(),
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 6),
            allow_signed_only: Some(true),
        })
        .expect("open missing-key signed-only decryption stream");
        let mut provisional = Vec::new();
        for chunk in signed.as_slice().chunks(7) {
            provisional.extend_from_slice(
                &session
                    .update(chunk)
                    .expect("stream missing-key signed-only message"),
            );
        }
        assert!(matches!(
            session.finish(),
            Err(OpenPgpWriteError::InvalidArgument)
        ));
        assert!(plaintext.starts_with(&provisional));
    }
}

#[test]
fn encrypted_signed_messages_preserve_missing_public_key_diagnostics() {
    let material = generated_modern_material();
    let plaintext = b"encrypted payload with an unavailable verification key";
    let secret = parse_private_certificate(&material.private_key_armored)
        .expect("parse generated secret key");
    let signing_packet = select_signing_packet(&secret, &material.fingerprint, TEST_TIME + 6, &[])
        .expect("select signing packet");
    let (public, _) =
        SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.as_slice()))
            .expect("parse generated public key");
    let recipient = public
        .public_subkeys
        .into_iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key))
        .expect("select encryption subkey");
    let signed = build_composed_message(
        plaintext,
        b"encrypted-signed.txt",
        Timestamp::from_secs((TEST_TIME + 6) as u32),
        Some(Timestamp::from_secs((TEST_TIME + 6) as u32)),
        Some(signing_packet),
        &[],
        None,
    )
    .expect("compose signed message");
    let encrypted = encrypt_composed_message(
        &signed,
        std::slice::from_ref(&recipient),
        ProtectionMode::SeipdV1Mdc,
        SymmetricKeyAlgorithm::AES256,
    )
    .expect("encrypt signed message");

    let result = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 6),
            allow_signed_only: None,
        })
        .expect("decrypt signed message without verification key")
        .as_slice(),
    )
    .expect("decode missing-key diagnostic");

    assert_eq!(result.data, plaintext);
    assert!(result.encrypted);
    assert_eq!(
        result.verification.as_ref().expect("verification").status,
        OpenPgpVerificationStatus::MissingPublicKey as i32,
    );
}

#[test]
fn decryption_results_identify_the_successful_private_component() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let unrelated = generated_modern_material();
    let recipient = generated_modern_material();
    let (recipient_secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(recipient.private_key_armored.as_slice()))
            .expect("parse recipient secret key");
    let expected_fingerprint = fingerprint_hex(&recipient_secret.secret_subkeys[1].key);
    let plaintext = b"attribute the actual recipient";
    let encrypted = OpenPgpEncryptResult::decode(
        encrypt_request(OpenPgpEncryptRequest {
            content: plaintext.to_vec(),
            public_keys: vec![recipient.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "attribution.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("encrypt for recipient")
        .as_slice(),
    )
    .expect("decode encrypted message");

    let one_shot = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: encrypted.data.clone(),
            private_keys: vec![
                unrelated.private_key_armored.clone(),
                recipient.private_key_armored.clone(),
            ],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("decrypt with recipient after unrelated candidate")
        .as_slice(),
    )
    .expect("decode one-shot decryption result");
    assert_eq!(one_shot.data, plaintext);
    assert_eq!(
        one_shot.decryption_key_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );

    let mut rng = AwsLcRng;
    let mut anonymous_builder = MessageBuilder::from_bytes("anonymous.bin", plaintext.as_slice())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    anonymous_builder
        .encrypt_to_key_anonymous(&mut rng, &recipient_secret.secret_subkeys[1].public_key())
        .expect("encrypt for hidden recipient");
    let anonymous_encrypted = anonymous_builder
        .to_vec(rng)
        .expect("serialize hidden-recipient message");
    let hidden_recipient = OpenPgpDecryptResult::decode(
        decrypt_request(OpenPgpDecryptRequest {
            content: anonymous_encrypted,
            private_keys: vec![
                unrelated.private_key_armored.clone(),
                recipient.private_key_armored.clone(),
            ],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("decrypt hidden-recipient message")
        .as_slice(),
    )
    .expect("decode hidden-recipient result");
    assert_eq!(hidden_recipient.data, plaintext);
    assert_eq!(
        hidden_recipient.decryption_key_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );

    let mut streaming = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![
            recipient.private_key_armored.clone(),
            unrelated.private_key_armored.clone(),
        ],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME),
        allow_signed_only: None,
    })
    .expect("open streaming decryption");
    let mut streamed_plaintext = Vec::new();
    for chunk in encrypted.data.chunks(17) {
        streamed_plaintext
            .extend_from_slice(&streaming.update(chunk).expect("stream decryption update"));
    }
    let final_result = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(streaming.finish().expect("finish streaming decryption")).as_slice(),
    )
    .expect("decode streaming decryption result");
    streamed_plaintext.extend_from_slice(&final_result.data);
    assert_eq!(streamed_plaintext, plaintext);
    assert_eq!(
        final_result.decryption_key_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );
}

#[test]
fn streaming_encryption_without_compression_preserves_metadata() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let plaintext = vec![0x5a_u8; 256 * 1024];
    let mut encryption = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![material.public_key_armored.clone()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "uncompressed.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME + 7),
        reference_time_epoch_seconds: Some(TEST_TIME + 7),
        enable_compression: Some(false),
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open uncompressed encryption stream");
    let mut encrypted = Vec::new();
    for chunk in plaintext.chunks(7_919) {
        encrypted.extend_from_slice(&encryption.update(chunk).expect("encrypt update"));
    }
    let final_output = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(encryption.finish().expect("finish encryption")).as_slice(),
    )
    .expect("decode encryption final");
    encrypted.extend_from_slice(&final_output.data);

    let mut decryption = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![material.private_key_armored.clone()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME + 7),
        allow_signed_only: Some(false),
    })
    .expect("open decryption stream");
    let mut decrypted = Vec::new();
    for chunk in encrypted.chunks(8_111) {
        decrypted.extend_from_slice(&decryption.update(chunk).expect("decrypt update"));
    }
    let final_output = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(decryption.finish().expect("finish decryption")).as_slice(),
    )
    .expect("decode decryption final");
    decrypted.extend_from_slice(&final_output.data);

    assert_eq!(decrypted, plaintext);
    assert!(final_output.encrypted);
    let metadata = final_output.metadata.as_ref().expect("literal metadata");
    assert_eq!(metadata.file_name, b"uncompressed.bin");
    assert_eq!(metadata.modification_time_epoch_seconds, TEST_TIME + 7);
    assert_eq!(metadata.original_size, plaintext.len() as u64);
}

#[test]
fn literal_metadata_defaults_roundtrip_across_buffering_protection_and_compression() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let modern = generated_dual_aead_material();
    let mdc_public = include_bytes!("../../../../tests/fixtures/openpgp/mdc-public.asc");
    let mdc_private = include_bytes!("../../../../tests/fixtures/openpgp/mdc-secret.asc");
    let plaintext = b"privacy-preserving literal metadata defaults";

    assert_eq!(resolve_literal_time(None), resolve_literal_time(Some(0)));
    let mut omitted_packet = Vec::new();
    write_literal_packet(
        &mut omitted_packet,
        plaintext,
        b"",
        resolve_literal_time(None),
    )
    .expect("write omitted-time literal packet");
    let mut explicit_zero_packet = Vec::new();
    write_literal_packet(
        &mut explicit_zero_packet,
        plaintext,
        b"",
        resolve_literal_time(Some(0)),
    )
    .expect("write explicit-zero literal packet");
    assert_eq!(omitted_packet, explicit_zero_packet);

    for (label, public_key, private_key, expected_mode) in [
        (
            "SEIPDv2",
            modern.public_key_armored.as_slice(),
            modern.private_key_armored.as_slice(),
            OpenPgpProtectionMode::SeipdV2Aead,
        ),
        (
            "MDC",
            mdc_public.as_slice(),
            mdc_private.as_slice(),
            OpenPgpProtectionMode::SeipdV1Mdc,
        ),
    ] {
        for streaming in [false, true] {
            for enable_compression in [false, true] {
                for literal_time in [None, Some(0)] {
                    let (encrypted, mode) = encrypt_literal_metadata_case(
                        public_key,
                        plaintext,
                        "",
                        literal_time,
                        1_800_000_000,
                        enable_compression,
                        streaming,
                    );
                    assert_eq!(
                        mode, expected_mode,
                        "{label}, streaming={streaming}, literal_time={literal_time:?}",
                    );
                    let decrypted = decrypt_literal_metadata_case(
                        encrypted.clone(),
                        private_key,
                        None,
                        1_800_000_000,
                    );
                    assert_eq!(
                        decrypted.data, plaintext,
                        "{label}, streaming={streaming}, compression={enable_compression}, literal_time={literal_time:?}",
                    );
                    let metadata = decrypted.metadata.as_ref().expect("literal metadata");
                    assert!(
                        metadata.file_name.is_empty(),
                        "{label}, streaming={streaming}, literal_time={literal_time:?}",
                    );
                    assert_eq!(
                        metadata.modification_time_epoch_seconds, 0,
                        "{label}, streaming={streaming}, literal_time={literal_time:?}",
                    );
                    assert_eq!(metadata.original_size, plaintext.len() as u64);

                    if !enable_compression && literal_time.is_none() {
                        let mut tampered = encrypted;
                        *tampered.last_mut().expect("encrypted authentication tag") ^= 1;
                        assert_eq!(
                            decrypt_request(OpenPgpDecryptRequest {
                                content: tampered,
                                private_keys: vec![private_key.to_vec()],
                                verification_public_keys: Vec::new(),
                                reference_time_epoch_seconds: Some(1_800_000_000),
                                allow_signed_only: None,
                            }),
                            Err(OpenPgpWriteError::AuthenticationFailed),
                            "tampered {label} message, streaming={streaming}",
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn literal_metadata_boundaries_preserve_explicit_values_and_reject_overflow() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let plaintext = b"literal metadata boundary payload";
    let max_file_name = "a".repeat(usize::from(u8::MAX));

    for (file_name, literal_time) in [
        ("explicit.bin", TEST_TIME + 7),
        (max_file_name.as_str(), u64::from(u32::MAX)),
    ] {
        let (encrypted, _) = encrypt_literal_metadata_case(
            &material.public_key_armored,
            plaintext,
            file_name,
            Some(literal_time),
            TEST_TIME + 8,
            false,
            false,
        );
        let decrypted = decrypt_literal_metadata_case(
            encrypted,
            &material.private_key_armored,
            None,
            TEST_TIME + 8,
        );
        let metadata = decrypted.metadata.as_ref().expect("literal metadata");
        assert_eq!(metadata.file_name, file_name.as_bytes());
        assert_eq!(metadata.modification_time_epoch_seconds, literal_time);
    }

    let oversized_file_name = "a".repeat(usize::from(u8::MAX) + 1);
    let invalid_request = |file_name: String, literal_time_epoch_seconds| OpenPgpEncryptRequest {
        content: plaintext.to_vec(),
        public_keys: vec![material.public_key_armored.clone()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name,
        armored: false,
        literal_time_epoch_seconds,
        reference_time_epoch_seconds: Some(TEST_TIME + 8),
        enable_compression: Some(false),
        candidate_revocation_keys: Vec::new(),
    };
    assert_eq!(
        encrypt_request(invalid_request(oversized_file_name.clone(), Some(0))),
        Err(OpenPgpWriteError::InvalidArgument),
    );
    assert_eq!(
        encrypt_request(invalid_request(
            String::new(),
            Some(u64::from(u32::MAX) + 1),
        )),
        Err(OpenPgpWriteError::InvalidArgument),
    );
    assert_eq!(
        open_encryption_session(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: oversized_file_name,
            armored: false,
            literal_time_epoch_seconds: Some(0),
            reference_time_epoch_seconds: Some(TEST_TIME + 8),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .map(|_| ()),
        Err(OpenPgpWriteError::InvalidArgument),
    );
    assert_eq!(
        open_encryption_session(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: String::new(),
            armored: false,
            literal_time_epoch_seconds: Some(u64::from(u32::MAX) + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 8),
            enable_compression: Some(false),
            candidate_revocation_keys: Vec::new(),
        })
        .map(|_| ()),
        Err(OpenPgpWriteError::InvalidArgument),
    );
}

#[test]
fn signed_encryption_uses_an_independent_nonzero_signature_time() {
    let material = generated_modern_material();
    let plaintext = b"literal time and signature creation time are independent";
    let literal_time = Timestamp::from_secs(0);
    let signature_time = Timestamp::from_secs((TEST_TIME + 5) as u32);
    let secret = parse_private_certificate(&material.private_key_armored)
        .expect("parse signing certificate");
    let signing_packet =
        select_signing_packet(&secret, "", TEST_TIME + 6, &[]).expect("select signing packet");
    let signer: &dyn SigningKey = match signing_packet {
        SecretPacketRef::Primary(key) => key,
        SecretPacketRef::Subkey(key) => key,
    };
    let (certificate, _) =
        SignedPublicKey::from_reader_single(Cursor::new(&material.public_key_armored))
            .expect("parse encryption certificate");
    let recipient = certificate
        .public_subkeys
        .iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key.clone()))
        .expect("encryption subkey");

    assert_eq!(
        resolve_signature_time(false, || panic!("unsigned path read the signing clock")),
        None,
    );
    assert_eq!(
        resolve_signature_time(true, || signature_time),
        Some(signature_time),
    );

    for streaming_composition in [false, true] {
        let composed = if streaming_composition {
            let mut reader = SignedLiteralReader::new(
                Cursor::new(plaintext),
                b"",
                literal_time,
                Some(signature_time),
                Some(signer),
                &[],
            )
            .expect("create signed literal stream");
            let mut output = Zeroizing::new(Vec::new());
            reader
                .read_to_end(&mut output)
                .expect("read signed literal stream");
            output
        } else {
            build_composed_message(
                plaintext,
                b"",
                literal_time,
                Some(signature_time),
                Some(signing_packet),
                &[],
                None,
            )
            .expect("compose signed literal message")
        };
        let mode = if streaming_composition {
            ProtectionMode::SeipdV2Aead
        } else {
            ProtectionMode::SeipdV1Mdc
        };
        let encrypted = encrypt_composed_message(
            &composed,
            std::slice::from_ref(&recipient),
            mode,
            SymmetricKeyAlgorithm::AES256,
        )
        .expect("encrypt signed literal message");
        let decrypted = decrypt_literal_metadata_case(
            encrypted,
            &material.private_key_armored,
            Some(&material.public_key_armored),
            TEST_TIME + 6,
        );
        assert_eq!(decrypted.data, plaintext);
        let metadata = decrypted.metadata.as_ref().expect("literal metadata");
        assert!(metadata.file_name.is_empty());
        assert_eq!(metadata.modification_time_epoch_seconds, 0);
        let verification = decrypted
            .verification
            .as_ref()
            .expect("inline signature verification");
        assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
        assert_eq!(
            verification.created_at_epoch_seconds,
            Some(u64::from(signature_time.as_secs())),
        );
        assert_ne!(verification.created_at_epoch_seconds, Some(0));
    }
}

#[test]
fn streaming_seipd_v1_withholds_plaintext_until_mdc_authentication() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let public_key = include_bytes!("../../../../tests/fixtures/openpgp/mdc-public.asc").to_vec();
    let private_key = include_bytes!("../../../../tests/fixtures/openpgp/mdc-secret.asc").to_vec();
    let mut state = 0xa341_316c_u32;
    let plaintext = (0..256 * 1024)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            state as u8
        })
        .collect::<Vec<_>>();
    let mut encryption = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![public_key],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "mdc-stream.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME),
        reference_time_epoch_seconds: Some(1_800_000_000),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open MDC encryption stream");
    let mut encrypted = Vec::new();
    for_odd_chunks(&plaintext, &[1, 7, 31], |chunk| {
        encrypted.extend_from_slice(&encryption.update(chunk).expect("MDC encrypt update"));
    });
    let encrypted_final = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(encryption.finish().expect("finish MDC encryption")).as_slice(),
    )
    .expect("decode MDC encryption final");
    assert_eq!(
        encrypted_final.protection_mode,
        OpenPgpProtectionMode::SeipdV1Mdc as i32
    );
    encrypted.extend_from_slice(&encrypted_final.data);

    let decrypt = |ciphertext: &[u8]| {
        let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![private_key.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(1_800_000_000),
            allow_signed_only: None,
        })
        .expect("open MDC decryption stream");
        for_odd_chunks(ciphertext, &[31, 7, 1], |chunk| {
            assert!(
                session
                    .update(chunk)
                    .expect("consume unauthenticated MDC ciphertext")
                    .is_empty(),
                "SEIPDv1 plaintext must remain withheld before MDC authentication",
            );
        });
        session
    };
    let session = decrypt(&encrypted);
    let final_output = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(session.finish().expect("authenticate MDC stream")).as_slice(),
    )
    .expect("decode MDC final");
    assert_eq!(final_output.data.as_slice(), plaintext.as_slice());

    let truncated_session = decrypt(&encrypted[..encrypted.len() - 1]);
    assert_eq!(
        truncated_session
            .finish()
            .expect_err("truncated MDC must fail finalization"),
        OpenPgpWriteError::AuthenticationFailed
    );
}

#[test]
fn streaming_dual_mode_seipd_v2_armor_roundtrip_preserves_large_compressible_plaintext() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_dual_aead_material();
    let signer = generated_modern_material();
    let mut plaintext = Zeroizing::new(vec![0_u8; 2 * 1024 * 1024]);
    for (index, byte) in plaintext.iter_mut().enumerate().step_by(4096) {
        *byte = (index / 4096) as u8;
    }

    let mut encryption = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![material.public_key_armored.clone()],
        signing_private_key: Some(signer.private_key_armored.clone()),
        preferred_signing_fingerprint: String::new(),
        file_name: "large.bin".to_owned(),
        armored: true,
        literal_time_epoch_seconds: Some(TEST_TIME + 3),
        reference_time_epoch_seconds: Some(1_800_000_000),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open encryption stream");
    let mut encrypted = Vec::new();
    for_odd_chunks(
        plaintext.as_slice(),
        &[1, 7, 31, OPENPGP_PARTIAL_PACKET_BYTES],
        |chunk| {
            encrypted.extend_from_slice(&encryption.update(chunk).expect("encrypt update"));
        },
    );
    let final_output = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(encryption.finish().expect("finish encryption")).as_slice(),
    )
    .expect("decode encryption final");
    assert_eq!(
        final_output.protection_mode,
        OpenPgpProtectionMode::SeipdV2Aead as i32
    );
    encrypted.extend_from_slice(&final_output.data);
    assert!(encrypted.starts_with(b"-----BEGIN PGP MESSAGE-----"));
    let armor_header_end = if encrypted.starts_with(b"-----BEGIN PGP MESSAGE-----\r\n") {
        b"-----BEGIN PGP MESSAGE-----\r\n".len()
    } else {
        b"-----BEGIN PGP MESSAGE-----\n".len()
    };
    encrypted.splice(
        armor_header_end..armor_header_end,
        b"Charset: ISO-8859-1\n".iter().copied(),
    );

    let mut decryption = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![material.private_key_armored.clone()],
        verification_public_keys: vec![signer.public_key_armored.clone()],
        reference_time_epoch_seconds: Some(1_800_000_000),
        allow_signed_only: None,
    })
    .expect("open decryption stream");
    let mut decrypted = Zeroizing::new(Vec::new());
    for_odd_chunks(
        &encrypted,
        &[64, 31, 7, 1, OPENPGP_PARTIAL_PACKET_BYTES],
        |chunk| {
            decrypted.extend_from_slice(&decryption.update(chunk).expect("decrypt update"));
        },
    );
    let final_output = OpenPgpDecryptFinal::decode(
        encode_decrypt_final(decryption.finish().expect("finish decryption")).as_slice(),
    )
    .expect("decode decryption final");
    decrypted.extend_from_slice(&final_output.data);
    assert_eq!(decrypted.as_slice(), plaintext.as_slice());
    assert!(final_output.verification.is_some());
    assert_eq!(final_output.declared_charset.as_deref(), Some("ISO-8859-1"));
}

#[test]
fn openpgp_stream_worker_limit_is_fail_closed_and_released_on_drop() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let request = || OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![material.public_key_armored.clone()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "limit.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME),
        reference_time_epoch_seconds: Some(TEST_TIME),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    };
    let sessions = (0..MAX_OPENPGP_STREAM_WORKERS)
        .map(|_| open_encryption_session(request()).expect("worker slot"))
        .collect::<Vec<_>>();
    assert!(matches!(
        open_encryption_session(request()),
        Err(OpenPgpWriteError::ResourceLimit)
    ));
    drop(sessions);
    let mut replacement = open_encryption_session(request()).expect("released worker slot");
    let _ = replacement
        .update(b"cancel this partially consumed stream")
        .expect("partial update before cancellation");
    drop(replacement);
    let final_replacement =
        open_encryption_session(request()).expect("cancelled worker slot released");
    drop(final_replacement);
}

#[test]
fn worker_input_disconnect_awaits_terminal_error_and_keeps_its_stable_code() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (input_dropped_tx, input_dropped_rx) = mpsc::sync_channel(0);
    let (release_tx, release_rx) = mpsc::sync_channel(0);
    let mut worker = OpenPgpWorkerPipe::spawn(
        "keyguard-openpgp-error-race-test",
        move |mut input, output| {
            let mut consumed = [0_u8; 3];
            input
                .read_exact(&mut consumed)
                .map_err(|_| OpenPgpWriteError::Internal)?;
            drop(input);
            input_dropped_tx
                .send(())
                .map_err(|_| OpenPgpWriteError::Internal)?;
            release_rx.recv().map_err(|_| OpenPgpWriteError::Internal)?;
            output
                .send(OpenPgpWorkerOutput::Data(Zeroizing::new(
                    b"unauthenticated".to_vec(),
                )))
                .map_err(|_| OpenPgpWriteError::Internal)?;
            Err(OpenPgpWriteError::ResourceLimit)
        },
    )
    .expect("open terminal-error test worker");

    assert!(
        worker
            .update(b"abc")
            .expect("consume first chunk")
            .is_empty()
    );
    input_dropped_rx
        .recv()
        .expect("worker dropped its input receiver");
    release_tx.send(()).expect("release terminal error");

    assert_eq!(
        worker.update(b"next"),
        Err(OpenPgpWriteError::ResourceLimit),
    );
    assert_eq!(
        worker.update(b"later"),
        Err(OpenPgpWriteError::ResourceLimit),
    );
    assert!(matches!(
        worker.finish(),
        Err(OpenPgpWriteError::ResourceLimit)
    ));
}

#[test]
fn worker_input_disconnect_preserves_terminal_success_output_for_finish() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (input_dropped_tx, input_dropped_rx) = mpsc::sync_channel(0);
    let (release_tx, release_rx) = mpsc::sync_channel(0);
    let mut worker = OpenPgpWorkerPipe::spawn(
        "keyguard-openpgp-success-race-test",
        move |mut input, output| {
            let mut consumed = [0_u8; 3];
            input
                .read_exact(&mut consumed)
                .map_err(|_| OpenPgpWriteError::Internal)?;
            drop(input);
            input_dropped_tx
                .send(())
                .map_err(|_| OpenPgpWriteError::Internal)?;
            release_rx.recv().map_err(|_| OpenPgpWriteError::Internal)?;
            output
                .send(OpenPgpWorkerOutput::Data(Zeroizing::new(
                    b"valid final output".to_vec(),
                )))
                .map_err(|_| OpenPgpWriteError::Internal)?;
            Ok(OpenPgpWorkerFinal::Encrypt(ProtectionMode::SeipdV1Mdc))
        },
    )
    .expect("open terminal-success test worker");

    assert!(
        worker
            .update(b"abc")
            .expect("consume first chunk")
            .is_empty()
    );
    input_dropped_rx
        .recv()
        .expect("worker dropped its input receiver");
    release_tx.send(()).expect("release terminal success");

    assert_eq!(
        worker.update(b"next"),
        Err(OpenPgpWriteError::InvalidArgument),
    );
    assert_eq!(
        worker.update(b"later"),
        Err(OpenPgpWriteError::InvalidArgument),
    );
    let (output, final_state) = worker.finish().expect("finish successful worker");
    assert_eq!(output, b"valid final output");
    assert!(matches!(
        final_state,
        OpenPgpWorkerFinal::Encrypt(ProtectionMode::SeipdV1Mdc)
    ));
}

#[test]
fn worker_panic_after_input_disconnect_is_stable() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (input_dropped_tx, input_dropped_rx) = mpsc::sync_channel(0);
    let (release_tx, release_rx) = mpsc::sync_channel(0);
    let mut worker =
        OpenPgpWorkerPipe::spawn("keyguard-openpgp-panic-race-test", move |mut input, _| {
            let mut consumed = [0_u8; 3];
            input
                .read_exact(&mut consumed)
                .map_err(|_| OpenPgpWriteError::Internal)?;
            drop(input);
            input_dropped_tx
                .send(())
                .map_err(|_| OpenPgpWriteError::Internal)?;
            release_rx.recv().map_err(|_| OpenPgpWriteError::Internal)?;
            panic!("injected worker panic");
        })
        .expect("open panic test worker");

    assert!(
        worker
            .update(b"abc")
            .expect("consume first chunk")
            .is_empty()
    );
    input_dropped_rx
        .recv()
        .expect("worker dropped its input receiver");
    release_tx.send(()).expect("release worker panic");

    assert_eq!(worker.update(b"next"), Err(OpenPgpWriteError::Panic));
    assert_eq!(worker.update(b"later"), Err(OpenPgpWriteError::Panic));
    assert!(matches!(worker.finish(), Err(OpenPgpWriteError::Panic)));
}

#[test]
fn worker_output_disconnect_without_terminal_is_stable_internal_failure() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (input_tx, input_rx) = mpsc::sync_channel(1);
    let (output_tx, output_rx) = mpsc::sync_channel(1);
    drop(input_rx);
    drop(output_tx);
    let mut worker = OpenPgpWorkerPipe::from_test_channels(input_tx, output_rx);

    assert_eq!(worker.update(b"first"), Err(OpenPgpWriteError::Internal));
    assert_eq!(worker.update(b"later"), Err(OpenPgpWriteError::Internal));
    assert!(matches!(worker.finish(), Err(OpenPgpWriteError::Internal)));
}

#[test]
fn streaming_aead_releases_authenticated_chunks_but_rejects_truncated_final_tag() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let material = generated_modern_material();
    let mut state = 0x9e37_79b9_u32;
    let plaintext = (0..512 * 1024)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            state as u8
        })
        .collect::<Vec<_>>();
    let mut encryption = open_encryption_session(OpenPgpEncryptStreamOpenRequest {
        public_keys: vec![material.public_key_armored.clone()],
        signing_private_key: None,
        preferred_signing_fingerprint: String::new(),
        file_name: "tamper.bin".to_owned(),
        armored: false,
        literal_time_epoch_seconds: Some(TEST_TIME + 4),
        reference_time_epoch_seconds: Some(TEST_TIME + 4),
        enable_compression: None,
        candidate_revocation_keys: Vec::new(),
    })
    .expect("open tamper encryption stream");
    let mut encrypted = Vec::new();
    for chunk in plaintext.chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
        encrypted.extend_from_slice(&encryption.update(chunk).expect("tamper encrypt update"));
    }
    let encrypted_final = OpenPgpEncryptFinal::decode(
        encode_encrypt_final(encryption.finish().expect("finish tamper encryption")).as_slice(),
    )
    .expect("decode tamper encryption final");
    assert_eq!(
        encrypted_final.protection_mode,
        OpenPgpProtectionMode::GnupgOcb as i32,
    );
    encrypted.extend_from_slice(&encrypted_final.data);
    let truncated = &encrypted[..encrypted.len() - 1];
    let mut decryption = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys: vec![material.private_key_armored.clone()],
        verification_public_keys: Vec::new(),
        reference_time_epoch_seconds: Some(TEST_TIME + 4),
        allow_signed_only: None,
    })
    .expect("open decryption stream");
    let mut authenticated_bytes = 0_usize;
    for_odd_chunks(truncated, &[1, 7, 31], |chunk| {
        authenticated_bytes += decryption
            .update(chunk)
            .expect("authenticated AEAD decrypt update")
            .len();
    });
    assert!(authenticated_bytes > 0);
    assert_eq!(
        decryption.finish().expect_err("missing OCB tag must fail"),
        OpenPgpWriteError::AuthenticationFailed
    );
}
