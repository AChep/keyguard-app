//! OpenPGP read-path parsing, protocol orchestration, and signature verification.
//!
//! Every certificate document entering the read path walks one pipeline, and
//! walks it once:
//!
//! ```text
//! bytes -> RawPacketStream            (bounded, lossless raw framing)
//!       -> PublicCertificatePacketSet (per-certificate packet evidence, merged
//!                                      by primary fingerprint)
//!       -> CanonicalCertificate       (`finalize()`: retained and transferable
//!                                      serializations plus the composed view)
//!       -> validate_certificate       (the single cryptographic verifier,
//!                                      under the single policy budget)
//!       -> metadata, verification, signer selection
//! ```
//!
//! No stage re-scans bytes a previous stage already scanned, and no stage
//! re-derives what [`CanonicalCertificate`] already carries.
//!
//! rPGP is intentionally used only for public packet parsing and public-key
//! verification in this module. Private signing, decryption, and generation
//! stay outside this dependency path.

use std::{
    collections::BTreeMap,
    io::{self, Cursor, Read},
    sync::{
        atomic::{AtomicUsize, Ordering},
        mpsc::{self, Receiver, SyncSender},
    },
    thread::{self, JoinHandle},
};

use pgp::{
    armor::{self, BlockType},
    composed::SignedPublicKey,
    crypto::ecc_curve::ECCCurve,
    packet::{
        Packet, PacketParser, Signature, SignatureConfig, SignatureType, SignatureVersion,
        SignatureVersionSpecific,
    },
    ser::Serialize,
    types::{Fingerprint, KeyDetails, KeyVersion, PublicParams},
};
use thiserror::Error;
use zeroize::Zeroizing;

use crate::openpgp::{
    certificate::{
        CanonicalCertificate, CertificateMergeError, ExportClassificationBudget,
        MutationMaterialError, PublicCertificatePacketSet, SecretCertificateOverlay,
        SignatureRehomingBudget, identity_id, merge_public_certificate_packet_sets,
        parse_public_certificate_packet_sets_with_budget, project_secret_certificate,
        raw_packet_is_exportable,
    },
    crypto::{
        algorithm_name, keygrip, leading_mpi_bits, secret::SecretChunks,
        verification::signature_verification_compatible,
    },
    format::{fingerprint_hex, hex_upper, normalize_fingerprint},
    packet::{
        MARKER_TAG, PADDING_TAG, PUBLIC_KEY_TAG, PUBLIC_SUBKEY_TAG, RawPacketError,
        RawPacketStream, SECRET_KEY_TAG, SECRET_SUBKEY_TAG, SIGNATURE_TAG,
        armor::{RawPackets, key_block_include_checksum},
        dearmor_bounded, serialize_params, take_mpi,
    },
    policy::{
        EvaluatedComponent, MutationAuthorizationError, OpenPgpPolicyBudget, OpenPgpPolicyError,
        PublicComponent, RenewalAuthorization, SignatureIssuerMetadata, ValidatedCertificate,
        all_components, can_encrypt, can_sign, certificate_components, certificate_index,
        component_expiration, data_signature_acceptable, is_legacy_weak_hash,
        key_signature_verification_acceptable, reference_time, revocation_key_id,
        signature_creation_time, signature_expired, validate_certificate,
        validate_certificate_with_policy_time,
    },
};

mod model;

pub(crate) use model::{
    CertificateResolution, ClearVerificationResult, ClearVerifyInput, ComponentPolicySummary,
    DetachedVerifyInput, MetadataResolution, MetadataResolveInput, PolicyUse, PublicKeyInfo,
    PublicKeyParseFailure, PublicKeyParseInput, PublicKeyParseOutcome, PublicKeyParseSuccess,
    PublicSubkeyInfo, RenewalCapability, UserIdInfo, Verification, VerificationStatus,
    VerificationWarning, VerifyInput, VerifyKind,
};

#[cfg(test)]
use crate::openpgp::policy::{
    PolicyContext, PolicySelection, authenticated_key_flags, select_newest_policy_signature,
    select_primary_user_id, signature_expired_at,
};
#[cfg(test)]
use pgp::composed::{Deserializable, DetachedSignature};
#[cfg(test)]
use pgp::types::Tag;
#[cfg(test)]
use std::io::{BufRead, BufReader};

pub(crate) const METADATA_POLICY_REVISION: u32 = 1;
const STREAM_CHANNEL_DEPTH: usize = 1;
const VERIFY_BUFFER_BYTES: usize = 8 * 1024;
const MAX_PUBLIC_KEY_DOCUMENTS: usize = 64;
const MAX_CERTIFICATES_PER_REQUEST: usize = 64;
const MAX_COMPONENTS_PER_CERTIFICATE: usize = 64;
const MAX_IDENTITIES_PER_CERTIFICATE: usize = 256;
const MAX_DETACHED_SIGNATURES: usize = 64;
const MAX_CLEAR_SIGNED_HEADER_BYTES: usize = 64 * 1024;
const MAX_CLEAR_SIGNED_LINES: usize = 16 * 1024;
const MAX_CLEAR_SIGNED_LINE_BYTES: usize = 64 * 1024;
// Canonical signed text is retained in memory until the trailing signature
// arrives; parity with the one-shot control-envelope bound.
const MAX_CLEAR_SIGNED_BODY_BYTES: usize = 16 * 1024 * 1024;
const CLEAR_SIGNED_CANONICAL_CHUNK_BYTES: usize = 64 * 1024;
const MAX_CLEAR_SIGNED_SIGNATURE_BYTES: usize = 1024 * 1024;
// Match Sequoia's default tolerance for signatures checked against the local
// clock. Explicit historical reference times remain exact: tolerance there
// can make a later statement appear to have existed in an earlier view.
const DATA_SIGNATURE_CLOCK_SKEW_TOLERANCE_SECONDS: u64 = 30 * 60;
const CLEAR_SIGNED_MESSAGE_MARKER: &[u8] = b"-----BEGIN PGP SIGNED MESSAGE-----";
const CLEAR_SIGNED_SIGNATURE_MARKER: &[u8] = b"-----BEGIN PGP SIGNATURE-----";
// RFC 9580 Table 23 text names.
const CLEAR_SIGNED_HASH_TEXT_NAMES: [&str; 9] = [
    "MD5",
    "SHA1",
    "RIPEMD160",
    "SHA256",
    "SHA384",
    "SHA512",
    "SHA224",
    "SHA3-256",
    "SHA3-512",
];

/// Matches an RFC 9580 §6.2.1 Armor Header Line after line-ending removal.
///
/// The marker must begin the line, and only spaces or horizontal tabs may
/// follow it on that line.
fn is_clear_signed_armor_marker(line: &[u8], marker: &[u8]) -> bool {
    line.strip_prefix(marker)
        .is_some_and(|suffix| suffix.iter().all(|byte| matches!(byte, b' ' | b'\t')))
}

// Aggregate request limits are deliberately much smaller than the product of
// the per-certificate limits. They bound allocations and policy work for
// attacker-controlled keyserver/import documents without affecting normal
// transferable certificates (which usually contain fewer than ten packets).
const MAX_PACKETS_PER_REQUEST: usize = 8 * 1024;
const MAX_PACKET_BODY_BYTES: usize = 4 * 1024 * 1024;
const MAX_COMPONENTS_PER_REQUEST: usize = 512;
const MAX_IDENTITIES_PER_REQUEST: usize = 1024;
const MAX_SIGNATURES_PER_REQUEST: usize = 4 * 1024;
const MAX_PUBLIC_KEY_PARAMETER_BYTES: usize = 16 * 1024;
const MAX_RSA_MODULUS_BITS: u32 = 8 * 1024;
const MAX_RSA_PUBLIC_EXPONENT_BYTES: usize = 8;
const MAX_DISCRETE_LOG_KEY_BITS: u32 = 8 * 1024;
const MAX_ECC_PUBLIC_PARAMETER_BYTES: usize = 1024;
// Four verifier workers keep native thread stacks bounded on Android/iOS while
// still permitting foreground verification plus modest application parallelism.
const MAX_OPENPGP_VERIFIER_WORKERS: usize = 4;
// Issuer key IDs are 64-bit hints, so a handful of colliding components in one
// request is conceivable; hundreds is an attack. Total verification work stays
// bounded by the single policy budget either way.
const MAX_SIGNER_CANDIDATES_PER_SIGNATURE: usize = 32;

static ACTIVE_OPENPGP_VERIFIER_WORKERS: AtomicUsize = AtomicUsize::new(0);

/// The trusted clock used to decide whether a data signature is live.
///
/// Current-time verification tolerates bounded skew between the signer's and
/// verifier's clocks. A caller-provided historical time is exact, matching
/// Sequoia's `signature_alive(Some(time), None)` semantics.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) struct DataSignatureVerificationTime {
    reference_time: u64,
    latest_acceptable_creation_time: u64,
}

impl DataSignatureVerificationTime {
    pub(in crate::openpgp) fn from_reference_time(explicit: Option<u64>) -> Self {
        let allows_clock_skew = explicit.is_none();
        let reference_time = reference_time(explicit);
        Self {
            reference_time,
            latest_acceptable_creation_time: if allows_clock_skew {
                reference_time.saturating_add(DATA_SIGNATURE_CLOCK_SKEW_TOLERANCE_SECONDS)
            } else {
                reference_time
            },
        }
    }

    #[cfg(test)]
    fn exact(reference_time: u64) -> Self {
        Self {
            reference_time,
            latest_acceptable_creation_time: reference_time,
        }
    }

    pub(in crate::openpgp) fn reference_time(self) -> u64 {
        self.reference_time
    }

    /// Returns the signed creation time that may select a historical
    /// certificate view. A tolerated future timestamp is capped at the trusted
    /// reference time so it cannot activate a binding early.
    pub(in crate::openpgp) fn trusted_signature_time(self, signature: &Signature) -> Option<u64> {
        signature_creation_time(signature)
            .map(u64::from)
            .filter(|creation_time| self.accepts_creation_time(*creation_time))
            .map(|creation_time| creation_time.min(self.reference_time))
    }

    fn accepts_creation_time(self, creation_time: u64) -> bool {
        creation_time <= self.latest_acceptable_creation_time
    }
}

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

