//! Length-prefixed protobuf transport for desktop IPC.

use anyhow::{bail, Result};
use bytes::{Buf, BufMut, BytesMut};
use prost::Message;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tracing::trace;

use super::super::messages::{IpcRequest, IpcResponse};

/// Maximum IPC message size (16 MB). Protects against malformed length prefixes.
pub(super) const MAX_MESSAGE_SIZE: u32 = 16 * 1024 * 1024;

/// Trait alias for streams that support both async read and write.
/// This allows `IpcClient` to be constructed with either a real platform
/// socket or a test duplex stream.
pub(super) trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

/// Length-prefixed protobuf message transport, generic over the underlying
/// async stream. This allows tests to substitute `tokio::io::DuplexStream`
/// in place of a real socket.
pub(super) struct IpcStream<S> {
    pub(super) stream: S,
    buf: BytesMut,
}

impl<S: AsyncRead + AsyncWrite + Unpin> IpcStream<S> {
    /// Creates a new `IpcStream` wrapping the given async stream.
    pub(super) fn new(stream: S) -> Self {
        Self {
            stream,
            buf: BytesMut::with_capacity(4096),
        }
    }

    /// Writes a length-prefixed protobuf `IpcRequest` to the stream.
    pub(super) async fn write_message(&mut self, msg: &IpcRequest) -> Result<()> {
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
    pub(super) async fn read_message(&mut self) -> Result<IpcResponse> {
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
    pub(super) async fn write_response(&mut self, msg: &IpcResponse) -> Result<()> {
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
    pub(super) async fn read_request(&mut self) -> Result<IpcRequest> {
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
