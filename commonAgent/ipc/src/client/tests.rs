//! Tests of the client over the SSH agent's messages, with a duplex stream
//! in place of the app's server.

use super::*;
use common_ssh_agent_rust::messages::{
    AuthenticateResponse, ErrorCode, IpcRequest, IpcResponse, ListKeysRequest, ListKeysResponse,
    SignDataRequest, SshKey, ipc_request, ipc_response,
};
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use std::collections::VecDeque;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, DuplexStream};

crate::envelope_protocol! {
    /// The SSH agent's protocol, as `keyguard-ssh-agent` defines it.
    struct SshIpc;
    messages = common_ssh_agent_rust::messages;
    retry_safe = ListKeys;
}

type IpcClient = Client<SshIpc>;

impl<P: Protocol> Client<P> {
    /// A client of `stream` that never reconnects.
    fn from_stream(stream: impl stream::Stream + 'static) -> Self {
        Self {
            inner: Arc::new(Inner {
                stream: Mutex::new(Some(Box::new(stream))),
                next_id: AtomicU64::new(1),
                reconnect: None,
                protocol: PhantomData,
            }),
        }
    }

    /// A client of `stream` that reconnects to `reconnect_streams` in turn.
    fn from_stream_with_reconnect(
        stream: impl stream::Stream + 'static,
        auth_token: Vec<u8>,
        reconnect_streams: Vec<BoxStream>,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                stream: Mutex::new(Some(Box::new(stream))),
                next_id: AtomicU64::new(1),
                reconnect: Some(Reconnect {
                    connector: Box::new(QueuedStreams(std::sync::Mutex::new(
                        reconnect_streams.into_iter().collect(),
                    ))),
                    token: test_auth_token(&auth_token),
                }),
                protocol: PhantomData,
            }),
        }
    }
}

/// A ListKeys request without a caller.
fn list_keys() -> ipc_request::Request {
    ipc_request::Request::ListKeys(ListKeysRequest { caller: None })
}

/// The name of the first key that `reply` lists.
fn first_key_name(reply: ipc_response::Response) -> String {
    match reply {
        ipc_response::Response::ListKeys(mut keys) => keys.keys.swap_remove(0).name,
        reply => panic!("expected a key list, got {reply:?}"),
    }
}

/// Hands out the next of its streams on each connect.
struct QueuedStreams(std::sync::Mutex<VecDeque<BoxStream>>);

impl Connect for QueuedStreams {
    fn connect(&self) -> BoxFuture<'_, Result<BoxStream>> {
        let next = self
            .0
            .lock()
            .map_err(|_| anyhow!("Test reconnect stream queue is poisoned"))
            .and_then(|mut streams| {
                streams
                    .pop_front()
                    .context("No test reconnect streams remaining")
            });
        Box::pin(async move { next })
    }
}

/// The app's side of a connection.
struct IpcStream<S> {
    stream: S,
}

impl<S: AsyncRead + AsyncWrite + Unpin> IpcStream<S> {
    fn new(stream: S) -> Self {
        Self { stream }
    }

    async fn read_request(&mut self) -> Result<IpcRequest> {
        let bytes = read_frame(&mut self.stream, MAX_AGENT_FRAME).await?;
        Ok(IpcRequest::decode(&bytes[..])?)
    }

    async fn write_response(&mut self, msg: &IpcResponse) -> Result<()> {
        write_frame(&mut self.stream, &msg.encode_to_vec(), MAX_AGENT_FRAME).await
    }
}

