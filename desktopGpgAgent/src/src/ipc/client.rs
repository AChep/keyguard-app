//! Protobuf IPC client for talking to the Keyguard desktop app.

use anyhow::{bail, Context, Error, Result};
use common_gpg_agent_rust::messages::{
    ipc_request, ipc_response, AuthenticateRequest, AuthenticateResponse, CallerIdentity,
    ErrorCode, ErrorResponse, IpcRequest, IpcResponse, ListKeysRequest, ListKeysResponse,
    PkdecryptRequest, PkdecryptResponse, SignHashRequest, SignHashResponse,
};
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use prost::Message;
use std::fmt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::Mutex;

/// Trait alias for streams that support both async read and write, allowing the
/// IPC client to be backed by a Unix socket (macOS/Linux) or a named pipe
/// (Windows).
trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

#[derive(Clone)]
pub struct IpcClient {
    stream: Arc<Mutex<Box<dyn AsyncStream>>>,
    next_id: Arc<AtomicU64>,
}

#[derive(Clone, Debug)]
pub struct IpcError {
    code: ErrorCode,
    message: String,
}

impl IpcError {
    pub(crate) fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn code(&self) -> ErrorCode {
        self.code
    }

    #[cfg(test)]
    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for IpcError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "IPC error ({:?}): {}", self.code, self.message)
    }
}

impl std::error::Error for IpcError {}

