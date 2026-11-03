use super::*;
use crate::ipc::messages::CallerAuthorization;
use std::collections::HashSet;
use std::os::windows::process::CommandExt;
use std::process::{Child, Command, Stdio};
use tokio::net::windows::named_pipe::{ClientOptions, ServerOptions};

struct TestProcess(Child);

impl TestProcess {
    fn spawn(pipe: &str) -> Self {
        Self(
            Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "windows_identity::process::tests::process_fixture",
                    "--nocapture",
                ])
                .env("KEYGUARD_TEST_PROCESS_PIPE", pipe)
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW)
                .spawn()
                .unwrap(),
        )
    }

    fn stop(&mut self) {
        self.0.kill().unwrap();
        self.0.wait().unwrap();
    }
}

impl Drop for TestProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
fn process_fixture() {
    let Ok(path) = std::env::var("KEYGUARD_TEST_PROCESS_PIPE") else {
        return;
    };
    let _pipe = (!path.is_empty()).then(|| {
        std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .unwrap()
    });
    // The parent owns stdin and releases this fixture even if a test panics.
    std::io::stdin().read_line(&mut String::new()).unwrap();
}

fn pipe_name() -> String {
    let nonce = keyguard_agent_identity::ConnectionFingerprint::generate().unwrap();
    format!(
        r"\\.\pipe\keyguard-identity-test-{}",
        hex::encode(nonce.as_bytes())
    )
}

fn caller(identity: &WindowsCallerIdentity) -> CallerIdentity {
    let mut caller = CallerIdentity {
        authorization: Some(CallerAuthorization {
            connection_fingerprint: keyguard_agent_identity::ConnectionFingerprint::generate()
                .unwrap()
                .into_bytes()
                .to_vec(),
            ..Default::default()
        }),
        ..Default::default()
    };
    identity.update_caller(&mut caller).unwrap();
    caller
}

#[tokio::test]
async fn reconnecting_process_retains_subject_but_not_connection() {
    let name = pipe_name();
    let server = ServerOptions::new()
        .first_pipe_instance(true)
        .create(&name)
        .unwrap();
    let client = ClientOptions::new().open(&name).unwrap();
    server.connect().await.unwrap();
    let first = caller(&WindowsCallerIdentity::from_pipe(&server).unwrap());
    assert_eq!(first.pid, std::process::id());
    drop(client);
    drop(server);
    let name = pipe_name();
    let server = ServerOptions::new()
        .first_pipe_instance(true)
        .create(&name)
        .unwrap();
    let _client = ClientOptions::new().open(&name).unwrap();
    server.connect().await.unwrap();
    let second = caller(&WindowsCallerIdentity::from_pipe(&server).unwrap());
    let first = first.authorization.unwrap();
    let second = second.authorization.unwrap();
    assert_ne!(first.connection_fingerprint, second.connection_fingerprint);
    assert_eq!(first.subjects[0], second.subjects[0]);
    assert_eq!(
        first.subjects[0].evidence_source,
        Evidence::WindowsProcessSnapshot as i32
    );
    assert!(first
        .subjects
        .iter()
        .all(|subject| subject.kind != Kind::TerminalSession as i32));
}

#[tokio::test]
async fn named_pipe_peer_exit_rejects_further_use() {
    let name = pipe_name();
    let server = ServerOptions::new()
        .first_pipe_instance(true)
        .create(&name)
        .unwrap();
    let mut peer = TestProcess::spawn(&name);
    tokio::time::timeout(std::time::Duration::from_secs(10), server.connect())
        .await
        .unwrap()
        .unwrap();
    let identity = WindowsCallerIdentity::from_pipe(&server).unwrap();
    let mut caller = caller(&identity);
    assert_eq!(caller.pid, peer.0.id());
    peer.stop();
    assert!(identity.update_caller(&mut caller).is_err());
}

#[tokio::test]
async fn unconnected_pipe_cannot_supply_process_evidence() {
    let server = ServerOptions::new()
        .first_pipe_instance(true)
        .create(pipe_name())
        .unwrap();
    assert!(WindowsCallerIdentity::from_pipe(&server).is_err());
}

#[test]
fn sibling_processes_share_live_application_but_not_process_subjects() {
    let first = TestProcess::spawn("");
    let second = TestProcess::spawn("");
    let first = ProcessIdentity::open(first.0.id()).unwrap();
    let second = ProcessIdentity::open(second.0.id()).unwrap();
    let windows = HashSet::from([std::process::id()]);
    let first_app = find_application(&first, &windows).unwrap();
    let second_app = find_application(&second, &windows).unwrap();
    assert_eq!(first_app.pid(), std::process::id());
    assert_eq!(
        subject(&first_app, Kind::ApplicationInstance).unwrap(),
        subject(&second_app, Kind::ApplicationInstance).unwrap()
    );
    assert_ne!(
        subject(&first, Kind::Process).unwrap(),
        subject(&second, Kind::Process).unwrap()
    );
}

#[test]
fn expired_application_falls_back_to_live_process() {
    let mut app = TestProcess::spawn("");
    let identity = WindowsCallerIdentity {
        process: ProcessIdentity::open(std::process::id()).unwrap(),
        application: Some(ProcessIdentity::open(app.0.id()).unwrap()),
    };
    let mut before = caller(&identity);
    assert_eq!(before.authorization.as_ref().unwrap().subjects.len(), 2);
    app.stop();
    identity.update_caller(&mut before).unwrap();
    assert_eq!(before.authorization.unwrap().subjects.len(), 1);
    assert_eq!(before.app_pid, std::process::id());
}

#[test]
fn subject_separates_scope() {
    let process = ProcessIdentity::open(std::process::id()).unwrap();
    assert_ne!(
        subject(&process, Kind::Process).unwrap().fingerprint,
        subject(&process, Kind::ApplicationInstance)
            .unwrap()
            .fingerprint
    );
}
