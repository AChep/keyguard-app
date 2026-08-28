use std::sync::{Mutex, MutexGuard};

use pgp::{
    composed::{
        ArmorOptions, DsaKeySize, KeyType, SecretKeyParamsBuilder, SignedSecretKey, SubpacketConfig,
    },
    crypto::{ecc_curve::ECCCurve, hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    packet::{
        KeyFlags, PacketHeader, RevocationCode, SignatureConfig, SignatureType, SignatureVersion,
        Subpacket, SubpacketData,
    },
    types::{Password, RsaPublicParams, SignatureBytes, Timestamp},
};
use rand::{SeedableRng, rngs::StdRng};

use super::*;
use crate::openpgp::adapter::wire::{
    Message as _, OpenPgpClearVerifyResult, OpenPgpClearVerifyStreamOpenRequest,
    OpenPgpComponentPolicyV2, OpenPgpDetachedVerifyStreamOpenRequest, OpenPgpKeyComponentRole,
    OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial, OpenPgpMetadataResolveRequest,
    OpenPgpMetadataResolveResult, OpenPgpPublicKeyInfo, OpenPgpPublicKeyParseErrorReason,
    OpenPgpPublicKeyParseRequest, OpenPgpPublicKeyParseResult, OpenPgpRenewalAuthorization,
    OpenPgpRevocationStatus, OpenPgpVerification, OpenPgpVerificationStatus,
    OpenPgpVerificationWarning, OpenPgpVerifyKind, OpenPgpVerifyRequest,
    open_pgp_public_key_parse_result,
};
use crate::openpgp::certificate::filtered_tsk_fixture;
use crate::openpgp::crypto::verification::{
    MAX_SIGNATURE_ISSUER_HINTS, cryptographic_signature_material_cmp,
    signature_verification_compatible,
};
use crate::openpgp::key::generate_rsa_certificate_for_test;
use crate::openpgp::packet::USER_ID_TAG;
const PUBLIC_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc");
const DETACHED_BODY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/detached-body.txt");
const DETACHED_SIGNATURE: &[u8] =
    include_bytes!("../../../../tests/fixtures/openpgp/detached-signature.asc");
const CLEAR_SIGNED: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/clear-signed.asc");
const DESIGNATED_REVOKED_PUBLIC_KEY: &[u8] =
    include_bytes!("../../../../tests/fixtures/openpgp/designated-revoked-public.asc");
const DESIGNATED_REVOKER_PUBLIC_KEY: &[u8] =
    include_bytes!("../../../../tests/fixtures/openpgp/designated-revoker-public.asc");
// The fixed GnuPG detached and cleartext signatures were created at
// 1_784_073_600. Keep the shared verification view just after that instant so
// these interoperability fixtures are historical, not accidentally future-
// dated under the production creation-time check.
const REFERENCE_TIME: u64 = 1_784_073_601;
const RENEWAL_TEST_CREATION_TIME: u64 = 1_700_000_000;
const RENEWAL_TEST_SIGNATURE_TIME: u32 = 1_700_000_010;

const RENEWAL_TEST_REFERENCE_TIME: u64 = 1_700_000_120;
const PRIMARY_FINGERPRINT: &str = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7";
const PRIMARY_KEYGRIP: &str = "894264A490F8D55E3E28378A7E44373782806220";
const SUBKEY_FINGERPRINT: &str = "93ABCF804D85EE79D6E1DB0E77648D3E5D4E7699";
const SUBKEY_KEYGRIP: &str = "85C1DE785BEE9244BAFBA73A09E6085BA7A35C8E";
const USER_ID: &str = "Keyguard Test CV25519 <cv25519@test.invalid>";

static VERIFIER_WORKER_TEST_LOCK: Mutex<()> = Mutex::new(());

fn parse_public_key_request(
    request: OpenPgpPublicKeyParseRequest,
) -> Result<Vec<u8>, OpenPgpReadError> {
    crate::openpgp::adapter::parse_public_key(request).map_err(read_adapter_error)
}

fn verify_request(request: OpenPgpVerifyRequest) -> Result<Vec<u8>, OpenPgpReadError> {
    crate::openpgp::adapter::verify(request).map_err(read_adapter_error)
}

fn resolve_metadata(request: OpenPgpMetadataResolveRequest) -> Result<Vec<u8>, OpenPgpReadError> {
    crate::openpgp::adapter::resolve_metadata(request).map_err(read_adapter_error)
}

fn generate_key_request(
    request: OpenPgpKeyGenerateRequest,
) -> Result<Vec<u8>, crate::primitives::PrimitiveError> {
    crate::openpgp::adapter::generate_key(request)
}

fn read_adapter_error(error: crate::primitives::PrimitiveError) -> OpenPgpReadError {
    match error {
        crate::primitives::PrimitiveError::InvalidArgument => OpenPgpReadError::InvalidArgument,
        crate::primitives::PrimitiveError::ResourceLimit => OpenPgpReadError::ResourceLimit,
        _ => OpenPgpReadError::Internal,
    }
}

fn wire_verification_from_domain(result: Verification) -> OpenPgpVerification {
    OpenPgpVerification {
        status: match result.status {
            VerificationStatus::Valid => OpenPgpVerificationStatus::Valid,
            VerificationStatus::Invalid => OpenPgpVerificationStatus::Invalid,
            VerificationStatus::MissingPublicKey => OpenPgpVerificationStatus::MissingPublicKey,
        } as i32,
        key_id: result.key_id,
        fingerprint: result.fingerprint,
        user_ids: result.user_ids,
        created_at_epoch_seconds: result.created_at_epoch_seconds,
        warnings: result
            .warnings
            .into_iter()
            .map(|warning| match warning {
                VerificationWarning::KeyRevoked => OpenPgpVerificationWarning::KeyRevoked,
                VerificationWarning::KeyExpired => OpenPgpVerificationWarning::KeyExpired,
                VerificationWarning::SignatureExpired => {
                    OpenPgpVerificationWarning::SignatureExpired
                }
                VerificationWarning::PolicyConflict => OpenPgpVerificationWarning::PolicyConflict,
                VerificationWarning::WeakDigest => OpenPgpVerificationWarning::WeakDigest,
            } as i32)
            .collect(),
        primary_fingerprint: result.primary_fingerprint,
        primary_user_id: result.primary_user_id,
        signatures: result
            .signatures
            .into_iter()
            .map(wire_verification_from_domain)
            .collect(),
    }
}

fn wire_clear_verification_from_domain(
    result: ClearVerificationResult,
) -> OpenPgpClearVerifyResult {
    OpenPgpClearVerifyResult {
        verification: Some(wire_verification_from_domain(result.verification)),
        body_valid_utf8: result.body_valid_utf8,
    }
}

fn replace_armor_checksum(input: &[u8], replacement: Option<&[u8]>) -> Vec<u8> {
    let start = find_subslice(input, b"\n=").expect("fixture armor checksum line") + 1;
    let end = start
        + input[start..]
            .iter()
            .position(|byte| *byte == b'\n')
            .expect("fixture checksum line ending");
    let mut output = Vec::with_capacity(input.len() + replacement.map_or(0, <[u8]>::len));
    output.extend_from_slice(&input[..start]);
    if let Some(replacement) = replacement {
        output.extend_from_slice(replacement);
        output.extend_from_slice(&input[end..]);
    } else {
        output.extend_from_slice(&input[end + 1..]);
    }
    output
}

fn crc24_armor_cases(input: &[u8]) -> [(&'static str, Vec<u8>); 4] {
    [
        ("valid", input.to_vec()),
        ("wrong", replace_armor_checksum(input, Some(b"=AAAA"))),
        ("malformed", replace_armor_checksum(input, Some(b"=A"))),
        ("missing", replace_armor_checksum(input, None)),
    ]
}

fn damaged_revoked_certificate_keyring(separator: &[u8]) -> Vec<u8> {
    let damaged = RawPacketStream::parse(DESIGNATED_REVOKED_PUBLIC_KEY, MAX_PACKETS_PER_REQUEST)
        .expect("parse revoked certificate fixture");
    let revocation_index = damaged
        .packets()
        .iter()
        .position(|packet| {
            packet.tag() == SIGNATURE_TAG
                && damaged
                    .body(packet)
                    .get(1)
                    .copied()
                    .is_some_and(|signature_type| matches!(signature_type, 0x20 | 0x28 | 0x30))
        })
        .expect("fixture contains a key revocation");
    let mut keyring = Vec::new();
    for (index, packet) in damaged.packets().iter().enumerate() {
        if index != revocation_index {
            keyring.extend_from_slice(damaged.raw(packet));
        }
    }
    keyring.extend_from_slice(separator);
    keyring.extend_from_slice(damaged.raw(&damaged.packets()[revocation_index]));
    keyring.extend_from_slice(
        &decode_openpgp_packets(PUBLIC_KEY).expect("dearmor independent certificate"),
    );
    keyring
}

fn identity_after_subkey_certificate() -> Vec<u8> {
    let stream = RawPacketStream::parse(PUBLIC_KEY, MAX_PACKETS_PER_REQUEST)
        .expect("parse fixture packet stream");
    assert_eq!(
        stream
            .packets()
            .iter()
            .map(|packet| packet.tag())
            .collect::<Vec<_>>(),
        vec![
            PUBLIC_KEY_TAG,
            USER_ID_TAG,
            SIGNATURE_TAG,
            PUBLIC_SUBKEY_TAG,
            SIGNATURE_TAG,
        ],
    );
    let mut malformed = Vec::new();
    for index in [0, 3, 4, 1, 2] {
        malformed.extend_from_slice(stream.raw(&stream.packets()[index]));
    }
    malformed
}

#[test]
fn certificate_validation_cache_is_scoped_to_both_validation_times() {
    let mut cache = CertificateValidationCache::new();
    let mut attempts = 0;

    for validation_times in [(10, 30), (10, 30), (20, 30), (20, 40)] {
        let result = cache
            .entry(validation_times)
            .or_default()
            .get_or_validate(|| {
                attempts += 1;
                Err(OpenPgpPolicyError::ResourceLimit)
            });
        assert!(matches!(result, Ok(None)));
    }

    assert_eq!(attempts, 3);
    assert_eq!(cache.len(), 3);
}

fn verifier_worker_test_guard() -> MutexGuard<'static, ()> {
    VERIFIER_WORKER_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[test]
fn fingerprint_key_id_uses_version_specific_64_bits() {
    let v4_bytes = std::array::from_fn(|index| index as u8);
    let v6_bytes = std::array::from_fn(|index| (index as u8).wrapping_add(0x40));

    assert_eq!(
        fingerprint_key_id(&Fingerprint::V4(v4_bytes)),
        Some(&v4_bytes[12..]),
    );
    assert_eq!(
        fingerprint_key_id(&Fingerprint::V6(v6_bytes)),
        Some(&v6_bytes[..8]),
    );
}

#[test]
fn fingerprint_key_id_rejects_unsupported_or_unknown_fingerprints() {
    assert_eq!(fingerprint_key_id(&Fingerprint::V5([0x55; 32])), None);
    for bytes in [
        vec![0x44; 19],
        vec![0x44; 20],
        vec![0x66; 31],
        vec![0x66; 32],
        vec![0x66; 33],
    ] {
        assert_eq!(
            fingerprint_key_id(&Fingerprint::Unknown(bytes.into_boxed_slice())),
            None,
        );
    }
}

#[test]
fn signature_key_id_derives_v6_hashed_and_unhashed_fingerprints_consistently() {
    let fingerprint_bytes = std::array::from_fn(|index| (index as u8).wrapping_add(0x80));
    let expected = hex_upper(&fingerprint_bytes[..8]);

    for hashed in [true, false] {
        let mut config = SignatureConfig::v6(
            StdRng::seed_from_u64(0x5636_4b45_5949_4400 + u64::from(hashed)),
            SignatureType::Binary,
            PublicKeyAlgorithm::Ed25519,
            HashAlgorithm::Sha256,
        )
        .expect("v6 signature config");
        let issuer = Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
            fingerprint_bytes,
        )))
        .expect("v6 issuer fingerprint");
        if hashed {
            config.hashed_subpackets = vec![issuer];
        } else {
            config.unhashed_subpackets = vec![issuer];
        }
        let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic signature");

        assert_eq!(signature_key_id(&signature), expected);
    }
}

#[test]
fn signature_key_id_preserves_explicit_issuer_key_ids() {
    let key_id_bytes = [0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0xfe];

    for hashed in [true, false] {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            PublicKeyAlgorithm::Ed25519,
            HashAlgorithm::Sha256,
        );
        let issuer = Subpacket::regular(SubpacketData::IssuerKeyId(key_id_bytes.into()))
            .expect("issuer key ID");
        if hashed {
            config.hashed_subpackets = vec![issuer];
        } else {
            config.unhashed_subpackets = vec![issuer];
        }
        let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
            .expect("synthetic signature");

        assert_eq!(signature_key_id(&signature), hex_upper(&key_id_bytes));
    }
}

#[test]
fn over_limit_nonmatching_issuer_hints_skip_certificate_policy() {
    let certificate = parse_public_certificates_fresh(PUBLIC_KEY)
        .expect("fixed certificate must parse")
        .remove(0);
    let wrong_key_id = [0xa5; 8].into();
    assert_ne!(wrong_key_id, certificate.primary_key.legacy_key_id());
    assert!(
        certificate
            .public_subkeys
            .iter()
            .all(|subkey| subkey.key.legacy_key_id() != wrong_key_id)
    );

    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        PublicKeyAlgorithm::Ed25519,
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = (0..MAX_SIGNATURE_ISSUER_HINTS * 16)
        .map(|_| {
            Subpacket::regular(SubpacketData::IssuerKeyId(wrong_key_id))
                .expect("nonmatching issuer key ID")
        })
        .collect();
    let signature = Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic over-limit signature");
    let certificates = vec![certificate];
    let candidates = all_components(&certificates);
    let mut validated = std::iter::repeat_with(CertificateValidationCache::new)
        .take(certificates.len())
        .collect::<Vec<_>>();

    let result = resolve_signer(
        &signature,
        &certificates,
        &candidates,
        &mut validated,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("reject over-limit issuer metadata");

    assert!(matches!(
        result,
        SignerResolution::Rejected { fingerprint: None }
    ));
    assert!(validated.iter().all(CertificateValidationCache::is_empty));
}

#[test]
fn v6_detached_verification_uses_fingerprint_and_rejects_key_id_in_both_areas() {
    let reference_time = reference_time(None);
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(
            u32::try_from(reference_time.saturating_sub(60)).expect("test timestamp"),
        ))
        .primary_user_id("V6 Signer <v6@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 signer")
        .generate(StdRng::seed_from_u64(0x5636_4b45_5949_4421))
        .expect("generate v6 signer");
    let fingerprint = secret.primary_key.fingerprint();
    let Fingerprint::V6(fingerprint_bytes) = &fingerprint else {
        panic!("generated v6 key must have a v6 fingerprint");
    };
    let expected_key_id = hex_upper(&fingerprint_bytes[..8]);
    assert_ne!(
        expected_key_id,
        hex_upper(&fingerprint_bytes[fingerprint_bytes.len() - 8..]),
        "test fingerprint must distinguish its high and low 64 bits",
    );
    let expected_fingerprint = format!("{fingerprint:X}");

    let mut config = SignatureConfig::v6(
        StdRng::seed_from_u64(0x5636_5349_474e_2121),
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    )
    .expect("create v6 signature config");
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            u32::try_from(reference_time).expect("test timestamp"),
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(fingerprint.clone()))
            .expect("issuer fingerprint"),
    ];
    let signature = config
        .sign(
            &secret.primary_key,
            &Password::empty(),
            Cursor::new(DETACHED_BODY),
        )
        .expect("sign v6 detached data");
    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signature(signature),
        public_keys: vec![serialized_public_certificate(&secret.to_public_key())],
        reference_time_epoch_seconds: Some(reference_time.saturating_add(1)),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(result.key_id, expected_key_id);
    assert_eq!(
        result.fingerprint.as_deref(),
        Some(expected_fingerprint.as_str())
    );
    assert_eq!(
        result.primary_fingerprint.as_deref(),
        Some(expected_fingerprint.as_str()),
    );

    for (hashed, seed, expected) in [
        (
            true,
            0x5636_4b45_5949_4422,
            OpenPgpVerificationStatus::Invalid,
        ),
        (
            false,
            0x5636_4b45_5949_4423,
            OpenPgpVerificationStatus::Invalid,
        ),
    ] {
        let mut key_id_config = SignatureConfig::v6(
            StdRng::seed_from_u64(seed),
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        )
        .expect("create v6 key-ID signature config");
        key_id_config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                u32::try_from(reference_time).expect("test timestamp"),
            )))
            .expect("signature creation time"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(fingerprint.clone()))
                .expect("issuer fingerprint"),
        ];
        let issuer_key_id = Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("forbidden v6 issuer key ID");
        if hashed {
            key_id_config.hashed_subpackets.push(issuer_key_id);
        } else {
            key_id_config.unhashed_subpackets = vec![issuer_key_id];
        }
        let key_id_signature = key_id_config
            .sign(
                &secret.primary_key,
                &Password::empty(),
                Cursor::new(DETACHED_BODY),
            )
            .expect("sign v6 detached data with key ID");
        key_id_signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("v6 key-ID fixture remains mathematically valid");
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(key_id_signature),
            public_keys: vec![serialized_public_certificate(&secret.to_public_key())],
            reference_time_epoch_seconds: Some(reference_time.saturating_add(1)),
        });

        assert_eq!(result.status, expected as i32, "hashed: {hashed}",);
        assert_eq!(result.fingerprint.as_deref(), None, "hashed: {hashed}",);
        assert_eq!(
            result.primary_fingerprint.as_deref(),
            None,
            "hashed: {hashed}",
        );
    }
}

#[test]
fn v6_document_signature_requires_hashed_creation_time() {
    let reference_time = reference_time(None);
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(
            u32::try_from(reference_time.saturating_sub(60)).expect("test timestamp"),
        ))
        .primary_user_id("V6 Creation Time <v6-time@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 signer")
        .generate(StdRng::seed_from_u64(0x5636_5449_4d45_2121))
        .expect("generate v6 signer");
    let public_key = serialized_public_certificate(&secret.to_public_key());
    let creation_time = secret
        .details
        .direct_signatures
        .iter()
        .chain(
            secret
                .details
                .users
                .iter()
                .flat_map(|user| user.signatures.iter()),
        )
        .filter_map(signature_creation_time)
        .max()
        .expect("generated certificate carries a self-signature")
        .checked_add(1)
        .expect("test timestamp");
    let verification_time = u64::from(creation_time) + 1;

    for (index, (placement, expected)) in [
        (
            CreationTimePlacement::Hashed,
            OpenPgpVerificationStatus::Valid,
        ),
        (
            CreationTimePlacement::Unhashed,
            OpenPgpVerificationStatus::Invalid,
        ),
        (
            CreationTimePlacement::Missing,
            OpenPgpVerificationStatus::Invalid,
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let mut config = SignatureConfig::v6(
            StdRng::seed_from_u64(0x5636_5449_4d45_5300 + index as u64),
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        )
        .expect("create v6 signature config");
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                secret.primary_key.fingerprint(),
            ))
            .expect("issuer fingerprint"),
        );
        place_creation_time(&mut config, placement, creation_time);
        let signature = sign_detached_with_config(config, &secret.primary_key);
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("v6 fixture remains mathematically valid");

        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature),
            public_keys: vec![public_key.clone()],
            reference_time_epoch_seconds: Some(verification_time),
        });
        assert_eq!(
            result.status, expected as i32,
            "creation-time placement: {placement:?}",
        );
    }
}

#[test]
fn primary_user_id_selection_prefers_newer_certification_time() {
    let identities = [
        ("newer", b"a".as_slice(), false, Some(20)),
        ("older but greater", b"zz".as_slice(), false, Some(10)),
    ];

    assert_eq!(
        select_primary_user_id(identities.into_iter()).as_deref(),
        Some("newer"),
    );
    assert_eq!(
        select_primary_user_id(identities.into_iter().rev()).as_deref(),
        Some("newer"),
    );
}

#[test]
fn primary_user_id_selection_prefers_explicit_primary_marker() {
    let identities = [
        ("new fallback", b"zz".as_slice(), false, Some(30)),
        ("old primary", b"a".as_slice(), true, Some(10)),
    ];

    assert_eq!(
        select_primary_user_id(identities.into_iter()).as_deref(),
        Some("old primary"),
    );
    assert_eq!(
        select_primary_user_id(identities.into_iter().rev()).as_deref(),
        Some("old primary"),
    );
}

#[test]
fn primary_user_id_selection_tie_breaks_lexicographically() {
    let identities = [
        ("z", b"z".as_slice(), false, Some(20)),
        ("aa", b"aa".as_slice(), false, Some(20)),
    ];

    assert_eq!(
        select_primary_user_id(identities.into_iter()).as_deref(),
        Some("aa"),
    );
    assert_eq!(
        select_primary_user_id(identities.into_iter().rev()).as_deref(),
        Some("aa"),
    );
}

#[test]
fn primary_user_id_selection_prefers_lexicographically_smaller_equal_length_body() {
    let identities = [
        ("lower", b"az".as_slice(), true, Some(20)),
        ("greater", b"za".as_slice(), true, Some(20)),
    ];

    assert_eq!(
        select_primary_user_id(identities.into_iter()).as_deref(),
        Some("lower"),
    );
    assert_eq!(
        select_primary_user_id(identities.into_iter().rev()).as_deref(),
        Some("lower"),
    );
}

#[test]
fn primary_user_id_selection_returns_none_when_empty() {
    assert_eq!(select_primary_user_id(std::iter::empty()), None);
}

fn direct_policy_signature(
    secret: &SignedSecretKey,
    flags: KeyFlags,
    include_unhashed_issuer: bool,
) -> Signature {
    direct_policy_signature_at(
        secret,
        flags,
        include_unhashed_issuer,
        (REFERENCE_TIME - 1) as u32,
    )
}

fn direct_policy_signature_at(
    secret: &SignedSecretKey,
    flags: KeyFlags,
    include_unhashed_issuer: bool,
    created_at: u32,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            created_at,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags"),
    ];
    if include_unhashed_issuer {
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(
                secret.primary_key.legacy_key_id(),
            ))
            .expect("issuer key ID"),
        ];
    }
    config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign direct policy")
}

fn identity_policy_signature(secret: &SignedSecretKey, flags: KeyFlags) -> Signature {
    identity_policy_signature_at(secret, flags, (REFERENCE_TIME - 1) as u32)
}

fn identity_policy_signature_at(
    secret: &SignedSecretKey,
    flags: KeyFlags,
    created_at: u32,
) -> Signature {
    identity_policy_signature_at_with_hash(secret, flags, created_at, HashAlgorithm::Sha256)
}

fn identity_policy_signature_at_with_hash(
    secret: &SignedSecretKey,
    flags: KeyFlags,
    created_at: u32,
    hash_algorithm: HashAlgorithm,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            created_at,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint"),
        Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags"),
        Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID"),
    ];
    let user_id = &secret.details.users[0].id;
    config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            user_id,
        )
        .expect("sign identity policy")
}

