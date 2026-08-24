//! Deterministic, packet-preserving OpenPGP public-certificate merging.
//!
//! Certificate storage and certificate policy are deliberately separate. This
//! module retains packet evidence without deciding whether that
//! evidence is valid or trusted. Signature placement normally follows packet
//! position plus issuer-independent signature typing. The narrow exception is
//! bounded cryptographic repair of a self-issued component signature found in
//! a syntactically impossible position; repair requires one exact target.
//! [`crate::openpgp::policy`] remains the certificate-policy verifier under the
//! single policy budget. The ordinary-export confidentiality boundary is the
//! narrow exception: it omits components without an exportable authenticated
//! self-binding and suppresses every signature carrying a hashed sensitive
//! Revocation Key to avoid revealing designated revokers.
//!
//! [`PublicCertificatePacketSet`] is the pipeline's interchange type and this
//! module owns its two ends: [`parse_public_certificate_packet_sets_with_budget`] turns
//! raw framing into packet sets, and [`PublicCertificatePacketSet::finalize`]
//! is the only output boundary that serializes retained and transferable
//! canonical bytes and derives the composed [`SignedPublicKey`] semantic view
//! into a [`CanonicalCertificate`]. Policy evaluation happens afterwards, on
//! the retained view in the [`CanonicalCertificate`].

use std::{
    borrow::Cow,
    collections::{BTreeMap, BTreeSet},
    io::Cursor,
    ops::Range,
    sync::Arc,
};

#[cfg(test)]
use pgp::packet::Subpacket;
use pgp::{
    composed::{Deserializable, SignedPublicKey},
    packet::{
        PacketHeader, PublicKey, PublicSubkey, Signature, SignatureType, SignatureVersion,
        SignatureVersionSpecific, SubpacketData,
    },
    ser::Serialize,
    types::{KeyDetails, KeyId, KeyVersion, Tag},
};
use thiserror::Error;

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        crypto::verification::{
            cryptographic_signature_material_cmp, key_signature_verification_acceptable,
            signature_algorithm_acceptable, signature_config_is_non_exportable,
            signature_creation_time, signature_ignoring_unhashed_issuer_hints,
            signature_matches_signer, signature_verification_compatible,
            signature_version_matches_signer,
        },
        format::hex_upper,
        packet::{
            MARKER_TAG, MAX_CERTIFICATE_PACKETS, PADDING_TAG, PUBLIC_KEY_TAG, PUBLIC_SUBKEY_TAG,
            RawPacketError, RawPacketSpan, RawPacketStream, SECRET_KEY_TAG, SIGNATURE_TAG,
            TRUST_TAG, USER_ATTRIBUTE_TAG, USER_ID_TAG, parse_fixed_packet_body,
        },
    },
};

use super::PublicComponent;

mod canonicalization;
mod export;
mod parsing;

pub(crate) use canonicalization::{
    canonicalize_public_certificate_material, merge_public_certificate_material_documents,
    merge_public_certificate_packet_sets, normalize_expected_fingerprint,
};
#[cfg(test)]
pub(crate) use export::export_public_certificate_preserving_framing;
pub(crate) use export::{local_public_certificate_preserving_framing, raw_packet_is_exportable};
pub(crate) use parsing::{
    parse_public_certificate_packet_sets_with_budget, parse_single_certificate_packet_set,
};

#[cfg(test)]
pub(crate) use parsing::parse_public_certificate_packet_sets;

#[cfg(test)]
pub(crate) use canonicalization::{
    canonicalize_public_certificate, merge_public_certificate_documents,
};

const MAX_MERGE_PACKETS: usize = MAX_CERTIFICATE_PACKETS;
const MAX_PACKET_BODY_BYTES: usize = 4 * 1024 * 1024;
const MAX_COMPONENTS: usize = 64;
const MAX_IDENTITIES: usize = 256;
const MAX_SIGNATURES_PER_OBJECT: usize = 256;
const MAX_SIGNATURES: usize = 4 * 1024;
const MAX_SIGNATURE_REHOMING_VERIFICATIONS: usize = 4 * 1024;
// Preserve a complete independent allowance after one certificate reaches its
// local cap, but never allow a keyring/request to multiply that work by the
// maximum certificate count.
const MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST: usize = 8 * 1024;
const MAX_EXPORT_CLASSIFICATION_VERIFICATIONS: usize = 4 * 1024;
// As with signature rehoming, permit one additional certificate a complete
// independent allowance without multiplying work by a keyring's size.
const MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST: usize = 8 * 1024;
const MAX_SENSITIVE_REVOKERS_PER_EXPORT: usize = 32;
const MAX_REVOCATION_EXPORT_VERIFICATIONS: usize = 4 * 1024;

impl From<RawPacketError> for CertificateMergeError {
    fn from(error: RawPacketError) -> Self {
        match error {
            RawPacketError::Malformed => Self::Malformed,
            RawPacketError::ResourceLimit => Self::ResourceLimit,
        }
    }
}

/// Stable failure classification for raw public-certificate merging.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum CertificateMergeError {
    /// The packet stream is not a supported transferable public certificate.
    #[error("malformed OpenPGP public certificate")]
    Malformed,
    /// A v2 or v3 primary key or subkey was encountered.
    #[error("unsupported OpenPGP key version")]
    UnsupportedKeyVersion,
    /// Equal component identities carried conflicting key packet bodies.
    #[error("conflicting OpenPGP certificate component")]
    ComponentCollision,
    /// The merged certificate exceeded an explicit resource bound.
    #[error("OpenPGP certificate merge resource limit exceeded")]
    ResourceLimit,
    /// Canonical packet serialization failed unexpectedly.
    #[error("OpenPGP certificate merge failed")]
    Internal,
}

/// Every transferable public certificate found in one document, plus counts
/// of independently unsupported or malformed entries skipped during tolerant
/// keyring import.
#[derive(Default)]
pub(crate) struct ParsedCertificateDocument {
    pub(crate) certificates: Vec<PublicCertificatePacketSet>,
    /// Source packet-index range of each retained entry of `certificates`.
    ///
    /// Callers that must republish the *original* framing (the public-key
    /// parse DTO re-armors exactly what it was given) index the same
    /// [`RawPacketStream`] with these ranges instead of rescanning it.
    pub(crate) spans: Vec<Range<usize>>,
    /// Certificates skipped for carrying a v2/v3 primary key or subkey.
    pub(crate) skipped_unsupported: usize,
    /// Malformed certificate entries skipped while later keyring entries were
    /// still recoverable.
    pub(crate) skipped_malformed: usize,
}

