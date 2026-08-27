//! Unix domain socket serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use keyguard_agent_identity::socket_lifecycle::{
    current_uid, entry_identity, wait_for_shutdown_request, SocketLifecycle,
};
use std::ffi::OsString;
use std::fs;
use std::future::Future;
use std::io::ErrorKind;
use std::io::{self, Read};
use std::os::unix::ffi::OsStringExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::net::UnixListener;
use tokio::sync::{oneshot, Semaphore};
use tokio::task::{JoinError, JoinSet};
use tracing::{info, warn};

const LIFECYCLE: SocketLifecycle = SocketLifecycle::new("GPG agent");

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

// Libassuan documents a 511-byte maximum for its two-line redirect file.
const ASSUAN_REDIRECTION_MAX_BYTES: usize = 511;
const ASSUAN_REDIRECTION_PREFIX: &[u8] = b"%Assuan%\nsocket=";

#[derive(Debug, Eq, PartialEq)]
struct ResolvedSocketPath {
    bind_path: PathBuf,
    redirected: bool,
}

pub async fn serve<F>(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
    on_ready: F,
) -> Result<()>
where
    F: FnOnce() -> Result<()>,
{
    let resolved_socket_path = resolve_assuan_socket_path(socket_path)?;
    let bind_path = &resolved_socket_path.bind_path;
    LIFECYCLE.validate_socket_path(bind_path)?;
    ensure_socket_parent_dir(bind_path)?;

    let uid = current_uid();
    let lifecycle_lock = LIFECYCLE.acquire_lifecycle_lock(bind_path, uid)?;
    LIFECYCLE
        .prepare_socket_path_for_bind(bind_path, uid)
        .await?;

    let listener = UnixListener::bind(bind_path)
        .with_context(|| format!("failed to bind GPG agent socket at {}", bind_path.display()))?;
    let socket_identity = LIFECYCLE.owned_socket_identity(bind_path, uid)?;
    LIFECYCLE.secure_bound_socket(bind_path, uid, socket_identity)?;
    require_public_socket_target(socket_path, bind_path)?;
    LIFECYCLE
        .attest_bound_socket_path(bind_path, uid, socket_identity)
        .await?;
    // Do not arm cleanup until both the public route and the bound listener
    // have been attested. Before that point the visible target may be owned by
    // another process.
    let socket_guard = LIFECYCLE.guard(bind_path, socket_identity, uid);

    // Report readiness only after the actual endpoint is bound, still owned
    // by this process, and confirmed to have owner-only permissions. A
    // Libassuan redirection file remains the public endpoint and is never
    // owned or cleaned up by this process.
    on_ready().context("failed to report GPG agent socket readiness")?;

    if resolved_socket_path.redirected {
        info!(
            path = %socket_path.display(),
            bind_path = %bind_path.display(),
            "GPG agent listening on redirected Unix socket"
        );
    } else {
        info!(path = %socket_path.display(), "GPG agent listening on Unix socket");
    }

    let outcome = accept_until(
        listener,
        ipc_client,
        socket_path.to_string_lossy().into_owned(),
        wait_for_shutdown_request(parent_stdin_closed),
        default_max_connections(),
    )
    .await;
    let outcome = match outcome {
        Ok(reason) => {
            info!(reason, "stopping GPG agent listener");
            Ok(())
        }
        Err(error) => Err(error),
    };

    // Remove only the endpoint created by this process, including when the
    // accept loop fails. A replacement at the same path is left untouched.
    drop(socket_guard);
    // Keep the lifecycle lock through identity-checked socket cleanup. Closing
    // the file releases the lock; its persistent inode is deliberately kept in
    // Keyguard's owner-only lock directory, outside GnuPG's socket directory.
    drop(lifecycle_lock);
    outcome
}

fn resolve_assuan_socket_path(socket_path: &Path) -> Result<ResolvedSocketPath> {
    resolve_assuan_socket_path_with(socket_path, || {
        std::env::current_dir()
            .context("failed to determine the working directory for a relative Assuan redirect")
    })
}

