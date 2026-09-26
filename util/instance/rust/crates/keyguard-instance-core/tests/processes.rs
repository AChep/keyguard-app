//! Real-process election, recovery, and native bridge lifetime regressions.
use keyguard_instance_core::bridge;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

const APP_LOCK_FILE: &str = "kgi-app.lock";
const APP_ENDPOINT_FILE: &str = "kgi-app.endpoint";

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let mut random = [0; 8];
        getrandom::fill(&mut random).unwrap();
        let suffix = u64::from_ne_bytes(random);
        Self(std::env::temp_dir().join(format!("kgi-test-{}-{suffix:x}", std::process::id())))
    }

    fn path(&self) -> &str {
        self.0.to_str().unwrap()
    }

    fn acquire(&self, identity: &str) -> u64 {
        let handle =
            bridge::acquire_or_activate(self.path(), runtime().to_str().unwrap(), identity, 5000);
        assert!(handle > 0, "failed to acquire: {handle}");
        handle as u64
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn runtime() -> PathBuf {
    #[cfg(unix)]
    {
        PathBuf::from("/tmp")
    }
    #[cfg(windows)]
    {
        std::env::temp_dir()
    }
}

struct Process {
    child: Child,
    output: Receiver<String>,
}

impl Process {
    fn start(directory: &Directory, identity: &str, timeout: u64) -> Self {
        let mut child = Command::new(env!("CARGO_BIN_EXE_instance-fixture"))
            .arg(directory.path())
            .arg(runtime())
            .arg(identity)
            .arg(timeout.to_string())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        let stdout = child.stdout.take().unwrap();
        let (sender, output) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                if sender.send(line.unwrap()).is_err() {
                    break;
                }
            }
        });
        Self { child, output }
    }

    fn line(&self) -> String {
        self.output
            .recv_timeout(Duration::from_secs(10))
            .expect("fixture did not respond")
    }

    fn command(&mut self, command: &str) {
        writeln!(self.child.stdin.as_mut().unwrap(), "{command}").unwrap();
        self.child.stdin.as_mut().unwrap().flush().unwrap();
    }

    fn kill(&mut self) {
        self.child.kill().unwrap();
        self.child.wait().unwrap();
    }
}

impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[test]
fn simultaneous_processes_elect_exactly_one_primary() {
    let directory = Directory::new();
    let mut processes: Vec<_> = (0..8)
        .map(|_| Process::start(&directory, "app", 5000))
        .collect();
    let outcomes: Vec<_> = processes.iter().map(Process::line).collect();
    assert_eq!(
        outcomes
            .iter()
            .filter(|result| *result == "PRIMARY")
            .count(),
        1,
        "{outcomes:?}"
    );
    assert_eq!(
        outcomes
            .iter()
            .filter(|result| *result == "ACTIVATED")
            .count(),
        7,
        "{outcomes:?}"
    );
    for (process, result) in processes.iter_mut().zip(outcomes) {
        if result == "PRIMARY" {
            process.command("close");
        }
    }
}

#[test]
fn activation_before_first_receiver_is_retained_and_coalesced() {
    let directory = Directory::new();
    let handle = directory.acquire("app");
    for _ in 0..3 {
        let secondary = Process::start(&directory, "app", 5000);
        assert_eq!(secondary.line(), "ACTIVATED");
    }
    assert_eq!(bridge::wait_event(handle), 1);
    let (sender, receiver) = mpsc::channel();
    let waiter = std::thread::spawn(move || {
        sender.send(bridge::wait_event(handle)).unwrap();
    });
    assert!(receiver.recv_timeout(Duration::from_millis(75)).is_err());
    assert_eq!(bridge::stop(handle), 0);
    assert_eq!(receiver.recv_timeout(Duration::from_secs(2)).unwrap(), 0);
    waiter.join().unwrap();
    assert_eq!(bridge::close(handle), 0);
}

#[test]
fn stopped_or_unreachable_primary_never_allows_duplicate_ownership() {
    let directory = Directory::new();
    let mut primary = Process::start(&directory, "app", 5000);
    assert_eq!(primary.line(), "PRIMARY");
    primary.command("stop");
    assert_eq!(primary.line(), "STOPPED");
    let secondary = Process::start(&directory, "app", 200);
    assert_eq!(secondary.line(), "ERROR -3");
    primary.command("close");
    assert!(primary.child.wait().unwrap().success());
    let replacement = Process::start(&directory, "app", 5000);
    assert_eq!(replacement.line(), "PRIMARY");
}

