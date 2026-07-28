//! Tests for the IPC client and its wire transport.

use super::*;
use crate::ipc::messages::{
    ipc_response, AuthenticateResponse, IpcResponse, ListKeysResponse, SignDataResponse, SshKey,
};
use bytes::BufMut;
use tokio::io::AsyncWriteExt;
use tokio::io::DuplexStream;

/// Helper: creates a duplex pair and wraps both ends as `IpcStream`.
fn duplex_pair() -> (IpcStream<DuplexStream>, IpcStream<DuplexStream>) {
    let (a, b) = tokio::io::duplex(64 * 1024);
    (IpcStream::new(a), IpcStream::new(b))
}

/// Helper: builds a successful authenticate response.
fn auth_ok_response(id: u64) -> IpcResponse {
    IpcResponse {
        id,
        response: Some(ipc_response::Response::Authenticate(AuthenticateResponse {
            success: true,
            protocol_revision: IPC_PROTOCOL_REVISION,
        })),
    }
}

/// Helper: builds a list_keys response with the given keys.
fn list_keys_response(id: u64, keys: Vec<SshKey>) -> IpcResponse {
    IpcResponse {
        id,
        response: Some(ipc_response::Response::ListKeys(ListKeysResponse { keys })),
    }
}

/// Helper: builds a sign_data response.
fn sign_data_response(id: u64, algorithm: &str, signature: Vec<u8>) -> IpcResponse {
    IpcResponse {
        id,
        response: Some(ipc_response::Response::SignData(SignDataResponse {
            algorithm: algorithm.to_string(),
            signature,
        })),
    }
}

/// Helper: builds an error response.
fn error_response(id: u64, code: i32, message: &str) -> IpcResponse {
    IpcResponse {
        id,
        response: Some(ipc_response::Response::Error(
            crate::ipc::messages::ErrorResponse {
                code,
                message: message.to_string(),
            },
        )),
    }
}

// ================================================================
// IpcStream round-trip tests
// ================================================================

#[tokio::test]
async fn ipc_stream_write_and_read_request_round_trip() {
    let (mut client_side, mut server_side) = duplex_pair();

    let request = IpcRequest {
        id: 42,
        request: Some(ipc_request::Request::ListKeys(ListKeysRequest {
            caller: None,
        })),
    };

    client_side.write_message(&request).await.unwrap();
    let received = server_side.read_request().await.unwrap();

    assert_eq!(received.id, 42);
    assert!(matches!(
        received.request,
        Some(ipc_request::Request::ListKeys(_))
    ));
}

#[tokio::test]
async fn ipc_stream_write_and_read_response_round_trip() {
    let (mut client_side, mut server_side) = duplex_pair();

    let response = auth_ok_response(1);

    server_side.write_response(&response).await.unwrap();
    let received = client_side.read_message().await.unwrap();

    assert_eq!(received.id, 1);
    assert!(matches!(
        received.response,
        Some(ipc_response::Response::Authenticate(_))
    ));
}

#[tokio::test]
async fn ipc_stream_multiple_messages_in_sequence() {
    let (mut client_side, mut server_side) = duplex_pair();

    // Send two requests.
    let req1 = IpcRequest {
        id: 1,
        request: Some(ipc_request::Request::ListKeys(ListKeysRequest {
            caller: None,
        })),
    };
    let req2 = IpcRequest {
        id: 2,
        request: Some(ipc_request::Request::ListKeys(ListKeysRequest {
            caller: None,
        })),
    };

    client_side.write_message(&req1).await.unwrap();
    client_side.write_message(&req2).await.unwrap();

    let r1 = server_side.read_request().await.unwrap();
    let r2 = server_side.read_request().await.unwrap();

    assert_eq!(r1.id, 1);
    assert_eq!(r2.id, 2);
}

