//! Deterministic OpenPGP key and signature selection.
//!
//! Component usability is derived only from an already validated certificate
//! view. Equal-time valid statements are ordered by their cryptographic
//! signature material, so unauthenticated packet order cannot change policy.

use pgp::{
    crypto::public_key::PublicKeyAlgorithm,
    packet::{KeyFlags, PublicKey, PublicSubkey, Signature, SubpacketData},
    types::{KeyDetails, KeyVersion, PublicParams},
};

use crate::openpgp::crypto::leading_mpi_bits;
use crate::openpgp::crypto::verification::{
    cryptographic_signature_material_cmp, key_signature_verification_acceptable,
    signature_algorithm_acceptable, signature_creation_time, signature_expiration_seconds,
    signature_ignoring_unhashed_issuer_hints, signature_matches_signer,
    signature_verification_compatible,
};

use super::{
    acceptance::{CertificateValidationTimes, authentication_signature_acceptable_at},
    budget::{OpenPgpPolicyBudget, OpenPgpPolicyError},
    model::{
        AuthenticatedFeatures, ComponentPolicy, EncryptionModePreferences, PolicyContext,
        PolicySelection, SignaturePolicyProjection,
    },
};

const MIN_RSA_ENCRYPTION_BITS: u16 = 2_048;

pub(in crate::openpgp) fn authenticated_key_flags(signature: &Signature) -> Option<KeyFlags> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match &subpacket.data {
                SubpacketData::KeyFlags(flags) => Some(flags.clone()),
                _ => None,
            })
    })
}

pub(in crate::openpgp) fn select_newest_policy_signature<'a>(
    signatures: impl Iterator<Item = &'a Signature>,
    context: PolicyContext,
    cross_certified: impl FnMut(&Signature) -> Result<bool, OpenPgpPolicyError>,
) -> Result<PolicySelection<'a>, OpenPgpPolicyError> {
    select_newest_policy_signature_in(signatures, context, cross_certified)
}

/// Selects the newest self-signature, generic over the caller's error type so
/// mutation-side selection can reuse it verbatim.
///
/// RFC 9580 requires the most recent valid self-signature. Its timestamp has
/// one-second precision and the RFC does not define a secondary order. Match
/// Sequoia by using the lexicographically smaller cryptographic signature
/// material for equal timestamps. The single-pass selection avoids allocations
/// and ignores mutable unhashed metadata.
pub(in crate::openpgp) fn select_newest_policy_signature_in<'a, E>(
    signatures: impl Iterator<Item = &'a Signature>,
    context: PolicyContext,
    mut cross_certified: impl FnMut(&Signature) -> Result<bool, E>,
) -> Result<PolicySelection<'a>, E> {
    let mut newest: Option<(u32, &'a Signature)> = None;
    for signature in signatures {
        let Some(time) = signature_creation_time(signature) else {
            continue;
        };
        if newest.is_none_or(|(newest_time, newest_signature)| {
            time > newest_time
                || (time == newest_time
                    && cryptographic_signature_material_cmp(signature, newest_signature).is_lt())
        }) {
            newest = Some((time, signature));
        }
    }
    let Some((_, selected)) = newest else {
        return Ok(PolicySelection::Missing);
    };
    let selected_projection =
        signature_policy_projection(selected, context, cross_certified(selected)?);
    Ok(PolicySelection::Selected {
        signature: selected,
        projection: Box::new(selected_projection),
    })
}

pub(in crate::openpgp) fn signature_policy_projection(
    signature: &Signature,
    context: PolicyContext,
    signing_cross_certified: bool,
) -> SignaturePolicyProjection {
    SignaturePolicyProjection {
        signature_expiration_seconds: signature_expiration_seconds(signature),
        key_expiration_seconds: key_expiration_seconds(signature),
        key_flags: authenticated_key_flags(signature),
        is_primary: matches!(context, PolicyContext::Identity) && signature_is_primary(signature),
        // RFC 9580 sections 5.2.3.10 and 12.2 permit preferences and
        // Features on a Subkey Binding signature.  They apply specifically
        // to that subkey and therefore must be projected before the broader
        // primary policy is considered as a field-by-field fallback.
        preferred_symmetric: preferred_symmetric_algorithms(signature),
        preferred_compression: preferred_compression_algorithms(signature),
        preferred_aead: preferred_aead_algorithms(signature),
        preferred_encryption_modes: preferred_encryption_modes(signature),
        features: signature_features(signature),
        signing_cross_certified: matches!(context, PolicyContext::Subkey)
            && signing_cross_certified,
    }
}

