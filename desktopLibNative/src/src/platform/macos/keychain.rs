use std::ffi::c_char;

unsafe extern "C" {
    fn kg_keychain_add_password(id: *const c_char, password: *const c_char) -> bool;
    fn kg_keychain_get_password(id: *const c_char) -> *mut c_char;
    fn kg_keychain_delete_password(id: *const c_char) -> bool;
    fn kg_keychain_contains_password(id: *const c_char) -> bool;
}

pub(crate) fn add_password(id: *const c_char, password: *const c_char) -> bool {
    unsafe { kg_keychain_add_password(id, password) }
}

pub(crate) fn get_password(id: *const c_char) -> *mut c_char {
    unsafe { kg_keychain_get_password(id) }
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    unsafe { kg_keychain_delete_password(id) }
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    unsafe { kg_keychain_contains_password(id) }
}