#[tokio::test]
async fn ipc_stream_oversize_message_rejected() {
    let (mut client_side, mut _server_side) = duplex_pair();

    // Manually write a length prefix that exceeds MAX_MESSAGE_SIZE.
    let huge_len: u32 = MAX_MESSAGE_SIZE + 1;
    let mut len_buf = [0u8; 4];
    (&mut len_buf[..]).put_u32(huge_len);
    client_side.stream.write_all(&len_buf).await.unwrap();

    // The other side should reject it.
    // We need to read from client_side perspective — but actually the
    // oversize check is on read. Let's write from server to client.
    // Re-do: write the huge length from the server side.
    let (mut writer, mut reader) = duplex_pair();
    let mut len_buf = [0u8; 4];
    (&mut len_buf[..]).put_u32(huge_len);
    writer.stream.write_all(&len_buf).await.unwrap();

    let result = reader.read_message().await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("too large"),
        "Expected 'too large' error, got: {}",
        err_msg
    );
}

#[tokio::test]
async fn ipc_stream_eof_on_read_returns_error() {
    let (client_side, mut server_side) = duplex_pair();
    // Drop the writer side to simulate EOF.
    drop(client_side);

    let result = server_side.read_request().await;
    assert!(result.is_err(), "Should error on EOF");
}

// ================================================================
// IpcClient request/response integration (via duplex) tests
// ================================================================

/// Creates an `IpcClient` backed by one half of a duplex, returning
/// the other half so the test can act as the server.
///
/// The "server" side must handle the initial authenticate request.
async fn make_test_client() -> (IpcClient, IpcStream<DuplexStream>) {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);

    let client = IpcClient::from_stream(client_stream);

    // Simulate server handling authenticate in a background task.
    // We need to spawn because authenticate() is synchronous from the
    // client's perspective (send + receive).
    let handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        assert!(matches!(
            req.request,
            Some(ipc_request::Request::Authenticate(_))
        ));
        server
            .write_response(&auth_ok_response(req.id))
            .await
            .unwrap();
        server
    });

    client.authenticate(&[0u8; 32]).await.unwrap();
    let server = handle.await.unwrap();

    (client, server)
}

#[tokio::test]
async fn client_list_keys_via_duplex() {
    let (client, mut server) = make_test_client().await;

    let caller = CallerIdentity {
        pid: 123,
        uid: 456,
        gid: 789,
        process_name: "ssh".to_string(),
        executable_path: "/usr/bin/ssh".to_string(),
        app_pid: 321,
        app_name: "Terminal".to_string(),
        app_bundle_path: "/System/Applications/Utilities/Terminal.app".to_string(),
        authorization: None,
    };
    let expected_pid = caller.pid;
    let expected_app_name = caller.app_name.clone();

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        if let Some(ipc_request::Request::ListKeys(list_req)) = &req.request {
            let caller = list_req
                .caller
                .as_ref()
                .expect("caller identity should be set");
            assert_eq!(caller.pid, expected_pid);
            assert_eq!(caller.app_name, expected_app_name);
        } else {
            panic!("Expected ListKeys request");
        }
        let response = list_keys_response(
            req.id,
            vec![SshKey {
                name: "my-key".to_string(),
                public_key: "ssh-ed25519 AAAA... test".to_string(),
                key_type: "ssh-ed25519".to_string(),
                fingerprint: "SHA256:abc".to_string(),
            }],
        );
        server.write_response(&response).await.unwrap();
        server
    });

    let keys = client.list_keys(Some(caller)).await.unwrap();
    assert_eq!(keys.keys.len(), 1);
    assert_eq!(keys.keys[0].name, "my-key");

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_sign_data_via_duplex() {
    let (client, mut server) = make_test_client().await;

    let caller = CallerIdentity {
        pid: 123,
        uid: 456,
        gid: 789,
        process_name: "ssh".to_string(),
        executable_path: "/usr/bin/ssh".to_string(),
        app_pid: 321,
        app_name: "Terminal".to_string(),
        app_bundle_path: "/System/Applications/Utilities/Terminal.app".to_string(),
        authorization: None,
    };

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        if let Some(ipc_request::Request::SignData(sign_req)) = &req.request {
            assert_eq!(sign_req.public_key, "ssh-ed25519 AAAA...");
            assert_eq!(sign_req.data, vec![1, 2, 3]);
            assert_eq!(sign_req.flags, 0);
            assert_eq!(sign_req.caller.as_ref().map(|c| c.pid), Some(123));
        } else {
            panic!("Expected SignData request");
        }
        let response = sign_data_response(req.id, "ssh-ed25519", vec![42u8; 64]);
        server.write_response(&response).await.unwrap();
        server
    });

    let sig = client
        .sign_data("ssh-ed25519 AAAA...", &[1, 2, 3], 0, Some(caller))
        .await
        .unwrap();
    assert_eq!(sig.algorithm, "ssh-ed25519");
    assert_eq!(sig.signature, vec![42u8; 64]);

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_error_response_propagated() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        let response = error_response(req.id, 2, "vault is locked");
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.list_keys(None).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("vault is locked"),
        "Expected error message, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_id_mismatch_returns_error() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        // Respond with wrong ID.
        let response = list_keys_response(req.id + 999, vec![]);
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.list_keys(None).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("mismatch"),
        "Expected ID mismatch error, got: {}",
        err_msg
    );
    assert!(
        client.inner.stream.lock().await.is_none(),
        "an out-of-sequence response must invalidate the connection"
    );

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_empty_response_returns_error() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        // Send response with no payload.
        let response = IpcResponse {
            id: req.id,
            response: None,
        };
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.list_keys(None).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("no payload"),
        "Expected 'no payload' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

