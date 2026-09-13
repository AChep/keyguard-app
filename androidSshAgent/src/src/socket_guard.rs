use anyhow::{bail, Context, Result};
use rand::RngCore;
use std::fs::{self, File, OpenOptions};
use std::io::{self, ErrorKind};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileTypeExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener as StdUnixListener, UnixStream};
use std::path::{Path, PathBuf};

const SOCKET_DIR_MODE: u32 = 0o700;
const SOCKET_FILE_MODE: u32 = 0o600;
const GENERATED_SOCKET_DIR_ATTEMPTS: usize = 32;
const GENERATED_SOCKET_DIR_SUFFIX_BYTES: usize = 8;
const STARTUP_LOCK_MODE: u32 = 0o600;

pub(crate) enum EnsureSocket {
    Existing(PathBuf),
    Start(SocketGuard),
}

enum SocketProbe {
    Reachable,
    Missing,
    Stale,
}

struct SocketStartupLock {
    _file: File,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SocketIdentity {
    device: u64,
    inode: u64,
}

pub(crate) struct SocketGuard {
    socket_path: PathBuf,
    socket_dir: Option<PathBuf>,
    startup_lock: Option<SocketStartupLock>,
    socket_identity: Option<SocketIdentity>,
    armed: bool,
}

impl SocketGuard {
    pub(crate) fn new(
        explicit_path: Option<PathBuf>,
        pid: i32,
        random_hex: String,
    ) -> Result<Self> {
        if let Some(socket_path) = explicit_path {
            return Ok(Self::at(socket_path, None, None));
        }

        let tmpdir = default_tmpdir();
        Self::new_generated(tmpdir, pid, random_hex)
    }

    fn new_generated(tmpdir: PathBuf, pid: i32, random_hex: String) -> Result<Self> {
        let socket_dir = create_generated_socket_dir(&tmpdir, random_hex)?;
        let socket_path = socket_dir.join(format!("agent.{pid}"));
        Ok(Self::at(socket_path, Some(socket_dir), None))
    }

    /// Reuses a reachable agent at `socket_path`, or returns a guard that holds
    /// the startup lock until the new listener is bound.
    pub(crate) fn ensure(socket_path: PathBuf) -> Result<EnsureSocket> {
        ensure_parent_directory_is_private(&socket_path)?;
        if let SocketProbe::Reachable = probe_socket(&socket_path)? {
            return Ok(EnsureSocket::Existing(socket_path));
        }

        let startup_lock = SocketStartupLock::acquire(&socket_path)?;
        match probe_socket(&socket_path)? {
            SocketProbe::Reachable => return Ok(EnsureSocket::Existing(socket_path)),
            SocketProbe::Missing => {}
            SocketProbe::Stale => remove_stale_socket(&socket_path)?,
        }
        Ok(EnsureSocket::Start(Self::at(
            socket_path,
            None,
            Some(startup_lock),
        )))
    }

    fn at(
        socket_path: PathBuf,
        socket_dir: Option<PathBuf>,
        startup_lock: Option<SocketStartupLock>,
    ) -> Self {
        Self {
            socket_path,
            socket_dir,
            startup_lock,
            socket_identity: None,
            armed: true,
        }
    }

    pub(crate) fn path(&self) -> &Path {
        &self.socket_path
    }

    pub(crate) fn bind_listener(&mut self) -> Result<StdUnixListener> {
        if self.startup_lock.is_none() {
            ensure_parent_directory_is_private(&self.socket_path)?;
        }
        let listener = StdUnixListener::bind(&self.socket_path).with_context(|| {
            format!(
                "Failed to create Unix socket at {}",
                self.socket_path.display()
            )
        })?;
        let metadata = fs::symlink_metadata(&self.socket_path).with_context(|| {
            format!(
                "Failed to inspect Unix socket at {}",
                self.socket_path.display()
            )
        })?;
        self.socket_identity = Some(SocketIdentity::from(&metadata));
        listener.set_nonblocking(true).with_context(|| {
            format!(
                "Failed to mark socket {} as non-blocking",
                self.socket_path.display()
            )
        })?;
        fs::set_permissions(
            &self.socket_path,
            fs::Permissions::from_mode(SOCKET_FILE_MODE),
        )
        .with_context(|| {
            format!(
                "Failed to set permissions on {}",
                self.socket_path.display()
            )
        })?;
        // The socket is reachable now; competing starters may proceed and
        // will find it. Release before `fork` so the daemon never holds it.
        self.startup_lock = None;
        Ok(listener)
    }

