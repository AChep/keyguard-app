//! Secret/public OpenPGP certificate material conversion.
//!
//! Secret packets stay byte exact and are restored only after every public
//! component has been matched. This layer does not make certificate-policy
//! decisions; callers must explicitly choose how trusted secret variants are
//! reconciled.

use std::{
    collections::{BTreeMap, BTreeSet},
    io::Cursor,
};

use pgp::{
    armor::BlockType,
    composed::{Deserializable, SignedPublicKey, SignedSecretKey},
    packet::{PublicKey, PublicSubkey, SecretKey, SecretSubkey},
    ser::Serialize,
    types::{KeyDetails, KeyVersion, Tag},
};
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        format::fingerprint_hex,
        packet::{
            FixedPacketWriteError, MARKER_TAG, MAX_CERTIFICATE_PACKETS, PADDING_TAG,
            PUBLIC_KEY_TAG, PUBLIC_SUBKEY_TAG, RawPacketError, RawPacketStream, SECRET_KEY_TAG,
            SECRET_SUBKEY_TAG, SIGNATURE_TAG, USER_ATTRIBUTE_TAG, USER_ID_TAG,
            armor::{KeyArmorError, armor_key_packets_bounded},
            parse_fixed_packet_body, write_fixed_packet,
        },
    },
};

use super::{CertificateMergeError, CertificateMutationError};

const MAX_KEY_PACKETS: usize = MAX_CERTIFICATE_PACKETS;
pub(crate) const MAX_MUTATION_CANDIDATE_CERTIFICATES: usize = 64;

/// Transferable public and secret representations of one certificate.
pub(crate) struct KeyMaterial {
    pub(crate) private_key_armored: Vec<u8>,
    pub(crate) public_key_armored: Vec<u8>,
    pub(crate) fingerprint: String,
}

/// A transferable secret key whose public certificate and available secret
/// packets are represented independently.
///
/// RFC 9580 permits a public primary key packet alongside secret subkey
/// packets.  Keeping the primary secret optional models that packet sequence
/// without inventing private material merely to satisfy a composed-key type.
pub(crate) struct ParsedSecretCertificate {
    public: SignedPublicKey,
    primary: Option<SecretKey>,
    subkeys: Vec<SecretSubkey>,
}

impl ParsedSecretCertificate {
    pub(crate) fn public(&self) -> &SignedPublicKey {
        &self.public
    }

    pub(crate) fn primary(&self) -> Option<&SecretKey> {
        self.primary.as_ref()
    }

    pub(crate) fn subkeys(&self) -> &[SecretSubkey] {
        &self.subkeys
    }
}

impl Drop for KeyMaterial {
    fn drop(&mut self) {
        self.private_key_armored.zeroize();
    }
}

/// Failures owned by the shared packet/material pipeline.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum MutationMaterialError {
    #[error("malformed OpenPGP key material")]
    MalformedKey,
    #[error("OpenPGP certificate components do not match")]
    FingerprintMismatch,
    #[error("unsupported OpenPGP key version")]
    UnsupportedKeyVersion,
    #[error("unsupported OpenPGP secret-key layout")]
    UnsupportedTskLayout,
    #[error("OpenPGP mutation resource limit exceeded")]
    ResourceLimit,
    #[error("internal OpenPGP mutation failure")]
    InternalFailure,
    #[error("mutated OpenPGP certificate failed verification")]
    SignatureVerificationFailed,
}

// Keep the dense packet implementation readable while exposing only the
// precise material type to its consumers.
/// Coarse ABI severity of a [`MutationMaterialError`], for modules whose error enums
/// only distinguish resource exhaustion, caller error, and internal failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MaterialErrorSeverity {
    ResourceLimit,
    InvalidArgument,
    Internal,
}

impl MutationMaterialError {
    /// Classifies a failure for agent and message callers that only reuse the
    /// *material* half of this pipeline.
    ///
    /// Those callers never run a mutation operation, so they can only observe
    /// the material variants. The remaining arms are listed explicitly rather
    /// than caught by a wildcard so a new variant has to be classified here.
    pub(crate) fn severity(self) -> MaterialErrorSeverity {
        match self {
            Self::ResourceLimit => MaterialErrorSeverity::ResourceLimit,
            Self::MalformedKey
            | Self::FingerprintMismatch
            | Self::UnsupportedKeyVersion
            | Self::UnsupportedTskLayout => MaterialErrorSeverity::InvalidArgument,
            Self::InternalFailure | Self::SignatureVerificationFailed => {
                MaterialErrorSeverity::Internal
            }
        }
    }
}

impl From<RawPacketError> for MutationMaterialError {
    fn from(error: RawPacketError) -> Self {
        Self::from(CertificateMergeError::from(error))
    }
}

struct SecretPacketOverlay {
    public_body: Vec<u8>,
    secret_body: Zeroizing<Vec<u8>>,
    secret_packet: Zeroizing<Vec<u8>>,
}