// ================================================================
// Authentication failure tests
// ================================================================

#[tokio::test]
async fn client_authenticate_rejected_token() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        assert!(matches!(
            req.request,
            Some(ipc_request::Request::Authenticate(_))
        ));
        // Respond with success=false.
        let response = IpcResponse {
            id: req.id,
            response: Some(ipc_response::Response::Authenticate(AuthenticateResponse {
                success: false,
                protocol_revision: IPC_PROTOCOL_REVISION,
            })),
        };
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.authenticate(&[0u8; 32]).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("rejected"),
        "Expected 'rejected' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_authenticate_rejects_mismatched_protocol_revision() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let server_handle = tokio::spawn(async move {
        let request = server.read_request().await.expect("authenticate request");
        let Some(ipc_request::Request::Authenticate(authenticate)) = request.request else {
            panic!("expected authenticate request");
        };
        assert_eq!(authenticate.protocol_revision, IPC_PROTOCOL_REVISION);
        server
            .write_response(&IpcResponse {
                id: request.id,
                response: Some(ipc_response::Response::Authenticate(AuthenticateResponse {
                    success: true,
                    protocol_revision: IPC_PROTOCOL_REVISION + 1,
                })),
            })
            .await
            .expect("authenticate response");
    });

    let error = client
        .authenticate(&[0u8; 32])
        .await
        .expect_err("revision mismatch")
        .to_string();
    assert!(error.contains("protocol revision mismatch"), "{error}");
    server_handle.await.expect("server task");
}

#[tokio::test]
async fn client_authenticate_wrong_response_type() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        // Respond with a ListKeys response instead of Authenticate.
        let response = list_keys_response(req.id, vec![]);
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.authenticate(&[0u8; 32]).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("Unexpected"),
        "Expected 'Unexpected' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_authenticate_error_response() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        let response = error_response(req.id, ErrorCode::AuthFailed as i32, "bad token");
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.authenticate(&[0u8; 32]).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("bad token"),
        "Expected 'bad token' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

// ================================================================
// Wrong response type for list_keys / sign_data
// ================================================================

#[tokio::test]
async fn client_list_keys_wrong_response_type() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        // Respond with an Authenticate response instead of ListKeys.
        let response = auth_ok_response(req.id);
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client.list_keys(None).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("Unexpected"),
        "Expected 'Unexpected' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_sign_data_wrong_response_type() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        // Respond with a ListKeys response instead of SignData.
        let response = list_keys_response(req.id, vec![]);
        server.write_response(&response).await.unwrap();
        server
    });

    let result = client
        .sign_data("ssh-ed25519 AAAA...", &[1, 2, 3], 0, None)
        .await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("Unexpected"),
        "Expected 'Unexpected' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