/// One certificate's retained and transferable canonical serializations plus
/// everything derived from the retained evidence.
///
/// Producing this is the *only* place a merged certificate is serialized and
/// the only place the composed rPGP view is parsed, so every downstream stage
/// consumes the same evidence without re-scanning bytes.
pub(crate) struct CanonicalCertificate {
    /// Whether the retained certificate has any ordinary transferable form.
    pub(crate) transferable: bool,
    /// Ordinary transferable canonical packet bytes.
    pub(crate) bytes: Vec<u8>,
    /// Canonical public projection used by local import/mutation state.
    ///
    /// This keeps structurally unusable components so local operations remain
    /// lossless, but still withholds certifications explicitly marked local
    /// and sensitive designated-revoker declarations. It is not an ordinary
    /// transferable export; [`Self::bytes`] is that stricter view.
    pub(crate) local_public_bytes: Vec<u8>,
    /// Canonical packet bytes retaining local-only evidence.
    ///
    /// These bytes are used to reconstruct locally stored private material,
    /// but are never returned as an ordinary public export.
    pub(crate) retained_bytes: Vec<u8>,
    /// Upper-case hex primary fingerprint.
    pub(crate) fingerprint: String,
    /// Composed semantic view over [`Self::retained_bytes`].
    pub(crate) semantic: SignedPublicKey,
    /// Primary key first, then every subkey in first-seen document order.
    ///
    /// Derived from the packet set rather than [`Self::semantic`]: the composed
    /// parser omits subkeys whose binding signature it rejects, and the
    /// component index must stay complete.
    pub(crate) components: Vec<PublicComponent>,
    /// Retained signature packet count, for aggregate request budgets.
    pub(crate) signature_count: usize,
    /// Identity packets in canonical order, as raw `(tag, body)` pairs.
    ///
    /// The composed view is not a faithful source for these bodies: rPGP
    /// re-serializes a User Attribute's image header in its own normal form,
    /// so a certificate carrying a non-standard header would never match its
    /// own stored packet. Mutation therefore addresses identities through
    /// these raw bodies, which are exactly what [`Self::retained_bytes`]
    /// contains.
    pub(crate) identities: Vec<CanonicalIdentityPacket>,
    /// Canonical identities of packets withheld from ordinary export.
    ///
    /// Signature identities are unhashed-normalized. The original-framing
    /// export path consults this set so component and sensitive-declaration
    /// verification performed during canonical serialization is not repeated.
    withheld_packet_keys: BTreeSet<CanonicalPacket>,
    /// Packet identities withheld from [`Self::local_public_bytes`].
    local_public_withheld_packet_keys: BTreeSet<CanonicalPacket>,
}

/// One raw identity packet (User ID or User Attribute) of a certificate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CanonicalIdentityPacket {
    pub(crate) tag: u8,
    pub(crate) body: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct CanonicalPacket {
    tag: u8,
    body: Vec<u8>,
}

#[derive(Clone, Copy)]
enum SerializationPurpose {
    Retained,
    LocalPublic,
    Transferable,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct SensitiveRevokerId {
    algorithm: u8,
    fingerprint: Vec<u8>,
}

#[derive(Default)]
struct TransferableComponents {
    certificate: bool,
    identities: BTreeSet<CanonicalPacket>,
    subkeys: BTreeSet<FingerprintKey>,
}

/// Exact identity packet body used by certification verification.
///
/// Parsing and reserializing a User Attribute may normalize its image header,
/// so exportability verification hashes the retained body byte-for-byte.
struct RawIdentityBody<'a>(&'a [u8]);

impl Serialize for RawIdentityBody<'_> {
    fn to_writer<W: std::io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

/// Structural owner of a signature packet inside a transferable certificate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum CertificateSignatureOwner {
    /// The primary key itself: Direct Key signatures and their revocations,
    /// plus key revocations.
    Direct,
    /// One identity component, addressed by its raw packet body.
    Identity { tag: u8, body: Vec<u8> },
    /// One subkey, addressed by its raw fingerprint bytes.
    Subkey { fingerprint: Vec<u8> },
}

/// One packet-level addition to a transferable public certificate.
///
/// Additions are the only way a mutation introduces new evidence. Applying the
/// same list to the certificate and to an otherwise empty shell yields the
/// mutated certificate and its minimal distributable fragment from one
/// description, so the two can never describe different statements.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum CertificateAddition {
    /// Attaches a signature packet to an existing component.
    Signature {
        owner: CertificateSignatureOwner,
        body: Vec<u8>,
    },
    /// Adds a previously absent identity component with its own signatures.
    Identity {
        tag: u8,
        body: Vec<u8>,
        signature_bodies: Vec<Vec<u8>>,
    },
}

/// Why an in-place packet mutation could not be applied.
///
/// Every variant discards the whole mutation: a target that cannot be located
/// exactly once must never be approximated.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum CertificateMutationError {
    #[error("malformed OpenPGP certificate mutation")]
    Malformed,
    #[error("OpenPGP certificate mutation target was not found")]
    TargetNotFound,
    #[error("OpenPGP certificate mutation exceeded a resource bound")]
    ResourceLimit,
    #[error("internal OpenPGP certificate mutation failure")]
    Internal,
}

impl From<CertificateMergeError> for CertificateMutationError {
    fn from(error: CertificateMergeError) -> Self {
        match error {
            CertificateMergeError::Malformed
            | CertificateMergeError::UnsupportedKeyVersion
            | CertificateMergeError::ComponentCollision => Self::Malformed,
            CertificateMergeError::ResourceLimit => Self::ResourceLimit,
            CertificateMergeError::Internal => Self::Internal,
        }
    }
}

impl CanonicalPacket {
    fn from_span(stream: &RawPacketStream, span: &RawPacketSpan) -> Self {
        Self {
            tag: span.tag(),
            // Public-certificate packet bodies are not secret, so a single
            // plain allocation avoids the zeroized intermediate copy.
            body: stream.body_to_vec(span),
        }
    }

    fn write_to(&self, output: &mut Vec<u8>) -> Result<(), CertificateMergeError> {
        let length =
            u32::try_from(self.body.len()).map_err(|_| CertificateMergeError::ResourceLimit)?;
        let header = PacketHeader::new_fixed(Tag::from(self.tag), length);
        let required = header
            .write_len()
            .checked_add(self.body.len())
            .and_then(|packet_len| output.len().checked_add(packet_len))
            .filter(|total| *total <= MAX_CONTROL_ENVELOPE_BYTES)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        output.reserve(required - output.len());
        header
            .to_writer(output)
            .map_err(|_| CertificateMergeError::Internal)?;
        output.extend_from_slice(&self.body);
        Ok(())
    }
}

