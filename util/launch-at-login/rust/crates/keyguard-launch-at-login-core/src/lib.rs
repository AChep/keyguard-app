//! Desktop launch-at-login registration; the OS is the source of truth for status.
use keyguard_ffi::PanicHook;
use std::cell::RefCell;
use std::fmt;

#[cfg(target_os = "macos")]
mod macos;
#[cfg(any(target_os = "linux", all(unix, test)))]
mod portal;
#[cfg(windows)]
mod windows;
#[cfg(any(windows, test))]
mod windows_command;
#[cfg(any(windows, test))]
mod windows_startup;
#[cfg(any(target_os = "linux", test))]
mod xdg;

/// Stable native bridge version.
pub const ABI_VERSION: u32 = 1;
/// Maximum UTF-8 request size.
pub const MAX_REQUEST: usize = 65_536;

/// Effective state, when the operating system can report it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Status {
    /// Registered and authorized.
    Enabled = 0,
    /// Not registered.
    Disabled = 1,
    /// System approval is required.
    RequiresApproval = 2,
    /// Disabled outside the app; do not override it.
    DisabledByUser = 3,
    /// Unsupported target or OS.
    Unavailable = 4,
    /// Registration refers to an outdated launcher.
    Stale = 5,
    /// No supported effective-state query exists.
    Unknown = 6,
    /// Enabled by system policy; the app cannot change it.
    EnabledByPolicy = 7,
    /// Disabled by system policy; the app cannot change it.
    DisabledByPolicy = 8,
}

/// Stable failure categories.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Error {
    /// Malformed configuration.
    InvalidArgument = -1,
    /// Unsupported operation.
    Unavailable = -2,
    /// Operating-system failure.
    System = -3,
    /// User cancelled a request.
    Cancelled = -4,
    /// User or OS denied permission.
    Denied = -5,
    /// An entry with another owner already exists.
    Conflict = -6,
    /// An unexpected internal failure.
    Internal = -7,
}

