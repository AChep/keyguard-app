//! Fail-closed macOS identity collection for accepted Unix-domain sockets.
//!
//! The primary evidence is `LOCAL_PEERTOKEN`, an audit token retained by the
//! accepted socket. The token includes the kernel PID version, so it continues
//! to identify the original process instance even if the numeric PID is later
//! reused. The direct peer always receives a process subject. Independent
//! application and terminal-session subjects require bounded ancestry and
//! retained `EVFILT_PROC` lifecycle guards. Stable application identity also
//! requires strict Security.framework validation.
//!
//! If `LOCAL_PEERTOKEN` is unavailable, collection fails. Callers may still use
//! a fresh connection-only principal, but must not construct a reusable subject
//! from a numeric PID or display metadata.

use crate::{IdentityError, SubjectFingerprint, VerifiedSubject, VerifiedSubjectKind};
use std::ffi::{c_char, c_int, c_void};
use std::io;
use std::mem::{size_of, MaybeUninit};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::ptr::{self, NonNull};
use std::sync::Arc;
use thiserror::Error;

const SOL_LOCAL: c_int = 0;
const LOCAL_PEEREPID: c_int = 0x003;
const LOCAL_PEERTOKEN: c_int = 0x006;
const AUDIT_TOKEN_VALUE_COUNT: usize = 8;
const MAX_SIGNING_IDENTIFIER_BYTES: usize = 4 * 1024;
const MAX_DESIGNATED_REQUIREMENT_BYTES: usize = 256 * 1024;
const MAX_CDHASH_BYTES: usize = 128;

const K_SEC_CS_CHECK_ALL_ARCHITECTURES: u32 = 1 << 0;
const K_SEC_CS_STRICT_VALIDATE: u32 = 1 << 4;
const K_SEC_CS_SIGNING_INFORMATION: u32 = 1 << 1;
const K_CF_STRING_ENCODING_UTF8: u32 = 0x0800_0100;
const K_CF_NUMBER_SINT32_TYPE: CfIndex = 3;

const AUDIT_TOKEN_EVIDENCE_DOMAIN: &[u8] = b"keyguard.macos.audit-token-validated-code.v1";
const APPLICATION_INSTANCE_EVIDENCE_DOMAIN: &[u8] = b"keyguard.macos.application-instance.v1";
const APPLICATION_CODE_SIGNING_EVIDENCE_DOMAIN: &[u8] =
    b"keyguard.macos.application-code-signing.v1";
const TERMINAL_SESSION_EVIDENCE_DOMAIN: &[u8] = b"keyguard.macos.terminal-session.v1";
const MAX_ANCESTRY_DEPTH: usize = 16;

type CfTypeRef = *const c_void;
type CfDataRef = *const c_void;
type CfDictionaryRef = *const c_void;
type CfStringRef = *const c_void;
type SecCodeRef = *const c_void;
type SecRequirementRef = *const c_void;
type OsStatus = i32;
type CfIndex = isize;
type CfTypeId = usize;
type CfBoolean = u8;

/// Provenance recipe used to construct a macOS subject fingerprint.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MacosEvidenceClass {
    /// Full socket audit token, including its kernel PID version.
    AuditToken,
}

/// Human-readable signing attributes accompanying a stable subject.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MacosCodeSigningIdentity {
    team_identifier: String,
    signing_identifier: String,
}

impl MacosCodeSigningIdentity {
    /// Team identifier verified by Security.framework.
    #[must_use]
    pub fn team_identifier(&self) -> &str {
        &self.team_identifier
    }

    /// Code-signing identifier verified by Security.framework.
    #[must_use]
    pub fn signing_identifier(&self) -> &str {
        &self.signing_identifier
    }
}

/// Why a kernel-authenticated peer was conservatively kept at process scope.
#[derive(Clone, Debug, Eq, PartialEq, Error)]
#[error("{message}")]
pub struct MacosCodeSigningFailure {
    message: String,
}

impl MacosCodeSigningFailure {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }

    fn status(operation: &'static str, status: OsStatus) -> Self {
        Self::new(format!("{operation} failed with OSStatus {status}"))
    }
}

/// Result of validating the direct peer's live code.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MacosCodeSigningStatus {
    /// Non-platform code carries authenticated team/signing identifiers.
    Verified(MacosCodeSigningIdentity),
    /// Direct code remains process-scoped and records why no signing label was exposed.
    Degraded(MacosCodeSigningFailure),
}

/// Verified subject and kernel peer metadata for one accepted connection.
#[derive(Clone, Debug)]
pub struct MacosPeerIdentity {
    pid: u32,
    pid_version: u32,
    effective_uid: u32,
    subject: VerifiedSubject,
    evidence_class: MacosEvidenceClass,
    code_signing_status: MacosCodeSigningStatus,
    team_identifier: Option<String>,
    signing_identifier: Option<String>,
    application: Option<MacosApplicationIdentity>,
    terminal_session: Option<MacosTerminalSessionIdentity>,
    audit_token: AuditToken,
    retained_socket: Arc<OwnedFd>,
}

/// Verified terminal session derived from the peer's kernel session/TTY.
#[derive(Clone, Debug)]
pub struct MacosTerminalSessionIdentity {
    subject: VerifiedSubject,
    session_id: u32,
    controlling_tty: u64,
    leader: MacosProcessSnapshot,
    accepted_chain: Box<[MacosProcessSnapshot]>,
    lifecycle: Arc<MacosProcessLifecycleGuard>,
}

impl PartialEq for MacosTerminalSessionIdentity {
    fn eq(&self, other: &Self) -> bool {
        self.subject == other.subject
            && self.session_id == other.session_id
            && self.controlling_tty == other.controlling_tty
            && self.leader == other.leader
            && self.accepted_chain == other.accepted_chain
    }
}

impl Eq for MacosTerminalSessionIdentity {}

impl MacosTerminalSessionIdentity {
    /// Terminal-session authorization subject.
    #[must_use]
    pub const fn subject(&self) -> VerifiedSubject {
        self.subject
    }

    /// Kernel session leader PID.
    #[must_use]
    pub const fn session_id(&self) -> u32 {
        self.session_id
    }
}

/// Verified origin application plus display-only presentation hints.
#[derive(Clone, Debug)]
pub struct MacosApplicationIdentity {
    pid: u32,
    subject: VerifiedSubject,
    display_name: String,
    bundle_path: String,
    team_identifier: Option<String>,
    signing_identifier: Option<String>,
    lifecycle: Arc<MacosProcessLifecycleGuard>,
}

impl PartialEq for MacosApplicationIdentity {
    fn eq(&self, other: &Self) -> bool {
        self.pid == other.pid
            && self.subject == other.subject
            && self.display_name == other.display_name
            && self.bundle_path == other.bundle_path
            && self.team_identifier == other.team_identifier
            && self.signing_identifier == other.signing_identifier
    }
}

impl Eq for MacosApplicationIdentity {}

#[derive(Debug)]
struct MacosProcessLifecycleGuard {
    kqueue: OwnedFd,
}

impl MacosProcessLifecycleGuard {
    fn ensure_live(&self) -> Result<(), MacosIdentityError> {
        let mut event = MaybeUninit::<libc::kevent>::zeroed();
        let timeout = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        // SAFETY: event is writable storage for one kevent, timeout is valid,
        // and the retained kqueue descriptor remains live for the call.
        let count = unsafe {
            libc::kevent(
                self.kqueue.as_raw_fd(),
                ptr::null(),
                0,
                event.as_mut_ptr(),
                1,
                &timeout,
            )
        };
        if count < 0 {
            return Err(io_error("kevent(process lifecycle poll)"));
        }
        if count != 0 {
            return Err(MacosIdentityError::ProcessIdentityChanged);
        }
        Ok(())
    }
}

impl MacosApplicationIdentity {
    /// PID of the verified live application process.
    #[must_use]
    pub const fn pid(&self) -> u32 {
        self.pid
    }

    /// Stable-application or application-instance authorization subject.
    #[must_use]
    pub const fn subject(&self) -> VerifiedSubject {
        self.subject
    }

    /// Bundle-derived name hint. This is presentation metadata only.
    #[must_use]
    pub fn display_name(&self) -> &str {
        &self.display_name
    }

