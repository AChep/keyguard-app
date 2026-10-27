//! keyguard-ssh-agent: SSH agent for Keyguard password manager.
//!
//! This binary implements the SSH agent protocol and communicates with the
//! Keyguard desktop application over a Protobuf-based IPC channel to retrieve
//! SSH keys and request signing operations.

mod agent;
mod config;
mod ipc;
mod socket;
#[cfg(windows)]
mod windows_identity;

use anyhow::{Context, Result};
use clap::Parser;
use keyguard_agent_ipc::launch;
use std::path::PathBuf;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

/// SSH agent for Keyguard password manager.
#[derive(Parser, Debug)]
#[command(name = "keyguard-ssh-agent", version, about)]
struct Args {
    /// Path to the IPC socket for communicating with the Keyguard app.
    #[arg(long, required_unless_present = "print_socket")]
    ipc_socket: Option<PathBuf>,

    /// PID of the Keyguard desktop process that owns the private IPC server.
    #[arg(long, required_unless_present = "print_socket")]
    parent_pid: Option<u32>,

    /// Print the resolved socket path without starting or contacting an agent.
    #[arg(long)]
    print_socket: bool,

    /// Path to the SSH agent socket to listen on.
    /// If not specified, uses a platform-specific default.
    #[arg(long)]
    ssh_socket: Option<PathBuf>,

    /// Enable debug logging. Otherwise RUST_LOG applies, defaulting to warnings and errors.
    #[arg(long, short)]
    verbose: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let ssh_socket_path = args
        .ssh_socket
        .unwrap_or_else(config::default_ssh_agent_socket_path);
    if args.print_socket {
        println!("{}", ssh_socket_path.display());
        return Ok(());
    }
    let ipc_socket = args.ipc_socket.context("--ipc-socket is required")?;
    let parent_pid = args.parent_pid.context("--parent-pid is required")?;

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

    info!("keyguard-ssh-agent starting");

    // The parent process writes the hex-encoded token followed by a newline
    // to stdin, then keeps it open for the lifetime of this process. This
    // avoids exposing the token in the process environment, which is readable
    // by other same-user processes via /proc/<pid>/environ (Linux) or ps eww
    // (macOS), and gives us a parent liveness signal via EOF.
    let launch = launch::read_launch()
        .await
        .context("Failed to read auth token from stdin")?;

    info!(
        ipc_socket = %ipc_socket.display(),
        ssh_socket = %ssh_socket_path.display(),
        "Configuration loaded"
    );

    // Connect to the Keyguard IPC server and authenticate.
    // The client keeps the token to re-authenticate after a reconnect.
    let ipc_client = ipc::client::IpcClient::connect(&ipc_socket, launch.token, parent_pid)
        .await
        .context("Failed to connect to Keyguard IPC server")?;
    info!("Authenticated with Keyguard IPC server");

    // Create the SSH agent session factory backed by the IPC client.
    let agent = agent::KeyguardAgentFactory::new(ipc_client);

    // Start serving the SSH agent protocol.
    info!(
        ssh_socket = %ssh_socket_path.display(),
        "Starting SSH agent"
    );
    socket::serve(agent, &ssh_socket_path, launch.parent_gone, || {
        launch::write_ready_record().context("Failed to write SSH agent startup readiness record")
    })
    .await
    .map_err(|e| {
        error!("SSH agent server failed: {}", e);
        e
    })?;

    Ok(())
}

#[cfg(test)]
mod args_tests {
    use super::*;

    #[test]
    fn discovery_does_not_require_runtime_credentials() {
        assert!(
            Args::try_parse_from(["agent", "--print-socket"])
                .unwrap()
                .print_socket
        );
        assert!(Args::try_parse_from(["agent"]).is_err());
        assert!(Args::try_parse_from(["agent", "--ipc-socket", "/ipc"]).is_err());
    }
}
