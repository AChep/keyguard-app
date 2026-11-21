//! The client side of the owner-only local transport. The CLI connects to
//! the agent, and the agent to the app, through it.
pub use keyguard_agent_ipc::stream::{BoxStream, CONNECT_TIMEOUT, Stream};

#[cfg(windows)]
pub mod windows;

/// Connects to the server at `path` as
/// [`keyguard_agent_ipc::stream::connect`] does. On Linux, a path too long
/// for a socket address is reached through its folder.
///
/// # Errors
///
/// Fails when the server is unreachable or its identity does not match.
pub async fn connect(
    path: &std::path::Path,
    expected_pid: Option<u32>,
) -> anyhow::Result<BoxStream> {
    #[cfg(target_os = "linux")]
    {
        let address = crate::socket::ShortPath::new(path)?;
        keyguard_agent_ipc::stream::connect(address.path(), expected_pid).await
    }
    #[cfg(not(target_os = "linux"))]
    keyguard_agent_ipc::stream::connect(path, expected_pid).await
}