/// A primary packet retained for private-certificate serialization.
///
/// GnuPG's dummy packet is deliberately distinct from real secret material:
/// it must be reproduced for interoperability, but must never make the
/// primary key available to signing or mutation code.
enum PrimarySecretPacketOverlay {
    Material(SecretPacketOverlay),
    GnuDummyStub(SecretPacketOverlay),
}

enum SubkeySecretPacketOverlay {
    Material(SecretPacketOverlay),
    GnuDummyStub(SecretPacketOverlay),
}

impl SubkeySecretPacketOverlay {
    fn packet(&self) -> &SecretPacketOverlay {
        match self {
            Self::Material(packet) | Self::GnuDummyStub(packet) => packet,
        }
    }

    fn material(&self) -> Option<&SecretPacketOverlay> {
        match self {
            Self::Material(packet) => Some(packet),
            Self::GnuDummyStub(_) => None,
        }
    }

    fn is_material(&self) -> bool {
        matches!(self, Self::Material(_))
    }
}

impl PrimarySecretPacketOverlay {
    fn packet(&self) -> &SecretPacketOverlay {
        match self {
            Self::Material(packet) | Self::GnuDummyStub(packet) => packet,
        }
    }

    fn material(&self) -> Option<&SecretPacketOverlay> {
        match self {
            Self::Material(packet) => Some(packet),
            Self::GnuDummyStub(_) => None,
        }
    }

    fn is_material(&self) -> bool {
        matches!(self, Self::Material(_))
    }
}

/// Secret packet bodies indexed by the public components they protect.
///
/// Fields remain private so callers can only restore secret material through
/// [`rebuild_secret_certificate`], which verifies every overlay is consumed.
pub(crate) struct SecretCertificateOverlay {
    primary: Option<PrimarySecretPacketOverlay>,
    subkeys: BTreeMap<String, SubkeySecretPacketOverlay>,
    subkey_order: Vec<String>,
}

impl SecretCertificateOverlay {
    pub(crate) fn has_secret_primary(&self) -> bool {
        self.primary
            .as_ref()
            .is_some_and(PrimarySecretPacketOverlay::is_material)
    }

    pub(crate) fn secret_subkey_fingerprints(&self) -> impl Iterator<Item = &str> {
        self.subkey_order.iter().filter_map(|fingerprint| {
            self.subkeys
                .get(fingerprint)
                .is_some_and(SubkeySecretPacketOverlay::is_material)
                .then_some(fingerprint.as_str())
        })
    }

    fn has_secret_capability(&self) -> bool {
        self.has_secret_primary()
            || self
                .subkeys
                .values()
                .any(SubkeySecretPacketOverlay::is_material)
    }
}

#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum SecretOverlayMergeError {
    #[error("OpenPGP secret certificate components do not match")]
    ComponentMismatch,
    #[error("OpenPGP secret certificate contains conflicting secret material")]
    ConflictingSecretMaterial,
}

pub(crate) struct SecretOverlayMergeResult {
    pub(crate) overlay: SecretCertificateOverlay,
    pub(crate) existing_contributed: bool,
    pub(crate) incoming_contributed: bool,
}

/// Unions byte-exact secret packets from two independently validated copies.
///
/// A component present on only one side is retained. Overlapping components
/// must have identical public and secret packet bodies; otherwise the merge
/// fails closed instead of selecting an order-dependent opaque secret value.
/// Real primary material still wins over a GnuPG dummy primary, independent
/// of side order.
pub(crate) fn merge_secret_certificate_overlays(
    existing: Option<SecretCertificateOverlay>,
    incoming: Option<SecretCertificateOverlay>,
) -> Result<Option<SecretOverlayMergeResult>, SecretOverlayMergeError> {
    let (existing, incoming) = match (existing, incoming) {
        (None, None) => return Ok(None),
        (Some(overlay), None) => {
            let existing_contributed = overlay.has_secret_capability();
            return Ok(Some(SecretOverlayMergeResult {
                overlay,
                existing_contributed,
                incoming_contributed: false,
            }));
        }
        (None, Some(overlay)) => {
            let incoming_contributed = overlay.has_secret_capability();
            return Ok(Some(SecretOverlayMergeResult {
                overlay,
                existing_contributed: false,
                incoming_contributed,
            }));
        }
        (Some(existing), Some(incoming)) => (existing, incoming),
    };

    let SecretCertificateOverlay {
        primary: existing_primary,
        mut subkeys,
        mut subkey_order,
    } = existing;
    let SecretCertificateOverlay {
        primary: incoming_primary,
        subkeys: incoming_subkeys,
        subkey_order: incoming_subkey_order,
    } = incoming;
    let (primary, existing_primary_contributed, incoming_primary_contributed) =
        merge_primary_secret_packet_overlays(existing_primary, incoming_primary)?;

    let existing_fingerprints = subkeys
        .iter()
        .filter_map(|(fingerprint, packet)| packet.is_material().then_some(fingerprint.clone()))
        .collect::<BTreeSet<_>>();
    let incoming_material_fingerprints = incoming_subkeys
        .iter()
        .filter_map(|(fingerprint, packet)| packet.is_material().then_some(fingerprint.clone()))
        .collect::<BTreeSet<_>>();
    let mut existing_contributed = existing_primary_contributed
        || existing_fingerprints
            .difference(&incoming_material_fingerprints)
            .next()
            .is_some();
    let mut incoming_contributed = incoming_primary_contributed
        || incoming_material_fingerprints
            .difference(&existing_fingerprints)
            .next()
            .is_some();
    for fingerprint in incoming_subkey_order {
        if !subkey_order.contains(&fingerprint) {
            subkey_order.push(fingerprint);
        }
    }
    for (fingerprint, incoming_subkey) in incoming_subkeys {
        match subkeys.entry(fingerprint) {
            std::collections::btree_map::Entry::Vacant(entry) => {
                entry.insert(incoming_subkey);
            }
            std::collections::btree_map::Entry::Occupied(mut entry) => {
                match (entry.get(), &incoming_subkey) {
                    (
                        SubkeySecretPacketOverlay::Material(existing),
                        SubkeySecretPacketOverlay::GnuDummyStub(incoming),
                    ) => {
                        ensure_secret_packet_public_component(existing, incoming)?;
                        existing_contributed = true;
                    }
                    (
                        SubkeySecretPacketOverlay::GnuDummyStub(existing),
                        SubkeySecretPacketOverlay::Material(incoming),
                    ) => {
                        ensure_secret_packet_public_component(existing, incoming)?;
                        entry.insert(incoming_subkey);
                        incoming_contributed = true;
                    }
                    (existing, incoming) => {
                        ensure_compatible_secret_packets(existing.packet(), incoming.packet())?;
                        let prefer_incoming_framing = incoming.packet().secret_packet.as_slice()
                            < existing.packet().secret_packet.as_slice();
                        if prefer_incoming_framing {
                            entry.insert(incoming_subkey);
                        }
                    }
                }
            }
        }
    }
    subkey_order.sort();

    Ok(Some(SecretOverlayMergeResult {
        overlay: SecretCertificateOverlay {
            primary,
            subkeys,
            subkey_order,
        },
        existing_contributed,
        incoming_contributed,
    }))
}

