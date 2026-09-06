//! keyguard-gpg-agent: GPG agent bridge for Keyguard.

mod assuan;
#[cfg(unix)]
mod caller_identity;
mod config;
mod ipc;
mod socket;

use anyhow::{Context, Result};
use clap::Parser;
use std::io::Read;
use std::path::PathBuf;
use tokio::sync::oneshot;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "keyguard-gpg-agent", version, about)]
struct Args {
    /// Path to the IPC socket for communicating with the Keyguard app.
    #[arg(long)]
    ipc_socket: PathBuf,

    /// PID of the Keyguard desktop process that owns the private IPC server.
    #[arg(long)]
    parent_pid: u32,

    /// Path to the GPG agent socket to listen on.
    #[arg(long)]
    gpg_socket: Option<PathBuf>,

    /// Enable debug logging. Otherwise RUST_LOG applies, defaulting to warnings and errors.
    #[arg(long, short)]
    verbose: bool,
}

fn decode_auth_token_from_stdin_line(line: &str) -> Result<Vec<u8>> {
    let hex_str = line.trim();
    if hex_str.is_empty() {
        anyhow::bail!(
            "No auth token received on stdin. This binary should be launched by Keyguard."
        );
    }

    let token = hex::decode(hex_str).with_context(|| {
        format!(
            "Auth token from stdin is malformed hex (expected 64 hex chars / 32 bytes, got {} chars)",
            hex_str.len()
        )
    })?;
    if token.len() != 32 {
        anyhow::bail!(
            "Auth token from stdin must decode to exactly 32 bytes; got {} bytes",
            token.len()
        );
    }
    Ok(token)
}

fn spawn_stdin_eof_watcher() -> Result<oneshot::Receiver<()>> {
    let (sender, receiver) = oneshot::channel();
    std::thread::Builder::new()
        .name("keyguard-gpg-parent-stdin-watch".to_string())
        .spawn(move || {
            let stdin = std::io::stdin();
            let mut stdin = stdin.lock();
            let mut buf = [0u8; 1024];
            loop {
                match stdin.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
            }
            let _ = sender.send(());
        })
        .context("failed to spawn stdin liveness watcher")?;
    Ok(receiver)
}

fn zeroize_bytes(buf: &mut [u8]) {
    buf.fill(0);
}

fn zeroize_string(buf: &mut String) {
    // SAFETY: Replacing every byte with ASCII NUL leaves the string as valid
    // UTF-8, and `fill` does not change its length or capacity.
    unsafe {
        buf.as_bytes_mut().fill(0);
    }
    buf.clear();
}

fn write_startup_ready_record() -> Result<()> {
    let stdout = std::io::stdout();
    keyguard_agent_identity::write_startup_ready_record_to(stdout.lock())
        .context("failed to write GPG agent startup readiness record")
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // Keep routine activity out of logs in every build unless explicitly enabled.
    let filter = if args.verbose {
        EnvFilter::new("debug")
    } else {
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("warn"))
    };
    // Stdout is reserved for the machine-readable startup handshake consumed
    // by the desktop app. Keep all human-readable tracing on stderr.
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .init();
    info!("keyguard-gpg-agent starting");

    let mut auth_token = {
        use std::io::BufRead;
        let stdin = std::io::stdin();
        let mut line = String::new();
        stdin
            .lock()
            .read_line(&mut line)
            .context("failed to read auth token from stdin")?;
        let decoded = decode_auth_token_from_stdin_line(&line)
            .context("failed to parse auth token from stdin");
        zeroize_string(&mut line);
        decoded?
    };
    let parent_stdin_closed = spawn_stdin_eof_watcher()?;

    let gpg_socket_path = resolve_gpg_socket_path(args.gpg_socket)?;

    info!(
        ipc_socket = %args.ipc_socket.display(),
        gpg_socket = %gpg_socket_path.display(),
        "Configuration loaded"
    );

    let ipc_client =
        ipc::client::IpcClient::connect(&args.ipc_socket, &auth_token, args.parent_pid)
            .await
            .context("failed to connect to Keyguard IPC server");
    zeroize_bytes(&mut auth_token);
    let ipc_client = ipc_client?;
    info!("authenticated with Keyguard IPC server");

    socket::serve(
        ipc_client,
        &gpg_socket_path,
        parent_stdin_closed,
        write_startup_ready_record,
    )
    .await
    .map_err(|e| {
        error!("GPG agent server failed: {e}");
        e
    })?;

    Ok(())
}

fn resolve_gpg_socket_path(configured: Option<PathBuf>) -> Result<PathBuf> {
    #[cfg(windows)]
    {
        configured.context("--gpg-socket is required on Windows")
    }

    #[cfg(unix)]
    {
        match configured {
            Some(socket_path) => Ok(socket_path),
            None => config::default_gpg_agent_socket_path()
                .context("failed to resolve the default GPG agent socket"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::decode_auth_token_from_stdin_line;
    #[cfg(any(unix, windows))]
    use super::resolve_gpg_socket_path;
    #[cfg(unix)]
    use std::path::PathBuf;

    #[test]
    fn auth_token_rejects_wrong_length() {
        let err = decode_auth_token_from_stdin_line("aa")
            .unwrap_err()
            .to_string();
        assert!(err.contains("exactly 32 bytes"));
    }

    #[test]
    fn auth_token_accepts_32_bytes() {
        let token = decode_auth_token_from_stdin_line(&"ab".repeat(32)).unwrap();
        assert_eq!(token.len(), 32);
    }

    #[cfg(unix)]
    #[test]
    fn explicit_unix_gpg_socket_bypasses_default_resolution() {
        let configured = PathBuf::from("/tmp/keyguard-explicit-gpg.sock");

        assert_eq!(
            resolve_gpg_socket_path(Some(configured.clone())).unwrap(),
            configured
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_requires_an_explicit_gpg_socket() {
        let err = resolve_gpg_socket_path(None).unwrap_err().to_string();
        assert!(err.contains("--gpg-socket is required on Windows"));
    }
}
