//! Protocol-neutral caller identity extraction for agent requests.
//!
//! Public agent protocols do not carry trustworthy information about the
//! requesting process. On Unix this module derives peer credentials from the
//! accepted socket, collects reusable authorization subjects when the OS can
//! prove them, and keeps display metadata separate from authorization state.

#[cfg(target_os = "linux")]
use crate::unix_caller_identity::process_details_from_pid;
use crate::unix_caller_identity::{
    caller_from_unix_stream as shared_caller_from_unix_stream, UnixCallerIdentity,
};
use crate::{
    AuthorizationContextFingerprint, ConnectionFingerprint, VerifiedSubject, VerifiedSubjectKind,
};
#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::os::fd::AsRawFd;
use tokio::net::UnixStream;

/// Protocol-neutral identity and retained platform guards for an accepted peer.
#[derive(Debug)]
pub struct UnixCallerContext {
    /// Caller data ready for a protocol-specific wire adapter.
    pub caller: CallerIdentity,
    #[cfg(target_os = "macos")]
    pub macos_guard: Option<crate::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    pub linux_guard: Option<crate::linux_identity::LinuxProcessIdentity>,
}

/// Protocol-neutral caller display and authorization data.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CallerIdentity {
    pub pid: u32,
    pub uid: u32,
    pub gid: u32,
    pub process_name: String,
    pub executable_path: String,
    pub app_pid: u32,
    pub app_name: String,
    pub app_bundle_path: String,
    pub authorization: Option<CallerAuthorization>,
}

/// Typed authorization evidence awaiting protocol-specific serialization.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CallerAuthorization {
    pub connection_fingerprint: ConnectionFingerprint,
    pub subjects: Vec<CallerAuthorizationSubject>,
    pub authorization_context_fingerprint: Option<AuthorizationContextFingerprint>,
}

/// One independently verified caller subject.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CallerAuthorizationSubject {
    pub subject: VerifiedSubject,
    pub evidence_source: CallerAuthorizationEvidenceSource,
}

/// Platform recipe that established a caller subject.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CallerAuthorizationEvidenceSource {
    LinuxPidfd,
    LinuxApplicationAncestry,
    LinuxTerminalSession,
    MacosAuditToken,
    MacosCodeSigning,
    MacosApplicationAncestry,
    MacosTerminalSession,
}

/// Collects caller data without retaining the platform guards.
#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub fn caller_from_unix_stream(stream: &UnixStream) -> Option<CallerIdentity> {
    caller_context_from_unix_stream(stream).map(|context| context.caller)
}

/// Collects caller data and the guards required for per-request revalidation.
pub fn caller_context_from_unix_stream(stream: &UnixStream) -> Option<UnixCallerContext> {
    let shared_identity = shared_caller_from_unix_stream(stream)?;
    let mut identity = identity_from_shared(&shared_identity);
    // Establish reusable identity before reading PID-addressed display fields.
    // Process names and paths are secondary metadata and never authorization.
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    let mut collected = authorization_from_unix_stream(stream, &shared_identity);
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    let collected = authorization_from_unix_stream(stream, &shared_identity);
    #[cfg(target_os = "macos")]
    if identity.pid != 0 {
        let pid = identity.pid;
        populate_macos_details(&mut identity, pid);
    }
    #[cfg(target_os = "macos")]
    apply_macos_authenticated_display(&mut identity, &mut collected);
    #[cfg(target_os = "linux")]
    apply_linux_authenticated_display(&mut identity, &mut collected);
    identity.authorization = collected.authorization;

    Some(UnixCallerContext {
        caller: identity,
        #[cfg(target_os = "macos")]
        macos_guard: collected.macos_guard,
        #[cfg(target_os = "linux")]
        linux_guard: collected.linux_guard,
    })
}

#[cfg(target_os = "linux")]
fn apply_linux_authenticated_display(
    caller: &mut CallerIdentity,
    collected: &mut CollectedAuthorization,
) {
    let Some(guard) = collected.linux_guard.as_ref() else {
        caller.process_name = escape_display_identifier(&caller.process_name);
        caller.executable_path = escape_display_identifier(&caller.executable_path);
        caller.app_name = "Unverified caller".to_string();
        return;
    };

    // Refresh mutable display metadata only after the process/executable
    // snapshot exists, then revalidate that snapshot.
    let details = process_details_from_pid(guard.pid);
    caller.process_name = details
        .process_name
        .as_deref()
        .map(escape_display_identifier)
        .unwrap_or_default();
    caller.executable_path = details
        .executable_path
        .as_deref()
        .map(escape_display_identifier)
        .unwrap_or_default();
    if let Err(error) = guard.revalidate() {
        tracing::warn!(%error, "Linux agent caller changed during display lookup");
        collected.authorization = None;
        caller.app_name = "Caller identity changed".to_string();
        return;
    }

    if let Some(application) = guard.application() {
        caller.app_pid = application.pid().unwrap_or(0);
        let display_name = application
            .display_name_hint()
            .map(escape_display_identifier)
            .unwrap_or_else(|| "Verified application".to_string());
        caller.app_name = display_name;
        caller.app_bundle_path = application
            .executable_path_hint()
            .map(escape_display_identifier)
            .unwrap_or_default();
    } else {
        caller.app_name = "Verified process".to_string();
    }
}

