//! Canonical certificate merging and packet-level mutation.

use super::*;

/// Unions packet evidence by full primary fingerprint, preserving the order in
/// which each certificate was first seen.
pub(crate) fn merge_public_certificate_packet_sets(
    certificates: Vec<PublicCertificatePacketSet>,
    max_certificates: usize,
) -> Result<Vec<PublicCertificatePacketSet>, CertificateMergeError> {
    let mut order = Vec::<FingerprintKey>::new();
    let mut merged = BTreeMap::<FingerprintKey, PublicCertificatePacketSet>::new();
    for certificate in certificates {
        if let Some(existing) = merged.get_mut(&certificate.fingerprint) {
            existing.merge(certificate)?;
        } else {
            if merged.len() >= max_certificates {
                return Err(CertificateMergeError::ResourceLimit);
            }
            order.push(certificate.fingerprint.clone());
            merged.insert(certificate.fingerprint.clone(), certificate);
        }
    }
    order
        .into_iter()
        .map(|fingerprint| {
            let certificate = merged
                .remove(&fingerprint)
                .ok_or(CertificateMergeError::Internal)?;
            certificate.validate_shape()?;
            Ok(certificate)
        })
        .collect()
}

/// Canonicalizes one public certificate to ordinary transferable bytes.
#[cfg(test)]
pub(crate) fn canonicalize_public_certificate(
    data: &[u8],
) -> Result<(Vec<u8>, String), CertificateMergeError> {
    let canonical = canonicalize_public_certificate_material(data)?;
    Ok((canonical.bytes, canonical.fingerprint))
}

/// Canonicalizes one public certificate into its retained local and ordinary
/// transferable views.
pub(crate) fn canonicalize_public_certificate_material(
    data: &[u8],
) -> Result<CanonicalCertificate, CertificateMergeError> {
    let mut rehoming_budget = SignatureRehomingBudget::default();
    validate_single_certificate(data, &mut rehoming_budget)?.finalize()
}

/// Canonically unions public certificate documents into transferable bytes.
#[cfg(test)]
pub(crate) fn merge_public_certificate_documents(
    documents: &[&[u8]],
) -> Result<(Vec<u8>, String), CertificateMergeError> {
    let canonical = merge_public_certificate_material_documents(documents)?;
    Ok((canonical.bytes, canonical.fingerprint))
}

/// Canonically unions public certificate documents while retaining the local
/// evidence that ordinary transferable export must omit.
pub(crate) fn merge_public_certificate_material_documents(
    documents: &[&[u8]],
) -> Result<CanonicalCertificate, CertificateMergeError> {
    let mut rehoming_budget = SignatureRehomingBudget::default();
    let mut values = documents
        .iter()
        .map(|document| validate_single_certificate(document, &mut rehoming_budget))
        .collect::<Result<Vec<_>, _>>()?;
    if values.is_empty() {
        return Err(CertificateMergeError::Malformed);
    }
    // Resource caps are enforced per document by `validate_single_certificate`
    // and on the deduplicated union by `finalize`; summing per-document counts
    // against the per-certificate caps here would reject legitimate merges of
    // near-cap duplicates, such as a certificate reconciled with its own
    // secret projection.
    let mut merged = values.remove(0);
    let fingerprint = merged.fingerprint.clone();
    for value in values {
        if value.fingerprint != fingerprint {
            return Err(CertificateMergeError::ComponentCollision);
        }
        merged.merge(value)?;
    }
    merged.finalize()
}

fn validate_single_certificate(
    data: &[u8],
    budget: &mut SignatureRehomingBudget,
) -> Result<PublicCertificatePacketSet, CertificateMergeError> {
    let (_, certificate) = parsing::parse_single_certificate_with_stream_and_budget(data, budget)?;
    Ok(certificate)
}

pub(crate) fn normalize_expected_fingerprint(value: &str) -> Option<String> {
    let mut normalized = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_hexdigit() {
            normalized.push(char::from(byte.to_ascii_uppercase()));
        } else if !matches!(byte, b' ' | b'\t' | b'\r' | b'\n' | b':' | b'-') {
            return None;
        }
    }
    (!normalized.is_empty()).then_some(normalized)
}

