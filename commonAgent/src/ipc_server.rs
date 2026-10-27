//! Verification of a private IPC server before a helper sends it a token.
//!
//! A predictable socket or pipe name does not prove who serves it. Helpers
//! check the server's kernel-reported identity right after connecting.

use anyhow::{bail, Context, Result};

/// Verifies that the Unix socket server runs as the current effective user
/// and, when `expected_pid` is given, is that process.
///
/// # Errors
///
/// Fails when peer credentials are unavailable or do not match.
#[cfg(unix)]
pub fn verify_unix_ipc_server(
    stream: &tokio::net::UnixStream,
    expected_pid: Option<u32>,
) -> Result<()> {
    let credentials = stream
        .peer_cred()
        .context("failed to read IPC server peer credentials")?;
    if let Some(expected_pid) = expected_pid {
        let peer_pid = credentials
            .pid()
            .context("IPC server peer credentials did not include a PID")?;
        let peer_pid =
            u32::try_from(peer_pid).context("IPC server reported an invalid peer PID")?;
        if peer_pid != expected_pid {
            bail!("IPC server PID mismatch: expected parent {expected_pid}, got {peer_pid}");
        }
    }

    // SAFETY: geteuid has no preconditions and does not dereference pointers.
    let expected_uid = unsafe { libc::geteuid() };
    if credentials.uid() != expected_uid {
        bail!(
            "IPC server UID mismatch: expected {expected_uid}, got {}",
            credentials.uid()
        );
    }
    Ok(())
}

/// Verifies that the named-pipe server runs as the current user and, when
/// `expected_pid` is given, is that process. A predictable pipe name alone
/// does not prove who serves it: another login must not reserve it.
///
/// # Errors
///
/// Fails when the server PID is unavailable or does not match, or when the
/// server process cannot be opened or belongs to another user.
#[cfg(windows)]
pub fn verify_named_pipe_ipc_server(
    pipe: &impl std::os::windows::io::AsRawHandle,
    expected_pid: Option<u32>,
) -> Result<()> {
    use windows_sys::Win32::System::Threading::PROCESS_QUERY_LIMITED_INFORMATION;

    let mut server_pid = 0u32;
    // SAFETY: the caller's pipe handle stays valid for the duration of this
    // call, and server_pid points to writable u32 storage.
    let succeeded = unsafe {
        windows_sys::Win32::System::Pipes::GetNamedPipeServerProcessId(
            pipe.as_raw_handle().cast(),
            &mut server_pid,
        )
    };
    if succeeded == 0 {
        return Err(std::io::Error::last_os_error())
            .context("failed to read named-pipe server PID");
    }
    if let Some(expected_pid) = expected_pid {
        if server_pid != expected_pid {
            bail!(
                "IPC named-pipe server PID mismatch: expected parent {expected_pid}, got {server_pid}"
            );
        }
    }
    crate::windows::open_current_user_process(server_pid, PROCESS_QUERY_LIMITED_INFORMATION)?;
    Ok(())
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    fn connected_pair() -> (tokio::net::UnixStream, std::os::unix::net::UnixStream) {
        let (stream, peer) = std::os::unix::net::UnixStream::pair().unwrap();
        stream.set_nonblocking(true).unwrap();
        (tokio::net::UnixStream::from_std(stream).unwrap(), peer)
    }

    #[tokio::test]
    async fn unix_ipc_server_verification_accepts_current_process() {
        let (stream, _peer) = connected_pair();

        verify_unix_ipc_server(&stream, Some(std::process::id())).unwrap();
        verify_unix_ipc_server(&stream, None).unwrap();
    }

    #[tokio::test]
    async fn unix_ipc_server_verification_rejects_wrong_parent_pid() {
        let (stream, _peer) = connected_pair();
        let wrong_pid = std::process::id().wrapping_add(1).max(1);

        let error = verify_unix_ipc_server(&stream, Some(wrong_pid))
            .unwrap_err()
            .to_string();
        assert!(error.contains("PID mismatch"), "{error}");
    }
}
