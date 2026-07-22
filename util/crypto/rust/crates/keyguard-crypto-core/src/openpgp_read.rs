//! OpenPGP read-path parsing, certificate policy, and signature verification.
//!
//! rPGP is intentionally used only for public packet parsing and public-key
//! verification in this module. Private signing, decryption, and generation
//! stay outside this dependency path.

use std::{
    io::{self, BufRead, BufReader, Cursor, Read},
    sync::{
        atomic::{AtomicUsize, Ordering},
        mpsc::{self, Receiver, SyncSender},
    },
    thread::{self, JoinHandle},
    time::{SystemTime, UNIX_EPOCH},
};

use pgp::{
    armor::{self, BlockType, Dearmor, DearmorOptions},
    composed::{Deserializable, DetachedSignature, PublicOrSecret, SignedPublicKey},
    crypto::{ecc_curve::ECCCurve, public_key::PublicKeyAlgorithm},
    packet::{
        KeyFlags, PacketParser, PublicKey, PublicSubkey, Signature, SignatureType,
        SignatureVersion, SignatureVersionSpecific, SubpacketData,
    },
    ser::Serialize,
    types::{KeyDetails, KeyVersion, PublicParams, Tag, VerifyingKey},
};
use prost::Message;
use thiserror::Error;
use zeroize::Zeroizing;

use crate::{
    openpgp_packets::{RawPacketError, RawPacketStream},
    protocol::{
        OpenPgpDetachedVerifyStreamOpenRequest, OpenPgpKeyMetadata, OpenPgpKeyMetadataKey,
        OpenPgpMetadataResolveRequest, OpenPgpMetadataResolveResult, OpenPgpPublicKeyInfo,
        OpenPgpPublicKeyParseError, OpenPgpPublicKeyParseErrorReason, OpenPgpPublicKeyParseRequest,
        OpenPgpPublicKeyParseResult, OpenPgpPublicKeyParseSuccess, OpenPgpPublicSubKeyInfo,
        OpenPgpVerification, OpenPgpVerificationStatus, OpenPgpVerificationWarning,
        OpenPgpVerifyKind, OpenPgpVerifyRequest, open_pgp_public_key_parse_result,
    },
};

const METADATA_VERSION: u32 = 1;
const STREAM_CHANNEL_DEPTH: usize = 1;
const MAX_PUBLIC_KEY_DOCUMENTS: usize = 64;
const MAX_CERTIFICATES_PER_REQUEST: usize = 64;
const MAX_COMPONENTS_PER_CERTIFICATE: usize = 64;
const MAX_IDENTITIES_PER_CERTIFICATE: usize = 256;
const MAX_SIGNATURES_PER_OBJECT: usize = 256;
const MAX_DETACHED_SIGNATURES: usize = 64;
const MAX_CLEAR_SIGNED_HEADER_BYTES: usize = 64 * 1024;
const MAX_CLEAR_SIGNED_LINES: usize = 16 * 1024;
const MAX_CLEAR_SIGNED_LINE_BYTES: usize = 64 * 1024;
// Aggregate request limits are deliberately much smaller than the product of
// the per-certificate limits. They bound allocations and policy work for
// attacker-controlled keyserver/import documents without affecting normal
// transferable certificates (which usually contain fewer than ten packets).
const MAX_PACKETS_PER_REQUEST: usize = 8 * 1024;
const MAX_PACKET_BODY_BYTES: usize = 4 * 1024 * 1024;
const MAX_COMPONENTS_PER_REQUEST: usize = 512;
const MAX_IDENTITIES_PER_REQUEST: usize = 1024;
const MAX_SIGNATURES_PER_REQUEST: usize = 4 * 1024;
const MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST: usize = 4 * 1024;
const MAX_DESIGNATED_REVOKERS_PER_REQUEST: usize = 32;
const MAX_PUBLIC_KEY_PARAMETER_BYTES: usize = 16 * 1024;
const MAX_RSA_MODULUS_BITS: u32 = 8 * 1024;
const MAX_RSA_PUBLIC_EXPONENT_BYTES: usize = 8;
const MAX_DISCRETE_LOG_KEY_BITS: u32 = 8 * 1024;
const MAX_ECC_PUBLIC_PARAMETER_BYTES: usize = 1024;
// Four verifier workers keep native thread stacks bounded on Android/iOS while
// still permitting foreground verification plus modest application parallelism.
const MAX_OPENPGP_VERIFIER_WORKERS: usize = 4;

static ACTIVE_OPENPGP_VERIFIER_WORKERS: AtomicUsize = AtomicUsize::new(0);

/// Stable internal read-path failure classification.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum OpenPgpReadError {
    /// A request enum or OpenPGP control document is malformed.
    #[error("invalid OpenPGP read request")]
    InvalidArgument,
    /// Explicit OpenPGP parser or policy work bound was exceeded.
    #[error("OpenPGP read resource limit exceeded")]
    ResourceLimit,
    /// A background verifier could not be created or joined.
    #[error("OpenPGP verifier failed")]
    Internal,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ParseFailure {
    Malformed,
    UnsupportedKeyVersion,
    ResourceLimit,
}

#[derive(Clone)]
pub(super) enum PublicComponent {
    Primary(PublicKey),
    Subkey(PublicSubkey),
}

pub(super) struct ComponentPolicy<'a, K> {
    pub(super) key: &'a K,
    pub(super) authenticated: bool,
    pub(super) effective_signature: Option<&'a Signature>,
    pub(super) key_flags: Option<KeyFlags>,
    pub(super) revoked: bool,
    pub(super) signing_cross_certified: bool,
}

pub(super) struct CertificatePolicy<'a> {
    pub(super) primary: ComponentPolicy<'a, PublicKey>,
    pub(super) subkeys: Vec<ComponentPolicy<'a, PublicSubkey>>,
    pub(super) verified_user_ids: Vec<String>,
}

pub(super) struct SubkeyAuthentication<'a> {
    verified_bindings: Vec<&'a Signature>,
}

/// Cryptographic ownership evidence without expiry or revocation policy.
///
/// Third-party and invalid ancillary signatures are intentionally absent from
/// this summary but remain untouched in the parsed certificate.
pub(super) struct CertificateAuthentication<'a> {
    pub(super) subkeys: Vec<SubkeyAuthentication<'a>>,
    verified_direct: Vec<&'a Signature>,
    primary_self_revoked: bool,
    user_ids: Vec<IdentityAuthentication<'a>>,
    user_attributes: Vec<IdentityAuthentication<'a>>,
    subkey_self_revoked: Vec<bool>,
}

struct IdentityAuthentication<'a> {
    effective_signature: Option<&'a Signature>,
    self_revoked: bool,
}

struct SelectedSigner {
    certificate_index: usize,
    component_index: Option<usize>,
    component: PublicComponent,
}

struct ParsedPublicCertificate {
    certificate: SignedPublicKey,
    packets: Vec<u8>,
}

struct RawOpenPgpPackets<'a>(&'a [u8]);

#[derive(Debug, Default)]
pub(super) struct OpenPgpReadBudget {
    packets: usize,
    components: usize,
    identities: usize,
    signatures: usize,
    public_key_verifications: usize,
    designated_revokers: Vec<DesignatedRevokerId>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct DesignatedRevokerId {
    algorithm: u8,
    fingerprint: Vec<u8>,
}

#[derive(Debug)]
struct VerifierWorkerPermit;

enum PreparedVerification {
    Missing(OpenPgpVerification),
    Candidate {
        signature: Box<Signature>,
        signer: Box<PublicComponent>,
        result: OpenPgpVerification,
        budget: OpenPgpReadBudget,
    },
}

/// Bounded, incremental detached-signature verification session.
pub(crate) struct DetachedVerificationSession {
    sender: Option<SyncSender<Zeroizing<Vec<u8>>>>,
    worker: Option<JoinHandle<Result<Vec<u8>, OpenPgpReadError>>>,
}

struct ChannelReader {
    receiver: Receiver<Zeroizing<Vec<u8>>>,
    current: Zeroizing<Vec<u8>>,
    offset: usize,
}

impl OpenPgpReadBudget {
    fn charge_packets(&mut self, count: usize) -> Result<(), ParseFailure> {
        Self::charge_parse_counter(&mut self.packets, count, MAX_PACKETS_PER_REQUEST)
    }

    fn charge_shape(
        &mut self,
        components: usize,
        identities: usize,
        signatures: usize,
    ) -> Result<(), ParseFailure> {
        let components = self
            .components
            .checked_add(components)
            .filter(|value| *value <= MAX_COMPONENTS_PER_REQUEST)
            .ok_or(ParseFailure::ResourceLimit)?;
        let identities = self
            .identities
            .checked_add(identities)
            .filter(|value| *value <= MAX_IDENTITIES_PER_REQUEST)
            .ok_or(ParseFailure::ResourceLimit)?;
        let signatures = self
            .signatures
            .checked_add(signatures)
            .filter(|value| *value <= MAX_SIGNATURES_PER_REQUEST)
            .ok_or(ParseFailure::ResourceLimit)?;
        self.components = components;
        self.identities = identities;
        self.signatures = signatures;
        Ok(())
    }

    fn charge_signatures(&mut self, count: usize) -> Result<(), OpenPgpReadError> {
        Self::charge_parse_counter(&mut self.signatures, count, MAX_SIGNATURES_PER_REQUEST)
            .map_err(|_| OpenPgpReadError::ResourceLimit)
    }

    fn charge_public_key_verification(&mut self) -> Result<(), OpenPgpReadError> {
        self.public_key_verifications = self
            .public_key_verifications
            .checked_add(1)
            .filter(|value| *value <= MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST)
            .ok_or(OpenPgpReadError::ResourceLimit)?;
        Ok(())
    }

    fn charge_designated_revoker(
        &mut self,
        revoker: DesignatedRevokerId,
    ) -> Result<(), OpenPgpReadError> {
        insert_designated_revoker(&mut self.designated_revokers, revoker).map(|_| ())
    }

    fn charge_parse_counter(
        counter: &mut usize,
        count: usize,
        limit: usize,
    ) -> Result<(), ParseFailure> {
        *counter = (*counter)
            .checked_add(count)
            .filter(|value| *value <= limit)
            .ok_or(ParseFailure::ResourceLimit)?;
        Ok(())
    }
}

impl VerifierWorkerPermit {
    fn acquire() -> Result<Self, OpenPgpReadError> {
        ACTIVE_OPENPGP_VERIFIER_WORKERS
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |active| {
                (active < MAX_OPENPGP_VERIFIER_WORKERS).then_some(active + 1)
            })
            .map(|_| Self)
            .map_err(|_| OpenPgpReadError::ResourceLimit)
    }
}

impl Drop for VerifierWorkerPermit {
    fn drop(&mut self) {
        let previous = ACTIVE_OPENPGP_VERIFIER_WORKERS.fetch_sub(1, Ordering::AcqRel);
        debug_assert!(previous > 0, "OpenPGP verifier permit underflow");
    }
}

impl Serialize for RawOpenPgpPackets<'_> {
    fn to_writer<W: io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

/// Parses transferable public keys and returns a typed domain result.
pub(crate) fn parse_public_key_request(
    request: OpenPgpPublicKeyParseRequest,
) -> Result<Vec<u8>, OpenPgpReadError> {
    if request.key_data.iter().all(u8::is_ascii_whitespace) {
        return Ok(encode_parse_error(OpenPgpPublicKeyParseErrorReason::Empty));
    }
    let reference_time = reference_time(request.reference_time_epoch_seconds);
    let mut budget = OpenPgpReadBudget::default();
    let parsed = match parse_public_certificates_preserving_packets(&request.key_data, &mut budget)
    {
        Ok(certificates) => certificates,
        Err(ParseFailure::UnsupportedKeyVersion) => {
            return Ok(encode_parse_error(
                OpenPgpPublicKeyParseErrorReason::UnsupportedKeyVersion,
            ));
        }
        Err(ParseFailure::Malformed) => {
            return Ok(encode_parse_error(
                OpenPgpPublicKeyParseErrorReason::Malformed,
            ));
        }
        Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
    };
    let candidates = parsed
        .iter()
        .flat_map(|parsed| certificate_components(&parsed.certificate))
        .collect::<Vec<_>>();
    let mut keys = Vec::new();
    for parsed in &parsed {
        let policy = inspect_certificate(
            &parsed.certificate,
            &candidates,
            reference_time,
            &mut budget,
        )?;
        if let Some(key) = public_key_info(&policy, &parsed.packets, reference_time) {
            keys.push(key);
        }
    }
    if keys.is_empty() {
        return Ok(encode_parse_error(
            OpenPgpPublicKeyParseErrorReason::Malformed,
        ));
    }
    Ok(OpenPgpPublicKeyParseResult {
        result: Some(open_pgp_public_key_parse_result::Result::Success(
            OpenPgpPublicKeyParseSuccess { keys },
        )),
    }
    .encode_to_vec())
}

