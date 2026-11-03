//! Tests of the GPG agent's requests over the shared client, with duplex
//! streams in place of the app's server.

use super::*;
use anyhow::Context;
use common_gpg_agent_rust::messages::{
    AuthenticateResponse, ErrorCode, ErrorResponse, GpgKey, IpcRequest, IpcResponse,
    ListKeysResponse,
};
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_agent_ipc::error::IpcError;
use keyguard_agent_ipc::frame::{read_frame, write_frame, MAX_AGENT_FRAME};
use keyguard_agent_ipc::stream::BoxStream;
use prost::Message;
use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::Duration;
use tokio::io::{duplex, DuplexStream};

const TOKEN: [u8; 32] = [0x47; 32];

/// Hands out the next of its streams on each connect.
struct QueuedStreams(Mutex<VecDeque<BoxStream>>);

impl QueuedStreams {
    /// Connections whose app sides are returned, in the order the client opens them.
    fn new(count: usize) -> (Self, Vec<DuplexStream>) {
        let (clients, apps): (VecDeque<BoxStream>, Vec<DuplexStream>) = (0..count)
            .map(|_| {
                let (client, app) = duplex(64 * 1024);
                (Box::new(client) as BoxStream, app)
            })
            .unzip();
        (Self(Mutex::new(clients)), apps)
    }
}

impl Connect for QueuedStreams {
    fn connect(&self) -> BoxFuture<'_, Result<BoxStream>> {
        let next = self.0.lock().unwrap().pop_front();
        Box::pin(async move { next.context("No test stream remaining") })
    }
}

async fn read_request(app: &mut DuplexStream) -> Result<IpcRequest> {
    let bytes = read_frame(app, MAX_AGENT_FRAME).await?;
    Ok(IpcRequest::decode(&bytes[..])?)
}

async fn reply(app: &mut DuplexStream, id: u64, response: ipc_response::Response) {
    let response = IpcResponse {
        id,
        response: Some(response),
    };
    write_frame(app, &response.encode_to_vec(), MAX_AGENT_FRAME)
        .await
        .unwrap();
}

/// Answers the authenticate request that opens every connection.
async fn accept(app: &mut DuplexStream) {
    let request = read_request(app).await.unwrap();
    let Some(ipc_request::Request::Authenticate(authenticate)) = request.request else {
        panic!("expected an authenticate request, got {request:?}");
    };
    assert_eq!(authenticate.token, TOKEN);
    let accepted = AuthenticateResponse {
        success: true,
        protocol_revision: IPC_PROTOCOL_REVISION,
    };
    reply(
        app,
        request.id,
        ipc_response::Response::Authenticate(accepted),
    )
    .await;
}

async fn connected(count: usize) -> (IpcClient, Vec<DuplexStream>) {
    let (connector, mut apps) = QueuedStreams::new(count);
    let mut first = apps.remove(0);
    let app = tokio::spawn(async move {
        accept(&mut first).await;
        first
    });
    let client = connect_with(connector, AuthToken::from_bytes(TOKEN))
        .await
        .unwrap();
    apps.insert(0, app.await.unwrap());
    (client, apps)
}

#[tokio::test]
async fn listing_keys_reconnects_after_the_app_drops_an_idle_connection() {
    let (client, mut apps) = connected(2).await;
    // The app closes the idle connection, as the macOS server does.
    drop(apps.remove(0));
    let mut second = apps.remove(0);
    let app = tokio::spawn(async move {
        accept(&mut second).await;
        let request = read_request(&mut second).await.unwrap();
        assert!(matches!(
            request.request,
            Some(ipc_request::Request::ListKeys(_))
        ));
        let keys = ListKeysResponse {
            keys: vec![GpgKey {
                name: "after-reconnect".into(),
                ..GpgKey::default()
            }],
        };
        reply(
            &mut second,
            request.id,
            ipc_response::Response::ListKeys(keys),
        )
        .await;
    });

    // The IDs on the wire differ from the Assuan layer's after the reconnect.
    let keys = client.list_keys(None).await.unwrap();
    assert_eq!(keys.keys[0].name, "after-reconnect");
    app.await.unwrap();
}

#[tokio::test]
async fn a_signature_whose_answer_was_lost_is_not_sent_again() {
    let (client, mut apps) = connected(2).await;
    let mut first = apps.remove(0);
    let mut second = apps.remove(0);
    let app = tokio::spawn(async move {
        let request = read_request(&mut first).await.unwrap();
        assert!(matches!(
            request.request,
            Some(ipc_request::Request::SignHash(_))
        ));
        // The connection breaks before the app answers.
        drop(first);
        // The client repairs the connection, but must not sign again.
        accept(&mut second).await;
        second
    });

    // A replay would wait for an answer that never comes.
    let signed = client.sign_hash("keygrip", "sha256", &[1; 32], None);
    let error = tokio::time::timeout(Duration::from_secs(10), signed)
        .await
        .expect("the signature was sent again")
        .unwrap_err()
        .to_string();
    assert!(error.contains("operation was not retried"), "{error}");
    let mut second = app.await.unwrap();
    drop(client);
    assert!(read_request(&mut second).await.is_err());
}

#[tokio::test]
async fn an_error_of_the_app_keeps_its_code() {
    let (client, mut apps) = connected(1).await;
    let mut first = apps.remove(0);
    let app = tokio::spawn(async move {
        let request = read_request(&mut first).await.unwrap();
        let denied = ErrorResponse {
            message: "denied".into(),
            code: ErrorCode::UserDenied.into(),
        };
        reply(
            &mut first,
            request.id,
            ipc_response::Response::Error(denied),
        )
        .await;
        first
    });

    let error = client
        .pkdecrypt("keygrip", b"(enc-val)", false, None)
        .await
        .unwrap_err();
    let error = error.downcast_ref::<IpcError>().expect("a typed IPC error");
    assert_eq!(error.code(), ErrorCode::UserDenied);
    app.await.unwrap();
}
