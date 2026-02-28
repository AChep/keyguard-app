//! Platform-specific configuration for the SSH agent.

use std::path::PathBuf;

#[cfg(target_os = "linux")]
pub(crate) fn linux_fallback_ssh_agent_socket_path(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}/ssh-agent.sock"))
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
