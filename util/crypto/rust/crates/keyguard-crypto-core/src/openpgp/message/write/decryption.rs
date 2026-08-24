//! One-shot and streaming authenticated OpenPGP decryption workflows.
//!
//! Historical key recovery, authenticated plaintext delivery, and inline
//! verification retain their established ordering and error behavior.

use super::*;
use crate::openpgp::message::VerificationStatus;

pub(super) struct OpenPgpDecryptWorkerConfig {
    secrets: Vec<ParsedSecretCertificate>,
    verification_certificates: Vec<SignedPublicKey>,
    verification_time: DataSignatureVerificationTime,
    allow_signed_only: bool,
}

impl OpenPgpDecryptWorkerFinal {
    fn into_result(self, data: Zeroizing<Vec<u8>>) -> DecryptionResult {
        let Self {
            verification,
            metadata,
            decryption_key_fingerprint,
            declared_charset,
            warnings,
        } = self;
        DecryptionResult {
            data,
            verification,
            metadata,
            encrypted: decryption_key_fingerprint.is_some(),
            declared_charset,
            decryption_key_fingerprint: decryption_key_fingerprint
                .map(|fingerprint| format!("{fingerprint:X}")),
            warnings,
        }
    }
}

fn prepare_decryption(
    request: DecryptStreamInput,
) -> Result<OpenPgpDecryptWorkerConfig, OpenPgpWriteError> {
    if (!request.allow_signed_only && request.private_keys.is_empty())
        || request.private_keys.len() > MAX_OPENPGP_KEYS
        || request.verification_public_keys.len() > MAX_OPENPGP_KEYS
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    if request
        .private_keys
        .iter()
        .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
        || request
            .verification_public_keys
            .iter()
            .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let secrets = parse_secret_key_candidates(&request.private_keys)?;
    let mut budget = OpenPgpReadBudget::default();
    let verification_certificates =
        parse_public_key_documents(&request.verification_public_keys, &mut budget)
            .map_err(map_read_error)?;
    Ok(OpenPgpDecryptWorkerConfig {
        secrets,
        verification_certificates,
        verification_time: DataSignatureVerificationTime::from_reference_time(
            request.reference_time_epoch_seconds,
        ),
        allow_signed_only: request.allow_signed_only,
    })
}

/// Incremental authenticated OpenPGP decryption. AEAD plaintext may be
/// delivered after its chunk tag is verified; SEIPDv1 plaintext is held until
/// the message-wide MDC is verified.
pub(crate) struct OpenPgpDecryptionSession {
    worker: OpenPgpWorkerPipe,
}

impl OpenPgpDecryptionSession {
    pub(in crate::openpgp) fn open(request: DecryptStreamInput) -> Result<Self, OpenPgpWriteError> {
        let config = prepare_decryption(request)?;
        let worker = OpenPgpWorkerPipe::spawn("keyguard-openpgp-decrypt", move |input, output| {
            run_openpgp_decrypt_worker(config, input, output)
        })?;
        Ok(Self { worker })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.worker.update(data)
    }

    pub(in crate::openpgp) fn finish(self) -> Result<DecryptionResult, OpenPgpWriteError> {
        let (data, final_state) = self.worker.finish()?;
        let OpenPgpWorkerFinal::Decrypt(final_state) = final_state else {
            return Err(OpenPgpWriteError::Internal);
        };
        Ok(final_state.into_result(Zeroizing::new(data)))
    }
}

pub(super) fn run_openpgp_decrypt_worker(
    config: OpenPgpDecryptWorkerConfig,
    input: OpenPgpChannelReader,
    output: SyncSender<OpenPgpWorkerOutput>,
) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError> {
    let final_state = process_decryption(
        &config,
        input,
        DecryptionOptions::new()
            .enable_gnupg_aead()
            .set_seipdv1_read_mode(Seipdv1ReadMode::CheckFirst {
                max_message_size: MAX_CONTROL_ENVELOPE_BYTES,
            }),
        |data| {
            output
                .send(OpenPgpWorkerOutput::Data(Zeroizing::new(data.to_vec())))
                .map_err(|_| OpenPgpWriteError::Internal)
        },
    )?;
    Ok(OpenPgpWorkerFinal::Decrypt(Box::new(final_state)))
}

