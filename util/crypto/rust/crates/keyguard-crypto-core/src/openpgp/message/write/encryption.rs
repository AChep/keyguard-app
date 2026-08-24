//! One-shot and streaming OpenPGP encryption workflows.
//!
//! Recipient selection, literal/signature composition, protection-mode choice,
//! and concrete packet encryption share one incremental execution path.

use super::*;

#[cfg(test)]
use crate::openpgp::policy::ValidatedCertificate;

/// Encrypts bounded content for all policy-valid recipients, preferring RFC
/// 9580 SEIPDv2 when it is common to every recipient and retaining the
/// LibrePGP/GnuPG OCB mode as a compatibility fallback when only that mode is
/// common. Both modes require an authentic advertisement from every selected
/// certificate.
pub(in crate::openpgp) fn encrypt_request(
    request: EncryptInput,
) -> Result<EncryptionResult, OpenPgpWriteError> {
    if request.content.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let EncryptInput {
        content,
        public_keys,
        signing_private_key,
        preferred_signing_fingerprint,
        file_name,
        armored,
        literal_time_epoch_seconds,
        reference_time_epoch_seconds,
        enable_compression,
        candidate_revocation_keys,
    } = request;
    let config = prepare_encryption(EncryptStreamInput {
        public_keys,
        signing_private_key,
        preferred_signing_fingerprint,
        file_name,
        armored,
        literal_time_epoch_seconds,
        reference_time_epoch_seconds,
        enable_compression,
        candidate_revocation_keys,
    })?;
    let (encrypted, mode) = encrypt_prepared(config, Cursor::new(content.as_slice()), Vec::new())?;
    Ok(EncryptionResult {
        data: encrypted,
        protection_mode: mode,
    })
}

pub(super) fn first_legacy_public_version(
    documents: &[Vec<u8>],
) -> Result<Option<u8>, OpenPgpWriteError> {
    let mut parsed_count = 0_usize;
    for document in documents {
        let (iterator, _) = PublicOrSecret::from_reader_many(Cursor::new(document.as_slice()))
            .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
        for item in iterator {
            parsed_count = parsed_count
                .checked_add(1)
                .filter(|value| *value <= MAX_OPENPGP_KEYS)
                .ok_or(OpenPgpWriteError::ResourceLimit)?;
            let certificate = match item.map_err(|_| OpenPgpWriteError::InvalidArgument)? {
                PublicOrSecret::Public(certificate) => certificate,
                PublicOrSecret::Secret(secret) => secret.to_public_key(),
            };
            if let Some(version) = legacy_public_version(&certificate) {
                return Ok(Some(version));
            }
        }
    }
    Ok(None)
}

pub(super) fn select_recipients(
    certificates: &[SignedPublicKey],
    revocation_candidates: &[SignedPublicKey],
    reference_time: u64,
    budget: &mut OpenPgpReadBudget,
) -> Result<
    (
        Vec<PublicComponent>,
        Vec<Fingerprint>,
        RecipientProtectionSupport,
    ),
    OpenPgpWriteError,