impl From<OpenPgpPolicyError> for OpenPgpReadError {
    fn from(error: OpenPgpPolicyError) -> Self {
        match error {
            OpenPgpPolicyError::ResourceLimit | OpenPgpPolicyError::RequestResourceLimit => {
                Self::ResourceLimit
            }
            OpenPgpPolicyError::Internal => Self::Internal,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ParseFailure {
    Malformed,
    UnsupportedKeyVersion,
    ResourceLimit,
}

impl From<RawPacketError> for ParseFailure {
    fn from(error: RawPacketError) -> Self {
        match error {
            RawPacketError::Malformed => Self::Malformed,
            RawPacketError::ResourceLimit => Self::ResourceLimit,
        }
    }
}

struct SelectedSigner {
    component: PublicComponent,
    certificate_fingerprint: Vec<u8>,
    fingerprint: String,
    primary_fingerprint: String,
    user_ids: Vec<String>,
    primary_user_id: Option<String>,
    warnings: Vec<VerificationWarning>,
    /// Cryptography may still verify historical data, but any defensive
    /// unresolved-policy state must never produce a Valid status.
    policy_conflict: bool,
}

enum SignerResolution {
    Missing,
    Rejected {
        fingerprint: Option<String>,
    },
    /// Every routed component, in certificate order.
    ///
    /// RFC 9580 §5.2.3.12 makes issuer subpackets a *hint*, so a key-ID
    /// collision must not veto verification. Each candidate is tried
    /// cryptographically, under the single policy budget. When the signature
    /// is issuerless, routing is instead restricted to supplied components
    /// with a compatible signature version, algorithm, and digest shape.
    Selected {
        signers: Vec<SelectedSigner>,
        /// Issuer metadata identifies a failed candidate even when the
        /// mathematical signature check does not. An issuerless signature
        /// only identifies its signer after a successful check.
        report_rejected_signer: bool,
    },
}

#[derive(Clone, Copy)]
enum AuthenticatedRecipientContext<'a> {
    None,
    PerSignature(&'a [Option<Fingerprint>]),
}

impl<'a> AuthenticatedRecipientContext<'a> {
    fn for_signature(
        self,
        signature_index: usize,
    ) -> Result<Option<&'a Fingerprint>, OpenPgpReadError> {
        match self {
            Self::None => Ok(None),
            Self::PerSignature(fingerprints) => fingerprints
                .get(signature_index)
                .map(Option::as_ref)
                .ok_or(OpenPgpReadError::Internal),
        }
    }
}

#[derive(Default)]
enum CachedCertificateValidation<'a> {
    #[default]
    Unchecked,
    RejectedByResourceLimit,
    Accepted(Box<ValidatedCertificate<'a>>),
}

type CertificateValidationCache<'a> = BTreeMap<(u64, u64), CachedCertificateValidation<'a>>;

impl<'a> CachedCertificateValidation<'a> {
    fn get_or_validate(
        &mut self,
        validate: impl FnOnce() -> Result<ValidatedCertificate<'a>, OpenPgpPolicyError>,
    ) -> Result<Option<&ValidatedCertificate<'a>>, OpenPgpPolicyError> {
        if matches!(self, Self::Unchecked) {
            *self = match validate() {
                Ok(certificate) => Self::Accepted(Box::new(certificate)),
                Err(OpenPgpPolicyError::ResourceLimit) => Self::RejectedByResourceLimit,
                Err(OpenPgpPolicyError::RequestResourceLimit) => {
                    return Err(OpenPgpPolicyError::RequestResourceLimit);
                }
                Err(error) => return Err(error),
            };
        }
        match &*self {
            Self::Accepted(certificate) => Ok(Some(certificate.as_ref())),
            Self::RejectedByResourceLimit => Ok(None),
            Self::Unchecked => Err(OpenPgpPolicyError::Internal),
        }
    }
}

struct ParsedPublicCertificate {
    canonical: CanonicalCertificate,
    /// The selected public packet view in its original framing. A public-key
    /// input retains its complete certificate span; a secret-key input uses
    /// the ordinary transferable view of its public projection.
    packets: Vec<u8>,
    include_armor_checksum: bool,
}

#[derive(Clone, Copy)]
enum PublicPacketView {
    OriginalCertificate,
    TransferableSecretProjection,
}

#[derive(Debug, Default)]
pub(super) struct OpenPgpReadBudget {
    packets: usize,
    components: usize,
    identities: usize,
    signatures: usize,
    signature_rehoming: SignatureRehomingBudget,
    export_classification: ExportClassificationBudget,
    policy: OpenPgpPolicyBudget,
}

#[derive(Debug)]
struct VerifierWorkerPermit;

enum PreparedVerification {
    Terminal(Verification),
    Candidates {
        candidates: Vec<PreparedVerificationCandidate>,
        results: Vec<Verification>,
        budget: OpenPgpReadBudget,
    },
}

struct PreparedVerificationCandidate {
    signature_index: usize,
    signature: Box<Signature>,
    signers: Vec<SelectedSigner>,
    /// Warnings that describe the signature itself rather than its signer.
    signature_warnings: Vec<VerificationWarning>,
    policy_acceptable: bool,
    report_rejected_signer: bool,
}

impl PreparedVerificationCandidate {
    fn result(&self, signer: &SelectedSigner, status: VerificationStatus) -> Verification {
        let mut warnings = signer.warnings.clone();
        warnings.extend_from_slice(&self.signature_warnings);
        verification_result(
            &self.signature,
            status,
            Some(signer.fingerprint.clone()),
            signer.user_ids.clone(),
            warnings,
            Some(signer.primary_fingerprint.clone()),
            signer.primary_user_id.clone(),
        )
    }

    /// The reported outcome when no candidate signer verifies.
    fn rejected(&self) -> Option<Verification> {
        if !self.report_rejected_signer {
            return Some(verification_result(
                &self.signature,
                VerificationStatus::Invalid,
                None,
                Vec::new(),
                self.signature_warnings.clone(),
                None,
                None,
            ));
        }
        self.signers
            .iter()
            .find(|signer| signer.policy_conflict)
            .or_else(|| self.signers.first())
            .map(|signer| self.result(signer, VerificationStatus::Invalid))
    }
}

struct PreparedSignatureHasher {
    config: SignatureConfig,
    hasher: Box<dyn digest::DynDigest + Send>,
    text_mode: bool,
}

struct StreamingTextCanonicalizer {
    output: Zeroizing<Vec<u8>>,
    pending_cr: bool,
}

impl Default for StreamingTextCanonicalizer {
    fn default() -> Self {
        Self {
            // The verifier reads at most 8 KiB at a time. Every byte can
            // normalize to CRLF, plus one carried CR from the prior chunk.
            output: Zeroizing::new(Vec::with_capacity(2 * VERIFY_BUFFER_BYTES + 2)),
            pending_cr: false,
        }
    }
}

/// Bounded, incremental detached-signature verification session.
pub(crate) struct DetachedVerificationSession {
    sender: Option<SyncSender<Zeroizing<Vec<u8>>>>,
    worker: Option<JoinHandle<Result<Verification, OpenPgpReadError>>>,
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

    pub(super) fn policy_mut(&mut self) -> &mut OpenPgpPolicyBudget {
        &mut self.policy
    }

    fn signature_rehoming_mut(&mut self) -> &mut SignatureRehomingBudget {
        &mut self.signature_rehoming
    }

