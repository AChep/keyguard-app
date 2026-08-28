use pgp::{
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    packet::{SignatureConfig, SignatureType, Subpacket},
    types::{Duration, SignatureBytes, Timestamp},
};

use super::*;

fn signature(
    signature_type: SignatureType,
    creation_time: u32,
    reason: Option<RevocationCode>,
    revocable: Option<bool>,
) -> Signature {
    signature_with_expiration(signature_type, creation_time, reason, revocable, None)
}

fn signature_with_expiration(
    signature_type: SignatureType,
    creation_time: u32,
    reason: Option<RevocationCode>,
    revocable: Option<bool>,
    expiration_seconds: Option<u32>,
) -> Signature {
    let mut config = SignatureConfig::v4(
        signature_type,
        PublicKeyAlgorithm::RSA,
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("creation time"),
    ];
    if let Some(reason) = reason {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
                .expect("revocation reason"),
        );
    }
    if let Some(revocable) = revocable {
        config
            .hashed_subpackets
            .push(Subpacket::regular(SubpacketData::Revocable(revocable)).expect("revocable"));
    }
    if let Some(expiration_seconds) = expiration_seconds {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                expiration_seconds,
            )))
            .expect("expiration time"),
        );
    }
    Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic signature")
}

fn signature_without_creation_time(
    signature_type: SignatureType,
    reason: RevocationCode,
) -> Signature {
    let mut config = SignatureConfig::v4(
        signature_type,
        PublicKeyAlgorithm::RSA,
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::RevocationReason(reason, Vec::new().into()))
            .expect("revocation reason"),
    ];
    Signature::from_config(config, [0, 0], SignatureBytes::Mpis(Vec::new()))
        .expect("synthetic signature")
}

#[test]
fn revocation_reason_scopes_are_explicit() {
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::KeySuperseded)),
        RevocationScope::Prospective
    );
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::KeyRetired)),
        RevocationScope::Prospective
    );
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::CertUserIdInvalid)),
        RevocationScope::Prospective
    );
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::KeyCompromised)),
        RevocationScope::Retrospective
    );
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::NoReason)),
        RevocationScope::Retrospective
    );
    assert_eq!(
        revocation_scope_for_reason(None),
        RevocationScope::Retrospective
    );
    assert_eq!(
        revocation_scope_for_reason(Some(RevocationCode::Other(42))),
        RevocationScope::Retrospective
    );
}

#[test]
fn retrospective_revocation_is_final() {
    let old_binding = signature(SignatureType::Key, 100, None, None);
    let new_binding = signature(SignatureType::Key, 300, None, None);
    let revocation = signature(
        SignatureType::KeyRevocation,
        200,
        Some(RevocationCode::KeyCompromised),
        None,
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&old_binding),
        150,
        RevocationTarget::PrimaryKey,
    ));
    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&new_binding),
        300,
        RevocationTarget::PrimaryKey,
    ));
}

#[test]
fn future_prospective_key_and_subkey_revocations_are_ineffective() {
    let old_binding = signature(SignatureType::Key, 100, None, None);

    for (reason, signature_type, target) in [
        (
            RevocationCode::KeySuperseded,
            SignatureType::KeyRevocation,
            RevocationTarget::PrimaryKey,
        ),
        (
            RevocationCode::KeyRetired,
            SignatureType::SubkeyRevocation,
            RevocationTarget::Subkey,
        ),
        (
            RevocationCode::CertUserIdInvalid,
            SignatureType::SubkeyRevocation,
            RevocationTarget::Subkey,
        ),
    ] {
        let revocation = signature(signature_type, 200, Some(reason), None);

        assert!(!revocation_is_effective(
            std::iter::once(&revocation),
            Some(&old_binding),
            199,
            target,
        ));
    }
}

#[test]
fn retired_and_superseded_keys_require_strictly_newer_bindings() {
    for reason in [RevocationCode::KeySuperseded, RevocationCode::KeyRetired] {
        for (binding_type, revocation_type, target) in [
            (
                SignatureType::Key,
                SignatureType::KeyRevocation,
                RevocationTarget::PrimaryKey,
            ),
            (
                SignatureType::SubkeyBinding,
                SignatureType::SubkeyRevocation,
                RevocationTarget::Subkey,
            ),
        ] {
            let revocation = signature(revocation_type, 200, Some(reason), None);
            for (statement_time, expected) in [(199, true), (200, true), (201, false)] {
                let binding = signature(binding_type, statement_time, None, None);
                assert_eq!(
                    revocation_is_effective(
                        std::iter::once(&revocation),
                        Some(&binding),
                        201,
                        target,
                    ),
                    expected,
                    "{target:?}, {reason:?}, statement time {statement_time}",
                );
            }
        }
    }
}

