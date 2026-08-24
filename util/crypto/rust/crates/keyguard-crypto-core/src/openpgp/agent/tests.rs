#![allow(clippy::expect_used, clippy::panic, clippy::unwrap_used)]

use std::io::Cursor;

use super::*;
use crate::{
    openpgp::adapter::wire::{
        Message as _, OpenPgpAgentDecryptRequest, OpenPgpAgentDecryptResult,
        OpenPgpAgentErrorReason, OpenPgpAgentSignRequest, OpenPgpAgentSignResult,
        OpenPgpKeyGenerateRequest, OpenPgpKeyImportRequest, OpenPgpKeyImportResult, OpenPgpKeyKind,
        OpenPgpKeyMaterial, OpenPgpMetadataResolveRequest, OpenPgpMetadataResolveResult,
        OpenPgpPublicKeyParseRequest, OpenPgpPublicKeyParseResult, open_pgp_agent_decrypt_result,
        open_pgp_agent_sign_result, open_pgp_key_import_result, open_pgp_public_key_parse_result,
    },
    openpgp::{
        adapter::key::{generate as generate_key_request, import as import_key_request},
        key::import_secret_packet_public_len,
        packet::{
            PUBLIC_KEY_TAG, RawPacketStream, SECRET_KEY_TAG, SECRET_SUBKEY_TAG, write_fixed_packet,
        },
        policy::RenewalAuthorization,
    },
};
use pgp::{
    composed::{
        ArmorOptions, Deserializable, EncryptionCaps, KeyType, SecretKeyParamsBuilder,
        SignedSecretKey, SubkeyParamsBuilder,
    },
    packet::{KeyFlags, SignatureConfig, SignatureType, Subpacket, SubpacketData},
    ser::Serialize,
    types::{Duration, KeyVersion, Mpi, SignatureBytes, Tag, Timestamp, VerifyingKey},
};
use proptest::prelude::*;
use rand::{SeedableRng, rngs::StdRng};

const TEST_TIME: u64 = 1_700_000_000;

fn sign_request(request: OpenPgpAgentSignRequest) -> Result<Vec<u8>, crate::PrimitiveError> {
    crate::openpgp::adapter::agent_sign(request)
}

fn decrypt_request(request: OpenPgpAgentDecryptRequest) -> Result<Vec<u8>, crate::PrimitiveError> {
    crate::openpgp::adapter::agent_decrypt(request)
}

#[test]
fn canonical_enc_val_preserves_binary_atoms_and_flags() {
    let input = b"(7:enc-val(5:flags3:raw)(4:ecdh(1:e4:\0@\x7f\xff)(1:s3:\x02ab)))";
    let parsed = parse_enc_val(input).expect("canonical expression parses");
    assert_eq!(parsed.algorithm, EncValAlgorithm::Ecdh);
    assert_eq!(parsed.e, Some(&[0, 0x40, 0x7f, 0xff][..]));
    assert_eq!(parsed.s, Some(&[2, b'a', b'b'][..]));
}

#[test]
fn canonical_parser_rejects_trailing_overflow_duplicate_and_deep_inputs() {
    for malformed in [
        b"(7:enc-val(3:rsa(1:a1:x)))junk".as_slice(),
        b"(7:enc-val(3:rsa(1:a1:x)(1:a1:y)))".as_slice(),
        b"(7:enc-val(3:rsa(1:a999999999999999999999:x)))".as_slice(),
    ] {
        assert!(parse_enc_val(malformed).is_err());
    }
    let mut deep = vec![b'('; MAX_SEXPR_DEPTH + 1];
    deep.extend_from_slice(b"1:x");
    deep.extend(std::iter::repeat_n(b')', MAX_SEXPR_DEPTH + 1));
    assert_eq!(
        CanonicalCursor::parse(&deep).expect_err("depth bound must fail"),
        OpenPgpAgentError::ResourceLimit,
    );
}

proptest! {
    #[test]
    fn canonical_parser_contains_arbitrary_malformed_input(
        input in proptest::collection::vec(any::<u8>(), 0..2_048),
    ) {
        let _ = CanonicalCursor::parse(&input);
    }
}

#[test]
fn canonical_renderers_emit_transport_not_advanced_hex() {
    assert_eq!(
        canonical_signature("ecdsa", &[("r", &[0, 1]), ("s", &[2, 3])])
            .expect("signature renders")
            .as_slice(),
        b"(7:sig-val(5:ecdsa(1:r2:\0\x01)(1:s2:\x02\x03)))",
    );
    assert_eq!(
        canonical_value(&[0, 0xff])
            .expect("value renders")
            .as_slice(),
        b"(5:value2:\0\xff)",
    );
}

#[test]
fn minimal_unsigned_keeps_one_zero_octet() {
    assert_eq!(minimal_unsigned(&[0, 0, 1]), &[1]);
    assert_eq!(minimal_unsigned(&[0, 0]), &[0]);
    assert!(minimal_unsigned(&[]).is_empty());
}

#[test]
fn agent_hash_algorithm_parses_allowed_gnupg_names_and_numbers_with_exact_lengths() {
    for (name, number, length, expected) in [
        ("sha224", "11", 28, AgentHashAlgorithm::Sha224),
        ("sha256", "8", 32, AgentHashAlgorithm::Sha256),
        ("sha384", "9", 48, AgentHashAlgorithm::Sha384),
        ("sha512", "10", 64, AgentHashAlgorithm::Sha512),
    ] {
        let digest = vec![0x5a; length];
        assert_eq!(
            AgentHashAlgorithm::parse(name, &digest),
            Ok(expected),
            "named GnuPG algorithm {name}",
        );
        assert_eq!(
            AgentHashAlgorithm::parse(number, &digest),
            Ok(expected),
            "numeric GnuPG algorithm {number}",
        );
        assert_eq!(
            AgentHashAlgorithm::parse(&name.to_ascii_uppercase(), &digest),
            Ok(expected),
            "case-insensitive GnuPG algorithm {name}",
        );
        for mismatch in [length - 1, length + 1] {
            assert_eq!(
                AgentHashAlgorithm::parse(name, &vec![0x5a; mismatch]),
                Err(AgentHashParseError::InvalidArgument),
                "{name} digest length {mismatch}",
            );
        }
    }
    for invalid in ["", "sha3-256", "999"] {
        assert_eq!(
            AgentHashAlgorithm::parse(invalid, &[0x5a; 32]),
            Err(AgentHashParseError::InvalidArgument),
        );
    }
}