    /// Verified process ancestry's enclosing application bundle path.
    /// The path remains presentation metadata and is not an authorization key.
    #[must_use]
    pub fn bundle_path(&self) -> &str {
        &self.bundle_path
    }

    /// Security.framework TeamIdentifier when available.
    #[must_use]
    pub fn team_identifier(&self) -> Option<&str> {
        self.team_identifier.as_deref()
    }

    /// Security.framework signing identifier when available.
    #[must_use]
    pub fn signing_identifier(&self) -> Option<&str> {
        self.signing_identifier.as_deref()
    }
}

impl MacosPeerIdentity {
    /// Maximum descriptors beyond the accepted public socket needed by one
    /// connection at the peak of revalidation: retained socket duplicate,
    /// retained app/session kqueues, and two temporary recollection kqueues.
    pub const MAX_ADDITIONAL_FD_COUNT: usize = 5;

    /// PID recorded by the accepted socket's kernel credentials.
    #[must_use]
    pub const fn pid(&self) -> u32 {
        self.pid
    }

    /// Kernel PID generation carried by the retained audit token.
    #[must_use]
    pub const fn pid_version(&self) -> u32 {
        self.pid_version
    }

    /// Effective UID recorded by the accepted socket's kernel credentials.
    #[must_use]
    pub const fn effective_uid(&self) -> u32 {
        self.effective_uid
    }

    /// Cryptographically separated direct-process subject identity.
    #[must_use]
    pub const fn subject(&self) -> VerifiedSubject {
        self.subject
    }

    /// Provenance recipe selected for the subject fingerprint.
    #[must_use]
    pub const fn evidence_class(&self) -> MacosEvidenceClass {
        self.evidence_class
    }

    /// Direct code-signing result or conservative degradation reason.
    #[must_use]
    pub const fn code_signing_status(&self) -> &MacosCodeSigningStatus {
        &self.code_signing_status
    }

    /// Direct peer TeamIdentifier, when present in validated signing info.
    #[must_use]
    pub fn team_identifier(&self) -> Option<&str> {
        self.team_identifier.as_deref()
    }

    /// Direct peer signing identifier, when present in validated signing info.
    #[must_use]
    pub fn signing_identifier(&self) -> Option<&str> {
        self.signing_identifier.as_deref()
    }

    /// Verified origin application, when a bounded ancestry walk found and
    /// validated an enclosing `.app` process.
    #[must_use]
    pub const fn application(&self) -> Option<&MacosApplicationIdentity> {
        self.application.as_ref()
    }

    /// Verified terminal-session subject, when the peer has a controlling TTY.
    #[must_use]
    pub fn terminal_session_subject(&self) -> Option<VerifiedSubject> {
        self.terminal_session
            .as_ref()
            .map(|session| session.subject)
    }

    /// Revalidates the retained socket audit token and dynamic code identity.
    ///
    /// Agent sessions should call this immediately before every IPC request
    /// that may consult or create a reusable approval. This catches an inherited
    /// socket whose peer executes different code after the connection was
    /// accepted. Application and terminal ancestors are also guarded against
    /// exit/exec and fully recollected before reuse.
    ///
    /// # Errors
    ///
    /// Returns an error when the socket peer, audit token, code validity,
    /// evidence class, or canonical subject differs from the accepted identity.
    pub fn revalidate(&self) -> Result<(), MacosIdentityError> {
        if let Some(application) = &self.application {
            application.lifecycle.ensure_live()?;
        }
        if let Some(session) = &self.terminal_session {
            session.lifecycle.ensure_live()?;
        }
        // Reuse the retained descriptor instead of duplicating it again. This
        // keeps the per-session descriptor cost constant even when many peers
        // are revalidated concurrently near RLIMIT_NOFILE.
        let fd = self.retained_socket.as_raw_fd();
        let token = read_peer_audit_token(fd)?;
        let current = collect_from_audit_token(
            fd,
            token,
            effective_uid(),
            Arc::clone(&self.retained_socket),
        )?;
        if self.audit_token != current.audit_token
            || self.pid != current.pid
            || self.pid_version != current.pid_version
            || self.effective_uid != current.effective_uid
            || self.subject != current.subject
            || self.evidence_class != current.evidence_class
            || self.team_identifier != current.team_identifier
            || self.signing_identifier != current.signing_identifier
            || self.application != current.application
            || self.terminal_session != current.terminal_session
        {
            return Err(MacosIdentityError::ProcessIdentityChanged);
        }
        if let Some(application) = &self.application {
            application.lifecycle.ensure_live()?;
        }
        if let Some(session) = &self.terminal_session {
            session.lifecycle.ensure_live()?;
        }
        Ok(())
    }
}

/// Fatal failures at the kernel/process provenance boundary.
#[derive(Debug, Error)]
pub enum MacosIdentityError {
    /// A required socket or process operation failed.
    #[error("{operation} failed")]
    Io {
        operation: &'static str,
        #[source]
        source: io::Error,
    },
    /// The peer credentials are internally inconsistent or not owner-local.
    #[error("invalid macOS peer identity: {0}")]
    InvalidPeer(&'static str),
    /// `LOCAL_PEERTOKEN` is unavailable, so process identity cannot be pinned.
    #[error("LOCAL_PEERTOKEN is unavailable; refusing reusable macOS identity")]
    AuditTokenUnavailable {
        #[source]
        source: io::Error,
    },
    /// The audit-token-selected dynamic code could not be strictly validated.
    #[error("macOS peer code-signing validation failed")]
    CodeSigning(#[source] MacosCodeSigningFailure),
    /// The accepted socket's retained peer changed across provenance sampling.
    #[error("macOS peer process identity changed during collection")]
    ProcessIdentityChanged,
    /// Canonical evidence could not be converted into a subject fingerprint.
    #[error(transparent)]
    Fingerprint(#[from] IdentityError),
}

/// Collects a reusable, kernel-authenticated identity from an accepted Unix
/// domain socket.
///
/// The direct peer always receives process scope. A separate origin-application
/// candidate is added only after bounded ancestry and Security.framework code
/// validation. Failure to validate the direct live code is fatal and never
/// degrades to token-only identity because the accepted socket's audit token
/// can survive `exec`.
///
/// # Errors
///
/// Returns an error when audit-token provenance is unavailable, cannot be read
/// consistently, or belongs to another effective user. Callers must omit the
/// subject on error; they must not derive a replacement from PID/display data.
pub fn collect_accepted_unix_peer(fd: RawFd) -> Result<MacosPeerIdentity, MacosIdentityError> {
    let retained_socket = Arc::new(duplicate_socket(fd)?);
    let fd = retained_socket.as_raw_fd();
    let expected_uid = effective_uid();
    let token = read_peer_audit_token(fd)?;
    collect_from_audit_token(fd, token, expected_uid, retained_socket)
}

fn collect_from_audit_token(
    fd: RawFd,
    token: AuditToken,
    expected_uid: u32,
    retained_socket: Arc<OwnedFd>,
) -> Result<MacosPeerIdentity, MacosIdentityError> {
    let peer = read_peer_credentials(fd)?;
    let token_pid = token.pid()?;
    let token_pid_version = token.pid_version()?;
    let token_euid = token.effective_uid();
    if peer.pid != token_pid || peer.effective_uid != token_euid {
        return Err(MacosIdentityError::InvalidPeer(
            "audit token does not match effective socket credentials",
        ));
    }
    require_owner_peer(peer, expected_uid)?;

    let validated = validated_code_subject(&token).map_err(MacosIdentityError::CodeSigning)?;
    let application = collect_origin_application(peer.pid, expected_uid).unwrap_or_default();
    let terminal_session = collect_terminal_session(peer.pid, expected_uid).unwrap_or_default();
    let final_peer = read_peer_credentials(fd)?;
    let final_token = read_peer_audit_token(fd)?;
    if peer != final_peer || token != final_token {
        return Err(MacosIdentityError::ProcessIdentityChanged);
    }

    Ok(MacosPeerIdentity {
        pid: peer.pid,
        pid_version: token_pid_version,
        effective_uid: peer.effective_uid,
        subject: validated.subject,
        evidence_class: MacosEvidenceClass::AuditToken,
        code_signing_status: validated.code_signing_status,
        team_identifier: validated.team_identifier,
        signing_identifier: validated.signing_identifier,
        application,
        terminal_session,
        audit_token: token,
        retained_socket,
    })
}

fn duplicate_socket(fd: RawFd) -> Result<OwnedFd, MacosIdentityError> {
    // SAFETY: `fd` is borrowed for this synchronous operation. On success,
    // fcntl returns a distinct descriptor with close-on-exec already set.
    let duplicated = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if duplicated < 0 {
        return Err(io_error("fcntl(F_DUPFD_CLOEXEC)"));
    }
    // SAFETY: a successful F_DUPFD_CLOEXEC call returns a new descriptor owned
    // by the caller, and this is its sole conversion to OwnedFd.
    Ok(unsafe { OwnedFd::from_raw_fd(duplicated) })
}

fn retain_process_lifecycle(
    pid: u32,
) -> Result<Arc<MacosProcessLifecycleGuard>, MacosCodeSigningFailure> {
    // SAFETY: kqueue has no arguments and returns a newly owned descriptor.
    let kqueue = unsafe { libc::kqueue() };
    if kqueue < 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "kqueue failed: {}",
            io::Error::last_os_error(),
        )));
    }
    // SAFETY: successful kqueue returned one newly owned descriptor.
    let kqueue = unsafe { OwnedFd::from_raw_fd(kqueue) };
    // SAFETY: the retained descriptor is live and F_SETFD preserves ownership.
    let descriptor_flags = unsafe { libc::fcntl(kqueue.as_raw_fd(), libc::F_GETFD) };
    if descriptor_flags < 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "fcntl(kqueue, FD_CLOEXEC) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    // SAFETY: the descriptor remains live and the flags preserve every bit
    // returned by F_GETFD while adding close-on-exec.
    let set_flags = unsafe {
        libc::fcntl(
            kqueue.as_raw_fd(),
            libc::F_SETFD,
            descriptor_flags | libc::FD_CLOEXEC,
        )
    };
    if set_flags < 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "fcntl(kqueue, FD_CLOEXEC) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    let change = libc::kevent {
        ident: pid as libc::uintptr_t,
        filter: libc::EVFILT_PROC,
        flags: libc::EV_ADD | libc::EV_CLEAR,
        fflags: libc::NOTE_EXIT | libc::NOTE_EXEC,
        data: 0,
        udata: ptr::null_mut(),
    };
    // SAFETY: change points to one initialized kevent and no output list or
    // timeout is requested for this registration call.
    let result = unsafe {
        libc::kevent(
            kqueue.as_raw_fd(),
            &change,
            1,
            ptr::null_mut(),
            0,
            ptr::null(),
        )
    };
    if result < 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "kevent(EVFILT_PROC, {pid}) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    let guard = Arc::new(MacosProcessLifecycleGuard { kqueue });
    guard
        .ensure_live()
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
    Ok(guard)
}