fn identity_from_shared(shared_identity: &UnixCallerIdentity) -> CallerIdentity {
    CallerIdentity {
        pid: shared_identity.pid.unwrap_or(0),
        uid: shared_identity.uid,
        gid: shared_identity.gid,
        process_name: shared_identity.process_name.clone().unwrap_or_default(),
        executable_path: shared_identity.executable_path.clone().unwrap_or_default(),
        app_pid: 0,
        app_name: String::new(),
        app_bundle_path: String::new(),
        authorization: None,
    }
}

fn authorization_from_unix_stream(
    stream: &UnixStream,
    shared_identity: &UnixCallerIdentity,
) -> CollectedAuthorization {
    let connection = match ConnectionFingerprint::generate() {
        Ok(connection) => connection,
        Err(error) => {
            tracing::warn!(%error, "Failed to generate agent connection authorization");
            return CollectedAuthorization::none();
        }
    };

    #[cfg(target_os = "macos")]
    if let Some((subjects, guard)) = macos_subjects_from_stream(stream, shared_identity) {
        return CollectedAuthorization {
            authorization: Some(platform_authorization(connection, subjects)),
            macos_guard: Some(guard),
        };
    }

    #[cfg(target_os = "linux")]
    if let Some((subjects, guard)) = linux_subjects_from_stream(stream, shared_identity) {
        return CollectedAuthorization {
            authorization: Some(platform_authorization(connection, subjects)),
            linux_guard: Some(guard),
        };
    }

    CollectedAuthorization {
        authorization: Some(platform_authorization(connection, Vec::new())),
        #[cfg(target_os = "macos")]
        macos_guard: None,
        #[cfg(target_os = "linux")]
        linux_guard: None,
    }
}

struct CollectedAuthorization {
    authorization: Option<CallerAuthorization>,
    #[cfg(target_os = "macos")]
    macos_guard: Option<crate::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    linux_guard: Option<crate::linux_identity::LinuxProcessIdentity>,
}

impl CollectedAuthorization {
    fn none() -> Self {
        Self {
            authorization: None,
            #[cfg(target_os = "macos")]
            macos_guard: None,
            #[cfg(target_os = "linux")]
            linux_guard: None,
        }
    }
}

fn platform_authorization(
    connection: ConnectionFingerprint,
    subjects: Vec<CallerAuthorizationSubject>,
) -> CallerAuthorization {
    CallerAuthorization {
        connection_fingerprint: connection,
        subjects,
        authorization_context_fingerprint: None,
    }
}

#[cfg(target_os = "linux")]
fn linux_subjects_from_stream(
    stream: &UnixStream,
    shared_identity: &UnixCallerIdentity,
) -> Option<(
    Vec<CallerAuthorizationSubject>,
    crate::linux_identity::LinuxProcessIdentity,
)> {
    match crate::linux_identity::process_identity_from_socket(stream.as_raw_fd()) {
        Ok(identity)
            if shared_identity.pid == Some(identity.pid)
                && shared_identity.uid == identity.uid
                && shared_identity.gid == identity.gid =>
        {
            let mut subjects = Vec::with_capacity(3);
            subjects.push(CallerAuthorizationSubject {
                subject: identity.process_subject(),
                evidence_source: CallerAuthorizationEvidenceSource::LinuxPidfd,
            });
            if let Some(application) = identity.application() {
                subjects.push(CallerAuthorizationSubject {
                    subject: application.subject(),
                    evidence_source: CallerAuthorizationEvidenceSource::LinuxApplicationAncestry,
                });
            }
            if let Some(subject) = identity.terminal_session_subject() {
                subjects.push(CallerAuthorizationSubject {
                    subject,
                    evidence_source: CallerAuthorizationEvidenceSource::LinuxTerminalSession,
                });
            }
            Some((subjects, identity))
        }
        Ok(_) => {
            tracing::warn!("Linux pidfd identity did not match SO_PEERCRED metadata");
            None
        }
        Err(crate::linux_identity::LinuxIdentityError::PeerPidfdUnsupported(error)) => {
            tracing::debug!(%error, "Linux kernel has no SO_PEERPIDFD; using connection scope");
            None
        }
        Err(error) => {
            tracing::warn!(%error, "Failed to establish Linux agent process identity");
            None
        }
    }
}

