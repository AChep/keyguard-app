#![cfg(unix)]

#[path = "broker_lifecycle/fifo_cancellation.rs"]
mod fifo_cancellation;

#[path = "broker_lifecycle/mount_reconciliation.rs"]
mod mount_reconciliation;

use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use prost::Message;
use serde_json::{Value, json};
use std::{
    os::unix::fs::{FileTypeExt, PermissionsExt},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    process::{Child, Command},
    sync::{Notify, watch},
};

const CONTENT: &str = "SIMPLE='hello'\nEMPTY=''\nSPECIAL='two words # $dollar = equal'\n";

/// The `keyguard` CLI, built next to the agent. It belongs to another
/// package, so test both packages together.
fn cli_binary() -> PathBuf {
    let path =
        std::path::Path::new(env!("CARGO_BIN_EXE_keyguard-cli-agent")).with_file_name("keyguard");
    assert!(
        path.exists(),
        "{} is missing: test keyguard-cli together with keyguard-cli-agent",
        path.display()
    );
    path
}

async fn read_json(stream: &mut (impl AsyncRead + Unpin)) -> Value {
    let size = stream.read_u32().await.expect("frame header") as usize;
    assert!(size <= 1024 * 1024);
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes).await.expect("frame body");
    serde_json::from_slice(&bytes).expect("JSON")
}

async fn write_json(stream: &mut (impl AsyncWrite + Unpin), value: Value) {
    let bytes = serde_json::to_vec(&value).expect("serialize");
    stream
        .write_u32(bytes.len() as u32)
        .await
        .expect("frame header");
    stream.write_all(&bytes).await.expect("frame body");
}

// The private channel to the app, as the agent's build generates it.
#[allow(dead_code)]
mod protocol {
    include!(concat!(env!("OUT_DIR"), "/keyguard.cliagent.rs"));
}

use protocol::{request::Request as Op, response::Response as Reply};

/// The next private request of the agent, `None` once it disconnects.
async fn read_request(stream: &mut (impl AsyncRead + Unpin)) -> Option<Op> {
    let size = stream.read_u32().await.ok()?;
    assert!(size <= 1024 * 1024);
    let mut bytes = vec![0; size as usize];
    stream.read_exact(&mut bytes).await.ok()?;
    let request = protocol::Request::decode(bytes.as_slice()).expect("protobuf");
    Some(request.request.expect("a request"))
}

async fn write_response(stream: &mut (impl AsyncWrite + Unpin), response: Reply) {
    let bytes = protocol::Response {
        response: Some(response),
    }
    .encode_to_vec();
    stream
        .write_u32(bytes.len() as u32)
        .await
        .expect("frame header");
    stream.write_all(&bytes).await.expect("frame body");
}

fn error(code: &str) -> Reply {
    Reply::Error(protocol::ErrorResponse { code: code.into() })
}

fn manage_result(result: protocol::manage_response::Result) -> Reply {
    Reply::Manage(protocol::ManageResponse {
        result: Some(result),
    })
}

fn accounts() -> Reply {
    manage_result(protocol::manage_response::Result::Accounts(
        protocol::AccountList {
            accounts: vec![protocol::Account {
                id: "account".into(),
                name: "Personal".into(),
            }],
        },
    ))
}

/// The answer to `keyguard run`, as the app writes it.
fn run_result(variables: &[(&str, &str)]) -> Reply {
    let variables: serde_json::Map<String, Value> = variables
        .iter()
        .map(|(name, value)| ((*name).into(), json!(value)))
        .collect();
    Reply::Cli(protocol::CliResponse {
        result: serde_json::to_vec(&json!({"ok":true,"variables":variables})).unwrap(),
    })
}

struct App {
    directory: tempfile::TempDir,
    public: PathBuf,
    private: PathBuf,
    fifo: PathBuf,
    revision: watch::Sender<u32>,
    // 0: normal; 1: pending FIFO approval; 2: pending MCP approval;
    // 3: a management result of another tool;
    // 4: denied run; 5: run result with an invalid variable name;
    // 6: mount watch requests fail; 7: denied FIFO read; 8: empty FIFO content.
    mode: Arc<AtomicU8>,
    resolves: Arc<AtomicUsize>,
    pending: Arc<Notify>,
    canceled: Arc<Notify>,
    statuses: Arc<Mutex<Vec<protocol::MountStatusRequest>>>,
    server: tokio::task::JoinHandle<()>,
}

