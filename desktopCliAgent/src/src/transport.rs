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

/// The socket that a connection came in on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Endpoint {
    /// The agent's socket.
    Public,
    /// A socket in a Flatpak browser's folder, next to its copy of the
    /// connector. It serves only browser streams.
    FlatpakBrowser,
}

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
    /// Whether `caller` shows the process that a bundled program relays
    /// for. False when the peer is not a bundled program, or the agent
    /// could not find that process: `caller` then shows the peer itself.
    pub relayed: bool,
    /// The program bundled with this agent that the caller runs, if any.
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
    pub bundled_peer: Option<BundledPeer>,
    pub guard: CallerGuard,
}

/// A program bundled next to this agent. Each only relays the requests of
/// the process that started it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BundledPeer {
    /// The `keyguard` CLI, started by a terminal or an MCP client.
    Cli,
    /// The browser connector `keyguard-browser-host`, started by a browser
    /// for its extension.
    BrowserHost,
}

impl BundledPeer {
    const ALL: [Self; 2] = [Self::Cli, Self::BrowserHost];

    /// The file name of its executable.
    fn file_name(self) -> String {
        let name = match self {
            Self::Cli => "keyguard",
            Self::BrowserHost => keyguard_cli_protocol::browser::HOST_EXECUTABLE,
        };
        format!("{name}{}", std::env::consts::EXE_SUFFIX)
    }
}

/// The bundled program that `is_bundled` accepts, by its path in
/// `directory`.
fn find_bundled_peer(
    directory: &Path,
    mut is_bundled: impl FnMut(&Path) -> bool,
) -> Option<BundledPeer> {
    BundledPeer::ALL
        .into_iter()
        .find(|peer| is_bundled(&directory.join(peer.file_name())))
}

impl Accepted {
    /// A bundled program only relays the requests of the process that
    /// started it, such as an MCP client or a browser, so that process is
    /// shown as the caller once the agent finds it. The guard keeps
    /// verifying the bundled program.
    fn new(stream: BoxStream, mut caller: CallerIdentity, guard: CallerGuard) -> Self {
        let bundled_peer = guard.0.bundled_peer();
        #[cfg(unix)]
        let relayed = bundled_peer.and_then(|_| {
            keyguard_agent_identity::caller_identity::relayed_caller(&guard.0.core, caller.pid)
        });
        // Chromium starts its connector through `cmd.exe`.
        #[cfg(windows)]
        let relayed = bundled_peer.and_then(|peer| match peer {
            BundledPeer::Cli => {
                keyguard_agent_identity::windows::process::relayed_caller(caller.pid)
            }
            BundledPeer::BrowserHost => {
                keyguard_agent_identity::windows::process::relayed_browser_caller(caller.pid)
            }
        });
        let found = relayed.is_some();
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
            relayed: found,
            bundled_peer,
            guard,
        }
    }

    /// Whether the caller runs the `keyguard` CLI bundled with this agent.
    /// Never true for another bundled program, such as the browser
    /// connector.
    pub(crate) fn is_bundled_cli(&self) -> bool {
        self.bundled_peer == Some(BundledPeer::Cli)
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

/// Whether `caller` is the program at `bundled`, or a copy of it. The
/// bundled file is opened for every check, so that an updated program is
/// accepted without a restart. Only a copy of the same size is hashed.
#[cfg(any(test, not(target_os = "macos")))]
fn is_bundled_file(caller: &std::fs::File, bundled: &Path) -> bool {
    std::fs::File::open(bundled)
        .is_ok_and(|bundled| keyguard_cli_protocol::executable::same_executable(caller, &bundled))
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

    /// The program bundled next to this agent that the caller runs, if any.
    fn bundled_peer(&self) -> Option<BundledPeer> {
        let agent = std::env::current_exe().ok()?;
        let directory = agent.parent()?;
        #[cfg(target_os = "macos")]
        {
            let identity = self.core.macos()?;
            find_bundled_peer(directory, |bundled| {
                keyguard_agent_identity::macos::static_code_directory_hash(bundled)
                    .is_ok_and(|expected| expected == identity.code_directory_hash())
            })
        }
        #[cfg(not(target_os = "macos"))]
        {
            #[cfg(target_os = "linux")]
            let caller = self.open_executable()?;
            #[cfg(windows)]
            let caller = self.process.open_image().ok()?;
            find_bundled_peer(directory, |bundled| is_bundled_file(&caller, bundled))
        }
    }
}

#[cfg(target_os = "linux")]
impl RetainedCaller {
    /// The executable retained at accept, or the one that the caller's
    /// process ID runs. `None` when that process is hidden from the agent.
    fn open_executable(&self) -> Option<std::fs::File> {
        let path = match self.core.linux() {
            Some(identity) => {
                keyguard_agent_identity::socket_lifecycle::fd_path(&identity.executable_fd())
            }
            // A process in another PID namespace has the ID 0 here.
            None => format!("/proc/{}/exe", self.pid).into(),
        };
        std::fs::File::open(path).ok()
    }
}

impl CallerGuard {
    pub(crate) async fn revalidate(&self) -> Result<()> {
        let caller = self.0.clone();
        blocking(move || caller.revalidate()).await?
    }

    /// Linux: whether the agent can't open the caller's executable, as for a
    /// process in another PID namespace, such as a browser's Flatpak
    /// sandbox.
    pub(crate) async fn executable_hidden(&self) -> bool {
        #[cfg(target_os = "linux")]
        {
            let caller = self.0.clone();
            blocking(move || caller.open_executable().is_none())
                .await
                .unwrap_or(false)
        }
        #[cfg(not(target_os = "linux"))]
        false
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
    /// The socket file that this listener bound.
    #[cfg(target_os = "linux")]
    identity: keyguard_agent_identity::socket_lifecycle::EntryIdentity,
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
            #[cfg(target_os = "linux")]
            identity,
            _guard: guard,
            _lock: lock,
        })
    }

    pub async fn accept(&mut self) -> Result<Incoming> {
        let (stream, _) = self.listener.accept().await?;
        Ok(Incoming(stream))
    }
}

