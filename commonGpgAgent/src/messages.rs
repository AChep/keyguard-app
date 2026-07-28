//! Auto-generated protobuf message types and convenience re-exports.

pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/keyguard.gpgagent.rs"));
}

pub use proto::*;

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    #[test]
    fn authenticate_revision_round_trip() {
        let request = AuthenticateRequest {
            token: vec![0xA5; 32],
            protocol_revision: 1,
        };
        let request = AuthenticateRequest::decode(request.encode_to_vec().as_slice())
            .expect("decode authenticate request");
        assert_eq!(request.protocol_revision, 1);

        let response = AuthenticateResponse {
            success: true,
            protocol_revision: 1,
        };
        let response = AuthenticateResponse::decode(response.encode_to_vec().as_slice())
            .expect("decode authenticate response");
        assert!(response.success);
        assert_eq!(response.protocol_revision, 1);
    }

    #[test]
    fn sign_hash_request_round_trip() {
        let original = IpcRequest {
            id: 42,
            request: Some(ipc_request::Request::SignHash(SignHashRequest {
                keygrip: "ABCD".to_string(),
                hash_algorithm: "sha256".to_string(),
                hash: vec![1, 2, 3],
                caller: None,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).expect("decode");

        assert_eq!(decoded.id, 42);
        match decoded.request {
            Some(ipc_request::Request::SignHash(req)) => {
                assert_eq!(req.keygrip, "ABCD");
                assert_eq!(req.hash_algorithm, "sha256");
                assert_eq!(req.hash, vec![1, 2, 3]);
            }
            other => panic!("unexpected request: {other:?}"),
        }
    }

    #[test]
    fn caller_authorization_round_trip() {
        let connection = vec![0xA5; 32];
        let original = CallerIdentity {
            pid: 123,
            uid: 456,
            gid: 789,
            process_name: "gpg".to_string(),
            executable_path: "/usr/bin/gpg".to_string(),
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
        let subject = vec![0x44; 32];
        let connection = vec![0x55; 32];
        let authorization = CallerAuthorization {
            connection_fingerprint: connection.clone(),
            subjects: vec![CallerAuthorizationSubject {
                kind: CallerAuthorizationSubjectKind::Process as i32,
                evidence_source: CallerAuthorizationEvidenceSource::LinuxPidfd as i32,
                fingerprint: subject.clone(),
            }],
            authorization_context_fingerprint: Vec::new(),
        };

        let encoded = authorization.encode_to_vec();
        let decoded = CallerAuthorization::decode(&encoded[..]).expect("decode authorization");

        assert_eq!(decoded.connection_fingerprint, connection);
        assert!(decoded.authorization_context_fingerprint.is_empty());
        assert_eq!(decoded.subjects.len(), 1);
        assert_eq!(decoded.subjects[0].fingerprint, subject);
        assert_eq!(
            CallerAuthorizationSubjectKind::try_from(decoded.subjects[0].kind),
            Ok(CallerAuthorizationSubjectKind::Process)
        );
    }
}