impl App {
    async fn new() -> Self {
        Self::with_snapshots(None).await
    }

    async fn with_snapshots(
        snapshots: Option<
            tokio::sync::mpsc::UnboundedSender<mount_reconciliation::SnapshotRequest>,
        >,
    ) -> Self {
        // Short absolute paths also exercise macOS's standard /tmp symlink.
        let directory = tempfile::Builder::new()
            .prefix("kg-cli-")
            .tempdir_in("/tmp")
            .unwrap();
        std::fs::set_permissions(directory.path(), std::fs::Permissions::from_mode(0o700)).unwrap();
        let private = directory.path().join("i");
        let public = directory.path().join("s");
        let fifo = directory.path().join(".env");
        let listener = UnixListener::bind(&private).unwrap();
        let (revision, receiver) = watch::channel(1u32);
        let mode = Arc::new(AtomicU8::new(0));
        let resolves = Arc::new(AtomicUsize::new(0));
        let pending = Arc::new(Notify::new());
        let canceled = Arc::new(Notify::new());
        let statuses = Arc::new(Mutex::new(Vec::new()));
        let task_mode = mode.clone();
        let task_resolves = resolves.clone();
        let task_pending = pending.clone();
        let task_canceled = canceled.clone();
        let task_statuses = statuses.clone();
        let mount_path = fifo.clone();
        let server = tokio::spawn(async move {
            loop {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut receiver = receiver.clone();
                let mode = task_mode.clone();
                let resolves = task_resolves.clone();
                let pending = task_pending.clone();
                let canceled = task_canceled.clone();
                let statuses = task_statuses.clone();
                let path = mount_path.clone();
                let snapshots = snapshots.clone();
                tokio::spawn(async move {
                    let Some(auth) = read_request(&mut stream).await else {
                        return;
                    };
                    assert_eq!(
                        auth,
                        Op::Authenticate(keyguard_agent_proto::AuthenticateRequest {
                            token: vec![0x11; 32],
                            protocol_revision: IPC_PROTOCOL_REVISION,
                        })
                    );
                    write_response(
                        &mut stream,
                        Reply::Authenticate(keyguard_agent_proto::AuthenticateResponse {
                            success: true,
                            protocol_revision: IPC_PROTOCOL_REVISION,
                        }),
                    )
                    .await;
                    let Some(request) = read_request(&mut stream).await else {
                        return;
                    };
                    let response = match request {
                        Op::Mounts(_) | Op::WatchMounts(_) if snapshots.is_some() => {
                            let (reply, response) = tokio::sync::oneshot::channel();
                            if snapshots
                                .as_ref()
                                .unwrap()
                                .send(mount_reconciliation::SnapshotRequest { request, reply })
                                .is_err()
                            {
                                return;
                            }
                            tokio::select! {
                                response = response => match response {
                                    Ok(response) => response,
                                    Err(_) => return,
                                },
                                _ = stream.read_u8() => return,
                            }
                        }
                        Op::WatchMounts(_) if mode.load(Ordering::SeqCst) == 6 => {
                            pending.notify_one();
                            return;
                        }
                        Op::Mounts(_) | Op::WatchMounts(_) => {
                            if let Op::WatchMounts(watch) = &request
                                && watch.revision == receiver.borrow().to_string()
                            {
                                tokio::select! {
                                    _ = receiver.changed() => {},
                                    _ = stream.read_u8() => return,
                                }
                            }
                            let revision = receiver.borrow().to_string();
                            Reply::Mounts(protocol::MountsResponse {
                                revision: revision.clone(),
                                mounts: vec![protocol::Mount {
                                    id: "mount".into(),
                                    path: path.to_string_lossy().into_owned(),
                                    generation: revision,
                                    bookmark: None,
                                }],
                            })
                        }
                        Op::ResolveMount(resolve) => {
                            resolves.fetch_add(1, Ordering::SeqCst);
                            let resolved = |content: &[u8]| {
                                Reply::ResolveMount(protocol::ResolveMountResponse {
                                    generation: resolve.generation.clone(),
                                    content: content.to_vec(),
                                })
                            };
                            match mode.load(Ordering::SeqCst) {
                                1 => {
                                    pending.notify_one();
                                    assert!(stream.read_u8().await.is_err());
                                    canceled.notify_one();
                                    return;
                                }
                                7 => error("user_denied"),
                                8 => resolved(b""),
                                _ => resolved(CONTENT.as_bytes()),
                            }
                        }
                        Op::MountStatus(status) => {
                            statuses.lock().unwrap().push(status);
                            Reply::MountStatus(protocol::MountStatusResponse {})
                        }
                        Op::Cli(cli) => {
                            let caller = cli.caller.expect("a caller");
                            assert_eq!(cli.command, "run");
                            let run: Value = serde_json::from_slice(&cli.request).expect("JSON");
                            assert_eq!(run["environment"], "env");
                            // The CLI relays for the process that started it.
                            assert_eq!(caller.pid, std::process::id());
                            // Present when the platform verified the caller, which
                            // macOS always does for the signed CLI.
                            #[cfg(target_os = "macos")]
                            assert!(caller.authorization.is_some());
                            if let Some(authorization) = &caller.authorization {
                                assert_eq!(authorization.connection_fingerprint.len(), 32);
                            }
                            match mode.load(Ordering::SeqCst) {
                                4 => error("user_denied"),
                                5 => run_result(&[("BAD-NAME", "value")]),
                                _ => {
                                    let mut variables =
                                        vec![("GREETING", "hello world"), ("EMPTY", "")];
                                    // Echo the account, so a test can see it was forwarded.
                                    if let Some(account) = run["account"].as_str() {
                                        variables.push(("ACCOUNT", account));
                                    }
                                    run_result(&variables)
                                }
                            }
                        }
                        Op::Manage(manage) => {
                            assert!(manage.caller.expect("a caller").pid > 0);
                            assert_eq!(manage.connection_id.len(), 32);
                            match mode.load(Ordering::SeqCst) {
                                2 => {
                                    pending.notify_one();
                                    assert!(stream.read_u8().await.is_err());
                                    canceled.notify_one();
                                    return;
                                }
                                // The result of another tool.
                                3 => accounts(),
                                _ if manage.tool == "create_local_env_file" => {
                                    manage_result(protocol::manage_response::Result::LocalEnvFile(
                                        protocol::LocalEnvFile {
                                            id: "mount".into(),
                                            environment_id: "env".into(),
                                            account_id: Some("account".into()),
                                            path: manage.arguments["path"].clone(),
                                            enabled: true,
                                            status: protocol::MountStatus::Pending.into(),
                                        },
                                    ))
                                }
                                _ => accounts(),
                            }
                        }
                        Op::Authenticate(_) => panic!("authenticated twice"),
                    };
                    write_response(&mut stream, response).await;
                });
            }
        });
        Self {
            directory,
            public,
            private,
            fifo,
            revision,
            mode,
            resolves,
            pending,
            canceled,
            statuses,
            server,
        }
    }

