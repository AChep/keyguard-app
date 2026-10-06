use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const TEST_TIMEOUT: Duration = Duration::from_secs(10);

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = PathBuf::from(format!("/tmp/kgl-{}-{unique:x}", std::process::id()));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        Self(path)
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

struct AgentProcess {
    pid: Option<i32>,
}

impl AgentProcess {
    fn start(socket_path: &Path) -> Self {
        let stdout = run_ensure(socket_path);
        let pid = stdout
            .lines()
            .find_map(|line| line.strip_prefix("SSH_AGENT_PID="))
            .and_then(|line| line.split(';').next())
            .expect("new daemon should report its pid")
            .parse::<i32>()
            .unwrap();
        assert!(pid > 0);
        Self { pid: Some(pid) }
    }

    fn kill(&mut self) {
        if let Some(pid) = self.pid.take() {
            // SAFETY: `pid` is the positive pid of the daemon started by this
            // test, and SIGKILL is a valid signal. No pointers are passed.
            unsafe { libc::kill(pid, libc::SIGKILL) };
        }
    }
}

impl Drop for AgentProcess {
    fn drop(&mut self) {
        self.kill();
    }
}

struct StartupProcess(Child);

impl Drop for StartupProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn run_ensure(socket_path: &Path) -> String {
    let stdout_path = socket_path.with_extension("stdout");
    let stderr_path = socket_path.with_extension("stderr");
    let mut process = StartupProcess(
        Command::new(env!("CARGO_BIN_EXE_keyguard-android-ssh-agent"))
            .args(["--ensure", "-s", "-a"])
            .arg(socket_path)
            .stdin(Stdio::null())
            // The daemon inherits stderr. Files avoid waiting for an
            // inherited pipe to close after the original parent exits.
            .stdout(File::create(&stdout_path).unwrap())
            .stderr(File::create(&stderr_path).unwrap())
            .spawn()
            .unwrap(),
    );
    wait_until(|| process.0.try_wait().unwrap().is_some());
    assert!(
        process.0.wait().unwrap().success(),
        "{}",
        fs::read_to_string(stderr_path).unwrap()
    );
    fs::read_to_string(stdout_path).unwrap()
}

fn wait_until(mut condition: impl FnMut() -> bool) {
    let deadline = Instant::now() + TEST_TIMEOUT;
    while !condition() {
        assert!(Instant::now() < deadline, "timed out waiting for agent");
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn assert_agent_responds(socket_path: &Path) {
    let mut client = UnixStream::connect(socket_path).unwrap();
    client.set_read_timeout(Some(TEST_TIMEOUT)).unwrap();
    client.set_write_timeout(Some(TEST_TIMEOUT)).unwrap();
    // An unsupported SSH-agent packet is answered locally, without Android.
    client.write_all(&[0, 0, 0, 1, 255]).unwrap();
    let mut response = [0; 5];
    client.read_exact(&mut response).unwrap();
    assert_eq!(response, [0, 0, 0, 1, 5]);
}

#[test]
fn daemon_survives_parent_exit_and_recovers_after_crash() {
    let temp = TestDir::new();
    let socket_path = temp.0.join("agent.sock");
    let mut agent = AgentProcess::start(&socket_path);
    assert_agent_responds(&socket_path);
    let reused = run_ensure(&socket_path);
    assert!(!reused.contains("SSH_AGENT_PID="));

    agent.kill();
    wait_until(|| match UnixStream::connect(&socket_path) {
        Ok(_) => false,
        Err(err) => {
            assert_eq!(err.kind(), io::ErrorKind::ConnectionRefused);
            true
        }
    });
    assert!(socket_path.exists(), "crash should leave a stale socket");

    let _replacement = AgentProcess::start(&socket_path);
    assert_agent_responds(&socket_path);
}
