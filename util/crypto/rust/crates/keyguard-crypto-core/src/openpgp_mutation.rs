//! OpenPGP v4 expiration recertification and packet-preserving key rebuilding.
//!
//! The module keeps the refreshed public certificate as the source of truth,
//! replaces only authenticated effective self-signatures, and rebuilds the
//! transferable secret key in public-certificate component order. RSA private
//! signatures are routed through the AWS-LC adapter in `openpgp_write`.

use std::io::{self, Cursor};

use pgp::{
    armor::{self, BlockType},
    composed::{
        Deserializable, SignedKeyDetails, SignedPublicKey, SignedPublicSubKey, SignedSecretKey,
    },
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    packet::{
        PacketTrait, SecretKey, SecretSubkey, Signature, SignatureConfig, SignatureType, Subpacket,
        SubpacketData,
    },
    ser::Serialize,
    types::{
        Duration, Fingerprint, KeyDetails, KeyId, KeyVersion, Password, PublicParams,
        SignatureBytes, SigningKey, Tag, Timestamp,
    },
};
use prost::Message;
use thiserror::Error;
use zeroize::Zeroizing;

use crate::{
    openpgp_read::{
        OpenPgpReadBudget, PublicComponent, all_components, fingerprint_hex, inspect_certificate,
        normalize_fingerprint,
    },
    openpgp_write::{AwsLcRsaSecretKey, SecretPacketRef},
    protocol::{
        OpenPgpExpirationUpdateError, OpenPgpExpirationUpdateErrorReason,
        OpenPgpExpirationUpdateRequest, OpenPgpExpirationUpdateResult,
        OpenPgpExpirationUpdateSuccess, OpenPgpKeyMaterial, OpenPgpMetadataResolveRequest,
        OpenPgpMetadataResolveResult, open_pgp_expiration_update_result,
    },
};

const MAX_COMPONENTS: usize = 64;
const MAX_IDENTITIES: usize = 256;
const MAX_SIGNATURES: usize = 4 * 1024;
const MAX_CANDIDATE_CERTIFICATES: usize = 64;
const MAX_KEY_DOCUMENT_BYTES: usize = 8 * 1024 * 1024;

/// Fatal failure that must use the stable native error channel.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum OpenPgpMutationFatal {
    /// An explicit shape or allocation-work limit was exceeded.
    #[error("OpenPGP mutation resource limit exceeded")]
    ResourceLimit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MutationError {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    NoComponentsSelected,
    ComponentNotFound,
    RevokedComponent,
    UnresolvedRevocationAuthority,
    UnsupportedKeyVersion,
    MissingSecretKey,
    ProtectedSecretKey,
    MissingSelfSignature,
    InvalidExpiration,
    TimeConflict,
    SignatureVerificationFailed,
    MetadataResolutionFailed,
    InternalFailure,
    ResourceLimit,
}

impl MutationError {
    const fn reason(self) -> Option<OpenPgpExpirationUpdateErrorReason> {
        Some(match self {
            Self::EmptyPrivateKey => OpenPgpExpirationUpdateErrorReason::EmptyPrivateKey,
            Self::MalformedKey => OpenPgpExpirationUpdateErrorReason::MalformedKey,
            Self::FingerprintMismatch => OpenPgpExpirationUpdateErrorReason::FingerprintMismatch,
            Self::NoComponentsSelected => OpenPgpExpirationUpdateErrorReason::NoComponentsSelected,
            Self::ComponentNotFound => OpenPgpExpirationUpdateErrorReason::ComponentNotFound,
            Self::RevokedComponent => OpenPgpExpirationUpdateErrorReason::RevokedComponent,
            Self::UnresolvedRevocationAuthority => {
                OpenPgpExpirationUpdateErrorReason::UnresolvedRevocationAuthority
            }
            Self::UnsupportedKeyVersion => {
                OpenPgpExpirationUpdateErrorReason::UnsupportedKeyVersion
            }
            Self::MissingSecretKey => OpenPgpExpirationUpdateErrorReason::MissingSecretKey,
            Self::ProtectedSecretKey => OpenPgpExpirationUpdateErrorReason::ProtectedSecretKey,
            Self::MissingSelfSignature => OpenPgpExpirationUpdateErrorReason::MissingSelfSignature,
            Self::InvalidExpiration => OpenPgpExpirationUpdateErrorReason::InvalidExpiration,
            Self::TimeConflict => OpenPgpExpirationUpdateErrorReason::TimeConflict,
            Self::SignatureVerificationFailed => {
                OpenPgpExpirationUpdateErrorReason::SignatureVerificationFailed
            }
            Self::MetadataResolutionFailed => {
                OpenPgpExpirationUpdateErrorReason::MetadataResolutionFailed
            }
            Self::InternalFailure => OpenPgpExpirationUpdateErrorReason::InternalFailure,
            Self::ResourceLimit => return None,
        })
    }
}

/// Executes an expiration update and encodes its typed domain outcome.
pub(crate) fn update_expiration_request(
    request: OpenPgpExpirationUpdateRequest,
) -> Result<Vec<u8>, OpenPgpMutationFatal> {
    match update_expiration(request) {
        Ok(success) => Ok(OpenPgpExpirationUpdateResult {
            result: Some(open_pgp_expiration_update_result::Result::Success(success)),
        }
        .encode_to_vec()),
        Err(MutationError::ResourceLimit) => Err(OpenPgpMutationFatal::ResourceLimit),
        Err(error) => Ok(OpenPgpExpirationUpdateResult {
            result: Some(open_pgp_expiration_update_result::Result::Error(
                OpenPgpExpirationUpdateError {
                    reason: error
                        .reason()
                        .unwrap_or(OpenPgpExpirationUpdateErrorReason::InternalFailure)
                        as i32,
                },
            )),
        }
        .encode_to_vec()),
    }
}