#[test]
fn equal_time_policy_selection_uses_cryptographic_material_order() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let mut signing = KeyFlags::default();
    signing.set_sign(true);
    let mut encryption = KeyFlags::default();
    encryption.set_encrypt_comms(true);
    let signing = direct_policy_signature(&secret, signing, false);
    let encryption = direct_policy_signature(&secret, encryption, false);

    for context in [
        PolicyContext::Direct,
        PolicyContext::Identity,
        PolicyContext::Subkey,
    ] {
        let PolicySelection::Selected {
            signature: forward, ..
        } = select_newest_policy_signature([&signing, &encryption].into_iter(), context, |_| {
            Ok(false)
        })
        .expect("select tied policy")
        else {
            panic!("tied policy must select a representative");
        };
        let PolicySelection::Selected {
            signature: reverse, ..
        } = select_newest_policy_signature([&encryption, &signing].into_iter(), context, |_| {
            Ok(false)
        })
        .expect("select reversed tied policy")
        else {
            panic!("reversed tied policy must select a representative");
        };
        let expected = if cryptographic_signature_material_cmp(&signing, &encryption).is_lt() {
            &signing
        } else {
            &encryption
        };
        assert!(std::ptr::eq(forward, expected));
        assert!(std::ptr::eq(reverse, expected));
    }

    let equivalent_without_issuer = direct_policy_signature(
        &secret,
        authenticated_key_flags(&signing).expect("signing flags"),
        false,
    );
    let equivalent_with_issuer = direct_policy_signature(
        &secret,
        authenticated_key_flags(&signing).expect("signing flags"),
        true,
    );
    for context in [
        PolicyContext::Direct,
        PolicyContext::Identity,
        PolicyContext::Subkey,
    ] {
        let PolicySelection::Selected {
            projection: forward,
            ..
        } = select_newest_policy_signature(
            [&equivalent_without_issuer, &equivalent_with_issuer].into_iter(),
            context,
            |_| Ok(false),
        )
        .expect("select equivalent policy")
        else {
            panic!("equivalent policy must select a representative");
        };
        let PolicySelection::Selected {
            projection: reverse,
            ..
        } = select_newest_policy_signature(
            [&equivalent_with_issuer, &equivalent_without_issuer].into_iter(),
            context,
            |_| Ok(false),
        )
        .expect("select reversed equivalent policy")
        else {
            panic!("reversed equivalent policy must select a representative");
        };
        assert_eq!(forward, reverse);
    }
}

#[test]
fn tied_direct_policy_is_canonical_and_independent_of_packet_order() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let mut signing = KeyFlags::default();
    signing.set_sign(true);
    // Both tied signatures keep the signing bit: this test is about the
    // tie-break, not about key usage, which
    // `data_signature_from_a_non_signing_component_is_not_valid` covers.
    let mut encryption = KeyFlags::default();
    encryption.set_sign(true);
    encryption.set_encrypt_comms(true);
    let signing = direct_policy_signature(&secret, signing, false);
    let encryption = direct_policy_signature(&secret, encryption, false);

    let mut certificates = Vec::new();
    let mut selected_flags = Vec::new();
    for order in [[signing.clone(), encryption.clone()], [encryption, signing]] {
        let mut public = secret.to_public_key();
        public.details.direct_signatures.extend(order);
        let candidates = all_components(std::slice::from_ref(&public));
        let policy = validate_certificate(
            &public,
            &candidates,
            REFERENCE_TIME,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("inspect certificate with tied direct signatures");
        assert!(policy.primary.authenticated);
        assert!(!policy.primary.policy_conflict);
        assert!(policy.primary.effective_signature.is_some());
        assert!(
            policy
                .primary
                .key_flags
                .as_ref()
                .is_some_and(KeyFlags::sign)
        );
        selected_flags.push(policy.primary.key_flags.clone());
        drop(policy);
        certificates.push(public);
    }
    assert_eq!(selected_flags[0], selected_flags[1]);

    let result = verification(detached_request(
        DETACHED_BODY.to_vec(),
        vec![serialized_public_certificate(&certificates[0])],
    ));
    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert!(
        !result
            .warnings
            .contains(&(OpenPgpVerificationWarning::PolicyConflict as i32)),
    );
}

fn detached_stream_request() -> DetachedVerifyInput {
    DetachedVerifyInput {
        signature: DETACHED_SIGNATURE.to_vec(),
        public_keys: vec![PUBLIC_KEY.to_vec()],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    }
}

fn parse_public_certificates_fresh(data: &[u8]) -> Result<Vec<SignedPublicKey>, ParseFailure> {
    parse_public_certificates(data, &mut OpenPgpReadBudget::default())
}

fn parse_detached_signatures_fresh(data: &[u8]) -> Result<Vec<Signature>, OpenPgpReadError> {
    parse_detached_signatures(data, &mut OpenPgpReadBudget::default())
}

fn clear_verify_session_fresh(
    data: &[u8],
) -> Result<(Vec<u8>, OpenPgpClearVerifyResult), OpenPgpReadError> {
    let mut budget = OpenPgpReadBudget::default();
    let certificates = parse_public_key_documents(&[PUBLIC_KEY.to_vec()], &mut budget)
        .expect("fixture public key must parse");
    let mut session = ClearVerificationSession::with_certificates(
        certificates,
        budget,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
    );
    let body = session.update(data)?;
    let result = wire_clear_verification_from_domain(session.finish()?);
    Ok((body, result))
}

fn rsa_public_parameter_bytes(exponent_bytes: usize) -> Vec<u8> {
    let mut encoded = Vec::new();
    let mut modulus = vec![0u8; usize::try_from(MAX_RSA_MODULUS_BITS).unwrap().div_ceil(8)];
    modulus[0] = 0x80;
    *modulus.last_mut().expect("non-empty modulus") = 1;
    encoded.extend_from_slice(
        &u16::try_from(MAX_RSA_MODULUS_BITS)
            .expect("RSA limit fits MPI")
            .to_be_bytes(),
    );
    encoded.extend_from_slice(&modulus);

    let mut exponent = vec![0u8; exponent_bytes];
    if exponent_bytes == 3 {
        exponent.copy_from_slice(&[1, 0, 1]);
    } else {
        exponent[0] = if exponent_bytes == MAX_RSA_PUBLIC_EXPONENT_BYTES {
            0x80
        } else {
            1
        };
        *exponent.last_mut().expect("non-empty exponent") |= 1;
    }
    let exponent_bits =
        (exponent_bytes - 1) * 8 + (u8::BITS - exponent[0].leading_zeros()) as usize;
    encoded.extend_from_slice(
        &u16::try_from(exponent_bits)
            .expect("test exponent fits MPI")
            .to_be_bytes(),
    );
    encoded.extend_from_slice(&exponent);
    encoded
}

fn rsa_public_params() -> PublicParams {
    PublicParams::RSA(
        RsaPublicParams::try_from_reader(rsa_public_parameter_bytes(3).as_slice())
            .expect("8192-bit RSA parameters with exponent 65537 must parse"),
    )
}

fn parse_result(data: &[u8]) -> OpenPgpPublicKeyParseResult {
    parse_result_at(data, REFERENCE_TIME)
}

fn parse_result_at(data: &[u8], reference_time: u64) -> OpenPgpPublicKeyParseResult {
    OpenPgpPublicKeyParseResult::decode(
        parse_public_key_request(OpenPgpPublicKeyParseRequest {
            key_data: data.to_vec(),
            reference_time_epoch_seconds: Some(reference_time),
        })
        .expect("public-key parse request must produce a domain result")
        .as_slice(),
    )
    .expect("public-key parse result must decode")
}

fn armor_has_checksum(data: &[u8]) -> bool {
    data.split(|byte| *byte == b'\n')
        .any(|line| line.first() == Some(&b'='))
}

fn protected_secret_key() -> Vec<u8> {
    let (mut protected, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("secret-key fixture must parse");
    let password = Password::from("parser must not need this passphrase");
    let mut rng = StdRng::seed_from_u64(0x4b45_5947_5541_5244);
    protected
        .primary_key
        .set_password(&mut rng, &password)
        .expect("protect primary key");
    for subkey in &mut protected.secret_subkeys {
        subkey
            .key
            .set_password(&mut rng, &password)
            .expect("protect secret subkey");
    }
    protected
        .to_armored_bytes(ArmorOptions::default())
        .expect("armor protected secret key")
}

fn detached_request(content: Vec<u8>, public_keys: Vec<Vec<u8>>) -> OpenPgpVerifyRequest {
    OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content,
        signature: DETACHED_SIGNATURE.to_vec(),
        public_keys,
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    }
}

fn verification(request: OpenPgpVerifyRequest) -> OpenPgpVerification {
    OpenPgpVerification::decode(
        verify_request(request)
            .expect("verification request must produce a domain result")
            .as_slice(),
    )
    .expect("verification result must decode")
}

fn serialized_public_certificate(certificate: &SignedPublicKey) -> Vec<u8> {
    let mut encoded = Vec::with_capacity(certificate.write_len());
    certificate
        .to_writer(&mut encoded)
        .expect("test public certificate must serialize");
    encoded
}

fn serialized_detached_signature(signature: Signature) -> Vec<u8> {
    let detached = DetachedSignature::new(signature);
    let mut encoded = Vec::with_capacity(detached.write_len());
    detached
        .to_writer(&mut encoded)
        .expect("test detached signature must serialize");
    encoded
}

#[derive(Clone, Copy, Debug)]
enum CreationTimePlacement {
    Hashed,
    Unhashed,
    Missing,
}

fn place_creation_time(
    config: &mut SignatureConfig,
    placement: CreationTimePlacement,
    creation_time: u32,
) {
    let creation_time = Subpacket::regular(SubpacketData::SignatureCreationTime(
        Timestamp::from_secs(creation_time),
    ))
    .expect("signature creation time");
    match placement {
        CreationTimePlacement::Hashed => config.hashed_subpackets.push(creation_time),
        CreationTimePlacement::Unhashed => config.unhashed_subpackets.push(creation_time),
        CreationTimePlacement::Missing => {}
    }
}

fn sign_detached_with_config(
    config: SignatureConfig,
    signer: &impl pgp::types::SigningKey,
) -> Signature {
    config
        .sign(signer, &Password::empty(), Cursor::new(DETACHED_BODY))
        .expect("sign detached signature fixture")
}

fn sign_legacy_detached_with_config(
    config: SignatureConfig,
    signer: &impl pgp::types::SigningKey,
) -> Signature {
    let mut hasher = config
        .hash_alg
        .new_hasher()
        .expect("create legacy signature hasher");
    hasher.update(DETACHED_BODY);
    config
        .hash_signature_data(&mut hasher)
        .expect("hash legacy signature metadata");
    let hash = hasher.finalize();
    let signed_hash_value = [hash[0], hash[1]];
    let signature = signer
        .sign(&Password::empty(), config.hash_alg, &hash)
        .expect("sign legacy detached signature fixture");
    Signature::from_config(config, signed_hash_value, signature)
        .expect("construct legacy detached signature fixture")
}

fn generated_v4_signer(key_type: KeyType, seed: u64, user_id: &str) -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(key_type)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs((REFERENCE_TIME - 60) as u32))
        .primary_user_id(user_id.to_owned())
        .passphrase(None)
        .build()
        .expect("build V4 signing certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate V4 signing certificate")
}

fn detached_signature_with_policy_subpackets(
    secret: &SignedSecretKey,
    hash_algorithm: HashAlgorithm,
    creation_time: u32,
    additional_hashed: impl IntoIterator<Item = Subpacket>,
) -> Signature {
    detached_signature_signed_by(
        &secret.primary_key,
        hash_algorithm,
        creation_time,
        additional_hashed,
    )
}

fn detached_signature_signed_by<K>(
    signer: &K,
    hash_algorithm: HashAlgorithm,
    creation_time: u32,
    additional_hashed: impl IntoIterator<Item = Subpacket>,
) -> Signature
where
    K: pgp::types::SigningKey + KeyDetails,
{
    let mut hashed = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .expect("issuer fingerprint"),
    ];
    hashed.extend(additional_hashed);
    DetachedSignature::sign_binary_data_with_subpackets(
        StdRng::seed_from_u64(0x504f_4c49_4359_5347),
        signer,
        &Password::empty(),
        hash_algorithm,
        DETACHED_BODY,
        SubpacketConfig::UserDefined {
            hashed,
            unhashed: vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
                    .expect("issuer key ID"),
            ],
        },
    )
    .expect("sign detached policy fixture")
    .signature
}

fn issuerless_v4_detached_signature<K>(signer: &K, creation_time: u32) -> Signature
where
    K: pgp::types::SigningKey + KeyDetails,
{
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    ];
    sign_detached_with_config(config, signer)
}

fn data_signature_with_intended_recipient(
    secret: &SignedSecretKey,
    signature_type: SignatureType,
    hashed: bool,
    critical: bool,
    seed: u64,
) -> Signature {
    let signer = &secret.primary_key;
    let mut config = match signer.version() {
        KeyVersion::V4 => {
            SignatureConfig::v4(signature_type, signer.algorithm(), HashAlgorithm::Sha256)
        }
        KeyVersion::V6 => SignatureConfig::v6(
            StdRng::seed_from_u64(seed),
            signature_type,
            signer.algorithm(),
            HashAlgorithm::Sha256,
        )
        .expect("create v6 recipient-bound signature config"),
        version => panic!("unsupported test key version: {version:?}"),
    };
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            data_signature_fixture_creation_time(secret),
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .expect("issuer fingerprint"),
    ];
    if signer.version() == KeyVersion::V4 {
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
                .expect("issuer key ID"),
        );
    }
    let recipient = if critical {
        Subpacket::critical(SubpacketData::IntendedRecipientFingerprint(
            signer.fingerprint(),
        ))
        .expect("critical intended recipient fingerprint")
    } else {
        Subpacket::regular(SubpacketData::IntendedRecipientFingerprint(
            signer.fingerprint(),
        ))
        .expect("intended recipient fingerprint")
    };
    if hashed {
        config.hashed_subpackets.push(recipient);
    } else {
        config.unhashed_subpackets.push(recipient);
    }
    config
        .sign(
            signer,
            &Password::empty(),
            Cursor::new(data_signature_fixture_content(signature_type)),
        )
        .expect("sign recipient-bound data fixture")
}

fn data_signature_fixture_content(signature_type: SignatureType) -> &'static [u8] {
    if signature_type == SignatureType::Text {
        DETACHED_BODY
            .strip_suffix(b"\n")
            .expect("text fixture has a cleartext-framework separator")
    } else {
        DETACHED_BODY
    }
}

fn data_signature_fixture_creation_time(secret: &SignedSecretKey) -> u32 {
    secret
        .details
        .direct_signatures
        .iter()
        .chain(
            secret
                .details
                .users
                .iter()
                .flat_map(|user| user.signatures.iter()),
        )
        .filter_map(signature_creation_time)
        .max()
        .expect("test certificate carries a self-signature")
        .checked_add(1)
        .expect("test signature creation time")
}

fn generated_v6_data_signer() -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs((REFERENCE_TIME - 60) as u32))
        .primary_user_id("V6 Data Policy <v6-data-policy@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 data-policy signer")
        .generate(StdRng::seed_from_u64(0x5636_4441_5441_504f))
        .expect("generate v6 data-policy signer")
}

fn verification_for_data_signature(
    secret: &SignedSecretKey,
    signature: Signature,
) -> OpenPgpVerification {
    let signature_type = signature.typ().expect("data signature type");
    let public_key = serialized_public_certificate(&secret.to_public_key());
    let reference_time = u64::from(data_signature_fixture_creation_time(secret)) + 1;
    match signature_type {
        SignatureType::Binary => verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature),
            public_keys: vec![public_key],
            reference_time_epoch_seconds: Some(reference_time),
        }),
        SignatureType::Text => {
            let armored_signature = DetachedSignature::new(signature)
                .to_armored_bytes(ArmorOptions::default())
                .expect("armor cleartext signature");
            let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n".to_vec();
            document.extend_from_slice(DETACHED_BODY);
            if !DETACHED_BODY.ends_with(b"\n") {
                document.push(b'\n');
            }
            document.extend_from_slice(&armored_signature);
            verification(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: document,
                signature: Vec::new(),
                public_keys: vec![public_key],
                reference_time_epoch_seconds: Some(reference_time),
            })
        }
        signature_type => panic!("unsupported test signature type: {signature_type:?}"),
    }
}

fn detached_signature_for_content(
    secret: &SignedSecretKey,
    content: &[u8],
    creation_time: u32,
    seed: u64,
) -> Signature {
    DetachedSignature::sign_binary_data_with_subpackets(
        StdRng::seed_from_u64(seed),
        &secret.primary_key,
        &Password::empty(),
        HashAlgorithm::Sha256,
        content,
        SubpacketConfig::UserDefined {
            hashed: vec![
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    creation_time,
                )))
                .expect("signature creation time"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    secret.primary_key.fingerprint(),
                ))
                .expect("issuer fingerprint"),
            ],
            unhashed: vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(
                    secret.primary_key.legacy_key_id(),
                ))
                .expect("issuer key ID"),
            ],
        },
    )
    .expect("sign detached multi-signature fixture")
    .signature
}

fn historical_signing_certificate(creation_time: u64) -> SignedSecretKey {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Historical Signer <historical@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: creation_time,
            expiration_seconds: None,
        })
        .expect("generate historical signing certificate")
        .as_slice(),
    )
    .expect("decode historical signing certificate");
    SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
        .expect("parse historical signing certificate")
        .0
}

fn primary_key_revocation(
    secret: &SignedSecretKey,
    creation_time: u32,
    reason: Option<RevocationCode>,
) -> Signature {
    let primary = &secret.primary_key;
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(primary.fingerprint()))
            .expect("revocation issuer fingerprint"),
    ];
    if let Some(reason) = reason {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
                .expect("revocation reason"),
        );
    }
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary.legacy_key_id()))
            .expect("revocation issuer key ID"),
    ];
    config
        .sign_key(primary, &Password::empty(), primary.public_key())
        .expect("sign primary-key revocation")
}

fn signing_subkey_index(secret: &SignedSecretKey) -> usize {
    secret
        .secret_subkeys
        .iter()
        .position(|subkey| {
            subkey.signatures.iter().any(|signature| {
                authenticated_key_flags(signature).is_some_and(|flags| flags.sign())
            })
        })
        .expect("generated certificate carries a signing subkey")
}

fn replacement_subkey_binding(
    secret: &SignedSecretKey,
    subkey_index: usize,
    creation_time: u32,
    signature_expiration_seconds: Option<u32>,
    may_sign: bool,
) -> Signature {
    replacement_subkey_binding_with_hash(
        secret,
        subkey_index,
        creation_time,
        signature_expiration_seconds,
        may_sign,
        HashAlgorithm::Sha256,
    )
}

fn replacement_subkey_binding_with_hash(
    secret: &SignedSecretKey,
    subkey_index: usize,
    creation_time: u32,
    signature_expiration_seconds: Option<u32>,
    may_sign: bool,
    hash_algorithm: HashAlgorithm,
) -> Signature {
    let primary = &secret.primary_key;
    let subkey = secret.secret_subkeys[subkey_index].key.public_key().clone();
    let mut config = secret.secret_subkeys[subkey_index]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("generated subkey binding config");
    config.hash_alg = hash_algorithm;
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_)
                | SubpacketData::SignatureExpirationTime(_)
                | SubpacketData::KeyFlags(_)
        )
    });
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("replacement binding creation time"),
    );
    if let Some(expiration) = signature_expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(
                pgp::types::Duration::from_secs(expiration),
            ))
            .expect("replacement binding expiration"),
        );
    }
    let mut flags = KeyFlags::default();
    flags.set_sign(may_sign);
    flags.set_encrypt_comms(!may_sign);
    flags.set_encrypt_storage(!may_sign);
    config
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("replacement flags"));
    config
        .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
        .expect("sign replacement subkey binding")
}

fn direct_key_signature_and_document(
    secret: &SignedSecretKey,
    creation_time: u32,
) -> (Signature, Vec<u8>) {
    let key = secret.primary_key.public_key();
    let key_len = u16::try_from(key.write_len()).expect("v4 key packet body length");
    let mut document = Vec::with_capacity(key.write_len() + 3);
    document.push(0x99);
    document.extend_from_slice(&key_len.to_be_bytes());
    key.to_writer(&mut document)
        .expect("serialize key hash input");

    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("issuer key ID"),
    ];
    let signature = config
        .sign_key(&secret.primary_key, &Password::empty(), key)
        .expect("sign direct key fixture");
    (signature, document)
}

fn detached_text_signature_for_content(
    secret: &SignedSecretKey,
    content: &[u8],
    creation_time: u32,
) -> Signature {
    DetachedSignature::sign_text_data_with_subpackets(
        StdRng::seed_from_u64(0x5445_5854_5041_5249),
        &secret.primary_key,
        &Password::empty(),
        HashAlgorithm::Sha256,
        content,
        SubpacketConfig::UserDefined {
            hashed: vec![
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    creation_time,
                )))
                .expect("signature creation time"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    secret.primary_key.fingerprint(),
                ))
                .expect("issuer fingerprint"),
            ],
            unhashed: vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(
                    secret.primary_key.legacy_key_id(),
                ))
                .expect("issuer key ID"),
            ],
        },
    )
    .expect("sign detached text parity fixture")
    .signature
}

fn serialized_detached_signatures(signatures: impl IntoIterator<Item = Signature>) -> Vec<u8> {
    signatures
        .into_iter()
        .flat_map(serialized_detached_signature)
        .collect()
}

fn fixed_openpgp_packet(tag: u8, body: &[u8]) -> Vec<u8> {
    let mut packet = Vec::new();
    crate::openpgp::packet::write_fixed_packet(tag, body, &mut packet)
        .expect("test packet must fit the fixed-length framing");
    packet
}

fn armored_signature_packets(packets: &[u8]) -> Vec<u8> {
    let mut armored = Vec::new();
    armor::write(
        &RawPackets(packets),
        BlockType::Signature,
        &mut armored,
        None,
        true,
    )
    .expect("test signature packets must armor");
    armored
}

fn binary_detached_signature_fixture() -> Vec<u8> {
    decode_openpgp_packets(DETACHED_SIGNATURE).expect("detached signature fixture must dearmor")
}

fn signature_packet_with_body_byte(index: usize, value: u8) -> Vec<u8> {
    let binary = binary_detached_signature_fixture();
    let stream = RawPacketStream::parse(&binary, MAX_PACKETS_PER_REQUEST)
        .expect("detached signature fixture must frame");
    let packet = stream
        .packets()
        .first()
        .expect("detached signature fixture must contain a packet");
    assert_eq!(packet.tag(), SIGNATURE_TAG);
    assert_eq!(stream.packets().len(), 1);
    let mut body = stream.body_to_vec(packet);
    body[index] = value;
    fixed_openpgp_packet(SIGNATURE_TAG, &body)
}

/// A completely framed V6 Signature packet whose zero-byte salt contradicts
/// SHA-256's required 16-byte salt.  rPGP rejects it after reading only this
/// packet body, making it a useful regression fixture for semantic isolation.
fn malformed_v6_salt_signature_packet() -> Vec<u8> {
    fixed_openpgp_packet(
        SIGNATURE_TAG,
        &[
            6,  // version
            0,  // binary signature
            27, // EdDSA signing algorithm
            8,  // SHA-256
            0, 0, 0, 0, // hashed subpacket length
            0, 0, 0, 0, // unhashed subpacket length
            0, 0, // left 16 hash bits
            0, // invalid SHA-256 salt length
        ],
    )
}

fn detached_verification_with_signature(signature: Vec<u8>) -> OpenPgpVerification {
    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = signature;
    verification(request)
}

fn streamed_detached_verification_with_signature(signature: Vec<u8>) -> OpenPgpVerification {
    streamed_detached_verification(&OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature,
        public_keys: vec![PUBLIC_KEY.to_vec()],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    })
}

fn streamed_detached_verification(request: &OpenPgpVerifyRequest) -> OpenPgpVerification {
    let mut stream = crate::openpgp::adapter::OpenPgpSession::detached_verify(
        OpenPgpDetachedVerifyStreamOpenRequest {
            signature: request.signature.clone(),
            public_keys: request.public_keys.clone(),
            reference_time_epoch_seconds: request.reference_time_epoch_seconds,
        },
    )
    .expect("open detached verification stream");
    for chunk in request.content.chunks(3) {
        stream.update(chunk).expect("stream detached body chunk");
    }
    OpenPgpVerification::decode(
        stream
            .finish()
            .expect("finish detached verification stream")
            .as_slice(),
    )
    .expect("decode detached verification result")
}

