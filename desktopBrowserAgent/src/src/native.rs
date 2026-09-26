//! Native Messaging server for Firefox/Chrome/Edge.
//!
//! Communicates with the browser extension over stdin/stdout using a 4-byte
//! little-endian length prefix followed by a UTF-8 JSON payload.
//!
//! No HMAC authentication is needed — the browser already verifies the
//! extension ID via `allowed_extensions` in the native messaging manifest.
//! The browser itself is the authentication mechanism.

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{anyhow, Context, Result};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::Notify;
use tracing::{debug, error, info, warn};

use crate::ipc::{open_transport, JsonIpcClient};
use crate::protocol::{
    AgentResponse, ClientRequest, QueryResult, SecretResult,
};

/// Maximum NM message size (64 MB per Chrome/Firefox spec).
const MAX_NM_MESSAGE_SIZE: usize = 64 * 1024 * 1024;

/// Reads a length-prefixed JSON message from stdin.
/// Format: 4-byte LE length + UTF-8 JSON.
async fn read_nm_message(reader: &mut tokio::io::BufReader<tokio::io::Stdin>) -> Result<String> {
    let mut len_buf = [0u8; 4];
    reader.read_exact(&mut len_buf).await
        .context("Failed to read NM message length")?;
    let len = u32::from_le_bytes(len_buf) as usize;
    if len > MAX_NM_MESSAGE_SIZE {
        anyhow::bail!("NM message too large: {len} bytes (max {MAX_NM_MESSAGE_SIZE})");
    }
    let mut buf = vec![0u8; len];
    reader.read_exact(&mut buf).await
        .context("Failed to read NM message body")?;
    String::from_utf8(buf)
        .map_err(|e| anyhow!("NM message is not valid UTF-8: {e}"))
}

/// Writes a length-prefixed JSON message to stdout.
/// Format: 4-byte LE length + UTF-8 JSON.
async fn write_nm_message(writer: &mut tokio::io::Stdout, msg: &str) -> Result<()> {
    let len = u32::try_from(msg.len())
        .context("NM message too large for length prefix")?;
    writer.write_all(&len.to_le_bytes()).await
        .context("Failed to write NM message length")?;
    writer.write_all(msg.as_bytes()).await
        .context("Failed to write NM message body")?;
    writer.flush().await
        .context("Failed to flush NM stdout")?;
    Ok(())
}

/// Starts the Native Messaging server. Blocks until [shutdown] is signalled.
///
/// In NM mode, the agent reads from stdin and writes to stdout. The browser
/// keeps stdin open as a liveness signal — when the extension disconnects,
/// stdin closes and the agent shuts down.
pub async fn serve(
    ipc_socket: PathBuf,
    auth_token: Vec<u8>,
    parent_pid: u32,
    shutdown: Arc<Notify>,
) -> Result<()> {
    info!("Native Messaging server starting");

    let mut reader = tokio::io::BufReader::new(tokio::io::stdin());
    let mut stdout = tokio::io::stdout();

    // Open IPC connection to the desktop app.
    let transport = open_transport(&ipc_socket, parent_pid)
        .await
        .context("Failed to connect to Keyguard IPC")?;
    let ipc = JsonIpcClient::connect(transport, &auth_token)
        .await
        .context("Failed to connect to Keyguard IPC")?;

    info!("Native Messaging IPC connected");

    // Read and process messages until stdin closes or shutdown is signalled.
    loop {
        tokio::select! {
            _ = shutdown.notified() => {
                info!("Shutdown signal received; stopping NM server");
                break;
            }
            msg = read_nm_message(&mut reader) => {
                match msg {
                    Ok(text) => {
                        debug!(len = text.len(), "Received NM message");

                        let request: ClientRequest = match serde_json::from_str(&text) {
                            Ok(r) => r,
                            Err(e) => {
                                warn!(error = %e, "Failed to parse NM message as ClientRequest");
                                let err_resp = AgentResponse::Query(QueryResult {
                                    locked: false,
                                    items: Vec::new(),
                                });
                                write_nm_message(&mut stdout, &serde_json::to_string(&err_resp)?).await?;
                                continue;
                            }
                        };

                        let response = handle_request(&ipc, request).await;
                        let resp_json = serde_json::to_string(&response)?;
                        write_nm_message(&mut stdout, &resp_json).await?;
                    }
                    Err(e) => {
                        // stdin closed or read error — normal shutdown.
                        info!(error = %e, "NM stdin closed or read error, shutting down");
                        break;
                    }
                }
            }
        }
    }

    info!("Native Messaging server stopped");
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
        ClientRequest::RequestForeground { token } => {
            // Read XDG_ACTIVATION_TOKEN from the process environment if the
            // extension did not provide one explicitly.
            let effective_token = token.or_else(|| {
                std::env::var("XDG_ACTIVATION_TOKEN").ok().filter(|v| !v.is_empty())
            });
            match ipc.request_foreground_with_token(effective_token).await {
                Ok(_success) => AgentResponse::RequestForeground { success: true },
                Err(e) => {
                    error!(error = %e, "RequestForeground failed");
                    AgentResponse::RequestForeground { success: false }
                }
            }
        }
        ClientRequest::HmacResponse { .. } => {
            // HMAC is not used in NM mode.
            AgentResponse::HmacFailed
        }
    }
}

/// Convenience alias for the IPC transport bounds.
trait AsyncReadWrite: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}
impl<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send> AsyncReadWrite for T {}
