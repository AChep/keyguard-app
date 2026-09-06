use super::*;
use crate::openpgp::adapter::wire::{
    Message as _, OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial,
};
use crate::openpgp::certificate::{
    canonicalize_public_certificate, parse_single_public, parse_single_secret,
    project_secret_certificate, rebuild_secret_certificate,
};
use crate::openpgp::crypto::verification::{is_certification, signature_config_is_non_exportable};
use crate::openpgp::packet::{PUBLIC_SUBKEY_TAG, RawPacketStream};
use crate::openpgp::policy::{
    MutationAuthorizationError, OpenPgpPolicyBudget, OpenPgpPolicyError, PolicyContext,
    PolicySelection, all_components, select_newest_policy_signature, signature_expired,
    validate_certificate,
};
use pgp::composed::SignedPublicKey;
use pgp::packet::{Subpacket, SubpacketData};
use pgp::ser::Serialize;
use pgp::types::{SignedUser, Tag, Timestamp};

/// Test-only mirror of the policy-effective certification lookup, for
/// fixtures that build certificates by hand.
fn select_effective_certification<'a>(
    certificate: &'a SignedPublicKey,
    user: &'a SignedUser,
    reference_time: u64,
) -> Result<&'a Signature, UserIdReplacementFailure> {
    let verified = user.signatures.iter().filter(|signature| {
        is_certification(signature.typ())
            && signature
                .verify_certification(&certificate.primary_key, Tag::UserId, &user.id)
                .is_ok()
            && !signature_expired(signature, reference_time)
    });
    match select_newest_policy_signature(verified, PolicyContext::Identity, |_| Ok(false))? {
        PolicySelection::Selected { signature, .. } => Ok(signature),
        PolicySelection::Missing => Err(UserIdReplacementFailure::MissingSelfSignature),
        PolicySelection::Conflict => Err(UserIdReplacementFailure::PolicyConflict),
    }
}

/// Applies hand-built additions to a certificate document, mirroring what
/// the production path does with the same packet-set API.
fn apply_test_additions(document: &[u8], additions: &[CertificateAddition]) -> Vec<u8> {
    let mut packet_set = crate::openpgp::certificate::parse_single_certificate_packet_set(document)
        .expect("parse fixture certificate");
    packet_set
        .apply_additions(additions)
        .expect("apply fixture additions");
    packet_set
        .finalize()
        .expect("finalize fixture certificate")
        .bytes
}

const CREATED: u64 = 1_700_000_000;
const REFERENCE: u64 = CREATED + 120;
const OLD_USER_ID: &str = "Old Identity <old@example.test>";
const SECOND_USER_ID: &str = "Second Identity <second@example.test>";
const NEW_USER_ID: &str = "New Identity <new@example.test>";

fn generated_material() -> OpenPgpKeyMaterial {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: OLD_USER_ID.to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: CREATED,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    OpenPgpKeyMaterial::decode(generated.as_slice()).expect("decode material")
}

fn material_with_secondary_user_id_exportability(
    hashed: &[bool],
    unhashed: &[bool],
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
    let template = select_effective_certification(&public, &public.details.users[0], REFERENCE)
        .expect("select primary template");
    let mut config = new_user_id_certification_config(
        template,
        template,
        signer,
        Timestamp::from_secs(u32::try_from(CREATED + 1).expect("test timestamp")),
        false,
    )
    .expect("build local secondary configuration");
    config.hashed_subpackets.extend(
        hashed
            .iter()
            .copied()
            .map(exportable_certification_subpacket),
    );
    config.unhashed_subpackets.extend(
        unhashed
            .iter()
            .copied()
            .map(exportable_certification_subpacket),
    );
    let certification =
        UserIdCertificationBuilder::new(signer, &public.primary_key, SECOND_USER_ID, config)
            .build()
            .expect("certify secondary user ID");
    let mut packet_set = crate::openpgp::certificate::parse_single_certificate_packet_set(
        &material.public_key_armored,
    )
    .expect("parse generated certificate");
    packet_set
        .apply_additions(&[CertificateAddition::Identity {
            tag: USER_ID_TAG,
            body: certification.user_id.id().to_vec(),
            signature_bodies: vec![
                serialize_packet_body(&certification.signature)
                    .expect("serialize secondary certification"),
            ],
        }])
        .expect("add secondary identity");
    let canonical = packet_set.finalize().expect("finalize secondary fixture");
    let (_, overlay) = project_secret_certificate(&material.private_key_armored)
        .expect("project generated secret certificate");
    let private = rebuild_secret_certificate(&canonical.retained_bytes, &overlay)
        .expect("rebuild secondary secret certificate");
    material.public_key_armored = armor_key_packets(&canonical.bytes, BlockType::PublicKey)
        .expect("armor secondary public certificate");
    material.private_key_armored = armor_key_packets(&private, BlockType::PrivateKey)
        .expect("armor secondary secret certificate");
    material
}