    fn state_dir(&self) -> PathBuf {
        // A fresh system has no parent for the state directory either.
        self.directory.path().join("fresh/state")
    }

    /// Starts an agent of this app, without waiting for it to be ready.
    async fn spawn(&self, public: &Path, state_dir: &Path, stdout: Stdio) -> Child {
        let mut child = Command::new(env!("CARGO_BIN_EXE_keyguard-cli-agent"))
            .arg("--ipc-socket")
            .arg(&self.private)
            .arg("--parent-pid")
            .arg(std::process::id().to_string())
            .arg("--cli-socket")
            .arg(public)
            .arg("--state-dir")
            .arg(state_dir)
            .stdin(Stdio::piped())
            .stdout(stdout)
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        child
            .stdin
            .as_mut()
            .unwrap()
            .write_all(format!("{}\n", "11".repeat(32)).as_bytes())
            .await
            .unwrap();
        child
    }

    async fn launch(&self) -> Child {
        let mut child = self
            .spawn(&self.public, &self.state_dir(), Stdio::piped())
            .await;
        let mut line = String::new();
        timeout(BufReader::new(child.stdout.take().unwrap()).read_line(&mut line))
            .await
            .unwrap();
        assert_eq!(line, "KEYGUARD_AGENT_READY 1\n");
        child
    }
}