fn verified_subject(
    domain: &[u8],
    canonical_evidence: &[u8],
    maximum_scope: VerifiedSubjectKind,
) -> Result<VerifiedSubject, IdentityError> {
    let fingerprint = SubjectFingerprint::derive(domain, canonical_evidence)?;
    Ok(VerifiedSubject::new(fingerprint, maximum_scope))
}

struct ValidatedCodeSubject {
    subject: VerifiedSubject,
    code_signing_status: MacosCodeSigningStatus,
    team_identifier: Option<String>,
    signing_identifier: Option<String>,
}

fn validated_code_subject(
    token: &AuditToken,
) -> Result<ValidatedCodeSubject, MacosCodeSigningFailure> {
    let code = copy_code_for_audit_token(token)?;
    let requirement = copy_designated_requirement(code.as_ptr())?;

    // Validate the live dynamic code against the exact requirement whose bytes
    // become identity material. This prevents an attacker from merely embedding
    // another app's requirement without satisfying its signing constraints.
    // SAFETY: `code` and `requirement` own valid Security.framework objects;
    // both remain alive for this synchronous call. The copied designated
    // requirement is deliberately supplied as the validation policy.
    let status = unsafe { SecCodeCheckValidity(code.as_ptr(), 0, requirement.as_ptr()) };
    require_status("SecCodeCheckValidity", status)?;
    strictly_validate_static_code(code.as_ptr(), requirement.as_ptr())?;

    let requirement_data = copy_requirement_data(requirement.as_ptr())?;
    let signing_info = copy_signing_information(code.as_ptr())?;
    let cdhash = dictionary_data(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoUnique },
        "code-directory hash",
    )?;
    let team_identifier = dictionary_optional_string(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoTeamIdentifier },
        "team identifier",
    )?;
    let signing_identifier = dictionary_optional_string(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoIdentifier },
        "signing identifier",
    )?;
    let is_apple_platform_code = dictionary_value(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoPlatformIdentifier },
        "platform identifier",
    )?
    .is_some();

    let canonical = canonical_validated_process_evidence(token, &requirement_data, &cdhash)?;
    let subject = verified_subject(
        AUDIT_TOKEN_EVIDENCE_DOMAIN,
        &canonical,
        VerifiedSubjectKind::Process,
    )
    .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
    let code_signing_status = if !is_apple_platform_code {
        match (&team_identifier, &signing_identifier) {
            (Some(team_identifier), Some(signing_identifier)) => {
                MacosCodeSigningStatus::Verified(MacosCodeSigningIdentity {
                    team_identifier: team_identifier.clone(),
                    signing_identifier: signing_identifier.clone(),
                })
            }
            _ => MacosCodeSigningStatus::Degraded(MacosCodeSigningFailure::new(
                "validated code has no non-empty team identifier and signing identifier",
            )),
        }
    } else {
        MacosCodeSigningStatus::Degraded(MacosCodeSigningFailure::new(
            "validated Apple platform code uses process scope; its owning app is collected separately",
        ))
    };
    Ok(ValidatedCodeSubject {
        subject,
        code_signing_status,
        team_identifier,
        signing_identifier,
    })
}

fn canonical_validated_process_evidence(
    token: &AuditToken,
    requirement_data: &[u8],
    cdhash: &[u8],
) -> Result<Vec<u8>, MacosCodeSigningFailure> {
    if requirement_data.is_empty() {
        return Err(MacosCodeSigningFailure::new(
            "validated process identity is missing designated requirement evidence",
        ));
    }
    if cdhash.is_empty() || cdhash.len() > MAX_CDHASH_BYTES {
        return Err(MacosCodeSigningFailure::new(
            "validated process identity has an invalid CDHash",
        ));
    }
    let token = token.canonical_bytes();
    let mut output = Vec::with_capacity(12 + token.len() + requirement_data.len() + cdhash.len());
    append_len_prefixed(&mut output, &token)?;
    append_len_prefixed(&mut output, requirement_data)?;
    append_len_prefixed(&mut output, cdhash)?;
    Ok(output)
}

