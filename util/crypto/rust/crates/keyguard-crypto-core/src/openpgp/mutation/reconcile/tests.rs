use std::io::Cursor;

use super::*;
use crate::openpgp::adapter::wire::{
    Message as _, OpenPgpCertificateMaterialInputErrorReason,
    OpenPgpCertificateMaterialPairErrorReason, OpenPgpCertificateMaterialReconcileError,
    OpenPgpCertificateMaterialReconcileRequest, OpenPgpCertificateMaterialReconcileResult,
    OpenPgpCertificateMaterialReconcileSuccess, open_pgp_certificate_material_reconcile_result,
};
use crate::openpgp::certificate::{canonicalize_public_certificate, filtered_tsk_fixture};
use crate::openpgp::packet::{PUBLIC_KEY_TAG, RawPacketStream, SECRET_KEY_TAG, write_fixed_packet};
use pgp::{
    composed::{
        ArmorOptions, Deserializable, EncryptionCaps, KeyType, SecretKeyParamsBuilder,
        SignedPublicKey, SignedSecretKey, SubkeyParamsBuilder,
    },
    crypto::hash::HashAlgorithm,
    packet::{PacketHeader, SignatureConfig, SignatureType, Subpacket, SubpacketData},
    ser::Serialize,
    types::{KeyDetails, KeyVersion, Password, RevocationKey, RevocationKeyClass, Tag, Timestamp},
};
use rand::{SeedableRng, rngs::StdRng};

const MAX_RECONCILE_PACKETS: usize = 8 * 1024;

const PUBLIC_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc");
const OTHER_SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/mdc-secret.asc");

fn fingerprint() -> String {
    canonicalize_public_certificate(PUBLIC_KEY)
        .expect("canonicalize public fixture")
        .1
}

fn public_with_user_id_certification_before_user_id() -> Vec<u8> {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_RECONCILE_PACKETS).expect("parse public fixture");
    assert_eq!(
        stream
            .packets()
            .iter()
            .map(|packet| packet.tag())
            .collect::<Vec<_>>(),
        vec![6, 13, 2, 14, 2],
    );
    let mut displaced = Vec::new();
    for index in [0, 2, 1, 3, 4] {
        displaced.extend_from_slice(stream.raw(&stream.packets()[index]));
    }
    displaced
}

fn public_with_external_certification_before_user_id() -> Vec<u8> {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_RECONCILE_PACKETS).expect("parse public fixture");
    let (target, _) = SignedPublicKey::from_reader_single(Cursor::new(PUBLIC_KEY))
        .expect("parse target public certificate");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse external certifier");
    let certification = SignatureConfig::v4(
        SignatureType::CertPositive,
        certifier.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    )
    .sign_certification(
        &certifier.primary_key,
        &target.primary_key,
        &Password::empty(),
        Tag::UserId,
        &target.details.users[0].id,
    )
    .expect("sign external certification")
    .to_bytes()
    .expect("serialize external certification");

    let mut displaced = stream.raw(&stream.packets()[0]).to_vec();
    PacketHeader::new_fixed(
        Tag::Signature,
        u32::try_from(certification.len()).expect("signature body length fits u32"),
    )
    .to_writer(&mut displaced)
    .expect("write signature packet header");
    displaced.extend_from_slice(&certification);
    for packet in stream.packets().iter().skip(1) {
        displaced.extend_from_slice(stream.raw(packet));
    }
    displaced
}

fn reconcile(
    existing_public_certificate: Option<Vec<u8>>,
    incoming_public_certificate: Option<Vec<u8>>,
    existing_secret_certificate: Option<Vec<u8>>,
    incoming_secret_certificate: Option<Vec<u8>>,
) -> OpenPgpCertificateMaterialReconcileResult {
    reconcile_for_fingerprint(
        fingerprint(),
        existing_public_certificate,
        incoming_public_certificate,
        existing_secret_certificate,
        incoming_secret_certificate,
    )
}

fn reconcile_for_fingerprint(
    expected_primary_fingerprint: String,
    existing_public_certificate: Option<Vec<u8>>,
    incoming_public_certificate: Option<Vec<u8>>,
    existing_secret_certificate: Option<Vec<u8>>,
    incoming_secret_certificate: Option<Vec<u8>>,
) -> OpenPgpCertificateMaterialReconcileResult {
    let encoded = crate::openpgp::adapter::reconcile_certificate_material(
        OpenPgpCertificateMaterialReconcileRequest {
            expected_primary_fingerprint,
            existing_public_certificate,
            incoming_public_certificate,
            existing_secret_certificate,
            incoming_secret_certificate,
        },
    )
    .expect("reconciliation must not fail fatally");
    OpenPgpCertificateMaterialReconcileResult::decode(encoded.as_slice())
        .expect("decode reconciliation result")
}

