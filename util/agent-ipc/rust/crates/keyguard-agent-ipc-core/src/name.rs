//! Endpoint names.
//!
//! A name is `<prefix><random hex>`, or an exact name for tests. Both use only
//! `[A-Za-z0-9._-]` and are never `.` or `..`, so a name can neither leave its
//! parent directory nor the pipe namespace. Every length is checked before
//! anything is created.

use crate::{Error, Failure, Result};

/// Longest prefix, in bytes.
pub(crate) const MAX_PREFIX_BYTES: usize = 32;
/// Longest exact name, in bytes.
pub(crate) const MAX_EXACT_BYTES: usize = 64;

const OPERATION: &str = "listen";
const HEX: &[u8; 16] = b"0123456789abcdef";
const MAX_RANDOM_BYTES: usize = 16;

/// Random bytes in a Unix run directory name: 12 hex digits.
#[cfg(any(unix, test))]
const UNIX_RANDOM_BYTES: usize = 6;
/// Random bytes in a Windows pipe name: 32 hex digits.
#[cfg(any(windows, test))]
const WINDOWS_RANDOM_BYTES: usize = 16;

/// The socket's file name inside its run directory.
#[cfg(any(unix, test))]
pub(crate) const UNIX_SOCKET_FILE: &str = "i";

/// Longest socket path that `sun_path` holds with its NUL: 104 bytes on macOS.
#[cfg(all(any(unix, test), target_os = "macos"))]
const UNIX_MAX_SOCKET_PATH: usize = 103;
/// Longest socket path that `sun_path` holds with its NUL: 108 bytes on Linux.
#[cfg(all(any(unix, test), not(target_os = "macos")))]
const UNIX_MAX_SOCKET_PATH: usize = 107;

#[cfg(any(windows, test))]
const WINDOWS_PIPE_NAMESPACE: &str = r"\\.\pipe\";
/// Longest pipe name, in UTF-16 units, namespace included.
#[cfg(any(windows, test))]
const WINDOWS_MAX_PIPE_UNITS: usize = 256;

fn invalid(reason: &'static str) -> Failure {
    Failure::new(Error::InvalidArgument, OPERATION, reason)
}

/// `[A-Za-z0-9._-]*`, and neither `.` nor `..`.
fn is_portable(value: &str) -> bool {
    value != "."
        && value != ".."
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// A validated naming rule shared by both platforms.
#[derive(Clone, Copy)]
struct Spec<'a> {
    prefix: &'a str,
    exact: Option<&'a str>,
    random_bytes: usize,
}

impl<'a> Spec<'a> {
    fn new(prefix: &'a str, exact: Option<&'a str>, random_bytes: usize) -> Result<Self> {
        debug_assert!(random_bytes <= MAX_RANDOM_BYTES);
        if prefix.len() > MAX_PREFIX_BYTES || !is_portable(prefix) {
            return Err(invalid("invalid_prefix"));
        }
        if let Some(exact) = exact
            && (exact.is_empty() || exact.len() > MAX_EXACT_BYTES || !is_portable(exact))
        {
            return Err(invalid("invalid_name"));
        }
        Ok(Self {
            prefix,
            exact,
            random_bytes,
        })
    }

    /// The byte length of every candidate.
    fn len(&self) -> usize {
        self.exact
            .map_or(self.prefix.len() + 2 * self.random_bytes, str::len)
    }

    /// The exact name, or the prefix followed by fresh random hex.
    fn candidate(&self) -> Result<String> {
        if let Some(exact) = self.exact {
            return Ok(exact.to_owned());
        }
        let mut random = [0_u8; MAX_RANDOM_BYTES];
        let random = &mut random[..self.random_bytes];
        getrandom::fill(random).map_err(|error| {
            let failure = Failure::new(Error::Io, OPERATION, "random");
            match error.raw_os_error() {
                Some(os_code) => failure.with_os_code(os_code),
                None => failure,
            }
        })?;
        let mut name = String::with_capacity(self.len());
        name.push_str(self.prefix);
        for byte in random.iter() {
            name.push(char::from(HEX[usize::from(byte >> 4)]));
            name.push(char::from(HEX[usize::from(byte & 0xf)]));
        }
        Ok(name)
    }
}