impl Drop for App {
    fn drop(&mut self) {
        self.server.abort();
    }
}

async fn timeout<T>(future: impl std::future::Future<Output = T>) -> T {
    tokio::time::timeout(Duration::from_secs(10), future)
        .await
        .expect("operation timed out")
}

#[tokio::test]
async fn real_broker_serves_fifo_revokes_pending_reads_and_cleans_shutdown() {
    let app = App::new().await;
    let mut child = app.launch().await;
    let metadata = std::fs::symlink_metadata(&app.fifo).unwrap();
    assert!(metadata.file_type().is_fifo());
    assert_eq!(metadata.permissions().mode() & 0o777, 0o600);
    assert_eq!(
        app.statuses.lock().unwrap().first(),
        Some(&protocol::MountStatusRequest {
            mount_id: "mount".into(),
            status: protocol::MountStatus::Ready.into(),
        })
    );
    assert_eq!(
        timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
        CONTENT
    );

    app.mode.store(1, Ordering::SeqCst);
    let path = app.fifo.clone();
    let reader = tokio::spawn(async move { tokio::fs::read_to_string(path).await.unwrap() });
    timeout(app.pending.notified()).await;
    app.revision.send(2).unwrap();
    timeout(app.canceled.notified()).await;
    assert_eq!(timeout(reader).await.unwrap(), "");

    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
    assert!(!app.public.exists());
    assert!(!app.fifo.exists());
    let mut diagnostics = String::new();
    child
        .stderr
        .take()
        .unwrap()
        .read_to_string(&mut diagnostics)
        .await
        .unwrap();
    assert!(diagnostics.is_empty());
}

/// Opens the FIFO without waiting for a writer, as a reader that keeps it open.
fn open_reader(path: &std::path::Path) -> std::fs::File {
    use std::os::unix::fs::OpenOptionsExt;
    std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NONBLOCK)
        .open(path)
        .unwrap()
}

/// Reads until EOF, waiting while a writer is open with nothing written yet.
async fn read_to_eof(reader: &mut std::fs::File) -> String {
    use std::io::Read;
    let mut content = Vec::new();
    let mut buffer = [0; 4096];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => return String::from_utf8(content).unwrap(),
            Ok(size) => content.extend_from_slice(&buffer[..size]),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                tokio::time::sleep(Duration::from_millis(10)).await
            }
            Err(error) => panic!("{error}"),
        }
    }
}