fn success(
    result: OpenPgpCertificateMaterialReconcileResult,
) -> OpenPgpCertificateMaterialReconcileSuccess {
    match result.result {
        Some(open_pgp_certificate_material_reconcile_result::Result::Success(success)) => success,
        _ => panic!("expected reconciliation success"),
    }
}

fn error(
    result: OpenPgpCertificateMaterialReconcileResult,
) -> OpenPgpCertificateMaterialReconcileError {
    match result.result {
        Some(open_pgp_certificate_material_reconcile_result::Result::Error(error)) => error,
        _ => panic!("expected reconciliation error"),
    }
}

fn certificate_with_primary_as_subkey() -> Vec<u8> {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_RECONCILE_PACKETS).expect("parse public fixture");
    let primary = stream
        .packets()
        .iter()
        .find(|packet| packet.tag() == 6)
        .expect("public fixture has a primary key packet");
    let primary_body = stream.body_to_vec(primary);
    let mut certificate = stream.bytes().to_vec();
    PacketHeader::new_fixed(
        Tag::PublicSubkey,
        u32::try_from(primary_body.len()).expect("primary packet length fits u32"),
    )
    .to_writer(&mut certificate)
    .expect("write public subkey packet header");
    certificate.extend_from_slice(&primary_body);
    certificate
}

fn generated_uniform_version_secret(version: KeyVersion, seed: u64) -> SignedSecretKey {
    let subkey = SubkeyParamsBuilder::default()
        .version(version)
        .key_type(KeyType::X25519)
        .can_encrypt(EncryptionCaps::All)
        .created_at(Timestamp::from_secs(1_700_000_001))
        .build()
        .expect("build versioned encryption subkey");
    SecretKeyParamsBuilder::default()
        .version(version)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_700_000_000))
        .primary_user_id(format!(
            "Reconciliation {seed} <reconciliation-{seed}@example.test>"
        ))
        .passphrase(None)
        .subkey(subkey)
        .build()
        .expect("build versioned reconciliation certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate versioned reconciliation certificate")
}

fn wholly_local_public_certificate() -> Vec<u8> {
    let mut secret = generated_uniform_version_secret(KeyVersion::V4, 0x5245_434f_4e5f_4c4f);
    let local_config = |signature_type| {
        let mut config = SignatureConfig::v4(
            signature_type,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::ExportableCertification(false))
                .expect("critical non-exportable subpacket"),
        );
        config
    };
    let direct = local_config(SignatureType::Key)
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign local Direct Key signature");
    let user_id = secret.details.users[0].id.clone();
    let identity = local_config(SignatureType::CertPositive)
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign local identity binding");
    let subkey = secret.secret_subkeys[0].key.public_key();
    let binding = local_config(SignatureType::SubkeyBinding)
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &subkey,
        )
        .expect("sign local subkey binding");
    secret.details.direct_signatures = vec![direct];
    secret.details.users[0].signatures = vec![identity];
    secret.secret_subkeys[0].signatures = vec![binding];
    secret
        .to_public_key()
        .to_bytes()
        .expect("serialize wholly local public certificate")
}

fn certificate_with_subkeys_from(
    primary_source: &[u8],
    subkey_source: &[u8],
    subkey_tag: u8,
) -> Vec<u8> {
    let primary_stream = RawPacketStream::parse(primary_source, MAX_RECONCILE_PACKETS)
        .expect("parse primary-source certificate");
    let subkey_stream = RawPacketStream::parse(subkey_source, MAX_RECONCILE_PACKETS)
        .expect("parse subkey-source certificate");
    let primary_subkey = primary_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == subkey_tag)
        .expect("primary source has a subkey");
    let donor_subkey = subkey_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == subkey_tag)
        .expect("subkey source has a subkey");
    let mut certificate = Vec::new();
    for packet in primary_stream.packets().iter().take(primary_subkey) {
        certificate.extend_from_slice(primary_stream.raw(packet));
    }
    for packet in subkey_stream.packets().iter().skip(donor_subkey) {
        certificate.extend_from_slice(subkey_stream.raw(packet));
    }
    certificate
}

fn secret_packet_bodies(data: &[u8]) -> Vec<(u8, Vec<u8>)> {
    let stream =
        RawPacketStream::parse(data, MAX_RECONCILE_PACKETS).expect("parse transferable secret key");
    stream
        .packets()
        .iter()
        .filter(|packet| matches!(packet.tag(), 5 | 7))
        .map(|packet| (packet.tag(), stream.body(packet).to_vec()))
        .collect()
}

