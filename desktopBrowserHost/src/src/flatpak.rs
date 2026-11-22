//! The connector in the Flatpak sandbox of a browser of Flathub, which
//! reaches the agent only through the socket next to itself.
use keyguard_cli_protocol::browser::{Channel, flatpak};
use std::path::{Path, PathBuf};

/// The agent's socket for the connector at `executable` while it runs in
/// the Flatpak sandbox of a browser of Flathub, from that browser's folder
/// of manifests: the socket of the copy's channel next to it, as the copy's
/// file name tells. `flatpak_info` is the sandbox's `/.flatpak-info`, which
/// names the app that the sandbox runs. Elsewhere, the connector finds the
/// agent as the CLI does.
///
/// The path of `executable` has its links resolved, as `current_exe`
/// gives it, so the links of `home` are resolved too, such as those of a
/// `/home` that links to `/var/home`. Below the home, none may be a link.
fn flatpak_browser_socket(executable: &Path, home: &Path, flatpak_info: &str) -> Option<PathBuf> {
    let app_id = flatpak_app_id(flatpak_info)?;
    let browser = flatpak::ALL
        .into_iter()
        .find(|browser| browser.app_id == app_id)?;
    let channel = Channel::of_copy(executable.file_name()?)?;
    let home = std::fs::canonicalize(home).unwrap_or_else(|_| home.to_owned());
    let folder = browser.manifests(&home);
    (executable.parent() == Some(folder.as_path())).then(|| folder.join(channel.host_socket()))
}

/// [`flatpak_browser_socket`] for this process.
#[cfg(target_os = "linux")]
pub(crate) fn current_flatpak_browser_socket() -> Option<PathBuf> {
    // Only Flatpak's sandboxes have it, and only Flatpak writes it.
    let info = std::fs::read_to_string("/.flatpak-info").ok()?;
    let executable = std::env::current_exe().ok()?;
    flatpak_browser_socket(&executable, &dirs::home_dir()?, &info)
}

/// `name` in the `[Application]` group of `/.flatpak-info`, a key file.
fn flatpak_app_id(info: &str) -> Option<&str> {
    let mut application = false;
    for line in info.lines().map(str::trim) {
        if line.starts_with('[') {
            application = line == "[Application]";
        } else if application
            && let Some((key, value)) = line.split_once('=')
            && key.trim() == "name"
        {
            return Some(value.trim());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The release channel's copy, and its socket.
    const COPY: &str = Channel::Production.host_copy();
    const SOCKET: &str = Channel::Production.host_socket();

    #[test]
    fn only_a_copy_in_its_flatpak_browser_uses_the_socket_next_to_it() {
        let home = Path::new("/home/me");
        let firefox = flatpak::FIREFOX.manifests(home);
        let copy = firefox.join(COPY);
        let info = "[Application]\nname=org.mozilla.firefox\nruntime=runtime/org.freedesktop.Platform/x86_64/24.08\n\n[Instance]\ninstance-id=1\n";
        assert_eq!(
            flatpak_browser_socket(&copy, home, info),
            Some(firefox.join(SOCKET))
        );
        // The copy of a development build finds its own agent.
        assert_eq!(
            flatpak_browser_socket(&firefox.join("keyguard-browser-host-dev"), home, info),
            Some(firefox.join("keyguard-browser-host-dev.sock"))
        );
        // Outside a sandbox, in the sandbox of another app, or as another
        // browser's copy.
        for (executable, info) in [
            (&copy, ""),
            (&copy, "[Application]\nname=org.example.Terminal\n"),
            (&copy, "[Instance]\nname=org.mozilla.firefox\n"),
            (&flatpak::CHROME.manifests(home).join(COPY), info),
            (&firefox.join("other-host"), info),
            (&firefox.join(SOCKET), info),
            (&home.join(".local/bin").join(COPY), info),
        ] {
            assert_eq!(
                flatpak_browser_socket(executable, home, info),
                None,
                "{executable:?} {info:?}"
            );
        }
        // Key files allow spaces around `=`.
        assert_eq!(
            flatpak_app_id("[Application]\nname = com.google.Chrome\n"),
            Some("com.google.Chrome")
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_home_through_a_link_finds_the_socket_next_to_the_copy() {
        // HOME is /home/me, where /home links to /var/home.
        let root = tempfile::tempdir().unwrap();
        let resolved = std::fs::canonicalize(root.path()).unwrap();
        std::fs::create_dir_all(resolved.join("var/home/me")).unwrap();
        std::os::unix::fs::symlink(resolved.join("var/home"), root.path().join("home")).unwrap();
        let home = root.path().join("home/me");
        // The executable's path as current_exe gives it, links resolved.
        let copy = flatpak::FIREFOX
            .manifests(&resolved.join("var/home/me"))
            .join(COPY);
        let info = "[Application]\nname=org.mozilla.firefox\n";
        assert_eq!(
            flatpak_browser_socket(&copy, &home, info),
            Some(copy.with_file_name(SOCKET))
        );
        // The copy of another browser still isn't.
        let chrome = flatpak::CHROME
            .manifests(&resolved.join("var/home/me"))
            .join(COPY);
        assert_eq!(flatpak_browser_socket(&chrome, &home, info), None);
    }
}
