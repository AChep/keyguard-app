//! Fail-closed macOS identity collection for accepted Unix-domain sockets.
//!
//! The primary evidence is `LOCAL_PEERTOKEN`, an audit token retained by the
//! accepted socket. The token includes the kernel PID version, so it continues
//! to identify the original process instance even if the numeric PID is later
//! reused. The direct peer always receives a process subject. Independent
//! application and terminal-session subjects require bounded ancestry and
//! retained `EVFILT_PROC` lifecycle guards. A root-owned system login is crossed
//! only through a joint, owner-bound terminal proof with explicit Apple code
//! validation. Stable application identity also requires strict
//! Security.framework validation.
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
use std::sync::{Arc, Mutex};
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
const TERMINAL_SESSION_EVIDENCE_DOMAIN: &[u8] = b"keyguard.macos.terminal-session.v2";
const MAX_ANCESTRY_DEPTH: usize = 16;
const SYSTEM_LOGIN_PATH: &str = "/usr/bin/login";
const SYSTEM_LOGIN_REQUIREMENT: &str = "identifier \"com.apple.login\" and anchor apple";

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
    login: Option<Arc<MacosLoginSessionProof>>,
}

impl PartialEq for MacosTerminalSessionIdentity {
    fn eq(&self, other: &Self) -> bool {
        self.subject == other.subject
            && self.session_id == other.session_id
            && self.controlling_tty == other.controlling_tty
            && self.leader == other.leader
            && self.accepted_chain == other.accepted_chain
            && self.login == other.login
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
    accepted_chain: Box<[MacosProcessSnapshot]>,
    login: Option<Arc<MacosLoginSessionProof>>,
}

impl PartialEq for MacosApplicationIdentity {
    fn eq(&self, other: &Self) -> bool {
        self.pid == other.pid
            && self.subject == other.subject
            && self.display_name == other.display_name
            && self.bundle_path == other.bundle_path
            && self.team_identifier == other.team_identifier
            && self.signing_identifier == other.signing_identifier
            && self.accepted_chain == other.accepted_chain
            && self.login == other.login
    }
}

impl Eq for MacosApplicationIdentity {}

#[derive(Debug)]
struct MacosProcessLifecycleGuard {
    kqueue: OwnedFd,
    valid: Mutex<bool>,
}

impl MacosProcessLifecycleGuard {
    fn ensure_live(&self) -> Result<(), MacosIdentityError> {
        self.ensure_live_with(|| self.poll())
    }

    fn ensure_live_with(
        &self,
        poll: impl FnOnce() -> Result<(), MacosIdentityError>,
    ) -> Result<(), MacosIdentityError> {
        // EV_CLEAR consumes an event. Serialize consumers and permanently
        // invalidate every clone before releasing this lock, including on
        // polling errors; a later empty poll must never resurrect the proof.
        let mut valid = self
            .valid
            .lock()
            .map_err(|_| MacosIdentityError::ProcessIdentityChanged)?;
        if !*valid {
            return Err(MacosIdentityError::ProcessIdentityChanged);
        }
        let result = poll();
        if result.is_err() {
            *valid = false;
        }
        result
    }

