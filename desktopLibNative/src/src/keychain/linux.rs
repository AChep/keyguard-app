//! Linux's protected credential is accessed only by the biometric transform.
//! Keep contains/delete compatible with the desktop repository, but never
//! expose its bytes through the unauthenticated string keychain API.

use super::memory::store;
use crate::ffi;
use std::ffi::c_char;
use std::ptr;

const BIOMETRIC_ID: &str = "com.artemchep.keyguard.biometric-unlock";

pub(crate) fn add_password(_id: *const c_char, _password: *const c_char) -> bool {
    false
}

pub(crate) fn get_password(_id: *const c_char) -> *mut c_char {
    ptr::null_mut()
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    if is_biometric_id(id) {
        store().remove();
        true
    } else {
        false
    }
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    is_biometric_id(id) && store().contains()
}

fn is_biometric_id(id: *const c_char) -> bool {
    // SAFETY: The FFI contract supplies a readable NUL-terminated id for
    // this call. require_string rejects null and invalid UTF-8.
    unsafe { ffi::require_string(id, "id") }.is_ok_and(|id| id == BIOMETRIC_ID)
}

#[cfg(test)]
mod tests {
    #[test]
    fn legacy_string_api_cannot_import_or_export_credentials() {
        assert!(!super::add_password(c"id".as_ptr(), c"secret".as_ptr()));
        assert!(super::get_password(c"id".as_ptr()).is_null());
    }
}
