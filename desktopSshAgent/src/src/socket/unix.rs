//! Unix domain socket SSH agent server for macOS and Linux.

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use anyhow::{Context, Result};
use std::fs;
use std::path::{Path, PathBuf};
use tokio::net::UnixListener;
use tracing::{info, warn};

/// Serves the SSH agent protocol over a Unix domain socket.
///
/// The socket file is created with restrictive permissions (0600) to prevent
/// other users from connecting. If a stale socket file exists, it is removed.
pub async fn serve<K: KeyProvider>(agent: KeyguardAgentFactory<K>, socket_path: &Path) -> Result<()> {
    ensure_socket_parent_dir(socket_path)?;

    // Remove stale socket file if it exists.
    if socket_path.exists() {
        warn!(
            path = %socket_path.display(),
            "Removing stale SSH agent socket"
        );
        fs::remove_file(socket_path).with_context(|| {
            format!(
                "Failed to remove stale SSH agent socket: {}",
                socket_path.display()
            )
        })?;
    }

    // Bind the Unix socket.
    let listener = UnixListener::bind(socket_path).with_context(|| {
        format!(
            "Failed to bind SSH agent socket at {}",
            socket_path.display()
        )
    })?;

    // Set restrictive permissions (0600) on the socket file.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600)).with_context(|| {
            format!(
                "Failed to set permissions on SSH agent socket: {}",
                socket_path.display()
            )
        })?;
    }

    info!(
        path = %socket_path.display(),
        "SSH agent listening on Unix socket"
    );

    // Use ssh-agent-lib's listen() free function.
    // We provide a custom Agent factory so we can capture per-connection
    // caller identity (peer credentials) from the accepted Unix socket.
    tokio::select! {
        result = ssh_agent_lib::agent::listen(listener, agent) => {
            result.map_err(|e| anyhow::anyhow!("SSH agent server error: {}", e))?;
        }
        _ = wait_for_shutdown_signal() => {
            info!("Received shutdown signal, stopping SSH agent listener");
        }
    }

    cleanup_socket_file(socket_path.to_path_buf());

    Ok(())
}

fn ensure_socket_parent_dir(socket_path: &Path) -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        let uid = unsafe { libc::getuid() };
        return ensure_socket_parent_dir_for_uid(socket_path, uid);
    }

    #[cfg(not(target_os = "linux"))]
    {
        if let Some(parent) = socket_path.parent() {
            fs::create_dir_all(parent).with_context(|| {
                format!(
                    "Failed to create parent directory for SSH agent socket: {}",
                    parent.display()
                )
            })?;
        }
        Ok(())
    }
}

#[cfg(target_os = "linux")]
fn ensure_socket_parent_dir_for_uid(socket_path: &Path, uid: libc::uid_t) -> Result<()> {
    if is_linux_fallback_socket_path_for_uid(socket_path, uid) {
        let fallback_socket_path = crate::config::linux_fallback_ssh_agent_socket_path(uid);
        let fallback_parent = fallback_socket_path.parent().context(
            "Linux fallback SSH agent socket path does not have a parent directory",
        )?;
        ensure_safe_linux_fallback_parent_dir(fallback_parent, uid)?;
        return Ok(());
    }

    if let Some(parent) = socket_path.parent() {
        fs::create_dir_all(parent).with_context(|| {
            format!(
                "Failed to create parent directory for SSH agent socket: {}",
                parent.display()
            )
        })?;
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn is_linux_fallback_socket_path_for_uid(socket_path: &Path, uid: libc::uid_t) -> bool {
    socket_path == crate::config::linux_fallback_ssh_agent_socket_path(uid)
}

#[cfg(target_os = "linux")]
fn ensure_safe_linux_fallback_parent_dir(parent: &Path, uid: libc::uid_t) -> Result<()> {
    use std::io::ErrorKind;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt, PermissionsExt};

    // Best-effort creation with restrictive permissions if absent.
    let mut builder = fs::DirBuilder::new();
    builder.mode(0o700);
    match builder.create(parent) {
        Ok(()) => {}
        Err(e) if e.kind() == ErrorKind::AlreadyExists => {}
        Err(e) => {
            return Err(e).with_context(|| {
                format!(
                    "Failed to create Linux fallback SSH agent directory {}",
                    parent.display()
                )
            });
        }
    }

    let metadata = fs::symlink_metadata(parent).with_context(|| {
        format!(
            "Failed to inspect Linux fallback SSH agent directory {}",
            parent.display()
        )
    })?;

    if metadata.file_type().is_symlink() {
        anyhow::bail!(
            "Unsafe Linux fallback SSH agent directory {}: parent is a symlink",
            parent.display()
        );
    }
    if !metadata.file_type().is_dir() {
        anyhow::bail!(
            "Unsafe Linux fallback SSH agent directory {}: parent is not a directory",
            parent.display()
        );
    }
    if metadata.uid() != uid {
        anyhow::bail!(
            "Unsafe Linux fallback SSH agent directory {}: owned by uid {}, expected {}",
            parent.display(),
            metadata.uid(),
            uid
        );
    }

    if (metadata.mode() & 0o777) != 0o700 {
        fs::set_permissions(parent, fs::Permissions::from_mode(0o700)).with_context(|| {
            format!(
                "Failed to set Linux fallback SSH agent directory permissions to 0700: {}",
                parent.display()
            )
        })?;
    }

    let final_mode = fs::symlink_metadata(parent)
        .with_context(|| {
            format!(
                "Failed to re-check Linux fallback SSH agent directory {}",
                parent.display()
            )
        })?
        .mode()
        & 0o777;
    if final_mode != 0o700 {
        anyhow::bail!(
            "Unsafe Linux fallback SSH agent directory {}: expected mode 0700, got {:03o}",
            parent.display(),
            final_mode
        );
    }

    Ok(())
}

async fn wait_for_shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};

        let mut terminate =
            signal(SignalKind::terminate()).expect("Failed to install SIGTERM handler");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }
}