#[test]
fn fixed_gnupg_public_certificate_has_exact_authenticated_dto_and_rearmor() {
    let result = parse_result(PUBLIC_KEY);
    let success = match result.result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        _ => panic!("fixed public certificate must parse"),
    };
    let key = success.keys.first().expect("one public certificate");
    assert_eq!(success.keys.len(), 1);
    assert_eq!(key.fingerprint, PRIMARY_FINGERPRINT);
    assert_eq!(key.keygrip.as_deref(), Some(PRIMARY_KEYGRIP));
    assert_eq!(key.key_id, "F83D947D29EFECF7");
    assert_eq!(key.algorithm, "EDDSA");
    assert_eq!(key.bit_strength, Some(256));
    assert_eq!(key.user_ids, [USER_ID]);
    assert_eq!(key.emails, ["cv25519@test.invalid"]);
    assert_eq!(key.created_at_epoch_seconds, Some(1_782_541_263));
    assert_eq!(key.expires_at_epoch_seconds, None);
    assert!(!key.revoked);
    assert!(key.can_sign);
    assert!(key.can_encrypt);
    assert_eq!(
        key.public_key_armored.trim_end(),
        std::str::from_utf8(PUBLIC_KEY)
            .expect("public fixture must be UTF-8")
            .trim_end(),
    );

    let subkey = key.subkeys.first().expect("one authenticated subkey");
    assert_eq!(key.subkeys.len(), 1);
    assert_eq!(subkey.fingerprint, SUBKEY_FINGERPRINT);
    assert_eq!(subkey.keygrip.as_deref(), Some(SUBKEY_KEYGRIP));
    assert_eq!(subkey.key_id, "77648D3E5D4E7699");
    assert_eq!(subkey.algorithm, "ECDH");
    assert_eq!(subkey.bit_strength, Some(256));
    assert!(!subkey.can_sign);
    assert!(subkey.can_encrypt);
    assert!(!subkey.revoked);
    assert_eq!(subkey.created_at_epoch_seconds, Some(1_782_541_292));
    assert_eq!(subkey.expires_at_epoch_seconds, None);
    assert_eq!(
        key.component_fingerprints,
        [PRIMARY_FINGERPRINT, SUBKEY_FINGERPRINT],
    );
    assert!(key.revocation_authority_fingerprints.is_empty());
}

#[test]
fn metadata_rearmor_omits_v6_public_and_secret_projection_checksums() {
    let reference_time = reference_time(None);
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(
            u32::try_from(reference_time.saturating_sub(60)).expect("test timestamp"),
        ))
        .primary_user_id("V6 Metadata <v6-metadata@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 metadata key parameters")
        .generate(StdRng::seed_from_u64(0x5636_4d45_5441_4441))
        .expect("generate v6 metadata key");
    let options = ArmorOptions {
        include_checksum: true,
        ..ArmorOptions::default()
    };
    let public_input = secret
        .to_public_key()
        .to_armored_bytes(options.clone())
        .expect("armor v6 public input with a legacy checksum");
    let secret_input = secret
        .to_armored_bytes(options)
        .expect("armor v6 secret input with a legacy checksum");

    for (label, input) in [("public", public_input), ("secret", secret_input)] {
        let result = parse_result_at(&input, reference_time);
        let success = match result.result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
            result => panic!("v6 {label} key must parse, got {result:?}"),
        };
        let key = success.keys.first().expect("one v6 public certificate");
        assert_eq!(success.keys.len(), 1);
        assert!(
            !armor_has_checksum(key.public_key_armored.as_bytes()),
            "{label} projection",
        );
        let (reparsed, _) =
            SignedPublicKey::from_reader_single(Cursor::new(key.public_key_armored.as_bytes()))
                .unwrap_or_else(|error| panic!("reparse v6 {label} projection: {error}"));
        assert_eq!(reparsed.primary_key.version(), KeyVersion::V6);
    }

    let v4 = parse_result(PUBLIC_KEY);
    let Some(open_pgp_public_key_parse_result::Result::Success(v4)) = v4.result else {
        panic!("v4 control key must parse");
    };
    let v4 = v4.keys.first().expect("one v4 public certificate");
    assert!(armor_has_checksum(v4.public_key_armored.as_bytes()));
    SignedPublicKey::from_reader_single(Cursor::new(v4.public_key_armored.as_bytes()))
        .expect("reparse v4 control certificate");
}

#[test]
fn multi_ring_parser_preserves_input_order_and_original_packet_bytes() {
    let first_packets = decode_openpgp_packets(DESIGNATED_REVOKED_PUBLIC_KEY)
        .expect("designated-revocation victim ring must dearmor");
    let second_packets =
        decode_openpgp_packets(DESIGNATED_REVOKER_PUBLIC_KEY).expect("second ring must dearmor");
    let mut collection_packets = first_packets.clone();
    collection_packets.extend_from_slice(&second_packets);
    let collection =
        armor_public_key_packets(&collection_packets, true).expect("test collection must armor");

    let first_fingerprint = match parse_result(DESIGNATED_REVOKED_PUBLIC_KEY).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => {
            success.keys[0].fingerprint.clone()
        }
        _ => panic!("first ring must parse"),
    };
    let second_fingerprint = match parse_result(DESIGNATED_REVOKER_PUBLIC_KEY).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => {
            success.keys[0].fingerprint.clone()
        }
        _ => panic!("second ring must parse"),
    };
    let parsed = match parse_result(collection.as_bytes()).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success.keys,
        _ => panic!("multi-ring collection must parse"),
    };

    assert_eq!(parsed.len(), 2);
    assert_eq!(
        parsed
            .iter()
            .map(|key| key.fingerprint.as_str())
            .collect::<Vec<_>>(),
        [first_fingerprint.as_str(), second_fingerprint.as_str()],
    );
    assert_eq!(
        decode_openpgp_packets(parsed[0].public_key_armored.as_bytes()),
        Ok(first_packets),
    );
    assert_eq!(
        decode_openpgp_packets(parsed[1].public_key_armored.as_bytes()),
        Ok(second_packets),
    );
}

#[test]
fn parser_projects_protected_secret_certificate_without_passphrase() {
    let protected = protected_secret_key();
    let (expected_projection, _protected_overlay) =
        project_secret_certificate(&protected).expect("protected secret key must project");
    let (unprotected_projection, _unprotected_overlay) =
        project_secret_certificate(SECRET_KEY).expect("secret-key fixture must project");
    assert_eq!(expected_projection, unprotected_projection);

    crate::openpgp::adapter::wire::reset_zeroized_secret_request_drops();
    let parsed = match parse_result(&protected).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success.keys,
        _ => panic!("protected secret certificate must parse as public"),
    };
    assert_eq!(
        crate::openpgp::adapter::wire::zeroized_secret_request_drops(),
        1
    );
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].fingerprint, PRIMARY_FINGERPRINT);
    assert!(
        parsed[0]
            .public_key_armored
            .starts_with("-----BEGIN PGP PUBLIC KEY BLOCK-----")
    );

    let returned_packets =
        decode_openpgp_packets(parsed[0].public_key_armored.as_bytes()).expect("dearmor output");
    assert_eq!(returned_packets, expected_projection);
    let stream = RawPacketStream::parse(&returned_packets, MAX_PACKETS_PER_REQUEST)
        .expect("projected public packets must scan");
    assert_eq!(
        stream.packets().first().map(|packet| packet.tag()),
        Some(u8::from(Tag::PublicKey)),
    );
    assert!(
        stream
            .packets()
            .iter()
            .all(|packet| { !matches!(packet.tag(), 5 | 7) })
    );
}

#[test]
fn parser_projects_filtered_tsk_with_public_primary_and_secret_subkey() {
    let filtered = filtered_tsk_fixture();
    let (expected_projection, _secret_overlay) =
        project_secret_certificate(&filtered).expect("filtered TSK must project");

    let parsed = match parse_result(&filtered).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success.keys,
        _ => panic!("filtered TSK must parse as public"),
    };
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].fingerprint, PRIMARY_FINGERPRINT);

    let returned_packets = decode_openpgp_packets(parsed[0].public_key_armored.as_bytes())
        .expect("dearmor projected output");
    assert_eq!(returned_packets, expected_projection);
    let stream = RawPacketStream::parse(&returned_packets, MAX_PACKETS_PER_REQUEST)
        .expect("projected public packets must scan");
    assert!(
        stream
            .packets()
            .iter()
            .all(|packet| !matches!(packet.tag(), 5 | 7))
    );
}

#[test]
fn public_parse_preserves_local_only_evidence_but_secret_projection_withholds_it() {
    let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("secret-key fixture must parse");
    let user_id = secret.details.users[0].id.clone();
    let mut local_config = secret.details.users[0].signatures[0]
        .config()
        .cloned()
        .expect("fixture self-certification config");
    local_config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::ExportableCertification(false))
            .expect("local-only certification subpacket"),
    );
    let local_certification = local_config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign local-only self-certification");
    secret.details.users[0].signatures = vec![local_certification];
    let secret_packets = secret
        .to_bytes()
        .expect("serialize local-only secret certificate");
    let (public_packets, _) =
        project_secret_certificate(&secret_packets).expect("project local-only secret certificate");

    let public_key = match parse_result(&public_packets).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success
            .keys
            .into_iter()
            .next()
            .expect("one parsed public certificate"),
        other => panic!("expected a successful public parse, got {other:?}"),
    };
    assert_eq!(public_key.user_ids, [USER_ID]);
    assert_eq!(
        decode_openpgp_packets(public_key.public_key_armored.as_bytes())
            .expect("dearmor preserved public certificate"),
        public_packets,
    );

    let secret_key = match parse_result(&secret_packets).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success
            .keys
            .into_iter()
            .next()
            .expect("one parsed secret certificate"),
        other => panic!("expected a successful secret parse, got {other:?}"),
    };
    assert_eq!(secret_key.user_ids, [USER_ID]);
    let secret_public = parse_public_certificates_fresh(secret_key.public_key_armored.as_bytes())
        .expect("reparse ordinary secret-key projection");
    assert_eq!(secret_public.len(), 1);
    assert!(
        secret_public[0].details.users.is_empty(),
        "ordinary secret-key projection must withhold the local-only identity certification",
    );
}

#[test]
fn parser_rejects_multiple_secret_certificates() {
    let packets = decode_openpgp_packets(SECRET_KEY).expect("secret-key fixture must dearmor");
    let mut collection = packets.clone();
    collection.extend_from_slice(&packets);

    let error = match parse_result(&collection).result {
        Some(open_pgp_public_key_parse_result::Result::Error(error)) => error,
        _ => panic!("multiple secret certificates must return a typed error"),
    };
    // The input is well-formed OpenPGP; the operation just accepts one
    // secret certificate, and the reason must say so.
    assert_eq!(
        error.reason,
        OpenPgpPublicKeyParseErrorReason::MultipleCertificates as i32,
    );
}

#[test]
fn parser_returns_stable_expected_errors() {
    for (input, expected) in [
        (
            b" \r\n\t".as_slice(),
            OpenPgpPublicKeyParseErrorReason::Empty,
        ),
        (
            b"not an OpenPGP certificate".as_slice(),
            OpenPgpPublicKeyParseErrorReason::Malformed,
        ),
    ] {
        let result = parse_result(input);
        let error = match result.result {
            Some(open_pgp_public_key_parse_result::Result::Error(error)) => error,
            _ => panic!("invalid public certificate must return a typed error"),
        };
        assert_eq!(error.reason, expected as i32);
    }
}

#[test]
fn parser_rejects_legacy_v3_key_packets_with_typed_error() {
    // Old-format Public-Key packet with a structurally valid v3 RSA body.
    let v3_public_key = [
        0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
    ];
    let result = parse_result(&v3_public_key);
    let error = match result.result {
        Some(open_pgp_public_key_parse_result::Result::Error(error)) => error,
        _ => panic!("legacy public key must return a typed error"),
    };
    assert_eq!(
        error.reason,
        OpenPgpPublicKeyParseErrorReason::UnsupportedKeyVersion as i32,
    );
}

#[test]
fn fixed_gnupg_detached_signature_reports_valid_invalid_and_missing_key() {
    let valid = verification(detached_request(
        DETACHED_BODY.to_vec(),
        vec![PUBLIC_KEY.to_vec()],
    ));
    assert_verification(&valid, OpenPgpVerificationStatus::Valid, 1_784_073_600);

    let mut changed_body = DETACHED_BODY.to_vec();
    changed_body[0] ^= 1;
    let invalid = verification(detached_request(changed_body, vec![PUBLIC_KEY.to_vec()]));
    assert_verification(&invalid, OpenPgpVerificationStatus::Invalid, 1_784_073_600);

    let missing = verification(detached_request(DETACHED_BODY.to_vec(), Vec::new()));
    assert_eq!(
        missing.status,
        OpenPgpVerificationStatus::MissingPublicKey as i32,
    );
    assert_eq!(missing.key_id, "F83D947D29EFECF7");
    assert_eq!(missing.fingerprint, None);
    assert!(missing.user_ids.is_empty());
    assert_eq!(missing.created_at_epoch_seconds, Some(1_784_073_600));
    assert!(missing.warnings.is_empty());
}

#[test]
fn v4_document_signature_requires_hashed_creation_time() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let creation_time = (REFERENCE_TIME - 1) as u32;

    for (placement, expected) in [
        (
            CreationTimePlacement::Hashed,
            OpenPgpVerificationStatus::Valid,
        ),
        (
            CreationTimePlacement::Unhashed,
            OpenPgpVerificationStatus::Invalid,
        ),
        (
            CreationTimePlacement::Missing,
            OpenPgpVerificationStatus::Invalid,
        ),
    ] {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                secret.primary_key.fingerprint(),
            ))
            .expect("issuer fingerprint"),
        );
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(
                secret.primary_key.legacy_key_id(),
            ))
            .expect("issuer key ID"),
        );
        place_creation_time(&mut config, placement, creation_time);
        let signature = sign_detached_with_config(config, &secret.primary_key);
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("v4 fixture remains mathematically valid");

        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = serialized_detached_signature(signature);
        let result = verification(request);
        assert_eq!(
            result.status, expected as i32,
            "creation-time placement: {placement:?}",
        );
    }
}

#[test]
fn issuerless_v4_detached_signature_tries_the_supplied_signer() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature =
        issuerless_v4_detached_signature(&secret.primary_key, (REFERENCE_TIME - 1) as u32);
    assert!(
        SignatureIssuerMetadata::from_signature(&signature).is_missing(),
        "fixture must not carry an issuer routing hint",
    );
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("issuerless fixture remains mathematically valid");

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized_detached_signature(signature);
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(result.key_id, "0000000000000000");
    assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
    assert_eq!(
        result.primary_fingerprint.as_deref(),
        Some(PRIMARY_FINGERPRINT),
    );
    assert_eq!(result.user_ids, [USER_ID]);
}

#[test]
fn issuerless_v4_detached_signature_does_not_identify_a_wrong_supplied_certificate() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let wrong = generated_v4_signer(
        KeyType::Ed25519Legacy,
        0x4953_5355_4552_4c01,
        "Wrong issuerless signer <wrong-issuerless@example.test>",
    );
    assert_eq!(
        wrong.primary_key.algorithm(),
        secret.primary_key.algorithm()
    );
    let signature =
        issuerless_v4_detached_signature(&secret.primary_key, (REFERENCE_TIME - 1) as u32);

    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signature(signature),
        public_keys: vec![serialized_public_certificate(&wrong.to_public_key())],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Invalid as i32);
    assert_eq!(result.key_id, "0000000000000000");
    assert_eq!(result.fingerprint, None);
    assert_eq!(result.primary_fingerprint, None);
    assert!(result.user_ids.is_empty());
}

#[test]
fn issuerless_v4_detached_signature_tries_multiple_compatible_candidates() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let wrong = generated_v4_signer(
        KeyType::Ed25519Legacy,
        0x4953_5355_4552_4c02,
        "First issuerless candidate <first-issuerless@example.test>",
    );
    let signature =
        issuerless_v4_detached_signature(&secret.primary_key, (REFERENCE_TIME - 1) as u32);
    let signature = serialized_detached_signature(signature);
    let wrong = serialized_public_certificate(&wrong.to_public_key());

    for public_keys in [
        vec![wrong.clone(), PUBLIC_KEY.to_vec()],
        vec![PUBLIC_KEY.to_vec(), wrong.clone()],
    ] {
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: signature.clone(),
            public_keys,
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });

        assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
        assert_eq!(result.key_id, "0000000000000000");
        assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
        assert_eq!(
            result.primary_fingerprint.as_deref(),
            Some(PRIMARY_FINGERPRINT),
        );
    }
}

#[test]
fn issuerless_candidate_limit_is_inclusive_and_hints_still_route_narrowly() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let issuerless =
        issuerless_v4_detached_signature(&secret.primary_key, (REFERENCE_TIME - 1) as u32);
    let certificate = secret.to_public_key();

    for (count, expect_selected) in [
        (MAX_SIGNER_CANDIDATES_PER_SIGNATURE, true),
        (MAX_SIGNER_CANDIDATES_PER_SIGNATURE + 1, false),
    ] {
        let certificates = vec![certificate.clone(); count];
        let candidates = all_components(&certificates);
        let mut validated = std::iter::repeat_with(CertificateValidationCache::new)
            .take(certificates.len())
            .collect::<Vec<_>>();
        let resolution = resolve_signer(
            &issuerless,
            &certificates,
            &candidates,
            &mut validated,
            DataSignatureVerificationTime::exact(REFERENCE_TIME),
            &mut OpenPgpReadBudget::default(),
        )
        .expect("resolve bounded issuerless candidates");

        match resolution {
            SignerResolution::Selected {
                signers,
                report_rejected_signer,
            } if expect_selected => {
                assert_eq!(signers.len(), MAX_SIGNER_CANDIDATES_PER_SIGNATURE);
                assert!(!report_rejected_signer);
            }
            SignerResolution::Rejected { fingerprint: None } if !expect_selected => {}
            _ => panic!("unexpected bounded issuerless resolution"),
        }
    }

    let hinted = parse_detached_signatures_fresh(DETACHED_SIGNATURE)
        .expect("fixed detached signature must parse")
        .remove(0);
    let wrong = generated_v4_signer(
        KeyType::Ed25519Legacy,
        0x4953_5355_4552_4c03,
        "Wrong hinted signer <wrong-hinted@example.test>",
    )
    .to_public_key();
    let certificates = vec![wrong, certificate];
    let candidates = all_components(&certificates);
    let mut validated = std::iter::repeat_with(CertificateValidationCache::new)
        .take(certificates.len())
        .collect::<Vec<_>>();
    let hinted_verification_time =
        u64::from(signature_creation_time(&hinted).expect("hinted signature creation time")) + 1;
    let resolution = resolve_signer(
        &hinted,
        &certificates,
        &candidates,
        &mut validated,
        DataSignatureVerificationTime::exact(hinted_verification_time),
        &mut OpenPgpReadBudget::default(),
    )
    .expect("resolve hinted signer narrowly");
    let SignerResolution::Selected {
        signers,
        report_rejected_signer,
    } = resolution
    else {
        panic!("hinted signer must resolve");
    };
    assert!(report_rejected_signer);
    assert_eq!(signers.len(), 1);
    assert_eq!(signers[0].fingerprint, PRIMARY_FINGERPRINT);
}

#[test]
fn v4_detached_verification_prefers_hashed_issuer_over_unhashed_conflict() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let creation_time = (REFERENCE_TIME - 1) as u32;
    let wrong_key_id = [0xa5; 8].into();
    assert_ne!(wrong_key_id, secret.primary_key.legacy_key_id());

    for (with_fingerprint, key_id, expected) in [
        (
            false,
            secret.primary_key.legacy_key_id(),
            OpenPgpVerificationStatus::Valid,
        ),
        (true, wrong_key_id, OpenPgpVerificationStatus::Valid),
    ] {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                creation_time,
            )))
            .expect("signature creation time"),
        );
        if with_fingerprint {
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    secret.primary_key.fingerprint(),
                ))
                .expect("issuer fingerprint"),
            );
        }
        config
            .unhashed_subpackets
            .push(Subpacket::regular(SubpacketData::IssuerKeyId(key_id)).expect("issuer key ID"));
        let signature = sign_detached_with_config(config, &secret.primary_key);
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("v4 fixture remains mathematically valid");

        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = serialized_detached_signature(signature);
        let result = verification(request);
        assert_eq!(
            result.status, expected as i32,
            "fingerprint present: {with_fingerprint}",
        );
    }
}

#[test]
fn v4_detached_verification_rejects_unhashed_candidate_after_foreign_hashed_issuer() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let wrong_key_id = [0xa5; 8].into();
    let wrong_fingerprint = Fingerprint::V4([0xa5; 20]);
    assert_ne!(wrong_key_id, secret.primary_key.legacy_key_id());
    assert_ne!(wrong_fingerprint, secret.primary_key.fingerprint());

    let cases = [
        (
            "unhashed issuer fingerprint",
            SubpacketData::IssuerKeyId(wrong_key_id),
            SubpacketData::IssuerFingerprint(secret.primary_key.fingerprint()),
        ),
        (
            "unhashed issuer key ID",
            SubpacketData::IssuerFingerprint(wrong_fingerprint),
            SubpacketData::IssuerKeyId(secret.primary_key.legacy_key_id()),
        ),
    ];
    for (case, wrong_hashed_hint, correct_unhashed_hint) in cases {
        let mut config = SignatureConfig::v4(
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                (REFERENCE_TIME - 1) as u32,
            )))
            .expect("signature creation time"),
            Subpacket::regular(wrong_hashed_hint).expect("wrong hashed issuer hint"),
        ];
        config.unhashed_subpackets =
            vec![Subpacket::regular(correct_unhashed_hint).expect("correct unhashed issuer hint")];
        let signature = sign_detached_with_config(config, &secret.primary_key);
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("mixed-hint fixture remains mathematically valid");

        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = serialized_detached_signature(signature);
        let result = verification(request);

        assert_eq!(
            result.status,
            OpenPgpVerificationStatus::Invalid as i32,
            "case: {case}",
        );
        assert_eq!(
            result.fingerprint.as_deref(),
            Some(PRIMARY_FINGERPRINT),
            "routing metadata remains available for case: {case}",
        );
    }
}

#[test]
fn multiple_hashed_issuer_key_ids_remain_cryptographic_hints() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let alternate_key_id = [0xa5; 8].into();
    assert_ne!(alternate_key_id, secret.primary_key.legacy_key_id());

    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (REFERENCE_TIME - 1) as u32,
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("actual issuer fingerprint"),
        // RFC 9580 Section 5.2.3.9 explicitly permits this shape for V3
        // and V4 keys that share RSA material and therefore verify the
        // same signature despite carrying different key IDs.
        Subpacket::regular(SubpacketData::IssuerKeyId(alternate_key_id))
            .expect("alternate issuer key ID"),
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("actual issuer key ID"),
    ];
    let signature = sign_detached_with_config(config, &secret.primary_key);
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("multiple-ID fixture remains mathematically valid");

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized_detached_signature(signature);
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
}

#[test]
fn legacy_eddsa_document_signatures_reject_v2_and_forbidden_v3() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let creation_time = (REFERENCE_TIME - 1) as u32;
    let configurations = [
        (
            SignatureVersion::V2,
            OpenPgpVerificationStatus::Invalid,
            SignatureConfig::v2(
                SignatureType::Binary,
                secret.primary_key.algorithm(),
                HashAlgorithm::Sha256,
                Timestamp::from_secs(creation_time),
                secret.primary_key.legacy_key_id(),
            ),
        ),
        (
            SignatureVersion::V3,
            OpenPgpVerificationStatus::Invalid,
            SignatureConfig::v3(
                SignatureType::Binary,
                secret.primary_key.algorithm(),
                HashAlgorithm::Sha256,
                Timestamp::from_secs(creation_time),
                secret.primary_key.legacy_key_id(),
            ),
        ),
    ];

    for (version, expected_status, config) in configurations {
        let signature = sign_legacy_detached_with_config(config, &secret.primary_key);
        assert_eq!(signature.version(), version);
        assert!(
            signature
                .config()
                .expect("known legacy signature")
                .hashed_subpackets
                .is_empty(),
        );
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("legacy fixture remains mathematically valid");

        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = serialized_detached_signature(signature);
        let result = verification(request);
        assert_eq!(
            result.status, expected_status as i32,
            "legacy signature version: {version:?}",
        );
        assert_eq!(
            result.created_at_epoch_seconds,
            Some(u64::from(creation_time)),
            "legacy signature version: {version:?}",
        );
    }
}

