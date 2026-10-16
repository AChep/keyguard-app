use super::*;
use crate::openpgp::{
    adapter::{
        key::generate,
        wire::{Message as _, OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial},
    },
    certificate::{identity_id, parse_single_public, parse_single_secret},
    message::{PublicKeyParseInput, PublicKeyParseOutcome, parse_public_key},
    mutation::{UserIdRevocationInput, revoke_user_id_request},
    policy::{OpenPgpPolicyBudget, all_components, validate_certificate},
};
use zeroize::Zeroizing;

const CREATED: u64 = 1_700_000_000;
const FIRST: &str = "First <first@example.test>";
const SECOND: &str = "Second <second@example.test>";

fn material(version: i32) -> KeyMaterial {
    let bytes = generate(OpenPgpKeyGenerateRequest {
        kind: if version == 6 {
            OpenPgpKeyKind::Ed25519X25519
        } else {
            OpenPgpKeyKind::LegacyEd25519X25519
        } as i32,
        version,
        user_id: FIRST.to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: CREATED,
        expiration_seconds: Some(3_600),
    })
    .expect("generate");
    let mut value = OpenPgpKeyMaterial::decode(bytes.as_slice()).expect("decode");
    KeyMaterial {
        private_key_armored: std::mem::take(&mut value.private_key_armored),
        public_key_armored: std::mem::take(&mut value.public_key_armored),
        fingerprint: std::mem::take(&mut value.fingerprint),
    }
}

fn request(material: &KeyMaterial, change: UserIdUpdate) -> UserIdUpdateInput {
    UserIdUpdateInput {
        documents: MutationDocuments {
            private_key: material.private_key_armored.clone(),
            public_key: material.public_key_armored.clone(),
            expected_primary_fingerprint: material.fingerprint.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: CREATED + 120,
        },
        change,
    }
}

fn assert_identities(material: &KeyMaterial, primary: &str, count: usize) {
    let public = parse_single_public(&material.public_key_armored).expect("parse public");
    let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
    public.verify_bindings().expect("public signatures");
    secret.verify_bindings().expect("secret signatures");
    let candidates = all_components(std::slice::from_ref(&public));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy =
        validate_certificate(&public, &candidates, CREATED + 300, &mut budget).expect("policy");
    assert_eq!(policy.authenticated_user_ids().count(), count);
    assert_eq!(
        policy.primary_user_id().expect("primary").packet_body(),
        primary.as_bytes()
    );
}

#[test]
fn add_and_change_primary_preserve_key_and_are_idempotent() {
    for version in [4, 6] {
        let original = material(version);
        let added =
            update_user_id_request(request(&original, UserIdUpdate::Add(SECOND.to_owned())))
                .expect("add");
        assert!(added.changed);
        assert_eq!(added.key_material.fingerprint, original.fingerprint);
        assert_identities(&added.key_material, FIRST, 2);
        let again = update_user_id_request(request(
            &added.key_material,
            UserIdUpdate::Add(SECOND.to_owned()),
        ))
        .expect("repeat add");
        assert!(!again.changed);
        let mut change = request(
            &added.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes())),
        );
        change.documents.reference_time_epoch_seconds += 1;
        let selected = update_user_id_request(change).expect("primary");
        assert!(selected.changed);
        assert_identities(&selected.key_material, SECOND, 2);
        let mut change = request(
            &selected.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes())),
        );
        change.documents.reference_time_epoch_seconds += 1;
        let again = update_user_id_request(change).expect("repeat primary");
        assert!(!again.changed);
        let mut change = request(
            &selected.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, FIRST.as_bytes())),
        );
        change.documents.reference_time_epoch_seconds += 2;
        let restored = update_user_id_request(change).expect("restore primary");
        assert_identities(&restored.key_material, FIRST, 2);
    }
}

