//! The arguments a browser starts its native messaging host with. They name
//! the extension that asked for the host.
use keyguard_cli_protocol::browser::{is_chromium_origin, is_firefox_extension_id};
use std::{ffi::OsString, path::Path};

/// The origin of the extension, or `None` when the arguments are not those
/// of a browser:
///
/// - Chromium browsers pass `chrome-extension://<id>/`. On Windows a
///   `--parent-window=<handle>` follows; flags after the origin are ignored.
/// - Firefox passes the absolute path of the host's manifest, then the
///   add-on ID.
pub(crate) fn extension_origin(args: impl IntoIterator<Item = OsString>) -> Option<String> {
    let mut args = args
        .into_iter()
        .map(OsString::into_string)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    let origin = match args.as_slice() {
        [origin, flags @ ..]
            if is_chromium_origin(origin) && flags.iter().all(|flag| flag.starts_with("--")) =>
        {
            0
        }
        [manifest, id] if Path::new(manifest).is_absolute() && is_firefox_extension_id(id) => 1,
        _ => return None,
    };
    Some(args.swap_remove(origin))
}

#[cfg(test)]
mod tests {
    use super::*;

    const CHROME: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
    const FIREFOX: &str = "keyguard@artemchep.com";
    /// An absolute path of the manifest on this platform.
    const MANIFEST: &str = if cfg!(windows) {
        r"C:\Users\me\AppData\Roaming\Keyguard\com.artemchep.keyguard.firefox.json"
    } else {
        "/home/me/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json"
    };

    fn origin(args: &[&str]) -> Option<String> {
        extension_origin(args.iter().map(OsString::from))
    }

    #[test]
    fn chromium_passes_the_origin_first() {
        assert_eq!(origin(&[CHROME]).as_deref(), Some(CHROME));
        // Windows adds the browser window.
        assert_eq!(
            origin(&[CHROME, "--parent-window=0"]).as_deref(),
            Some(CHROME)
        );
        assert_eq!(
            origin(&[CHROME, "--parent-window=1234", "--future-flag"]).as_deref(),
            Some(CHROME)
        );
    }

    #[test]
    fn firefox_passes_the_manifest_then_the_add_on_id() {
        assert_eq!(origin(&[MANIFEST, FIREFOX]).as_deref(), Some(FIREFOX));
        let guid = "{8a3b1c2d-4e5f-6a7b-8c9d-0e1f2a3b4c5d}";
        assert_eq!(origin(&[MANIFEST, guid]).as_deref(), Some(guid));
    }

    #[test]
    fn other_arguments_are_refused() {
        for args in [
            &[][..],
            &[""],
            &["--help"],
            &[FIREFOX],
            // Not an extension origin.
            &["chrome-extension://abcdefghijklmnopabcdefghijklmnop"],
            &["chrome-extension://ABCDEFGHIJKLMNOPABCDEFGHIJKLMNOP/"],
            &["chrome-extension://x/", "--parent-window=1"],
            // Read as a Chromium origin, never as a Firefox ID.
            &["chrome-extension:abcdefghijklmnopabcdefghijklmnop"],
            // A Chromium origin with an argument that is not a flag.
            &[CHROME, "extra"],
            // Firefox without the absolute path of its manifest.
            &["", FIREFOX],
            &["manifest.json", FIREFOX],
            &["--help", "--version"],
            // Firefox with an invalid add-on ID.
            &[MANIFEST, ""],
            &[MANIFEST, "with space@example.com"],
            &[MANIFEST, "moz-extension://8a3b1c2d/"],
            &[MANIFEST, CHROME],
            &[MANIFEST, FIREFOX, "extra"],
        ] {
            assert_eq!(origin(args), None, "{args:?}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn arguments_that_are_not_unicode_are_refused() {
        use std::os::unix::ffi::OsStringExt;
        let args = [
            OsString::from("manifest.json"),
            OsString::from_vec(vec![0xff]),
        ];
        assert_eq!(extension_origin(args), None);
    }
}
