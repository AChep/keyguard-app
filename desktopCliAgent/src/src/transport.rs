//! The owner-only public socket, and the verified identity of its clients.
#[cfg(unix)]
use anyhow::Context;
use anyhow::{Result, ensure};
use keyguard_agent_proto::CallerIdentity;
use keyguard_cli_protocol::transport::BoxStream;
#[cfg(windows)]
use keyguard_cli_protocol::transport::windows::Process;
use std::{path::Path, sync::Arc, time::Duration};

/// Runs identity work, which can validate code signatures, off the async workers.
async fn blocking<T: Send + 'static>(work: impl FnOnce() -> T + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(work)
        .await
        .map_err(|_| anyhow::anyhow!("identity_unavailable"))
}

#[cfg(unix)]
type Connection = tokio::net::UnixStream;
#[cfg(windows)]
type Connection = tokio::net::windows::named_pipe::NamedPipeServer;

/// An accepted connection whose peer is not verified yet.
pub(crate) struct Incoming(Connection);

impl Incoming {
    pub(crate) fn into_stream(self) -> BoxStream {
        Box::new(self.0)
    }

    /// Verifies the peer and collects its identity.
    pub(crate) async fn identify(self) -> Result<Accepted> {
        blocking(move || identify(self.0)).await?
    }
}

pub(crate) struct Accepted {
    pub stream: BoxStream,
    /// The verified caller, with the subjects the app may reuse an approval
    /// for, as the SSH and GPG agents attach them.
    pub caller: CallerIdentity,
    /// Whether the caller runs the `keyguard` CLI bundled with this agent.
    /// macOS compares the validated live code. Linux checks the executable
    /// retained at accept, or without SO_PEERPIDFD the one that the peer's
    /// process ID runs, and Windows the image file of the process that the
    /// pipe reports: it must be the bundled file, or a copy of it.
    ///
    /// This is best effort on every platform: it checks the process when the
    /// connection is identified, not the code that writes the request, and
    /// the CLI starts the command with the caller's `PATH` and directory.
    /// It only rules out programs that do not try to get around it; the
    /// approval in the app is what protects the values.
    pub bundled_cli: bool,
    pub guard: CallerGuard,
}

impl Accepted {
    /// The bundled CLI only relays the requests of the process that started
    /// it, such as an MCP client, so that process is shown as the caller.
    /// The guard keeps verifying the CLI.
    fn new(stream: BoxStream, mut caller: CallerIdentity, guard: CallerGuard) -> Self {
        let bundled_cli = guard.0.is_bundled_cli();
        #[cfg(unix)]
        let relayed = bundled_cli
            .then(|| {
                keyguard_agent_identity::caller_identity::relayed_caller(&guard.0.core, caller.pid)
            })
            .flatten();
        #[cfg(windows)]
        let relayed = bundled_cli
            .then(|| keyguard_agent_identity::windows::process::relayed_caller(caller.pid))
            .flatten();
        if let Some(relayed) = relayed {
            caller.pid = relayed.pid;
            caller.process_name = relayed.process_name;
            caller.executable_path = relayed.executable_path;
            caller.app_name = relayed.app_name;
            caller.app_bundle_path = relayed.app_bundle_path;
        }
        Self {
            stream,
            caller,
            bundled_cli,
            guard,
        }
    }
}

/// The shared caller guard, plus the executable checks of `keyguard run`.
/// Clones share one retained identity.
#[derive(Clone)]
pub(crate) struct CallerGuard(Arc<RetainedCaller>);

struct RetainedCaller {
    core: keyguard_agent_identity::caller_guard::CallerGuard,
    /// The process that connected. Without SO_PEERPIDFD, only its ID is known.
    #[cfg(target_os = "linux")]
    pid: u32,
    /// The client process, held so that its ID can not be reused.
    #[cfg(windows)]
    process: Process,
}

/// The `keyguard` CLI bundled next to this agent.
fn bundled_cli_path() -> Option<std::path::PathBuf> {
    let name = if cfg!(windows) {
        "keyguard.exe"
    } else {
        "keyguard"
    };
    std::env::current_exe()
        .ok()
        .map(|path| path.with_file_name(name))
}

#[cfg(any(test, not(target_os = "macos")))]
fn sha256_file(file: std::fs::File) -> Option<[u8; 32]> {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    std::io::copy(&mut std::io::BufReader::new(file), &mut hasher).ok()?;
    Some(hasher.finalize().into())
}

/// The file that `file` is: equal identities mean the same file.
#[cfg(all(unix, any(test, not(target_os = "macos"))))]
fn file_identity(file: &std::fs::File) -> Option<(u64, u64)> {
    use std::os::unix::fs::MetadataExt;
    let metadata = file.metadata().ok()?;
    Some((metadata.dev(), metadata.ino()))
}

