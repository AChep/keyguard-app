use super::*;
use crate::openpgp::adapter::wire::{
    OpenPgpKeyGenerateRequest, OpenPgpKeyKind, OpenPgpKeyMaterial,
};
use crate::openpgp::certificate::{
    armor_key_packets, parse_single_public, parse_single_secret, project_secret_certificate,
};
use crate::openpgp::crypto::verification::{
    cryptographic_signature_material_cmp, is_certification,
};
use crate::openpgp::packet::{MAX_CERTIFICATE_PACKETS as MAX_KEY_PACKETS, RawPacketStream};
use crate::openpgp::policy::{
    MutationAuthorizationError, OpenPgpPolicyBudget, OpenPgpPolicyError, PublicComponent,
    RenewalAuthorization, RevocationStatus, all_components, signature_is_primary,
    validate_certificate,
};
use pgp::{
    armor::BlockType,
    composed::SignedSecretKey,
    packet::{PacketHeader, RevocationCode, UserAttribute},
    ser::Serialize,
    types::{Fingerprint, KeyId, RevocationKey, RevocationKeyClass, SignedUserAttribute, Tag},
};
use prost::{Message as _, bytes::Bytes};

fn generated_test_certificate(user_id: &str) -> (SignedSecretKey, SignedPublicKey) {
    generated_test_certificate_with_kind(user_id, OpenPgpKeyKind::LegacyEd25519X25519, 0)
}

fn generated_test_certificate_with_kind(
    user_id: &str,
    kind: OpenPgpKeyKind,
    rsa_bits: u32,
) -> (SignedSecretKey, SignedPublicKey) {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: kind as i32,
        user_id: user_id.to_owned(),
        rsa_bits,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let secret = parse_single_secret(&material.private_key_armored).expect("secret key");
    let public = parse_single_public(&material.public_key_armored).expect("public key");
    (secret, public)
}

#[derive(Clone, Copy)]
enum BackSignatureDefect {
    Expired,
    WeakHash,
    UnknownCritical,
    PrimaryIssuer,
}

fn assert_defective_back_signature_is_regenerated(defect: BackSignatureDefect) {
    const RENEWAL_TIME: u64 = 1_700_000_120;

    let (mut secret, _) = if matches!(defect, BackSignatureDefect::WeakHash) {
        generated_test_certificate_with_kind(
            "Weak Back Signature <weak-backsig@example.test>",
            OpenPgpKeyKind::Rsa,
            3_072,
        )
    } else {
        generated_test_certificate(
            "Back Signature Regeneration <backsig-regeneration@example.test>",
        )
    };
    let signing_position = secret
        .secret_subkeys
        .iter()
        .position(|subkey| {
            subkey.signatures.iter().any(|signature| {
                signature.typ() == Some(SignatureType::SubkeyBinding)
                    && binding_designates_signature_capable_subkey(
                        signature,
                        subkey.key.algorithm(),
                    )
            })
        })
        .expect("generated signing subkey");
    let signing_subkey = &secret.secret_subkeys[signing_position].key;
    let signing_public = signing_subkey.public_key().clone();
    let binding_template = secret.secret_subkeys[signing_position]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .cloned()
        .expect("generated signing-subkey binding");

    let hash_algorithm = if matches!(defect, BackSignatureDefect::WeakHash) {
        HashAlgorithm::Sha1
    } else {
        HashAlgorithm::Sha256
    };
    let mut back_config = SignatureConfig::v4(
        SignatureType::KeyBinding,
        signing_subkey.algorithm(),
        hash_algorithm,
    );
    back_config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_001,
        )))
        .expect("back-signature creation time"),
    );
    if matches!(defect, BackSignatureDefect::Expired) {
        back_config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                1,
            )))
            .expect("back-signature expiration"),
        );
    }
    if matches!(defect, BackSignatureDefect::UnknownCritical) {
        back_config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::Experimental(
                100,
                Bytes::from_static(b"unsupported critical back-signature policy"),
            ))
            .expect("unknown critical back-signature subpacket"),
        );
    }
    let (issuer_fingerprint, issuer_key_id) =
        if matches!(defect, BackSignatureDefect::PrimaryIssuer) {
            (
                secret.primary_key.fingerprint(),
                secret.primary_key.legacy_key_id(),
            )
        } else {
            (signing_subkey.fingerprint(), signing_subkey.legacy_key_id())
        };
    back_config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerFingerprint(issuer_fingerprint))
            .expect("back-signature issuer fingerprint"),
    );
    back_config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(issuer_key_id))
            .expect("back-signature issuer key ID"),
    );
    let defective_back_signature = {
        let signer = OpenPgpSecretSigner::new(
            SecretPacketRef::Subkey(signing_subkey),
            signing_subkey as &dyn SigningKey,
        )
        .expect("acquire signing-subkey signer");
        back_config
            .sign_primary_key_binding(
                &signer.as_ref(),
                &signing_public,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign defective back signature")
    };
    defective_back_signature
        .verify_primary_key_binding(&signing_public, secret.primary_key.public_key())
        .expect("defective back signature remains mathematically valid");

    let mut binding_config = binding_template
        .config()
        .cloned()
        .expect("generated v4 binding config");
    binding_config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::EmbeddedSignature(_)));
    binding_config
        .unhashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::EmbeddedSignature(_)));
    binding_config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(
            defective_back_signature.clone(),
        )))
        .expect("embedded defective back signature"),
    );
    let defective_binding = {
        let signer = OpenPgpSecretSigner::new(
            SecretPacketRef::Primary(&secret.primary_key),
            &secret.primary_key as &dyn SigningKey,
        )
        .expect("acquire primary-key signer");
        binding_config
            .sign_subkey_binding(
                &signer.as_ref(),
                secret.primary_key.public_key(),
                &Password::empty(),
                &signing_public,
            )
            .expect("sign binding with defective back signature")
    };
    defective_binding
        .verify_subkey_binding(secret.primary_key.public_key(), &signing_public)
        .expect("binding with defective back signature remains mathematically valid");
    secret.secret_subkeys[signing_position].signatures = vec![defective_binding];

    let public = secret.to_public_key();
    let primary_fingerprint = fingerprint_hex(secret.primary_key.public_key());
    let signing_fingerprint = fingerprint_hex(&signing_public);
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: secret
            .to_armored_bytes(Default::default())
            .expect("armored defective secret key"),
        public_key: public
            .to_armored_bytes(Default::default())
            .expect("armored defective public key"),
        expected_primary_fingerprint: primary_fingerprint,
        component_fingerprints: vec![signing_fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew signing subkey with defective back signature");
    let renewed_material = success.key_material;
    let renewed =
        parse_single_public(&renewed_material.public_key_armored).expect("renewed public key");
    let renewed_subkey = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == signing_fingerprint)
        .expect("renewed signing subkey");
    assert_eq!(
        renewed_subkey
            .signatures
            .iter()
            .filter(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
            .count(),
        1,
        "renewal must replace the selected effective binding",
    );
    let renewed_binding = renewed_subkey
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(RENEWAL_TIME as u32))
        .expect("replacement binding signature");
    let regenerated = renewed_binding
        .config()
        .into_iter()
        .flat_map(|config| {
            config
                .hashed_subpackets
                .iter()
                .chain(&config.unhashed_subpackets)
        })
        .find_map(|subpacket| match &subpacket.data {
            SubpacketData::EmbeddedSignature(signature) => Some(signature.as_ref()),
            _ => None,
        })
        .expect("regenerated embedded back signature");
    assert_ne!(regenerated, &defective_back_signature);
    regenerated
        .verify_primary_key_binding(&renewed_subkey.key, &renewed.primary_key)
        .expect("regenerated back signature verifies");
    assert!(authentication_signature_acceptable(
        regenerated,
        RENEWAL_TIME
    ));
    assert!(signature_issuer_consistent(
        regenerated,
        &renewed_subkey.key
    ));

    let config = regenerated.config().expect("v4 regenerated back signature");
    assert_eq!(config.hash_alg, HashAlgorithm::Sha256);
    assert_eq!(config.hashed_subpackets.len(), 2);
    assert!(config.hashed_subpackets.iter().all(|subpacket| matches!(
        subpacket.data,
        SubpacketData::SignatureCreationTime(_) | SubpacketData::IssuerFingerprint(_)
    )));
    assert_eq!(config.unhashed_subpackets.len(), 1);
    assert!(matches!(
        config.unhashed_subpackets[0].data,
        SubpacketData::IssuerKeyId(_)
    ));
    let expected_fingerprint = renewed_subkey.key.fingerprint();
    let expected_key_id = renewed_subkey.key.legacy_key_id();
    assert_eq!(
        regenerated.issuer_fingerprint(),
        vec![&expected_fingerprint]
    );
    assert_eq!(regenerated.issuer_key_id(), vec![&expected_key_id]);
}

fn subkey_policy_snapshot(
    certificate: &SignedPublicKey,
    fingerprint: &str,
    reference_time: u64,
) -> (bool, Option<u32>, Option<u32>) {
    let candidates = all_components(std::slice::from_ref(certificate));
    let policy = validate_certificate(
        certificate,
        &candidates,
        reference_time,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("inspect certificate policy");
    let subkey = certificate
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .expect("selected subkey policy");
    let component = policy.subkey(&subkey.key).expect("selected subkey policy");
    let component = component.policy();
    (
        component.authenticated,
        component
            .effective_signature
            .and_then(signature_creation_time),
        component.key_expiration_seconds,
    )
}

fn identity_certification(
    secret: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    creation_time: u32,
    expiration_seconds: Option<u32>,
) -> Signature {
    identity_certification_with_hash(
        secret,
        tag,
        identity,
        creation_time,
        expiration_seconds,
        HashAlgorithm::Sha256,
    )
}

fn identity_certification_with_hash(
    secret: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    creation_time: u32,
    expiration_seconds: Option<u32>,
    hash: HashAlgorithm,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        hash,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("creation subpacket"),
    );
    if let Some(seconds) = expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                seconds,
            )))
            .expect("expiration subpacket"),
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
        .expect("sign identity certification")
}

/// Runs the production certificate policy and returns the primary
/// component's mutation authorization.
fn authorize_primary_mutation(
    certificate: &SignedPublicKey,
    candidates: &[PublicComponent],
) -> Result<(), MutationAuthorizationError> {
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(certificate, candidates, 1_700_000_120, &mut budget)
        .expect("validate certificate policy");
    policy.primary_component().authorize_mutation()
}

fn add_designated_revoker_declaration(
    secret: &SignedSecretKey,
    certificate: &mut SignedPublicKey,
    revoker: &SignedPublicKey,
) {
    let declaration =
        designated_revoker_declaration(secret, certificate, revoker, RevocationKeyClass::Default);
    certificate.details.direct_signatures.push(declaration);
}

fn designated_revoker_declaration(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
    revoker: &SignedPublicKey,
    class: RevocationKeyClass,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_010,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint subpacket"),
        Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
            class,
            revoker.primary_key.algorithm(),
            revoker.primary_key.fingerprint().as_bytes(),
        )))
        .expect("revocation key subpacket"),
    ];
    let declaration = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            &certificate.primary_key,
        )
        .expect("sign designated revoker declaration");
    declaration
        .verify_key(&certificate.primary_key)
        .expect("verify designated revoker declaration");
    declaration
}

fn identity_designated_revoker_declaration(
    secret: &SignedSecretKey,
    tag: Tag,
    identity: &impl Serialize,
    revoker: &SignedPublicKey,
    class: RevocationKeyClass,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_010,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint subpacket"),
        Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
            class,
            revoker.primary_key.algorithm(),
            revoker.primary_key.fingerprint().as_bytes(),
        )))
        .expect("revocation key subpacket"),
    ];
    let declaration = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            tag,
            identity,
        )
        .expect("sign identity-scoped revoker declaration");
    declaration
        .verify_certification(secret.primary_key.public_key(), tag, identity)
        .expect("verify identity-scoped revoker declaration");
    declaration
}

fn signed_key_revocation(
    signer: &SignedSecretKey,
    certificate: &SignedPublicKey,
    hashed_fingerprint: bool,
    unhashed_key_id: bool,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        signer.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_020,
        )))
        .expect("creation subpacket"),
    );
    if hashed_fingerprint {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                signer.primary_key.fingerprint(),
            ))
            .expect("issuer fingerprint subpacket"),
        );
    }
    if unhashed_key_id {
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(
                signer.primary_key.legacy_key_id(),
            ))
            .expect("issuer key ID subpacket"),
        );
    }
    config
        .sign_key(
            &signer.primary_key,
            &Password::empty(),
            &certificate.primary_key,
        )
        .expect("sign key revocation")
}

fn signed_soft_key_revocation(
    signer: &SignedSecretKey,
    certificate: &SignedPublicKey,
    creation_time: u32,
    reason: RevocationCode,
    expiration_seconds: Option<u32>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        signer.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("revocation reason subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signer.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint subpacket"),
    ];
    if let Some(seconds) = expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                seconds,
            )))
            .expect("signature expiration subpacket"),
        );
    }
    config
        .sign_key(
            &signer.primary_key,
            &Password::empty(),
            &certificate.primary_key,
        )
        .expect("sign soft key revocation")
}

