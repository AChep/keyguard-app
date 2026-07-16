//! Unix domain socket serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use std::fs;
use std::future::Future;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::net::UnixListener;
use tokio::sync::{oneshot, Semaphore};
use tokio::task::{JoinError, JoinSet};
use tracing::{info, warn};

// Bound task, socket, and per-session buffer use under local connection floods.
const MAX_CONCURRENT_CONNECTIONS: usize = 32;
// Leave room for the listener, IPC, logging, runtime, and transient files.
const NON_AGENT_FD_RESERVE: usize = 16;
#[cfg(target_os = "macos")]
// Include the public socket and peak retained/transient identity descriptors.
const AGENT_FDS_PER_CONNECTION: usize =
    1 + keyguard_agent_identity::macos::MacosPeerIdentity::MAX_ADDITIONAL_FD_COUNT;
#[cfg(target_os = "linux")]
// Linux sessions retain both a pidfd and an O_PATH executable handle.
const AGENT_FDS_PER_CONNECTION: usize =
    1 + keyguard_agent_identity::linux_identity::LinuxProcessIdentity::RETAINED_FD_COUNT;
#[cfg(not(any(target_os = "linux", target_os = "macos")))]
const AGENT_FDS_PER_CONNECTION: usize = 1;

pub async fn serve(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    ensure_socket_parent_dir(socket_path)?;
    if socket_path.exists() {
        warn!(path = %socket_path.display(), "removing stale GPG agent socket");
        fs::remove_file(socket_path).with_context(|| {
            format!(
                "failed to remove stale GPG agent socket: {}",
                socket_path.display()
            )
        })?;
    }

    let listener = UnixListener::bind(socket_path).with_context(|| {
        format!(
            "failed to bind GPG agent socket at {}",
            socket_path.display()
        )
    })?;
    fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600))
        .with_context(|| format!("failed to set permissions on {}", socket_path.display()))?;

    info!(path = %socket_path.display(), "GPG agent listening on Unix socket");

    let outcome = accept_until(
        listener,
        ipc_client,
        socket_path.to_string_lossy().into_owned(),
        wait_for_shutdown_request(parent_stdin_closed),
        default_max_connections(),
    )
    .await;
    if let Ok(reason) = &outcome {
        info!(reason, "stopping GPG agent listener");
    }

    // Always remove the socket, including when the accept loop fails.
    cleanup_socket_file(socket_path.to_path_buf());
    outcome.map(|_| ())
}

async fn accept_until<F, R>(
    listener: UnixListener,
    ipc_client: IpcClient,
    socket_name: String,
    shutdown: F,
    max_connections: usize,
) -> Result<R>
where
    F: Future<Output = R>,
{
    if max_connections == 0 {
        anyhow::bail!("maximum GPG agent connections must be greater than zero");
    }

    let connection_limit = Arc::new(Semaphore::new(max_connections));
    let mut tasks = JoinSet::new();
    tokio::pin!(shutdown);

    let outcome = loop {
        reap_completed(&mut tasks);

        // Acquire before accept so excess clients stay in the bounded OS
        // backlog instead of consuming application file descriptors and tasks.
        let permit = tokio::select! {
            reason = &mut shutdown => break Ok(reason),
            permit = Arc::clone(&connection_limit).acquire_owned() => {
                match permit {
                    Ok(permit) => permit,
                    Err(_) => {
                        break Err(anyhow::Error::new(io::Error::other(
                            "GPG agent connection limiter closed unexpectedly",
                        )));
                    }
                }
            }
        };

        let stream = tokio::select! {
            reason = &mut shutdown => {
                drop(permit);
                break Ok(reason);
            }
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, _)) => stream,
                    Err(error) => {
                        drop(permit);
                        break Err(error.into());
                    }
                }
            }
        };

        #[cfg(target_os = "macos")]
        let (caller, macos_guard) =
            match crate::caller_identity::caller_context_from_unix_stream(&stream) {
                Some(context) => (Some(context.caller), context.macos_guard),
                None => (None, None),
            };
        #[cfg(target_os = "linux")]
        let (caller, linux_guard) =
            match crate::caller_identity::caller_context_from_unix_stream(&stream) {
                Some(context) => (Some(context.caller), context.linux_guard),
                None => (None, None),
            };
        #[cfg(not(any(target_os = "linux", target_os = "macos")))]
        let caller = crate::caller_identity::caller_from_unix_stream(&stream);
        let ipc_client = ipc_client.clone();
        let socket_name = socket_name.clone();
        tasks.spawn(async move {
            // Keep the permit for the full Assuan session.
            let _permit = permit;
            #[cfg(target_os = "macos")]
            {
                assuan::serve_connection_with_macos_guard(
                    stream,
                    ipc_client,
                    caller,
                    macos_guard,
                    socket_name,
                )
                .await
            }
            #[cfg(target_os = "linux")]
            {
                assuan::serve_connection_with_linux_guard(
                    stream,
                    ipc_client,
                    caller,
                    linux_guard,
                    socket_name,
                )
                .await
            }
            #[cfg(not(any(target_os = "linux", target_os = "macos")))]
            {
                assuan::serve_connection(stream, ipc_client, caller, socket_name).await
            }
        });
    };

    abort_and_drain(&mut tasks).await;
    outcome
}