/// Deduplicated packets attached to one certificate component, in first-seen
/// insertion order.
///
/// The map deduplicates by the unhashed-normalized packet identity; `order`
/// records the insertion sequence of those identities. Serialization applies
/// the canonical revocation-first ordering on top, which keeps the merge
/// commutative for signatures, where packet order carries no semantics.
#[derive(Clone, Debug, Default)]
struct AttachedPackets {
    packets: BTreeMap<CanonicalPacket, AttachedPacketEntry>,
    order: Vec<CanonicalPacket>,
}

#[derive(Clone, Debug)]
struct AttachedPacketEntry {
    packet: CanonicalPacket,
    signature: Option<Arc<Signature>>,
    quality: SignatureVariantQuality,
    creation_time: Option<u32>,
}

impl AttachedPacketEntry {
    fn from_packet(
        packet: CanonicalPacket,
        quality: SignatureVariantQuality,
    ) -> Result<(CanonicalPacket, Self), CertificateMergeError> {
        let signature = (packet.tag == SIGNATURE_TAG)
            .then(|| parse_signature_packet(&packet).map(Arc::new))
            .transpose()?;
        Self::from_parsed(packet, signature, quality)
    }

    fn from_signature(
        packet: CanonicalPacket,
        signature: Arc<Signature>,
        quality: SignatureVariantQuality,
    ) -> Result<(CanonicalPacket, Self), CertificateMergeError> {
        if packet.tag != SIGNATURE_TAG {
            return Err(CertificateMergeError::Internal);
        }
        Self::from_parsed(packet, Some(signature), quality)
    }

    fn from_parsed(
        packet: CanonicalPacket,
        signature: Option<Arc<Signature>>,
        quality: SignatureVariantQuality,
    ) -> Result<(CanonicalPacket, Self), CertificateMergeError> {
        let key = match signature.as_deref() {
            Some(signature) => attached_packet_key_from_signature(&packet, signature)?,
            None => packet.clone(),
        };
        let creation_time = signature.as_deref().and_then(signature_creation_time);
        Ok((
            key,
            Self {
                packet,
                signature,
                quality,
                creation_time,
            },
        ))
    }

    fn signature(&self) -> Option<&Signature> {
        self.signature.as_deref()
    }
}

impl PartialEq for AttachedPackets {
    fn eq(&self, other: &Self) -> bool {
        self.packets.len() == other.packets.len()
            && self.packets.iter().all(|(key, entry)| {
                other
                    .packets
                    .get(key)
                    .is_some_and(|other| entry.packet == other.packet)
            })
    }
}

impl Eq for AttachedPackets {}

/// Preference between wire variants of the same cryptographic signature.
///
/// The digest prefix and legacy V2/V3 issuer Key ID are mutable without
/// changing the signature MPIs.  Remembering what the bounded rehoming pass
/// learned prevents an early damaged variant from winning the deterministic
/// merge over a later usable copy.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord)]
enum SignatureVariantQuality {
    IncorrectPrefix,
    #[default]
    Unknown,
    CorrectPrefix,
    VerifiedByPrimary,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum AttachedPacketOwner {
    Direct,
    Identity,
    Subkey,
    Opaque,
}

impl AttachedPacketOwner {
    fn signature_group(self, signature_type: Option<SignatureType>) -> u8 {
        match (self, signature_type) {
            (Self::Direct, Some(SignatureType::KeyRevocation))
            | (Self::Direct, Some(SignatureType::CertRevocation))
            | (Self::Identity, Some(SignatureType::CertRevocation))
            | (Self::Subkey, Some(SignatureType::SubkeyRevocation)) => 0,
            (Self::Direct, Some(SignatureType::Key))
            | (
                Self::Identity,
                Some(
                    SignatureType::CertGeneric
                    | SignatureType::CertPersona
                    | SignatureType::CertCasual
                    | SignatureType::CertPositive,
                ),
            )
            | (Self::Subkey, Some(SignatureType::SubkeyBinding)) => 1,
            _ => 2,
        }
    }
}

impl AttachedPackets {
    fn values(&self) -> impl Iterator<Item = &CanonicalPacket> {
        self.packets.values().map(|entry| &entry.packet)
    }

    fn entries(&self) -> impl Iterator<Item = (&CanonicalPacket, &AttachedPacketEntry)> {
        self.packets.iter()
    }

    #[cfg(test)]
    fn is_empty(&self) -> bool {
        self.packets.is_empty()
    }

    /// Inserts or merges one packet and reports whether the retained evidence
    /// changed.
    ///
    /// Callers use the flag instead of comparing canonical serializations.
    fn insert(&mut self, packet: CanonicalPacket) -> Result<bool, CertificateMergeError> {
        self.insert_with_quality(packet, SignatureVariantQuality::Unknown)
    }

    fn insert_with_quality(
        &mut self,
        packet: CanonicalPacket,
        quality: SignatureVariantQuality,
    ) -> Result<bool, CertificateMergeError> {
        let (key, entry) = AttachedPacketEntry::from_packet(packet, quality)?;
        Ok(self.insert_prepared(key, entry))
    }

    fn insert_signature(
        &mut self,
        packet: CanonicalPacket,
        signature: Arc<Signature>,
        quality: SignatureVariantQuality,
    ) -> Result<bool, CertificateMergeError> {
        let (key, entry) = AttachedPacketEntry::from_signature(packet, signature, quality)?;
        Ok(self.insert_prepared(key, entry))
    }

    fn insert_prepared(&mut self, key: CanonicalPacket, entry: AttachedPacketEntry) -> bool {
        match self.packets.entry(key) {
            std::collections::btree_map::Entry::Occupied(mut occupied) => {
                let existing = occupied.get_mut();
                if existing.packet == entry.packet {
                    existing.quality = existing.quality.max(entry.quality);
                    return false;
                }
                let changed = second_signature_variant_is_preferred(
                    &existing.packet,
                    existing.quality,
                    &entry.packet,
                    entry.quality,
                );
                if changed {
                    *existing = entry;
                } else {
                    existing.quality = existing.quality.max(entry.quality);
                }
                changed
            }
            std::collections::btree_map::Entry::Vacant(vacant) => {
                self.order.push(vacant.key().clone());
                vacant.insert(entry);
                true
            }
        }
    }

    /// Replaces the packet stored under `expected`'s deduplication identity.
    ///
    /// The target must already be present: a mutation that cannot find the
    /// exact statement its policy view selected must fail, never append a
    /// second competing one.
    fn replace(
        &mut self,
        expected: &CanonicalPacket,
        replacement: CanonicalPacket,
    ) -> Result<(), CertificateMutationError> {
        if self.packets.remove(expected).is_none() {
            return Err(CertificateMutationError::TargetNotFound);
        }
        self.order.retain(|key| key != expected);
        self.insert(replacement)?;
        Ok(())
    }

