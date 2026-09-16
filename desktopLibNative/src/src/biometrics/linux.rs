//! Linux backend: the user is verified through polkit, so the desktop's own
//! authentication agent shows the dialog and offers whatever PAM allows,
//! typically a fingerprint or the login password. A process-local protected
//! credential is retained natively; only an opaque handle is persisted.

use super::linux_shared::{
    classify_authorization, classify_method_error, classify_pkexec_exit, is_flatpak,
    is_subject_rejection, ACTION_ID, DETAIL_DISMISSED, FLAG_ALLOW_USER_INTERACTION,
    INSTALL_POLICY_SCRIPT, POLICY_PATH, POLICY_XML,
};
use super::{report_verify_result, ChallengeResult, ChallengeStatus};
use crate::ffi::BiometricsVerifyCallback;
use crate::keychain::memory::{store, MemoryStore};
use std::collections::HashMap;
use std::io::Write;
use std::os::fd::{AsFd, FromRawFd, OwnedFd};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant};
use zbus::blocking::Connection;
use zbus::zvariant::{self, Value};

const POLICY_RELOAD_TIMEOUT: Duration = Duration::from_secs(2);
const POLICY_RELOAD_POLL_INTERVAL: Duration = Duration::from_millis(100);
// Flatpak also bind-mounts its system-bus proxy at this fixed path.
// Never use Connection::system(): it accepts DBUS_SYSTEM_BUS_ADDRESS.
const SYSTEM_BUS_ADDRESS: &str = "unix:path=/run/dbus/system_bus_socket";

fn system_connection() -> zbus::Result<Connection> {
    zbus::blocking::connection::Builder::address(SYSTEM_BUS_ADDRESS)?.build()
}

type Outcome = (ChallengeStatus, Option<String>);

#[zbus::proxy(
    interface = "org.freedesktop.PolicyKit1.Authority",
    default_service = "org.freedesktop.PolicyKit1",
    default_path = "/org/freedesktop/PolicyKit1/Authority",
    gen_async = false
)]
trait Authority {
    fn check_authorization(
        &self,
        subject: &Subject<'_>,
        action_id: &str,
        details: HashMap<&str, &str>,
        flags: u32,
        cancellation_id: &str,
    ) -> zbus::Result<AuthorizationResult>;

    fn enumerate_actions(&self, locale: &str) -> zbus::Result<Vec<ActionDescription>>;

    #[zbus(property)]
    fn backend_version(&self) -> zbus::Result<String>;
}

/// `(sa{sv})`
#[derive(serde::Serialize, zvariant::Type)]
struct Subject<'a> {
    kind: &'a str,
    details: HashMap<&'a str, Value<'a>>,
}

impl<'a> Subject<'a> {
    /// Identifies this process by a pidfd, which the kernel resolves to the
    /// right process even from inside a Flatpak PID namespace. Understood
    /// by polkit 124 and newer.
    fn unix_process(pidfd: &'a OwnedFd) -> Self {
        let uid = i32::try_from(
            // SAFETY: getuid takes no arguments and cannot fail.
            unsafe { libc::getuid() },
        )
        .unwrap_or(-1);
        Self {
            kind: "unix-process",
            details: HashMap::from([
                ("pidfd", Value::Fd(zvariant::Fd::from(pidfd.as_fd()))),
                ("uid", Value::I32(uid)),
            ]),
        }
    }

    /// Identifies this process by its bus connection; polkitd asks the bus
    /// daemon who owns it, so no PID lookup happens on our side.
    fn system_bus_name(name: &'a str) -> Self {
        Self {
            kind: "system-bus-name",
            details: HashMap::from([("name", Value::Str(name.into()))]),
        }
    }
}

/// `(bba{ss})`
#[derive(serde::Deserialize, zvariant::Type)]
struct AuthorizationResult {
    is_authorized: bool,
    is_challenge: bool,
    details: HashMap<String, String>,
}