// ================================================================
// KeyProvider trait impl integration test
// ================================================================

#[tokio::test]
async fn key_provider_list_keys_delegates_to_client() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        let response = list_keys_response(
            req.id,
            vec![SshKey {
                name: "provider-key".to_string(),
                public_key: "ssh-ed25519 AAAA... provider".to_string(),
                key_type: "ssh-ed25519".to_string(),
                fingerprint: "SHA256:xyz".to_string(),
            }],
        );
        server.write_response(&response).await.unwrap();
        server
    });

    // Call via the KeyProvider trait.
    let keys = KeyProvider::list_keys(&client, None).await.unwrap();
    assert_eq!(keys.keys.len(), 1);
    assert_eq!(keys.keys[0].name, "provider-key");

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn key_provider_sign_data_delegates_to_client() {
    let (client, mut server) = make_test_client().await;

    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        if let Some(ipc_request::Request::SignData(sign_req)) = &req.request {
            assert_eq!(sign_req.public_key, "ssh-ed25519 AAAA...");
            assert_eq!(sign_req.flags, 4);
        } else {
            panic!("Expected SignData request");
        }
        let response = sign_data_response(req.id, "ssh-ed25519", vec![99u8; 64]);
        server.write_response(&response).await.unwrap();
        server
    });

    // Call via the KeyProvider trait.
    let sig = KeyProvider::sign_data(&client, "ssh-ed25519 AAAA...", &[5, 6, 7], 4, None)
        .await
        .unwrap();
    assert_eq!(sig.algorithm, "ssh-ed25519");
    assert_eq!(sig.signature, vec![99u8; 64]);

    let _ = server_handle.await.unwrap();
}

// ================================================================
// IpcStream zero-length and edge case tests
// ================================================================

#[tokio::test]
async fn ipc_stream_zero_length_message() {
    let (mut writer, mut reader) = duplex_pair();

    // Write a zero-length message (valid length prefix but empty body).
    let mut len_buf = [0u8; 4];
    (&mut len_buf[..]).put_u32(0u32);
    writer.stream.write_all(&len_buf).await.unwrap();
    writer.stream.flush().await.unwrap();

    // Reading should fail because an empty buffer can't decode a valid protobuf.
    let result = reader.read_message().await;
    // An empty protobuf message with all defaults may actually decode successfully
    // (id=0, response=None). Either outcome is acceptable.
    match result {
        Ok(resp) => {
            assert_eq!(resp.id, 0);
            assert!(resp.response.is_none());
        }
        Err(_) => {
            // Also acceptable if the decoder rejects empty input.
        }
    }
}