fn material_with_local_secondary_user_id() -> OpenPgpKeyMaterial {
    material_with_secondary_user_id_exportability(&[true, false], &[])
}

fn exportable_certification_subpacket(exportable: bool) -> Subpacket {
    let data = SubpacketData::ExportableCertification(exportable);
    if exportable {
        Subpacket::regular(data).expect("exportable certification subpacket")
    } else {
        Subpacket::critical(data).expect("non-exportable certification subpacket")
    }
}

fn signature_has_local_marker(signature: &Signature) -> bool {
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
fn renaming_onto_a_bare_user_id_packet_reports_a_duplicate_identity() {
    // The target name already exists as a packet, even though it carries no
    // certification. Adding it again is impossible, and reporting a missing
    // self-signature described the wrong object entirely.
    let material = generated_material();
    let document = crate::openpgp::certificate::parse_single_certificate_packet_set(
        &material.public_key_armored,
    )
    .expect("parse generated certificate")
    .finalize()
    .expect("finalize generated certificate")
    .bytes;
    let bare =
        pgp::packet::UserId::from_str(Default::default(), NEW_USER_ID).expect("bare user id");
    let body = bare.id().to_vec();
    let mut bare_packet = Vec::new();
    pgp::packet::PacketHeader::new_fixed(
        Tag::UserId,
        u32::try_from(body.len()).expect("user id length"),
    )
    .to_writer(&mut bare_packet)
    .expect("write bare user id header");
    bare_packet.extend_from_slice(&body);

    let stream =
        RawPacketStream::parse(&document, 8 * 1024).expect("parse finalized certificate packets");
    let first_subkey = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == PUBLIC_SUBKEY_TAG)
        .expect("generated certificate has a subkey");
    let mut document_with_bare_identity = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        if index == first_subkey {
            document_with_bare_identity.extend_from_slice(&bare_packet);
        }
        document_with_bare_identity.extend_from_slice(stream.raw(packet));
    }

    let Err(error) = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key: armor_key_packets(&document_with_bare_identity, BlockType::PublicKey)
            .expect("armor certificate"),
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("a name that already has a packet cannot be added again");
    };
    assert_eq!(error, UserIdReplacementFailure::DuplicateIdentity);
}

#[test]
fn mutation_authorization_and_policy_failures_keep_stable_reasons() {
    assert_eq!(
        UserIdReplacementFailure::from(MutationAuthorizationError::Revoked),
        UserIdReplacementFailure::CertificateRevoked,
    );
    assert_eq!(
        UserIdReplacementFailure::from(MutationAuthorizationError::IndeterminateRevocation),
        UserIdReplacementFailure::UnresolvedRevocationAuthority,
    );
    assert_eq!(
        UserIdReplacementFailure::from(OpenPgpPolicyError::ResourceLimit),
        UserIdReplacementFailure::ResourceLimit,
    );
}

#[test]
fn primary_policy_snapshot_distinguishes_missing_from_negative_features() {
    let snapshot = |features| PrimaryKeyPolicySnapshot {
        authenticated: true,
        revocation_status: crate::openpgp::policy::RevocationStatus::NotRevoked,
        key_flags: None,
        key_expiration_seconds: None,
        features,
        allows_gnupg_ocb: false,
        preferred_encryption_modes: crate::openpgp::policy::EncryptionModePreferences::Missing,
    };

    assert_ne!(
        snapshot(crate::openpgp::policy::AuthenticatedFeatures::Missing),
        snapshot(crate::openpgp::policy::AuthenticatedFeatures::Present(
            vec![0x01].into_boxed_slice(),
        )),
    );
}

