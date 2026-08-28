use std::io::Cursor;

use pgp::{
    composed::{Deserializable, SignedSecretKey},
    crypto::{
        aead::AeadAlgorithm, hash::HashAlgorithm, public_key::PublicKeyAlgorithm,
        sym::SymmetricKeyAlgorithm,
    },
    packet::{
        Features, KeyFlags, RevocationCode, Signature, SignatureConfig, SignatureType,
        SignatureVersion, Subpacket, SubpacketData, UserAttribute, UserId,
    },
    ser::Serialize,
    types::{
        CompressionAlgorithm, Duration, Fingerprint, KeyDetails, KeyId, KeyVersion, Password,
        RevocationKey, RevocationKeyClass, SignatureBytes, SignedUser, SignedUserAttribute,
        SigningKey, Tag, Timestamp,
    },
};
use prost::{Message, bytes::Bytes};

use super::{acceptance::*, budget::*, evaluation::*, model::*, selection::*};
use crate::{
    openpgp::adapter::key::generate as generate_key_request,
    openpgp::adapter::wire::{OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial},
    openpgp::certificate::PublicComponent,
    openpgp::crypto::verification::*,
    openpgp::format::hex_upper,
    openpgp::key::generate_rsa_certificate_for_test,
};

const TEST_TIME: u64 = 1_700_000_000;

#[test]
fn signature_versions_match_only_registered_signer_key_versions() {
    for (signature_version, signer_version) in [
        (SignatureVersion::V3, KeyVersion::V3),
        (SignatureVersion::V3, KeyVersion::V4),
        (SignatureVersion::V4, KeyVersion::V4),
        (SignatureVersion::V6, KeyVersion::V6),
    ] {
        assert!(
            signature_version_matches_signer(signature_version, signer_version),
            "registered pair: {signature_version:?}/{signer_version:?}",
        );
    }

    for (signature_version, signer_version) in [
        (SignatureVersion::V2, KeyVersion::V3),
        (SignatureVersion::V2, KeyVersion::V4),
        (SignatureVersion::V3, KeyVersion::V2),
        (SignatureVersion::V3, KeyVersion::V5),
        (SignatureVersion::V4, KeyVersion::V3),
        (SignatureVersion::V4, KeyVersion::V5),
        (SignatureVersion::V4, KeyVersion::V6),
        (SignatureVersion::V5, KeyVersion::V5),
        (SignatureVersion::V6, KeyVersion::V4),
        (SignatureVersion::Other(7), KeyVersion::Other(7)),
    ] {
        assert!(
            !signature_version_matches_signer(signature_version, signer_version),
            "unsupported pair: {signature_version:?}/{signer_version:?}",
        );
    }
}

fn generated_test_secret(user_id: &str) -> SignedSecretKey {
    generated_test_secret_with_kind(user_id, OpenPgpKeyKind::LegacyEd25519X25519, 0)
}

fn generated_test_secret_with_kind(
    user_id: &str,
    kind: OpenPgpKeyKind,
    rsa_bits: u32,
) -> SignedSecretKey {
    generated_test_secret_at_with_kind(user_id, kind, rsa_bits, TEST_TIME)
}

fn generated_test_secret_at_with_kind(
    user_id: &str,
    kind: OpenPgpKeyKind,
    rsa_bits: u32,
    creation_time: u64,
) -> SignedSecretKey {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: kind as i32,
            user_id: user_id.to_owned(),
            rsa_bits,
            creation_time_epoch_seconds: creation_time,
            expiration_seconds: None,
        })
        .expect("generate certificate")
        .as_slice(),
    )
    .expect("decode generated certificate");
    SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
        .expect("parse generated certificate")
        .0
}

#[test]
fn rsa_certificate_authentication_rejects_1024_bits_and_accepts_2048_bits() {
    for (bits, expected_authenticated) in [(1_024, false), (2_048, true)] {
        let secret = generate_rsa_certificate_for_test(
            &format!("RSA {bits} <rsa-{bits}@example.test>"),
            Timestamp::from_secs(TEST_TIME as u32),
            bits,
        )
        .expect("generate RSA policy fixture");
        let certificate = secret.to_public_key();
        let candidates = certificate_components(&certificate).collect::<Vec<_>>();
        let policy = validate_certificate(
            &certificate,
            &candidates,
            TEST_TIME + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate RSA certificate policy");

        assert_eq!(
            key_signature_verification_acceptable(&certificate.primary_key),
            expected_authenticated,
            "RSA-{bits} primary verification policy",
        );
        assert_eq!(
            policy.primary.authenticated, expected_authenticated,
            "RSA-{bits} primary authentication",
        );
        assert_eq!(
            policy.authenticated_user_ids().count(),
            usize::from(expected_authenticated),
            "RSA-{bits} identity authentication",
        );
        assert!(policy.subkeys.iter().all(|subkey| {
            subkey.authenticated == expected_authenticated
                && key_signature_verification_acceptable(subkey.key) == expected_authenticated
        }));
    }
}

#[test]
fn signature_algorithm_policy_accepts_modern_signers_and_rejects_legacy_algorithms() {
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);

    for algorithm in [
        PublicKeyAlgorithm::RSA,
        PublicKeyAlgorithm::ECDSA,
        PublicKeyAlgorithm::EdDSALegacy,
        PublicKeyAlgorithm::Ed25519,
    ] {
        assert!(signature_algorithm_acceptable(algorithm), "{algorithm:?}");
        assert!(can_sign(algorithm, Some(&signing_flags)), "{algorithm:?}");
    }

    for algorithm in [PublicKeyAlgorithm::DSA, PublicKeyAlgorithm::Elgamal] {
        assert!(!signature_algorithm_acceptable(algorithm), "{algorithm:?}");
        assert!(!can_sign(algorithm, None), "{algorithm:?}");
        assert!(!can_sign(algorithm, Some(&signing_flags)), "{algorithm:?}");
    }

    assert!(!can_sign(PublicKeyAlgorithm::ECDH, Some(&signing_flags),));
}

#[test]
fn encryption_algorithm_policy_accepts_modern_recipients_and_rejects_elgamal() {
    let mut encryption_flags = KeyFlags::default();
    encryption_flags.set_encrypt_comms(true);

    for algorithm in [
        PublicKeyAlgorithm::RSA,
        PublicKeyAlgorithm::RSAEncrypt,
        PublicKeyAlgorithm::ECDH,
        PublicKeyAlgorithm::X25519,
        PublicKeyAlgorithm::X448,
    ] {
        assert!(encryption_algorithm_acceptable(algorithm), "{algorithm:?}");
        assert!(
            can_encrypt(algorithm, Some(&encryption_flags)),
            "{algorithm:?}"
        );
    }

    for algorithm in [
        PublicKeyAlgorithm::Elgamal,
        PublicKeyAlgorithm::ElgamalEncrypt,
    ] {
        assert!(!encryption_algorithm_acceptable(algorithm), "{algorithm:?}");
        assert!(!can_encrypt(algorithm, None), "{algorithm:?}");
        assert!(
            !can_encrypt(algorithm, Some(&encryption_flags)),
            "{algorithm:?}"
        );
    }

    assert!(!can_encrypt(
        PublicKeyAlgorithm::Ed25519,
        Some(&encryption_flags),
    ));
}

fn identity_signature(
    secret: &SignedSecretKey,
    signature_type: SignatureType,
    tag: Tag,
    identity: &impl Serialize,
    creation_time: u32,
    reason: Option<RevocationCode>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        signature_type,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    );
    if let Some(reason) = reason {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
                .expect("revocation reason"),
        );
    }
    config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            tag,
            identity,
        )
        .expect("sign identity signature")
}

fn identity_certification_revocation(
    target: &SignedSecretKey,
    signer: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    creation_time: u32,
    additional_hashed_subpackets: Vec<Subpacket>,
    unhashed_subpackets: Vec<Subpacket>,
) -> Signature {
    identity_certification_revocation_with_options(
        target,
        signer,
        tag,
        identity,
        creation_time,
        IdentityCertificationRevocationOptions {
            hash_algorithm: HashAlgorithm::Sha256,
            additional_hashed_subpackets,
            unhashed_subpackets,
        },
    )
}

struct IdentityCertificationRevocationOptions {
    hash_algorithm: HashAlgorithm,
    additional_hashed_subpackets: Vec<Subpacket>,
    unhashed_subpackets: Vec<Subpacket>,
}

fn identity_certification_revocation_with_options(
    target: &SignedSecretKey,
    signer: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    creation_time: u32,
    options: IdentityCertificationRevocationOptions,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        signer.primary_key.algorithm(),
        options.hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("certification revocation creation time"),
        Subpacket::regular(SubpacketData::RevocationReason(
            RevocationCode::CertUserIdInvalid,
            Vec::new().into(),
        ))
        .expect("certification revocation reason"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signer.primary_key.fingerprint(),
        ))
        .expect("certification revocation issuer fingerprint"),
    ];
    config
        .hashed_subpackets
        .extend(options.additional_hashed_subpackets);
    config.unhashed_subpackets = options.unhashed_subpackets;
    config
        .sign_certification(
            &signer.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            tag,
            identity,
        )
        .expect("sign certification revocation")
}

fn expiring_revocation_config(
    secret: &SignedSecretKey,
    signature_type: SignatureType,
    reason: RevocationCode,
) -> SignatureConfig {
    let mut config = SignatureConfig::v4(
        signature_type,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
            1,
        )))
        .expect("revocation expiration time"),
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("revocation reason"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("revocation issuer key ID"),
    ];
    config
}

fn key_revocation(
    secret: &SignedSecretKey,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
) -> Signature {
    key_revocation_with_reason(
        secret,
        creation_time,
        hash_algorithm,
        RevocationCode::KeyCompromised,
    )
}

fn key_revocation_with_reason(
    secret: &SignedSecretKey,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    reason: RevocationCode,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        secret.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("revocation reason"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("revocation issuer key ID"),
    ];
    config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign key revocation")
}

fn v3_key_revocation(secret: &SignedSecretKey, creation_time: u32) -> Signature {
    let config = SignatureConfig::v3(
        SignatureType::KeyRevocation,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
        Timestamp::from_secs(creation_time),
        secret.primary_key.legacy_key_id(),
    );

    let public_key = secret.primary_key.public_key();
    let mut key_body = Vec::with_capacity(public_key.write_len());
    public_key
        .to_writer(&mut key_body)
        .expect("serialize v3 public key body");

    let mut hasher = HashAlgorithm::Sha256
        .new_hasher()
        .expect("create SHA-256 hasher");
    hasher.update(&[0x99]);
    hasher.update(
        &u16::try_from(key_body.len())
            .expect("v3 public key body length")
            .to_be_bytes(),
    );
    hasher.update(&key_body);
    config
        .hash_signature_data(&mut hasher)
        .expect("hash v3 revocation metadata");
    let hash = hasher.finalize();
    let signed_hash_value = [hash[0], hash[1]];
    let signature = secret
        .primary_key
        .sign(&Password::empty(), HashAlgorithm::Sha256, &hash)
        .expect("sign v3 key revocation");

    Signature::from_config(config, signed_hash_value, signature)
        .expect("construct v3 key revocation")
}

fn self_certification(
    secret: &SignedSecretKey,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    let user_id = &secret.details.users[0].id;
    user_id_self_certification(
        secret,
        user_id,
        creation_time,
        hash_algorithm,
        additional_subpackets,
    )
}

fn user_id_self_certification(
    secret: &SignedSecretKey,
    user_id: &UserId,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    );
    config.hashed_subpackets.extend(additional_subpackets);
    config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            user_id,
        )
        .expect("sign self certification")
}

fn user_attribute_self_certification(
    secret: &SignedSecretKey,
    attribute: &UserAttribute,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    );
    config.hashed_subpackets.extend(additional_subpackets);
    config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserAttribute,
            attribute,
        )
        .expect("sign User Attribute self certification")
}

fn signed_user_id(
    secret: &SignedSecretKey,
    user_id: &str,
    creation_time: u32,
    additional_subpackets: Vec<Subpacket>,
) -> SignedUser {
    let user_id = UserId::from_str(Default::default(), user_id).expect("create User ID");
    let certification = user_id_self_certification(
        secret,
        &user_id,
        creation_time,
        HashAlgorithm::Sha256,
        additional_subpackets,
    );
    SignedUser::new(user_id, vec![certification])
}

fn direct_self_signature(
    secret: &SignedSecretKey,
    creation_time: u32,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    direct_self_signature_with_hash(
        secret,
        creation_time,
        HashAlgorithm::Sha256,
        additional_subpackets,
    )
}

fn direct_self_signature_with_hash(
    secret: &SignedSecretKey,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    );
    config.hashed_subpackets.extend(additional_subpackets);
    config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign Direct Key signature")
}

fn direct_certification_revocation(
    target: &SignedSecretKey,
    signer: &SignedSecretKey,
    creation_time: u32,
    additional_hashed_subpackets: Vec<Subpacket>,
    unhashed_subpackets: Vec<Subpacket>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        signer.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signer.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    config
        .hashed_subpackets
        .extend(additional_hashed_subpackets);
    config.unhashed_subpackets = unhashed_subpackets;

    // pgp 0.20 intentionally rejects type 0x30 in `sign_key`, although
    // RFC 9580 defines the same primary-key-only hash input for this case.
    let unsigned = Signature::from_config(config.clone(), [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("construct unsigned direct certification revocation");
    let digest = direct_key_signature_digest(&unsigned, target.primary_key.public_key())
        .expect("hash direct certification revocation");
    let signature = signer
        .primary_key
        .sign(&Password::empty(), HashAlgorithm::Sha256, &digest)
        .expect("sign direct certification revocation");
    Signature::from_config(config, [digest[0], digest[1]], signature)
        .expect("construct direct certification revocation")
}

fn subkey_binding_at(
    secret: &SignedSecretKey,
    subkey_index: usize,
    creation_time: u32,
) -> Signature {
    let primary = &secret.primary_key;
    let signed_subkey = &secret.secret_subkeys[subkey_index];
    let subkey = signed_subkey.key.public_key();
    let mut config = signed_subkey
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_)
        )
    });
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("subkey binding creation time"),
    );
    config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), subkey)
        .expect("sign subkey binding")
}

fn subkey_revocation_at(
    secret: &SignedSecretKey,
    subkey_index: usize,
    creation_time: u32,
    reason: RevocationCode,
) -> Signature {
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[subkey_index].key.public_key();
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("subkey revocation creation time"),
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("subkey revocation reason"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("subkey revocation issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("subkey revocation issuer key ID"),
    ];
    config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), subkey)
        .expect("sign subkey revocation")
}

fn direct_signature_target(target: &Signature, secret: &SignedSecretKey) -> Subpacket {
    let config = target.config().expect("target signature config");
    let digest = direct_key_signature_digest(target, secret.primary_key.public_key())
        .expect("hash target Direct Key signature");
    signature_target_subpacket(config.pub_alg, config.hash_alg, digest, true)
}

fn signature_target_subpacket(
    public_key_algorithm: PublicKeyAlgorithm,
    hash_algorithm: HashAlgorithm,
    digest: Vec<u8>,
    critical: bool,
) -> Subpacket {
    let data = SubpacketData::SignatureTarget(public_key_algorithm, hash_algorithm, digest.into());
    if critical {
        Subpacket::critical(data)
    } else {
        Subpacket::regular(data)
    }
    .expect("signature target")
}

fn identity_signature_target(
    target: &Signature,
    secret: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    critical: bool,
) -> Subpacket {
    let config = target.config().expect("target signature config");
    let digest =
        certification_signature_digest(target, secret.primary_key.public_key(), tag, identity)
            .expect("hash target identity certification");
    let signed_hash_value = target
        .signed_hash_value()
        .expect("target certification signed-hash prefix");
    assert_eq!(
        digest.get(..signed_hash_value.len()),
        Some(signed_hash_value.as_slice()),
        "target digest must reproduce the certification's signed hash",
    );
    signature_target_subpacket(config.pub_alg, config.hash_alg, digest, critical)
}

fn signature_with_invalid_material(signature: &Signature) -> Signature {
    let signed_hash = signature.signed_hash_value().expect("signed hash prefix");
    Signature::from_config(
        signature.config().expect("signature config").clone(),
        [signed_hash[0], signed_hash[1]],
        SignatureBytes::Mpis(Vec::new()),
    )
    .expect("construct signature with invalid material")
}

fn signature_with_unhashed_issuer_hints(
    signature: &Signature,
    issuer_hints: Vec<Subpacket>,
) -> Signature {
    let mut config = signature.config().expect("signature config").clone();
    config.unhashed_subpackets = issuer_hints;
    let signed_hash = signature.signed_hash_value().expect("signed hash prefix");
    Signature::from_config(
        config,
        [signed_hash[0], signed_hash[1]],
        signature
            .signature()
            .cloned()
            .expect("cryptographic signature material"),
    )
    .expect("rebuild signature with advisory issuer hints")
}

fn certificate_with_only_direct_signatures(
    mut secret: SignedSecretKey,
    signatures: Vec<Signature>,
) -> SignedSecretKey {
    secret.details.direct_signatures = signatures;
    secret.details.users.clear();
    secret.details.user_attributes.clear();
    secret
}

#[test]
fn signature_expiration_uses_the_last_hashed_occurrence() {
    let secret = generated_test_secret("Duplicate Expiration <duplicate@example.test>");
    let signature_with_expirations = |durations: &[u32]| {
        direct_self_signature(
            &secret,
            TEST_TIME as u32,
            durations
                .iter()
                .copied()
                .map(|duration| {
                    Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                        duration,
                    )))
                    .expect("signature expiration time")
                })
                .collect(),
        )
    };

    let bounded_then_zero = signature_with_expirations(&[300, 0]);
    assert_eq!(signature_expiration_seconds(&bounded_then_zero), None);

    let zero_then_bounded = signature_with_expirations(&[0, 300]);
    assert_eq!(signature_expiration_seconds(&zero_then_bounded), Some(300));

    let single_zero = signature_with_expirations(&[0]);
    assert_eq!(signature_expiration_seconds(&single_zero), None);

    let absent = signature_with_expirations(&[]);
    assert_eq!(signature_expiration_seconds(&absent), None);
}

#[test]
fn final_zero_signature_expiration_keeps_a_direct_signature_live() {
    let secret = generated_test_secret("Unexpired Direct <unexpired@example.test>");
    let direct = direct_self_signature(
        &secret,
        TEST_TIME as u32,
        vec![
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                1,
            )))
            .expect("bounded signature expiration time"),
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                0,
            )))
            .expect("unbounded signature expiration time"),
        ],
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![direct]);

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate duplicate signature expiration times");

    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_expiration_seconds),
        None,
    );
}

#[test]
fn untargeted_direct_certification_revocation_cancels_a_direct_key_signature() {
    let secret = generated_test_secret("Direct Revocation <direct-revocation@example.test>");
    let direct = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let unrelated = direct_self_signature(&secret, (TEST_TIME + 11) as u32, Vec::new());
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        vec![direct_signature_target(&unrelated, &secret)],
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![direct, revocation]);

    let public = secret.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut budget,
    )
    .expect("evaluate untargeted direct certification revocation");

    // An unhashed Signature Target is mutable metadata and cannot narrow
    // the otherwise untargeted, authenticated revocation.
    assert!(!policy.primary.authenticated);
    assert!(policy.primary.effective_signature.is_none());
    assert_eq!(budget.signature_target_digests, 0);
}

#[test]
fn targeted_direct_certification_revocation_cancels_only_its_target() {
    let secret = generated_test_secret("Targeted Direct <targeted-direct@example.test>");
    let older = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let newer = direct_self_signature(&secret, (TEST_TIME + 20) as u32, Vec::new());
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 30) as u32,
        vec![direct_signature_target(&newer, &secret)],
        Vec::new(),
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![older, newer, revocation]);

    let public = secret.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut budget,
    )
    .expect("evaluate targeted direct certification revocation");

    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
    assert_eq!(
        budget.signature_target_digests, 2,
        "each same-algorithm candidate is hashed once, not once per pair",
    );
}

#[test]
fn malformed_hashed_signature_target_does_not_cancel_a_direct_signature() {
    let secret = generated_test_secret("Malformed Target <malformed-target@example.test>");
    let direct = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let config = direct.config().expect("Direct Key signature config");
    let malformed_target = Subpacket::critical(SubpacketData::SignatureTarget(
        config.pub_alg,
        config.hash_alg,
        Bytes::from_static(b"not a full digest"),
    ))
    .expect("malformed signature target");
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        vec![malformed_target],
        Vec::new(),
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![direct, revocation]);

    let public = secret.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut budget,
    )
    .expect("evaluate malformed direct Signature Target");

    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
    assert_eq!(budget.signature_target_digests, 0);
}