#[test]
fn v3_ecc_document_signatures_are_mathematically_valid_but_rejected() {
    let creation_time = (REFERENCE_TIME - 1) as u32;
    let signers = [
        (
            "ECDSA",
            generated_v4_signer(
                KeyType::ECDSA(ECCCurve::P256),
                0x5633_4543_4453_4101,
                "V3 ECDSA <v3-ecdsa@example.test>",
            ),
            HashAlgorithm::Sha256,
        ),
        (
            "Ed25519",
            generated_v4_signer(
                KeyType::Ed25519,
                0x5633_4544_3235_3501,
                "V3 Ed25519 <v3-ed25519@example.test>",
            ),
            HashAlgorithm::Sha256,
        ),
        (
            "Ed448",
            generated_v4_signer(
                KeyType::Ed448,
                0x5633_4544_3434_3801,
                "V3 Ed448 <v3-ed448@example.test>",
            ),
            HashAlgorithm::Sha512,
        ),
    ];

    for (name, secret, hash_algorithm) in signers {
        let signature = sign_legacy_detached_with_config(
            SignatureConfig::v3(
                SignatureType::Binary,
                secret.primary_key.algorithm(),
                hash_algorithm,
                Timestamp::from_secs(creation_time),
                secret.primary_key.legacy_key_id(),
            ),
            &secret.primary_key,
        );
        signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("V3 ECC fixture remains mathematically valid in rPGP");

        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature),
            public_keys: vec![serialized_public_certificate(&secret.to_public_key())],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_eq!(
            result.status,
            OpenPgpVerificationStatus::Invalid as i32,
            "algorithm: {name}",
        );
    }
}

#[test]
fn ecdsa_document_verification_enforces_curve_digest_floors() {
    for (name, curve, weak, boundary, seed) in [
        (
            "P-256",
            ECCCurve::P256,
            HashAlgorithm::Sha224,
            HashAlgorithm::Sha256,
            0x4543_4453_4132_5601,
        ),
        (
            "P-384",
            ECCCurve::P384,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha384,
            0x4543_4453_4133_8401,
        ),
        (
            "P-521",
            ECCCurve::P521,
            HashAlgorithm::Sha384,
            HashAlgorithm::Sha512,
            0x4543_4453_4135_2101,
        ),
    ] {
        let secret = generated_v4_signer(
            KeyType::ECDSA(curve),
            seed,
            &format!("ECDSA {name} <ecdsa-floor@example.test>"),
        );
        let config = |hash_algorithm| {
            let mut config = SignatureConfig::v4(
                SignatureType::Binary,
                secret.primary_key.algorithm(),
                hash_algorithm,
            );
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    (REFERENCE_TIME - 1) as u32,
                )))
                .expect("signature creation time"),
            );
            config
        };

        let boundary_signature = sign_detached_with_config(config(boundary), &secret.primary_key);
        boundary_signature
            .verify(secret.primary_key.public_key(), DETACHED_BODY)
            .expect("boundary ECDSA fixture remains mathematically valid");
        assert!(signature_verification_compatible(
            &boundary_signature,
            &secret.primary_key,
        ));

        // rPGP's signing API refuses to create the weak fixture.  Reusing the
        // well-formed MPI shape exercises the shared pre-verification check.
        let weak_signature = Signature::from_config(
            config(weak),
            [0, 0],
            boundary_signature
                .signature()
                .expect("known ECDSA signature")
                .clone(),
        )
        .expect("construct weak-digest ECDSA signature shape");
        assert!(
            !signature_verification_compatible(&weak_signature, &secret.primary_key),
            "curve: {name}, weak hash: {weak:?}",
        );
    }
}

#[test]
fn ed_signature_verification_enforces_digest_floors() {
    let (legacy, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed Ed25519Legacy secret key must parse");
    let signers = [
        (
            "Ed25519Legacy",
            legacy,
            HashAlgorithm::Sha224,
            HashAlgorithm::Sha256,
        ),
        (
            "Ed25519",
            generated_v4_signer(
                KeyType::Ed25519,
                0x4544_3235_3546_4c01,
                "Ed25519 floor <ed25519-floor@example.test>",
            ),
            HashAlgorithm::Sha224,
            HashAlgorithm::Sha256,
        ),
        (
            "Ed448",
            generated_v4_signer(
                KeyType::Ed448,
                0x4544_3434_3846_4c01,
                "Ed448 floor <ed448-floor@example.test>",
            ),
            HashAlgorithm::Sha384,
            HashAlgorithm::Sha512,
        ),
    ];

    for (name, secret, weak, boundary) in signers {
        for (hash_algorithm, expected) in [(weak, false), (boundary, true)] {
            // rPGP refuses to create the weak EdDSA signatures.  Constructing
            // the packet directly exercises the shared pre-verification gate.
            let signature = Signature::from_config(
                SignatureConfig::v4(
                    SignatureType::Binary,
                    secret.primary_key.algorithm(),
                    hash_algorithm,
                ),
                [0, 0],
                SignatureBytes::Mpis(Vec::new()),
            )
            .expect("construct digest-floor signature shape");
            assert_eq!(
                signature_verification_compatible(&signature, &secret.primary_key),
                expected,
                "algorithm: {name}, hash: {hash_algorithm:?}",
            );
        }
    }
}

#[test]
fn rsa_v3_document_signature_remains_compatible() {
    let secret = generated_v4_signer(
        KeyType::Rsa(2_048),
        0x5253_4156_3343_4f01,
        "RSA V3 <rsa-v3@example.test>",
    );
    let signature = sign_legacy_detached_with_config(
        SignatureConfig::v3(
            SignatureType::Binary,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
            Timestamp::from_secs((REFERENCE_TIME - 1) as u32),
            secret.primary_key.legacy_key_id(),
        ),
        &secret.primary_key,
    );
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("RSA V3 fixture remains mathematically valid");
    assert!(signature_verification_compatible(
        &signature,
        &secret.primary_key,
    ));
}

#[test]
fn detached_verification_reports_last_hashed_v4_creation_time_used_by_policy() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let first_creation_time = (REFERENCE_TIME - 100) as u32;
    let authoritative_creation_time = (REFERENCE_TIME - 1) as u32;
    let expiration_seconds = 10_u32;
    let mut config = SignatureConfig::v4(
        SignatureType::Binary,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            first_creation_time,
        )))
        .expect("first creation time"),
        Subpacket::regular(SubpacketData::SignatureExpirationTime(
            pgp::types::Duration::from_secs(expiration_seconds),
        ))
        .expect("signature expiration time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint"),
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            authoritative_creation_time,
        )))
        .expect("last creation time"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("issuer key ID"),
    ];
    let signature = sign_detached_with_config(config, &secret.primary_key);

    assert!(u64::from(first_creation_time + expiration_seconds) <= REFERENCE_TIME);
    assert!(!signature_expired(&signature, REFERENCE_TIME));
    assert_eq!(
        signature.created().map(|time| time.as_secs()),
        Some(first_creation_time),
        "rPGP accessor demonstrates the first-occurrence mismatch",
    );

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized_detached_signature(signature);
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(
        result.created_at_epoch_seconds,
        Some(u64::from(authoritative_creation_time)),
    );
}

#[test]
fn detached_verification_uses_later_valid_signature_and_preserves_fallbacks() {
    let _guard = verifier_worker_test_guard();
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let first_created = (REFERENCE_TIME - 3) as u32;
    let second_created = (REFERENCE_TIME - 2) as u32;
    let invalid_first = detached_signature_for_content(
        &secret,
        b"content not present in the request",
        first_created,
        0x494e_5641_4c49_4431,
    );
    let valid_second = detached_signature_for_content(
        &secret,
        DETACHED_BODY,
        second_created,
        0x5641_4c49_4430_3032,
    );
    let signatures = serialized_detached_signatures([invalid_first.clone(), valid_second.clone()]);

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = signatures.clone();
    let one_shot = verification(request);
    assert_eq!(one_shot.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(
        one_shot.created_at_epoch_seconds,
        Some(u64::from(second_created)),
    );
    assert_eq!(
        one_shot
            .signatures
            .iter()
            .map(|result| result.status)
            .collect::<Vec<_>>(),
        vec![
            OpenPgpVerificationStatus::Invalid as i32,
            OpenPgpVerificationStatus::Valid as i32,
        ],
    );
    assert!(
        one_shot
            .signatures
            .iter()
            .all(|result| result.signatures.is_empty())
    );

    let mut stream = crate::openpgp::adapter::OpenPgpSession::detached_verify(
        OpenPgpDetachedVerifyStreamOpenRequest {
            signature: signatures,
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    )
    .expect("open multi-signature verification stream");
    for chunk in DETACHED_BODY.chunks(3) {
        stream.update(chunk).expect("stream body chunk");
    }
    let streamed = OpenPgpVerification::decode(
        stream
            .finish()
            .expect("finish multi-signature verification")
            .as_slice(),
    )
    .expect("decode streamed verification");
    assert_eq!(streamed, one_shot);

    let all_invalid = serialized_detached_signatures([
        invalid_first.clone(),
        detached_signature_for_content(
            &secret,
            b"another absent body",
            second_created,
            0x494e_5641_4c49_4432,
        ),
    ]);
    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = all_invalid.clone();
    let invalid = verification(request);
    assert_eq!(invalid.status, OpenPgpVerificationStatus::Invalid as i32);
    assert_eq!(
        invalid.created_at_epoch_seconds,
        Some(u64::from(first_created)),
    );
    assert_eq!(invalid.signatures.len(), 2);
    assert!(
        invalid
            .signatures
            .iter()
            .all(|result| { result.status == OpenPgpVerificationStatus::Invalid as i32 })
    );

    let mut request = detached_request(DETACHED_BODY.to_vec(), Vec::new());
    request.signature = all_invalid;
    let missing = verification(request);
    assert_eq!(
        missing.status,
        OpenPgpVerificationStatus::MissingPublicKey as i32,
    );
    assert_eq!(
        missing.created_at_epoch_seconds,
        Some(u64::from(first_created)),
    );
    assert_eq!(missing.signatures.len(), 2);
    assert!(
        missing
            .signatures
            .iter()
            .all(|result| { result.status == OpenPgpVerificationStatus::MissingPublicKey as i32 })
    );
}

#[test]
fn single_detached_signature_results_remain_flat_for_all_statuses_and_streaming() {
    let _guard = verifier_worker_test_guard();
    let cases = [
        (
            "valid",
            DETACHED_BODY.to_vec(),
            vec![PUBLIC_KEY.to_vec()],
            OpenPgpVerificationStatus::Valid,
        ),
        (
            "invalid",
            b"tampered detached body".to_vec(),
            vec![PUBLIC_KEY.to_vec()],
            OpenPgpVerificationStatus::Invalid,
        ),
        (
            "missing public key",
            DETACHED_BODY.to_vec(),
            Vec::new(),
            OpenPgpVerificationStatus::MissingPublicKey,
        ),
    ];

    for (case, content, public_keys, expected_status) in cases {
        let one_shot = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: content.clone(),
            signature: DETACHED_SIGNATURE.to_vec(),
            public_keys: public_keys.clone(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_eq!(one_shot.status, expected_status as i32, "case: {case}");
        assert!(one_shot.signatures.is_empty(), "case: {case}");

        let mut stream = crate::openpgp::adapter::OpenPgpSession::detached_verify(
            OpenPgpDetachedVerifyStreamOpenRequest {
                signature: DETACHED_SIGNATURE.to_vec(),
                public_keys,
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )
        .expect("open single-signature verification stream");
        for chunk in content.chunks(3) {
            stream.update(chunk).expect("stream body chunk");
        }
        let streamed = OpenPgpVerification::decode(
            stream
                .finish()
                .expect("finish single-signature verification")
                .as_slice(),
        )
        .expect("decode streamed verification");
        assert_eq!(streamed, one_shot, "case: {case}");
        assert!(streamed.signatures.is_empty(), "case: {case}");
    }
}

#[test]
fn text_signature_canonicalization_handles_all_line_endings_across_stream_boundaries() {
    let _guard = verifier_worker_test_guard();
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let content = b"lf\nlone\rnext\r\nsplit\r\ntrailing\r";
    let canonical_equivalent = b"lf\r\nlone\r\nnext\r\nsplit\r\ntrailing\r\n";
    let signature = detached_text_signature_for_content(
        &secret,
        canonical_equivalent,
        (REFERENCE_TIME - 1) as u32,
    );
    signature
        .verify(secret.primary_key.public_key(), &canonical_equivalent[..])
        .expect("signature must verify over canonical CRLF text");
    let serialized = serialized_detached_signature(signature);

    let mut request = detached_request(content.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized.clone();
    let one_shot = verification(request);
    assert_eq!(one_shot.status, OpenPgpVerificationStatus::Valid as i32);

    let mut stream = crate::openpgp::adapter::OpenPgpSession::detached_verify(
        OpenPgpDetachedVerifyStreamOpenRequest {
            signature: serialized,
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    )
    .expect("open text-signature verification stream");
    for byte in content {
        stream
            .update(std::slice::from_ref(byte))
            .expect("stream one text byte");
    }
    let streamed = OpenPgpVerification::decode(
        stream
            .finish()
            .expect("finish text-signature verification")
            .as_slice(),
    )
    .expect("decode text-signature verification");
    assert_eq!(streamed, one_shot);
}

#[test]
fn preverified_signatures_use_later_valid_signature_and_first_invalid_fallback() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let first_created = (REFERENCE_TIME - 3) as u32;
    let second_created = (REFERENCE_TIME - 2) as u32;
    let signatures = [
        detached_signature_for_content(
            &secret,
            b"first preverified signature",
            first_created,
            0x5052_4556_4552_4931,
        ),
        detached_signature_for_content(
            &secret,
            b"second preverified signature",
            second_created,
            0x5052_4556_4552_4932,
        ),
    ];
    let certificates =
        parse_public_certificates_fresh(PUBLIC_KEY).expect("fixed public certificate must parse");
    let mut visited = Vec::new();
    let valid = evaluate_preverified_signatures(
        &signatures,
        &certificates,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |signature_index, _| {
            visited.push(signature_index);
            signature_index == 1
        },
    )
    .expect("evaluate multiple preverified signatures");
    assert_eq!(visited, [0, 1]);
    assert_eq!(valid.status, VerificationStatus::Valid);
    assert_eq!(
        valid.created_at_epoch_seconds,
        Some(u64::from(second_created)),
    );

    let invalid = evaluate_preverified_signatures(
        &signatures,
        &certificates,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |_, _| false,
    )
    .expect("evaluate all-invalid preverified signatures");
    assert_eq!(invalid.status, VerificationStatus::Invalid);
    assert_eq!(
        invalid.created_at_epoch_seconds,
        Some(u64::from(first_created)),
    );

    let missing = evaluate_preverified_signatures(
        &signatures,
        &[],
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |_, _| panic!("missing signers must not be preverified"),
    )
    .expect("evaluate all-missing preverified signatures");
    assert_eq!(missing.status, VerificationStatus::MissingPublicKey,);
    assert_eq!(
        missing.created_at_epoch_seconds,
        Some(u64::from(first_created)),
    );
}

#[test]
fn preverified_signatures_skip_non_document_types_and_use_later_document_signature() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let non_document_created = (REFERENCE_TIME - 2) as u32;
    let document_created = (REFERENCE_TIME - 1) as u32;
    let (non_document, document) = direct_key_signature_and_document(&secret, non_document_created);
    let document =
        detached_signature_for_content(&secret, &document, document_created, 0x444f_4355_4d45_4e54);
    let certificates =
        parse_public_certificates_fresh(PUBLIC_KEY).expect("fixed public certificate must parse");

    let mut visited = Vec::new();
    let valid = evaluate_preverified_signatures(
        &[non_document.clone(), document],
        &certificates,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |signature_index, _| {
            visited.push(signature_index);
            true
        },
    )
    .expect("evaluate typed preverified signatures");
    assert_eq!(visited, [1]);
    assert_eq!(valid.status, VerificationStatus::Valid);
    assert_eq!(
        valid.created_at_epoch_seconds,
        Some(u64::from(document_created)),
    );

    let mut visited = Vec::new();
    let rejected = evaluate_preverified_signatures(
        std::slice::from_ref(&non_document),
        &certificates,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |signature_index, _| {
            visited.push(signature_index);
            true
        },
    )
    .expect("reject non-document preverified signature");
    assert!(visited.is_empty());
    assert_eq!(rejected.status, VerificationStatus::Invalid);
    assert_eq!(
        rejected.created_at_epoch_seconds,
        Some(u64::from(non_document_created)),
    );
}

#[test]
fn recipient_bound_v4_and_v6_data_signatures_require_an_encrypted_context() {
    let (v4, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed v4 secret key must parse");
    let v6 = generated_v6_data_signer();

    for (version_index, (version, secret)) in [("v4", v4), ("v6", v6)].into_iter().enumerate() {
        for (critical_index, critical) in [false, true].into_iter().enumerate() {
            for (type_index, signature_type) in [SignatureType::Binary, SignatureType::Text]
                .into_iter()
                .enumerate()
            {
                let signature = data_signature_with_intended_recipient(
                    &secret,
                    signature_type,
                    true,
                    critical,
                    0x494e_5452_4350_0000
                        + (version_index as u64) * 0x100
                        + (critical_index as u64) * 0x10
                        + type_index as u64,
                );
                signature
                    .verify(
                        secret.primary_key.public_key(),
                        data_signature_fixture_content(signature_type),
                    )
                    .expect("recipient-bound fixture must be mathematically valid");

                let result = verification_for_data_signature(&secret, signature);

                assert_eq!(
                    result.status,
                    OpenPgpVerificationStatus::Invalid as i32,
                    "version: {version}, type: {signature_type:?}, critical: {critical}",
                );
            }
        }
    }
}

#[test]
fn unhashed_intended_recipient_noise_does_not_bind_v4_or_v6_data_signatures() {
    let (v4, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed v4 secret key must parse");
    let v6 = generated_v6_data_signer();

    for (version_index, (version, secret)) in [("v4", v4), ("v6", v6)].into_iter().enumerate() {
        for (critical_index, critical) in [false, true].into_iter().enumerate() {
            for (type_index, signature_type) in [SignatureType::Binary, SignatureType::Text]
                .into_iter()
                .enumerate()
            {
                let signature = data_signature_with_intended_recipient(
                    &secret,
                    signature_type,
                    false,
                    critical,
                    0x554e_4841_5348_0000
                        + (version_index as u64) * 0x100
                        + (critical_index as u64) * 0x10
                        + type_index as u64,
                );
                signature
                    .verify(
                        secret.primary_key.public_key(),
                        data_signature_fixture_content(signature_type),
                    )
                    .expect("unhashed-noise fixture must be mathematically valid");

                let result = verification_for_data_signature(&secret, signature);

                assert_eq!(
                    result.status,
                    OpenPgpVerificationStatus::Valid as i32,
                    "version: {version}, type: {signature_type:?}, critical: {critical}",
                );
            }
        }
    }
}

#[test]
fn detached_and_preverified_paths_reject_unknown_critical_hashed_subpackets() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature = detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        (REFERENCE_TIME - 1) as u32,
        [Subpacket::critical(SubpacketData::Experimental(
            100,
            prost::bytes::Bytes::from_static(b"unknown critical data policy"),
        ))
        .expect("unknown critical subpacket")],
    );
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("critical signature remains mathematically valid");

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized_detached_signature(signature.clone());
    let detached = verification(request);
    assert_eq!(detached.status, OpenPgpVerificationStatus::Invalid as i32,);

    let certificates =
        parse_public_certificates_fresh(PUBLIC_KEY).expect("fixed public certificate must parse");
    let preverified = evaluate_preverified_signatures(
        std::slice::from_ref(&signature),
        &certificates,
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
        None,
        |signature_index, _| signature_index == 0,
    )
    .expect("evaluate preverified signature");
    assert_eq!(preverified.status, VerificationStatus::Invalid,);
}

#[test]
fn rsa_data_verification_rejects_1024_bits_and_accepts_2048_bits() {
    const CREATED_AT: u32 = 1_700_000_000;

    for (bits, expected_status) in [
        (1_024, OpenPgpVerificationStatus::Invalid),
        (2_048, OpenPgpVerificationStatus::Valid),
    ] {
        let secret = generate_rsa_certificate_for_test(
            &format!("RSA {bits} <rsa-{bits}@example.test>"),
            Timestamp::from_secs(CREATED_AT),
            bits,
        )
        .expect("generate RSA verification fixture");
        let certificate = secret.to_public_key();
        let public_key = serialized_public_certificate(&certificate);
        let parsed = parse_result_at(&public_key, u64::from(CREATED_AT) + 2);
        let success = match parsed.result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
            result => panic!("RSA-{bits} certificate must remain parseable, got {result:?}"),
        };
        let key = success.keys.first().expect("one RSA certificate");
        assert_eq!(success.keys.len(), 1);
        assert_eq!(key.bit_strength, Some(bits));
        assert_eq!(key.authenticated, bits >= 2_048);

        let signing_subkey = secret
            .secret_subkeys
            .get(signing_subkey_index(&secret))
            .expect("generated certificate carries a signing subkey");
        let signature = detached_signature_signed_by(
            &signing_subkey.key,
            HashAlgorithm::Sha256,
            CREATED_AT + 1,
            std::iter::empty(),
        );
        signature
            .verify(signing_subkey.key.public_key(), DETACHED_BODY)
            .expect("RSA fixture signature must be mathematically valid");

        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature.clone()),
            public_keys: vec![public_key],
            reference_time_epoch_seconds: Some(u64::from(CREATED_AT) + 2),
        });
        assert_eq!(result.status, expected_status as i32, "RSA-{bits}");

        let certificates = vec![certificate];
        let mut visited = 0;
        let preverified = evaluate_preverified_signatures(
            std::slice::from_ref(&signature),
            &certificates,
            DataSignatureVerificationTime::exact(u64::from(CREATED_AT) + 2),
            None,
            |_, _| {
                visited += 1;
                true
            },
        )
        .expect("evaluate preverified RSA signature");
        assert_eq!(
            preverified.status,
            match expected_status {
                OpenPgpVerificationStatus::Valid => VerificationStatus::Valid,
                OpenPgpVerificationStatus::Invalid => VerificationStatus::Invalid,
                OpenPgpVerificationStatus::MissingPublicKey => {
                    VerificationStatus::MissingPublicKey
                }
                OpenPgpVerificationStatus::Unspecified => {
                    panic!("test status must be specified")
                }
            },
            "RSA-{bits}",
        );
        assert_eq!(visited, usize::from(bits >= 2_048), "RSA-{bits}");
    }
}

#[test]
fn dsa_certificate_and_data_signature_remain_parseable_but_cannot_authenticate() {
    let reference_time = reference_time(None);
    let created_at = u32::try_from(reference_time.saturating_sub(60)).expect("test timestamp");
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Dsa(DsaKeySize::B2048))
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(created_at))
        .primary_user_id("Legacy DSA <dsa@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build DSA signer")
        .generate(StdRng::seed_from_u64(0x4453_415f_504f_4c49))
        .expect("generate DSA signer");
    let certificate = secret.to_public_key();
    let public_key = serialized_public_certificate(&certificate);

    let parsed = parse_result_at(&public_key, reference_time);
    let success = match parsed.result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        result => panic!("DSA certificate must remain parseable, got {result:?}"),
    };
    let key = success.keys.first().expect("one DSA certificate");
    assert_eq!(success.keys.len(), 1);
    assert!(!key.authenticated);
    assert!(!key.can_sign);

    let signature = detached_signature_signed_by(
        &secret.primary_key,
        HashAlgorithm::Sha256,
        created_at + 1,
        std::iter::empty(),
    );
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("DSA fixture signature must remain mathematically valid");

    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signature(signature.clone()),
        public_keys: vec![public_key],
        reference_time_epoch_seconds: Some(reference_time),
    });
    assert_eq!(result.status, OpenPgpVerificationStatus::Invalid as i32);

    let certificates = vec![certificate];
    let mut visited = 0;
    let preverified = evaluate_preverified_signatures(
        std::slice::from_ref(&signature),
        &certificates,
        DataSignatureVerificationTime::exact(reference_time),
        None,
        |_, _| {
            visited += 1;
            true
        },
    )
    .expect("evaluate preverified DSA signature");
    assert_eq!(preverified.status, VerificationStatus::Invalid);
    assert_eq!(visited, 0);
}