fn reprotected_secret(password: &str, seed: u64) -> Vec<u8> {
    let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("parse secret fixture for reprotection");
    let password = Password::from(password);
    let mut rng = StdRng::seed_from_u64(seed);
    secret
        .primary_key
        .set_password(&mut rng, &password)
        .expect("protect primary key");
    for subkey in &mut secret.secret_subkeys {
        subkey
            .key
            .set_password(&mut rng, &password)
            .expect("protect secret subkey");
    }
    secret
        .to_bytes()
        .expect("serialize reprotected secret certificate")
}

fn secret_with_gnu_dummy_primary(source: &[u8], usage: u8) -> Vec<u8> {
    assert!(matches!(usage, 254 | 255));
    let stream =
        RawPacketStream::parse(source, MAX_RECONCILE_PACKETS).expect("parse secret certificate");
    let primary = stream
        .packets()
        .first()
        .filter(|packet| packet.tag() == SECRET_KEY_TAG)
        .expect("secret certificate starts with a secret primary");
    let public_len = crate::openpgp::key::import_secret_packet_public_len(&stream, primary)
        .expect("parse primary public fields");
    let primary_body = stream.body(primary);
    let mut dummy_body = Vec::with_capacity(public_len + 8);
    dummy_body.extend_from_slice(&primary_body[..public_len]);
    // GnuPG private S2K 101, mode 1: no secret material follows the
    // extension number. Import and reconciliation share this exact shape.
    dummy_body.extend_from_slice(&[usage, 0, 101, 0, b'G', b'N', b'U', 1]);

    let mut certificate = Vec::new();
    write_fixed_packet(SECRET_KEY_TAG, &dummy_body, &mut certificate)
        .expect("serialize GNU dummy primary");
    for packet in stream.packets().iter().skip(1) {
        certificate.extend_from_slice(stream.raw(packet));
    }
    certificate
}

fn filtered_tsk_from(source: &[u8]) -> Vec<u8> {
    let (projection, _) =
        project_secret_certificate(source).expect("project source secret certificate");
    let public = RawPacketStream::parse(&projection, MAX_RECONCILE_PACKETS)
        .expect("parse source public projection");
    let public_primary = public
        .packets()
        .first()
        .filter(|packet| packet.tag() == PUBLIC_KEY_TAG)
        .expect("public projection starts with a public primary");
    let secret =
        RawPacketStream::parse(source, MAX_RECONCILE_PACKETS).expect("parse source secret packets");
    let mut filtered = public.raw(public_primary).to_vec();
    for packet in secret.packets().iter().skip(1) {
        filtered.extend_from_slice(secret.raw(packet));
    }
    filtered
}

fn armor_has_checksum(data: &[u8]) -> bool {
    std::str::from_utf8(data)
        .expect("armored output is UTF-8")
        .lines()
        .any(|line| line.starts_with('='))
}

fn secret_with_sensitive_revoker_declaration() -> Vec<u8> {
    let (mut target, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("parse target secret certificate");
    let (revoker, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse designated revoker secret certificate");
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        target.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            target.primary_key.fingerprint(),
        ))
        .expect("declaration issuer fingerprint"),
        Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
            RevocationKeyClass::Sensitive,
            revoker.primary_key.algorithm(),
            revoker.primary_key.fingerprint().as_bytes(),
        )))
        .expect("sensitive revocation-key subpacket"),
    ]);
    let declaration = config
        .sign_key(
            &target.primary_key,
            &Password::empty(),
            target.primary_key.public_key(),
        )
        .expect("sign sensitive designated-revoker declaration");
    target.details.direct_signatures.push(declaration);
    target
        .to_bytes()
        .expect("serialize secret certificate with sensitive declaration")
}

fn secret_with_local_certification() -> Vec<u8> {
    let (mut target, _) = SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY))
        .expect("parse target secret certificate");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse certifier secret certificate");
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        certifier.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::ExportableCertification(false))
            .expect("exportable-certification subpacket"),
    );
    let certification = config
        .sign_certification(
            &certifier.primary_key,
            target.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("sign local certification");
    target.details.users[0].signatures.push(certification);
    target
        .to_bytes()
        .expect("serialize secret certificate with local certification")
}

fn has_sensitive_revoker_declaration(data: &[u8]) -> bool {
    let (certificate, _) = SignedPublicKey::from_reader_single(Cursor::new(data))
        .expect("parse public certificate projection");
    certificate
        .details
        .direct_signatures
        .iter()
        .filter_map(|signature| signature.config())
        .flat_map(|config| config.hashed_subpackets())
        .any(|subpacket| {
            matches!(
                &subpacket.data,
                SubpacketData::RevocationKey(revoker)
                    if revoker.class == RevocationKeyClass::Sensitive
            )
        })
}

