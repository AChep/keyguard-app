//! GnuPG's version check compares release numbers, not agent capabilities.
//! Keep the selected client's version separate from the Keyguard build version.

use anyhow::Result;
use tracing::warn;

pub const KEYGUARD_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Reported when the host could not detect the client version, e.g. in a sandbox.
/// GnuPG warns when the agent is older than the client, so stay above every 2.x
/// release; a GnuPG 3 client should warn until the agent is checked against it.
pub const FALLBACK_GNUPG_VERSION: &str = "2.99.0";

const MAX_VERSION_LEN: usize = 128;

pub fn parse_version(value: &str) -> Result<String> {
    let prefix = value.split(['-', '+']).next().unwrap_or_default();
    let components: Vec<_> = prefix.split('.').collect();
    anyhow::ensure!(
        value.len() <= MAX_VERSION_LEN
            && (prefix.len() == value.len() || value.len() > prefix.len() + 1)
            && components.len() == 3
            && components
                .iter()
                .all(|part| !part.is_empty() && part.bytes().all(|c| c.is_ascii_digit()))
            && value
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b".-+_".contains(&c)),
        "invalid GnuPG version"
    );
    Ok(value.to_owned())
}

/// A bad version must not stop the agent; it only affects GnuPG's version warning.
pub fn resolve_gnupg_version(value: Option<String>) -> String {
    match value.as_deref().map(parse_version) {
        Some(Ok(version)) => version,
        Some(Err(e)) => {
            warn!("ignoring --gnupg-version {value:?}: {e}");
            FALLBACK_GNUPG_VERSION.to_owned()
        }
        None => FALLBACK_GNUPG_VERSION.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_gnupg_versions_without_removing_suffixes() {
        for version in ["2.2.40", "2.5.24", "2.5.24-beta1", "2.4.8+vendor.1"] {
            assert_eq!(parse_version(version).unwrap(), version);
        }
        for version in [
            "",
            "2.5",
            "2.5.24-",
            "2..24",
            "2.5.24\n",
            "2.5.24\rOK",
            "2.5.24\0",
            "2.5.24 other",
            "２.5.24",
        ] {
            assert!(parse_version(version).is_err(), "{version:?}");
        }
        assert!(parse_version(&format!("2.5.24-{}", "a".repeat(128))).is_err());
    }

    #[test]
    fn fallback_is_a_valid_gnupg_2_version() {
        assert_eq!(
            parse_version(FALLBACK_GNUPG_VERSION).unwrap(),
            FALLBACK_GNUPG_VERSION
        );
        assert!(FALLBACK_GNUPG_VERSION.starts_with("2."));
    }

    #[test]
    fn falls_back_on_missing_or_invalid_versions() {
        assert_eq!(resolve_gnupg_version(Some("2.4.5".into())), "2.4.5");
        assert_eq!(resolve_gnupg_version(None), FALLBACK_GNUPG_VERSION);
        assert_eq!(
            resolve_gnupg_version(Some("2.4.5.1".into())),
            FALLBACK_GNUPG_VERSION
        );
    }
}