pub(super) fn merge_subkey_binding_policy(
    mut binding: SignaturePolicyProjection,
    primary: &SignaturePolicyProjection,
) -> SignaturePolicyProjection {
    // A binding statement has the narrowest RFC 9580 scope.  Fall back one
    // field at a time so an explicit binding value (including an explicit
    // negative Features value or an ambiguous type-34 statement) cannot be
    // widened by certificate-level policy.
    binding.preferred_symmetric = binding
        .preferred_symmetric
        .or_else(|| primary.preferred_symmetric.clone());
    binding.preferred_compression = binding
        .preferred_compression
        .or_else(|| primary.preferred_compression.clone());
    binding.preferred_aead = binding
        .preferred_aead
        .or_else(|| primary.preferred_aead.clone());
    binding.preferred_encryption_modes = binding
        .preferred_encryption_modes
        .or(primary.preferred_encryption_modes.clone());
    binding.features = binding.features.or(primary.features.clone());
    binding
}

pub(super) fn merge_v4_primary_policy(
    direct: SignaturePolicyProjection,
    identity: SignaturePolicyProjection,
) -> SignaturePolicyProjection {
    // RFC 9580 scopes preferences as narrowly as possible. For a V4 key, keep
    // the selected primary User ID's statement ahead of certificate-wide
    // Direct Key defaults instead of combining fields from unrelated identity
    // certifications.
    SignaturePolicyProjection {
        signature_expiration_seconds: identity
            .signature_expiration_seconds
            .or(direct.signature_expiration_seconds),
        key_expiration_seconds: identity
            .key_expiration_seconds
            .or(direct.key_expiration_seconds),
        key_flags: identity.key_flags.or(direct.key_flags),
        is_primary: identity.is_primary,
        preferred_symmetric: identity.preferred_symmetric.or(direct.preferred_symmetric),
        preferred_compression: identity
            .preferred_compression
            .or(direct.preferred_compression),
        preferred_aead: identity.preferred_aead.or(direct.preferred_aead),
        preferred_encryption_modes: identity
            .preferred_encryption_modes
            .or(direct.preferred_encryption_modes),
        features: identity.features.or(direct.features),
        signing_cross_certified: false,
    }
}

impl SignaturePolicyProjection {
    pub(super) fn allows_gnupg_ocb(&self) -> bool {
        let advertises_gnupg_aead = self.features.contains(0x02);
        let supports_aes256 = self.preferred_symmetric.as_ref().is_some_and(|algorithms| {
            algorithms.contains(&u8::from(pgp::crypto::sym::SymmetricKeyAlgorithm::AES256))
        });
        let supports_ocb = self
            .preferred_encryption_modes
            .contains(u8::from(pgp::crypto::aead::AeadAlgorithm::Ocb));
        advertises_gnupg_aead && supports_aes256 && supports_ocb
    }
}

pub(super) fn key_expiration_seconds(signature: &Signature) -> Option<Option<u32>> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match subpacket.data {
                SubpacketData::KeyExpirationTime(duration) => {
                    Some((duration.as_secs() != 0).then_some(duration.as_secs()))
                }
                _ => None,
            })
    })
}

pub(in crate::openpgp) fn signature_is_primary(signature: &Signature) -> bool {
    signature.config().is_some_and(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match subpacket.data {
                SubpacketData::IsPrimary(value) => Some(value),
                _ => None,
            })
            .unwrap_or(false)
    })
}

fn preferred_symmetric_algorithms(signature: &Signature) -> Option<Vec<u8>> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match &subpacket.data {
                SubpacketData::PreferredSymmetricAlgorithms(values) => {
                    Some(values.iter().copied().map(u8::from).collect())
                }
                _ => None,
            })
    })
}

fn preferred_compression_algorithms(signature: &Signature) -> Option<Vec<u8>> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match &subpacket.data {
                SubpacketData::PreferredCompressionAlgorithms(values) => {
                    Some(values.iter().copied().map(u8::from).collect())
                }
                _ => None,
            })
    })
}

