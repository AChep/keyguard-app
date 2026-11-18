//! The stream of a browser extension. Its connector, `keyguard-browser-host`,
//! opens one with a `browser` frame. Once the agent answers `{"ok":true}`, the
//! connection carries opaque frames of at most
//! [`BROWSER_MAX_FRAME`](crate::frame::BROWSER_MAX_FRAME) bytes in both
//! directions, until either side closes it.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

/// The connector's executable, without the `.exe` of Windows. It is
/// bundled next to the CLI agent.
pub const HOST_EXECUTABLE: &str = "keyguard-browser-host";

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
