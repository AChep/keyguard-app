//! Target-specific OpenPGP certification construction.
//!
//! Mutation workflows decide which identity and policy template to use. This
//! module owns the exact User ID target parsing and signing call so key
//! generation and identity replacement cannot diverge at that boundary.

use pgp::{
    crypto::hash::HashAlgorithm,
    packet::{
        PacketTrait, RevocationCode, Signature, SignatureConfig, SignatureType, Subpacket,
        SubpacketData, UserId,
    },
    ser::Serialize,
    types::{Duration, KeyDetails, Password, SigningKey, Tag, Timestamp},
};
use thiserror::Error;

use crate::openpgp::crypto::{
    signer::{SigningKeyRef, select_signature_hash},
    verification::{
        is_certification, signature_config_is_non_exportable, signature_creation_time,
        signature_expiration_seconds,
    },
};

#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum UserIdCertificationError {
    #[error("invalid OpenPGP User ID")]
    InvalidUserId,
    #[error("invalid OpenPGP User ID certification type")]
    InvalidSignatureType,
    #[error("OpenPGP User ID certification failed")]
    SigningFailed,
    #[error("no acceptable OpenPGP signing hash for the signer")]
    UnsupportedSigningHash,
    #[error("unsupported OpenPGP User ID certification template")]
    UnsupportedTemplate,
}

pub(crate) struct UserIdCertification {
    pub(crate) user_id: UserId,
    pub(crate) signature: Signature,
}

/// Prepares a V4 self-certification for a replacement textual User ID.
///
/// Only authenticated, context-appropriate policy subpackets are retained.
/// Identity, issuer, and time fields are regenerated for the new statement;
/// signature lifetime and local-only exportability follow the certification
/// being replaced rather than the certificate's primary policy template.
pub(crate) fn new_user_id_certification_config(
    policy_template: &Signature,
    lifetime_template: &Signature,
    signer: SigningKeyRef<'_>,
    creation_time: Timestamp,
    primary: bool,
) -> Result<SignatureConfig, UserIdCertificationError> {
    let policy_config = policy_template
        .config()
        .ok_or(UserIdCertificationError::UnsupportedTemplate)?;
    let lifetime_config = lifetime_template
        .config()
        .ok_or(UserIdCertificationError::UnsupportedTemplate)?;
    if policy_config.version() != pgp::packet::SignatureVersion::V4
        || lifetime_config.version() != pgp::packet::SignatureVersion::V4
        || !is_certification(Some(policy_config.typ))
        || !is_certification(Some(lifetime_config.typ))
    {
        return Err(UserIdCertificationError::UnsupportedTemplate);
    }
    let mut config = SignatureConfig::v4(
        lifetime_config.typ,
        signer.algorithm(),
        replacement_hash(signer, policy_config.hash_alg)?,
    );
    config.hashed_subpackets = vec![
        Subpacket::critical(SubpacketData::SignatureCreationTime(creation_time))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
    ];
    for subpacket in &policy_config.hashed_subpackets {
        let retain = match &subpacket.data {
            SubpacketData::KeyExpirationTime(_)
            | SubpacketData::PreferredSymmetricAlgorithms(_)
            | SubpacketData::PreferredHashAlgorithms(_)
            | SubpacketData::PreferredCompressionAlgorithms(_)
            | SubpacketData::KeyServerPreferences(_)
            | SubpacketData::KeyFlags(_)
            | SubpacketData::Features(_)
            | SubpacketData::PreferredKeyServer(_)
            | SubpacketData::PolicyURI(_)
            | SubpacketData::PreferredEncryptionModes(_)
            | SubpacketData::PreferredAeadAlgorithms(_) => true,
            SubpacketData::RevocationKey(_) => {
                return Err(UserIdCertificationError::UnsupportedTemplate);
            }
            SubpacketData::Experimental(_, _) | SubpacketData::Other(_, _)
                if subpacket.is_critical =>
            {
                return Err(UserIdCertificationError::UnsupportedTemplate);
            }
            SubpacketData::SignatureCreationTime(_)
            | SubpacketData::SignatureExpirationTime(_)
            | SubpacketData::IssuerKeyId(_)
            | SubpacketData::RevocationReason(_, _)
            | SubpacketData::IsPrimary(_)
            | SubpacketData::Revocable(_)
            | SubpacketData::EmbeddedSignature(_)
            | SubpacketData::Notation(_)
            | SubpacketData::SignersUserID(_)
            | SubpacketData::TrustSignature(_, _)
            | SubpacketData::RegularExpression(_)
            | SubpacketData::ExportableCertification(_)
            | SubpacketData::IssuerFingerprint(_)
            | SubpacketData::IntendedRecipientFingerprint(_)
            | SubpacketData::Experimental(_, _)
            | SubpacketData::Other(_, _)
            | SubpacketData::SignatureTarget(_, _, _) => false,
        };
        if retain {
            config.hashed_subpackets.push(subpacket.clone());
        }
    }
    if signature_config_is_non_exportable(lifetime_config) {
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::ExportableCertification(false))
                .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
        );
    }
    if let Some(duration) = replacement_signature_expiration(lifetime_template, creation_time)? {
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                duration,
            )))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
        );
    }
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::IsPrimary(primary))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
    );
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
    ];
    Ok(config)
}