fn process_decryption<'a, R, F>(
    config: &OpenPgpDecryptWorkerConfig,
    input: R,
    decrypt_options: DecryptionOptions,
    mut emit: F,
) -> Result<OpenPgpDecryptWorkerFinal, OpenPgpWriteError>
where
    R: Read + std::fmt::Debug + Send + 'a,
    F: FnMut(&[u8]) -> Result<(), OpenPgpWriteError>,
{
    let (message, armor_headers) = parse_streaming_message(input)?;
    let declared_charset = declared_armor_charset(armor_headers.as_ref());
    let OpenedLiteralMessage {
        mut message,
        decryption_key_fingerprint,
        recipient_identity,
        decryption_warnings,
    } = open_literal_message(
        message,
        &config.secrets,
        config.allow_signed_only,
        decrypt_options,
    )?;
    let encrypted = decryption_key_fingerprint.is_some();
    let mut metadata = literal_metadata(&message)?;

    let mut buffer = Zeroizing::new(vec![0_u8; OPENPGP_PARTIAL_PACKET_BYTES]);
    let mut original_size = 0_u64;
    loop {
        let read = message
            .read(&mut buffer)
            .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?;
        if read == 0 {
            break;
        }
        original_size = original_size
            .checked_add(read as u64)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        emit(&buffer[..read])?;
        buffer[..read].zeroize();
    }
    let verification = finish_inline_verification(
        &message,
        &config.verification_certificates,
        config.verification_time,
        encrypted,
        recipient_identity.as_ref(),
    )?;
    metadata.original_size = original_size;
    Ok(OpenPgpDecryptWorkerFinal {
        verification,
        metadata: Some(metadata),
        decryption_key_fingerprint,
        declared_charset,
        warnings: decryption_warnings,
    })
}

/// Decrypts and authenticates a bounded OpenPGP message. Plaintext remains in
/// zeroizing staging memory until the final MDC/AEAD tag has been consumed.
pub(in crate::openpgp) fn decrypt_request(
    request: DecryptInput,
) -> Result<DecryptionResult, OpenPgpWriteError> {
    if request.content.is_empty() || request.content.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let DecryptInput {
        content,
        private_keys,
        verification_public_keys,
        reference_time_epoch_seconds,
        allow_signed_only,
    } = request;
    let config = prepare_decryption(DecryptStreamInput {
        private_keys,
        verification_public_keys,
        reference_time_epoch_seconds,
        allow_signed_only,
    })?;
    let mut plaintext = SecretChunks::default();
    let final_state = process_decryption(
        &config,
        Cursor::new(content.as_slice()),
        DecryptionOptions::new().enable_gnupg_aead(),
        |data| {
            plaintext
                .push(Zeroizing::new(data.to_vec()), MAX_CONTROL_ENVELOPE_BYTES)
                .map_err(|_| OpenPgpWriteError::ResourceLimit)
        },
    )?;
    let plaintext = plaintext
        .into_zeroizing()
        .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
    Ok(final_state.into_result(plaintext))
}

/// Resolves a parsed message to its literal form, enforcing the signed-only
/// policy: a message that is not encrypted is accepted only when
/// `allow_signed_only` is set and it carries an inline signature. Returns the
/// authenticated literal message and the private component used to open it.
pub(super) struct OpenedLiteralMessage<'a> {
    message: Message<'a>,
    decryption_key_fingerprint: Option<Fingerprint>,
    recipient_identity: Option<RecipientIdentity>,
    decryption_warnings: Vec<DecryptionWarning>,
}

pub(super) fn open_literal_message<'a>(
    message: Message<'a>,
    secrets: &[ParsedSecretCertificate],
    allow_signed_only: bool,
    decrypt_options: DecryptionOptions,
) -> Result<OpenedLiteralMessage<'a>, OpenPgpWriteError> {
    let (message, decryption_key_fingerprint, recipient_identity, decryption_warnings) =
        if message.is_encrypted() {
            let recovered = find_message_session_key(&message, secrets)?;
            let RecoveredSessionKey {
                session_key,
                key_fingerprint,
                recipient_identity,
                warning,
            } = recovered;
            let ring = TheRing {
                session_keys: vec![session_key],
                decrypt_options,
                ..TheRing::default()
            };
            let message = message
                .decrypt_the_ring(ring, true)
                .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?
                .0;
            (
                message,
                Some(key_fingerprint),
                Some(recipient_identity),
                warning.into_iter().collect(),
            )
        } else if allow_signed_only {
            (message, None, None, Vec::new())
        } else {
            return Err(OpenPgpWriteError::InvalidArgument);
        };
    let message = decompress_to_literal(message)?;
    if decryption_key_fingerprint.is_none() && !message.is_signed() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    Ok(OpenedLiteralMessage {
        message,
        decryption_key_fingerprint,
        recipient_identity,
        decryption_warnings,
    })
}