/// The launch token of `bytes`, which must be 32 of them.
fn test_auth_token(bytes: &[u8]) -> AuthToken {
    AuthToken::from_bytes(bytes.try_into().expect("a 32-byte test token"))
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

/// Helper: builds an error response.
fn error_response(id: u64, code: i32, message: &str) -> IpcResponse {
    IpcResponse {
        id,
        response: Some(ipc_response::Response::Error(ErrorResponse {
            code,
            message: message.to_string(),
        })),
    }
}

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

    client
        .authenticate(&test_auth_token(&[0u8; 32]))
        .await
        .unwrap();
    let server = handle.await.unwrap();

    (client, server)
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

    let result = client.request(list_keys()).await;
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
async fn oversize_request_is_not_sent_and_keeps_the_connection() {
    let (client, mut server) = make_test_client().await;

    let error = client
        .request(ipc_request::Request::SignData(SignDataRequest {
            public_key: "ssh-ed25519 AAAA...".to_string(),
            data: vec![0; MAX_AGENT_FRAME],
            flags: 0,
            caller: None,
        }))
        .await
        .unwrap_err();
    assert!(error.is::<crate::frame::InvalidFrameSize>(), "{error:#}");
    assert!(error.to_string().contains("not sent"), "{error:#}");

    // The next request travels over the same connection, so the server reads
    // it as the first request after authentication.
    let server_handle = tokio::spawn(async move {
        let req = server.read_request().await.unwrap();
        assert!(matches!(
            req.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        let key = SshKey {
            name: "Key".to_string(),
            ..Default::default()
        };
        server
            .write_response(&list_keys_response(req.id, vec![key]))
            .await
            .unwrap();
    });
    assert_eq!(
        first_key_name(client.request(list_keys()).await.unwrap()),
        "Key"
    );
    server_handle.await.unwrap();
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

    let result = client.request(list_keys()).await;
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

    let result = client.request(list_keys()).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("no payload"),
        "Expected 'no payload' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
}

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

    let result = client.authenticate(&test_auth_token(&[0u8; 32])).await;
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
        .authenticate(&test_auth_token(&[0u8; 32]))
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

    let result = client.authenticate(&test_auth_token(&[0u8; 32])).await;
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

    let result = client.authenticate(&test_auth_token(&[0u8; 32])).await;
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("bad token"),
        "Expected 'bad token' error, got: {}",
        err_msg
    );

    let _ = server_handle.await.unwrap();
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

    let _ = client.request(list_keys()).await.unwrap();
    let _ = client.request(list_keys()).await.unwrap();

    let _ = server_handle.await.unwrap();
}

#[tokio::test]
async fn client_server_dropped_mid_request_returns_error() {
    let (client, server) = make_test_client().await;

    // Drop the server side to simulate a broken connection.
    drop(server);

    let result = client.request(list_keys()).await;
    assert!(result.is_err(), "Should fail when server is dropped");
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

    client.authenticate(&test_auth_token(&token)).await.unwrap();
    let first = client.request(list_keys()).await.unwrap();
    assert_eq!(first_key_name(first), "first-connection");
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

    let second = client.request(list_keys()).await.unwrap();
    assert_eq!(first_key_name(second), "reconnected");
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

    client.authenticate(&test_auth_token(&token)).await.unwrap();
    let keys = client.request(list_keys()).await.unwrap();
    assert_eq!(first_key_name(keys), "after-malformed-frame");

    first_server_task.await.unwrap();
    second_server_task.await.unwrap();
}

#[tokio::test(start_paused = true)]
async fn authentication_timeout_invalidates_the_stream() {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);
    let client = IpcClient::from_stream(client_stream);

    let authenticate_client = client.clone();
    let authenticate_task = tokio::spawn(async move {
        authenticate_client
            .authenticate(&test_auth_token(&[0x31; 32]))
            .await
    });

    let request = server.read_request().await.unwrap();
    assert!(matches!(
        request.request,
        Some(ipc_request::Request::Authenticate(_))
    ));

    tokio::time::advance(AUTHENTICATION_TIMEOUT).await;
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
    let request_task = tokio::spawn(async move { request_client.request(list_keys()).await });

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
    let request_task = tokio::spawn(async move { waiting_client.request(list_keys()).await });
    tokio::task::yield_now().await;
    tokio::time::advance(REQUEST_TIMEOUT).await;

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

    client.authenticate(&test_auth_token(&token)).await.unwrap();

    let timed_client = client.clone();
    let timed_request_task = tokio::spawn(async move {
        timed_client
            .request(ipc_request::Request::SignData(SignDataRequest {
                public_key: "ssh-ed25519 AAAA...".to_string(),
                data: vec![1, 2, 3],
                flags: 0,
                caller: None,
            }))
            .await
    });
    timed_request_seen_rx.await.unwrap();
    tokio::time::advance(REQUEST_TIMEOUT).await;

    let error = timed_request_task.await.unwrap().unwrap_err().to_string();
    assert!(error.contains("request timed out"), "{error}");
    assert!(error.contains("operation was not retried"), "{error}");
    assert!(
        client.inner.stream.lock().await.is_none(),
        "timed-out transport must remain invalid until the next request reconnects"
    );

    let next_response = client.request(list_keys()).await.unwrap();
    assert_eq!(first_key_name(next_response), "after-timeout");

    let _ = release_first_connection_tx.send(());
    first_server_task.await.unwrap();
    second_server_task.await.unwrap();
}