/// Reissues a certification over the same User ID while changing only fields
/// owned by the new signature and the requested primary-User-ID marker.
///
/// Unlike a certification for a new identity, this path preserves every safely
/// reusable signed assertion from the effective certification, including a
/// `Revocable(false)` subpacket.  GnuPG will not let a newer revocable
/// certification supersede a live non-revocable one, so rebuilding this
/// signature from an allowlist would make the primary-marker update inert.
pub(crate) fn existing_user_id_recertification_config(
    template: &Signature,
    signer: SigningKeyRef<'_>,
    creation_time: Timestamp,
    primary: bool,
) -> Result<SignatureConfig, UserIdCertificationError> {
    let mut config = template
        .config()
        .ok_or(UserIdCertificationError::UnsupportedTemplate)?
        .clone();
    if config.version() != pgp::packet::SignatureVersion::V4 || !is_certification(Some(config.typ))
    {
        return Err(UserIdCertificationError::UnsupportedTemplate);
    }
    validate_preserved_subpackets(&config.hashed_subpackets)?;
    validate_preserved_subpackets(&config.unhashed_subpackets)?;
    let creation_time_is_critical = last_hashed_subpacket_is_critical(
        &config,
        |data| matches!(data, SubpacketData::SignatureCreationTime(_)),
        true,
    );
    let issuer_fingerprint_is_critical = last_hashed_subpacket_is_critical(
        &config,
        |data| matches!(data, SubpacketData::IssuerFingerprint(_)),
        false,
    );
    let signature_expiration_is_critical = last_hashed_subpacket_is_critical(
        &config,
        |data| matches!(data, SubpacketData::SignatureExpirationTime(_)),
        true,
    );
    let primary_marker_is_critical = last_hashed_subpacket_is_critical(
        &config,
        |data| matches!(data, SubpacketData::IsPrimary(_)),
        false,
    );

    config.pub_alg = signer.algorithm();
    config.hash_alg = replacement_hash(signer, config.hash_alg)?;
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            &subpacket.data,
            SubpacketData::SignatureCreationTime(_)
                | SubpacketData::SignatureExpirationTime(_)
                | SubpacketData::IssuerKeyId(_)
                | SubpacketData::IssuerFingerprint(_)
                | SubpacketData::IsPrimary(_)
        )
    });
    config.unhashed_subpackets.retain(|subpacket| {
        !matches!(
            &subpacket.data,
            SubpacketData::SignatureCreationTime(_)
                | SubpacketData::SignatureExpirationTime(_)
                | SubpacketData::IssuerKeyId(_)
                | SubpacketData::IssuerFingerprint(_)
                | SubpacketData::IsPrimary(_)
        )
    });
    config.hashed_subpackets.insert(
        0,
        configured_subpacket(
            SubpacketData::SignatureCreationTime(creation_time),
            creation_time_is_critical,
        )?,
    );
    config.hashed_subpackets.insert(
        1,
        configured_subpacket(
            SubpacketData::IssuerFingerprint(signer.fingerprint()),
            issuer_fingerprint_is_critical,
        )?,
    );
    if let Some(duration) = replacement_signature_expiration(template, creation_time)? {
        config.hashed_subpackets.push(configured_subpacket(
            SubpacketData::SignatureExpirationTime(Duration::from_secs(duration)),
            signature_expiration_is_critical,
        )?);
    }
    config.hashed_subpackets.push(configured_subpacket(
        SubpacketData::IsPrimary(primary),
        primary_marker_is_critical,
    )?);
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .map_err(|_| UserIdCertificationError::UnsupportedTemplate)?,
    );
    Ok(config)
}

