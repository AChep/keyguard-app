//! Platform-specific configuration for the SSH agent.

use std::path::PathBuf;

#[cfg(target_os = "linux")]
pub(crate) fn linux_fallback_ssh_agent_socket_path(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}/ssh-agent.sock"))
}

#[cfg(target_os = "linux")]
const FLATPAK_APP_ID_FALLBACK: &str = "com.artemchep.keyguard";

/// Returns the socket path to use inside a Flatpak sandbox, or `None`
/// if the agent is not sandboxed or the path can not be determined.
///
/// Inside the sandbox both `$XDG_RUNTIME_DIR` and `/tmp` are private
/// tmpfs mounts, so a socket bound there is invisible to the host.
/// `$XDG_RUNTIME_DIR/app/$FLATPAK_ID/` is the only runtime directory
/// that Flatpak bind-mounts to the host at the same path, no extra
/// sandbox permissions needed.
///
/// Keep in sync with the path shown to the user on the SSH agent
/// setup screen, see `SSH_AGENT_SETUP_LINUX_FLATPAK_SOCKET` in
/// `SshAgentSetupScreen.kt`.
///
/// Keep in sync with the `actual val CurrentPlatform: Platform` implementation.
#[cfg(target_os = "linux")]
pub(crate) fn flatpak_ssh_agent_socket_path(
    container: Option<&str>,
    runtime_dir: Option<&str>,
    flatpak_id: Option<&str>,
) -> Option<PathBuf> {
    // The 'container' environment variable is set by 'flatpak run'.
    if container != Some("flatpak") {
        return None;
    }
    let runtime_dir = runtime_dir.filter(|dir| !dir.trim().is_empty())?;
    let app_id = flatpak_id
        .filter(|id| !id.trim().is_empty())
        .unwrap_or(FLATPAK_APP_ID_FALLBACK);
    Some(
        PathBuf::from(runtime_dir)
            .join("app")
            .join(app_id)
            .join("ssh-agent.sock"),
    )
}

/// Returns the default path for the SSH agent socket.
///
/// - macOS: `~/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock`
/// - Linux: `$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock` or `/tmp/keyguard-$UID/ssh-agent.sock`
/// - Windows: `\\.\pipe\keyguard-ssh-agent`
pub fn default_ssh_agent_socket_path() -> PathBuf {
    #[cfg(target_os = "macos")]
    {
        // Use a path that's accessible and conventional for macOS apps.
        // ~/Library/Group Containers/ is writable without sandbox issues.
        let home = dirs::home_dir().expect("Could not determine home directory");
        home.join("Library")
            .join("Group Containers")
            .join("com.artemchep.keyguard")
            .join("ssh-agent.sock")
    }

    #[cfg(target_os = "linux")]
    {
        // When sandboxed, the regular locations below are not visible
        // to the host, so the Flatpak path takes priority.
        if let Some(path) = flatpak_ssh_agent_socket_path(
            std::env::var("container").ok().as_deref(),
            std::env::var("XDG_RUNTIME_DIR").ok().as_deref(),
            std::env::var("FLATPAK_ID").ok().as_deref(),
        ) {
            return path;
        }

        // Prefer XDG_RUNTIME_DIR (typically /run/user/<uid>/) for ephemeral
        // sockets. Fall back to /tmp with the UID appended for uniqueness.
        if let Some(runtime_dir) = dirs::runtime_dir() {
            runtime_dir.join("keyguard-ssh-agent.sock")
        } else {
            let uid = unsafe { libc::getuid() };
            linux_fallback_ssh_agent_socket_path(uid)
        }
    }

    #[cfg(target_os = "windows")]
    {
        // Windows OpenSSH supports named pipes via SSH_AUTH_SOCK.
        PathBuf::from(r"\\.\pipe\keyguard-ssh-agent")
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        compile_error!("Unsupported platform for SSH agent socket path");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_socket_path_is_absolute() {
        let path = default_ssh_agent_socket_path();
        assert!(
            path.is_absolute(),
            "Default socket path should be absolute, got: {}",
            path.display()
        );
    }

    #[test]
    fn default_socket_path_is_not_empty() {
        let path = default_ssh_agent_socket_path();
        assert!(
            path.to_str().map_or(false, |s| !s.is_empty()),
            "Default socket path should be non-empty"
        );
    }

    #[test]
    fn default_socket_path_contains_keyguard() {
        let path = default_ssh_agent_socket_path();
        let path_str = path.to_string_lossy();
        assert!(
            path_str.contains("keyguard"),
            "Default socket path should contain 'keyguard', got: {}",
            path_str
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_path_ends_with_sock() {
        let path = default_ssh_agent_socket_path();
        let path_str = path.to_string_lossy();
        assert!(
            path_str.ends_with(".sock"),
            "macOS socket path should end with .sock, got: {}",
            path_str
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_path_ends_with_sock() {
        let path = default_ssh_agent_socket_path();
        let path_str = path.to_string_lossy();
        assert!(
            path_str.ends_with(".sock"),
            "Linux socket path should end with .sock, got: {}",
            path_str
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_resolves_in_the_shared_runtime_dir() {
        let path = flatpak_ssh_agent_socket_path(
            Some("flatpak"),
            Some("/run/user/1000"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(
            path,
            Some(PathBuf::from(
                "/run/user/1000/app/com.artemchep.keyguard/ssh-agent.sock"
            ))
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_falls_back_to_the_default_app_id() {
        let path = flatpak_ssh_agent_socket_path(Some("flatpak"), Some("/run/user/1000"), None);
        assert_eq!(
            path,
            Some(PathBuf::from(
                "/run/user/1000/app/com.artemchep.keyguard/ssh-agent.sock"
            ))
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_is_none_outside_of_a_flatpak() {
        let path = flatpak_ssh_agent_socket_path(
            None,
            Some("/run/user/1000"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(path, None);

        let path = flatpak_ssh_agent_socket_path(
            Some("docker"),
            Some("/run/user/1000"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(path, None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_is_none_without_a_runtime_dir() {
        let path = flatpak_ssh_agent_socket_path(Some("flatpak"), None, None);
        assert_eq!(path, None);

        let path = flatpak_ssh_agent_socket_path(Some("flatpak"), Some(" "), None);
        assert_eq!(path, None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_fallback_path_matches_expected_layout() {
        let uid = unsafe { libc::getuid() };
        let path = linux_fallback_ssh_agent_socket_path(uid);
        assert_eq!(
            path,
            PathBuf::from(format!("/tmp/keyguard-{uid}/ssh-agent.sock"))
        );
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_path_is_named_pipe() {
        let path = default_ssh_agent_socket_path();
        let path_str = path.to_string_lossy();
        assert!(
            path_str.starts_with(r"\\.\pipe\"),
            "Windows socket path should be a named pipe, got: {}",
            path_str
        );
    }
}