/// Verifies a clear-signed document or a one-shot detached signature.
pub(crate) fn verify_request(
    mut request: OpenPgpVerifyRequest,
) -> Result<Vec<u8>, OpenPgpReadError> {
    let kind = OpenPgpVerifyKind::try_from(request.kind)
        .ok()
        .filter(|kind| *kind != OpenPgpVerifyKind::Unspecified)
        .ok_or(OpenPgpReadError::InvalidArgument)?;
    let reference_time = reference_time(request.reference_time_epoch_seconds);
    let mut budget = OpenPgpReadBudget::default();
    let certificates = parse_public_key_documents(&request.public_keys, &mut budget)?;
    match kind {
        OpenPgpVerifyKind::ClearText => {
            let content = Zeroizing::new(std::mem::take(&mut request.content));
            let (signatures, signed_text) = parse_clear_signed_message(&content, &mut budget)?;
            let prepared =
                prepare_verification(&signatures, &certificates, reference_time, budget)?;
            verify_prepared(prepared, Cursor::new(signed_text.as_slice()))
        }
        OpenPgpVerifyKind::Detached => {
            let signatures = parse_detached_signatures(&request.signature, &mut budget)?;
            let prepared =
                prepare_verification(&signatures, &certificates, reference_time, budget)?;
            let content = Zeroizing::new(std::mem::take(&mut request.content));
            verify_prepared(prepared, Cursor::new(content.as_slice()))
        }
        OpenPgpVerifyKind::Unspecified => Err(OpenPgpReadError::InvalidArgument),
    }
}

/// Resolves versioned gpg-agent metadata from secret or public transferable keys.
pub(crate) fn resolve_metadata(
    mut request: OpenPgpMetadataResolveRequest,
) -> Result<Vec<u8>, OpenPgpReadError> {
    // Take the only secret control field out of the protobuf request immediately.
    // The wrapper erases its full allocation on every return path. Parsed private
    // packet values are independently erased by rPGP; this function converts each
    // entry to public form immediately.
    let private_key_data = request.private_key_data.take().map(Zeroizing::new);
    let reference_time = reference_time(request.reference_time_epoch_seconds);
    let mut budget = OpenPgpReadBudget::default();
    if request.candidate_revocation_keys.len() > MAX_PUBLIC_KEY_DOCUMENTS {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    let mut external_candidates = Vec::new();
    for data in &request.candidate_revocation_keys {
        match parse_public_certificates(data, &mut budget) {
            Ok(parsed) => {
                if external_candidates.len() + parsed.len() > MAX_CERTIFICATES_PER_REQUEST {
                    return Err(OpenPgpReadError::ResourceLimit);
                }
                external_candidates.extend(parsed);
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
            Err(ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion) => {}
        }
    }

    let normalized = normalize_fingerprint(&request.normalized_fingerprint);
    let mut metadata = None;
    if let Some(data) = private_key_data
        .as_deref()
        .filter(|data| !data.iter().all(u8::is_ascii_whitespace))
    {
        match parse_any_certificates(data, &mut budget) {
            Ok(certificates) => {
                metadata = resolve_metadata_certificates(
                    &certificates,
                    &external_candidates,
                    &normalized,
                    reference_time,
                    &mut budget,
                )?
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
            Err(ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion) => {}
        }
    }
    if metadata.is_none()
        && let Some(data) = request
            .public_key_data
            .as_deref()
            .filter(|data| !data.iter().all(u8::is_ascii_whitespace))
    {
        match parse_public_certificates(data, &mut budget) {
            Ok(certificates) => {
                metadata = resolve_metadata_certificates(
                    &certificates,
                    &external_candidates,
                    &normalized,
                    reference_time,
                    &mut budget,
                )?
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
            Err(ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion) => {}
        }
    }

    Ok(OpenPgpMetadataResolveResult { metadata }.encode_to_vec())
}

fn resolve_metadata_certificates(
    certificates: &[SignedPublicKey],
    external_candidates: &[SignedPublicKey],
    normalized_fingerprint: &str,
    reference_time: u64,
    budget: &mut OpenPgpReadBudget,
) -> Result<Option<OpenPgpKeyMetadata>, OpenPgpReadError> {
    let mut candidates = all_components(certificates);
    candidates.extend(all_components(external_candidates));
    let mut keys = Vec::new();
    for certificate in certificates {
        if !normalized_fingerprint.is_empty()
            && !certificate_components(certificate)
                .any(|component| component.fingerprint_hex() == normalized_fingerprint)
        {
            continue;
        }
        let policy = inspect_certificate(certificate, &candidates, reference_time, budget)?;
        keys.extend(metadata_keys(&policy, reference_time));
    }
    Ok((!keys.is_empty()).then_some(OpenPgpKeyMetadata {
        version: METADATA_VERSION,
        keys,
    }))
}

impl DetachedVerificationSession {
    /// Parses control data and starts a bounded verifier worker.
    pub(crate) fn open(
        request: OpenPgpDetachedVerifyStreamOpenRequest,
    ) -> Result<Self, OpenPgpReadError> {
        let permit = VerifierWorkerPermit::acquire()?;
        let mut budget = OpenPgpReadBudget::default();
        let certificates = parse_public_key_documents(&request.public_keys, &mut budget)?;
        let signatures = parse_detached_signatures(&request.signature, &mut budget)?;
        let prepared = prepare_verification(
            &signatures,
            &certificates,
            reference_time(request.reference_time_epoch_seconds),
            budget,
        )?;
        let (sender, receiver) = mpsc::sync_channel(STREAM_CHANNEL_DEPTH);
        let worker = thread::Builder::new()
            .name("keyguard-openpgp-verify".to_owned())
            .spawn(move || {
                let _permit = permit;
                verify_prepared(
                    prepared,
                    ChannelReader {
                        receiver,
                        current: Zeroizing::new(Vec::new()),
                        offset: 0,
                    },
                )
            })
            .map_err(|_| OpenPgpReadError::Internal)?;
        Ok(Self {
            sender: Some(sender),
            worker: Some(worker),
        })
    }

    /// Supplies one raw file body chunk to the verifier.
    pub(crate) fn update(&mut self, data: &[u8]) -> Result<(), OpenPgpReadError> {
        self.sender
            .as_ref()
            .ok_or(OpenPgpReadError::Internal)?
            .send(Zeroizing::new(data.to_vec()))
            .map_err(|_| OpenPgpReadError::Internal)
    }

    /// Closes the body stream and returns the encoded verification DTO.
    pub(crate) fn finish(mut self) -> Result<Vec<u8>, OpenPgpReadError> {
        self.sender.take();
        self.worker
            .take()
            .ok_or(OpenPgpReadError::Internal)?
            .join()
            .map_err(|_| OpenPgpReadError::Internal)?
    }
}

impl Drop for DetachedVerificationSession {
    fn drop(&mut self) {
        self.sender.take();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

impl Read for ChannelReader {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        loop {
            if self.offset < self.current.len() {
                let count = output.len().min(self.current.len() - self.offset);
                output[..count].copy_from_slice(&self.current[self.offset..self.offset + count]);
                self.offset += count;
                return Ok(count);
            }
            self.current = match self.receiver.recv() {
                Ok(chunk) => chunk,
                Err(_) => return Ok(0),
            };
            self.offset = 0;
        }
    }
}

impl PublicComponent {
    fn fingerprint(&self) -> pgp::types::Fingerprint {
        match self {
            Self::Primary(key) => key.fingerprint(),
            Self::Subkey(key) => key.fingerprint(),
        }
    }

    fn fingerprint_hex(&self) -> String {
        hex_upper(self.fingerprint().as_bytes())
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        match self {
            Self::Primary(key) => key.algorithm(),
            Self::Subkey(key) => key.algorithm(),
        }
    }

    fn verify<R: Read>(
        &self,
        signature: &Signature,
        input: R,
        budget: &mut OpenPgpReadBudget,
    ) -> Result<bool, OpenPgpReadError> {
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => signature.verify(key, input).is_ok(),
            Self::Subkey(key) => signature.verify(key, input).is_ok(),
        })
    }

    fn verifies_key_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        budget: &mut OpenPgpReadBudget,
    ) -> Result<bool, OpenPgpReadError> {
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => signature.verify_key_third_party(primary, key).is_ok(),
            Self::Subkey(key) => signature.verify_key_third_party(primary, key).is_ok(),
        })
    }

    fn verifies_certification_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        tag: Tag,
        identity: &impl Serialize,
        budget: &mut OpenPgpReadBudget,
    ) -> Result<bool, OpenPgpReadError> {
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => signature
                .verify_third_party_certification(primary, key, tag, identity)
                .is_ok(),
            Self::Subkey(key) => signature
                .verify_third_party_certification(primary, key, tag, identity)
                .is_ok(),
        })
    }

    fn verifies_subkey_revocation(
        &self,
        signature: &Signature,
        primary: &PublicKey,
        subkey: &PublicSubkey,
        budget: &mut OpenPgpReadBudget,
    ) -> Result<bool, OpenPgpReadError> {
        budget.charge_public_key_verification()?;
        Ok(match self {
            Self::Primary(key) => {
                verify_third_party_subkey_revocation(signature, primary, subkey, key)
            }
            Self::Subkey(key) => {
                verify_third_party_subkey_revocation(signature, primary, subkey, key)
            }
        })
    }
}

fn verify_third_party_subkey_revocation<V>(
    signature: &Signature,
    primary: &PublicKey,
    subkey: &PublicSubkey,
    signer: &V,
) -> bool
where
    V: VerifyingKey + Serialize,
{
    if signature.typ() != Some(SignatureType::SubkeyRevocation)
        || !signature_matches_signer(signature, signer)
        || !signature_version_matches_signer(signature.version(), signer.version())
    {
        return false;
    }
    let Some(config) = signature.config() else {
        return false;
    };
    let Ok(mut hasher) = config.hash_alg.new_hasher() else {
        return false;
    };
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    let Some(primary_hash_data) = key_hash_data(primary) else {
        return false;
    };
    let Some(subkey_hash_data) = key_hash_data(subkey) else {
        return false;
    };
    hasher.update(&primary_hash_data);
    hasher.update(&subkey_hash_data);
    let Ok(signature_data_len) = config.hash_signature_data(&mut hasher) else {
        return false;
    };
    let Ok(trailer) = config.trailer(signature_data_len) else {
        return false;
    };
    hasher.update(&trailer);
    let digest = hasher.finalize();
    let Some(expected_prefix) = signature.signed_hash_value() else {
        return false;
    };
    if digest.get(..expected_prefix.len()) != Some(expected_prefix.as_slice()) {
        return false;
    }
    signature
        .signature()
        .is_some_and(|value| signer.verify(config.hash_alg, &digest, value).is_ok())
}

fn signature_matches_signer<V: KeyDetails>(signature: &Signature, signer: &V) -> bool {
    let issuer_key_ids = signature.issuer_key_id();
    let issuer_fingerprints = signature.issuer_fingerprint();
    if issuer_key_ids.is_empty() && issuer_fingerprints.is_empty() {
        return true;
    }
    let key_id = signer.legacy_key_id();
    let fingerprint = signer.fingerprint();
    issuer_key_ids.contains(&&key_id) || issuer_fingerprints.contains(&&fingerprint)
}

fn signature_version_matches_signer(
    signature_version: SignatureVersion,
    signer_version: KeyVersion,
) -> bool {
    matches!(
        (signature_version, signer_version),
        (SignatureVersion::V6, KeyVersion::V6)
    ) || (signature_version != SignatureVersion::V6 && signer_version != KeyVersion::V6)
}

fn key_hash_data<K>(key: &K) -> Option<Vec<u8>>
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
        _ => return None,
    }
    key.to_writer(&mut result).ok()?;
    Some(result)
}

fn preflight_openpgp_packets(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    let mut input = BufReader::new(Cursor::new(data));
    let first = input
        .fill_buf()
        .map_err(|_| ParseFailure::Malformed)?
        .first()
        .copied()
        .ok_or(ParseFailure::Malformed)?;
    if first & 0x80 != 0 {
        return preflight_packet_reader(input, budget);
    }

    let mut dearmor = Dearmor::with_options(
        input,
        DearmorOptions::default().set_limit(crate::MAX_CONTROL_ENVELOPE_BYTES),
    );
    dearmor.read_header().map_err(|_| ParseFailure::Malformed)?;
    preflight_packet_reader(BufReader::new(dearmor), budget)
}

fn preflight_packet_reader<R: BufRead>(
    reader: R,
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    let mut packets = PacketParser::new(reader);
    while let Some(packet) = packets.next_ref() {
        let mut body = packet.map_err(|_| ParseFailure::Malformed)?;
        budget.charge_packets(1)?;
        if body
            .packet_header()
            .packet_length()
            .maybe_len()
            .is_some_and(|length| length as usize > MAX_PACKET_BODY_BYTES)
        {
            return Err(ParseFailure::ResourceLimit);
        }
        let read = io::copy(
            &mut body.by_ref().take((MAX_PACKET_BODY_BYTES + 1) as u64),
            &mut io::sink(),
        )
        .map_err(|_| ParseFailure::Malformed)?;
        if read > MAX_PACKET_BODY_BYTES as u64 {
            return Err(ParseFailure::ResourceLimit);
        }
    }
    Ok(())
}