fn has_local_certification(data: &[u8]) -> bool {
    let (certificate, _) = SignedPublicKey::from_reader_single(Cursor::new(data))
        .expect("parse public certificate projection");
    certificate.details.users.iter().any(|user| {
        user.signatures.iter().any(|signature| {
            signature.config().is_some_and(|config| {
                config.hashed_subpackets().any(|subpacket| {
                    matches!(
                        subpacket.data,
                        SubpacketData::ExportableCertification(false)
                    )
                })
            })
        })
    })
}

#[test]
fn component_collision_is_attributed_to_each_invalid_public_input() {
    let colliding_certificate = certificate_with_primary_as_subkey();
    let incoming_error = error(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(colliding_certificate.clone()),
        None,
        None,
    ));
    let existing_error = error(reconcile(
        Some(colliding_certificate),
        Some(PUBLIC_KEY.to_vec()),
        None,
        None,
    ));
    let collision = OpenPgpCertificateMaterialInputErrorReason::ComponentCollision as i32;
    let unspecified = OpenPgpCertificateMaterialInputErrorReason::Unspecified as i32;
    let unspecified_pair = OpenPgpCertificateMaterialPairErrorReason::Unspecified as i32;

    assert_eq!(incoming_error.existing_public_input_error, unspecified);
    assert_eq!(incoming_error.incoming_public_input_error, collision);
    assert_eq!(incoming_error.existing_secret_input_error, unspecified);
    assert_eq!(incoming_error.incoming_secret_input_error, unspecified);
    assert_eq!(incoming_error.pair_error, unspecified_pair);
    assert_eq!(existing_error.existing_public_input_error, collision);
    assert_eq!(existing_error.incoming_public_input_error, unspecified);
    assert_eq!(existing_error.existing_secret_input_error, unspecified);
    assert_eq!(existing_error.incoming_secret_input_error, unspecified);
    assert_eq!(existing_error.pair_error, unspecified_pair);
}

#[test]
fn reconciliation_rehomes_a_displaced_self_certification() {
    let result = success(reconcile(
        None,
        Some(public_with_user_id_certification_before_user_id()),
        None,
        None,
    ));
    let (certificate, _) =
        SignedPublicKey::from_reader_single(Cursor::new(&result.public_certificate))
            .expect("parse reconciled public certificate");
    let user = certificate.details.users.first().expect("fixture user ID");

    assert!(user.signatures.iter().any(|signature| {
        signature
            .verify_certification(&certificate.primary_key, Tag::UserId, &user.id)
            .is_ok()
    }));
}

#[test]
fn reconciliation_preserves_an_unplaceable_external_certification_as_inert() {
    let result = success(reconcile(
        None,
        Some(public_with_external_certification_before_user_id()),
        None,
        None,
    ));
    let stream = RawPacketStream::parse(&result.public_certificate, MAX_RECONCILE_PACKETS)
        .expect("parse reconciled public certificate");
    let tags = stream
        .packets()
        .iter()
        .map(|packet| packet.tag())
        .collect::<Vec<_>>();

    assert_eq!(tags, vec![6, 2, 13, 2, 14, 2]);
    assert!(result.incoming_public_contributed);
}

#[test]
fn reconciliation_exports_bare_components_without_local_certifications() {
    let local = wholly_local_public_certificate();
    let expected_fingerprint = canonicalize_public_certificate(&local)
        .expect("canonicalize wholly local certificate")
        .1;
    let result = success(reconcile_for_fingerprint(
        expected_fingerprint.clone(),
        None,
        Some(local),
        None,
        None,
    ));
    assert_eq!(result.primary_fingerprint, expected_fingerprint);
    assert!(result.incoming_public_contributed);
    let stream = RawPacketStream::parse(&result.public_certificate, MAX_RECONCILE_PACKETS)
        .expect("parse reconciled public certificate");
    assert_eq!(
        stream
            .packets()
            .iter()
            .map(|packet| packet.tag())
            .collect::<Vec<_>>(),
        // Exportable Certification is inapplicable to the Direct Key and
        // Subkey Binding signatures, which remain around the omitted local
        // identity certification.
        vec![6, 2, 13, 14, 2],
    );
    assert!(!has_local_certification(&result.public_certificate));
}

