//! The client side of the owner-only local transport. The CLI connects to
//! the agent, and the agent to the app, through it.
use anyhow::Result;
use std::path::Path;
use tokio::io::{AsyncRead, AsyncWrite};

#[cfg(windows)]
pub mod windows;

#[cfg(all(test, windows))]
mod windows_tests;

pub trait Stream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Stream for T {}
pub type BoxStream = Box<dyn Stream>;

/// Connects to the server at `path`, and verifies that it runs as this
/// user and, if given, as the process `expected_pid`.
pub async fn connect(path: &Path, expected_pid: Option<u32>) -> Result<BoxStream> {
    #[cfg(unix)]
    {
        let stream = tokio::net::UnixStream::connect(path).await?;
        keyguard_agent_identity::ipc_server::verify_unix_ipc_server(&stream, expected_pid)?;
        Ok(Box::new(stream))
    }
    #[cfg(windows)]
    {
        use tokio::net::windows::named_pipe::ClientOptions;
        use windows_sys::Win32::Foundation::ERROR_PIPE_BUSY;
        // Every instance is busy until the server creates its next one.
        // Callers bound the wait.
        let stream = loop {
            match ClientOptions::new().open(path) {
                Err(error) if error.raw_os_error() == Some(ERROR_PIPE_BUSY as i32) => {
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await
                }
                result => break result?,
            }
        };
        let pid = keyguard_agent_identity::ipc_server::verify_named_pipe_ipc_server(
            &stream,
            expected_pid,
        )?;
        // A predictable pipe name alone does not prove the server belongs to this
        // user: another login must not reserve it and collect append arguments.
        windows::Process::open(pid)?;
        Ok(Box::new(stream))
    }
}