/// A validated `<parent>/<name>/i` socket location.
#[cfg(any(unix, test))]
pub(crate) struct UnixName<'a> {
    parent: &'a str,
    spec: Spec<'a>,
}

#[cfg(any(unix, test))]
impl<'a> UnixName<'a> {
    /// `parent` is absolute and NUL-free; the whole socket path fits `sun_path`.
    pub(crate) fn new(parent: &'a str, prefix: &'a str, exact: Option<&'a str>) -> Result<Self> {
        Self::with_limit(parent, prefix, exact, UNIX_MAX_SOCKET_PATH)
    }

    fn with_limit(
        parent: &'a str,
        prefix: &'a str,
        exact: Option<&'a str>,
        max_path: usize,
    ) -> Result<Self> {
        if !parent.starts_with('/') || parent.contains('\0') {
            return Err(invalid("invalid_parent"));
        }
        let spec = Spec::new(prefix, exact, UNIX_RANDOM_BYTES)?;
        // `<parent>/<name>/i`
        let path = parent.len() + 1 + spec.len() + 1 + UNIX_SOCKET_FILE.len();
        if path > max_path {
            return Err(invalid("path_too_long"));
        }
        Ok(Self { parent, spec })
    }

    /// The existing directory that receives the run directory.
    pub(crate) fn parent(&self) -> &'a str {
        self.parent
    }

    /// An exact name is created once; a random one may be retried with a new candidate.
    pub(crate) fn is_exact(&self) -> bool {
        self.spec.exact.is_some()
    }

    /// The run directory's name: the exact name, or new random hex on every call.
    pub(crate) fn directory(&self) -> Result<String> {
        self.spec.candidate()
    }

    /// `<parent>/<directory>/i`, within the platform's limit for a [`Self::directory`] name.
    pub(crate) fn socket_path(&self, directory: &str) -> String {
        format!("{}/{directory}/{UNIX_SOCKET_FILE}", self.parent)
    }
}

/// A validated `\\.\pipe\<name>` pipe name.
#[cfg(any(windows, test))]
pub(crate) struct WindowsName<'a> {
    spec: Spec<'a>,
}

#[cfg(any(windows, test))]
impl<'a> WindowsName<'a> {
    pub(crate) fn new(prefix: &'a str, exact: Option<&'a str>) -> Result<Self> {
        let spec = Spec::new(prefix, exact, WINDOWS_RANDOM_BYTES)?;
        // Names are ASCII: one UTF-16 unit per byte.
        if WINDOWS_PIPE_NAMESPACE.len() + spec.len() > WINDOWS_MAX_PIPE_UNITS {
            return Err(invalid("path_too_long"));
        }
        Ok(Self { spec })
    }