/// The file that `file` is: equal identities mean the same file. ReFS needs
/// the 128-bit file ID.
#[cfg(windows)]
fn file_identity(file: &std::fs::File) -> Option<(u64, [u8; 16])> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_ID_INFO, FileIdInfo, GetFileInformationByHandleEx,
    };
    let mut info = FILE_ID_INFO::default();
    // SAFETY: file owns a live handle, and info is writable for the stated size.
    let found = unsafe {
        GetFileInformationByHandleEx(
            file.as_raw_handle().cast(),
            FileIdInfo,
            std::ptr::from_mut(&mut info).cast(),
            size_of::<FILE_ID_INFO>() as u32,
        )
    } != 0;
    found.then_some((info.VolumeSerialNumber, info.FileId.Identifier))
}

/// Whether `caller` is the CLI at `bundled`, or a copy of it. The bundled
/// file is opened for every check, so that an updated CLI is accepted
/// without a restart. Only a copy of the same size is hashed.
#[cfg(any(test, not(target_os = "macos")))]
fn is_bundled_file(caller: std::fs::File, bundled: &Path) -> bool {
    let Ok(bundled) = std::fs::File::open(bundled) else {
        return false;
    };
    let (Ok(caller_metadata), Ok(bundled_metadata)) = (caller.metadata(), bundled.metadata())
    else {
        return false;
    };
    if caller_metadata.len() != bundled_metadata.len() {
        return false;
    }
    let same_file = file_identity(&caller).is_some_and(|id| Some(id) == file_identity(&bundled));
    same_file || {
        let digest = sha256_file(caller);
        digest.is_some() && digest == sha256_file(bundled)
    }
}

impl RetainedCaller {
    fn revalidate(&self) -> Result<()> {
        self.core
            .revalidate()
            .map_err(|_| anyhow::anyhow!("caller_changed"))?;
        #[cfg(windows)]
        ensure!(self.process.is_running(), "caller_changed");
        Ok(())
    }

    fn is_bundled_cli(&self) -> bool {
        #[cfg(target_os = "macos")]
        {
            let Some(identity) = self.core.macos() else {
                return false;
            };
            bundled_cli_path()
                .and_then(|path| {
                    keyguard_agent_identity::macos::static_code_directory_hash(&path).ok()
                })
                .is_some_and(|expected| expected == identity.code_directory_hash())
        }
        #[cfg(target_os = "linux")]
        {
            use std::os::fd::AsRawFd;
            let path = match self.core.linux() {
                Some(identity) => {
                    format!("/proc/self/fd/{}", identity.executable_fd().as_raw_fd())
                }
                None => format!("/proc/{}/exe", self.pid),
            };
            std::fs::File::open(path)
                .ok()
                .zip(bundled_cli_path())
                .is_some_and(|(caller, bundled)| is_bundled_file(caller, &bundled))
        }
        #[cfg(windows)]
        {
            self.process
                .open_image()
                .ok()
                .zip(bundled_cli_path())
                .is_some_and(|(caller, bundled)| is_bundled_file(caller, &bundled))
        }
    }
}

impl CallerGuard {
    pub(crate) async fn revalidate(&self) -> Result<()> {
        let caller = self.0.clone();
        blocking(move || caller.revalidate()).await?
    }

    /// Resolves once the caller exits or runs other code. A check can
    /// validate code signatures, so it runs once a second; callers
    /// revalidate again before they act.
    pub(crate) async fn changed(&self) {
        loop {
            tokio::time::sleep(Duration::from_secs(1)).await;
            if self.revalidate().await.is_err() {
                return;
            }
        }
    }
}

#[cfg(unix)]
pub(crate) struct Listener {
    listener: tokio::net::UnixListener,
    _guard: keyguard_agent_identity::socket_lifecycle::SocketFileGuard,
    _lock: keyguard_agent_identity::socket_lifecycle::SocketLifecycleLock,
}

#[cfg(unix)]
impl Listener {
    pub async fn bind(path: &Path, state_dir: &Path) -> Result<Self> {
        use keyguard_agent_identity::socket_lifecycle::{SocketLifecycle, current_uid};
        let lifecycle = SocketLifecycle::new("cli");
        lifecycle.validate_socket_path(path)?;
        let uid = current_uid();
        let lock =
            lifecycle.acquire_lifecycle_lock_in_directory(path, uid, &state_dir.join("locks"))?;
        lifecycle.prepare_socket_path_for_bind(path, uid).await?;
        let listener = tokio::net::UnixListener::bind(path)?;
        let identity = lifecycle.owned_socket_identity(path, uid)?;
        let guard = lifecycle.guard(path, identity, uid);
        lifecycle.secure_bound_socket(path, uid, identity)?;
        Ok(Self {
            listener,
            _guard: guard,
            _lock: lock,
        })
    }

    pub async fn accept(&mut self) -> Result<Incoming> {
        let (stream, _) = self.listener.accept().await?;
        Ok(Incoming(stream))
    }
}