#[test]
fn agent_hash_algorithm_rejects_rfc9580_prohibited_gnupg_names_and_numbers() {
    for (algorithm, length) in [
        ("md5", 16),
        ("1", 16),
        ("sha1", 20),
        ("2", 20),
        ("rmd160", 20),
        ("ripemd160", 20),
        ("3", 20),
    ] {
        let digest = vec![0x5a; length];
        assert_eq!(
            AgentHashAlgorithm::parse(algorithm, &digest),
            Err(AgentHashParseError::UnsupportedAlgorithm),
            "RFC 9580 prohibited GnuPG algorithm {algorithm}",
        );
        assert_eq!(
            AgentHashAlgorithm::parse(&algorithm.to_ascii_uppercase(), &digest),
            Err(AgentHashParseError::UnsupportedAlgorithm),
            "case-insensitive prohibited GnuPG algorithm {algorithm}",
        );
    }
}

#[test]
fn rsa_agent_signing_routes_prehashed_private_work_through_aws_lc() {
    let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
    let keys = parse_secret_keys(&material.private_key_armored).expect("parse generated RSA");
    let packet = keys
        .iter()
        .flat_map(|key| key.subkeys().iter().map(SecretPacketRef::Subkey))
        .find(|packet| {
            matches!(
                packet.algorithm(),
                PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSASign
            )
        })
        .expect("generated RSA signing component");
    let fingerprint = format!("{:X}", packet.fingerprint());
    let digest = [0x5a_u8; 32];

    for (hash_algorithm, hash) in [
        ("", vec![0x5a; 32]),
        ("unknown", vec![0x5a; 32]),
        ("sha256", vec![0x5a; 31]),
        ("sha256", vec![0x5a; 33]),
    ] {
        assert_sign_invalid(
            &material.private_key_armored,
            &fingerprint,
            hash_algorithm,
            hash,
        );
    }
    for (hash_algorithm, hash) in [
        ("md5", vec![0x5a; 16]),
        ("1", vec![0x5a; 16]),
        ("sha1", vec![0x5a; 20]),
        ("2", vec![0x5a; 20]),
        ("rmd160", vec![0x5a; 20]),
        ("ripemd160", vec![0x5a; 20]),
        ("3", vec![0x5a; 20]),
    ] {
        assert_sign_unsupported(
            &material.private_key_armored,
            &fingerprint,
            hash_algorithm,
            hash,
        );
    }
    // A prohibited hash fails before certificate policy and key selection.
    assert_sign_unsupported(
        &material.private_key_armored,
        &"0".repeat(40),
        "sha1",
        vec![0x5a; 20],
    );

    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: fingerprint,
            hash_algorithm: "8".to_owned(),
            hash: digest.to_vec(),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("agent RSA signing")
        .as_slice(),
    )
    .expect("decode agent RSA result");
    let canonical = match response.result.as_ref() {
        Some(open_pgp_agent_sign_result::Result::Success(success)) => {
            success.canonical_sexp.as_slice()
        }
        result => panic!("expected RSA signing success, got {result:?}"),
    };
    let signature = signature_components(canonical, b"rsa");
    assert_eq!(signature.len(), 1);
    assert_eq!(signature[0].0, b"s");
    let signature = SignatureBytes::Mpis(vec![Mpi::from_slice(&signature[0].1)]);
    verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
}

#[test]
fn ecdsa_p521_agent_signing_enforces_the_sha512_floor_and_exact_length() {
    let secret = generated_agent_signer(KeyType::ECDSA(ECCCurve::P521), 0x5047_5035_3231);
    let private_key = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor generated P-521 key");
    let packet = SecretPacketRef::Primary(&secret.primary_key);
    let fingerprint = format!("{:X}", packet.fingerprint());

    assert_sign_invalid(&private_key, &fingerprint, "sha384", vec![0x3c; 48]);
    assert_sign_invalid(&private_key, &fingerprint, "sha512", vec![0x3c; 63]);
    assert_sign_invalid(&private_key, &fingerprint, "sha512", vec![0x3c; 65]);

    let digest = [0x3c_u8; 64];
    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key,
            preferred_fingerprint: fingerprint,
            hash_algorithm: "10".to_owned(),
            hash: digest.to_vec(),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("agent P-521 signing")
        .as_slice(),
    )
    .expect("decode agent P-521 result");
    let canonical = match response.result.as_ref() {
        Some(open_pgp_agent_sign_result::Result::Success(success)) => {
            success.canonical_sexp.as_slice()
        }
        result => panic!("expected P-521 signing success, got {result:?}"),
    };
    let components = signature_components(canonical, b"ecdsa");
    assert_eq!(components.len(), 2);
    let signature = SignatureBytes::Mpis(vec![
        Mpi::from_slice(&components[0].1),
        Mpi::from_slice(&components[1].1),
    ]);
    verify_with_packet(packet, HashAlgorithm::Sha512, &digest, &signature);
}

