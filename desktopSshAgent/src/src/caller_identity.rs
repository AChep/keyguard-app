//! SSH protobuf adapter for shared Unix caller identity collection.

use crate::ipc::messages::{
    CallerAuthorization as ProtoCallerAuthorization,
    CallerAuthorizationEvidenceSource as ProtoEvidenceSource,
    CallerAuthorizationSubject as ProtoCallerAuthorizationSubject,
    CallerAuthorizationSubjectKind as ProtoSubjectKind, CallerIdentity as ProtoCallerIdentity,
};
use keyguard_agent_identity::caller_identity::{
    self as shared, CallerAuthorization, CallerAuthorizationEvidenceSource,
    CallerAuthorizationSubject, CallerIdentity,
};
use keyguard_agent_identity::VerifiedSubjectKind;
use tokio::net::UnixStream;

pub(crate) struct UnixCallerContext {
    pub(crate) caller: ProtoCallerIdentity,
    #[cfg(target_os = "macos")]
    pub(crate) macos_guard: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    pub(crate) linux_guard: Option<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>,
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub(crate) fn caller_from_unix_stream(stream: &UnixStream) -> Option<ProtoCallerIdentity> {
    shared::caller_from_unix_stream(stream).map(|identity| proto_identity(&identity))
}

pub(crate) fn caller_context_from_unix_stream(stream: &UnixStream) -> Option<UnixCallerContext> {
    let context = shared::caller_context_from_unix_stream(stream)?;
    let caller = proto_identity(&context.caller);
    Some(UnixCallerContext {
        caller,
        #[cfg(target_os = "macos")]
        macos_guard: context.macos_guard,
        #[cfg(target_os = "linux")]
        linux_guard: context.linux_guard,
    })
}

fn proto_identity(identity: &CallerIdentity) -> ProtoCallerIdentity {
    ProtoCallerIdentity {
        pid: identity.pid,
        uid: identity.uid,
        gid: identity.gid,
        process_name: identity.process_name.clone(),
        executable_path: identity.executable_path.clone(),
        app_pid: identity.app_pid,
        app_name: identity.app_name.clone(),
        app_bundle_path: identity.app_bundle_path.clone(),
        authorization: identity.authorization.as_ref().map(proto_authorization),
    }
}

fn proto_authorization(authorization: &CallerAuthorization) -> ProtoCallerAuthorization {
    ProtoCallerAuthorization {
        connection_fingerprint: authorization.connection_fingerprint.as_bytes().to_vec(),
        subjects: authorization
            .subjects
            .iter()
            .map(proto_authorization_subject)
            .collect(),
        authorization_context_fingerprint: authorization
            .authorization_context_fingerprint
            .map(|fingerprint| fingerprint.into_bytes().to_vec())
            .unwrap_or_default(),
    }
}

fn proto_authorization_subject(
    subject: &CallerAuthorizationSubject,
) -> ProtoCallerAuthorizationSubject {
    ProtoCallerAuthorizationSubject {
        kind: proto_subject_kind(subject.subject.kind()) as i32,
        evidence_source: proto_evidence_source(subject.evidence_source) as i32,
        fingerprint: subject.subject.fingerprint().into_bytes().to_vec(),
    }
}

fn proto_subject_kind(kind: VerifiedSubjectKind) -> ProtoSubjectKind {
    match kind {
        VerifiedSubjectKind::Process => ProtoSubjectKind::Process,
        VerifiedSubjectKind::ApplicationInstance => ProtoSubjectKind::ApplicationInstance,
        VerifiedSubjectKind::StableApplication => ProtoSubjectKind::StableApplication,
        VerifiedSubjectKind::TerminalSession => ProtoSubjectKind::TerminalSession,
    }
}

fn proto_evidence_source(source: CallerAuthorizationEvidenceSource) -> ProtoEvidenceSource {
    match source {
        CallerAuthorizationEvidenceSource::LinuxPidfd => ProtoEvidenceSource::LinuxPidfd,
        CallerAuthorizationEvidenceSource::LinuxApplicationAncestry => {
            ProtoEvidenceSource::LinuxApplicationAncestry
        }
        CallerAuthorizationEvidenceSource::LinuxTerminalSession => {
            ProtoEvidenceSource::LinuxTerminalSession
        }
        CallerAuthorizationEvidenceSource::MacosAuditToken => ProtoEvidenceSource::MacosAuditToken,
        CallerAuthorizationEvidenceSource::MacosCodeSigning => {
            ProtoEvidenceSource::MacosCodeSigning
        }
        CallerAuthorizationEvidenceSource::MacosApplicationAncestry => {
            ProtoEvidenceSource::MacosApplicationAncestry
        }
        CallerAuthorizationEvidenceSource::MacosTerminalSession => {
            ProtoEvidenceSource::MacosTerminalSession
        }
    }
}