    /// The full pipe name: the exact name, or new random hex on every call.
    pub(crate) fn pipe_name(&self) -> Result<String> {
        Ok(format!(
            "{WINDOWS_PIPE_NAMESPACE}{}",
            self.spec.candidate()?
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reason<T>(result: Result<T>) -> &'static str {
        match result {
            Ok(_) => "ok",
            Err(failure) => {
                assert_eq!(failure.kind(), Error::InvalidArgument);
                assert_eq!(failure.operation(), "listen");
                failure.reason()
            }
        }
    }

    fn is_lower_hex(value: &str) -> bool {
        value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    }

    #[test]
    fn prefixes_are_short_portable_and_never_dot_names() {
        let longest = "p".repeat(MAX_PREFIX_BYTES);
        for prefix in ["", "keyguard-ssh-", "a.b_c-D9", longest.as_str()] {
            assert_eq!(reason(WindowsName::new(prefix, None)), "ok", "{prefix}");
        }
        let longer = "p".repeat(MAX_PREFIX_BYTES + 1);
        for prefix in [".", "..", "a/b", "a\\b", "a b", "é", "a\0", longer.as_str()] {
            assert_eq!(reason(WindowsName::new(prefix, None)), "invalid_prefix");
            assert_eq!(
                reason(UnixName::new("/tmp", prefix, None)),
                "invalid_prefix"
            );
        }
    }

    #[test]
    fn exact_names_are_short_portable_and_never_dot_names() {
        let longest = "n".repeat(MAX_EXACT_BYTES);
        for exact in ["ipc", "...", "kg-sshe2e-1-ipc", longest.as_str()] {
            assert_eq!(reason(WindowsName::new("", Some(exact))), "ok", "{exact}");
        }
        let longer = "n".repeat(MAX_EXACT_BYTES + 1);
        for exact in ["", ".", "..", "a/b", ":", "a\0", longer.as_str()] {
            assert_eq!(reason(WindowsName::new("", Some(exact))), "invalid_name");
            assert_eq!(
                reason(UnixName::new("/tmp", "", Some(exact))),
                "invalid_name"
            );
        }
    }

    #[test]
    fn unix_parents_are_absolute_without_nul() {
        for parent in ["", "tmp", "./tmp", "C:\\tmp", "/tmp\0/x"] {
            assert_eq!(reason(UnixName::new(parent, "", None)), "invalid_parent");
        }
        assert_eq!(reason(UnixName::new("/", "", None)), "ok");
    }

    #[test]
    fn unix_paths_are_checked_against_both_limits() {
        // `<parent>/` + prefix + 12 hex + `/i`
        for limit in [103, 107] {
            let parent = format!("/{}", "d".repeat(limit - 1 - 1 - 1 - 12 - 2));
            let fits = UnixName::with_limit(&parent, "p", None, limit).unwrap();
            assert_eq!(fits.socket_path(&fits.directory().unwrap()).len(), limit);
            assert_eq!(
                reason(UnixName::with_limit(&parent, "pp", None, limit)),
                "path_too_long"
            );
            let exact = "e".repeat(13);
            assert_eq!(
                reason(UnixName::with_limit(&parent, "", Some(&exact), limit)),
                "ok"
            );
            let exact = "e".repeat(14);
            assert_eq!(
                reason(UnixName::with_limit(&parent, "", Some(&exact), limit)),
                "path_too_long"
            );
        }
    }

    #[test]
    fn unix_random_names_have_12_fresh_hex_digits() {
        let name = UnixName::new("/tmp", "kg-", None).unwrap();
        assert!(!name.is_exact());
        assert_eq!(name.parent(), "/tmp");
        let first = name.directory().unwrap();
        let second = name.directory().unwrap();
        assert_ne!(first, second);
        for directory in [&first, &second] {
            let random = directory.strip_prefix("kg-").unwrap();
            assert_eq!(random.len(), 12);
            assert!(is_lower_hex(random), "{random}");
        }
        assert_eq!(name.socket_path(&first), format!("/tmp/{first}/i"));
    }

    #[test]
    fn unix_exact_names_are_used_as_given() {
        let name = UnixName::new("/run/user/501", "kg-", Some("ipc")).unwrap();
        assert!(name.is_exact());
        assert_eq!(name.directory().unwrap(), "ipc");
        assert_eq!(name.socket_path("ipc"), "/run/user/501/ipc/i");
    }

    #[test]
    fn windows_random_names_have_32_fresh_hex_digits() {
        let name = WindowsName::new("keyguard-ssh-", None).unwrap();
        let first = name.pipe_name().unwrap();
        let second = name.pipe_name().unwrap();
        assert_ne!(first, second);
        for pipe in [&first, &second] {
            let random = pipe.strip_prefix(r"\\.\pipe\keyguard-ssh-").unwrap();
            assert_eq!(random.len(), 32);
            assert!(is_lower_hex(random), "{random}");
        }
    }

    #[test]
    fn windows_exact_names_are_used_as_given_and_fit_the_limit() {
        let name = WindowsName::new("ignored-", Some("kg-sshe2e-7-ipc")).unwrap();
        assert_eq!(name.pipe_name().unwrap(), r"\\.\pipe\kg-sshe2e-7-ipc");
        let exact = "n".repeat(MAX_EXACT_BYTES);
        let longest = WindowsName::new("", Some(&exact)).unwrap();
        assert!(longest.pipe_name().unwrap().encode_utf16().count() <= WINDOWS_MAX_PIPE_UNITS);
        let prefix = "p".repeat(MAX_PREFIX_BYTES);
        let random = WindowsName::new(&prefix, None).unwrap();
        assert!(random.pipe_name().unwrap().encode_utf16().count() <= WINDOWS_MAX_PIPE_UNITS);
    }
}