fn append_len_prefixed(output: &mut Vec<u8>, value: &[u8]) -> Result<(), MacosCodeSigningFailure> {
    let length = u32::try_from(value.len())
        .map_err(|_| MacosCodeSigningFailure::new("code-signing evidence is too large"))?;
    output.extend_from_slice(&length.to_be_bytes());
    output.extend_from_slice(value);
    Ok(())
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct MacosProcessSnapshot {
    pid: u32,
    parent_pid: u32,
    session_id: u32,
    controlling_tty: u64,
    effective_uid: u32,
    start_seconds: u64,
    start_microseconds: u64,
    executable_path: String,
    bundle_path: String,
}

fn collect_terminal_session(
    peer_pid: u32,
    expected_uid: u32,
) -> Result<Option<MacosTerminalSessionIdentity>, MacosCodeSigningFailure> {
    let peer = read_process_snapshot(peer_pid)?;
    if peer.effective_uid != expected_uid
        || peer.session_id <= 1
        || peer.controlling_tty == 0
        || peer.controlling_tty == u64::from(u32::MAX)
    {
        return Ok(None);
    }
    let first_chain = process_chain_to(peer_pid, peer.session_id, expected_uid)?;
    let leader = first_chain
        .last()
        .filter(|snapshot| snapshot.pid == peer.session_id)
        .cloned()
        .ok_or_else(|| MacosCodeSigningFailure::new("session leader is not a bounded ancestor"))?;
    if leader.session_id != peer.session_id || leader.controlling_tty != peer.controlling_tty {
        return Ok(None);
    }
    let lifecycle = retain_process_lifecycle(leader.pid)?;
    let confirmed_chain = process_chain_to(peer_pid, peer.session_id, expected_uid)?;
    if first_chain != confirmed_chain {
        return Err(MacosCodeSigningFailure::new(
            "terminal session ancestry changed during collection",
        ));
    }
    lifecycle
        .ensure_live()
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;

    let subject = terminal_session_subject_from_leader(&leader)?;
    Ok(Some(MacosTerminalSessionIdentity {
        subject,
        session_id: peer.session_id,
        controlling_tty: peer.controlling_tty,
        leader,
        accepted_chain: first_chain.into_boxed_slice(),
        lifecycle,
    }))
}

fn terminal_session_subject_from_leader(
    leader: &MacosProcessSnapshot,
) -> Result<VerifiedSubject, MacosCodeSigningFailure> {
    let mut canonical = Vec::with_capacity(128 + leader.executable_path.len());
    canonical.extend_from_slice(&leader.pid.to_be_bytes());
    canonical.extend_from_slice(&leader.parent_pid.to_be_bytes());
    canonical.extend_from_slice(&leader.session_id.to_be_bytes());
    canonical.extend_from_slice(&leader.controlling_tty.to_be_bytes());
    canonical.extend_from_slice(&leader.effective_uid.to_be_bytes());
    canonical.extend_from_slice(&leader.start_seconds.to_be_bytes());
    canonical.extend_from_slice(&leader.start_microseconds.to_be_bytes());
    append_len_prefixed(&mut canonical, leader.executable_path.as_bytes())?;
    let fingerprint = SubjectFingerprint::derive(TERMINAL_SESSION_EVIDENCE_DOMAIN, &canonical)
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
    Ok(VerifiedSubject::new(
        fingerprint,
        VerifiedSubjectKind::TerminalSession,
    ))
}

fn process_chain_to(
    leaf_pid: u32,
    target_pid: u32,
    expected_uid: u32,
) -> Result<Vec<MacosProcessSnapshot>, MacosCodeSigningFailure> {
    let mut chain = Vec::with_capacity(MAX_ANCESTRY_DEPTH);
    let mut current_pid = leaf_pid;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        let snapshot = read_process_snapshot(current_pid)?;
        if snapshot.effective_uid != expected_uid {
            break;
        }
        let parent_pid = snapshot.parent_pid;
        chain.push(snapshot);
        if current_pid == target_pid {
            return Ok(chain);
        }
        if parent_pid <= 1 || parent_pid == current_pid {
            break;
        }
        current_pid = parent_pid;
    }
    Err(MacosCodeSigningFailure::new(
        "target process is not a bounded same-user ancestor",
    ))
}

fn collect_origin_application(
    peer_pid: u32,
    expected_uid: u32,
) -> Result<Option<MacosApplicationIdentity>, MacosCodeSigningFailure> {
    let Some(first) = find_origin_application(peer_pid, expected_uid)? else {
        return Ok(None);
    };
    let lifecycle = retain_process_lifecycle(first.pid)?;
    let code = copy_code_for_pid(first.pid)?;
    let requirement = copy_designated_requirement(code.as_ptr())?;
    // SAFETY: retained Security.framework objects are valid for this call.
    let status = unsafe { SecCodeCheckValidity(code.as_ptr(), 0, requirement.as_ptr()) };
    require_status("SecCodeCheckValidity(application)", status)?;
    strictly_validate_static_code(code.as_ptr(), requirement.as_ptr())?;

    let requirement_data = copy_requirement_data(requirement.as_ptr())?;
    let signing_info = copy_signing_information(code.as_ptr())?;
    let cdhash = dictionary_data(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoUnique },
        "application code-directory hash",
    )?;
    let team_identifier = dictionary_optional_string(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoTeamIdentifier },
        "application team identifier",
    )?;
    let signing_identifier = dictionary_optional_string(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoIdentifier },
        "application signing identifier",
    )?;
    let is_apple_platform_code = dictionary_value(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoPlatformIdentifier },
        "application platform identifier",
    )?
    .is_some();

    let confirmed = find_origin_application(peer_pid, expected_uid)?
        .ok_or_else(|| MacosCodeSigningFailure::new("origin application disappeared"))?;
    if first != confirmed {
        return Err(MacosCodeSigningFailure::new(
            "origin application changed during validation",
        ));
    }
    lifecycle
        .ensure_live()
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;

    let subject = if let Some(signing_identifier) = signing_identifier.as_deref() {
        if is_apple_platform_code || team_identifier.is_some() {
            let canonical = canonical_application_code_signing_evidence(
                is_apple_platform_code,
                team_identifier.as_deref(),
                signing_identifier,
                &requirement_data,
            )?;
            verified_subject(
                APPLICATION_CODE_SIGNING_EVIDENCE_DOMAIN,
                &canonical,
                VerifiedSubjectKind::StableApplication,
            )
            .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?
        } else {
            application_instance_subject(&first, &requirement_data, &cdhash)?
        }
    } else {
        application_instance_subject(&first, &requirement_data, &cdhash)?
    };

    let display_name = std::path::Path::new(&first.bundle_path)
        .file_stem()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("Application")
        .to_owned();
    Ok(Some(MacosApplicationIdentity {
        pid: first.pid,
        subject,
        display_name,
        bundle_path: first.bundle_path,
        team_identifier,
        signing_identifier,
        lifecycle,
    }))
}

fn find_origin_application(
    peer_pid: u32,
    expected_uid: u32,
) -> Result<Option<MacosProcessSnapshot>, MacosCodeSigningFailure> {
    let mut current_pid = peer_pid;
    let mut selected_bundle: Option<String> = None;
    let mut selected = None;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        let snapshot = read_process_snapshot(current_pid)?;
        if snapshot.effective_uid != expected_uid {
            break;
        }
        if snapshot.bundle_path.is_empty() {
            if selected_bundle.is_some() {
                break;
            }
        } else {
            match selected_bundle.as_deref() {
                None => {
                    selected_bundle = Some(snapshot.bundle_path.clone());
                    selected = Some(snapshot.clone());
                }
                Some(bundle) if bundle == snapshot.bundle_path => {
                    // Prefer the highest process in the same outer bundle so
                    // helpers resolve to their owning app's main process.
                    selected = Some(snapshot.clone());
                }
                Some(_) => break,
            }
        }
        if snapshot.parent_pid <= 1 || snapshot.parent_pid == snapshot.pid {
            break;
        }
        current_pid = snapshot.parent_pid;
    }
    Ok(selected)
}

fn read_process_snapshot(pid: u32) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure> {
    let mut info = MaybeUninit::<libc::proc_bsdinfo>::zeroed();
    // SAFETY: `info` is writable storage of the advertised proc_bsdinfo size.
    let size = unsafe {
        libc::proc_pidinfo(
            pid as c_int,
            libc::PROC_PIDTBSDINFO,
            0,
            info.as_mut_ptr().cast(),
            size_of::<libc::proc_bsdinfo>() as c_int,
        )
    };
    if size != size_of::<libc::proc_bsdinfo>() as c_int {
        return Err(MacosCodeSigningFailure::new(format!(
            "proc_pidinfo({pid}) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    // SAFETY: proc_pidinfo returned the complete structure size.
    let info = unsafe { info.assume_init() };
    if info.pbi_pid == 0 || info.pbi_pid != pid {
        return Err(MacosCodeSigningFailure::new(
            "proc_pidinfo returned inconsistent PID",
        ));
    }
    let executable_path = process_path(pid)?;
    let bundle_path = outer_app_bundle_path(&executable_path).unwrap_or_default();
    // SAFETY: getsid reads the kernel session associated with this numeric PID.
    // The surrounding repeated proc snapshot detects PID reuse/races.
    let session_id = unsafe { libc::getsid(pid as libc::pid_t) };
    if session_id <= 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "getsid({pid}) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    Ok(MacosProcessSnapshot {
        pid,
        parent_pid: info.pbi_ppid,
        session_id: u32::try_from(session_id)
            .map_err(|_| MacosCodeSigningFailure::new("invalid session ID"))?,
        controlling_tty: u64::from(info.e_tdev),
        effective_uid: info.pbi_uid,
        start_seconds: info.pbi_start_tvsec,
        start_microseconds: info.pbi_start_tvusec,
        executable_path,
        bundle_path,
    })
}

fn process_path(pid: u32) -> Result<String, MacosCodeSigningFailure> {
    let mut buffer = vec![0u8; libc::PROC_PIDPATHINFO_MAXSIZE as usize];
    // SAFETY: the buffer is writable for its reported capacity.
    let length = unsafe {
        libc::proc_pidpath(
            pid as c_int,
            buffer.as_mut_ptr().cast(),
            buffer.len() as u32,
        )
    };
    if length <= 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "proc_pidpath({pid}) failed: {}",
            io::Error::last_os_error(),
        )));
    }
    let end = buffer
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(buffer.len());
    let path = std::str::from_utf8(&buffer[..end])
        .map_err(|_| MacosCodeSigningFailure::new("process path is not valid UTF-8"))?;
    if path.is_empty() || path.contains('\0') {
        return Err(MacosCodeSigningFailure::new("process path is empty"));
    }
    Ok(path.to_owned())
}