    pub(crate) fn disarm(&mut self) {
        self.armed = false;
    }
}

fn probe_socket(socket_path: &Path) -> Result<SocketProbe> {
    match UnixStream::connect(socket_path) {
        Ok(_) => Ok(SocketProbe::Reachable),
        Err(err) if err.kind() == ErrorKind::NotFound => Ok(SocketProbe::Missing),
        Err(err) if err.kind() == ErrorKind::ConnectionRefused => Ok(SocketProbe::Stale),
        Err(err) => Err(err).with_context(|| {
            format!(
                "Failed to check existing Unix socket at {}",
                socket_path.display()
            )
        }),
    }
}

impl SocketStartupLock {
    fn acquire(socket_path: &Path) -> Result<Self> {
        let lock_path = startup_lock_path(socket_path);
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(STARTUP_LOCK_MODE)
            .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(&lock_path)
            .with_context(|| {
                format!(
                    "Failed to safely open socket startup lock {}",
                    lock_path.display()
                )
            })?;
        validate_startup_lock(&lock_path, &file)?;

        loop {
            // SAFETY: `file.as_raw_fd()` is a live descriptor for a regular
            // file and `LOCK_EX` is a valid flock operation.
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
                break;
            }
            let err = io::Error::last_os_error();
            if err.kind() == ErrorKind::Interrupted {
                continue;
            }
            return Err(err).with_context(|| {
                format!(
                    "Failed to acquire socket startup lock {}",
                    lock_path.display()
                )
            });
        }

        Ok(Self { _file: file })
    }
}

fn startup_lock_path(socket_path: &Path) -> PathBuf {
    let mut lock_path = socket_path.as_os_str().to_os_string();
    lock_path.push(".lock");
    PathBuf::from(lock_path)
}

fn validate_startup_lock(lock_path: &Path, file: &File) -> Result<()> {
    let metadata = file
        .metadata()
        .with_context(|| format!("Failed to inspect startup lock {}", lock_path.display()))?;
    if !metadata.is_file() {
        bail!("Socket startup lock {} is not a file", lock_path.display());
    }
    if !is_private_to_current_user(&metadata) {
        bail!("Socket startup lock {} is not private", lock_path.display());
    }
    Ok(())
}

fn remove_stale_socket(socket_path: &Path) -> Result<()> {
    let metadata = match fs::symlink_metadata(socket_path) {
        Ok(metadata) => metadata,
        Err(err) if err.kind() == ErrorKind::NotFound => return Ok(()),
        Err(err) => {
            return Err(err).with_context(|| {
                format!("Failed to inspect stale socket {}", socket_path.display())
            });
        }
    };
    if !metadata.file_type().is_socket() {
        bail!(
            "Refusing to remove non-socket entry at {}",
            socket_path.display()
        );
    }
    if metadata.uid() != current_uid() {
        bail!(
            "Refusing to remove socket at {} owned by another user",
            socket_path.display()
        );
    }

    remove_file_if_exists(socket_path)
        .with_context(|| format!("Failed to remove stale socket {}", socket_path.display()))
}

fn remove_file_if_exists(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(err) if err.kind() != ErrorKind::NotFound => Err(err),
        _ => Ok(()),
    }
}

