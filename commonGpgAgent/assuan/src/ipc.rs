//! Protobuf IPC client for the key operations behind the Assuan commands.

use anyhow::{bail, Result};
use common_gpg_agent_rust::messages::{
    ipc_request, ipc_response, CallerIdentity, ListKeysRequest, ListKeysResponse, PkdecryptRequest,
    PkdecryptResponse, SignHashRequest, SignHashResponse,
};
pub use keyguard_agent_ipc::client::BoxFuture;
pub use keyguard_agent_ipc::error::IpcError;
use std::sync::Arc;

#[cfg(test)]
pub(crate) use framed::FramedStream;

/// Carries one protobuf request to the Keyguard app and returns its response.
/// The transport puts `request` in an envelope and pairs the response to it.
pub trait IpcTransport: Send + Sync + 'static {
    /// Sends `request` and waits for the response to it. An error that the
    /// app answers with can also come back as an [`IpcError`].
    // A boxed future, not `impl Future + Send`: an opaque future here makes
    // the spawned Assuan sessions fail the higher-ranked `Send` check.
    fn round_trip(
        &self,
        request: ipc_request::Request,
    ) -> BoxFuture<'_, Result<ipc_response::Response>>;
}

/// Request/response client over an [`IpcTransport`].
#[derive(Clone)]
pub struct IpcClient {
    transport: Arc<dyn IpcTransport>,
}

impl IpcClient {
    /// Creates a client over `transport`.
    pub fn new(transport: impl IpcTransport) -> Self {
        Self {
            transport: Arc::new(transport),
        }
    }

    /// Lists the keys visible to `caller`.
    pub async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        let response = self
            .transport
            .round_trip(ipc_request::Request::ListKeys(ListKeysRequest { caller }))
            .await?;

        match response {
            ipc_response::Response::ListKeys(response) => Ok(response),
            ipc_response::Response::Error(error) => Err(IpcError::from(&error).into()),
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
            .transport
            .round_trip(ipc_request::Request::SignHash(SignHashRequest {
                keygrip: keygrip.to_string(),
                hash_algorithm: hash_algorithm.to_string(),
                hash: hash.to_vec(),
                caller,
            }))
            .await?;

        match response {
            ipc_response::Response::SignHash(response) => Ok(response),
            ipc_response::Response::Error(error) => Err(IpcError::from(&error).into()),
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
            .transport
            .round_trip(ipc_request::Request::Pkdecrypt(PkdecryptRequest {
                keygrip: keygrip.to_string(),
                ciphertext: ciphertext.to_vec(),
                unwrap_ecdh,
                caller,
            }))
            .await?;

        match response {
            ipc_response::Response::Pkdecrypt(response) => Ok(response),
            ipc_response::Response::Error(error) => Err(IpcError::from(&error).into()),
            other => bail!("unexpected pkdecrypt response: {other:?}"),
        }
    }
}

#[cfg(test)]
mod framed {
    use super::{BoxFuture, IpcTransport};
    use anyhow::{Context, Result};
    use common_gpg_agent_rust::messages::{ipc_request, ipc_response, IpcRequest, IpcResponse};
    use keyguard_agent_ipc::frame::{read_frame, write_frame, MAX_AGENT_FRAME};
    use prost::Message;
    use tokio::io::{AsyncRead, AsyncWrite};
    use tokio::sync::Mutex;

    /// [`IpcTransport`] over a byte stream with u32 big-endian length-prefixed
    /// frames, for tests that play the app. It sends one request at a time,
    /// so it leaves the request IDs at 0.
    pub(crate) struct FramedStream<S> {
        stream: Mutex<S>,
    }

    impl<S> FramedStream<S> {
        /// Wraps a connected stream.
        pub(crate) fn new(stream: S) -> Self {
            Self {
                stream: Mutex::new(stream),
            }
        }
    }

    impl<S> IpcTransport for FramedStream<S>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        fn round_trip(
            &self,
            request: ipc_request::Request,
        ) -> BoxFuture<'_, Result<ipc_response::Response>> {
            Box::pin(async move {
                let request = IpcRequest {
                    request: Some(request),
                    ..Default::default()
                };
                let mut stream = self.stream.lock().await;
                write_frame(&mut *stream, &request.encode_to_vec(), MAX_AGENT_FRAME).await?;
                let response_bytes = read_frame(&mut *stream, MAX_AGENT_FRAME).await?;
                IpcResponse::decode(&response_bytes[..])?
                    .response
                    .context("IPC response has no payload")
            })
        }
    }
}