fn merge_primary_secret_packet_overlays(
    existing: Option<PrimarySecretPacketOverlay>,
    incoming: Option<PrimarySecretPacketOverlay>,
) -> Result<(Option<PrimarySecretPacketOverlay>, bool, bool), SecretOverlayMergeError> {
    match (existing, incoming) {
        (None, None) => Ok((None, false, false)),
        (Some(primary), None) => {
            let existing_contributed = primary.is_material();
            Ok((Some(primary), existing_contributed, false))
        }
        (None, Some(primary)) => {
            let incoming_contributed = primary.is_material();
            Ok((Some(primary), false, incoming_contributed))
        }
        (
            Some(existing @ PrimarySecretPacketOverlay::Material(_)),
            Some(incoming @ PrimarySecretPacketOverlay::GnuDummyStub(_)),
        ) => {
            ensure_secret_packet_public_component(existing.packet(), incoming.packet())?;
            Ok((Some(existing), true, false))
        }
        (
            Some(existing @ PrimarySecretPacketOverlay::GnuDummyStub(_)),
            Some(incoming @ PrimarySecretPacketOverlay::Material(_)),
        ) => {
            ensure_secret_packet_public_component(existing.packet(), incoming.packet())?;
            Ok((Some(incoming), false, true))
        }
        (Some(existing), Some(incoming)) => {
            ensure_compatible_secret_packets(existing.packet(), incoming.packet())?;
            let primary = if incoming.packet().secret_packet.as_slice()
                < existing.packet().secret_packet.as_slice()
            {
                incoming
            } else {
                existing
            };
            Ok((Some(primary), false, false))
        }
    }
}

fn ensure_secret_packet_public_component(
    existing: &SecretPacketOverlay,
    incoming: &SecretPacketOverlay,
) -> Result<(), SecretOverlayMergeError> {
    if existing.public_body == incoming.public_body {
        Ok(())
    } else {
        Err(SecretOverlayMergeError::ComponentMismatch)
    }
}

fn ensure_compatible_secret_packets(
    existing: &SecretPacketOverlay,
    incoming: &SecretPacketOverlay,
) -> Result<(), SecretOverlayMergeError> {
    if existing.public_body != incoming.public_body {
        return Err(SecretOverlayMergeError::ComponentMismatch);
    }
    if existing.secret_body.as_slice() != incoming.secret_body.as_slice() {
        return Err(SecretOverlayMergeError::ConflictingSecretMaterial);
    }
    Ok(())
}