fn update_expiration(
    mut request: OpenPgpExpirationUpdateRequest,
) -> Result<OpenPgpExpirationUpdateSuccess, MutationError> {
    if request.private_key.iter().all(u8::is_ascii_whitespace) {
        return Err(MutationError::EmptyPrivateKey);
    }
    if request.private_key.len() > MAX_KEY_DOCUMENT_BYTES
        || request.public_key.len() > MAX_KEY_DOCUMENT_BYTES
        || request
            .candidate_revocation_keys
            .iter()
            .any(|candidate| candidate.len() > MAX_KEY_DOCUMENT_BYTES)
    {
        return Err(MutationError::ResourceLimit);
    }
    if request.component_fingerprints.is_empty() {
        return Err(MutationError::NoComponentsSelected);
    }
    if request.component_fingerprints.len() > MAX_COMPONENTS
        || request.candidate_revocation_keys.len() > MAX_CANDIDATE_CERTIFICATES
    {
        return Err(MutationError::ResourceLimit);
    }
    let replacement_time = u32::try_from(request.reference_time_epoch_seconds)
        .map(Timestamp::from_secs)
        .map_err(|_| MutationError::InvalidExpiration)?;
    if let Some(expiration) = request.expires_at_epoch_seconds
        && (expiration <= request.reference_time_epoch_seconds || expiration > u64::from(u32::MAX))
    {
        return Err(MutationError::InvalidExpiration);
    }

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let secret = parse_single_secret(&private_key)?;
    let supplied = parse_single_public(&request.public_key, MutationError::FingerprintMismatch)?;
    ensure_v4_secret(&secret)?;
    ensure_v4_public(&supplied)?;
    ensure_shape(&secret, &supplied)?;

    let mut certificate = reconcile_certificate(&secret, supplied)?;
    let primary_fingerprint = fingerprint_hex(&certificate.primary_key);
    let expected = normalize_fingerprint(&request.expected_primary_fingerprint);
    if !expected.is_empty() && expected != primary_fingerprint {
        return Err(MutationError::FingerprintMismatch);
    }
    if fingerprint_hex(secret.primary_key.public_key()) != primary_fingerprint {
        return Err(MutationError::FingerprintMismatch);
    }

    let selected = normalize_selected(&request.component_fingerprints)?;
    let mut external_certificates = Vec::new();
    for candidate in &request.candidate_revocation_keys {
        let parsed = match parse_public_many(candidate) {
            Ok(parsed) => parsed,
            // Vault-local candidate material is advisory. Preserve the JVM
            // behavior by ignoring unrelated or malformed entries while still
            // enforcing parser resource limits before cryptographic work.
            Err(MutationError::MalformedKey | MutationError::UnsupportedKeyVersion) => continue,
            Err(error) => return Err(error),
        };
        if external_certificates.len().saturating_add(parsed.len()) > MAX_CANDIDATE_CERTIFICATES {
            return Err(MutationError::ResourceLimit);
        }
        external_certificates.extend(parsed);
    }
    let mut candidates = all_components(std::slice::from_ref(&certificate));
    candidates.extend(all_components(&external_certificates));
    let mut budget = OpenPgpReadBudget::default();
    let policy = inspect_certificate(
        &certificate,
        &candidates,
        request.reference_time_epoch_seconds,
        &mut budget,
    )
    .map_err(|_| MutationError::SignatureVerificationFailed)?;
    if policy.primary.revoked {
        return Err(MutationError::RevokedComponent);
    }
    if unresolved_primary_revocation(&certificate, &candidates) {
        return Err(MutationError::UnresolvedRevocationAuthority);
    }
    if certificate
        .public_subkeys
        .iter()
        .zip(&policy.subkeys)
        .any(|(subkey, component)| {
            !component.authenticated
                && !subkey.signatures.iter().any(|signature| {
                    signature.typ() == Some(SignatureType::SubkeyBinding)
                        && signature
                            .verify_subkey_binding(&certificate.primary_key, &subkey.key)
                            .is_ok()
                })
        })
    {
        return Err(MutationError::FingerprintMismatch);
    }

    validate_selection(&certificate, &policy, &selected, &candidates)?;
    let primary_selected = selected.contains(&primary_fingerprint);
    if primary_selected && unresolved_identity_revocation(&certificate, &candidates) {
        return Err(MutationError::UnresolvedRevocationAuthority);
    }

    if secret.primary_key.secret_params().is_encrypted() {
        return Err(MutationError::ProtectedSecretKey);
    }
    let primary_packet = SecretPacketRef::Primary(&secret.primary_key);
    let primary_rsa = is_rsa(primary_packet.algorithm())
        .then(|| AwsLcRsaSecretKey::new(primary_packet))
        .transpose()
        .map_err(|_| MutationError::InternalFailure)?;
    let primary_signer = SigningKeyRef(
        primary_rsa
            .as_ref()
            .map_or(&secret.primary_key as &dyn SigningKey, |key| key),
    );

    if primary_selected {
        renew_primary(
            &mut certificate,
            &primary_signer,
            replacement_time,
            request.expires_at_epoch_seconds,
            request.reference_time_epoch_seconds,
            &candidates,
        )?;
    }
    let secret_subkeys = secret_subkeys_by_fingerprint(&secret)?;
    let primary_public = certificate.primary_key.clone();
    for subkey in &mut certificate.public_subkeys {
        let fingerprint = fingerprint_hex(&subkey.key);
        if !selected.contains(&fingerprint) {
            continue;
        }
        renew_subkey(
            subkey,
            &primary_public,
            &primary_signer,
            secret_subkeys.get(&fingerprint).copied(),
            replacement_time,
            request.expires_at_epoch_seconds,
            request.reference_time_epoch_seconds,
        )?;
    }

    validate_renewed_certificate(
        &certificate,
        &selected,
        request.expires_at_epoch_seconds,
        request.reference_time_epoch_seconds,
        &external_certificates,
    )?;

    let public_key_armored = certificate
        .to_armored_bytes(Default::default())
        .map_err(|_| MutationError::InternalFailure)?;
    let private_key_armored = armor_secret_in_certificate_order(&secret, &certificate)?;
    validate_reparsed_outputs(&private_key_armored, &public_key_armored, &selected)?;

    let metadata_bytes = crate::openpgp_read::resolve_metadata(OpenPgpMetadataResolveRequest {
        private_key_data: Some(private_key_armored.clone()),
        public_key_data: Some(public_key_armored.clone()),
        normalized_fingerprint: primary_fingerprint.clone(),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
        reference_time_epoch_seconds: Some(request.reference_time_epoch_seconds),
    })
    .map_err(|_| MutationError::MetadataResolutionFailed)?;
    let mut metadata = OpenPgpMetadataResolveResult::decode(metadata_bytes.as_slice())
        .map_err(|_| MutationError::MetadataResolutionFailed)?
        .metadata
        .ok_or(MutationError::MetadataResolutionFailed)?;
    let secret_fingerprints = std::iter::once(primary_fingerprint.clone())
        .chain(
            secret
                .secret_subkeys
                .iter()
                .map(|subkey| fingerprint_hex(&subkey.key)),
        )
        .collect::<Vec<_>>();
    metadata
        .keys
        .retain(|key| secret_fingerprints.contains(&normalize_fingerprint(&key.fingerprint)));
    if metadata.keys.is_empty() {
        return Err(MutationError::MetadataResolutionFailed);
    }

    Ok(OpenPgpExpirationUpdateSuccess {
        key_material: Some(OpenPgpKeyMaterial {
            private_key_armored,
            public_key_armored,
            fingerprint: primary_fingerprint,
        }),
        metadata: Some(metadata),
    })
}

