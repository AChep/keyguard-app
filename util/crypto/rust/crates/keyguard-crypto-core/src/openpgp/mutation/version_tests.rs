use super::*;
use crate::openpgp::{
    adapter::{
        key::generate,
        wire::{Message as _, OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial},
    },
    certificate::{
        identity_id, parse_single_certificate_packet_set, parse_single_public, parse_single_secret,
    },
    key::encode_key_material,
    packet::USER_ID_TAG,
    policy::{OpenPgpPolicyBudget, all_components, validate_certificate},
};
use pgp::{
    composed::SignedSecretKey,
    packet::{Signature, SignatureVersion, SignatureVersionSpecific, SubpacketData},
    ser::Serialize,
    types::{KeyDetails, KeyVersion},
};

const CREATED: u64 = 1_700_000_000;
const USER_ID: &str = "Version Test <versions@example.test>";

fn material(kind: OpenPgpKeyKind, version: i32, bits: u32) -> OpenPgpKeyMaterial {
    let bytes = generate(OpenPgpKeyGenerateRequest {
        kind: kind as i32,
        version,
        user_id: USER_ID.to_owned(),
        rsa_bits: bits,
        creation_time_epoch_seconds: CREATED,
        expiration_seconds: Some(3_600),
    })
    .expect("generate versioned certificate");
    OpenPgpKeyMaterial::decode(bytes.as_slice()).expect("decode generated certificate")
}

fn signatures(secret: &SignedSecretKey) -> impl Iterator<Item = &Signature> {
    secret
        .details
        .direct_signatures
        .iter()
        .chain(
            secret
                .details
                .users
                .iter()
                .flat_map(|user| &user.signatures),
        )
        .chain(
            secret
                .secret_subkeys
                .iter()
                .flat_map(|subkey| &subkey.signatures),
        )
}

fn assert_signature_version(signature: &Signature, version: KeyVersion) {
    let config = signature.config().expect("signature config");
    assert_eq!(
        signature.version(),
        if version == KeyVersion::V6 {
            SignatureVersion::V6
        } else {
            SignatureVersion::V4
        }
    );
    if version == KeyVersion::V6 {
        assert!(
            matches!(&config.version_specific, SignatureVersionSpecific::V6 { salt } if !salt.is_empty())
        );
        assert!(
            !config
                .hashed_subpackets
                .iter()
                .chain(&config.unhashed_subpackets)
                .any(|packet| matches!(packet.data, SubpacketData::IssuerKeyId(_)))
        );
    }
    for packet in &config.hashed_subpackets {
        if let SubpacketData::EmbeddedSignature(embedded) = &packet.data {
            assert_signature_version(embedded, version);
        }
    }
}

fn secret_packets(secret: &SignedSecretKey) -> Vec<Vec<u8>> {
    std::iter::once(secret.primary_key.to_bytes().expect("primary bytes"))
        .chain(
            secret
                .secret_subkeys
                .iter()
                .map(|key| key.key.to_bytes().expect("subkey bytes")),
        )
        .collect()
}

