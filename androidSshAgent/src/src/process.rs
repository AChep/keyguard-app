use anyhow::{bail, Context, Result};
use libc::{self, SIGTERM};
use std::ffi::{CString, OsString};
use std::fs;
use std::io::{self, ErrorKind};
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::RawFd;
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::path::{Path, PathBuf};
use std::process;

pub(crate) const AUTH_SOCK_ENV: &str = "SSH_AUTH_SOCK";
pub(crate) const AGENT_PID_ENV: &str = "SSH_AGENT_PID";

#[derive(Debug, Clone, PartialEq, Eq)]
struct AgentTarget {
    pid: i32,
    auth_sock: PathBuf,
    socket_inode: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ProcessIdentity {
    start_time_ticks: u64,
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[derive(Debug)]
struct ProcessHandle {
    fd: RawFd,
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
#[derive(Debug)]
struct ProcessHandle;

#[cfg(any(target_os = "linux", target_os = "android"))]
impl Drop for ProcessHandle {
    fn drop(&mut self) {
        close_fd(self.fd);
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
impl ProcessHandle {
    fn open(pid: i32) -> io::Result<Option<Self>> {
        let Some(fd) = pidfd_open(pid)? else {
            return Ok(None);
        };
        Ok(Some(Self { fd }))
    }

    fn send_signal(&self, signal: i32) -> io::Result<()> {
        pidfd_send_signal(self.fd, signal)
    }
}

pub(crate) fn kill_existing_agent(is_csh: bool) -> Result<()> {
    let proc_root = Path::new("/proc");
    let target = resolve_kill_target_from_env()?;
    let process_handle = open_process_handle(target.pid)
        .with_context(|| format!("Failed to open a handle for process {}", target.pid))?;
    let identity = verify_process_owns_socket(&target, proc_root)?;
    send_termination_signal(&target, process_handle.as_ref(), identity, proc_root)?;

    if is_csh {
        println!("unsetenv {};", AUTH_SOCK_ENV);
        println!("unsetenv {};", AGENT_PID_ENV);
    } else {
        println!("unset {};", AUTH_SOCK_ENV);
        println!("unset {};", AGENT_PID_ENV);
    }
    println!("echo Agent pid {} killed;", target.pid);
    Ok(())
}

pub(crate) fn print_env_exports(is_csh: bool, socket_file: &Path, background_pid: Option<i32>) {
    for line in render_env_exports(is_csh, socket_file, background_pid) {
        println!("{line}");
    }
}

pub(crate) fn redirect_null(fd: i32, write: bool) -> Result<()> {
    let path = CString::new("/dev/null").context("Invalid /dev/null path")?;
    let null_fd = open_null_fd(&path, write)?;
    if null_fd == fd {
        return Ok(());
    }

    if let Err(err) = duplicate_fd(null_fd, fd) {
        close_fd(null_fd);
        return Err(err).context("Failed to redirect file descriptor");
    }
    close_fd(null_fd);
    Ok(())
}

pub(crate) fn terminate_pid(pid: i32) -> io::Result<()> {
    libc_kill(pid, SIGTERM)
}

fn render_env_exports(
    is_csh: bool,
    socket_file: &Path,
    background_pid: Option<i32>,
) -> Vec<String> {
    let socket_value = shell_quote(&socket_file.to_string_lossy());

    if is_csh {
        let mut lines = vec![format!("setenv {} {};", AUTH_SOCK_ENV, socket_value)];
        if let Some(pid) = background_pid {
            lines.push(format!("setenv {} {};", AGENT_PID_ENV, pid));
            lines.push(format!("echo Agent pid {};", pid));
        } else {
            lines.push(format!("echo Agent pid {};", process::id()));
        }
        return lines;
    }

    let mut lines = vec![format!("{}={}; export {0};", AUTH_SOCK_ENV, socket_value)];
    if let Some(pid) = background_pid {
        lines.push(format!("{}={}; export {0};", AGENT_PID_ENV, pid));
        lines.push(format!("echo Agent pid {};", pid));
    } else {
        lines.push(format!("echo Agent pid {};", process::id()));
    }
    lines
}

fn shell_quote(value: &str) -> String {
    if value.is_empty() {
        return "''".to_string();
    }

    let mut quoted = String::with_capacity(value.len() + 2);
    quoted.push('\'');
    for ch in value.chars() {
        if ch == '\'' {
            quoted.push_str("'\"'\"'");
        } else {
            quoted.push(ch);
        }
    }
    quoted.push('\'');
    quoted
}

fn resolve_kill_target_from_env() -> Result<AgentTarget> {
    let env_pid = std::env::var(AGENT_PID_ENV).ok();
    let auth_sock = std::env::var_os(AUTH_SOCK_ENV);
    resolve_kill_target(env_pid.as_deref(), auth_sock)
}

fn resolve_kill_target(env_pid: Option<&str>, auth_sock: Option<OsString>) -> Result<AgentTarget> {
    let env_pid = env_pid
        .with_context(|| format!("Failed to read {}", AGENT_PID_ENV))?
        .trim()
        .to_owned();
    let pid = env_pid
        .parse::<i32>()
        .with_context(|| format!("Failed to parse {} as a positive pid", AGENT_PID_ENV))?;
    if pid <= 0 {
        bail!("{} must be a positive pid", AGENT_PID_ENV);
    }

    let auth_sock = auth_sock.with_context(|| format!("Failed to read {}", AUTH_SOCK_ENV))?;
    let auth_sock = PathBuf::from(auth_sock);
    let metadata = fs::metadata(&auth_sock)
        .with_context(|| format!("Failed to stat {}", auth_sock.display()))?;
    if !metadata.file_type().is_socket() {
        bail!(
            "{}={} is not a Unix socket",
            AUTH_SOCK_ENV,
            auth_sock.display()
        );
    }
    let socket_inode = metadata.ino();

    Ok(AgentTarget {
        pid,
        auth_sock,
        socket_inode,
    })
}

fn verify_process_owns_socket(target: &AgentTarget, proc_root: &Path) -> Result<ProcessIdentity> {
    let fd_dir = proc_root.join(target.pid.to_string()).join("fd");
    let entries =
        fs::read_dir(&fd_dir).with_context(|| format!("Failed to inspect {}", fd_dir.display()))?;

    for entry_result in entries {
        let entry = match entry_result {
            Ok(entry) => entry,
            Err(err) if err.kind() == ErrorKind::NotFound => continue,
            Err(err) => {
                return Err(err).with_context(|| format!("Failed to inspect {}", fd_dir.display()));
            }
        };
        let link = match fs::read_link(entry.path()) {
            Ok(link) => link,
            Err(err) if err.kind() == ErrorKind::NotFound => continue,
            Err(err) => {
                return Err(err).with_context(|| format!("Failed to inspect {}", fd_dir.display()));
            }
        };
        if parse_socket_inode_link(&link) == Some(target.socket_inode) {
            return read_process_identity(target.pid, proc_root);
        }
    }

    bail!(
        "Refusing to kill process {} because it does not own {}",
        target.pid,
        target.auth_sock.display(),
    )
}

fn read_process_identity(pid: i32, proc_root: &Path) -> Result<ProcessIdentity> {
    let stat_path = proc_root.join(pid.to_string()).join("stat");
    let stat = fs::read_to_string(&stat_path)
        .with_context(|| format!("Failed to inspect {}", stat_path.display()))?;
    let start_time_ticks = parse_process_start_time(&stat)
        .with_context(|| format!("Failed to parse {}", stat_path.display()))?;

    Ok(ProcessIdentity { start_time_ticks })
}

fn parse_process_start_time(stat: &str) -> Result<u64> {
    let (_, rest) = stat
        .rsplit_once(") ")
        .context("Process stat is missing the comm terminator")?;
    let start_time = rest
        .split_whitespace()
        .nth(19)
        .context("Process stat is missing the start time field")?;

    start_time
        .parse::<u64>()
        .context("Process stat start time is not a valid integer")
}

fn send_termination_signal(
    target: &AgentTarget,
    process_handle: Option<&ProcessHandle>,
    expected_identity: ProcessIdentity,
    proc_root: &Path,
) -> Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    if let Some(process_handle) = process_handle {
        process_handle
            .send_signal(SIGTERM)
            .with_context(|| format!("Failed to kill process {}", target.pid))?;
        return Ok(());
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    let _ = process_handle;

    ensure_process_identity_unchanged(target.pid, expected_identity, proc_root)?;
    libc_kill(target.pid, SIGTERM)
        .with_context(|| format!("Failed to kill process {}", target.pid))?;
    Ok(())
}

fn ensure_process_identity_unchanged(
    pid: i32,
    expected_identity: ProcessIdentity,
    proc_root: &Path,
) -> Result<()> {
    let current_identity = read_process_identity(pid, proc_root)?;
    if current_identity != expected_identity {
        bail!(
            "Refusing to kill process {} because its identity changed",
            pid
        );
    }

    Ok(())
}

fn parse_socket_inode_link(link: &Path) -> Option<u64> {
    let value = link.to_str()?;
    let inode = value
        .strip_prefix("socket:[")?
        .strip_suffix(']')?
        .parse()
        .ok()?;
    Some(inode)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn open_process_handle(pid: i32) -> io::Result<Option<ProcessHandle>> {
    ProcessHandle::open(pid)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn open_process_handle(_pid: i32) -> io::Result<Option<ProcessHandle>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn pidfd_open(pid: i32) -> io::Result<Option<RawFd>> {
    // SAFETY: This directly invokes the kernel pidfd_open syscall with a plain pid and zero flags.
    // The returned fd is checked for -1 and is owned by the caller on success.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) as i32 };
    if fd == -1 {
        let err = io::Error::last_os_error();
        return match err.raw_os_error() {
            Some(libc::ENOSYS | libc::EINVAL | libc::EPERM) => Ok(None),
            _ => Err(err),
        };
    }
    Ok(Some(fd))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn pidfd_send_signal(fd: RawFd, signal: i32) -> io::Result<()> {
    // SAFETY: `fd` is a live pidfd owned by ProcessHandle and `signal` is forwarded unchanged.
    // No pointers are dereferenced because the last two syscall arguments are null / zero.
    let result = unsafe { libc::syscall(libc::SYS_pidfd_send_signal, fd, signal, 0, 0) as i32 };
    if result == -1 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn open_null_fd(path: &CString, write: bool) -> Result<i32> {
    // SAFETY: `path` is a valid NUL-terminated C string for the lifetime of the call, and the
    // flags are valid `open(2)` flags selecting read-only or read-write access to `/dev/null`.
    let fd = unsafe {
        libc::open(
            path.as_ptr(),
            if write { libc::O_RDWR } else { libc::O_RDONLY },
        )
    };
    if fd == -1 {
        bail!("Failed to open /dev/null: {}", io::Error::last_os_error());
    }
    Ok(fd)
}

fn duplicate_fd(src: i32, dst: i32) -> io::Result<()> {
    // SAFETY: `src` is an open file descriptor returned by `open(2)` and `dst` is one of the
    // standard file descriptor numbers being redirected. `dup2` performs the atomic replacement.
    let result = unsafe { libc::dup2(src, dst) };
    if result == -1 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn close_fd(fd: i32) {
    // SAFETY: `fd` is either an owned descriptor opened by this module or a best-effort close on
    // teardown. `close(2)` does not require additional invariants beyond passing an integer fd.
    let _ = unsafe { libc::close(fd) };
}

fn libc_kill(pid: i32, signal: i32) -> io::Result<()> {
    // SAFETY: `kill(2)` only reads the integer pid and signal values. No pointers are involved.
    let result = unsafe { libc::kill(pid, signal) };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct TestDir {
        path: PathBuf,
    }

    impl TestDir {
        fn new() -> Self {
            let unique = format!(
                "kg-{:x}-{:x}",
                process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            );
            let path = PathBuf::from("/tmp").join(unique);
            fs::create_dir_all(&path).unwrap();
            Self { path }
        }

        fn path(&self) -> &Path {
            &self.path
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    #[test]
    fn resolve_kill_target_rejects_missing_or_invalid_pid() {
        let missing = resolve_kill_target(None, Some(OsString::from("/tmp/agent.sock")));
        assert!(missing.unwrap_err().to_string().contains(AGENT_PID_ENV));

        let invalid = resolve_kill_target(Some("0"), Some(OsString::from("/tmp/agent.sock")));
        assert!(invalid.unwrap_err().to_string().contains("positive pid"));
    }

    #[test]
    fn resolve_kill_target_requires_auth_sock() {
        let err = resolve_kill_target(Some("123"), None).unwrap_err();
        assert!(err.to_string().contains(AUTH_SOCK_ENV));
    }

    #[test]
    fn verify_process_owns_socket_accepts_matching_proc_fd_link() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let _listener = std::os::unix::net::UnixListener::bind(&socket_path).unwrap();
        let target = resolve_kill_target(Some("4242"), Some(socket_path.into_os_string())).unwrap();
        let proc_root = temp.path().join("proc");
        let fd_dir = proc_root.join("4242").join("fd");
        fs::create_dir_all(&fd_dir).unwrap();
        write_proc_stat(&proc_root, 4242, 12345);
        symlink(
            format!("socket:[{}]", target.socket_inode),
            fd_dir.join("3"),
        )
        .unwrap();

        let identity = verify_process_owns_socket(&target, &proc_root).unwrap();
        assert_eq!(identity.start_time_ticks, 12345);
    }

    #[test]
    fn verify_process_owns_socket_rejects_non_matching_proc_fd_link() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let _listener = std::os::unix::net::UnixListener::bind(&socket_path).unwrap();
        let target = resolve_kill_target(Some("4242"), Some(socket_path.into_os_string())).unwrap();
        let proc_root = temp.path().join("proc");
        let fd_dir = proc_root.join("4242").join("fd");
        fs::create_dir_all(&fd_dir).unwrap();
        write_proc_stat(&proc_root, 4242, 12345);
        symlink("socket:[999999]", fd_dir.join("3")).unwrap();

        let err = verify_process_owns_socket(&target, &proc_root).unwrap_err();
        assert!(err.to_string().contains("does not own"));
    }

    #[test]
    fn parse_socket_inode_link_accepts_proc_socket_links() {
        assert_eq!(
            parse_socket_inode_link(Path::new("socket:[12345]")),
            Some(12345)
        );
        assert_eq!(parse_socket_inode_link(Path::new("pipe:[12345]")), None);
        assert_eq!(parse_socket_inode_link(Path::new("socket:[oops]")), None);
    }

    #[test]
    fn verify_process_owns_socket_supports_custom_proc_root() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let _listener = std::os::unix::net::UnixListener::bind(&socket_path).unwrap();
        let target = resolve_kill_target(Some("4242"), Some(socket_path.into_os_string())).unwrap();

        let fd_dir = temp.path().join("proc").join("4242").join("fd");
        fs::create_dir_all(&fd_dir).unwrap();
        write_proc_stat(&temp.path().join("proc"), 4242, 54321);
        symlink(
            format!("socket:[{}]", target.socket_inode),
            fd_dir.join("3"),
        )
        .unwrap();

        let identity = verify_process_owns_socket(&target, &temp.path().join("proc")).unwrap();
        assert_eq!(identity.start_time_ticks, 54321);
    }

    #[test]
    fn parse_process_start_time_supports_process_names_with_spaces() {
        let stat = "4242 (ssh agent) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 24680 20";
        assert_eq!(parse_process_start_time(stat).unwrap(), 24680);
    }

    #[test]
    fn ensure_process_identity_unchanged_rejects_pid_reuse() {
        let temp = TestDir::new();
        let proc_root = temp.path().join("proc");
        write_proc_stat(&proc_root, 4242, 12345);

        let err = ensure_process_identity_unchanged(
            4242,
            ProcessIdentity {
                start_time_ticks: 99999,
            },
            &proc_root,
        )
        .unwrap_err();

        assert!(err.to_string().contains("identity changed"));
    }

    #[test]
    fn render_env_exports_shell_quotes_socket_path() {
        let temp = TestDir::new();
        let marker_a = temp.path().join("marker-a");
        let marker_b = temp.path().join("marker-b");
        let socket_value = format!(
            "{}/agent '$HOME' $(touch {}) `touch {}`",
            temp.path().display(),
            marker_a.display(),
            marker_b.display(),
        );
        let export = render_env_exports(false, Path::new(&socket_value), Some(4321))
            .into_iter()
            .next()
            .unwrap();
        let script = format!("{export}\nprintf '%s' \"$SSH_AUTH_SOCK\"");
        let output = std::process::Command::new("sh")
            .arg("-c")
            .arg(script)
            .output()
            .unwrap();

        assert!(output.status.success());
        assert_eq!(String::from_utf8_lossy(&output.stdout), socket_value);
        assert!(!marker_a.exists());
        assert!(!marker_b.exists());
    }

    fn write_proc_stat(proc_root: &Path, pid: i32, start_time_ticks: u64) {
        let proc_dir = proc_root.join(pid.to_string());
        fs::create_dir_all(&proc_dir).unwrap();
        let stat = format!(
            "{pid} (keyguard agent) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 {start_time_ticks} 20\n"
        );
        fs::write(proc_dir.join("stat"), stat).unwrap();
    }
}