/// Structured error, with a diagnostic for the calling application's error UI.
#[derive(Debug)]
pub struct Failure {
    /// Stable category.
    pub code: Error,
    /// Platform diagnostic.
    pub message: String,
}
impl Failure {
    pub(crate) fn new(code: Error, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}
impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for Failure {}
impl From<std::io::Error> for Failure {
    fn from(error: std::io::Error) -> Self {
        Self::new(Error::System, error.to_string())
    }
}

#[derive(Debug)]
struct Target<'a> {
    kind: &'a str,
    id: &'a str,
    name: &'a str,
    executable: &'a str,
    /// The MSIX `StartupTask` ID; only a Windows target has one.
    task_id: &'a str,
    arguments: Vec<&'a str>,
}
impl<'a> Target<'a> {
    fn parse(request: &'a str) -> Result<Self, Failure> {
        if request.len() > MAX_REQUEST {
            return Err(Failure::new(Error::InvalidArgument, "request too large"));
        }
        // Every kind sends the same header: kind, ID, name, executable and the Windows
        // startup task ID, which is empty for other kinds. Arguments follow it.
        const HEADER: usize = 5;
        let fields: Vec<_> = request.split('\0').collect();
        if !(HEADER..=68).contains(&fields.len()) {
            return Err(Failure::new(Error::InvalidArgument, "invalid target"));
        }
        let target = Self {
            kind: fields[0],
            id: fields[1],
            name: fields[2],
            executable: fields[3],
            task_id: fields[4],
            arguments: fields[HEADER..].to_vec(),
        };
        let windows = target.kind == "windows";
        if !windows && !target.task_id.is_empty() {
            return Err(Failure::new(
                Error::InvalidArgument,
                "only a Windows target has a startup task",
            ));
        }
        match target.kind {
            "macos" if fields.len() == HEADER => {}
            "flatpak" if !target.arguments.is_empty() && !target.arguments[0].is_empty() => {}
            "windows" | "xdg"
                if valid_id(target.id)
                    && (!windows || valid_id(target.task_id))
                    && !target.name.is_empty()
                    && !target.executable.is_empty()
                    && !target.name.contains(['\r', '\n'])
                    && !target.executable.contains(['\r', '\n']) => {}
            _ => {
                return Err(Failure::new(
                    Error::InvalidArgument,
                    "invalid launch target",
                ));
            }
        }
        Ok(target)
    }
}
/// An owned entry is stale only when it launches a missing executable, or this
/// executable with other arguments. Another existing install keeps its entry.
/// An executable that cannot be inspected is `Unknown`, so the app repairs nothing.
#[cfg(any(windows, target_os = "linux", test))]
fn registered_status(
    stored_executable: &std::path::Path,
    same_executable: bool,
    same_arguments: bool,
) -> Status {
    let missing = stored_executable.as_os_str().is_empty()
        || match std::fs::metadata(stored_executable) {
            Ok(metadata) => !metadata.is_file(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
            Err(_) => return Status::Unknown,
        };
    if missing || (same_executable && !same_arguments) {
        Status::Stale
    } else {
        Status::Enabled
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 200
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-')
}
thread_local! { static LAST_ERROR: RefCell<Option<String>> = const { RefCell::new(None) }; }
/// Retrieves this thread's most recent operation failure.
pub fn last_error() -> Option<String> {
    LAST_ERROR.with(|value| value.borrow().clone())
}
/// Records malformed ABI arguments.
pub fn invalid_argument() -> i32 {
    record_failure(Failure::new(
        Error::InvalidArgument,
        "invalid bridge argument",
    ))
}
fn record_failure(failure: Failure) -> i32 {
    let code = failure.code as i32;
    LAST_ERROR.with(|value| *value.borrow_mut() = Some(failure.message));
    code
}
/// Registration operations, decoded once from the bridge's wire codes 0–3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum Operation {
    /// Queries the effective state.
    Status = 0,
    /// Registers the target.
    Enable = 1,
    /// Removes the registration.
    Disable = 2,
    /// Opens the OS login-items settings.
    Settings = 3,
}

/// Every bridge operation, decoded from its stable wire code. Code 4 is retired.
enum Command {
    Register(Operation),
    LaunchedAtLogin,
    PrepareLaunchDetection,
}
impl Command {
    fn from_code(code: i32) -> Option<Self> {
        Some(match code {
            0 => Self::Register(Operation::Status),
            1 => Self::Register(Operation::Enable),
            2 => Self::Register(Operation::Disable),
            3 => Self::Register(Operation::Settings),
            5 => Self::LaunchedAtLogin,
            6 => Self::PrepareLaunchDetection,
            _ => return None,
        })
    }
}

/// Tests keep the default panic hook so CI logs show assertion details.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Executes an operation and contains panics at the native boundary.
/// Operations 0–3 are query, enable, disable and OS settings. 5 queries login intent and 6
/// prepares login detection; both ignore the request, which may be empty.
pub fn execute(operation: i32, request: &str) -> i32 {
    LAST_ERROR.with(|value| *value.borrow_mut() = None);
    match keyguard_ffi::contained(PANIC_HOOK, || run(operation, request)) {
        Ok(Ok(value)) => value,
        Ok(Err(failure)) => record_failure(failure),
        Err(_) => record_failure(Failure::new(
            Error::Internal,
            "native launch registration failed",
        )),
    }
}
fn run(code: i32, request: &str) -> Result<i32, Failure> {
    let command = Command::from_code(code)
        .ok_or_else(|| Failure::new(Error::InvalidArgument, "invalid operation"))?;
    match command {
        Command::Register(operation) => register(operation, request).map(|s| s as i32),
        Command::LaunchedAtLogin => {
            #[cfg(target_os = "macos")]
            {
                Ok(i32::from(macos::launched_at_login()))
            }
            #[cfg(not(target_os = "macos"))]
            {
                Ok(0)
            }
        }
        Command::PrepareLaunchDetection => {
            #[cfg(target_os = "macos")]
            macos::prepare_launch_detection();
            Ok(0)
        }
    }
}
fn register(operation: Operation, request: &str) -> Result<Status, Failure> {
    let target = Target::parse(request)?;
    #[cfg(target_os = "macos")]
    if target.kind == "macos" {
        return macos::execute(operation);
    }
    #[cfg(windows)]
    if target.kind == "windows" {
        return windows::execute(operation, &target);
    }
    #[cfg(target_os = "linux")]
    {
        if target.kind == "flatpak" {
            return portal::execute(operation, &target);
        }
        if target.kind == "xdg" {
            return xdg::execute(operation, &target);
        }
    }
    if operation == Operation::Status {
        Ok(Status::Unavailable)
    } else {
        Err(Failure::new(
            Error::Unavailable,
            "launch target is unavailable on this operating system",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_path_traversal_and_excessive_requests() {
        assert!(Target::parse("xdg\0../bad\0Name\0/bin/app\0").is_err());
        assert!(Target::parse("windows\0bad/path\0Name\0C:\\app.exe\0Task").is_err());
        assert!(Target::parse(&"x".repeat(MAX_REQUEST + 1)).is_err());
    }
    #[test]
    fn preserves_empty_and_unicode_arguments() {
        let target =
            Target::parse("xdg\0app.example\0Example\0/opt/a b/应用\0\0\0--login").unwrap();
        assert_eq!(target.task_id, "");
        assert_eq!(target.arguments, ["", "--login"]);
    }
    #[test]
    fn every_kind_sends_a_five_field_header() {
        let target =
            Target::parse("windows\0app.example\0Example\0C:\\app.exe\0Task\0--login").unwrap();
        assert_eq!(target.task_id, "Task");
        assert_eq!(target.arguments, ["--login"]);
        assert!(Target::parse("windows\0app.example\0Example\0C:\\app.exe").is_err());
        assert!(Target::parse("windows\0app.example\0Example\0C:\\app.exe\0bad task").is_err());
        assert!(Target::parse("msix\0Task\0\0\0").is_err());
        // The task ID is the fifth field on every kind, and empty outside Windows.
        assert!(Target::parse("macos\0\0\0\0").is_ok());
        assert!(Target::parse("macos\0\0\0").is_err());
        assert!(Target::parse("macos\0\0\0\0\0extra").is_err());
        assert!(Target::parse("xdg\0app.example\0Example\0/bin/app").is_err());
        assert!(Target::parse("xdg\0app.example\0Example\0/bin/app\0Task").is_err());
        let flatpak = Target::parse("flatpak\0\0Reason\0\0\0keyguard\0--x").unwrap();
        assert_eq!(flatpak.arguments, ["keyguard", "--x"]);
        assert!(Target::parse("flatpak\0\0Reason\0\0Task\0keyguard").is_err());
    }
    #[test]
    fn stale_means_a_missing_executable_or_changed_arguments_of_this_one() {
        let root = tempfile::tempdir().unwrap();
        let existing = root.path().join("launcher");
        std::fs::write(&existing, "fixture").unwrap();
        let missing = root.path().join("missing");
        assert_eq!(registered_status(&existing, true, true), Status::Enabled);
        assert_eq!(registered_status(&existing, true, false), Status::Stale);
        // Another install that still exists owns the entry.
        assert_eq!(registered_status(&existing, false, false), Status::Enabled);
        assert_eq!(registered_status(&missing, false, true), Status::Stale);
        assert_eq!(registered_status(&missing, true, true), Status::Stale);
        // An entry that exists but is no regular file launches nothing.
        assert_eq!(registered_status(root.path(), false, true), Status::Stale);
        assert_eq!(
            registered_status(std::path::Path::new(""), false, true),
            Status::Stale
        );
    }
    #[cfg(unix)]
    #[test]
    fn uninspectable_executable_is_unknown_never_stale() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let root = tempfile::tempdir().unwrap();
        // Running as root would bypass the permission being tested.
        assert_ne!(
            std::fs::metadata(root.path()).unwrap().uid(),
            0,
            "permission fixtures require an unprivileged account"
        );
        let directory = root.path().join("install");
        std::fs::create_dir(&directory).unwrap();
        let launcher = directory.join("launcher");
        std::fs::write(&launcher, "fixture").unwrap();
        struct Restore<'a>(&'a std::path::Path);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                let mode = std::fs::Permissions::from_mode(0o700);
                std::fs::set_permissions(self.0, mode).unwrap();
            }
        }
        let _restore = Restore(&directory);
        std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o000)).unwrap();
        for same_executable in [true, false] {
            assert_eq!(
                registered_status(&launcher, same_executable, true),
                Status::Unknown
            );
        }
        assert_eq!(
            registered_status(&directory.join("missing"), false, true),
            Status::Unknown
        );
    }
    #[test]
    fn invalid_operation_has_diagnostic_and_success_clears_it() {
        assert_eq!(execute(99, ""), Error::InvalidArgument as i32);
        assert!(last_error().is_some());
        assert_eq!(execute(4, ""), Error::InvalidArgument as i32);
        assert_eq!(execute(0, ""), Error::InvalidArgument as i32);
        // Login detection takes an empty request.
        assert_eq!(execute(5, ""), 0);
        assert!(last_error().is_none());
    }
}
