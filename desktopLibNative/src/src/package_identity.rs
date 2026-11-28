//! Package identity of the running Windows launcher.

use jni::{objects::JObject, sys::jstring, EnvUnowned};
use keyguard_ffi::{with_java_env, PanicHook};
use std::{ffi::OsString, os::windows::ffi::OsStringExt, path::PathBuf, ptr};
use windows::{
    core::PWSTR,
    Win32::{
        Foundation::{ERROR_INSUFFICIENT_BUFFER, ERROR_SUCCESS, WIN32_ERROR},
        Storage::Packaging::Appx::{GetCurrentPackageFamilyName, GetCurrentPackagePath},
    },
};

fn package_string(query: unsafe fn(*mut u32, Option<PWSTR>) -> WIN32_ERROR) -> Option<Vec<u16>> {
    let mut length = 0;
    // SAFETY: Both package queries accept a null buffer for sizing.
    if unsafe { query(&mut length, None) } != ERROR_INSUFFICIENT_BUFFER {
        return None;
    }
    let mut value = vec![0; length as usize];
    // SAFETY: value has room for the requested number of UTF-16 code units.
    if unsafe { query(&mut length, Some(PWSTR(value.as_mut_ptr()))) } != ERROR_SUCCESS {
        return None;
    }
    value.truncate(length as usize);
    value.pop();
    Some(value)
}

fn family_name() -> Option<String> {
    let root = PathBuf::from(OsString::from_wide(&package_string(GetCurrentPackagePath)?));
    let executable = std::env::current_exe().ok()?;
    // An unpackaged launcher can inherit the identity of the packaged process that started it.
    if !executable.starts_with(&root)
        && !executable
            .canonicalize()
            .ok()?
            .starts_with(root.canonicalize().ok()?)
    {
        return None;
    }
    String::from_utf16(&package_string(GetCurrentPackageFamilyName)?).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_WindowsPackageIdentityJni_familyName(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jstring {
    keyguard_ffi::contained(PanicHook::Redact, || {
        with_java_env(&mut environment, |environment| {
            family_name()
                .and_then(|name| environment.new_string(name).ok())
                .map_or(ptr::null_mut(), |name| name.into_raw())
        })
    })
    .unwrap_or(ptr::null_mut())
}
