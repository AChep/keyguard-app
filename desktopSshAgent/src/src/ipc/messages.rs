//! Auto-generated protobuf message types and convenience wrappers.

/// Include the generated protobuf code.
pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/keyguard.sshagent.rs"));
}

// Re-export the main types for convenience.
pub use proto::*;

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    // ================================================================
    // IpcRequest round-trip tests
    // ================================================================

    #[test]
    fn authenticate_request_round_trip() {
        let original = IpcRequest {
            id: 1,
            request: Some(ipc_request::Request::Authenticate(AuthenticateRequest {
                token: vec![0xAA; 32],
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 1);
        match decoded.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, vec![0xAA; 32]);
            }
            _ => panic!("Expected Authenticate request"),
        }
    }

    #[test]
    fn list_keys_request_round_trip() {
        let original = IpcRequest {
            id: 42,
            request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller: None })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 42);
        assert!(matches!(
            decoded.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
    }

    #[test]
    fn sign_data_request_round_trip() {
        let original = IpcRequest {
            id: 7,
            request: Some(ipc_request::Request::SignData(SignDataRequest {
                public_key: "ssh-ed25519 AAAA... test".to_string(),
                data: vec![1, 2, 3, 4, 5],
                flags: 0x04,
                caller: None,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 7);
        match decoded.request {
            Some(ipc_request::Request::SignData(sign)) => {
                assert_eq!(sign.public_key, "ssh-ed25519 AAAA... test");
                assert_eq!(sign.data, vec![1, 2, 3, 4, 5]);
                assert_eq!(sign.flags, 0x04);
            }
            _ => panic!("Expected SignData request"),
        }
    }

    // ================================================================
    // IpcResponse round-trip tests
    // ================================================================

    #[test]
    fn authenticate_response_round_trip() {
        let original = IpcResponse {
            id: 1,
            response: Some(ipc_response::Response::Authenticate(AuthenticateResponse {
                success: true,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 1);
        match decoded.response {
            Some(ipc_response::Response::Authenticate(auth)) => {
                assert!(auth.success);
            }
            _ => panic!("Expected Authenticate response"),
        }
    }

    #[test]
    fn list_keys_response_round_trip() {
        let original = IpcResponse {
            id: 2,
            response: Some(ipc_response::Response::ListKeys(ListKeysResponse {
                keys: vec![
                    SshKey {
                        name: "my-key".to_string(),
                        public_key: "ssh-ed25519 AAAA...".to_string(),
                        key_type: "ssh-ed25519".to_string(),
                        fingerprint: "SHA256:abcdef".to_string(),
                    },
                    SshKey {
                        name: "rsa-key".to_string(),
                        public_key: "ssh-rsa BBBB...".to_string(),
                        key_type: "ssh-rsa".to_string(),
                        fingerprint: "SHA256:ghijkl".to_string(),
                    },
                ],
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 2);
        match decoded.response {
            Some(ipc_response::Response::ListKeys(keys_resp)) => {
                assert_eq!(keys_resp.keys.len(), 2);
                assert_eq!(keys_resp.keys[0].name, "my-key");
                assert_eq!(keys_resp.keys[1].key_type, "ssh-rsa");
            }
            _ => panic!("Expected ListKeys response"),
        }
    }

    #[test]
    fn sign_data_response_round_trip() {
        let sig_bytes = vec![42u8; 64];
        let original = IpcResponse {
            id: 3,
            response: Some(ipc_response::Response::SignData(SignDataResponse {
                signature: sig_bytes.clone(),
                algorithm: "ssh-ed25519".to_string(),
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 3);
        match decoded.response {
            Some(ipc_response::Response::SignData(sig)) => {
                assert_eq!(sig.signature, sig_bytes);
                assert_eq!(sig.algorithm, "ssh-ed25519");
            }
            _ => panic!("Expected SignData response"),
        }
    }

    #[test]
    fn error_response_round_trip() {
        let original = IpcResponse {
            id: 4,
            response: Some(ipc_response::Response::Error(ErrorResponse {
                message: "vault is locked".to_string(),
                code: ErrorCode::VaultLocked as i32,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 4);
        match decoded.response {
            Some(ipc_response::Response::Error(err)) => {
                assert_eq!(err.message, "vault is locked");
                assert_eq!(
                    ErrorCode::try_from(err.code).unwrap(),
                    ErrorCode::VaultLocked
                );
            }
            _ => panic!("Expected Error response"),
        }
    }

    // ================================================================
    // Edge cases
    // ================================================================

    #[test]
    fn empty_request_no_variant_round_trip() {
        let original = IpcRequest {
            id: 99,
            request: None,
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        assert_eq!(decoded.id, 99);
        assert!(decoded.request.is_none());
    }

    #[test]
    fn empty_keys_list_round_trip() {
        let original = IpcResponse {
            id: 5,
            response: Some(ipc_response::Response::ListKeys(ListKeysResponse {
                keys: vec![],
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        match decoded.response {
            Some(ipc_response::Response::ListKeys(keys_resp)) => {
                assert!(keys_resp.keys.is_empty());
            }
            _ => panic!("Expected empty ListKeys response"),
        }
    }

    #[test]
    fn error_code_enum_variants() {
        // Verify all error codes can round-trip through i32.
        let codes = vec![
            ErrorCode::Unspecified,
            ErrorCode::VaultLocked,
            ErrorCode::UserDenied,
            ErrorCode::KeyNotFound,
            ErrorCode::AuthFailed,
            ErrorCode::NotAuthenticated,
        ];

        for code in codes {
            let value = code as i32;
            let recovered = ErrorCode::try_from(value).unwrap();
            assert_eq!(recovered, code);
        }
    }

    // ================================================================
    // Additional protobuf edge cases
    // ================================================================

    #[test]
    fn sign_data_request_with_flags_round_trip() {
        // Test various flag values including edge values.
        for flags in [0u32, 1, 0x04, 0xFF, u32::MAX] {
            let original = IpcRequest {
                id: 100,
                request: Some(ipc_request::Request::SignData(SignDataRequest {
                    public_key: "ssh-ed25519 AAAA...".to_string(),
                    data: vec![0xDE, 0xAD],
                    flags,
                    caller: None,
                })),
            };

            let encoded = original.encode_to_vec();
            let decoded = IpcRequest::decode(&encoded[..]).unwrap();

            match decoded.request {
                Some(ipc_request::Request::SignData(sign)) => {
                    assert_eq!(sign.flags, flags, "Flags mismatch for value {}", flags);
                }
                _ => panic!("Expected SignData request"),
            }
        }
    }

    #[test]
    fn large_public_key_round_trip() {
        // A very long public key string should survive encoding.
        let long_key = "ssh-rsa ".to_string() + &"A".repeat(8000) + " user@host";
        let original = IpcResponse {
            id: 6,
            response: Some(ipc_response::Response::ListKeys(ListKeysResponse {
                keys: vec![SshKey {
                    name: "large-key".to_string(),
                    public_key: long_key.clone(),
                    key_type: "ssh-rsa".to_string(),
                    fingerprint: "SHA256:large".to_string(),
                }],
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        match decoded.response {
            Some(ipc_response::Response::ListKeys(resp)) => {
                assert_eq!(resp.keys.len(), 1);
                assert_eq!(resp.keys[0].public_key, long_key);
            }
            _ => panic!("Expected ListKeys response"),
        }
    }

    #[test]
    fn large_signature_round_trip() {
        // RSA signatures can be large (e.g. 512 bytes for 4096-bit keys).
        let large_sig = vec![0xBB; 512];
        let original = IpcResponse {
            id: 7,
            response: Some(ipc_response::Response::SignData(SignDataResponse {
                signature: large_sig.clone(),
                algorithm: "rsa-sha2-512".to_string(),
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        match decoded.response {
            Some(ipc_response::Response::SignData(sig)) => {
                assert_eq!(sig.signature, large_sig);
                assert_eq!(sig.algorithm, "rsa-sha2-512");
            }
            _ => panic!("Expected SignData response"),
        }
    }

    #[test]
    fn empty_error_message_round_trip() {
        let original = IpcResponse {
            id: 8,
            response: Some(ipc_response::Response::Error(ErrorResponse {
                message: "".to_string(),
                code: ErrorCode::Unspecified as i32,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        match decoded.response {
            Some(ipc_response::Response::Error(err)) => {
                assert!(err.message.is_empty());
                assert_eq!(
                    ErrorCode::try_from(err.code).unwrap(),
                    ErrorCode::Unspecified
                );
            }
            _ => panic!("Expected Error response"),
        }
    }

    #[test]
    fn many_keys_in_list_response_round_trip() {
        let keys: Vec<SshKey> = (0..100)
            .map(|i| SshKey {
                name: format!("key-{}", i),
                public_key: format!("ssh-ed25519 AAAA{:04}... user{}", i, i),
                key_type: "ssh-ed25519".to_string(),
                fingerprint: format!("SHA256:{:04}", i),
            })
            .collect();

        let original = IpcResponse {
            id: 9,
            response: Some(ipc_response::Response::ListKeys(ListKeysResponse {
                keys: keys.clone(),
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcResponse::decode(&encoded[..]).unwrap();

        match decoded.response {
            Some(ipc_response::Response::ListKeys(resp)) => {
                assert_eq!(resp.keys.len(), 100);
                assert_eq!(resp.keys[0].name, "key-0");
                assert_eq!(resp.keys[99].name, "key-99");
            }
            _ => panic!("Expected ListKeys response"),
        }
    }

    #[test]
    fn authenticate_request_empty_token() {
        let original = IpcRequest {
            id: 10,
            request: Some(ipc_request::Request::Authenticate(AuthenticateRequest {
                token: vec![],
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        match decoded.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert!(auth.token.is_empty());
            }
            _ => panic!("Expected Authenticate request"),
        }
    }

    #[test]
    fn sign_data_request_empty_data() {
        let original = IpcRequest {
            id: 11,
            request: Some(ipc_request::Request::SignData(SignDataRequest {
                public_key: "ssh-ed25519 AAAA...".to_string(),
                data: vec![],
                flags: 0,
                caller: None,
            })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();

        match decoded.request {
            Some(ipc_request::Request::SignData(sign)) => {
                assert!(sign.data.is_empty());
            }
            _ => panic!("Expected SignData request"),
        }
    }

    #[test]
    fn unknown_error_code_from_i32() {
        // An unknown error code should fail to convert.
        let result = ErrorCode::try_from(999);
        assert!(result.is_err(), "Unknown error code should fail to convert");
    }

    #[test]
    fn max_id_round_trip() {
        let original = IpcRequest {
            id: u64::MAX,
            request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller: None })),
        };

        let encoded = original.encode_to_vec();
        let decoded = IpcRequest::decode(&encoded[..]).unwrap();
        assert_eq!(decoded.id, u64::MAX);
    }
}
