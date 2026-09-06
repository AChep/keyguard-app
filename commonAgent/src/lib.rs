//! Protocol-neutral caller identity and authorization primitives for Keyguard agents.
//!
//! Display metadata is collected alongside typed authorization evidence, but
//! remains structurally separate so protocol adapters cannot accidentally use
//! names or paths as reusable approval keys.

use sha2::{Digest, Sha256};
use thiserror::Error;

#[cfg(unix)]
pub mod caller_identity;
#[cfg(target_os = "linux")]
pub mod linux_identity;
#[cfg(target_os = "macos")]
pub mod macos;
#[cfg(unix)]
pub mod socket_lifecycle;
#[cfg(unix)]
pub mod unix_caller_identity;

/// Size of opaque authorization fingerprints carried over protobuf IPC.
pub const PRINCIPAL_FINGERPRINT_LEN: usize = 32;

/// Exact IPC contract revision required by current desktop agents and apps.
pub const IPC_PROTOCOL_REVISION: u32 = 1;

/// Exact stdout record an agent binary emits once its public endpoint is
/// ready. Must stay byte-identical to the desktop app's expectation
/// (`AGENT_STARTUP_READY_RECORD` on the Kotlin side).
pub const STARTUP_READY_RECORD: &[u8] = b"KEYGUARD_AGENT_READY 1\n";

/// Writes [`STARTUP_READY_RECORD`] to `writer` and flushes it.
///
/// # Errors
///
/// Propagates the underlying write or flush failure.
pub fn write_startup_ready_record_to(mut writer: impl std::io::Write) -> std::io::Result<()> {
    writer.write_all(STARTUP_READY_RECORD)?;
    writer.flush()
}

const SUBJECT_FINGERPRINT_DOMAIN: &[u8] = b"keyguard-agent-subject-v2\0";
const CONTEXT_FINGERPRINT_DOMAIN: &[u8] = b"keyguard-agent-context-v2\0";

/// An opaque, fixed-size identifier used to scope an approval to one public
/// agent connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ConnectionPrincipal([u8; PRINCIPAL_FINGERPRINT_LEN]);

/// Explicit name for the live connection fingerprint.
pub type ConnectionFingerprint = ConnectionPrincipal;

impl ConnectionPrincipal {
    /// Generates a fresh connection principal from the operating system CSPRNG.
    ///
    /// # Errors
    ///
    /// Returns [`IdentityError::Random`] when the operating system cannot
    /// provide cryptographically secure randomness. Callers must reject the
    /// connection or disable reusable authorization rather than fall back to
    /// a predictable identifier.
    pub fn generate() -> Result<Self, IdentityError> {
        let mut bytes = [0u8; PRINCIPAL_FINGERPRINT_LEN];
        getrandom::fill(&mut bytes).map_err(IdentityError::Random)?;
        Ok(Self(bytes))
    }

    /// Constructs a principal from already validated opaque bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; PRINCIPAL_FINGERPRINT_LEN]) -> Self {
        Self(bytes)
    }

    /// Borrows the fixed-size principal bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; PRINCIPAL_FINGERPRINT_LEN] {
        &self.0
    }

    /// Returns the principal bytes by value.
    #[must_use]
    pub const fn into_bytes(self) -> [u8; PRINCIPAL_FINGERPRINT_LEN] {
        self.0
    }
}

/// Canonical fingerprint of a verified process or application subject.
///
/// This type is deliberately distinct from [`ConnectionPrincipal`]. A stable
/// subject may be reused across connections only at the exact scope established
/// by its canonical platform recipe.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct SubjectFingerprint([u8; PRINCIPAL_FINGERPRINT_LEN]);

