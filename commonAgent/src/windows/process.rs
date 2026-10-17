//! Best-effort Windows process identity and application grouping, not
//! authenticated process origin.
//!
//! Retained handles and creation times prevent reuse after an observed process
//! exits. They cannot authenticate the writer of a transferred pipe handle or
//! prevent a client from choosing a different parent at process creation.

use super::{invalid_data, last_error, owned_handle, sid_bytes, token_information};
use crate::{IdentityError, RelayedCaller, SubjectFingerprint};
use std::collections::{HashMap, HashSet};
use std::io;
use std::os::windows::io::{AsHandle, AsRawHandle, OwnedHandle};
use windows_sys::Win32::Foundation::{FILETIME, HANDLE, HWND, LPARAM, WAIT_TIMEOUT};
use windows_sys::Win32::Security::{
    TokenIntegrityLevel, TokenStatistics, TokenUser, TOKEN_MANDATORY_LABEL, TOKEN_QUERY,
    TOKEN_STATISTICS, TOKEN_USER,
};
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS,
};
use windows_sys::Win32::System::Threading::{
    GetProcessTimes, OpenProcess, OpenProcessToken, QueryFullProcessImageNameW,
    WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowThreadProcessId, IsWindowVisible,
};

fn raw_handle(handle: &OwnedHandle) -> HANDLE {
    handle.as_raw_handle().cast()
}

#[derive(PartialEq, Eq)]
struct TokenContext {
    user: Box<[u8]>,
    integrity: Box<[u8]>,
    authentication_id: [u8; 8],
}

fn token_context_for_current_process() -> io::Result<TokenContext> {
    // SAFETY: the pseudo-handle is borrowed only for the following query.
    token_context(unsafe { windows_sys::Win32::System::Threading::GetCurrentProcess() })
}

fn token_context(process: HANDLE) -> io::Result<TokenContext> {
    let mut token = std::ptr::null_mut();
    // SAFETY: process is a live handle and token is writable handle storage.
    if unsafe { OpenProcessToken(process, TOKEN_QUERY, &mut token) } == 0 {
        return Err(last_error("OpenProcessToken"));
    }
    // SAFETY: OpenProcessToken succeeded and returned a new owned token
    // handle, which is transferred to OwnedHandle and closed with CloseHandle.
    let token = unsafe { owned_handle(token, "OpenProcessToken") }?;
    let user_buffer = token_information(token.as_handle(), TokenUser)?;
    // SAFETY: TokenUser fills the buffer with a TOKEN_USER.
    let user: TOKEN_USER = unsafe { user_buffer.read() }?;
    let integrity_buffer = token_information(token.as_handle(), TokenIntegrityLevel)?;
    // SAFETY: TokenIntegrityLevel fills the buffer with a TOKEN_MANDATORY_LABEL.
    let integrity: TOKEN_MANDATORY_LABEL = unsafe { integrity_buffer.read() }?;
    let statistics_buffer = token_information(token.as_handle(), TokenStatistics)?;
    // SAFETY: TokenStatistics fills the buffer with a TOKEN_STATISTICS.
    let statistics: TOKEN_STATISTICS = unsafe { statistics_buffer.read() }?;
    let mut authentication_id = [0; 8];
    authentication_id[..4].copy_from_slice(&statistics.AuthenticationId.LowPart.to_le_bytes());
    authentication_id[4..].copy_from_slice(&statistics.AuthenticationId.HighPart.to_le_bytes());
    Ok(TokenContext {
        // SAFETY: both SIDs point into buffers that are alive in this scope.
        user: unsafe { sid_bytes(user.User.Sid) }?,
        // SAFETY: as above, `integrity_buffer` is still alive.
        integrity: unsafe { sid_bytes(integrity.Label.Sid) }?,
        authentication_id,
    })
}

/// A process held open so that its ID can not be reused, with the security
/// context and creation time it had when opened.
pub struct ProcessIdentity {
    handle: OwnedHandle,
    pid: u32,
    created: u64,
    context: TokenContext,
    path: String,
}