#[test]
fn replacement_canonically_resolves_tied_conflicting_certifications() {
    let material = generated_material();
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("resolve signer");
    let signer = signer.as_ref();
    let primary_user = &public.details.users[0];
    let template = select_effective_certification(&public, primary_user, REFERENCE)
        .expect("select primary template");
    let tied_certification = |flags: KeyFlags| {
        let mut config = existing_user_id_recertification_config(
            template,
            signer,
            Timestamp::from_secs((CREATED + 1) as u32),
            true,
        )
        .expect("build tied certification configuration");
        config
            .hashed_subpackets
            .retain(|subpacket| !matches!(subpacket.data, SubpacketData::KeyFlags(_)));
        config
            .hashed_subpackets
            .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags"));
        UserIdCertificationBuilder::for_existing(
            signer,
            &public.primary_key,
            &primary_user.id,
            config,
        )
        .build()
        .expect("build tied certification")
        .signature
    };
    let mut signing = KeyFlags::default();
    signing.set_sign(true);
    let mut certifying = KeyFlags::default();
    certifying.set_certify(true);
    let tied = [tied_certification(signing), tied_certification(certifying)];
    for order in [[0, 1], [1, 0]] {
        let additions = order.map(|index| CertificateAddition::Signature {
            owner: CertificateSignatureOwner::Identity {
                tag: USER_ID_TAG,
                body: primary_user.id.id().to_vec(),
            },
            body: serialize_packet_body(&tied[index]).expect("serialize tied certification"),
        });
        let merged = apply_test_additions(&material.public_key_armored, &additions);

        let success = replace_user_id_request(UserIdReplacementInput {
            private_key: material.private_key_armored.clone(),
            public_key: armor_key_packets(&merged, BlockType::PublicKey)
                .expect("armor tied certificate"),
            expected_primary_fingerprint: material.fingerprint.clone(),
            old_identity_id: identity_id(USER_ID_TAG, OLD_USER_ID.as_bytes()),
            new_user_id: NEW_USER_ID.to_owned(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: REFERENCE,
        })
        .expect("canonical tied policy permits replacement");

        assert!(success.changed);
        assert_eq!(success.primary_user_id, NEW_USER_ID);
    }
}

fn add_secondary_user_id(
    material: &OpenPgpKeyMaterial,
    demote_primary: bool,
    non_revocable_primary: bool,
    secondary_is_primary: bool,
) -> Vec<u8> {
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("resolve signer");
    let signer = signer.as_ref();
    let primary_user = &public.details.users[0];
    let policy_template = select_effective_certification(&public, primary_user, REFERENCE)
        .expect("select primary template");
    let timestamp = |seconds| {
        u32::try_from(seconds)
            .map(Timestamp::from_secs)
            .expect("test timestamp")
    };

    let secondary_config = new_user_id_certification_config(
        policy_template,
        policy_template,
        signer,
        timestamp(CREATED + 1),
        secondary_is_primary,
    )
    .expect("build secondary configuration");
    let secondary = UserIdCertificationBuilder::new(
        signer,
        &public.primary_key,
        SECOND_USER_ID,
        secondary_config,
    )
    .build()
    .expect("certify secondary user ID");

    let attached_signatures = if demote_primary {
        let mut primary_config = existing_user_id_recertification_config(
            policy_template,
            signer,
            timestamp(CREATED + 2),
            false,
        )
        .expect("build fallback-primary configuration");
        if non_revocable_primary {
            primary_config.hashed_subpackets.push(
                Subpacket::critical(SubpacketData::Revocable(false))
                    .expect("non-revocable subpacket"),
            );
        }
        let primary = UserIdCertificationBuilder::new(
            signer,
            &public.primary_key,
            OLD_USER_ID,
            primary_config,
        )
        .build()
        .expect("re-certify fallback primary");
        vec![CertificateAddition::Signature {
            owner: CertificateSignatureOwner::Identity {
                tag: USER_ID_TAG,
                body: primary_user.id.id().to_vec(),
            },
            body: serialize_packet_body(&primary.signature).expect("serialize primary"),
        }]
    } else {
        Vec::new()
    };
    let mut additions = attached_signatures;
    additions.push(CertificateAddition::Identity {
        tag: USER_ID_TAG,
        body: secondary.user_id.id().to_vec(),
        signature_bodies: vec![
            serialize_packet_body(&secondary.signature).expect("serialize secondary"),
        ],
    });
    let merged = apply_test_additions(&material.public_key_armored, &additions);
    armor_key_packets(&merged, BlockType::PublicKey).expect("armor certificate")
}

fn recertify_primary_user_id(
    material: &OpenPgpKeyMaterial,
    certifications: &[(u64, bool)],
) -> Vec<u8> {
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("resolve signer");
    let signer = signer.as_ref();
    let primary_user = &public.details.users[0];
    let policy_template = select_effective_certification(&public, primary_user, REFERENCE)
        .expect("select primary template");
    let additions = certifications
        .iter()
        .map(|(creation_time, non_revocable)| {
            let mut config = existing_user_id_recertification_config(
                policy_template,
                signer,
                u32::try_from(*creation_time)
                    .map(Timestamp::from_secs)
                    .expect("test timestamp"),
                true,
            )
            .expect("build primary recertification configuration");
            if *non_revocable {
                config.hashed_subpackets.push(
                    Subpacket::regular(SubpacketData::Revocable(false))
                        .expect("non-revocable subpacket"),
                );
            }
            let certification =
                UserIdCertificationBuilder::new(signer, &public.primary_key, OLD_USER_ID, config)
                    .build()
                    .expect("re-certify primary user ID");
            CertificateAddition::Signature {
                owner: CertificateSignatureOwner::Identity {
                    tag: USER_ID_TAG,
                    body: primary_user.id.id().to_vec(),
                },
                body: serialize_packet_body(&certification.signature)
                    .expect("serialize primary recertification"),
            }
        })
        .collect::<Vec<_>>();
    let merged = apply_test_additions(&material.public_key_armored, &additions);
    armor_key_packets(&merged, BlockType::PublicKey).expect("armor certificate")
}

#[test]
fn older_non_revocable_certification_blocks_user_id_replacement() {
    let material = generated_material();
    let public_key =
        recertify_primary_user_id(&material, &[(CREATED + 1, true), (CREATED + 2, false)]);

    let Err(error) = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("the older non-revocable certification must survive");
    };

    assert_eq!(error, UserIdReplacementFailure::NonRevocable);
}