#[tokio::test]
async fn a_reader_that_keeps_the_fifo_open_is_served_once() {
    let app = App::new().await;
    let mut child = app.launch().await;
    // A delivery that is not read yet stays in the FIFO, and is not followed
    // by another one.
    let mut reader = open_reader(&app.fifo);
    tokio::time::sleep(Duration::from_secs(1)).await;
    assert_eq!(app.resolves.load(Ordering::SeqCst), 1);
    assert_eq!(timeout(read_to_eof(&mut reader)).await, CONTENT);
    drop(reader);

    // A refused reader that keeps the FIFO open is not asked for again.
    app.mode.store(7, Ordering::SeqCst);
    let reader = open_reader(&app.fifo);
    tokio::time::sleep(Duration::from_secs(1)).await;
    assert_eq!(app.resolves.load(Ordering::SeqCst), 2);
    drop(reader);

    // Once no reader holds it, readers that come one after another are each served.
    app.mode.store(0, Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(300)).await;
    for _ in 0..2 {
        assert_eq!(
            timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
            CONTENT
        );
    }
    assert_eq!(app.resolves.load(Ordering::SeqCst), 4);
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn a_drained_reader_keeps_eof_while_later_readers_get_their_own_delivery() {
    use std::io::Read;
    let app = App::new().await;
    let mut child = app.launch().await;
    let mut reader = open_reader(&app.fifo);
    timeout(async {
        while app.resolves.load(Ordering::SeqCst) == 0 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    let mut content = vec![0; CONTENT.len()];
    timeout(async {
        let mut read = 0;
        while read < content.len() {
            match reader.read(&mut content[read..]) {
                Ok(0) => panic!("delivery ended before its complete content"),
                Ok(size) => read += size,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
                Err(error) => panic!("{error}"),
            }
        }
    })
    .await;
    assert_eq!(content, CONTENT.as_bytes());
    // Keep the drained descriptor open across several broker polling periods,
    // without reading its EOF. The old buffer is empty throughout this pause.
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(app.resolves.load(Ordering::SeqCst), 1);
    assert_eq!(timeout(read_to_eof(&mut reader)).await, "");
    for _ in 0..3 {
        assert_eq!(
            timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
            CONTENT
        );
        assert_eq!(timeout(read_to_eof(&mut reader)).await, "");
    }
    assert_eq!(app.resolves.load(Ordering::SeqCst), 4);
    drop(reader);
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn empty_and_refused_readers_keep_eof_without_blocking_later_readers() {
    use std::os::unix::fs::MetadataExt;
    for mode in [7, 8] {
        let app = App::new().await;
        app.mode.store(mode, Ordering::SeqCst);
        let mut child = app.launch().await;
        let mut reader = open_reader(&app.fifo);
        let inode = reader.metadata().unwrap().ino();
        // Wait for acceptance, so the initial EOF of O_NONBLOCK open cannot
        // be mistaken for the application's empty/refused response.
        timeout(async {
            while std::fs::symlink_metadata(&app.fifo).unwrap().ino() == inode {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await;
        assert_eq!(timeout(read_to_eof(&mut reader)).await, "");
        app.mode.store(0, Ordering::SeqCst);
        assert_eq!(
            timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
            CONTENT
        );
        assert_eq!(timeout(read_to_eof(&mut reader)).await, "");
        assert_eq!(app.resolves.load(Ordering::SeqCst), 2);
        drop(reader);
        drop(child.stdin.take());
        assert!(timeout(child.wait()).await.unwrap().success());
    }
}

#[tokio::test]
async fn failed_mount_watch_is_retried_and_keeps_the_mounts() {
    let app = App::new().await;
    let mut child = app.launch().await;
    app.mode.store(6, Ordering::SeqCst);
    // Ends the pending watch, so that the next one fails.
    app.revision.send(2).unwrap();
    timeout(app.pending.notified()).await;
    timeout(app.pending.notified()).await;
    assert!(child.try_wait().unwrap().is_none());
    app.mode.store(0, Ordering::SeqCst);
    assert_eq!(
        timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
        CONTENT
    );
    assert!(child.try_wait().unwrap().is_none());
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn termination_signal_stops_the_broker_cleanly() {
    let app = App::new().await;
    let mut child = app.launch().await;
    let pid = i32::try_from(child.id().unwrap()).unwrap();
    // SAFETY: kill only signals the broker started by this test.
    assert_eq!(unsafe { libc::kill(pid, libc::SIGTERM) }, 0);
    assert!(timeout(child.wait()).await.unwrap().success());
    assert!(!app.public.exists());
    assert!(!app.fifo.exists());
}

#[tokio::test]
async fn public_transport_is_metadata_only_and_disconnect_cancels_approval() {
    let app = App::new().await;
    let mut child = app.launch().await;
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"manage","tool":"authenticate","arguments":{}}),
    )
    .await;
    assert_eq!(
        read_json(&mut stream).await["result"]["accounts"][0]["id"],
        "account"
    );
    drop(stream);

    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"manage","tool":"resolveMount","arguments":{"mountId":"mount"}}),
    )
    .await;
    assert!(timeout(stream.read_u8()).await.is_err());

    // Private operations are not public operations: they are unknown to
    // the public socket, like the operations of a newer client.
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"resolveMount","mountId":"mount","generation":"1"}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"unsupported_command"})
    );
    assert!(timeout(stream.read_u8()).await.is_err());

    // A result of another tool never reaches the client.
    app.mode.store(3, Ordering::SeqCst);
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"manage","tool":"list_variables","arguments":{"environment_id":"env"}}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"invalid_response"})
    );

    app.mode.store(2, Ordering::SeqCst);
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"manage","tool":"authenticate","arguments":{}}),
    )
    .await;
    timeout(app.pending.notified()).await;
    drop(stream);
    timeout(app.canceled.notified()).await;
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn restart_reclaims_only_its_persisted_fifo_identity() {
    let app = App::new().await;
    let mut first = app.launch().await;
    first.kill().await.unwrap();
    assert!(
        std::fs::symlink_metadata(&app.fifo)
            .unwrap()
            .file_type()
            .is_fifo()
    );
    let mut second = app.launch().await;
    assert_eq!(
        timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
        CONTENT
    );
    // A replacement ordinary file must survive broker shutdown.
    std::fs::remove_file(&app.fifo).unwrap();
    std::fs::write(&app.fifo, b"existing-user-file").unwrap();
    drop(second.stdin.take());
    assert!(timeout(second.wait()).await.unwrap().success());
    assert_eq!(
        std::fs::read_to_string(&app.fifo).unwrap(),
        "existing-user-file"
    );
}