impl ProcessIdentity {
    /// Opens the process with ID `pid`.
    ///
    /// # Errors
    ///
    /// Fails when the process has exited or can not be queried.
    pub fn open(pid: u32) -> io::Result<Self> {
        // SAFETY: OpenProcess takes a PID value and returns either null or a
        // new owned handle compatible with CloseHandle. Ownership transfers
        // directly to OwnedHandle.
        let handle = unsafe {
            owned_handle(
                OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    0,
                    pid,
                ),
                "OpenProcess",
            )
        }?;
        let mut created = zero_filetime();
        let mut exited = zero_filetime();
        let mut kernel = zero_filetime();
        let mut user = zero_filetime();
        // SAFETY: all output pointers refer to writable FILETIME storage.
        if unsafe {
            GetProcessTimes(
                raw_handle(&handle),
                &mut created,
                &mut exited,
                &mut kernel,
                &mut user,
            )
        } == 0
        {
            return Err(last_error("GetProcessTimes"));
        }
        let context = token_context(raw_handle(&handle))?;
        let mut path = vec![0u16; 32768];
        let mut length = path.len() as u32;
        // SAFETY: path is a writable UTF-16 buffer of the supplied size.
        if unsafe {
            QueryFullProcessImageNameW(raw_handle(&handle), 0, path.as_mut_ptr(), &mut length)
        } == 0
        {
            return Err(last_error("QueryFullProcessImageNameW"));
        }
        let value = Self {
            handle,
            pid,
            created: (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime),
            context,
            path: String::from_utf16_lossy(&path[..length as usize]),
        };
        value.revalidate()?;
        Ok(value)
    }

    /// Fails once the process exited or its security context changed.
    ///
    /// # Errors
    ///
    /// Fails when the process exited, its context changed, or it can not be
    /// queried.
    pub fn revalidate(&self) -> io::Result<()> {
        // SAFETY: the retained process handle has SYNCHRONIZE access.
        if unsafe { WaitForSingleObject(raw_handle(&self.handle), 0) } != WAIT_TIMEOUT {
            return Err(invalid_data(
                "caller process has exited or cannot be queried",
            ));
        }
        if token_context(raw_handle(&self.handle))? != self.context {
            return Err(invalid_data("caller security context changed"));
        }
        Ok(())
    }

    /// Whether the process runs as the user of this process.
    ///
    /// # Errors
    ///
    /// Fails when the token of this process can not be read.
    pub fn is_current_user(&self) -> io::Result<bool> {
        Ok(self.context.user == token_context_for_current_process()?.user)
    }

    #[must_use]
    pub const fn pid(&self) -> u32 {
        self.pid
    }

    /// The image path. Display data only.
    #[must_use]
    pub fn path(&self) -> &str {
        &self.path
    }

    /// The file name of the image. Display data only.
    #[must_use]
    pub fn name(&self) -> String {
        self.path
            .rsplit(['\\', '/'])
            .next()
            .unwrap_or(&self.path)
            .to_owned()
    }

    /// A fingerprint of this process instance in `domain`. The display path
    /// never contributes to it.
    ///
    /// # Errors
    ///
    /// Fails when the fingerprint can not be derived.
    pub fn subject_fingerprint(&self, domain: &[u8]) -> Result<SubjectFingerprint, IdentityError> {
        let mut canonical = Vec::new();
        canonical.extend_from_slice(&self.pid.to_le_bytes());
        canonical.extend_from_slice(&self.created.to_le_bytes());
        canonical.extend_from_slice(&self.context.authentication_id);
        for sid in [&self.context.user, &self.context.integrity] {
            canonical.extend_from_slice(&(sid.len() as u32).to_le_bytes());
            canonical.extend_from_slice(sid);
        }
        SubjectFingerprint::derive(domain, &canonical)
    }

    /// The process that started this one, while it runs in the same
    /// security context.
    #[must_use]
    pub fn parent(&self) -> Option<Self> {
        parent_in(self, &process_parents().ok()?)
    }
}

fn zero_filetime() -> FILETIME {
    FILETIME {
        dwLowDateTime: 0,
        dwHighDateTime: 0,
    }
}

