//! The stream of a browser extension. Its connector, `keyguard-browser-host`,
//! opens one with a `browser` frame. Once the agent answers `{"ok":true}`, the
//! connection carries opaque frames of at most
//! [`BROWSER_MAX_FRAME`](crate::frame::BROWSER_MAX_FRAME) bytes in both
//! directions, until either side closes it.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// The connector's executable, without the `.exe` of Windows. It is
/// bundled next to the CLI agent.
pub const HOST_EXECUTABLE: &str = "keyguard-browser-host";

/// The CLI agent's socket next to a copy of the connector. A browser's
/// Flatpak sandbox sees only the browser's own folder, so the agent listens
/// next to the copy there.
pub const HOST_SOCKET: &str = "keyguard-browser-host.sock";

/// A browser of Flathub. Its sandbox sees only its own folder,
/// `~/.var/app/<app ID>`, of the user's files.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FlatpakBrowser {
    pub app_id: &'static str,
    /// Where in that folder it reads the manifests of native messaging hosts.
    pub manifests: &'static [&'static str],
}

impl FlatpakBrowser {
    /// The browser's folder, which Flatpak creates on its first run.
    pub fn folder(&self, home: &Path) -> PathBuf {
        home.join(".var").join("app").join(self.app_id)
    }

    /// The folder of its manifests. It also holds the copy of the connector
    /// and [`HOST_SOCKET`].
    pub fn manifests(&self, home: &Path) -> PathBuf {
        self.manifests
            .iter()
            .fold(self.folder(home), |path, part| path.join(part))
    }
}

/// The browsers of Flathub. Chromium browsers read manifests from their
/// folder in `XDG_CONFIG_HOME`, which Flatpak sets to `config`; Firefox from
/// `~/.mozilla`, which its Flatpak keeps in `.mozilla`.
pub mod flatpak {
    use super::FlatpakBrowser;

    pub const CHROME: FlatpakBrowser = FlatpakBrowser {
        app_id: "com.google.Chrome",
        manifests: &["config", "google-chrome", "NativeMessagingHosts"],
    };
    pub const CHROMIUM: FlatpakBrowser = FlatpakBrowser {
        app_id: "org.chromium.Chromium",
        manifests: &["config", "chromium", "NativeMessagingHosts"],
    };
    pub const EDGE: FlatpakBrowser = FlatpakBrowser {
        app_id: "com.microsoft.Edge",
        manifests: &["config", "microsoft-edge", "NativeMessagingHosts"],
    };
    pub const BRAVE: FlatpakBrowser = FlatpakBrowser {
        app_id: "com.brave.Browser",
        manifests: &[
            "config",
            "BraveSoftware",
            "Brave-Browser",
            "NativeMessagingHosts",
        ],
    };
    pub const FIREFOX: FlatpakBrowser = FlatpakBrowser {
        app_id: "org.mozilla.firefox",
        manifests: &[".mozilla", "native-messaging-hosts"],
    };

    pub const ALL: [FlatpakBrowser; 5] = [CHROME, CHROMIUM, EDGE, BRAVE, FIREFOX];
}

/// Bounds the ID of a Firefox extension, in bytes.
const MAX_FIREFOX_EXTENSION_ID: usize = 255;

/// How the origin of a Chromium extension starts.
pub const CHROMIUM_ORIGIN_PREFIX: &str = "chrome-extension://";
const CHROME_EXTENSION_ID_LENGTH: usize = 32;

/// Opens the stream of the extension of `origin`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct BrowserRequest {
    /// The extension as the browser names it to its connector:
    /// `chrome-extension://<id>/` for Chromium browsers, or the ID of a
    /// Firefox extension.
    pub origin: String,
    /// The revision of the connector, so that the app can ask for an update.
    pub host_revision: u32,
}

impl BrowserRequest {
    pub fn validate(&self) -> Result<()> {
        ensure!(is_extension_origin(&self.origin), "invalid_arguments");
        Ok(())
    }
}

/// The scheme of Chromium extensions. A string that starts with it is only
/// ever read as a Chromium origin, never as a Firefox ID.
const CHROMIUM_SCHEME: &str = "chrome-extension:";

/// A Chromium origin or a Firefox extension ID, see [`is_chromium_origin`]
/// and [`is_firefox_extension_id`].
pub fn is_extension_origin(origin: &str) -> bool {
    is_chromium_origin(origin) || is_firefox_extension_id(origin)
}