#[test]
fn brainpool_ecdsa_keys_are_not_agent_capable_and_return_unsupported_envelopes() {
    let source = generated_agent_signer(KeyType::ECDSA(ECCCurve::P256), 0x4250_4543_4453_4100)
        .to_bytes()
        .expect("serialize ECDSA source key");

    for curve in brainpool_curves() {
        let private_key = rewrite_first_ecc_curve(&source, PublicKeyAlgorithm::ECDSA, &curve);
        let keys = parse_secret_keys(&private_key).expect("parse rewritten Brainpool ECDSA key");
        let packet = keys
            .iter()
            .flat_map(secret_packets)
            .find(|packet| packet.algorithm() == PublicKeyAlgorithm::ECDSA)
            .expect("rewritten Brainpool ECDSA component");
        assert_eq!(packet_curve(packet), curve);
        assert!(!supports_signing_key(
            packet.algorithm(),
            packet.public_key().public_params(),
        ));

        let response = OpenPgpAgentSignResult::decode(
            sign_request(OpenPgpAgentSignRequest {
                private_key,
                preferred_fingerprint: format!("{:X}", packet.fingerprint()),
                hash_algorithm: "sha512".to_owned(),
                hash: vec![0x42; 64],
                candidate_revocation_keys: Vec::new(),
            })
            .expect("unsupported ECDSA curve is a typed result")
            .as_slice(),
        )
        .expect("decode unsupported ECDSA result");
        assert!(matches!(
            response.result,
            Some(open_pgp_agent_sign_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::UnsupportedAlgorithm as i32
        ));
    }
}

#[test]
fn brainpool_ecdh_keys_are_not_agent_capable_and_return_unsupported_envelopes() {
    let source = generated_agent_decryptor(ECCCurve::P256, 0x4250_4543_4448_0000)
        .to_bytes()
        .expect("serialize ECDH source key");

    for curve in brainpool_curves() {
        let private_key = rewrite_first_ecc_curve(&source, PublicKeyAlgorithm::ECDH, &curve);
        let keys = parse_secret_keys(&private_key).expect("parse rewritten Brainpool ECDH key");
        let packet = keys
            .iter()
            .flat_map(secret_packets)
            .find(|packet| packet.algorithm() == PublicKeyAlgorithm::ECDH)
            .expect("rewritten Brainpool ECDH component");
        assert_eq!(packet_curve(packet), curve);
        assert!(!supports_decryption_key(
            packet.algorithm(),
            packet.public_key().public_params(),
        ));

        let mut wrapped = vec![16];
        wrapped.extend_from_slice(&[0x24; 16]);
        let response = OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key,
                preferred_fingerprint: format!("{:X}", packet.fingerprint()),
                ciphertext: ecdh_enc_val(&[0x04], &wrapped),
                unwrap_ecdh: true,
            })
            .expect("unsupported ECDH curve is a typed result")
            .as_slice(),
        )
        .expect("decode unsupported ECDH result");
        assert!(matches!(
            response.result,
            Some(open_pgp_agent_decrypt_result::Result::Error(error))
                if error.reason == OpenPgpAgentErrorReason::UnsupportedAlgorithm as i32
        ));
    }
}

#[test]
fn concrete_agent_curve_capabilities_keep_supported_curves_enabled() {
    for (curve, seed) in [
        (ECCCurve::P256, 0x5355_5050_4543_0001),
        (ECCCurve::P384, 0x5355_5050_4543_0002),
        (ECCCurve::P521, 0x5355_5050_4543_0003),
        (ECCCurve::Secp256k1, 0x5355_5050_4543_0004),
    ] {
        let secret = generated_agent_signer(KeyType::ECDSA(curve.clone()), seed);
        let packet = SecretPacketRef::Primary(&secret.primary_key);
        assert_eq!(packet_curve(packet), curve);
        assert!(supports_signing_key(
            packet.algorithm(),
            packet.public_key().public_params(),
        ));
    }

    for (curve, seed) in [
        (ECCCurve::Curve25519Legacy, 0x5355_5050_4543_0005),
        (ECCCurve::P256, 0x5355_5050_4543_0006),
        (ECCCurve::P384, 0x5355_5050_4543_0007),
        (ECCCurve::P521, 0x5355_5050_4543_0008),
    ] {
        let secret = generated_agent_decryptor(curve.clone(), seed);
        let packet = SecretPacketRef::Subkey(&secret.secret_subkeys[0].key);
        assert_eq!(packet_curve(packet), curve);
        assert!(supports_decryption_key(
            packet.algorithm(),
            packet.public_key().public_params(),
        ));
    }
}

#[test]
fn ed25519_agent_signing_keeps_fixed_width_components() {
    let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
    let keys = parse_secret_keys(&material.private_key_armored).expect("parse generated Ed25519");
    let packet = keys
        .iter()
        .flat_map(|key| key.subkeys().iter().map(SecretPacketRef::Subkey))
        .find(|packet| {
            matches!(
                packet.algorithm(),
                PublicKeyAlgorithm::EdDSALegacy | PublicKeyAlgorithm::Ed25519
            )
        })
        .expect("generated Ed25519 signing component");
    let fingerprint = format!("{:X}", packet.fingerprint());
    let digest = [0xa5_u8; 32];

    assert_sign_invalid(
        &material.private_key_armored,
        &fingerprint,
        "sha224",
        vec![0xa5; 28],
    );
    assert_sign_invalid(
        &material.private_key_armored,
        &fingerprint,
        "sha256",
        vec![0xa5; 31],
    );

    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: fingerprint,
            hash_algorithm: "sha256".to_owned(),
            hash: digest.to_vec(),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("agent Ed25519 signing")
        .as_slice(),
    )
    .expect("decode agent Ed25519 result");
    let canonical = match response.result.as_ref() {
        Some(open_pgp_agent_sign_result::Result::Success(success)) => {
            success.canonical_sexp.as_slice()
        }
        result => panic!("expected Ed25519 signing success, got {result:?}"),
    };
    let signature = signature_components(canonical, b"eddsa");
    assert_eq!(signature.len(), 2);
    assert_eq!(signature[0].0, b"r");
    assert_eq!(signature[1].0, b"s");
    assert_eq!(signature[0].1.len(), 32);
    assert_eq!(signature[1].1.len(), 32);
    let signature = SignatureBytes::Mpis(vec![
        Mpi::from_slice(&signature[0].1),
        Mpi::from_slice(&signature[1].1),
    ]);
    verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
}