/// Recognizes the exact GnuPG mode-1 private S2K extension, which explicitly
/// stores no secret key material.
///
/// Both legacy usage octets documented by GnuPG are accepted for V4 packets;
/// RFC 9580 forbids usage 255 for V6 packets, so no GNU stub compatibility is
/// extended to V6.
pub(crate) fn is_gnu_dummy_secret_stub(version: KeyVersion, suffix: &[u8]) -> bool {
    classify_gnu_secret_s2k(version, suffix).is_ok_and(|value| value == Some(GnuSecretS2k::Dummy))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum GnuSecretS2k {
    Dummy,
}

/// Classifies GnuPG's private S2K extension before it can be mistaken for
/// ordinary portable secret material.
///
/// Mode 1001 is the only representation currently preserved. Card-diverted
/// mode 1002 and GnuPG-internal mode 1003 require explicit local-state models;
/// accepting them as portable secret material would overstate key capability.
fn classify_gnu_secret_s2k(
    version: KeyVersion,
    suffix: &[u8],
) -> Result<Option<GnuSecretS2k>, MutationMaterialError> {
    let is_private_s2k =
        matches!(suffix.first(), Some(254 | 255)) && suffix.get(2).copied() == Some(101);
    if !is_private_s2k {
        return Ok(None);
    }
    if version != KeyVersion::V4 || suffix.len() < 8 || suffix.get(4..7) != Some(b"GNU".as_slice())
    {
        return Err(MutationMaterialError::UnsupportedTskLayout);
    }
    match (suffix[7], suffix.len()) {
        (1, 8) => Ok(Some(GnuSecretS2k::Dummy)),
        _ => Err(MutationMaterialError::UnsupportedTskLayout),
    }
}

/// Converts one certificate containing secret components to its lossless public
/// projection while retaining byte-exact secret packet bodies for rebuilding.
///
/// Secret material is handled per component. In particular, a filtered TSK may
/// carry a public primary key followed by selected secret subkeys.
pub(crate) fn project_secret_certificate(
    input: &[u8],
) -> Result<(Vec<u8>, SecretCertificateOverlay), MutationMaterialError> {
    let stream = RawPacketStream::parse(input, MAX_KEY_PACKETS)?;
    stream.validate_marker_packets(stream.packets())?;
    if !stream
        .packets()
        .first()
        .is_some_and(|packet| matches!(packet.tag(), SECRET_KEY_TAG | PUBLIC_KEY_TAG))
        || stream
            .packets()
            .iter()
            .skip(1)
            .any(|packet| matches!(packet.tag(), SECRET_KEY_TAG | PUBLIC_KEY_TAG))
    {
        return Err(MutationMaterialError::MalformedKey);
    }

    let mut projected = Vec::new();
    let mut primary = None;
    let mut primary_version = None;
    let mut subkeys = BTreeMap::new();
    let mut subkey_order = Vec::new();
    let mut component_fingerprints = BTreeSet::new();
    for (index, span) in stream.packets().iter().enumerate() {
        match span.tag() {
            SECRET_KEY_TAG if index == 0 => {
                let body = stream.body(span);
                let key = parse_secret_key_body(body.as_slice())?;
                let version = projected_key_version(key.version())?;
                primary_version = Some(version);
                let public_body = serialize_packet_body(key.public_key())?;
                if !body.starts_with(&public_body)
                    || body.len() == public_body.len()
                    || !component_fingerprints.insert(fingerprint_hex(key.public_key()))
                {
                    return Err(MutationMaterialError::MalformedKey);
                }
                write_fixed_packet(PUBLIC_KEY_TAG, &public_body, &mut projected)?;
                let is_gnu_dummy = classify_gnu_secret_s2k(version, &body[public_body.len()..])?
                    == Some(GnuSecretS2k::Dummy);
                let packet = SecretPacketOverlay {
                    public_body,
                    secret_body: body,
                    secret_packet: Zeroizing::new(stream.raw(span).to_vec()),
                };
                primary = Some(if is_gnu_dummy {
                    PrimarySecretPacketOverlay::GnuDummyStub(packet)
                } else {
                    PrimarySecretPacketOverlay::Material(packet)
                });
            }
            PUBLIC_KEY_TAG if index == 0 => {
                let body = stream.body(span);
                let key = parse_public_key_body(body.as_slice())?;
                primary_version = Some(projected_key_version(key.version())?);
                if !component_fingerprints.insert(fingerprint_hex(&key)) {
                    return Err(MutationMaterialError::FingerprintMismatch);
                }
                projected.extend_from_slice(stream.raw(span));
            }
            SECRET_SUBKEY_TAG if index > 0 => {
                let body = stream.body(span);
                let key = parse_secret_subkey_body(body.as_slice())?;
                let version = projected_key_version(key.version())?;
                // RFC 9580 §§10.1.1 and 10.1.3 prohibit mixing V4 and V6
                // key packets within one certificate. Reject before writing
                // a public projection that could later be canonicalized.
                if primary_version != Some(version) {
                    return Err(MutationMaterialError::MalformedKey);
                }
                let fingerprint = fingerprint_hex(&key);
                let public_body = serialize_packet_body(key.public_key())?;
                if !body.starts_with(&public_body)
                    || body.len() == public_body.len()
                    || !component_fingerprints.insert(fingerprint.clone())
                    || subkeys.contains_key(&fingerprint)
                {
                    return Err(MutationMaterialError::FingerprintMismatch);
                }
                write_fixed_packet(PUBLIC_SUBKEY_TAG, &public_body, &mut projected)?;
                let is_gnu_dummy = classify_gnu_secret_s2k(version, &body[public_body.len()..])?
                    == Some(GnuSecretS2k::Dummy);
                if !is_gnu_dummy {
                    subkey_order.push(fingerprint.clone());
                    subkeys.insert(
                        fingerprint,
                        SubkeySecretPacketOverlay::Material(SecretPacketOverlay {
                            public_body,
                            secret_body: body,
                            secret_packet: Zeroizing::new(stream.raw(span).to_vec()),
                        }),
                    );
                } else {
                    subkey_order.push(fingerprint.clone());
                    subkeys.insert(
                        fingerprint,
                        SubkeySecretPacketOverlay::GnuDummyStub(SecretPacketOverlay {
                            public_body,
                            secret_body: body,
                            secret_packet: Zeroizing::new(stream.raw(span).to_vec()),
                        }),
                    );
                }
            }
            PUBLIC_SUBKEY_TAG if index > 0 => {
                let body = stream.body(span);
                let key = parse_public_subkey_body(body.as_slice())?;
                let version = projected_key_version(key.version())?;
                if primary_version != Some(version) {
                    return Err(MutationMaterialError::MalformedKey);
                }
                if !component_fingerprints.insert(fingerprint_hex(&key)) {
                    return Err(MutationMaterialError::FingerprintMismatch);
                }
                projected.extend_from_slice(stream.raw(span));
            }
            SIGNATURE_TAG
            | MARKER_TAG
            | USER_ID_TAG
            | USER_ATTRIBUTE_TAG
            | PADDING_TAG
            | 40..=63
                if index > 0 =>
            {
                projected.extend_from_slice(stream.raw(span));
            }
            _ => return Err(MutationMaterialError::MalformedKey),
        }
    }
    if primary.is_none() && subkeys.is_empty() {
        return Err(MutationMaterialError::MalformedKey);
    }

    Ok((
        projected,
        SecretCertificateOverlay {
            primary,
            subkeys,
            subkey_order,
        },
    ))
}

fn projected_key_version(version: KeyVersion) -> Result<KeyVersion, MutationMaterialError> {
    match version {
        KeyVersion::V4 | KeyVersion::V6 => Ok(version),
        KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V5 => {
            Err(MutationMaterialError::UnsupportedKeyVersion)
        }
        KeyVersion::Other(_) => Err(MutationMaterialError::MalformedKey),
    }
}

/// Restores byte-exact secret packets onto a mutated public certificate.
pub(crate) fn rebuild_secret_certificate(
    public: &[u8],
    secret: &SecretCertificateOverlay,
) -> Result<Zeroizing<Vec<u8>>, MutationMaterialError> {
    rebuild_secret_certificate_with_mode(public, secret, SecretRebuildMode::Local)?
        .ok_or(MutationMaterialError::InternalFailure)
}

/// Restores only standard secret packets whose public components survived the
/// ordinary transferable-public export boundary.
///
/// Local-only components and GnuPG dummy primary packets remain available to
/// [`rebuild_secret_certificate`], but are deliberately excluded here. A
/// `None` result means that no secret capability survived strict export.
pub(crate) fn rebuild_transferable_secret_certificate(
    public: &[u8],
    secret: &SecretCertificateOverlay,
) -> Result<Option<Zeroizing<Vec<u8>>>, MutationMaterialError> {
    rebuild_secret_certificate_with_mode(public, secret, SecretRebuildMode::Transferable)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SecretRebuildMode {
    Local,
    Transferable,
}

fn rebuild_secret_certificate_with_mode(
    public: &[u8],
    secret: &SecretCertificateOverlay,
    mode: SecretRebuildMode,
) -> Result<Option<Zeroizing<Vec<u8>>>, MutationMaterialError> {
    let stream = RawPacketStream::parse(public, MAX_KEY_PACKETS)?;
    let range = stream
        .first_public_certificate()
        .ok_or(MutationMaterialError::MalformedKey)?;
    if range.start != 0 || range.end != stream.packets().len() {
        return Err(MutationMaterialError::MalformedKey);
    }

    let mut segments = Vec::new();
    segments
        .try_reserve_exact(stream.packets().len())
        .map_err(|_| MutationMaterialError::ResourceLimit)?;
    let mut output_len = 0_usize;
    let mut primary_seen = false;
    let mut primary_consumed = false;
    let mut consumed_subkeys = BTreeSet::new();
    let mut secret_packet_written = false;
    for span in stream.packets() {
        match span.tag() {
            PUBLIC_KEY_TAG if !primary_seen => {
                let body = stream.body(span);
                let primary = secret.primary.as_ref().and_then(|primary| match mode {
                    SecretRebuildMode::Local => Some(primary.packet()),
                    SecretRebuildMode::Transferable => primary.material(),
                });
                if let Some(primary) = primary {
                    if body.as_slice() != primary.public_body {
                        return Err(MutationMaterialError::FingerprintMismatch);
                    }
                    let packet = primary.secret_packet.as_slice();
                    output_len = output_len
                        .checked_add(packet.len())
                        .ok_or(MutationMaterialError::ResourceLimit)?;
                    segments.push(packet);
                    primary_consumed = true;
                    secret_packet_written = true;
                } else {
                    let packet = stream.raw(span);
                    output_len = output_len
                        .checked_add(packet.len())
                        .ok_or(MutationMaterialError::ResourceLimit)?;
                    segments.push(packet);
                }
                primary_seen = true;
            }
            PUBLIC_SUBKEY_TAG => {
                let body = stream.body(span);
                let subkey = parse_public_subkey_body(body.as_slice())?;
                let fingerprint = fingerprint_hex(&subkey);
                let overlay = secret
                    .subkeys
                    .get(&fingerprint)
                    .and_then(|overlay| match mode {
                        SecretRebuildMode::Local => Some(overlay.packet()),
                        SecretRebuildMode::Transferable => overlay.material(),
                    });
                if let Some(overlay) = overlay {
                    if body.as_slice() != overlay.public_body
                        || !consumed_subkeys.insert(fingerprint)
                    {
                        return Err(MutationMaterialError::FingerprintMismatch);
                    }
                    let packet = overlay.secret_packet.as_slice();
                    output_len = output_len
                        .checked_add(packet.len())
                        .ok_or(MutationMaterialError::ResourceLimit)?;
                    segments.push(packet);
                    secret_packet_written = true;
                } else {
                    let packet = stream.raw(span);
                    output_len = output_len
                        .checked_add(packet.len())
                        .ok_or(MutationMaterialError::ResourceLimit)?;
                    segments.push(packet);
                }
            }
            _ => {
                let packet = stream.raw(span);
                output_len = output_len
                    .checked_add(packet.len())
                    .ok_or(MutationMaterialError::ResourceLimit)?;
                segments.push(packet);
            }
        }
    }
    let overlays_consumed = secret.primary.is_some() == primary_consumed
        && consumed_subkeys.len() == secret.subkeys.len();
    if !primary_seen || (mode == SecretRebuildMode::Local && !overlays_consumed) {
        return Err(MutationMaterialError::FingerprintMismatch);
    }
    if mode == SecretRebuildMode::Transferable && !secret_packet_written {
        return Ok(None);
    }
    let mut output = Zeroizing::new(Vec::new());
    output
        .try_reserve_exact(output_len)
        .map_err(|_| MutationMaterialError::ResourceLimit)?;
    let allocation = output.as_ptr();
    let capacity = output.capacity();
    for packet in segments {
        if packet.len() > capacity.saturating_sub(output.len()) {
            return Err(MutationMaterialError::InternalFailure);
        }
        output.extend_from_slice(packet);
    }
    if output.len() != output_len || output.capacity() != capacity || output.as_ptr() != allocation
    {
        return Err(MutationMaterialError::InternalFailure);
    }
    Ok(Some(output))
}

pub(crate) fn armor_key_packets(
    data: &[u8],
    block_type: BlockType,
) -> Result<Vec<u8>, MutationMaterialError> {
    armor_key_packets_zeroizing(data, block_type).map(|output| output.to_vec())
}

pub(crate) fn armor_key_packets_zeroizing(
    data: &[u8],
    block_type: BlockType,
) -> Result<Zeroizing<Vec<u8>>, MutationMaterialError> {
    // Mutation callers may hand this function key packets that were never
    // semantically parsed. Validate every key body before armoring so a
    // malformed key packet keeps failing here rather than being emitted.
    let stream = RawPacketStream::parse(data, MAX_KEY_PACKETS)?;
    for packet in stream.packets() {
        match packet.tag() {
            SECRET_KEY_TAG => {
                parse_secret_key_body(stream.body(packet).as_slice())?;
            }
            SECRET_SUBKEY_TAG => {
                parse_secret_subkey_body(stream.body(packet).as_slice())?;
            }
            PUBLIC_KEY_TAG => {
                parse_public_key_body(stream.body(packet).as_slice())?;
            }
            PUBLIC_SUBKEY_TAG => {
                parse_public_subkey_body(stream.body(packet).as_slice())?;
            }
            _ => {}
        }
    }
    armor_key_packets_bounded(
        data,
        block_type,
        MAX_KEY_PACKETS,
        MAX_CONTROL_ENVELOPE_BYTES,
    )
    .map_err(|error| match error {
        KeyArmorError::Malformed => MutationMaterialError::MalformedKey,
        KeyArmorError::ResourceLimit => MutationMaterialError::ResourceLimit,
        KeyArmorError::UnsupportedVersion => MutationMaterialError::UnsupportedKeyVersion,
        KeyArmorError::Internal => MutationMaterialError::InternalFailure,
    })
}

pub(crate) fn parse_single_secret(input: &[u8]) -> Result<SignedSecretKey, MutationMaterialError> {
    let packets = RawPacketStream::parse(input, MAX_KEY_PACKETS)?;
    let semantic = packets.semantic_bytes();
    let (iterator, _) = SignedSecretKey::from_reader_many(Cursor::new(semantic.as_slice()))
        .map_err(|_| MutationMaterialError::MalformedKey)?;
    let values = iterator
        .take(2)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| MutationMaterialError::MalformedKey)?;
    if values.len() != 1 {
        return Err(MutationMaterialError::MalformedKey);
    }
    values
        .into_iter()
        .next()
        .ok_or(MutationMaterialError::MalformedKey)
}

/// Parses one transferable secret key while preserving a full public policy
/// view and retaining only the secret packets that contain real key material.
pub(crate) fn parse_secret_certificate(
    input: &[u8],
) -> Result<ParsedSecretCertificate, MutationMaterialError> {
    let (projection, overlay) = project_secret_certificate(input)?;
    let public = parse_single_public_certificate(&projection)?;
    let primary = overlay
        .primary
        .as_ref()
        .and_then(PrimarySecretPacketOverlay::material)
        .map(parse_primary_overlay)
        .transpose()?;
    let mut subkeys = Vec::new();
    subkeys
        .try_reserve_exact(overlay.subkey_order.len())
        .map_err(|_| MutationMaterialError::ResourceLimit)?;
    for fingerprint in &overlay.subkey_order {
        let packet = overlay
            .subkeys
            .get(fingerprint)
            .ok_or(MutationMaterialError::InternalFailure)?;
        if let Some(packet) = packet.material() {
            subkeys.push(parse_subkey_overlay(packet)?);
        }
    }
    if primary.is_none() && subkeys.is_empty() {
        return Err(MutationMaterialError::MalformedKey);
    }
    Ok(ParsedSecretCertificate {
        public,
        primary,
        subkeys,
    })
}

fn parse_primary_overlay(
    overlay: &SecretPacketOverlay,
) -> Result<SecretKey, MutationMaterialError> {
    let stream = RawPacketStream::parse(overlay.secret_packet.as_slice(), 1)?;
    let [span] = stream.packets() else {
        return Err(MutationMaterialError::MalformedKey);
    };
    if span.tag() != SECRET_KEY_TAG {
        return Err(MutationMaterialError::MalformedKey);
    }
    let body = stream.body(span);
    let key = parse_secret_key_body(body.as_slice())?;
    (serialize_packet_body(key.public_key())? == overlay.public_body)
        .then_some(key)
        .ok_or(MutationMaterialError::FingerprintMismatch)
}

fn parse_subkey_overlay(
    overlay: &SecretPacketOverlay,
) -> Result<SecretSubkey, MutationMaterialError> {
    let stream = RawPacketStream::parse(overlay.secret_packet.as_slice(), 1)?;
    let [span] = stream.packets() else {
        return Err(MutationMaterialError::MalformedKey);
    };
    if span.tag() != SECRET_SUBKEY_TAG {
        return Err(MutationMaterialError::MalformedKey);
    }
    let body = stream.body(span);
    let key = parse_secret_subkey_body(body.as_slice())?;
    (serialize_packet_body(key.public_key())? == overlay.public_body)
        .then_some(key)
        .ok_or(MutationMaterialError::FingerprintMismatch)
}

fn parse_single_public_certificate(input: &[u8]) -> Result<SignedPublicKey, MutationMaterialError> {
    let error = MutationMaterialError::MalformedKey;
    let packets = RawPacketStream::parse(input, MAX_KEY_PACKETS)?;
    let semantic = packets.semantic_bytes();
    let (iterator, _) =
        SignedPublicKey::from_reader_many(Cursor::new(semantic.as_slice())).map_err(|_| error)?;
    let values = iterator
        .take(2)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| error)?;
    if values.len() != 1 {
        return Err(error);
    }
    values.into_iter().next().ok_or(error)
}