/// Evaluates inline signatures once the literal data has been fully read,
/// rejecting messages accepted under the signed-only policy whose signature
/// did not verify.
pub(super) fn finish_inline_verification(
    message: &Message<'_>,
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    encrypted: bool,
    recipient_identity: Option<&RecipientIdentity>,
) -> Result<Option<Verification>, OpenPgpWriteError> {
    let verification =
        evaluate_inline_verification(message, certificates, verification_time, recipient_identity)?;
    if !encrypted
        && !matches!(
            verification.as_ref().map(|result| result.status),
            Some(VerificationStatus::Valid)
        )
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    Ok(verification)
}

pub(super) fn literal_metadata(
    message: &Message<'_>,
) -> Result<LiteralMetadata, OpenPgpWriteError> {
    let header = message
        .literal_data_header()
        .ok_or(OpenPgpWriteError::InvalidArgument)?;
    Ok(LiteralMetadata {
        file_name: header.file_name().to_vec(),
        format: u32::from(u8::from(header.mode())),
        modification_time_epoch_seconds: u64::from(header.created().as_secs()),
        original_size: 0,
    })
}

pub(super) fn parse_secret_key_candidates(
    inputs: &[Zeroizing<Vec<u8>>],
) -> Result<Vec<ParsedSecretCertificate>, OpenPgpWriteError> {
    if inputs.is_empty() {
        return Ok(Vec::new());
    }
    let mut secrets = Vec::with_capacity(inputs.len());
    let mut unsupported_version = None;
    for input in inputs {
        match parse_private_certificate(input.as_slice()) {
            Ok(secret) => secrets.push(secret),
            Err(OpenPgpWriteError::UnsupportedKeyVersion(version)) => {
                unsupported_version.get_or_insert(version);
            }
            Err(error) => return Err(error),
        }
    }
    if secrets.is_empty() {
        return Err(unsupported_version.map_or(
            OpenPgpWriteError::MissingKey,
            OpenPgpWriteError::UnsupportedKeyVersion,
        ));
    }
    Ok(secrets)
}

pub(super) struct RecoveredSessionKey {
    session_key: PlainSessionKey,
    key_fingerprint: Fingerprint,
    recipient_identity: RecipientIdentity,
    warning: Option<DecryptionWarning>,
}

/// The exact private component that recovered the PKESK together with the
/// public certificate that claims it. Packet containment alone is not trusted:
/// subkey ownership is authenticated later in the data signature's historical
/// certificate view.
pub(super) struct RecipientIdentity {
    certificate: SignedPublicKey,
    component_fingerprint: Fingerprint,
}

#[derive(Debug)]
pub(super) struct PrivateKeyAttemptBudget {
    pub(super) attempts: usize,
    limit: usize,
    pub(super) exhausted: bool,
}

impl PrivateKeyAttemptBudget {
    pub(super) const fn new(limit: usize) -> Self {
        Self {
            attempts: 0,
            limit,
            exhausted: false,
        }
    }

    pub(super) fn consume(&mut self) -> bool {
        if self.attempts >= self.limit {
            self.exhausted = true;
            return false;
        }
        self.attempts += 1;
        true
    }
}

#[derive(Clone, Copy)]
pub(super) struct PrivateKeyCandidate<'a> {
    certificate: &'a ParsedSecretCertificate,
    packet: SecretPacketRef<'a>,
}

#[cfg(test)]
impl RecoveredSessionKey {
    pub(super) fn symmetric_algorithm(&self) -> Option<SymmetricKeyAlgorithm> {
        self.session_key.sym_algorithm()
    }