fn resolve_assuan_socket_path_with<D>(
    socket_path: &Path,
    current_dir: D,
) -> Result<ResolvedSocketPath>
where
    D: FnOnce() -> Result<PathBuf>,
{
    let Some(target) = read_assuan_socket_redirect(socket_path)? else {
        return Ok(ResolvedSocketPath {
            bind_path: socket_path.to_path_buf(),
            redirected: false,
        });
    };
    let bind_path = if target.is_absolute() {
        target
    } else {
        current_dir()?.join(target)
    };
    Ok(ResolvedSocketPath {
        bind_path,
        redirected: true,
    })
}

fn read_assuan_socket_redirect(socket_path: &Path) -> Result<Option<PathBuf>> {
    // Match Libassuan's stat-based type check: only an existing regular file
    // is interpreted as a redirect; missing paths and sockets are used as-is.
    let metadata = match fs::metadata(socket_path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => {
            return Ok(None);
        }
        Err(error) => {
            return Err(error).with_context(|| {
                format!(
                    "failed to inspect possible Assuan socket redirect: {}",
                    socket_path.display()
                )
            });
        }
    };
    if !metadata.is_file() {
        return Ok(None);
    }
    let original_identity = entry_identity(&metadata);

    let file = fs::File::open(socket_path).with_context(|| {
        format!(
            "failed to open Assuan socket redirect: {}",
            socket_path.display()
        )
    })?;
    let opened_metadata = file.metadata().with_context(|| {
        format!(
            "failed to inspect opened Assuan socket redirect: {}",
            socket_path.display()
        )
    })?;
    if !opened_metadata.is_file() || entry_identity(&opened_metadata) != original_identity {
        anyhow::bail!(
            "Assuan socket redirect changed while opening it: {}",
            socket_path.display()
        );
    }

    let mut contents = Vec::with_capacity(ASSUAN_REDIRECTION_MAX_BYTES + 1);
    file.take((ASSUAN_REDIRECTION_MAX_BYTES + 1) as u64)
        .read_to_end(&mut contents)
        .with_context(|| {
            format!(
                "failed to read Assuan socket redirect: {}",
                socket_path.display()
            )
        })?;
    if contents.len() > ASSUAN_REDIRECTION_MAX_BYTES {
        anyhow::bail!(
            "Assuan socket redirect is too large (maximum {} bytes): {}",
            ASSUAN_REDIRECTION_MAX_BYTES,
            socket_path.display()
        );
    }

    parse_assuan_redirection(&contents).map(Some).with_context(|| {
        format!(
            "malformed Assuan socket redirect at {}",
            socket_path.display()
        )
    })
}

