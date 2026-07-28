//! Minimal Assuan server implementation for the GPG agent protocol.

use crate::ipc::client::IpcClient;
use crate::ipc::messages::CallerIdentity;
use anyhow::Result;
use tokio::io::{AsyncRead, AsyncWrite, BufReader};
use tracing::debug;

mod commands;
mod syntax;
mod transport;

use commands::{handle_command, ERR_ASS_SYNTAX};
use syntax::parse_command;
use transport::{
    write_error, write_response, AssuanTimeouts, BoundedRead, DeadlineReader, DeadlineWriter,
    MAX_LINE_LEN,
};

#[derive(Default)]
struct SessionState {
    sigkey: Option<String>,
    sigkey_another: Option<String>,
    hash_algorithm: Option<String>,
    hash: Option<Vec<u8>>,
    hash_pss: bool,
    setkey: Option<String>,
    setkey_another: Option<String>,
}

#[derive(Default)]
struct CallerGuard {
    #[cfg(target_os = "macos")]
    macos: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    linux: Option<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>,
}

impl CallerGuard {
    fn revalidate(&self) -> Result<()> {
        #[cfg(target_os = "macos")]
        if let Some(identity) = self.macos.as_ref() {
            identity
                .revalidate()
                .map_err(|error| anyhow::anyhow!("macOS caller identity changed: {error}"))?;
        }
        #[cfg(target_os = "linux")]
        if let Some(identity) = self.linux.as_ref() {
            identity
                .revalidate()
                .map_err(|error| anyhow::anyhow!("Linux caller identity changed: {error}"))?;
        }
        Ok(())
    }
}

#[cfg_attr(any(target_os = "linux", target_os = "macos"), allow(dead_code))]
pub async fn serve_connection<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard::default(),
    )
    .await
}

#[cfg(target_os = "linux")]
pub async fn serve_connection_with_linux_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    linux_guard: Option<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard { linux: linux_guard },
    )
    .await
}

#[cfg(target_os = "macos")]
pub async fn serve_connection_with_macos_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    macos_guard: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard { macos: macos_guard },
    )
    .await
}

async fn serve_connection_with_timeouts_and_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    socket_name: String,
    timeouts: AssuanTimeouts,
    caller_guard: CallerGuard,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (read_half, write_half) = tokio::io::split(stream);
    let mut reader = DeadlineReader::new(BufReader::new(read_half), timeouts);
    let mut write_half = DeadlineWriter::new(write_half, timeouts.write);
    let mut state = SessionState::default();

    write_response(&mut write_half, "OK Keyguard GPG agent ready").await?;

    loop {
        let line = match reader.read_until_limited(b'\n', MAX_LINE_LEN).await? {
            BoundedRead::Eof => break,
            BoundedRead::TooLong => {
                // A single command line exceeded the cap. The stream can no
                // longer be framed reliably, matching libassuan's behavior:
                // close the connection without trying to write a response.
                break;
            }
            BoundedRead::Chunk(bytes) => bytes,
        };

        // Assuan command lines are ASCII text; reject non-UTF-8 input rather than
        // guessing at its meaning.
        let Ok(text) = std::str::from_utf8(&line) else {
            write_error(&mut write_half, ERR_ASS_SYNTAX, "invalid encoding").await?;
            break;
        };

        let request = text.trim_end_matches(['\r', '\n']);
        if request.is_empty() || request.starts_with('#') {
            continue;
        }
        if request.starts_with(' ') || request.starts_with('\t') {
            write_error(&mut write_half, ERR_ASS_SYNTAX, "invalid command").await?;
            continue;
        }

        let command = parse_command(request);
        // Log only the command verb, never the arguments: SETHASH carries the
        // message digest and SIGKEY/SETKEY carry keygrips, so keep those out of
        // the logs (which may be inherited into the parent app's output).
        debug!(command = %command.name, "Assuan command");
        let should_close = handle_command(
            command,
            &mut state,
            &ipc_client,
            &caller_guard,
            caller.clone(),
            &socket_name,
            &mut reader,
            &mut write_half,
        )
        .await?;
        if should_close {
            break;
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests;
