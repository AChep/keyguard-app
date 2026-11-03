//! Windows named pipe SSH agent server.

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use anyhow::{Context, Result};
use async_trait::async_trait;
use keyguard_agent_identity::windows::create_owner_only_pipe;
use ssh_agent_lib::agent::ListeningSocket;
use std::ffi::OsString;
use std::io;
use std::path::Path;
use tokio::net::windows::named_pipe::NamedPipeServer;
use tokio::sync::oneshot;
use tracing::info;

/// Named-pipe listener that applies an owner-only DACL to every pipe instance.
///
/// `ssh-agent-lib` otherwise relies on the process's default named-pipe DACL,
/// which can grant access to principals other than the current desktop user.
#[derive(Debug)]
struct OwnerOnlyNamedPipeListener {
    server: NamedPipeServer,
    pipe_name: OsString,
}

impl OwnerOnlyNamedPipeListener {
    fn bind(pipe_name: impl Into<OsString>) -> io::Result<Self> {
        let pipe_name = pipe_name.into();
        let server = create_owner_only_pipe(&pipe_name, true)?;
        Ok(Self { server, pipe_name })
    }
}

#[async_trait]
impl ListeningSocket for OwnerOnlyNamedPipeListener {
    type Stream = NamedPipeServer;

    async fn accept(&mut self) -> io::Result<Self::Stream> {
        self.server.connect().await?;
        let next = create_owner_only_pipe(&self.pipe_name, false)?;
        Ok(std::mem::replace(&mut self.server, next))
    }
}

/// Serves the SSH agent protocol over a Windows named pipe.
///
/// The pipe name is expected to be in the format `\\.\pipe\keyguard-ssh-agent`.
pub async fn serve<K, F>(
    agent: KeyguardAgentFactory<K>,
    pipe_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
    on_ready: F,
) -> Result<()>
where
    K: KeyProvider,
    F: FnOnce() -> Result<()>,
{
    let pipe_name = pipe_path
        .to_str()
        .context("Invalid pipe name (not valid UTF-8)")?;

    info!(
        pipe = %pipe_name,
        "SSH agent listening on Windows named pipe"
    );

    // Keep the public pipe name compatible, while failing startup if another
    // pipe instance already owns it and restricting all instances to the
    // current owner. The local server retains bounded framing and deadlines.
    let listener = OwnerOnlyNamedPipeListener::bind(pipe_name)
        .with_context(|| format!("Failed to bind named pipe: {}", pipe_name))?;

    // The first named-pipe instance now exists with the owner-only DACL.
    on_ready().context("Failed to report SSH agent named-pipe readiness")?;

    super::server::listen_until(listener, agent, async move {
        let _ = parent_stdin_closed.await;
    })
    .await
    .map_err(|e| anyhow::anyhow!("SSH agent server error: {}", e))?;
    info!("Parent stdin closed, stopping SSH agent listener");

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::Duration;
    use tokio::net::windows::named_pipe::ClientOptions;
    use tokio::time::timeout;

    static NEXT_PIPE_ID: AtomicU64 = AtomicU64::new(0);

    fn unique_pipe_name() -> OsString {
        format!(
            r"\\.\pipe\keyguard-ssh-agent-test-{}-{}",
            std::process::id(),
            NEXT_PIPE_ID.fetch_add(1, Ordering::Relaxed)
        )
        .into()
    }

    #[tokio::test]
    async fn listener_rejects_a_second_first_instance_and_accepts_local_client() -> io::Result<()> {
        let pipe_name = unique_pipe_name();
        let mut listener = OwnerOnlyNamedPipeListener::bind(pipe_name.clone())?;

        assert!(create_owner_only_pipe(&pipe_name, true).is_err());

        let client = ClientOptions::new().open(&pipe_name)?;
        let accepted = timeout(Duration::from_secs(2), listener.accept())
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "pipe accept timed out"))??;

        drop(accepted);
        drop(client);
        Ok(())
    }
}