#[cfg(unix)]
fn identify(stream: tokio::net::UnixStream) -> Result<Accepted> {
    let credentials = stream.peer_cred()?;
    ensure!(
        credentials.uid() == keyguard_agent_identity::socket_lifecycle::current_euid(),
        "peer_owner_mismatch"
    );
    let identity =
        keyguard_agent_identity::caller_identity::caller_context_from_unix_stream(&stream);
    let pid = credentials
        .pid()
        .and_then(|v| u32::try_from(v).ok())
        .context("peer_pid_unavailable")?;
    let (caller, core) = match identity {
        Some(identity) => {
            #[cfg(target_os = "macos")]
            let verified_app = identity
                .guard
                .macos()
                .and_then(|g| g.application())
                .is_some();
            #[cfg(target_os = "linux")]
            let verified_app = identity
                .guard
                .linux()
                .and_then(|g| g.application())
                .is_some();
            let caller = identity.caller;
            let (app_name, app_bundle_path) = if verified_app {
                (caller.app_name, caller.app_bundle_path)
            } else {
                Default::default()
            };
            let caller = CallerIdentity {
                pid,
                process_name: caller.process_name,
                executable_path: caller.executable_path,
                app_name,
                app_bundle_path,
                authorization: caller
                    .authorization
                    .as_ref()
                    .map(keyguard_agent_proto_adapter::proto_authorization),
                ..CallerIdentity::default()
            };
            (caller, identity.guard)
        }
        None => (
            CallerIdentity {
                pid,
                ..CallerIdentity::default()
            },
            Default::default(),
        ),
    };
    let guard = CallerGuard(Arc::new(RetainedCaller {
        core,
        #[cfg(target_os = "linux")]
        pid,
    }));
    Ok(Accepted::new(Box::new(stream), caller, guard))
}

#[cfg(windows)]
pub(crate) struct Listener {
    /// The instance waiting for a client. After a failure to create it, the
    /// next accept tries again.
    server: Option<tokio::net::windows::named_pipe::NamedPipeServer>,
    path: std::path::PathBuf,
}

#[cfg(windows)]
impl Listener {
    pub async fn bind(path: &Path, _state_dir: &Path) -> Result<Self> {
        Ok(Self {
            server: Some(keyguard_agent_identity::windows::create_owner_only_pipe(
                path, true,
            )?),
            path: path.into(),
        })
    }

    pub async fn accept(&mut self) -> Result<Incoming> {
        use keyguard_agent_identity::windows::create_owner_only_pipe;
        if self.server.is_none() {
            self.server = Some(create_owner_only_pipe(&self.path, false)?);
        }
        // The instance stays in place while waiting, so canceling keeps it.
        let Some(server) = self.server.as_mut() else {
            anyhow::bail!("pipe_unavailable");
        };
        if let Err(error) = server.connect().await {
            self.server = None;
            return Err(error.into());
        }
        let next = create_owner_only_pipe(&self.path, false).ok();
        match std::mem::replace(&mut self.server, next) {
            Some(server) => Ok(Incoming(server)),
            None => anyhow::bail!("pipe_unavailable"),
        }
    }
}

#[cfg(windows)]
fn identify(server: tokio::net::windows::named_pipe::NamedPipeServer) -> Result<Accepted> {
    use std::os::windows::io::AsRawHandle;
    let mut pid = 0;
    ensure!(
        // SAFETY: server owns the connected pipe and pid is writable for this call.
        unsafe {
            windows_sys::Win32::System::Pipes::GetNamedPipeClientProcessId(
                server.as_raw_handle().cast(),
                &mut pid,
            )
        } != 0,
        "peer_pid_unavailable"
    );
    let process = Process::open(pid)?;
    let executable_path = windows::display_path(process.image());
    let process_name = process
        .image()
        .file_name()
        .map(|name| windows::display_path(Path::new(name)))
        .unwrap_or_default();
    let caller = CallerIdentity {
        pid,
        process_name,
        executable_path,
        ..CallerIdentity::default()
    };
    let guard = CallerGuard(Arc::new(RetainedCaller {
        core: Default::default(),
        process,
    }));
    Ok(Accepted::new(Box::new(server), caller, guard))
}

#[cfg(windows)]
mod windows {
    use std::path::Path;

    /// Display data is never authorization evidence. Escape control characters.
    pub(super) fn display_path(path: &Path) -> String {
        keyguard_agent_identity::windows::escape_display_text(&path.to_string_lossy())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_bundled_cli_is_its_file_or_a_copy() {
        let temp = tempfile::tempdir().unwrap();
        let bundled = temp.path().join("keyguard");
        std::fs::write(&bundled, b"bundled cli").unwrap();
        let caller = |name: &str, content: Option<&[u8]>| {
            let path = temp.path().join(name);
            if let Some(content) = content {
                std::fs::write(&path, content).unwrap();
            }
            std::fs::File::open(path).unwrap()
        };
        assert!(is_bundled_file(caller("keyguard", None), &bundled));
        std::fs::hard_link(&bundled, temp.path().join("linked")).unwrap();
        assert!(is_bundled_file(caller("linked", None), &bundled));
        assert!(is_bundled_file(
            caller("copy", Some(b"bundled cli")),
            &bundled
        ));
        assert!(!is_bundled_file(
            caller("same size", Some(b"another cli")),
            &bundled
        ));
        assert!(!is_bundled_file(
            caller("longer", Some(b"bundled cli 2")),
            &bundled
        ));
        assert!(!is_bundled_file(
            caller("copy", None),
            &temp.path().join("missing")
        ));
    }
}
