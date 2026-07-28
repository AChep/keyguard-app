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
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use tokio::sync::Mutex;
use tracing::{debug, info, warn};

use super::messages::{
    ipc_request, ipc_response, AuthenticateRequest, CallerIdentity, ErrorCode, IpcRequest,
    IpcResponse, ListKeysRequest, ListKeysResponse, SignDataRequest, SignDataResponse,
};
use crate::agent::KeyProvider;

mod transport;

#[cfg(test)]
use transport::MAX_MESSAGE_SIZE;
use transport::{AsyncStream, IpcStream};

const RECONNECT_MAX_ATTEMPTS: u32 = 6;
const RECONNECT_INITIAL_DELAY_MS: u64 = 100;
const RECONNECT_MAX_DELAY_MS: u64 = 3_000;
const RECONNECT_MAX_JITTER_MS: u64 = 250;
const IPC_REQUEST_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const IPC_AUTHENTICATION_TIMEOUT: Duration = Duration::from_secs(30);

/// IPC client for communicating with the Keyguard desktop application.
///
/// This is safe to clone and share across tasks -- all access to the
/// underlying socket is serialized via a mutex.
#[derive(Clone)]
pub struct IpcClient {
    inner: Arc<IpcClientInner>,
}

struct IpcClientInner {
    // `None` means the previous transport is unusable. In particular, an
    // async read or write cancelled by a timeout may have consumed only part
    // of a frame, so that transport must never be used again.
    stream: Mutex<Option<IpcStream<Box<dyn AsyncStream>>>>,
    next_id: AtomicU64,
    reconnect: Option<ReconnectConfig>,
}

struct ReconnectConfig {
    socket_path: PathBuf,
    auth_token: Vec<u8>,
    expected_parent_pid: u32,
    #[cfg(test)]
    test_reconnect_streams: Option<std::sync::Mutex<VecDeque<Box<dyn AsyncStream>>>>,
}

impl Drop for ReconnectConfig {
    fn drop(&mut self) {
        self.auth_token.fill(0);
    }
}

impl IpcClient {
    /// Connects to the Keyguard IPC server and authenticates.
    ///
    /// # Arguments
    /// * `socket_path` - Path to the IPC Unix socket (or named pipe on Windows).
    /// * `auth_token` - The 256-bit authentication token (raw bytes).
    pub async fn connect(
        socket_path: &Path,
        auth_token: &[u8],
        expected_parent_pid: u32,
    ) -> Result<Self> {
        if auth_token.len() != 32 {
            bail!(
                "IPC auth token must be exactly 32 bytes, got {} bytes",
                auth_token.len()
            );
        }
        if expected_parent_pid == 0 {
            bail!("Expected parent PID must be non-zero");
        }

        debug!(path = %socket_path.display(), "Connecting to IPC server");
        let ipc_stream = Self::connect_stream(socket_path, expected_parent_pid).await?;

        let client = Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(Some(ipc_stream)),
                next_id: AtomicU64::new(1),
                reconnect: Some(ReconnectConfig {
                    socket_path: socket_path.to_path_buf(),
                    auth_token: auth_token.to_vec(),
                    expected_parent_pid,
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
    fn from_stream(stream: impl AsyncStream + 'static) -> Self {
        Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(Some(IpcStream::new(
                    Box::new(stream) as Box<dyn AsyncStream>
                ))),
                next_id: AtomicU64::new(1),
                reconnect: None,
            }),
        }
    }

    #[cfg(test)]
    fn from_stream_with_reconnect(
        stream: impl AsyncStream + 'static,
        auth_token: Vec<u8>,
        reconnect_streams: Vec<Box<dyn AsyncStream>>,
    ) -> Self {
        Self {
            inner: Arc::new(IpcClientInner {
                stream: Mutex::new(Some(IpcStream::new(
                    Box::new(stream) as Box<dyn AsyncStream>
                ))),
                next_id: AtomicU64::new(1),
                reconnect: Some(ReconnectConfig {
                    socket_path: PathBuf::new(),
                    auth_token,
                    expected_parent_pid: std::process::id(),
                    test_reconnect_streams: Some(std::sync::Mutex::new(
                        reconnect_streams.into_iter().collect(),
                    )),
                }),
            }),
        }
    }