/// Parses one transferable public certificate document.
///
/// Production code reads certificates through the packet set; this remains for
/// tests that need the composed view of an armored document directly.
#[cfg(test)]
pub(crate) fn parse_single_public(input: &[u8]) -> Result<SignedPublicKey, MutationMaterialError> {
    parse_single_public_certificate(input)
}

/// Parses the advisory certificate set used to resolve designated revokers.
///
/// Callers deliberately ignore malformed vault-local candidates, but resource
/// exhaustion is kept distinct and must remain fatal.
fn parse_public_candidates(input: &[u8]) -> Result<Vec<SignedPublicKey>, MutationMaterialError> {
    let packets = RawPacketStream::parse(input, MAX_KEY_PACKETS)?;
    let semantic = packets.semantic_bytes();
    let (iterator, _) = SignedPublicKey::from_reader_many(Cursor::new(semantic.as_slice()))
        .map_err(|_| MutationMaterialError::MalformedKey)?;
    let values = iterator
        .take(MAX_MUTATION_CANDIDATE_CERTIFICATES + 1)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| MutationMaterialError::MalformedKey)?;
    if values.len() > MAX_MUTATION_CANDIDATE_CERTIFICATES {
        Err(MutationMaterialError::ResourceLimit)
    } else {
        Ok(values)
    }
}