/// `chrome-extension://` with 32 letters `a` to `p`, then `/`.
pub fn is_chromium_origin(origin: &str) -> bool {
    let Some(id) = origin
        .strip_prefix(CHROMIUM_ORIGIN_PREFIX)
        .and_then(|rest| rest.strip_suffix('/'))
    else {
        return false;
    };
    id.len() == CHROME_EXTENSION_ID_LENGTH && id.bytes().all(|byte| (b'a'..=b'p').contains(&byte))
}

/// The ID of a Firefox extension: printable ASCII without spaces or `/`, and
/// nothing that starts as a Chromium origin.
pub fn is_firefox_extension_id(id: &str) -> bool {
    !id.starts_with(CHROMIUM_SCHEME)
        && (1..=MAX_FIREFOX_EXTENSION_ID).contains(&id.len())
        && id
            .bytes()
            .all(|byte| byte.is_ascii_graphic() && byte != b'/')
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const CHROME: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";

    #[test]
    fn chromium_origins_and_firefox_ids_are_valid() {
        for origin in [
            CHROME,
            "keyguard@artemchep.com",
            "{8a3b1c2d-4e5f-6a7b-8c9d-0e1f2a3b4c5d}",
            &"a".repeat(MAX_FIREFOX_EXTENSION_ID),
        ] {
            assert!(is_extension_origin(origin), "{origin}");
        }
    }

    #[test]
    fn other_origins_are_invalid() {
        for origin in [
            "",
            // Not a Chromium extension ID: too short, too long, outside a-p,
            // upper case, or without its trailing slash.
            "chrome-extension://abcdefghijklmnopabcdefghijklmno/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnopa/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnoq/",
            "chrome-extension://ABCDEFGHIJKLMNOPABCDEFGHIJKLMNOP/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop/path",
            "chrome-extension://",
            // Starts as a Chromium origin, so never a Firefox ID.
            "chrome-extension:abcdefghijklmnopabcdefghijklmnop",
            // Not a Firefox extension ID.
            "moz-extension://8a3b1c2d-4e5f-6a7b-8c9d-0e1f2a3b4c5d/",
            "with space@example.com",
            "line\nbreak@example.com",
            "ünicode@example.com",
            &"a".repeat(MAX_FIREFOX_EXTENSION_ID + 1),
        ] {
            assert!(!is_extension_origin(origin), "{origin:?}");
        }
    }

    #[test]
    fn each_origin_is_of_one_kind() {
        assert!(is_chromium_origin(CHROME) && !is_firefox_extension_id(CHROME));
        let firefox = "keyguard@artemchep.com";
        assert!(is_firefox_extension_id(firefox) && !is_chromium_origin(firefox));
    }

    #[test]
    fn flatpak_browsers_read_manifests_in_their_own_folder() {
        let home = Path::new("/home/me");
        let folders: Vec<PathBuf> = flatpak::ALL
            .iter()
            .map(|browser| browser.manifests(home))
            .collect();
        assert_eq!(
            folders,
            [
                "/home/me/.var/app/com.google.Chrome/config/google-chrome/NativeMessagingHosts",
                "/home/me/.var/app/org.chromium.Chromium/config/chromium/NativeMessagingHosts",
                "/home/me/.var/app/com.microsoft.Edge/config/microsoft-edge/NativeMessagingHosts",
                "/home/me/.var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser/NativeMessagingHosts",
                "/home/me/.var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts",
            ]
            .map(PathBuf::from)
        );
        for browser in flatpak::ALL {
            assert!(browser.manifests(home).starts_with(browser.folder(home)));
        }
    }

    #[test]
    fn requests_are_camel_case_and_reject_unknown_fields() {
        let request = BrowserRequest {
            origin: CHROME.into(),
            host_revision: 1,
        };
        assert_eq!(
            serde_json::to_value(&request).unwrap(),
            json!({"origin":CHROME,"hostRevision":1})
        );
        for value in [
            json!({"origin":CHROME,"hostRevision":1,"caller":{}}),
            json!({"origin":CHROME,"host_revision":1}),
            json!({"origin":CHROME}),
            json!({"origin":CHROME,"hostRevision":-1}),
        ] {
            assert!(
                serde_json::from_value::<BrowserRequest>(value.clone()).is_err(),
                "{value}"
            );
        }
    }
}