#[test]
fn generation_supports_both_versions_and_profiles() {
    for (kind, version, bits) in [
        (OpenPgpKeyKind::LegacyEd25519X25519, 0, 0),
        (OpenPgpKeyKind::LegacyEd25519X25519, 4, 0),
        (OpenPgpKeyKind::Ed25519X25519, 6, 0),
        (OpenPgpKeyKind::Rsa, 4, 3_072),
        (OpenPgpKeyKind::Rsa, 4, 4_096),
        (OpenPgpKeyKind::Rsa, 6, 3_072),
        (OpenPgpKeyKind::Rsa, 6, 4_096),
    ] {
        let material = material(kind, version, bits);
        let secret = parse_single_secret(&material.private_key_armored).expect("parse secret");
        secret
            .verify_bindings()
            .expect("verify all generated bindings");
        let public = parse_single_public(&material.public_key_armored).expect("parse public");
        public.verify_bindings().expect("verify exported bindings");
        let expected = if version == 6 {
            KeyVersion::V6
        } else {
            KeyVersion::V4
        };
        assert_eq!(public.primary_key.version(), expected);
        assert_eq!(public.public_subkeys.len(), 2);
        assert!(
            public
                .public_subkeys
                .iter()
                .all(|key| key.key.version() == expected)
        );
        assert_eq!(
            material.fingerprint.len(),
            if version == 6 { 64 } else { 40 }
        );
        let id = public.primary_key.legacy_key_id();
        let fingerprint = public.primary_key.fingerprint();
        assert_eq!(
            id.as_ref(),
            if version == 6 {
                &fingerprint.as_bytes()[..8]
            } else {
                &fingerprint.as_bytes()[12..]
            }
        );
        assert_eq!(public.details.direct_signatures.is_empty(), version != 6);
        for signature in signatures(&secret) {
            assert_signature_version(signature, expected);
        }
        for armored in [&material.public_key_armored, &material.private_key_armored] {
            assert_eq!(
                std::str::from_utf8(armored)
                    .expect("armor")
                    .lines()
                    .any(|line| line.starts_with('=')),
                version != 6
            );
        }
        let candidates = all_components(std::slice::from_ref(&public));
        let mut budget = OpenPgpPolicyBudget::default();
        let policy =
            validate_certificate(&public, &candidates, CREATED + 10, &mut budget).expect("policy");
        policy
            .primary_component()
            .authorize_mutation()
            .expect("authenticated primary");
        assert_eq!(policy.primary.key_expiration_seconds, Some(3_600));
    }
}

#[test]
fn generation_rejects_unsupported_versions_and_kind_combinations() {
    for (kind, version) in [
        (OpenPgpKeyKind::LegacyEd25519X25519, 6),
        (OpenPgpKeyKind::Ed25519X25519, 4),
        (OpenPgpKeyKind::Ed25519X25519, 0),
        (OpenPgpKeyKind::Rsa, 5),
        (OpenPgpKeyKind::Rsa, 99),
    ] {
        assert!(
            generate(OpenPgpKeyGenerateRequest {
                kind: kind as i32,
                version,
                user_id: USER_ID.to_owned(),
                rsa_bits: if kind == OpenPgpKeyKind::Rsa {
                    3_072
                } else {
                    0
                },
                creation_time_epoch_seconds: CREATED,
                expiration_seconds: None,
            })
            .is_err()
        );
    }
}