fn parse_single_secret(input: &[u8]) -> Result<SignedSecretKey, MutationError> {
    let (iterator, _) = SignedSecretKey::from_reader_many(Cursor::new(input))
        .map_err(|_| MutationError::MalformedKey)?;
    let values = iterator
        .take(2)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| MutationError::MalformedKey)?;
    if values.len() != 1 {
        return Err(MutationError::MalformedKey);
    }
    values.into_iter().next().ok_or(MutationError::MalformedKey)
}

fn parse_single_public(
    input: &[u8],
    error: MutationError,
) -> Result<SignedPublicKey, MutationError> {
    let (iterator, _) = SignedPublicKey::from_reader_many(Cursor::new(input)).map_err(|_| error)?;
    let values = iterator
        .take(2)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| error)?;
    if values.len() != 1 {
        return Err(error);
    }
    values.into_iter().next().ok_or(error)
}

fn parse_public_many(input: &[u8]) -> Result<Vec<SignedPublicKey>, MutationError> {
    let (iterator, _) = SignedPublicKey::from_reader_many(Cursor::new(input))
        .map_err(|_| MutationError::MalformedKey)?;
    let values = iterator
        .take(MAX_CANDIDATE_CERTIFICATES + 1)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| MutationError::MalformedKey)?;
    if values.len() > MAX_CANDIDATE_CERTIFICATES {
        Err(MutationError::ResourceLimit)
    } else {
        Ok(values)
    }
}

