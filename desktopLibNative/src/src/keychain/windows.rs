use crate::ffi::require_string;
use std::ffi::{c_char, c_void};
use std::ptr;

type Bool = i32;
type Dword = u32;

const CRED_TYPE_GENERIC: Dword = 1;
const CRED_PERSIST_LOCAL_MACHINE: Dword = 2;
const TARGET_PREFIX: &str = "com.artemchep.keyguard/";
const USER_NAME: &str = "com.artemchep.keyguard";

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct FileTime {
    low_date_time: Dword,
    high_date_time: Dword,
}

#[repr(C)]
struct CredentialW {
    flags: Dword,
    credential_type: Dword,
    target_name: *mut u16,
    comment: *mut u16,
    last_written: FileTime,
    credential_blob_size: Dword,
    credential_blob: *mut u8,
    persist: Dword,
    attribute_count: Dword,
    attributes: *mut c_void,
    target_alias: *mut u16,
    user_name: *mut u16,
}

#[link(name = "Advapi32")]
unsafe extern "system" {
    fn CredWriteW(credential: *const CredentialW, flags: Dword) -> Bool;
    fn CredReadW(
        target_name: *const u16,
        credential_type: Dword,
        flags: Dword,
        credential: *mut *mut CredentialW,
    ) -> Bool;
    fn CredDeleteW(target_name: *const u16, credential_type: Dword, flags: Dword) -> Bool;
    fn CredFree(buffer: *const c_void);
}

pub(crate) fn add_password(id: *const c_char, password: *const c_char) -> bool {
    let Ok(id) = require_string(id, "id") else {
        return false;
    };
    let Ok(password) = require_string(password, "password") else {
        return false;
    };
    let mut target = wide(&target_name(&id));
    let mut user_name = wide(USER_NAME);
    let mut blob = password.into_bytes();
    let credential = CredentialW {
        flags: 0,
        credential_type: CRED_TYPE_GENERIC,
        target_name: target.as_mut_ptr(),
        comment: ptr::null_mut(),
        last_written: FileTime::default(),
        credential_blob_size: blob.len() as Dword,
        credential_blob: blob.as_mut_ptr(),
        persist: CRED_PERSIST_LOCAL_MACHINE,
        attribute_count: 0,
        attributes: ptr::null_mut(),
        target_alias: ptr::null_mut(),
        user_name: user_name.as_mut_ptr(),
    };

    // SAFETY: Every pointer in `credential` either targets a live contiguous
    // buffer for the duration of the call or is explicitly null as permitted
    // by CREDENTIALW. Sizes exactly match the supplied byte buffers.
    let success = unsafe { CredWriteW(&credential, 0) != 0 };
    blob.fill(0);
    success
}

pub(crate) fn get_password(id: *const c_char) -> *mut c_char {
    let Ok(id) = require_string(id, "id") else {
        return ptr::null_mut();
    };
    let target = wide(&target_name(&id));
    let Some((credential, mut value)) = read_password(&target) else {
        return ptr::null_mut();
    };

    let result = duplicate_for_ffi(&value).unwrap_or(ptr::null_mut());
    value.fill(0);
    wipe_and_free_credential(credential);
    result
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    let Ok(id) = require_string(id, "id") else {
        return false;
    };
    let target = wide(&target_name(&id));
    // SAFETY: `target` is NUL-terminated and remains alive for this call; the
    // credential type and flags follow the Win32 contract.
    unsafe { CredDeleteW(target.as_ptr(), CRED_TYPE_GENERIC, 0) != 0 }
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    let Ok(id) = require_string(id, "id") else {
        return false;
    };
    let target = wide(&target_name(&id));
    let Some((credential, mut value)) = read_password(&target) else {
        return false;
    };
    value.fill(0);
    wipe_and_free_credential(credential);
    true
}