#[cfg(target_os = "macos")]
fn macos_subjects_from_stream(
    stream: &UnixStream,
    shared_identity: &UnixCallerIdentity,
) -> Option<(
    Vec<CallerAuthorizationSubject>,
    crate::macos::MacosPeerIdentity,
)> {
    let identity = match crate::macos::collect_accepted_unix_peer(stream.as_raw_fd()) {
        Ok(identity) => identity,
        Err(error) => {
            tracing::warn!(%error, "Failed to establish macOS agent caller identity");
            return None;
        }
    };
    if shared_identity.pid != Some(identity.pid())
        || shared_identity.uid != identity.effective_uid()
    {
        tracing::warn!("macOS audit-token identity did not match socket peer metadata");
        return None;
    }

    if identity.subject().kind() != VerifiedSubjectKind::Process {
        tracing::warn!("Rejected inconsistent macOS direct-process subject");
        return None;
    }
    let mut subjects = Vec::with_capacity(3);
    subjects.push(CallerAuthorizationSubject {
        subject: identity.subject(),
        evidence_source: CallerAuthorizationEvidenceSource::MacosAuditToken,
    });
    if let Some(application) = identity.application() {
        let Some(evidence_source) = macos_application_evidence_source(application.subject().kind())
        else {
            tracing::warn!("Rejected inconsistent macOS application subject kind");
            return None;
        };
        subjects.push(CallerAuthorizationSubject {
            subject: application.subject(),
            evidence_source,
        });
    }
    if let Some(subject) = identity.terminal_session_subject() {
        subjects.push(CallerAuthorizationSubject {
            subject,
            evidence_source: CallerAuthorizationEvidenceSource::MacosTerminalSession,
        });
    }
    Some((subjects, identity))
}

#[cfg(target_os = "macos")]
fn macos_application_evidence_source(
    kind: VerifiedSubjectKind,
) -> Option<CallerAuthorizationEvidenceSource> {
    match kind {
        VerifiedSubjectKind::StableApplication => {
            Some(CallerAuthorizationEvidenceSource::MacosCodeSigning)
        }
        VerifiedSubjectKind::ApplicationInstance => {
            Some(CallerAuthorizationEvidenceSource::MacosApplicationAncestry)
        }
        _ => None,
    }
}

// ================================================================
// macOS implementation
// ================================================================

#[cfg(target_os = "macos")]
fn populate_macos_details(identity: &mut CallerIdentity, pid: u32) {
    if let Some(exe) = macos_proc_pidpath(pid) {
        identity.executable_path = exe.clone();
        if identity.process_name.is_empty() {
            identity.process_name = exe.rsplit('/').next().unwrap_or_default().to_string();
        }
    }

    if identity.process_name.is_empty() {
        if let Some(name) = macos_proc_name(pid) {
            identity.process_name = name;
        }
    }

    // Retain only a bundle path enclosing the direct executable. This path is
    // movable/renameable display metadata; it must remain secondary to the
    // authenticated code-signing label assigned below.
    if let Some(app_bundle_path) = find_app_bundle_path(&identity.executable_path) {
        identity.app_pid = pid;
        identity.app_bundle_path = app_bundle_path;
    }

    identity.process_name = escape_display_identifier(&identity.process_name);
    identity.executable_path = escape_display_identifier(&identity.executable_path);
    identity.app_bundle_path = escape_display_identifier(&identity.app_bundle_path);
}

#[cfg(target_os = "macos")]
fn apply_macos_authenticated_display(
    caller: &mut CallerIdentity,
    collected: &mut CollectedAuthorization,
) {
    let Some(guard) = collected.macos_guard.as_ref() else {
        caller.app_name = "Unverified caller".to_string();
        return;
    };

    // Re-sample after PID-based display lookup. A failure leaves the guard in
    // the session so every sensitive IPC is refused; never send the stale
    // subject authorization merely because display lookup raced an exec.
    if let Err(error) = guard.revalidate() {
        tracing::warn!(%error, "macOS agent caller changed during display lookup");
        collected.authorization = None;
        caller.app_name = "Caller identity changed".to_string();
        return;
    }

    if let Some(application) = guard.application() {
        caller.app_pid = application.pid();
        caller.app_name = macos_authenticated_application_label(application);
        caller.app_bundle_path = escape_display_identifier(application.bundle_path());
    } else {
        caller.app_name = "Verified process".to_string();
    }
}

