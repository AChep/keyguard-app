//! keyguard-browser-agent: browser autofill agent for Keyguard.
//!
//! This binary bridges the Keyguard browser extension and the Keyguard desktop
//! application over the agent IPC channel.
//!
//! Two communication modes:
//! - **WebSocket** (`--ws-port`): For Safari and fallback. ECDH + AES-256-GCM
//!   transport, with optional HMAC challenge-response when `--secret-path` is
//!   provided.
//! - **Native Messaging** (`--native-messaging`): For Firefox/Chrome/Edge.
//!   stdin/stdout with 4-byte LE length prefix. No HMAC — the browser already
//!   verifies the extension ID via `allowed_extensions` in the NM manifest.

mod crypto;
mod ipc;
mod pairing;
mod protocol;
mod ws;
mod native;

use anyhow::{Context, Result};
use clap::Parser;
use std::io::{BufRead, Read};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Notify;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

/// Browser autofill agent for Keyguard password manager.
#[derive(Parser, Debug)]
#[command(name = "keyguard-browser-agent", version, about)]
struct Args {
    /// Path to the IPC socket for communicating with the Keyguard app.
    #[arg(long)]
    ipc_socket: Option<PathBuf>,

    /// PID of the Keyguard desktop process that owns the private IPC server.
    #[arg(long)]
    parent_pid: Option<u32>,

    /// Port to bind the WebSocket server on (127.0.0.1 only).
    #[arg(long, default_value_t = 40432)]
    ws_port: u16,

    /// Run in Native Messaging mode (stdin/stdout, 4-byte LE).
    /// When set, the agent communicates via Native Messaging instead of WebSocket.
    #[arg(long)]
    native_messaging: bool,

    /// Path to the shared secret file for HMAC verification (Safari WS path).
    /// When not set, HMAC verification is disabled.
    #[arg(long)]
    secret_path: Option<PathBuf>,

    /// Enable verbose logging.
    #[arg(long, short)]
    verbose: bool,

    /// Extra positional arguments (ignored).
    /// Firefox passes the manifest path and extension ID as positional args.
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    _extra: Vec<String>,
}

/// Session data written by the Kotlin app for the NM host.
#[derive(serde::Deserialize)]
struct SessionData {
    auth_token: String,
    ipc_socket: String,
}