#[test]
fn rejects_invalid_user_id_stale_fingerprint_and_missing_identity() {
    let key = material(4);
    for value in [
        "",
        " ",
        "Injected\nUser",
        "Injected\u{2028}User",
        "Injected\u{2029}User",
    ] {
        assert!(matches!(
            update_user_id_request(request(&key, UserIdUpdate::Add(value.to_owned()))),
            Err(Failure::InvalidNewUserId)
        ));
    }
    // Shared request limits are reported before operation arguments.
    let mut oversized = request(&key, UserIdUpdate::Add(String::new()));
    oversized.documents.public_key = vec![b'A'; 8 * 1024 * 1024 + 1];
    assert!(matches!(
        update_user_id_request(oversized),
        Err(Failure::ResourceLimit)
    ));
    let mut stale = request(&key, UserIdUpdate::Add(SECOND.to_owned()));
    stale.documents.expected_primary_fingerprint = "00".repeat(20);
    assert!(matches!(
        update_user_id_request(stale),
        Err(Failure::FingerprintMismatch)
    ));
    assert!(matches!(
        update_user_id_request(request(
            &key,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes()))
        )),
        Err(Failure::TargetNotFound)
    ));
    assert_identities(&key, FIRST, 1);
}
#[test]
fn same_second_revocation_waits_and_roundtrips_for_v4_and_v6() {
    for version in [4, 6] {
        let original = material(version);
        let added =
            update_user_id_request(request(&original, UserIdUpdate::Add(SECOND.to_owned())))
                .expect("add");
        // The certification was signed at CREATED + 120, and a revocation
        // is never dated in the future.
        assert!(matches!(
            revoke_user_id_request(revocation_request(&added.key_material, CREATED + 120)),
            Err(Failure::RetryAt(time)) if time == CREATED + 121
        ));
        let revoked =
            revoke_user_id_request(revocation_request(&added.key_material, CREATED + 121))
                .expect("revoke after the wait");
        assert!(revoked.changed);
        assert_revocation_metadata(&revoked.key_material, CREATED + 121, true, false);
    }
}

#[test]
fn revoked_ids_stay_in_history_and_cannot_become_primary_or_be_added_again() {
    for version in [4, 6] {
        let original = material(version);
        let added =
            update_user_id_request(request(&original, UserIdUpdate::Add(SECOND.to_owned())))
                .expect("add");
        let revoked =
            revoke_user_id_request(revocation_request(&added.key_material, CREATED + 121))
                .expect("revoke");
        let mut add_again = request(&revoked.key_material, UserIdUpdate::Add(SECOND.to_owned()));
        add_again.documents.reference_time_epoch_seconds += 2;
        assert!(matches!(
            update_user_id_request(add_again),
            Err(Failure::PreviouslyRevokedIdentity)
        ));
        let mut select = request(
            &revoked.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes())),
        );
        select.documents.reference_time_epoch_seconds += 2;
        assert!(matches!(
            update_user_id_request(select),
            Err(Failure::TargetInactive)
        ));
        let parsed = parse_public_key(PublicKeyParseInput {
            key_data: Zeroizing::new(revoked.key_material.public_key_armored.clone()),
            reference_time_epoch_seconds: Some(CREATED + 122),
        })
        .expect("parse");
        let PublicKeyParseOutcome::Success(parsed) = parsed else {
            panic!("metadata");
        };
        let info = &parsed.keys[0];
        assert_eq!(info.user_id_details.len(), 1);
        assert!(info.user_id_details[0].primary);
        assert_eq!(info.user_id_history.len(), 1);
        assert_eq!(info.user_id_history[0].user_id, SECOND);
        assert!(info.user_id_history[0].revoked);
        assert!(!info.user_id_history[0].can_revoke);
        assert_eq!(info.user_id_details[0].can_revoke, version == 6);
    }
}

#[test]
fn identityless_v6_certificate_accepts_its_first_identity() {
    let original = material(6);
    let mut secret = parse_single_secret(&original.private_key_armored).expect("secret");
    secret.details.users.clear();
    let empty = crate::openpgp::key::encode_key_material(&secret).expect("identityless");
    let added = update_user_id_request(request(&empty, UserIdUpdate::Add(SECOND.to_owned())))
        .expect("first identity");
    assert_identities(&added.key_material, SECOND, 1);
    assert_eq!(added.key_material.fingerprint, original.fingerprint);
}

