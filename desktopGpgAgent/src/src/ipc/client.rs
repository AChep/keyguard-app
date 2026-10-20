//! Authenticated protobuf IPC connection to the Keyguard desktop app.

use anyhow::{bail, Context, Error, Result};
use common_gpg_agent_rust::messages::{
    ipc_request, ipc_response, AuthenticateRequest, AuthenticateResponse,
};
#[cfg(windows)]
use keyguard_agent_identity::ipc_server::verify_named_pipe_ipc_server;
#[cfg(unix)]
use keyguard_agent_identity::ipc_server::verify_unix_ipc_server;
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_gpg_agent_assuan::ipc::{ipc_error, FramedStream};
use std::path::Path;
use tokio::io::{AsyncRead, AsyncWrite};

/// Trait alias for streams that support both async read and write, allowing the
/// IPC client to be backed by a Unix socket (macOS/Linux) or a named pipe
/// (Windows).
pub trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

pub use keyguard_gpg_agent_assuan::ipc::IpcClient;

pub async fn connect(
    socket_path: &Path,
    auth_token: &[u8],
    expected_parent_pid: u32,
) -> Result<IpcClient> {
    if expected_parent_pid == 0 {
        bail!("expected parent PID must be non-zero");
    }
    let stream = connect_stream(socket_path, expected_parent_pid).await?;
    let client = IpcClient::new(FramedStream::new(stream));

    let response = client
        .send(ipc_request::Request::Authenticate(AuthenticateRequest {
            token: auth_token.to_vec(),
            protocol_revision: IPC_PROTOCOL_REVISION,
        }))
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
        verify_unix_ipc_server(&stream, Some(expected_parent_pid))?;
        Ok(Box::new(stream) as Box<dyn AsyncStream>)
    }

    #[cfg(windows)]
    {
        use tokio::net::windows::named_pipe::ClientOptions;
        let pipe_name = socket_path.to_str().context("invalid pipe name")?;
        let stream = ClientOptions::new()
            .open(pipe_name)
            .with_context(|| format!("failed to connect to named pipe {pipe_name}"))?;
        verify_named_pipe_ipc_server(&stream, Some(expected_parent_pid))?;
        Ok(Box::new(stream) as Box<dyn AsyncStream>)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