#[tokio::test]
async fn a_second_agent_exits_before_touching_mounts() {
    let app = App::new().await;
    let mut first = app.launch().await;
    let statuses = app.statuses.lock().unwrap().len();
    // The same app shares the lock, as a restart does. Another app with
    // another mount meets the listening socket, as a dev build does.
    let other = App::new().await;
    for (owner, state_dir) in [(&app, app.state_dir()), (&other, other.state_dir())] {
        let mut second = owner.spawn(&app.public, &state_dir, Stdio::piped()).await;
        assert!(!timeout(second.wait()).await.unwrap().success());
    }
    assert_eq!(app.statuses.lock().unwrap().len(), statuses);
    assert!(other.statuses.lock().unwrap().is_empty());
    assert!(std::fs::symlink_metadata(&other.fifo).is_err());
    assert_eq!(
        timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
        CONTENT
    );
    drop(first.stdin.take());
    assert!(timeout(first.wait()).await.unwrap().success());
}

#[tokio::test]
async fn a_failed_readiness_report_removes_the_fifos() {
    let app = App::new().await;
    // The app stopped reading before the agent became ready.
    let (reader, writer) = std::io::pipe().unwrap();
    drop(reader);
    let mut child = app
        .spawn(&app.public, &app.state_dir(), writer.into())
        .await;
    assert!(!timeout(child.wait()).await.unwrap().success());
    assert_eq!(
        app.statuses.lock().unwrap().first(),
        Some(&protocol::MountStatusRequest {
            mount_id: "mount".into(),
            status: protocol::MountStatus::Ready.into(),
        })
    );
    assert!(std::fs::symlink_metadata(&app.fifo).is_err());
    assert!(!app.public.exists());
}