#[test]
fn duplicate_and_unsupported_hashed_signature_targets_fail_closed_without_hashing() {
    let secret = generated_test_secret("Invalid Targets <invalid-targets@example.test>");
    let direct = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let valid_target = direct_signature_target(&direct, &secret);
    let config = direct.config().expect("Direct Key signature config");
    let target_digest = direct_key_signature_digest(&direct, secret.primary_key.public_key())
        .expect("hash target Direct Key signature");
    let cases = [
        (
            "duplicate selector",
            vec![valid_target.clone(), valid_target],
        ),
        (
            "unsupported public-key algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    PublicKeyAlgorithm::Unknown(99),
                    config.hash_alg,
                    target_digest.clone().into(),
                ))
                .expect("unsupported public-key target"),
            ],
        ),
        (
            "unsupported hash algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    config.pub_alg,
                    HashAlgorithm::Other(111),
                    Bytes::from(vec![0; target_digest.len()]),
                ))
                .expect("unsupported hash target"),
            ],
        ),
    ];

    for (case, selectors) in cases {
        let revocation = direct_certification_revocation(
            &secret,
            &secret,
            (TEST_TIME + 20) as u32,
            selectors,
            Vec::new(),
        );
        let candidate = certificate_with_only_direct_signatures(
            secret.clone(),
            vec![direct.clone(), revocation],
        );
        let public = candidate.to_public_key();
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut budget,
        )
        .expect("evaluate invalid direct Signature Target");

        assert!(policy.primary.authenticated, "{case}");
        assert_eq!(
            policy
                .primary
                .effective_signature
                .and_then(signature_creation_time),
            Some((TEST_TIME + 10) as u32),
            "{case}",
        );
        assert_eq!(budget.signature_target_digests, 0, "{case}");
    }
}

#[test]
fn repeated_mixed_algorithm_targets_hash_each_direct_candidate_once() {
    let secret = generated_test_secret("Target Flood <target-flood@example.test>");
    let sha256 = direct_self_signature_with_hash(
        &secret,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let sha512 = direct_self_signature_with_hash(
        &secret,
        (TEST_TIME + 11) as u32,
        HashAlgorithm::Sha512,
        Vec::new(),
    );
    let sha256_target = direct_signature_target(&sha256, &secret);
    let sha512_target = direct_signature_target(&sha512, &secret);
    let mut signatures = vec![sha256, sha512];
    for index in 0..16 {
        let selector = if index % 2 == 0 {
            sha256_target.clone()
        } else {
            sha512_target.clone()
        };
        signatures.push(direct_certification_revocation(
            &secret,
            &secret,
            (TEST_TIME + 20 + index) as u32,
            vec![selector],
            Vec::new(),
        ));
    }
    let secret = certificate_with_only_direct_signatures(secret, signatures);

    let public = secret.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut budget,
    )
    .expect("evaluate repeated mixed-algorithm targets");

    assert!(!policy.primary.authenticated);
    assert_eq!(
        budget.signature_target_digests, 2,
        "each target signature is hashed once with its own declared hash algorithm",
    );
}

#[test]
fn targeted_user_id_self_revocations_accept_critical_and_noncritical_targets() {
    for critical in [false, true] {
        let mut secret = generated_test_secret("Targeted User ID <targeted-uid@example.test>");
        let user_id = secret.details.users[0].id.clone();
        let certification = user_id_self_certification(
            &secret,
            &user_id,
            (TEST_TIME + 10) as u32,
            HashAlgorithm::Sha256,
            Vec::new(),
        );
        let target =
            identity_signature_target(&certification, &secret, Tag::UserId, &user_id, critical);
        // The target certification's signed-data hash omits unhashed
        // subpackets. Adding a matching advisory issuer after creating the
        // selector must not change the target's identity.
        let certification = signature_with_unhashed_issuer_hints(
            &certification,
            vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(
                    secret.primary_key.legacy_key_id(),
                ))
                .expect("advisory certification issuer"),
            ],
        );
        let revocation = identity_certification_revocation(
            &secret,
            &secret,
            Tag::UserId,
            &user_id,
            (TEST_TIME + 20) as u32,
            vec![target],
            Vec::new(),
        );
        secret.details.users[0].signatures = vec![certification, revocation];

        let public = secret.to_public_key();
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut budget,
        )
        .expect("evaluate targeted User ID self-revocation");

        assert_eq!(
            policy.user_ids[0].revocation_status,
            RevocationStatus::Revoked,
            "critical: {critical}",
        );
        assert_eq!(budget.signature_target_digests, 1, "critical: {critical}");
    }
}

#[test]
fn targeted_user_attribute_self_revocations_accept_critical_and_noncritical_targets() {
    for critical in [false, true] {
        let mut secret = generated_test_secret("Targeted Attribute <targeted-uat@example.test>");
        let attribute = UserAttribute::new_image(Bytes::from_static(b"targeted image"))
            .expect("user attribute");
        let certification = user_attribute_self_certification(
            &secret,
            &attribute,
            (TEST_TIME + 10) as u32,
            HashAlgorithm::Sha256,
            Vec::new(),
        );
        let revocation = identity_certification_revocation(
            &secret,
            &secret,
            Tag::UserAttribute,
            &attribute,
            (TEST_TIME + 20) as u32,
            vec![identity_signature_target(
                &certification,
                &secret,
                Tag::UserAttribute,
                &attribute,
                critical,
            )],
            Vec::new(),
        );
        secret.details.direct_signatures.clear();
        secret.details.users.clear();
        secret.details.user_attributes = vec![SignedUserAttribute::new(
            attribute,
            vec![certification, revocation],
        )];

        let public = secret.to_public_key();
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut budget,
        )
        .expect("evaluate targeted User Attribute self-revocation");

        assert_eq!(
            policy.user_attributes[0].revocation_status,
            RevocationStatus::Revoked,
            "critical: {critical}",
        );
        assert_eq!(budget.signature_target_digests, 1, "critical: {critical}");
    }
}

#[test]
fn user_id_signature_target_revokes_only_its_match_and_falls_back() {
    let base = generated_test_secret("Selected Target <selected-target@example.test>");
    let user_id = base.details.users[0].id.clone();
    let older = user_id_self_certification(
        &base,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let newer = user_id_self_certification(
        &base,
        &user_id,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let unrelated_user_id =
        UserId::from_str(Default::default(), "Unrelated <unrelated@example.test>")
            .expect("unrelated User ID");
    let unrelated_newer = user_id_self_certification(
        &base,
        &unrelated_user_id,
        (TEST_TIME + 25) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );

    for (case, target, expected_time) in [
        (
            "older",
            identity_signature_target(&older, &base, Tag::UserId, &user_id, true),
            TEST_TIME + 20,
        ),
        (
            "newer",
            identity_signature_target(&newer, &base, Tag::UserId, &user_id, true),
            TEST_TIME + 10,
        ),
        (
            "unrelated newer",
            identity_signature_target(
                &unrelated_newer,
                &base,
                Tag::UserId,
                &unrelated_user_id,
                true,
            ),
            TEST_TIME + 20,
        ),
    ] {
        let revocation = identity_certification_revocation(
            &base,
            &base,
            Tag::UserId,
            &user_id,
            (TEST_TIME + 30) as u32,
            vec![target],
            Vec::new(),
        );
        let mut candidate = base.clone();
        candidate.details.users[0].signatures = vec![older.clone(), newer.clone(), revocation];
        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 40,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate selected certification Signature Target");

        assert_eq!(
            policy.user_ids[0]
                .effective_signature
                .and_then(signature_creation_time),
            Some(expected_time as u32),
            "case: {case}",
        );
        assert_eq!(
            policy.user_ids[0].revocation_status,
            RevocationStatus::NotRevoked,
            "case: {case}",
        );
        assert!(policy.user_ids[0].authenticated(), "case: {case}");
    }
}

#[test]
fn invalid_user_id_signature_targets_fail_closed() {
    let base = generated_test_secret("Invalid Identity Target <invalid-identity@example.test>");
    let user_id = base.details.users[0].id.clone();
    let certification = user_id_self_certification(
        &base,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let config = certification.config().expect("target certification config");
    let digest = certification_signature_digest(
        &certification,
        base.primary_key.public_key(),
        Tag::UserId,
        &user_id,
    )
    .expect("target certification digest");
    let valid = identity_signature_target(&certification, &base, Tag::UserId, &user_id, true);
    let cases = [
        (
            "noncritical digest-size mismatch",
            vec![
                Subpacket::regular(SubpacketData::SignatureTarget(
                    config.pub_alg,
                    HashAlgorithm::Sha256,
                    Bytes::from_static(b"short"),
                ))
                .expect("short Signature Target"),
            ],
            0,
        ),
        ("duplicate", vec![valid.clone(), valid], 0),
        (
            "unsupported public-key algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    PublicKeyAlgorithm::Unknown(99),
                    HashAlgorithm::Sha256,
                    digest.clone().into(),
                ))
                .expect("unsupported public-key Signature Target"),
            ],
            0,
        ),
        (
            "unsupported hash algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    config.pub_alg,
                    HashAlgorithm::Other(111),
                    Bytes::from(vec![0; digest.len()]),
                ))
                .expect("unsupported hash Signature Target"),
            ],
            0,
        ),
        (
            "mismatched supported public-key algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    PublicKeyAlgorithm::RSA,
                    HashAlgorithm::Sha256,
                    digest.clone().into(),
                ))
                .expect("mismatched public-key Signature Target"),
            ],
            0,
        ),
        (
            "mismatched supported hash algorithm",
            vec![
                Subpacket::critical(SubpacketData::SignatureTarget(
                    config.pub_alg,
                    HashAlgorithm::Sha512,
                    Bytes::from(vec![0; 64]),
                ))
                .expect("mismatched hash Signature Target"),
            ],
            0,
        ),
        (
            "wrong full-size digest",
            vec![
                Subpacket::regular(SubpacketData::SignatureTarget(
                    config.pub_alg,
                    HashAlgorithm::Sha256,
                    Bytes::from(vec![0xa5; digest.len()]),
                ))
                .expect("wrong digest Signature Target"),
            ],
            1,
        ),
    ];

    for (case, targets, expected_digest_work) in cases {
        let revocation = identity_certification_revocation(
            &base,
            &base,
            Tag::UserId,
            &user_id,
            (TEST_TIME + 20) as u32,
            targets,
            Vec::new(),
        );
        let mut candidate = base.clone();
        candidate.details.users[0].signatures = vec![certification.clone(), revocation];
        let public = candidate.to_public_key();
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut budget,
        )
        .expect("evaluate invalid identity Signature Target");

        assert_eq!(
            policy.user_ids[0].revocation_status,
            RevocationStatus::NotRevoked,
            "case: {case}",
        );
        assert!(policy.user_ids[0].authenticated(), "case: {case}");
        assert_eq!(
            budget.signature_target_digests, expected_digest_work,
            "case: {case}",
        );
    }
}

#[test]
fn unhashed_user_id_signature_target_preserves_untargeted_revocation_behavior() {
    let mut secret = generated_test_secret("Unhashed Identity Target <unhashed@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let certification = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let unrelated = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 11) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let revocation = identity_certification_revocation(
        &secret,
        &secret,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        vec![identity_signature_target(
            &unrelated,
            &secret,
            Tag::UserId,
            &user_id,
            true,
        )],
    );
    secret.details.users[0].signatures = vec![certification, revocation];

    let public = secret.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut budget,
    )
    .expect("evaluate unhashed identity Signature Target");

    assert_eq!(
        policy.user_ids[0].revocation_status,
        RevocationStatus::Revoked,
    );
    assert_eq!(budget.signature_target_digests, 0);
}

#[test]
fn designated_revoker_can_target_a_user_id_self_certification() {
    let mut target = generated_test_secret("Targeted Owner <targeted-owner@example.test>");
    let revoker = generated_test_secret_with_kind(
        "Targeted Revoker <targeted-revoker@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    add_designated_revoker_declaration(&mut target, &revoker);
    let user_id = target.details.users[0].id.clone();
    let older = user_id_self_certification(
        &target,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let newer = user_id_self_certification(
        &target,
        &user_id,
        (TEST_TIME + 15) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    assert_ne!(
        newer.config().map(|config| config.pub_alg),
        Some(revoker.primary_key.algorithm()),
        "the selector identifies the target signature's issuer algorithm, not the revoker's",
    );
    let revocation = identity_certification_revocation(
        &target,
        &revoker,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 20) as u32,
        vec![identity_signature_target(
            &newer,
            &target,
            Tag::UserId,
            &user_id,
            true,
        )],
        Vec::new(),
    );
    target.details.users[0].signatures = vec![older, newer, revocation];

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let candidates = all_components(&[target_public.clone(), revoker_public]);
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(&target_public, &candidates, TEST_TIME + 30, &mut budget)
        .expect("evaluate targeted designated certification revocation");

    assert_eq!(
        policy.user_ids[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(
        policy.user_ids[0]
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
    assert_eq!(budget.signature_target_digests, 2);
}

#[test]
fn non_revocable_direct_signature_ignores_certification_revocation() {
    let secret = generated_test_secret("Committed Direct <committed-direct@example.test>");
    let direct = direct_self_signature(
        &secret,
        (TEST_TIME + 10) as u32,
        vec![
            Subpacket::critical(SubpacketData::Revocable(false))
                .expect("non-revocable Direct Key signature"),
        ],
    );
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        Vec::new(),
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![direct, revocation]);

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate non-revocable Direct Key signature");

    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
}

#[test]
fn invalid_or_foreign_direct_certification_revocation_is_ignored() {
    let secret = generated_test_secret("Direct Owner <direct-owner@example.test>");
    let foreign = generated_test_secret("Foreign Revoker <foreign-revoker@example.test>");
    let direct = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let valid_self_revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        Vec::new(),
    );
    let invalid = signature_with_invalid_material(&valid_self_revocation);
    let foreign = direct_certification_revocation(
        &secret,
        &foreign,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        Vec::new(),
    );

    for revocation in [invalid, foreign] {
        let candidate = certificate_with_only_direct_signatures(
            secret.clone(),
            vec![direct.clone(), revocation],
        );
        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate unauthenticated direct certification revocation");

        assert!(policy.primary.authenticated);
        assert_eq!(
            policy
                .primary
                .effective_signature
                .and_then(signature_creation_time),
            Some((TEST_TIME + 10) as u32),
        );
    }
}

#[test]
fn later_direct_signature_replaces_an_untargeted_revoked_signature() {
    let secret = generated_test_secret("Renewed Direct <renewed-direct@example.test>");
    let older = direct_self_signature(&secret, (TEST_TIME + 10) as u32, Vec::new());
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        Vec::new(),
    );
    let newer = direct_self_signature(&secret, (TEST_TIME + 30) as u32, Vec::new());
    let secret = certificate_with_only_direct_signatures(secret, vec![older, revocation, newer]);

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate replacement Direct Key signature");

    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 30) as u32),
    );
}

#[test]
fn direct_certification_revocation_cancels_an_equal_or_older_signature() {
    let secret = generated_test_secret("Ordered Direct <ordered-direct@example.test>");
    let revocation_time = (TEST_TIME + 20) as u32;

    for (statement_time, expected_authenticated) in [
        (revocation_time - 1, false),
        (revocation_time, false),
        (revocation_time + 1, true),
    ] {
        let direct = direct_self_signature(&secret, statement_time, Vec::new());
        let revocation = direct_certification_revocation(
            &secret,
            &secret,
            revocation_time,
            Vec::new(),
            Vec::new(),
        );
        let packet_orders = if statement_time == revocation_time {
            vec![
                vec![direct.clone(), revocation.clone()],
                vec![revocation, direct],
            ]
        } else {
            vec![vec![direct, revocation]]
        };

        for signatures in packet_orders {
            let candidate = certificate_with_only_direct_signatures(secret.clone(), signatures);
            let public = candidate.to_public_key();
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                TEST_TIME + 30,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("evaluate ordered Direct Key revocation");

            assert_eq!(
                policy.primary.authenticated, expected_authenticated,
                "statement time {statement_time}",
            );
            assert_eq!(
                policy
                    .primary
                    .effective_signature
                    .and_then(signature_creation_time),
                expected_authenticated.then_some(statement_time),
                "statement time {statement_time}",
            );
        }
    }
}

#[test]
fn revoked_direct_signature_is_not_retained_as_a_renewal_template() {
    let secret = generated_test_secret_with_kind(
        "Legacy Direct <legacy-direct@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let direct = direct_self_signature_with_hash(
        &secret,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    );
    let revocation = direct_certification_revocation(
        &secret,
        &secret,
        (TEST_TIME + 20) as u32,
        Vec::new(),
        Vec::new(),
    );
    let secret = certificate_with_only_direct_signatures(secret, vec![direct, revocation]);

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        reference_time(None),
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked legacy Direct Key signature");

    assert!(policy.primary.verified_templates.is_empty());
}

#[test]
fn revoked_direct_signature_no_longer_declares_a_revocation_authority() {
    let target = generated_test_secret("Revoker Target <revoker-target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    let declaration = designated_revoker_declaration(&target, &revoker, Vec::new());
    let revocation = direct_certification_revocation(
        &target,
        &revoker,
        (TEST_TIME + 20) as u32,
        vec![direct_signature_target(&declaration, &target)],
        Vec::new(),
    );
    let mut target = certificate_with_only_direct_signatures(target, vec![declaration, revocation]);
    let key_revocation = designated_key_revocation(&target, &revoker);
    target.details.revocation_signatures.push(key_revocation);

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let candidates = all_components(&[target_public.clone(), revoker_public]);
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked designated-revoker declaration");

    assert!(!policy.primary.authenticated);
    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
        "a canceled declaration must not authorize a key revocation",
    );
}

#[test]
fn invalid_issuer_metadata_cannot_authenticate_self_certifications() {
    let secret = generated_test_secret("Issuer Metadata <issuer-metadata@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let wrong_key_id = [0xa5; 8].into();
    assert_ne!(wrong_key_id, secret.primary_key.legacy_key_id());

    let cases = [(
        "inconsistent key ID",
        vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                secret.primary_key.fingerprint(),
            ))
            .expect("issuer fingerprint"),
            Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
                .expect("inconsistent issuer key ID"),
        ],
    )];

    for (case, issuer_subpackets) in cases {
        let mut candidate = secret.clone();
        let certification = user_id_self_certification(
            &candidate,
            &user_id,
            TEST_TIME as u32,
            HashAlgorithm::Sha256,
            issuer_subpackets,
        );
        let mut config = certification.config().cloned().expect("v4 certification");
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(
                candidate.primary_key.legacy_key_id(),
            ))
            .expect("correct unhashed issuer key ID"),
        );
        let certification = Signature::from_config(
            config,
            certification
                .signed_hash_value()
                .expect("signed hash prefix"),
            certification.signature().cloned().expect("signature bytes"),
        )
        .expect("rebuild certification with an unhashed issuer");
        certification
            .verify_certification(candidate.primary_key.public_key(), Tag::UserId, &user_id)
            .expect("fixture remains mathematically valid");
        candidate.details.direct_signatures.clear();
        candidate.details.users[0].signatures = vec![certification];

        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate malformed issuer metadata");

        let identity = policy.user_ids.first().expect("identity policy");
        assert!(!identity.authenticated(), "case: {case}");
        assert!(identity.verified_certifications.is_empty(), "case: {case}");
        assert!(
            policy.verified_user_ids_for_test().is_empty(),
            "case: {case}"
        );
    }
}

#[test]
fn issuer_metadata_ignores_unhashed_conflicts_when_hashed_hint_exists() {
    let fingerprint = Fingerprint::V4([0x44; 20]);
    let key_id = [0x44; 8].into();
    let cases = [
        (
            "conflicting unhashed fingerprint",
            vec![
                Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V4(
                    [0x45; 20],
                )))
                .expect("conflicting v4 fingerprint"),
            ],
            SubpacketData::IssuerFingerprint(fingerprint.clone()),
        ),
        (
            "conflicting unhashed key ID",
            vec![
                Subpacket::regular(SubpacketData::IssuerKeyId([0xa5; 8].into()))
                    .expect("conflicting key ID"),
            ],
            SubpacketData::IssuerKeyId(key_id),
        ),
    ];

    for (case, unhashed_subpackets, hashed_issuer) in cases {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            PublicKeyAlgorithm::Ed25519,
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets =
            vec![Subpacket::regular(hashed_issuer).expect("hashed issuer metadata")];
        config.unhashed_subpackets = unhashed_subpackets;
        let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic signature");

        assert!(
            !SignatureIssuerMetadata::from_signature(&signature).is_invalid(),
            "case: {case}",
        );
    }
}

#[test]
fn unhashed_issuer_hints_route_but_do_not_constrain_a_known_signer() {
    let secret = generated_test_secret("Advisory Issuer <advisory-issuer@example.test>");
    let wrong_key_id = KeyId::from([0xa5; 8]);
    assert_ne!(wrong_key_id, secret.primary_key.legacy_key_id());
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
            .expect("advisory issuer key ID"),
    ];
    let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic signature");

    let issuer = SignatureIssuerMetadata::from_signature(&signature);
    assert!(!issuer.is_invalid());
    assert!(!issuer.matches(&secret.primary_key));
    assert!(issuer.signer_constraints_match(&secret.primary_key));
    assert_eq!(issuer.key_id(), Some(&wrong_key_id));
    let normalized = signature_ignoring_unhashed_issuer_hints(&signature)
        .expect("structurally valid routing hint can be normalized");
    assert!(SignatureIssuerMetadata::from_signature(normalized.as_ref()).is_missing());
}