    fn merge(&mut self, other: Self) -> Result<bool, CertificateMergeError> {
        let Self { mut packets, order } = other;
        let mut changed = false;
        for key in order {
            let Some((key, entry)) = packets.remove_entry(&key) else {
                return Err(CertificateMergeError::Internal);
            };
            changed |= self.insert_prepared(key, entry);
        }
        Ok(changed)
    }

    fn write_to(
        &self,
        output: &mut Vec<u8>,
        owner: AttachedPacketOwner,
        purpose: SerializationPurpose,
        authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
        withheld_packet_keys: &mut BTreeSet<CanonicalPacket>,
    ) -> Result<(), CertificateMergeError> {
        let mut packets = Vec::with_capacity(self.packets.len());
        for (key, entry) in &self.packets {
            let packet = &entry.packet;
            // Retained bytes preserve local-only evidence for the composed
            // policy view and private-material rebuild. Both public views
            // filter non-exportable certifications and sensitive designated-
            // revoker declarations; the transferable view additionally gates
            // their containing components in `serialize`.
            let exportable = match purpose {
                SerializationPurpose::Retained => true,
                SerializationPurpose::LocalPublic | SerializationPurpose::Transferable => {
                    attached_packet_is_exportable(key, entry, authenticated_sensitive_declarations)
                }
            };
            if !exportable {
                if !matches!(purpose, SerializationPurpose::Retained) && packet.tag == SIGNATURE_TAG
                {
                    withheld_packet_keys.insert(key.clone());
                }
                continue;
            }
            let signature = entry.signature();
            let signature_group = owner.signature_group(signature.and_then(Signature::typ));
            packets.push((packet, signature, signature_group, entry.creation_time));
        }
        packets.sort_by(
            |(first, first_signature, first_group, first_time),
             (second, second_signature, second_group, second_time)| {
                first
                    .tag
                    .cmp(&second.tag)
                    // RFC 9580 separates primary and subkey revocations in both
                    // v4 and v6, and additionally separates identity revocations
                    // in v6.  Using that identity split for v4 is compatible
                    // with its undifferentiated identity-signature sequence.
                    .then_with(|| first_group.cmp(second_group))
                    // Newest-first order makes the effective statement easy
                    // for streaming readers to encounter before superseded
                    // statements.
                    .then_with(|| second_time.cmp(first_time))
                    // Equal-time policy selection uses only cryptographic
                    // signature material. Put that same statement first in
                    // canonical output; full bytes remain a harmless final
                    // fallback for variants that differ only in unhashed data.
                    .then_with(|| match (first_signature, second_signature) {
                        (Some(first), Some(second)) => {
                            cryptographic_signature_material_cmp(first, second)
                        }
                        _ => std::cmp::Ordering::Equal,
                    })
                    .then_with(|| first.cmp(second))
            },
        );
        for (packet, _, _, _) in packets {
            packet.write_to(output)?;
        }
        Ok(())
    }