impl PublicCertificatePacketSet {
    /// Unions `other` into `self` and reports whether anything changed.
    pub(crate) fn merge(&mut self, other: Self) -> Result<bool, CertificateMergeError> {
        if self.fingerprint != other.fingerprint {
            return Err(CertificateMergeError::ComponentCollision);
        }
        if self.primary != other.primary {
            return Err(CertificateMergeError::ComponentCollision);
        }
        let mut changed = false;
        changed |= self.direct.merge(other.direct)?;
        let mut identities = other.identities;
        for identity in other.identity_order {
            let Some(signatures) = identities.remove(&identity) else {
                return Err(CertificateMergeError::Internal);
            };
            match self.identities.get_mut(&identity) {
                Some(existing) => changed |= existing.merge(signatures)?,
                None => {
                    changed = true;
                    self.identity_order.push(identity.clone());
                    self.identities.insert(identity, signatures);
                }
            }
        }
        let mut subkeys = other.subkeys;
        for fingerprint in other.subkey_order {
            let Some(component) = subkeys.remove(&fingerprint) else {
                return Err(CertificateMergeError::Internal);
            };
            match self.subkeys.get_mut(&fingerprint) {
                Some(existing) => {
                    if existing.packet != component.packet {
                        return Err(CertificateMergeError::ComponentCollision);
                    }
                    changed |= existing.attached.merge(component.attached)?;
                }
                None => {
                    changed = true;
                    self.subkey_order.push(fingerprint.clone());
                    self.subkeys.insert(fingerprint, component);
                }
            }
        }
        let mut unknowns = other.unknowns;
        for unknown in other.unknown_order {
            let Some(attached) = unknowns.remove(&unknown) else {
                return Err(CertificateMergeError::Internal);
            };
            match self.unknowns.get_mut(&unknown) {
                Some(existing) => changed |= existing.merge(attached)?,
                None => {
                    changed = true;
                    self.unknown_order.push(unknown.clone());
                    self.unknowns.insert(unknown, attached);
                }
            }
        }
        Ok(changed)
    }

    /// Returns an empty certificate carrying only this primary key identity.
    pub(super) fn empty_shell(&self) -> Self {
        Self {
            fingerprint: self.fingerprint.clone(),
            primary: self.primary.clone(),
            direct: AttachedPackets::default(),
            identities: BTreeMap::new(),
            identity_order: Vec::new(),
            subkeys: BTreeMap::new(),
            subkey_order: Vec::new(),
            unknowns: BTreeMap::new(),
            unknown_order: Vec::new(),
        }
    }

    /// Locates the attached packets of one addressable component.
    pub(super) fn component_packets(
        &mut self,
        owner: &CertificateSignatureOwner,
    ) -> Result<&mut AttachedPackets, CertificateMutationError> {
        match owner {
            CertificateSignatureOwner::Direct => Ok(&mut self.direct),
            CertificateSignatureOwner::Identity { tag, body } => {
                let identity = CanonicalPacket {
                    tag: *tag,
                    body: body.clone(),
                };
                self.identities
                    .get_mut(&identity)
                    .ok_or(CertificateMutationError::TargetNotFound)
            }
            CertificateSignatureOwner::Subkey { fingerprint } => {
                let key = self
                    .subkey_order
                    .iter()
                    .find(|candidate| candidate.bytes == *fingerprint)
                    .cloned()
                    .ok_or(CertificateMutationError::TargetNotFound)?;
                self.subkeys
                    .get_mut(&key)
                    .map(|component| &mut component.attached)
                    .ok_or(CertificateMutationError::Internal)
            }
        }
    }

