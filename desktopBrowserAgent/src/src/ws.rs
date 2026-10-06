//! WebSocket server that exposes the browser agent to the Keyguard extension.
//!
//! The server binds `127.0.0.1:<port>` only. Each extension connection performs
//! an ephemeral X25519 handshake, after which every frame is an AES-256-GCM
//! envelope carrying a [`ClientRequest`]/`AgentResponse`. Requests are bridged
//! to the Keyguard desktop app over the IPC channel.
//!
//! For the Safari (WS) path, HMAC challenge-response authentication is performed
//! after ECDH. The agent sends a random 32-byte challenge; the extension signs
//! it with HMAC-SHA256 using the shared secret established during pairing, and
//! the agent verifies against the stored secret.

use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Notify;

use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use futures::{SinkExt, StreamExt};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, error, info, warn};

use crate::crypto::{aes_decrypt, aes_encrypt, KeyAgreement};
use crate::ipc::{open_transport, JsonIpcClient};
use crate::pairing::{compute_hmac, generate_challenge};
use crate::protocol::{
    AgentResponse, ClientRequest, QueryResult, SecretResult, WsClientHello, WsEnvelope,
    WsServerHello,
};

/// Reads the stored shared secret (raw 32 bytes) from the given hex file.
fn load_secret(path: &std::path::Path) -> Result<[u8; 32]> {
    let hex_str = std::fs::read_to_string(path)
        .with_context(|| format!("Failed to read secret from {}", path.display()))?;
    let bytes = hex::decode(hex_str.trim())
        .with_context(|| format!("Secret in {} is not valid hex", path.display()))?;
    if bytes.len() != 32 {
        anyhow::bail!("Secret must be 32 bytes, got {} bytes", bytes.len());
    }
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&bytes);
    Ok(secret)
}

/// Starts the WebSocket server. Blocks until [shutdown] is signalled.
pub async fn serve(
    listen_port: u16,
    ipc_socket: PathBuf,
    auth_token: Vec<u8>,
    parent_pid: u32,
    shutdown: Arc<Notify>,
    secret_path: Option<PathBuf>,
) -> Result<()> {
    let addr = format!("127.0.0.1:{listen_port}");
    let listener = TcpListener::bind(&addr)
        .await
        .with_context(|| format!("Failed to bind WebSocket server on {addr}"))?;
    info!(address = %addr, "Browser agent WebSocket server listening");
    let ipc_socket = Arc::new(ipc_socket);

    // Load the shared secret if available (Safari HMAC path).
    let shared_secret = match secret_path {
        Some(path) => match load_secret(&path) {
            Ok(s) => {
                info!("Loaded shared secret for HMAC verification");
                Some(s)
            }
            Err(e) => {
                warn!(error = %e, "Could not load shared secret — HMAC verification disabled");
                None
            }
        },
        None => {
            info!("No secret path provided — HMAC verification disabled");
            None
        }
    };

    loop {
        tokio::select! {
            _ = shutdown.notified() => {
                info!("Shutdown signal received; stopping WebSocket server");
                break;
            }
            accepted = listener.accept() => {
                let (stream, peer) = accepted
                    .with_context(|| format!("Failed to accept WebSocket connection on {addr}"))?;
                debug!(peer = %peer, "Accepted WebSocket connection");
                let ipc_socket = ipc_socket.clone();
                let token = auth_token.clone();
                let secret = shared_secret;
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(stream, &ipc_socket, &token, parent_pid, secret).await {
                        warn!(error = %e, "WebSocket connection ended with error");
                    }
                });
            }
        }
    }

    info!("WebSocket server stopped");
    Ok(())
}

