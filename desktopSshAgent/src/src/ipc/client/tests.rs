//! Tests of the SSH agent's requests over the shared client, with a duplex
//! stream in place of the app's server. The client's own transport,
//! reconnect and timeout tests live in `keyguard-agent-ipc`.

use super::*;
use crate::ipc::messages::{AuthenticateResponse, IpcRequest, IpcResponse, SshKey};
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_agent_ipc::client::{BoxFuture, Connect};
use keyguard_agent_ipc::frame::{read_frame, write_frame, MAX_AGENT_FRAME};
use keyguard_agent_ipc::stream::BoxStream;
use prost::Message;
use tokio::io::{AsyncRead, AsyncWrite, DuplexStream};

/// Hands out its one stream on the first connect.
struct OneStream(std::sync::Mutex<Option<BoxStream>>);

impl Connect for OneStream {
    fn connect(&self) -> BoxFuture<'_, Result<BoxStream>> {
        let stream = self.0.lock().unwrap().take();
        Box::pin(async move { stream.ok_or_else(|| anyhow::anyhow!("No test stream remaining")) })
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

/// Creates an `IpcClient` backed by one half of a duplex, returning
/// the other half so the test can act as the server, which has answered
/// the initial authenticate request.
async fn make_test_client() -> (IpcClient, IpcStream<DuplexStream>) {
    let (client_stream, server_stream) = tokio::io::duplex(64 * 1024);
    let mut server = IpcStream::new(server_stream);

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

    let connector = OneStream(std::sync::Mutex::new(Some(Box::new(client_stream))));
    let client = IpcClient(
        Client::connect(connector, AuthToken::from_bytes([0u8; 32]))
            .await
            .unwrap(),
    );
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