#[test]
fn consecutive_changes_wait_for_the_clock_instead_of_signing_future_policy() {
    let original = material(4);
    let added = update_user_id_request(request(&original, UserIdUpdate::Add(SECOND.to_owned())))
        .expect("add");
    assert!(matches!(
        update_user_id_request(request(
            &added.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes()))
        )),
        Err(Failure::RetryAt(time)) if time == CREATED + 121
    ));
}
#[test]
fn no_op_ignores_unrelated_future_certifications() {
    for version in [4, 6] {
        let original = material(version);
        let mut future = request(&original, UserIdUpdate::Add(SECOND.to_owned()));
        future.documents.reference_time_epoch_seconds += 60;
        let updated = update_user_id_request(future).expect("add future identity");
        for change in [
            UserIdUpdate::Add(FIRST.to_owned()),
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, FIRST.as_bytes())),
        ] {
            let unchanged = update_user_id_request(request(&updated.key_material, change))
                .expect("no-op before unrelated future signature");
            assert!(!unchanged.changed);
        }
    }
}

fn certify_identity(
    material: &KeyMaterial,
    body: &str,
    created: u64,
    primary: bool,
    local: bool,
) -> KeyMaterial {
    use pgp::{
        packet::{Subpacket, SubpacketData},
        types::{SignedUser, Timestamp},
    };
    let mut secret = parse_single_secret(&material.private_key_armored).expect("secret");
    let public = secret.to_public_key();
    let signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&secret.primary_key),
        &secret.primary_key as &dyn SigningKey,
    )
    .expect("signer");
    let signer = signer.as_ref();
    let mut config = existing_user_id_recertification_config(
        &public.details.users[0].signatures[0],
        signer,
        Timestamp::from_secs(u32::try_from(created).expect("timestamp")),
        primary,
    )
    .expect("config");
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::ExportableCertification(!local)).expect("exportability"),
    );
    let certification = UserIdCertificationBuilder::new(signer, &public.primary_key, body, config)
        .build()
        .expect("certification");
    if let Some(user) = secret
        .details
        .users
        .iter_mut()
        .find(|user| user.id.id() == body.as_bytes())
    {
        user.signatures.push(certification.signature);
    } else {
        secret.details.users.push(SignedUser::new(
            certification.user_id,
            vec![certification.signature],
        ));
    }
    crate::openpgp::key::encode_key_material(&secret).expect("material")
}

fn revocation_request(key: &KeyMaterial, time: u64) -> UserIdRevocationInput {
    UserIdRevocationInput {
        documents: MutationDocuments {
            private_key: key.private_key_armored.clone(),
            public_key: key.public_key_armored.clone(),
            expected_primary_fingerprint: key.fingerprint.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: time,
        },
        identity_id: identity_id(USER_ID_TAG, SECOND.as_bytes()),
    }
}

fn recertified_after_revocation(version: i32, certification_time: u64) -> KeyMaterial {
    let key = certify_identity(&material(version), SECOND, CREATED + 1, false, false);
    let revoked =
        revoke_user_id_request(revocation_request(&key, CREATED + 10)).expect("initial revocation");
    certify_identity(
        &revoked.key_material,
        SECOND,
        certification_time,
        false,
        false,
    )
}

fn assert_revocation_metadata(key: &KeyMaterial, time: u64, revoked: bool, can_revoke: bool) {
    for document in [&key.public_key_armored, &key.private_key_armored] {
        let PublicKeyParseOutcome::Success(parsed) = parse_public_key(PublicKeyParseInput {
            key_data: Zeroizing::new(document.clone()),
            reference_time_epoch_seconds: Some(time),
        })
        .expect("reopen saved key") else {
            panic!("metadata");
        };
        let identity = parsed.keys[0]
            .user_id_details
            .iter()
            .chain(&parsed.keys[0].user_id_history)
            .find(|identity| identity.user_id == SECOND)
            .expect("retained identity");
        assert_eq!(identity.revoked, revoked);
        assert_eq!(identity.can_revoke, can_revoke);
    }
}