fn ensure_v4_secret(secret: &SignedSecretKey) -> Result<(), MutationError> {
    let all_v4 = std::iter::once(secret.primary_key.version())
        .chain(
            secret
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .chain(
            secret
                .secret_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .all(|version| version == KeyVersion::V4);
    all_v4
        .then_some(())
        .ok_or(MutationError::UnsupportedKeyVersion)
}

fn ensure_v4_public(certificate: &SignedPublicKey) -> Result<(), MutationError> {
    std::iter::once(certificate.primary_key.version())
        .chain(
            certificate
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .all(|version| version == KeyVersion::V4)
        .then_some(())
        .ok_or(MutationError::UnsupportedKeyVersion)
}

fn ensure_shape(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
) -> Result<(), MutationError> {
    let components = 1_usize
        .saturating_add(secret.public_subkeys.len())
        .saturating_add(secret.secret_subkeys.len())
        .saturating_add(certificate.public_subkeys.len());
    let identities = secret
        .details
        .users
        .len()
        .saturating_add(secret.details.user_attributes.len())
        .saturating_add(certificate.details.users.len())
        .saturating_add(certificate.details.user_attributes.len());
    let signatures = signature_count_secret(secret).saturating_add(signature_count(certificate));
    if components > MAX_COMPONENTS.saturating_mul(2)
        || identities > MAX_IDENTITIES.saturating_mul(2)
        || signatures > MAX_SIGNATURES
    {
        Err(MutationError::ResourceLimit)
    } else {
        Ok(())
    }
}

fn signature_count(certificate: &SignedPublicKey) -> usize {
    certificate
        .details
        .revocation_signatures
        .len()
        .saturating_add(certificate.details.direct_signatures.len())
        .saturating_add(
            certificate
                .details
                .users
                .iter()
                .map(|user| user.signatures.len())
                .sum::<usize>(),
        )
        .saturating_add(
            certificate
                .details
                .user_attributes
                .iter()
                .map(|attribute| attribute.signatures.len())
                .sum::<usize>(),
        )
        .saturating_add(
            certificate
                .public_subkeys
                .iter()
                .map(|subkey| subkey.signatures.len())
                .sum::<usize>(),
        )
}

fn signature_count_secret(secret: &SignedSecretKey) -> usize {
    let public = secret.to_public_key();
    signature_count(&public)
}

fn reconcile_certificate(
    secret: &SignedSecretKey,
    mut supplied: SignedPublicKey,
) -> Result<SignedPublicKey, MutationError> {
    if fingerprint_hex(secret.primary_key.public_key()) != fingerprint_hex(&supplied.primary_key) {
        return Err(MutationError::FingerprintMismatch);
    }
    merge_details(&mut supplied.details, &secret.details);
    let secret_public = secret.to_public_key();
    ensure_unique_components(&supplied)?;
    ensure_unique_components(&secret_public)?;
    for supplied_subkey in &mut supplied.public_subkeys {
        let supplied_fingerprint = fingerprint_hex(&supplied_subkey.key);
        if let Some(secret_subkey) = secret_public
            .public_subkeys
            .iter()
            .find(|subkey| subkey.key.legacy_key_id() == supplied_subkey.key.legacy_key_id())
        {
            if fingerprint_hex(&secret_subkey.key) != supplied_fingerprint {
                return Err(MutationError::FingerprintMismatch);
            }
            append_unique(&mut supplied_subkey.signatures, &secret_subkey.signatures);
        }
    }
    let supplied_fingerprints = supplied
        .public_subkeys
        .iter()
        .map(|subkey| fingerprint_hex(&subkey.key))
        .collect::<Vec<_>>();
    supplied.public_subkeys.extend(
        secret_public
            .public_subkeys
            .into_iter()
            .filter(|subkey| !supplied_fingerprints.contains(&fingerprint_hex(&subkey.key))),
    );
    ensure_unique_components(&supplied)?;
    Ok(supplied)
}

fn merge_details(target: &mut SignedKeyDetails, source: &SignedKeyDetails) {
    append_unique(
        &mut target.revocation_signatures,
        &source.revocation_signatures,
    );
    append_unique(&mut target.direct_signatures, &source.direct_signatures);
    for source_user in &source.users {
        if let Some(target_user) = target
            .users
            .iter_mut()
            .find(|user| user.id == source_user.id)
        {
            append_unique(&mut target_user.signatures, &source_user.signatures);
        } else {
            target.users.push(source_user.clone());
        }
    }
    for source_attribute in &source.user_attributes {
        if let Some(target_attribute) = target
            .user_attributes
            .iter_mut()
            .find(|attribute| attribute.attr == source_attribute.attr)
        {
            append_unique(
                &mut target_attribute.signatures,
                &source_attribute.signatures,
            );
        } else {
            target.user_attributes.push(source_attribute.clone());
        }
    }
}

fn append_unique<T: Clone + PartialEq>(target: &mut Vec<T>, source: &[T]) {
    for value in source {
        if !target.contains(value) {
            target.push(value.clone());
        }
    }
}

fn ensure_unique_components(certificate: &SignedPublicKey) -> Result<(), MutationError> {
    let mut fingerprints = Vec::new();
    let mut key_ids = Vec::new();
    for component in std::iter::once((
        fingerprint_hex(&certificate.primary_key),
        certificate.primary_key.legacy_key_id(),
    ))
    .chain(
        certificate
            .public_subkeys
            .iter()
            .map(|subkey| (fingerprint_hex(&subkey.key), subkey.key.legacy_key_id())),
    ) {
        if fingerprints.contains(&component.0) || key_ids.contains(&component.1) {
            return Err(MutationError::FingerprintMismatch);
        }
        fingerprints.push(component.0);
        key_ids.push(component.1);
    }
    Ok(())
}

fn normalize_selected(values: &[String]) -> Result<Vec<String>, MutationError> {
    let selected = values
        .iter()
        .map(|value| normalize_fingerprint(value))
        .collect::<Vec<_>>();
    if selected.iter().any(String::is_empty) {
        return Err(MutationError::ComponentNotFound);
    }
    let mut distinct = Vec::new();
    for value in &selected {
        if distinct.contains(value) {
            return Err(MutationError::ComponentNotFound);
        }
        distinct.push(value.clone());
    }
    Ok(selected)
}

fn validate_selection(
    certificate: &SignedPublicKey,
    policy: &crate::openpgp_read::CertificatePolicy<'_>,
    selected: &[String],
    candidates: &[PublicComponent],
) -> Result<(), MutationError> {
    let primary_fingerprint = fingerprint_hex(&certificate.primary_key);
    for fingerprint in selected {
        if fingerprint == &primary_fingerprint {
            if policy.primary.revoked {
                return Err(MutationError::RevokedComponent);
            }
            continue;
        }
        let Some((_, component)) = certificate
            .public_subkeys
            .iter()
            .zip(&policy.subkeys)
            .find(|(subkey, _)| fingerprint_hex(&subkey.key) == *fingerprint)
        else {
            return Err(MutationError::ComponentNotFound);
        };
        if component.revoked {
            return Err(MutationError::RevokedComponent);
        }
        if unresolved_subkey_revocation(certificate, fingerprint, candidates) {
            return Err(MutationError::UnresolvedRevocationAuthority);
        }
    }
    Ok(())
}

fn unresolved_primary_revocation(
    certificate: &SignedPublicKey,
    candidates: &[PublicComponent],
) -> bool {
    unresolved_revocations(
        certificate,
        &certificate.details.revocation_signatures,
        candidates,
    )
}

fn unresolved_identity_revocation(
    certificate: &SignedPublicKey,
    candidates: &[PublicComponent],
) -> bool {
    certificate.details.users.iter().any(|user| {
        let revocations = user
            .signatures
            .iter()
            .filter(|signature| signature.typ() == Some(SignatureType::CertRevocation))
            .cloned()
            .collect::<Vec<_>>();
        unresolved_revocations(certificate, &revocations, candidates)
    }) || certificate.details.user_attributes.iter().any(|attribute| {
        let revocations = attribute
            .signatures
            .iter()
            .filter(|signature| signature.typ() == Some(SignatureType::CertRevocation))
            .cloned()
            .collect::<Vec<_>>();
        unresolved_revocations(certificate, &revocations, candidates)
    })
}

fn unresolved_subkey_revocation(
    certificate: &SignedPublicKey,
    fingerprint: &str,
    candidates: &[PublicComponent],
) -> bool {
    let Some(subkey) = certificate
        .public_subkeys
        .iter()
        .find(|subkey| fingerprint_hex(&subkey.key) == fingerprint)
    else {
        return false;
    };
    let revocations = subkey
        .signatures
        .iter()
        .filter(|signature| signature.typ() == Some(SignatureType::SubkeyRevocation))
        .cloned()
        .collect::<Vec<_>>();
    unresolved_revocations(certificate, &revocations, candidates)
}

fn unresolved_revocations(
    certificate: &SignedPublicKey,
    revocations: &[Signature],
    candidates: &[PublicComponent],
) -> bool {
    if revocations.is_empty() {
        return false;
    }
    let declarations = certificate
        .details
        .direct_signatures
        .iter()
        .filter(|signature| signature.verify_key(&certificate.primary_key).is_ok())
        .filter_map(Signature::config)
        .flat_map(|config| config.hashed_subpackets.iter())
        .filter_map(|subpacket| match &subpacket.data {
            SubpacketData::RevocationKey(key) => Some(key),
            _ => None,
        })
        .collect::<Vec<_>>();
    declarations.iter().any(|declaration| {
        let available = candidates.iter().any(|candidate| {
            candidate.algorithm() == declaration.algorithm
                && candidate.fingerprint().as_bytes() == declaration.fingerprint.as_slice()
        });
        !available
            && revocations.iter().any(|signature| {
                signature
                    .config()
                    .is_some_and(|config| config.pub_alg == declaration.algorithm)
            })
    })
}

fn renew_primary(
    certificate: &mut SignedPublicKey,
    signer: &SigningKeyRef<'_>,
    replacement_time: Timestamp,
    expires_at: Option<u64>,
    reference_time: u64,
    candidates: &[PublicComponent],
) -> Result<(), MutationError> {
    let primary = certificate.primary_key.clone();
    let authorized_candidates = authorized_revocation_candidates(certificate, candidates);
    let mut renewed = 0_usize;
    let direct_indices = certificate
        .details
        .direct_signatures
        .iter()
        .enumerate()
        .filter(|(_, signature)| {
            signature.typ() == Some(SignatureType::Key)
                && signature.verify_key(&primary).is_ok()
                && !signature_expired(signature, reference_time)
        })
        .map(|(index, _)| index)
        .collect::<Vec<_>>();
    for index in direct_indices {
        let template = certificate.details.direct_signatures[index].clone();
        let config = replacement_config(
            &template,
            signer.algorithm(),
            &primary,
            replacement_time,
            expires_at,
            None,
        )?;
        let signature = config
            .sign_key(signer, &Password::empty(), &primary)
            .map_err(|_| MutationError::InternalFailure)?;
        signature
            .verify_key(&primary)
            .map_err(|_| MutationError::SignatureVerificationFailed)?;
        certificate.details.direct_signatures[index] = signature;
        renewed = renewed.saturating_add(1);
    }

    for user in &mut certificate.details.users {
        let certifications = user
            .signatures
            .iter()
            .enumerate()
            .filter(|(_, signature)| {
                is_certification(signature.typ())
                    && signature
                        .verify_certification(&primary, Tag::UserId, &user.id)
                        .is_ok()
            })
            .map(|(index, _)| index)
            .collect::<Vec<_>>();
        if certifications.is_empty()
            || identity_revoked_user(&primary, user, &authorized_candidates)
        {
            continue;
        }
        let Some(index) = newest_index(&user.signatures, &certifications) else {
            continue;
        };
        if signature_expired(&user.signatures[index], reference_time) {
            continue;
        }
        let template = user.signatures[index].clone();
        let config = replacement_config(
            &template,
            signer.algorithm(),
            &primary,
            replacement_time,
            expires_at,
            None,
        )?;
        let replacement = config
            .sign_certification(
                signer,
                &primary,
                &Password::empty(),
                user.id.tag(),
                &user.id,
            )
            .map_err(|_| MutationError::InternalFailure)?;
        let first = certifications[0];
        user.signatures = user
            .signatures
            .iter()
            .enumerate()
            .filter(|(candidate, _)| !certifications.contains(candidate))
            .map(|(_, signature)| signature.clone())
            .collect();
        user.signatures
            .insert(first.min(user.signatures.len()), replacement);
        renewed = renewed.saturating_add(1);
    }

    for attribute in &mut certificate.details.user_attributes {
        let certifications = attribute
            .signatures
            .iter()
            .enumerate()
            .filter(|(_, signature)| {
                is_certification(signature.typ())
                    && signature
                        .verify_certification(&primary, Tag::UserAttribute, &attribute.attr)
                        .is_ok()
            })
            .map(|(index, _)| index)
            .collect::<Vec<_>>();
        if certifications.is_empty()
            || identity_revoked_attribute(&primary, attribute, &authorized_candidates)
        {
            continue;
        }
        let Some(index) = newest_index(&attribute.signatures, &certifications) else {
            continue;
        };
        if signature_expired(&attribute.signatures[index], reference_time) {
            continue;
        }
        let template = attribute.signatures[index].clone();
        let config = replacement_config(
            &template,
            signer.algorithm(),
            &primary,
            replacement_time,
            expires_at,
            None,
        )?;
        let replacement = config
            .sign_certification(
                signer,
                &primary,
                &Password::empty(),
                attribute.attr.tag(),
                &attribute.attr,
            )
            .map_err(|_| MutationError::InternalFailure)?;
        let first = certifications[0];
        attribute.signatures = attribute
            .signatures
            .iter()
            .enumerate()
            .filter(|(candidate, _)| !certifications.contains(candidate))
            .map(|(_, signature)| signature.clone())
            .collect();
        attribute
            .signatures
            .insert(first.min(attribute.signatures.len()), replacement);
        renewed = renewed.saturating_add(1);
    }
    if renewed == 0 {
        Err(MutationError::MissingSelfSignature)
    } else {
        Ok(())
    }
}

fn authorized_revocation_candidates(
    certificate: &SignedPublicKey,
    candidates: &[PublicComponent],
) -> Vec<PublicComponent> {
    let declarations = certificate
        .details
        .direct_signatures
        .iter()
        .filter(|signature| signature.verify_key(&certificate.primary_key).is_ok())
        .filter_map(Signature::config)
        .flat_map(|config| config.hashed_subpackets.iter())
        .filter_map(|subpacket| match &subpacket.data {
            SubpacketData::RevocationKey(key) => Some(key),
            _ => None,
        })
        .collect::<Vec<_>>();
    candidates
        .iter()
        .filter(|candidate| {
            declarations.iter().any(|declaration| {
                candidate.algorithm() == declaration.algorithm
                    && candidate.fingerprint().as_bytes() == declaration.fingerprint.as_slice()
            })
        })
        .cloned()
        .collect()
}

fn identity_revoked_user(
    primary: &pgp::packet::PublicKey,
    user: &pgp::types::SignedUser,
    candidates: &[PublicComponent],
) -> bool {
    user.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertRevocation)
            && (signature
                .verify_certification(primary, Tag::UserId, &user.id)
                .is_ok()
                || candidates.iter().any(|candidate| match candidate {
                    PublicComponent::Primary(key) => signature
                        .verify_third_party_certification(primary, key, Tag::UserId, &user.id)
                        .is_ok(),
                    PublicComponent::Subkey(key) => signature
                        .verify_third_party_certification(primary, key, Tag::UserId, &user.id)
                        .is_ok(),
                }))
    })
}