fn outer_app_bundle_path(executable_path: &str) -> Option<String> {
    std::path::Path::new(executable_path)
        .ancestors()
        .filter(|ancestor| {
            ancestor
                .file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with(".app"))
        })
        .last()
        .map(|path| path.to_string_lossy().into_owned())
}

fn canonical_application_code_signing_evidence(
    is_apple_platform_code: bool,
    team_identifier: Option<&str>,
    signing_identifier: &str,
    requirement_data: &[u8],
) -> Result<Vec<u8>, MacosCodeSigningFailure> {
    if signing_identifier.is_empty() || requirement_data.is_empty() {
        return Err(MacosCodeSigningFailure::new(
            "application signature identity is incomplete",
        ));
    }
    if !is_apple_platform_code && team_identifier.is_none() {
        return Err(MacosCodeSigningFailure::new(
            "third-party application has no team identifier",
        ));
    }
    let mut output = Vec::with_capacity(
        13 + team_identifier.map_or(0, str::len)
            + signing_identifier.len()
            + requirement_data.len(),
    );
    output.push(u8::from(is_apple_platform_code));
    append_len_prefixed(&mut output, team_identifier.unwrap_or_default().as_bytes())?;
    append_len_prefixed(&mut output, signing_identifier.as_bytes())?;
    append_len_prefixed(&mut output, requirement_data)?;
    Ok(output)
}