fn parse_assuan_redirection(contents: &[u8]) -> Result<PathBuf> {
    let Some(target_with_newline) = contents.strip_prefix(ASSUAN_REDIRECTION_PREFIX) else {
        anyhow::bail!("missing %Assuan% socket redirect header");
    };
    let Some(target) = target_with_newline.strip_suffix(b"\n") else {
        anyhow::bail!("socket target must end with a single linefeed");
    };
    if target.is_empty() {
        anyhow::bail!("socket target is empty");
    }
    if target.contains(&b'\n') || target.contains(&b'\r') {
        anyhow::bail!("socket redirect must contain exactly two LF-terminated lines");
    }
    if target.iter().any(u8::is_ascii_whitespace) {
        anyhow::bail!("socket target contains whitespace");
    }
    if target.contains(&0) {
        anyhow::bail!("socket target contains a NUL byte");
    }
    if target.windows(2).any(|pair| pair == b"${") {
        anyhow::bail!(
            "socket target contains a ${{...}} environment reference, which is not supported; \
             use a literal absolute path"
        );
    }

    Ok(PathBuf::from(OsString::from_vec(target.to_vec())))
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

fn require_public_socket_target(
    public_socket_path: &Path,
    expected_bind_path: &Path,
) -> Result<()> {
    let fresh = resolve_assuan_socket_path(public_socket_path)?;
    if fresh.bind_path != expected_bind_path {
        anyhow::bail!(
            "GPG agent public socket route changed from {} to {}: {}",
            expected_bind_path.display(),
            fresh.bind_path.display(),
            public_socket_path.display()
        );
    }
    Ok(())
}

fn ensure_socket_parent_dir(socket_path: &Path) -> Result<()> {
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    {
        ensure_socket_parent_dir_for_uid(socket_path, current_uid())
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        let parent = socket_path.parent().with_context(|| {
            format!(
                "GPG agent socket path does not have a parent directory: {}",
                socket_path.display()
            )
        })?;
        validate_external_socket_parent(parent, current_uid())
    }
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_socket_parent_dir_for_uid(socket_path: &Path, uid: libc::uid_t) -> Result<()> {
    if let Some(managed_parents) = managed_socket_parent_chain_for_uid(socket_path, uid) {
        return ensure_managed_socket_parent_dirs(&managed_parents, uid);
    }

    let parent = socket_path.parent().with_context(|| {
        format!(
            "GPG agent socket path does not have a parent directory: {}",
            socket_path.display()
        )
    })?;
    validate_external_socket_parent(parent, uid)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn ensure_managed_socket_parent_dirs(parents: &[PathBuf], uid: libc::uid_t) -> Result<()> {
    if let Some(shared_parent) = parents.first().and_then(|parent| parent.parent()) {
        // Ancestors such as Library/Group Containers are shared. Create
        // missing ancestors without changing existing permissions.
        fs::create_dir_all(shared_parent).with_context(|| {
            format!(
                "failed to create GPG agent directory ancestors: {}",
                shared_parent.display()
            )
        })?;
    }
    for parent in parents {
        LIFECYCLE.ensure_safe_managed_parent_dir(parent, uid)?;
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn managed_socket_parent_chain_for_uid(
    socket_path: &Path,
    uid: libc::uid_t,
) -> Option<Vec<PathBuf>> {
    let fallback_socket_path = crate::config::linux_fallback_gpg_agent_socket_path(uid);
    if socket_path == fallback_socket_path {
        let home = fallback_socket_path.parent()?;
        let keyguard_root = home.parent()?;
        return Some(vec![keyguard_root.to_path_buf(), home.to_path_buf()]);
    }

    #[cfg(target_os = "macos")]
    {
        let home = dirs::home_dir()?;
        let gpg_home = crate::config::macos_managed_gpg_home_path(&home);
        if socket_path == crate::config::managed_gpg_agent_socket_path(&gpg_home) {
            let keyguard_root = gpg_home.parent()?;
            return Some(vec![keyguard_root.to_path_buf(), gpg_home]);
        }
    }

    #[cfg(target_os = "linux")]
    {
        let gpg_home = crate::config::linux_managed_gpg_home_path().ok()?;
        if socket_path == crate::config::managed_gpg_agent_socket_path(&gpg_home) {
            // Runtime and Flatpak data directories are shared roots. The
            // two-level /tmp fallback is handled above.
            return Some(vec![gpg_home]);
        }
    }

    None
}

fn validate_external_socket_parent(parent: &Path, uid: libc::uid_t) -> Result<()> {
    const DESCRIPTION: &str = "GPG agent socket parent directory";
    let metadata = fs::symlink_metadata(parent).with_context(|| {
        format!(
            "failed to inspect GPG agent socket parent directory {}",
            parent.display()
        )
    })?;
    let identity =
        LIFECYCLE.validate_owned_directory_metadata(parent, &metadata, uid, DESCRIPTION)?;
    let mode = metadata.mode() & 0o777;
    if mode != 0o700 {
        anyhow::bail!(
            "unsafe GPG agent socket parent directory {}: expected mode 0700, got {:03o}",
            parent.display(),
            mode
        );
    }

    let final_metadata = fs::symlink_metadata(parent).with_context(|| {
        format!(
            "failed to re-check GPG agent socket parent directory {}",
            parent.display()
        )
    })?;
    let final_identity =
        LIFECYCLE.validate_owned_directory_metadata(parent, &final_metadata, uid, DESCRIPTION)?;
    if final_identity != identity || (final_metadata.mode() & 0o777) != mode {
        anyhow::bail!(
            "GPG agent socket parent directory changed while validating it: {}",
            parent.display()
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_agent_identity::socket_lifecycle::lifecycle_lock_path;
    use std::fs;
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::{symlink, PermissionsExt};
    use std::os::unix::net::UnixListener as StdUnixListener;
    use std::sync::atomic::{AtomicBool, Ordering};
    use tempfile::tempdir;

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

    fn test_current_dir(directory: &Path) -> impl FnOnce() -> Result<PathBuf> {
        let directory = directory.to_path_buf();
        move || Ok(directory)
    }

    #[test]
    fn assuan_redirect_resolves_absolute_socket_and_preserves_public_file() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let bind_path = tmp.path().join("S.gpg-agent.real");
        let contents = assuan_redirect_contents(&bind_path);
        fs::write(&public_path, &contents).expect("write redirect");

        let resolved =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                .expect("resolve redirect");

        assert_eq!(resolved.bind_path, bind_path);
        assert!(resolved.redirected);
        assert_eq!(fs::read(&public_path).expect("read redirect"), contents);
    }

    #[test]
    fn assuan_redirect_uses_current_directory_for_relative_target() {
        let tmp = tempdir().expect("tempdir");
        let public_dir = tmp.path().join("public");
        fs::create_dir(&public_dir).expect("create public dir");
        let public_path = public_dir.join("S.gpg-agent");
        fs::write(&public_path, b"%Assuan%\nsocket=runtime/agent.sock\n").expect("write redirect");
        let current_dir = tmp.path().join("working-directory");

        let resolved =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(&current_dir))
                .expect("resolve redirect");

        assert_eq!(resolved.bind_path, current_dir.join("runtime/agent.sock"));
        assert_ne!(resolved.bind_path, public_dir.join("runtime/agent.sock"));
    }

    #[test]
    fn assuan_redirect_with_environment_reference_fails_closed() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let contents = b"%Assuan%\nsocket=${RUNTIME}/agent.sock\n";
        fs::write(&public_path, contents).expect("write redirect");

        let error = resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
            .expect_err("environment reference must fail");

        let diagnostic = format!("{error:#}");
        assert!(diagnostic.contains("malformed Assuan socket redirect"));
        assert!(diagnostic.contains("environment reference"));
        assert_eq!(fs::read(&public_path).expect("read redirect"), contents);
    }

    #[test]
    fn assuan_redirect_resolution_is_one_level_only() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let second_redirect = tmp.path().join("second-redirect");
        let final_socket = tmp.path().join("actual.sock");
        fs::write(&public_path, assuan_redirect_contents(&second_redirect))
            .expect("write first redirect");
        fs::write(&second_redirect, assuan_redirect_contents(&final_socket))
            .expect("write second redirect");

        let resolved =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                .expect("resolve first redirect");

        assert_eq!(resolved.bind_path, second_redirect);
        assert_ne!(resolved.bind_path, final_socket);
    }

    #[test]
    fn missing_path_and_existing_socket_are_not_treated_as_redirects() {
        let tmp = tempdir().expect("tempdir");
        let missing_path = tmp.path().join("missing.sock");
        let missing =
            resolve_assuan_socket_path_with(&missing_path, test_current_dir(tmp.path()))
                .expect("resolve missing socket");
        assert_eq!(missing.bind_path, missing_path);
        assert!(!missing.redirected);

        let socket_path = tmp.path().join("existing.sock");
        let _listener = StdUnixListener::bind(&socket_path).expect("bind existing socket");
        let socket =
            resolve_assuan_socket_path_with(&socket_path, test_current_dir(tmp.path()))
                .expect("resolve existing socket");
        assert_eq!(socket.bind_path, socket_path);
        assert!(!socket.redirected);
    }

    #[test]
    fn malformed_assuan_redirects_fail_closed_and_are_preserved() {
        let malformed_contents: &[&[u8]] = &[
            b"",
            b"not an Assuan redirect\n",
            b"%Assuan%\nsocket=/tmp/agent.sock",
            b"%Assuan%\nsocket=\n",
            b"%Assuan%\nsocket=/tmp/agent.sock\nextra\n",
            b"%Assuan%\r\nsocket=/tmp/agent.sock\r\n",
            b"%Assuan%\nsocket=${UNTERMINATED\n",
            b"%Assuan%\nsocket=/tmp/agent socket\n",
            b"%Assuan%\nsocket=/tmp/agent\tsocket\n",
            b"%Assuan%\nsocket=/tmp/agent\0.sock\n",
        ];

        for (index, contents) in malformed_contents.iter().enumerate() {
            let tmp = tempdir().expect("tempdir");
            let public_path = tmp.path().join(format!("S.gpg-agent-{index}"));
            fs::write(&public_path, contents).expect("write malformed redirect");

            let error =
                resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                    .expect_err("malformed redirect must fail");

            assert!(
                format!("{error:#}").contains("malformed Assuan socket redirect"),
                "unexpected diagnostic for case {index}: {error:#}"
            );
            assert_eq!(
                fs::read(&public_path).expect("read preserved redirect"),
                *contents
            );
        }
    }

    #[test]
    fn assuan_redirect_larger_than_511_bytes_fails_closed() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let mut contents = ASSUAN_REDIRECTION_PREFIX.to_vec();
        contents.resize(ASSUAN_REDIRECTION_MAX_BYTES, b'a');
        contents.push(b'\n');
        assert_eq!(contents.len(), ASSUAN_REDIRECTION_MAX_BYTES + 1);
        fs::write(&public_path, &contents).expect("write oversized redirect");

        let error =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                .expect_err("oversized redirect must fail");

        assert!(format!("{error:#}").contains("too large"));
        assert_eq!(fs::read(&public_path).expect("read redirect"), contents);
    }

    #[test]
    fn redirected_socket_cleanup_removes_only_actual_socket() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let bind_path = tmp.path().join("S.gpg-agent.real");
        let redirect_contents = assuan_redirect_contents(&bind_path);
        fs::write(&public_path, &redirect_contents).expect("write redirect");
        let listener = StdUnixListener::bind(&bind_path).expect("bind actual socket");
        let identity = LIFECYCLE
            .owned_socket_identity(&bind_path, current_uid())
            .expect("socket identity");
        let resolved =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                .expect("resolve redirect");

        LIFECYCLE.cleanup_socket_file(&resolved.bind_path, identity, current_uid());

        assert!(!bind_path.exists());
        assert_eq!(
            fs::read(&public_path).expect("read preserved redirect"),
            redirect_contents
        );
        drop(listener);
    }

    #[test]
    fn redirected_socket_lifecycle_lock_is_derived_from_actual_socket() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let bind_path = tmp.path().join("S.gpg-agent.real");
        let redirect_contents = assuan_redirect_contents(&bind_path);
        fs::write(&public_path, &redirect_contents).expect("write redirect");
        let resolved =
            resolve_assuan_socket_path_with(&public_path, test_current_dir(tmp.path()))
                .expect("resolve redirect");

        let actual_lock_path = lifecycle_lock_path(&resolved.bind_path, current_uid())
            .expect("derive actual socket lock path");
        let public_lock_path = lifecycle_lock_path(&public_path, current_uid())
            .expect("derive public redirect lock path");

        assert_ne!(actual_lock_path, public_lock_path);
        assert!(!actual_lock_path.starts_with(tmp.path()));
        assert_eq!(
            fs::read(&public_path).expect("read preserved redirect"),
            redirect_contents
        );
    }

    fn assuan_redirect_contents(target: &Path) -> Vec<u8> {
        let mut contents = ASSUAN_REDIRECTION_PREFIX.to_vec();
        contents.extend_from_slice(target.as_os_str().as_bytes());
        contents.push(b'\n');
        contents
    }

    #[test]
    fn public_socket_target_drift_fails_closed() {
        let tmp = tempdir().expect("tempdir");
        let public_path = tmp.path().join("S.gpg-agent");
        let bind_path = tmp.path().join("S.gpg-agent.real");
        let replacement_path = tmp.path().join("S.gpg-agent.replacement");
        fs::write(&public_path, assuan_redirect_contents(&bind_path)).expect("write redirect");

        require_public_socket_target(&public_path, &bind_path)
            .expect("matching redirect must pass");

        fs::write(&public_path, assuan_redirect_contents(&replacement_path))
            .expect("redirect public path to replacement");
        let error = require_public_socket_target(&public_path, &bind_path)
            .expect_err("redirect drift must fail");

        assert!(format!("{error:#}").contains("public socket route changed"));
    }

    #[test]
    fn managed_path_detection_is_exact() {
        let uid = current_uid();
        let fallback_socket_path = crate::config::linux_fallback_gpg_agent_socket_path(uid);
        let fallback_home = fallback_socket_path.parent().expect("fallback home");
        let fallback_root = fallback_home.parent().expect("fallback root");
        assert_eq!(
            managed_socket_parent_chain_for_uid(&fallback_socket_path, uid),
            Some(vec![
                fallback_root.to_path_buf(),
                fallback_home.to_path_buf()
            ])
        );

        let other_socket_path = fallback_home.join("other.sock");
        assert_eq!(
            managed_socket_parent_chain_for_uid(&other_socket_path, uid),
            None
        );

        #[cfg(target_os = "macos")]
        {
            let home = dirs::home_dir().expect("home directory");
            let gpg_home = crate::config::macos_managed_gpg_home_path(&home);
            let socket_path = crate::config::managed_gpg_agent_socket_path(&gpg_home);
            assert_eq!(
                managed_socket_parent_chain_for_uid(&socket_path, uid),
                Some(vec![
                    gpg_home.parent().expect("group container").to_path_buf(),
                    gpg_home
                ])
            );
        }

        #[cfg(target_os = "linux")]
        {
            let gpg_home = crate::config::linux_managed_gpg_home_path().expect("Linux GPG home");
            let socket_path = crate::config::managed_gpg_agent_socket_path(&gpg_home);
            if socket_path != fallback_socket_path {
                assert_eq!(
                    managed_socket_parent_chain_for_uid(&socket_path, uid),
                    Some(vec![gpg_home])
                );
            }
        }
    }

    #[test]
    fn managed_parent_preparation_preserves_shared_ancestors_for_each_layout() {
        let tmp = tempdir().expect("tempdir");
        for (relative_home, owns_parent) in [
            ("run/keyguard-gpg-agent", false),
            ("tmp/keyguard-1000/gnupg", true),
            ("data/gnupg", false),
            (
                "Library/Group Containers/com.artemchep.keyguard/gnupg",
                true,
            ),
        ] {
            let home = tmp.path().join(relative_home);
            let parents = if owns_parent {
                vec![
                    home.parent().expect("home parent").to_path_buf(),
                    home.clone(),
                ]
            } else {
                vec![home.clone()]
            };
            let shared_parent = parents[0].parent().expect("shared parent");
            ensure_managed_socket_parent_dirs(&parents, current_uid()).expect("prepare new home");
            fs::set_permissions(shared_parent, fs::Permissions::from_mode(0o755))
                .expect("chmod shared parent");
            for parent in &parents {
                fs::set_permissions(parent, fs::Permissions::from_mode(0o755))
                    .expect("chmod managed parent");
            }

            ensure_managed_socket_parent_dirs(&parents, current_uid())
                .expect("secure existing home");

            assert_eq!(
                fs::metadata(shared_parent)
                    .expect("shared parent metadata")
                    .mode()
                    & 0o777,
                0o755
            );
            for parent in &parents {
                assert_eq!(
                    fs::metadata(parent)
                        .expect("managed parent metadata")
                        .mode()
                        & 0o777,
                    0o700
                );
            }
        }
    }

    #[test]
    fn external_parent_is_validated_without_creation_or_permission_changes() {
        let tmp = tempdir().expect("tempdir");
        let parent = tmp.path().join("gpgconf-parent");
        fs::create_dir(&parent).expect("create parent");
        fs::set_permissions(&parent, fs::Permissions::from_mode(0o700)).expect("chmod 700");

        let socket_path = parent.join("S.gpg-agent.external");
        ensure_socket_parent_dir_for_uid(&socket_path, current_uid()).expect("validate parent");
        assert_eq!(
            fs::symlink_metadata(&parent).expect("metadata").mode() & 0o777,
            0o700
        );

        let missing_parent = tmp.path().join("missing-parent");
        let missing_socket = missing_parent.join("S.gpg-agent.external");
        ensure_socket_parent_dir_for_uid(&missing_socket, current_uid())
            .expect_err("missing external parent must fail");
        assert!(!missing_parent.exists());
    }

    #[test]
    fn external_parent_rejects_symlink_and_unsafe_permissions_without_chmod() {
        let tmp = tempdir().expect("tempdir");
        let target = tmp.path().join("real-parent");
        let link = tmp.path().join("link-parent");
        fs::create_dir(&target).expect("create target");
        fs::set_permissions(&target, fs::Permissions::from_mode(0o755)).expect("chmod 755");
        symlink(&target, &link).expect("create symlink");

        let link_error = validate_external_socket_parent(&link, current_uid())
            .expect_err("symlink parent must fail");
        assert!(link_error.to_string().contains("symlink"));

        let mode_error = validate_external_socket_parent(&target, current_uid())
            .expect_err("non-private parent must fail");
        assert!(mode_error.to_string().contains("expected mode 0700"));
        assert_eq!(
            fs::symlink_metadata(&target).expect("metadata").mode() & 0o777,
            0o755
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