fn signed_soft_subkey_revocation(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
    subkey: &pgp::packet::PublicSubkey,
    creation_time: u32,
    reason: RevocationCode,
    expiration_seconds: Option<u32>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyRevocation,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("revocation reason subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            secret.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint subpacket"),
    ];
    if let Some(seconds) = expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                seconds,
            )))
            .expect("signature expiration subpacket"),
        );
    }
    config
        .sign_subkey_binding(
            &secret.primary_key,
            &certificate.primary_key,
            &Password::empty(),
            subkey,
        )
        .expect("sign soft subkey revocation")
}

fn newer_subkey_binding(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
    subkey: &SignedPublicSubKey,
    creation_time: u32,
) -> Signature {
    let mut config = subkey
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .and_then(Signature::config)
        .cloned()
        .expect("generated subkey binding template");
    config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::SignatureCreationTime(_)));
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("replacement binding creation subpacket"),
    );
    config
        .sign_subkey_binding(
            &secret.primary_key,
            &certificate.primary_key,
            &Password::empty(),
            &subkey.key,
        )
        .expect("sign newer subkey binding")
}

fn signed_user_id_revocation(signer: &SignedSecretKey, certificate: &SignedPublicKey) -> Signature {
    let user = certificate
        .details
        .users
        .first()
        .expect("generated user ID");
    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        signer.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_020,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signer.primary_key.fingerprint(),
        ))
        .expect("issuer fingerprint subpacket"),
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(
            signer.primary_key.legacy_key_id(),
        ))
        .expect("issuer key ID subpacket"),
    ];
    config
        .sign_certification(
            &signer.primary_key,
            &certificate.primary_key,
            &Password::empty(),
            Tag::UserId,
            &user.id,
        )
        .expect("sign user ID revocation")
}

/// Builds request documents whose public half is exactly `certificate`.
///
/// The secret document carries only the primary secret packet, so the
/// preflight's secret projection contributes nothing and a hand-built
/// certificate reaches the renewal unchanged.
fn mutation_documents(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
) -> (Vec<u8>, Vec<u8>) {
    let public_packets = certificate.to_bytes().expect("serialize certificate");
    mutation_documents_from_packets(secret, &public_packets)
}

/// As [`mutation_documents`], for a certificate held as raw packet bytes.
fn mutation_documents_from_packets(
    secret: &SignedSecretKey,
    public_packets: &[u8],
) -> (Vec<u8>, Vec<u8>) {
    let public_packets = public_packets.to_vec();
    let secret_packets = secret.to_bytes().expect("serialize secret key");
    let secret_stream =
        RawPacketStream::parse(&secret_packets, MAX_KEY_PACKETS).expect("scan secret key");
    let primary = secret_stream
        .packets()
        .first()
        .expect("secret key primary packet");
    assert_eq!(primary.tag(), 5);
    let public_stream =
        RawPacketStream::parse(&public_packets, MAX_KEY_PACKETS).expect("scan certificate");
    let mut private = Vec::new();
    private.extend_from_slice(secret_stream.raw(primary));
    for span in public_stream.packets().iter().skip(1) {
        private.extend_from_slice(public_stream.raw(span));
    }
    (
        armor_key_packets(&private, BlockType::PrivateKey).expect("armor secret document"),
        armor_key_packets(&public_packets, BlockType::PublicKey).expect("armor certificate"),
    )
}

/// Renews the primary through the production request path.
fn renew_test_primary_material(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
) -> (KeyMaterial, CertificateIndex) {
    let (private_key, public_key) = mutation_documents(secret, certificate);
    let fingerprint = fingerprint_hex(&certificate.primary_key);
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: fingerprint.clone(),
        component_fingerprints: vec![fingerprint],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: 1_700_000_130,
    })
    .expect("renew primary");
    (success.key_material, success.certificate_index)
}

/// Renews and reparses the ordinary transferable public result.
fn renew_test_primary(secret: &SignedSecretKey, certificate: &SignedPublicKey) -> SignedPublicKey {
    let (material, _) = renew_test_primary_material(secret, certificate);
    parse_single_public(&material.public_key_armored).expect("reparse renewed certificate")
}

fn revocation_authorities(certificate: &SignedPublicKey) -> Vec<DesignatedRevokerId> {
    let candidates = all_components(std::slice::from_ref(certificate));
    let mut budget = OpenPgpPolicyBudget::default();
    validate_certificate(certificate, &candidates, 1_700_000_130, &mut budget)
        .expect("evaluate revocation authorities")
        .legacy_designated_revokers()
        .cloned()
        .collect()
}

fn update_test_component(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
    component_fingerprint: String,
    candidate_revocation_keys: Vec<Vec<u8>>,
) -> Result<ExpirationUpdateSuccess, ExpirationUpdateFailure> {
    let (private_key, public_key) = mutation_documents(secret, certificate);
    update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: fingerprint_hex(&certificate.primary_key),
        component_fingerprints: vec![component_fingerprint],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys,
        reference_time_epoch_seconds: 1_700_000_130,
    })
}

/// Test-only mirror of the newest-signature selection the renewal uses.
fn newest_index(
    signatures: &[Signature],
    indices: &[usize],
    context: PolicyContext,
    cross_certified: impl FnMut(&Signature) -> Result<bool, ExpirationUpdateFailure>,
) -> Result<Option<usize>, ExpirationUpdateFailure> {
    let selection = select_newest_policy_signature_in(
        indices.iter().map(|index| &signatures[*index]),
        context,
        cross_certified,
    )?;
    Ok(match selection {
        PolicySelection::Missing => None,
        PolicySelection::Selected { signature, .. } => indices
            .iter()
            .copied()
            .find(|index| std::ptr::eq(&signatures[*index], signature)),
        PolicySelection::Conflict => return Err(ExpirationUpdateFailure::TimeConflict),
    })
}

/// Whether any identity's revocation evidence cannot be resolved.
fn any_identity_revocation_indeterminate(
    certificate: &SignedPublicKey,
    candidates: &[PublicComponent],
) -> bool {
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(certificate, candidates, 1_700_000_120, &mut budget)
        .expect("validate certificate policy");
    (0..certificate.details.users.len()).any(|index| {
        policy
            .user_id_at(index)
            .is_some_and(|identity| identity.revocation_status.is_indeterminate())
    }) || (0..certificate.details.user_attributes.len()).any(|index| {
        policy
            .user_attribute_at(index)
            .is_some_and(|identity| identity.revocation_status.is_indeterminate())
    })
}

/// Whether the certificate's first User ID reads as revoked.
fn first_user_id_is_revoked(certificate: &SignedPublicKey, reference_time: u64) -> bool {
    let candidates = all_components(std::slice::from_ref(certificate));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(certificate, &candidates, reference_time, &mut budget)
        .expect("validate certificate policy");
    policy
        .user_id_at(0)
        .expect("first user ID policy")
        .revocation_status
        .is_revoked()
}

fn direct_signature_with_creation_times(
    secret: &SignedSecretKey,
    creation_times: &[u32],
) -> Signature {
    direct_signature_with_policy(secret, creation_times, None, false)
}

fn direct_signature_with_policy(
    secret: &SignedSecretKey,
    creation_times: &[u32],
    key_expiration_seconds: Option<u32>,
    include_unhashed_issuer: bool,
) -> Signature {
    let mut config = SignatureConfig::v4(
        SignatureType::Key,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = creation_times
        .iter()
        .copied()
        .map(|time| {
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                time,
            )))
            .expect("creation subpacket")
        })
        .collect();
    if let Some(seconds) = key_expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(
                seconds,
            )))
            .expect("key expiration subpacket"),
        );
    }
    if include_unhashed_issuer {
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(
                secret.primary_key.legacy_key_id(),
            ))
            .expect("issuer subpacket"),
        );
    }
    config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign direct signature")
}

#[test]
fn renewal_retains_local_certification_only_in_private_output() {
    const RENEWAL_TIME: u64 = 1_700_000_130;
    let (target_secret, mut target_public) =
        generated_test_certificate("Local certification <local-certification@example.test>");
    let (certifier, _) =
        generated_test_certificate("Local certifier <local-certifier@example.test>");
    let user_id = target_public.details.users[0].id.clone();
    let mut config = SignatureConfig::v4(
        SignatureType::CertPositive,
        certifier.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_010,
        )))
        .expect("creation subpacket"),
        Subpacket::critical(SubpacketData::ExportableCertification(false))
            .expect("non-exportable certification subpacket"),
    ];
    let local_certification = config
        .sign_certification(
            &certifier.primary_key,
            &target_public.primary_key,
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign local certification");
    target_public.details.users[0]
        .signatures
        .push(local_certification);

    let has_local_certification = |certificate: &SignedPublicKey| {
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
    };
    assert!(has_local_certification(&target_public));

    let (private_key, public_key) = mutation_documents(&target_secret, &target_public);
    let fingerprint = fingerprint_hex(&target_public.primary_key);
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: fingerprint.clone(),
        component_fingerprints: vec![fingerprint],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew certificate carrying a local certification");
    let material = success.key_material;

    let renewed_public = parse_single_public(&material.public_key_armored)
        .expect("parse renewed public certificate");
    assert!(!has_local_certification(&renewed_public));

    let (private_projection, _) = project_secret_certificate(&material.private_key_armored)
        .expect("project renewed private certificate");
    let renewed_private_projection =
        parse_single_public(&private_projection).expect("parse renewed private projection");
    assert!(has_local_certification(&renewed_private_projection));
}

#[test]
fn replacement_hash_upgrades_sha1_for_supported_signers() {
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::RSA,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha1,
        ),
        Ok(HashAlgorithm::Sha256),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::DSA,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha1,
        ),
        Ok(HashAlgorithm::Sha256),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::ECDSA,
            HashAlgorithm::Sha384,
            HashAlgorithm::Sha1,
        ),
        Ok(HashAlgorithm::Sha384),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::EdDSALegacy,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha1,
        ),
        Ok(HashAlgorithm::Sha256),
    );
}

#[test]
fn replacement_hash_respects_signer_and_aws_lc_compatibility() {
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::ECDSA,
            HashAlgorithm::Sha384,
            HashAlgorithm::Sha256,
        ),
        Ok(HashAlgorithm::Sha384),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::ECDSA,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha512,
        ),
        Ok(HashAlgorithm::Sha512),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::RSA,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha384,
        ),
        Ok(HashAlgorithm::Sha256),
    );
    assert_eq!(
        replacement_hash(
            PublicKeyAlgorithm::ECDSA,
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha1,
        ),
        Err(ExpirationUpdateFailure::UnsupportedSigningHash),
    );
}

#[test]
fn duplicate_or_blank_component_selection_is_rejected() {
    assert_eq!(
        normalize_selected(&["AA".to_owned(), "aa".to_owned()]),
        Err(ExpirationUpdateFailure::ComponentNotFound)
    );
    assert_eq!(
        normalize_selected(&[" -- ".to_owned()]),
        Err(ExpirationUpdateFailure::ComponentNotFound)
    );
}