fn identity_revoked_attribute(
    primary: &pgp::packet::PublicKey,
    attribute: &pgp::types::SignedUserAttribute,
    candidates: &[PublicComponent],
) -> bool {
    attribute.signatures.iter().any(|signature| {
        signature.typ() == Some(SignatureType::CertRevocation)
            && (signature
                .verify_certification(primary, Tag::UserAttribute, &attribute.attr)
                .is_ok()
                || candidates.iter().any(|candidate| match candidate {
                    PublicComponent::Primary(key) => signature
                        .verify_third_party_certification(
                            primary,
                            key,
                            Tag::UserAttribute,
                            &attribute.attr,
                        )
                        .is_ok(),
                    PublicComponent::Subkey(key) => signature
                        .verify_third_party_certification(
                            primary,
                            key,
                            Tag::UserAttribute,
                            &attribute.attr,
                        )
                        .is_ok(),
                }))
    })
}

fn renew_subkey(
    subkey: &mut SignedPublicSubKey,
    primary: &pgp::packet::PublicKey,
    primary_signer: &SigningKeyRef<'_>,
    secret_subkey: Option<&SecretSubkey>,
    replacement_time: Timestamp,
    expires_at: Option<u64>,
    reference_time: u64,
) -> Result<(), MutationError> {
    let bindings = subkey
        .signatures
        .iter()
        .enumerate()
        .filter(|(_, signature)| {
            signature.typ() == Some(SignatureType::SubkeyBinding)
                && signature
                    .verify_subkey_binding(primary, &subkey.key)
                    .is_ok()
                && !signature_expired(signature, reference_time)
        })
        .map(|(index, _)| index)
        .collect::<Vec<_>>();
    let template_index =
        newest_index(&subkey.signatures, &bindings).ok_or(MutationError::MissingSelfSignature)?;
    let template = subkey.signatures[template_index].clone();
    let signing = binding_designates_signing_subkey(&template, subkey.key.algorithm());
    let embedded = if signing {
        match template.embedded_signature().filter(|signature| {
            signature
                .verify_primary_key_binding(&subkey.key, primary)
                .is_ok()
        }) {
            Some(signature) => Some(signature.clone()),
            None => Some(create_primary_binding(
                &template,
                primary,
                &subkey.key,
                secret_subkey.ok_or(MutationError::MissingSecretKey)?,
                replacement_time,
            )?),
        }
    } else {
        None
    };
    let config = replacement_config(
        &template,
        primary_signer.algorithm(),
        &subkey.key,
        replacement_time,
        expires_at,
        embedded,
    )?;
    let replacement = config
        .sign_subkey_binding(primary_signer, primary, &Password::empty(), &subkey.key)
        .map_err(|_| MutationError::InternalFailure)?;
    replacement
        .verify_subkey_binding(primary, &subkey.key)
        .map_err(|_| MutationError::SignatureVerificationFailed)?;
    let first = bindings[0];
    subkey.signatures = subkey
        .signatures
        .iter()
        .enumerate()
        .filter(|(index, _)| !bindings.contains(index))
        .map(|(_, signature)| signature.clone())
        .collect();
    subkey
        .signatures
        .insert(first.min(subkey.signatures.len()), replacement);
    Ok(())
}

