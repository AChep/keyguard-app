//! The Keyguard-managed GnuPG home inside Termux.
//!
//! Like the desktop helper, the agent serves a separate home rather than
//! `~/.gnupg`: GnuPG's own agent keeps working there, and `no-autostart` stops
//! `gpg` from starting a real agent in this home while the helper is down.

use anyhow::{bail, ensure, Context, Result};
use keyguard_agent_identity::socket_lifecycle::{current_euid, SocketLifecycle};
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

const LIFECYCLE: SocketLifecycle = SocketLifecycle::new("GnuPG home");
const FILE_MODE: u32 = 0o600;
const NO_AUTOSTART: &str = "no-autostart";
const MAX_SYMLINKS: u32 = 40;

/// A GnuPG home and the directories the helper may restrict to the owner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ManagedHome {
    pub(crate) path: PathBuf,
    owned_directories: Vec<PathBuf>,
}

impl ManagedHome {
    /// `~/.keyguard/gnupg`, matching the macOS managed home.
    pub(crate) fn default_for(user_home: &Path) -> Self {
        let keyguard_directory = user_home.join(".keyguard");
        let path = keyguard_directory.join("gnupg");
        Self {
            owned_directories: vec![keyguard_directory, path.clone()],
            path,
        }
    }

    /// A home chosen with `--homedir`; its ancestors are left as they are.
    pub(crate) fn explicit(path: PathBuf) -> Self {
        Self {
            owned_directories: vec![path.clone()],
            path,
        }
    }

    /// Creates the home with owner-only permissions and turns off agent
    /// autostart in its `common.conf`.
    pub(crate) fn prepare(&self, user_home: &Path) -> Result<()> {
        ensure!(
            self.path.is_absolute(),
            "GnuPG home must be an absolute path: {}",
            self.path.display()
        );
        let default_home = user_home.join(".gnupg");
        ensure!(
            !is_same_path(&self.path, &default_home)?,
            "Refusing to serve the default GnuPG home {}; it belongs to GnuPG's own agent",
            default_home.display()
        );
        let common_conf = self.path.join("common.conf");
        let default_common_conf = default_home.join("common.conf");
        ensure!(
            !is_same_path(&common_conf, &default_common_conf)?,
            "Refusing to modify the default GnuPG config {}",
            default_common_conf.display()
        );

        let uid = current_euid();
        if let Some(parent) = self.owned_directories.first().and_then(|dir| dir.parent()) {
            fs::create_dir_all(parent)
                .with_context(|| format!("Failed to create {}", parent.display()))?;
        }
        for directory in &self.owned_directories {
            LIFECYCLE.ensure_safe_managed_parent_dir(directory, uid)?;
        }
        ensure_no_autostart(&common_conf, uid)
    }
}

fn ensure_no_autostart(common_conf: &Path, uid: u32) -> Result<()> {
    let existing = match fs::symlink_metadata(common_conf) {
        Ok(metadata) => {
            ensure!(
                metadata.is_file(),
                "Managed GnuPG path is not a regular file: {}",
                common_conf.display()
            );
            ensure!(
                metadata.uid() == uid,
                "Managed GnuPG file is not owned by the current user: {}",
                common_conf.display()
            );
            fs::set_permissions(common_conf, fs::Permissions::from_mode(FILE_MODE))
                .with_context(|| format!("Failed to restrict {}", common_conf.display()))?;
            fs::read_to_string(common_conf)
                .with_context(|| format!("Failed to read {}", common_conf.display()))?
        }
        Err(err) if err.kind() == ErrorKind::NotFound => String::new(),
        Err(err) => {
            return Err(err).with_context(|| format!("Failed to inspect {}", common_conf.display()))
        }
    };
    if existing.lines().any(|line| line.trim() == NO_AUTOSTART) {
        return Ok(());
    }

    let separator = if existing.is_empty() || existing.ends_with('\n') {
        ""
    } else {
        "\n"
    };
    let mut file = OpenOptions::new()
        .append(true)
        .create(true)
        .mode(FILE_MODE)
        .custom_flags(libc::O_NOFOLLOW)
        .open(common_conf)
        .with_context(|| format!("Failed to open {}", common_conf.display()))?;
    writeln!(file, "{separator}{NO_AUTOSTART}")
        .with_context(|| format!("Failed to write {}", common_conf.display()))
}