#[test]
fn reconciliation_rejects_mixed_key_versions_as_malformed_inputs() {
    let v4 = generated_uniform_version_secret(KeyVersion::V4, 0x5245_434f_4e5f_5634);
    let v6 = generated_uniform_version_secret(KeyVersion::V6, 0x5245_434f_4e5f_5636);
    let v4_public = v4
        .to_public_key()
        .to_bytes()
        .expect("serialize uniform V4 public certificate");
    let v6_public = v6
        .to_public_key()
        .to_bytes()
        .expect("serialize uniform V6 public certificate");
    let v4_secret = v4
        .to_bytes()
        .expect("serialize uniform V4 secret certificate");
    let v6_secret = v6
        .to_bytes()
        .expect("serialize uniform V6 secret certificate");
    let malformed = OpenPgpCertificateMaterialInputErrorReason::MalformedCertificate as i32;
    let unspecified = OpenPgpCertificateMaterialInputErrorReason::Unspecified as i32;

    for (primary_public, subkey_public, primary_secret, subkey_secret, case) in [
        (&v4_public, &v6_public, &v4_secret, &v6_secret, "V4/V6"),
        (&v6_public, &v4_public, &v6_secret, &v4_secret, "V6/V4"),
    ] {
        let expected_fingerprint = canonicalize_public_certificate(primary_public)
            .expect("canonicalize uniform primary source")
            .1;
        let mixed_public = certificate_with_subkeys_from(primary_public, subkey_public, 14);
        let public_error = error(reconcile_for_fingerprint(
            expected_fingerprint.clone(),
            None,
            Some(mixed_public),
            None,
            None,
        ));
        assert_eq!(
            public_error.incoming_public_input_error, malformed,
            "classify mixed public {case} certificate",
        );
        assert_eq!(public_error.incoming_secret_input_error, unspecified);

        let mixed_secret = certificate_with_subkeys_from(primary_secret, subkey_secret, 7);
        let secret_error = error(reconcile_for_fingerprint(
            expected_fingerprint,
            None,
            None,
            None,
            Some(mixed_secret),
        ));
        assert_eq!(secret_error.incoming_public_input_error, unspecified);
        assert_eq!(
            secret_error.incoming_secret_input_error, malformed,
            "classify mixed secret {case} certificate",
        );
    }
}

#[test]
fn reconciliation_accepts_uniform_v4_and_v6_key_versions() {
    for (version, seed, case) in [
        (KeyVersion::V4, 0x554e_4946_4f52_4d34, "V4"),
        (KeyVersion::V6, 0x554e_4946_4f52_4d36, "V6"),
    ] {
        let secret = generated_uniform_version_secret(version, seed);
        let public = secret
            .to_public_key()
            .to_bytes()
            .expect("serialize uniform public certificate");
        let fingerprint = canonicalize_public_certificate(&public)
            .expect("canonicalize uniform public certificate")
            .1;
        let private = secret
            .to_bytes()
            .expect("serialize uniform secret certificate");

        let result = success(reconcile_for_fingerprint(
            fingerprint.clone(),
            Some(public),
            None,
            Some(private),
            None,
        ));
        assert_eq!(
            result.primary_fingerprint, fingerprint,
            "accept uniform {case} certificate",
        );
    }
}

#[test]
fn reconcile_derives_coherent_outputs_without_changing_secret_packets() {
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(PUBLIC_KEY.to_vec()),
        Some(SECRET_KEY.to_vec()),
        None,
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("secret input yields a rebuilt private certificate");
    let (projected, _) =
        project_secret_certificate(private).expect("project rebuilt private certificate");
    let canonical_projected = canonicalize_public_certificate(&projected)
        .expect("canonicalize rebuilt projection")
        .0;
    let canonical_public = canonicalize_public_certificate(&result.public_certificate)
        .expect("canonicalize public output")
        .0;

    assert_eq!(canonical_projected, canonical_public);
    assert_eq!(
        secret_packet_bodies(SECRET_KEY),
        secret_packet_bodies(private)
    );
    assert!(!result.existing_public_contributed);
    assert!(!result.incoming_public_contributed);
    assert!(result.existing_secret_contributed);
    assert!(!result.incoming_secret_contributed);
}

