//! Message signing workflows.
//!
//! Detached, cleartext, and inline signing share the same policy-qualified
//! secret-key selection and signature configuration.

use super::*;

/// Signs bounded content with a policy-valid primary key or signing subkey.
pub(in crate::openpgp) fn sign_request(request: SignInput) -> Result<Vec<u8>, OpenPgpWriteError> {
    if request.content.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    if request
        .signature_time_epoch_seconds
        .is_some_and(|value| value > u64::from(u32::MAX))
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }

    match request.kind {
        SignKind::Unspecified => Err(OpenPgpWriteError::InvalidArgument),
        SignKind::Detached => {
            let mut session = DetachedSigningSession::open(DetachedSignInput {
                private_key: request.private_key,
                preferred_fingerprint: request.preferred_fingerprint,
                armored: request.armored,
                signature_time_epoch_seconds: request.signature_time_epoch_seconds,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
                candidate_revocation_keys: request.candidate_revocation_keys,
            })?;
            session.update(request.content.as_slice())?;
            session.finish()
        }
        SignKind::ClearText => {
            let mut session = ClearSigningSession::open(ClearSignInput {
                private_key: request.private_key,
                preferred_fingerprint: request.preferred_fingerprint,
                signature_time_epoch_seconds: request.signature_time_epoch_seconds,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
                candidate_revocation_keys: request.candidate_revocation_keys,
            })?;
            let mut output = session.update(request.content.as_slice())?;
            output.extend_from_slice(&session.finish()?);
            Ok(output)
        }
    }
}

pub(super) struct PreparedSigningKey {
    secret: ParsedSecretCertificate,
    selection: SecretPacketSelection,
}

impl PreparedSigningKey {
    pub(super) fn packet(&self) -> Result<SecretPacketRef<'_>, OpenPgpWriteError> {
        Ok(self
            .selection
            .packet(self.secret.primary(), self.secret.subkeys())?)
    }
}

pub(super) fn prepare_signing_key(
    private_key: Zeroizing<Vec<u8>>,
    preferred_fingerprint: &str,
    policy_time: u64,
    revocation_candidates: &[SignedPublicKey],
) -> Result<PreparedSigningKey, OpenPgpWriteError> {
    let secret = parse_private_certificate(private_key.as_slice())?;
    let packet = select_signing_packet(
        &secret,
        preferred_fingerprint,
        policy_time,
        revocation_candidates,
    )?;
    let selection = SecretPacketSelection::from_ref(secret.primary(), secret.subkeys(), packet)?;
    Ok(PreparedSigningKey { secret, selection })
}

/// Incremental detached signer. Only the selected hash state grows with input; key
/// material and output remain bounded by the stream-open control envelope.
pub(crate) struct DetachedSigningSession {
    signing: PreparedSigningKey,
    hasher: SignatureHasher,
    armored: bool,
}

impl DetachedSigningSession {
    pub(in crate::openpgp) fn open(request: DetachedSignInput) -> Result<Self, OpenPgpWriteError> {
        if request.private_key.is_empty()
            || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
            || request
                .signature_time_epoch_seconds
                .is_some_and(|value| value > u64::from(u32::MAX))
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        let revocation_candidates = parse_policy_candidates(&request.candidate_revocation_keys)?;
        let signing = prepare_signing_key(
            request.private_key,
            &request.preferred_fingerprint,
            reference_time(request.reference_time_epoch_seconds),
            &revocation_candidates,
        )?;
        let signature_time = request
            .signature_time_epoch_seconds
            .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
        let packet = signing.packet()?;
        let (hasher, _, _) =
            detached_signature_hasher(packet, signature_time, SignatureType::Binary)?;
        Ok(Self {
            signing,
            hasher,
            armored: request.armored,
        })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<(), OpenPgpWriteError> {
        self.hasher
            .write_all(data)
            .map_err(|_| OpenPgpWriteError::Internal)
    }

    pub(in crate::openpgp) fn finish(self) -> Result<Vec<u8>, OpenPgpWriteError> {
        let packet = self.signing.packet()?;
        let signature = sign_hasher_with_packet(self.hasher, packet)?;
        if self.armored {
            armor_signature(signature)
        } else {
            DetachedSignature::new(signature)
                .to_bytes()
                .map_err(|_| OpenPgpWriteError::Internal)
        }
    }
}

pub(super) fn detached_signature_hasher(
    packet: SecretPacketRef<'_>,
    signature_time: Timestamp,
    signature_type: SignatureType,
) -> Result<(SignatureHasher, HashAlgorithm, SignatureVersion), OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        signature_hasher_with_key(&adapter, signature_time, signature_type)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => {
                signature_hasher_with_key(key, signature_time, signature_type)
            }
            SecretPacketRef::Subkey(key) => {
                signature_hasher_with_key(key, signature_time, signature_type)
            }
        }
    }
}