/// Whether two paths name the same file, even before it exists. Matches
/// `isSameGpgPath` of the desktop helper.
fn is_same_path(path: &Path, other: &Path) -> Result<bool> {
    // Resolve aliases for comparison only, so `~/.keyguard/../.gnupg` and
    // symlinked homes are still refused.
    if resolve_for_comparison(path, MAX_SYMLINKS)? == resolve_for_comparison(other, MAX_SYMLINKS)? {
        return Ok(true);
    }
    // Hard links name one file under different paths.
    Ok(
        match (metadata_if_exists(path)?, metadata_if_exists(other)?) {
            (Some(path), Some(other)) => path.dev() == other.dev() && path.ino() == other.ino(),
            _ => false,
        },
    )
}

/// Like `fs::canonicalize`, but a missing suffix is resolved against its
/// nearest existing ancestor, as `realpath -m` does. A dangling symlink is
/// followed, as it can point at a home that does not exist yet.
fn resolve_for_comparison(path: &Path, remaining_links: u32) -> Result<PathBuf> {
    match fs::canonicalize(path) {
        Ok(resolved) => return Ok(resolved),
        Err(err) if err.kind() == ErrorKind::NotFound => {}
        Err(err) => {
            return Err(err).with_context(|| format!("Failed to resolve {}", path.display()))
        }
    }
    let parent = path
        .parent()
        .with_context(|| format!("Could not resolve GnuPG path: {}", path.display()))?;
    if fs::symlink_metadata(path).is_ok_and(|metadata| metadata.file_type().is_symlink()) {
        ensure!(
            remaining_links > 0,
            "Too many symbolic links in GnuPG path: {}",
            path.display()
        );
        let target = fs::read_link(path)
            .with_context(|| format!("Failed to read the link {}", path.display()))?;
        return resolve_for_comparison(&parent.join(target), remaining_links - 1);
    }

    // The resolved parent has no links left, so `..` applies lexically.
    let resolved_parent = resolve_for_comparison(parent, remaining_links)?;
    Ok(match path.components().next_back() {
        Some(Component::ParentDir) => resolved_parent
            .parent()
            .map_or_else(|| resolved_parent.clone(), Path::to_path_buf),
        Some(Component::Normal(name)) => resolved_parent.join(name),
        _ => resolved_parent,
    })
}

fn metadata_if_exists(path: &Path) -> Result<Option<fs::Metadata>> {
    match fs::metadata(path) {
        Ok(metadata) => Ok(Some(metadata)),
        Err(err) if err.kind() == ErrorKind::NotFound => Ok(None),
        Err(err) => Err(err).with_context(|| format!("Failed to inspect {}", path.display())),
    }
}

