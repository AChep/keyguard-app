//! keyguard-ssh-agent: SSH agent for Keyguard password manager.
//!
//! This binary implements the SSH agent protocol and communicates with the
//! Keyguard desktop application over a Protobuf-based IPC channel to retrieve
//! SSH keys and request signing operations.

mod agent;
#[cfg(unix)]
mod caller_identity;
mod config;
mod ipc;
mod socket;

use anyhow::{Context, Result};
use clap::Parser;
use std::path::PathBuf;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

/// SSH agent for Keyguard password manager.
#[derive(Parser, Debug)]
#[command(name = "keyguard-ssh-agent", version, about)]
struct Args {
    /// Path to the IPC socket for communicating with the Keyguard app.
    #[arg(long)]
    ipc_socket: PathBuf,

    /// Path to the SSH agent socket to listen on.
    /// If not specified, uses a platform-specific default.
    #[arg(long)]
    ssh_socket: Option<PathBuf>,

    /// Enable verbose logging.
    #[arg(long, short)]
    verbose: bool,
}

fn decode_auth_token_from_stdin_line(line: &str) -> Result<Vec<u8>> {
    let hex_str = line.trim();
    if hex_str.is_empty() {
        anyhow::bail!(
            "No auth token received on stdin. \
             This binary should be launched by the Keyguard desktop app."
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
            "Auth token from stdin must decode to exactly 32 bytes; got {} bytes (input length: {} chars)",
            token.len(),
            hex_str.len()
        );
    }

    Ok(token)
}

fn zeroize_bytes(buf: &mut [u8]) {
    buf.fill(0);
}

fn zeroize_string(buf: &mut String) {
    // SAFETY: replacing bytes with zero keeps UTF-8 validity.
    unsafe {
        buf.as_bytes_mut().fill(0);
    }
    buf.clear();
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // Initialize logging.
    let filter = if args.verbose {
        EnvFilter::new("debug")
    } else {
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"))
    };
    tracing_subscriber::fmt().with_env_filter(filter).init();

    info!("keyguard-ssh-agent starting");

    // Read the authentication token from stdin. The parent process writes the
    // hex-encoded token followed by a newline, then closes stdin. This avoids
    // exposing the token in the process environment, which is readable by other
    // same-user processes via /proc/<pid>/environ (Linux) or ps eww (macOS).
    let mut auth_token = {
        use std::io::BufRead;
        let stdin = std::io::stdin();
        let mut line = String::new();
        stdin
            .lock()
            .read_line(&mut line)
            .context("Failed to read auth token from stdin")?;
        let decoded = decode_auth_token_from_stdin_line(&line)
            .context("Failed to parse auth token from stdin");
        zeroize_string(&mut line);
        decoded?
    };

    // Determine the SSH agent socket path.
    let ssh_socket_path = args
        .ssh_socket
        .unwrap_or_else(|| config::default_ssh_agent_socket_path());

    info!(
        ipc_socket = %args.ipc_socket.display(),
        ssh_socket = %ssh_socket_path.display(),
        "Configuration loaded"
    );

    // Connect to the Keyguard IPC server and authenticate.
    let ipc_client = ipc::client::IpcClient::connect(&args.ipc_socket, &auth_token)
        .await
        .context("Failed to connect to Keyguard IPC server");
    zeroize_bytes(&mut auth_token);
    let ipc_client = ipc_client?;
    info!("Authenticated with Keyguard IPC server");

    // Create the SSH agent session factory backed by the IPC client.
    let agent = agent::KeyguardAgentFactory::new(ipc_client);

    // Start serving the SSH agent protocol.
    info!(
        ssh_socket = %ssh_socket_path.display(),
        "Starting SSH agent"
    );
    socket::serve(agent, &ssh_socket_path).await.map_err(|e| {
        error!("SSH agent server failed: {}", e);
        e
    })?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::decode_auth_token_from_stdin_line;

    #[test]
    fn decode_auth_token_rejects_invalid_hex() {
        let err = decode_auth_token_from_stdin_line("zzzz")
            .unwrap_err()
            .to_string();
        assert!(
            err.contains("malformed hex"),
            "Expected malformed hex context, got: {}",
            err
        );
    }

    #[test]
    fn decode_auth_token_rejects_wrong_length() {
        let err = decode_auth_token_from_stdin_line("aa")
            .unwrap_err()
            .to_string();
        assert!(
            err.contains("exactly 32 bytes"),
            "Expected explicit length error, got: {}",
            err
        );
    }

    #[test]
    fn decode_auth_token_accepts_32_bytes() {
        let token = decode_auth_token_from_stdin_line(&"ab".repeat(32)).unwrap();
        assert_eq!(token.len(), 32);
    }
}