/// `(ssssssuuua{ss})`
#[derive(serde::Deserialize, zvariant::Type)]
struct ActionDescription {
    action_id: String,
    _description: String,
    _message: String,
    _vendor_name: String,
    _vendor_url: String,
    _icon_name: String,
    _implicit_any: u32,
    _implicit_inactive: u32,
    _implicit_active: u32,
    _annotations: HashMap<String, String>,
}

pub(crate) fn is_supported() -> bool {
    static SUPPORTED: OnceLock<bool> = OnceLock::new();
    *SUPPORTED.get_or_init(probe_support)
}

/// polkitd must answer on the system bus. The action registration is
/// deliberately not part of the probe, the setting has to stay visible so
/// the one-time policy install can run from it.
fn probe_support() -> bool {
    if !MemoryStore::is_supported() {
        eprintln!("keyguard-lib::biometrics: protected credential storage is unavailable");
        return false;
    }
    let has_polkit = system_connection()
        .and_then(|connection| AuthorityProxy::new(&connection))
        .and_then(|authority| authority.backend_version())
        .is_ok();
    if !has_polkit {
        eprintln!("keyguard-lib::biometrics: polkit is not reachable on the system bus");
    }
    has_polkit
}

pub(crate) fn verify(_window_handle: i64, _title: &str, callback: BiometricsVerifyCallback) {
    // The dialog belongs to the polkit agent, which ignores our window and
    // shows the message from the policy file instead of the title.
    let (status, error) = run_verify();
    report_verify_result(callback, status, error.as_deref());
}

/// Installs the polkit policy if polkitd does not know our action yet. Only
/// enrollment calls this, so the administrator prompt never appears from a
/// plain verification such as the "Confirm access" dialog.
pub(crate) fn prepare_enrollment(callback: BiometricsVerifyCallback) {
    let (status, error) = match connect_authority() {
        Ok((_connection, authority)) => match ensure_action_registered(&authority) {
            Ok(()) => (ChallengeStatus::Success, None),
            Err(outcome) => outcome,
        },
        Err(outcome) => outcome,
    };
    report_verify_result(callback, status, error.as_deref());
}

fn connect_authority() -> Result<(Connection, AuthorityProxy<'static>), Outcome> {
    let connection = system_connection().map_err(|err| {
        (
            ChallengeStatus::Unavailable,
            Some(format!("Failed to connect to the system bus: {err}")),
        )
    })?;
    let authority = AuthorityProxy::new(&connection).map_err(|err| {
        (
            ChallengeStatus::Unavailable,
            Some(format!("Failed to reach polkit: {err}")),
        )
    })?;
    Ok((connection, authority))
}

/// A plain `CheckAuthorization`. A missing policy surfaces as the
/// "not registered" method error, which classifies as `PolicyNotInstalled`.
fn run_verify() -> Outcome {
    let (connection, authority) = match connect_authority() {
        Ok(pair) => pair,
        Err(outcome) => return outcome,
    };

    let cancellation_id = next_cancellation_id();
    if let Some(pidfd) = open_pidfd().filter(|_| !PIDFD_SUBJECT_REJECTED.load(Ordering::Relaxed)) {
        let subject = Subject::unix_process(&pidfd);
        match check_authorization(&authority, &subject, &cancellation_id) {
            Ok(outcome) => return outcome,
            Err(zbus::Error::MethodError(name, message, _))
                if is_subject_rejection(name.as_str(), message.as_deref()) =>
            {
                // Older polkit rejects a subject without a pid; fall back to
                // the bus name below and remember the decision so a real
                // failure is never re-issued as a second polkit call.
                PIDFD_SUBJECT_REJECTED.store(true, Ordering::Relaxed);
                let detail = message.as_deref().unwrap_or("no details");
                eprintln!(
                    "keyguard-lib::biometrics: pidfd subject rejected ({name}: {detail}), \
                     using the bus name from now on"
                );
            }
            Err(err) => return classify_error(err),
        }
    }

    let Some(unique_name) = connection.unique_name() else {
        return (
            ChallengeStatus::Unknown,
            Some("The system bus connection has no unique name".to_owned()),
        );
    };
    let subject = Subject::system_bus_name(unique_name.as_str());
    match check_authorization(&authority, &subject, &cancellation_id) {
        Ok(outcome) => outcome,
        Err(err) => classify_error(err),
    }
}

