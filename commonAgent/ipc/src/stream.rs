//! The client side of the owner-only local transport. An agent connects to
//! the app through it, and the `keyguard` CLI to its agent.
use anyhow::{Context, Result};
use std::path::Path;
use tokio::io::{AsyncRead, AsyncWrite};

#[cfg(all(test, windows))]
mod windows_tests;

/// A connected stream of the transport.
pub trait Stream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Stream for T {}

/// A boxed [`Stream`] of either platform transport.
pub type BoxStream = Box<dyn Stream>;

/// Connects to the server at `path`, a Unix socket or a named pipe, and
/// verifies that it runs as this user and, if given, as `expected_pid`.
///
/// On Windows it waits while every instance of the pipe is busy; callers
/// bound the wait.
///
/// # Errors
///
/// Fails when the server is unreachable or its identity does not match.
pub async fn connect(path: &Path, expected_pid: Option<u32>) -> Result<BoxStream> {
    #[cfg(unix)]
    {
        let stream = tokio::net::UnixStream::connect(path)
            .await
            .with_context(|| format!("failed to connect to {}", path.display()))?;
        keyguard_agent_identity::ipc_server::verify_unix_ipc_server(&stream, expected_pid)?;
        Ok(Box::new(stream))
    }
    #[cfg(windows)]
    {
        use tokio::net::windows::named_pipe::ClientOptions;
        use windows_sys::Win32::Foundation::ERROR_PIPE_BUSY;
        // Every instance is busy until the server creates its next one.
        let stream = loop {
            match ClientOptions::new().open(path) {
                Err(error) if error.raw_os_error() == Some(ERROR_PIPE_BUSY as i32) => {
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await
                }
                result => {
                    break result
                        .with_context(|| format!("failed to connect to {}", path.display()))?;
                }
            }
        };
        keyguard_agent_identity::ipc_server::verify_named_pipe_ipc_server(&stream, expected_pid)?;
        Ok(Box::new(stream))
    }
}