#[test]
fn agent_refuses_generated_certify_only_primaries_but_renewal_remains_authorized() {
    for (kind, rsa_bits) in [
        (OpenPgpKeyKind::Rsa, 3_072),
        (OpenPgpKeyKind::LegacyEd25519X25519, 0),
    ] {
        let material = generated_material(kind, rsa_bits);
        let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated certify-only key");
        let public = secret.to_public_key();
        let candidates = all_components(std::slice::from_ref(&public));
        let policy = validate_certificate(
            &public,
            &candidates,
            TEST_TIME,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("inspect generated certify-only key");
        let primary = policy.primary_component();
        let flags = primary
            .policy()
            .key_flags
            .as_ref()
            .expect("generated primary key flags");
        assert!(flags.certify());
        assert!(!flags.sign());
        assert!(!primary.signing_usable());
        assert_eq!(
            policy.authorize_primary_renewal(),
            Ok(RenewalAuthorization::Authenticated),
        );
        assert_sign_key_not_found(
            &material.private_key_armored,
            &format!("{:X}", secret.primary_key.fingerprint()),
        );
    }
}

#[test]
fn agent_refuses_a_sha1_template_primary_but_renewal_remains_authorized() {
    let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated RSA key");
    let user_id = secret.details.users[0].id.clone();
    let mut config = secret.details.users[0].signatures[0]
        .config()
        .cloned()
        .expect("generated v4 self-certification");
    config.hash_alg = HashAlgorithm::Sha1;
    let sha1_certification = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign SHA-1 renewal template");
    secret.details.users[0].signatures = vec![sha1_certification];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        reference_time(None),
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect SHA-1 template certificate");
    assert!(!policy.primary.authenticated);
    assert_eq!(
        policy.authorize_primary_renewal(),
        Ok(RenewalAuthorization::TemplateOnly),
    );
    assert!(!policy.primary_component().signing_usable());

    let private_key = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor SHA-1 template key");
    assert_sign_key_not_found(
        &private_key,
        &format!("{:X}", secret.primary_key.fingerprint()),
    );
}

#[test]
fn filtered_tsk_agent_signing_uses_the_secret_signing_subkey() {
    let digest = [0xa7_u8; 32];
    for (label, private_key) in filtered_agent_private_keys() {
        let keys = parse_secret_keys(&private_key)
            .unwrap_or_else(|error| panic!("parse {label}: {error:?}"));
        assert!(
            keys.iter().all(|key| key.primary().is_none()),
            "{label} has no primary secret",
        );
        let packet = keys
            .iter()
            .flat_map(|key| key.subkeys().iter().map(SecretPacketRef::Subkey))
            .find(|packet| {
                matches!(
                    packet.algorithm(),
                    PublicKeyAlgorithm::EdDSALegacy | PublicKeyAlgorithm::Ed25519
                )
            })
            .unwrap_or_else(|| panic!("{label} signing subkey"));
        let fingerprint = format!("{:X}", packet.fingerprint());
        let response = OpenPgpAgentSignResult::decode(
            sign_request(OpenPgpAgentSignRequest {
                private_key,
                preferred_fingerprint: fingerprint,
                hash_algorithm: "sha256".to_owned(),
                hash: digest.to_vec(),
                candidate_revocation_keys: Vec::new(),
            })
            .unwrap_or_else(|error| panic!("agent sign with {label}: {error}"))
            .as_slice(),
        )
        .unwrap_or_else(|error| panic!("decode {label} agent signature: {error}"));
        let canonical = match response.result.as_ref() {
            Some(open_pgp_agent_sign_result::Result::Success(success)) => {
                success.canonical_sexp.as_slice()
            }
            result => panic!("expected {label} signing success, got {result:?}"),
        };
        let components = signature_components(canonical, b"eddsa");
        let signature = SignatureBytes::Mpis(vec![
            Mpi::from_slice(&components[0].1),
            Mpi::from_slice(&components[1].1),
        ]);
        verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
    }
}

#[test]
fn native_ed25519_agent_signing_accepts_a_declared_sha256_digest() {
    let secret = generated_agent_signer(KeyType::Ed25519, 0x0045_4432_3535_3139);
    let private_key = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor generated native Ed25519 key");
    let packet = SecretPacketRef::Primary(&secret.primary_key);
    assert_eq!(packet.algorithm(), PublicKeyAlgorithm::Ed25519);
    let fingerprint = format!("{:X}", packet.fingerprint());
    let digest = [0x69_u8; 32];

    assert_sign_invalid(&private_key, &fingerprint, "sha224", vec![0x69; 28]);
    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key,
            preferred_fingerprint: fingerprint,
            hash_algorithm: "sha256".to_owned(),
            hash: digest.to_vec(),
            candidate_revocation_keys: Vec::new(),
        })
        .expect("agent native Ed25519 signing")
        .as_slice(),
    )
    .expect("decode native Ed25519 result");
    let canonical = match response.result.as_ref() {
        Some(open_pgp_agent_sign_result::Result::Success(success)) => {
            success.canonical_sexp.as_slice()
        }
        result => panic!("expected native Ed25519 signing success, got {result:?}"),
    };
    let components = signature_components(canonical, b"eddsa");
    assert_eq!(components.len(), 2);
    assert_eq!(components[0].1.len(), 32);
    assert_eq!(components[1].1.len(), 32);
    let signature = SignatureBytes::Native(
        components
            .into_iter()
            .flat_map(|(_, value)| value)
            .collect::<Vec<_>>()
            .into(),
    );
    verify_with_packet(packet, HashAlgorithm::Sha256, &digest, &signature);
}

#[test]
fn x25519_agent_decryption_returns_legacy_and_rfc6637_values() {
    for (label, private_key) in filtered_agent_private_keys() {
        assert_x25519_agent_decryption(&label, private_key);
    }
}