#[test]
fn effective_non_revocable_certification_blocks_user_id_replacement() {
    let material = generated_material();
    let public_key =
        recertify_primary_user_id(&material, &[(CREATED + 1, false), (CREATED + 2, true)]);

    let Err(error) = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    }) else {
        panic!("the effective non-revocable certification must be preserved");
    };

    assert_eq!(error, UserIdReplacementFailure::NonRevocable);
}

#[test]
fn replacing_local_user_id_keeps_the_mutation_private() {
    let material = material_with_local_secondary_user_id();

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(USER_ID_TAG, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("replace local identity");

    assert!(success.changed);
    assert!(success.replacement_certificate_armored.is_empty());
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
    assert!(!document_contains_user_id(&transferable, NEW_USER_ID));

    let (private_projection, _) =
        project_secret_certificate(&success.key_material.private_key_armored)
            .expect("project returned private certificate");
    let retained = parse_single_public(&private_projection).expect("parse private projection");
    let old = retained
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("retain old local identity");
    assert!(old.signatures.iter().any(|signature| {
        signature.typ() == Some(pgp::packet::SignatureType::CertRevocation)
            && signature_has_local_marker(signature)
    }));
    let new = retained
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(NEW_USER_ID))
        .expect("retain replacement local identity");
    assert!(signature_has_local_marker(
        select_effective_certification(&retained, new, REFERENCE)
            .expect("select replacement certification"),
    ));
    let candidates = all_components(std::slice::from_ref(&retained));
    let policy = validate_certificate(
        &retained,
        &candidates,
        REFERENCE,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("validate retained local replacement");
    assert!(
        policy
            .user_id(SECOND_USER_ID.as_bytes())
            .is_some_and(|identity| identity.revocation_status.is_revoked()),
    );
    assert!(
        policy
            .authenticated_user_ids()
            .any(|identity| identity.packet_body() == NEW_USER_ID.as_bytes()),
    );
}

#[test]
fn replacing_user_id_with_final_hashed_true_exports_replacement_and_revocation() {
    let material = material_with_secondary_user_id_exportability(&[false, true], &[false]);

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(USER_ID_TAG, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("replace transport-visible identity");

    assert!(success.changed);
    assert!(!success.replacement_certificate_armored.is_empty());
    let artifact = parse_single_public(&success.replacement_certificate_armored)
        .expect("parse replacement artifact");
    let old = artifact
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(SECOND_USER_ID))
        .expect("artifact contains old identity");
    assert!(old.signatures.iter().any(|signature| {
        signature.typ() == Some(pgp::packet::SignatureType::CertRevocation)
            && !signature_has_local_marker(signature)
    }));
    let new = artifact
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(NEW_USER_ID))
        .expect("artifact contains replacement identity");
    assert!(!signature_has_local_marker(
        select_effective_certification(&artifact, new, REFERENCE)
            .expect("select replacement certification"),
    ));
    assert!(document_contains_user_id(
        &success.key_material.public_key_armored,
        NEW_USER_ID,
    ));
}