    pub(super) fn key_fingerprint(&self) -> &Fingerprint {
        &self.key_fingerprint
    }
}

pub(super) fn find_message_session_key(
    message: &Message<'_>,
    secrets: &[ParsedSecretCertificate],
) -> Result<RecoveredSessionKey, OpenPgpWriteError> {
    let mut budget = PrivateKeyAttemptBudget::new(MAX_OPENPGP_PRIVATE_KEY_ATTEMPTS_PER_REQUEST);
    find_message_session_key_with_budget(message, secrets, &mut budget)
}

pub(super) fn find_message_session_key_with_budget(
    message: &Message<'_>,
    secrets: &[ParsedSecretCertificate],
    budget: &mut PrivateKeyAttemptBudget,
) -> Result<RecoveredSessionKey, OpenPgpWriteError> {
    let Message::Encrypted { esk, .. } = message else {
        return Err(OpenPgpWriteError::InvalidArgument);
    };
    if esk.len() > MAX_OPENPGP_KEYS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let candidates = private_key_candidates(secrets)?;
    // Decryption is historical access, not authorization for new data.  Match
    // the PKESK to an exact secret component and its algorithm without applying
    // the component's current binding, expiration, flag, or revocation policy.
    let mut pkcs1_decryption_attempted = false;
    // Exact recipients retain cheap routing and cannot be starved by an
    // attacker placing anonymous PKESKs first. Anonymous recipients are the
    // only packets that require an exhaustive component search.
    for anonymous in [false, true] {
        for encrypted_session_key in esk {
            let Esk::PublicKeyEncryptedSessionKey(pkesk) = encrypted_session_key else {
                continue;
            };
            if is_anonymous_pkesk(pkesk) != anonymous {
                continue;
            }
            let typ = match pkesk.version() {
                PkeskVersion::V3 => EskType::V3_4,
                PkeskVersion::V6 => EskType::V6,
                PkeskVersion::Other(_) => continue,
            };
            let values = pkesk
                .values()
                .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
            for candidate in &candidates {
                let packet = candidate.packet;
                if !pkesk.match_identity(&packet) || !session_key_algorithm_matches(packet, values)
                {
                    continue;
                }
                if !budget.consume() {
                    break;
                }
                pkcs1_decryption_attempted |= uses_pkcs1_session_key_encoding(packet);
                if let Some(session_key) = decrypt_session_key(packet, values, typ) {
                    return Ok(RecoveredSessionKey {
                        session_key,
                        key_fingerprint: packet.fingerprint(),
                        recipient_identity: RecipientIdentity {
                            certificate: candidate.certificate.public().clone(),
                            component_fingerprint: packet.fingerprint(),
                        },
                        warning: decryption_warning(packet),
                    });
                }
            }
            if budget.exhausted {
                break;
            }
        }
        if budget.exhausted {
            break;
        }
    }
    // A truncated search may have skipped the matching component, so it must
    // not masquerade as an authoritative "no key holds this message".
    // RFC 9580 section 13.5 requires PKCS#1 padding, session-key decoding,
    // and encrypted-payload authentication failures to share one public
    // result. Preserve MissingKey only when no matching RSA/ElGamal private
    // operation was attempted, and preserve the bounded-search signal when
    // work was truncated.
    Err(if budget.exhausted {
        OpenPgpWriteError::ResourceLimit
    } else if pkcs1_decryption_attempted {
        OpenPgpWriteError::AuthenticationFailed
    } else {
        OpenPgpWriteError::MissingKey
    })
}

/// Returns the RFC 9580 deprecation warning for a private component that has
/// successfully recovered a message session key. Calling this only on the
/// successful branch prevents failed candidates from leaking into the result.
pub(super) fn decryption_warning(packet: SecretPacketRef<'_>) -> Option<DecryptionWarning> {
    match packet.algorithm() {
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt => {
            let params = packet.public_key().public_params();
            if !matches!(params, PublicParams::RSA(_)) {
                return None;
            }
            let bits = leading_mpi_bits(params)?;
            (bits < 3_072).then_some(DecryptionWarning::WeakRsaKey)
        }
        PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt => {
            Some(DecryptionWarning::ElgamalKey)
        }
        _ => None,
    }
}

