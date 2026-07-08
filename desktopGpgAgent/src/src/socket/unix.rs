//! Unix domain socket serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use tokio::net::UnixListener;
use tokio::sync::oneshot;
use tracing::{info, warn};

pub async fn serve(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    ensure_socket_parent_dir(socket_path)?;
    if socket_path.exists() {
        warn!(path = %socket_path.display(), "removing stale GPG agent socket");
        fs::remove_file(socket_path).with_context(|| {
            format!("failed to remove stale GPG agent socket: {}", socket_path.display())
        })?;
    }

    let listener = UnixListener::bind(socket_path).with_context(|| {
        format!("failed to bind GPG agent socket at {}", socket_path.display())
    })?;
    fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600)).with_context(|| {
        format!("failed to set permissions on {}", socket_path.display())
    })?;

    info!(path = %socket_path.display(), "GPG agent listening on Unix socket");

    tokio::select! {
        result = accept_loop(listener, ipc_client, socket_path.to_string_lossy().to_string()) => {
            result?;
        }
        reason = wait_for_shutdown_request(parent_stdin_closed) => {
            info!(reason, "stopping GPG agent listener");
        }
    }

    cleanup_socket_file(socket_path.to_path_buf());
    Ok(())
}

async fn accept_loop(
    listener: UnixListener,
    ipc_client: IpcClient,
    socket_name: String,
) -> Result<()> {
    loop {
        let (stream, _) = listener.accept().await?;
        let caller = crate::caller_identity::caller_from_unix_stream(&stream);
        let ipc_client = ipc_client.clone();
        let socket_name = socket_name.clone();
        tokio::spawn(async move {
            if let Err(e) = assuan::serve_connection(stream, ipc_client, caller, socket_name).await {
                warn!("GPG Assuan connection failed: {e}");
            }
        });
    }
}

fn ensure_socket_parent_dir(socket_path: &Path) -> Result<()> {
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    {
        let uid = unsafe { libc::getuid() };
        return ensure_socket_parent_dir_for_uid(socket_path, uid);
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
            return Err(e)
                .with_context(|| format!("failed to create {}", parent.display()));
        }
    }

    let metadata = fs::symlink_metadata(parent)
        .with_context(|| format!("failed to inspect {}", parent.display()))?;
    if metadata.file_type().is_symlink() {
        anyhow::bail!("unsafe GPG fallback directory {}: symlink", parent.display());
    }
    if !metadata.file_type().is_dir() {
        anyhow::bail!("unsafe GPG fallback directory {}: not a directory", parent.display());
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
        fs::set_permissions(parent, fs::Permissions::from_mode(0o700)).with_context(|| {
            format!("failed to set permissions on {}", parent.display())
        })?;
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