#[test]
fn sha1_only_certificate_is_unreadable_but_still_renewable_with_a_modern_hash() {
    // The project's chosen legacy rescue: a certificate whose only
    // self-certification is past the SHA-1 cutoff authenticates nothing,
    // yet renewal must still work — the renewal is what replaces that
    // signature with a modern one.
    let (secret, mut certificate) = generated_test_certificate_with_kind(
        "Legacy Hash <legacy-hash@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let user_id = certificate.details.users[0].id.clone();
    certificate.details.users[0].signatures = vec![identity_certification_with_hash(
        &secret,
        Tag::UserId,
        &user_id,
        1_700_000_010,
        None,
        HashAlgorithm::Sha1,
    )];

    let candidates = all_components(std::slice::from_ref(&certificate));
    let mut budget = OpenPgpPolicyBudget::default();
    let before = validate_certificate(&certificate, &candidates, 1_700_000_120, &mut budget)
        .expect("inspect legacy certificate");
    assert!(!before.primary.authenticated);
    assert!(before.verified_user_ids_for_test().is_empty());
    assert_eq!(
        before.authorize_primary_renewal(),
        Ok(RenewalAuthorization::TemplateOnly),
    );
    drop(before);

    let renewed = renew_test_primary(&secret, &certificate);
    let signatures = &renewed.details.users[0].signatures;
    assert_eq!(signatures.len(), 1);
    assert_eq!(
        signatures[0].config().map(|config| config.hash_alg),
        Some(HashAlgorithm::Sha256),
    );
    assert_eq!(signature_creation_time(&signatures[0]), Some(1_700_000_130));

    let candidates = all_components(std::slice::from_ref(&renewed));
    let mut budget = OpenPgpPolicyBudget::default();
    let after = validate_certificate(&renewed, &candidates, 1_700_000_130, &mut budget)
        .expect("inspect renewed certificate");
    assert!(after.primary.authenticated);
    assert_eq!(
        after.authorize_primary_renewal(),
        Ok(RenewalAuthorization::Authenticated),
    );
}

#[test]
fn a_newer_weak_hash_certification_never_resurrects_a_revoked_identity() {
    let (secret, mut certificate) = generated_test_certificate_with_kind(
        "Revoked Identity <revoked@example.test>",
        OpenPgpKeyKind::Rsa,
        3_072,
    );
    let revoked_id = certificate.details.users[0].id.clone();
    let live_id =
        pgp::packet::UserId::from_str(Default::default(), "Live Identity <live@example.test>")
            .expect("second user id");

    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_020,
        )))
        .expect("creation subpacket"),
    ];
    let revocation = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &revoked_id,
        )
        .expect("sign certification revocation");
    certificate.details.users[0].signatures = vec![
        identity_certification(&secret, Tag::UserId, &revoked_id, 1_700_000_010, None),
        revocation,
        // Newest of all three, but only ever a renewal template.
        identity_certification_with_hash(
            &secret,
            Tag::UserId,
            &revoked_id,
            1_700_000_030,
            None,
            HashAlgorithm::Sha1,
        ),
    ];
    certificate.details.users.push(SignedUser::new(
        live_id.clone(),
        vec![identity_certification(
            &secret,
            Tag::UserId,
            &live_id,
            1_700_000_015,
            None,
        )],
    ));
    assert!(first_user_id_is_revoked(&certificate, 1_700_000_120));

    let renewed = renew_test_primary(&secret, &certificate);
    assert!(first_user_id_is_revoked(&renewed, 1_700_000_130));
    // The revoked identity keeps exactly the packets it had: no fresh
    // certification was issued over it.
    let signatures = &renewed.details.users[0].signatures;
    assert_eq!(signatures.len(), 3);
    assert!(
        !signatures
            .iter()
            .any(|signature| signature_creation_time(signature) == Some(1_700_000_130))
    );
}

#[test]
fn a_far_future_dated_identity_certification_causes_a_time_conflict() {
    let (secret, mut certificate) =
        generated_test_certificate("Future Dated <future-dated@example.test>");
    let user_id = certificate.details.users[0].id.clone();
    certificate.details.users[0].signatures = vec![
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_010, None),
        identity_certification(&secret, Tag::UserId, &user_id, 1_800_000_000, None),
    ];

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    let error = update_test_component(&secret, &certificate, fingerprint, Vec::new())
        .err()
        .expect("far-future certification must block renewal");

    assert_eq!(error, ExpirationUpdateFailure::TimeConflict);
}

#[test]
fn a_near_future_dated_identity_certification_is_safely_superseded() {
    let (secret, mut certificate) =
        generated_test_certificate("Near Future <near-future@example.test>");
    let user_id = certificate.details.users[0].id.clone();
    certificate.details.users[0].signatures = vec![
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_010, None),
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_200, None),
    ];

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    let success = update_test_component(&secret, &certificate, fingerprint, Vec::new())
        .expect("near-future certification can be safely superseded");
    let renewed = parse_single_public(&success.key_material.public_key_armored)
        .expect("reparse renewed certificate");
    let times = renewed.details.users[0]
        .signatures
        .iter()
        .filter_map(signature_creation_time)
        .collect::<std::collections::BTreeSet<_>>();

    assert_eq!(times, [1_700_000_200, 1_700_000_201].into_iter().collect(),);
}

#[test]
fn a_far_future_dated_direct_key_signature_causes_a_time_conflict() {
    let (secret, mut certificate) =
        generated_test_certificate("Future Direct <future-direct@example.test>");
    certificate.details.direct_signatures = vec![
        direct_signature_with_creation_times(&secret, &[1_700_000_010]),
        direct_signature_with_creation_times(&secret, &[1_800_000_000]),
    ];

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    let error = update_test_component(&secret, &certificate, fingerprint, Vec::new())
        .err()
        .expect("far-future Direct-Key signature must block renewal");

    assert_eq!(error, ExpirationUpdateFailure::TimeConflict);
}

#[test]
fn a_far_future_dated_subkey_binding_causes_a_time_conflict() {
    let (mut secret, certificate) =
        generated_test_certificate("Future Subkey <future-subkey@example.test>");
    let subkey = certificate
        .public_subkeys
        .first()
        .expect("generated subkey")
        .clone();
    let fingerprint = fingerprint_hex(&subkey.key);
    let future_binding = newer_subkey_binding(&secret, &certificate, &subkey, 1_800_000_000);
    let secret_subkey = secret
        .secret_subkeys
        .iter_mut()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .expect("generated secret subkey");
    secret_subkey.signatures.push(future_binding);

    let error = renew_subkey(&secret, &fingerprint)
        .expect_err("far-future subkey binding must block renewal");

    assert_eq!(error, ExpirationUpdateFailure::TimeConflict);
}

#[test]
fn repeated_same_second_renewals_advance_the_replacement_by_one_second() {
    const RENEWAL_TIME: u64 = 1_700_000_120;
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Same Second <same-second@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let mut material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let material = KeyMaterial {
        private_key_armored: std::mem::take(&mut material.private_key_armored),
        public_key_armored: std::mem::take(&mut material.public_key_armored),
        fingerprint: std::mem::take(&mut material.fingerprint),
    };
    let renew = |material: &KeyMaterial| {
        update_expiration_request(ExpirationUpdateInput {
            private_key: material.private_key_armored.clone(),
            public_key: material.public_key_armored.clone(),
            expected_primary_fingerprint: material.fingerprint.clone(),
            component_fingerprints: vec![material.fingerprint.clone()],
            expires_at_epoch_seconds: Some(1_800_000_000),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: RENEWAL_TIME,
        })
    };
    let first = renew(&material).expect("first renewal").key_material;
    assert_eq!(primary_certification_time(&first), RENEWAL_TIME);
    // Renewing again within the same second succeeds by dating the
    // replacement one second after the statement it supersedes, instead of
    // failing the way a strict `replacement > template` comparison did.
    let second = renew(&first).expect("same-second renewal").key_material;
    assert_eq!(primary_certification_time(&second), RENEWAL_TIME + 1);
    // The bump has now put the certificate's own newest self-signature one
    // second ahead of this frozen clock, and a future-dated signature is in
    // no policy tier — so a third attempt in the *same* second is refused.
    // A real caller's clock advances past it; this is the documented edge
    // of the bump, not a retry loop.
    assert!(renew(&second).is_err());
}

#[test]
fn expiration_renewal_preserves_a_fallback_primary_user_id() {
    // Neither identity carries an explicit primary marker, so the winner
    // is decided by certification time. Renewing both at one instant would
    // flatten that order and hand the role to the other identity.
    const NEWER: &str = "AAA Identity <aaa@example.test>";
    const OLDER: &str = "ZZZ Identity <zzz@example.test>";
    let (secret, mut certificate) = generated_test_certificate(NEWER);
    let newer_id = certificate.details.users[0].id.clone();
    let older_id =
        pgp::packet::UserId::from_str(Default::default(), OLDER).expect("second user id");
    certificate.details.users[0].signatures = vec![identity_certification(
        &secret,
        Tag::UserId,
        &newer_id,
        1_700_000_020,
        None,
    )];
    certificate.details.users.push(SignedUser::new(
        older_id.clone(),
        vec![identity_certification(
            &secret,
            Tag::UserId,
            &older_id,
            1_700_000_010,
            None,
        )],
    ));
    assert!(older_id.id() > newer_id.id());

    let candidates = all_components(std::slice::from_ref(&certificate));
    let mut budget = OpenPgpPolicyBudget::default();
    let before = validate_certificate(&certificate, &candidates, 1_700_000_120, &mut budget)
        .expect("inspect certificate");
    assert_eq!(before.primary_user_id_for_test().as_deref(), Some(NEWER));
    drop(before);

    let renewed = renew_test_primary(&secret, &certificate);
    let candidates = all_components(std::slice::from_ref(&renewed));
    let mut budget = OpenPgpPolicyBudget::default();
    let after = validate_certificate(&renewed, &candidates, 1_700_000_130, &mut budget)
        .expect("inspect renewed certificate");
    assert_eq!(after.primary_user_id_for_test().as_deref(), Some(NEWER));
}

/// Creation time of the primary User ID's effective certification.
fn primary_certification_time(material: &KeyMaterial) -> u64 {
    let certificate = parse_single_public(&material.public_key_armored).expect("parse certificate");
    certificate.details.users[0]
        .signatures
        .iter()
        .filter(|signature| is_certification(signature.typ()))
        .filter_map(signature_creation_time)
        .map(u64::from)
        .max()
        .expect("primary certification")
}

#[test]
fn renewal_preserves_but_never_recertifies_a_non_canonical_user_attribute() {
    // rPGP normalises a non-standard image header on re-serialization and
    // hashes that normalized form, while RFC 9580 §5.2.4 signs the raw stored
    // bytes. Renewing such an attribute's certification would emit a
    // signature over different data, so the renewal keeps the raw body and
    // existing certification untouched while the rest renews normally.
    const RENEWAL_TIME: u64 = 1_700_000_130;
    let (secret, certificate) =
        generated_test_certificate("Attribute Body <attribute-body@example.test>");
    // Image type, then a v1 JPEG header that declares 20 bytes where rPGP
    // always writes the canonical 16, then the image itself.
    let mut body = vec![30_u8, 0x01, 0x14, 0x00, 0x01, 0x01];
    body.extend_from_slice(&[0x00; 12]);
    body.extend_from_slice(&[0xde, 0xad, 0xbe, 0xef]);
    body.extend_from_slice(b"jpegbytes");
    let attribute = UserAttribute::try_from_reader(
        PacketHeader::new_fixed(
            Tag::UserAttribute,
            u32::try_from(body.len()).expect("attribute body length"),
        ),
        std::io::Cursor::new(body.as_slice()),
    )
    .expect("parse non-standard user attribute");
    assert_ne!(
        serialize_packet_body(&attribute).expect("serialize attribute"),
        body,
        "fixture must exercise a body rPGP does not reproduce",
    );
    let certification =
        identity_certification(&secret, Tag::UserAttribute, &attribute, 1_700_000_010, None);

    // RFC 9580 §10.1 puts identities ahead of subkeys, and the composed
    // parser follows it, so splice the attribute in before the first one.
    let public_packets = certificate.to_bytes().expect("serialize certificate");
    let stream =
        RawPacketStream::parse(&public_packets, MAX_KEY_PACKETS).expect("scan certificate");
    let first_subkey = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == 14)
        .expect("generated public subkey");
    let mut document = Vec::new();
    for packet in &stream.packets()[..first_subkey] {
        document.extend_from_slice(stream.raw(packet));
    }
    append_test_packet(Tag::UserAttribute, &body, &mut document);
    append_test_packet(
        Tag::Signature,
        &serialize_packet_body(&certification).expect("serialize certification"),
        &mut document,
    );
    for packet in &stream.packets()[first_subkey..] {
        document.extend_from_slice(stream.raw(packet));
    }
    let attributed = parse_single_public(&document).expect("parse certificate with attribute");
    assert_eq!(attributed.details.user_attributes.len(), 1);

    let (private_key, public_key) = mutation_documents_from_packets(&secret, &document);
    let fingerprint = fingerprint_hex(&certificate.primary_key);
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: fingerprint.clone(),
        component_fingerprints: vec![fingerprint],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew certificate carrying a non-standard user attribute");
    let renewed_material = success.key_material;

    let renewed_stream =
        RawPacketStream::parse(&renewed_material.public_key_armored, MAX_KEY_PACKETS)
            .expect("scan renewed certificate");
    let renewed_bodies = renewed_stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() == 17)
        .map(|packet| renewed_stream.body_to_vec(packet))
        .collect::<Vec<_>>();
    assert_eq!(renewed_bodies, vec![body]);
    let renewed = parse_single_public(&renewed_material.public_key_armored)
        .expect("parse renewed certificate");
    let times = renewed.details.user_attributes[0]
        .signatures
        .iter()
        .filter_map(signature_creation_time)
        .collect::<Vec<_>>();
    assert_eq!(
        times,
        vec![1_700_000_010],
        "the non-canonical attribute keeps its original certification",
    );
    let user_id_times = renewed.details.users[0]
        .signatures
        .iter()
        .filter_map(signature_creation_time)
        .collect::<Vec<_>>();
    assert_eq!(
        user_id_times,
        vec![RENEWAL_TIME as u32],
        "the rest of the certificate still renews",
    );
}