fn check_authorization(
    authority: &AuthorityProxy<'_>,
    subject: &Subject<'_>,
    cancellation_id: &str,
) -> zbus::Result<Outcome> {
    let result = authority.check_authorization(
        subject,
        ACTION_ID,
        HashMap::new(),
        FLAG_ALLOW_USER_INTERACTION,
        cancellation_id,
    )?;
    let (status, error) = classify_authorization(
        result.is_authorized,
        result.is_challenge,
        result.details.get(DETAIL_DISMISSED).map(String::as_str),
    );
    Ok((status, error.map(str::to_owned)))
}

fn classify_error(err: zbus::Error) -> Outcome {
    match err {
        zbus::Error::MethodError(name, message, _) => {
            classify_method_error(name.as_str(), message.as_deref())
        }
        other => (
            ChallengeStatus::Unavailable,
            Some(format!("polkit call failed: {other}")),
        ),
    }
}

/// Set once polkit has rejected the pidfd subject (polkit < 124).
static PIDFD_SUBJECT_REJECTED: AtomicBool = AtomicBool::new(false);

fn next_cancellation_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let counter = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("keyguard-{}-{counter}", std::process::id())
}

fn open_pidfd() -> Option<OwnedFd> {
    // SAFETY: pidfd_open takes a pid and a flags word by value and returns a
    // new descriptor or a negative error; it touches no memory we own.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, libc::getpid(), 0 as libc::c_uint) };
    let fd = libc::c_int::try_from(fd).ok().filter(|fd| *fd >= 0)?;
    // SAFETY: the descriptor was just returned by the kernel and is owned by
    // nobody else, so wrapping it transfers the single ownership to us.
    Some(unsafe { OwnedFd::from_raw_fd(fd) })
}

fn is_action_registered(authority: &AuthorityProxy<'_>) -> zbus::Result<bool> {
    let actions = authority.enumerate_actions("")?;
    Ok(actions.iter().any(|action| action.action_id == ACTION_ID))
}

/// Makes sure polkitd knows our action, installing the policy through
/// `pkexec` on a native install. Inside Flatpak the host file system is out
/// of reach, so the user has to run the documented command once.
fn ensure_action_registered(authority: &AuthorityProxy<'_>) -> Result<(), Outcome> {
    match is_action_registered(authority) {
        Ok(true) => return Ok(()),
        Ok(false) => {}
        Err(err) => {
            return Err((
                ChallengeStatus::Unavailable,
                Some(format!("Failed to enumerate polkit actions: {err}")),
            ))
        }
    }
    if is_flatpak() {
        return Err((
            ChallengeStatus::PolicyNotInstalled,
            Some(format!("{POLICY_PATH} is not installed on the host")),
        ));
    }
    install_policy_with_pkexec()?;

    // polkitd picks the new file up through inotify; give it a moment.
    let deadline = Instant::now() + POLICY_RELOAD_TIMEOUT;
    loop {
        if is_action_registered(authority).unwrap_or(false) {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err((
                ChallengeStatus::PolicyNotInstalled,
                Some(format!("polkit has not loaded {POLICY_PATH} yet")),
            ));
        }
        thread::sleep(POLICY_RELOAD_POLL_INTERVAL);
    }
}

fn install_policy_with_pkexec() -> Result<(), Outcome> {
    let failure = |message| (ChallengeStatus::PolicyNotInstalled, Some(message));
    let mut child = Command::new("pkexec")
        .args(["sh", "-c", INSTALL_POLICY_SCRIPT])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|err| failure(format!("Failed to start pkexec: {err}")))?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(POLICY_XML.as_bytes())
            .map_err(|err| failure(format!("Failed to pass the policy to pkexec: {err}")))?;
    }
    let output = child
        .wait_with_output()
        .map_err(|err| failure(format!("Failed to wait for pkexec: {err}")))?;
    let stderr = String::from_utf8_lossy(&output.stderr);
    match classify_pkexec_exit(output.status.code(), &stderr) {
        None => Ok(()),
        Some((status, message)) => Err((status, Some(message))),
    }
}