pub(crate) fn parse_mutation_candidates(
    documents: &[Vec<u8>],
) -> Result<Vec<SignedPublicKey>, MutationMaterialError> {
    if documents.len() > MAX_MUTATION_CANDIDATE_CERTIFICATES {
        return Err(MutationMaterialError::ResourceLimit);
    }
    let mut certificates = Vec::new();
    for document in documents {
        let parsed = match parse_public_candidates(document) {
            Ok(parsed) => parsed,
            // Candidate material is advisory and may include unrelated or
            // malformed vault entries. It must not make the target unusable.
            Err(
                MutationMaterialError::MalformedKey | MutationMaterialError::UnsupportedKeyVersion,
            ) => continue,
            Err(error) => return Err(error),
        };
        if certificates.len().saturating_add(parsed.len()) > MAX_MUTATION_CANDIDATE_CERTIFICATES {
            return Err(MutationMaterialError::ResourceLimit);
        }
        certificates.extend(parsed);
    }
    Ok(certificates)
}

fn parse_secret_key_body(body: &[u8]) -> Result<SecretKey, MutationMaterialError> {
    parse_fixed_packet_body(Tag::SecretKey, body, |header, reader| {
        SecretKey::try_from_reader(header, reader)
    })
    .map_err(map_key_body_error)
}