impl SubjectFingerprint {
    /// Derives a stable subject fingerprint from canonical platform evidence.
    ///
    /// `evidence_domain` must uniquely identify both the platform evidence
    /// source and its canonical encoding recipe. Length-delimited domain
    /// separation prevents fingerprints from different collectors or encoding
    /// versions from colliding by construction.
    ///
    /// # Errors
    ///
    /// Returns [`IdentityError::EmptyEvidenceDomain`] or
    /// [`IdentityError::EmptyCanonicalEvidence`] instead of constructing a
    /// fingerprint whose provenance cannot be established.
    pub fn derive(
        evidence_domain: &[u8],
        canonical_evidence: &[u8],
    ) -> Result<Self, IdentityError> {
        if evidence_domain.is_empty() {
            return Err(IdentityError::EmptyEvidenceDomain);
        }
        if canonical_evidence.is_empty() {
            return Err(IdentityError::EmptyCanonicalEvidence);
        }

        Ok(Self(derive_fingerprint(
            SUBJECT_FINGERPRINT_DOMAIN,
            evidence_domain,
            canonical_evidence,
        )))
    }

    /// Constructs a fingerprint from already validated canonical bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; PRINCIPAL_FINGERPRINT_LEN]) -> Self {
        Self(bytes)
    }

    /// Borrows the fixed-size fingerprint bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; PRINCIPAL_FINGERPRINT_LEN] {
        &self.0
    }

    /// Returns the fingerprint bytes by value.
    #[must_use]
    pub const fn into_bytes(self) -> [u8; PRINCIPAL_FINGERPRINT_LEN] {
        self.0
    }
}

/// Semantic kind proved by the canonical subject fingerprint recipe.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum VerifiedSubjectKind {
    /// Fingerprint identifies one kernel-verified operating-system process instance.
    Process,
    /// Fingerprint identifies one live owning-application instance.
    ApplicationInstance,
    /// Fingerprint identifies a signed or framework-authenticated application.
    StableApplication,
    /// Fingerprint identifies one live terminal session, tab, or pane.
    TerminalSession,
}

/// Stable verified identity returned by a platform collector.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct VerifiedSubject {
    fingerprint: SubjectFingerprint,
    kind: VerifiedSubjectKind,
}

impl VerifiedSubject {
    /// Associates a canonical fingerprint with the exact scope it proves.
    #[must_use]
    pub const fn new(fingerprint: SubjectFingerprint, kind: VerifiedSubjectKind) -> Self {
        Self { fingerprint, kind }
    }

    /// Returns the canonical subject fingerprint.
    #[must_use]
    pub const fn fingerprint(&self) -> SubjectFingerprint {
        self.fingerprint
    }

    /// Returns the semantic subject kind established by the collector.
    #[must_use]
    pub const fn kind(&self) -> VerifiedSubjectKind {
        self.kind
    }
}

/// Canonical fingerprint of verified protocol context, such as an OpenSSH
/// host/session-binding chain.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct AuthorizationContextFingerprint([u8; PRINCIPAL_FINGERPRINT_LEN]);

impl AuthorizationContextFingerprint {
    /// Derives a domain-separated fingerprint from canonical verified context.
    ///
    /// # Errors
    ///
    /// Returns [`IdentityError::EmptyAuthorizationContext`] when no verified
    /// context exists. The wire representation uses an absent field for that
    /// state rather than the fingerprint of an empty byte string.
    pub fn derive(canonical_context: &[u8]) -> Result<Self, IdentityError> {
        if canonical_context.is_empty() {
            return Err(IdentityError::EmptyAuthorizationContext);
        }

        Ok(Self(derive_fingerprint(
            CONTEXT_FINGERPRINT_DOMAIN,
            b"verified-protocol-context",
            canonical_context,
        )))
    }

    /// Constructs a fingerprint from already validated canonical bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; PRINCIPAL_FINGERPRINT_LEN]) -> Self {
        Self(bytes)
    }

    /// Borrows the fixed-size fingerprint bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; PRINCIPAL_FINGERPRINT_LEN] {
        &self.0
    }

    /// Returns the fingerprint bytes by value.
    #[must_use]
    pub const fn into_bytes(self) -> [u8; PRINCIPAL_FINGERPRINT_LEN] {
        self.0
    }
}

