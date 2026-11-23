use crate::windows_command::quote_argument;
use crate::windows_startup::{self, shortcut_approval, state};
use crate::{Error, Failure, Operation, Status, Target, registered_status};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use windows::ApplicationModel::StartupTask;
use windows::System::Profile::AnalyticsInfo;
use windows::Win32::Foundation::{
    APPMODEL_ERROR_NO_PACKAGE, ERROR_FILE_NOT_FOUND, ERROR_INSUFFICIENT_BUFFER, ERROR_SUCCESS,
    RPC_E_CHANGED_MODE,
};
use windows::Win32::Storage::Packaging::Appx::GetCurrentPackageFullName;
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, CoCreateInstance, CoTaskMemFree, IPersistFile, STGM_READ,
};
use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_BINARY, RegGetValueW};
use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize};
use windows::Win32::UI::Shell::{
    FOLDERID_Startup, IShellLinkW, KF_FLAG_CREATE, SHGetKnownFolderPath, ShellExecuteW, ShellLink,
};
use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
use windows::core::{HSTRING, Interface, w};

fn system(error: impl std::fmt::Display) -> Failure {
    Failure::new(Error::System, error.to_string())
}

/// Caches a process-lifetime fact after its first successful query; failures are retried.
fn cached<T: Clone>(
    cell: &OnceLock<T>,
    query: impl FnOnce() -> Result<T, Failure>,
) -> Result<T, Failure> {
    if let Some(value) = cell.get() {
        return Ok(value.clone());
    }
    let value = query()?;
    Ok(cell.get_or_init(|| value).clone())
}

fn has_package_identity() -> Result<bool, Failure> {
    static IDENTITY: OnceLock<bool> = OnceLock::new();
    cached(&IDENTITY, query_package_identity)
}

fn query_package_identity() -> Result<bool, Failure> {
    let mut length = 0;
    // SAFETY: Windows supports a null buffer with zero size to query package identity.
    let code = unsafe { GetCurrentPackageFullName(&mut length, None) };
    if code == ERROR_INSUFFICIENT_BUFFER {
        Ok(true)
    } else if code == APPMODEL_ERROR_NO_PACKAGE {
        Ok(false)
    } else {
        Err(system(format!("package identity query failed: {}", code.0)))
    }
}

struct Apartment(bool);
impl Apartment {
    fn enter() -> Result<Self, Failure> {
        // SAFETY: Initializes the calling thread; the guard balances successful calls.
        match unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
            Ok(()) => Ok(Self(true)),
            Err(error) if error.code() == RPC_E_CHANGED_MODE => Ok(Self(false)),
            Err(error) => Err(system(error)),
        }
    }
}
impl Drop for Apartment {
    fn drop(&mut self) {
        if self.0 {
            // SAFETY: Exactly balances this guard's successful RoInitialize.
            unsafe { RoUninitialize() };
        }
    }
}

fn device_family_version() -> Result<String, Failure> {
    static VERSION: OnceLock<String> = OnceLock::new();
    cached(&VERSION, || {
        AnalyticsInfo::VersionInfo()
            .and_then(|info| info.DeviceFamilyVersion())
            .map(|version| version.to_string())
            .map_err(system)
    })
}

pub(crate) fn execute(operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    let _apartment = Apartment::enter()?;
    if operation == Operation::Settings {
        // SAFETY: All strings are constant, terminated UTF-16; no untrusted command is executed.
        let result = unsafe {
            ShellExecuteW(
                None,
                w!("open"),
                w!("ms-settings:startupapps"),
                None,
                None,
                SW_SHOWNORMAL,
            )
        };
        return if result.0 as isize > 32 {
            Ok(Status::Unknown)
        } else {
            Err(system("Windows could not open Startup Apps settings"))
        };
    }
    // A packaged app must use its manifest's startup task, never a shortcut.
    if has_package_identity()? {
        return windows_startup::execute_package(operation, device_family_version, || {
            StartupTask::GetAsync(&HSTRING::from(target.task_id))
                .map_err(system)?
                .join()
                .map(OsStartupTask)
                .map_err(system)
        });
    }
    shortcut(operation, target)
}

struct OsStartupTask(StartupTask);
impl windows_startup::StartupTask for OsStartupTask {
    fn status(&self) -> Result<Status, Failure> {
        self.0.State().map(|value| state(value.0)).map_err(system)
    }

    fn enable(&self) -> Result<Status, Failure> {
        self.0
            .RequestEnableAsync()
            .map_err(system)?
            .join()
            .map(|value| state(value.0))
            .map_err(system)
    }

    fn disable(&self) -> Result<(), Failure> {
        self.0.Disable().map_err(system)
    }
}

fn startup_directory() -> Result<PathBuf, Failure> {
    // The Startup folder may be missing on a fresh or cleaned profile.
    // SAFETY: The known-folder API allocates a terminated path using the COM allocator.
    let pointer =
        unsafe { SHGetKnownFolderPath(&FOLDERID_Startup, KF_FLAG_CREATE, None) }.map_err(system)?;
    // SAFETY: The successful API returned a live terminated UTF-16 allocation.
    let value = unsafe { pointer.to_string() }.map_err(system);
    // SAFETY: This pointer is owned by the caller and freed exactly once.
    unsafe { CoTaskMemFree(Some(pointer.0.cast())) };
    value.map(PathBuf::from)
}

const STARTUP_APPROVED: windows::core::PCWSTR =
    w!("Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\StartupFolder");