fn parse_secret_subkey_body(body: &[u8]) -> Result<SecretSubkey, MutationMaterialError> {
    parse_fixed_packet_body(Tag::SecretSubkey, body, |header, reader| {
        SecretSubkey::try_from_reader(header, reader)
    })
    .map_err(map_key_body_error)
}

fn parse_public_key_body(body: &[u8]) -> Result<PublicKey, MutationMaterialError> {
    parse_fixed_packet_body(Tag::PublicKey, body, |header, reader| {
        PublicKey::try_from_reader(header, reader)
    })
    .map_err(map_key_body_error)
}

fn parse_public_subkey_body(body: &[u8]) -> Result<PublicSubkey, MutationMaterialError> {
    parse_fixed_packet_body(Tag::PublicSubkey, body, |header, reader| {
        PublicSubkey::try_from_reader(header, reader)
    })
    .map_err(map_key_body_error)
}

const fn map_key_body_error(error: RawPacketError) -> MutationMaterialError {
    match error {
        RawPacketError::Malformed => MutationMaterialError::MalformedKey,
        RawPacketError::ResourceLimit => MutationMaterialError::ResourceLimit,
    }
}

pub(crate) fn serialize_packet_body(
    value: &impl Serialize,
) -> Result<Vec<u8>, MutationMaterialError> {
    let mut output = Vec::with_capacity(value.write_len());
    value
        .to_writer(&mut output)
        .map_err(|_| MutationMaterialError::InternalFailure)?;
    Ok(output)
}