#[tokio::test]
async fn mcp_stdio_initializes_and_exposes_only_the_eight_approved_tools() {
    let app = App::new().await;
    let mut broker = app.launch().await;
    let mut mcp = Command::new(cli_binary())
        .arg("mcp")
        .arg("--socket")
        .arg(&app.public)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut input = mcp.stdin.take().unwrap();
    let mut output = BufReader::new(mcp.stdout.take().unwrap()).lines();
    let initialize = json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"integration-test","version":"1"}}});
    input
        .write_all(format!("{initialize}\n").as_bytes())
        .await
        .unwrap();
    let initialized: Value =
        serde_json::from_str(&timeout(output.next_line()).await.unwrap().unwrap()).unwrap();
    assert_eq!(initialized["id"], 1);
    assert_eq!(
        initialized["result"]["serverInfo"]["name"],
        "keyguard-environments"
    );
    let ready = json!({"jsonrpc":"2.0","method":"notifications/initialized"});
    let list = json!({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}});
    input
        .write_all(format!("{ready}\n{list}\n").as_bytes())
        .await
        .unwrap();
    let tools: Value =
        serde_json::from_str(&timeout(output.next_line()).await.unwrap().unwrap()).unwrap();
    let names: Vec<_> = tools["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    assert_eq!(
        names,
        [
            "authenticate",
            "list_environments",
            "create_environment",
            "rename_environment",
            "list_variables",
            "append_variables",
            "create_local_env_file",
            "list_local_env_files"
        ]
    );
    assert!(
        initialized["result"]["capabilities"]
            .get("resources")
            .is_none()
    );
    // Neither request reaches the broker.
    let unknown = json!({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"missing","arguments":{}}});
    let invalid = json!({"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_variables","arguments":{}}});
    input
        .write_all(format!("{unknown}\n").as_bytes())
        .await
        .unwrap();
    let unknown: Value =
        serde_json::from_str(&timeout(output.next_line()).await.unwrap().unwrap()).unwrap();
    assert_eq!(unknown["error"]["code"], -32602);
    assert!(unknown.get("result").is_none());
    input
        .write_all(format!("{invalid}\n").as_bytes())
        .await
        .unwrap();
    let invalid: Value =
        serde_json::from_str(&timeout(output.next_line()).await.unwrap().unwrap()).unwrap();
    assert_eq!(invalid["result"]["isError"], true);
    let error = &invalid["result"]["structuredContent"];
    assert_eq!(error["error"], "invalid_arguments");
    assert!(
        error["message"]
            .as_str()
            .unwrap()
            .contains("environment_id")
    );
    let call = json!({"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"authenticate","arguments":{}}});
    input
        .write_all(format!("{call}\n").as_bytes())
        .await
        .unwrap();
    let authenticated: Value =
        serde_json::from_str(&timeout(output.next_line()).await.unwrap().unwrap()).unwrap();
    assert_eq!(
        authenticated["result"]["structuredContent"]["accounts"][0]["id"],
        "account"
    );
    drop(input);
    assert!(timeout(mcp.wait()).await.unwrap().success());
    drop(broker.stdin.take());
    assert!(timeout(broker.wait()).await.unwrap().success());
}

async fn run_cli(app: &App, command: &[&str]) -> std::process::Output {
    run_cli_with(app, &[], command).await
}

async fn run_cli_with(app: &App, options: &[&str], command: &[&str]) -> std::process::Output {
    timeout(
        Command::new(cli_binary())
            .arg("run")
            .arg("--socket")
            .arg(&app.public)
            .args(["--environment", "env"])
            .args(options)
            .arg("--")
            .args(command)
            .env("GREETING", "inherited")
            .stdin(Stdio::null())
            .output(),
    )
    .await
    .unwrap()
}

#[tokio::test]
async fn run_starts_the_command_with_the_environment_values() {
    let app = App::new().await;
    let mut broker = app.launch().await;

    let output = run_cli(
        &app,
        &[
            "sh",
            "-c",
            r#"printf '%s|%s|%s' "$GREETING" "${EMPTY-unset}" "${ACCOUNT-unset}""#,
        ],
    )
    .await;
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    // Values override inherited variables; empty values are set; no account
    // is sent unless one is given.
    assert_eq!(
        String::from_utf8_lossy(&output.stdout),
        "hello world||unset"
    );
    let output = run_cli_with(
        &app,
        &["--account", "work"],
        &["sh", "-c", r#"printf '%s' "$ACCOUNT""#],
    )
    .await;
    assert_eq!(String::from_utf8_lossy(&output.stdout), "work");
    assert_eq!(
        run_cli(&app, &["sh", "-c", "exit 7"]).await.status.code(),
        Some(7)
    );
    assert_eq!(
        run_cli(&app, &["keyguard-test-missing-command"])
            .await
            .status
            .code(),
        Some(127)
    );

    app.mode.store(4, Ordering::SeqCst);
    let denied = run_cli(&app, &["sh", "-c", "echo started"]).await;
    assert_eq!(denied.status.code(), Some(1));
    assert!(denied.stdout.is_empty());
    assert!(String::from_utf8_lossy(&denied.stderr).contains("denied"));

    app.mode.store(5, Ordering::SeqCst);
    let invalid = run_cli(&app, &["sh", "-c", "echo started"]).await;
    assert_eq!(invalid.status.code(), Some(1));
    assert!(invalid.stdout.is_empty());
    assert!(String::from_utf8_lossy(&invalid.stderr).contains("invalid_response"));

    drop(broker.stdin.take());
    assert!(timeout(broker.wait()).await.unwrap().success());
}

#[tokio::test]
async fn run_values_are_only_served_to_the_bundled_cli() {
    let app = App::new().await;
    let mut broker = app.launch().await;
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"cli","command":"run","request":{"environment":"env","command":["env"],"workingDirectory":"/"}}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"unverified_cli"})
    );
    drop(broker.stdin.take());
    assert!(timeout(broker.wait()).await.unwrap().success());
}

