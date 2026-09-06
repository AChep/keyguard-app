//! Parsing and structural attachment for packet-preserving certificates.

use super::*;

/// Parses every transferable public certificate in one decoded or armored
/// document without discarding packet bodies.
///
/// Independently unsupported or malformed certificate entries are skipped and
/// counted rather than failing later recoverable entries, matching tolerant
/// keyring import behavior.
#[cfg(test)]
pub(crate) fn parse_public_certificate_packet_sets(
    stream: &RawPacketStream,
) -> Result<ParsedCertificateDocument, CertificateMergeError> {
    parse_public_certificate_packet_sets_with_budget(
        stream,
        &mut SignatureRehomingBudget::default(),
    )
}

/// Parses a keyring while charging every certificate's placement repair to
/// one request-global budget.
pub(crate) fn parse_public_certificate_packet_sets_with_budget(
    stream: &RawPacketStream,
    budget: &mut SignatureRehomingBudget,
) -> Result<ParsedCertificateDocument, CertificateMergeError> {
    debug_assert!(
        stream
            .packets()
            .iter()
            .filter(|packet| packet.recovered_after_tainted_certificate())
            .all(|packet| matches!(packet.tag(), PUBLIC_KEY_TAG | SECRET_KEY_TAG))
    );
    let mut starts = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        if packet.tag() == PUBLIC_KEY_TAG {
            starts.push(index);
        }
    }
    if starts.is_empty() {
        return Err(CertificateMergeError::Malformed);
    }
    let mut document = ParsedCertificateDocument {
        skipped_malformed: stream.skipped_tainted_certificates(),
        ..ParsedCertificateDocument::default()
    };
    let first_start = starts
        .first()
        .copied()
        .ok_or(CertificateMergeError::Malformed)?;
    let prefix = stream
        .packets()
        .get(..first_start)
        .ok_or(CertificateMergeError::Malformed)?;
    if stream.validate_marker_packets(prefix).is_err() {
        // Keyring parsing deliberately recovers later independent
        // certificates. Treat malformed leading Marker data as one discarded
        // sequence instead of silently accepting it or poisoning later keys.
        document.skipped_malformed = document.skipped_malformed.saturating_add(1);
    }
    for (position, start) in starts.iter().enumerate() {
        let next_primary = starts
            .get(position + 1)
            .copied()
            .unwrap_or(stream.packets().len());
        // A transferable certificate cannot contain message packets. RFC
        // 9580 sections 4.3 and 10 require a known but inappropriate critical
        // packet to invalidate the whole current sequence; it is not an
        // end-marker that makes the preceding certificate prefix valid.
        let tainted = stream.packets()[start.saturating_add(1)..next_primary]
            .iter()
            .any(|packet| !certificate_packet_tag(packet.tag()));
        if tainted {
            document.skipped_malformed = document.skipped_malformed.saturating_add(1);
            continue;
        }
        let packets = stream
            .packets()
            .get(*start..next_primary)
            .ok_or(CertificateMergeError::Malformed)?;
        match PublicCertificatePacketSet::parse(stream, packets, budget) {
            Ok(certificate) => {
                document.certificates.push(certificate);
                document.spans.push(*start..next_primary);
            }
            Err(CertificateMergeError::UnsupportedKeyVersion) => {
                document.skipped_unsupported += 1;
            }
            Err(CertificateMergeError::Malformed | CertificateMergeError::ComponentCollision) => {
                document.skipped_malformed += 1;
            }
            Err(error) => return Err(error),
        }
    }
    Ok(document)
}

fn certificate_packet_tag(tag: u8) -> bool {
    matches!(
        tag,
        SIGNATURE_TAG
            | MARKER_TAG
            | TRUST_TAG
            | USER_ID_TAG
            | PUBLIC_SUBKEY_TAG
            | USER_ATTRIBUTE_TAG
            | PADDING_TAG
            | 40..=63
    )
}

/// Parses one document that must contain exactly one transferable
/// certificate.
pub(crate) fn parse_single_certificate_packet_set(
    data: &[u8],
) -> Result<PublicCertificatePacketSet, CertificateMergeError> {
    let (_, certificate) = parse_single_certificate_with_stream(data)?;
    Ok(certificate)
}