    fn withhold_all(
        &self,
        withheld_packet_keys: &mut BTreeSet<CanonicalPacket>,
    ) -> Result<(), CertificateMergeError> {
        withheld_packet_keys.extend(self.packets.keys().cloned());
        Ok(())
    }
}

/// Returns whether the packet is a certification the issuer marked local.
///
/// Only the hashed area is honored: an unhashed `ExportableCertification`
/// subpacket is attacker-modifiable, and treating it as authoritative would
/// let an intermediary suppress certifications. RFC 9580 sections 5.2.1 and
/// 5.2.3.19 limit this instruction to certification signature types 0x10
/// through 0x13; Direct Key, binding, and revocation signatures are distinct
/// types and remain exportable. If signed values conflict, the last hashed
/// occurrence wins per RFC 9580 section 5.2.3.9.
#[cfg(test)]
fn is_non_exportable_signature(packet: &CanonicalPacket) -> bool {
    if packet.tag != SIGNATURE_TAG {
        return false;
    }
    let Ok(signature) = parse_signature_packet(packet) else {
        return false;
    };
    signature_is_non_exportable(&signature)
}

fn signature_is_non_exportable(signature: &Signature) -> bool {
    matches!(
        signature.typ(),
        Some(
            SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
        )
    ) && signature
        .config()
        .is_some_and(signature_config_is_non_exportable)
}

fn attached_packet_is_exportable(
    key: &CanonicalPacket,
    entry: &AttachedPacketEntry,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
) -> bool {
    entry.signature().is_none_or(|signature| {
        parsed_signature_is_exportable(key, signature, authenticated_sensitive_declarations)
    })
}

fn parsed_signature_is_exportable(
    key: &CanonicalPacket,
    signature: &Signature,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
) -> bool {
    !signature_is_non_exportable(signature)
        && (!signature_has_sensitive_revocation_key(signature)
            || authenticated_sensitive_declarations.contains(key))
}

fn is_exportable_direct_self_signature_entry(
    key: &CanonicalPacket,
    entry: &AttachedPacketEntry,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    let Some(signature) = entry.signature() else {
        return Ok(false);
    };
    is_exportable_direct_self_signature_parsed(
        key,
        signature,
        primary,
        authenticated_sensitive_declarations,
        budget,
    )
}

fn is_exportable_direct_self_signature_parsed(
    key: &CanonicalPacket,
    signature: &Signature,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if !parsed_signature_is_exportable(key, signature, authenticated_sensitive_declarations) {
        return Ok(false);
    }
    if !matches!(
        signature.typ(),
        Some(SignatureType::Key | SignatureType::KeyRevocation | SignatureType::CertRevocation)
    ) {
        return Ok(false);
    }
    let Some(signature) = signature_ignoring_unhashed_issuer_hints(signature) else {
        return Ok(false);
    };
    if !signature_matches_signer(&signature, primary) {
        return Ok(false);
    }
    budget.verify(|| signature.verify_key(primary).is_ok())
}

#[cfg(test)]
fn is_exportable_identity_self_signature(
    packet: &CanonicalPacket,
    identity: &CanonicalPacket,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if packet.tag != SIGNATURE_TAG {
        return Ok(false);
    }
    let signature = parse_signature_packet(packet)?;
    let key = attached_packet_key_from_signature(packet, &signature)?;
    is_exportable_identity_self_signature_parsed(
        &key,
        &signature,
        identity,
        primary,
        authenticated_sensitive_declarations,
        budget,
    )
}

fn is_exportable_identity_self_signature_entry(
    key: &CanonicalPacket,
    entry: &AttachedPacketEntry,
    identity: &CanonicalPacket,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    let Some(signature) = entry.signature() else {
        return Ok(false);
    };
    is_exportable_identity_self_signature_parsed(
        key,
        signature,
        identity,
        primary,
        authenticated_sensitive_declarations,
        budget,
    )
}

fn is_exportable_identity_self_signature_parsed(
    key: &CanonicalPacket,
    signature: &Signature,
    identity: &CanonicalPacket,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if !parsed_signature_is_exportable(key, signature, authenticated_sensitive_declarations) {
        return Ok(false);
    }
    let tag = match identity.tag {
        USER_ID_TAG => Tag::UserId,
        USER_ATTRIBUTE_TAG => Tag::UserAttribute,
        _ => return Ok(false),
    };
    let admits_identity = match signature.typ() {
        Some(
            SignatureType::CertGeneric
            | SignatureType::CertPersona
            | SignatureType::CertCasual
            | SignatureType::CertPositive,
        ) => true,
        // A Certification Revocation can be the only statement in a minimal
        // mutation fragment.  A copied local marker does not make this packet
        // non-exportable under RFC 9580, but it does show that the revocation
        // must not by itself disclose an otherwise local-only identity.  If
        // an ordinary self-certification also exists, that certification
        // admits the component and the revocation remains associated evidence.
        Some(SignatureType::CertRevocation) => !signature
            .config()
            .is_some_and(signature_config_is_non_exportable),
        _ => false,
    };
    if !admits_identity {
        return Ok(false);
    }
    let Some(signature) = signature_ignoring_unhashed_issuer_hints(signature) else {
        return Ok(false);
    };
    if !signature_matches_signer(&signature, primary) {
        return Ok(false);
    }
    budget.verify(|| {
        signature
            .verify_certification(primary, tag, &RawIdentityBody(&identity.body))
            .is_ok()
    })
}

#[cfg(test)]
fn is_exportable_subkey_binding_signature(
    packet: &CanonicalPacket,
    subkey: &PublicSubkey,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if packet.tag != SIGNATURE_TAG {
        return Ok(false);
    }
    let signature = parse_signature_packet(packet)?;
    let key = attached_packet_key_from_signature(packet, &signature)?;
    is_exportable_subkey_binding_signature_parsed(
        &key,
        &signature,
        subkey,
        primary,
        authenticated_sensitive_declarations,
        budget,
    )
}

fn is_exportable_subkey_binding_signature_entry(
    key: &CanonicalPacket,
    entry: &AttachedPacketEntry,
    subkey: &PublicSubkey,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    let Some(signature) = entry.signature() else {
        return Ok(false);
    };
    is_exportable_subkey_binding_signature_parsed(
        key,
        signature,
        subkey,
        primary,
        authenticated_sensitive_declarations,
        budget,
    )
}

fn is_exportable_subkey_binding_signature_parsed(
    key: &CanonicalPacket,
    signature: &Signature,
    subkey: &PublicSubkey,
    primary: &PublicKey,
    authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if !parsed_signature_is_exportable(key, signature, authenticated_sensitive_declarations) {
        return Ok(false);
    }
    if signature.typ() != Some(SignatureType::SubkeyBinding) {
        return Ok(false);
    }
    let Some(signature) = signature_ignoring_unhashed_issuer_hints(signature) else {
        return Ok(false);
    };
    if !signature_matches_signer(&signature, primary) {
        return Ok(false);
    }
    if !budget.verify(|| signature.verify_subkey_binding(primary, subkey).is_ok())? {
        return Ok(false);
    }
    if !subkey_binding_requires_cross_certification(&signature, subkey) {
        return Ok(true);
    }
    has_valid_embedded_primary_key_binding(&signature, subkey, primary, budget)
}

/// Returns whether this exact binding advertises a signing-capable subkey.
///
/// Key Flags are authoritative only in the hashed area. When they are absent,
/// preserve the repository's legacy capability fallback: an algorithm that
/// can make acceptable signatures is signing-capable. An explicit hashed
/// Key Flags value without the signing bit opts a general-purpose algorithm
/// out, which keeps RSA encryption-only subkeys from requiring a backsig.
fn subkey_binding_requires_cross_certification(
    signature: &Signature,
    subkey: &PublicSubkey,
) -> bool {
    signature
        .config()
        .and_then(|config| {
            config
                .hashed_subpackets
                .iter()
                .rev()
                .find_map(|subpacket| match &subpacket.data {
                    SubpacketData::KeyFlags(flags) => Some(flags.sign()),
                    _ => None,
                })
        })
        .unwrap_or_else(|| signature_algorithm_acceptable(subkey.algorithm()))
}

/// Verifies a cross-certification carried by this exact outer binding.
///
/// RFC 9580 does not require the self-authenticating Embedded Signature
/// subpacket to be in the outer signature's hashed area, so inspect both
/// areas. Mutable issuer hints cannot select or veto the already-known
/// subkey, while authenticated issuer constraints must still match it.
fn has_valid_embedded_primary_key_binding(
    binding: &Signature,
    subkey: &PublicSubkey,
    primary: &PublicKey,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if !key_signature_verification_acceptable(subkey) {
        return Ok(false);
    }
    let Some(config) = binding.config() else {
        return Ok(false);
    };
    for embedded in config
        .hashed_subpackets()
        .chain(config.unhashed_subpackets())
        .filter_map(|subpacket| match &subpacket.data {
            SubpacketData::EmbeddedSignature(signature) => Some(signature.as_ref()),
            _ => None,
        })
    {
        if embedded.typ() != Some(SignatureType::KeyBinding) {
            continue;
        }
        let Some(embedded) = signature_ignoring_unhashed_issuer_hints(embedded) else {
            continue;
        };
        if !signature_matches_signer(&embedded, subkey) {
            continue;
        }
        if budget.verify(|| embedded.verify_primary_key_binding(subkey, primary).is_ok())? {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Returns whether a signature carries a hashed sensitive Revocation Key.
///
/// Exportability depends only on the signed subpacket, not on where the
/// signature is attached or whether its issuer can be authenticated. Unhashed
/// Revocation Key subpackets are ignored because they are mutable in transit.
fn signature_has_sensitive_revocation_key(signature: &Signature) -> bool {
    signature.config().is_some_and(|config| {
        config.hashed_subpackets().any(|subpacket| {
            matches!(&subpacket.data, SubpacketData::RevocationKey(revoker) if {
                let class = revoker.class as u8;
                class & 0xc0 == 0xc0
            })
        })
    })
}

/// Returns whether a signature is a genuine sensitive designated-revoker
/// declaration that must remain local at an ordinary export boundary.
///
/// Owner and type checks happen before cryptographic work. `finalize` applies
/// the existing shape cap first, so at most `MAX_SIGNATURES_PER_OBJECT`
/// Direct-Key candidates can reach this boundary, and only candidates that
/// actually carry a hashed sensitive declaration are verified.
fn is_genuine_sensitive_revoker_declaration(
    signature: &Signature,
    owner: AttachedPacketOwner,
    primary: &PublicKey,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if owner != AttachedPacketOwner::Direct || signature.typ() != Some(SignatureType::Key) {
        return Ok(false);
    }
    if !signature_has_sensitive_revocation_key(signature) {
        return Ok(false);
    }
    if !signature_version_matches_signer(signature.version(), primary.version())
        || !hashed_issuer_constraints_match_primary(signature, primary)
    {
        return Ok(false);
    }
    budget.verify(|| verify_key_ignoring_unhashed_issuer_hints(signature, primary))
}

/// Returns whether every authenticated issuer constraint identifies `primary`.
///
/// Unhashed issuer subpackets are deliberately ignored here. They are mutable
/// in transit and therefore cannot make a genuine sensitive declaration safe
/// to reveal, even when the mutation makes the packet RFC-malformed (notably a
/// V6 Issuer Key ID). Hashed constraints are strict because changing them also
/// invalidates the signature checked by the caller.
fn hashed_issuer_constraints_match_primary(signature: &Signature, primary: &PublicKey) -> bool {
    let Some(config) = signature.config() else {
        return false;
    };
    let primary_fingerprint = primary.fingerprint();
    let primary_key_id = primary.legacy_key_id();
    config
        .hashed_subpackets()
        .all(|subpacket| match &subpacket.data {
            SubpacketData::IssuerFingerprint(fingerprint) => {
                matches!(
                    (signature.version(), fingerprint.version()),
                    (SignatureVersion::V4, Some(KeyVersion::V4))
                        | (SignatureVersion::V6, Some(KeyVersion::V6))
                ) && fingerprint == &primary_fingerprint
            }
            SubpacketData::IssuerKeyId(key_id) => {
                signature.version() != SignatureVersion::V6 && key_id == &primary_key_id
            }
            _ => true,
        })
}

/// Cryptographically verifies a Direct-Key signature without allowing mutable
/// issuer hints to veto the known primary-key candidate.
fn verify_key_ignoring_unhashed_issuer_hints(signature: &Signature, primary: &PublicKey) -> bool {
    key_signature_verification_acceptable(primary)
        && signature_ignoring_unhashed_issuer_hints(signature).is_some_and(|candidate| {
            signature_verification_compatible(&candidate, primary)
                && candidate.verify_key(primary).is_ok()
        })
}

fn sensitive_revokers(signature: &Signature) -> Vec<SensitiveRevokerId> {
    let Some(config) = signature.config() else {
        return Vec::new();
    };
    let mut revokers = Vec::new();
    for subpacket in config.hashed_subpackets() {
        let SubpacketData::RevocationKey(revoker) = &subpacket.data else {
            continue;
        };
        let class = revoker.class as u8;
        if class & 0xc0 != 0xc0 {
            continue;
        }
        let revoker = SensitiveRevokerId {
            algorithm: u8::from(revoker.algorithm),
            fingerprint: revoker.fingerprint.to_vec(),
        };
        if !revokers.contains(&revoker) {
            revokers.push(revoker);
        }
    }
    revokers
}

fn sensitive_revoker_matches_component(
    revoker: &SensitiveRevokerId,
    candidate: &PublicComponent,
) -> bool {
    u8::from(candidate.algorithm()) == revoker.algorithm
        && candidate.fingerprint().as_bytes() == revoker.fingerprint.as_slice()
}

/// Verifies a Key Revocation using the exact key selected by a signed
/// designated-revoker declaration.
///
/// Issuer subpackets never select the key. Mutable unhashed issuer hints are
/// ignored, while the shared issuer-metadata validator still rejects malformed
/// or signed-inconsistent metadata before public-key verification.
fn component_verifies_key_revocation(
    candidate: &PublicComponent,
    signature: &Signature,
    primary: &PublicKey,
    budget: &mut ExportClassificationBudget,
) -> Result<bool, CertificateMergeError> {
    if !key_signature_verification_acceptable(candidate)
        || signature.typ() != Some(SignatureType::KeyRevocation)
    {
        return Ok(false);
    }
    let Some(signature) = signature_ignoring_unhashed_issuer_hints(signature) else {
        return Ok(false);
    };
    if !signature_matches_signer(&signature, candidate)
        || signature
            .config()
            .is_none_or(|config| config.pub_alg != candidate.algorithm())
    {
        return Ok(false);
    }
    budget.verify(|| match candidate {
        PublicComponent::Primary(key) => signature.verify_key_third_party(primary, key).is_ok(),
        PublicComponent::Subkey(key) => signature.verify_key_third_party(primary, key).is_ok(),
    })
}

/// Rejects a signature packet whose type does not belong to `owner`.
///
/// `allow_revocation` is set only for additions. Replacement never accepts a
/// revocation on either side, which is what keeps revocation evidence from
/// being edited out of a certificate by a renewal.
fn signature_type_belongs_to_owner(
    owner: &CertificateSignatureOwner,
    body: &[u8],
    allow_revocation: bool,
) -> Result<(), CertificateMutationError> {
    let signature = parse_signature_packet(&CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: body.to_vec(),
    })?;
    let valid = match (owner, signature.typ()) {
        (CertificateSignatureOwner::Direct, Some(SignatureType::Key))
        | (
            CertificateSignatureOwner::Identity { .. },
            Some(
                SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive,
            ),
        )
        | (CertificateSignatureOwner::Subkey { .. }, Some(SignatureType::SubkeyBinding)) => true,
        (
            CertificateSignatureOwner::Direct,
            Some(SignatureType::KeyRevocation | SignatureType::CertRevocation),
        )
        | (CertificateSignatureOwner::Identity { .. }, Some(SignatureType::CertRevocation))
        | (CertificateSignatureOwner::Subkey { .. }, Some(SignatureType::SubkeyRevocation)) => {
            allow_revocation
        }
        _ => false,
    };
    valid
        .then_some(())
        .ok_or(CertificateMutationError::Malformed)
}

fn validate_signature_type(
    owner: &CertificateSignatureOwner,
    body: &[u8],
) -> Result<(), CertificateMutationError> {
    signature_type_belongs_to_owner(owner, body, true)
}

fn attached_packet_key(packet: &CanonicalPacket) -> Result<CanonicalPacket, CertificateMergeError> {
    if packet.tag != SIGNATURE_TAG {
        return Ok(packet.clone());
    }
    let signature = parse_signature_packet(packet)?;
    attached_packet_key_from_signature(packet, &signature)
}

fn attached_packet_key_from_signature(
    packet: &CanonicalPacket,
    signature: &Signature,
) -> Result<CanonicalPacket, CertificateMergeError> {
    if packet.tag != SIGNATURE_TAG {
        return Ok(packet.clone());
    }
    let Some(mut normalized) = signature.config().cloned() else {
        return Ok(packet.clone());
    };
    normalized.unhashed_subpackets.clear();
    match &mut normalized.version_specific {
        SignatureVersionSpecific::V2 { issuer_key_id, .. }
        | SignatureVersionSpecific::V3 { issuer_key_id, .. } => {
            // V2/V3 hash only the signature type and creation time.  Their
            // fixed issuer Key ID is mutable routing metadata just like a
            // modern signature's unhashed issuer subpacket.
            *issuer_key_id = KeyId::WILDCARD;
        }
        SignatureVersionSpecific::V4 | SignatureVersionSpecific::V6 { .. } => {}
    }
    Ok(CanonicalPacket {
        tag: SIGNATURE_TAG,
        // The digest prefix is only a quick-rejection hint.  The signed
        // metadata plus signature MPIs are the cryptographic identity.
        body: rebuild_signature_body_with_prefix(signature, normalized, [0; 2])?,
    })
}

fn parse_signature_packet(packet: &CanonicalPacket) -> Result<Signature, CertificateMergeError> {
    parse_fixed_packet_body(Tag::Signature, packet.body.as_slice(), |header, reader| {
        Signature::try_from_reader(header, reader)
    })
    .map_err(CertificateMergeError::from)
}

fn rebuild_signature_body_with_prefix(
    signature: &Signature,
    config: pgp::packet::SignatureConfig,
    signed_hash_value: [u8; 2],
) -> Result<Vec<u8>, CertificateMergeError> {
    let signature_bytes = signature
        .signature()
        .cloned()
        .ok_or(CertificateMergeError::Malformed)?;
    Signature::from_config(config, signed_hash_value, signature_bytes)
        .and_then(|signature| signature.to_bytes())
        .map_err(|_| CertificateMergeError::Internal)
}

#[cfg(test)]
fn rebuild_signature_body(
    signature: &Signature,
    config: pgp::packet::SignatureConfig,
) -> Result<Vec<u8>, CertificateMergeError> {
    let signed_hash_value = signature
        .signed_hash_value()
        .ok_or(CertificateMergeError::Malformed)?;
    rebuild_signature_body_with_prefix(signature, config, signed_hash_value)
}

/// Chooses one complete wire variant of an otherwise equivalent signature.
///
/// Unhashed subpackets are advisory and are not protected by the signature.
/// Combining fields from separate packets would create a third packet that no
/// input actually supplied. Retaining one complete variant keeps provenance
/// explicit, avoids authenticating mutable metadata by accident, and makes
/// this merge a constant-space operation.
fn second_signature_variant_is_preferred(
    first: &CanonicalPacket,
    first_quality: SignatureVariantQuality,
    second: &CanonicalPacket,
    second_quality: SignatureVariantQuality,
) -> bool {
    second_quality > first_quality || (second_quality == first_quality && second.body < first.body)
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct CertificateComponent {
    packet: CanonicalPacket,
    attached: AttachedPackets,
}

/// Where one signature packet is stored inside the packet set.
#[derive(Clone, Debug, PartialEq, Eq)]
enum SignaturePlacement {
    Direct,
    Identity(CanonicalPacket),
    Subkey(FingerprintKey),
    /// No certificate component can be proven to own this signature.
    ///
    /// It is retained as opaque evidence and excluded from the semantic view,
    /// so a later identity or subkey can never acquire it by position alone.
    Unowned,
}

struct ResolvedSignaturePlacement {
    placement: SignaturePlacement,
    verified_by_primary: bool,
}

struct PendingSignature {
    packet: CanonicalPacket,
    signature: Arc<Signature>,
    syntactic_placement: Option<SignaturePlacement>,
}

#[derive(Debug, Default)]
pub(crate) struct SignatureRehomingBudget {
    verifications: usize,
    request_verifications: usize,
    active_certificate: Option<FingerprintKey>,
    inactive_certificate_verifications: BTreeMap<FingerprintKey, usize>,
}

/// Request-scoped accounting for cryptographic work performed solely to
/// classify ordinary transferable output.
///
/// This budget is deliberately independent from the policy budget:
/// structural parsing may derive an export view, but must not consume the
/// caller's later policy-verification allowance.
#[derive(Debug, Default)]
pub(crate) struct ExportClassificationBudget {
    verifications: usize,
    request_verifications: usize,
    active_certificate: Option<FingerprintKey>,
    inactive_certificate_verifications: BTreeMap<FingerprintKey, usize>,
}

impl ExportClassificationBudget {
    fn begin_certificate(&mut self, fingerprint: &FingerprintKey) {
        if self.active_certificate.as_ref() == Some(fingerprint) {
            return;
        }
        if let Some(active) = self.active_certificate.take() {
            self.inactive_certificate_verifications
                .insert(active, self.verifications);
        }
        self.verifications = self
            .inactive_certificate_verifications
            .remove(fingerprint)
            .unwrap_or_default();
        self.active_certificate = Some(fingerprint.clone());
    }

    fn verify(&mut self, verify: impl FnOnce() -> bool) -> Result<bool, CertificateMergeError> {
        let request_verifications = self
            .request_verifications
            .checked_add(1)
            .filter(|count| *count <= MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        let verifications = self
            .verifications
            .checked_add(1)
            .filter(|count| *count <= MAX_EXPORT_CLASSIFICATION_VERIFICATIONS)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        self.request_verifications = request_verifications;
        self.verifications = verifications;
        Ok(verify())
    }
}

impl SignatureRehomingBudget {
    fn begin_certificate(&mut self, fingerprint: &FingerprintKey) {
        if self.active_certificate.as_ref() == Some(fingerprint) {
            return;
        }
        if let Some(active) = self.active_certificate.take() {
            self.inactive_certificate_verifications
                .insert(active, self.verifications);
        }
        self.verifications = self
            .inactive_certificate_verifications
            .remove(fingerprint)
            .unwrap_or_default();
        self.active_certificate = Some(fingerprint.clone());
    }

    fn verify(&mut self, verify: impl FnOnce() -> bool) -> Result<bool, CertificateMergeError> {
        let request_verifications = self
            .request_verifications
            .checked_add(1)
            .filter(|count| *count <= MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        let verifications = self
            .verifications
            .checked_add(1)
            .filter(|count| *count <= MAX_SIGNATURE_REHOMING_VERIFICATIONS)
            .ok_or(CertificateMergeError::ResourceLimit)?;
        self.request_verifications = request_verifications;
        self.verifications = verifications;
        Ok(verify())
    }
}

impl CertificateComponent {
    fn write_to(
        &self,
        output: &mut Vec<u8>,
        purpose: SerializationPurpose,
        authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
        withheld_packet_keys: &mut BTreeSet<CanonicalPacket>,
    ) -> Result<(), CertificateMergeError> {
        self.packet.write_to(output)?;
        self.attached.write_to(
            output,
            AttachedPacketOwner::Subkey,
            purpose,
            authenticated_sensitive_declarations,
            withheld_packet_keys,
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct FingerprintKey {
    version: u8,
    bytes: Vec<u8>,
}

impl FingerprintKey {
    fn from_key(key: &impl KeyDetails) -> Result<Self, CertificateMergeError> {
        let fingerprint = key.fingerprint();
        let version = match fingerprint.version() {
            Some(KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V5) => {
                return Err(CertificateMergeError::UnsupportedKeyVersion);
            }
            Some(KeyVersion::V4) => 4,
            Some(KeyVersion::V6) => 6,
            Some(KeyVersion::Other(_)) | None => {
                return Err(CertificateMergeError::Malformed);
            }
        };
        Ok(Self {
            version,
            bytes: fingerprint.as_bytes().to_vec(),
        })
    }

    fn upper_hex(&self) -> String {
        hex_upper(&self.bytes)
    }
}

/// The read/merge pipeline's interchange representation of one transferable
/// public certificate.
///
/// Component, identity and unknown-component order is first-seen document
/// order. The metadata component index exposes that order, so it is retained
/// as input data rather than reconstructed after the fact. Component identity
/// and routing use versioned full fingerprints; Key IDs are only hints and are
/// not stored here.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PublicCertificatePacketSet {
    fingerprint: FingerprintKey,
    primary: CanonicalPacket,
    direct: AttachedPackets,
    identities: BTreeMap<CanonicalPacket, AttachedPackets>,
    identity_order: Vec<CanonicalPacket>,
    subkeys: BTreeMap<FingerprintKey, CertificateComponent>,
    subkey_order: Vec<FingerprintKey>,
    unknowns: BTreeMap<CanonicalPacket, AttachedPackets>,
    unknown_order: Vec<CanonicalPacket>,
}

#[derive(Clone)]
enum CurrentOwner {
    Direct,
    Identity(CanonicalPacket),
    Subkey(FingerprintKey),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CertificateComponentPhase {
    Identities,
    Subkeys,
}

/// Returns the component that may legally own a signature at this position.
///
/// Certificate-forming signature types have a constrained owner. Other known
/// or experimental signature types remain attached to an existing component
/// as opaque evidence, but are never accepted in the structurally unowned gap
/// after the primary key.
fn syntactic_placement(
    signature_type: Option<SignatureType>,
    owner: &CurrentOwner,
) -> Option<SignaturePlacement> {
    match (signature_type, owner) {
        (Some(SignatureType::Key | SignatureType::KeyRevocation), _) => {
            // A primary-key signature has exactly one possible target, so it is
            // hoisted regardless of where it was found.  Leaving a misplaced
            // key revocation attached to a User ID would silently strip it from
            // the policy view.
            Some(SignaturePlacement::Direct)
        }
        (Some(SignatureType::CertRevocation), CurrentOwner::Direct) => {
            // RFC 9580 permits a Certification Revocation to revoke a Direct
            // Key signature.  Its syntactic owner distinguishes that case
            // from the same signature type attached to an identity.
            Some(SignaturePlacement::Direct)
        }
        (
            Some(
                SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
                | SignatureType::CertRevocation,
            ),
            CurrentOwner::Identity(identity),
        ) => Some(SignaturePlacement::Identity(identity.clone())),
        (
            Some(SignatureType::SubkeyBinding | SignatureType::SubkeyRevocation),
            CurrentOwner::Subkey(fingerprint),
        ) => Some(SignaturePlacement::Subkey(fingerprint.clone())),
        (
            Some(
                SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
                | SignatureType::CertRevocation
                | SignatureType::SubkeyBinding
                | SignatureType::SubkeyRevocation,
            ),
            _,
        ) => None,
        (_, CurrentOwner::Identity(identity)) => {
            Some(SignaturePlacement::Identity(identity.clone()))
        }
        (_, CurrentOwner::Subkey(fingerprint)) => {
            Some(SignaturePlacement::Subkey(fingerprint.clone()))
        }
        // RFC 9580 §10.1 offers no slot between the primary key and the first
        // component for anything but the primary key's own signatures.  The
        // caller decides how to handle the signature classes that reach this
        // structurally unowned gap.
        (_, CurrentOwner::Direct) => None,
    }
}

fn signature_count(packets: &AttachedPackets) -> usize {
    packets
        .values()
        .filter(|packet| packet.tag == SIGNATURE_TAG)
        .count()
}

fn signature_key_hash_data<K>(key: &K) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let key_len = key.write_len();
    let mut result = Vec::with_capacity(key_len.saturating_add(5));
    match key.version() {
        KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V4 => {
            result.push(0x99);
            result.extend_from_slice(&u16::try_from(key_len).ok()?.to_be_bytes());
        }
        KeyVersion::V6 => {
            result.push(0x9b);
            result.extend_from_slice(&u32::try_from(key_len).ok()?.to_be_bytes());
        }
        KeyVersion::V5 | KeyVersion::Other(_) => return None,
    }
    key.to_writer(&mut result).ok()?;
    Some(result)
}

fn parse_primary_key(packet: &CanonicalPacket) -> Result<PublicKey, CertificateMergeError> {
    ensure_supported_key_version(&packet.body)?;
    parse_fixed_packet_body(Tag::PublicKey, packet.body.as_slice(), |header, reader| {
        PublicKey::try_from_reader(header, reader)
    })
    .map_err(CertificateMergeError::from)
}

fn parse_public_subkey(packet: &CanonicalPacket) -> Result<PublicSubkey, CertificateMergeError> {
    ensure_supported_key_version(&packet.body)?;
    parse_fixed_packet_body(
        Tag::PublicSubkey,
        packet.body.as_slice(),
        |header, reader| PublicSubkey::try_from_reader(header, reader),
    )
    .map_err(CertificateMergeError::from)
}

fn ensure_supported_key_version(body: &[u8]) -> Result<(), CertificateMergeError> {
    match body.first().copied().map(KeyVersion::from) {
        Some(KeyVersion::V4 | KeyVersion::V6) => Ok(()),
        Some(KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V5) => {
            Err(CertificateMergeError::UnsupportedKeyVersion)
        }
        Some(KeyVersion::Other(_)) | None => Err(CertificateMergeError::Malformed),
    }
}

#[cfg(test)]
fn parse_user_id(packet: &CanonicalPacket) -> Result<pgp::packet::UserId, CertificateMergeError> {
    parse_fixed_packet_body(Tag::UserId, packet.body.as_slice(), |header, reader| {
        pgp::packet::UserId::try_from_reader(header, reader)
    })
    .map_err(CertificateMergeError::from)
}

#[cfg(test)]
mod tests;