#[test]
fn unhashed_candidate_cannot_override_a_foreign_hashed_issuer() {
    let secret = generated_test_secret("Hashed Issuer <hashed-issuer@example.test>");
    let foreign_fingerprint = Fingerprint::V4([0xa5; 20]);
    assert_ne!(foreign_fingerprint, secret.primary_key.fingerprint());
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            foreign_fingerprint.clone(),
        ))
        .expect("foreign hashed issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("candidate unhashed issuer fingerprint"),
    ];
    let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic signature");

    let issuer = SignatureIssuerMetadata::from_signature(&signature);
    assert!(!issuer.is_invalid());
    assert!(issuer.matches(&secret.primary_key));
    assert!(!issuer.signer_constraints_match(&secret.primary_key));
    assert_eq!(issuer.fingerprint(), Some(&foreign_fingerprint));
}

#[test]
fn malformed_or_contradictory_hashed_issuer_metadata_remains_invalid() {
    let hashed_cases = [
        vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
                [0x66; 32],
            )))
            .expect("wrong-version hashed issuer fingerprint"),
        ],
        vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V4(
                [0x44; 20],
            )))
            .expect("hashed issuer fingerprint"),
            Subpacket::regular(SubpacketData::IssuerKeyId(KeyId::from([0xa5; 8])))
                .expect("contradictory hashed issuer key ID"),
        ],
    ];

    for hashed_subpackets in hashed_cases {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            PublicKeyAlgorithm::Ed25519,
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = hashed_subpackets;
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(KeyId::from([0x44; 8])))
                .expect("advisory matching issuer key ID"),
        ];
        let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic malformed signature");

        assert!(SignatureIssuerMetadata::from_signature(&signature).is_invalid());
    }
}

#[test]
fn issuer_metadata_rejects_wrong_version_unhashed_fingerprint() {
    for preceding_hints in [0, MAX_SIGNATURE_ISSUER_HINTS] {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            PublicKeyAlgorithm::Ed25519,
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V4(
                [0x44; 20],
            )))
            .expect("v4 issuer fingerprint"),
        ];
        config.unhashed_subpackets = (0..preceding_hints)
            .map(|_| {
                Subpacket::regular(SubpacketData::IssuerKeyId(KeyId::from([0x44; 8])))
                    .expect("valid unhashed issuer key ID")
            })
            .collect();
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
                [0x66; 32],
            )))
            .expect("wrong-version unhashed issuer fingerprint"),
        );
        let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic signature");

        assert!(
            SignatureIssuerMetadata::from_signature(&signature).is_invalid(),
            "preceding hints: {preceding_hints}",
        );
        assert!(
            signature_ignoring_unhashed_issuer_hints(&signature).is_none(),
            "preceding hints: {preceding_hints}",
        );
    }
}

#[test]
fn issuer_metadata_accepts_combined_hint_cap_and_preserves_first_display_values() {
    let secret = generated_test_secret("Issuer Boundary <issuer-boundary@example.test>");
    let fingerprint = secret.primary_key.fingerprint();
    let actual_key_id = secret.primary_key.legacy_key_id();
    let first_key_id = KeyId::from([0xa5; 8]);
    assert_ne!(first_key_id, actual_key_id);

    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerFingerprint(fingerprint.clone()))
            .expect("first issuer fingerprint"),
    );
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(first_key_id)).expect("first issuer key ID"),
    );
    for _ in 0..MAX_SIGNATURE_ISSUER_HINTS - 3 {
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(first_key_id))
                .expect("additional issuer key ID"),
        );
    }
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(actual_key_id))
            .expect("matching issuer key ID"),
    );
    let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic boundary signature");

    let issuer = SignatureIssuerMetadata::from_signature(&signature);
    assert!(!issuer.is_invalid());
    assert!(issuer.matches(&secret.primary_key));
    assert_eq!(issuer.fingerprint(), Some(&fingerprint));
    assert_eq!(issuer.key_id(), Some(&first_key_id));
}

#[test]
fn issuer_metadata_rejects_hashed_hints_above_cap_and_truncates_unhashed_hints() {
    let secret = generated_test_secret("Bounded Issuer <bounded-issuer@example.test>");
    let key_id = KeyId::from([0xa5; 8]);
    assert_ne!(key_id, secret.primary_key.legacy_key_id());
    let issuer_subpackets = || {
        (0..=MAX_SIGNATURE_ISSUER_HINTS)
            .map(|_| Subpacket::regular(SubpacketData::IssuerKeyId(key_id)).expect("issuer key ID"))
            .collect::<Vec<_>>()
    };

    let mut hashed_config = SignatureConfig::v4(
        SignatureType::Binary,
        PublicKeyAlgorithm::Ed25519,
        HashAlgorithm::Sha256,
    );
    hashed_config.hashed_subpackets = issuer_subpackets();
    let hashed_signature =
        Signature::from_config(hashed_config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic over-limit hashed signature");
    assert!(
        SignatureIssuerMetadata::from_signature(&hashed_signature).is_invalid(),
        "authenticated constraints stay strict",
    );

    let mut unhashed_config = SignatureConfig::v4(
        SignatureType::Binary,
        PublicKeyAlgorithm::Ed25519,
        HashAlgorithm::Sha256,
    );
    unhashed_config.unhashed_subpackets = (0..MAX_SIGNATURE_ISSUER_HINTS)
        .map(|_| Subpacket::regular(SubpacketData::IssuerKeyId(key_id)).expect("issuer key ID"))
        .collect();
    unhashed_config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("matching issuer key ID beyond the routing cap"),
    );
    let unhashed_signature =
        Signature::from_config(unhashed_config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic over-limit unhashed signature");
    let issuer = SignatureIssuerMetadata::from_signature(&unhashed_signature);
    assert!(!issuer.is_invalid());
    assert_eq!(issuer.key_id(), Some(&key_id));
    assert!(
        !issuer.matches(&secret.primary_key),
        "routing must not retain a hint beyond the cap",
    );
    assert!(issuer.signer_constraints_match(&secret.primary_key));
}

#[test]
fn issuer_metadata_hint_cap_applies_across_both_areas() {
    let first_key_id = KeyId::from([0x44; 8]);
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        PublicKeyAlgorithm::Ed25519,
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(first_key_id)).expect("first issuer key ID"),
    ];
    config.unhashed_subpackets = (0..MAX_SIGNATURE_ISSUER_HINTS - 1)
        .map(|_| {
            Subpacket::regular(SubpacketData::IssuerKeyId([0xa5; 8].into()))
                .expect("unhashed issuer key ID")
        })
        .collect();
    let signature =
        Signature::from_config(config.clone(), [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic boundary signature");

    let issuer = SignatureIssuerMetadata::from_signature(&signature);
    assert!(!issuer.is_invalid());
    assert_eq!(issuer.key_id(), Some(&first_key_id));

    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId([0xa5; 8].into()))
            .expect("over-limit issuer key ID"),
    );
    let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic over-limit signature");
    let issuer = SignatureIssuerMetadata::from_signature(&signature);
    assert!(!issuer.is_invalid());
    assert_eq!(issuer.key_id(), Some(&first_key_id));
}

fn add_designated_revoker_declaration(target: &mut SignedSecretKey, revoker: &SignedSecretKey) {
    let declaration = designated_revoker_declaration(target, revoker, Vec::new());
    target.details.direct_signatures.push(declaration);
}

fn designated_revoker_subpacket(revoker: &SignedSecretKey) -> Subpacket {
    Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
        RevocationKeyClass::Default,
        revoker.primary_key.algorithm(),
        revoker.primary_key.fingerprint().as_bytes(),
    )))
    .expect("revocation key subpacket")
}

fn user_id_designated_revoker_declaration(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    creation_time: u32,
) -> Signature {
    self_certification(
        target,
        creation_time,
        HashAlgorithm::Sha256,
        vec![designated_revoker_subpacket(revoker)],
    )
}

fn designated_revoker_declaration(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    additional_subpackets: Vec<Subpacket>,
) -> Signature {
    designated_revoker_declaration_with_hash(
        target,
        revoker,
        additional_subpackets,
        HashAlgorithm::Sha256,
    )
}

fn designated_revoker_declaration_with_hash(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    additional_subpackets: Vec<Subpacket>,
    hash_algorithm: HashAlgorithm,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        target.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("declaration creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            target.primary_key.fingerprint(),
        ))
        .expect("declaration issuer fingerprint"),
        designated_revoker_subpacket(revoker),
    ];
    config.hashed_subpackets.extend(additional_subpackets);
    config
        .sign_key(
            &target.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign designated-revoker declaration")
}

fn designated_key_revocation(target: &SignedSecretKey, revoker: &SignedSecretKey) -> Signature {
    designated_key_revocation_with_hash(target, revoker, HashAlgorithm::Sha256)
}

fn designated_key_revocation_with_hash(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    hash_algorithm: HashAlgorithm,
) -> Signature {
    designated_key_revocation_at(
        target,
        revoker,
        (TEST_TIME + 2) as u32,
        hash_algorithm,
        None,
    )
}

fn designated_key_revocation_at(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    creation_time: u32,
    hash_algorithm: HashAlgorithm,
    reason: Option<RevocationCode>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        revoker.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    if let Some(reason) = reason {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
                .expect("revocation reason"),
        );
    }
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            revoker.primary_key.legacy_key_id(),
        ))
        .expect("revocation issuer key ID"),
    ];
    config
        .sign_key(
            &revoker.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign designated revocation")
}

fn designated_subkey_revocation(
    target: &SignedSecretKey,
    subkey: &pgp::packet::PublicSubkey,
    revoker: &SignedSecretKey,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyRevocation,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            revoker.primary_key.legacy_key_id(),
        ))
        .expect("revocation issuer key ID"),
    ];
    config
        .sign_subkey_binding(
            &revoker.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            subkey,
        )
        .expect("sign designated subkey revocation")
}

#[test]
fn signature_creation_time_reads_fixed_v3_key_revocation_time() {
    let secret = SignedSecretKey::from_reader_single(Cursor::new(include_bytes!(
        "../../../tests/fixtures/openpgp/v3-secret.asc"
    )))
    .expect("parse fixed v3 secret key")
    .0;
    let creation_time = (TEST_TIME + 42) as u32;
    let revocation = v3_key_revocation(&secret, creation_time);

    assert_eq!(revocation.version(), SignatureVersion::V3);
    assert_eq!(revocation.typ(), Some(SignatureType::KeyRevocation));
    assert!(
        revocation
            .config()
            .expect("known v3 signature")
            .hashed_subpackets
            .is_empty()
    );
    revocation
        .verify_key(secret.primary_key.public_key())
        .expect("verify genuine v3 key revocation");
    assert_eq!(signature_creation_time(&revocation), Some(creation_time));
}

#[test]
fn signature_creation_time_uses_last_hashed_v4_duplicate() {
    let secret = generated_test_secret("Duplicate Time <duplicate-time@example.test>");
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("first hashed creation time"),
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("last hashed creation time"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 3) as u32,
        )))
        .expect("unhashed creation time"),
    ];
    let signature = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign duplicate-time direct signature");

    assert_eq!(
        signature_creation_time(&signature),
        Some((TEST_TIME + 2) as u32)
    );
}

#[test]
fn signature_creation_time_ignores_unhashed_v4_time() {
    let secret = generated_test_secret("Unhashed Time <unhashed-time@example.test>");
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("unhashed creation time"),
    ];
    let signature = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign unhashed-time direct signature");

    assert_eq!(signature_creation_time(&signature), None);
}

#[test]
fn sparse_v4_direct_signature_preserves_primary_user_id_policy() {
    let mut secret = generated_test_secret("Sparse Direct <sparse-direct@example.test>");
    let mut identity_flags = KeyFlags::default();
    identity_flags.set_sign(true);
    let certification = self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("identity key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(identity_flags))
                .expect("identity key flags"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("identity symmetric preferences"),
            Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
                vec![CompressionAlgorithm::ZLIB].into(),
            ))
            .expect("identity compression preferences"),
            Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
                vec![(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)].into(),
            ))
            .expect("identity AEAD preferences"),
            Subpacket::regular(SubpacketData::PreferredEncryptionModes(
                vec![AeadAlgorithm::Ocb].into(),
            ))
            .expect("identity GnuPG encryption-mode preferences"),
            Subpacket::regular(SubpacketData::Features(Features::from(&[0x0b][..])))
                .expect("identity features"),
        ],
    );
    secret.details.users[0].signatures = vec![certification];
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 1) as u32,
        Vec::new(),
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate sparse Direct Key policy");

    assert!(policy.primary.authenticated);
    assert_eq!(policy.primary.key_expiration_seconds, Some(3_600));
    assert!(
        policy
            .primary
            .key_flags
            .as_ref()
            .is_some_and(KeyFlags::sign)
    );
    assert!(policy.primary.features.contains(0x08));
    assert!(policy.primary.allows_gnupg_ocb);
    assert_eq!(
        policy.primary.preferred_symmetric.as_deref(),
        Some(&[u8::from(SymmetricKeyAlgorithm::AES256)][..]),
    );
    assert_eq!(
        policy.primary.preferred_compression.as_deref(),
        Some(&[u8::from(CompressionAlgorithm::ZLIB)][..]),
    );
    assert_eq!(
        policy.primary.preferred_aead.as_deref(),
        Some(
            &[(
                u8::from(SymmetricKeyAlgorithm::AES256),
                u8::from(AeadAlgorithm::Ocb),
            )][..],
        ),
    );
    assert_eq!(
        policy.primary.preferred_encryption_modes,
        EncryptionModePreferences::Present(vec![u8::from(AeadAlgorithm::Ocb)].into_boxed_slice()),
    );
}

#[test]
fn standard_and_gnupg_aead_capabilities_use_independent_preference_subpackets() {
    let standard_only = SignaturePolicyProjection {
        signature_expiration_seconds: None,
        key_expiration_seconds: None,
        key_flags: None,
        is_primary: false,
        preferred_symmetric: Some(vec![u8::from(SymmetricKeyAlgorithm::AES256)]),
        preferred_compression: None,
        preferred_aead: Some(vec![(
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
        )]),
        preferred_encryption_modes: EncryptionModePreferences::Missing,
        features: AuthenticatedFeatures::Present(vec![0x0a].into_boxed_slice()),
        signing_cross_certified: false,
    };

    assert!(standard_only.features.contains(0x08));
    assert!(
        !standard_only.allows_gnupg_ocb(),
        "RFC 9580 type 39 must not stand in for GnuPG type 34",
    );

    let implicit_aes128_ocb = SignaturePolicyProjection {
        preferred_symmetric: None,
        preferred_aead: None,
        ..standard_only.clone()
    };
    assert!(
        implicit_aes128_ocb.features.contains(0x08),
        "the feature bit permits SEIPDv2 because AES-128/OCB is implicit",
    );
    assert!(!implicit_aes128_ocb.allows_gnupg_ocb());

    let gnupg_only = SignaturePolicyProjection {
        preferred_aead: None,
        preferred_encryption_modes: EncryptionModePreferences::Present(
            vec![u8::from(AeadAlgorithm::Ocb)].into_boxed_slice(),
        ),
        features: AuthenticatedFeatures::Present(vec![0x03].into_boxed_slice()),
        ..standard_only
    };
    assert!(!gnupg_only.features.contains(0x08));
    assert!(gnupg_only.allows_gnupg_ocb());

    for unsupported in [
        SignaturePolicyProjection {
            preferred_encryption_modes: EncryptionModePreferences::Missing,
            ..gnupg_only.clone()
        },
        SignaturePolicyProjection {
            preferred_encryption_modes: EncryptionModePreferences::Ambiguous,
            ..gnupg_only.clone()
        },
        SignaturePolicyProjection {
            preferred_symmetric: Some(vec![u8::from(SymmetricKeyAlgorithm::AES128)]),
            ..gnupg_only.clone()
        },
        SignaturePolicyProjection {
            features: AuthenticatedFeatures::Present(vec![0x01].into_boxed_slice()),
            ..gnupg_only.clone()
        },
    ] {
        assert!(!unsupported.allows_gnupg_ocb());
    }

    let merged = merge_v4_primary_policy(
        gnupg_only.clone(),
        SignaturePolicyProjection {
            preferred_encryption_modes: EncryptionModePreferences::Ambiguous,
            ..gnupg_only
        },
    );
    assert_eq!(
        merged.preferred_encryption_modes,
        EncryptionModePreferences::Ambiguous,
    );
    assert!(
        !merged.allows_gnupg_ocb(),
        "an ambiguous primary-identity statement must not fall through to a Direct Key default",
    );
}

#[test]
fn seipd_v2_support_distinguishes_missing_from_explicit_negative_features() {
    let missing = AuthenticatedFeatures::Missing;
    let negative = AuthenticatedFeatures::Present(vec![0x01].into_boxed_slice());
    let advertised = AuthenticatedFeatures::Present(vec![0x09].into_boxed_slice());

    assert!(missing.allows_seipd_v2(KeyVersion::V6));
    assert!(!missing.allows_seipd_v2(KeyVersion::V4));
    assert!(!negative.allows_seipd_v2(KeyVersion::V6));
    assert!(!negative.allows_seipd_v2(KeyVersion::V4));
    assert!(advertised.allows_seipd_v2(KeyVersion::V6));
    assert!(advertised.allows_seipd_v2(KeyVersion::V4));
}

#[test]
fn gnupg_encryption_modes_require_one_hashed_type34_subpacket() {
    let base = generated_test_secret("GnuPG Modes <gnupg-modes@example.test>");
    let mode = || {
        Subpacket::regular(SubpacketData::PreferredEncryptionModes(
            vec![AeadAlgorithm::Ocb].into(),
        ))
        .expect("GnuPG encryption-mode preference")
    };

    for (label, hashed_modes, unhashed_modes, expected) in [
        (
            "one authenticated type 34",
            vec![mode()],
            Vec::new(),
            EncryptionModePreferences::Present(
                vec![u8::from(AeadAlgorithm::Ocb)].into_boxed_slice(),
            ),
        ),
        (
            "missing type 34",
            Vec::new(),
            Vec::new(),
            EncryptionModePreferences::Missing,
        ),
        (
            "unhashed type 34",
            Vec::new(),
            vec![mode()],
            EncryptionModePreferences::Missing,
        ),
        (
            "duplicate authenticated type 34",
            vec![mode(), mode()],
            Vec::new(),
            EncryptionModePreferences::Ambiguous,
        ),
    ] {
        let mut secret = base.clone();
        let mut config = SignatureConfig::v4(
            SignatureType::CertPositive,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                TEST_TIME as u32,
            )))
            .expect("signature creation time"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::Features(Features::from(&[0x03][..])))
                .expect("GnuPG AEAD feature"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("GnuPG symmetric preferences"),
        ];
        config.hashed_subpackets.extend(hashed_modes);
        config.unhashed_subpackets = unhashed_modes;
        let certification = config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &secret.details.users[0].id,
            )
            .expect("sign GnuPG-mode certification");
        secret.details.direct_signatures.clear();
        secret.details.users[0].signatures = vec![certification];
        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .unwrap_or_else(|error| panic!("evaluate {label}: {error}"));

        assert_eq!(
            &policy.primary.preferred_encryption_modes, &expected,
            "{label}",
        );
        assert_eq!(
            policy.primary.allows_gnupg_ocb,
            matches!(
                &policy.primary.preferred_encryption_modes,
                EncryptionModePreferences::Present(_)
            ),
            "{label}",
        );
    }
}

#[test]
fn v4_primary_policy_prefers_the_selected_user_id_statement() {
    let mut secret = generated_test_secret("Policy Merge <policy-merge@example.test>");
    let mut identity_flags = KeyFlags::default();
    identity_flags.set_sign(true);
    let certification = self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("identity key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(identity_flags))
                .expect("identity key flags"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("identity symmetric preferences"),
            Subpacket::regular(SubpacketData::Features(Features::from(&[0x03][..])))
                .expect("identity features"),
        ],
    );
    let mut direct_flags = KeyFlags::default();
    direct_flags.set_certify(true);
    let direct = direct_self_signature(
        &secret,
        (TEST_TIME + 1) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(7_200)))
                .expect("Direct Key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(direct_flags)).expect("Direct Key flags"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES128].into(),
            ))
            .expect("Direct Key symmetric preferences"),
            Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
                vec![(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)].into(),
            ))
            .expect("Direct Key AEAD preferences"),
            Subpacket::regular(SubpacketData::PreferredEncryptionModes(
                vec![AeadAlgorithm::Ocb].into(),
            ))
            .expect("Direct Key GnuPG encryption-mode preferences"),
            Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
                vec![CompressionAlgorithm::ZLIB].into(),
            ))
            .expect("Direct Key compression preferences"),
            Subpacket::regular(SubpacketData::Features(Features::from(&[0x01][..])))
                .expect("Direct Key features"),
        ],
    );
    secret.details.users[0].signatures = vec![certification];
    secret.details.direct_signatures = vec![direct];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate merged v4 primary policy");

    let flags = policy
        .primary
        .key_flags
        .as_ref()
        .expect("primary key flags");
    assert!(flags.sign(), "the selected User ID supplies key flags");
    assert!(!flags.certify());
    assert_eq!(
        policy.primary.key_expiration_seconds,
        Some(3_600),
        "the selected User ID supplies key expiration",
    );
    assert!(
        policy.primary.allows_gnupg_ocb,
        "identity symmetric preferences and features take precedence while the missing identity type-34 field falls back to Direct Key policy",
    );
    assert!(
        !policy.primary.features.contains(0x08),
        "the LibrePGP feature bit must not opt a recipient into standard SEIPDv2",
    );
    assert_eq!(
        policy.primary.preferred_compression.as_deref(),
        Some(&[u8::from(CompressionAlgorithm::ZLIB)][..]),
        "the missing identity compression field falls back to Direct Key policy",
    );
}