pub(super) fn parse_single_certificate_with_stream(
    data: &[u8],
) -> Result<(RawPacketStream, PublicCertificatePacketSet), CertificateMergeError> {
    parse_single_certificate_with_stream_and_budget(data, &mut SignatureRehomingBudget::default())
}

pub(super) fn parse_single_certificate_with_stream_and_budget(
    data: &[u8],
    budget: &mut SignatureRehomingBudget,
) -> Result<(RawPacketStream, PublicCertificatePacketSet), CertificateMergeError> {
    if data.is_empty() || data.iter().all(u8::is_ascii_whitespace) {
        return Err(CertificateMergeError::Malformed);
    }
    let stream = RawPacketStream::parse(data, MAX_MERGE_PACKETS)?;
    if stream
        .packets()
        .iter()
        .any(|packet| packet.body_len() > MAX_PACKET_BODY_BYTES)
    {
        return Err(CertificateMergeError::ResourceLimit);
    }
    let certificate = parse_single_certificate_from_stream(&stream, budget)?;
    certificate.validate_shape()?;
    Ok((stream, certificate))
}

/// Parses exactly one certificate without keyring recovery.
///
/// Import paths deliberately skip an independently malformed keyring entry so
/// later certificates remain usable.  Single-certificate mutation and
/// reconciliation paths still need the precise failure (notably component
/// collisions), so they use this strict entry parser instead.
fn parse_single_certificate_from_stream(
    stream: &RawPacketStream,
    budget: &mut SignatureRehomingBudget,
) -> Result<PublicCertificatePacketSet, CertificateMergeError> {
    let starts = stream
        .packets()
        .iter()
        .enumerate()
        .filter_map(|(index, packet)| (packet.tag() == PUBLIC_KEY_TAG).then_some(index))
        .collect::<Vec<_>>();
    let [start] = starts.as_slice() else {
        return Err(CertificateMergeError::Malformed);
    };
    let prefix = stream
        .packets()
        .get(..*start)
        .ok_or(CertificateMergeError::Malformed)?;
    stream.validate_marker_packets(prefix)?;
    if prefix
        .iter()
        .any(|packet| !matches!(packet.tag(), MARKER_TAG | TRUST_TAG))
    {
        return Err(CertificateMergeError::Malformed);
    }
    let end = stream.packets()[start.saturating_add(1)..]
        .iter()
        .position(|packet| !certificate_packet_tag(packet.tag()))
        .map(|offset| start + 1 + offset)
        .unwrap_or(stream.packets().len());
    if end != stream.packets().len() {
        return Err(CertificateMergeError::Malformed);
    }
    let packets = stream
        .packets()
        .get(*start..end)
        .ok_or(CertificateMergeError::Malformed)?;
    PublicCertificatePacketSet::parse(stream, packets, budget)
}