impl From<&fs::Metadata> for SocketIdentity {
    fn from(metadata: &fs::Metadata) -> Self {
        Self {
            device: metadata.dev(),
            inode: metadata.ino(),
        }
    }
}

fn remove_socket_if_matches(path: &Path, expected: SocketIdentity) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata)
            if metadata.file_type().is_socket() && SocketIdentity::from(&metadata) == expected =>
        {
            remove_file_if_exists(path)
        }
        Ok(_) => Ok(()),
        Err(err) if err.kind() == ErrorKind::NotFound => Ok(()),
        Err(err) => Err(err),
    }
}

fn create_generated_socket_dir(tmpdir: &Path, initial_suffix: String) -> Result<PathBuf> {
    fs::create_dir_all(tmpdir)
        .with_context(|| format!("Failed to create temporary root {}", tmpdir.display()))?;
    let mut suffix = initial_suffix;

    for _ in 0..GENERATED_SOCKET_DIR_ATTEMPTS {
        let socket_dir = tmpdir.join(format!("keyguard-android-ssh-{suffix}"));
        match fs::create_dir(&socket_dir) {
            Ok(()) => {
                if let Err(err) =
                    fs::set_permissions(&socket_dir, fs::Permissions::from_mode(SOCKET_DIR_MODE))
                {
                    let _ = fs::remove_dir(&socket_dir);
                    return Err(err).with_context(|| {
                        format!("Failed to set permissions on {}", socket_dir.display())
                    });
                }
                return Ok(socket_dir);
            }
            Err(err) if err.kind() == ErrorKind::AlreadyExists => {
                suffix = random_hex(GENERATED_SOCKET_DIR_SUFFIX_BYTES);
            }
            Err(err) => {
                return Err(err).with_context(|| {
                    format!(
                        "Failed to create temporary directory {}",
                        socket_dir.display()
                    )
                });
            }
        }
    }

    bail!(
        "Failed to create a unique temporary directory under {}",
        tmpdir.display()
    )
}

fn ensure_parent_directory_is_private(socket_path: &Path) -> Result<()> {
    let parent = socket_path
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let metadata = fs::metadata(parent)
        .with_context(|| format!("Failed to inspect parent directory {}", parent.display()))?;

    if !metadata.is_dir() {
        bail!("Socket parent {} is not a directory", parent.display());
    }

    if !is_private_to_current_user(&metadata) {
        bail!(
            "Refusing to bind Unix socket in non-private directory {}",
            parent.display()
        );
    }

    Ok(())
}

fn is_private_to_current_user(metadata: &fs::Metadata) -> bool {
    metadata.uid() == current_uid() && (metadata.mode() & 0o077) == 0
}

fn current_uid() -> u32 {
    // SAFETY: `geteuid(2)` has no preconditions and returns the effective uid.
    unsafe { libc::geteuid() as u32 }
}

fn random_hex(bytes: usize) -> String {
    let mut data = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut data);
    data.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn default_tmpdir() -> PathBuf {
    if let Some(tmpdir) = std::env::var_os("TMPDIR") {
        PathBuf::from(tmpdir)
    } else if let Ok(prefix) = std::env::var("PREFIX") {
        Path::new(&prefix).join("tmp")
    } else {
        PathBuf::from("/tmp")
    }
}

