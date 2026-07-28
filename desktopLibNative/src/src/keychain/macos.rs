use std::ffi::c_char;

unsafe extern "C" {
    fn kg_keychain_add_password(id: *const c_char, password: *const c_char) -> bool;
    fn kg_keychain_get_password(id: *const c_char) -> *mut c_char;
    fn kg_keychain_delete_password(id: *const c_char) -> bool;
    fn kg_keychain_contains_password(id: *const c_char) -> bool;
}

pub(crate) fn add_password(id: *const c_char, password: *const c_char) -> bool {
    // SAFETY: The exported FFI contract supplies both pointers as readable,
    // NUL-terminated strings. The shim converts them synchronously and does
    // not retain either pointer.
    unsafe { kg_keychain_add_password(id, password) }
}

pub(crate) fn get_password(id: *const c_char) -> *mut c_char {
    // SAFETY: The exported FFI contract supplies a readable, NUL-terminated
    // id. The shim consumes it synchronously and returns either null or a new
    // strdup allocation whose ownership is transferred to the caller.
    unsafe { kg_keychain_get_password(id) }
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    // SAFETY: The exported FFI contract supplies id as a readable,
    // NUL-terminated string; the shim converts it before returning and does
    // not retain the pointer.
    unsafe { kg_keychain_delete_password(id) }
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    // SAFETY: The exported FFI contract supplies id as a readable,
    // NUL-terminated string; the shim converts it before returning and does
    // not retain the pointer.
    unsafe { kg_keychain_contains_password(id) }
}