#[test]
fn reconciliation_accepts_reprotection_and_prefers_incoming_secret_packets() {
    let p0 = reprotected_secret("p0", 0x5030_5052_4f54_4543);
    let p1 = reprotected_secret("p1", 0x5031_5052_4f54_4543);
    assert_ne!(secret_packet_bodies(&p0), secret_packet_bodies(&p1));

    let p1_wins = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(p0.clone()),
        Some(p1.clone()),
    ));
    let p1_output = p1_wins
        .private_certificate
        .as_deref()
        .expect("trusted reprotection yields private output");
    assert_eq!(secret_packet_bodies(p1_output), secret_packet_bodies(&p1));
    assert!(!p1_wins.existing_secret_contributed);
    assert!(p1_wins.incoming_secret_contributed);

    let p0_wins_when_incoming = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(p1),
        Some(p0.clone()),
    ));
    let p0_output = p0_wins_when_incoming
        .private_certificate
        .as_deref()
        .expect("reversed trusted reprotection yields private output");
    assert_eq!(secret_packet_bodies(p0_output), secret_packet_bodies(&p0));
    assert!(!p0_wins_when_incoming.existing_secret_contributed);
    assert!(p0_wins_when_incoming.incoming_secret_contributed);
}

#[test]
fn reconciliation_prefers_real_secret_material_over_gnu_dummy_stub() {
    let real = reprotected_secret("real", 0x5245_414c_5354_5542);
    let stub = secret_with_gnu_dummy_primary(&real, 255);
    let (_, stub_overlay) =
        project_secret_certificate(&stub).expect("project exact GNU dummy primary");
    assert!(!stub_overlay.has_secret_primary());

    let stub_to_real = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(stub.clone()),
        Some(real.clone()),
    ));
    let upgraded = stub_to_real
        .private_certificate
        .as_deref()
        .expect("real incoming material upgrades dummy stub");
    assert_eq!(secret_packet_bodies(upgraded), secret_packet_bodies(&real));
    assert!(!stub_to_real.existing_secret_contributed);
    assert!(stub_to_real.incoming_secret_contributed);

    let real_to_stub = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(real.clone()),
        Some(stub),
    ));
    let retained = real_to_stub
        .private_certificate
        .as_deref()
        .expect("dummy incoming material cannot replace real material");
    assert_eq!(secret_packet_bodies(retained), secret_packet_bodies(&real));
    assert!(real_to_stub.existing_secret_contributed);
    assert!(!real_to_stub.incoming_secret_contributed);
}

#[test]
fn reconciliation_preserves_gnu_dummy_primary_packet() {
    for usage in [254, 255] {
        let stub = secret_with_gnu_dummy_primary(SECRET_KEY, usage);
        let stub_stream = RawPacketStream::parse(&stub, MAX_RECONCILE_PACKETS)
            .expect("parse GNU dummy-primary input");
        let original_primary = stub_stream.raw(&stub_stream.packets()[0]);

        let result = success(reconcile(Some(PUBLIC_KEY.to_vec()), None, Some(stub), None));
        let private = result
            .private_certificate
            .as_deref()
            .expect("GNU dummy-primary input yields private output");
        let rebuilt = RawPacketStream::parse(private, MAX_RECONCILE_PACKETS)
            .expect("parse reconciled GNU dummy-primary TSK");

        assert_eq!(rebuilt.packets()[0].tag(), SECRET_KEY_TAG);
        assert_eq!(rebuilt.raw(&rebuilt.packets()[0]), original_primary);
        assert!(rebuilt.packets().iter().any(|packet| packet.tag() == 7));
        assert!(result.existing_secret_contributed);
        assert!(!result.incoming_secret_contributed);
    }
}

#[test]
fn reconciliation_prefers_incoming_gnu_dummy_primary_representation() {
    let existing = secret_with_gnu_dummy_primary(SECRET_KEY, 254);
    let incoming = secret_with_gnu_dummy_primary(SECRET_KEY, 255);
    let incoming_stream = RawPacketStream::parse(&incoming, MAX_RECONCILE_PACKETS)
        .expect("parse incoming GNU dummy-primary TSK");
    let incoming_primary = incoming_stream.raw(&incoming_stream.packets()[0]);

    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(existing),
        Some(incoming),
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("two GNU dummy-primary inputs yield private output");
    let rebuilt = RawPacketStream::parse(private, MAX_RECONCILE_PACKETS)
        .expect("parse merged GNU dummy-primary TSK");

    assert_eq!(rebuilt.raw(&rebuilt.packets()[0]), incoming_primary);
    assert!(!result.existing_secret_contributed);
    assert!(result.incoming_secret_contributed);
}