pub(crate) fn delete_credential() -> bool {
    store().remove();
    true
}

pub(crate) fn transform_secret(
    _window_handle: i64,
    _title: &str,
    input: &[u8],
    decrypt: bool,
) -> ChallengeResult {
    transform_with(store(), input, decrypt, run_verify)
}

fn transform_with(
    store: &MemoryStore,
    input: &[u8],
    decrypt: bool,
    authenticate: impl FnOnce() -> Outcome,
) -> ChallengeResult {
    if !decrypt {
        // Password rearming already proves the master key. Enrollment prompts
        // before this call; storing a key never releases an existing secret.
        return match store.put(input) {
            Ok(handle) => ChallengeResult::success(handle),
            Err(error) => ChallengeResult::failure(ChallengeStatus::Unavailable, error.to_string()),
        };
    }
    let (status, error) = authenticate();
    if status != ChallengeStatus::Success {
        return ChallengeResult::failure(status, error.unwrap_or_default());
    }
    match store.get(input) {
        Ok(secret) => ChallengeResult::success(secret),
        Err(error) => {
            ChallengeResult::failure(ChallengeStatus::CredentialNotFound, error.to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "requires a reachable system bus; run in the isolated Linux test container"]
    fn system_bus_ignores_environment_override() {
        let path = std::env::temp_dir().join(format!("keyguard-fake-bus-{}", std::process::id()));
        assert!(!path.exists());
        for address in [
            format!("unix:path={}", path.display()),
            "invalid-address".to_owned(),
        ] {
            let child = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "biometrics::imp::tests::trusted_bus_connection_child",
                ])
                .env("KEYGUARD_TEST_TRUSTED_BUS", "1")
                .env("DBUS_SYSTEM_BUS_ADDRESS", address)
                .output()
                .unwrap();
            let output = String::from_utf8_lossy(&child.stdout);
            assert!(child.status.success(), "{output}");
            assert!(
                output.contains("1 passed"),
                "child test must execute: {output}"
            );
        }
    }

    #[test]
    fn trusted_bus_connection_child() {
        if std::env::var_os("KEYGUARD_TEST_TRUSTED_BUS").is_some() {
            let connection = system_connection().unwrap();
            assert!(connection.unique_name().is_some());
        }
    }

    #[test]
    #[ignore = "requires a seccomp filter denying add_key, keyctl and memfd_secret"]
    fn unavailable_storage_fails_closed() {
        assert!(!MemoryStore::is_supported());
        let result = transform_with(&MemoryStore::new(), b"master key", false, || {
            panic!("provisioning must not prompt")
        });
        assert_eq!(result.status, ChallengeStatus::Unavailable);
        assert!(result.value.is_empty());
    }

    #[test]
    fn credential_release_requires_successful_authentication() {
        let store = MemoryStore::new();
        let handle = transform_with(&store, b"master key", false, || {
            panic!("rearm must not prompt")
        });
        assert_eq!(handle.status, ChallengeStatus::Success);
        for status in [
            ChallengeStatus::UserCanceled,
            ChallengeStatus::Unavailable,
            ChallengeStatus::Unknown,
        ] {
            let denied = transform_with(&store, &handle.value, true, || (status, None));
            assert_eq!(denied.status, status);
            assert!(denied.value.is_empty());
        }
        let allowed = transform_with(&store, &handle.value, true, || {
            (ChallengeStatus::Success, None)
        });
        assert_eq!(allowed.value.as_slice(), b"master key");
        store.remove();
        let absent = transform_with(&store, &handle.value, true, || {
            (ChallengeStatus::Success, None)
        });
        assert_eq!(absent.status, ChallengeStatus::CredentialNotFound);
        assert!(absent.value.is_empty());
    }
}