#[test]
fn replaces_last_primary_user_id_atomically_and_idempotently() {
    let material = generated_material();
    let request = UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    };
    let success = replace_user_id_request(request).expect("replace identity");
    assert!(success.changed);
    assert!(!success.replacement_certificate_armored.is_empty());
    assert_eq!(success.effective_at_epoch_seconds, REFERENCE);
    assert_eq!(success.primary_user_id, NEW_USER_ID);
    assert_eq!(
        success.new_identity_id,
        identity_id(13, NEW_USER_ID.as_bytes())
    );
    let artifact = RawPacketStream::parse(&success.replacement_certificate_armored, 8)
        .expect("parse replacement artifact");
    let artifact_tags = artifact
        .packets()
        .iter()
        .map(|packet| packet.tag())
        .collect::<Vec<_>>();
    assert_eq!(artifact_tags.first(), Some(&6));
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 13).count(), 2);
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 2).count(), 2);
    assert_eq!(artifact_tags.len(), 5);

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let candidates = all_components(std::slice::from_ref(&reparsed));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(&reparsed, &candidates, REFERENCE, &mut budget)
        .expect("inspect updated certificate");
    assert_eq!(
        policy.verified_user_ids_for_test(),
        vec![NEW_USER_ID.to_owned()],
    );
    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some(NEW_USER_ID),
    );

    let repeated = replace_user_id_request(UserIdReplacementInput {
        private_key: updated.private_key_armored.clone(),
        public_key: updated.public_key_armored.clone(),
        expected_primary_fingerprint: updated.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("repeat replacement");
    assert!(!repeated.changed);
    assert!(repeated.replacement_certificate_armored.is_empty());
    assert_eq!(repeated.primary_user_id, NEW_USER_ID);
}

#[test]
fn replaces_deterministically_selected_primary_when_multiple_are_explicit() {
    let material = generated_material();
    let public_key = add_secondary_user_id(&material, false, false, true);
    let before_snapshot = {
        let before = parse_single_public(&public_key).expect("parse certificate");
        let candidates = all_components(std::slice::from_ref(&before));
        let policy = validate_certificate(
            &before,
            &candidates,
            CREATED + 1,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("inspect certificate");
        let explicit_primary_count = policy
            .authenticated_user_ids()
            .filter(|user_id| {
                policy
                    .user_id_at(user_id.index())
                    .and_then(|identity| identity.effective_signature)
                    .is_some_and(signature_is_primary)
            })
            .count();
        assert_eq!(explicit_primary_count, 2);
        assert_eq!(
            policy.primary_user_id_for_test().as_deref(),
            Some(SECOND_USER_ID),
            "the newer explicit-primary certification wins",
        );
        PrimaryKeyPolicySnapshot::capture(&policy)
    };

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: CREATED + 1,
    })
    .expect("replace the deterministically selected primary identity");
    assert!(success.changed);
    assert_eq!(success.effective_at_epoch_seconds, CREATED + 2);
    assert_eq!(success.primary_user_id, NEW_USER_ID);

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let candidates = all_components(std::slice::from_ref(&reparsed));
    let policy = validate_certificate(
        &reparsed,
        &candidates,
        CREATED + 2,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect updated certificate");
    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some(NEW_USER_ID),
    );
    assert!(
        PrimaryKeyPolicySnapshot::capture(&policy) == before_snapshot,
        "replacement must preserve primary-key policy",
    );
}