#[test]
fn v4_primary_user_id_flags_take_precedence_over_direct_key_defaults() {
    let mut secret = generated_test_secret("Direct Sign <direct-sign@example.test>");
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(certifying_flags))
                .expect("certifying User ID flags"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
        ],
    )];
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 1) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(signing_flags))
                .expect("Direct Key signing flags"),
        ],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate Direct Key signing precedence");

    let flags = policy
        .primary
        .key_flags
        .as_ref()
        .expect("primary key flags");
    assert!(!flags.sign());
    assert!(flags.certify());
    assert!(!policy.primary_component().signing_usable());
}

#[test]
fn v4_zero_direct_key_expiration_falls_back_to_user_id_expiration() {
    let mut secret = generated_test_secret("Zero Direct <zero-direct@example.test>");
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("User ID key expiration"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
        ],
    )];
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 1) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(0)))
                .expect("zero Direct Key expiration"),
        ],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate zero Direct Key expiration");

    assert_eq!(policy.primary.key_expiration_seconds, Some(3_600));
}

#[test]
fn v4_primary_key_fields_come_from_the_selected_primary_user_id() {
    let mut secret = generated_test_secret("Primary <primary-fields@example.test>");
    let mut primary_flags = KeyFlags::default();
    primary_flags.set_certify(true);
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(600)))
                .expect("primary User ID key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(primary_flags))
                .expect("primary User ID key flags"),
        ],
    )];
    secret.details.users.push(signed_user_id(
        &secret,
        "Expiration Carrier <expiration-fields@example.test>",
        (TEST_TIME + 2) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("newest nonzero User ID key expiration"),
        ],
    ));
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    secret.details.users.push(signed_user_id(
        &secret,
        "Flags Carrier <flags-fields@example.test>",
        (TEST_TIME + 3) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(signing_flags))
                .expect("newest User ID key flags"),
        ],
    ));
    secret.details.users.push(signed_user_id(
        &secret,
        "Zero Expiration <zero-fields@example.test>",
        (TEST_TIME + 4) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(0)))
                .expect("zero User ID key expiration"),
        ],
    ));
    secret.details.direct_signatures.clear();

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 5,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate V4 User ID primary-key fields");

    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some("Primary <primary-fields@example.test>"),
    );
    let flags = policy
        .primary
        .key_flags
        .as_ref()
        .expect("primary User ID key flags");
    assert!(flags.certify());
    assert!(!flags.sign());
    assert_eq!(
        policy.primary.key_expiration_seconds,
        Some(600),
        "key metadata stays scoped to the selected primary identity",
    );
    assert!(policy.primary_available());
}

#[test]
fn user_attribute_policy_does_not_override_v4_primary_user_id_fields() {
    let mut secret = generated_test_secret("Primary <primary-attribute-fields@example.test>");
    let mut user_id_flags = KeyFlags::default();
    user_id_flags.set_certify(true);
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(600)))
                .expect("User ID key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(user_id_flags)).expect("User ID key flags"),
        ],
    )];
    let attribute = UserAttribute::new_image(Bytes::from_static(b"newer field carrier image"))
        .expect("create User Attribute");
    let mut attribute_flags = KeyFlags::default();
    attribute_flags.set_sign(true);
    let attribute_certification = user_attribute_self_certification(
        &secret,
        &attribute,
        (TEST_TIME + 2) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("User Attribute key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(attribute_flags))
                .expect("User Attribute key flags"),
        ],
    );
    secret.details.user_attributes = vec![SignedUserAttribute::new(
        attribute,
        vec![attribute_certification],
    )];
    secret.details.direct_signatures.clear();

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate V4 User Attribute primary-key fields");

    assert!(policy.user_attributes[0].authenticated());
    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some("Primary <primary-attribute-fields@example.test>"),
        "a User Attribute field carrier must not become the textual primary User ID",
    );
    let flags = policy
        .primary
        .key_flags
        .as_ref()
        .expect("primary User ID key flags");
    assert!(flags.certify());
    assert!(!flags.sign());
    assert_eq!(policy.primary.key_expiration_seconds, Some(600));
}

#[test]
fn revoked_user_attribute_does_not_override_v4_primary_key_fields() {
    let mut secret = generated_test_secret("Primary <revoked-attribute-fields@example.test>");
    let mut user_id_flags = KeyFlags::default();
    user_id_flags.set_certify(true);
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(600)))
                .expect("User ID key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(user_id_flags)).expect("User ID key flags"),
        ],
    )];
    let attribute = UserAttribute::new_image(Bytes::from_static(b"revoked field carrier image"))
        .expect("create User Attribute");
    let mut attribute_flags = KeyFlags::default();
    attribute_flags.set_sign(true);
    let attribute_certification = user_attribute_self_certification(
        &secret,
        &attribute,
        (TEST_TIME + 2) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("User Attribute key expiration"),
            Subpacket::regular(SubpacketData::KeyFlags(attribute_flags))
                .expect("User Attribute key flags"),
        ],
    );
    let attribute_revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserAttribute,
        &attribute,
        (TEST_TIME + 3) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.user_attributes = vec![SignedUserAttribute::new(
        attribute,
        vec![attribute_certification, attribute_revocation],
    )];
    secret.details.direct_signatures.clear();

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked V4 User Attribute field carrier");

    assert_eq!(
        policy.user_attributes[0].revocation_status,
        RevocationStatus::Revoked,
    );
    let flags = policy
        .primary
        .key_flags
        .as_ref()
        .expect("live User ID key flags");
    assert!(flags.certify());
    assert!(!flags.sign());
    assert_eq!(policy.primary.key_expiration_seconds, Some(600));
}

#[test]
fn v4_primary_user_id_fields_do_not_mix_with_equal_time_identities() {
    let mut secret = generated_test_secret("First <first-conflict@example.test>");
    let created_at = (TEST_TIME + 1) as u32;
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        created_at,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(certifying_flags))
                .expect("certifying flags"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(600)))
                .expect("first expiration"),
        ],
    )];
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    secret.details.users.push(signed_user_id(
        &secret,
        "Second <second-conflict@example.test>",
        created_at,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(signing_flags)).expect("signing flags"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("second expiration"),
        ],
    ));
    secret.details.direct_signatures.clear();
    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate tied V4 User ID primary fields");

    assert!(policy.user_ids.iter().all(IdentityPolicy::authenticated));
    assert!(!policy.primary.policy_conflict);
    assert!(policy.primary.authenticated);
    let flags = policy.primary.key_flags.as_ref().expect("key flags");
    assert!(flags.certify());
    assert!(!flags.sign());
    assert_eq!(policy.primary.key_expiration_seconds, Some(600));
    assert!(policy.primary_available());
}

#[test]
fn non_utf8_user_ids_preserve_exact_identity() {
    let mut secret = generated_test_secret("Placeholder <placeholder@example.test>");
    let latin1_id: &[u8] = b"J\xF6rg <joerg@example.test>";
    let colliding_display_id: &[u8] = b"J\xF7rg <joerg@example.test>";
    let header = pgp::packet::PacketHeader::new_fixed(
        Tag::UserId,
        u32::try_from(latin1_id.len()).expect("user id length"),
    );
    let user_id = UserId::try_from_reader(header, latin1_id).expect("construct Latin-1 user id");
    let colliding_display_user_id = UserId::try_from_reader(
        pgp::packet::PacketHeader::new_fixed(
            Tag::UserId,
            u32::try_from(colliding_display_id.len()).expect("user id length"),
        ),
        colliding_display_id,
    )
    .expect("construct second Latin-1 user id");
    assert!(user_id.as_str().is_none(), "fixture must not be UTF-8");
    let certification = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let colliding_display_certification = user_id_self_certification(
        &secret,
        &colliding_display_user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    secret.details.users = vec![
        SignedUser::new(user_id, vec![certification]),
        SignedUser::new(
            colliding_display_user_id,
            vec![colliding_display_certification],
        ),
    ];
    secret.details.direct_signatures.clear();

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate certificate whose only User ID is not UTF-8");

    assert!(policy.primary.authenticated);
    assert!(policy.primary_available());
    assert_eq!(
        policy
            .authenticated_user_ids()
            .map(ValidatedUserId::packet_body)
            .collect::<Vec<_>>(),
        vec![latin1_id, colliding_display_id],
        "distinct packet bodies must not collapse through lossy display text",
    );
    let primary_body = policy
        .primary_user_id()
        .map(ValidatedUserId::packet_body)
        .expect("one authenticated User ID is selected");
    assert!(
        primary_body == latin1_id || primary_body == colliding_display_id,
        "primary selection must return an exact authenticated packet body",
    );
}

#[test]
fn explicit_zero_v4_direct_expiration_preserves_primary_user_id_expiration() {
    let mut secret = generated_test_secret("Zero Direct <zero-direct@example.test>");
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("identity key expiration"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
        ],
    )];
    secret.details.users.push(signed_user_id(
        &secret,
        "Newer Expiration <newer-expiration@example.test>",
        (TEST_TIME + 1) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(7_200)))
                .expect("newer User ID key expiration"),
        ],
    ));
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 2) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(0)))
                .expect("zero Direct Key expiration"),
        ],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate zero Direct Key expiration");

    assert_eq!(policy.primary.key_expiration_seconds, Some(3_600));
    assert!(component_is_expired(&policy.primary, u64::MAX));
}

#[test]
fn absent_v4_direct_expiration_preserves_primary_user_id_expiration() {
    let mut secret = generated_test_secret("Sparse Direct <sparse-expiry@example.test>");
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(3_600)))
                .expect("identity key expiration"),
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
        ],
    )];
    secret.details.users.push(signed_user_id(
        &secret,
        "Newer Expiration <newer-expiration@example.test>",
        (TEST_TIME + 1) as u32,
        vec![
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(7_200)))
                .expect("newer User ID key expiration"),
        ],
    ));
    // No Key Expiration Time subpacket at all: the Direct Key signature makes
    // no statement, so the selected primary User ID remains authoritative.
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 2) as u32,
        Vec::new(),
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate absent Direct Key expiration");

    assert_eq!(policy.primary.key_expiration_seconds, Some(3_600));
}

#[test]
fn public_verification_work_limit_is_inclusive() {
    let mut budget = OpenPgpPolicyBudget::default();
    for _ in 0..MAX_PUBLIC_KEY_VERIFICATIONS_PER_CERTIFICATE {
        assert_eq!(budget.charge_public_key_verification(), Ok(()));
    }
    assert_eq!(
        budget.charge_public_key_verification(),
        Err(OpenPgpPolicyError::ResourceLimit),
    );
    assert_eq!(
        budget.public_key_verifications,
        MAX_PUBLIC_KEY_VERIFICATIONS_PER_CERTIFICATE,
    );
}

#[test]
fn unmatched_third_party_certification_does_not_consume_verification_budget() {
    let mut target = generated_test_secret("Budget Target <budget-target@example.test>");
    let signer = generated_test_secret("Budget Signer <budget-signer@example.test>");
    let baseline = target.to_public_key();
    let mut baseline_budget = OpenPgpPolicyBudget::default();
    validate_certificate(
        &baseline,
        &all_components(std::slice::from_ref(&baseline)),
        TEST_TIME + 2,
        &mut baseline_budget,
    )
    .expect("evaluate baseline certificate");

    let user_id = target.details.users[0].id.clone();
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        signer.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("third-party certification creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signer.primary_key.fingerprint(),
        ))
        .expect("third-party certification issuer"),
    ];
    let certification = config
        .sign_certification(
            &signer.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign third-party certification");
    certification
        .verify_third_party_certification(
            target.primary_key.public_key(),
            signer.primary_key.public_key(),
            Tag::UserId,
            &user_id,
        )
        .expect("third-party certification remains mathematically valid");
    target.details.users[0].signatures.push(certification);

    let public = target.to_public_key();
    let mut budget = OpenPgpPolicyBudget::default();
    validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut budget,
    )
    .expect("evaluate certificate with an unrelated certification");

    assert_eq!(
        budget.public_key_verifications,
        baseline_budget.public_key_verifications,
    );
    assert_eq!(
        budget.request_public_key_verifications,
        baseline_budget.request_public_key_verifications,
    );
}

#[test]
fn public_verification_request_limit_cannot_multiply_across_certificates() {
    let mut budget = OpenPgpPolicyBudget::default();
    for fingerprint in [b"certificate-one".as_slice(), b"certificate-two".as_slice()] {
        budget.select_certificate(fingerprint);
        for _ in 0..MAX_PUBLIC_KEY_VERIFICATIONS_PER_CERTIFICATE {
            assert_eq!(budget.charge_public_key_verification(), Ok(()));
        }
    }
    assert_eq!(
        budget.request_public_key_verifications,
        MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST,
    );

    // Selecting a third fingerprint creates a fresh fair certificate-local
    // allowance, but it must not create fresh request capacity.
    budget.select_certificate(b"certificate-three");
    assert_eq!(budget.public_key_verifications, 0);
    assert_eq!(
        budget.charge_public_key_verification(),
        Err(OpenPgpPolicyError::RequestResourceLimit),
    );
    assert_eq!(budget.public_key_verifications, 0);
}

#[test]
fn exhausted_certificate_does_not_drain_an_unrelated_certificate() {
    let first = generated_test_secret("Budget One <budget-one@example.test>").to_public_key();
    let second = generated_test_secret("Budget Two <budget-two@example.test>").to_public_key();
    let certificates = [first, second];
    let candidates = all_components(&certificates);
    let mut budget = OpenPgpPolicyBudget::default();

    validate_certificate(&certificates[0], &candidates, TEST_TIME + 1, &mut budget)
        .expect("first certificate initially validates");
    while budget.charge_public_key_verification().is_ok() {}
    assert!(matches!(
        validate_certificate_with_policy_time(
            &certificates[0],
            &candidates,
            TEST_TIME,
            TEST_TIME + 1,
            &mut budget,
        ),
        Err(OpenPgpPolicyError::ResourceLimit),
    ));

    validate_certificate(&certificates[1], &candidates, TEST_TIME + 1, &mut budget)
        .expect("second certificate retains an independent allowance");
    assert!(matches!(
        validate_certificate_with_policy_time(
            &certificates[0],
            &candidates,
            TEST_TIME + 1,
            TEST_TIME + 1,
            &mut budget,
        ),
        Err(OpenPgpPolicyError::ResourceLimit),
    ));
}

#[test]
fn signature_target_digest_work_limit_is_inclusive() {
    let mut budget = OpenPgpPolicyBudget::default();
    for _ in 0..MAX_SIGNATURE_TARGET_DIGESTS_PER_CERTIFICATE {
        assert_eq!(budget.charge_signature_target_digest(), Ok(()));
    }
    assert_eq!(
        budget.charge_signature_target_digest(),
        Err(OpenPgpPolicyError::ResourceLimit),
    );
    assert_eq!(
        budget.signature_target_digests,
        MAX_SIGNATURE_TARGET_DIGESTS_PER_CERTIFICATE,
    );
}

#[test]
fn designated_revoker_cap_deduplicates_before_accounting() {
    let mut budget = OpenPgpPolicyBudget::default();
    for index in 0_u64..MAX_DESIGNATED_REVOKERS_PER_REQUEST as u64 {
        let id = DesignatedRevokerId {
            algorithm: 1,
            fingerprint: index.to_be_bytes().to_vec(),
            key_class: 0x80,
        };
        assert_eq!(budget.charge_designated_revoker(id.clone()), Ok(()));
        assert_eq!(budget.charge_designated_revoker(id), Ok(()));
    }
    assert_eq!(
        budget.designated_revokers.len(),
        MAX_DESIGNATED_REVOKERS_PER_REQUEST,
    );
    assert_eq!(
        budget.charge_designated_revoker(DesignatedRevokerId {
            algorithm: 1,
            fingerprint: b"one-too-many".to_vec(),
            key_class: 0x80,
        }),
        Err(OpenPgpPolicyError::ResourceLimit),
    );
}

#[test]
fn user_id_designated_revoker_declaration_is_ignored() {
    let mut target = generated_test_secret("UID Target <uid-target@example.test>");
    let revoker = generated_test_secret("UID Revoker <uid-revoker@example.test>");
    let declaration =
        user_id_designated_revoker_declaration(&target, &revoker, (TEST_TIME + 1) as u32);
    let newer_certification = self_certification(
        &target,
        (TEST_TIME + 3) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    target.details.direct_signatures.clear();
    target.details.users[0].signatures = vec![declaration, newer_certification];
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate ignored User ID declaration");

    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 3) as u32),
        "the newer certification remains effective policy",
    );
    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(!policy.primary.revoked);
    assert!(policy.primary_available());
    assert_eq!(policy.primary_component().authorize_mutation(), Ok(()));
}