#[test]
fn renewal_survives_duplicate_primary_user_id_markers() {
    // A merged certificate can contain two certifications that both assert
    // IsPrimary; renewing them at one instant used to let the tie-break flip
    // the primary and fail the post-renewal check.
    const RENEWAL_TIME: u64 = 1_700_000_130;
    let (mut secret, _) = generated_test_certificate("First <first@example.test>");
    let primary_flagged =
        |secret: &SignedSecretKey, user_id: &pgp::packet::UserId, creation_time: u32| {
            let mut config = SignatureConfig::v4(
                SignatureType::CertPositive,
                secret.primary_key.algorithm(),
                HashAlgorithm::Sha256,
            );
            config.hashed_subpackets = vec![
                Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                    creation_time,
                )))
                .expect("creation subpacket"),
                Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary subpacket"),
            ];
            config
                .sign_certification(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    user_id.tag(),
                    user_id,
                )
                .expect("sign primary-flagged certification")
        };
    let first_id = pgp::packet::UserId::from_str(Default::default(), "First <first@example.test>")
        .expect("first user id");
    let second_id =
        pgp::packet::UserId::from_str(Default::default(), "Second <second@example.test>")
            .expect("second user id");
    let first_certification = primary_flagged(&secret, &first_id, 1_700_000_010);
    let second_certification = primary_flagged(&secret, &second_id, 1_700_000_020);
    secret.details.users = vec![
        SignedUser::new(first_id, vec![first_certification]),
        SignedUser::new(second_id, vec![second_certification]),
    ];

    let public = secret.to_public_key();
    let fingerprint = fingerprint_hex(secret.primary_key.public_key());
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: secret
            .to_armored_bytes(Default::default())
            .expect("armored secret key"),
        public_key: public
            .to_armored_bytes(Default::default())
            .expect("armored public key"),
        expected_primary_fingerprint: fingerprint.clone(),
        component_fingerprints: vec![fingerprint],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew certificate with duplicate primary markers");

    let renewed = parse_single_public(&success.key_material.public_key_armored)
        .expect("parse renewed certificate");
    let flagged = renewed
        .details
        .users
        .iter()
        .filter(|user| user.signatures.iter().any(signature_is_primary))
        .map(|user| user.id.id().to_vec())
        .collect::<Vec<_>>();
    assert_eq!(
        flagged,
        vec![b"Second <second@example.test>".to_vec()],
        "the policy primary keeps the marker and the stale duplicate is stripped",
    );
}

#[test]
fn renewal_drops_a_stale_back_signature_from_the_unhashed_area() {
    // A template may carry a primary-key binding in the unhashed area, so
    // stripping embedded signatures from the hashed area alone would retain
    // both the stale and the fresh back-signature.
    const RENEWAL_TIME: u64 = 1_700_000_120;
    let (mut secret, _) =
        generated_test_certificate("Unhashed Backsig <unhashed-backsig@example.test>");
    let signing_position = secret
        .secret_subkeys
        .iter()
        .position(|subkey| {
            subkey.signatures.iter().any(|signature| {
                signature.typ() == Some(SignatureType::SubkeyBinding)
                    && binding_designates_signature_capable_subkey(
                        signature,
                        subkey.key.algorithm(),
                    )
            })
        })
        .expect("generated signing subkey");
    let signing_public = secret.secret_subkeys[signing_position]
        .key
        .public_key()
        .clone();
    let binding = secret.secret_subkeys[signing_position]
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .cloned()
        .expect("generated binding");
    let mut config = binding.config().cloned().expect("v4 binding config");
    let embedded = config
        .hashed_subpackets
        .iter()
        .find_map(|subpacket| match &subpacket.data {
            SubpacketData::EmbeddedSignature(signature) => Some(signature.clone()),
            _ => None,
        })
        .expect("generated back signature");
    // The unhashed area is not covered by the signature, so a peer may add
    // this copy without invalidating anything.
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::EmbeddedSignature(embedded))
            .expect("unhashed back signature"),
    );
    let doctored = Signature::from_config(
        config,
        binding.signed_hash_value().expect("binding hash prefix"),
        binding.signature().cloned().expect("binding bytes"),
    )
    .expect("rebuild binding with an unhashed back signature");
    doctored
        .verify_subkey_binding(secret.primary_key.public_key(), &signing_public)
        .expect("doctored binding remains valid");
    assert_eq!(embedded_signature_count(&doctored), 2);
    secret.secret_subkeys[signing_position].signatures = vec![doctored];

    let public = secret.to_public_key();
    let signing_fingerprint = fingerprint_hex(&signing_public);
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: secret
            .to_armored_bytes(Default::default())
            .expect("armored secret key"),
        public_key: public
            .to_armored_bytes(Default::default())
            .expect("armored public key"),
        expected_primary_fingerprint: fingerprint_hex(secret.primary_key.public_key()),
        component_fingerprints: vec![signing_fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew signing subkey");
    let renewed =
        parse_single_public(&success.key_material.public_key_armored).expect("renewed public key");
    let renewed_binding = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == signing_fingerprint)
        .expect("renewed signing subkey")
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .expect("renewed binding");
    assert_eq!(embedded_signature_count(renewed_binding), 1);
}