#[test]
fn legacy_sha1_data_signature_requires_a_pre_cutoff_custody_time() {
    const LEGACY_TIME: u64 = 1_300_000_000;

    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::Rsa as i32,
            user_id: "Weak Data Signature <weak-data@example.test>".to_owned(),
            rsa_bits: 3_072,
            creation_time_epoch_seconds: LEGACY_TIME - 60,
            expiration_seconds: None,
        })
        .expect("generate RSA certificate")
        .as_slice(),
    )
    .expect("decode generated RSA certificate");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated RSA certificate");
    // Sign with the signing subkey: Keyguard's generated primary keys carry
    // certify-only key flags, and a data signature from a component whose
    // effective self-signature withholds the signing bit is refused before
    // its digest is ever computed.
    let signing_subkey = secret
        .secret_subkeys
        .iter()
        .find(|subkey| {
            subkey.signatures.iter().any(|signature| {
                authenticated_key_flags(signature).is_some_and(|flags| flags.sign())
            })
        })
        .expect("generated certificate carries a signing subkey");
    let signature = detached_signature_signed_by(
        &signing_subkey.key,
        HashAlgorithm::Sha1,
        LEGACY_TIME as u32,
        std::iter::empty(),
    );
    signature
        .verify(signing_subkey.key.public_key(), DETACHED_BODY)
        .expect("SHA-1 signature remains mathematically valid");

    let signature = serialized_detached_signature(signature);
    let mut archival_request = detached_request(
        DETACHED_BODY.to_vec(),
        vec![material.public_key_armored.clone()],
    );
    archival_request.signature = signature.clone();
    archival_request.reference_time_epoch_seconds = Some(LEGACY_TIME + 1);
    let archival = verification(archival_request);
    assert_eq!(archival.status, OpenPgpVerificationStatus::Valid as i32);

    let mut current_request = detached_request(
        DETACHED_BODY.to_vec(),
        vec![material.public_key_armored.clone()],
    );
    current_request.signature = signature;
    let current = verification(current_request);
    assert_eq!(current.status, OpenPgpVerificationStatus::Invalid as i32);
    // The caller must be able to say "this signature uses SHA-1" rather
    // than "this signature does not verify".
    assert!(
        current
            .warnings
            .contains(&(OpenPgpVerificationWarning::WeakDigest as i32)),
    );
    assert!(
        !archival
            .warnings
            .contains(&(OpenPgpVerificationWarning::PolicyConflict as i32)),
    );
}

#[test]
fn backdated_data_signature_cannot_select_legacy_certificate_hash_policy() {
    const LEGACY_TIME: u64 = 1_600_000_000;

    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::Rsa as i32,
            user_id: "Backdated Signer <backdated@example.test>".to_owned(),
            rsa_bits: 3_072,
            creation_time_epoch_seconds: LEGACY_TIME - 60,
            expiration_seconds: None,
        })
        .expect("generate historical RSA certificate")
        .as_slice(),
    )
    .expect("decode historical RSA certificate");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse historical RSA certificate");
    let subkey_index = signing_subkey_index(&secret);
    let data_signature = detached_signature_signed_by(
        &secret.secret_subkeys[subkey_index].key,
        HashAlgorithm::Sha256,
        LEGACY_TIME as u32,
        std::iter::empty(),
    );

    for weak_binding in [false, true] {
        let mut candidate = secret.clone();
        let weak_statement = if weak_binding {
            candidate.secret_subkeys[subkey_index].signatures =
                vec![replacement_subkey_binding_with_hash(
                    &candidate,
                    subkey_index,
                    (LEGACY_TIME - 10) as u32,
                    None,
                    true,
                    HashAlgorithm::Sha1,
                )];
            "subkey binding"
        } else {
            let mut flags = KeyFlags::default();
            flags.set_certify(true);
            candidate.details.direct_signatures.clear();
            candidate.details.users[0].signatures = vec![identity_policy_signature_at_with_hash(
                &candidate,
                flags,
                (LEGACY_TIME - 10) as u32,
                HashAlgorithm::Sha1,
            )];
            "User ID self-certification"
        };
        let public_key = serialized_public_certificate(&candidate.to_public_key());

        for (custody_time, expected) in [
            (LEGACY_TIME + 1, OpenPgpVerificationStatus::Valid),
            (REFERENCE_TIME, OpenPgpVerificationStatus::Invalid),
        ] {
            let result = verification(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: DETACHED_BODY.to_vec(),
                signature: serialized_detached_signature(data_signature.clone()),
                public_keys: vec![public_key.clone()],
                reference_time_epoch_seconds: Some(custody_time),
            });

            assert_eq!(
                result.status, expected as i32,
                "weak statement: {weak_statement}, custody time: {custody_time}",
            );
        }
    }
}

#[test]
fn data_signature_from_a_non_signing_primary_is_not_valid() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature = serialized_detached_signature(detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        (REFERENCE_TIME - 1) as u32,
        std::iter::empty(),
    ));

    for (signing_allowed, expected) in [
        (true, OpenPgpVerificationStatus::Valid),
        (false, OpenPgpVerificationStatus::Invalid),
    ] {
        let mut flags = KeyFlags::default();
        flags.set_certify(true);
        flags.set_sign(signing_allowed);
        flags.set_encrypt_comms(!signing_allowed);
        let mut public = secret.to_public_key();
        public.details.users[0].signatures = vec![identity_policy_signature(&secret, flags)];

        let mut request = detached_request(
            DETACHED_BODY.to_vec(),
            vec![serialized_public_certificate(&public)],
        );
        request.signature = signature.clone();
        let result = verification(request);
        // GnuPG: "wrong key usage". The effective self-signature's key flags
        // gate the verification path, not just the signing path.
        assert_eq!(
            result.status, expected as i32,
            "signing bit present: {signing_allowed}",
        );
    }
}

#[test]
fn data_signature_primary_usage_follows_selected_v4_user_id_policy() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature = serialized_detached_signature(detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        (REFERENCE_TIME - 1) as u32,
        std::iter::empty(),
    ));

    for (user_id_allows_signing, direct_allows_signing, expected) in [
        (false, true, OpenPgpVerificationStatus::Invalid),
        (true, false, OpenPgpVerificationStatus::Valid),
    ] {
        let mut user_id_flags = KeyFlags::default();
        user_id_flags.set_certify(true);
        user_id_flags.set_sign(user_id_allows_signing);
        let mut direct_flags = KeyFlags::default();
        direct_flags.set_certify(true);
        direct_flags.set_sign(direct_allows_signing);

        let mut public = secret.to_public_key();
        public.details.users[0].signatures = vec![identity_policy_signature_at(
            &secret,
            user_id_flags,
            (REFERENCE_TIME - 3) as u32,
        )];
        public.details.direct_signatures = vec![direct_policy_signature_at(
            &secret,
            direct_flags,
            false,
            (REFERENCE_TIME - 2) as u32,
        )];

        let mut request = detached_request(
            DETACHED_BODY.to_vec(),
            vec![serialized_public_certificate(&public)],
        );
        request.signature = signature.clone();
        let result = verification(request);
        assert_eq!(
            result.status, expected as i32,
            "User ID signing: {user_id_allows_signing}, Direct Key signing: {direct_allows_signing}",
        );
    }
}

#[test]
fn each_primary_signature_uses_selected_v4_user_id_policy_at_its_creation_time() {
    const USER_ID_POLICY_TIME: u32 = (REFERENCE_TIME - 100) as u32;
    const DIRECT_POLICY_TIME: u32 = (REFERENCE_TIME - 50) as u32;
    const EARLY_SIGNATURE_TIME: u32 = (REFERENCE_TIME - 75) as u32;
    const LATE_SIGNATURE_TIME: u32 = (REFERENCE_TIME - 25) as u32;

    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let early = detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        EARLY_SIGNATURE_TIME,
        std::iter::empty(),
    );
    let late = detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        LATE_SIGNATURE_TIME,
        std::iter::empty(),
    );

    let mut signing_flags = KeyFlags::default();
    signing_flags.set_sign(true);
    let mut certifying_flags = KeyFlags::default();
    certifying_flags.set_certify(true);
    let mut public = secret.to_public_key();
    public.details.users[0].signatures = vec![identity_policy_signature_at(
        &secret,
        signing_flags,
        USER_ID_POLICY_TIME,
    )];
    public.details.direct_signatures = vec![direct_policy_signature_at(
        &secret,
        certifying_flags,
        false,
        DIRECT_POLICY_TIME,
    )];

    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signatures([early, late]),
        public_keys: vec![serialized_public_certificate(&public)],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(
        result
            .signatures
            .iter()
            .map(|signature| signature.status)
            .collect::<Vec<_>>(),
        [
            OpenPgpVerificationStatus::Valid as i32,
            OpenPgpVerificationStatus::Valid as i32,
        ],
    );
}

#[test]
fn distinct_historical_cache_misses_cannot_reset_certificate_work() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signatures = [
        detached_signature_with_policy_subpackets(
            &secret,
            HashAlgorithm::Sha256,
            (REFERENCE_TIME - 2) as u32,
            std::iter::empty(),
        ),
        detached_signature_with_policy_subpackets(
            &secret,
            HashAlgorithm::Sha256,
            (REFERENCE_TIME - 1) as u32,
            std::iter::empty(),
        ),
    ];
    let certificates = [secret.to_public_key()];
    let candidates = all_components(&certificates);
    let mut validated = [CertificateValidationCache::new()];
    let mut budget = OpenPgpReadBudget::default();

    assert!(matches!(
        resolve_signer(
            &signatures[0],
            &certificates,
            &candidates,
            &mut validated,
            DataSignatureVerificationTime::exact(REFERENCE_TIME),
            &mut budget,
        )
        .expect("resolve first historical view"),
        SignerResolution::Selected { .. },
    ));
    while budget.policy_mut().charge_public_key_verification().is_ok() {}

    assert!(matches!(
        resolve_signer(
            &signatures[1],
            &certificates,
            &candidates,
            &mut validated,
            DataSignatureVerificationTime::exact(REFERENCE_TIME),
            &mut budget,
        )
        .expect("reject exhausted second historical view"),
        SignerResolution::Rejected { .. },
    ));
    assert_eq!(validated[0].len(), 2, "both distinct times are cached");
    assert!(matches!(
        validated[0].get(&((REFERENCE_TIME - 1), REFERENCE_TIME)),
        Some(CachedCertificateValidation::RejectedByResourceLimit),
    ));
}

#[test]
fn historical_subkey_signature_survives_later_binding_expiration() {
    const KEY_CREATION_TIME: u64 = 1_700_000_000;
    const BINDING_CREATION_TIME: u32 = 1_700_000_100;
    const BINDING_LIFETIME: u32 = 60;
    const SIGNATURE_CREATION_TIME: u32 = BINDING_CREATION_TIME + 20;
    const VERIFICATION_TIME: u64 = 1_700_000_180;

    let mut secret = historical_signing_certificate(KEY_CREATION_TIME);
    let subkey_index = signing_subkey_index(&secret);
    let signature = detached_signature_signed_by(
        &secret.secret_subkeys[subkey_index].key,
        HashAlgorithm::Sha256,
        SIGNATURE_CREATION_TIME,
        std::iter::empty(),
    );
    let binding = replacement_subkey_binding(
        &secret,
        subkey_index,
        BINDING_CREATION_TIME,
        Some(BINDING_LIFETIME),
        true,
    );
    secret.secret_subkeys[subkey_index].signatures = vec![binding];

    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signature(signature),
        public_keys: vec![serialized_public_certificate(&secret.to_public_key())],
        reference_time_epoch_seconds: Some(VERIFICATION_TIME),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert!(
        !result
            .warnings
            .contains(&(OpenPgpVerificationWarning::KeyExpired as i32)),
    );
}

#[test]
fn each_signature_uses_the_subkey_policy_at_its_creation_time() {
    const KEY_CREATION_TIME: u64 = 1_700_000_000;
    const REPLACEMENT_TIME: u32 = 1_700_000_100;
    const EARLY_SIGNATURE_TIME: u32 = REPLACEMENT_TIME - 20;
    const LATE_SIGNATURE_TIME: u32 = REPLACEMENT_TIME + 20;
    const VERIFICATION_TIME: u64 = 1_700_000_140;

    let mut secret = historical_signing_certificate(KEY_CREATION_TIME);
    let subkey_index = signing_subkey_index(&secret);
    let signing_subkey = &secret.secret_subkeys[subkey_index].key;
    let early = detached_signature_signed_by(
        signing_subkey,
        HashAlgorithm::Sha256,
        EARLY_SIGNATURE_TIME,
        std::iter::empty(),
    );
    let late = detached_signature_signed_by(
        signing_subkey,
        HashAlgorithm::Sha256,
        LATE_SIGNATURE_TIME,
        std::iter::empty(),
    );
    let old_binding = secret.secret_subkeys[subkey_index].signatures[0].clone();
    let non_signing_binding =
        replacement_subkey_binding(&secret, subkey_index, REPLACEMENT_TIME, None, false);
    secret.secret_subkeys[subkey_index].signatures = vec![old_binding, non_signing_binding];

    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signatures([early, late]),
        public_keys: vec![serialized_public_certificate(&secret.to_public_key())],
        reference_time_epoch_seconds: Some(VERIFICATION_TIME),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(
        result
            .signatures
            .iter()
            .map(|signature| signature.status)
            .collect::<Vec<_>>(),
        [
            OpenPgpVerificationStatus::Valid as i32,
            OpenPgpVerificationStatus::Invalid as i32,
        ],
    );
}

#[test]
fn current_time_verification_tolerates_only_bounded_future_signatures() {
    let started_at = reference_time(None);
    let secret = historical_signing_certificate(started_at.saturating_sub(60));
    let subkey_index = signing_subkey_index(&secret);
    let signing_subkey = &secret.secret_subkeys[subkey_index].key;
    let public_key = serialized_public_certificate(&secret.to_public_key());
    let small_skew_time = started_at
        .checked_add(DATA_SIGNATURE_CLOCK_SKEW_TOLERANCE_SECONDS / 2)
        .and_then(|time| u32::try_from(time).ok())
        .expect("small-skew OpenPGP timestamp");
    let arbitrary_future_time = started_at
        .checked_add(24 * 60 * 60)
        .and_then(|time| u32::try_from(time).ok())
        .expect("future OpenPGP timestamp");

    for (creation_time, expected) in [
        (small_skew_time, OpenPgpVerificationStatus::Valid),
        (arbitrary_future_time, OpenPgpVerificationStatus::Invalid),
    ] {
        let signature = detached_signature_signed_by(
            signing_subkey,
            HashAlgorithm::Sha256,
            creation_time,
            std::iter::empty(),
        );
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature),
            public_keys: vec![public_key.clone()],
            // An absent time means "now" and receives the bounded skew
            // allowance. Explicit historical checks below remain exact.
            reference_time_epoch_seconds: None,
        });

        assert_eq!(
            result.status, expected as i32,
            "creation time {creation_time}"
        );
    }
}

#[test]
fn explicit_historical_reference_time_does_not_tolerate_future_creation() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature = detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        (REFERENCE_TIME + 1) as u32,
        std::iter::empty(),
    );
    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::Detached as i32,
        content: DETACHED_BODY.to_vec(),
        signature: serialized_detached_signature(signature),
        public_keys: vec![PUBLIC_KEY.to_vec()],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    });

    assert_eq!(result.status, OpenPgpVerificationStatus::Invalid as i32);
}

#[test]
fn future_data_signature_cannot_activate_a_future_signing_binding() {
    let started_at = reference_time(None);
    let mut secret = historical_signing_certificate(started_at.saturating_sub(60));
    let subkey_index = signing_subkey_index(&secret);
    let future_signature_time = started_at
        .checked_add(DATA_SIGNATURE_CLOCK_SKEW_TOLERANCE_SECONDS / 2)
        .and_then(|time| u32::try_from(time).ok())
        .expect("future OpenPGP timestamp within the skew allowance");
    let signature = detached_signature_signed_by(
        &secret.secret_subkeys[subkey_index].key,
        HashAlgorithm::Sha256,
        future_signature_time,
        std::iter::empty(),
    );
    let inactive_binding = replacement_subkey_binding(
        &secret,
        subkey_index,
        u32::try_from(started_at.saturating_sub(1)).expect("current OpenPGP timestamp"),
        None,
        false,
    );
    let future_signing_binding =
        replacement_subkey_binding(&secret, subkey_index, future_signature_time - 1, None, true);
    secret.secret_subkeys[subkey_index].signatures = vec![inactive_binding, future_signing_binding];
    let public_key = serialized_public_certificate(&secret.to_public_key());
    let verify_at = |reference_time_epoch_seconds| {
        verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature.clone()),
            public_keys: vec![public_key.clone()],
            reference_time_epoch_seconds,
        })
    };

    // The data signature itself is within the live-signature skew allowance,
    // but that allowance must not advance certificate policy to the future
    // binding.
    let before_binding = verify_at(None);
    assert_eq!(
        before_binding.status,
        OpenPgpVerificationStatus::Invalid as i32,
    );

    // Once the claimed time actually arrives, the same signature and binding
    // form a legitimate historical view. This guards the creation-time
    // authorization semantics while rejecting the premature activation.
    let after_binding = verify_at(Some(u64::from(future_signature_time) + 1));
    assert_eq!(
        after_binding.status,
        OpenPgpVerificationStatus::Valid as i32,
    );
}

#[test]
fn expired_data_signature_is_not_reported_as_valid() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let created = (REFERENCE_TIME - 1_000) as u32;
    for (lifetime, expected) in [
        (10u32, OpenPgpVerificationStatus::Invalid),
        (100_000u32, OpenPgpVerificationStatus::Valid),
    ] {
        let signature = serialized_detached_signature(detached_signature_with_policy_subpackets(
            &secret,
            HashAlgorithm::Sha256,
            created,
            [Subpacket::regular(SubpacketData::SignatureExpirationTime(
                pgp::types::Duration::from_secs(lifetime),
            ))
            .expect("signature expiration subpacket")],
        ));
        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = signature;
        let result = verification(request);
        // RFC 9580 §5.2.3.18: an expired signature is no longer a valid
        // statement, so it must not share a status with a live one. The
        // wire keeps the existing SIGNATURE_EXPIRED warning to tell it
        // apart from an ordinary bad signature: the status enum is a
        // closed enum on the current clients, so a new value there would
        // break decoding rather than inform them.
        assert_eq!(result.status, expected as i32, "lifetime: {lifetime}");
        assert_eq!(
            result
                .warnings
                .contains(&(OpenPgpVerificationWarning::SignatureExpired as i32)),
            expected == OpenPgpVerificationStatus::Invalid,
            "lifetime: {lifetime}",
        );
    }
}

#[test]
fn expired_backdated_signature_keeps_compatibility_metadata_and_warning() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let signature = serialized_detached_signature(detached_signature_with_policy_subpackets(
        &secret,
        HashAlgorithm::Sha256,
        1_612_323_906,
        [Subpacket::regular(SubpacketData::SignatureExpirationTime(
            pgp::types::Duration::from_secs(60),
        ))
        .expect("signature expiration subpacket")],
    ));
    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = signature;
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Invalid as i32);
    assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
    assert_eq!(result.user_ids, [USER_ID]);
    assert_eq!(
        result.warnings,
        [OpenPgpVerificationWarning::SignatureExpired as i32],
    );
    assert!(result.signatures.is_empty());
}

#[test]
fn unsupported_certificates_are_skipped_and_counted_in_the_parse_result() {
    // Old-format Public-Key packet with a structurally valid v3 RSA body.
    let v3_public_key = [
        0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
    ];
    let supported = decode_openpgp_packets(PUBLIC_KEY).expect("fixture must dearmor");
    let mut document = v3_public_key.to_vec();
    document.extend_from_slice(&supported);

    let success = match parse_result(&document).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("one unsupported certificate must not fail the document: {other:?}"),
    };
    assert_eq!(success.keys.len(), 1);
    assert_eq!(success.keys[0].fingerprint, PRIMARY_FINGERPRINT);
    assert_eq!(success.skipped_certificates, 1);
}

#[test]
fn public_keyring_import_discards_tainted_certificate_and_recovers_the_next_one() {
    // New-format Literal Data packet containing "note".
    let literal = [0xcb, 0x04, b'n', b'o', b't', b'e'];
    // New-format unknown critical packet, tag 22 with an empty body.
    let unknown_critical = [0xd6, 0x00];
    for (case, separator) in [
        ("framed Literal Data", literal.as_slice()),
        ("unknown critical", unknown_critical.as_slice()),
        ("raw junk", b"raw keyring junk".as_slice()),
    ] {
        let document = damaged_revoked_certificate_keyring(separator);

        let success = match parse_result(&document).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
            other => panic!("later certificate must survive {case}: {other:?}"),
        };
        assert_eq!(success.keys.len(), 1, "{case}");
        assert_eq!(success.keys[0].fingerprint, PRIMARY_FINGERPRINT, "{case}");
        assert_eq!(success.skipped_certificates, 1, "{case}");
    }
}

#[test]
fn public_keyring_import_accepts_concatenated_armored_certificates() {
    let mut document = PUBLIC_KEY.to_vec();
    document.extend_from_slice(b"\nforwarded separately\n");
    document.extend_from_slice(PUBLIC_KEY);

    let success = match parse_result(&document).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("concatenated armor blocks must parse: {other:?}"),
    };
    assert_eq!(success.keys.len(), 2);
    assert_eq!(success.skipped_certificates, 0);
}

#[test]
fn malformed_keyring_entry_is_skipped_when_a_later_certificate_is_valid() {
    let malformed_primary = [0xc6, 0x01, 0x04];
    let supported = decode_openpgp_packets(PUBLIC_KEY).expect("fixture must dearmor");
    let mut document = malformed_primary.to_vec();
    document.extend_from_slice(&supported);

    let success = match parse_result(&document).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("later valid certificate must survive: {other:?}"),
    };
    assert_eq!(success.keys.len(), 1);
    assert_eq!(success.keys[0].fingerprint, PRIMARY_FINGERPRINT);
    assert_eq!(success.skipped_certificates, 1);
}

#[test]
fn public_keyring_import_skips_identity_after_subkey_instead_of_normalizing_it() {
    let mut document = identity_after_subkey_certificate();
    document.extend_from_slice(&decode_openpgp_packets(PUBLIC_KEY).expect("fixture must dearmor"));

    let success = match parse_result(&document).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("later valid certificate must survive malformed order: {other:?}"),
    };

    assert_eq!(success.keys.len(), 1);
    assert_eq!(success.keys[0].fingerprint, PRIMARY_FINGERPRINT);
    assert_eq!(success.skipped_certificates, 1);
}

#[test]
fn unhashed_foreign_issuer_fingerprint_cannot_veto_valid_signature() {
    let foreign = parse_public_certificates_fresh(DESIGNATED_REVOKER_PUBLIC_KEY)
        .expect("foreign certificate must parse")
        .remove(0);
    let mut signature = parse_detached_signatures_fresh(DETACHED_SIGNATURE)
        .expect("fixed detached signature must parse")
        .remove(0);
    signature
        .unhashed_subpacket_push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                foreign.primary_key.fingerprint(),
            ))
            .expect("foreign issuer fingerprint"),
        )
        .expect("append unhashed issuer fingerprint");

    let mut request = detached_request(
        DETACHED_BODY.to_vec(),
        vec![PUBLIC_KEY.to_vec(), serialized_public_certificate(&foreign)],
    );
    request.signature = serialized_detached_signature(signature);
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
    assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
    assert_eq!(
        result.primary_fingerprint.as_deref(),
        Some(PRIMARY_FINGERPRINT),
    );
    assert_eq!(result.user_ids, [USER_ID]);
}

#[test]
fn wrong_version_unhashed_issuer_fingerprint_rejects_valid_signature() {
    let mut signature = parse_detached_signatures_fresh(DETACHED_SIGNATURE)
        .expect("fixed detached signature must parse")
        .remove(0);
    signature
        .unhashed_subpacket_push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
                [0x66; 32],
            )))
            .expect("wrong-version issuer fingerprint"),
        )
        .expect("append wrong-version issuer fingerprint");

    let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    request.signature = serialized_detached_signature(signature);
    let result = verification(request);

    assert_eq!(result.status, OpenPgpVerificationStatus::Invalid as i32);
    assert_eq!(result.fingerprint, None);
    assert_eq!(result.primary_fingerprint, None);
    assert!(result.user_ids.is_empty());
}