#[test]
fn replacing_secondary_preserves_newest_certification_fallback_primary() {
    let material = generated_material();
    let public_key = add_secondary_user_id(&material, true, false, false);
    let before = parse_single_public(&public_key).expect("parse certificate");
    let before_candidates = all_components(std::slice::from_ref(&before));
    let before_policy = validate_certificate(
        &before,
        &before_candidates,
        REFERENCE,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect certificate");
    assert_eq!(
        before_policy.primary_user_id_for_test().as_deref(),
        Some(OLD_USER_ID),
    );
    assert!(before.details.users.iter().all(|user| {
        !signature_is_primary(
            select_effective_certification(&before, user, REFERENCE)
                .expect("select effective certification"),
        )
    }));

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("replace secondary identity");
    assert!(success.changed);
    assert_eq!(success.primary_user_id, OLD_USER_ID);

    let artifact = RawPacketStream::parse(&success.replacement_certificate_armored, 8)
        .expect("parse replacement artifact");
    let artifact_tags = artifact
        .packets()
        .iter()
        .map(|packet| packet.tag())
        .collect::<Vec<_>>();
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 13).count(), 3);
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 2).count(), 3);

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let candidates = all_components(std::slice::from_ref(&reparsed));
    let policy = validate_certificate(
        &reparsed,
        &candidates,
        REFERENCE,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect updated certificate");
    assert_eq!(policy.authenticated_user_ids().count(), 2);
    assert!(
        policy
            .authenticated_user_ids()
            .any(|user_id| user_id.packet_body() == OLD_USER_ID.as_bytes()),
    );
    assert!(
        policy
            .authenticated_user_ids()
            .any(|user_id| user_id.packet_body() == NEW_USER_ID.as_bytes()),
    );
    assert_eq!(
        policy.primary_user_id_for_test().as_deref(),
        Some(OLD_USER_ID),
    );
    let primary_user = reparsed
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(OLD_USER_ID))
        .expect("find preserved primary");
    assert!(signature_is_primary(
        select_effective_certification(&reparsed, primary_user, REFERENCE)
            .expect("select preserved primary certification"),
    ));
}

#[test]
fn fallback_primary_recertification_preserves_non_revocable_policy() {
    let material = generated_material();
    let public_key = add_secondary_user_id(&material, true, true, false);
    let before = parse_single_public(&public_key).expect("parse certificate");
    let before_primary = before
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(OLD_USER_ID))
        .expect("find fallback primary");
    assert!(!signature_is_revocable(
        select_effective_certification(&before, before_primary, REFERENCE)
            .expect("select non-revocable certification"),
    ));

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("replace secondary identity");

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let primary_user = reparsed
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(OLD_USER_ID))
        .expect("find preserved primary");
    let effective = select_effective_certification(&reparsed, primary_user, REFERENCE)
        .expect("select preserved certification");
    assert!(signature_is_primary(effective));
    assert!(!signature_is_revocable(effective));
    let revocable = effective
        .config()
        .expect("V4 signature configuration")
        .hashed_subpackets
        .iter()
        .rev()
        .find(|subpacket| matches!(&subpacket.data, SubpacketData::Revocable(_)))
        .expect("preserved revocable subpacket");
    assert!(revocable.is_critical);
    assert!(matches!(&revocable.data, SubpacketData::Revocable(false)));
}

#[test]
fn replacing_secondary_does_not_recertify_explicit_primary() {
    let material = generated_material();
    let public_key = add_secondary_user_id(&material, false, false, false);
    let before = parse_single_public(&public_key).expect("parse certificate");
    let primary_signature_count = before.details.users[0].signatures.len();

    let success = replace_user_id_request(UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, SECOND_USER_ID.as_bytes()),
        new_user_id: NEW_USER_ID.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    })
    .expect("replace secondary identity");
    assert_eq!(success.primary_user_id, OLD_USER_ID);

    let artifact = RawPacketStream::parse(&success.replacement_certificate_armored, 8)
        .expect("parse replacement artifact");
    let artifact_tags = artifact
        .packets()
        .iter()
        .map(|packet| packet.tag())
        .collect::<Vec<_>>();
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 13).count(), 2);
    assert_eq!(artifact_tags.iter().filter(|tag| **tag == 2).count(), 2);

    let updated = success.key_material;
    let reparsed = parse_single_public(&updated.public_key_armored).expect("parse updated");
    let primary_user = reparsed
        .details
        .users
        .iter()
        .find(|user| user.id.as_str() == Some(OLD_USER_ID))
        .expect("find explicit primary");
    assert_eq!(primary_user.signatures.len(), primary_signature_count);
}

#[test]
fn rejects_same_blank_and_control_character_user_ids_before_signing() {
    let material = generated_material();
    let request = |new_user_id: &str| UserIdReplacementInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        old_identity_id: identity_id(13, OLD_USER_ID.as_bytes()),
        new_user_id: new_user_id.to_owned(),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE,
    };

    assert!(matches!(
        replace_user_id_request(request(OLD_USER_ID)),
        Err(UserIdReplacementFailure::SameIdentity),
    ));
    assert!(matches!(
        replace_user_id_request(request(" \t ")),
        Err(UserIdReplacementFailure::InvalidNewUserId),
    ));
    assert!(matches!(
        replace_user_id_request(request("New\u{0000}Identity")),
        Err(UserIdReplacementFailure::InvalidNewUserId),
    ));
}