    /// Replaces exactly one attached signature packet with a fresh one.
    ///
    /// `expected_body` identifies the packet the caller's policy view already
    /// selected. Lookup goes through the same unhashed-normalized identity the
    /// merge deduplicates on, so a packet whose stored unhashed area differs
    /// from the caller's serialization is still the same target — and a target
    /// that is absent fails the whole mutation rather than silently appending.
    pub(crate) fn replace_signature(
        &mut self,
        owner: &CertificateSignatureOwner,
        expected_body: &[u8],
        replacement_body: Vec<u8>,
    ) -> Result<(), CertificateMutationError> {
        signature_type_belongs_to_owner(owner, expected_body, false)?;
        signature_type_belongs_to_owner(owner, &replacement_body, false)?;
        let expected = attached_packet_key(&CanonicalPacket {
            tag: SIGNATURE_TAG,
            body: expected_body.to_vec(),
        })?;
        let replacement = CanonicalPacket {
            tag: SIGNATURE_TAG,
            body: replacement_body,
        };
        let packets = self.component_packets(owner)?;
        packets.replace(&expected, replacement)
    }

    /// Applies every addition, or none of them.
    pub(crate) fn apply_additions(
        &mut self,
        additions: &[CertificateAddition],
    ) -> Result<(), CertificateMutationError> {
        let mut staged = self.clone();
        for addition in additions {
            staged.apply_addition(addition)?;
        }
        staged.validate_shape()?;
        *self = staged;
        Ok(())
    }

    pub(super) fn apply_addition(
        &mut self,
        addition: &CertificateAddition,
    ) -> Result<(), CertificateMutationError> {
        match addition {
            CertificateAddition::Signature { owner, body } => {
                validate_signature_type(owner, body)?;
                let packet = CanonicalPacket {
                    tag: SIGNATURE_TAG,
                    body: body.clone(),
                };
                self.component_packets(owner)?.insert(packet)?;
                Ok(())
            }
            CertificateAddition::Identity {
                tag,
                body,
                signature_bodies,
            } => {
                if !matches!(*tag, USER_ID_TAG | USER_ATTRIBUTE_TAG) || signature_bodies.is_empty()
                {
                    return Err(CertificateMutationError::Malformed);
                }
                let identity = CanonicalPacket {
                    tag: *tag,
                    body: body.clone(),
                };
                if self.identities.contains_key(&identity) {
                    return Err(CertificateMutationError::Malformed);
                }
                let mut attached = AttachedPackets::default();
                for signature in signature_bodies {
                    validate_signature_type(
                        &CertificateSignatureOwner::Identity {
                            tag: *tag,
                            body: body.clone(),
                        },
                        signature,
                    )?;
                    attached.insert(CanonicalPacket {
                        tag: SIGNATURE_TAG,
                        body: signature.clone(),
                    })?;
                }
                self.identity_order.push(identity.clone());
                self.identities.insert(identity, attached);
                Ok(())
            }
        }
    }

    /// Builds the minimal transferable fragment carrying only `additions`.
    ///
    /// Signature additions must name a component this certificate already
    /// has, so a fragment can never publish evidence about something the
    /// signer never held.
    pub(crate) fn fragment(
        &self,
        additions: &[CertificateAddition],
    ) -> Result<Vec<u8>, CertificateMutationError> {
        let mut fragment = self.empty_shell();
        for addition in additions {
            match addition {
                CertificateAddition::Signature { owner, .. } => {
                    self.require_component(owner)?;
                    fragment.ensure_component(owner)?;
                }
                CertificateAddition::Identity { tag, body, .. } => {
                    let identity = CanonicalPacket {
                        tag: *tag,
                        body: body.clone(),
                    };
                    if self.identities.contains_key(&identity) {
                        return Err(CertificateMutationError::Malformed);
                    }
                }
            }
            fragment.apply_addition(addition)?;
        }
        fragment.validate_shape()?;
        Ok(fragment.canonical_bytes()?)
    }

