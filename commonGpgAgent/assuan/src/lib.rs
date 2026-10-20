//! Minimal Assuan server implementation for the GPG agent protocol.
//!
//! The server answers protocol commands itself and forwards key listing,
//! signing, and decryption to the Keyguard app through an [`IpcTransport`](ipc::IpcTransport).

#[cfg(unix)]
pub mod gpgconf;
pub mod ipc;
pub mod version;

use crate::ipc::IpcClient;
use anyhow::Result;
use common_gpg_agent_rust::messages::CallerIdentity;
use keyguard_agent_identity::caller_guard::CallerGuard;
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncWrite, BufReader};
use tracing::debug;

/// Connection-independent answers to `GETINFO`.
pub struct AgentInfo {
    socket_name: String,
    gnupg_version: String,
}

impl AgentInfo {
    /// `socket_name` is the public socket path; `gnupg_version` is reported as
    /// the agent version so GnuPG does not warn about an older agent.
    pub fn new(socket_name: String, gnupg_version: String) -> Self {
        Self {
            socket_name,
            gnupg_version,
        }
    }

    #[cfg(test)]
    fn for_test() -> Arc<Self> {
        Arc::new(Self::new("test".into(), "2.5.24".into()))
    }
}

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
    // SIGKEY is an alias of SETKEY in gpg-agent, so both commands share these
    // selections. They persist until RESET or disconnect.
    keygrip: Option<String>,
    keygrip_another: Option<String>,
    hash_algorithm: Option<String>,
    hash: Option<Vec<u8>>,
    hash_pss: bool,
}

/// Serves one Assuan connection; `caller_guard` is revalidated before each
/// key listing, signature, and decryption.
pub async fn serve_connection<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    caller_guard: CallerGuard,
    info: Arc<AgentInfo>,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        info,
        AssuanTimeouts::default(),
        caller_guard,
    )
    .await
}

async fn serve_connection_with_timeouts_and_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    info: Arc<AgentInfo>,
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
            &info,
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
