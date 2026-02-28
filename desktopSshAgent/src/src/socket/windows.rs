//! Windows named pipe SSH agent server.

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use anyhow::{Context, Result};
use std::path::Path;
use tracing::info;

/// Serves the SSH agent protocol over a Windows named pipe.
///
/// The pipe name is expected to be in the format `\\.\pipe\keyguard-ssh-agent`.
pub async fn serve<K: KeyProvider>(agent: KeyguardAgentFactory<K>, pipe_path: &Path) -> Result<()> {
    let pipe_name = pipe_path
        .to_str()
        .context("Invalid pipe name (not valid UTF-8)")?;

    info!(
        pipe = %pipe_name,
        "SSH agent listening on Windows named pipe"
    );

    // Use ssh-agent-lib's NamedPipeListener and listen() free function.
    // We pass an explicit Agent factory; caller identity is not available
    // on Windows in this iteration.
    let listener = ssh_agent_lib::agent::NamedPipeListener::bind(pipe_name)
        .with_context(|| format!("Failed to bind named pipe: {}", pipe_name))?;

    ssh_agent_lib::agent::listen(listener, agent)
        .await
        .map_err(|e| anyhow::anyhow!("SSH agent server error: {}", e))?;

    Ok(())
}
