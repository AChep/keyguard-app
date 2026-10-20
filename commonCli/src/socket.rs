//! The socket of the CLI agent. Explicit paths also support sandboxed native macOS.
#[cfg(target_os = "macos")]
use anyhow::Context;
use anyhow::Result;
use std::path::PathBuf;

pub fn default_socket() -> Result<PathBuf> {
    #[cfg(windows)]
    {
        use sha2::{Digest, Sha256};
        let sid = keyguard_agent_identity::windows::current_user_sid_string()?;
        let suffix = hex::encode(Sha256::digest(sid.as_bytes()));
        Ok(PathBuf::from(format!(r"\\.\pipe\keyguard-cli-{suffix}")))
    }
    #[cfg(target_os = "macos")]
    return Ok(dirs::home_dir()
        .context("home directory unavailable")?
        .join(".keyguard/cli.sock"));
    #[cfg(target_os = "linux")]
    {
        // Without a runtime directory, fall back to the SSH agent's /tmp directory.
        let Some(runtime) = dirs::runtime_dir() else {
            let uid = keyguard_agent_identity::socket_lifecycle::current_uid();
            return Ok(PathBuf::from(format!("/tmp/keyguard-{uid}/cli.sock")));
        };
        if std::env::var("container").as_deref() == Ok("flatpak") {
            let app_id =
                std::env::var("FLATPAK_ID").unwrap_or_else(|_| "com.artemchep.keyguard".into());
            return Ok(runtime.join("app").join(app_id).join("cli.sock"));
        }
        Ok(runtime.join("keyguard/cli.sock"))
    }
}

/// Socket of the running Keyguard for a client: an explicit path, then
/// `KEYGUARD_CLI_SOCKET`, then the native macOS app that bundles
/// this executable, then the platform default.
pub fn client_socket(explicit: Option<PathBuf>) -> Result<PathBuf> {
    if let Some(path) = explicit {
        return Ok(path);
    }
    if let Some(path) = std::env::var_os("KEYGUARD_CLI_SOCKET").filter(|path| !path.is_empty()) {
        return Ok(path.into());
    }
    #[cfg(target_os = "macos")]
    if let Some(path) = app_group_socket() {
        return Ok(path);
    }
    default_socket()
}

/// The native macOS app records its App Group next to the bundled CLI. Its
/// agent listens inside that group's container.
#[cfg(target_os = "macos")]
fn app_group_socket() -> Option<PathBuf> {
    let executable = std::env::current_exe().ok()?.canonicalize().ok()?;
    app_group_socket_for(&executable, &dirs::home_dir()?)
}

#[cfg(target_os = "macos")]
fn app_group_socket_for(executable: &std::path::Path, home: &std::path::Path) -> Option<PathBuf> {
    let group = std::fs::read_to_string(executable.with_file_name("keyguard-app-group")).ok()?;
    let group = group.trim();
    let valid = !group.is_empty()
        && group.len() <= 255
        && group
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    valid.then(|| {
        home.join("Library/Group Containers")
            .join(group)
            .join("cli/s")
    })
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::*;

    #[test]
    fn the_native_app_group_names_the_socket() {
        let bundle = tempfile::tempdir().unwrap();
        let executable = bundle.path().join("keyguard");
        let home = std::path::Path::new("/Users/me");
        assert_eq!(app_group_socket_for(&executable, home), None);
        std::fs::write(
            bundle.path().join("keyguard-app-group"),
            "group.com.example\n",
        )
        .unwrap();
        assert_eq!(
            app_group_socket_for(&executable, home),
            Some(PathBuf::from(
                "/Users/me/Library/Group Containers/group.com.example/cli/s"
            )),
        );
        std::fs::write(bundle.path().join("keyguard-app-group"), "../escape").unwrap();
        assert_eq!(app_group_socket_for(&executable, home), None);
    }
}