#[test]
fn renewal_normalizes_new_issuer_hints_without_rewriting_history() {
    const RENEWAL_TIME: u64 = 1_700_000_120;

    fn with_unhashed_issuer_hints(
        signature: &Signature,
        fingerprint: Fingerprint,
        key_id: KeyId,
    ) -> Signature {
        let mut config = signature.config().cloned().expect("v4 signature config");
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerFingerprint(fingerprint))
                .expect("unhashed issuer fingerprint"),
            Subpacket::regular(SubpacketData::IssuerKeyId(key_id)).expect("unhashed issuer key ID"),
        ];
        Signature::from_config(
            config,
            signature.signed_hash_value().expect("signed hash prefix"),
            signature.signature().cloned().expect("signature bytes"),
        )
        .expect("rebuild signature with unhashed issuer hints")
    }

    let (mut secret, _) = generated_test_certificate("Merged Issuer <merged-issuer@example.test>");
    let user_id = secret.details.users[0].id.clone();
    secret.details.direct_signatures = vec![direct_signature_with_policy(
        &secret,
        &[1_700_000_010],
        Some(86_400),
        false,
    )];
    secret.details.users[0].signatures = vec![identity_certification(
        &secret,
        Tag::UserId,
        &user_id,
        1_700_000_010,
        None,
    )];
    let mut public = secret.to_public_key();

    let correct_fingerprint = secret.primary_key.fingerprint();
    let correct_key_id = secret.primary_key.legacy_key_id();
    let wrong_fingerprint = Fingerprint::V4([0; 20]);
    let wrong_key_id = KeyId::from([0; 8]);
    assert_ne!(correct_fingerprint, wrong_fingerprint);
    assert_ne!(correct_key_id, wrong_key_id);

    secret.details.direct_signatures[0] = with_unhashed_issuer_hints(
        &secret.details.direct_signatures[0],
        correct_fingerprint.clone(),
        correct_key_id,
    );
    secret.details.users[0].signatures[0] = with_unhashed_issuer_hints(
        &secret.details.users[0].signatures[0],
        correct_fingerprint.clone(),
        correct_key_id,
    );
    public.details.direct_signatures[0] = with_unhashed_issuer_hints(
        &public.details.direct_signatures[0],
        wrong_fingerprint.clone(),
        wrong_key_id,
    );
    public.details.users[0].signatures[0] = with_unhashed_issuer_hints(
        &public.details.users[0].signatures[0],
        wrong_fingerprint,
        wrong_key_id,
    );

    let private_key = secret
        .to_armored_bytes(Default::default())
        .expect("armored secret copy");
    let public_key = public
        .to_armored_bytes(Default::default())
        .expect("armored public copy");
    let primary_fingerprint = fingerprint_hex(secret.primary_key.public_key());

    // The mutation preflight retains one complete signature variant instead
    // of synthesizing a packet from conflicting advisory metadata.
    let preflight = MutationPreflight::open(
        &private_key,
        &public_key,
        &[],
        &primary_fingerprint,
        RENEWAL_TIME,
    )
    .expect("merge conflicting issuer hints");
    for signature in [
        &preflight.canonical.semantic.details.direct_signatures[0],
        &preflight.canonical.semantic.details.users[0].signatures[0],
    ] {
        let config = signature.config().expect("merged v4 signature");
        assert_eq!(
            config
                .unhashed_subpackets
                .iter()
                .filter(|subpacket| matches!(subpacket.data, SubpacketData::IssuerFingerprint(_)))
                .count(),
            1,
        );
        assert_eq!(
            config
                .unhashed_subpackets
                .iter()
                .filter(|subpacket| matches!(subpacket.data, SubpacketData::IssuerKeyId(_)))
                .count(),
            1,
        );
    }
    drop(preflight);

    let success = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: primary_fingerprint.clone(),
        component_fingerprints: vec![primary_fingerprint],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew certificate with conflicting issuer hints");
    let renewed =
        parse_single_public(&success.key_material.public_key_armored).expect("renewed public key");
    let renewed_signatures = renewed
        .details
        .direct_signatures
        .iter()
        .filter(|signature| signature.typ() == Some(SignatureType::Key))
        .chain(
            renewed.details.users[0]
                .signatures
                .iter()
                .filter(|signature| is_certification(signature.typ())),
        )
        .collect::<Vec<_>>();
    assert_eq!(renewed_signatures.len(), 2);

    let mut normalized_signatures = 0;
    for signature in renewed_signatures {
        let config = signature.config().expect("renewed v4 signature");
        if signature_creation_time(signature).map(u64::from) != Some(RENEWAL_TIME) {
            // Renewal changes only the selected policy statement. Historical
            // packets remain byte-for-byte evidence, including their advisory
            // unhashed issuer hints.
            assert!(config.hashed_subpackets.iter().all(|subpacket| {
                !matches!(subpacket.data, SubpacketData::IssuerFingerprint(_))
            }));
            assert_eq!(
                config
                    .unhashed_subpackets
                    .iter()
                    .filter(|subpacket| {
                        matches!(subpacket.data, SubpacketData::IssuerFingerprint(_))
                    })
                    .count(),
                1,
            );
            continue;
        }
        normalized_signatures += 1;

        let hashed_fingerprints = config
            .hashed_subpackets
            .iter()
            .filter_map(|subpacket| match &subpacket.data {
                SubpacketData::IssuerFingerprint(fingerprint) => Some(fingerprint),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(hashed_fingerprints, vec![&correct_fingerprint]);
        assert!(
            config
                .hashed_subpackets
                .iter()
                .all(|subpacket| { !matches!(subpacket.data, SubpacketData::IssuerKeyId(_)) })
        );

        let unhashed_key_ids = config
            .unhashed_subpackets
            .iter()
            .filter_map(|subpacket| match &subpacket.data {
                SubpacketData::IssuerKeyId(key_id) => Some(key_id),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(unhashed_key_ids, vec![&correct_key_id]);
        assert!(
            config.unhashed_subpackets.iter().all(|subpacket| {
                !matches!(subpacket.data, SubpacketData::IssuerFingerprint(_))
            })
        );

        let issuer_position = config
            .hashed_subpackets
            .iter()
            .position(|subpacket| matches!(subpacket.data, SubpacketData::IssuerFingerprint(_)))
            .expect("issuer fingerprint position");
        let creation_position = config
            .hashed_subpackets
            .iter()
            .position(|subpacket| matches!(subpacket.data, SubpacketData::SignatureCreationTime(_)))
            .expect("creation-time position");
        assert!(
            creation_position < issuer_position,
            "creation time precedes the authenticated issuer metadata",
        );
    }
    assert_eq!(normalized_signatures, 2);
}

fn embedded_signature_count(signature: &Signature) -> usize {
    signature
        .config()
        .into_iter()
        .flat_map(|config| {
            config
                .hashed_subpackets
                .iter()
                .chain(&config.unhashed_subpackets)
        })
        .filter(|subpacket| matches!(subpacket.data, SubpacketData::EmbeddedSignature(_)))
        .count()
}

fn append_test_packet(tag: Tag, body: &[u8], output: &mut Vec<u8>) {
    PacketHeader::new_fixed(tag, u32::try_from(body.len()).expect("packet length"))
        .to_writer(output)
        .expect("write packet header");
    output.extend_from_slice(body);
}

#[test]
fn mutation_authorization_and_policy_failures_keep_stable_reasons() {
    assert_eq!(
        ExpirationUpdateFailure::from(MutationAuthorizationError::Revoked),
        ExpirationUpdateFailure::RevokedComponent,
    );
    assert_eq!(
        ExpirationUpdateFailure::from(MutationAuthorizationError::IndeterminateRevocation),
        ExpirationUpdateFailure::UnresolvedRevocationAuthority,
    );
    assert_eq!(
        ExpirationUpdateFailure::from(OpenPgpPolicyError::ResourceLimit),
        ExpirationUpdateFailure::ResourceLimit,
    );
}

#[test]
fn expired_prospective_primary_revocation_blocks_expiration_mutation() {
    let (secret, mut certificate) =
        generated_test_certificate("Retired Primary <retired-primary@example.test>");
    certificate
        .details
        .revocation_signatures
        .push(signed_soft_key_revocation(
            &secret,
            &certificate,
            1_700_000_010,
            RevocationCode::KeyRetired,
            Some(10),
        ));

    let candidates = all_components(std::slice::from_ref(&certificate));
    let policy = validate_certificate(
        &certificate,
        &candidates,
        1_700_000_130,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate primary policy");
    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    assert_eq!(
        update_test_component(&secret, &certificate, fingerprint, Vec::new()).err(),
        Some(ExpirationUpdateFailure::RevokedComponent),
    );
}

#[test]
fn newer_primary_binding_does_not_supersede_prospective_revocation_for_mutation() {
    let (secret, mut certificate) =
        generated_test_certificate("Superseded Primary <superseded-primary@example.test>");
    certificate
        .details
        .revocation_signatures
        .push(signed_soft_key_revocation(
            &secret,
            &certificate,
            1_700_000_010,
            RevocationCode::KeySuperseded,
            None,
        ));
    let user_id = certificate.details.users[0].id.clone();
    certificate.details.users[0]
        .signatures
        .push(identity_certification(
            &secret,
            Tag::UserId,
            &user_id,
            1_700_000_020,
            None,
        ));

    let candidates = all_components(std::slice::from_ref(&certificate));
    let policy = validate_certificate(
        &certificate,
        &candidates,
        1_700_000_130,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate primary policy");
    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    assert_eq!(
        update_test_component(&secret, &certificate, fingerprint, Vec::new()).err(),
        Some(ExpirationUpdateFailure::RevokedComponent),
    );
}

#[test]
fn expired_prospective_subkey_revocation_blocks_expiration_mutation() {
    let (secret, mut certificate) =
        generated_test_certificate("Retired Subkey <retired-subkey@example.test>");
    let subkey = certificate
        .public_subkeys
        .first()
        .cloned()
        .expect("generated subkey");
    let revocation = signed_soft_subkey_revocation(
        &secret,
        &certificate,
        &subkey.key,
        1_700_000_010,
        RevocationCode::KeySuperseded,
        Some(10),
    );
    certificate.public_subkeys[0].signatures.push(revocation);

    let candidates = all_components(std::slice::from_ref(&certificate));
    let policy = validate_certificate(
        &certificate,
        &candidates,
        1_700_000_130,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate subkey policy");
    assert_eq!(
        policy
            .subkey(&subkey.key)
            .expect("generated subkey policy")
            .policy()
            .revocation_status,
        RevocationStatus::Revoked,
    );

    assert_eq!(
        update_test_component(
            &secret,
            &certificate,
            fingerprint_hex(&subkey.key),
            Vec::new(),
        )
        .err(),
        Some(ExpirationUpdateFailure::RevokedComponent),
    );
}

#[test]
fn newer_subkey_binding_does_not_supersede_prospective_revocation_for_mutation() {
    let (secret, mut certificate) =
        generated_test_certificate("Superseded Subkey <superseded-subkey@example.test>");
    let subkey = certificate
        .public_subkeys
        .first()
        .cloned()
        .expect("generated subkey");
    let revocation = signed_soft_subkey_revocation(
        &secret,
        &certificate,
        &subkey.key,
        1_700_000_010,
        RevocationCode::KeyRetired,
        None,
    );
    let newer_binding = newer_subkey_binding(&secret, &certificate, &subkey, 1_700_000_020);
    certificate.public_subkeys[0]
        .signatures
        .extend([revocation, newer_binding]);

    let candidates = all_components(std::slice::from_ref(&certificate));
    let policy = validate_certificate(
        &certificate,
        &candidates,
        1_700_000_130,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate subkey policy");
    assert_eq!(
        policy
            .subkey(&subkey.key)
            .expect("generated subkey policy")
            .policy()
            .revocation_status,
        RevocationStatus::Revoked,
    );

    assert_eq!(
        update_test_component(
            &secret,
            &certificate,
            fingerprint_hex(&subkey.key),
            Vec::new(),
        )
        .err(),
        Some(ExpirationUpdateFailure::RevokedComponent),
    );
}

#[test]
fn expired_authenticated_designated_revocation_blocks_mutation() {
    let (target_secret, mut target) =
        generated_test_certificate("Designated Target <designated-target@example.test>");
    let (revoker_secret, revoker) =
        generated_test_certificate("Designated Revoker <designated-revoker@example.test>");
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);
    target
        .details
        .revocation_signatures
        .push(signed_soft_key_revocation(
            &revoker_secret,
            &target,
            1_700_000_020,
            RevocationCode::KeyRetired,
            Some(10),
        ));

    let mut candidates = all_components(std::slice::from_ref(&target));
    candidates.extend(all_components(std::slice::from_ref(&revoker)));
    let policy = validate_certificate(
        &target,
        &candidates,
        1_700_000_130,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate designated-revoker policy");
    assert_eq!(policy.primary.revocation_status, RevocationStatus::Revoked);

    let fingerprint = fingerprint_hex(&target.primary_key);
    assert_eq!(
        update_test_component(&target_secret, &target, fingerprint.clone(), Vec::new()).err(),
        Some(ExpirationUpdateFailure::UnresolvedRevocationAuthority),
    );
    let revoker_document = revoker
        .to_armored_bytes(Default::default())
        .expect("armor designated revoker");
    assert_eq!(
        update_test_component(&target_secret, &target, fingerprint, vec![revoker_document],).err(),
        Some(ExpirationUpdateFailure::RevokedComponent),
    );
}

#[test]
fn certification_revocation_does_not_trip_key_revocation_guard() {
    let (secret, mut certificate) =
        generated_test_certificate("Certification Only <certification-only@example.test>");
    let user_id = certificate.details.users[0].id.clone();
    let revocation = signed_user_id_revocation(&secret, &certificate);
    certificate.details.users[0].signatures.extend([
        revocation,
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_030, None),
    ]);

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    update_test_component(&secret, &certificate, fingerprint, Vec::new())
        .expect("non-key Certification Revocation must not block renewal");
}

#[test]
fn invalid_key_revocation_evidence_does_not_block_renewal() {
    let (secret, mut certificate) =
        generated_test_certificate("Invalid Evidence <invalid-evidence@example.test>");
    let (_, unrelated) =
        generated_test_certificate("Unrelated Target <unrelated-target@example.test>");
    let invalid = signed_soft_key_revocation(
        &secret,
        &unrelated,
        1_700_000_010,
        RevocationCode::KeyRetired,
        Some(10),
    );
    assert!(invalid.verify_key(&certificate.primary_key).is_err());
    certificate.details.revocation_signatures.push(invalid);

    let fingerprint = fingerprint_hex(&certificate.primary_key);
    update_test_component(&secret, &certificate, fingerprint, Vec::new())
        .expect("cryptographically invalid evidence must not block renewal");
}

#[test]
fn same_algorithm_self_revocation_is_not_unresolved_external_evidence() {
    let (target_secret, mut target) =
        generated_test_certificate("Self Revocation <self-revocation@example.test>");
    let (_, revoker) = generated_test_certificate("Missing Revoker <missing-revoker@example.test>");
    assert_eq!(
        target.primary_key.algorithm(),
        revoker.primary_key.algorithm()
    );
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);

    let revocation = signed_user_id_revocation(&target_secret, &target);
    revocation
        .verify_certification(
            &target.primary_key,
            Tag::UserId,
            &target.details.users[0].id,
        )
        .expect("verify self-revocation");
    target.details.users[0].signatures.push(revocation);

    let candidates = all_components(std::slice::from_ref(&target));
    assert!(!any_identity_revocation_indeterminate(&target, &candidates));
}

#[test]
fn undeclared_matching_algorithm_revocation_is_not_unresolved() {
    let (target_secret, mut target) = generated_test_certificate("Target <target@example.test>");
    let (_, revoker) = generated_test_certificate("Missing Revoker <missing-revoker@example.test>");
    let (attacker_secret, attacker) =
        generated_test_certificate("Undeclared Signer <undeclared@example.test>");
    assert_eq!(
        revoker.primary_key.algorithm(),
        attacker.primary_key.algorithm()
    );
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);

    let injected = signed_key_revocation(&attacker_secret, &target, true, true);
    injected
        .verify_key_third_party(&target.primary_key, &attacker.primary_key)
        .expect("verify injected signature cryptographically");
    target.details.revocation_signatures.push(injected);

    let candidates = all_components(std::slice::from_ref(&target));
    assert_eq!(authorize_primary_mutation(&target, &candidates), Ok(()));
}

#[test]
fn unhashed_matching_fingerprint_does_not_override_hashed_issuer() {
    let (target_secret, mut target) = generated_test_certificate("Target <target@example.test>");
    let (_, revoker) = generated_test_certificate("Missing Revoker <missing-revoker@example.test>");
    let (attacker_secret, attacker) =
        generated_test_certificate("Undeclared Signer <undeclared@example.test>");
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);

    let mut injected = signed_key_revocation(&attacker_secret, &target, true, true);
    injected
        .unhashed_subpacket_push(
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                revoker.primary_key.fingerprint(),
            ))
            .expect("appended issuer fingerprint subpacket"),
        )
        .expect("append unhashed issuer fingerprint");
    injected
        .verify_key_third_party(&target.primary_key, &attacker.primary_key)
        .expect("unhashed injection preserves the signature");
    target.details.revocation_signatures.push(injected);

    let candidates = all_components(std::slice::from_ref(&target));
    assert_eq!(authorize_primary_mutation(&target, &candidates), Ok(()));
}

#[test]
fn ambiguous_hashed_fingerprints_do_not_select_a_missing_revoker() {
    let (target_secret, mut target) = generated_test_certificate("Target <target@example.test>");
    let (_, revoker) = generated_test_certificate("Missing Revoker <missing-revoker@example.test>");
    let (attacker_secret, attacker) =
        generated_test_certificate("Undeclared Signer <undeclared@example.test>");
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);

    let mut config = SignatureConfig::v4(
        SignatureType::KeyRevocation,
        attacker_secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_020,
        )))
        .expect("creation subpacket"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            revoker.primary_key.fingerprint(),
        ))
        .expect("revoker issuer fingerprint"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            attacker.primary_key.fingerprint(),
        ))
        .expect("attacker issuer fingerprint"),
    ];
    let injected = config
        .sign_key(
            &attacker_secret.primary_key,
            &Password::empty(),
            &target.primary_key,
        )
        .expect("sign ambiguous revocation");
    injected
        .verify_key_third_party(&target.primary_key, &attacker.primary_key)
        .expect("verify ambiguous signature cryptographically");
    target.details.revocation_signatures.push(injected);

    let candidates = all_components(std::slice::from_ref(&target));
    assert_eq!(authorize_primary_mutation(&target, &candidates), Ok(()));
}

#[test]
fn missing_declared_revoker_with_matching_issuer_evidence_fails_closed() {
    let (target_secret, mut target) = generated_test_certificate("Target <target@example.test>");
    let (revoker_secret, revoker) =
        generated_test_certificate("Missing Revoker <missing-revoker@example.test>");
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);

    // A v4 reader may route signatures using the unhashed Issuer Key ID.
    let revocation = signed_key_revocation(&revoker_secret, &target, false, true);
    revocation
        .verify_key_third_party(&target.primary_key, &revoker.primary_key)
        .expect("verify designated revocation");
    target.details.revocation_signatures.push(revocation);

    let candidates = all_components(std::slice::from_ref(&target));
    assert_eq!(
        authorize_primary_mutation(&target, &candidates),
        Err(MutationAuthorizationError::IndeterminateRevocation),
    );
    let fingerprint = fingerprint_hex(&target.primary_key);
    assert_eq!(
        update_test_component(&target_secret, &target, fingerprint, Vec::new()).err(),
        Some(ExpirationUpdateFailure::UnresolvedRevocationAuthority),
    );
}

#[test]
fn available_declared_revoker_resolves_matching_revocation() {
    let (target_secret, mut target) = generated_test_certificate("Target <target@example.test>");
    let (revoker_secret, revoker) =
        generated_test_certificate("Available Revoker <available-revoker@example.test>");
    add_designated_revoker_declaration(&target_secret, &mut target, &revoker);
    target
        .details
        .revocation_signatures
        .push(signed_key_revocation(&revoker_secret, &target, true, true));

    let mut candidates = all_components(std::slice::from_ref(&target));
    candidates.extend(all_components(std::slice::from_ref(&revoker)));
    assert_eq!(
        authorize_primary_mutation(&target, &candidates),
        Err(MutationAuthorizationError::Revoked),
    );
}

#[test]
fn user_id_renewal_uses_older_live_certification_over_newer_expired_one() {
    let (secret, mut certificate) =
        generated_test_certificate("Live User ID <live-user-id@example.test>");
    let user = certificate
        .details
        .users
        .first_mut()
        .expect("generated user ID");
    user.signatures = vec![
        identity_certification(&secret, Tag::UserId, &user.id, 1_700_000_010, None),
        identity_certification(&secret, Tag::UserId, &user.id, 1_700_000_020, Some(10)),
    ];

    let certificate = renew_test_primary(&secret, &certificate);

    let signatures = &certificate.details.users[0].signatures;
    assert_eq!(signature_creation_time(&signatures[0]), Some(1_700_000_130));
    assert_eq!(signature_creation_time(&signatures[1]), Some(1_700_000_020));
}