fn assert_x25519_agent_decryption(label: &str, private_key: Vec<u8>) {
    let keys = parse_secret_keys(&private_key)
        .unwrap_or_else(|error| panic!("parse {label} ECDH key: {error:?}"));
    let packet = keys
        .iter()
        .flat_map(secret_packets)
        .find(|packet| packet.algorithm() == PublicKeyAlgorithm::ECDH)
        .expect("generated Curve25519 component");
    let fingerprint = format!("{:X}", packet.fingerprint());
    let recipient_secret = unlock_agent_packet(packet, |_, private| {
        let PlainSecretParams::ECDH(private) = private else {
            return Err(pgp_error("expected ECDH private parameters"));
        };
        if private.curve() != ECCCurve::Curve25519Legacy {
            return Err(pgp_error("expected legacy Curve25519"));
        }
        Ok(private.to_bytes())
    })
    .expect("unlock generated Curve25519");
    let recipient_secret: [u8; 32] = recipient_secret
        .as_slice()
        .try_into()
        .expect("Curve25519 scalar width");
    let recipient_public = X25519PublicKey::from(&X25519Secret::from(recipient_secret));
    let ephemeral_secret = X25519Secret::from([0x33_u8; 32]);
    let ephemeral_public = X25519PublicKey::from(&ephemeral_secret);
    let shared = ephemeral_secret.diffie_hellman(&recipient_public);
    assert!(shared.was_contributory());

    let PublicParams::ECDH(public) = packet.public_key().public_params() else {
        panic!("generated ECDH public parameters");
    };
    let (hash, symmetric) = ecdh_algorithms(public).expect("supported RFC 6637 algorithms");
    let param = build_ecdh_param(
        &ECCCurve::Curve25519Legacy.oid(),
        symmetric,
        hash,
        packet.fingerprint().as_bytes(),
    );
    let kek = rfc6637_kdf(hash, shared.as_bytes(), symmetric.key_size(), &param)
        .expect("derive RFC 6637 KEK");
    let padded_session_key = [0x42_u8; 40];
    let wrapped = aes_kw::wrap(kek.as_slice(), &padded_session_key).expect("AES-wrap session");
    let mut encoded_wrapped = vec![u8::try_from(wrapped.len()).expect("wrapped key length")];
    encoded_wrapped.extend_from_slice(&wrapped);
    let mut encoded_ephemeral = vec![X25519_LEGACY_PREFIX];
    encoded_ephemeral.extend_from_slice(ephemeral_public.as_bytes());
    let enc_val = ecdh_enc_val(&encoded_ephemeral, &encoded_wrapped);

    let decrypt = |unwrap_ecdh| {
        OpenPgpAgentDecryptResult::decode(
            decrypt_request(OpenPgpAgentDecryptRequest {
                private_key: private_key.clone(),
                preferred_fingerprint: fingerprint.clone(),
                ciphertext: enc_val.clone(),
                unwrap_ecdh,
            })
            .unwrap_or_else(|error| panic!("agent Curve25519 decryption with {label}: {error}"))
            .as_slice(),
        )
        .expect("decode agent Curve25519 result")
    };
    let unwrapped = decrypt(true);
    assert_eq!(
        decrypt_value(&unwrapped),
        padded_session_key,
        "agent must leave RFC 6637 PKCS#5 padding for gpg",
    );
    let legacy = decrypt(false);
    let mut expected_legacy = vec![X25519_LEGACY_PREFIX];
    expected_legacy.extend_from_slice(shared.as_bytes());
    assert_eq!(decrypt_value(&legacy), expected_legacy);

    let missing = OpenPgpAgentDecryptResult::decode(
        decrypt_request(OpenPgpAgentDecryptRequest {
            private_key: private_key.clone(),
            preferred_fingerprint: "0".repeat(40),
            ciphertext: Vec::new(),
            unwrap_ecdh: false,
        })
        .expect("missing selector is a typed result")
        .as_slice(),
    )
    .expect("decode missing selector result");
    assert!(matches!(
        missing.result,
        Some(open_pgp_agent_decrypt_result::Result::Error(error))
            if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
    ));

    let blank = OpenPgpAgentDecryptResult::decode(
        decrypt_request(OpenPgpAgentDecryptRequest {
            private_key,
            preferred_fingerprint: String::new(),
            ciphertext: Vec::new(),
            unwrap_ecdh: false,
        })
        .expect("blank selector is a typed result")
        .as_slice(),
    )
    .expect("decode blank-selector result");
    assert!(matches!(
        blank.result,
        Some(open_pgp_agent_decrypt_result::Result::Error(error))
            if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
    ));
}