/// Reads, and never writes, Explorer's approval value for a Startup-folder file.
fn startup_approved(file_name: &str) -> Result<Option<Vec<u8>>, Failure> {
    let name = HSTRING::from(file_name);
    let read = |data: Option<*mut core::ffi::c_void>, length: &mut u32| {
        // SAFETY: The key and value names are terminated UTF-16, and data, when
        // present, has `length` writable bytes.
        unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                STARTUP_APPROVED,
                &name,
                RRF_RT_REG_BINARY,
                None,
                data,
                Some(std::ptr::from_mut(length)),
            )
        }
    };
    let mut length = 0;
    let code = read(None, &mut length);
    if code == ERROR_FILE_NOT_FOUND {
        return Ok(None);
    }
    if code != ERROR_SUCCESS {
        return Err(system(format!("StartupApproved query failed: {}", code.0)));
    }
    let mut value = vec![0u8; length as usize];
    if length > 0 {
        let code = read(Some(value.as_mut_ptr().cast()), &mut length);
        if code != ERROR_SUCCESS {
            return Err(system(format!("StartupApproved read failed: {}", code.0)));
        }
        value.truncate(length as usize);
    }
    Ok(Some(value))
}

fn shortcut(operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    shortcut_in(&startup_directory()?, operation, target, startup_approved)
}

fn shortcut_in(
    directory: &Path,
    operation: Operation,
    target: &Target<'_>,
    approval: impl FnOnce(&str) -> Result<Option<Vec<u8>>, Failure>,
) -> Result<Status, Failure> {
    let executable = Path::new(target.executable);
    if !executable.is_absolute() {
        return Err(Failure::new(
            Error::InvalidArgument,
            "launcher must be an absolute installed path",
        ));
    }
    let file_name = format!("{}-autostart.lnk", target.id);
    let path = directory.join(&file_name);
    let marker = format!("Keyguard launch-at-login {}", target.id);
    let arguments = target
        .arguments
        .iter()
        .map(|argument| quote_argument(argument))
        .collect::<Vec<_>>()
        .join(" ");
    let existing = match std::fs::symlink_metadata(&path) {
        Ok(metadata) if !metadata.is_file() => {
            return Err(Failure::new(
                Error::Conflict,
                "startup entry is not a regular file",
            ));
        }
        Ok(_) => true,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(error) => return Err(error.into()),
    };
    // SAFETY: COM is initialized on this thread, and CLSID_ShellLink produces IShellLinkW.
    let link: IShellLinkW =
        unsafe { CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER) }.map_err(system)?;
    let file: IPersistFile = link.cast().map_err(system)?;
    let path_string = HSTRING::from(path.as_os_str());
    if existing {
        // SAFETY: Strings and buffers remain live through each synchronous COM operation.
        unsafe {
            file.Load(&path_string, STGM_READ).map_err(system)?;
            let mut description = [0u16; 512];
            link.GetDescription(&mut description).map_err(system)?;
            if decode(&description) != marker {
                return Err(Failure::new(
                    Error::Conflict,
                    "an unrelated startup shortcut uses this identifier",
                ));
            }
        }
    }
    match operation {
        Operation::Disable => {
            if existing {
                std::fs::remove_file(path)?;
            }
            return Ok(Status::Disabled);
        }
        Operation::Enable => {
            if !executable.is_file() {
                return Err(Failure::new(Error::InvalidArgument, "launcher is missing"));
            }
            // SAFETY: All HSTRING temporaries live until their synchronous calls finish.
            unsafe {
                link.SetPath(&HSTRING::from(target.executable))
                    .map_err(system)?;
                link.SetArguments(&HSTRING::from(arguments.as_str()))
                    .map_err(system)?;
                link.SetDescription(&HSTRING::from(marker))
                    .map_err(system)?;
                if let Some(parent) = executable.parent() {
                    link.SetWorkingDirectory(&HSTRING::from(parent.as_os_str()))
                        .map_err(system)?;
                }
                file.Save(&path_string, true).map_err(system)?;
            }
        }
        // `execute` handles Settings before reaching the shortcut.
        Operation::Status | Operation::Settings if !existing => return Ok(Status::Disabled),
        Operation::Status | Operation::Settings => {
            // Approval first: never repair an entry the user disabled.
            let approved = shortcut_approval(approval(&file_name)?.as_deref());
            if approved != Status::Enabled {
                return Ok(approved);
            }
            let mut stored_path = [0u16; 32_768];
            let mut stored_arguments = [0u16; 32_768];
            // SAFETY: Buffers have their declared lengths; GetPath does not dereference a null find-data pointer.
            unsafe {
                link.GetPath(&mut stored_path, std::ptr::null_mut(), 0)
                    .map_err(system)?;
                link.GetArguments(&mut stored_arguments).map_err(system)?;
            }
            let stored_path = decode(&stored_path);
            return Ok(registered_status(
                Path::new(&stored_path),
                stored_path.eq_ignore_ascii_case(target.executable),
                decode(&stored_arguments) == arguments,
            ));
        }
    }
    // A recreated shortcut keeps the user's earlier Task Manager decision.
    Ok(shortcut_approval(approval(&file_name)?.as_deref()))
}

#[cfg(test)]
#[path = "windows_shortcut_tests.rs"]
mod shortcut_tests;

fn decode(buffer: &[u16]) -> String {
    let length = buffer
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(buffer.len());
    String::from_utf16_lossy(&buffer[..length])
}