#[test]
fn multiple_hashed_issuer_fingerprints_are_resolved_cryptographically() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let foreign = parse_public_certificates_fresh(DESIGNATED_REVOKER_PUBLIC_KEY)
        .expect("foreign certificate must parse")
        .remove(0);
    let signature = DetachedSignature::sign_binary_data_with_subpackets(
        StdRng::seed_from_u64(0x4953_5355_4552_4650),
        &secret.primary_key,
        &Password::empty(),
        HashAlgorithm::Sha256,
        DETACHED_BODY,
        SubpacketConfig::UserDefined {
            hashed: vec![
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    (REFERENCE_TIME - 1) as u32,
                )))
                .expect("signature creation time"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    secret.primary_key.fingerprint(),
                ))
                .expect("real issuer fingerprint"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    foreign.primary_key.fingerprint(),
                ))
                .expect("foreign issuer fingerprint"),
            ],
            unhashed: vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(
                    secret.primary_key.legacy_key_id(),
                ))
                .expect("real issuer key ID"),
            ],
        },
    )
    .expect("sign detached data with ambiguous authenticated hints");
    signature
        .verify(secret.primary_key.public_key(), DETACHED_BODY)
        .expect("signature must be cryptographically valid for the real key");
    let signature = serialized_detached_signature(signature.signature);
    for public_keys in [
        vec![PUBLIC_KEY.to_vec()],
        vec![PUBLIC_KEY.to_vec(), serialized_public_certificate(&foreign)],
    ] {
        let mut request = detached_request(DETACHED_BODY.to_vec(), public_keys);
        request.signature = signature.clone();
        let result = verification(request);

        assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
        assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
        assert_eq!(
            result.primary_fingerprint.as_deref(),
            Some(PRIMARY_FINGERPRINT),
        );
        assert_eq!(result.user_ids, [USER_ID]);
    }
}

#[test]
fn verification_merges_hard_revocation_and_preserves_math_status_in_either_order() {
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("fixed secret key must parse");
    let current = secret.to_public_key();
    let primary = &secret.primary_key;
    let mut revocation = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        primary.algorithm(),
        HashAlgorithm::Sha256,
    );
    revocation.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            REFERENCE_TIME as u32,
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
    revocation
        .verify_key(primary.public_key())
        .expect("verify primary revocation");

    let mut revoked = current.clone();
    revoked.details.revocation_signatures.push(revocation);
    let current = serialized_public_certificate(&current);
    let revoked = serialized_public_certificate(&revoked);

    for public_keys in [
        vec![current.clone(), revoked.clone()],
        vec![revoked.clone(), current.clone()],
    ] {
        let result = verification(detached_request(
            DETACHED_BODY.to_vec(),
            public_keys.clone(),
        ));
        // An unspecified key revocation is retrospective. The mathematical
        // signature still verifies, while the separate warning prevents a
        // caller from treating the revoked authority as policy-acceptable.
        assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
        assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
        assert_eq!(result.user_ids, [USER_ID]);
        assert_eq!(
            result.warnings,
            [OpenPgpVerificationWarning::KeyRevoked as i32],
        );

        let invalid = verification(detached_request(b"tampered".to_vec(), public_keys));
        assert_eq!(invalid.status, OpenPgpVerificationStatus::Invalid as i32);
        assert_eq!(
            invalid.warnings,
            [OpenPgpVerificationWarning::KeyRevoked as i32],
        );
    }
}

#[test]
fn signer_revocation_scope_and_creation_time_control_verification_status() {
    const KEY_CREATION_TIME: u64 = 1_700_000_000;
    const EARLY_SIGNATURE_TIME: u32 = 1_700_000_080;
    const REVOCATION_TIME: u32 = 1_700_000_100;
    const LATE_SIGNATURE_TIME: u32 = 1_700_000_120;
    const BEFORE_REVOCATION_VIEW: u64 = 1_700_000_090;
    const LATE_VIEW: u64 = 1_700_000_180;

    let secret = historical_signing_certificate(KEY_CREATION_TIME);
    let signing_subkey = &secret.secret_subkeys[signing_subkey_index(&secret)].key;
    let early_signature = detached_signature_signed_by(
        signing_subkey,
        HashAlgorithm::Sha256,
        EARLY_SIGNATURE_TIME,
        std::iter::empty(),
    );
    let late_signature = detached_signature_signed_by(
        signing_subkey,
        HashAlgorithm::Sha256,
        LATE_SIGNATURE_TIME,
        std::iter::empty(),
    );

    for (case, reason, signature, verification_time, expected, warnings) in [
        (
            "retired after the signature",
            RevocationCode::KeyRetired,
            &early_signature,
            LATE_VIEW,
            OpenPgpVerificationStatus::Valid,
            Vec::new(),
        ),
        (
            "signed after retirement",
            RevocationCode::KeyRetired,
            &late_signature,
            LATE_VIEW,
            OpenPgpVerificationStatus::Valid,
            vec![OpenPgpVerificationWarning::KeyRevoked as i32],
        ),
        (
            "compromise learned after the historical view",
            RevocationCode::KeyCompromised,
            &early_signature,
            BEFORE_REVOCATION_VIEW,
            OpenPgpVerificationStatus::Valid,
            vec![OpenPgpVerificationWarning::KeyRevoked as i32],
        ),
        (
            "no reason learned after the historical view",
            RevocationCode::NoReason,
            &early_signature,
            BEFORE_REVOCATION_VIEW,
            OpenPgpVerificationStatus::Valid,
            vec![OpenPgpVerificationWarning::KeyRevoked as i32],
        ),
    ] {
        signature
            .verify(signing_subkey.public_key(), DETACHED_BODY)
            .expect("fixture signature is mathematically valid");
        let mut public = secret.to_public_key();
        public
            .details
            .revocation_signatures
            .push(primary_key_revocation(
                &secret,
                REVOCATION_TIME,
                Some(reason),
            ));
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature.clone()),
            public_keys: vec![serialized_public_certificate(&public)],
            reference_time_epoch_seconds: Some(verification_time),
        });

        assert_eq!(result.status, expected as i32, "{case}");
        assert_eq!(result.warnings, warnings, "{case}");
        assert!(result.fingerprint.is_some(), "{case}");
        assert_eq!(
            result.user_ids,
            ["Historical Signer <historical@example.test>"],
            "{case}",
        );
    }
}

#[test]
fn restored_keys_preserve_historical_revocation_intervals() {
    let secret = historical_signing_certificate(RENEWAL_TEST_CREATION_TIME);
    let signing_index = signing_subkey_index(&secret);
    let signing_subkey = &secret.secret_subkeys[signing_index].key;
    for revoke_subkey in [false, true] {
        let mut public = secret.to_public_key();
        let mut config = SignatureConfig::v4(
            if revoke_subkey {
                SignatureType::SubkeyRevocation
            } else {
                SignatureType::KeyRevocation
            },
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                1_700_000_100,
            )))
            .expect("retirement time"),
            Subpacket::regular(SubpacketData::RevocationReason(
                RevocationCode::KeyRetired,
                Vec::new().into(),
            ))
            .expect("retirement reason"),
        ];
        let revocation = if revoke_subkey {
            config.sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                signing_subkey.public_key(),
            )
        } else {
            config.sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
        }
        .expect("retire signing authority");
        let mut config = if revoke_subkey {
            secret.secret_subkeys[signing_index]
                .signatures
                .iter()
                .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
                .and_then(Signature::config)
                .cloned()
                .expect("binding config")
        } else {
            SignatureConfig::v4(
                SignatureType::Key,
                secret.primary_key.algorithm(),
                HashAlgorithm::Sha256,
            )
        };
        config.hashed_subpackets.retain(|packet| {
            !matches!(
                packet.data,
                SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_)
            )
        });
        config.hashed_subpackets.extend([
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                1_700_000_140,
            )))
            .expect("restoration time"),
            Subpacket::regular(SubpacketData::SignatureExpirationTime(
                pgp::types::Duration::from_secs(30),
            ))
            .expect("restoration expiration"),
        ]);
        let restoring = if revoke_subkey {
            config.sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                signing_subkey.public_key(),
            )
        } else {
            config.sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
        }
        .expect("restore signing authority");
        if revoke_subkey {
            public.public_subkeys[signing_index]
                .signatures
                .extend([revocation, restoring]);
        } else {
            public.details.revocation_signatures.push(revocation);
            public.details.direct_signatures.push(restoring);
        }
        for (signature_time, revoked) in [
            (1_700_000_080, false),
            (1_700_000_120, true),
            (1_700_000_160, false),
            (1_700_000_180, true),
        ] {
            let signature = detached_signature_signed_by(
                signing_subkey,
                HashAlgorithm::Sha256,
                signature_time,
                std::iter::empty(),
            );
            let request = OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: DETACHED_BODY.to_vec(),
                signature: serialized_detached_signature(signature),
                public_keys: vec![serialized_public_certificate(&public)],
                reference_time_epoch_seconds: Some(1_700_000_200),
            };
            let result = verification(request.clone());
            assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
            assert_eq!(
                result
                    .warnings
                    .contains(&(OpenPgpVerificationWarning::KeyRevoked as i32)),
                revoked,
                "subkey {revoke_subkey}, signed at {signature_time}"
            );
            assert_eq!(streamed_detached_verification(&request), result);
            let resolution = OpenPgpMetadataResolveResult::decode(
                resolve_metadata(OpenPgpMetadataResolveRequest {
                    private_key_data: None,
                    public_key_data: Some(serialized_public_certificate(&public)),
                    normalized_fingerprint: String::new(),
                    candidate_revocation_keys: Vec::new(),
                    reference_time_epoch_seconds: Some(u64::from(signature_time)),
                })
                .expect("resolve restoration metadata")
                .as_slice(),
            )
            .expect("decode metadata")
            .resolution
            .expect("restoration metadata");
            assert_eq!(resolution.policy_revision, 2);
            let fingerprint = if revoke_subkey {
                fingerprint_hex(signing_subkey.public_key())
            } else {
                fingerprint_hex(secret.primary_key.public_key())
            };
            let component = resolution.certificates[0]
                .policy
                .iter()
                .find(|component| component.fingerprint == fingerprint)
                .expect("restored component metadata");
            assert_eq!(
                component.revocation_status,
                if revoked {
                    OpenPgpRevocationStatus::Revoked
                } else {
                    OpenPgpRevocationStatus::NotRevoked
                } as i32
            );
        }
    }
}

#[test]
fn signer_expiration_preserves_math_status_with_warning_across_verification_paths() {
    const KEY_CREATION_TIME: u64 = 1_700_000_000;
    const KEY_LIFETIME: u32 = 60;
    const LIVE_SIGNATURE_TIME: u32 = 1_700_000_020;
    const EXPIRED_SIGNATURE_TIME: u32 = 1_700_000_120;
    const VERIFICATION_TIME: u64 = 1_700_000_180;

    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "Expired Signer <expired@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: KEY_CREATION_TIME,
            expiration_seconds: Some(KEY_LIFETIME),
        })
        .expect("generate expiring signing certificate")
        .as_slice(),
    )
    .expect("decode expiring signing certificate");
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse expiring signing certificate");
    let signing_subkey = &secret.secret_subkeys[signing_subkey_index(&secret)].key;
    let certificates = parse_public_certificates_fresh(&material.public_key_armored)
        .expect("parse expiring public certificate");

    for (
        signature_time,
        expected_status,
        expected_warnings,
        expected_domain_status,
        expected_domain_warnings,
    ) in [
        (
            LIVE_SIGNATURE_TIME,
            OpenPgpVerificationStatus::Valid,
            Vec::new(),
            VerificationStatus::Valid,
            Vec::new(),
        ),
        (
            EXPIRED_SIGNATURE_TIME,
            OpenPgpVerificationStatus::Valid,
            vec![OpenPgpVerificationWarning::KeyExpired as i32],
            VerificationStatus::Valid,
            vec![VerificationWarning::KeyExpired],
        ),
    ] {
        let signature = detached_signature_signed_by(
            signing_subkey,
            HashAlgorithm::Sha256,
            signature_time,
            std::iter::empty(),
        );
        signature
            .verify(signing_subkey.public_key(), DETACHED_BODY)
            .expect("fixture signature is mathematically valid");
        let request = OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content: DETACHED_BODY.to_vec(),
            signature: serialized_detached_signature(signature.clone()),
            public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(VERIFICATION_TIME),
        };

        let one_shot = verification(request.clone());
        assert_eq!(one_shot.status, expected_status as i32,);
        assert_eq!(one_shot.warnings, expected_warnings);
        assert_eq!(one_shot.user_ids, ["Expired Signer <expired@example.test>"],);
        assert_eq!(streamed_detached_verification(&request), one_shot);

        // Inline signed-message verification reaches this same path after its
        // packet reader has already checked the checksum. Keep exercising it
        // directly so buffered and preverified callers cannot diverge.
        let mut visited = false;
        let preverified = evaluate_preverified_signatures(
            std::slice::from_ref(&signature),
            &certificates,
            DataSignatureVerificationTime::exact(VERIFICATION_TIME),
            None,
            |_, _| {
                visited = true;
                true
            },
        )
        .expect("evaluate inline/preverified signature");
        assert!(
            visited,
            "authority policy must not skip the checksum result"
        );
        assert_eq!(preverified.status, expected_domain_status);
        assert_eq!(preverified.warnings, expected_domain_warnings);
    }
}

#[test]
fn crc24_footer_state_does_not_block_certificate_or_signature_reads() {
    for (label, certificate) in crc24_armor_cases(PUBLIC_KEY) {
        let success = match parse_result(&certificate).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
            other => panic!("{label} certificate CRC24 must be ignored: {other:?}"),
        };
        assert_eq!(success.keys.len(), 1, "{label}");
        assert_eq!(success.keys[0].fingerprint, PRIMARY_FINGERPRINT, "{label}");
    }

    for (label, signature) in crc24_armor_cases(DETACHED_SIGNATURE) {
        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = signature;
        let result = verification(request);
        assert_eq!(
            result.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} detached-signature CRC24",
        );
    }

    for (label, clear_signed) in crc24_armor_cases(CLEAR_SIGNED) {
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: clear_signed,
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_eq!(
            result.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{label} clear-signature CRC24",
        );
    }
}

#[test]
fn detached_signature_crc24_tolerance_keeps_payload_and_armor_type_strict() {
    let mut malformed_payload = DETACHED_SIGNATURE.to_vec();
    let payload_start =
        find_subslice(&malformed_payload, b"\n\n").expect("signature armor header separator") + 2;
    malformed_payload[payload_start] = b'!';
    let mut malformed_request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    malformed_request.signature = malformed_payload;
    assert_eq!(
        verify_request(malformed_request),
        Err(OpenPgpReadError::InvalidArgument),
    );

    let wrong_kind = std::str::from_utf8(DETACHED_SIGNATURE)
        .expect("signature fixture is UTF-8")
        .replace("PGP SIGNATURE", "PGP PUBLIC KEY BLOCK")
        .into_bytes();
    let mut wrong_kind_request =
        detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
    wrong_kind_request.signature = wrong_kind;
    assert_eq!(
        verify_request(wrong_kind_request),
        Err(OpenPgpReadError::InvalidArgument),
    );
}

#[test]
fn fixed_gnupg_clear_signature_verifies_lf_and_crlf_canonical_forms() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let crlf = fixture.replace('\n', "\r\n").into_bytes();
    let trailing_whitespace = fixture
        .replace("OpenPGP clear text\n", "OpenPGP clear text \t\n")
        .into_bytes();
    let preamble = format!("Quoted introduction text.\n\n{fixture}").into_bytes();
    for content in [CLEAR_SIGNED.to_vec(), crlf, trailing_whitespace, preamble] {
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content,
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_verification(&result, OpenPgpVerificationStatus::Valid, 1_784_073_600);
    }

    // The signature marker must stand on its own line; a marker glued to
    // the final body line is rejected as malformed framing.
    let glued_signature_marker = fixture
        .replace(
            "final line\n-----BEGIN PGP SIGNATURE-----",
            "final line-----BEGIN PGP SIGNATURE-----",
        )
        .into_bytes();
    assert_eq!(
        verify_request(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: glued_signature_marker,
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        })
        .err(),
        Some(OpenPgpReadError::InvalidArgument),
    );

    let mut changed = CLEAR_SIGNED.to_vec();
    let offset = changed
        .windows(b"clear".len())
        .position(|window| window == b"clear")
        .expect("fixture body marker");
    changed[offset] ^= 1;
    let result = verification(OpenPgpVerifyRequest {
        kind: OpenPgpVerifyKind::ClearText as i32,
        content: changed,
        signature: Vec::new(),
        public_keys: vec![PUBLIC_KEY.to_vec()],
        reference_time_epoch_seconds: Some(REFERENCE_TIME),
    });
    assert_verification(&result, OpenPgpVerificationStatus::Invalid, 1_784_073_600);
}

#[test]
fn malformed_verification_key_candidate_fails_but_legacy_candidate_is_skipped() {
    let malformed = detached_request(
        DETACHED_BODY.to_vec(),
        vec![PUBLIC_KEY.to_vec(), b"malformed".to_vec()],
    );
    assert_eq!(
        verify_request(malformed),
        Err(OpenPgpReadError::InvalidArgument),
    );

    let v3_public_key = vec![
        0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
    ];
    let valid = verification(detached_request(
        DETACHED_BODY.to_vec(),
        vec![v3_public_key, PUBLIC_KEY.to_vec()],
    ));
    assert_eq!(valid.status, OpenPgpVerificationStatus::Valid as i32);
}

#[test]
fn public_key_document_and_certificate_shape_limits_are_inclusive() {
    let boundary_documents = vec![PUBLIC_KEY.to_vec(); MAX_PUBLIC_KEY_DOCUMENTS];
    assert_eq!(
        parse_public_key_documents(&boundary_documents, &mut OpenPgpReadBudget::default(),)
            .expect("document-count boundary must parse")
            .len(),
        1,
    );
    let over_limit_documents = vec![PUBLIC_KEY.to_vec(); MAX_PUBLIC_KEY_DOCUMENTS + 1];
    assert_eq!(
        parse_public_key_documents(&over_limit_documents, &mut OpenPgpReadBudget::default(),),
        Err(OpenPgpReadError::ResourceLimit),
    );

    // Per-certificate shape bounds are charged from the packet set's own
    // counts, which stay complete even when the composed parser drops
    // evidence it rejects.
    assert_eq!(
        charge_certificate_shape(
            MAX_COMPONENTS_PER_CERTIFICATE,
            1,
            1,
            &mut OpenPgpReadBudget::default(),
        ),
        Ok(()),
    );
    assert_eq!(
        charge_certificate_shape(
            MAX_COMPONENTS_PER_CERTIFICATE + 1,
            1,
            1,
            &mut OpenPgpReadBudget::default(),
        ),
        Err(ParseFailure::ResourceLimit),
    );
}

#[test]
fn identity_and_signature_limits_are_inclusive() {
    assert_eq!(
        charge_certificate_shape(
            1,
            MAX_IDENTITIES_PER_CERTIFICATE,
            1,
            &mut OpenPgpReadBudget::default(),
        ),
        Ok(()),
    );
    assert_eq!(
        charge_certificate_shape(
            1,
            MAX_IDENTITIES_PER_CERTIFICATE + 1,
            1,
            &mut OpenPgpReadBudget::default(),
        ),
        Err(ParseFailure::ResourceLimit),
    );

    // Per-object signature quotas belong to the merge, the only stage that
    // sees every retained packet; see
    // `per_object_signature_quota_is_inclusive` in
    // `openpgp_certificate_merge`.
}

#[test]
fn aggregate_shape_limits_are_inclusive_across_individually_valid_objects() {
    let mut budget = OpenPgpReadBudget::default();
    for _ in 0..8 {
        assert_eq!(
            charge_certificate_shape(MAX_COMPONENTS_PER_CERTIFICATE, 1, 1, &mut budget),
            Ok(()),
        );
    }
    assert_eq!(budget.components, MAX_COMPONENTS_PER_REQUEST);
    assert_eq!(
        charge_certificate_shape(MAX_COMPONENTS_PER_CERTIFICATE, 1, 1, &mut budget),
        Err(ParseFailure::ResourceLimit),
    );

    let mut budget = OpenPgpReadBudget::default();
    for _ in 0..4 {
        assert_eq!(
            charge_certificate_shape(1, MAX_IDENTITIES_PER_CERTIFICATE, 1, &mut budget),
            Ok(()),
        );
    }
    assert_eq!(budget.identities, MAX_IDENTITIES_PER_REQUEST);
    assert_eq!(
        charge_certificate_shape(1, MAX_IDENTITIES_PER_CERTIFICATE, 1, &mut budget),
        Err(ParseFailure::ResourceLimit),
    );
}

#[test]
fn aggregate_signature_limit_blocks_per_object_multiplier() {
    let mut budget = OpenPgpReadBudget::default();
    for _ in 0..16 {
        assert_eq!(charge_certificate_shape(1, 16, 256, &mut budget), Ok(()),);
    }
    assert_eq!(budget.signatures, MAX_SIGNATURES_PER_REQUEST);
    assert_eq!(
        charge_certificate_shape(1, 16, 256, &mut budget),
        Err(ParseFailure::ResourceLimit),
    );
}

#[test]
fn structural_public_key_parsing_does_not_consume_policy_verification_budget() {
    let mut budget = OpenPgpReadBudget::default();
    for fingerprint in [b"first".as_slice(), b"second".as_slice()] {
        budget.policy_mut().select_certificate(fingerprint);
        while budget.policy_mut().charge_public_key_verification().is_ok() {}
    }

    assert_eq!(
        parse_public_key_documents(&[PUBLIC_KEY.to_vec()], &mut budget)
            .expect("parse certificate without policy verification")
            .len(),
        1,
    );
}

#[test]
fn ordinary_multi_certificate_document_stays_within_global_work_budgets() {
    let documents = vec![PUBLIC_KEY.to_vec(), DESIGNATED_REVOKER_PUBLIC_KEY.to_vec()];
    let certificates = parse_public_key_documents(&documents, &mut OpenPgpReadBudget::default())
        .expect("parse ordinary multi-certificate keyserver response");

    assert_eq!(certificates.len(), 2);
}

#[test]
fn packet_count_and_body_size_limits_are_inclusive() {
    let mut empty_packet = Vec::new();
    PacketHeader::new_fixed(Tag::Padding, 0)
        .to_writer(&mut empty_packet)
        .expect("padding header must serialize");
    let mut budget = OpenPgpReadBudget::default();
    assert_eq!(
        preflight_openpgp_packets(&empty_packet.repeat(MAX_PACKETS_PER_REQUEST), &mut budget),
        Ok(())
    );
    assert_eq!(budget.packets, MAX_PACKETS_PER_REQUEST);
    assert_eq!(
        preflight_openpgp_packets(
            &empty_packet.repeat(MAX_PACKETS_PER_REQUEST + 1),
            &mut OpenPgpReadBudget::default(),
        ),
        Err(ParseFailure::ResourceLimit),
    );

    let mut boundary_packet = Vec::new();
    PacketHeader::new_fixed(
        Tag::Padding,
        u32::try_from(MAX_PACKET_BODY_BYTES).expect("packet limit fits u32"),
    )
    .to_writer(&mut boundary_packet)
    .expect("padding header must serialize");
    boundary_packet.resize(boundary_packet.len() + MAX_PACKET_BODY_BYTES, 0);
    assert_eq!(
        preflight_openpgp_packets(&boundary_packet, &mut OpenPgpReadBudget::default(),),
        Ok(())
    );

    let mut oversized_header = Vec::new();
    PacketHeader::new_fixed(
        Tag::Padding,
        u32::try_from(MAX_PACKET_BODY_BYTES + 1).expect("packet limit fits u32"),
    )
    .to_writer(&mut oversized_header)
    .expect("padding header must serialize");
    assert_eq!(
        preflight_openpgp_packets(&oversized_header, &mut OpenPgpReadBudget::default(),),
        Err(ParseFailure::ResourceLimit),
    );
}

#[test]
fn rsa_parameter_caps_preserve_8192_bit_keys_and_bound_exponents() {
    assert_eq!(
        validate_public_key_parameter_values(&rsa_public_params()),
        Ok(()),
    );
    assert_eq!(
        validate_rsa_parameter_bytes(&rsa_public_parameter_bytes(MAX_RSA_PUBLIC_EXPONENT_BYTES,)),
        Ok(()),
    );
    assert_eq!(
        validate_rsa_parameter_bytes(&rsa_public_parameter_bytes(
            MAX_RSA_PUBLIC_EXPONENT_BYTES + 1,
        )),
        Err(ParseFailure::ResourceLimit),
    );
}