fn parse_public_certificates_preserving_packets(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<ParsedPublicCertificate>, ParseFailure> {
    let binary = decode_openpgp_packets(data)?;
    let rings = split_public_certificate_packets(&binary)?;
    let semantic = semantic_key_packets(data, budget)?;
    let semantic_rings = split_public_certificate_packets(&semantic)?;
    if semantic_rings.len() != rings.len() {
        return Err(ParseFailure::Malformed);
    }
    if rings.len() > MAX_CERTIFICATES_PER_REQUEST {
        return Err(ParseFailure::ResourceLimit);
    }

    let mut parsed = Vec::with_capacity(rings.len());
    for (packets, semantic_packets) in rings.into_iter().zip(semantic_rings) {
        let mut certificates = parse_public_certificates_composed(&semantic_packets, budget)?;
        if certificates.len() != 1 {
            return Err(ParseFailure::Malformed);
        }
        parsed.push(ParsedPublicCertificate {
            certificate: certificates.remove(0),
            packets,
        });
    }
    (!parsed.is_empty())
        .then_some(parsed)
        .ok_or(ParseFailure::Malformed)
}

fn decode_openpgp_packets(data: &[u8]) -> Result<Vec<u8>, ParseFailure> {
    RawPacketStream::parse(data, MAX_PACKETS_PER_REQUEST)
        .map(|stream| stream.bytes().to_vec())
        .map_err(map_raw_packet_error)
}

fn split_public_certificate_packets(data: &[u8]) -> Result<Vec<Vec<u8>>, ParseFailure> {
    let stream =
        RawPacketStream::parse(data, MAX_PACKETS_PER_REQUEST).map_err(map_raw_packet_error)?;
    let mut primary_starts = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        if packet.tag() == u8::from(Tag::PublicKey) {
            primary_starts.push(index);
        }
    }
    if primary_starts.is_empty() {
        return Err(ParseFailure::Malformed);
    }
    Ok(primary_starts
        .iter()
        .enumerate()
        .map(|(position, start)| {
            let end = primary_starts
                .get(position + 1)
                .copied()
                .unwrap_or(stream.packets().len());
            let mut certificate = Vec::new();
            for packet in &stream.packets()[*start..end] {
                certificate.extend_from_slice(stream.raw(packet));
            }
            certificate
        })
        .collect())
}

fn map_raw_packet_error(error: RawPacketError) -> ParseFailure {
    match error {
        RawPacketError::Malformed => ParseFailure::Malformed,
        RawPacketError::ResourceLimit => ParseFailure::ResourceLimit,
    }
}

fn semantic_key_packets(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Zeroizing<Vec<u8>>, ParseFailure> {
    let stream =
        RawPacketStream::parse(data, MAX_PACKETS_PER_REQUEST).map_err(map_raw_packet_error)?;
    for packet in stream.packets() {
        budget.charge_packets(1)?;
        if packet.body_len() > MAX_PACKET_BODY_BYTES {
            return Err(ParseFailure::ResourceLimit);
        }
    }
    Ok(stream.semantic_bytes())
}

fn parse_public_certificates(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, ParseFailure> {
    let semantic = semantic_key_packets(data, budget)?;
    parse_public_certificates_composed(&semantic, budget)
}

fn parse_public_certificates_composed(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, ParseFailure> {
    let (iterator, _) = SignedPublicKey::from_reader_many(Cursor::new(data))
        .map_err(|_| ParseFailure::Malformed)?;
    let certificates = iterator
        .take(MAX_CERTIFICATES_PER_REQUEST + 1)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| ParseFailure::Malformed)?;
    if certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
        return Err(ParseFailure::ResourceLimit);
    }
    validate_certificate_versions(&certificates)?;
    validate_certificate_shapes(&certificates, budget)?;
    (!certificates.is_empty())
        .then_some(certificates)
        .ok_or(ParseFailure::Malformed)
}

fn parse_any_certificates(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, ParseFailure> {
    let semantic = semantic_key_packets(data, budget)?;
    let (iterator, _) = PublicOrSecret::from_reader_many(Cursor::new(semantic.as_slice()))
        .map_err(|_| ParseFailure::Malformed)?;
    let certificates = iterator
        .take(MAX_CERTIFICATES_PER_REQUEST + 1)
        .map(|entry| {
            entry.map(|entry| match entry {
                PublicOrSecret::Public(public) => public,
                PublicOrSecret::Secret(secret) => secret.to_public_key(),
            })
        })
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| ParseFailure::Malformed)?;
    if certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
        return Err(ParseFailure::ResourceLimit);
    }
    validate_certificate_versions(&certificates)?;
    validate_certificate_shapes(&certificates, budget)?;
    (!certificates.is_empty())
        .then_some(certificates)
        .ok_or(ParseFailure::Malformed)
}

pub(super) fn parse_public_key_documents(
    documents: &[Vec<u8>],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, OpenPgpReadError> {
    if documents.len() > MAX_PUBLIC_KEY_DOCUMENTS {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    let mut certificates = Vec::new();
    let mut saw_unsupported_version = false;
    for document in documents {
        match parse_public_certificates(document, budget) {
            Ok(parsed) => {
                if certificates.len() + parsed.len() > MAX_CERTIFICATES_PER_REQUEST {
                    return Err(OpenPgpReadError::ResourceLimit);
                }
                certificates.extend(parsed);
            }
            Err(ParseFailure::UnsupportedKeyVersion) => saw_unsupported_version = true,
            Err(ParseFailure::Malformed) => return Err(OpenPgpReadError::InvalidArgument),
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
        }
    }
    if certificates.is_empty() && saw_unsupported_version {
        return Err(OpenPgpReadError::InvalidArgument);
    }
    Ok(certificates)
}

fn validate_certificate_versions(certificates: &[SignedPublicKey]) -> Result<(), ParseFailure> {
    for certificate in certificates {
        if matches!(
            certificate.primary_key.version(),
            KeyVersion::V2 | KeyVersion::V3
        ) || certificate
            .public_subkeys
            .iter()
            .any(|subkey| matches!(subkey.version(), KeyVersion::V2 | KeyVersion::V3))
        {
            return Err(ParseFailure::UnsupportedKeyVersion);
        }
    }
    Ok(())
}

fn validate_certificate_shapes(
    certificates: &[SignedPublicKey],
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    for certificate in certificates {
        let component_count = certificate
            .public_subkeys
            .len()
            .checked_add(1)
            .ok_or(ParseFailure::ResourceLimit)?;
        if component_count > MAX_COMPONENTS_PER_CERTIFICATE {
            return Err(ParseFailure::ResourceLimit);
        }
        let identity_count = certificate
            .details
            .users
            .len()
            .checked_add(certificate.details.user_attributes.len())
            .ok_or(ParseFailure::ResourceLimit)?;
        if identity_count > MAX_IDENTITIES_PER_CERTIFICATE
            || certificate.details.direct_signatures.len() > MAX_SIGNATURES_PER_OBJECT
            || certificate.details.revocation_signatures.len() > MAX_SIGNATURES_PER_OBJECT
            || certificate
                .details
                .users
                .iter()
                .any(|user| user.signatures.len() > MAX_SIGNATURES_PER_OBJECT)
            || certificate
                .details
                .user_attributes
                .iter()
                .any(|attribute| attribute.signatures.len() > MAX_SIGNATURES_PER_OBJECT)
            || certificate
                .public_subkeys
                .iter()
                .any(|subkey| subkey.signatures.len() > MAX_SIGNATURES_PER_OBJECT)
        {
            return Err(ParseFailure::ResourceLimit);
        }

        validate_public_key_parameters(&certificate.primary_key)?;
        for subkey in &certificate.public_subkeys {
            validate_public_key_parameters(&subkey.key)?;
        }

        let signature_count = certificate
            .details
            .direct_signatures
            .len()
            .checked_add(certificate.details.revocation_signatures.len())
            .and_then(|count| {
                certificate
                    .details
                    .users
                    .iter()
                    .try_fold(count, |count, user| {
                        count.checked_add(user.signatures.len())
                    })
            })
            .and_then(|count| {
                certificate
                    .details
                    .user_attributes
                    .iter()
                    .try_fold(count, |count, attribute| {
                        count.checked_add(attribute.signatures.len())
                    })
            })
            .and_then(|count| {
                certificate
                    .public_subkeys
                    .iter()
                    .try_fold(count, |count, subkey| {
                        count.checked_add(subkey.signatures.len())
                    })
            })
            .ok_or(ParseFailure::ResourceLimit)?;
        budget.charge_shape(component_count, identity_count, signature_count)?;
    }
    Ok(())
}

fn validate_public_key_parameters(key: &impl KeyDetails) -> Result<(), ParseFailure> {
    validate_public_key_parameter_values(key.public_params())
}

fn validate_public_key_parameter_values(params: &PublicParams) -> Result<(), ParseFailure> {
    let serialized_len = params.write_len();
    if serialized_len > MAX_PUBLIC_KEY_PARAMETER_BYTES {
        return Err(ParseFailure::ResourceLimit);
    }

    match params {
        PublicParams::RSA(_) => {
            let bytes = serialize_params(params).ok_or(ParseFailure::Malformed)?;
            validate_rsa_parameter_bytes(&bytes)?;
        }
        PublicParams::DSA(_) | PublicParams::Elgamal(_) => {
            let bytes = serialize_params(params).ok_or(ParseFailure::Malformed)?;
            if serialized_mpi_bits(&bytes).ok_or(ParseFailure::Malformed)?
                > MAX_DISCRETE_LOG_KEY_BITS
            {
                return Err(ParseFailure::ResourceLimit);
            }
        }
        PublicParams::ECDSA(_) | PublicParams::ECDH(_) | PublicParams::EdDSALegacy(_)
            if serialized_len > MAX_ECC_PUBLIC_PARAMETER_BYTES =>
        {
            return Err(ParseFailure::ResourceLimit);
        }
        _ => {}
    }
    Ok(())
}

fn validate_rsa_parameter_bytes(bytes: &[u8]) -> Result<(), ParseFailure> {
    let modulus_bits = serialized_mpi_bits(bytes).ok_or(ParseFailure::Malformed)?;
    let (_, exponent) = take_mpi(bytes).ok_or(ParseFailure::Malformed)?;
    let (exponent, _) = take_mpi(exponent).ok_or(ParseFailure::Malformed)?;
    if modulus_bits > MAX_RSA_MODULUS_BITS || exponent.len() > MAX_RSA_PUBLIC_EXPONENT_BYTES {
        return Err(ParseFailure::ResourceLimit);
    }
    Ok(())
}

fn serialized_mpi_bits(bytes: &[u8]) -> Option<u32> {
    Some(u32::from(u16::from_be_bytes([
        *bytes.first()?,
        *bytes.get(1)?,
    ])))
}

pub(super) fn all_components(certificates: &[SignedPublicKey]) -> Vec<PublicComponent> {
    certificates
        .iter()
        .flat_map(certificate_components)
        .collect()
}

fn certificate_components(
    certificate: &SignedPublicKey,
) -> impl Iterator<Item = PublicComponent> + '_ {
    std::iter::once(PublicComponent::Primary(certificate.primary_key.clone())).chain(
        certificate
            .public_subkeys
            .iter()
            .map(|subkey| PublicComponent::Subkey(subkey.key.clone())),
    )
}

pub(super) fn inspect_certificate<'a>(
    certificate: &'a SignedPublicKey,
    candidates: &[PublicComponent],
    reference_time: u64,
    budget: &mut OpenPgpReadBudget,
) -> Result<CertificatePolicy<'a>, OpenPgpReadError> {
    let authentication = authenticate_certificate_bindings(certificate, budget)?;
    let authorized_revokers =
        authorized_revokers(&authentication.verified_direct, candidates, budget)?;
    let mut primary_revoked = authentication.primary_self_revoked;
    if !primary_revoked {
        'primary_revocations: for signature in &certificate.details.revocation_signatures {
            for candidate in &authorized_revokers {
                if candidate.verifies_key_revocation(signature, &certificate.primary_key, budget)? {
                    primary_revoked = true;
                    break 'primary_revocations;
                }
            }
        }
    }

    let mut primary_effective = authentication
        .verified_direct
        .iter()
        .copied()
        .filter(|signature| !signature_expired(signature, reference_time))
        .collect::<Vec<_>>();
    let mut verified_user_ids = Vec::new();
    for (user, identity) in certificate
        .details
        .users
        .iter()
        .zip(&authentication.user_ids)
    {
        let mut revoked = identity.self_revoked;
        if !revoked {
            'user_revocations: for signature in &user.signatures {
                if signature.typ() != Some(SignatureType::CertRevocation) {
                    continue;
                }
                for candidate in &authorized_revokers {
                    if candidate.verifies_certification_revocation(
                        signature,
                        &certificate.primary_key,
                        Tag::UserId,
                        &user.id,
                        budget,
                    )? {
                        revoked = true;
                        break 'user_revocations;
                    }
                }
            }
        }
        if !revoked
            && let Some(signature) = identity.effective_signature
            && !signature_expired(signature, reference_time)
        {
            if certificate.primary_key.version() != KeyVersion::V6 {
                primary_effective.push(signature);
            }
            if let Some(user_id) = user.id.as_str() {
                verified_user_ids.push(user_id.to_owned());
            }
        }
    }
    for (attribute, identity) in certificate
        .details
        .user_attributes
        .iter()
        .zip(&authentication.user_attributes)
    {
        let mut revoked = identity.self_revoked;
        if !revoked {
            'attribute_revocations: for signature in &attribute.signatures {
                if signature.typ() != Some(SignatureType::CertRevocation) {
                    continue;
                }
                for candidate in &authorized_revokers {
                    if candidate.verifies_certification_revocation(
                        signature,
                        &certificate.primary_key,
                        Tag::UserAttribute,
                        &attribute.attr,
                        budget,
                    )? {
                        revoked = true;
                        break 'attribute_revocations;
                    }
                }
            }
        }
        if !revoked
            && let Some(signature) = identity.effective_signature
            && !signature_expired(signature, reference_time)
            && certificate.primary_key.version() != KeyVersion::V6
        {
            primary_effective.push(signature);
        }
    }
    let primary_signature = newest_signature(primary_effective.into_iter());
    let primary = ComponentPolicy {
        key: &certificate.primary_key,
        authenticated: primary_signature.is_some(),
        effective_signature: primary_signature,
        key_flags: primary_signature.and_then(authenticated_key_flags),
        revoked: primary_revoked,
        signing_cross_certified: true,
    };

    let mut subkeys = Vec::with_capacity(certificate.public_subkeys.len());
    for ((subkey, component), self_revoked) in certificate
        .public_subkeys
        .iter()
        .zip(&authentication.subkeys)
        .zip(&authentication.subkey_self_revoked)
    {
        let binding = newest_signature(
            component
                .verified_bindings
                .iter()
                .copied()
                .filter(|signature| !signature_expired(signature, reference_time)),
        );
        let signing_cross_certified =
            if let Some(signature) = binding.and_then(Signature::embedded_signature) {
                budget.charge_public_key_verification()?;
                signature
                    .verify_primary_key_binding(&subkey.key, &certificate.primary_key)
                    .is_ok()
            } else {
                false
            };
        let mut revoked = *self_revoked;
        if !revoked {
            'subkey_revocations: for signature in &subkey.signatures {
                if signature.typ() != Some(SignatureType::SubkeyRevocation) {
                    continue;
                }
                for candidate in &authorized_revokers {
                    if candidate.verifies_subkey_revocation(
                        signature,
                        &certificate.primary_key,
                        &subkey.key,
                        budget,
                    )? {
                        revoked = true;
                        break 'subkey_revocations;
                    }
                }
            }
        }
        subkeys.push(ComponentPolicy {
            key: &subkey.key,
            authenticated: binding.is_some(),
            effective_signature: binding,
            key_flags: binding.and_then(authenticated_key_flags),
            revoked,
            signing_cross_certified,
        });
    }

    Ok(CertificatePolicy {
        primary,
        subkeys,
        verified_user_ids,
    })
}

