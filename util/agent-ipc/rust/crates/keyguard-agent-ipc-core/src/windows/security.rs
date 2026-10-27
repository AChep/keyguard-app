//! The owner-only pipe DACL and the same-user peer check.
//!
//! Copied, tokio-free, from `commonAgent/src/windows.rs` (plan §4.1, §5.1 items 4
//! and 9):
//!
//! - The current user's SID, from `OpenProcessToken` and `TokenUser` with a token
//!   buffer bounded at 1 MiB, cached as bytes.
//! - The descriptor `O:<sid>D:P(A;;GA;;;<sid>)` through
//!   `ConvertStringSecurityDescriptorToSecurityDescriptorW`, freed with `LocalFree`
//!   when the listener drops.
//! - The peer snapshot: `GetNamedPipeClientProcessId`, then
//!   `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`,
//!   `OpenProcessToken(TOKEN_QUERY)` and `TokenUser`, then `EqualSid` with the
//!   cached SID. The result is the pid (`> 0`) or a fixed failure reason. The
//!   decision takes the expected SID as an argument so tests can inject another.

use std::{
    iter,
    mem::{align_of, size_of},
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    ptr, slice,
};

use windows_sys::{
    Win32::{
        Foundation::{ERROR_INSUFFICIENT_BUFFER, GetLastError, HANDLE, LocalFree},
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
                SDDL_REVISION_1,
            },
            EqualSid, GetLengthSid, GetTokenInformation, IsValidSid, PSECURITY_DESCRIPTOR, PSID,
            SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER, TokenUser,
        },
        System::{
            Pipes::GetNamedPipeClientProcessId,
            Threading::{
                GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION,
            },
        },
    },
    core::PWSTR,
};

use crate::{Error, Failure, Result};

/// Largest `TokenUser` answer accepted.
const MAX_TOKEN_INFORMATION_BYTES: usize = 1 << 20;
/// `SECURITY_MAX_SID_SIZE`.
const MAX_SID_BYTES: usize = 68;
/// The documented maximum SID string is below this bound, in UTF-16 units.
const MAX_SID_STRING_UNITS: usize = 256;

/// A valid SID copied out of a token, in 4-byte aligned storage.
pub(super) struct Sid {
    words: Box<[u32]>,
}

impl Sid {
    /// The user of this process's token.
    pub(super) fn current_user() -> Result<Self> {
        let mut token = ptr::null_mut();
        // SAFETY: GetCurrentProcess is a pseudo-handle, and `token` is writable.
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return Err(Failure::last_os_error(
                Error::Io,
                "listen",
                "open_process_token",
            ));
        }
        // SAFETY: OpenProcessToken succeeded and transferred this handle.
        let token = unsafe { OwnedHandle::from_raw_handle(token) };
        token_user(&token).map_err(|code| failure(Error::Io, "listen", "token_user", code))
    }

    /// Copies a valid SID; `None` when `sid` is null, invalid or too long.
    ///
    /// # Safety
    ///
    /// A non-null `sid` points to memory that stays readable for the call.
    pub(super) unsafe fn copy(sid: PSID) -> Option<Self> {
        // SAFETY: Forwarded from this function's contract; null is checked first.
        if sid.is_null() || unsafe { IsValidSid(sid) } == 0 {
            return None;
        }
        // SAFETY: IsValidSid accepted this SID.
        let length = unsafe { GetLengthSid(sid) } as usize;
        if !(8..=MAX_SID_BYTES).contains(&length) {
            return None;
        }
        let mut words = vec![0_u32; length.div_ceil(size_of::<u32>())].into_boxed_slice();
        // SAFETY: The valid SID has `length` readable bytes, `words` has room for at
        // least as many, and the two never overlap.
        unsafe {
            ptr::copy_nonoverlapping(sid.cast::<u8>(), words.as_mut_ptr().cast::<u8>(), length);
        }
        Some(Self { words })
    }

    fn as_psid(&self) -> PSID {
        // The SID APIs take a mutable pointer but only read through it.
        self.words.as_ptr().cast_mut().cast()
    }

    fn matches(&self, other: &Self) -> bool {
        // SAFETY: Both SIDs were validated when copied and stay alive.
        unsafe { EqualSid(self.as_psid(), other.as_psid()) != 0 }
    }

    /// The canonical SDDL form, `S-1-…`.
    fn sddl(&self) -> Result<String> {
        let mut string: PWSTR = ptr::null_mut();
        // SAFETY: The SID is valid and `string` is writable; Windows allocates the
        // result with LocalAlloc.
        if unsafe { ConvertSidToStringSidW(self.as_psid(), &mut string) } == 0 || string.is_null() {
            return Err(Failure::last_os_error(Error::Io, "listen", "sid_string"));
        }
        let string = LocalAllocation(string.cast());
        let units = string.0.cast::<u16>();
        let length = (0..MAX_SID_STRING_UNITS)
            // SAFETY: The string is NUL-terminated; the scan stops at its terminator or
            // the bound.
            .find(|&index| unsafe { *units.add(index) } == 0)
            .ok_or(Failure::new(Error::Io, "listen", "sid_string"))?;
        // SAFETY: The scan proved these `length` units readable.
        let units = unsafe { slice::from_raw_parts(units, length) };
        String::from_utf16(units).map_err(|_| Failure::new(Error::Io, "listen", "sid_string"))
    }
}

