//! Socket module: platform-specific GPG agent socket serving.

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

use crate::ipc::client::IpcClient;
use anyhow::Result;
use std::path::Path;
use tokio::sync::oneshot;

/// Start serving the GPG agent (Assuan) protocol on a platform-appropriate
/// socket.
pub async fn serve(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    #[cfg(unix)]
    {
        unix::serve(ipc_client, socket_path, parent_stdin_closed).await
    }

    #[cfg(windows)]
    {
        windows::serve(ipc_client, socket_path, parent_stdin_closed).await
    }
}