fn validate_preserved_subpackets(subpackets: &[Subpacket]) -> Result<(), UserIdCertificationError> {
    for subpacket in subpackets {
        match &subpacket.data {
            SubpacketData::RevocationKey(_) => {
                return Err(UserIdCertificationError::UnsupportedTemplate);
            }
            SubpacketData::Experimental(_, _) | SubpacketData::Other(_, _)
                if subpacket.is_critical =>
            {
                return Err(UserIdCertificationError::UnsupportedTemplate);
            }
            _ => {}
        }
    }
    Ok(())
}

fn last_hashed_subpacket_is_critical(
    config: &SignatureConfig,
    predicate: impl Fn(&SubpacketData) -> bool,
    default: bool,
) -> bool {
    config
        .hashed_subpackets
        .iter()
        .rev()
        .find(|subpacket| predicate(&subpacket.data))
        .map_or(default, |subpacket| subpacket.is_critical)
}

fn configured_subpacket(
    data: SubpacketData,
    critical: bool,
) -> Result<Subpacket, UserIdCertificationError> {
    if critical {
        Subpacket::critical(data)
    } else {
        Subpacket::regular(data)
    }
    .map_err(|_| UserIdCertificationError::UnsupportedTemplate)
}

/// Builds a Certification Revocation with the certification's exportability.
///
/// A local certification needs a local revocation: otherwise the revocation
/// itself is exportable evidence that can republish the attached identity.
#[must_use]
pub(crate) struct UserIdRevocationBuilder<'a, K> {
    signer: SigningKeyRef<'a>,
    primary_key: &'a K,
    user_id: &'a UserId,
    certification_template: &'a Signature,
    creation_time: Timestamp,
}

impl<'a, K> UserIdRevocationBuilder<'a, K>
where
    K: KeyDetails + Serialize,
{
    pub(crate) fn new(
        signer: SigningKeyRef<'a>,
        primary_key: &'a K,
        user_id: &'a UserId,
        certification_template: &'a Signature,
        creation_time: Timestamp,
    ) -> Self {
        Self {
            signer,
            primary_key,
            user_id,
            certification_template,
            creation_time,
        }
    }

    pub(crate) fn build(self) -> Result<Signature, UserIdCertificationError> {
        let mut config = SignatureConfig::v4(
            SignatureType::CertRevocation,
            self.signer.algorithm(),
            replacement_hash(self.signer, HashAlgorithm::Sha256)?,
        );
        config.hashed_subpackets = vec![
            Subpacket::critical(SubpacketData::SignatureCreationTime(self.creation_time))
                .map_err(|_| UserIdCertificationError::SigningFailed)?,
            Subpacket::regular(SubpacketData::IssuerFingerprint(self.signer.fingerprint()))
                .map_err(|_| UserIdCertificationError::SigningFailed)?,
            Subpacket::regular(SubpacketData::RevocationReason(
                RevocationCode::CertUserIdInvalid,
                Vec::new().into(),
            ))
            .map_err(|_| UserIdCertificationError::SigningFailed)?,
        ];
        if self
            .certification_template
            .config()
            .is_some_and(signature_config_is_non_exportable)
        {
            config.hashed_subpackets.push(
                Subpacket::critical(SubpacketData::ExportableCertification(false))
                    .map_err(|_| UserIdCertificationError::SigningFailed)?,
            );
        }
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(self.signer.legacy_key_id()))
                .map_err(|_| UserIdCertificationError::SigningFailed)?,
        ];
        config
            .sign_certification(
                &self.signer,
                self.primary_key,
                &Password::empty(),
                Tag::UserId,
                self.user_id,
            )
            .map_err(|_| UserIdCertificationError::SigningFailed)
    }
}