fn cleanup_socket_file(socket_path: PathBuf) {
    match fs::remove_file(&socket_path) {
        Ok(()) => {
            info!(
                path = %socket_path.display(),
                "Removed SSH agent socket file"
            );
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => {
            warn!(
                path = %socket_path.display(),
                error = %e,
                "Failed to remove SSH agent socket file"
            );
        }
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::{symlink, MetadataExt, PermissionsExt};
    use tempfile::tempdir;

    fn current_uid() -> libc::uid_t {
        unsafe { libc::getuid() }
    }

    #[test]
    fn safe_fallback_parent_is_created_with_0700() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("keyguard-parent");

        ensure_safe_linux_fallback_parent_dir(&parent, current_uid()).expect("prepare parent");

        let metadata = fs::symlink_metadata(&parent).expect("metadata");
        assert!(metadata.file_type().is_dir());
        assert_eq!(metadata.mode() & 0o777, 0o700);
    }

    #[test]
    fn safe_fallback_parent_permissions_are_tightened_to_0700() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("keyguard-parent");

        fs::create_dir(&parent).expect("create");
        fs::set_permissions(&parent, fs::Permissions::from_mode(0o755)).expect("chmod 755");

        ensure_safe_linux_fallback_parent_dir(&parent, current_uid()).expect("prepare parent");

        let metadata = fs::symlink_metadata(&parent).expect("metadata");
        assert_eq!(metadata.mode() & 0o777, 0o700);
    }

    #[test]
    fn safe_fallback_parent_rejects_symlink() {
        let tmp = tempdir().expect("tempdir");
        let target = tmp.path().join("real-parent");
        let link = tmp.path().join("link-parent");

        fs::create_dir(&target).expect("create target");
        symlink(&target, &link).expect("create symlink");

        let err =
            ensure_safe_linux_fallback_parent_dir(&link, current_uid()).expect_err("must fail");
        assert!(err.to_string().contains("symlink"));
    }

    #[test]
    fn safe_fallback_parent_rejects_wrong_owner_uid() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("keyguard-parent");
        let uid = current_uid();
        let wrong_uid = uid.saturating_add(1);
        assert_ne!(uid, wrong_uid, "wrong uid must differ in test");

        fs::create_dir(&parent).expect("create parent");
        fs::set_permissions(&parent, fs::Permissions::from_mode(0o700)).expect("chmod 700");

        let err = ensure_safe_linux_fallback_parent_dir(&parent, wrong_uid).expect_err("must fail");
        assert!(err.to_string().contains("owned by uid"));
    }

    #[test]
    fn non_fallback_path_preserves_existing_parent_handling() {
        let tmp = tempdir().expect("tempdir");
        let real_parent = tmp.path().join("real-parent");
        let link_parent = tmp.path().join("link-parent");

        fs::create_dir(&real_parent).expect("create real parent");
        symlink(&real_parent, &link_parent).expect("create symlink parent");

        let socket_path = link_parent.join("ssh-agent.sock");
        ensure_socket_parent_dir_for_uid(&socket_path, current_uid()).expect("should allow");
    }

    #[test]
    fn fallback_path_detection_is_exact() {
        let uid = current_uid();
        let fallback_socket_path = crate::config::linux_fallback_ssh_agent_socket_path(uid);
        let non_fallback_socket_path = PathBuf::from(format!("/tmp/keyguard-{uid}/other.sock"));

        assert!(is_linux_fallback_socket_path_for_uid(&fallback_socket_path, uid));
        assert!(!is_linux_fallback_socket_path_for_uid(
            &non_fallback_socket_path,
            uid
        ));
    }
}