fn application_instance_subject(
    snapshot: &MacosProcessSnapshot,
    requirement_data: &[u8],
    cdhash: &[u8],
) -> Result<VerifiedSubject, MacosCodeSigningFailure> {
    if cdhash.is_empty() || cdhash.len() > MAX_CDHASH_BYTES {
        return Err(MacosCodeSigningFailure::new(
            "application code-directory hash is invalid",
        ));
    }
    let mut canonical = Vec::with_capacity(
        32 + snapshot.executable_path.len()
            + snapshot.bundle_path.len()
            + requirement_data.len()
            + cdhash.len(),
    );
    canonical.extend_from_slice(&snapshot.pid.to_be_bytes());
    canonical.extend_from_slice(&snapshot.parent_pid.to_be_bytes());
    canonical.extend_from_slice(&snapshot.effective_uid.to_be_bytes());
    canonical.extend_from_slice(&snapshot.start_seconds.to_be_bytes());
    canonical.extend_from_slice(&snapshot.start_microseconds.to_be_bytes());
    append_len_prefixed(&mut canonical, snapshot.executable_path.as_bytes())?;
    append_len_prefixed(&mut canonical, snapshot.bundle_path.as_bytes())?;
    append_len_prefixed(&mut canonical, requirement_data)?;
    append_len_prefixed(&mut canonical, cdhash)?;
    verified_subject(
        APPLICATION_INSTANCE_EVIDENCE_DOMAIN,
        &canonical,
        VerifiedSubjectKind::ApplicationInstance,
    )
    .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PeerCredentials {
    pid: u32,
    effective_uid: u32,
    effective_gid: u32,
}

fn read_peer_credentials(fd: RawFd) -> Result<PeerCredentials, MacosIdentityError> {
    let mut pid: libc::pid_t = 0;
    let mut length = size_of::<libc::pid_t>() as libc::socklen_t;
    // SAFETY: `pid` and `length` point to writable storage of the exact
    // advertised sizes, and `fd` is borrowed for this synchronous syscall.
    let result = unsafe {
        libc::getsockopt(
            fd,
            SOL_LOCAL,
            LOCAL_PEEREPID,
            ptr::from_mut(&mut pid).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(io_error("getsockopt(LOCAL_PEEREPID)"));
    }
    if length as usize != size_of::<libc::pid_t>() || pid <= 0 {
        return Err(MacosIdentityError::InvalidPeer(
            "LOCAL_PEEREPID returned an invalid PID",
        ));
    }

    let mut effective_uid: libc::uid_t = 0;
    let mut effective_gid: libc::gid_t = 0;
    // SAFETY: the uid/gid pointers are valid writable values and `fd` is an
    // accepted local-domain socket for the duration of this call.
    let result = unsafe { libc::getpeereid(fd, &mut effective_uid, &mut effective_gid) };
    if result != 0 {
        return Err(io_error("getpeereid"));
    }

    Ok(PeerCredentials {
        pid: u32::try_from(pid)
            .map_err(|_| MacosIdentityError::InvalidPeer("peer PID does not fit into u32"))?,
        effective_uid,
        effective_gid,
    })
}

fn require_owner_peer(peer: PeerCredentials, expected_uid: u32) -> Result<(), MacosIdentityError> {
    if peer.effective_uid != expected_uid {
        return Err(MacosIdentityError::InvalidPeer(
            "peer effective UID differs from the agent effective UID",
        ));
    }
    Ok(())
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct AuditToken {
    values: [u32; AUDIT_TOKEN_VALUE_COUNT],
}

impl AuditToken {
    fn pid(self) -> Result<u32, MacosIdentityError> {
        // SAFETY: this value was initialized by LOCAL_PEERTOKEN and is passed
        // by value using the public audit_token_t ABI.
        let pid = unsafe { audit_token_to_pid(self) };
        if pid <= 0 {
            return Err(MacosIdentityError::InvalidPeer(
                "audit token contains an invalid PID",
            ));
        }
        u32::try_from(pid)
            .map_err(|_| MacosIdentityError::InvalidPeer("audit-token PID does not fit u32"))
    }

    fn effective_uid(self) -> u32 {
        // SAFETY: this value was initialized by LOCAL_PEERTOKEN and is passed
        // by value using the public audit_token_t ABI.
        unsafe { audit_token_to_euid(self) }
    }

    fn pid_version(self) -> Result<u32, MacosIdentityError> {
        // SAFETY: this value was initialized by LOCAL_PEERTOKEN and is passed
        // by value using the public audit_token_t ABI.
        let version = unsafe { audit_token_to_pidversion(self) };
        u32::try_from(version).map_err(|_| {
            MacosIdentityError::InvalidPeer("audit token contains an invalid PID version")
        })
    }

    fn canonical_bytes(self) -> [u8; AUDIT_TOKEN_VALUE_COUNT * size_of::<u32>()] {
        let mut output = [0u8; AUDIT_TOKEN_VALUE_COUNT * size_of::<u32>()];
        for (index, value) in self.values.into_iter().enumerate() {
            let start = index * size_of::<u32>();
            output[start..start + size_of::<u32>()].copy_from_slice(&value.to_be_bytes());
        }
        output
    }
}

fn read_peer_audit_token(fd: RawFd) -> Result<AuditToken, MacosIdentityError> {
    let mut token = MaybeUninit::<AuditToken>::uninit();
    let mut length = size_of::<AuditToken>() as libc::socklen_t;
    // SAFETY: `token` has exactly audit_token_t's public 8-u32 ABI and
    // `length` advertises that full storage to the synchronous syscall.
    let result = unsafe {
        libc::getsockopt(
            fd,
            SOL_LOCAL,
            LOCAL_PEERTOKEN,
            token.as_mut_ptr().cast(),
            &mut length,
        )
    };
    if result != 0 {
        let error = io::Error::last_os_error();
        return match error.raw_os_error() {
            Some(libc::ENOPROTOOPT) | Some(libc::EOPNOTSUPP) => {
                Err(MacosIdentityError::AuditTokenUnavailable { source: error })
            }
            _ => Err(MacosIdentityError::Io {
                operation: "getsockopt(LOCAL_PEERTOKEN)",
                source: error,
            }),
        };
    }
    if length as usize != size_of::<AuditToken>() {
        return Err(MacosIdentityError::InvalidPeer(
            "LOCAL_PEERTOKEN returned an unexpected token length",
        ));
    }

    // SAFETY: getsockopt succeeded and reported writing the complete
    // audit_token_t-sized object.
    let token = unsafe { token.assume_init() };
    if token.values.iter().all(|value| *value == 0) {
        return Err(MacosIdentityError::InvalidPeer(
            "LOCAL_PEERTOKEN returned an empty token",
        ));
    }
    // Parse PID once here so malformed tokens fail before Security.framework.
    token.pid()?;
    token.pid_version()?;
    Ok(token)
}

fn effective_uid() -> u32 {
    // SAFETY: geteuid has no preconditions and returns a plain uid_t.
    unsafe { libc::geteuid() }
}

fn io_error(operation: &'static str) -> MacosIdentityError {
    MacosIdentityError::Io {
        operation,
        source: io::Error::last_os_error(),
    }
}

struct OwnedCf(NonNull<c_void>);

impl OwnedCf {
    fn from_created(
        raw: CfTypeRef,
        operation: &'static str,
    ) -> Result<Self, MacosCodeSigningFailure> {
        let raw = NonNull::new(raw.cast_mut()).ok_or_else(|| {
            MacosCodeSigningFailure::new(format!("{operation} returned a null object"))
        })?;
        Ok(Self(raw))
    }

    fn as_ptr(&self) -> CfTypeRef {
        self.0.as_ptr().cast_const()
    }
}

impl Drop for OwnedCf {
    fn drop(&mut self) {
        // SAFETY: every OwnedCf is constructed only from a Create/Copy result
        // and therefore owns exactly one Core Foundation retain.
        unsafe {
            CFRelease(self.as_ptr());
        }
    }
}

fn copy_code_for_audit_token(token: &AuditToken) -> Result<OwnedCf, MacosCodeSigningFailure> {
    // SAFETY: `token` points to a fully initialized audit_token_t ABI value;
    // CFDataCreate copies the bytes before returning.
    let token_data = unsafe {
        CFDataCreate(
            ptr::null(),
            ptr::from_ref(token).cast::<u8>(),
            size_of::<AuditToken>() as CfIndex,
        )
    };
    let token_data = OwnedCf::from_created(token_data, "CFDataCreate(audit token)")?;

    // SAFETY: Security.framework exports this immortal CFString key.
    let key = unsafe { kSecGuestAttributeAudit };
    let keys = [key];
    let values = [token_data.as_ptr()];
    // SAFETY: the one-element key/value arrays remain alive for the call.
    // Null callbacks are intentional: the dictionary is synchronous and does
    // not own the immortal key or token_data retained in this frame.
    let attributes = unsafe {
        CFDictionaryCreate(
            ptr::null(),
            keys.as_ptr(),
            values.as_ptr(),
            1,
            ptr::null(),
            ptr::null(),
        )
    };
    let attributes = OwnedCf::from_created(attributes, "CFDictionaryCreate(audit token)")?;

    let mut code: SecCodeRef = ptr::null();
    // SAFETY: `attributes` is a live CFDictionary containing the exact socket
    // audit token; `code` points to writable retained output storage.
    let status =
        unsafe { SecCodeCopyGuestWithAttributes(ptr::null(), attributes.as_ptr(), 0, &mut code) };
    require_status("SecCodeCopyGuestWithAttributes", status)?;
    OwnedCf::from_created(code, "SecCodeCopyGuestWithAttributes")
}

fn copy_code_for_pid(pid: u32) -> Result<OwnedCf, MacosCodeSigningFailure> {
    let pid = i32::try_from(pid)
        .map_err(|_| MacosCodeSigningFailure::new("application PID does not fit pid_t"))?;
    // SAFETY: `pid` points to one initialized SInt32 copied synchronously by
    // CFNumberCreate.
    let pid_number = unsafe {
        CFNumberCreate(
            ptr::null(),
            K_CF_NUMBER_SINT32_TYPE,
            ptr::from_ref(&pid).cast(),
        )
    };
    let pid_number = OwnedCf::from_created(pid_number, "CFNumberCreate(application PID)")?;
    // SAFETY: Security.framework exports this immortal CFString key.
    let key = unsafe { kSecGuestAttributePid };
    let keys = [key];
    let values = [pid_number.as_ptr()];
    // SAFETY: the key/value arrays and pid_number live through this call.
    let attributes = unsafe {
        CFDictionaryCreate(
            ptr::null(),
            keys.as_ptr(),
            values.as_ptr(),
            1,
            ptr::null(),
            ptr::null(),
        )
    };
    let attributes = OwnedCf::from_created(attributes, "CFDictionaryCreate(application PID)")?;
    let mut code: SecCodeRef = ptr::null();
    // SAFETY: attributes is a live dictionary and code is writable output.
    let status =
        unsafe { SecCodeCopyGuestWithAttributes(ptr::null(), attributes.as_ptr(), 0, &mut code) };
    require_status("SecCodeCopyGuestWithAttributes(application PID)", status)?;
    OwnedCf::from_created(code, "SecCodeCopyGuestWithAttributes(application PID)")
}

fn copy_designated_requirement(code: SecCodeRef) -> Result<OwnedCf, MacosCodeSigningFailure> {
    let mut requirement: SecRequirementRef = ptr::null();
    // SAFETY: `code` is a retained live SecCodeRef and `requirement` points to
    // writable retained output storage.
    let status = unsafe { SecCodeCopyDesignatedRequirement(code, 0, &mut requirement) };
    require_status("SecCodeCopyDesignatedRequirement", status)?;
    OwnedCf::from_created(requirement, "SecCodeCopyDesignatedRequirement")
}

fn strictly_validate_static_code(
    code: SecCodeRef,
    requirement: SecRequirementRef,
) -> Result<(), MacosCodeSigningFailure> {
    let mut static_code: SecCodeRef = ptr::null();
    // SAFETY: `code` is a retained live SecCodeRef and `static_code` points to
    // writable retained output storage.
    let status = unsafe { SecCodeCopyStaticCode(code, 0, &mut static_code) };
    require_status("SecCodeCopyStaticCode", status)?;
    let static_code = OwnedCf::from_created(static_code, "SecCodeCopyStaticCode")?;

    let flags = K_SEC_CS_CHECK_ALL_ARCHITECTURES | K_SEC_CS_STRICT_VALIDATE;
    // SAFETY: `static_code` and `requirement` are live Security.framework
    // objects retained for this synchronous strict validation call.
    let status = unsafe { SecStaticCodeCheckValidity(static_code.as_ptr(), flags, requirement) };
    require_status("SecStaticCodeCheckValidity", status)
}

fn copy_requirement_data(
    requirement: SecRequirementRef,
) -> Result<Vec<u8>, MacosCodeSigningFailure> {
    let mut data: CfDataRef = ptr::null();
    // SAFETY: `requirement` is a retained SecRequirementRef and `data` points
    // to writable retained output storage.
    let status = unsafe { SecRequirementCopyData(requirement, 0, &mut data) };
    require_status("SecRequirementCopyData", status)?;
    let data = OwnedCf::from_created(data, "SecRequirementCopyData")?;
    copy_cf_data(data.as_ptr(), "designated requirement")
}

fn copy_signing_information(code: SecCodeRef) -> Result<OwnedCf, MacosCodeSigningFailure> {
    let mut information: CfDictionaryRef = ptr::null();
    // SAFETY: `code` is a retained live SecCodeRef and `information` points to
    // writable retained output storage.
    let status = unsafe {
        SecCodeCopySigningInformation(code, K_SEC_CS_SIGNING_INFORMATION, &mut information)
    };
    require_status("SecCodeCopySigningInformation", status)?;
    OwnedCf::from_created(information, "SecCodeCopySigningInformation")
}

fn dictionary_data(
    dictionary: CfDictionaryRef,
    key: CfStringRef,
    field: &'static str,
) -> Result<Vec<u8>, MacosCodeSigningFailure> {
    let value = dictionary_value(dictionary, key, field)?
        .ok_or_else(|| MacosCodeSigningFailure::new(format!("code signature has no {field}")))?;
    copy_cf_data(value, field)
}

fn copy_cf_data(data: CfTypeRef, field: &'static str) -> Result<Vec<u8>, MacosCodeSigningFailure> {
    // SAFETY: `data` is a live Core Foundation object.
    let data_type = unsafe { CFGetTypeID(data) };
    // SAFETY: CFDataGetTypeID has no preconditions.
    let expected_type = unsafe { CFDataGetTypeID() };
    if data_type != expected_type {
        return Err(MacosCodeSigningFailure::new(format!(
            "{field} is not CFData",
        )));
    }

    // SAFETY: the runtime type check above established a valid CFDataRef.
    let length = unsafe { CFDataGetLength(data) };
    if length <= 0 || length as usize > MAX_DESIGNATED_REQUIREMENT_BYTES {
        return Err(MacosCodeSigningFailure::new(format!(
            "{field} has invalid length {length}",
        )));
    }
    // SAFETY: the runtime type check and positive length establish a valid
    // non-null byte range owned by `data` for this copy.
    let bytes = unsafe { CFDataGetBytePtr(data) };
    if bytes.is_null() {
        return Err(MacosCodeSigningFailure::new(format!(
            "{field} returned a null byte pointer",
        )));
    }
    // SAFETY: CFData owns at least `length` bytes at `bytes` until return.
    let bytes = unsafe { std::slice::from_raw_parts(bytes, length as usize) };
    Ok(bytes.to_vec())
}

fn dictionary_value(
    dictionary: CfDictionaryRef,
    key: CfStringRef,
    field: &'static str,
) -> Result<Option<CfTypeRef>, MacosCodeSigningFailure> {
    if key.is_null() {
        return Err(MacosCodeSigningFailure::new(format!(
            "Security.framework returned a null {field} key",
        )));
    }

    let mut value: CfTypeRef = ptr::null();
    // SAFETY: `dictionary` and the immortal `key` are live CF objects; `value`
    // points to writable borrowed output storage.
    let found = unsafe { CFDictionaryGetValueIfPresent(dictionary, key, &mut value) };
    if found == 0 || value.is_null() {
        return Ok(None);
    }
    Ok(Some(value))
}

fn dictionary_optional_string(
    dictionary: CfDictionaryRef,
    key: CfStringRef,
    field: &'static str,
) -> Result<Option<String>, MacosCodeSigningFailure> {
    let Some(value) = dictionary_value(dictionary, key, field)? else {
        return Ok(None);
    };
    // SAFETY: `value` is borrowed from the retained signing dictionary.
    let value_type = unsafe { CFGetTypeID(value) };
    // SAFETY: CFStringGetTypeID has no preconditions.
    let expected_type = unsafe { CFStringGetTypeID() };
    if value_type != expected_type {
        return Err(MacosCodeSigningFailure::new(format!(
            "code signature {field} is not CFString",
        )));
    }

    // Teamless/ad-hoc signatures can expose an empty string. Absence is a
    // valid reason to cap the subject at Process, not a code-validation error.
    // SAFETY: the runtime type check above established a valid CFStringRef.
    if unsafe { CFStringGetLength(value) } == 0 {
        return Ok(None);
    }
    cf_string_to_rust(value, field).map(Some)
}

fn cf_string_to_rust(
    value: CfStringRef,
    field: &'static str,
) -> Result<String, MacosCodeSigningFailure> {
    // SAFETY: the caller established that `value` is a valid CFStringRef.
    let character_count = unsafe { CFStringGetLength(value) };
    if character_count <= 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "code signature {field} is empty",
        )));
    }
    for index in 0..character_count {
        // SAFETY: `index` is within the character count returned by this same
        // valid CFStringRef.
        if unsafe { CFStringGetCharacterAtIndex(value, index) } == 0 {
            return Err(MacosCodeSigningFailure::new(format!(
                "code signature {field} contains an embedded NUL",
            )));
        }
    }
    // SAFETY: `character_count` came from the same valid CFStringRef.
    let maximum =
        unsafe { CFStringGetMaximumSizeForEncoding(character_count, K_CF_STRING_ENCODING_UTF8) };
    if maximum < 0 || maximum as usize >= MAX_SIGNING_IDENTIFIER_BYTES {
        return Err(MacosCodeSigningFailure::new(format!(
            "code signature {field} is too large",
        )));
    }
    let capacity = maximum as usize + 1;
    let mut buffer = vec![0u8; capacity];
    // SAFETY: `buffer` is writable for `capacity` bytes and the requested UTF-8
    // encoding preserves a trailing NUL on success.
    let converted = unsafe {
        CFStringGetCString(
            value,
            buffer.as_mut_ptr().cast::<c_char>(),
            capacity as CfIndex,
            K_CF_STRING_ENCODING_UTF8,
        )
    };
    if converted == 0 {
        return Err(MacosCodeSigningFailure::new(format!(
            "code signature {field} is not valid UTF-8",
        )));
    }
    let nul = buffer.iter().position(|byte| *byte == 0).ok_or_else(|| {
        MacosCodeSigningFailure::new(format!("code signature {field} is not NUL-terminated"))
    })?;
    let value = std::str::from_utf8(&buffer[..nul]).map_err(|_| {
        MacosCodeSigningFailure::new(format!("code signature {field} is not valid UTF-8"))
    })?;
    if value.is_empty() {
        return Err(MacosCodeSigningFailure::new(format!(
            "code signature {field} is empty",
        )));
    }
    Ok(value.to_owned())
}