impl PublicCertificatePacketSet {
    pub(super) fn parse(
        stream: &RawPacketStream,
        packets: &[RawPacketSpan],
        rehoming_budget: &mut SignatureRehomingBudget,
    ) -> Result<Self, CertificateMergeError> {
        let primary_span = packets.first().ok_or(CertificateMergeError::Malformed)?;
        if primary_span.tag() != PUBLIC_KEY_TAG {
            return Err(CertificateMergeError::Malformed);
        }
        stream.validate_marker_packets(packets)?;
        let primary = CanonicalPacket::from_span(stream, primary_span);
        let primary_key = parse_primary_key(&primary)?;
        let primary_version = primary_key.version();
        let fingerprint = FingerprintKey::from_key(&primary_key)?;
        rehoming_budget.begin_certificate(&fingerprint);
        let mut certificate = Self {
            fingerprint: fingerprint.clone(),
            primary,
            direct: AttachedPackets::default(),
            identities: BTreeMap::new(),
            identity_order: Vec::new(),
            subkeys: BTreeMap::new(),
            subkey_order: Vec::new(),
            unknowns: BTreeMap::new(),
            unknown_order: Vec::new(),
        };
        let mut owner = CurrentOwner::Direct;
        let mut component_phase = CertificateComponentPhase::Identities;
        let mut pending_signatures = Vec::new();
        let mut signature_packets = 0usize;

        for span in packets.iter().skip(1) {
            // RFC 9580 §5.8 and §5.10: Marker packets carry no information and
            // MUST be ignored, and Trust packets are strictly local storage
            // that must never travel with a certificate. GnuPG drops both on
            // import instead of failing the certificate.
            if matches!(span.tag(), MARKER_TAG | TRUST_TAG) {
                continue;
            }
            let packet = CanonicalPacket::from_span(stream, span);
            match packet.tag {
                PUBLIC_KEY_TAG => return Err(CertificateMergeError::Malformed),
                USER_ID_TAG | USER_ATTRIBUTE_TAG => {
                    // RFC 9580 §§10.1.1, 10.1.3, and 10.1.5 put the
                    // complete identity section before the first subkey. A
                    // known critical identity packet cannot reopen that
                    // section once the subkey section has started.
                    if component_phase == CertificateComponentPhase::Subkeys {
                        return Err(CertificateMergeError::Malformed);
                    }
                    certificate.attach_identity(&packet);
                    owner = CurrentOwner::Identity(packet);
                }
                PUBLIC_SUBKEY_TAG => {
                    component_phase = CertificateComponentPhase::Subkeys;
                    let subkey = parse_public_subkey(&packet)?;
                    // RFC 9580 §§10.1.1 and 10.1.3 require every subkey to
                    // use the primary key's packet version. Both V4 and V6
                    // are supported individually, but mixing them makes the
                    // certificate structure malformed.
                    if subkey.version() != primary_version {
                        return Err(CertificateMergeError::Malformed);
                    }
                    let subkey_fingerprint = FingerprintKey::from_key(&subkey)?;
                    if subkey_fingerprint == certificate.fingerprint {
                        return Err(CertificateMergeError::ComponentCollision);
                    }
                    match certificate.subkeys.get(&subkey_fingerprint) {
                        Some(existing) if existing.packet != packet => {
                            return Err(CertificateMergeError::ComponentCollision);
                        }
                        Some(_) => {}
                        None => {
                            certificate.subkey_order.push(subkey_fingerprint.clone());
                            certificate.subkeys.insert(
                                subkey_fingerprint.clone(),
                                CertificateComponent {
                                    packet,
                                    attached: AttachedPackets::default(),
                                },
                            );
                        }
                    }
                    owner = CurrentOwner::Subkey(subkey_fingerprint);
                }
                SIGNATURE_TAG => {
                    signature_packets = signature_packets
                        .checked_add(1)
                        .filter(|count| *count <= MAX_SIGNATURES)
                        .ok_or(CertificateMergeError::ResourceLimit)?;
                    let (signature, signature_type) = match parse_signature_packet(&packet) {
                        Ok(signature) => match signature.typ() {
                            Some(signature_type) => (signature, signature_type),
                            None => {
                                // RFC 9580 §5.2.5: a future/unknown signature
                                // must not reject the other signatures in this
                                // packet stream. Its type and therefore its
                                // owner are unknowable, so retain it as an
                                // unowned opaque component instead of guessing.
                                certificate.attach_unknown(packet);
                                continue;
                            }
                        },
                        Err(CertificateMergeError::Malformed) => {
                            // Packet framing has already isolated this exact
                            // body. Preserve the opaque evidence, but exclude
                            // it from semantic parsing and placement.
                            certificate.attach_unknown(packet);
                            continue;
                        }
                        Err(error) => return Err(error),
                    };
                    pending_signatures.push(PendingSignature {
                        packet,
                        signature: Arc::new(signature),
                        syntactic_placement: syntactic_placement(Some(signature_type), &owner),
                    });
                }
                40..=63 => {
                    certificate.attach_unknown(packet);
                    // RFC 9580 §§4.3 and 10 require unknown non-critical
                    // packets to be ignored and allow them anywhere in a
                    // sequence. Retain the raw packet for lossless merging,
                    // but leave the last known component as the syntactic
                    // owner of following signatures.
                }
                PADDING_TAG => {
                    certificate.attach(&owner, packet)?;
                }
                _ => return Err(CertificateMergeError::Malformed),
            }
        }
        // Cap the candidate set before any cryptographic rehoming.  Signature
        // attachment is still empty here, so this enforces the component
        // bounds without charging the final per-object signature checks.
        certificate.validate_shape()?;
        for pending in pending_signatures {
            let PendingSignature {
                packet,
                signature,
                syntactic_placement,
            } = pending;
            let resolved = certificate.resolve_signature_placement(
                &primary_key,
                &signature,
                syntactic_placement,
                rehoming_budget,
            )?;
            let quality =
                certificate.signature_variant_quality(&primary_key, &signature, &resolved);
            certificate.attach_signature(resolved.placement, packet, signature, quality)?;
        }
        // Rehoming can turn pending signatures into standalone opaque
        // components, so enforce component and per-owner bounds again on the
        // fully attached packet set.
        certificate.validate_shape()?;
        Ok(certificate)
    }