#[test]
fn detached_signature_count_limit_is_inclusive() {
    let signature = parse_detached_signatures_fresh(DETACHED_SIGNATURE)
        .expect("fixed detached signature must parse")
        .remove(0);
    let mut packet = Vec::new();
    DetachedSignature::new(signature)
        .to_writer(&mut packet)
        .expect("detached signature packet must serialize");
    assert_eq!(
        parse_detached_signatures_fresh(&packet.repeat(MAX_DETACHED_SIGNATURES))
            .expect("signature-count boundary must parse")
            .len(),
        MAX_DETACHED_SIGNATURES,
    );
    assert_eq!(
        parse_detached_signatures_fresh(&packet.repeat(MAX_DETACHED_SIGNATURES + 1)),
        Err(OpenPgpReadError::ResourceLimit),
    );

    let malformed = malformed_v6_salt_signature_packet();
    assert_eq!(
        parse_detached_signatures_fresh(&malformed.repeat(MAX_DETACHED_SIGNATURES)),
        Err(OpenPgpReadError::InvalidArgument),
        "all malformed signatures at the boundary are unusable, not over limit",
    );
    assert_eq!(
        parse_detached_signatures_fresh(&malformed.repeat(MAX_DETACHED_SIGNATURES + 1)),
        Err(OpenPgpReadError::ResourceLimit),
        "skipped malformed signatures still consume the signature-count budget",
    );
}

#[test]
fn malformed_detached_signature_peers_are_isolated_across_encodings_and_apis() {
    let _guard = verifier_worker_test_guard();
    let valid = binary_detached_signature_fixture();
    let malformed = malformed_v6_salt_signature_packet();

    for (order, first, second) in [
        ("malformed before valid", &malformed, &valid),
        ("malformed after valid", &valid, &malformed),
    ] {
        let mut binary = first.clone();
        binary.extend_from_slice(second);

        let combined_armor = armored_signature_packets(&binary);
        let mut separate_armor = armored_signature_packets(first);
        separate_armor.extend_from_slice(b"\ninter-block prose\n");
        separate_armor.extend_from_slice(&armored_signature_packets(second));

        for (encoding, document) in [
            ("binary", binary),
            ("combined armor", combined_armor),
            ("separate armor blocks", separate_armor),
        ] {
            let parsed = parse_detached_signatures_fresh(&document)
                .unwrap_or_else(|error| panic!("{order}, {encoding}: {error:?}"));
            assert_eq!(parsed.len(), 1, "{order}, {encoding}");

            let one_shot = detached_verification_with_signature(document.clone());
            assert_eq!(
                one_shot.status,
                OpenPgpVerificationStatus::Valid as i32,
                "{order}, {encoding}",
            );
            assert!(
                one_shot.signatures.is_empty(),
                "skipped malformed packets are omitted, leaving the usual flat single-signature result: {order}, {encoding}",
            );

            let streamed = streamed_detached_verification_with_signature(document);
            assert_eq!(streamed, one_shot, "{order}, {encoding}");
        }
    }
}

#[test]
fn unsupported_detached_signature_forms_do_not_hide_a_valid_peer() {
    let valid = binary_detached_signature_fixture();
    let unsupported = [
        (
            "unknown version",
            fixed_openpgp_packet(SIGNATURE_TAG, &[99]),
        ),
        (
            "unknown signature type",
            signature_packet_with_body_byte(1, 0xff),
        ),
        (
            "unknown public-key algorithm and opaque material",
            signature_packet_with_body_byte(2, 0xff),
        ),
        (
            "unknown hash algorithm",
            signature_packet_with_body_byte(3, 0xff),
        ),
    ];

    for (case, unsupported) in unsupported {
        for (order, first, second) in [
            ("unsupported before valid", &unsupported, &valid),
            ("unsupported after valid", &valid, &unsupported),
        ] {
            let mut document = first.clone();
            document.extend_from_slice(second);
            let parsed = parse_detached_signatures_fresh(&document)
                .unwrap_or_else(|error| panic!("{case}, {order}: {error:?}"));
            assert_eq!(parsed.len(), 1, "{case}, {order}");
            let result = detached_verification_with_signature(document);
            assert_eq!(
                result.status,
                OpenPgpVerificationStatus::Valid as i32,
                "{case}, {order}",
            );
            assert!(result.signatures.is_empty(), "{case}, {order}");
        }
    }
}

#[test]
fn malformed_detached_signatures_never_report_success_without_a_valid_peer() {
    let malformed = malformed_v6_salt_signature_packet();
    let unknown_version = fixed_openpgp_packet(SIGNATURE_TAG, &[99]);
    let mut binary = malformed;
    binary.extend_from_slice(&unknown_version);

    for (encoding, document) in [
        ("binary", binary.clone()),
        ("armor", armored_signature_packets(&binary)),
    ] {
        assert_eq!(
            parse_detached_signatures_fresh(&document),
            Err(OpenPgpReadError::InvalidArgument),
            "{encoding}",
        );
        let mut request = detached_request(DETACHED_BODY.to_vec(), vec![PUBLIC_KEY.to_vec()]);
        request.signature = document.clone();
        assert_eq!(
            verify_request(request),
            Err(OpenPgpReadError::InvalidArgument),
            "{encoding}",
        );
        assert!(matches!(
            DetachedVerificationSession::open(DetachedVerifyInput {
                signature: document,
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            }),
            Err(OpenPgpReadError::InvalidArgument),
        ));
    }
}

#[test]
fn detached_signature_sequence_keeps_framing_criticality_and_resource_errors_strict() {
    let valid = binary_detached_signature_fixture();

    let mut allowed = fixed_openpgp_packet(MARKER_TAG, b"PGP");
    allowed.extend_from_slice(&fixed_openpgp_packet(PADDING_TAG, b"padding"));
    allowed.extend_from_slice(&fixed_openpgp_packet(40, b"noncritical extension"));
    allowed.extend_from_slice(&valid);
    assert_eq!(
        parse_detached_signatures_fresh(&allowed)
            .expect("Marker, Padding, and unknown noncritical packets are permitted")
            .len(),
        1,
    );

    for (case, packet) in [
        ("known non-signature packet", fixed_openpgp_packet(11, b"")),
        (
            "unknown critical packet",
            fixed_openpgp_packet(22, b"opaque"),
        ),
        ("malformed Marker", fixed_openpgp_packet(MARKER_TAG, b"PGX")),
    ] {
        let mut document = packet;
        document.extend_from_slice(&valid);
        assert_eq!(
            parse_detached_signatures_fresh(&document),
            Err(OpenPgpReadError::InvalidArgument),
            "{case}",
        );
    }

    let mut truncated = valid.clone();
    truncated.pop();
    assert_eq!(
        parse_detached_signatures_fresh(&truncated),
        Err(OpenPgpReadError::InvalidArgument),
        "a truncated framed body is a document error",
    );

    let mut malformed_trailing_armor = armored_signature_packets(&valid);
    malformed_trailing_armor.extend_from_slice(b"\n-----BEGIN PGP SIGNATURE-----\n\nAAAA\n");
    assert_eq!(
        parse_detached_signatures_fresh(&malformed_trailing_armor),
        Err(OpenPgpReadError::InvalidArgument),
        "a malformed trailing armor block is not hidden by an earlier valid block",
    );

    let oversized = fixed_openpgp_packet(SIGNATURE_TAG, &vec![0; MAX_PACKET_BODY_BYTES + 1]);
    assert_eq!(
        parse_detached_signatures_fresh(&oversized),
        Err(OpenPgpReadError::ResourceLimit),
        "an oversized malformed Signature packet remains a resource error",
    );

    let noncritical = fixed_openpgp_packet(40, b"");
    let mut too_many_packets = noncritical.repeat(MAX_PACKETS_PER_REQUEST);
    too_many_packets.extend_from_slice(&valid);
    assert_eq!(
        parse_detached_signatures_fresh(&too_many_packets),
        Err(OpenPgpReadError::ResourceLimit),
        "packet-stream limits remain document-wide",
    );
}

#[test]
fn cleartext_verification_skips_malformed_signature_peers() {
    let _guard = verifier_worker_test_guard();
    let signature_start = find_subslice(CLEAR_SIGNED, CLEAR_SIGNED_SIGNATURE_MARKER)
        .expect("clear-sign fixture signature marker");
    let valid = decode_openpgp_packets(&CLEAR_SIGNED[signature_start..])
        .expect("clear-sign fixture signature must dearmor");
    let malformed = malformed_v6_salt_signature_packet();

    for (order, first, second) in [
        ("malformed before valid", &malformed, &valid),
        ("malformed after valid", &valid, &malformed),
    ] {
        let mut packets = first.clone();
        packets.extend_from_slice(second);
        let mut document = CLEAR_SIGNED[..signature_start].to_vec();
        document.extend_from_slice(&armored_signature_packets(&packets));

        let one_shot = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: document.clone(),
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_eq!(
            one_shot.status,
            OpenPgpVerificationStatus::Valid as i32,
            "{order}",
        );
        assert!(one_shot.signatures.is_empty(), "{order}");

        let mut stream = crate::openpgp::adapter::OpenPgpSession::clear_verify(
            OpenPgpClearVerifyStreamOpenRequest {
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        )
        .expect("open cleartext verification stream");
        let mut recovered_body = Vec::new();
        for chunk in document.chunks(5) {
            recovered_body
                .extend_from_slice(&stream.update(chunk).expect("stream cleartext chunk"));
        }
        let streamed = OpenPgpClearVerifyResult::decode(
            stream
                .finish()
                .expect("finish cleartext verification stream")
                .as_slice(),
        )
        .expect("decode cleartext verification result");
        assert_eq!(
            streamed.verification,
            Some(one_shot),
            "streamed result: {order}",
        );
        assert_eq!(
            recovered_body, b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
            "streamed body: {order}",
        );
    }
}

#[test]
fn streaming_clear_verify_matches_one_shot_across_chunk_boundaries() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let crlf = fixture.replace('\n', "\r\n");
    let whitespace_markers = fixture
        .replace(
            "-----BEGIN PGP SIGNED MESSAGE-----",
            "-----BEGIN PGP SIGNED MESSAGE----- \t",
        )
        .replace(
            "-----BEGIN PGP SIGNATURE-----",
            "-----BEGIN PGP SIGNATURE-----\t ",
        );
    let whitespace_markers_crlf = whitespace_markers.replace('\n', "\r\n");
    let cases: [(Vec<u8>, &[u8]); 4] = [
        (
            CLEAR_SIGNED.to_vec(),
            b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
        ),
        (
            crlf.into_bytes(),
            b"OpenPGP clear text\r\n- dash-prefixed line\r\nfinal line",
        ),
        (
            whitespace_markers.into_bytes(),
            b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
        ),
        (
            whitespace_markers_crlf.into_bytes(),
            b"OpenPGP clear text\r\n- dash-prefixed line\r\nfinal line",
        ),
    ];
    for (document, expected_body) in cases {
        let expected = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: document.clone(),
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_eq!(expected.status, OpenPgpVerificationStatus::Valid as i32);
        assert!(expected.signatures.is_empty());
        for chunk_size in [1_usize, 7, 31, 64 * 1024] {
            let mut budget = OpenPgpReadBudget::default();
            let certificates = parse_public_key_documents(&[PUBLIC_KEY.to_vec()], &mut budget)
                .expect("fixture public key must parse");
            let mut session = ClearVerificationSession::with_certificates(
                certificates,
                budget,
                DataSignatureVerificationTime::exact(REFERENCE_TIME),
            );
            let mut body = Vec::new();
            for chunk in document.chunks(chunk_size) {
                body.extend_from_slice(&session.update(chunk).expect("update must succeed"));
            }
            let result =
                wire_clear_verification_from_domain(session.finish().expect("finish must succeed"));
            assert_eq!(body, expected_body);
            assert_eq!(result.verification, Some(expected.clone()));
            assert!(result.body_valid_utf8);
        }
    }
}

#[test]
fn clear_verify_session_rejects_nonconformant_armor_marker_lines() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    for (marker, malformed) in [
        (
            "-----BEGIN PGP SIGNED MESSAGE-----",
            " -----BEGIN PGP SIGNED MESSAGE-----",
        ),
        (
            "-----BEGIN PGP SIGNED MESSAGE-----",
            "-----BEGIN PGP SIGNED MESSAGE-----x",
        ),
        (
            "-----BEGIN PGP SIGNED MESSAGE-----",
            "-----BEGIN PGP SIGNED MESSAG-----",
        ),
        (
            "-----BEGIN PGP SIGNATURE-----",
            " -----BEGIN PGP SIGNATURE-----",
        ),
        (
            "-----BEGIN PGP SIGNATURE-----",
            "-----BEGIN PGP SIGNATURE-----x",
        ),
        (
            "-----BEGIN PGP SIGNATURE-----",
            "-----BEGIN PGP SIGNATUR-----",
        ),
    ] {
        let document = fixture.replacen(marker, malformed, 1);
        assert_eq!(
            clear_verify_session_fresh(document.as_bytes()).err(),
            Some(OpenPgpReadError::InvalidArgument),
            "armor marker must be rejected: {malformed:?}",
        );
    }
}

#[test]
fn clear_verify_session_accepts_a_missing_legacy_hash_header() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let missing_hash = fixture.replace("Hash: SHA512\n", "");
    let (_, result) = clear_verify_session_fresh(missing_hash.as_bytes())
        .expect("RFC 9580 permits an omitted Hash header");
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Valid as i32,
    );
}

#[test]
fn clear_verify_session_skips_safe_non_hash_armor_headers() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    for header in [
        "Comment: hello",
        "Version: GnuPG",
        "Charset: ISO-8859-1",
        "MessageID: 1234",
        "X-Unknown: value",
        "Hash-Extension: SHA512",
    ] {
        let document = fixture.replace("Hash: SHA512", &format!("Hash: SHA512\n{header}"));
        for chunk_size in [1, 7, document.len()] {
            let mut budget = OpenPgpReadBudget::default();
            let certificates = parse_public_key_documents(&[PUBLIC_KEY.to_vec()], &mut budget)
                .expect("fixture public key must parse");
            let mut session = ClearVerificationSession::with_certificates(
                certificates,
                budget,
                DataSignatureVerificationTime::exact(REFERENCE_TIME),
            );
            let mut body = Vec::new();
            for chunk in document.as_bytes().chunks(chunk_size) {
                body.extend_from_slice(
                    &session
                        .update(chunk)
                        .expect("safe unknown header must be skipped"),
                );
            }
            let result = session
                .finish()
                .expect("safe unknown header must not prevent verification");
            assert_eq!(
                body, b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
                "header must not enter the recovered body: {header:?} with chunk size {chunk_size}",
            );
            assert_eq!(
                result.verification.status,
                VerificationStatus::Valid,
                "header must be skipped: {header:?} with chunk size {chunk_size}",
            );
        }
    }
}

#[test]
fn clear_verify_session_rejects_malformed_unknown_armor_headers() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    // A header section line with no colon is body text that escaped the
    // mandatory blank separator; swallowing it would change the signed
    // bytes.
    for malformed in [
        "not a header line",
        ": value",
        "Bad Key: value",
        "X-Unknown:value",
        "X-Unknown:\tvalue",
        "X-Unknown: ",
        "HASH: SHA512",
        "X-Unknown: value\u{7f}",
    ] {
        let document = fixture.replace("Hash: SHA512", &format!("Hash: SHA512\n{malformed}"));
        assert_eq!(
            clear_verify_session_fresh(document.as_bytes()).err(),
            Some(OpenPgpReadError::InvalidArgument),
            "malformed header must be rejected: {malformed:?}",
        );
    }
}

#[test]
fn clear_verify_session_treats_not_dash_escaped_marker_as_body_text() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let marker = "NotDashEscaped: You need GnuPG to verify this message";
    let document = fixture.replace("OpenPGP clear text", marker);
    let (body, result) = clear_verify_session_fresh(document.as_bytes())
        .expect("the marker is ordinary text after the header separator");
    assert_eq!(
        body,
        b"NotDashEscaped: You need GnuPG to verify this message\n- dash-prefixed line\nfinal line",
    );
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Invalid as i32,
        "changing signed body text must invalidate the fixture signature",
    );
}

#[test]
fn clear_verify_session_rejects_malformed_hash_headers() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    for malformed in [
        "hash: SHA512",
        "Hash:SHA512",
        "Hash:\tSHA512",
        "Hash: sha512",
        "Hash: SHA-512",
        "Hash: BLAKE2",
        "Hash:",
        "Hash: ",
        "Hash: ,SHA512",
        "Hash: SHA512,",
        "Hash: SHA512,,SHA256",
    ] {
        let document = fixture.replace("Hash: SHA512", malformed);
        assert_eq!(
            clear_verify_session_fresh(document.as_bytes()).err(),
            Some(OpenPgpReadError::InvalidArgument),
            "header must be rejected: {malformed:?}",
        );
    }
}

#[test]
fn clear_verify_session_accepts_well_formed_hash_headers() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    for text_name in [
        "MD5",
        "SHA1",
        "RIPEMD160",
        "SHA256",
        "SHA384",
        "SHA512",
        "SHA224",
        "SHA3-256",
        "SHA3-512",
    ] {
        let misleading = fixture.replace("Hash: SHA512", &format!("Hash: {text_name}"));
        let (_, result) = clear_verify_session_fresh(misleading.as_bytes())
            .expect("the signature packet selects the digest");
        assert_eq!(
            result.verification.expect("verification").status,
            OpenPgpVerificationStatus::Valid as i32,
        );
    }
    let (_, result) = clear_verify_session_fresh(
        fixture
            .replace("Hash: SHA512", "Hash: SHA256, SHA512")
            .as_bytes(),
    )
    .expect("a well-formed digest list must be accepted");
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Valid as i32,
    );
}

#[test]
fn clear_verify_session_accepts_a_whitespace_only_header_separator() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let document = fixture.replace("Hash: SHA512\n\n", "Hash: SHA512\n \t\n");
    let (body, result) = clear_verify_session_fresh(document.as_bytes())
        .expect("a whitespace-only armor separator must be accepted");
    assert_eq!(
        body,
        b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
    );
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Valid as i32,
    );
}

#[test]
fn clear_verify_session_omits_unauthenticated_trailing_whitespace() {
    let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
    let document = fixture.replace(
        "OpenPGP clear text\n- - dash-prefixed line\nfinal line\n",
        "OpenPGP clear text \t\n- - dash-prefixed line\t\nfinal line   \n",
    );
    let (body, result) = clear_verify_session_fresh(document.as_bytes())
        .expect("trailing whitespace is excluded from cleartext signatures");
    assert_eq!(
        body,
        b"OpenPGP clear text\n- dash-prefixed line\nfinal line",
    );
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Valid as i32,
    );
}

#[test]
fn clear_verify_session_recovers_body_and_flags_invalid_utf8() {
    let signature_offset = find_subslice(CLEAR_SIGNED, b"-----BEGIN PGP SIGNATURE-----")
        .expect("clear-sign fixture signature marker");
    let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n".to_vec();
    document.extend_from_slice(b"first\rsecond \xFF\rthird\n");
    document.extend_from_slice(&CLEAR_SIGNED[signature_offset..]);
    let (body, result) = clear_verify_session_fresh(&document).expect("bare-CR body must parse");
    assert_eq!(body, b"first\rsecond \xFF\rthird");
    assert!(!result.body_valid_utf8);
    assert_eq!(
        result.verification.expect("verification").status,
        OpenPgpVerificationStatus::Invalid as i32,
    );
}