pub(super) fn private_key_candidates<'a>(
    secrets: &'a [ParsedSecretCertificate],
) -> Result<Vec<PrivateKeyCandidate<'a>>, OpenPgpWriteError> {
    let capacity = secrets
        .len()
        .checked_mul(MAX_OPENPGP_PRIVATE_COMPONENTS_PER_CERTIFICATE)
        .filter(|value| *value <= MAX_OPENPGP_PRIVATE_KEY_ATTEMPTS_PER_REQUEST)
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    let mut candidates = Vec::new();
    candidates
        .try_reserve_exact(capacity)
        .map_err(|_| OpenPgpWriteError::ResourceLimit)?;

    for secret in secrets {
        if let Some(primary) = secret.primary().map(SecretPacketRef::Primary) {
            candidates.push(PrivateKeyCandidate {
                certificate: secret,
                packet: primary,
            });
        }
        for subkey in secret.subkeys() {
            candidates.push(PrivateKeyCandidate {
                certificate: secret,
                packet: SecretPacketRef::Subkey(subkey),
            });
        }
    }
    Ok(candidates)
}

fn is_anonymous_pkesk(pkesk: &PublicKeyEncryptedSessionKey) -> bool {
    match pkesk {
        PublicKeyEncryptedSessionKey::V3 { id, .. } => id.is_wildcard(),
        PublicKeyEncryptedSessionKey::V6 { fingerprint, .. } => fingerprint.is_none(),
        PublicKeyEncryptedSessionKey::Other { .. } => false,
    }
}

pub(super) fn uses_pkcs1_session_key_encoding(packet: SecretPacketRef<'_>) -> bool {
    matches!(
        packet.algorithm(),
        PublicKeyAlgorithm::RSA
            | PublicKeyAlgorithm::RSAEncrypt
            | PublicKeyAlgorithm::Elgamal
            | PublicKeyAlgorithm::ElgamalEncrypt
    )
}

pub(super) fn authenticated_recipient_primary_fingerprint(
    recipient: &RecipientIdentity,
    certificate_time: u64,
    cryptographic_policy_time: u64,
    budget: &mut OpenPgpPolicyBudget,
) -> Result<Option<Fingerprint>, OpenPgpWriteError> {
    let public = &recipient.certificate;
    let primary_fingerprint = public.primary_key.fingerprint();
    if recipient.component_fingerprint == primary_fingerprint {
        return Ok(Some(primary_fingerprint));
    }
    let Some(subkey) = public
        .public_subkeys
        .iter()
        .find(|subkey| subkey.key.fingerprint() == recipient.component_fingerprint)
    else {
        return Ok(None);
    };
    let candidates = all_components(std::slice::from_ref(public));
    let policy = validate_certificate_with_policy_time(
        public,
        &candidates,
        certificate_time,
        cryptographic_policy_time,
        budget,
    )
    .map_err(map_policy_error)?;
    Ok(policy
        .subkeys_matching(&subkey.key)
        .any(|component| component.policy().authenticated)
        .then_some(primary_fingerprint))
}

pub(super) fn session_key_algorithm_matches(
    packet: SecretPacketRef<'_>,
    values: &PkeskBytes,
) -> bool {
    matches!(
        (packet.algorithm(), values),
        (
            PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt,
            PkeskBytes::Rsa { .. }
        ) | (
            PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt,
            PkeskBytes::Elgamal { .. }
        ) | (PublicKeyAlgorithm::ECDH, PkeskBytes::Ecdh { .. })
            | (PublicKeyAlgorithm::X25519, PkeskBytes::X25519 { .. })
            | (PublicKeyAlgorithm::X448, PkeskBytes::X448 { .. })
    )
}

pub(super) fn decompress_to_literal<'a>(
    mut message: Message<'a>,
) -> Result<Message<'a>, OpenPgpWriteError> {
    for _ in 0..MAX_OPENPGP_NESTING {
        validate_signed_nesting(&message)?;
        if message.literal_data_header().is_some() {
            return Ok(message);
        }
        if !message.is_compressed() && !message.is_signed() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        message = message
            .decompress()
            .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?;
    }
    Err(OpenPgpWriteError::ResourceLimit)
}