#[test]
fn user_attribute_renewal_uses_older_live_certification_over_newer_expired_one() {
    let (secret, mut certificate) =
        generated_test_certificate("Attribute Owner <attribute-owner@example.test>");
    let attribute =
        UserAttribute::new_image(Bytes::from_static(b"test image")).expect("user attribute");
    certificate
        .details
        .user_attributes
        .push(SignedUserAttribute::new(
            attribute.clone(),
            vec![
                identity_certification(
                    &secret,
                    Tag::UserAttribute,
                    &attribute,
                    1_700_000_010,
                    None,
                ),
                identity_certification(
                    &secret,
                    Tag::UserAttribute,
                    &attribute,
                    1_700_000_020,
                    Some(10),
                ),
            ],
        ));

    let certificate = renew_test_primary(&secret, &certificate);

    let signatures = &certificate.details.user_attributes[0].signatures;
    assert_eq!(signature_creation_time(&signatures[0]), Some(1_700_000_130));
    assert_eq!(signature_creation_time(&signatures[1]), Some(1_700_000_020));
}

#[test]
fn mutation_selection_uses_last_hashed_creation_time_and_resolves_equivalent_ties() {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Creation Time <creation-time@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let secret = parse_single_secret(&material.private_key_armored).expect("secret key");
    let last_hashed_is_newest =
        direct_signature_with_creation_times(&secret, &[1_700_000_010, 1_700_000_030]);
    let older = direct_signature_with_creation_times(&secret, &[1_700_000_020]);
    let signatures = [last_hashed_is_newest, older];
    assert_eq!(
        newest_index(&signatures, &[0, 1], PolicyContext::Direct, |_| Ok(false),)
            .expect("select newest signature"),
        Some(0),
    );

    let tied_with_unhashed_issuer =
        direct_signature_with_policy(&secret, &[1_700_000_030], None, true);
    let signatures = [
        direct_signature_with_policy(&secret, &[1_700_000_030], None, false),
        tied_with_unhashed_issuer,
    ];
    let PolicySelection::Selected {
        projection: forward,
        ..
    } = select_newest_policy_signature_in(signatures.iter(), PolicyContext::Direct, |_| {
        Ok::<_, ExpirationUpdateFailure>(false)
    })
    .expect("select equivalent tied policy")
    else {
        panic!("equivalent tie must select a policy");
    };
    let PolicySelection::Selected {
        projection: reverse,
        ..
    } = select_newest_policy_signature_in(signatures.iter().rev(), PolicyContext::Direct, |_| {
        Ok::<_, ExpirationUpdateFailure>(false)
    })
    .expect("select reversed equivalent tied policy")
    else {
        panic!("reversed equivalent tie must select a policy");
    };
    assert_eq!(forward, reverse);
}

#[test]
fn renewal_uses_an_older_non_revocable_surviving_certification() {
    const REFERENCE_TIME: u64 = 1_700_000_100;
    let (secret, certificate) =
        generated_test_certificate("Scoped Revocable <scoped-revocable@example.test>");
    let user_id = secret.details.users[0].id.clone();
    let non_revocable = |creation_time: u32| {
        let mut config = SignatureConfig::v4(
            SignatureType::CertPositive,
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        );
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                creation_time,
            )))
            .expect("creation subpacket"),
            Subpacket::regular(SubpacketData::Revocable(false)).expect("revocable subpacket"),
        ];
        config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign non-revocable certification")
    };
    let mut config = SignatureConfig::v4(
        SignatureType::CertRevocation,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_700_000_030,
        )))
        .expect("creation subpacket"),
    ];
    let revocation = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign certification revocation");

    // The older non-revocable certification protects itself, so revoking the
    // newer certification exposes the older certification as the surviving
    // policy statement. Renewal may safely use that authenticated template.
    let mut surviving = certificate.clone();
    surviving.details.users[0].signatures = vec![
        non_revocable(1_700_000_010),
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_020, None),
        revocation.clone(),
    ];
    assert!(!first_user_id_is_revoked(&surviving, REFERENCE_TIME));
    let (private_key, public_key) = mutation_documents(&secret, &surviving);
    let fingerprint = fingerprint_hex(&surviving.primary_key);
    let renewed = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: fingerprint.clone(),
        component_fingerprints: vec![fingerprint],
        expires_at_epoch_seconds: None,
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: REFERENCE_TIME,
    })
    .expect("renew from the surviving certification");
    let renewed = parse_single_public(&renewed.key_material.public_key_armored)
        .expect("renewed public certificate");
    assert!(!first_user_id_is_revoked(&renewed, REFERENCE_TIME));

    // A non-revocable *effective* certification still blocks the
    // revocation, and the identity stays renewable.
    let mut protected = certificate.clone();
    protected.details.users[0].signatures = vec![
        identity_certification(&secret, Tag::UserId, &user_id, 1_700_000_010, None),
        non_revocable(1_700_000_020),
        revocation,
    ];
    assert!(!first_user_id_is_revoked(&protected, REFERENCE_TIME));
}

#[test]
fn mutation_selection_uses_cryptographic_material_for_tied_policies() {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Policy Conflict <policy-conflict@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let secret = parse_single_secret(&material.private_key_armored).expect("secret key");
    let signatures = [
        direct_signature_with_policy(&secret, &[1_700_000_030], None, false),
        direct_signature_with_policy(&secret, &[1_700_000_030], Some(86_400), false),
    ];
    let expected =
        usize::from(cryptographic_signature_material_cmp(&signatures[0], &signatures[1]).is_gt());
    let selected = newest_index(&signatures, &[0, 1], PolicyContext::Direct, |_| Ok(false))
        .expect("select tied policy");
    assert_eq!(selected, Some(expected));
    assert_eq!(
        newest_index(&signatures, &[1, 0], PolicyContext::Direct, |_| Ok(false)),
        Ok(Some(expected)),
    );
}

#[test]
fn mutation_preserves_packet_inventory_that_composed_reserialization_would_drop() {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Packet Guard <packet-guard@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate guarded certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let packets = RawPacketStream::parse(&material.private_key_armored, MAX_KEY_PACKETS)
        .expect("scan secret key");
    let mut with_marker = packets.bytes().to_vec();
    with_marker.extend_from_slice(&[0xca, 0x03, b'P', b'G', b'P']);
    let guarded_secret =
        RawPacketStream::parse(&with_marker, MAX_KEY_PACKETS).expect("parse guarded secret");
    let mut original_secret_bodies = guarded_secret
        .packets()
        .iter()
        .filter(|packet| matches!(packet.tag(), 5 | 7))
        .map(|packet| (packet.tag(), guarded_secret.body(packet).to_vec()))
        .collect::<Vec<_>>();
    original_secret_bodies.sort();
    let mut original_secret_packets = guarded_secret
        .packets()
        .iter()
        .filter(|packet| matches!(packet.tag(), 5 | 7))
        .map(|packet| (packet.tag(), guarded_secret.raw(packet).to_vec()))
        .collect::<Vec<_>>();
    original_secret_packets.sort();
    let packets = RawPacketStream::parse(&material.public_key_armored, MAX_KEY_PACKETS)
        .expect("scan public key");
    let mut public_with_marker = packets.bytes().to_vec();
    public_with_marker.extend_from_slice(&[0xca, 0x03, b'P', b'G', b'P']);
    let guarded_public = RawPacketStream::parse(&public_with_marker, MAX_KEY_PACKETS)
        .expect("parse guarded public key");
    let first_subkey = guarded_public
        .packets()
        .iter()
        .position(|packet| packet.tag() == 14)
        .expect("generated public subkey");
    let original_subkey_tail = guarded_public.packets()[first_subkey..]
        .iter()
        .filter(|packet| packet.tag() != 10)
        .flat_map(|packet| guarded_public.raw(packet))
        .copied()
        .collect::<Vec<_>>();
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: with_marker,
        public_key: public_with_marker,
        expected_primary_fingerprint: material.fingerprint.clone(),
        component_fingerprints: vec![material.fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: 1_700_000_120,
    })
    .expect("packet-surgical mutation succeeds");
    let renewed = success.key_material;
    let private_packets = RawPacketStream::parse(&renewed.private_key_armored, MAX_KEY_PACKETS)
        .expect("parse renewed private key");
    let mut renewed_secret_bodies = private_packets
        .packets()
        .iter()
        .filter(|packet| matches!(packet.tag(), 5 | 7))
        .map(|packet| (packet.tag(), private_packets.body(packet).to_vec()))
        .collect::<Vec<_>>();
    renewed_secret_bodies.sort();
    assert_eq!(renewed_secret_bodies, original_secret_bodies);
    let mut renewed_secret_packets = private_packets
        .packets()
        .iter()
        .filter(|packet| matches!(packet.tag(), 5 | 7))
        .map(|packet| (packet.tag(), private_packets.raw(packet).to_vec()))
        .collect::<Vec<_>>();
    renewed_secret_packets.sort();
    assert_eq!(renewed_secret_packets, original_secret_packets);
    // RFC 9580 §5.8 makes Marker packets carry no information. The shared
    // packet set drops them at parse time, so a mutation must not reproduce
    // them either.
    for output in [
        renewed.private_key_armored.as_slice(),
        renewed.public_key_armored.as_slice(),
    ] {
        let packets = RawPacketStream::parse(output, MAX_KEY_PACKETS).expect("parse output");
        assert!(!packets.packets().iter().any(|packet| packet.tag() == 10));
    }
    let renewed_public = RawPacketStream::parse(&renewed.public_key_armored, MAX_KEY_PACKETS)
        .expect("parse renewed public key");
    let renewed_first_subkey = renewed_public
        .packets()
        .iter()
        .position(|packet| packet.tag() == 14)
        .expect("renewed public subkey");
    let renewed_subkey_tail = renewed_public.packets()[renewed_first_subkey..]
        .iter()
        .flat_map(|packet| renewed_public.raw(packet))
        .copied()
        .collect::<Vec<_>>();
    assert_eq!(renewed_subkey_tail, original_subkey_tail);
}