impl IpcClient {
    #[cfg(test)]
    pub(crate) fn from_test_stream<S>(stream: S) -> Self
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        Self {
            stream: Arc::new(Mutex::new(Box::new(stream))),
            next_id: Arc::new(AtomicU64::new(1)),
        }
    }

    pub async fn connect(
        socket_path: &Path,
        auth_token: &[u8],
        expected_parent_pid: u32,
    ) -> Result<Self> {
        if expected_parent_pid == 0 {
            bail!("expected parent PID must be non-zero");
        }
        let stream = connect_stream(socket_path, expected_parent_pid).await?;
        let client = Self {
            stream: Arc::new(Mutex::new(stream)),
            next_id: Arc::new(AtomicU64::new(1)),
        };

        let response = client
            .send(IpcRequest {
                id: client.next_request_id(),
                request: Some(ipc_request::Request::Authenticate(AuthenticateRequest {
                    token: auth_token.to_vec(),
                    protocol_revision: IPC_PROTOCOL_REVISION,
                })),
            })
            .await?;
        match response.response {
            Some(ipc_response::Response::Authenticate(auth)) => {
                validate_authenticate_response(&auth)?;
                Ok(client)
            }
            Some(ipc_response::Response::Error(error)) => {
                Err(Error::new(ipc_error(&error)).context("authentication failed"))
            }
            other => bail!("unexpected authentication response: {other:?}"),
        }
    }

    pub async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        let id = self.next_request_id();
        let response = self
            .send(IpcRequest {
                id,
                request: Some(ipc_request::Request::ListKeys(ListKeysRequest { caller })),
            })
            .await?;

        match response.response {
            Some(ipc_response::Response::ListKeys(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected list_keys response: {other:?}"),
        }
    }

    pub async fn sign_hash(
        &self,
        keygrip: &str,
        hash_algorithm: &str,
        hash: &[u8],
        caller: Option<CallerIdentity>,
    ) -> Result<SignHashResponse> {
        let id = self.next_request_id();
        let response = self
            .send(IpcRequest {
                id,
                request: Some(ipc_request::Request::SignHash(SignHashRequest {
                    keygrip: keygrip.to_string(),
                    hash_algorithm: hash_algorithm.to_string(),
                    hash: hash.to_vec(),
                    caller,
                })),
            })
            .await?;

        match response.response {
            Some(ipc_response::Response::SignHash(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected sign_hash response: {other:?}"),
        }
    }

    pub async fn pkdecrypt(
        &self,
        keygrip: &str,
        ciphertext: &[u8],
        unwrap_ecdh: bool,
        caller: Option<CallerIdentity>,
    ) -> Result<PkdecryptResponse> {
        let id = self.next_request_id();
        let response = self
            .send(IpcRequest {
                id,
                request: Some(ipc_request::Request::Pkdecrypt(PkdecryptRequest {
                    keygrip: keygrip.to_string(),
                    ciphertext: ciphertext.to_vec(),
                    unwrap_ecdh,
                    caller,
                })),
            })
            .await?;

        match response.response {
            Some(ipc_response::Response::Pkdecrypt(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected pkdecrypt response: {other:?}"),
        }
    }

    fn next_request_id(&self) -> u64 {
        self.next_id.fetch_add(1, Ordering::Relaxed)
    }

    async fn send(&self, request: IpcRequest) -> Result<IpcResponse> {
        let expected_id = request.id;
        let request_bytes = request.encode_to_vec();
        let mut stream = self.stream.lock().await;
        write_frame(&mut *stream, &request_bytes).await?;
        let response_bytes = read_frame(&mut *stream).await?;
        let response = IpcResponse::decode(&response_bytes[..])?;
        if response.id != expected_id {
            bail!(
                "protobuf response id mismatch: expected {} got {}",
                expected_id,
                response.id
            );
        }
        Ok(response)
    }
}

fn validate_authenticate_response(response: &AuthenticateResponse) -> Result<()> {
    if response.protocol_revision != IPC_PROTOCOL_REVISION {
        bail!(
            "IPC protocol revision mismatch: expected {}, received {}",
            IPC_PROTOCOL_REVISION,
            response.protocol_revision,
        );
    }
    if !response.success {
        bail!("authentication failed");
    }
    Ok(())
}

/// Converts an IPC `ErrorResponse` into a typed error, preserving the
/// locked/denied/not-found distinction carried in `code` so the Assuan layer
/// can map it to an appropriate response.
fn ipc_error(error: &ErrorResponse) -> IpcError {
    let code = ErrorCode::try_from(error.code).unwrap_or(ErrorCode::Unspecified);
    IpcError::new(code, error.message.clone())
}

/// Establishes the platform-appropriate IPC transport: a Unix domain socket on
/// macOS/Linux or a named pipe on Windows.
async fn connect_stream(
    socket_path: &Path,
    expected_parent_pid: u32,
) -> Result<Box<dyn AsyncStream>> {
    #[cfg(unix)]
    {
        let stream = tokio::net::UnixStream::connect(socket_path)
            .await
            .with_context(|| format!("failed to connect to {}", socket_path.display()))?;
        verify_unix_ipc_server(&stream, expected_parent_pid)?;
        Ok(Box::new(stream) as Box<dyn AsyncStream>)
    }

    #[cfg(windows)]
    {
        use tokio::net::windows::named_pipe::ClientOptions;
        let pipe_name = socket_path.to_str().context("invalid pipe name")?;
        let stream = ClientOptions::new()
            .open(pipe_name)
            .with_context(|| format!("failed to connect to named pipe {pipe_name}"))?;
        verify_windows_ipc_server(&stream, expected_parent_pid)?;
        Ok(Box::new(stream) as Box<dyn AsyncStream>)
    }
}

#[cfg(unix)]
fn verify_unix_ipc_server(stream: &tokio::net::UnixStream, expected_parent_pid: u32) -> Result<()> {
    let credentials = stream
        .peer_cred()
        .context("failed to read IPC server peer credentials")?;
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
            .context("failed to read named-pipe server PID");
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

async fn write_frame<W: AsyncWrite + Unpin>(stream: &mut W, body: &[u8]) -> Result<()> {
    let len = u32::try_from(body.len()).context("IPC frame too large")?;
    stream.write_all(&len.to_be_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await?;
    Ok(())
}

async fn read_frame<R: AsyncRead + Unpin>(stream: &mut R) -> Result<Vec<u8>> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 || len > 16 * 1024 * 1024 {
        bail!("invalid IPC frame size={len}");
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await?;
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipc_error_preserves_code_for_downcast() {
        let error = Error::new(ipc_error(&ErrorResponse {
            message: "denied".to_string(),
            code: ErrorCode::UserDenied as i32,
        }));

        let ipc_error = error.downcast_ref::<IpcError>().expect("typed IPC error");
        assert_eq!(ipc_error.code(), ErrorCode::UserDenied);
        assert_eq!(ipc_error.message(), "denied");
    }

    #[test]
    fn ipc_error_defaults_unknown_code_to_unspecified() {
        let error = ipc_error(&ErrorResponse {
            message: "unknown".to_string(),
            code: i32::MAX,
        });

        assert_eq!(error.code(), ErrorCode::Unspecified);
    }

    #[test]
    fn ipc_error_survives_anyhow_context() {
        let error = Error::new(ipc_error(&ErrorResponse {
            message: "auth failed".to_string(),
            code: ErrorCode::AuthFailed as i32,
        }))
        .context("authentication failed");

        let ipc_error = error
            .downcast_ref::<IpcError>()
            .expect("context should preserve typed IPC error");
        assert_eq!(ipc_error.code(), ErrorCode::AuthFailed);
    }

    #[test]
    fn authentication_rejects_mismatched_protocol_revision() {
        let error = validate_authenticate_response(&AuthenticateResponse {
            success: true,
            protocol_revision: IPC_PROTOCOL_REVISION + 1,
        })
        .expect_err("revision mismatch")
        .to_string();

        assert!(error.contains("protocol revision mismatch"), "{error}");
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
}
