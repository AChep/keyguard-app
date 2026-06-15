//! IPC client that connects to the Keyguard desktop application.
//!
//! The client communicates over a Unix domain socket (macOS/Linux) or named pipe
//! (Windows) using length-prefixed Protobuf messages.

#[cfg(test)]
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use bytes::{Buf, BufMut, BytesMut};
use prost::Message;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::Mutex;
use tracing::{debug, info, trace, warn};

use super::messages::{
    ipc_request, ipc_response, AuthenticateRequest, CallerIdentity, ErrorCode, IpcRequest,
    IpcResponse, ListKeysRequest, ListKeysResponse, SignDataRequest, SignDataResponse,
};
use crate::agent::KeyProvider;

/// Maximum IPC message size (16 MB). Protects against malformed length prefixes.
const MAX_MESSAGE_SIZE: u32 = 16 * 1024 * 1024;
const RECONNECT_MAX_ATTEMPTS: u32 = 6;
const RECONNECT_INITIAL_DELAY_MS: u64 = 100;
const RECONNECT_MAX_DELAY_MS: u64 = 3_000;
const RECONNECT_MAX_JITTER_MS: u64 = 250;

/// IPC client for communicating with the Keyguard desktop application.
///
/// This is safe to clone and share across tasks -- all access to the
/// underlying socket is serialized via a mutex.
#[derive(Clone)]
pub struct IpcClient {
    inner: Arc<IpcClientInner>,
}

struct IpcClientInner {
    stream: Mutex<IpcStream<Box<dyn AsyncStream>>>,
    next_id: AtomicU64,
    reconnect: Option<ReconnectConfig>,
}

struct ReconnectConfig {
    socket_path: PathBuf,
    auth_token: Vec<u8>,
    #[cfg(test)]
    test_reconnect_streams: Option<std::sync::Mutex<VecDeque<Box<dyn AsyncStream>>>>,
}

impl Drop for ReconnectConfig {
    fn drop(&mut self) {
        self.auth_token.fill(0);
    }
}

/// Trait alias for streams that support both async read and write.
/// This allows `IpcClient` to be constructed with either a real platform
/// socket or a test duplex stream.
pub(crate) trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

/// Length-prefixed protobuf message transport, generic over the underlying
/// async stream. This allows tests to substitute `tokio::io::DuplexStream`
/// in place of a real socket.
pub(crate) struct IpcStream<S> {
    stream: S,
    buf: BytesMut,
}

impl<S: AsyncRead + AsyncWrite + Unpin> IpcStream<S> {
    /// Creates a new `IpcStream` wrapping the given async stream.
    pub(crate) fn new(stream: S) -> Self {
        Self {
            stream,
            buf: BytesMut::with_capacity(4096),
        }
    }

    /// Writes a length-prefixed protobuf `IpcRequest` to the stream.
    pub(crate) async fn write_message(&mut self, msg: &IpcRequest) -> Result<()> {
        let encoded = msg.encode_to_vec();
        let len = encoded.len() as u32;
        trace!(len, "Sending IPC message");

        // Write 4-byte big-endian length prefix.
        let mut len_buf = [0u8; 4];
        (&mut len_buf[..]).put_u32(len);
        self.stream.write_all(&len_buf).await?;
        // Write the message body.
        self.stream.write_all(&encoded).await?;
        self.stream.flush().await?;
        Ok(())
    }

    /// Reads a length-prefixed protobuf `IpcResponse` from the stream.
    pub(crate) async fn read_message(&mut self) -> Result<IpcResponse> {
        // Read 4-byte big-endian length prefix.
        let mut len_buf = [0u8; 4];
        self.stream.read_exact(&mut len_buf).await?;
        let len = (&len_buf[..]).get_u32();

        if len > MAX_MESSAGE_SIZE {
            bail!(
                "IPC message too large: {} bytes (max {})",
                len,
                MAX_MESSAGE_SIZE
            );
        }

        // Read the message body.
        self.buf.clear();
        self.buf.resize(len as usize, 0);
        self.stream.read_exact(&mut self.buf).await?;

        let response = IpcResponse::decode(&self.buf[..])?;
        Ok(response)
    }