#[test]
fn current_policy_blocks_metadata_but_raw_decrypt_routes_exact_component() {
    let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated RSA key");
    let primary = &secret.primary_key;
    let target = secret.secret_subkeys[1].key.public_key().clone();
    let fingerprint = format!("{:X}", target.fingerprint());
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            TEST_TIME as u32,
        )))
        .expect("signature creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("issuer fingerprint subpacket"),
        Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
            1,
        )))
        .expect("signature expiration subpacket"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags subpacket"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("issuer key ID subpacket"),
    ];
    let binding = config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &target)
        .expect("create sign-only binding");
    secret.secret_subkeys[1].signatures = vec![binding];
    let mut revocation = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    revocation.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("revocation creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("revocation issuer fingerprint"),
    ];
    revocation.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("revocation issuer key ID"),
    ];
    let revocation = revocation
        .sign_key(primary, &Password::empty(), primary.public_key())
        .expect("create primary revocation");
    secret.details.revocation_signatures.push(revocation);

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(&public, &candidates, TEST_TIME + 1, &mut budget)
        .expect("inspect sign-only certificate");
    let component = policy
        .subkey_components()
        .nth(secret.public_subkeys.len() + 1)
        .expect("inspect selected subkey policy");
    assert!(!component.policy().authenticated);
    assert!(!component.encryption_usable());
    let public_info = OpenPgpPublicKeyParseResult::decode(
        crate::openpgp::adapter::parse_public_key(OpenPgpPublicKeyParseRequest {
            key_data: public
                .to_armored_bytes(ArmorOptions::default())
                .expect("armor sign-only public key"),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("parse current public policy")
        .as_slice(),
    )
    .expect("decode current public policy");
    let Some(open_pgp_public_key_parse_result::Result::Success(success)) = public_info.result
    else {
        panic!("expected current public-key information");
    };
    assert!(success.keys[0].revoked);
    assert!(!success.keys[0].can_encrypt);

    let private_key = secret
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor sign-only key");
    let parsed = parse_secret_keys(&private_key).expect("parse sign-only key");
    assert!(select_preferred_packet(&parsed, &fingerprint).is_some());

    let metadata = OpenPgpMetadataResolveResult::decode(
        crate::openpgp::adapter::resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(private_key),
            public_key_data: None,
            normalized_fingerprint: material.fingerprint.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("resolve current metadata")
        .as_slice(),
    )
    .expect("decode current metadata");
    let metadata = metadata
        .resolution
        .expect("matching revoked certificate resolves successfully");
    assert!(
        metadata
            .certificates
            .iter()
            .flat_map(|certificate| &certificate.policy)
            .all(|component| component.allowed_new_data_uses.is_empty())
    );
}

#[test]
fn current_rsa_metadata_respects_key_flags() {
    let material = generated_material(OpenPgpKeyKind::Rsa, 3_072);
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated RSA key");
    let primary_fingerprint = format!("{:X}", secret.primary_key.fingerprint());
    let signing_fingerprint = format!("{:X}", secret.secret_subkeys[0].key.fingerprint());
    let encryption_fingerprint = format!("{:X}", secret.secret_subkeys[1].key.fingerprint());
    let metadata = OpenPgpMetadataResolveResult::decode(
        crate::openpgp::adapter::resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(material.private_key_armored.clone()),
            public_key_data: None,
            normalized_fingerprint: primary_fingerprint.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("resolve current RSA metadata")
        .as_slice(),
    )
    .expect("decode current RSA metadata")
    .resolution
    .expect("current RSA metadata");

    let policy = |fingerprint: &str| {
        metadata.certificates[0]
            .policy
            .iter()
            .find(|component| component.fingerprint == fingerprint)
            .unwrap_or_else(|| panic!("missing metadata for {fingerprint}"))
    };
    assert!(
        policy(&primary_fingerprint)
            .allowed_new_data_uses
            .is_empty()
    );
    assert_eq!(
        policy(&signing_fingerprint).allowed_new_data_uses,
        [crate::openpgp::adapter::wire::OpenPgpPolicyUse::SignNewData as i32],
    );
    assert_eq!(
        policy(&encryption_fingerprint).allowed_new_data_uses,
        [crate::openpgp::adapter::wire::OpenPgpPolicyUse::EncryptNewData as i32],
    );

    let parsed = parse_secret_keys(&material.private_key_armored).expect("parse current RSA key");
    assert!(select_preferred_packet(&parsed, &signing_fingerprint).is_some());
}

#[test]
fn agent_rejects_unbound_signing_but_raw_decrypt_routes_exact_subkey() {
    let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
    let (mut signing_unbound, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated signing key");
    let signing_fingerprint = format!("{:X}", signing_unbound.secret_subkeys[0].key.fingerprint());
    signing_unbound.secret_subkeys[0].signatures =
        signing_unbound.secret_subkeys[1].signatures.clone();
    let signing_unbound = signing_unbound
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor unbound signing key");
    assert_sign_key_not_found(&signing_unbound, &signing_fingerprint);

    let (mut decryption_unbound, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated decryption key");
    let decryption_fingerprint = format!(
        "{:X}",
        decryption_unbound.secret_subkeys[1].key.fingerprint()
    );
    decryption_unbound.secret_subkeys[1].signatures =
        decryption_unbound.secret_subkeys[0].signatures.clone();
    let decryption_unbound = decryption_unbound
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor unbound decryption key");
    let decryption_unbound =
        parse_secret_keys(&decryption_unbound).expect("parse unbound decryption key");
    assert!(select_preferred_packet(&decryption_unbound, &decryption_fingerprint).is_some(),);
}

#[test]
fn agent_signing_keeps_subkey_sign_flag_and_cross_certification_rules() {
    let rsa = generated_material(OpenPgpKeyKind::Rsa, 3_072);
    let (rsa, _) =
        SignedSecretKey::from_reader_single(Cursor::new(rsa.private_key_armored.as_slice()))
            .expect("parse generated RSA key");
    let encryption_fingerprint = format!("{:X}", rsa.secret_subkeys[1].key.fingerprint());
    assert_sign_key_not_found(
        &rsa.to_armored_bytes(ArmorOptions::default())
            .expect("armor RSA key"),
        &encryption_fingerprint,
    );

    let material = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
    let (mut missing_cross_certification, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated signing key");
    let primary = &missing_cross_certification.primary_key;
    let signing_subkey = missing_cross_certification.secret_subkeys[0]
        .key
        .public_key()
        .clone();
    let signing_fingerprint = format!("{:X}", signing_subkey.fingerprint());
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            TEST_TIME as u32,
        )))
        .expect("signature creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("issuer fingerprint subpacket"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags subpacket"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("issuer key ID subpacket"),
    ];
    let binding = config
        .sign_subkey_binding(
            primary,
            primary.public_key(),
            &Password::empty(),
            &signing_subkey,
        )
        .expect("create binding without back-signature");
    missing_cross_certification.secret_subkeys[0].signatures = vec![binding];
    assert_sign_key_not_found(
        &missing_cross_certification
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor key missing cross-certification"),
        &signing_fingerprint,
    );
}

#[test]
fn agent_refuses_expired_and_revoked_primaries_but_expired_renewal_remains_authorized() {
    let expired = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Agent <expired@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: Some(3_600),
        })
        .expect("generate expired agent certificate")
        .as_slice(),
    )
    .expect("decode expired agent key material");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(expired.private_key_armored.as_slice()))
            .expect("parse expired agent key");
    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(&public, &candidates, reference_time(None), &mut budget)
        .expect("inspect expired certificate");
    assert!(!policy.primary_available());
    assert_eq!(
        policy.authorize_primary_renewal(),
        Ok(RenewalAuthorization::Authenticated),
    );
    assert!(!policy.primary_component().signing_usable());

    let packet = SecretPacketRef::Primary(&secret.primary_key);
    let fingerprint = format!("{:X}", packet.fingerprint());
    assert_sign_key_not_found(&expired.private_key_armored, &fingerprint);

    let mut revoked = secret.clone();
    let primary = &revoked.primary_key;
    let mut revocation = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    revocation.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("revocation creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("revocation issuer fingerprint"),
    ];
    revocation.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("revocation issuer key ID"),
    ];
    let revocation = revocation
        .sign_key(primary, &Password::empty(), primary.public_key())
        .expect("create primary revocation");
    revoked.details.revocation_signatures.push(revocation);
    assert_sign_key_not_found(
        &revoked
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor revoked key"),
        &fingerprint,
    );
}