#[test]
fn user_attribute_designated_revoker_declaration_is_ignored() {
    let mut target = generated_test_secret("Attribute Target <attribute-target@example.test>");
    let revoker = generated_test_secret("Attribute Revoker <attribute-revoker@example.test>");
    let attribute = UserAttribute::new_image(Bytes::from_static(b"revoker declaration"))
        .expect("create User Attribute");
    let declaration = user_attribute_self_certification(
        &target,
        &attribute,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![designated_revoker_subpacket(&revoker)],
    );
    declaration
        .verify_certification(
            target.primary_key.public_key(),
            Tag::UserAttribute,
            &attribute,
        )
        .expect("User Attribute declaration is mathematically valid");
    target.details.direct_signatures.clear();
    target.details.user_attributes = vec![SignedUserAttribute::new(attribute, vec![declaration])];
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate ignored User Attribute declaration");

    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn subkey_designated_revoker_declaration_is_ignored() {
    let mut target = generated_test_secret("Subkey Target <subkey-target@example.test>");
    let revoker = generated_test_secret("Subkey Revoker <subkey-revoker@example.test>");
    let subkey = target.secret_subkeys[0].key.public_key().clone();
    let mut binding_config = target.secret_subkeys[0]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    binding_config
        .hashed_subpackets
        .push(designated_revoker_subpacket(&revoker));
    let declaration = binding_config
        .sign_subkey_binding(
            &target.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("sign subkey declaration");
    declaration
        .verify_subkey_binding(target.primary_key.public_key(), &subkey)
        .expect("subkey declaration is mathematically valid");
    target.details.direct_signatures.clear();
    target.secret_subkeys[0].signatures = vec![declaration];
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate ignored Subkey declaration");

    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn third_party_direct_key_designated_revoker_declaration_is_ignored() {
    let mut target = generated_test_secret("Third-party Target <third-party@example.test>");
    let revoker = generated_test_secret("Third-party Revoker <revoker@example.test>");
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("declaration creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("third-party declaration issuer"),
        designated_revoker_subpacket(&revoker),
    ];
    let declaration = config
        .sign_key(
            &revoker.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign third-party Direct Key declaration");
    declaration
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("third-party Direct Key declaration is mathematically valid");
    target.details.direct_signatures = vec![declaration];
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate ignored third-party Direct Key declaration");

    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn future_user_id_designated_revoker_declaration_does_not_authorize_revocation() {
    let mut target = generated_test_secret("Future UID Target <future-uid@example.test>");
    let revoker = generated_test_secret("Future UID Revoker <future-revoker@example.test>");
    let declaration =
        user_id_designated_revoker_declaration(&target, &revoker, (TEST_TIME + 10) as u32);
    declaration
        .verify_certification(
            target.primary_key.public_key(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("future declaration remains mathematically valid");
    target.details.direct_signatures.clear();
    target.details.users[0].signatures.push(declaration);
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate future User ID declaration");

    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert!(policy.primary_available());
    assert_eq!(policy.primary_component().authorize_mutation(), Ok(()));
}

#[test]
fn third_party_user_id_designated_revoker_declaration_does_not_authorize_revocation() {
    let mut target = generated_test_secret("Third-party UID Target <target@example.test>");
    let revoker = generated_test_secret("Third-party UID Revoker <revoker@example.test>");
    let user_id = target.details.users[0].id.clone();
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("third-party certification creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("third-party certification issuer"),
        designated_revoker_subpacket(&revoker),
    ];
    let declaration = config
        .sign_certification(
            &revoker.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign third-party User ID certification");
    declaration
        .verify_third_party_certification(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
            Tag::UserId,
            &user_id,
        )
        .expect("third-party declaration is mathematically valid");
    target.details.direct_signatures.clear();
    target.details.users[0].signatures.push(declaration);
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate third-party User ID declaration");

    assert!(policy.revocation_authority_fingerprints().next().is_none());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
    assert_eq!(policy.primary_component().authorize_mutation(), Ok(()));
}

#[test]
fn designated_revocation_fails_closed_until_the_authority_key_verifies_it() {
    let mut target = generated_test_secret("Target <target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);

    let declaration_only = target.to_public_key();
    let policy = validate_certificate(
        &declaration_only,
        &all_components(std::slice::from_ref(&declaration_only)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate declaration without a revocation packet");
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(
        policy
            .revocation_authority_fingerprints()
            .collect::<Vec<_>>(),
        vec![hex_upper(revoker.primary_key.fingerprint().as_bytes())],
        "the surviving Direct Key declaration remains visible",
    );
    assert!(policy.primary_available());
    assert_eq!(policy.primary_component().authorize_mutation(), Ok(()));

    let revocation = designated_key_revocation(&target, &revoker);
    revocation
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("verify designated revocation");
    target.details.revocation_signatures.push(revocation);
    let target_public = target.to_public_key();
    let target_only = all_components(std::slice::from_ref(&target_public));
    let policy = validate_certificate(
        &target_public,
        &target_only,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revocation without its authority key");
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::Indeterminate,
    );
    assert!(!policy.primary.revoked);
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::IndeterminateRevocation),
    );

    let mut revoked_revoker = revoker.to_public_key();
    let revoker_self_revocation = designated_key_revocation(&revoker, &revoker);
    revoked_revoker
        .details
        .revocation_signatures
        .push(revoker_self_revocation);
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoked_revoker)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revocation with a currently revoked authority certificate");
    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked,);
    assert!(policy.primary.revoked);
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::Revoked),
    );
}

#[test]
fn designated_soft_revocation_requires_authentication_before_supersession() {
    let target = generated_test_secret("Ordered Target <ordered-target@example.test>");
    let revoker = generated_test_secret("Ordered Revoker <ordered-revoker@example.test>");
    let statement_time = (TEST_TIME + 1) as u32;
    let declaration = designated_revoker_declaration(&target, &revoker, Vec::new());
    assert_eq!(signature_creation_time(&declaration), Some(statement_time),);

    for revocation_time in [statement_time - 1, statement_time, statement_time + 1] {
        let revocation = designated_key_revocation_at(
            &target,
            &revoker,
            revocation_time,
            HashAlgorithm::Sha256,
            Some(RevocationCode::KeyRetired),
        );
        let mut candidate =
            certificate_with_only_direct_signatures(target.clone(), vec![declaration.clone()]);
        candidate.details.revocation_signatures = vec![revocation];
        let public = candidate.to_public_key();
        let target_only = all_components(std::slice::from_ref(&public));
        let unresolved = validate_certificate(
            &public,
            &target_only,
            TEST_TIME + 10,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate ordered unresolved designated revocation");
        assert_eq!(
            unresolved.primary.revocation_status,
            RevocationStatus::Indeterminate,
            "revocation time {revocation_time}",
        );
        assert!(!unresolved.primary_available());
        assert_eq!(
            unresolved.primary_component().authorize_mutation(),
            Err(MutationAuthorizationError::IndeterminateRevocation),
        );

        let revoker_public = revoker.to_public_key();
        let mut candidates = target_only;
        candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
        let resolved = validate_certificate(
            &public,
            &candidates,
            TEST_TIME + 10,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate ordered verified designated revocation");
        let restored = revocation_time < statement_time;
        assert_eq!(
            resolved.primary.revocation_status,
            if restored {
                RevocationStatus::NotRevoked
            } else {
                RevocationStatus::Revoked
            },
            "revocation time {revocation_time}",
        );
        assert_eq!(resolved.primary_available(), restored);
        assert_eq!(
            resolved.primary_component().authorize_mutation(),
            if restored {
                Ok(())
            } else {
                Err(MutationAuthorizationError::Revoked)
            },
        );
    }
}

#[test]
fn unresolved_designated_subkey_revocation_fails_closed_for_that_subkey() {
    let mut target = generated_test_secret("Subkey Target <subkey-target@example.test>");
    let revoker = generated_test_secret("Subkey Revoker <subkey-revoker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);
    let subkey = target.secret_subkeys[0].key.public_key().clone();
    let revocation = designated_subkey_revocation(&target, &subkey, &revoker);
    target.secret_subkeys[0].signatures.push(revocation);

    let target_public = target.to_public_key();
    let target_only = all_components(std::slice::from_ref(&target_public));
    let unresolved = validate_certificate(
        &target_public,
        &target_only,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate subkey revocation without its authority key");
    assert_eq!(
        unresolved.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(unresolved.primary_component().authorize_mutation(), Ok(()));
    let unresolved_subkey = unresolved.subkey(&subkey).expect("revoked subkey policy");
    assert_eq!(
        unresolved_subkey.policy().revocation_status,
        RevocationStatus::Indeterminate,
    );
    assert_eq!(
        unresolved_subkey.authorize_renewal(),
        Err(MutationAuthorizationError::IndeterminateRevocation),
    );

    let revoker_public = revoker.to_public_key();
    let mut candidates = target_only;
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let resolved = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate subkey revocation with its authority key");
    assert_eq!(
        resolved
            .subkey(&subkey)
            .expect("revoked subkey policy")
            .policy()
            .revocation_status,
        RevocationStatus::Revoked,
    );
}

#[test]
fn designated_key_revocation_requires_valid_hashed_issuer_metadata() {
    let mut target = generated_test_secret("Issuer Target <issuer-target@example.test>");
    let revoker = generated_test_secret("Issuer Revoker <issuer-revoker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);
    let wrong_key_id = [0xa5; 8].into();
    assert_ne!(wrong_key_id, revoker.primary_key.legacy_key_id());

    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
        Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
            .expect("inconsistent revocation issuer key ID"),
    ];
    let malformed = config
        .sign_key(
            &revoker.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign malformed designated revocation");
    malformed
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("malformed packet remains cryptographically valid");
    assert!(
        SignatureIssuerMetadata::from_signature(&malformed).is_invalid(),
        "the hashed V4 fingerprint and key ID disagree",
    );

    let valid = designated_key_revocation(&target, &revoker);
    let mut issuerless_config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    issuerless_config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("issuer-less revocation creation time"),
    ];
    let issuerless = issuerless_config
        .sign_key(
            &revoker.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign issuer-less designated revocation");
    assert!(SignatureIssuerMetadata::from_signature(&issuerless).is_missing());
    for (case, revocation, expected) in [
        ("malformed", malformed, RevocationStatus::NotRevoked),
        ("valid", valid, RevocationStatus::Revoked),
        ("issuer-less", issuerless, RevocationStatus::Revoked),
    ] {
        let mut candidate = target.clone();
        candidate.details.revocation_signatures.push(revocation);
        let target_public = candidate.to_public_key();
        let revoker_public = revoker.to_public_key();
        let candidates = all_components(&[target_public.clone(), revoker_public]);
        let policy = validate_certificate(
            &target_public,
            &candidates,
            TEST_TIME + 3,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate designated key revocation");

        assert_eq!(policy.primary.revocation_status, expected, "case: {case}");
    }
}

#[test]
fn forged_designated_revoker_metadata_quarantines_the_key_until_disproved() {
    let mut target = generated_test_secret("Target <target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    let attacker = generated_test_secret("Attacker <attacker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);

    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        attacker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("forged issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            revoker.primary_key.legacy_key_id(),
        ))
        .expect("forged issuer key ID"),
    ];
    let forged_revocation = config
        .sign_key(
            &attacker.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign forged designated revocation");
    target.details.revocation_signatures.push(forged_revocation);

    let target_public = target.to_public_key();
    let candidates = all_components(std::slice::from_ref(&target_public));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate forged designated revocation");

    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::Indeterminate,
    );
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::IndeterminateRevocation),
    );

    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let resolved = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("resolve forged designated-revoker metadata");
    assert_eq!(
        resolved.primary.revocation_status,
        RevocationStatus::NotRevoked,
        "the exact authority key disproves the forged signature",
    );
    assert!(resolved.primary_available());
    assert_eq!(resolved.primary_component().authorize_mutation(), Ok(()));
}

#[test]
fn self_revocation_remains_blocking_without_external_authority() {
    let mut target = generated_test_secret("Self Revoked <self-revoked@example.test>");
    let revocation = designated_key_revocation(&target, &target);
    target.details.revocation_signatures.push(revocation);

    let target_public = target.to_public_key();
    let policy = validate_certificate(
        &target_public,
        &all_components(std::slice::from_ref(&target_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate self-revoked certificate");

    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);
    assert!(policy.primary.revoked);
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::Revoked),
    );
}

#[test]
fn sha1_designated_revocation_requires_collision_resistance() {
    let mut target = generated_test_secret_with_kind(
        "SHA-1 Designated Target <sha1-designated-target@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let revoker = generated_test_secret_with_kind(
        "SHA-1 Designated Revoker <sha1-designated-revoker@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    add_designated_revoker_declaration(&mut target, &revoker);

    let revocation = designated_key_revocation_with_hash(&target, &revoker, HashAlgorithm::Sha1);
    revocation
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("SHA-1 designated revocation remains mathematically valid");
    target.details.revocation_signatures.push(revocation);

    let reference_time = TEST_TIME + 3;
    let collision_deadline = SHA1_COLLISION_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 collision-resistance revocation deadline");
    let second_preimage_deadline = SHA1_SECOND_PREIMAGE_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 second-preimage revocation deadline");
    assert!(collision_deadline <= reference_time);
    assert!(reference_time < second_preimage_deadline);

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        reference_time,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate SHA-1 designated revocation");

    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn sha1_designated_revoker_declaration_does_not_receive_revocation_tolerance() {
    let mut target = generated_test_secret_with_kind(
        "SHA-1 Declaration Target <sha1-declaration-target@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let revoker = generated_test_secret_with_kind(
        "SHA-1 Declaration Revoker <sha1-declaration-revoker@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let declaration = designated_revoker_declaration_with_hash(
        &target,
        &revoker,
        Vec::new(),
        HashAlgorithm::Sha1,
    );
    declaration
        .verify_key(target.primary_key.public_key())
        .expect("SHA-1 declaration remains mathematically valid");
    let reference_time = TEST_TIME + 3;
    assert!(SHA1_SECOND_PREIMAGE_REJECT_AT <= reference_time);
    let declaration_deadline = SHA1_SECOND_PREIMAGE_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 second-preimage declaration deadline");
    assert!(reference_time < declaration_deadline);
    assert!(!revocation_authority_declaration_acceptable(
        &declaration,
        reference_time,
        reference_time,
    ));
    target.details.direct_signatures.push(declaration);
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        reference_time,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate modern revocation under a policy-rejected SHA-1 declaration");

    assert_eq!(
        policy
            .revocation_authority_fingerprints()
            .collect::<Vec<_>>(),
        vec![hex_upper(revoker.primary_key.fingerprint().as_bytes())],
        "mathematically verified declarations remain discovery requirements",
    );
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn expired_designated_revoker_declaration_remains_authoritative() {
    let mut target = generated_test_secret("Target <target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    let declaration = designated_revoker_declaration(
        &target,
        &revoker,
        vec![
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                1,
            )))
            .expect("declaration expiration time"),
        ],
    );
    declaration
        .verify_key(target.primary_key.public_key())
        .expect("verify genuine designated-revoker declaration");
    assert!(signature_expired(&declaration, TEST_TIME + 4));
    target.details.direct_signatures.extend([
        declaration,
        direct_self_signature(&target, (TEST_TIME + 3) as u32, Vec::new()),
    ]);

    let revocation = designated_key_revocation(&target, &revoker);
    revocation
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("verify genuine designated revocation");
    target.details.revocation_signatures.push(revocation);

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revocation authorized by an expired declaration");

    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some(TEST_TIME as u32),
        "the selected primary User ID binding remains authoritative for V4 policy",
    );
    assert_eq!(
        policy
            .revocation_authority_fingerprints()
            .collect::<Vec<_>>(),
        vec![hex_upper(revoker.primary_key.fingerprint().as_bytes())],
    );
    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);
}

#[test]
fn expired_designated_revoker_declaration_still_requires_signature_policy() {
    let mut target = generated_test_secret("Target <target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    let declaration = designated_revoker_declaration(
        &target,
        &revoker,
        vec![
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                1,
            )))
            .expect("declaration expiration time"),
            Subpacket::critical(SubpacketData::Experimental(
                100,
                Bytes::from_static(b"unknown critical authority policy"),
            ))
            .expect("unknown critical declaration subpacket"),
        ],
    );
    declaration
        .verify_key(target.primary_key.public_key())
        .expect("critical declaration remains mathematically valid");
    assert!(signature_expired(&declaration, TEST_TIME + 4));
    assert!(!revocation_authority_declaration_acceptable(
        &declaration,
        TEST_TIME + 4,
        TEST_TIME + 4,
    ));
    target.details.direct_signatures.extend([
        declaration,
        direct_self_signature(&target, (TEST_TIME + 3) as u32, Vec::new()),
    ]);
    target
        .details
        .revocation_signatures
        .push(designated_key_revocation(&target, &revoker));

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 4,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate policy-rejected designated-revoker declaration");

    assert_eq!(
        policy
            .revocation_authority_fingerprints()
            .collect::<Vec<_>>(),
        vec![hex_upper(revoker.primary_key.fingerprint().as_bytes())],
        "mathematically verified declarations remain discovery requirements",
    );
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
}

#[test]
fn expired_hard_primary_and_subkey_revocations_remain_effective() {
    let mut primary_secret = generated_test_secret("Expired Hard Primary <primary@example.test>");
    let primary_revocation = expiring_revocation_config(
        &primary_secret,
        SignatureType::KeyRevocation,
        RevocationCode::KeyCompromised,
    )
    .sign_key(
        &primary_secret.primary_key,
        &Password::empty(),
        primary_secret.primary_key.public_key(),
    )
    .expect("sign expiring primary revocation");
    assert!(signature_expired(&primary_revocation, TEST_TIME + 3));
    primary_secret
        .details
        .revocation_signatures
        .push(primary_revocation);

    let primary_public = primary_secret.to_public_key();
    let primary_policy = validate_certificate(
        &primary_public,
        &all_components(std::slice::from_ref(&primary_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired hard primary revocation");
    assert_eq!(
        primary_policy.primary.revocation_status,
        RevocationStatus::Revoked,
    );

    let mut subkey_secret = generated_test_secret("Expired Hard Subkey <subkey@example.test>");
    let subkey = subkey_secret.secret_subkeys[1].key.public_key().clone();
    let subkey_revocation = expiring_revocation_config(
        &subkey_secret,
        SignatureType::SubkeyRevocation,
        RevocationCode::NoReason,
    )
    .sign_subkey_binding(
        &subkey_secret.primary_key,
        subkey_secret.primary_key.public_key(),
        &Password::empty(),
        &subkey,
    )
    .expect("sign expiring subkey revocation");
    assert!(signature_expired(&subkey_revocation, TEST_TIME + 3));
    subkey_secret.secret_subkeys[1]
        .signatures
        .push(subkey_revocation);

    let subkey_public = subkey_secret.to_public_key();
    let subkey_policy = validate_certificate(
        &subkey_public,
        &all_components(std::slice::from_ref(&subkey_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired hard subkey revocation");
    assert_eq!(
        subkey_policy.subkeys[1].revocation_status,
        RevocationStatus::Revoked,
    );
}

#[test]
fn expired_soft_key_and_subkey_revocations_remain_effective() {
    let mut key_secret = generated_test_secret("Expired Soft Key <key@example.test>");
    let key_revocation = expiring_revocation_config(
        &key_secret,
        SignatureType::KeyRevocation,
        RevocationCode::KeySuperseded,
    )
    .sign_key(
        &key_secret.primary_key,
        &Password::empty(),
        key_secret.primary_key.public_key(),
    )
    .expect("sign expiring soft key revocation");
    assert!(signature_expired(&key_revocation, TEST_TIME + 3));
    key_secret
        .details
        .revocation_signatures
        .push(key_revocation);

    let key_public = key_secret.to_public_key();
    let key_policy = validate_certificate(
        &key_public,
        &all_components(std::slice::from_ref(&key_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired soft key revocation");
    assert_eq!(
        key_policy.primary.revocation_status,
        RevocationStatus::Revoked,
    );
    assert!(!key_policy.primary_available());
    assert_eq!(
        key_policy.authorize_primary_renewal(),
        Err(MutationAuthorizationError::Revoked),
    );
    assert_eq!(
        key_policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::Revoked),
    );

    let mut subkey_secret = generated_test_secret("Expired Soft Subkey <subkey@example.test>");
    let subkey = subkey_secret.secret_subkeys[1].key.public_key().clone();
    let subkey_revocation = expiring_revocation_config(
        &subkey_secret,
        SignatureType::SubkeyRevocation,
        RevocationCode::KeyRetired,
    )
    .sign_subkey_binding(
        &subkey_secret.primary_key,
        subkey_secret.primary_key.public_key(),
        &Password::empty(),
        &subkey,
    )
    .expect("sign expiring soft subkey revocation");
    assert!(signature_expired(&subkey_revocation, TEST_TIME + 3));
    subkey_secret.secret_subkeys[1]
        .signatures
        .push(subkey_revocation);

    let subkey_public = subkey_secret.to_public_key();
    let subkey_policy = validate_certificate(
        &subkey_public,
        &all_components(std::slice::from_ref(&subkey_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired soft subkey revocation");
    assert_eq!(
        subkey_policy.subkeys[1].revocation_status,
        RevocationStatus::Revoked,
    );
    assert!(!encryption_component_usable(
        &subkey_policy.subkeys[1],
        TEST_TIME + 3,
    ));
}

#[test]
fn expired_user_id_certification_revocation_is_ineffective() {
    let mut certification_secret =
        generated_test_secret("Expired Certification <certification@example.test>");
    let user_id = certification_secret.details.users[0].id.clone();
    let certification_revocation = expiring_revocation_config(
        &certification_secret,
        SignatureType::CertRevocation,
        RevocationCode::CertUserIdInvalid,
    )
    .sign_certification(
        &certification_secret.primary_key,
        certification_secret.primary_key.public_key(),
        &Password::empty(),
        Tag::UserId,
        &user_id,
    )
    .expect("sign expiring certification revocation");
    assert!(signature_expired(&certification_revocation, TEST_TIME + 3,));
    certification_secret.details.users[0]
        .signatures
        .push(certification_revocation);

    let certification_public = certification_secret.to_public_key();
    let certification_policy = validate_certificate(
        &certification_public,
        &all_components(std::slice::from_ref(&certification_public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired certification revocation");
    assert_eq!(
        certification_policy.user_ids[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(certification_policy.verified_user_ids_for_test().len(), 1);
}

#[test]
fn expired_user_attribute_certification_revocation_is_ineffective() {
    let mut secret = generated_test_secret("Expired Attribute <expired-attribute@example.test>");
    let attribute =
        UserAttribute::new_image(Bytes::from_static(b"expired image")).expect("user attribute");
    let certification = identity_signature(
        &secret,
        SignatureType::CertPositive,
        Tag::UserAttribute,
        &attribute,
        TEST_TIME as u32,
        None,
    );
    let revocation = expiring_revocation_config(
        &secret,
        SignatureType::CertRevocation,
        RevocationCode::CertUserIdInvalid,
    )
    .sign_certification(
        &secret.primary_key,
        secret.primary_key.public_key(),
        &Password::empty(),
        Tag::UserAttribute,
        &attribute,
    )
    .expect("sign expiring User Attribute revocation");
    assert!(signature_expired(&revocation, TEST_TIME + 3));
    secret.details.direct_signatures.clear();
    secret.details.users.clear();
    secret.details.user_attributes = vec![SignedUserAttribute::new(
        attribute,
        vec![certification, revocation],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate expired User Attribute revocation");

    assert_eq!(
        policy.user_attributes[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(
        policy.primary.authenticated,
        "the live User Attribute certification can authenticate the primary key",
    );
}

#[test]
fn soft_primary_revocation_is_superseded_only_by_later_policy_statements() {
    let base = generated_test_secret("Ordered Primary <ordered-primary@example.test>");
    let revocation_time = (TEST_TIME + 20) as u32;

    for (statement_kind, direct_statement) in [("Direct Key", true), ("self-certification", false)]
    {
        for statement_time in [revocation_time - 1, revocation_time, revocation_time + 1] {
            let revocation = key_revocation_with_reason(
                &base,
                revocation_time,
                HashAlgorithm::Sha256,
                RevocationCode::KeyRetired,
            );
            let mut candidate = base.clone();
            candidate.details.revocation_signatures = vec![revocation];
            if direct_statement {
                candidate = certificate_with_only_direct_signatures(
                    candidate,
                    vec![direct_self_signature(&base, statement_time, Vec::new())],
                );
            } else {
                candidate.details.direct_signatures.clear();
                candidate.details.user_attributes.clear();
                candidate.details.users[0].signatures = vec![self_certification(
                    &base,
                    statement_time,
                    HashAlgorithm::Sha256,
                    Vec::new(),
                )];
            }

            let public = candidate.to_public_key();
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                TEST_TIME + 30,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("evaluate ordered primary revocation");
            let restored = statement_time > revocation_time;
            assert_eq!(
                policy
                    .primary
                    .effective_signature
                    .and_then(signature_creation_time),
                Some(statement_time),
                "{statement_kind}, statement time {statement_time}",
            );
            assert_eq!(
                policy.primary.revocation_status,
                if restored {
                    RevocationStatus::NotRevoked
                } else {
                    RevocationStatus::Revoked
                },
                "{statement_kind}, statement time {statement_time}",
            );
            assert_eq!(
                policy.primary_available(),
                restored,
                "{statement_kind}, statement time {statement_time}",
            );
            assert_eq!(
                policy.authorize_primary_renewal(),
                if restored {
                    Ok(RenewalAuthorization::Authenticated)
                } else {
                    Err(MutationAuthorizationError::Revoked)
                },
                "{statement_kind}, statement time {statement_time}",
            );
        }
    }
}

#[test]
fn primary_restoration_uses_newest_direct_or_primary_identity_without_changing_policy_fields() {
    let mut secret = generated_test_secret("Primary Restore <primary-restore@example.test>");
    secret.details.users[0].signatures = vec![self_certification(
        &secret,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary identity"),
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(600)))
                .expect("identity expiration"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES128].into(),
            ))
            .expect("identity preferences"),
        ],
    )];
    secret.details.direct_signatures = vec![direct_self_signature(
        &secret,
        (TEST_TIME + 20) as u32,
        vec![
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("direct preferences"),
        ],
    )];
    secret.details.revocation_signatures = vec![key_revocation_with_reason(
        &secret,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        RevocationCode::KeySuperseded,
    )];
    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate independent revocation ordering");
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked
    );
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 1) as u32)
    );
    assert_eq!(policy.primary.key_expiration_seconds, Some(600));
    assert_eq!(
        policy.primary.preferred_symmetric.as_deref(),
        Some([u8::from(SymmetricKeyAlgorithm::AES128)].as_slice())
    );
}

#[test]
fn nonprimary_identity_and_user_attribute_cannot_restore_primary_key() {
    let mut base = generated_test_secret("Selected Identity <selected@example.test>");
    base.details.direct_signatures.clear();
    base.details.users[0].signatures = vec![self_certification(
        &base,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![Subpacket::regular(SubpacketData::IsPrimary(true)).expect("selected identity")],
    )];
    base.details.revocation_signatures = vec![key_revocation_with_reason(
        &base,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        RevocationCode::KeyRetired,
    )];
    for attribute_fallback in [false, true] {
        let mut candidate = base.clone();
        if attribute_fallback {
            let attribute = UserAttribute::new_image(Bytes::from_static(b"restoring image"))
                .expect("attribute");
            let certification = user_attribute_self_certification(
                &base,
                &attribute,
                (TEST_TIME + 20) as u32,
                HashAlgorithm::Sha256,
                Vec::new(),
            );
            candidate.details.users.clear();
            candidate.details.user_attributes =
                vec![SignedUserAttribute::new(attribute, vec![certification])];
        } else {
            candidate.details.users.push(signed_user_id(
                &base,
                "Nonprimary <nonprimary@example.test>",
                (TEST_TIME + 20) as u32,
                Vec::new(),
            ));
        }
        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate unrelated identity statement");
        assert!(policy.primary.authenticated);
        assert_eq!(
            policy.primary.revocation_status,
            RevocationStatus::Revoked,
            "attribute fallback: {attribute_fallback}"
        );
        assert!(!policy.primary_available());
    }
}

#[test]
fn primary_restoration_expires_and_another_live_statement_can_restore_it() {
    let base = generated_test_secret("Temporary Restore <temporary@example.test>");
    for direct in [false, true] {
        let expiration = vec![
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                10,
            )))
            .expect("restoring signature expiration"),
        ];
        let restoring = if direct {
            direct_self_signature(&base, (TEST_TIME + 20) as u32, expiration)
        } else {
            self_certification(
                &base,
                (TEST_TIME + 20) as u32,
                HashAlgorithm::Sha256,
                expiration,
            )
        };
        let mut candidate = base.clone();
        candidate.details.revocation_signatures = vec![key_revocation_with_reason(
            &base,
            (TEST_TIME + 10) as u32,
            HashAlgorithm::Sha256,
            RevocationCode::KeyRetired,
        )];
        if direct {
            candidate.details.direct_signatures.push(restoring);
        } else {
            candidate.details.users[0].signatures.push(restoring);
        }
        for (offset, restored) in [(19, false), (20, true), (29, true), (30, false)] {
            let public = candidate.to_public_key();
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                TEST_TIME + offset,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("evaluate temporary restoration");
            assert_eq!(
                policy.primary_available(),
                restored,
                "direct {direct}, offset {offset}"
            );
            assert_eq!(
                policy.authorize_primary_renewal(),
                if restored {
                    Ok(RenewalAuthorization::Authenticated)
                } else {
                    Err(MutationAuthorizationError::Revoked)
                }
            );
        }
        candidate
            .details
            .direct_signatures
            .push(direct_self_signature(
                &base,
                (TEST_TIME + 35) as u32,
                Vec::new(),
            ));
        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 40,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate subsequent live restoration");
        assert!(policy.primary_available());
        assert_eq!(public.details.revocation_signatures.len(), 1);
    }
}

#[test]
fn unacceptable_owner_statements_cannot_restore_primary_key() {
    let base = generated_test_secret_with_kind(
        "Rejected Restore <rejected@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let other = generated_test_secret("Other Owner <other@example.test>");
    let now = SHA1_SECOND_PREIMAGE_REJECT_AT.max(TEST_TIME + 30);
    for direct in [false, true] {
        for defect in ["future", "wrong owner", "critical", "weak"] {
            let signer = if defect == "wrong owner" {
                &other
            } else {
                &base
            };
            let time = if defect == "future" {
                now + 1
            } else {
                TEST_TIME + 20
            };
            let hash = if defect == "weak" {
                HashAlgorithm::Sha1
            } else {
                HashAlgorithm::Sha256
            };
            let additional = if defect == "critical" {
                vec![
                    Subpacket::critical(SubpacketData::Experimental(
                        100,
                        Bytes::from_static(b"unknown policy"),
                    ))
                    .expect("critical subpacket"),
                ]
            } else {
                Vec::new()
            };
            let statement = if direct {
                direct_self_signature_with_hash(signer, time as u32, hash, additional)
            } else {
                user_id_self_certification(
                    signer,
                    &base.details.users[0].id,
                    time as u32,
                    hash,
                    additional,
                )
            };
            let mut candidate = base.clone();
            candidate.details.revocation_signatures = vec![key_revocation_with_reason(
                &base,
                (TEST_TIME + 10) as u32,
                HashAlgorithm::Sha256,
                RevocationCode::KeyRetired,
            )];
            if direct {
                candidate.details.direct_signatures.push(statement);
            } else {
                candidate.details.users[0].signatures.push(statement);
            }
            let public = candidate.to_public_key();
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                now,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("evaluate rejected restoring evidence");
            assert_eq!(
                policy.primary.revocation_status,
                RevocationStatus::Revoked,
                "direct {direct}, {defect}"
            );
            assert_eq!(
                policy.authorize_primary_renewal(),
                Err(MutationAuthorizationError::Revoked)
            );
        }
    }
}

#[test]
fn same_second_hard_primary_revocation_remains_final() {
    let base = generated_test_secret("Hard Primary <hard-primary@example.test>");
    let creation_time = (TEST_TIME + 20) as u32;
    let direct = direct_self_signature(&base, creation_time, Vec::new());
    let revocation = key_revocation_with_reason(
        &base,
        creation_time,
        HashAlgorithm::Sha256,
        RevocationCode::KeyCompromised,
    );
    let mut candidate = certificate_with_only_direct_signatures(base, vec![direct]);
    candidate.details.revocation_signatures = vec![revocation];

    let public = candidate.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate same-second hard primary revocation");

    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked,);
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::Revoked),
    );
}

#[test]
fn v4_hard_primary_self_revocation_rejects_wrong_version_unhashed_issuer_fingerprint() {
    let base = generated_test_secret("Poisoned Revocation <poisoned-revocation@example.test>");
    let revocation = key_revocation_with_reason(
        &base,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        RevocationCode::KeyCompromised,
    );
    let revocation = signature_with_unhashed_issuer_hints(
        &revocation,
        vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
                [0x66; 32],
            )))
            .expect("wrong-version unhashed issuer fingerprint"),
        ],
    );
    revocation
        .verify_key(base.primary_key.public_key())
        .expect("unhashed mutation preserves the revocation signature");
    let mut candidate = base;
    candidate.details.revocation_signatures = vec![revocation];

    let public = candidate.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revocation with a malformed issuer fingerprint");

    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary_available());
    assert_eq!(policy.primary_component().authorize_mutation(), Ok(()));
}

#[test]
fn v4_hard_primary_self_revocation_ignores_over_limit_unhashed_issuer_hints() {
    let base = generated_test_secret("Flooded Revocation <flooded-revocation@example.test>");
    let wrong_key_id = KeyId::from([0xa5; 8]);
    assert_ne!(wrong_key_id, base.primary_key.legacy_key_id());
    let revocation = key_revocation_with_reason(
        &base,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        RevocationCode::KeyCompromised,
    );
    let revocation = signature_with_unhashed_issuer_hints(
        &revocation,
        (0..=MAX_SIGNATURE_ISSUER_HINTS)
            .map(|_| {
                Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
                    .expect("unhashed issuer key ID")
            })
            .collect(),
    );
    revocation
        .verify_key(base.primary_key.public_key())
        .expect("unhashed mutation preserves the revocation signature");
    let mut candidate = base;
    candidate.details.revocation_signatures = vec![revocation];

    let public = candidate.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revocation with flooded advisory issuer hints");

    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);
    assert!(!policy.primary_available());
    assert_eq!(
        policy.primary_component().authorize_mutation(),
        Err(MutationAuthorizationError::Revoked),
    );
}

#[test]
fn user_id_revocation_supersedes_an_equal_or_older_certification() {
    let base = generated_test_secret("Ordered User ID <ordered-user-id@example.test>");
    let user_id = base.details.users[0].id.clone();
    let revocation_time = (TEST_TIME + 20) as u32;

    for statement_time in [revocation_time - 1, revocation_time, revocation_time + 1] {
        let certification = user_id_self_certification(
            &base,
            &user_id,
            statement_time,
            HashAlgorithm::Sha256,
            Vec::new(),
        );
        let revocation = identity_signature(
            &base,
            SignatureType::CertRevocation,
            Tag::UserId,
            &user_id,
            revocation_time,
            Some(RevocationCode::CertUserIdInvalid),
        );
        let mut candidate = base.clone();
        candidate.details.direct_signatures.clear();
        candidate.details.users[0].signatures = vec![certification, revocation];

        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate ordered User ID revocation");
        let expected_revoked = statement_time <= revocation_time;

        assert_eq!(
            policy.user_ids[0].revocation_status,
            if expected_revoked {
                RevocationStatus::Revoked
            } else {
                RevocationStatus::NotRevoked
            },
            "statement time {statement_time}",
        );
        assert_eq!(
            policy.user_ids[0].authenticated(),
            !expected_revoked,
            "statement time {statement_time}",
        );
        assert_eq!(
            policy.verified_user_ids_for_test().is_empty(),
            expected_revoked,
            "statement time {statement_time}",
        );
    }
}

#[test]
fn user_attribute_revocation_supersedes_an_equal_or_older_certification() {
    let base = generated_test_secret("Ordered Attribute <ordered-attribute@example.test>");
    let attribute =
        UserAttribute::new_image(Bytes::from_static(b"ordered image")).expect("user attribute");
    let revocation_time = (TEST_TIME + 20) as u32;

    for statement_time in [revocation_time - 1, revocation_time, revocation_time + 1] {
        let certification = user_attribute_self_certification(
            &base,
            &attribute,
            statement_time,
            HashAlgorithm::Sha256,
            Vec::new(),
        );
        let revocation = identity_signature(
            &base,
            SignatureType::CertRevocation,
            Tag::UserAttribute,
            &attribute,
            revocation_time,
            Some(RevocationCode::CertUserIdInvalid),
        );
        let mut candidate = base.clone();
        candidate.details.direct_signatures.clear();
        candidate.details.users.clear();
        candidate.details.user_attributes = vec![SignedUserAttribute::new(
            attribute.clone(),
            vec![certification, revocation],
        )];

        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate ordered User Attribute revocation");
        let expected_revoked = statement_time <= revocation_time;

        assert_eq!(
            policy.user_attributes[0].revocation_status,
            if expected_revoked {
                RevocationStatus::Revoked
            } else {
                RevocationStatus::NotRevoked
            },
            "statement time {statement_time}",
        );
        assert_eq!(
            policy.user_attributes[0].authenticated(),
            !expected_revoked,
            "statement time {statement_time}",
        );
        assert_eq!(
            policy.primary.authenticated, !expected_revoked,
            "User Attribute primary-policy fallback at {statement_time}",
        );
    }
}

#[test]
fn soft_subkey_revocation_is_superseded_only_by_later_bindings() {
    const SUBKEY_INDEX: usize = 1;

    let base = generated_test_secret("Ordered Subkey <ordered-subkey@example.test>");
    let revocation_time = (TEST_TIME + 20) as u32;

    for statement_time in [revocation_time - 1, revocation_time, revocation_time + 1] {
        let binding = subkey_binding_at(&base, SUBKEY_INDEX, statement_time);
        let revocation = subkey_revocation_at(
            &base,
            SUBKEY_INDEX,
            revocation_time,
            RevocationCode::KeyRetired,
        );
        let mut candidate = base.clone();
        candidate.secret_subkeys[SUBKEY_INDEX].signatures = vec![binding, revocation];

        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate ordered subkey revocation");
        let restored = statement_time > revocation_time;
        let component = &policy.subkeys[SUBKEY_INDEX];
        assert_eq!(
            component
                .effective_signature
                .and_then(signature_creation_time),
            Some(statement_time),
            "statement time {statement_time}",
        );
        assert_eq!(
            component.revocation_status,
            if restored {
                RevocationStatus::NotRevoked
            } else {
                RevocationStatus::Revoked
            },
            "statement time {statement_time}",
        );
        assert_eq!(
            encryption_component_usable(component, TEST_TIME + 30),
            restored,
            "statement time {statement_time}",
        );
    }

    let creation_time = revocation_time;
    let binding = subkey_binding_at(&base, SUBKEY_INDEX, creation_time);
    let hard_revocation = subkey_revocation_at(
        &base,
        SUBKEY_INDEX,
        creation_time,
        RevocationCode::KeyCompromised,
    );
    let mut candidate = base;
    candidate.secret_subkeys[SUBKEY_INDEX].signatures = vec![binding, hard_revocation];
    let public = candidate.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate same-second hard subkey revocation");
    assert_eq!(
        policy.subkeys[SUBKEY_INDEX].revocation_status,
        RevocationStatus::Revoked,
    );
    assert!(!encryption_component_usable(
        &policy.subkeys[SUBKEY_INDEX],
        TEST_TIME + 30,
    ));
}

#[test]
fn subkey_restoration_requires_live_policy_acceptable_binding_for_that_subkey() {
    let base = generated_test_secret_with_kind(
        "Subkey Restore <subkey-restore@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let reference_time = SHA1_SECOND_PREIMAGE_REJECT_AT.max(TEST_TIME + 30);
    let subkey_index = 1;
    for defect in [
        "none",
        "future",
        "expired",
        "weak",
        "critical",
        "wrong subkey",
    ] {
        let mut candidate = base.clone();
        let subkey = base.secret_subkeys[subkey_index].key.public_key();
        let binding = subkey_binding_at(&base, subkey_index, (TEST_TIME + 20) as u32);
        let mut config = binding.config().cloned().expect("binding config");
        if defect == "future" {
            config
                .hashed_subpackets
                .retain(|packet| !matches!(packet.data, SubpacketData::SignatureCreationTime(_)));
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    (reference_time + 1) as u32,
                )))
                .expect("future creation"),
            );
        }
        if defect == "expired" {
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                    10,
                )))
                .expect("binding expiration"),
            );
        }
        if defect == "weak" {
            config.hash_alg = HashAlgorithm::Sha1;
        }
        if defect == "critical" {
            config.hashed_subpackets.push(
                Subpacket::critical(SubpacketData::Experimental(
                    100,
                    Bytes::from_static(b"unknown binding policy"),
                ))
                .expect("critical binding policy"),
            );
        }
        let signed_target = if defect == "wrong subkey" {
            base.secret_subkeys[0].key.public_key()
        } else {
            subkey
        };
        let binding = config
            .sign_subkey_binding(
                &base.primary_key,
                base.primary_key.public_key(),
                &Password::empty(),
                signed_target,
            )
            .expect("sign restoring binding");
        candidate.secret_subkeys[subkey_index].signatures.extend([
            subkey_revocation_at(
                &base,
                subkey_index,
                (TEST_TIME + 10) as u32,
                RevocationCode::KeyRetired,
            ),
            binding,
        ]);
        let public = candidate.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            reference_time,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate restoring binding");
        let restored = defect == "none";
        let component = policy.subkey(subkey).expect("subkey policy");
        assert_eq!(component.encryption_usable(), restored, "{defect}");
        assert_eq!(
            component.authorize_renewal(),
            if restored {
                Ok(RenewalAuthorization::Authenticated)
            } else {
                Err(MutationAuthorizationError::Revoked)
            },
            "{defect}"
        );
        if defect == "expired" {
            for (offset, restored) in [(29, true), (30, false)] {
                let policy = validate_certificate(
                    &public,
                    &all_components(std::slice::from_ref(&public)),
                    TEST_TIME + offset,
                    &mut OpenPgpPolicyBudget::default(),
                )
                .expect("evaluate binding lifetime");
                assert_eq!(
                    policy
                        .subkey(subkey)
                        .expect("subkey policy")
                        .encryption_usable(),
                    restored
                );
            }
        }
    }
}