/// The sockets in Flatpak browsers' folders.
#[cfg(target_os = "linux")]
impl Listener {
    pub fn poll_accept(
        &self,
        context: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<Incoming>> {
        self.listener
            .poll_accept(context)
            .map(|accepted| Ok(Incoming(accepted?.0)))
    }

    /// The socket file that this listener bound. Someone else may have
    /// removed or replaced it since.
    pub fn identity(&self) -> keyguard_agent_identity::socket_lifecycle::EntryIdentity {
        self.identity
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
        assert!(is_bundled_file(&caller("keyguard", None), &bundled));
        std::fs::hard_link(&bundled, temp.path().join("linked")).unwrap();
        assert!(is_bundled_file(&caller("linked", None), &bundled));
        assert!(is_bundled_file(
            &caller("copy", Some(b"bundled cli")),
            &bundled
        ));
        assert!(!is_bundled_file(
            &caller("same size", Some(b"another cli")),
            &bundled
        ));
        assert!(!is_bundled_file(
            &caller("longer", Some(b"bundled cli 2")),
            &bundled
        ));
        assert!(!is_bundled_file(
            &caller("copy", None),
            &temp.path().join("missing")
        ));
    }

    #[test]
    fn the_bundled_peer_is_the_program_whose_file_the_caller_runs() {
        let temp = tempfile::tempdir().unwrap();
        let bundled = temp.path().join("bundled");
        std::fs::create_dir(&bundled).unwrap();
        let peer = |name: &str, content: &[u8]| {
            let caller = temp.path().join(name);
            std::fs::write(&caller, content).unwrap();
            find_bundled_peer(&bundled, |path| {
                is_bundled_file(&std::fs::File::open(&caller).unwrap(), path)
            })
        };
        // Neither program is installed, as in a build without the connector.
        assert_eq!(peer("cli", b"bundled cli"), None);
        std::fs::write(bundled.join(BundledPeer::Cli.file_name()), b"bundled cli").unwrap();
        assert_eq!(peer("cli", b"bundled cli"), Some(BundledPeer::Cli));
        assert_eq!(peer("host", b"browser host"), None);
        std::fs::write(
            bundled.join(BundledPeer::BrowserHost.file_name()),
            b"browser host",
        )
        .unwrap();
        assert_eq!(
            peer("host", b"browser host"),
            Some(BundledPeer::BrowserHost)
        );
        assert_eq!(peer("cli", b"bundled cli"), Some(BundledPeer::Cli));
        assert_eq!(peer("other", b"other host"), None);
    }

    #[test]
    fn bundled_programs_are_named_for_the_platform() {
        let names = BundledPeer::ALL.map(BundledPeer::file_name);
        if cfg!(windows) {
            assert_eq!(names, ["keyguard.exe", "keyguard-browser-host.exe"]);
        } else {
            assert_eq!(names, ["keyguard", "keyguard-browser-host"]);
        }
    }
}