fn read_auth_token_from_stdin() -> Result<Vec<u8>> {
    let stdin = std::io::stdin();
    let mut line = String::new();
    stdin
        .lock()
        .read_line(&mut line)
        .context("Failed to read auth token from stdin")?;
    let hex_str = line.trim();
    if hex_str.is_empty() {
        anyhow::bail!("No auth token received on stdin. This binary should be launched by the Keyguard desktop app.");
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

fn read_session_file() -> Result<(Vec<u8>, PathBuf)> {
    let session_path = std::env::var("HOME")
        .map(|home| {
            std::path::PathBuf::from(home).join(".config/keyguard/agent-session.json")
        })
        .context("Cannot determine HOME for session file")?;
    let session_json = std::fs::read_to_string(&session_path).with_context(|| {
        format!(
            "Failed to read NM session file {}. Is the Keyguard app running?",
            session_path.display()
        )
    })?;
    let session: SessionData =
        serde_json::from_str(&session_json).with_context(|| {
            format!("Malformed session file: {}", session_path.display())
        })?;
    let token = hex::decode(&session.auth_token)
        .context("Session file auth_token is not valid hex")?;
    if token.len() != 32 {
        anyhow::bail!(
            "Session auth_token must be 32 bytes; got {}",
            token.len()
        );
    }
    Ok((token, PathBuf::from(session.ipc_socket)))
}

fn spawn_stdin_eof_watcher() -> Result<tokio::sync::oneshot::Receiver<()>> {
    let (sender, receiver) = tokio::sync::oneshot::channel();
    std::thread::Builder::new()
        .name("keyguard-parent-stdin-watch".to_string())
        .spawn(move || {
            let stdin = std::io::stdin();
            let mut buf = [0u8; 1024];
            loop {
                match stdin.lock().read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
            }
            let _ = sender.send(());
        })
        .context("Failed to spawn stdin liveness watcher")?;
    Ok(receiver)
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    let filter = if args.verbose {
        EnvFilter::new("debug")
    } else {
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"))
    };
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .init();

    info!("keyguard-browser-agent starting");

    let shutdown = Arc::new(Notify::new());

    // In NM mode: the Kotlin app writes a session file with the auth token
    // and IPC socket path. Firefox occupies stdin with NM JSON, so we cannot
    // read the auth token from stdin. In WS mode: read auth token from stdin
    // (sent by the Kotlin app as the parent process).
    // Auto-detect Native Messaging mode: if no --ipc-socket is provided,
    // we were launched by the browser (Firefox passes manifest path +
    // extension ID as positional args, not --native-messaging).
    let native_messaging = args.native_messaging || args.ipc_socket.is_none();
    let (mut auth_token, ipc_socket_path, parent_pid) = if native_messaging {
        let (token, socket) = read_session_file()?;
        (token, socket, 0u32)
    } else {
        let token = read_auth_token_from_stdin()?;
        let socket = args.ipc_socket
            .context("--ipc-socket is required in WebSocket mode")?;
        let pid = args.parent_pid
            .context("--parent-pid is required in WebSocket mode")?;
        (token, socket, pid)
    };

    let eof = if native_messaging {
        // In NM mode, stdin is Firefox's NM protocol — don't watch for EOF
        // as a parent-death signal. Instead, we rely on SIGTERM/SIGINT or
        // the NM host being killed by Firefox.
        let (tx, rx) = tokio::sync::oneshot::channel();
        // Never send — NM host lifetime is managed by Firefox.
        std::mem::forget(tx);
        rx
    } else {
        spawn_stdin_eof_watcher()?
    };

    // React to parent death (stdin EOF) by requesting a graceful shutdown.
    let shutdown_eof = shutdown.clone();
    tokio::spawn(async move {
        if eof.await.is_ok() {
            info!("Parent stdin closed; initiating shutdown");
            shutdown_eof.notify_one();
        }
    });

    // React to process signals (SIGTERM sent by the desktop app on stop, or a
    // Ctrl-C) by requesting a graceful shutdown too.
    let shutdown_signal = shutdown.clone();
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut sigterm = signal(SignalKind::terminate())
            .expect("failed to install SIGTERM handler");
        let mut sigint = signal(SignalKind::interrupt())
            .expect("failed to install SIGINT handler");
        tokio::spawn(async move {
            tokio::select! {
                _ = sigterm.recv() => info!("Received SIGTERM"),
                _ = sigint.recv() => info!("Received SIGINT"),
            }
            shutdown_signal.notify_one();
        });
    }
    #[cfg(not(unix))]
    {
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                info!("Received Ctrl-C");
                shutdown_signal.notify_one();
            }
        });
    }

    info!(
        ipc_socket = %ipc_socket_path.display(),
        ws_port = args.ws_port,
        native_messaging = native_messaging,
        "Configuration loaded"
    );

    // Route to the appropriate server mode.
    let result = if native_messaging {
        info!("Starting in Native Messaging mode (stdin/stdout)");
        native::serve(
            ipc_socket_path,
            auth_token.clone(),
            parent_pid,
            shutdown,
        )
        .await
    } else {
        info!("Starting in WebSocket mode on port {}", args.ws_port);
        ws::serve(
            args.ws_port,
            ipc_socket_path,
            auth_token.clone(),
            parent_pid,
            shutdown,
            args.secret_path,
        )
        .await
    };

    // Best-effort zeroization of the auth token.
    auth_token.fill(0);

    if let Err(e) = result {
        error!("Browser agent server failed: {e}");
        return Err(e);
    }
    Ok(())
}