#[test]
fn revocation_covers_certifications_after_an_earlier_revocation() {
    for version in [4, 6] {
        let key = recertified_after_revocation(version, CREATED + 20);
        // The later certification makes the identity valid again.
        assert_revocation_metadata(&key, CREATED + 21, false, true);

        let result = revoke_user_id_request(revocation_request(&key, CREATED + 21))
            .expect("cover the later certification");
        assert!(result.changed);
        assert_eq!(result.effective_at_epoch_seconds, CREATED + 21);
        assert!(!result.revocation_certificate_armored.is_empty());
        for later in [CREATED + 21, CREATED + 40] {
            assert_revocation_metadata(&result.key_material, later, true, false);
        }

        let again = revoke_user_id_request(revocation_request(&result.key_material, CREATED + 21))
            .expect("repeat revocation");
        assert!(!again.changed);
        assert_eq!(again.effective_at_epoch_seconds, CREATED + 21);
        assert!(again.revocation_certificate_armored.is_empty());
    }
}

#[test]
fn future_dated_revocation_does_not_make_a_new_one_redundant() {
    for version in [4, 6] {
        let key = recertified_after_revocation(version, CREATED + 20);
        let scheduled = revoke_user_id_request(revocation_request(&key, CREATED + 500))
            .expect("later revocation");
        // Read before CREATED + 500, that revocation is not in effect yet.
        assert_revocation_metadata(&scheduled.key_material, CREATED + 25, false, true);

        let result =
            revoke_user_id_request(revocation_request(&scheduled.key_material, CREATED + 25))
                .expect("revoke now");
        assert!(result.changed);
        assert_eq!(result.effective_at_epoch_seconds, CREATED + 25);
        assert_revocation_metadata(&result.key_material, CREATED + 25, true, false);
    }
}

#[test]
fn earlier_revocation_does_not_bypass_the_retry_window() {
    for version in [4, 6] {
        let key = recertified_after_revocation(version, CREATED + 1_000);
        for time in [CREATED + 5, CREATED + 15] {
            assert!(matches!(
                revoke_user_id_request(revocation_request(&key, time)),
                Err(Failure::TimeConflict)
            ));
            assert_revocation_metadata(&key, time, time > CREATED + 10, false);
        }
    }
}

#[test]
fn unrelated_future_and_revoked_identities_do_not_block_add_or_primary_selection() {
    const THIRD: &str = "Third <third@example.test>";
    for version in [4, 6] {
        for revoked in [false, true] {
            let original = material(version);
            let second = certify_identity(&original, SECOND, CREATED + 1, false, false);
            let mut key = certify_identity(&second, THIRD, CREATED + 2, false, false);
            if revoked {
                key = revoke_user_id_request(UserIdRevocationInput {
                    documents: MutationDocuments {
                        private_key: key.private_key_armored.clone(),
                        public_key: key.public_key_armored.clone(),
                        expected_primary_fingerprint: key.fingerprint.clone(),
                        candidate_revocation_keys: Vec::new(),
                        reference_time_epoch_seconds: CREATED + 3,
                    },
                    identity_id: identity_id(USER_ID_TAG, THIRD.as_bytes()),
                })
                .expect("revoke third identity")
                .key_material;
            }
            // The unrelated identity has a mathematically valid statement ahead
            // of the operation's clock, whether it is currently active or revoked.
            key = certify_identity(&key, THIRD, CREATED + 360, false, false);
            let public = parse_single_public(&key.public_key_armored).expect("public");
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                CREATED + 120,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("policy");
            assert_eq!(
                policy
                    .user_id(THIRD.as_bytes())
                    .expect("third")
                    .revocation_status
                    .is_revoked(),
                revoked
            );
            for change in [
                UserIdUpdate::Add("Fourth <fourth@example.test>".to_owned()),
                UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes())),
            ] {
                let result = update_user_id_request(request(&key, change))
                    .expect("unrelated evidence must not block mutation");
                assert!(result.changed);
            }
        }
    }
}

#[test]
fn primary_selection_waits_for_certifications_it_must_supersede() {
    use crate::openpgp::policy::MAX_SIGNATURE_RETRY_SECONDS;

    let near = CREATED + 120 + MAX_SIGNATURE_RETRY_SECONDS - 1;
    let far = CREATED + 120 + MAX_SIGNATURE_RETRY_SECONDS;
    for version in [4, 6] {
        for future_body in [FIRST, SECOND] {
            for (created, expected) in [
                (near, Failure::RetryAt(near + 1)),
                (far, Failure::TimeConflict),
            ] {
                let second =
                    certify_identity(&material(version), SECOND, CREATED + 1, false, false);
                let key = certify_identity(&second, future_body, created, false, false);
                let change = UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes()));
                assert_eq!(
                    update_user_id_request(request(&key, change)).err(),
                    Some(expected)
                );
            }
        }
    }
}