#[cfg(target_os = "macos")]
fn macos_authenticated_application_label(
    application: &crate::macos::MacosApplicationIdentity,
) -> String {
    escape_display_identifier(application.display_name())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn escape_display_identifier(value: &str) -> String {
    value.chars().flat_map(char::escape_default).collect()
}

#[cfg(target_os = "macos")]
fn macos_proc_pidpath(pid: u32) -> Option<String> {
    use libc::{c_int, c_void};

    let mut buf = vec![0u8; libc::PROC_PIDPATHINFO_MAXSIZE as usize];
    // SAFETY: `buf` is writable for its declared length and `proc_pidpath`
    // does not retain the pointer after returning.
    let ret = unsafe {
        libc::proc_pidpath(
            pid as c_int,
            buf.as_mut_ptr() as *mut c_void,
            buf.len() as u32,
        )
    };
    if ret <= 0 {
        return None;
    }

    // proc_pidpath returns a C string, but `ret` is the buffer size written.
    let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    Some(String::from_utf8_lossy(&buf[..len]).to_string())
}

#[cfg(target_os = "macos")]
fn macos_proc_name(pid: u32) -> Option<String> {
    use libc::{c_int, c_void};

    let mut buf = vec![0u8; 256];
    // SAFETY: `buf` is writable for its declared length and `proc_name` does
    // not retain the pointer after returning.
    let ret = unsafe {
        libc::proc_name(
            pid as c_int,
            buf.as_mut_ptr() as *mut c_void,
            buf.len() as u32,
        )
    };
    if ret <= 0 {
        return None;
    }
    let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    Some(String::from_utf8_lossy(&buf[..len]).to_string())
}

#[cfg(target_os = "macos")]
fn find_app_bundle_path(executable_path: &str) -> Option<String> {
    use std::path::Path;

    let path = Path::new(executable_path);
    for ancestor in path.ancestors() {
        let Some(name) = ancestor.file_name().and_then(|s| s.to_str()) else {
            continue;
        };
        if name.ends_with(".app") {
            return Some(ancestor.to_string_lossy().to_string());
        }
    }
    None
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::{
        caller_context_from_unix_stream, escape_display_identifier,
        macos_authenticated_application_label, CallerAuthorizationEvidenceSource,
    };
    use crate::VerifiedSubjectKind;
    use tokio::net::UnixStream;

    #[test]
    fn authenticated_display_identifier_escapes_controls() {
        assert_eq!(
            escape_display_identifier("com.example\nspoof"),
            "com.example\\nspoof",
        );
    }

    #[test]
    fn macos_application_kind_selects_exact_evidence_source() {
        assert_eq!(
            super::macos_application_evidence_source(VerifiedSubjectKind::StableApplication,),
            Some(CallerAuthorizationEvidenceSource::MacosCodeSigning),
        );
        assert_eq!(
            super::macos_application_evidence_source(VerifiedSubjectKind::ApplicationInstance,),
            Some(CallerAuthorizationEvidenceSource::MacosApplicationAncestry),
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn caller_identity_recovers_macos_peer_pid_and_details() {
        let (server_stream, client_stream) =
            std::os::unix::net::UnixStream::pair().expect("Unix socket pair");
        server_stream
            .set_nonblocking(true)
            .expect("nonblocking server");
        client_stream
            .set_nonblocking(true)
            .expect("nonblocking client");
        let server_stream = UnixStream::from_std(server_stream).expect("Tokio server stream");
        let client_stream = UnixStream::from_std(client_stream).expect("Tokio client stream");
        let context = caller_context_from_unix_stream(&server_stream).expect("caller identity");
        let guard = context.macos_guard.as_ref().expect("retained macOS guard");
        let expected_label = guard
            .application()
            .map(macos_authenticated_application_label)
            .unwrap_or_else(|| "Verified process".to_string());
        assert_eq!(context.caller.app_name, expected_label);
        let identity = context.caller;

        assert_eq!(identity.pid, std::process::id());
        let authorization = identity.authorization.expect("verified authorization");
        assert_eq!(
            authorization.connection_fingerprint.as_bytes().len(),
            crate::PRINCIPAL_FINGERPRINT_LEN,
        );
        let process = authorization
            .subjects
            .iter()
            .find(|subject| subject.subject.kind() == VerifiedSubjectKind::Process)
            .expect("process subject");
        assert_eq!(
            process.evidence_source,
            CallerAuthorizationEvidenceSource::MacosAuditToken,
        );
        assert_eq!(
            process.subject.fingerprint().as_bytes().len(),
            crate::PRINCIPAL_FINGERPRINT_LEN,
        );
        // SAFETY: `getuid` has no preconditions and does not dereference pointers.
        assert_eq!(identity.uid, unsafe { libc::getuid() });
        assert!(
            !identity.process_name.is_empty() || !identity.executable_path.is_empty(),
            "expected process_name or executable_path to be populated"
        );
        assert!(!identity.app_name.is_empty());
        assert_ne!(identity.app_name, "Unverified caller");
        assert_ne!(identity.app_name, "Caller identity changed");

        drop(client_stream);
    }
}