#[test]
fn v6_primary_restoration_uses_direct_key_signature() {
    let mut secret = pgp::composed::SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(pgp::composed::KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("V6 Restore <v6-restore@example.test>".to_owned())
        .build()
        .expect("v6 key parameters")
        .generate(crate::openpgp::crypto::secret::AwsLcRng)
        .expect("v6 key");
    let mut config = secret.details.direct_signatures[0]
        .config()
        .cloned()
        .expect("v6 Direct Key config");
    assert_eq!(secret.primary_key.version(), KeyVersion::V6);
    config
        .hashed_subpackets
        .retain(|packet| !matches!(packet.data, SubpacketData::SignatureCreationTime(_)));
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 10) as u32,
        )))
        .expect("revocation time"),
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::RevocationReason(
            RevocationCode::KeyRetired,
            Vec::new().into(),
        ))
        .expect("retirement"),
    );
    config.typ = SignatureType::KeyRevocation;
    let revocation = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("v6 revocation");
    secret.details.revocation_signatures.push(revocation);
    let mut config = secret.details.direct_signatures[0]
        .config()
        .cloned()
        .expect("v6 Direct Key config");
    config
        .hashed_subpackets
        .retain(|packet| !matches!(packet.data, SubpacketData::SignatureCreationTime(_)));
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 20) as u32,
        )))
        .expect("restoration time"),
    );
    let restoring = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("v6 restoration");
    secret.details.direct_signatures.push(restoring);
    let public = secret.to_public_key();
    for (offset, restored) in [(19, false), (20, true)] {
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + offset,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate v6 restoration");
        assert_eq!(policy.primary_available(), restored);
    }
}

#[test]
fn newer_binding_signatures_restore_soft_revoked_key_and_subkey_with_retained_evidence() {
    let mut primary_secret = generated_test_secret("Retired Primary <primary@example.test>");
    let primary_revocation = key_revocation_with_reason(
        &primary_secret,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        RevocationCode::KeyRetired,
    );
    let newer_primary_binding = self_certification(
        &primary_secret,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    primary_secret
        .details
        .revocation_signatures
        .push(primary_revocation);
    primary_secret.details.users[0]
        .signatures
        .push(newer_primary_binding);

    let primary_public = primary_secret.to_public_key();
    let primary_policy = validate_certificate(
        &primary_public,
        &all_components(std::slice::from_ref(&primary_public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate retired primary with a newer self-certification");
    assert_eq!(
        primary_policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 20) as u32),
    );
    assert_eq!(
        primary_policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(primary_policy.primary_available());
    assert_eq!(primary_public.details.revocation_signatures.len(), 1);
    assert_eq!(
        primary_policy.authorize_primary_renewal(),
        Ok(RenewalAuthorization::Authenticated),
    );

    let mut subkey_secret = generated_test_secret("Retired Subkey <subkey@example.test>");
    let primary = &subkey_secret.primary_key;
    let subkey = subkey_secret.secret_subkeys[1].key.public_key().clone();
    let mut revocation_config = SignatureConfig::v4(
        SignatureType::SubkeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    revocation_config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 10) as u32,
        )))
        .expect("subkey revocation creation time"),
        Subpacket::regular(SubpacketData::RevocationReason(
            RevocationCode::KeyRetired,
            Vec::new().into(),
        ))
        .expect("subkey revocation reason"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("subkey revocation issuer fingerprint"),
    ];
    revocation_config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("subkey revocation issuer key ID"),
    ];
    let subkey_revocation = revocation_config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign retired subkey revocation");

    let mut binding_config = subkey_secret.secret_subkeys[1]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    binding_config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_)
        )
    });
    binding_config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 20) as u32,
        )))
        .expect("newer binding creation time"),
    );
    let newer_binding = binding_config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign newer subkey binding");
    subkey_secret.secret_subkeys[1]
        .signatures
        .extend([subkey_revocation, newer_binding]);

    let subkey_public = subkey_secret.to_public_key();
    let subkey_policy = validate_certificate(
        &subkey_public,
        &all_components(std::slice::from_ref(&subkey_public)),
        TEST_TIME + 30,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked subkey with a newer binding signature");
    assert_eq!(
        subkey_policy.subkeys[1]
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 20) as u32),
    );
    assert_eq!(
        subkey_policy.subkeys[1].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(encryption_component_usable(
        &subkey_policy.subkeys[1],
        TEST_TIME + 30,
    ));
    assert!(
        subkey_public.public_subkeys[1]
            .signatures
            .iter()
            .any(|signature| signature.typ() == Some(SignatureType::SubkeyRevocation))
    );
}