pub(super) fn validate_signed_nesting(message: &Message<'_>) -> Result<(), OpenPgpWriteError> {
    let mut current = message;
    let mut depth = 0_usize;
    let mut signatures = 0_usize;
    while let Message::Signed { reader, .. } = current {
        depth = depth
            .checked_add(1)
            .filter(|value| *value <= MAX_OPENPGP_NESTING)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        signatures = signatures
            .checked_add(reader.num_signatures())
            .filter(|value| *value <= MAX_OPENPGP_COMPONENTS)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        current = reader.get_ref();
    }
    Ok(())
}

pub(super) fn decrypt_session_key(
    packet: SecretPacketRef<'_>,
    values: &PkeskBytes,
    typ: EskType,
) -> Option<PlainSessionKey> {
    let password = Password::empty();
    let result = if matches!(
        packet.algorithm(),
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ) {
        AwsLcRsaSecretKey::new(packet)
            .ok()?
            .decrypt(&password, values, typ)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => key.decrypt(&password, values, typ),
            SecretPacketRef::Subkey(key) => key.decrypt(&password, values, typ),
        }
    };
    result.ok().and_then(Result::ok)
}

pub(super) fn evaluate_inline_verification(
    message: &Message<'_>,
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    recipient_identity: Option<&RecipientIdentity>,
) -> Result<Option<Verification>, OpenPgpWriteError> {
    let Message::Signed { reader, .. } = message else {
        return Ok(None);
    };
    if reader.num_signatures() > MAX_OPENPGP_COMPONENTS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let mut signature_indices = Vec::new();
    let mut signatures = Vec::new();
    for index in 0..reader.num_signatures() {
        let Some(signature) = reader.signature(index) else {
            continue;
        };
        signature_indices.push(index);
        signatures.push(signature.clone());
    }
    if signatures.is_empty() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let authenticated_recipients =
        authenticated_recipient_fingerprints(&signatures, recipient_identity, verification_time)?;
    evaluate_preverified_signatures_with_recipients(
        &signatures,
        certificates,
        verification_time,
        &authenticated_recipients,
        |signature_index, component| {
            let Some(&nested_index) = signature_indices.get(signature_index) else {
                return false;
            };
            message
                .verify_nested_explicit(nested_index, component)
                .is_ok()
        },
    )
    .map(Some)
    .map_err(map_read_error)
}

fn authenticated_recipient_fingerprints(
    signatures: &[pgp::packet::Signature],
    recipient: Option<&RecipientIdentity>,
    verification_time: DataSignatureVerificationTime,
) -> Result<Vec<Option<Fingerprint>>, OpenPgpWriteError> {
    let Some(recipient) = recipient else {
        return Ok(vec![None; signatures.len()]);
    };
    let mut budget = OpenPgpPolicyBudget::default();
    let mut cache = Vec::<(u64, Option<Fingerprint>)>::new();
    let mut fingerprints = Vec::with_capacity(signatures.len());
    for signature in signatures {
        let Some(certificate_time) = verification_time.trusted_signature_time(signature) else {
            fingerprints.push(None);
            continue;
        };
        if let Some((_, fingerprint)) = cache
            .iter()
            .find(|(cached_time, _)| *cached_time == certificate_time)
        {
            fingerprints.push(fingerprint.clone());
            continue;
        }
        let fingerprint = authenticated_recipient_primary_fingerprint(
            recipient,
            certificate_time,
            verification_time.reference_time(),
            &mut budget,
        )?;
        cache.push((certificate_time, fingerprint.clone()));
        fingerprints.push(fingerprint);
    }
    Ok(fingerprints)
}

#[cfg(test)]
#[derive(Default)]
pub(super) struct SecretVec(SecretChunks);

#[cfg(test)]
impl SecretVec {
    pub(super) fn into_zeroizing(self) -> Result<Zeroizing<Vec<u8>>, ()> {
        self.0.into_zeroizing()
    }
}

#[cfg(test)]
impl Write for SecretVec {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let mut chunk = Zeroizing::new(Vec::new());
        chunk
            .try_reserve_exact(buffer.len())
            .map_err(|_| std::io::Error::other("compressed secret output allocation failed"))?;
        chunk.extend_from_slice(buffer);
        self.0
            .push(chunk, usize::MAX)
            .map_err(|()| std::io::Error::other("compressed secret output allocation failed"))?;
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
