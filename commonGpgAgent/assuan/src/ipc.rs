//! Protobuf IPC client for the key operations behind the Assuan commands.

use anyhow::{bail, Context, Result};
use common_gpg_agent_rust::messages::{
    ipc_request, ipc_response, CallerIdentity, ErrorCode, ErrorResponse, IpcRequest, IpcResponse,
    ListKeysRequest, ListKeysResponse, PkdecryptRequest, PkdecryptResponse, SignHashRequest,
    SignHashResponse,
};
use prost::Message;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::Mutex;

/// Upper bound of one length-prefixed protobuf frame.
pub const MAX_FRAME_SIZE: usize = 16 * 1024 * 1024;

/// Carries one protobuf request to the Keyguard app and returns its response.
pub trait IpcTransport: Send + Sync + 'static {
    /// Sends `request` and waits for the response to it.
    // A boxed future, not `impl Future + Send`: an opaque future here makes
    // the spawned Assuan sessions fail the higher-ranked `Send` check.
    fn round_trip(&self, request: IpcRequest) -> BoxFuture<'_, Result<IpcResponse>>;
}

/// The boxed future returned by [`IpcTransport::round_trip`].
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Request/response client over an [`IpcTransport`].
#[derive(Clone)]
pub struct IpcClient {
    transport: Arc<dyn IpcTransport>,
    next_id: Arc<AtomicU64>,
}

/// Typed error reported by the Keyguard app.
#[derive(Clone, Debug)]
pub struct IpcError {
    code: ErrorCode,
    message: String,
}

impl IpcError {
    /// Creates an error with the app's protocol code and free-form message.
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    /// The protocol error code.
    pub fn code(&self) -> ErrorCode {
        self.code
    }

    /// The app's free-form message. It may contain vault data, so never log it.
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
    /// Creates a client whose request IDs start at 1.
    pub fn new(transport: impl IpcTransport) -> Self {
        Self {
            transport: Arc::new(transport),
            next_id: Arc::new(AtomicU64::new(1)),
        }
    }

    /// Sends `request` under a fresh ID and checks that the response echoes it.
    pub async fn send(&self, request: ipc_request::Request) -> Result<IpcResponse> {
        let expected_id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let response = self
            .transport
            .round_trip(IpcRequest {
                id: expected_id,
                request: Some(request),
            })
            .await?;
        if response.id != expected_id {
            bail!(
                "protobuf response id mismatch: expected {} got {}",
                expected_id,
                response.id
            );
        }
        Ok(response)
    }

    /// Lists the keys visible to `caller`.
    pub async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        let response = self
            .send(ipc_request::Request::ListKeys(ListKeysRequest { caller }))
            .await?;

        match response.response {
            Some(ipc_response::Response::ListKeys(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected list_keys response: {other:?}"),
        }
    }

    /// Signs a digest with the key identified by `keygrip`.
    pub async fn sign_hash(
        &self,
        keygrip: &str,
        hash_algorithm: &str,
        hash: &[u8],
        caller: Option<CallerIdentity>,
    ) -> Result<SignHashResponse> {
        let response = self
            .send(ipc_request::Request::SignHash(SignHashRequest {
                keygrip: keygrip.to_string(),
                hash_algorithm: hash_algorithm.to_string(),
                hash: hash.to_vec(),
                caller,
            }))
            .await?;

        match response.response {
            Some(ipc_response::Response::SignHash(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected sign_hash response: {other:?}"),
        }
    }

    /// Decrypts a ciphertext S-expression with the key identified by `keygrip`.
    pub async fn pkdecrypt(
        &self,
        keygrip: &str,
        ciphertext: &[u8],
        unwrap_ecdh: bool,
        caller: Option<CallerIdentity>,
    ) -> Result<PkdecryptResponse> {
        let response = self
            .send(ipc_request::Request::Pkdecrypt(PkdecryptRequest {
                keygrip: keygrip.to_string(),
                ciphertext: ciphertext.to_vec(),
                unwrap_ecdh,
                caller,
            }))
            .await?;

        match response.response {
            Some(ipc_response::Response::Pkdecrypt(response)) => Ok(response),
            Some(ipc_response::Response::Error(error)) => Err(ipc_error(&error).into()),
            other => bail!("unexpected pkdecrypt response: {other:?}"),
        }
    }
}

/// Converts an IPC `ErrorResponse` into a typed error, preserving the
/// locked/denied/not-found distinction carried in `code` so the Assuan layer
/// can map it to an appropriate response.
pub fn ipc_error(error: &ErrorResponse) -> IpcError {
    let code = ErrorCode::try_from(error.code).unwrap_or(ErrorCode::Unspecified);
    IpcError::new(code, error.message.clone())
}

/// [`IpcTransport`] over a byte stream with u32 big-endian length-prefixed frames.
pub struct FramedStream<S> {
    stream: Mutex<S>,
}

impl<S> FramedStream<S> {
    /// Wraps a connected stream.
    pub fn new(stream: S) -> Self {
        Self {
            stream: Mutex::new(stream),
        }
    }
}

impl<S> IpcTransport for FramedStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    fn round_trip(&self, request: IpcRequest) -> BoxFuture<'_, Result<IpcResponse>> {
        Box::pin(async move {
            let request_bytes = request.encode_to_vec();
            let mut stream = self.stream.lock().await;
            write_frame(&mut *stream, &request_bytes).await?;
            let response_bytes = read_frame(&mut *stream).await?;
            Ok(IpcResponse::decode(&response_bytes[..])?)
        })
    }
}

/// Writes one length-prefixed frame.
pub async fn write_frame<W: AsyncWrite + Unpin>(stream: &mut W, body: &[u8]) -> Result<()> {
    let len = u32::try_from(body.len()).context("IPC frame too large")?;
    stream.write_all(&len.to_be_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await?;
    Ok(())
}

/// Reads one length-prefixed frame.
pub async fn read_frame<R: AsyncRead + Unpin>(stream: &mut R) -> Result<Vec<u8>> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 || len > MAX_FRAME_SIZE {
        bail!("invalid IPC frame size={len}");
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await?;
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Error;

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

    #[tokio::test]
    async fn mismatched_response_id_is_rejected() {
        let (stream, mut peer) = tokio::io::duplex(1024);
        let client = IpcClient::new(FramedStream::new(stream));
        let backend = tokio::spawn(async move {
            let request =
                IpcRequest::decode(read_frame(&mut peer).await.unwrap().as_slice()).unwrap();
            let response = IpcResponse {
                id: request.id + 1,
                response: None,
            };
            write_frame(&mut peer, &response.encode_to_vec())
                .await
                .unwrap();
        });

        let error = client.list_keys(None).await.unwrap_err().to_string();
        backend.await.unwrap();
        assert!(error.contains("id mismatch"), "{error}");
    }
}