#[test]
fn v6_mutations_preserve_material_and_emit_mergeable_artifacts() {
    for (kind, bits) in [
        (OpenPgpKeyKind::Ed25519X25519, 0),
        (OpenPgpKeyKind::Rsa, 3_072),
    ] {
        let original = material(kind, 6, bits);
        let before = parse_single_secret(&original.private_key_armored).expect("original");
        let components = std::iter::once(original.fingerprint.clone())
            .chain(
                before
                    .secret_subkeys
                    .iter()
                    .map(|key| crate::openpgp::format::fingerprint_hex(&key.key)),
            )
            .collect();
        let renewed = update_expiration_request(ExpirationUpdateInput {
            private_key: original.private_key_armored.clone(),
            public_key: original.public_key_armored.clone(),
            expected_primary_fingerprint: original.fingerprint.clone(),
            component_fingerprints: components,
            expires_at_epoch_seconds: Some(CREATED + 7_200),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: CREATED + 20,
        })
        .expect("renew V6 primary and subkeys");
        let after =
            parse_single_secret(&renewed.key_material.private_key_armored).expect("renewed");
        after.verify_bindings().expect("renewed bindings");
        assert_eq!(secret_packets(&before), secret_packets(&after));
        assert_eq!(
            before.details.users[0].signatures[0].to_bytes().unwrap(),
            after.details.users[0].signatures[0].to_bytes().unwrap()
        );
        for signature in signatures(&after) {
            assert_signature_version(signature, KeyVersion::V6);
        }
        assert_ne!(
            before.details.direct_signatures[0]
                .config()
                .unwrap()
                .version_specific,
            after.details.direct_signatures[0]
                .config()
                .unwrap()
                .version_specific
        );

        let replaced = replace_user_id_request(UserIdReplacementInput {
            private_key: renewed.key_material.private_key_armored.clone(),
            public_key: renewed.key_material.public_key_armored.clone(),
            expected_primary_fingerprint: original.fingerprint.clone(),
            old_identity_id: identity_id(USER_ID_TAG, USER_ID.as_bytes()),
            new_user_id: "Renamed <renamed@example.test>".to_owned(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: CREATED + 30,
        })
        .expect("replace V6 identity");
        let replacement_secret = parse_single_secret(&replaced.key_material.private_key_armored)
            .expect("replacement secret");
        assert_eq!(secret_packets(&before), secret_packets(&replacement_secret));
        assert_eq!(
            after.details.direct_signatures[0].to_bytes().unwrap(),
            replacement_secret.details.direct_signatures[0]
                .to_bytes()
                .unwrap()
        );
        for signature in signatures(&replacement_secret) {
            assert_signature_version(signature, KeyVersion::V6);
        }
        let mut peer =
            parse_single_certificate_packet_set(&renewed.key_material.public_key_armored)
                .expect("peer");
        let artifact =
            parse_single_certificate_packet_set(&replaced.replacement_certificate_armored)
                .expect("replacement artifact");
        assert!(
            artifact
                .clone()
                .finalize()
                .expect("standalone artifact")
                .transferable
        );
        peer.merge(artifact).expect("merge replacement");
        assert_eq!(
            peer.finalize().expect("merged").bytes,
            parse_single_certificate_packet_set(&replaced.key_material.public_key_armored)
                .unwrap()
                .finalize()
                .unwrap()
                .bytes
        );

        let revoked = revoke_user_id_request(UserIdRevocationInput {
            private_key: replaced.key_material.private_key_armored.clone(),
            public_key: replaced.key_material.public_key_armored.clone(),
            expected_primary_fingerprint: original.fingerprint.clone(),
            identity_id: replaced.new_identity_id.clone(),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: CREATED + 40,
        })
        .expect("revoke last V6 identity");
        let public = parse_single_public(&revoked.key_material.public_key_armored)
            .expect("revoked identity certificate");
        let candidates = all_components(std::slice::from_ref(&public));
        let mut budget = OpenPgpPolicyBudget::default();
        let policy = validate_certificate(&public, &candidates, CREATED + 41, &mut budget)
            .expect("post-revocation policy");
        policy
            .primary_component()
            .authorize_mutation()
            .expect("identityless primary stays usable");
        assert_eq!(policy.authenticated_user_ids().count(), 0);
        let mut peer =
            parse_single_certificate_packet_set(&replaced.key_material.public_key_armored).unwrap();
        peer.merge(
            parse_single_certificate_packet_set(&revoked.revocation_certificate_armored)
                .expect("revocation artifact"),
        )
        .unwrap();
        assert_eq!(
            peer.finalize().unwrap().bytes,
            parse_single_certificate_packet_set(&revoked.key_material.public_key_armored)
                .unwrap()
                .finalize()
                .unwrap()
                .bytes
        );
    }
}

#[test]
fn v6_primary_without_identities_can_be_renewed() {
    let original = material(OpenPgpKeyKind::Ed25519X25519, 6, 0);
    let mut secret = parse_single_secret(&original.private_key_armored).unwrap();
    secret.details.users.clear();
    let material = encode_key_material(&secret).expect("identityless certificate");
    let updated = update_expiration_request(ExpirationUpdateInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        component_fingerprints: vec![material.fingerprint.clone()],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: CREATED + 10,
    })
    .expect("renew identityless V6 primary");
    let public = parse_single_public(&updated.key_material.public_key_armored).unwrap();
    assert!(public.details.users.is_empty());
    public.verify_bindings().expect("identityless bindings");
}