    fn poll(&self) -> Result<(), MacosIdentityError> {
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

    /// Application authorization, distinct from authenticated display data.
    /// An application reached through system login requires the matching
    /// retained terminal proof; incomplete evidence never widens its scope.
    #[must_use]
    pub fn application_authorization_subject(&self) -> Option<VerifiedSubject> {
        let application = self.application.as_ref()?;
        if let Some(login) = &application.login {
            let session = self.terminal_session.as_ref()?;
            if session.login.as_deref() != Some(login.as_ref()) || session.subject != login.subject
            {
                return None;
            }
        }
        Some(application.subject)
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
            if let Some(login) = &application.login {
                login.lifecycle.ensure_live()?;
            }
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
            if let Some(login) = &application.login {
                login.lifecycle.ensure_live()?;
            }
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
    let source = SystemAncestrySource;
    let terminal_session = collect_terminal_session(&source, peer.pid, expected_uid)
        .unwrap_or_else(|_| {
            tracing::debug!(
                stage = "terminal_session",
                "macOS reusable ancestry unavailable"
            );
            None
        });
    let application =
        collect_origin_application(&source, peer.pid, expected_uid, terminal_session.as_ref())
            .unwrap_or_else(|_| {
                tracing::debug!(stage = "application", "macOS reusable ancestry unavailable");
                None
            });
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
    let guard = Arc::new(MacosProcessLifecycleGuard {
        kqueue,
        valid: Mutex::new(true),
    });
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
    real_uid: u32,
    start_seconds: u64,
    start_microseconds: u64,
    executable_path: String,
    bundle_path: String,
}

/// This proof is shared by the session and any application reached through it.
/// Its lifecycle descriptor is the session's existing descriptor, not another
/// monitor. Per-connection ancestry never enters a reusable app fingerprint.
#[derive(Clone, Debug)]
struct MacosLoginSessionProof {
    owner_uid: u32,
    subject: VerifiedSubject,
    leader: MacosProcessSnapshot,
    parent: MacosProcessSnapshot,
    accepted_chain: Box<[MacosProcessSnapshot]>,
    code: MacosLoginCodeEvidence,
    lifecycle: Arc<MacosProcessLifecycleGuard>,
}

impl PartialEq for MacosLoginSessionProof {
    fn eq(&self, other: &Self) -> bool {
        self.owner_uid == other.owner_uid
            && self.subject == other.subject
            && self.leader == other.leader
            && self.parent == other.parent
            && self.accepted_chain == other.accepted_chain
            && self.code == other.code
    }
}

impl Eq for MacosLoginSessionProof {}

#[derive(Clone, Debug, Eq, PartialEq)]
struct MacosLoginCodeEvidence {
    requirement: Vec<u8>,
    cdhash: Vec<u8>,
}

struct MacosApplicationCodeEvidence {
    requirement: Vec<u8>,
    cdhash: Vec<u8>,
    team_identifier: Option<String>,
    signing_identifier: Option<String>,
    is_apple_platform_code: bool,
}

/// Private seam: fixtures exercise the same bounded walks, joint proof and
/// recollection as the OS implementation, without spawning privileged code.
trait MacosAncestrySource {
    fn snapshot(&self, pid: u32) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure>;
    fn lifecycle(
        &self,
        pid: u32,
    ) -> Result<Arc<MacosProcessLifecycleGuard>, MacosCodeSigningFailure>;
    fn login_code(&self, pid: u32) -> Result<MacosLoginCodeEvidence, MacosCodeSigningFailure>;
    fn application_code(
        &self,
        pid: u32,
    ) -> Result<MacosApplicationCodeEvidence, MacosCodeSigningFailure>;
}

struct SystemAncestrySource;

impl MacosAncestrySource for SystemAncestrySource {
    fn snapshot(&self, pid: u32) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure> {
        read_process_snapshot(pid)
    }

    fn lifecycle(
        &self,
        pid: u32,
    ) -> Result<Arc<MacosProcessLifecycleGuard>, MacosCodeSigningFailure> {
        retain_process_lifecycle(pid)
    }

    fn login_code(&self, pid: u32) -> Result<MacosLoginCodeEvidence, MacosCodeSigningFailure> {
        validate_system_login_code(pid)
    }

    fn application_code(
        &self,
        pid: u32,
    ) -> Result<MacosApplicationCodeEvidence, MacosCodeSigningFailure> {
        validate_application_code(pid)
    }
}

fn valid_controlling_tty(tty: u64) -> bool {
    tty != 0 && tty != u64::from(u32::MAX)
}

fn collect_terminal_session(
    source: &impl MacosAncestrySource,
    peer_pid: u32,
    expected_uid: u32,
) -> Result<Option<MacosTerminalSessionIdentity>, MacosCodeSigningFailure> {
    let peer = source.snapshot(peer_pid)?;
    if peer.effective_uid != expected_uid
        || peer.session_id <= 1
        || !valid_controlling_tty(peer.controlling_tty)
    {
        return Ok(None);
    }
    let first_chain = process_chain_to(source, peer_pid, peer.session_id, expected_uid)?;
    let leader = first_chain
        .last()
        .filter(|snapshot| snapshot.pid == peer.session_id)
        .cloned()
        .ok_or_else(|| MacosCodeSigningFailure::new("session leader is not a bounded ancestor"))?;
    if first_chain.first() != Some(&peer)
        || leader.session_id != peer.session_id
        || leader.controlling_tty != peer.controlling_tty
    {
        return Err(MacosCodeSigningFailure::new(
            "terminal session snapshot disagrees",
        ));
    }
    let login_parent = if leader.effective_uid != expected_uid {
        // Include this required owner-side boundary in the same depth budget.
        if first_chain.len() >= MAX_ANCESTRY_DEPTH || leader.parent_pid <= 1 {
            return Err(MacosCodeSigningFailure::new(
                "system login has no bounded owner parent",
            ));
        }
        let parent = source.snapshot(leader.parent_pid)?;
        if parent.effective_uid != expected_uid
            || first_chain
                .iter()
                .any(|ancestor| ancestor.pid == parent.pid)
        {
            return Err(MacosCodeSigningFailure::new(
                "system login parent is not owner-local",
            ));
        }
        Some(parent)
    } else {
        None
    };

    let lifecycle = source.lifecycle(leader.pid).inspect_err(|_| {
        tracing::debug!(
            stage = "terminal_session",
            reason = "lifecycle_unavailable",
            "macOS reusable ancestry unavailable"
        );
    })?;
    let login_code = login_parent
        .as_ref()
        .map(|_| {
            source.login_code(leader.pid).inspect_err(|_| {
                tracing::debug!(
                    stage = "system_login",
                    reason = "code_validation_failed",
                    "macOS reusable ancestry unavailable"
                );
            })
        })
        .transpose()?;
    let confirmed_chain = process_chain_to(source, peer_pid, peer.session_id, expected_uid)?;
    if first_chain != confirmed_chain {
        return Err(MacosCodeSigningFailure::new(
            "terminal session ancestry changed during collection",
        ));
    }
    if let Some(parent) = &login_parent {
        if source.snapshot(parent.pid)? != *parent {
            return Err(MacosCodeSigningFailure::new("system login parent changed"));
        }
    }
    lifecycle
        .ensure_live()
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;

    let subject = terminal_session_subject_from_leader(&leader, expected_uid)?;
    let login = login_parent.zip(login_code).map(|(parent, code)| {
        Arc::new(MacosLoginSessionProof {
            owner_uid: expected_uid,
            subject,
            leader: leader.clone(),
            parent,
            accepted_chain: first_chain.clone().into_boxed_slice(),
            code,
            lifecycle: Arc::clone(&lifecycle),
        })
    });
    Ok(Some(MacosTerminalSessionIdentity {
        subject,
        session_id: peer.session_id,
        controlling_tty: peer.controlling_tty,
        leader,
        accepted_chain: first_chain.into_boxed_slice(),
        lifecycle,
        login,
    }))
}

fn terminal_session_subject_from_leader(
    leader: &MacosProcessSnapshot,
    owner_uid: u32,
) -> Result<VerifiedSubject, MacosCodeSigningFailure> {
    let mut canonical = Vec::with_capacity(132 + leader.executable_path.len());
    canonical.extend_from_slice(&owner_uid.to_be_bytes());
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

/// Build an untrusted candidate chain. The sole foreign-UID candidate must be
/// the exact root login session leader. It is not reusable until its live code,
/// owner parent, lifecycle and repeated snapshots have all been verified.
fn process_chain_to(
    source: &impl MacosAncestrySource,
    leaf_pid: u32,
    target_pid: u32,
    expected_uid: u32,
) -> Result<Vec<MacosProcessSnapshot>, MacosCodeSigningFailure> {
    let mut chain: Vec<MacosProcessSnapshot> = Vec::with_capacity(MAX_ANCESTRY_DEPTH);
    let mut current_pid = leaf_pid;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        if chain.iter().any(|ancestor| ancestor.pid == current_pid) {
            break;
        }
        let snapshot = source.snapshot(current_pid)?;
        if snapshot.effective_uid != expected_uid {
            let valid_login = snapshot.pid == target_pid
                && snapshot.effective_uid == 0
                && snapshot.real_uid == expected_uid
                && snapshot.executable_path == SYSTEM_LOGIN_PATH
                && snapshot.session_id == target_pid
                && valid_controlling_tty(snapshot.controlling_tty)
                && !chain.is_empty()
                && chain.iter().all(|ancestor| {
                    ancestor.effective_uid == expected_uid
                        && ancestor.session_id == target_pid
                        && ancestor.controlling_tty == snapshot.controlling_tty
                });
            if !valid_login {
                tracing::debug!(
                    stage = "system_login",
                    reason = "boundary_not_verified",
                    "macOS reusable ancestry unavailable"
                );
                break;
            }
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
        "target process is not a bounded owner-local or system-login ancestor",
    ))
}

#[derive(Debug, Eq, PartialEq)]
struct MacosApplicationCandidate {
    selected: MacosProcessSnapshot,
    accepted_chain: Vec<MacosProcessSnapshot>,
    login: Option<Arc<MacosLoginSessionProof>>,
}

fn collect_origin_application(
    source: &impl MacosAncestrySource,
    peer_pid: u32,
    expected_uid: u32,
    terminal_session: Option<&MacosTerminalSessionIdentity>,
) -> Result<Option<MacosApplicationIdentity>, MacosCodeSigningFailure> {
    let Some(first) = find_origin_application(source, peer_pid, expected_uid, terminal_session)?
    else {
        return Ok(None);
    };
    let lifecycle = source.lifecycle(first.selected.pid)?;
    let code = source.application_code(first.selected.pid)?;
    let confirmed = find_origin_application(source, peer_pid, expected_uid, terminal_session)?
        .ok_or_else(|| MacosCodeSigningFailure::new("origin application disappeared"))?;
    if first != confirmed {
        return Err(MacosCodeSigningFailure::new(
            "origin application changed during validation",
        ));
    }
    lifecycle
        .ensure_live()
        .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
    if let Some(login) = &first.login {
        login
            .lifecycle
            .ensure_live()
            .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
    }

    let subject = if let Some(signing_identifier) = code.signing_identifier.as_deref() {
        if code.is_apple_platform_code || code.team_identifier.is_some() {
            let canonical = canonical_application_code_signing_evidence(
                code.is_apple_platform_code,
                code.team_identifier.as_deref(),
                signing_identifier,
                &code.requirement,
            )?;
            verified_subject(
                APPLICATION_CODE_SIGNING_EVIDENCE_DOMAIN,
                &canonical,
                VerifiedSubjectKind::StableApplication,
            )
            .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?
        } else {
            application_instance_subject(&first.selected, &code.requirement, &code.cdhash)?
        }
    } else {
        application_instance_subject(&first.selected, &code.requirement, &code.cdhash)?
    };

    let display_name = std::path::Path::new(&first.selected.bundle_path)
        .file_stem()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("Application")
        .to_owned();
    // Existing same-user applications keep their previous identity semantics.
    // The newly enabled root path additionally retains its complete ancestry.
    let accepted_chain = if first.login.is_some() {
        first.accepted_chain.into_boxed_slice()
    } else {
        Box::new([])
    };
    Ok(Some(MacosApplicationIdentity {
        pid: first.selected.pid,
        subject,
        display_name,
        bundle_path: first.selected.bundle_path,
        team_identifier: code.team_identifier,
        signing_identifier: code.signing_identifier,
        lifecycle,
        accepted_chain,
        login: first.login,
    }))
}

fn find_origin_application(
    source: &impl MacosAncestrySource,
    peer_pid: u32,
    expected_uid: u32,
    terminal_session: Option<&MacosTerminalSessionIdentity>,
) -> Result<Option<MacosApplicationCandidate>, MacosCodeSigningFailure> {
    let permitted_login = terminal_session.and_then(|session| session.login.as_ref());
    let mut current_pid = peer_pid;
    let mut selected_bundle: Option<String> = None;
    let mut selected = None;
    let mut chain: Vec<MacosProcessSnapshot> = Vec::with_capacity(MAX_ANCESTRY_DEPTH);
    let mut crossed_login: Option<Arc<MacosLoginSessionProof>> = None;
    let mut ended = false;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        if chain.iter().any(|ancestor| ancestor.pid == current_pid) {
            return Err(MacosCodeSigningFailure::new(
                "application ancestry contains a cycle",
            ));
        }
        let snapshot = source.snapshot(current_pid)?;
        if let Some(login) = &crossed_login {
            if chain
                .last()
                .is_some_and(|previous| previous.pid == login.leader.pid)
                && snapshot != login.parent
            {
                return Err(MacosCodeSigningFailure::new(
                    "system login owner parent changed",
                ));
            }
        }
        if snapshot.effective_uid != expected_uid {
            if crossed_login.is_some() {
                return Err(MacosCodeSigningFailure::new(
                    "application ancestry has another UID boundary",
                ));
            }
            let Some(login) = permitted_login else {
                ended = true;
                break;
            };
            if login.owner_uid != expected_uid
                || snapshot != login.leader
                || chain.len() + 1 != login.accepted_chain.len()
                || chain.as_slice() != &login.accepted_chain[..chain.len()]
                || terminal_session.is_none_or(|session| session.subject != login.subject)
            {
                return Err(MacosCodeSigningFailure::new(
                    "application does not match system login proof",
                ));
            }
            login
                .lifecycle
                .ensure_live()
                .map_err(|error| MacosCodeSigningFailure::new(error.to_string()))?;
            crossed_login = Some(Arc::clone(login));
        }
        chain.push(snapshot.clone());
        if snapshot.bundle_path.is_empty() {
            if selected_bundle.is_some() {
                ended = true;
                break;
            }
        } else {
            match selected_bundle.as_deref() {
                None => {
                    selected_bundle = Some(snapshot.bundle_path.clone());
                    selected = Some(snapshot.clone());
                }
                Some(bundle) if bundle == snapshot.bundle_path => {
                    selected = Some(snapshot.clone());
                }
                Some(_) => {
                    ended = true;
                    break;
                }
            }
        }
        if snapshot.parent_pid == snapshot.pid && crossed_login.is_some() {
            return Err(MacosCodeSigningFailure::new(
                "system login application ancestry contains a self-cycle",
            ));
        }
        if snapshot.parent_pid <= 1 || snapshot.parent_pid == snapshot.pid {
            ended = true;
            break;
        }
        current_pid = snapshot.parent_pid;
    }
    if crossed_login.is_some() && !ended {
        return Err(MacosCodeSigningFailure::new(
            "system login application ancestry exceeds depth bound",
        ));
    }
    Ok(selected.map(|selected| MacosApplicationCandidate {
        selected,
        accepted_chain: if crossed_login.is_some() {
            chain
        } else {
            Vec::new()
        },
        login: crossed_login,
    }))
}

fn validate_system_login_code(pid: u32) -> Result<MacosLoginCodeEvidence, MacosCodeSigningFailure> {
    // The caller arms a lifecycle guard before this PID lookup and resamples
    // the complete candidate afterwards. Never invent an ancestor audit token.
    let code = copy_code_for_pid(pid)?;
    let requirement = create_requirement(SYSTEM_LOGIN_REQUIREMENT)?;
    // SAFETY: the retained code and explicit external requirement remain live.
    let status = unsafe { SecCodeCheckValidity(code.as_ptr(), 0, requirement.as_ptr()) };
    require_status("SecCodeCheckValidity(system login)", status)?;
    strictly_validate_static_code(code.as_ptr(), requirement.as_ptr())?;
    let signing_info = copy_signing_information(code.as_ptr())?;
    let cdhash = dictionary_data(
        signing_info.as_ptr(),
        // SAFETY: Security.framework exports this immortal CFString key.
        unsafe { kSecCodeInfoUnique },
        "system login code-directory hash",
    )?;
    if cdhash.is_empty() || cdhash.len() > MAX_CDHASH_BYTES {
        return Err(MacosCodeSigningFailure::new(
            "system login has an invalid code-directory hash",
        ));
    }
    Ok(MacosLoginCodeEvidence {
        requirement: copy_requirement_data(requirement.as_ptr())?,
        cdhash,
    })
}

fn validate_application_code(
    pid: u32,
) -> Result<MacosApplicationCodeEvidence, MacosCodeSigningFailure> {
    let code = copy_code_for_pid(pid)?;
    let requirement = copy_designated_requirement(code.as_ptr())?;
    // SAFETY: retained Security.framework objects are valid for this call.
    let status = unsafe { SecCodeCheckValidity(code.as_ptr(), 0, requirement.as_ptr()) };
    require_status("SecCodeCheckValidity(application)", status)?;
    strictly_validate_static_code(code.as_ptr(), requirement.as_ptr())?;
    let signing_info = copy_signing_information(code.as_ptr())?;
    Ok(MacosApplicationCodeEvidence {
        requirement: copy_requirement_data(requirement.as_ptr())?,
        cdhash: dictionary_data(
            signing_info.as_ptr(),
            // SAFETY: Security.framework exports this immortal CFString key.
            unsafe { kSecCodeInfoUnique },
            "application code-directory hash",
        )?,
        team_identifier: dictionary_optional_string(
            signing_info.as_ptr(),
            // SAFETY: Security.framework exports this immortal CFString key.
            unsafe { kSecCodeInfoTeamIdentifier },
            "application team identifier",
        )?,
        signing_identifier: dictionary_optional_string(
            signing_info.as_ptr(),
            // SAFETY: Security.framework exports this immortal CFString key.
            unsafe { kSecCodeInfoIdentifier },
            "application signing identifier",
        )?,
        is_apple_platform_code: dictionary_value(
            signing_info.as_ptr(),
            // SAFETY: Security.framework exports this immortal CFString key.
            unsafe { kSecCodeInfoPlatformIdentifier },
            "application platform identifier",
        )?
        .is_some(),
    })
}

// Darwin sysctl/proc ABI, mirrored because libc does not expose
// kinfo_proc on macOS. Field types/order follow <sys/sysctl.h>, <sys/proc.h>
// and <sys/vm.h>; SDK-backed tests assert every consumed offset and the total
// sizes. Pointer-valued fields are opaque integer slots and never dereferenced.
#[repr(C)]
struct MacosExternProc {
    start_time: libc::timeval,
    _vmspace: usize,
    _sigacts: usize,
    _flag: c_int,
    status: c_char,
    pid: libc::pid_t,
    _original_parent: libc::pid_t,
    _duplicate_fd: c_int,
    _user_stack: usize,
    _exit_thread: usize,
    _debugger: c_int,
    _sigwait: u32,
    _estcpu: u32,
    _cpticks: c_int,
    _pctcpu: u32,
    _wchan: usize,
    _wmesg: usize,
    _swtime: u32,
    _slptime: u32,
    _realtimer: libc::itimerval,
    _rtime: libc::timeval,
    _uticks: u64,
    _sticks: u64,
    _iticks: u64,
    _traceflag: c_int,
    _tracep: usize,
    _siglist: c_int,
    _textvp: usize,
    _holdcnt: c_int,
    _sigmask: libc::sigset_t,
    _sigignore: libc::sigset_t,
    _sigcatch: libc::sigset_t,
    _priority: u8,
    _usrpri: u8,
    _nice: c_char,
    _comm: [c_char; 17],
    _pgrp: usize,
    _addr: usize,
    _xstat: u16,
    _acflag: u16,
    _ru: usize,
}

#[repr(C)]
struct MacosProcessCredentials {
    _lock: [c_char; 72],
    _credentials: usize,
    real_uid: libc::uid_t,
    _saved_uid: libc::uid_t,
    _real_gid: libc::gid_t,
    _saved_gid: libc::gid_t,
    _references: c_int,
}

#[repr(C)]
struct MacosUserCredentials {
    _references: i32,
    effective_uid: libc::uid_t,
    _group_count: i16,
    _groups: [libc::gid_t; 16],
}

#[repr(C)]
struct MacosVmSpace {
    _dummy: i32,
    _dummy2: usize,
    _dummy3: [i32; 5],
    _dummy4: [usize; 3],
}

#[repr(C)]
struct MacosEproc {
    _process_address: usize,
    _session: usize,
    process_credentials: MacosProcessCredentials,
    user_credentials: MacosUserCredentials,
    _vm: MacosVmSpace,
    parent_pid: libc::pid_t,
    _process_group: libc::pid_t,
    _job_count: i16,
    controlling_tty: libc::dev_t,
    _tty_process_group: libc::pid_t,
    _tty_session: usize,
    _wait_message: [c_char; 8],
    _text_size: i32,
    _text_resident_size: i16,
    _text_references: i16,
    _text_swapped_size: i16,
    _flags: i32,
    _login: [c_char; 12],
    _spare: [i32; 4],
}

#[repr(C)]
struct MacosKinfoProc {
    process: MacosExternProc,
    extended: MacosEproc,
}

fn read_kinfo_proc(pid: u32) -> Result<MacosKinfoProc, MacosCodeSigningFailure> {
    let pid = i32::try_from(pid)
        .map_err(|_| MacosCodeSigningFailure::new("sysctl PID does not fit pid_t"))?;
    let mut mib = [libc::CTL_KERN, libc::KERN_PROC, libc::KERN_PROC_PID, pid];
    let mut info = MaybeUninit::<MacosKinfoProc>::zeroed();
    let mut length = size_of::<MacosKinfoProc>();
    // SAFETY: the MIB selects one process; info and length are writable for
    // the advertised sizes. Null newp makes this a read-only kernel query.
    let status = unsafe {
        libc::sysctl(
            mib.as_mut_ptr(),
            mib.len() as u32,
            info.as_mut_ptr().cast(),
            &mut length,
            ptr::null_mut(),
            0,
        )
    };
    if status != 0 || length != size_of::<MacosKinfoProc>() {
        return Err(MacosCodeSigningFailure::new(
            "sysctl process snapshot unavailable or incomplete",
        ));
    }
    // SAFETY: sysctl wrote the complete C-compatible structure. Every field
    // has a numeric representation; opaque kernel addresses are not pointers.
    let info = unsafe { info.assume_init() };
    if info.process.pid != pid || pid <= 0 || info.process.status == libc::SZOMB as c_char {
        return Err(MacosCodeSigningFailure::new(
            "sysctl returned a stale process snapshot",
        ));
    }
    Ok(info)
}

fn snapshot_from_kinfo(
    info: &MacosKinfoProc,
    executable_path: String,
) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure> {
    let pid = u32::try_from(info.process.pid)
        .map_err(|_| MacosCodeSigningFailure::new("invalid sysctl process PID"))?;
    let parent_pid = u32::try_from(info.extended.parent_pid)
        .map_err(|_| MacosCodeSigningFailure::new("invalid sysctl parent PID"))?;
    let start_seconds = u64::try_from(info.process.start_time.tv_sec)
        .map_err(|_| MacosCodeSigningFailure::new("invalid sysctl process start time"))?;
    let start_microseconds = u64::try_from(info.process.start_time.tv_usec)
        .map_err(|_| MacosCodeSigningFailure::new("invalid sysctl process start fraction"))?;
    if pid == 0 || start_seconds == 0 || start_microseconds >= 1_000_000 {
        return Err(MacosCodeSigningFailure::new(
            "incomplete sysctl process-instance evidence",
        ));
    }
    // Darwin dev_t is signed, while proc_bsdinfo exposes the same device bits
    // as u32, including NODEV (-1). Preserve that representation for equality.
    let controlling_tty = u64::from(u32::from_ne_bytes(
        info.extended.controlling_tty.to_ne_bytes(),
    ));
    Ok(MacosProcessSnapshot {
        pid,
        parent_pid,
        session_id: read_session_id(pid)?,
        controlling_tty,
        effective_uid: info.extended.user_credentials.effective_uid,
        real_uid: info.extended.process_credentials.real_uid,
        start_seconds,
        start_microseconds,
        bundle_path: outer_app_bundle_path(&executable_path).unwrap_or_default(),
        executable_path,
    })
}

fn read_system_login_snapshot(pid: u32) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure> {
    let path = process_path(pid)?;
    if path != SYSTEM_LOGIN_PATH {
        return Err(MacosCodeSigningFailure::new(
            "foreign process is not the system login path",
        ));
    }
    let info = read_kinfo_proc(pid)?;
    if info.extended.user_credentials.effective_uid != 0
        || info.extended.process_credentials.real_uid != effective_uid()
    {
        return Err(MacosCodeSigningFailure::new(
            "foreign system login is not owner-local",
        ));
    }
    snapshot_from_kinfo(&info, path)
}

fn read_session_id(pid: u32) -> Result<u32, MacosCodeSigningFailure> {
    let pid = i32::try_from(pid)
        .map_err(|_| MacosCodeSigningFailure::new("session PID does not fit pid_t"))?;
    // SAFETY: getsid reads the kernel session associated with this numeric PID.
    // The surrounding repeated snapshots and lifecycle guard detect changes.
    let session_id = unsafe { libc::getsid(pid) };
    if session_id <= 0 {
        return Err(MacosCodeSigningFailure::new(
            "kernel session ID unavailable",
        ));
    }
    u32::try_from(session_id).map_err(|_| MacosCodeSigningFailure::new("invalid session ID"))
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
        let error = io::Error::last_os_error();
        if size <= 0 && error.raw_os_error() == Some(libc::EPERM) {
            // Full BSD info is owner-EUID restricted; kernel KERN_PROC_PID
            // supplies the same kernel fields for this narrowly checked root
            // login candidate. No other error or foreign path gets a fallback.
            return read_system_login_snapshot(pid);
        }
        return Err(MacosCodeSigningFailure::new(format!(
            "proc_pidinfo({pid}) failed: {error}",
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
    Ok(MacosProcessSnapshot {
        pid,
        parent_pid: info.pbi_ppid,
        session_id: read_session_id(pid)?,
        controlling_tty: u64::from(info.e_tdev),
        effective_uid: info.pbi_uid,
        real_uid: info.pbi_ruid,
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

fn create_requirement(expression: &str) -> Result<OwnedCf, MacosCodeSigningFailure> {
    let length = CfIndex::try_from(expression.len())
        .map_err(|_| MacosCodeSigningFailure::new("requirement expression is too large"))?;
    // SAFETY: expression contains initialized UTF-8 bytes copied by the call.
    let string = unsafe {
        CFStringCreateWithBytes(
            ptr::null(),
            expression.as_ptr(),
            length,
            K_CF_STRING_ENCODING_UTF8,
            0,
        )
    };
    let string = OwnedCf::from_created(string, "CFStringCreateWithBytes(requirement)")?;
    let mut requirement: SecRequirementRef = ptr::null();
    // SAFETY: the retained string is live and requirement is writable output.
    let status = unsafe { SecRequirementCreateWithString(string.as_ptr(), 0, &mut requirement) };
    require_status("SecRequirementCreateWithString", status)?;
    OwnedCf::from_created(requirement, "SecRequirementCreateWithString")
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
    fn SecRequirementCreateWithString(
        text: CfStringRef,
        flags: u32,
        requirement: *mut SecRequirementRef,
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
    fn CFStringCreateWithBytes(
        allocator: CfTypeRef,
        bytes: *const u8,
        length: CfIndex,
        encoding: u32,
        is_external_representation: CfBoolean,
    ) -> CfStringRef;
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

    use std::cell::{Cell, RefCell};
    use std::collections::BTreeMap;
    use std::io::{BufRead, BufReader, Write};
    use std::process::{Child, Command, Stdio};
    use std::sync::Weak;
    use std::time::{Duration, Instant};

    #[derive(Clone, Copy, Eq, PartialEq)]
    enum FixtureFailure {
        None,
        LoginCode,
        ApplicationCode,
        LifecycleCreate,
        LifecyclePoll,
    }

    type SnapshotMutation = fn(&mut BTreeMap<u32, MacosProcessSnapshot>);
    type InvalidTopologyCase = (&'static str, u32, fn(&mut MacosProcessSnapshot));

    struct FixtureSource {
        processes: RefCell<BTreeMap<u32, MacosProcessSnapshot>>,
        failure: Cell<FixtureFailure>,
        mutate_on_login: RefCell<Option<SnapshotMutation>>,
        mutate_on_application: RefCell<Option<SnapshotMutation>>,
        guards: RefCell<Vec<Weak<MacosProcessLifecycleGuard>>>,
        peak_guards: Cell<usize>,
        login_checks: Cell<usize>,
    }

    impl FixtureSource {
        fn iterm() -> Self {
            let mut processes = BTreeMap::new();
            for (pid, parent_pid, effective_uid, executable_path) in [
                (60, 50, 501, "/usr/bin/ssh"),
                (50, 40, 501, "/bin/zsh"),
                (40, 30, 0, SYSTEM_LOGIN_PATH),
                (
                    30,
                    20,
                    501,
                    "/Users/test/Library/Application Support/iTerm2/iTermServer",
                ),
                (20, 1, 501, "/Applications/iTerm.app/Contents/MacOS/iTerm2"),
            ] {
                processes.insert(
                    pid,
                    MacosProcessSnapshot {
                        pid,
                        parent_pid,
                        session_id: if pid >= 40 { 40 } else { 20 },
                        controlling_tty: if pid >= 40 { 100 } else { 0 },
                        effective_uid,
                        real_uid: 501,
                        start_seconds: 1000 + u64::from(pid),
                        start_microseconds: 200,
                        executable_path: executable_path.to_owned(),
                        bundle_path: outer_app_bundle_path(executable_path).unwrap_or_default(),
                    },
                );
            }
            Self {
                processes: RefCell::new(processes),
                failure: Cell::new(FixtureFailure::None),
                mutate_on_login: RefCell::new(None),
                mutate_on_application: RefCell::new(None),
                guards: RefCell::new(Vec::new()),
                peak_guards: Cell::new(0),
                login_checks: Cell::new(0),
            }
        }

        fn change(&self, pid: u32, change: impl FnOnce(&mut MacosProcessSnapshot)) {
            change(
                self.processes
                    .borrow_mut()
                    .get_mut(&pid)
                    .expect("fixture process"),
            );
        }

        fn live_guards(&self) -> usize {
            self.guards
                .borrow()
                .iter()
                .filter(|guard| guard.strong_count() != 0)
                .count()
        }

        fn collect(
            &self,
        ) -> (
            Option<MacosTerminalSessionIdentity>,
            Option<MacosApplicationIdentity>,
        ) {
            let session = collect_terminal_session(self, 60, 501).unwrap_or_default();
            let app =
                collect_origin_application(self, 60, 501, session.as_ref()).unwrap_or_default();
            (session, app)
        }
    }

    impl MacosAncestrySource for FixtureSource {
        fn snapshot(&self, pid: u32) -> Result<MacosProcessSnapshot, MacosCodeSigningFailure> {
            self.processes
                .borrow()
                .get(&pid)
                .cloned()
                .ok_or_else(|| MacosCodeSigningFailure::new("fixture snapshot unavailable"))
        }

        fn lifecycle(
            &self,
            _pid: u32,
        ) -> Result<Arc<MacosProcessLifecycleGuard>, MacosCodeSigningFailure> {
            if self.failure.get() == FixtureFailure::LifecycleCreate {
                return Err(MacosCodeSigningFailure::new(
                    "fixture lifecycle creation failed",
                ));
            }
            let guard = retain_process_lifecycle(std::process::id())?;
            if self.failure.get() == FixtureFailure::LifecyclePoll {
                let _ = guard.ensure_live_with(|| Err(MacosIdentityError::ProcessIdentityChanged));
            }
            self.guards.borrow_mut().push(Arc::downgrade(&guard));
            self.peak_guards
                .set(self.peak_guards.get().max(self.live_guards()));
            Ok(guard)
        }

        fn login_code(&self, _pid: u32) -> Result<MacosLoginCodeEvidence, MacosCodeSigningFailure> {
            self.login_checks.set(self.login_checks.get() + 1);
            if self.failure.get() == FixtureFailure::LoginCode {
                return Err(MacosCodeSigningFailure::new(
                    "fixture login signature rejected",
                ));
            }
            if let Some(mutate) = self.mutate_on_login.borrow_mut().take() {
                mutate(&mut self.processes.borrow_mut());
            }
            Ok(MacosLoginCodeEvidence {
                requirement: b"apple-login-requirement".to_vec(),
                cdhash: b"login-cdhash".to_vec(),
            })
        }

        fn application_code(
            &self,
            _pid: u32,
        ) -> Result<MacosApplicationCodeEvidence, MacosCodeSigningFailure> {
            if self.failure.get() == FixtureFailure::ApplicationCode {
                return Err(MacosCodeSigningFailure::new(
                    "fixture app signature rejected",
                ));
            }
            if let Some(mutate) = self.mutate_on_application.borrow_mut().take() {
                mutate(&mut self.processes.borrow_mut());
            }
            Ok(MacosApplicationCodeEvidence {
                requirement: b"iterm-requirement".to_vec(),
                cdhash: b"iterm-cdhash".to_vec(),
                team_identifier: Some("ITERMTEAM".to_owned()),
                signing_identifier: Some("com.googlecode.iterm2".to_owned()),
                is_apple_platform_code: false,
            })
        }
    }

    fn fixture_peer(
        session: Option<MacosTerminalSessionIdentity>,
        application: Option<MacosApplicationIdentity>,
    ) -> (MacosPeerIdentity, UnixStream) {
        let (client, server) = UnixStream::pair().expect("fixture socket pair");
        (
            MacosPeerIdentity {
                pid: 60,
                pid_version: 1,
                effective_uid: 501,
                subject: verified_subject(
                    b"fixture-process",
                    b"peer",
                    VerifiedSubjectKind::Process,
                )
                .expect("fixture subject"),
                evidence_class: MacosEvidenceClass::AuditToken,
                code_signing_status: MacosCodeSigningStatus::Degraded(
                    MacosCodeSigningFailure::new("fixture"),
                ),
                team_identifier: None,
                signing_identifier: None,
                application,
                terminal_session: session,
                audit_token: AuditToken {
                    values: [501, 501, 20, 501, 20, 60, 1, 1],
                },
                retained_socket: Arc::new(server.into()),
            },
            client,
        )
    }

    #[test]
    fn verified_login_joint_proof_exports_app_and_session_without_extra_monitor() {
        let source = FixtureSource::iterm();
        let (session, app) = source.collect();
        let session = session.expect("verified login session");
        let app = app.expect("verified owning app");
        let login = session.login.as_ref().expect("login proof");
        assert!(Arc::ptr_eq(&session.lifecycle, &login.lifecycle));
        assert!(Arc::ptr_eq(
            login,
            app.login.as_ref().expect("joint app proof")
        ));
        assert_eq!(app.display_name, "iTerm");
        assert_eq!(app.accepted_chain.len(), 5);
        assert_eq!(source.live_guards(), 2);
        let (peer, _client) = fixture_peer(Some(session), Some(app));
        let exported =
            crate::caller_identity::macos_authorization_subjects(&peer).expect("exported subjects");
        assert_eq!(exported.len(), 3);
        assert_eq!(
            peer.application_authorization_subject()
                .expect("app subject")
                .kind(),
            VerifiedSubjectKind::StableApplication
        );
    }

    #[test]
    fn incomplete_joint_proof_never_exports_display_application() {
        let source = FixtureSource::iterm();
        let (session, app) = source.collect();
        let (mut peer, _client) = fixture_peer(session, app);
        assert!(peer.application_authorization_subject().is_some());
        let original_session = peer.terminal_session.take().expect("session");
        assert!(peer.application().is_some());
        assert!(peer.application_authorization_subject().is_none());
        assert_eq!(
            crate::caller_identity::macos_authorization_subjects(&peer)
                .expect("process only")
                .len(),
            1
        );
        let mut mismatched = original_session.clone();
        mismatched.subject = verified_subject(
            b"fixture-session",
            b"other",
            VerifiedSubjectKind::TerminalSession,
        )
        .expect("other subject");
        peer.terminal_session = Some(mismatched);
        assert!(peer.application_authorization_subject().is_none());
        peer.terminal_session = Some(original_session);
        peer.terminal_session.as_mut().expect("session").login = None;
        assert!(peer.application_authorization_subject().is_none());
    }

    #[test]
    fn login_scope_is_stable_across_children_and_distinct_across_tabs_and_owners() {
        let first_source = FixtureSource::iterm();
        let (first_session, first_app) = first_source.collect();
        let first_session = first_session.expect("first session");
        let first_app = first_app.expect("first app");
        let other_child = FixtureSource::iterm();
        other_child.change(60, |peer| peer.start_microseconds += 1);
        let (second_session, second_app) = other_child.collect();
        assert_eq!(
            first_session.subject,
            second_session.expect("same session").subject
        );
        assert_eq!(first_app.subject, second_app.expect("same app").subject);
        let other_tab = FixtureSource::iterm();
        {
            let mut processes = other_tab.processes.borrow_mut();
            let mut login = processes.remove(&40).expect("login");
            login.pid = 41;
            login.session_id = 41;
            processes.insert(41, login);
            for pid in [50, 60] {
                let process = processes.get_mut(&pid).expect("child");
                process.session_id = 41;
                if pid == 50 {
                    process.parent_pid = 41;
                }
            }
        }
        let (other_session, other_app) = other_tab.collect();
        assert_ne!(
            first_session.subject,
            other_session.expect("new terminal").subject
        );
        assert_eq!(
            first_app.subject,
            other_app.expect("same application").subject
        );
        assert_ne!(
            first_session.subject,
            terminal_session_subject_from_leader(&first_session.leader, 502).expect("other owner")
        );
        let mut old_recipe = Vec::new();
        let leader = &first_session.leader;
        old_recipe.extend_from_slice(&leader.pid.to_be_bytes());
        old_recipe.extend_from_slice(&leader.parent_pid.to_be_bytes());
        old_recipe.extend_from_slice(&leader.session_id.to_be_bytes());
        old_recipe.extend_from_slice(&leader.controlling_tty.to_be_bytes());
        old_recipe.extend_from_slice(&leader.effective_uid.to_be_bytes());
        old_recipe.extend_from_slice(&leader.start_seconds.to_be_bytes());
        old_recipe.extend_from_slice(&leader.start_microseconds.to_be_bytes());
        append_len_prefixed(&mut old_recipe, leader.executable_path.as_bytes()).expect("v1 recipe");
        assert_ne!(
            first_session.subject.fingerprint(),
            SubjectFingerprint::derive(b"keyguard.macos.terminal-session.v1", &old_recipe)
                .expect("v1 fingerprint")
        );
    }

    #[test]
    fn invalid_login_topologies_never_enable_application_fallback() {
        let cases: &[InvalidTopologyCase] = &[
            ("wrong path", 40, |p| {
                p.executable_path = "/tmp/login".to_owned()
            }),
            ("other Apple tool", 40, |p| {
                p.executable_path = "/usr/bin/su".to_owned()
            }),
            ("wrong effective UID", 40, |p| p.effective_uid = 502),
            ("wrong real UID", 40, |p| p.real_uid = 502),
            ("foreign intermediate", 50, |p| p.effective_uid = 502),
            ("second root parent", 30, |p| p.effective_uid = 0),
            ("wrong login SID", 40, |p| p.session_id = 99),
            ("nested session", 50, |p| p.session_id = 99),
            ("nonancestor leader", 60, |p| p.session_id = 99),
            ("detached peer", 60, |p| p.controlling_tty = 0),
            ("missing TTY", 60, |p| {
                p.controlling_tty = u64::from(u32::MAX)
            }),
            ("wrong login TTY", 40, |p| p.controlling_tty = 101),
            ("wrong intermediate TTY", 50, |p| p.controlling_tty = 101),
            ("cycle below login", 50, |p| p.parent_pid = 60),
            ("cycle at login parent", 40, |p| p.parent_pid = 60),
        ];
        for (name, pid, mutate) in cases {
            let source = FixtureSource::iterm();
            source.change(*pid, *mutate);
            let (session, app) = source.collect();
            assert!(session.is_none(), "{name}: no new session");
            assert!(app.is_none(), "{name}: no app fallback");
            assert_eq!(source.live_guards(), 0, "{name}: no retained monitor");
        }
    }

    #[test]
    fn rejected_signature_or_lifecycle_never_enables_bridge_application() {
        for failure in [
            FixtureFailure::LoginCode,
            FixtureFailure::LifecycleCreate,
            FixtureFailure::LifecyclePoll,
        ] {
            let source = FixtureSource::iterm();
            source.failure.set(failure);
            let (session, app) = source.collect();
            assert!(session.is_none());
            assert!(app.is_none());
            assert_eq!(source.live_guards(), 0);
        }
        let source = FixtureSource::iterm();
        source.failure.set(FixtureFailure::ApplicationCode);
        let (session, app) = source.collect();
        assert!(
            session.is_some(),
            "independent verified session survives app failure"
        );
        assert!(app.is_none());
        assert_eq!(source.live_guards(), 1);
    }

    #[test]
    fn login_and_application_recollection_rejects_changed_complete_ancestry() {
        let changes: &[SnapshotMutation] = &[
            |p| p.get_mut(&40).expect("login").start_microseconds += 1,
            |p| p.get_mut(&40).expect("login").real_uid = 502,
            |p| p.get_mut(&40).expect("login").parent_pid = 20,
            |p| p.get_mut(&50).expect("shell").executable_path = "/bin/other".to_owned(),
            |p| p.get_mut(&30).expect("helper").start_microseconds += 1,
        ];
        for mutate in changes {
            let source = FixtureSource::iterm();
            *source.mutate_on_login.borrow_mut() = Some(*mutate);
            assert!(collect_terminal_session(&source, 60, 501).is_err());
            assert_eq!(source.live_guards(), 0);
        }
        for mutate in changes {
            let source = FixtureSource::iterm();
            let session = collect_terminal_session(&source, 60, 501)
                .expect("collection")
                .expect("session");
            *source.mutate_on_application.borrow_mut() = Some(*mutate);
            assert!(collect_origin_application(&source, 60, 501, Some(&session)).is_err());
            assert_eq!(source.live_guards(), 1, "only original session retained");
        }
    }

    #[test]
    fn depth_limit_is_global_for_login_application_and_rejects_second_boundary() {
        let source = FixtureSource::iterm();
        // Session evidence is valid, but additional foreign ancestors must not
        // turn the already collected terminal proof into app authorization.
        source.change(20, |app| app.effective_uid = 0);
        let (session, app) = source.collect();
        assert!(session.is_some());
        assert!(app.is_none());
        let source = FixtureSource::iterm();
        source.change(30, |helper| helper.parent_pid = 100);
        {
            let mut processes = source.processes.borrow_mut();
            let template = processes.get(&30).expect("helper").clone();
            for pid in 100..115 {
                let mut process = template.clone();
                process.pid = pid;
                process.parent_pid = if pid == 114 { 20 } else { pid + 1 };
                processes.insert(pid, process);
            }
        }
        let (session, app) = source.collect();
        assert!(session.is_some());
        assert!(app.is_none(), "cannot restart the depth budget above login");
        for pid in [20, 30] {
            let source = FixtureSource::iterm();
            source.change(pid, |process| process.parent_pid = process.pid);
            let (session, app) = source.collect();
            assert!(session.is_some());
            assert!(
                app.is_none(),
                "self-cycle above login cannot authorize the app"
            );
        }
    }

    #[test]
    fn existing_same_user_and_gui_paths_do_not_need_login_proof() {
        let source = FixtureSource::iterm();
        source.change(40, |leader| {
            leader.effective_uid = 501;
            leader.executable_path = "/bin/zsh".to_owned();
        });
        let (session, app) = source.collect();
        assert!(session.expect("same-user session").login.is_none());
        assert!(app.expect("same-user application").login.is_none());
        assert_eq!(source.login_checks.get(), 0);
        let source = FixtureSource::iterm();
        source.change(60, |peer| {
            peer.parent_pid = 20;
            peer.controlling_tty = 0;
            peer.executable_path = "/Applications/iTerm.app/Contents/MacOS/helper".to_owned();
            peer.bundle_path = "/Applications/iTerm.app".to_owned();
        });
        let (session, app) = source.collect();
        assert!(session.is_none());
        let (peer, _client) = fixture_peer(session, app);
        assert!(
            peer.application_authorization_subject().is_some(),
            "existing GUI evidence remains independent"
        );
        assert_eq!(source.login_checks.get(), 0);
    }

    #[test]
    fn recollection_peak_matches_descriptor_budget_and_proof_clones_release_monitors() {
        let source = FixtureSource::iterm();
        let original = source.collect();
        assert_eq!(source.live_guards(), 2);
        let cloned = original.clone();
        for _ in 0..10 {
            let current = source.collect();
            assert_eq!(original, current);
            assert_eq!(source.live_guards(), 4);
            drop(current);
            assert_eq!(source.live_guards(), 2);
        }
        // One retained socket duplicate plus original/recollected app/session
        // monitors. Sharing login proof Arc adds no descriptors.
        assert_eq!(
            1 + source.peak_guards.get(),
            MacosPeerIdentity::MAX_ADDITIONAL_FD_COUNT
        );
        drop(original);
        assert_eq!(source.live_guards(), 2);
        drop(cloned);
        assert_eq!(source.live_guards(), 0);
    }

    struct TestChild(Child);

    impl Drop for TestChild {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    fn assert_child_event_stays_invalid(ending: &str) {
        let mut child = TestChild(
            Command::new("/bin/sh")
                .args(["-c", &format!("printf 'ready\\n'; read marker; {ending}")])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .expect("test child"),
        );
        let mut ready = String::new();
        BufReader::new(child.0.stdout.take().expect("child stdout"))
            .read_line(&mut ready)
            .expect("child handshake");
        assert_eq!(ready, "ready\n");
        let full = read_process_snapshot(child.0.id()).expect("child full BSD snapshot");
        let kernel = read_kinfo_proc(child.0.id()).expect("child sysctl snapshot");
        assert_eq!(
            full,
            snapshot_from_kinfo(&kernel, full.executable_path.clone())
                .expect("child sysctl fields")
        );
        assert!(
            validate_system_login_code(child.0.id()).is_err(),
            "another Apple-signed component is not system login"
        );
        let guard = retain_process_lifecycle(child.0.id()).expect("retained test child");
        let cloned = Arc::clone(&guard);
        child
            .0
            .stdin
            .as_mut()
            .expect("child stdin")
            .write_all(b"go\n")
            .expect("release child");
        let deadline = Instant::now() + Duration::from_secs(3);
        while guard.ensure_live().is_ok() {
            assert!(
                Instant::now() < deadline,
                "lifecycle event was not observed"
            );
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(guard.ensure_live().is_err());
        assert!(cloned.ensure_live().is_err());
        assert!(cloned
            .ensure_live_with(|| panic!("invalid guard must not poll again"))
            .is_err());
    }

    #[test]
    fn observed_child_exit_invalidates_original_and_cloned_guards_permanently() {
        assert_child_event_stays_invalid("exit 0");
    }

    #[test]
    fn observed_child_exec_invalidates_original_and_cloned_guards_permanently() {
        assert_child_event_stays_invalid("exec /bin/cat");
    }

    #[test]
    fn lifecycle_poll_failure_and_poisoned_lock_stay_invalid() {
        let guard = retain_process_lifecycle(std::process::id()).expect("guard");
        let clone = Arc::clone(&guard);
        assert!(guard
            .ensure_live_with(|| Err(MacosIdentityError::InvalidPeer("fixture poll failure")))
            .is_err());
        assert!(clone
            .ensure_live_with(|| panic!("failed poll is latched"))
            .is_err());
        let guard = retain_process_lifecycle(std::process::id()).expect("guard");
        let clone = Arc::clone(&guard);
        let poisoned = std::thread::spawn(move || {
            let _lock = clone.valid.lock().expect("guard lock");
            panic!("fixture lock poisoning");
        });
        assert!(poisoned.join().is_err());
        assert!(guard.ensure_live().is_err());
        assert!(guard.ensure_live().is_err());
    }

    #[test]
    fn darwin_sysctl_layout_matches_installed_sdk_for_both_desktop_architectures() {
        let checks = [
            ("sizeof(struct kinfo_proc)", size_of::<MacosKinfoProc>()),
            (
                "_Alignof(struct kinfo_proc)",
                std::mem::align_of::<MacosKinfoProc>(),
            ),
            ("sizeof(struct extern_proc)", size_of::<MacosExternProc>()),
            ("sizeof(struct eproc)", size_of::<MacosEproc>()),
            (
                "sizeof(struct _pcred)",
                size_of::<MacosProcessCredentials>(),
            ),
            ("sizeof(struct _ucred)", size_of::<MacosUserCredentials>()),
            ("sizeof(struct vmspace)", size_of::<MacosVmSpace>()),
            (
                "offsetof(struct kinfo_proc, kp_proc.p_starttime)",
                std::mem::offset_of!(MacosKinfoProc, process.start_time),
            ),
            (
                "offsetof(struct kinfo_proc, kp_proc.p_stat)",
                std::mem::offset_of!(MacosKinfoProc, process.status),
            ),
            (
                "offsetof(struct kinfo_proc, kp_proc.p_pid)",
                std::mem::offset_of!(MacosKinfoProc, process.pid),
            ),
            (
                "offsetof(struct kinfo_proc, kp_eproc.e_ppid)",
                std::mem::offset_of!(MacosKinfoProc, extended.parent_pid),
            ),
            (
                "offsetof(struct kinfo_proc, kp_eproc.e_tdev)",
                std::mem::offset_of!(MacosKinfoProc, extended.controlling_tty),
            ),
            (
                "offsetof(struct kinfo_proc, kp_eproc.e_pcred.p_ruid)",
                std::mem::offset_of!(MacosKinfoProc, extended.process_credentials.real_uid),
            ),
            (
                "offsetof(struct kinfo_proc, kp_eproc.e_ucred.cr_uid)",
                std::mem::offset_of!(MacosKinfoProc, extended.user_credentials.effective_uid),
            ),
        ];
        let mut source =
            "#include <stddef.h>\n#include <sys/types.h>\n#include <sys/sysctl.h>\n".to_owned();
        for (expression, expected) in checks {
            source.push_str(&format!(
                "_Static_assert(({expression}) == {expected}, \"{expression}\");\n"
            ));
        }
        for target in ["arm64-apple-macos11", "x86_64-apple-macos10.15"] {
            let mut compiler = Command::new("xcrun")
                .args(["clang", "-x", "c", "-fsyntax-only", "-target", target, "-"])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .expect("installed macOS SDK compiler");
            compiler
                .stdin
                .take()
                .expect("compiler stdin")
                .write_all(source.as_bytes())
                .expect("SDK layout assertions");
            let output = compiler.wait_with_output().expect("SDK compiler result");
            assert!(
                output.status.success(),
                "{target}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
        }
    }

    #[test]
    fn sysctl_snapshot_matches_full_bsd_fields_and_rejects_bad_numeric_evidence() {
        let pid = std::process::id();
        let full = read_process_snapshot(pid).expect("same-user full BSD snapshot");
        let mut info = read_kinfo_proc(pid).expect("kernel sysctl snapshot");
        assert_eq!(
            full,
            snapshot_from_kinfo(&info, process_path(pid).expect("process path"))
                .expect("sysctl fields")
        );
        info.process.start_time.tv_usec = 1_000_000;
        assert!(snapshot_from_kinfo(&info, full.executable_path.clone()).is_err());
        info.process.start_time.tv_usec = -1;
        assert!(snapshot_from_kinfo(&info, full.executable_path.clone()).is_err());
        info.process.start_time.tv_usec = 0;
        info.process.start_time.tv_sec = -1;
        assert!(snapshot_from_kinfo(&info, full.executable_path.clone()).is_err());
        info.process.start_time.tv_sec = 0;
        assert!(snapshot_from_kinfo(&info, full.executable_path).is_err());
        assert!(
            read_system_login_snapshot(pid).is_err(),
            "sysctl fallback never generalizes to another executable"
        );
    }

    #[test]
    fn login_requirement_rejects_an_adhoc_binary_with_the_exact_login_identifier() {
        let directory = tempfile::tempdir().expect("disposable signature fixture");
        let executable = directory.path().join("login");
        std::fs::copy("/bin/cat", &executable).expect("copy test executable");
        let signed = Command::new("/usr/bin/codesign")
            .args(["--force", "--sign", "-", "--identifier", "com.apple.login"])
            .arg(&executable)
            .output()
            .expect("ad-hoc fixture signing");
        assert!(
            signed.status.success(),
            "{}",
            String::from_utf8_lossy(&signed.stderr)
        );
        let mut child = TestChild(
            Command::new(&executable)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .expect("disposable ad-hoc process"),
        );
        child
            .0
            .stdin
            .as_mut()
            .expect("stdin")
            .write_all(b"ready\n")
            .expect("fixture handshake");
        let mut ready = String::new();
        BufReader::new(child.0.stdout.take().expect("stdout"))
            .read_line(&mut ready)
            .expect("live ad-hoc process");
        assert_eq!(ready, "ready\n");
        assert!(
            validate_system_login_code(child.0.id()).is_err(),
            "identifier alone cannot satisfy Apple anchor"
        );
    }

    #[test]
    fn explicit_login_requirement_rejects_the_test_binary_and_differs_from_generic_anchor() {
        assert!(validate_system_login_code(std::process::id()).is_err());
        let exact = create_requirement(SYSTEM_LOGIN_REQUIREMENT).expect("fixed requirement");
        let generic = create_requirement("identifier \"com.apple.login\" and anchor apple generic")
            .expect("generic requirement");
        assert_ne!(
            copy_requirement_data(exact.as_ptr()).expect("fixed data"),
            copy_requirement_data(generic.as_ptr()).expect("generic data")
        );
    }

    #[test]
    #[ignore = "requires KEYGUARD_TEST_TERMINAL_PID naming an existing same-user login terminal"]
    fn existing_terminal_has_live_joint_login_application_proof() {
        let pid = std::env::var("KEYGUARD_TEST_TERMINAL_PID")
            .expect("set the existing terminal shell PID")
            .parse::<u32>()
            .expect("numeric shell PID");
        let source = SystemAncestrySource;
        let owner = effective_uid();
        let session = collect_terminal_session(&source, pid, owner)
            .expect("live session collection")
            .expect("terminal session");
        let login = session.login.as_ref().expect("verified system login");
        assert_eq!(login.leader.effective_uid, 0);
        assert_eq!(login.leader.real_uid, owner);
        let app = collect_origin_application(&source, pid, owner, Some(&session))
            .expect("live application collection")
            .expect("verified application");
        assert!(Arc::ptr_eq(login, app.login.as_ref().expect("joint proof")));
        let repeated_session = collect_terminal_session(&source, pid, owner)
            .expect("repeat session collection")
            .expect("repeat session");
        let repeated_app = collect_origin_application(&source, pid, owner, Some(&repeated_session))
            .expect("repeat app collection")
            .expect("repeat application");
        assert_eq!(session, repeated_session);
        assert_eq!(app, repeated_app);
    }

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
            real_uid: 501,
            start_seconds: 1_000,
            start_microseconds: 200,
            executable_path: "/bin/zsh".to_string(),
            bundle_path: String::new(),
        };
        let first = terminal_session_subject_from_leader(&leader, 501).expect("terminal subject");
        // No child-process field participates in the recipe, so another child
        // of this retained leader produces the same subject.
        let another_child =
            terminal_session_subject_from_leader(&leader, 501).expect("terminal subject");
        let mut changed_tty = leader.clone();
        changed_tty.controlling_tty += 1;
        let changed_tty =
            terminal_session_subject_from_leader(&changed_tty, 501).expect("terminal subject");
        let mut changed_leader = leader;
        changed_leader.pid += 1;
        changed_leader.session_id += 1;
        let changed_leader =
            terminal_session_subject_from_leader(&changed_leader, 501).expect("terminal subject");

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