#[test]
fn v4_primary_renewal_without_direct_key_does_not_create_direct_key() {
    const RENEWAL_TIME: u64 = 1_700_000_120;
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Direct Insert <direct-insert@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let strip_direct_signatures = |input: &[u8]| {
        let stream = RawPacketStream::parse(input, MAX_KEY_PACKETS).expect("scan certificate");
        let mut direct = true;
        let mut output = Vec::new();
        for (index, packet) in stream.packets().iter().enumerate() {
            if index > 0 && matches!(packet.tag(), 13 | 14 | 17) {
                direct = false;
            }
            if direct && packet.tag() == 2 {
                continue;
            }
            output.extend_from_slice(stream.raw(packet));
        }
        output
    };
    let private_key = strip_direct_signatures(&material.private_key_armored);
    let public_key = strip_direct_signatures(&material.public_key_armored);
    let without_direct = parse_single_public(&public_key).expect("stripped key");
    assert!(without_direct.details.direct_signatures.is_empty());

    let success = update_expiration_request(ExpirationUpdateInput {
        private_key,
        public_key,
        expected_primary_fingerprint: material.fingerprint.clone(),
        component_fingerprints: vec![material.fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("primary renewal should use the existing identity certification");
    let renewed_material = success.key_material;
    let renewed = parse_single_public(&renewed_material.public_key_armored).expect("renewed key");
    assert!(
        renewed.details.direct_signatures.is_empty(),
        "V4 primary renewal must not introduce a second expiration policy carrier",
    );
    let (private_projection, _) = project_secret_certificate(&renewed_material.private_key_armored)
        .expect("project renewed private certificate");
    let renewed_private =
        parse_single_public(&private_projection).expect("parse renewed private projection");
    assert!(renewed_private.details.direct_signatures.is_empty());
    let user = &renewed.details.users[0];
    let certification = user
        .signatures
        .iter()
        .find(|signature| {
            is_certification(signature.typ())
                && signature_creation_time(signature)
                    == Some(u32::try_from(RENEWAL_TIME).expect("bounded renewal time"))
        })
        .expect("renewed User ID certification");
    certification
        .verify_certification(&renewed.primary_key, Tag::UserId, &user.id)
        .expect("renewed User ID certification verifies");
    assert_eq!(
        certification
            .key_expiration_time()
            .map(|duration| duration.as_secs()),
        Some(172_800),
    );

    let candidates = all_components(std::slice::from_ref(&renewed));
    let policy = validate_certificate(
        &renewed,
        &candidates,
        RENEWAL_TIME,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("renewed certificate remains valid");
    assert!(policy.primary.authenticated);
    assert_eq!(policy.primary.key_expiration_seconds, Some(172_800));
}

#[test]
fn primary_renewal_without_direct_key_does_not_promote_user_id_revocation_authority() {
    let (secret, mut certificate) =
        generated_test_certificate("UID Revoker Template <uid-revoker-template@example.test>");
    let (_, revoker) = generated_test_certificate("UID Revoker <uid-revoker@example.test>");
    let user_id = certificate.details.users[0].id.clone();
    let declaration = identity_designated_revoker_declaration(
        &secret,
        Tag::UserId,
        &user_id,
        &revoker,
        RevocationKeyClass::Default,
    );
    certificate.details.direct_signatures.clear();
    certificate.details.users[0].signatures = vec![declaration];
    assert!(revocation_authorities(&certificate).is_empty());

    let (material, index) = renew_test_primary_material(&secret, &certificate);
    let renewed =
        parse_single_public(&material.public_key_armored).expect("parse renewed certificate");
    assert!(renewed.details.direct_signatures.is_empty());
    assert_eq!(
        renewed.details.users[0]
            .signatures
            .iter()
            .filter(|signature| signature_contains_revocation_key(signature))
            .count(),
        1,
        "the original legacy certification remains available to legacy readers",
    );
    let renewed_certification = renewed.details.users[0]
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("renewed User ID certification");
    assert!(!signature_contains_revocation_key(renewed_certification));
    assert!(revocation_authorities(&renewed).is_empty());
    assert!(index.legacy_designated_revokers.is_empty());
}

#[test]
fn primary_renewal_without_direct_key_does_not_promote_user_attribute_revocation_authority() {
    let (secret, mut certificate) = generated_test_certificate(
        "Attribute Revoker Template <attribute-revoker-template@example.test>",
    );
    let (_, revoker) =
        generated_test_certificate("Attribute Revoker <attribute-revoker@example.test>");
    let attribute =
        UserAttribute::new_image(Bytes::from_static(b"revoker image")).expect("user attribute");
    let declaration = identity_designated_revoker_declaration(
        &secret,
        Tag::UserAttribute,
        &attribute,
        &revoker,
        RevocationKeyClass::Default,
    );
    certificate.details.direct_signatures.clear();
    certificate.details.users.clear();
    certificate
        .details
        .user_attributes
        .push(SignedUserAttribute::new(attribute, vec![declaration]));
    assert!(revocation_authorities(&certificate).is_empty());

    let (material, index) = renew_test_primary_material(&secret, &certificate);
    let renewed =
        parse_single_public(&material.public_key_armored).expect("parse renewed certificate");
    assert!(renewed.details.direct_signatures.is_empty());
    assert_eq!(
        renewed.details.user_attributes[0]
            .signatures
            .iter()
            .filter(|signature| signature_contains_revocation_key(signature))
            .count(),
        1,
    );
    let renewed_certification = renewed.details.user_attributes[0]
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("renewed User Attribute certification");
    assert!(!signature_contains_revocation_key(renewed_certification));
    assert!(revocation_authorities(&renewed).is_empty());
    assert!(index.legacy_designated_revokers.is_empty());
}

#[test]
fn primary_renewal_without_direct_key_keeps_scoped_subpackets_on_certification() {
    let (secret, mut certificate) =
        generated_test_certificate("Scoped Direct Template <scoped-direct@example.test>");
    let user_id = certificate.details.users[0].id.clone();
    let mut config = certificate.details.users[0].signatures[0]
        .config()
        .cloned()
        .expect("generated certification config");
    let identity_scoped = vec![
        Subpacket::regular(SubpacketData::ExportableCertification(true))
            .expect("exportable certification subpacket"),
        Subpacket::regular(SubpacketData::Revocable(false)).expect("revocable subpacket"),
        Subpacket::regular(SubpacketData::TrustSignature(1, 120))
            .expect("trust signature subpacket"),
        Subpacket::regular(SubpacketData::RegularExpression(Bytes::from_static(
            b".*@example.test\0",
        )))
        .expect("regular expression subpacket"),
        Subpacket::regular(SubpacketData::IsPrimary(true)).expect("primary User ID subpacket"),
        Subpacket::regular(SubpacketData::SignersUserID(Bytes::from_static(
            b"Scoped Direct Template <scoped-direct@example.test>",
        )))
        .expect("signer's User ID subpacket"),
        Subpacket::regular(SubpacketData::RevocationReason(
            RevocationCode::CertUserIdInvalid,
            Bytes::from_static(b"identity-local reason"),
        ))
        .expect("reason for revocation subpacket"),
        Subpacket::regular(SubpacketData::SignatureTarget(
            secret.primary_key.algorithm(),
            HashAlgorithm::Sha256,
            Bytes::from(vec![0x42; 32]),
        ))
        .expect("signature target subpacket"),
    ];
    config.hashed_subpackets.extend(identity_scoped.clone());
    config.unhashed_subpackets.extend(identity_scoped.clone());
    let certification = config
        .sign_certification(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            Tag::UserId,
            &user_id,
        )
        .expect("sign adversarial identity template");
    certification
        .verify_certification(secret.primary_key.public_key(), Tag::UserId, &user_id)
        .expect("adversarial identity template verifies");
    certificate.details.direct_signatures.clear();
    certificate.details.users[0].signatures = vec![certification];

    let renewed = renew_test_primary(&secret, &certificate);
    assert!(renewed.details.direct_signatures.is_empty());
    let certification = renewed.details.users[0]
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("renewed User ID certification");
    certification
        .verify_certification(&renewed.primary_key, Tag::UserId, &user_id)
        .expect("renewed User ID certification verifies");
    let config = certification.config().expect("v4 User ID certification");
    for expected in &identity_scoped {
        assert!(config.hashed_subpackets.contains(expected));
        assert!(config.unhashed_subpackets.contains(expected));
    }
}

#[test]
fn existing_direct_key_renewal_preserves_revocability_and_key_wide_policy() {
    let (secret, mut certificate) =
        generated_test_certificate("Existing Direct Template <existing-direct@example.test>");
    let mut config = certificate.details.users[0].signatures[0]
        .config()
        .cloned()
        .expect("generated certification config");
    config.typ = SignatureType::Key;
    config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::IsPrimary(_)));
    config
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::Revocable(false)).expect("revocable subpacket"));
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
            vec![(
                pgp::crypto::sym::SymmetricKeyAlgorithm::AES256,
                pgp::crypto::aead::AeadAlgorithm::Ocb,
            )]
            .into(),
        ))
        .expect("RFC 9580 AEAD preferences"),
    );
    let direct = config
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("sign existing Direct-Key template");
    direct
        .verify_key(&certificate.primary_key)
        .expect("existing Direct-Key template verifies");
    certificate.details.direct_signatures = vec![direct];

    let renewed = renew_test_primary(&secret, &certificate);
    let direct = renewed
        .details
        .direct_signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("renewed Direct-Key signature");
    direct
        .verify_key(&renewed.primary_key)
        .expect("renewed Direct-Key signature verifies");
    let config = direct.config().expect("v4 Direct-Key signature");
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::Revocable(false)))
    );
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::KeyFlags(_)))
    );
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::Features(_)))
    );
    assert!(config.hashed_subpackets.iter().any(|subpacket| matches!(
        subpacket.data,
        SubpacketData::PreferredSymmetricAlgorithms(_)
    )));
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::PreferredHashAlgorithms(_)))
    );
    assert!(config.hashed_subpackets.iter().any(|subpacket| matches!(
        subpacket.data,
        SubpacketData::PreferredCompressionAlgorithms(_)
    )));
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::PreferredAeadAlgorithms(_)))
    );
    assert!(
        config
            .hashed_subpackets
            .iter()
            .any(|subpacket| matches!(subpacket.data, SubpacketData::PreferredEncryptionModes(_)))
    );
}

#[test]
fn existing_direct_key_revoker_declaration_survives_without_being_reissued() {
    let (secret, mut certificate) =
        generated_test_certificate("Existing Revoker Declaration <existing-revoker@example.test>");
    let (_, revoker) =
        generated_test_certificate("Existing Revoker <existing-revoker-key@example.test>");
    add_designated_revoker_declaration(&secret, &mut certificate, &revoker);
    let before = revocation_authorities(&certificate);
    let declaration_count = certificate
        .details
        .direct_signatures
        .iter()
        .filter(|signature| signature_contains_revocation_key(signature))
        .count();
    assert_eq!(before.len(), 1);

    let renewed = renew_test_primary(&secret, &certificate);
    assert_eq!(revocation_authorities(&renewed), before);
    assert_eq!(
        renewed
            .details
            .direct_signatures
            .iter()
            .filter(|signature| signature_contains_revocation_key(signature))
            .count(),
        declaration_count,
        "the original declaration is retained, not cloned into the renewal",
    );
    let renewed_direct = renewed
        .details
        .direct_signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("renewed Direct-Key signature");
    assert!(!signature_contains_revocation_key(renewed_direct));
}

#[test]
fn sensitive_direct_key_revoker_stays_private_across_renewal() {
    let (secret, mut certificate) = generated_test_certificate(
        "Sensitive Revoker Declaration <sensitive-revoker@example.test>",
    );
    let (_, revoker) =
        generated_test_certificate("Sensitive Revoker <sensitive-revoker-key@example.test>");
    let declaration = designated_revoker_declaration(
        &secret,
        &certificate,
        &revoker,
        RevocationKeyClass::Sensitive,
    );
    certificate.details.direct_signatures.push(declaration);
    let before = revocation_authorities(&certificate);
    assert_eq!(before.len(), 1);
    assert_eq!(before[0].key_class, RevocationKeyClass::Sensitive as u8);

    let (material, index) = renew_test_primary_material(&secret, &certificate);
    assert_eq!(index.legacy_designated_revokers.len(), 1);
    assert!(index.legacy_designated_revokers[0].sensitive);

    let public = parse_single_public(&material.public_key_armored).expect("parse public renewal");
    assert!(
        public
            .details
            .direct_signatures
            .iter()
            .all(|signature| !signature_contains_revocation_key(signature)),
        "ordinary public export must withhold the sensitive declaration",
    );

    let (private_projection, _) = project_secret_certificate(&material.private_key_armored)
        .expect("project renewed private certificate");
    let retained =
        parse_single_public(&private_projection).expect("parse retained private projection");
    assert_eq!(revocation_authorities(&retained), before);
    assert_eq!(
        retained
            .details
            .direct_signatures
            .iter()
            .filter(|signature| signature_contains_revocation_key(signature))
            .count(),
        1,
    );
    let renewed_direct = retained
        .details
        .direct_signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(1_700_000_130))
        .expect("retained renewed Direct-Key signature");
    assert!(!signature_contains_revocation_key(renewed_direct));
}

#[test]
fn expiration_postflight_rejects_revocation_authority_drift() {
    let (secret, mut certificate) =
        generated_test_certificate("Authority Postflight <authority-postflight@example.test>");
    let (_, revoker) =
        generated_test_certificate("Postflight Revoker <postflight-revoker@example.test>");
    let before = revocation_authorities(&certificate);
    assert!(before.is_empty());
    add_designated_revoker_declaration(&secret, &mut certificate, &revoker);

    let candidates = all_components(std::slice::from_ref(&certificate));
    let mut budget = OpenPgpPolicyBudget::default();
    let after = validate_certificate(&certificate, &candidates, 1_700_000_130, &mut budget)
        .expect("evaluate changed authority");
    assert_eq!(
        validate_revocation_authority_unchanged(&after, &before),
        Err(ExpirationUpdateFailure::SignatureVerificationFailed),
    );
}

#[test]
fn generated_v4_certificate_renews_primary_without_changing_identity() {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "OpenPGP Mutation <openpgp-mutation@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: Some(86_400),
    })
    .expect("generated certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let expected_fingerprint = material.fingerprint.clone();
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: expected_fingerprint.clone(),
        component_fingerprints: vec![expected_fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: 1_700_000_120,
    })
    .expect("renewal succeeds");
    let renewed = success.key_material;
    assert_eq!(renewed.fingerprint, expected_fingerprint);
    assert!(!renewed.private_key_armored.is_empty());
    assert!(!renewed.public_key_armored.is_empty());
    assert_eq!(
        success.certificate_index.primary_fingerprint,
        expected_fingerprint
    );

    let parsed = crate::openpgp::adapter::parse_public_key(
        crate::openpgp::adapter::wire::OpenPgpPublicKeyParseRequest {
            key_data: renewed.public_key_armored.clone(),
            reference_time_epoch_seconds: Some(1_700_000_120),
        },
    )
    .expect("fresh public-key parse succeeds");
    let parsed =
        crate::openpgp::adapter::wire::OpenPgpPublicKeyParseResult::decode(parsed.as_slice())
            .expect("fresh public-key parse result");
    let crate::openpgp::adapter::wire::open_pgp_public_key_parse_result::Result::Success(parsed) =
        parsed.result.expect("fresh public-key parse outcome")
    else {
        panic!("renewed public key must parse successfully");
    };
    assert_eq!(
        parsed
            .keys
            .first()
            .and_then(|key| key.expires_at_epoch_seconds),
        Some(1_700_172_800),
        "fresh policy evaluation must retain the renewed primary expiration",
    );
}

