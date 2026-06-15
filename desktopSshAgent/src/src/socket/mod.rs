//! Socket module: platform-specific SSH agent socket serving.

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use anyhow::Result;
use std::path::Path;
use tokio::sync::oneshot;

/// Start serving the SSH agent protocol on a platform-appropriate socket.
pub async fn serve<K: KeyProvider>(
    agent: KeyguardAgentFactory<K>,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    #[cfg(unix)]
    {
        unix::serve(agent, socket_path, parent_stdin_closed).await
    }

    #[cfg(windows)]
    {
        windows::serve(agent, socket_path, parent_stdin_closed).await
    }
}