#[test]
fn trusted_secret_reconciliation_is_deterministic_and_idempotent() {
    let p0 = reprotected_secret("p0", 0x4445_5445_524d_5030);
    let p1 = reprotected_secret("p1", 0x4445_5445_524d_5031);
    let first = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(PUBLIC_KEY.to_vec()),
        Some(p0.clone()),
        Some(p1.clone()),
    ));
    let repeated = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(PUBLIC_KEY.to_vec()),
        Some(p0),
        Some(p1.clone()),
    ));
    assert_eq!(first.public_certificate, repeated.public_certificate);
    assert_eq!(first.private_certificate, repeated.private_certificate);
    assert_eq!(first.primary_fingerprint, repeated.primary_fingerprint);

    let idempotent = success(reconcile(
        Some(first.public_certificate.clone()),
        Some(PUBLIC_KEY.to_vec()),
        first.private_certificate.clone(),
        Some(p1),
    ));
    assert_eq!(first.public_certificate, idempotent.public_certificate);
    assert_eq!(first.private_certificate, idempotent.private_certificate);
    assert!(!idempotent.existing_secret_contributed);
    assert!(!idempotent.incoming_secret_contributed);
}

#[test]
fn reconciliation_retains_sensitive_revoker_declaration_only_in_private_output() {
    let secret = secret_with_sensitive_revoker_declaration();
    let (input_projection, _) =
        project_secret_certificate(&secret).expect("project sensitive secret input");
    assert!(has_sensitive_revoker_declaration(&input_projection));

    let result = success(reconcile(None, None, Some(secret), None));
    assert!(!has_sensitive_revoker_declaration(
        &result.public_certificate
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("secret input yields a rebuilt private certificate");
    let (private_projection, _) =
        project_secret_certificate(private).expect("project rebuilt private certificate");
    assert!(has_sensitive_revoker_declaration(&private_projection));
}

#[test]
fn reconciliation_retains_local_certification_only_in_private_output() {
    let secret = secret_with_local_certification();
    let (input_projection, _) =
        project_secret_certificate(&secret).expect("project locally certified secret input");
    assert!(has_local_certification(&input_projection));

    let result = success(reconcile(None, None, Some(secret), None));
    assert!(!has_local_certification(&result.public_certificate));
    let private = result
        .private_certificate
        .as_deref()
        .expect("secret input yields a rebuilt private certificate");
    let (private_projection, _) = project_secret_certificate(private)
        .expect("project rebuilt locally certified private certificate");
    assert!(has_local_certification(&private_projection));
}

#[test]
fn reconciliation_armor_retains_v4_checksums() {
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(SECRET_KEY.to_vec()),
        None,
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("secret input yields a rebuilt private certificate");

    assert!(armor_has_checksum(&result.public_certificate));
    assert!(armor_has_checksum(private));
    RawPacketStream::parse(&result.public_certificate, MAX_RECONCILE_PACKETS)
        .expect("reparse v4 public output");
    project_secret_certificate(private).expect("reparse v4 private output");
}

#[test]
fn reconciliation_armor_omits_v6_checksums() {
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_700_000_000))
        .primary_user_id("V6 Reconciliation <v6-reconcile@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build v6 key parameters")
        .generate(StdRng::seed_from_u64(0x5636_5245_434f_4e43))
        .expect("generate v6 certificate");
    let public = secret.to_public_key();
    let armor_options = ArmorOptions {
        include_checksum: false,
        ..ArmorOptions::default()
    };
    let public_input = public
        .to_armored_bytes(armor_options.clone())
        .expect("armor v6 public input");
    let private_input = secret
        .to_armored_bytes(armor_options)
        .expect("armor v6 private input");
    let fingerprint = canonicalize_public_certificate(&public_input)
        .expect("canonicalize v6 public input")
        .1;

    let result = success(reconcile_for_fingerprint(
        fingerprint,
        Some(public_input),
        None,
        Some(private_input),
        None,
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("v6 secret input yields a rebuilt private certificate");

    assert!(!armor_has_checksum(&result.public_certificate));
    assert!(!armor_has_checksum(private));
    canonicalize_public_certificate(&result.public_certificate).expect("reparse v6 public output");
    project_secret_certificate(private).expect("reparse v6 private output");
}

#[test]
fn reconcile_accepts_filtered_tsk_with_offline_primary() {
    let filtered = filtered_tsk_fixture();
    let filtered_secret_bodies = secret_packet_bodies(&filtered);
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(filtered),
        None,
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("selected secret subkeys yield a private output");
    let rebuilt =
        RawPacketStream::parse(private, MAX_RECONCILE_PACKETS).expect("parse rebuilt filtered TSK");

    assert_eq!(rebuilt.packets()[0].tag(), 6);
    assert!(!rebuilt.packets().iter().any(|packet| packet.tag() == 5));
    assert!(rebuilt.packets().iter().any(|packet| packet.tag() == 7));
    assert_eq!(secret_packet_bodies(private), filtered_secret_bodies);
    let (projection, _) =
        project_secret_certificate(private).expect("project rebuilt filtered TSK");
    assert_eq!(
        canonicalize_public_certificate(&projection)
            .expect("canonicalize rebuilt projection")
            .0,
        canonicalize_public_certificate(&result.public_certificate)
            .expect("canonicalize public output")
            .0,
    );
    assert!(result.existing_secret_contributed);
    assert!(!result.incoming_secret_contributed);
}

#[test]
fn filtered_tsk_reprotection_prefers_incoming_secret_subkeys() {
    let p0 = filtered_tsk_from(&reprotected_secret("filtered-p0", 0x4649_4c54_4552_5030));
    let p1 = filtered_tsk_from(&reprotected_secret("filtered-p1", 0x4649_4c54_4552_5031));
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(p0),
        Some(p1.clone()),
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("filtered TSK reprotection yields private output");
    let rebuilt = RawPacketStream::parse(private, MAX_RECONCILE_PACKETS)
        .expect("parse reconciled filtered TSK");

    assert_eq!(rebuilt.packets()[0].tag(), PUBLIC_KEY_TAG);
    assert_eq!(secret_packet_bodies(private), secret_packet_bodies(&p1));
    assert!(!result.existing_secret_contributed);
    assert!(result.incoming_secret_contributed);
}

#[test]
fn contribution_flags_are_computed_for_owned_sides() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_RECONCILE_PACKETS).expect("parse public fixture");
    let primary_only = stream
        .packets()
        .iter()
        .find(|packet| packet.tag() == 6)
        .map(|packet| stream.raw(packet).to_vec())
        .expect("public fixture has a primary key packet");
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(primary_only),
        Some(SECRET_KEY.to_vec()),
        None,
    ));

    assert!(result.existing_public_contributed);
    assert!(!result.incoming_public_contributed);
    assert!(result.existing_secret_contributed);
    assert!(!result.incoming_secret_contributed);
}