fn process_parents() -> io::Result<HashMap<u32, u32>> {
    // SAFETY: these flags request a process snapshot. CreateToolhelp32Snapshot
    // returns either INVALID_HANDLE_VALUE or a new owned handle compatible
    // with CloseHandle, which is transferred directly to OwnedHandle.
    let snapshot = unsafe {
        owned_handle(
            CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0),
            "CreateToolhelp32Snapshot",
        )
    }?;
    // SAFETY: PROCESSENTRY32W is a plain C output structure; zero is valid for
    // all fields before setting the size required by Toolhelp.
    let mut entry: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
    entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
    let mut parents = HashMap::new();
    // SAFETY: entry has the required size and snapshot is a live process snapshot.
    let mut found = unsafe { Process32FirstW(raw_handle(&snapshot), &mut entry) };
    while found != 0 {
        parents.insert(entry.th32ProcessID, entry.th32ParentProcessID);
        // SAFETY: same valid snapshot and output structure as above.
        found = unsafe { Process32NextW(raw_handle(&snapshot), &mut entry) };
    }
    Ok(parents)
}

/// The processes that own a visible window.
#[must_use]
pub fn visible_window_processes() -> HashSet<u32> {
    unsafe extern "system" fn visit(window: HWND, param: LPARAM) -> i32 {
        // SAFETY: param points to the set borrowed for the synchronous EnumWindows call.
        let processes = unsafe { &mut *(param as *mut HashSet<u32>) };
        // SAFETY: window is provided by EnumWindows; a destroyed window simply fails the query.
        if unsafe { IsWindowVisible(window) } != 0 {
            let mut pid = 0;
            // SAFETY: pid is writable storage and window is supplied by EnumWindows.
            unsafe { GetWindowThreadProcessId(window, &mut pid) };
            processes.insert(pid);
        }
        1
    }
    let mut processes = HashSet::new();
    // SAFETY: the callback only borrows this live set and never retains its pointer.
    unsafe { EnumWindows(Some(visit), &mut processes as *mut HashSet<u32> as LPARAM) };
    processes
}

fn is_desktop_broker(name: &str) -> bool {
    matches!(
        name,
        "explorer.exe"
            | "wininit.exe"
            | "winlogon.exe"
            | "services.exe"
            | "svchost.exe"
            | "dwm.exe"
            | "runtimebroker.exe"
            | "conhost.exe"
            | "openconsole.exe"
    )
}

fn is_shell(name: &str) -> bool {
    matches!(
        name,
        "pwsh.exe" | "powershell.exe" | "cmd.exe" | "bash.exe" | "zsh.exe" | "fish.exe" | "nu.exe"
    )
}

/// The closest visible application (IDE/terminal) in the ancestry of
/// `process`, or else the closest shell.
#[must_use]
pub fn find_application(
    process: &ProcessIdentity,
    windows: &HashSet<u32>,
) -> Option<ProcessIdentity> {
    find_application_in(process, windows, &process_parents().ok()?)
}

fn find_application_in(
    process: &ProcessIdentity,
    windows: &HashSet<u32>,
    parents: &HashMap<u32, u32>,
) -> Option<ProcessIdentity> {
    let mut pid = process.pid;
    let mut child_created = process.created;
    let mut visited = HashSet::new();
    let mut shell = None;
    // Prefer the closest visible application (IDE/terminal), then the closest
    // shell when Windows does not expose the terminal in the parent chain.
    // Never group unrelated apps under the desktop or a session broker.
    for _ in 0..16 {
        if pid == 0 || !visited.insert(pid) {
            break;
        }
        let Ok(candidate) = ProcessIdentity::open(pid) else {
            break;
        };
        if candidate.created > child_created || candidate.context != process.context {
            break;
        }
        let name = candidate.name().to_ascii_lowercase();
        if is_desktop_broker(&name) {
            break;
        }
        child_created = candidate.created;
        if windows.contains(&pid) {
            return Some(candidate);
        }
        if shell.is_none() && is_shell(&name) {
            shell = Some(candidate);
        }
        let Some(parent) = parents.get(&pid) else {
            break;
        };
        pid = *parent;
    }
    shell
}

fn parent_in(process: &ProcessIdentity, parents: &HashMap<u32, u32>) -> Option<ProcessIdentity> {
    let pid = *parents.get(&process.pid)?;
    if pid == 0 || pid == process.pid {
        return None;
    }
    let parent = ProcessIdentity::open(pid).ok()?;
    // Windows keeps the ID of an exited parent, which a newer process can reuse.
    (parent.created <= process.created && parent.context == process.context).then_some(parent)
}

