use pgp::{
    crypto::hash::HashAlgorithm,
    packet::{SignatureConfig, SignatureType, Subpacket, SubpacketData},
    types::{KeyDetails, Timestamp},
};
use prost::bytes::Bytes;

use super::*;
use crate::{
    openpgp::adapter::wire::{
        Message as _, OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial,
    },
    openpgp::certificate::{
        UserIdCertificationBuilder, canonicalize_public_certificate, parse_single_public,
        parse_single_secret, project_secret_certificate, rebuild_secret_certificate,
    },
    openpgp::crypto::verification::signature_config_is_non_exportable,
    openpgp::packet::RawPacketStream,
    openpgp::policy::{
        MutationAuthorizationError, OpenPgpPolicyBudget, OpenPgpPolicyError, all_components,
        validate_certificate,
    },
};

const CREATED: u64 = 1_700_000_000;
const REFERENCE: u64 = CREATED + 120;
const FIRST_USER_ID: &str = "First Identity <first@example.test>";
const SECOND_USER_ID: &str = "Second Identity <second@example.test>";

fn generated_material() -> OpenPgpKeyMaterial {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: FIRST_USER_ID.to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: CREATED,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    OpenPgpKeyMaterial::decode(generated.as_slice()).expect("decode material")
}

fn material_with_retained_second_user_id(
    certifications: Vec<(u64, Vec<Subpacket>)>,
) -> OpenPgpKeyMaterial {
    let mut material = generated_material();
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("resolve signer");
    let signer = signer.as_ref();
    let mut user_id_body = None;
    let signature_bodies = certifications
        .into_iter()
        .map(|(creation_time, extra_hashed_subpackets)| {
            let mut config = SignatureConfig::v4(
                SignatureType::CertPositive,
                signer.algorithm(),
                HashAlgorithm::Sha256,
            );
            config.hashed_subpackets = vec![
                Subpacket::critical(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    u32::try_from(creation_time).expect("test timestamp"),
                )))
                .expect("creation time"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
                    .expect("issuer fingerprint"),
            ];
            config.hashed_subpackets.extend(extra_hashed_subpackets);
            config.unhashed_subpackets = vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
                    .expect("issuer key id"),
            ];
            let certification = UserIdCertificationBuilder::new(
                signer,
                &public.primary_key,
                SECOND_USER_ID,
                config,
            )
            .build()
            .expect("certify retained second user ID");
            user_id_body.get_or_insert_with(|| certification.user_id.id().to_vec());
            serialize_packet_body(&certification.signature).expect("serialize certification")
        })
        .collect();
    let mut packet_set = crate::openpgp::certificate::parse_single_certificate_packet_set(
        &material.public_key_armored,
    )
    .expect("parse generated certificate");
    packet_set
        .apply_additions(&[CertificateAddition::Identity {
            tag: USER_ID_TAG,
            body: user_id_body.expect("at least one certification"),
            signature_bodies,
        }])
        .expect("add retained second identity");
    let canonical = packet_set.finalize().expect("finalize retained fixture");
    let (_, overlay) = project_secret_certificate(&material.private_key_armored)
        .expect("project generated secret certificate");
    let private = rebuild_secret_certificate(&canonical.retained_bytes, &overlay)
        .expect("rebuild retained secret certificate");
    material.public_key_armored = armor_key_packets(&canonical.bytes, BlockType::PublicKey)
        .expect("armor retained public certificate");
    material.private_key_armored = armor_key_packets(&private, BlockType::PrivateKey)
        .expect("armor retained secret certificate");
    material
}

fn material_with_local_second_user_id() -> OpenPgpKeyMaterial {
    material_with_retained_second_user_id(vec![(
        CREATED + 1,
        vec![
            Subpacket::regular(SubpacketData::ExportableCertification(true))
                .expect("exportable certification subpacket"),
            Subpacket::critical(SubpacketData::ExportableCertification(false))
                .expect("non-exportable certification subpacket"),
        ],
    )])
}

fn signature_has_local_marker(signature: &pgp::packet::Signature) -> bool {
    signature
        .config()
        .is_some_and(signature_config_is_non_exportable)
}