#[test]
fn selecting_the_current_primary_clears_competing_markers_before_becoming_a_no_op() {
    for version in [4, 6] {
        for competing_time in [CREATED, CREATED + 1] {
            let key = certify_identity(&material(version), SECOND, competing_time, true, false);
            let public = parse_single_public(&key.public_key_armored).expect("public");
            let policy = validate_certificate(
                &public,
                &all_components(std::slice::from_ref(&public)),
                CREATED + 120,
                &mut OpenPgpPolicyBudget::default(),
            )
            .expect("policy");
            let target = policy.primary_user_id().expect("primary").packet_body();
            assert!(!has_exclusive_primary_user_id(&policy, target));
            let change = || UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, target));
            let result =
                update_user_id_request(request(&key, change())).expect("normalize primary markers");
            assert!(result.changed);
            for bytes in [
                &result.key_material.public_key_armored,
                &result.key_material.private_key_armored,
            ] {
                let public = if bytes == &result.key_material.private_key_armored {
                    parse_single_secret(bytes).expect("secret").to_public_key()
                } else {
                    parse_single_public(bytes).expect("public")
                };
                let after = validate_certificate(
                    &public,
                    &all_components(std::slice::from_ref(&public)),
                    CREATED + 120,
                    &mut OpenPgpPolicyBudget::default(),
                )
                .expect("updated policy");
                assert!(has_exclusive_primary_user_id(&after, target));
            }
            let repeated = update_user_id_request(request(&result.key_material, change()))
                .expect("repeat at same time");
            assert!(!repeated.changed);
        }
    }
}

#[test]
fn selecting_an_implicit_primary_establishes_an_explicit_marker() {
    for version in [4, 6] {
        let key = certify_identity(&material(version), FIRST, CREATED + 1, false, false);
        let selected = update_user_id_request(request(
            &key,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, FIRST.as_bytes())),
        ))
        .expect("make fallback primary explicit");
        assert!(selected.changed);
        let repeated = update_user_id_request(request(
            &selected.key_material,
            UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, FIRST.as_bytes())),
        ))
        .expect("repeat explicit selection");
        assert!(!repeated.changed);
    }
}

#[test]
fn adding_an_identity_keeps_the_pinned_implicit_primary_non_revocable() {
    use crate::openpgp::policy::{signature_is_primary, signature_is_revocable};
    use pgp::{
        packet::{Subpacket, SubpacketData},
        types::Timestamp,
    };
    let primary_certification = |key: &KeyMaterial| {
        let public = parse_single_public(&key.public_key_armored).expect("public");
        let policy = validate_certificate(
            &public,
            &all_components(std::slice::from_ref(&public)),
            CREATED + 120,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("policy");
        let primary = policy.primary_user_id().expect("primary");
        assert_eq!(primary.packet_body(), FIRST.as_bytes());
        let certification = policy
            .user_id_at(primary.index())
            .and_then(|identity| identity.effective_signature)
            .expect("primary certification");
        (
            signature_is_primary(certification),
            signature_is_revocable(certification),
        )
    };
    for version in [4, 6] {
        // Recertify the only identity without a primary marker and as non-revocable.
        let mut secret =
            parse_single_secret(&material(version).private_key_armored).expect("secret");
        let public = secret.to_public_key();
        let signer = OpenPgpSecretSigner::new(
            SecretPacketRef::Primary(&secret.primary_key),
            &secret.primary_key as &dyn SigningKey,
        )
        .expect("signer");
        let mut config = existing_user_id_recertification_config(
            &public.details.users[0].signatures[0],
            signer.as_ref(),
            Timestamp::from_secs(u32::try_from(CREATED + 1).expect("timestamp")),
            false,
        )
        .expect("config");
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::Revocable(false)).expect("non-revocable subpacket"),
        );
        let certification = UserIdCertificationBuilder::for_existing(
            signer.as_ref(),
            &public.primary_key,
            &public.details.users[0].id,
            config,
        )
        .build()
        .expect("certification");
        secret.details.users[0]
            .signatures
            .push(certification.signature);
        let key = crate::openpgp::key::encode_key_material(&secret).expect("material");
        assert_eq!(primary_certification(&key), (false, false));

        let added = update_user_id_request(request(&key, UserIdUpdate::Add(SECOND.to_owned())))
            .expect("add");
        assert!(added.changed);
        // The pin makes the primary explicit and keeps it non-revocable.
        assert_eq!(primary_certification(&added.key_material), (true, false));
    }
}

