//! Best-effort Windows SSH approval grouping, not authenticated process origin.
//!
//! Retained handles and creation times prevent reuse after an observed process
//! exits. They cannot authenticate the writer of a transferred pipe handle or
//! prevent a client from choosing a different parent at process creation.

use super::{invalid_data, last_error, owned_handle, raw_handle, sid_bytes, token_information};
use crate::ipc::messages::{
    CallerAuthorizationEvidenceSource as Evidence, CallerAuthorizationSubject,
    CallerAuthorizationSubjectKind as Kind, CallerIdentity,
};
use keyguard_agent_identity::SubjectFingerprint;
use std::collections::{HashMap, HashSet};
use std::io;
use std::os::windows::io::{AsRawHandle, OwnedHandle};
use windows_sys::Win32::Foundation::{FILETIME, HANDLE, HWND, LPARAM, WAIT_TIMEOUT};
use windows_sys::Win32::Security::{
    TokenIntegrityLevel, TokenStatistics, TokenUser, TOKEN_MANDATORY_LABEL, TOKEN_QUERY,
    TOKEN_STATISTICS, TOKEN_USER,
};
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS,
};
use windows_sys::Win32::System::Pipes::GetNamedPipeClientProcessId;
use windows_sys::Win32::System::Threading::{
    GetProcessTimes, OpenProcess, OpenProcessToken, QueryFullProcessImageNameW,
    WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowThreadProcessId, IsWindowVisible,
};

pub(crate) struct WindowsCallerIdentity {
    process: ProcessIdentity,
    application: Option<ProcessIdentity>,
}

impl WindowsCallerIdentity {
    pub(crate) fn from_pipe(
        pipe: &tokio::net::windows::named_pipe::NamedPipeServer,
    ) -> io::Result<Self> {
        let mut pid = 0;
        // SAFETY: the connected pipe stays alive throughout the call and pid
        // is writable storage. This API only attributes the original opener.
        if unsafe { GetNamedPipeClientProcessId(pipe.as_raw_handle().cast(), &mut pid) } == 0 {
            return Err(last_error("GetNamedPipeClientProcessId"));
        }
        let process = ProcessIdentity::open(pid)?;
        if process.context.user != token_context_for_current_process()?.user {
            return Err(invalid_data("pipe opener belongs to a different user"));
        }
        let application = find_application(&process, &visible_window_processes());
        process.revalidate()?;
        Ok(Self {
            process,
            application,
        })
    }

    /// Return fresh evidence each time, omitting an application that has exited
    /// or changed security context. An expired direct peer rejects the request.
    pub(crate) fn update_caller(&self, caller: &mut CallerIdentity) -> io::Result<()> {
        self.process.revalidate()?;
        let application = self
            .application
            .as_ref()
            .filter(|app| app.revalidate().is_ok());
        let mut subjects = vec![self.process.subject(Kind::Process)?];
        if let Some(app) = application {
            subjects.push(app.subject(Kind::ApplicationInstance)?);
        }
        caller.pid = self.process.pid;
        caller.process_name = self.process.name();
        caller.executable_path = self.process.path.clone();
        let app = application.unwrap_or(&self.process);
        caller.app_pid = app.pid;
        caller.app_name = app.name();
        caller.app_bundle_path = app.path.clone();
        if let Some(authorization) = caller.authorization.as_mut() {
            authorization.subjects = subjects;
        }
        Ok(())
    }
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
    let token = owned_handle(token, "OpenProcessToken")?;
    let user_buffer = token_information(raw_handle(&token), TokenUser)?;
    let user: TOKEN_USER = user_buffer.read()?;
    let integrity_buffer = token_information(raw_handle(&token), TokenIntegrityLevel)?;
    let integrity: TOKEN_MANDATORY_LABEL = integrity_buffer.read()?;
    let statistics: TOKEN_STATISTICS =
        token_information(raw_handle(&token), TokenStatistics)?.read()?;
    let mut authentication_id = [0; 8];
    authentication_id[..4].copy_from_slice(&statistics.AuthenticationId.LowPart.to_le_bytes());
    authentication_id[4..].copy_from_slice(&statistics.AuthenticationId.HighPart.to_le_bytes());
    Ok(TokenContext {
        user: sid_bytes(user.User.Sid)?,
        integrity: sid_bytes(integrity.Label.Sid)?,
        authentication_id,
    })
}

struct ProcessIdentity {
    handle: OwnedHandle,
    pid: u32,
    created: u64,
    context: TokenContext,
    path: String,
}

impl ProcessIdentity {
    fn open(pid: u32) -> io::Result<Self> {
        // SAFETY: pid is a value, and the resulting handle is wrapped in RAII.
        let handle = owned_handle(
            unsafe {
                OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    0,
                    pid,
                )
            },
            "OpenProcess",
        )?;
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

    fn revalidate(&self) -> io::Result<()> {
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

    fn name(&self) -> String {
        self.path
            .rsplit(['\\', '/'])
            .next()
            .unwrap_or(&self.path)
            .to_owned()
    }

    fn subject(&self, kind: Kind) -> io::Result<CallerAuthorizationSubject> {
        let (domain, evidence): (&[u8], Evidence) = match kind {
            Kind::Process => (
                b"windows-pipe-opener-process-v1",
                Evidence::WindowsProcessSnapshot,
            ),
            Kind::ApplicationInstance => (
                b"windows-pipe-opener-application-v1",
                Evidence::WindowsApplicationSnapshot,
            ),
            _ => return Err(invalid_data("unsupported Windows subject kind")),
        };
        let mut canonical = Vec::new();
        canonical.extend_from_slice(&self.pid.to_le_bytes());
        canonical.extend_from_slice(&self.created.to_le_bytes());
        canonical.extend_from_slice(&self.context.authentication_id);
        for sid in [&self.context.user, &self.context.integrity] {
            canonical.extend_from_slice(&(sid.len() as u32).to_le_bytes());
            canonical.extend_from_slice(sid);
        }
        let fingerprint = SubjectFingerprint::derive(domain, &canonical)
            .map_err(|error| invalid_data(error.to_string()))?;
        Ok(CallerAuthorizationSubject {
            kind: kind as i32,
            evidence_source: evidence as i32,
            fingerprint: fingerprint.into_bytes().to_vec(),
        })
    }
}

fn zero_filetime() -> FILETIME {
    FILETIME {
        dwLowDateTime: 0,
        dwHighDateTime: 0,
    }
}

fn process_parents() -> io::Result<HashMap<u32, u32>> {
    // SAFETY: flags request a process snapshot; the returned handle is owned.
    let snapshot = owned_handle(
        unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) },
        "CreateToolhelp32Snapshot",
    )?;
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

fn visible_window_processes() -> HashSet<u32> {
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

fn find_application(process: &ProcessIdentity, windows: &HashSet<u32>) -> Option<ProcessIdentity> {
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

#[cfg(test)]
mod tests;