fn binding_designates_signing_subkey(signature: &Signature, algorithm: PublicKeyAlgorithm) -> bool {
    signature
        .config()
        .and_then(|config| {
            config
                .hashed_subpackets()
                .find_map(|subpacket| match &subpacket.data {
                    SubpacketData::KeyFlags(flags) => Some(flags.sign()),
                    _ => None,
                })
        })
        .unwrap_or_else(|| algorithm.can_sign())
}

fn create_primary_binding(
    binding_template: &Signature,
    primary: &pgp::packet::PublicKey,
    subkey: &pgp::packet::PublicSubkey,
    secret_subkey: &SecretSubkey,
    replacement_time: Timestamp,
) -> Result<Signature, MutationError> {
    if secret_subkey.secret_params().is_encrypted() {
        return Err(MutationError::ProtectedSecretKey);
    }
    let packet = SecretPacketRef::Subkey(secret_subkey);
    let rsa = is_rsa(packet.algorithm())
        .then(|| AwsLcRsaSecretKey::new(packet))
        .transpose()
        .map_err(|_| MutationError::InternalFailure)?;
    let signer = SigningKeyRef(
        rsa.as_ref()
            .map_or(secret_subkey as &dyn SigningKey, |key| key),
    );
    let embedded_template = binding_template.embedded_signature();
    let source = embedded_template.unwrap_or(binding_template);
    let mut config = source
        .config()
        .cloned()
        .ok_or(MutationError::SignatureVerificationFailed)?;
    config.typ = SignatureType::KeyBinding;
    config.pub_alg = signer.algorithm();
    config.hash_alg = replacement_hash(signer.algorithm(), config.hash_alg);
    replace_creation_and_signature_expiration(&mut config, source, replacement_time)?;
    let signature = config
        .sign_primary_key_binding(&signer, subkey, &Password::empty(), primary)
        .map_err(|_| MutationError::InternalFailure)?;
    signature
        .verify_primary_key_binding(subkey, primary)
        .map_err(|_| MutationError::SignatureVerificationFailed)?;
    Ok(signature)
}

fn replacement_config<K: KeyDetails>(
    template: &Signature,
    signing_algorithm: PublicKeyAlgorithm,
    expiring_key: &K,
    replacement_time: Timestamp,
    expires_at: Option<u64>,
    embedded: Option<Signature>,
) -> Result<SignatureConfig, MutationError> {
    let mut config = template
        .config()
        .cloned()
        .ok_or(MutationError::SignatureVerificationFailed)?;
    if config.version() != pgp::packet::SignatureVersion::V4 {
        return Err(MutationError::UnsupportedKeyVersion);
    }
    config.pub_alg = signing_algorithm;
    config.hash_alg = replacement_hash(signing_algorithm, config.hash_alg);
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_)
                | SubpacketData::KeyExpirationTime(_)
                | SubpacketData::EmbeddedSignature(_)
        )
    });
    replace_creation_and_signature_expiration(&mut config, template, replacement_time)?;
    if let Some(expiration) = expires_at {
        let duration = expiration
            .checked_sub(u64::from(expiring_key.created_at().as_secs()))
            .filter(|duration| *duration > 0 && *duration <= u64::from(u32::MAX))
            .and_then(|duration| u32::try_from(duration).ok())
            .ok_or(MutationError::InvalidExpiration)?;
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::KeyExpirationTime(Duration::from_secs(
                duration,
            )))
            .map_err(|_| MutationError::InternalFailure)?,
        );
    }
    if let Some(signature) = embedded {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(signature)))
                .map_err(|_| MutationError::InternalFailure)?,
        );
    }
    Ok(config)
}

fn replace_creation_and_signature_expiration(
    config: &mut SignatureConfig,
    template: &Signature,
    replacement_time: Timestamp,
) -> Result<(), MutationError> {
    let template_time = template
        .created()
        .ok_or(MutationError::MissingSelfSignature)?;
    if replacement_time.as_secs() <= template_time.as_secs() {
        return Err(MutationError::TimeConflict);
    }
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_)
        )
    });
    config.hashed_subpackets.push(
        Subpacket::critical(SubpacketData::SignatureCreationTime(replacement_time))
            .map_err(|_| MutationError::InternalFailure)?,
    );
    if let Some(duration) = replacement_signature_expiration(template, replacement_time) {
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                duration,
            )))
            .map_err(|_| MutationError::InternalFailure)?,
        );
    }
    Ok(())
}

fn replacement_signature_expiration(
    template: &Signature,
    replacement_time: Timestamp,
) -> Option<u32> {
    let duration = template.signature_expiration_time()?.as_secs();
    if duration == 0 {
        return None;
    }
    let expiration = template.created()?.as_secs().wrapping_add(duration);
    if expiration == 0 {
        return None;
    }
    Some(if expiration > replacement_time.as_secs() {
        expiration - replacement_time.as_secs()
    } else {
        1
    })
}

fn replacement_hash(
    signing_algorithm: PublicKeyAlgorithm,
    template: HashAlgorithm,
) -> HashAlgorithm {
    if matches!(
        signing_algorithm,
        PublicKeyAlgorithm::DSA | PublicKeyAlgorithm::ECDSA | PublicKeyAlgorithm::EdDSALegacy
    ) {
        return template;
    }
    if matches!(template, HashAlgorithm::Sha1 | HashAlgorithm::Ripemd160) {
        HashAlgorithm::Sha256
    } else {
        template
    }
}

fn newest_index(signatures: &[Signature], indices: &[usize]) -> Option<usize> {
    indices.iter().copied().reduce(|current, candidate| {
        if signatures[candidate].created().map(Timestamp::as_secs)
            > signatures[current].created().map(Timestamp::as_secs)
        {
            candidate
        } else {
            current
        }
    })
}

fn is_certification(typ: Option<SignatureType>) -> bool {
    matches!(
        typ,
        Some(
            SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
        )
    )
}

fn signature_expired(signature: &Signature, reference_time: u64) -> bool {
    let Some(duration) = signature.signature_expiration_time() else {
        return false;
    };
    if duration.as_secs() == 0 {
        return false;
    }
    let expiration = signature
        .created()
        .map_or(0, Timestamp::as_secs)
        .wrapping_add(duration.as_secs());
    expiration != 0 && expiration <= (reference_time as u32)
}