    /// Resolves a signature without ever guessing a different component.
    ///
    /// A syntactically legal packet remains evidence on that component when
    /// the known primary key cannot authenticate it (notably a third-party or
    /// damaged signature). If the signature is self-issued, however, every
    /// type-compatible component is tried under a fixed aggregate budget and
    /// one exact cryptographic match is authoritative. A packet in an illegal
    /// position has no safe component fallback: an unplaceable certification
    /// remains inert opaque evidence rather than poisoning the certificate or
    /// being rewritten under an arbitrary owner.
    pub(super) fn resolve_signature_placement(
        &self,
        primary: &PublicKey,
        signature: &Signature,
        syntactic_placement: Option<SignaturePlacement>,
        budget: &mut SignatureRehomingBudget,
    ) -> Result<ResolvedSignaturePlacement, CertificateMergeError> {
        let signature_type = signature.typ();

        // Primary-key signatures have one structural target. Key revocations
        // may be issued by a designated revoker whose key is not in this
        // certificate, so placement must not depend on self-verification.
        if matches!(
            signature_type,
            Some(SignatureType::Key | SignatureType::KeyRevocation)
        ) {
            let verified_by_primary = if key_signature_verification_acceptable(primary)
                && signature_matches_signer(signature, primary)
            {
                budget.verify(|| verify_key_ignoring_unhashed_issuer_hints(signature, primary))?
            } else {
                false
            };
            return Ok(ResolvedSignaturePlacement {
                placement: SignaturePlacement::Direct,
                verified_by_primary,
            });
        }

        let type_has_component_target = matches!(
            signature_type,
            Some(
                SignatureType::CertGeneric
                    | SignatureType::CertPersona
                    | SignatureType::CertCasual
                    | SignatureType::CertPositive
                    | SignatureType::CertRevocation
                    | SignatureType::SubkeyBinding
                    | SignatureType::SubkeyRevocation
            )
        );
        if !type_has_component_target {
            return unresolved_signature_placement(signature_type, syntactic_placement);
        }

        if !key_signature_verification_acceptable(primary) {
            return unresolved_signature_placement(signature_type, syntactic_placement);
        }

        // Unhashed issuer hints are mutable routing metadata. Strip them for
        // exact placement, while retaining every signed issuer constraint.
        let Some(verification_signature) = signature_ignoring_unhashed_issuer_hints(signature)
        else {
            return unresolved_signature_placement(signature_type, syntactic_placement);
        };
        if !signature_matches_signer(&verification_signature, primary) {
            return unresolved_signature_placement(signature_type, syntactic_placement);
        }

        let matches = self.exact_signature_placements(
            primary,
            &verification_signature,
            signature_type,
            syntactic_placement.as_ref(),
            budget,
        )?;
        match matches.as_slice() {
            [placement] => Ok(ResolvedSignaturePlacement {
                placement: placement.clone(),
                verified_by_primary: true,
            }),
            [] | [_, _, ..] => unresolved_signature_placement(signature_type, syntactic_placement),
        }
    }