    pub(super) fn require_component(
        &self,
        owner: &CertificateSignatureOwner,
    ) -> Result<(), CertificateMutationError> {
        let present = match owner {
            CertificateSignatureOwner::Direct => true,
            CertificateSignatureOwner::Identity { tag, body } => {
                self.identities.contains_key(&CanonicalPacket {
                    tag: *tag,
                    body: body.clone(),
                })
            }
            CertificateSignatureOwner::Subkey { fingerprint } => self
                .subkey_order
                .iter()
                .any(|candidate| candidate.bytes == *fingerprint),
        };
        present
            .then_some(())
            .ok_or(CertificateMutationError::TargetNotFound)
    }

    /// Copies the addressed component packet out of `source` into this shell.
    pub(super) fn ensure_component(
        &mut self,
        owner: &CertificateSignatureOwner,
    ) -> Result<(), CertificateMutationError> {
        match owner {
            CertificateSignatureOwner::Direct => Ok(()),
            CertificateSignatureOwner::Identity { tag, body } => {
                let identity = CanonicalPacket {
                    tag: *tag,
                    body: body.clone(),
                };
                if !self.identities.contains_key(&identity) {
                    self.identity_order.push(identity.clone());
                    self.identities.insert(identity, AttachedPackets::default());
                }
                Ok(())
            }
            CertificateSignatureOwner::Subkey { .. } => {
                // A subkey fragment would have to republish the subkey packet
                // itself; no mutation needs one, so refuse rather than emit a
                // fragment whose component is missing.
                Err(CertificateMutationError::Malformed)
            }
        }
    }

    /// Returns whether an identity packet with this exact body is retained.
    ///
    /// The composed view omits an identity that carries no signature at all,
    /// so a caller that must not add a duplicate packet has to ask the packet
    /// set rather than the parsed certificate.
    pub(crate) fn has_identity(&self, tag: u8, body: &[u8]) -> bool {
        self.identities.contains_key(&CanonicalPacket {
            tag,
            body: body.to_vec(),
        })
    }

    /// Returns whether every retained public subkey packet carries a valid
    /// primary-issued binding signature.
    ///
    /// Composed parsers may omit unauthenticated subkeys, so this has to run
    /// over the packet set rather than a [`SignedPublicKey`] view.
    pub(crate) fn subkeys_are_bound(&self) -> Result<bool, CertificateMergeError> {
        let primary = parse_primary_key(&self.primary)?;
        if !key_signature_verification_acceptable(&primary) {
            return Ok(false);
        }
        for component in self.subkeys.values() {
            let subkey = parse_public_subkey(&component.packet)?;
            let is_bound = component.attached.values().any(|packet| {
                if packet.tag != SIGNATURE_TAG {
                    return false;
                }
                parse_signature_packet(packet).is_ok_and(|signature| {
                    signature.typ() == Some(SignatureType::SubkeyBinding)
                        && signature_verification_compatible(&signature, &primary)
                        && signature_ignoring_unhashed_issuer_hints(&signature).is_some_and(
                            |signature| signature.verify_subkey_binding(&primary, &subkey).is_ok(),
                        )
                })
            });
            if !is_bound {
                return Ok(false);
            }
        }
        Ok(true)
    }

    pub(super) fn attached_packets(&self) -> impl Iterator<Item = &CanonicalPacket> {
        self.direct
            .values()
            .chain(self.identities.values().flat_map(AttachedPackets::values))
            .chain(
                self.subkeys
                    .values()
                    .flat_map(|component| component.attached.values()),
            )
            .chain(self.unknowns.values().flat_map(AttachedPackets::values))
    }

    /// Returns the packet-derived public keys that may authenticate a
    /// designated revocation in another certificate from the same bounded
    /// request.
    pub(crate) fn public_components(&self) -> Result<Vec<PublicComponent>, CertificateMergeError> {
        let mut components = Vec::with_capacity(self.subkey_order.len() + 1);
        components.push(PublicComponent::Primary(parse_primary_key(&self.primary)?));
        for fingerprint in &self.subkey_order {
            let component = self
                .subkeys
                .get(fingerprint)
                .ok_or(CertificateMergeError::Internal)?;
            components.push(PublicComponent::Subkey(parse_public_subkey(
                &component.packet,
            )?));
        }
        Ok(components)
    }
}