#[tokio::test]
async fn ipc_stream_request_id_monotonically_increases() {
    let (client, mut server) = make_test_client().await;

    // Send two list_keys requests and verify IDs increase.
    let server_handle = tokio::spawn(async move {
        let req1 = server.read_request().await.unwrap();
        server
            .write_response(&list_keys_response(req1.id, vec![]))
            .await
            .unwrap();

        let req2 = server.read_request().await.unwrap();
        server
            .write_response(&list_keys_response(req2.id, vec![]))
            .await
            .unwrap();

        assert!(
            req2.id > req1.id,
            "Request IDs should increase: {} > {}",
            req2.id,
            req1.id
        );
        server
    });

    let _ = client.list_keys(None).await.unwrap();
    let _ = client.list_keys(None).await.unwrap();

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_server_dropped_mid_request_returns_error() {
    let (client, server) = make_test_client().await;

    // Drop the server side to simulate a broken connection.
    drop(server);

    let result = client.list_keys(None).await;
    assert!(result.is_err(), "Should fail when server is dropped");
}

#[tokio::test]
async fn connect_rejects_invalid_token_length() {
    let err = match IpcClient::connect(
        Path::new("/tmp/should-not-connect.sock"),
        &[0xAA; 31],
        std::process::id(),
    )
    .await
    {
        Ok(_) => panic!("Expected invalid auth token length error"),
        Err(err) => err.to_string(),
    };
    assert!(
        err.contains("exactly 32 bytes"),
        "Expected explicit length failure, got: {}",
        err
    );
}

#[cfg(unix)]
#[tokio::test]
async fn unix_ipc_server_verification_accepts_current_process() {
    let (stream, _peer) = std::os::unix::net::UnixStream::pair().unwrap();
    stream.set_nonblocking(true).unwrap();
    let stream = tokio::net::UnixStream::from_std(stream).unwrap();

    verify_unix_ipc_server(&stream, std::process::id()).unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn unix_ipc_server_verification_rejects_wrong_parent_pid() {
    let (stream, _peer) = std::os::unix::net::UnixStream::pair().unwrap();
    stream.set_nonblocking(true).unwrap();
    let stream = tokio::net::UnixStream::from_std(stream).unwrap();
    let wrong_pid = std::process::id().wrapping_add(1).max(1);

    let error = verify_unix_ipc_server(&stream, wrong_pid)
        .unwrap_err()
        .to_string();
    assert!(error.contains("PID mismatch"), "{error}");
}

#[test]
fn reconnect_delay_is_bounded() {
    for attempt in 1..=32 {
        let delay = reconnect_delay_with_jitter(attempt);
        assert!(
            delay <= Duration::from_millis(RECONNECT_MAX_DELAY_MS),
            "delay {:?} exceeds cap for attempt {}",
            delay,
            attempt
        );
    }
}

#[tokio::test]
async fn client_reconnects_and_reauthenticates_after_transport_drop() {
    let token = vec![0x7Au8; 32];
    let (client_stream1, server_stream1) = tokio::io::duplex(64 * 1024);
    let (client_stream2, server_stream2) = tokio::io::duplex(64 * 1024);
    let client = IpcClient::from_stream_with_reconnect(
        client_stream1,
        token.clone(),
        vec![Box::new(client_stream2)],
    );

    let server_token = token.clone();
    let first_server_task = tokio::spawn(async move {
        let mut conn1 = IpcStream::new(server_stream1);
        let auth1 = conn1.read_request().await.unwrap();
        match auth1.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, server_token)
            }
            _ => panic!("Expected Authenticate request on first connection"),
        }
        conn1
            .write_response(&auth_ok_response(auth1.id))
            .await
            .unwrap();

        let first_list = conn1.read_request().await.unwrap();
        assert!(matches!(
            first_list.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        conn1
            .write_response(&list_keys_response(
                first_list.id,
                vec![SshKey {
                    name: "first-connection".to_string(),
                    public_key: "ssh-ed25519 AAAA... first".to_string(),
                    key_type: "ssh-ed25519".to_string(),
                    fingerprint: "SHA256:first".to_string(),
                }],
            ))
            .await
            .unwrap();
    });

    client.authenticate(&token).await.unwrap();
    let first = client.list_keys(None).await.unwrap();
    assert_eq!(first.keys[0].name, "first-connection");
    first_server_task.await.unwrap();

    let server_token = token.clone();
    let second_server_task = tokio::spawn(async move {
        let mut conn2 = IpcStream::new(server_stream2);
        let auth2 = conn2.read_request().await.unwrap();
        match auth2.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, server_token)
            }
            _ => panic!("Expected Authenticate request on reconnect"),
        }
        conn2
            .write_response(&auth_ok_response(auth2.id))
            .await
            .unwrap();

        let second_list = conn2.read_request().await.unwrap();
        assert!(matches!(
            second_list.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        conn2
            .write_response(&list_keys_response(
                second_list.id,
                vec![SshKey {
                    name: "reconnected".to_string(),
                    public_key: "ssh-ed25519 AAAA... second".to_string(),
                    key_type: "ssh-ed25519".to_string(),
                    fingerprint: "SHA256:second".to_string(),
                }],
            ))
            .await
            .unwrap();
    });

    let second = client.list_keys(None).await.unwrap();
    assert_eq!(second.keys[0].name, "reconnected");
    second_server_task.await.unwrap();
}