fn document_contains_user_id(document: &[u8], user_id: &str) -> bool {
    let stream = RawPacketStream::parse(document, 64).expect("parse certificate packets");
    stream.packets().iter().any(|packet| {
        packet.tag() == USER_ID_TAG && stream.body(packet).as_slice() == user_id.as_bytes()
    })
}

#[test]
fn mutation_authorization_and_policy_failures_keep_stable_reasons() {
    assert_eq!(
        UserIdRevocationFailure::from(MutationAuthorizationError::Revoked),
        UserIdRevocationFailure::CertificateRevoked,
    );
    assert_eq!(
        UserIdRevocationFailure::from(MutationAuthorizationError::IndeterminateRevocation),
        UserIdRevocationFailure::UnresolvedRevocationAuthority,
    );
    assert_eq!(
        UserIdRevocationFailure::from(OpenPgpPolicyError::ResourceLimit),
        UserIdRevocationFailure::ResourceLimit,
    );
}

fn add_user_id(
    material: &OpenPgpKeyMaterial,
    user_id: &str,
    creation_time: u64,
    extra_hashed_subpackets: Vec<Subpacket>,
) -> Vec<u8> {
    add_user_id_certifications(
        material,
        user_id,
        vec![(creation_time, extra_hashed_subpackets)],
    )
}

fn add_user_id_certifications(
    material: &OpenPgpKeyMaterial,
    user_id: &str,
    certifications: Vec<(u64, Vec<Subpacket>)>,
) -> Vec<u8> {
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("resolve signer");
    let signer = signer.as_ref();
    let mut user_id_body = None;
    let signature_bodies = certifications
        .into_iter()
        .map(|(creation_time, extra_hashed_subpackets)| {
            let mut config = SignatureConfig::v4(
                SignatureType::CertPositive,
                signer.algorithm(),
                HashAlgorithm::Sha256,
            );
            config.hashed_subpackets = vec![
                Subpacket::critical(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    u32::try_from(creation_time).expect("test timestamp"),
                )))
                .expect("creation time"),
                Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
                    .expect("issuer fingerprint"),
            ];
            config.hashed_subpackets.extend(extra_hashed_subpackets);
            config.unhashed_subpackets = vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
                    .expect("issuer key id"),
            ];
            let certification =
                UserIdCertificationBuilder::new(signer, &public.primary_key, user_id, config)
                    .build()
                    .expect("certify user id");
            user_id_body.get_or_insert_with(|| certification.user_id.id().to_vec());
            serialize_packet_body(&certification.signature).expect("serialize certification")
        })
        .collect();
    let mut packet_set = crate::openpgp::certificate::parse_single_certificate_packet_set(
        &material.public_key_armored,
    )
    .expect("parse fixture certificate");
    packet_set
        .apply_additions(&[CertificateAddition::Identity {
            tag: USER_ID_TAG,
            body: user_id_body.expect("at least one certification"),
            signature_bodies,
        }])
        .expect("add second identity");
    let merged = packet_set
        .finalize()
        .expect("finalize fixture certificate")
        .bytes;
    armor_key_packets(&merged, BlockType::PublicKey).expect("armor certificate")
}

#[test]
fn older_non_revocable_certification_blocks_user_id_revocation() {
    let material = generated_material();
    let public_key = add_user_id_certifications(
        &material,
        SECOND_USER_ID,
        vec![
            (
                CREATED + 1,
                vec![
                    Subpacket::regular(SubpacketData::Revocable(false))
                        .expect("non-revocable subpacket"),
                ],
            ),
            (CREATED + 2, Vec::new()),
        ],
    );

    let Err(error) = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("the older non-revocable certification must survive");
    };

    assert_eq!(error, UserIdRevocationFailure::NonRevocable);
}

#[test]
fn effective_non_revocable_certification_blocks_user_id_revocation() {
    let material = generated_material();
    let public_key = add_user_id_certifications(
        &material,
        SECOND_USER_ID,
        vec![
            (CREATED + 1, Vec::new()),
            (
                CREATED + 2,
                vec![
                    Subpacket::regular(SubpacketData::Revocable(false))
                        .expect("non-revocable subpacket"),
                ],
            ),
        ],
    );

    let Err(error) = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("the effective non-revocable certification must be preserved");
    };

    assert_eq!(error, UserIdRevocationFailure::NonRevocable);
}