/// A `LocalAlloc` allocation from a Windows security API, freed on drop.
struct LocalAllocation(*mut core::ffi::c_void);

impl Drop for LocalAllocation {
    fn drop(&mut self) {
        // SAFETY: A security API allocated this pointer with LocalAlloc, and this
        // owner frees it once.
        unsafe { LocalFree(self.0) };
    }
}

/// The self-relative descriptor `O:<sid>D:P(A;;GA;;;<sid>)`: the user owns the pipe
/// and is its only trustee, and the DACL inherits nothing.
pub(super) struct SecurityDescriptor(LocalAllocation);

// SAFETY: The descriptor is never changed after creation; CreateNamedPipeW only
// reads it, from any thread.
unsafe impl Send for SecurityDescriptor {}
// SAFETY: As above.
unsafe impl Sync for SecurityDescriptor {}

impl SecurityDescriptor {
    pub(super) fn owner_only(user: &Sid) -> Result<Self> {
        let sid = user.sddl()?;
        let sddl: Vec<u16> = format!("O:{sid}D:P(A;;GA;;;{sid})")
            .encode_utf16()
            .chain(iter::once(0))
            .collect();
        let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
        // SAFETY: `sddl` is NUL-terminated and alive for the call, and `descriptor`
        // is writable.
        let converted = unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                ptr::null_mut(),
            )
        };
        if converted == 0 || descriptor.is_null() {
            return Err(Failure::last_os_error(
                Error::Io,
                "listen",
                "security_descriptor",
            ));
        }
        Ok(Self(LocalAllocation(descriptor)))
    }

    /// Attributes for `CreateNamedPipeW`: this descriptor, and a handle that is not
    /// inheritable. They are valid while `self` is.
    pub(super) fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.0.0,
            bInheritHandle: 0,
        }
    }
}

