//! The caller schema and the handshake and error messages shared by the SSH,
//! GPG and CLI agent IPC protocols.

/// Generated protobuf messages of `caller.proto` and `agent.proto`.
pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/keyguard.agent.rs"));
}

pub use proto::*;

#[cfg(feature = "fixtures")]
pub mod fixtures;

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    #[test]
    fn caller_authorization_round_trip() {
        let connection = vec![0x5A; 32];
        let original = CallerIdentity {
            pid: 123,
            uid: 456,
            gid: 789,
            process_name: "ssh".to_string(),
            executable_path: "/usr/bin/ssh".to_string(),
            app_pid: 321,
            app_name: "Terminal".to_string(),
            app_bundle_path: "/Applications/Terminal.app".to_string(),
            authorization: Some(CallerAuthorization {
                connection_fingerprint: connection.clone(),
                subjects: Vec::new(),
                authorization_context_fingerprint: Vec::new(),
            }),
        };

        let encoded = original.encode_to_vec();
        let decoded = CallerIdentity::decode(&encoded[..]).expect("decode caller identity");
        let authorization = decoded.authorization.expect("authorization field");

        assert_eq!(authorization.connection_fingerprint, connection);
        assert!(authorization.subjects.is_empty());
        assert!(authorization.authorization_context_fingerprint.is_empty());
    }

    #[test]
    fn multi_subject_caller_authorization_round_trip() {
        let subject = vec![0x11; 32];
        let connection = vec![0x22; 32];
        let context = vec![0x33; 32];
        let authorization = CallerAuthorization {
            connection_fingerprint: connection.clone(),
            subjects: vec![CallerAuthorizationSubject {
                kind: CallerAuthorizationSubjectKind::StableApplication as i32,
                evidence_source: CallerAuthorizationEvidenceSource::MacosApplicationAncestry as i32,
                fingerprint: subject.clone(),
            }],
            authorization_context_fingerprint: context.clone(),
        };

        let encoded = authorization.encode_to_vec();
        let decoded = CallerAuthorization::decode(&encoded[..]).expect("decode authorization");

        assert_eq!(decoded.connection_fingerprint, connection);
        assert_eq!(decoded.authorization_context_fingerprint, context);
        assert_eq!(decoded.subjects.len(), 1);
        assert_eq!(decoded.subjects[0].fingerprint, subject);
        assert_eq!(
            CallerAuthorizationSubjectKind::try_from(decoded.subjects[0].kind),
            Ok(CallerAuthorizationSubjectKind::StableApplication)
        );
    }
}
