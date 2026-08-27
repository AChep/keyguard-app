//! Unix domain socket SSH agent server for macOS and Linux.

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use anyhow::{Context, Result};
use keyguard_agent_identity::socket_lifecycle::{
    current_uid, wait_for_shutdown_request, SocketLifecycle,
};
use std::path::Path;
use tokio::net::UnixListener;
use tokio::sync::oneshot;
use tracing::info;

const LIFECYCLE: SocketLifecycle = SocketLifecycle::new("SSH agent");

/// Serves the SSH agent protocol over a Unix domain socket.
///
/// The socket file is created with restrictive permissions (0600) to prevent
/// other users from connecting. An owned, stale socket is removed before bind.
pub async fn serve<K, F>(
    agent: KeyguardAgentFactory<K>,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
    on_ready: F,
) -> Result<()>
where
    K: KeyProvider,
    F: FnOnce() -> Result<()>,
{
    LIFECYCLE.validate_socket_path(socket_path)?;
    ensure_socket_parent_dir(socket_path)?;

    let uid = current_uid();
    let lifecycle_lock = LIFECYCLE.acquire_lifecycle_lock(socket_path, uid)?;
    LIFECYCLE
        .prepare_socket_path_for_bind(socket_path, uid)
        .await?;

    // Bind the Unix socket.
    let listener = UnixListener::bind(socket_path).with_context(|| {
        format!(
            "failed to bind SSH agent socket at {}",
            socket_path.display()
        )
    })?;
    let socket_identity = LIFECYCLE.owned_socket_identity(socket_path, uid)?;

    // Set restrictive permissions (0600) on the socket file.
    LIFECYCLE.secure_bound_socket(socket_path, uid, socket_identity)?;
    // A filesystem identity alone cannot prove that the pathname still leads
    // to `listener`: another process with the same UID can replace the entry
    // between bind and the first stat. Kernel peer credentials on a fresh
    // connection provide that missing process-level proof.
    LIFECYCLE
        .attest_bound_socket_path(socket_path, uid, socket_identity)
        .await?;
    // Do not arm cleanup until endpoint ownership has been proven. On an
    // attestation failure the visible pathname may belong to another process.
    let socket_guard = LIFECYCLE.guard(socket_path, socket_identity, uid);

    // Report readiness only after the public endpoint is bound, owned by this
    // process, and confirmed to have owner-only permissions.
    on_ready().context("failed to report SSH agent socket readiness")?;

    info!(
        path = %socket_path.display(),
        "SSH agent listening on Unix socket"
    );

    // Use the local bounded server so untrusted protocol frames and client
    // connections cannot grow process resources without limit. The custom
    // Agent factory still captures per-connection peer credentials.
    let result = super::server::listen_until(
        listener,
        agent,
        wait_for_shutdown_request(parent_stdin_closed),
    )
    .await
    .map_err(|e| anyhow::anyhow!("SSH agent server error: {}", e));
    let result = match result {
        Ok(reason) => {
            info!(reason, "Stopping SSH agent listener");
            Ok(())
        }
        Err(error) => Err(error),
    };

    // The guard removes only the socket created by this process, including
    // when the accept loop or a post-bind setup step fails.
    drop(socket_guard);
    // Keep the lifecycle lock through identity-checked socket cleanup. Closing
    // the file releases the lock; its persistent inode is deliberately kept in
    // Keyguard's owner-only lock directory, separate from the SSH socket.
    drop(lifecycle_lock);

    result
}