#[test]
fn designated_revoker_can_revoke_certifications() {
    let mut target = generated_test_secret("Target <target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);

    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        revoker.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 2) as u32,
        )))
        .expect("certification revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("certification revocation issuer fingerprint"),
    ];
    let external_revocation = config
        .sign_certification(
            &revoker.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("sign external certification revocation");
    target.details.users[0].signatures.push(external_revocation);

    let target_public = target.to_public_key();
    let revoker_public = revoker.to_public_key();
    let mut candidates = all_components(std::slice::from_ref(&target_public));
    candidates.extend(all_components(std::slice::from_ref(&revoker_public)));
    let policy = validate_certificate(
        &target_public,
        &candidates,
        TEST_TIME + 3,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate externally signed certification revocation");

    assert!(policy.verified_user_ids_for_test().is_empty());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
}

#[test]
fn designated_certification_revocation_requires_consistent_hashed_issuer_metadata() {
    let mut target = generated_test_secret("Issuer Target <issuer-target@example.test>");
    let revoker = generated_test_secret("Issuer Revoker <issuer-revoker@example.test>");
    add_designated_revoker_declaration(&mut target, &revoker);
    let user_id = target.details.users[0].id.clone();
    let revocation = |issuer_subpackets: Vec<Subpacket>| {
        let mut config = SignatureConfig::v4(
            SignatureType::CertRevocation,
            revoker.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                (TEST_TIME + 2) as u32,
            )))
            .expect("certification revocation creation time"),
        );
        config.hashed_subpackets.extend(issuer_subpackets);
        config
            .sign_certification(
                &revoker.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign designated certification revocation")
    };

    let wrong_key_id = [0xa5; 8].into();
    assert_ne!(wrong_key_id, revoker.primary_key.legacy_key_id());
    let malformed = revocation(vec![
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("certification revocation issuer fingerprint"),
        Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
            .expect("inconsistent certification revocation issuer key ID"),
    ]);
    malformed
        .verify_third_party_certification(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
            Tag::UserId,
            &user_id,
        )
        .expect("malformed packet remains cryptographically valid");
    assert!(
        SignatureIssuerMetadata::from_signature(&malformed).is_invalid(),
        "the hashed V4 fingerprint and key ID disagree",
    );
    let valid = revocation(vec![
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("certification revocation issuer fingerprint"),
    ]);
    let issuerless = revocation(Vec::new());
    assert!(SignatureIssuerMetadata::from_signature(&issuerless).is_missing());

    for (case, revocation, expected) in [
        ("malformed", malformed, RevocationStatus::NotRevoked),
        ("valid", valid, RevocationStatus::Revoked),
        ("issuer-less", issuerless, RevocationStatus::Revoked),
    ] {
        let mut candidate = target.clone();
        candidate.details.users[0].signatures.push(revocation);
        let target_public = candidate.to_public_key();
        let revoker_public = revoker.to_public_key();
        let candidates = all_components(&[target_public.clone(), revoker_public]);
        let policy = validate_certificate(
            &target_public,
            &candidates,
            TEST_TIME + 3,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate designated certification revocation");

        assert_eq!(
            policy.user_ids[0].revocation_status, expected,
            "case: {case}",
        );
    }
}

#[test]
fn validated_certificate_keeps_one_certificate_time_and_component_identity() {
    let public = generated_test_secret("Validated View <validated@example.test>").to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let reference_time = TEST_TIME + 1;
    let validated = validate_certificate(
        &public,
        &candidates,
        reference_time,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("validate certificate");

    assert!(std::ptr::eq(validated.certificate(), &public));
    assert_eq!(validated.reference_time(), reference_time);
    for subkey in &public.public_subkeys {
        let component = validated
            .subkey(&subkey.key)
            .expect("component lookup by fingerprint");
        assert_eq!(
            component.policy().key.fingerprint(),
            subkey.key.fingerprint(),
        );
    }
}

#[test]
fn future_subkey_binding_is_not_authenticated_early() {
    let mut secret = generated_test_secret("Future Binding <future-binding@example.test>");
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[1].key.public_key().clone();
    let mut config = secret.secret_subkeys[1]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::SignatureCreationTime(_)));
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 10) as u32,
        )))
        .expect("future binding creation time"),
    );
    let binding = config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign future subkey binding");
    binding
        .verify_subkey_binding(primary.public_key(), &subkey)
        .expect("future binding remains mathematically valid");
    secret.secret_subkeys[1].signatures = vec![binding];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        TEST_TIME + 5,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect future binding");

    assert!(!policy.subkeys[1].authenticated);
    assert!(policy.subkeys[1].effective_signature.is_none());
}