fn read_password(target: &[u16]) -> Option<(*mut CredentialW, Vec<u8>)> {
    let mut credential = ptr::null_mut();
    // SAFETY: `target` is a live NUL-terminated UTF-16 slice and `credential`
    // is a valid out pointer. A successful allocation is later freed by caller.
    let success = unsafe { CredReadW(target.as_ptr(), CRED_TYPE_GENERIC, 0, &mut credential) != 0 };
    if !success || credential.is_null() {
        return None;
    }

    // SAFETY: `credential` is non-null and came from a successful CredReadW,
    // so its fields are readable until the matching CredFree below or in caller.
    let (blob_pointer, blob_size) = unsafe {
        (
            (*credential).credential_blob,
            (*credential).credential_blob_size as usize,
        )
    };
    if blob_size > 0 && blob_pointer.is_null() {
        wipe_and_free_credential(credential);
        return None;
    }
    let blob = if blob_size == 0 {
        Vec::new()
    } else {
        // SAFETY: CredReadW supplied a non-null blob readable for `blob_size`
        // bytes until the credential is freed.
        unsafe { std::slice::from_raw_parts(blob_pointer, blob_size).to_vec() }
    };
    Some((credential, blob))
}

fn wipe_and_free_credential(credential: *mut CredentialW) {
    if credential.is_null() {
        return;
    }
    // SAFETY: `credential` must be a still-owned CredReadW result. Its blob is
    // writable for the declared size until CredFree, which is called once here.
    unsafe {
        if !(*credential).credential_blob.is_null() {
            ptr::write_bytes(
                (*credential).credential_blob,
                0,
                (*credential).credential_blob_size as usize,
            );
        }
        CredFree(credential.cast());
    }
}

fn target_name(id: &str) -> String {
    format!("{TARGET_PREFIX}{id}")
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

fn duplicate_for_ffi(value: &[u8]) -> Option<*mut c_char> {
    let allocation_size = value.len().checked_add(1)?;
    // SAFETY: malloc receives the exact positive byte count needed for this
    // byte slice plus NUL terminator, and its result is checked before use.
    let allocation = unsafe { libc::malloc(allocation_size) }.cast::<c_char>();
    if allocation.is_null() {
        return None;
    }
    // SAFETY: `allocation` is writable for `allocation_size` bytes, the source
    // remains readable for the copy, and the final byte is in bounds.
    unsafe {
        ptr::copy_nonoverlapping(value.as_ptr().cast::<c_char>(), allocation, value.len());
        *allocation.add(value.len()) = 0;
    }
    Some(allocation)
}

#[cfg(test)]
mod tests {
    use super::{
        add_password, contains_password, delete_password, duplicate_for_ffi, get_password,
        target_name, wide,
    };
    use std::ffi::{CStr, CString};

    #[test]
    fn target_is_namespaced_to_keyguard() {
        assert_eq!(
            target_name("biometric_unlock"),
            "com.artemchep.keyguard/biometric_unlock"
        );
    }

    #[test]
    fn wide_strings_are_null_terminated() {
        assert_eq!(wide("hello"), vec![104, 101, 108, 108, 111, 0]);
    }

    #[test]
    fn ffi_string_uses_the_c_allocator() {
        let pointer = duplicate_for_ffi(b"hello").unwrap();
        // SAFETY: duplicate_for_ffi returned a non-null NUL-terminated copy.
        let actual = unsafe { CStr::from_ptr(pointer) }.to_str().unwrap();
        assert_eq!(actual, "hello");
        // SAFETY: The pointer was allocated by libc::malloc and is freed once.
        unsafe {
            libc::free(pointer.cast());
        }
    }

    #[test]
    fn credential_manager_round_trip() {
        let id = CString::new("keyguard_windows_test").unwrap();
        let password = CString::new("temporary-test-value").unwrap();

        let added = add_password(id.as_ptr(), password.as_ptr());
        let contained = contains_password(id.as_ptr());
        let actual_pointer = get_password(id.as_ptr());
        let actual = if actual_pointer.is_null() {
            None
        } else {
            // SAFETY: get_password returns either null or a NUL-terminated C
            // allocation owned by the caller.
            let value = unsafe { CStr::from_ptr(actual_pointer) }
                .to_string_lossy()
                .into_owned();
            // SAFETY: The pointer was allocated by libc::malloc and is freed once.
            unsafe {
                libc::free(actual_pointer.cast());
            }
            Some(value)
        };
        let deleted = delete_password(id.as_ptr());

        assert!(added);
        assert!(contained);
        assert_eq!(actual.as_deref(), Some("temporary-test-value"));
        assert!(deleted);
        assert!(!contains_password(id.as_ptr()));
    }
}
