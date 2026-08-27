//! JSON-framed IPC client that connects to the Keyguard desktop application
//! over a Unix domain socket (macOS/Linux) or named pipe (Windows).
//!
//! Framing: a 4-byte big-endian length prefix followed by a UTF-8 JSON
//! `IpcRequest`/`IpcResponse`. The agent authenticates with the shared token
//! (hex, read from stdin) immediately after connecting.

use anyhow::{bail, Context, Result};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tracing::error;

use crate::protocol::{IpcRequest, IpcResponse, QueryResult, SecretResult};

const MAX_MESSAGE_SIZE: usize = 16 * 1024 * 1024;

/// Client for the agent <-> desktop IPC channel, generic over the transport.
pub struct JsonIpcClient<S> {
    stream: tokio::sync::Mutex<S>,
}

impl<S> JsonIpcClient<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    /// Connects to the IPC endpoint and authenticates with `token` (raw 32 bytes).
    pub async fn connect(
        stream: S,
        token: &[u8],
    ) -> Result<Self> {
        if token.len() != 32 {
            bail!("IPC auth token must be 32 bytes, got {}", token.len());
        }
        let client = Self {
            stream: tokio::sync::Mutex::new(stream),
        };
        client
            .authenticate(&hex::encode(token))
            .await
            .context("IPC authentication failed")?;
        Ok(client)
    }

    async fn authenticate(&self, token_hex: &str) -> Result<()> {
        let resp = self
            .request(IpcRequest::Authenticate {
                token: token_hex.to_string(),
            })
            .await?;
        match resp {
            IpcResponse::Authenticate { success } if success => Ok(()),
            IpcResponse::Authenticate { .. } => bail!("IPC server rejected the auth token"),
            other => bail!("Unexpected IPC response to Authenticate: {other:?}"),
        }
    }

    async fn request(&self, req: IpcRequest) -> Result<IpcResponse> {
        let mut stream = self.stream.lock().await;
        let body = serde_json::to_vec(&req).context("Failed to serialize IPC request")?;
        let len = u32::try_from(body.len()).context("IPC message too large")?;
        stream.write_all(&len.to_be_bytes()).await?;
        stream.write_all(&body).await?;
        stream.flush().await?;

        let mut len_buf = [0u8; 4];
        stream.read_exact(&mut len_buf).await?;
        let len = u32::from_be_bytes(len_buf) as usize;
        if len > MAX_MESSAGE_SIZE {
            bail!("IPC message too large: {len} bytes");
        }
        let mut buf = vec![0u8; len];
        stream.read_exact(&mut buf).await?;
        match serde_json::from_slice::<IpcResponse>(&buf) {
            Ok(resp) => Ok(resp),
            Err(e) => {
                let preview = String::from_utf8_lossy(&buf);
                error!(
                    "Failed to parse IPC response: {e}; len={} preview={:?}",
                    buf.len(),
                    preview
                );
                return Err(e).context("Failed to parse IPC response");
            }
        }
    }

    /// Queries matching logins for a domain.
    pub async fn query(&self, domain: &str, uri: Option<&str>) -> Result<QueryResult> {
        match self
            .request(IpcRequest::Query {
                domain: domain.to_string(),
                uri: uri.map(str::to_string),
            })
            .await?
        {
            IpcResponse::Query(result) => Ok(result),
            other => bail!("Unexpected IPC response to Query: {other:?}"),
        }
    }

    /// Fetches the secret for a previously listed item.
    pub async fn secret(&self, item_id: &str) -> Result<SecretResult> {
        match self
            .request(IpcRequest::Secret {
                item_id: item_id.to_string(),
            })
            .await?
        {
            IpcResponse::Secret(result) => Ok(result),
            other => bail!("Unexpected IPC response to Secret: {other:?}"),
        }
    }

    /// Asks the desktop app to bring its window to the foreground.
    pub async fn request_foreground(&self) -> Result<bool> {
        self.request_foreground_with_token(None).await
    }

    /// Asks the desktop app to bring its window to the foreground,
    /// passing an optional XDG activation token for Wayland.
    pub async fn request_foreground_with_token(&self, token: Option<String>) -> Result<bool> {
        match self
            .request(IpcRequest::RequestForeground { token })
            .await?
        {
            IpcResponse::RequestForeground { success } => Ok(success),
            other => bail!("Unexpected IPC response to RequestForeground: {other:?}"),
        }
    }
}

/// Opens the platform IPC transport for the given socket path.
#[cfg(unix)]
pub async fn open_transport(
    socket_path: &std::path::Path,
    _expected_parent_pid: u32,
) -> Result<tokio::net::UnixStream> {
    tokio::net::UnixStream::connect(socket_path)
        .await
        .with_context(|| format!("Failed to connect to IPC socket {}", socket_path.display()))
}

#[cfg(windows)]
pub async fn open_transport(
    socket_path: &std::path::Path,
    _expected_parent_pid: u32,
) -> Result<tokio::net::windows::named_pipe::NamedPipeClient> {
    use tokio::net::windows::named_pipe::ClientOptions;
    let pipe = socket_path
        .to_str()
        .context("Invalid named pipe path")?;
    ClientOptions::new()
        .open(pipe)
        .with_context(|| format!("Failed to connect to named pipe {pipe}"))
}
