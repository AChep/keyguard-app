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