    /// Writes a length-prefixed protobuf `IpcResponse` to the stream.
    /// This is the server-side counterpart to `read_message` — used in tests
    /// to simulate a server sending responses.
    #[cfg(test)]
    pub(crate) async fn write_response(&mut self, msg: &IpcResponse) -> Result<()> {
        let encoded = msg.encode_to_vec();
        let len = encoded.len() as u32;

        let mut len_buf = [0u8; 4];
        (&mut len_buf[..]).put_u32(len);
        self.stream.write_all(&len_buf).await?;
        self.stream.write_all(&encoded).await?;
        self.stream.flush().await?;
        Ok(())
    }

    /// Reads a length-prefixed protobuf `IpcRequest` from the stream.
    /// This is the server-side counterpart to `write_message` — used in tests
    /// to simulate a server reading requests.
    #[cfg(test)]
    pub(crate) async fn read_request(&mut self) -> Result<IpcRequest> {
        let mut len_buf = [0u8; 4];
        self.stream.read_exact(&mut len_buf).await?;
        let len = (&len_buf[..]).get_u32();

        if len > MAX_MESSAGE_SIZE {
            bail!(
                "IPC message too large: {} bytes (max {})",
                len,
                MAX_MESSAGE_SIZE
            );
        }

        self.buf.clear();
        self.buf.resize(len as usize, 0);
        self.stream.read_exact(&mut self.buf).await?;

        let request = IpcRequest::decode(&self.buf[..])?;
        Ok(request)
    }
}