#[test]
fn revoking_local_user_id_keeps_the_mutation_private() {
    let material = material_with_local_second_user_id();

    let success = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(USER_ID_TAG, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("revoke local identity");

    assert!(success.changed);
    assert!(success.revocation_certificate_armored.is_empty());
    let returned_public = parse_single_public(&success.key_material.public_key_armored)
        .expect("parse returned public certificate");
    assert!(
        returned_public
            .details
            .users
            .iter()
            .flat_map(|user| &user.signatures)
            .all(|signature| !signature_has_local_marker(signature)),
    );
    let transferable = canonicalize_public_certificate(&success.key_material.public_key_armored)
        .expect("export returned public certificate")
        .0;
    assert!(!document_contains_user_id(&transferable, SECOND_USER_ID));

    let (private_projection, _) =
        project_secret_certificate(&success.key_material.private_key_armored)
            .expect("project returned private certificate");
    let retained = parse_single_public(&private_projection).expect("parse private projection");
    let local = retained
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("retain revoked local identity");
    assert!(local.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertRevocation)
            && signature_has_local_marker(signature)
    }));
    let candidates = all_components(std::slice::from_ref(&retained));
    let policy = validate_certificate(
        &retained,
        &candidates,
        REFERENCE,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("validate retained local revocation");
    assert!(
        policy
            .user_id(SECOND_USER_ID.as_bytes())
            .is_some_and(|identity| identity.revocation_status.is_revoked()),
    );
}

#[test]
fn revoking_transport_visible_user_id_exports_revocation_despite_older_local_certification() {
    let material = material_with_retained_second_user_id(vec![
        (
            CREATED + 1,
            vec![
                Subpacket::critical(SubpacketData::ExportableCertification(false))
                    .expect("non-exportable certification subpacket"),
            ],
        ),
        (
            CREATED + 2,
            vec![
                Subpacket::critical(SubpacketData::ExportableCertification(false))
                    .expect("non-exportable certification subpacket"),
                Subpacket::regular(SubpacketData::ExportableCertification(true))
                    .expect("exportable certification subpacket"),
            ],
        ),
    ]);

    let success = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(USER_ID_TAG, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("revoke transport-visible identity");

    assert!(success.changed);
    assert!(!success.revocation_certificate_armored.is_empty());
    let artifact = parse_single_public(&success.revocation_certificate_armored)
        .expect("parse revocation artifact");
    let artifact_identity = artifact
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("artifact contains transport-visible identity");
    assert!(artifact_identity.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertRevocation)
            && !signature_has_local_marker(signature)
    }));

    let returned = parse_single_public(&success.key_material.public_key_armored)
        .expect("parse returned public certificate");
    let returned_identity = returned
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("retain transport-visible identity");
    assert!(returned_identity.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertRevocation)
            && !signature_has_local_marker(signature)
    }));
    assert!(
        returned
            .details
            .users
            .iter()
            .flat_map(|user| &user.signatures)
            .all(|signature| !signature_has_local_marker(signature)),
    );
    let candidates = all_components(std::slice::from_ref(&returned));
    let policy = validate_certificate(
        &returned,
        &candidates,
        REFERENCE,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("validate exported revocation");
    assert!(
        policy
            .user_id(SECOND_USER_ID.as_bytes())
            .is_some_and(|identity| identity.revocation_status.is_revoked()),
    );

    let (private_projection, _) =
        project_secret_certificate(&success.key_material.private_key_armored)
            .expect("project returned private certificate");
    let retained = parse_single_public(&private_projection).expect("parse private projection");
    let retained_identity = retained
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("retain private identity history");
    assert!(retained_identity.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertPositive)
            && signature_has_local_marker(signature)
    }));
}