fn ensure_socket_parent_dir(socket_path: &Path) -> Result<()> {
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    {
        ensure_socket_parent_dir_for_uid(socket_path, current_uid())
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        if let Some(parent) = socket_path.parent() {
            std::fs::create_dir_all(parent).with_context(|| {
                format!(
                    "failed to create parent directory for SSH agent socket: {}",
                    parent.display()
                )
            })?;
        }
        Ok(())
    }
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_socket_parent_dir_for_uid(socket_path: &Path, uid: libc::uid_t) -> Result<()> {
    if let Some(managed_parent) = managed_socket_parent_for_uid(socket_path, uid) {
        return ensure_managed_socket_parent_dir(&managed_parent, uid);
    }

    if let Some(parent) = socket_path.parent() {
        std::fs::create_dir_all(parent).with_context(|| {
            format!(
                "failed to create parent directory for SSH agent socket: {}",
                parent.display()
            )
        })?;
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_managed_socket_parent_dir(parent: &Path, uid: libc::uid_t) -> Result<()> {
    if let Some(shared_parent) = parent.parent() {
        // Library/Group Containers may not exist on a fresh macOS home.
        // Its existing permissions must remain unchanged.
        std::fs::create_dir_all(shared_parent).with_context(|| {
            format!(
                "failed to create SSH agent directory ancestors: {}",
                shared_parent.display()
            )
        })?;
    }
    LIFECYCLE.ensure_safe_managed_parent_dir(parent, uid)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn managed_socket_parent_for_uid(
    socket_path: &Path,
    uid: libc::uid_t,
) -> Option<std::path::PathBuf> {
    let linux_fallback_socket_path = crate::config::linux_fallback_ssh_agent_socket_path(uid);
    if socket_path == linux_fallback_socket_path {
        return linux_fallback_socket_path.parent().map(Path::to_path_buf);
    }

    #[cfg(target_os = "macos")]
    {
        let macos_socket_path = crate::config::default_ssh_agent_socket_path();
        if socket_path == macos_socket_path {
            return macos_socket_path.parent().map(Path::to_path_buf);
        }
    }

    None
}

#[cfg(all(test, any(target_os = "linux", target_os = "macos")))]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::{symlink, MetadataExt, PermissionsExt};
    use std::path::PathBuf;
    use tempfile::tempdir;

    #[test]
    fn managed_parent_preparation_creates_and_preserves_shared_ancestors() {
        let tmp = tempdir().expect("tempdir");
        let shared_parent = tmp.path().join("Library/Group Containers");
        let parent = shared_parent.join("com.artemchep.keyguard");
        ensure_managed_socket_parent_dir(&parent, current_uid()).expect("prepare new container");
        fs::set_permissions(&shared_parent, fs::Permissions::from_mode(0o755))
            .expect("chmod shared parent");
        fs::set_permissions(&parent, fs::Permissions::from_mode(0o755)).expect("chmod container");

        ensure_managed_socket_parent_dir(&parent, current_uid())
            .expect("secure existing container");

        assert_eq!(
            fs::metadata(shared_parent)
                .expect("shared parent metadata")
                .mode()
                & 0o777,
            0o755
        );
        assert_eq!(
            fs::metadata(parent).expect("container metadata").mode() & 0o777,
            0o700
        );
    }

    #[test]
    fn non_managed_path_preserves_existing_parent_handling() {
        let tmp = tempdir().expect("tempdir");
        let real_parent = tmp.path().join("real-parent");
        let link_parent = tmp.path().join("link-parent");

        fs::create_dir(&real_parent).expect("create real parent");
        fs::set_permissions(&real_parent, fs::Permissions::from_mode(0o755)).expect("chmod 755");
        symlink(&real_parent, &link_parent).expect("create symlink parent");

        let socket_path = link_parent.join("ssh-agent.sock");
        ensure_socket_parent_dir_for_uid(&socket_path, current_uid()).expect("should allow");
        assert_eq!(
            fs::metadata(&real_parent).expect("metadata").mode() & 0o777,
            0o755
        );
    }

    #[test]
    fn managed_path_detection_is_exact() {
        let uid = current_uid();
        let fallback_socket_path = crate::config::linux_fallback_ssh_agent_socket_path(uid);
        let non_fallback_socket_path = PathBuf::from(format!("/tmp/keyguard-{uid}/other.sock"));

        assert_eq!(
            managed_socket_parent_for_uid(&fallback_socket_path, uid),
            fallback_socket_path.parent().map(Path::to_path_buf)
        );
        assert_eq!(
            managed_socket_parent_for_uid(&non_fallback_socket_path, uid),
            None
        );

        #[cfg(target_os = "macos")]
        {
            let macos_socket_path = crate::config::default_ssh_agent_socket_path();
            assert_eq!(
                managed_socket_parent_for_uid(&macos_socket_path, uid),
                macos_socket_path.parent().map(Path::to_path_buf)
            );
        }
    }
}