    fn export_classification_mut(&mut self) -> &mut ExportClassificationBudget {
        &mut self.export_classification
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

/// Parses transferable public certificates, or the public projection of one
/// transferable secret certificate, and returns a typed domain result.
pub(crate) fn parse_public_key(
    request: PublicKeyParseInput,
) -> Result<PublicKeyParseOutcome, OpenPgpReadError> {
    // This operation also accepts secret certificates. Move the input into a
    // zeroizing owner before any early return; the request's Drop policy covers
    // decoded requests rejected before they reach this function.
    let key_data = request.key_data;
    if key_data.iter().all(u8::is_ascii_whitespace) {
        return Ok(PublicKeyParseOutcome::Failure(PublicKeyParseFailure::Empty));
    }
    let reference_time = reference_time(request.reference_time_epoch_seconds);
    let (contains_secret_key_material, certificate_boundaries) =
        match RawPacketStream::parse_transferable_keyring(&key_data, MAX_PACKETS_PER_REQUEST) {
            Ok(stream) => (
                stream
                    .packets()
                    .iter()
                    .any(|packet| matches!(packet.tag(), SECRET_KEY_TAG | SECRET_SUBKEY_TAG)),
                stream
                    .packets()
                    .iter()
                    .filter(|packet| matches!(packet.tag(), SECRET_KEY_TAG | PUBLIC_KEY_TAG))
                    .count(),
            ),
            Err(RawPacketError::Malformed) => {
                return Ok(PublicKeyParseOutcome::Failure(
                    PublicKeyParseFailure::Malformed,
                ));
            }
            Err(RawPacketError::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
        };
    let projected_key_data = if contains_secret_key_material {
        // A multi-key secret export (e.g. `gpg --export-secret-keys` of a full
        // keyring) is well-formed OpenPGP that this single-key operation
        // cannot accept; name the real problem instead of calling the
        // document malformed.
        if certificate_boundaries > 1 {
            return Ok(PublicKeyParseOutcome::Failure(
                PublicKeyParseFailure::MultipleCertificates,
            ));
        }
        match project_secret_certificate(&key_data) {
            Ok((public_projection, _secret_overlay)) => Some(public_projection),
            Err(
                MutationMaterialError::MalformedKey
                | MutationMaterialError::FingerprintMismatch
                | MutationMaterialError::UnsupportedTskLayout,
            ) => {
                return Ok(PublicKeyParseOutcome::Failure(
                    PublicKeyParseFailure::Malformed,
                ));
            }
            Err(MutationMaterialError::UnsupportedKeyVersion) => {
                return Ok(PublicKeyParseOutcome::Failure(
                    PublicKeyParseFailure::UnsupportedKeyVersion,
                ));
            }
            Err(MutationMaterialError::ResourceLimit) => {
                return Err(OpenPgpReadError::ResourceLimit);
            }
            // The projection only reports material failures; the
            // operation-scoped variants of the shared mutation error cannot
            // reach a read-path caller.
            Err(error) => {
                debug_assert!(
                    matches!(
                        error,
                        MutationMaterialError::InternalFailure
                            | MutationMaterialError::SignatureVerificationFailed
                    ),
                    "secret-certificate projection reported a non-material failure",
                );
                return Err(OpenPgpReadError::Internal);
            }
        }
    } else {
        None
    };
    let public_key_data = projected_key_data.as_deref().unwrap_or(&key_data);
    let packet_view = if projected_key_data.is_some() {
        PublicPacketView::TransferableSecretProjection
    } else {
        PublicPacketView::OriginalCertificate
    };
    let mut budget = OpenPgpReadBudget::default();
    let (parsed, mut skipped_certificates, only_unsupported_skips) =
        match parse_public_certificates_preserving_packets(
            public_key_data,
            packet_view,
            &mut budget,
        ) {
            Ok(parsed) => parsed,
            Err(ParseFailure::UnsupportedKeyVersion) => {
                return Ok(PublicKeyParseOutcome::Failure(
                    PublicKeyParseFailure::UnsupportedKeyVersion,
                ));
            }
            Err(ParseFailure::Malformed) => {
                return Ok(PublicKeyParseOutcome::Failure(
                    PublicKeyParseFailure::Malformed,
                ));
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
        };
    let candidates = parsed
        .iter()
        .flat_map(|parsed| certificate_components(&parsed.canonical.semantic))
        .collect::<Vec<_>>();
    let mut keys = Vec::new();
    for parsed in &parsed {
        let policy = match validate_certificate(
            &parsed.canonical.semantic,
            &candidates,
            reference_time,
            budget.policy_mut(),
        ) {
            Ok(policy) => policy,
            // The verification budget is per certificate, so exceeding it
            // convicts only this certificate (a certification-flooded one);
            // it is skipped rather than failing every other certificate in
            // the document.
            Err(OpenPgpPolicyError::ResourceLimit) => {
                skipped_certificates = skipped_certificates.saturating_add(1);
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        if let Some(key) = public_key_info(&policy, &parsed.packets, parsed.include_armor_checksum)
        {
            keys.push(key);
        }
    }
    if keys.is_empty() {
        // A document made only of certificates Keyguard cannot represent keeps
        // its precise reason; anything else is reported as malformed.
        return Ok(PublicKeyParseOutcome::Failure(if only_unsupported_skips {
            PublicKeyParseFailure::UnsupportedKeyVersion
        } else {
            PublicKeyParseFailure::Malformed
        }));
    }
    Ok(PublicKeyParseOutcome::Success(PublicKeyParseSuccess {
        keys,
        skipped_certificates: u32::try_from(skipped_certificates).unwrap_or(u32::MAX),
    }))
}

/// Verifies a clear-signed document or a one-shot detached signature.
pub(crate) fn verify(request: VerifyInput) -> Result<Verification, OpenPgpReadError> {
    match request.kind {
        VerifyKind::ClearText => {
            let content = request.content;
            let mut session = ClearVerificationSession::open(ClearVerifyInput {
                public_keys: request.public_keys,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
            })?;
            // The recovered plaintext body is not needed here; skip emitting it.
            session.emit_body = false;
            session.update(&content)?;
            let result = session.finish()?;
            Ok(result.verification)
        }
        VerifyKind::Detached => {
            let prepared = prepare_detached_verification(DetachedVerifyInput {
                signature: request.signature,
                public_keys: request.public_keys,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
            })?;
            let content = request.content;
            verify_prepared(prepared, Cursor::new(content.as_slice()))
        }
    }
}

/// Resolves canonical gpg-agent metadata from secret or public transferable keys.
pub(crate) fn resolve_metadata(
    request: MetadataResolveInput,
) -> Result<Option<MetadataResolution>, OpenPgpReadError> {
    // Keep the only secret control field in its zeroizing owner on every return
    // path. Parsed private packet values are independently erased by the packet
    // projection pipeline.
    let private_key_data = request.private_key_data;
    let reference_time = reference_time(request.reference_time_epoch_seconds);
    if request.candidate_revocation_keys.len() > MAX_PUBLIC_KEY_DOCUMENTS {
        return Err(OpenPgpReadError::ResourceLimit);
    }

    let mut budget = OpenPgpReadBudget::default();
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

    // Each input document is scanned into packet sets exactly once here; the
    // union below and every projection after it read the struct, never bytes.
    let mut packet_sets = Vec::new();
    let mut secret_fingerprints = Vec::new();
    if let Some(data) = private_key_data
        .as_deref()
        .filter(|data| !data.iter().all(u8::is_ascii_whitespace))
    {
        match split_secret_keyring_packets(data) {
            Ok(secret_certificates) => {
                if secret_certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
                    return Err(OpenPgpReadError::ResourceLimit);
                }
                let secret_certificates = Zeroizing::new(secret_certificates);
                for secret_certificate in secret_certificates.iter() {
                    match project_secret_certificate(secret_certificate) {
                        Ok((public_projection, secret_overlay)) => {
                            let mut projected =
                                parse_document_packet_sets(&public_projection, &mut budget)
                                    .map_err(map_metadata_parse_failure)?;
                            if projected.len() != 1 {
                                return Err(OpenPgpReadError::InvalidArgument);
                            }
                            let projected = projected.remove(0);
                            record_secret_fingerprints(
                                &projected,
                                &secret_overlay,
                                &mut secret_fingerprints,
                            );
                            packet_sets.push(projected);
                        }
                        Err(MutationMaterialError::ResourceLimit) => {
                            return Err(OpenPgpReadError::ResourceLimit);
                        }
                        Err(
                            MutationMaterialError::MalformedKey
                            | MutationMaterialError::FingerprintMismatch
                            | MutationMaterialError::UnsupportedKeyVersion
                            | MutationMaterialError::UnsupportedTskLayout,
                        ) => {}
                        // See `parse_public_key`: only material
                        // failures can reach a read-path caller.
                        Err(error) => {
                            debug_assert!(
                                matches!(
                                    error,
                                    MutationMaterialError::InternalFailure
                                        | MutationMaterialError::SignatureVerificationFailed
                                ),
                                "secret-certificate projection reported a non-material failure",
                            );
                            return Err(OpenPgpReadError::Internal);
                        }
                    }
                }
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
            Err(ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion) => {}
        }
    }

    if let Some(data) = request
        .public_key_data
        .as_deref()
        .filter(|data| !data.iter().all(u8::is_ascii_whitespace))
    {
        match parse_document_packet_sets(data, &mut budget) {
            Ok(parsed) => {
                if packet_sets.len() + parsed.len() > MAX_CERTIFICATES_PER_REQUEST {
                    return Err(OpenPgpReadError::ResourceLimit);
                }
                packet_sets.extend(parsed);
            }
            Err(ParseFailure::ResourceLimit) => return Err(OpenPgpReadError::ResourceLimit),
            Err(ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion) => {}
        }
    }

    if packet_sets.is_empty() {
        return Ok(None);
    }

    let certificates = finalize_packet_sets(
        merge_public_certificate_packet_sets(packet_sets, MAX_CERTIFICATES_PER_REQUEST)
            .map_err(map_certificate_merge_error)?,
        &mut budget,
    )?;
    let normalized = normalize_fingerprint(&request.normalized_fingerprint);
    let resolved = resolve_metadata_v2_certificates(
        &certificates,
        &external_candidates,
        &secret_fingerprints,
        &normalized,
        reference_time,
        &mut budget,
    )?;
    Ok((!resolved.is_empty()).then_some(MetadataResolution {
        evaluated_at_epoch_seconds: reference_time,
        policy_revision: METADATA_POLICY_REVISION,
        certificates: resolved,
    }))
}

fn resolve_metadata_v2_certificates(
    certificates: &[CanonicalCertificate],
    external_candidates: &[SignedPublicKey],
    secret_fingerprints: &[String],
    normalized_fingerprint: &str,
    reference_time: u64,
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<CertificateResolution>, OpenPgpReadError> {
    let mut candidates = certificates
        .iter()
        .flat_map(|certificate| certificate_components(&certificate.semantic))
        .collect::<Vec<_>>();
    candidates.extend(all_components(external_candidates));
    let mut resolved = Vec::new();
    for certificate in certificates {
        if !normalized_fingerprint.is_empty()
            && !certificate
                .components
                .iter()
                .any(|component| component.fingerprint_hex() == normalized_fingerprint)
        {
            continue;
        }
        let policy = match validate_certificate(
            &certificate.semantic,
            &candidates,
            reference_time,
            budget.policy_mut(),
        ) {
            Ok(policy) => policy,
            // Per-certificate budget: a certification-flooded certificate is
            // skipped instead of failing the whole resolution.
            Err(OpenPgpPolicyError::ResourceLimit) => continue,
            Err(error) => return Err(error.into()),
        };
        resolved.push(metadata_v2_certificate(
            &policy,
            certificate,
            secret_fingerprints,
        ));
    }
    Ok(resolved)
}

fn map_metadata_parse_failure(error: ParseFailure) -> OpenPgpReadError {
    match error {
        ParseFailure::ResourceLimit => OpenPgpReadError::ResourceLimit,
        ParseFailure::Malformed | ParseFailure::UnsupportedKeyVersion => {
            OpenPgpReadError::InvalidArgument
        }
    }
}

fn record_secret_fingerprints(
    certificate: &PublicCertificatePacketSet,
    overlay: &SecretCertificateOverlay,
    result: &mut Vec<String>,
) {
    if overlay.has_secret_primary() {
        push_unique(result, certificate.fingerprint_hex());
    }
    for fingerprint in overlay.secret_subkey_fingerprints() {
        push_unique(result, fingerprint.to_owned());
    }
}

/// Projects one already-validated certificate into the v2 metadata index.
///
/// Mutation calls this directly on its own post-mutation
/// `(CanonicalCertificate, ValidatedCertificate)` pair, so a mutation never
/// re-armors and re-reads its own output to learn what it just produced.
pub(crate) fn metadata_v2_certificate(
    policy: &ValidatedCertificate<'_>,
    certificate: &CanonicalCertificate,
    secret_fingerprints: &[String],
) -> CertificateResolution {
    let primary_available = policy.primary_available();
    let mut component_policy = Vec::with_capacity(certificate.components.len());
    for component in &certificate.components {
        let policy_component = match component {
            PublicComponent::Primary(_) => Some(metadata_v2_component_policy(
                policy.primary_component(),
                primary_available,
                policy.authorize_primary_renewal(),
            )),
            PublicComponent::Subkey(subkey) => policy.subkey(subkey).map(|validated| {
                let renewal = validated.authorize_renewal();
                metadata_v2_component_policy(validated, primary_available, renewal)
            }),
        };
        component_policy.push(policy_component.unwrap_or_else(|| ComponentPolicySummary {
            fingerprint: component.fingerprint_hex(),
            allowed_new_data_uses: Vec::new(),
            renewal: RenewalCapability::None,
        }));
    }

    CertificateResolution {
        index: certificate_index(policy, certificate, secret_fingerprints),
        policy: component_policy,
    }
}

/// Projects one component's transient policy verdict.
///
/// `renewal` is resolved by the caller because the primary key and a subkey
/// take different authorizations; both are decided by the same table, so the
/// capability cannot drift between the two roles.
fn metadata_v2_component_policy<K: KeyDetails>(
    component: EvaluatedComponent<'_, '_, K>,
    primary_available: bool,
    renewal: Result<RenewalAuthorization, MutationAuthorizationError>,
) -> ComponentPolicySummary {
    let mut allowed_new_data_uses = Vec::with_capacity(2);
    if primary_available && component.signing_usable() {
        allowed_new_data_uses.push(PolicyUse::SignNewData);
    }
    if primary_available && component.encryption_usable() {
        allowed_new_data_uses.push(PolicyUse::EncryptNewData);
    }
    let component = component.policy();
    ComponentPolicySummary {
        fingerprint: fingerprint_hex(component.key),
        allowed_new_data_uses,
        renewal: renewal_capability(renewal),
    }
}

/// Collapses the renewal decision to the reusable workflow capability.
///
/// Every refusal collapses to `None`: revocation and indeterminate revocation
/// are reported through their own fields, and repeating the reason here would
/// give a caller a second, weaker place to read revocation state from.
fn renewal_capability(
    renewal: Result<RenewalAuthorization, MutationAuthorizationError>,
) -> RenewalCapability {
    match renewal {
        Ok(RenewalAuthorization::Authenticated) => RenewalCapability::Authenticated,
        Ok(RenewalAuthorization::TemplateOnly) => RenewalCapability::TemplateOnly,
        Err(_) => RenewalCapability::None,
    }
}

impl DetachedVerificationSession {
    /// Parses control data and starts a bounded verifier worker.
    pub(crate) fn open(request: DetachedVerifyInput) -> Result<Self, OpenPgpReadError> {
        let permit = VerifierWorkerPermit::acquire()?;
        let prepared = prepare_detached_verification(request)?;
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

    /// Closes the body stream and returns its authenticated verification.
    pub(crate) fn finish(mut self) -> Result<Verification, OpenPgpReadError> {
        self.sender.take();
        self.worker
            .take()
            .ok_or(OpenPgpReadError::Internal)?
            .join()
            .map_err(|_| OpenPgpReadError::Internal)?
    }
}

fn prepare_detached_verification(
    request: DetachedVerifyInput,
) -> Result<PreparedVerification, OpenPgpReadError> {
    let mut budget = OpenPgpReadBudget::default();
    let certificates = parse_public_key_documents(&request.public_keys, &mut budget)?;
    let signatures = parse_detached_signatures(&request.signature, &mut budget)?;
    prepare_verification(
        &signatures,
        &certificates,
        DataSignatureVerificationTime::from_reference_time(request.reference_time_epoch_seconds),
        AuthenticatedRecipientContext::None,
        budget,
    )
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

#[derive(Clone, Copy, PartialEq, Eq)]
enum ClearVerifyState {
    /// Bounded scan for the signed-message marker line.
    Preamble,
    /// Optional legacy armor headers up to the blank line.
    ArmorHeaders,
    /// Dash-escaped body lines up to the signature marker line.
    Body,
    /// The trailing armored signature block, retained verbatim.
    SignatureArmor,
}

#[derive(Clone, Copy)]
enum ClearTextLineEnding {
    None,
    Lf,
    Cr,
    CrLf,
}

struct ChunkedZeroizingBuffer {
    chunks: Vec<Zeroizing<Vec<u8>>>,
    tail: Zeroizing<Vec<u8>>,
    length: usize,
}

impl Default for ChunkedZeroizingBuffer {
    fn default() -> Self {
        Self {
            chunks: Vec::new(),
            tail: Zeroizing::new(Vec::new()),
            length: 0,
        }
    }
}

impl ChunkedZeroizingBuffer {
    fn extend_from_slice(&mut self, data: &[u8], limit: usize) -> Result<(), OpenPgpReadError> {
        let length = bounded_total(self.length, data.len(), limit)?;
        let mut remaining = data;
        while !remaining.is_empty() {
            if self.tail.len() == CLEAR_SIGNED_CANONICAL_CHUNK_BYTES {
                self.chunks
                    .try_reserve(1)
                    .map_err(|_| OpenPgpReadError::ResourceLimit)?;
                let full = std::mem::replace(&mut self.tail, Zeroizing::new(Vec::new()));
                self.chunks.push(full);
            }
            if self.tail.capacity() == 0 {
                self.tail
                    .try_reserve_exact(CLEAR_SIGNED_CANONICAL_CHUNK_BYTES)
                    .map_err(|_| OpenPgpReadError::ResourceLimit)?;
            }
            let count = remaining
                .len()
                .min(CLEAR_SIGNED_CANONICAL_CHUNK_BYTES - self.tail.len());
            self.tail.extend_from_slice(&remaining[..count]);
            remaining = &remaining[count..];
        }
        self.length = length;
        Ok(())
    }

    fn into_zeroizing(self) -> Result<Zeroizing<Vec<u8>>, OpenPgpReadError> {
        // Every chunk stays `Zeroizing` on the way into `SecretChunks`, so the
        // retained bytes (tail included) are wiped on success and failure
        // alike.
        let mut chunks = SecretChunks::default();
        for chunk in self.chunks.into_iter().chain(std::iter::once(self.tail)) {
            chunks
                .push(chunk, self.length)
                .map_err(|_| OpenPgpReadError::Internal)?;
        }
        let output = chunks
            .into_zeroizing()
            .map_err(|_| OpenPgpReadError::ResourceLimit)?;
        if output.len() != self.length {
            return Err(OpenPgpReadError::Internal);
        }
        Ok(output)
    }
}

impl ClearTextLineEnding {
    fn as_bytes(self) -> &'static [u8] {
        match self {
            Self::None => b"",
            Self::Lf => b"\n",
            Self::Cr => b"\r",
            Self::CrLf => b"\r\n",
        }
    }
}

/// Bounded, incremental cleartext-signature verification session.
///
/// A forward-only, line-oriented parser over the RFC 9580 cleartext
/// framework. `update` returns the dash-unescaped body bytes with their
/// serialized line endings as they are recovered, excluding the final
/// separator before the signature armor. Per-line trailing spaces and tabs
/// are omitted because the cleartext signature does not authenticate them.
/// The CRLF-canonical signed bytes and the trailing armored signature are
/// retained (bounded) until `finish`, which runs the verification and returns
/// a [`ClearVerificationResult`].
pub(crate) struct ClearVerificationSession {
    certificates: Vec<SignedPublicKey>,
    budget: OpenPgpReadBudget,
    verification_time: DataSignatureVerificationTime,
    state: ClearVerifyState,
    line: Zeroizing<Vec<u8>>,
    previous_input_was_cr: bool,
    framing_bytes: usize,
    body_lines: usize,
    body_valid_utf8: bool,
    first_body_line: bool,
    pending_line_ending: ClearTextLineEnding,
    canonical: ChunkedZeroizingBuffer,
    signature: Vec<u8>,
    /// When false, `update` returns no recovered body bytes; used by the
    /// one-shot verify path, which only needs the verification result.
    emit_body: bool,
}

impl ClearVerificationSession {
    /// Parses the trusted public keys and prepares an empty parser.
    pub(crate) fn open(request: ClearVerifyInput) -> Result<Self, OpenPgpReadError> {
        let mut budget = OpenPgpReadBudget::default();
        let certificates = parse_public_key_documents(&request.public_keys, &mut budget)?;
        Ok(Self::with_certificates(
            certificates,
            budget,
            DataSignatureVerificationTime::from_reference_time(
                request.reference_time_epoch_seconds,
            ),
        ))
    }

    fn with_certificates(
        certificates: Vec<SignedPublicKey>,
        budget: OpenPgpReadBudget,
        verification_time: DataSignatureVerificationTime,
    ) -> Self {
        Self {
            certificates,
            budget,
            verification_time,
            state: ClearVerifyState::Preamble,
            line: Zeroizing::new(Vec::with_capacity(MAX_CLEAR_SIGNED_LINE_BYTES)),
            previous_input_was_cr: false,
            framing_bytes: 0,
            body_lines: 0,
            body_valid_utf8: true,
            first_body_line: true,
            pending_line_ending: ClearTextLineEnding::None,
            canonical: ChunkedZeroizingBuffer::default(),
            signature: Vec::new(),
            emit_body: true,
        }
    }

    /// Supplies one raw document chunk and returns recovered body bytes.
    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpReadError> {
        let output_capacity = data
            .len()
            .checked_add(self.line.len())
            .and_then(|length| length.checked_add(2))
            .ok_or(OpenPgpReadError::ResourceLimit)?;
        let mut output = Zeroizing::new(Vec::new());
        output
            .try_reserve_exact(output_capacity)
            .map_err(|_| OpenPgpReadError::ResourceLimit)?;
        let mut index = 0;
        while index < data.len() {
            if self.state == ClearVerifyState::SignatureArmor {
                self.push_signature(&data[index..])?;
                return Ok(output.to_vec());
            }
            let byte = data[index];
            if self.previous_input_was_cr {
                self.previous_input_was_cr = false;
                let ending = if byte == b'\n' {
                    index += 1;
                    ClearTextLineEnding::CrLf
                } else {
                    ClearTextLineEnding::Cr
                };
                self.complete_line(ending, &mut output)?;
                continue;
            }
            index += 1;
            match byte {
                b'\r' => self.previous_input_was_cr = true,
                b'\n' => self.complete_line(ClearTextLineEnding::Lf, &mut output)?,
                _ => {
                    if self.line.len() >= MAX_CLEAR_SIGNED_LINE_BYTES {
                        return Err(OpenPgpReadError::ResourceLimit);
                    }
                    self.line.push(byte);
                }
            }
        }
        Ok(output.to_vec())
    }

    /// Verifies the retained canonical text against the trailing signature
    /// and returns its authenticated verification result.
    pub(crate) fn finish(mut self) -> Result<ClearVerificationResult, OpenPgpReadError> {
        // Flush an unterminated final line; recovered body bytes are
        // irrelevant here because every non-signature end state fails below.
        self.emit_body = false;
        if self.previous_input_was_cr {
            self.previous_input_was_cr = false;
            self.complete_line(ClearTextLineEnding::Cr, &mut Vec::new())?;
        } else if !self.line.is_empty() {
            self.complete_line(ClearTextLineEnding::None, &mut Vec::new())?;
        }
        if self.state != ClearVerifyState::SignatureArmor {
            return Err(OpenPgpReadError::InvalidArgument);
        }
        let signatures = parse_detached_signatures(&self.signature, &mut self.budget)?;
        let prepared = prepare_verification(
            &signatures,
            &self.certificates,
            self.verification_time,
            AuthenticatedRecipientContext::None,
            std::mem::take(&mut self.budget),
        )?;
        let canonical = std::mem::take(&mut self.canonical).into_zeroizing()?;
        let verification = verify_prepared(prepared, Cursor::new(canonical.as_slice()))?;
        Ok(ClearVerificationResult {
            verification,
            body_valid_utf8: self.body_valid_utf8,
        })
    }

    fn complete_line(
        &mut self,
        ending: ClearTextLineEnding,
        output: &mut Vec<u8>,
    ) -> Result<(), OpenPgpReadError> {
        match self.state {
            ClearVerifyState::Preamble => {
                self.charge_framing(ending)?;
                if is_clear_signed_armor_marker(&self.line, CLEAR_SIGNED_MESSAGE_MARKER) {
                    self.state = ClearVerifyState::ArmorHeaders;
                }
            }

            ClearVerifyState::ArmorHeaders => {
                self.charge_framing(ending)?;
                if self.line.iter().all(|byte| matches!(byte, b' ' | b'\t')) {
                    self.state = ClearVerifyState::Body;
                } else {
                    self.accept_armor_header()?;
                }
            }

            ClearVerifyState::Body => {
                if is_clear_signed_armor_marker(&self.line, CLEAR_SIGNED_SIGNATURE_MARKER) {
                    self.line.clear();
                    // The pinned armor parser requires the line ending to
                    // follow the marker immediately, so normalize only the
                    // already-validated marker before handing the armor off.
                    self.push_signature(CLEAR_SIGNED_SIGNATURE_MARKER)?;
                    self.push_signature(ending.as_bytes())?;
                    self.state = ClearVerifyState::SignatureArmor;
                    return Ok(());
                }
                self.accept_body_line(ending, output)?;
            }

            ClearVerifyState::SignatureArmor => return Err(OpenPgpReadError::Internal),
        }
        self.line.clear();
        Ok(())
    }

    /// Consumes one syntactically safe cleartext armor header.
    ///
    /// `Hash` is security-relevant parser input and remains restricted to the
    /// standard digest text names. Other headers are ignored for compatibility
    /// with historical producers, but only after their framing is unambiguous
    /// and seven-bit clean.
    fn accept_armor_header(&mut self) -> Result<(), OpenPgpReadError> {
        let header =
            std::str::from_utf8(&self.line).map_err(|_| OpenPgpReadError::InvalidArgument)?;
        if let Some(algorithms) = header.strip_prefix("Hash: ") {
            // An empty value fails inside the loop: splitting it yields one
            // empty item.
            for algorithm in algorithms.split(',') {
                let algorithm = algorithm.trim_matches([' ', '\t']);
                if algorithm.is_empty() || !CLEAR_SIGNED_HASH_TEXT_NAMES.contains(&algorithm) {
                    return Err(OpenPgpReadError::InvalidArgument);
                }
            }
            return Ok(());
        }

        let (key, value) = header
            .split_once(": ")
            .ok_or(OpenPgpReadError::InvalidArgument)?;
        let safe_key = !key.is_empty()
            && !key.eq_ignore_ascii_case("Hash")
            && key
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-');
        let safe_value = !value.is_empty() && value.bytes().all(|byte| matches!(byte, b' '..=b'~'));
        if !safe_key || !safe_value {
            return Err(OpenPgpReadError::InvalidArgument);
        }
        Ok(())
    }

    fn accept_body_line(
        &mut self,
        ending: ClearTextLineEnding,
        output: &mut Vec<u8>,
    ) -> Result<(), OpenPgpReadError> {
        self.body_lines += 1;
        if self.body_lines > MAX_CLEAR_SIGNED_LINES {
            return Err(OpenPgpReadError::ResourceLimit);
        }
        let content_start = if self.line.starts_with(b"- ") { 2 } else { 0 };
        if self.first_body_line {
            self.first_body_line = false;
        } else {
            if self.emit_body {
                extend_fixed_output(output, self.pending_line_ending.as_bytes())?;
            }
            self.canonical
                .extend_from_slice(b"\r\n", MAX_CLEAR_SIGNED_BODY_BYTES)?;
        }
        let canonical_length = self.line[content_start..]
            .iter()
            .rposition(|byte| !matches!(byte, b' ' | b'\t'))
            .map_or(0, |position| position + 1);
        let content = &self.line[content_start..content_start + canonical_length];
        if self.body_valid_utf8 && std::str::from_utf8(content).is_err() {
            self.body_valid_utf8 = false;
        }
        if self.emit_body {
            extend_fixed_output(output, content)?;
        }
        self.canonical
            .extend_from_slice(content, MAX_CLEAR_SIGNED_BODY_BYTES)?;
        self.pending_line_ending = ending;
        Ok(())
    }

    fn charge_framing(&mut self, ending: ClearTextLineEnding) -> Result<(), OpenPgpReadError> {
        self.framing_bytes = bounded_total(
            self.framing_bytes,
            self.line.len() + ending.as_bytes().len(),
            MAX_CLEAR_SIGNED_HEADER_BYTES,
        )?;
        Ok(())
    }

    fn push_signature(&mut self, data: &[u8]) -> Result<(), OpenPgpReadError> {
        let total = bounded_total(
            self.signature.len(),
            data.len(),
            MAX_CLEAR_SIGNED_SIGNATURE_BYTES,
        )?;
        self.signature
            .try_reserve(total - self.signature.len())
            .map_err(|_| OpenPgpReadError::ResourceLimit)?;
        self.signature.extend_from_slice(data);
        Ok(())
    }
}

fn extend_fixed_output(output: &mut Vec<u8>, data: &[u8]) -> Result<(), OpenPgpReadError> {
    if data.len() > output.capacity().saturating_sub(output.len()) {
        return Err(OpenPgpReadError::Internal);
    }
    output.extend_from_slice(data);
    Ok(())
}

/// Accumulation guard: the new total after `additional` bytes, or
/// [`OpenPgpReadError::ResourceLimit`] once it would exceed `limit`.
fn bounded_total(
    current: usize,
    additional: usize,
    limit: usize,
) -> Result<usize, OpenPgpReadError> {
    current
        .checked_add(additional)
        .filter(|total| *total <= limit)
        .ok_or(OpenPgpReadError::ResourceLimit)
}

enum OpenPgpPacketInput<'a> {
    Binary(&'a [u8]),
    Armored(Zeroizing<Vec<u8>>),
}

impl OpenPgpPacketInput<'_> {
    fn as_slice(&self) -> &[u8] {
        match self {
            Self::Binary(bytes) => bytes,
            Self::Armored(bytes) => bytes,
        }
    }
}

fn openpgp_packet_input<'a>(
    data: &'a [u8],
    expected_armor_type: Option<&BlockType>,
) -> Result<OpenPgpPacketInput<'a>, ParseFailure> {
    let first = data.first().copied().ok_or(ParseFailure::Malformed)?;
    if first & 0x80 != 0 {
        Ok(OpenPgpPacketInput::Binary(data))
    } else {
        dearmor_bounded(data, expected_armor_type)
            .map(OpenPgpPacketInput::Armored)
            .map_err(ParseFailure::from)
    }
}

#[cfg(test)]
fn preflight_openpgp_packets(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    let input = openpgp_packet_input(data, None)?;
    preflight_packet_reader(BufReader::new(Cursor::new(input.as_slice())), budget)
}

#[cfg(test)]
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

/// Parses one document into per-certificate packet sets, pairing each with a
/// selected original-framing public view.
///
/// The document is scanned once. Certificates are deliberately *not* unioned:
/// public input reports each complete certificate span in input order, while
/// a secret-key projection retains the ordinary transferable filter. Both are
/// re-armored from the packet bytes this function was handed.
fn parse_public_certificates_preserving_packets(
    data: &[u8],
    packet_view: PublicPacketView,
    budget: &mut OpenPgpReadBudget,
) -> Result<(Vec<ParsedPublicCertificate>, usize, bool), ParseFailure> {
    let stream = RawPacketStream::parse_transferable_keyring(data, MAX_PACKETS_PER_REQUEST)
        .map_err(ParseFailure::from)?;
    charge_stream(&stream, budget)?;
    let document =
        parse_public_certificate_packet_sets_with_budget(&stream, budget.signature_rehoming_mut())
            .map_err(map_certificate_merge_parse_error)?;
    if document.certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
        return Err(ParseFailure::ResourceLimit);
    }
    let candidates = document
        .certificates
        .iter()
        .map(PublicCertificatePacketSet::public_components)
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_certificate_merge_parse_error)?
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();
    let mut parsed = Vec::with_capacity(document.certificates.len());
    for (packet_set, span) in document.certificates.iter().zip(&document.spans) {
        let canonical = packet_set
            .finalize_with_export_budget(&candidates, budget.export_classification_mut())
            .map_err(map_certificate_merge_parse_error)?;
        validate_canonical_certificate(&canonical, budget)?;
        let mut packets = Vec::new();
        let mut key_versions = Vec::new();
        let mut signature_versions = Vec::new();
        for packet in stream
            .packets()
            .get(span.clone())
            .ok_or(ParseFailure::Malformed)?
            .iter()
            .filter(|packet| match packet_view {
                PublicPacketView::OriginalCertificate => true,
                PublicPacketView::TransferableSecretProjection => {
                    raw_packet_is_exportable(&canonical, &stream, packet)
                }
            })
        {
            let version = match packet.tag() {
                PUBLIC_KEY_TAG | PUBLIC_SUBKEY_TAG => stream
                    .first_body_byte(packet)
                    .map(KeyVersion::from)
                    .ok_or(ParseFailure::Malformed)?,
                SIGNATURE_TAG => {
                    let version = stream
                        .first_body_byte(packet)
                        .map(SignatureVersion::from)
                        .ok_or(ParseFailure::Malformed)?;
                    signature_versions.push(version);
                    packets.extend_from_slice(stream.raw(packet));
                    continue;
                }
                _ => {
                    packets.extend_from_slice(stream.raw(packet));
                    continue;
                }
            };
            key_versions.push(version);
            packets.extend_from_slice(stream.raw(packet));
        }
        if key_versions.is_empty() {
            // A wholly non-exportable certificate still has useful structural
            // and policy metadata. Retain its primary version solely to
            // derive the armor convention; the outward packet payload remains
            // empty and is rendered as an empty string below.
            key_versions.push(canonical.semantic.primary_key.version());
        }
        let include_armor_checksum =
            key_block_include_checksum(key_versions, signature_versions, true)
                .map_err(|_| ParseFailure::UnsupportedKeyVersion)?;
        parsed.push(ParsedPublicCertificate {
            canonical,
            packets,
            include_armor_checksum,
        });
    }
    if parsed.is_empty() && document.skipped_unsupported == 0 && document.skipped_malformed == 0 {
        return Err(ParseFailure::Malformed);
    }
    let skipped_certificates = document
        .skipped_unsupported
        .saturating_add(document.skipped_malformed);
    let only_unsupported_skips =
        parsed.is_empty() && document.skipped_unsupported > 0 && document.skipped_malformed == 0;
    Ok((parsed, skipped_certificates, only_unsupported_skips))
}

/// Parses one document into packet sets without serializing anything.
fn parse_document_packet_sets(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<PublicCertificatePacketSet>, ParseFailure> {
    let stream = RawPacketStream::parse_transferable_keyring(data, MAX_PACKETS_PER_REQUEST)
        .map_err(ParseFailure::from)?;
    charge_stream(&stream, budget)?;
    let document =
        parse_public_certificate_packet_sets_with_budget(&stream, budget.signature_rehoming_mut())
            .map_err(map_certificate_merge_parse_error)?;
    if document.certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
        return Err(ParseFailure::ResourceLimit);
    }
    if document.certificates.is_empty() {
        return Err(
            if document.skipped_unsupported > 0 && document.skipped_malformed == 0 {
                ParseFailure::UnsupportedKeyVersion
            } else {
                ParseFailure::Malformed
            },
        );
    }
    Ok(document.certificates)
}

fn charge_stream(
    stream: &RawPacketStream,
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    budget.charge_packets(stream.packets().len())?;
    if stream
        .packets()
        .iter()
        .any(|packet| packet.body_len() > MAX_PACKET_BODY_BYTES)
    {
        return Err(ParseFailure::ResourceLimit);
    }
    Ok(())
}

fn map_certificate_merge_parse_error(error: CertificateMergeError) -> ParseFailure {
    match error {
        CertificateMergeError::ResourceLimit => ParseFailure::ResourceLimit,
        CertificateMergeError::UnsupportedKeyVersion => ParseFailure::UnsupportedKeyVersion,
        CertificateMergeError::Malformed
        | CertificateMergeError::ComponentCollision
        | CertificateMergeError::Internal => ParseFailure::Malformed,
    }
}

fn map_certificate_merge_error(error: CertificateMergeError) -> OpenPgpReadError {
    match error {
        CertificateMergeError::ResourceLimit => OpenPgpReadError::ResourceLimit,
        CertificateMergeError::Malformed
        | CertificateMergeError::UnsupportedKeyVersion
        | CertificateMergeError::ComponentCollision => OpenPgpReadError::InvalidArgument,
        CertificateMergeError::Internal => OpenPgpReadError::Internal,
    }
}

#[cfg(test)]
fn decode_openpgp_packets(data: &[u8]) -> Result<Vec<u8>, ParseFailure> {
    RawPacketStream::parse(data, MAX_PACKETS_PER_REQUEST)
        .map(|stream| stream.bytes().to_vec())
        .map_err(ParseFailure::from)
}

fn split_secret_keyring_packets(data: &[u8]) -> Result<Vec<Vec<u8>>, ParseFailure> {
    split_certificate_packets(data, |tag| matches!(tag, SECRET_KEY_TAG | PUBLIC_KEY_TAG))
}

fn split_certificate_packets(
    data: &[u8],
    is_primary_boundary: impl Fn(u8) -> bool,
) -> Result<Vec<Vec<u8>>, ParseFailure> {
    let stream =
        RawPacketStream::parse(data, MAX_PACKETS_PER_REQUEST).map_err(ParseFailure::from)?;
    let mut primary_starts = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        if is_primary_boundary(packet.tag()) {
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
            let packets = &stream.packets()[*start..end];
            // Exact capacity: growing this buffer would strand unzeroized
            // copies of secret key packets in reallocated heap blocks, and the
            // caller's Zeroizing wrapper only covers the final allocation.
            let total = packets
                .iter()
                .map(|packet| stream.raw(packet).len())
                .sum::<usize>();
            let mut certificate = Vec::with_capacity(total);
            for packet in packets {
                certificate.extend_from_slice(stream.raw(packet));
            }
            certificate
        })
        .collect())
}

/// Parses one document and returns the union of its certificates.
///
/// Retained for candidate-key inputs, which only contribute components to the
/// designated-revoker candidate set.
fn parse_public_certificates(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, ParseFailure> {
    let packet_sets = parse_document_packet_sets(data, budget)?;
    let merged = merge_public_certificate_packet_sets(packet_sets, MAX_CERTIFICATES_PER_REQUEST)
        .map_err(map_certificate_merge_parse_error)?;
    let mut certificates = Vec::with_capacity(merged.len());
    for packet_set in &merged {
        let canonical = packet_set
            .finalize_with_export_budget(&[], budget.export_classification_mut())
            .map_err(map_certificate_merge_parse_error)?;
        validate_canonical_certificate(&canonical, budget)?;
        certificates.push(canonical.semantic);
    }
    Ok(certificates)
}

/// Narrows [`merge_public_key_documents`] to the composed semantic view.
///
/// Call sites that only verify signatures never need the canonical bytes or
/// the packet-derived component list, so they take the `SignedPublicKey` half
/// of each [`CanonicalCertificate`] and drop the rest.
pub(super) fn parse_public_key_documents(
    documents: &[Vec<u8>],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<SignedPublicKey>, OpenPgpReadError> {
    Ok(merge_public_key_documents(documents, budget)?
        .into_iter()
        .map(|certificate| certificate.semantic)
        .collect())
}

/// Scans every document once, unions the certificates by primary fingerprint
/// in first-seen order, and serializes each union exactly once.
///
/// Certificates carrying a v2/v3 primary key or subkey are skipped GnuPG-style
/// rather than failing the whole document; a document that contained nothing
/// else is rejected.
pub(super) fn merge_public_key_documents(
    documents: &[Vec<u8>],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<CanonicalCertificate>, OpenPgpReadError> {
    if documents.len() > MAX_PUBLIC_KEY_DOCUMENTS {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    let mut packet_sets = Vec::new();
    let mut skipped_unsupported = 0usize;
    for document in documents {
        let stream = RawPacketStream::parse_transferable_keyring(document, MAX_PACKETS_PER_REQUEST)
            .map_err(ParseFailure::from)
            .map_err(map_metadata_parse_failure)?;
        charge_stream(&stream, budget).map_err(map_metadata_parse_failure)?;
        let parsed = parse_public_certificate_packet_sets_with_budget(
            &stream,
            budget.signature_rehoming_mut(),
        )
        .map_err(map_certificate_merge_error)?;
        skipped_unsupported = skipped_unsupported.saturating_add(parsed.skipped_unsupported);
        skipped_unsupported = skipped_unsupported.saturating_add(parsed.skipped_malformed);
        if packet_sets.len() + parsed.certificates.len() > MAX_CERTIFICATES_PER_REQUEST {
            return Err(OpenPgpReadError::ResourceLimit);
        }
        packet_sets.extend(parsed.certificates);
    }
    if packet_sets.is_empty() && skipped_unsupported > 0 {
        return Err(OpenPgpReadError::InvalidArgument);
    }
    finalize_packet_sets(
        merge_public_certificate_packet_sets(packet_sets, MAX_CERTIFICATES_PER_REQUEST)
            .map_err(map_certificate_merge_error)?,
        budget,
    )
}

fn finalize_packet_sets(
    packet_sets: Vec<PublicCertificatePacketSet>,
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<CanonicalCertificate>, OpenPgpReadError> {
    let candidates = packet_sets
        .iter()
        .map(PublicCertificatePacketSet::public_components)
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_certificate_merge_error)?
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();
    let mut certificates = Vec::with_capacity(packet_sets.len());
    for packet_set in &packet_sets {
        let canonical = packet_set
            .finalize_with_export_budget(&candidates, budget.export_classification_mut())
            .map_err(map_certificate_merge_error)?;
        validate_canonical_certificate(&canonical, budget).map_err(map_metadata_parse_failure)?;
        certificates.push(canonical);
    }
    Ok(certificates)
}

/// Applies the aggregate per-request shape bounds to one finalized union.
///
/// Per-object bounds are already enforced by the merge, which sees every
/// retained packet; the composed view can omit evidence it rejects, so the
/// component list comes from the packet set instead.
fn validate_canonical_certificate(
    certificate: &CanonicalCertificate,
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    let component_count = certificate.components.len();
    if component_count > MAX_COMPONENTS_PER_CERTIFICATE
        || certificate.identities.len() > MAX_IDENTITIES_PER_CERTIFICATE
    {
        return Err(ParseFailure::ResourceLimit);
    }
    for component in &certificate.components {
        validate_public_key_parameter_values(component.public_params())?;
    }
    charge_certificate_shape(
        component_count,
        certificate.identities.len(),
        certificate.signature_count,
        budget,
    )
}

/// Charges one certificate's shape against the aggregate per-request bounds.
fn charge_certificate_shape(
    component_count: usize,
    identity_count: usize,
    signature_count: usize,
    budget: &mut OpenPgpReadBudget,
) -> Result<(), ParseFailure> {
    if component_count > MAX_COMPONENTS_PER_CERTIFICATE
        || identity_count > MAX_IDENTITIES_PER_CERTIFICATE
    {
        return Err(ParseFailure::ResourceLimit);
    }
    budget.charge_shape(component_count, identity_count, signature_count)
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

fn public_key_info(
    policy: &ValidatedCertificate<'_>,
    original_packets: &[u8],
    include_armor_checksum: bool,
) -> Option<PublicKeyInfo> {
    let certificate = policy.certificate();
    let primary = &policy.primary;
    let primary_available = policy.primary_available();
    // A subkey bound only by a weak-hash template authenticates nothing, but it
    // is renewable, so hiding it would make the renewal UI unable to offer the
    // repair. It is reported with `authenticated: false` and no capability; a
    // subkey with no verified binding at all stays invisible.
    let subkeys = policy
        .subkey_components()
        .filter(|subkey| {
            let subkey = subkey.policy();
            subkey.authenticated || subkey.has_template()
        })
        .map(|evaluated| {
            let subkey = evaluated.policy();
            PublicSubkeyInfo {
                fingerprint: fingerprint_hex(subkey.key),
                keygrip: keygrip(subkey.key.public_params()),
                key_id: key_id_hex(subkey.key),
                algorithm: algorithm_name(subkey.key.algorithm()),
                bit_strength: bit_strength(subkey.key.public_params()),
                can_sign: primary_available && evaluated.signing_usable(),
                can_encrypt: primary_available && evaluated.encryption_usable(),
                revoked: subkey.revoked,
                created_at_epoch_seconds: Some(u64::from(subkey.key.created_at().as_secs())),
                expires_at_epoch_seconds: component_expiration(subkey),
                authenticated: subkey.authenticated,
            }
        })
        .collect::<Vec<_>>();
    let primary_can_sign = primary_available && policy.primary_component().signing_usable();
    let certificate_can_encrypt = primary_available
        && (can_encrypt(primary.key.algorithm(), primary.key_flags.as_ref())
            || policy
                .subkey_components()
                .any(|subkey| subkey.encryption_usable()));
    let public_key_armored = if original_packets.is_empty() {
        String::new()
    } else {
        armor_public_key_packets(original_packets, include_armor_checksum)?
    };
    let user_id_details = policy
        .authenticated_user_ids()
        .filter_map(|validated| {
            let user = certificate.details.users.get(validated.index())?;
            let value = user.id.as_str()?;
            Some(UserIdInfo {
                identity_id: identity_id(13, validated.packet_body()),
                user_id: value.to_owned(),
            })
        })
        .collect();
    let user_ids = policy
        .authenticated_user_ids()
        .map(|validated| String::from_utf8_lossy(validated.packet_body()).into_owned())
        .collect::<Vec<_>>();
    Some(PublicKeyInfo {
        fingerprint: fingerprint_hex(primary.key),
        keygrip: keygrip(primary.key.public_params()),
        key_id: key_id_hex(primary.key),
        algorithm: algorithm_name(primary.key.algorithm()),
        bit_strength: bit_strength(primary.key.public_params()),
        emails: distinct_emails(&user_ids),
        user_ids,
        created_at_epoch_seconds: Some(u64::from(primary.key.created_at().as_secs())),
        expires_at_epoch_seconds: component_expiration(primary),
        revoked: primary.revoked,
        can_sign: primary_can_sign || subkeys.iter().any(|subkey| subkey.can_sign),
        can_encrypt: certificate_can_encrypt,
        public_key_armored,
        subkeys,
        user_id_details,
        component_fingerprints: certificate_components(certificate)
            .map(|component| component.fingerprint_hex())
            .collect(),
        revocation_authority_fingerprints: policy.revocation_authority_fingerprints().collect(),
        authenticated: primary.authenticated,
        // Disambiguates `authenticated: false`: `TemplateOnly` is the weak-hash
        // primary a renewal repairs, `None` is the primary a renewal cannot
        // touch. Reading only `authenticated` cannot tell the two apart.
        renewal: renewal_capability(policy.authorize_primary_renewal()),
    })
}

fn armor_public_key_packets(packets: &[u8], include_checksum: bool) -> Option<String> {
    let mut output = Vec::new();
    armor::write(
        &RawPackets(packets),
        BlockType::PublicKey,
        &mut output,
        None,
        include_checksum,
    )
    .ok()?;
    String::from_utf8(output).ok()
}

fn parse_detached_signatures(
    data: &[u8],
    budget: &mut OpenPgpReadBudget,
) -> Result<Vec<Signature>, OpenPgpReadError> {
    let input = openpgp_packet_input(data, Some(&BlockType::Signature))
        .map_err(map_metadata_parse_failure)?;
    // Keep packet framing strict and bounded for the whole detached-signature
    // document.  Only a semantic failure contained by one complete Signature
    // packet is recoverable under RFC 9580 section 5.2.5.
    let stream = RawPacketStream::parse(input.as_slice(), MAX_PACKETS_PER_REQUEST)
        .map_err(ParseFailure::from)
        .map_err(map_metadata_parse_failure)?;
    charge_stream(&stream, budget).map_err(map_metadata_parse_failure)?;

    let signature_packet_count = stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() == SIGNATURE_TAG)
        .count();
    if signature_packet_count > MAX_DETACHED_SIGNATURES {
        return Err(OpenPgpReadError::ResourceLimit);
    }
    // Malformed signatures consume the same request budget as valid ones, so
    // an attacker cannot bypass the detached-signature or verification-work
    // limits by making packet bodies fail semantic parsing.
    budget.charge_signatures(signature_packet_count)?;

    let mut signatures = Vec::with_capacity(signature_packet_count);
    for packet in stream.packets() {
        match packet.tag() {
            SIGNATURE_TAG => {
                if let Some(signature) = parse_detached_signature_packet(stream.raw(packet)) {
                    signatures.push(signature);
                }
            }
            // RFC 9580 section 10.4 permits Marker and Padding anywhere in
            // a detached-signature sequence.  Validate Marker semantics so
            // tolerance remains scoped to malformed Signature bodies.
            MARKER_TAG if stream.body_matches(packet, b"PGP") => {}
            PADDING_TAG | 40..=63 => {}
            // Known but unexpected packets and malformed Marker packets are
            // critical sequence errors (RFC 9580 sections 4.3 and 10).
            _ => return Err(OpenPgpReadError::InvalidArgument),
        }
    }
    (!signatures.is_empty())
        .then_some(signatures)
        .ok_or(OpenPgpReadError::InvalidArgument)
}

/// Semantically parses exactly one already-framed Signature packet.
///
/// Packet-local parse failures and unsupported signature forms are ignored;
/// the caller has already established and charged the packet boundary, so a
/// body error cannot consume bytes belonging to a valid peer packet.
fn parse_detached_signature_packet(packet: &[u8]) -> Option<Signature> {
    let mut parser = PacketParser::new(Cursor::new(packet));
    let signature = match parser.next()? {
        Ok(Packet::Signature(signature)) => signature,
        Ok(_) | Err(_) => return None,
    };
    if parser.next().is_some() {
        return None;
    }
    let config = signature.config()?;
    (matches!(
        signature.version(),
        SignatureVersion::V2 | SignatureVersion::V3 | SignatureVersion::V4 | SignatureVersion::V6
    ) && matches!(config.typ, SignatureType::Binary | SignatureType::Text)
        && config.pub_alg.can_sign()
        && config.hash_alg.digest_size().is_some())
    .then_some(signature)
}

#[cfg(test)]
fn find_subslice(input: &[u8], needle: &[u8]) -> Option<usize> {
    input
        .windows(needle.len())
        .position(|window| window == needle)
}

fn prepare_verification(
    signatures: &[Signature],
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    authenticated_recipients: AuthenticatedRecipientContext<'_>,
    mut budget: OpenPgpReadBudget,
) -> Result<PreparedVerification, OpenPgpReadError> {
    let Some(_first_signature) = signatures.first() else {
        return Err(OpenPgpReadError::InvalidArgument);
    };
    let candidates = all_components(certificates);
    let mut prepared_candidates = Vec::new();
    let mut results = Vec::with_capacity(signatures.len());
    // Signer authorization is evaluated at each signature's creation time.
    // Cache equal-time evaluations while retaining distinct historical views
    // for a multi-signature document. The detached-signature and signer-
    // candidate caps bound the number of distinct entries in this request.
    let mut validated = std::iter::repeat_with(CertificateValidationCache::new)
        .take(certificates.len())
        .collect::<Vec<_>>();
    for (signature_index, signature) in signatures.iter().enumerate() {
        let authenticated_recipient = authenticated_recipients.for_signature(signature_index)?;
        match resolve_signer(
            signature,
            certificates,
            &candidates,
            &mut validated,
            verification_time,
            &mut budget,
        )? {
            SignerResolution::Missing => results.push(verification_result(
                signature,
                VerificationStatus::MissingPublicKey,
                None,
                Vec::new(),
                Vec::new(),
                None,
                None,
            )),
            SignerResolution::Rejected { fingerprint } => {
                results.push(verification_result(
                    signature,
                    VerificationStatus::Invalid,
                    fingerprint,
                    Vec::new(),
                    Vec::new(),
                    None,
                    None,
                ));
            }
            SignerResolution::Selected {
                signers,
                report_rejected_signer,
            } => {
                let mut signature_warnings = Vec::new();
                let expired = signature_expired(signature, verification_time.reference_time());
                if expired {
                    signature_warnings.push(VerificationWarning::SignatureExpired);
                }
                if weak_data_signature_digest(signature) {
                    signature_warnings.push(VerificationWarning::WeakDigest);
                }
                let candidate = PreparedVerificationCandidate {
                    signature_index,
                    signature: Box::new(signature.clone()),
                    signers,
                    signature_warnings,
                    // RFC 9580 §5.2.3.18: a signature past its expiration time
                    // is no longer a valid statement, so it must never reach the
                    // same status as a live one. Refusing to hash it here keeps
                    // it at `Invalid` while still reporting SIGNATURE_EXPIRED,
                    // which is what tells the two apart on the wire.
                    policy_acceptable: !expired
                        && data_signature_acceptable(
                            signature,
                            verification_time.reference_time(),
                            verification_time.latest_acceptable_creation_time,
                            authenticated_recipient,
                        ),
                    report_rejected_signer,
                };
                results.push(candidate.rejected().ok_or(OpenPgpReadError::Internal)?);
                prepared_candidates.push(candidate);
            }
        }
    }
    if !prepared_candidates.is_empty() {
        return Ok(PreparedVerification::Candidates {
            candidates: prepared_candidates,
            results,
            budget,
        });
    }
    Ok(PreparedVerification::Terminal(
        aggregate_verification_results(results)?,
    ))
}

fn verification_result(
    signature: &Signature,
    status: VerificationStatus,
    fingerprint: Option<String>,
    user_ids: Vec<String>,
    warnings: Vec<VerificationWarning>,
    primary_fingerprint: Option<String>,
    primary_user_id: Option<String>,
) -> Verification {
    Verification {
        status,
        key_id: signature_key_id(signature),
        fingerprint,
        user_ids,
        created_at_epoch_seconds: signature_creation_time(signature).map(u64::from),
        warnings,
        primary_fingerprint,
        primary_user_id,
        signatures: Vec::new(),
    }
}

fn aggregate_verification_results(
    signatures: Vec<Verification>,
) -> Result<Verification, OpenPgpReadError> {
    let selected = signatures
        .iter()
        .find(|result| result.status == VerificationStatus::Valid)
        .or_else(|| {
            signatures
                .iter()
                .find(|result| result.status == VerificationStatus::Invalid)
        })
        .or_else(|| signatures.first())
        .ok_or(OpenPgpReadError::Internal)?;
    let mut aggregate = selected.clone();
    // Keep the historical single-signature result shape flat.  The nested
    // inventory is only meaningful when it preserves multiple independent
    // signature decisions; including the sole result would duplicate the
    // aggregate into `signatures[0]` and change the wire contract.
    if signatures.len() > 1 {
        aggregate.signatures = signatures;
    }
    Ok(aggregate)
}

fn verify_prepared(
    prepared: PreparedVerification,
    mut input: impl Read,
) -> Result<Verification, OpenPgpReadError> {
    match prepared {
        PreparedVerification::Terminal(result) => {
            io::copy(&mut input, &mut io::sink()).map_err(|_| OpenPgpReadError::Internal)?;
            Ok(result)
        }
        PreparedVerification::Candidates {
            candidates,
            mut results,
            mut budget,
        } => {
            let mut hashers = candidates
                .iter()
                .map(|candidate| {
                    if candidate.policy_acceptable {
                        PreparedSignatureHasher::new(&candidate.signature)
                    } else {
                        None
                    }
                })
                .collect::<Vec<_>>();
            let mut buffer = [0u8; VERIFY_BUFFER_BYTES];
            let mut text_canonicalizer = StreamingTextCanonicalizer::default();
            loop {
                let count = input
                    .read(&mut buffer)
                    .map_err(|_| OpenPgpReadError::Internal)?;
                if count == 0 {
                    break;
                }
                let input = &buffer[..count];
                let normalized_text = text_canonicalizer.update(input)?;
                for hasher in hashers.iter_mut().flatten() {
                    hasher.update(input, normalized_text);
                }
            }
            let normalized_text_tail = text_canonicalizer.finish();
            if !normalized_text_tail.is_empty() {
                for hasher in hashers.iter_mut().flatten() {
                    if hasher.text_mode {
                        hasher.hasher.update(normalized_text_tail);
                    }
                }
            }
            for (candidate, hasher) in candidates.iter().zip(hashers) {
                let Some(digest) = hasher.and_then(PreparedSignatureHasher::finish) else {
                    continue;
                };
                // The digest is shared by every candidate signer; only the
                // public-key operation differs, and it is budget-charged.
                for signer in candidate
                    .signers
                    .iter()
                    .filter(|signer| !signer.policy_conflict)
                {
                    budget
                        .policy_mut()
                        .select_certificate(&signer.certificate_fingerprint);
                    let verified = match signer.component.verify_digest(
                        &candidate.signature,
                        &digest,
                        budget.policy_mut(),
                    ) {
                        Ok(verified) => verified,
                        // The allowance belongs to this certificate. Treat an
                        // exhausted signer as unusable while retaining the
                        // independent allowances of later candidates.
                        Err(OpenPgpPolicyError::ResourceLimit) => continue,
                        Err(error) => return Err(error.into()),
                    };
                    if verified {
                        results[candidate.signature_index] =
                            candidate.result(signer, VerificationStatus::Valid);
                        break;
                    }
                }
            }
            aggregate_verification_results(results)
        }
    }
}

/// Applies the OpenPGP certificate, revocation, expiry, cross-certification,
/// and warning policy to message signatures whose data cryptography is
/// evaluated by the caller.
#[cfg(test)]
pub(super) fn evaluate_preverified_signatures(
    signatures: &[Signature],
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    authenticated_recipient: Option<&Fingerprint>,
    verify: impl FnMut(usize, &PublicComponent) -> bool,
) -> Result<Verification, OpenPgpReadError> {
    let authenticated_recipients = vec![authenticated_recipient.cloned(); signatures.len()];
    evaluate_preverified_signatures_with_recipients(
        signatures,
        certificates,
        verification_time,
        &authenticated_recipients,
        verify,
    )
}

/// Applies a distinct authenticated recipient identity to each signature.
/// Decryption uses this when the recipient subkey's binding is evaluated in
/// the historical certificate view selected by that signature.
pub(super) fn evaluate_preverified_signatures_with_recipients(
    signatures: &[Signature],
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    authenticated_recipients: &[Option<Fingerprint>],
    verify: impl FnMut(usize, &PublicComponent) -> bool,
) -> Result<Verification, OpenPgpReadError> {
    if authenticated_recipients.len() != signatures.len() {
        return Err(OpenPgpReadError::Internal);
    }
    evaluate_preverified_signatures_with_context(
        signatures,
        certificates,
        verification_time,
        AuthenticatedRecipientContext::PerSignature(authenticated_recipients),
        verify,
    )
}

fn evaluate_preverified_signatures_with_context(
    signatures: &[Signature],
    certificates: &[SignedPublicKey],
    verification_time: DataSignatureVerificationTime,
    authenticated_recipients: AuthenticatedRecipientContext<'_>,
    mut verify: impl FnMut(usize, &PublicComponent) -> bool,
) -> Result<Verification, OpenPgpReadError> {
    match prepare_verification(
        signatures,
        certificates,
        verification_time,
        authenticated_recipients,
        OpenPgpReadBudget::default(),
    )? {
        PreparedVerification::Terminal(result) => Ok(result),
        PreparedVerification::Candidates {
            candidates,
            mut results,
            ..
        } => {
            for candidate in &candidates {
                if !candidate.policy_acceptable {
                    continue;
                }
                for signer in candidate
                    .signers
                    .iter()
                    .filter(|signer| !signer.policy_conflict)
                {
                    if verify(candidate.signature_index, &signer.component) {
                        results[candidate.signature_index] =
                            candidate.result(signer, VerificationStatus::Valid);
                        break;
                    }
                }
            }
            aggregate_verification_results(results)
        }
    }
}

impl PreparedSignatureHasher {
    fn new(signature: &Signature) -> Option<Self> {
        let config = signature.config()?.clone();
        let mut hasher = config.hash_alg.new_hasher().ok()?;
        if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
            if config.hash_alg.salt_len() != Some(salt.len()) {
                return None;
            }
            hasher.update(salt);
        }
        Some(Self {
            text_mode: config.typ == SignatureType::Text,
            config,
            hasher,
        })
    }

    fn update(&mut self, binary: &[u8], normalized_text: &[u8]) {
        self.hasher.update(if self.text_mode {
            normalized_text
        } else {
            binary
        });
    }

    fn finish(mut self) -> Option<Vec<u8>> {
        let signature_data_len = self.config.hash_signature_data(&mut self.hasher).ok()?;
        let trailer = self.config.trailer(signature_data_len).ok()?;
        self.hasher.update(&trailer);
        Some(self.hasher.finalize().to_vec())
    }
}

impl StreamingTextCanonicalizer {
    fn update(&mut self, input: &[u8]) -> Result<&[u8], OpenPgpReadError> {
        if input.len() > VERIFY_BUFFER_BYTES {
            return Err(OpenPgpReadError::Internal);
        }
        self.output.clear();
        for &byte in input {
            if self.pending_cr {
                self.pending_cr = false;
                if byte == b'\n' {
                    self.output.extend_from_slice(b"\r\n");
                    continue;
                }
                self.output.extend_from_slice(b"\r\n");
            }
            match byte {
                b'\r' => self.pending_cr = true,
                b'\n' => self.output.extend_from_slice(b"\r\n"),
                _ => self.output.push(byte),
            }
        }
        Ok(&self.output)
    }

    fn finish(&mut self) -> &[u8] {
        self.output.clear();
        if self.pending_cr {
            self.pending_cr = false;
            self.output.extend_from_slice(b"\r\n");
        }
        &self.output
    }
}

fn resolve_signer<'a>(
    signature: &Signature,
    certificates: &'a [SignedPublicKey],
    candidates: &[PublicComponent],
    validated: &mut [CertificateValidationCache<'a>],
    verification_time: DataSignatureVerificationTime,
    budget: &mut OpenPgpReadBudget,
) -> Result<SignerResolution, OpenPgpReadError> {
    let issuer = SignatureIssuerMetadata::from_signature(signature);
    if issuer.is_invalid() {
        return Ok(SignerResolution::Rejected { fingerprint: None });
    }
    let issuerless = issuer.is_missing();

    let cryptographic_policy_time = verification_time.reference_time();
    let signature_time = signature_creation_time(signature)
        .map(u64::from)
        .filter(|creation_time| verification_time.accepts_creation_time(*creation_time));
    // An out-of-window creation time cannot choose a certificate view at all.
    // For an accepted signature, an already-expired signature is
    // unconditionally ineligible for a Valid result. Resolve its signer using
    // the current certificate view so the compatibility result can still
    // identify the key and report the SIGNATURE_EXPIRED warning, even for a
    // backdated signature that predates the certificate. Past live signatures
    // retain their historical view. A tolerated future data signature is
    // authorized against the trusted current view: clock-skew tolerance must
    // not activate a future certificate binding or revocation early.
    let authorization_time = signature_time.map(|signature_time| {
        if signature_expired(signature, cryptographic_policy_time) {
            cryptographic_policy_time
        } else {
            signature_time.min(cryptographic_policy_time)
        }
    });
    let mut raw_fingerprints = Vec::new();
    let mut selected = Vec::new();
    let mut matching_components = 0usize;
    for (certificate_index, certificate) in certificates.iter().enumerate() {
        let primary_matches = if issuerless {
            signature_verification_compatible(signature, &certificate.primary_key)
        } else {
            issuer.matches(&certificate.primary_key)
        };
        let matching_subkeys = certificate
            .public_subkeys
            .iter()
            .enumerate()
            .filter_map(|(index, subkey)| {
                let matches = if issuerless {
                    signature_verification_compatible(signature, &subkey.key)
                } else {
                    issuer.matches(&subkey.key)
                };
                matches.then_some(index)
            })
            .collect::<Vec<_>>();
        if !primary_matches && matching_subkeys.is_empty() {
            continue;
        }
        matching_components = matching_components
            .saturating_add(usize::from(primary_matches))
            .saturating_add(matching_subkeys.len());
        if matching_components > MAX_SIGNER_CANDIDATES_PER_SIGNATURE {
            // Reject before certificate policy evaluation or public-key work.
            // Request-level component limits bound the discovery scan itself.
            return Ok(SignerResolution::Rejected { fingerprint: None });
        }

        if primary_matches {
            push_unique(
                &mut raw_fingerprints,
                fingerprint_hex(&certificate.primary_key),
            );
        }
        for &index in &matching_subkeys {
            let subkey = certificate
                .public_subkeys
                .get(index)
                .ok_or(OpenPgpReadError::Internal)?;
            push_unique(&mut raw_fingerprints, fingerprint_hex(&subkey.key));
        }

        // A data signature without a signed creation time cannot establish
        // the historical certificate view needed to authorize its signer.
        let Some(authorization_time) = authorization_time else {
            continue;
        };

        let slot = validated
            .get_mut(certificate_index)
            .ok_or(OpenPgpReadError::Internal)?
            .entry((authorization_time, cryptographic_policy_time))
            .or_default();
        // A certification-flooded certificate cannot vouch for the signer,
        // but it must not fail resolution against other certificates. Cache
        // that stable rejection just like a successful policy evaluation.
        let Some(policy) = slot.get_or_validate(|| {
            validate_certificate_with_policy_time(
                certificate,
                candidates,
                authorization_time,
                cryptographic_policy_time,
                budget.policy_mut(),
            )
        })?
        else {
            continue;
        };
        let primary_component = policy.primary_component();
        if primary_matches
            && (primary_component.policy().policy_conflict
                || signer_verification_usable(&primary_component, false))
        {
            let identity_authenticated = policy.primary.authenticated;
            selected.push(selected_signer(
                PublicComponent::Primary(certificate.primary_key.clone()),
                policy,
                identity_authenticated,
                primary_component,
            ));
        }
        for index in matching_subkeys {
            let subkey = certificate
                .public_subkeys
                .get(index)
                .ok_or(OpenPgpReadError::Internal)?;
            let component = policy
                .subkey(&subkey.key)
                .ok_or(OpenPgpReadError::Internal)?;
            let component_policy = component.policy();
            let policy_conflict =
                policy.primary.policy_conflict || component_policy.policy_conflict;
            if !policy_conflict
                && (!policy.primary.authenticated || !signer_verification_usable(&component, true))
            {
                continue;
            }
            selected.push(selected_signer(
                PublicComponent::Subkey(subkey.key.clone()),
                policy,
                true,
                component,
            ));
        }
    }

    if raw_fingerprints.is_empty() {
        return Ok(SignerResolution::Missing);
    }
    if !selected.is_empty() {
        return Ok(SignerResolution::Selected {
            signers: selected,
            report_rejected_signer: !issuerless,
        });
    }
    Ok(SignerResolution::Rejected {
        fingerprint: (!issuerless && raw_fingerprints.len() == 1)
            .then(|| raw_fingerprints.remove(0)),
    })
}

/// Returns whether an authenticated component is eligible for mathematical
/// verification of an existing data signature.
///
/// Revocation and key expiration intentionally do not filter candidates here.
/// A cryptographically valid signature made by a revoked or expired authority
/// still needs to reach [`selected_signer`] so its mathematical status, identity,
/// and precise policy warning can be reported separately. Authentication,
/// signing key flags, and the subkey back-signature remain mandatory.
fn signer_verification_usable<K: KeyDetails>(
    component: &EvaluatedComponent<'_, '_, K>,
    require_cross_certification: bool,
) -> bool {
    let component = component.policy();
    component.authenticated
        && key_signature_verification_acceptable(component.key)
        && (!require_cross_certification || component.signing_cross_certified)
        && can_sign(component.key.algorithm(), component.key_flags.as_ref())
}

/// Returns whether the signature's digest is one the verification policy
/// refuses outright.
///
/// Reported as a distinct warning so a caller can say "this signature uses
/// SHA-1" instead of "this signature does not verify".
fn weak_data_signature_digest(signature: &Signature) -> bool {
    signature
        .config()
        .is_some_and(|config| is_legacy_weak_hash(config.hash_alg))
}

fn selected_signer<K: KeyDetails>(
    component: PublicComponent,
    policy: &ValidatedCertificate<'_>,
    identity_authenticated: bool,
    component_policy: EvaluatedComponent<'_, '_, K>,
) -> SelectedSigner {
    let evaluated_component = component_policy;
    let component_policy = evaluated_component.policy();
    let policy_conflict = policy.primary.policy_conflict || component_policy.policy_conflict;
    let revoked = policy.primary.revoked || component_policy.revoked;
    let expired = policy.primary_component().is_expired() || evaluated_component.is_expired();
    let mut warnings = Vec::new();
    if identity_authenticated && revoked {
        warnings.push(VerificationWarning::KeyRevoked);
    }
    if identity_authenticated && expired {
        warnings.push(VerificationWarning::KeyExpired);
    }
    if policy_conflict
        || policy.primary.revocation_status.is_indeterminate()
        || component_policy.revocation_status.is_indeterminate()
    {
        warnings.push(VerificationWarning::PolicyConflict);
    }
    SelectedSigner {
        fingerprint: component.fingerprint_hex(),
        component,
        certificate_fingerprint: policy
            .certificate()
            .primary_key
            .fingerprint()
            .as_bytes()
            .to_vec(),
        primary_fingerprint: fingerprint_hex(&policy.certificate().primary_key),
        user_ids: if identity_authenticated {
            policy
                .authenticated_user_ids()
                .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
                .collect()
        } else {
            Vec::new()
        },
        primary_user_id: if identity_authenticated {
            policy
                .primary_user_id()
                .map(|user_id| String::from_utf8_lossy(user_id.packet_body()).into_owned())
        } else {
            None
        },
        warnings,
        policy_conflict,
    }
}

fn push_unique(values: &mut Vec<String>, value: String) {
    if !values.contains(&value) {
        values.push(value);
    }
}

fn signature_key_id(signature: &Signature) -> String {
    let issuer = SignatureIssuerMetadata::from_signature(signature);
    let key_id = issuer
        .key_id()
        .map(AsRef::as_ref)
        .or_else(|| issuer.fingerprint().and_then(fingerprint_key_id))
        .map(hex_upper);
    key_id.unwrap_or_else(|| "0000000000000000".to_owned())
}

fn fingerprint_key_id(fingerprint: &Fingerprint) -> Option<&[u8]> {
    match fingerprint {
        // RFC 9580 Sections 5.5.4.2 and 5.5.4.3 define opposite ends.
        Fingerprint::V4(bytes) => revocation_key_id(bytes),
        Fingerprint::V6(bytes) => revocation_key_id(bytes),
        _ => None,
    }
}

fn key_id_hex(key: &impl KeyDetails) -> String {
    hex_upper(key.legacy_key_id().as_ref())
}

fn bit_strength(params: &PublicParams) -> Option<u32> {
    match params {
        PublicParams::RSA(_) | PublicParams::DSA(_) | PublicParams::Elgamal(_) => {
            Some(u32::from(leading_mpi_bits(params)?))
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
mod tests;