async fn handle_connection(
    stream: tokio::net::TcpStream,
    ipc_socket: &std::path::Path,
    auth_token: &[u8],
    parent_pid: u32,
    shared_secret: Option<[u8; 32]>,
) -> Result<()> {
    use tokio_tungstenite::accept_async;
    let ws_stream = accept_async(stream)
        .await
        .context("WebSocket handshake failed")?;
    let (mut writer, mut reader) = ws_stream.split();

    // --- Ephemeral ECDH handshake ---
    let hello_text = reader
        .next()
        .await
        .ok_or_else(|| anyhow!("Connection closed before handshake"))??
        .into_text()
        .map_err(|e| anyhow!("Handshake message not text: {e}"))?;
    let client_hello: WsClientHello = serde_json::from_str(&hello_text)
        .context("Failed to parse client hello")?;

    let agreement = KeyAgreement::generate();
    let public_key = agreement.public_key_base64();
    let key = agreement
        .derive_key(&client_hello.public_key, &client_hello.client_id)
        .context("ECDH key derivation failed")?;

    let server_hello = WsServerHello { public_key };
    writer
        .send(Message::Text(serde_json::to_string(&server_hello)?))
        .await?;

    // --- HMAC challenge-response (Safari path) ---
    if let Some(secret) = shared_secret {
        let challenge = generate_challenge();
        let challenge_b64 = B64.encode(&challenge);

        let resp = AgentResponse::HmacChallenge {
            challenge: challenge_b64,
        };
        writer
            .send(Message::Text(serde_json::to_string(&resp)?))
            .await?;

        // Wait for the HMAC response from the extension.
        let hmac_msg = reader
            .next()
            .await
            .ok_or_else(|| anyhow!("Connection closed before HMAC response"))??
            .into_text()
            .map_err(|e| anyhow!("HMAC response not text: {e}"))?;
        let envelope: WsEnvelope = serde_json::from_str(&hmac_msg)
            .map_err(|e| anyhow!("Failed to parse HMAC envelope: {e}"))?;
        let plaintext = aes_decrypt(&key, &envelope.nonce, &envelope.payload)?;
        let request: ClientRequest = serde_json::from_slice(&plaintext)
            .map_err(|e| anyhow!("Failed to parse HMAC request: {e}"))?;

        match request {
            ClientRequest::HmacResponse { response } => {
                let response_bytes = B64.decode(&response)
                    .map_err(|e| anyhow!("Invalid HMAC response base64: {e}"))?;
                if response_bytes.len() != 32 {
                    anyhow::bail!("HMAC response must be 32 bytes, got {}", response_bytes.len());
                }
                let mut hmac_received = [0u8; 32];
                hmac_received.copy_from_slice(&response_bytes);

                // Verify: compute HMAC locally and compare.
                let expected = compute_hmac(&secret, &challenge);
                if hmac_received == expected {
                    info!("HMAC verification succeeded");
                    let ok = AgentResponse::HmacOk;
                    writer
                        .send(Message::Text(serde_json::to_string(&ok)?))
                        .await?;
                } else {
                    warn!("HMAC verification failed");
                    let fail = AgentResponse::HmacFailed;
                    writer
                        .send(Message::Text(serde_json::to_string(&fail)?))
                        .await?;
                    anyhow::bail!("HMAC verification failed");
                }
            }
            _ => {
                anyhow::bail!("Expected HmacResponse as first message after challenge");
            }
        }
    }

    // --- Bridge to the desktop app for the lifetime of this connection ---
    let transport = open_transport(ipc_socket, parent_pid)
        .await
        .context("Failed to connect to Keyguard IPC")?;
    let ipc = JsonIpcClient::connect(transport, auth_token)
        .await
        .context("Failed to connect to Keyguard IPC")?;

    while let Some(msg) = reader.next().await {
        let msg = msg?;
        let text = match msg {
            Message::Text(t) => t,
            Message::Close(_) => break,
            _ => continue,
        };
        let envelope: WsEnvelope = serde_json::from_str(&text)
            .map_err(|e| anyhow!("Failed to parse envelope: {e}"))?;
        let plaintext = aes_decrypt(&key, &envelope.nonce, &envelope.payload)?;
        let request: ClientRequest = serde_json::from_slice(&plaintext)
            .map_err(|e| anyhow!("Failed to parse request: {e}"))?;

        let response = handle_request(&ipc, request).await;
        let response_json = serde_json::to_vec(&response)?;
        let (nonce, payload) = aes_encrypt(&key, &response_json)?;
        let reply = WsEnvelope { nonce, payload };
        writer
            .send(Message::Text(serde_json::to_string(&reply)?))
            .await?;
    }

    writer.close().await.ok();
    info!("WebSocket connection closed");
    Ok(())
}

async fn handle_request(ipc: &JsonIpcClient<impl AsyncReadWrite>, request: ClientRequest) -> AgentResponse {
    match request {
        ClientRequest::Query { domain, uri } => match ipc.query(&domain, uri.as_deref()).await {
            Ok(result) => AgentResponse::Query(result),
            Err(e) => {
                error!(error = %e, "Query failed");
                AgentResponse::Query(QueryResult {
                    locked: false,
                    items: Vec::new(),
                })
            }
        },
        ClientRequest::Secret { item_id } => match ipc.secret(&item_id).await {
            Ok(result) => AgentResponse::Secret(result),
            Err(e) => {
                error!(error = %e, "Secret fetch failed");
                AgentResponse::Secret(SecretResult::default())
            }
        },
        ClientRequest::RequestForeground { .. } => match ipc.request_foreground().await {
            Ok(_success) => AgentResponse::RequestForeground { success: true },
            Err(e) => {
                error!(error = %e, "RequestForeground failed");
                AgentResponse::RequestForeground { success: false }
            }
        },
        // HMAC response is handled during the handshake phase, not here.
        ClientRequest::HmacResponse { .. } => {
            AgentResponse::HmacFailed
        }
    }
}

/// Convenience alias for the IPC transport bounds.
trait AsyncReadWrite: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}
impl<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send> AsyncReadWrite for T {}
