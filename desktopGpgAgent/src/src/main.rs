//! keyguard-gpg-agent: GPG agent bridge for Keyguard.

mod config;
mod ipc;
mod socket;

use anyhow::{Context, Result};
use clap::Parser;
use keyguard_agent_ipc::launch;
use keyguard_gpg_agent_assuan::version;
use std::path::PathBuf;
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

    /// Stable private lifecycle lock directory for a sandboxed Unix host.
    #[arg(long)]
    lifecycle_lock_dir: Option<PathBuf>,

    /// GnuPG client version selected by the desktop host. Invalid values fall back.
    #[arg(long)]
    gnupg_version: Option<String>,

    /// Enable debug logging. Otherwise RUST_LOG applies, defaulting to warnings and errors.
    #[arg(long, short)]
    verbose: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    #[cfg(windows)]
    if args.lifecycle_lock_dir.is_some() {
        anyhow::bail!("--lifecycle-lock-dir is only supported on Unix");
    }

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

    let launch = launch::read_launch()
        .await
        .context("failed to read the auth token from stdin")?;

    let gpg_socket_path = resolve_gpg_socket_path(args.gpg_socket)?;

    info!(
        ipc_socket = %args.ipc_socket.display(),
        gpg_socket = %gpg_socket_path.display(),
        "Configuration loaded"
    );

    // The client keeps the token to re-authenticate after a reconnect.
    let ipc_client = ipc::client::connect(&args.ipc_socket, launch.token, args.parent_pid)
        .await
        .context("failed to connect to Keyguard IPC server")?;
    info!("authenticated with Keyguard IPC server");

    let gnupg_version = version::resolve_gnupg_version(args.gnupg_version);
    info!(
        %gnupg_version,
        keyguard_version = version::KEYGUARD_VERSION,
        "agent versions"
    );

    socket::serve(
        ipc_client,
        gnupg_version,
        &gpg_socket_path,
        args.lifecycle_lock_dir.as_deref(),
        launch.parent_gone,
        || {
            launch::write_ready_record()
                .context("failed to write GPG agent startup readiness record")
        },
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
    #[cfg(any(unix, windows))]
    use super::resolve_gpg_socket_path;
    #[cfg(unix)]
    use std::path::PathBuf;

    #[test]
    fn lifecycle_lock_directory_is_opt_in() {
        use clap::Parser;
        let base = [
            "keyguard-gpg-agent",
            "--ipc-socket",
            "/tmp/test-ipc",
            "--parent-pid",
            "123",
        ];
        let defaults = super::Args::try_parse_from(base).expect("default arguments");
        assert!(defaults.lifecycle_lock_dir.is_none());
        let configured = super::Args::try_parse_from(
            base.into_iter()
                .chain(["--lifecycle-lock-dir", "/private/app-group/gpg/locks"]),
        )
        .expect("sandbox arguments");
        assert_eq!(
            configured.lifecycle_lock_dir.as_deref(),
            Some(std::path::Path::new("/private/app-group/gpg/locks"))
        );
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