#[test]
fn future_key_is_not_authenticated_by_an_already_live_self_signature() {
    let mut secret = generated_test_secret("Future Key <future-key@example.test>");
    let certification = self_certification(
        &secret,
        (TEST_TIME - 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    secret.details.direct_signatures.clear();
    secret.details.users[0].signatures = vec![certification];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        TEST_TIME - 5,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect future key");

    assert!(policy.primary.effective_signature.is_some());
    assert!(!policy.primary.authenticated);
    assert!(!signing_component_usable(
        &policy.primary,
        TEST_TIME - 5,
        false,
    ));
}

#[test]
fn unknown_critical_subpacket_cannot_authenticate_user_id() {
    let mut secret = generated_test_secret("Critical Packet <critical@example.test>");
    let certification = self_certification(
        &secret,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::critical(SubpacketData::Experimental(
                100,
                Bytes::from_static(b"unknown critical policy"),
            ))
            .expect("unknown critical subpacket"),
        ],
    );
    certification
        .verify_certification(
            secret.primary_key.public_key(),
            Tag::UserId,
            &secret.details.users[0].id,
        )
        .expect("critical certification remains mathematically valid");
    secret.details.direct_signatures.clear();
    secret.details.users[0].signatures = vec![certification];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        reference_time(None),
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect critical certification");

    assert!(policy.verified_user_ids_for_test().is_empty());
    assert!(!policy.primary.authenticated);
}

#[test]
fn sha1_self_signature_cannot_authenticate_at_current_reference_time() {
    let mut secret = generated_test_secret_with_kind(
        "Weak Hash <weak-hash@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let certification =
        self_certification(&secret, TEST_TIME as u32, HashAlgorithm::Sha1, Vec::new());
    certification
        .verify_certification(
            secret.primary_key.public_key(),
            Tag::UserId,
            &secret.details.users[0].id,
        )
        .expect("SHA-1 certification remains mathematically valid");
    secret.details.direct_signatures.clear();
    secret.details.users[0].signatures = vec![certification];
    let now = reference_time(None);
    assert!(now >= SHA1_SECOND_PREIMAGE_REJECT_AT);

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        now,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect SHA-1 certification");

    assert!(policy.verified_user_ids_for_test().is_empty());
    assert!(!policy.primary.authenticated);
}

#[test]
fn sha1_user_id_self_certification_keeps_second_preimage_compatibility() {
    let creation_time = SHA1_COLLISION_REJECT_AT - 1;
    let mut secret = generated_test_secret_at_with_kind(
        "SHA-1 User ID <sha1-user-id@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
        creation_time,
    );
    let certification = self_certification(
        &secret,
        creation_time as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    );
    certification
        .verify_certification(
            secret.primary_key.public_key(),
            Tag::UserId,
            &secret.details.users[0].id,
        )
        .expect("SHA-1 User ID certification remains mathematically valid");
    secret.details.direct_signatures.clear();
    secret.details.users[0].signatures = vec![certification];

    assert_eq!(
        SHA1_COLLISION_REJECT_AT.cmp(&SHA1_SECOND_PREIMAGE_REJECT_AT),
        std::cmp::Ordering::Less,
    );
    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        SHA1_COLLISION_REJECT_AT,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate SHA-1 User ID certification after the collision cutoff");

    assert_eq!(
        policy.verified_user_ids_for_test(),
        vec!["SHA-1 User ID <sha1-user-id@example.test>".to_owned()],
    );
    assert!(policy.primary.authenticated);
}

#[test]
fn sha1_user_attribute_self_certification_requires_collision_resistance() {
    let creation_time = SHA1_COLLISION_REJECT_AT - 1;
    let mut secret = generated_test_secret_at_with_kind(
        "SHA-1 Attribute Owner <sha1-attribute@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
        creation_time,
    );
    let attribute = UserAttribute::new_image(Bytes::from_static(
        b"SHA-1 image attribute collision-policy fixture",
    ))
    .expect("create User Attribute");
    let certification = user_attribute_self_certification(
        &secret,
        &attribute,
        creation_time as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    );
    certification
        .verify_certification(
            secret.primary_key.public_key(),
            Tag::UserAttribute,
            &attribute,
        )
        .expect("SHA-1 User Attribute certification remains mathematically valid");
    secret.details.direct_signatures.clear();
    secret.details.users.clear();
    secret.details.user_attributes = vec![SignedUserAttribute::new(attribute, vec![certification])];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let before_cutoff = validate_certificate(
        &public,
        &candidates,
        SHA1_COLLISION_REJECT_AT - 1,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate User Attribute just before the SHA-1 collision cutoff");
    assert!(before_cutoff.primary.authenticated);

    let at_cutoff = validate_certificate(
        &public,
        &candidates,
        SHA1_COLLISION_REJECT_AT,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate User Attribute at the SHA-1 collision cutoff");
    assert!(!at_cutoff.primary.authenticated);
}

#[test]
fn user_id_self_revocation_hash_security_matches_safe_text_boundaries() {
    assert_eq!(
        user_id_self_revocation_hash_security(&[b'a'; 96]),
        SelfRevocationHashSecurity::SecondPreimageResistance,
    );
    assert_eq!(
        user_id_self_revocation_hash_security(&[b'a'; 97]),
        SelfRevocationHashSecurity::CollisionResistance,
    );
    assert_eq!(
        user_id_self_revocation_hash_security("visible\u{0007}control".as_bytes()),
        SelfRevocationHashSecurity::CollisionResistance,
    );
    assert_eq!(
        user_id_self_revocation_hash_security(&[0xff]),
        SelfRevocationHashSecurity::CollisionResistance,
    );
}

#[test]
fn sha1_user_id_self_revocation_compatibility_is_limited_to_safe_text() {
    let collision_deadline = SHA1_COLLISION_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 collision-resistance revocation deadline");
    let key_creation_time = collision_deadline - 3;
    let base = generated_test_secret_at_with_kind(
        "SHA-1 Revocation Owner <sha1-revocation-owner@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
        key_creation_time,
    );

    for (user_id_len, expected_status) in [
        (96, RevocationStatus::Revoked),
        (97, RevocationStatus::NotRevoked),
    ] {
        let mut secret = base.clone();
        let user_id = UserId::from_str(Default::default(), "u".repeat(user_id_len))
            .expect("create boundary User ID");
        let certification = user_id_self_certification(
            &secret,
            &user_id,
            (key_creation_time + 1) as u32,
            HashAlgorithm::Sha256,
            Vec::new(),
        );
        let revocation = identity_certification_revocation_with_options(
            &secret,
            &secret,
            Tag::UserId,
            &user_id,
            (key_creation_time + 2) as u32,
            IdentityCertificationRevocationOptions {
                hash_algorithm: HashAlgorithm::Sha1,
                additional_hashed_subpackets: Vec::new(),
                unhashed_subpackets: Vec::new(),
            },
        );
        revocation
            .verify_certification(secret.primary_key.public_key(), Tag::UserId, &user_id)
            .expect("SHA-1 User ID revocation remains mathematically valid");
        secret.details.users = vec![SignedUser::new(user_id, vec![certification, revocation])];

        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            collision_deadline,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate boundary User ID revocation");

        assert_eq!(
            policy.user_ids[0].revocation_status, expected_status,
            "User ID length: {user_id_len}",
        );
        assert_eq!(
            policy.user_ids[0].authenticated(),
            expected_status == RevocationStatus::NotRevoked,
            "User ID length: {user_id_len}",
        );
    }
}

#[test]
fn sha1_user_attribute_self_revocation_requires_collision_resistance() {
    let collision_deadline = SHA1_COLLISION_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 collision-resistance revocation deadline");
    let key_creation_time = collision_deadline - 3;
    let mut secret = generated_test_secret_at_with_kind(
        "SHA-1 Attribute Revocation <sha1-attribute-revocation@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
        key_creation_time,
    );
    let attribute = UserAttribute::new_image(Bytes::from_static(b"revoked image attribute"))
        .expect("create User Attribute");
    let certification = user_attribute_self_certification(
        &secret,
        &attribute,
        (key_creation_time + 1) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let revocation = identity_certification_revocation_with_options(
        &secret,
        &secret,
        Tag::UserAttribute,
        &attribute,
        (key_creation_time + 2) as u32,
        IdentityCertificationRevocationOptions {
            hash_algorithm: HashAlgorithm::Sha1,
            additional_hashed_subpackets: Vec::new(),
            unhashed_subpackets: Vec::new(),
        },
    );
    revocation
        .verify_certification(
            secret.primary_key.public_key(),
            Tag::UserAttribute,
            &attribute,
        )
        .expect("SHA-1 User Attribute revocation remains mathematically valid");
    secret.details.user_attributes = vec![SignedUserAttribute::new(
        attribute,
        vec![certification, revocation],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        collision_deadline,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate User Attribute revocation at the collision cutoff");

    assert_eq!(
        policy.user_attributes[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.user_attributes[0].authenticated());
}

#[test]
fn sha1_self_revocation_tolerance_ends_at_2030_cutoff() {
    let mut secret = generated_test_secret_with_kind(
        "Weak Revocation <weak-revocation@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let revocation = key_revocation(&secret, TEST_TIME as u32, HashAlgorithm::Sha1);
    revocation
        .verify_key(secret.primary_key.public_key())
        .expect("SHA-1 revocation remains mathematically valid");
    secret.details.revocation_signatures.push(revocation);

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let revocation_cutoff = SHA1_SECOND_PREIMAGE_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 revocation cutoff");
    let before_cutoff = validate_certificate(
        &public,
        &candidates,
        revocation_cutoff - 1,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate just before SHA-1 revocation cutoff");
    assert_eq!(
        before_cutoff.primary.revocation_status,
        RevocationStatus::Revoked,
    );

    let at_cutoff = validate_certificate(
        &public,
        &candidates,
        revocation_cutoff,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate at SHA-1 revocation cutoff");
    assert_eq!(
        at_cutoff.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
}

#[test]
fn revocation_hash_tolerance_does_not_extend_sha1_authentication() {
    let secret = generated_test_secret_with_kind(
        "Weak Authentication <weak-authentication@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let certification = self_certification(
        &secret,
        (SHA1_SECOND_PREIMAGE_REJECT_AT - 1) as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    );
    let revocation_cutoff = SHA1_SECOND_PREIMAGE_REJECT_AT
        .checked_add(LEGACY_REVOCATION_GRACE_SECONDS)
        .expect("SHA-1 revocation cutoff");

    assert!(authentication_signature_acceptable(
        &certification,
        SHA1_SECOND_PREIMAGE_REJECT_AT - 1,
    ));
    assert!(!authentication_signature_acceptable(
        &certification,
        SHA1_SECOND_PREIMAGE_REJECT_AT,
    ));
    assert!(!authentication_signature_acceptable(
        &certification,
        revocation_cutoff - 1,
    ));
}

#[test]
fn md5_is_unconditionally_rejected_at_historical_policy_times() {
    const HISTORICAL_TIME: u64 = 800_000_000;

    let mut secret = generated_test_secret_at_with_kind(
        "Historical MD5 <historical-md5@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
        HISTORICAL_TIME,
    );
    let user_id = secret.details.users[0].id.clone();
    let certification = user_id_self_certification(
        &secret,
        &user_id,
        (HISTORICAL_TIME + 1) as u32,
        HashAlgorithm::Md5,
        Vec::new(),
    );
    certification
        .verify_certification(secret.primary_key.public_key(), Tag::UserId, &user_id)
        .expect("MD5 certification remains mathematically valid");
    let revocation = key_revocation(&secret, (HISTORICAL_TIME + 1) as u32, HashAlgorithm::Md5);
    revocation
        .verify_key(secret.primary_key.public_key())
        .expect("MD5 revocation remains mathematically valid");
    secret.details.direct_signatures.clear();
    secret.details.users[0].signatures = vec![certification];
    secret.details.revocation_signatures = vec![revocation];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        HISTORICAL_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate historical MD5 certificate");
    let identity = policy.user_id(user_id.id()).expect("identity policy");

    assert!(!identity.authenticated());
    assert!(identity.verified_templates.is_empty());
    assert_eq!(
        policy.primary.revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(!data_signature_hash_acceptable(
        HashAlgorithm::Md5,
        HISTORICAL_TIME,
    ));
}

#[test]
fn data_signature_hash_cutoffs_are_exclusive_and_require_collision_resistance() {
    assert!(!data_signature_hash_acceptable(HashAlgorithm::Md5, 0));
    assert!(!data_signature_hash_acceptable(
        HashAlgorithm::Md5,
        u64::MAX,
    ));
    for (algorithm, cutoff) in [
        (HashAlgorithm::Sha1, SHA1_COLLISION_REJECT_AT),
        (HashAlgorithm::Ripemd160, RIPEMD160_COLLISION_REJECT_AT),
    ] {
        assert!(data_signature_hash_acceptable(algorithm, cutoff - 1));
        assert!(!data_signature_hash_acceptable(algorithm, cutoff));
    }

    assert!(data_signature_hash_acceptable(
        HashAlgorithm::Sha256,
        u64::MAX,
    ));
    assert!(!data_signature_hash_acceptable(HashAlgorithm::Other(99), 0,));
}

#[test]
fn non_revocable_binding_does_not_suppress_verified_subkey_revocation() {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Subkey Revocation <subkey-revocation@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: TEST_TIME,
            expiration_seconds: None,
        })
        .expect("generate certificate")
        .as_slice(),
    )
    .expect("decode generated certificate");
    let (mut secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated certificate");
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[1].key.public_key().clone();

    let mut binding_config = secret.secret_subkeys[1]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    binding_config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::Revocable(_)));
    binding_config
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::Revocable(false)).expect("revocable subpacket"));
    let binding = binding_config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign non-revocable binding");
    binding
        .verify_subkey_binding(primary.public_key(), &subkey)
        .expect("verify non-revocable binding");

    let mut revocation_config = SignatureConfig::v4(
        SignatureType::SubkeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    revocation_config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("revocation issuer fingerprint"),
    ];
    revocation_config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("revocation issuer key ID"),
    ];
    let revocation = revocation_config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign subkey revocation");
    revocation
        .verify_subkey_binding(primary.public_key(), &subkey)
        .expect("verify subkey revocation");
    secret.secret_subkeys[1].signatures = vec![binding, revocation];

    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let policy = validate_certificate(
        &public,
        &candidates,
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect revoked certificate");
    let subkey = &policy.subkeys[1];

    assert!(subkey.authenticated);
    assert!(subkey.revoked);
    assert!(!encryption_component_usable(subkey, TEST_TIME + 2));
}

#[test]
fn revoked_newer_user_id_certification_falls_back_to_older_non_revocable_binding() {
    let mut secret = generated_test_secret("Committed User ID <committed-user-id@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let non_revocable = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::critical(SubpacketData::Revocable(false)).expect("non-revocable subpacket"),
        ],
    );
    let newer = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 30) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.users[0].signatures = vec![non_revocable, newer, revocation];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate non-revocable User ID certification");

    // RFC 9580 §5.2.3.20 binds Revocable to the signature that carries it.
    // Resolve each binding independently: the revocation cancels the newer
    // revocable certification, then selection falls back to the surviving
    // non-revocable one.
    assert_eq!(
        policy.user_ids[0]
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
    assert!(!policy.user_ids[0].effective_certification_revocable);
    assert_eq!(
        policy.user_ids[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(policy.user_ids[0].effective_revocation_at, None);
    assert!(policy.user_ids[0].authenticated());
    assert_eq!(
        policy.verified_user_ids_for_test(),
        vec!["Committed User ID <committed-user-id@example.test>".to_owned()],
    );
    assert_eq!(
        policy.user_ids[0].newest_certification_time,
        Some(TEST_TIME + 20),
    );
    assert_eq!(policy.user_ids[0].verified_certifications.len(), 2);
    assert_eq!(policy.user_ids[0].verified_revocations.len(), 1);
}

#[test]
fn untargeted_revocation_removes_all_older_revocable_user_id_certifications() {
    let mut secret = generated_test_secret("All Revoked <all-revoked@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let older = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let newer = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 30) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.users[0].signatures = vec![older, newer, revocation];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate fully revoked User ID certifications");

    assert_eq!(policy.user_ids[0].effective_signature, None);
    assert_eq!(
        policy.user_ids[0].revocation_status,
        RevocationStatus::Revoked,
    );
    assert_eq!(
        policy.user_ids[0].effective_revocation_at,
        Some(TEST_TIME + 30),
    );
    assert!(!policy.user_ids[0].authenticated());
    assert_eq!(policy.user_ids[0].verified_certifications.len(), 2);
}

#[test]
fn targeted_revocation_of_canonical_equal_time_binding_selects_the_other_binding() {
    let mut base = generated_test_secret("Tied Survivor <tied-survivor@example.test>");
    let user_id = base.details.users[0].id.clone();
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    let first = user_id_self_certification(
        &base,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        vec![Subpacket::regular(SubpacketData::KeyFlags(signing_flags)).expect("key flags")],
    );
    let second = user_id_self_certification(
        &base,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        vec![Subpacket::regular(SubpacketData::KeyFlags(certifying_flags)).expect("key flags")],
    );
    let (revoked, survivor) = if cryptographic_signature_material_cmp(&first, &second).is_lt() {
        (&first, &second)
    } else {
        (&second, &first)
    };
    let revocation = identity_certification_revocation(
        &base,
        &base,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 20) as u32,
        vec![identity_signature_target(
            revoked,
            &base,
            Tag::UserId,
            &user_id,
            true,
        )],
        Vec::new(),
    );

    for certifications in [
        vec![first.clone(), second.clone(), revocation.clone()],
        vec![second.clone(), first.clone(), revocation.clone()],
    ] {
        base.details.users[0].signatures = certifications;
        let public = base.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate equal-time certification fallback");
        let effective = policy.user_ids[0]
            .effective_signature
            .expect("the non-targeted equal-time binding survives");

        assert_eq!(
            cryptographic_signature_material_cmp(effective, survivor),
            std::cmp::Ordering::Equal,
        );
        assert_eq!(
            policy.user_ids[0].revocation_status,
            RevocationStatus::NotRevoked,
        );
    }
}

#[test]
fn non_revocable_effective_user_id_certification_still_blocks_revocation() {
    let mut secret = generated_test_secret("Committed User ID <committed-user-id@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let older = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let non_revocable = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::critical(SubpacketData::Revocable(false)).expect("non-revocable subpacket"),
        ],
    );
    let revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 30) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.users[0].signatures = vec![older, non_revocable, revocation];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate non-revocable effective certification");

    assert!(!policy.user_ids[0].effective_certification_revocable);
    assert_eq!(
        policy.user_ids[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert_eq!(
        policy.verified_user_ids_for_test(),
        vec!["Committed User ID <committed-user-id@example.test>".to_owned()],
    );
}

#[test]
fn revoked_newer_user_attribute_certification_falls_back_to_older_non_revocable_binding() {
    let mut secret = generated_test_secret("Attribute Owner <attribute-owner@example.test>");
    let attribute =
        UserAttribute::new_image(Bytes::from_static(b"committed image")).expect("user attribute");
    let non_revocable = user_attribute_self_certification(
        &secret,
        &attribute,
        (TEST_TIME + 10) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::critical(SubpacketData::Revocable(false)).expect("non-revocable subpacket"),
        ],
    );
    let newer = user_attribute_self_certification(
        &secret,
        &attribute,
        (TEST_TIME + 20) as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    );
    let revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserAttribute,
        &attribute,
        (TEST_TIME + 30) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.direct_signatures.clear();
    secret.details.users.clear();
    secret.details.user_attributes = vec![SignedUserAttribute::new(
        attribute,
        vec![non_revocable, newer, revocation],
    )];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 40,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate non-revocable User Attribute certification");

    assert_eq!(
        policy.user_attributes[0]
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
    assert!(!policy.user_attributes[0].effective_certification_revocable);
    assert_eq!(
        policy.user_attributes[0].revocation_status,
        RevocationStatus::NotRevoked,
    );
    assert!(policy.primary.authenticated);
    assert_eq!(
        policy
            .primary
            .effective_signature
            .and_then(signature_creation_time),
        Some((TEST_TIME + 10) as u32),
    );
}

#[test]
fn newer_user_id_certification_supersedes_hard_reason_revocations() {
    for reason in [RevocationCode::NoReason, RevocationCode::KeyCompromised] {
        let mut secret = generated_test_secret("Restored User ID <restored-user-id@example.test>");
        let user_id = secret.details.users[0].id.clone();
        let revocation = identity_signature(
            &secret,
            SignatureType::CertRevocation,
            Tag::UserId,
            &user_id,
            (TEST_TIME + 10) as u32,
            Some(reason),
        );
        let certification = identity_signature(
            &secret,
            SignatureType::CertPositive,
            Tag::UserId,
            &user_id,
            (TEST_TIME + 20) as u32,
            None,
        );
        secret.details.users[0].signatures = vec![revocation, certification];

        let public = secret.to_public_key();
        let candidates = all_components(std::slice::from_ref(&public));
        let policy = validate_certificate(
            &public,
            &candidates,
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("inspect restored User ID");

        assert_eq!(
            policy.verified_user_ids_for_test(),
            vec!["Restored User ID <restored-user-id@example.test>".to_owned()],
        );
        assert_eq!(
            policy
                .primary
                .effective_signature
                .and_then(signature_creation_time),
            Some((TEST_TIME + 20) as u32),
        );
    }
}

#[test]
fn newer_user_attribute_certification_supersedes_hard_reason_revocations() {
    for reason in [RevocationCode::NoReason, RevocationCode::KeyCompromised] {
        let mut secret = generated_test_secret("Attribute Owner <attribute-owner@example.test>");
        let attribute = UserAttribute::new_image(Bytes::from_static(b"restored image"))
            .expect("user attribute");
        let revocation = identity_signature(
            &secret,
            SignatureType::CertRevocation,
            Tag::UserAttribute,
            &attribute,
            (TEST_TIME + 10) as u32,
            Some(reason),
        );
        let certification = identity_signature(
            &secret,
            SignatureType::CertPositive,
            Tag::UserAttribute,
            &attribute,
            (TEST_TIME + 20) as u32,
            None,
        );
        secret.details.direct_signatures.clear();
        secret.details.users.clear();
        secret.details.user_attributes = vec![SignedUserAttribute::new(
            attribute,
            vec![revocation, certification],
        )];

        let public = secret.to_public_key();
        let candidates = all_components(std::slice::from_ref(&public));
        let policy = validate_certificate(
            &public,
            &candidates,
            TEST_TIME + 30,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("inspect restored User Attribute");

        assert!(policy.primary.authenticated);
        assert_eq!(
            policy
                .primary
                .effective_signature
                .and_then(signature_creation_time),
            Some((TEST_TIME + 20) as u32),
        );
    }
}

#[test]
fn weak_hash_self_signatures_are_renewal_templates_and_never_authentication() {
    let now = reference_time(None);
    assert!(now >= SHA1_SECOND_PREIMAGE_REJECT_AT);
    let mut secret = generated_test_secret_with_kind(
        "Legacy Hash <legacy-hash@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );

    // A SHA-1 self-certification: verified, but past the hash cutoff.
    let user_id = secret.details.users[0].id.clone();
    secret.details.users[0].signatures = vec![user_id_self_certification(
        &secret,
        &user_id,
        TEST_TIME as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    )];
    // A future-dated SHA-1 certification must never be retained: renewal
    // adopting it would let an attacker park a self-signature in the
    // future and lock the identity out until that instant.
    let future_user_id = UserId::from_str(Default::default(), "Future <future@example.test>")
        .expect("create future User ID");
    let future_certification = user_id_self_certification(
        &secret,
        &future_user_id,
        (now + 3_600) as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    );
    secret.details.users.push(SignedUser::new(
        future_user_id.clone(),
        vec![future_certification],
    ));
    // Nor may a signature whose critical subpackets Keyguard cannot
    // interpret be copied into a freshly issued signature.
    let critical_user_id = UserId::from_str(Default::default(), "Critical <critical@example.test>")
        .expect("create critical User ID");
    let critical_certification = user_id_self_certification(
        &secret,
        &critical_user_id,
        TEST_TIME as u32,
        HashAlgorithm::Sha1,
        vec![
            Subpacket::critical(SubpacketData::Experimental(
                100,
                Bytes::from_static(b"unknown critical policy"),
            ))
            .expect("unknown critical subpacket"),
        ],
    );
    secret.details.users.push(SignedUser::new(
        critical_user_id.clone(),
        vec![critical_certification],
    ));

    secret.details.direct_signatures = vec![direct_self_signature_with_hash(
        &secret,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha1,
        Vec::new(),
    )];

    let subkey = secret.secret_subkeys[0].key.public_key().clone();
    let mut binding_config = secret.secret_subkeys[0]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    binding_config.hash_alg = HashAlgorithm::Sha1;
    let binding = binding_config
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("sign SHA-1 subkey binding");
    binding
        .verify_subkey_binding(secret.primary_key.public_key(), &subkey)
        .expect("SHA-1 binding remains mathematically valid");
    secret.secret_subkeys[0].signatures = vec![binding];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        now,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate legacy-hash certificate");

    // The active-policy tier rejects every legacy-hash signature here.
    assert!(policy.verified_user_ids_for_test().is_empty());
    assert!(!policy.primary.authenticated);
    assert!(policy.primary.verified_bindings.is_empty());
    assert!(!policy.subkeys[0].authenticated);
    assert!(policy.subkeys[0].verified_bindings.is_empty());
    let identity = policy.user_id(user_id.id()).expect("identity policy");
    assert!(!identity.authenticated());
    assert!(identity.verified_certifications.is_empty());
    assert_eq!(identity.newest_certification_time, None);

    // Tier two keeps exactly the signatures renewal may rebuild.
    assert_eq!(identity.verified_templates.len(), 1);
    assert!(std::ptr::eq(
        identity.verified_templates[0].template_signature(),
        &public.details.users[0].signatures[0],
    ));
    assert_eq!(policy.primary.verified_templates.len(), 1);
    assert_eq!(policy.subkeys[0].verified_templates.len(), 1);
    assert!(
        policy
            .user_id(future_user_id.id())
            .expect("future identity policy")
            .verified_templates
            .is_empty(),
        "a future-dated signature must not appear in any tier",
    );
    assert!(
        policy
            .user_id(critical_user_id.id())
            .expect("critical identity policy")
            .verified_templates
            .is_empty(),
        "unsupported critical subpackets cannot be copied into a renewal",
    );
}

#[test]
fn accepted_self_signatures_are_never_offered_as_templates() {
    let mut secret = generated_test_secret("Modern Hash <modern-hash@example.test>");
    let user_id = secret.details.users[0].id.clone();
    secret.details.users[0].signatures = vec![user_id_self_certification(
        &secret,
        &user_id,
        TEST_TIME as u32,
        HashAlgorithm::Sha256,
        Vec::new(),
    )];
    secret.details.direct_signatures =
        vec![direct_self_signature(&secret, TEST_TIME as u32, Vec::new())];

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 1,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate modern certificate");

    let identity = policy.user_id(user_id.id()).expect("identity policy");
    assert!(identity.authenticated());
    assert_eq!(identity.verified_certifications.len(), 1);
    assert_eq!(identity.newest_certification_time, Some(TEST_TIME));
    assert!(identity.verified_templates.is_empty());
    assert!(policy.primary.verified_templates.is_empty());
    assert!(
        policy
            .subkeys
            .iter()
            .all(|subkey| subkey.verified_templates.is_empty()),
    );
    assert_eq!(policy.primary.verified_bindings.len(), 1);
    assert!(
        policy
            .subkeys
            .iter()
            .all(|subkey| subkey.verified_bindings.len() == 1),
    );
}

#[test]
fn amalgamation_exposes_the_verified_revocations_it_acted_on() {
    let mut secret = generated_test_secret("Revoked Key <revoked-key@example.test>");
    let revocation = key_revocation(&secret, (TEST_TIME + 1) as u32, HashAlgorithm::Sha256);
    secret.details.revocation_signatures = vec![revocation];
    let user_id = secret.details.users[0].id.clone();
    let certification_revocation = identity_signature(
        &secret,
        SignatureType::CertRevocation,
        Tag::UserId,
        &user_id,
        (TEST_TIME + 1) as u32,
        Some(RevocationCode::CertUserIdInvalid),
    );
    secret.details.users[0]
        .signatures
        .push(certification_revocation);

    let public = secret.to_public_key();
    let policy = validate_certificate(
        &public,
        &all_components(std::slice::from_ref(&public)),
        TEST_TIME + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked certificate");

    assert!(policy.primary.revoked);
    let identity = policy.user_id(user_id.id()).expect("identity policy");
    assert_eq!(identity.revocation_status, RevocationStatus::Revoked);
    assert_eq!(identity.verified_revocations.len(), 1);
}

#[test]
fn tied_user_id_certifications_use_cryptographic_material_order() {
    let mut secret = generated_test_secret("Tied Identity <tied-identity@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    let first = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(signing_flags)).expect("key flags"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES128].into(),
            ))
            .expect("symmetric preferences"),
        ],
    );
    let second = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(certifying_flags)).expect("key flags"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("symmetric preferences"),
        ],
    );
    secret.details.direct_signatures.clear();
    let first_is_selected = cryptographic_signature_material_cmp(&first, &second).is_lt();
    let expected = if first_is_selected { &first } else { &second };
    for order in [
        vec![first.clone(), second.clone()],
        vec![second.clone(), first.clone()],
    ] {
        let mut secret = secret.clone();
        secret.details.users[0].signatures = order;
        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 2,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate tied certifications");

        let identity = policy.user_id(user_id.id()).expect("identity policy");
        assert!(!identity.policy_conflict);
        assert!(identity.authenticated());
        assert_eq!(
            identity
                .effective_signature
                .expect("effective certification"),
            expected,
        );
        assert_eq!(policy.verified_user_ids_for_test().len(), 1);
        assert!(!policy.primary.policy_conflict);
        assert!(policy.primary.authenticated);
        let flags = policy
            .primary
            .key_flags
            .as_ref()
            .expect("primary key flags");
        assert_eq!(flags.sign(), first_is_selected);
        assert_eq!(flags.certify(), !first_is_selected);
        assert_eq!(
            policy.primary.preferred_symmetric.as_deref(),
            Some(
                &[u8::from(if first_is_selected {
                    SymmetricKeyAlgorithm::AES128
                } else {
                    SymmetricKeyAlgorithm::AES256
                })][..]
            ),
        );
    }
}

#[test]
fn newer_user_id_certification_precedes_the_material_tie_break() {
    let mut secret = generated_test_secret("Newest Identity <newest-identity@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    let older = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(signing_flags)).expect("key flags"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES128].into(),
            ))
            .expect("symmetric preferences"),
        ],
    );
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    let newer = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 2) as u32,
        HashAlgorithm::Sha256,
        vec![
            Subpacket::regular(SubpacketData::KeyFlags(certifying_flags)).expect("key flags"),
            Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
                vec![SymmetricKeyAlgorithm::AES256].into(),
            ))
            .expect("symmetric preferences"),
        ],
    );
    secret.details.direct_signatures.clear();

    for order in [
        vec![older.clone(), newer.clone()],
        vec![newer.clone(), older],
    ] {
        let mut secret = secret.clone();
        secret.details.users[0].signatures = order;
        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 3,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate differently dated certifications");

        let identity = policy.user_id(user_id.id()).expect("identity policy");
        assert_eq!(identity.effective_signature, Some(&newer));
        let flags = policy
            .primary
            .key_flags
            .as_ref()
            .expect("primary key flags");
        assert!(flags.certify());
        assert!(!flags.sign());
        assert_eq!(
            policy.primary.preferred_symmetric.as_deref(),
            Some(&[u8::from(SymmetricKeyAlgorithm::AES256)][..]),
        );
    }
}

#[test]
fn tied_equivalent_user_id_certifications_still_authenticate_the_identity() {
    let mut secret = generated_test_secret("Tied Identity <tied-identity@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    let first = user_id_self_certification(
        &secret,
        &user_id,
        (TEST_TIME + 1) as u32,
        HashAlgorithm::Sha256,
        vec![Subpacket::regular(SubpacketData::KeyFlags(signing_flags)).expect("key flags")],
    );
    let mut second_config = first.config().cloned().expect("v4 certification");
    second_config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("issuer key ID"),
    );
    let second = Signature::from_config(
        second_config,
        first.signed_hash_value().expect("signed hash prefix"),
        first.signature().cloned().expect("signature bytes"),
    )
    .expect("rebuild certification with an unhashed issuer");
    second
        .verify_certification(secret.primary_key.public_key(), Tag::UserId, &user_id)
        .expect("unhashed issuer does not change certification validity");
    secret.details.direct_signatures.clear();

    for order in [
        vec![first.clone(), second.clone()],
        vec![second.clone(), first.clone()],
    ] {
        let mut secret = secret.clone();
        secret.details.users[0].signatures = order;
        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 2,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate equivalent tied certifications");

        let identity = policy.user_id(user_id.id()).expect("identity policy");
        assert!(!identity.policy_conflict);
        assert!(identity.authenticated());
        assert_eq!(
            policy.verified_user_ids_for_test(),
            vec!["Tied Identity <tied-identity@example.test>".to_owned()],
        );
        assert!(policy.primary.authenticated);
    }
}

#[test]
fn tied_subkey_bindings_use_cryptographic_material_order() {
    let secret = generated_test_secret("Tied Subkey <tied-subkey@example.test>");
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[1].key.public_key().clone();
    let base = secret.secret_subkeys[1]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("subkey binding config");
    let binding = |flags: KeyFlags| {
        let mut config = base.clone();
        config.hashed_subpackets.retain(|subpacket| {
            !matches!(
                subpacket.data,
                SubpacketData::SignatureCreationTime(_) | SubpacketData::KeyFlags(_)
            )
        });
        config.hashed_subpackets.extend([
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                (TEST_TIME + 1) as u32,
            )))
            .expect("creation time"),
            Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags"),
        ]);
        config
            .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
            .expect("sign tied binding")
    };
    let mut communications = KeyFlags::default();
    communications.set_encrypt_comms(true);
    let mut storage = KeyFlags::default();
    storage.set_encrypt_storage(true);
    let first = binding(communications);
    let second = binding(storage);
    let first_is_selected = cryptographic_signature_material_cmp(&first, &second).is_lt();
    let expected = if first_is_selected { &first } else { &second };
    for order in [
        vec![first.clone(), second.clone()],
        vec![second.clone(), first.clone()],
    ] {
        let mut secret = secret.clone();
        secret.secret_subkeys[1].signatures = order;
        let public = secret.to_public_key();
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            TEST_TIME + 2,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate tied subkey bindings");

        let component = &policy.subkeys[1];
        assert!(!component.policy_conflict);
        assert!(component.authenticated);
        assert_eq!(
            component
                .effective_signature
                .expect("effective subkey binding"),
            expected,
        );
        let flags = component.key_flags.as_ref().expect("subkey key flags");
        assert_eq!(flags.encrypt_comms(), first_is_selected);
        assert_eq!(flags.encrypt_storage(), !first_is_selected);
        assert!(encryption_component_usable(component, TEST_TIME + 2));
    }
}

#[test]
fn designated_revoker_declaration_requires_the_exact_declared_key() {
    let target = generated_test_secret("Revoker Target <revoker-target@example.test>");
    let revoker = generated_test_secret("Revoker <revoker@example.test>");
    let declaration = designated_revoker_declaration(&target, &revoker, Vec::new());
    let declarations = designated_revokers(
        std::iter::once(&declaration),
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("extract designated revokers");

    assert_eq!(declarations.len(), 1);
    assert_eq!(declarations[0].key_class, 0x80);
    let revoker_public = revoker.to_public_key();
    let target_public = target.to_public_key();
    assert!(declarations[0].matches_component(&PublicComponent::Primary(
        revoker_public.primary_key.clone()
    )));
    assert!(
        !declarations[0]
            .matches_component(&PublicComponent::Primary(target_public.primary_key.clone()))
    );
    assert!(!declarations[0].matches_component(&PublicComponent::Subkey(
        revoker_public.public_subkeys[0].key.clone()
    )),);
}