fn replacement_hash(
    signer: SigningKeyRef<'_>,
    template: HashAlgorithm,
) -> Result<HashAlgorithm, UserIdCertificationError> {
    select_signature_hash(signer.algorithm(), signer.hash_alg(), template)
        .ok_or(UserIdCertificationError::UnsupportedSigningHash)
}

fn replacement_signature_expiration(
    template: &Signature,
    replacement_time: Timestamp,
) -> Result<Option<u32>, UserIdCertificationError> {
    let created =
        signature_creation_time(template).ok_or(UserIdCertificationError::UnsupportedTemplate)?;
    let Some(duration) = signature_expiration_seconds(template) else {
        return Ok(None);
    };
    let expires_at = u64::from(created)
        .checked_add(u64::from(duration))
        .ok_or(UserIdCertificationError::UnsupportedTemplate)?;
    let remaining = expires_at
        .checked_sub(u64::from(replacement_time.as_secs()))
        .filter(|value| *value > 0)
        .ok_or(UserIdCertificationError::UnsupportedTemplate)?;
    u32::try_from(remaining)
        .map(Some)
        .map_err(|_| UserIdCertificationError::UnsupportedTemplate)
}

/// Signs one textual User ID using an already prepared certification policy.
///
/// The caller owns hashed-subpacket policy and signature-version selection.
/// Keeping that policy explicit lets a future replacement mutation derive it
/// from the certificate being replaced rather than silently using generation
/// defaults.
#[must_use]
pub(crate) struct UserIdCertificationBuilder<'a, K> {
    signer: SigningKeyRef<'a>,
    primary_key: &'a K,
    target: UserIdCertificationTarget<'a>,
    config: SignatureConfig,
}

enum UserIdCertificationTarget<'a> {
    Text(&'a str),
    Existing(&'a UserId),
}

impl<'a, K> UserIdCertificationBuilder<'a, K>
where
    K: KeyDetails + Serialize,
{
    pub(crate) fn new(
        signer: SigningKeyRef<'a>,
        primary_key: &'a K,
        user_id: &'a str,
        config: SignatureConfig,
    ) -> Self {
        Self {
            signer,
            primary_key,
            target: UserIdCertificationTarget::Text(user_id),
            config,
        }
    }

    pub(crate) fn for_existing(
        signer: SigningKeyRef<'a>,
        primary_key: &'a K,
        user_id: &'a UserId,
        config: SignatureConfig,
    ) -> Self {
        Self {
            signer,
            primary_key,
            target: UserIdCertificationTarget::Existing(user_id),
            config,
        }
    }

    pub(crate) fn build(self) -> Result<UserIdCertification, UserIdCertificationError> {
        if !is_certification(Some(self.config.typ)) {
            return Err(UserIdCertificationError::InvalidSignatureType);
        }
        let user_id = match self.target {
            UserIdCertificationTarget::Text(value) => {
                UserId::from_str(Default::default(), value)
                    .map_err(|_| UserIdCertificationError::InvalidUserId)?
            }
            UserIdCertificationTarget::Existing(user_id) => user_id.clone(),
        };
        let signature = self
            .config
            .sign_certification(
                &self.signer,
                self.primary_key,
                &Password::empty(),
                user_id.tag(),
                &user_id,
            )
            .map_err(|_| UserIdCertificationError::SigningFailed)?;
        Ok(UserIdCertification { user_id, signature })
    }
}