fn validate_renewed_certificate(
    certificate: &SignedPublicKey,
    selected: &[String],
    expires_at: Option<u64>,
    reference_time: u64,
    external: &[SignedPublicKey],
) -> Result<(), MutationError> {
    let mut candidates = all_components(std::slice::from_ref(certificate));
    candidates.extend(all_components(external));
    let mut budget = OpenPgpReadBudget::default();
    let policy = inspect_certificate(certificate, &candidates, reference_time, &mut budget)
        .map_err(|_| MutationError::SignatureVerificationFailed)?;
    for fingerprint in selected {
        let (created, effective, authenticated) =
            if fingerprint == &fingerprint_hex(&certificate.primary_key) {
                (
                    certificate.primary_key.created_at(),
                    policy.primary.effective_signature,
                    policy.primary.authenticated,
                )
            } else {
                let (_, component) = certificate
                    .public_subkeys
                    .iter()
                    .zip(&policy.subkeys)
                    .find(|(subkey, _)| fingerprint_hex(&subkey.key) == *fingerprint)
                    .ok_or(MutationError::SignatureVerificationFailed)?;
                (
                    component.key.created_at(),
                    component.effective_signature,
                    component.authenticated,
                )
            };
        if !authenticated {
            return Err(MutationError::SignatureVerificationFailed);
        }
        let actual = effective
            .and_then(Signature::key_expiration_time)
            .map_or(0, |duration| u64::from(duration.as_secs()));
        let expected = expires_at
            .map(|expiration| expiration.saturating_sub(u64::from(created.as_secs())))
            .unwrap_or(0);
        if actual != expected {
            return Err(MutationError::SignatureVerificationFailed);
        }
    }
    Ok(())
}

fn validate_reparsed_outputs(
    private_key: &[u8],
    public_key: &[u8],
    selected: &[String],
) -> Result<(), MutationError> {
    let reparsed_secret =
        parse_single_secret(private_key).map_err(|_| MutationError::SignatureVerificationFailed)?;
    let reparsed_public =
        parse_single_public(public_key, MutationError::SignatureVerificationFailed)?;
    if fingerprint_hex(reparsed_secret.primary_key.public_key())
        != fingerprint_hex(&reparsed_public.primary_key)
    {
        return Err(MutationError::SignatureVerificationFailed);
    }
    let public_fingerprints = std::iter::once(fingerprint_hex(&reparsed_public.primary_key))
        .chain(
            reparsed_public
                .public_subkeys
                .iter()
                .map(|subkey| fingerprint_hex(&subkey.key)),
        )
        .collect::<Vec<_>>();
    if !selected
        .iter()
        .all(|value| public_fingerprints.contains(value))
    {
        return Err(MutationError::SignatureVerificationFailed);
    }
    Ok(())
}

fn secret_subkeys_by_fingerprint(
    secret: &SignedSecretKey,
) -> Result<std::collections::BTreeMap<String, &SecretSubkey>, MutationError> {
    let mut result = std::collections::BTreeMap::new();
    for subkey in &secret.secret_subkeys {
        if result
            .insert(fingerprint_hex(&subkey.key), &subkey.key)
            .is_some()
        {
            return Err(MutationError::FingerprintMismatch);
        }
    }
    Ok(result)
}

enum OrderedSecretSubkey<'a> {
    Public(&'a SignedPublicSubKey),
    Secret(&'a SecretSubkey, &'a [Signature]),
}

struct OrderedSecretKey<'a> {
    primary: &'a SecretKey,
    details: &'a SignedKeyDetails,
    subkeys: Vec<OrderedSecretSubkey<'a>>,
}

impl Serialize for OrderedSecretKey<'_> {
    fn to_writer<W: io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        self.primary.to_writer_with_header(writer)?;
        self.details.to_writer(writer)?;
        for subkey in &self.subkeys {
            match subkey {
                OrderedSecretSubkey::Public(subkey) => subkey.to_writer(writer)?,
                OrderedSecretSubkey::Secret(key, signatures) => {
                    key.to_writer_with_header(writer)?;
                    for signature in *signatures {
                        signature.to_writer_with_header(writer)?;
                    }
                }
            }
        }
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.primary.write_len_with_header()
            + self.details.write_len()
            + self
                .subkeys
                .iter()
                .map(|subkey| match subkey {
                    OrderedSecretSubkey::Public(subkey) => subkey.write_len(),
                    OrderedSecretSubkey::Secret(key, signatures) => {
                        key.write_len_with_header()
                            + signatures
                                .iter()
                                .map(|signature| signature.write_len_with_header())
                                .sum::<usize>()
                    }
                })
                .sum::<usize>()
    }
}

fn armor_secret_in_certificate_order(
    secret: &SignedSecretKey,
    certificate: &SignedPublicKey,
) -> Result<Vec<u8>, MutationError> {
    let secret_by_fingerprint = secret_subkeys_by_fingerprint(secret)?;
    let subkeys = certificate
        .public_subkeys
        .iter()
        .map(|subkey| {
            secret_by_fingerprint
                .get(&fingerprint_hex(&subkey.key))
                .map_or(OrderedSecretSubkey::Public(subkey), |secret| {
                    OrderedSecretSubkey::Secret(secret, &subkey.signatures)
                })
        })
        .collect::<Vec<_>>();
    let value = OrderedSecretKey {
        primary: &secret.primary_key,
        details: &certificate.details,
        subkeys,
    };
    let mut output = Vec::new();
    armor::write(&value, BlockType::PrivateKey, &mut output, None, true)
        .map_err(|_| MutationError::InternalFailure)?;
    Ok(output)
}

#[derive(Clone, Copy)]
struct SigningKeyRef<'a>(&'a dyn SigningKey);

impl std::fmt::Debug for SigningKeyRef<'_> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SigningKeyRef")
            .finish_non_exhaustive()
    }
}

impl KeyDetails for SigningKeyRef<'_> {
    fn version(&self) -> KeyVersion {
        self.0.version()
    }

    fn legacy_key_id(&self) -> KeyId {
        self.0.legacy_key_id()
    }

    fn fingerprint(&self) -> Fingerprint {
        self.0.fingerprint()
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        self.0.algorithm()
    }

    fn created_at(&self) -> Timestamp {
        self.0.created_at()
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        self.0.legacy_v3_expiration_days()
    }

    fn public_params(&self) -> &PublicParams {
        self.0.public_params()
    }
}

impl SigningKey for SigningKeyRef<'_> {
    fn sign(
        &self,
        password: &Password,
        hash: HashAlgorithm,
        data: &[u8],
    ) -> pgp::errors::Result<SignatureBytes> {
        self.0.sign(password, hash, data)
    }

    fn hash_alg(&self) -> HashAlgorithm {
        self.0.hash_alg()
    }
}

