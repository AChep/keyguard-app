//! Platform-specific configuration for the GPG agent socket.

#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::path::{Path, PathBuf};
#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::process::Command;

#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(crate) fn linux_fallback_gpg_agent_socket_path(uid: libc::uid_t) -> PathBuf {
    linux_fallback_gpg_home_path(uid).join("S.gpg-agent")
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn linux_fallback_gpg_home_path(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg"))
}

#[cfg(target_os = "linux")]
const FLATPAK_APP_ID_FALLBACK: &str = "com.artemchep.keyguard";

#[cfg(target_os = "linux")]
fn linux_managed_gpg_home_path() -> PathBuf {
    if std::env::var("container").ok().as_deref() == Some("flatpak") {
        return flatpak_gpg_home_path(
            std::env::var("XDG_DATA_HOME").ok().as_deref(),
            &dirs::home_dir().expect("could not determine home directory"),
            std::env::var("FLATPAK_ID").ok().as_deref(),
        );
    }

    if let Some(runtime_dir) = dirs::runtime_dir() {
        runtime_dir.join("keyguard-gpg-agent")
    } else {
        // SAFETY: `getuid` takes no arguments, has no preconditions, and only
        // returns the real user ID of the calling process.
        let uid = unsafe { libc::getuid() };
        linux_fallback_gpg_home_path(uid)
    }
}

#[cfg(target_os = "linux")]
pub(crate) fn flatpak_gpg_home_path(
    xdg_data_home: Option<&str>,
    home: &Path,
    flatpak_id: Option<&str>,
) -> PathBuf {
    if let Some(xdg_data_home) = xdg_data_home.filter(|dir| !dir.trim().is_empty()) {
        return PathBuf::from(xdg_data_home).join("gnupg");
    }
    let app_id = flatpak_id
        .filter(|id| !id.trim().is_empty())
        .unwrap_or(FLATPAK_APP_ID_FALLBACK);
    home.join(".var")
        .join("app")
        .join(app_id)
        .join("data")
        .join("gnupg")
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn gpgconf_agent_socket_path(home: &Path) -> Option<PathBuf> {
    let output = Command::new("gpgconf")
        .arg("--homedir")
        .arg(home)
        .arg("--list-dirs")
        .arg("agent-socket")
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let stdout = String::from_utf8(output.stdout).ok()?;
    let socket = parse_gpgconf_agent_socket(&stdout)?;
    prepare_gpgconf_socket_directory(home, &socket);
    Some(socket)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn parse_gpgconf_agent_socket(stdout: &str) -> Option<PathBuf> {
    stdout
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(|line| line.strip_prefix("agent-socket:").unwrap_or(line))
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn prepare_gpgconf_socket_directory(home: &Path, socket: &Path) {
    if socket.starts_with(home) {
        return;
    }

    let _ = Command::new("gpgconf")
        .arg("--homedir")
        .arg(home)
        .arg("--create-socketdir")
        .output();
}

/// Returns the default path for the GPG agent socket.
#[cfg(unix)]
pub fn default_gpg_agent_socket_path() -> PathBuf {
    #[cfg(target_os = "macos")]
    {
        let home = dirs::home_dir().expect("could not determine home directory");
        let gpg_home = home
            .join("Library")
            .join("Group Containers")
            .join("com.artemchep.keyguard")
            .join("gnupg");
        gpgconf_agent_socket_path(&gpg_home).unwrap_or_else(|| gpg_home.join("S.gpg-agent"))
    }

    #[cfg(target_os = "linux")]
    {
        let gpg_home = linux_managed_gpg_home_path();
        gpgconf_agent_socket_path(&gpg_home).unwrap_or_else(|| gpg_home.join("S.gpg-agent"))
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn default_socket_path_is_absolute() {
        let path = default_gpg_agent_socket_path();
        assert!(path.is_absolute(), "path={}", path.display());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_default_path_uses_group_container() {
        let path = default_gpg_agent_socket_path();
        assert!(path.ends_with("Library/Group Containers/com.artemchep.keyguard/gnupg/S.gpg-agent"));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_dev_fallback_path_matches_layout() {
        // SAFETY: `getuid` takes no arguments, has no preconditions, and only
        // returns the real user ID of the calling process.
        let uid = unsafe { libc::getuid() };
        assert_eq!(
            linux_fallback_gpg_agent_socket_path(uid),
            PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg/S.gpg-agent"))
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_uses_persistent_app_data_dir() {
        let path = flatpak_gpg_home_path(
            None,
            Path::new("/home/alice"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(
            path,
            PathBuf::from("/home/alice/.var/app/com.artemchep.keyguard/data/gnupg")
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_prefers_xdg_data_home() {
        let path = flatpak_gpg_home_path(
            Some("/home/alice/.var/app/com.artemchep.keyguard/data"),
            Path::new("/home/alice"),
            Some("com.example.Other"),
        );
        assert_eq!(
            path,
            PathBuf::from("/home/alice/.var/app/com.artemchep.keyguard/data/gnupg")
        );
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn gpgconf_agent_socket_parser_accepts_plain_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("/run/user/1000/gnupg/d.abc/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/d.abc/S.gpg-agent"))
        );
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn gpgconf_agent_socket_parser_accepts_keyed_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("agent-socket:/run/user/1000/gnupg/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/S.gpg-agent"))
        );
    }
}