#[tokio::test]
async fn unknown_operations_and_commands_are_answered_as_unsupported() {
    let app = App::new().await;
    let mut broker = app.launch().await;
    for frame in [
        json!({"type":"cli","command":"read","request":{"reference":"x"}}),
        json!({"type":"whoami"}),
        json!({"type":"run","environment":"env","command":["env"],"workingDirectory":"/"}),
    ] {
        let mut stream = UnixStream::connect(&app.public).await.unwrap();
        write_json(&mut stream, frame).await;
        assert_eq!(
            timeout(read_json(&mut stream)).await,
            json!({"ok":false,"error":"unsupported_command"})
        );
        assert!(timeout(stream.read_u8()).await.is_err());
    }
    // A known command that a client tries to attach a caller to is dropped.
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"cli","command":"run","request":{"environment":"env","command":["env"],"workingDirectory":"/"},"caller":{"pid":1}}),
    )
    .await;
    assert!(timeout(stream.read_u8()).await.is_err());
    drop(broker.stdin.take());
    assert!(timeout(broker.wait()).await.unwrap().success());
}

// This test harness can also act as an independently exec-able socket client.
#[cfg(target_os = "macos")]
#[test]
fn peer_process_fixture() {
    use std::{
        io::{Read, Write},
        os::{fd::AsRawFd, unix::process::CommandExt},
    };
    let Ok(path) = std::env::var("KEYGUARD_CLI_TEST_PEER_SOCKET") else {
        return;
    };
    let mut stream = std::os::unix::net::UnixStream::connect(path).unwrap();
    let request = br#"{"type":"manage","tool":"authenticate","arguments":{}}"#;
    stream
        .write_all(&(request.len() as u32).to_be_bytes())
        .unwrap();
    stream.write_all(request).unwrap();
    let mut instruction = [0u8];
    std::io::stdin().read_exact(&mut instruction).unwrap();
    // Keep the connection across exec to prove identity change, not EOF, cancels it.
    assert_eq!(
        // SAFETY: stream owns a live descriptor, and clearing FD_CLOEXEC is local to this fixture.
        unsafe { libc::fcntl(stream.as_raw_fd(), libc::F_SETFD, 0) },
        0
    );
    let error = std::process::Command::new("/bin/sleep").arg("10").exec();
    panic!("exec failed: {error}");
}

#[cfg(target_os = "macos")]
#[tokio::test]
async fn exec_during_approval_revokes_the_retained_peer_identity() {
    let app = App::new().await;
    app.mode.store(2, Ordering::SeqCst);
    let mut broker = app.launch().await;
    let mut peer = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "peer_process_fixture", "--nocapture"])
        .env("KEYGUARD_CLI_TEST_PEER_SOCKET", &app.public)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    timeout(app.pending.notified()).await;
    peer.stdin.take().unwrap().write_all(b"x").await.unwrap();
    timeout(app.canceled.notified()).await;
    // The new executable is still alive, holding the inherited socket open.
    assert!(peer.try_wait().unwrap().is_none());
    peer.kill().await.unwrap();
    drop(broker.stdin.take());
    assert!(timeout(broker.wait()).await.unwrap().success());
}