/// Authenticates primary ownership and subkey bindings independently of time.
pub(super) fn authenticate_certificate_bindings<'a>(
    certificate: &'a SignedPublicKey,
    budget: &mut OpenPgpReadBudget,
) -> Result<CertificateAuthentication<'a>, OpenPgpReadError> {
    let mut verified_direct = Vec::new();
    for signature in &certificate.details.direct_signatures {
        if signature.typ() != Some(SignatureType::Key) {
            continue;
        }
        budget.charge_public_key_verification()?;
        if signature.verify_key(&certificate.primary_key).is_ok() {
            verified_direct.push(signature);
        }
    }

    let mut primary_self_revoked = false;
    for signature in &certificate.details.revocation_signatures {
        budget.charge_public_key_verification()?;
        primary_self_revoked |= signature.verify_key(&certificate.primary_key).is_ok();
    }

    let mut user_ids = Vec::with_capacity(certificate.details.users.len());
    for user in &certificate.details.users {
        let mut certifications = Vec::new();
        let mut self_revoked = false;
        for signature in &user.signatures {
            if is_certification(signature.typ()) {
                budget.charge_public_key_verification()?;
                if signature
                    .verify_certification(&certificate.primary_key, Tag::UserId, &user.id)
                    .is_ok()
                {
                    certifications.push(signature);
                }
            } else if signature.typ() == Some(SignatureType::CertRevocation) {
                budget.charge_public_key_verification()?;
                self_revoked |= signature
                    .verify_certification(&certificate.primary_key, Tag::UserId, &user.id)
                    .is_ok();
            }
        }
        user_ids.push(IdentityAuthentication {
            effective_signature: newest_signature(certifications.into_iter()),
            self_revoked,
        });
    }

    let mut user_attributes = Vec::with_capacity(certificate.details.user_attributes.len());
    for attribute in &certificate.details.user_attributes {
        let mut certifications = Vec::new();
        let mut self_revoked = false;
        for signature in &attribute.signatures {
            if is_certification(signature.typ()) {
                budget.charge_public_key_verification()?;
                if signature
                    .verify_certification(
                        &certificate.primary_key,
                        Tag::UserAttribute,
                        &attribute.attr,
                    )
                    .is_ok()
                {
                    certifications.push(signature);
                }
            } else if signature.typ() == Some(SignatureType::CertRevocation) {
                budget.charge_public_key_verification()?;
                self_revoked |= signature
                    .verify_certification(
                        &certificate.primary_key,
                        Tag::UserAttribute,
                        &attribute.attr,
                    )
                    .is_ok();
            }
        }
        user_attributes.push(IdentityAuthentication {
            effective_signature: newest_signature(certifications.into_iter()),
            self_revoked,
        });
    }

    let mut subkeys = Vec::with_capacity(certificate.public_subkeys.len());
    let mut subkey_self_revoked = Vec::with_capacity(certificate.public_subkeys.len());
    for subkey in &certificate.public_subkeys {
        let mut bindings = Vec::new();
        let mut self_revoked = false;
        for signature in &subkey.signatures {
            if signature.typ() == Some(SignatureType::SubkeyBinding) {
                budget.charge_public_key_verification()?;
                if signature
                    .verify_subkey_binding(&certificate.primary_key, &subkey.key)
                    .is_ok()
                {
                    bindings.push(signature);
                }
            } else if signature.typ() == Some(SignatureType::SubkeyRevocation) {
                budget.charge_public_key_verification()?;
                self_revoked |= signature
                    .verify_subkey_binding(&certificate.primary_key, &subkey.key)
                    .is_ok();
            }
        }
        subkeys.push(SubkeyAuthentication {
            verified_bindings: bindings,
        });
        subkey_self_revoked.push(self_revoked);
    }

    Ok(CertificateAuthentication {
        subkeys,
        verified_direct,
        primary_self_revoked,
        user_ids,
        user_attributes,
        subkey_self_revoked,
    })
}

fn is_certification(signature_type: Option<SignatureType>) -> bool {
    matches!(
        signature_type,
        Some(
            SignatureType::CertGeneric
                | SignatureType::CertPersona
                | SignatureType::CertCasual
                | SignatureType::CertPositive
        )
    )
}

fn authorized_revokers<'a>(
    verified_direct: &[&Signature],
    candidates: &'a [PublicComponent],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<&'a PublicComponent>, OpenPgpReadError> {
    let mut declarations = Vec::new();
    for signature in verified_direct {
        let Some(config) = signature.config() else {
            continue;
        };
        for subpacket in config.hashed_subpackets() {
            if let SubpacketData::RevocationKey(key) = &subpacket.data {
                let revoker = DesignatedRevokerId {
                    algorithm: u8::from(key.algorithm),
                    fingerprint: key.fingerprint.to_vec(),
                };
                budget.charge_designated_revoker(revoker.clone())?;
                insert_designated_revoker(&mut declarations, revoker)?;
            }
        }
    }

    let mut authorized = Vec::new();
    let mut authorized_ids = Vec::new();
    for candidate in candidates {
        let id = DesignatedRevokerId {
            algorithm: u8::from(candidate.algorithm()),
            fingerprint: candidate.fingerprint().as_bytes().to_vec(),
        };
        if declarations.contains(&id) && insert_designated_revoker(&mut authorized_ids, id)? {
            authorized.push(candidate);
        }
    }
    Ok(authorized)
}

fn insert_designated_revoker(
    revokers: &mut Vec<DesignatedRevokerId>,
    candidate: DesignatedRevokerId,
) -> Result<bool, OpenPgpReadError> {
    if revokers.contains(&candidate) {
        return Ok(false);
    }
    if revokers.len() >= MAX_DESIGNATED_REVOKERS_PER_REQUEST {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    revokers.push(candidate);
    Ok(true)
}

fn authenticated_key_flags(signature: &Signature) -> Option<KeyFlags> {
    signature.config().and_then(|config| {
        config.hashed_subpackets().find_map(|subpacket| {
            if let SubpacketData::KeyFlags(flags) = &subpacket.data {
                Some(flags.clone())
            } else {
                None
            }
        })
    })
}

fn newest_signature<'a>(signatures: impl Iterator<Item = &'a Signature>) -> Option<&'a Signature> {
    signatures.reduce(|current, candidate| {
        if candidate.created().map(|time| time.as_secs())
            > current.created().map(|time| time.as_secs())
        {
            candidate
        } else {
            current
        }
    })
}

fn signature_expired(signature: &Signature, reference_time: u64) -> bool {
    let Some(duration) = signature.signature_expiration_time() else {
        return false;
    };
    signature_expired_at(
        signature
            .created()
            .map(|created| u64::from(created.as_secs())),
        u64::from(duration.as_secs()),
        reference_time,
    )
}

fn signature_expired_at(created: Option<u64>, duration: u64, reference_time: u64) -> bool {
    const UINT32_MASK: u64 = u32::MAX as u64;
    let duration = duration & UINT32_MASK;
    if duration == 0 {
        return false;
    }
    let created = created.unwrap_or(0) & UINT32_MASK;
    let expires = created.wrapping_add(duration) & UINT32_MASK;
    let reference_time = reference_time & UINT32_MASK;
    expires != 0 && expires <= reference_time
}

fn public_key_info(
    policy: &CertificatePolicy<'_>,
    original_packets: &[u8],
    reference_time: u64,
) -> Option<OpenPgpPublicKeyInfo> {
    let primary = &policy.primary;
    let certificate_revoked = primary.revoked;
    let primary_available = primary.authenticated
        && !certificate_revoked
        && !component_is_expired(primary, reference_time);
    let subkeys = policy
        .subkeys
        .iter()
        .filter(|subkey| subkey.authenticated)
        .map(|subkey| OpenPgpPublicSubKeyInfo {
            fingerprint: fingerprint_hex(subkey.key),
            keygrip: keygrip(subkey.key.public_params()),
            key_id: key_id_hex(subkey.key),
            algorithm: algorithm_name(subkey.key.algorithm()),
            bit_strength: bit_strength(subkey.key.public_params()),
            can_sign: primary_available && signing_component_usable(subkey, reference_time, true),
            can_encrypt: primary_available
                && subkey.authenticated
                && !subkey.revoked
                && !component_is_expired(subkey, reference_time)
                && can_encrypt(subkey.key.algorithm(), subkey.key_flags.as_ref()),
            revoked: subkey.revoked,
            created_at_epoch_seconds: Some(u64::from(subkey.key.created_at().as_secs())),
            expires_at_epoch_seconds: component_expiration(subkey),
        })
        .collect::<Vec<_>>();
    let primary_can_sign =
        primary_available && signing_component_usable(primary, reference_time, false);
    let certificate_can_encrypt = primary_available
        && (can_encrypt(primary.key.algorithm(), primary.key_flags.as_ref())
            || policy.subkeys.iter().any(|subkey| {
                subkey.authenticated
                    && !subkey.revoked
                    && !component_is_expired(subkey, reference_time)
                    && can_encrypt(subkey.key.algorithm(), subkey.key_flags.as_ref())
            }));
    let public_key_armored = armor_public_key_packets(original_packets)?;
    Some(OpenPgpPublicKeyInfo {
        fingerprint: fingerprint_hex(primary.key),
        keygrip: keygrip(primary.key.public_params()),
        key_id: key_id_hex(primary.key),
        algorithm: algorithm_name(primary.key.algorithm()),
        bit_strength: bit_strength(primary.key.public_params()),
        user_ids: policy.verified_user_ids.clone(),
        emails: distinct_emails(&policy.verified_user_ids),
        created_at_epoch_seconds: Some(u64::from(primary.key.created_at().as_secs())),
        expires_at_epoch_seconds: component_expiration(primary),
        revoked: primary.revoked,
        can_sign: primary_can_sign || subkeys.iter().any(|subkey| subkey.can_sign),
        can_encrypt: certificate_can_encrypt,
        public_key_armored,
        subkeys,
    })
}

fn armor_public_key_packets(packets: &[u8]) -> Option<String> {
    let mut output = Vec::new();
    armor::write(
        &RawOpenPgpPackets(packets),
        BlockType::PublicKey,
        &mut output,
        None,
        true,
    )
    .ok()?;
    String::from_utf8(output).ok()
}

fn metadata_keys(
    policy: &CertificatePolicy<'_>,
    reference_time: u64,
) -> Vec<OpenPgpKeyMetadataKey> {
    let mut result = Vec::new();
    let primary_available = policy.primary.authenticated
        && !policy.primary.revoked
        && !component_is_expired(&policy.primary, reference_time);
    if primary_available
        && let Some(metadata) = metadata_key(&policy.primary, true, reference_time, true)
    {
        result.push(metadata);
    }
    result.extend(
        policy
            .subkeys
            .iter()
            .filter_map(|subkey| metadata_key(subkey, false, reference_time, primary_available)),
    );
    result
}

fn metadata_key<K: KeyDetails>(
    component: &ComponentPolicy<'_, K>,
    is_primary: bool,
    reference_time: u64,
    primary_available: bool,
) -> Option<OpenPgpKeyMetadataKey> {
    let mut capabilities = Vec::new();
    if primary_available && signing_component_usable(component, reference_time, !is_primary) {
        capabilities.push("sign".to_owned());
    }
    if primary_available && encryption_component_usable(component, reference_time) {
        capabilities.push("decrypt".to_owned());
    }
    if !is_primary && capabilities.is_empty() {
        return None;
    }
    Some(OpenPgpKeyMetadataKey {
        keygrip: keygrip(component.key.public_params())?,
        fingerprint: fingerprint_hex(component.key),
        algorithm: algorithm_name(component.key.algorithm()),
        capabilities,
    })
}