#[test]
fn key_restoration_requires_a_live_owner_statement() {
    for (binding_type, revocation_type, target) in [
        (
            SignatureType::Key,
            SignatureType::KeyRevocation,
            RevocationTarget::PrimaryKey,
        ),
        (
            SignatureType::SubkeyBinding,
            SignatureType::SubkeyRevocation,
            RevocationTarget::Subkey,
        ),
    ] {
        let revocation = signature(revocation_type, 200, Some(RevocationCode::KeyRetired), None);
        let binding = signature_with_expiration(binding_type, 300, None, None, Some(10));
        for (reference_time, expected) in [(299, true), (300, false), (309, false), (310, true)] {
            assert_eq!(
                revocation_is_effective(
                    std::iter::once(&revocation),
                    Some(&binding),
                    reference_time,
                    target
                ),
                expected,
                "{target:?}, reference time {reference_time}",
            );
        }
    }
}

#[test]
fn other_key_revocation_reasons_cannot_be_superseded() {
    for reason in [
        None,
        Some(RevocationCode::NoReason),
        Some(RevocationCode::KeyCompromised),
        Some(RevocationCode::CertUserIdInvalid),
        Some(RevocationCode::Private100),
        Some(RevocationCode::Other(42)),
    ] {
        for (binding_type, revocation_type, target) in [
            (
                SignatureType::Key,
                SignatureType::KeyRevocation,
                RevocationTarget::PrimaryKey,
            ),
            (
                SignatureType::SubkeyBinding,
                SignatureType::SubkeyRevocation,
                RevocationTarget::Subkey,
            ),
        ] {
            let revocation = signature(revocation_type, 200, reason, None);
            let binding = signature(binding_type, 300, None, None);
            assert!(
                revocation_is_effective(std::iter::once(&revocation), Some(&binding), 300, target),
                "{target:?}, {reason:?}"
            );
        }
    }
}

#[test]
fn hard_revocation_blocks_restoration_even_with_superseded_retirement() {
    let binding = signature(SignatureType::Key, 300, None, None);
    let retirement = signature(
        SignatureType::KeyRevocation,
        200,
        Some(RevocationCode::KeyRetired),
        None,
    );
    let compromise = signature(
        SignatureType::KeyRevocation,
        100,
        Some(RevocationCode::KeyCompromised),
        None,
    );
    for revocations in [[&retirement, &compromise], [&compromise, &retirement]] {
        assert!(revocation_is_effective(
            revocations.into_iter(),
            Some(&binding),
            300,
            RevocationTarget::PrimaryKey
        ));
    }
}

#[test]
fn certification_revocations_supersede_equal_or_older_statements() {
    for (binding_type, revocation_type, target) in [
        (
            SignatureType::CertPositive,
            SignatureType::CertRevocation,
            RevocationTarget::Certification { revocable: true },
        ),
        (
            SignatureType::Key,
            SignatureType::CertRevocation,
            RevocationTarget::DirectKeySignature { revocable: true },
        ),
    ] {
        let revocation = signature(revocation_type, 200, Some(RevocationCode::KeyRetired), None);

        for (statement_time, expected) in [(199, true), (200, true), (201, false)] {
            let statement = signature(binding_type, statement_time, None, None);
            assert_eq!(
                revocation_is_effective(
                    std::iter::once(&revocation),
                    Some(&statement),
                    201,
                    target,
                ),
                expected,
                "{target:?}, statement time {statement_time}",
            );
        }
    }
}

#[test]
fn hard_key_revocation_remains_effective_at_the_statement_timestamp() {
    let statement = signature(SignatureType::Key, 200, None, None);
    let revocation = signature(
        SignatureType::KeyRevocation,
        200,
        Some(RevocationCode::KeyCompromised),
        None,
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&statement),
        200,
        RevocationTarget::PrimaryKey,
    ));
}

#[test]
fn prospective_key_and_subkey_revocations_survive_signature_expiration() {
    for reason in [RevocationCode::KeySuperseded, RevocationCode::KeyRetired] {
        for (binding_type, revocation_type, target) in [
            (
                SignatureType::Key,
                SignatureType::KeyRevocation,
                RevocationTarget::PrimaryKey,
            ),
            (
                SignatureType::SubkeyBinding,
                SignatureType::SubkeyRevocation,
                RevocationTarget::Subkey,
            ),
        ] {
            let binding = signature(binding_type, 50, None, None);
            let revocation =
                signature_with_expiration(revocation_type, 100, Some(reason), None, Some(10));

            assert!(revocation_is_effective(
                std::iter::once(&revocation),
                Some(&binding),
                109,
                target,
            ));
            assert!(revocation_is_effective(
                std::iter::once(&revocation),
                Some(&binding),
                110,
                target,
            ));
        }
    }
}