#[tokio::test]
async fn client_reconnects_after_malformed_frame() {
    let token = vec![0x51u8; 32];
    let (client_stream1, server_stream1) = tokio::io::duplex(64 * 1024);
    let (client_stream2, server_stream2) = tokio::io::duplex(64 * 1024);
    let client = IpcClient::from_stream_with_reconnect(
        client_stream1,
        token.clone(),
        vec![Box::new(client_stream2)],
    );

    let server_token = token.clone();
    let first_server_task = tokio::spawn(async move {
        let mut conn1 = IpcStream::new(server_stream1);
        let auth1 = conn1.read_request().await.unwrap();
        match auth1.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, server_token)
            }
            _ => panic!("Expected Authenticate request on first connection"),
        }
        conn1
            .write_response(&auth_ok_response(auth1.id))
            .await
            .unwrap();

        let list_req = conn1.read_request().await.unwrap();
        assert!(matches!(
            list_req.request,
            Some(ipc_request::Request::ListKeys(_))
        ));

        // Send an invalid protobuf frame and close the stream to trigger reconnect.
        let len_buf = [0, 0, 0, 1];
        conn1.stream.write_all(&len_buf).await.unwrap();
        conn1.stream.write_all(&[0xFF]).await.unwrap();
        conn1.stream.flush().await.unwrap();
    });

    let server_token = token.clone();
    let second_server_task = tokio::spawn(async move {
        let mut conn2 = IpcStream::new(server_stream2);
        let auth2 = conn2.read_request().await.unwrap();
        match auth2.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, server_token)
            }
            _ => panic!("Expected Authenticate request on reconnect"),
        }
        conn2
            .write_response(&auth_ok_response(auth2.id))
            .await
            .unwrap();

        let retry_req = conn2.read_request().await.unwrap();
        assert!(matches!(
            retry_req.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        conn2
            .write_response(&list_keys_response(
                retry_req.id,
                vec![SshKey {
                    name: "after-malformed-frame".to_string(),
                    public_key: "ssh-ed25519 AAAA... recovered".to_string(),
                    key_type: "ssh-ed25519".to_string(),
                    fingerprint: "SHA256:recovered".to_string(),
                }],
            ))
            .await
            .unwrap();
    });

    client.authenticate(&token).await.unwrap();
    let keys = client.list_keys(None).await.unwrap();
    assert_eq!(keys.keys[0].name, "after-malformed-frame");

    first_server_task.await.unwrap();
    second_server_task.await.unwrap();
}

#[tokio::test(start_paused = true)]
async fn authentication_timeout_invalidates_the_stream() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let authenticate_client = client.clone();
    let authenticate_task =
        tokio::spawn(async move { authenticate_client.authenticate(&[0x31; 32]).await });

    let request = server.read_request().await.unwrap();
    assert!(matches!(
        request.request,
        Some(ipc_request::Request::Authenticate(_))
    ));

    tokio::time::advance(IPC_AUTHENTICATION_TIMEOUT).await;
    let error = authenticate_task.await.unwrap().unwrap_err().to_string();
    assert!(error.contains("authentication timed out"), "{error}");
    assert!(
        client.inner.stream.lock().await.is_none(),
        "a stream cancelled during authentication must not be reused"
    );
}

#[tokio::test]
async fn externally_cancelled_request_invalidates_the_stream() {
    let (client, mut server) = make_test_client().await;
    let request_client = client.clone();
    let request_task = tokio::spawn(async move { request_client.list_keys(None).await });

    let request = server.read_request().await.unwrap();
    assert!(matches!(
        request.request,
        Some(ipc_request::Request::ListKeys(_))
    ));

    request_task.abort();
    assert!(request_task.await.unwrap_err().is_cancelled());
    assert!(
        client.inner.stream.lock().await.is_none(),
        "a transport cancelled mid-transaction must not be reused"
    );
    assert!(
        server.read_request().await.is_err(),
        "cancelling the request must close the in-flight transport"
    );
}