#[test]
fn newly_learned_public_only_subkey_remains_public_in_private_output() {
    let stream =
        RawPacketStream::parse(SECRET_KEY, MAX_RECONCILE_PACKETS).expect("parse secret fixture");
    let mut primary_only_secret = Vec::new();
    for packet in stream.packets() {
        if packet.tag() == 7 {
            break;
        }
        primary_only_secret.extend_from_slice(stream.raw(packet));
    }
    let result = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        None,
        Some(primary_only_secret.clone()),
        None,
    ));
    let private = result
        .private_certificate
        .as_deref()
        .expect("secret input yields a private output");
    let rebuilt = RawPacketStream::parse(private, MAX_RECONCILE_PACKETS)
        .expect("parse rebuilt private output");

    assert!(rebuilt.packets().iter().any(|packet| packet.tag() == 5));
    assert!(rebuilt.packets().iter().any(|packet| packet.tag() == 14));
    assert!(!rebuilt.packets().iter().any(|packet| packet.tag() == 7));
    assert_eq!(
        secret_packet_bodies(&primary_only_secret),
        secret_packet_bodies(private),
    );
}

#[test]
fn complementary_secret_coverage_is_unioned_independent_of_side_order() {
    let stream =
        RawPacketStream::parse(SECRET_KEY, MAX_RECONCILE_PACKETS).expect("parse secret fixture");
    let mut primary_only_secret = Vec::new();
    for packet in stream.packets() {
        if packet.tag() == 7 {
            break;
        }
        primary_only_secret.extend_from_slice(stream.raw(packet));
    }
    let existing_full = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(PUBLIC_KEY.to_vec()),
        Some(SECRET_KEY.to_vec()),
        Some(primary_only_secret.clone()),
    ));
    let incoming_full = success(reconcile(
        Some(PUBLIC_KEY.to_vec()),
        Some(PUBLIC_KEY.to_vec()),
        Some(primary_only_secret),
        Some(SECRET_KEY.to_vec()),
    ));
    let existing_full_private = existing_full
        .private_certificate
        .as_deref()
        .expect("union has private output");
    let incoming_full_private = incoming_full
        .private_certificate
        .as_deref()
        .expect("swapped union has private output");

    assert_eq!(
        secret_packet_bodies(SECRET_KEY),
        secret_packet_bodies(existing_full_private),
    );
    assert_eq!(existing_full_private, incoming_full_private);
    assert!(existing_full.existing_secret_contributed);
    assert!(!existing_full.incoming_secret_contributed);
    assert!(!incoming_full.existing_secret_contributed);
    assert!(incoming_full.incoming_secret_contributed);
}

#[test]
fn no_secret_input_produces_no_private_output() {
    let result = success(reconcile(Some(PUBLIC_KEY.to_vec()), None, None, None));

    assert!(result.private_certificate.is_none());
    assert!(!result.existing_secret_contributed);
    assert!(!result.incoming_secret_contributed);
}