impl Drop for SocketGuard {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }

        if let Some(socket_identity) = self.socket_identity {
            if self.socket_dir.is_some() || self.startup_lock.is_some() {
                let _ = remove_socket_if_matches(&self.socket_path, socket_identity);
            } else if let Ok(_startup_lock) = SocketStartupLock::acquire(&self.socket_path) {
                let _ = remove_socket_if_matches(&self.socket_path, socket_identity);
            }
        }
        if let Some(socket_dir) = &self.socket_dir {
            let _ = fs::remove_dir(socket_dir);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::Permissions;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    static NEXT_TEST_DIR_ID: AtomicU64 = AtomicU64::new(0);

    struct TestDir {
        path: PathBuf,
    }

    impl TestDir {
        fn new() -> Self {
            let unique = format!(
                "kgs-{:x}-{:x}-{:x}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT_TEST_DIR_ID.fetch_add(1, Ordering::Relaxed),
            );
            let path = PathBuf::from("/tmp").join(unique);
            fs::create_dir_all(&path).unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
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
    fn generated_socket_guard_sets_permissions_and_cleans_up() {
        let temp = TestDir::new();
        let socket_path;
        let socket_dir;

        {
            let mut guard =
                SocketGuard::new_generated(temp.path().to_path_buf(), 1234, "abcdef".to_string())
                    .unwrap();
            socket_path = guard.path().to_path_buf();
            socket_dir = guard.path().parent().unwrap().to_path_buf();
            let listener = guard.bind_listener().unwrap();

            let dir_mode = fs::metadata(&socket_dir).unwrap().permissions().mode() & 0o777;
            let socket_mode = fs::metadata(&socket_path).unwrap().permissions().mode() & 0o777;
            assert_eq!(dir_mode, SOCKET_DIR_MODE);
            assert_eq!(socket_mode, SOCKET_FILE_MODE);
            drop(listener);
        }

        assert!(!socket_path.exists());
        assert!(!socket_dir.exists());
    }

    #[test]
    fn generated_socket_guard_retries_preexisting_directory() {
        let temp = TestDir::new();
        let taken_dir = temp.path().join("keyguard-android-ssh-abcdef");
        fs::create_dir_all(&taken_dir).unwrap();

        let guard =
            SocketGuard::new_generated(temp.path().to_path_buf(), 1234, "abcdef".to_string())
                .unwrap();

        assert_ne!(guard.path().parent().unwrap(), &taken_dir);
        assert!(guard.path().parent().unwrap().starts_with(temp.path()));
    }

    #[test]
    fn generated_socket_guard_cleans_up_directory_before_bind() {
        let temp = TestDir::new();
        let socket_dir;

        {
            let guard =
                SocketGuard::new_generated(temp.path().to_path_buf(), 1234, "abcdef".to_string())
                    .unwrap();
            socket_dir = guard.path().parent().unwrap().to_path_buf();

            assert!(socket_dir.exists());
        }

        assert!(!socket_dir.exists());
    }

    #[test]
    fn disarmed_generated_socket_guard_preserves_socket_and_directory() {
        let temp = TestDir::new();
        let socket_path;
        let socket_dir;

        {
            let mut guard =
                SocketGuard::new_generated(temp.path().to_path_buf(), 1234, "abcdef".to_string())
                    .unwrap();
            socket_path = guard.path().to_path_buf();
            socket_dir = guard.path().parent().unwrap().to_path_buf();
            let listener = guard.bind_listener().unwrap();

            guard.disarm();
            drop(listener);
        }

        assert!(socket_path.exists());
        assert!(socket_dir.exists());
    }

    #[test]
    fn explicit_socket_guard_preserves_parent_directory() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");

        {
            let mut guard =
                SocketGuard::new(Some(socket_path.clone()), 1234, "unused".to_string()).unwrap();
            let listener = guard.bind_listener().unwrap();
            drop(listener);
        }

        assert!(!socket_path.exists());
        assert!(temp.path().exists());
    }

    #[test]
    fn failed_second_bind_preserves_reachable_original_listener() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let mut original_guard =
            SocketGuard::new(Some(socket_path.clone()), 1234, "unused".to_string()).unwrap();
        let original_listener = original_guard.bind_listener().unwrap();

        {
            let mut second_guard =
                SocketGuard::new(Some(socket_path.clone()), 1235, "unused".to_string()).unwrap();
            assert!(second_guard.bind_listener().is_err());
        }

        assert!(socket_path.exists());
        let client = UnixStream::connect(&socket_path).unwrap();
        let (connection, _) = original_listener.accept().unwrap();
        drop(connection);
        drop(client);
    }

    #[test]
    fn failed_bind_preserves_preexisting_file() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        fs::write(&socket_path, b"keep me").unwrap();

        {
            let mut guard =
                SocketGuard::new(Some(socket_path.clone()), 1234, "unused".to_string()).unwrap();
            assert!(guard.bind_listener().is_err());
        }

        assert_eq!(fs::read(&socket_path).unwrap(), b"keep me");
    }

    #[test]
    fn ensure_reuses_reachable_socket() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let _listener = StdUnixListener::bind(&socket_path).unwrap();

        assert!(matches!(
            SocketGuard::ensure(socket_path.clone()).unwrap(),
            EnsureSocket::Existing(_)
        ));
        assert!(socket_path.exists());
    }

    #[test]
    fn ensure_removes_stale_socket_before_start() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        drop(StdUnixListener::bind(&socket_path).unwrap());

        let outcome = SocketGuard::ensure(socket_path.clone()).unwrap();

        assert!(matches!(outcome, EnsureSocket::Start(_)));
        assert!(!socket_path.exists());
    }

    #[test]
    fn old_guard_shutdown_preserves_replacement_socket() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let mut old_guard =
            SocketGuard::new(Some(socket_path.clone()), 1234, "unused".to_string()).unwrap();
        let old_listener = old_guard.bind_listener().unwrap();
        drop(old_listener);

        let mut replacement_guard = match SocketGuard::ensure(socket_path.clone()).unwrap() {
            EnsureSocket::Existing(_) => panic!("stale socket should require a replacement"),
            EnsureSocket::Start(guard) => guard,
        };
        let replacement_listener = replacement_guard.bind_listener().unwrap();

        drop(old_guard);

        assert!(socket_path.exists());
        let client = UnixStream::connect(&socket_path).unwrap();
        let (connection, _) = replacement_listener.accept().unwrap();
        drop(connection);
        drop(client);
    }

    #[test]
    fn ensure_preserves_non_socket_entry() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        fs::write(&socket_path, b"keep me").unwrap();

        let result = SocketGuard::ensure(socket_path.clone());

        assert!(result.is_err());
        assert_eq!(fs::read(&socket_path).unwrap(), b"keep me");
    }

    #[test]
    fn ensure_keeps_private_startup_lock_file() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let lock_path = startup_lock_path(&socket_path);

        let outcome = SocketGuard::ensure(socket_path.clone()).unwrap();
        assert!(matches!(outcome, EnsureSocket::Start(_)));
        let mode = fs::metadata(&lock_path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, STARTUP_LOCK_MODE);

        drop(outcome);
        assert!(lock_path.exists());
    }

    #[test]
    fn startup_lock_excludes_competing_starter() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");
        let lock_path = startup_lock_path(&socket_path);
        let startup_lock = SocketStartupLock::acquire(&socket_path).unwrap();
        let competing_file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&lock_path)
            .unwrap();

        // SAFETY: `competing_file` is open and both flags are valid for flock.
        let result =
            unsafe { libc::flock(competing_file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        assert_eq!(result, -1);
        assert_eq!(io::Error::last_os_error().kind(), ErrorKind::WouldBlock);

        drop(startup_lock);
        // SAFETY: `competing_file` remains open and both flags are valid for flock.
        let result =
            unsafe { libc::flock(competing_file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        assert_eq!(result, 0);
    }

    #[test]
    fn explicit_socket_guard_rejects_shared_parent_directory() {
        let temp = TestDir::new();
        let shared_dir = temp.path().join("shared");
        fs::create_dir_all(&shared_dir).unwrap();
        fs::set_permissions(&shared_dir, Permissions::from_mode(0o777)).unwrap();
        let socket_path = shared_dir.join("agent.sock");

        let mut guard = SocketGuard::new(Some(socket_path), 1234, "unused".to_string()).unwrap();
        let err = guard.bind_listener().unwrap_err();

        assert!(err.to_string().contains("non-private directory"));
    }
}