fn generated_material(kind: OpenPgpKeyKind, rsa_bits: u32) -> OpenPgpKeyMaterial {
    OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: kind as i32,
            user_id: "Agent Test <agent@example.test>".to_owned(),
            rsa_bits,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: None,
        })
        .expect("generate agent test certificate")
        .as_slice(),
    )
    .expect("decode generated agent key material")
}

fn filtered_agent_private_keys() -> [(String, Vec<u8>); 2] {
    let source = generated_material(OpenPgpKeyKind::LegacyEd25519X25519, 0);
    let stream = RawPacketStream::parse(&source.private_key_armored, MAX_AGENT_PACKETS)
        .expect("parse GnuPG dummy-primary source");
    let public = RawPacketStream::parse(&source.public_key_armored, MAX_AGENT_PACKETS)
        .expect("parse Sequoia filtered-TSK public source");
    let public_primary = public
        .packets()
        .first()
        .filter(|packet| packet.tag() == PUBLIC_KEY_TAG)
        .expect("public source starts with a public primary");
    let mut sequoia = Vec::new();
    sequoia.extend_from_slice(public.raw(public_primary));
    for packet in stream.packets().iter().skip(1) {
        sequoia.extend_from_slice(stream.raw(packet));
    }
    let primary = stream
        .packets()
        .first()
        .filter(|packet| packet.tag() == SECRET_KEY_TAG)
        .expect("GnuPG source starts with a secret primary");
    let public_len =
        import_secret_packet_public_len(&stream, primary).expect("parse primary public fields");
    let primary_body = stream.body(primary);
    let mut dummy_body = Vec::with_capacity(public_len + 8);
    dummy_body.extend_from_slice(&primary_body[..public_len]);
    dummy_body.extend_from_slice(&[254, 0, 101, 0, b'G', b'N', b'U', 1]);
    let mut dummy = Vec::new();
    write_fixed_packet(SECRET_KEY_TAG, &dummy_body, &mut dummy)
        .expect("serialize GnuPG dummy primary");
    for packet in stream.packets().iter().skip(1) {
        dummy.extend_from_slice(stream.raw(packet));
    }
    let imported = OpenPgpKeyImportResult::decode(
        import_key_request(OpenPgpKeyImportRequest {
            key_data: dummy,
            passphrase_utf8: None,
            reference_time_epoch_seconds: Some(TEST_TIME),
        })
        .expect("import GnuPG dummy-primary key")
        .as_slice(),
    )
    .expect("decode GnuPG dummy-primary import");
    let normalized = match imported.result {
        Some(open_pgp_key_import_result::Result::Success(success)) => success
            .key_material
            .expect("normalized GnuPG key material")
            .private_key_armored
            .clone(),
        result => panic!("expected normalized GnuPG key import, got {result:?}"),
    };
    [
        ("Sequoia filtered TSK".to_owned(), sequoia),
        ("normalized GnuPG dummy-primary TSK".to_owned(), normalized),
    ]
}

fn generated_agent_signer(key_type: KeyType, seed: u64) -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(key_type)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Agent ECDSA <agent-ecdsa@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build agent signing certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate agent signing certificate")
}

fn generated_agent_decryptor(curve: ECCCurve, seed: u64) -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::ECDSA(ECCCurve::P256))
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Agent ECDH <agent-ecdh@example.test>".to_owned())
        .passphrase(None)
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V4)
                .key_type(KeyType::ECDH(curve))
                .can_encrypt(EncryptionCaps::All)
                .created_at(Timestamp::from_secs(TEST_TIME as u32))
                .passphrase(None)
                .build()
                .expect("build agent ECDH subkey"),
        )
        .build()
        .expect("build agent ECDH certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate agent ECDH certificate")
}

fn brainpool_curves() -> [ECCCurve; 3] {
    [
        ECCCurve::BrainpoolP256r1,
        ECCCurve::BrainpoolP384r1,
        ECCCurve::BrainpoolP512r1,
    ]
}

fn packet_curve(packet: SecretPacketRef<'_>) -> ECCCurve {
    match packet.public_key().public_params() {
        PublicParams::ECDSA(params) => params.curve(),
        PublicParams::ECDH(params) => params.curve(),
        params => panic!("expected elliptic-curve parameters, got {params:?}"),
    }
}

fn rewrite_first_ecc_curve(
    private_key: &[u8],
    algorithm: PublicKeyAlgorithm,
    curve: &ECCCurve,
) -> Vec<u8> {
    let stream = RawPacketStream::parse(private_key, MAX_AGENT_PACKETS)
        .expect("parse ECC source certificate");
    let algorithm_id = u8::from(algorithm);
    let mut rewritten = false;
    let mut output = Vec::new();
    for packet in stream.packets() {
        if !rewritten && matches!(packet.tag(), SECRET_KEY_TAG | SECRET_SUBKEY_TAG) {
            let mut body = stream.body(packet);
            if body.first() == Some(&4) && body.get(5) == Some(&algorithm_id) {
                let oid_length = usize::from(*body.get(6).expect("ECC curve OID length"));
                let oid_end = 7_usize
                    .checked_add(oid_length)
                    .filter(|end| *end <= body.len())
                    .expect("ECC curve OID body");
                let oid = curve.oid();
                let encoded_length = u8::try_from(oid.len()).expect("ECC curve OID length fits");
                body.splice(6..oid_end, std::iter::once(encoded_length).chain(oid));
                write_fixed_packet(packet.tag(), body.as_slice(), &mut output)
                    .expect("serialize rewritten ECC key packet");
                rewritten = true;
                continue;
            }
        }
        output.extend_from_slice(stream.raw(packet));
    }
    assert!(rewritten, "source certificate contains target ECC key");
    output
}