fn derive_fingerprint(
    outer_domain: &[u8],
    evidence_domain: &[u8],
    canonical_evidence: &[u8],
) -> [u8; PRINCIPAL_FINGERPRINT_LEN] {
    let mut digest = Sha256::new();
    digest.update(outer_domain);
    digest.update((evidence_domain.len() as u64).to_be_bytes());
    digest.update(evidence_domain);
    digest.update((canonical_evidence.len() as u64).to_be_bytes());
    digest.update(canonical_evidence);
    digest.finalize().into()
}

/// Failures while constructing trusted caller identity primitives.
#[derive(Debug, Error)]
pub enum IdentityError {
    /// The operating system CSPRNG failed.
    #[error("failed to generate caller connection identity")]
    Random(#[source] getrandom::Error),
    /// The collector did not identify its canonical evidence recipe.
    #[error("caller identity evidence domain is empty")]
    EmptyEvidenceDomain,
    /// The collector did not provide any canonical subject evidence.
    #[error("canonical caller identity evidence is empty")]
    EmptyCanonicalEvidence,
    /// The caller did not provide any verified authorization context.
    #[error("authorization context is empty")]
    EmptyAuthorizationContext,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn startup_ready_record_is_exact_and_newline_terminated() {
        let mut output = Vec::new();
        write_startup_ready_record_to(&mut output).unwrap();

        assert_eq!(output, b"KEYGUARD_AGENT_READY 1\n");
    }

    #[test]
    fn connection_principal_round_trips_fixed_bytes() {
        let bytes = [0xA5; PRINCIPAL_FINGERPRINT_LEN];
        let principal = ConnectionPrincipal::from_bytes(bytes);

        assert_eq!(principal.as_bytes(), &bytes);
        assert_eq!(principal.into_bytes(), bytes);
    }

    #[test]
    fn generated_connection_principal_has_wire_length() {
        let principal = ConnectionPrincipal::generate().expect("OS randomness");

        assert_eq!(principal.as_bytes().len(), PRINCIPAL_FINGERPRINT_LEN);
    }

    #[test]
    fn subject_fingerprints_are_domain_separated() {
        let first = SubjectFingerprint::derive(b"macos-signing-v1", b"same evidence")
            .expect("valid subject fingerprint");
        let second = SubjectFingerprint::derive(b"windows-signing-v1", b"same evidence")
            .expect("valid subject fingerprint");

        assert_ne!(first, second);
    }

    #[test]
    fn subject_fingerprint_rejects_ambiguous_empty_inputs() {
        assert!(matches!(
            SubjectFingerprint::derive(b"", b"evidence"),
            Err(IdentityError::EmptyEvidenceDomain)
        ));
        assert!(matches!(
            SubjectFingerprint::derive(b"macos-signing-v1", b""),
            Err(IdentityError::EmptyCanonicalEvidence)
        ));
    }

    #[test]
    fn authorization_context_is_separate_from_subject_identity() {
        let subject = SubjectFingerprint::derive(b"test-subject-v1", b"application")
            .expect("valid subject fingerprint");
        let first =
            AuthorizationContextFingerprint::derive(b"host-a").expect("valid context fingerprint");
        let second =
            AuthorizationContextFingerprint::derive(b"host-b").expect("valid context fingerprint");

        assert_ne!(first, second);
        assert_ne!(first.as_bytes(), subject.as_bytes());
        assert!(matches!(
            AuthorizationContextFingerprint::derive(b""),
            Err(IdentityError::EmptyAuthorizationContext)
        ));
    }

    #[test]
    fn verified_subject_carries_its_kind() {
        let fingerprint = SubjectFingerprint::from_bytes([0x33; PRINCIPAL_FINGERPRINT_LEN]);
        let subject = VerifiedSubject::new(fingerprint, VerifiedSubjectKind::StableApplication);

        assert_eq!(subject.fingerprint(), fingerprint);
        assert_eq!(subject.kind(), VerifiedSubjectKind::StableApplication);
    }
}