#[test]
fn clear_verify_session_rejects_missing_or_oversized_signature() {
    let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\nbody\n".to_vec();
    assert_eq!(
        clear_verify_session_fresh(&document).err(),
        Some(OpenPgpReadError::InvalidArgument),
    );
    document.extend_from_slice(b"-----BEGIN PGP SIGNATURE-----\n");
    document.extend(std::iter::repeat_n(b'a', MAX_CLEAR_SIGNED_SIGNATURE_BYTES));
    assert_eq!(
        clear_verify_session_fresh(&document).err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
}

#[test]
fn clear_verify_signature_buffer_accepts_single_byte_updates_up_to_limit() {
    let mut session = ClearVerificationSession::with_certificates(
        Vec::new(),
        OpenPgpReadBudget::default(),
        DataSignatureVerificationTime::exact(REFERENCE_TIME),
    );
    for _ in 0..MAX_CLEAR_SIGNED_SIGNATURE_BYTES {
        session
            .push_signature(b"a")
            .expect("single-byte signature update within the limit must succeed");
    }
    assert_eq!(session.signature.len(), MAX_CLEAR_SIGNED_SIGNATURE_BYTES);
    assert_eq!(
        session.push_signature(b"a"),
        Err(OpenPgpReadError::ResourceLimit),
    );
}

#[test]
fn clear_verify_session_bounds_retained_canonical_body() {
    let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n".to_vec();
    let line = vec![b'a'; MAX_CLEAR_SIGNED_LINE_BYTES];
    for _ in 0..(MAX_CLEAR_SIGNED_BODY_BYTES / MAX_CLEAR_SIGNED_LINE_BYTES + 2) {
        document.extend_from_slice(&line);
        document.push(b'\n');
    }
    assert_eq!(
        clear_verify_session_fresh(&document).err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
}

#[test]
fn clear_verify_session_bounds_the_preamble_scan() {
    let mut document = Vec::new();
    for _ in 0..=(MAX_CLEAR_SIGNED_HEADER_BYTES / 8) {
        document.extend_from_slice(b"quoted \n");
    }
    document.extend_from_slice(CLEAR_SIGNED);
    assert_eq!(
        clear_verify_session_fresh(&document).err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
    assert_eq!(
        clear_verify_session_fresh(b"no marker here\n\nbody\n").err(),
        Some(OpenPgpReadError::InvalidArgument),
    );
}

#[test]
fn cleartext_header_line_count_and_line_length_limits_are_inclusive() {
    let signature_marker = b"-----BEGIN PGP SIGNATURE-----";
    let signature_offset =
        find_subslice(CLEAR_SIGNED, signature_marker).expect("clear-sign fixture signature marker");
    let signature = &CLEAR_SIGNED[signature_offset..];

    // The framing region (marker line, headers, blank line) is padded to
    // exactly `framing_bytes` using interior whitespace in a valid Hash
    // header, which the parser trims before matching the algorithm.
    const MIN_FRAMING_BYTES: usize =
        "-----BEGIN PGP SIGNED MESSAGE-----\n".len() + "Hash: SHA256\n".len() + "\n".len();
    let clear_signed = |framing_bytes: usize, line_count: usize, line_bytes: usize| {
        let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----\n".to_vec();
        document.extend_from_slice(b"Hash: ");
        document.extend(std::iter::repeat_n(b' ', framing_bytes - MIN_FRAMING_BYTES));
        document.extend_from_slice(b"SHA256\n\n");
        for _ in 0..line_count {
            document.extend(std::iter::repeat_n(b'a', line_bytes));
            document.push(b'\n');
        }
        document.extend_from_slice(signature);
        document
    };

    assert!(
        clear_verify_session_fresh(&clear_signed(MAX_CLEAR_SIGNED_HEADER_BYTES, 1, 1,)).is_ok(),
    );
    assert_eq!(
        clear_verify_session_fresh(&clear_signed(MAX_CLEAR_SIGNED_HEADER_BYTES + 1, 1, 1,)).err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
    assert!(
        clear_verify_session_fresh(&clear_signed(MIN_FRAMING_BYTES, MAX_CLEAR_SIGNED_LINES, 1))
            .is_ok(),
    );
    assert_eq!(
        clear_verify_session_fresh(&clear_signed(
            MIN_FRAMING_BYTES,
            MAX_CLEAR_SIGNED_LINES + 1,
            1
        ))
        .err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
    assert!(
        clear_verify_session_fresh(&clear_signed(
            MIN_FRAMING_BYTES,
            1,
            MAX_CLEAR_SIGNED_LINE_BYTES
        ))
        .is_ok(),
    );
    assert_eq!(
        clear_verify_session_fresh(&clear_signed(
            MIN_FRAMING_BYTES,
            1,
            MAX_CLEAR_SIGNED_LINE_BYTES + 1,
        ))
        .err(),
        Some(OpenPgpReadError::ResourceLimit),
    );
}

#[test]
fn secret_and_public_inputs_resolve_canonical_metadata() {
    for request in [
        OpenPgpMetadataResolveRequest {
            private_key_data: Some(SECRET_KEY.to_vec()),
            public_key_data: None,
            normalized_fingerprint: "93ab cf80 4d85 ee79 d6e1 db0e 7764 8d3e 5d4e 7699".to_owned(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
        OpenPgpMetadataResolveRequest {
            private_key_data: Some(b"malformed secret".to_vec()),
            public_key_data: Some(PUBLIC_KEY.to_vec()),
            normalized_fingerprint: PRIMARY_FINGERPRINT.to_ascii_lowercase(),
            candidate_revocation_keys: vec![b"ignored malformed candidate".to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        },
    ] {
        let result = OpenPgpMetadataResolveResult::decode(
            resolve_metadata(request)
                .expect("metadata request must produce nullable metadata")
                .as_slice(),
        )
        .expect("metadata result must decode");
        let resolution = result.resolution.expect("fixed key metadata");
        let certificate = resolution
            .certificates
            .first()
            .expect("certificate metadata");
        let index = certificate.index.as_ref().expect("certificate index");
        assert_eq!(index.primary_fingerprint, PRIMARY_FINGERPRINT);
        assert_eq!(index.components.len(), 2);
        assert_eq!(index.components[0].fingerprint, PRIMARY_FINGERPRINT);
        assert_eq!(index.components[0].keygrips, [PRIMARY_KEYGRIP]);
        assert_eq!(index.components[0].algorithm, "EDDSA");
        assert_eq!(index.components[1].fingerprint, SUBKEY_FINGERPRINT);
        assert_eq!(index.components[1].keygrips, [SUBKEY_KEYGRIP]);
        assert_eq!(index.components[1].algorithm, "ECDH");
        assert!(index.legacy_designated_revokers.is_empty());
    }
}

#[test]
fn metadata_is_absent_when_no_selected_authenticated_ring_exists() {
    let result = OpenPgpMetadataResolveResult::decode(
        resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(SECRET_KEY.to_vec()),
            public_key_data: Some(PUBLIC_KEY.to_vec()),
            normalized_fingerprint: "0000000000000000000000000000000000000000".to_owned(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        })
        .expect("metadata request must produce nullable metadata")
        .as_slice(),
    )
    .expect("metadata result must decode");
    assert_eq!(result.resolution, None);
}

#[test]
fn metadata_keeps_multiple_certificate_roots_separate() {
    let mut keyring = decode_openpgp_packets(PUBLIC_KEY).expect("decode fixed public key");
    keyring.extend_from_slice(
        &decode_openpgp_packets(DESIGNATED_REVOKER_PUBLIC_KEY).expect("decode designated revoker"),
    );
    let result = OpenPgpMetadataResolveResult::decode(
        resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: None,
            public_key_data: Some(keyring),
            normalized_fingerprint: String::new(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        })
        .expect("resolve multi-certificate metadata")
        .as_slice(),
    )
    .expect("decode multi-certificate metadata");
    let metadata = result.resolution.expect("metadata");
    assert_eq!(metadata.certificates.len(), 2);
    let primary_fingerprints = metadata
        .certificates
        .iter()
        .map(|certificate| {
            let index = certificate.index.as_ref().expect("certificate index");
            let primary = index.components.first().expect("primary component");
            assert_eq!(primary.role, OpenPgpKeyComponentRole::Primary as i32);
            assert_eq!(primary.fingerprint, index.primary_fingerprint);
            index.primary_fingerprint.clone()
        })
        .collect::<Vec<_>>();
    let revoker = parse_public_certificates_fresh(DESIGNATED_REVOKER_PUBLIC_KEY)
        .expect("parse designated revoker")
        .remove(0);
    assert_eq!(
        primary_fingerprints,
        [
            PRIMARY_FINGERPRINT.to_owned(),
            fingerprint_hex(&revoker.primary_key)
        ],
    );
}

#[test]
fn metadata_indexes_each_certificate_in_a_secret_keyring() {
    let generated = generate_key_request(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Second Root <second-root@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: REFERENCE_TIME - 60,
        expiration_seconds: None,
    })
    .expect("generate second secret certificate");
    let generated = OpenPgpKeyMaterial::decode(generated.as_slice())
        .expect("decode generated secret certificate");
    let mut keyring = decode_openpgp_packets(SECRET_KEY).expect("decode fixed secret key");
    keyring.extend_from_slice(
        &decode_openpgp_packets(&generated.private_key_armored)
            .expect("decode generated secret key"),
    );

    let result = OpenPgpMetadataResolveResult::decode(
        resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(keyring),
            public_key_data: None,
            normalized_fingerprint: String::new(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        })
        .expect("resolve secret keyring metadata")
        .as_slice(),
    )
    .expect("decode secret keyring metadata");
    let metadata = result.resolution.expect("secret keyring metadata");
    assert_eq!(metadata.certificates.len(), 2);
    assert!(metadata.certificates.iter().all(|certificate| {
        certificate.index.as_ref().is_some_and(|index| {
            index.components.iter().any(|component| {
                component.role == OpenPgpKeyComponentRole::Primary as i32
                    && component.stored_secret_material
            })
        })
    }));
}

#[test]
fn metadata_keeps_public_primary_first_for_filtered_tsk() {
    let result = OpenPgpMetadataResolveResult::decode(
        resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: Some(filtered_tsk_fixture()),
            public_key_data: Some(PUBLIC_KEY.to_vec()),
            normalized_fingerprint: String::new(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        })
        .expect("resolve filtered TSK metadata")
        .as_slice(),
    )
    .expect("decode filtered TSK metadata");

    let metadata = result.resolution.as_ref().expect("metadata");
    let certificate = metadata.certificates.first().expect("certificate metadata");
    let index = certificate.index.as_ref().expect("certificate index");
    assert_eq!(index.primary_fingerprint, PRIMARY_FINGERPRINT);
    assert_eq!(
        index
            .components
            .iter()
            .map(|component| component.fingerprint.as_str())
            .collect::<Vec<_>>(),
        [PRIMARY_FINGERPRINT, SUBKEY_FINGERPRINT],
    );
    assert_eq!(
        index.components[0].role,
        OpenPgpKeyComponentRole::Primary as i32,
    );
    assert!(!index.components[0].stored_secret_material);
    assert_eq!(
        index.components[1].role,
        OpenPgpKeyComponentRole::Subkey as i32,
    );
    assert!(index.components[1].stored_secret_material);
}

#[test]
fn unresolved_designated_revocation_blocks_new_uses_until_resolved() {
    let resolve = |candidate_revocation_keys| {
        OpenPgpMetadataResolveResult::decode(
            resolve_metadata(OpenPgpMetadataResolveRequest {
                private_key_data: None,
                public_key_data: Some(DESIGNATED_REVOKED_PUBLIC_KEY.to_vec()),
                normalized_fingerprint: String::new(),
                candidate_revocation_keys,
                reference_time_epoch_seconds: Some(1_783_960_000),
            })
            .expect("metadata request must produce nullable metadata")
            .as_slice(),
        )
        .expect("metadata result must decode")
        .resolution
        .expect("fixed designated-revocation certificate metadata")
    };

    let unresolved = resolve(Vec::new());
    assert_eq!(unresolved.policy_revision, 2);
    let unresolved_certificate = unresolved.certificates.first().expect("certificate");
    let unresolved_index = unresolved_certificate.index.as_ref().expect("index");
    assert!(
        unresolved_certificate
            .policy
            .iter()
            .any(|component| component.revocation_status
                == OpenPgpRevocationStatus::Indeterminate as i32)
    );
    let revoker = parse_public_certificates_fresh(DESIGNATED_REVOKER_PUBLIC_KEY)
        .expect("parse designated revoker")
        .remove(0);
    assert_eq!(
        unresolved_index
            .legacy_designated_revokers
            .iter()
            .map(|revoker| revoker.fingerprint.as_str())
            .collect::<Vec<_>>(),
        [fingerprint_hex(&revoker.primary_key)],
    );
    assert!(
        unresolved_certificate
            .policy
            .iter()
            .any(|component| component.allowed_new_data_uses.is_empty()),
        "the component with unresolved external revocation evidence must fail closed",
    );

    let resolved = resolve(vec![DESIGNATED_REVOKER_PUBLIC_KEY.to_vec()]);
    let resolved_certificate = resolved.certificates.first().expect("certificate");
    assert!(resolved_certificate.policy.iter().any(|component| component.revocation_status == OpenPgpRevocationStatus::Revoked as i32));
    assert_eq!(resolved_certificate.index, unresolved_certificate.index);
    for resolved_component in resolved_certificate
        .policy
        .iter()
        .filter(|component| component.allowed_new_data_uses.is_empty())
    {
        let unresolved_component = unresolved_certificate
            .policy
            .iter()
            .find(|component| component.fingerprint == resolved_component.fingerprint)
            .expect("matching unresolved component");
        assert!(
            unresolved_component.allowed_new_data_uses.is_empty(),
            "a component that resolves as revoked must already be quarantined",
        );
    }
}

/// Certificate states that share one generated RSA key, so the three
/// renewal tiers are compared against identical key material.
///
/// RSA is deliberate: an EdDSA self-signature cannot be issued under SHA-1
/// at all, so the legacy rescue tier is only reachable with an RSA primary.
fn renewal_test_certificate(user_id: &str) -> (SignedSecretKey, SignedPublicKey) {
    let material = OpenPgpKeyMaterial::decode(
        generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::Rsa as i32,
            user_id: user_id.to_owned(),
            rsa_bits: 3_072,
            creation_time_epoch_seconds: RENEWAL_TEST_CREATION_TIME,
            expiration_seconds: None,
        })
        .expect("generate renewal test certificate")
        .as_slice(),
    )
    .expect("decode generated certificate");
    let secret =
        SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.as_slice()))
            .expect("parse generated secret key")
            .0;
    let public = secret.to_public_key();
    (secret, public)
}

fn renewal_test_identity_certification(
    secret: &SignedSecretKey,
    identity: &impl Serialize,
    hash: HashAlgorithm,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        hash,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            RENEWAL_TEST_SIGNATURE_TIME,
        )))
        .expect("certification creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("certification issuer fingerprint"),
    ];
    config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            identity,
        )
        .expect("sign renewal test certification")
}

fn renewal_test_key_revocation(secret: &SignedSecretKey) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            RENEWAL_TEST_SIGNATURE_TIME,
        )))
        .expect("revocation creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ];
    config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign renewal test key revocation")
}

fn renewal_test_subkey_binding(
    secret: &SignedSecretKey,
    subkey_index: usize,
    hash: HashAlgorithm,
) -> Signature {
    let mut config = secret.secret_subkeys[subkey_index]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("generated subkey binding config");
    config.hash_alg = hash;
    let subkey = secret.secret_subkeys[subkey_index].key.public_key().clone();
    config
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("sign renewal test subkey binding")
}

/// Resolves v2 metadata for one in-memory certificate and returns its
/// transient per-component policy entries.
fn renewal_test_policies(certificate: &SignedPublicKey) -> Vec<OpenPgpComponentPolicyV2> {
    let resolution = OpenPgpMetadataResolveResult::decode(
        resolve_metadata(OpenPgpMetadataResolveRequest {
            private_key_data: None,
            public_key_data: Some(serialized_public_certificate(certificate)),
            normalized_fingerprint: String::new(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: Some(RENEWAL_TEST_REFERENCE_TIME),
        })
        .expect("resolve renewal test metadata")
        .as_slice(),
    )
    .expect("metadata result must decode")
    .resolution
    .expect("metadata");
    assert_eq!(resolution.policy_revision, 2);
    resolution
        .certificates
        .into_iter()
        .flat_map(|certificate| certificate.policy)
        .collect()
}

fn renewal_test_primary_policy(certificate: &SignedPublicKey) -> OpenPgpComponentPolicyV2 {
    let primary_fingerprint = fingerprint_hex(&certificate.primary_key);
    renewal_test_policies(certificate)
        .into_iter()
        .find(|policy| policy.fingerprint == primary_fingerprint)
        .expect("primary component policy")
}

#[test]
fn metadata_policy_reports_the_renewal_tier_for_each_certificate_state() {
    let (secret, healthy) = renewal_test_certificate("Renewal Tier <renewal@example.test>");
    let user_id = healthy.details.users[0].id.clone();

    let healthy_policies = renewal_test_policies(&healthy);
    assert!(
        healthy_policies
            .iter()
            .all(|policy| policy.revocation_status == OpenPgpRevocationStatus::NotRevoked as i32)
    );
    let primary_fingerprint = fingerprint_hex(&healthy.primary_key);
    assert!(
        healthy_policies
            .iter()
            .any(|policy| !policy.allowed_new_data_uses.is_empty()),
        "a healthy certificate must still authorize new data",
    );
    assert!(
        healthy_policies
            .iter()
            .all(|policy| policy.renewal == OpenPgpRenewalAuthorization::Authenticated as i32)
    );
    assert!(
        healthy_policies
            .iter()
            .any(|policy| policy.fingerprint == primary_fingerprint)
    );

    // Only self-certification is past the SHA-1 cutoff: the certificate
    // authenticates nothing, yet renewal is exactly what repairs it.
    let mut weak = healthy.clone();
    weak.details.users[0].signatures = vec![renewal_test_identity_certification(
        &secret,
        &user_id,
        HashAlgorithm::Sha1,
    )];
    let weak_policy = renewal_test_primary_policy(&weak);
    assert_eq!(
        weak_policy.renewal,
        OpenPgpRenewalAuthorization::TemplateOnly as i32,
    );
    assert!(
        renewal_test_policies(&weak)
            .iter()
            .all(|policy| policy.allowed_new_data_uses.is_empty()),
        "a certificate that authenticates nothing cannot authorize new data",
    );

    let mut revoked = healthy.clone();
    revoked.details.revocation_signatures = vec![renewal_test_key_revocation(&secret)];
    let revoked_policy = renewal_test_primary_policy(&revoked);
    assert_eq!(
        revoked_policy.revocation_status,
        OpenPgpRevocationStatus::Revoked as i32
    );
    assert_eq!(
        revoked_policy.renewal,
        OpenPgpRenewalAuthorization::None as i32,
    );
    assert!(revoked_policy.allowed_new_data_uses.is_empty());
    drop(secret);
}

#[test]
fn parse_result_reports_weak_hash_subkeys_as_renewable_but_unauthenticated() {
    let (secret, healthy) = renewal_test_certificate("Weak Binding <weak-binding@example.test>");
    let mut certificate = healthy.clone();
    // The signing subkey keeps a modern binding; the encryption subkey is
    // downgraded to a SHA-1 binding, which authenticates nothing.
    let weak_fingerprint = fingerprint_hex(&certificate.public_subkeys[1].key);
    let strong_fingerprint = fingerprint_hex(&certificate.public_subkeys[0].key);
    certificate.public_subkeys[1].signatures =
        vec![renewal_test_subkey_binding(&secret, 1, HashAlgorithm::Sha1)];

    let parsed = match parse_result(&serialized_public_certificate(&certificate)).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("expected a successful parse, got {other:?}"),
    };
    let key = &parsed.keys[0];
    assert!(key.authenticated);

    let strong = key
        .subkeys
        .iter()
        .find(|subkey| subkey.fingerprint == strong_fingerprint)
        .expect("strong subkey stays reported");
    assert!(strong.authenticated);
    assert!(strong.can_sign);

    let weak = key
        .subkeys
        .iter()
        .find(|subkey| subkey.fingerprint == weak_fingerprint)
        .expect("weak-hash subkey stays reported so renewal can repair it");
    assert!(!weak.authenticated);
    assert!(!weak.can_sign);
    assert!(!weak.can_encrypt);
    assert!(!weak.revoked);

    // A subkey with no verified binding at all is still hidden.
    let mut orphaned = healthy;
    orphaned.public_subkeys[1].signatures = Vec::new();
    let parsed = match parse_result(&serialized_public_certificate(&orphaned)).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
        other => panic!("expected a successful parse, got {other:?}"),
    };
    assert!(
        !parsed.keys[0]
            .subkeys
            .iter()
            .any(|subkey| subkey.fingerprint == weak_fingerprint)
    );
}

#[test]
fn parse_result_reports_the_primary_renewal_tier_for_each_certificate_state() {
    fn parsed_primary(certificate: &SignedPublicKey) -> OpenPgpPublicKeyInfo {
        match parse_result(&serialized_public_certificate(certificate)).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success
                .keys
                .into_iter()
                .next()
                .expect("a parsed certificate"),
            other => panic!("expected a successful parse, got {other:?}"),
        }
    }

    let (secret, healthy) = renewal_test_certificate("Primary Tier <primary@example.test>");
    let user_id = healthy.details.users[0].id.clone();

    let healthy_key = parsed_primary(&healthy);
    assert!(healthy_key.authenticated);
    assert_eq!(
        healthy_key.renewal,
        OpenPgpRenewalAuthorization::Authenticated as i32,
    );

    // Weak-hash self-certification: unauthenticated, yet a renewal is
    // exactly what reissues it with a modern digest.
    let mut weak = healthy.clone();
    weak.details.users[0].signatures = vec![renewal_test_identity_certification(
        &secret,
        &user_id,
        HashAlgorithm::Sha1,
    )];
    let weak_key = parsed_primary(&weak);
    assert!(!weak_key.authenticated);
    assert_eq!(
        weak_key.renewal,
        OpenPgpRenewalAuthorization::TemplateOnly as i32,
    );

    // No verified self-signature at all: also unauthenticated, but renewal
    // has nothing to reissue, so the two states must not be conflated. The
    // metadata view hides the unbound identity while the read DTO preserves
    // its original packet evidence.
    let healthy_packets = serialized_public_certificate(&healthy);
    let healthy_stream = RawPacketStream::parse(&healthy_packets, MAX_PACKETS_PER_REQUEST)
        .expect("frame generated certificate");
    let mut orphaned_packets = Vec::new();
    for packet in healthy_stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() != SIGNATURE_TAG)
    {
        orphaned_packets.extend_from_slice(healthy_stream.raw(packet));
    }
    assert!(
        RawPacketStream::parse(&orphaned_packets, MAX_PACKETS_PER_REQUEST)
            .expect("frame orphaned certificate")
            .packets()
            .iter()
            .any(|packet| packet.tag() == USER_ID_TAG),
    );
    let orphaned_key = match parse_result(&orphaned_packets).result {
        Some(open_pgp_public_key_parse_result::Result::Success(success)) => success
            .keys
            .into_iter()
            .next()
            .expect("one parsed orphaned certificate"),
        other => panic!("expected a successful orphaned parse, got {other:?}"),
    };
    assert!(!orphaned_key.authenticated);
    assert!(orphaned_key.user_ids.is_empty());
    assert_eq!(
        decode_openpgp_packets(orphaned_key.public_key_armored.as_bytes())
            .expect("dearmor preserved orphaned certificate"),
        orphaned_packets,
    );
    assert_eq!(
        orphaned_key.renewal,
        OpenPgpRenewalAuthorization::None as i32,
    );

    // A revoked primary is authenticated but not renewable.
    let mut revoked = healthy;
    revoked.details.revocation_signatures = vec![renewal_test_key_revocation(&secret)];
    let revoked_key = parsed_primary(&revoked);
    assert!(revoked_key.revoked);
    assert_eq!(
        revoked_key.renewal,
        OpenPgpRenewalAuthorization::None as i32,
    );
    drop(secret);
}

#[test]
fn detached_stream_verifies_across_arbitrary_chunk_boundaries() {
    let _guard = verifier_worker_test_guard();
    let request = detached_stream_request();
    for chunk_size in [1, 2, 3, 7, 31, 64 * 1024] {
        let mut session =
            DetachedVerificationSession::open(request.clone()).expect("stream must open");
        session.update(&[]).expect("empty chunk must be accepted");
        for chunk in DETACHED_BODY.chunks(chunk_size) {
            session.update(chunk).expect("body chunk must be accepted");
        }
        let result = session.finish().expect("stream must finish");
        assert_eq!(result.status, VerificationStatus::Valid);
    }
}

#[test]
fn dropping_unfinished_detached_stream_cancels_and_joins_worker() {
    let _guard = verifier_worker_test_guard();
    let mut session =
        DetachedVerificationSession::open(detached_stream_request()).expect("stream must open");
    session
        .update(&DETACHED_BODY[..3])
        .expect("partial body must be accepted");
    drop(session);
    assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
}

#[test]
fn verifier_worker_limit_rejects_then_releases_on_finish_and_cancellation() {
    let _guard = verifier_worker_test_guard();
    assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
    let request = detached_stream_request();
    let mut sessions = (0..MAX_OPENPGP_VERIFIER_WORKERS)
        .map(|_| DetachedVerificationSession::open(request.clone()).expect("worker permit"))
        .collect::<Vec<_>>();
    assert_eq!(
        ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
        MAX_OPENPGP_VERIFIER_WORKERS,
    );
    assert!(matches!(
        DetachedVerificationSession::open(request.clone()),
        Err(OpenPgpReadError::ResourceLimit),
    ));

    drop(sessions.pop());
    assert_eq!(
        ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
        MAX_OPENPGP_VERIFIER_WORKERS - 1,
    );
    let mut replacement =
        DetachedVerificationSession::open(request).expect("released permit must be reusable");
    replacement
        .update(DETACHED_BODY)
        .expect("replacement body must be accepted");
    let result = replacement.finish().expect("replacement must finish");
    assert_eq!(result.status, VerificationStatus::Valid);
    assert_eq!(
        ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
        MAX_OPENPGP_VERIFIER_WORKERS - 1,
    );

    drop(sessions);
    assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
}

fn assert_verification(
    result: &OpenPgpVerification,
    status: OpenPgpVerificationStatus,
    created_at_epoch_seconds: u64,
) {
    assert_eq!(result.status, status as i32);
    assert_eq!(result.key_id, "F83D947D29EFECF7");
    assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
    assert_eq!(result.user_ids, [USER_ID]);
    assert_eq!(
        result.created_at_epoch_seconds,
        Some(created_at_epoch_seconds),
    );
    assert!(result.warnings.is_empty());
}

#[test]
fn fingerprint_normalization_ignores_ascii_separators_and_case() {
    assert_eq!(
        normalize_fingerprint("d0bb cfbb-250d:3bb0"),
        "D0BBCFBB250D3BB0"
    );
    assert_eq!(normalize_fingerprint("d0bg"), "D0BG");
}

#[test]
fn signature_expiration_uses_the_mathematical_sum_without_wraparound() {
    // Although each operand is a 32-bit wire value, RFC 9580 defines the
    // expiration instant as their mathematical sum.  This expires five
    // seconds after the 32-bit timestamp boundary, not at epoch second 4.
    let created = Some(u64::from(u32::MAX) - 5);
    assert!(!signature_expired_at(created, 10, 4));
    assert!(!signature_expired_at(created, 10, 3));
    assert!(!signature_expired_at(created, 10, u64::from(u32::MAX) + 3));
    assert!(signature_expired_at(created, 10, u64::from(u32::MAX) + 5));

    // Only an encoded duration of zero means "never expires".
    assert!(signature_expired_at(
        Some(u64::from(u32::MAX) - 4),
        5,
        u64::MAX,
    ));
    assert!(signature_expired_at(created, 1_u64 << 32, u64::MAX));
    assert!(!signature_expired_at(Some(10), 20, 29));
    assert!(signature_expired_at(Some(10), 20, 30));
}

#[test]
fn channel_reader_preserves_arbitrary_chunk_boundaries() {
    let (sender, receiver) = mpsc::sync_channel(1);
    let worker = thread::spawn(move || {
        sender
            .send(Zeroizing::new(b"ab".to_vec()))
            .expect("first send must work");
        sender
            .send(Zeroizing::new(Vec::new()))
            .expect("empty send must work");
        sender
            .send(Zeroizing::new(b"cdef".to_vec()))
            .expect("last send must work");
    });
    let mut reader = ChannelReader {
        receiver,
        current: Zeroizing::new(Vec::new()),
        offset: 0,
    };
    let mut output = Vec::new();
    reader
        .read_to_end(&mut output)
        .expect("channel reader must drain");
    worker.join().expect("sender must join");
    assert_eq!(output, b"abcdef");
}