fn assert_sign_invalid(
    private_key: &[u8],
    preferred_fingerprint: &str,
    hash_algorithm: &str,
    hash: Vec<u8>,
) {
    assert_eq!(
        sign_request(OpenPgpAgentSignRequest {
            private_key: private_key.to_vec(),
            preferred_fingerprint: preferred_fingerprint.to_owned(),
            hash_algorithm: hash_algorithm.to_owned(),
            hash,
            candidate_revocation_keys: Vec::new(),
        }),
        Err(crate::PrimitiveError::InvalidArgument),
    );
}

fn assert_sign_unsupported(
    private_key: &[u8],
    preferred_fingerprint: &str,
    hash_algorithm: &str,
    hash: Vec<u8>,
) {
    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key: private_key.to_vec(),
            preferred_fingerprint: preferred_fingerprint.to_owned(),
            hash_algorithm: hash_algorithm.to_owned(),
            hash,
            candidate_revocation_keys: Vec::new(),
        })
        .expect("unsupported agent signing algorithm is a typed result")
        .as_slice(),
    )
    .expect("decode unsupported agent signing result");
    assert!(matches!(
        response.result,
        Some(open_pgp_agent_sign_result::Result::Error(error))
            if error.reason == OpenPgpAgentErrorReason::UnsupportedAlgorithm as i32
    ));
}

fn assert_sign_key_not_found(private_key: &[u8], preferred_fingerprint: &str) {
    let response = OpenPgpAgentSignResult::decode(
        sign_request(OpenPgpAgentSignRequest {
            private_key: private_key.to_vec(),
            preferred_fingerprint: preferred_fingerprint.to_owned(),
            hash_algorithm: "sha256".to_owned(),
            hash: vec![0x5a; 32],
            candidate_revocation_keys: Vec::new(),
        })
        .expect("unusable signing selector is a typed result")
        .as_slice(),
    )
    .expect("decode unusable signing result");
    assert!(matches!(
        response.result,
        Some(open_pgp_agent_sign_result::Result::Error(error))
            if error.reason == OpenPgpAgentErrorReason::KeyNotFound as i32
    ));
}

fn secret_packets(key: &ParsedSecretCertificate) -> impl Iterator<Item = SecretPacketRef<'_>> {
    key.primary()
        .map(SecretPacketRef::Primary)
        .into_iter()
        .chain(key.subkeys().iter().map(SecretPacketRef::Subkey))
}

fn verify_with_packet(
    packet: SecretPacketRef<'_>,
    hash: HashAlgorithm,
    digest: &[u8],
    signature: &SignatureBytes,
) {
    match packet {
        SecretPacketRef::Primary(key) => key
            .public_key()
            .verify(hash, digest, signature)
            .expect("agent signature verifies"),
        SecretPacketRef::Subkey(key) => key
            .public_key()
            .verify(hash, digest, signature)
            .expect("agent signature verifies"),
    }
}

fn signature_components(input: &[u8], algorithm: &[u8]) -> Vec<(Vec<u8>, Vec<u8>)> {
    let SExpr::List(outer) = CanonicalCursor::parse(input).expect("parse signature expression")
    else {
        panic!("signature expression must be a list");
    };
    assert!(matches!(outer.first(), Some(SExpr::Atom(b"sig-val"))));
    let Some(SExpr::List(signature)) = outer.get(1) else {
        panic!("signature body must be a list");
    };
    assert!(matches!(signature.first(), Some(SExpr::Atom(name)) if *name == algorithm));
    signature[1..]
        .iter()
        .map(|component| {
            let SExpr::List(component) = component else {
                panic!("signature component must be a list");
            };
            let [SExpr::Atom(name), SExpr::Atom(value)] = component.as_slice() else {
                panic!("signature component must contain a name and value");
            };
            (name.to_vec(), value.to_vec())
        })
        .collect()
}

fn ecdh_enc_val(ephemeral: &[u8], wrapped: &[u8]) -> Vec<u8> {
    let mut writer = CanonicalWriter::new(MAX_AGENT_OUTPUT_BYTES).expect("canonical writer");
    writer.byte(b'(').expect("outer list");
    writer.atom(b"enc-val").expect("enc-val atom");
    writer.byte(b'(').expect("ECDH list");
    writer.atom(b"ecdh").expect("ECDH atom");
    for (name, value) in [(b"e".as_slice(), ephemeral), (b"s".as_slice(), wrapped)] {
        writer.byte(b'(').expect("parameter list");
        writer.atom(name).expect("parameter name");
        writer.atom(value).expect("parameter value");
        writer.byte(b')').expect("parameter close");
    }
    writer.byte(b')').expect("ECDH close");
    writer.byte(b')').expect("outer close");
    writer.output.to_vec()
}

fn decrypt_value(result: &OpenPgpAgentDecryptResult) -> Vec<u8> {
    let canonical = match result.result.as_ref() {
        Some(open_pgp_agent_decrypt_result::Result::Success(success)) => {
            success.canonical_sexp.as_slice()
        }
        result => panic!("expected decrypt success, got {result:?}"),
    };
    let SExpr::List(value) = CanonicalCursor::parse(canonical).expect("parse value expression")
    else {
        panic!("value expression must be a list");
    };
    let [SExpr::Atom(b"value"), SExpr::Atom(value)] = value.as_slice() else {
        panic!("expected canonical value expression");
    };
    value.to_vec()
}