#[test]
fn expired_identity_certification_revocation_is_ineffective() {
    let binding = signature(SignatureType::CertPositive, 50, None, None);
    let revocation = signature_with_expiration(
        SignatureType::CertRevocation,
        100,
        Some(RevocationCode::CertUserIdInvalid),
        None,
        Some(10),
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        109,
        RevocationTarget::Certification { revocable: true },
    ));
    assert!(!revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        110,
        RevocationTarget::Certification { revocable: true },
    ));
}

#[test]
fn expired_direct_key_certification_revocation_is_ineffective() {
    let binding = signature(SignatureType::Key, 50, None, None);
    let revocation = signature_with_expiration(
        SignatureType::CertRevocation,
        100,
        Some(RevocationCode::NoReason),
        None,
        Some(10),
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        109,
        RevocationTarget::DirectKeySignature { revocable: true },
    ));
    assert!(!revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        110,
        RevocationTarget::DirectKeySignature { revocable: true },
    ));
}

#[test]
fn missing_creation_time_is_always_ineffective() {
    let binding = signature(SignatureType::CertPositive, 50, None, None);

    for (signature_type, reason, target) in [
        (
            SignatureType::KeyRevocation,
            RevocationCode::KeyCompromised,
            RevocationTarget::PrimaryKey,
        ),
        (
            SignatureType::KeyRevocation,
            RevocationCode::KeyRetired,
            RevocationTarget::PrimaryKey,
        ),
        (
            SignatureType::SubkeyRevocation,
            RevocationCode::KeyRetired,
            RevocationTarget::Subkey,
        ),
        (
            SignatureType::CertRevocation,
            RevocationCode::NoReason,
            RevocationTarget::Certification { revocable: true },
        ),
    ] {
        let revocation = signature_without_creation_time(signature_type, reason);
        assert!(!revocation_is_effective(
            std::iter::once(&revocation),
            Some(&binding),
            100,
            target,
        ));
    }
}

#[test]
fn certification_revocations_are_prospective_and_newer_evidence_supersedes_them() {
    let old_binding = signature(SignatureType::CertPositive, 100, None, None);
    let new_binding = signature(SignatureType::CertPositive, 300, None, None);
    for reason in [
        None,
        Some(RevocationCode::NoReason),
        Some(RevocationCode::KeyCompromised),
        Some(RevocationCode::CertUserIdInvalid),
    ] {
        let revocation = signature(SignatureType::CertRevocation, 200, reason, None);

        assert!(!revocation_is_effective(
            std::iter::once(&revocation),
            Some(&old_binding),
            150,
            RevocationTarget::Certification { revocable: true },
        ));
        assert!(revocation_is_effective(
            std::iter::once(&revocation),
            Some(&old_binding),
            200,
            RevocationTarget::Certification { revocable: true },
        ));
        assert!(!revocation_is_effective(
            std::iter::once(&revocation),
            Some(&new_binding),
            300,
            RevocationTarget::Certification { revocable: true },
        ));
    }
}

#[test]
fn non_revocable_certification_rejects_later_revocation() {
    let binding = signature(SignatureType::CertPositive, 100, None, Some(false));
    let revocation = signature(
        SignatureType::CertRevocation,
        200,
        Some(RevocationCode::NoReason),
        None,
    );

    assert!(!revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        300,
        RevocationTarget::Certification { revocable: false },
    ));
}

#[test]
fn revocable_is_scoped_to_the_superseded_certification() {
    // RFC 4880 §5.2.3.12 / RFC 9580 §5.2.3.20: the Revocable flag binds the
    // signature that carries it. The newer revocable certification can be
    // discarded without revoking the older non-revocable certification.
    let older_binding = signature(SignatureType::CertPositive, 100, None, Some(false));
    let newer_binding = signature(SignatureType::CertPositive, 200, None, None);
    let revocation = signature(
        SignatureType::CertRevocation,
        300,
        Some(RevocationCode::NoReason),
        None,
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&newer_binding),
        300,
        RevocationTarget::Certification {
            revocable: signature_is_revocable(&newer_binding),
        },
    ));
    // The non-revocable certification protects itself and remains eligible
    // for fallback selection.
    assert!(!revocation_is_effective(
        std::iter::once(&revocation),
        Some(&older_binding),
        300,
        RevocationTarget::Certification {
            revocable: signature_is_revocable(&older_binding),
        },
    ));
}

#[test]
fn non_revocable_binding_does_not_suppress_subkey_revocation() {
    let binding = signature(SignatureType::SubkeyBinding, 300, None, Some(false));
    let revocation = signature(
        SignatureType::SubkeyRevocation,
        200,
        Some(RevocationCode::KeyCompromised),
        None,
    );

    assert!(revocation_is_effective(
        std::iter::once(&revocation),
        Some(&binding),
        300,
        RevocationTarget::Subkey,
    ));
}
