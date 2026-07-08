//! Platform-specific configuration for the GPG agent socket.

use std::path::PathBuf;

#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(crate) fn linux_fallback_gpg_agent_socket_path(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg/S.gpg-agent"))
}


#[cfg(target_os = "linux")]
const FLATPAK_APP_ID_FALLBACK: &str = "com.artemchep.keyguard";

#[cfg(target_os = "linux")]
pub(crate) fn flatpak_gpg_agent_socket_path(
    container: Option<&str>,
    runtime_dir: Option<&str>,
    flatpak_id: Option<&str>,
) -> Option<PathBuf> {
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
            .join("gnupg")
            .join("S.gpg-agent"),
    )
}

/// Returns the default path for the GPG agent socket.
pub fn default_gpg_agent_socket_path() -> PathBuf {
    #[cfg(target_os = "macos")]
    {
        let home = dirs::home_dir().expect("could not determine home directory");
        home.join("Library")
            .join("Group Containers")
            .join("com.artemchep.keyguard")
            .join("gnupg")
            .join("S.gpg-agent")
    }

    #[cfg(target_os = "linux")]
    {
        if let Some(path) = flatpak_gpg_agent_socket_path(
            std::env::var("container").ok().as_deref(),
            std::env::var("XDG_RUNTIME_DIR").ok().as_deref(),
            std::env::var("FLATPAK_ID").ok().as_deref(),
        ) {
            return path;
        }

        if let Some(runtime_dir) = dirs::runtime_dir() {
            runtime_dir.join("keyguard-gpg-agent").join("S.gpg-agent")
        } else {
            let uid = unsafe { libc::getuid() };
            linux_fallback_gpg_agent_socket_path(uid)
        }
    }

    #[cfg(target_os = "windows")]
    {
        PathBuf::from(r"\\.\pipe\keyguard-gpg-agent")
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        compile_error!("unsupported platform for GPG agent socket path");
    }
}

#[cfg(test)]
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
        assert!(
            path.ends_with("Library/Group Containers/com.artemchep.keyguard/gnupg/S.gpg-agent")
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_dev_fallback_path_matches_layout() {
        let uid = unsafe { libc::getuid() };
        assert_eq!(
            linux_fallback_gpg_agent_socket_path(uid),
            PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg/S.gpg-agent"))
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_resolves_in_shared_runtime_dir() {
        let path = flatpak_gpg_agent_socket_path(
            Some("flatpak"),
            Some("/run/user/1000"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(
            path,
            Some(PathBuf::from(
                "/run/user/1000/app/com.artemchep.keyguard/gnupg/S.gpg-agent"
            ))
        );
    }
}