#[test]
fn process_crash_releases_ownership_and_replaces_stale_endpoint() {
    let directory = Directory::new();
    let mut primary = Process::start(&directory, "app", 5000);
    assert_eq!(primary.line(), "PRIMARY");
    let old_record = std::fs::read_to_string(directory.0.join(APP_ENDPOINT_FILE)).unwrap();
    primary.kill();
    let mut replacement = Process::start(&directory, "app", 5000);
    assert_eq!(replacement.line(), "PRIMARY");
    let new_record = std::fs::read_to_string(directory.0.join(APP_ENDPOINT_FILE)).unwrap();
    assert_ne!(old_record, new_record);
    #[cfg(unix)]
    assert!(!std::path::Path::new(old_record.lines().nth(2).unwrap()).exists());
    let secondary = Process::start(&directory, "app", 5000);
    assert_eq!(secondary.line(), "ACTIVATED");
    assert_eq!(replacement.line(), "ACTIVATION");
    replacement.command("close");
}

#[test]
fn identity_and_data_directory_scope_ownership() {
    let directory = Directory::new();
    let another_directory = Directory::new();
    let primary = Process::start(&directory, "app", 5000);
    let development = Process::start(&directory, "app-dev", 5000);
    let another = Process::start(&another_directory, "app", 5000);
    assert_eq!(primary.line(), "PRIMARY");
    assert_eq!(development.line(), "PRIMARY");
    assert_eq!(another.line(), "PRIMARY");
}

#[test]
fn close_wakes_receiver_and_preserves_permanent_ownership_file() {
    let directory = Directory::new();
    let handle = directory.acquire("app");
    let (sender, receiver) = mpsc::channel();
    let waiter = std::thread::spawn(move || {
        sender.send(bridge::wait_event(handle)).unwrap();
    });
    let started = Instant::now();
    assert_eq!(bridge::close(handle), 0);
    assert!(matches!(
        receiver.recv_timeout(Duration::from_secs(2)).unwrap(),
        0 | -5
    ));
    waiter.join().unwrap();
    assert!(started.elapsed() < Duration::from_secs(2));
    assert!(directory.0.join(APP_LOCK_FILE).is_file());
    assert!(!directory.0.join(APP_ENDPOINT_FILE).exists());
    assert_eq!(bridge::close(handle), -5);
    assert_eq!(bridge::wait_event(handle), -5);
    let replacement = directory.acquire("app");
    assert_ne!(handle, replacement);
    assert_eq!(bridge::close(replacement), 0);
}

#[test]
fn malformed_active_metadata_is_explicit_failure() {
    let directory = Directory::new();
    let handle = directory.acquire("app");
    std::fs::write(
        directory.0.join(APP_ENDPOINT_FILE),
        b"unrecognized-version\n",
    )
    .unwrap();
    let secondary = Process::start(&directory, "app", 5000);
    assert_eq!(secondary.line(), "ERROR -4");
    assert_eq!(bridge::close(handle), 0);
}

#[cfg(unix)]
#[test]
fn invalid_active_socket_path_is_reported_instead_of_retried_to_timeout() {
    let directory = Directory::new();
    let handle = directory.acquire("app");
    let metadata = directory.0.join(APP_ENDPOINT_FILE);
    let record = std::fs::read_to_string(&metadata).unwrap();
    let token = record.lines().nth(1).unwrap();
    // Valid protocol metadata with an endpoint longer than either supported Unix sun_path.
    std::fs::write(&metadata, format!("KGI1\n{token}\n/{}\n", "x".repeat(256))).unwrap();
    let secondary = Process::start(&directory, "app", 5000);
    assert_eq!(secondary.line(), "ERROR -1");
    assert_eq!(bridge::close(handle), 0);
}

#[test]
fn invalid_config_never_enters_native_wait() {
    let directory = Directory::new();
    for identity in ["", ".", "..", "../escape", "new\nline", "non-ascii-æ"] {
        assert_eq!(
            bridge::acquire_or_activate(
                directory.path(),
                runtime().to_str().unwrap(),
                identity,
                5000
            ),
            -1
        );
    }
    assert_eq!(
        bridge::acquire_or_activate("relative", "/tmp", "app", 5000),
        -1
    );
    assert_eq!(
        bridge::acquire_or_activate(directory.path(), runtime().to_str().unwrap(), "app", 0),
        -1
    );
}