fn default_max_connections() -> usize {
    let mut file_limit = std::mem::MaybeUninit::<libc::rlimit>::uninit();
    // SAFETY: `getrlimit` initializes the supplied `rlimit` on success. The
    // pointer is valid for writes and checked before `assume_init`.
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, file_limit.as_mut_ptr()) } != 0 {
        return MAX_CONCURRENT_CONNECTIONS;
    }

    // SAFETY: the successful `getrlimit` call above initialized `file_limit`.
    let soft_limit = unsafe { file_limit.assume_init() }.rlim_cur;
    if soft_limit == libc::RLIM_INFINITY {
        return MAX_CONCURRENT_CONNECTIONS;
    }

    let soft_limit = usize::try_from(soft_limit).unwrap_or(usize::MAX);
    max_connections_for_soft_limit(soft_limit)
}

fn max_connections_for_soft_limit(soft_limit: usize) -> usize {
    MAX_CONCURRENT_CONNECTIONS
        .min(soft_limit.saturating_sub(NON_AGENT_FD_RESERVE) / AGENT_FDS_PER_CONNECTION)
        .max(1)
}

fn reap_completed(tasks: &mut JoinSet<Result<()>>) {
    while let Some(result) = tasks.try_join_next() {
        log_task_result(result);
    }
}

async fn abort_and_drain(tasks: &mut JoinSet<Result<()>>) {
    tasks.abort_all();
    while let Some(result) = tasks.join_next().await {
        log_task_result(result);
    }
}

fn log_task_result(result: std::result::Result<Result<()>, JoinError>) {
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => warn!(%error, "GPG Assuan connection failed"),
        Err(error) if error.is_cancelled() => {}
        Err(error) => warn!(%error, "GPG Assuan connection task failed"),
    }
}