fn preferred_aead_algorithms(signature: &Signature) -> Option<Vec<(u8, u8)>> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match &subpacket.data {
                SubpacketData::PreferredAeadAlgorithms(values) => Some(
                    values
                        .iter()
                        .map(|(symmetric, aead)| (u8::from(*symmetric), u8::from(*aead)))
                        .collect(),
                ),
                _ => None,
            })
    })
}

fn preferred_encryption_modes(signature: &Signature) -> EncryptionModePreferences {
    let Some(config) = signature.config() else {
        return EncryptionModePreferences::Missing;
    };
    let mut subpackets =
        config
            .hashed_subpackets
            .iter()
            .filter_map(|subpacket| match &subpacket.data {
                SubpacketData::PreferredEncryptionModes(values) => Some(values),
                _ => None,
            });
    let Some(values) = subpackets.next() else {
        return EncryptionModePreferences::Missing;
    };
    if subpackets.next().is_some() {
        return EncryptionModePreferences::Ambiguous;
    }
    EncryptionModePreferences::Present(
        values
            .iter()
            .copied()
            .map(u8::from)
            .collect::<Vec<_>>()
            .into_boxed_slice(),
    )
}

fn signature_features(signature: &Signature) -> AuthenticatedFeatures {
    signature
        .config()
        .and_then(|config| {
            config
                .hashed_subpackets
                .iter()
                .rev()
                .find_map(|subpacket| match &subpacket.data {
                    SubpacketData::Features(features) => {
                        Some(Vec::<u8>::from(features).into_boxed_slice())
                    }
                    _ => None,
                })
        })
        .map_or(
            AuthenticatedFeatures::Missing,
            AuthenticatedFeatures::Present,
        )
}

pub(super) fn embedded_cross_certified(
    signature: &Signature,
    subkey: &PublicSubkey,
    primary: &PublicKey,
    times: CertificateValidationTimes,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<bool, OpenPgpPolicyError> {
    if !key_signature_verification_acceptable(subkey) {
        return Ok(false);
    }
    let Some(config) = signature.config() else {
        return Ok(false);
    };
    let mut verified = false;
    for embedded in config
        .hashed_subpackets
        .iter()
        .chain(&config.unhashed_subpackets)
        .filter_map(|subpacket| match &subpacket.data {
            SubpacketData::EmbeddedSignature(signature) => Some(signature.as_ref()),
            _ => None,
        })
    {
        if !authentication_signature_acceptable_at(embedded, times)
            || !signature_verification_compatible(embedded, subkey)
            || !signature_matches_signer(embedded, subkey)
        {
            continue;
        }
        budget.charge_public_key_verification()?;
        verified |= signature_ignoring_unhashed_issuer_hints(embedded)
            .is_some_and(|embedded| embedded.verify_primary_key_binding(subkey, primary).is_ok());
    }
    Ok(verified)
}

#[cfg(test)]
pub(in crate::openpgp) fn select_primary_user_id<'a>(
    identities: impl Iterator<Item = (&'a str, &'a [u8], bool, Option<u64>)>,
) -> Option<String> {
    select_primary_user_id_candidate(identities).map(str::to_owned)
}

pub(super) fn select_primary_user_id_candidate<'a, T: Copy>(
    identities: impl Iterator<Item = (T, &'a [u8], bool, Option<u64>)>,
) -> Option<T> {
    let mut fallback = None;
    let mut selected_primary = None;
    for (value, packet_body, primary, created_at) in identities {
        let candidate = (value, packet_body, created_at);
        if fallback
            .as_ref()
            .is_none_or(|current| primary_user_id_candidate_is_newer(&candidate, current))
        {
            fallback = Some(candidate);
        }
        if primary
            && selected_primary
                .as_ref()
                .is_none_or(|current| primary_user_id_candidate_is_newer(&candidate, current))
        {
            selected_primary = Some(candidate);
        }
    }
    selected_primary.or(fallback).map(|(value, _, _)| value)
}