    async fn connect_stream(
        socket_path: &Path,
        expected_parent_pid: u32,
    ) -> Result<IpcStream<Box<dyn AsyncStream>>> {
        #[cfg(unix)]
        let stream = {
            let stream = tokio::net::UnixStream::connect(socket_path)
                .await
                .with_context(|| {
                    format!(
                        "Failed to connect to IPC socket at {}",
                        socket_path.display()
                    )
                })?;
            verify_unix_ipc_server(&stream, expected_parent_pid)?;
            stream
        };

        #[cfg(windows)]
        let stream = {
            use tokio::net::windows::named_pipe::ClientOptions;
            let pipe_name = socket_path.to_str().context("Invalid pipe name")?;
            let stream = ClientOptions::new()
                .open(pipe_name)
                .with_context(|| format!("Failed to connect to named pipe {}", pipe_name))?;
            verify_windows_ipc_server(&stream, expected_parent_pid)?;
            stream
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
                protocol_revision: IPC_PROTOCOL_REVISION,
            })),
        };

        let response = Self::request_once_locked(stream, &request)
            .await
            .context("IPC transport failed during authenticate")?;
        let payload = Self::parse_response(id, response)?;

        match payload {
            ipc_response::Response::Authenticate(auth) => {
                if auth.success && auth.protocol_revision == IPC_PROTOCOL_REVISION {
                    Ok(())
                } else if auth.protocol_revision != IPC_PROTOCOL_REVISION {
                    bail!(
                        "IPC protocol revision mismatch: expected {}, received {}",
                        IPC_PROTOCOL_REVISION,
                        auth.protocol_revision,
                    )
                } else {
                    bail!("Authentication failed: server rejected the token")
                }
            }
            _ => bail!("Unexpected response to authenticate request"),
        }
    }

    async fn authenticate_stream_with_timeout(
        stream: &mut IpcStream<Box<dyn AsyncStream>>,
        next_id: &AtomicU64,
        token: &[u8],
    ) -> Result<()> {
        tokio::time::timeout(
            IPC_AUTHENTICATION_TIMEOUT,
            Self::authenticate_stream(stream, next_id, token),
        )
        .await
        .map_err(|_| {
            anyhow!(
                "IPC authentication timed out after {} seconds",
                IPC_AUTHENTICATION_TIMEOUT.as_secs()
            )
        })?
    }

    async fn reconnect_locked(
        &self,
        stream: &mut Option<IpcStream<Box<dyn AsyncStream>>>,
    ) -> Result<()> {
        // A reconnect is only ever allowed to replace an invalidated
        // transport. Keep the slot empty until the new stream has completed
        // authentication so a failed attempt cannot restore stale state.
        stream.take();
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
            Self::authenticate_stream_with_timeout(
                &mut new_stream,
                &self.inner.next_id,
                reconnect.auth_token.as_slice(),
            )
            .await?;
            *stream = Some(new_stream);
            return Ok(());
        }

        let mut new_stream =
            Self::connect_stream(&reconnect.socket_path, reconnect.expected_parent_pid).await?;
        Self::authenticate_stream_with_timeout(
            &mut new_stream,
            &self.inner.next_id,
            reconnect.auth_token.as_slice(),
        )
        .await?;
        *stream = Some(new_stream);
        Ok(())
    }

    async fn reconnect_with_backoff_locked(
        &self,
        stream: &mut Option<IpcStream<Box<dyn AsyncStream>>>,
        reconnect_attempts: &mut u32,
        mut last_error: anyhow::Error,
    ) -> Result<()> {
        while *reconnect_attempts < RECONNECT_MAX_ATTEMPTS {
            *reconnect_attempts += 1;
            let reconnect_attempt = *reconnect_attempts;
            let delay = reconnect_delay_with_jitter(reconnect_attempt);
            warn!(
                attempt = reconnect_attempt,
                max_attempts = RECONNECT_MAX_ATTEMPTS,
                delay_ms = delay.as_millis() as u64,
                error = %last_error,
                "IPC transport failed; reconnecting and re-authenticating"
            );
            tokio::time::sleep(delay).await;

            match self.reconnect_locked(stream).await {
                Ok(()) => {
                    info!(
                        attempt = reconnect_attempt,
                        "IPC reconnect and re-authentication succeeded"
                    );
                    return Ok(());
                }
                Err(reconnect_error) => {
                    warn!(
                        attempt = reconnect_attempt,
                        max_attempts = RECONNECT_MAX_ATTEMPTS,
                        error = %reconnect_error,
                        "IPC reconnect attempt failed"
                    );
                    last_error = reconnect_error;
                }
            }
        }

        Err(last_error).context(format!(
            "Unable to restore IPC connection after {} attempts",
            RECONNECT_MAX_ATTEMPTS
        ))
    }

    async fn repair_connection_locked(
        &self,
        stream: &mut Option<IpcStream<Box<dyn AsyncStream>>>,
        request_deadline: tokio::time::Instant,
    ) -> Result<()> {
        stream.take();
        let recovery_deadline =
            request_deadline.min(tokio::time::Instant::now() + IPC_AUTHENTICATION_TIMEOUT);
        tokio::time::timeout_at(recovery_deadline, self.reconnect_locked(stream))
            .await
            .map_err(|_| anyhow!("IPC connection recovery did not complete before its deadline"))?
    }

    fn request_timeout_error_locked(
        stream: &mut Option<IpcStream<Box<dyn AsyncStream>>>,
        timeout_message: String,
    ) -> anyhow::Error {
        // Keep the slot empty so the next request must reconnect. Eager
        // recovery here would extend the request beyond its absolute deadline
        // while continuing to hold the shared mutex and SSH session permit.
        stream.take();
        anyhow!(timeout_message)
    }

    /// Sends an IPC request and waits for the matching response.
    async fn request(&self, request: ipc_request::Request) -> Result<ipc_response::Response> {
        let deadline = tokio::time::Instant::now() + IPC_REQUEST_TIMEOUT;
        let mut stream = tokio::time::timeout_at(deadline, self.inner.stream.lock())
            .await
            .map_err(|_| {
                anyhow!(
                    "IPC request timed out after {} seconds while waiting for the shared connection; operation was not sent",
                    IPC_REQUEST_TIMEOUT.as_secs()
                )
            })?;
        let mut reconnect_attempts = 0;
        // Only read-only requests are safe to replay after an ordinary
        // transport failure. A failed SignData response is ambiguous: the
        // server may have completed the signature before the channel broke.
        let is_retry_safe = matches!(&request, ipc_request::Request::ListKeys(_));

        loop {
            if stream.is_none() {
                if self.inner.reconnect.is_none() {
                    bail!("IPC connection is unavailable");
                }
                match tokio::time::timeout_at(
                    deadline,
                    self.reconnect_with_backoff_locked(
                        &mut stream,
                        &mut reconnect_attempts,
                        anyhow!("IPC connection is unavailable"),
                    ),
                )
                .await
                {
                    Ok(result) => result?,
                    Err(_) => {
                        let timeout_message = format!(
                            "IPC request timed out after {} seconds while restoring the connection; operation was not sent",
                            IPC_REQUEST_TIMEOUT.as_secs()
                        );
                        return Err(Self::request_timeout_error_locked(
                            &mut stream,
                            timeout_message,
                        ));
                    }
                }
            }

            let id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
            let msg = IpcRequest {
                id,
                request: Some(request.clone()),
            };

            // Move the transport out of shared state for the entire
            // transaction. If this future is cancelled externally, the local
            // stream is dropped and the shared slot remains `None`, so a
            // partially written/read frame can never be reused.
            let mut active_stream = stream
                .take()
                .context("IPC connection unexpectedly unavailable")?;
            match tokio::time::timeout_at(
                deadline,
                Self::request_once_locked(&mut active_stream, &msg),
            )
            .await
            {
                Ok(Ok(response)) => {
                    if response.id != id {
                        let actual_id = response.id;
                        drop(active_stream);
                        let mismatch = anyhow!(
                            "IPC response ID mismatch: expected {}, got {}",
                            id,
                            actual_id
                        );
                        if self.inner.reconnect.is_none() {
                            return Err(mismatch);
                        }

                        return match self.repair_connection_locked(&mut stream, deadline).await {
                            Ok(()) => Err(mismatch),
                            Err(reconnect_error) => Err(reconnect_error)
                                .context(format!("{}; connection recovery failed", mismatch)),
                        };
                    }

                    *stream = Some(active_stream);
                    return Self::parse_response(id, response);
                }
                Ok(Err(err)) => {
                    // A transport error can leave framing state ambiguous.
                    // Invalidate it before attempting the existing bounded
                    // reconnect-and-retry behavior.
                    drop(active_stream);
                    if self.inner.reconnect.is_none() {
                        return Err(err).context("IPC transport failure");
                    }

                    if !is_retry_safe {
                        let result = self.repair_connection_locked(&mut stream, deadline).await;
                        return match result {
                            Ok(()) => Err(err).context(
                                "IPC transport failed during a non-idempotent request; operation was not retried because completion is unknown",
                            ),
                            Err(reconnect_error) => Err(reconnect_error).context(format!(
                                "IPC transport failed during a non-idempotent request and connection recovery failed; operation was not retried because completion is unknown: {err}"
                            )),
                        };
                    }

                    match tokio::time::timeout_at(
                        deadline,
                        self.reconnect_with_backoff_locked(
                            &mut stream,
                            &mut reconnect_attempts,
                            err,
                        ),
                    )
                    .await
                    {
                        Ok(result) => result?,
                        Err(_) => {
                            let timeout_message = format!(
                                "IPC request timed out after {} seconds while restoring the connection; operation was not retried",
                                IPC_REQUEST_TIMEOUT.as_secs()
                            );
                            return Err(Self::request_timeout_error_locked(
                                &mut stream,
                                timeout_message,
                            ));
                        }
                    }
                }
                Err(_) => {
                    // Cancellation may interrupt a prefix, body, or response
                    // read. Drop the stream before reconnecting; retrying this
                    // request could duplicate a signing operation whose result
                    // was merely delayed.
                    drop(active_stream);
                    let timeout_message = format!(
                        "IPC request timed out after {} seconds; operation was not retried",
                        IPC_REQUEST_TIMEOUT.as_secs()
                    );
                    return Err(Self::request_timeout_error_locked(
                        &mut stream,
                        timeout_message,
                    ));
                }
            }
        }
    }

    /// Authenticates with the IPC server using the shared token.
    async fn authenticate(&self, token: &[u8]) -> Result<()> {
        let deadline = tokio::time::Instant::now() + IPC_AUTHENTICATION_TIMEOUT;
        let mut stream = tokio::time::timeout_at(deadline, self.inner.stream.lock())
            .await
            .map_err(|_| {
                anyhow!(
                    "IPC authentication timed out after {} seconds while waiting for the shared connection",
                    IPC_AUTHENTICATION_TIMEOUT.as_secs()
                )
            })?;
        let mut candidate = stream.take().context("IPC connection is unavailable")?;
        let result = tokio::time::timeout_at(
            deadline,
            Self::authenticate_stream(&mut candidate, &self.inner.next_id, token),
        )
        .await
        .map_err(|_| {
            anyhow!(
                "IPC authentication timed out after {} seconds",
                IPC_AUTHENTICATION_TIMEOUT.as_secs()
            )
        })?;

        if result.is_ok() {
            *stream = Some(candidate);
        }
        result
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

#[cfg(unix)]
fn verify_unix_ipc_server(stream: &tokio::net::UnixStream, expected_parent_pid: u32) -> Result<()> {
    let credentials = stream
        .peer_cred()
        .context("Failed to read IPC server peer credentials")?;
    let peer_pid = credentials
        .pid()
        .context("IPC server peer credentials did not include a PID")?;
    let peer_pid = u32::try_from(peer_pid).context("IPC server reported an invalid peer PID")?;
    if peer_pid != expected_parent_pid {
        bail!(
            "IPC server PID mismatch: expected parent {}, got {}",
            expected_parent_pid,
            peer_pid
        );
    }

    // SAFETY: geteuid has no preconditions and does not dereference pointers.
    let expected_uid = unsafe { libc::geteuid() };
    if credentials.uid() != expected_uid {
        bail!(
            "IPC server UID mismatch: expected {}, got {}",
            expected_uid,
            credentials.uid()
        );
    }
    Ok(())
}

#[cfg(windows)]
fn verify_windows_ipc_server(
    stream: &tokio::net::windows::named_pipe::NamedPipeClient,
    expected_parent_pid: u32,
) -> Result<()> {
    use std::ffi::c_void;
    use std::os::windows::io::AsRawHandle;

    #[link(name = "kernel32")]
    extern "system" {
        fn GetNamedPipeServerProcessId(pipe: *mut c_void, server_process_id: *mut u32) -> i32;
    }

    let mut server_pid = 0u32;
    // SAFETY: the Tokio client owns a valid pipe handle for the duration of
    // this call, and server_pid points to writable u32 storage.
    let succeeded =
        unsafe { GetNamedPipeServerProcessId(stream.as_raw_handle().cast(), &mut server_pid) };
    if succeeded == 0 {
        return Err(std::io::Error::last_os_error())
            .context("Failed to read named-pipe server PID");
    }
    if server_pid != expected_parent_pid {
        bail!(
            "IPC named-pipe server PID mismatch: expected parent {}, got {}",
            expected_parent_pid,
            server_pid
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests;