fn ensure_socket_parent_dir(socket_path: &Path) -> Result<()> {
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    {
        let uid = unsafe { libc::getuid() };
        ensure_socket_parent_dir_for_uid(socket_path, uid)
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        if let Some(parent) = socket_path.parent() {
            fs::create_dir_all(parent).with_context(|| {
                format!("failed to create parent directory: {}", parent.display())
            })?;
        }
        Ok(())
    }
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_socket_parent_dir_for_uid(socket_path: &Path, uid: libc::uid_t) -> Result<()> {
    if socket_path == crate::config::linux_fallback_gpg_agent_socket_path(uid) {
        let parent = socket_path
            .parent()
            .context("Linux fallback GPG socket path does not have a parent directory")?;
        let base_parent = parent
            .parent()
            .context("Linux fallback GPG socket path does not have a base directory")?;
        ensure_safe_linux_fallback_parent_dir(base_parent, uid)?;
        ensure_safe_linux_fallback_parent_dir(parent, uid)?;
        return Ok(());
    }

    if let Some(parent) = socket_path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("failed to create parent directory: {}", parent.display()))?;
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_safe_linux_fallback_parent_dir(parent: &Path, uid: libc::uid_t) -> Result<()> {
    use std::io::ErrorKind;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt};

    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    builder.mode(0o700);
    match builder.create(parent) {
        Ok(()) => {}
        Err(e) if e.kind() == ErrorKind::AlreadyExists => {}
        Err(e) => {
            return Err(e).with_context(|| format!("failed to create {}", parent.display()));
        }
    }

    let metadata = fs::symlink_metadata(parent)
        .with_context(|| format!("failed to inspect {}", parent.display()))?;
    if metadata.file_type().is_symlink() {
        anyhow::bail!(
            "unsafe GPG fallback directory {}: symlink",
            parent.display()
        );
    }
    if !metadata.file_type().is_dir() {
        anyhow::bail!(
            "unsafe GPG fallback directory {}: not a directory",
            parent.display()
        );
    }
    if metadata.uid() != uid {
        anyhow::bail!(
            "unsafe GPG fallback directory {}: owned by uid {}, expected {}",
            parent.display(),
            metadata.uid(),
            uid
        );
    }
    if (metadata.mode() & 0o777) != 0o700 {
        fs::set_permissions(parent, fs::Permissions::from_mode(0o700))
            .with_context(|| format!("failed to set permissions on {}", parent.display()))?;
    }
    Ok(())
}

async fn wait_for_shutdown_signal() {
    use tokio::signal::unix::{signal, SignalKind};

    let mut terminate = signal(SignalKind::terminate()).expect("failed to install SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = terminate.recv() => {}
    }
}

async fn wait_for_shutdown_request(parent_stdin_closed: oneshot::Receiver<()>) -> &'static str {
    tokio::select! {
        _ = wait_for_shutdown_signal() => "signal",
        _ = parent_stdin_closed => "parent_stdin_closed",
    }
}

fn cleanup_socket_file(socket_path: PathBuf) {
    match fs::remove_file(&socket_path) {
        Ok(()) => info!(path = %socket_path.display(), "removed GPG agent socket file"),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => warn!(
            path = %socket_path.display(),
            error = %e,
            "failed to remove GPG agent socket file"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    #[test]
    fn connection_cap_preserves_fd_reserve_and_never_reaches_zero() {
        assert_eq!(max_connections_for_soft_limit(0), 1);
        assert_eq!(max_connections_for_soft_limit(NON_AGENT_FD_RESERVE), 1);
        assert_eq!(max_connections_for_soft_limit(NON_AGENT_FD_RESERVE + 2), 1);
        assert_eq!(max_connections_for_soft_limit(usize::MAX), 32);
        assert!((1..=MAX_CONCURRENT_CONNECTIONS).contains(&default_max_connections()));
        assert_eq!(
            max_connections_for_soft_limit(NON_AGENT_FD_RESERVE + AGENT_FDS_PER_CONNECTION * 2,),
            2,
        );
    }

    struct DropSignal(Arc<AtomicBool>);

    impl Drop for DropSignal {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn shutdown_aborts_and_drains_connection_tasks() {
        let dropped = Arc::new(AtomicBool::new(false));
        let task_dropped = Arc::clone(&dropped);
        let mut tasks = JoinSet::<Result<()>>::new();
        tasks.spawn(async move {
            let _drop_signal = DropSignal(task_dropped);
            std::future::pending::<()>().await;
            Ok(())
        });
        tokio::task::yield_now().await;

        abort_and_drain(&mut tasks).await;

        assert!(dropped.load(Ordering::SeqCst));
        assert!(tasks.is_empty());
    }
}