fn primary_user_id_candidate_is_newer<T>(
    candidate: &(T, &[u8], Option<u64>),
    current: &(T, &[u8], Option<u64>),
) -> bool {
    candidate.2 > current.2 || (candidate.2 == current.2 && candidate.1 < current.1)
}

/// Returns whether a signature has expired at `reference_time`.
///
/// Wire values are individually 32-bit, but RFC 9580 defines expiration as
/// their mathematical sum. Compute that instant in 64 bits so a duration that
/// crosses the 2106 timestamp boundary does not wrap into the past.
pub(in crate::openpgp) fn signature_expired_at(
    created: Option<u64>,
    duration: u64,
    reference_time: u64,
) -> bool {
    if duration == 0 {
        return false;
    }
    created
        .unwrap_or(0)
        .saturating_add(duration)
        .le(&reference_time)
}

pub(in crate::openpgp) fn component_expiration<K>(component: &ComponentPolicy<'_, K>) -> Option<u64>
where
    K: KeyDetails,
{
    component
        .key_expiration_seconds
        .map(|duration| u64::from(component.key.created_at().as_secs()) + u64::from(duration))
}

pub(in crate::openpgp) fn can_sign(
    algorithm: PublicKeyAlgorithm,
    flags: Option<&KeyFlags>,
) -> bool {
    signature_algorithm_acceptable(algorithm) && flags.is_none_or(KeyFlags::sign)
}

/// RFC 9580 section 10.1 makes V4 primary keys certification-capable
/// independently of an advertised Key Flags subpacket.
pub(in crate::openpgp) fn can_certify(version: KeyVersion, flags: Option<&KeyFlags>) -> bool {
    version == KeyVersion::V4 || flags.is_none_or(KeyFlags::certify)
}

pub(in crate::openpgp) fn can_encrypt(
    algorithm: PublicKeyAlgorithm,
    flags: Option<&KeyFlags>,
) -> bool {
    encryption_algorithm_acceptable(algorithm)
        && flags.is_none_or(|flags| flags.encrypt_comms() || flags.encrypt_storage())
}

/// Returns whether an algorithm may be selected for new public-key encryption.
///
/// RFC 9580 section 12.6 permits legacy ElGamal decryption with a warning, but
/// prohibits new encryption.  Keep that decision out of parsing and
/// decryption so old packets can still be retained and handled independently.
pub(super) fn encryption_algorithm_acceptable(algorithm: PublicKeyAlgorithm) -> bool {
    algorithm.can_encrypt()
        && !matches!(
            algorithm,
            PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt
        )
}

pub(super) fn signing_component_usable<K>(
    component: &ComponentPolicy<'_, K>,
    reference_time: u64,
    require_cross_certification: bool,
) -> bool
where
    K: KeyDetails,
{
    component.authenticated
        && component.revocation_status.permits_new_data()
        && !component_is_expired(component, reference_time)
        && (!require_cross_certification || component.signing_cross_certified)
        && can_sign(component.key.algorithm(), component.key_flags.as_ref())
}

pub(super) fn encryption_component_usable<K>(
    component: &ComponentPolicy<'_, K>,
    reference_time: u64,
) -> bool
where
    K: KeyDetails,
{
    component.authenticated
        && component.revocation_status.permits_new_data()
        && !component_is_expired(component, reference_time)
        && can_encrypt(component.key.algorithm(), component.key_flags.as_ref())
        && encryption_key_strength_acceptable(component.key)
}

/// Returns whether a component has enough key material for new encryption.
///
/// RFC 9580 section 12.4 prohibits encrypting with RSA keys below 2048 bits.
/// This remains operation-specific so legacy weak keys stay parseable and
/// available to the separately governed decryption path.
fn encryption_key_strength_acceptable(key: &impl KeyDetails) -> bool {
    if !matches!(
        key.algorithm(),
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ) {
        return true;
    }
    let params = key.public_params();
    if !matches!(params, PublicParams::RSA(_)) {
        return false;
    }
    leading_mpi_bits(params).is_some_and(|bits| bits >= MIN_RSA_ENCRYPTION_BITS)
}

pub(super) fn component_is_expired<K>(
    component: &ComponentPolicy<'_, K>,
    reference_time: u64,
) -> bool
where
    K: KeyDetails,
{
    component_expiration(component).is_some_and(|expires| expires <= reference_time)
}
