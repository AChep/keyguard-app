use anyhow::{bail, Context, Result};
use rand::RngCore;
use std::fs;
use std::io::ErrorKind;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::os::unix::net::UnixListener as StdUnixListener;
use std::path::{Path, PathBuf};

const SOCKET_DIR_MODE: u32 = 0o700;
const SOCKET_FILE_MODE: u32 = 0o600;
const GENERATED_SOCKET_DIR_ATTEMPTS: usize = 32;
const GENERATED_SOCKET_DIR_SUFFIX_BYTES: usize = 8;

pub(crate) struct SocketGuard {
    socket_path: PathBuf,
    socket_dir: Option<PathBuf>,
    armed: bool,
}

impl SocketGuard {
    pub(crate) fn new(
        explicit_path: Option<PathBuf>,
        pid: i32,
        random_hex: String,
    ) -> Result<Self> {
        if let Some(socket_path) = explicit_path {
            return Ok(Self {
                socket_path,
                socket_dir: None,
                armed: true,
            });
        }

        let tmpdir = default_tmpdir();
        Self::new_generated(tmpdir, pid, random_hex)
    }

    fn new_generated(tmpdir: PathBuf, pid: i32, random_hex: String) -> Result<Self> {
        let socket_dir = create_generated_socket_dir(&tmpdir, random_hex)?;

        Ok(Self {
            socket_path: socket_dir.join(format!("agent.{pid}")),
            socket_dir: Some(socket_dir),
            armed: true,
        })
    }

    pub(crate) fn path(&self) -> &Path {
        &self.socket_path
    }

    pub(crate) fn bind_listener(&self) -> Result<StdUnixListener> {
        ensure_parent_directory_is_private(&self.socket_path)?;
        let listener = StdUnixListener::bind(&self.socket_path).with_context(|| {
            format!(
                "Failed to create Unix socket at {}",
                self.socket_path.display()
            )
        })?;
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
        Ok(listener)
    }

    pub(crate) fn disarm(&mut self) {
        self.armed = false;
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

    let mode = metadata.mode() & 0o777;
    let uid = metadata.uid();
    // SAFETY: `geteuid(2)` has no preconditions and simply returns the effective uid.
    let current_uid = unsafe { libc::geteuid() } as u32;
    if uid != current_uid || (mode & 0o077) != 0 {
        bail!(
            "Refusing to bind Unix socket in non-private directory {}",
            parent.display()
        );
    }

    Ok(())
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

        if let Err(err) = fs::remove_file(&self.socket_path) {
            if err.kind() != std::io::ErrorKind::NotFound {
                let _ = err;
            }
        }

        if let Some(socket_dir) = &self.socket_dir {
            if let Err(err) = fs::remove_dir(socket_dir) {
                if err.kind() != std::io::ErrorKind::NotFound {
                    let _ = err;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::Permissions;
    use std::os::unix::fs::PermissionsExt;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct TestDir {
        path: PathBuf,
    }

    impl TestDir {
        fn new() -> Self {
            let unique = format!(
                "kgs-{:x}-{:x}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
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
            let guard =
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
    fn explicit_socket_guard_preserves_parent_directory() {
        let temp = TestDir::new();
        let socket_path = temp.path().join("agent.sock");

        {
            let guard =
                SocketGuard::new(Some(socket_path.clone()), 1234, "unused".to_string()).unwrap();
            let listener = guard.bind_listener().unwrap();
            drop(listener);
        }

        assert!(!socket_path.exists());
        assert!(temp.path().exists());
    }

    #[test]
    fn explicit_socket_guard_rejects_shared_parent_directory() {
        let temp = TestDir::new();
        let shared_dir = temp.path().join("shared");
        fs::create_dir_all(&shared_dir).unwrap();
        fs::set_permissions(&shared_dir, Permissions::from_mode(0o777)).unwrap();
        let socket_path = shared_dir.join("agent.sock");

        let guard = SocketGuard::new(Some(socket_path), 1234, "unused".to_string()).unwrap();
        let err = guard.bind_listener().unwrap_err();

        assert!(err.to_string().contains("non-private directory"));
    }
}