#[tokio::test(start_paused = true)]
async fn request_timeout_includes_waiting_for_the_shared_stream() {
    let (client_stream, _server_stream) = tokio::io::duplex(64 * 1024);
    let client = IpcClient::from_stream(client_stream);
    let stream_guard = client.inner.stream.lock().await;

    let waiting_client = client.clone();
    let request_task = tokio::spawn(async move { waiting_client.list_keys(None).await });
    tokio::task::yield_now().await;
    tokio::time::advance(IPC_REQUEST_TIMEOUT).await;

    let error = request_task.await.unwrap().unwrap_err().to_string();
    assert!(
        error.contains("waiting for the shared connection"),
        "{error}"
    );
    assert!(error.contains("operation was not sent"), "{error}");

    drop(stream_guard);
}

#[tokio::test(start_paused = true)]
async fn request_timeout_invalidates_without_retry_and_next_request_reconnects() {
    let token = vec![0xA7; 32];
    let (client_stream1, server_stream1) = tokio::io::duplex(64 * 1024);
    let (client_stream2, server_stream2) = tokio::io::duplex(64 * 1024);
    let client = IpcClient::from_stream_with_reconnect(
        client_stream1,
        token.clone(),
        vec![Box::new(client_stream2)],
    );

    let (timed_request_seen_tx, timed_request_seen_rx) = tokio::sync::oneshot::channel();
    let (release_first_connection_tx, release_first_connection_rx) =
        tokio::sync::oneshot::channel();
    let first_token = token.clone();
    let first_server_task = tokio::spawn(async move {
        let mut connection = IpcStream::new(server_stream1);
        let authenticate = connection.read_request().await.unwrap();
        match authenticate.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, first_token)
            }
            _ => panic!("Expected Authenticate request on first connection"),
        }
        connection
            .write_response(&auth_ok_response(authenticate.id))
            .await
            .unwrap();

        let timed_request = connection.read_request().await.unwrap();
        assert!(matches!(
            timed_request.request,
            Some(ipc_request::Request::SignData(_))
        ));
        timed_request_seen_tx.send(()).unwrap();

        // Keep the original connection open without replying. This makes
        // the client deadline, rather than EOF, end the transaction.
        let _ = release_first_connection_rx.await;
    });

    let second_token = token.clone();
    let second_server_task = tokio::spawn(async move {
        let mut connection = IpcStream::new(server_stream2);
        let authenticate = connection.read_request().await.unwrap();
        match authenticate.request {
            Some(ipc_request::Request::Authenticate(auth)) => {
                assert_eq!(auth.token, second_token)
            }
            _ => panic!("Expected Authenticate request on replacement connection"),
        }
        connection
            .write_response(&auth_ok_response(authenticate.id))
            .await
            .unwrap();

        // The next request after reconnect must be the caller's new
        // ListKeys operation, never a replay of the ambiguous SignData.
        let next_request = connection.read_request().await.unwrap();
        assert!(matches!(
            next_request.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        connection
            .write_response(&list_keys_response(
                next_request.id,
                vec![SshKey {
                    name: "after-timeout".to_string(),
                    public_key: "ssh-ed25519 AAAA... after-timeout".to_string(),
                    key_type: "ssh-ed25519".to_string(),
                    fingerprint: "SHA256:after-timeout".to_string(),
                }],
            ))
            .await
            .unwrap();
    });

    client.authenticate(&token).await.unwrap();

    let timed_client = client.clone();
    let timed_request_task = tokio::spawn(async move {
        timed_client
            .sign_data("ssh-ed25519 AAAA...", &[1, 2, 3], 0, None)
            .await
    });
    timed_request_seen_rx.await.unwrap();
    tokio::time::advance(IPC_REQUEST_TIMEOUT).await;

    let error = timed_request_task.await.unwrap().unwrap_err().to_string();
    assert!(error.contains("request timed out"), "{error}");
    assert!(error.contains("operation was not retried"), "{error}");
    assert!(
        client.inner.stream.lock().await.is_none(),
        "timed-out transport must remain invalid until the next request reconnects"
    );

    let next_response = client.list_keys(None).await.unwrap();
    assert_eq!(next_response.keys[0].name, "after-timeout");

    let _ = release_first_connection_tx.send(());
    first_server_task.await.unwrap();
    second_server_task.await.unwrap();
}