impl From<CertificateMergeError> for MutationMaterialError {
    fn from(error: CertificateMergeError) -> Self {
        match error {
            CertificateMergeError::Malformed => Self::MalformedKey,
            CertificateMergeError::UnsupportedKeyVersion => Self::UnsupportedKeyVersion,
            CertificateMergeError::ComponentCollision => Self::FingerprintMismatch,
            CertificateMergeError::ResourceLimit => Self::ResourceLimit,
            CertificateMergeError::Internal => Self::InternalFailure,
        }
    }
}

impl From<FixedPacketWriteError> for MutationMaterialError {
    fn from(error: FixedPacketWriteError) -> Self {
        match error {
            FixedPacketWriteError::ResourceLimit => Self::ResourceLimit,
            FixedPacketWriteError::Internal => Self::InternalFailure,
        }
    }
}

impl From<CertificateMutationError> for MutationMaterialError {
    fn from(error: CertificateMutationError) -> Self {
        match error {
            CertificateMutationError::Malformed => Self::MalformedKey,
            // A target the policy view selected but the packet set does not
            // hold means the two disagree about the certificate; that is a
            // verification failure, never something to work around.
            CertificateMutationError::TargetNotFound => Self::SignatureVerificationFailed,
            CertificateMutationError::ResourceLimit => Self::ResourceLimit,
            CertificateMutationError::Internal => Self::InternalFailure,
        }
    }
}

/// Builds a transferable secret key whose primary secret packet is replaced by
/// the public fixture's primary packet. Shared by module tests that need a
/// public/secret packet mismatch fixture.
#[cfg(test)]
pub(crate) fn filtered_tsk_fixture() -> Vec<u8> {
    const PUBLIC_KEY: &[u8] = include_bytes!("../../../tests/fixtures/openpgp/cv25519-public.asc");
    const SECRET_KEY: &[u8] = include_bytes!("../../../tests/fixtures/openpgp/cv25519-secret.asc");
    let public = RawPacketStream::parse(PUBLIC_KEY, MAX_KEY_PACKETS).expect("parse public fixture");
    let public_primary = public
        .packets()
        .first()
        .filter(|packet| packet.tag() == 6)
        .expect("public fixture starts with its primary key");
    let secret = RawPacketStream::parse(SECRET_KEY, MAX_KEY_PACKETS).expect("parse secret fixture");
    assert!(secret.packets().iter().any(|packet| packet.tag() == 7));

    let mut filtered = Vec::new();
    for (index, packet) in secret.packets().iter().enumerate() {
        if index == 0 {
            assert_eq!(packet.tag(), 5);
            filtered.extend_from_slice(public.raw(public_primary));
        } else {
            filtered.extend_from_slice(secret.raw(packet));
        }
    }
    filtered
}

#[cfg(test)]
mod tests;