/// Describes the process that started `pid`, for a peer that only relays
/// requests for it, such as a bundled command-line client. Display data only.
/// `None` when the parent exited or runs in another security context.
#[must_use]
pub fn relayed_caller(pid: u32) -> Option<RelayedCaller> {
    let process = ProcessIdentity::open(pid).ok()?;
    let parent = process.parent()?;
    let application = find_application(&parent, &visible_window_processes());
    Some(RelayedCaller {
        pid: parent.pid,
        process_name: super::escape_display_text(&parent.name()),
        executable_path: super::escape_display_text(&parent.path),
        app_name: application
            .as_ref()
            .map(|application| super::escape_display_text(&application.name()))
            .unwrap_or_default(),
        app_bundle_path: application
            .as_ref()
            .map(|application| super::escape_display_text(&application.path))
            .unwrap_or_default(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::windows::process::CommandExt;
    use std::process::{Child, Command, Stdio};

    struct TestProcess(Child);

    impl TestProcess {
        fn spawn() -> Self {
            Self(
                Command::new(std::env::current_exe().unwrap())
                    .args([
                        "--exact",
                        "windows::process::tests::process_fixture",
                        "--nocapture",
                    ])
                    .env("KEYGUARD_TEST_PROCESS_FIXTURE", "1")
                    .stdin(Stdio::piped())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW)
                    .spawn()
                    .unwrap(),
            )
        }
    }

    impl Drop for TestProcess {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    #[test]
    fn process_fixture() {
        if std::env::var("KEYGUARD_TEST_PROCESS_FIXTURE").is_err() {
            return;
        }
        // The parent owns stdin and releases this fixture even if a test panics.
        std::io::stdin().read_line(&mut String::new()).unwrap();
    }

    #[test]
    fn ancestry_rejects_cycles_newer_parents_and_changed_context() {
        let child = TestProcess::spawn();
        let mut process = ProcessIdentity::open(child.0.id()).unwrap();
        let parent = std::process::id();
        let windows = HashSet::from([parent]);
        let cycle = HashMap::from([(process.pid, process.pid)]);
        assert!(find_application_in(&process, &windows, &cycle).is_none());
        let chain = HashMap::from([(process.pid, parent)]);
        assert!(find_application_in(&process, &windows, &chain).is_some());
        process.context.authentication_id[0] ^= 1;
        assert!(find_application_in(&process, &windows, &chain).is_none());
        assert!(process.revalidate().is_err());
        process.context.authentication_id[0] ^= 1;
        // A recycled parent PID can now identify a process born after the child.
        let newer = TestProcess::spawn();
        assert!(ProcessIdentity::open(newer.0.id()).unwrap().created > process.created);
        assert!(find_application_in(
            &process,
            &HashSet::from([newer.0.id()]),
            &HashMap::from([(process.pid, newer.0.id())]),
        )
        .is_none());
    }

    #[test]
    fn parent_is_the_older_process_that_started_the_child() {
        let child = TestProcess::spawn();
        let process = ProcessIdentity::open(child.0.id()).unwrap();
        let parent = process.parent().expect("parent");
        assert_eq!(parent.pid, std::process::id());
        assert!(parent_in(&process, &HashMap::from([(process.pid, process.pid)])).is_none());
        // A recycled parent PID can identify a process born after the child.
        let newer = TestProcess::spawn();
        assert!(parent_in(&process, &HashMap::from([(process.pid, newer.0.id())])).is_none());
    }

    #[test]
    fn subject_fingerprint_separates_lifetime_and_context_without_using_display_path() {
        let mut process = ProcessIdentity::open(std::process::id()).unwrap();
        let original = process.subject_fingerprint(b"process").unwrap();
        process.path = "other-name.exe".into();
        assert_eq!(original, process.subject_fingerprint(b"process").unwrap());
        assert_ne!(
            original,
            process.subject_fingerprint(b"application").unwrap()
        );
        process.created += 1;
        assert_ne!(original, process.subject_fingerprint(b"process").unwrap());
        process.created -= 1;
        process.context.authentication_id[0] ^= 1;
        assert_ne!(original, process.subject_fingerprint(b"process").unwrap());
    }
}