/// The kernel's view of the client of the connected `pipe`: its pid when it runs
/// as `user`, else `Permission` with the reason.
pub(super) fn peer(pipe: &OwnedHandle, user: &Sid) -> Result<u32> {
    let mut pid = 0;
    // SAFETY: The pipe is open and connected, and `pid` is writable.
    if unsafe { GetNamedPipeClientProcessId(pipe.as_raw_handle(), &mut pid) } == 0 {
        return Err(Failure::last_os_error(
            Error::Permission,
            "peer_pid",
            "client_pid",
        ));
    }
    if pid == 0 {
        return Err(Failure::new(Error::Permission, "peer_pid", "client_pid"));
    }
    // SAFETY: OpenProcess takes a plain pid and returns null or a new handle.
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if process.is_null() {
        return Err(Failure::last_os_error(
            Error::Permission,
            "peer_pid",
            "open_process",
        ));
    }
    // SAFETY: OpenProcess succeeded and transferred this handle.
    let process = unsafe { OwnedHandle::from_raw_handle(process) };
    let mut token = ptr::null_mut();
    // SAFETY: The process handle is open with query access, and `token` is writable.
    if unsafe { OpenProcessToken(process.as_raw_handle(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(Failure::last_os_error(
            Error::Permission,
            "peer_pid",
            "open_process_token",
        ));
    }
    // SAFETY: OpenProcessToken succeeded and transferred this handle.
    let token = unsafe { OwnedHandle::from_raw_handle(token) };
    let peer = token_user(&token)
        .map_err(|code| failure(Error::Permission, "peer_pid", "token_user", code))?;
    if !peer.matches(user) {
        return Err(Failure::new(Error::Permission, "peer_pid", "user_mismatch"));
    }
    Ok(pid)
}

fn failure(
    kind: Error,
    operation: &'static str,
    reason: &'static str,
    code: Option<u32>,
) -> Failure {
    let failure = Failure::new(kind, operation, reason);
    match code {
        Some(code) => failure.with_os_code(code as i32),
        None => failure,
    }
}

/// Replaces the DACL of the pipe behind `handle`, opened with `WRITE_DAC`, by one
/// that grants `user` only `FILE_GENERIC_READ`: no new instance can be created.
#[cfg(test)]
pub(super) fn deny_new_instances(handle: &impl AsRawHandle, user: &Sid) {
    use windows_sys::Win32::Security::{
        Authorization::{SE_FILE_OBJECT, SetSecurityInfo},
        DACL_SECURITY_INFORMATION, GetSecurityDescriptorDacl, PROTECTED_DACL_SECURITY_INFORMATION,
    };

    let sid = user.sddl().unwrap();
    let sddl: Vec<u16> = format!("D:P(A;;FR;;;{sid})")
        .encode_utf16()
        .chain(iter::once(0))
        .collect();
    let mut descriptor = ptr::null_mut();
    // SAFETY: `sddl` is NUL-terminated and `descriptor` is writable.
    let converted = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            ptr::null_mut(),
        )
    };
    assert_ne!(converted, 0);
    let descriptor = LocalAllocation(descriptor);
    let (mut present, mut defaulted, mut acl) = (0, 0, ptr::null_mut());
    // SAFETY: The descriptor is valid and every output is writable.
    let read =
        unsafe { GetSecurityDescriptorDacl(descriptor.0, &mut present, &mut acl, &mut defaulted) };
    assert!(read != 0 && present != 0);
    // SAFETY: The handle is open with WRITE_DAC, and the ACL lives in `descriptor`.
    let status = unsafe {
        SetSecurityInfo(
            handle.as_raw_handle(),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            ptr::null_mut(),
            ptr::null_mut(),
            acl,
            ptr::null(),
        )
    };
    assert_eq!(status, 0);
}

/// 16-byte aligned storage for token information.
#[repr(C, align(16))]
#[derive(Clone, Copy)]
struct Block([u8; 16]);

/// The user SID of `token`; the error carries the OS code, if any.
fn token_user(token: &OwnedHandle) -> std::result::Result<Sid, Option<u32>> {
    let token: HANDLE = token.as_raw_handle();
    let mut required = 0_u32;
    // SAFETY: A sizing call: a null buffer of length 0, and a writable length.
    let sized = unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut required) };
    if sized == 0 {
        // SAFETY: No preconditions; read right after the failed call.
        let code = unsafe { GetLastError() };
        if code != ERROR_INSUFFICIENT_BUFFER {
            return Err(Some(code));
        }
    }
    let length = required as usize;
    if !(size_of::<TOKEN_USER>()..=MAX_TOKEN_INFORMATION_BYTES).contains(&length) {
        return Err(None);
    }
    const { assert!(align_of::<TOKEN_USER>() <= align_of::<Block>()) };
    let mut buffer = vec![Block([0; 16]); length.div_ceil(size_of::<Block>())];
    // SAFETY: `buffer` is aligned for TOKEN_USER and has `required` writable bytes.
    let queried = unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            required,
            &mut required,
        )
    };
    if queried == 0 {
        // SAFETY: No preconditions; read right after the failed call.
        return Err(Some(unsafe { GetLastError() }));
    }
    // SAFETY: TokenUser wrote a TOKEN_USER at the start of the aligned buffer, which
    // is at least that large.
    let user = unsafe { buffer.as_ptr().cast::<TOKEN_USER>().read() };
    // SAFETY: The SID points into `buffer`, which is alive for the copy.
    unsafe { Sid::copy(user.User.Sid) }.ok_or(None)
}