#[test]
fn user_id_revocation_is_minimal_persistent_and_idempotent() {
    let material = generated_material();
    let public_key = add_user_id(&material, SECOND_USER_ID, CREATED + 1, Vec::new());
    let request = UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, FIRST_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    };
    let success = revoke_user_id_request(request).expect("revoke first identity");
    assert!(success.changed);
    assert!(!success.revocation_certificate_armored.is_empty());
    assert_eq!(success.effective_at_epoch_seconds, REFERENCE);
    let updated = success.key_material;

    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let candidates = all_components(std::slice::from_ref(&reparsed));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(&reparsed, &candidates, REFERENCE, &mut budget)
        .expect("inspect updated certificate");
    assert_eq!(
        policy.verified_user_ids_for_test(),
        vec![SECOND_USER_ID.to_owned()],
    );

    let repeated = revoke_user_id_request(UserIdRevocationInput {
        private_key: updated.private_key_armored.clone(),
        public_key: updated.public_key_armored.clone(),
        expected_primary_fingerprint: updated.fingerprint.clone(),
        identity_id: identity_id(13, FIRST_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: u64::from(u32::MAX) + 1,
    })
    .expect("repeat effective revocation outside the v4 timestamp range");
    assert!(!repeated.changed);
    assert!(repeated.revocation_certificate_armored.is_empty());
    assert_eq!(repeated.effective_at_epoch_seconds, REFERENCE);
    let repeated_material = repeated.key_material;
    assert_eq!(
        repeated_material.private_key_armored,
        updated.private_key_armored
    );
    assert_eq!(
        repeated_material.public_key_armored,
        updated.public_key_armored
    );
    assert_eq!(repeated_material.fingerprint, updated.fingerprint);
}

#[test]
fn v4_last_user_id_is_rejected() {
    let material = generated_material();
    let Err(error) = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, FIRST_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("last user id must be preserved");
    };
    assert_eq!(error, UserIdRevocationFailure::LastUserId);
}

#[test]
fn future_dated_user_id_is_revoked_immediately_after_its_certification() {
    let material = generated_material();
    let public_key = add_user_id(&material, SECOND_USER_ID, REFERENCE + 60, Vec::new());

    let success = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("future certification should receive a chronologically later revocation");
    assert!(success.changed);
    assert_eq!(success.effective_at_epoch_seconds, REFERENCE + 61);

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let candidates = all_components(std::slice::from_ref(&reparsed));
    let mut budget = OpenPgpPolicyBudget::default();
    let before = validate_certificate(&reparsed, &candidates, REFERENCE, &mut budget)
        .expect("evaluate before scheduled revocation");
    assert!(
        !before
            .user_id(SECOND_USER_ID.as_bytes())
            .expect("second identity")
            .revocation_status
            .is_revoked()
    );
    let mut budget = OpenPgpPolicyBudget::default();
    let effective = validate_certificate(&reparsed, &candidates, REFERENCE + 61, &mut budget)
        .expect("evaluate scheduled revocation");
    assert!(
        effective
            .user_id(SECOND_USER_ID.as_bytes())
            .expect("second identity")
            .revocation_status
            .is_revoked()
    );

    let repeated = revoke_user_id_request(UserIdRevocationInput {
        private_key: updated.private_key_armored.clone(),
        public_key: updated.public_key_armored.clone(),
        expected_primary_fingerprint: updated.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("the authenticated scheduled revocation is idempotent");
    assert!(!repeated.changed);
    assert_eq!(repeated.effective_at_epoch_seconds, REFERENCE + 61);
}

#[test]
fn a_far_future_certification_cannot_push_the_revocation_out_of_reach() {
    // A revocation has to be dated after the certification it supersedes.
    // Honouring a certification parked far in the future would emit a
    // revocation that only takes effect then, so the bump is bounded and
    // the request fails with its typed time error instead.
    let material = generated_material();
    let public_key = add_user_id(&material, SECOND_USER_ID, REFERENCE + 3_600, Vec::new());

    let Err(error) = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("a far-future certification must not be superseded silently");
    };
    assert_eq!(error, UserIdRevocationFailure::TimeConflict);
}

#[test]
fn policy_inactive_user_id_receives_durable_revocation_evidence() {
    let material = generated_material();
    let unsupported_critical = Subpacket::critical(SubpacketData::Experimental(
        100,
        Bytes::from_static(b"unknown critical policy"),
    ))
    .expect("experimental critical subpacket");
    let public_key = add_user_id(
        &material,
        SECOND_USER_ID,
        CREATED + 1,
        vec![unsupported_critical],
    );

    let success = revoke_user_id_request(UserIdRevocationInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("policy-inactive identity should still receive a self-revocation");

    assert!(success.changed);
    assert!(!success.revocation_certificate_armored.is_empty());
}