impl IpcClient {
    /// Connects to the Keyguard IPC server and authenticates.
    ///
    /// # Arguments
    /// * `socket_path` - Path to the IPC Unix socket (or named pipe on Windows).
    /// * `auth_token` - The 256-bit authentication token (raw bytes).
    pub async fn connect(socket_path: &Path, auth_token: &[u8]) -> Result<Self> {
        if auth_token.len() != 32 {
            bail!(
                "IPC auth token must be exactly 32 bytes, got {} bytes",
                auth_token.len()
            );
        }

        debug!(path = %socket_path.display(), "Connecting to IPC server");
        let ipc_stream = Self::connect_stream(socket_path).await?;

        let client = Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(ipc_stream),
                next_id: AtomicU64::new(1),
                reconnect: Some(ReconnectConfig {
                    socket_path: socket_path.to_path_buf(),
                    auth_token: auth_token.to_vec(),
                    #[cfg(test)]
                    test_reconnect_streams: None,
                }),
            }),
        };

        // Authenticate immediately.
        client.authenticate(auth_token).await?;

        Ok(client)
    }

    /// Creates an `IpcClient` from a pre-connected async stream.
    /// Used in tests to inject a `tokio::io::DuplexStream`.
    #[cfg(test)]
    pub(crate) fn from_stream(stream: impl AsyncStream + 'static) -> Self {
        Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(IpcStream::new(Box::new(stream) as Box<dyn AsyncStream>)),
                next_id: AtomicU64::new(1),
                reconnect: None,
            }),
        }
    }

    #[cfg(test)]
    pub(crate) fn from_stream_with_reconnect(
        stream: impl AsyncStream + 'static,
        auth_token: Vec<u8>,
        reconnect_streams: Vec<Box<dyn AsyncStream>>,
    ) -> Self {
        Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(IpcStream::new(Box::new(stream) as Box<dyn AsyncStream>)),
                next_id: AtomicU64::new(1),
                reconnect: Some(ReconnectConfig {
                    socket_path: PathBuf::new(),
                    auth_token,
                    test_reconnect_streams: Some(std::sync::Mutex::new(
                        reconnect_streams.into_iter().collect(),
                    )),
                }),
            }),
        }
    }

    async fn connect_stream(socket_path: &Path) -> Result<IpcStream<Box<dyn AsyncStream>>> {
        #[cfg(unix)]
        let stream = tokio::net::UnixStream::connect(socket_path)
            .await
            .with_context(|| {
                format!(
                    "Failed to connect to IPC socket at {}",
                    socket_path.display()
                )
            })?;

        #[cfg(windows)]
        let stream = {
            use tokio::net::windows::named_pipe::ClientOptions;
            let pipe_name = socket_path.to_str().context("Invalid pipe name")?;
            ClientOptions::new()
                .open(pipe_name)
                .with_context(|| format!("Failed to connect to named pipe {}", pipe_name))?
        };

        Ok(IpcStream::new(Box::new(stream) as Box<dyn AsyncStream>))
    }

    async fn request_once_locked(
        stream: &mut IpcStream<Box<dyn AsyncStream>>,
        msg: &IpcRequest,
    ) -> Result<IpcResponse> {
        stream.write_message(msg).await?;
        stream.read_message().await
    }

    fn parse_response(id: u64, response: IpcResponse) -> Result<ipc_response::Response> {
        if response.id != id {
            bail!(
                "IPC response ID mismatch: expected {}, got {}",
                id,
                response.id
            );
        }

        match response.response {
            Some(ipc_response::Response::Error(err)) => {
                let code = ErrorCode::try_from(err.code).unwrap_or(ErrorCode::Unspecified);
                Err(anyhow!("IPC error ({:?}): {}", code, err.message))
            }
            Some(resp) => Ok(resp),
            None => bail!("IPC response has no payload"),
        }
    }

    async fn authenticate_stream(
        stream: &mut IpcStream<Box<dyn AsyncStream>>,
        next_id: &AtomicU64,
        token: &[u8],
    ) -> Result<()> {
        let id = next_id.fetch_add(1, Ordering::Relaxed);
        let request = IpcRequest {
            id,
            request: Some(ipc_request::Request::Authenticate(AuthenticateRequest {
                token: token.to_vec(),
            })),
        };

        let response = Self::request_once_locked(stream, &request)
            .await
            .context("IPC transport failed during authenticate")?;
        let payload = Self::parse_response(id, response)?;

        match payload {
            ipc_response::Response::Authenticate(auth) => {
                if auth.success {
                    Ok(())
                } else {
                    bail!("Authentication failed: server rejected the token")
                }
            }
            _ => bail!("Unexpected response to authenticate request"),
        }
    }

    async fn reconnect_locked(&self, stream: &mut IpcStream<Box<dyn AsyncStream>>) -> Result<()> {
        let reconnect = self
            .inner
            .reconnect
            .as_ref()
            .context("Reconnect is not configured for this IPC client")?;

        #[cfg(test)]
        if let Some(test_reconnect_streams) = &reconnect.test_reconnect_streams {
            let next_stream = {
                let mut streams = test_reconnect_streams
                    .lock()
                    .map_err(|_| anyhow!("Test reconnect stream queue is poisoned"))?;
                streams
                    .pop_front()
                    .context("No test reconnect streams remaining")?
            };
            let mut new_stream = IpcStream::new(next_stream);
            Self::authenticate_stream(
                &mut new_stream,
                &self.inner.next_id,
                reconnect.auth_token.as_slice(),
            )
            .await?;
            *stream = new_stream;
            return Ok(());
        }

        let mut new_stream = Self::connect_stream(&reconnect.socket_path).await?;
        Self::authenticate_stream(
            &mut new_stream,
            &self.inner.next_id,
            reconnect.auth_token.as_slice(),
        )
        .await?;
        *stream = new_stream;
        Ok(())
    }

    /// Sends an IPC request and waits for the matching response.
    async fn request(&self, request: ipc_request::Request) -> Result<ipc_response::Response> {
        let mut stream = self.inner.stream.lock().await;
        let mut reconnect_attempt = 0u32;

        loop {
            let id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
            let msg = IpcRequest {
                id,
                request: Some(request.clone()),
            };

            match Self::request_once_locked(&mut stream, &msg).await {
                Ok(response) => return Self::parse_response(id, response),
                Err(err) => {
                    if self.inner.reconnect.is_none() {
                        return Err(err).context("IPC transport failure");
                    }

                    if reconnect_attempt >= RECONNECT_MAX_ATTEMPTS {
                        return Err(err).context(format!(
                            "IPC transport failed after {} reconnect attempts",
                            RECONNECT_MAX_ATTEMPTS
                        ));
                    }

                    reconnect_attempt += 1;
                    let delay = reconnect_delay_with_jitter(reconnect_attempt);
                    warn!(
                        attempt = reconnect_attempt,
                        max_attempts = RECONNECT_MAX_ATTEMPTS,
                        delay_ms = delay.as_millis() as u64,
                        error = %err,
                        "IPC transport failed; reconnecting and re-authenticating"
                    );
                    tokio::time::sleep(delay).await;

                    match self.reconnect_locked(&mut stream).await {
                        Ok(()) => {
                            info!(
                                attempt = reconnect_attempt,
                                "IPC reconnect and re-authentication succeeded"
                            );
                            continue;
                        }
                        Err(reconnect_err) => {
                            warn!(
                                attempt = reconnect_attempt,
                                max_attempts = RECONNECT_MAX_ATTEMPTS,
                                error = %reconnect_err,
                                "IPC reconnect attempt failed"
                            );
                            if reconnect_attempt >= RECONNECT_MAX_ATTEMPTS {
                                return Err(reconnect_err).context(format!(
                                    "Unable to restore IPC connection after {} attempts",
                                    RECONNECT_MAX_ATTEMPTS
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    /// Authenticates with the IPC server using the shared token.
    async fn authenticate(&self, token: &[u8]) -> Result<()> {
        let mut stream = self.inner.stream.lock().await;
        Self::authenticate_stream(&mut stream, &self.inner.next_id, token).await
    }

    /// Requests the list of SSH keys available in the Keyguard vault.
    pub async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        let resp = self
            .request(ipc_request::Request::ListKeys(ListKeysRequest { caller }))
            .await?;

        match resp {
            ipc_response::Response::ListKeys(keys) => Ok(keys),
            _ => bail!("Unexpected response to list_keys request"),
        }
    }

    /// Requests Keyguard to sign data with the specified key.
    ///
    /// This may trigger a user approval prompt in the Keyguard UI.
    pub async fn sign_data(
        &self,
        public_key: &str,
        data: &[u8],
        flags: u32,
        caller: Option<CallerIdentity>,
    ) -> Result<SignDataResponse> {
        let resp = self
            .request(ipc_request::Request::SignData(SignDataRequest {
                public_key: public_key.to_string(),
                data: data.to_vec(),
                flags,
                caller,
            }))
            .await?;

        match resp {
            ipc_response::Response::SignData(sig) => Ok(sig),
            _ => bail!("Unexpected response to sign_data request"),
        }
    }
}

fn reconnect_delay_with_jitter(attempt: u32) -> Duration {
    let exponent = attempt.saturating_sub(1).min(10);
    let base = RECONNECT_INITIAL_DELAY_MS
        .saturating_mul(1u64 << exponent)
        .min(RECONNECT_MAX_DELAY_MS);

    let jitter_span = (base / 4).clamp(1, RECONNECT_MAX_JITTER_MS);
    let now_nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as u64)
        .unwrap_or(0);
    let jitter_seed = now_nanos ^ (attempt as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    let jitter = jitter_seed % (jitter_span + 1);

    Duration::from_millis(base.saturating_add(jitter).min(RECONNECT_MAX_DELAY_MS))
}

#[ssh_agent_lib::async_trait]
impl KeyProvider for IpcClient {
    async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        self.list_keys(caller).await
    }

    async fn sign_data(
        &self,
        public_key: &str,
        data: &[u8],
        flags: u32,
        caller: Option<CallerIdentity>,
    ) -> Result<SignDataResponse> {
        self.sign_data(public_key, data, flags, caller).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc::messages::{
        ipc_response, AuthenticateResponse, IpcResponse, ListKeysResponse, SignDataResponse, SshKey,
    };
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
            request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller: None })),
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
            request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller: None })),
        };
        let req2 = IpcRequest {
            id: 2,
            request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller: None })),
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
        };
        let expected_pid = caller.pid;
        let expected_app_name = caller.app_name.clone();

        let server_handle = tokio::spawn(async move {
            let req = server.read_request().await.unwrap();
            if let Some(ipc_request::Request::ListKeys(list_req)) = &req.request {
                let caller = list_req.caller.as_ref().expect("caller identity should be set");
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
        let err = match IpcClient::connect(Path::new("/tmp/should-not-connect.sock"), &[0xAA; 31])
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
}
