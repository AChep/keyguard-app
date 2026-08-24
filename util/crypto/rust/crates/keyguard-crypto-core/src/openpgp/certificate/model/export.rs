//! Retained and transferable certificate serialization.

use super::*;

/// Returns whether one packet of an otherwise byte-preserved certificate may
/// appear in an ordinary transferable document.
///
/// Trust and Marker packets are always local/empty; components without
/// authenticated self-binding evidence, non-exportable certifications, and
/// signatures carrying hashed sensitive Revocation Keys are withheld.
pub(crate) fn raw_packet_is_exportable(
    canonical: &CanonicalCertificate,
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> bool {
    if !canonical.transferable || matches!(span.tag(), TRUST_TAG | MARKER_TAG) {
        return false;
    }
    let packet = CanonicalPacket::from_span(stream, span);
    match attached_packet_key(&packet) {
        Ok(key) => !canonical.withheld_packet_keys.contains(&key),
        // Semantically malformed Signature packets with valid framing are
        // retained as opaque certificate components. They cannot carry an
        // authenticated export restriction, and their raw framing remains
        // safe to reproduce without trying to interpret or attach them.
        Err(CertificateMergeError::Malformed) if packet.tag == SIGNATURE_TAG => true,
        Err(_) => false,
    }
}

/// Returns whether one packet may appear in a locally stored public
/// projection while preserving its original framing.
///
/// Unlike ordinary transferable export, this view keeps structurally
/// unusable components so import, inspection, mutation, and reconciliation do
/// not discard evidence. It still withholds certifications carrying an
/// explicit local-only flag and signatures carrying a sensitive export
/// restriction.
fn raw_packet_is_local_public(
    canonical: &CanonicalCertificate,
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> bool {
    if matches!(span.tag(), TRUST_TAG | MARKER_TAG) {
        return false;
    }
    let packet = CanonicalPacket::from_span(stream, span);
    match attached_packet_key(&packet) {
        Ok(key) => !canonical.local_public_withheld_packet_keys.contains(&key),
        Err(CertificateMergeError::Malformed) if packet.tag == SIGNATURE_TAG => true,
        Err(_) => false,
    }
}

/// Filters one public certificate for ordinary export while preserving the
/// framing of every packet that remains.
#[cfg(test)]
pub(crate) fn export_public_certificate_preserving_framing(
    data: &[u8],
) -> Result<Vec<u8>, CertificateMergeError> {
    let mut rehoming_budget = SignatureRehomingBudget::default();
    let (stream, certificate) =
        parsing::parse_single_certificate_with_stream_and_budget(data, &mut rehoming_budget)?;
    let canonical = certificate.finalize()?;
    if !canonical.transferable {
        return Ok(Vec::new());
    }
    let mut output = Vec::with_capacity(data.len());
    for packet in stream
        .packets()
        .iter()
        .filter(|packet| raw_packet_is_exportable(&canonical, &stream, packet))
    {
        output.extend_from_slice(stream.raw(packet));
    }
    Ok(output)
}

/// Builds the public projection used by local key state while preserving the
/// framing of every retained packet.
pub(crate) fn local_public_certificate_preserving_framing(
    data: &[u8],
) -> Result<Vec<u8>, CertificateMergeError> {
    let mut rehoming_budget = SignatureRehomingBudget::default();
    let (stream, certificate) =
        parsing::parse_single_certificate_with_stream_and_budget(data, &mut rehoming_budget)?;
    let canonical = certificate.finalize()?;
    let mut output = Vec::with_capacity(data.len());
    for packet in stream
        .packets()
        .iter()
        .filter(|packet| raw_packet_is_local_public(&canonical, &stream, packet))
    {
        output.extend_from_slice(stream.raw(packet));
    }
    Ok(output)
}

impl PublicCertificatePacketSet {
    pub(super) fn authenticated_sensitive_declarations(
        &self,
        primary: &PublicKey,
        candidates: &[PublicComponent],
        budget: &mut ExportClassificationBudget,
    ) -> Result<BTreeSet<CanonicalPacket>, CertificateMergeError> {
        if candidates.is_empty() {
            return Ok(BTreeSet::new());
        }
        let revocations = self
            .direct
            .entries()
            .filter_map(|(_, entry)| entry.signature())
            .collect::<Vec<_>>();
        let mut declarations = BTreeSet::new();
        let mut revoker_count = 0usize;
        let mut verifications = 0usize;
        for (key, entry) in self.direct.entries() {
            let Some(signature) = entry.signature() else {
                continue;
            };
            if !is_genuine_sensitive_revoker_declaration(
                signature,
                AttachedPacketOwner::Direct,
                primary,
                budget,
            )? {
                continue;
            }
            let revokers = sensitive_revokers(signature);
            revoker_count = revoker_count
                .checked_add(revokers.len())
                .filter(|count| *count <= MAX_SENSITIVE_REVOKERS_PER_EXPORT)
                .ok_or(CertificateMergeError::ResourceLimit)?;

            // A signature packet is atomic. If it contains multiple sensitive
            // relationships, exporting it for just one would reveal the rest;
            // require every sensitive declaration in this packet to accompany
            // an authenticated revocation.
            let mut every_revoker_authenticated = !revokers.is_empty();
            for revoker in &revokers {
                let candidate = candidates.iter().find(|candidate| {
                    sensitive_revoker_matches_component(revoker, candidate)
                        && FingerprintKey::from_key(*candidate).is_ok_and(|fingerprint| {
                            fingerprint != self.fingerprint
                                && !self.subkeys.contains_key(&fingerprint)
                        })
                });
                let Some(candidate) = candidate else {
                    every_revoker_authenticated = false;
                    break;
                };
                let mut authenticated = false;
                for revocation in &revocations {
                    if revocation.typ() != Some(SignatureType::KeyRevocation)
                        || revocation
                            .config()
                            .is_none_or(|config| u8::from(config.pub_alg) != revoker.algorithm)
                    {
                        continue;
                    }
                    verifications = verifications
                        .checked_add(1)
                        .filter(|count| *count <= MAX_REVOCATION_EXPORT_VERIFICATIONS)
                        .ok_or(CertificateMergeError::ResourceLimit)?;
                    if component_verifies_key_revocation(candidate, revocation, primary, budget)? {
                        authenticated = true;
                        break;
                    }
                }
                if !authenticated {
                    every_revoker_authenticated = false;
                    break;
                }
            }
            if every_revoker_authenticated {
                declarations.insert(key.clone());
            }
        }
        Ok(declarations)
    }

    /// Selects the components that have authenticated evidence suitable for
    /// an ordinary transferable certificate.
    ///
    /// Third-party certifications remain associated evidence, but cannot bind
    /// an identity or subkey to this primary key. A version 4 primary key is a
    /// complete certificate by itself under RFC 9580 §10.1.3. An identity
    /// whose only self-certification is local is omitted with that
    /// certification: the grammar permits a zero-signature identity, but does
    /// not require exporting one, and omission avoids disclosing local-only
    /// identity data. Version 6 still requires a valid Direct Key
    /// self-signature, except for the exact primary-key-plus-Key-Revocation
    /// form from §10.1.2. Every exported subkey requires a valid self-issued
    /// binding signature.
    pub(super) fn transferable_components(
        &self,
        primary: &PublicKey,
        authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
        budget: &mut ExportClassificationBudget,
    ) -> Result<TransferableComponents, CertificateMergeError> {
        let mut transferable = TransferableComponents {
            certificate: primary.version() == KeyVersion::V4,
            ..TransferableComponents::default()
        };
        let mut has_exportable_v6_key_revocation = false;
        if primary.version() == KeyVersion::V6 {
            for (key, entry) in self.direct.entries() {
                if is_exportable_direct_self_signature_entry(
                    key,
                    entry,
                    primary,
                    authenticated_sensitive_declarations,
                    budget,
                )? {
                    match entry.signature().and_then(Signature::typ) {
                        Some(SignatureType::Key) => {
                            transferable.certificate = true;
                            break;
                        }
                        Some(SignatureType::KeyRevocation) => {
                            has_exportable_v6_key_revocation = true;
                        }
                        _ => {}
                    }
                }
            }
        }
        for identity in &self.identity_order {
            let attached = self
                .identities
                .get(identity)
                .ok_or(CertificateMergeError::Internal)?;
            let mut exportable = false;
            for (key, entry) in attached.entries() {
                if is_exportable_identity_self_signature_entry(
                    key,
                    entry,
                    identity,
                    primary,
                    authenticated_sensitive_declarations,
                    budget,
                )? {
                    exportable = true;
                    break;
                }
            }
            if exportable {
                transferable.identities.insert(identity.clone());
            }
        }
        for fingerprint in &self.subkey_order {
            let component = self
                .subkeys
                .get(fingerprint)
                .ok_or(CertificateMergeError::Internal)?;
            let subkey = parse_public_subkey(&component.packet)?;
            let mut exportable = false;
            for (key, entry) in component.attached.entries() {
                if is_exportable_subkey_binding_signature_entry(
                    key,
                    entry,
                    &subkey,
                    primary,
                    authenticated_sensitive_declarations,
                    budget,
                )? {
                    exportable = true;
                    break;
                }
            }
            if exportable {
                transferable.subkeys.insert(fingerprint.clone());
            }
        }
        if primary.version() == KeyVersion::V6
            && has_exportable_v6_key_revocation
            && self.direct.values().count() == 1
            && self.identities.is_empty()
            && self.subkeys.is_empty()
            && self.unknowns.is_empty()
        {
            transferable.certificate = true;
        }
        Ok(transferable)
    }

    pub(super) fn canonical_bytes(&self) -> Result<Vec<u8>, CertificateMergeError> {
        self.serialize(
            true,
            SerializationPurpose::Transferable,
            &BTreeSet::new(),
            &mut ExportClassificationBudget::default(),
        )
        .map(|(bytes, _)| bytes)
    }

    /// Serializes the certificate in RFC 9580 §10.1 order.
    ///
    /// `include_unknown_components` selects between the complete bytes and the
    /// subset the composed parser interprets. `purpose` selects retained local
    /// evidence or ordinary transferable output; every combination uses this
    /// one placement and ordering path.
    pub(super) fn serialize(
        &self,
        include_unknown_components: bool,
        purpose: SerializationPurpose,
        authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
        budget: &mut ExportClassificationBudget,
    ) -> Result<(Vec<u8>, BTreeSet<CanonicalPacket>), CertificateMergeError> {
        budget.begin_certificate(&self.fingerprint);
        let primary = parse_primary_key(&self.primary)?;
        let transferable = matches!(purpose, SerializationPurpose::Transferable)
            .then(|| {
                self.transferable_components(&primary, authenticated_sensitive_declarations, budget)
            })
            .transpose()?;
        let mut output = Vec::new();
        let mut withheld_packet_keys = BTreeSet::new();
        if transferable
            .as_ref()
            .is_some_and(|components| !components.certificate)
        {
            return Ok((output, withheld_packet_keys));
        }
        self.primary.write_to(&mut output)?;
        self.direct.write_to(
            &mut output,
            AttachedPacketOwner::Direct,
            purpose,
            authenticated_sensitive_declarations,
            &mut withheld_packet_keys,
        )?;
        if include_unknown_components {
            // A quarantined signature cannot be moved behind an identity or
            // subkey without assigning it there syntactically. Retained and
            // local-public views keep it in the only neutral canonical
            // position: after direct signatures, before components. Ordinary
            // transferable output must still follow RFC 9580 §10.1, where a
            // certification has no legal slot before its identity.
            for unknown in self
                .unknown_order
                .iter()
                .filter(|unknown| unknown.tag == SIGNATURE_TAG)
            {
                self.write_unknown_component(
                    unknown,
                    &mut output,
                    purpose,
                    authenticated_sensitive_declarations,
                    &mut withheld_packet_keys,
                )?;
            }
        }
        for identity in &self.identity_order {
            let attached = self
                .identities
                .get(identity)
                .ok_or(CertificateMergeError::Internal)?;
            if transferable
                .as_ref()
                .is_some_and(|components| !components.identities.contains(identity))
            {
                withheld_packet_keys.insert(identity.clone());
                attached.withhold_all(&mut withheld_packet_keys)?;
                continue;
            }
            identity.write_to(&mut output)?;
            attached.write_to(
                &mut output,
                AttachedPacketOwner::Identity,
                purpose,
                authenticated_sensitive_declarations,
                &mut withheld_packet_keys,
            )?;
        }
        for fingerprint in &self.subkey_order {
            let component = self
                .subkeys
                .get(fingerprint)
                .ok_or(CertificateMergeError::Internal)?;
            if transferable
                .as_ref()
                .is_some_and(|components| !components.subkeys.contains(fingerprint))
            {
                withheld_packet_keys.insert(component.packet.clone());
                component.attached.withhold_all(&mut withheld_packet_keys)?;
                continue;
            }
            component.write_to(
                &mut output,
                purpose,
                authenticated_sensitive_declarations,
                &mut withheld_packet_keys,
            )?;
        }
        if include_unknown_components {
            for unknown in self
                .unknown_order
                .iter()
                .filter(|unknown| unknown.tag != SIGNATURE_TAG)
            {
                self.write_unknown_component(
                    unknown,
                    &mut output,
                    purpose,
                    authenticated_sensitive_declarations,
                    &mut withheld_packet_keys,
                )?;
            }
        }
        Ok((output, withheld_packet_keys))
    }

    fn write_unknown_component(
        &self,
        unknown: &CanonicalPacket,
        output: &mut Vec<u8>,
        purpose: SerializationPurpose,
        authenticated_sensitive_declarations: &BTreeSet<CanonicalPacket>,
        withheld_packet_keys: &mut BTreeSet<CanonicalPacket>,
    ) -> Result<(), CertificateMergeError> {
        let attached = self
            .unknowns
            .get(unknown)
            .ok_or(CertificateMergeError::Internal)?;
        if matches!(purpose, SerializationPurpose::Transferable)
            && is_certification_signature(unknown)
        {
            withheld_packet_keys.insert(attached_packet_key(unknown)?);
            attached.withhold_all(withheld_packet_keys)?;
            return Ok(());
        }
        unknown.write_to(output)?;
        attached.write_to(
            output,
            AttachedPacketOwner::Opaque,
            purpose,
            authenticated_sensitive_declarations,
            withheld_packet_keys,
        )
    }

    /// Derives the retained policy view and ordinary transferable bytes from
    /// the same packet set and canonical ordering.
    pub(crate) fn finalize(&self) -> Result<CanonicalCertificate, CertificateMergeError> {
        self.finalize_with_revocation_candidates(&[])
    }

    /// Finalizes with exact public-key candidates that may authenticate the
    /// RFC 9580 sensitive-designated-revoker export exception.
    pub(crate) fn finalize_with_revocation_candidates(
        &self,
        candidates: &[PublicComponent],
    ) -> Result<CanonicalCertificate, CertificateMergeError> {
        self.finalize_with_export_budget(candidates, &mut ExportClassificationBudget::default())
    }

    /// Finalizes while sharing export-classification accounting with every
    /// other certificate in the request, independently of policy evaluation.
    pub(crate) fn finalize_with_export_budget(
        &self,
        candidates: &[PublicComponent],
        budget: &mut ExportClassificationBudget,
    ) -> Result<CanonicalCertificate, CertificateMergeError> {
        self.validate_shape()?;
        budget.begin_certificate(&self.fingerprint);
        let primary = parse_primary_key(&self.primary)?;
        let authenticated_sensitive_declarations =
            self.authenticated_sensitive_declarations(&primary, candidates, budget)?;
        let (bytes, withheld_packet_keys) = self.serialize(
            true,
            SerializationPurpose::Transferable,
            &authenticated_sensitive_declarations,
            budget,
        )?;
        let transferable = !bytes.is_empty();
        let (local_public_bytes, local_public_withheld_packet_keys) = self.serialize(
            true,
            SerializationPurpose::LocalPublic,
            &authenticated_sensitive_declarations,
            budget,
        )?;
        let retained_bytes = self
            .serialize(
                true,
                SerializationPurpose::Retained,
                &BTreeSet::new(),
                budget,
            )?
            .0;
        let semantic_bytes = if self.unknowns.is_empty() {
            Cow::Borrowed(retained_bytes.as_slice())
        } else {
            Cow::Owned(
                self.serialize(
                    false,
                    SerializationPurpose::Retained,
                    &BTreeSet::new(),
                    budget,
                )?
                .0,
            )
        };
        let (semantic, _) =
            SignedPublicKey::from_reader_single(Cursor::new(semantic_bytes.as_ref()))
                .map_err(|_| CertificateMergeError::Malformed)?;
        if FingerprintKey::from_key(&semantic.primary_key)? != self.fingerprint {
            return Err(CertificateMergeError::ComponentCollision);
        }
        drop(semantic_bytes);
        let components = self.public_components()?;
        let identities = self
            .identity_order
            .iter()
            .map(|identity| CanonicalIdentityPacket {
                tag: identity.tag,
                body: identity.body.clone(),
            })
            .collect::<Vec<_>>();
        Ok(CanonicalCertificate {
            transferable,
            bytes,
            local_public_bytes,
            retained_bytes,
            fingerprint: self.fingerprint.upper_hex(),
            semantic,
            components,
            signature_count: self.signature_count(),
            identities,
            withheld_packet_keys,
            local_public_withheld_packet_keys,
        })
    }

    pub(crate) fn fingerprint_hex(&self) -> String {
        self.fingerprint.upper_hex()
    }

    pub(super) fn signature_count(&self) -> usize {
        self.attached_packets()
            .filter(|packet| packet.tag == SIGNATURE_TAG)
            .count()
            .saturating_add(
                self.unknowns
                    .keys()
                    .filter(|packet| packet.tag == SIGNATURE_TAG)
                    .count(),
            )
    }

    pub(super) fn validate_shape(&self) -> Result<(), CertificateMergeError> {
        if self
            .subkeys
            .len()
            .saturating_add(self.unknowns.len())
            .saturating_add(1)
            > MAX_COMPONENTS
            || self.identities.len() > MAX_IDENTITIES
            || self.signature_count() > MAX_SIGNATURES
            || signature_count(&self.direct) > MAX_SIGNATURES_PER_OBJECT
            || self
                .identities
                .values()
                .any(|attached| signature_count(attached) > MAX_SIGNATURES_PER_OBJECT)
            || self
                .subkeys
                .values()
                .any(|component| signature_count(&component.attached) > MAX_SIGNATURES_PER_OBJECT)
            || self
                .unknowns
                .values()
                .any(|attached| signature_count(attached) > MAX_SIGNATURES_PER_OBJECT)
        {
            return Err(CertificateMergeError::ResourceLimit);
        }
        Ok(())
    }
}

/// Returns whether an otherwise quarantined signature can only certify an
/// identity component.
///
/// Certification Revocations are excluded: when they occur directly after
/// the primary key, RFC 9580 permits them to revoke a Direct Key signature and
/// parsing therefore gives them a direct owner instead of quarantining them.
fn is_certification_signature(packet: &CanonicalPacket) -> bool {
    if packet.tag != SIGNATURE_TAG {
        return false;
    }
    parse_signature_packet(packet).is_ok_and(|signature| {
        matches!(
            signature.typ(),
            Some(
                SignatureType::CertGeneric
                    | SignatureType::CertPersona
                    | SignatureType::CertCasual
                    | SignatureType::CertPositive
            )
        )
    })
}
