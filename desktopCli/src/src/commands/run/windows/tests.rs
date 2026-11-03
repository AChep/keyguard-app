use super::*;
use crate::commands::run::start;
use std::{
    path::Path,
    process::{Child, Command, ExitStatus, Stdio},
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{WAIT_OBJECT_0, WAIT_TIMEOUT},
    System::{
        JobObjects::IsProcessInJob,
        Threading::{
            OpenProcess, PROCESS_SYNCHRONIZE, PROCESS_TERMINATE, TerminateProcess,
            WaitForSingleObject,
        },
    },
};

const MODE: &str = "KEYGUARD_JOB_TEST_MODE";
const DIRECTORY: &str = "KEYGUARD_JOB_TEST_DIRECTORY";
const FIXTURE: &str = "commands::run::windows::tests::subprocess_fixture";
const WAIT: Duration = Duration::from_secs(10);

fn fixture_command(mode: &str, directory: &Path) -> Command {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .args(["--exact", FIXTURE, "--nocapture"])
        .env(MODE, mode)
        .env(DIRECTORY, directory)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::inherit());
    command
}

struct Fixture(Child);

impl Fixture {
    fn spawn(mode: &str, directory: &Path) -> Self {
        Self(fixture_command(mode, directory).spawn().unwrap())
    }

    fn wait(&mut self) -> ExitStatus {
        let deadline = Instant::now() + WAIT;
        loop {
            if let Some(status) = self.0.try_wait().unwrap() {
                return status;
            }
            assert!(Instant::now() < deadline, "fixture did not exit");
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Retains the exact descendant process and cleans it up even if an assertion fails.
struct Descendant(OwnedHandle);

impl Descendant {
    fn open(directory: &Path) -> Self {
        let path = directory.join("ready");
        let deadline = Instant::now() + WAIT;
        let pid = loop {
            if let Ok(value) = std::fs::read_to_string(&path)
                && let Ok(pid) = value.parse::<u32>()
            {
                break pid;
            }
            assert!(Instant::now() < deadline, "descendant did not become ready");
            std::thread::sleep(Duration::from_millis(10));
        };
        // SAFETY: pid identifies our fixture; these rights only wait for or end it.
        let handle = unsafe { OpenProcess(PROCESS_SYNCHRONIZE | PROCESS_TERMINATE, FALSE, pid) };
        assert!(!handle.is_null(), "{}", std::io::Error::last_os_error());
        // SAFETY: OpenProcess transferred a non-null owned process handle.
        Self(unsafe { OwnedHandle::from_raw_handle(handle.cast()) })
    }

    fn wait(&self, millis: u32) -> u32 {
        // SAFETY: the owned process handle has synchronize access and stays live.
        unsafe { WaitForSingleObject(self.0.as_raw_handle().cast(), millis) }
    }
}

impl Drop for Descendant {
    fn drop(&mut self) {
        if self.wait(0) == WAIT_TIMEOUT {
            // SAFETY: this handle refers only to the descendant spawned by this test.
            unsafe { TerminateProcess(self.0.as_raw_handle().cast(), 1) };
            self.wait(10_000);
        }
    }
}

#[test]
fn normal_return_disarms_an_assigned_job() {
    let directory = tempfile::tempdir().unwrap();
    assert_eq!(
        Fixture::spawn("guard", directory.path()).wait().code(),
        Some(42)
    );
}

#[test]
fn spawn_failures_and_success_preserve_command_exit_codes() {
    let directory = tempfile::tempdir().unwrap();
    for (mode, expected) in [("missing", 127), ("invalid", 126), ("exit", 23)] {
        assert_eq!(
            Fixture::spawn(mode, directory.path()).wait().code(),
            Some(expected),
            "{mode}"
        );
    }
}

#[test]
fn killing_the_cli_ends_its_running_command() {
    let directory = tempfile::tempdir().unwrap();
    let mut parent = Fixture::spawn("parent", directory.path());
    let descendant = Descendant::open(directory.path());
    assert_eq!(descendant.wait(0), WAIT_TIMEOUT);
    parent.0.kill().unwrap();
    parent.wait();
    assert_eq!(descendant.wait(10_000), WAIT_OBJECT_0);
}

#[test]
fn normal_completion_allows_a_commands_descendant_to_outlive_it() {
    let directory = tempfile::tempdir().unwrap();
    let mut parent = Fixture::spawn("parent_with_descendant", directory.path());
    let descendant = Descendant::open(directory.path());
    assert_eq!(parent.wait().code(), Some(23));
    assert_eq!(descendant.wait(0), WAIT_TIMEOUT);
}

fn run_fixture(mode: &str) -> i32 {
    let command = [
        std::env::current_exe().unwrap().into_os_string(),
        "--exact".into(),
        FIXTURE.into(),
        "--nocapture".into(),
    ];
    start(&command, vec![(MODE.into(), mode.to_owned().into())])
}

// Runs only when launched by a test above. Job membership and console handlers
// are process-wide, so none of the fixtures may execute in the test runner.
#[test]
fn subprocess_fixture() {
    let Ok(mode) = std::env::var(MODE) else {
        return;
    };
    let directory = std::path::PathBuf::from(std::env::var_os(DIRECTORY).unwrap());
    let code = match mode.as_str() {
        "guard" => {
            fn early_return() -> i32 {
                let job = kill_on_close_job().expect("test requires successful job assignment");
                let mut assigned = FALSE;
                // SAFETY: both process and job handles are live; assigned is writable.
                let result = unsafe {
                    IsProcessInJob(
                        GetCurrentProcess(),
                        job.handle.as_raw_handle().cast(),
                        &mut assigned,
                    )
                };
                assert_ne!(result, FALSE);
                assert_ne!(assigned, FALSE, "fixture must belong to this exact job");
                42
            }
            early_return()
        }
        "missing" | "invalid" => {
            let program = directory.join(format!("{mode}.exe"));
            if mode == "invalid" {
                std::fs::write(&program, b"not an executable").unwrap();
            }
            start(&[program.into_os_string()], Vec::new())
        }
        "exit" => run_fixture("exit_child"),
        "exit_child" => 23,
        "parent" => run_fixture("hold"),
        "parent_with_descendant" => run_fixture("spawn_descendant"),
        "spawn_descendant" => {
            // Intentionally leave a descendant alive after the command exits.
            // The outer test retains its process handle and terminates it.
            #[allow(clippy::zombie_processes)]
            let _child = fixture_command("hold", &directory).spawn().unwrap();
            // The parent test opens the descendant while `hold` keeps it alive.
            let deadline = Instant::now() + WAIT;
            while !directory.join("ready").exists() {
                assert!(Instant::now() < deadline);
                std::thread::sleep(Duration::from_millis(10));
            }
            23
        }
        "hold" => {
            std::fs::write(directory.join("ready"), std::process::id().to_string()).unwrap();
            // A bound also prevents a leaked fixture from surviving a failed test run.
            std::thread::sleep(Duration::from_secs(30));
            0
        }
        _ => panic!("unknown fixture mode: {mode}"),
    };
    std::process::exit(code);
}