> {
    let mut candidates = all_components(certificates);
    candidates.extend(all_components(revocation_candidates));
    let mut recipients = Vec::new();
    let mut fingerprints = HashSet::new();
    let mut intended_recipients = Vec::new();
    let mut protection_support = RecipientProtectionSupport::new();
    for certificate in certificates {
        let policy = validate_certificate(
            certificate,
            &candidates,
            reference_time,
            budget.policy_mut(),
        )
        .map_err(map_policy_error)?;
        if !policy.primary_available() {
            return Err(OpenPgpWriteError::MissingKey);
        }
        let selected_subkey = policy
            .subkey_components()
            .filter(|component| component.encryption_usable())
            .max_by_key(|component| {
                let component = component.policy();
                (
                    component.key.created_at().as_secs(),
                    fingerprint_hex(component.key),
                )
            });
        let selected = if let Some(component) = selected_subkey {
            let component = component.policy();
            protection_support.intersect(component);
            PublicComponent::Subkey(component.key.clone())
        } else if policy.primary_component().encryption_usable() {
            let component = policy.primary_component();
            protection_support.intersect(component.policy());
            PublicComponent::Primary(component.policy().key.clone())
        } else {
            return Err(OpenPgpWriteError::MissingKey);
        };
        intended_recipients.push(policy.certificate().primary_key.fingerprint());
        let fingerprint = fingerprint_hex(&selected);
        if fingerprints.insert(fingerprint) {
            recipients.push(selected);
        }
    }
    intended_recipients.sort_by(|left, right| {
        left.version()
            .map(u8::from)
            .cmp(&right.version().map(u8::from))
            .then_with(|| left.as_bytes().cmp(right.as_bytes()))
    });
    intended_recipients.dedup();
    Ok((recipients, intended_recipients, protection_support))
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct RecipientProtectionSupport {
    pub(super) all_allow_seipd_v2: bool,
    pub(super) all_allow_gnupg_ocb: bool,
    pub(super) common_seipd_v2: Vec<(SymmetricKeyAlgorithm, AeadAlgorithm)>,
    pub(super) common_v1_symmetric: Vec<SymmetricKeyAlgorithm>,
    common_compression: Vec<CompressionAlgorithm>,
}

impl RecipientProtectionSupport {
    pub(super) fn new() -> Self {
        Self {
            all_allow_seipd_v2: true,
            all_allow_gnupg_ocb: true,
            // Sender policy for this writer's RFC 9580 OCB negotiation.
            // AES-128/OCB is the mandatory final choice.
            common_seipd_v2: vec![
                (SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb),
                (SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb),
            ],
            // Use Sequoia's AES-256 default as the sender preference while
            // retaining every AES cipher implemented by this writer.
            common_v1_symmetric: vec![
                SymmetricKeyAlgorithm::AES256,
                SymmetricKeyAlgorithm::AES192,
                SymmetricKeyAlgorithm::AES128,
            ],
            // Sequoia defaults to ZIP when DEFLATE is available.  ZLIB is
            // also implemented here; Uncompressed is the mandatory tail.
            common_compression: vec![
                CompressionAlgorithm::ZIP,
                CompressionAlgorithm::ZLIB,
                CompressionAlgorithm::Uncompressed,
            ],
        }
    }

    fn intersect<K: KeyDetails>(&mut self, policy: &ComponentPolicy<'_, K>) {
        self.all_allow_seipd_v2 &= component_allows_seipd_v2(policy);
        self.all_allow_gnupg_ocb &= component_allows_gnupg_ocb(policy);
        self.intersect_preferences(
            policy.preferred_symmetric.as_deref(),
            policy.preferred_compression.as_deref(),
        );
        self.intersect_aead_preferences(policy.preferred_aead.as_deref());
    }

    pub(super) fn intersect_preferences(
        &mut self,
        preferred_symmetric: Option<&[u8]>,
        preferred_compression: Option<&[u8]>,
    ) {
        self.common_v1_symmetric
            .retain(|algorithm| recipient_accepts_v1_symmetric(preferred_symmetric, *algorithm));
        self.common_compression
            .retain(|algorithm| recipient_accepts_compression(preferred_compression, *algorithm));
    }

    pub(super) fn intersect_aead_preferences(&mut self, preferred_aead: Option<&[(u8, u8)]>) {
        self.common_seipd_v2.retain(|ciphersuite| {
            recipient_accepts_seipd_v2_ciphersuite(preferred_aead, *ciphersuite)
        });
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) struct MessageAlgorithms {
    pub(super) protection_mode: ProtectionMode,
    pub(super) symmetric: SymmetricKeyAlgorithm,
    pub(super) compression: Option<CompressionAlgorithm>,
}

pub(super) fn select_message_algorithms(
    support: &RecipientProtectionSupport,
    enable_compression: bool,
) -> Result<MessageAlgorithms, OpenPgpWriteError> {
    let protection_mode = select_protection_mode(support);
    let symmetric = match protection_mode {
        // Preserve the LibrePGP/GnuPG writer's established ciphersuite.
        ProtectionMode::GnupgOcb => SymmetricKeyAlgorithm::AES256,
        // RFC 9580's v1 symmetric preferences do not constrain SEIPDv2.
        ProtectionMode::SeipdV2Aead => support
            .common_seipd_v2
            .first()
            .map(|(symmetric, _)| *symmetric)
            .ok_or(OpenPgpWriteError::InvalidArgument)?,
        ProtectionMode::SeipdV1Mdc => support
            .common_v1_symmetric
            .first()
            .copied()
            .ok_or(OpenPgpWriteError::InvalidArgument)?,
    };
    let compression = enable_compression
        .then(|| support.common_compression.first().copied())
        .flatten()
        .filter(|algorithm| *algorithm != CompressionAlgorithm::Uncompressed);
    Ok(MessageAlgorithms {
        protection_mode,
        symmetric,
        compression,
    })
}

fn recipient_accepts_seipd_v2_ciphersuite(
    preferences: Option<&[(u8, u8)]>,
    ciphersuite: (SymmetricKeyAlgorithm, AeadAlgorithm),
) -> bool {
    if ciphersuite == (SymmetricKeyAlgorithm::AES128, AeadAlgorithm::Ocb) {
        // RFC 9580 section 5.2.3.15 implicitly appends AES-128/OCB when it is
        // absent, including when the preference subpacket itself is absent.
        return true;
    }
    preferences
        .is_some_and(|values| values.contains(&(u8::from(ciphersuite.0), u8::from(ciphersuite.1))))
}

fn recipient_accepts_v1_symmetric(
    preferences: Option<&[u8]>,
    algorithm: SymmetricKeyAlgorithm,
) -> bool {
    if algorithm == SymmetricKeyAlgorithm::AES128 {
        // RFC 9580 section 12.2 tacitly appends AES-128 to every list.
        return true;
    }
    preferences.is_some_and(|values| values.contains(&u8::from(algorithm)))
}

fn recipient_accepts_compression(
    preferences: Option<&[u8]>,
    algorithm: CompressionAlgorithm,
) -> bool {
    if algorithm == CompressionAlgorithm::Uncompressed {
        // RFC 9580 section 12.3.1 tacitly appends Uncompressed.
        return true;
    }
    // RFC 9580 section 5.2.3.17 says absence denotes a preference for
    // uncompressed data and may indicate no compression support.  Section
    // 12.3.1's [ZIP, Uncompressed] note only describes earlier senders.
    preferences.is_some_and(|values| values.contains(&u8::from(algorithm)))
}

pub(super) fn select_protection_mode(support: &RecipientProtectionSupport) -> ProtectionMode {
    // RFC 9580 sections 4.3 and 13.7 require standard-only consumers to reject
    // unknown critical tag 20 and recommend v2 SEIPD when every recipient
    // supports it. Retain LibrePGP/GnuPG OCB only as an interoperability
    // fallback for recipient sets that cannot all consume standard SEIPDv2.
    if support.all_allow_seipd_v2 && !support.common_seipd_v2.is_empty() {
        ProtectionMode::SeipdV2Aead
    } else if support.all_allow_gnupg_ocb {
        ProtectionMode::GnupgOcb
    } else {
        ProtectionMode::SeipdV1Mdc
    }
}

pub(super) fn encrypted_message_include_checksum(
    mode: ProtectionMode,
    legacy_default: bool,
) -> Result<bool, OpenPgpWriteError> {
    // RFC 9580 section 6.1 forbids a CRC24 footer when the encrypted
    // packet sequence ends in a v2 SEIPD packet.  The MDC and GnuPG tag-20
    // OCB formats retain the legacy default for interoperability.
    match mode {
        ProtectionMode::SeipdV1Mdc | ProtectionMode::GnupgOcb => Ok(legacy_default),
        ProtectionMode::SeipdV2Aead => Ok(false),
    }
}

#[cfg(test)]
pub(super) fn recipient_allows_seipd_v2(policy: &ValidatedCertificate<'_>) -> bool {
    policy.primary_available() && component_allows_seipd_v2(&policy.primary)
}

fn component_allows_seipd_v2<K: KeyDetails>(policy: &ComponentPolicy<'_, K>) -> bool {
    policy.authenticated && policy.features.allows_seipd_v2(policy.key.version())
}

fn component_allows_gnupg_ocb<K>(policy: &ComponentPolicy<'_, K>) -> bool {
    policy.authenticated && policy.allows_gnupg_ocb
}

#[cfg(test)]
pub(super) fn build_composed_message(
    content: &[u8],
    file_name: &[u8],
    literal_time: Timestamp,
    signature_time: Option<Timestamp>,
    signer: Option<SecretPacketRef<'_>>,
    intended_recipients: &[Fingerprint],
    compression_algorithm: Option<CompressionAlgorithm>,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError> {
    let inline_signature = signer
        .map(|packet| {
            let signature_time = signature_time.ok_or(OpenPgpWriteError::Internal)?;
            create_inline_signature(packet, content, signature_time, intended_recipients)
        })
        .transpose()?;
    let literal_body_len = literal_packet_body_len(content, file_name)?;
    let literal_header = PacketHeader::new_fixed(Tag::LiteralData, literal_body_len);
    let mut inner_len = literal_header
        .write_len()
        .checked_add(literal_body_len as usize)
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    if let Some((one_pass, signature)) = &inline_signature {
        inner_len = inner_len
            .checked_add(one_pass.write_len_with_header())
            .and_then(|length| length.checked_add(signature.write_len_with_header()))
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
    }
    let mut inner = Zeroizing::new(Vec::new());
    inner
        .try_reserve_exact(inner_len)
        .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
    let allocation = inner.as_ptr();
    let capacity = inner.capacity();
    if let Some((one_pass, _)) = &inline_signature {
        one_pass
            .to_writer_with_header(&mut FixedCapacityWriter(&mut inner))
            .map_err(|_| OpenPgpWriteError::Internal)?;
    }
    write_literal_packet(
        &mut FixedCapacityWriter(&mut inner),
        content,
        file_name,
        literal_time,
    )?;
    if let Some((_, signature)) = inline_signature {
        signature
            .to_writer_with_header(&mut FixedCapacityWriter(&mut inner))
            .map_err(|_| OpenPgpWriteError::Internal)?;
    }
    if inner.len() != inner_len || inner.capacity() != capacity || inner.as_ptr() != allocation {
        return Err(OpenPgpWriteError::Internal);
    }
    let Some(compression_algorithm) = compression_algorithm else {
        return Ok(inner);
    };

    let compressed = compress_composed_message(inner.as_slice(), compression_algorithm)?;
    let body_len = compressed
        .len()
        .checked_add(1)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    let header = PacketHeader::new_fixed(Tag::CompressedData, body_len);
    let output_len = header
        .write_len()
        .checked_add(usize::try_from(body_len).map_err(|_| OpenPgpWriteError::ResourceLimit)?)
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    let mut output = Zeroizing::new(Vec::new());
    output
        .try_reserve_exact(output_len)
        .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
    let mut writer = FixedCapacityWriter(&mut output);
    header
        .to_writer(&mut writer)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    writer
        .write_all(&[u8::from(compression_algorithm)])
        .and_then(|()| writer.write_all(&compressed))
        .map_err(|_| OpenPgpWriteError::Internal)?;
    if output.len() != output_len {
        return Err(OpenPgpWriteError::Internal);
    }
    Ok(output)
}

#[cfg(test)]
fn compress_composed_message(
    input: &[u8],
    algorithm: CompressionAlgorithm,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError> {
    match algorithm {
        CompressionAlgorithm::ZIP => finish_compression(
            DeflateEncoder::new(SecretVec::default(), Compression::default()),
            input,
        ),
        CompressionAlgorithm::ZLIB => finish_compression(
            ZlibEncoder::new(SecretVec::default(), Compression::default()),
            input,
        ),
        _ => Err(OpenPgpWriteError::InvalidArgument),
    }
}

#[cfg(test)]
fn finish_compression<W>(
    mut encoder: W,
    input: &[u8],
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError>
where
    W: Write + CompressionWriter,
{
    encoder
        .write_all(input)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    encoder
        .finish()
        .map_err(|_| OpenPgpWriteError::Internal)?
        .into_zeroizing()
        .map_err(|_| OpenPgpWriteError::ResourceLimit)
}

#[cfg(test)]
trait CompressionWriter {
    fn finish(self) -> std::io::Result<SecretVec>;
}

#[cfg(test)]
impl CompressionWriter for DeflateEncoder<SecretVec> {
    fn finish(self) -> std::io::Result<SecretVec> {
        DeflateEncoder::finish(self)
    }
}

#[cfg(test)]
impl CompressionWriter for ZlibEncoder<SecretVec> {
    fn finish(self) -> std::io::Result<SecretVec> {
        ZlibEncoder::finish(self)
    }
}

#[cfg(test)]
pub(super) fn literal_packet_body_len(
    content: &[u8],
    file_name: &[u8],
) -> Result<u32, OpenPgpWriteError> {
    1_usize
        .checked_add(1)
        .and_then(|value| value.checked_add(file_name.len()))
        .and_then(|value| value.checked_add(4))
        .and_then(|value| value.checked_add(content.len()))
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)
}

#[cfg(test)]
pub(super) fn write_literal_packet(
    output: &mut impl Write,
    content: &[u8],
    file_name: &[u8],
    literal_time: Timestamp,
) -> Result<(), OpenPgpWriteError> {
    let file_name_len =
        u8::try_from(file_name.len()).map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    let body_len = literal_packet_body_len(content, file_name)?;
    PacketHeader::new_fixed(Tag::LiteralData, body_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output
        .write_all(&[b'b', file_name_len])
        .and_then(|()| output.write_all(file_name))
        .and_then(|()| output.write_all(&literal_time.as_secs().to_be_bytes()))
        .and_then(|()| output.write_all(content))
        .map_err(|_| OpenPgpWriteError::Internal)
}

pub(super) fn resolve_literal_time(explicit: Option<u64>) -> Timestamp {
    Timestamp::from_secs(explicit.unwrap_or(0) as u32)
}

pub(super) fn resolve_signature_time(
    signer_present: bool,
    clock: impl FnOnce() -> Timestamp,
) -> Option<Timestamp> {
    signer_present.then(clock)
}

#[cfg(test)]
pub(super) fn create_inline_signature(
    packet: SecretPacketRef<'_>,
    content: &[u8],
    signature_time: Timestamp,
    intended_recipients: &[Fingerprint],
) -> Result<(OnePassSignature, pgp::packet::Signature), OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        create_inline_signature_with_key(&adapter, content, signature_time, intended_recipients)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => {
                create_inline_signature_with_key(key, content, signature_time, intended_recipients)
            }
            SecretPacketRef::Subkey(key) => {
                create_inline_signature_with_key(key, content, signature_time, intended_recipients)
            }
        }
    }
}

#[cfg(test)]
pub(super) fn create_inline_signature_with_key<K>(
    key: &K,
    content: &[u8],
    signature_time: Timestamp,
    intended_recipients: &[Fingerprint],
) -> Result<(OnePassSignature, pgp::packet::Signature), OpenPgpWriteError>
where
    K: SigningKey,
{
    let (config, one_pass) = inline_signature_setup(key, signature_time, intended_recipients)?;
    let signature = config
        .sign(key, &Password::empty(), Cursor::new(content))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok((one_pass, signature))
}

/// Builds the shared inline-signature prelude: a binary-data signature config
/// with the standard signing subpackets and its matching v4/v6
/// one-pass-signature packet.
pub(super) fn inline_signature_setup(
    key: &(impl SigningKey + ?Sized),
    signature_time: Timestamp,
    intended_recipients: &[Fingerprint],
) -> Result<(SignatureConfig, OnePassSignature), OpenPgpWriteError> {
    let mut config = data_signature_config(key, SignatureType::Binary)?;
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(key, signature_time)?
    else {
        return Err(OpenPgpWriteError::Internal);
    };
    let intended_recipient_is_critical =
        matches!(config.version_specific, SignatureVersionSpecific::V6 { .. });
    config.hashed_subpackets = hashed;
    for fingerprint in intended_recipients {
        let data = SubpacketData::IntendedRecipientFingerprint(fingerprint.clone());
        config.hashed_subpackets.push(
            if intended_recipient_is_critical {
                Subpacket::critical(data)
            } else {
                Subpacket::regular(data)
            }
            .map_err(pgp_internal)?,
        );
    }
    config.unhashed_subpackets = unhashed;
    let hash_algorithm = config.hash_alg;
    let one_pass = match &config.version_specific {
        SignatureVersionSpecific::V4 => OnePassSignature::v3(
            SignatureType::Binary,
            hash_algorithm,
            key.algorithm(),
            key.legacy_key_id(),
        ),
        SignatureVersionSpecific::V6 { salt } => {
            let Fingerprint::V6(fingerprint) = key.fingerprint() else {
                return Err(OpenPgpWriteError::InvalidArgument);
            };
            OnePassSignature::v6(
                SignatureType::Binary,
                hash_algorithm,
                key.algorithm(),
                salt.clone(),
                fingerprint,
            )
        }
        _ => return Err(OpenPgpWriteError::InvalidArgument),
    };
    Ok((config, one_pass))
}

#[cfg(test)]
pub(super) fn encrypt_composed_message(
    plaintext: &[u8],
    recipients: &[PublicComponent],
    mode: ProtectionMode,
    symmetric_algorithm: SymmetricKeyAlgorithm,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let mut rng = AwsLcRng;
    let session_key = symmetric_algorithm.new_session_key(rng);
    let mut output = Vec::new();
    for recipient in recipients {
        encrypt_session_key_for_recipient(
            &mut rng,
            &session_key,
            recipient,
            mode,
            symmetric_algorithm,
        )
        .and_then(|packet| packet.to_writer_with_header(&mut output))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    }
    match mode {
        ProtectionMode::SeipdV1Mdc => {
            write_seipd_v1(
                &mut output,
                plaintext,
                &session_key,
                &mut rng,
                symmetric_algorithm,
            )?;
        }
        ProtectionMode::SeipdV2Aead => {
            write_seipd_v2(
                &mut output,
                plaintext,
                &session_key,
                &mut rng,
                symmetric_algorithm,
            )?;
        }
        ProtectionMode::GnupgOcb => {
            write_gnupg_ocb(&mut output, plaintext, &session_key, &mut rng)?;
        }
    }
    Ok(output)
}

pub(super) fn encrypt_session_key_for_recipient(
    rng: &mut AwsLcRng,
    session_key: &RawSessionKey,
    recipient: &PublicComponent,
    mode: ProtectionMode,
    symmetric_algorithm: SymmetricKeyAlgorithm,
) -> pgp::errors::Result<PublicKeyEncryptedSessionKey> {
    if mode == ProtectionMode::SeipdV2Aead {
        PublicKeyEncryptedSessionKey::from_session_key_v6(rng, session_key, recipient)
    } else {
        PublicKeyEncryptedSessionKey::from_session_key_v3(
            rng,
            session_key,
            symmetric_algorithm,
            recipient,
        )
    }
}

#[cfg(test)]
pub(super) fn write_seipd_v1(
    output: &mut Vec<u8>,
    plaintext: &[u8],
    session_key: &RawSessionKey,
    rng: &mut AwsLcRng,
    symmetric_algorithm: SymmetricKeyAlgorithm,
) -> Result<(), OpenPgpWriteError> {
    let encrypted_len = symmetric_algorithm
        .encrypted_protected_len(plaintext.len())
        .checked_add(1)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::SymEncryptedProtectedData, encrypted_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.push(1);
    symmetric_algorithm
        .stream_encryptor(rng, session_key.as_ref(), Cursor::new(plaintext))
        .and_then(|mut encryptor| encryptor.read_to_end(output).map_err(Into::into))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok(())
}

#[cfg(test)]
pub(super) fn write_seipd_v2(
    output: &mut Vec<u8>,
    plaintext: &[u8],
    session_key: &RawSessionKey,
    rng: &mut AwsLcRng,
    symmetric_algorithm: SymmetricKeyAlgorithm,
) -> Result<(), OpenPgpWriteError> {
    SymEncryptedProtectedData::encrypt_seipdv2(
        rng,
        symmetric_algorithm,
        AeadAlgorithm::Ocb,
        ChunkSize::C64KiB,
        session_key.as_ref(),
        plaintext,
    )
    .and_then(|packet| packet.to_writer_with_header(output))
    .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

#[cfg(test)]
pub(super) fn write_gnupg_ocb(
    output: &mut Vec<u8>,
    plaintext: &[u8],
    session_key: &RawSessionKey,
    rng: &mut AwsLcRng,
) -> Result<(), OpenPgpWriteError> {
    let chunks = plaintext.len().div_ceil(GNUPG_AEAD_CHUNK_BYTES);
    let body_len = 4_usize
        .checked_add(15)
        .and_then(|value| value.checked_add(plaintext.len()))
        .and_then(|value| value.checked_add(chunks.checked_mul(AEAD_TAG_BYTES)?))
        .and_then(|value| value.checked_add(AEAD_TAG_BYTES))
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::GnupgAeadData, body_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.extend_from_slice(&[
        1,
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(AeadAlgorithm::Ocb),
        GNUPG_AEAD_CHUNK_OCTET,
    ]);
    let mut iv = [0_u8; 15];
    rng.try_fill_bytes(&mut iv)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    output.extend_from_slice(&iv);

    // The patched OCB implementation erases both AES-256's expanded key and
    // all OCB L-table state on Drop. Keep this explicit type shape auditable.
    let cipher = Aes256Ocb::new_from_slice(session_key.as_ref())
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let mut index = 0_u64;
    let mut written = 0_u64;
    for chunk in plaintext.chunks(GNUPG_AEAD_CHUNK_BYTES) {
        let nonce = gnupg_ocb_nonce(&iv, index);
        let associated_data = gnupg_ocb_associated_data(index);
        let mut encrypted = chunk.to_vec();
        let tag = cipher
            .encrypt_in_place_detached(
                Nonce::<U15>::from_slice(&nonce),
                &associated_data,
                &mut encrypted,
            )
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        output.extend_from_slice(&encrypted);
        output.extend_from_slice(&tag);
        encrypted.zeroize();
        written = written
            .checked_add(chunk.len() as u64)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        index = index
            .checked_add(1)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
    }
    let nonce = gnupg_ocb_nonce(&iv, index);
    let mut final_associated_data = gnupg_ocb_associated_data(index).to_vec();
    final_associated_data.extend_from_slice(&written.to_be_bytes());
    let mut empty = Vec::new();
    let final_tag = cipher
        .encrypt_in_place_detached(
            Nonce::<U15>::from_slice(&nonce),
            &final_associated_data,
            &mut empty,
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    output.extend_from_slice(&final_tag);
    final_associated_data.zeroize();
    iv.zeroize();
    Ok(())
}

pub(super) fn gnupg_ocb_nonce(iv: &[u8; 15], chunk_index: u64) -> [u8; 15] {
    let mut nonce = *iv;
    for (nonce_byte, index_byte) in nonce[7..].iter_mut().zip(chunk_index.to_be_bytes()) {
        *nonce_byte ^= index_byte;
    }
    nonce
}

pub(super) fn gnupg_ocb_associated_data(chunk_index: u64) -> [u8; 13] {
    let mut data = [
        Tag::GnupgAeadData.encode(),
        1,
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(AeadAlgorithm::Ocb),
        GNUPG_AEAD_CHUNK_OCTET,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    data[5..].copy_from_slice(&chunk_index.to_be_bytes());
    data
}

pub(super) struct OpenPgpEncryptWorkerConfig {
    recipients: Vec<PublicComponent>,
    intended_recipients: Vec<Fingerprint>,
    signing: Option<PreparedSigningKey>,
    file_name: Vec<u8>,
    literal_time: Timestamp,
    signature_time: Option<Timestamp>,
    armored: bool,
    compression_algorithm: Option<CompressionAlgorithm>,
    symmetric_algorithm: SymmetricKeyAlgorithm,
    mode: ProtectionMode,
}

fn prepare_encryption(
    mut request: EncryptStreamInput,
) -> Result<OpenPgpEncryptWorkerConfig, OpenPgpWriteError> {
    if request.public_keys.is_empty()
        || request.public_keys.len() > MAX_OPENPGP_KEYS
        || request.file_name.len() > MAX_FILE_NAME_BYTES
        || request.file_name.len() > usize::from(u8::MAX)
        || (request.signing_private_key.is_none()
            && !request.preferred_signing_fingerprint.is_empty())
        || request
            .literal_time_epoch_seconds
            .is_some_and(|value| value > u64::from(u32::MAX))
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    if request
        .public_keys
        .iter()
        .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
        || request
            .signing_private_key
            .as_ref()
            .is_some_and(|key| key.len() > MAX_CONTROL_ENVELOPE_BYTES)
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    if let Some(version) = first_legacy_public_version(&request.public_keys)? {
        return Err(OpenPgpWriteError::UnsupportedKeyVersion(version));
    }

    let mut budget = OpenPgpReadBudget::default();
    let certificates =
        parse_public_key_documents(&request.public_keys, &mut budget).map_err(map_read_error)?;
    let revocation_candidates = parse_policy_candidates(&request.candidate_revocation_keys)?;
    let policy_time = reference_time(request.reference_time_epoch_seconds);
    let (recipients, intended_recipients, recipient_preferences) = select_recipients(
        &certificates,
        &revocation_candidates,
        policy_time,
        &mut budget,
    )?;
    if recipients.is_empty() {
        return Err(OpenPgpWriteError::MissingKey);
    }

    let signing = request
        .signing_private_key
        .take()
        .map(|private_key| {
            prepare_signing_key(
                private_key,
                &request.preferred_signing_fingerprint,
                policy_time,
                &revocation_candidates,
            )
        })
        .transpose()?;
    let literal_time = resolve_literal_time(request.literal_time_epoch_seconds);
    let signature_time = resolve_signature_time(signing.is_some(), Timestamp::now);
    let algorithms = select_message_algorithms(&recipient_preferences, request.enable_compression)?;
    Ok(OpenPgpEncryptWorkerConfig {
        recipients,
        intended_recipients,
        signing,
        file_name: request.file_name.into_bytes(),
        literal_time,
        signature_time,
        armored: request.armored,
        compression_algorithm: algorithms.compression,
        symmetric_algorithm: algorithms.symmetric,
        mode: algorithms.protection_mode,
    })
}

/// Incremental OpenPGP encryption. The worker owns all parser/compressor
/// state, and each channel is bounded independently of the file size.
pub(crate) struct OpenPgpEncryptionSession {
    worker: OpenPgpWorkerPipe,
}

impl OpenPgpEncryptionSession {
    pub(in crate::openpgp) fn open(request: EncryptStreamInput) -> Result<Self, OpenPgpWriteError> {
        let config = prepare_encryption(request)?;
        let worker = OpenPgpWorkerPipe::spawn("keyguard-openpgp-encrypt", move |input, output| {
            run_openpgp_encrypt_worker(config, input, output)
        })?;
        Ok(Self { worker })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.worker.update(data)
    }

    pub(in crate::openpgp) fn finish(self) -> Result<EncryptionResult, OpenPgpWriteError> {
        let (data, final_state) = self.worker.finish()?;
        let OpenPgpWorkerFinal::Encrypt(mode) = final_state else {
            return Err(OpenPgpWriteError::Internal);
        };
        Ok(EncryptionResult {
            data,
            protection_mode: mode,
        })
    }
}

pub(super) fn run_openpgp_encrypt_worker(
    config: OpenPgpEncryptWorkerConfig,
    input: OpenPgpChannelReader,
    output_sender: SyncSender<OpenPgpWorkerOutput>,
) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError> {
    let (_, mode) = encrypt_prepared(config, input, OpenPgpChannelWriter::new(output_sender))?;
    Ok(OpenPgpWorkerFinal::Encrypt(mode))
}

fn encrypt_prepared<R, W>(
    config: OpenPgpEncryptWorkerConfig,
    input: R,
    output: W,
) -> Result<(W::Output, ProtectionMode), OpenPgpWriteError>
where
    R: Read,
    W: OpenPgpOutputSink,
{
    let OpenPgpEncryptWorkerConfig {
        recipients,
        intended_recipients,
        signing,
        file_name,
        literal_time,
        signature_time,
        armored,
        compression_algorithm,
        symmetric_algorithm,
        mode,
    } = config;
    let mut rng = AwsLcRng;
    let session_key = symmetric_algorithm.new_session_key(rng);
    let include_checksum =
        encrypted_message_include_checksum(mode, ArmorOptions::default().include_checksum)?;
    let mut writer = OpenPgpMessageWriter::new(output, armored, include_checksum)?;
    for recipient in &recipients {
        encrypt_session_key_for_recipient(
            &mut rng,
            &session_key,
            recipient,
            mode,
            symmetric_algorithm,
        )
        .and_then(|packet| packet.to_writer_with_header(&mut writer))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    }

    let packet = signing
        .as_ref()
        .map(PreparedSigningKey::packet)
        .transpose()?;
    let rsa_signer = packet
        .filter(|packet| is_rsa_private_algorithm(packet.algorithm()))
        .map(AwsLcRsaSecretKey::new)
        .transpose()?;
    let signer: Option<&dyn SigningKey> = if let Some(adapter) = &rsa_signer {
        Some(adapter)
    } else {
        packet.map(|packet| match packet {
            SecretPacketRef::Primary(key) => key as &dyn SigningKey,
            SecretPacketRef::Subkey(key) => key as &dyn SigningKey,
        })
    };
    let signed = SignedLiteralReader::new(
        input,
        &file_name,
        literal_time,
        signature_time,
        signer,
        &intended_recipients,
    )?;
    let composed: Box<dyn Read + '_> = match compression_algorithm {
        Some(CompressionAlgorithm::ZIP) => compressed_stream(
            CompressionAlgorithm::ZIP,
            DeflateReader::new(signed, Compression::default()),
        ),
        Some(CompressionAlgorithm::ZLIB) => compressed_stream(
            CompressionAlgorithm::ZLIB,
            ZlibReader::new(signed, Compression::default()),
        ),
        Some(_) => return Err(OpenPgpWriteError::InvalidArgument),
        None => Box::new(signed),
    };
    match mode {
        ProtectionMode::SeipdV1Mdc => {
            let encrypted = symmetric_algorithm
                .stream_encryptor(rng, session_key.as_ref(), composed)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
            let protected = PrefixedReader::new(vec![1], encrypted);
            let mut protected_packet =
                PartialPacketReader::new(Tag::SymEncryptedProtectedData, protected);
            std::io::copy(&mut protected_packet, &mut writer)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        }
        ProtectionMode::SeipdV2Aead => {
            let mut salt = [0_u8; 32];
            rng.try_fill_bytes(&mut salt)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
            let encrypted = SymEncryptedProtectedData::encrypt_seipdv2_stream(
                symmetric_algorithm,
                AeadAlgorithm::Ocb,
                ChunkSize::C64KiB,
                session_key.as_ref(),
                salt,
                composed,
            )
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
            let mut prefix = Vec::with_capacity(36);
            prefix.extend_from_slice(&[
                2,
                u8::from(symmetric_algorithm),
                u8::from(AeadAlgorithm::Ocb),
                u8::from(ChunkSize::C64KiB),
            ]);
            prefix.extend_from_slice(&salt);
            let protected = PrefixedReader::new(prefix, encrypted);
            let mut protected_packet =
                PartialPacketReader::new(Tag::SymEncryptedProtectedData, protected);
            std::io::copy(&mut protected_packet, &mut writer)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        }
        ProtectionMode::GnupgOcb => {
            let encrypted = GnuPgpOcbEncryptReader::new(composed, &session_key, &mut rng)?;
            let mut protected_packet = PartialPacketReader::new(Tag::GnupgAeadData, encrypted);
            std::io::copy(&mut protected_packet, &mut writer)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        }
    }
    let output = writer.finish()?;
    Ok((output, mode))
}

fn compressed_stream<'a, R>(algorithm: CompressionAlgorithm, reader: R) -> Box<dyn Read + 'a>
where
    R: Read + 'a,
{
    let compressed_body = PrefixedReader::new(vec![u8::from(algorithm)], reader);
    Box::new(PartialPacketReader::new(
        Tag::CompressedData,
        compressed_body,
    ))
}