fn require_status(
    operation: &'static str,
    status: OsStatus,
) -> Result<(), MacosCodeSigningFailure> {
    if status == 0 {
        Ok(())
    } else {
        Err(MacosCodeSigningFailure::status(operation, status))
    }
}

#[link(name = "bsm")]
unsafe extern "C" {
    fn audit_token_to_euid(token: AuditToken) -> libc::uid_t;
    fn audit_token_to_pid(token: AuditToken) -> libc::pid_t;
    fn audit_token_to_pidversion(token: AuditToken) -> c_int;
}

#[link(name = "Security", kind = "framework")]
unsafe extern "C" {
    static kSecGuestAttributeAudit: CfStringRef;
    static kSecGuestAttributePid: CfStringRef;
    static kSecCodeInfoIdentifier: CfStringRef;
    static kSecCodeInfoPlatformIdentifier: CfStringRef;
    static kSecCodeInfoTeamIdentifier: CfStringRef;
    static kSecCodeInfoUnique: CfStringRef;

    fn SecCodeCopyGuestWithAttributes(
        host: SecCodeRef,
        attributes: CfDictionaryRef,
        flags: u32,
        guest: *mut SecCodeRef,
    ) -> OsStatus;
    fn SecCodeCheckValidity(
        code: SecCodeRef,
        flags: u32,
        requirement: SecRequirementRef,
    ) -> OsStatus;
    fn SecCodeCopyStaticCode(
        code: SecCodeRef,
        flags: u32,
        static_code: *mut SecCodeRef,
    ) -> OsStatus;
    fn SecStaticCodeCheckValidity(
        static_code: SecCodeRef,
        flags: u32,
        requirement: SecRequirementRef,
    ) -> OsStatus;
    fn SecCodeCopyDesignatedRequirement(
        code: SecCodeRef,
        flags: u32,
        requirement: *mut SecRequirementRef,
    ) -> OsStatus;
    fn SecRequirementCopyData(
        requirement: SecRequirementRef,
        flags: u32,
        data: *mut CfDataRef,
    ) -> OsStatus;
    fn SecCodeCopySigningInformation(
        code: SecCodeRef,
        flags: u32,
        information: *mut CfDictionaryRef,
    ) -> OsStatus;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFRelease(value: CfTypeRef);
    fn CFGetTypeID(value: CfTypeRef) -> CfTypeId;
    fn CFDataGetTypeID() -> CfTypeId;
    fn CFStringGetTypeID() -> CfTypeId;
    fn CFDataCreate(allocator: CfTypeRef, bytes: *const u8, length: CfIndex) -> CfDataRef;
    fn CFNumberCreate(
        allocator: CfTypeRef,
        number_type: CfIndex,
        value: *const c_void,
    ) -> CfTypeRef;
    fn CFDataGetLength(data: CfDataRef) -> CfIndex;
    fn CFDataGetBytePtr(data: CfDataRef) -> *const u8;
    fn CFDictionaryCreate(
        allocator: CfTypeRef,
        keys: *const CfTypeRef,
        values: *const CfTypeRef,
        count: CfIndex,
        key_callbacks: *const c_void,
        value_callbacks: *const c_void,
    ) -> CfDictionaryRef;
    fn CFDictionaryGetValueIfPresent(
        dictionary: CfDictionaryRef,
        key: CfTypeRef,
        value: *mut CfTypeRef,
    ) -> CfBoolean;
    fn CFStringGetLength(value: CfStringRef) -> CfIndex;
    fn CFStringGetCharacterAtIndex(value: CfStringRef, index: CfIndex) -> u16;
    fn CFStringGetMaximumSizeForEncoding(length: CfIndex, encoding: u32) -> CfIndex;
    fn CFStringGetCString(
        value: CfStringRef,
        buffer: *mut c_char,
        buffer_size: CfIndex,
        encoding: u32,
    ) -> CfBoolean;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;

    #[test]
    fn application_signing_recipe_binds_vendor_identifier_and_requirement() {
        let first = canonical_application_code_signing_evidence(
            false,
            Some("TEAM"),
            "com.example.app",
            b"requirement-a",
        )
        .expect("canonical evidence");
        let changed_team = canonical_application_code_signing_evidence(
            false,
            Some("OTHER"),
            "com.example.app",
            b"requirement-a",
        )
        .expect("canonical evidence");
        let changed_identifier = canonical_application_code_signing_evidence(
            false,
            Some("TEAM"),
            "com.example.other",
            b"requirement-a",
        )
        .expect("canonical evidence");
        let changed_requirement = canonical_application_code_signing_evidence(
            false,
            Some("TEAM"),
            "com.example.app",
            b"requirement-b",
        )
        .expect("canonical evidence");

        assert_ne!(first, changed_team);
        assert_ne!(first, changed_identifier);
        assert_ne!(first, changed_requirement);
    }

    #[test]
    fn stable_recipe_requires_team_for_third_party_but_accepts_apple_platform_code() {
        assert!(canonical_application_code_signing_evidence(
            false,
            None,
            "com.example.app",
            b"requirement"
        )
        .is_err());
        assert!(canonical_application_code_signing_evidence(
            true,
            None,
            "com.apple.Terminal",
            b"requirement"
        )
        .is_ok());
        assert!(canonical_application_code_signing_evidence(
            false,
            Some("TEAM"),
            "",
            b"requirement"
        )
        .is_err());
    }

    #[test]
    fn audit_token_canonical_encoding_includes_every_word() {
        let original = AuditToken {
            values: [1, 2, 3, 4, 5, 6, 7, 8],
        }
        .canonical_bytes();
        for index in 0..AUDIT_TOKEN_VALUE_COUNT {
            let mut changed = AuditToken {
                values: [1, 2, 3, 4, 5, 6, 7, 8],
            };
            changed.values[index] = changed.values[index].wrapping_add(1);
            assert_ne!(original, changed.canonical_bytes());
        }
    }

    #[test]
    fn validated_process_recipe_binds_token_requirement_and_cdhash() {
        let token = AuditToken {
            values: [1, 2, 3, 4, 5, 6, 7, 8],
        };
        let first = canonical_validated_process_evidence(&token, b"requirement-a", b"cdhash-a")
            .expect("canonical evidence");
        let mut changed_token = token;
        changed_token.values[7] = changed_token.values[7].wrapping_add(1);

        assert_ne!(
            first,
            canonical_validated_process_evidence(&changed_token, b"requirement-a", b"cdhash-a")
                .expect("canonical evidence"),
        );
        assert_ne!(
            first,
            canonical_validated_process_evidence(&token, b"requirement-b", b"cdhash-a")
                .expect("canonical evidence"),
        );
        assert_ne!(
            first,
            canonical_validated_process_evidence(&token, b"requirement-a", b"cdhash-b")
                .expect("canonical evidence"),
        );
        assert!(canonical_validated_process_evidence(
            &token,
            b"requirement-a",
            &[0; MAX_CDHASH_BYTES + 1],
        )
        .is_err());
    }

    #[test]
    fn retained_process_lifecycle_guard_is_live_and_close_on_exec() {
        let guard = retain_process_lifecycle(std::process::id()).expect("lifecycle guard");
        guard.ensure_live().expect("current process is live");
        // SAFETY: F_GETFD reads flags from the retained live kqueue descriptor.
        let flags = unsafe { libc::fcntl(guard.kqueue.as_raw_fd(), libc::F_GETFD) };
        assert!(flags >= 0);
        assert_ne!(flags & libc::FD_CLOEXEC, 0);
    }

    #[test]
    fn terminal_subject_depends_only_on_retained_leader_and_tty() {
        let leader = MacosProcessSnapshot {
            pid: 20,
            parent_pid: 10,
            session_id: 20,
            controlling_tty: 100,
            effective_uid: 501,
            start_seconds: 1_000,
            start_microseconds: 200,
            executable_path: "/bin/zsh".to_string(),
            bundle_path: String::new(),
        };
        let first = terminal_session_subject_from_leader(&leader).expect("terminal subject");
        // No child-process field participates in the recipe, so another child
        // of this retained leader produces the same subject.
        let another_child =
            terminal_session_subject_from_leader(&leader).expect("terminal subject");
        let mut changed_tty = leader.clone();
        changed_tty.controlling_tty += 1;
        let changed_tty =
            terminal_session_subject_from_leader(&changed_tty).expect("terminal subject");
        let mut changed_leader = leader;
        changed_leader.pid += 1;
        changed_leader.session_id += 1;
        let changed_leader =
            terminal_session_subject_from_leader(&changed_leader).expect("terminal subject");

        assert_eq!(first, another_child);
        assert_ne!(first, changed_tty);
        assert_ne!(first, changed_leader);
    }

    #[test]
    fn accepted_socket_collects_current_process_instance() {
        let (client, server) = UnixStream::pair().expect("Unix socket pair");
        let identity = collect_accepted_unix_peer(server.as_raw_fd()).expect("macOS peer identity");

        assert_eq!(identity.pid(), std::process::id());
        assert_eq!(
            identity.pid_version(),
            read_peer_audit_token(server.as_raw_fd())
                .expect("audit token")
                .pid_version()
                .expect("PID version"),
        );
        assert_eq!(identity.effective_uid(), effective_uid());
        assert_eq!(identity.evidence_class(), MacosEvidenceClass::AuditToken);
        assert_eq!(identity.subject().kind(), VerifiedSubjectKind::Process);

        drop(client);
    }

    #[test]
    fn process_subject_is_stable_across_connections_from_same_process() {
        let (first_client, first_server) = UnixStream::pair().expect("first Unix socket pair");
        let (second_client, second_server) = UnixStream::pair().expect("second Unix socket pair");

        let first = collect_accepted_unix_peer(first_server.as_raw_fd()).expect("first identity");
        let second =
            collect_accepted_unix_peer(second_server.as_raw_fd()).expect("second identity");

        assert_eq!(first.subject(), second.subject());
        assert_eq!(first.evidence_class(), second.evidence_class());

        drop((first_client, second_client));
    }

    #[test]
    fn retained_socket_supports_per_request_revalidation() {
        let (client, server) = UnixStream::pair().expect("Unix socket pair");
        let identity = collect_accepted_unix_peer(server.as_raw_fd()).expect("macOS peer identity");
        let retained_identity = identity.clone();
        drop((identity, server));

        retained_identity
            .revalidate()
            .expect("retained identity must revalidate");

        drop(client);
    }
}