#[test]
fn local_recertification_preserves_unrelated_public_components() {
    use crate::openpgp::{
        certificate::{
            armor_key_packets, parse_single_certificate_packet_set, project_secret_certificate,
            rebuild_secret_certificate,
        },
        crypto::verification::signature_config_is_non_exportable,
        packet::{PUBLIC_SUBKEY_TAG, RawPacketStream},
    };
    use pgp::armor::BlockType;
    const UNATTESTED: &str = "Unattested <unattested@example.test>";
    for version in [4, 6] {
        for explicit_primary in [false, true] {
            let second = certify_identity(&material(version), SECOND, CREATED + 1, false, false);
            let mut key = certify_identity(&second, FIRST, CREATED + 2, explicit_primary, true);
            let (_, overlay) =
                project_secret_certificate(&key.private_key_armored).expect("overlay");
            let canonical = parse_single_certificate_packet_set(&key.public_key_armored)
                .expect("packets")
                .finalize()
                .expect("canonical");
            let stream = RawPacketStream::parse(&canonical.retained_bytes, 64).expect("stream");
            let mut bytes = Vec::new();
            let mut inserted = false;
            for packet in stream.packets() {
                if packet.tag() == PUBLIC_SUBKEY_TAG && !inserted {
                    bytes.extend_from_slice(&[
                        0xc0 | USER_ID_TAG,
                        u8::try_from(UNATTESTED.len()).expect("length"),
                    ]);
                    bytes.extend_from_slice(UNATTESTED.as_bytes());
                    inserted = true;
                }
                bytes.extend_from_slice(stream.raw(packet));
            }
            assert!(inserted);
            let canonical = parse_single_certificate_packet_set(&bytes)
                .expect("unattested fixture")
                .finalize()
                .expect("canonical fixture");
            key.public_key_armored =
                armor_key_packets(&canonical.local_public_bytes, BlockType::PublicKey)
                    .expect("public armor");
            let private =
                rebuild_secret_certificate(&canonical.retained_bytes, &overlay).expect("private");
            key.private_key_armored =
                armor_key_packets(&private, BlockType::PrivateKey).expect("private armor");
            for change in [
                UserIdUpdate::Add("Third <third@example.test>".into()),
                UserIdUpdate::MakePrimary(identity_id(USER_ID_TAG, SECOND.as_bytes())),
            ] {
                let result = update_user_id_request(request(&key, change))
                    .expect("update with local certification");
                for bytes in [
                    &result.key_material.public_key_armored,
                    &result.key_material.private_key_armored,
                ] {
                    let stream = RawPacketStream::parse(bytes, 64).expect("output packets");
                    assert!(
                        stream
                            .packets()
                            .iter()
                            .any(|packet| packet.tag() == USER_ID_TAG
                                && stream.body(packet).as_slice() == UNATTESTED.as_bytes())
                    );
                }
                let public =
                    parse_single_public(&result.key_material.public_key_armored).expect("public");
                assert!(
                    public
                        .details
                        .users
                        .iter()
                        .flat_map(|user| &user.signatures)
                        .all(|signature| !signature
                            .config()
                            .is_some_and(signature_config_is_non_exportable))
                );
                let private =
                    parse_single_secret(&result.key_material.private_key_armored).expect("secret");
                assert!(
                    private
                        .details
                        .users
                        .iter()
                        .flat_map(|user| &user.signatures)
                        .any(|signature| signature
                            .config()
                            .is_some_and(signature_config_is_non_exportable))
                );
            }
        }
    }
}