fn component_expiration<K>(component: &ComponentPolicy<'_, K>) -> Option<u64>
where
    K: KeyDetails,
{
    let duration = component.effective_signature?.key_expiration_time()?;
    (duration.as_secs() != 0)
        .then(|| u64::from(component.key.created_at().as_secs()) + u64::from(duration.as_secs()))
}

pub(super) fn can_sign(algorithm: PublicKeyAlgorithm, flags: Option<&KeyFlags>) -> bool {
    flags.map_or_else(|| algorithm.can_sign(), KeyFlags::sign)
}

pub(super) fn can_encrypt(algorithm: PublicKeyAlgorithm, flags: Option<&KeyFlags>) -> bool {
    flags.map_or_else(
        || algorithm.can_encrypt(),
        |flags| flags.encrypt_comms() || flags.encrypt_storage(),
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
        && !component.revoked
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
        && !component.revoked
        && !component_is_expired(component, reference_time)
        && can_encrypt(component.key.algorithm(), component.key_flags.as_ref())
}

fn parse_detached_signatures(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<Signature>, OpenPgpReadError> {
    preflight_openpgp_packets(data, budget).map_err(|error| match error {
        ParseFailure::ResourceLimit => OpenPgpReadError::ResourceLimit,
        ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion => {
            OpenPgpReadError::InvalidArgument
        }
    })?;
    let (iterator, _) = DetachedSignature::from_reader_many(Cursor::new(data))
        .map_err(|_| OpenPgpReadError::InvalidArgument)?;
    let signatures = iterator
        .take(MAX_DETACHED_SIGNATURES + 1)
        .map(|signature| signature.map(|signature| signature.signature))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| OpenPgpReadError::InvalidArgument)?;
    if signatures.len() > MAX_DETACHED_SIGNATURES {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    budget.charge_signatures(signatures.len())?;
    (!signatures.is_empty())
        .then_some(signatures)
        .ok_or(OpenPgpReadError::InvalidArgument)
}

fn parse_clear_signed_message(
    input: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<(Vec<Signature>, Zeroizing<Vec<u8>>), OpenPgpReadError> {
    const SIGNED_MESSAGE_MARKER: &[u8] = b"-----BEGIN PGP SIGNED MESSAGE-----";
    const SIGNATURE_MARKER: &[u8] = b"-----BEGIN PGP SIGNATURE-----";

    // Mirror Keyguard's historical clear-sign parser: normalize every input
    // newline to LF, use the final signature marker (so an escaped marker may
    // occur in the body), undo dash escaping, strip per-line trailing RFC 4880
    // whitespace, and hash lines joined by CRLF. Both plaintext allocations are
    // erased when this function/verification returns.
    let normalized = normalize_lf(input);
    let header_scan_start = find_subslice(&normalized, SIGNED_MESSAGE_MARKER).unwrap_or(0);
    let header_end = find_subslice(&normalized[header_scan_start..], b"\n\n")
        .map(|offset| header_scan_start + offset)
        .ok_or(OpenPgpReadError::InvalidArgument)?;
    if header_end - header_scan_start > MAX_CLEAR_SIGNED_HEADER_BYTES {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    let signature_index = rfind_subslice(&normalized, SIGNATURE_MARKER)
        .filter(|index| *index > header_end)
        .ok_or(OpenPgpReadError::InvalidArgument)?;

    let mut body_end = signature_index;
    if body_end > header_end + 2 && normalized.get(body_end - 1) == Some(&b'\n') {
        body_end -= 1;
    }
    let body = normalized
        .get(header_end + 2..body_end)
        .ok_or(OpenPgpReadError::InvalidArgument)?;
    let mut canonical = Zeroizing::new(Vec::with_capacity(body.len()));
    for (index, line) in body.split(|byte| *byte == b'\n').enumerate() {
        if index >= MAX_CLEAR_SIGNED_LINES || line.len() > MAX_CLEAR_SIGNED_LINE_BYTES {
            return Err(OpenPgpReadError::ResourceLimit);
        }
        if index > 0 {
            canonical.extend_from_slice(b"\r\n");
        }
        let line = line.strip_prefix(b"- ").unwrap_or(line);
        let content_end = line
            .iter()
            .rposition(|byte| !matches!(byte, b' ' | b'\t' | b'\r' | b'\n'))
            .map_or(0, |index| index + 1);
        canonical.extend_from_slice(&line[..content_end]);
    }

    let signatures = parse_detached_signatures(
        normalized
            .get(signature_index..)
            .ok_or(OpenPgpReadError::InvalidArgument)?,
        budget,
    )?;
    Ok((signatures, canonical))
}

fn normalize_lf(input: &[u8]) -> Zeroizing<Vec<u8>> {
    let mut output = Zeroizing::new(Vec::with_capacity(input.len()));
    let mut index = 0;
    while index < input.len() {
        match input[index] {
            b'\r' => {
                output.push(b'\n');
                index += usize::from(input.get(index + 1) == Some(&b'\n')) + 1;
            }
            byte => {
                output.push(byte);
                index += 1;
            }
        }
    }
    output
}

fn find_subslice(input: &[u8], needle: &[u8]) -> Option<usize> {
    input
        .windows(needle.len())
        .position(|window| window == needle)
}

fn rfind_subslice(input: &[u8], needle: &[u8]) -> Option<usize> {
    input
        .windows(needle.len())
        .rposition(|window| window == needle)
}

fn prepare_verification(
    signatures: &[Signature],
    certificates: &[SignedPublicKey],
    reference_time: u64,
    mut budget: OpenPgpReadBudget,
) -> Result<PreparedVerification, OpenPgpReadError> {
    let signature = signatures
        .iter()
        .find_map(|signature| {
            select_signer(signature, certificates).map(|signer| (signature.clone(), Some(signer)))
        })
        .or_else(|| {
            signatures
                .first()
                .cloned()
                .map(|signature| (signature, None))
        });
    let Some((signature, signer)) = signature else {
        return Err(OpenPgpReadError::InvalidArgument);
    };
    let key_id = signature_key_id(&signature);
    let created_at_epoch_seconds = signature
        .created()
        .map(|created| u64::from(created.as_secs()));
    let Some(signer) = signer else {
        return Ok(PreparedVerification::Missing(OpenPgpVerification {
            status: OpenPgpVerificationStatus::MissingPublicKey as i32,
            key_id,
            fingerprint: None,
            user_ids: Vec::new(),
            created_at_epoch_seconds,
            warnings: Vec::new(),
        }));
    };

    let candidates = all_components(certificates);
    let certificate = certificates
        .get(signer.certificate_index)
        .ok_or(OpenPgpReadError::Internal)?;
    let policy = inspect_certificate(certificate, &candidates, reference_time, &mut budget)?;
    let (component_authenticated, component_revoked, component_expired, cross_certified) =
        match signer.component_index {
            None => (
                policy.primary.authenticated,
                policy.primary.revoked,
                component_is_expired(&policy.primary, reference_time),
                true,
            ),
            Some(index) => {
                let component = policy
                    .subkeys
                    .get(index)
                    .ok_or(OpenPgpReadError::Internal)?;
                (
                    component.authenticated,
                    component.revoked,
                    component_is_expired(component, reference_time),
                    component.signing_cross_certified,
                )
            }
        };
    let signer_authenticated = component_authenticated && cross_certified;
    let mut warnings = Vec::new();
    if signer_authenticated && (policy.primary.revoked || component_revoked) {
        warnings.push(OpenPgpVerificationWarning::KeyRevoked as i32);
    }
    if signer_authenticated
        && (component_is_expired(&policy.primary, reference_time) || component_expired)
    {
        warnings.push(OpenPgpVerificationWarning::KeyExpired as i32);
    }
    if signature_expired(&signature, reference_time) {
        warnings.push(OpenPgpVerificationWarning::SignatureExpired as i32);
    }
    let fingerprint = signer.component.fingerprint_hex();
    Ok(PreparedVerification::Candidate {
        signature: Box::new(signature),
        signer: Box::new(signer.component),
        result: OpenPgpVerification {
            status: OpenPgpVerificationStatus::Invalid as i32,
            key_id,
            fingerprint: Some(fingerprint),
            user_ids: if signer_authenticated {
                policy.verified_user_ids.clone()
            } else {
                Vec::new()
            },
            created_at_epoch_seconds,
            warnings,
        },
        budget,
    })
}

fn verify_prepared(
    prepared: PreparedVerification,
    mut input: impl Read,
) -> Result<Vec<u8>, OpenPgpReadError> {
    match prepared {
        PreparedVerification::Missing(result) => {
            io::copy(&mut input, &mut io::sink()).map_err(|_| OpenPgpReadError::Internal)?;
            Ok(result.encode_to_vec())
        }
        PreparedVerification::Candidate {
            signature,
            signer,
            mut result,
            mut budget,
        } => {
            if signer.verify(&signature, &mut input, &mut budget)? {
                result.status = OpenPgpVerificationStatus::Valid as i32;
            }
            Ok(result.encode_to_vec())
        }
    }
}

/// Applies the OpenPGP certificate, revocation, expiry, cross-certification,
/// and warning policy to a message signature whose data cryptography has
/// already been evaluated by the streaming OpenPGP reader.
pub(super) fn evaluate_preverified_signature(
    signature: &Signature,
    certificates: &[SignedPublicKey],
    reference_time: u64,
    cryptographically_valid: bool,
) -> Result<OpenPgpVerification, OpenPgpReadError> {
    match prepare_verification(
        std::slice::from_ref(signature),
        certificates,
        reference_time,
        OpenPgpReadBudget::default(),
    )? {
        PreparedVerification::Missing(result) => Ok(result),
        PreparedVerification::Candidate { mut result, .. } => {
            if cryptographically_valid {
                result.status = OpenPgpVerificationStatus::Valid as i32;
            }
            Ok(result)
        }
    }
}

fn select_signer(
    signature: &Signature,
    certificates: &[SignedPublicKey],
) -> Option<SelectedSigner> {
    let has_issuer =
        !signature.issuer_key_id().is_empty() || !signature.issuer_fingerprint().is_empty();
    if !has_issuer {
        return None;
    }
    for (certificate_index, certificate) in certificates.iter().enumerate() {
        if signature_matches_key(signature, &certificate.primary_key) {
            return Some(SelectedSigner {
                certificate_index,
                component_index: None,
                component: PublicComponent::Primary(certificate.primary_key.clone()),
            });
        }
        for (component_index, subkey) in certificate.public_subkeys.iter().enumerate() {
            if signature_matches_key(signature, &subkey.key) {
                return Some(SelectedSigner {
                    certificate_index,
                    component_index: Some(component_index),
                    component: PublicComponent::Subkey(subkey.key.clone()),
                });
            }
        }
    }
    None
}

fn signature_matches_key(signature: &Signature, key: &impl KeyDetails) -> bool {
    signature
        .issuer_key_id()
        .iter()
        .any(|issuer| **issuer == key.legacy_key_id())
        || signature
            .issuer_fingerprint()
            .iter()
            .any(|issuer| issuer.as_bytes() == key.fingerprint().as_bytes())
}

fn signature_key_id(signature: &Signature) -> String {
    if let Some(key_id) = signature.issuer_key_id().first() {
        return hex_upper(key_id.as_ref());
    }
    if let Some(fingerprint) = signature.issuer_fingerprint().first() {
        let bytes = fingerprint.as_bytes();
        return hex_upper(&bytes[bytes.len().saturating_sub(8)..]);
    }
    "0000000000000000".to_owned()
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

pub(super) fn reference_time(explicit: Option<u64>) -> u64 {
    explicit.unwrap_or_else(|| {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_secs())
    })
}

fn encode_parse_error(reason: OpenPgpPublicKeyParseErrorReason) -> Vec<u8> {
    OpenPgpPublicKeyParseResult {
        result: Some(open_pgp_public_key_parse_result::Result::Error(
            OpenPgpPublicKeyParseError {
                reason: reason as i32,
            },
        )),
    }
    .encode_to_vec()
}

pub(super) fn normalize_fingerprint(value: &str) -> String {
    value
        .bytes()
        .filter(u8::is_ascii_alphanumeric)
        .map(|byte| char::from(byte.to_ascii_uppercase()))
        .collect()
}

pub(super) fn fingerprint_hex(key: &impl KeyDetails) -> String {
    hex_upper(key.fingerprint().as_bytes())
}

fn key_id_hex(key: &impl KeyDetails) -> String {
    hex_upper(key.legacy_key_id().as_ref())
}

fn algorithm_name(algorithm: PublicKeyAlgorithm) -> String {
    match algorithm {
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign => {
            "RSA".to_owned()
        }
        PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt => "ELGAMAL".to_owned(),
        PublicKeyAlgorithm::DSA => "DSA".to_owned(),
        PublicKeyAlgorithm::ECDH => "ECDH".to_owned(),
        PublicKeyAlgorithm::ECDSA => "ECDSA".to_owned(),
        PublicKeyAlgorithm::EdDSALegacy => "EDDSA".to_owned(),
        PublicKeyAlgorithm::X25519 => "X25519".to_owned(),
        PublicKeyAlgorithm::X448 => "X448".to_owned(),
        PublicKeyAlgorithm::Ed25519 => "ED25519".to_owned(),
        PublicKeyAlgorithm::Ed448 => "ED448".to_owned(),
        _ => format!("ALGO_{}", u8::from(algorithm)),
    }
}

fn bit_strength(params: &PublicParams) -> Option<u32> {
    match params {
        PublicParams::RSA(_) | PublicParams::DSA(_) | PublicParams::Elgamal(_) => {
            let bytes = serialize_params(params)?;
            Some(u32::from(u16::from_be_bytes([
                *bytes.first()?,
                *bytes.get(1)?,
            ])))
        }
        PublicParams::ECDSA(params) => Some(u32::from(params.curve().nbits())),
        PublicParams::ECDH(params) => Some(legacy_curve_bit_strength(params.curve())),
        PublicParams::EdDSALegacy(params) => Some(legacy_curve_bit_strength(params.curve())),
        PublicParams::Ed25519(_) | PublicParams::X25519(_) => Some(255),
        PublicParams::Ed448(_) | PublicParams::X448(_) => Some(448),
        _ => None,
    }
    .filter(|bits| *bits > 0)
}

fn legacy_curve_bit_strength(curve: ECCCurve) -> u32 {
    if matches!(curve, ECCCurve::Ed25519Legacy | ECCCurve::Curve25519Legacy) {
        256
    } else {
        u32::from(curve.nbits())
    }
}

fn keygrip(params: &PublicParams) -> Option<String> {
    let input = match params {
        PublicParams::RSA(_) => {
            let bytes = serialize_params(params)?;
            let (modulus, _) = take_mpi(&bytes)?;
            if modulus.first().is_some_and(|byte| byte & 0x80 != 0) {
                let mut signed = Vec::with_capacity(modulus.len() + 1);
                signed.push(0);
                signed.extend_from_slice(modulus);
                signed
            } else {
                modulus.to_vec()
            }
        }
        PublicParams::Ed25519(params) => {
            ecc_keygrip_input(ECCCurve::Ed25519Legacy, params.key.as_bytes())?
        }
        PublicParams::X25519(params) => {
            ecc_keygrip_input(ECCCurve::Curve25519Legacy, params.key.as_bytes())?
        }
        PublicParams::EdDSALegacy(params) => {
            let bytes = serialize_params(params)?;
            let q = serialized_ecc_point(&bytes)?;
            let q = q.strip_prefix(&[0x40]).unwrap_or(q);
            ecc_keygrip_input(params.curve(), q)?
        }
        PublicParams::ECDH(params) => {
            let bytes = serialize_params(params)?;
            let q = serialized_ecc_point(&bytes)?;
            let q = if params.curve() == ECCCurve::Curve25519Legacy {
                q.strip_prefix(&[0x40]).unwrap_or(q)
            } else {
                q
            };
            ecc_keygrip_input(params.curve(), q)?
        }
        PublicParams::ECDSA(params) => {
            let bytes = serialize_params(params)?;
            ecc_keygrip_input(params.curve(), serialized_ecc_point(&bytes)?)?
        }
        _ => return None,
    };
    let digest = aws_lc_rs::digest::digest(&aws_lc_rs::digest::SHA1_FOR_LEGACY_USE_ONLY, &input);
    Some(hex_upper(digest.as_ref()))
}

fn serialize_params(params: &impl Serialize) -> Option<Vec<u8>> {
    let mut bytes = Vec::new();
    params.to_writer(&mut bytes).ok()?;
    Some(bytes)
}

fn serialized_ecc_point(bytes: &[u8]) -> Option<&[u8]> {
    let oid_length = usize::from(*bytes.first()?);
    let mpi = bytes.get(1 + oid_length..)?;
    take_mpi(mpi).map(|(value, _)| value)
}

fn take_mpi(bytes: &[u8]) -> Option<(&[u8], &[u8])> {
    let bits = usize::from(u16::from_be_bytes([*bytes.first()?, *bytes.get(1)?]));
    let length = bits.div_ceil(8);
    let value = bytes.get(2..2 + length)?;
    Some((value, bytes.get(2 + length..)?))
}

fn ecc_keygrip_input(curve: ECCCurve, q: &[u8]) -> Option<Vec<u8>> {
    let (p, a, b, g, n) = curve_constants(&curve)?;
    let mut output = Vec::new();
    for (name, value) in [
        (b'p', p.as_slice()),
        (b'a', a.as_slice()),
        (b'b', b.as_slice()),
        (b'g', g.as_slice()),
        (b'n', n.as_slice()),
        (b'q', q),
    ] {
        output.push(b'(');
        output.extend_from_slice(b"1:");
        output.push(name);
        output.extend_from_slice(value.len().to_string().as_bytes());
        output.push(b':');
        output.extend_from_slice(value);
        output.push(b')');
    }
    Some(output)
}

type CurveConstants = (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>);

fn curve_constants(curve: &ECCCurve) -> Option<CurveConstants> {
    let values = match curve {
        ECCCurve::Ed25519Legacy => (
            "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED",
            "01",
            "2DFC9311D490018C7338BF8688861767FF8FF5B2BEBE27548A14B235ECA6874A",
            concat!(
                "04",
                "216936D3CD6E53FEC0A4E231FDD6DC5C692CC7609525A7B2C9562D608F25D51A",
                "6666666666666666666666666666666666666666666666666666666666666658"
            ),
            "1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED",
        ),
        ECCCurve::Curve25519Legacy => (
            "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED",
            "01DB41",
            "01",
            concat!(
                "04",
                "0000000000000000000000000000000000000000000000000000000000000009",
                "20AE19A1B8A086B4E01EDD2C7748D14C923D4D7E6D7C61B229E9C5A27ECED3D9"
            ),
            "1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED",
        ),
        ECCCurve::P256 => (
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            concat!(
                "04",
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"
            ),
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        ),
        ECCCurve::P384 => (
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF"
            ),
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFC"
            ),
            concat!(
                "B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE814112",
                "0314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF"
            ),
            concat!(
                "04",
                "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A38",
                "5502F25DBF55296C3A545E3872760AB73617DE4A96262C6F5D9E98BF9292DC29",
                "F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F"
            ),
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "C7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973"
            ),
        ),
        ECCCurve::P521 => (
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC",
            concat!(
                "51953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918E",
                "F109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46",
                "B503F00"
            ),
            concat!(
                "04",
                "00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B",
                "4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C",
                "2E5BD66",
                "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD1727",
                "3E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE9476",
                "9FD16650"
            ),
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409",
        ),
        _ => return None,
    };
    Some((
        decode_hex(values.0)?,
        decode_hex(values.1)?,
        decode_hex(values.2)?,
        decode_hex(values.3)?,
        decode_hex(values.4)?,
    ))
}

fn decode_hex(value: &str) -> Option<Vec<u8>> {
    if !value.len().is_multiple_of(2) {
        return None;
    }
    value
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| {
            let high = hex_nibble(pair[0])?;
            let low = hex_nibble(pair[1])?;
            Some((high << 4) | low)
        })
        .collect()
}

fn hex_nibble(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn hex_upper(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(char::from(HEX[usize::from(byte >> 4)]));
        output.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    output
}

fn distinct_emails(user_ids: &[String]) -> Vec<String> {
    let mut emails = Vec::new();
    for user_id in user_ids {
        let email = user_id
            .find('<')
            .and_then(|start| {
                user_id[start + 1..]
                    .find('>')
                    .map(|end| &user_id[start + 1..start + 1 + end])
            })
            .map(str::trim)
            .filter(|email| !email.is_empty())
            .or_else(|| {
                let value = user_id.trim();
                (value.contains('@') && !value.contains(' ')).then_some(value)
            });
        if let Some(email) = email
            && !emails.iter().any(|existing| existing == email)
        {
            emails.push(email.to_owned());
        }
    }
    emails
}

#[cfg(test)]
mod tests {
    use std::sync::{Mutex, MutexGuard};

    use pgp::{packet::PacketHeader, types::RsaPublicParams};

    use super::*;

    const PUBLIC_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/cv25519-public.asc");
    const SECRET_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/cv25519-secret.asc");
    const DETACHED_BODY: &[u8] = include_bytes!("../tests/fixtures/openpgp/detached-body.txt");
    const DETACHED_SIGNATURE: &[u8] =
        include_bytes!("../tests/fixtures/openpgp/detached-signature.asc");
    const CLEAR_SIGNED: &[u8] = include_bytes!("../tests/fixtures/openpgp/clear-signed.asc");
    const DESIGNATED_REVOKED_PUBLIC_KEY: &[u8] =
        include_bytes!("../tests/fixtures/openpgp/designated-revoked-public.asc");
    const DESIGNATED_REVOKER_PUBLIC_KEY: &[u8] =
        include_bytes!("../tests/fixtures/openpgp/designated-revoker-public.asc");
    const REFERENCE_TIME: u64 = 1_783_944_100;
    const PRIMARY_FINGERPRINT: &str = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7";
    const PRIMARY_KEYGRIP: &str = "894264A490F8D55E3E28378A7E44373782806220";
    const SUBKEY_FINGERPRINT: &str = "93ABCF804D85EE79D6E1DB0E77648D3E5D4E7699";
    const SUBKEY_KEYGRIP: &str = "85C1DE785BEE9244BAFBA73A09E6085BA7A35C8E";
    const USER_ID: &str = "Keyguard Test CV25519 <cv25519@test.invalid>";

    static VERIFIER_WORKER_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn verifier_worker_test_guard() -> MutexGuard<'static, ()> {
        VERIFIER_WORKER_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn detached_stream_request() -> OpenPgpDetachedVerifyStreamOpenRequest {
        OpenPgpDetachedVerifyStreamOpenRequest {
            signature: DETACHED_SIGNATURE.to_vec(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        }
    }

    fn parse_public_certificates_fresh(data: &[u8]) -> Result<Vec<SignedPublicKey>, ParseFailure> {
        parse_public_certificates(data, &mut OpenPgpReadBudget::default())
    }

    fn validate_certificate_shapes_fresh(
        certificates: &[SignedPublicKey],
    ) -> Result<(), ParseFailure> {
        validate_certificate_shapes(certificates, &mut OpenPgpReadBudget::default())
    }

    fn parse_detached_signatures_fresh(data: &[u8]) -> Result<Vec<Signature>, OpenPgpReadError> {
        parse_detached_signatures(data, &mut OpenPgpReadBudget::default())
    }

    fn parse_clear_signed_message_fresh(
        data: &[u8],
    ) -> Result<(Vec<Signature>, Zeroizing<Vec<u8>>), OpenPgpReadError> {
        parse_clear_signed_message(data, &mut OpenPgpReadBudget::default())
    }

    fn rsa_public_parameter_bytes(exponent_bytes: usize) -> Vec<u8> {
        let mut encoded = Vec::new();
        let mut modulus = vec![0u8; usize::try_from(MAX_RSA_MODULUS_BITS).unwrap().div_ceil(8)];
        modulus[0] = 0x80;
        *modulus.last_mut().expect("non-empty modulus") = 1;
        encoded.extend_from_slice(
            &u16::try_from(MAX_RSA_MODULUS_BITS)
                .expect("RSA limit fits MPI")
                .to_be_bytes(),
        );
        encoded.extend_from_slice(&modulus);

        let mut exponent = vec![0u8; exponent_bytes];
        if exponent_bytes == 3 {
            exponent.copy_from_slice(&[1, 0, 1]);
        } else {
            exponent[0] = if exponent_bytes == MAX_RSA_PUBLIC_EXPONENT_BYTES {
                0x80
            } else {
                1
            };
            *exponent.last_mut().expect("non-empty exponent") |= 1;
        }
        let exponent_bits =
            (exponent_bytes - 1) * 8 + (u8::BITS - exponent[0].leading_zeros()) as usize;
        encoded.extend_from_slice(
            &u16::try_from(exponent_bits)
                .expect("test exponent fits MPI")
                .to_be_bytes(),
        );
        encoded.extend_from_slice(&exponent);
        encoded
    }

    fn rsa_public_params() -> PublicParams {
        PublicParams::RSA(
            RsaPublicParams::try_from_reader(rsa_public_parameter_bytes(3).as_slice())
                .expect("8192-bit RSA parameters with exponent 65537 must parse"),
        )
    }

    fn parse_result(data: &[u8]) -> OpenPgpPublicKeyParseResult {
        OpenPgpPublicKeyParseResult::decode(
            parse_public_key_request(OpenPgpPublicKeyParseRequest {
                key_data: data.to_vec(),
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            })
            .expect("public-key parse request must produce a domain result")
            .as_slice(),
        )
        .expect("public-key parse result must decode")
    }

    fn detached_request(content: Vec<u8>, public_keys: Vec<Vec<u8>>) -> OpenPgpVerifyRequest {
        OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::Detached as i32,
            content,
            signature: DETACHED_SIGNATURE.to_vec(),
            public_keys,
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        }
    }

    fn verification(request: OpenPgpVerifyRequest) -> OpenPgpVerification {
        OpenPgpVerification::decode(
            verify_request(request)
                .expect("verification request must produce a domain result")
                .as_slice(),
        )
        .expect("verification result must decode")
    }

    #[test]
    fn fixed_gnupg_public_certificate_has_exact_authenticated_dto_and_rearmor() {
        let result = parse_result(PUBLIC_KEY);
        let success = match result.result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success,
            _ => panic!("fixed public certificate must parse"),
        };
        let key = success.keys.first().expect("one public certificate");
        assert_eq!(success.keys.len(), 1);
        assert_eq!(key.fingerprint, PRIMARY_FINGERPRINT);
        assert_eq!(key.keygrip.as_deref(), Some(PRIMARY_KEYGRIP));
        assert_eq!(key.key_id, "F83D947D29EFECF7");
        assert_eq!(key.algorithm, "EDDSA");
        assert_eq!(key.bit_strength, Some(256));
        assert_eq!(key.user_ids, [USER_ID]);
        assert_eq!(key.emails, ["cv25519@test.invalid"]);
        assert_eq!(key.created_at_epoch_seconds, Some(1_782_541_263));
        assert_eq!(key.expires_at_epoch_seconds, None);
        assert!(!key.revoked);
        assert!(key.can_sign);
        assert!(key.can_encrypt);
        assert_eq!(
            key.public_key_armored.trim_end(),
            std::str::from_utf8(PUBLIC_KEY)
                .expect("public fixture must be UTF-8")
                .trim_end(),
        );

        let subkey = key.subkeys.first().expect("one authenticated subkey");
        assert_eq!(key.subkeys.len(), 1);
        assert_eq!(subkey.fingerprint, SUBKEY_FINGERPRINT);
        assert_eq!(subkey.keygrip.as_deref(), Some(SUBKEY_KEYGRIP));
        assert_eq!(subkey.key_id, "77648D3E5D4E7699");
        assert_eq!(subkey.algorithm, "ECDH");
        assert_eq!(subkey.bit_strength, Some(256));
        assert!(!subkey.can_sign);
        assert!(subkey.can_encrypt);
        assert!(!subkey.revoked);
        assert_eq!(subkey.created_at_epoch_seconds, Some(1_782_541_292));
        assert_eq!(subkey.expires_at_epoch_seconds, None);
    }

    #[test]
    fn multi_ring_parser_preserves_input_order_and_original_packet_bytes() {
        let first_packets = decode_openpgp_packets(DESIGNATED_REVOKED_PUBLIC_KEY)
            .expect("designated-revocation victim ring must dearmor");
        let second_packets = decode_openpgp_packets(DESIGNATED_REVOKER_PUBLIC_KEY)
            .expect("second ring must dearmor");
        let mut collection_packets = first_packets.clone();
        collection_packets.extend_from_slice(&second_packets);
        let collection =
            armor_public_key_packets(&collection_packets).expect("test collection must armor");

        let first_fingerprint = match parse_result(DESIGNATED_REVOKED_PUBLIC_KEY).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => {
                success.keys[0].fingerprint.clone()
            }
            _ => panic!("first ring must parse"),
        };
        let second_fingerprint = match parse_result(DESIGNATED_REVOKER_PUBLIC_KEY).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => {
                success.keys[0].fingerprint.clone()
            }
            _ => panic!("second ring must parse"),
        };
        let parsed = match parse_result(collection.as_bytes()).result {
            Some(open_pgp_public_key_parse_result::Result::Success(success)) => success.keys,
            _ => panic!("multi-ring collection must parse"),
        };

        assert_eq!(parsed.len(), 2);
        assert_eq!(
            parsed
                .iter()
                .map(|key| key.fingerprint.as_str())
                .collect::<Vec<_>>(),
            [first_fingerprint.as_str(), second_fingerprint.as_str()],
        );
        assert_eq!(
            decode_openpgp_packets(parsed[0].public_key_armored.as_bytes()),
            Ok(first_packets),
        );
        assert_eq!(
            decode_openpgp_packets(parsed[1].public_key_armored.as_bytes()),
            Ok(second_packets),
        );
    }

    #[test]
    fn parser_returns_stable_expected_errors() {
        for (input, expected) in [
            (
                b" \r\n\t".as_slice(),
                OpenPgpPublicKeyParseErrorReason::Empty,
            ),
            (
                b"not an OpenPGP certificate".as_slice(),
                OpenPgpPublicKeyParseErrorReason::Malformed,
            ),
            (SECRET_KEY, OpenPgpPublicKeyParseErrorReason::Malformed),
        ] {
            let result = parse_result(input);
            let error = match result.result {
                Some(open_pgp_public_key_parse_result::Result::Error(error)) => error,
                _ => panic!("invalid public certificate must return a typed error"),
            };
            assert_eq!(error.reason, expected as i32);
        }
    }

    #[test]
    fn parser_rejects_legacy_v3_key_packets_with_typed_error() {
        // Old-format Public-Key packet with a structurally valid v3 RSA body.
        let v3_public_key = [
            0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
        ];
        let result = parse_result(&v3_public_key);
        let error = match result.result {
            Some(open_pgp_public_key_parse_result::Result::Error(error)) => error,
            _ => panic!("legacy public key must return a typed error"),
        };
        assert_eq!(
            error.reason,
            OpenPgpPublicKeyParseErrorReason::UnsupportedKeyVersion as i32,
        );
    }

    #[test]
    fn fixed_gnupg_detached_signature_reports_valid_invalid_and_missing_key() {
        let valid = verification(detached_request(
            DETACHED_BODY.to_vec(),
            vec![PUBLIC_KEY.to_vec()],
        ));
        assert_verification(&valid, OpenPgpVerificationStatus::Valid, 1_784_073_600);

        let mut changed_body = DETACHED_BODY.to_vec();
        changed_body[0] ^= 1;
        let invalid = verification(detached_request(changed_body, vec![PUBLIC_KEY.to_vec()]));
        assert_verification(&invalid, OpenPgpVerificationStatus::Invalid, 1_784_073_600);

        let missing = verification(detached_request(DETACHED_BODY.to_vec(), Vec::new()));
        assert_eq!(
            missing.status,
            OpenPgpVerificationStatus::MissingPublicKey as i32,
        );
        assert_eq!(missing.key_id, "F83D947D29EFECF7");
        assert_eq!(missing.fingerprint, None);
        assert!(missing.user_ids.is_empty());
        assert_eq!(missing.created_at_epoch_seconds, Some(1_784_073_600));
        assert!(missing.warnings.is_empty());
    }

    #[test]
    fn fixed_gnupg_clear_signature_verifies_lf_and_crlf_canonical_forms() {
        let fixture = std::str::from_utf8(CLEAR_SIGNED).expect("clear-sign fixture must be UTF-8");
        let crlf = fixture.replace('\n', "\r\n").into_bytes();
        let trailing_whitespace = fixture
            .replace("OpenPGP clear text\n", "OpenPGP clear text \t\n")
            .into_bytes();
        let glued_signature_marker = fixture
            .replace(
                "final line\n-----BEGIN PGP SIGNATURE-----",
                "final line-----BEGIN PGP SIGNATURE-----",
            )
            .into_bytes();
        for content in [
            CLEAR_SIGNED.to_vec(),
            crlf,
            trailing_whitespace,
            glued_signature_marker,
        ] {
            let result = verification(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content,
                signature: Vec::new(),
                public_keys: vec![PUBLIC_KEY.to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            });
            assert_verification(&result, OpenPgpVerificationStatus::Valid, 1_784_073_600);
        }

        let mut changed = CLEAR_SIGNED.to_vec();
        let offset = changed
            .windows(b"clear".len())
            .position(|window| window == b"clear")
            .expect("fixture body marker");
        changed[offset] ^= 1;
        let result = verification(OpenPgpVerifyRequest {
            kind: OpenPgpVerifyKind::ClearText as i32,
            content: changed,
            signature: Vec::new(),
            public_keys: vec![PUBLIC_KEY.to_vec()],
            reference_time_epoch_seconds: Some(REFERENCE_TIME),
        });
        assert_verification(&result, OpenPgpVerificationStatus::Invalid, 1_784_073_600);
    }

    #[test]
    fn malformed_verification_key_candidate_fails_but_legacy_candidate_is_skipped() {
        let malformed = detached_request(
            DETACHED_BODY.to_vec(),
            vec![PUBLIC_KEY.to_vec(), b"malformed".to_vec()],
        );
        assert_eq!(
            verify_request(malformed),
            Err(OpenPgpReadError::InvalidArgument),
        );

        let v3_public_key = vec![
            0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
        ];
        let valid = verification(detached_request(
            DETACHED_BODY.to_vec(),
            vec![v3_public_key, PUBLIC_KEY.to_vec()],
        ));
        assert_eq!(valid.status, OpenPgpVerificationStatus::Valid as i32);
    }

    #[test]
    fn public_key_document_and_certificate_shape_limits_are_inclusive() {
        let boundary_documents = vec![PUBLIC_KEY.to_vec(); MAX_PUBLIC_KEY_DOCUMENTS];
        assert_eq!(
            parse_public_key_documents(&boundary_documents, &mut OpenPgpReadBudget::default(),)
                .expect("document-count boundary must parse")
                .len(),
            MAX_CERTIFICATES_PER_REQUEST,
        );
        let over_limit_documents = vec![PUBLIC_KEY.to_vec(); MAX_PUBLIC_KEY_DOCUMENTS + 1];
        assert_eq!(
            parse_public_key_documents(&over_limit_documents, &mut OpenPgpReadBudget::default(),),
            Err(OpenPgpReadError::ResourceLimit),
        );

        let mut certificate = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let subkey = certificate.public_subkeys[0].clone();
        certificate
            .public_subkeys
            .resize(MAX_COMPONENTS_PER_CERTIFICATE - 1, subkey.clone());
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate.clone()]),
            Ok(())
        );
        certificate.public_subkeys.push(subkey);
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate]),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn identity_and_signature_limits_are_inclusive() {
        let mut certificate = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let user = certificate.details.users[0].clone();
        certificate
            .details
            .users
            .resize(MAX_IDENTITIES_PER_CERTIFICATE, user.clone());
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate.clone()]),
            Ok(())
        );
        certificate.details.users.push(user);
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate]),
            Err(ParseFailure::ResourceLimit),
        );

        let mut certificate = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let signature = certificate.details.users[0].signatures[0].clone();
        certificate.details.users[0]
            .signatures
            .resize(MAX_SIGNATURES_PER_OBJECT, signature.clone());
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate.clone()]),
            Ok(())
        );
        certificate.details.users[0].signatures.push(signature);
        assert_eq!(
            validate_certificate_shapes_fresh(&[certificate]),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn aggregate_shape_limits_are_inclusive_across_individually_valid_objects() {
        let mut component_heavy = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let subkey = component_heavy.public_subkeys[0].clone();
        component_heavy
            .public_subkeys
            .resize(MAX_COMPONENTS_PER_CERTIFICATE - 1, subkey);
        let boundary_components = vec![component_heavy.clone(); 8];
        let mut budget = OpenPgpReadBudget::default();
        assert_eq!(
            validate_certificate_shapes(&boundary_components, &mut budget),
            Ok(())
        );
        assert_eq!(budget.components, MAX_COMPONENTS_PER_REQUEST);
        let over_components = vec![component_heavy; 9];
        assert_eq!(
            validate_certificate_shapes(&over_components, &mut OpenPgpReadBudget::default(),),
            Err(ParseFailure::ResourceLimit),
        );

        let mut identity_heavy = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let user = identity_heavy.details.users[0].clone();
        identity_heavy
            .details
            .users
            .resize(MAX_IDENTITIES_PER_CERTIFICATE, user);
        let boundary_identities = vec![identity_heavy.clone(); 4];
        let mut budget = OpenPgpReadBudget::default();
        assert_eq!(
            validate_certificate_shapes(&boundary_identities, &mut budget),
            Ok(())
        );
        assert_eq!(budget.identities, MAX_IDENTITIES_PER_REQUEST);
        let over_identities = vec![identity_heavy; 5];
        assert_eq!(
            validate_certificate_shapes(&over_identities, &mut OpenPgpReadBudget::default(),),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn aggregate_signature_limit_blocks_per_object_multiplier() {
        let mut certificate = parse_public_certificates_fresh(PUBLIC_KEY)
            .expect("fixed certificate must parse")
            .remove(0);
        let mut user = certificate.details.users[0].clone();
        let signature = user.signatures[0].clone();
        user.signatures.resize(MAX_SIGNATURES_PER_OBJECT, signature);
        certificate.details.direct_signatures.clear();
        certificate.details.revocation_signatures.clear();
        certificate.details.users.clear();
        certificate.details.user_attributes.clear();
        certificate.public_subkeys.clear();
        certificate.details.users.resize(16, user.clone());

        let mut budget = OpenPgpReadBudget::default();
        assert_eq!(
            validate_certificate_shapes(&[certificate.clone()], &mut budget),
            Ok(())
        );
        assert_eq!(budget.signatures, MAX_SIGNATURES_PER_REQUEST);
        certificate.details.users.push(user);
        assert_eq!(
            validate_certificate_shapes(&[certificate], &mut OpenPgpReadBudget::default(),),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn packet_count_and_body_size_limits_are_inclusive() {
        let mut empty_packet = Vec::new();
        PacketHeader::new_fixed(Tag::Padding, 0)
            .to_writer(&mut empty_packet)
            .expect("padding header must serialize");
        let mut budget = OpenPgpReadBudget::default();
        assert_eq!(
            preflight_openpgp_packets(&empty_packet.repeat(MAX_PACKETS_PER_REQUEST), &mut budget),
            Ok(())
        );
        assert_eq!(budget.packets, MAX_PACKETS_PER_REQUEST);
        assert_eq!(
            preflight_openpgp_packets(
                &empty_packet.repeat(MAX_PACKETS_PER_REQUEST + 1),
                &mut OpenPgpReadBudget::default(),
            ),
            Err(ParseFailure::ResourceLimit),
        );

        let mut boundary_packet = Vec::new();
        PacketHeader::new_fixed(
            Tag::Padding,
            u32::try_from(MAX_PACKET_BODY_BYTES).expect("packet limit fits u32"),
        )
        .to_writer(&mut boundary_packet)
        .expect("padding header must serialize");
        boundary_packet.resize(boundary_packet.len() + MAX_PACKET_BODY_BYTES, 0);
        assert_eq!(
            preflight_openpgp_packets(&boundary_packet, &mut OpenPgpReadBudget::default(),),
            Ok(())
        );

        let mut oversized_header = Vec::new();
        PacketHeader::new_fixed(
            Tag::Padding,
            u32::try_from(MAX_PACKET_BODY_BYTES + 1).expect("packet limit fits u32"),
        )
        .to_writer(&mut oversized_header)
        .expect("padding header must serialize");
        assert_eq!(
            preflight_openpgp_packets(&oversized_header, &mut OpenPgpReadBudget::default(),),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn public_verification_work_limit_is_inclusive() {
        let mut budget = OpenPgpReadBudget::default();
        for _ in 0..MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST {
            assert_eq!(budget.charge_public_key_verification(), Ok(()));
        }
        assert_eq!(
            budget.charge_public_key_verification(),
            Err(OpenPgpReadError::ResourceLimit),
        );
        assert_eq!(
            budget.public_key_verifications,
            MAX_PUBLIC_KEY_VERIFICATIONS_PER_REQUEST,
        );
    }

    #[test]
    fn rsa_parameter_caps_preserve_8192_bit_keys_and_bound_exponents() {
        assert_eq!(
            validate_public_key_parameter_values(&rsa_public_params()),
            Ok(()),
        );
        assert_eq!(
            validate_rsa_parameter_bytes(&rsa_public_parameter_bytes(
                MAX_RSA_PUBLIC_EXPONENT_BYTES,
            )),
            Ok(()),
        );
        assert_eq!(
            validate_rsa_parameter_bytes(&rsa_public_parameter_bytes(
                MAX_RSA_PUBLIC_EXPONENT_BYTES + 1,
            )),
            Err(ParseFailure::ResourceLimit),
        );
    }

    #[test]
    fn designated_revoker_cap_deduplicates_before_accounting() {
        let mut budget = OpenPgpReadBudget::default();
        for index in 0..MAX_DESIGNATED_REVOKERS_PER_REQUEST {
            let id = DesignatedRevokerId {
                algorithm: 1,
                fingerprint: index.to_be_bytes().to_vec(),
            };
            assert_eq!(budget.charge_designated_revoker(id.clone()), Ok(()));
            assert_eq!(budget.charge_designated_revoker(id), Ok(()));
        }
        assert_eq!(
            budget.designated_revokers.len(),
            MAX_DESIGNATED_REVOKERS_PER_REQUEST,
        );
        assert_eq!(
            budget.charge_designated_revoker(DesignatedRevokerId {
                algorithm: 1,
                fingerprint: b"one-too-many".to_vec(),
            },),
            Err(OpenPgpReadError::ResourceLimit),
        );
    }

    #[test]
    fn detached_signature_count_limit_is_inclusive() {
        let signature = parse_detached_signatures_fresh(DETACHED_SIGNATURE)
            .expect("fixed detached signature must parse")
            .remove(0);
        let mut packet = Vec::new();
        DetachedSignature::new(signature)
            .to_writer(&mut packet)
            .expect("detached signature packet must serialize");
        assert_eq!(
            parse_detached_signatures_fresh(&packet.repeat(MAX_DETACHED_SIGNATURES))
                .expect("signature-count boundary must parse")
                .len(),
            MAX_DETACHED_SIGNATURES,
        );
        assert_eq!(
            parse_detached_signatures_fresh(&packet.repeat(MAX_DETACHED_SIGNATURES + 1)),
            Err(OpenPgpReadError::ResourceLimit),
        );
    }

    #[test]
    fn cleartext_header_line_count_and_line_length_limits_are_inclusive() {
        let signature_marker = b"-----BEGIN PGP SIGNATURE-----";
        let signature_offset = find_subslice(CLEAR_SIGNED, signature_marker)
            .expect("clear-sign fixture signature marker");
        let signature = &CLEAR_SIGNED[signature_offset..];

        let clear_signed = |header_bytes: usize, line_count: usize, line_bytes: usize| {
            let mut document = b"-----BEGIN PGP SIGNED MESSAGE-----".to_vec();
            document.resize(header_bytes, b'X');
            document.extend_from_slice(b"\n\n");
            for index in 0..line_count {
                document.extend(std::iter::repeat_n(b'a', line_bytes));
                if index + 1 < line_count {
                    document.push(b'\n');
                }
            }
            if line_count > 0 {
                document.push(b'\n');
            }
            document.extend_from_slice(signature);
            document
        };

        assert!(
            parse_clear_signed_message_fresh(&clear_signed(MAX_CLEAR_SIGNED_HEADER_BYTES, 1, 1,))
                .is_ok(),
        );
        assert_eq!(
            parse_clear_signed_message_fresh(&clear_signed(
                MAX_CLEAR_SIGNED_HEADER_BYTES + 1,
                1,
                1,
            ))
            .err(),
            Some(OpenPgpReadError::ResourceLimit),
        );
        assert!(
            parse_clear_signed_message_fresh(&clear_signed(64, MAX_CLEAR_SIGNED_LINES, 1)).is_ok(),
        );
        assert_eq!(
            parse_clear_signed_message_fresh(&clear_signed(64, MAX_CLEAR_SIGNED_LINES + 1, 1))
                .err(),
            Some(OpenPgpReadError::ResourceLimit),
        );
        assert!(
            parse_clear_signed_message_fresh(&clear_signed(64, 1, MAX_CLEAR_SIGNED_LINE_BYTES))
                .is_ok(),
        );
        assert_eq!(
            parse_clear_signed_message_fresh(
                &clear_signed(64, 1, MAX_CLEAR_SIGNED_LINE_BYTES + 1,)
            )
            .err(),
            Some(OpenPgpReadError::ResourceLimit),
        );
    }

    #[test]
    fn secret_and_public_inputs_resolve_exact_versioned_metadata() {
        for request in [
            OpenPgpMetadataResolveRequest {
                private_key_data: Some(SECRET_KEY.to_vec()),
                public_key_data: None,
                normalized_fingerprint: "93ab cf80 4d85 ee79 d6e1 db0e 7764 8d3e 5d4e 7699"
                    .to_owned(),
                candidate_revocation_keys: Vec::new(),
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
            OpenPgpMetadataResolveRequest {
                private_key_data: Some(b"malformed secret".to_vec()),
                public_key_data: Some(PUBLIC_KEY.to_vec()),
                normalized_fingerprint: PRIMARY_FINGERPRINT.to_ascii_lowercase(),
                candidate_revocation_keys: vec![b"ignored malformed candidate".to_vec()],
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            },
        ] {
            let result = OpenPgpMetadataResolveResult::decode(
                resolve_metadata(request)
                    .expect("metadata request must produce nullable metadata")
                    .as_slice(),
            )
            .expect("metadata result must decode");
            let metadata = result.metadata.expect("fixed key metadata");
            assert_eq!(metadata.version, METADATA_VERSION);
            assert_eq!(metadata.keys.len(), 2);
            assert_eq!(metadata.keys[0].fingerprint, PRIMARY_FINGERPRINT);
            assert_eq!(metadata.keys[0].keygrip, PRIMARY_KEYGRIP);
            assert_eq!(metadata.keys[0].algorithm, "EDDSA");
            assert_eq!(metadata.keys[0].capabilities, ["sign"]);
            assert_eq!(metadata.keys[1].fingerprint, SUBKEY_FINGERPRINT);
            assert_eq!(metadata.keys[1].keygrip, SUBKEY_KEYGRIP);
            assert_eq!(metadata.keys[1].algorithm, "ECDH");
            assert_eq!(metadata.keys[1].capabilities, ["decrypt"]);
        }
    }

    #[test]
    fn metadata_is_absent_when_no_selected_authenticated_ring_exists() {
        let result = OpenPgpMetadataResolveResult::decode(
            resolve_metadata(OpenPgpMetadataResolveRequest {
                private_key_data: Some(SECRET_KEY.to_vec()),
                public_key_data: Some(PUBLIC_KEY.to_vec()),
                normalized_fingerprint: "0000000000000000000000000000000000000000".to_owned(),
                candidate_revocation_keys: Vec::new(),
                reference_time_epoch_seconds: Some(REFERENCE_TIME),
            })
            .expect("metadata request must produce nullable metadata")
            .as_slice(),
        )
        .expect("metadata result must decode");
        assert_eq!(result.metadata, None);
    }

    #[test]
    fn verified_designated_revoker_omits_externally_revoked_subkey_from_metadata() {
        let resolve = |candidate_revocation_keys| {
            OpenPgpMetadataResolveResult::decode(
                resolve_metadata(OpenPgpMetadataResolveRequest {
                    private_key_data: None,
                    public_key_data: Some(DESIGNATED_REVOKED_PUBLIC_KEY.to_vec()),
                    normalized_fingerprint: String::new(),
                    candidate_revocation_keys,
                    reference_time_epoch_seconds: Some(1_783_960_000),
                })
                .expect("metadata request must produce nullable metadata")
                .as_slice(),
            )
            .expect("metadata result must decode")
            .metadata
            .expect("fixed designated-revocation certificate metadata")
        };

        let unresolved = resolve(Vec::new());
        assert_eq!(unresolved.keys.len(), 3);

        let resolved = resolve(vec![DESIGNATED_REVOKER_PUBLIC_KEY.to_vec()]);
        assert_eq!(resolved.keys.len(), 2);
        assert_eq!(resolved.keys[0], unresolved.keys[0]);
        let omitted = unresolved
            .keys
            .iter()
            .skip(1)
            .filter(|key| !resolved.keys.contains(key))
            .collect::<Vec<_>>();
        assert_eq!(omitted.len(), 1);
    }

    #[test]
    fn detached_stream_verifies_across_arbitrary_chunk_boundaries() {
        let _guard = verifier_worker_test_guard();
        let request = detached_stream_request();
        for chunk_size in [1, 2, 3, 7, 31, 64 * 1024] {
            let mut session =
                DetachedVerificationSession::open(request.clone()).expect("stream must open");
            session.update(&[]).expect("empty chunk must be accepted");
            for chunk in DETACHED_BODY.chunks(chunk_size) {
                session.update(chunk).expect("body chunk must be accepted");
            }
            let result = OpenPgpVerification::decode(
                session.finish().expect("stream must finish").as_slice(),
            )
            .expect("stream result must decode");
            assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
        }
    }

    #[test]
    fn dropping_unfinished_detached_stream_cancels_and_joins_worker() {
        let _guard = verifier_worker_test_guard();
        let mut session =
            DetachedVerificationSession::open(detached_stream_request()).expect("stream must open");
        session
            .update(&DETACHED_BODY[..3])
            .expect("partial body must be accepted");
        drop(session);
        assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
    }

    #[test]
    fn verifier_worker_limit_rejects_then_releases_on_finish_and_cancellation() {
        let _guard = verifier_worker_test_guard();
        assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
        let request = detached_stream_request();
        let mut sessions = (0..MAX_OPENPGP_VERIFIER_WORKERS)
            .map(|_| DetachedVerificationSession::open(request.clone()).expect("worker permit"))
            .collect::<Vec<_>>();
        assert_eq!(
            ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
            MAX_OPENPGP_VERIFIER_WORKERS,
        );
        assert!(matches!(
            DetachedVerificationSession::open(request.clone()),
            Err(OpenPgpReadError::ResourceLimit),
        ));

        drop(sessions.pop());
        assert_eq!(
            ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
            MAX_OPENPGP_VERIFIER_WORKERS - 1,
        );
        let mut replacement =
            DetachedVerificationSession::open(request).expect("released permit must be reusable");
        replacement
            .update(DETACHED_BODY)
            .expect("replacement body must be accepted");
        let result = OpenPgpVerification::decode(
            replacement
                .finish()
                .expect("replacement must finish")
                .as_slice(),
        )
        .expect("replacement result must decode");
        assert_eq!(result.status, OpenPgpVerificationStatus::Valid as i32);
        assert_eq!(
            ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire),
            MAX_OPENPGP_VERIFIER_WORKERS - 1,
        );

        drop(sessions);
        assert_eq!(ACTIVE_OPENPGP_VERIFIER_WORKERS.load(Ordering::Acquire), 0);
    }

    fn assert_verification(
        result: &OpenPgpVerification,
        status: OpenPgpVerificationStatus,
        created_at_epoch_seconds: u64,
    ) {
        assert_eq!(result.status, status as i32);
        assert_eq!(result.key_id, "F83D947D29EFECF7");
        assert_eq!(result.fingerprint.as_deref(), Some(PRIMARY_FINGERPRINT));
        assert_eq!(result.user_ids, [USER_ID]);
        assert_eq!(
            result.created_at_epoch_seconds,
            Some(created_at_epoch_seconds),
        );
        assert!(result.warnings.is_empty());
    }

    #[test]
    fn fingerprint_normalization_ignores_ascii_separators_and_case() {
        assert_eq!(
            normalize_fingerprint("d0bb cfbb-250d:3bb0"),
            "D0BBCFBB250D3BB0"
        );
        assert_eq!(normalize_fingerprint("d0bg"), "D0BG");
    }

    #[test]
    fn signature_expiration_matches_gnupg_uint32_wrap_semantics() {
        let created = Some(u64::from(u32::MAX) - 5);
        assert!(!signature_expired_at(created, 10, u64::from(u32::MAX) + 3));
        assert!(signature_expired_at(created, 10, u64::from(u32::MAX) + 5));

        // A wrapped expiration value of zero is GnuPG's no-expiration sentinel.
        assert!(!signature_expired_at(
            Some(u64::from(u32::MAX) - 4),
            5,
            u64::MAX,
        ));
        assert!(!signature_expired_at(created, 1_u64 << 32, u64::MAX));
    }

    #[test]
    fn channel_reader_preserves_arbitrary_chunk_boundaries() {
        let (sender, receiver) = mpsc::sync_channel(1);
        let worker = thread::spawn(move || {
            sender
                .send(Zeroizing::new(b"ab".to_vec()))
                .expect("first send must work");
            sender
                .send(Zeroizing::new(Vec::new()))
                .expect("empty send must work");
            sender
                .send(Zeroizing::new(b"cdef".to_vec()))
                .expect("last send must work");
        });
        let mut reader = ChannelReader {
            receiver,
            current: Zeroizing::new(Vec::new()),
            offset: 0,
        };
        let mut output = Vec::new();
        reader
            .read_to_end(&mut output)
            .expect("channel reader must drain");
        worker.join().expect("sender must join");
        assert_eq!(output, b"abcdef");
    }

    #[test]
    fn keygrip_hex_decoder_rejects_malformed_input() {
        assert_eq!(decode_hex("0A10"), Some(vec![0x0a, 0x10]));
        assert_eq!(decode_hex("0"), None);
        assert_eq!(decode_hex("GG"), None);
    }
}