    /// Finds at most two exact targets; a second is enough to establish that
    /// placement is ambiguous and avoids spending more of the request budget.
    pub(super) fn exact_signature_placements(
        &self,
        primary: &PublicKey,
        signature: &Signature,
        signature_type: Option<SignatureType>,
        syntactic_placement: Option<&SignaturePlacement>,
        budget: &mut SignatureRehomingBudget,
    ) -> Result<Vec<SignaturePlacement>, CertificateMergeError> {
        let mut matches = Vec::with_capacity(2);
        match signature_type {
            Some(
                SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
                | SignatureType::CertRevocation,
            ) => {
                if let Some(SignaturePlacement::Identity(identity)) = syntactic_placement {
                    let tag = match identity.tag {
                        USER_ID_TAG => Tag::UserId,
                        USER_ATTRIBUTE_TAG => Tag::UserAttribute,
                        _ => return Err(CertificateMergeError::Internal),
                    };
                    if budget.verify(|| {
                        signature
                            .verify_certification(primary, tag, &RawIdentityBody(&identity.body))
                            .is_ok()
                    })? {
                        return Ok(vec![SignaturePlacement::Identity(identity.clone())]);
                    }
                }
                for identity in &self.identity_order {
                    if matches!(
                        syntactic_placement,
                        Some(SignaturePlacement::Identity(current)) if current == identity
                    ) {
                        continue;
                    }
                    let tag = match identity.tag {
                        USER_ID_TAG => Tag::UserId,
                        USER_ATTRIBUTE_TAG => Tag::UserAttribute,
                        _ => return Err(CertificateMergeError::Internal),
                    };
                    if budget.verify(|| {
                        signature
                            .verify_certification(primary, tag, &RawIdentityBody(&identity.body))
                            .is_ok()
                    })? {
                        matches.push(SignaturePlacement::Identity(identity.clone()));
                        if matches.len() == 2 {
                            break;
                        }
                    }
                }
            }
            Some(SignatureType::SubkeyBinding | SignatureType::SubkeyRevocation) => {
                if let Some(SignaturePlacement::Subkey(fingerprint)) = syntactic_placement {
                    let component = self
                        .subkeys
                        .get(fingerprint)
                        .ok_or(CertificateMergeError::Internal)?;
                    let subkey = parse_public_subkey(&component.packet)?;
                    if budget
                        .verify(|| signature.verify_subkey_binding(primary, &subkey).is_ok())?
                    {
                        return Ok(vec![SignaturePlacement::Subkey(fingerprint.clone())]);
                    }
                }
                for fingerprint in &self.subkey_order {
                    if matches!(
                        syntactic_placement,
                        Some(SignaturePlacement::Subkey(current)) if current == fingerprint
                    ) {
                        continue;
                    }
                    let component = self
                        .subkeys
                        .get(fingerprint)
                        .ok_or(CertificateMergeError::Internal)?;
                    let subkey = parse_public_subkey(&component.packet)?;
                    if budget
                        .verify(|| signature.verify_subkey_binding(primary, &subkey).is_ok())?
                    {
                        matches.push(SignaturePlacement::Subkey(fingerprint.clone()));
                        if matches.len() == 2 {
                            break;
                        }
                    }
                }
            }
            _ => {}
        }
        Ok(matches)
    }

    /// Ranks duplicate wire encodings without trusting their mutable fields.
    ///
    /// Rehoming already authenticates self-issued component signatures under
    /// a fixed aggregate budget.  For third-party and damaged signatures, the
    /// owner-specific digest is still cheap to compute without the issuer's
    /// key, which is enough to distinguish a correct prefix from a corrupted
    /// one.  Unknown hash algorithms remain retained with neutral quality.
    pub(super) fn signature_variant_quality(
        &self,
        primary: &PublicKey,
        signature: &Signature,
        resolved: &ResolvedSignaturePlacement,
    ) -> SignatureVariantQuality {
        if resolved.verified_by_primary {
            return SignatureVariantQuality::VerifiedByPrimary;
        }
        match self.signature_digest_prefix_matches_placement(
            primary,
            signature,
            &resolved.placement,
        ) {
            Some(true) => SignatureVariantQuality::CorrectPrefix,
            Some(false) => SignatureVariantQuality::IncorrectPrefix,
            None => SignatureVariantQuality::Unknown,
        }
    }

    pub(super) fn signature_digest_prefix_matches_placement(
        &self,
        primary: &PublicKey,
        signature: &Signature,
        placement: &SignaturePlacement,
    ) -> Option<bool> {
        let config = signature.config()?;
        let expected = signature.signed_hash_value()?;
        let mut hasher = config.hash_alg.new_hasher().ok()?;
        if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
            hasher.update(salt);
        }