fn is_rsa(algorithm: PublicKeyAlgorithm) -> bool {
    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{OpenPgpKeyGenerateRequest, OpenPgpKeyKind};

    #[test]
    fn replacement_signature_expiration_preserves_absolute_second() {
        assert_eq!(
            replacement_hash(PublicKeyAlgorithm::RSA, HashAlgorithm::Sha1),
            HashAlgorithm::Sha256
        );
        assert_eq!(
            replacement_hash(PublicKeyAlgorithm::ECDSA, HashAlgorithm::Sha1),
            HashAlgorithm::Sha1
        );
    }

    #[test]
    fn duplicate_or_blank_component_selection_is_rejected() {
        assert_eq!(
            normalize_selected(&["AA".to_owned(), "aa".to_owned()]),
            Err(MutationError::ComponentNotFound)
        );
        assert_eq!(
            normalize_selected(&[" -- ".to_owned()]),
            Err(MutationError::ComponentNotFound)
        );
    }

    #[test]
    fn generated_v4_certificate_renews_primary_without_changing_identity() {
        let generated = crate::openpgp_write::generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "OpenPGP Mutation <openpgp-mutation@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: 1_700_000_000,
            expiration_seconds: Some(86_400),
        })
        .expect("generated certificate");
        let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
        let expected_fingerprint = material.fingerprint.clone();
        let success = update_expiration(OpenPgpExpirationUpdateRequest {
            private_key: material.private_key_armored.clone(),
            public_key: material.public_key_armored.clone(),
            expected_primary_fingerprint: expected_fingerprint.clone(),
            component_fingerprints: vec![expected_fingerprint.clone()],
            expires_at_epoch_seconds: Some(1_700_172_800),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: 1_700_000_120,
        })
        .expect("renewal succeeds");
        let renewed = success.key_material.expect("renewed material");
        assert_eq!(renewed.fingerprint, expected_fingerprint);
        assert!(!renewed.private_key_armored.is_empty());
        assert!(!renewed.public_key_armored.is_empty());
        assert!(success.metadata.is_some());
    }

    #[test]
    fn generated_rsa_certificate_renews_through_aws_lc_signer() {
        let generated = crate::openpgp_write::generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::Rsa as i32,
            user_id: "OpenPGP RSA <openpgp-rsa@example.test>".to_owned(),
            rsa_bits: 3_072,
            creation_time_epoch_seconds: 1_700_000_000,
            expiration_seconds: None,
        })
        .expect("generated RSA certificate");
        let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
        let expected_fingerprint = material.fingerprint.clone();
        let success = update_expiration(OpenPgpExpirationUpdateRequest {
            private_key: material.private_key_armored.clone(),
            public_key: material.public_key_armored.clone(),
            expected_primary_fingerprint: expected_fingerprint.clone(),
            component_fingerprints: vec![expected_fingerprint.clone()],
            expires_at_epoch_seconds: Some(1_700_172_800),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: 1_700_000_120,
        })
        .expect("RSA renewal succeeds");
        assert_eq!(
            success.key_material.as_ref().map(|key| &key.fingerprint),
            Some(&expected_fingerprint)
        );
    }

    #[test]
    fn authentication_only_sign_capable_public_subkey_renews_without_secret_material() {
        let generated = crate::openpgp_write::generate_key_request(OpenPgpKeyGenerateRequest {
            kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
            user_id: "OpenPGP Authentication <openpgp-auth@example.test>".to_owned(),
            rsa_bits: 0,
            creation_time_epoch_seconds: 1_700_000_000,
            expiration_seconds: None,
        })
        .expect("generated certificate");
        let material = OpenPgpKeyMaterial::decode(generated.as_slice()).expect("key material");
        let mut secret = parse_single_secret(&material.private_key_armored).expect("secret key");
        let signing_position = secret
            .secret_subkeys
            .iter()
            .position(|subkey| subkey.key.algorithm().can_sign())
            .expect("sign-capable subkey");
        let signing_subkey = secret.secret_subkeys.remove(signing_position);
        let authentication_key = signing_subkey.key.public_key().clone();
        let mut binding_config = signing_subkey.signatures[0]
            .config()
            .cloned()
            .expect("v4 binding config");
        binding_config.hashed_subpackets.retain(|subpacket| {
            !matches!(
                subpacket.data,
                SubpacketData::KeyFlags(_) | SubpacketData::EmbeddedSignature(_)
            )
        });
        let mut authentication_flags = pgp::packet::KeyFlags::default();
        authentication_flags.set_authentication(true);
        binding_config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::KeyFlags(authentication_flags))
                .expect("authentication key flags"),
        );
        let authentication_binding = binding_config
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &authentication_key,
            )
            .expect("authentication binding");
        authentication_binding
            .verify_subkey_binding(secret.primary_key.public_key(), &authentication_key)
            .expect("valid authentication binding");
        assert!(!binding_designates_signing_subkey(
            &authentication_binding,
            authentication_key.algorithm()
        ));
        secret.public_subkeys.push(SignedPublicSubKey::new(
            authentication_key.clone(),
            vec![authentication_binding],
        ));

        let public = secret.to_public_key();
        let primary_fingerprint = fingerprint_hex(secret.primary_key.public_key());
        let authentication_fingerprint = fingerprint_hex(&authentication_key);
        let success = update_expiration(OpenPgpExpirationUpdateRequest {
            private_key: secret
                .to_armored_bytes(Default::default())
                .expect("armored secret key"),
            public_key: public
                .to_armored_bytes(Default::default())
                .expect("armored public key"),
            expected_primary_fingerprint: primary_fingerprint,
            component_fingerprints: vec![authentication_fingerprint.clone()],
            expires_at_epoch_seconds: Some(1_700_172_800),
            candidate_revocation_keys: Vec::new(),
            reference_time_epoch_seconds: 1_700_000_120,
        })
        .expect("authentication subkey renewal succeeds without its secret material");

        let renewed_material = success.key_material.expect("renewed material");
        let renewed = parse_single_public(
            &renewed_material.public_key_armored,
            MutationError::MalformedKey,
        )
        .expect("renewed public key");
        let renewed_subkey = renewed
            .public_subkeys
            .iter()
            .find(|subkey| fingerprint_hex(&subkey.key) == authentication_fingerprint)
            .expect("renewed authentication subkey");
        let renewed_binding = renewed_subkey
            .signatures
            .iter()
            .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
            .expect("renewed authentication binding");
        let renewed_flags = renewed_binding
            .config()
            .and_then(|config| {
                config
                    .hashed_subpackets()
                    .find_map(|subpacket| match &subpacket.data {
                        SubpacketData::KeyFlags(flags) => Some(flags),
                        _ => None,
                    })
            })
            .expect("authenticated key flags are preserved");
        assert!(renewed_flags.authentication());
        assert!(!renewed_flags.sign());
        assert!(renewed_binding.embedded_signature().is_none());
    }
}
