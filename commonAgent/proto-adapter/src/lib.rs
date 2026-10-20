//! Adapters from native caller identity collection to the shared agent protobuf schema.

#[cfg(unix)]
pub use unix::{caller_from_unix_stream, proto_authorization};

#[cfg(unix)]
mod unix {
    use keyguard_agent_identity::caller_guard::CallerGuard;
    use keyguard_agent_identity::caller_identity::{
        self as shared, CallerAuthorization, CallerAuthorizationSubject, CallerIdentity,
    };
    use keyguard_agent_proto::{
        CallerAuthorization as ProtoCallerAuthorization,
        CallerAuthorizationSubject as ProtoCallerAuthorizationSubject,
        CallerIdentity as ProtoCallerIdentity,
    };
    use tokio::net::UnixStream;

    /// Collects the protobuf caller and the guard that revalidates it.
    pub fn caller_from_unix_stream(
        stream: &UnixStream,
    ) -> (Option<ProtoCallerIdentity>, CallerGuard) {
        match shared::caller_context_from_unix_stream(stream) {
            Some(context) => (Some(proto_identity(&context.caller)), context.guard),
            None => (None, CallerGuard::default()),
        }
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

    /// The protobuf form of collected authorization evidence.
    pub fn proto_authorization(authorization: &CallerAuthorization) -> ProtoCallerAuthorization {
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
            kind: subject.subject.kind().wire_value(),
            evidence_source: subject.evidence_source.wire_value(),
            fingerprint: subject.subject.fingerprint().into_bytes().to_vec(),
        }
    }

    #[cfg(test)]
    mod tests {
        use keyguard_agent_identity::caller_identity::CallerAuthorizationEvidenceSource;
        use keyguard_agent_identity::VerifiedSubjectKind;
        use keyguard_agent_proto::{
            CallerAuthorizationEvidenceSource as ProtoEvidenceSource,
            CallerAuthorizationSubjectKind as ProtoSubjectKind,
        };

        // The caller is serialized from `wire_value()` directly, so this pins
        // the SSH, GPG, and environments caller schemas together.
        #[test]
        fn protobuf_values_match_the_shared_wire_values() {
            for (kind, expected) in [
                (VerifiedSubjectKind::Process, ProtoSubjectKind::Process),
                (
                    VerifiedSubjectKind::ApplicationInstance,
                    ProtoSubjectKind::ApplicationInstance,
                ),
                (
                    VerifiedSubjectKind::StableApplication,
                    ProtoSubjectKind::StableApplication,
                ),
                (
                    VerifiedSubjectKind::TerminalSession,
                    ProtoSubjectKind::TerminalSession,
                ),
            ] {
                assert_eq!(kind.wire_value(), expected as i32);
            }
            for (source, expected) in [
                (
                    CallerAuthorizationEvidenceSource::LinuxPidfd,
                    ProtoEvidenceSource::LinuxPidfd,
                ),
                (
                    CallerAuthorizationEvidenceSource::LinuxApplicationAncestry,
                    ProtoEvidenceSource::LinuxApplicationAncestry,
                ),
                (
                    CallerAuthorizationEvidenceSource::LinuxTerminalSession,
                    ProtoEvidenceSource::LinuxTerminalSession,
                ),
                (
                    CallerAuthorizationEvidenceSource::MacosAuditToken,
                    ProtoEvidenceSource::MacosAuditToken,
                ),
                (
                    CallerAuthorizationEvidenceSource::MacosCodeSigning,
                    ProtoEvidenceSource::MacosCodeSigning,
                ),
                (
                    CallerAuthorizationEvidenceSource::MacosApplicationAncestry,
                    ProtoEvidenceSource::MacosApplicationAncestry,
                ),
                (
                    CallerAuthorizationEvidenceSource::MacosTerminalSession,
                    ProtoEvidenceSource::MacosTerminalSession,
                ),
            ] {
                assert_eq!(source.wire_value(), expected as i32);
            }
        }
    }
}
