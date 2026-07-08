//! Windows named pipe serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use std::path::Path;
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use tokio::sync::oneshot;
use tracing::{info, warn};

/// Serves the GPG agent (Assuan) protocol over a Windows named pipe.
///
/// The pipe name is expected to be in the format `\\.\pipe\keyguard-gpg-agent`.
/// Caller identity (peer credentials) is not derived on Windows in this
/// iteration; approval is delegated to the Keyguard app regardless.
pub async fn serve(
    ipc_client: IpcClient,
    pipe_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    let pipe_name = pipe_path
        .to_str()
        .context("invalid pipe name (not valid UTF-8)")?
        .to_string();

    info!(pipe = %pipe_name, "GPG agent listening on Windows named pipe");

    tokio::select! {
        result = accept_loop(ipc_client, pipe_name.clone()) => {
            result?;
        }
        _ = parent_stdin_closed => {
            info!("parent stdin closed, stopping GPG agent listener");
        }
    }

    Ok(())
}

async fn accept_loop(ipc_client: IpcClient, pipe_name: String) -> Result<()> {
    // Create the first server instance up front; `ServerOptions::first_pipe_instance`
    // guards against another process having already claimed the name.
    let mut server = ServerOptions::new()
        .first_pipe_instance(true)
        .create(&pipe_name)
        .with_context(|| format!("failed to create named pipe {pipe_name}"))?;

    loop {
        // Wait for a client to connect to the current server instance.
        server
            .connect()
            .await
            .with_context(|| format!("failed to accept on named pipe {pipe_name}"))?;
        let connected: NamedPipeServer = server;

        // Immediately create the next server instance so the next client can
        // connect while we handle the current one.
        server = ServerOptions::new()
            .create(&pipe_name)
            .with_context(|| format!("failed to create named pipe {pipe_name}"))?;

        let ipc_client = ipc_client.clone();
        let socket_name = pipe_name.clone();
        tokio::spawn(async move {
            if let Err(e) = assuan::serve_connection(connected, ipc_client, None, socket_name).await
            {
                warn!("GPG Assuan connection failed: {e}");
            }
        });
    }
}