pub(super) fn sign_hasher_with_packet(
    hasher: SignatureHasher,
    packet: SecretPacketRef<'_>,
) -> Result<pgp::packet::Signature, OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        hasher.sign(&adapter, &Password::empty())
    } else {
        match packet {
            SecretPacketRef::Primary(key) => hasher.sign(key, &Password::empty()),
            SecretPacketRef::Subkey(key) => hasher.sign(key, &Password::empty()),
        }
    }
    .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

pub(super) fn signature_hasher_with_key(
    key: &impl SigningKey,
    signature_time: Timestamp,
    signature_type: SignatureType,
) -> Result<(SignatureHasher, HashAlgorithm, SignatureVersion), OpenPgpWriteError> {
    let mut config = data_signature_config(key, signature_type)?;
    let signature_version = config.version();
    let hash_algorithm = config.hash_alg;
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(key, signature_time)?
    else {
        return Err(OpenPgpWriteError::Internal);
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    let hasher = config
        .into_hasher()
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok((hasher, hash_algorithm, signature_version))
}

/// Forms an RFC 9580 cleartext-signature preamble. The deprecated `Hash`
/// header is retained only for the GnuPG-compatible V4 SHA2 profile.
pub(super) fn cleartext_signature_header(
    signature_version: SignatureVersion,
    hash_algorithm: HashAlgorithm,
) -> Vec<u8> {
    if signature_version == SignatureVersion::V4
        && matches!(
            hash_algorithm,
            HashAlgorithm::Sha224
                | HashAlgorithm::Sha256
                | HashAlgorithm::Sha384
                | HashAlgorithm::Sha512
        )
    {
        format!("-----BEGIN PGP SIGNED MESSAGE-----\nHash: {hash_algorithm}\n\n").into_bytes()
    } else {
        b"-----BEGIN PGP SIGNED MESSAGE-----\n\n".to_vec()
    }
}

/// Incremental cleartext signer. Only up to 64 KiB of trailing horizontal
/// whitespace and an incomplete UTF-8 code point are retained between chunks.
pub(crate) struct ClearSigningSession {
    pub(super) signing: PreparedSigningKey,
    pub(super) hasher: SignatureHasher,
    pub(super) header: Vec<u8>,
    pub(super) pending_whitespace: Zeroizing<Vec<u8>>,
    pub(super) utf8_tail: Zeroizing<Vec<u8>>,
    pub(super) started: bool,
    pub(super) line_start: bool,
    pub(super) canonical_needs_break: bool,
    pub(super) previous_input_was_cr: bool,
    pub(super) output_ended_with_line_break: bool,
}

impl ClearSigningSession {
    pub(in crate::openpgp) fn open(request: ClearSignInput) -> Result<Self, OpenPgpWriteError> {
        if request.private_key.is_empty()
            || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
            || request
                .signature_time_epoch_seconds
                .is_some_and(|value| value > u64::from(u32::MAX))
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        let revocation_candidates = parse_policy_candidates(&request.candidate_revocation_keys)?;
        let signing = prepare_signing_key(
            request.private_key,
            &request.preferred_fingerprint,
            reference_time(request.reference_time_epoch_seconds),
            &revocation_candidates,
        )?;
        let signature_time = request
            .signature_time_epoch_seconds
            .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
        let packet = signing.packet()?;
        let (hasher, hash_algorithm, signature_version) =
            detached_signature_hasher(packet, signature_time, SignatureType::Text)?;
        Ok(Self {
            signing,
            hasher,
            header: cleartext_signature_header(signature_version, hash_algorithm),
            pending_whitespace: Zeroizing::new(Vec::with_capacity(
                MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES,
            )),
            utf8_tail: Zeroizing::new(Vec::with_capacity(4)),
            started: false,
            line_start: true,
            canonical_needs_break: false,
            previous_input_was_cr: false,
            output_ended_with_line_break: false,
        })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.validate_pending_whitespace_run(data)?;
        self.validate_utf8(data)?;
        let header_length = if self.started { 0 } else { self.header.len() };
        let output_len = self.escaped_output_len(data, header_length)?;
        let mut output = Zeroizing::new(Vec::new());
        output
            .try_reserve_exact(output_len)
            .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
        if !self.started {
            FixedCapacityWriter(&mut output)
                .write_all(&self.header)
                .map_err(|_| OpenPgpWriteError::Internal)?;
            self.started = true;
        }
        // Canonical bytes are hashed as contiguous `data` runs rather than one
        // byte at a time. Whitespace stays provisional until a later
        // non-whitespace byte on the same line confirms it; only whitespace
        // that is still provisional at the end of the chunk is copied into
        // `pending_whitespace`.
        let mut hash_run: Option<(usize, usize)> = None;
        let mut whitespace_start: Option<usize> = None;
        for (index, &byte) in data.iter().enumerate() {
            if self.previous_input_was_cr && byte == b'\n' {
                FixedCapacityWriter(&mut output)
                    .write_all(&[byte])
                    .map_err(|_| OpenPgpWriteError::Internal)?;
                self.previous_input_was_cr = false;
                self.line_start = true;
                self.output_ended_with_line_break = true;
                continue;
            }
            self.previous_input_was_cr = false;
            if matches!(byte, b'\r' | b'\n') {
                self.hash_canonical_run(data, hash_run.take())?;
                whitespace_start = None;
                if self.canonical_needs_break {
                    self.hasher
                        .write_all(b"\r\n")
                        .map_err(|_| OpenPgpWriteError::Internal)?;
                }
                self.pending_whitespace.clear();
                self.canonical_needs_break = true;
                self.previous_input_was_cr = byte == b'\r';
                self.line_start = true;
                self.output_ended_with_line_break = true;
                FixedCapacityWriter(&mut output)
                    .write_all(&[byte])
                    .map_err(|_| OpenPgpWriteError::Internal)?;
                continue;
            }
            if self.canonical_needs_break {
                self.hasher
                    .write_all(b"\r\n")
                    .map_err(|_| OpenPgpWriteError::Internal)?;
                self.canonical_needs_break = false;
            }
            if matches!(byte, b' ' | b'\t') {
                whitespace_start.get_or_insert(index);
            } else {
                // Carried-over whitespace can only precede the first confirmed
                // byte of this chunk, so the run is necessarily empty here.
                if !self.pending_whitespace.is_empty() {
                    self.hasher
                        .write_all(self.pending_whitespace.as_slice())
                        .map_err(|_| OpenPgpWriteError::Internal)?;
                    self.pending_whitespace.clear();
                }
                match hash_run.as_mut() {
                    Some((_, end)) => *end = index + 1,
                    None => hash_run = Some((whitespace_start.unwrap_or(index), index + 1)),
                }
                whitespace_start = None;
            }
            if self.line_start && byte == b'-' {
                FixedCapacityWriter(&mut output)
                    .write_all(b"- ")
                    .map_err(|_| OpenPgpWriteError::Internal)?;
            }
            FixedCapacityWriter(&mut output)
                .write_all(&[byte])
                .map_err(|_| OpenPgpWriteError::Internal)?;
            self.line_start = false;
            self.output_ended_with_line_break = false;
        }
        self.hash_canonical_run(data, hash_run)?;
        if let Some(start) = whitespace_start {
            self.pending_whitespace.extend_from_slice(&data[start..]);
        }
        if output.len() != output_len {
            return Err(OpenPgpWriteError::Internal);
        }
        Ok(std::mem::take(&mut *output))
    }

    pub(in crate::openpgp) fn finish(mut self) -> Result<Vec<u8>, OpenPgpWriteError> {
        if !self.utf8_tail.is_empty() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        self.pending_whitespace.clear();
        let packet = self.signing.packet()?;
        let signature = sign_hasher_with_packet(self.hasher, packet)?;
        let armored = armor_signature(signature)?;
        let header_length = if self.started { 0 } else { self.header.len() };
        let mut output = Vec::with_capacity(armored.len() + header_length + 1);
        if !self.started {
            output.extend_from_slice(&self.header);
        }
        if !self.output_ended_with_line_break {
            output.push(b'\n');
        }
        output.extend_from_slice(&armored);
        Ok(output)
    }

    pub(super) fn hash_canonical_run(
        &mut self,
        data: &[u8],
        run: Option<(usize, usize)>,
    ) -> Result<(), OpenPgpWriteError> {
        if let Some((start, end)) = run {
            self.hasher
                .write_all(&data[start..end])
                .map_err(|_| OpenPgpWriteError::Internal)?;
        }
        Ok(())
    }

    pub(super) fn escaped_output_len(
        &self,
        data: &[u8],
        header_length: usize,
    ) -> Result<usize, OpenPgpWriteError> {
        let mut line_start = self.line_start;
        let mut previous_input_was_cr = self.previous_input_was_cr;
        let mut escaped_dashes = 0_usize;
        for &byte in data {
            if previous_input_was_cr && byte == b'\n' {
                previous_input_was_cr = false;
                line_start = true;
                continue;
            }
            previous_input_was_cr = false;
            if matches!(byte, b'\r' | b'\n') {
                previous_input_was_cr = byte == b'\r';
                line_start = true;
            } else {
                if line_start && byte == b'-' {
                    escaped_dashes = escaped_dashes
                        .checked_add(2)
                        .ok_or(OpenPgpWriteError::ResourceLimit)?;
                }
                line_start = false;
            }
        }
        header_length
            .checked_add(data.len())
            .and_then(|length| length.checked_add(escaped_dashes))
            .ok_or(OpenPgpWriteError::ResourceLimit)
    }

    pub(super) fn validate_pending_whitespace_run(
        &self,
        data: &[u8],
    ) -> Result<(), OpenPgpWriteError> {
        let mut pending = self.pending_whitespace.len();
        for &byte in data {
            if matches!(byte, b' ' | b'\t') {
                pending = pending
                    .checked_add(1)
                    .filter(|length| *length <= MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES)
                    .ok_or(OpenPgpWriteError::ResourceLimit)?;
            } else {
                pending = 0;
            }
        }
        Ok(())
    }

    pub(super) fn validate_utf8(&mut self, data: &[u8]) -> Result<(), OpenPgpWriteError> {
        let mut remaining = data;
        if !self.utf8_tail.is_empty() {
            // The retained tail is a valid prefix of a single code point, so
            // its lead byte determines the full sequence length. Completing it
            // needs at most three bytes; the chunk itself is validated
            // borrowed, without copying it.
            let sequence_length = match self.utf8_tail[0] {
                byte if byte >= 0xF0 => 4,
                byte if byte >= 0xE0 => 3,
                _ => 2,
            };
            let needed = sequence_length - self.utf8_tail.len();
            if remaining.len() < needed {
                self.utf8_tail.extend_from_slice(remaining);
                return Ok(());
            }
            let (head, rest) = remaining.split_at(needed);
            let mut sequence = Zeroizing::new([0_u8; 4]);
            sequence[..self.utf8_tail.len()].copy_from_slice(&self.utf8_tail);
            sequence[self.utf8_tail.len()..sequence_length].copy_from_slice(head);
            if std::str::from_utf8(&sequence[..sequence_length]).is_err() {
                return Err(OpenPgpWriteError::InvalidArgument);
            }
            self.utf8_tail.clear();
            remaining = rest;
        }
        match std::str::from_utf8(remaining) {
            Ok(_) => {}
            Err(error) if error.error_len().is_none() => {
                self.utf8_tail
                    .extend_from_slice(&remaining[error.valid_up_to()..]);
            }
            Err(_) => return Err(OpenPgpWriteError::InvalidArgument),
        }
        Ok(())
    }
}

pub(super) fn armor_signature(
    signature: pgp::packet::Signature,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let mut options = ArmorOptions::default();
    options.include_checksum = signature_include_checksum(
        std::iter::once(signature.version()),
        options.include_checksum,
    )
    .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    DetachedSignature::new(signature)
        .to_armored_bytes(options)
        .map_err(|_| OpenPgpWriteError::Internal)
}

pub(super) fn select_signing_packet<'a>(
    secret: &'a ParsedSecretCertificate,
    preferred_fingerprint: &str,
    reference_time: u64,
    revocation_candidates: &[SignedPublicKey],
) -> Result<SecretPacketRef<'a>, OpenPgpWriteError> {
    let public = secret.public();
    let mut candidates = all_components(std::slice::from_ref(public));
    candidates.extend(all_components(revocation_candidates));
    let mut budget = OpenPgpPolicyBudget::default();
    let policy = validate_certificate(public, &candidates, reference_time, &mut budget)
        .map_err(map_policy_error)?;
    if !policy.primary_available() {
        return Err(OpenPgpWriteError::MissingKey);
    }

    let primary_usable = policy.primary_component().signing_usable();
    let usable_subkeys = secret
        .subkeys()
        .iter()
        .enumerate()
        .filter_map(|(index, secret_subkey)| {
            policy
                .subkeys_matching(secret_subkey)
                .any(|component| component.signing_usable())
                .then_some((
                    index,
                    secret_subkey.created_at().as_secs(),
                    fingerprint_hex(secret_subkey),
                ))
        })
        .collect::<Vec<_>>();

    let preferred = normalize_fingerprint(preferred_fingerprint);
    let newest_subkey = usable_subkeys
        .iter()
        .max_by_key(|(_, created_at, fingerprint)| (*created_at, fingerprint.clone()))
        .map(|(index, _, _)| *index);
    if !preferred_fingerprint.is_empty() {
        if preferred.is_empty() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        // Preferring the primary selects from the whole certificate, exactly
        // like the no-preference fallback below.
        if fingerprint_hex(&public.primary_key) != preferred {
            let index = usable_subkeys
                .iter()
                .find_map(|(index, _, fingerprint)| (fingerprint == &preferred).then_some(*index))
                .ok_or(OpenPgpWriteError::MissingKey)?;
            return Ok(SecretPacketRef::Subkey(&secret.subkeys()[index]));
        }
    }

    if let Some(index) = newest_subkey {
        return Ok(SecretPacketRef::Subkey(&secret.subkeys()[index]));
    }
    secret
        .primary()
        .filter(|_| primary_usable)
        .map(SecretPacketRef::Primary)
        .ok_or(OpenPgpWriteError::MissingKey)
}