/// Resolves the user's home directory from `$HOME`.
pub(crate) fn user_home() -> Result<PathBuf> {
    let Some(home) = std::env::var_os("HOME").map(PathBuf::from) else {
        bail!("HOME is not set");
    };
    ensure!(
        home.is_absolute(),
        "HOME must be an absolute path: {}",
        home.display()
    );
    Ok(home)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_home_is_inside_the_keyguard_directory() {
        let home = ManagedHome::default_for(Path::new("/data/data/com.termux/files/home"));
        assert_eq!(
            home.path,
            Path::new("/data/data/com.termux/files/home/.keyguard/gnupg")
        );
    }

    #[test]
    fn prepare_creates_private_home_with_no_autostart() {
        let user_home = tempfile::tempdir().unwrap();
        let home = ManagedHome::default_for(user_home.path());

        home.prepare(user_home.path()).unwrap();
        home.prepare(user_home.path()).unwrap();

        for directory in [user_home.path().join(".keyguard"), home.path.clone()] {
            let mode = fs::metadata(&directory).unwrap().mode() & 0o777;
            assert_eq!(mode, 0o700, "{}", directory.display());
        }
        let common_conf = home.path.join("common.conf");
        assert_eq!(fs::read_to_string(&common_conf).unwrap(), "no-autostart\n");
        assert_eq!(
            fs::metadata(&common_conf).unwrap().mode() & 0o777,
            FILE_MODE
        );
    }

    #[test]
    fn prepare_keeps_existing_options() {
        let user_home = tempfile::tempdir().unwrap();
        let home = ManagedHome::explicit(user_home.path().join("gnupg"));
        fs::create_dir(&home.path).unwrap();
        fs::write(home.path.join("common.conf"), "use-keyboxd").unwrap();

        home.prepare(user_home.path()).unwrap();

        assert_eq!(
            fs::read_to_string(home.path.join("common.conf")).unwrap(),
            "use-keyboxd\nno-autostart\n"
        );
    }

    #[test]
    fn prepare_refuses_the_default_gnupg_home() {
        let user_home = tempfile::tempdir().unwrap();
        let home = ManagedHome::explicit(user_home.path().join(".gnupg"));

        let err = home.prepare(user_home.path()).unwrap_err().to_string();

        assert!(
            err.contains("Refusing to serve the default GnuPG home"),
            "{err}"
        );
    }

    #[test]
    fn prepare_refuses_aliases_of_the_default_home_before_it_exists() {
        for keyguard_exists in [true, false] {
            let user_home = tempfile::tempdir().unwrap();
            let keyguard = user_home.path().join(".keyguard");
            if keyguard_exists {
                fs::create_dir(&keyguard).unwrap();
            }
            let alias = keyguard.join("..").join(".gnupg");
            let link = user_home.path().join("gnupg");
            std::os::unix::fs::symlink(user_home.path().join(".gnupg"), &link).unwrap();

            for path in [alias, link] {
                let err = ManagedHome::explicit(path.clone())
                    .prepare(user_home.path())
                    .unwrap_err()
                    .to_string();

                let label = format!("{} keyguard_exists={keyguard_exists}", path.display());
                assert!(
                    err.contains("Refusing to serve the default GnuPG home"),
                    "{label}: {err}"
                );
                assert!(!user_home.path().join(".gnupg").exists(), "{label}");
            }
        }
    }

    #[test]
    fn prepare_refuses_a_hard_link_to_the_default_config() {
        let user_home = tempfile::tempdir().unwrap();
        let default_home = user_home.path().join(".gnupg");
        fs::create_dir(&default_home).unwrap();
        fs::write(default_home.join("common.conf"), "use-keyboxd\n").unwrap();
        let home = ManagedHome::explicit(user_home.path().join("gnupg"));
        fs::create_dir(&home.path).unwrap();
        fs::hard_link(
            default_home.join("common.conf"),
            home.path.join("common.conf"),
        )
        .unwrap();

        let err = home.prepare(user_home.path()).unwrap_err().to_string();

        assert!(
            err.contains("Refusing to modify the default GnuPG config"),
            "{err}"
        );
        assert_eq!(
            fs::read_to_string(default_home.join("common.conf")).unwrap(),
            "use-keyboxd\n"
        );
    }

    #[test]
    fn prepare_refuses_a_symlinked_home() {
        let user_home = tempfile::tempdir().unwrap();
        let target = user_home.path().join("elsewhere");
        fs::create_dir(&target).unwrap();
        let link = user_home.path().join("gnupg");
        std::os::unix::fs::symlink(&target, &link).unwrap();

        let err = ManagedHome::explicit(link)
            .prepare(user_home.path())
            .unwrap_err()
            .to_string();

        assert!(err.contains("directory is a symlink"), "{err}");
    }
}
