//! Auto-generated protobuf message types and convenience re-exports.

pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/keyguard.gpgagent.rs"));
}

pub use proto::*;

// The caller, handshake and error messages are generated once, in
// keyguard-agent-proto.
pub use keyguard_agent_proto::{
    AuthenticateRequest, AuthenticateResponse, CallerAuthorization,
    CallerAuthorizationEvidenceSource, CallerAuthorizationSubject, CallerAuthorizationSubjectKind,
    CallerIdentity, ErrorCode, ErrorResponse,
};

#[cfg(test)]
mod fixtures;

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
    use prost::Message;

    #[test]
    fn authenticate_revision_round_trip() {
        let request = AuthenticateRequest {
            token: vec![0xA5; 32],
            protocol_revision: IPC_PROTOCOL_REVISION,
        };
        let request = AuthenticateRequest::decode(request.encode_to_vec().as_slice())
            .expect("decode authenticate request");
        assert_eq!(request.protocol_revision, IPC_PROTOCOL_REVISION);

        let response = AuthenticateResponse {
            success: true,
            protocol_revision: IPC_PROTOCOL_REVISION,
        };
        let response = AuthenticateResponse::decode(response.encode_to_vec().as_slice())
            .expect("decode authenticate response");
        assert!(response.success);
        assert_eq!(response.protocol_revision, IPC_PROTOCOL_REVISION);
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
}