#[test]
fn expired_back_signature_is_regenerated() {
    assert_defective_back_signature_is_regenerated(BackSignatureDefect::Expired);
}

#[test]
fn weak_hash_back_signature_is_regenerated() {
    assert_defective_back_signature_is_regenerated(BackSignatureDefect::WeakHash);
}

#[test]
fn unknown_critical_back_signature_is_regenerated() {
    assert_defective_back_signature_is_regenerated(BackSignatureDefect::UnknownCritical);
}

#[test]
fn regenerated_back_signature_uses_subkey_issuer_metadata() {
    assert_defective_back_signature_is_regenerated(BackSignatureDefect::PrimaryIssuer);
}

#[test]
fn renewed_subkey_replaces_effective_binding_in_its_original_packet_position() {
    const RENEWAL_TIME: u64 = 1_700_000_120;
    const EXPIRES_AT: u64 = 1_700_172_800;

    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
        user_id: "Historical Binding <historical-binding@example.test>".to_owned(),
        rsa_bits: 0,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generate certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let original = parse_single_public(&material.public_key_armored).expect("original public key");
    let original_subkey = original.public_subkeys.first().expect("generated subkey");
    let subkey_fingerprint = fingerprint_hex(&original_subkey.key);
    let old_binding_time = original_subkey
        .signatures
        .iter()
        .filter(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .filter_map(signature_creation_time)
        .max()
        .expect("original binding creation time");
    let original_back_signature = original_subkey
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(old_binding_time))
        .and_then(Signature::embedded_signature)
        .cloned()
        .expect("original valid back signature");
    let historical_time = u64::from(old_binding_time.saturating_add(1));
    assert!(historical_time < RENEWAL_TIME);

    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: material.fingerprint.clone(),
        component_fingerprints: vec![subkey_fingerprint.clone()],
        expires_at_epoch_seconds: Some(EXPIRES_AT),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: RENEWAL_TIME,
    })
    .expect("renew subkey expiration");
    let renewed_material = success.key_material;
    let renewed =
        parse_single_public(&renewed_material.public_key_armored).expect("renewed public key");
    let renewed_subkey = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == subkey_fingerprint)
        .expect("renewed subkey");
    assert_eq!(
        renewed_subkey
            .signatures
            .iter()
            .filter(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
            .count(),
        1,
    );
    assert_eq!(
        renewed_subkey
            .signatures
            .first()
            .and_then(signature_creation_time),
        Some(u32::try_from(RENEWAL_TIME).expect("bounded renewal time")),
        "the effective binding should be replaced locally",
    );
    let renewed_back_signature = renewed_subkey
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(RENEWAL_TIME as u32))
        .and_then(Signature::embedded_signature)
        .expect("reused back signature");
    assert_eq!(
        renewed_back_signature, &original_back_signature,
        "expiration renewal must preserve the existing possession proof",
    );
    assert_eq!(
        signature_creation_time(renewed_back_signature),
        Some(old_binding_time),
    );
    renewed_back_signature
        .verify_primary_key_binding(&renewed_subkey.key, &renewed.primary_key)
        .expect("reused back signature verifies");

    assert_eq!(
        subkey_policy_snapshot(&renewed, &subkey_fingerprint, historical_time),
        (false, None, None),
    );
    assert_eq!(
        subkey_policy_snapshot(&renewed, &subkey_fingerprint, RENEWAL_TIME),
        (
            true,
            Some(u32::try_from(RENEWAL_TIME).expect("bounded renewal time")),
            Some(
                u32::try_from(EXPIRES_AT - u64::from(renewed_subkey.key.created_at().as_secs()))
                    .expect("bounded key expiration"),
            ),
        ),
    );
}

#[test]
fn generated_rsa_certificate_renews_through_aws_lc_signer() {
    let generated = crate::openpgp::adapter::key::generate(OpenPgpKeyGenerateRequest {
        kind: OpenPgpKeyKind::Rsa as i32,
        user_id: "OpenPGP RSA <openpgp-rsa@example.test>".to_owned(),
        rsa_bits: 3_072,
        creation_time_epoch_seconds: 1_700_000_000,
        expiration_seconds: None,
    })
    .expect("generated RSA certificate");
    let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
    let expected_fingerprint = material.fingerprint.clone();
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: material.private_key_armored.clone(),
        public_key: material.public_key_armored.clone(),
        expected_primary_fingerprint: expected_fingerprint.clone(),
        component_fingerprints: vec![expected_fingerprint.clone()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: 1_700_000_120,
    })
    .expect("RSA renewal succeeds");
    assert_eq!(success.key_material.fingerprint, expected_fingerprint);
}

const SUBKEY_RENEWAL_TIME: u64 = 1_700_000_120;

fn rebind_sign_capable_subkey(
    secret: &mut SignedSecretKey,
    flags: Option<pgp::packet::KeyFlags>,
) -> (String, Signature) {
    let position = secret
        .secret_subkeys
        .iter()
        .position(|subkey| subkey.key.algorithm().can_sign())
        .expect("sign-capable subkey");
    let subkey = &secret.secret_subkeys[position];
    let public = subkey.key.public_key().clone();
    let binding = subkey
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .expect("subkey binding")
        .clone();
    let original_back_signature = binding
        .embedded_signature()
        .expect("generated back signature")
        .clone();
    let mut config = binding.config().cloned().expect("v4 binding config");
    config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::KeyFlags(_)));
    if let Some(flags) = flags {
        config
            .hashed_subpackets
            .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("key flags subpacket"));
    }
    let binding = config
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &public,
        )
        .expect("replacement subkey binding");
    binding
        .verify_subkey_binding(secret.primary_key.public_key(), &public)
        .expect("valid replacement subkey binding");
    secret.secret_subkeys[position].signatures = vec![binding];
    (fingerprint_hex(&public), original_back_signature)
}

fn move_secret_subkey_to_public(secret: &mut SignedSecretKey, fingerprint: &str) {
    let position = secret
        .secret_subkeys
        .iter()
        .position(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .expect("selected secret subkey");
    let subkey = secret.secret_subkeys.remove(position);
    secret.public_subkeys.push(SignedPublicSubKey::new(
        subkey.key.public_key().clone(),
        subkey.signatures,
    ));
}

fn remove_back_signatures(secret: &mut SignedSecretKey, fingerprint: &str) {
    let position = secret
        .secret_subkeys
        .iter()
        .position(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .expect("selected secret subkey");
    let subkey = &secret.secret_subkeys[position];
    let public = subkey.key.public_key().clone();
    let binding = subkey
        .signatures
        .iter()
        .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        .expect("subkey binding");
    let mut config = binding.config().cloned().expect("v4 binding config");
    let retain =
        |subpacket: &Subpacket| !matches!(subpacket.data, SubpacketData::EmbeddedSignature(_));
    config.hashed_subpackets.retain(&retain);
    config.unhashed_subpackets.retain(retain);
    let binding = config
        .sign_subkey_binding(
            &secret.primary_key,
            secret.primary_key.public_key(),
            &Password::empty(),
            &public,
        )
        .expect("binding without back-signature");
    binding
        .verify_subkey_binding(secret.primary_key.public_key(), &public)
        .expect("valid binding without back-signature");
    secret.secret_subkeys[position].signatures = vec![binding];
}

fn renew_subkey(
    secret: &SignedSecretKey,
    fingerprint: &str,
) -> Result<SignedPublicKey, ExpirationUpdateFailure> {
    let public = secret.to_public_key();
    let success = update_expiration_request(ExpirationUpdateInput {
        private_key: secret
            .to_armored_bytes(Default::default())
            .expect("armored secret key"),
        public_key: public
            .to_armored_bytes(Default::default())
            .expect("armored public key"),
        expected_primary_fingerprint: fingerprint_hex(secret.primary_key.public_key()),
        component_fingerprints: vec![fingerprint.to_owned()],
        expires_at_epoch_seconds: Some(1_700_172_800),
        candidate_revocation_keys: Vec::new(),
        reference_time_epoch_seconds: SUBKEY_RENEWAL_TIME,
    })?;
    let material = success.key_material;
    parse_single_public(&material.public_key_armored)
        .map_err(|_| ExpirationUpdateFailure::InternalFailure)
}

fn assert_signature_capable_subkey_reuses_back_signature(
    flags: Option<pgp::packet::KeyFlags>,
    user_id: &str,
) {
    let (mut secret, _) = generated_test_certificate(user_id);
    let expected_flags = flags.clone();
    let (fingerprint, original_back_signature) = rebind_sign_capable_subkey(&mut secret, flags);
    let renewed = renew_subkey(&secret, &fingerprint).expect("renew signature-capable subkey");
    let renewed_subkey = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .expect("renewed subkey");
    let renewed_binding = renewed_subkey
        .signatures
        .iter()
        .find(|signature| signature_creation_time(signature) == Some(SUBKEY_RENEWAL_TIME as u32))
        .expect("renewed subkey binding");
    assert_eq!(authenticated_key_flags(renewed_binding), expected_flags);
    assert_eq!(embedded_signature_count(renewed_binding), 1);
    let renewed_back_signature = renewed_binding
        .embedded_signature()
        .expect("renewed back signature");
    assert_eq!(renewed_back_signature, &original_back_signature);
    renewed_back_signature
        .verify_primary_key_binding(&renewed_subkey.key, &renewed.primary_key)
        .expect("renewed back signature verifies");
}

#[test]
fn certify_only_subkey_renewal_reuses_its_back_signature() {
    let mut flags = pgp::packet::KeyFlags::default();
    flags.set_certify(true);
    assert_signature_capable_subkey_reuses_back_signature(
        Some(flags),
        "OpenPGP Certification <openpgp-certify@example.test>",
    );
}

#[test]
fn authentication_only_subkey_renewal_reuses_its_back_signature() {
    let mut flags = pgp::packet::KeyFlags::default();
    flags.set_authentication(true);
    assert_signature_capable_subkey_reuses_back_signature(
        Some(flags),
        "OpenPGP Authentication <openpgp-auth@example.test>",
    );
}

#[test]
fn authenticated_public_only_subkey_reuses_its_back_signature() {
    let (mut secret, _) = generated_test_certificate(
        "OpenPGP Missing Authentication Secret <openpgp-auth-missing@example.test>",
    );
    let mut flags = pgp::packet::KeyFlags::default();
    flags.set_authentication(true);
    let (fingerprint, original_back_signature) =
        rebind_sign_capable_subkey(&mut secret, Some(flags));
    move_secret_subkey_to_public(&mut secret, &fingerprint);

    let renewed = renew_subkey(&secret, &fingerprint).expect("public-only renewal succeeds");
    let renewed_back_signature = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .and_then(|subkey| {
            subkey
                .signatures
                .iter()
                .find(|signature| {
                    signature_creation_time(signature) == Some(SUBKEY_RENEWAL_TIME as u32)
                })
                .and_then(Signature::embedded_signature)
        })
        .expect("reused back-signature");
    assert_eq!(renewed_back_signature, &original_back_signature);
}

#[test]
fn signature_capable_subkey_without_a_back_signature_requires_secret_material() {
    let (mut secret, _) = generated_test_certificate(
        "OpenPGP Missing Authentication Secret <openpgp-auth-missing@example.test>",
    );
    let mut flags = pgp::packet::KeyFlags::default();
    flags.set_authentication(true);
    let (fingerprint, _) = rebind_sign_capable_subkey(&mut secret, Some(flags));
    remove_back_signatures(&mut secret, &fingerprint);
    move_secret_subkey_to_public(&mut secret, &fingerprint);

    assert_eq!(
        renew_subkey(&secret, &fingerprint).expect_err("subkey secret must be required"),
        ExpirationUpdateFailure::MissingSecretKey,
    );
}

#[test]
fn encryption_only_subkey_renewal_strips_back_signature_without_secret_material() {
    let (mut secret, _) =
        generated_test_certificate("OpenPGP Explicit Encryption <openpgp-encryption@example.test>");
    let mut flags = pgp::packet::KeyFlags::default();
    flags.set_encrypt_comms(true);
    flags.set_encrypt_storage(true);
    let expected_flags = flags.clone();
    let (fingerprint, _) = rebind_sign_capable_subkey(&mut secret, Some(flags));
    move_secret_subkey_to_public(&mut secret, &fingerprint);

    let renewed = renew_subkey(&secret, &fingerprint).expect("renew encryption-only subkey");
    let renewed_binding = renewed
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
        .and_then(|subkey| {
            subkey.signatures.iter().find(|signature| {
                signature_creation_time(signature) == Some(SUBKEY_RENEWAL_TIME as u32)
            })
        })
        .expect("renewed encryption-only binding");
    assert_eq!(
        authenticated_key_flags(renewed_binding),
        Some(expected_flags)
    );
    assert_eq!(embedded_signature_count(renewed_binding), 0);
}

#[test]
fn absent_key_flags_fall_back_to_the_signing_algorithm() {
    assert_signature_capable_subkey_reuses_back_signature(
        None,
        "OpenPGP Algorithm Fallback <openpgp-fallback@example.test>",
    );
}