        hasher.update(&signature_key_hash_data(primary)?);
        match placement {
            SignaturePlacement::Direct => {}
            SignaturePlacement::Identity(identity) => {
                match signature.version() {
                    SignatureVersion::V2 | SignatureVersion::V3 => {}
                    SignatureVersion::V4 | SignatureVersion::V6 => {
                        let prefix = match identity.tag {
                            USER_ID_TAG => 0xb4,
                            USER_ATTRIBUTE_TAG => 0xd1,
                            _ => return None,
                        };
                        let length = u32::try_from(identity.body.len()).ok()?;
                        hasher.update(&[prefix]);
                        hasher.update(&length.to_be_bytes());
                    }
                    SignatureVersion::V5 | SignatureVersion::Other(_) => return None,
                }
                hasher.update(&identity.body);
            }
            SignaturePlacement::Subkey(fingerprint) => {
                let component = self.subkeys.get(fingerprint)?;
                let subkey = parse_public_subkey(&component.packet).ok()?;
                hasher.update(&signature_key_hash_data(&subkey)?);
            }
            SignaturePlacement::Unowned => return None,
        }
        let signature_data_len = config.hash_signature_data(&mut hasher).ok()?;
        hasher.update(&config.trailer(signature_data_len).ok()?);
        let digest = hasher.finalize();
        Some(digest.get(..expected.len()) == Some(expected.as_slice()))
    }

    pub(super) fn attach_signature(
        &mut self,
        placement: SignaturePlacement,
        packet: CanonicalPacket,
        signature: Arc<Signature>,
        quality: SignatureVariantQuality,
    ) -> Result<(), CertificateMergeError> {
        match placement {
            SignaturePlacement::Direct => {
                self.direct.insert_signature(packet, signature, quality)?
            }
            SignaturePlacement::Identity(identity) => self
                .identities
                .get_mut(&identity)
                .ok_or(CertificateMergeError::Internal)?
                .insert_signature(packet, signature, quality)?,
            SignaturePlacement::Subkey(fingerprint) => self
                .subkeys
                .get_mut(&fingerprint)
                .ok_or(CertificateMergeError::Internal)?
                .attached
                .insert_signature(packet, signature, quality)?,
            SignaturePlacement::Unowned => {
                self.attach_unknown(packet);
                false
            }
        };
        Ok(())
    }

    /// Records an identity component once, keeping `identity_order` aligned
    /// with the `identities` map in first-seen order.
    fn attach_identity(&mut self, packet: &CanonicalPacket) {
        if !self.identities.contains_key(packet) {
            self.identity_order.push(packet.clone());
            self.identities
                .insert(packet.clone(), AttachedPackets::default());
        }
    }

    /// Retains an opaque or unowned packet once, keeping `unknown_order`
    /// aligned with the `unknowns` map in first-seen order.
    fn attach_unknown(&mut self, packet: CanonicalPacket) {
        if !self.unknowns.contains_key(&packet) {
            self.unknown_order.push(packet.clone());
            self.unknowns.insert(packet, AttachedPackets::default());
        }
    }

    pub(super) fn attach(
        &mut self,
        owner: &CurrentOwner,
        packet: CanonicalPacket,
    ) -> Result<(), CertificateMergeError> {
        match owner {
            CurrentOwner::Direct => self.direct.insert(packet)?,
            CurrentOwner::Identity(identity) => self
                .identities
                .get_mut(identity)
                .ok_or(CertificateMergeError::Internal)?
                .insert(packet)?,
            CurrentOwner::Subkey(fingerprint) => self
                .subkeys
                .get_mut(fingerprint)
                .ok_or(CertificateMergeError::Internal)?
                .attached
                .insert(packet)?,
        };
        Ok(())
    }
}

/// Keeps a syntactically attached signature with its component, but treats an
/// unplaceable certification as inert evidence. Other signature classes still
/// require a structurally valid owner.
fn unresolved_signature_placement(
    signature_type: Option<SignatureType>,
    syntactic_placement: Option<SignaturePlacement>,
) -> Result<ResolvedSignaturePlacement, CertificateMergeError> {
    if let Some(placement) = syntactic_placement {
        return Ok(ResolvedSignaturePlacement {
            placement,
            verified_by_primary: false,
        });
    }
    if matches!(
        signature_type,
        Some(
            SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
                | SignatureType::CertRevocation
        )
    ) {
        return Ok(ResolvedSignaturePlacement {
            placement: SignaturePlacement::Unowned,
            verified_by_primary: false,
        });
    }
    Err(CertificateMergeError::Malformed)
}
