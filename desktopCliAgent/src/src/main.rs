//! Keyguard's CLI agent. The app starts it to serve the `keyguard` CLI, its
//! MCP adapter, and the local `.env` files; it asks the app for everything.
#[cfg(unix)]
use anyhow::Context;
use broker::Options;
use clap::Parser;
use keyguard_cli_protocol::socket;
use std::path::PathBuf;

mod app_client;
#[cfg(target_os = "macos")]
mod bookmark;
mod broker;
#[cfg(unix)]
mod fifo;
mod mounts;
mod protocol;
mod public;
mod transport;

#[derive(Parser)]
#[command(about = "Keyguard's app-owned CLI agent")]
struct Args {
    #[arg(long)]
    ipc_socket: PathBuf,
    #[arg(long)]
    parent_pid: u32,
    #[arg(long)]
    cli_socket: Option<PathBuf>,
    #[arg(long)]
    state_dir: Option<PathBuf>,
}

#[tokio::main]
async fn main() {
    let args = Args::parse();
    let result = async {
        let public_socket = args
            .cli_socket
            .map(Ok)
            .unwrap_or_else(socket::default_socket)?;
        let state_dir = match args.state_dir {
            Some(state_dir) => state_dir,
            None => default_state_dir()?,
        };
        broker::run(Options {
            ipc_socket: args.ipc_socket,
            parent_pid: args.parent_pid,
            public_socket,
            state_dir,
        })
        .await
    }
    .await;
    if result.is_err() {
        // Errors can contain caller-provided paths or parser excerpts. Never print them.
        eprintln!("Keyguard CLI agent stopped; consult Keyguard's agent status.");
        std::process::exit(1);
    }
}

/// The FIFO ownership records. They outlive a reboot, so that the FIFOs left
/// by a crash are recognized.
fn default_state_dir() -> anyhow::Result<PathBuf> {
    #[cfg(all(unix, not(target_os = "macos")))]
    return Ok(dirs::state_dir()
        .context("state directory unavailable")?
        .join("keyguard/cli-state"));
    #[cfg(target_os = "macos")]
    return Ok(dirs::home_dir()
        .context("home directory unavailable")?
        .join(".keyguard/cli-state"));
    #[cfg(windows)]
    return Ok(PathBuf::new());
}