pub(super) fn signing_subpackets(
    key: &(impl SigningKey + ?Sized),
    signature_time: Timestamp,
) -> Result<SubpacketConfig, OpenPgpWriteError> {
    let hashed = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(signature_time))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(key.fingerprint()))
            .map_err(pgp_internal)?,
    ];
    let unhashed = if key.version() <= KeyVersion::V4 {
        vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(key.legacy_key_id()))
                .map_err(pgp_internal)?,
        ]
    } else {
        Vec::new()
    };
    Ok(SubpacketConfig::UserDefined { hashed, unhashed })
}

pub(super) fn data_signature_config(
    key: &(impl SigningKey + ?Sized),
    typ: SignatureType,
) -> Result<SignatureConfig, OpenPgpWriteError> {
    let hash_algorithm =
        select_signature_hash(key.algorithm(), key.hash_alg(), HashAlgorithm::Sha256)
            .ok_or(OpenPgpWriteError::CryptoFailure)?;
    match key.version() {
        KeyVersion::V4 => Ok(SignatureConfig::v4(typ, key.algorithm(), hash_algorithm)),
        KeyVersion::V6 => SignatureConfig::v6(AwsLcRng, typ, key.algorithm(), hash_algorithm)
            .map_err(|_| OpenPgpWriteError::CryptoFailure),
        _ => Err(OpenPgpWriteError::InvalidArgument),
    }
}
